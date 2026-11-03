package io.github.slebed.gdxvulkan;

/** The window surface was lost (VK_ERROR_SURFACE_LOST_KHR). Recreate the presenter, e.g. after an Android surfaceDestroyed. */
public class VulkanSurfaceLostException extends GdxVkException {
    private static final long serialVersionUID = 1L;

    public VulkanSurfaceLostException(String message) { super(message); }
}
