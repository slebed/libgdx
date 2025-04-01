package com.badlogic.gdx.backends.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkCreateSwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkGetSwapchainImagesKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

public class VkSwapchain implements VkResource {

    private final long swapchain;
    private final VkDevice device;
    private final List<Long> imageViews = new ArrayList<>();
    private final int colorFormat;  // Store the color format

    private VkSwapchain(long swapchain, VkDevice device, List<Long> imageViews, int colorFormat) {
        this.swapchain = swapchain;
        this.device = device;
        this.imageViews.addAll(imageViews);
        this.colorFormat = colorFormat;  // Set the color format
    }

    public long getHandle() {
        return swapchain;
    }

    public List<Long> getImageViews() {
        return imageViews;
    }

    public int getColorFormat() {  // Provide a getter for the color format
        return colorFormat;
    }

    @Override
    public void cleanup() {
        for (long imageView : imageViews) {
            VkMemoryUtil.safeDestroyImageView(imageView, device.getRawDevice());
        }
        VkMemoryUtil.safeDestroySwapchain(swapchain, device.getRawDevice());
    }

    public static class Builder {
        private VkDevice device;
        private VkPhysicalDevice physicalDevice;
        private long surface;
        private int width;
        private int height;

        public Builder setDevice(VkDevice device) {
            this.device = device;
            return this;
        }

        public Builder setPhysicalDevice(VkPhysicalDevice physicalDevice) {
            this.physicalDevice = physicalDevice;
            return this;
        }

        public Builder setSurface(long surface) {
            this.surface = surface;
            return this;
        }

        public Builder setWidth(int width) {
            this.width = width;
            return this;
        }

        public Builder setHeight(int height) {
            this.height = height;
            return this;
        }

        public VkSwapchain build() {
            if (device == null || physicalDevice == null || surface == 0) {
                throw new IllegalStateException("Device, PhysicalDevice, and Surface must be set before building VkSwapchain.");
            }

            try (MemoryStack stack = stackPush()) {
                VkSurfaceCapabilitiesKHR surfaceCapabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
                vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, surfaceCapabilities);

                IntBuffer formatCount = stack.mallocInt(1);
                vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, null);

                VkSurfaceFormatKHR.Buffer surfaceFormats = VkSurfaceFormatKHR.calloc(formatCount.get(0), stack);
                vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, surfaceFormats);

                VkSurfaceFormatKHR chosenFormat = surfaceFormats.get(0);
                int imageFormat = chosenFormat.format();

                IntBuffer presentModeCount = stack.mallocInt(1);
                vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, presentModeCount, null);

                IntBuffer presentModes = stack.mallocInt(presentModeCount.get(0));
                vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, presentModeCount, presentModes);

                int chosenPresentMode = VK_PRESENT_MODE_FIFO_KHR;

                VkExtent2D extent = VkExtent2D.calloc(stack);
                extent.width(Math.max(surfaceCapabilities.minImageExtent().width(), Math.min(surfaceCapabilities.maxImageExtent().width(), width)));
                extent.height(Math.max(surfaceCapabilities.minImageExtent().height(), Math.min(surfaceCapabilities.maxImageExtent().height(), height)));

                int imageCount = surfaceCapabilities.minImageCount() + 1;
                if (surfaceCapabilities.maxImageCount() > 0 && imageCount > surfaceCapabilities.maxImageCount()) {
                    imageCount = surfaceCapabilities.maxImageCount();
                }

                LongBuffer pSwapchain = stack.mallocLong(1);
                VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(imageFormat)
                    .imageColorSpace(chosenFormat.colorSpace())
                    .imageExtent(extent)
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(surfaceCapabilities.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(chosenPresentMode)
                    .clipped(true)
                    .oldSwapchain(VK_NULL_HANDLE);

                VkMemoryUtil.vkCheck(vkCreateSwapchainKHR(device.getRawDevice(), createInfo, null, pSwapchain), "Failed to create swapchain.");

                long swapchain = pSwapchain.get(0);

                IntBuffer pImageCount = stack.mallocInt(1);
                vkGetSwapchainImagesKHR(device.getRawDevice(), swapchain, pImageCount, null);
                int swapchainImageCount = pImageCount.get(0);

                LongBuffer pImages = stack.mallocLong(swapchainImageCount);
                vkGetSwapchainImagesKHR(device.getRawDevice(), swapchain, pImageCount, pImages);

                List<Long> imageViews = new ArrayList<>();
                for (int i = 0; i < swapchainImageCount; i++) {
                    long imageView = createImageView(pImages.get(i), imageFormat);
                    imageViews.add(imageView);
                }

                return new VkSwapchain(swapchain, device, imageViews, imageFormat);
            }
        }

        private long createImageView(long image, int imageFormat) {
            try (MemoryStack stack = stackPush()) {
                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(imageFormat)
                    .subresourceRange(range -> range
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1));

                LongBuffer pImageView = stack.mallocLong(1);
                VkMemoryUtil.vkCheck(vkCreateImageView(device.getRawDevice(), viewInfo, null, pImageView), "Failed to create Image View.");
                return pImageView.get(0);
            }
        }
    }
}
