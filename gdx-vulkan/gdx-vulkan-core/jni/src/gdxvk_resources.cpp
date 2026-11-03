#include "gdxvk_resources.h"

#include <algorithm>

#include "gdxvk_deletion.h"
#include "gdxvk_staging.h"
#include "gdxvk_timeline.h"

namespace gdxvk {

namespace {
constexpr uint64_t kPoolSize = 32ull << 20;
}

Resources::Resources(ContextHandles& h) : h_(h) {}

Resources::~Resources() {
    // The context has waited for the device and flushed the deletion queue only after us, so run it now: it owns the
    // destructors for everything still retired.
    h_.deletion->flushAll();
    for (TextureEntry& t : textures_) {
        if (!t.live) continue;
        vkDestroyImageView(h_.device, t.view, nullptr);
        vmaDestroyImage(h_.allocator, t.image, t.alloc);
    }
    for (Pool& p : pools_) {
        vmaDestroyVirtualBlock(p.block);
        vmaDestroyBuffer(h_.allocator, p.buffer, p.alloc);
    }
}

// ---------------------------------------------------------------- buffers

int Resources::createBuffer(uint64_t size) {
    if (size == 0) return -1;
    const uint64_t align = std::max<uint64_t>(h_.limits.minStorageBufferOffsetAlignment, 16);
    std::lock_guard<std::mutex> lock(mutex_);

    VmaVirtualAllocationCreateInfo ci{};
    ci.size = size;
    ci.alignment = align;
    BufferEntry e;
    bool placed = false;
    for (uint32_t i = 0; i < pools_.size() && !placed; ++i) {
        if (vmaVirtualAllocate(pools_[i].block, &ci, &e.allocation, &e.offset) == VK_SUCCESS) {
            e.pool = i;
            placed = true;
        }
    }
    if (!placed) {
        Pool p;
        p.size = std::max(kPoolSize, size);
        VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bi.size = p.size;
        bi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT |
                   VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        VmaAllocationCreateInfo ai{};
        ai.usage = VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
        if (vmaCreateBuffer(h_.allocator, &bi, &ai, &p.buffer, &p.alloc, nullptr) != VK_SUCCESS) return -1;
        VmaVirtualBlockCreateInfo vi{};
        vi.size = p.size;
        if (vmaCreateVirtualBlock(&vi, &p.block) != VK_SUCCESS) {
            vmaDestroyBuffer(h_.allocator, p.buffer, p.alloc);
            return -1;
        }
        pools_.push_back(p);
        if (vmaVirtualAllocate(p.block, &ci, &e.allocation, &e.offset) != VK_SUCCESS) return -1;
        e.pool = static_cast<uint32_t>(pools_.size() - 1);
    }
    e.live = true;
    e.size = size;
    int id;
    if (!freeBufferIds_.empty()) {
        id = freeBufferIds_.back();
        freeBufferIds_.pop_back();
        buffers_[id] = e;
    } else {
        id = static_cast<int>(buffers_.size());
        buffers_.push_back(e);
    }
    bufferBytesLive_ += size;
    ++buffersLive_;
    return id;
}

bool Resources::writeBuffer(int id, uint64_t offset, const void* data, uint64_t size) {
    VkBuffer dst;
    uint64_t base, total;
    if (!bufferInfo(id, dst, base, total) || offset + size > total) return false;
    return h_.staging->uploadBuffer(data, size, dst, base + offset);
}

bool Resources::bufferInfo(int id, VkBuffer& buffer, uint64_t& offset, uint64_t& size) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (id < 0 || static_cast<size_t>(id) >= buffers_.size() || !buffers_[id].live) return false;
    const BufferEntry& e = buffers_[id];
    buffer = pools_[e.pool].buffer;
    offset = e.offset;
    size = e.size;
    return true;
}

void Resources::destroyBuffer(int id) {
    BufferEntry e;
    VmaVirtualBlock block;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (id < 0 || static_cast<size_t>(id) >= buffers_.size() || !buffers_[id].live) return;
        e = buffers_[id];
        block = pools_[e.pool].block;
        buffers_[id].live = false;
        freeBufferIds_.push_back(id);
        bufferBytesLive_ -= e.size;
        --buffersLive_;
    }
    // The id is reusable now; the bytes are not, until the GPU has finished with every submit that could read them.
    // One serial later than usual (see DeletionQueue::retire): a copy into this range may still be queued for the next frame, and the
    // range must not be handed to a new owner, whose copy would race it, until that frame has completed.
    h_.deletion->retire(
        [this, block, e]() {
            std::lock_guard<std::mutex> lock(mutex_);
            vmaVirtualFree(block, e.allocation);
        },
        1);
}

// --------------------------------------------------------------- textures

bool Resources::bindSampler(TextureEntry& t, const SamplerKey& key, int* newSlot, DescriptorLayouts::TextureSet* newSet) {
    VkSampler sampler = h_.samplers->get(key);
    if (sampler == VK_NULL_HANDLE) return false;
    if (h_.tierA) {
        const int slot = h_.layouts->allocSlot();
        if (slot < 0) return false;
        h_.layouts->writeSlot(slot, t.view, sampler);
        *newSlot = slot;
    } else {
        DescriptorLayouts::TextureSet set;
        if (!h_.layouts->allocTextureSet(set)) return false;
        h_.layouts->writeTextureSet(set, t.view, sampler);
        *newSet = set;
    }
    return true;
}

void Resources::retireSampler(int slot, DescriptorLayouts::TextureSet set) {
    DescriptorLayouts* layouts = h_.layouts;
    if (h_.tierA) {
        if (slot >= 0) h_.deletion->retire([layouts, slot]() { layouts->freeSlot(slot); });
    } else if (set.set != VK_NULL_HANDLE) {
        h_.deletion->retire([layouts, set]() { layouts->freeTextureSet(set); });
    }
}

int Resources::createTexture(uint32_t width, uint32_t height, const void* rgba, const SamplerKey& sampler) {
    if (width == 0 || height == 0 || rgba == nullptr) return -1;
    TextureEntry t;

    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = VK_FORMAT_R8G8B8A8_UNORM;
    ii.extent = {width, height, 1};
    ii.mipLevels = 1;
    ii.arrayLayers = 1;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VmaAllocationCreateInfo ai{};
    ai.usage = VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    if (vmaCreateImage(h_.allocator, &ii, &ai, &t.image, &t.alloc, nullptr) != VK_SUCCESS) return -1;

    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = t.image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = ii.format;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vkCreateImageView(h_.device, &vi, nullptr, &t.view) != VK_SUCCESS) {
        vmaDestroyImage(h_.allocator, t.image, t.alloc);
        return -1;
    }
    auto abandon = [&]() {
        vkDestroyImageView(h_.device, t.view, nullptr);
        vmaDestroyImage(h_.allocator, t.image, t.alloc);
        return -1;
    };
    if (!bindSampler(t, sampler, &t.slot, &t.set)) return abandon();
    if (!h_.staging->uploadImage(rgba, static_cast<VkDeviceSize>(width) * height * 4, t.image, width, height)) {
        retireSampler(t.slot, t.set);
        return abandon();
    }

    std::lock_guard<std::mutex> lock(mutex_);
    t.live = true;
    int id;
    if (!freeTextureIds_.empty()) {
        id = freeTextureIds_.back();
        freeTextureIds_.pop_back();
        textures_[id] = t;
    } else {
        id = static_cast<int>(textures_.size());
        textures_.push_back(t);
    }
    ++texturesLive_;
    return id;
}

bool Resources::setTextureSampler(int id, const SamplerKey& sampler) {
    TextureEntry copy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (id < 0 || static_cast<size_t>(id) >= textures_.size() || !textures_[id].live) return false;
        copy = textures_[id];
    }
    int newSlot = -1;
    DescriptorLayouts::TextureSet newSet;
    if (!bindSampler(copy, sampler, &newSlot, &newSet)) return false;
    int oldSlot;
    DescriptorLayouts::TextureSet oldSet;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!textures_[id].live) { // destroyed meanwhile: give the new binding back
            retireSampler(newSlot, newSet);
            return false;
        }
        oldSlot = textures_[id].slot;
        oldSet = textures_[id].set;
        textures_[id].slot = newSlot;
        textures_[id].set = newSet;
    }
    retireSampler(oldSlot, oldSet);
    return true;
}

void Resources::destroyTexture(int id) {
    TextureEntry t;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (id < 0 || static_cast<size_t>(id) >= textures_.size() || !textures_[id].live) return;
        t = textures_[id];
        textures_[id].live = false;
        freeTextureIds_.push_back(id);
        --texturesLive_;
    }
    retireSampler(t.slot, t.set);
    VkDevice device = h_.device;
    VmaAllocator allocator = h_.allocator;
    // The upload recorded for this image may belong to the next frame, so wait one submit longer than usual (see retire()).
    h_.deletion->retire(
        [device, allocator, t]() {
            vkDestroyImageView(device, t.view, nullptr);
            vmaDestroyImage(allocator, t.image, t.alloc);
        },
        1);
}

bool Resources::textureBinding(int id, int* slot, VkDescriptorSet* set) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (id < 0 || static_cast<size_t>(id) >= textures_.size() || !textures_[id].live) return false;
    *slot = textures_[id].slot;
    *set = textures_[id].set.set;
    return true;
}

// ---------------------------------------------------------------- uploads

bool Resources::flushUploads() {
    VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pi.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    pi.queueFamilyIndex = h_.graphicsQueueFamily;
    VkCommandPool pool;
    if (vkCreateCommandPool(h_.device, &pi, nullptr, &pool) != VK_SUCCESS) return false;
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    VkCommandBuffer cmd;
    bool ok = vkAllocateCommandBuffers(h_.device, &ai, &cmd) == VK_SUCCESS;
    if (ok) {
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &bi);
        const bool any = h_.staging->recordPending(cmd);
        vkEndCommandBuffer(cmd);
        // Always submit, even when empty: retired resources are freed once a later serial completes, and without a running
        // presenter this is the only thing that advances the serial.
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        uint64_t serial = 0;
        ok = h_.timeline->submit(si, &serial) == VK_SUCCESS;
        if (ok) {
            if (any) h_.staging->markSubmitted(serial);
            ok = h_.timeline->waitSerial(serial) == VK_SUCCESS;
        }
    }
    vkDestroyCommandPool(h_.device, pool, nullptr);
    h_.staging->collect(h_.timeline->completed());
    return ok;
}

Resources::Stats Resources::stats() {
    Stats s;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        s.buffersLive = buffersLive_;
        s.texturesLive = texturesLive_;
        s.bufferBytesLive = bufferBytesLive_;
        for (const Pool& p : pools_) s.poolBytes += p.size;
    }
    s.slotsInUse = h_.layouts->slotsInUse();
    s.samplers = static_cast<uint32_t>(h_.samplers->size());
    s.stagingBlocks = static_cast<uint32_t>(h_.staging->blockCount());
    s.retiredPending = h_.deletion->pending();
    s.totalRetired = h_.deletion->totalRetired();
    s.completedSerial = h_.timeline->completed();
    VmaTotalStatistics total;
    vmaCalculateStatistics(h_.allocator, &total);
    s.vmaBlockBytes = total.total.statistics.blockBytes;
    s.vmaAllocationBytes = total.total.statistics.allocationBytes;
    return s;
}

} // namespace gdxvk
