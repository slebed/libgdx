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
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.AbstractGraphics;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
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
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;

public class VulkanGraphics extends AbstractGraphics implements Disposable {
    final String logTag = "VulkanGraphics";
    private final VulkanApplicationConfiguration config;
    private final long windowHandle;
    private final long vmaAllocator;
    volatile boolean framebufferResized = false; // Flag for resize/vsync change

    private boolean vsyncEnabled = true; // Store desired vsync state, default true
    private List<VkCommandBuffer> commandBuffers; // VkCommandBuffer objects

    private VulkanSyncManager syncManager;
    private VulkanRenderer vulkanRenderer;

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
    private VulkanMesh quadMesh;

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

    private VkPhysicalDevice physicalDevice;

    private static final class FrameInfo { // final makes the class itself non-extendable
        final boolean success;           // final fields ensure immutability after construction
        final VkCommandBuffer commandBuffer;
        final int imageIndex;

        // Constructor to initialize the final fields
        FrameInfo(boolean success, VkCommandBuffer commandBuffer, int imageIndex) {
            this.success = success;
            this.commandBuffer = commandBuffer;
            this.imageIndex = imageIndex;
        }
    }

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

        this.glVersion = new GLVersion(Application.ApplicationType.Desktop, "", "", "Vulkan");
    }

    public VulkanWindow getWindow() {
        return window;
    }

    public void initializeSwapchainAndResources() {
        // --- 1. Get Core Vulkan Dependencies ---
        this.app = (VulkanApplication) Gdx.app;
        if (this.app == null) {
            throw new GdxRuntimeException("Gdx.app is null, cannot initialize VulkanGraphics resources.");
        }
        this.vulkanDevice = app.getVkDevice();
        if (this.vulkanDevice == null || this.vulkanDevice.getRawDevice() == null || this.vulkanDevice.getRawDevice().address() == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VulkanDevice is not initialized in VulkanApplication.");
        }
        this.surface = app.getSurface(this.windowHandle);
        if (this.surface == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Vulkan Surface KHR is not created for window handle: " + windowHandle);
        }
        // Get other device components (already done if fields are initialized from constructor)
        this.rawDevice = vulkanDevice.getRawDevice();
        this.physicalDevice = vulkanDevice.getPhysicalDevice();
        this.graphicsQueue = vulkanDevice.getGraphicsQueue();
        this.presentQueue = graphicsQueue; // Assuming same queue

        Gdx.app.log(logTag, "Initializing swapchain and graphics resources...");

        try {
            this.descriptorManager = new VulkanDescriptorManager(this.vulkanDevice);
            this.pipelineManager = new VulkanPipelineManager(this.vulkanDevice);
        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to create managers during init", e);
        }

        try (MemoryStack stack = stackPush()) {

            // --- 2. Create Swapchain ---
            Gdx.app.log(logTag, "Building VulkanSwapchain...");
            this.vulkanSwapchain = new VulkanSwapchain.Builder()
                    .device(this.vulkanDevice)
                    .surface(this.surface)
                    .windowHandle(this.windowHandle)
                    .configuration(this.config)
                    .build();
            Gdx.app.log(logTag, "VulkanSwapchain built successfully.");

            // --- 3. Create Other Graphics Resources ---

            // Load Texture (creates VulkanImage, ImageView, Sampler)
            Gdx.app.log(logTag, "Loading default texture...");
            FileHandle textureFile = Gdx.files.internal("data/badlogic.jpg"); // Ensure exists
            if (!textureFile.exists()) throw new GdxRuntimeException("Default texture file not found: " + textureFile);
            this.texture = VulkanTexture.loadFromFile(textureFile, this.vulkanDevice, this.vmaAllocator);
            Gdx.app.log(logTag, "Default texture loaded successfully.");

            // Descriptor Setup (Layout is created in manager, allocate set, update)
            this.singleTextureLayoutHandle = descriptorManager.getDefaultLayout();
            if (this.singleTextureLayoutHandle == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Failed to get default layout from DescriptorManager");
            }
            Gdx.app.log(logTag, "Retrieved descriptor set layout from manager.");

            this.textureDescriptorSet = descriptorManager.allocateSet();
            if (this.textureDescriptorSet == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Failed to allocate descriptor set from DescriptorManager");
            }
            Gdx.app.log(logTag, "Allocated descriptor set from manager.");

            VulkanDescriptorManager.updateCombinedImageSampler(
                    rawDevice,
                    this.textureDescriptorSet,
                    0, // binding
                    this.texture // Pass VulkanTexture object
            );
            Gdx.app.log(logTag, "Updated descriptor set using manager helper for texture.");

            // Graphics Pipeline (Manager loads shaders internally now)
            Gdx.app.log(logTag, "Creating graphics pipeline via manager...");
            FileHandle vertShaderFile = Gdx.files.internal("data/vulkan/shaders/textured.vert.spv");
            FileHandle fragShaderFile = Gdx.files.internal("data/vulkan/shaders/textured.frag.spv");
            long renderPassHandle = this.vulkanSwapchain.getRenderPass(); // Get RP handle from Swapchain
            if (renderPassHandle == VK_NULL_HANDLE) throw new GdxRuntimeException("Render pass handle invalid for pipeline creation");
            if (!vertShaderFile.exists()) throw new GdxRuntimeException("Vertex shader file not found: " + vertShaderFile);
            if (!fragShaderFile.exists()) throw new GdxRuntimeException("Fragment shader file not found: " + fragShaderFile);

            pipelineManager.createDefaultPipeline(
                    vertShaderFile,
                    fragShaderFile,
                    this.singleTextureLayoutHandle,
                    renderPassHandle
            );
            Gdx.app.log(logTag, "Graphics pipeline created via manager.");

            // Geometry Buffers (Create VulkanMesh)
            Gdx.app.log(logTag, "Creating quad mesh...");
            VertexAttributes quadAttributes = new VertexAttributes(
                    new VertexAttribute(VertexAttributes.Usage.Position, 2, ShaderProgram.POSITION_ATTRIBUTE),
                    new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 3, ShaderProgram.COLOR_ATTRIBUTE),
                    new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE + "0")
            );
            this.quadMesh = new VulkanMesh(this.vulkanDevice, this.vmaAllocator, true, // isStatic = true
                    quadVertices.length / 7, // numVertices = 4
                    quadIndices.length,    // numIndices = 6
                    quadAttributes);
            this.quadMesh.setVertices(quadVertices); // Upload data
            this.quadMesh.setIndices(quadIndices);   // Upload data
            Gdx.app.log(logTag, "Quad mesh created successfully.");

            this.vulkanRenderer = new VulkanRenderer(this.vulkanDevice);
            Gdx.app.log(logTag, "VulkanRenderer created.");

            // Main Command Buffers (per swapchain image)
            createMainCommandBuffers(stack);
            Gdx.app.log(logTag, "Main command buffers created.");

            // Synchronization Objects
            Gdx.app.log(logTag, "Creating sync objects via manager...");
            this.syncManager = new VulkanSyncManager(this.vulkanDevice);
            Gdx.app.log(logTag, "Sync objects created via manager.");

            Gdx.app.log(logTag, "Initialization of swapchain and resources complete.");

        } catch (Exception e) {
            Gdx.app.error(logTag, "Exception during initializeSwapchainAndResources", e);
            try {
                cleanupVulkan(); // Attempt cleanup
            } catch (Exception cleanupEx) {
                Gdx.app.error(logTag, "Exception during cleanup after initialization failure", cleanupEx);
            }
            throw new GdxRuntimeException("Failed to initialize Vulkan graphics resources", e);
        }
    }

    // Simplified recreateSwapChain in VulkanGraphics
    private void recreateSwapChain() {
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
                //Gdx.app.log(logTag, "Window minimized, deferring swapchain recreation.");
                this.vulkanSwapchain.needsRecreation = true;
                this.framebufferResized = true;
                return;
            }
        }

        Gdx.app.log(logTag, "Waiting for device idle before swapchain recreation...");
        vkDeviceWaitIdle(rawDevice);
        Gdx.app.log(logTag, "Device idle. Calling swapchain recreate...");

        try {
            // Delegate swapchain resource recreation
            vulkanSwapchain.recreate();
            Gdx.app.log(logTag, "Swapchain recreation delegated.");

            // Update internal size tracking
            updateFramebufferInfo();

            // Recreate Graphics Pipeline (uses new render pass from swapchain)
            Gdx.app.log(logTag, "Recreating graphics pipeline after swapchain change...");
            FileHandle vertShaderFile = Gdx.files.internal("data/vulkan/shaders/textured.vert.spv");
            FileHandle fragShaderFile = Gdx.files.internal("data/vulkan/shaders/textured.frag.spv");
            long renderPassHandle = this.vulkanSwapchain.getRenderPass();
            // Assume layout handle (singleTextureLayoutHandle) is still valid
            if (pipelineManager == null || renderPassHandle == VK_NULL_HANDLE || singleTextureLayoutHandle == VK_NULL_HANDLE
                    || !vertShaderFile.exists() || !fragShaderFile.exists()) {
                throw new GdxRuntimeException("Missing dependencies for pipeline recreation during swapchain recreate.");
            }
            pipelineManager.createDefaultPipeline(
                    vertShaderFile,
                    fragShaderFile,
                    this.singleTextureLayoutHandle,
                    renderPassHandle
            );
            Gdx.app.log(logTag, "Graphics pipeline recreated via manager.");

            // Re-allocate Command Buffers (number might have changed)
            cleanupCommandBuffers(); // Clean up old command buffer objects/handles first
            try (MemoryStack stack = stackPush()) {
                createMainCommandBuffers(stack);
                Gdx.app.log(logTag, "Main command buffers re-allocated.");
            }

            // Notify listener
            if (Gdx.app != null && Gdx.app.getApplicationListener() != null) {
                Gdx.app.getApplicationListener().resize(getWidth(), getHeight());
            }
            Gdx.app.log(logTag, "Swapchain recreation fully complete.");

        } catch (Exception e) {
            Gdx.app.error(logTag, "Failed during VulkanSwapchain recreation or dependent resource update", e);
            // We might be in a bad state here, re-throwing is safest
            throw new GdxRuntimeException("Failed to fully recreate swapchain and dependent resources", e);
        }
    }

    /**
     * Cleans up the main command buffer List and potentially frees buffers
     * if the pool wasn't created with RESET_COMMAND_BUFFER_BIT (though ours is).
     * Primarily clears the Java list reference.
     */
    private void cleanupCommandBuffers() {
        // For pools created with VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        // explicitly freeing individual buffers isn't strictly required before resetting/reallocating,
        // but it doesn't hurt to clear the Java list.
        // If not using that flag, vkFreeCommandBuffers would be needed here.
        if (commandBuffers != null) {
            Gdx.app.log(logTag, "Clearing main command buffer list.");
            commandBuffers.clear(); // Clear the list of wrappers
            commandBuffers = null; // Allow GC
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

        // Dispose managers and resources in reverse order of dependency/creation
        if (pipelineManager != null) {
            pipelineManager.dispose();
            pipelineManager = null;
        }
        if (descriptorManager != null) {
            descriptorManager.dispose();
            descriptorManager = null;
        }
        if (texture != null) {
            texture.dispose();
            texture = null;
        }
        if (quadMesh != null) {
            quadMesh.dispose();
            quadMesh = null;
        }
        if (vulkanSwapchain != null) {
            vulkanSwapchain.dispose();
            vulkanSwapchain = null;
        }

        if (syncManager != null) {
            Gdx.app.log(logTag, "Disposing sync manager...");
            syncManager.dispose();
            syncManager = null;
        }

        singleTextureLayoutHandle = VK_NULL_HANDLE;
        textureDescriptorSet = VK_NULL_HANDLE;

        // Clear command buffer list reference (actual buffers tied to pool)
        if (commandBuffers != null) {
            commandBuffers.clear();
            commandBuffers = null;
        }

        vulkanRenderer = null;
        rawDevice = null; // Mark this instance as cleaned
        logInfo(logTag, "VulkanGraphics cleanup finished.", useGdxLog);
    }

    private void logInfo(String tag, String message, boolean useGdx) {
        if (useGdx) Gdx.app.log(tag, message);
        else System.out.println("[" + tag + "] " + message);
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

    public boolean drawFrame() {
        // Step 1: Check if recreation is needed BEFORE trying to begin frame
        if (framebufferResized || (vulkanSwapchain != null && vulkanSwapchain.needsRecreation())) {
            //Gdx.app.log(logTag, "[DrawFrame] Recreation needed (resized=" + framebufferResized + ", needsRecreation=" + (vulkanSwapchain != null && vulkanSwapchain.needsRecreation()) + ").");
            framebufferResized = false; // Reset GLFW flag
            if (vulkanSwapchain == null || vulkanSwapchain.getHandle() == VK_NULL_HANDLE) {
                Gdx.app.error(logTag, "[DrawFrame] Cannot recreate, swapchain invalid.");
                return false; // Cannot proceed if swapchain fundamentally broken
            }

            recreateSwapChain(); // Call the method to handle recreation

            // Skip rendering this frame as resources changed or might still need recreation (minimized)
            return false;
        }

        // Step 2: Begin the frame
        FrameInfo frameInfo = beginFrame();
        if (!frameInfo.success) {
            return false;
        }

        VkCommandBuffer commandBuffer = frameInfo.commandBuffer;
        int imageIndex = frameInfo.imageIndex; // Keep index for endFrame

        try {
            // --- CORE DRAWING LOGIC (Using Renderer) ---
            vulkanRenderer.begin(commandBuffer); // Tell renderer which CB to use

            vulkanRenderer.setDynamicStates(vulkanSwapchain.getExtent()); // Set viewport/scissor
            vulkanRenderer.bindPipeline(pipelineManager.getGraphicsPipeline()); // Bind the default pipeline
            vulkanRenderer.bindDescriptorSet(pipelineManager.getPipelineLayout(), this.textureDescriptorSet, 0); // Bind the texture's set
            vulkanRenderer.drawMesh(quadMesh); // Bind and draw the mesh

            vulkanRenderer.end(); // Finish renderer sequence for this CB
            // --- END CORE DRAWING ---

        } catch (Exception e) {
            Gdx.app.error(logTag, "Exception during drawing command recording via Renderer", e);
            // Attempt to end frame, then re-throw
            try {
                endFrame(commandBuffer, imageIndex);
            } catch (Exception endEx) { /* Log endEx */ }
            throw new GdxRuntimeException("Exception during drawing command recording", e);
        } finally {
            // Ensure renderer state is cleared even if error occurred during drawing
            // (begin() already checks if already active)
            if (vulkanRenderer != null) {
                vulkanRenderer.end(); // Ensure end() is called if begin() succeeded but drawing failed
            }
        }

        // Step 3: End the frame
        endFrame(commandBuffer, imageIndex);

        update(); // Update timing
        return true; // Rendered successfully
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

    /*private void createDescriptorSetLayout() {
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
*/

    /**
     * Handles start-of-frame Vulkan operations: waits for GPU, acquires swapchain image,
     * resets fence, begins command buffer, and begins the render pass.
     *
     * @return A FrameInfo object containing success status, the active command buffer, and the swapchain image index.
     * Returns success=false if swapchain is out of date or acquire failed non-critically, requiring recreation check.
     */
    private FrameInfo beginFrame() {
        // --- Pre-checks ---
        if (vulkanSwapchain == null || vulkanSwapchain.getHandle() == VK_NULL_HANDLE) {
            Gdx.app.error(logTag, "[beginFrame] Swapchain object or handle is invalid!");
            return new FrameInfo(false, null, -1);
        }
        if (syncManager == null) {
            Gdx.app.error(logTag, "[beginFrame] SyncManager is null!");
            // Or throw new GdxRuntimeException("SyncManager not initialized");
            return new FrameInfo(false, null, -1);
        }

        try (MemoryStack stack = stackPush()) {
            // --- 1. Wait for the Previous Frame to Finish ---
            long fence = syncManager.getInFlightFence(); // Get fence from manager
            if (fence == VK_NULL_HANDLE) throw new GdxRuntimeException("In-flight fence handle is NULL!");
            vkCheck(vkWaitForFences(rawDevice, fence, true, Long.MAX_VALUE),
                    "vkWaitForFences failed!");

            // --- 2. Acquire Next Swapchain Image ---
            IntBuffer pImageIndex = stack.mallocInt(1);
            long imageAcquireSemaphore = syncManager.getImageAvailableSemaphore(); // Get semaphore from manager
            if (imageAcquireSemaphore == VK_NULL_HANDLE) throw new GdxRuntimeException("Image available semaphore handle is NULL!");

            int acquireResult = vulkanSwapchain.acquireNextImage(
                    imageAcquireSemaphore,
                    VK_NULL_HANDLE, // No fence needed for acquire
                    pImageIndex);

            int imageIndex = pImageIndex.get(0);

            // Handle Acquire Results
            if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
                Gdx.app.log(logTag, "[beginFrame] Swapchain out of date after acquire. Needs recreation.");
                // Flag is set internally by acquireNextImage, recreation check happens before beginFrame
                return new FrameInfo(false, null, -1); // Signal failure
            } else if (acquireResult == VK_SUBOPTIMAL_KHR) {
                Gdx.app.log(logTag, "[beginFrame] Swapchain suboptimal after acquire. Recreation pending next frame.");
                // Proceed, but flag is set for next frame's check
            } else if (acquireResult != VK_SUCCESS) {
                throw new GdxRuntimeException("[beginFrame] Failed to acquire swap chain image! Result: " + VkResultDecoder.decode(acquireResult));
            }

            // --- 3. Reset Fence ---
            // Fence waited successfully, image acquired successfully (or suboptimal), reset the fence
            vkCheck(vkResetFences(rawDevice, fence), "vkResetFences failed!"); // Use fence handle obtained earlier

            // --- 4. Prepare Command Buffer ---
            if (commandBuffers == null || imageIndex < 0 || imageIndex >= commandBuffers.size()) {
                throw new GdxRuntimeException("[beginFrame] CommandBuffers list invalid or imageIndex (" + imageIndex + ") out of bounds. CB Size=" + (commandBuffers != null ? commandBuffers.size() : "null"));
            }
            VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);

            vkCheck(vkResetCommandBuffer(commandBuffer, 0), "vkResetCommandBuffer failed!");

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            vkCheck(vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer failed!");

            // --- 5. Begin Render Pass ---
            if (renderPassManager == null) throw new GdxRuntimeException("RenderPassManager is null!");
            renderPassManager.beginSwapchainRenderPass(commandBuffer, vulkanSwapchain, imageIndex, null); // Use default clear color

            // --- Success ---
            return new FrameInfo(true, commandBuffer, imageIndex);

        } catch (Exception e) {
            Gdx.app.error(logTag, "Exception during beginFrame", e);
            return new FrameInfo(false, null, -1); // Indicate failure on any exception
        }
    }

    /**
     * Handles end-of-frame Vulkan operations: ends render pass, ends command buffer,
     * submits command buffer to queue, and presents the swapchain image.
     *
     * @param commandBuffer The command buffer that was recorded into.
     * @param imageIndex    The index of the swapchain image that was acquired and rendered to.
     */
    private void endFrame(VkCommandBuffer commandBuffer, int imageIndex) {
        // --- Pre-checks ---
        if (commandBuffer == null) {
            Gdx.app.error(logTag, "[endFrame] Received null command buffer!");
            // Maybe throw? Continuing might leave sync objects in weird state.
            throw new GdxRuntimeException("Cannot end frame with null command buffer");
        }
        if (vulkanSwapchain == null || vulkanSwapchain.getHandle() == VK_NULL_HANDLE) {
            Gdx.app.error(logTag, "[endFrame] Swapchain object or handle is invalid!");
            throw new GdxRuntimeException("Cannot end frame with invalid swapchain");
        }
        if (syncManager == null) {
            Gdx.app.error(logTag, "[endFrame] SyncManager is null!");
            throw new GdxRuntimeException("SyncManager not initialized for endFrame");
        }


        try (MemoryStack stack = stackPush()) {

            // --- 1. End Render Pass & Command Buffer ---
            if (renderPassManager == null) throw new GdxRuntimeException("RenderPassManager is null!");
            renderPassManager.endRenderPass(commandBuffer);
            vkCheck(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer failed!");

            // --- 2. Submit Command Buffer ---
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default();

            // Get sync object handles from manager
            long imgAvailSem = syncManager.getImageAvailableSemaphore();
            long renderFinSem = syncManager.getRenderFinishedSemaphore();
            long fence = syncManager.getInFlightFence();
            if (imgAvailSem == VK_NULL_HANDLE || renderFinSem == VK_NULL_HANDLE || fence == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("One or more sync object handles are NULL in endFrame!");
            }


            LongBuffer waitSemaphores = stack.longs(imgAvailSem); // Wait for image available
            IntBuffer waitStages = stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            LongBuffer signalSemaphores = stack.longs(renderFinSem); // Signal render finished

            submitInfo.waitSemaphoreCount(1).pWaitSemaphores(waitSemaphores).pWaitDstStageMask(waitStages);
            submitInfo.pCommandBuffers(stack.pointers(commandBuffer.address()));
            submitInfo.pSignalSemaphores(signalSemaphores);

            // Submit to the graphics queue, signaling the inFlightFence
            vkCheck(vkQueueSubmit(graphicsQueue, submitInfo, fence), // Use fence from manager
                    "vkQueueSubmit failed!");

            // --- 3. Presentation ---
            int presentResult = vulkanSwapchain.present(
                    presentQueue,            // Use present queue
                    imageIndex,
                    renderFinSem);           // Wait for render finished semaphore from manager

            // Handle Present Results (needsRecreation flag is set internally if needed)
            if (presentResult != VK_SUCCESS && presentResult != VK_SUBOPTIMAL_KHR && presentResult != VK_ERROR_OUT_OF_DATE_KHR) {
                // Throw only for critical errors, OOD/Suboptimal are handled by recreation check next frame
                throw new GdxRuntimeException("[endFrame] Failed to present swap chain image! Result: " + VkResultDecoder.decode(presentResult));
            }
            // Logging for suboptimal/ood happens within vulkanSwapchain.present if desired

        } catch (Exception e) {
            Gdx.app.error(logTag, "Exception during endFrame", e);
            throw new GdxRuntimeException("Exception during endFrame", e);
        }
    }

}
