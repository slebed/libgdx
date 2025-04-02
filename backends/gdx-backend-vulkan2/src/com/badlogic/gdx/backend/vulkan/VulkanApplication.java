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

import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
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
import org.lwjgl.vulkan.EXTDebugUtils;
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

import java.util.Set; // For efficient checking
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
    private VkDevice vkDevice;
    // Maybe a map for surfaces if supporting multiple windows: Map<Long, Long> windowSurfaces = new HashMap<>();
    private long primarySurface = VK_NULL_HANDLE; // For the first window
    private final VulkanClipboard clipboard;
    private final VulkanApplicationConfiguration config;
    private long window;
    private VulkanWindow gdxWindow;
    private long debugMessenger;
    private boolean enableValidationLayers = false;

    private static final Set<String> DEVICE_EXTENSIONS = Collections.singleton(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    //private static final Set<String> DEVICE_EXTENSIONS = Set.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    //private static final List<String> DESIRED_VALIDATION_LAYERS = List.of("VK_LAYER_KHRONOS_validation");
    private static final List<String> DESIRED_VALIDATION_LAYERS = Collections.singletonList("VK_LAYER_KHRONOS_validation");

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
        try (MemoryStack stack = MemoryStack.stackPush()) {
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
            VkMemoryUtil.vkCheck(err, "Failed to create window surface");
            this.primarySurface = pSurface.get(0); // Store the surface handle
            // If using map: windowSurfaces.put(windowHandle, this.primarySurface);
            System.out.println("Window Surface created.");

            // --- Select Physical Device ---
            this.physicalDevice = selectPhysicalDevice(stack); // Implement this selection logic
            System.out.println("Physical Device selected.");

            // --- Create Logical Device (VkDevice wrapper) ---
            QueueFamilyIndices indices = findQueueFamilies(physicalDevice, stack); // Implement this
            this.vkDevice = new VkDevice.Builder()
                    .setPhysicalDevice(physicalDevice)
                    .setQueueFamilyIndex(indices.graphicsFamily) // Assuming graphics==present for now
                    // Add required device extensions (like swapchain) to builder if not hardcoded
                    .build(); // Builder creates device, queues, command pool
            System.out.println("Logical Device created.");

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
        initializeGlfw(); // Initializes GLFW library
        setApplicationLogger(new VulkanApplicationLogger());
        this.config = VulkanApplicationConfiguration.copy(config);
        if (config.title == null) config.title = listener.getClass().getSimpleName();
        Gdx.app = this; // Set Gdx.app early

        // 2. Create GLFW Window Handle (but not VulkanWindow/Graphics yet)
        //    You need the handle for surface creation. Adapt createGlfwWindow.
        long windowHandle = createGlfwWindow(config, 0); // Returns the long handle

        // 3. Core Vulkan Initialization (Instance, Surface, Device)
        initializeVulkanCore(windowHandle); // New method below

        // 4. Initialize Gdx Subsystems (Audio, Files, etc.)
        //    (Moved later as they don't depend on Vulkan core)
        if (!config.disableAudio) { /* ... audio setup ... */ } else {
            this.audio = new MockAudio();
        }
        Gdx.audio = audio;
        this.files = Gdx.files = createFiles();
        this.net = Gdx.net = new VulkanNet(config);
        this.clipboard = new VulkanClipboard();

        // 5. Create VulkanWindow and VulkanGraphics (Graphics init is deferred)
        //    Pass the handle we created earlier.
        //VulkanWindow window = new VulkanWindow(listener, lifecycleListeners, config, this);
        // window.create(windowHandle); // This now creates VulkanGraphics internally
        //windows.add(window);
        //this.primaryWindowHandle = windowHandle; // Store if needed

        // Option B (Often Cleaner): Create Graphics here, pass to Window
        VulkanGraphics graphics = new VulkanGraphics(windowHandle, config); // Adjust constructor as needed
        Gdx.graphics = graphics; // <<< --- SET THE STATIC FIELD ---

        VulkanWindow window = new VulkanWindow(listener, lifecycleListeners, config, this);//, graphics, input); // Window takes graphics/input
        window.create(windowHandle); // Window might just store handle now
        windows.add(window);
        this.currentWindow = window; // Set current window
        this.primaryWindowHandle = windowHandle;

        VulkanInput input = createInput(window); // Create input, might need window later?
        Gdx.input = input; // Set Gdx.input

        // ---> 7. Initialize Swapchain using the LOCAL 'graphics' variable <---
        try {
            Gdx.app.log("VulkanApplication", "Initializing graphics resources...");
            // Call the method on the 'graphics' variable (which IS a VulkanGraphics)
            graphics.initializeSwapchainAndResources(); // <<<<------ CORRECT WAY TO CALL IT
            Gdx.app.log("VulkanApplication", "Graphics resources initialized.");
        } catch (Throwable e) {
            // Handle fatal error during graphics setup
            System.err.println("FATAL: Exception occurred during graphics resource initialization");
            e.printStackTrace();
            cleanup();
            System.exit(-1);
        }

        // 8. Call Listener's create() (Gdx.graphics is set)
        try {
            Gdx.app.log("VulkanApplication", "Calling listener.create()...");
            listener.create();
            Gdx.app.log("VulkanApplication", "listener.create() completed.");
        } catch (Throwable e) {
            throw new GdxRuntimeException("Exception occurred in ApplicationListener.create()", e);
        }

        // 9. Start Main Loop
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
        while (running && windows.size > 0) {
            // FIXME put it on a separate thread
            if (audio!=null) {
                audio.update();
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
        VulkanCursor.disposeSystemCursors();
        if (audio!=null){
            audio.dispose();
        }
        errorCallback.free();
        errorCallback = null;
        if (glDebugCallback != null) {
            glDebugCallback.free();
            glDebugCallback = null;
        }
        if (window != 0) {
            glfwDestroyWindow(window);
        }
        if (vkDevice != null) {
            vkDevice.cleanup(); // Should call vkDestroyDevice internally
        }
        // Destroy debug messenger BEFORE freeing the callback instance
        if (debugMessenger != VK_NULL_HANDLE && vulkanInstance != null) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(vulkanInstance.getRawInstance(), debugMessenger, null);
            if(Gdx.app != null) Gdx.app.log("VulkanApplication", "Debug messenger destroyed.");
            else System.out.println("VulkanApplication: Debug messenger destroyed.");
            debugMessenger = VK_NULL_HANDLE; // Avoid double destroy
        }

        // Free the callback instance AFTER it's no longer needed by the messenger
        if (debugCallbackInstance != null) {
            debugCallbackInstance.free(); // IMPORTANT
            debugCallbackInstance = null;
            if(Gdx.app != null) Gdx.app.log("VulkanApplication", "Debug callback freed.");
            else System.out.println("VulkanApplication: Debug callback freed.");
        }
        if (vulkanInstance != null) {
            vulkanInstance.cleanup(); // Should call vkDestroyInstance internally
        }
        GLFW.glfwTerminate();
    }

    public VulkanInstance getVulkanInstance() {
        return vulkanInstance;
    }

    public VkDevice getVkDevice() {
        return vkDevice;
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
        return window;
    }

    void createWindow(VulkanWindow window, VulkanApplicationConfiguration config, long sharedContext) {
        long windowHandle = createGlfwWindow(config, sharedContext);
        window.create(windowHandle);
        window.setVisible(config.initialVisible);

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

        try (MemoryStack stack = MemoryStack.stackPush()) {
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

    /*private static final VkDebugUtilsMessengerCallbackEXT DEBUG_CALLBACK = new VkDebugUtilsMessengerCallbackEXT() {
        @Override
        public int invoke(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
            VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
            String severity;
            if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                severity = "ERROR";
            } else if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
                severity = "WARNING";
            } else if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) { // Add INFO level if desired
                severity = "INFO";
            } else if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT) != 0) {
                severity = "VERBOSE";
            } else {
                severity = "UNKNOWN";
            }
            System.err.println("VULKAN " + severity + ": " + callbackData.pMessageString());
            // You could add breakpoints here based on severity for debugging
            return VK_FALSE; // Must return VK_FALSE
        }
    };*/

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
                    if(Gdx.app != null) Gdx.app.error("VulkanDebug", message);
                    else System.err.println(message);
                    return VK_FALSE;
                }
            };
            if(Gdx.app != null) Gdx.app.log("VulkanApplication", "Debug callback instance created.");
            else System.out.println("VulkanApplication: Debug callback instance created.");
        }

        VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType$Default()
                // ... set severities/types ...
                .pfnUserCallback(this.debugCallbackInstance) // Use the instance field
                .pUserData(0);

        // ... rest of the method ...
        LongBuffer pDebugMessenger = stack.mallocLong(1);
        int err = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(vulkanInstance.getRawInstance(), createInfo, null, pDebugMessenger);
        // ... handle error or success ...
        if (err == VK_SUCCESS) {
            this.debugMessenger = pDebugMessenger.get(0);
            if(Gdx.app != null) Gdx.app.log("VulkanApplication", "Vulkan Debug Messenger setup complete.");
            else System.out.println("VulkanApplication: Vulkan Debug Messenger setup complete.");
        } else {
            if(Gdx.app != null) Gdx.app.error("VulkanApplication", "Failed to set up Vulkan debug messenger: error code " + err);
            else System.err.println("WARNING: Failed to set up Vulkan debug messenger: error code " + err);
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
