package com.badlogic.gdx.backend.vulkan;

import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;

public class VulkanBuffer implements Disposable {
    public final long bufferHandle;
    public final long allocationHandle; // VmaAllocation handle
    public final long size;
    private final long allocatorHandle; // Reference to the VMA allocator

    public VulkanBuffer(long bufferHandle, long allocationHandle, long size, long allocatorHandle) {
        this.bufferHandle = bufferHandle;
        this.allocationHandle = allocationHandle;
        this.size = size;
        this.allocatorHandle = allocatorHandle;
    }

    @Override
    public void dispose() {
        // Use VMA to destroy buffer AND free allocation
        if (allocatorHandle != VK_NULL_HANDLE && bufferHandle != VK_NULL_HANDLE && allocationHandle != VK_NULL_HANDLE) {
            Gdx.app.log("VulkanBuffer", "Disposing VMA Buffer: " + bufferHandle + " Alloc: " + allocationHandle); // Add Log
            vmaDestroyBuffer(allocatorHandle, bufferHandle, allocationHandle);
        }
        // Avoid double-free by nulling handles? Or rely on caller not to call dispose twice.
    }
}