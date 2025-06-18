
package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.IndexData;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanIndexData implements IndexData {
	private final String logTag = "VulkanIndexData";

	private final VulkanDevice device;
	private final long vmaAllocator;
	private final int indexCount;
	private final boolean isStatic;

	private VulkanBuffer indexBuffer; // VMA-managed GPU buffer
	private ShortBuffer cpuBuffer; // Direct buffer for CPU-side data
	private boolean isDirty = false;
	private boolean isBound = false;

	// Using ShortBuffer (VK_INDEX_TYPE_UINT16) by default
	private static final int INDEX_TYPE = VK_INDEX_TYPE_UINT16;
	private static final int INDEX_SIZE_BYTES = Short.BYTES;

	public VulkanIndexData (VulkanDevice device, long vmaAllocator, boolean isStatic, int numIndices) {
		this.device = device;
		this.vmaAllocator = vmaAllocator;
		this.isStatic = isStatic;
		this.indexCount = numIndices;

		this.cpuBuffer = BufferUtils.newShortBuffer(numIndices);
		((Buffer)this.cpuBuffer).limit(0);

		this.indexBuffer = null; // Create lazily
	}

	@Override
	public int getNumIndices () {
		return indexCount;
	}

	@Override
	public int getNumMaxIndices () {
		return indexCount;
	}

	/** Sets the index data. Creates GPU buffer on first call. */
	@Override
	public void setIndices (short[] indices, int offset, int count) {
		if (indexBuffer == null) {
			createAndUploadInitialData(indices, offset, count);
		} else {
			((Buffer)cpuBuffer).clear(); // Prepare buffer for writing from the start
			cpuBuffer.put(indices, offset, count); // Copy the data
			((Buffer)cpuBuffer).flip(); // Prepare buffer for reading (e.g., for upload)

			isDirty = true;
			Gdx.app.log(logTag, "setIndices: Marked " + count + " indices as dirty.");
			if (!isStatic) {
				updateIndices(0, indices, offset, count); // Consider if update logic needs adjustment too
			}
		}
	}

	@Override
	public void setIndices (ShortBuffer indices) {

	}

	private void createAndUploadInitialData (short[] indices, int offset, int count) {
		if (indexBuffer != null) return;

		long bufferSizeBytes = (long)indexCount * INDEX_SIZE_BYTES;
		if (bufferSizeBytes == 0) return;

		Gdx.app.log(logTag, "Creating index buffer (size: " + bufferSizeBytes + " bytes)");

		((Buffer)cpuBuffer).clear(); // Prepare buffer for writing from the start
		cpuBuffer.put(indices, offset, count); // Copy the data
		((Buffer)cpuBuffer).flip(); // Prepare buffer for reading (for upload to staging)

		VulkanBuffer stagingBuffer = VulkanResourceUtil.createManagedBuffer(vmaAllocator, bufferSizeBytes,
			VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VMA_MEMORY_USAGE_AUTO,
			VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
		try {
			PointerBuffer pData = MemoryUtil.memAllocPointer(1);
			try {
				vkCheck(vmaMapMemory(vmaAllocator, stagingBuffer.allocationHandle, pData), "VMA Failed to map index staging buffer");
				ByteBuffer stagingByteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int)bufferSizeBytes);
				stagingByteBuffer.asShortBuffer().put(cpuBuffer);
				vmaUnmapMemory(vmaAllocator, stagingBuffer.allocationHandle);
			} finally {
				MemoryUtil.memFree(pData);
			}

			this.indexBuffer = VulkanResourceUtil.createManagedBuffer(vmaAllocator, bufferSizeBytes,
				VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT, // Dest + Index
				VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0);

			final long srcHandle = stagingBuffer.bufferHandle;
			final long dstHandle = this.indexBuffer.bufferHandle;
			device.executeSingleTimeCommands(cmd -> {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).size(bufferSizeBytes);
					vkCmdCopyBuffer(cmd, srcHandle, dstHandle, region);
				}
			});
			Gdx.app.log(logTag, "Index buffer created and initial data uploaded.");

		} catch (Exception e) {
			if (this.indexBuffer != null) {
				this.indexBuffer.dispose();
				this.indexBuffer = null;
			}
			throw new GdxRuntimeException("Failed to create/upload initial index data", e);
		} finally {
			if (stagingBuffer != null) stagingBuffer.dispose();
		}
		isDirty = false;
	}

	/** Updates index data in an existing buffer. Currently re-uploads entire buffer if dirty. */
	@Override
	public void updateIndices (int targetOffset, short[] indices, int sourceOffset, int count) {
		if (indexBuffer == null) {
			Gdx.app.error(logTag, "updateIndices called before buffer created. Call setIndices first.");
			return;
		}
		if (isStatic) {
			Gdx.app.error(logTag, "Cannot update static index data.");
			return;
		}
		// TODO: Implement partial updates
		if (isDirty) {
			Gdx.app.log(logTag, "Uploading dirty index data (full buffer)...");
			long bufferSizeBytes = this.indexBuffer.size;
			VulkanBuffer stagingBuffer = VulkanResourceUtil.createManagedBuffer(vmaAllocator, bufferSizeBytes,
				VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VMA_MEMORY_USAGE_AUTO,
				VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
			try {
				PointerBuffer pData = MemoryUtil.memAllocPointer(1);
				try {
					vkCheck(vmaMapMemory(vmaAllocator, stagingBuffer.allocationHandle, pData), "VMA map failed for index update");
					ByteBuffer stagingByteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int)bufferSizeBytes);
					((Buffer)cpuBuffer).position(0);
					((Buffer)cpuBuffer).limit(cpuBuffer.capacity());
					stagingByteBuffer.asShortBuffer().put(cpuBuffer);
					vmaUnmapMemory(vmaAllocator, stagingBuffer.allocationHandle);
				} finally {
					MemoryUtil.memFree(pData);
				}

				final long srcHandle = stagingBuffer.bufferHandle;
				final long dstHandle = this.indexBuffer.bufferHandle;
				device.executeSingleTimeCommands(cmd -> {
					try (MemoryStack stack = MemoryStack.stackPush()) {
						VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).size(bufferSizeBytes);
						vkCmdCopyBuffer(cmd, srcHandle, dstHandle, region);
					}
				});
				isDirty = false;
				Gdx.app.log(logTag, "Index data upload complete.");
			} catch (Exception e) {
				throw new GdxRuntimeException("Failed to update index data", e);
			} finally {
				if (stagingBuffer != null) stagingBuffer.dispose();
			}
		}
	}

	@Override
	public ShortBuffer getBuffer () {
		return null;
	}

	@Override
	public ShortBuffer getBuffer (boolean direct) {
		return direct ? cpuBuffer : null;
	}

	@Override
	public void bind () {

	}

	/** Binds the index buffer to the given command buffer. Must be called within a render pass instance.
	 * @param commandBuffer The active command buffer. */
	public void bind (VkCommandBuffer commandBuffer) {
		if (indexBuffer == null) {
			throw new GdxRuntimeException("Cannot bind index buffer before setIndices is called.");
		}
		// TODO: Handle dirty data uploads if deferred? See VulkanVertexData.
		vkCmdBindIndexBuffer(commandBuffer, indexBuffer.bufferHandle, 0, INDEX_TYPE);
		isBound = true;
	}

	/** Unbinding is a no-op */
	@Override
	public void unbind () {
		isBound = false;
	}

	@Override
	public void invalidate () {

	}

	@Override
	public void dispose () {
		Gdx.app.log(logTag, "Disposing IndexData...");
		if (indexBuffer != null) {
			indexBuffer.dispose(); // Calls vmaDestroyBuffer
			indexBuffer = null;
		}
		if (cpuBuffer != null) {
			cpuBuffer = null; // Assume GC
		}
		Gdx.app.log(logTag, "IndexData disposed.");
	}

	// Overloads for IntBuffer if needed (VK_INDEX_TYPE_UINT32) - Requires changing INDEX_TYPE etc.
	// @Override public void setIndices(IntBuffer indices) { ... }
	// @Override public IntBuffer getBuffer() { ... }

}
