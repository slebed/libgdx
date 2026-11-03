// Mutex-guarded staging ring (plan, Phase 1b): any thread copies bytes in and queues the transfer; the render thread records
// every pending copy at the start of the frame command buffer. No second queue, no ownership transfers.
//
// Blocks are persistently mapped host memory. A block is reused only once the submit that read it has completed
// (Timeline serial), so a copy can never be overwritten while the GPU is still reading it.
//
// Requests are recorded in the order they were queued, so an update queued after an upload of the same image sees it finished.
#pragma once

#include <cstdint>
#include <mutex>
#include <vector>

#include "gdxvk_internal.h"

namespace gdxvk {

// A rectangle of one mip level, and where its (tightly packed) bytes start inside the data handed to uploadImage/updateImage.
struct ImageRegion {
    uint32_t level = 0, x = 0, y = 0, width = 0, height = 0;
    VkDeviceSize dataOffset = 0;
};

class StagingRing {
public:
    StagingRing(ContextHandles& h, VkDeviceSize blockSize);
    ~StagingRing();
    StagingRing(const StagingRing&) = delete;
    StagingRing& operator=(const StagingRing&) = delete;

    // Thread-safe. Copy the bytes into a staging slice and queue the transfer, atomically, so the render thread can never
    // record a copy whose bytes are still being written. Return false if staging memory could not be allocated.
    bool uploadBuffer(const void* data, VkDeviceSize size, VkBuffer dst, VkDeviceSize dstOffset);

    // First upload into a freshly created image (layout UNDEFINED, `levels` mip levels, usage SAMPLED | TRANSFER_DST | TRANSFER_SRC).
    // Writes `regions`; with `generateMips`, levels 1.. are then built from level 0 by a blit chain. Leaves SHADER_READ_ONLY_OPTIMAL.
    bool uploadImage(const void* data, VkDeviceSize size, VkImage image, uint32_t width, uint32_t height, uint32_t levels,
                     const std::vector<ImageRegion>& regions, bool generateMips, VkFilter blitFilter);

    // Copy-on-write update (plan, Core decision 4): `newImage` (UNDEFINED) receives the first `copyLevels` levels of `oldImage`
    // (SHADER_READ_ONLY_OPTIMAL, restored afterwards because draws recorded later this frame may still sample it), then `regions`
    // on top, then optionally regenerated mips. Leaves `newImage` SHADER_READ_ONLY_OPTIMAL.
    bool updateImage(const void* data, VkDeviceSize size, VkImage newImage, VkImage oldImage, uint32_t width, uint32_t height,
                     uint32_t levels, uint32_t copyLevels, const std::vector<ImageRegion>& regions, bool generateMips,
                     VkFilter blitFilter);

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
        enum Kind { BufferCopy, ImageUpload, ImageUpdate } kind = BufferCopy;
        VkBuffer src = VK_NULL_HANDLE;
        VkDeviceSize srcOffset = 0, size = 0;
        VkBuffer dstBuffer = VK_NULL_HANDLE;
        VkDeviceSize dstOffset = 0;
        VkImage image = VK_NULL_HANDLE, oldImage = VK_NULL_HANDLE;
        uint32_t width = 0, height = 0, levels = 1, copyLevels = 0;
        bool generateMips = false;
        VkFilter blitFilter = VK_FILTER_LINEAR;
        std::vector<ImageRegion> regions;
    };

    bool allocSlice(VkDeviceSize size, Block*& block, VkDeviceSize& offset); // requires mutex_
    Block* newBlock(VkDeviceSize size);
    void destroyBlock(Block* b);
    bool queueImage(Request::Kind kind, const void* data, VkDeviceSize size, VkImage image, VkImage oldImage, uint32_t width,
                    uint32_t height, uint32_t levels, uint32_t copyLevels, const std::vector<ImageRegion>& regions, bool generateMips,
                    VkFilter blitFilter);
    void recordImage(VkCommandBuffer cmd, const Request& r);

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
