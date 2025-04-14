package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Represents a Vulkan Texture, combining the Image, ImageView, and Sampler.
 * Manages the lifecycle of these resources.
 */
public class VulkanTexture extends Texture {
    private final String logTag = "VulkanTexture";

    // Make fields final if they are always initialized in constructors
    private final VulkanDevice device;
    private final VulkanImage vulkanImage;
    private final long imageViewHandle;
    private final long samplerHandle;
    private final int width;
    private final int height;
    private final int format; // Store VkFormat
    private boolean disposed = false;

    /**
     * Constructor that automatically retrieves Vulkan context from Gdx.graphics.
     * Assumes Vulkan backend is initialized and active.
     *
     * @param file The FileHandle of the image to load.
     * @throws GdxRuntimeException if Gdx.graphics is not VulkanGraphics or context is invalid.
     */
    public VulkanTexture(FileHandle file) {
        super(); // Call the protected no-op Texture() constructor FIRST

        if (file == null || !file.exists()) {
            throw new GdxRuntimeException("FileHandle cannot be null and must exist: " + file);
        }

        Gdx.app.log(logTag, "(Constructor) Loading texture from: " + file.path());

        // 1. Get Vulkan Context (Device and Allocator)
        if (!(Gdx.graphics instanceof VulkanGraphics)) {
            throw new GdxRuntimeException("Cannot create VulkanTexture: Gdx.graphics is not an instance of VulkanGraphics.");
        }
        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
        VulkanDevice retrievedDevice = gfx.getVulkanDevice();
        long vmaAllocator = gfx.getVmaAllocator();

        if (retrievedDevice == null || vmaAllocator == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot create VulkanTexture: VulkanDevice or VMA Allocator not available from VulkanGraphics.");
        }

        // Temporary variables for resource handles to allow cleanup in case of partial failure
        VulkanBuffer stagingBuffer = null;
        VulkanImage tempGpuImage = null;
        long tempImageView = VK_NULL_HANDLE;
        long tempSampler = VK_NULL_HANDLE;
        Pixmap originalPixmap = null;
        Pixmap rgbaPixmap = null;

        try {
            // 2. Load Pixmap & ensure RGBA8888
            originalPixmap = new Pixmap(file);
            if (originalPixmap.getFormat() != Pixmap.Format.RGBA8888) {
                Gdx.app.log(logTag, "Converting Pixmap to RGBA8888...");
                rgbaPixmap = new Pixmap(originalPixmap.getWidth(), originalPixmap.getHeight(), Pixmap.Format.RGBA8888);
                rgbaPixmap.setBlending(Pixmap.Blending.None); // Disable blending for direct copy
                rgbaPixmap.drawPixmap(originalPixmap, 0, 0);
                originalPixmap.dispose(); // Dispose original immediately
                originalPixmap = null;
            } else {
                rgbaPixmap = originalPixmap;
                originalPixmap = null; // Original is now the rgbaPixmap
            }

            int texWidth = rgbaPixmap.getWidth();
            int texHeight = rgbaPixmap.getHeight();
            int vkFormat = VK_FORMAT_R8G8B8A8_SRGB; // Assuming SRGB for typical image files
            long imageSize = (long) texWidth * texHeight * 4;
            ByteBuffer pixelBuffer = rgbaPixmap.getPixels();

            // 3. Create Staging Buffer via VMA
            stagingBuffer = VulkanResourceUtil.createManagedBuffer(
                    vmaAllocator, imageSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VMA_MEMORY_USAGE_AUTO,
                    VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
            );

            // 4. Map, Copy Pixmap data, Unmap
            PointerBuffer pData = MemoryUtil.memAllocPointer(1);
            try {
                vkCheck(vmaMapMemory(vmaAllocator, stagingBuffer.allocationHandle, pData), "VMA Failed to map texture staging buffer");
                ByteBuffer stagingByteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int) imageSize);
                stagingByteBuffer.put(pixelBuffer); // Copy data from Pixmap buffer
                stagingByteBuffer.flip(); // Not strictly needed after put, but good practice
                vmaUnmapMemory(vmaAllocator, stagingBuffer.allocationHandle);
            } finally {
                MemoryUtil.memFree(pData);
            }
            // We can dispose the Pixmap now as its data is in the staging buffer
            rgbaPixmap.dispose();
            rgbaPixmap = null;


            // 5. Create Final GPU Image via VMA
            tempGpuImage = VulkanResourceUtil.createManagedImage(
                    vmaAllocator, texWidth, texHeight, vkFormat, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0
            );

            // 6. Perform Layout Transitions and Copy (using the retrieved device)
            transitionImageLayoutCmd(retrievedDevice, tempGpuImage.imageHandle, vkFormat, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyBufferToImageCmd(retrievedDevice, stagingBuffer.bufferHandle, tempGpuImage.imageHandle, texWidth, texHeight);
            transitionImageLayoutCmd(retrievedDevice, tempGpuImage.imageHandle, vkFormat, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            Gdx.app.log(logTag, "VMA Image created and data uploaded.");

            // 7. Create ImageView
            tempImageView = createImageViewInternal(retrievedDevice.getRawDevice(), tempGpuImage.imageHandle, vkFormat);
            Gdx.app.log(logTag, "ImageView created: " + tempImageView);

            // 8. Create Sampler
            tempSampler = createSamplerInternal(retrievedDevice.getRawDevice());
            Gdx.app.log(logTag, "Sampler created: " + tempSampler);

            // 9. Assign to final fields *after* all steps succeed
            this.device = retrievedDevice;
            this.vulkanImage = tempGpuImage;
            this.imageViewHandle = tempImageView;
            this.samplerHandle = tempSampler;
            this.width = texWidth;
            this.height = texHeight;
            this.format = vkFormat;

            Gdx.app.log(logTag, "VulkanTexture created successfully from " + file.path());

        } catch (Exception e) {
            // Cleanup intermediate resources if constructor failed
            Gdx.app.error(logTag, "Failed during VulkanTexture constructor for: " + file.path(), e);
            // Destroy in reverse order of creation attempt
            if (tempSampler != VK_NULL_HANDLE) vkDestroySampler(retrievedDevice.getRawDevice(), tempSampler, null);
            if (tempImageView != VK_NULL_HANDLE) vkDestroyImageView(retrievedDevice.getRawDevice(), tempImageView, null);
            if (tempGpuImage != null) tempGpuImage.dispose(); // Calls vmaDestroyImage

            // stagingBuffer and pixmaps are handled in finally block
            throw new GdxRuntimeException("Failed to create VulkanTexture from FileHandle", e); // Re-throw
        } finally {
            // Always cleanup staging buffer and any remaining pixmaps
            if (stagingBuffer != null) stagingBuffer.dispose(); // Calls vmaDestroyBuffer
            if (rgbaPixmap != null) rgbaPixmap.dispose();       // Should be null if successful put(), but check anyway
            if (originalPixmap != null) originalPixmap.dispose(); // Should be null anyway
        }
    }

    // Private constructor, use loadFromFile factory method
    private VulkanTexture(VulkanDevice device, VulkanImage vulkanImage, long imageViewHandle, long samplerHandle) {
        super(); // <<< CORRECTED: Call the protected no-op Texture() constructor

        // Initialize fields AFTER super() call
        this.device = device;
        this.vulkanImage = vulkanImage;
        this.imageViewHandle = imageViewHandle;
        this.samplerHandle = samplerHandle;
        this.width = vulkanImage.width;
        this.height = vulkanImage.height;
        this.format = vulkanImage.format;
    }

    // --- Getters ---
    public long getImageViewHandle() {
        return imageViewHandle;
    }

    public long getSamplerHandle() {
        return samplerHandle;
    }

    public long getImageHandle() {
        return vulkanImage != null ? vulkanImage.imageHandle : VK_NULL_HANDLE;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFormat() {
        return format;
    } // Return VkFormat


    /**
     * Factory method to load a texture from a file.
     * Handles Pixmap loading, staging buffer creation, VMA image creation,
     * view/sampler creation, data upload, and layout transitions.
     *
     * @param file         The FileHandle to load from (e.g., Gdx.files.internal(...)).
     * @param device       The VulkanDevice wrapper.
     * @param vmaAllocator The VMA Allocator handle.
     * @return A new VulkanTexture instance.
     */
    public static VulkanTexture loadFromFile(FileHandle file, VulkanDevice device, long vmaAllocator) {
        final String logTag = "VulkanTextureLoader"; // Specific tag for loading
        Gdx.app.log(logTag, "Loading texture from: " + file.path());

        if (device == null || vmaAllocator == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VulkanDevice and VMA Allocator cannot be null for texture loading.");
        }

        Pixmap originalPixmap = null;
        Pixmap rgbaPixmap = null;
        VulkanBuffer stagingBuffer = null;
        VulkanImage finalGpuImage = null;
        long imageView = VK_NULL_HANDLE;
        long sampler = VK_NULL_HANDLE;

        try {
            // 1. Load Pixmap & ensure RGBA8888
            originalPixmap = new Pixmap(file);
            if (originalPixmap.getFormat() != Pixmap.Format.RGBA8888) {
                Gdx.app.log(logTag, "Converting Pixmap to RGBA8888...");
                rgbaPixmap = new Pixmap(originalPixmap.getWidth(), originalPixmap.getHeight(), Pixmap.Format.RGBA8888);
                rgbaPixmap.setBlending(Pixmap.Blending.None);
                rgbaPixmap.drawPixmap(originalPixmap, 0, 0);
                originalPixmap.dispose();
                originalPixmap = null;
            } else {
                rgbaPixmap = originalPixmap;
                originalPixmap = null;
            }

            int texWidth = rgbaPixmap.getWidth();
            int texHeight = rgbaPixmap.getHeight();
            // Assuming SRGB format for textures loaded from typical image files
            int vkFormat = VK_FORMAT_R8G8B8A8_SRGB;
            long imageSize = (long) texWidth * texHeight * 4;
            ByteBuffer pixelBuffer = rgbaPixmap.getPixels();

            // 2. Create Staging Buffer via VMA
            stagingBuffer = VulkanResourceUtil.createManagedBuffer(
                    vmaAllocator, imageSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VMA_MEMORY_USAGE_AUTO,
                    VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
            );

            // 3. Map, Copy Pixmap data, Unmap
            PointerBuffer pData = MemoryUtil.memAllocPointer(1);
            try {
                vkCheck(vmaMapMemory(vmaAllocator, stagingBuffer.allocationHandle, pData), "VMA Failed to map texture staging buffer");
                ByteBuffer stagingByteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int) imageSize);
                stagingByteBuffer.put(pixelBuffer);
                stagingByteBuffer.flip();
                vmaUnmapMemory(vmaAllocator, stagingBuffer.allocationHandle);
            } finally {
                MemoryUtil.memFree(pData);
            }

            // 4. Create Final GPU Image via VMA
            finalGpuImage = VulkanResourceUtil.createManagedImage(
                    vmaAllocator, texWidth, texHeight, vkFormat, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, // Dest for copy, Sampled for shader
                    VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0
            );

            // 5. Perform Layout Transitions and Copy (using helpers)
            // These helpers need access to the device to execute commands
            transitionImageLayoutCmd(device, finalGpuImage.imageHandle, vkFormat, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyBufferToImageCmd(device, stagingBuffer.bufferHandle, finalGpuImage.imageHandle, texWidth, texHeight);
            transitionImageLayoutCmd(device, finalGpuImage.imageHandle, vkFormat, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            Gdx.app.log(logTag, "VMA Image created and data uploaded.");

            // 6. Create ImageView
            imageView = createImageViewInternal(device.getRawDevice(), finalGpuImage.imageHandle, vkFormat);
            Gdx.app.log(logTag, "ImageView created: " + imageView);

            // 7. Create Sampler (Using default settings for now)
            sampler = createSamplerInternal(device.getRawDevice());
            Gdx.app.log(logTag, "Sampler created: " + sampler);

            // If all successful, create the VulkanTexture instance
            return new VulkanTexture(device, finalGpuImage, imageView, sampler);

        } catch (Exception e) {
            // Cleanup intermediate resources if creation failed
            Gdx.app.error(logTag, "Failed to load texture from file: " + file.path(), e);
            if (imageView != VK_NULL_HANDLE) vkDestroyImageView(device.getRawDevice(), imageView, null);
            if (sampler != VK_NULL_HANDLE) vkDestroySampler(device.getRawDevice(), sampler, null);
            if (finalGpuImage != null) finalGpuImage.dispose(); // Calls vmaDestroyImage
            throw new GdxRuntimeException("Failed to load texture", e); // Re-throw
        } finally {
            // Always cleanup staging buffer and pixmaps
            if (stagingBuffer != null) stagingBuffer.dispose(); // Calls vmaDestroyBuffer
            if (rgbaPixmap != null) rgbaPixmap.dispose();
            if (originalPixmap != null) originalPixmap.dispose(); // Should be null already
        }
    }

    private static long createImageViewInternal(VkDevice rawDevice, long imageHandle, int format) {
        Gdx.app.log("VulkanTexture", "Creating internal image view...");
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(imageHandle)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format)
                    .components(c -> c.r(VK_COMPONENT_SWIZZLE_IDENTITY).g(VK_COMPONENT_SWIZZLE_IDENTITY).b(VK_COMPONENT_SWIZZLE_IDENTITY).a(VK_COMPONENT_SWIZZLE_IDENTITY))
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT) // Assuming color
                            .baseMipLevel(0).levelCount(1) // TODO: Support mipmaps
                            .baseArrayLayer(0).layerCount(1)); // TODO: Support array layers

            LongBuffer pImageView = stack.mallocLong(1);
            vkCheck(vkCreateImageView(rawDevice, viewInfo, null, pImageView), "Failed to create texture image view");
            return pImageView.get(0);
        }
    }

    private static long createSamplerInternal(VkDevice rawDevice) {
        // TODO: Parameterize sampler settings (filter, wrap, anisotropy)
        // TODO: Implement Sampler Caching
        Gdx.app.log("VulkanTexture", "Creating internal sampler (default settings)...");
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .anisotropyEnable(false)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false)
                    .compareEnable(false).compareOp(VK_COMPARE_OP_ALWAYS)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR) // Needs mip levels > 1 on image to be effective
                    .mipLodBias(0.0f).minLod(0.0f).maxLod(0.0f); // Use maxLod > 0 for mipmaps

            LongBuffer pSampler = stack.mallocLong(1);
            vkCheck(vkCreateSampler(rawDevice, samplerInfo, null, pSampler), "Failed to create texture sampler");
            return pSampler.get(0);
        }
    }

    private static void transitionImageLayoutCmd(VulkanDevice device, long image, int format, int oldLayout, int newLayout) {
        device.executeSingleTimeCommands(commandBuffer -> {
            try (MemoryStack stack = stackPush()) {
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .oldLayout(oldLayout).newLayout(newLayout)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image);

                int aspectMask;
                if (newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
                    aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT;
                    if (hasStencilComponentInternal(format)) {
                        aspectMask |= VK_IMAGE_ASPECT_STENCIL_BIT;
                    }
                } else {
                    aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
                }
                barrier.subresourceRange().aspectMask(aspectMask).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

                int sourceStage, destinationStage;
                if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                    barrier.srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                    sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                    destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                    sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                    destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                } else {
                    throw new GdxRuntimeException("Unsupported layout transition!");
                } // TODO: Add more transitions

                vkCmdPipelineBarrier(commandBuffer, sourceStage, destinationStage, 0, null, null, barrier);
            }
        });
    }

    private static void copyBufferToImageCmd(VulkanDevice device, long buffer, long image, int width, int height) {
        device.executeSingleTimeCommands(commandBuffer -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                        .bufferOffset(0)
                        .bufferRowLength(width)
                        .bufferImageHeight(0)
                        .imageSubresource(is -> is.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                        .imageOffset(off -> off.set(0, 0, 0))
                        .imageExtent(ext -> ext.set(width, height, 1));
                vkCmdCopyBufferToImage(commandBuffer, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }
        });
    }

    private static boolean hasStencilComponentInternal(int format) {
        return format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT;
    }


    // --- Dispose ---
    @Override
    public void dispose() {
        Gdx.app.log(logTag, "Disposing texture (" + width + "x" + height + ")...");

        if (disposed) {
            Gdx.app.debug(logTag, "Texture already disposed."); // Optional log
            return;
        }
        // Check device validity early
        VkDevice rawDevice = (device != null) ? device.getRawDevice() : null;
        if (rawDevice == null) {
            Gdx.app.error(logTag, "Cannot dispose texture, VulkanDevice reference is null! Handles might leak.");
            disposed = true; // Mark as disposed even if cleanup failed
            return;
        }

        // Destroy view and sampler FIRST
        if (imageViewHandle != VK_NULL_HANDLE) { // Check required if field isn't nulled below
            Gdx.app.log(logTag, "Destroying image view: " + imageViewHandle);
            vkDestroyImageView(rawDevice, imageViewHandle, null);
            // imageViewHandle = VK_NULL_HANDLE; // Cannot do if final
        }
        if (samplerHandle != VK_NULL_HANDLE) { // Check required if field isn't nulled below
            Gdx.app.log(logTag, "Destroying sampler: " + samplerHandle);
            vkDestroySampler(rawDevice, samplerHandle, null);
            // samplerHandle = VK_NULL_HANDLE; // Cannot do if final
        }

        // Dispose the underlying VulkanImage
        if (vulkanImage != null) {
            vulkanImage.dispose(); // VulkanImage.dispose needs to be idempotent too!
            // vulkanImage = null; // Cannot do if final
        }

        disposed = true; // --- Mark as disposed ---
        Gdx.app.log(logTag, "Texture disposed.");
    }

    @Override
    public int getTextureObjectHandle() {
        return 0;
    }

    @Override
    public void bind() {

    }

    @Override
    public void bind(int unit) {

    }

    @Override
    public TextureFilter getMinFilter() {
        // TODO: Return filter based on samplerHandle settings
        return TextureFilter.Linear; // Placeholder
    }

    @Override
    public TextureFilter getMagFilter() {
        // TODO: Return filter based on samplerHandle settings
        return TextureFilter.Linear; // Placeholder
    }

    @Override
    public TextureWrap getUWrap() {
        // TODO: Return wrap mode based on samplerHandle settings
        return TextureWrap.Repeat; // Placeholder
    }

    @Override
    public TextureWrap getVWrap() {
        // TODO: Return wrap mode based on samplerHandle settings
        return TextureWrap.Repeat; // Placeholder
    }

    @Override
    public boolean isManaged() {
        // VMA handles memory, not libGDX Texture management
        return false;
    }

    @Override
    public void draw(Pixmap pixmap, int x, int y) {
        // This method is for drawing *onto* a texture (like a framebuffer).
        // Requires implementing texture as a render target.
        throw new GdxRuntimeException("VulkanTexture.draw(Pixmap) not implemented yet.");
    }

    @Override
    public int getDepth() {
        return 0; // Only 2D textures implemented currently
    }

    @Override
    public void setFilter(TextureFilter minFilter, TextureFilter magFilter) {
        // NO-OP for VulkanTexture in this basic implementation.
        // The VkSampler used by this texture was created with fixed settings during load.
        // To properly support this, we would need to potentially find or create
        // a new VkSampler matching these filters and update descriptor sets referencing it,
        // which is significantly more complex.
        Gdx.app.debug(logTag, "VulkanTexture.setFilter() called, ignoring. Min: " + minFilter + ", Mag: " + magFilter);
        // DO NOT call super.setFilter(minFilter, magFilter);
    }

    @Override
    public void setWrap(TextureWrap u, TextureWrap v) {
        // NO-OP for VulkanTexture in this basic implementation.
        // The VkSampler used by this texture was created with fixed settings during load.
        // Similar complexity to setFilter applies to supporting runtime wrap changes.
        Gdx.app.debug(logTag, "VulkanTexture.setWrap() called, ignoring. U: " + u + ", V: " + v);
        // DO NOT call super.setWrap(u, v);
    }
}