package com.badlogic.gdx.backend.vulkan;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;

import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.system.MemoryStack.*;
// Import necessary constants for descriptor indexing (core in 1.2)
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT;
// import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT; // If needed later
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO;
// Optional: If pool needs update_after_bind flag
// import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException; // Use GdxRuntimeException

public class VulkanDescriptorManager implements Disposable { // Implement Disposable

    private static final String TAG = "VulkanDescriptorManager";
    private static final boolean DEBUG = true; // Renamed from debug

    // Default pool sizes (can be configured)
    private static final int MAX_SETS_PER_POOL = 1000;
    private static final int MAX_UBOS_PER_POOL = 1000;
    private static final int MAX_SAMPLERS_PER_POOL = 4000; // Increased sampler count for texture arrays

    private final VkDevice device;
    private final VkPhysicalDeviceLimits limits; // Store device limits (can be useful even if not checked everywhere)
    private final Map<String, Long> layoutCache = new HashMap<>();

    private long descriptorPool;
    private final List<List<Long>> setsToFree; // List of lists for deferred freeing per frame
    private final int maxFramesInFlight;

    // Define keys for common layouts (optional, can use generated keys)
    // public static final String LAYOUT_KEY_SINGLE_SAMPLER = "SingleSampler0"; // Keep if needed elsewhere

    /**
     * Constructor for VulkanDescriptorManager.
     * @param device The Vulkan logical device handle.
     * @param physicalDeviceLimits The limits of the physical device.
     * @param maxFramesInFlight Number of frames for synchronization (determines size of deferred free queue).
     */
    public VulkanDescriptorManager(VkDevice device, VkPhysicalDeviceLimits physicalDeviceLimits, int maxFramesInFlight) {
        this.device = Objects.requireNonNull(device, "VkDevice cannot be null");
        this.limits = Objects.requireNonNull(physicalDeviceLimits, "VkPhysicalDeviceLimits cannot be null");
        this.maxFramesInFlight = maxFramesInFlight;
        if (maxFramesInFlight <= 0) {
            throw new IllegalArgumentException("maxFramesInFlight must be positive.");
        }

        createPool(false); // Create initial pool (false = no UPDATE_AFTER_BIND flag initially)

        this.setsToFree = new ArrayList<>(maxFramesInFlight);
        for (int i = 0; i < maxFramesInFlight; i++) {
            setsToFree.add(new ArrayList<>());
        }
        if (DEBUG) Gdx.app.log(TAG, "Initialized with maxFramesInFlight=" + maxFramesInFlight);
    }

    /**
     * Creates or retrieves a cached descriptor set layout suitable for a UBO at binding 0
     * and a bindless-like texture array at binding 1.
     * Assumes Vulkan 1.2+ features are available based on flags.
     *
     * @param textureCount Max number of textures in the array (binding 1).
     * @param allowPartiallyBound If true, VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT is added to binding 1.
     * @param allowUpdateAfterBind If true, VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT is added to binding 1.
     * NOTE: If true, the descriptor pool must also be created with VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT.
     * @return The handle to the VkDescriptorSetLayout.
     */
    public long getOrCreateBindlessLikeTextureArrayLayout(int textureCount, boolean allowPartiallyBound, boolean allowUpdateAfterBind) {
        // Generate a unique key based on parameters
        String key = String.format("UBO0_TexArray1_Count%d_Partial%b_Update%b",
                textureCount, allowPartiallyBound, allowUpdateAfterBind);

        return layoutCache.computeIfAbsent(key, k -> {
            if (DEBUG) Gdx.app.log(TAG, "Creating Descriptor Set Layout: " + k);
            try (MemoryStack stack = stackPush()) {
                VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);

                // Binding 0: Uniform Buffer (Vertex Shader)
                VkDescriptorSetLayoutBinding uboBinding = bindings.get(0);
                uboBinding.binding(0);
                uboBinding.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                uboBinding.descriptorCount(1);
                uboBinding.stageFlags(VK_SHADER_STAGE_VERTEX_BIT); // Common for projection matrix
                uboBinding.pImmutableSamplers(null);

                // Binding 1: Combined Image Sampler Array (Fragment Shader)
                VkDescriptorSetLayoutBinding samplerBinding = bindings.get(1);
                samplerBinding.binding(1);
                samplerBinding.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                samplerBinding.descriptorCount(textureCount); // Max size of the array
                samplerBinding.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT); // Common for textures
                samplerBinding.pImmutableSamplers(null);

                // --- Flags for Descriptor Indexing (Binding 1 only) ---
                IntBuffer bindingFlags = stack.mallocInt(2); // One flag entry per binding
                bindingFlags.put(0, 0); // Binding 0 (UBO) has no special flags

                int samplerFlags = 0;
                if (allowPartiallyBound) {
                    samplerFlags |= VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT;
                }
                if (allowUpdateAfterBind) {
                    samplerFlags |= VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT;
                    // TODO: Ensure the descriptor pool supports UPDATE_AFTER_BIND if this flag is used.
                    // Might require recreating the pool if it wasn't created with the flag initially.
                    // Consider adding a check or ensuring the pool is created correctly based on usage.
                }
                bindingFlags.put(1, samplerFlags); // Set flags for Binding 1 (Sampler Array)
                bindingFlags.flip();

                VkDescriptorSetLayoutBindingFlagsCreateInfo flagsInfo = VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack);
                flagsInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO);
                flagsInfo.pBindingFlags(bindingFlags);
                // --- End Flags ---

                VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
                layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
                layoutInfo.pBindings(bindings);
                layoutInfo.pNext(flagsInfo.address()); // Chain the flags struct

                // Add layout flag if updateAfterBind is used for any binding
                if (allowUpdateAfterBind) {
                    layoutInfo.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT);
                }

                LongBuffer pLayout = stack.mallocLong(1);
                vkCheck(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout),
                        "Failed to create bindless-like texture array descriptor set layout");

                long layoutHandle = pLayout.get(0);
                if (DEBUG) Gdx.app.log(TAG, "Created layout handle: " + layoutHandle + " for key: " + k);
                return layoutHandle;
            }
        });
    }


    // Example: Keep the old single sampler layout creation if needed elsewhere
    public long getOrCreateSingleSamplerLayout() {
        final String LAYOUT_KEY_SINGLE_SAMPLER = "SingleSampler0";
        return layoutCache.computeIfAbsent(LAYOUT_KEY_SINGLE_SAMPLER, k -> createSingleSamplerLayout());
    }

    private long createSingleSamplerLayout() {
        if (DEBUG) Gdx.app.log(TAG, "Creating Single Sampler Descriptor Set Layout...");
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);

            // Binding 0: Combined Image Sampler (Fragment Shader)
            VkDescriptorSetLayoutBinding samplerBinding = bindings.get(0);
            samplerBinding.binding(0);
            samplerBinding.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            samplerBinding.descriptorCount(1);
            samplerBinding.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            samplerBinding.pImmutableSamplers(null);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            vkCheck(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout),
                    "Failed to create Single Sampler descriptor set layout");

            long layoutHandle = pLayout.get(0);
            if (DEBUG) Gdx.app.log(TAG, "Created Single Sampler Layout: " + layoutHandle);
            return layoutHandle;
        }
    }

    /**
     * Creates the descriptor pool.
     * @param supportUpdateAfterBind If true, adds the VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT flag.
     */
    private void createPool(boolean supportUpdateAfterBind) {
        if (DEBUG) Gdx.app.log(TAG, "Creating descriptor pool (supportUpdateAfterBind=" + supportUpdateAfterBind + ")");
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);

            // Size for Uniform Buffers
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(MAX_UBOS_PER_POOL);
            // Size for Combined Image Samplers
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_SAMPLERS_PER_POOL);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(MAX_SETS_PER_POOL); // Max total sets from this pool
            int poolFlags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT; // Allows freeing individual sets
            if (supportUpdateAfterBind) {
                poolFlags |= VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT;
            }
            poolInfo.flags(poolFlags);

            LongBuffer pDescriptorPool = stack.mallocLong(1);
            vkCheck(vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool),
                    "Failed to create descriptor pool");
            this.descriptorPool = pDescriptorPool.get(0);
            if (DEBUG) Gdx.app.log(TAG, "Descriptor pool created: " + this.descriptorPool);
        }
    }

    /**
     * Allocates a descriptor set from the pool using the specified layout.
     * NOTE: If the pool runs out, this will throw a RuntimeException. Consider adding pool recreation logic if needed.
     * @param layoutHandle The handle of the VkDescriptorSetLayout to use.
     * @return The handle of the allocated VkDescriptorSet.
     */
    public long allocateSet(long layoutHandle) {
        if (layoutHandle == VK_NULL_HANDLE) {
            throw new IllegalArgumentException("Cannot allocate descriptor set with VK_NULL_HANDLE layout.");
        }
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(descriptorPool);
            allocInfo.pSetLayouts(stack.longs(layoutHandle));

            LongBuffer pDescriptorSet = stack.mallocLong(1);
            int result = vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);

            if (result == VK_ERROR_OUT_OF_POOL_MEMORY || result == VK_ERROR_FRAGMENTED_POOL) {
                // TODO: Handle pool exhaustion - recreate pool, reallocate sets?
                Gdx.app.error(TAG, "Descriptor pool exhausted or fragmented! Need to implement pool recreation. Result: " + VkResult.translate(result));
                throw new GdxRuntimeException("Failed to allocate descriptor set: Pool exhausted. Result: " + VkResult.translate(result));
            } else if (result != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to allocate descriptor set: " + VkResult.translate(result));
            }

            long setHandle = pDescriptorSet.get(0);
            if (DEBUG) Gdx.app.log(TAG, "Allocated Descriptor Set: " + setHandle + " with Layout: " + layoutHandle);
            return setHandle;
        }
    }

    /**
     * Static helper to update a Combined Image Sampler descriptor at a specific array element.
     *
     * @param device        The Vulkan logical device.
     * @param set           The descriptor set handle.
     * @param binding       The binding number within the set.
     * @param arrayElement  The index within the descriptor array at the specified binding.
     * @param imageViewHandle The handle to the VkImageView.
     * @param samplerHandle The handle to the VkSampler.
     */
    public static void updateCombinedImageSampler(VkDevice device, long set, int binding, int arrayElement, long imageViewHandle, long samplerHandle) {
        // Allow binding null handles if partiallyBound is used, but log a warning.
        // The shader must handle sampling invalid indices if partiallyBound is used.
        if (imageViewHandle == VK_NULL_HANDLE || samplerHandle == VK_NULL_HANDLE) {
            if (DEBUG)
                Gdx.app.log(TAG, "  --> Updating binding " + binding + "[" + arrayElement + "] with NULL handles (ImageView: " + imageViewHandle + ", Sampler: " + samplerHandle + "). Requires PARTIALLY_BOUND.");
            // If partiallyBound is NOT used, this would likely cause validation errors or crashes.
            // For now, proceed assuming partiallyBound allows this.
        }

        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL); // Common layout for sampling
            imageInfo.imageView(imageViewHandle); // Can be NULL if partially bound
            imageInfo.sampler(samplerHandle);     // Can be NULL if partially bound

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
            descriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            descriptorWrite.dstSet(set);
            descriptorWrite.dstBinding(binding);
            descriptorWrite.dstArrayElement(arrayElement); // Use the provided array element index
            descriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            descriptorWrite.descriptorCount(1); // We are updating one element at a time here
            descriptorWrite.pImageInfo(imageInfo);
            // descriptorWrite.pBufferInfo(null); // Not used for image samplers
            // descriptorWrite.pTexelBufferView(null); // Not used for image samplers

            vkUpdateDescriptorSets(device, descriptorWrite, null); // Submit the update
        }
    }

    // Overload for convenience when updating a single texture (element 0)
    public static void updateCombinedImageSampler(VkDevice device, long set, int binding, VulkanTexture texture) {
        //long viewHandle = (texture != null) ? texture.getImageViewHandle() : VK_NULL_HANDLE;
        //long samplerHandle = (texture != null) ? texture.getSamplerHandle() : VK_NULL_HANDLE;
        //updateCombinedImageSampler(device, set, binding, 0, viewHandle, samplerHandle);
    }

    /**
     * Static helper to update a Uniform Buffer descriptor using a VulkanUniformBuffer object.
     * @param device The Vulkan logical device.
     * @param set The descriptor set handle.
     * @param binding The binding number within the set.
     * @param ubo The VulkanUniformBuffer object containing buffer handle, offset, and range.
     */
    public static void updateUniformBuffer(VkDevice device, long set, int binding, VulkanUniformBuffer ubo) {
        if (ubo == null) {
            Gdx.app.error(TAG, "Attempting to update UBO binding " + binding + " with null VulkanUniformBuffer object.");
            return;
        }
        updateUniformBuffer(device, set, binding, ubo.getBufferHandle(), ubo.getOffset(), ubo.getRange());
    }

    /**
     * Static helper to update a Uniform Buffer descriptor with explicit buffer details.
     * @param device The Vulkan logical device.
     * @param set The descriptor set handle.
     * @param binding The binding number within the set.
     * @param bufferHandle The handle to the VkBuffer.
     * @param offset The offset within the buffer.
     * @param range The size of the buffer region to bind.
     */
    public static void updateUniformBuffer(VkDevice device, long set, int binding, long bufferHandle, long offset, long range) {
        if (bufferHandle == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Attempting to update UBO binding " + binding + " with null buffer handle.");
            return; // Avoid crash
        }
        if (range <= 0) {
            Gdx.app.error(TAG, "Attempting to update UBO binding " + binding + " with zero or negative range (" + range + ").");
            // This is likely an error, proceed cautiously or return. For now, proceed.
        }

        //if (DEBUG) Gdx.app.log(TAG, "updateUniformBuffer: Set=" + set + ", Binding=" + binding + ", Buf=" + bufferHandle + ", Offset=" + offset + ", Range=" + range);

        try (MemoryStack stack = stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.buffer(bufferHandle);
            bufferInfo.offset(offset);
            bufferInfo.range(range);

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
            descriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            descriptorWrite.dstSet(set);
            descriptorWrite.dstBinding(binding); // Use the provided binding
            descriptorWrite.dstArrayElement(0); // UBOs are typically not arrays here
            descriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            descriptorWrite.descriptorCount(1);
            descriptorWrite.pBufferInfo(bufferInfo);
            // descriptorWrite.pImageInfo(null); // Not used for buffers
            // descriptorWrite.pTexelBufferView(null); // Not used for buffers

            vkUpdateDescriptorSets(device, descriptorWrite, null);
        }
    }

    /**
     * Queues descriptor sets for deferred freeing. They will be actually freed
     * when cleanupCompletedFrameSets is called for the corresponding frame index.
     * Requires the pool to be created with VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT.
     * @param setHandles A list of descriptor set handles to queue for freeing.
     */
    public void freeSets(List<Long> setHandles) {
        if (setHandles == null || setHandles.isEmpty() || this.descriptorPool == VK_NULL_HANDLE) {
            return;
        }
        // Get frame index from VulkanGraphics - ensure Gdx.graphics is valid
        VulkanGraphics gfx = null;
        if (Gdx.graphics instanceof VulkanGraphics) {
            gfx = (VulkanGraphics) Gdx.graphics;
        }
        if (gfx == null) {
            Gdx.app.error(TAG, "Cannot queue sets for freeing: VulkanGraphics not available.");
            return;
        }

        int currentFrameIndex = gfx.getCurrentFrameIndex();
        if (currentFrameIndex < 0 || currentFrameIndex >= setsToFree.size()) {
            Gdx.app.error(TAG, "Cannot queue sets for freeing: Invalid currentFrameIndex " + currentFrameIndex);
            return;
        }

        List<Long> frameQueue = setsToFree.get(currentFrameIndex);
        synchronized (frameQueue) {
            frameQueue.addAll(setHandles);
        }
        if (DEBUG) Gdx.app.log(TAG, "Queued " + setHandles.size() + " sets for freeing (associated with frame " + currentFrameIndex + ")");
    }

    /**
     * Actually frees descriptor sets that were queued for the completed frame index.
     * Called typically after waiting for the frame's fence.
     * @param frameIndex The index of the frame that has just completed.
     */
    public void cleanupCompletedFrameSets(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= setsToFree.size()) {
            Gdx.app.error(TAG, "Cannot cleanup sets: Invalid frameIndex " + frameIndex);
            return;
        }

        List<Long> handlesToActuallyFree;
        List<Long> frameQueue = setsToFree.get(frameIndex);

        synchronized (frameQueue) {
            if (frameQueue.isEmpty()) {
                return; // Nothing to free for this frame
            }
            // Copy to avoid holding lock during vkFreeDescriptorSets
            handlesToActuallyFree = new ArrayList<>(frameQueue);
            frameQueue.clear();
        }

        if (!handlesToActuallyFree.isEmpty() && this.descriptorPool != VK_NULL_HANDLE) {
            if (DEBUG) Gdx.app.log(TAG, "cleanupCompletedFrameSets: Actually freeing " + handlesToActuallyFree.size() + " sets from completed frame " + frameIndex);
            try (MemoryStack stack = stackPush()) {
                LongBuffer pSets = stack.mallocLong(handlesToActuallyFree.size());
                for (int i = 0; i < handlesToActuallyFree.size(); i++) {
                    pSets.put(i, handlesToActuallyFree.get(i));
                }

                // Requires VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT on pool
                int result = vkFreeDescriptorSets(device, descriptorPool, pSets);
                if (result != VK_SUCCESS) {
                    Gdx.app.error(TAG, "vkFreeDescriptorSets failed for frame " + frameIndex + "! Result: " + VkResult.translate(result));
                    // Consider how to handle this - maybe try again later? Pool might be invalid.
                } else {
                    if (DEBUG) Gdx.app.log(TAG, "Successfully freed " + handlesToActuallyFree.size() + " descriptor sets from completed frame " + frameIndex);
                }
            } catch (Exception e) {
                Gdx.app.error(TAG, "Exception during deferred vkFreeDescriptorSets for frame " + frameIndex, e);
            }
        }
    }

    /**
     * Disposes the descriptor pool and clears internal caches.
     */
    @Override
    public void dispose() {
        if (DEBUG) Gdx.app.log(TAG, "Disposing VulkanDescriptorManager...");

        // Layouts are owned by the manager and should be destroyed
        if (DEBUG) Gdx.app.log(TAG, "Cleaning up cached descriptor set layouts (" + layoutCache.size() + ")...");
        for (long layoutHandle : layoutCache.values()) {
            if (layoutHandle != VK_NULL_HANDLE) {
                if (DEBUG) Gdx.app.log(TAG, "  Destroying layout: " + layoutHandle);
                vkDestroyDescriptorSetLayout(device, layoutHandle, null);
            }
        }
        layoutCache.clear();
        if (DEBUG) Gdx.app.log(TAG, "Descriptor set layout cache cleared.");

        // Destroy the pool (implicitly frees all sets allocated from it)
        if (descriptorPool != VK_NULL_HANDLE) {
            if (DEBUG) Gdx.app.log(TAG, "Destroying descriptor pool: " + descriptorPool);
            vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = VK_NULL_HANDLE;
        }

        // Clear the deferred free queues
        if (setsToFree != null) {
            for (List<Long> queue : setsToFree) {
                queue.clear();
            }
            setsToFree.clear();
        }

        if (DEBUG) Gdx.app.log(TAG, "VulkanDescriptorManager disposed.");
    }


    /**
     * Returns the raw Vulkan logical device handle.
     */
    public VkDevice getDevice() {
        return device;
    }
}
