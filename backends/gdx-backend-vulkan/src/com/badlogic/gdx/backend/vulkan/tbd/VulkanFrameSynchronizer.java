package com.badlogic.gdx.backend.vulkan.tbd;

import org.lwjgl.vulkan.VkCommandBuffer;

import com.badlogic.gdx.backend.vulkan.VulkanSwapchain;

/**
 * An interface that provides access to the essential, per-frame resources required for rendering.
 * This is implemented by VulkanGraphics and passed to rendering components like SpriteBatch
 * to give them access to the currently active command buffer and frame index without
 * exposing the entire Graphics object.
 */
public interface VulkanFrameSynchronizer {

    /**
     * Gets the command buffer that is currently being recorded for this frame.
     *
     * @return The active VkCommandBuffer, or null if a frame is not currently active.
     */
    VkCommandBuffer getCurrentCommandBuffer();

    /**
     * Gets the swapchain associated with the graphics context.
     *
     * @return The VulkanSwapchain instance.
     */
    VulkanSwapchain getSwapchain();

    /**
     * Gets the index of the current frame-in-flight (e.g., 0 or 1 for double buffering).
     * This is used to select the correct uniform buffers or other per-frame resources.
     *
     * @return The index of the current frame.
     */
    int getFrameIndex();
}
