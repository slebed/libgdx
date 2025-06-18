package com.badlogic.gdx.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.vma.Vma.*;
import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck; // Or check result directly
import static org.lwjgl.vulkan.VK10.*;

import com.badlogic.gdx.Gdx; // For logging
// For creating NIO buffers
// VulkanBuffer should implement this
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.GdxRuntimeException;

// For Buffer operations like flip()
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer; // Added for ShortBuffer overload

/**
 * Utility class for creating and managing Vulkan resources, primarily buffers and images,
 * using the Vulkan Memory Allocator (VMA) library.
 */
public class VulkanResourceUtil {

    private static final String TAG = "VulkanResourceUtil";
    private static final boolean DEBUG = false;

    /**
     * Creates a Vulkan buffer managed by VMA.
     * This method allocates the buffer and its memory according to the specified usage and flags.
     *
     * @param allocator VMA allocator instance handle.
     * @param size Size of the buffer in bytes.
     * @param bufferUsageFlags VkBufferUsageFlags describing how the buffer will be used (e.g., VERTEX_BUFFER_BIT, TRANSFER_SRC_BIT).
     * @param vmaMemoryUsage VmaMemoryUsage hint suggesting the preferred memory type (e.g., VMA_MEMORY_USAGE_AUTO, _GPU_ONLY, _CPU_ONLY).
     * @param vmaAllocFlags VmaAllocationCreateFlags specifying allocation behavior (e.g., HOST_ACCESS_SEQUENTIAL_WRITE_BIT, MAPPED_BIT).
     * @return A VulkanBuffer wrapper object containing the handles and size.
     * @throws GdxRuntimeException if buffer creation fails.
     */
    public static VulkanBuffer createManagedBuffer(long allocator, long size, int bufferUsageFlags,
                                                   int vmaMemoryUsage, int vmaAllocFlags) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default().size(size).usage(bufferUsageFlags)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(vmaMemoryUsage).flags(vmaAllocFlags);

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1); // VmaAllocation*

            // *** Crucial: VmaAllocationInfo to get pMappedData ***
            VmaAllocationInfo allocationInfoPojo = VmaAllocationInfo.calloc(stack);

            vkCheck(Vma.vmaCreateBuffer(allocator, bufferInfo, allocInfo, pBuffer, pAllocation, allocationInfoPojo), // Pass allocationInfoPojo
                    "VMA failed to create buffer");

            long bufferHandle = pBuffer.get(0);
            long allocationHandle = pAllocation.get(0);

            if (bufferHandle == VK_NULL_HANDLE || allocationHandle == VK_NULL_HANDLE) {
                if (bufferHandle != VK_NULL_HANDLE || allocationHandle != VK_NULL_HANDLE) {
                    Vma.vmaDestroyBuffer(allocator, bufferHandle, allocationHandle);
                }
                throw new GdxRuntimeException("VMA buffer creation returned NULL handle(s).");
            }

            ByteBuffer persistentlyMappedBuffer = null;
            if ((vmaAllocFlags & VMA_ALLOCATION_CREATE_MAPPED_BIT) != 0) {
                long pMappedDataAddress = allocationInfoPojo.pMappedData(); // Get from VmaAllocationInfo
                if (pMappedDataAddress != NULL) {
                    persistentlyMappedBuffer = MemoryUtil.memByteBuffer(pMappedDataAddress, (int) size);
                    if (Gdx.app != null && DEBUG) { // Assuming DEBUG is a static field
                        Gdx.app.debug(TAG, "Buffer " + bufferHandle + " created with persistent VMA mapping. Address: " + pMappedDataAddress);
                    }
                } else {
                    if (Gdx.app != null) Gdx.app.error(TAG, "VMA_ALLOCATION_CREATE_MAPPED_BIT was set but pMappedData is NULL for buffer: " + bufferHandle);
                }
            }
            // Make sure your VulkanBuffer constructor can take this ByteBuffer
            return new VulkanBuffer(bufferHandle, allocationHandle, size, allocator, persistentlyMappedBuffer);
        }
    }

    /**
     * Creates a Vulkan image managed by VMA.
     * Simplified version for 2D images; extend parameters for more complex scenarios (mipmaps, arrays, etc.).
     *
     * @param allocator VMA allocator instance handle.
     * @param width Image width in pixels.
     * @param height Image height in pixels.
     * @param format VkFormat specifying the image format.
     * @param tiling VkImageTiling (OPTIMAL for device access, LINEAR for host access).
     * @param imageUsageFlags VkImageUsageFlags describing how the image will be used (e.g., SAMPLED_BIT, TRANSFER_DST_BIT).
     * @param vmaMemoryUsage VmaMemoryUsage hint for memory type.
     * @param vmaAllocFlags VmaAllocationCreateFlags.
     * @return A VulkanImage wrapper object.
     * @throws GdxRuntimeException if image creation fails.
     */
    public static VulkanImage createManagedImage(long allocator, int width, int height, int format, int tiling,
                                                 int imageUsageFlags, int vmaMemoryUsage, int vmaAllocFlags) {
        try (MemoryStack stack = stackPush()) {
            // Define image creation parameters
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(e -> e.width(width).height(height).depth(1)) // Extent for 2D image
                    .mipLevels(1) // Assuming single mip level
                    .arrayLayers(1) // Assuming single array layer
                    .samples(VK_SAMPLE_COUNT_1_BIT) // Assuming no multisampling
                    .tiling(tiling)
                    .usage(imageUsageFlags)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED); // Image layout must be transitioned before use

            // Define VMA allocation parameters
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(vmaMemoryUsage)
                    .flags(vmaAllocFlags);
            // Consider VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT if required

            // Pointers to receive created handles
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);

            // Create image and allocate memory using VMA
            vkCheck(vmaCreateImage(allocator, imageInfo, allocInfo, pImage, pAllocation, null),
                    "VMA failed to create image");

            long imageHandle = pImage.get(0);
            long allocationHandle = pAllocation.get(0);

            // Basic validation
            if (imageHandle == VK_NULL_HANDLE || allocationHandle == VK_NULL_HANDLE) {
                if (imageHandle != VK_NULL_HANDLE || allocationHandle != VK_NULL_HANDLE) {
                    vmaDestroyImage(allocator, imageHandle, allocationHandle);
                }
                throw new GdxRuntimeException("VMA image creation returned NULL handle(s) despite success code.");
            }

            // Return wrapper object
            return new VulkanImage(imageHandle, allocationHandle, format, width, height, allocator);
        }
    }

    /**
     * Creates a device-local buffer (optimized for GPU access) and uploads data from a FloatBuffer
     * using an intermediate staging buffer.
     *
     * @param device The VulkanDevice context, needed for executing transfer commands.
     * @param vmaAllocator The VMA allocator instance.
     * @param dataBuffer The FloatBuffer containing the data to upload. Must be positioned correctly (e.g., after flip()).
     * @param targetUsageFlags The final usage flags for the device-local buffer (e.g., VK_BUFFER_USAGE_VERTEX_BUFFER_BIT).
     * VK_BUFFER_USAGE_TRANSFER_DST_BIT will be added automatically.
     * @return A VulkanBuffer representing the device-local buffer containing the uploaded data.
     * @throws GdxRuntimeException if any Vulkan or VMA operation fails.
     * @throws IllegalArgumentException if dataBuffer is null or empty.
     */
    public static VulkanBuffer createDeviceLocalBuffer(VulkanDevice device, long vmaAllocator, FloatBuffer dataBuffer, int targetUsageFlags) {
        if (dataBuffer == null || !dataBuffer.hasRemaining()) {
            throw new IllegalArgumentException("Input FloatBuffer cannot be null or empty for device local buffer creation.");
        }

        long bufferSize = (long) dataBuffer.remaining() * Float.BYTES;
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("Calculated buffer size must be positive.");
        }
        VulkanBuffer stagingBuffer = null; // Declare outside try for finally block

        try {
            // 1. Create Staging Buffer (Host Visible, Mapped)
            stagingBuffer = createManagedBuffer(
                    vmaAllocator,
                    bufferSize,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT, // Usage: Source for memory transfer
                    VMA_MEMORY_USAGE_AUTO,            // VMA likely picks HOST_VISIBLE + HOST_COHERENT
                    VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT // Need mapped for writing
            );

            // 2. Copy data from FloatBuffer to the Staging Buffer
            PointerBuffer pData = MemoryUtil.memAllocPointer(1); // Pointer to receive mapped memory address
            try {
                // Map the staging buffer memory
                vkCheck(Vma.vmaMapMemory(vmaAllocator, stagingBuffer.allocationHandle, pData),
                        "VMA failed to map staging buffer memory");
                // Get a ByteBuffer view of the mapped memory
                ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int) stagingBuffer.size);
                // Copy data from the input FloatBuffer into the mapped ByteBuffer
                byteBuffer.asFloatBuffer().put(dataBuffer);
                // Unmap the memory (VMA handles potential flushing if memory is not HOST_COHERENT)
                Vma.vmaUnmapMemory(vmaAllocator, stagingBuffer.allocationHandle);
            } finally {
                MemoryUtil.memFree(pData); // Free the temporary pointer buffer
            }

            // 3. Create the Final Device-Local Buffer
            VulkanBuffer finalBuffer = createManagedBuffer(
                    vmaAllocator,
                    bufferSize,
                    targetUsageFlags | VK_BUFFER_USAGE_TRANSFER_DST_BIT, // Final usage + Destination for transfer
                    VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, // Hint: Prefer GPU-only memory
                    0 // No special flags needed for typical device-local buffer
            );

            // 4. Record and Submit Commands to Copy from Staging to Device-Local Buffer
            // This requires a helper method on the VulkanDevice or similar context
            // to handle command buffer allocation, recording, submission, and synchronization.
            final VulkanBuffer finalStagingBuffer = stagingBuffer; // Need final variable for lambda capture
            device.executeSingleTimeCommands(cmd -> { // Assuming this helper exists
                try (MemoryStack stack = stackPush()) {
                    // Define the copy operation parameters
                    VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack)
                            .srcOffset(0) // Copy from the beginning of the staging buffer
                            .dstOffset(0) // Copy to the beginning of the final buffer
                            .size(bufferSize); // Copy the entire buffer content
                    // Record the copy command
                    vkCmdCopyBuffer(cmd, finalStagingBuffer.bufferHandle, finalBuffer.bufferHandle, copyRegion);
                }
            });

            // 5. Return the final device-local buffer
            return finalBuffer;

        } catch (Exception e) {
            // Cleanup staging buffer if creation failed mid-way
            if (stagingBuffer != null) {
                stagingBuffer.dispose();
            }
            // Re-throw exception
            throw new GdxRuntimeException("Failed to create device local buffer from FloatBuffer", e);
        } finally {
            // 6. Clean up the staging buffer after the copy is submitted (or if an error occurred)
            if (stagingBuffer != null) {
                stagingBuffer.dispose();
            }
        }
    }

    /**
     * Overload for creating a device-local buffer from a ShortBuffer (e.g., for index buffers).
     *
     * @param device The VulkanDevice context.
     * @param vmaAllocator The VMA allocator instance.
     * @param dataBuffer The ShortBuffer containing the data to upload. Must be positioned correctly.
     * @param targetUsageFlags The final usage flags (e.g., VK_BUFFER_USAGE_INDEX_BUFFER_BIT).
     * VK_BUFFER_USAGE_TRANSFER_DST_BIT will be added automatically.
     * @return A VulkanBuffer representing the device-local buffer containing the uploaded data.
     * @throws GdxRuntimeException if any Vulkan or VMA operation fails.
     * @throws IllegalArgumentException if dataBuffer is null or empty.
     */
    public static VulkanBuffer createDeviceLocalBuffer(VulkanDevice device, long vmaAllocator, ShortBuffer dataBuffer, int targetUsageFlags) {
        if (dataBuffer == null || !dataBuffer.hasRemaining()) {
            throw new IllegalArgumentException("Input ShortBuffer cannot be null or empty for device local buffer creation.");
        }

        long bufferSize = (long) dataBuffer.remaining() * Short.BYTES;
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("Calculated buffer size must be positive.");
        }
        VulkanBuffer stagingBuffer = null;

        try {
            // 1. Create Staging Buffer
            stagingBuffer = createManagedBuffer(
                    vmaAllocator, bufferSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VMA_MEMORY_USAGE_AUTO,
                    VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
            );

            // 2. Copy data to Staging Buffer
            PointerBuffer pData = MemoryUtil.memAllocPointer(1);
            try {
                vkCheck(Vma.vmaMapMemory(vmaAllocator, stagingBuffer.allocationHandle, pData), "VMA map failed");
                ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int) stagingBuffer.size);
                byteBuffer.asShortBuffer().put(dataBuffer); // Copy ShortBuffer data
                Vma.vmaUnmapMemory(vmaAllocator, stagingBuffer.allocationHandle);
            } finally {
                MemoryUtil.memFree(pData);
            }

            // 3. Create Final Device-Local Buffer
            VulkanBuffer finalBuffer = createManagedBuffer(
                    vmaAllocator, bufferSize, targetUsageFlags | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0
            );

            // 4. Copy from Staging to Device-Local
            final VulkanBuffer finalStagingBuffer = stagingBuffer;
            device.executeSingleTimeCommands(cmd -> {
                try (MemoryStack stack = stackPush()) {
                    VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack).size(bufferSize);
                    vkCmdCopyBuffer(cmd, finalStagingBuffer.bufferHandle, finalBuffer.bufferHandle, copyRegion);
                }
            });

            return finalBuffer;

        } catch (Exception e) {
            if (stagingBuffer != null) stagingBuffer.dispose();
            throw new GdxRuntimeException("Failed to create device local buffer from ShortBuffer", e);
        } finally {
            if (stagingBuffer != null) stagingBuffer.dispose();
        }
    }


    /**
     * Helper method to update data in a host-visible Vulkan buffer managed by VMA.
     * Assumes the buffer was created with appropriate host-access flags.
     * Handles mapping, copying, flushing (if needed), and unmapping.
     *
     * @param allocator VMA allocator instance.
     * @param buffer The VulkanBuffer to update (must be host-visible).
     * @param data The float array containing the data to write.
     * @param offset Offset in bytes within the Vulkan buffer where writing should start.
     * @param length Number of bytes to write from the data array. Should be <= data.length * Float.BYTES.
     * @throws GdxRuntimeException if mapping or updating fails.
     * @throws IllegalArgumentException if buffer is invalid or update range exceeds buffer size.
     */
    public static void updateBuffer(long allocator, VulkanBuffer buffer, float[] data, long offset, long length) {
        // --- Input Validation ---
        if (buffer == null || buffer.allocationHandle == VK_NULL_HANDLE) {
            throw new IllegalArgumentException("Cannot update null or invalid buffer.");
        }
        if (data == null) {
            throw new IllegalArgumentException("Input data array cannot be null.");
        }
        if (offset < 0 || length <= 0) {
            throw new IllegalArgumentException("Offset must be non-negative and length must be positive.");
        }
        if (offset + length > buffer.size) {
            throw new IllegalArgumentException("Update range exceeds buffer size (Offset: " + offset + ", Length: " + length + ", BufferSize: " + buffer.size + ")");
        }
        long requiredDataBytes = (long) data.length * Float.BYTES;
        if (length > requiredDataBytes) {
            Gdx.app.error(TAG, "Update length (" + length + " bytes) exceeds size of provided data array (" + requiredDataBytes + " bytes). Clamping length.");
            length = requiredDataBytes; // Clamp length to prevent reading past data array end
            if (length <= 0) return; // Nothing to write if data array was empty
        }
        // --- End Validation ---


        PointerBuffer pData = MemoryUtil.memAllocPointer(1); // Pointer to receive mapped address
        boolean mapped = false; // Track if mapping was successful
        try {
            // 1. Map the buffer memory
            vkCheck(Vma.vmaMapMemory(allocator, buffer.allocationHandle, pData), "VMA map failed for buffer update");
            mapped = true; // Mapping succeeded

            // 2. Get a ByteBuffer view for the specific update range
            ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(pData.get(0) + offset, (int) length);
            FloatBuffer floatBuffer = byteBuffer.asFloatBuffer(); // Get a FloatBuffer view

            // 3. Copy data from the float array to the mapped buffer
            // Calculate how many floats fit into the specified 'length' (in bytes)
            int floatsToWrite = (int) (length / Float.BYTES);
            // Ensure we don't read past the end of the input 'data' array
            floatsToWrite = Math.min(floatsToWrite, data.length);
            floatBuffer.put(data, 0, floatsToWrite); // Put the calculated number of floats

            // 4. Flush memory if the allocation is not HOST_COHERENT
            // This ensures writes are visible to the GPU. VMA often uses coherent memory
            // with HOST_ACCESS flags, but flushing is safer if unsure.
            // TODO: Check allocation flags/info to determine if flush is strictly necessary.
            Vma.vmaFlushAllocation(allocator, buffer.allocationHandle, offset, length);

            // 5. Unmap the memory
            Vma.vmaUnmapMemory(allocator, buffer.allocationHandle);
            mapped = false; // Unmapping succeeded

        } catch (Exception e) {
            // Rethrow exceptions, ensuring unmap is attempted if mapping occurred
            throw new GdxRuntimeException("Failed to update buffer data", e);
        } finally {
            // Ensure unmap is called if mapping succeeded but an error occurred before explicit unmap
            if (mapped) {
                try {
                    Vma.vmaUnmapMemory(allocator, buffer.allocationHandle);
                } catch (Exception unmapE) {
                    Gdx.app.error(TAG, "Error during unmap in finally block", unmapE); // Log secondary error
                }
            }
            MemoryUtil.memFree(pData); // Always free the pointer buffer
        }
    }

    /**
     * Creates a VkSampler.
     *
     * @param rawDevice The raw VkDevice.
     * @param minFilter LibGDX TextureFilter for minification.
     * @param magFilter LibGDX TextureFilter for magnification.
     * @param uWrap LibGDX TextureWrap for U coordinate.
     * @param vWrap LibGDX TextureWrap for V coordinate.
     * @param anisotropyEnabled Whether anisotropic filtering should be enabled (if supported by device).
     * @return The handle of the created VkSampler.
     * @throws GdxRuntimeException if sampler creation fails.
     */
    public static long createSampler(VkDevice rawDevice, Texture.TextureFilter minFilter, Texture.TextureFilter magFilter,
                                     Texture.TextureWrap uWrap, Texture.TextureWrap vWrap, boolean anisotropyEnabled) {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(VulkanFormatUtils.getVkFilter(magFilter))
                    .minFilter(VulkanFormatUtils.getVkFilter(minFilter))
                    .addressModeU(VulkanFormatUtils.getVkSamplerAddressMode(uWrap))
                    .addressModeV(VulkanFormatUtils.getVkSamplerAddressMode(vWrap))
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT) // Or CLAMP_TO_EDGE, depending on preference for 3D/Cube
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK) // Or float transparent black
                    .unnormalizedCoordinates(false) // LibGDX uses normalized tex coords
                    .compareEnable(false)
                    .compareOp(VK_COMPARE_OP_ALWAYS)
                    .mipmapMode(VulkanFormatUtils.getVkSamplerMipmapMode(minFilter))
                    .mipLodBias(0.0f)
                    .minLod(0.0f)
                    // If mipmapping is used, maxLod should be set to number of mips or VK_LOD_CLAMP_NONE
                    .maxLod((minFilter.isMipMap()) ? VK_LOD_CLAMP_NONE : 0.0f);


            // Anisotropy (check if physical device supports it first)
            if (anisotropyEnabled) { // Assumes anisotropyEnabled means supported & desired
                // Query maxAnisotropy from physical device properties
                // VkPhysicalDeviceProperties deviceProperties = ... get from VulkanDevice ...
                // float maxAnis = deviceProperties.limits().maxSamplerAnisotropy();
                // samplerInfo.anisotropyEnable(true).maxAnisotropy(maxAnis);
                // For now, let's assume a common value if enabled, or disable if not checked:
                samplerInfo.anisotropyEnable(true).maxAnisotropy(16.0f); // Example, query actual max from device
            } else {
                samplerInfo.anisotropyEnable(false).maxAnisotropy(1.0f);
            }


            LongBuffer pSampler = stack.mallocLong(1);
            vkCheck(vkCreateSampler(rawDevice, samplerInfo, null, pSampler), "Failed to create texture sampler");
            return pSampler.get(0);
        }
    }
}