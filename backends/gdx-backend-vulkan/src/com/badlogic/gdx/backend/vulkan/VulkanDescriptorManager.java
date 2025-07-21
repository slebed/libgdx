package com.badlogic.gdx.backend.vulkan;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;

import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

public class VulkanDescriptorManager implements Disposable {

    private static final String TAG = "VulkanDescriptorManager";
    private static final boolean DEBUG = true;

    private static final int MAX_SETS_PER_POOL = 1000;
    private static final int MAX_UBOS_PER_POOL = 1000;
    private static final int MAX_SAMPLERS_PER_POOL = 4000;

    private final VkDevice device;
    private final VkPhysicalDeviceLimits limits;
    private final Map<String, Long> layoutCache = new HashMap<>();

    private long descriptorPool;
    private final List<List<Long>> setsToFree;
    private final int maxFramesInFlight;

    /**
     * Corrected constructor to match the instantiation in VulkanApplication.
     * @param device The Vulkan logical device handle.
     * @param physicalDeviceLimits The limits of the physical device.
     * @param maxFramesInFlight Number of frames for synchronization.
     */
    public VulkanDescriptorManager(VkDevice device, VkPhysicalDeviceLimits physicalDeviceLimits, int maxFramesInFlight) {
        this.device = Objects.requireNonNull(device, "VkDevice cannot be null");
        this.limits = Objects.requireNonNull(physicalDeviceLimits, "VkPhysicalDeviceLimits cannot be null");
        this.maxFramesInFlight = maxFramesInFlight;
        if (maxFramesInFlight <= 0) {
            throw new IllegalArgumentException("maxFramesInFlight must be positive.");
        }

        createPool(false);

        this.setsToFree = new ArrayList<>(maxFramesInFlight);
        for (int i = 0; i < maxFramesInFlight; i++) {
            setsToFree.add(new ArrayList<>());
        }
        if (DEBUG) Gdx.app.log(TAG, "Initialized with maxFramesInFlight=" + maxFramesInFlight);
    }

    public long getOrCreateLayout(VulkanShaderPipelineBundle.Config.BindingConfigPojo[] pojoBindings, boolean allowUpdateAfterBind) {
        // Generate a unique key from the binding configuration
        StringBuilder keyBuilder = new StringBuilder();
        if (pojoBindings != null) {
            for (VulkanShaderPipelineBundle.Config.BindingConfigPojo b : pojoBindings) {
                keyBuilder.append(b.binding).append(":").append(b.descriptorType).append(":").append(b.descriptorCount).append(":").append(b.stageFlags).append(";");
            }
        }
        keyBuilder.append("updateAfterBind=").append(allowUpdateAfterBind);
        String key = keyBuilder.toString();

        return layoutCache.computeIfAbsent(key, k -> {
            if (DEBUG) Gdx.app.log(TAG, "Creating Descriptor Set Layout for key: " + k);
            return createDescriptorSetLayoutInternal(pojoBindings, allowUpdateAfterBind);
        });
    }

    private long createDescriptorSetLayoutInternal(VulkanShaderPipelineBundle.Config.BindingConfigPojo[] pojoBindings, boolean allowUpdateAfterBind) {
        try (MemoryStack stack = stackPush()) {
            if (pojoBindings == null || pojoBindings.length == 0) {
                return VK_NULL_HANDLE;
            }

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(pojoBindings.length, stack);
            for (int i = 0; i < pojoBindings.length; i++) {
                VulkanShaderPipelineBundle.Config.BindingConfigPojo pojo = pojoBindings[i];
                bindings.get(i)
                        .binding(pojo.binding)
                        .descriptorType(pojo.descriptorType)
                        .descriptorCount(pojo.descriptorCount)
                        .stageFlags(pojo.stageFlags)
                        .pImmutableSamplers(null);
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);

            if (allowUpdateAfterBind) {
                layoutInfo.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT);
            }

            LongBuffer pLayout = stack.mallocLong(1);
            vkCheck(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout), "Failed to create descriptor set layout");
            return pLayout.get(0);
        }
    }


    private void createPool(boolean supportUpdateAfterBind) {
        if (DEBUG) Gdx.app.log(TAG, "Creating descriptor pool (supportUpdateAfterBind=" + supportUpdateAfterBind + ")");
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(MAX_UBOS_PER_POOL);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_SAMPLERS_PER_POOL);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .pPoolSizes(poolSizes)
                    .maxSets(MAX_SETS_PER_POOL);

            int poolFlags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
            if (supportUpdateAfterBind) {
                poolFlags |= VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT;
            }
            poolInfo.flags(poolFlags);

            LongBuffer pDescriptorPool = stack.mallocLong(1);
            vkCheck(vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool), "Failed to create descriptor pool");
            this.descriptorPool = pDescriptorPool.get(0);
        }
    }

    public long allocateSet(long layoutHandle) {
        if (layoutHandle == VK_NULL_HANDLE) {
            throw new IllegalArgumentException("Cannot allocate descriptor set with VK_NULL_HANDLE layout.");
        }
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(layoutHandle));

            LongBuffer pDescriptorSet = stack.mallocLong(1);
            int result = vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);

            if (result != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to allocate descriptor set: " + VkResult.translate(result));
            }
            return pDescriptorSet.get(0);
        }
    }

    public static void updateCombinedImageSampler(VkDevice device, long set, int binding, VulkanTexture texture) {
        long viewHandle = (texture != null) ? texture.getImageViewHandle() : VK_NULL_HANDLE;
        long samplerHandle = (texture != null) ? texture.getSamplerHandle() : VK_NULL_HANDLE;
        updateCombinedImageSampler(device, set, binding, 0, viewHandle, samplerHandle);
    }

    public static void updateCombinedImageSampler(VkDevice device, long set, int binding, int arrayElement, long imageViewHandle, long samplerHandle) {
        if (imageViewHandle == VK_NULL_HANDLE || samplerHandle == VK_NULL_HANDLE) {
            if (DEBUG) Gdx.app.log(TAG, "Updating binding " + binding + "[" + arrayElement + "] with NULL handles. Requires PARTIALLY_BOUND flag.");
        }

        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(imageViewHandle)
                    .sampler(samplerHandle);

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack)
                    .sType$Default()
                    .dstSet(set)
                    .dstBinding(binding)
                    .dstArrayElement(arrayElement)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo);

            vkUpdateDescriptorSets(device, descriptorWrite, null);
        }
    }

    public static void updateUniformBuffer(VkDevice device, long set, int binding, VulkanBuffer buffer) {
        if (buffer == null) {
            Gdx.app.error(TAG, "Attempting to update UBO binding " + binding + " with null VulkanBuffer object.");
            return;
        }
        updateUniformBuffer(device, set, binding, buffer.getBufferHandle(), 0, buffer.getSize());
    }

    public static void updateUniformBuffer(VkDevice device, long set, int binding, long bufferHandle, long offset, long range) {
        if (bufferHandle == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Attempting to update UBO binding " + binding + " with null buffer handle.");
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(bufferHandle)
                    .offset(offset)
                    .range(range);

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack)
                    .sType$Default()
                    .dstSet(set)
                    .dstBinding(binding)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(bufferInfo);

            vkUpdateDescriptorSets(device, descriptorWrite, null);
        }
    }

    public void freeSets(List<Long> setHandles) {
        if (setHandles == null || setHandles.isEmpty() || this.descriptorPool == VK_NULL_HANDLE) {
            return;
        }
        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
        if (gfx == null) {
            Gdx.app.error(TAG, "Cannot queue sets for freeing: VulkanGraphics not available.");
            return;
        }

        int currentFrameIndex = gfx.getCurrentFrameIndex();
        List<Long> frameQueue = setsToFree.get(currentFrameIndex);
        synchronized (frameQueue) {
            frameQueue.addAll(setHandles);
        }
    }

    public void cleanupCompletedFrameSets(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= setsToFree.size()) return;

        List<Long> handlesToActuallyFree;
        List<Long> frameQueue = setsToFree.get(frameIndex);

        synchronized (frameQueue) {
            if (frameQueue.isEmpty()) return;
            handlesToActuallyFree = new ArrayList<>(frameQueue);
            frameQueue.clear();
        }

        if (!handlesToActuallyFree.isEmpty() && this.descriptorPool != VK_NULL_HANDLE) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer pSets = stack.mallocLong(handlesToActuallyFree.size());
                for (int i = 0; i < handlesToActuallyFree.size(); i++) {
                    pSets.put(i, handlesToActuallyFree.get(i));
                }
                vkFreeDescriptorSets(device, descriptorPool, pSets);
            } catch (Exception e) {
                Gdx.app.error(TAG, "Exception during deferred vkFreeDescriptorSets for frame " + frameIndex, e);
            }
        }
    }

    @Override
    public void dispose() {
        if (DEBUG) Gdx.app.log(TAG, "Disposing VulkanDescriptorManager...");

        for (long layoutHandle : layoutCache.values()) {
            if (layoutHandle != VK_NULL_HANDLE) {
                vkDestroyDescriptorSetLayout(device, layoutHandle, null);
            }
        }
        layoutCache.clear();

        if (descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = VK_NULL_HANDLE;
        }

        if (setsToFree != null) {
            setsToFree.forEach(List::clear);
            setsToFree.clear();
        }
        if (DEBUG) Gdx.app.log(TAG, "VulkanDescriptorManager disposed.");
    }

    public VkDevice getDevice() {
        return device;
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
        String key = String.format("UBO0_TexArray1_Count%d_Partial%b_Update%b", textureCount, allowPartiallyBound, allowUpdateAfterBind);

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
}
