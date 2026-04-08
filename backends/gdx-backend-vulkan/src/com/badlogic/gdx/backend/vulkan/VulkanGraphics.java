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

/**
 * Core rendering interface for the Vulkan backend.
 *
 * <h3>Threading Model</h3>
 * <ul>
 *   <li>All Vulkan command buffer recording, pipeline binding, and draw calls must happen on the
 *       <b>main (render) thread</b>.</li>
 *   <li>Dimension getters ({@code getWidth()}, {@code getBackBufferWidth()}, etc.) are safe to call
 *       from any thread — they read from a single volatile {@link DimensionSnapshot} reference,
 *       ensuring a consistent width/height pair even during concurrent resize events.</li>
 *   <li>{@link #updateFramebufferInfo} is called from the main thread (GLFW resize callback path).</li>
 *   <li>{@link #registerFrameResourcePreparer} / {@link #unregisterFrameResourcePreparer} use a
 *       {@link java.util.concurrent.CopyOnWriteArrayList} and are safe from any thread.</li>
 *   <li>Frame timing fields ({@code deltaTime}, {@code frameId}, {@code fps}) are only written on
 *       the main thread; reading from other threads is safe but values may be stale.</li>
 * </ul>
 */
public class VulkanGraphics extends AbstractGraphics implements Disposable {
    private final String TAG = "VulkanGraphics";
    private final boolean debug;

    final VulkanApplicationConfiguration config;
    private final long windowHandle;
    private final VulkanApplication app;
    private final VulkanDevice vulkanDevice;
    private final long vmaAllocator;
    VulkanWindow window; // Set after construction via setWindow()

    private final VulkanPipelineManager pipelineManager;
    private final VulkanDescriptorManager descriptorManager;
    private VkCommandBuffer currentRecordingCommandBuffer = null;
    private BufferFormat bufferFormat;

    /** Immutable snapshot of framebuffer dimensions, published atomically via volatile reference.
     * Ensures readers never see an inconsistent mix of old/new width/height values. */
    private static final class DimensionSnapshot {
        final int backBufferWidth, backBufferHeight, logicalWidth, logicalHeight;
        DimensionSnapshot(int bbW, int bbH, int lW, int lH) {
            this.backBufferWidth = bbW; this.backBufferHeight = bbH;
            this.logicalWidth = lW; this.logicalHeight = lH;
        }
    }
    private volatile DimensionSnapshot dimensions = new DimensionSnapshot(0, 0, 0, 0);
    private volatile boolean isContinuous = true;
    private long currentRenderPassHandle = VK_NULL_HANDLE;
    private int currentFrameIndex = 0;

    // Frame timing (owned by Graphics, matching Lwjgl3Graphics)
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

    final IntBuffer tmpBuffer = BufferUtils.createIntBuffer(1);
    final IntBuffer tmpBuffer2 = BufferUtils.createIntBuffer(1);
    private long mainSwapchainRenderPass = VK_NULL_HANDLE;
    private long currentSwapchainFramebuffer = VK_NULL_HANDLE;
    private VulkanSwapchain currentSwapchain;
    private final List<VulkanFrameResourcePreparer> frameResourcePreparers = new CopyOnWriteArrayList<>();

    private final VulkanGL20Impl vulkanGL20Instance;

    public VulkanGraphics(long windowHandle, long surfaceHandle, VulkanApplicationConfiguration config, VulkanApplication app, VulkanDevice device, long vmaAllocatorHandle, VulkanPipelineManager pipelineManager, VulkanDescriptorManager descriptorManager) {
        this.windowHandle = windowHandle;
        this.config = config;
        this.app = app;
        this.debug = config.debugLog;
        this.vulkanDevice = device;
        this.vmaAllocator = vmaAllocatorHandle;
        this.pipelineManager = pipelineManager;
        this.descriptorManager = descriptorManager;

        this.vulkanGL20Instance = new VulkanGL20Impl(this);
        if (debug) {
            if (Gdx.app != null) Gdx.app.log(TAG, "VulkanGL20Impl instance created.");
            else System.out.println(TAG + ": VulkanGL20Impl instance created.");
        }

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

    /** Sets the VulkanWindow associated with this graphics context. Called after window construction. */
    void setWindow(VulkanWindow window) {
        this.window = window;
    }

    /** Returns the VulkanWindow associated with this graphics context. */
    public VulkanWindow getWindow() {
        return window;
    }

    /** Updates frame timing. Called once per frame before rendering, matching Lwjgl3Graphics.update(). */
    void update() {
        long time = System.nanoTime();
        if (lastFrameTime == -1) lastFrameTime = time;
        if (resetDeltaTime) {
            resetDeltaTime = false;
            deltaTime = 0;
        } else {
            deltaTime = (time - lastFrameTime) / 1000000000.0f;
        }
        lastFrameTime = time;

        if (time - frameCounterStart >= 1000000000) {
            fps = frames;
            frames = 0;
            frameCounterStart = time;
        }
        frames++;
        frameId++;
    }

    /** Creates a shell VulkanTexture placeholder for GL-emulation texture management.
     * @param glHandle The GL handle to assign to the texture.
     * @return A VulkanTexture shell instance.
     * @throws UnsupportedOperationException always — not yet implemented. */
    VulkanTexture createVulkanTextureShell(int glHandle) {
        throw new UnsupportedOperationException("createVulkanTextureShell is not yet implemented.");
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
        if (debug) Gdx.app.log(TAG, "updateFramebufferInfo called with: BB=" + backBufferWidth + " x " + backBufferHeight + ", Logical=" + logicalWidth + " x " + logicalHeight);

        // Single atomic publish — readers always see a consistent set of dimensions
        this.dimensions = new DimensionSnapshot(backBufferWidth, backBufferHeight, logicalWidth, logicalHeight);

        if (this.config != null) {
            bufferFormat = new BufferFormat(config.r, config.g, config.b, config.a, config.depth, config.stencil, config.samples, false);
        } else {
            Gdx.app.error(TAG, "Config is null during updateFramebufferInfo, cannot update bufferFormat.");
        }

        if (debug) Gdx.app.log(TAG, "Cached dimensions updated: BB=" + backBufferWidth + " x " + backBufferHeight + ", Logical=" + logicalWidth + " x " + logicalHeight);
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

    public void setCurrentSwapchainFramebuffer(long framebuffer) {
        this.currentSwapchainFramebuffer = framebuffer;
    }

    public long getCurrentSwapchainFramebuffer() {
        return currentSwapchainFramebuffer;
    }

    public void setCurrentSwapchain(VulkanSwapchain swapchain) {
        this.currentSwapchain = swapchain;
    }

    public VulkanSwapchain getCurrentSwapchain() {
        return currentSwapchain;
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
        DimensionSnapshot d = this.dimensions;
        return (config.hdpiMode == HdpiMode.Pixels) ? d.backBufferWidth : d.logicalWidth;
    }

    @Override
    public int getHeight() {
        DimensionSnapshot d = this.dimensions;
        return (config.hdpiMode == HdpiMode.Pixels) ? d.backBufferHeight : d.logicalHeight;
    }

    @Override
    public int getBackBufferWidth() {
        return dimensions.backBufferWidth;
    }

    @Override
    public int getBackBufferHeight() {
        return dimensions.backBufferHeight;
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
        return window;
    }

    private void storeCurrentWindowPositionAndDisplayMode() {
        if (window != null) {
            windowPosXBeforeFullscreen = window.getPositionX();
            windowPosYBeforeFullscreen = window.getPositionY();
            windowWidthBeforeFullscreen = window.getLogicalWidth();
            windowHeightBeforeFullscreen = window.getLogicalHeight();
        } else {
            windowPosXBeforeFullscreen = 0;
            windowPosYBeforeFullscreen = 0;
            DimensionSnapshot d = this.dimensions;
            windowWidthBeforeFullscreen = d.logicalWidth;
            windowHeightBeforeFullscreen = d.logicalHeight;
            Gdx.app.error(TAG, "Could not get window to store position/size before fullscreen.");
        }
        displayModeBeforeFullscreen = getDisplayMode(getMonitor());
    }

    @Override
    public BufferFormat getBufferFormat() {
        return bufferFormat;
    }

    @Override
    public boolean supportsExtension(String extension) {
        // glfwExtensionSupported queries OpenGL extensions which are not available in a
        // Vulkan context.  Vulkan extension support is handled via VulkanDeviceCapabilities.
        return false;
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
        if (window != null) {
            window.requestRendering();
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
        if (window == null) {
            Gdx.app.error(TAG, "Cannot set fullscreen mode, no window.");
            return false;
        }
        storeCurrentWindowPositionAndDisplayMode();
        boolean result = window.setFullscreenModeInternal(displayMode);
        updateFramebufferInfo();
        return result;
    }

    @Override
    public boolean setWindowedMode(int width, int height) {
        if (window == null) {
            Gdx.app.error(TAG, "Cannot set windowed mode, no window.");
            return false;
        }
        return window.setWindowedModeInternal(width, height);
    }

    @Override
    public void setTitle(String title) {
        if (window != null) {
            window.setTitleInternal(title);
        } else {
            GLFW.glfwSetWindowTitle(windowHandle, title);
        }
    }

    @Override
    public void setUndecorated(boolean undecorated) {
        if (window != null) {
            window.setUndecoratedInternal(undecorated);
        }
    }

    @Override
    public void setResizable(boolean resizable) {
        if (window != null) {
            window.setResizableInternal(resizable);
        }
    }

    @Override
    public void setVSync(boolean vsync) {
        if (window != null) {
            window.setVSyncInternal(vsync);
        }
    }

    @Override
    public boolean isFullscreen() {
        if (window != null) {
            return window.isFullscreenInternal();
        }
        return windowHandle != 0 && GLFW.glfwGetWindowMonitor(windowHandle) != 0;
    }

    @Override
    public Cursor newCursor(Pixmap pixmap, int xHotspot, int yHotspot) {
        if (window == null) {
            Gdx.app.error(TAG, "Cannot create cursor, no window.");
            return null;
        }
        return new VulkanCursor(window, pixmap, xHotspot, yHotspot);
    }

    @Override
    public void setCursor(Cursor cursor) {
        if (window == null) return;
        if (cursor == null) {
            GLFW.glfwSetCursor(window.getWindowHandle(), NULL);
            return;
        }
        window.setCursorInternal(cursor);
    }

    @Override
    public void setSystemCursor(SystemCursor systemCursor) {
        if (window != null) {
            window.setSystemCursorInternal(systemCursor);
        }
    }

    // --- Monitor and Display Mode Methods ---

    @Override
    public Monitor getPrimaryMonitor() {
        return VulkanApplicationConfiguration.getPrimaryMonitor();
    }

    @Override
    public Monitor getMonitor() {
        Monitor[] monitors = getMonitors();
        if (monitors == null || monitors.length == 0) return getPrimaryMonitor();
        Monitor result = monitors[0];
        if (monitors.length == 1) return result;

        GLFW.glfwGetWindowPos(windowHandle, tmpBuffer, tmpBuffer2);
        int windowX = tmpBuffer.get(0);
        int windowY = tmpBuffer2.get(0);
        GLFW.glfwGetWindowSize(windowHandle, tmpBuffer, tmpBuffer2);
        int windowWidth = tmpBuffer.get(0);
        int windowHeight = tmpBuffer2.get(0);

        int bestOverlap = 0;
        for (Monitor monitor : monitors) {
            DisplayMode mode = getDisplayMode(monitor);
            if (mode == null) continue;
            int overlap = Math.max(0, Math.min(windowX + windowWidth, monitor.virtualX + mode.width) - Math.max(windowX, monitor.virtualX))
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
        return VulkanApplicationConfiguration.getMonitors();
    }

    @Override
    public DisplayMode[] getDisplayModes() {
        return getDisplayModes(getMonitor());
    }

    @Override
    public DisplayMode[] getDisplayModes(Monitor monitor) {
        return VulkanApplicationConfiguration.getDisplayModes(monitor);
    }

    @Override
    public DisplayMode getDisplayMode() {
        return getDisplayMode(getMonitor());
    }

    @Override
    public DisplayMode getDisplayMode(Monitor monitor) {
        return VulkanApplicationConfiguration.getDisplayMode(monitor);
    }

}