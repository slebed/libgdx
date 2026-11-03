// GPU buffers and 2D textures behind small integer ids, thread-safe, with deferred deletion (plan, Phase 1b). This is the native
// half of what the GL20 shim will sit on in Phase 2; the stress test drives it directly.
//
//  - Buffers are sub-allocated from large device-local pools (VmaVirtualBlock). Writes go through the staging ring.
//    destroyBuffer() returns the range to the pool only after the GPU is done with it.
//  - Textures are R8G8B8A8_UNORM images with one mip. Tier A gives each a slot in the global table, Tier B a descriptor set.
//    Changing the sampler allocates a new slot/set and retires the old one (plan, Core decision 4: copy-on-write renaming).
#pragma once

#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>

#include "gdxvk_caches.h"
#include "gdxvk_layout.h"

namespace gdxvk {

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

    // ---- textures ----
    int createTexture(uint32_t width, uint32_t height, const void* rgba, const SamplerKey& sampler); // -1 on failure
    bool setTextureSampler(int id, const SamplerKey& sampler);
    void destroyTexture(int id);
    // Tier A: *slot is the table index for u_indices.y. Tier B: *set is the descriptor set to bind as set 1.
    bool textureBinding(int id, int* slot, VkDescriptorSet* set);

    // Uploads queued while no frame is being recorded (headless use, tests): records them in a one-off command buffer,
    // submits and waits. A running Presenter does this every frame instead.
    bool flushUploads();

    struct Stats {
        uint32_t buffersLive = 0, texturesLive = 0, slotsInUse = 0, samplers = 0, stagingBlocks = 0;
        uint64_t bufferBytesLive = 0, poolBytes = 0, retiredPending = 0, totalRetired = 0, vmaBlockBytes = 0, vmaAllocationBytes = 0;
        uint64_t completedSerial = 0;
    };
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
        VmaVirtualAllocation allocation = nullptr;
        uint64_t offset = 0, size = 0;
    };
    struct TextureEntry {
        bool live = false;
        VkImage image = VK_NULL_HANDLE;
        VmaAllocation alloc = nullptr;
        VkImageView view = VK_NULL_HANDLE;
        int slot = -1;                        // Tier A
        DescriptorLayouts::TextureSet set;    // Tier B
    };

    bool bindSampler(TextureEntry& t, const SamplerKey& key, int* newSlot, DescriptorLayouts::TextureSet* newSet);
    void retireSampler(int slot, DescriptorLayouts::TextureSet set);

    ContextHandles& h_;
    std::mutex mutex_;
    std::deque<Pool> pools_; // stable addresses
    std::vector<BufferEntry> buffers_;
    std::vector<int> freeBufferIds_;
    std::vector<TextureEntry> textures_;
    std::vector<int> freeTextureIds_;
    uint64_t bufferBytesLive_ = 0;
    uint32_t buffersLive_ = 0, texturesLive_ = 0;
};

} // namespace gdxvk
