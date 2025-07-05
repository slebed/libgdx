// Create new file: vulkan/VulkanImageView.java
package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkDevice; // Assuming you pass this from VulkanDevice
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanImageView {

    /**
     * Creates a VkImageView.
     * @param device The logical device.
     * @param image The VkImage handle.
     * @param format The VkFormat of the image.
     * @param aspectFlags How the image data will be used (e.g., VK_IMAGE_ASPECT_COLOR_BIT).
     * @return The handle to the created VkImageView.
     */
    public static long create(VkDevice device, long image, int format, int aspectFlags) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack);
            viewInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            viewInfo.image(image);
            viewInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(format);

            viewInfo.subresourceRange()
                    .aspectMask(aspectFlags)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pImageView = stack.mallocLong(1);
            if (vkCreateImageView(device, viewInfo, null, pImageView) != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to create texture image view!");
            }
            return pImageView.get(0);
        }
    }
}