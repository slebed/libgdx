
package com.badlogic.gdx.backend.vulkan; // Or your preferred package

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;

import java.nio.FloatBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Helper class to manage beginning and ending Vulkan render pass instances, specifically tailored for the main swapchain render
 * pass. */
public class VulkanRenderPassManager {

	// Default clear color (e.g., dark blue) - Can be made configurable
	private final Color defaultClearColor = new Color(0.0f, 0.0f, 0.2f, 1.0f);

	/** Begins the main swapchain render pass on the given command buffer. Assumes the commandBuffer is already in the recording
	 * state.
	 *
	 * @param commandBuffer The active command buffer to record into.
	 * @param swapchain The VulkanSwapchain providing render pass, framebuffer, and extent info.
	 * @param imageIndex The index of the current swapchain image/framebuffer to use.
	 * @param clearColor The color to clear the attachment with (if null, uses default). */
	public void beginSwapchainRenderPass (VkCommandBuffer commandBuffer, VulkanSwapchain swapchain, int imageIndex,		Color clearColor) {
		if (commandBuffer == null) {
			throw new GdxRuntimeException("CommandBuffer cannot be null for beginSwapchainRenderPass");
		}
		if (swapchain == null || swapchain.getHandle() == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("VulkanSwapchain is null or invalid for beginSwapchainRenderPass");
		}

		try (MemoryStack stack = stackPush()) {
			long renderPassHandle = swapchain.getRenderPass();
			if (renderPassHandle == VK_NULL_HANDLE) {
				throw new GdxRuntimeException("RenderPass handle is null in VulkanSwapchain");
			}

			long framebufferHandle = 0l;//swapchain.getFramebuffer(imageIndex); // getFramebuffer handles index check
			VkExtent2D currentExtent = swapchain.getExtent();
			if (currentExtent == null) {
				throw new GdxRuntimeException("Swapchain extent is null");
			}

			VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPassHandle)
				.framebuffer(framebufferHandle);

			renderPassInfo.renderArea().offset().set(0, 0);
			renderPassInfo.renderArea().extent().set(currentExtent);

			// Setup clear color
			VkClearValue.Buffer clearValues = VkClearValue.calloc(1, stack);
			Color colorToUse = (clearColor != null) ? clearColor : defaultClearColor;
			FloatBuffer fb = stack.mallocFloat(4);
			fb.put(0, colorToUse.r);
			fb.put(1, colorToUse.g);
			fb.put(2, colorToUse.b);
			fb.put(3, colorToUse.a);
			clearValues.get(0).color().float32(fb);
			// TODO: Add depth/stencil clear value here if depth buffer is used

			renderPassInfo.pClearValues(clearValues);

			// Begin the render pass instance
			vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

		} catch (Exception e) {
			// Catch potential stack allocation or Vulkan errors
			throw new GdxRuntimeException("Failed to begin swapchain render pass", e);
		}
	}

	/** Ends the currently active render pass on the command buffer.
	 *
	 * @param commandBuffer The command buffer where the render pass is active. */
	public void endRenderPass (VkCommandBuffer commandBuffer) {
		if (commandBuffer == null) {
			throw new GdxRuntimeException("CommandBuffer cannot be null for endRenderPass");
		}
		vkCmdEndRenderPass(commandBuffer);
	}

	// Optional: Method to set a different default clear color
	public void setDefaultClearColor (float r, float g, float b, float a) {
		this.defaultClearColor.set(r, g, b, a);
	}
}
