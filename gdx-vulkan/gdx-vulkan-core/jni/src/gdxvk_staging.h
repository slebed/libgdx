// Mutex-guarded staging ring (plan, Phase 1b): any thread copies bytes in and queues the transfer; the render thread records
// every pending copy at the start of the frame command buffer. No second queue, no ownership transfers.
//
// Blocks are persistently mapped host memory. A block is reused only once the submit that read it has completed
// (Timeline serial), so a copy can never be overwritten while the GPU is still reading it.
#pragma once

#include <cstdint>
#include <mutex>
#include <vector>

#include "gdxvk_internal.h"

namespace gdxvk {

class StagingRing {
public:
    StagingRing(ContextHandles& h, VkDeviceSize blockSize);
    ~StagingRing();
    StagingRing(const StagingRing&) = delete;
    StagingRing& operator=(const StagingRing&) = delete;

    // Thread-safe. Copy the bytes into a staging slice and queue the transfer, atomically, so the render thread can never
    // record a copy whose bytes are still being written. Returns false if staging memory could not be allocated.
    bool uploadBuffer(const void* data, VkDeviceSize size, VkBuffer dst, VkDeviceSize dstOffset);
    // First upload of a freshly created VK_FORMAT_R8G8B8A8_UNORM image (layout UNDEFINED); leaves it SHADER_READ_ONLY_OPTIMAL.
    bool uploadImage(const void* data, VkDeviceSize size, VkImage image, uint32_t width, uint32_t height);

    // Render thread. Records all queued copies, with the barriers around them, into `cmd`.
    // Returns false when there was nothing to record. Every true return must be followed by markSubmitted().
    bool recordPending(VkCommandBuffer cmd);
    void markSubmitted(uint64_t serial);
    // Returns blocks the GPU has finished with to the free list (and trims the surplus).
    void collect(uint64_t completedSerial);

    size_t blockCount();
    size_t pendingCopies();

private:
    struct Block {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation alloc = nullptr;
        uint8_t* ptr = nullptr;
        VkDeviceSize size = 0, offset = 0;
        uint64_t serial = 0;
    };
    struct Request {
        VkBuffer src;
        VkDeviceSize srcOffset, size;
        VkBuffer dstBuffer; // or
        VkDeviceSize dstOffset;
        VkImage dstImage;
        uint32_t width, height;
    };

    bool allocSlice(VkDeviceSize size, Block*& block, VkDeviceSize& offset); // requires mutex_
    Block* newBlock(VkDeviceSize size);
    void destroyBlock(Block* b);

    ContextHandles& h_;
    VkDeviceSize blockSize_;
    std::mutex mutex_;
    std::vector<Block*> active_;    // accepting allocations (the last one is current)
    std::vector<Block*> recording_; // handed to recordPending, waiting for markSubmitted
    std::vector<Block*> inFlight_;
    std::vector<Block*> free_;
    std::vector<Request> requests_;
};

} // namespace gdxvk
