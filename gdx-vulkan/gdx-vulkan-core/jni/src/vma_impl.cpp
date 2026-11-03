// Single translation unit that instantiates VMA. Functions are fetched through the vkGetInstanceProcAddr /
// vkGetDeviceProcAddr pointers that Context passes in VmaVulkanFunctions (dynamic mode).
#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
#define VMA_IMPLEMENTATION
#include "volk.h"
#include "vk_mem_alloc.h"
