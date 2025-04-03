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

package com.badlogic.gdx.backend.vulkan;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
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
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_IDENTITY;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT;
import static org.lwjgl.vulkan.VK10.VK_FENCE_CREATE_SIGNALED_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_CONTENTS_INLINE;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_EXTERNAL;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;
import static org.lwjgl.vulkan.VK10.vkCmdBeginRenderPass;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;
import static org.lwjgl.vulkan.VK10.vkCmdEndRenderPass;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateFramebuffer;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateRenderPass;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkFreeCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkMapMemory;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkQueueWaitIdle;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkResetFences;
import static org.lwjgl.vulkan.VK10.vkUnmapMemory;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.AbstractGraphics;
import com.badlogic.gdx.Application;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
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
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;

public class VulkanGraphics extends AbstractGraphics implements Disposable {
    private final VulkanApplicationConfiguration config;
    private final long windowHandle;
    volatile boolean framebufferResized = false; // Flag for resize/vsync change
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

    VulkanWindow window;
    IntBuffer tmpBuffer = BufferUtils.createIntBuffer(1);
    IntBuffer tmpBuffer2 = BufferUtils.createIntBuffer(1);

    private final float[] quadVertices = {
            // Position      // Color
            -0.5f, -0.5f,    1.0f, 0.0f, 0.0f, // 0: Bottom Left (Red)
            0.5f, -0.5f,    0.0f, 1.0f, 0.0f, // 1: Bottom Right (Green)
            0.5f,  0.5f,    0.0f, 0.0f, 1.0f, // 2: Top Right (Blue)
            -0.5f,  0.5f,    1.0f, 1.0f, 1.0f  // 3: Top Left (White)
    };
    private final short[] quadIndices = { 0, 1, 2, 2, 3, 0 };

    // Handles for the final GPU buffers & memory
    private long vertexBuffer = VK_NULL_HANDLE;
    private long vertexBufferMemory = VK_NULL_HANDLE;
    private long indexBuffer = VK_NULL_HANDLE;
    private long indexBufferMemory = VK_NULL_HANDLE;

    // Store counts for drawing
    private final int vertexCount = 4; // Explicitly 4 vertices
    private final int indexCount = quadIndices.length; // 6 indices

    private long vertShaderModule = VK_NULL_HANDLE;
    private long fragShaderModule = VK_NULL_HANDLE;

    private long pipelineLayout = VK_NULL_HANDLE;
    private long graphicsPipeline = VK_NULL_HANDLE;

    GLFWFramebufferSizeCallback resizeCallback = new GLFWFramebufferSizeCallback() {
        @Override
        public void invoke(long windowHandle, final int width, final int height) {
            if (Gdx.graphics instanceof VulkanGraphics) {
                ((VulkanGraphics) Gdx.graphics).framebufferResized(width, height);
            }
            System.out.println("Framebuffer resize requested: " + width + "x" + height);
        }
    };

    public VulkanGraphics(long windowHandle, VulkanApplicationConfiguration config) {
        this.windowHandle = windowHandle;
        this.config = config;
        updateFramebufferInfo(); // Get initial size info

        GLFW.glfwSetFramebufferSizeCallback(this.windowHandle, resizeCallback);
    }

    public VulkanWindow getWindow() {
        return window;
    }

    // Contains the logic previously in initiateVulkan()
    public void initializeSwapchainAndResources() {
        // Now it's safe to get these, assuming VulkanApplication initialized them
        this.app = (VulkanApplication) Gdx.app; // Or get app reference passed differently
        this.vkInstance = app.getVulkanInstance();
        this.vkDevice = app.getVkDevice();
        this.surface = app.getSurface(this.windowHandle); // Surface is per-window! Need a way to get the right one.
        this.physicalDevice = vkDevice.getPhysicalDevice();
        this.rawDevice = vkDevice.getRawDevice();
        this.graphicsQueue = vkDevice.getGraphicsQueue();
        this.presentQueue = graphicsQueue; // Assuming same queue

        if (this.vkDevice == null || this.surface == VK_NULL_HANDLE) {
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
            createShaderModules();
            System.out.println("Shader modules created.");
            createGraphicsPipeline();
            System.out.println("Graphics pipeline created.");
            createFramebuffers(stack);
            System.out.println("Framebuffers created.");
            createCommandBuffers(stack);
            System.out.println("CommandBuffers created.");
            createSyncObjects(stack);
            System.out.println("Sync objects created.");

            createGeometryBuffers();
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
        vkCheck(KHRSwapchain.vkCreateSwapchainKHR(rawDevice, createInfo, null, pSwapchain),
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

    /**
     * Called by the GLFW framebuffer resize callback. Should just set a flag
     * to be handled by the render thread, as GLFW callbacks can occur on
     * separate threads.
     *
     * @param width  New framebuffer width
     * @param height New framebuffer height
     */
    private void framebufferResized(int width, int height) {
        if (width > 0 && height > 0) {
            this.framebufferResized = true;
            // Using Gdx.app here might be risky if called very early or from wrong thread
            // System.out.println("Framebuffer resize flagged: " + width + "x" + height);
        }
    }

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
            vkCheck(vkCreateImageView(rawDevice, createInfo, null, pImageView),
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
        vkCheck(vkCreateRenderPass(rawDevice, renderPassInfo, null, pRenderPass),
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
            vkCheck(vkCreateFramebuffer(rawDevice, framebufferInfo, null, pFramebuffer),
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
        vkCheck(vkAllocateCommandBuffers(rawDevice, allocInfo, pCommandBuffers),
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

        vkCheck(vkCreateSemaphore(rawDevice, semaphoreInfo, null, pSemaphore),
                "Failed to create image available semaphore");
        imageAvailableSemaphore = pSemaphore.get(0);

        vkCheck(vkCreateSemaphore(rawDevice, semaphoreInfo, null, pSemaphore),
                "Failed to create render finished semaphore");
        renderFinishedSemaphore = pSemaphore.get(0);

        vkCheck(vkCreateFence(rawDevice, fenceInfo, null, pFence),
                "Failed to create in-flight fence");
        inFlightFence = pFence.get(0);
    }

    // --- Need cleanupVulkan() method for dispose() and error handling ---
    private void cleanupVulkan() {
        if (rawDevice != null) {
            vkDeviceWaitIdle(rawDevice);
        } else {
            return; // Nothing to clean if device is gone
        }

        if (graphicsPipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(rawDevice, graphicsPipeline, null);
            graphicsPipeline = VK_NULL_HANDLE;
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(rawDevice, pipelineLayout, null);
            pipelineLayout = VK_NULL_HANDLE;
        }

        if (vertShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(rawDevice, vertShaderModule, null);
            System.out.println("Vertex shader module destroyed."); // Optional log
            vertShaderModule = VK_NULL_HANDLE; // Nullify handle after destruction
        }
        if (fragShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(rawDevice, fragShaderModule, null);
            System.out.println("Fragment shader module destroyed."); // Optional log
            fragShaderModule = VK_NULL_HANDLE; // Nullify handle after destruction
        }

        cleanupSwapChainRelatedResources(); // Cleanup swapchain stuff first
        cleanupGeometryBuffers();

        // Destroy Sync Objects
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

        System.out.println("Vulkan sync objects destroyed.");
    }

// --- Add to dispose() method in VulkanGraphics ---
// @Override
// public void dispose() {
//    cleanupVulkan();
//    this.resizeCallback.free();
// }

    void updateFramebufferInfo() {
        /*GLFW.glfwGetFramebufferSize(window.getWindowHandle(), tmpBuffer, tmpBuffer2);
        this.backBufferWidth = tmpBuffer.get(0);
        this.backBufferHeight = tmpBuffer2.get(0);
        GLFW.glfwGetWindowSize(window.getWindowHandle(), tmpBuffer, tmpBuffer2);
        VulkanGraphics.this.logicalWidth = tmpBuffer.get(0);
        VulkanGraphics.this.logicalHeight = tmpBuffer2.get(0);
        VulkanApplicationConfiguration config = window.getConfig();
        bufferFormat = new BufferFormat(config.r, config.g, config.b, config.a, config.depth, config.stencil, config.samples, false);*/
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);

            // Use the stored windowHandle field
            GLFW.glfwGetFramebufferSize(this.windowHandle, pWidth, pHeight);
            this.backBufferWidth = pWidth.get(0);
            this.backBufferHeight = pHeight.get(0);

            // Also get logical size using the handle
            GLFW.glfwGetWindowSize(this.windowHandle, pWidth, pHeight);
            this.logicalWidth = pWidth.get(0);
            this.logicalHeight = pHeight.get(0);

            // Use System.out here as Gdx.app might not be fully ready
            System.out.println("VulkanGraphics: Initial FB Size: " + backBufferWidth + "x" + backBufferHeight +
                    ", Logical Size: " + logicalWidth + "x" + logicalHeight);
        } // Buffers are freed automatically

        // Ensure 'config' field exists and is set if you need it for BufferFormat
        if (this.config != null) {
            bufferFormat = new BufferFormat(config.r, config.g, config.b, config.a, config.depth, config.stencil, config.samples, false);
        } else {
            System.err.println("VulkanGraphics Warning: config field is null during updateFramebufferInfo!");
            // Handle default buffer format?
        }
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

    /**
     * Draws a single frame. Called repeatedly by the application loop.
     * Handles swapchain recreation, synchronization, command buffer recording & submission, and presentation.
     *
     * @return true if rendering occurred, false if skipped (e.g., due to resize).
     */
    public boolean drawFrame() {
        try (MemoryStack stack = stackPush()) {

            // --- 1. Wait for the previous frame to finish ---
            // Wait for the fence associated with this frame resource slot to be signaled
            // Using a single fence for simplicity now (limits to 1 frame in flight effectively)
            vkWaitForFences(rawDevice, inFlightFence, true, Long.MAX_VALUE); // Wait indefinitely

            // --- 2. Handle Swapchain Recreation (if needed) ---
            // Check AFTER waiting for fence to ensure resources aren't in use
            if (framebufferResized) {
                framebufferResized = false; // Reset flag
                recreateSwapChain();
                return false; // Skip rendering this frame, resources changed
            }

            // --- 3. Acquire an image from the swap chain ---
            IntBuffer pImageIndex = stack.mallocInt(1);
            int acquireResult = KHRSwapchain.vkAcquireNextImageKHR(
                    rawDevice,
                    swapchain,
                    Long.MAX_VALUE, // Timeout (use UINT64_MAX for no timeout)
                    imageAvailableSemaphore, // Semaphore to signal when image is ready
                    VK_NULL_HANDLE, // Fence to signal (optional, using semaphore is common)
                    pImageIndex
            );
            int imageIndex = pImageIndex.get(0); // Index of the acquired swapchain image/framebuffer

            // Handle potential swapchain issues detected during acquire
            if (acquireResult == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapChain();
                return false; // Skip rendering
            } else if (acquireResult == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                // Swapchain can still be used, but flag for recreate next frame
                framebufferResized = true;
            } else if (acquireResult != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to acquire swap chain image! Vulkan Error Code: " + acquireResult);
            }

            // --- Important: Only reset fence AFTER confirming image acquire succeeded ---
            // Ensures we don't reset fence if vkAcquireNextImageKHR indicates recreation needed
            vkResetFences(rawDevice, inFlightFence);

            // --- 4. Record Command Buffer ---
            VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex); // Get CB for this image index

            // Reset command buffer before recording new commands
            // Flags = 0 means don't release resources
            vkResetCommandBuffer(commandBuffer, 0);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            // Optional flags: .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkCheck(vkBeginCommandBuffer(commandBuffer, beginInfo),                    "Failed to begin recording command buffer!");

            // --- Begin Render Pass ---
            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(swapchainFramebuffers.get(imageIndex)); // Use framebuffer matching acquired image

            renderPassInfo.renderArea().offset().set(0, 0);
            renderPassInfo.renderArea().extent().set(swapchainExtent);

            // Define clear color value (e.g., dark blue)
            VkClearValue.Buffer clearValues = VkClearValue.calloc(1, stack); // One clear value for the color attachment
            clearValues.get(0).color().float32(stack.floats(0.0f, 0.0f, 0.5f, 1.0f)); // R,G,B,A

            renderPassInfo.pClearValues(clearValues);

            // Start the render pass instance
            vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0.0f)
                    .y(0.0f) // Y can sometimes be height for inverted viewport, but 0 standard
                    .width((float) swapchainExtent.width())
                    .height((float) swapchainExtent.height())
                    .minDepth(0.0f)
                    .maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, 0, viewport); // Viewport index 0

            // 3. Set Dynamic Scissor State
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(swapchainExtent); // Use full swapchain extent
            vkCmdSetScissor(commandBuffer, 0, scissor); // Scissor index 0

            // 4. Bind the Vertex Buffer
            LongBuffer pVertexBuffers = stack.longs(vertexBuffer); // Buffer containing the vertex buffer handle
            LongBuffer pOffsets = stack.longs(0); // Starting offset within the buffer
            // Bind to binding point 0 (matches VkVertexInputBindingDescription)
            vkCmdBindVertexBuffers(commandBuffer, 0, pVertexBuffers, pOffsets);

            // 5. Bind the Index Buffer
            // Use VK_INDEX_TYPE_UINT16 because quadIndices was short[]
            vkCmdBindIndexBuffer(commandBuffer, indexBuffer, 0, VK_INDEX_TYPE_UINT16);

            // 6. Issue the Draw Call!
            vkCmdDrawIndexed(
                    commandBuffer,
                    indexCount,    // How many indices to draw (6 for our quad)
                    1,             // Instance count (just 1 instance)
                    0,             // First index offset
                    0,             // Vertex offset (added to index value before lookup)
                    0              // First instance index
            );

            // End the render pass instance
            vkCmdEndRenderPass(commandBuffer);

            // Finish recording
            vkCheck(vkEndCommandBuffer(commandBuffer),
                    "Failed to record command buffer!");

            // --- 5. Submit Command Buffer ---
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            // Specify which semaphores to wait for before execution begins
            LongBuffer waitSemaphores = stack.longs(imageAvailableSemaphore);
            // Specify which pipeline stages to wait in
            IntBuffer waitStages = stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            submitInfo.waitSemaphoreCount(1);
            submitInfo.pWaitSemaphores(waitSemaphores);
            submitInfo.pWaitDstStageMask(waitStages);

            // Specify command buffer to submit
            submitInfo.pCommandBuffers(stack.pointers(commandBuffer));

            // Specify which semaphores to signal once command buffer finishes
            LongBuffer signalSemaphores = stack.longs(renderFinishedSemaphore);
            //submitInfo.signalSemaphoreCount(1);
            submitInfo.pSignalSemaphores(signalSemaphores);

            // Submit to the graphics queue, signaling the fence when done
            vkCheck(vkQueueSubmit(graphicsQueue, submitInfo, inFlightFence), // Use the fence
                    "Failed to submit draw command buffer!");


            // --- 6. Presentation ---
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);

            // Wait on the semaphore signaling that rendering is finished
            presentInfo.pWaitSemaphores(signalSemaphores);

            LongBuffer pSwapchains = stack.longs(swapchain);
            IntBuffer pImageIndices = stack.ints(imageIndex);
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(pSwapchains);
            presentInfo.pImageIndices(pImageIndices);

            // Submit request to present image to the screen
            int presentResult = KHRSwapchain.vkQueuePresentKHR(presentQueue, presentInfo);

            // Handle swapchain issues detected during present
            if (presentResult == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || presentResult == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                framebufferResized = true; // Mark for recreation next frame
            } else if (presentResult != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to present swap chain image! Vulkan Error Code: " + presentResult);
            }

            // Advance frame index if using multiple frames in flight
            // currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;

            update(); // Update frame ID, delta time etc. (from AbstractGraphics)
            return true; // Indicate rendering happened

        } catch (Exception e) {
            // Log or rethrow appropriately
            Gdx.app.error("VulkanGraphics", "Exception during drawFrame", e);
            // Depending on error, maybe try to recover or exit?
            // For now, rethrow as runtime exception
            throw new GdxRuntimeException("Exception during drawFrame", e);
        }
    }


    /**
     * Handles recreating the swapchain and dependent resources after resize or vsync change.
     */
    private void recreateSwapChain() {
        try (MemoryStack stack = stackPush()) {
            // Wait until current resources are no longer in use
            Gdx.app.log("VulkanGraphics", "Recreating SwapChain...");
            vkDeviceWaitIdle(rawDevice);

            // Clean up OLD swapchain resources
            cleanupSwapChainRelatedResources(); // Destroys swapchain, views, framebuffers, render pass

            // Re-create swapchain resources with current window size
            createSwapchain(stack);
            createImageViews(stack);
            createRenderPass(stack); // Recreate render pass, format might theoretically change
            createFramebuffers(stack);
            createCommandBuffers(stack); // Re-allocate command buffers tied to framebuffers

            Gdx.app.log("VulkanGraphics", "SwapChain recreation complete.");

            // Update internal size tracking AFTER recreation
            updateFramebufferInfo();

            // Notify listener AFTER new resources are ready
            if (Gdx.app != null && Gdx.app.getApplicationListener() != null) {
                Gdx.app.getApplicationListener().resize(getWidth(), getHeight());
            }

        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to recreate swapchain", e);
        }
    }

    // Ensure cleanupSwapChainRelatedResources is implemented correctly
    private void cleanupSwapChainRelatedResources() {
        // DO NOT wait for device idle here, recreateSwapChain should do it BEFORE calling this

        // Framebuffers
        if (swapchainFramebuffers != null) {
            for (long framebuffer : swapchainFramebuffers) {
                VkMemoryUtil.safeDestroyFramebuffer(framebuffer, rawDevice);
            }
            swapchainFramebuffers.clear(); // Clear list
            swapchainFramebuffers = null; // Allow GC
        }
        // Command Buffers were implicitly tied to framebuffers, clear list
        // Actual freeing depends on pool flags, safer to just clear reference here
        if (commandBuffers != null) {
            commandBuffers.clear();
            commandBuffers = null;
        }
        // Render Pass
        VkMemoryUtil.safeDestroyRenderPass(renderPass, rawDevice);
        renderPass = VK_NULL_HANDLE;
        // Image Views
        if (swapchainImageViews != null) {
            for (long imageView : swapchainImageViews) {
                VkMemoryUtil.safeDestroyImageView(imageView, rawDevice);
            }
            swapchainImageViews.clear();
            swapchainImageViews = null;
        }
        // Swapchain itself
        VkMemoryUtil.safeDestroySwapchain(swapchain, rawDevice);
        swapchain = VK_NULL_HANDLE;
        // Swapchain Images list (handles retrieved, not owned)
        if (swapchainImages != null) {
            swapchainImages.clear();
            swapchainImages = null;
        }
        System.out.println("Cleaned up swapchain-related resources.");
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
        if (this.config != null && this.config.hdpiMode == HdpiMode.Pixels) {
            return backBufferWidth; // Use the stored back buffer width
        } else {
            // If config is null or mode isn't Pixels, return logical width
            return logicalWidth; // Use the stored logical width
        }
    }

    @Override
    public int getHeight() {
        if (this.config != null && this.config.hdpiMode == HdpiMode.Pixels) {
            return backBufferHeight; // Use the stored back buffer height
        } else {
            // If config is null or mode isn't Pixels, return logical height
            return logicalHeight; // Use the stored logical height
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

    /**
     * Sets the target framerate for the application, when using continuous rendering. Must be positive. The cpu sleeps as needed.
     * Use 0 to never sleep. If there are multiple windows, the value for the first window created is used for all. Default is 0.
     *
     * @param fps fps
     */
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
        cleanupVulkan(); // Call full Vulkan cleanup
        if (this.resizeCallback != null) {
            this.resizeCallback.free(); // Free GLFW callback
        }
        System.out.println("VulkanGraphics disposed.");
    }

    // Method to clean up ONLY swapchain-related resources
    /*private void cleanupSwapChainRelatedResources() {
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
        if (commandBuffers != null) {
            commandBuffers.clear();
        }

        System.out.println("Cleaned up swapchain-related resources.");
    }*/

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

    /**
     * Finds a suitable memory type index on the physical device.
     *
     * @param typeFilter         A bitmask where each set bit indicates an acceptable memory type index. (From vkGetBufferMemoryRequirements)
     * @param requiredProperties A bitmask of required VkMemoryPropertyFlagBits (e.g., HOST_VISIBLE, DEVICE_LOCAL).
     * @return The index of the found memory type.
     * @throws GdxRuntimeException if no suitable type is found.
     */
    private int findMemoryType(int typeFilter, int requiredProperties) {
        // This struct holds properties for all memory types available on the GPU
        VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.malloc(); // Allocate on heap
        try {
            // Query the physical device for its memory properties
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProperties);

            // Iterate through the available memory types
            for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
                // Check if the i-th memory type is allowed by the typeFilter
                // (typeFilter is a bitmask where the i-th bit being 1 means allowed)
                boolean isTypeAllowed = (typeFilter & (1 << i)) != 0;

                // Check if this memory type has ALL the required properties (flags)
                boolean hasRequiredProperties = (memProperties.memoryTypes(i).propertyFlags() & requiredProperties) == requiredProperties;

                if (isTypeAllowed && hasRequiredProperties) {
                    return i; // Found a suitable type, return its index
                }
            }
        } finally {
            memProperties.free(); // Free the struct allocated with malloc()
        }

        // If the loop finishes without finding a suitable type
        throw new GdxRuntimeException("Failed to find suitable memory type!");
    }

    /**
     * Creates a Vulkan buffer and allocates/binds appropriate device memory.
     *
     * @param size             The size of the buffer in bytes.
     * @param usage            VkBufferUsageFlagBits specifying buffer usage (e.g., VERTEX_BUFFER, TRANSFER_SRC).
     * @param memoryProperties VkMemoryPropertyFlagBits specifying desired memory properties (e.g., HOST_VISIBLE, DEVICE_LOCAL).
     * @param pBufferMemory    Output buffer (capacity 1) to store the handle of the allocated VkDeviceMemory.
     * @return The handle (long) of the created VkBuffer.
     * @throws GdxRuntimeException on failure.
     */
    private long createBuffer(long size, int usage, int memoryProperties, LongBuffer pBufferMemory) {
        // Use MemoryStack for temporary Vulkan struct allocations
        try (MemoryStack stack = stackPush()) {

            // 1. Define buffer structure
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)                     // Buffer size in bytes
                    .usage(usage)                   // How the buffer will be used
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE); // Assume only graphics queue access for now

            // 2. Create the buffer handle
            LongBuffer pBufferHandle = stack.mallocLong(1); // To store the resulting handle
            vkCheck(vkCreateBuffer(rawDevice, bufferInfo, null, pBufferHandle),
                    "Failed to create VkBuffer");
            long buffer = pBufferHandle.get(0);

            // 3. Query memory requirements for this buffer
            VkMemoryRequirements memRequirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(rawDevice, buffer, memRequirements);

            // 4. Find a suitable memory type index on the GPU
            int memoryTypeIndex = findMemoryType(
                    memRequirements.memoryTypeBits(), // Bitmask of allowed memory types for this buffer
                    memoryProperties                  // Required properties (e.g., HOST_VISIBLE)
            );

            // 5. Define memory allocation structure
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memRequirements.size()) // Size required by the buffer
                    .memoryTypeIndex(memoryTypeIndex);      // Index of the suitable memory type

            // 6. Allocate the device memory
            LongBuffer pMemoryHandle = stack.mallocLong(1); // To store the resulting memory handle
            vkCheck(vkAllocateMemory(rawDevice, allocInfo, null, pMemoryHandle),
                    "Failed to allocate buffer memory");
            long bufferMemory = pMemoryHandle.get(0);

            // 7. Bind the allocated memory to the buffer handle
            // The last parameter '0' is the memory offset
            vkCheck(vkBindBufferMemory(rawDevice, buffer, bufferMemory, 0),
                    "Failed to bind buffer memory");

            // 8. Output the allocated memory handle through the parameter buffer
            // Ensure the passed LongBuffer has capacity >= 1
            pBufferMemory.put(0, bufferMemory);

            // 9. Return the handle to the created VkBuffer
            return buffer;

        } // MemoryStack automatically frees bufferInfo, pBufferHandle, memRequirements, allocInfo, pMemoryHandle
    }

    /**
     * Copies data from one VkBuffer to another using a temporary command buffer.
     * Assumes graphics queue can handle transfers. Uses vkQueueWaitIdle for synchronization.
     *
     * @param srcBuffer The source VkBuffer handle (e.g., staging buffer).
     * @param dstBuffer The destination VkBuffer handle (e.g., device-local buffer).
     * @param size      The number of bytes to copy.
     */
    private void copyBuffer(long srcBuffer, long dstBuffer, long size) {
        // ... Pre-checks ...
        long commandPool = vkDevice.getCommandPool();
        // ...

        try (MemoryStack stack = stackPush()) {
            // 1. Allocate Info (Same)
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default() // <<< --- ADD THIS LINE --- <<< (Sets sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandPool(commandPool)
                    .commandBufferCount(1);

            // 2. Allocate Handle Buffer (Same)
            PointerBuffer pCommandBuffer = stack.mallocPointer(1); // Buffer for handle(s)
            vkCheck(vkAllocateCommandBuffers(rawDevice, allocInfo, pCommandBuffer), "Failed to allocate copy command buffer");
            long commandBufferHandle = pCommandBuffer.get(0); // Extract the raw handle

            // ---> 3. Create the VkCommandBuffer WRAPPER OBJECT <---
            VkCommandBuffer commandBuffer = new VkCommandBuffer(commandBufferHandle, rawDevice);
            // ----------------------------------------------------

            // 4. Begin Recording (Use the wrapper object)
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkCheck(vkBeginCommandBuffer(commandBuffer, beginInfo), "Failed to begin recording copy command buffer");

            // 5. Record Copy Command (Use the wrapper object)
            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack)
                    // ... set offsets and size ...
                    .size(size);

            vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, copyRegion); // <<< Pass OBJECT

            // 6. End Recording (Use the wrapper object)
            vkCheck(vkEndCommandBuffer(commandBuffer), "Failed to record copy command buffer");

            // 7. Submit Command Buffer
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(pCommandBuffer); // <<< Use PointerBuffer containing handle(s) for submission

            vkCheck(vkQueueSubmit(graphicsQueue, submitInfo, VK_NULL_HANDLE), "Failed to submit copy command buffer");

            // 8. Wait for Queue Idle (Same)
            vkCheck(vkQueueWaitIdle(graphicsQueue), "Queue wait idle failed after buffer copy");

            // 9. Free Command Buffer (Use PointerBuffer containing handle(s))
            vkFreeCommandBuffers(rawDevice, commandPool, pCommandBuffer); // <<< Use PointerBuffer here

        } catch (Exception e) {
            throw new GdxRuntimeException("Failed during buffer copy", e);
        }
    }

    private void createGeometryBuffers() {
        // Don't recreate if they already exist
        if (vertexBuffer != VK_NULL_HANDLE || indexBuffer != VK_NULL_HANDLE) {
            Gdx.app.log("VulkanGraphics", "Geometry buffers seem to already exist. Skipping creation.");
            return;
        }
        Gdx.app.log("VulkanGraphics", "Creating geometry buffers...");

        try (MemoryStack stack = stackPush()) {

            LongBuffer pStagingMemory = stack.mallocLong(1);
            LongBuffer pDeviceMemory = stack.mallocLong(1); // Reusable buffer for device memory handles
            PointerBuffer pData = stack.mallocPointer(1); // Reusable buffer for mapped memory pointer

            // --- Vertex Buffer ---
            long vertexDataSize = (long)quadVertices.length * Float.BYTES;

            // 1. Create Staging Buffer (Host Visible)
            long stagingBuffer = createBuffer(vertexDataSize,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pStagingMemory); // Outputs staging memory handle
            long stagingMemory = pStagingMemory.get(0);

            // 2. Map, Copy Vertex Data, Unmap
            vkCheck(vkMapMemory(rawDevice, stagingMemory, 0, vertexDataSize, 0, pData),
                    "Failed to map vertex staging buffer memory");
            MemoryUtil.memFloatBuffer(pData.get(0), quadVertices.length).put(quadVertices).flip();
            vkUnmapMemory(rawDevice, stagingMemory);

            // 3. Create Final Vertex Buffer (Device Local)
            vertexBuffer = createBuffer(vertexDataSize,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    pDeviceMemory); // Outputs device memory handle
            vertexBufferMemory = pDeviceMemory.get(0); // Store the handle

            // 4. Copy from Staging to Device Buffer
            copyBuffer(stagingBuffer, vertexBuffer, vertexDataSize);

            // 5. Clean up Staging Buffer
            vkDestroyBuffer(rawDevice, stagingBuffer, null);
            vkFreeMemory(rawDevice, stagingMemory, null);
            Gdx.app.log("VulkanGraphics", "Vertex buffer created successfully.");

            // --- Index Buffer ---
            long indexDataSize = (long)quadIndices.length * Short.BYTES;

            // 1. Create Staging Buffer
            stagingBuffer = createBuffer(indexDataSize,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pStagingMemory);
            stagingMemory = pStagingMemory.get(0);

            // 2. Map, Copy Index Data, Unmap
            vkCheck(vkMapMemory(rawDevice, stagingMemory, 0, indexDataSize, 0, pData),
                    "Failed to map index staging buffer memory");
            MemoryUtil.memShortBuffer(pData.get(0), quadIndices.length).put(quadIndices).flip();
            vkUnmapMemory(rawDevice, stagingMemory);

            // 3. Create Final Index Buffer (Device Local)
            indexBuffer = createBuffer(indexDataSize,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    pDeviceMemory);
            indexBufferMemory = pDeviceMemory.get(0);

            // 4. Copy from Staging to Device Buffer
            copyBuffer(stagingBuffer, indexBuffer, indexDataSize);

            // 5. Clean up Staging Buffer
            vkDestroyBuffer(rawDevice, stagingBuffer, null);
            vkFreeMemory(rawDevice, stagingMemory, null);
            Gdx.app.log("VulkanGraphics", "Index buffer created successfully.");

        } catch (Exception e) {
            // Basic cleanup attempt on failure
            cleanupGeometryBuffers();
            throw new GdxRuntimeException("Failed to create geometry buffers", e);
        }
    }

    private void cleanupGeometryBuffers() {
        System.out.println("Cleaning up geometry buffers...");
        if (vertexBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(rawDevice, vertexBuffer, null);
            vertexBuffer = VK_NULL_HANDLE;
        }
        if (vertexBufferMemory != VK_NULL_HANDLE) {
            vkFreeMemory(rawDevice, vertexBufferMemory, null);
            vertexBufferMemory = VK_NULL_HANDLE;
        }
        if (indexBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(rawDevice, indexBuffer, null);
            indexBuffer = VK_NULL_HANDLE;
        }
        if (indexBufferMemory != VK_NULL_HANDLE) {
            vkFreeMemory(rawDevice, indexBufferMemory, null);
            indexBufferMemory = VK_NULL_HANDLE;
        }
    }

    private long createShaderModule(ByteBuffer code) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code); // Pass the ByteBuffer directly

            LongBuffer pShaderModule = stack.mallocLong(1);
            VkMemoryUtil.vkCheck(vkCreateShaderModule(rawDevice, createInfo, null, pShaderModule),
                    "Failed to create shader module");

            return pShaderModule.get(0);
        }
    }

    // Helper to read file into ByteBuffer
    private ByteBuffer readFileToByteBuffer(com.badlogic.gdx.files.FileHandle fileHandle) {
        if (!fileHandle.exists()) { // Add explicit check here too
            throw new GdxRuntimeException("File not found in readFileToByteBuffer: " + fileHandle.path() + " (" + fileHandle.type() + ")");
        }
        byte[] bytes = fileHandle.readBytes();
        ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    private void createShaderModules() {
        System.out.println("Creating shader modules...");
        String vertPath = "data/vulkan/shaders/vert.spv"; // Use forward slashes
        String fragPath = "data/vulkan/shaders/frag.spv";

        com.badlogic.gdx.files.FileHandle vertFileHandle = Gdx.files.internal(vertPath);
        com.badlogic.gdx.files.FileHandle fragFileHandle = Gdx.files.internal(fragPath);

        // --- Add Logging & Checks ---
        System.out.println("Attempting to load Vert Shader from: " + vertFileHandle.path() + " (Type: " + vertFileHandle.type() + ")");
        System.out.println("Vert shader exists: " + vertFileHandle.exists());
        System.out.println("Attempting to load Frag Shader from: " + fragFileHandle.path() + " (Type: " + fragFileHandle.type() + ")");
        System.out.println("Frag shader exists: " + fragFileHandle.exists());

        if (!vertFileHandle.exists()) {
            throw new GdxRuntimeException("Vertex shader not found at internal path: " + vertPath);
        }
        if (!fragFileHandle.exists()) {
            throw new GdxRuntimeException("Fragment shader not found at internal path: " + fragPath);
        }
        // --- End Logging & Checks ---

        ByteBuffer vertShaderCode = readFileToByteBuffer(vertFileHandle);
        ByteBuffer fragShaderCode = readFileToByteBuffer(fragFileHandle);

        vertShaderModule = createShaderModule(vertShaderCode);
        fragShaderModule = createShaderModule(fragShaderCode);
        System.out.println("Shader modules created.");
    }

    private void createGraphicsPipeline() {
        Gdx.app.log("VulkanGraphics", "Creating graphics pipeline...");
        try (MemoryStack stack = stackPush()) {

            // === 1. Shader Stages ===
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);

            // Vertex Shader Stage
            VkPipelineShaderStageCreateInfo vertShaderStageInfo = shaderStages.get(0);
            vertShaderStageInfo.sType$Default();
            vertShaderStageInfo.stage(VK_SHADER_STAGE_VERTEX_BIT);
            vertShaderStageInfo.module(vertShaderModule); // Handle from createShaderModules()
            vertShaderStageInfo.pName(stack.UTF8("main")); // Entry point function name in shader

            // Fragment Shader Stage
            VkPipelineShaderStageCreateInfo fragShaderStageInfo = shaderStages.get(1);
            fragShaderStageInfo.sType$Default();
            fragShaderStageInfo.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            fragShaderStageInfo.module(fragShaderModule); // Handle from createShaderModules()
            fragShaderStageInfo.pName(stack.UTF8("main"));

            // === 2. Vertex Input State ===
            // Describes how vertex data is formatted and passed to the vertex shader

            // Binding Description (how vertex data is spaced)
            VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack);
            bindingDescription.get(0)
                    .binding(0) // Index of the buffer binding (we'll use binding 0 in vkCmdBindVertexBuffers)
                    .stride(5 * Float.BYTES) // Bytes between consecutive vertices (X,Y + R,G,B = 5 floats)
                    .inputRate(VK_VERTEX_INPUT_RATE_VERTEX); // Move to next data entry for each vertex

            // Attribute Descriptions (format of each attribute within a vertex)
            VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(2, stack);
            // Position Attribute (location = 0 in shader)
            attributeDescriptions.get(0)
                    .binding(0) // Which binding description this attribute uses
                    .location(0) // Corresponds to layout(location = 0) in shader
                    .format(VK_FORMAT_R32G32_SFLOAT) // Format is vec2 (2 * 32-bit float)
                    .offset(0); // Offset in bytes from the start of the vertex data
            // Color Attribute (location = 1 in shader)
            attributeDescriptions.get(1)
                    .binding(0)
                    .location(1) // Corresponds to layout(location = 1) in shader
                    .format(VK_FORMAT_R32G32B32_SFLOAT) // Format is vec3 (3 * 32-bit float)
                    .offset(2 * Float.BYTES); // Offset after the position data (2 floats)

            // Combine binding and attributes
            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pVertexBindingDescriptions(bindingDescription)
                    .pVertexAttributeDescriptions(attributeDescriptions);

            // === 3. Input Assembly State ===
            // Describes how vertices are assembled into primitives (e.g., triangles)
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST) // Draw triangles from vertex triplets
                    .primitiveRestartEnable(false);

            // === 4. Viewport State ===
            // Defines the drawing area within the framebuffer
            // We will use dynamic viewport/scissor, so these are placeholders but struct is needed
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .viewportCount(1) // Placeholder count, actual viewport set dynamically
                    .scissorCount(1); // Placeholder count, actual scissor set dynamically

            // === 5. Rasterization State ===
            // Configures the rasterizer (converts primitives to fragments)
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthClampEnable(false) // Don't clamp fragments beyond near/far planes
                    .rasterizerDiscardEnable(false) // Don't discard geometry before rasterization
                    .polygonMode(VK_POLYGON_MODE_FILL) // Fill polygons
                    .lineWidth(1.0f)
                    .cullMode(VK_CULL_MODE_NONE) // Disable back-face culling for simple quad
                    // .cullMode(VK_CULL_MODE_BACK_BIT) // Or enable if needed
                    .frontFace(VK_FRONT_FACE_CLOCKWISE) // Define which winding order is front-facing (CHECK YOUR INDICES/VERTICES!)
                    // .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(false);

            // === 6. Multisample State ===
            // Configures MSAA (anti-aliasing) - disabled for now
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .sampleShadingEnable(false)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT); // No multisampling

            // === 7. Depth/Stencil State ===
            // Disabled for simple 2D quad
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(false)
                    .depthWriteEnable(false)
                    .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL) // Optional if test disabled
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

            // === 8. Color Blend State ===
            // Configures how fragment shader output blends with existing framebuffer color

            // Per-attachment state (we only have one color attachment)
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.get(0)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(false); // No blending (overwrite)
            // If blendEnable=true, set srcColorBlendFactor, dstColorBlendFactor, etc.

            // Global blend state
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .logicOpEnable(false) // Don't use logical operations
                    .pAttachments(colorBlendAttachment); // Attach the per-attachment state
            // .blendConstants(...); // Optional constant blend color

            // === 9. Dynamic State ===
            // Specifies which parts of the pipeline state can be changed dynamically via command buffers
            IntBuffer pDynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(pDynamicStates);

            // === 10. Pipeline Layout ===
            // Defines uniforms and push constants (none for this simple shader)
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default(); // No descriptor sets or push constants needed

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            VkMemoryUtil.vkCheck(vkCreatePipelineLayout(rawDevice, pipelineLayoutInfo, null, pPipelineLayout),
                    "Failed to create pipeline layout");
            pipelineLayout = pPipelineLayout.get(0); // Store the handle in class field

            // === 11. Create Graphics Pipeline ===
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .pStages(shaderStages) // Shader stages
                    .pVertexInputState(vertexInputInfo)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil) // Pass even if disabled
                    .pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState) // Specify dynamic states
                    .layout(pipelineLayout) // The layout created above
                    .renderPass(renderPass) // The render pass created earlier
                    .subpass(0); // Index of the subpass where this pipeline will be used
            // .basePipelineHandle(VK_NULL_HANDLE) // Optional for pipeline derivation
            // .basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            VkMemoryUtil.vkCheck(vkCreateGraphicsPipelines(rawDevice, VK_NULL_HANDLE, pipelineInfo, null, pGraphicsPipeline),
                    "Failed to create graphics pipeline");
            graphicsPipeline = pGraphicsPipeline.get(0); // Store the handle in class field

            Gdx.app.log("VulkanGraphics", "Graphics pipeline created successfully.");

        } // MemoryStack frees all structs allocated with .calloc(stack)
    }

}
