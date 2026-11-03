#include "gdxvk_staging.h"

#include <algorithm>
#include <cstring>
#include <memory>

namespace gdxvk {

namespace {
constexpr VkDeviceSize kAlign = 16; // also a multiple of every texel size we upload (4 bytes)
constexpr size_t kMaxFreeBlocks = 4;
constexpr VkPipelineStageFlags kShaderStages = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT |
                                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
constexpr VkPipelineStageFlags kReadStages = kShaderStages | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
VkDeviceSize alignUp(VkDeviceSize v, VkDeviceSize a) { return (v + a - 1) & ~(a - 1); }
} // namespace

StagingRing::StagingRing(ContextHandles& h, VkDeviceSize blockSize) : h_(h), blockSize_(blockSize) {}

StagingRing::~StagingRing() {
    for (auto* list : {&active_, &recording_, &inFlight_, &free_}) {
        for (Block* b : *list) destroyBlock(b);
    }
}

StagingRing::Block* StagingRing::newBlock(VkDeviceSize size) {
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size;
    bi.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    VmaAllocationCreateInfo ai{};
    ai.usage = VMA_MEMORY_USAGE_AUTO;
    ai.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT;
    VmaAllocationInfo info{};
    std::unique_ptr<Block> b(new Block());
    if (vmaCreateBuffer(h_.allocator, &bi, &ai, &b->buffer, &b->alloc, &info) != VK_SUCCESS) return nullptr;
    b->ptr = static_cast<uint8_t*>(info.pMappedData);
    b->size = size;
    return b.release();
}

void StagingRing::destroyBlock(Block* b) {
    vmaDestroyBuffer(h_.allocator, b->buffer, b->alloc);
    delete b;
}

bool StagingRing::allocSlice(VkDeviceSize size, Block*& block, VkDeviceSize& offset) {
    const VkDeviceSize need = alignUp(size, kAlign);
    if (active_.empty() || active_.back()->offset + need > active_.back()->size) {
        Block* b = nullptr;
        for (size_t i = 0; i < free_.size(); ++i) { // a recycled block big enough for this request
            if (free_[i]->size >= need) {
                b = free_[i];
                free_.erase(free_.begin() + static_cast<long>(i));
                break;
            }
        }
        if (!b) b = newBlock(std::max(blockSize_, need));
        if (!b) return false;
        b->offset = 0;
        active_.push_back(b);
    }
    block = active_.back();
    offset = block->offset;
    block->offset += need;
    return true;
}

bool StagingRing::uploadBuffer(const void* data, VkDeviceSize size, VkBuffer dst, VkDeviceSize dstOffset) {
    if (size == 0) return true;
    std::lock_guard<std::mutex> lock(mutex_);
    Block* b;
    VkDeviceSize off;
    if (!allocSlice(size, b, off)) return false;
    std::memcpy(b->ptr + off, data, static_cast<size_t>(size));
    vmaFlushAllocation(h_.allocator, b->alloc, off, size);
    requests_.push_back({b->buffer, off, size, dst, dstOffset, VK_NULL_HANDLE, 0, 0});
    return true;
}

bool StagingRing::uploadImage(const void* data, VkDeviceSize size, VkImage image, uint32_t width, uint32_t height) {
    std::lock_guard<std::mutex> lock(mutex_);
    Block* b;
    VkDeviceSize off;
    if (!allocSlice(size, b, off)) return false;
    std::memcpy(b->ptr + off, data, static_cast<size_t>(size));
    vmaFlushAllocation(h_.allocator, b->alloc, off, size);
    requests_.push_back({b->buffer, off, size, VK_NULL_HANDLE, 0, image, width, height});
    return true;
}

bool StagingRing::recordPending(VkCommandBuffer cmd) {
    std::vector<Request> reqs;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        reqs.swap(requests_);
        // Every block that handed out slices is now part of this submit, including the one still accepting allocations:
        // the next allocation opens a fresh block rather than appending to bytes the GPU is about to read.
        if (!reqs.empty()) {
            for (Block* b : active_) recording_.push_back(b);
            active_.clear();
        }
    }
    if (reqs.empty()) return false;

    std::vector<VkImageMemoryBarrier> toDst, toRead;
    for (const Request& r : reqs) {
        if (r.dstImage == VK_NULL_HANDLE) continue;
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = r.dstImage;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toDst.push_back(b);
        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        toRead.push_back(b);
    }

    // Leading barrier: earlier submits on this queue may still be reading what we are about to overwrite (a recycled sub-allocation),
    // or may still be writing it (their own upload). Reads need only the execution dependency; earlier transfer writes also need
    // to be made available before ours.
    VkMemoryBarrier lead{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    lead.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    lead.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, kReadStages | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &lead, 0, nullptr,
                         static_cast<uint32_t>(toDst.size()), toDst.data());

    for (const Request& r : reqs) {
        if (r.dstImage == VK_NULL_HANDLE) {
            VkBufferCopy c{r.srcOffset, r.dstOffset, r.size};
            vkCmdCopyBuffer(cmd, r.src, r.dstBuffer, 1, &c);
        } else {
            VkBufferImageCopy c{};
            c.bufferOffset = r.srcOffset;
            c.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            c.imageExtent = {r.width, r.height, 1};
            vkCmdCopyBufferToImage(cmd, r.src, r.dstImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &c);
        }
    }

    VkMemoryBarrier trail{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    trail.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    trail.dstAccessMask = VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT |
                          VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, kReadStages, 0, 1, &trail, 0, nullptr, static_cast<uint32_t>(toRead.size()),
                         toRead.data());
    return true;
}

void StagingRing::markSubmitted(uint64_t serial) {
    std::lock_guard<std::mutex> lock(mutex_);
    for (Block* b : recording_) {
        b->serial = serial;
        inFlight_.push_back(b);
    }
    recording_.clear();
}

void StagingRing::collect(uint64_t completedSerial) {
    std::vector<Block*> surplus;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        size_t keep = 0;
        for (Block* b : inFlight_) {
            if (b->serial <= completedSerial) {
                b->offset = 0;
                free_.push_back(b);
            } else {
                inFlight_[keep++] = b;
            }
        }
        inFlight_.resize(keep);
        while (free_.size() > kMaxFreeBlocks) { // bursts grow the ring; do not keep the memory forever
            surplus.push_back(free_.back());
            free_.pop_back();
        }
    }
    for (Block* b : surplus) destroyBlock(b);
}

size_t StagingRing::blockCount() {
    std::lock_guard<std::mutex> lock(mutex_);
    return active_.size() + recording_.size() + inFlight_.size() + free_.size();
}

size_t StagingRing::pendingCopies() {
    std::lock_guard<std::mutex> lock(mutex_);
    return requests_.size();
}

} // namespace gdxvk
