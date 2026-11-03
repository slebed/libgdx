package io.github.slebed.gdxvulkan;

/** Vulkan instance, device or allocator creation failed. */
public class VulkanInitException extends GdxVkException {
    private static final long serialVersionUID = 1L;

    public VulkanInitException(String message) { super(message); }
    public VulkanInitException(String message, Throwable cause) { super(message, cause); }
}
