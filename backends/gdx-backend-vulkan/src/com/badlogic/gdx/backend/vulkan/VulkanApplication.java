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
import static com.badlogic.gdx.backend.vulkan.VulkanDeviceUtils.isDeviceSuitable;
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
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_FALSE;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_TRUE;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties;
// VK12 import for VK_API_VERSION_1_2
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;


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


import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkExtensionProperties;

import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

public class VulkanApplication implements VulkanApplicationBase {
    private static final String TAG = "VulkanApplication";
    private static final boolean debug = true; // Enabled debug for more verbose logging during init

    private static GLFWErrorCallback errorCallback;
    private static final Set<String> DEVICE_EXTENSIONS = Collections.singleton(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    private static final List<String> DESIRED_VALIDATION_LAYERS = Collections.singletonList("VK_LAYER_KHRONOS_validation");

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

    private void initializeVulkanCore(long windowHandle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            List<String> requiredExtensions = getRequiredInstanceExtensions(stack);
            List<String> validationLayers = getValidationLayers();

            this.vulkanInstance = new VulkanInstance.Builder()
                    .setApplicationName(appConfig.title)
                    .setRequiredExtensions(requiredExtensions)
                    .setValidationLayers(validationLayers)
                    .setApiVersion(VK_API_VERSION_1_2)
                    .build();
            if (debug) Gdx.app.log(TAG, "Vulkan Instance created.");

            if (appConfig.enableValidationLayers) {
                setupDebugMessenger(stack);
                if (debug) Gdx.app.log(TAG, "Debug Messenger created.");
            }

            LongBuffer pSurface = stack.mallocLong(1);
            int err = GLFWVulkan.glfwCreateWindowSurface(vulkanInstance.getRawInstance(), windowHandle, null, pSurface);
            vkCheck(err, "Failed to create window surface");
            this.primarySurface = pSurface.get(0);
            if (debug) Gdx.app.log(TAG, "Window Surface created for handle: " + windowHandle + ", surface: " + this.primarySurface);

            this.physicalDevice = selectPhysicalDevice(stack);
            if (this.physicalDevice == null) { // Added null check
                throw new GdxRuntimeException("Failed to select a suitable Vulkan physical device.");
            }
            if (debug) Gdx.app.log(TAG, "Physical Device selected: " + this.physicalDevice.address());

            // Instantiate and log device capabilities
            this.deviceCapabilities = new VulkanDeviceCapabilities(this.physicalDevice);
            if (debug) { // Added debug flag check for logging block
                Gdx.app.log(TAG, "--- Vulkan Device Capabilities ---");
                Gdx.app.log(TAG, "API Version: " + this.deviceCapabilities.getApiVersionString() +
                        " (Raw: " + this.deviceCapabilities.getApiVersion() + ")");
                Gdx.app.log(TAG, "Is Vulkan 1.2 Core Available: " + (this.deviceCapabilities.getApiVersion() >= VK_API_VERSION_1_2));
                Gdx.app.log(TAG, "VK_EXT_descriptor_indexing Supported (if API < 1.2): " +
                        (this.deviceCapabilities.getApiVersion() < VK_API_VERSION_1_2 && this.deviceCapabilities.isDescriptorIndexingSupported()));
                Gdx.app.log(TAG, "Overall Descriptor Indexing Features Available: " + this.deviceCapabilities.isDescriptorIndexingSupported());
                Gdx.app.log(TAG, "  runtimeDescriptorArray: " + this.deviceCapabilities.isRuntimeDescriptorArray());
                Gdx.app.log(TAG, "  shaderSampledImageArrayNonUniformIndexing: " + this.deviceCapabilities.isShaderSampledImageArrayNonUniformIndexing());
                Gdx.app.log(TAG, "  shaderStorageBufferArrayNonUniformIndexing: " + this.deviceCapabilities.isShaderStorageBufferArrayNonUniformIndexing());
                Gdx.app.log(TAG, "  descriptorBindingPartiallyBound: " + this.deviceCapabilities.isDescriptorBindingPartiallyBound());
                Gdx.app.log(TAG, "  descriptorBindingVariableDescriptorCount: " + this.deviceCapabilities.isDescriptorBindingVariableDescriptorCount());
                this.deviceCapabilities.printSummary(); // For full details if needed
                Gdx.app.log(TAG, "----------------------------------");
            }

            QueueFamilyIndices indices = findQueueFamilies(physicalDevice, stack);
            if (!indices.isComplete()) {
                throw new GdxRuntimeException("Failed to find required queue families.");
            }
            this.graphicsQueueFamily = indices.graphicsFamily;
            this.presentQueueFamily = indices.presentFamily;

            // Pass capabilities to VulkanDevice.Builder
            this.vulkanDevice = new VulkanDevice.Builder()
                    .setPhysicalDevice(physicalDevice)
                    .setGraphicsQueueFamilyIndex(indices.graphicsFamily) // Pass graphics queue index
                    .setPresentQueueFamilyIndex(indices.presentFamily)   // Pass present queue index
                    .setDeviceCapabilities(this.deviceCapabilities)      // Pass queried capabilities
                    .setTemporarilyDisableMaintenance4(true)
                    .build();
            if (debug) Gdx.app.log(TAG, "Logical Device created.");

            if (this.vulkanInstance != null && this.vulkanDevice != null) {
                createVmaAllocator(this.vulkanInstance, this.vulkanDevice);
            } else {
                throw new GdxRuntimeException("Cannot create VMA Allocator: Instance or Device is null!");
            }

        } catch (Exception e) {
            // Log the exception before re-throwing for better diagnostics
            Gdx.app.error(TAG, "Exception during core Vulkan initialization", e);
            throw new GdxRuntimeException("Failed core Vulkan initialization", e);
        }

        if (this.vmaAllocator == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VMA Allocator was not initialized by initializeVulkanCore!");
        }
    }

    private void initializeWindow(long windowHandle) {
       /* VulkanWindow newWindow = new VulkanWindow(
                listener,
                lifecycleListeners,
                config,             // Use the specific config passed to newWindow
                this,
                newSurfaceHandle,
                newGraphicsInstance // Pass the NEW graphics instance
        );*/
    }

    private List<String> convertPointerBufferToStringList(PointerBuffer buffer) {
        if (buffer == null) return Collections.emptyList(); // Return empty list instead of null
        List<String> list = new ArrayList<>(buffer.remaining());
        for (int i = 0; i < buffer.limit(); i++) { // Iterate up to limit
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
        this.primaryWindowHandle = windowHandle;

        initializeVulkanCore(windowHandle); // This now initializes deviceCapabilities

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
        this.descriptorManager = new VulkanDescriptorManager(this.vulkanDevice.getLogicalDevice(), limits, this.appConfig.getMaxFramesInFlight());
    }

    private void initializePipeline() {
        if (this.vulkanDevice == null || this.vulkanDevice.getLogicalDevice() == null) { // Check logical device from wrapper
            throw new GdxRuntimeException("VulkanDevice or its logical device is null in initializePipeline!");
        }
        this.pipelineManager = new VulkanPipelineManager(this.vulkanDevice, new VulkanShaderManager(this.vulkanDevice.getLogicalDevice()));
    }

    private VulkanGraphics initializeGraphics(long windowHandle) {
        return new VulkanGraphics(
                windowHandle,
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

                boolean currentWindowStillValid = false;
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
                            Gdx.app.log(TAG, "Processing closed windows: Reset current window to handle " + this.currentWindow.getWindowHandle() + " Gdx.graphics=" + (Gdx.graphics == null ? "null" : Gdx.graphics.hashCode()) + " Gdx.input=" + (Gdx.input == null ? "null" : Gdx.input.hashCode()));
                    } else {
                        Gdx.graphics = null;
                        Gdx.input = null;
                        if (debug) Gdx.app.log(TAG, "Processing closed windows: No windows left, context nulled.");
                    }
                }
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

        destroyVmaAllocator();

        if (vulkanDevice != null) {
            if (debug) Gdx.app.log(TAG, "Cleaning up VulkanDevice..."); // Changed from System.out
            vulkanDevice.dispose();
            if (debug) Gdx.app.log(TAG, "VulkanDevice cleanup finished."); // Changed from System.out
            vulkanDevice = null;
        }

        if (debugMessenger != VK_NULL_HANDLE && vulkanInstance != null && vulkanInstance.getRawInstance() != null) { // Added null check for raw instance
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(vulkanInstance.getRawInstance(), debugMessenger, null);
            if (debug) Gdx.app.log(TAG, "Debug messenger destroyed."); // Changed from System.out
            debugMessenger = VK_NULL_HANDLE;
        } else if (debugMessenger != VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "ERROR: Cannot destroy debug messenger, Vulkan instance or its raw handle is null!"); // Changed from System.err
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
        return ApplicationType.HeadlessDesktop; // Should be Desktop if it has windows
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

    private List<String> getRequiredInstanceExtensions(MemoryStack stack) {
        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
        if (glfwExtensions == null) {
            // Corrected error message
            throw new GdxRuntimeException("Failed to find required GLFW extensions for Vulkan. Check GLFW error callback output for details.");
        }

        List<String> extensions = new ArrayList<>();
        for (int i = 0; i < glfwExtensions.limit(); i++) {
            extensions.add(MemoryUtil.memUTF8(glfwExtensions.get(i)));
        }

        extensions.add(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
        if (debug) Gdx.app.log(TAG, "Added required instance extension: " + VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);

        if (appConfig.enableValidationLayers) {
            extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            if (debug) Gdx.app.log(TAG, "Added instance extension for validation: " + VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        }

        if (debug) Gdx.app.log(TAG, "Required instance extensions: " + extensions);
        return extensions;
    }

    private List<String> getValidationLayers() {
        if (!appConfig.enableValidationLayers) {
            if (debug) Gdx.app.log(TAG, "Validation layers disabled."); // Changed from System.out
            return Collections.emptyList();
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer layerCount = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(layerCount, null);

            if (layerCount.get(0) == 0) {
                Gdx.app.error(TAG, "WARNING: Validation layers requested, but no layers found!"); // Changed from System.err
                return Collections.emptyList();
            }

            VkLayerProperties.Buffer availableLayers = VkLayerProperties.calloc(layerCount.get(0), stack);
            vkEnumerateInstanceLayerProperties(layerCount, availableLayers);

            Set<String> availableLayerNames = new HashSet<>();
            for (int i = 0; i < availableLayers.limit(); i++) {
                availableLayerNames.add(availableLayers.get(i).layerNameString());
            }

            for (String layerName : DESIRED_VALIDATION_LAYERS) {
                if (!availableLayerNames.contains(layerName)) {
                    Gdx.app.error(TAG, "WARNING: Validation layer '" + layerName + "' requested, but not available!"); // Changed from System.err
                    return Collections.emptyList();
                }
            }

            if (debug) Gdx.app.log(TAG, "Validation layers enabled: " + DESIRED_VALIDATION_LAYERS); // Changed from System.out
            if (!DESIRED_VALIDATION_LAYERS.isEmpty() && availableLayerNames.containsAll(DESIRED_VALIDATION_LAYERS)) {
                if (debug) Gdx.app.log(TAG, "[Validation Check] Found and returning requested layers: " + DESIRED_VALIDATION_LAYERS);
            } else if (appConfig.enableValidationLayers) {
                if (debug) Gdx.app.log(TAG, "[Validation Check] Validation enabled, but requested layers NOT found or not available. Returning empty list.");
            } else {
                if (debug) Gdx.app.log(TAG, "[Validation Check] Validation disabled. Returning empty list.");
            }
            return DESIRED_VALIDATION_LAYERS;

        } catch (Exception e) {
            Gdx.app.error(TAG, "WARNING: Failed to check for validation layers. Disabling. Error: " + e.getMessage()); // Changed from System.err
            return Collections.emptyList();
        }
    }

    private void setupDebugMessenger(MemoryStack stack) {
        if (!appConfig.enableValidationLayers) return;

        if (this.debugCallbackInstance == null) {
            this.debugCallbackInstance = new VkDebugUtilsMessengerCallbackEXT() {
                @Override
                public int invoke(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
                    VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                    String message = "VULKAN DEBUG: " + callbackData.pMessageString();
                    if (Gdx.app != null) Gdx.app.error("VulkanDebug", message); // Log as error for visibility
                    else System.err.println(message);
                    return VK_FALSE;
                }
            };
            if (debug) Gdx.app.log(TAG, "Debug callback instance created.");
        }

        VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType$Default()
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(this.debugCallbackInstance)
                .pUserData(0);

        LongBuffer pDebugMessenger = stack.mallocLong(1);
        // Ensure vulkanInstance and its raw handle are not null
        if (vulkanInstance == null || vulkanInstance.getRawInstance() == null) {
            Gdx.app.error(TAG, "Cannot create debug messenger, Vulkan instance or its raw handle is null!");
            this.debugMessenger = VK_NULL_HANDLE;
            return;
        }
        int err = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(vulkanInstance.getRawInstance(), createInfo, null, pDebugMessenger);

        if (err == VK_SUCCESS) {
            this.debugMessenger = pDebugMessenger.get(0);
            if (debug) Gdx.app.log(TAG, "Vulkan Debug Messenger setup complete.");
        } else {
            Gdx.app.error(TAG, "Failed to set up Vulkan debug messenger: error code " + err);
            this.debugMessenger = VK_NULL_HANDLE;
        }
    }

    private VkPhysicalDevice selectPhysicalDevice(MemoryStack stack) {
        if (vulkanInstance == null || vulkanInstance.getRawInstance() == null) { // Added null check
            throw new GdxRuntimeException("Cannot select physical device, Vulkan instance is null.");
        }
        org.lwjgl.vulkan.VkInstance rawInstance = vulkanInstance.getRawInstance();

        IntBuffer deviceCount = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(rawInstance, deviceCount, null);

        if (deviceCount.get(0) == 0) {
            throw new GdxRuntimeException("Failed to find GPUs with Vulkan support!");
        }

        PointerBuffer ppPhysicalDevices = stack.mallocPointer(deviceCount.get(0));
        vkEnumeratePhysicalDevices(rawInstance, deviceCount, ppPhysicalDevices);

        VkPhysicalDevice selectedDevice = null;
        int bestScore = -1;

        for (int i = 0; i < ppPhysicalDevices.limit(); i++) {
            VkPhysicalDevice device = new VkPhysicalDevice(ppPhysicalDevices.get(i), rawInstance);
            int score = 0;
            if (isDeviceSuitable(device, stack,DEVICE_EXTENSIONS, this.primarySurface)) {
                score = rateDeviceSuitability(device, stack); // Rate the device
                if (score > bestScore) {
                    bestScore = score;
                    selectedDevice = device;
                }
            }
        }

        if (selectedDevice == null) {
            throw new GdxRuntimeException("Failed to find a suitable GPU!");
        }
        if (debug) { // Log selected device
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(selectedDevice, properties);
            Gdx.app.log(TAG, "Selected Physical Device: " + properties.deviceNameString() + " (Score: " + bestScore + ")");
            // properties.free(); // Not needed, stack allocated
        }
        return selectedDevice;
    }

    private int rateDeviceSuitability(VkPhysicalDevice device, MemoryStack stack) {
        int score = 0;
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
        vkGetPhysicalDeviceProperties(device, properties);

        // Massively prefer discrete GPUs
        if (properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
            score += 10000;
        }
        // Add points for higher API version (minor increments)
        score += VK_API_VERSION_MAJOR(properties.apiVersion()) * 100;
        score += VK_API_VERSION_MINOR(properties.apiVersion()) * 10;

        // Add points for larger max image dimensions (example)
        score += properties.limits().maxImageDimension2D() / 1024;

        // properties.free(); // Not needed for stack allocated struct
        return score;
    }


    // Helper to check device extension support
    private boolean checkDeviceExtensionSupport(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer extensionCountBuffer = stack.mallocInt(1); // Use stack for small IntBuffer
        vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCountBuffer, null);

        int numExtensions = extensionCountBuffer.get(0);

        if (numExtensions == 0) {
            return DEVICE_EXTENSIONS.isEmpty(); // True if no extensions are required, false otherwise
        }

        // Allocate VkExtensionProperties.Buffer on the HEAP to avoid stack overflow
        // if numExtensions is very large.
        VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.calloc(numExtensions);
        try {
            // Query again with the allocated buffer
            // Note: vkEnumerateDeviceExtensionProperties expects the IntBuffer to contain the capacity
            // when ppProperties is not null, and it will write the actual count of properties written.
            // Resetting the buffer for the count is good practice.
            extensionCountBuffer.put(0, numExtensions); // Ensure count is set for the call
            vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCountBuffer, availableExtensions);
            // numExtensions = extensionCountBuffer.get(0); // Update numExtensions with actual count written, though it should match

            Set<String> availableExtensionNames = new HashSet<>();
            for (int i = 0; i < numExtensions /* availableExtensions.limit() */; i++) { // Iterate up to the actual count
                availableExtensionNames.add(availableExtensions.get(i).extensionNameString());
            }

            // Check if all required extensions are present
            return availableExtensionNames.containsAll(DEVICE_EXTENSIONS);
        } finally {
            if (availableExtensions != null) {
                availableExtensions.free(); // Manually free the heap-allocated buffer
            }
        }
    }


    private SwapChainSupportDetails querySwapChainSupport(VkPhysicalDevice device, MemoryStack stack) {
        SwapChainSupportDetails details = new SwapChainSupportDetails();
        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
        // Ensure primarySurface is valid before calling this
        if (this.primarySurface == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "querySwapChainSupport called with null primarySurface!");
            // Return empty details or throw, current logic will likely fail downstream
            details.formats = VkSurfaceFormatKHR.malloc(0, stack);
            details.presentModes = stack.mallocInt(0);
            return details;
        }
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, this.primarySurface, details.capabilities);

        IntBuffer formatCount = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfaceFormatsKHR(device, this.primarySurface, formatCount, null);
        if (formatCount.get(0) != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(formatCount.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, this.primarySurface, formatCount, details.formats);
        } else {
            details.formats = VkSurfaceFormatKHR.malloc(0, stack);
        }

        IntBuffer presentModeCount = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfacePresentModesKHR(device, this.primarySurface, presentModeCount, null);
        if (presentModeCount.get(0) != 0) {
            details.presentModes = stack.mallocInt(presentModeCount.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, this.primarySurface, presentModeCount, details.presentModes);
        } else {
            details.presentModes = stack.mallocInt(0);
        }
        return details;
    }

    public void createVmaAllocator(VulkanInstance vkInstanceWrapper, VulkanDevice vulkanDeviceWrapper) {
        if (debug) Gdx.app.log(TAG, "Creating VMA Allocator...");
        try (MemoryStack stack = stackPush()) {
            if (vkInstanceWrapper == null || vkInstanceWrapper.getRawInstance() == null) {
                throw new GdxRuntimeException("VMA Allocator: VulkanInstance or its raw handle is null!");
            }
            if (vulkanDeviceWrapper == null || vulkanDeviceWrapper.getLogicalDevice() == null) { // Check logical device
                throw new GdxRuntimeException("VMA Allocator: VulkanDevice or its logical device is null!");
            }
            if (vulkanDeviceWrapper.getPhysicalDevice() == null) {
                throw new GdxRuntimeException("VMA Allocator: PhysicalDevice in VulkanDevice is null!");
            }


            VmaVulkanFunctions vulkanFunctions = VmaVulkanFunctions.calloc(stack)
                    .set(vkInstanceWrapper.getRawInstance(), vulkanDeviceWrapper.getLogicalDevice()); // Use logical device

            VmaAllocatorCreateInfo allocatorInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(0)
                    .physicalDevice(vulkanDeviceWrapper.getPhysicalDevice())
                    .device(vulkanDeviceWrapper.getLogicalDevice()) // Use logical device
                    .pVulkanFunctions(vulkanFunctions)
                    .instance(vkInstanceWrapper.getRawInstance())
                    .vulkanApiVersion(this.deviceCapabilities != null ? this.deviceCapabilities.getApiVersion() : VK_API_VERSION_1_0); // Use queried API version

            PointerBuffer pAllocator = stack.mallocPointer(1);
            vkCheck(vmaCreateAllocator(allocatorInfo, pAllocator), "Failed to create VMA allocator");
            this.vmaAllocator = pAllocator.get(0);

            if (this.vmaAllocator == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("VMA Allocator creation succeeded according to result code, but handle is NULL!");
            }
            if (debug) Gdx.app.log(TAG, "VMA Allocator created successfully. Handle: " + this.vmaAllocator);
        }
    }

    public void destroyVmaAllocator() {
        if (vmaAllocator != VK_NULL_HANDLE) {
            if (debug) Gdx.app.log(TAG, "Destroying VMA Allocator...");
            vmaDestroyAllocator(vmaAllocator);
            vmaAllocator = VK_NULL_HANDLE;
            if (debug) Gdx.app.log(TAG, "VMA Allocator destroyed.");
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

        IntBuffer pPresentSupport = stack.mallocInt(1);
        // Ensure primarySurface is valid
        if (this.primarySurface == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("findQueueFamilies called with null primarySurface!");
        }

        for (int i = 0; i < queueFamilies.limit(); i++) {
            if ((queueFamilies.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                indices.graphicsFamily = i;
            }
            vkGetPhysicalDeviceSurfaceSupportKHR(device, i, this.primarySurface, pPresentSupport);
            if (pPresentSupport.get(0) == VK_TRUE) {
                indices.presentFamily = i;
            }
            if (indices.isComplete()) {
                break;
            }
        }

        if (!indices.isComplete()) {
            Gdx.app.error(TAG, "Could not find all required queue families. Graphics: " + indices.graphicsFamily + ", Present: " + indices.presentFamily);
            throw new GdxRuntimeException("Could not find suitable queue families on the physical device!");
        }

        if (debug) Gdx.app.log(TAG, "Found Graphics Queue Family: " + indices.graphicsFamily); // Changed from System.out
        if (debug) Gdx.app.log(TAG, "Found Present Queue Family: " + indices.presentFamily); // Changed from System.out
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
        Graphics.Monitor[] monitors = getMonitors();
        if (monitors.length == 0) return null;
        if (monitors.length == 1) return monitors[0];

        int winX = currentWindow.getPositionX();
        int winY = currentWindow.getPositionY();
        int winW = currentWindow.getLogicalWidth();
        int winH = currentWindow.getLogicalHeight();
        int bestOverlap = -1;
        Graphics.Monitor bestMonitor = monitors[0];

        for (Graphics.Monitor monitor : monitors) {
            Graphics.DisplayMode mode = getDisplayMode(monitor);
            if (mode == null) continue; // Skip if mode is null
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
            return null;
        }
        return new VulkanCursor(currentWindow, pixmap, xHotspot, yHotspot);
    }

    public void setCursor(Cursor cursor) {
        if (currentWindow == null) {
            Gdx.app.error(TAG, "Cannot set cursor, no current window.");
            return;
        }
        if (cursor == null) {
            GLFW.glfwSetCursor(currentWindow.getWindowHandle(), NULL);
            return;
        }
        if (!(cursor instanceof VulkanCursor)) {
            Gdx.app.error(TAG, "Invalid cursor type provided: " + cursor.getClass().getName());
            return;
        }
        currentWindow.setCursorInternal(cursor);
    }

    public void setSystemCursor(Cursor.SystemCursor systemCursor) {
        if (currentWindow == null) {
            Gdx.app.error(TAG, "Cannot set system cursor, no current window.");
            return;
        }
        currentWindow.setSystemCursorInternal(systemCursor);
    }

    public Graphics.DisplayMode[] getDisplayModes(Graphics.Monitor monitor) {
        return VulkanApplicationConfiguration.getDisplayModes(monitor);
    }

    public VulkanPipelineManager getPipelineManager() {
        return pipelineManager;
    }

    public VulkanDescriptorManager getDescriptorManager() {
        return descriptorManager;
    }

    public static class QueueFamilyIndices {
        public Integer graphicsFamily;
        public Integer presentFamily;

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
        return primaryWindowHandle != 0 && GLFW.glfwGetWindowMonitor(primaryWindowHandle) != 0; // Added null check for handle
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

    public static VulkanGraphics.VulkanMonitor toVulkanMonitor(long glfwMonitor) {
        if (glfwMonitor == NULL) {
            throw new GdxRuntimeException("Cannot create VulkanMonitor from NULL GLFW handle.");
        }
        IntBuffer x = BufferUtils.createIntBuffer(1);
        IntBuffer y = BufferUtils.createIntBuffer(1);
        GLFW.glfwGetMonitorPos(glfwMonitor, x, y);
        String name = GLFW.glfwGetMonitorName(glfwMonitor);
        if (name == null) name = "Unknown";
        return new VulkanGraphics.VulkanMonitor(glfwMonitor, x.get(0), y.get(0), name);
    }

    public Graphics.Monitor getMonitor() {
        Graphics.Monitor[] monitors = getMonitors();
        if (monitors == null || monitors.length == 0) return getPrimaryMonitor(); // Added null check
        Graphics.Monitor result = monitors[0];
        if (monitors.length == 1) return result;

        if (primaryWindowHandle == 0) return result; // Cannot get window info if handle is null

        GLFW.glfwGetWindowPos(primaryWindowHandle, tmpBuffer, tmpBuffer2);
        int windowX = tmpBuffer.get(0);
        int windowY = tmpBuffer2.get(0);
        GLFW.glfwGetWindowSize(primaryWindowHandle, tmpBuffer, tmpBuffer2);
        int windowWidth = tmpBuffer.get(0);
        int windowHeight = tmpBuffer2.get(0);

        int overlap;
        int bestOverlap = 0;
        for (Graphics.Monitor monitor : monitors) {
            Graphics.DisplayMode mode = getDisplayMode(monitor);
            if (mode == null) continue; // Skip if mode is null
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
    }

    private void storeCurrentWindowPositionAndDisplayMode() {
        VulkanWindow primary = getPrimaryWindow();
        if (primary != null) {
            windowPosXBeforeFullscreen = primary.getPositionX();
            windowPosYBeforeFullscreen = primary.getPositionY();
            windowWidthBeforeFullscreen = primary.getLogicalWidth();
            windowHeightBeforeFullscreen = primary.getLogicalHeight();
        } else {
            windowPosXBeforeFullscreen = 0;
            windowPosYBeforeFullscreen = 0;
            windowWidthBeforeFullscreen = logicalWidth; // Use app-level cache
            windowHeightBeforeFullscreen = logicalHeight; // Use app-level cache
            Gdx.app.error(TAG, "Could not get primary window to store position/size before fullscreen. Using cached logical dimensions.");
        }
        displayModeBeforeFullscreen = getDisplayMode(getMonitor());
    }

    public boolean setFullscreenMode(Graphics.DisplayMode displayMode) {
        VulkanWindow primaryWindow = getPrimaryWindow();
        if (primaryWindow == null || primaryWindow.getInput() == null) {
            Gdx.app.error(TAG, "Cannot set fullscreen mode, primary window or its input is null");
            return false;
        }
        primaryWindow.getInput().resetPollingStates();

        if (!(displayMode instanceof VulkanGraphics.VulkanDisplayMode)) {
            Gdx.app.error(TAG, "Invalid DisplayMode type provided to setFullscreenMode.");
            return false;
        }
        VulkanGraphics.VulkanDisplayMode newMode = (VulkanGraphics.VulkanDisplayMode) displayMode;

        if (isFullscreen()) {
            VulkanGraphics.VulkanDisplayMode currentMode = (VulkanGraphics.VulkanDisplayMode) getDisplayMode(getMonitor());
            if (currentMode != null && currentMode.monitorHandle == newMode.monitorHandle && currentMode.refreshRate == newMode.refreshRate) { // Added null check
                GLFW.glfwSetWindowSize(primaryWindowHandle, newMode.width, newMode.height);
            } else {
                GLFW.glfwSetWindowMonitor(primaryWindowHandle, newMode.monitorHandle, 0, 0, newMode.width, newMode.height, newMode.refreshRate);
            }
        } else {
            storeCurrentWindowPositionAndDisplayMode();
            GLFW.glfwSetWindowMonitor(primaryWindowHandle, newMode.monitorHandle, 0, 0, newMode.width, newMode.height, newMode.refreshRate);
        }
        updateFramebufferInfo();
        return true;
    }

    void updateFramebufferInfo() {
        if (primaryWindowHandle == 0) { // Added check for valid handle
            Gdx.app.error(TAG, "Cannot update framebuffer info, primary window handle is null.");
            return;
        }
        int fbWidth, fbHeight, logWidth, logHeight;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(this.primaryWindowHandle, pWidth, pHeight);
            fbWidth = pWidth.get(0);
            fbHeight = pHeight.get(0);
            pWidth.clear(); pHeight.clear();
            GLFW.glfwGetWindowSize(this.primaryWindowHandle, pWidth, pHeight);
            logWidth = pWidth.get(0);
            logHeight = pHeight.get(0);
        }
        updateFramebufferInfo(fbWidth, fbHeight, logWidth, logHeight);
    }

    public void updateFramebufferInfo(int backBufferWidth, int backBufferHeight, int logicalWidth, int logicalHeight) {
        if (debug) Gdx.app.log(TAG, "updateFramebufferInfo called with: BB=" + backBufferWidth + " x " + backBufferHeight + ", Logical=" + logicalWidth + " x " + logicalHeight);
        this.backBufferWidth = backBufferWidth;
        this.backBufferHeight = backBufferHeight;
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;
        if (appConfig != null) {
            bufferFormat = new Graphics.BufferFormat(appConfig.r, appConfig.g, appConfig.b, appConfig.a, appConfig.depth, appConfig.stencil, appConfig.samples, false);
        } else {
            Gdx.app.error(TAG, "Config is null during updateFramebufferInfo, cannot update bufferFormat.");
        }
        if (debug) Gdx.app.log(TAG, "Cached dimensions updated: BB=" + this.backBufferWidth + " x " + this.backBufferHeight + ", Logical=" + this.logicalWidth + " x " + this.logicalHeight);
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
        if (currentWindow != null) {
            currentWindow.setTitleInternal(title);
        } else if (primaryWindowHandle != 0) { // Fallback to primary if no current window
            GLFW.glfwSetWindowTitle(primaryWindowHandle, title);
        } else {
            Gdx.app.error(TAG, "Cannot set title, no current window and primary window handle is null.");
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

    public void setCurrentWindow(VulkanWindow window) {
        this.currentWindow = window;
    }
}
