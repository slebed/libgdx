package com.badlogic.gdx.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.EXTDescriptorIndexing.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES_EXT;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDescriptorIndexing.VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.*;


import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

public class VulkanDevice implements Disposable {
    private static final String TAG = VulkanDevice.class.getSimpleName();
    private static final boolean debug = false; // Or from a shared config

    private final VkDevice logicalDevice; // This is the primary LWJGL VkDevice object
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;
    private final VkPhysicalDevice physicalDevice;
    private long commandPool; // Should be final if not recreated
    private final int graphicsQueueFamilyIndex;
    private final int presentQueueFamilyIndex;
    private final VulkanDeviceCapabilities capabilities;

    private boolean isDisposed = false;

    public VulkanDevice(VkDevice logicalDevice, VkQueue graphicsQueue, VkQueue presentQueue,
                        VkPhysicalDevice physicalDevice, long commandPool,
                        int graphicsQueueFamilyIndex, int presentQueueFamilyIndex,
                        VulkanDeviceCapabilities capabilities) {
        this.logicalDevice = logicalDevice;
        this.graphicsQueue = graphicsQueue;
        this.presentQueue = presentQueue;
        this.physicalDevice = physicalDevice;
        this.commandPool = commandPool;
        this.graphicsQueueFamilyIndex = graphicsQueueFamilyIndex;
        this.presentQueueFamilyIndex = presentQueueFamilyIndex;
        this.capabilities = capabilities;
        if (debug && Gdx.app != null) Gdx.app.log(TAG, "VulkanDevice created. Graphics Queue: " + graphicsQueue.address() + ", Present Queue: " + presentQueue.address());
    }

    public VkDevice getLogicalDevice() {
        return this.logicalDevice;
    }

    /**
     * @deprecated Use getLogicalDevice().address() if you need the raw long handle.
     * getRawDevice() returning VkDevice might be confusing.
     */
    @Deprecated
    public VkDevice getRawDevice() {
        return getLogicalDevice();
    }

    public long getRawDeviceHandle() {
        // The actual Vulkan handle is obtained via address() on the LWJGL VkDevice object
        if (this.logicalDevice == null) return 0L; // Or VK_NULL_HANDLE directly if it's defined as 0L
        return this.logicalDevice.address(); // Corrected: use address()
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

    public VulkanDeviceCapabilities getCapabilities() {
        return capabilities;
    }

    /**
     * Checks if the Vulkan logical device is considered available for operations.
     * This means the device has been created and not yet disposed.
     *
     * @return true if the logical device is available, false otherwise.
     */
    public boolean isDeviceAvailable() {
        if (isDisposed) {
            return false;
        }
        // Check if the logicalDevice object exists and its underlying Vulkan handle is valid
        // logicalDevice.address() is the correct LWJGL method.
        return this.logicalDevice != null && this.logicalDevice.address() != VK_NULL_HANDLE; // Corrected: use address()
    }

    public void executeSingleTimeCommands(Consumer<VkCommandBuffer> commands) {
        if (!isDeviceAvailable() || graphicsQueue == null || commandPool == VK_NULL_HANDLE) {
            if (Gdx.app != null) Gdx.app.error(TAG, "Cannot execute single time commands: device, queue, or command pool is not initialized or device is disposed.");
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
            vkQueueWaitIdle(graphicsQueue); // Ensure command completes
            vkFreeCommandBuffers(logicalDevice, commandPool, commandBuffer);
        }
    }

    public static void transitionImageLayoutCmd(VulkanDevice device, long image, int format, int oldLayout, int newLayout) {
        if (device == null || !device.isDeviceAvailable()) {
            if (Gdx.app != null) Gdx.app.error(TAG, "VulkanDevice is null or not available in transitionImageLayoutCmd");
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
                    barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    sourceStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
                    destinationStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
                }
                vkCmdPipelineBarrier(commandBuffer, sourceStage, destinationStage, 0, null, null, barrier);
            }
        });
    }

    private void cleanupInternal() {
        if (debug && Gdx.app != null) Gdx.app.log(TAG, "Cleaning up VulkanDevice resources...");

        if (commandPool != VK_NULL_HANDLE) {
            // Use isDeviceAvailable() or check logicalDevice and its handle before using it
            if (!isDeviceAvailable()) {
                if (debug && Gdx.app != null) Gdx.app.error(TAG, "Cannot destroy command pool, logicalDevice is not valid.");
            } else {
                vkDestroyCommandPool(logicalDevice, commandPool, null);
                if (debug && Gdx.app != null) Gdx.app.log(TAG, "Command pool destroyed.");
            }
            commandPool = VK_NULL_HANDLE;
        }
        if (debug && Gdx.app != null) Gdx.app.log(TAG, "VulkanDevice internal cleanup finished.");
    }

    @Override
    public void dispose() {
        if (isDisposed) {
            return;
        }
        cleanupInternal();
        isDisposed = true;
        if (debug && Gdx.app != null) Gdx.app.log(TAG, "VulkanDevice disposed (marked as unusable).");
    }

    public static class Builder {
        private VkPhysicalDevice physicalDevice;
        private int graphicsQueueFamilyIndex = -1;
        private int presentQueueFamilyIndex = -1;
        private VulkanDeviceCapabilities capabilities;
        private boolean temporarilyDisableMaintenance4 = true;

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

                // Enable VK_EXT_descriptor_indexing if API < 1.2 and capabilities indicate it's supported (via extension)
                boolean enableDescriptorIndexingViaExtension = capabilities.getApiVersion() < VK_API_VERSION_1_2 &&
                        capabilities.isDescriptorIndexingSupported(); // isDescriptorIndexingSupported will be true if ext was found

                if (enableDescriptorIndexingViaExtension) {
                    enabledExtensionsList.add(VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME);
                    if (debug && Gdx.app != null) Gdx.app.log(TAG, "Enabling device extension: " + VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME);
                } else if (capabilities.getApiVersion() >= VK_API_VERSION_1_2 && capabilities.isDescriptorIndexingSupported()) {
                    if (debug && Gdx.app != null) Gdx.app.log(TAG, "Descriptor Indexing features to be enabled via Vulkan 1.2 core features.");
                }

                PointerBuffer ppEnabledExtensionNames = null;
                if (!enabledExtensionsList.isEmpty()) {
                    ppEnabledExtensionNames = stack.mallocPointer(enabledExtensionsList.size());
                    for (int i = 0; i < enabledExtensionsList.size(); i++) {
                        ppEnabledExtensionNames.put(i, stack.UTF8(enabledExtensionsList.get(i)));
                    }
                }

                VkPhysicalDeviceFeatures features0 = VkPhysicalDeviceFeatures.calloc(stack);
                if (capabilities.isSamplerAnisotropy()) features0.samplerAnisotropy(true);
                if (capabilities.isGeometryShader()) features0.geometryShader(true);
                // ... Add other V1.0 features based on capabilities ...
                if (capabilities.isShaderSampledImageArrayDynamicIndexing()) features0.shaderSampledImageArrayDynamicIndexing(true);
                if (capabilities.isShaderStorageBufferArrayDynamicIndexing()) features0.shaderStorageBufferArrayDynamicIndexing(true);
                if (capabilities.isShaderUniformBufferArrayDynamicIndexing()) features0.shaderUniformBufferArrayDynamicIndexing(true);
                // ... etc for other V1.0 features from your capabilities class


                VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                        .features(features0);

                long pNextChainHead = features2.address();

                if (capabilities.getApiVersion() >= VK_API_VERSION_1_1) {
                    VkPhysicalDeviceVulkan11Features features11 = VkPhysicalDeviceVulkan11Features.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES).pNext(NULL);
                    if (capabilities.isStorageBuffer16BitAccess()) features11.storageBuffer16BitAccess(true);
                    if (capabilities.isVariablePointersStorageBuffer()) features11.variablePointersStorageBuffer(true);
                    if (capabilities.isVariablePointers()) features11.variablePointers(true);
                    if (capabilities.isShaderDrawParameters()) features11.shaderDrawParameters(true);
                    // ... other 1.1 features from capabilities ...
                    MemoryUtil.memPutAddress(pNextChainHead + VkBaseOutStructure.PNEXT, features11.address());
                    pNextChainHead = features11.address();
                }

                // Descriptor Indexing Features
                if (capabilities.getApiVersion() >= VK_API_VERSION_1_2 && capabilities.isDescriptorIndexingSupported()) {
                    VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES).pNext(NULL);

                    features12.descriptorIndexing(true); // Enable the main DI feature
                    if (capabilities.isRuntimeDescriptorArray()) features12.runtimeDescriptorArray(true);
                    if (capabilities.isShaderSampledImageArrayNonUniformIndexing()) features12.shaderSampledImageArrayNonUniformIndexing(true);
                    if (capabilities.isShaderStorageBufferArrayNonUniformIndexing()) features12.shaderStorageBufferArrayNonUniformIndexing(true);
                    if (capabilities.isDescriptorBindingPartiallyBound()) features12.descriptorBindingPartiallyBound(true);
                    if (capabilities.isDescriptorBindingVariableDescriptorCount()) features12.descriptorBindingVariableDescriptorCount(true);
                    if (capabilities.isShaderInputAttachmentArrayDynamicIndexing()) features12.shaderInputAttachmentArrayDynamicIndexing(true);
                    if (capabilities.isShaderUniformTexelBufferArrayDynamicIndexing()) features12.shaderUniformTexelBufferArrayDynamicIndexing(true);
                    if (capabilities.isShaderStorageTexelBufferArrayDynamicIndexing()) features12.shaderStorageTexelBufferArrayDynamicIndexing(true);
                    if (capabilities.isShaderUniformBufferArrayNonUniformIndexing()) features12.shaderUniformBufferArrayNonUniformIndexing(true);
                    if (capabilities.isShaderInputAttachmentArrayNonUniformIndexing()) features12.shaderInputAttachmentArrayNonUniformIndexing(true);
                    if (capabilities.isShaderUniformTexelBufferArrayNonUniformIndexing()) features12.shaderUniformTexelBufferArrayNonUniformIndexing(true);
                    if (capabilities.isShaderStorageTexelBufferArrayNonUniformIndexing()) features12.shaderStorageTexelBufferArrayNonUniformIndexing(true);
                    if (capabilities.isDescriptorBindingUniformBufferUpdateAfterBind()) features12.descriptorBindingUniformBufferUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingSampledImageUpdateAfterBind()) features12.descriptorBindingSampledImageUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingStorageImageUpdateAfterBind()) features12.descriptorBindingStorageImageUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingStorageBufferUpdateAfterBind()) features12.descriptorBindingStorageBufferUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingUniformTexelBufferUpdateAfterBind()) features12.descriptorBindingUniformTexelBufferUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingStorageTexelBufferUpdateAfterBind()) features12.descriptorBindingStorageTexelBufferUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingUpdateUnusedWhilePending()) features12.descriptorBindingUpdateUnusedWhilePending(true);


                    if (capabilities.isScalarBlockLayout()) features12.scalarBlockLayout(true);
                    if (capabilities.isTimelineSemaphore()) features12.timelineSemaphore(true);
                    if (capabilities.isBufferDeviceAddress()) features12.bufferDeviceAddress(true);
                    // ... other 1.2 features from capabilities ...
                    MemoryUtil.memPutAddress(pNextChainHead + VkBaseOutStructure.PNEXT, features12.address());
                    pNextChainHead = features12.address();
                } else if (enableDescriptorIndexingViaExtension) { // Only if extension was added and API < 1.2
                    VkPhysicalDeviceDescriptorIndexingFeaturesEXT extFeatures = VkPhysicalDeviceDescriptorIndexingFeaturesEXT.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES_EXT).pNext(NULL);
                    if (capabilities.isRuntimeDescriptorArray()) extFeatures.runtimeDescriptorArray(true);
                    if (capabilities.isShaderSampledImageArrayNonUniformIndexing()) extFeatures.shaderSampledImageArrayNonUniformIndexing(true);
                    if (capabilities.isShaderStorageBufferArrayNonUniformIndexing()) extFeatures.shaderStorageBufferArrayNonUniformIndexing(true);
                    if (capabilities.isDescriptorBindingPartiallyBound()) extFeatures.descriptorBindingPartiallyBound(true);
                    if (capabilities.isDescriptorBindingVariableDescriptorCount()) extFeatures.descriptorBindingVariableDescriptorCount(true);
                    if (capabilities.isShaderInputAttachmentArrayDynamicIndexing()) extFeatures.shaderInputAttachmentArrayDynamicIndexing(true);
                    if (capabilities.isShaderUniformTexelBufferArrayDynamicIndexing()) extFeatures.shaderUniformTexelBufferArrayDynamicIndexing(true);
                    if (capabilities.isShaderStorageTexelBufferArrayDynamicIndexing()) extFeatures.shaderStorageTexelBufferArrayDynamicIndexing(true);
                    if (capabilities.isShaderUniformBufferArrayNonUniformIndexing()) extFeatures.shaderUniformBufferArrayNonUniformIndexing(true);
                    if (capabilities.isShaderInputAttachmentArrayNonUniformIndexing()) extFeatures.shaderInputAttachmentArrayNonUniformIndexing(true);
                    if (capabilities.isShaderUniformTexelBufferArrayNonUniformIndexing()) extFeatures.shaderUniformTexelBufferArrayNonUniformIndexing(true);
                    if (capabilities.isShaderStorageTexelBufferArrayNonUniformIndexing()) extFeatures.shaderStorageTexelBufferArrayNonUniformIndexing(true);
                    if (capabilities.isDescriptorBindingUniformBufferUpdateAfterBind()) extFeatures.descriptorBindingUniformBufferUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingSampledImageUpdateAfterBind()) extFeatures.descriptorBindingSampledImageUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingStorageImageUpdateAfterBind()) extFeatures.descriptorBindingStorageImageUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingStorageBufferUpdateAfterBind()) extFeatures.descriptorBindingStorageBufferUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingUniformTexelBufferUpdateAfterBind()) extFeatures.descriptorBindingUniformTexelBufferUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingStorageTexelBufferUpdateAfterBind()) extFeatures.descriptorBindingStorageTexelBufferUpdateAfterBind(true);
                    if (capabilities.isDescriptorBindingUpdateUnusedWhilePending()) extFeatures.descriptorBindingUpdateUnusedWhilePending(true);
                    MemoryUtil.memPutAddress(pNextChainHead + VkBaseOutStructure.PNEXT, extFeatures.address());
                    pNextChainHead = extFeatures.address();
                }

                if (capabilities.getApiVersion() >= VK_API_VERSION_1_3) {
                    VkPhysicalDeviceVulkan13Features features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES).pNext(NULL);
                    if (capabilities.isDynamicRendering()) features13.dynamicRendering(true);
                    if (capabilities.isSynchronization2()) features13.synchronization2(true);
                    if (capabilities.isMaintenance4() && !temporarilyDisableMaintenance4) {
                        features13.maintenance4(true);
                    } else if (capabilities.isMaintenance4() && temporarilyDisableMaintenance4) {
                        if (debug && Gdx.app != null) Gdx.app.log(TAG, "DIAGNOSTIC: Maintenance4 supported BUT TEMPORARILY DISABLED for this test.");
                    }
                    // ... other 1.3 features from capabilities ...
                    MemoryUtil.memPutAddress(pNextChainHead + VkBaseOutStructure.PNEXT, features13.address());
                }

                VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                        .pNext(features2.address())
                        .pQueueCreateInfos(queueCreateInfos)
                        .ppEnabledExtensionNames(ppEnabledExtensionNames);

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
