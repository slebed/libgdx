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

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.badlogic.gdx.AbstractGraphics;
import com.badlogic.gdx.Gdx;

import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.Cursor.SystemCursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GL31;
import com.badlogic.gdx.graphics.GL32;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.graphics.glutils.HdpiMode;

import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

public class VulkanGraphics extends AbstractGraphics implements Disposable {
    private final String TAG = "VulkanGraphics"; // Keep TAG
    private static final boolean debug = false;

    final VulkanApplicationConfiguration config;
    private final long windowHandle;
    private final VulkanApplication app;
    private final VulkanDevice vulkanDevice;
    private final long vmaAllocator;

    private final VulkanPipelineManager pipelineManager;
    private final VulkanDescriptorManager descriptorManager;
    private VkCommandBuffer currentRecordingCommandBuffer = null;
    private BufferFormat bufferFormat;
    private volatile int backBufferWidth;
    private volatile int backBufferHeight;
    private volatile int logicalWidth;
    private volatile int logicalHeight;
    private volatile boolean isContinuous = true;
    private long currentRenderPassHandle = VK_NULL_HANDLE;
    private int currentFrameIndex = 0;

    private int windowPosXBeforeFullscreen;
    private int windowPosYBeforeFullscreen;
    private int windowWidthBeforeFullscreen;
    private int windowHeightBeforeFullscreen;
    private DisplayMode displayModeBeforeFullscreen = null;

    private final IntBuffer tmpBuffer = BufferUtils.createIntBuffer(1);
    private final IntBuffer tmpBuffer2 = BufferUtils.createIntBuffer(1);
    private long mainSwapchainRenderPass = VK_NULL_HANDLE;
    private final List<VulkanFrameResourcePreparer> frameResourcePreparers = new CopyOnWriteArrayList<>(); // Use thread-safe list if needed, or ArrayList if access is synchronized

    private final VulkanGL20Impl vulkanGL20Instance;

    public VulkanGraphics(long windowHandle, VulkanApplicationConfiguration config, VulkanApplication app, VulkanDevice device, long vmaAllocatorHandle, VulkanPipelineManager pipelineManager, VulkanDescriptorManager descriptorManager) {
        this.windowHandle = windowHandle; // Store handle of the first window
        this.config = config;
        this.app = app; // Get app reference
        this.vulkanDevice = device;
        this.vmaAllocator = vmaAllocatorHandle;
        this.pipelineManager = pipelineManager;
        this.descriptorManager = descriptorManager;

        this.vulkanGL20Instance = new VulkanGL20Impl(this);
        if (Gdx.app != null) Gdx.app.log(TAG, "VulkanGL20Impl instance created and assigned in VulkanGraphics constructor.");
        else System.out.println(TAG + ": VulkanGL20Impl instance created and assigned in VulkanGraphics constructor.");

        int initialBackBufferWidth;
        int initialBackBufferHeight;
        int initialLogicalWidth;
        int initialLogicalHeight;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);

            GLFW.glfwGetFramebufferSize(this.windowHandle, pWidth, pHeight);
            initialBackBufferWidth = pWidth.get(0);
            initialBackBufferHeight = pHeight.get(0);

            pWidth.clear();
            pHeight.clear();

            GLFW.glfwGetWindowSize(this.windowHandle, pWidth, pHeight);
            initialLogicalWidth = pWidth.get(0);
            initialLogicalHeight = pHeight.get(0);
        }

        updateFramebufferInfo(initialBackBufferWidth, initialBackBufferHeight, initialLogicalWidth, initialLogicalHeight);
    }

    /**
     * Sets the command buffer that is currently being recorded. Called by active VulkanWindow.update().
     */
    public void setCurrentCommandBuffer(VkCommandBuffer cmd) {
        this.currentRecordingCommandBuffer = cmd;
    }

    /**
     * Gets the command buffer currently being recorded, if any. Called by VulkanSpriteBatch etc.
     */
    public VkCommandBuffer getCurrentCommandBuffer() {
        return this.currentRecordingCommandBuffer;
    }

    /**
     * Sets the handle of the VkRenderPass that is currently active. Called by active VulkanWindow.update().
     */
    public void setCurrentRenderPassHandle(long rpHandle) {
        this.currentRenderPassHandle = rpHandle;
    }

    /**
     * Gets the handle of the VkRenderPass currently active, if any. Called by VulkanSpriteBatch etc.
     */
    public long getCurrentRenderPassHandle() {
        return this.currentRenderPassHandle;
    }

    /**
     * Sets the index of the frame-in-flight currently being processed. Called by active VulkanWindow.update().
     */
    public void setCurrentFrameIndex(int index) {
        this.currentFrameIndex = index;
    }

    /**
     * Gets the index of the frame-in-flight currently being processed. Called by VulkanSpriteBatch etc.
     */
    public int getCurrentFrameIndex() {
        return this.currentFrameIndex;
    }

    /**
     * Updates stored size info based on the primary window handle.
     */
    void updateFramebufferInfo() {
        int initialBackBufferWidth;
        int initialBackBufferHeight;
        int initialLogicalWidth;
        int initialLogicalHeight;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);

            // Query initial physical framebuffer size (Backbuffer)
            GLFW.glfwGetFramebufferSize(this.windowHandle, pWidth, pHeight);
            initialBackBufferWidth = pWidth.get(0);
            initialBackBufferHeight = pHeight.get(0);

            // Reset buffers for next query
            pWidth.clear();
            pHeight.clear();

            // Query initial logical window size
            GLFW.glfwGetWindowSize(this.windowHandle, pWidth, pHeight);
            initialLogicalWidth = pWidth.get(0);
            initialLogicalHeight = pHeight.get(0);
        } // MemoryStack automatically frees pWidth, pHeight

        // Call the NEW updateFramebufferInfo method with the fetched dimensions
        updateFramebufferInfo(initialBackBufferWidth, initialBackBufferHeight, initialLogicalWidth, initialLogicalHeight);

    }

    /**
     * Updates the cached graphics dimensions.
     * Call this after a resize or mode change when the new dimensions are known.
     *
     * @param backBufferWidth The new physical width of the backbuffer.
     * @param backBufferHeight The new physical height of the backbuffer.
     * @param logicalWidth The new logical width of the window's client area.
     * @param logicalHeight The new logical height of the window's client area.
     */
    public void updateFramebufferInfo(int backBufferWidth, int backBufferHeight, int logicalWidth, int logicalHeight) {
        // Log the update attempt with the values *received*
        if (debug) Gdx.app.log(TAG, "updateFramebufferInfo called with: BB=" + backBufferWidth + " x " + backBufferHeight + ", Logical=" + logicalWidth + " x " + logicalHeight);

        this.backBufferWidth = backBufferWidth;
        this.backBufferHeight = backBufferHeight;
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;

        if (this.config != null) {
            bufferFormat = new BufferFormat(config.r, config.g, config.b, config.a, config.depth, config.stencil, config.samples, false);
        } else {
            Gdx.app.error(TAG, "Config is null during updateFramebufferInfo, cannot update bufferFormat.");
        }

        if (debug) Gdx.app.log(TAG, "Cached dimensions updated: BB=" + this.backBufferWidth + " x " + this.backBufferHeight + ", Logical=" + this.logicalWidth + " x " + this.logicalHeight);
    }

    public VulkanDevice getVulkanDevice() {
        return this.vulkanDevice;
    }

    public long getVmaAllocator() {
        return this.vmaAllocator;
    }

    public VulkanPipelineManager getPipelineManager() {
        return this.pipelineManager;
    }

    public VulkanDescriptorManager getDescriptorManager() {
        return this.descriptorManager;
    }

    public void setMainSwapchainRenderPass(long renderPassHandle) {
        if (renderPassHandle == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Attempted to set main swapchain render pass to VK_NULL_HANDLE");
        }
        this.mainSwapchainRenderPass = renderPassHandle;
        if (debug && mainSwapchainRenderPass != VK_NULL_HANDLE) Gdx.app.log(TAG, "Main swapchain render pass set to: " + mainSwapchainRenderPass);
    }

    /**
     * Gets the main VkRenderPass compatible with this graphics context's swapchain.
     * Used for creating graphics pipelines.
     */
    public long getSwapchainRenderPass() {
        if (mainSwapchainRenderPass == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "getSwapchainRenderPass() called but mainSwapchainRenderPass is VK_NULL_HANDLE! Ensure it's set after swapchain/window creation.");
        }
        return mainSwapchainRenderPass;
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
        if (this.vulkanGL20Instance == null) {
            String errorMsg = "CRITICAL ERROR: vulkanGL20Instance is null in getGL20(). It should have been created in the VulkanGraphics constructor.";
            if (Gdx.app != null) Gdx.app.error(TAG, errorMsg);
            else System.err.println(TAG + ": " + errorMsg);
            throw new IllegalStateException("VulkanGL20Impl not initialized!");
        }
        // String logMsg = "VulkanGraphics.getGL20() returning VulkanGL20Impl instance.";
        // if (Gdx.app != null) Gdx.app.log(TAG, logMsg); else System.out.println(TAG + ": " + logMsg);
        return this.vulkanGL20Instance;
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
        return (config.hdpiMode == HdpiMode.Pixels) ? backBufferWidth : logicalWidth;
    }

    @Override
    public int getHeight() {
        return (config.hdpiMode == HdpiMode.Pixels) ? backBufferHeight : logicalHeight;
    }

    @Override
    public int getBackBufferWidth() {
        return backBufferWidth;
    }

    @Override
    public int getBackBufferHeight() {
        return backBufferHeight;
    }

    @Override
    public long getFrameId() {
        return app.frameId;
    }

    @Override
    public float getDeltaTime() {
        return app.deltaTime;
    }

    public void resetDeltaTime() {
        app.resetDeltaTime = true;
    }

    @Override
    public int getFramesPerSecond() {
        return app.fps;
    }

    @Override
    public GraphicsType getType() {
        return GraphicsType.LWJGL3;
    }

    @Override
    public GLVersion getGLVersion() {
        return null;// glVersion;
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
    public float getDensity() {
        return getPpiX() / 160f;
    }

    @Override
    public boolean supportsDisplayModeChange() {
        return true;
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

    public VkCommandBuffer getCurrentVkCommandBuffer() {
        return this.currentRecordingCommandBuffer;
    }

    public VulkanWindow getCurrentWindow() {
        if (app != null) {
            return app.getCurrentWindow(); // Assumes VulkanApplication has a getCurrentWindow() method
        }
        return null;
    }

    private void storeCurrentWindowPositionAndDisplayMode() {
        VulkanWindow primary = app.getPrimaryWindow();
        if (primary != null) {
            // Use the window's getters for current state
            windowPosXBeforeFullscreen = primary.getPositionX();
            windowPosYBeforeFullscreen = primary.getPositionY();
            windowWidthBeforeFullscreen = primary.getLogicalWidth();
            windowHeightBeforeFullscreen = primary.getLogicalHeight();
        } else {
            // Fallback using potentially stale graphics fields
            windowPosXBeforeFullscreen = 0; // Or get from primaryWindowHandle? Less safe if window moved.
            windowPosYBeforeFullscreen = 0;
            windowWidthBeforeFullscreen = logicalWidth;
            windowHeightBeforeFullscreen = logicalHeight;
            Gdx.app.error(TAG, "Could not get primary window to store position/size before fullscreen.");
        }
        // Get display mode of the monitor the primary window is currently on
        displayModeBeforeFullscreen = getDisplayMode(getMonitor());
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
        if (app != null) {
            VulkanWindow primary = app.getPrimaryWindow();
            if (primary != null) {
                primary.requestRendering();
            }
        }
    }

    @Override
    public void setForegroundFPS(int fps) {
        if (config != null) {
            config.foregroundFPS = fps;
        }
    }

    @Override
    public void dispose() {
        if (debug) Gdx.app.log(TAG, "Disposing VulkanGraphics...");

        if (vulkanDevice != null && vulkanDevice.getRawDevice() != null) {
            if (debug) Gdx.app.log(TAG, "Waiting for device idle before graphics cleanup...");
        }

        // Clear context state
        this.currentRecordingCommandBuffer = null;
        this.currentRenderPassHandle = VK_NULL_HANDLE;

        if (debug) Gdx.app.log(TAG, "VulkanGraphics cleanup finished.");
    }

    /**
     * Vulkan-specific implementation of Monitor.
     */
    public static class VulkanMonitor extends Monitor {
        final long monitorHandle; // Store the GLFW monitor handle

        protected VulkanMonitor(long handle, int virtualX, int virtualY, String name) {
            super(virtualX, virtualY, name);
            this.monitorHandle = handle;
        }

        public long getMonitorHandle() {
            return monitorHandle;
        }
    }

    /**
     * Helper method to convert a GLFW monitor handle to a VulkanMonitor object.
     * (Could also reside in VulkanApplicationConfiguration).
     *
     * @param glfwMonitor Handle to the GLFW monitor.
     * @return A VulkanMonitor instance.
     */
    public static VulkanMonitor toVulkanMonitor(long glfwMonitor) { // Ensure public static
        if (glfwMonitor == NULL) {
            throw new GdxRuntimeException("Cannot create VulkanMonitor from NULL GLFW handle.");
        }

        IntBuffer x = BufferUtils.createIntBuffer(1); // Or use stack allocation if in instance method
        IntBuffer y = BufferUtils.createIntBuffer(1);
        GLFW.glfwGetMonitorPos(glfwMonitor, x, y);
        String name = GLFW.glfwGetMonitorName(glfwMonitor);
        if (name == null) name = "Unknown";
        return new VulkanMonitor(glfwMonitor, x.get(0), y.get(0), name);
    }

    /**
     * Gets the GLFW window handle of the primary application window.
     *
     * @return The primary window handle.
     */
    public long getWindowHandle() {
        return this.windowHandle;
    }

    /**
     * Vulkan-specific implementation of DisplayMode.
     */
    public static class VulkanDisplayMode extends DisplayMode { // <<< ADDED CLASS
        final long monitorHandle; // Store associated monitor handle

        protected VulkanDisplayMode(long monitorHandle, int width, int height, int refreshRate, int bitsPerPixel) {
            super(width, height, refreshRate, bitsPerPixel);
            this.monitorHandle = monitorHandle;
        }

        public long getMonitorHandle() {
            return monitorHandle;
        }
    }

    // Method for renderers/users to register
    public void registerFrameResourcePreparer(VulkanFrameResourcePreparer preparer) {
        if (preparer != null && !frameResourcePreparers.contains(preparer)) {
            frameResourcePreparers.add(preparer);
        }
    }

    // Method for renderers/users to unregister (e.g., on dispose)
    public void unregisterFrameResourcePreparer(VulkanFrameResourcePreparer preparer) {
        if (preparer != null) {
            frameResourcePreparers.remove(preparer);
        }
    }

    // Method called internally at the right time in the frame loop
    void prepareAllFrameResources(int frameIndex) {
        for (VulkanFrameResourcePreparer preparer : frameResourcePreparers) {
            try {
                preparer.prepareResourcesForFrame(frameIndex);
            } catch (Exception e) {
                Gdx.app.error("VulkanGraphics", "Exception during prepareResourcesForFrame for " + preparer.getClass().getSimpleName(), e);
                // Decide how to handle errors - continue? rethrow?
            }
        }
    }

    @Override
    public boolean setFullscreenMode(DisplayMode displayMode) {
        if (app == null) {
            Gdx.app.error(TAG, "Cannot set fullscreen mode, app reference is null.");
            return false;
        }
        // Delegate to VulkanApplication, which will use the currentWindow
        return app.setFullscreenMode(displayMode);
    }

    @Override
    public boolean setWindowedMode(int width, int height) {
        if (app == null) {
            Gdx.app.error(TAG, "Cannot set windowed mode, app reference is null.");
            return false;
        }
        // Delegate to VulkanApplication
        return app.setWindowedMode(width, height);
    }

    @Override
    public void setTitle(String title) {
        if (app == null) {
            Gdx.app.error(TAG, "Cannot set title, app reference is null.");
            return;
        }
        // Delegate to VulkanApplication
        app.setTitle(title);
    }

    @Override
    public void setUndecorated(boolean undecorated) {
        if (app == null) {
            Gdx.app.error(TAG, "Cannot set undecorated state, app reference is null.");
            return;
        }
        // Delegate to VulkanApplication
        app.setUndecorated(undecorated);
    }

    @Override
    public void setResizable(boolean resizable) {
        if (app == null) {
            Gdx.app.error(TAG, "Cannot set resizable state, app reference is null.");
            return;
        }
        // Delegate to VulkanApplication
        app.setResizable(resizable);
    }

    @Override
    public void setVSync(boolean vsync) {
        if (app == null) {
            Gdx.app.error(TAG, "Cannot set VSync, app reference is null.");
            return;
        }
        // Delegate to VulkanApplication
        app.setVSync(vsync);
    }

    @Override
    public boolean isFullscreen() {
        if (app == null) {
            Gdx.app.error(TAG, "Cannot check fullscreen state, app reference is null.");
            return false; // Or throw? Defaulting to false.
        }
        // Delegate to VulkanApplication
        return app.isFullscreen();
    }

    @Override
    public Cursor newCursor(Pixmap pixmap, int xHotspot, int yHotspot) {
        if (app == null) {
            Gdx.app.error(TAG, "Cannot create cursor, app reference is null.");
            // Maybe return a dummy cursor or throw?
            return null;
        }
        // Delegate cursor creation to the application, which knows the current window
        return app.newCursor(pixmap, xHotspot, yHotspot);
    }

    @Override
    public void setCursor(Cursor cursor) {
        if (app == null) {
            Gdx.app.error(TAG, "Cannot set cursor, app reference is null.");
            return;
        }
        // Delegate to VulkanApplication
        app.setCursor(cursor);
    }

    @Override
    public void setSystemCursor(SystemCursor systemCursor) {
        if (app == null) {
            Gdx.app.error(TAG, "Cannot set system cursor, app reference is null.");
            return;
        }
        // Delegate to VulkanApplication
        app.setSystemCursor(systemCursor);
    }

    // --- Monitor and Display Mode Delegation ---

    @Override
    public Monitor getPrimaryMonitor() {
        if (app == null) {
            // Fallback or error? Let's try calling the static method directly
            Gdx.app.error(TAG, "App reference null in getPrimaryMonitor, using static fallback.");
            return VulkanApplicationConfiguration.getPrimaryMonitor();
        }
        // Delegate to VulkanApplication
        return app.getPrimaryMonitor();
    }

    @Override
    public Monitor getMonitor() {
        if (app == null) {
            Gdx.app.error(TAG, "App reference null in getMonitor, falling back to primary.");
            return getPrimaryMonitor(); // Fallback to primary
        }
        // Delegate to VulkanApplication to get the monitor for the *current* window
        return app.getCurrentWindowMonitor();
    }

    @Override
    public Monitor[] getMonitors() {
        if (app == null) {
            Gdx.app.error(TAG, "App reference null in getMonitors, using static fallback.");
            return VulkanApplicationConfiguration.getMonitors();
        }
        // Delegate to VulkanApplication
        return app.getMonitors();
    }

    @Override
    public DisplayMode[] getDisplayModes() {
        // This implicitly uses getMonitor() which delegates correctly
        return getDisplayModes(getMonitor());
    }

    @Override
    public DisplayMode[] getDisplayModes(Monitor monitor) {
        if (app == null) {
            Gdx.app.error(TAG, "App reference null in getDisplayModes(Monitor), using static fallback.");
            return VulkanApplicationConfiguration.getDisplayModes(monitor);
        }
        // Delegate to VulkanApplication
        return app.getDisplayModes(monitor);
    }

    @Override
    public DisplayMode getDisplayMode() {
        // This implicitly uses getMonitor() which delegates correctly
        return getDisplayMode(getMonitor());
    }

    @Override
    public DisplayMode getDisplayMode(Monitor monitor) {
        if (app == null) {
            Gdx.app.error(TAG, "App reference null in getDisplayMode(Monitor), using static fallback.");
            return VulkanApplicationConfiguration.getDisplayMode(monitor);
        }
        // Delegate to VulkanApplication
        return app.getDisplayMode(monitor);
    }

    public VulkanTexture createVulkanTextureShell(int glHandle) {
        return null;//new VulkanTexture(glHandle, this.vulkanDevice, this.vmaAllocator);
    }
}