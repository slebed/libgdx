
package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** A simple helper class to encapsulate common Vulkan rendering command sequences. Takes an active command buffer and provides
 * methods to bind state and issue draws. Note: This initial version is very basic and assumes simple state management. */
public class VulkanRenderer {
	private final String logTag = "VulkanRenderer";
	private final VulkanDevice device;
	private final VkDevice rawDevice;

	private VkCommandBuffer currentCommandBuffer = null;

	public VulkanRenderer (VulkanDevice device) {
		this.device = device;
		this.rawDevice = device.getRawDevice();
	}

	/** Begins a rendering sequence for the given command buffer.
	 * @param commandBuffer The active command buffer (must be in recording state). */
	public void begin (VkCommandBuffer commandBuffer) {
		if (commandBuffer == null) {
			throw new GdxRuntimeException("Cannot begin renderer with a null command buffer.");
		}
		if (currentCommandBuffer != null) {
			Gdx.app.error(logTag, "Renderer begin() called while already active. Did you forget end()?");
			// Optionally throw, or just overwrite
		}
		this.currentCommandBuffer = commandBuffer;
		// Gdx.app.log(logTag, "Begin rendering sequence.");
	}

	/** Ends the current rendering sequence, clearing the active command buffer reference. */
	public void end () {
		// Gdx.app.log(logTag, "End rendering sequence.");
		this.currentCommandBuffer = null;
	}

	/** Checks if begin() was called and the command buffer is set. */
	private void checkActive () {
		if (currentCommandBuffer == null) {
			throw new GdxRuntimeException("Renderer commands called before begin() or after end().");
		}
	}

	/** Sets the dynamic viewport and scissor states based on the provided extent.
	 * @param extent The VkExtent2D defining the render area. */
	public void setDynamicStates (VkExtent2D extent) {
		checkActive();
		if (extent == null) return;

		try (MemoryStack stack = stackPush()) {
			// Viewport (Y-down example)
			VkViewport.Buffer viewport = VkViewport.calloc(1, stack).x(0.0f).y((float)extent.height()).width((float)extent.width())
				.height(-(float)extent.height()).minDepth(0.0f).maxDepth(1.0f);
			vkCmdSetViewport(currentCommandBuffer, 0, viewport);

			// Scissor
			VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack).offset(o -> o.set(0, 0)).extent(extent); // Use extent directly
			vkCmdSetScissor(currentCommandBuffer, 0, scissor);
		}
	}

	/** Binds the specified graphics pipeline.
	 * @param pipelineHandle The VkPipeline handle. */
	public void bindPipeline (long pipelineHandle) {
		checkActive();
		if (pipelineHandle == VK_NULL_HANDLE) {
			Gdx.app.error(logTag, "Attempted to bind a NULL pipeline handle!");
			return; // Or throw?
		}
		vkCmdBindPipeline(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineHandle);
	}

	/** Binds a descriptor set to the specified set index (e.g., 0).
	 * @param layoutHandle The VkPipelineLayout compatible with the set.
	 * @param setHandle The VkDescriptorSet handle to bind.
	 * @param setIndex The index (usually 0 for simple cases) to bind the set to. */
	public void bindDescriptorSet (long layoutHandle, long setHandle, int setIndex) {
		checkActive();
		if (layoutHandle == VK_NULL_HANDLE || setHandle == VK_NULL_HANDLE) {
			Gdx.app.error(logTag, "Attempted to bind with NULL layout or descriptor set handle!");
			return; // Or throw?
		}
		try (MemoryStack stack = stackPush()) {
			LongBuffer pDescriptorSets = stack.longs(setHandle);
			vkCmdBindDescriptorSets(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, layoutHandle, setIndex, // The set index
																																						// (usually 0)
				pDescriptorSets, null); // No dynamic offsets
		}
	}

	/** Binds and draws the specified mesh.
	 * @param mesh The VulkanMesh containing vertex and index data. */
	public void drawMesh (VulkanMesh mesh) {
		checkActive();
		if (mesh == null) {
			Gdx.app.error(logTag, "Attempted to draw a null mesh!");
			return;
		}

		// Bind Vertex and Index buffers
		mesh.bind(currentCommandBuffer);

		// Issue Draw Call
		int indexCount = mesh.getNumIndices();
		if (indexCount > 0) {
			vkCmdDrawIndexed(currentCommandBuffer, indexCount, 1, 0, 0, 0);
		} else {
			int vertexCount = mesh.getNumVertices();
			if (vertexCount > 0) {
				vkCmdDraw(currentCommandBuffer, vertexCount, 1, 0, 0);
			} else {
				Gdx.app.log(logTag, "Skipping draw call for mesh with 0 vertices/indices.");
			}
		}
	}
}
