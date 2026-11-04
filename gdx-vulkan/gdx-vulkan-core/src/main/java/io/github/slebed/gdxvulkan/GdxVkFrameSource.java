package io.github.slebed.gdxvulkan;

/**
 * Implemented by the {@code Gdx.graphics} of the gdx-vulkan applications ({@code VulkanDesktopApplication}, {@code VulkanAndroidApplication}): it
 * hands out the application's {@link GdxVkFrame}, which is how {@code new VulkanSpriteBatch()} and the other batches find it without being told.
 */
public interface GdxVkFrameSource {
    /** The frame everything draws into. The same object for the life of the application; the swapchain behind it may be replaced. */
    GdxVkFrame getFrame ();
}
