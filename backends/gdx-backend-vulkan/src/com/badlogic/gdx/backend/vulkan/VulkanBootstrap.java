package com.badlogic.gdx.backend.vulkan;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static com.badlogic.gdx.backend.vulkan.VulkanDeviceUtils.isDeviceSuitable;
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
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_FALSE;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;

/**
 * Performs one-time Vulkan initialization: instance, physical device selection,
 * logical device creation, VMA allocator setup, and debug messenger.
 * Returns an immutable {@link BootstrapResult} containing all created handles.
 */
public class VulkanBootstrap {
    private static final String TAG = "VulkanBootstrap";
    private static final boolean debug = true;

    private static final Set<String> DEVICE_EXTENSIONS = Collections.singleton(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    private static final List<String> DESIRED_VALIDATION_LAYERS = Collections.singletonList("VK_LAYER_KHRONOS_validation");

    /** Immutable result of Vulkan bootstrap initialization. */
    public static class BootstrapResult {
        public final VulkanInstance vulkanInstance;
        public final VkPhysicalDevice physicalDevice;
        public final VulkanDevice vulkanDevice;
        public final VulkanDeviceCapabilities deviceCapabilities;
        public final long vmaAllocator;
        public final int graphicsQueueFamily;
        public final int presentQueueFamily;
        public final long debugMessenger;
        public final VkDebugUtilsMessengerCallbackEXT debugCallback;
        public final long primarySurface;

        BootstrapResult(VulkanInstance vulkanInstance, VkPhysicalDevice physicalDevice,
                        VulkanDevice vulkanDevice, VulkanDeviceCapabilities deviceCapabilities,
                        long vmaAllocator, int graphicsQueueFamily, int presentQueueFamily,
                        long debugMessenger, VkDebugUtilsMessengerCallbackEXT debugCallback,
                        long primarySurface) {
            this.vulkanInstance = vulkanInstance;
            this.physicalDevice = physicalDevice;
            this.vulkanDevice = vulkanDevice;
            this.deviceCapabilities = deviceCapabilities;
            this.vmaAllocator = vmaAllocator;
            this.graphicsQueueFamily = graphicsQueueFamily;
            this.presentQueueFamily = presentQueueFamily;
            this.debugMessenger = debugMessenger;
            this.debugCallback = debugCallback;
            this.primarySurface = primarySurface;
        }
    }

    /**
     * Initializes all Vulkan infrastructure for a given GLFW window.
     *
     * @param windowHandle GLFW window handle to create surface for
     * @param config application configuration
     * @return immutable result containing all created Vulkan handles
     */
    public BootstrapResult initialize(long windowHandle, VulkanApplicationConfiguration config) {
        VulkanInstance vulkanInstance;
        VkPhysicalDevice physicalDevice;
        VulkanDevice vulkanDevice;
        VulkanDeviceCapabilities deviceCapabilities;
        long vmaAllocator;
        int graphicsQueueFamily;
        int presentQueueFamily;
        long debugMessenger = VK_NULL_HANDLE;
        VkDebugUtilsMessengerCallbackEXT debugCallback = null;
        long primarySurface;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Create Vulkan instance
            List<String> requiredExtensions = getRequiredInstanceExtensions(config);
            List<String> validationLayers = getValidationLayers(config);

            int requestedApiVersion = config.preferredVulkanApiVersion != 0
                    ? config.preferredVulkanApiVersion : VK_API_VERSION_1_2;
            vulkanInstance = new VulkanInstance.Builder()
                    .setApplicationName(config.title)
                    .setRequiredExtensions(requiredExtensions)
                    .setValidationLayers(validationLayers)
                    .setApiVersion(requestedApiVersion)
                    .build();
            if (debug) Gdx.app.log(TAG, "Vulkan Instance created.");

            // 2. Setup debug messenger
            if (config.enableValidationLayers) {
                VkDebugUtilsMessengerCallbackEXT[] callbackHolder = new VkDebugUtilsMessengerCallbackEXT[1];
                debugMessenger = setupDebugMessenger(stack, vulkanInstance, callbackHolder);
                debugCallback = callbackHolder[0];
                if (debug) Gdx.app.log(TAG, "Debug Messenger created.");
            }

            // 3. Create window surface
            LongBuffer pSurface = stack.mallocLong(1);
            int err = GLFWVulkan.glfwCreateWindowSurface(vulkanInstance.getRawInstance(), windowHandle, null, pSurface);
            vkCheck(err, "Failed to create window surface");
            primarySurface = pSurface.get(0);
            if (debug) Gdx.app.log(TAG, "Window Surface created for handle: " + windowHandle + ", surface: " + primarySurface);

            // 4. Select physical device
            physicalDevice = selectPhysicalDevice(stack, vulkanInstance, primarySurface);
            if (physicalDevice == null) {
                throw new GdxRuntimeException("Failed to select a suitable Vulkan physical device.");
            }
            if (debug) Gdx.app.log(TAG, "Physical Device selected: " + physicalDevice.address());

            // 5. Query device capabilities
            deviceCapabilities = new VulkanDeviceCapabilities(physicalDevice);
            if (debug) {
                Gdx.app.log(TAG, "--- Vulkan Device Capabilities ---");
                Gdx.app.log(TAG, "API Version: " + deviceCapabilities.getApiVersionString());
                Gdx.app.log(TAG, "Descriptor Indexing Available: " + deviceCapabilities.isDescriptorIndexingSupported());
                deviceCapabilities.printSummary();
                Gdx.app.log(TAG, "----------------------------------");
            }

            // 6. Find queue families
            VulkanDeviceUtils.QueueFamilyIndices indices =
                    VulkanDeviceUtils.findQueueFamilies(physicalDevice, stack, primarySurface);
            if (!indices.isComplete()) {
                throw new GdxRuntimeException("Failed to find required queue families.");
            }
            graphicsQueueFamily = indices.graphicsFamily;
            presentQueueFamily = indices.presentFamily;

            // 7. Create logical device
            vulkanDevice = new VulkanDevice.Builder()
                    .setPhysicalDevice(physicalDevice)
                    .setGraphicsQueueFamilyIndex(indices.graphicsFamily)
                    .setPresentQueueFamilyIndex(indices.presentFamily)
                    .setDeviceCapabilities(deviceCapabilities)
                    .setTemporarilyDisableMaintenance4(true)
                    .build();
            if (debug) Gdx.app.log(TAG, "Logical Device created.");

            // 8. Create VMA allocator
            vmaAllocator = createVmaAllocator(vulkanInstance, vulkanDevice, deviceCapabilities);

        } catch (Exception e) {
            Gdx.app.error(TAG, "Exception during core Vulkan initialization", e);
            throw new GdxRuntimeException("Failed core Vulkan initialization", e);
        }

        if (vmaAllocator == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VMA Allocator was not initialized!");
        }

        return new BootstrapResult(vulkanInstance, physicalDevice, vulkanDevice, deviceCapabilities,
                vmaAllocator, graphicsQueueFamily, presentQueueFamily, debugMessenger, debugCallback,
                primarySurface);
    }

    private List<String> getRequiredInstanceExtensions(VulkanApplicationConfiguration config) {
        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
        if (glfwExtensions == null) {
            throw new GdxRuntimeException("Failed to find required GLFW extensions for Vulkan.");
        }

        List<String> extensions = new ArrayList<>();
        for (int i = 0; i < glfwExtensions.limit(); i++) {
            extensions.add(MemoryUtil.memUTF8(glfwExtensions.get(i)));
        }

        extensions.add(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);

        if (config.enableValidationLayers) {
            extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        }

        if (debug) Gdx.app.log(TAG, "Required instance extensions: " + extensions);
        return extensions;
    }

    private List<String> getValidationLayers(VulkanApplicationConfiguration config) {
        if (!config.enableValidationLayers) {
            if (debug) Gdx.app.log(TAG, "Validation layers disabled.");
            return Collections.emptyList();
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer layerCount = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(layerCount, null);

            if (layerCount.get(0) == 0) {
                Gdx.app.error(TAG, "WARNING: Validation layers requested, but no layers found!");
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
                    Gdx.app.error(TAG, "WARNING: Validation layer '" + layerName + "' requested, but not available!");
                    return Collections.emptyList();
                }
            }

            if (debug) Gdx.app.log(TAG, "Validation layers enabled: " + DESIRED_VALIDATION_LAYERS);
            return DESIRED_VALIDATION_LAYERS;

        } catch (Exception e) {
            Gdx.app.error(TAG, "WARNING: Failed to check for validation layers: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private long setupDebugMessenger(MemoryStack stack, VulkanInstance vulkanInstance,
                                      VkDebugUtilsMessengerCallbackEXT[] callbackHolder) {
        VkDebugUtilsMessengerCallbackEXT callback = new VkDebugUtilsMessengerCallbackEXT() {
            @Override
            public int invoke(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
                VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                String message = "VULKAN DEBUG: " + callbackData.pMessageString();
                if (Gdx.app != null) Gdx.app.error("VulkanDebug", message);
                else System.err.println(message);
                return VK_FALSE;
            }
        };
        callbackHolder[0] = callback;

        VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType$Default()
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(callback)
                .pUserData(0);

        LongBuffer pDebugMessenger = stack.mallocLong(1);
        if (vulkanInstance == null || vulkanInstance.getRawInstance() == null) {
            Gdx.app.error(TAG, "Cannot create debug messenger, Vulkan instance is null!");
            return VK_NULL_HANDLE;
        }
        int err = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(vulkanInstance.getRawInstance(), createInfo, null, pDebugMessenger);

        if (err == VK_SUCCESS) {
            if (debug) Gdx.app.log(TAG, "Vulkan Debug Messenger setup complete.");
            return pDebugMessenger.get(0);
        } else {
            Gdx.app.error(TAG, "Failed to set up Vulkan debug messenger: error code " + err);
            return VK_NULL_HANDLE;
        }
    }

    private VkPhysicalDevice selectPhysicalDevice(MemoryStack stack, VulkanInstance vulkanInstance, long surface) {
        if (vulkanInstance == null || vulkanInstance.getRawInstance() == null) {
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
            if (isDeviceSuitable(device, stack, DEVICE_EXTENSIONS, surface)) {
                int score = rateDeviceSuitability(device, stack);
                if (score > bestScore) {
                    bestScore = score;
                    selectedDevice = device;
                }
            }
        }

        if (selectedDevice == null) {
            throw new GdxRuntimeException("Failed to find a suitable GPU!");
        }
        if (debug) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(selectedDevice, properties);
            Gdx.app.log(TAG, "Selected Physical Device: " + properties.deviceNameString() + " (Score: " + bestScore + ")");
        }
        return selectedDevice;
    }

    private int rateDeviceSuitability(VkPhysicalDevice device, MemoryStack stack) {
        int score = 0;
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
        vkGetPhysicalDeviceProperties(device, properties);

        if (properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
            score += 10000;
        }
        score += VK_API_VERSION_MAJOR(properties.apiVersion()) * 100;
        score += VK_API_VERSION_MINOR(properties.apiVersion()) * 10;
        score += properties.limits().maxImageDimension2D() / 1024;

        return score;
    }

    private long createVmaAllocator(VulkanInstance vulkanInstance, VulkanDevice vulkanDevice,
                                     VulkanDeviceCapabilities deviceCapabilities) {
        if (debug) Gdx.app.log(TAG, "Creating VMA Allocator...");
        try (MemoryStack stack = stackPush()) {
            if (vulkanInstance == null || vulkanInstance.getRawInstance() == null) {
                throw new GdxRuntimeException("VMA Allocator: VulkanInstance or its raw handle is null!");
            }
            if (vulkanDevice == null || vulkanDevice.getLogicalDevice() == null) {
                throw new GdxRuntimeException("VMA Allocator: VulkanDevice or its logical device is null!");
            }
            if (vulkanDevice.getPhysicalDevice() == null) {
                throw new GdxRuntimeException("VMA Allocator: PhysicalDevice in VulkanDevice is null!");
            }

            VmaVulkanFunctions vulkanFunctions = VmaVulkanFunctions.calloc(stack)
                    .set(vulkanInstance.getRawInstance(), vulkanDevice.getLogicalDevice());

            VmaAllocatorCreateInfo allocatorInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(0)
                    .physicalDevice(vulkanDevice.getPhysicalDevice())
                    .device(vulkanDevice.getLogicalDevice())
                    .pVulkanFunctions(vulkanFunctions)
                    .instance(vulkanInstance.getRawInstance())
                    .vulkanApiVersion(deviceCapabilities != null ? deviceCapabilities.getApiVersion() : VK_API_VERSION_1_0);

            PointerBuffer pAllocator = stack.mallocPointer(1);
            vkCheck(vmaCreateAllocator(allocatorInfo, pAllocator), "Failed to create VMA allocator");
            long allocator = pAllocator.get(0);

            if (allocator == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("VMA Allocator creation succeeded but handle is NULL!");
            }
            if (debug) Gdx.app.log(TAG, "VMA Allocator created successfully. Handle: " + allocator);
            return allocator;
        }
    }

    /** Destroys a VMA allocator handle. */
    public static void destroyVmaAllocator(long vmaAllocator) {
        if (vmaAllocator != VK_NULL_HANDLE) {
            vmaDestroyAllocator(vmaAllocator);
        }
    }

    /** Destroys a debug messenger. */
    public static void destroyDebugMessenger(org.lwjgl.vulkan.VkInstance instance, long debugMessenger) {
        if (instance != null && debugMessenger != VK_NULL_HANDLE) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        }
    }
}
