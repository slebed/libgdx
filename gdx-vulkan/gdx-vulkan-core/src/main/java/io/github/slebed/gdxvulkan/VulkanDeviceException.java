package io.github.slebed.gdxvulkan;

/** No usable Vulkan device or driver (ICD) was found. */
public class VulkanDeviceException extends GdxVkException {
    private static final long serialVersionUID = 1L;

    public VulkanDeviceException(String message) { super(message); }
    public VulkanDeviceException(String message, Throwable cause) { super(message, cause); }
}
