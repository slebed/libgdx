#include "gdxvk_staging.h"

#include <algorithm>
#include <cstring>
#include <memory>

namespace gdxvk {

namespace {
constexpr VkDeviceSize kAlign = 16; // also a multiple of every texel size we upload
constexpr size_t kMaxFreeBlocks = 4;
constexpr VkPipelineStageFlags kShaderStages = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT |
                                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
constexpr VkPipelineStageFlags kReadStages = kShaderStages | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
VkDeviceSize alignUp(VkDeviceSize v, VkDeviceSize a) { return (v + a - 1) & ~(a - 1); }
uint32_t levelExtent(uint32_t base, uint32_t level) { return std::max(1u, base >> level); }

VkImageMemoryBarrier imageBarrier(VkImage image, VkImageLayout from, VkImageLayout to, VkAccessFlags src, VkAccessFlags dst,
                                  uint32_t baseLevel, uint32_t levelCount) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = src;
    b.dstAccessMask = dst;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, baseLevel, levelCount, 0, 1};
    return b;
}

void barrier(VkCommandBuffer cmd, VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage, const VkImageMemoryBarrier& b) {
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
}

// Transfer write -> transfer write (a copy followed by another copy into the same texels).
void transferToTransfer(VkCommandBuffer cmd) {
    VkMemoryBarrier m{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    m.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    m.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &m, 0, nullptr, 0, nullptr);
}
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
    Request r;
    r.kind = Request::BufferCopy;
    r.src = b->buffer;
    r.srcOffset = off;
    r.size = size;
    r.dstBuffer = dst;
    r.dstOffset = dstOffset;
    requests_.push_back(std::move(r));
    return true;
}

bool StagingRing::queueImage(Request::Kind kind, const void* data, VkDeviceSize size, VkImage image, VkImage oldImage, uint32_t width,
                             uint32_t height, uint32_t levels, uint32_t copyLevels, const std::vector<ImageRegion>& regions,
                             bool generateMips, VkFilter blitFilter) {
    std::lock_guard<std::mutex> lock(mutex_);
    Block* b = nullptr;
    VkDeviceSize off = 0;
    Request r;
    r.kind = kind;
    if (size > 0) {
        if (!allocSlice(size, b, off)) return false;
        std::memcpy(b->ptr + off, data, static_cast<size_t>(size));
        vmaFlushAllocation(h_.allocator, b->alloc, off, size);
        r.src = b->buffer;
    }
    r.srcOffset = off;
    r.size = size;
    r.image = image;
    r.oldImage = oldImage;
    r.width = width;
    r.height = height;
    r.levels = levels;
    r.copyLevels = copyLevels;
    r.regions = regions;
    r.generateMips = generateMips;
    r.blitFilter = blitFilter;
    requests_.push_back(std::move(r));
    return true;
}

bool StagingRing::uploadImage(const void* data, VkDeviceSize size, VkImage image, uint32_t width, uint32_t height, uint32_t levels,
                              const std::vector<ImageRegion>& regions, bool generateMips, VkFilter blitFilter) {
    return queueImage(Request::ImageUpload, data, size, image, VK_NULL_HANDLE, width, height, levels, 0, regions, generateMips, blitFilter);
}

bool StagingRing::updateImage(const void* data, VkDeviceSize size, VkImage newImage, VkImage oldImage, uint32_t width, uint32_t height,
                              uint32_t levels, uint32_t copyLevels, const std::vector<ImageRegion>& regions, bool generateMips,
                              VkFilter blitFilter) {
    return queueImage(Request::ImageUpdate, data, size, newImage, oldImage, width, height, levels, copyLevels, regions, generateMips,
                      blitFilter);
}

// Records one image request in full: layout transitions, the copies, optional mip generation, and the final layout.
void StagingRing::recordImage(VkCommandBuffer cmd, const Request& r) {
    const bool update = r.kind == Request::ImageUpdate;
    if (update) {
        // The old image is sampled by draws recorded earlier on this queue; wait for them, then read it as a copy source.
        barrier(cmd, kShaderStages, VK_PIPELINE_STAGE_TRANSFER_BIT,
                imageBarrier(r.oldImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 0,
                             VK_ACCESS_TRANSFER_READ_BIT, 0, VK_REMAINING_MIP_LEVELS));
    }
    // Transfer writes by earlier submits (a recycled block) before ours; execution dependency on earlier reads.
    barrier(cmd, kReadStages | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            imageBarrier(r.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                         r.levels));

    if (update) {
        for (uint32_t l = 0; l < r.copyLevels; ++l) {
            VkImageCopy c{};
            c.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, l, 0, 1};
            c.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, l, 0, 1};
            c.extent = {levelExtent(r.width, l), levelExtent(r.height, l), 1};
            vkCmdCopyImage(cmd, r.oldImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, r.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &c);
        }
        if (r.copyLevels > 0 && !r.regions.empty()) transferToTransfer(cmd); // our regions overwrite what was just copied
    }
    for (const ImageRegion& g : r.regions) {
        VkBufferImageCopy c{};
        c.bufferOffset = r.srcOffset + g.dataOffset;
        c.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, g.level, 0, 1};
        c.imageOffset = {static_cast<int32_t>(g.x), static_cast<int32_t>(g.y), 0};
        c.imageExtent = {g.width, g.height, 1};
        vkCmdCopyBufferToImage(cmd, r.src, r.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &c);
    }

    if (r.generateMips && r.levels > 1) {
        for (uint32_t l = 1; l < r.levels; ++l) {
            // Level l-1 is final: make it a blit source (its writes, from a copy or the previous blit, become visible).
            barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    imageBarrier(r.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                 VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, l - 1, 1));
            VkImageBlit blit{};
            blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, l - 1, 0, 1};
            blit.srcOffsets[1] = {static_cast<int32_t>(levelExtent(r.width, l - 1)), static_cast<int32_t>(levelExtent(r.height, l - 1)), 1};
            blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, l, 0, 1};
            blit.dstOffsets[1] = {static_cast<int32_t>(levelExtent(r.width, l)), static_cast<int32_t>(levelExtent(r.height, l)), 1};
            vkCmdBlitImage(cmd, r.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, r.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit,
                           r.blitFilter);
        }
        // Levels 0..n-2 are blit sources, the last one is still a destination.
        VkImageMemoryBarrier toRead[2] = {
            imageBarrier(r.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_READ_BIT,
                         VK_ACCESS_SHADER_READ_BIT, 0, r.levels - 1),
            imageBarrier(r.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                         VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, r.levels - 1, 1)};
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, kShaderStages, 0, 0, nullptr, 0, nullptr, 2, toRead);
    } else {
        barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, kShaderStages,
                imageBarrier(r.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                             VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, 0, r.levels));
    }
    if (update) {
        // Draws recorded later this frame may still sample the old image through its old slot.
        barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, kShaderStages,
                imageBarrier(r.oldImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                             VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT, 0, VK_REMAINING_MIP_LEVELS));
    }
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

    // Leading barrier for buffer copies: earlier submits may still be reading a recycled sub-allocation, or still be writing it.
    // Reads need only the execution dependency; earlier transfer writes also need to be made available before ours.
    VkMemoryBarrier lead{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    lead.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    lead.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, kReadStages | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &lead, 0, nullptr, 0,
                         nullptr);

    // Buffer copies and image requests touch different resources, so each keeps its own order but they need not interleave.
    for (const Request& r : reqs) {
        if (r.kind != Request::BufferCopy) continue;
        VkBufferCopy c{r.srcOffset, r.dstOffset, r.size};
        vkCmdCopyBuffer(cmd, r.src, r.dstBuffer, 1, &c);
    }
    // Runs of plain first uploads (no mip generation, no rename) share one pair of barrier calls: fewer commands for the driver, and
    // far less work for the validation layer, whose cost grows with the number of barriers recorded.
    std::vector<const Request*> run;
    auto flushRun = [&]() {
        if (run.empty()) return;
        std::vector<VkImageMemoryBarrier> toDst, toRead;
        for (const Request* r : run) {
            toDst.push_back(imageBarrier(r->image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                                         r->levels));
            toRead.push_back(imageBarrier(r->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                          VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, 0, r->levels));
        }
        vkCmdPipelineBarrier(cmd, kReadStages | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr,
                             static_cast<uint32_t>(toDst.size()), toDst.data());
        for (const Request* r : run) {
            for (const ImageRegion& g : r->regions) {
                VkBufferImageCopy c{};
                c.bufferOffset = r->srcOffset + g.dataOffset;
                c.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, g.level, 0, 1};
                c.imageOffset = {static_cast<int32_t>(g.x), static_cast<int32_t>(g.y), 0};
                c.imageExtent = {g.width, g.height, 1};
                vkCmdCopyBufferToImage(cmd, r->src, r->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &c);
            }
        }
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, kShaderStages, 0, 0, nullptr, 0, nullptr, static_cast<uint32_t>(toRead.size()),
                             toRead.data());
        run.clear();
    };
    for (const Request& r : reqs) {
        if (r.kind == Request::BufferCopy) continue;
        if (r.kind == Request::ImageUpload && !r.generateMips) {
            run.push_back(&r);
        } else {
            flushRun(); // keep image requests in the order they were queued
            recordImage(cmd, r);
        }
    }
    flushRun();

    VkMemoryBarrier trail{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    trail.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    trail.dstAccessMask = VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT |
                          VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, kReadStages, 0, 1, &trail, 0, nullptr, 0, nullptr);
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
