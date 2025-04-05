package com.badlogic.gdx.backend.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkFreeCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkQueueWaitIdle;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;

public class VulkanDevice implements VkResource {

    private final VkDeviceHandle deviceHandle;
    private final VkQueue graphicsQueue;
    private final VkPhysicalDevice physicalDevice;
    private final int queueFamilyIndex;
    //private final long commandPool;
    private long graphicsCommandPool = VK_NULL_HANDLE;

    private VulkanDevice(VkDeviceHandle deviceHandle, VkQueue graphicsQueue, VkPhysicalDevice physicalDevice, long graphicsCommandPoolHandle, int queueFamilyIndex) {
        this.deviceHandle = deviceHandle;
        this.graphicsQueue = graphicsQueue;
        this.physicalDevice = physicalDevice;
        this.graphicsCommandPool = graphicsCommandPoolHandle; // <<< --- Assign the handle passed from Builder
        this.queueFamilyIndex = queueFamilyIndex;

        /*try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    // Flag allows resetting individual command buffers
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    // Use the queue family index you used to create the device/queue
                    .queueFamilyIndex(yourGraphicsQueueFamilyIndex); // Replace with actual index

            LongBuffer pCommandPool = stack.mallocLong(1);
            VkMemoryUtil.vkCheck(vkCreateCommandPool(rawDeviceHandle, poolInfo, null, pCommandPool),
                    "Failed to create command pool");
            this.graphicsCommandPool = pCommandPool.get(0);
            System.out.println("Graphics Command Pool created: " + this.graphicsCommandPool);
        }*/
        System.out.println("VulkanDevice created. Graphics Command Pool Handle: " + this.graphicsCommandPool); // Log stored handle
    }

    public long getHandle() {
        return deviceHandle.address();
    }

    public org.lwjgl.vulkan.VkDevice getRawDevice() {
        return deviceHandle;
    }

    public VkQueue getGraphicsQueue() {
        return graphicsQueue;
    }

    public VkPhysicalDevice getPhysicalDevice() {
        return physicalDevice;
    }

    public long getCommandPool() {
        return this.graphicsCommandPool;
    }

    @Override
    public void cleanup() {
        System.out.println("Cleaning up VulkanDevice...");
        // Destroy command pool FIRST
        if (graphicsCommandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(deviceHandle, graphicsCommandPool, null);
            System.out.println("Graphics Command Pool destroyed.");
        }
        // THEN Destroy the logical device itself
        if (deviceHandle != null) {
            vkDestroyDevice(deviceHandle, null);
            System.out.println("Logical Device destroyed.");
        }
        System.out.println("VulkanDevice cleanup finished.");
    }

    public int getQueueFamilyIndex() {
        return this.queueFamilyIndex;
    }

    public static class Builder {
        private VkPhysicalDevice physicalDevice;
        private int queueFamilyIndex;

        public Builder setPhysicalDevice(VkPhysicalDevice physicalDevice) {
            this.physicalDevice = physicalDevice;
            return this;
        }

        public Builder setQueueFamilyIndex(int index) {
            this.queueFamilyIndex = index;
            return this;
        }

        public VulkanDevice build() {
            try (MemoryStack stack = stackPush()) {
                FloatBuffer queuePriorities = stack.floats(1.0f);

                VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                        .sType$Default() // Use sType$Default() for convenience
                        .queueFamilyIndex(queueFamilyIndex)
                        .pQueuePriorities(queuePriorities);

                // Define required device extensions (swapchain is essential)
                PointerBuffer requiredExtensions = stack.mallocPointer(1);
                requiredExtensions.put(0, stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
                // Add other required extensions here if needed later

                VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                        .sType$Default()
                        .pQueueCreateInfos(queueCreateInfo)
                        .ppEnabledExtensionNames(requiredExtensions) // Pass the buffer
                        .pEnabledFeatures(null); // Enable features later if needed

                PointerBuffer pDevice = stack.mallocPointer(1);
                VkMemoryUtil.vkCheck(
                        vkCreateDevice(physicalDevice, createInfo, null, pDevice),
                        "Failed to create logical device"
                );
                // Use your inner class or LWJGL's VulkanDevice directly
                VkDeviceHandle device = new VkDeviceHandle(pDevice.get(0), physicalDevice, createInfo);

                PointerBuffer pQueue = stack.mallocPointer(1);
                vkGetDeviceQueue(device, queueFamilyIndex, 0, pQueue); // Queue index 0 within the family
                VkQueue graphicsQueue = new VkQueue(pQueue.get(0), device);

                // --- Create Command Pool Correctly HERE ---
                VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .queueFamilyIndex(queueFamilyIndex)
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT); // Or maybe TRANSIENT?

                LongBuffer pCommandPool = stack.mallocLong(1);
                VkMemoryUtil.vkCheck(
                        vkCreateCommandPool(device, poolInfo, null, pCommandPool),
                        "Failed to create command pool"
                );
                long commandPoolHandle = pCommandPool.get(0); // Get the handle
                // -----------------------------------------

                // Pass the created handle to the constructor
                return new VulkanDevice(device, graphicsQueue, physicalDevice, commandPoolHandle, this.queueFamilyIndex);
            }
        }
    }

    private static class VkDeviceHandle extends org.lwjgl.vulkan.VkDevice {
        private final VkPhysicalDevice physicalDevice;
        private final VkDeviceCreateInfo createInfo;

        private VkDeviceHandle(long handle, VkPhysicalDevice physicalDevice, VkDeviceCreateInfo createInfo) {
            super(handle, physicalDevice, createInfo);
            this.physicalDevice = physicalDevice;
            this.createInfo = createInfo;
        }


        public VkPhysicalDevice getPhysicalDevice() {
            return physicalDevice;
        }

        public VkDeviceCreateInfo getCreateInfo() {
            return createInfo;
        }
    }

    public long loadShaderModule(String filePath) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Read the shader file as a stream
            java.io.InputStream inputStream = new java.io.FileInputStream(filePath);
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();

            byte[] buffer = new byte[1024];  // Read in chunks of 1KB
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            inputStream.close();
            byte[] shaderCode = outputStream.toByteArray();

            ByteBuffer shaderCodeBuffer = ByteBuffer.allocateDirect(shaderCode.length).put(shaderCode);
            shaderCodeBuffer.flip();

            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(shaderCodeBuffer);

            LongBuffer pShaderModule = stack.mallocLong(1);
            VkMemoryUtil.vkCheck(
                vkCreateShaderModule(this.deviceHandle, createInfo, null, pShaderModule),
                "Failed to create shader module."
            );

            return pShaderModule.get(0);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader file: " + filePath, e);
        }
    }

    public VkCommandBuffer beginSingleTimeCommands() {
        try (MemoryStack stack = stackPush()) {
            long poolToUse = this.graphicsCommandPool; // Use correct field
            if (poolToUse == VK_NULL_HANDLE) {
                throw new IllegalStateException("Command pool not initialized before beginSingleTimeCommands");
            }

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default() // <<< --- ADDED sType --- <<<
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandPool(poolToUse) // Use correct field
                    .commandBufferCount(1);

            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            VkMemoryUtil.vkCheck(vkAllocateCommandBuffers(deviceHandle, allocInfo, pCommandBuffer),
                    "Failed to allocate single time command buffer");

            VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), deviceHandle); // Create wrapper

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VkMemoryUtil.vkCheck(vkBeginCommandBuffer(commandBuffer, beginInfo), // Use wrapper
                    "Failed to begin single time command buffer");

            return commandBuffer;
        }
    }

    public void endSingleTimeCommands(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long poolToUse = this.graphicsCommandPool; // Use correct field
            if (poolToUse == VK_NULL_HANDLE) {
                throw new IllegalStateException("Command pool not initialized before endSingleTimeCommands");
            }

            VkMemoryUtil.vkCheck(vkEndCommandBuffer(commandBuffer), // Use wrapper
                    "Failed to end single time command buffer");

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    // Need PointerBuffer containing the handle for submission
                    .pCommandBuffers(stack.pointers(commandBuffer.address())); // Get handle address for buffer

            VkMemoryUtil.vkCheck(vkQueueSubmit(graphicsQueue, submitInfo, VK_NULL_HANDLE),
                    "Failed to submit single time command buffer");
            VkMemoryUtil.vkCheck(vkQueueWaitIdle(graphicsQueue),
                    "Queue wait idle failed after single time command");

            // Free using PointerBuffer containing the handle
            vkFreeCommandBuffers(deviceHandle, poolToUse, stack.pointers(commandBuffer.address())); // Pass handle in buffer
        }
    }


}


