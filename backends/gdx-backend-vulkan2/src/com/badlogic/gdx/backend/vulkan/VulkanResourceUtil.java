package com.badlogic.gdx.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck; // Or check result directly
import static org.lwjgl.vulkan.VK10.*;

import com.badlogic.gdx.utils.GdxRuntimeException;

import java.nio.LongBuffer;

public class VulkanResourceUtil { // Example utility class

    /**
     * Creates a Vulkan buffer using VMA.
     *
     * @param allocator VMA allocator instance handle.
     * @param size Size of the buffer in bytes.
     * @param bufferUsageFlags VkBufferUsageFlags (e.g., VERTEX_BUFFER_BIT, TRANSFER_SRC_BIT).
     * @param vmaMemoryUsage VmaMemoryUsage hint (e.g., VMA_MEMORY_USAGE_AUTO, _GPU_ONLY, _CPU_ONLY).
     * @param vmaAllocFlags VmaAllocationCreateFlags (e.g., HOST_ACCESS_SEQUENTIAL_WRITE_BIT, MAPPED_BIT).
     * @return A VulkanBuffer wrapper object.
     */
    public static VulkanBuffer createManagedBuffer(long allocator, long size, int bufferUsageFlags, int vmaMemoryUsage, int vmaAllocFlags) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(bufferUsageFlags)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE); // Assuming exclusive access for simplicity

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(vmaMemoryUsage) // VMA hint for memory type/location
                    .flags(vmaAllocFlags); // Allocation flags (mappable, etc.)
            // Optional: .requiredFlags(), .preferredFlags(), .pool(), .pUserData()

            LongBuffer pBuffer = stack.mallocLong(1);       // To receive VkBuffer handle
            PointerBuffer pAllocation = stack.mallocPointer(1);   // To receive VmaAllocation handle
            // VmaAllocationInfo pAllocationInfo = VmaAllocationInfo.calloc(stack); // Optional: To get details about the allocation

            vkCheck(vmaCreateBuffer(allocator, bufferInfo, allocInfo, pBuffer, pAllocation, null /* or pAllocationInfo */),
                    "VMA failed to create buffer");

            long bufferHandle = pBuffer.get(0);
            long allocationHandle = pAllocation.get(0);

            if (bufferHandle == VK_NULL_HANDLE || allocationHandle == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("VMA buffer creation returned NULL handle(s) despite success code.");
            }

            return new VulkanBuffer(bufferHandle, allocationHandle, size, allocator);
        }
    }

    /**
     * Creates a Vulkan image using VMA.
     * (Simplified - add parameters for tiling, layers, mip levels etc. as needed)
     *
     * @param allocator VMA allocator instance handle.
     * @param width Image width.
     * @param height Image height.
     * @param format VkFormat.
     * @param tiling VkImageTiling (OPTIMAL or LINEAR).
     * @param imageUsageFlags VkImageUsageFlags (e.g., SAMPLED_BIT, TRANSFER_DST_BIT, COLOR_ATTACHMENT_BIT).
     * @param vmaMemoryUsage VmaMemoryUsage hint.
     * @param vmaAllocFlags VmaAllocationCreateFlags.
     * @return A VulkanImage wrapper object.
     */
    public static VulkanImage createManagedImage(long allocator, int width, int height, int format, int tiling, int imageUsageFlags, int vmaMemoryUsage, int vmaAllocFlags) {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(1) // TODO: Parameterize
                    .arrayLayers(1) // TODO: Parameterize
                    .samples(VK_SAMPLE_COUNT_1_BIT) // TODO: Parameterize for MSAA
                    .tiling(tiling)
                    .usage(imageUsageFlags)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED); // Will need transition

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(vmaMemoryUsage)
                    .flags(vmaAllocFlags);
            // Use VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT in flags if required (check vkGetImageMemoryRequirements2)

            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);

            vkCheck(vmaCreateImage(allocator, imageInfo, allocInfo, pImage, pAllocation, null),
                    "VMA failed to create image");

            long imageHandle = pImage.get(0);
            long allocationHandle = pAllocation.get(0);

            if (imageHandle == VK_NULL_HANDLE || allocationHandle == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("VMA image creation returned NULL handle(s) despite success code.");
            }

            return new VulkanImage(imageHandle, allocationHandle, format, width, height, allocator);
        }
    }
} // End VulkanResourceUtil