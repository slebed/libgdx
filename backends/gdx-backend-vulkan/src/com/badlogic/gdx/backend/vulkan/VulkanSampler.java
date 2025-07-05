/*
// Create new file: vulkan/VulkanSampler.java
package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanSampler {

    */
/**
     * Creates a VkSampler.
     * @param device The logical device.
     * @return The handle to the created VkSampler.
     *//*

    public static long create(VkDevice device, TextureFilter minFilter, TextureFilter magFilter, TextureWrap uWrap, TextureWrap vWrap) {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack);
            samplerInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
            samplerInfo.magFilter(VulkanFormatUtils.textureFilterToVkFilter(magFilter));
            samplerInfo.minFilter(VulkanFormatUtils.textureFilterToVkFilter(minFilter));
            samplerInfo.addressModeU(VulkanFormatUtils.textureWrapToVkSamplerAddressMode(uWrap));
            samplerInfo.addressModeV(VulkanFormatUtils.textureWrapToVkSamplerAddressMode(vWrap));
            samplerInfo.addressModeW(VulkanFormatUtils.textureWrapToVkSamplerAddressMode(vWrap));
            samplerInfo.anisotropyEnable(false);
            samplerInfo.maxAnisotropy(1.0f);
            samplerInfo.borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK);
            samplerInfo.unnormalizedCoordinates(false);
            samplerInfo.compareEnable(false);
            samplerInfo.compareOp(VK_COMPARE_OP_ALWAYS);
            samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR);
            samplerInfo.mipLodBias(0.0f);
            samplerInfo.minLod(0.0f);
            samplerInfo.maxLod(0.0f);

            LongBuffer pSampler = stack.mallocLong(1);
            if (vkCreateSampler(device, samplerInfo, null, pSampler) != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to create texture sampler!");
            }
            return pSampler.get(0);
        }
    }
}*/
