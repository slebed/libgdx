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
    bool debugUtils = false;       // VK_EXT_debug_utils is enabled: objects get names and passes get labels, for RenderDoc and AGI

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

// Debug names and labels (no-ops without VK_EXT_debug_utils). They make a RenderDoc or Android GPU Inspector capture readable: every pipeline,
// image and buffer pool has a name, and each render pass, upload batch and compute section is a labelled region.
inline void nameObject(const ContextHandles& h, VkObjectType type, uint64_t handle, const char* name) {
    if (!h.debugUtils || name == nullptr || handle == 0) return;
    VkDebugUtilsObjectNameInfoEXT info{VK_STRUCTURE_TYPE_DEBUG_UTILS_OBJECT_NAME_INFO_EXT};
    info.objectType = type;
    info.objectHandle = handle;
    info.pObjectName = name;
    vkSetDebugUtilsObjectNameEXT(h.device, &info);
}

inline void beginLabel(const ContextHandles& h, VkCommandBuffer cmd, const char* name, float r = 0.4f, float g = 0.6f, float b = 0.9f) {
    if (!h.debugUtils) return;
    VkDebugUtilsLabelEXT label{VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT};
    label.pLabelName = name;
    label.color[0] = r;
    label.color[1] = g;
    label.color[2] = b;
    label.color[3] = 1.0f;
    vkCmdBeginDebugUtilsLabelEXT(cmd, &label);
}

inline void endLabel(const ContextHandles& h, VkCommandBuffer cmd) {
    if (h.debugUtils) vkCmdEndDebugUtilsLabelEXT(cmd);
}

} // namespace gdxvk
