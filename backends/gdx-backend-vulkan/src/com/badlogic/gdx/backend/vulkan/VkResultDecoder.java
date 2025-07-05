
package com.badlogic.gdx.backend.vulkan; // Or your preferred utility package

import static org.lwjgl.vulkan.EXTDebugReport.*;

import static org.lwjgl.vulkan.KHRDisplaySwapchain.VK_ERROR_INCOMPATIBLE_DISPLAY_KHR;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.KHRVideoQueue.VK_ERROR_IMAGE_USAGE_NOT_SUPPORTED_KHR; // If using video extensions
import static org.lwjgl.vulkan.KHRVideoQueue.VK_ERROR_VIDEO_PICTURE_LAYOUT_NOT_SUPPORTED_KHR;
import static org.lwjgl.vulkan.KHRVideoQueue.VK_ERROR_VIDEO_PROFILE_CODEC_NOT_SUPPORTED_KHR;
import static org.lwjgl.vulkan.KHRVideoQueue.VK_ERROR_VIDEO_PROFILE_FORMAT_NOT_SUPPORTED_KHR;
import static org.lwjgl.vulkan.KHRVideoQueue.VK_ERROR_VIDEO_PROFILE_OPERATION_NOT_SUPPORTED_KHR;
import static org.lwjgl.vulkan.KHRVideoQueue.VK_ERROR_VIDEO_STD_VERSION_NOT_SUPPORTED_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

import org.lwjgl.vulkan.KHRSurface;
// Add imports for other extension results if you use them (VK12, VK13, vendor specific etc.)

/** Utility class to decode Vulkan VkResult codes into human-readable strings. */
public class VkResultDecoder {

	/** Decodes a VkResult integer code into a string representation.
	 *
	 * @param vkResultCode The integer result code (e.g., from a Vulkan function call).
	 * @return A string representation of the result code (e.g., "VK_SUCCESS", "VK_ERROR_DEVICE_LOST"). */
	public static String decode (int vkResultCode) {
		switch (vkResultCode) {
		// --- Success Codes ---
		case VK_SUCCESS:
			return "VK_SUCCESS";
		case VK_NOT_READY:
			return "VK_NOT_READY";
		case VK_TIMEOUT:
			return "VK_TIMEOUT";
		case VK_EVENT_SET:
			return "VK_EVENT_SET";
		case VK_EVENT_RESET:
			return "VK_EVENT_RESET";
		case VK_INCOMPLETE:
			return "VK_INCOMPLETE";
		case VK_SUBOPTIMAL_KHR:
			return "VK_SUBOPTIMAL_KHR"; // Swapchain related
		// Add other success codes if needed (e.g., VK_THREAD_IDLE_KHR)

		// --- Error Codes ---
		case VK_ERROR_OUT_OF_HOST_MEMORY:
			return "VK_ERROR_OUT_OF_HOST_MEMORY";
		case VK_ERROR_OUT_OF_DEVICE_MEMORY:
			return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
		case VK_ERROR_INITIALIZATION_FAILED:
			return "VK_ERROR_INITIALIZATION_FAILED";
		case VK_ERROR_DEVICE_LOST:
			return "VK_ERROR_DEVICE_LOST";
		case VK_ERROR_MEMORY_MAP_FAILED:
			return "VK_ERROR_MEMORY_MAP_FAILED";
		case VK_ERROR_LAYER_NOT_PRESENT:
			return "VK_ERROR_LAYER_NOT_PRESENT";
		case VK_ERROR_EXTENSION_NOT_PRESENT:
			return "VK_ERROR_EXTENSION_NOT_PRESENT";
		case VK_ERROR_FEATURE_NOT_PRESENT:
			return "VK_ERROR_FEATURE_NOT_PRESENT";
		case VK_ERROR_INCOMPATIBLE_DRIVER:
			return "VK_ERROR_INCOMPATIBLE_DRIVER";
		case VK_ERROR_TOO_MANY_OBJECTS:
			return "VK_ERROR_TOO_MANY_OBJECTS";
		case VK_ERROR_FORMAT_NOT_SUPPORTED:
			return "VK_ERROR_FORMAT_NOT_SUPPORTED";
		case VK_ERROR_FRAGMENTED_POOL:
			return "VK_ERROR_FRAGMENTED_POOL";

		// --- KHR Surface/Swapchain Errors ---
		case VK_ERROR_SURFACE_LOST_KHR:
			return "VK_ERROR_SURFACE_LOST_KHR";
		case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR:
			return "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
		case VK_ERROR_OUT_OF_DATE_KHR:
			return "VK_ERROR_OUT_OF_DATE_KHR"; // Swapchain related
		case VK_ERROR_INCOMPATIBLE_DISPLAY_KHR:
			return "VK_ERROR_INCOMPATIBLE_DISPLAY_KHR";

		// --- VK11 Errors (Example) ---
		case VK_ERROR_OUT_OF_POOL_MEMORY:
			return "VK_ERROR_OUT_OF_POOL_MEMORY"; // Renamed from _KHR
		case VK_ERROR_INVALID_EXTERNAL_HANDLE:
			return "VK_ERROR_INVALID_EXTERNAL_HANDLE"; // Renamed from _KHR

		// --- Debug/Validation Errors ---
		case VK_ERROR_VALIDATION_FAILED_EXT:
			return "VK_ERROR_VALIDATION_FAILED_EXT"; // Common with validation layers
		case VK_ERROR_IMAGE_USAGE_NOT_SUPPORTED_KHR:
			return "VK_ERROR_IMAGE_USAGE_NOT_SUPPORTED_KHR";
		case VK_ERROR_VIDEO_PICTURE_LAYOUT_NOT_SUPPORTED_KHR:
			return "VK_ERROR_VIDEO_PICTURE_LAYOUT_NOT_SUPPORTED_KHR";
		case VK_ERROR_VIDEO_PROFILE_OPERATION_NOT_SUPPORTED_KHR:
			return "VK_ERROR_VIDEO_PROFILE_OPERATION_NOT_SUPPORTED_KHR";
		case VK_ERROR_VIDEO_PROFILE_FORMAT_NOT_SUPPORTED_KHR:
			return "VK_ERROR_VIDEO_PROFILE_FORMAT_NOT_SUPPORTED_KHR";
		case VK_ERROR_VIDEO_PROFILE_CODEC_NOT_SUPPORTED_KHR:
			return "VK_ERROR_VIDEO_PROFILE_CODEC_NOT_SUPPORTED_KHR";
		case VK_ERROR_VIDEO_STD_VERSION_NOT_SUPPORTED_KHR:
			return "VK_ERROR_VIDEO_STD_VERSION_NOT_SUPPORTED_KHR";

		// --- Unknown/Other ---
		default:
			return "UNKNOWN_VK_RESULT_CODE (" + vkResultCode + ")";
		}
	}

	public static String decodePresentMode (int presentMode) {
		switch (presentMode) {
		case KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR:
			return "IMMEDIATE_KHR (" + presentMode + ")";
		case KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR:
			return "MAILBOX_KHR (" + presentMode + ")";
		case KHRSurface.VK_PRESENT_MODE_FIFO_KHR:
			return "FIFO_KHR (" + presentMode + ")"; // VSync
		case KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR:
			return "FIFO_RELAXED_KHR (" + presentMode + ")";
		// Add cases for other modes like SHARED_DEMAND_REFRESH / SHARED_CONTINUOUS_REFRESH if needed
		default:
			return "Unknown Present Mode (" + presentMode + ")";
		}
	}

	// Private constructor to prevent instantiation
	private VkResultDecoder () {
	}
}
