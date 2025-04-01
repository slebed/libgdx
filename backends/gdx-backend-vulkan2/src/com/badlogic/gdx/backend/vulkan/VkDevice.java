package com.badlogic.gdx.backend.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
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

public class VkDevice implements VkResource {

    private final VkDeviceHandle deviceHandle;
    private final VkQueue graphicsQueue;
    private final VkPhysicalDevice physicalDevice;
    private final int queueFamilyIndex;
    private final long commandPool;

    private VkDevice(VkDeviceHandle deviceHandle, VkQueue graphicsQueue, VkPhysicalDevice physicalDevice, long commandPool, int queueFamilyIndex) {
        this.deviceHandle = deviceHandle;
        this.graphicsQueue = graphicsQueue;
        this.physicalDevice = physicalDevice;
        this.commandPool = commandPool;
        this.queueFamilyIndex = queueFamilyIndex;
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
        return commandPool;
    }

    @Override
    public void cleanup() {
        VkMemoryUtil.safeDestroyCommandPool(commandPool, deviceHandle);
        // CRITICAL: Ensure device is destroyed! Add this line if missing:
        if (deviceHandle != null) {
            vkDestroyDevice(deviceHandle, null);
        }
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

        public VkDevice build() {
            try (MemoryStack stack = stackPush()) {
                FloatBuffer queuePriorities = stack.floats(1.0f);

                VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(queueFamilyIndex)
                    .pQueuePriorities(queuePriorities);

                // Check if the device supports the VK_KHR_swapchain extension
                PointerBuffer requiredExtensions = stack.pointers(stack.UTF8("VK_KHR_swapchain"));

                VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCreateInfo)
                    .ppEnabledExtensionNames(requiredExtensions)
                    .pEnabledFeatures(null);

                PointerBuffer pDevice = stack.mallocPointer(1);
                VkMemoryUtil.vkCheck(
                    vkCreateDevice(physicalDevice, createInfo, null, pDevice),
                    "Failed to create logical device"
                );

                VkDeviceHandle device = new VkDeviceHandle(pDevice.get(0), physicalDevice, createInfo);

                PointerBuffer pQueue = stack.mallocPointer(1);
                vkGetDeviceQueue(device, queueFamilyIndex, 0, pQueue);
                VkQueue graphicsQueue = new VkQueue(pQueue.get(0), device);

                // Create Command Pool
                VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(queueFamilyIndex)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

                LongBuffer pCommandPool = stack.mallocLong(1);
                VkMemoryUtil.vkCheck(
                    vkCreateCommandPool(device, poolInfo, null, pCommandPool),
                    "Failed to create command pool"
                );
                long commandPool = pCommandPool.get(0);

                return new VkDevice(device, graphicsQueue, physicalDevice, commandPool, this.queueFamilyIndex);
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Allocate command buffer
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandPool(commandPool) // We'll need to create this later
                .commandBufferCount(1);

            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            VkMemoryUtil.vkCheck(vkAllocateCommandBuffers(deviceHandle, allocInfo, pCommandBuffer), "Failed to allocate command buffer");

            VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), deviceHandle);

            // Begin command buffer recording
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(commandBuffer, beginInfo);

            return commandBuffer;
        }
    }

    public void endSingleTimeCommands(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkEndCommandBuffer(commandBuffer);

            // Submit to queue
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(commandBuffer));

            vkQueueSubmit(graphicsQueue, submitInfo, VK_NULL_HANDLE);
            vkQueueWaitIdle(graphicsQueue);

            // Free the command buffer
            vkFreeCommandBuffers(deviceHandle, commandPool, commandBuffer);
        }
    }


}


