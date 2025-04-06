package com.badlogic.gdx.backend.vulkan;

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

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.util.vma.Vma.vmaDestroyAllocator;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;
import static org.lwjgl.vulkan.VK10.VK_FALSE;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_TRUE;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties;

import java.io.File;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.backend.vulkan.audio.OpenALLwjgl3Audio;
import com.badlogic.gdx.backend.vulkan.audio.VulkanAudio;

import com.badlogic.gdx.utils.*;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkPhysicalDevice;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backend.vulkan.audio.mock.MockAudio;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.SharedLibraryLoader;

import org.lwjgl.vulkan.VkLayerProperties;

import java.util.Set;
import java.util.HashSet;
import java.util.Collections;

import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;

import static org.lwjgl.vulkan.KHRSurface.*; // For vkGetPhysicalDeviceSurfaceSupportKHR
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.KHRSwapchain.*; // For VK_KHR_SWAPCHAIN_EXTENSION_NAME

import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkExtensionProperties;

import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

public class VulkanApplication implements VulkanApplicationBase {

    final Array<VulkanWindow> windows = new Array<VulkanWindow>();
    private final long primaryWindowHandle;
    private VulkanInstance vulkanInstance;
    private volatile VulkanWindow currentWindow;
    private VulkanAudio audio;
    private final Files files;
    private final Net net;
    private final ObjectMap<String, Preferences> preferences = new ObjectMap<String, Preferences>();
    private int logLevel = LOG_INFO;
    private ApplicationLogger applicationLogger;
    private volatile boolean running = true;
    private final Array<Runnable> runnables = new Array<Runnable>();
    private final Array<Runnable> executedRunnables = new Array<Runnable>();
    private final Array<LifecycleListener> lifecycleListeners = new Array<LifecycleListener>();
    private static GLFWErrorCallback errorCallback;

    private static Callback glDebugCallback;

    private VkPhysicalDevice physicalDevice; // You'll need logic to select this
    private VulkanDevice vulkanDevice;
    // Maybe a map for surfaces if supporting multiple windows: Map<Long, Long> windowSurfaces = new HashMap<>();
    private long primarySurface = VK_NULL_HANDLE; // For the first window
    private final VulkanClipboard clipboard;
    private final VulkanApplicationConfiguration config;
    private long window;
    private VulkanWindow gdxWindow;
    private long debugMessenger;
    private boolean enableValidationLayers = true;

    private static final Set<String> DEVICE_EXTENSIONS = Collections.singleton(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    private static final List<String> DESIRED_VALIDATION_LAYERS = Collections.singletonList("VK_LAYER_KHRONOS_validation");

    private long vmaAllocator = VK_NULL_HANDLE;

    private VkDebugUtilsMessengerCallbackEXT debugCallbackInstance = null;

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

    private void initializeVulkanCore(long windowHandle) {
        try (MemoryStack stack = stackPush()) {
            // --- Create Instance ---
            List<String> requiredExtensions = getRequiredInstanceExtensions(stack);
            List<String> validationLayers = getValidationLayers(); // Get based on config

            this.vulkanInstance = new VulkanInstance.Builder()
                    .setApplicationName(config.title)
                    .setRequiredExtensions(requiredExtensions)
                    .setValidationLayers(validationLayers)
                    .build();
            System.out.println("Vulkan Instance created.");

            // Setup Debug Messenger (using vulkanInstance.getRawInstance())
            if (enableValidationLayers) { // Assuming this field exists
                setupDebugMessenger(stack);
                System.out.println("Debug Messenger created.");
            }

            // --- Create Surface ---
            LongBuffer pSurface = stack.mallocLong(1);
            int err = GLFWVulkan.glfwCreateWindowSurface(vulkanInstance.getRawInstance(), windowHandle, null, pSurface);
            vkCheck(err, "Failed to create window surface");
            this.primarySurface = pSurface.get(0); // Store the surface handle
            // If using map: windowSurfaces.put(windowHandle, this.primarySurface);
            System.out.println("Window Surface created.");

            // --- Select Physical Device ---
            this.physicalDevice = selectPhysicalDevice(stack); // Implement this selection logic
            System.out.println("Physical Device selected.");

            // --- Create Logical Device (VulkanDevice wrapper) ---
            QueueFamilyIndices indices = findQueueFamilies(physicalDevice, stack); // Implement this
            this.vulkanDevice = new VulkanDevice.Builder()
                    .setPhysicalDevice(physicalDevice)
                    .setQueueFamilyIndex(indices.graphicsFamily) // Assuming graphics==present for now
                    // Add required device extensions (like swapchain) to builder if not hardcoded
                    .build(); // Builder creates device, queues, command pool
            System.out.println("Logical Device created.");

            if (this.vulkanInstance != null && this.vulkanDevice != null) {
                createVmaAllocator(this.vulkanInstance, this.vulkanDevice);
                // createVmaAllocator should log success or throw on failure internally
            } else {
                // Should not happen if prior steps succeeded, but good defensive check
                throw new GdxRuntimeException("Cannot create VMA Allocator: Instance or Device is null!");
            }

        } catch (Exception e) {
            // Handle core init failure - cleanup might be needed
            throw new GdxRuntimeException("Failed core Vulkan initialization", e);
        }
    }

    private List<String> convertPointerBufferToStringList(PointerBuffer buffer) {
        if (buffer == null) return null;
        List<String> list = new ArrayList<>(buffer.remaining());
        for (int i = buffer.position(); i < buffer.limit(); i++) {
            list.add(MemoryUtil.memUTF8(buffer.get(i)));
        }
        return list;
    }

    public VulkanApplication(ApplicationListener listener) {
        this(listener, new VulkanApplicationConfiguration());
    }

    public VulkanApplication(ApplicationListener listener, VulkanApplicationConfiguration config) {
        // 1. Basic Setup
        initializeGlfw();
        setApplicationLogger(new VulkanApplicationLogger());
        this.config = VulkanApplicationConfiguration.copy(config);
        if (config.title == null) config.title = listener.getClass().getSimpleName();
        Gdx.app = this;

        // 2. Create GLFW Window Handle
        long windowHandle = createGlfwWindow(config, 0);

        // 3. Core Vulkan Initialization (Instance, Surface, Device, **VMA Allocator**)
        initializeVulkanCore(windowHandle); // This MUST initialize this.vulkanDevice and this.vmaAllocator

        // --- Check that core Vulkan objects were initialized ---
        if (this.vulkanDevice == null || this.vulkanDevice.getRawDevice() == null || this.vulkanDevice.getRawDevice().address() == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VulkanDevice was not initialized by initializeVulkanCore!");
        }
        if (this.vmaAllocator == VK_NULL_HANDLE) { // Assuming vmaAllocator field exists in VulkanApplication
            throw new GdxRuntimeException("VMA Allocator was not initialized by initializeVulkanCore!");
        }
        // -------------------------------------------------------


        // 4. Initialize Gdx Subsystems (Audio, Files, etc.)
        if (!config.disableAudio) {
            this.audio = createAudio(config);
        } else {
            this.audio = new MockAudio();
        }
        Gdx.audio = audio;
        this.files = Gdx.files = createFiles();
        this.net = Gdx.net = new VulkanNet(config);
        this.clipboard = new VulkanClipboard();

        // ***** MODIFIED VulkanGraphics INSTANTIATION *****
        VulkanGraphics graphics = new VulkanGraphics(
                windowHandle,
                config,
                this.vulkanDevice,  // Pass the VulkanDevice object
                this.vmaAllocator   // Pass the VMA Allocator handle
        );
        // ************************************************
        Gdx.graphics = graphics; // Set the static field

        // 5. Create VulkanWindow and VulkanInput
        VulkanWindow window = new VulkanWindow(listener, lifecycleListeners, config, this);
        window.create(windowHandle);
        window.setVisible(config.initialVisible);

        windows.add(window);
        this.currentWindow = window;
        this.primaryWindowHandle = windowHandle;

        VulkanInput input = createInput(window);
        Gdx.input = input;

        // 6. Initialize Swapchain etc. using the Graphics instance
        try {
            Gdx.app.log("VulkanApplication", "Initializing graphics resources...");
            graphics.initializeSwapchainAndResources(); // Call method on the graphics instance
            Gdx.app.log("VulkanApplication", "Graphics resources initialized.");
        } catch (Throwable e) {
            System.err.println("FATAL: Exception occurred during graphics resource initialization");
            e.printStackTrace();
            cleanup();
            System.exit(-1);
        }

        // 7. Call Listener's create()
        try {
            Gdx.app.log("VulkanApplication", "Calling listener.create()...");
            listener.create();
            Gdx.app.log("VulkanApplication", "listener.create() completed.");
        } catch (Throwable e) {
            throw new GdxRuntimeException("Exception occurred in ApplicationListener.create()", e);
        }

        // 8. Start Main Loop
        Gdx.app.log("VulkanApplication", "Starting main loop...");
        runMainLoop();
    }

    public void runMainLoop() {
        try {
            loop();
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
        long frameCount = 0; // Add counter
        while (running && windows.size > 0) {
            // FIXME put it on a separate thread
            if (audio != null) {
                audio.update();
            }

            if (++frameCount % 120 == 0) { // Print every ~2 seconds
                System.out.println("[VulkanApplication] loop() iteration: " + frameCount);
            }

            boolean haveWindowsRendered = false;
            closedWindows.clear();
            int targetFramerate = -2;
            for (VulkanWindow window : windows) {
                if (currentWindow != window) {
                    window.makeCurrent();
                    currentWindow = window;
                }
                if (targetFramerate == -2) targetFramerate = window.getConfig().foregroundFPS;
                synchronized (lifecycleListeners) {
                    haveWindowsRendered |= window.update();
                }
                if (window.shouldClose()) {
                    closedWindows.add(window);
                }
            }
            GLFW.glfwPollEvents();

            boolean shouldRequestRendering;
            synchronized (runnables) {
                shouldRequestRendering = runnables.size > 0;
                executedRunnables.clear();
                executedRunnables.addAll(runnables);
                runnables.clear();
            }
            for (Runnable runnable : executedRunnables) {
                runnable.run();
            }
            if (shouldRequestRendering) {
                // Must follow Runnables execution so changes done by Runnables are reflected
                // in the following render.
                for (VulkanWindow window : windows) {
                    if (!window.getGraphics().isContinuousRendering()) window.requestRendering();
                }
            }

            for (VulkanWindow closedWindow : closedWindows) {
                if (windows.size == 1) {
                    // Lifecycle listener methods have to be called before ApplicationListener methods. The
                    // application will be disposed when _all_ windows have been disposed, which is the case,
                    // when there is only 1 window left, which is in the process of being disposed.
                    for (int i = lifecycleListeners.size - 1; i >= 0; i--) {
                        LifecycleListener l = lifecycleListeners.get(i);
                        l.pause();
                        l.dispose();
                    }
                    lifecycleListeners.clear();
                }
                closedWindow.dispose();

                windows.removeValue(closedWindow, false);
            }

            if (!haveWindowsRendered) {
                // Sleep a few milliseconds in case no rendering was requested
                // with continuous rendering disabled.
                try {
                    Thread.sleep(1000 / config.idleFPS);
                } catch (InterruptedException e) {
                    // ignore
                }
            } else if (targetFramerate > 0) {
                //sync.sync(targetFramerate); // sleep as needed to meet the target framerate
            }
        }

        System.out.println("[VulkanApplication] loop() finished after " + frameCount + " iterations."); // Log exit
    }

    protected void cleanupWindows() {
        synchronized (lifecycleListeners) {
            for (LifecycleListener lifecycleListener : lifecycleListeners) {
                lifecycleListener.pause();
                lifecycleListener.dispose();
            }
        }
        for (VulkanWindow window : windows) {
            window.dispose();
        }
        windows.clear();
    }

    protected void cleanup() {
        final String logTag = "VulkanApplication";
        // Use System.out/err during cleanup as Gdx.app might become null unexpectedly
        System.out.println("[" + logTag + "] Starting cleanup...");

        // 1. Dispose Listener FIRST (Prevents listener code running during shutdown)
        if (currentWindow != null && currentWindow.getListener() != null) {
            try {
                currentWindow.getListener().pause();
                currentWindow.getListener().dispose();
                System.out.println("[" + logTag + "] ApplicationListener disposed.");
            } catch (Throwable t) {
                System.err.println("[" + logTag + "] ERROR: Exception during listener dispose.");
                t.printStackTrace();
            }
        }

        // 2. Dispose All Windows (Calls VulkanWindow.dispose())
        // IMPORTANT: Ensure VulkanWindow.dispose() ONLY cleans up window-specific things like
        //            input callbacks (via input.dispose()) and the GLFW window handle (glfwDestroyWindow).
        //            It should *NOT* dispose the shared Gdx.graphics instance.
        cleanupWindows(); // Calls dispose() on each VulkanWindow
        System.out.println("[" + logTag + "] Window cleanup finished.");

        // 3. Dispose Graphics Resources EXPLICITLY and wait for GPU idle
        // Access the single graphics instance via Gdx static.
        if (Gdx.graphics instanceof VulkanGraphics) {
            System.out.println("[" + logTag + "] Disposing VulkanGraphics...");
            try {
                // VulkanGraphics.dispose() MUST call cleanupVulkan(),
                // which MUST call vkDeviceWaitIdle() FIRST, then destroy
                // pipeline, layout, shaders, buffers, memory, swapchain resources, sync objects.
                ((VulkanGraphics)Gdx.graphics).dispose();
                System.out.println("[" + logTag + "] VulkanGraphics disposed.");
            } catch (Throwable t) {
                System.err.println("[" + logTag + "] ERROR: Exception during graphics dispose.");
                t.printStackTrace();
            }
        } else if (Gdx.graphics != null) {
            System.err.println("[" + logTag + "] WARNING: Gdx.graphics was not VulkanGraphics?");
        }

        // 4. Dispose Audio (Can happen after graphics)
        if (audio != null) {
            audio.dispose();
            System.out.println("[" + logTag + "] Audio disposed.");
            audio = null;
        }

        destroyVmaAllocator();

        // 5. Cleanup Vulkan Logical Device (AFTER graphics resources are destroyed and GPU is idle)
        // VulkanDevice.cleanup() should destroy command pool THEN logical device.
        if (vulkanDevice != null) {
            System.out.println("[" + logTag + "] Cleaning up VulkanDevice...");
            vulkanDevice.cleanup();
            System.out.println("[" + logTag + "] VulkanDevice cleanup finished.");
            vulkanDevice = null;
        }

        // 6. Destroy Debug Messenger (Requires Instance handle)
        if (debugMessenger != VK_NULL_HANDLE && vulkanInstance != null) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(vulkanInstance.getRawInstance(), debugMessenger, null);
            System.out.println("[" + logTag + "] Debug messenger destroyed.");
            debugMessenger = VK_NULL_HANDLE;
        } else if (debugMessenger != VK_NULL_HANDLE) {
            System.err.println("[" + logTag + "] ERROR: Cannot destroy debug messenger, Vulkan instance is null!");
        }

        // 7. Free Debug Callback instance object
        if (debugCallbackInstance != null) {
            debugCallbackInstance.free();
            System.out.println("[" + logTag + "] Debug callback freed.");
            debugCallbackInstance = null;
        }

        // 8. Destroy Vulkan Surface (Requires Instance handle)
        if (primarySurface != VK_NULL_HANDLE && vulkanInstance != null) {
            KHRSurface.vkDestroySurfaceKHR(vulkanInstance.getRawInstance(), primarySurface, null);
            System.out.println("[" + logTag + "] Primary surface destroyed.");
            primarySurface = VK_NULL_HANDLE;
        } else if (primarySurface != VK_NULL_HANDLE) {
            System.err.println("[" + logTag + "] ERROR: Cannot destroy surface - Vulkan instance already null!");
        }

        // 9. Destroy Vulkan Instance
        if (vulkanInstance != null) {
            vulkanInstance.cleanup(); // Calls vkDestroyInstance
            System.out.println("[" + logTag + "] Vulkan instance cleaned up.");
            vulkanInstance = null;
        }

        // 10. Cleanup GLFW Callbacks
        // Static error callback needs null check before free if load fails early
        if (errorCallback != null) {
            errorCallback.free();
            errorCallback = null; // Set static field to null
            System.out.println("[" + logTag + "] GLFW error callback freed.");
        }
        if (glDebugCallback != null) { /* ... free ... */ }

        // 11. Terminate GLFW (Must be AFTER windows/surfaces are destroyed)
        GLFW.glfwTerminate();
        System.out.println("[" + logTag + "] GLFW terminated.");

        // 12. Dispose system cursors
        VulkanCursor.disposeSystemCursors();

        // Log finished BEFORE nullifying statics
        System.out.println("[" + logTag + "] Cleanup finished.");

        // 13. Nullify Gdx statics LAST to prevent NPEs during cleanup logging/calls
        Gdx.app = null;
        Gdx.graphics = null;
        Gdx.input = null;
        Gdx.audio = null;
        Gdx.files = null;
        Gdx.net = null;
    }

    // Helper logging methods (if not already present)
    private void logInfo(String tag, String message) {
        if (Gdx.app != null && Gdx.app.getApplicationLogger() != null) Gdx.app.log(tag, message);
        else System.out.println("[" + tag + "] " + message);
    }
    private void logError(String tag, String message) {
        if (Gdx.app != null && Gdx.app.getApplicationLogger() != null) Gdx.app.error(tag, message);
        else System.err.println("[" + tag + "] ERROR: " + message);
    }
    private void logError(String tag, String message, Throwable t) {
        if (Gdx.app != null && Gdx.app.getApplicationLogger() != null) Gdx.app.error(tag, message, t);
        else { System.err.println("[" + tag + "] ERROR: " + message); t.printStackTrace(); }
    }

    public VulkanInstance getVulkanInstance() {
        return vulkanInstance;
    }

    public VulkanDevice getVkDevice() {
        return vulkanDevice;
    }

    // Surface getter needs to know which window's surface is needed
    public long getSurface(long windowHandle) {
        // If using a map: return windowSurfaces.getOrDefault(windowHandle, VK_NULL_HANDLE);
        // If only one window for now:
        if (windows.notEmpty() && windowHandle == windows.first().getWindowHandle()) {
            return primarySurface;
        }
        return VK_NULL_HANDLE;
    }

    @Override
    public ApplicationListener getApplicationListener() {
        return currentWindow.getListener();
    }

    @Override
    public Graphics getGraphics() {
        return currentWindow.getGraphics();
    }

    @Override
    public Audio getAudio() {
        return audio;
    }

    @Override
    public Input getInput() {
        return currentWindow.getInput();
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
        return ApplicationType.HeadlessDesktop;
    }

    @Override
    public int getVersion() {
        return 0;
    }

    @Override
    public long getJavaHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    @Override
    public long getNativeHeap() {
        return getJavaHeap();
    }

    @Override
    public Preferences getPreferences(String name) {
        if (preferences.containsKey(name)) {
            return preferences.get(name);
        } else {
            Preferences prefs = new VulkanPreferences(
                    new VulkanFileHandle(new File(config.preferencesDirectory, name), config.preferencesFileType));
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
    }

    @Override
    public void removeLifecycleListener(LifecycleListener listener) {
        synchronized (lifecycleListeners) {
            lifecycleListeners.removeValue(listener, true);
        }
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

    protected Files createFiles() {
        return new VulkanFiles();
    }

    /**
     * Creates a new {@link VulkanWindow} using the provided listener and {@link VulkanWindowConfiguration}.
     * <p>
     * This function only just instantiates a {@link VulkanWindow} and returns immediately. The actual window creation is postponed
     * with {@link Application#postRunnable(Runnable)} until after all existing windows are updated.
     */
    public VulkanWindow newWindow(ApplicationListener listener, VulkanWindowConfiguration config) {
        VulkanApplicationConfiguration appConfig = VulkanApplicationConfiguration.copy(this.config);
        appConfig.setWindowConfiguration(config);
        if (appConfig.title == null) appConfig.title = listener.getClass().getSimpleName();
        System.out.println("Creating new window with title: " + appConfig.title);
        return createWindow(appConfig, listener, windows.get(0).getWindowHandle());
    }

    private VulkanWindow createWindow(final VulkanApplicationConfiguration config, ApplicationListener listener, final long sharedContext) {
        final VulkanWindow window = new VulkanWindow(listener, lifecycleListeners, config, this);
        if (sharedContext == 0) {
            // the main window is created immediately
            createWindow(window, config, sharedContext);
        } else {
            // creation of additional windows is deferred to avoid GL context trouble
            postRunnable(new Runnable() {
                public void run() {
                    createWindow(window, config, sharedContext);
                    windows.add(window);
                }
            });
        }
        System.out.println("CreateWindow HERE!!!");
        return window;
    }

    void createWindow(VulkanWindow window, VulkanApplicationConfiguration config, long sharedContext) {
        long windowHandle = createGlfwWindow(config, sharedContext);
        window.create(windowHandle);
        window.setVisible(config.initialVisible);
System.out.println("HERE!!!!!!!!!!");
        if (currentWindow != null) {
            // the call above to createGlfwWindow switches the OpenGL context to the newly created window,
            // ensure that the invariant "currentWindow is the window with the current active OpenGL context" holds
            currentWindow.makeCurrent();
        }
    }

    private long createGlfwWindow(VulkanApplicationConfiguration config, long sharedContextWindow) {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, config.windowResizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_MAXIMIZED, config.windowMaximized ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_AUTO_ICONIFY, config.autoIconify ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        if (!GLFW.glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        System.out.println("Creating Vulkan-compatible GLFW window...");
        long window = GLFW.glfwCreateWindow(config.windowWidth, config.windowHeight, "GdxVk", 0, 0);

        if (window == 0) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        System.out.println("Vulkan-compatible window created. Window handle: " + window);

        GLFW.glfwSetWindowPos(window, 100, 100); // Set position explicitly
        System.out.println("Window position explicitly set to 100, 100.");

        return window;
    }

    private void createLibGDXWindow(ApplicationListener listener, VulkanApplicationConfiguration config) {
        // Initialize the array to hold VulkanWindow instances
        windows.clear();

        // Create the VulkanWindow instance directly
        gdxWindow = new VulkanWindow(listener, lifecycleListeners, config, this);

        // Add the window to the internal windows list
        windows.add(gdxWindow);

        System.out.println("LibGDX Vulkan window successfully created.");
    }

    private List<String> getRequiredInstanceExtensions(MemoryStack stack) {
        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
        if (glfwExtensions == null) {
            throw new GdxRuntimeException("Failed to find required GLFW extensions for Vulkan");
        }

        // Use the existing helper to convert PointerBuffer to List<String>
        List<String> extensions = convertPointerBufferToStringList(glfwExtensions);

        // Add debug utils extension if validation layers are enabled
        if (enableValidationLayers) {
            // Make sure the list is modifiable if needed
            if (extensions == Collections.EMPTY_LIST) { // Check if the list from helper could be immutable empty
                extensions = new ArrayList<>();
            } else if (extensions.getClass() != ArrayList.class) { // Ensure it's a modifiable list type
                extensions = new ArrayList<>(extensions);
            }
            extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            System.out.println("Debug Utils extension enabled.");
        }

        System.out.println("Required instance extensions: " + extensions);
        return extensions;
    }

    private List<String> getValidationLayers() {
        if (!enableValidationLayers) {
            System.out.println("Validation layers disabled.");
            return Collections.emptyList(); // Return empty list, not null
        }

        try (MemoryStack stack = stackPush()) {
            IntBuffer layerCount = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(layerCount, null);

            if (layerCount.get(0) == 0) {
                System.err.println("WARNING: Validation layers requested, but no layers found!");
                return Collections.emptyList();
            }

            VkLayerProperties.Buffer availableLayers = VkLayerProperties.calloc(layerCount.get(0), stack);
            vkEnumerateInstanceLayerProperties(layerCount, availableLayers);

            // Check if all desired layers are available
            Set<String> availableLayerNames = new HashSet<>();
            for (int i = 0; i < availableLayers.limit(); i++) {
                availableLayerNames.add(availableLayers.get(i).layerNameString());
            }

            for (String layerName : DESIRED_VALIDATION_LAYERS) {
                if (!availableLayerNames.contains(layerName)) {
                    System.err.println("WARNING: Validation layer '" + layerName + "' requested, but not available!");
                    // You could choose to throw an error or proceed without it
                    // For now, we'll proceed without validation if the standard one isn't found
                    return Collections.emptyList();
                }
            }

            System.out.println("Validation layers enabled: " + DESIRED_VALIDATION_LAYERS);
            return DESIRED_VALIDATION_LAYERS; // Return the constant list

        } catch (Exception e) {
            System.err.println("WARNING: Failed to check for validation layers. Disabling. Error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private void setupDebugMessenger(MemoryStack stack) {
        if (!enableValidationLayers) return;

        // Create the callback instance lazily
        if (this.debugCallbackInstance == null) {
            this.debugCallbackInstance = new VkDebugUtilsMessengerCallbackEXT() {
                @Override
                public int invoke(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
                    VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                    // ... your logging logic ...
                    String message = "VULKAN DEBUG: " + callbackData.pMessageString();
                    if (Gdx.app != null) Gdx.app.error("VulkanDebug", message);
                    else System.err.println(message);
                    return VK_FALSE;
                }
            };
            if (Gdx.app != null)
                Gdx.app.log("VulkanApplication", "Debug callback instance created.");
            else System.out.println("VulkanApplication: Debug callback instance created.");
        }

        VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType$Default()
                // ---> ADD or CORRECT these lines <---
                .messageSeverity( // Specify which message severities to receive
                        //VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT | // Optional: very detailed output
                        VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                                VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) // Must be non-zero!
                .messageType( // Specify which message types to receive
                        VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                                VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                                VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT) // Must be non-zero!
                // ---------------------------------
                .pfnUserCallback(this.debugCallbackInstance)
                .pUserData(0);


        // ... rest of the method ...
        LongBuffer pDebugMessenger = stack.mallocLong(1);
        int err = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(vulkanInstance.getRawInstance(), createInfo, null, pDebugMessenger);
        // ... handle error or success ...
        if (err == VK_SUCCESS) {
            this.debugMessenger = pDebugMessenger.get(0);
            if (Gdx.app != null)
                Gdx.app.log("VulkanApplication", "Vulkan Debug Messenger setup complete.");
            else System.out.println("VulkanApplication: Vulkan Debug Messenger setup complete.");
        } else {
            if (Gdx.app != null)
                Gdx.app.error("VulkanApplication", "Failed to set up Vulkan debug messenger: error code " + err);
            else
                System.err.println("WARNING: Failed to set up Vulkan debug messenger: error code " + err);
            this.debugMessenger = VK_NULL_HANDLE;
        }
    }

    private VkPhysicalDevice selectPhysicalDevice(MemoryStack stack) {
        org.lwjgl.vulkan.VkInstance rawInstance = vulkanInstance.getRawInstance();

        IntBuffer deviceCount = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(rawInstance, deviceCount, null);

        if (deviceCount.get(0) == 0) {
            throw new GdxRuntimeException("Failed to find GPUs with Vulkan support!");
        }

        PointerBuffer ppPhysicalDevices = stack.mallocPointer(deviceCount.get(0));
        vkEnumeratePhysicalDevices(rawInstance, deviceCount, ppPhysicalDevices);

        VkPhysicalDevice selectedDevice = null;
        for (int i = 0; i < ppPhysicalDevices.limit(); i++) {
            VkPhysicalDevice device = new VkPhysicalDevice(ppPhysicalDevices.get(i), rawInstance);
            if (isDeviceSuitable(device, stack)) {
                selectedDevice = device;
                // Prefer discrete GPU if found, otherwise take the first suitable
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(device, properties);
                System.out.println("Selected Physical Device: " + properties.deviceNameString());
                properties.free(); // Free the allocated properties struct
                if (properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                    break; // Found a discrete GPU, stop searching
                }
                // Keep searching in case a discrete GPU comes later
            }
        }

        if (selectedDevice == null) {
            throw new GdxRuntimeException("Failed to find a suitable GPU!");
        }

        return selectedDevice;
    }

    // Helper function to check device suitability
    private boolean isDeviceSuitable(VkPhysicalDevice device, MemoryStack stack) {
        // 1. Check Queue Families
        QueueFamilyIndices indices = findQueueFamilies(device, stack); // Reuse the find method

        // 2. Check Required Device Extensions
        boolean extensionsSupported = checkDeviceExtensionSupport(device, stack);

        // 3. Check Swapchain Support (requires extensions to be supported first)
        boolean swapChainAdequate = false;
        if (extensionsSupported) {
            // querySwapChainSupport allocates details on the current stack frame
            SwapChainSupportDetails swapChainSupport = querySwapChainSupport(device, stack);
            // Check if swapchain support is minimally adequate
            swapChainAdequate = swapChainSupport.formats.limit() > 0 && swapChainSupport.presentModes.limit() > 0;
            // *** No need to free swapChainSupport members allocated on the stack ***
        }

        return indices.isComplete() && extensionsSupported && swapChainAdequate; // && featuresSupported;
    }

    // Helper to check device extension support
    private boolean checkDeviceExtensionSupport(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer extensionCount = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCount, null);

        if (extensionCount.get(0) == 0 && !DEVICE_EXTENSIONS.isEmpty()) {
            return false; // No extensions available, but we need some
        }

        VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.calloc(extensionCount.get(0), stack);
        vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCount, availableExtensions);

        Set<String> availableExtensionNames = new HashSet<>();
        for (int i = 0; i < availableExtensions.limit(); i++) {
            availableExtensionNames.add(availableExtensions.get(i).extensionNameString());
        }

        // Check if all required extensions are present
        return availableExtensionNames.containsAll(DEVICE_EXTENSIONS);
    }

    // Helper to query swap chain support details
    private SwapChainSupportDetails querySwapChainSupport(VkPhysicalDevice device, MemoryStack stack) {
        SwapChainSupportDetails details = new SwapChainSupportDetails();

        // Allocate these on the stack passed in, they will be freed by the caller (isDeviceSuitable)
        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, this.primarySurface, details.capabilities);

        IntBuffer formatCount = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfaceFormatsKHR(device, this.primarySurface, formatCount, null);
        if (formatCount.get(0) != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(formatCount.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, this.primarySurface, formatCount, details.formats);
        } else {
            details.formats = VkSurfaceFormatKHR.malloc(0, stack); // Allocate empty buffer if none found
        }


        IntBuffer presentModeCount = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfacePresentModesKHR(device, this.primarySurface, presentModeCount, null);
        if (presentModeCount.get(0) != 0) {
            details.presentModes = stack.mallocInt(presentModeCount.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, this.primarySurface, presentModeCount, details.presentModes);
        } else {
            details.presentModes = stack.mallocInt(0); // Allocate empty buffer if none found
        }


        return details;
    }

    public void createVmaAllocator(VulkanInstance vkInstanceWrapper, VulkanDevice vulkanDeviceWrapper) {
        Gdx.app.log("VmaInit", "Creating VMA Allocator...");
        try (MemoryStack stack = stackPush()) {
            // 1. Set up Vulkan functions for VMA (LWJGL helper)
            VmaVulkanFunctions vulkanFunctions = VmaVulkanFunctions.calloc(stack)
                    .set(vkInstanceWrapper.getRawInstance(), vulkanDeviceWrapper.getRawDevice()); // Pass VkInstance, VkDevice

            // 2. Set up Allocator Create Info
            VmaAllocatorCreateInfo allocatorInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(0) // Optional flags (e.g., VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT)
                    .physicalDevice(vulkanDeviceWrapper.getPhysicalDevice()) // Pass VkPhysicalDevice
                    .device(vulkanDeviceWrapper.getRawDevice())             // Pass VkDevice
                    .pVulkanFunctions(vulkanFunctions)
                    .instance(vkInstanceWrapper.getRawInstance())                // Pass VkInstance
                    .vulkanApiVersion(VK_API_VERSION_1_0); // Or your target version (VK_API_VERSION_1_1 etc.)
            // Optional: .pHeapSizeLimit(), .pTypeExternalMemoryHandleTypes()

            // 3. Create the Allocator
            PointerBuffer pAllocator = stack.mallocPointer(1); // VmaAllocator is pointer type
            vkCheck(vmaCreateAllocator(allocatorInfo, pAllocator), "Failed to create VMA allocator"); // Use vkCheck or check result
            this.vmaAllocator = pAllocator.get(0); // Store the handle

            if (this.vmaAllocator == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("VMA Allocator creation succeeded according to result code, but handle is NULL!");
            }
            Gdx.app.log("VmaInit", "VMA Allocator created successfully. Handle: " + this.vmaAllocator);
        }
    }

    // Ensure you have a corresponding destroy method called before device/instance destruction
    public void destroyVmaAllocator() {
        if (vmaAllocator != VK_NULL_HANDLE) {
            Gdx.app.log("VmaInit", "Destroying VMA Allocator...");
            vmaDestroyAllocator(vmaAllocator);
            vmaAllocator = VK_NULL_HANDLE;
            Gdx.app.log("VmaInit", "VMA Allocator destroyed.");
        }
    }

    // Add a getter for the allocator handle
    public long getVmaAllocator() {
        if (vmaAllocator == VK_NULL_HANDLE) {
            throw new IllegalStateException("VMA Allocator not created or already destroyed.");
        }
        return vmaAllocator;
    }

    private QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device, MemoryStack stack) {
        QueueFamilyIndices indices = new QueueFamilyIndices();

        IntBuffer queueFamilyCount = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

        if (queueFamilyCount.get(0) == 0) {
            throw new GdxRuntimeException("Physical device has no queue families!");
        }

        VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.calloc(queueFamilyCount.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

        IntBuffer pPresentSupport = stack.mallocInt(1); // Buffer for surface support query result

        for (int i = 0; i < queueFamilies.limit(); i++) {
            // Check for Graphics support
            if ((queueFamilies.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                indices.graphicsFamily = i;
            }

            // Check for Presentation support (needs the surface)
            vkGetPhysicalDeviceSurfaceSupportKHR(device, i, this.primarySurface, pPresentSupport);
            if (pPresentSupport.get(0) == VK_TRUE) {
                indices.presentFamily = i;
            }

            // If we found both, we can stop early (though separate queues can be better)
            if (indices.isComplete()) {
                break;
            }
        }

        if (!indices.isComplete()) {
            throw new GdxRuntimeException("Could not find suitable queue families on the physical device!");
        }

        System.out.println("Found Graphics Queue Family: " + indices.graphicsFamily);
        System.out.println("Found Present Queue Family: " + indices.presentFamily);
        return indices;
    }

    public static class QueueFamilyIndices {
        public Integer graphicsFamily;
        public Integer presentFamily; // Queue family for presenting to the surface

        public boolean isComplete() {
            return graphicsFamily != null && presentFamily != null;
        }
    }

    private static class SwapChainSupportDetails {
        VkSurfaceCapabilitiesKHR capabilities;
        VkSurfaceFormatKHR.Buffer formats;
        IntBuffer presentModes;
    }
}
