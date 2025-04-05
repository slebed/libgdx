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
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
import static org.lwjgl.util.vma.Vma.vmaMapMemory;
import static org.lwjgl.util.vma.Vma.vmaUnmapMemory;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.*;

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

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkViewport;

public class VulkanGraphics extends AbstractGraphics implements Disposable {
    final String logTag = "VulkanGraphics";
    private final VulkanApplicationConfiguration config;
    private final long windowHandle;
    private final long vmaAllocator;
    volatile boolean framebufferResized = false; // Flag for resize/vsync change

    private boolean vsyncEnabled = true; // Store desired vsync state, default true
    private List<VkCommandBuffer> commandBuffers; // VkCommandBuffer objects

    // Sync objects (basic, 1 frame in flight)
    private long imageAvailableSemaphore = VK_NULL_HANDLE;
    private long renderFinishedSemaphore = VK_NULL_HANDLE;
    private long inFlightFence = VK_NULL_HANDLE;

    // Assuming access to VulkanApplication components
    private VulkanApplication app = (VulkanApplication) Gdx.app; // Or get via constructor arg

    private VulkanDevice vulkanDevice = app.getVkDevice();           // Need getter in VulkanApplication
    private long surface;     // Need getter in VulkanApplication
    private org.lwjgl.vulkan.VkDevice rawDevice = vulkanDevice.getRawDevice(); // Get raw device from VulkanDevice wrapper
    private VkQueue graphicsQueue = vulkanDevice.getGraphicsQueue();       // Get from VulkanDevice wrapper
    private VkQueue presentQueue = graphicsQueue; // Assume graphics and present are the same for simplicity
    private final VulkanRenderPassManager renderPassManager;
    private VulkanPipelineManager pipelineManager;
    private final GLVersion glVersion;
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
    private VulkanSwapchain vulkanSwapchain;

    VulkanWindow window;
    IntBuffer tmpBuffer = BufferUtils.createIntBuffer(1);
    IntBuffer tmpBuffer2 = BufferUtils.createIntBuffer(1);

    private VulkanTexture texture;

    private VulkanDescriptorManager descriptorManager;

    private long singleTextureLayoutHandle = VK_NULL_HANDLE;
    private long textureDescriptorSet = VK_NULL_HANDLE;

    private final float[] quadVertices = {
            // Position      // Color          // TexCoord (UV)
            -0.5f, -0.5f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            0.5f, -0.5f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f,
            0.5f, 0.5f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f,
            -0.5f, 0.5f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f
            // Note: Vulkan's default screen coord Y is often down, while texture V coord is often up.
            // You might need to flip V coordinates (0,0 top-left -> 1,1 bottom-right) depending on your image loading and coordinate system.
            // This example assumes V=0 is top, V=1 is bottom of texture. Adjust if needed!
    };
    private final short[] quadIndices = {0, 1, 2, 2, 3, 0};

    private VulkanBuffer vertexBuffer; // Uses the wrapper class
    private VulkanBuffer indexBuffer;  // Uses the wrapper class

    // Store counts for drawing
    private final int vertexCount = 4; // Explicitly 4 vertices
    private final int indexCount = quadIndices.length; // 6 indices

    private long vertShaderModule = VK_NULL_HANDLE;
    private long fragShaderModule = VK_NULL_HANDLE;

    GLFWFramebufferSizeCallback resizeCallback = new GLFWFramebufferSizeCallback() {
        @Override
        public void invoke(long windowHandle, final int width, final int height) {
            if (Gdx.graphics instanceof VulkanGraphics) {
                ((VulkanGraphics) Gdx.graphics).framebufferResized(width, height);
            }
            System.out.println("Framebuffer resize requested: " + width + "x" + height);
        }
    };

    public VulkanGraphics(long windowHandle, VulkanApplicationConfiguration config, VulkanDevice device, long vmaAllocatorHandle) {
        this.windowHandle = windowHandle;
        this.config = config; // Keep local copy if needed, or use Gdx.app.getConfig()
        this.vulkanDevice = device; // Store the VulkanDevice reference
        this.vmaAllocator = vmaAllocatorHandle; // Store the VMA handle

        this.renderPassManager = new VulkanRenderPassManager();

        if (this.vulkanDevice == null) {
            throw new GdxRuntimeException("VulkanDevice cannot be null for VulkanGraphics");
        }
        if (this.vmaAllocator == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VMA Allocator handle cannot be null for VulkanGraphics");
        }

        // Initial size query can happen here or later
        updateFramebufferInfo();

        // Other non-Vulkan initializations if any...
        this.glVersion = new GLVersion(Application.ApplicationType.Desktop, "", "", "Vulkan");
    }

    public VulkanWindow getWindow() {
        return window;
    }

    public void initializeSwapchainAndResources() {
        // --- 1. Get Core Vulkan Dependencies ---
        // It's crucial that VulkanApplication has initialized these before this method is called.
        // Using Gdx.app here assumes it's safe and properly typed.
        this.app = (VulkanApplication) Gdx.app;
        if (this.app == null) {
            throw new GdxRuntimeException("Gdx.app is null, cannot initialize VulkanGraphics resources.");
        }

        // Get the logical device wrapper
        this.vulkanDevice = app.getVkDevice();
        if (this.vulkanDevice == null || this.vulkanDevice.getRawDevice() == null || this.vulkanDevice.getRawDevice().address() == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VulkanDevice is not initialized in VulkanApplication.");
        }

        this.pipelineManager = new VulkanPipelineManager(this.vulkanDevice);
        this.descriptorManager = new VulkanDescriptorManager(this.vulkanDevice);

        // Get the surface associated with *this* graphics instance's window
        // VulkanApplication needs a way to provide the correct surface per window handle.
        this.surface = app.getSurface(this.windowHandle);
        if (this.surface == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Vulkan Surface KHR is not created for window handle: " + windowHandle);
        }

        // Get other necessary device components (raw device, physical device, queues)
        // These are now obtained via the vulkanDevice wrapper or directly if needed
        this.rawDevice = vulkanDevice.getRawDevice();

        this.graphicsQueue = vulkanDevice.getGraphicsQueue();
        // Assuming present queue is the same as graphics for now
        // TODO: Query and store separate present queue if necessary
        this.presentQueue = graphicsQueue;

        Gdx.app.log("VulkanGraphics", "Initializing swapchain and graphics resources...");

        try (MemoryStack stack = stackPush()) { // Use stack for temporary allocations if needed locally

            // --- 2. Create Swapchain using the Builder ---
            Gdx.app.log("VulkanGraphics", "Building VulkanSwapchain...");
            this.vulkanSwapchain = new VulkanSwapchain.Builder()
                    .device(this.vulkanDevice)
                    .surface(this.surface)
                    .windowHandle(this.windowHandle)
                    .configuration(this.config) // Pass the config for initial VSync etc.
                    .build(); // This call creates swapchain, views, renderpass, framebuffers
            Gdx.app.log("VulkanGraphics", "VulkanSwapchain built successfully.");

            // --- 3. Create Other Graphics Resources ---
            // Shaders (don't depend on swapchain)
            createShaderModules();
            Gdx.app.log("VulkanGraphics", "Shader modules created.");

            try {
                Gdx.app.log(logTag, "Loading default texture...");
                FileHandle textureFile = Gdx.files.internal("data/badlogic.jpg"); // Ensure exists
                this.texture = VulkanTexture.loadFromFile(textureFile, this.vulkanDevice, this.vmaAllocator);
                Gdx.app.log(logTag, "Default texture loaded successfully.");
            } catch (Exception e) {
                // Handle texture loading failure during initialization
                throw new GdxRuntimeException("Failed to load default texture during init", e);
            }

            // Descriptor Setup (Layout, Pool, Set - layout might depend on texture sampler binding)
            createDescriptorSetLayout();
            Gdx.app.log("VulkanGraphics", "Descriptor set layout created.");
            createDescriptorSet();
            Gdx.app.log("VulkanGraphics", "Descriptor set allocated.");
            updateDescriptorSet(); // Link texture resources to descriptor set
            Gdx.app.log("VulkanGraphics", "Descriptor set updated.");

            // Graphics Pipeline (Depends on shaders, vertex layout, AND the render pass from the swapchain)
            createGraphicsPipeline(); // This method now gets renderPass via vulkanSwapchain.getRenderPass()
            Gdx.app.log("VulkanGraphics", "Graphics pipeline created.");

            // Geometry Buffers (vertex/index - don't depend on swapchain)
            createGeometryBuffers();
            Gdx.app.log("VulkanGraphics", "Geometry buffers created.");

            // Command Buffers (Pool already exists in VulkanDevice, allocate main command buffers here)
            // Note: These command buffers are separate from the single-time ones used for resource loading.
            // These are the ones used in drawFrame().
            createMainCommandBuffers(stack); // Pass stack if needed, rename method if desired
            Gdx.app.log("VulkanGraphics", "Main command buffers created.");

            // Synchronization Objects (semaphores, fences for frame pacing)
            createSyncObjects(stack); // Pass stack if needed
            Gdx.app.log("VulkanGraphics", "Sync objects created.");

            Gdx.app.log("VulkanGraphics", "Initialization of swapchain and resources complete.");

        } catch (Exception e) {
            // Cleanup whatever might have been created before the exception
            // Calling the main cleanupVulkan is safest as it checks for null handles.
            Gdx.app.error("VulkanGraphics", "Exception during initializeSwapchainAndResources", e);
            try {
                cleanupVulkan(); // Attempt cleanup
            } catch (Exception cleanupEx) {
                Gdx.app.error("VulkanGraphics", "Exception during cleanup after initialization failure", cleanupEx);
            }
            // Re-throw the original exception to signal failure
            throw new GdxRuntimeException("Failed to initialize Vulkan graphics resources", e);
        }
    }

    /**
     * Creates the command buffers used for recording rendering commands each frame.
     * One command buffer is typically allocated per swapchain image.
     */
    private void createMainCommandBuffers(MemoryStack stack) {
        if (vulkanSwapchain == null || vulkanSwapchain.getImageCount() == 0) {
            throw new GdxRuntimeException("Cannot create main command buffers before swapchain exists.");
        }
        int count = vulkanSwapchain.getImageCount();
        commandBuffers = new ArrayList<>(count); // Assuming commandBuffers is List<VkCommandBuffer> field

        VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(vulkanDevice.getCommandPool()) // Use main command pool from VulkanDevice
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(count);

        PointerBuffer pCommandBuffers = stack.mallocPointer(count);
        vkCheck(vkAllocateCommandBuffers(rawDevice, allocInfo, pCommandBuffers), "Failed to allocate main command buffers");

        for (int i = 0; i < count; i++) {
            // Create the LWJGL wrapper object for each handle
            commandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), rawDevice));
        }
        Gdx.app.log("VulkanGraphics", "Allocated " + count + " main command buffers.");
    }

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

    private void createSyncObjects(MemoryStack stack) {
        VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK_FENCE_CREATE_SIGNALED_BIT); // Create fence initially signaled

        LongBuffer pSemaphore = stack.mallocLong(1);
        LongBuffer pFence = stack.mallocLong(1);

        vkCheck(vkCreateSemaphore(rawDevice, semaphoreInfo, null, pSemaphore), "Failed to create image available semaphore");
        imageAvailableSemaphore = pSemaphore.get(0);

        vkCheck(vkCreateSemaphore(rawDevice, semaphoreInfo, null, pSemaphore), "Failed to create render finished semaphore");
        renderFinishedSemaphore = pSemaphore.get(0);

        vkCheck(vkCreateFence(rawDevice, fenceInfo, null, pFence),
                "Failed to create in-flight fence");
        inFlightFence = pFence.get(0);
    }

    // --- Need cleanupVulkan() method for dispose() and error handling ---
    private void cleanupVulkan() {
        // Use Gdx.app logger if available, otherwise System.out/err
        final boolean useGdxLog = (Gdx.app != null && Gdx.app.getApplicationLogger() != null);

        // Check if already cleaned or device is invalid
        if (rawDevice == null) {
            System.out.println("[" + logTag + "] Already cleaned or device object is null.");
            return;
        }

        // 1. Wait for GPU to finish all operations using these resources
        logInfo(logTag, "Waiting for device idle before graphics cleanup...", useGdxLog);
        vkDeviceWaitIdle(rawDevice); // MUST BE FIRST!
        logInfo(logTag, "Device idle. Proceeding with graphics cleanup...", useGdxLog);

        // 2. Destroy Pipeline and related objects
        if (pipelineManager != null) {
            pipelineManager.dispose();
            pipelineManager = null;
        }

        cleanupShaderModules();       // Destroys Shader Modules

        if (descriptorManager != null) { descriptorManager.dispose(); descriptorManager = null; } // Dispose manager

        // 4. Destroy Texture Resources
        if (texture != null) {
            Gdx.app.log(logTag, "Disposing VulkanTexture wrapper...");
            texture.dispose(); // Calls internal cleanup for view, sampler, image
            texture = null;
        }

        // 5. Destroy Geometry Buffers
        cleanupGeometryBuffers();     // Destroys Buffers and Frees Memory

        // 7. Destroy Synchronization Objects
        cleanupSyncObjects();         // Destroys Semaphores, Fences

        vulkanSwapchain.dispose();

        vertShaderModule = VK_NULL_HANDLE;
        fragShaderModule = VK_NULL_HANDLE;

        imageAvailableSemaphore = VK_NULL_HANDLE;
        renderFinishedSemaphore = VK_NULL_HANDLE;
        inFlightFence = VK_NULL_HANDLE;

        rawDevice = null; // Mark this instance as cleaned
        logInfo(logTag, "VulkanGraphics cleanup finished.", useGdxLog);
    }

    private void logInfo(String tag, String message, boolean useGdx) {
        if (useGdx) Gdx.app.log(tag, message);
        else System.out.println("[" + tag + "] " + message);
    }

    private void cleanupSyncObjects() {
        final boolean useGdxLog = (Gdx.app != null && Gdx.app.getApplicationLogger() != null);
        logInfo(logTag, "Cleaning up sync objects...", useGdxLog);

        // Check if device exists before proceeding
        if (rawDevice == null) {
            logInfo(logTag, "Device already null in cleanupSyncObjects.", useGdxLog);
            // Ensure handles are null anyway
            imageAvailableSemaphore = VK_NULL_HANDLE;
            renderFinishedSemaphore = VK_NULL_HANDLE;
            inFlightFence = VK_NULL_HANDLE;
            return;
        }

        // Using safeDestroy avoids explicit null checks for handles
        VkMemoryUtil.safeDestroySemaphore(imageAvailableSemaphore, rawDevice);
        imageAvailableSemaphore = VK_NULL_HANDLE;

        VkMemoryUtil.safeDestroySemaphore(renderFinishedSemaphore, rawDevice);
        renderFinishedSemaphore = VK_NULL_HANDLE;

        VkMemoryUtil.safeDestroyFence(inFlightFence, rawDevice);
        inFlightFence = VK_NULL_HANDLE;

        logInfo(logTag, "Sync objects destroyed.", useGdxLog);
    }

    /**
     * Destroys the Vulkan shader modules.
     */
    private void cleanupShaderModules() {
        final boolean useGdxLog = (Gdx.app != null && Gdx.app.getApplicationLogger() != null);
        logInfo(logTag, "Cleaning up shader modules...", useGdxLog);

        if (rawDevice == null) { /* ... log error/return ... */
            return;
        }

        if (vertShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(rawDevice, vertShaderModule, null);
            vertShaderModule = VK_NULL_HANDLE;
        }
        if (fragShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(rawDevice, fragShaderModule, null);
            fragShaderModule = VK_NULL_HANDLE;
        }
        logInfo(logTag, "Shader modules destroyed.", useGdxLog);
    }

    void updateFramebufferInfo() {
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
            System.out.println("VulkanGraphics: Initial FB Size: " + backBufferWidth + "x" + backBufferHeight + ", Logical Size: " + logicalWidth + "x" + logicalHeight);
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
     * Uses the managed VulkanSwapchain object for swapchain operations.
     *
     * @return true if rendering occurred, false if skipped (e.g., due to resize/recreation).
     */
    public boolean drawFrame() {
        // --- Initial Validity Check ---
        // Check if swapchain object exists and its handle seems valid before proceeding
        if (vulkanSwapchain == null || vulkanSwapchain.getHandle() == VK_NULL_HANDLE) {
            Gdx.app.error("VulkanGraphics", "[DrawFrame] Swapchain object or handle is invalid! Attempting recreation trigger.");
            // If the swapchain isn't valid, we definitely need recreation.
            // Ensure flags are set so the next attempt triggers recreateSwapChain().
            this.framebufferResized = true; // Use resize flag to trigger the check path
            if (vulkanSwapchain != null) {
                // If object exists but handle is null, ensure its internal flag is set too.
                vulkanSwapchain.needsRecreation = true;
            }
            // Cannot render this frame.
            return false;
        }

        try (MemoryStack stack = stackPush()) {

            // --- 1. Wait for the Previous Frame to Finish ---
            // Wait indefinitely for the fence protecting the resources of the frame we are about to render.
            vkCheck(vkWaitForFences(rawDevice, inFlightFence, true, Long.MAX_VALUE), "vkWaitForFences failed!");

            // --- 2. Handle Swapchain Recreation ---
            // Check if recreation is needed due to resize event or previous suboptimal/OOD state.
            // Check AFTER waiting for fence to ensure resources aren't in use.
            if (framebufferResized || vulkanSwapchain.needsRecreation()) {
                Gdx.app.log("VulkanGraphics", "[DrawFrame] Recreation needed (resized=" + framebufferResized + ", needsRecreation=" + vulkanSwapchain.needsRecreation() + ").");
                framebufferResized = false; // Reset GLFW resize callback flag
                // The needsRecreation flag within vulkanSwapchain will be reset internally if recreate() succeeds.
                recreateSwapChain(); // This calls vkDeviceWaitIdle then vulkanSwapchain.recreate() etc.

                // If recreateSwapChain threw an exception, we won't get here.
                // If it returned normally, skip rendering this frame as resources changed.
                // It's possible recreation failed internally (e.g., minimized window),
                // in which case needsRecreation might still be true, handled next frame.
                return false;
            }

            // --- 3. Acquire Next Swapchain Image ---
            //Gdx.app.log("VulkanGraphics", "[DrawFrame] Frame " + frameId + ". Acquiring image...");
            IntBuffer pImageIndex = stack.mallocInt(1);

            // Use the wrapper method from the VulkanSwapchain object
            int acquireResult = vulkanSwapchain.acquireNextImage(
                    imageAvailableSemaphore, // Semaphore to signal when image is available
                    VK_NULL_HANDLE,          // Fence (optional, using semaphore)
                    pImageIndex);

            int imageIndex = pImageIndex.get(0); // Index of the acquired image

            // Handle Acquire Results (needsRecreation flag is set internally by acquireNextImage)
            if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
                // Swapchain is outdated, cannot render. Recreation flagged internally.
                Gdx.app.log("VulkanGraphics", "[DrawFrame] Swapchain out of date after acquire. Recreation pending next frame.");
                // No need to reset fence yet. Skip rest of frame.
                return false;
            } else if (acquireResult == VK_SUBOPTIMAL_KHR) {
                // Can still render, but flag is set for recreation next frame.
                Gdx.app.log("VulkanGraphics", "[DrawFrame] Swapchain suboptimal after acquire. Recreation pending next frame.");
                // Proceed with rendering this frame.
            } else if (acquireResult != VK_SUCCESS) {
                // Handle other critical errors (e.g., device lost)
                throw new GdxRuntimeException("Failed to acquire swap chain image! Result: " + VkResultDecoder.decode(acquireResult));
            }

            // --- Reset Fence ---
            // Now that we have successfully acquired an image and don't need immediate recreation,
            // reset the fence associated with *this* frame, allowing vkQueueSubmit to signal it later.
            vkCheck(vkResetFences(rawDevice, inFlightFence), "vkResetFences failed!");

            // --- 4. Record Command Buffer ---
            if (imageIndex < 0 || imageIndex >= commandBuffers.size()) {
                throw new GdxRuntimeException("Invalid imageIndex (" + imageIndex + ") obtained from acquireNextImage. CB Size=" + commandBuffers.size());
            }
            VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);

            // Reset command buffer (reuse)
            vkCheck(vkResetCommandBuffer(commandBuffer, 0), "vkResetCommandBuffer failed!");

            // Begin recording
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            // Optionally add .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT) if not reusing CBs frequently,
            // but typically primary CBs are reused.
            vkCheck(vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer failed!");

            // --- Begin Render Pass ---
            renderPassManager.beginSwapchainRenderPass(commandBuffer, vulkanSwapchain, imageIndex, null);

            VkExtent2D currentExtent = vulkanSwapchain.getExtent();

            // --- Set Dynamic States and Bind Resources ---
            //vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getGraphicsPipeline());

            // Set dynamic viewport (example: Y-down)
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.x(0.0f);
            viewport.y((float) currentExtent.height());
            viewport.width((float) currentExtent.width());
            viewport.height(-(float) currentExtent.height()); // Negative height for Y-down
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, 0, viewport);

            // Set dynamic scissor
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(currentExtent); // Use full extent
            vkCmdSetScissor(commandBuffer, 0, scissor);

            // Bind vertex buffer
            LongBuffer pVertexBuffers = stack.longs(vertexBuffer.bufferHandle);
            LongBuffer pOffsets = stack.longs(0);
            vkCmdBindVertexBuffers(commandBuffer, 0, pVertexBuffers, pOffsets);

            // Bind index buffer
            vkCmdBindIndexBuffer(commandBuffer, indexBuffer.bufferHandle, 0, VK_INDEX_TYPE_UINT16); // Assuming 16-bit indices

            // Bind descriptor sets
            LongBuffer pDescriptorSets = stack.longs(this.textureDescriptorSet); // Use the stored set handle
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getPipelineLayout(), 0, pDescriptorSets, null);

            // --- Issue Draw Call ---
            vkCmdDrawIndexed(commandBuffer, indexCount, 1, 0, 0, 0);

            // --- End Render Pass / Command Buffer ---
            renderPassManager.endRenderPass(commandBuffer);

            vkCheck(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer failed!");

            // --- 5. Submit Command Buffer ---
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default();

            // Wait for imageAvailableSemaphore before executing color attachment output stage
            LongBuffer waitSemaphores = stack.longs(imageAvailableSemaphore);
            IntBuffer waitStages = stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            submitInfo.waitSemaphoreCount(1).pWaitSemaphores(waitSemaphores).pWaitDstStageMask(waitStages);

            // Command buffer to execute
            submitInfo.pCommandBuffers(stack.pointers(commandBuffer.address()));

            // Signal renderFinishedSemaphore when commands complete
            LongBuffer signalSemaphores = stack.longs(renderFinishedSemaphore);
            submitInfo.pSignalSemaphores(signalSemaphores);

            // Submit to the graphics queue, signaling inFlightFence when done
            vkCheck(vkQueueSubmit(graphicsQueue, submitInfo, inFlightFence), "vkQueueSubmit failed!");

            // --- 6. Presentation ---
            // Use the wrapper method from the VulkanSwapchain object
            int presentResult = vulkanSwapchain.present(
                    presentQueue,            // Queue for presentation
                    imageIndex,              // Index of image to present
                    renderFinishedSemaphore);// Wait for rendering to finish

            // Handle Present Results (needsRecreation flag is set internally by present)
            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) {
                // Flag is already set, check at start of next frame will handle it.
                Gdx.app.log("VulkanGraphics", "[DrawFrame] Swapchain suboptimal/OOD after present. Recreation pending next frame.");
                // No exception needed here, allows frame to finish.
            } else if (presentResult != VK_SUCCESS) {
                // Handle other critical errors
                throw new GdxRuntimeException("Failed to present swap chain image! Result: " + VkResultDecoder.decode(presentResult));
            }

            // --- Update Timing ---
            update(); // Update delta time, frame ID, FPS counter etc.

            return true; // Indicate rendering happened for this frame

        } catch (Exception e) {
            // Catch potential exceptions from Vulkan calls or other logic
            Gdx.app.error("VulkanGraphics", "Exception during drawFrame", e);
            // Re-throw as a runtime exception to halt the application loop cleanly
            throw new GdxRuntimeException("Exception during drawFrame", e);
        }
    }

    // Simplified recreateSwapChain in VulkanGraphics
    private void recreateSwapChain() {
        // Check if swapchain object exists - Needed if called before init finishes?
        if (this.vulkanSwapchain == null) {
            Gdx.app.error(logTag, "Attempted to recreate null swapchain object!");
            return;
        }

        // Check for minimized window
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(this.windowHandle, pWidth, pHeight);
            if (pWidth.get(0) == 0 || pHeight.get(0) == 0) {
                Gdx.app.log(logTag, "Window minimized, deferring swapchain recreation.");
                // Ensure flag remains set in VulkanSwapchain so we try again later
                this.vulkanSwapchain.needsRecreation = true; // Make sure it tries again
                this.framebufferResized = true; // Also keep local flag set
                return;
            }
        }

        Gdx.app.log(logTag, "Waiting for device idle before swapchain recreation...");
        vkDeviceWaitIdle(rawDevice); // Wait here BEFORE calling recreate
        Gdx.app.log(logTag, "Device idle. Calling swapchain recreate...");

        try {
            // Delegate recreation to the swapchain object
            vulkanSwapchain.recreate();
            Gdx.app.log(logTag, "Swapchain recreation delegated.");

            // Update internal size tracking AFTER recreation
            updateFramebufferInfo();

            // Recreate Graphics Pipeline - RenderPass might have changed
            Gdx.app.log(logTag, "Recreating graphics pipeline after swapchain change...");
            // cleanupPipelineLayout(); // Called internally by createGraphicsPipeline now
            createGraphicsPipeline();
            Gdx.app.log(logTag, "Graphics pipeline recreated.");

            // Notify listener AFTER new resources are ready and internal state updated
            if (Gdx.app != null && Gdx.app.getApplicationListener() != null) {
                Gdx.app.getApplicationListener().resize(getWidth(), getHeight());
            }
            Gdx.app.log(logTag, "Swapchain recreation fully complete.");

        } catch (Exception e) {
            // Log error and re-throw
            Gdx.app.error(logTag, "Failed during VulkanSwapchain recreation or pipeline update", e);
            throw new GdxRuntimeException("Failed to recreate swapchain/pipeline", e);
        }
    }

    @Override
    public boolean isGL30Available() {
        return false;
    }

    @Override
    public boolean isGL31Available() {
        return false;
    }

    @Override
    public boolean isGL32Available() {
        return false;
    }

    @Override
    public GL20 getGL20() {
        return null;
    }

    @Override
    public GL30 getGL30() {
        return null;
    }

    @Override
    public GL31 getGL31() {
        return null;
    }

    @Override
    public GL32 getGL32() {
        return null;
    }


    @Override
    public void setGL20(GL20 gl20) {

    }

    @Override
    public void setGL30(GL30 gl30) {

    }

    @Override
    public void setGL31(GL31 gl31) {

    }

    @Override
    public void setGL32(GL32 gl32) {

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
            vulkanSwapchain.setVSync(vsync);
            System.out.println("VSync changed to: " + vsync + ", requesting swapchain recreation.");
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
     * Copies data from one VkBuffer to another using a single-time command buffer.
     *
     * @param srcBuffer The source VkBuffer handle.
     * @param dstBuffer The destination VkBuffer handle.
     * @param size      The number of bytes to copy.
     */
    private void copyBuffer(long srcBuffer, long dstBuffer, long size) {
        if (vulkanDevice == null) throw new GdxRuntimeException("VulkanDevice not initialized for copyBuffer");

        vulkanDevice.executeSingleTimeCommands(commandBuffer -> { // Pass lambda for recording
            try (MemoryStack stack = stackPush()) {
                VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack)
                        .srcOffset(0)  // Optional: if copying from specific offset
                        .dstOffset(0)  // Optional: if copying to specific offset
                        .size(size);
                vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, copyRegion);
            } // Stack frame for copyRegion is automatically managed
        }); // executeSingleTimeCommands handles begin, end, submit, wait, free
    }

    private void createGeometryBuffers() {
        // Clean up old buffers if they exist (e.g., during recreation)
        cleanupGeometryBuffers(); // Ensure previous are disposed

        Gdx.app.log("VulkanGraphics", "Creating geometry buffers using VMA...");

        VulkanBuffer stagingVertexBuffer = null;
        VulkanBuffer stagingIndexBuffer = null;

        try {
            // --- Vertex Buffer ---
            long vertexDataSize = (long) quadVertices.length * Float.BYTES;

            // 1. Create Staging Buffer (Host Visible, Mapped)
            stagingVertexBuffer = VulkanResourceUtil.createManagedBuffer(
                    vmaAllocator,
                    vertexDataSize,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,             // Usage: Source for transfer
                    VMA_MEMORY_USAGE_AUTO,                        // Let VMA pick CPU or GPU based on flags
                    VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT // Mappable
            );

            // 2. Map, Copy Vertex Data, Unmap (Flush might be needed if not HOST_COHERENT)
            PointerBuffer pData = MemoryUtil.memAllocPointer(1); // Use heap allocation for pointer across VMA calls
            try {
                vkCheck(vmaMapMemory(vmaAllocator, stagingVertexBuffer.allocationHandle, pData), "VMA Failed to map vertex staging buffer");
                ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int) vertexDataSize);
                byteBuffer.asFloatBuffer().put(quadVertices).flip();
                // If VMA_MEMORY_USAGE_AUTO didn't pick a HOST_COHERENT type, flush is needed:
                // vmaFlushAllocation(vmaAllocator, stagingVertexBuffer.allocationHandle, 0, vertexDataSize);
                vmaUnmapMemory(vmaAllocator, stagingVertexBuffer.allocationHandle);
            } finally {
                MemoryUtil.memFree(pData); // Free the pointer buffer
            }


            // 3. Create Final Vertex Buffer (Device Local Optimal)
            this.vertexBuffer = VulkanResourceUtil.createManagedBuffer(
                    vmaAllocator,
                    vertexDataSize,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, // Usage: Dest + Vertex
                    VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE,          // Prefer GPU memory
                    0                                             // No specific alloc flags needed
            );

            // 4. Copy using single-time command
            copyBuffer(stagingVertexBuffer.bufferHandle, this.vertexBuffer.bufferHandle, vertexDataSize); // Uses executeSingleTimeCommands


            Gdx.app.log("VulkanGraphics", "Vertex buffer created successfully.");


            // --- Index Buffer ---
            long indexDataSize = (long) quadIndices.length * Short.BYTES;

            // 1. Create Staging Buffer
            stagingIndexBuffer = VulkanResourceUtil.createManagedBuffer(
                    vmaAllocator,
                    indexDataSize,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VMA_MEMORY_USAGE_AUTO,
                    VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
            );

            // 2. Map, Copy Index Data, Unmap
            pData = MemoryUtil.memAllocPointer(1);
            try {
                vkCheck(vmaMapMemory(vmaAllocator, stagingIndexBuffer.allocationHandle, pData), "VMA Failed to map index staging buffer");
                ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int) indexDataSize);
                byteBuffer.asShortBuffer().put(quadIndices).flip();
                // vmaFlushAllocation(vmaAllocator, stagingIndexBuffer.allocationHandle, 0, indexDataSize); // If needed
                vmaUnmapMemory(vmaAllocator, stagingIndexBuffer.allocationHandle);
            } finally {
                MemoryUtil.memFree(pData);

            }


            // 3. Create Final Index Buffer
            this.indexBuffer = VulkanResourceUtil.createManagedBuffer(
                    vmaAllocator,
                    indexDataSize,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT, // Usage: Dest + Index
                    VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE,
                    0
            );

            // 4. Copy using single-time command
            copyBuffer(stagingIndexBuffer.bufferHandle, this.indexBuffer.bufferHandle, indexDataSize);

            Gdx.app.log("VulkanGraphics", "Index buffer created successfully.");

        } catch (Exception e) {
            // Ensure partial resources are cleaned up on failure
            cleanupGeometryBuffers(); // Call helper that disposes wrappers
            throw new GdxRuntimeException("Failed to create geometry buffers", e);
        } finally {
            // 5. Clean up Staging Buffers (always happens)
            if (stagingVertexBuffer != null) {
                Gdx.app.log(logTag, "Disposing staging buffer (handle " + stagingVertexBuffer.bufferHandle + ")"); // Add log
                stagingVertexBuffer.dispose();
            }
            if (stagingIndexBuffer != null) {
                Gdx.app.log(logTag, "Disposing staging buffer (handle " + stagingIndexBuffer.bufferHandle + ")"); // Add log
                stagingIndexBuffer.dispose();
            }

        }
    }

    private void cleanupGeometryBuffers() {
        Gdx.app.log("VulkanGraphics", "Cleaning up geometry buffer wrappers...");
        if (vertexBuffer != null) {
            vertexBuffer.dispose(); // Calls vmaDestroyBuffer internally
            vertexBuffer = null;
        }
        if (indexBuffer != null) {
            indexBuffer.dispose(); // Calls vmaDestroyBuffer internally
            indexBuffer = null;
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
        String vertPath = "data/vulkan/shaders/textured.vert.spv"; // Use forward slashes
        String fragPath = "data/vulkan/shaders/textured.frag.spv";

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
        // Ensure dependencies are ready
        if (this.vulkanSwapchain == null || this.vulkanSwapchain.getRenderPass() == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot create pipeline before swapchain and render pass exist.");
        }
        if (this.vertShaderModule == VK_NULL_HANDLE || this.fragShaderModule == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Shader modules must be created before pipeline.");
        }

        if (this.singleTextureLayoutHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("DescriptorSetLayout handle not set before pipeline creation.");
        }
        pipelineManager.createDefaultPipeline(
                this.vertShaderModule,
                this.fragShaderModule,
                this.singleTextureLayoutHandle, // Pass the stored layout handle
                this.vulkanSwapchain.getRenderPass()
        );
    }

    private void createDescriptorSetLayout() {
        Gdx.app.log(logTag, "Attempting to retrieve layout from DescriptorManager...");
        if (descriptorManager == null) {
            throw new GdxRuntimeException("DescriptorManager is null in createDescriptorSetLayout!");
        }
        long layoutHandleFromManager = descriptorManager.getDefaultLayout();
        Gdx.app.log(logTag, "Value returned by descriptorManager.getDefaultLayout(): " + layoutHandleFromManager); // <-- ADD LOG

        this.singleTextureLayoutHandle = layoutHandleFromManager; // Assignment
        Gdx.app.log(logTag, "Value assigned to this.singleTextureLayoutHandle: " + this.singleTextureLayoutHandle); // <-- ADD LOG

        // Keep the check
        if (this.singleTextureLayoutHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to get default layout from DescriptorManager (returned NULL).");
        }
        Gdx.app.log(logTag, "Retrieved descriptor set layout from manager successfully.");
    }

    private void createDescriptorSet() {
        // Allocate the set using the manager
        this.textureDescriptorSet = descriptorManager.allocateSet();
        if (this.textureDescriptorSet == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to allocate descriptor set from DescriptorManager");
        }
        Gdx.app.log(logTag, "Allocated descriptor set from manager.");
    }

    private void updateDescriptorSet() {
        // Use the static helper method

        if (this.texture == null || this.textureDescriptorSet == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot update descriptor set, texture or set handle is null.");
        }
        VulkanDescriptorManager.updateCombinedImageSampler(
                rawDevice, // Pass VkDevice
                this.textureDescriptorSet,
                0, // binding
                this.texture // Pass the VulkanTexture object itself
        );
        Gdx.app.log(logTag, "Updated descriptor set using manager helper for texture.");

    }

}
