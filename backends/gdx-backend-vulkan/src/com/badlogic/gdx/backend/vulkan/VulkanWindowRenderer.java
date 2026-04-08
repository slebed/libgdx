package com.badlogic.gdx.backend.vulkan;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkQueuePresentKHR;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_CONTENTS_INLINE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBeginRenderPass;
import static org.lwjgl.vulkan.VK10.vkCmdEndRenderPass;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkResetFences;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

/**
 * Owns the per-frame rendering loop for a single VulkanWindow.
 * Handles fence synchronization, command buffer recording, swapchain acquisition,
 * dynamic state updates, queue submission, and presentation.
 */
public class VulkanWindowRenderer implements Disposable {
    private static final String TAG = "VulkanWindowRenderer";

    private final VulkanDevice vulkanDevice;
    private final int maxFramesInFlight;
    private final boolean debug;

    private VulkanSwapchain swapchain;
    private VulkanFrameResources frameResources;
    private VulkanGraphics vulkanGraphics;
    private long renderPass;
    private int currentFrame = 0;
    private volatile boolean framebufferResized = false;
    private com.badlogic.gdx.utils.viewport.Viewport viewportForVkCommands = null;

    public VulkanWindowRenderer(VulkanDevice vulkanDevice, VulkanSwapchain swapchain,
                                 VulkanFrameResources frameResources, VulkanGraphics vulkanGraphics,
                                 long renderPass, int maxFramesInFlight, boolean debug) {
        this.vulkanDevice = vulkanDevice;
        this.swapchain = swapchain;
        this.frameResources = frameResources;
        this.vulkanGraphics = vulkanGraphics;
        this.renderPass = renderPass;
        this.maxFramesInFlight = maxFramesInFlight;
        this.debug = debug;
    }

    /**
     * Renders a single frame. Returns true if rendering occurred, false if skipped.
     */
    public boolean renderFrame(VulkanWindow window, VulkanGraphics gfx, ApplicationListener listener,
                                VulkanWindowConfiguration config) {
        // Pre-render resource checks
        if (frameResources == null || !frameResources.hasResources() ||
                renderPass == VK_NULL_HANDLE ||
                vulkanDevice.getPresentQueue() == null ||
                vulkanDevice.getGraphicsQueue() == null) {
            Gdx.app.error(TAG, "[" + window.hashCode() + "] Skipping render: Essential Vulkan resources/queues missing!");
            return false;
        }
        if (currentFrame < 0 || currentFrame >= maxFramesInFlight) {
            Gdx.app.error(TAG, "[" + window.hashCode() + "] Invalid currentFrame index! currentFrame=" + currentFrame + ", maxFrames=" + maxFramesInFlight);
            return false;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = vulkanDevice.getRawDevice();
            VkQueue graphicsQueue = vulkanDevice.getGraphicsQueue();
            VkQueue presentQueue = vulkanDevice.getPresentQueue();

            // Double-check after obtaining queues
            if (frameResources == null || !frameResources.hasResources() ||
                    renderPass == VK_NULL_HANDLE || presentQueue == null || graphicsQueue == null) {
                Gdx.app.error(TAG, "[" + window.hashCode() + "] Skipping render: Essential Vulkan resources/queues missing!");
                return false;
            }

            // Step 1: Wait for fence
            long fence = frameResources.getFence(currentFrame);
            vkCheck(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "vkWaitForFences failed");
            gfx.prepareAllFrameResources(currentFrame);

            // Step 1.5: Cleanup completed frame descriptor sets
            VulkanDescriptorManager descriptorManager = gfx.getDescriptorManager();
            if (descriptorManager != null) {
                descriptorManager.cleanupCompletedFrameSets(currentFrame);
            } else {
                Gdx.app.error(TAG, "[" + window.hashCode() + "] Descriptor Manager is null, cannot clean up sets!");
            }

            // Step 2: Reset fence
            vkCheck(vkResetFences(device, fence), "Failed to reset fence after wait");

            // Step 3: Acquire the next image
            long imageAvailableSemaphore = frameResources.getImageAvailableSemaphore(currentFrame);
            IntBuffer pImageIndex = stack.mallocInt(1);
            int acquireResultCode;
            try {
                acquireResultCode = swapchain.acquireNextImage(imageAvailableSemaphore, VK_NULL_HANDLE, pImageIndex);
            } catch (Exception e) {
                Gdx.app.error(TAG, "Exception during swapchain.acquireNextImage", e);
                framebufferResized = true;
                acquireResultCode = VK_ERROR_OUT_OF_DATE_KHR;
            }

            // Step 4: Handle swapchain invalidation
            boolean resizeNeeded = framebufferResized;
            boolean justRecreated = false;

            if (acquireResultCode == VK_ERROR_OUT_OF_DATE_KHR || acquireResultCode == VK_SUBOPTIMAL_KHR || resizeNeeded) {
                framebufferResized = false;
                try {
                    recreateSwapchainAndDependents(window);
                    justRecreated = true;

                    int currentBBW = window.getBackBufferWidth();
                    int currentBBH = window.getBackBufferHeight();
                    int currentLW = window.getLogicalWidth();
                    int currentLH = window.getLogicalHeight();
                    gfx.updateFramebufferInfo(currentBBW, currentBBH, currentLW, currentLH);
                    if (listener != null) {
                        VulkanApplication app = (VulkanApplication) Gdx.app;
                        int lw = (app.getAppConfig().hdpiMode == HdpiMode.Pixels) ? currentBBW : currentLW;
                        int lh = (app.getAppConfig().hdpiMode == HdpiMode.Pixels) ? currentBBH : currentLH;
                        if (lw > 0 && lh > 0) listener.resize(lw, lh);
                    }
                } catch (Exception e) {
                    Gdx.app.error(TAG, "[" + window.hashCode() + "] Exception during swapchain recreation!", e);
                    return false;
                }
            } else if (acquireResultCode != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to acquire swapchain image. Result code: " + acquireResultCode);
            }

            if (justRecreated) {
                acquireResultCode = swapchain.acquireNextImage(imageAvailableSemaphore, VK_NULL_HANDLE, pImageIndex);
                if (acquireResultCode != VK_SUCCESS && acquireResultCode != VK_SUBOPTIMAL_KHR) {
                    Gdx.app.error(TAG, "[" + window.hashCode() + "] Failed to acquire image immediately after recreation!");
                    return false;
                }
            }

            int imageIndex = pImageIndex.get(0);

            // Step 5: Record command buffer
            VkCommandBuffer commandBuffer = frameResources.getCommandBuffer(currentFrame);
            vkCheck(vkResetCommandBuffer(commandBuffer, 0), "Failed to reset command buffer");

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            vkCheck(vkBeginCommandBuffer(commandBuffer, beginInfo), "Failed to begin recording command buffer");

            gfx.setCurrentCommandBuffer(commandBuffer);
            gfx.setCurrentRenderPassHandle(this.renderPass);
            gfx.setCurrentFrameIndex(this.currentFrame);

            // Begin Render Pass
            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack).sType$Default();
            renderPassInfo.renderPass(this.renderPass);
            long currentFramebufferHandle = this.swapchain.getFramebuffer(imageIndex);
            if (currentFramebufferHandle == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Could not get valid Framebuffer handle for imageIndex: " + imageIndex);
            }
            renderPassInfo.framebuffer(currentFramebufferHandle);
            renderPassInfo.renderArea().offset().set(0, 0);
            VkExtent2D extent = this.swapchain.getExtent();
            renderPassInfo.renderArea().extent().set(extent);
            VkClearValue.Buffer clearValues = VkClearValue.calloc(2, stack);
            clearValues.get(0).color().float32(stack.floats(
                    config.initialBackgroundColor.r, config.initialBackgroundColor.g,
                    config.initialBackgroundColor.b, config.initialBackgroundColor.a));
            clearValues.get(1).depthStencil().depth(1.0f).stencil(0);
            renderPassInfo.pClearValues(clearValues);
            vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

            // Set dynamic states
            updateDynamicStates(commandBuffer, stack, extent, window);

            // Invoke application render
            if (listener != null) listener.render();

            // End Render Pass & Command Buffer
            vkCmdEndRenderPass(commandBuffer);
            vkCheck(vkEndCommandBuffer(commandBuffer), "Failed to record command buffer");

            // Clear global context
            gfx.setCurrentCommandBuffer(null);
            gfx.setCurrentRenderPassHandle(VK_NULL_HANDLE);

            // Step 6: Submit command buffer
            imageAvailableSemaphore = frameResources.getImageAvailableSemaphore(currentFrame);
            long renderFinishedSemaphore = frameResources.getRenderFinishedSemaphore(currentFrame);
            fence = frameResources.getFence(currentFrame);
            commandBuffer = frameResources.getCommandBuffer(currentFrame);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default();

            LongBuffer waitSemaphores = stack.mallocLong(1);
            waitSemaphores.put(0, imageAvailableSemaphore);

            IntBuffer waitStages = stack.mallocInt(1);
            waitStages.put(0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);

            PointerBuffer pCommandBuffers = stack.mallocPointer(1);
            pCommandBuffers.put(0, commandBuffer);

            LongBuffer signalSemaphores = stack.mallocLong(1);
            signalSemaphores.put(0, renderFinishedSemaphore);

            submitInfo.waitSemaphoreCount(waitSemaphores.limit());
            submitInfo.pWaitSemaphores(waitSemaphores);
            submitInfo.pWaitDstStageMask(waitStages);
            submitInfo.pCommandBuffers(pCommandBuffers);
            submitInfo.pSignalSemaphores(signalSemaphores);

            vkCheck(vkQueueSubmit(graphicsQueue, submitInfo, fence), "Failed to submit draw command buffer");

            // Step 7: Present
            LongBuffer pSwapchains = stack.longs(this.swapchain.getHandle());
            IntBuffer pImageIndices = stack.ints(imageIndex);
            LongBuffer pWaitSemaphoresPresent = stack.longs(renderFinishedSemaphore);

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack).sType$Default()
                    .pWaitSemaphores(pWaitSemaphoresPresent)
                    .swapchainCount(1)
                    .pSwapchains(pSwapchains)
                    .pImageIndices(pImageIndices);

            int presentResultCode = vkQueuePresentKHR(presentQueue, presentInfo);

            if (presentResultCode == VK_ERROR_OUT_OF_DATE_KHR || presentResultCode == VK_SUBOPTIMAL_KHR) {
                framebufferResized = true;
            } else if (presentResultCode != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to present swap chain image: " + VkResult.translate(presentResultCode));
            }

            // Step 8: Advance frame index
            currentFrame = (currentFrame + 1) % maxFramesInFlight;
            return true;

        } catch (Exception e) {
            Gdx.app.error(TAG, "[" + window.hashCode() + "] Exception during Vulkan frame rendering", e);
            gfx.setCurrentCommandBuffer(null);
            gfx.setCurrentRenderPassHandle(VK_NULL_HANDLE);
            if (e instanceof GdxRuntimeException) throw (GdxRuntimeException) e;
            throw new GdxRuntimeException("Vulkan frame rendering failed", e);
        }
    }

    @SuppressWarnings("DefaultLocale")
    private void updateDynamicStates(VkCommandBuffer commandBuffer, MemoryStack stack,
                                      VkExtent2D swapchainExtent, VulkanWindow window) {
        if (debug && Gdx.app != null) {
            Gdx.app.debug(TAG, "updateDynamicStates: ENTER for window " + window.hashCode() +
                    ". SwapchainExtent: " + swapchainExtent.width() + "x" + swapchainExtent.height());
            if (this.viewportForVkCommands != null) {
                Gdx.app.debug(TAG, "updateDynamicStates: viewportForVkCommands IS SET. Hash: " + this.viewportForVkCommands.hashCode() +
                        ", Type: " + this.viewportForVkCommands.getClass().getSimpleName() +
                        ", Screen X/Y: " + this.viewportForVkCommands.getScreenX() + "/" + this.viewportForVkCommands.getScreenY() +
                        ", Screen W/H: " + this.viewportForVkCommands.getScreenWidth() + "/" + this.viewportForVkCommands.getScreenHeight() +
                        ", World W/H: " + this.viewportForVkCommands.getWorldWidth() + "/" + this.viewportForVkCommands.getWorldHeight());
            } else {
                Gdx.app.debug(TAG, "updateDynamicStates: viewportForVkCommands is NULL.");
            }
        }

        float actualVkTargetX;
        float actualVkTargetY_libGDX;
        float actualVkTargetWidth;
        float actualVkTargetHeight;

        if (this.viewportForVkCommands != null) {
            actualVkTargetX = this.viewportForVkCommands.getScreenX();
            actualVkTargetY_libGDX = this.viewportForVkCommands.getScreenY();
            actualVkTargetWidth = this.viewportForVkCommands.getScreenWidth();
            actualVkTargetHeight = this.viewportForVkCommands.getScreenHeight();
            if (Gdx.app != null && debug) {
                Gdx.app.debug(TAG, "updateDynamicStates: USING EXPLICIT viewportForVkCommands. Screen X/Y: " + actualVkTargetX + "/" + actualVkTargetY_libGDX + ", Screen W/H: " + actualVkTargetWidth + "/" + actualVkTargetHeight);
            }
        } else {
            // Default: use full swapchain extent
            actualVkTargetX = 0.0f;
            actualVkTargetY_libGDX = 0.0f;
            actualVkTargetWidth = (float) swapchainExtent.width();
            actualVkTargetHeight = (float) swapchainExtent.height();
            if (Gdx.app != null && debug) {
                Gdx.app.debug(TAG, "updateDynamicStates: NO suitable viewport found. Defaulting to full swapchain extent.");
            }
        }

        // Set Vulkan Viewport
        org.lwjgl.vulkan.VkViewport.Buffer vkViewportBuffer = org.lwjgl.vulkan.VkViewport.calloc(1, stack);
        vkViewportBuffer.x(actualVkTargetX);
        vkViewportBuffer.y(actualVkTargetY_libGDX + actualVkTargetHeight);
        vkViewportBuffer.width(actualVkTargetWidth);
        vkViewportBuffer.height(-actualVkTargetHeight);
        vkViewportBuffer.minDepth(0.0f);
        vkViewportBuffer.maxDepth(1.0f);

        if (Gdx.app != null && debug) {
            Gdx.app.debug(TAG, String.format("updateDynamicStates: FINAL vkViewport PARAMS for window %d: x=%.1f, y=%.1f, width=%.1f, height=%.1f",
                    window.hashCode(), vkViewportBuffer.x(), vkViewportBuffer.y(), vkViewportBuffer.width(), vkViewportBuffer.height()));
        }
        VK10.vkCmdSetViewport(commandBuffer, 0, vkViewportBuffer);

        // Set Vulkan Scissor
        VkRect2D.Buffer vkScissorBuffer = VkRect2D.calloc(1, stack);
        int scissorX = (int) actualVkTargetX;
        int scissorY_vulkanTopLeft = swapchainExtent.height() - ((int) actualVkTargetY_libGDX + (int) actualVkTargetHeight);
        int scissorWidth = (int) actualVkTargetWidth;
        int scissorHeight = (int) actualVkTargetHeight;
        int clampedScissorX = Math.max(0, scissorX);
        int clampedScissorY = Math.max(0, scissorY_vulkanTopLeft);
        int clampedScissorWidth = Math.min(swapchainExtent.width() - clampedScissorX, scissorWidth);
        int clampedScissorHeight = Math.min(swapchainExtent.height() - clampedScissorY, scissorHeight);
        clampedScissorWidth = Math.max(0, clampedScissorWidth);
        clampedScissorHeight = Math.max(0, clampedScissorHeight);

        vkScissorBuffer.offset(VkOffset2D.calloc(stack).set(clampedScissorX, clampedScissorY));
        vkScissorBuffer.extent(VkExtent2D.calloc(stack).set(clampedScissorWidth, clampedScissorHeight));

        if (Gdx.app != null && debug) {
            Gdx.app.debug(TAG, String.format("updateDynamicStates: FINAL vkScissor PARAMS for window %d: x=%d, y=%d, width=%d, height=%d",
                    window.hashCode(), vkScissorBuffer.offset().x(), vkScissorBuffer.offset().y(), vkScissorBuffer.extent().width(), vkScissorBuffer.extent().height()));
        }
        VK10.vkCmdSetScissor(commandBuffer, 0, vkScissorBuffer);
    }

    private void recreateSwapchainAndDependents(VulkanWindow window) {
        VkDevice device = vulkanDevice.getRawDevice();
        vkDeviceWaitIdle(device);

        if (swapchain != null) {
            swapchain.recreate();
        } else {
            throw new GdxRuntimeException("Cannot recreate null swapchain");
        }

        this.renderPass = swapchain.getRenderPass();
        if (this.renderPass == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("RenderPass missing after swapchain recreation");
        }

        if (this.vulkanGraphics != null) {
            this.vulkanGraphics.setMainSwapchainRenderPass(this.renderPass);
            // Invalidate cached pipelines — they reference the old render pass handle
            VulkanPipelineManager pm = this.vulkanGraphics.getPipelineManager();
            if (pm != null) {
                pm.invalidatePipelines();
            }
        }
    }

    public void setViewportForVkCommands(com.badlogic.gdx.utils.viewport.Viewport viewport) {
        this.viewportForVkCommands = viewport;
    }

    public void setFramebufferResized(boolean resized) {
        this.framebufferResized = resized;
    }

    public boolean isFramebufferResized() {
        return framebufferResized;
    }

    public int getCurrentFrame() {
        return currentFrame;
    }

    public long getRenderPass() {
        return renderPass;
    }

    @Override
    public void dispose() {
        // Renderer doesn't own resources directly — VulkanFrameResources and VulkanSwapchain
        // are disposed by VulkanWindow. Just null out references.
        swapchain = null;
        frameResources = null;
        vulkanGraphics = null;
        renderPass = VK_NULL_HANDLE;
    }
}
