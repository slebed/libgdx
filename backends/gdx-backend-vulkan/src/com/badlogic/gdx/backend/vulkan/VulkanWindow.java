/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backend.vulkan;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;

import java.nio.IntBuffer;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWWindowCloseCallback;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.glfw.GLFWWindowIconifyCallback;
import org.lwjgl.glfw.GLFWWindowMaximizeCallback;
import org.lwjgl.glfw.GLFWWindowRefreshCallback;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;

/**
 * Represents a single GLFW window backed by a Vulkan swapchain and render pass.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li>All public methods must be called from the <b>main (GLFW) thread</b> unless documented
 *       otherwise.</li>
 *   <li>Dimension queries ({@link #getBackBufferWidth()}, {@link #getLogicalWidth()}, etc.)
 *       call GLFW directly and are therefore main-thread-only.</li>
 *   <li>The {@code runnables} queue is drained on the main thread inside {@link #update()}.</li>
 * </ul>
 */
public class VulkanWindow implements Disposable {
    private static final String TAG = "VulkanWindow";
    private static final boolean debug = false;

    private final VulkanGraphics vulkanGraphics;
    private long windowHandle;
    final ApplicationListener listener;
    private final Array<LifecycleListener> lifecycleListeners;
    final VulkanApplication application;
    private boolean listenerInitialized = false;
    VulkanWindowListener windowListener;
    private VulkanInput input;
    private final VulkanWindowConfiguration config;
    private final Array<Runnable> runnables = new Array<>();
    private final Array<Runnable> executedRunnables = new Array<>();
    private final IntBuffer tmpBuffer;
    private final IntBuffer tmpBuffer2;
    private long surface;
    boolean iconified = false;
    boolean focused = false;
    private boolean requestRendering = false;
    private boolean framebufferResized = false;

    private VulkanSwapchain swapchain = null;
    private long renderPass = VK_NULL_HANDLE; // Cached from VulkanSwapchain
    private VulkanFrameResources frameResources;
    private VulkanWindowRenderer renderer;
    private int maxFramesInFlight = 1;

    private final GLFWWindowFocusCallback focusCallback = new GLFWWindowFocusCallback() {
        @Override
        public void invoke(long windowHandle, final boolean focused) {
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (windowListener != null) {
                        if (focused) {
                            if (application.getAppConfig().pauseWhenLostFocus) {
                                synchronized (lifecycleListeners) {
                                    for (LifecycleListener lifecycleListener : lifecycleListeners) {
                                        lifecycleListener.resume();
                                    }
                                }
                            }
                            windowListener.focusGained();
                        } else {
                            windowListener.focusLost();
                            if (application.getAppConfig().pauseWhenLostFocus) {
                                synchronized (lifecycleListeners) {
                                    for (LifecycleListener lifecycleListener : lifecycleListeners) {
                                        lifecycleListener.pause();
                                    }
                                }
                                listener.pause();
                            }
                        }
                        VulkanWindow.this.focused = focused;
                    }
                }
            });
        }
    };

    private final GLFWWindowIconifyCallback iconifyCallback = new GLFWWindowIconifyCallback() {
        @Override
        public void invoke(long windowHandle, final boolean iconified) {
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (windowListener != null) {
                        windowListener.iconified(iconified);
                    }
                    VulkanWindow.this.iconified = iconified;
                    if (iconified) {
                        if (application.getAppConfig().pauseWhenMinimized) {
                            synchronized (lifecycleListeners) {
                                for (LifecycleListener lifecycleListener : lifecycleListeners) {
                                    lifecycleListener.pause();
                                }
                            }
                            listener.pause();
                        }
                    } else {
                        if (application.getAppConfig().pauseWhenMinimized) {
                            synchronized (lifecycleListeners) {
                                for (LifecycleListener lifecycleListener : lifecycleListeners) {
                                    lifecycleListener.resume();
                                }
                            }
                            listener.resume();
                        }
                    }
                }
            });
        }
    };

    private final GLFWWindowMaximizeCallback maximizeCallback = new GLFWWindowMaximizeCallback() {
        @Override
        public void invoke(long windowHandle, final boolean maximized) {
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (windowListener != null) {
                        windowListener.maximized(maximized);
                    }
                }
            });
        }
    };

    private final GLFWWindowCloseCallback closeCallback = new GLFWWindowCloseCallback() {
        @Override
        public void invoke(final long windowHandle) {
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (debug) Gdx.app.log(TAG, "Closing window " + hashCode());
                    if (windowListener != null) {
                        if (!windowListener.closeRequested()) {
                            GLFW.glfwSetWindowShouldClose(windowHandle, false);
                        }
                    }
                }
            });
        }
    };

    private final GLFWDropCallback dropCallback = new GLFWDropCallback() {
        @Override
        public void invoke(final long windowHandle, final int count, final long names) {
            final String[] files = new String[count];
            for (int i = 0; i < count; i++) {
                files[i] = getName(names, i);
            }
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (windowListener != null) {
                        windowListener.filesDropped(files);
                    }
                }
            });
        }
    };

    private final GLFWWindowRefreshCallback refreshCallback = new GLFWWindowRefreshCallback() {
        @Override
        public void invoke(long windowHandle) {
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (windowListener != null) {
                        windowListener.refreshRequested();
                    }
                }
            });
        }
    };

    private final GLFWFramebufferSizeCallback resizeCallback = new GLFWFramebufferSizeCallback() {
        @Override
        public void invoke(long windowHandle, int width, int height) {
            if (windowHandle == VulkanWindow.this.windowHandle) {
                VulkanWindow.this.framebufferResized = true;
                if (VulkanWindow.this.renderer != null) {
                    VulkanWindow.this.renderer.setFramebufferResized(true);
                }
            }
        }
    };

    VulkanWindow(ApplicationListener listener, Array<LifecycleListener> lifecycleListeners, VulkanWindowConfiguration config, VulkanApplication application, long surfaceHandle, VulkanGraphics primaryGraphics) {
        this.listener = listener;
        this.lifecycleListeners = lifecycleListeners;
        this.windowListener = config.windowListener;
        this.config = config;
        this.application = application;
        this.surface = surfaceHandle;
        this.tmpBuffer = BufferUtils.createIntBuffer(1);
        this.tmpBuffer2 = BufferUtils.createIntBuffer(1);
        this.vulkanGraphics = primaryGraphics;

        if (this.surface == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VulkanWindow created with VK_NULL_HANDLE surface!");
        }
    }

    void create(long windowHandle) {
        if (debug) Gdx.app.log("VulkanAppInit", "Calling window.create() for handle: " + windowHandle);
        if (windowHandle == 0) {
            Gdx.app.error(TAG, "create() called with invalid window handle!");
            throw new GdxRuntimeException("Cannot create VulkanWindow with invalid handle.");
        }
        this.windowHandle = windowHandle; // Store handle

        // Register non-input GLFW callbacks (including resize)
        GLFW.glfwSetWindowFocusCallback(windowHandle, focusCallback);
        GLFW.glfwSetWindowIconifyCallback(windowHandle, iconifyCallback);
        GLFW.glfwSetWindowMaximizeCallback(windowHandle, maximizeCallback);
        GLFW.glfwSetWindowCloseCallback(windowHandle, closeCallback);
        GLFW.glfwSetDropCallback(windowHandle, dropCallback);
        GLFW.glfwSetWindowRefreshCallback(windowHandle, refreshCallback);
        GLFW.glfwSetFramebufferSizeCallback(windowHandle, resizeCallback); // Register resize callback

        // Register input callbacks via the input handler (must be set BEFORE create is called)
        if (this.input != null) {
            try {
                this.input.windowHandleChanged(this.windowHandle);
            } catch (Throwable t) {
                Gdx.app.error(TAG, "[" + this.hashCode() + "] Error calling windowHandleChanged!", t);
            }
        } else {
            // This should NOT happen if VulkanApplication order is correct
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Cannot set input callbacks during create(), input handler is null!");
        }

        //if (debug) Gdx.app.log(TAG, "[" + this.hashCode() + "] Creating Vulkan resources...");

        try {
            VulkanDevice vulkanDevice = application.getVulkanDevice();
            // Ensure VulkanSwapchain.Builder accepts VulkanWindowConfiguration
            this.swapchain = new VulkanSwapchain.Builder()
                    .device(vulkanDevice)
                    .surface(this.surface)
                    .windowHandle(this.windowHandle)
                    .configuration(this.config) // Pass VulkanWindowConfiguration
                    .build();
            //if (debug) Gdx.app.log(TAG, "[" + this.hashCode() + "] Swapchain created.");
        } catch (Exception e) {
            // Clean up surface if swapchain creation fails
            if (this.surface != VK_NULL_HANDLE && application.getVulkanInstance() != null) {
                try {
                    vkDestroySurfaceKHR(application.getVulkanInstance().getRawInstance(), this.surface, null);
                } catch (Exception cleanupEx) {
                    Gdx.app.error(TAG, "Error cleaning up surface after swapchain failure", cleanupEx);
                } finally {
                    this.surface = VK_NULL_HANDLE;
                }
            }
            throw new GdxRuntimeException("Swapchain creation failed", e);
        }

        this.maxFramesInFlight = application.getAppConfig().getMaxFramesInFlight();
        if (this.maxFramesInFlight < 1) { // Add validation just in case config had an invalid value
            Gdx.app.error(TAG, "Configuration maxFramesInFlight is invalid (" + this.maxFramesInFlight + "), defaulting to 1.");
            this.maxFramesInFlight = 1;
        }

        try {
            // Use render pass and framebuffers from VulkanSwapchain (no duplication)
            this.renderPass = this.swapchain.getRenderPass();
            if (this.renderPass == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Swapchain render pass is null after creation!");
            }

            if (this.vulkanGraphics != null) {
                this.vulkanGraphics.setMainSwapchainRenderPass(this.renderPass);
            } else {
                Gdx.app.error(TAG, "VulkanGraphics instance is null in VulkanWindow.create() after render pass creation.");
            }

            // Create frame resources (command pool, command buffers, sync objects)
            int graphicsQueueFamily = application.getGraphicsQueueFamily();
            this.frameResources = new VulkanFrameResources(application.getVulkanDevice(), graphicsQueueFamily, maxFramesInFlight);

            // Create renderer
            this.renderer = new VulkanWindowRenderer(application.getVulkanDevice(), this.swapchain,
                    this.frameResources, this.vulkanGraphics, this.renderPass, this.maxFramesInFlight, this.debug);
        } catch (Exception e) {
            disposeVulkanResources();
            throw new GdxRuntimeException("Failed during Vulkan resource creation for window " + windowHandle, e);
        }

        if (windowListener != null) {
            windowListener.created(this);
        }
        if (debug) Gdx.app.log("VulkanAppInit", "window.create() finished.");
    }

    /**
     * @return the {@link ApplicationListener} associated with this window
     **/
    public ApplicationListener getListener() {
        return listener;
    }

    /**
     * @return the {@link VulkanWindowListener} set on this window
     **/
    public VulkanWindowListener getWindowListener() {
        return windowListener;
    }

    public void setWindowListener(VulkanWindowListener listener) {
        this.windowListener = listener;
    }

    public void setViewportForVkCommands(com.badlogic.gdx.utils.viewport.Viewport viewport) {
        if (renderer != null) {
            renderer.setViewportForVkCommands(viewport);
        }
        if (Gdx.app != null && debug) {
            if (viewport != null) {
                Gdx.app.debug(TAG, "setViewportForVkCommands: Viewport SET for window " + this.hashCode());
            } else {
                Gdx.app.debug(TAG, "setViewportForVkCommands: Viewport CLEARED for window " + this.hashCode());
            }
        }
    }

    /**
     * Post a {@link Runnable} to this window's event queue. Use this if you access statics like {@link Gdx#graphics} in your
     * runnable instead of {@link Application#postRunnable(Runnable)}.
     */
    public void postRunnable(Runnable runnable) {
        synchronized (runnables) {
            runnables.add(runnable);
        }
    }

    /**
     * Sets the position of the window in logical coordinates. All monitors span a virtual surface together. The coordinates are
     * relative to the first monitor in the virtual surface.
     **/
    public void setPosition(int x, int y) {
        if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) return;
        GLFW.glfwSetWindowPos(windowHandle, x, y);
    }

    /**
     * @return the window position in logical coordinates. All monitors span a virtual surface together. The coordinates are
     * relative to the first monitor in the virtual surface.
     **/
    public int getPositionX() {
        GLFW.glfwGetWindowPos(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer.get(0);
    }

    /**
     * @return the window position in logical coordinates. All monitors span a virtual surface together. The coordinates are
     * relative to the first monitor in the virtual surface.
     **/
    public int getPositionY() {
        GLFW.glfwGetWindowPos(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer2.get(0);
    }

    /**
     * Sets the visibility of the window. Invisible windows will still call their {@link ApplicationListener}
     */
    public void setVisible(boolean visible) {
        if (debug) System.out.println("[VulkanWindow] setVisible called with: " + visible);
        if (visible) {
            if (debug) System.out.println("[VulkanWindow] Attempting glfwShowWindow...");
            GLFW.glfwShowWindow(windowHandle);
            if (debug) System.out.println("[VulkanWindow] glfwShowWindow call finished.");
        } else {
            GLFW.glfwHideWindow(windowHandle);
            if (debug) System.out.println("[VulkanWindow] glfwHideWindow call finished.");
        }
    }

    /**
     * Closes this window and pauses and disposes the associated {@link ApplicationListener}.
     */
    public void closeWindow() {
        GLFW.glfwSetWindowShouldClose(windowHandle, true);
    }

    /**
     * Minimizes (iconifies) the window. Iconified windows do not call their {@link ApplicationListener} until the window is
     * restored.
     */
    public void iconifyWindow() {
        GLFW.glfwIconifyWindow(windowHandle);
    }

    /**
     * Whether the window is iconfieid
     */
    public boolean isIconified() {
        return iconified;
    }

    /**
     * De-minimizes (de-iconifies) and de-maximizes the window.
     */
    public void restoreWindow() {
        GLFW.glfwRestoreWindow(windowHandle);
    }

    /**
     * Maximizes the window.
     */
    public void maximizeWindow() {
        GLFW.glfwMaximizeWindow(windowHandle);
    }

    /**
     * Brings the window to front and sets input focus. The window should already be visible and not iconified.
     */
    public void focusWindow() {
        GLFW.glfwFocusWindow(windowHandle);
    }

    public boolean isFocused() {
        return focused;
    }

    /**
     * Sets the icon that will be used in the window's title bar. Has no effect in macOS, which doesn't use window icons.
     *
     * @param image One or more images. The one closest to the system's desired size will be scaled. Good sizes include 16x16,
     *              32x32 and 48x48. Pixmap format {@link Pixmap.Format#RGBA8888 RGBA8888} is preferred so
     *              the images will not have to be copied and converted. The chosen image is copied, and the provided Pixmaps are not
     *              disposed.
     */
    public void setIcon(Pixmap... image) {
        setIcon(windowHandle, image);
    }

    static void setIcon(long windowHandle, String[] imagePaths, Files.FileType imageFileType) {
        if (SharedLibraryLoader.os == Os.MacOsX) return;

        Pixmap[] pixmaps = new Pixmap[imagePaths.length];
        for (int i = 0; i < imagePaths.length; i++) {
            pixmaps[i] = new Pixmap(Gdx.files.getFileHandle(imagePaths[i], imageFileType));
        }

        setIcon(windowHandle, pixmaps);

        for (Pixmap pixmap : pixmaps) {
            pixmap.dispose();
        }
    }

    static void setIcon(long windowHandle, Pixmap[] images) {
        if (SharedLibraryLoader.os == Os.MacOsX) return;
        if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) return;

        GLFWImage.Buffer buffer = GLFWImage.malloc(images.length);
        Pixmap[] tmpPixmaps = new Pixmap[images.length];

        for (int i = 0; i < images.length; i++) {
            Pixmap pixmap = images[i];

            if (pixmap.getFormat() != Pixmap.Format.RGBA8888) {
                Pixmap rgba = new Pixmap(pixmap.getWidth(), pixmap.getHeight(), Pixmap.Format.RGBA8888);
                rgba.setBlending(Pixmap.Blending.None);
                rgba.drawPixmap(pixmap, 0, 0);
                tmpPixmaps[i] = rgba;
                pixmap = rgba;
            }

            GLFWImage icon = GLFWImage.malloc();
            icon.set(pixmap.getWidth(), pixmap.getHeight(), pixmap.getPixels());
            buffer.put(icon);

            icon.free();
        }

        buffer.position(0);
        GLFW.glfwSetWindowIcon(windowHandle, buffer);

        buffer.free();
        for (Pixmap pixmap : tmpPixmaps) {
            if (pixmap != null) {
                pixmap.dispose();
            }
        }
    }

    public void resizeFrameBuffer(boolean b) {
        framebufferResized = b;
        if (renderer != null) {
            renderer.setFramebufferResized(b);
        }
    }

    public void setTitle(CharSequence title) {
        GLFW.glfwSetWindowTitle(windowHandle, title);
    }

    /**
     * Sets minimum and maximum size limits for the window. If the window is full screen or not resizable, these limits are
     * ignored. Use -1 to indicate an unrestricted dimension.
     */
    public void setSizeLimits(int minWidth, int minHeight, int maxWidth, int maxHeight) {
        setSizeLimits(windowHandle, minWidth, minHeight, maxWidth, maxHeight);
    }

    static void setSizeLimits(long windowHandle, int minWidth, int minHeight, int maxWidth, int maxHeight) {
        GLFW.glfwSetWindowSizeLimits(windowHandle, minWidth > -1 ? minWidth : GLFW.GLFW_DONT_CARE,
                minHeight > -1 ? minHeight : GLFW.GLFW_DONT_CARE, maxWidth > -1 ? maxWidth : GLFW.GLFW_DONT_CARE,
                maxHeight > -1 ? maxHeight : GLFW.GLFW_DONT_CARE);
    }

    VulkanGraphics getGraphics() {
        return (VulkanGraphics) Gdx.graphics;
    }

    VulkanInput getInput() {
        return input;
    }

    public long getWindowHandle() {
        return windowHandle;
    }

    /**
     * @return The width of the window's client area in logical coordinates.
     */
    public int getLogicalWidth() {
        GLFW.glfwGetWindowSize(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer.get(0);
    }

    /**
     * @return The height of the window's client area in logical coordinates.
     */
    public int getLogicalHeight() {
        GLFW.glfwGetWindowSize(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer2.get(0);
    }

    /**
     * @return The width of the window's framebuffer in physical pixels.
     */
    public int getBackBufferWidth() {
        GLFW.glfwGetFramebufferSize(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer.get(0);
    }

    /**
     * @return The height of the window's framebuffer in physical pixels.
     */
    public int getBackBufferHeight() {
        GLFW.glfwGetFramebufferSize(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer2.get(0);
    }

    /**
     * Updates the window's state, processes input and runnables,
     * and performs the Vulkan rendering sequence for this window's frame.
     *
     * @return true if rendering occurred, false otherwise.
     */
    boolean update() {
        //if (debug) Gdx.app.log(TAG, "[" + this.hashCode() + "] update() called. listenerInitialized=" + this.listenerInitialized + ", listener=" + this.listener); // <-- ADD THIS

        if (!listenerInitialized && listener != null) {
            ensureListenerCreatedAndResized(); // Call helper method
        }

        if (!iconified && this.input != null) {
            this.input.update();      // Process events (touchDown, keyTyped etc.)
            this.input.prepareNext(); // Reset polling states (isKeyPressed etc.)
        } else if (!iconified) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Cannot update input, this.input is null!");
        }

        synchronized (runnables) {
            if (runnables.size > 0) {
                executedRunnables.clear();
                executedRunnables.addAll(runnables);
                runnables.clear();
            }
        }
        for (Runnable runnable : executedRunnables) {
            try {
                runnable.run();
            } catch (Throwable t) {
                Gdx.app.error(TAG, "Exception in runnable", t);
            }
        }
        // Determine if rendering is needed based on runnables or continuous rendering setting
        VulkanGraphics gfx = getGraphics(); // Get graphics instance early
        boolean shouldRender = executedRunnables.size > 0 || (gfx != null && gfx.isContinuousRendering());
        boolean continuous = (gfx != null && gfx.isContinuousRendering());
        executedRunnables.clear();
        //if (debug) Gdx.app.log(TAG, "update() called. shouldRender=" + shouldRender + ", continuous=" + continuous + ", requestRendering=" + requestRendering + ", iconified=" + iconified);
        boolean renderingRequested = false;
        synchronized (this) {
            renderingRequested = requestRendering;
            shouldRender |= renderingRequested && !iconified;
            requestRendering = false; // Consume the request
        }

        if (renderer != null && renderer.isFramebufferResized()) {
            shouldRender = true; // Force render if resized to handle recreation
        }

        // --- Early Exit Checks ---
        if (!shouldRender || iconified || swapchain == null || gfx == null || renderer == null) {
            return false; // No rendering needed or possible this iteration
        }

        return renderer.renderFrame(this, gfx, listener, config);
    }

    private void ensureListenerCreatedAndResized() {

        // Check if initialization is needed and possible
        if (!listenerInitialized && listener != null) {

            try {
                application.setCurrentWindow(this); // Inform application of the current window context

                listener.create();

                int width = Gdx.graphics.getWidth();
                int height = Gdx.graphics.getHeight();

                if (width > 0 && height > 0) {
                    //if (debug) Gdx.app.log(TAG, "[" + this.hashCode() + "] Calling initial listener.resize(" + width + ", " + height + ")");
                    listener.resize(width, height);
                    //if (debug) Gdx.app.log(TAG, "[" + this.hashCode() + "] Initial listener.resize() completed.");
                } else {
                    // Log error if dimensions aren't valid yet, might happen if called too early?
                    Gdx.app.error(TAG, "[" + this.hashCode() + "] Invalid dimensions (" + width + "x" + height + ") obtained for initial listener resize.");
                    // Proceed to mark as initialized anyway to avoid repeated create() calls.
                }

            } catch (Throwable t) {
                Gdx.app.error(TAG, "[" + this.hashCode() + "] !!! EXCEPTION during listener.create() or initial listener.resize() !!!", t);
                throw new GdxRuntimeException("Listener create/resize failed for window " + windowHandle, t); // Rethrow
            } finally {
                // Crucially, mark the listener as initialized AFTER the try block attempts create/resize,
                // preventing this block from running again for this window instance.
                listenerInitialized = true;
                if (debug) Gdx.app.log(TAG, "[" + this.hashCode() + "] Listener marked as initialized (listenerInitialized = true).");
            }
        } else {
            // Condition failed, log the reason and skip initialization steps
            if (debug)
                Gdx.app.log(TAG, "[" + this.hashCode() + "] Condition FAILED (listenerInitialized=" + listenerInitialized + ", listenerIsNull=" + (listener == null) + "), skipping create/resize.");
        }
    }

    void requestRendering() {
        synchronized (this) {
            this.requestRendering = true;
        }
    }

    boolean shouldClose() {
        return GLFW.glfwWindowShouldClose(windowHandle);
    }

    VulkanApplicationConfiguration getApplicationConfig() {
        return application.getAppConfig();
    }

    VulkanWindowConfiguration getConfig() {
        return config;
    }

    boolean isListenerInitialized() {
        return listenerInitialized;
    }

    void makeCurrent() {
        if (this.input != null) {
            Gdx.input = this.input;
        } else {
            // This case should ideally not happen if input is created properly
            Gdx.app.error(TAG, "makeCurrent() called on window " + windowHandle + " but its input handler is null!");
            Gdx.input = null; // Explicitly null out global static
        }
    }

    @Override
    public void dispose() {
        final boolean useGdxLog = (Gdx.app != null && Gdx.app.getApplicationLogger() != null);
        logInfo(TAG, "[" + this.hashCode() + "] Disposing window " + windowHandle, useGdxLog);

        VkDevice device = application.getVulkanDevice().getLogicalDevice();

        // Wait for idle before destroying window-specific resources
        if (device != null) {
            logInfo(TAG, "[" + this.hashCode() + "] Waiting for device idle before window resource cleanup...", useGdxLog);
            vkDeviceWaitIdle(device);
            logInfo(TAG, "[" + this.hashCode() + "] Device idle.", useGdxLog);
        }

        // This prevents issues if callback objects are freed before GLFW stops using them.
        if (windowHandle != NULL) {
            logInfo(TAG, "[" + this.hashCode() + "] Nullifying GLFW callbacks.", useGdxLog);
            GLFW.glfwSetWindowFocusCallback(windowHandle, null);
            GLFW.glfwSetWindowIconifyCallback(windowHandle, null);
            GLFW.glfwSetWindowMaximizeCallback(windowHandle, null);
            GLFW.glfwSetWindowCloseCallback(windowHandle, null);
            GLFW.glfwSetDropCallback(windowHandle, null);
            GLFW.glfwSetWindowRefreshCallback(windowHandle, null);
            // Nullify input callbacks too
            GLFW.glfwSetKeyCallback(windowHandle, null);
            GLFW.glfwSetCharCallback(windowHandle, null);
            GLFW.glfwSetScrollCallback(windowHandle, null);
            GLFW.glfwSetCursorPosCallback(windowHandle, null);
            GLFW.glfwSetMouseButtonCallback(windowHandle, null);
        }

        // Dispose Vulkan Resources (Helper Method)
        disposeVulkanResources(); // Disposes swapchain, surface, pool, sync etc.

        if (vulkanGraphics != null) {
            vulkanGraphics.dispose(); // Dispose this window's graphics instance
            logInfo(TAG, "[" + this.hashCode() + "] VulkanGraphics instance disposed.", useGdxLog);
        }

        // Dispose Input Handler (which frees its callback objects)
        if (input != null) {
            input.dispose();
            logInfo(TAG, "[" + this.hashCode() + "] Input disposed.", useGdxLog);
            input = null;
        }

        // Dispose Cursors
        VulkanCursor.dispose(this);
        logInfo(TAG, "[" + this.hashCode() + "] Cursors disposed.", useGdxLog);

        // Destroy GLFW Window Handle
        if (windowHandle != NULL) {
            GLFW.glfwDestroyWindow(windowHandle);
            logInfo(TAG, "[" + this.hashCode() + "] GLFW window handle " + windowHandle + " destroyed.", useGdxLog);
            windowHandle = NULL;
        }

        // Free Window Callback Instances (Input ones freed by input.dispose())
        focusCallback.free();
        iconifyCallback.free();
        maximizeCallback.free();
        closeCallback.free();
        dropCallback.free();
        refreshCallback.free();
        resizeCallback.free();
        logInfo(TAG, "[" + this.hashCode() + "] Window callback instances freed.", useGdxLog);

        logInfo(TAG, "[" + this.hashCode() + "] dispose() finished.", useGdxLog);
    }

    private void disposeVulkanResources() {
        final boolean useGdxLog = (Gdx.app != null && Gdx.app.getApplicationLogger() != null);
        VkInstance instance = null;
        if (application != null && application.getVulkanDevice() != null) {
            instance = application.getVulkanInstance().getRawInstance();
        }

        // Dispose renderer (clears references, doesn't own resources)
        if (renderer != null) {
            renderer.dispose();
            renderer = null;
            logInfo(TAG, "[" + this.hashCode() + "] Renderer disposed.", useGdxLog);
        }

        // Dispose frame resources (sync objects, command pool)
        if (frameResources != null) {
            frameResources.dispose();
            frameResources = null;
            logInfo(TAG, "[" + this.hashCode() + "] Frame resources disposed.", useGdxLog);
        }

        // Render pass and framebuffers are owned by VulkanSwapchain — just clear cached handle
        renderPass = VK_NULL_HANDLE;

        // --- Dispose Step 2 Resources ---
        if (swapchain != null) {
            swapchain.dispose();
            logInfo(TAG, "[" + this.hashCode() + "] Swapchain disposed.", useGdxLog);
            swapchain = null;
        }
        if (instance != null && surface != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance, surface, null);
            logInfo(TAG, "[" + this.hashCode() + "] Surface disposed.", useGdxLog);
            surface = VK_NULL_HANDLE;
        }
        // --- End Step 2 Resources ---
    }

    // Helper method for consistent logging during cleanup
    private void logInfo(String tag, String message, boolean useGdx) {
        if (useGdx) {
            //if (debug) Gdx.app.log(tag, message);
        } else {
            if (debug) System.out.println("[" + tag + "] " + message);
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Long.hashCode(windowHandle);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        VulkanWindow other = (VulkanWindow) obj;
        if (windowHandle != other.windowHandle) return false;
        return true;
    }

    public void flash() {
        GLFW.glfwRequestWindowAttention(windowHandle);
    }

    public void setInputHandler(VulkanInput inputInstance) {
        this.input = inputInstance;
    }

    boolean isFullscreenInternal() {
        return GLFW.glfwGetWindowMonitor(this.windowHandle) != NULL;
    }

    boolean setFullscreenModeInternal(Graphics.DisplayMode displayMode) {
        if (input != null) input.resetPollingStates();

        if (!(displayMode instanceof VulkanGraphics.VulkanDisplayMode)) {
            Gdx.app.error(TAG, "[" + windowHandle + "] Invalid DisplayMode type provided to setFullscreenModeInternal.");
            return false;
        }
        VulkanGraphics.VulkanDisplayMode newMode = (VulkanGraphics.VulkanDisplayMode) displayMode;

        if (isFullscreenInternal()) {
            // Already fullscreen, potentially change mode or monitor
            VulkanGraphics.VulkanDisplayMode currentMode = (VulkanGraphics.VulkanDisplayMode) vulkanGraphics.getDisplayMode(vulkanGraphics.getMonitor());
            if (currentMode.monitorHandle == newMode.monitorHandle && currentMode.refreshRate == newMode.refreshRate) {
                GLFW.glfwSetWindowSize(this.windowHandle, newMode.width, newMode.height);
            } else {
                GLFW.glfwSetWindowMonitor(this.windowHandle, newMode.monitorHandle, 0, 0, newMode.width, newMode.height, newMode.refreshRate);
            }
        } else {
            // Switching from windowed to fullscreen
            // TODO: Need to properly implement storing/restoring previous window state
            // storeCurrentWindowPositionAndDisplayMode(); // Logic needs to exist here or be passed
            //if (debug) Gdx.app.log(TAG, "[" + windowHandle + "] Storing window state before fullscreen (TODO: Implement!)");
            GLFW.glfwSetWindowMonitor(this.windowHandle, newMode.monitorHandle, 0, 0, newMode.width, newMode.height, newMode.refreshRate);
        }
        // Swapchain recreation will be triggered by resize event / framebufferResized flag
        return true;
    }

    /** Called by VulkanApplication.setWindowedMode() */
    boolean setWindowedModeInternal(int width, int height) {
        if (input != null) input.resetPollingStates();

        if (!isFullscreenInternal()) {
            // Already windowed, just resize
            // TODO: Implement centering logic if desired, potentially using stored previous position?
            //if (debug) Gdx.app.log(TAG, "[" + windowHandle + "] Setting window size to " + width + "x" + height + " (TODO: Centering?)");
            GLFW.glfwSetWindowSize(this.windowHandle, width, height);
        } else {
            // Switching from fullscreen to windowed
            // TODO: Restore previous window position/size/mode properly
            //if (debug) Gdx.app.log(TAG, "[" + windowHandle + "] Restoring windowed mode at " + width + "x" + height + " (TODO: Use stored state!)");
            int posX = 100; // Placeholder - use stored previous X
            int posY = 100; // Placeholder - use stored previous Y
            int refreshRate = GLFW.GLFW_DONT_CARE; // Placeholder

            GLFW.glfwSetWindowMonitor(this.windowHandle, NULL, posX, posY, width, height, refreshRate);
        }
        // Swapchain recreation will be triggered by resize event / framebufferResized flag
        return true;
    }

    void setTitleInternal(CharSequence title) {
        GLFW.glfwSetWindowTitle(this.windowHandle, title);
    }

    void setUndecoratedInternal(boolean undecorated) {
        GLFW.glfwSetWindowAttrib(this.windowHandle, GLFW.GLFW_DECORATED, undecorated ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
    }

    void setResizableInternal(boolean resizable) {
        GLFW.glfwSetWindowAttrib(this.windowHandle, GLFW.GLFW_RESIZABLE, resizable ? GLFW.GLFW_TRUE : GLFW.GLFW_TRUE);
    }

    void setVSyncInternal(boolean vsync) {
        // VSync is controlled by the swapchain present mode.
        // We need to change the desired mode in the window config and trigger a swapchain recreate.
        VulkanApplicationConfiguration.SwapchainPresentMode targetMode =
                vsync ? VulkanApplicationConfiguration.SwapchainPresentMode.FIFO
                        : VulkanApplicationConfiguration.SwapchainPresentMode.MAILBOX; // Or IMMEDIATE

        if (config.presentMode != targetMode) {
            //if (debug) Gdx.app.log(TAG, "[" + windowHandle + "] setVSyncInternal(" + vsync + ") changing presentMode to: " + targetMode);
            config.presentMode = targetMode; // Update this window's config
            this.framebufferResized = true; // Flag for swapchain recreation
            if (renderer != null) renderer.setFramebufferResized(true);
            this.requestRendering();         // Ensure render loop checks the flag
        }
    }

    void setCursorInternal(Cursor cursor) {
        // Assumes VulkanCursor holds the glfwCursor handle
        if (cursor instanceof VulkanCursor) {
            GLFW.glfwSetCursor(this.windowHandle, ((VulkanCursor) cursor).glfwCursor);
        } else if (cursor == null) {
            GLFW.glfwSetCursor(this.windowHandle, NULL);
        } else {
            Gdx.app.error(TAG, "Cannot set cursor: Invalid cursor type provided.");
        }
    }

    void setSystemCursorInternal(Cursor.SystemCursor systemCursor) {
        VulkanCursor.setSystemCursor(this.windowHandle, systemCursor);
    }

    // --- Optional Helpers for requestRendering flag ---
    boolean needsRendering() {
        return requestRendering;
    }

    void clearNeedsRendering() {
        requestRendering = false;
    }
}
