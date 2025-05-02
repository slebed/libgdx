
package com.badlogic.gdx.backend.vulkan;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.Consumer;

public class VulkanDevice implements VkResource, Disposable {
    private static final String TAG = "VulkanDevice";
    private VkDeviceHandle deviceHandle;
    private final VkQueue graphicsQueue;
    private final VkPhysicalDevice physicalDevice;
    private long graphicsCommandPool = VK_NULL_HANDLE;
    private final VkQueue presentQueue;

    private VulkanDevice(VkDeviceHandle deviceHandle,
                         VkQueue graphicsQueue,
                         VkQueue presentQueue, // Added
                         VkPhysicalDevice physicalDevice,
                         long graphicsCommandPoolHandle,
                         int graphicsQueueFamilyIndex, // Renamed param
                         int presentQueueFamilyIndex) { // Added
        this.deviceHandle = deviceHandle;
        this.graphicsQueue = graphicsQueue;
        this.presentQueue = presentQueue; // Store present queue
        this.physicalDevice = physicalDevice;
        this.graphicsCommandPool = graphicsCommandPoolHandle;
        // Renamed for clarity
        Gdx.app.log(TAG, "presentQueue: " + presentQueue);
        System.out.println("VulkanDevice created. Graphics Pool: " + this.graphicsCommandPool);
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

    /**
     * Gets the VkQueue handle used for presentation operations.
     * @return The VkQueue used for presentation.
     */
    public VkQueue getPresentQueue() {
        if (presentQueue == null) {
            // Or throw an exception if it should always be valid after creation
            Gdx.app.error("VulkanDevice", "getPresentQueue() called but presentQueue is null!");
        }
        return presentQueue;
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

        if (graphicsCommandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(deviceHandle, graphicsCommandPool, null);
            System.out.println("Graphics Command Pool destroyed.");
        }

        if (deviceHandle != null) {
            vkDestroyDevice(deviceHandle, null);
            System.out.println("Logical Device destroyed.");
        }
        System.out.println("VulkanDevice cleanup finished.");
    }

    public static class Builder {
        private VkPhysicalDevice physicalDevice;
        private int graphicsQueueFamilyIndex; // Renamed field
        private int presentQueueFamilyIndex;  // Added field

        public Builder setPhysicalDevice(VkPhysicalDevice physicalDevice) {
            this.physicalDevice = physicalDevice;
            return this;
        }

        public Builder setQueueFamilyIndex(int index) {
            return this;
        }

        public Builder setGraphicsQueueFamilyIndex(int index) {
            this.graphicsQueueFamilyIndex = index;
            return this;
        }

        // Added setter
        public Builder setPresentQueueFamilyIndex(int index) {
            this.presentQueueFamilyIndex = index;
            return this;
        }


        public VulkanDevice build() {
            try (MemoryStack stack = stackPush()) {
                FloatBuffer queuePriorities = stack.floats(1.0f);
                VkDeviceQueueCreateInfo.Buffer queueCreateInfos;
                boolean separatePresentQueue = graphicsQueueFamilyIndex != presentQueueFamilyIndex;

                if (separatePresentQueue) {
                    queueCreateInfos = VkDeviceQueueCreateInfo.calloc(2, stack);
                    // Graphics Queue Info
                    queueCreateInfos.get(0)
                            .sType$Default()
                            .queueFamilyIndex(graphicsQueueFamilyIndex)
                            .pQueuePriorities(queuePriorities);
                    // Present Queue Info
                    queueCreateInfos.get(1)
                            .sType$Default()
                            .queueFamilyIndex(presentQueueFamilyIndex)
                            .pQueuePriorities(queuePriorities);
                } else {
                    queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack);
                    queueCreateInfos.get(0)
                            .sType$Default()
                            .queueFamilyIndex(graphicsQueueFamilyIndex)
                            .pQueuePriorities(queuePriorities);
                }

                PointerBuffer requiredExtensions = stack.mallocPointer(1);
                requiredExtensions.put(0, stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));

                VkPhysicalDeviceFeatures deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack);

                VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack).sType$Default()
                        .pQueueCreateInfos(queueCreateInfos)
                        .ppEnabledExtensionNames(requiredExtensions)
                        .pEnabledFeatures(deviceFeatures);

                PointerBuffer pDevice = stack.mallocPointer(1);
                vkCheck(vkCreateDevice(physicalDevice, createInfo, null, pDevice), "Failed to create logical device");
                VkDeviceHandle device = new VkDeviceHandle(pDevice.get(0), physicalDevice, createInfo);

                PointerBuffer pGraphicsQueue = stack.mallocPointer(1);
                vkGetDeviceQueue(device, graphicsQueueFamilyIndex, 0, pGraphicsQueue);
                VkQueue graphicsQueue = new VkQueue(pGraphicsQueue.get(0), device);

                PointerBuffer pPresentQueue = stack.mallocPointer(1);
                vkGetDeviceQueue(device, presentQueueFamilyIndex, 0, pPresentQueue);
                VkQueue presentQueue = new VkQueue(pPresentQueue.get(0), device);
                Gdx.app.log(TAG, " pPresentQueue.get(0) = " + pPresentQueue.get(0));

                VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .queueFamilyIndex(graphicsQueueFamilyIndex)
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

                LongBuffer pCommandPool = stack.mallocLong(1);
                vkCheck(vkCreateCommandPool(device, poolInfo, null, pCommandPool), "Failed to create command pool");
                long commandPoolHandle = pCommandPool.get(0);

                return new VulkanDevice(device, graphicsQueue, presentQueue, physicalDevice, commandPoolHandle, this.graphicsQueueFamilyIndex, this.presentQueueFamilyIndex);
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
            java.io.InputStream inputStream = Files.newInputStream(Paths.get(filePath));
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();

            byte[] buffer = new byte[1024]; // Read in chunks of 1KB
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            inputStream.close();
            byte[] shaderCode = outputStream.toByteArray();

            ByteBuffer shaderCodeBuffer = ByteBuffer.allocateDirect(shaderCode.length).put(shaderCode);
            shaderCodeBuffer.flip();

            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(shaderCodeBuffer);

            LongBuffer pShaderModule = stack.mallocLong(1);
            vkCheck(vkCreateShaderModule(this.deviceHandle, createInfo, null, pShaderModule), "Failed to create shader module.");

            return pShaderModule.get(0);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader file: " + filePath, e);
        }
    }

    public VkCommandBuffer beginSingleTimeCommands() {
        try (MemoryStack stack = stackPush()) {
            long poolToUse = this.graphicsCommandPool;
            if (poolToUse == VK_NULL_HANDLE) {
                throw new IllegalStateException("Command pool not initialized before beginSingleTimeCommands");
            }

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandPool(poolToUse)
                    .commandBufferCount(1);

            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            vkCheck(vkAllocateCommandBuffers(deviceHandle, allocInfo, pCommandBuffer), "Failed to allocate single time command buffer");

            VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), deviceHandle);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkCheck(vkBeginCommandBuffer(commandBuffer, beginInfo), "Failed to begin single time command buffer");

            return commandBuffer;
        }
    }

    public void endSingleTimeCommands(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long poolToUse = this.graphicsCommandPool;
            if (poolToUse == VK_NULL_HANDLE) {
                throw new IllegalStateException("Command pool not initialized before endSingleTimeCommands");
            }

            vkCheck(vkEndCommandBuffer(commandBuffer), "Failed to end single time command buffer");

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default()
                    // Need PointerBuffer containing the handle for submission
                    .pCommandBuffers(stack.pointers(commandBuffer.address())); // Get handle address for buffer

            vkCheck(vkQueueSubmit(graphicsQueue, submitInfo, VK_NULL_HANDLE), "Failed to submit single time command buffer");
            vkCheck(vkQueueWaitIdle(graphicsQueue), "Queue wait idle failed after single time command");

            // Free using PointerBuffer containing the handle
            vkFreeCommandBuffers(deviceHandle, poolToUse, stack.pointers(commandBuffer.address()));
        }
    }

    /** Executes a sequence of Vulkan commands recorded by the provided Consumer using a temporary, single-use command buffer.
     * Handles allocation, beginning, ending, submission, waiting, and freeing. Assumes the graphics queue can handle these
     * commands (e.g., transfers, barriers).
     *
     * @param recorder A Consumer lambda that accepts a VkCommandBuffer and records commands into it. */
    public void executeSingleTimeCommands(Consumer<VkCommandBuffer> recorder) {
        if (graphicsCommandPool == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Command pool handle is VK_NULL_HANDLE in executeSingleTimeCommands!");
        }
        if (graphicsQueue == null) {
            throw new GdxRuntimeException("Graphics queue is null in executeSingleTimeCommands!");
        }

        VkCommandBuffer commandBuffer = null; // Hold the wrapper object

        try (MemoryStack stack = stackPush()) {
            // 1. Allocate Command Buffer
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandPool(graphicsCommandPool).commandBufferCount(1);

            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            vkCheck(vkAllocateCommandBuffers(deviceHandle, allocInfo, pCommandBuffer), "Failed to allocate single time command buffer");
            long commandBufferHandle = pCommandBuffer.get(0);
            commandBuffer = new VkCommandBuffer(commandBufferHandle, deviceHandle); // Create wrapper

            // 2. Begin Command Buffer
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT); // Single use flag

            vkCheck(vkBeginCommandBuffer(commandBuffer, beginInfo), "Failed to begin single time command buffer");

            // 3. Record Commands (using the provided lambda)
            // Use try-finally to ensure vkEndCommandBuffer is always called
            try {
                recorder.accept(commandBuffer);
            } finally {
                // 4. End Command Buffer
                vkCheck(vkEndCommandBuffer(commandBuffer), "Failed to end single time command buffer");
            }

            // 5. Submit Command Buffer
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(commandBuffer.address())); // Submit the handle

            System.out.println("VulkanDevice\t" + "Submitting single time command buffer");

            // Submit to the graphics queue, no fence needed as we wait for idle
            vkCheck(vkQueueSubmit(graphicsQueue, submitInfo, VK_NULL_HANDLE), "Failed to submit single time command buffer");

            // 6. Wait for Execution to Finish
            // NOTE: vkQueueWaitIdle is simple but can cause stalls. For high-performance
            // scenarios, using fences is better, but for one-off init tasks, this is often acceptable.
            vkCheck(vkQueueWaitIdle(graphicsQueue), "Queue wait idle failed after single time command");

        } catch (Exception e) {
            // Log or handle potential exceptions during setup/execution
            // Re-throw as a runtime exception
            throw new GdxRuntimeException("Failed during executeSingleTimeCommands", e);

        } finally {
            // 7. Free Command Buffer (always runs)
            if (commandBuffer != null && graphicsCommandPool != VK_NULL_HANDLE) {
                // Use a PointerBuffer allocated on the heap temporarily or use another stack frame
                // Using the heap here is safer if the try-with-resources stack was already closed by an exception
                PointerBuffer pSingleCommandBuffer = org.lwjgl.BufferUtils.createPointerBuffer(1);
                pSingleCommandBuffer.put(0, commandBuffer.address());
                vkFreeCommandBuffers(deviceHandle, graphicsCommandPool, pSingleCommandBuffer);
            }
        }
    }

    @Override
    public void dispose() {
        System.out.println("Disposing VulkanDevice...");

        if (graphicsCommandPool != VK_NULL_HANDLE) {
            System.out.println("Destroying Graphics Command Pool: " + graphicsCommandPool);
            vkDestroyCommandPool(deviceHandle, graphicsCommandPool, null);
            graphicsCommandPool = VK_NULL_HANDLE;
        } else {
            System.out.println("Graphics Command Pool already destroyed or never created.");
        }

        if (deviceHandle != null) {
            System.out.println("Destroying Logical Device.");
            vkDestroyDevice(deviceHandle, null);
            deviceHandle = null;
        } else {
            System.out.println("Logical Device already destroyed or never created.");
        }
        System.out.println("VulkanDevice disposal finished.");
    }
}
