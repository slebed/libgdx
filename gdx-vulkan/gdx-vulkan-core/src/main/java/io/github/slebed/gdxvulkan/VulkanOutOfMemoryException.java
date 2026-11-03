package io.github.slebed.gdxvulkan;

/** Host or device memory ran out (VK_ERROR_OUT_OF_HOST_MEMORY / VK_ERROR_OUT_OF_DEVICE_MEMORY). */
public class VulkanOutOfMemoryException extends GdxVkException {
    private static final long serialVersionUID = 1L;

    public VulkanOutOfMemoryException(String message) { super(message); }
}
