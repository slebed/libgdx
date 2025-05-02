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
import static org.lwjgl.system.MemoryUtil.NULL;
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
import java.util.HashMap;
import java.util.List;

import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.backend.vulkan.audio.OpenALLwjgl3Audio;
import com.badlogic.gdx.backend.vulkan.audio.VulkanAudio;

import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.*;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

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

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.KHRSwapchain.*;

import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkExtensionProperties;

import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

public class VulkanApplication implements VulkanApplicationBase {
    private static final String TAG = "VulkanApplication";
    private static GLFWErrorCallback errorCallback;
    private static final Set<String> DEVICE_EXTENSIONS = Collections.singleton(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    private static final List<String> DESIRED_VALIDATION_LAYERS = Collections.singletonList("VK_LAYER_KHRONOS_validation");

    private final Array<Runnable> runnables = new Array<>();
    private final Array<Runnable> executedRunnables = new Array<>();
    private final Array<LifecycleListener> lifecycleListeners = new Array<>();
    private final Files files;
    private final Net net;
    private final ObjectMap<String, Preferences> preferences = new ObjectMap<>();
    private final VulkanClipboard clipboard;
    private final VulkanApplicationConfiguration appConfig;
    private final Map<Long, Long> windowSurfaces = new ConcurrentHashMap<>();
    private final ApplicationListener mainListener;

    private final long primaryWindowHandle;
    //private final boolean enableValidationLayers = true;

    private VulkanInstance vulkanInstance;
    private VkPhysicalDevice physicalDevice; // You'll need logic to select this
    private VulkanDevice vulkanDevice;
    private VulkanAudio audio;
    private VkDebugUtilsMessengerCallbackEXT debugCallbackInstance = null;
    private Integer graphicsQueueFamily;
    private Integer presentQueueFamily;
    private ApplicationLogger applicationLogger;

    private long primarySurface = VK_NULL_HANDLE; // For the first window
    private long debugMessenger;
    private long vmaAllocator = VK_NULL_HANDLE;
    private int logLevel = LOG_INFO;

    private volatile boolean running = true;
    private volatile VulkanWindow currentWindow;

    //final Array<VulkanWindow> windows = new Array<>();
    private long lastFrameTime;
    private long frameCounterStart;
    boolean resetDeltaTime;
    float deltaTime;
    private int frames;
    int frameId;
    int fps;

    private volatile int backBufferWidth;
    private volatile int backBufferHeight;
    private volatile int logicalWidth;
    private volatile int logicalHeight;
    private int windowPosXBeforeFullscreen;
    private int windowPosYBeforeFullscreen;
    private int windowWidthBeforeFullscreen;
    private int windowHeightBeforeFullscreen;
    private Graphics.DisplayMode displayModeBeforeFullscreen = null;
    private final Map<Long, VulkanGraphics> windowGraphicsMap = new HashMap<>();
    private final IntBuffer tmpBuffer = org.lwjgl.BufferUtils.createIntBuffer(1);
    private final IntBuffer tmpBuffer2 = BufferUtils.createIntBuffer(1);
    private VulkanPipelineManager pipelineManager;
    private VulkanDescriptorManager descriptorManager;
    private Graphics.BufferFormat bufferFormat;
    final Array<VulkanWindow> windows = new Array<>();
    private SnapshotArray<VulkanWindow> currentWindowsSnapshot;// = new SnapshotArray<>(windows); // Initialize here

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

            List<String> requiredExtensions = getRequiredInstanceExtensions(stack);
            ////Gdx.app.log(TAG, "Required Instance Extensions: " + requiredExtensions);
            List<String> validationLayers = getValidationLayers();

            this.vulkanInstance = new VulkanInstance.Builder()
                    .setApplicationName(appConfig.title)
                    .setRequiredExtensions(requiredExtensions)
                    .setValidationLayers(validationLayers)
                    .build();
            System.out.println("Vulkan Instance created.");

            if (appConfig.enableValidationLayers) {
                setupDebugMessenger(stack);
                System.out.println("Debug Messenger created.");
            }

            LongBuffer pSurface = stack.mallocLong(1);
            int err = GLFWVulkan.glfwCreateWindowSurface(vulkanInstance.getRawInstance(), windowHandle, null, pSurface);
            vkCheck(err, "Failed to create window surface");
            this.primarySurface = pSurface.get(0);
            System.out.println("Window Surface created.");

            this.physicalDevice = selectPhysicalDevice(stack);
            System.out.println("Physical Device selected.");

            QueueFamilyIndices indices = findQueueFamilies(physicalDevice, stack);
            if (!indices.isComplete()) {
                // This should ideally be checked inside findQueueFamilies and throw
                throw new GdxRuntimeException("Failed to find required queue families.");
            }
            this.graphicsQueueFamily = indices.graphicsFamily; // Store the index
            this.presentQueueFamily = indices.presentFamily;   // Store the index
            this.vulkanDevice = new VulkanDevice.Builder()
                    .setPhysicalDevice(physicalDevice)
                    .setQueueFamilyIndex(indices.graphicsFamily)
                    .build();
            System.out.println("Logical Device created.");

            if (this.vulkanInstance != null && this.vulkanDevice != null) {
                createVmaAllocator(this.vulkanInstance, this.vulkanDevice);
            } else {
                throw new GdxRuntimeException("Cannot create VMA Allocator: Instance or Device is null!");
            }

        } catch (Exception e) {
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

    public VulkanApplication(ApplicationListener listener, VulkanApplicationConfiguration appConfig) {
        initializeGlfw();
        setApplicationLogger(new VulkanApplicationLogger());

        this.mainListener = listener;
        this.appConfig = VulkanApplicationConfiguration.copy(appConfig);

        if (this.appConfig.title == null) this.appConfig.title = listener.getClass().getSimpleName();
        Gdx.app = this;

        long windowHandle = createGlfwWindow(this.appConfig, 0);
        //long primaryHandle = createGlfwWindow(this.appConfig, 0);
        this.primaryWindowHandle = windowHandle;

        initializeVulkanCore(windowHandle);

        if (this.vulkanDevice == null) throw new GdxRuntimeException("VulkanDevice is null after core init!");
        this.pipelineManager = new VulkanPipelineManager(this.vulkanDevice);
        this.descriptorManager = new VulkanDescriptorManager(this.vulkanDevice.getRawDevice());

        //if (this.vulkanDevice == null || this.vulkanDevice.getRawDevice() == null || this.vulkanDevice.getRawDevice().address() == VK_NULL_HANDLE) {
        //     throw new GdxRuntimeException("VulkanDevice was not initialized by initializeVulkanCore!");
        //}

        if (this.vmaAllocator == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VMA Allocator was not initialized by initializeVulkanCore!");
        }

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
        Gdx.audio = audio;
        this.files = Gdx.files = createFiles();
        this.net = Gdx.net = new VulkanNet(this.appConfig);
        this.clipboard = new VulkanClipboard();

        VulkanGraphics primaryGraphics = new VulkanGraphics(
                windowHandle,
                this.appConfig, // Use the app config
                this,           // Pass application reference
                this.vulkanDevice,
                this.vmaAllocator,
                this.pipelineManager,   // Pass shared manager
                this.descriptorManager  // Pass shared manager
        );
        // ---> End Create Primary Graphics Instance <---

        // ---> Store Primary Graphics Instance in Map <---
        windowGraphicsMap.put(windowHandle, primaryGraphics);
        // ---> End Store Primary Graphics Instance <---

        // ---> Set initial Gdx.graphics static field <---
        Gdx.graphics = primaryGraphics;

        /*VulkanGraphics graphics = new VulkanGraphics(
                windowHandle,
                this.appConfig,
                this.vulkanDevice,
                this.vmaAllocator
        );
        Gdx.graphics = graphics;*/

        //VulkanWindow window = new VulkanWindow(listener, lifecycleListeners, this.appConfig, this);
        //VulkanWindow window = new VulkanWindow(listener, lifecycleListeners, this.appConfig, this, this.primarySurface);

        VulkanWindow window = new VulkanWindow(
                listener,
                lifecycleListeners,
                this.appConfig, // Or a window-specific config based on appConfig?
                this,
                this.primarySurface, // Surface from initializeVulkanCore
                primaryGraphics      // Pass the graphics instance
        );
        ////Gdx.app.log("VulkanAppInit", "Primary VulkanWindow object created. Hash: " + window.hashCode());

        ////Gdx.app.log("VulkanAppInit", "Calling createInput...");

        VulkanInput createdInput = createInput(window);
        ////Gdx.app.log("VulkanAppInit", "createInput returned: " + (createdInput == null ? "NULL" : createdInput.getClass().getName()));

        Gdx.input = createdInput;
        if (Gdx.input != null) {
            ////Gdx.app.log("VulkanAppInit", "Gdx.input assigned. Instance Hash: " + Gdx.input.hashCode());
        } else {
            ////Gdx.app.log("VulkanAppInit", "Gdx.input assignment resulted in NULL!");
            throw new GdxRuntimeException("Failed to create VulkanInput handler"); // Fail fast
        }

        window.setInputHandler(createdInput); // Call the setter method

        //Gdx.app.log("VulkanAppInit", "Calling window.create() for handle: " + windowHandle);
        window.create(windowHandle);
        //Gdx.app.log("VulkanAppInit", "window.create() finished.");

        window.setVisible(this.appConfig.initialVisible);
        windows.add(window);

        currentWindowsSnapshot = new SnapshotArray<>(windows);
        //Gdx.app.log(TAG, "CONSTRUCTOR: Initialized SnapshotArray. snapshot.size = " + currentWindowsSnapshot.size); // Add log

        //this.currentWindow = window; // Set initial current window

        // Initial ApplicationListener calls (moved here from newWindow)
        /*try {
            //Gdx.app.log(TAG, "Calling primary listener.create()...");
            listener.create();
            //Gdx.app.log(TAG, "Primary listener.create() completed.");

            final int initialWidth = Gdx.graphics.getWidth();
            final int initialHeight = Gdx.graphics.getHeight();
            if (initialWidth > 0 && initialHeight > 0) {
                //Gdx.app.log(TAG, "Calling primary listener.resize() with initial dimensions: " + initialWidth + "x" + initialHeight);
                listener.resize(initialWidth, initialHeight);
                //Gdx.app.log(TAG, "Initial primary listener.resize() completed.");
            } else {
                Gdx.app.error(TAG, "Initial dimensions from Gdx.graphics are invalid (" + initialWidth + "x" + initialHeight + "), skipping initial resize call!");
            }
        } catch (Throwable e) {
            Gdx.app.error(TAG, "Exception occurred during initial ApplicationListener.resize()", e);
            cleanup();
            throw new GdxRuntimeException("Exception occurred during initial ApplicationListener.resize()", e);
        }*/

        /*try {
            //Gdx.app.log(TAG, "Calling listener.create()...");
            listener.create(); // Calls VulkanTestChooser.create() -> setInputProcessor()
            //Gdx.app.log(TAG, "listener.create() completed.");
        } catch (Throwable e) {
            cleanup();
            throw new GdxRuntimeException("Exception occurred in ApplicationListener.create()", e);
        }

        try {
            final int initialWidth = Gdx.graphics.getWidth();
            final int initialHeight = Gdx.graphics.getHeight();
            if (initialWidth > 0 && initialHeight > 0) {
                //Gdx.app.log(TAG, "Calling listener.resize() with initial dimensions: " + initialWidth + "x" + initialHeight);
                listener.resize(initialWidth, initialHeight);
                //Gdx.app.log(TAG, "Initial listener.resize() completed.");
            } else {
                Gdx.app.error(TAG, "Initial dimensions from Gdx.graphics are invalid (" + initialWidth + "x" + initialHeight + "), skipping initial resize call!");
            }
        } catch (Throwable e) {
            Gdx.app.error(TAG, "Exception occurred during initial ApplicationListener.resize()", e);
            cleanup();
            throw new GdxRuntimeException("Exception occurred during initial ApplicationListener.resize()", e);
        }
*/
       //Gdx.app.log(TAG, "Starting main loop..............................................................................................");
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

    /**
     * Main application loop using direct, synchronized iteration as a workaround
     * for the SnapshotArray issue.
     */
    public void loop() {
        // Temporary list to store windows requesting closure during iteration
        Array<VulkanWindow> closedWindows = new Array<>();
        long frameCount = 0; // Local frame counter for the loop method duration

        // --- Init timing (only needed on first entry) ---
        if (lastFrameTime == -1) {
            lastFrameTime = System.nanoTime();
        }
        if (frameCounterStart == 0) {
            frameCounterStart = System.nanoTime();
        }
        // --- End Init timing ---

        // --- Reset initial context ---
        // Context will be set per-window inside the loop.
        this.currentWindow = null;
        Gdx.graphics = null;
        Gdx.input = null;
        // --- End Reset initial context ---


        // --- Main loop ---
        while (running && windows.size > 0) { // Loop while running and there are windows left
            //Gdx.app.log(TAG, "LOOP_THREAD: Loop running on thread: " + Thread.currentThread().getName());
            frameCount++; // Increment local frame counter for logging/debugging

            // --- Timing / FPS Calculation ---
            long time = System.nanoTime();
            deltaTime = (time - lastFrameTime) / 1000000000.0f;
            lastFrameTime = time;
            if (time - frameCounterStart >= 1000000000) {
                fps = frames; // Store FPS
                frames = 0;   // Reset frame counter for next second
                frameCounterStart = time;
            }
            frames++; // Increment frame counter for FPS calculation
            frameId++; // Increment global frame ID (if used elsewhere)
            // --- End Timing / FPS ---

            if (audio != null) {
                audio.update();
            }

            // Poll OS events (mouse, keyboard, window events)
            GLFW.glfwPollEvents();

            // --- Process Runnables (posted via Gdx.app.postRunnable) ---
            synchronized (runnables) {
                if (!runnables.isEmpty()) { // Use isEmpty() for clarity
                    // Copy to avoid issues if a runnable posts another runnable
                    executedRunnables.addAll(runnables);
                    runnables.clear();

                    for (Runnable runnable : executedRunnables) {
                        try {
                            runnable.run();
                        } catch (Throwable t) {
                            Gdx.app.error(TAG, "Exception occurred in runnable execution", t);
                        }
                    }
                    executedRunnables.clear(); // Clear after execution

                    // If runnables were processed, assume something might need rendering
                    synchronized (windows) { // Need sync to safely iterate 'windows' here
                        for (VulkanWindow w : windows) {
                            if (w != null) w.requestRendering(); // Request render for all windows
                        }
                    }
                }
            }
            // --- End Process Runnables ---


            boolean haveWindowsRendered = false; // Reset per frame
            closedWindows.clear(); // Clear list of windows to close for this frame

            // --- Window Iteration using Direct, Synchronized Iteration ---
           //Gdx.app.log(TAG, "LOOP: === Starting Direct Iteration ===");
            synchronized (windows) { // Synchronize on the actual list being iterated
                int currentSize = windows.size; // Get size *inside* sync block before loop
               //Gdx.app.log(TAG, "LOOP: Direct Iteration. Size = " + currentSize);

                for (int i = 0; i < currentSize; i++) { // Iterate up to the size captured
                    VulkanWindow window = windows.get(i); // Get directly from the list
                    if (window == null) { // Safety check
                        Gdx.app.error(TAG, "LOOP: DIRECT Encountered null window in list at index " + i);
                        continue; // Skip this iteration
                    }
                    long currentHandle = window.getWindowHandle();
                    //Gdx.app.log(TAG, "LOOP: DIRECT Processing window iteration i=" + i + ", handle=" + currentHandle + ", hash=" + window.hashCode());

                    // --- Set Context for THIS window ---
                    VulkanGraphics windowGraphics = windowGraphicsMap.get(currentHandle); // Use handle for lookup
                    VulkanInput windowInput = window.getInput();

                    if (windowGraphics == null || windowInput == null) {
                        Gdx.app.error(TAG, "Skipping update for window " + currentHandle + " due to missing Graphics (" + windowGraphics + ") or Input (" + windowInput + ") instance.");
                        continue; // Skip this window if context is missing
                    }

                    // Set global references for Gdx.graphics/input
                    Gdx.graphics = windowGraphics;
                    Gdx.input = windowInput;
                    this.currentWindow = window; // Update the application's tracked current window
                    // --- End Set Context ---

                    // --- Check if Rendering is Needed ---
                    boolean continuous = windowGraphics.isContinuousRendering();
                    boolean requested = window.needsRendering(); // Check flag before clearing
                    window.clearNeedsRendering(); // Consume flag
                    boolean iconified = window.isIconified();
                    boolean needsRender = continuous || requested;
                   //Gdx.app.log(TAG, "LOOP: DIRECT Window " + currentHandle + " check: continuous=" + continuous + ", requested=" + requested + ", needsRender=" + needsRender + ", isIconified=" + iconified);
                    // --- End Check Rendering ---

                    // --- Update and Render Window (if needed) ---
                    boolean windowRendered = false;
                    if (needsRender && !iconified) {
                        //Gdx.app.log(TAG, "LOOP: DIRECT >>> Calling update() for window " + currentHandle + " <<<");
                        synchronized (lifecycleListeners) { // Sync listener execution if needed
                            try {
                                windowRendered = window.update(); // Performs the actual render sequence
                                haveWindowsRendered |= windowRendered; // Track if any window rendered
                            } catch (Throwable t) {
                                Gdx.app.error(TAG, "Exception occurred during window update/render for " + currentHandle, t);
                                // Consider adding window to closedWindows here if update fails critically
                                // closedWindows.add(window);
                            }
                        }
                       //Gdx.app.log(TAG, "LOOP: DIRECT <<< Returned from update() for window " + currentHandle + " >>>");
                    } else {
                       //Gdx.app.log(TAG, "LOOP: DIRECT --- Skipping update() for window " + currentHandle + " --- (Reason: needsRender=" + needsRender + ", isIconified=" + iconified + ")");
                    }
                    // --- End Update/Render Window ---

                    // --- Check if Window Should Close ---
                    // This check happens after update/render for the frame
                    if (window.shouldClose()) {
                        if (!closedWindows.contains(window, true)) { // Avoid adding duplicates
                            closedWindows.add(window);
                        }
                    }
                    // --- End Check Close ---

                } // --- End For Loop iterating through windows ---
            } // --- End Synchronized Block for iteration ---
           //Gdx.app.log(TAG, "LOOP: === Finished Direct Iteration ===");


            // --- Process Windows Marked for Closure ---
            // This happens *after* the iteration loop is complete
            if (!closedWindows.isEmpty()) { // Use isEmpty()
                //Gdx.app.log(TAG, "Processing " + closedWindows.size + " windows marked for closure.");
                synchronized (windows) { // Synchronize modifications to the main list and map
                    boolean wasLastWindowClosed = (windows.size == closedWindows.size); // Check if all existing windows are closing

                    for (VulkanWindow closedWindow : closedWindows) {
                        long closedHandle = closedWindow.getWindowHandle();
                        //Gdx.app.log(TAG, "Closing window with handle: " + closedHandle);
                        try {
                            ApplicationListener listener = closedWindow.getListener();
                            if (listener != null) {
                                Gdx.app.log(TAG, "Calling listener.dispose() for closing window: " + closedHandle);
                                try {
                                    listener.dispose();
                                } catch (Throwable t_listener) {
                                    Gdx.app.error(TAG, "Exception during listener dispose for closing window: " + closedHandle, t_listener);
                                }
                                Gdx.app.log(TAG, "listener.dispose() finished for window: " + closedHandle);
                            } else {
                                Gdx.app.log(TAG, "Window " + closedHandle + " had null listener, skipping listener dispose.");
                            }
                            // Remove from main list and map BEFORE disposing
                            boolean removed = windows.removeValue(closedWindow, true); // Use identity comparison
                            windowGraphicsMap.remove(closedHandle);
                            if (!removed) {
                                Gdx.app.error(TAG, "Attempted to remove window " + closedHandle + " but it wasn't found in 'windows' list during closure.");
                            }

                            // Now dispose the window's resources
                            closedWindow.dispose();
                            //Gdx.app.log(TAG, "Disposed window with handle: " + closedHandle);

                        } catch (Throwable t) {
                            Gdx.app.error(TAG, "Exception occurred during window dispose for handle " + closedHandle, t);
                        }
                    } // End for each closed window

                    // Check if the main list is now empty AFTER removals
                    if (windows.isEmpty() && !lifecycleListeners.isEmpty()) {
                        //Gdx.app.log(TAG, "Last window closed, disposing lifecycle listeners.");
                        // Dispose global lifecycle listeners
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

                } // End synchronized block for modification


                // --- Reset currentWindow reference if needed ---
                // Check if the application's tracked currentWindow is still in the main list
                boolean currentWindowStillValid = false;
                synchronized (windows) { // Need sync to safely check contains
                    currentWindowStillValid = windows.contains(this.currentWindow, true);
                }

                if (!currentWindowStillValid) {
                    synchronized (windows) { // Need sync to safely access first() / check size
                        this.currentWindow = !windows.isEmpty() ? windows.first() : null;
                    }
                    // Set context for the new current window if one exists
                    if (this.currentWindow != null) {
                        VulkanGraphics newCurrentGraphics = windowGraphicsMap.get(this.currentWindow.getWindowHandle());
                        VulkanInput newCurrentInput = this.currentWindow.getInput();
                        Gdx.graphics = newCurrentGraphics; // Assign even if null (map lookup could fail)
                        Gdx.input = newCurrentInput; // Assign even if null
                        //Gdx.app.log(TAG, "Processing closed windows: Reset current window to handle " + this.currentWindow.getWindowHandle()   + " Gdx.graphics=" + (Gdx.graphics == null ? "null" : Gdx.graphics.hashCode())                                + " Gdx.input=" + (Gdx.input == null ? "null" : Gdx.input.hashCode()) );
                    } else {
                        // No windows left
                        Gdx.graphics = null;
                        Gdx.input = null;
                        //Gdx.app.log(TAG, "Processing closed windows: No windows left, context nulled.");
                    }
                }
                // --- End Reset currentWindow ---
            }
            // --- End Process Closed Windows ---


            // --- Sleep if Idle ---
            // Only sleep if windows exist but none rendered this frame
            if (!haveWindowsRendered && !windows.isEmpty()) {
                try {
                    // Ensure sleep time is at least 1ms to avoid busy-waiting
                    Thread.sleep(Math.max(1, 1000 / appConfig.idleFPS));
                } catch (InterruptedException e) {
                    // Restore interrupt status if needed, but otherwise ignore
                    Thread.currentThread().interrupt();
                }
            }
            // --- End Sleep if Idle ---

        } // --- End main while loop ---

        // Log loop exit
        //Gdx.app.log(TAG, "loop() finished. Frame count: " + frameCount);
    } // --- End loop() method ---

    protected void cleanupWindows() {
        synchronized (lifecycleListeners) {
            for (LifecycleListener lifecycleListener : lifecycleListeners) {
                lifecycleListener.pause();
                lifecycleListener.dispose();
            }
        }
        Gdx.app.log(TAG, "cleanupWindows: Disposing listeners for remaining windows (count=" + windows.size + ")...");
        synchronized (windows) { // Ensure thread safety iterating/modifying
            // Create a copy to iterate over if modification happens during listener dispose
            // Although ideally listener dispose shouldn't modify the main windows list here.
            Array<VulkanWindow> windowsToClean = new Array<>(windows);

            for (VulkanWindow window : windowsToClean) { // Iterate over copy
                ApplicationListener listener = window.getListener();
                if (listener != null) {
                    Gdx.app.log(TAG, "cleanupWindows: Calling listener.dispose() for remaining window: " + window.getWindowHandle());
                    try {
                        listener.dispose();
                    } catch (Throwable t_listener) {
                        Gdx.app.error(TAG, "cleanupWindows: Exception during listener dispose for window: " + window.getWindowHandle(), t_listener);
                    }
                } else {
                    Gdx.app.log(TAG, "cleanupWindows: Window " + window.getWindowHandle() + " had null listener.");
                }
            }
            // --->>> ADD THIS SECTION END <<<---

            // Now dispose the VulkanWindow objects (Original loop)
            Gdx.app.log(TAG, "cleanupWindows: Disposing VulkanWindow objects for remaining windows...");
            for (VulkanWindow window : windowsToClean) { // Iterate over copy again
                try {
                    window.dispose(); // Dispose VulkanWindow resources
                } catch (Throwable t_window) {
                    Gdx.app.error(TAG, "cleanupWindows: Exception during VulkanWindow dispose for window: " + window.getWindowHandle(), t_window);
                }
            }
            windows.clear(); // Clear original list after all disposed
        } // End synchronized block
        Gdx.app.log(TAG, "cleanupWindows: Finished.");
    }

    protected void cleanup() {

        Gdx.app.log(TAG, "Cleanup check: currentWindow hash=" + (currentWindow == null ? "null" : currentWindow.hashCode()));
        if (currentWindow != null) {
            Gdx.app.log(TAG, "Cleanup check: currentWindow.getListener() is null? " + (currentWindow.getListener() == null));
        }

        Gdx.app.log(TAG, "Cleanup check: mainListener is null? " + (mainListener == null)); // Log check
        if (mainListener != null) { // Check the stored field
            //Gdx.app.log(TAG, "Cleanup: Attempting mainListener pause/dispose...");
            try {
                //Gdx.app.log(TAG, "Cleanup: Calling mainListener.pause()...");
                mainListener.pause(); // Call pause on the stored listener
                //Gdx.app.log(TAG, "Cleanup: mainListener.pause() finished.");

                Gdx.app.log(TAG, "Cleanup: Calling mainListener.dispose()...");
                mainListener.dispose(); // Call dispose on the stored listener
                Gdx.app.log(TAG, "Cleanup: mainListener.dispose() finished.");

                //Gdx.app.log(TAG, "ApplicationListener disposed.");
            } catch (Throwable t) {
                Gdx.app.error(TAG, "Cleanup: Exception during mainListener pause/dispose!", t);
            }
        } else {
            //Gdx.app.log(TAG, "Cleanup: Skipping mainListener pause/dispose - mainListener field was null.");
        }

        Gdx.app.log(TAG, "Cleanup: Before cleanupWindows()...");
        cleanupWindows();
        Gdx.app.log(TAG, "Cleanup: After cleanupWindows()...");

        if (audio != null) {
            audio.dispose();
            Gdx.app.error(TAG, "Audio disposed.");
            audio = null;
        }

        if (vulkanDevice != null && vulkanDevice.getRawDevice() != null) {
            Gdx.app.log(TAG, "Cleanup: Waiting for device idle before destroying managers and device...");
            VK10.vkDeviceWaitIdle(vulkanDevice.getRawDevice()); // Ensure all submitted commands are finished
            Gdx.app.log(TAG, "Cleanup: Device idle.");
        }

        if (pipelineManager != null) {
            pipelineManager.dispose();
            pipelineManager = null;
            Gdx.app.log(TAG, "PipelineManager disposed.");
        }
        if (descriptorManager != null) {
            descriptorManager.dispose();
            descriptorManager = null;
            Gdx.app.log(TAG, "DescriptorManager disposed.");
        }

        destroyVmaAllocator();

        if (vulkanDevice != null) {
            System.out.println("[" + TAG + "] Cleaning up VulkanDevice...");
            vulkanDevice.cleanup();
            System.out.println("[" + TAG + "] VulkanDevice cleanup finished.");
            vulkanDevice = null;
        }

        if (debugMessenger != VK_NULL_HANDLE && vulkanInstance != null) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(vulkanInstance.getRawInstance(), debugMessenger, null);
            System.out.println("[" + TAG + "] Debug messenger destroyed.");
            debugMessenger = VK_NULL_HANDLE;
        } else if (debugMessenger != VK_NULL_HANDLE) {
            System.err.println("[" + TAG + "] ERROR: Cannot destroy debug messenger, Vulkan instance is null!");
        }

        if (debugCallbackInstance != null) {
            debugCallbackInstance.free();
            System.out.println("[" + TAG + "] Debug callback freed.");
            debugCallbackInstance = null;
        }

        if (vulkanInstance != null) {
            vulkanInstance.cleanup();
            System.out.println("[" + TAG + "] Vulkan instance cleaned up.");
            vulkanInstance = null;
        }

        if (errorCallback != null) {
            errorCallback.free();
            errorCallback = null;
            System.out.println("[" + TAG + "] GLFW error callback freed.");
        }

        GLFW.glfwTerminate();
        System.out.println("[" + TAG + "] GLFW terminated.");

        VulkanCursor.disposeSystemCursors();

        System.out.println("[" + TAG + "] Cleanup finished.");

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

    /**
     * @return The selected physical device (VkPhysicalDevice).
     */
    public VkPhysicalDevice getPhysicalDevice() {
        if (physicalDevice == null) {
            throw new IllegalStateException("Physical Device not selected or initialized.");
        }
        return physicalDevice;
    }

    /**
     * @return The VulkanDevice wrapper (contains logical device, queues, pool).
     */
    public VulkanDevice getVulkanDevice() {
        if (vulkanDevice == null) {
            throw new IllegalStateException("Vulkan Logical Device not created.");
        }
        return vulkanDevice;
    }

    /**
     * @return The VMA Allocator handle.
     */
    public long getVmaAllocator() {
        if (vmaAllocator == VK_NULL_HANDLE) {
            throw new IllegalStateException("VMA Allocator not created or already destroyed.");
        }
        return vmaAllocator;
    }

    public void registerSurface(long windowHandle, long surfaceHandle) {
        if (surfaceHandle != VK10.VK_NULL_HANDLE) {
            windowSurfaces.put(windowHandle, surfaceHandle);
            //Gdx.app.log("VulkanApplication", "Registered surface " + surfaceHandle + " for window " + windowHandle);
        } else {
            Gdx.app.error("VulkanApplication", "Attempted to register VK_NULL_HANDLE surface for window " + windowHandle);
        }
    }

    /**
     * @return The index of the graphics queue family.
     */
    public int getGraphicsQueueFamily() {
        if (graphicsQueueFamily == null) {
            throw new IllegalStateException("Graphics Queue Family index not found or initialized.");
        }
        return graphicsQueueFamily;
    }

    /**
     * @return The index of the present queue family.
     */
    public int getPresentQueueFamily() {
        if (presentQueueFamily == null) {
            throw new IllegalStateException("Present Queue Family index not found or initialized.");
        }
        return presentQueueFamily;
    }

    /**
     * @return The primary window (first created).
     */
    public VulkanWindow getPrimaryWindow() {
        return windows.size > 0 ? windows.first() : null;
    }

    /**
     * @return The window object for a given handle, or null if not found.
     */
    public VulkanWindow getWindow(long handle) {
        for (VulkanWindow window : windows) {
            if (window.getWindowHandle() == handle) {
                return window;
            }
        }
        return null;
    }

    protected Files createFiles() {
        return new VulkanFiles();
    }

    public VulkanApplicationConfiguration getAppConfig() {
        return appConfig;
    }

    /**
     * Creates and registers a new VulkanWindow.
     *
     * @param listener The ApplicationListener for the new window.
     * @param config   The configuration for the new window.
     * @return The newly created VulkanWindow.
     */
    public VulkanWindow newWindow(ApplicationListener listener, VulkanWindowConfiguration config) {
        //Gdx.app.log(TAG, "Creating new window for listener: " + listener.getClass().getSimpleName());

        long newWindowHandle = createGlfwWindow(config, this.primaryWindowHandle);
        if (newWindowHandle == 0) {
            throw new GdxRuntimeException("Failed to create GLFW window for newWindow");
        }

        long newSurfaceHandle = VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkInstance instance = this.vulkanInstance.getRawInstance(); // Get instance
            LongBuffer pSurface = stack.mallocLong(1);
            int err = GLFWVulkan.glfwCreateWindowSurface(instance, newWindowHandle, null, pSurface);
            vkCheck(err, "Failed to create window surface for new window");
            newSurfaceHandle = pSurface.get(0);
            //Gdx.app.log(TAG, "Created surface " + newSurfaceHandle + " for new window " + newWindowHandle);
        } catch (Exception e) {
            GLFW.glfwDestroyWindow(newWindowHandle); // Clean up GLFW window if surface fails
            throw new GdxRuntimeException("Failed to create Vulkan surface for new window", e);
        }

        VulkanGraphics newGraphicsInstance = new VulkanGraphics(
                newWindowHandle,    // Use the NEW handle
                this.appConfig,     // Base config, window config might override specifics later? Review needed.
                this,               // Pass application reference
                this.vulkanDevice,
                this.vmaAllocator,
                this.pipelineManager,   // Pass SHARED manager
                this.descriptorManager  // Pass SHARED manager
        );
        //Gdx.app.log(TAG, "Created new VulkanGraphics instance for window " + newWindowHandle);

        //Gdx.app.log(TAG, "CREATE_WINDOW: Added window handle " + newWindowHandle + ". Collection size now: " + windows.size);
// Also verify it's added to windowGraphicsMap, etc.
        windowGraphicsMap.put(newWindowHandle, newGraphicsInstance);
        //Gdx.app.log(TAG, "CREATE_WINDOW: Added graphics for handle " + newWindowHandle + " to map. Map size now: " + windowGraphicsMap.size());
        //Gdx.app.log(TAG, "Stored new VulkanGraphics instance in map.");

        VulkanWindow newWindow = new VulkanWindow(
                listener,
                lifecycleListeners,
                config,             // Use the specific config passed to newWindow
                this,
                newSurfaceHandle,
                newGraphicsInstance // Pass the NEW graphics instance
        );
        //Gdx.app.log(TAG, "New VulkanWindow object created. Hash: " + newWindow.hashCode());
        //VulkanWindow newWindow = new VulkanWindow(listener, lifecycleListeners, config, this, newSurfaceHandle);

        VulkanInput newInput = new DefaultVulkanInput(newWindow); // Create input FOR THIS NEW WINDOW
        newWindow.setInputHandler(newInput); // Associate it using the setter method
        //Gdx.app.log(TAG, "Created and set Input handler for new window. Hash: " + newInput.hashCode());

        //Gdx.app.log(TAG, ">>> PAUSING before creating new window resources (Testing Timing)...");
        try {
            // Pause for 2 seconds (2000 milliseconds)
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Gdx.app.error(TAG, "Thread sleep interrupted", e);
            Thread.currentThread().interrupt(); // Restore interrupt status
        }
        //Gdx.app.log(TAG, "<<< RESUMING after pause.");

        try {
            newWindow.create(newWindowHandle);
            //Gdx.app.log(TAG, "newWindow.create() finished for handle: " + newWindowHandle);
        } catch (Throwable t) {
            Gdx.app.error(TAG, "Failed to create new window's resources", t);
            // Clean up the GLFW window handle if creation failed mid-way
            if (newSurfaceHandle != VK_NULL_HANDLE && this.vulkanInstance != null) {
                KHRSurface.vkDestroySurfaceKHR(this.vulkanInstance.getRawInstance(), newSurfaceHandle, null);
            }
            GLFW.glfwDestroyWindow(newWindowHandle);

            throw new GdxRuntimeException("Failed to create new window's resources", t);
        }

        newWindow.setVisible(config.initialVisible);
        //Gdx.app.log(TAG, "NEW_WINDOW_THREAD: Adding window on thread: " + Thread.currentThread().getName());
        synchronized (windows) {
            long newHandle = newWindow.getWindowHandle();
            //Gdx.app.log(TAG, "CREATE_WINDOW: About to add window handle " + newHandle + " to main window collection.");
            windows.add(newWindow);
            //Gdx.app.log(TAG, "CREATE_WINDOW: Added window handle " + newHandle + ". Collection size now: " + windows.size);

        }

        /*try {
            //Gdx.app.log(TAG, "Calling listener.create()...");
            listener.create(); // Calls VulkanTestChooser.create() -> setInputProcessor()
            //Gdx.app.log(TAG, "listener.create() completed.");
        } catch (Throwable e) {
            cleanup();
            throw new GdxRuntimeException("Exception occurred in ApplicationListener.create()", e);
        }

        try {
            final int initialWidth = Gdx.graphics.getWidth();
            final int initialHeight = Gdx.graphics.getHeight();
            if (initialWidth > 0 && initialHeight > 0) {
                //Gdx.app.log(TAG, "Calling listener.resize() with initial dimensions: " + initialWidth + "x" + initialHeight);
                listener.resize(initialWidth, initialHeight);
                //Gdx.app.log(TAG, "Initial listener.resize() completed.");
            } else {
                Gdx.app.error(TAG, "Initial dimensions from Gdx.graphics are invalid (" + initialWidth + "x" + initialHeight + "), skipping initial resize call!");
            }
        } catch (Throwable e) {
            Gdx.app.error(TAG, "Exception occurred during initial ApplicationListener.resize()", e);
            cleanup();
            throw new GdxRuntimeException("Exception occurred during initial ApplicationListener.resize()", e);
        }*/

        //Gdx.app.log(TAG, "newWindow finished successfully.");
        return newWindow;
    }

    private long createGlfwWindow(VulkanWindowConfiguration config, long sharedContextWindow) {
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

        GLFW.glfwSetWindowPos(window, 100, 100);
        System.out.println("Window position explicitly set to 100, 100.");

        return window;
    }

    private List<String> getRequiredInstanceExtensions(MemoryStack stack) {
        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
        if (glfwExtensions == null) {
            throw new GdxRuntimeException("Failed to find required GLFW extensions for Vulkan");
        }

        // Use the existing helper to convert PointerBuffer to List<String>
        List<String> extensions = convertPointerBufferToStringList(glfwExtensions);

        // Add debug utils extension if validation layers are enabled
        if (appConfig.enableValidationLayers) {
            // Make sure the list is modifiable if needed
            if (extensions == Collections.EMPTY_LIST) {
                extensions = new ArrayList<>();
            } else if (extensions.getClass() != ArrayList.class) {
                extensions = new ArrayList<>(extensions);
            }
            extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            System.out.println("Debug Utils extension enabled.");
        }

        System.out.println("Required instance extensions: " + extensions);
        if (appConfig.enableValidationLayers && extensions.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
            System.out.println("[Validation Check] Added VK_EXT_DEBUG_UTILS_EXTENSION_NAME.");
        } else if (appConfig.enableValidationLayers) {
            System.out.println("[Validation Check] Validation enabled, but Debug Utils extension missing?");
        }
        return extensions;
    }

    private List<String> getValidationLayers() {
        if (!appConfig.enableValidationLayers) {
            System.out.println("Validation layers disabled.");
            return Collections.emptyList();
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
            if ( !DESIRED_VALIDATION_LAYERS.isEmpty() && availableLayerNames.containsAll(DESIRED_VALIDATION_LAYERS)) {
                System.out.println("[Validation Check] Found and returning requested layers: " + DESIRED_VALIDATION_LAYERS);
            } else if (appConfig.enableValidationLayers) {
                System.out.println("[Validation Check] Validation enabled, but requested layers NOT found or not available. Returning empty list.");
            } else {
                System.out.println("[Validation Check] Validation disabled. Returning empty list.");
            }
            return DESIRED_VALIDATION_LAYERS; // Return the constant list

        } catch (Exception e) {
            System.err.println("WARNING: Failed to check for validation layers. Disabling. Error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private void setupDebugMessenger(MemoryStack stack) {
        if (!appConfig.enableValidationLayers) return;

        // Create the callback instance lazily
        if (this.debugCallbackInstance == null) {
            this.debugCallbackInstance = new VkDebugUtilsMessengerCallbackEXT() {
                @Override
                public int invoke(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
                    VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);

                    String message = "VULKAN DEBUG: " + callbackData.pMessageString();
                    if (Gdx.app != null) Gdx.app.error("VulkanDebug", message);
                    else System.err.println(message);
                    return VK_FALSE;
                }
            };
            if (Gdx.app != null) {
                //Gdx.app.log(TAG, "Debug callback instance created.");
            } else {
                System.out.println("VulkanApplication: Debug callback instance created.");
            }
        }

        VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType$Default()
                .messageSeverity( // Specify which message severities to receive
                        VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                                VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) // Must be non-zero!
                .messageType( // Specify which message types to receive
                        VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                                VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                                VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT) // Must be non-zero!
                .pfnUserCallback(this.debugCallbackInstance)
                .pUserData(0);

        LongBuffer pDebugMessenger = stack.mallocLong(1);
        int err = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(vulkanInstance.getRawInstance(), createInfo, null, pDebugMessenger);

        if (err == VK_SUCCESS) {
            this.debugMessenger = pDebugMessenger.get(0);
            if (Gdx.app != null) {
                //Gdx.app.log(TAG, "Vulkan Debug Messenger setup complete.");
            } else {
                System.out.println("VulkanApplication: Vulkan Debug Messenger setup complete.");
            }
        } else {
            if (Gdx.app != null)
                Gdx.app.error(TAG, "Failed to set up Vulkan debug messenger: error code " + err);
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
                //properties.free(); // Free the allocated properties struct
                if (properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                    break; // Found a discrete GPU, stop searching
                }
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
        //Gdx.app.log(TAG, "Creating VMA Allocator...");
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
            //Gdx.app.log(TAG, "VMA Allocator created successfully. Handle: " + this.vmaAllocator);
        }
    }

    // Ensure you have a corresponding destroy method called before device/instance destruction
    public void destroyVmaAllocator() {
        if (vmaAllocator != VK_NULL_HANDLE) {
            //Gdx.app.log(TAG, "Destroying VMA Allocator...");
            vmaDestroyAllocator(vmaAllocator);
            vmaAllocator = VK_NULL_HANDLE;
            //Gdx.app.log(TAG, "VMA Allocator destroyed.");
        }
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

    public VulkanWindow getCurrentWindow() {
        return this.currentWindow;
    }

    public Graphics.Monitor getCurrentWindowMonitor() {
        if (currentWindow == null) {
            Gdx.app.error(TAG, "Cannot get current window monitor, no current window. Falling back to primary.");
            return getPrimaryMonitor();
        }
        // Implement logic to find monitor based on currentWindow's position/size
        // This might involve calling getMonitors() and calculating overlap.
        // Example (needs refinement and Monitor class details):
        Graphics.Monitor[] monitors = getMonitors();
        if (monitors.length == 0) return null; // Should not happen if primary exists
        if (monitors.length == 1) return monitors[0];

        int winX = currentWindow.getPositionX();
        int winY = currentWindow.getPositionY();
        int winW = currentWindow.getLogicalWidth(); // Use logical for overlap calc?
        int winH = currentWindow.getLogicalHeight();
        int bestOverlap = -1;
        Graphics.Monitor bestMonitor = monitors[0];

        for (Graphics.Monitor monitor : monitors) {
            Graphics.DisplayMode mode = getDisplayMode(monitor); // Assumes this works
            int overlap = Math.max(0, Math.min(winX + winW, monitor.virtualX + mode.width) - Math.max(winX, monitor.virtualX))
                    * Math.max(0, Math.min(winY + winH, monitor.virtualY + mode.height) - Math.max(winY, monitor.virtualY));
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                bestMonitor = monitor;
            }
        }
        return bestMonitor;
    }

    public Cursor newCursor(Pixmap pixmap, int xHotspot, int yHotspot) {
        if (currentWindow == null) {
            Gdx.app.error(TAG, "Cannot create cursor, no current window.");
            return null; // Or throw?
        }
        // Assumes VulkanCursor constructor takes the VulkanWindow instance
        return new VulkanCursor(currentWindow, pixmap, xHotspot, yHotspot);
    }

    public void setCursor(Cursor cursor) {
        if (currentWindow == null) {
            Gdx.app.error(TAG, "Cannot set cursor, no current window.");
            return;
        }
        if (cursor == null) {
            // Restore default cursor? Or is this handled by VulkanCursor?
            GLFW.glfwSetCursor(currentWindow.getWindowHandle(), NULL);
            return;
        }
        if (!(cursor instanceof VulkanCursor)) {
            Gdx.app.error(TAG, "Invalid cursor type provided: " + cursor.getClass().getName());
            return;
        }
        // Option 1: Direct GLFW call
        // GLFW.glfwSetCursor(currentWindow.getWindowHandle(), ((VulkanCursor) cursor).glfwCursor);
        // Option 2: Delegate to window
        currentWindow.setCursorInternal(cursor);
    }

    public void setSystemCursor(Cursor.SystemCursor systemCursor) {
        if (currentWindow == null) {
            Gdx.app.error(TAG, "Cannot set system cursor, no current window.");
            return;
        }
        // Option 1: Direct static call
        // VulkanCursor.setSystemCursor(currentWindow.getWindowHandle(), systemCursor);
        // Option 2: Delegate to window
        currentWindow.setSystemCursorInternal(systemCursor);
    }

    public Graphics.DisplayMode[] getDisplayModes(Graphics.Monitor monitor) {
        return VulkanApplicationConfiguration.getDisplayModes(monitor);
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

    public boolean isFullscreen() {
        return GLFW.glfwGetWindowMonitor(primaryWindowHandle) != 0;
    }

    public Graphics.DisplayMode getDisplayMode() {
        return VulkanApplicationConfiguration.getDisplayMode(getMonitor());
    }

    public Graphics.DisplayMode getDisplayMode(Graphics.Monitor monitor) {
        return VulkanApplicationConfiguration.getDisplayMode(monitor);
    }

    public Graphics.Monitor getPrimaryMonitor() {
        return VulkanApplicationConfiguration.getPrimaryMonitor();
    }

    /**
     * Helper method to convert a GLFW monitor handle to a VulkanMonitor object.
     * (Could also reside in VulkanApplicationConfiguration).
     *
     * @param glfwMonitor Handle to the GLFW monitor.
     * @return A VulkanMonitor instance.
     */
    public static VulkanGraphics.VulkanMonitor toVulkanMonitor(long glfwMonitor) { // Ensure public static
        if (glfwMonitor == NULL) {
            throw new GdxRuntimeException("Cannot create VulkanMonitor from NULL GLFW handle.");
        }

        IntBuffer x = BufferUtils.createIntBuffer(1); // Or use stack allocation if in instance method
        IntBuffer y = BufferUtils.createIntBuffer(1);
        GLFW.glfwGetMonitorPos(glfwMonitor, x, y);
        String name = GLFW.glfwGetMonitorName(glfwMonitor);
        if (name == null) name = "Unknown";
        return new VulkanGraphics.VulkanMonitor(glfwMonitor, x.get(0), y.get(0), name);
    }

    public Graphics.Monitor getMonitor() {
        // Find monitor the primary window is on
        Graphics.Monitor[] monitors = getMonitors();
        if (monitors.length == 0) return getPrimaryMonitor(); // Fallback
        Graphics.Monitor result = monitors[0];

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
        for (Graphics.Monitor monitor : monitors) {
            Graphics.DisplayMode mode = getDisplayMode(monitor); // Use helper
            overlap = Math.max(0, Math.min(windowX + windowWidth, monitor.virtualX + mode.width) - Math.max(windowX, monitor.virtualX))
                    * Math.max(0, Math.min(windowY + windowHeight, monitor.virtualY + mode.height) - Math.max(windowY, monitor.virtualY));
            if (bestOverlap < overlap) {
                bestOverlap = overlap;
                result = monitor;
            }
        }
        return result;
    }

    public Graphics.Monitor[] getMonitors() {
        return VulkanApplicationConfiguration.getMonitors();
        /*PointerBuffer glfwMonitors = GLFW.glfwGetMonitors();
        if (glfwMonitors == null) return new Graphics.Monitor[0];
        Graphics.Monitor[] monitors = new Graphics.Monitor[glfwMonitors.limit()];
        for (int i = 0; i < glfwMonitors.limit(); i++) {
            monitors[i] = toVulkanMonitor(glfwMonitors.get(i)); // Use helper
        }
        return monitors;*/
    }

    private void storeCurrentWindowPositionAndDisplayMode() {
        VulkanWindow primary = getPrimaryWindow();
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

    public boolean setFullscreenMode(Graphics.DisplayMode displayMode) {
        VulkanWindow primaryWindow = getPrimaryWindow();
        if (primaryWindow == null || primaryWindow.getInput() == null) {
            Gdx.app.error(TAG, "Cannot set fullscreen mode, primary window or its input is null");
            return false;
        }
        primaryWindow.getInput().resetPollingStates(); // Reset input state

        // Ensure displayMode is the correct type
        if (!(displayMode instanceof VulkanGraphics.VulkanDisplayMode)) {
            Gdx.app.error(TAG, "Invalid DisplayMode type provided to setFullscreenMode.");
            return false;
        }
        VulkanGraphics.VulkanDisplayMode newMode = (VulkanGraphics.VulkanDisplayMode) displayMode;

        if (isFullscreen()) { // Check based on primary window handle
            // Already fullscreen, potentially change mode or monitor
            VulkanGraphics.VulkanDisplayMode currentMode = (VulkanGraphics.VulkanDisplayMode) getDisplayMode(getMonitor()); // Get current mode for primary monitor
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

        updateFramebufferInfo();

        return true;
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
            GLFW.glfwGetFramebufferSize(this.primaryWindowHandle, pWidth, pHeight);
            initialBackBufferWidth = pWidth.get(0);
            initialBackBufferHeight = pHeight.get(0);

            // Reset buffers for next query
            pWidth.clear();
            pHeight.clear();

            // Query initial logical window size
            GLFW.glfwGetWindowSize(this.primaryWindowHandle, pWidth, pHeight);
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
        //Gdx.app.log(TAG, "updateFramebufferInfo called with: BB=" + backBufferWidth + " x " + backBufferHeight + ", Logical=" + logicalWidth + " x " + logicalHeight);

        this.backBufferWidth = backBufferWidth;
        this.backBufferHeight = backBufferHeight;
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;

        if (appConfig != null) {
            bufferFormat = new Graphics.BufferFormat(appConfig.r, appConfig.g, appConfig.b, appConfig.a, appConfig.depth, appConfig.stencil, appConfig.samples, false);
        } else {
            Gdx.app.error(TAG, "Config is null during updateFramebufferInfo, cannot update bufferFormat.");
        }

        //Gdx.app.log(TAG, "Cached dimensions updated: BB=" + this.backBufferWidth + " x " + this.backBufferHeight + ", Logical=" + this.logicalWidth + " x " + this.logicalHeight);
    }

    public boolean setWindowedMode(int width, int height) {
        if (currentWindow != null) {
            return currentWindow.setWindowedModeInternal(width, height);
        } else {
            Gdx.app.error(TAG, "Cannot set windowed mode, no current window.");
            return false;
        }
    }

    public void setTitle(String title) {
        //GLFW.glfwSetWindowTitle(primaryWindowHandle, title);
        if (currentWindow != null) {
            currentWindow.setTitleInternal(title);
        } else {
            Gdx.app.error(TAG, "Cannot set title, no current window.");
        }
    }

    public void setUndecorated(boolean undecorated) {
        if (currentWindow != null) {
            currentWindow.setUndecoratedInternal(undecorated);
        } else {
            Gdx.app.error(TAG, "Cannot set undecorated, no current window.");
        }
    }

    public void setResizable(boolean resizable) {
        if (currentWindow != null) {
            currentWindow.setResizableInternal(resizable);
        } else {
            Gdx.app.error(TAG, "Cannot set resizable, no current window.");
        }
    }

    public void setVSync(boolean vsync) {
        if (currentWindow != null) {
            currentWindow.setVSyncInternal(vsync);
        } else {
            Gdx.app.error(TAG, "Cannot set VSync, no current window.");
        }
    }

    /**
     * Updates the application's reference to the currently active window.
     * This is typically called by the main loop before updating/rendering a window,
     * and potentially by a window during listener initialization if the listener
     * needs to interact with app-level methods that depend on the current window context.
     *
     * @param window The VulkanWindow whose context is now (or is about to be) active.
     */
    public void setCurrentWindow(VulkanWindow window) {
        // Optional: Add logging for debugging context switches if helpful
        // if (this.currentWindow != window && Gdx.app != null) { // Check Gdx.app for safety during early init/late cleanup
        //     long newHandle = (window != null) ? window.getWindowHandle() : 0;
        //     //Gdx.app.log(TAG, "setCurrentWindow called. New current window handle: " + newHandle);
        // }
        this.currentWindow = window;
    }

}
