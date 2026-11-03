// Per-frame dynamic ring for Set 0 (plan, Phase 1b): persistently mapped host memory behind the UNIFORM_BUFFER_DYNAMIC and
// STORAGE_BUFFER_DYNAMIC bindings. One FrameRing per frame in flight; reset() once that frame has finished on the GPU.
// Offsets are aligned to the device's min*BufferOffsetAlignment. When a block is exhausted a larger one is added, and a new
// descriptor set is written for it, so the data already written this frame stays valid.
#pragma once

#include <cstdint>
#include <vector>

#include "gdxvk_internal.h"

namespace gdxvk {

class FrameRing {
public:
    explicit FrameRing(const ContextHandles& h, VkDeviceSize uniformBlock = 1u << 20, VkDeviceSize storageBlock = 4u << 20);
    ~FrameRing();
    FrameRing(const FrameRing&) = delete;
    FrameRing& operator=(const FrameRing&) = delete;

    struct Binding {
        VkDescriptorSet set = VK_NULL_HANDLE;
        uint32_t offsets[2] = {0, 0}; // dynamic offsets: binding 0 (uniform), binding 1 (storage)
    };

    // Copies the bytes into the rings and returns what to bind as set 0. A size of 0 binds the start of the current block.
    // Fails (false) when the uniform data exceeds the 16 KB descriptor range or memory runs out.
    bool allocate(const void* uniform, uint32_t uniformBytes, const void* storage, uint64_t storageBytes, Binding& out);

    // Vertex/index data for the command stream: host-visible blocks, persistently mapped, addressed as (block, offset). Java writes
    // into the mapped memory directly. Growth appends a block, so earlier (block, offset) pairs stay valid for the frame.
    bool allocVertex(uint64_t bytes, uint32_t* block, uint64_t* offset);
    uint8_t* vertexBlockPtr(uint32_t block, uint64_t* size);
    VkBuffer vertexBlockBuffer(uint32_t block) const;
    void flushVertices(uint32_t block, uint64_t offset, uint64_t bytes);

    // The frame this ring belonged to has completed: rewind, keep only the largest block of each kind, reset the pool.
    void reset();

    size_t blockCount() const { return uniform_.size() + storage_.size(); }

private:
    struct Block {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation alloc = nullptr;
        uint8_t* ptr = nullptr;
        VkDeviceSize size = 0, offset = 0;
    };
    bool addBlock(std::vector<Block>& list, VkBufferUsageFlags usage, VkDeviceSize size);
    void destroyBlock(const Block& b);
    bool writeSet(VkDescriptorSet& set);

    const ContextHandles& h_;
    VkDeviceSize uniformBlock_, storageBlock_;
    std::vector<Block> uniform_, storage_, vertex_; // the last element of each is current
    VkDescriptorPool pool_ = VK_NULL_HANDLE;
    VkDescriptorSet set_ = VK_NULL_HANDLE; // describes (uniform_.back(), storage_.back()) at the time it was written
    VkBuffer setUniform_ = VK_NULL_HANDLE, setStorage_ = VK_NULL_HANDLE;
};

} // namespace gdxvk
