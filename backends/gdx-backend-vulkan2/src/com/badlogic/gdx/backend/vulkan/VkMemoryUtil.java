
package com.badlogic.gdx.backend.vulkan;

import org.lwjgl.vulkan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.vkDestroySwapchainKHR;
import static org.lwjgl.vulkan.VK10.*;

public class VkMemoryUtil {

	/** Finds a suitable memory type based on memory requirements and desired properties.
	 *
	 * @param physicalDevice The physical device to query.
	 * @param typeFilter The bitmask of suitable memory types.
	 * @param properties The required memory properties (e.g., VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT).
	 * @return The index of a suitable memory type. */
	public static int findMemoryType (VkPhysicalDevice physicalDevice, int typeFilter, int properties) {
		try (MemoryStack stack = stackPush()) {
			VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
			vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProperties);

			for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
				if ((typeFilter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
					return i;
				}
			}
		}
		throw new RuntimeException("Failed to find suitable memory type");
	}

	/** Checks the result of a Vulkan function call and throws an exception if it fails.
	 *
	 * @param result The result of the Vulkan function call.
	 * @param errorMessage The error message to include if the check fails. */
	public static void vkCheck (int result, String errorMessage) {
		if (result != VK_SUCCESS) {
			throw new RuntimeException(errorMessage + " (Error Code: " + result + ")");
		}
	}

	/** Safely destroys a Vulkan object if it's not already destroyed.
	 *
	 * @param device The Vulkan device associated with the object. */
	// public static void safeDestroy(long handle, org.lwjgl.vulkan.VulkanDevice device) {
	// if (handle != VK_NULL_HANDLE) {
	// vkDestroyDevice(device, null);
	// }
	// }

	public static void safeDestroyRenderPass (long renderPassHandle, org.lwjgl.vulkan.VkDevice device) {
		if (renderPassHandle != VK_NULL_HANDLE) {
			vkDestroyRenderPass(device, renderPassHandle, null);
		}
	}

	public static void safeDestroyFramebuffer (long framebuffer, VkDevice device) {
		if (framebuffer != VK_NULL_HANDLE) {
			vkDestroyFramebuffer(device, framebuffer, null);
		}
	}

	public static void safeDestroyImageView (long imageView, org.lwjgl.vulkan.VkDevice device) {
		if (imageView != VK_NULL_HANDLE) {
			vkDestroyImageView(device, imageView, null);
		}
	}

	public static void safeDestroyBuffer (long buffer, org.lwjgl.vulkan.VkDevice device) {
		if (buffer != VK_NULL_HANDLE) {
			vkDestroyBuffer(device, buffer, null);
		}
	}

	public static void safeDestroySampler (long sampler, org.lwjgl.vulkan.VkDevice device) {
		if (sampler != VK_NULL_HANDLE) {
			vkDestroySampler(device, sampler, null);
		}
	}

	public static void safeDestroyCommandPool (long commandPool, org.lwjgl.vulkan.VkDevice device) {
		if (commandPool != VK_NULL_HANDLE) {
			vkDestroyCommandPool(device, commandPool, null);
		}
	}

	public static void safeDestroySwapchain (long swapchain, org.lwjgl.vulkan.VkDevice device) {
		if (swapchain != VK_NULL_HANDLE) {
			vkDestroySwapchainKHR(device, swapchain, null);
		}
	}

	public static void safeDestroyDescriptorPool (long descriptorPool, org.lwjgl.vulkan.VkDevice device) {
		if (descriptorPool != VK_NULL_HANDLE) {
			vkDestroyDescriptorPool(device, descriptorPool, null);
		}
	}

	public static void safeDestroyDescriptorSetLayout (long descriptorSetLayout, VkDevice device) {
		if (descriptorSetLayout != VK_NULL_HANDLE) {
			vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
		}
	}

	public static void safeDestroyInstance (VkInstance instance) {
		if (instance != null) {
			vkDestroyInstance(instance, null);
		}
	}

	/** Safely frees Vulkan memory.
	 *
	 * @param memoryHandle The handle to the memory to be freed.
	 * @param device The Vulkan device associated with the memory. */
	public static void safeFreeMemory (long memoryHandle, VkDevice device) {
		if (memoryHandle != VK_NULL_HANDLE) {
			vkFreeMemory(device, memoryHandle, null);
		}
	}

	public static void safeDestroySemaphore (long imageAvailableSemaphore, VkDevice rawDevice) {
		if (imageAvailableSemaphore != VK_NULL_HANDLE) {
			vkDestroySemaphore(rawDevice, imageAvailableSemaphore, null);
		}
	}

	public static void safeDestroyFence (long inFlightFence, VkDevice rawDevice) {
		if (inFlightFence != VK_NULL_HANDLE) {
			vkDestroyFence(rawDevice, inFlightFence, null);
		}
	}
}
