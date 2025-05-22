package com.badlogic.gdx.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer; // For executeSingleTimeCommands in VulkanDevice

import static org.lwjgl.system.MemoryStack.stackPush;
// Make sure all necessary static imports for Vulkan constants (VK_...) are present
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.EXTDescriptorIndexing.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES_EXT;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDescriptorIndexing.VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.*;


import com.badlogic.gdx.Gdx; // For logging
import com.badlogic.gdx.utils.GdxRuntimeException; // For exceptions

// Assuming VulkanDeviceCapabilities is in the same package or imported correctly.

/**
 * Placeholder for LibGDX's VkDeviceHandle if it's a custom class.
 * This is based on the error messages encountered. If LibGDX uses
 * org.lwjgl.vulkan.VkDevice directly, this custom class might not be needed,
 * and VulkanDevice would store and use org.lwjgl.vulkan.VkDevice directly.
 */
class VkDeviceHandle { // This class might not be used if VulkanDevice directly uses org.lwjgl.vulkan.VkDevice
    public final long handle;
    public final VkPhysicalDevice physicalDevice;
    public final VkDeviceCreateInfo createInfo; // Or relevant parts of it

    public VkDeviceHandle(long handle, VkPhysicalDevice physicalDevice, VkDeviceCreateInfo createInfo) {
        this.handle = handle;
        this.physicalDevice = physicalDevice;
        // Caution: createInfo might be stack-allocated when this is called.
        // If VkDeviceHandle is long-lived, createInfo should be deep-copied or relevant data extracted.
        this.createInfo = createInfo;
    }

    public long getHandle() {
        return handle;
    }
}

/**
 * Represents a Vulkan logical device, its associated queues, and command pool.
 * This class is responsible for the creation and management of these core Vulkan objects.
 */
public class VulkanDevice {
    private static final String TAG = VulkanDevice.class.getSimpleName();
    private static final boolean debug = false; // Or from a shared config

    private final VkDevice logicalDevice; // LWJGL's wrapper for the Vulkan logical device
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;
    private final VkPhysicalDevice physicalDevice;
    private final long commandPool;
    private final int graphicsQueueFamilyIndex;
    private final int presentQueueFamilyIndex;
    private final VulkanDeviceCapabilities capabilities; // Store capabilities for later reference

    /**
     * Constructs a VulkanDevice.
     *
     * @param logicalDevice The LWJGL VkDevice object representing the created logical device.
     * @param graphicsQueue The graphics queue.
     * @param presentQueue The presentation queue.
     * @param physicalDevice The physical device from which this logical device was created.
     * @param commandPool The command pool associated with the graphics queue.
     * @param graphicsQueueFamilyIndex The index of the graphics queue family.
     * @param presentQueueFamilyIndex The index of the presentation queue family.
     * @param capabilities The capabilities of the physical device, used for creating this logical device.
     */
    public VulkanDevice(VkDevice logicalDevice, VkQueue graphicsQueue, VkQueue presentQueue,
                        VkPhysicalDevice physicalDevice, long commandPool,
                        int graphicsQueueFamilyIndex, int presentQueueFamilyIndex,
                        VulkanDeviceCapabilities capabilities) { // Added capabilities
        this.logicalDevice = logicalDevice;
        this.graphicsQueue = graphicsQueue;
        this.presentQueue = presentQueue;
        this.physicalDevice = physicalDevice;
        this.commandPool = commandPool;
        this.graphicsQueueFamilyIndex = graphicsQueueFamilyIndex;
        this.presentQueueFamilyIndex = presentQueueFamilyIndex;
        this.capabilities = capabilities; // Store capabilities
        if (debug && Gdx.app != null) Gdx.app.log(TAG, "VulkanDevice created. Graphics Queue: " + graphicsQueue.address() + ", Present Queue: " + presentQueue.address());
    }

    public VkDevice getLogicalDevice() {
        return this.logicalDevice;
    }

    public VkDevice getRawDevice() { // Alias for consistency if used elsewhere
        return getLogicalDevice();
    }

    public long getRawDeviceHandle() {
        if (this.logicalDevice == null) return 0L;
        return this.logicalDevice.address();
    }

    public VkPhysicalDevice getPhysicalDevice() {
        return physicalDevice;
    }

    public VkQueue getGraphicsQueue() {
        return graphicsQueue;
    }

    public VkQueue getPresentQueue() {
        return presentQueue;
    }

    public long getCommandPool() {
        return commandPool;
    }

    public int getGraphicsQueueFamilyIndex() {
        return graphicsQueueFamilyIndex;
    }

    public int getPresentQueueFamilyIndex() {
        return presentQueueFamilyIndex;
    }

    public VulkanDeviceCapabilities getCapabilities() { // Getter for stored capabilities
        return capabilities;
    }

    public void executeSingleTimeCommands(Consumer<VkCommandBuffer> commands) {
        if (logicalDevice == null || graphicsQueue == null || commandPool == VK_NULL_HANDLE) {
            if (Gdx.app != null) Gdx.app.error(TAG, "Cannot execute single time commands: device, queue, or command pool is not initialized.");
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandPool(commandPool)
                    .commandBufferCount(1);
            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            int vkResult = vkAllocateCommandBuffers(logicalDevice, allocInfo, pCommandBuffer);
            if (vkResult != VK_SUCCESS) throw new GdxRuntimeException("Failed to allocate command buffer: " + vkResult);
            VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), logicalDevice);
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(commandBuffer, beginInfo);
            commands.accept(commandBuffer);
            vkEndCommandBuffer(commandBuffer);
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(commandBuffer));
            vkQueueSubmit(graphicsQueue, submitInfo, VK_NULL_HANDLE);
            vkQueueWaitIdle(graphicsQueue);
            vkFreeCommandBuffers(logicalDevice, commandPool, commandBuffer);
        }
    }

    public static void transitionImageLayoutCmd(VulkanDevice device, long image, int format, int oldLayout, int newLayout) {
        if (device == null) {
            if (Gdx.app != null) Gdx.app.error(TAG, "VulkanDevice is null in transitionImageLayoutCmd");
            return;
        }
        device.executeSingleTimeCommands(commandBuffer -> {
            try (MemoryStack stack = stackPush()) {
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER).oldLayout(oldLayout).newLayout(newLayout)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image);
                barrier.subresourceRange().baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                int sourceStage, destinationStage;
                if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                    barrier.srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                    barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                    destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                    barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                    destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                } else if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
                    barrier.srcAccessMask(0).dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                    barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                    if (format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT) {
                        barrier.subresourceRange().aspectMask(barrier.subresourceRange().aspectMask() | VK_IMAGE_ASPECT_STENCIL_BIT);
                    }
                    sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                    destinationStage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
                } else {
                    if (Gdx.app != null) Gdx.app.error(TAG, "Unsupported layout transition: " + oldLayout + " to " + newLayout);
                    barrier.srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
                    barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT); // Default, might be wrong
                    sourceStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
                    destinationStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
                }
                vkCmdPipelineBarrier(commandBuffer, sourceStage, destinationStage, 0, null, null, barrier);
            }
        });
    }

    public void cleanup() {
        if (debug && Gdx.app != null) Gdx.app.log(TAG, "Cleaning up VulkanDevice resources...");
        if (commandPool != VK_NULL_HANDLE) {
            if (logicalDevice == null) {
                if (debug && Gdx.app != null) Gdx.app.error(TAG, "Cannot destroy command pool, logicalDevice is null.");
            } else {
                vkDestroyCommandPool(logicalDevice, commandPool, null);
                if (debug && Gdx.app != null) Gdx.app.log(TAG, "Command pool destroyed.");
            }
        }
        if (logicalDevice != null) {
            vkDestroyDevice(logicalDevice, null);
            if (debug && Gdx.app != null) Gdx.app.log(TAG, "Logical device destroyed.");
        } else {
            if (debug && Gdx.app != null) Gdx.app.log(TAG, "Logical device was already null, no destruction needed.");
        }
        if (debug && Gdx.app != null) Gdx.app.log(TAG, "VulkanDevice cleanup finished.");
    }

    public static class Builder {
        private VkPhysicalDevice physicalDevice;
        private int graphicsQueueFamilyIndex = -1;
        private int presentQueueFamilyIndex = -1;
        private VulkanDeviceCapabilities capabilities;
        // **** DIAGNOSTIC FLAG ****
        private boolean temporarilyDisableMaintenance4 = true; // Set to true to test without enabling maintenance4

        public Builder setPhysicalDevice(VkPhysicalDevice physicalDevice) {
            this.physicalDevice = physicalDevice;
            return this;
        }

        public Builder setGraphicsQueueFamilyIndex(int graphicsQueueFamilyIndex) {
            this.graphicsQueueFamilyIndex = graphicsQueueFamilyIndex;
            return this;
        }

        public Builder setPresentQueueFamilyIndex(int presentQueueFamilyIndex) {
            this.presentQueueFamilyIndex = presentQueueFamilyIndex;
            return this;
        }

        public Builder setDeviceCapabilities(VulkanDeviceCapabilities capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        // Method to set the diagnostic flag
        public Builder setTemporarilyDisableMaintenance4(boolean disable) {
            this.temporarilyDisableMaintenance4 = disable;
            return this;
        }

        public VulkanDevice build() {
            if (this.capabilities == null) throw new IllegalStateException("VulkanDeviceCapabilities must be set in the Builder.");
            if (this.physicalDevice == null) throw new IllegalStateException("VkPhysicalDevice must be set in the Builder.");
            if (this.graphicsQueueFamilyIndex == -1) throw new IllegalStateException("Graphics queue family index must be set.");
            if (this.presentQueueFamilyIndex == -1) throw new IllegalStateException("Present queue family index must be set.");

            try (MemoryStack stack = stackPush()) {

                if (debug && Gdx.app != null) {
                    Gdx.app.log(TAG, "[DeviceBuilder] --- Capabilities Check ---");
                    Gdx.app.log(TAG, "[DeviceBuilder] API Version from capabilities: " + capabilities.getApiVersion() + " (Target for 1.2 struct: " + VK_API_VERSION_1_2 + ")");
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isSamplerAnisotropy(): " + capabilities.isSamplerAnisotropy());
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isRuntimeDescriptorArray(): " + capabilities.isRuntimeDescriptorArray());
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isShaderSampledImageArrayNonUniformIndexing(): " + capabilities.isShaderSampledImageArrayNonUniformIndexing());
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isShaderStorageBufferArrayNonUniformIndexing(): " + capabilities.isShaderStorageBufferArrayNonUniformIndexing());
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isDescriptorBindingPartiallyBound(): " + capabilities.isDescriptorBindingPartiallyBound());
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isDescriptorBindingVariableDescriptorCount(): " + capabilities.isDescriptorBindingVariableDescriptorCount());
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isScalarBlockLayout(): " + capabilities.isScalarBlockLayout());
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isTimelineSemaphore(): " + capabilities.isTimelineSemaphore());
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isBufferDeviceAddress(): " + capabilities.isBufferDeviceAddress());
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isDynamicRendering(): " + capabilities.isDynamicRendering());
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isSynchronization2(): " + capabilities.isSynchronization2());
                    Gdx.app.log(TAG, "[DeviceBuilder] capabilities.isMaintenance4(): " + capabilities.isMaintenance4());
                    Gdx.app.log(TAG, "[DeviceBuilder] --- End Capabilities Check ---");
                }

                FloatBuffer queuePriorities = stack.floats(1.0f);
                VkDeviceQueueCreateInfo.Buffer queueCreateInfos;
                java.util.Set<Integer> uniqueQueueFamilies = new java.util.HashSet<>();
                uniqueQueueFamilies.add(graphicsQueueFamilyIndex);
                uniqueQueueFamilies.add(presentQueueFamilyIndex);
                queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueQueueFamilies.size(), stack);
                int queueInfoIndex = 0;
                for (int queueFamily : uniqueQueueFamilies) {
                    queueCreateInfos.get(queueInfoIndex++)
                            .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO).pNext(NULL).flags(0)
                            .queueFamilyIndex(queueFamily).pQueuePriorities(queuePriorities);
                }

                List<String> enabledExtensionsList = new ArrayList<>();
                enabledExtensionsList.add(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
                /*if (capabilities.getApiVersion() < VK_API_VERSION_1_2 && capabilities.isDescriptorIndexingSupported() &&
                        (capabilities.isRuntimeDescriptorArray() || capabilities.isShaderSampledImageArrayNonUniformIndexing() ||
                                capabilities.isShaderStorageBufferArrayNonUniformIndexing() || capabilities.isDescriptorBindingPartiallyBound() ||
                                capabilities.isDescriptorBindingVariableDescriptorCount())) {
                    enabledExtensionsList.add(VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME);
                    if (debug && Gdx.app != null) Gdx.app.log(TAG, "Enabling device extension: " + VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME);
                }*/
                if (capabilities.isRuntimeDescriptorArray()
                        || capabilities.isShaderSampledImageArrayNonUniformIndexing()
                        || capabilities.isDescriptorBindingPartiallyBound()
                        || capabilities.isDescriptorBindingVariableDescriptorCount()) {
                    enabledExtensionsList.add(VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME);
                    if (debug) Gdx.app.log(TAG, "Enabling device extension: " + VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME);
                }

                PointerBuffer ppEnabledExtensionNames = null;
                if (!enabledExtensionsList.isEmpty()) {
                    ppEnabledExtensionNames = stack.mallocPointer(enabledExtensionsList.size());
                    for (int i = 0; i < enabledExtensionsList.size(); i++) {
                        ppEnabledExtensionNames.put(i, stack.UTF8(enabledExtensionsList.get(i)));
                    }
                }

                VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(NULL);
                if (capabilities.isSamplerAnisotropy()) features2.features().samplerAnisotropy(true);
                if (capabilities.isGeometryShader()) features2.features().geometryShader(true);
                // TODO: Add ALL other V1.0 features LibGDX typically enables, each guarded by a capabilities check.

                long pNextChainHead = features2.address();

                if (capabilities.getApiVersion() >= VK_API_VERSION_1_1) {
                    VkPhysicalDeviceVulkan11Features features11 = VkPhysicalDeviceVulkan11Features.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES).pNext(NULL);
                    if (capabilities.isStorageBuffer16BitAccess()) features11.storageBuffer16BitAccess(true);
                    if (capabilities.isVariablePointersStorageBuffer()) features11.variablePointersStorageBuffer(true);
                    if (capabilities.isVariablePointers()) features11.variablePointers(true);
                    if (capabilities.isShaderDrawParameters()) features11.shaderDrawParameters(true);
                    MemoryUtil.memPutAddress(pNextChainHead + VkBaseOutStructure.PNEXT, features11.address());
                    pNextChainHead = features11.address();
                }

                boolean diFeaturesActuallyEnabled = false;
                if (capabilities.getApiVersion() >= VK_API_VERSION_1_2) {
                    VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES).pNext(NULL);
                    if (debug && Gdx.app != null) Gdx.app.log(TAG, "[DeviceBuilder] Populating VkPhysicalDeviceVulkan12Features...");

                    features12.descriptorIndexing(true);
                    diFeaturesActuallyEnabled = true;
                    if (debug && Gdx.app != null) Gdx.app.log(TAG, "[DeviceBuilder]   features12.descriptorIndexing = true");

                    if (capabilities.isRuntimeDescriptorArray()) {
                        features12.runtimeDescriptorArray(true);
                        diFeaturesActuallyEnabled = true;
                        if (debug && Gdx.app != null) Gdx.app.log(TAG, "[DeviceBuilder]   features12.runtimeDescriptorArray = true");
                    }
                    if (capabilities.isShaderSampledImageArrayNonUniformIndexing()) {
                        features12.shaderSampledImageArrayNonUniformIndexing(true);
                        diFeaturesActuallyEnabled = true;
                        if (debug && Gdx.app != null) Gdx.app.log(TAG, "[DeviceBuilder]   features12.shaderSampledImageArrayNonUniformIndexing = true");
                    }
                    if (capabilities.isShaderStorageBufferArrayNonUniformIndexing()) {
                        features12.shaderStorageBufferArrayNonUniformIndexing(true);
                        diFeaturesActuallyEnabled = true;
                        if (debug && Gdx.app != null) Gdx.app.log(TAG, "[DeviceBuilder]   features12.shaderStorageBufferArrayNonUniformIndexing = true");
                    }
                    if (capabilities.isDescriptorBindingPartiallyBound()) {
                        features12.descriptorBindingPartiallyBound(true);
                        diFeaturesActuallyEnabled = true;
                        if (debug && Gdx.app != null) Gdx.app.log(TAG, "[DeviceBuilder]   features12.descriptorBindingPartiallyBound = true");
                    }
                    if (capabilities.isDescriptorBindingVariableDescriptorCount()) {
                        features12.descriptorBindingVariableDescriptorCount(true);
                        diFeaturesActuallyEnabled = true;
                        if (debug && Gdx.app != null) Gdx.app.log(TAG, "[DeviceBuilder]   features12.descriptorBindingVariableDescriptorCount = true");
                    }
                    if (capabilities.isScalarBlockLayout()) {
                        features12.scalarBlockLayout(true);
                        if (debug && Gdx.app != null) Gdx.app.log(TAG, "[DeviceBuilder]   features12.scalarBlockLayout = true");
                    }

                    if (capabilities.isTimelineSemaphore()) features12.timelineSemaphore(true);
                    if (capabilities.isBufferDeviceAddress()) features12.bufferDeviceAddress(true);
                    if (capabilities.isShaderFloat16()) features12.shaderFloat16(true);
                    if (capabilities.isShaderInt8()) features12.shaderInt8(true);
                    MemoryUtil.memPutAddress(pNextChainHead + VkBaseOutStructure.PNEXT, features12.address());
                    pNextChainHead = features12.address();
                } else if (enabledExtensionsList.contains(VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME)) {
                    VkPhysicalDeviceDescriptorIndexingFeaturesEXT extFeatures = VkPhysicalDeviceDescriptorIndexingFeaturesEXT.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES_EXT).pNext(NULL);
                    if (capabilities.isRuntimeDescriptorArray()) {
                        extFeatures.runtimeDescriptorArray(true);
                        diFeaturesActuallyEnabled = true;
                    }
                    if (capabilities.isShaderSampledImageArrayNonUniformIndexing()) {
                        extFeatures.shaderSampledImageArrayNonUniformIndexing(true);
                        diFeaturesActuallyEnabled = true;
                    }
                    if (capabilities.isShaderStorageBufferArrayNonUniformIndexing()) {
                        extFeatures.shaderStorageBufferArrayNonUniformIndexing(true);
                        diFeaturesActuallyEnabled = true;
                    }
                    if (capabilities.isDescriptorBindingPartiallyBound()) {
                        extFeatures.descriptorBindingPartiallyBound(true);
                        diFeaturesActuallyEnabled = true;
                    }
                    if (capabilities.isDescriptorBindingVariableDescriptorCount()) {
                        extFeatures.descriptorBindingVariableDescriptorCount(true);
                        diFeaturesActuallyEnabled = true;
                    }
                    MemoryUtil.memPutAddress(pNextChainHead + VkBaseOutStructure.PNEXT, extFeatures.address());
                    pNextChainHead = extFeatures.address();
                }

                if (!diFeaturesActuallyEnabled && (capabilities.isRuntimeDescriptorArray() || capabilities.isShaderSampledImageArrayNonUniformIndexing())) {
                    if (Gdx.app != null) Gdx.app.error(TAG, "WARNING: Key DI features supported by hardware but NOT enabled in structs for device creation.");
                }

                if (capabilities.getApiVersion() >= VK_API_VERSION_1_3) {
                    VkPhysicalDeviceVulkan13Features features13_to_enable = VkPhysicalDeviceVulkan13Features.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES).pNext(NULL);
                    if (capabilities.isDynamicRendering()) features13_to_enable.dynamicRendering(true);
                    if (capabilities.isSynchronization2()) features13_to_enable.synchronization2(true);

                    // **** MODIFIED SECTION FOR DIAGNOSTIC ****
                    if (capabilities.isMaintenance4() && !temporarilyDisableMaintenance4) {
                        features13_to_enable.maintenance4(true);
                        if (debug && Gdx.app != null) Gdx.app.log(TAG, "Enabling V1.3 feature: maintenance4");
                    } else if (capabilities.isMaintenance4() && temporarilyDisableMaintenance4) {
                        if (debug && Gdx.app != null) Gdx.app.log(TAG, "DIAGNOSTIC: Maintenance4 supported BUT TEMPORARILY DISABLED for this test.");
                        features13_to_enable.maintenance4(false); // Explicitly disable for test
                    } else {
                        if (debug && Gdx.app != null) Gdx.app.log(TAG, "V1.3 Maintenance4 feature NOT supported by caps OR NOT being enabled.");
                        features13_to_enable.maintenance4(false); // Ensure it's false if not supported
                    }
                    // **** END MODIFIED SECTION ****

                    MemoryUtil.memPutAddress(pNextChainHead + VkBaseOutStructure.PNEXT, features13_to_enable.address());
                    pNextChainHead = features13_to_enable.address();
                    if (debug && Gdx.app != null) Gdx.app.log(TAG, "Chained VkPhysicalDeviceVulkan13Features.");
                }

                // --- Logging before vkCreateDevice ---
                if (debug && Gdx.app != null) {
                    Gdx.app.log(TAG, "--- Pre-vkCreateDevice Feature Check ---");
                    Gdx.app.log(TAG, "Capabilities: Maintenance4 Supported by PhysicalDevice: " + capabilities.isMaintenance4());
                    Gdx.app.log(TAG, "Capabilities: MaxBufferSize from PhysicalDevice: " + capabilities.getMaxBufferSize());

                    boolean maintenance4FeatureBeingEnabledInChain = false;
                    long currentPNextAddressInspect = features2.pNext();

                    boolean foundV12FeaturesInChain = false;
                    boolean v12ShaderNonUniformEnabledInChain = false;

                    while (currentPNextAddressInspect != NULL) {
                        VkBaseOutStructure pNextStructInspect = VkBaseOutStructure.create(currentPNextAddressInspect);
                        if (pNextStructInspect.sType() == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES) {
                            VkPhysicalDeviceVulkan13Features features13InChainInspect = VkPhysicalDeviceVulkan13Features.create(currentPNextAddressInspect);
                            maintenance4FeatureBeingEnabledInChain = features13InChainInspect.maintenance4();
                            Gdx.app.log(TAG, "VkPhysicalDeviceVulkan13Features in chain to be created: maintenance4 set to: " + maintenance4FeatureBeingEnabledInChain);
                            break;
                        } else if (pNextStructInspect.sType() == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES) {
                            foundV12FeaturesInChain = true;
                            VkPhysicalDeviceVulkan12Features features12InChainInspect = VkPhysicalDeviceVulkan12Features.create(currentPNextAddressInspect);
                            v12ShaderNonUniformEnabledInChain = features12InChainInspect.shaderSampledImageArrayNonUniformIndexing();
                            Gdx.app.log(TAG, "VkPhysicalDeviceVulkan12Features in chain: shaderSampledImageArrayNonUniformIndexing set to: " + v12ShaderNonUniformEnabledInChain);
                            // You can log other 1.2 features here too
                        }
                        VkBaseOutStructure nextStructCandidate = pNextStructInspect.pNext();
                        currentPNextAddressInspect = (nextStructCandidate != null) ? nextStructCandidate.address() : NULL;
                    }
                    if (!maintenance4FeatureBeingEnabledInChain && capabilities.isMaintenance4() && !temporarilyDisableMaintenance4) {
                        Gdx.app.error(TAG, "CRITICAL WARNING: Maintenance4 is supported by caps and NOT temporarily disabled, but NOT being enabled in VkDeviceCreateInfo pNext chain!");
                    } else if (maintenance4FeatureBeingEnabledInChain && temporarilyDisableMaintenance4) {
                        Gdx.app.error(TAG, "DIAGNOSTIC WARNING: Maintenance4 IS being enabled in chain, but temporarilyDisableMaintenance4 was true. Check logic.");
                    }
                    if (foundV12FeaturesInChain && !v12ShaderNonUniformEnabledInChain && capabilities.isShaderSampledImageArrayNonUniformIndexing()) {
                        Gdx.app.error(TAG, "CRITICAL WARNING: V1.2 features struct found in chain, but shaderSampledImageArrayNonUniformIndexing is FALSE in it, despite capabilities reporting TRUE!");
                    } else if (!foundV12FeaturesInChain && capabilities.getApiVersion() >= VK_API_VERSION_1_2) {
                        Gdx.app.error(TAG, "CRITICAL WARNING: VkPhysicalDeviceVulkan12Features struct NOT FOUND in pNext chain for vkCreateDevice, but API is >= 1.2!");
                    }
                    Gdx.app.log(TAG, "--------------------------------------");
                }
                // --- End Logging ---


                VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO).pNext(features2.address())
                        .pQueueCreateInfos(queueCreateInfos).ppEnabledExtensionNames(ppEnabledExtensionNames)
                        .pEnabledFeatures(null);

                PointerBuffer pDevice = stack.mallocPointer(1);
                int vkResult = vkCreateDevice(physicalDevice, createInfo, null, pDevice);
                if (vkResult != VK_SUCCESS) throw new GdxRuntimeException("Failed to create logical Vulkan device: " + vkResult);
                long deviceHandleValue = pDevice.get(0);
                VkDevice lwjglLogicalDevice = new VkDevice(deviceHandleValue, physicalDevice, createInfo, capabilities.getApiVersion());

                PointerBuffer pGraphicsQueue = stack.mallocPointer(1);
                vkGetDeviceQueue(lwjglLogicalDevice, graphicsQueueFamilyIndex, 0, pGraphicsQueue);
                VkQueue graphicsQueue = new VkQueue(pGraphicsQueue.get(0), lwjglLogicalDevice);
                VkQueue presentQueue = graphicsQueue;
                if (graphicsQueueFamilyIndex != presentQueueFamilyIndex) {
                    PointerBuffer pPresentQueue = stack.mallocPointer(1);
                    vkGetDeviceQueue(lwjglLogicalDevice, presentQueueFamilyIndex, 0, pPresentQueue);
                    presentQueue = new VkQueue(pPresentQueue.get(0), lwjglLogicalDevice);
                }

                VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO).pNext(NULL)
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(graphicsQueueFamilyIndex);
                LongBuffer pCommandPool = stack.mallocLong(1);
                vkResult = vkCreateCommandPool(lwjglLogicalDevice, poolInfo, null, pCommandPool);
                if (vkResult != VK_SUCCESS) throw new GdxRuntimeException("Failed to create command pool: " + vkResult);
                long commandPoolHandle = pCommandPool.get(0);

                return new VulkanDevice(lwjglLogicalDevice, graphicsQueue, presentQueue, physicalDevice, commandPoolHandle,
                        this.graphicsQueueFamilyIndex, this.presentQueueFamilyIndex, this.capabilities);
            }
        }
    }
}
