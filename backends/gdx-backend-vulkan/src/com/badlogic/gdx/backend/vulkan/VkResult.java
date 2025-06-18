package com.badlogic.gdx.backend.vulkan; // Or your preferred utility package

import java.util.HashMap;
import java.util.Map;

// Import specific constants if needed, or rely on full qualification
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Utility enum and methods for interpreting Vulkan VkResult codes.
 */
public enum VkResult {
    VK_SUCCESS(0),
    VK_NOT_READY(1),
    VK_TIMEOUT(2),
    VK_EVENT_SET(3),
    VK_EVENT_RESET(4),
    VK_INCOMPLETE(5),
    VK_SUBOPTIMAL_KHR(1000001003),
    VK_ERROR_OUT_OF_DATE_KHR(-1000001004), // Note: Error codes are negative
    VK_ERROR_OUT_OF_HOST_MEMORY(-1),
    VK_ERROR_OUT_OF_DEVICE_MEMORY(-2),
    VK_ERROR_INITIALIZATION_FAILED(-3),
    VK_ERROR_DEVICE_LOST(-4),
    VK_ERROR_MEMORY_MAP_FAILED(-5),
    VK_ERROR_LAYER_NOT_PRESENT(-6),
    VK_ERROR_EXTENSION_NOT_PRESENT(-7),
    VK_ERROR_FEATURE_NOT_PRESENT(-8),
    VK_ERROR_INCOMPATIBLE_DRIVER(-9),
    VK_ERROR_TOO_MANY_OBJECTS(-10),
    VK_ERROR_FORMAT_NOT_SUPPORTED(-11),
    VK_ERROR_FRAGMENTED_POOL(-12),
    VK_ERROR_SURFACE_LOST_KHR(-1000000000),
    VK_ERROR_NATIVE_WINDOW_IN_USE_KHR(-1000000001),
    VK_ERROR_VALIDATION_FAILED_EXT(-1000011001),
    // Add more common result codes as needed...
    VK_UNKNOWN(Integer.MIN_VALUE); // Fallback for unknown codes

    public final int code;

    // Static map for quick lookup
    private static final Map<Integer, VkResult> codeToResultMap = new HashMap<>();

    static {
        for (VkResult result : VkResult.values()) {
            // Don't map UNKNOWN by its code, it's a fallback
            if (result != VK_UNKNOWN) {
                codeToResultMap.put(result.code, result);
            }
        }
    }

    VkResult(int code) {
        this.code = code;
    }

    /**
     * Translates an integer Vulkan result code into a VkResult enum constant.
     *
     * @param code The integer result code returned by a Vulkan function.
     * @return The corresponding VkResult enum constant, or VK_UNKNOWN if the code is not recognized.
     */
    public static VkResult translate(int code) {
        return codeToResultMap.getOrDefault(code, VK_UNKNOWN);
    }

    /**
     * Checks if this result code represents success (VK_SUCCESS).
     * @return true if successful, false otherwise.
     */
    public boolean isSuccess() {
        // Compare this enum constant to the VK_SUCCESS enum constant
        return this == VK_SUCCESS;
    }

    /**
     * Checks if this result code represents an error (negative value).
     * Does not include non-error codes like VK_SUBOPTIMAL_KHR.
     * @return true if it's an error code, false otherwise.
     */
    public boolean isError() {
        // All standard Vulkan error codes are negative
        return this.code < 0;
    }

    /**
     * Checks if this result code indicates the swapchain is out of date.
     * @return true if VK_ERROR_OUT_OF_DATE_KHR, false otherwise.
     */
    public boolean isOutOfDate() {
        return this == VK_ERROR_OUT_OF_DATE_KHR;
    }

    /**
     * Checks if this result code indicates the swapchain is suboptimal but usable.
     * @return true if VK_SUBOPTIMAL_KHR, false otherwise.
     */
    public boolean isSuboptimal() {
        return this == VK_SUBOPTIMAL_KHR;
    }

    /**
     * Returns the string representation of the enum constant (e.g., "VK_SUCCESS").
     * For VK_UNKNOWN, it includes the original integer code.
     */
    @Override
    public String toString() {
        if (this == VK_UNKNOWN) {
            return "VK_UNKNOWN"; // Keep it simple
        }
        return this.name(); // Returns the enum constant name
    }

    /**
     * Utility method combining translation and success check.
     * @param code The integer result code.
     * @return true if code is VK_SUCCESS (the integer value 0), false otherwise.
     */
    public static boolean isSuccess(int code) {
        // Explicitly compare the integer code to the integer constant from VK10
        return code == org.lwjgl.vulkan.VK10.VK_SUCCESS; // Use fully qualified name
    }

    /**
     * Utility method combining translation and error check.
     * @param code The integer result code.
     * @return true if code is less than 0, false otherwise.
     */
    public static boolean isError(int code) {
        // Error codes are defined as negative integers
        return code < 0;
    }
}