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
        if (allocatorHandle != VK_NULL_HANDLE && imageHandle != VK_NULL_HANDLE && allocationHandle != VK_NULL_HANDLE) {
            Gdx.app.log("VulkanImage", "Disposing VMA Image: " + imageHandle + " Alloc: " + allocationHandle); // Add Log
            vmaDestroyImage(allocatorHandle, imageHandle, allocationHandle);
        }
    }
}