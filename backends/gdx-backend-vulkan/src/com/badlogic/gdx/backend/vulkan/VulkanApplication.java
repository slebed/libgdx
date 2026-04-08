package com.badlogic.gdx.backend.vulkan;

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

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;

import java.io.File;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backend.vulkan.audio.OpenALLwjgl3Audio;
import com.badlogic.gdx.backend.vulkan.audio.VulkanAudio;
import com.badlogic.gdx.backend.vulkan.audio.mock.MockAudio;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import com.badlogic.gdx.utils.SnapshotArray;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;

/**
 * Main Vulkan application entry point, analogous to Lwjgl3Application.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li>The GLFW event loop and all {@code ApplicationListener} callbacks run on the <b>main thread</b>.</li>
 *   <li>Window-surface map ({@code windowSurfaces}) uses {@code ConcurrentHashMap} for safe
 *       cross-thread access.</li>
 * </ul>
 */
public class VulkanApplication implements VulkanApplicationBase {
    private static final String TAG = "VulkanApplication";
    private static final boolean debug = true; // Enabled debug for more verbose logging during init

    private static GLFWErrorCallback errorCallback;
    private final Array<Runnable> runnables = new Array<>();
    private final Array<Runnable> executedRunnables = new Array<>();
    private final Array<LifecycleListener> lifecycleListeners = new Array<>();
    private Files files;
    private Net net;
    private final ObjectMap<String, Preferences> preferences = new ObjectMap<>();
    private final VulkanClipboard clipboard;
    private final VulkanApplicationConfiguration appConfig;
    private final Map<Long, Long> windowSurfaces = new ConcurrentHashMap<>();
    private final ApplicationListener mainListener;

    private final long primaryWindowHandle;

    private VulkanInstance vulkanInstance;
    private VkPhysicalDevice physicalDevice;
    private VulkanDevice vulkanDevice;
    private VulkanDeviceCapabilities deviceCapabilities; // Field for storing device capabilities
    private VulkanAudio audio;
    private VkDebugUtilsMessengerCallbackEXT debugCallbackInstance = null;
    private Integer graphicsQueueFamily;
    private Integer presentQueueFamily;
    private ApplicationLogger applicationLogger;

    private long primarySurface = VK_NULL_HANDLE; // For the first window
    private long debugMessenger;
    private long vmaAllocator = VK_NULL_HANDLE;
    private int logLevel = LOG_DEBUG;

    private volatile boolean running = true;
    private volatile VulkanWindow currentWindow;

    private long lastFrameTime = -1; // Initialize for first frame delta calculation
    private long frameCounterStart = 0; // Initialize for FPS calculation
    boolean resetDeltaTime;
    float deltaTime;
    private int frames;
    int frameId;
    int fps;

    private final Map<Long, VulkanGraphics> windowGraphicsMap = new HashMap<>();
    private VulkanPipelineManager pipelineManager;
    private VulkanShaderManager shaderManager;
    private VulkanDescriptorManager descriptorManager;
    final Array<VulkanWindow> windows = new Array<>();
    private SnapshotArray<VulkanWindow> currentWindowsSnapshot;

    static void initializeGlfw() {
        if (errorCallback == null) {
            VulkanNativesLoader.load();
            errorCallback = GLFWErrorCallback.createPrint(VulkanApplicationConfiguration.errorStream);
            GLFW.glfwSetErrorCallback(errorCallback);
            if (SharedLibraryLoader.os == Os.MacOsX)
                GLFW.glfwInitHint(GLFW.GLFW_ANGLE_PLATFORM_TYPE, GLFW.GLFW_ANGLE_PLATFORM_TYPE_METAL);
            GLFW.glfwInitHint(GLFW.GLFW_JOYSTICK_HAT_BUTTONS, GLFW.GLFW_FALSE);
            if (!GLFW.glfwInit()) {
                throw new GdxRuntimeException("Unable to initialize GLFW");
            }
        }
    }

    public VulkanApplication(ApplicationListener listener) {
        this(listener, new VulkanApplicationConfiguration());
    }

    public VulkanApplication(ApplicationListener listener, VulkanApplicationConfiguration appConfig) {
        initializeGlfw();
        setApplicationLogger(new VulkanApplicationLogger());

        this.mainListener = listener;

        this.appConfig = VulkanApplicationConfiguration.copy(appConfig);
        if (this.appConfig.title == null) this.appConfig.title = listener.getClass().getSimpleName();

        Gdx.app = this;

        long windowHandle = createGlfwWindow(this.appConfig, 0);
        this.primaryWindowHandle = windowHandle;

        // Bootstrap Vulkan: instance, device, VMA, debug messenger
        VulkanBootstrap.BootstrapResult bootstrap = new VulkanBootstrap().initialize(windowHandle, this.appConfig);
        this.vulkanInstance = bootstrap.vulkanInstance;
        this.physicalDevice = bootstrap.physicalDevice;
        this.vulkanDevice = bootstrap.vulkanDevice;
        this.deviceCapabilities = bootstrap.deviceCapabilities;
        this.vmaAllocator = bootstrap.vmaAllocator;
        this.graphicsQueueFamily = bootstrap.graphicsQueueFamily;
        this.presentQueueFamily = bootstrap.presentQueueFamily;
        this.debugMessenger = bootstrap.debugMessenger;
        this.debugCallbackInstance = bootstrap.debugCallback;
        this.primarySurface = bootstrap.primarySurface;

        initializePipeline();

        initializeDescriptor();

        Gdx.audio = initializeAudio();
        Gdx.files = initializeFiles();
        Gdx.net = initializeNet();

        VulkanGraphics primaryGraphics = initializeGraphics(windowHandle);
        Gdx.graphics = primaryGraphics;

        windowGraphicsMap.put(windowHandle, primaryGraphics);

        VulkanWindow window = new VulkanWindow(
                listener,
                lifecycleListeners,
                this.appConfig,
                this,
                this.primarySurface,
                primaryGraphics
        );

        VulkanInput createdInput = initializeInput(window);
        Gdx.input = createdInput;

        window.setInputHandler(createdInput);

        this.clipboard = new VulkanClipboard();

        Gdx.gl20 = Gdx.graphics.getGL20();
        Gdx.gl = Gdx.gl20; // Gdx.gl is usually an alias for Gdx.gl20

        // Verification logging:
        if (Gdx.gl20 == null) {
            String errorMsg = "FATAL: Gdx.gl20 is NULL after assignment from Gdx.graphics.getGL20()! Check VulkanGraphics.getGL20() implementation and ensure VulkanGL20Impl is created.";
            Gdx.app.error(TAG, errorMsg); // Gdx.app should be available here
            throw new GdxRuntimeException(errorMsg);
        } else {
            Gdx.app.log(TAG, "Gdx.gl20 initialized successfully with: " + Gdx.gl20.getClass().getName());
        }
        if (Gdx.gl == null) { // Should be redundant if Gdx.gl20 is not null and Gdx.gl = Gdx.gl20
            Gdx.app.error(TAG, "FATAL: Gdx.gl is NULL after assignment!");
            throw new GdxRuntimeException("Gdx.gl is NULL after assignment!");
        } else {
            Gdx.app.log(TAG, "Gdx.gl initialized successfully with: " + Gdx.gl.getClass().getName());
        }

        window.create(windowHandle);

        window.setVisible(this.appConfig.initialVisible);
        windows.add(window);

        currentWindowsSnapshot = new SnapshotArray<>(windows);

        runMainLoop();
    }

    private VulkanInput initializeInput(VulkanWindow window) {
        VulkanInput createdInput = createInput(window);
        if (createdInput != null) {
            if (debug) Gdx.app.log("VulkanAppInit", "Gdx.input assigned. Instance Hash: " + createdInput.hashCode());
        } else {
            if (debug) Gdx.app.log("VulkanAppInit", "Gdx.input assignment resulted in NULL!");
            throw new GdxRuntimeException("Failed to create VulkanInput handler");
        }
        return createdInput;
    }

    private void initializeDescriptor() {
        // deviceCapabilities should be initialized by initializeVulkanCore before this is called
        if (this.deviceCapabilities == null) {
            throw new GdxRuntimeException("DeviceCapabilities not initialized before initializeDescriptor!");
        }
        // Use the already queried limits from deviceCapabilities
        VkPhysicalDeviceLimits limits = this.deviceCapabilities.getLimits();
        if (this.vulkanDevice == null || this.vulkanDevice.getLogicalDevice() == null) { // Check logical device from wrapper
            throw new GdxRuntimeException("VulkanDevice or its logical device is null in initializeDescriptor!");
        }
        this.descriptorManager = new VulkanDescriptorManager(this.vulkanDevice.getLogicalDevice(), limits, this.appConfig.getMaxFramesInFlight(), this.deviceCapabilities);
    }

    private void initializePipeline() {
        if (this.vulkanDevice == null || this.vulkanDevice.getLogicalDevice() == null) { // Check logical device from wrapper
            throw new GdxRuntimeException("VulkanDevice or its logical device is null in initializePipeline!");
        }
        this.shaderManager = new VulkanShaderManager(this.vulkanDevice.getLogicalDevice());
        this.pipelineManager = new VulkanPipelineManager(this.vulkanDevice,this.shaderManager);
    }

    private VulkanGraphics initializeGraphics(long windowHandle) {
        return new VulkanGraphics(
                windowHandle,
                0L,
                this.appConfig,
                this,
                this.vulkanDevice,
                this.vmaAllocator,
                this.pipelineManager,
                this.descriptorManager
        );
    }

    private Net initializeNet() {
        this.net = new VulkanNet(this.appConfig);
        return net;
    }

    private Files initializeFiles() {
        this.files = new VulkanFiles();
        return files;
    }

    private VulkanAudio initializeAudio() {
        if (!appConfig.disableAudio) {
            try {
                this.audio = createAudio(appConfig);
            } catch (Throwable t) {
                log(TAG, "Couldn't initialize audio, disabling audio", t);
                this.audio = new MockAudio();
            }
        } else {
            this.audio = new MockAudio();
        }
        return audio;
    }

    public void runMainLoop() {
        try {
            loop();
            if (debug) Gdx.app.log(TAG, "Main loop finished. Waiting for GPU idle before cleanup...");
            VulkanDevice currentVkDevice = getVulkanDevice();
            if (currentVkDevice != null && currentVkDevice.getLogicalDevice() != null) { // Use getLogicalDevice()
                vkDeviceWaitIdle(currentVkDevice.getLogicalDevice());
                if (debug) Gdx.app.log(TAG, "GPU is idle.");
            } else {
                Gdx.app.error(TAG, "Cannot wait for device idle during cleanup, VulkanDevice or its logical device unavailable!");
            }
            cleanupWindows();
        } catch (Throwable t) {
            if (t instanceof RuntimeException)
                throw (RuntimeException) t;
            else
                throw new GdxRuntimeException(t);
        } finally {
            cleanup();
        }
    }

    public void loop() {
        Array<VulkanWindow> closedWindows = new Array<>();
        long frameCount = 0;

        if (lastFrameTime == -1) {
            lastFrameTime = System.nanoTime();
        }
        if (frameCounterStart == 0) {
            frameCounterStart = System.nanoTime();
        }

        this.currentWindow = null;
        Gdx.graphics = null;
        Gdx.input = null;

        boolean fpsUpdatedThisFrame = false;

        while (running && windows.size > 0) {
            //if (debug) Gdx.app.log(TAG, "LOOP_THREAD: Loop running on thread: " + Thread.currentThread().getName());
            frameCount++;

            fpsUpdatedThisFrame = false;
            long time = System.nanoTime();
            deltaTime = (time - lastFrameTime) / 1000000000.0f;
            lastFrameTime = time;
            if (time - frameCounterStart >= 1000000000) {
                fps = frames;
                frames = 0;
                frameCounterStart = time;
                fpsUpdatedThisFrame = true;
            }
            frames++;
            frameId++;

            if (audio != null) {
                audio.update();
            }

            GLFW.glfwPollEvents();

            synchronized (runnables) {
                if (!runnables.isEmpty()) {
                    executedRunnables.addAll(runnables);
                    runnables.clear();

                    for (Runnable runnable : executedRunnables) {
                        try {
                            runnable.run();
                        } catch (Throwable t) {
                            Gdx.app.error(TAG, "Exception occurred in runnable execution", t);
                        }
                    }
                    executedRunnables.clear();

                    synchronized (windows) {
                        for (VulkanWindow w : windows) {
                            if (w != null) w.requestRendering();
                        }
                    }
                }
            }

            boolean haveWindowsRendered = false;
            closedWindows.clear();

            //if (debug) Gdx.app.log(TAG, "LOOP: === Starting Direct Iteration ===");
            synchronized (windows) {
                int currentSize = windows.size;
                //if (debug) Gdx.app.log(TAG, "LOOP: Direct Iteration. Size = " + currentSize);

                for (int i = 0; i < currentSize; i++) {
                    VulkanWindow window = windows.get(i);
                    if (window == null) {
                        //Gdx.app.error(TAG, "LOOP: DIRECT Encountered null window in list at index " + i);
                        continue;
                    }

                    long currentHandle = window.getWindowHandle();
                    if (fpsUpdatedThisFrame) {
                        String baseTitle = window.getConfig().title;
                        if (baseTitle == null) baseTitle = "GdxVulkan";
                        @SuppressWarnings("DefaultLocale") String newTitle = String.format("%s | FPS: %d | Delta: %.2f ms", baseTitle, fps, deltaTime * 1000f);
                        GLFW.glfwSetWindowTitle(currentHandle, newTitle);
                    }

                    VulkanGraphics windowGraphics = windowGraphicsMap.get(currentHandle);
                    VulkanInput windowInput = window.getInput();

                    if (windowGraphics == null || windowInput == null) {
                        Gdx.app.error(TAG, "Skipping update for window " + currentHandle + " due to missing Graphics (" + windowGraphics + ") or Input (" + windowInput + ") instance.");
                        continue;
                    }

                    Gdx.graphics = windowGraphics;
                    Gdx.input = windowInput;
                    this.currentWindow = window;

                    boolean continuous = windowGraphics.isContinuousRendering();
                    boolean requested = window.needsRendering();
                    window.clearNeedsRendering();
                    boolean iconified = window.isIconified();
                    boolean needsRender = continuous || requested;
                    if (debug) {
                        // Gdx.app.log(TAG, "LOOP: DIRECT Window " + currentHandle + " check: continuous=" + continuous + ", requested=" + requested + ", needsRender=" + needsRender + ", isIconified=" + iconified);
                    }

                    boolean windowRendered = false;
                    if (needsRender && !iconified) {
                        //if (debug) Gdx.app.log(TAG, "LOOP: DIRECT >>> Calling update() for window " + currentHandle + " <<<");
                        synchronized (lifecycleListeners) {
                            try {
                                windowRendered = window.update();
                                haveWindowsRendered |= windowRendered;
                            } catch (Throwable t) {
                                Gdx.app.error(TAG, "Exception occurred during window update/render for " + currentHandle, t);
                            }
                        }
                        //if (debug) Gdx.app.log(TAG, "LOOP: DIRECT <<< Returned from update() for window " + currentHandle + " >>>");
                    } else {
                        //  if (debug)                           Gdx.app.log(TAG, "LOOP: DIRECT --- Skipping update() for window " + currentHandle + " --- (Reason: needsRender=" + needsRender + ", isIconified=" + iconified + ")");
                    }

                    if (window.shouldClose()) {
                        if (!closedWindows.contains(window, true)) {
                            closedWindows.add(window);
                        }
                    }
                }
            }

            if (!closedWindows.isEmpty()) {
                processClosedWindows(closedWindows);
            }

            if (!haveWindowsRendered && !windows.isEmpty()) {
                try {
                    Thread.sleep(Math.max(1, 1000 / appConfig.idleFPS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processClosedWindows(Array<VulkanWindow> closedWindows) {
        if (debug) Gdx.app.log(TAG, "Processing " + closedWindows.size + " windows marked for closure.");
        synchronized (windows) {
            for (VulkanWindow closedWindow : closedWindows) {
                long closedHandle = closedWindow.getWindowHandle();
                if (debug) Gdx.app.log(TAG, "Closing window with handle: " + closedHandle);
                try {
                    ApplicationListener listener = closedWindow.getListener();
                    if (listener != null) {
                        if (debug) Gdx.app.log(TAG, "Calling listener.dispose() for closing window: " + closedHandle);
                        try {
                            listener.dispose();
                        } catch (Throwable t_listener) {
                            Gdx.app.error(TAG, "Exception during listener dispose for closing window: " + closedHandle, t_listener);
                        }
                        if (debug) Gdx.app.log(TAG, "listener.dispose() finished for window: " + closedHandle);
                    } else {
                        if (debug) Gdx.app.log(TAG, "Window " + closedHandle + " had null listener, skipping listener dispose.");
                    }
                    boolean removed = windows.removeValue(closedWindow, true);
                    windowGraphicsMap.remove(closedHandle);
                    if (!removed) {
                        Gdx.app.error(TAG, "Attempted to remove window " + closedHandle + " but it wasn't found in 'windows' list during closure.");
                    }
                    closedWindow.dispose();
                    if (debug) Gdx.app.log(TAG, "Disposed window with handle: " + closedHandle);
                } catch (Throwable t) {
                    Gdx.app.error(TAG, "Exception occurred during window dispose for handle " + closedHandle, t);
                }
            }

            if (windows.isEmpty() && !lifecycleListeners.isEmpty()) {
                if (debug) Gdx.app.log(TAG, "Last window closed, disposing lifecycle listeners.");
                for (int j = lifecycleListeners.size - 1; j >= 0; j--) {
                    LifecycleListener l = lifecycleListeners.get(j);
                    try {
                        l.pause();
                        l.dispose();
                    } catch (Throwable t) {
                        Gdx.app.error(TAG, "Exception during lifecycle listener dispose", t);
                    }
                }
                lifecycleListeners.clear();
            }
        }

        boolean currentWindowStillValid;
        synchronized (windows) {
            currentWindowStillValid = windows.contains(this.currentWindow, true);
        }

        if (!currentWindowStillValid) {
            synchronized (windows) {
                this.currentWindow = !windows.isEmpty() ? windows.first() : null;
            }
            if (this.currentWindow != null) {
                VulkanGraphics newCurrentGraphics = windowGraphicsMap.get(this.currentWindow.getWindowHandle());
                VulkanInput newCurrentInput = this.currentWindow.getInput();
                Gdx.graphics = newCurrentGraphics;
                Gdx.input = newCurrentInput;
                if (debug)
                    Gdx.app.log(TAG, "Processing closed windows: Reset current window to handle " + this.currentWindow.getWindowHandle());
            } else {
                Gdx.graphics = null;
                Gdx.input = null;
                if (debug) Gdx.app.log(TAG, "Processing closed windows: No windows left, context nulled.");
            }
        }
    }

    protected void cleanupWindows() {
        synchronized (lifecycleListeners) {
            for (LifecycleListener lifecycleListener : lifecycleListeners) {
                lifecycleListener.pause();
                lifecycleListener.dispose();
            }
        }
        if (debug) Gdx.app.log(TAG, "cleanupWindows: Disposing listeners for remaining windows (count=" + windows.size + ")...");
        synchronized (windows) {
            Array<VulkanWindow> windowsToClean = new Array<>(windows);
            for (VulkanWindow window : windowsToClean) {
                ApplicationListener listener = window.getListener();
                if (listener != null) {
                    if (debug) Gdx.app.log(TAG, "cleanupWindows: Calling listener.dispose() for remaining window: " + window.getWindowHandle());
                    try {
                        listener.dispose();
                    } catch (Throwable t_listener) {
                        Gdx.app.error(TAG, "cleanupWindows: Exception during listener dispose for window: " + window.getWindowHandle(), t_listener);
                    }
                } else {
                    if (debug) Gdx.app.log(TAG, "cleanupWindows: Window " + window.getWindowHandle() + " had null listener.");
                }
            }
            if (debug) Gdx.app.log(TAG, "cleanupWindows: Disposing VulkanWindow objects for remaining windows...");
            for (VulkanWindow window : windowsToClean) {
                try {
                    window.dispose();
                } catch (Throwable t_window) {
                    Gdx.app.error(TAG, "cleanupWindows: Exception during VulkanWindow dispose for window: " + window.getWindowHandle(), t_window);
                }
            }
            windows.clear();
        }
        if (debug) Gdx.app.log(TAG, "cleanupWindows: Finished.");
    }

    protected void cleanup() {
        if (debug) Gdx.app.log(TAG, "Cleanup check: currentWindow hash=" + (currentWindow == null ? "null" : currentWindow.hashCode()));
        if (currentWindow != null) {
            if (debug) Gdx.app.log(TAG, "Cleanup check: currentWindow.getListener() is null? " + (currentWindow.getListener() == null));
        }

        if (debug) Gdx.app.log(TAG, "Cleanup check: mainListener is null? " + (mainListener == null));
        if (mainListener != null) {
            if (debug) Gdx.app.log(TAG, "Cleanup: Attempting mainListener pause/dispose...");
            try {
                if (debug) Gdx.app.log(TAG, "Cleanup: Calling mainListener.pause()...");
                mainListener.pause();
                if (debug) Gdx.app.log(TAG, "Cleanup: mainListener.pause() finished.");

                if (debug) Gdx.app.log(TAG, "Cleanup: Calling mainListener.dispose()...");
                mainListener.dispose();
                if (debug) Gdx.app.log(TAG, "Cleanup: mainListener.dispose() finished.");

                if (debug) Gdx.app.log(TAG, "ApplicationListener disposed.");
            } catch (Throwable t) {
                Gdx.app.error(TAG, "Cleanup: Exception during mainListener pause/dispose!", t);
            }
        } else {
            if (debug) Gdx.app.log(TAG, "Cleanup: Skipping mainListener pause/dispose - mainListener field was null.");
        }

        if (debug) Gdx.app.log(TAG, "Cleanup: Before cleanupWindows()...");
        cleanupWindows();
        if (debug) Gdx.app.log(TAG, "Cleanup: After cleanupWindows()...");

        if (audio != null) {
            audio.dispose();
            if (debug) Gdx.app.log(TAG, "Audio disposed."); // Changed from error to log
            audio = null;
        }

        VulkanDevice currentVkDevice = getVulkanDevice(); // Get the device instance
        if (currentVkDevice != null && currentVkDevice.getLogicalDevice() != null) { // Check the LWJGL VkDevice
            if (debug) Gdx.app.log(TAG, "Cleanup: Waiting for device idle before destroying managers and device...");
            vkDeviceWaitIdle(currentVkDevice.getLogicalDevice());
            if (debug) Gdx.app.log(TAG, "Cleanup: Device idle.");
        }


        if (pipelineManager != null) {
            pipelineManager.dispose();
            pipelineManager = null;
            if (debug) Gdx.app.log(TAG, "PipelineManager disposed.");
        }
        if (descriptorManager != null) {
            descriptorManager.dispose();
            descriptorManager = null;
            if (debug) Gdx.app.log(TAG, "DescriptorManager disposed.");
        }

        if (vmaAllocator != VK_NULL_HANDLE) {
            if (debug) Gdx.app.log(TAG, "Destroying VMA Allocator...");
            VulkanBootstrap.destroyVmaAllocator(vmaAllocator);
            vmaAllocator = VK_NULL_HANDLE;
            if (debug) Gdx.app.log(TAG, "VMA Allocator destroyed.");
        }

        if (vulkanDevice != null) {
            if (debug) Gdx.app.log(TAG, "Cleaning up VulkanDevice..."); // Changed from System.out
            vulkanDevice.dispose();
            if (debug) Gdx.app.log(TAG, "VulkanDevice cleanup finished."); // Changed from System.out
            vulkanDevice = null;
        }

        if (debugMessenger != VK_NULL_HANDLE && vulkanInstance != null && vulkanInstance.getRawInstance() != null) {
            VulkanBootstrap.destroyDebugMessenger(vulkanInstance.getRawInstance(), debugMessenger);
            if (debug) Gdx.app.log(TAG, "Debug messenger destroyed.");
            debugMessenger = VK_NULL_HANDLE;
        }

        if (debugCallbackInstance != null) {
            debugCallbackInstance.free();
            if (debug) Gdx.app.log(TAG, "Debug callback freed."); // Changed from System.out
            debugCallbackInstance = null;
        }

        if (vulkanInstance != null) {
            vulkanInstance.cleanup();
            if (debug) Gdx.app.log(TAG, "Vulkan instance cleaned up."); // Changed from System.out
            vulkanInstance = null;
        }

        if (errorCallback != null) {
            errorCallback.free();
            errorCallback = null;
            if (debug) Gdx.app.log(TAG, "GLFW error callback freed."); // Changed from System.out
        }

        GLFW.glfwTerminate();
        if (debug) Gdx.app.log(TAG, "GLFW terminated."); // Changed from System.out

        VulkanCursor.disposeSystemCursors();

        if (debug) Gdx.app.log(TAG, "Cleanup finished."); // Changed from System.out

        Gdx.app = null;
        Gdx.graphics = null;
        Gdx.input = null;
        Gdx.audio = null;
        Gdx.files = null;
        Gdx.net = null;
    }

    public VulkanInstance getVulkanInstance() {
        return vulkanInstance;
    }

    public VulkanDevice getVkDevice() {
        return vulkanDevice;
    }

    public long getSurface(long windowHandle) {
        return windowSurfaces.getOrDefault(windowHandle, VK10.VK_NULL_HANDLE);
    }

    @Override
    public ApplicationListener getApplicationListener() {
        return currentWindow != null ? currentWindow.getListener() : null; // Added null check
    }

    @Override
    public Graphics getGraphics() {
        return currentWindow != null ? currentWindow.getGraphics() : null; // Added null check
    }

    @Override
    public Audio getAudio() {
        return audio;
    }

    @Override
    public Input getInput() {
        return currentWindow != null ? currentWindow.getInput() : null; // Added null check
    }

    @Override
    public Files getFiles() {
        return files;
    }

    @Override
    public Net getNet() {
        return net;
    }

    @Override
    public void debug(String tag, String message) {
        if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message);
    }

    @Override
    public void debug(String tag, String message, Throwable exception) {
        if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message, exception);
    }

    @Override
    public void log(String tag, String message) {
        if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message);
    }

    @Override
    public void log(String tag, String message, Throwable exception) {
        if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message, exception);
    }

    @Override
    public void error(String tag, String message) {
        if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message);
    }

    @Override
    public void error(String tag, String message, Throwable exception) {
        if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message, exception);
    }

    @Override
    public void setLogLevel(int logLevel) {
        this.logLevel = logLevel;
    }

    @Override
    public int getLogLevel() {
        return logLevel;
    }

    @Override
    public void setApplicationLogger(ApplicationLogger applicationLogger) {
        this.applicationLogger = applicationLogger;
    }

    @Override
    public ApplicationLogger getApplicationLogger() {
        return applicationLogger;
    }

    @Override
    public ApplicationType getType() {
        return ApplicationType.Desktop;
    }

    @Override
    public int getVersion() {
        return 0; // Or some actual version
    }

    @Override
    public long getJavaHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    @Override
    public long getNativeHeap() {
        return getJavaHeap(); // This is not accurate for native heap, but a placeholder
    }

    @Override
    public Preferences getPreferences(String name) {
        if (preferences.containsKey(name)) {
            return preferences.get(name);
        } else {
            Preferences prefs = new VulkanPreferences(
                    new VulkanFileHandle(new File(appConfig.preferencesDirectory, name), appConfig.preferencesFileType));
            preferences.put(name, prefs);
            return prefs;
        }
    }

    @Override
    public Clipboard getClipboard() {
        return clipboard;
    }

    @Override
    public void postRunnable(Runnable runnable) {
        synchronized (runnables) {
            runnables.add(runnable);
        }
    }

    @Override
    public void exit() {
        running = false;
    }

    @Override
    public void addLifecycleListener(LifecycleListener listener) {
        synchronized (lifecycleListeners) {
            lifecycleListeners.add(listener);
        }
        if (debug) Gdx.app.log(TAG, "Application listener added"); // Added debug flag
    }

    @Override
    public void removeLifecycleListener(LifecycleListener listener) {
        synchronized (lifecycleListeners) {
            lifecycleListeners.removeValue(listener, true);
        }
        if (debug) Gdx.app.log(TAG, "Application listener removed"); // Added debug flag
    }

    @Override
    public VulkanAudio createAudio(VulkanApplicationConfiguration config) {
        return new OpenALLwjgl3Audio(config.audioDeviceSimultaneousSources, config.audioDeviceBufferCount,
                config.audioDeviceBufferSize);
    }

    @Override
    public VulkanInput createInput(VulkanWindow window) {
        return new DefaultVulkanInput(window);
    }

    public VkPhysicalDevice getPhysicalDevice() {
        if (physicalDevice == null) {
            throw new IllegalStateException("Physical Device not selected or initialized.");
        }
        return physicalDevice;
    }

    public VulkanDevice getVulkanDevice() {
        if (vulkanDevice == null) {
            throw new IllegalStateException("Vulkan Logical Device not created.");
        }
        return vulkanDevice;
    }

    public long getVmaAllocator() {
        if (vmaAllocator == VK_NULL_HANDLE) {
            throw new IllegalStateException("VMA Allocator not created or already destroyed.");
        }
        return vmaAllocator;
    }

    public void registerSurface(long windowHandle, long surfaceHandle) {
        if (surfaceHandle != VK10.VK_NULL_HANDLE) {
            windowSurfaces.put(windowHandle, surfaceHandle);
            if (debug) Gdx.app.log(TAG, "Registered surface " + surfaceHandle + " for window " + windowHandle);
        } else {
            Gdx.app.error(TAG, "Attempted to register VK_NULL_HANDLE surface for window " + windowHandle);
        }
    }

    public int getGraphicsQueueFamily() {
        if (graphicsQueueFamily == null) {
            throw new IllegalStateException("Graphics Queue Family index not found or initialized.");
        }
        return graphicsQueueFamily;
    }

    public int getPresentQueueFamily() {
        if (presentQueueFamily == null) {
            throw new IllegalStateException("Present Queue Family index not found or initialized.");
        }
        return presentQueueFamily;
    }

    public VulkanWindow getPrimaryWindow() {
        return windows.size > 0 ? windows.first() : null;
    }

    public VulkanWindow getWindow(long handle) {
        for (VulkanWindow window : windows) {
            if (window.getWindowHandle() == handle) {
                return window;
            }
        }
        return null;
    }

    public VulkanApplicationConfiguration getAppConfig() {
        return appConfig;
    }

    public VulkanWindow newWindow(ApplicationListener listener, VulkanWindowConfiguration config) {
        long newWindowHandle = createGlfwWindow(config, this.primaryWindowHandle);
        if (newWindowHandle == 0) {
            throw new GdxRuntimeException("Failed to create GLFW window for newWindow");
        }

        long newSurfaceHandle = VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkInstance instance = this.vulkanInstance.getRawInstance();
            LongBuffer pSurface = stack.mallocLong(1);
            int err = GLFWVulkan.glfwCreateWindowSurface(instance, newWindowHandle, null, pSurface);
            vkCheck(err, "Failed to create window surface for new window");
            newSurfaceHandle = pSurface.get(0);
            if (debug) Gdx.app.log(TAG, "Created surface " + newSurfaceHandle + " for new window " + newWindowHandle);
        } catch (Exception e) {
            GLFW.glfwDestroyWindow(newWindowHandle);
            throw new GdxRuntimeException("Failed to create Vulkan surface for new window", e);
        }

        VulkanGraphics newGraphicsInstance = initializeGraphics(newWindowHandle);
        windowGraphicsMap.put(newWindowHandle, newGraphicsInstance);

        // initializeWindow(newWindowHandle); // This method was empty, can be removed or implemented
        VulkanWindow newWindow = new VulkanWindow(
                listener,
                lifecycleListeners,
                config,
                this,
                newSurfaceHandle,
                newGraphicsInstance
        );

        VulkanInput newInput = new DefaultVulkanInput(newWindow);
        newWindow.setInputHandler(newInput);

        // Removed Thread.sleep(2000) as it's unusual for production code

        try {
            newWindow.create(newWindowHandle);
            if (debug) Gdx.app.log(TAG, "newWindow.create() finished for handle: " + newWindowHandle);
        } catch (Throwable t) {
            Gdx.app.error(TAG, "Failed to create new window's resources", t);
            if (newSurfaceHandle != VK_NULL_HANDLE && this.vulkanInstance != null && this.vulkanInstance.getRawInstance() != null) { // Added raw instance null check
                KHRSurface.vkDestroySurfaceKHR(this.vulkanInstance.getRawInstance(), newSurfaceHandle, null);
            }
            GLFW.glfwDestroyWindow(newWindowHandle);
            throw new GdxRuntimeException("Failed to create new window's resources", t);
        }

        newWindow.setVisible(config.initialVisible);
        if (debug) Gdx.app.log(TAG, "NEW_WINDOW_THREAD: Adding window on thread: " + Thread.currentThread().getName());
        synchronized (windows) {
            long newHandle = newWindow.getWindowHandle();
            if (debug) Gdx.app.log(TAG, "CREATE_WINDOW: About to add window handle " + newHandle + " to main window collection.");
            windows.add(newWindow);
            if (debug) Gdx.app.log(TAG, "CREATE_WINDOW: Added window handle " + newHandle + ". Collection size now: " + windows.size);
        }
        return newWindow;
    }

    private long createGlfwWindow(VulkanWindowConfiguration config, long sharedContextWindow) {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, config.windowResizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_MAXIMIZED, config.windowMaximized ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_AUTO_ICONIFY, config.autoIconify ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        // Removed redundant !GLFW.glfwInit() check as it's in initializeGlfw()

        if (debug) Gdx.app.log(TAG, "Creating Vulkan-compatible GLFW window..."); // Changed from System.out
        String windowTitle = (config.title != null) ? config.title : "GdxVulkan Window";
        long window = GLFW.glfwCreateWindow(config.windowWidth, config.windowHeight, windowTitle, 0, 0); // Removed sharedContextWindow for Vulkan

        if (window == 0) {
            throw new GdxRuntimeException("Failed to create GLFW window"); // Changed from RuntimeException
        }

        if (debug) Gdx.app.log(TAG, "Vulkan-compatible window created. Window handle: " + window); // Changed from System.out
        if (config.windowX != -1 && config.windowY != -1) {
            if (debug) Gdx.app.log(TAG, "Setting window position from config: (" + config.windowX + ", " + config.windowY + ")"); // Changed from System.out
            GLFW.glfwSetWindowPos(window, config.windowX, config.windowY);
        } else {
            // Centering logic (ensure Gdx.graphics is available or handle null)
            Graphics currentGraphics = Gdx.graphics; // Use local var for null check
            if (currentGraphics != null) {
                Graphics.Monitor primary = currentGraphics.getPrimaryMonitor();
                Graphics.DisplayMode mode = currentGraphics.getDisplayMode(primary);
                if (mode != null) { // Added null check for mode
                    GLFW.glfwSetWindowPos(window, (mode.width - config.windowWidth) / 2 + primary.virtualX, (mode.height - config.windowHeight) / 2 + primary.virtualY);
                } else {
                    if (debug) Gdx.app.log(TAG, "Primary monitor display mode is null, cannot center window.");
                }
            } else {
                if (debug) Gdx.app.log(TAG, "Gdx.graphics not available for centering, letting window manager decide placement."); // Changed from System.out
            }
        }
        return window;
    }


    public VulkanWindow getCurrentWindow() {
        return this.currentWindow;
    }

    public VulkanPipelineManager getPipelineManager() {
        return pipelineManager;
    }

    public VulkanDescriptorManager getDescriptorManager() {
        return descriptorManager;
    }

    public VulkanShaderManager getShaderManager() {
        return shaderManager;
    }

    public void setCurrentWindow(VulkanWindow window) {
        this.currentWindow = window;
    }
}
