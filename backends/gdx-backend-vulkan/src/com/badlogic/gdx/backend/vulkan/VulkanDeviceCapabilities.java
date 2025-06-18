package com.badlogic.gdx.backend.vulkan; // Or your specific package

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.EXTDescriptorIndexing.*; // For VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME and struct type
import static org.lwjgl.vulkan.VK11.*; // For VK_API_VERSION_1_1, vkGetPhysicalDeviceFeatures2, and struct types
import static org.lwjgl.vulkan.VK12.*; // For VK_API_VERSION_1_2 and struct types
import static org.lwjgl.vulkan.VK13.*; // For VK_API_VERSION_1_3 and struct types

import com.badlogic.gdx.Gdx; // For logging
import org.lwjgl.vulkan.VkBaseOutStructure;

/**
 * Queries and stores various capabilities and limits of a Vulkan PhysicalDevice.
 * This class handles querying core features from Vulkan 1.0, 1.1, 1.2, 1.3,
 * and specific extensions like VK_EXT_descriptor_indexing.
 * It also queries properties like VkPhysicalDeviceMaintenance4Properties.
 */
public class VulkanDeviceCapabilities {

    private static final String TAG = "VulkanDeviceCapabilities";
    private static final boolean DEBUG_LOGGING = true;

    // --- API Version ---
    private final int apiVersion;
    private final String apiVersionString;

    // --- Persistent Properties and Limits ---
    private final VkPhysicalDeviceProperties persistentProperties; // Heap-allocated copy of core V1.0 properties
    private final VkPhysicalDeviceLimits limits; // View into persistentProperties.limits()

    // --- Vulkan 1.3 Maintenance4 Properties ---
    private final long maxBufferSize; // From VkPhysicalDeviceMaintenance4Properties

    // --- Individual Limit Fields (populated from this.limits for convenience) ---
    private final int maxImageDimension1D;
    private final int maxImageDimension2D;
    private final int maxImageDimension3D;
    private final int maxImageDimensionCube;
    private final int maxImageArrayLayers;
    private final long maxTexelBufferElements;
    private final long maxUniformBufferRange;
    private final long maxStorageBufferRange;
    private final int maxPushConstantsSize;
    private final int maxMemoryAllocationCount;
    private final int maxSamplerAllocationCount;
    private final long bufferImageGranularity;
    private final long sparseAddressSpaceSize;
    private final int maxBoundDescriptorSets;
    private final int maxPerStageDescriptorSamplers;
    private final int maxPerStageDescriptorUniformBuffers;
    private final int maxPerStageDescriptorStorageBuffers;
    private final int maxPerStageDescriptorSampledImages;
    private final int maxPerStageDescriptorStorageImages;
    private final int maxPerStageDescriptorInputAttachments;
    private final int maxPerStageResources;
    private final int maxDescriptorSetSamplers;
    private final int maxDescriptorSetUniformBuffers;
    private final int maxDescriptorSetUniformBuffersDynamic;
    private final int maxDescriptorSetStorageBuffers;
    private final int maxDescriptorSetStorageBuffersDynamic;
    private final int maxDescriptorSetSampledImages;
    private final int maxDescriptorSetStorageImages;
    private final int maxDescriptorSetInputAttachments;
    private final int maxVertexInputAttributes;
    private final int maxVertexInputBindings;
    private final int maxVertexInputAttributeOffset;
    private final int maxVertexInputBindingStride;
    private final int maxVertexOutputComponents;

    // --- Vulkan 1.0 Core Feature Flags ---
    private final boolean robustBufferAccess;
    private final boolean fullDrawIndexUint32;
    private final boolean imageCubeArray;
    private final boolean independentBlend;
    private final boolean geometryShader;
    private final boolean tessellationShader;
    private final boolean sampleRateShading;
    private final boolean dualSrcBlend;
    private final boolean logicOp;
    private final boolean multiDrawIndirect;
    private final boolean drawIndirectFirstInstance;
    private final boolean depthClamp;
    private final boolean depthBiasClamp;
    private final boolean fillModeNonSolid;
    private final boolean depthBounds;
    private final boolean wideLines;
    private final boolean largePoints;
    private final boolean alphaToOne;
    private final boolean multiViewport;
    private final boolean samplerAnisotropy;
    private final boolean textureCompressionETC2;
    private final boolean textureCompressionASTC_LDR;
    private final boolean textureCompressionBC;
    private final boolean occlusionQueryPrecise;
    private final boolean pipelineStatisticsQuery;
    private final boolean vertexPipelineStoresAndAtomics;
    private final boolean fragmentStoresAndAtomics;
    private final boolean shaderTessellationAndGeometryPointSize;
    private final boolean shaderImageGatherExtended;
    private final boolean shaderStorageImageExtendedFormats;
    private final boolean shaderStorageImageMultisample;
    private final boolean shaderStorageImageReadWithoutFormat;
    private final boolean shaderStorageImageWriteWithoutFormat;
    private final boolean shaderUniformBufferArrayDynamicIndexing;
    private final boolean shaderSampledImageArrayDynamicIndexing;
    private final boolean shaderStorageBufferArrayDynamicIndexing;
    private final boolean shaderStorageImageArrayDynamicIndexing;
    private final boolean shaderClipDistance;
    private final boolean shaderCullDistance;
    private final boolean shaderFloat64;
    private final boolean shaderInt64;
    private final boolean shaderInt16;
    private final boolean shaderResourceResidency;
    private final boolean shaderResourceMinLod;
    private final boolean sparseBinding;
    private final boolean sparseResidencyBuffer;
    private final boolean sparseResidencyImage2D;
    private final boolean sparseResidencyImage3D;
    private final boolean sparseResidency2Samples;
    private final boolean sparseResidency4Samples;
    private final boolean sparseResidency8Samples;
    private final boolean sparseResidency16Samples;
    private final boolean sparseResidencyAliased;
    private final boolean variableMultisampleRate;
    private final boolean inheritedQueries;

    // --- Vulkan 1.1 Core Feature Flags ---
    private final boolean storageBuffer16BitAccess;
    private final boolean uniformAndStorageBuffer16BitAccess;
    private final boolean storagePushConstant16;
    private final boolean storageInputOutput16;
    private final boolean multiview;
    private final boolean multiviewGeometryShader;
    private final boolean multiviewTessellationShader;
    private final boolean variablePointersStorageBuffer;
    private final boolean variablePointers;
    private final boolean protectedMemory;
    private final boolean samplerYcbcrConversion;
    private final boolean shaderDrawParameters;

    // --- Vulkan 1.2 Core Feature Flags (includes Descriptor Indexing core features) ---
    private final boolean samplerMirrorClampToEdge;
    private final boolean drawIndirectCount;
    private final boolean storageBuffer8BitAccess;
    private final boolean uniformAndStorageBuffer8BitAccess;
    private final boolean storagePushConstant8;
    private final boolean shaderBufferInt64Atomics;
    private final boolean shaderSharedInt64Atomics;
    private final boolean shaderFloat16;
    private final boolean shaderInt8;
    private final boolean descriptorIndexing; // Combined flag indicating overall DI support
    private final boolean shaderInputAttachmentArrayDynamicIndexing;
    private final boolean shaderUniformTexelBufferArrayDynamicIndexing;
    private final boolean shaderStorageTexelBufferArrayDynamicIndexing;
    private final boolean shaderUniformBufferArrayNonUniformIndexing;
    private final boolean shaderSampledImageArrayNonUniformIndexing;
    private final boolean shaderStorageBufferArrayNonUniformIndexing;
    private final boolean shaderStorageImageArrayNonUniformIndexing;
    private final boolean shaderInputAttachmentArrayNonUniformIndexing;
    private final boolean shaderUniformTexelBufferArrayNonUniformIndexing;
    private final boolean shaderStorageTexelBufferArrayNonUniformIndexing;
    private final boolean descriptorBindingUniformBufferUpdateAfterBind;
    private final boolean descriptorBindingSampledImageUpdateAfterBind;
    private final boolean descriptorBindingStorageImageUpdateAfterBind;
    private final boolean descriptorBindingStorageBufferUpdateAfterBind;
    private final boolean descriptorBindingUniformTexelBufferUpdateAfterBind;
    private final boolean descriptorBindingStorageTexelBufferUpdateAfterBind;
    private final boolean descriptorBindingUpdateUnusedWhilePending;
    private final boolean descriptorBindingPartiallyBound;
    private final boolean descriptorBindingVariableDescriptorCount;
    private final boolean runtimeDescriptorArray;
    private final boolean samplerFilterMinmax;
    private final boolean scalarBlockLayout;
    private final boolean imagelessFramebuffer;
    private final boolean uniformBufferStandardLayout;
    private final boolean shaderSubgroupExtendedTypes;
    private final boolean separateDepthStencilLayouts;
    private final boolean hostQueryReset;
    private final boolean timelineSemaphore;
    private final boolean bufferDeviceAddress;
    private final boolean bufferDeviceAddressCaptureReplay;
    private final boolean bufferDeviceAddressMultiDevice;
    private final boolean vulkanMemoryModel;
    private final boolean vulkanMemoryModelDeviceScope;
    private final boolean vulkanMemoryModelAvailabilityVisibilityChains;
    private final boolean shaderOutputViewportIndex;
    private final boolean shaderOutputLayer;
    private final boolean subgroupBroadcastDynamicId;

    // --- Vulkan 1.3 Core Feature Flags ---
    private final boolean robustImageAccess;
    private final boolean inlineUniformBlock;
    private final boolean descriptorBindingInlineUniformBlockUpdateAfterBind;
    private final boolean pipelineCreationCacheControl;
    private final boolean privateData;
    private final boolean shaderDemoteToHelperInvocation;
    private final boolean shaderTerminateInvocation;
    private final boolean subgroupSizeControl;
    private final boolean computeFullSubgroups;
    private final boolean synchronization2;
    private final boolean textureCompressionASTC_HDR;
    private final boolean shaderZeroInitializeWorkgroupMemory;
    private final boolean dynamicRendering;
    private final boolean shaderIntegerDotProduct;
    private final boolean maintenance4; // This is the FEATURE flag


    public VulkanDeviceCapabilities(VkPhysicalDevice physicalDevice) {
        if (physicalDevice == null) {
            throw new IllegalArgumentException("PhysicalDevice cannot be null.");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Query base properties (Vulkan 1.0 way) to get apiVersion first
            // This is needed to decide which higher-version property/feature structs to chain.
            VkPhysicalDeviceProperties stackCoreProperties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, stackCoreProperties);
            this.apiVersion = stackCoreProperties.apiVersion(); // Device's max supported API
            this.apiVersionString = String.format("%d.%d.%d",
                    VK_API_VERSION_MAJOR(this.apiVersion),
                    VK_API_VERSION_MINOR(this.apiVersion),
                    VK_API_VERSION_PATCH(this.apiVersion));

            // +++ ADDED LOGGING +++
            logMsg("Initializing for device: " + stackCoreProperties.deviceNameString());
            logMsg("Device API Version: " + this.apiVersionString);
            // +++ END LOGGING +++

            // 2. Prepare pNext chain for vkGetPhysicalDeviceProperties2
            VkPhysicalDeviceMaintenance4Properties maintenance4Properties = null;
            if (this.apiVersion >= VK_API_VERSION_1_3) {
                logMsg("Device supports Vulkan 1.3+, preparing to query Maintenance4 properties."); // +++ LOGGING +++
                maintenance4Properties = VkPhysicalDeviceMaintenance4Properties.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_4_PROPERTIES);
            } else {
                logMsg("Device does not support Vulkan 1.3. Skipping Maintenance4 properties query."); // +++ LOGGING +++
            }

            // Create VkPhysicalDeviceProperties2 and link the property chain head
            VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2);
            if (maintenance4Properties != null) { // If we allocated it, link it
                properties2.pNext(maintenance4Properties.address());
            }

            // Call vkGetPhysicalDeviceProperties2 to populate properties2 and any chained structs
            VK11.vkGetPhysicalDeviceProperties2(physicalDevice, properties2);
            logMsg("vkGetPhysicalDeviceProperties2 called."); // +++ LOGGING +++

            // Store persistent copy of core properties
            this.persistentProperties = VkPhysicalDeviceProperties.calloc(); // Heap allocation
            MemoryUtil.memCopy(properties2.properties().address(), this.persistentProperties.address(), VkPhysicalDeviceProperties.SIZEOF);
            this.limits = this.persistentProperties.limits();

            // Populate maxBufferSize from maintenance4Properties if it was queried and filled
            long finalMaxBufferSize = 0;
            if (maintenance4Properties != null && maintenance4Properties.sType() == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_4_PROPERTIES) {
                finalMaxBufferSize = maintenance4Properties.maxBufferSize();
                // +++ ADDED LOGGING +++
                logMsg("Raw value from VkPhysicalDeviceMaintenance4Properties.maxBufferSize: " + finalMaxBufferSize);
            } else {
                // +++ ADDED LOGGING +++
                logMsg("Maintenance4 properties were not queried or are invalid. maxBufferSize will be 0.");
            }
            this.maxBufferSize = finalMaxBufferSize;

            // +++ This is the line you saw in your log, now we have context for it.
            if (DEBUG_LOGGING && Gdx.app != null) Gdx.app.log(TAG, "Queried VkPhysicalDeviceMaintenance4Properties.maxBufferSize: " + this.maxBufferSize);


            // Populate individual limit fields for convenience
            this.maxImageDimension1D = this.limits.maxImageDimension1D();
            this.maxImageDimension2D = this.limits.maxImageDimension2D();
            this.maxImageDimension3D = this.limits.maxImageDimension3D();
            this.maxImageDimensionCube = this.limits.maxImageDimensionCube();
            this.maxImageArrayLayers = this.limits.maxImageArrayLayers();
            this.maxTexelBufferElements = this.limits.maxTexelBufferElements();
            this.maxUniformBufferRange = this.limits.maxUniformBufferRange();
            this.maxStorageBufferRange = this.limits.maxStorageBufferRange();
            this.maxPushConstantsSize = this.limits.maxPushConstantsSize();
            this.maxMemoryAllocationCount = this.limits.maxMemoryAllocationCount();
            this.maxSamplerAllocationCount = this.limits.maxSamplerAllocationCount();
            this.bufferImageGranularity = this.limits.bufferImageGranularity();
            this.sparseAddressSpaceSize = this.limits.sparseAddressSpaceSize();
            this.maxBoundDescriptorSets = this.limits.maxBoundDescriptorSets();
            this.maxPerStageDescriptorSamplers = this.limits.maxPerStageDescriptorSamplers();
            this.maxPerStageDescriptorUniformBuffers = this.limits.maxPerStageDescriptorUniformBuffers();
            this.maxPerStageDescriptorStorageBuffers = this.limits.maxPerStageDescriptorStorageBuffers();
            this.maxPerStageDescriptorSampledImages = this.limits.maxPerStageDescriptorSampledImages();
            this.maxPerStageDescriptorStorageImages = this.limits.maxPerStageDescriptorStorageImages();
            this.maxPerStageDescriptorInputAttachments = this.limits.maxPerStageDescriptorInputAttachments();
            this.maxPerStageResources = this.limits.maxPerStageResources();
            this.maxDescriptorSetSamplers = this.limits.maxDescriptorSetSamplers();
            this.maxDescriptorSetUniformBuffers = this.limits.maxDescriptorSetUniformBuffers();
            this.maxDescriptorSetUniformBuffersDynamic = this.limits.maxDescriptorSetUniformBuffersDynamic();
            this.maxDescriptorSetStorageBuffers = this.limits.maxDescriptorSetStorageBuffers();
            this.maxDescriptorSetStorageBuffersDynamic = this.limits.maxDescriptorSetStorageBuffersDynamic();
            this.maxDescriptorSetSampledImages = this.limits.maxDescriptorSetSampledImages();
            this.maxDescriptorSetStorageImages = this.limits.maxDescriptorSetStorageImages();
            this.maxDescriptorSetInputAttachments = this.limits.maxDescriptorSetInputAttachments();
            this.maxVertexInputAttributes = this.limits.maxVertexInputAttributes();
            this.maxVertexInputBindings = this.limits.maxVertexInputBindings();
            this.maxVertexInputAttributeOffset = this.limits.maxVertexInputAttributeOffset();
            this.maxVertexInputBindingStride = this.limits.maxVertexInputBindingStride();
            this.maxVertexOutputComponents = this.limits.maxVertexOutputComponents();

            // 3. Prepare feature structs for pNext chain for vkGetPhysicalDeviceFeatures2
            VkPhysicalDeviceVulkan13Features features13 = null;
            if (this.apiVersion >= VK_API_VERSION_1_3) {
                features13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES);
            }
            VkPhysicalDeviceVulkan12Features features12 = null;
            if (this.apiVersion >= VK_API_VERSION_1_2) {
                features12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES);
            }
            VkPhysicalDeviceVulkan11Features features11 = null;
            if (this.apiVersion >= VK_API_VERSION_1_1) {
                features11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES);
            }

            VkPhysicalDeviceDescriptorIndexingFeatures extDescriptorIndexingFeatures = null;
            boolean descriptorIndexingExtensionPresent = false;
            if (this.apiVersion < VK_API_VERSION_1_2) { // Check for EXT only if core 1.2 is not available
                IntBuffer pPropertyCount = stack.mallocInt(1);
                vkEnumerateDeviceExtensionProperties(physicalDevice, (CharSequence) null, pPropertyCount, null);
                int extCount = pPropertyCount.get(0);
                if (extCount > 0) {
                    VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.malloc(extCount, stack); // Stack allocation for extension names is usually fine
                    vkEnumerateDeviceExtensionProperties(physicalDevice, (CharSequence) null, pPropertyCount, availableExtensions);
                    for (int i = 0; i < extCount; i++) {
                        if (VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME.equals(availableExtensions.get(i).extensionNameString())) {
                            descriptorIndexingExtensionPresent = true;
                            break;
                        }
                    }
                }
                if (descriptorIndexingExtensionPresent) {
                    extDescriptorIndexingFeatures = VkPhysicalDeviceDescriptorIndexingFeatures.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES_EXT);
                }
            }

            // 4. Build the pNext chain for FEATURES query (in reverse order of linking)
            long currentFeatureChainHead = NULL;
            if (extDescriptorIndexingFeatures != null) {
                extDescriptorIndexingFeatures.pNext(currentFeatureChainHead);
                currentFeatureChainHead = extDescriptorIndexingFeatures.address();
            }
            if (features11 != null) {
                features11.pNext(currentFeatureChainHead);
                currentFeatureChainHead = features11.address();
            }
            if (features12 != null) {
                features12.pNext(currentFeatureChainHead);
                currentFeatureChainHead = features12.address();
            }
            if (features13 != null) {
                features13.pNext(currentFeatureChainHead);
                currentFeatureChainHead = features13.address();
            }

            VkPhysicalDeviceFeatures2 features2Query = VkPhysicalDeviceFeatures2.calloc(stack);
            features2Query.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2);
            features2Query.pNext(currentFeatureChainHead); // Link the constructed chain to features2Query

            // 5. Query features using vkGetPhysicalDeviceFeatures2 or KHR equivalent
            VkInstance instance = physicalDevice.getInstance();
            VKCapabilitiesInstance capabilitiesInstance = instance.getCapabilities();
            if (capabilitiesInstance.vkGetPhysicalDeviceFeatures2 != NULL) {
                if (DEBUG_LOGGING && Gdx.app != null) Gdx.app.log(TAG, "Using core vkGetPhysicalDeviceFeatures2 for feature query.");
                VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, features2Query);
            } else if (capabilitiesInstance.vkGetPhysicalDeviceFeatures2KHR != NULL) {
                if (DEBUG_LOGGING && Gdx.app != null) Gdx.app.log(TAG, "Using KHR vkGetPhysicalDeviceFeatures2KHR for feature query.");
                KHRGetPhysicalDeviceProperties2.vkGetPhysicalDeviceFeatures2KHR(physicalDevice, features2Query);
            } else {
                if (DEBUG_LOGGING && Gdx.app != null)
                    Gdx.app.error(TAG, "Neither core vkGetPhysicalDeviceFeatures2 nor KHR version is available for extended feature query. Querying only base V1.0 features.");
                vkGetPhysicalDeviceFeatures(physicalDevice, features2Query.features()); // Populate only V1.0 features into features2Query.features()
            }

            // 6. Populate boolean feature flags from the queried structs
            VkPhysicalDeviceFeatures coreDeviceFeatures = features2Query.features();
            this.robustBufferAccess = coreDeviceFeatures.robustBufferAccess();
            this.fullDrawIndexUint32 = coreDeviceFeatures.fullDrawIndexUint32();
            this.imageCubeArray = coreDeviceFeatures.imageCubeArray();
            this.independentBlend = coreDeviceFeatures.independentBlend();
            this.geometryShader = coreDeviceFeatures.geometryShader();
            this.tessellationShader = coreDeviceFeatures.tessellationShader();
            this.sampleRateShading = coreDeviceFeatures.sampleRateShading();
            this.dualSrcBlend = coreDeviceFeatures.dualSrcBlend();
            this.logicOp = coreDeviceFeatures.logicOp();
            this.multiDrawIndirect = coreDeviceFeatures.multiDrawIndirect();
            this.drawIndirectFirstInstance = coreDeviceFeatures.drawIndirectFirstInstance();
            this.depthClamp = coreDeviceFeatures.depthClamp();
            this.depthBiasClamp = coreDeviceFeatures.depthBiasClamp();
            this.fillModeNonSolid = coreDeviceFeatures.fillModeNonSolid();
            this.depthBounds = coreDeviceFeatures.depthBounds();
            this.wideLines = coreDeviceFeatures.wideLines();
            this.largePoints = coreDeviceFeatures.largePoints();
            this.alphaToOne = coreDeviceFeatures.alphaToOne();
            this.multiViewport = coreDeviceFeatures.multiViewport();
            this.samplerAnisotropy = coreDeviceFeatures.samplerAnisotropy();
            this.textureCompressionETC2 = coreDeviceFeatures.textureCompressionETC2();
            this.textureCompressionASTC_LDR = coreDeviceFeatures.textureCompressionASTC_LDR();
            this.textureCompressionBC = coreDeviceFeatures.textureCompressionBC();
            this.occlusionQueryPrecise = coreDeviceFeatures.occlusionQueryPrecise();
            this.pipelineStatisticsQuery = coreDeviceFeatures.pipelineStatisticsQuery();
            this.vertexPipelineStoresAndAtomics = coreDeviceFeatures.vertexPipelineStoresAndAtomics();
            this.fragmentStoresAndAtomics = coreDeviceFeatures.fragmentStoresAndAtomics();
            this.shaderTessellationAndGeometryPointSize = coreDeviceFeatures.shaderTessellationAndGeometryPointSize();
            this.shaderImageGatherExtended = coreDeviceFeatures.shaderImageGatherExtended();
            this.shaderStorageImageExtendedFormats = coreDeviceFeatures.shaderStorageImageExtendedFormats();
            this.shaderStorageImageMultisample = coreDeviceFeatures.shaderStorageImageMultisample();
            this.shaderStorageImageReadWithoutFormat = coreDeviceFeatures.shaderStorageImageReadWithoutFormat();
            this.shaderStorageImageWriteWithoutFormat = coreDeviceFeatures.shaderStorageImageWriteWithoutFormat();
            this.shaderUniformBufferArrayDynamicIndexing = coreDeviceFeatures.shaderUniformBufferArrayDynamicIndexing();
            this.shaderSampledImageArrayDynamicIndexing = coreDeviceFeatures.shaderSampledImageArrayDynamicIndexing();
            this.shaderStorageBufferArrayDynamicIndexing = coreDeviceFeatures.shaderStorageBufferArrayDynamicIndexing();
            this.shaderStorageImageArrayDynamicIndexing = coreDeviceFeatures.shaderStorageImageArrayDynamicIndexing();
            this.shaderClipDistance = coreDeviceFeatures.shaderClipDistance();
            this.shaderCullDistance = coreDeviceFeatures.shaderCullDistance();
            this.shaderFloat64 = coreDeviceFeatures.shaderFloat64();
            this.shaderInt64 = coreDeviceFeatures.shaderInt64();
            this.shaderInt16 = coreDeviceFeatures.shaderInt16();
            this.shaderResourceResidency = coreDeviceFeatures.shaderResourceResidency();
            this.shaderResourceMinLod = coreDeviceFeatures.shaderResourceMinLod();
            this.sparseBinding = coreDeviceFeatures.sparseBinding();
            this.sparseResidencyBuffer = coreDeviceFeatures.sparseResidencyBuffer();
            this.sparseResidencyImage2D = coreDeviceFeatures.sparseResidencyImage2D();
            this.sparseResidencyImage3D = coreDeviceFeatures.sparseResidencyImage3D();
            this.sparseResidency2Samples = coreDeviceFeatures.sparseResidency2Samples();
            this.sparseResidency4Samples = coreDeviceFeatures.sparseResidency4Samples();
            this.sparseResidency8Samples = coreDeviceFeatures.sparseResidency8Samples();
            this.sparseResidency16Samples = coreDeviceFeatures.sparseResidency16Samples();
            this.sparseResidencyAliased = coreDeviceFeatures.sparseResidencyAliased();
            this.variableMultisampleRate = coreDeviceFeatures.variableMultisampleRate();
            this.inheritedQueries = coreDeviceFeatures.inheritedQueries();

            // Vulkan 1.1 features
            if (features11 != null) {
                this.storageBuffer16BitAccess = features11.storageBuffer16BitAccess();
                this.uniformAndStorageBuffer16BitAccess = features11.uniformAndStorageBuffer16BitAccess();
                this.storagePushConstant16 = features11.storagePushConstant16();
                this.storageInputOutput16 = features11.storageInputOutput16();
                this.multiview = features11.multiview();
                this.multiviewGeometryShader = features11.multiviewGeometryShader();
                this.multiviewTessellationShader = features11.multiviewTessellationShader();
                this.variablePointersStorageBuffer = features11.variablePointersStorageBuffer();
                this.variablePointers = features11.variablePointers();
                this.protectedMemory = features11.protectedMemory();
                this.samplerYcbcrConversion = features11.samplerYcbcrConversion();
                this.shaderDrawParameters = features11.shaderDrawParameters();
            } else {
                this.storageBuffer16BitAccess = false;
                this.uniformAndStorageBuffer16BitAccess = false;
                this.storagePushConstant16 = false;
                this.storageInputOutput16 = false;
                this.multiview = false;
                this.multiviewGeometryShader = false;
                this.multiviewTessellationShader = false;
                this.variablePointersStorageBuffer = false;
                this.variablePointers = false;
                this.protectedMemory = false;
                this.samplerYcbcrConversion = false;
                this.shaderDrawParameters = false;
            }

            // Vulkan 1.2 features & Descriptor Indexing
            boolean diRuntimeDescArray = false, diShaderSampledImageArrayNonUniformIndexing = false, diShaderStorageBufferArrayNonUniformIndexing = false;
            boolean diShaderStorageImageArrayNonUniformIndexing = false, diDescBindingPartiallyBound = false, diDescBindingVariableDescCount = false;
            boolean diShaderInputAttachmentArrayDynamicIndexing = false, diShaderUniformTexelBufferArrayDynamicIndexing = false, diShaderStorageTexelBufferArrayDynamicIndexing = false;
            boolean diShaderUniformBufferArrayNonUniformIndexing = false, diShaderInputAttachmentArrayNonUniformIndexing = false, diShaderUniformTexelBufferArrayNonUniformIndexing = false;
            boolean diShaderStorageTexelBufferArrayNonUniformIndexing = false, diDescBindingUniformBufferUpdateAfterBind = false, diDescBindingSampledImageUpdateAfterBind = false;
            boolean diDescBindingStorageImageUpdateAfterBind = false, diDescBindingStorageBufferUpdateAfterBind = false, diDescBindingUniformTexelBufferUpdateAfterBind = false;
            boolean diDescBindingStorageTexelBufferUpdateAfterBind = false, diDescBindingUpdateUnusedWhilePending = false;
            boolean diScalarBlockLayout = false;

            if (features12 != null) {
                this.samplerMirrorClampToEdge = features12.samplerMirrorClampToEdge();
                this.drawIndirectCount = features12.drawIndirectCount();
                this.storageBuffer8BitAccess = features12.storageBuffer8BitAccess();
                this.uniformAndStorageBuffer8BitAccess = features12.uniformAndStorageBuffer8BitAccess();
                this.storagePushConstant8 = features12.storagePushConstant8();
                this.shaderBufferInt64Atomics = features12.shaderBufferInt64Atomics();
                this.shaderSharedInt64Atomics = features12.shaderSharedInt64Atomics();
                this.shaderFloat16 = features12.shaderFloat16();
                this.shaderInt8 = features12.shaderInt8();
                this.samplerFilterMinmax = features12.samplerFilterMinmax();
                this.imagelessFramebuffer = features12.imagelessFramebuffer();
                this.uniformBufferStandardLayout = features12.uniformBufferStandardLayout();
                this.shaderSubgroupExtendedTypes = features12.shaderSubgroupExtendedTypes();
                this.separateDepthStencilLayouts = features12.separateDepthStencilLayouts();
                this.hostQueryReset = features12.hostQueryReset();
                this.timelineSemaphore = features12.timelineSemaphore();
                this.bufferDeviceAddress = features12.bufferDeviceAddress();
                this.bufferDeviceAddressCaptureReplay = features12.bufferDeviceAddressCaptureReplay();
                this.bufferDeviceAddressMultiDevice = features12.bufferDeviceAddressMultiDevice();
                this.vulkanMemoryModel = features12.vulkanMemoryModel();
                this.vulkanMemoryModelDeviceScope = features12.vulkanMemoryModelDeviceScope();
                this.vulkanMemoryModelAvailabilityVisibilityChains = features12.vulkanMemoryModelAvailabilityVisibilityChains();
                this.shaderOutputViewportIndex = features12.shaderOutputViewportIndex();
                this.shaderOutputLayer = features12.shaderOutputLayer();
                this.subgroupBroadcastDynamicId = features12.subgroupBroadcastDynamicId();

                diRuntimeDescArray = features12.runtimeDescriptorArray();
                diShaderSampledImageArrayNonUniformIndexing = features12.shaderSampledImageArrayNonUniformIndexing();
                diShaderStorageBufferArrayNonUniformIndexing = features12.shaderStorageBufferArrayNonUniformIndexing();
                diShaderStorageImageArrayNonUniformIndexing = features12.shaderStorageImageArrayNonUniformIndexing();
                diDescBindingPartiallyBound = features12.descriptorBindingPartiallyBound();
                diDescBindingVariableDescCount = features12.descriptorBindingVariableDescriptorCount();
                diShaderInputAttachmentArrayDynamicIndexing = features12.shaderInputAttachmentArrayDynamicIndexing();
                diShaderUniformTexelBufferArrayDynamicIndexing = features12.shaderUniformTexelBufferArrayDynamicIndexing();
                diShaderStorageTexelBufferArrayDynamicIndexing = features12.shaderStorageTexelBufferArrayDynamicIndexing();
                diShaderUniformBufferArrayNonUniformIndexing = features12.shaderUniformBufferArrayNonUniformIndexing();
                diShaderInputAttachmentArrayNonUniformIndexing = features12.shaderInputAttachmentArrayNonUniformIndexing();
                diShaderUniformTexelBufferArrayNonUniformIndexing = features12.shaderUniformTexelBufferArrayNonUniformIndexing();
                diShaderStorageTexelBufferArrayNonUniformIndexing = features12.shaderStorageTexelBufferArrayNonUniformIndexing();
                diDescBindingUniformBufferUpdateAfterBind = features12.descriptorBindingUniformBufferUpdateAfterBind();
                diDescBindingSampledImageUpdateAfterBind = features12.descriptorBindingSampledImageUpdateAfterBind();
                diDescBindingStorageImageUpdateAfterBind = features12.descriptorBindingStorageImageUpdateAfterBind();
                diDescBindingStorageBufferUpdateAfterBind = features12.descriptorBindingStorageBufferUpdateAfterBind();
                diDescBindingUniformTexelBufferUpdateAfterBind = features12.descriptorBindingUniformTexelBufferUpdateAfterBind();
                diDescBindingStorageTexelBufferUpdateAfterBind = features12.descriptorBindingStorageTexelBufferUpdateAfterBind();
                diDescBindingUpdateUnusedWhilePending = features12.descriptorBindingUpdateUnusedWhilePending();
                diScalarBlockLayout = features12.scalarBlockLayout();
            } else {
                this.samplerMirrorClampToEdge = false;
                this.drawIndirectCount = false;
                this.storageBuffer8BitAccess = false;
                this.uniformAndStorageBuffer8BitAccess = false;
                this.storagePushConstant8 = false;
                this.shaderBufferInt64Atomics = false;
                this.shaderSharedInt64Atomics = false;
                this.shaderFloat16 = false;
                this.shaderInt8 = false;
                this.samplerFilterMinmax = false;
                this.imagelessFramebuffer = false;
                this.uniformBufferStandardLayout = false;
                this.shaderSubgroupExtendedTypes = false;
                this.separateDepthStencilLayouts = false;
                this.hostQueryReset = false;
                this.timelineSemaphore = false;
                this.bufferDeviceAddress = false;
                this.bufferDeviceAddressCaptureReplay = false;
                this.bufferDeviceAddressMultiDevice = false;
                this.vulkanMemoryModel = false;
                this.vulkanMemoryModelDeviceScope = false;
                this.vulkanMemoryModelAvailabilityVisibilityChains = false;
                this.shaderOutputViewportIndex = false;
                this.shaderOutputLayer = false;
                this.subgroupBroadcastDynamicId = false;
            }

            if (this.apiVersion < VK_API_VERSION_1_2 && extDescriptorIndexingFeatures != null) {
                diRuntimeDescArray = extDescriptorIndexingFeatures.runtimeDescriptorArray();
                diShaderSampledImageArrayNonUniformIndexing = extDescriptorIndexingFeatures.shaderSampledImageArrayNonUniformIndexing();
                diShaderStorageBufferArrayNonUniformIndexing = extDescriptorIndexingFeatures.shaderStorageBufferArrayNonUniformIndexing();
                diShaderStorageImageArrayNonUniformIndexing = extDescriptorIndexingFeatures.shaderStorageImageArrayNonUniformIndexing();
                diDescBindingPartiallyBound = extDescriptorIndexingFeatures.descriptorBindingPartiallyBound();
                diDescBindingVariableDescCount = extDescriptorIndexingFeatures.descriptorBindingVariableDescriptorCount();
                diShaderInputAttachmentArrayDynamicIndexing = extDescriptorIndexingFeatures.shaderInputAttachmentArrayDynamicIndexing();
                diShaderUniformTexelBufferArrayDynamicIndexing = extDescriptorIndexingFeatures.shaderUniformTexelBufferArrayDynamicIndexing();
                diShaderStorageTexelBufferArrayDynamicIndexing = extDescriptorIndexingFeatures.shaderStorageTexelBufferArrayDynamicIndexing();
                diShaderUniformBufferArrayNonUniformIndexing = extDescriptorIndexingFeatures.shaderUniformBufferArrayNonUniformIndexing();
                diShaderInputAttachmentArrayNonUniformIndexing = extDescriptorIndexingFeatures.shaderInputAttachmentArrayNonUniformIndexing();
                diShaderUniformTexelBufferArrayNonUniformIndexing = extDescriptorIndexingFeatures.shaderUniformTexelBufferArrayNonUniformIndexing();
                diShaderStorageTexelBufferArrayNonUniformIndexing = extDescriptorIndexingFeatures.shaderStorageTexelBufferArrayNonUniformIndexing();
                diDescBindingUniformBufferUpdateAfterBind = extDescriptorIndexingFeatures.descriptorBindingUniformBufferUpdateAfterBind();
                diDescBindingSampledImageUpdateAfterBind = extDescriptorIndexingFeatures.descriptorBindingSampledImageUpdateAfterBind();
                diDescBindingStorageImageUpdateAfterBind = extDescriptorIndexingFeatures.descriptorBindingStorageImageUpdateAfterBind();
                diDescBindingStorageBufferUpdateAfterBind = extDescriptorIndexingFeatures.descriptorBindingStorageBufferUpdateAfterBind();
                diDescBindingUniformTexelBufferUpdateAfterBind = extDescriptorIndexingFeatures.descriptorBindingUniformTexelBufferUpdateAfterBind();
                diDescBindingStorageTexelBufferUpdateAfterBind = extDescriptorIndexingFeatures.descriptorBindingStorageTexelBufferUpdateAfterBind();
                diDescBindingUpdateUnusedWhilePending = extDescriptorIndexingFeatures.descriptorBindingUpdateUnusedWhilePending();
            }

            this.runtimeDescriptorArray = diRuntimeDescArray;
            this.shaderSampledImageArrayNonUniformIndexing = diShaderSampledImageArrayNonUniformIndexing;
            this.shaderStorageBufferArrayNonUniformIndexing = diShaderStorageBufferArrayNonUniformIndexing;
            this.shaderStorageImageArrayNonUniformIndexing = diShaderStorageImageArrayNonUniformIndexing;
            this.descriptorBindingPartiallyBound = diDescBindingPartiallyBound;
            this.descriptorBindingVariableDescriptorCount = diDescBindingVariableDescCount;
            this.shaderInputAttachmentArrayDynamicIndexing = diShaderInputAttachmentArrayDynamicIndexing;
            this.shaderUniformTexelBufferArrayDynamicIndexing = diShaderUniformTexelBufferArrayDynamicIndexing;
            this.shaderStorageTexelBufferArrayDynamicIndexing = diShaderStorageTexelBufferArrayDynamicIndexing;
            this.shaderUniformBufferArrayNonUniformIndexing = diShaderUniformBufferArrayNonUniformIndexing;
            this.shaderInputAttachmentArrayNonUniformIndexing = diShaderInputAttachmentArrayNonUniformIndexing;
            this.shaderUniformTexelBufferArrayNonUniformIndexing = diShaderUniformTexelBufferArrayNonUniformIndexing;
            this.shaderStorageTexelBufferArrayNonUniformIndexing = diShaderStorageTexelBufferArrayNonUniformIndexing;
            this.descriptorBindingUniformBufferUpdateAfterBind = diDescBindingUniformBufferUpdateAfterBind;
            this.descriptorBindingSampledImageUpdateAfterBind = diDescBindingSampledImageUpdateAfterBind;
            this.descriptorBindingStorageImageUpdateAfterBind = diDescBindingStorageImageUpdateAfterBind;
            this.descriptorBindingStorageBufferUpdateAfterBind = diDescBindingStorageBufferUpdateAfterBind;
            this.descriptorBindingUniformTexelBufferUpdateAfterBind = diDescBindingUniformTexelBufferUpdateAfterBind;
            this.descriptorBindingStorageTexelBufferUpdateAfterBind = diDescBindingStorageTexelBufferUpdateAfterBind;
            this.descriptorBindingUpdateUnusedWhilePending = diDescBindingUpdateUnusedWhilePending;
            this.scalarBlockLayout = (features12 != null) && diScalarBlockLayout;

            this.descriptorIndexing = (this.apiVersion >= VK_API_VERSION_1_2 && features12 != null) ||
                    (this.apiVersion < VK_API_VERSION_1_2 && extDescriptorIndexingFeatures != null && descriptorIndexingExtensionPresent);

            // Vulkan 1.3 features
            if (features13 != null) {
                this.robustImageAccess = features13.robustImageAccess();
                this.inlineUniformBlock = features13.inlineUniformBlock();
                this.descriptorBindingInlineUniformBlockUpdateAfterBind = features13.descriptorBindingInlineUniformBlockUpdateAfterBind();
                this.pipelineCreationCacheControl = features13.pipelineCreationCacheControl();
                this.privateData = features13.privateData();
                this.shaderDemoteToHelperInvocation = features13.shaderDemoteToHelperInvocation();
                this.shaderTerminateInvocation = features13.shaderTerminateInvocation();
                this.subgroupSizeControl = features13.subgroupSizeControl();
                this.computeFullSubgroups = features13.computeFullSubgroups();
                this.synchronization2 = features13.synchronization2();
                this.textureCompressionASTC_HDR = features13.textureCompressionASTC_HDR();
                this.shaderZeroInitializeWorkgroupMemory = features13.shaderZeroInitializeWorkgroupMemory();
                this.dynamicRendering = features13.dynamicRendering();
                this.shaderIntegerDotProduct = features13.shaderIntegerDotProduct();
                this.maintenance4 = features13.maintenance4();
            } else {
                this.robustImageAccess = false;
                this.inlineUniformBlock = false;
                this.descriptorBindingInlineUniformBlockUpdateAfterBind = false;
                this.pipelineCreationCacheControl = false;
                this.privateData = false;
                this.shaderDemoteToHelperInvocation = false;
                this.shaderTerminateInvocation = false;
                this.subgroupSizeControl = false;
                this.computeFullSubgroups = false;
                this.synchronization2 = false;
                this.textureCompressionASTC_HDR = false;
                this.shaderZeroInitializeWorkgroupMemory = false;
                this.dynamicRendering = false;
                this.shaderIntegerDotProduct = false;
                this.maintenance4 = false;
            }
        }
    }

    // --- Getters for API version and Limits ---
    public int getApiVersion() {
        return apiVersion;
    }

    public String getApiVersionString() {
        return apiVersionString;
    }

    public VkPhysicalDeviceLimits getLimits() {
        return limits;
    }

    public long getMaxBufferSize() {
        return maxBufferSize;
    }

    // --- Getters for individual limit values ---
    public int getMaxImageDimension1D() {
        return maxImageDimension1D;
    }

    public int getMaxImageDimension2D() {
        return maxImageDimension2D;
    }

    public int getMaxImageDimension3D() {
        return maxImageDimension3D;
    }

    public int getMaxImageDimensionCube() {
        return maxImageDimensionCube;
    }

    public int getMaxImageArrayLayers() {
        return maxImageArrayLayers;
    }

    public long getMaxTexelBufferElements() {
        return maxTexelBufferElements;
    }

    public long getMaxUniformBufferRange() {
        return maxUniformBufferRange;
    }

    public long getMaxStorageBufferRange() {
        return maxStorageBufferRange;
    }

    public int getMaxPushConstantsSize() {
        return maxPushConstantsSize;
    }

    public int getMaxMemoryAllocationCount() {
        return maxMemoryAllocationCount;
    }

    public int getMaxSamplerAllocationCount() {
        return maxSamplerAllocationCount;
    }

    public long getBufferImageGranularity() {
        return bufferImageGranularity;
    }

    public long getSparseAddressSpaceSize() {
        return sparseAddressSpaceSize;
    }

    public int getMaxBoundDescriptorSets() {
        return maxBoundDescriptorSets;
    }

    public int getMaxPerStageDescriptorSamplers() {
        return maxPerStageDescriptorSamplers;
    }

    public int getMaxPerStageDescriptorUniformBuffers() {
        return maxPerStageDescriptorUniformBuffers;
    }

    public int getMaxPerStageDescriptorStorageBuffers() {
        return maxPerStageDescriptorStorageBuffers;
    }

    public int getMaxPerStageDescriptorSampledImages() {
        return maxPerStageDescriptorSampledImages;
    }

    public int getMaxPerStageDescriptorStorageImages() {
        return maxPerStageDescriptorStorageImages;
    }

    public int getMaxPerStageDescriptorInputAttachments() {
        return maxPerStageDescriptorInputAttachments;
    }

    public int getMaxPerStageResources() {
        return maxPerStageResources;
    }

    public int getMaxDescriptorSetSamplers() {
        return maxDescriptorSetSamplers;
    }

    public int getMaxDescriptorSetUniformBuffers() {
        return maxDescriptorSetUniformBuffers;
    }

    public int getMaxDescriptorSetUniformBuffersDynamic() {
        return maxDescriptorSetUniformBuffersDynamic;
    }

    public int getMaxDescriptorSetStorageBuffers() {
        return maxDescriptorSetStorageBuffers;
    }

    public int getMaxDescriptorSetStorageBuffersDynamic() {
        return maxDescriptorSetStorageBuffersDynamic;
    }

    public int getMaxDescriptorSetSampledImages() {
        return maxDescriptorSetSampledImages;
    }

    public int getMaxDescriptorSetStorageImages() {
        return maxDescriptorSetStorageImages;
    }

    public int getMaxDescriptorSetInputAttachments() {
        return maxDescriptorSetInputAttachments;
    }

    public int getMaxVertexInputAttributes() {
        return maxVertexInputAttributes;
    }

    public int getMaxVertexInputBindings() {
        return maxVertexInputBindings;
    }

    public int getMaxVertexInputAttributeOffset() {
        return maxVertexInputAttributeOffset;
    }

    public int getMaxVertexInputBindingStride() {
        return maxVertexInputBindingStride;
    }

    public int getMaxVertexOutputComponents() {
        return maxVertexOutputComponents;
    }

    // --- Getters for Vulkan 1.0 Core Features ---
    public boolean isRobustBufferAccess() {
        return robustBufferAccess;
    }

    public boolean isFullDrawIndexUint32() {
        return fullDrawIndexUint32;
    }

    public boolean isImageCubeArray() {
        return imageCubeArray;
    }

    public boolean isIndependentBlend() {
        return independentBlend;
    }

    public boolean isGeometryShader() {
        return geometryShader;
    }

    public boolean isTessellationShader() {
        return tessellationShader;
    }

    public boolean isSampleRateShading() {
        return sampleRateShading;
    }

    public boolean isDualSrcBlend() {
        return dualSrcBlend;
    }

    public boolean isLogicOp() {
        return logicOp;
    }

    public boolean isMultiDrawIndirect() {
        return multiDrawIndirect;
    }

    public boolean isDrawIndirectFirstInstance() {
        return drawIndirectFirstInstance;
    }

    public boolean isDepthClamp() {
        return depthClamp;
    }

    public boolean isDepthBiasClamp() {
        return depthBiasClamp;
    }

    public boolean isFillModeNonSolid() {
        return fillModeNonSolid;
    }

    public boolean isDepthBounds() {
        return depthBounds;
    }

    public boolean isWideLines() {
        return wideLines;
    }

    public boolean isLargePoints() {
        return largePoints;
    }

    public boolean isAlphaToOne() {
        return alphaToOne;
    }

    public boolean isMultiViewport() {
        return multiViewport;
    }

    public boolean isSamplerAnisotropy() {
        return samplerAnisotropy;
    }

    public boolean isTextureCompressionETC2() {
        return textureCompressionETC2;
    }

    public boolean isTextureCompressionASTC_LDR() {
        return textureCompressionASTC_LDR;
    }

    public boolean isTextureCompressionBC() {
        return textureCompressionBC;
    }

    public boolean isOcclusionQueryPrecise() {
        return occlusionQueryPrecise;
    }

    public boolean isPipelineStatisticsQuery() {
        return pipelineStatisticsQuery;
    }

    public boolean isVertexPipelineStoresAndAtomics() {
        return vertexPipelineStoresAndAtomics;
    }

    public boolean isFragmentStoresAndAtomics() {
        return fragmentStoresAndAtomics;
    }

    public boolean isShaderTessellationAndGeometryPointSize() {
        return shaderTessellationAndGeometryPointSize;
    }

    public boolean isShaderImageGatherExtended() {
        return shaderImageGatherExtended;
    }

    public boolean isShaderStorageImageExtendedFormats() {
        return shaderStorageImageExtendedFormats;
    }

    public boolean isShaderStorageImageMultisample() {
        return shaderStorageImageMultisample;
    }

    public boolean isShaderStorageImageReadWithoutFormat() {
        return shaderStorageImageReadWithoutFormat;
    }

    public boolean isShaderStorageImageWriteWithoutFormat() {
        return shaderStorageImageWriteWithoutFormat;
    }

    public boolean isShaderUniformBufferArrayDynamicIndexing() {
        return shaderUniformBufferArrayDynamicIndexing;
    }

    public boolean isShaderSampledImageArrayDynamicIndexing() {
        return shaderSampledImageArrayDynamicIndexing;
    }

    public boolean isShaderStorageBufferArrayDynamicIndexing() {
        return shaderStorageBufferArrayDynamicIndexing;
    }

    public boolean isShaderStorageImageArrayDynamicIndexing() {
        return shaderStorageImageArrayDynamicIndexing;
    }

    public boolean isShaderClipDistance() {
        return shaderClipDistance;
    }

    public boolean isShaderCullDistance() {
        return shaderCullDistance;
    }

    public boolean isShaderFloat64() {
        return shaderFloat64;
    }

    public boolean isShaderInt64() {
        return shaderInt64;
    }

    public boolean isShaderInt16() {
        return shaderInt16;
    }

    public boolean isShaderResourceResidency() {
        return shaderResourceResidency;
    }

    public boolean isShaderResourceMinLod() {
        return shaderResourceMinLod;
    }

    public boolean isSparseBinding() {
        return sparseBinding;
    }

    public boolean isSparseResidencyBuffer() {
        return sparseResidencyBuffer;
    }

    public boolean isSparseResidencyImage2D() {
        return sparseResidencyImage2D;
    }

    public boolean isSparseResidencyImage3D() {
        return sparseResidencyImage3D;
    }

    public boolean isSparseResidency2Samples() {
        return sparseResidency2Samples;
    }

    public boolean isSparseResidency4Samples() {
        return sparseResidency4Samples;
    }

    public boolean isSparseResidency8Samples() {
        return sparseResidency8Samples;
    }

    public boolean isSparseResidency16Samples() {
        return sparseResidency16Samples;
    }

    public boolean isSparseResidencyAliased() {
        return sparseResidencyAliased;
    }

    public boolean isVariableMultisampleRate() {
        return variableMultisampleRate;
    }

    public boolean isInheritedQueries() {
        return inheritedQueries;
    }

    // --- Getters for Vulkan 1.1 Core Features ---
    public boolean isStorageBuffer16BitAccess() {
        return storageBuffer16BitAccess;
    }

    public boolean isUniformAndStorageBuffer16BitAccess() {
        return uniformAndStorageBuffer16BitAccess;
    }

    public boolean isStoragePushConstant16() {
        return storagePushConstant16;
    }

    public boolean isStorageInputOutput16() {
        return storageInputOutput16;
    }

    public boolean isMultiview() {
        return multiview;
    }

    public boolean isMultiviewGeometryShader() {
        return multiviewGeometryShader;
    }

    public boolean isMultiviewTessellationShader() {
        return multiviewTessellationShader;
    }

    public boolean isVariablePointersStorageBuffer() {
        return variablePointersStorageBuffer;
    }

    public boolean isVariablePointers() {
        return variablePointers;
    }

    public boolean isProtectedMemory() {
        return protectedMemory;
    }

    public boolean isSamplerYcbcrConversion() {
        return samplerYcbcrConversion;
    }

    public boolean isShaderDrawParameters() {
        return shaderDrawParameters;
    }

    // --- Getters for Vulkan 1.2 Core Features (including Descriptor Indexing) ---
    public boolean isSamplerMirrorClampToEdge() {
        return samplerMirrorClampToEdge;
    }

    public boolean isDrawIndirectCount() {
        return drawIndirectCount;
    }

    public boolean isStorageBuffer8BitAccess() {
        return storageBuffer8BitAccess;
    }

    public boolean isUniformAndStorageBuffer8BitAccess() {
        return uniformAndStorageBuffer8BitAccess;
    }

    public boolean isStoragePushConstant8() {
        return storagePushConstant8;
    }

    public boolean isShaderBufferInt64Atomics() {
        return shaderBufferInt64Atomics;
    }

    public boolean isShaderSharedInt64Atomics() {
        return shaderSharedInt64Atomics;
    }

    public boolean isShaderFloat16() {
        return shaderFloat16;
    }

    public boolean isShaderInt8() {
        return shaderInt8;
    }

    public boolean isDescriptorIndexingSupported() {
        return descriptorIndexing;
    }

    public boolean isShaderInputAttachmentArrayDynamicIndexing() {
        return shaderInputAttachmentArrayDynamicIndexing;
    }

    public boolean isShaderUniformTexelBufferArrayDynamicIndexing() {
        return shaderUniformTexelBufferArrayDynamicIndexing;
    }

    public boolean isShaderStorageTexelBufferArrayDynamicIndexing() {
        return shaderStorageTexelBufferArrayDynamicIndexing;
    }

    public boolean isShaderUniformBufferArrayNonUniformIndexing() {
        return shaderUniformBufferArrayNonUniformIndexing;
    }

    public boolean isShaderSampledImageArrayNonUniformIndexing() {
        return shaderSampledImageArrayNonUniformIndexing;
    }

    public boolean isShaderStorageBufferArrayNonUniformIndexing() {
        return shaderStorageBufferArrayNonUniformIndexing;
    }

    public boolean isShaderStorageImageArrayNonUniformIndexing() {
        return shaderStorageImageArrayNonUniformIndexing;
    }

    public boolean isShaderInputAttachmentArrayNonUniformIndexing() {
        return shaderInputAttachmentArrayNonUniformIndexing;
    }

    public boolean isShaderUniformTexelBufferArrayNonUniformIndexing() {
        return shaderUniformTexelBufferArrayNonUniformIndexing;
    }

    public boolean isShaderStorageTexelBufferArrayNonUniformIndexing() {
        return shaderStorageTexelBufferArrayNonUniformIndexing;
    }

    public boolean isDescriptorBindingUniformBufferUpdateAfterBind() {
        return descriptorBindingUniformBufferUpdateAfterBind;
    }

    public boolean isDescriptorBindingSampledImageUpdateAfterBind() {
        return descriptorBindingSampledImageUpdateAfterBind;
    }

    public boolean isDescriptorBindingStorageImageUpdateAfterBind() {
        return descriptorBindingStorageImageUpdateAfterBind;
    }

    public boolean isDescriptorBindingStorageBufferUpdateAfterBind() {
        return descriptorBindingStorageBufferUpdateAfterBind;
    }

    public boolean isDescriptorBindingUniformTexelBufferUpdateAfterBind() {
        return descriptorBindingUniformTexelBufferUpdateAfterBind;
    }

    public boolean isDescriptorBindingStorageTexelBufferUpdateAfterBind() {
        return descriptorBindingStorageTexelBufferUpdateAfterBind;
    }

    public boolean isDescriptorBindingUpdateUnusedWhilePending() {
        return descriptorBindingUpdateUnusedWhilePending;
    }

    public boolean isDescriptorBindingPartiallyBound() {
        return descriptorBindingPartiallyBound;
    }

    public boolean isDescriptorBindingVariableDescriptorCount() {
        return descriptorBindingVariableDescriptorCount;
    }

    public boolean isRuntimeDescriptorArray() {
        return runtimeDescriptorArray;
    }

    public boolean isSamplerFilterMinmax() {
        return samplerFilterMinmax;
    }

    public boolean isScalarBlockLayout() {
        return scalarBlockLayout;
    }

    public boolean isImagelessFramebuffer() {
        return imagelessFramebuffer;
    }

    public boolean isUniformBufferStandardLayout() {
        return uniformBufferStandardLayout;
    }

    public boolean isShaderSubgroupExtendedTypes() {
        return shaderSubgroupExtendedTypes;
    }

    public boolean isSeparateDepthStencilLayouts() {
        return separateDepthStencilLayouts;
    }

    public boolean isHostQueryReset() {
        return hostQueryReset;
    }

    public boolean isTimelineSemaphore() {
        return timelineSemaphore;
    }

    public boolean isBufferDeviceAddress() {
        return bufferDeviceAddress;
    }

    public boolean isBufferDeviceAddressCaptureReplay() {
        return bufferDeviceAddressCaptureReplay;
    }

    public boolean isBufferDeviceAddressMultiDevice() {
        return bufferDeviceAddressMultiDevice;
    }

    public boolean isVulkanMemoryModel() {
        return vulkanMemoryModel;
    }

    public boolean isVulkanMemoryModelDeviceScope() {
        return vulkanMemoryModelDeviceScope;
    }

    public boolean isVulkanMemoryModelAvailabilityVisibilityChains() {
        return vulkanMemoryModelAvailabilityVisibilityChains;
    }

    public boolean isShaderOutputViewportIndex() {
        return shaderOutputViewportIndex;
    }

    public boolean isShaderOutputLayer() {
        return shaderOutputLayer;
    }

    public boolean isSubgroupBroadcastDynamicId() {
        return subgroupBroadcastDynamicId;
    }

    // --- Getters for Vulkan 1.3 Core Features ---
    public boolean isRobustImageAccess() {
        return robustImageAccess;
    }

    public boolean isInlineUniformBlock() {
        return inlineUniformBlock;
    }

    public boolean isDescriptorBindingInlineUniformBlockUpdateAfterBind() {
        return descriptorBindingInlineUniformBlockUpdateAfterBind;
    }

    public boolean isPipelineCreationCacheControl() {
        return pipelineCreationCacheControl;
    }

    public boolean isPrivateData() {
        return privateData;
    }

    public boolean isShaderDemoteToHelperInvocation() {
        return shaderDemoteToHelperInvocation;
    }

    public boolean isShaderTerminateInvocation() {
        return shaderTerminateInvocation;
    }

    public boolean isSubgroupSizeControl() {
        return subgroupSizeControl;
    }

    public boolean isComputeFullSubgroups() {
        return computeFullSubgroups;
    }

    public boolean isSynchronization2() {
        return synchronization2;
    }

    public boolean isTextureCompressionASTC_HDR() {
        return textureCompressionASTC_HDR;
    }

    public boolean isShaderZeroInitializeWorkgroupMemory() {
        return shaderZeroInitializeWorkgroupMemory;
    }

    public boolean isDynamicRendering() {
        return dynamicRendering;
    }

    public boolean isShaderIntegerDotProduct() {
        return shaderIntegerDotProduct;
    }

    public boolean isMaintenance4() {
        return maintenance4;
    }


    public void printSummary() {
        if (!DEBUG_LOGGING && Gdx.app == null) return;

        logMsg("--- Vulkan Device Capabilities Summary (" + (persistentProperties != null ? persistentProperties.deviceNameString() : "Unknown Device") + ") ---");
        logMsg("API Version: " + getApiVersionString() + " (Raw: " + getApiVersion() + ")");
        if (persistentProperties != null) logMsg("Device Type: " + getDeviceTypeString(persistentProperties.deviceType()));
        logMsg("Max Buffer Size (from Maintenance4 Props, if available): " + getMaxBufferSize());
        logMsg("Max Push Constants Size: " + getMaxPushConstantsSize());
        logMsg("Sampler Anisotropy: " + isSamplerAnisotropy() + (isSamplerAnisotropy() && getLimits() != null ? " (Max: " + getLimits().maxSamplerAnisotropy() + ")" : ""));

        logMsg("--- Descriptor Indexing ---");
        logMsg("  Overall Support (Core 1.2 or EXT): " + isDescriptorIndexingSupported());
        logMsg("  Runtime Descriptor Array: " + isRuntimeDescriptorArray());
        logMsg("  Shader Sampled Image Array Non-Uniform Indexing: " + isShaderSampledImageArrayNonUniformIndexing());
        logMsg("  Shader Storage Buffer Array Non-Uniform Indexing: " + isShaderStorageBufferArrayNonUniformIndexing());
        logMsg("  Descriptor Binding Partially Bound: " + isDescriptorBindingPartiallyBound());
        logMsg("  Descriptor Binding Variable Descriptor Count: " + isDescriptorBindingVariableDescriptorCount());

        logMsg("--- Other Key Features ---");
        logMsg("  Timeline Semaphore: " + isTimelineSemaphore());
        logMsg("  Dynamic Rendering: " + isDynamicRendering());
        logMsg("  Synchronization2: " + isSynchronization2());
        logMsg("  Buffer Device Address: " + isBufferDeviceAddress());
        logMsg("  Scalar Block Layout (1.2+): " + isScalarBlockLayout());
        logMsg("  Maintenance4 Feature Enabled: " + isMaintenance4());
        logMsg("----------------------------------------------------");
    }

    private String getDeviceTypeString(int deviceType) {
        switch (deviceType) {
            case VK_PHYSICAL_DEVICE_TYPE_OTHER:
                return "Other";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU:
                return "Integrated GPU";
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:
                return "Discrete GPU";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:
                return "Virtual GPU";
            case VK_PHYSICAL_DEVICE_TYPE_CPU:
                return "CPU";
            default:
                return "Unknown (" + deviceType + ")";
        }
    }

    private void logMsg(String message) {
        if (DEBUG_LOGGING && Gdx.app != null) {
            Gdx.app.log(TAG, message);
        } else if (DEBUG_LOGGING) {
            System.out.println("[" + TAG + "] " + message);
        }
    }

    public void free() {
        if (this.persistentProperties != null) {
            this.persistentProperties.free();
        }
    }
}