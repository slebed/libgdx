package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectIntMap;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;

public class VulkanTextureBatch implements Disposable {
    private static final String TAG = "VulkanTextureBatch";
    private static final boolean DEBUG = false;

    private final VulkanDescriptorManager descriptorManager;
    private final VkDevice rawDevice;
    private final VulkanGraphics vulkanGraphics;

    private final Array<VulkanTexture> uniqueTexturesForCurrentDrawCycle;
    private final ObjectIntMap<VulkanTexture> textureToDeviceIndexMap;
    private int nextDeviceIndex;

    private long descriptorSetLayout = VK_NULL_HANDLE;
    private final long[] frameDescriptorSets;
    private final int maxFramesInFlight;
    private VulkanTexture defaultTexture;

    final int maxTexturesInLayout;
    private long activeFrameDescriptorSet = VK_NULL_HANDLE;

    private boolean activeFrameSetPopulated;

    public VulkanTextureBatch(VulkanDescriptorManager descriptorManager, int maxTextures, VulkanGraphics gfx) {
        this.descriptorManager = descriptorManager;
        this.rawDevice = descriptorManager.getDevice();
        this.vulkanGraphics = gfx;
        this.maxFramesInFlight = vulkanGraphics.config.MAX_FRAMES_IN_FLIGHT;
        if (this.maxFramesInFlight <= 0) {
            throw new GdxRuntimeException("MAX_FRAMES_IN_FLIGHT must be positive.");
        }

        this.maxTexturesInLayout = maxTextures;
        if (DEBUG) Gdx.app.log(TAG, "Initializing with maxTexturesInLayout: " + this.maxTexturesInLayout + ", maxFramesInFlight: " + this.maxFramesInFlight);

        this.uniqueTexturesForCurrentDrawCycle = new Array<>(false, Math.max(16, maxTextures));
        this.textureToDeviceIndexMap = new ObjectIntMap<>(Math.max(16, maxTextures));
        this.frameDescriptorSets = new long[this.maxFramesInFlight];

        createDefaultTexture();
        createDescriptorSetLayout();
        allocateDescriptorSets();
    }

    private void createDefaultTexture() {
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(com.badlogic.gdx.graphics.Color.WHITE);
        pixmap.fill();
        this.defaultTexture = new VulkanTexture(pixmap);
        pixmap.dispose();
        if (DEBUG && defaultTexture != null) Gdx.app.log(TAG, "Default white texture created: " + defaultTexture.getImageViewHandle());
    }

    private void createDescriptorSetLayout() {
        this.descriptorSetLayout = descriptorManager.getOrCreateBindlessLikeTextureArrayLayout(
                maxTexturesInLayout,
                true,  // allowPartiallyBound
                false  // allowUpdateAfterBind is false
        );
        if (this.descriptorSetLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to create descriptor set layout for VulkanTextureBatch");
        }
        if (DEBUG) Gdx.app.log(TAG, "DescriptorSetLayout created/retrieved: " + this.descriptorSetLayout);
    }

    private void allocateDescriptorSets() {
        for (int i = 0; i < maxFramesInFlight; i++) {
            frameDescriptorSets[i] = descriptorManager.allocateSet(this.descriptorSetLayout);
            if (frameDescriptorSets[i] == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Failed to allocate descriptor set " + i + " for VulkanTextureBatch");
            }
            // Initialize all texture array slots (binding 1) in the new descriptor set with the defaultTexture.
            // Binding 0 (UBO) will be updated just before first use in a frame.
            for (int slot = 0; slot < maxTexturesInLayout; slot++) {
                VulkanDescriptorManager.updateCombinedImageSampler(rawDevice, frameDescriptorSets[i], 1, slot, defaultTexture.getImageViewHandle(), defaultTexture.getSamplerHandle());
            }
            if (DEBUG) Gdx.app.log(TAG, "Allocated and initialized DescriptorSet[" + i + "]: " + frameDescriptorSets[i] + " with default textures.");
        }
    }

    public void resetAndPrepareForFrame() {
        int frameIndex = vulkanGraphics.getCurrentFrameIndex();
        if (frameIndex < 0 || frameIndex >= frameDescriptorSets.length) {
            throw new GdxRuntimeException("Invalid frameIndex " + frameIndex + " in resetAndPrepareForFrame. Array size: " + frameDescriptorSets.length);
        }
        this.activeFrameDescriptorSet = this.frameDescriptorSets[frameIndex];

        uniqueTexturesForCurrentDrawCycle.clear();
        textureToDeviceIndexMap.clear();
        nextDeviceIndex = 0;
        activeFrameSetPopulated = false; // CRITICAL: Descriptor set for this frame needs to be fully populated once.

        if (defaultTexture != null) {
            textureToDeviceIndexMap.put(defaultTexture, nextDeviceIndex);
            uniqueTexturesForCurrentDrawCycle.add(defaultTexture);
            nextDeviceIndex++;
        }
        if (DEBUG) Gdx.app.debug(TAG, "Reset for new batch. Active DS for frame " + frameIndex + ": " + this.activeFrameDescriptorSet);
    }

    public int addTexture(VulkanTexture texture) {
        if (texture == null) texture = defaultTexture;
        if (texture == null) throw new GdxRuntimeException("Default texture is null in addTexture after fallback.");

        if (textureToDeviceIndexMap.containsKey(texture)) {
            return textureToDeviceIndexMap.get(texture, 0);
        }

        if (nextDeviceIndex >= maxTexturesInLayout) {
            Gdx.app.error(TAG, "Max unique textures (" + maxTexturesInLayout + ") for this begin/end cycle reached. Using default texture for: " + (texture.getFilePath() != null ? texture.getFilePath() : "PixmapTexture"));
            return textureToDeviceIndexMap.get(defaultTexture, 0);
        }

        int assignedIndex = nextDeviceIndex++;
        textureToDeviceIndexMap.put(texture, assignedIndex);
        uniqueTexturesForCurrentDrawCycle.add(texture);

        // Adding a new texture means the set *might* need an update if it hasn't been populated yet
        // for this begin/end cycle. activeFrameSetPopulated handles this in buildAndBind.
        // No need to change activeFrameSetPopulated here.
        if (DEBUG)
            Gdx.app.debug(TAG, "Added unique texture '" + (texture.getFilePath() != null ? texture.getFilePath() : "PixmapTex") + "' to current cycle, assigned slot: " + assignedIndex);
        return assignedIndex;
    }

    public void buildAndBind(VkCommandBuffer cmdBuffer, long pipelineLayout, VulkanBuffer projMatrixUbo) {
        if (this.activeFrameDescriptorSet == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Active frame descriptor set is null in buildAndBind! Was resetAndPrepareForFrame called properly?");
        }

        // If the descriptor set for this frame has not yet been fully populated in this
        // SpriteBatch.begin()/end() cycle, update ALL its bindings now.
        if (!activeFrameSetPopulated) {
            if (DEBUG) Gdx.app.log(TAG, "Populating DescriptorSet " + activeFrameDescriptorSet + " (UBO & Textures) for first use in this batch cycle.");

            // 1. Update UBO (Binding 0)
            if (projMatrixUbo != null && projMatrixUbo.bufferHandle != VK_NULL_HANDLE) {
                VulkanDescriptorManager.updateUniformBuffer(rawDevice, activeFrameDescriptorSet, 0, projMatrixUbo.bufferHandle, 0L, projMatrixUbo.size);
            } else {
                Gdx.app.error(TAG, "Projection Matrix UBO is null or invalid during DS population.");
            }

            // 2. Update all texture array slots (Binding 1) based on unique textures collected
            if (DEBUG) Gdx.app.log(TAG, "  Updating texture array with " + uniqueTexturesForCurrentDrawCycle.size + " unique textures for DS " + activeFrameDescriptorSet);
            for (int i = 0; i < uniqueTexturesForCurrentDrawCycle.size; i++) {
                VulkanTexture tex = uniqueTexturesForCurrentDrawCycle.get(i);
                int deviceIndex = textureToDeviceIndexMap.get(tex, -1); // Should match 'i' if logic is sequential

                if (deviceIndex == -1) {
                    Gdx.app.error(TAG, "Texture " + (tex.getFilePath() != null ? tex.getFilePath() : "PixmapTex") + " not in map during build! Using default.");
                    tex = defaultTexture; // Fallback to ensure valid handles
                    deviceIndex = textureToDeviceIndexMap.get(defaultTexture, 0); // Default is usually index 0
                }

                long viewHandle = tex.getImageViewHandle();
                long samplerHandle = tex.getSamplerHandle();
                if (viewHandle == VK_NULL_HANDLE || samplerHandle == VK_NULL_HANDLE) {
                    Gdx.app.error(TAG, "Texture (path: " + tex.getFilePath() + ") at deviceIndex " + deviceIndex + " has null view/sampler. Using default.");
                    viewHandle = defaultTexture.getImageViewHandle();
                    samplerHandle = defaultTexture.getSamplerHandle();
                }
                VulkanDescriptorManager.updateCombinedImageSampler(rawDevice, activeFrameDescriptorSet, 1, deviceIndex, viewHandle, samplerHandle);
            }
            // Ensure remaining unassigned slots in the texture array point to the default texture.
            // This ensures all descriptors in the array are valid if not using VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT
            // effectively, or if shaders might sample outside the range of uniqueTexturesForCurrentDrawCycle.size.
            // This was already done during allocateDescriptorSets, so it's a "top-up".
            for (int i = uniqueTexturesForCurrentDrawCycle.size; i < maxTexturesInLayout; i++) {
                VulkanDescriptorManager.updateCombinedImageSampler(rawDevice, activeFrameDescriptorSet, 1, i, defaultTexture.getImageViewHandle(), defaultTexture.getSamplerHandle());
            }

            activeFrameSetPopulated = true; // Mark as fully populated for this begin/end cycle
        } else {
            // The descriptor set (both UBO pointer and texture array pointers) is already populated for this cycle.
            // The UBO *buffer content* might have been updated by SpriteBatch directly via VMA mapping,
            // which is fine. We don't need to call vkUpdateDescriptorSets again for the UBO binding here.
            if (DEBUG) Gdx.app.debug(TAG, "DescriptorSet " + activeFrameDescriptorSet + " already populated this cycle. Binding directly.");
        }

        // 3. Bind the descriptor set (now correctly populated for this entire begin/end cycle)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(cmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(activeFrameDescriptorSet), null);
        }
    }

    public long getDescriptorSetLayout() {
        return descriptorSetLayout;
    }

    public VulkanTexture getDefaultTexture() {
        return defaultTexture;
    }

    @Override
    public void dispose() {
        if (DEBUG) Gdx.app.log(TAG, "Disposing VulkanTextureBatch.");
        if (defaultTexture != null) {
            defaultTexture.dispose();
            defaultTexture = null;
        }

        if (descriptorManager != null && frameDescriptorSets != null && frameDescriptorSets.length > 0) {
            ArrayList<Long> setsToFreeList = new ArrayList<>(frameDescriptorSets.length);
            for (long setHandle : frameDescriptorSets) {
                if (setHandle != VK_NULL_HANDLE) {
                    setsToFreeList.add(setHandle);
                }
            }
            if (!setsToFreeList.isEmpty()) {
                descriptorManager.freeSets(setsToFreeList);
            }
        }
        Arrays.fill(frameDescriptorSets, VK_NULL_HANDLE);
        descriptorSetLayout = VK_NULL_HANDLE;
        if (DEBUG) Gdx.app.log(TAG, "VulkanTextureBatch disposed.");
    }
}