package com.badlogic.gdx.backend.vulkan; // Example package

import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;

/**
 * Represents a Vulkan Buffer specifically allocated for use as a Uniform Buffer Object (UBO).
 * Provides methods for creation, updating data from the CPU, and cleanup.
 * Assumes usage with VMA (Vulkan Memory Allocator).
 */
public class VulkanUniformBuffer implements Disposable {

    private final long vmaAllocator;
    private final VulkanBuffer internalBuffer; // Composition: Holds the generic buffer resource
    private final long size; // Keep track of the intended size

    /**
     * Creates a new VulkanUniformBuffer.
     *
     * @param vmaAllocator The VMA allocator instance.
     * @param size         The size of the uniform buffer in bytes.
     */
    public VulkanUniformBuffer(long vmaAllocator, long size) {
        if (vmaAllocator == VK_NULL_HANDLE) {
            throw new IllegalArgumentException("VMA Allocator handle cannot be VK_NULL_HANDLE");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Uniform buffer size must be positive.");
        }
        this.vmaAllocator = vmaAllocator;
        this.size = size;

        // Use the same utility as VulkanSpriteBatch to create the underlying buffer
        // Ensure HOST_ACCESS for CPU updates. VMA_MEMORY_USAGE_AUTO lets VMA pick
        // appropriate memory (likely DEVICE_LOCAL | HOST_VISIBLE if available and needed,
        // or HOST_VISIBLE | HOST_COHERENT otherwise).
        this.internalBuffer = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator,
                size,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO, // Let VMA decide optimal memory type for UBO
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT // Or _RANDOM if updates aren't linear
                // We are *not* using VMA_ALLOCATION_CREATE_MAPPED_BIT here,
                // choosing temporary mapping in the update methods instead.
        );

        if (this.internalBuffer == null || this.internalBuffer.bufferHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to create internal VulkanBuffer for VulkanUniformBuffer");
        }
    }

    /**
     * Updates the uniform buffer data using temporary mapping.
     * This maps the buffer, copies the data, flushes if necessary, and unmaps.
     *
     * @param data       The ByteBuffer containing the data to upload. Must have remaining() >= size.
     * @param dataOffset The offset in the source data ByteBuffer to start reading from.
     */
    public void update(ByteBuffer data, int dataOffset) {
        if (data == null) {
            throw new IllegalArgumentException("Source data ByteBuffer cannot be null.");
        }
        long dataToCopy = Math.min(this.size, data.remaining() - dataOffset);
        if (dataToCopy <= 0) {
            System.err.println("WARN: VulkanUniformBuffer update called with no data to copy.");
            return; // Nothing to copy
        }
        if (dataToCopy > this.size) {
            throw new GdxRuntimeException("Cannot copy " + dataToCopy + " bytes into a UBO of size " + this.size);
            // Or log a warning and only copy this.size bytes
        }


        PointerBuffer pData = MemoryUtil.memAllocPointer(1);
        try {
            // 1. Map Memory
            vkCheck(Vma.vmaMapMemory(vmaAllocator, internalBuffer.allocationHandle, pData),
                    "VMA failed to map memory for UBO update");
            ByteBuffer mapped = MemoryUtil.memByteBuffer(pData.get(0), (int) this.size);

            // 2. Copy Data
            // Save state
            int originalLimit = data.limit();
            int originalPos = data.position();
            // Set position and limit for the slice to copy
            data.position(dataOffset);
            data.limit(dataOffset + (int) dataToCopy);
            // Perform the copy
            mapped.put(data);
            // Restore state
            data.limit(originalLimit);
            data.position(originalPos);

            // 3. Flush Allocation (Important if memory is not HOST_COHERENT)
            // Flushing ensures CPU writes are visible to the GPU before unmapping.
            // Even if potentially coherent, flushing is usually safe.
            Vma.vmaFlushAllocation(vmaAllocator, internalBuffer.allocationHandle, 0, dataToCopy);

            // 4. Unmap Memory
            Vma.vmaUnmapMemory(vmaAllocator, internalBuffer.allocationHandle);

        } catch (Exception e) {
            // Ensure unmap happens if map succeeded but subsequent steps failed
            if (pData.get(0) != 0) { // Check if map succeeded
                try {
                    Vma.vmaUnmapMemory(vmaAllocator, internalBuffer.allocationHandle);
                } catch (Exception unmapE) { /* Log? */ }
            }
            throw new GdxRuntimeException("Failed during UBO update", e);
        } finally {
            MemoryUtil.memFree(pData); // Clean up the pointer buffer
        }
    }

    /**
     * Convenience method to update the buffer from a FloatBuffer.
     * Assumes the FloatBuffer contains data starting from position 0.
     *
     * @param data The FloatBuffer containing the data.
     */
    public void update(FloatBuffer data) {
        if (data == null) {
            throw new IllegalArgumentException("Source data FloatBuffer cannot be null.");
        }
        // Use BufferUtils or direct ByteBuffer view for the update
        ByteBuffer byteData = BufferUtils.newByteBuffer((int) this.size); // Allocate temporary byte buffer
        // Save state
        int originalPos = data.position();
        data.position(0); // Ensure reading from start
        // Copy floats to byte buffer
        byteData.asFloatBuffer().put(data);
        // Restore state
        data.position(originalPos);

        byteData.rewind(); // Prepare byte buffer for reading
        update(byteData, 0);
    }

    // --- Getters for Descriptor Updates ---

    /**
     * Gets the underlying Vulkan buffer handle.
     *
     * @return The VkBuffer handle.
     */
    public long getBufferHandle() {
        return internalBuffer.bufferHandle;
    }

    /**
     * Gets the offset within the buffer where the UBO data starts.
     * For a dedicated VulkanUniformBuffer, this is typically 0.
     *
     * @return The offset (usually 0).
     */
    public long getOffset() {
        // Assuming the UBO occupies the entire buffer allocation
        return 0;
    }

    /**
     * Gets the size (range) of the uniform buffer data.
     *
     * @return The size in bytes.
     */
    public long getRange() {
        return this.size;
    }

    /**
     * Gets the size of the uniform buffer data. (Alternative name for getRange)
     *
     * @return The size in bytes.
     */
    public long getSize() {
        return this.size;
    }

    // --- Cleanup ---

    @Override
    public void dispose() {
        // The internal VulkanBuffer's dispose method handles vkDestroyBuffer and vmaFreeMemory
        if (internalBuffer != null) {
            internalBuffer.dispose();
        }
    }
}