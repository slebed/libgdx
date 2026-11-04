#include <cstring>
#include "gdxvk_resources.h"

#include <algorithm>

#include "gdxvk_deletion.h"
#include "gdxvk_staging.h"
#include "gdxvk_timeline.h"

namespace gdxvk {

namespace {
constexpr uint64_t kPoolSize = 32ull << 20;

uint32_t levelExtent(uint32_t base, uint32_t level) { return std::max(1u, base >> level); }

uint32_t fullChain(uint32_t w, uint32_t h) {
    uint32_t n = 1;
    for (uint32_t m = std::max(w, h); m > 1; m >>= 1) ++n;
    return n;
}
} // namespace

TexFormatInfo formatInfo(TexFormat f, const TextureDesc& desc) {
    TexFormatInfo i;
    switch (f) {
        case TexFormat::R8Alpha: // GL_ALPHA: rgb 0, alpha from the single channel
            i.vk = VK_FORMAT_R8_UNORM;
            i.swizzle = {VK_COMPONENT_SWIZZLE_ZERO, VK_COMPONENT_SWIZZLE_ZERO, VK_COMPONENT_SWIZZLE_ZERO, VK_COMPONENT_SWIZZLE_R};
            i.blockBytes = 1;
            break;
        case TexFormat::Rg8LuminanceAlpha: // GL_LUMINANCE_ALPHA: rgb = luminance, alpha = second channel
            i.vk = VK_FORMAT_R8G8_UNORM;
            i.swizzle = {VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_G};
            i.blockBytes = 2;
            break;
        case TexFormat::Compressed:
            i.vk = static_cast<VkFormat>(desc.vkFormat);
            i.blockW = desc.blockW;
            i.blockH = desc.blockH;
            i.blockBytes = desc.blockBytes;
            i.compressed = true;
            break;
        default: break; // Rgba8
    }
    return i;
}

Resources::Resources(ContextHandles& h) : h_(h) {}

Resources::~Resources() {
    // The context has waited for the device and flushes the deletion queue only after us, so run it now: it owns the
    // destructors for everything still retired.
    h_.deletion->flushAll();
    for (TextureEntry& t : textures_) {
        if (!t.live) continue;
        vkDestroyImageView(h_.device, t.view, nullptr);
        vmaDestroyImage(h_.allocator, t.image, t.alloc);
    }
    for (DepthEntry& d : depths_) {
        if (!d.live) continue;
        vkDestroyImageView(h_.device, d.view, nullptr);
        vmaDestroyImage(h_.allocator, d.image, d.alloc);
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
        bi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT /* readBuffer */ | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT |
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
        nameObject(h_, VK_OBJECT_TYPE_BUFFER, (uint64_t)p.buffer, ("gdx buffer pool " + std::to_string(pools_.size())).c_str());
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
    // The id is reusable now; the bytes are not, until the GPU has finished with every submit that could read them. One serial
    // later than usual (see DeletionQueue::retire): a copy into this range may still be queued for the next frame, and the range
    // must not be handed to a new owner, whose copy would race it, until that frame has completed.
    h_.deletion->retire(
        [this, block, e]() {
            std::lock_guard<std::mutex> lock(mutex_);
            vmaVirtualFree(block, e.allocation);
        },
        1);
}

// --------------------------------------------------------------- textures

bool Resources::makeImage(uint32_t w, uint32_t h, const TexFormatInfo& f, uint32_t levels, VkImage& image, VmaAllocation& alloc, VkImageView& view,
                        bool renderTarget) {
    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = f.vk;
    ii.extent = {w, h, 1};
    ii.mipLevels = levels;
    ii.arrayLayers = 1;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    // TRANSFER_SRC: blit chain for mips, and the source of a copy-on-write update.
    ii.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    if (renderTarget) ii.usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VmaAllocationCreateInfo ai{};
    ai.usage = VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    if (vmaCreateImage(h_.allocator, &ii, &ai, &image, &alloc, nullptr) != VK_SUCCESS) return false;

    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = ii.format;
    vi.components = f.swizzle;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, levels, 0, 1};
    if (vkCreateImageView(h_.device, &vi, nullptr, &view) != VK_SUCCESS) {
        vmaDestroyImage(h_.allocator, image, alloc);
        return false;
    }
    return true;
}

bool Resources::formatSupported(uint32_t vkFormat) {
    VkFormatProperties p;
    vkGetPhysicalDeviceFormatProperties(h_.physical, static_cast<VkFormat>(vkFormat), &p);
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    return (p.optimalTilingFeatures & need) == need;
}

bool Resources::blitFilter(const TexFormatInfo& f, VkFilter* filter) {
    if (f.compressed) return false; // no blitting into or out of block-compressed images
    VkFormatProperties p;
    vkGetPhysicalDeviceFormatProperties(h_.physical, f.vk, &p);
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
    if ((p.optimalTilingFeatures & need) != need) return false;
    *filter = (p.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    return true;
}

bool Resources::bindSampler(VkImageView view, const SamplerKey& key, int* newSlot, DescriptorLayouts::TextureSet* newSet) {
    VkSampler sampler = h_.samplers->get(key);
    if (sampler == VK_NULL_HANDLE) return false;
    if (h_.tierA) {
        const int slot = h_.layouts->allocSlot();
        if (slot < 0) return false;
        h_.layouts->writeSlot(slot, view, sampler);
        *newSlot = slot;
    } else {
        DescriptorLayouts::TextureSet set;
        if (!h_.layouts->allocTextureSet(set)) return false;
        h_.layouts->writeTextureSet(set, view, sampler);
        *newSet = set;
    }
    return true;
}

void Resources::retireBinding(int slot, DescriptorLayouts::TextureSet set) {
    DescriptorLayouts* layouts = h_.layouts;
    if (h_.tierA) {
        if (slot >= 0) h_.deletion->retire([layouts, slot]() { layouts->freeSlot(slot); });
    } else if (set.set != VK_NULL_HANDLE) {
        h_.deletion->retire([layouts, set]() { layouts->freeTextureSet(set); });
    }
}

void Resources::retireImage(VkImage image, VmaAllocation alloc, VkImageView view) {
    VkDevice device = h_.device;
    VmaAllocator allocator = h_.allocator;
    // A copy into this image may still be queued for the next frame, so wait one submit longer than usual (see retire()).
    h_.deletion->retire(
        [device, allocator, image, alloc, view]() {
            vkDestroyImageView(device, view, nullptr);
            vmaDestroyImage(allocator, image, alloc);
        },
        1);
}

// A format the device cannot filter linearly (a 32-bit float target on some GPUs) is sampled with nearest filtering, as GL does for
// textures that are not "texture complete" for linear sampling: a linear sampler on it would be invalid.
static SamplerKey clampFilters(SamplerKey k, bool linearOk) {
    if (!linearOk) {
        k.minFilter = k.magFilter = VK_FILTER_NEAREST;
        k.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    }
    return k;
}

int Resources::createTexture(const TextureDesc& d, const void* data, uint64_t dataBytes, const SamplerKey& sampler) {
    if (d.width == 0 || d.height == 0 || (data == nullptr && !d.renderTarget) || d.uploadedLevels == 0) return -1;
    if (d.generateMips && d.uploadedLevels != 1) return -1;
    if (d.renderTarget && (d.format != TexFormat::Rgba8 || d.generateMips || d.uploadedLevels != 1)) return -1;
    if (data == nullptr && dataBytes != 0) return -1;
    TexFormatInfo fi = formatInfo(d.format, d);
    bool linearOk = true;
    if (d.renderTarget && d.vkFormat != 0) { // a floating-point target
        const VkFormat vf = static_cast<VkFormat>(d.vkFormat);
        if (vf != VK_FORMAT_R16G16B16A16_SFLOAT && vf != VK_FORMAT_R32G32B32A32_SFLOAT) return -1;
        VkFormatProperties fp;
        vkGetPhysicalDeviceFormatProperties(h_.physical, vf, &fp);
        const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        if ((fp.optimalTilingFeatures & need) != need) return -1; // this device cannot render to it and sample it
        linearOk = (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
        fi.vk = vf;
        fi.blockBytes = vf == VK_FORMAT_R16G16B16A16_SFLOAT ? 8 : 16;
    }
    if (fi.compressed && (d.generateMips || d.blockW == 0 || d.blockH == 0 || d.blockBytes == 0 || !formatSupported(d.vkFormat))) return -1;
    const uint32_t levels = d.generateMips ? fullChain(d.width, d.height) : d.uploadedLevels;
    if (levels > fullChain(d.width, d.height)) return -1;

    std::vector<ImageRegion> regions;
    uint64_t offset = 0;
    for (uint32_t l = 0; data != nullptr && l < d.uploadedLevels; ++l) { // no data: the image only gets its sampling layout
        ImageRegion r;
        r.level = l;
        r.width = levelExtent(d.width, l);
        r.height = levelExtent(d.height, l);
        r.dataOffset = offset;
        offset += levelBytes(fi, r.width, r.height);
        regions.push_back(r);
    }
    if (offset != dataBytes) return -1;

    VkFilter filter = VK_FILTER_LINEAR;
    if (d.generateMips && !blitFilter(fi, &filter)) return -1;

    TextureEntry t;
    t.width = d.width;
    t.height = d.height;
    t.info = fi;
    t.levels = levels;
    t.linearFilter = linearOk;
    t.sampler = clampFilters(sampler, linearOk);
    t.renderTarget = d.renderTarget;
    if (!makeImage(d.width, d.height, fi, levels, t.image, t.alloc, t.view, d.renderTarget)) return -1;
    if (!bindSampler(t.view, t.sampler, &t.slot, &t.set)) {
        retireImage(t.image, t.alloc, t.view);
        return -1;
    }
    if (!h_.staging->uploadImage(data, dataBytes, t.image, d.width, d.height, levels, regions, d.generateMips, filter)) {
        retireBinding(t.slot, t.set);
        retireImage(t.image, t.alloc, t.view);
        return -1;
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
    nameObject(h_, VK_OBJECT_TYPE_IMAGE, (uint64_t)t.image, ("gdx texture " + std::to_string(id) + (d.renderTarget ? " (render target)" : "")).c_str());
    nameObject(h_, VK_OBJECT_TYPE_IMAGE_VIEW, (uint64_t)t.view, ("gdx texture " + std::to_string(id) + " view").c_str());
    return id;
}

bool Resources::updateTexture(int id, uint32_t level, uint32_t x, uint32_t y, uint32_t w, uint32_t h, const void* data, uint64_t dataBytes) {
    // Held throughout: the rename reads the entry, builds the replacement and swaps it in, and two updates of one texture must not
    // both rename the same old image.
    std::lock_guard<std::mutex> lock(mutex_);
    if (id < 0 || static_cast<size_t>(id) >= textures_.size() || !textures_[id].live) return false;
    TextureEntry& t = textures_[id];
    if (t.renderTarget) return false; // render targets are never renamed (plan, Core decision 4)
    if (level >= t.levels || w == 0 || h == 0) return false;
    if (x + w > levelExtent(t.width, level) || y + h > levelExtent(t.height, level)) return false;
    if (dataBytes != levelBytes(t.info, w, h)) return false;
    if (t.info.compressed) { // block-compressed updates must cover whole blocks (or run to the edge of the level)
        const uint32_t lw = levelExtent(t.width, level), lh = levelExtent(t.height, level);
        if (x % t.info.blockW != 0 || y % t.info.blockH != 0) return false;
        if ((w % t.info.blockW != 0 && x + w != lw) || (h % t.info.blockH != 0 && y + h != lh)) return false;
    }

    TextureEntry fresh = t;
    if (!makeImage(t.width, t.height, t.info, t.levels, fresh.image, fresh.alloc, fresh.view)) return false;
    fresh.slot = -1;
    fresh.set = DescriptorLayouts::TextureSet();
    if (!bindSampler(fresh.view, t.sampler, &fresh.slot, &fresh.set)) {
        retireImage(fresh.image, fresh.alloc, fresh.view);
        return false;
    }
    ImageRegion r;
    r.level = level;
    r.x = x;
    r.y = y;
    r.width = w;
    r.height = h;
    r.dataOffset = 0;
    if (!h_.staging->updateImage(data, dataBytes, fresh.image, t.image, t.width, t.height, t.levels, t.levels, {r}, false, VK_FILTER_NEAREST)) {
        retireBinding(fresh.slot, fresh.set);
        retireImage(fresh.image, fresh.alloc, fresh.view);
        return false;
    }
    const TextureEntry old = t;
    t = fresh;
    retireBinding(old.slot, old.set);
    retireImage(old.image, old.alloc, old.view);
    renames_.fetch_add(1, std::memory_order_relaxed);
    return true;
}

bool Resources::regenerateMips(int id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (id < 0 || static_cast<size_t>(id) >= textures_.size() || !textures_[id].live) return false;
    TextureEntry& t = textures_[id];
    if (t.renderTarget) return false;
    VkFilter filter;
    if (!blitFilter(t.info, &filter)) return false;
    const uint32_t levels = fullChain(t.width, t.height);

    TextureEntry fresh = t;
    fresh.levels = levels;
    if (!makeImage(t.width, t.height, t.info, levels, fresh.image, fresh.alloc, fresh.view)) return false;
    fresh.slot = -1;
    fresh.set = DescriptorLayouts::TextureSet();
    if (!bindSampler(fresh.view, t.sampler, &fresh.slot, &fresh.set)) {
        retireImage(fresh.image, fresh.alloc, fresh.view);
        return false;
    }
    // Copy level 0 from the old image and rebuild everything below it.
    if (!h_.staging->updateImage(nullptr, 0, fresh.image, t.image, t.width, t.height, levels, 1, {}, true, filter)) {
        retireBinding(fresh.slot, fresh.set);
        retireImage(fresh.image, fresh.alloc, fresh.view);
        return false;
    }
    const TextureEntry old = t;
    t = fresh;
    retireBinding(old.slot, old.set);
    retireImage(old.image, old.alloc, old.view);
    renames_.fetch_add(1, std::memory_order_relaxed);
    return true;
}

bool Resources::setTextureSampler(int id, const SamplerKey& sampler) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (id < 0 || static_cast<size_t>(id) >= textures_.size() || !textures_[id].live) return false;
    TextureEntry& t = textures_[id];
    int newSlot = -1;
    DescriptorLayouts::TextureSet newSet;
    const SamplerKey clamped = clampFilters(sampler, t.linearFilter);
    if (!bindSampler(t.view, clamped, &newSlot, &newSet)) return false;
    const int oldSlot = t.slot;
    const DescriptorLayouts::TextureSet oldSet = t.set;
    t.slot = newSlot;
    t.set = newSet;
    t.sampler = clamped;
    retireBinding(oldSlot, oldSet);
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
    retireBinding(t.slot, t.set);
    retireImage(t.image, t.alloc, t.view);
}

bool Resources::textureBinding(int id, int* slot, VkDescriptorSet* set) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (id < 0 || static_cast<size_t>(id) >= textures_.size() || !textures_[id].live) return false;
    *slot = textures_[id].slot;
    *set = textures_[id].set.set;
    return true;
}

bool Resources::renderTargetInfo(int id, VkImage* image, VkImageView* view, VkFormat* format, uint32_t* width, uint32_t* height) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (id < 0 || static_cast<size_t>(id) >= textures_.size() || !textures_[id].live || !textures_[id].renderTarget) return false;
    const TextureEntry& t = textures_[id];
    *image = t.image;
    *view = t.view;
    *format = t.info.vk;
    *width = t.width;
    *height = t.height;
    return true;
}

bool Resources::textureInfo(int id, uint32_t* width, uint32_t* height, uint32_t* levels) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (id < 0 || static_cast<size_t>(id) >= textures_.size() || !textures_[id].live) return false;
    *width = textures_[id].width;
    *height = textures_[id].height;
    *levels = textures_[id].levels;
    return true;
}

// ---------------------------------------------------------------- depth/stencil images

int Resources::createDepthImage(uint32_t w, uint32_t h, bool stencil, uint32_t depthBits, bool transient) {
    if (w == 0 || h == 0) return -1;
    auto usable = [&](VkFormat f) {
        VkFormatProperties p;
        vkGetPhysicalDeviceFormatProperties(h_.physical, f, &p);
        return (p.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0;
    };
    VkFormat fmt;
    if (stencil) { // AMD desktop GPUs have no D24S8 (plan, Phase 3)
        fmt = usable(VK_FORMAT_D24_UNORM_S8_UINT) ? VK_FORMAT_D24_UNORM_S8_UINT : VK_FORMAT_D32_SFLOAT_S8_UINT;
    } else {
        fmt = depthBits <= 16 ? VK_FORMAT_D16_UNORM : VK_FORMAT_D32_SFLOAT;
    }
    if (!usable(fmt)) return -1;

    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = fmt;
    ii.extent = {w, h, 1};
    ii.mipLevels = 1;
    ii.arrayLayers = 1;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    // Not transient: a framebuffer's depth survives between its passes (GL keeps it), so the passes load and store it.
    ii.usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | (transient ? VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT : 0u);
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VmaAllocationCreateInfo ai{};
    ai.usage = VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    if (transient) ai.preferredFlags = VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT; // on-chip on tile-based GPUs; ordinary memory elsewhere
    DepthEntry e;
    if (vmaCreateImage(h_.allocator, &ii, &ai, &e.image, &e.alloc, nullptr) != VK_SUCCESS) return -1;
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = e.image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = fmt;
    vi.subresourceRange = {static_cast<VkImageAspectFlags>(VK_IMAGE_ASPECT_DEPTH_BIT | (stencil ? VK_IMAGE_ASPECT_STENCIL_BIT : 0)), 0, 1, 0, 1};
    if (vkCreateImageView(h_.device, &vi, nullptr, &e.view) != VK_SUCCESS) {
        vmaDestroyImage(h_.allocator, e.image, e.alloc);
        return -1;
    }
    nameObject(h_, VK_OBJECT_TYPE_IMAGE, (uint64_t)e.image, transient ? "gdx depth image (transient)" : "gdx depth image");
    e.live = true;
    e.stencil = stencil;
    e.transient = transient;
    e.format = fmt;
    std::lock_guard<std::mutex> lock(mutex_);
    if (!freeDepthIds_.empty()) {
        const int id = freeDepthIds_.back();
        freeDepthIds_.pop_back();
        depths_[id] = e;
        return id;
    }
    depths_.push_back(e);
    return static_cast<int>(depths_.size() - 1);
}

void Resources::destroyDepthImage(int id) {
    DepthEntry e;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (id < 0 || static_cast<size_t>(id) >= depths_.size() || !depths_[id].live) return;
        e = depths_[id];
        depths_[id] = DepthEntry();
        freeDepthIds_.push_back(id);
    }
    retireImage(e.image, e.alloc, e.view);
}

bool Resources::depthImageInfo(int id, VkImage* image, VkImageView* view, VkFormat* format, bool* hasStencil, bool* initialized, bool* transient) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (id < 0 || static_cast<size_t>(id) >= depths_.size() || !depths_[id].live) return false;
    const DepthEntry& e = depths_[id];
    *image = e.image;
    *view = e.view;
    *format = e.format;
    *hasStencil = e.stencil;
    *initialized = e.initialized;
    *transient = e.transient;
    return true;
}

void Resources::markDepthInitialized(int id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (id >= 0 && static_cast<size_t>(id) < depths_.size() && depths_[id].live) depths_[id].initialized = true;
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

bool Resources::readBuffer(int id, uint64_t offset, void* dst, uint64_t size) {
    VkBuffer src;
    uint64_t base, total;
    if (!bufferInfo(id, src, base, total) || offset + size > total || size == 0) return false;
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size;
    bi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    VmaAllocationCreateInfo ai{};
    ai.usage = VMA_MEMORY_USAGE_AUTO;
    ai.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT;
    VkBuffer host;
    VmaAllocation alloc;
    VmaAllocationInfo info{};
    if (vmaCreateBuffer(h_.allocator, &bi, &ai, &host, &alloc, &info) != VK_SUCCESS) return false;
    VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pi.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    pi.queueFamilyIndex = h_.graphicsQueueFamily;
    VkCommandPool pool;
    bool ok = vkCreateCommandPool(h_.device, &pi, nullptr, &pool) == VK_SUCCESS;
    if (ok) {
        VkCommandBufferAllocateInfo ci{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ci.commandPool = pool;
        ci.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ci.commandBufferCount = 1;
        VkCommandBuffer cmd;
        ok = vkAllocateCommandBuffers(h_.device, &ci, &cmd) == VK_SUCCESS;
        if (ok) {
            VkCommandBufferBeginInfo bgn{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            bgn.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            vkBeginCommandBuffer(cmd, &bgn);
            const bool any = h_.staging->recordPending(cmd); // writes queued so far land first
            VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0,
                                 nullptr, 0, nullptr);
            const VkBufferCopy region{base + offset, 0, size};
            vkCmdCopyBuffer(cmd, src, host, 1, &region);
            VkMemoryBarrier hb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            hb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            // The host buffer is destroyed afterwards and its memory is handed to the next readback: make this write available to
            // that copy as well (the validation layer tracks the memory range across the destroyed buffer).
            hb.dstAccessMask = VK_ACCESS_HOST_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &hb, 0,
                                 nullptr, 0, nullptr);
            vkEndCommandBuffer(cmd);
            VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            si.commandBufferCount = 1;
            si.pCommandBuffers = &cmd;
            uint64_t serial = 0;
            ok = h_.timeline->submit(si, &serial) == VK_SUCCESS;
            if (ok) {
                if (any) h_.staging->markSubmitted(serial);
                ok = h_.timeline->waitSerial(serial) == VK_SUCCESS;
            }
            if (ok) {
                vmaInvalidateAllocation(h_.allocator, alloc, 0, size);
                std::memcpy(dst, info.pMappedData, static_cast<size_t>(size));
            }
        }
        vkDestroyCommandPool(h_.device, pool, nullptr);
    }
    vmaDestroyBuffer(h_.allocator, host, alloc);
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
    s.textureRenames = renames_.load(std::memory_order_relaxed);
    return s;
}

} // namespace gdxvk
