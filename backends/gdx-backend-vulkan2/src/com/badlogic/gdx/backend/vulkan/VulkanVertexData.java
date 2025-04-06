
package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.VertexData;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanVertexData implements VertexData {
	private final String logTag = "VulkanVertexData";

	private final VulkanDevice device;
	private final long vmaAllocator;
	private final VertexAttributes attributes;
	private final int vertexCount;
	private final int vertexSize; // Size of one vertex in bytes
	private final boolean isStatic; // Affects buffer usage/memory type? (Not strictly necessary with VMA_MEMORY_USAGE_AUTO)

	private VulkanBuffer vertexBuffer; // VMA-managed GPU buffer
	private FloatBuffer cpuBuffer; // Direct buffer for CPU-side data before upload
	private boolean isDirty = false;
	private boolean isBound = false; // Tracks binding state within a command buffer (optional)

	public VulkanVertexData (VulkanDevice device, long vmaAllocator, boolean isStatic, int numVertices,
		VertexAttributes attributes) {
		this.device = device;
		this.vmaAllocator = vmaAllocator;
		this.isStatic = isStatic;
		this.vertexCount = numVertices;
		this.attributes = attributes;
		this.vertexSize = attributes.vertexSize;

		// Allocate direct FloatBuffer on CPU side to hold data before upload
		this.cpuBuffer = BufferUtils.newFloatBuffer(numVertices * attributes.vertexSize / Float.BYTES); // Size in floats
		((Buffer)this.cpuBuffer).limit(0); // Initially no data

		// GPU buffer is created lazily when setVertices is first called
		this.vertexBuffer = null;
	}

	@Override
	public VertexAttributes getAttributes () {
		return attributes;
	}

	@Override
	public int getNumVertices () {
		return vertexCount;
	}

	@Override
	public int getNumMaxVertices () {
		return vertexCount; // Not dynamically resizing for now
	}

	/** Sets the vertex data. If this is the first time, creates the GPU buffer. Otherwise, marks data as dirty for upload on next
	 * bind/render. (For non-static data, could re-upload immediately or defer). */
	@Override
	public void setVertices (float[] vertices, int offset, int count) {
		if (vertexBuffer == null) {
			// First time: Create GPU buffer and upload initial data
			createAndUploadInitialData(vertices, offset, count);
		} else {
			// Subsequent times: Copy data to CPU buffer, mark dirty
			// TODO: Implement efficient updates (e.g., sub-data uploads) for non-static meshes
			// For now, just copy to CPU buffer and re-upload everything if non-static
			BufferUtils.copy(vertices, offset, count, cpuBuffer);
			((Buffer)cpuBuffer).position(0);
			((Buffer)cpuBuffer).limit(count);
			isDirty = true; // Mark for upload
			Gdx.app.log(logTag, "setVertices: Marked " + count + " floats as dirty.");
			if (!isStatic) {
				updateVertices(0, vertices, offset, count);
			}
		}
	}

	private void createAndUploadInitialData (float[] vertices, int offset, int count) {
		if (vertexBuffer != null) return; // Already created

		long bufferSizeBytes = (long)vertexCount * vertexSize;
		if (bufferSizeBytes == 0) return; // Nothing to create

		Gdx.app.log(logTag, "Creating vertex buffer (size: " + bufferSizeBytes + " bytes)");

		// Copy initial data to CPU buffer
		BufferUtils.copy(vertices, offset, count, cpuBuffer);
		((Buffer)cpuBuffer).position(0);
		((Buffer)cpuBuffer).limit(count);

		// --- Create Staging Buffer ---
		VulkanBuffer stagingBuffer = VulkanResourceUtil.createManagedBuffer(vmaAllocator, bufferSizeBytes,
			VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VMA_MEMORY_USAGE_AUTO, // CPU access needed
			VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);

		try {
			// --- Map, Copy, Unmap Staging Buffer ---
			PointerBuffer pData = MemoryUtil.memAllocPointer(1);
			try {
				vkCheck(vmaMapMemory(vmaAllocator, stagingBuffer.allocationHandle, pData), "VMA Failed to map vertex staging buffer");
				ByteBuffer stagingByteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int)bufferSizeBytes);
				// IMPORTANT: Ensure cpuBuffer has the correct data and limit set
				if (cpuBuffer.limit() * Float.BYTES > bufferSizeBytes) {
					throw new GdxRuntimeException("CPU buffer data exceeds vertex buffer size!");
				}
				stagingByteBuffer.asFloatBuffer().put(cpuBuffer); // Copy from CPU buffer
				vmaUnmapMemory(vmaAllocator, stagingBuffer.allocationHandle);
			} finally {
				MemoryUtil.memFree(pData);
			}

			// --- Create Final GPU Vertex Buffer ---
			this.vertexBuffer = VulkanResourceUtil.createManagedBuffer(vmaAllocator, bufferSizeBytes,
				VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, // Dest + Vertex
				VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, // Prefer GPU
				0);

			// --- Copy Staging -> GPU using single-time command ---
			final long srcHandle = stagingBuffer.bufferHandle;
			final long dstHandle = this.vertexBuffer.bufferHandle;
			device.executeSingleTimeCommands(cmd -> {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).size(bufferSizeBytes);
					vkCmdCopyBuffer(cmd, srcHandle, dstHandle, region);
				}
			});
			Gdx.app.log(logTag, "Vertex buffer created and initial data uploaded.");

		} catch (Exception e) {
			// Cleanup GPU buffer if partially created before error
			if (this.vertexBuffer != null) {
				this.vertexBuffer.dispose();
				this.vertexBuffer = null;
			}
			throw new GdxRuntimeException("Failed to create/upload initial vertex data", e);
		} finally {
			// --- Dispose Staging Buffer ---
			if (stagingBuffer != null) {
				stagingBuffer.dispose();
			}
		}
		isDirty = false; // Initial data is uploaded
	}

	/** Updates vertex data in an existing buffer. Currently re-uploads the entire buffer if dirty (inefficient for small
	 * updates). */
	@Override
	public void updateVertices (int targetOffset, float[] vertices, int sourceOffset, int count) {
		if (vertexBuffer == null) {
			Gdx.app.error(logTag, "updateVertices called before buffer created. Call setVertices first.");
			return; // Or maybe call createAndUploadInitialData? Let's require setVertices first.
		}
		if (isStatic) {
			Gdx.app.error(logTag, "Cannot update static vertex data.");
			return;
		}
		if (!isDirty && targetOffset == 0 && count == cpuBuffer.capacity()) {
			// If not explicitly marked dirty, but doing a full update, mark it
			// TODO: This logic might need refinement depending on how updates are triggered.
			// For now, assume if update is called, we should try to upload.
			// isDirty = true;
		}

		// TODO: Implement partial updates using staging buffer + vkCmdCopyBuffer with offsets.
		// For now, we re-upload the *entire* CPU buffer content if dirty (simple but slow).
		if (isDirty) {
			Gdx.app.log(logTag, "Uploading dirty vertex data (full buffer)...");
			long bufferSizeBytes = this.vertexBuffer.size;

			// Create a temporary staging buffer for the upload
			VulkanBuffer stagingBuffer = VulkanResourceUtil.createManagedBuffer(vmaAllocator, bufferSizeBytes,
				VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VMA_MEMORY_USAGE_AUTO,
				VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
			try {
				// Map, Copy current CPU buffer content, Unmap
				PointerBuffer pData = MemoryUtil.memAllocPointer(1);
				try {
					vkCheck(vmaMapMemory(vmaAllocator, stagingBuffer.allocationHandle, pData), "VMA map failed for vertex update");
					ByteBuffer stagingByteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int)bufferSizeBytes);
					((Buffer)cpuBuffer).position(0); // Ensure we copy from start
					((Buffer)cpuBuffer).limit(cpuBuffer.capacity()); // Ensure we copy full buffer capacity? Or just current limit? Use
																						// capacity for full re-upload.
					stagingByteBuffer.asFloatBuffer().put(cpuBuffer);
					vmaUnmapMemory(vmaAllocator, stagingBuffer.allocationHandle);
				} finally {
					MemoryUtil.memFree(pData);
				}

				// Copy Staging -> GPU
				final long srcHandle = stagingBuffer.bufferHandle;
				final long dstHandle = this.vertexBuffer.bufferHandle;
				device.executeSingleTimeCommands(cmd -> {
					try (MemoryStack stack = MemoryStack.stackPush()) {
						VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).size(bufferSizeBytes);
						vkCmdCopyBuffer(cmd, srcHandle, dstHandle, region);
					}
				});
				isDirty = false; // Mark as clean after upload
				Gdx.app.log(logTag, "Vertex data upload complete.");

			} catch (Exception e) {
				throw new GdxRuntimeException("Failed to update vertex data", e);
			} finally {
				if (stagingBuffer != null) {
					stagingBuffer.dispose();
				}
			}
		}
	}

	@Override
	public FloatBuffer getBuffer () {
		return null;
	}

	@Override
	public FloatBuffer getBuffer (boolean direct) {
		// Return the direct CPU-side buffer
		// Note: User should call flip() after putting data if using this directly.
		// And modifications might not be uploaded until next update/render if static.
		return direct ? cpuBuffer : null; // Only support direct buffer
	}

	@Override
	public void bind (ShaderProgram shader, int[] locations) {

	}

	@Override
	public void unbind (ShaderProgram shader, int[] locations) {

	}

	@Override
	public void invalidate () {

	}

	/** Binds the vertex buffer to the given command buffer. Must be called within a render pass instance.
	 * @param commandBuffer The active command buffer. */
	/*
	 * public void bind(VkCommandBuffer commandBuffer) { bind(commandBuffer, 0); // Bind to location 0 by default }
	 */

	/** Binds the vertex buffer to a specific binding point.
	 * @param commandBuffer The active command buffer.
	 * @param bindingPoint The binding point index. */
	/*
	 * public void bind(VkCommandBuffer commandBuffer, int bindingPoint) { if (vertexBuffer == null) { throw new
	 * GdxRuntimeException("Cannot bind vertex buffer before setVertices is called."); } // TODO: Handle dirty data upload here if
	 * deferred from setVertices/updateVertices for non-static buffers? // If deferred, need access to VulkanDevice for
	 * executeSingleTimeCommands. Requires careful synchronization. // For now, assume upload happened in
	 * setVertices/updateVertices.
	 * 
	 * try(MemoryStack stack = MemoryStack.stackPush()){ LongBuffer pBuffers = stack.longs(vertexBuffer.bufferHandle); LongBuffer
	 * pOffsets = stack.longs(0); // Starting offset is 0 vkCmdBindVertexBuffers(commandBuffer, bindingPoint, pBuffers, pOffsets);
	 * isBound = true; // Track state (optional) } }
	 */
/*
 *//** Unbinding is a no-op in Vulkan command buffers *//*
																			 * @Override public void unbind(ShaderProgram shader) { // Vulkan doesn't
																			 * have an explicit unbind for vertex buffers in the command stream. //
																			 * Binding is state within the command buffer. // We can just update our
																			 * optional tracking flag. isBound = false; }
																			 */

	@Override
	public void dispose () {
		Gdx.app.log(logTag, "Disposing VertexData...");
		if (vertexBuffer != null) {
			vertexBuffer.dispose(); // Calls vmaDestroyBuffer
			vertexBuffer = null;
		}
		if (cpuBuffer != null) {
			// If using MemoryUtil.memAlloc directly: MemoryUtil.memFree(cpuBuffer);
			// BufferUtils doesn't require explicit free? Check documentation. Assume GC for now.
			cpuBuffer = null;
		}
		Gdx.app.log(logTag, "VertexData disposed.");
	}

	/** Binds the vertex buffer to the given command buffer at binding point 0. Must be called within a render pass instance. NOTE:
	 * This is the Vulkan-specific bind method, NOT overriding VertexData.bind(ShaderProgram).
	 * @param commandBuffer The active command buffer. */
	public void bind (VkCommandBuffer commandBuffer) {
		bind(commandBuffer, 0); // Bind to location 0 by default
	}

	/** Binds the vertex buffer to a specific binding point. NOTE: This is the Vulkan-specific bind method, NOT overriding
	 * VertexData.bind(ShaderProgram).
	 * @param commandBuffer The active command buffer.
	 * @param bindingPoint The binding point index. */
	public void bind (VkCommandBuffer commandBuffer, int bindingPoint) {
		if (vertexBuffer == null) {
			throw new GdxRuntimeException("Cannot bind vertex buffer before setVertices is called.");
		}
		// TODO: Deferred dirty data upload could go here if needed

		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer pBuffers = stack.longs(vertexBuffer.bufferHandle);
			LongBuffer pOffsets = stack.longs(0);
			vkCmdBindVertexBuffers(commandBuffer, bindingPoint, pBuffers, pOffsets);
			isBound = true;
		}
	}

	/** Implements the VertexData interface method. In Vulkan, actual binding happens via vkCmdBindVertexBuffers with a
	 * VkCommandBuffer. This method might do nothing, or potentially store the shader if needed for attribute location lookups
	 * later (less common in Vulkan compared to GL). */
	@Override
	public void bind (ShaderProgram shader) {
		// Option 1: Do nothing, as binding requires the command buffer.
		// Option 2: Store the shader if you might need it later? Unlikely needed for vkCmdBindVertexBuffers.
		// Gdx.app.log(logTag, "VertexData.bind(ShaderProgram) called (No-op for Vulkan binding command)");
	}

	// Corrected unbind
	@Override
	public void unbind (ShaderProgram shader) {
		isBound = false;
	}

}
