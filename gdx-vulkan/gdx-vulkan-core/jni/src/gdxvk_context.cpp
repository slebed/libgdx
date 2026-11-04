#include "gdxvk_context.h"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "gdxvk_swappy.h"
#include "gdxvk_internal.h"

#include "gdxvk_caches.h"
#include "gdxvk_deletion.h"
#include "gdxvk_layout.h"
#include "gdxvk_resources.h"
#include "gdxvk_staging.h"
#include "gdxvk_timeline.h"

namespace gdxvk {

struct Context::Impl : ContextHandles {
    VkDebugUtilsMessengerEXT messenger = VK_NULL_HANDLE;
    std::atomic<uint32_t> validationMessages{0};
    DeviceInfo info;
};

namespace {

VKAPI_ATTR VkBool32 VKAPI_CALL debugCallback(VkDebugUtilsMessageSeverityFlagBitsEXT severity,
                                             VkDebugUtilsMessageTypeFlagsEXT, const VkDebugUtilsMessengerCallbackDataEXT* data,
                                             void* user) {
    // An older validation layer does not know a newer extension and says so when it is enabled; that is not a defect of ours.
    const bool unknownExtension = std::strstr(data->pMessage, "is not a known extension string") != nullptr;
    // Warnings and errors are counted so tests can assert zero; Phase 1b routes the text to a Java-visible sink.
    if (!unknownExtension) static_cast<std::atomic<uint32_t>*>(user)->fetch_add(1);
    const char* tag = (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)     ? "ERROR"
                      : (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) ? "WARN"
                                                                                       : "INFO";
    std::fprintf(stderr, "[gdx-vulkan][validation][%s] %s\n", tag, data->pMessage);
    return VK_FALSE;
}

bool hasLayer(const std::vector<VkLayerProperties>& layers, const char* name) {
    return std::any_of(layers.begin(), layers.end(), [&](const VkLayerProperties& l) { return std::strcmp(l.layerName, name) == 0; });
}
bool hasExt(const std::vector<VkExtensionProperties>& exts, const char* name) {
    return std::any_of(exts.begin(), exts.end(), [&](const VkExtensionProperties& e) { return std::strcmp(e.extensionName, name) == 0; });
}

int deviceRank(VkPhysicalDeviceType t) {
    switch (t) {
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return 4;
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return 3;
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return 2;
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return 1;
        default: return 0;
    }
}

} // namespace

Context::Context() : impl_(new Impl) {}

Context::~Context() {
    if (!impl_) return;
    if (impl_->device != VK_NULL_HANDLE) vkDeviceWaitIdle(impl_->device);
    // Services go in reverse dependency order. The deletion queue runs every pending destroy before the allocator and device go.
    delete impl_->resources;
    delete impl_->staging;
    delete impl_->deletion;
    delete impl_->layouts;
    delete impl_->samplers;
    delete impl_->pipelineCache; // saves the cache file
    delete impl_->timeline;
    if (impl_->allocator != VK_NULL_HANDLE) vmaDestroyAllocator(impl_->allocator);
    if (impl_->device != VK_NULL_HANDLE) {
        swappy::destroyDevice(impl_->device);
        vkDestroyDevice(impl_->device, nullptr);
    }
    if (impl_->messenger != VK_NULL_HANDLE) vkDestroyDebugUtilsMessengerEXT(impl_->instance, impl_->messenger, nullptr);
    if (impl_->instance != VK_NULL_HANDLE) vkDestroyInstance(impl_->instance, nullptr);
}

const DeviceInfo& Context::info() const { return impl_->info; }
uint32_t Context::validationMessageCount() const { return impl_->validationMessages.load(); }
const ContextHandles& Context::handles() const { return *impl_; }

std::unique_ptr<Context> Context::create(const ContextConfig& cfg, std::string& error, std::string& errorKind) {
    if (volkInitialize() != VK_SUCCESS) {
        errorKind = "loader";
        error = "Vulkan loader not found (vulkan-1.dll / libvulkan.so.1 / libvulkan.dylib)";
        return nullptr;
    }

    std::unique_ptr<Context> ctx(new Context());
    Impl& s = *ctx->impl_;

    // ---- Instance ----------------------------------------------------------------------------
    // The instance API version caps the effective version. Never request more than the loader supports.
    uint32_t loaderVersion = VK_API_VERSION_1_0;
    if (vkEnumerateInstanceVersion) vkEnumerateInstanceVersion(&loaderVersion);
    const uint32_t instanceApi = std::min(cfg.requestedApiVersion, loaderVersion);

    uint32_t layerCount = 0;
    vkEnumerateInstanceLayerProperties(&layerCount, nullptr);
    std::vector<VkLayerProperties> layers(layerCount);
    vkEnumerateInstanceLayerProperties(&layerCount, layers.data());

    uint32_t extCount = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> instExts(extCount);
    vkEnumerateInstanceExtensionProperties(nullptr, &extCount, instExts.data());

    std::vector<const char*> enabledLayers;
    std::vector<const char*> enabledExts;
    bool validation = false;
    if (cfg.enableValidation && hasLayer(layers, "VK_LAYER_KHRONOS_validation")) {
        enabledLayers.push_back("VK_LAYER_KHRONOS_validation");
        validation = true;
    }
    // Enabled whenever it exists (it is cheap), so object names and labels reach RenderDoc and AGI; only the message callback needs validation.
    const bool debugUtils = hasExt(instExts, VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    if (debugUtils) enabledExts.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    // Surface extensions, when the loader has them, so the swapchain device extension below is valid.
    // The platform extension is the one that matches the window-system handle Java passes to createSurface.
    bool surfaceExts = false, surfaceMaint = false;
    if (hasExt(instExts, VK_KHR_SURFACE_EXTENSION_NAME)) {
#if defined(VK_USE_PLATFORM_WIN32_KHR)
        const char* platformSurface = VK_KHR_WIN32_SURFACE_EXTENSION_NAME;
#elif defined(VK_USE_PLATFORM_ANDROID_KHR)
        const char* platformSurface = VK_KHR_ANDROID_SURFACE_EXTENSION_NAME;
#elif defined(VK_USE_PLATFORM_XLIB_KHR)
        const char* platformSurface = VK_KHR_XLIB_SURFACE_EXTENSION_NAME;
#else
        const char* platformSurface = nullptr;
#endif
        if (platformSurface && hasExt(instExts, platformSurface)) {
            enabledExts.push_back(VK_KHR_SURFACE_EXTENSION_NAME);
            enabledExts.push_back(platformSurface);
            surfaceExts = true;
            // Present fences (swapchain_maintenance1) need these two on the instance.
            if (hasExt(instExts, VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME) && hasExt(instExts, VK_KHR_SURFACE_MAINTENANCE_1_EXTENSION_NAME)) {
                enabledExts.push_back(VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME);
                enabledExts.push_back(VK_KHR_SURFACE_MAINTENANCE_1_EXTENSION_NAME);
                surfaceMaint = true;
            }
        }
    }
    // Portability enumeration is needed for MoltenVK (Phase 6); harmless elsewhere when present.
    VkInstanceCreateFlags instFlags = 0;
    if (hasExt(instExts, VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)) {
        enabledExts.push_back(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
        instFlags |= VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
    }

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = cfg.appName.c_str();
    app.pEngineName = "gdx-vulkan";
    app.engineVersion = 1;
    app.apiVersion = instanceApi;

    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.flags = instFlags;
    ici.pApplicationInfo = &app;
    ici.enabledLayerCount = static_cast<uint32_t>(enabledLayers.size());
    ici.ppEnabledLayerNames = enabledLayers.data();
    ici.enabledExtensionCount = static_cast<uint32_t>(enabledExts.size());
    ici.ppEnabledExtensionNames = enabledExts.data();
    const VkResult ir = vkCreateInstance(&ici, nullptr, &s.instance);
    if (ir != VK_SUCCESS) {
        // INCOMPATIBLE_DRIVER is what the loader returns when it finds no usable ICD.
        errorKind = (ir == VK_ERROR_INCOMPATIBLE_DRIVER) ? "device" : "init";
        error = "vkCreateInstance failed (VkResult " + std::to_string(static_cast<int>(ir)) + ")" +
                (ir == VK_ERROR_INCOMPATIBLE_DRIVER ? ": no Vulkan driver (ICD) installed" : "");
        s.instance = VK_NULL_HANDLE;
        return nullptr;
    }
    volkLoadInstanceOnly(s.instance);

    s.debugUtils = debugUtils;
    if (debugUtils && validation) {
        VkDebugUtilsMessengerCreateInfoEXT mci{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
        mci.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
        mci.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                          VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
        mci.pfnUserCallback = debugCallback;
        mci.pUserData = &s.validationMessages;
        vkCreateDebugUtilsMessengerEXT(s.instance, &mci, nullptr, &s.messenger);
    }

    // ---- Physical device ---------------------------------------------------------------------
    uint32_t pdCount = 0;
    vkEnumeratePhysicalDevices(s.instance, &pdCount, nullptr);
    if (pdCount == 0) {
        errorKind = "device";
        error = "No Vulkan physical devices found (no ICD installed?)";
        return nullptr;
    }
    std::vector<VkPhysicalDevice> pds(pdCount);
    vkEnumeratePhysicalDevices(s.instance, &pdCount, pds.data());

    int best = -1, bestRank = -1;
    for (uint32_t i = 0; i < pdCount; ++i) {
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(pds[i], &p);
        if (p.apiVersion < VK_API_VERSION_1_1) continue; // gdx-vulkan requires 1.1 minimum
        if (cfg.preferredDeviceIndex >= 0) {
            if (static_cast<int>(i) == cfg.preferredDeviceIndex) { best = static_cast<int>(i); break; }
            continue;
        }
        const int r = deviceRank(p.deviceType);
        if (r > bestRank) { bestRank = r; best = static_cast<int>(i); }
    }
    if (best < 0) {
        errorKind = "device";
        error = cfg.preferredDeviceIndex >= 0 ? "Requested device index not available or below Vulkan 1.1"
                                              : "No Vulkan 1.1+ physical device found";
        return nullptr;
    }
    s.physical = pds[best];

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(s.physical, &props);
    const uint32_t effective = std::min(props.apiVersion, instanceApi) & ~0xFFFu; // strip patch

    // ---- Feature / property queries (only chain structs the effective version knows) --------
    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    VkPhysicalDeviceVulkan12Properties p12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_PROPERTIES};

    TierInputs ti;
    ti.effectiveApiVersion = effective;
    ti.forceTierB = cfg.forceTierB;
    ti.maxPerStageDescriptorSampledImages = props.limits.maxPerStageDescriptorSampledImages;
    ti.maxPerStageDescriptorSamplers = props.limits.maxPerStageDescriptorSamplers;

    std::string driverInfo;
    if (effective >= VK_API_VERSION_1_2) {
        VkPhysicalDeviceFeatures2 feats{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        f12.pNext = (effective >= VK_API_VERSION_1_3) ? static_cast<void*>(&f13) : nullptr;
        feats.pNext = &f12;
        vkGetPhysicalDeviceFeatures2(s.physical, &feats);

        VkPhysicalDeviceProperties2 pr{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        pr.pNext = &p12;
        vkGetPhysicalDeviceProperties2(s.physical, &pr);

        ti.descriptorBindingPartiallyBound = f12.descriptorBindingPartiallyBound;
        ti.descriptorBindingSampledImageUpdateAfterBind = f12.descriptorBindingSampledImageUpdateAfterBind;
        ti.descriptorBindingUpdateUnusedWhilePending = f12.descriptorBindingUpdateUnusedWhilePending;
        ti.shaderSampledImageArrayNonUniformIndexing = f12.shaderSampledImageArrayNonUniformIndexing;
        ti.runtimeDescriptorArray = f12.runtimeDescriptorArray;
        ti.dynamicRendering = (effective >= VK_API_VERSION_1_3) && f13.dynamicRendering;
        ti.maxPerStageDescriptorUpdateAfterBindSampledImages = p12.maxPerStageDescriptorUpdateAfterBindSampledImages;
        ti.maxPerStageDescriptorUpdateAfterBindSamplers = p12.maxPerStageDescriptorUpdateAfterBindSamplers;
        ti.maxDescriptorSetUpdateAfterBindSampledImages = p12.maxDescriptorSetUpdateAfterBindSampledImages;
        ti.maxDescriptorSetUpdateAfterBindSamplers = p12.maxDescriptorSetUpdateAfterBindSamplers;
        driverInfo = std::string(p12.driverName) + " " + p12.driverInfo;
    }

    // ---- Queue family: graphics + compute (presentation support is checked once a surface exists)
    uint32_t qfCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(s.physical, &qfCount, nullptr);
    std::vector<VkQueueFamilyProperties> qfs(qfCount);
    vkGetPhysicalDeviceQueueFamilyProperties(s.physical, &qfCount, qfs.data());
    int gfx = -1;
    for (uint32_t i = 0; i < qfCount; ++i) {
        if (qfs[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { gfx = static_cast<int>(i); break; }
    }
    if (gfx < 0) {
        errorKind = "device";
        error = "Selected device has no graphics queue family";
        return nullptr;
    }

    // ---- Device ------------------------------------------------------------------------------
    const TierDecision tier = decideTier(ti);

    VkPhysicalDeviceVulkan12Features en12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceVulkan13Features en13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    VkPhysicalDeviceFeatures2 enFeats{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    VkPhysicalDeviceFeatures supported;
    vkGetPhysicalDeviceFeatures(s.physical, &supported);
    enFeats.features.samplerAnisotropy = supported.samplerAnisotropy;
    enFeats.features.fillModeNonSolid = supported.fillModeNonSolid; // wireframe in debug tools
    // GPU completion tracking: a timeline semaphore on Vulkan 1.2+, one fence per submit otherwise. forceTierB also takes the
    // fence path so CI exercises the Vulkan 1.1 behaviour on any device.
    const bool timelineSupported = effective >= VK_API_VERSION_1_2 && f12.timelineSemaphore;
    bool useTimeline = timelineSupported && !cfg.forceTierB;
    if (const char* mode = std::getenv("GDXVK_COMPLETION")) { // diagnostics: "fence" or "timeline" overrides the choice above
        if (std::strcmp(mode, "fence") == 0) useTimeline = false;
        else if (std::strcmp(mode, "timeline") == 0) useTimeline = timelineSupported;
    }
    if (effective >= VK_API_VERSION_1_2) {
        en12.timelineSemaphore = useTimeline ? VK_TRUE : VK_FALSE;
        enFeats.pNext = &en12;
    }
    if (tier.tier == Tier::A) {
        en12.descriptorIndexing = VK_TRUE;
        en12.descriptorBindingPartiallyBound = VK_TRUE;
        en12.descriptorBindingSampledImageUpdateAfterBind = VK_TRUE;
        en12.descriptorBindingUpdateUnusedWhilePending = VK_TRUE;
        en12.shaderSampledImageArrayNonUniformIndexing = VK_TRUE;
        en12.runtimeDescriptorArray = VK_TRUE;
        en13.dynamicRendering = VK_TRUE;
        en12.pNext = &en13;
    }

    const float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = static_cast<uint32_t>(gfx);
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;

    // VK_KHR_swapchain needs the instance surface extensions; headless runs (lavapipe CI smoke test)
    // work without it.
    uint32_t devExtCount = 0;
    vkEnumerateDeviceExtensionProperties(s.physical, nullptr, &devExtCount, nullptr);
    std::vector<VkExtensionProperties> devExts(devExtCount);
    vkEnumerateDeviceExtensionProperties(s.physical, nullptr, &devExtCount, devExts.data());
    std::vector<const char*> enabledDevExts;
    if (surfaceExts && hasExt(devExts, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) enabledDevExts.push_back(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    if (hasExt(devExts, "VK_KHR_portability_subset")) enabledDevExts.push_back("VK_KHR_portability_subset"); // required when exposed
    std::vector<std::string> swappyExts; // storage for the names below until vkCreateDevice
    if (surfaceExts && hasExt(devExts, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
        swappy::deviceExtensions(s.physical, devExts, swappyExts); // VK_GOOGLE_display_timing, when built with Swappy
        for (const std::string& e : swappyExts) {
            if (hasExt(devExts, e.c_str())) enabledDevExts.push_back(e.c_str());
        }
    }
    VkPhysicalDeviceSwapchainMaintenance1FeaturesKHR maint1{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SWAPCHAIN_MAINTENANCE_1_FEATURES_KHR};
    bool presentFences = false;
    if (surfaceMaint && surfaceExts && hasExt(devExts, VK_KHR_SWAPCHAIN_EXTENSION_NAME) && hasExt(devExts, VK_KHR_SWAPCHAIN_MAINTENANCE_1_EXTENSION_NAME) &&
        !std::getenv("GDXVK_NO_PRESENT_FENCES")) {
        VkPhysicalDeviceFeatures2 q{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        q.pNext = &maint1;
        vkGetPhysicalDeviceFeatures2(s.physical, &q);
        if (maint1.swapchainMaintenance1) {
            maint1.pNext = enFeats.pNext;
            enFeats.pNext = &maint1;
            enabledDevExts.push_back(VK_KHR_SWAPCHAIN_MAINTENANCE_1_EXTENSION_NAME);
            presentFences = true;
        }
    }

    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &enFeats;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = static_cast<uint32_t>(enabledDevExts.size());
    dci.ppEnabledExtensionNames = enabledDevExts.data();
    if (vkCreateDevice(s.physical, &dci, nullptr, &s.device) != VK_SUCCESS) {
        errorKind = "init";
        error = "vkCreateDevice failed";
        return nullptr;
    }
    volkLoadDevice(s.device);
    vkGetDeviceQueue(s.device, static_cast<uint32_t>(gfx), 0, &s.graphicsQueue);
    s.graphicsQueueFamily = static_cast<uint32_t>(gfx);
    s.tierA = tier.tier == Tier::A;
    s.swapchainEnabled = surfaceExts && hasExt(devExts, VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    s.presentFences = presentFences;

    // ---- VMA ---------------------------------------------------------------------------------
    VmaVulkanFunctions fns{};
    fns.vkGetInstanceProcAddr = vkGetInstanceProcAddr;
    fns.vkGetDeviceProcAddr = vkGetDeviceProcAddr;
    VmaAllocatorCreateInfo aci{};
    aci.physicalDevice = s.physical;
    aci.device = s.device;
    aci.instance = s.instance;
    aci.vulkanApiVersion = effective;
    aci.pVulkanFunctions = &fns;
    if (vmaCreateAllocator(&aci, &s.allocator) != VK_SUCCESS) {
        errorKind = "init";
        error = "vmaCreateAllocator failed";
        return nullptr;
    }

    // ---- Device-level services (Phase 1b) ---------------------------------------------------------------
    s.limits = props.limits;
    s.effectiveApi = effective;
    s.timeline = new Timeline(s, useTimeline);
    s.deletion = new DeletionQueue(*s.timeline);
    s.samplers = new SamplerCache(s, supported.samplerAnisotropy == VK_TRUE);
    s.pipelineCache = new PipelineCacheStore(s, cfg.pipelineCachePath);
    std::string layoutError;
    s.layouts = DescriptorLayouts::create(s, tier.textureTableSize, layoutError);
    if (!s.layouts) {
        errorKind = "init";
        error = layoutError;
        return nullptr;
    }

    s.staging = new StagingRing(s, 16u << 20);
    s.resources = new Resources(s);

    // ---- Report ------------------------------------------------------------------------------
    DeviceInfo& di = s.info;
    di.deviceName = props.deviceName;
    di.driverInfo = driverInfo;
    di.vendorId = props.vendorID;
    di.deviceId = props.deviceID;
    di.deviceApiVersion = props.apiVersion;
    di.effectiveApiVersion = effective;
    di.deviceType = static_cast<uint32_t>(props.deviceType);
    di.validationEnabled = validation;
    di.tier = tier;
    di.maxImageDimension2D = props.limits.maxImageDimension2D;
    di.maxSamplerAnisotropy = props.limits.maxSamplerAnisotropy;
    di.maxPushConstantsSize = props.limits.maxPushConstantsSize;
    di.minUniformBufferOffsetAlignment = static_cast<uint32_t>(props.limits.minUniformBufferOffsetAlignment);
    di.minStorageBufferOffsetAlignment = static_cast<uint32_t>(props.limits.minStorageBufferOffsetAlignment);
    di.graphicsQueueFamily = static_cast<uint32_t>(gfx);
    di.maxUniformBufferRange = props.limits.maxUniformBufferRange;
    di.timelineSemaphores = s.timeline->usesSemaphore();
    di.pipelineCacheLoadedBytes = static_cast<uint32_t>(s.pipelineCache->loadedBytes());

    // The plan fixes the push-constant block at 128 bytes (Vulkan guarantees >= 128).
    if (di.maxPushConstantsSize < 128) {
        errorKind = "device";
        error = "Device maxPushConstantsSize < 128";
        return nullptr;
    }
    return ctx;
}

} // namespace gdxvk
