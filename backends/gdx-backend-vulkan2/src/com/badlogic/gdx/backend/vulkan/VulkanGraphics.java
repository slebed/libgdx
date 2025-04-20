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

import static java.awt.SystemColor.window;

import java.nio.IntBuffer;

import com.badlogic.gdx.AbstractGraphics;
import com.badlogic.gdx.Application;
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

import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer; // Keep for currentRecordingCommandBuffer

public class VulkanGraphics extends AbstractGraphics implements Disposable {
    private final String TAG = "VulkanGraphics"; // Keep TAG

    // --- Fields to Keep ---
    private final VulkanApplicationConfiguration config; // Store config for settings like HDPI mode
    private final long primaryWindowHandle; // Store handle of the FIRST window created
    private final VulkanApplication app; // Reference to application
    private final VulkanDevice vulkanDevice; // Shared logical device wrapper
    private final long vmaAllocator; // Shared VMA allocator handle
    private VulkanPipelineManager pipelineManager; // Shared pipeline manager
    private VulkanDescriptorManager descriptorManager; // Shared descriptor manager

    // Current rendering context (set by active VulkanWindow.update)
    private VkCommandBuffer currentRecordingCommandBuffer = null;
    private long currentRenderPassHandle = VK_NULL_HANDLE;
    private int currentFrameIndex = 0;

    // Primary window state / Global info
    private final GLVersion glVersion;
    private volatile int backBufferWidth;
    private volatile int backBufferHeight;
    private volatile int logicalWidth;
    private volatile int logicalHeight;
    private volatile boolean isContinuous = true; // Default continuous rendering
    private BufferFormat bufferFormat; // Info about primary window format
    private long lastFrameTime = -1;
    private float deltaTime;
    private boolean resetDeltaTime = false;
    private long frameId;
    private long frameCounterStart = 0;
    private int frames;
    private int fps;

    // Fullscreen state (related to primary window)
    private int windowPosXBeforeFullscreen;
    private int windowPosYBeforeFullscreen;
    private int windowWidthBeforeFullscreen;
    private int windowHeightBeforeFullscreen;
    private DisplayMode displayModeBeforeFullscreen = null;

    // Utility buffers
    private final IntBuffer tmpBuffer = BufferUtils.createIntBuffer(1);
    private final IntBuffer tmpBuffer2 = BufferUtils.createIntBuffer(1);

    // Constructor - Simplified
    public VulkanGraphics(long primaryWindowHandle, VulkanApplicationConfiguration config, VulkanDevice device, long vmaAllocatorHandle) {
        this.primaryWindowHandle = primaryWindowHandle; // Store handle of the first window
        this.config = config;
        this.app = (VulkanApplication) Gdx.app; // Get app reference
        this.vulkanDevice = device;
        this.vmaAllocator = vmaAllocatorHandle;

        if (this.vulkanDevice == null || this.vmaAllocator == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VulkanDevice or VMA Allocator handle cannot be null for VulkanGraphics");
        }

        // Initialize shared managers
        this.pipelineManager = new VulkanPipelineManager(this.vulkanDevice);
        this.descriptorManager = new VulkanDescriptorManager(this.vulkanDevice.getRawDevice());

        // Get initial size info for the primary window
        updateFramebufferInfo();

        // Set GLVersion stub
        this.glVersion = new GLVersion(Application.ApplicationType.Desktop, "", "", "Vulkan");
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);

            // Use the stored windowHandle field
            GLFW.glfwGetFramebufferSize(this.primaryWindowHandle, pWidth, pHeight);
            this.backBufferWidth = pWidth.get(0);
            this.backBufferHeight = pHeight.get(0);

            // Also get logical size using the handle
            GLFW.glfwGetWindowSize(this.primaryWindowHandle, pWidth, pHeight);
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

    /**
     * Updates delta time and FPS counter. Called by VulkanApplication.loop().
     */
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
    public float getDensity() {
        return getPpiX() / 160f;
    }

    @Override
    public boolean supportsDisplayModeChange() {
        return true;
    }


    @Override
    public Monitor getMonitor() {
        // Find monitor the primary window is on
        Monitor[] monitors = getMonitors();
        if (monitors.length == 0) return getPrimaryMonitor(); // Fallback
        Monitor result = monitors[0];
        // Simplified logic: return primary if only one monitor
        if (monitors.length == 1) return result;

        // Get primary window position and size
        GLFW.glfwGetWindowPos(primaryWindowHandle, tmpBuffer, tmpBuffer2);
        int windowX = tmpBuffer.get(0);
        int windowY = tmpBuffer2.get(0);
        GLFW.glfwGetWindowSize(primaryWindowHandle, tmpBuffer, tmpBuffer2);
        int windowWidth = tmpBuffer.get(0);
        int windowHeight = tmpBuffer2.get(0);

        int overlap;
        int bestOverlap = 0;
        for (Monitor monitor : monitors) {
            DisplayMode mode = getDisplayMode(monitor); // Use helper
            overlap = Math.max(0, Math.min(windowX + windowWidth, monitor.virtualX + mode.width) - Math.max(windowX, monitor.virtualX))
                    * Math.max(0, Math.min(windowY + windowHeight, monitor.virtualY + mode.height) - Math.max(windowY, monitor.virtualY));
            if (bestOverlap < overlap) {
                bestOverlap = overlap;
                result = monitor;
            }
        }
        return result;
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
        VulkanWindow primaryWindow = app.getPrimaryWindow();
        if (primaryWindow == null || primaryWindow.getInput() == null) {
            Gdx.app.error(TAG, "Cannot set fullscreen mode, primary window or its input is null");
            return false;
        }
        primaryWindow.getInput().resetPollingStates(); // Reset input state

        // Ensure displayMode is the correct type
        if (!(displayMode instanceof VulkanDisplayMode)) {
            Gdx.app.error(TAG, "Invalid DisplayMode type provided to setFullscreenMode.");
            return false;
        }
        VulkanDisplayMode newMode = (VulkanDisplayMode) displayMode;

        if (isFullscreen()) { // Check based on primary window handle
            // Already fullscreen, potentially change mode or monitor
            VulkanDisplayMode currentMode = (VulkanDisplayMode) getDisplayMode(getMonitor()); // Get current mode for primary monitor
            if (currentMode.monitorHandle == newMode.monitorHandle && currentMode.refreshRate == newMode.refreshRate) {
                // Same monitor and refresh rate, just change resolution
                GLFW.glfwSetWindowSize(primaryWindowHandle, newMode.width, newMode.height);
            } else {
                // Different monitor or refresh rate, need to use glfwSetWindowMonitor
                GLFW.glfwSetWindowMonitor(primaryWindowHandle, newMode.monitorHandle, 0, 0, newMode.width, newMode.height, newMode.refreshRate);
            }
        } else {
            // Switching from windowed to fullscreen
            storeCurrentWindowPositionAndDisplayMode(); // Store state BEFORE changing
            GLFW.glfwSetWindowMonitor(primaryWindowHandle, newMode.monitorHandle, 0, 0, newMode.width, newMode.height, newMode.refreshRate);
        }

        updateFramebufferInfo(); // Update cached sizes

        // Re-apply VSync based on primary window's config AFTER mode change
        if (primaryWindow.getConfig() != null) {
            setVSync(primaryWindow.getConfig().vSyncEnabled);
        }

        return true;
    }

    @Override
    public boolean setWindowedMode(int width, int height) {
        VulkanWindow primaryWindow = app.getPrimaryWindow();
        if (primaryWindow == null || primaryWindow.getInput() == null) {
            Gdx.app.error(TAG, "Cannot set windowed mode, primary window or its input is null");
            return false;
        }
        primaryWindow.getInput().resetPollingStates(); // Reset input state

        if (!isFullscreen()) {
            // Already windowed, just resize and potentially center
            GridPoint2 newPos = null;
            boolean centerWindow = false;

            if (width != this.logicalWidth || height != this.logicalHeight) {
                centerWindow = true;
                newPos = VulkanApplicationConfiguration.calculateCenteredWindowPosition((VulkanMonitor) getMonitor(), width, height);
            }
            GLFW.glfwSetWindowSize(primaryWindowHandle, width, height);
            if (centerWindow && newPos != null) {
                // Use the primary window's setPosition method
                primaryWindow.setPosition(newPos.x, newPos.y);
            }
        } else {
            // Switching from fullscreen to windowed
            if (displayModeBeforeFullscreen == null) {
                // If previous state wasn't stored (shouldn't happen often), store current fullscreen state
                storeCurrentWindowPositionAndDisplayMode();
            }

            // Decide where to place the windowed mode window
            int posX, posY;
            if (width != windowWidthBeforeFullscreen || height != windowHeightBeforeFullscreen) {
                // Size changed, center it on the monitor it was fullscreen on
                Monitor monitor = (displayModeBeforeFullscreen instanceof VulkanDisplayMode)
                        ? toVulkanMonitor(((VulkanDisplayMode)displayModeBeforeFullscreen).monitorHandle)
                        : getPrimaryMonitor(); // Fallback
                GridPoint2 newPos = VulkanApplicationConfiguration.calculateCenteredWindowPosition((VulkanMonitor)monitor, width, height);
                posX = newPos.x;
                posY = newPos.y;
            } else {
                // Restore previous position and size
                posX = windowPosXBeforeFullscreen;
                posY = windowPosYBeforeFullscreen;
                // width and height are already correct
            }
            // Get refresh rate from stored mode or default
            int refreshRate = (displayModeBeforeFullscreen != null) ? displayModeBeforeFullscreen.refreshRate : GLFW.GLFW_DONT_CARE;

            // Switch back to windowed mode
            GLFW.glfwSetWindowMonitor(primaryWindowHandle, NULL, posX, posY, width, height, refreshRate);
        }

        updateFramebufferInfo(); // Update cached sizes
        return true;
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
    public void setTitle(String title) {
        GLFW.glfwSetWindowTitle(primaryWindowHandle, title);
    } // Operates on primary window

    @Override
    public void setUndecorated(boolean undecorated) {
        GLFW.glfwSetWindowAttrib(primaryWindowHandle, GLFW.GLFW_DECORATED, undecorated ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
    }

    @Override
    public void setResizable(boolean resizable) {
        GLFW.glfwSetWindowAttrib(primaryWindowHandle, GLFW.GLFW_RESIZABLE, resizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
    }

    @Override
    public void setVSync(boolean vsync) {
        if (app != null) {
            VulkanWindow primaryWindow = app.getPrimaryWindow(); // Assumes getter exists
            if (primaryWindow != null) {
                VulkanWindowConfiguration windowConfig = primaryWindow.getConfig(); // Assumes getter exists
                if (windowConfig != null && windowConfig.vSyncEnabled != vsync) {
                    Gdx.app.log(TAG, "Setting VSync for primary window (" + primaryWindow.hashCode() + ") to: " + vsync);
                    windowConfig.vSyncEnabled = vsync; // Update config
                    // Trigger recreation by setting the flag on the window instance
                    primaryWindow.framebufferResized = true; // Reuse resize flag
                    primaryWindow.requestRendering(); // Ensure update loop runs to check flag
                }
            } else {
                Gdx.app.error(TAG, "setVSync: Cannot find primary window.");
            }
        } else {
            Gdx.app.error(TAG, "setVSync: VulkanApplication reference is null.");
        }
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
    public boolean isFullscreen() {
        return GLFW.glfwGetWindowMonitor(primaryWindowHandle) != 0;
    }

    @Override
    public Cursor newCursor(Pixmap pixmap, int xHotspot, int yHotspot) {
        return new VulkanCursor(app.getPrimaryWindow(), pixmap, xHotspot, yHotspot);
    }

    @Override
    public void setCursor(Cursor cursor) {
        GLFW.glfwSetCursor(app.getPrimaryWindow().getWindowHandle(), ((VulkanCursor) cursor).glfwCursor);
    }

    @Override
    public void setSystemCursor(SystemCursor systemCursor) {
        VulkanCursor.setSystemCursor(app.getPrimaryWindow().getWindowHandle(), systemCursor);
    }

    @Override
    public void setForegroundFPS(int fps) {
        if (config != null) {
            config.foregroundFPS = fps;
        }
    }

    @Override
    public void dispose() {
        Gdx.app.log(TAG, "Disposing VulkanGraphics...");

        if (vulkanDevice != null && vulkanDevice.getRawDevice() != null) {
            Gdx.app.log(TAG, "Waiting for device idle before graphics cleanup...");
            try {
                vkDeviceWaitIdle(vulkanDevice.getRawDevice());
                Gdx.app.log(TAG, "Device idle. Proceeding with graphics cleanup...");
            } catch (Exception e) {
                Gdx.app.error(TAG, "Error waiting for device idle during graphics dispose", e);
            }
        }

        // Dispose shared managers FIRST
        if (pipelineManager != null) {
            pipelineManager.dispose();
            pipelineManager = null;
        }
        if (descriptorManager != null) {
            descriptorManager.dispose();
            descriptorManager = null;
        }

        // Clear context state
        this.currentRecordingCommandBuffer = null;
        this.currentRenderPassHandle = VK_NULL_HANDLE;

        Gdx.app.log(TAG, "VulkanGraphics cleanup finished.");
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

    @Override
    public Monitor getPrimaryMonitor() {
        return toVulkanMonitor(GLFW.glfwGetPrimaryMonitor()); // No cast needed
    }

    @Override
    public Monitor[] getMonitors() {
        PointerBuffer glfwMonitors = GLFW.glfwGetMonitors();
        if (glfwMonitors == null) return new Monitor[0];
        Monitor[] monitors = new Monitor[glfwMonitors.limit()];
        for (int i = 0; i < glfwMonitors.limit(); i++) {
            monitors[i] = toVulkanMonitor(glfwMonitors.get(i)); // Use helper
        }
        return monitors;
    }

    /**
     * Gets the GLFW window handle of the primary application window.
     *
     * @return The primary window handle.
     */
    public long getPrimaryWindowHandle() {
        return this.primaryWindowHandle;
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
}