package com.badlogic.gdx.backend.vulkan;

public enum VulkanLogCategory {
    GENERAL,            // General Vulkan setup/misc info
    INSTANCE_DEVICE,    // VkInstance, PhysicalDevice, VkDevice
    VALIDATION,         // Validation layer output processing (if you intercept it)
    SWAPCHAIN,          // Swapchain handling, VkSurfaceKHR, presentation
    MEMORY,             // VkDeviceMemory allocation, mapping, management
    RESOURCES,          // VkBuffer, VkImage, VkImageView creation/destruction
    SHADERS,            // VkShaderModule handling
    PIPELINE,           // VkPipeline, VkPipelineLayout, VkPipelineCache, VkRenderPass
    COMMANDS,           // VkCommandPool, VkCommandBuffer recording/submission
    SYNCHRONIZATION     // Fences, Semaphores, Events, Barriers
    // Add or refine categories as needed for your specific backend structure
}