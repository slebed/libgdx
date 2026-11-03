// Native-only view of a Context: the raw Vulkan handles. Kept out of gdxvk_context.h so that header (and the
// unit tests that include it) never need the Vulkan headers.
#pragma once

#include <atomic>

#include "volk.h"
#include "vk_mem_alloc.h"

#include "gdxvk_context.h"

namespace gdxvk {

struct ContextHandles {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue graphicsQueue = VK_NULL_HANDLE;
    uint32_t graphicsQueueFamily = 0;
    VmaAllocator allocator = VK_NULL_HANDLE;
    bool tierA = false;           // Tier A: dynamic rendering. Tier B: VkRenderPass + framebuffers.
    bool swapchainEnabled = false; // VK_KHR_swapchain (and the instance surface extensions) are enabled
};

} // namespace gdxvk
