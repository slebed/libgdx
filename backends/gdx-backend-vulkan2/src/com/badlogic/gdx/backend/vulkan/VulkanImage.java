
package com.badlogic.gdx.backend.vulkan;

import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;

public class VulkanImage implements Disposable {
    public final long imageHandle;
    public final long allocationHandle; // VmaAllocation handle
    public final int format;
    public final int width;
    public final int height;
    // Add extent, depth, layers, mipLevels if needed
    private final long allocatorHandle;
    private boolean disposed = false; // Add this flag

    public VulkanImage(long imageHandle, long allocationHandle, int format, int width, int height, long allocatorHandle) {
        this.imageHandle = imageHandle;
        this.allocationHandle = allocationHandle;
        this.format = format;
        this.width = width;
        this.height = height;
        this.allocatorHandle = allocatorHandle;
    }

    @Override
    public void dispose() {
        if (disposed) {
            // Gdx.app.debug("VulkanImage", "Already disposed."); // Optional log
            return;
        }

        // Use the allocator handle stored during construction for the check
        if (this.allocatorHandle == VK_NULL_HANDLE) {
            Gdx.app.error("VulkanImage", "Cannot dispose, stored VMA Allocator handle is null!");
            disposed = true; // Mark disposed even if we can't clean up
            return;
        }

        // Check if image/allocation handles seem valid before calling destroy
        if (imageHandle != VK_NULL_HANDLE && allocationHandle != VK_NULL_HANDLE) {
            Gdx.app.log("VulkanImage", "Disposing VMA Image: " + imageHandle + " Alloc: " + allocationHandle);
            // Call VMA destroy using the stored allocator handle
            vmaDestroyImage(this.allocatorHandle, imageHandle, allocationHandle);
        } else {
            // Log if handles were already null - indicates potential issue elsewhere or prior disposal attempt
            Gdx.app.debug("VulkanImage", "Skipping vmaDestroyImage because imageHandle or allocationHandle is VK_NULL_HANDLE.");
        }

        disposed = true; // Mark as disposed AFTER attempting cleanup
    }
}
