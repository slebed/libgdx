package io.github.slebed.gdxvulkan;

/** The Vulkan loader (vulkan-1.dll, libvulkan.so.1, libvulkan.dylib) or the gdx-vulkan native library could not be loaded. */
public class VulkanLoaderException extends GdxVkException {
    private static final long serialVersionUID = 1L;

    public VulkanLoaderException(String message) { super(message); }
    public VulkanLoaderException(String message, Throwable cause) { super(message, cause); }
}
