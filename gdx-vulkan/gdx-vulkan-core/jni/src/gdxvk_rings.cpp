#include "gdxvk_rings.h"

#include <algorithm>
#include <cstring>

#include "gdxvk_layout.h"

namespace gdxvk {

namespace {
constexpr uint32_t kMaxSetsPerFrame = 64; // one per block change; far more than a frame ever needs
VkDeviceSize alignUp(VkDeviceSize v, VkDeviceSize a) { return (v + a - 1) & ~(a - 1); }
} // namespace

FrameRing::FrameRing(const ContextHandles& h, VkDeviceSize uniformBlock, VkDeviceSize storageBlock)
    : h_(h), uniformBlock_(uniformBlock), storageBlock_(storageBlock) {
    VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, kMaxSetsPerFrame},
                                     {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC, kMaxSetsPerFrame}};
    VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pi.maxSets = kMaxSetsPerFrame;
    pi.poolSizeCount = 2;
    pi.pPoolSizes = sizes;
    if (vkCreateDescriptorPool(h_.device, &pi, nullptr, &pool_) != VK_SUCCESS) pool_ = VK_NULL_HANDLE;
    // The first block of each kind exists up front so a "size 0" binding always has something valid to point at.
    addBlock(uniform_, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, std::max<VkDeviceSize>(uniformBlock_, 2 * h_.layouts->uniformRange()));
    addBlock(storage_, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, storageBlock_);
    addBlock(vertex_, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT, 4u << 20);
}

FrameRing::~FrameRing() {
    for (const Block& b : uniform_) destroyBlock(b);
    for (const Block& b : storage_) destroyBlock(b);
    for (const Block& b : vertex_) destroyBlock(b);
    if (pool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(h_.device, pool_, nullptr);
}

bool FrameRing::addBlock(std::vector<Block>& list, VkBufferUsageFlags usage, VkDeviceSize size) {
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size;
    bi.usage = usage;
    VmaAllocationCreateInfo ai{};
    // Vertex and index data is written once per frame by the CPU and read once by the GPU: keep it in system memory. VMA's default
    // would pick the small host-visible device-local window (write-combined over PCIe), which the CPU fills several times slower
    // (measured on an RTX 5080: the vertex copy dominated a 20,000-sprite frame).
    ai.usage = (usage & VK_BUFFER_USAGE_VERTEX_BUFFER_BIT) ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST : VMA_MEMORY_USAGE_AUTO;
    // Random access asks for cached memory, which the Java side can also fill faster than write-combined memory.
    ai.flags = ((usage & VK_BUFFER_USAGE_VERTEX_BUFFER_BIT) ? VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT : VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT) |
               VMA_ALLOCATION_CREATE_MAPPED_BIT;
    VmaAllocationInfo info{};
    Block b;
    if (vmaCreateBuffer(h_.allocator, &bi, &ai, &b.buffer, &b.alloc, &info) != VK_SUCCESS) return false;
    b.ptr = static_cast<uint8_t*>(info.pMappedData);
    b.size = size;
    list.push_back(b);
    return true;
}

void FrameRing::destroyBlock(const Block& b) { vmaDestroyBuffer(h_.allocator, b.buffer, b.alloc); }

bool FrameRing::writeSet(VkDescriptorSet& set) {
    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    VkDescriptorSetLayout layout = h_.layouts->set0Layout();
    ai.descriptorPool = pool_;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &layout;
    if (vkAllocateDescriptorSets(h_.device, &ai, &set) != VK_SUCCESS) return false;
    VkDescriptorBufferInfo ub{uniform_.back().buffer, 0, h_.layouts->uniformRange()};
    VkDescriptorBufferInfo sb{storage_.back().buffer, 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet w[2] = {{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}, {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}};
    w[0].dstSet = set;
    w[0].dstBinding = 0;
    w[0].descriptorCount = 1;
    w[0].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
    w[0].pBufferInfo = &ub;
    w[1].dstSet = set;
    w[1].dstBinding = 1;
    w[1].descriptorCount = 1;
    w[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
    w[1].pBufferInfo = &sb;
    vkUpdateDescriptorSets(h_.device, 2, w, 0, nullptr);
    setUniform_ = uniform_.back().buffer;
    setStorage_ = storage_.back().buffer;
    return true;
}

bool FrameRing::allocate(const void* uniform, uint32_t uniformBytes, const void* storage, uint64_t storageBytes, Binding& out) {
    const uint32_t range = h_.layouts->uniformRange();
    if (uniformBytes > range || pool_ == VK_NULL_HANDLE || uniform_.empty() || storage_.empty()) return false;

    // Uniform: a dynamic offset is valid only while offset + range fits in the block, so the usable tail is range bytes shorter.
    uint32_t uniformOffset = 0;
    if (uniformBytes > 0) {
        const VkDeviceSize align = std::max<VkDeviceSize>(h_.limits.minUniformBufferOffsetAlignment, 16);
        Block* b = &uniform_.back();
        VkDeviceSize off = alignUp(b->offset, align);
        if (off + range > b->size) {
            if (!addBlock(uniform_, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, std::max<VkDeviceSize>(b->size * 2, 2 * range))) return false;
            b = &uniform_.back();
            off = 0;
        }
        std::memcpy(b->ptr + off, uniform, uniformBytes);
        vmaFlushAllocation(h_.allocator, b->alloc, off, uniformBytes);
        b->offset = off + uniformBytes;
        uniformOffset = static_cast<uint32_t>(off);
    }

    uint32_t storageOffset = 0;
    if (storageBytes > 0) {
        const VkDeviceSize align = std::max<VkDeviceSize>(h_.limits.minStorageBufferOffsetAlignment, 16);
        Block* b = &storage_.back();
        VkDeviceSize off = alignUp(b->offset, align);
        if (off + storageBytes > b->size) {
            if (!addBlock(storage_, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, std::max<VkDeviceSize>(b->size * 2, alignUp(storageBytes, 256)))) return false;
            b = &storage_.back();
            off = 0;
        }
        std::memcpy(b->ptr + off, storage, static_cast<size_t>(storageBytes));
        vmaFlushAllocation(h_.allocator, b->alloc, off, storageBytes);
        b->offset = off + storageBytes;
        storageOffset = static_cast<uint32_t>(off);
    }

    if (set_ == VK_NULL_HANDLE || setUniform_ != uniform_.back().buffer || setStorage_ != storage_.back().buffer) {
        if (!writeSet(set_)) return false;
    }
    out.set = set_;
    out.offsets[0] = uniformOffset;
    out.offsets[1] = storageOffset;
    return true;
}

bool FrameRing::externalStorageSet(VkBuffer buffer, VkDeviceSize offset, VkDeviceSize range, VkDescriptorSet& set) {
    if (pool_ == VK_NULL_HANDLE || uniform_.empty()) return false;
    const VkBuffer uniform = uniform_.back().buffer;
    for (const ExternalSet& e : external_) {
        if (e.buffer == buffer && e.offset == offset && e.range == range && e.uniform == uniform) {
            set = e.set;
            return true;
        }
    }
    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    VkDescriptorSetLayout layout = h_.layouts->set0Layout();
    ai.descriptorPool = pool_;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &layout;
    if (vkAllocateDescriptorSets(h_.device, &ai, &set) != VK_SUCCESS) return false;
    VkDescriptorBufferInfo ub{uniform, 0, h_.layouts->uniformRange()};
    VkDescriptorBufferInfo sb{buffer, offset, range};
    VkWriteDescriptorSet w[2] = {{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}, {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}};
    w[0].dstSet = set;
    w[0].dstBinding = 0;
    w[0].descriptorCount = 1;
    w[0].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
    w[0].pBufferInfo = &ub;
    w[1].dstSet = set;
    w[1].dstBinding = 1;
    w[1].descriptorCount = 1;
    w[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
    w[1].pBufferInfo = &sb;
    vkUpdateDescriptorSets(h_.device, 2, w, 0, nullptr);
    external_.push_back({buffer, uniform, offset, range, set});
    return true;
}

bool FrameRing::allocVertex(uint64_t bytes, uint32_t* block, uint64_t* offset) {
    if (vertex_.empty() || bytes == 0) return false;
    const VkBufferUsageFlags usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
    Block* b = &vertex_.back();
    VkDeviceSize off = alignUp(b->offset, 16);
    if (off + bytes > b->size) {
        if (!addBlock(vertex_, usage, std::max<VkDeviceSize>(b->size * 2, alignUp(bytes, 4096)))) return false;
        b = &vertex_.back();
        off = 0;
    }
    b->offset = off + bytes;
    *block = static_cast<uint32_t>(vertex_.size() - 1);
    *offset = off;
    return true;
}

uint8_t* FrameRing::vertexBlockPtr(uint32_t block, uint64_t* size) {
    if (block >= vertex_.size()) return nullptr;
    *size = vertex_[block].size;
    return vertex_[block].ptr;
}

VkBuffer FrameRing::vertexBlockBuffer(uint32_t block) const { return block < vertex_.size() ? vertex_[block].buffer : VK_NULL_HANDLE; }

void FrameRing::flushVertices(uint32_t block, uint64_t offset, uint64_t bytes) {
    if (block < vertex_.size()) vmaFlushAllocation(h_.allocator, vertex_[block].alloc, offset, bytes);
}

void FrameRing::reset() {
    // Keep the largest (newest) block of each kind; smaller ones were outgrown.
    while (uniform_.size() > 1) {
        destroyBlock(uniform_.front());
        uniform_.erase(uniform_.begin());
    }
    while (storage_.size() > 1) {
        destroyBlock(storage_.front());
        storage_.erase(storage_.begin());
    }
    while (vertex_.size() > 1) {
        destroyBlock(vertex_.front());
        vertex_.erase(vertex_.begin());
    }
    for (Block& b : vertex_) b.offset = 0;
    for (Block& b : uniform_) b.offset = 0;
    for (Block& b : storage_) b.offset = 0;
    if (pool_ != VK_NULL_HANDLE) vkResetDescriptorPool(h_.device, pool_, 0);
    set_ = VK_NULL_HANDLE;
    setUniform_ = setStorage_ = VK_NULL_HANDLE;
    external_.clear();
}

} // namespace gdxvk
