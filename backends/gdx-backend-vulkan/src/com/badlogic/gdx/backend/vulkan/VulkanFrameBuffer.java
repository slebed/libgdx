package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan implementation of an off-screen render target (frame buffer object).
 * Provides a color attachment as a VulkanTexture that can be used for rendering,
 * and an optional depth attachment.
 *
 * <p>Usage pattern (mirrors libGDX FrameBuffer):
 * <pre>
 * fbo.begin();
 * // draw scene into FBO
 * fbo.end();
 * // use fbo.getColorBufferTexture() to draw the result
 * </pre>
 */
public class VulkanFrameBuffer implements Disposable {
    private static final String TAG = "VulkanFrameBuffer";

    private final int width;
    private final int height;
    private final boolean hasDepth;

    // Color attachment
    private VulkanImage colorImage;
    private long colorImageView = VK_NULL_HANDLE;
    private long colorSampler = VK_NULL_HANDLE;
    private VulkanTexture colorTexture;

    // Depth attachment (optional)
    private VulkanImage depthImage;
    private long depthImageView = VK_NULL_HANDLE;
    private int depthFormat;

    // Render pass and framebuffer for this FBO
    private long renderPass = VK_NULL_HANDLE;
    private long framebuffer = VK_NULL_HANDLE;

    // Context references
    private final VulkanDevice device;
    private final long vmaAllocator;
    private final VulkanGraphics graphics;

    // State for restoring the swapchain render pass after end()
    private boolean active = false;

    private boolean disposed = false;

    /**
     * Creates a new VulkanFrameBuffer.
     *
     * @param width     Width in pixels.
     * @param height    Height in pixels.
     * @param hasDepth  Whether to include a depth attachment.
     */
    public VulkanFrameBuffer(int width, int height, boolean hasDepth) {
        if (width <= 0 || height <= 0) {
            throw new GdxRuntimeException("VulkanFrameBuffer dimensions must be positive: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.hasDepth = hasDepth;

        if (!(Gdx.graphics instanceof VulkanGraphics)) {
            throw new GdxRuntimeException("VulkanFrameBuffer requires VulkanGraphics.");
        }
        this.graphics = (VulkanGraphics) Gdx.graphics;
        this.device = graphics.getVulkanDevice();
        this.vmaAllocator = graphics.getVmaAllocator();

        createResources();
    }

    private void createResources() {
        int colorFormat = VK_FORMAT_R8G8B8A8_SRGB;

        // Create color image (TRANSFER_SRC so we can blit from it if needed, SAMPLED so it can be used as texture)
        colorImage = VulkanResourceUtil.createManagedImage(vmaAllocator, width, height, colorFormat,
                VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0);

        // Create color image view
        colorImageView = createImageView(colorImage.imageHandle, colorFormat, VK_IMAGE_ASPECT_COLOR_BIT);

        // Create sampler for the color texture
        colorSampler = VulkanResourceUtil.createSampler(
                device.getLogicalDevice(),
                com.badlogic.gdx.graphics.Texture.TextureFilter.Linear,
                com.badlogic.gdx.graphics.Texture.TextureFilter.Linear,
                com.badlogic.gdx.graphics.Texture.TextureWrap.ClampToEdge,
                com.badlogic.gdx.graphics.Texture.TextureWrap.ClampToEdge,
                false);

        // Transition color image to shader-read-only initially (will be transitioned as needed)
        transitionImageLayout(colorImage.imageHandle, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT);

        // Create depth resources if requested
        if (hasDepth) {
            depthFormat = VulkanSwapchain.findDepthFormat(device.getPhysicalDevice());
            depthImage = VulkanResourceUtil.createManagedImage(vmaAllocator, width, height, depthFormat,
                    VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                    VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0);
            depthImageView = createImageView(depthImage.imageHandle, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT);
        }

        // Create render pass
        renderPass = createRenderPass(colorFormat);

        // Create framebuffer
        framebuffer = createFramebuffer();

        // Wrap the color attachment as a VulkanTexture for user access
        colorTexture = VulkanTexture.wrapExisting(device, colorImage, colorImageView, colorSampler, width, height, colorFormat);
    }

    private long createImageView(long image, int format, int aspectMask) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format)
                    .components(c -> c
                            .r(VK_COMPONENT_SWIZZLE_IDENTITY).g(VK_COMPONENT_SWIZZLE_IDENTITY)
                            .b(VK_COMPONENT_SWIZZLE_IDENTITY).a(VK_COMPONENT_SWIZZLE_IDENTITY))
                    .subresourceRange(r -> r
                            .aspectMask(aspectMask)
                            .baseMipLevel(0).levelCount(1)
                            .baseArrayLayer(0).layerCount(1));

            LongBuffer pView = stack.mallocLong(1);
            vkCheck(vkCreateImageView(device.getLogicalDevice(), viewInfo, null, pView), "Failed to create FBO image view");
            return pView.get(0);
        }
    }

    private long createRenderPass(int colorFormat) {
        try (MemoryStack stack = stackPush()) {
            int attachmentCount = hasDepth ? 2 : 1;
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(attachmentCount, stack);

            // Color attachment
            attachments.get(0)
                    .format(colorFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL); // Ready for sampling after render pass

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);

            if (hasDepth) {
                attachments.get(1)
                        .format(depthFormat)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

                VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                        .attachment(1)
                        .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                subpass.pDepthStencilAttachment(depthRef);
            }

            // Dependencies to ensure proper ordering
            VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(2, stack);

            // External -> FBO subpass
            dependencies.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | (hasDepth ? VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT : 0))
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | (hasDepth ? VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT : 0));

            // FBO subpass -> External (transition to shader-read for sampling)
            dependencies.get(1)
                    .srcSubpass(0)
                    .dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependencies);

            LongBuffer pRenderPass = stack.mallocLong(1);
            vkCheck(vkCreateRenderPass(device.getLogicalDevice(), renderPassInfo, null, pRenderPass),
                    "Failed to create FBO render pass");
            return pRenderPass.get(0);
        }
    }

    private long createFramebuffer() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer attachments;
            if (hasDepth) {
                attachments = stack.longs(colorImageView, depthImageView);
            } else {
                attachments = stack.longs(colorImageView);
            }

            VkFramebufferCreateInfo fbInfo = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                    .renderPass(renderPass)
                    .pAttachments(attachments)
                    .width(width)
                    .height(height)
                    .layers(1);

            LongBuffer pFramebuffer = stack.mallocLong(1);
            vkCheck(vkCreateFramebuffer(device.getLogicalDevice(), fbInfo, null, pFramebuffer),
                    "Failed to create FBO framebuffer");
            return pFramebuffer.get(0);
        }
    }

    private void transitionImageLayout(long image, int oldLayout, int newLayout, int aspectMask) {
        device.executeSingleTimeCommands(commandBuffer -> {
            try (MemoryStack stack = stackPush()) {
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .oldLayout(oldLayout).newLayout(newLayout)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image);
                barrier.subresourceRange()
                        .aspectMask(aspectMask).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

                int srcStage, dstStage;
                if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                    barrier.srcAccessMask(0).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                    srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                    dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                } else {
                    barrier.srcAccessMask(0).dstAccessMask(0);
                    srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                    dstStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                }

                vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, null, null, barrier);
            }
        });
    }

    /**
     * Binds this frame buffer as the current render target. Ends the current swapchain render pass,
     * begins the FBO's render pass with its own framebuffer, and sets the viewport/scissor.
     */
    public void begin() {
        if (disposed) throw new GdxRuntimeException("Cannot begin a disposed VulkanFrameBuffer");
        if (active) throw new GdxRuntimeException("VulkanFrameBuffer.begin() called while already active");

        VkCommandBuffer cmd = graphics.getCurrentVkCommandBuffer();
        if (cmd == null) throw new GdxRuntimeException("No active command buffer for VulkanFrameBuffer.begin()");

        // End the current swapchain render pass
        vkCmdEndRenderPass(cmd);

        // Begin our FBO render pass
        try (MemoryStack stack = stackPush()) {
            VkRenderPassBeginInfo rpInfo = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                    .renderPass(renderPass)
                    .framebuffer(framebuffer);
            rpInfo.renderArea().offset().set(0, 0);
            rpInfo.renderArea().extent().set(width, height);

            int clearCount = hasDepth ? 2 : 1;
            VkClearValue.Buffer clearValues = VkClearValue.calloc(clearCount, stack);
            clearValues.get(0).color().float32(stack.floats(0f, 0f, 0f, 0f)); // Transparent black
            if (hasDepth) {
                clearValues.get(1).depthStencil().depth(1.0f).stencil(0);
            }
            rpInfo.pClearValues(clearValues);

            vkCmdBeginRenderPass(cmd, rpInfo, VK_SUBPASS_CONTENTS_INLINE);

            // Set viewport and scissor for FBO dimensions
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).set(0, (float) height, (float) width, -(float) height, 0f, 1f); // Y-flip
            vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(0, 0);
            scissor.get(0).extent().set(width, height);
            vkCmdSetScissor(cmd, 0, scissor);
        }

        // Update graphics context to reflect FBO render pass
        graphics.setCurrentRenderPassHandle(renderPass);
        active = true;
    }

    /**
     * Ends rendering to this frame buffer and restores the swapchain render pass.
     * After this call, the color buffer texture is ready for sampling.
     */
    public void end() {
        if (!active) throw new GdxRuntimeException("VulkanFrameBuffer.end() called without begin()");

        VkCommandBuffer cmd = graphics.getCurrentVkCommandBuffer();
        if (cmd == null) throw new GdxRuntimeException("No active command buffer for VulkanFrameBuffer.end()");

        // End the FBO render pass
        vkCmdEndRenderPass(cmd);

        // Restore the swapchain render pass using the LOAD_OP_LOAD variant so previously
        // rendered content is preserved (the normal render pass uses LOAD_OP_CLEAR).
        long swapchainFramebuffer = graphics.getCurrentSwapchainFramebuffer();
        VulkanSwapchain swapchain = graphics.getCurrentSwapchain();

        if (swapchainFramebuffer == VK_NULL_HANDLE || swapchain == null) {
            throw new GdxRuntimeException("Cannot restore swapchain render pass — missing state");
        }

        long resumeRenderPass = swapchain.getResumeRenderPass();
        if (resumeRenderPass == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Resume render pass not available on swapchain");
        }

        try (MemoryStack stack = stackPush()) {
            VkRenderPassBeginInfo rpInfo = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                    .renderPass(resumeRenderPass)
                    .framebuffer(swapchainFramebuffer);
            rpInfo.renderArea().offset().set(0, 0);
            VkExtent2D extent = swapchain.getExtent();
            rpInfo.renderArea().extent().set(extent);

            // LOAD_OP_LOAD doesn't use clear values, but Vulkan still requires
            // pClearValues to match the attachment count.
            VkClearValue.Buffer clearValues = VkClearValue.calloc(2, stack);
            rpInfo.pClearValues(clearValues);

            vkCmdBeginRenderPass(cmd, rpInfo, VK_SUBPASS_CONTENTS_INLINE);

            // Restore swapchain viewport/scissor
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).set(0, (float) extent.height(), (float) extent.width(), -(float) extent.height(), 0f, 1f);
            vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(0, 0);
            scissor.get(0).extent().set(extent);
            vkCmdSetScissor(cmd, 0, scissor);
        }

        // Restore graphics context — use the resume render pass handle since that's what's active now.
        // Pipelines are compatible between the two render passes (same attachments, formats, samples).
        graphics.setCurrentRenderPassHandle(resumeRenderPass);
        active = false;
    }

    /** Returns the color buffer as a VulkanTexture that can be drawn with VulkanSpriteBatch. */
    public VulkanTexture getColorBufferTexture() {
        return colorTexture;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public long getRenderPass() {
        return renderPass;
    }

    @Override
    public void dispose() {
        if (disposed) return;

        VkDevice rawDevice = device.getLogicalDevice();

        if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(rawDevice, framebuffer, null);
        if (renderPass != VK_NULL_HANDLE) vkDestroyRenderPass(rawDevice, renderPass, null);

        // Don't dispose colorTexture here — it wraps our resources but doesn't own them
        // (the image/view/sampler are managed by this class)
        if (colorSampler != VK_NULL_HANDLE) vkDestroySampler(rawDevice, colorSampler, null);
        if (colorImageView != VK_NULL_HANDLE) vkDestroyImageView(rawDevice, colorImageView, null);
        if (colorImage != null) colorImage.dispose();

        if (depthImageView != VK_NULL_HANDLE) vkDestroyImageView(rawDevice, depthImageView, null);
        if (depthImage != null) depthImage.dispose();

        disposed = true;
    }
}
