// GPU buffers and 2D textures behind small integer ids, thread-safe, with deferred deletion (plan, Phase 1b/2). The GL20 shim sits
// on this; the stress test drives it directly.
//
//  - Buffers are sub-allocated from large device-local pools (VmaVirtualBlock). Writes go through the staging ring.
//    destroyBuffer() returns the range to the pool only after the GPU is done with it.
//  - Textures are 2D images in one of three formats (see TexFormat) with one or more mip levels. Tier A gives each a slot in the
//    global table, Tier B a descriptor set. Any change after creation (sampler, region update, mip regeneration) allocates a new
//    slot/set, and for pixel changes a new image too, and retires the old ones (plan, Core decision 4: copy-on-write renaming).
#pragma once

#include <atomic>
#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>

#include "gdxvk_caches.h"
#include "gdxvk_layout.h"
#include "gdxvk_staging.h"

namespace gdxvk {

// Pixel formats the GL shim maps libGDX Pixmap formats onto (plan, Phase 2). Everything else is converted to Rgba8 on the CPU.
enum class TexFormat : int {
    Rgba8 = 0,            // R8G8B8A8_UNORM
    R8Alpha = 1,          // R8_UNORM, sampled as (0, 0, 0, R)   (GL_ALPHA)
    Rg8LuminanceAlpha = 2, // R8G8_UNORM, sampled as (R, R, R, G) (GL_LUMINANCE_ALPHA)
    Compressed = 3         // a block-compressed VkFormat given by TextureDesc::vkFormat (BC, ETC2, ASTC)
};

struct TextureDesc {
    uint32_t width = 0, height = 0;
    TexFormat format = TexFormat::Rgba8;
    // Compressed only: the VkFormat and its block geometry. A level of w x h texels takes ceil(w/blockW) * ceil(h/blockH) * blockBytes bytes.
    uint32_t vkFormat = 0, blockW = 4, blockH = 4, blockBytes = 8;
    uint32_t uploadedLevels = 1; // levels present in the data, packed level 0 first, no padding
    bool generateMips = false;   // build the rest of the chain by blitting (then uploadedLevels must be 1)
    // One level: the image can also be a colour attachment (a stock FrameBuffer's texture). It is never renamed. The data may be null: the image
    // is then only moved into its sampling layout and holds undefined contents until a pass writes it. Format Rgba8, or with `vkFormat` set
    // to R16G16B16A16_SFLOAT / R32G32B32A32_SFLOAT (a floating-point target, for HDR and multi-target framebuffers).
    bool renderTarget = false;
};

// What the rest of the code needs to know about a texture format: the Vulkan format, the image view swizzle, and the block geometry
// (1x1 blocks of bytes-per-pixel for the plain formats, so one formula sizes every level).
struct TexFormatInfo {
    VkFormat vk = VK_FORMAT_R8G8B8A8_UNORM;
    VkComponentMapping swizzle{VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
    uint32_t blockW = 1, blockH = 1, blockBytes = 4;
    bool compressed = false;
};
TexFormatInfo formatInfo(TexFormat f, const TextureDesc& desc);
inline uint64_t levelBytes(const TexFormatInfo& f, uint32_t w, uint32_t h) {
    return static_cast<uint64_t>((w + f.blockW - 1) / f.blockW) * ((h + f.blockH - 1) / f.blockH) * f.blockBytes;
}

class Resources {
public:
    explicit Resources(ContextHandles& h);
    ~Resources();
    Resources(const Resources&) = delete;
    Resources& operator=(const Resources&) = delete;

    // ---- buffers ----
    int createBuffer(uint64_t size); // -1 on failure
    bool writeBuffer(int id, uint64_t offset, const void* data, uint64_t size);
    void destroyBuffer(int id);
    bool bufferInfo(int id, VkBuffer& buffer, uint64_t& offset, uint64_t& size);
    // Copies a buffer range back to the host and waits for it: for tests and diagnostics, never for a frame (it stalls the GPU).
    bool readBuffer(int id, uint64_t offset, void* dst, uint64_t size);

    // ---- textures ----
    int createTexture(const TextureDesc& desc, const void* data, uint64_t dataBytes, const SamplerKey& sampler); // -1 on failure
    // Rewrites a rectangle of one level (tightly packed data). The texture is renamed: new image, slot/set, old ones retired.
    bool updateTexture(int id, uint32_t level, uint32_t x, uint32_t y, uint32_t w, uint32_t h, const void* data, uint64_t dataBytes);
    // Rebuilds levels 1.. from level 0 (glGenerateMipmap on an existing texture). Renames the texture, giving it a full chain.
    bool regenerateMips(int id);
    bool setTextureSampler(int id, const SamplerKey& sampler);
    void destroyTexture(int id);
    // Tier A: *slot is the table index for u_indices.y. Tier B: *set is the descriptor set to bind as set 1.
    bool textureBinding(int id, int* slot, VkDescriptorSet* set);
    bool textureInfo(int id, uint32_t* width, uint32_t* height, uint32_t* levels);
    // A render-target texture's image, view and size, for the presenter to draw into. False if it is not a render target.
    bool renderTargetInfo(int id, VkImage* image, VkImageView* view, VkFormat* format, uint32_t* width, uint32_t* height);
    // ---- depth/stencil images: the renderbuffers of a framebuffer (never sampled, never renamed) ----
    // One combined image: stencil adds a stencil aspect (D24_UNORM_S8_UINT, else D32_SFLOAT_S8_UINT); depth only is D16_UNORM for
    // 16 bits, else D32_SFLOAT. -1 on failure.
    // `transient`: an opt-in for a framebuffer whose depth is rebuilt every pass (cleared, never read across passes): the image is a transient
    // attachment in lazily allocated memory where the device has it (tile-based GPUs keep it on chip), and passes neither load nor store it.
    int createDepthImage(uint32_t width, uint32_t height, bool stencil, uint32_t depthBits, bool transient = false);
    void destroyDepthImage(int id);
    bool depthImageInfo(int id, VkImage* image, VkImageView* view, VkFormat* format, bool* hasStencil, bool* initialized, bool* transient);
    void markDepthInitialized(int id); // the image has been moved out of UNDEFINED into DEPTH_STENCIL_ATTACHMENT_OPTIMAL

    // Can this device sample (and linearly filter) images in the given VkFormat? Compressed formats vary by GPU: BC on desktop, ETC2/ASTC on mobile.
    bool formatSupported(uint32_t vkFormat);

    // Uploads queued while no frame is being recorded (headless use, tests): records them in a one-off command buffer,
    // submits and waits. A running Presenter does this every frame instead.
    bool flushUploads();

    struct Stats {
        uint32_t buffersLive = 0, texturesLive = 0, slotsInUse = 0, samplers = 0, stagingBlocks = 0;
        uint64_t bufferBytesLive = 0, poolBytes = 0, retiredPending = 0, totalRetired = 0, vmaBlockBytes = 0, vmaAllocationBytes = 0;
        uint64_t completedSerial = 0;
        uint64_t textureRenames = 0;
    };

    // Textures renamed since the context started: cheap, unlike stats(). A frame's renames are the difference between two readings.
    uint64_t renameCount() const { return renames_.load(std::memory_order_relaxed); }
    Stats stats();

private:
    struct Pool {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation alloc = nullptr;
        VmaVirtualBlock block = nullptr;
        uint64_t size = 0;
    };
    struct BufferEntry {
        bool live = false;
        uint32_t pool = 0;
        VmaVirtualAllocation allocation = VK_NULL_HANDLE; // a pointer on 64-bit targets, a 64-bit integer on 32-bit ones
        uint64_t offset = 0, size = 0;
    };
    struct TextureEntry {
        bool live = false;
        VkImage image = VK_NULL_HANDLE;
        VmaAllocation alloc = nullptr;
        VkImageView view = VK_NULL_HANDLE;
        uint32_t width = 0, height = 0, levels = 1;
        TexFormatInfo info;
        bool renderTarget = false;
        bool linearFilter = true; // the format can be filtered linearly (a 32-bit float target may not: its sampler is then forced to nearest)
        SamplerKey sampler;
        int slot = -1;                     // Tier A
        DescriptorLayouts::TextureSet set; // Tier B
    };

    bool makeImage(uint32_t w, uint32_t h, const TexFormatInfo& f, uint32_t levels, VkImage& image, VmaAllocation& alloc, VkImageView& view,
                   bool renderTarget = false);
    bool bindSampler(VkImageView view, const SamplerKey& key, int* newSlot, DescriptorLayouts::TextureSet* newSet);
    void retireBinding(int slot, DescriptorLayouts::TextureSet set);
    void retireImage(VkImage image, VmaAllocation alloc, VkImageView view);
    bool blitFilter(const TexFormatInfo& f, VkFilter* filter); // false if the format cannot be a blit source and destination

    ContextHandles& h_;
    std::mutex mutex_;
    std::deque<Pool> pools_; // stable addresses
    std::vector<BufferEntry> buffers_;
    std::vector<int> freeBufferIds_;
    std::vector<TextureEntry> textures_;
    std::vector<int> freeTextureIds_;
    struct DepthEntry {
        bool live = false, stencil = false, initialized = false, transient = false;
        VkImage image = VK_NULL_HANDLE;
        VmaAllocation alloc = nullptr;
        VkImageView view = VK_NULL_HANDLE;
        VkFormat format = VK_FORMAT_UNDEFINED;
    };
    std::vector<DepthEntry> depths_;
    std::vector<int> freeDepthIds_;
    uint64_t bufferBytesLive_ = 0;
    std::atomic<uint64_t> renames_{0}; // textures renamed (a new image after a pixel change), for the per-frame counter
    uint32_t buffersLive_ = 0, texturesLive_ = 0;
};

} // namespace gdxvk
