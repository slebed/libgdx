package io.github.slebed.gdxvulkan;

/** The Vulkan device was lost (VK_ERROR_DEVICE_LOST). Not recoverable without recreating the context. */
public class VulkanDeviceLostException extends GdxVkException {
    private static final long serialVersionUID = 1L;

    public VulkanDeviceLostException(String message) { super(message); }
    public VulkanDeviceLostException(String message, Throwable cause) { super(message, cause); }
}
