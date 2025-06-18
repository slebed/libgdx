package com.badlogic.gdx.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.VK10.*;
// Assuming DEVICE_EXTENSIONS and primarySurface are accessible in this context
// For a standalone method, these would need to be passed as parameters or accessed differently.

public class VulkanDeviceUtils { // Changed class name for a utility context

    private static final String TAG = "VulkanDeviceUtils"; // For logging
    // DEVICE_EXTENSIONS would typically be a static final Set<String> in VulkanApplication
    // For this standalone method, let's assume it's passed or defined if needed.
    // private static final Set<String> DEVICE_EXTENSIONS = Collections.singleton(VK_KHR_SWAPCHAIN_EXTENSION_NAME);


    // Inner class to hold queue family indices
    public static class QueueFamilyIndices {
        public Integer graphicsFamily;
        public Integer presentFamily;

        public boolean isComplete() {
            return graphicsFamily != null && presentFamily != null;
        }
    }

    // Inner class to hold swap chain support details
    private static class SwapChainSupportDetails {
        VkSurfaceCapabilitiesKHR capabilities;
        VkSurfaceFormatKHR.Buffer formats;
        IntBuffer presentModes;
        // Constructor or init method could be useful if this struct is used more broadly
    }

    /**
     * Checks if a given physical device is suitable for the application's needs.
     *
     * @param device The {@link VkPhysicalDevice} to check.
     * @param stack The current {@link MemoryStack} for temporary allocations.
     * @param requiredDeviceExtensions A Set of required device extension names.
     * @param surface The {@code VkSurfaceKHR} handle (long) against which to check presentation support.
     * This is crucial for swapchain adequacy checks.
     * @return {@code true} if the device is suitable, {@code false} otherwise.
     */
    public static boolean isDeviceSuitable(VkPhysicalDevice device, MemoryStack stack, Set<String> requiredDeviceExtensions, long surface) {
        // 1. Check Queue Families
        QueueFamilyIndices indices = findQueueFamilies(device, stack, surface);

        // 2. Check Required Device Extensions
        boolean extensionsSupported = checkDeviceExtensionSupport(device, stack, requiredDeviceExtensions);

        // 3. Check Swapchain Support (requires extensions to be supported first and a valid surface)
        boolean swapChainAdequate = false;
        if (extensionsSupported && surface != VK_NULL_HANDLE) {
            SwapChainSupportDetails swapChainSupport = querySwapChainSupport(device, stack, surface);
            swapChainAdequate = swapChainSupport.formats != null && swapChainSupport.formats.limit() > 0 &&
                    swapChainSupport.presentModes != null && swapChainSupport.presentModes.limit() > 0;
        } else if (surface == VK_NULL_HANDLE) {
            // If no surface is provided, we might skip swapchain adequacy check or define suitability differently.
            // For typical rendering applications, swapchain is essential.
            // Gdx.app.log(TAG, "No surface provided to isDeviceSuitable, skipping swapchain adequacy check.");
            // swapChainAdequate = true; // Or false, depending on requirements without a surface
        }


        // 4. Optionally, check for specific features (if VulkanDeviceCapabilities is available and initialized for 'device')
        // Example:
        // VulkanDeviceCapabilities tempCaps = new VulkanDeviceCapabilities(device);
        // boolean featuresSupported = tempCaps.isSamplerAnisotropy(); // And other critical features
        // tempCaps.free(); // If it allocates heap resources
        // For this standalone method, feature checking would require passing VulkanDeviceCapabilities or querying here.
        boolean featuresSupported = true; // Placeholder; implement actual feature checks if needed

        return indices.isComplete() && extensionsSupported && swapChainAdequate && featuresSupported;
    }

    /**
     * Finds the graphics and presentation queue families for a given device and surface.
     *
     * @param device The {@link VkPhysicalDevice} to query.
     * @param stack The current {@link MemoryStack}.
     * @param surface The {@code VkSurfaceKHR} handle (long) for checking presentation support.
     * @return A {@link QueueFamilyIndices} object.
     */
    private static QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device, MemoryStack stack, long surface) {
        QueueFamilyIndices indices = new QueueFamilyIndices();
        IntBuffer queueFamilyCount = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

        if (queueFamilyCount.get(0) == 0) {
            // Gdx.app.error(TAG, "Physical device has no queue families!");
            return indices; // Return incomplete indices
        }

        VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.calloc(queueFamilyCount.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

        IntBuffer pPresentSupport = stack.mallocInt(1);

        for (int i = 0; i < queueFamilies.limit(); i++) {
            if ((queueFamilies.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                indices.graphicsFamily = i;
            }

            if (surface != VK_NULL_HANDLE) { // Only check present support if a surface is provided
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, pPresentSupport);
                if (pPresentSupport.get(0) == VK_TRUE) {
                    indices.presentFamily = i;
                }
            } else {
                // If no surface, presentFamily might remain null, or be set to graphicsFamily if combined queues are acceptable.
                // For now, it remains null, and isComplete() will reflect this.
            }

            // If a surface is expected and both families are found, or if no surface and graphics is found.
            if (surface != VK_NULL_HANDLE && indices.isComplete()) {
                break;
            } else if (surface == VK_NULL_HANDLE && indices.graphicsFamily != null) {
                // If no surface, we might only care about graphics.
                // Or, presentFamily could be set to graphicsFamily if that's an acceptable fallback.
                // For simplicity, we break if graphics is found and no surface is given.
                // indices.presentFamily = indices.graphicsFamily; // Optional: assume combined queue
                // if(indices.isComplete()) break;
            }
        }
        return indices;
    }

    /**
     * Checks if a device supports all required extensions.
     *
     * @param device The {@link VkPhysicalDevice} to check.
     * @param stack The current {@link MemoryStack}.
     * @param requiredDeviceExtensions A Set of required device extension names.
     * @return {@code true} if all required extensions are supported, {@code false} otherwise.
     */
    private static boolean checkDeviceExtensionSupport(VkPhysicalDevice device, MemoryStack stack, Set<String> requiredDeviceExtensions) {
        if (requiredDeviceExtensions == null || requiredDeviceExtensions.isEmpty()) {
            return true; // No extensions required
        }

        IntBuffer extensionCountBuffer = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCountBuffer, null);
        int numExtensions = extensionCountBuffer.get(0);

        if (numExtensions == 0) {
            return requiredDeviceExtensions.isEmpty();
        }

        VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.calloc(numExtensions); // HEAP allocation
        try {
            extensionCountBuffer.put(0, numExtensions); // Reset count for the call
            vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCountBuffer, availableExtensions);

            Set<String> availableExtensionNames = new HashSet<>();
            for (int i = 0; i < numExtensions; i++) {
                availableExtensionNames.add(availableExtensions.get(i).extensionNameString());
            }
            return availableExtensionNames.containsAll(requiredDeviceExtensions);
        } finally {
            if (availableExtensions != null) {
                availableExtensions.free(); // Manually free heap-allocated buffer
            }
        }
    }

    /**
     * Queries swap chain support details for a given device and surface.
     *
     * @param device The {@link VkPhysicalDevice} to query.
     * @param stack The current {@link MemoryStack}.
     * @param surface The {@code VkSurfaceKHR} handle (long).
     * @return A {@link SwapChainSupportDetails} object.
     */
    private static SwapChainSupportDetails querySwapChainSupport(VkPhysicalDevice device, MemoryStack stack, long surface) {
        SwapChainSupportDetails details = new SwapChainSupportDetails();
        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);

        if (surface == VK_NULL_HANDLE) {
            // Gdx.app.error(TAG, "querySwapChainSupport called with null surface!");
            // Return empty/default details
            details.formats = VkSurfaceFormatKHR.malloc(0, stack);
            details.presentModes = stack.mallocInt(0);
            return details;
        }

        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities);

        IntBuffer formatCount = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, formatCount, null);
        if (formatCount.get(0) != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(formatCount.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, formatCount, details.formats);
        } else {
            details.formats = VkSurfaceFormatKHR.malloc(0, stack);
        }

        IntBuffer presentModeCount = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, presentModeCount, null);
        if (presentModeCount.get(0) != 0) {
            details.presentModes = stack.mallocInt(presentModeCount.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, presentModeCount, details.presentModes);
        } else {
            details.presentModes = stack.mallocInt(0);
        }
        return details;
    }
}
