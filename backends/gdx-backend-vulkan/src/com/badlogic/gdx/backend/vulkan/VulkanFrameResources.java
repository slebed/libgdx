package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns per-frame synchronization objects, command pool, and command buffers
 * for a single window's rendering pipeline.
 */
public class VulkanFrameResources implements Disposable {
    private static final String TAG = "VulkanFrameResources";

    private final VkDevice device;
    private final int maxFramesInFlight;

    private long commandPool = VK_NULL_HANDLE;
    private final List<VkCommandBuffer> commandBuffers;
    private final List<Long> imageAvailableSemaphores;
    private final List<Long> renderFinishedSemaphores;
    private final List<Long> inFlightFences;
    private boolean disposed = false;

    public VulkanFrameResources(VulkanDevice vulkanDevice, int graphicsQueueFamily, int maxFramesInFlight) {
        this.device = vulkanDevice.getRawDevice();
        this.maxFramesInFlight = maxFramesInFlight;
        this.commandBuffers = new ArrayList<>(maxFramesInFlight);
        this.imageAvailableSemaphores = new ArrayList<>(maxFramesInFlight + 1);
        this.renderFinishedSemaphores = new ArrayList<>(maxFramesInFlight + 1);
        this.inFlightFences = new ArrayList<>(maxFramesInFlight + 1);

        boolean initSuccess = false;
        try {
            createCommandPool(graphicsQueueFamily);
            allocateCommandBuffers();
            createSyncObjects();
            initSuccess = true;
        } finally {
            if (!initSuccess) {
                dispose();
            }
        }
    }

    private void createCommandPool(int graphicsQueueFamily) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(graphicsQueueFamily);
            LongBuffer pCommandPool = stack.mallocLong(1);
            VkResult result = VkResult.translate(vkCreateCommandPool(device, poolInfo, null, pCommandPool));
            if (!result.isSuccess()) {
                throw new GdxRuntimeException("Failed to create command pool: " + result);
            }
            this.commandPool = pCommandPool.get(0);
        }
    }

    private void allocateCommandBuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(this.commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(maxFramesInFlight);
            PointerBuffer pCommandBuffers = stack.mallocPointer(maxFramesInFlight);
            VkResult result = VkResult.translate(vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers));
            if (!result.isSuccess()) {
                throw new GdxRuntimeException("Failed to allocate command buffers: " + result);
            }
            for (int i = 0; i < maxFramesInFlight; i++) {
                this.commandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
            }
        }
    }

    private void createSyncObjects() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer pSemaphore = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);

            // Create initial set (index 0)
            vkCheck(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore), "Failed to create imageAvailable semaphore");
            imageAvailableSemaphores.add(pSemaphore.get(0));

            vkCheck(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore), "Failed to create renderFinished semaphore");
            renderFinishedSemaphores.add(pSemaphore.get(0));

            vkCheck(vkCreateFence(device, fenceInfo, null, pFence), "Failed to create inFlight fence");
            inFlightFences.add(pFence.get(0));

            // Create remaining sets (indices 1..maxFramesInFlight)
            for (int i = 0; i < maxFramesInFlight; i++) {
                VkResult r1 = VkResult.translate(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore));
                if (!r1.isSuccess()) throw new GdxRuntimeException("Failed to create imageAvailable semaphore: " + r1);
                imageAvailableSemaphores.add(pSemaphore.get(0));

                VkResult r2 = VkResult.translate(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore));
                if (!r2.isSuccess()) throw new GdxRuntimeException("Failed to create renderFinished semaphore: " + r2);
                renderFinishedSemaphores.add(pSemaphore.get(0));

                VkResult r3 = VkResult.translate(vkCreateFence(device, fenceInfo, null, pFence));
                if (!r3.isSuccess()) throw new GdxRuntimeException("Failed to create inFlight fence: " + r3);
                inFlightFences.add(pFence.get(0));
            }
        }
    }

    public VkCommandBuffer getCommandBuffer(int frame) {
        return commandBuffers.get(frame);
    }

    public long getFence(int frame) {
        return inFlightFences.get(frame);
    }

    public long getImageAvailableSemaphore(int frame) {
        return imageAvailableSemaphores.get(frame);
    }

    public long getRenderFinishedSemaphore(int frame) {
        return renderFinishedSemaphores.get(frame);
    }

    public long getCommandPool() {
        return commandPool;
    }

    public int getMaxFramesInFlight() {
        return maxFramesInFlight;
    }

    public boolean hasResources() {
        return !commandBuffers.isEmpty() && !inFlightFences.isEmpty()
                && !imageAvailableSemaphores.isEmpty() && !renderFinishedSemaphores.isEmpty();
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;

        if (device == null) return;

        for (long semaphore : imageAvailableSemaphores) {
            if (semaphore != VK_NULL_HANDLE) vkDestroySemaphore(device, semaphore, null);
        }
        for (long semaphore : renderFinishedSemaphores) {
            if (semaphore != VK_NULL_HANDLE) vkDestroySemaphore(device, semaphore, null);
        }
        for (long fence : inFlightFences) {
            if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, null);
        }
        imageAvailableSemaphores.clear();
        renderFinishedSemaphores.clear();
        inFlightFences.clear();

        // Command buffers are freed when pool is destroyed
        commandBuffers.clear();
        if (commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device, commandPool, null);
            commandPool = VK_NULL_HANDLE;
        }
    }
}
