// Submit serials and GPU completion tracking (plan, Phase 1b "Deferred deletion").
//
// Every vkQueueSubmit through Timeline::submit gets the next uint64 serial. Completion is tracked by a timeline semaphore on
// Vulkan 1.2+ and by one fence per submit on 1.1 (or whenever forceTierB asks for the 1.1 path, so CI exercises both).
// Anything recorded into a command buffer that has not been submitted yet will be part of serial nextSerial().
#pragma once

#include <atomic>
#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>

#include "gdxvk_internal.h"

namespace gdxvk {

class Timeline {
public:
    Timeline(ContextHandles& h, bool useTimelineSemaphore);
    ~Timeline();
    Timeline(const Timeline&) = delete;
    Timeline& operator=(const Timeline&) = delete;

    bool usesSemaphore() const { return semaphore_ != VK_NULL_HANDLE; }

    // Serial the next submit will get. Resources retired now may still be used by commands that will be part of it.
    uint64_t nextSerial() const { return lastSubmitted_.load() + 1; }

    // Submits one batch on the graphics queue (takes queueMutex). `info` may already carry binary semaphores.
    VkResult submit(const VkSubmitInfo& info, uint64_t* serial);

    // Highest serial known to have finished on the GPU. Polls; safe from any thread.
    uint64_t completed();

    // Blocks until `serial` has finished. Render thread.
    VkResult waitSerial(uint64_t serial);

    void waitIdle();

private:
    struct Entry {
        uint64_t serial;
        VkFence fence;
    };
    ContextHandles& h_;
    VkSemaphore semaphore_ = VK_NULL_HANDLE; // timeline mode
    std::atomic<uint64_t> lastSubmitted_{0};

    std::mutex mutex_; // fence mode state
    std::deque<Entry> pending_;
    std::vector<VkFence> freeFences_;
    uint64_t completedFence_ = 0;

    uint64_t pollFencesLocked();
};

} // namespace gdxvk
