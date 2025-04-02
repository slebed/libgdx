/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backends.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_IDENTITY;
import static org.lwjgl.vulkan.VK10.VK_FENCE_CREATE_SIGNALED_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_EXTERNAL;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateFramebuffer;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkCreateRenderPass;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.AbstractGraphics;
import com.badlogic.gdx.Application;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;

import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.Cursor.SystemCursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GL31;
import com.badlogic.gdx.graphics.GL32;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Disposable;

import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

public class VulkanGraphics extends AbstractGraphics implements Disposable {
    final VulkanWindow window;
    private volatile boolean framebufferResized = false; // Flag for resize/vsync change
    private long swapchain = VK_NULL_HANDLE;
    private List<Long> swapchainImages; // VkImage handles
    private int swapchainImageFormat;   // VkFormat enum value
    private VkExtent2D swapchainExtent;
    private List<Long> swapchainImageViews; // VkImageView handles
    private long renderPass = VK_NULL_HANDLE;
    private List<Long> swapchainFramebuffers; // VkFramebuffer handles
    private boolean vsyncEnabled = true; // Store desired vsync state, default true
    private List<VkCommandBuffer> commandBuffers; // VkCommandBuffer objects

    // Sync objects (basic, 1 frame in flight)
    private long imageAvailableSemaphore = VK_NULL_HANDLE;
    private long renderFinishedSemaphore = VK_NULL_HANDLE;
    private long inFlightFence = VK_NULL_HANDLE;

    // Assuming access to VulkanApplication components
    private VulkanApplication app = (VulkanApplication) Gdx.app; // Or get via constructor arg
    private VulkanInstance vkInstance = app.getVulkanInstance(); // Need getter in VulkanApplication
    private VkDevice vkDevice = app.getVkDevice();           // Need getter in VulkanApplication
    private long surface;     // Need getter in VulkanApplication
    private VkPhysicalDevice physicalDevice = vkDevice.getPhysicalDevice(); // Get from VkDevice wrapper
    private org.lwjgl.vulkan.VkDevice rawDevice = vkDevice.getRawDevice(); // Get raw device from VkDevice wrapper
    private VkQueue graphicsQueue = vkDevice.getGraphicsQueue();       // Get from VkDevice wrapper
    private VkQueue presentQueue = graphicsQueue; // Assume graphics and present are the same for simplicity
    private GLVersion glVersion;
    private volatile int backBufferWidth;
    private volatile int backBufferHeight;
    private volatile int logicalWidth;
    private volatile int logicalHeight;
    private volatile boolean isContinuous = true;
    private BufferFormat bufferFormat;
    private long lastFrameTime = -1;
    private float deltaTime;
    private boolean resetDeltaTime = false;
    private long frameId;
    private long frameCounterStart = 0;
    private int frames;
    private int fps;
    private int windowPosXBeforeFullscreen;
    private int windowPosYBeforeFullscreen;
    private int windowWidthBeforeFullscreen;
    private int windowHeightBeforeFullscreen;
    private DisplayMode displayModeBeforeFullscreen = null;

    IntBuffer tmpBuffer = BufferUtils.createIntBuffer(1);
    IntBuffer tmpBuffer2 = BufferUtils.createIntBuffer(1);

    GLFWFramebufferSizeCallback resizeCallback = new GLFWFramebufferSizeCallback() {
        @Override
        public void invoke(long windowHandle, final int width, final int height) {
            // This callback might be invoked from a GLFW thread.
            // Simply set a flag to be handled by the main render thread.
            if (width > 0 && height > 0) {
                // Check against current dimensions if needed to avoid redundant flags
                // if (width != backBufferWidth || height != backBufferHeight) {
                VulkanGraphics.this.framebufferResized = true;
                System.out.println("Framebuffer resize requested: " + width + "x" + height);
                // }
            }
        }
    };

    public VulkanGraphics(VulkanWindow window) {
        this.window = window;
        updateFramebufferInfo(); // Get initial size info

        GLFW.glfwSetFramebufferSizeCallback(window.getWindowHandle(), resizeCallback);
    }

    public VulkanGraphics(long windowHandle, VulkanApplicationConfiguration config) {
        super();
    }

    public VulkanWindow getWindow() {
        return window;
    }

    // Contains the logic previously in initiateVulkan()
    public void initializeSwapchainAndResources() {
        // Now it's safe to get these, assuming VulkanApplication initialized them
        this.app = (VulkanApplication)Gdx.app; // Or get app reference passed differently
        this.vkInstance = app.getVulkanInstance();
        this.vkDevice = app.getVkDevice();
        this.surface = app.getSurface(this.window.getWindowHandle()); // Surface is per-window! Need a way to get the right one.
        this.physicalDevice = vkDevice.getPhysicalDevice();
        this.rawDevice = vkDevice.getRawDevice();
        this.graphicsQueue = vkDevice.getGraphicsQueue();
        this.presentQueue = graphicsQueue; // Assuming same queue

        if(this.vkDevice == null || this.surface == VK_NULL_HANDLE){
            throw new GdxRuntimeException("Cannot initialize VulkanGraphics resources before VkDevice and Surface are created.");
        }

        System.out.println("VulkanGraphics: Initializing swapchain and resources...");
        try (MemoryStack stack = stackPush()) {
            createSwapchain(stack);
            System.out.println("Swapchain created.");
            createImageViews(stack);
            System.out.println("ImageViews created.");
            createRenderPass(stack);
            System.out.println("RenderPass created.");
            createFramebuffers(stack);
            System.out.println("Framebuffers created.");
            createCommandBuffers(stack);
            System.out.println("CommandBuffers created.");
            createSyncObjects(stack);
            System.out.println("Sync objects created.");
        } catch (Exception e) {
            cleanupVulkan();
            throw new GdxRuntimeException("Failed to initialize Vulkan graphics resources", e);
        }
    }

    private void createSwapchain(MemoryStack stack) {
        // --- Query Surface Capabilities ---
        VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
        KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities);

        // --- Query Supported Formats ---
        IntBuffer formatCount = stack.mallocInt(1);
        KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, null);
        VkSurfaceFormatKHR.Buffer surfaceFormats = null;
        if (formatCount.get(0) > 0) {
            surfaceFormats = VkSurfaceFormatKHR.calloc(formatCount.get(0), stack);
            KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, surfaceFormats);
        } else {
            throw new GdxRuntimeException("No surface formats supported");
        }

        // --- Query Supported Present Modes ---
        IntBuffer presentModeCount = stack.mallocInt(1);
        KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, presentModeCount, null);
        IntBuffer presentModes = null;
        if (presentModeCount.get(0) > 0) {
            presentModes = stack.mallocInt(presentModeCount.get(0));
            KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, presentModeCount, presentModes);
        } else {
            throw new GdxRuntimeException("No present modes supported");
        }

        // --- Choose Swapchain Settings ---
        VkSurfaceFormatKHR chosenFormat = chooseSwapSurfaceFormat(surfaceFormats);
        int chosenPresentMode = chooseSwapPresentMode(presentModes);
        VkExtent2D chosenExtent = chooseSwapExtent(capabilities);

        int imageCount = capabilities.minImageCount() + 1;
        if (capabilities.maxImageCount() > 0 && imageCount > capabilities.maxImageCount()) {
            imageCount = capabilities.maxImageCount();
        }

        this.swapchainImageFormat = chosenFormat.format();
        this.swapchainExtent = VkExtent2D.create().set(chosenExtent); // Store a copy

        // --- Create Swapchain ---
        VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(surface)
                .minImageCount(imageCount)
                .imageFormat(chosenFormat.format())
                .imageColorSpace(chosenFormat.colorSpace())
                .imageExtent(chosenExtent)
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT); // Add other usages if needed (e.g., transfer_dst)

        // Check queue families (assuming graphics and present are the same for simplicity)
        int queueFamilyIndex = vkDevice.getQueueFamilyIndex(); // Need getter in VkDevice
        createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE) // Or CONCURRENT if needed
                .pQueueFamilyIndices(null); // Ignored for EXCLUSIVE

        createInfo.preTransform(capabilities.currentTransform())
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) // Or other supported modes
                .presentMode(chosenPresentMode)
                .clipped(true)
                .oldSwapchain(VK_NULL_HANDLE); // Handle recreation later

        LongBuffer pSwapchain = stack.mallocLong(1);
        VkMemoryUtil.vkCheck(KHRSwapchain.vkCreateSwapchainKHR(rawDevice, createInfo, null, pSwapchain),
                "Failed to create swap chain");
        this.swapchain = pSwapchain.get(0);

        // --- Get Swapchain Images ---
        IntBuffer swapchainImageCount = stack.mallocInt(1);
        KHRSwapchain.vkGetSwapchainImagesKHR(rawDevice, swapchain, swapchainImageCount, null);
        int actualImageCount = swapchainImageCount.get(0);
        LongBuffer pSwapchainImages = stack.mallocLong(actualImageCount);
        KHRSwapchain.vkGetSwapchainImagesKHR(rawDevice, swapchain, swapchainImageCount, pSwapchainImages);

        this.swapchainImages = new ArrayList<>(actualImageCount);
        for (int i = 0; i < actualImageCount; i++) {
            this.swapchainImages.add(pSwapchainImages.get(i));
        }
    }

    // --- Placeholder helper methods for choosing swapchain settings ---
    private VkSurfaceFormatKHR chooseSwapSurfaceFormat(VkSurfaceFormatKHR.Buffer availableFormats) {
        // Prefer B8G8R8A8_SRGB if available, otherwise take the first one
        for (VkSurfaceFormatKHR format : availableFormats) {
            if (format.format() == VK_FORMAT_B8G8R8A8_SRGB && format.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return format;
            }
        }
        return availableFormats.get(0);
    }

    private int chooseSwapPresentMode(IntBuffer availablePresentModes) {
        if (this.vsyncEnabled) {
            // Standard VSync - guaranteed to be available
            return VK_PRESENT_MODE_FIFO_KHR;
        } else {
            // Try for Mailbox (low latency, no tearing) if VSync off
            for (int i = 0; i < availablePresentModes.limit(); i++) {
                if (availablePresentModes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                    System.out.println("Using Present Mode: MAILBOX");
                    return VK_PRESENT_MODE_MAILBOX_KHR;
                }
            }
            // Try Immediate (no sync, potential tearing) if Mailbox not found
            for (int i = 0; i < availablePresentModes.limit(); i++) {
                if (availablePresentModes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR) {
                    System.out.println("Using Present Mode: IMMEDIATE");
                    return VK_PRESENT_MODE_IMMEDIATE_KHR;
                }
            }
            // Fallback to FIFO if Mailbox/Immediate aren't available (shouldn't happen often)
            System.out.println("Using Present Mode: FIFO (Fallback)");
            return VK_PRESENT_MODE_FIFO_KHR;
        }
    }

    private VkExtent2D chooseSwapExtent(VkSurfaceCapabilitiesKHR capabilities) {
        if (capabilities.currentExtent().width() != 0xFFFFFFFF) { // 0xFFFFFFFF means window manager controls size
            return capabilities.currentExtent();
        } else {
            // Window manager doesn't dictate size, use our backbuffer size clamped to capabilities
            VkExtent2D actualExtent = VkExtent2D.mallocStack(); // Needs to be allocated properly
            actualExtent.width(Math.max(capabilities.minImageExtent().width(),
                    Math.min(capabilities.maxImageExtent().width(), getBackBufferWidth())));
            actualExtent.height(Math.max(capabilities.minImageExtent().height(),
                    Math.min(capabilities.maxImageExtent().height(), getBackBufferHeight())));
            return actualExtent;
        }
    }
// Note: The allocation of actualExtent needs care if chooseSwapExtent is called within the main try-with-resources MemoryStack block. It might be better to allocate it outside or ensure the stack frame lives long enough. Allocating with VkExtent2D.create() might be safer if it's stored long-term. Let's store a copy in createSwapchain instead.

    private void createImageViews(MemoryStack stack) {
        swapchainImageViews = new ArrayList<>(swapchainImages.size());

        for (long image : swapchainImages) {
            VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(swapchainImageFormat)
                    .components(c -> c.r(VK_COMPONENT_SWIZZLE_IDENTITY)
                            .g(VK_COMPONENT_SWIZZLE_IDENTITY)
                            .b(VK_COMPONENT_SWIZZLE_IDENTITY)
                            .a(VK_COMPONENT_SWIZZLE_IDENTITY))
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1));

            LongBuffer pImageView = stack.mallocLong(1);
            VkMemoryUtil.vkCheck(vkCreateImageView(rawDevice, createInfo, null, pImageView),
                    "Failed to create image view");
            swapchainImageViews.add(pImageView.get(0));
        }
    }

    private void createRenderPass(MemoryStack stack) {
        VkAttachmentDescription.Buffer colorAttachment = VkAttachmentDescription.calloc(1, stack)
                .format(swapchainImageFormat)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR); // Layout for presentation

        VkAttachmentReference.Buffer colorAttachmentRef = VkAttachmentReference.calloc(1, stack)
                .attachment(0) // Index of the attachment in the pAttachments array
                .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                .colorAttachmentCount(1)
                .pColorAttachments(colorAttachmentRef);
        // Add depth attachment reference here if using depth buffer

        // Subpass dependency to synchronize layout transition
        VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack)
                .srcSubpass(VK_SUBPASS_EXTERNAL) // Implicit subpass before render pass
                .dstSubpass(0) // Our first subpass
                .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .srcAccessMask(0)
                .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);


        VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                .pAttachments(colorAttachment)
                .pSubpasses(subpass)
                .pDependencies(dependency); // Add dependency

        LongBuffer pRenderPass = stack.mallocLong(1);
        VkMemoryUtil.vkCheck(vkCreateRenderPass(rawDevice, renderPassInfo, null, pRenderPass),
                "Failed to create render pass");
        this.renderPass = pRenderPass.get(0);
    }

    private void createFramebuffers(MemoryStack stack) {
        swapchainFramebuffers = new ArrayList<>(swapchainImageViews.size());
        LongBuffer attachments = stack.mallocLong(1); // Buffer to hold image view handle

        for (long imageView : swapchainImageViews) {
            attachments.put(0, imageView); // Put the current image view handle in the buffer

            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(attachments) // Pass the buffer containing the image view handle
                    .width(swapchainExtent.width())
                    .height(swapchainExtent.height())
                    .layers(1);

            LongBuffer pFramebuffer = stack.mallocLong(1);
            VkMemoryUtil.vkCheck(vkCreateFramebuffer(rawDevice, framebufferInfo, null, pFramebuffer),
                    "Failed to create framebuffer");
            swapchainFramebuffers.add(pFramebuffer.get(0));
        }
    }

    private void createCommandBuffers(MemoryStack stack) {
        commandBuffers = new ArrayList<>(swapchainFramebuffers.size());
        VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(vkDevice.getCommandPool()) // Use pool from VkDevice
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(swapchainFramebuffers.size()); // Allocate one per framebuffer

        PointerBuffer pCommandBuffers = stack.mallocPointer(swapchainFramebuffers.size());
        VkMemoryUtil.vkCheck(vkAllocateCommandBuffers(rawDevice, allocInfo, pCommandBuffers),
                "Failed to allocate command buffers");

        for (int i = 0; i < swapchainFramebuffers.size(); i++) {
            commandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), rawDevice));
        }
    }

    private void createSyncObjects(MemoryStack stack) {
        VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK_FENCE_CREATE_SIGNALED_BIT); // Create fence initially signaled

        LongBuffer pSemaphore = stack.mallocLong(1);
        LongBuffer pFence = stack.mallocLong(1);

        VkMemoryUtil.vkCheck(vkCreateSemaphore(rawDevice, semaphoreInfo, null, pSemaphore),
                "Failed to create image available semaphore");
        imageAvailableSemaphore = pSemaphore.get(0);

        VkMemoryUtil.vkCheck(vkCreateSemaphore(rawDevice, semaphoreInfo, null, pSemaphore),
                "Failed to create render finished semaphore");
        renderFinishedSemaphore = pSemaphore.get(0);

        VkMemoryUtil.vkCheck(vkCreateFence(rawDevice, fenceInfo, null, pFence),
                "Failed to create in-flight fence");
        inFlightFence = pFence.get(0);
    }

    // --- Need cleanupVulkan() method for dispose() and error handling ---
    private void cleanupVulkan() {
        // Wait for device to be idle before destroying anything
        if (rawDevice != null) {
            vkDeviceWaitIdle(rawDevice); // Important!
        }

        VkMemoryUtil.safeDestroySemaphore(imageAvailableSemaphore, rawDevice);
        VkMemoryUtil.safeDestroySemaphore(renderFinishedSemaphore, rawDevice);
        VkMemoryUtil.safeDestroyFence(inFlightFence, rawDevice);

        // Command buffers are implicitly freed when the pool is destroyed
        // VkMemoryUtil.safeDestroyCommandPool(commandPool, rawDevice); // Don't destroy here, owned by VkDevice

        if (swapchainFramebuffers != null) {
            for (long framebuffer : swapchainFramebuffers) {
                VkMemoryUtil.safeDestroyFramebuffer(framebuffer, rawDevice);
            }
        }
        VkMemoryUtil.safeDestroyRenderPass(renderPass, rawDevice);
        if (swapchainImageViews != null) {
            for (long imageView : swapchainImageViews) {
                VkMemoryUtil.safeDestroyImageView(imageView, rawDevice);
            }
        }
        VkMemoryUtil.safeDestroySwapchain(swapchain, rawDevice);

        // Reset handles
        swapchain = VK_NULL_HANDLE;
        renderPass = VK_NULL_HANDLE;
        imageAvailableSemaphore = VK_NULL_HANDLE;
        renderFinishedSemaphore = VK_NULL_HANDLE;
        inFlightFence = VK_NULL_HANDLE;
        // etc. Clear lists maybe
    }

// --- Add to dispose() method in VulkanGraphics ---
// @Override
// public void dispose() {
//    cleanupVulkan();
//    this.resizeCallback.free();
// }

    void updateFramebufferInfo() {
        GLFW.glfwGetFramebufferSize(window.getWindowHandle(), tmpBuffer, tmpBuffer2);
        this.backBufferWidth = tmpBuffer.get(0);
        this.backBufferHeight = tmpBuffer2.get(0);
        GLFW.glfwGetWindowSize(window.getWindowHandle(), tmpBuffer, tmpBuffer2);
        VulkanGraphics.this.logicalWidth = tmpBuffer.get(0);
        VulkanGraphics.this.logicalHeight = tmpBuffer2.get(0);
        VulkanApplicationConfiguration config = window.getConfig();
        bufferFormat = new BufferFormat(config.r, config.g, config.b, config.a, config.depth, config.stencil, config.samples, false);
    }

    void update() {
        long time = System.nanoTime();
        if (lastFrameTime == -1) lastFrameTime = time;
        if (resetDeltaTime) {
            resetDeltaTime = false;
            deltaTime = 0;
        } else
            deltaTime = (time - lastFrameTime) / 1000000000.0f;
        lastFrameTime = time;

        if (time - frameCounterStart >= 1000000000) {
            fps = frames;
            frames = 0;
            frameCounterStart = time;
        }
        frames++;
        frameId++;
    }

    @Override
    public boolean isGL30Available() {
        return false;//gl30 != null;
    }

    @Override
    public boolean isGL31Available() {
        return false;//gl31 != null;
    }

    @Override
    public boolean isGL32Available() {
        return false;//gl32 != null;
    }

    @Override
    public GL20 getGL20() {
        return null;//gl20;
    }

    @Override
    public GL30 getGL30() {
        return null;//gl30;
    }

    @Override
    public GL31 getGL31() {
        return null;//gl31;
    }

    @Override
    public GL32 getGL32() {
        return null;//gl32;
    }


    @Override
    public void setGL20(GL20 gl20) {
        //this.gl20 = null;//gl20;
    }

    @Override
    public void setGL30(GL30 gl30) {
        //this.gl30 = null;//gl30;
    }

    @Override
    public void setGL31(GL31 gl31) {
        //this.gl31 = null;//gl31;
    }

    @Override
    public void setGL32(GL32 gl32) {
        //this.gl32 = null;//gl32;
    }

    @Override
    public int getWidth() {
        if (window.getConfig().hdpiMode == HdpiMode.Pixels) {
            return backBufferWidth;
        } else {
            return logicalWidth;
        }
    }

    @Override
    public int getHeight() {
        if (window.getConfig().hdpiMode == HdpiMode.Pixels) {
            return backBufferHeight;
        } else {
            return logicalHeight;
        }
    }

    @Override
    public int getBackBufferWidth() {
        return backBufferWidth;
    }

    @Override
    public int getBackBufferHeight() {
        return backBufferHeight;
    }

    public int getLogicalWidth() {
        return logicalWidth;
    }

    public int getLogicalHeight() {
        return logicalHeight;
    }

    @Override
    public long getFrameId() {
        return frameId;
    }

    @Override
    public float getDeltaTime() {
        return deltaTime;
    }

    public void resetDeltaTime() {
        resetDeltaTime = true;
    }

    @Override
    public int getFramesPerSecond() {
        return fps;
    }

    @Override
    public GraphicsType getType() {
        return GraphicsType.LWJGL3;
    }

    @Override
    public GLVersion getGLVersion() {
        return glVersion;
    }

    @Override
    public float getPpiX() {
        return getPpcX() * 2.54f;
    }

    @Override
    public float getPpiY() {
        return getPpcY() * 2.54f;
    }

    @Override
    public float getPpcX() {
        VulkanMonitor monitor = (VulkanMonitor) getMonitor();
        GLFW.glfwGetMonitorPhysicalSize(monitor.monitorHandle, tmpBuffer, tmpBuffer2);
        int sizeX = tmpBuffer.get(0);
        DisplayMode mode = getDisplayMode();
        return mode.width / (float) sizeX * 10;
    }

    @Override
    public float getPpcY() {
        VulkanMonitor monitor = (VulkanMonitor) getMonitor();
        GLFW.glfwGetMonitorPhysicalSize(monitor.monitorHandle, tmpBuffer, tmpBuffer2);
        int sizeY = tmpBuffer2.get(0);
        DisplayMode mode = getDisplayMode();
        return mode.height / (float) sizeY * 10;
    }

    @Override
    public boolean supportsDisplayModeChange() {
        return true;
    }

    @Override
    public Monitor getPrimaryMonitor() {
        return VulkanApplicationConfiguration.toVulkanMonitor(GLFW.glfwGetPrimaryMonitor());
    }

    @Override
    public Monitor getMonitor() {
        Monitor[] monitors = getMonitors();
        Monitor result = monitors[0];

        GLFW.glfwGetWindowPos(window.getWindowHandle(), tmpBuffer, tmpBuffer2);
        int windowX = tmpBuffer.get(0);
        int windowY = tmpBuffer2.get(0);
        GLFW.glfwGetWindowSize(window.getWindowHandle(), tmpBuffer, tmpBuffer2);
        int windowWidth = tmpBuffer.get(0);
        int windowHeight = tmpBuffer2.get(0);
        int overlap;
        int bestOverlap = 0;

        for (Monitor monitor : monitors) {
            DisplayMode mode = getDisplayMode(monitor);

            overlap = Math.max(0,
                    Math.min(windowX + windowWidth, monitor.virtualX + mode.width) - Math.max(windowX, monitor.virtualX))
                    * Math.max(0, Math.min(windowY + windowHeight, monitor.virtualY + mode.height) - Math.max(windowY, monitor.virtualY));

            if (bestOverlap < overlap) {
                bestOverlap = overlap;
                result = monitor;
            }
        }
        return result;
    }

    @Override
    public Monitor[] getMonitors() {
        PointerBuffer glfwMonitors = GLFW.glfwGetMonitors();
        Monitor[] monitors = new Monitor[glfwMonitors.limit()];
        for (int i = 0; i < glfwMonitors.limit(); i++) {
            monitors[i] = VulkanApplicationConfiguration.toVulkanMonitor(glfwMonitors.get(i));
        }
        return monitors;
    }

    @Override
    public DisplayMode[] getDisplayModes() {
        return VulkanApplicationConfiguration.getDisplayModes(getMonitor());
    }

    @Override
    public DisplayMode[] getDisplayModes(Monitor monitor) {
        return VulkanApplicationConfiguration.getDisplayModes(monitor);
    }

    @Override
    public DisplayMode getDisplayMode() {
        return VulkanApplicationConfiguration.getDisplayMode(getMonitor());
    }

    @Override
    public DisplayMode getDisplayMode(Monitor monitor) {
        return VulkanApplicationConfiguration.getDisplayMode(monitor);
    }

    @Override
    public int getSafeInsetLeft() {
        return 0;
    }

    @Override
    public int getSafeInsetTop() {
        return 0;
    }

    @Override
    public int getSafeInsetBottom() {
        return 0;
    }

    @Override
    public int getSafeInsetRight() {
        return 0;
    }

    @Override
    public boolean setFullscreenMode(DisplayMode displayMode) {
        window.getInput().resetPollingStates();
        VulkanDisplayMode newMode = (VulkanDisplayMode) displayMode;
        if (isFullscreen()) {
            VulkanDisplayMode currentMode = (VulkanDisplayMode) getDisplayMode();
            if (currentMode.getMonitor() == newMode.getMonitor() && currentMode.refreshRate == newMode.refreshRate) {
                // same monitor and refresh rate
                GLFW.glfwSetWindowSize(window.getWindowHandle(), newMode.width, newMode.height);
            } else {
                // different monitor and/or refresh rate
                GLFW.glfwSetWindowMonitor(window.getWindowHandle(), newMode.getMonitor(), 0, 0, newMode.width, newMode.height,
                        newMode.refreshRate);
            }
        } else {
            // store window position so we can restore it when switching from fullscreen to windowed later
            storeCurrentWindowPositionAndDisplayMode();

            // switch from windowed to fullscreen
            GLFW.glfwSetWindowMonitor(window.getWindowHandle(), newMode.getMonitor(), 0, 0, newMode.width, newMode.height,
                    newMode.refreshRate);
        }
        updateFramebufferInfo();

        setVSync(window.getConfig().vSyncEnabled);

        return true;
    }

    private void storeCurrentWindowPositionAndDisplayMode() {
        windowPosXBeforeFullscreen = window.getPositionX();
        windowPosYBeforeFullscreen = window.getPositionY();
        windowWidthBeforeFullscreen = logicalWidth;
        windowHeightBeforeFullscreen = logicalHeight;
        displayModeBeforeFullscreen = getDisplayMode();
    }

    @Override
    public boolean setWindowedMode(int width, int height) {
        window.getInput().resetPollingStates();
        if (!isFullscreen()) {
            GridPoint2 newPos = null;
            boolean centerWindow = false;
            if (width != logicalWidth || height != logicalHeight) {
                centerWindow = true; // recenter the window since its size changed
                newPos = VulkanApplicationConfiguration.calculateCenteredWindowPosition((VulkanMonitor) getMonitor(), width, height);
            }
            GLFW.glfwSetWindowSize(window.getWindowHandle(), width, height);
            if (centerWindow) {
                window.setPosition(newPos.x, newPos.y); // on macOS the centering has to happen _after_ the new window size was set
            }
        } else { // if we were in fullscreen mode, we should consider restoring a previous display mode
            if (displayModeBeforeFullscreen == null) {
                storeCurrentWindowPositionAndDisplayMode();
            }
            if (width != windowWidthBeforeFullscreen || height != windowHeightBeforeFullscreen) { // center the window since its size
                // changed
                GridPoint2 newPos = VulkanApplicationConfiguration.calculateCenteredWindowPosition((VulkanMonitor) getMonitor(), width,
                        height);
                GLFW.glfwSetWindowMonitor(window.getWindowHandle(), 0, newPos.x, newPos.y, width, height,
                        displayModeBeforeFullscreen.refreshRate);
            } else { // restore previous position
                GLFW.glfwSetWindowMonitor(window.getWindowHandle(), 0, windowPosXBeforeFullscreen, windowPosYBeforeFullscreen, width,
                        height, displayModeBeforeFullscreen.refreshRate);
            }
        }
        updateFramebufferInfo();
        return true;
    }

    @Override
    public void setTitle(String title) {
        if (title == null) {
            title = "";
        }
        GLFW.glfwSetWindowTitle(window.getWindowHandle(), title);
    }

    @Override
    public void setUndecorated(boolean undecorated) {
        getWindow().getConfig().setDecorated(!undecorated);
        GLFW.glfwSetWindowAttrib(window.getWindowHandle(), GLFW.GLFW_DECORATED, undecorated ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
    }

    @Override
    public void setResizable(boolean resizable) {
        getWindow().getConfig().setResizable(resizable);
        GLFW.glfwSetWindowAttrib(window.getWindowHandle(), GLFW.GLFW_RESIZABLE, resizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
    }

    @Override
    public void setVSync(boolean vsync) {
        if (this.vsyncEnabled != vsync) {
            this.vsyncEnabled = vsync;
            // Trigger swap chain recreation to apply the new present mode
            this.framebufferResized = true; // Reuse the resize flag
            System.out.println("VSync changed to: " + vsync + ", requesting swapchain recreation.");
            // Don't call glfwSwapInterval - it's for OpenGL
            // GLFW.glfwSwapInterval(vsync ? 1 : 0); // REMOVE THIS
        }
    }

    /** Sets the target framerate for the application, when using continuous rendering. Must be positive. The cpu sleeps as needed.
     * Use 0 to never sleep. If there are multiple windows, the value for the first window created is used for all. Default is 0.
     *
     * @param fps fps */
    @Override
    public void setForegroundFPS(int fps) {
        getWindow().getConfig().foregroundFPS = fps;
    }

    @Override
    public BufferFormat getBufferFormat() {
        return bufferFormat;
    }

    @Override
    public boolean supportsExtension(String extension) {
        return GLFW.glfwExtensionSupported(extension);
    }

    @Override
    public void setContinuousRendering(boolean isContinuous) {
        this.isContinuous = isContinuous;
    }

    @Override
    public boolean isContinuousRendering() {
        return isContinuous;
    }

    @Override
    public void requestRendering() {
        window.requestRendering();
    }

    @Override
    public boolean isFullscreen() {
        return GLFW.glfwGetWindowMonitor(window.getWindowHandle()) != 0;
    }

    @Override
    public Cursor newCursor(Pixmap pixmap, int xHotspot, int yHotspot) {
        return new VulkanCursor(getWindow(), pixmap, xHotspot, yHotspot);
    }

    @Override
    public void setCursor(Cursor cursor) {
        GLFW.glfwSetCursor(getWindow().getWindowHandle(), ((VulkanCursor) cursor).glfwCursor);
    }

    @Override
    public void setSystemCursor(SystemCursor systemCursor) {
        VulkanCursor.setSystemCursor(getWindow().getWindowHandle(), systemCursor);
    }

    @Override
    public void dispose() {
            cleanupVulkan(); // Call Vulkan resource cleanup
            this.resizeCallback.free();
            System.out.println("VulkanGraphics disposed.");
    }

    // Method to clean up ONLY swapchain-related resources
    private void cleanupSwapChainRelatedResources() {
        // Wait for device idle ONLY IF resources actually exist
        if (rawDevice != null && swapchain != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(rawDevice);
        } else if (rawDevice == null) {
            return; // Cannot cleanup if device doesn't exist
        }


        // Don't destroy sync objects here - they are usually reusable
        // VkMemoryUtil.safeDestroySemaphore(imageAvailableSemaphore, rawDevice);
        // VkMemoryUtil.safeDestroySemaphore(renderFinishedSemaphore, rawDevice);
        // VkMemoryUtil.safeDestroyFence(inFlightFence, rawDevice);

        // Command buffers might need to be freed/reset depending on dependencies
        // If just resizing, often reusable. If render pass changes, need reallocation.
        // For simplicity now, we assume they might be invalid if framebuffers change.
        // NOTE: We allocated them per framebuffer, so they MUST be dealt with.
        // We can't easily free individual buffers allocated from a pool without resetting the pool,
        // or using VK_COMMAND_POOL_CREATE_FREE_COMMAND_BUFFER_BIT.
        // Simplest safe approach for now: rely on pool reset/destroy or recreate pool.
        // Since pool is in VkDevice, let's assume command buffers need re-recording but not freeing here.
        // If recreation logic re-allocates command buffers, the old ones become dangling references if not freed.
        // --> Safer to re-allocate command buffers in recreateSwapChain AFTER cleaning framebuffers.

        if (swapchainFramebuffers != null) {
            for (long framebuffer : swapchainFramebuffers) {
                VkMemoryUtil.safeDestroyFramebuffer(framebuffer, rawDevice);
            }
            swapchainFramebuffers.clear();
        }
        // RenderPass *might* be reusable if only extent changes, but safer to recreate if format could change.
        VkMemoryUtil.safeDestroyRenderPass(renderPass, rawDevice);
        renderPass = VK_NULL_HANDLE; // Mark as destroyed

        if (swapchainImageViews != null) {
            for (long imageView : swapchainImageViews) {
                VkMemoryUtil.safeDestroyImageView(imageView, rawDevice);
            }
            swapchainImageViews.clear();
        }
        VkMemoryUtil.safeDestroySwapchain(swapchain, rawDevice);
        swapchain = VK_NULL_HANDLE; // Mark as destroyed

        // Command buffers were tied to framebuffers, clear the list
        if(commandBuffers != null) {
            commandBuffers.clear();
        }

        System.out.println("Cleaned up swapchain-related resources.");
    }

    public static class VulkanDisplayMode extends DisplayMode {
        final long monitorHandle;

        VulkanDisplayMode(long monitor, int width, int height, int refreshRate, int bitsPerPixel) {
            super(width, height, refreshRate, bitsPerPixel);
            this.monitorHandle = monitor;
        }

        public long getMonitor() {
            return monitorHandle;
        }
    }

    public static class VulkanMonitor extends Monitor {
        final long monitorHandle;

        VulkanMonitor(long monitor, int virtualX, int virtualY, String name) {
            super(virtualX, virtualY, name);
            this.monitorHandle = monitor;
        }

        public long getMonitorHandle() {
            return monitorHandle;
        }
    }
}
