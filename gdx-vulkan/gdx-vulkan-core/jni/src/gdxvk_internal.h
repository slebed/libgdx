// Native-only view of a Context: the raw Vulkan handles and the device-level services. Kept out of gdxvk_context.h so that
// header (and the unit tests that include it) never need the Vulkan headers.
#pragma once

#include <atomic>
#include <mutex>

#include "volk.h"
#include "vk_mem_alloc.h"

#include "gdxvk_context.h"

namespace gdxvk {

class Timeline;
class DeletionQueue;
class SamplerCache;
class PipelineCacheStore;
class DescriptorLayouts;
class StagingRing;
class Resources;

struct ContextHandles {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue graphicsQueue = VK_NULL_HANDLE;
    uint32_t graphicsQueueFamily = 0;
    VmaAllocator allocator = VK_NULL_HANDLE;
    bool tierA = false;            // Tier A: dynamic rendering, bindless table. Tier B: VkRenderPass, per-texture sets.
    bool swapchainEnabled = false; // VK_KHR_swapchain (and the instance surface extensions) are enabled
    uint32_t effectiveApi = 0;
    VkPhysicalDeviceLimits limits{};

    // vkQueueSubmit and vkQueuePresentKHR need external synchronisation on the one graphics queue.
    mutable std::mutex queueMutex;

    // Device-level services, created by Context::create in dependency order and destroyed in reverse.
    Timeline* timeline = nullptr;
    DeletionQueue* deletion = nullptr;
    SamplerCache* samplers = nullptr;
    PipelineCacheStore* pipelineCache = nullptr;
    DescriptorLayouts* layouts = nullptr;
    StagingRing* staging = nullptr;
    Resources* resources = nullptr;
};

} // namespace gdxvk
