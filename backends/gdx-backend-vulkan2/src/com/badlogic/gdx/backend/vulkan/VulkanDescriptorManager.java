package com.badlogic.gdx.backend.vulkan;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;

import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO;

import com.badlogic.gdx.Gdx;

public class VulkanDescriptorManager {

    private static final String TAG = "VulkanDescriptorManager";
    private static final int MAX_SETS_PER_POOL = 1000;
    private static final int MAX_UBOS_PER_POOL = 1000;
    private static final int MAX_SAMPLERS_PER_POOL = 1000;

    private final VkDevice device;
    private long descriptorPool;
    private final List<List<Long>> setsToFree;

    // Cache layouts to avoid recreating identical ones
    private final Map<String, Long> layoutCache = new HashMap<>();

    // Define keys for common layouts
    public static final String LAYOUT_KEY_SPRITEBATCH = "SpriteBatch_UBO0_Sampler1";
    public static final String LAYOUT_KEY_SINGLE_SAMPLER = "SingleSampler0"; // Keep if needed elsewhere

    private static final int MAX_SPRITEBATCH_TEXTURES = VulkanSpriteBatch.MAX_BATCH_TEXTURES;

    public VulkanDescriptorManager(VkDevice device) {
        this.device = Objects.requireNonNull(device);
        createPool();

        VulkanApplication app = (VulkanApplication) Gdx.app;

        int capacity = app.getAppConfig().MAX_FRAMES_IN_FLIGHT;
        this.setsToFree = new ArrayList<>(capacity);

        for (int i = 0; i < capacity; i++) {
            setsToFree.add(new ArrayList<>());
        }
    }

    public long getOrCreateSpriteBatchLayout() {
        return layoutCache.computeIfAbsent(LAYOUT_KEY_SPRITEBATCH, k -> createSpriteBatchLayout());
    }

    // Example: Keep the old single sampler layout if needed elsewhere
    public long getOrCreateSingleSamplerLayout() {
        return layoutCache.computeIfAbsent(LAYOUT_KEY_SINGLE_SAMPLER, k -> createSingleSamplerLayout());
    }

    private long createSpriteBatchLayout() {
        Gdx.app.log(TAG, "Creating SpriteBatch Descriptor Set Layout (with Indexing for " + MAX_SPRITEBATCH_TEXTURES + " textures)...");
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);

            // Binding 0: Uniform Buffer (Vertex Shader) - NO CHANGE
            VkDescriptorSetLayoutBinding uboBinding = bindings.get(0);
            uboBinding.binding(0);
            uboBinding.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            uboBinding.descriptorCount(1);
            uboBinding.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            uboBinding.pImmutableSamplers(null);

            // --- Binding 1: Combined Image Sampler Array (Fragment Shader) --- MODIFIED ---
            VkDescriptorSetLayoutBinding samplerBinding = bindings.get(1);
            samplerBinding.binding(1);
            samplerBinding.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            samplerBinding.descriptorCount(MAX_SPRITEBATCH_TEXTURES); // Use array size
            samplerBinding.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            samplerBinding.pImmutableSamplers(null);

            // --- Flags for Descriptor Indexing (Specifically for Binding 1) --- NEW ---
            IntBuffer bindingFlags = stack.mallocInt(2); // One flag entry per binding
            bindingFlags.put(0, 0); // Binding 0 (UBO) has no special flags
            // Set flags for Binding 1 (Sampler Array)
            bindingFlags.put(1, VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT // Allows unused slots
                    // | VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT // Optional: If you want to update after binding (adds complexity)
                    // | VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT // Optional: If the last binding's count can vary (not needed here)
            );
            bindingFlags.flip();

            VkDescriptorSetLayoutBindingFlagsCreateInfo flagsInfo = VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack);
            flagsInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO);
            flagsInfo.pBindingFlags(bindingFlags);
            // --- End New Flags ---

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.pBindings(bindings);
            layoutInfo.pNext(flagsInfo.address()); // Chain the flags struct
            // Optional: If using UpdateAfterBind, might need VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT in layoutInfo.flags()

            LongBuffer pLayout = stack.mallocLong(1);
            vkCheck(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout), "Failed to create SpriteBatch descriptor set layout (with Indexing)");

            long layoutHandle = pLayout.get(0);
            Gdx.app.log(TAG, "Created Indexed SpriteBatch Descriptor Set Layout: " + layoutHandle);
            return layoutHandle;
        }
    }

    /*private long createSpriteBatchLayout() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);

            // Binding 0: Uniform Buffer (Vertex Shader)
            VkDescriptorSetLayoutBinding uboBinding = bindings.get(0);
            uboBinding.binding(0);
            uboBinding.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            uboBinding.descriptorCount(1);
            uboBinding.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            uboBinding.pImmutableSamplers(null); // Optional

            // Binding 1: Combined Image Sampler (Fragment Shader)
            VkDescriptorSetLayoutBinding samplerBinding = bindings.get(1);
            samplerBinding.binding(1);
            samplerBinding.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            samplerBinding.descriptorCount(1);
            samplerBinding.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            samplerBinding.pImmutableSamplers(null); // Optional

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            int result = vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create SpriteBatch descriptor set layout: " + result);
            }
            System.out.println("Created SpriteBatch Descriptor Set Layout: " + pLayout.get(0));
            return pLayout.get(0);
        }
    }*/

    private long createSingleSamplerLayout() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);

            // Binding 0: Combined Image Sampler (Fragment Shader)
            VkDescriptorSetLayoutBinding samplerBinding = bindings.get(0);
            samplerBinding.binding(0); // Original binding was 0
            samplerBinding.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            samplerBinding.descriptorCount(1);
            samplerBinding.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            samplerBinding.pImmutableSamplers(null);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            int result = vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Single Sampler descriptor set layout: " + result);
            }
            System.out.println("Created Single Sampler Descriptor Set Layout: " + pLayout.get(0));
            return pLayout.get(0);
        }
    }

    private void createPool() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);

            // Size for Uniform Buffers
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            poolSizes.get(0).descriptorCount(MAX_UBOS_PER_POOL);

            // Size for Combined Image Samplers
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            poolSizes.get(1).descriptorCount(MAX_SAMPLERS_PER_POOL);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(MAX_SETS_PER_POOL); // Max total sets from this pool
            poolInfo.flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT); // Optional: Allows freeing individual sets

            LongBuffer pDescriptorPool = stack.mallocLong(1);
            int result = vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create descriptor pool: " + result);
            }
            this.descriptorPool = pDescriptorPool.get(0);
        }
    }

    public long allocateSet(long layoutHandle) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(descriptorPool);
            allocInfo.pSetLayouts(stack.longs(layoutHandle)); // Pass the specific layout handle

            LongBuffer pDescriptorSet = stack.mallocLong(1);
            int result = vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);
            if (result != VK_SUCCESS) {
                // Consider specific error handling, maybe pool is full?
                throw new RuntimeException("Failed to allocate descriptor set: " + result);
            }
            System.out.println("Allocated Descriptor Set: " + pDescriptorSet.get(0) + " with Layout: " + layoutHandle);
            return pDescriptorSet.get(0);
        }
    }

    /**
     * Static helper to update a Combined Image Sampler descriptor at a specific array element.
     *
     * @param device        The Vulkan logical device.
     * @param set           The descriptor set handle.
     * @param binding       The binding number within the set.
     * @param arrayElement  The index within the descriptor array at the specified binding.
     * @param texture       The texture to bind.
     */
    public static void updateCombinedImageSampler(VkDevice device, long set, int binding, int arrayElement, VulkanTexture texture) {
        long imageViewHandle = (texture != null) ? texture.getImageViewHandle() : VK_NULL_HANDLE;
        long samplerHandle = (texture != null) ? texture.getSamplerHandle() : VK_NULL_HANDLE;

        // Log the attempt with all relevant details including arrayElement
    /* Gdx.app.log(TAG, "updateCombinedImageSampler: Called for Set=" + set + " (0x" + Long.toHexString(set) + "), Binding=" + binding
            + ", ArrayElement=" + arrayElement // NEW log detail
            + ", Texture Hash=" + (texture != null ? texture.hashCode() : "NULL")
            + ", ImageView=" + imageViewHandle + " (0x" + Long.toHexString(imageViewHandle) + ")"
            + ", Sampler=" + samplerHandle + " (0x" + Long.toHexString(samplerHandle) + ")"); */

        // Check for invalid handles BEFORE proceeding to Vulkan calls
        if (imageViewHandle == VK_NULL_HANDLE || samplerHandle == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "  --> ABORTING UPDATE: Invalid texture, ImageView, or Sampler handle provided for array element " + arrayElement + "!");
            // You might want default texture here, but null descriptor is safer with PARTIALLY_BOUND
            // For now, just skip the update for this element.
            return;
        }

        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            imageInfo.imageView(imageViewHandle);
            imageInfo.sampler(samplerHandle);

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
            descriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            descriptorWrite.dstSet(set);
            descriptorWrite.dstBinding(binding);
            descriptorWrite.dstArrayElement(arrayElement); // Use the provided array element index
            descriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            descriptorWrite.descriptorCount(1); // We are updating one element at a time here
            descriptorWrite.pImageInfo(imageInfo);
            descriptorWrite.pBufferInfo(null);
            descriptorWrite.pTexelBufferView(null);

            // ... (logging before/after vkUpdateDescriptorSets) ...
            vkUpdateDescriptorSets(device, descriptorWrite, null);
        }
    }

    public static void updateCombinedImageSampler(VkDevice device, long set, int binding, VulkanTexture texture) {
        updateCombinedImageSampler(device, set, binding, 0, texture); // Defaults to array element 0
    }

    /**
     * Static helper to update a Combined Image Sampler descriptor.
     */
    /*public static void updateCombinedImageSampler(VkDevice device, long set, int binding, VulkanTexture texture) {
        long imageViewHandle = (texture != null) ? texture.getImageViewHandle() : VK_NULL_HANDLE;
        long samplerHandle = (texture != null) ? texture.getSamplerHandle() : VK_NULL_HANDLE;

        // Log the attempt with all relevant details
        *//*Gdx.app.log(TAG, "updateCombinedImageSampler: Called for Set=" + set + " (0x" + Long.toHexString(set) + "), Binding=" + binding
                + ", Texture Hash=" + (texture != null ? texture.hashCode() : "NULL")
                + ", ImageView=" + imageViewHandle + " (0x" + Long.toHexString(imageViewHandle) + ")" // Log ImageView handle
                + ", Sampler=" + samplerHandle + " (0x" + Long.toHexString(samplerHandle) + ")");     // Log Sampler handle*//*

        // Check for invalid handles BEFORE proceeding to Vulkan calls
        if (imageViewHandle == VK_NULL_HANDLE || samplerHandle == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "  --> ABORTING UPDATE: Invalid texture, ImageView, or Sampler handle provided!");
            return; // Avoid trying to update with invalid handles
        }

        if (texture.getImageViewHandle() == VK_NULL_HANDLE || texture.getSamplerHandle() == VK_NULL_HANDLE) {
            System.err.println("WARN: Attempting to update sampler binding " + binding + " with invalid texture.");
            return; // Avoid crash, but log this!
        }

        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            imageInfo.imageView(texture.getImageViewHandle()); // Method from your VulkanTexture class
            imageInfo.sampler(texture.getSamplerHandle());     // Method from your VulkanTexture class

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
            descriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            descriptorWrite.dstSet(set);
            descriptorWrite.dstBinding(binding); // Use the provided binding
            descriptorWrite.dstArrayElement(0);
            descriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            descriptorWrite.descriptorCount(1);
            descriptorWrite.pImageInfo(imageInfo);
            descriptorWrite.pBufferInfo(null);
            descriptorWrite.pTexelBufferView(null);

            //Gdx.app.log(TAG, "  --> Calling vkUpdateDescriptorSets for Set=" + set + ", Binding=" + binding);
            vkUpdateDescriptorSets(device, descriptorWrite, null);
            //Gdx.app.log(TAG, "  <-- Returned from vkUpdateDescriptorSets for Set=" + set);
        }
    }*/

    /**
     * Static helper to update a Uniform Buffer descriptor. (NEW)
     */
    public static void updateUniformBuffer(VkDevice device, long set, int binding, VulkanUniformBuffer ubo) {
        if (ubo == null || ubo.getBufferHandle() == VK_NULL_HANDLE) {
            System.err.println("WARN: Attempting to update UBO binding " + binding + " with invalid buffer.");
            return; // Avoid crash
        }
        updateUniformBuffer(device, set, binding, ubo.getBufferHandle(), ubo.getOffset(), ubo.getRange());
    }

    /**
     * Static helper to update a Uniform Buffer descriptor with explicit details. (NEW)
     */
    public static void updateUniformBuffer(VkDevice device, long set, int binding, long bufferHandle, long offset, long range) {
        if (bufferHandle == VK_NULL_HANDLE) {
            System.err.println("WARN: Attempting to update UBO binding " + binding + " with null buffer handle.");
            return; // Avoid crash
        }
        if (range <= 0) {
            System.err.println("WARN: Attempting to update UBO binding " + binding + " with zero or negative range (" + range + ").");
        }

        Gdx.app.log(TAG, "updateUniformBuffer: Updating Set " + set + " (0x" + Long.toHexString(set) + "), Binding " + binding + ", Buf " + bufferHandle + ", Range " + range); // Log details

        try (MemoryStack stack = stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.buffer(bufferHandle);
            bufferInfo.offset(offset);
            bufferInfo.range(range);

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
            descriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            descriptorWrite.dstSet(set);
            descriptorWrite.dstBinding(binding); // Use the provided binding
            descriptorWrite.dstArrayElement(0);
            descriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            descriptorWrite.descriptorCount(1);
            descriptorWrite.pBufferInfo(bufferInfo);
            descriptorWrite.pImageInfo(null);
            descriptorWrite.pTexelBufferView(null);

            //Gdx.app.log(TAG, " --> Calling vkUpdateDescriptorSets for Set " + set);
            vkUpdateDescriptorSets(device, descriptorWrite, null);
            //Gdx.app.log(TAG, " <-- Returned from vkUpdateDescriptorSets for Set " + set);
        }
    }

    public void freeSets(List<Long> setHandles) {
        if (setHandles == null || setHandles.isEmpty() || this.descriptorPool == VK_NULL_HANDLE) {
            return;
        }
        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
        int currentFrameIndex = gfx.getCurrentFrameIndex(); // Get frame index (example)
        List<Long> frameQueue = setsToFree.get(currentFrameIndex); // Use .get()
        synchronized (frameQueue) {
            frameQueue.addAll(setHandles);
        }
        System.out.println("VulkanDescriptorManager: Queued " + setHandles.size() + " sets for freeing (associated with frame " + currentFrameIndex + ")");
    }

    public void cleanupCompletedFrameSets(int frameIndex) {
        //Gdx.app.log(TAG, "cleanupCompletedFrameSets: Cleaning up completed frame " + frameIndex + " sets");
        VulkanApplication app = (VulkanApplication) Gdx.app;
        int queueIndex = frameIndex % app.getAppConfig().MAX_FRAMES_IN_FLIGHT;
        List<Long> handlesToActuallyFree;
        List<Long> frameQueue = setsToFree.get(queueIndex); // Use .get()

        synchronized (frameQueue) {
            if (frameQueue.isEmpty()) {
                return;
            }
            handlesToActuallyFree = new ArrayList<>(frameQueue);
            frameQueue.clear();
        }

        if (!handlesToActuallyFree.isEmpty() && this.descriptorPool != VK_NULL_HANDLE) {
            Gdx.app.log(TAG, "cleanupCompletedFrameSets: Actually freeing " + handlesToActuallyFree.size() + " sets from completed frame " + frameIndex);
            try (MemoryStack stack = stackPush()) {
                LongBuffer pSets = stack.mallocLong(handlesToActuallyFree.size());
                for (int i = 0; i < handlesToActuallyFree.size(); i++) {
                    pSets.put(i, handlesToActuallyFree.get(i));
                }

                // Requires VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT on pool
                int result = vkFreeDescriptorSets(device, descriptorPool, pSets);
                if (result != VK_SUCCESS) {
                    System.err.println("VulkanDescriptorManager WARN: vkFreeDescriptorSets failed for completed frame " + frameIndex + " with result: " + result);
                } else {
                    System.out.println("VulkanDescriptorManager: Actually freed " + handlesToActuallyFree.size() + " descriptor sets from completed frame " + frameIndex);
                }
            } catch (Exception e) {
                System.err.println("VulkanDescriptorManager ERROR: Exception during deferred vkFreeDescriptorSets: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void dispose() {
        Gdx.app.log(TAG, "Disposing descriptor manager..."); // Add log

        Gdx.app.log(TAG, "Cleaning up cached descriptor set layouts (" + layoutCache.size() + ")...");
        for (long layoutHandle : layoutCache.values()) {
            if (layoutHandle != VK_NULL_HANDLE) {
                Gdx.app.log(TAG, "Destroying layout: " + layoutHandle); // Optional detailed log
                vkDestroyDescriptorSetLayout(device, layoutHandle, null); // <<< Use 'device' here
            }
        }
        layoutCache.clear();
        Gdx.app.log(TAG, "Descriptor set layout cache cleared.");


        // Destroy the pool (implicitly frees all sets allocated from it) - This is CORRECT
        if (descriptorPool != VK_NULL_HANDLE) {
            Gdx.app.log(TAG, "Destroying descriptor pool: " + descriptorPool);
            vkDestroyDescriptorPool(device, descriptorPool, null); // <<< Use 'device' here
            descriptorPool = VK_NULL_HANDLE;
        }

        // Clear the tracking lists (whether array or List<List>)
        if (setsToFree != null) { // Add null check
            // Adapt based on your implementation
            setsToFree.clear();
            // setsToFree = null; // Optional: allow GC
        }

        Gdx.app.log(TAG, "Descriptor manager disposed.");
    }
}