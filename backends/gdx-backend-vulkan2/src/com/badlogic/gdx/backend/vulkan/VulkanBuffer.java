package com.badlogic.gdx.backend.vulkan;

import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE; // It's good practice to use VK_NULL_HANDLE

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import org.lwjgl.system.MemoryUtil; // For MemoryUtil.NULL if preferred over VK_NULL_HANDLE for VMA handles

import java.nio.ByteBuffer; // Import ByteBuffer

public class VulkanBuffer implements Disposable {
    private static final String TAG = "VulkanBuffer";
    private static final boolean DEBUG = true; // Changed to DEBUG for consistency if you use this elsewhere

    public final long bufferHandle;
    public final long allocationHandle; // This is VmaAllocation handle
    public final long size;
    private final long allocatorHandle; // This is the VmaAllocator handle

    // --- NEW FIELD to store the persistently mapped buffer ---
    private final ByteBuffer mappedByteBuffer;

    /**
     * Constructor for buffers that are not (or not yet) persistently mapped by VMA,
     * or if mapping is handled externally.
     */
    public VulkanBuffer(long bufferHandle, long allocationHandle, long size, long allocatorHandle) {
        this(bufferHandle, allocationHandle, size, allocatorHandle, null); // Call the main constructor with null mappedBuffer
    }

    /**
     * Main constructor for VulkanBuffer.
     * @param bufferHandle The VkBuffer handle.
     * @param allocationHandle The VmaAllocation handle.
     * @param size The size of the buffer in bytes.
     * @param allocatorHandle The VmaAllocator handle used to create this buffer (for disposal).
     * @param mappedByteBuffer The ByteBuffer instance if this buffer is persistently mapped by VMA, otherwise null.
     */
    public VulkanBuffer(long bufferHandle, long allocationHandle, long size, long allocatorHandle, ByteBuffer mappedByteBuffer) {
        this.bufferHandle = bufferHandle;
        this.allocationHandle = allocationHandle;
        this.size = size;
        this.allocatorHandle = allocatorHandle;
        this.mappedByteBuffer = mappedByteBuffer; // Store the mapped buffer
    }

    /**
     * @return The native VkBuffer handle.
     */
    public long getBufferHandle() {
        return bufferHandle;
    }

    /**
     * @return The VMA allocation handle associated with this buffer.
     */
    public long getAllocationHandle() {
        return allocationHandle;
    }

    /**
     * @return The size of the buffer in bytes.
     */
    public long getSize() {
        return size;
    }

    /**
     * Returns the persistently mapped ByteBuffer if this buffer was created with
     * VMA_ALLOCATION_CREATE_MAPPED_BIT and VMA provided a mapped pointer.
     * Otherwise, returns null.
     * @return The mapped ByteBuffer or null.
     */
    public ByteBuffer getMappedByteBuffer() {
        return mappedByteBuffer;
    }


    /**
     * Gets the offset within the buffer. For UBOs in descriptor updates,
     * this is usually 0 unless you are binding a specific part of a larger UBO.
     * @return The offset (typically 0 for full buffer binding).
     */
    public long getOffset() {
        return 0;
    }

    /**
     * Gets the range of the buffer to be used in descriptor updates.
     * For UBOs, this is typically the full size of the data you intend to bind.
     * @return The range (typically the full buffer size or actual data size).
     */
    public long getRange() {
        return size;
    }

    @Override
    public void dispose() {
        // Use VMA to destroy buffer AND free allocation
        // Check against VK_NULL_HANDLE for Vulkan handles and VMA handles if they are long
        if (allocatorHandle != VK_NULL_HANDLE &&
                bufferHandle != VK_NULL_HANDLE &&
                allocationHandle != VK_NULL_HANDLE) { // VmaAllocation handle is a pointer, so NULL is also 0L

            if (DEBUG && Gdx.app != null) Gdx.app.log(TAG, "Disposing VMA Buffer: " + bufferHandle + " Alloc: " + allocationHandle);
            vmaDestroyBuffer(allocatorHandle, bufferHandle, allocationHandle);
            // No need to explicitly unmap mappedByteBuffer if VMA_ALLOCATION_CREATE_MAPPED_BIT was used;
            // vmaDestroyBuffer handles it.
        } else {
            if (DEBUG && Gdx.app != null) Gdx.app.log(TAG, "Skipping disposal of VMA Buffer (already null or invalid handles): Buffer=" + bufferHandle + ", Alloc=" + allocationHandle);
        }
        // To prevent accidental reuse after disposal, you might want to nullify if fields weren't final.
        // However, the object should not be used after dispose() is called.
    }
}