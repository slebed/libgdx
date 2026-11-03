#include "gdxvk_timeline.h"

namespace gdxvk {

Timeline::Timeline(ContextHandles& h, bool useTimelineSemaphore) : h_(h) {
    if (!useTimelineSemaphore) return;
    VkSemaphoreTypeCreateInfo type{VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO};
    type.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    type.initialValue = 0;
    VkSemaphoreCreateInfo ci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    ci.pNext = &type;
    if (vkCreateSemaphore(h_.device, &ci, nullptr, &semaphore_) != VK_SUCCESS) semaphore_ = VK_NULL_HANDLE; // fall back to fences
}

Timeline::~Timeline() {
    if (h_.device == VK_NULL_HANDLE) return;
    vkDeviceWaitIdle(h_.device);
    if (semaphore_ != VK_NULL_HANDLE) vkDestroySemaphore(h_.device, semaphore_, nullptr);
    for (const Entry& e : pending_) vkDestroyFence(h_.device, e.fence, nullptr);
    for (VkFence f : freeFences_) vkDestroyFence(h_.device, f, nullptr);
}

VkResult Timeline::submit(const VkSubmitInfo& info, uint64_t* serialOut) {
    std::lock_guard<std::mutex> queueLock(h_.queueMutex);
    const uint64_t serial = lastSubmitted_.load() + 1;
    VkResult r;
    if (semaphore_ != VK_NULL_HANDLE) {
        std::vector<VkSemaphore> signals(info.pSignalSemaphores, info.pSignalSemaphores + info.signalSemaphoreCount);
        std::vector<uint64_t> signalValues(signals.size(), 0); // ignored for the binary semaphores
        std::vector<uint64_t> waitValues(info.waitSemaphoreCount, 0);
        signals.push_back(semaphore_);
        signalValues.push_back(serial);
        VkTimelineSemaphoreSubmitInfo ti{VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO};
        ti.waitSemaphoreValueCount = static_cast<uint32_t>(waitValues.size());
        ti.pWaitSemaphoreValues = waitValues.data();
        ti.signalSemaphoreValueCount = static_cast<uint32_t>(signalValues.size());
        ti.pSignalSemaphoreValues = signalValues.data();
        VkSubmitInfo si = info;
        si.pNext = &ti;
        si.signalSemaphoreCount = static_cast<uint32_t>(signals.size());
        si.pSignalSemaphores = signals.data();
        r = vkQueueSubmit(h_.graphicsQueue, 1, &si, VK_NULL_HANDLE);
    } else {
        VkFence fence = VK_NULL_HANDLE;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            pollFencesLocked(); // recycles finished fences first
            if (!freeFences_.empty()) {
                fence = freeFences_.back();
                freeFences_.pop_back();
            }
        }
        if (fence == VK_NULL_HANDLE) {
            VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            if (vkCreateFence(h_.device, &fi, nullptr, &fence) != VK_SUCCESS) return VK_ERROR_OUT_OF_HOST_MEMORY;
        }
        r = vkQueueSubmit(h_.graphicsQueue, 1, &info, fence);
        std::lock_guard<std::mutex> lock(mutex_);
        if (r == VK_SUCCESS) pending_.push_back({serial, fence});
        else freeFences_.push_back(fence);
    }
    if (r != VK_SUCCESS) return r;
    lastSubmitted_.store(serial);
    if (serialOut) *serialOut = serial;
    return VK_SUCCESS;
}

// Pops finished fences in submit order. Requires mutex_.
uint64_t Timeline::pollFencesLocked() {
    while (!pending_.empty() && vkGetFenceStatus(h_.device, pending_.front().fence) == VK_SUCCESS) {
        completedFence_ = pending_.front().serial;
        vkResetFences(h_.device, 1, &pending_.front().fence);
        freeFences_.push_back(pending_.front().fence);
        pending_.pop_front();
    }
    return completedFence_;
}

uint64_t Timeline::completed() {
    if (semaphore_ != VK_NULL_HANDLE) {
        uint64_t v = 0;
        vkGetSemaphoreCounterValue(h_.device, semaphore_, &v);
        return v;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    return pollFencesLocked();
}

VkResult Timeline::waitSerial(uint64_t serial) {
    if (serial == 0) return VK_SUCCESS;
    if (semaphore_ != VK_NULL_HANDLE) {
        VkSemaphoreWaitInfo wi{VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO};
        wi.semaphoreCount = 1;
        wi.pSemaphores = &semaphore_;
        wi.pValues = &serial;
        return vkWaitSemaphores(h_.device, &wi, UINT64_MAX);
    }
    std::lock_guard<std::mutex> lock(mutex_);
    for (const Entry& e : pending_) {
        if (e.serial > serial) break;
        const VkResult r = vkWaitForFences(h_.device, 1, &e.fence, VK_TRUE, UINT64_MAX);
        if (r != VK_SUCCESS) return r;
    }
    pollFencesLocked();
    return VK_SUCCESS;
}

void Timeline::waitIdle() {
    {
        std::lock_guard<std::mutex> lock(h_.queueMutex);
        vkQueueWaitIdle(h_.graphicsQueue);
    }
    completed();
}

} // namespace gdxvk
