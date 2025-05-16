
package com.badlogic.gdx.backend.vulkan;

import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;

public class VulkanBuffer implements Disposable {
    private static final String TAG = "VulkanBuffer";
    private static final boolean debug = false;

    public final long bufferHandle;
    public final long allocationHandle;
    public final long size;
    private final long allocatorHandle;

    public VulkanBuffer(long bufferHandle, long allocationHandle, long size, long allocatorHandle) {
        this.bufferHandle = bufferHandle;
        this.allocationHandle = allocationHandle;
        this.size = size;
        this.allocatorHandle = allocatorHandle;
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
     * Gets the offset within the buffer. For basic UBOs created with this class,
     * the offset is typically 0. Subclasses might override this if needed.
     * @return The offset (usually 0).
     */
    public long getOffset() {
        // Default implementation assumes the buffer starts at offset 0
        // for descriptor updates. Override in subclasses if needed.
        return 0;
    }

    /**
     * Gets the range of the buffer to be used in descriptor updates.
     * For basic UBOs, this is typically the full size of the buffer.
     * @return The range (usually the full buffer size).
     */
    public long getRange() {
        // Default implementation uses the full buffer size as the range.
        // Override in subclasses if only a portion should be bound.
        return size;
    }

    @Override
    public void dispose() {
        // Use VMA to destroy buffer AND free allocation
        if (allocatorHandle != VK_NULL_HANDLE && bufferHandle != VK_NULL_HANDLE && allocationHandle != VK_NULL_HANDLE) {
            if (debug) Gdx.app.log(TAG, "Disposing VMA Buffer: " + bufferHandle + " Alloc: " + allocationHandle);
            vmaDestroyBuffer(allocatorHandle, bufferHandle, allocationHandle);
        } else {
            if (debug) Gdx.app.log(TAG, "Skipping disposal of VMA Buffer: " + bufferHandle + " Alloc: " + allocationHandle);
        }
    }
}
