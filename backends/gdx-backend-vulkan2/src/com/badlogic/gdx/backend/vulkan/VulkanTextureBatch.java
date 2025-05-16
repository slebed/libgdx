package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
// import com.badlogic.gdx.graphics.Texture; // Not directly used
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectIntMap;
// import com.badlogic.gdx.utils.ObjectSet; // Not used in this version

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import java.nio.LongBuffer;
import java.util.Arrays; // For Arrays.fill

import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages a batch of textures for bindless-like rendering with Vulkan 1.2+.
 * Creates a single descriptor set containing a texture array.
 * Assumes Vulkan 1.2+ features like partiallyBound and shaderSampledImageArrayNonUniformIndexing are available.
 * This version is adapted to not directly take VulkanDeviceCapabilities in constructor,
 * making assumptions for a Vulkan 1.2+ environment.
 */
public class VulkanTextureBatch implements Disposable {
    private static final String TAG = "VulkanTextureBatch";
    private static final boolean DEBUG = false;

    private final VulkanDescriptorManager descriptorManager;
    // private final VulkanDeviceCapabilities deviceCapabilities; // Removed from constructor
    private final VkDevice rawDevice;
    private final VulkanGraphics vulkanGraphics; // To get MAX_FRAMES_IN_FLIGHT

    private final Array<VulkanTexture> activeTexturesThisBuild; // Textures added since last build/reset for current frame set
    private final ObjectIntMap<VulkanTexture> textureToDeviceIndexMap; // Maps texture to its final index in the descriptor array
    private int nextDeviceIndex; // Next available slot in the texture array for a new texture

    private long descriptorSetLayout = VK_NULL_HANDLE;
    private final long[] frameDescriptorSets; // Per-frame descriptor sets
    private final int maxFramesInFlight;
    private VulkanTexture defaultTexture; // A 1x1 white texture

    private final int maxTexturesInLayout; // Max textures this batcher's layout can hold
    private boolean needsBuild = true; // Does the current frame's descriptor set need updating?

    /**
     * @param descriptorManager Manager for descriptor operations.
     * @param maxTextures Maximum number of unique textures this batcher can handle in its descriptor set.
     * This is a software-defined cap, should be <= device's maxDescriptorSetSamplers.
     * @param gfx Used to get MAX_FRAMES_IN_FLIGHT.
     */
    public VulkanTextureBatch(VulkanDescriptorManager descriptorManager, int maxTextures, VulkanGraphics gfx) {
        this.descriptorManager = descriptorManager;
        this.rawDevice = descriptorManager.getDevice();
        this.vulkanGraphics = gfx; // Store VulkanGraphics
        this.maxFramesInFlight = vulkanGraphics.config.MAX_FRAMES_IN_FLIGHT;

        // For a 1.2+ focused path, we assume required features are present.
        // A full implementation would still ideally check capabilities.
        if (DEBUG) Gdx.app.log(TAG, "Initializing with Vulkan 1.2+ assumptions.");

        // maxTextures is now a software-defined limit, not necessarily the hardware limit.
        // If you wanted to use the hardware limit, capabilities would be needed here.
        this.maxTexturesInLayout = maxTextures;
        if (DEBUG) Gdx.app.log(TAG, "Initializing with maxTexturesInLayout: " + this.maxTexturesInLayout);


        this.activeTexturesThisBuild = new Array<>(false, 16);
        this.textureToDeviceIndexMap = new ObjectIntMap<>();
        this.nextDeviceIndex = 0;
        this.frameDescriptorSets = new long[maxFramesInFlight];

        createDefaultTexture();
        createDescriptorSetLayout(); // Uses maxTexturesInLayout
        allocateDescriptorSets();
    }

    private void createDefaultTexture() {
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(com.badlogic.gdx.graphics.Color.WHITE);
        pixmap.fill();
        this.defaultTexture = new VulkanTexture(pixmap);
        pixmap.dispose();
        if (DEBUG) Gdx.app.log(TAG, "Default white texture created.");
    }

    private void createDescriptorSetLayout() {
        // Layout: Binding 0 = UBO (projection matrix), Binding 1 = Texture Array
        // Assuming Vulkan 1.2+ features like partiallyBound are available.
        this.descriptorSetLayout = descriptorManager.getOrCreateBindlessLikeTextureArrayLayout(
                maxTexturesInLayout, // descriptorCount for binding 1 (textures)
                true, // allowPartiallyBound (essential for 1.2+ bindless style)
                false // allowUpdateAfterBind (can be true if needed, requires pool flag)
        );
        if (this.descriptorSetLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to create descriptor set layout for VulkanTextureBatch");
        }
        if (DEBUG) Gdx.app.log(TAG, "DescriptorSetLayout created: " + this.descriptorSetLayout + " for " + maxTexturesInLayout + " textures.");
    }

    private void allocateDescriptorSets() {
        for (int i = 0; i < maxFramesInFlight; i++) {
            frameDescriptorSets[i] = descriptorManager.allocateSet(this.descriptorSetLayout);
            if (frameDescriptorSets[i] == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Failed to allocate descriptor set " + i + " for VulkanTextureBatch");
            }
            // Initialize all texture slots in the new descriptor set with the defaultTexture
            for (int slot = 0; slot < maxTexturesInLayout; slot++) {
                VulkanDescriptorManager.updateCombinedImageSampler(rawDevice, frameDescriptorSets[i], 1, slot, defaultTexture.getImageViewHandle(), defaultTexture.getSamplerHandle());
            }
            if (DEBUG) Gdx.app.log(TAG, "Allocated and initialized DescriptorSet[" + i + "]: " + frameDescriptorSets[i]);
        }
    }

    /**
     * Registers a texture with the batch for the current frame/build.
     * If the texture is new for this build cycle (since last resetAndPrepareForFrame),
     * it's assigned an index if slots are available.
     * This index is what the shader will use to access the texture in the array.
     * @param texture The texture to add.
     * @return The device index for this texture in the descriptor array.
     */
    public int addTexture(VulkanTexture texture) {
        if (texture == null) {
            texture = defaultTexture;
        }
        if (texture == null) { // Should not happen if defaultTexture is created
            throw new GdxRuntimeException("Attempted to add null texture and defaultTexture is also null.");
        }


        if (textureToDeviceIndexMap.containsKey(texture)) {
            return textureToDeviceIndexMap.get(texture, 0); // Return existing index
        }

        // Texture is new for this current "build cycle" (since last reset)
        if (nextDeviceIndex >= maxTexturesInLayout) {
            Gdx.app.error(TAG, "VulkanTextureBatch ran out of texture slots for new unique textures! Max: " + maxTexturesInLayout +
                    ". This means more unique textures are being drawn in a single batch segment than the layout supports.");
            // Fallback: return the index of the default texture. This will prevent crashes but show wrong textures.
            // A more robust solution might involve flushing the SpriteBatch earlier if this limit is hit.
            return textureToDeviceIndexMap.get(defaultTexture, 0); // Default texture should always be in the map at index 0
        }

        int assignedIndex = nextDeviceIndex++;
        activeTexturesThisBuild.add(texture); // Track for updating the descriptor set during buildAndBind
        textureToDeviceIndexMap.put(texture, assignedIndex);
        needsBuild = true; // Mark that the current frame's descriptor set needs updating
        if (DEBUG) Gdx.app.log(TAG, "Added new texture (hash: " + texture.hashCode() + ") to active build, assigned device index: " + assignedIndex);
        return assignedIndex;
    }


    /**
     * Prepares the texture batcher for a new frame or a new batch segment after a flush.
     * Clears the list of textures that were active in the *previous* build/bind operation for this frame,
     * and resets the index for assigning new textures.
     */
    public void resetAndPrepareForFrame() {
        activeTexturesThisBuild.clear(); // Clear textures that were part of the last build for this frame
        textureToDeviceIndexMap.clear(); // Clear the mapping for the new build
        nextDeviceIndex = 0;             // Reset index for assigning new textures
        needsBuild = true;               // A new build will be needed for any textures added

        // Ensure defaultTexture is always available, typically at index 0
        if (defaultTexture != null) {
            addTexture(defaultTexture); // This will add it to activeTexturesThisBuild and map it to index 0
        }
        if (DEBUG) Gdx.app.log(TAG, "Reset and prepared for new frame/batch. Default texture added to slot 0.");
    }

    /**
     * Updates the Vulkan descriptor set for the current frame if new textures were added (needsBuild is true).
     * Then binds this descriptor set to the command buffer.
     * Also updates and binds the UBO for the projection matrix to binding 0 of the same set.
     *
     * @param cmdBuffer The command buffer to bind the descriptor set to.
     * @param pipelineLayout The pipeline layout to use for binding.
     * @param projMatrixUbo The UBO containing the projection matrix (for binding 0).
     */
    public void buildAndBind(VkCommandBuffer cmdBuffer, long pipelineLayout, VulkanBuffer projMatrixUbo) {
        int currentFrameIdx = vulkanGraphics.getCurrentFrameIndex();
        if (currentFrameIdx < 0 || currentFrameIdx >= maxFramesInFlight) {
            Gdx.app.error(TAG, "Invalid frameIndex " + currentFrameIdx + " in buildAndBind");
            return;
        }
        long currentFrameSet = frameDescriptorSets[currentFrameIdx];

        // 1. Always update UBO (Binding 0) for the current frame's set
        if (projMatrixUbo != null && projMatrixUbo.getBufferHandle() != VK_NULL_HANDLE) {
            VulkanDescriptorManager.updateUniformBuffer(rawDevice, currentFrameSet, 0, projMatrixUbo.getBufferHandle(), projMatrixUbo.getOffset(), projMatrixUbo.getSize());
        } else {
            Gdx.app.error(TAG, "Projection Matrix UBO is null or invalid, cannot update descriptor set binding 0 for frame " + currentFrameIdx);
        }

        // 2. Update texture samplers if `needsBuild` is true for the current frame (Binding 1)
        if (needsBuild) {
            if (DEBUG) Gdx.app.log(TAG, "Updating descriptor set for frame " + currentFrameIdx + " with " + activeTexturesThisBuild.size + " unique textures for this build.");

            for (VulkanTexture texture : activeTexturesThisBuild) {
                int deviceIndex = textureToDeviceIndexMap.get(texture, -1);
                if (deviceIndex != -1) {
                    long viewHandle = texture.getImageViewHandle();
                    long samplerHandle = texture.getSamplerHandle();
                    if (viewHandle == VK_NULL_HANDLE || samplerHandle == VK_NULL_HANDLE) {
                        Gdx.app.error(TAG, "Attempting update with null handles for texture (hash: " + texture.hashCode() + ") at index " + deviceIndex + ". Using default.");
                        viewHandle = defaultTexture.getImageViewHandle();
                        samplerHandle = defaultTexture.getSamplerHandle();
                    }
                    VulkanDescriptorManager.updateCombinedImageSampler(rawDevice, currentFrameSet, 1, deviceIndex, viewHandle, samplerHandle);
                    if (DEBUG) Gdx.app.log(TAG, "  Updated frame " + currentFrameIdx + " slot " + deviceIndex + " with texture (hash: " + texture.hashCode() + ")");
                }
            }
            needsBuild = false; // Mark as updated for this build cycle
        } else {
            if (DEBUG) Gdx.app.log(TAG, "Skipping texture updates for frame " + currentFrameIdx + ", needsBuild is false.");
        }

        // 3. Bind the descriptor set for the current frame
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSet = stack.longs(currentFrameSet);
            vkCmdBindDescriptorSets(cmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, pSet, null);
        }
        if (DEBUG) Gdx.app.log(TAG, "Bound descriptor set for frame " + currentFrameIdx + (needsBuild ? " (after build)" : " (pre-built)"));
    }

    public long getDescriptorSetLayout() {
        return descriptorSetLayout;
    }

    public VulkanTexture getDefaultTexture() {
        return defaultTexture;
    }

    /**
     * Updates the descriptor set for the specified frame *if* new textures were added.
     * This should be called BEFORE the command buffer using this set begins recording draw calls.
     * Typically called from VulkanSpriteBatch.prepareResourcesForFrame.
     *
    // * @param frameIndex The index of the frame whose descriptor set needs potential updating.
    // * @param projMatrixUbo The UBO to bind to binding 0.
     */
    /*public void updateDescriptorSetIfNeeded(int frameIndex, VulkanBuffer projMatrixUbo) {
        if (frameIndex < 0 || frameIndex >= maxFramesInFlight) {
            Gdx.app.error(TAG, "Invalid frameIndex " + frameIndex + " in updateDescriptorSetIfNeeded");
            return;
        }
        long currentFrameSet = frameDescriptorSets[frameIndex];

        // 1. Always update UBO (Binding 0) - Assume projection matrix might change each frame
        // Use getters for VulkanBuffer properties
        if (projMatrixUbo != null && projMatrixUbo.getBufferHandle() != VK_NULL_HANDLE) {
            VulkanDescriptorManager.updateUniformBuffer(rawDevice, currentFrameSet, 0, projMatrixUbo.getBufferHandle(), projMatrixUbo.getOffset(), projMatrixUbo.getSize());
        } else {
            Gdx.app.error(TAG, "Projection Matrix UBO is null or invalid during updateDescriptorSetIfNeeded for frame " + frameIndex);
        }

        // 2. Update texture samplers only if needed (Binding 1)
        if (needsBuild) {
            if (DEBUG) Gdx.app.log(TAG, "Updating descriptor set for frame " + frameIndex + " with " + activeTexturesThisBuild.size + " unique textures for this build.");

            for (VulkanTexture texture : activeTexturesThisBuild) {
                int deviceIndex = textureToDeviceIndexMap.get(texture, -1);
                if (deviceIndex != -1) {
                    // Use getters for VulkanTexture properties
                    long viewHandle = texture.getImageViewHandle();
                    long samplerHandle = texture.getSamplerHandle();
                    if (viewHandle == VK_NULL_HANDLE || samplerHandle == VK_NULL_HANDLE) {
                        Gdx.app.error(TAG, "Attempting update with null handles for texture (hash: " + texture.hashCode() + ") at index " + deviceIndex + ". Using default.");
                        viewHandle = defaultTexture.getImageViewHandle();
                        samplerHandle = defaultTexture.getSamplerHandle();
                    }
                    VulkanDescriptorManager.updateCombinedImageSampler(rawDevice, currentFrameSet, 1, deviceIndex, viewHandle, samplerHandle);
                    if (DEBUG) Gdx.app.log(TAG, "  Updated frame " + frameIndex + " slot " + deviceIndex + " with texture (hash: " + texture.hashCode() + ")");
                }
            }
            // Note: Slots not included in activeTexturesThisBuild retain their previous state (likely defaultTexture).
            needsBuild = false; // Mark as updated for this cycle
        } else {
            if (DEBUG) Gdx.app.log(TAG, "Skipping texture updates for frame " + frameIndex + ", needsBuild is false.");
        }
    }*/

    @Override
    public void dispose() {
        if (DEBUG) Gdx.app.log(TAG, "Disposing VulkanTextureBatch.");
        if (defaultTexture != null) {
            defaultTexture.dispose();
            defaultTexture = null;
        }
        // Descriptor sets are typically freed by the VulkanDescriptorManager when its pool is reset or destroyed.
        // If they were allocated from a pool that is managed per-frame or per-batcher instance,
        // they might need explicit freeing here if the manager doesn't handle it.
        // For now, assuming manager handles freeing sets based on pool lifecycle.
        // Descriptor set layout is also cached and freed by VulkanDescriptorManager.
        if (descriptorManager != null && descriptorSetLayout != VK_NULL_HANDLE) {
            // If the layout was uniquely created for this batcher and not cached globally by name/config,
            // it might need to be destroyed. However, getOrCreate... implies caching by manager.
            // descriptorManager.destroyDescriptorSetLayout(descriptorSetLayout); // If manager doesn't auto-cleanup
        }
        descriptorSetLayout = VK_NULL_HANDLE;
        Arrays.fill(frameDescriptorSets, VK_NULL_HANDLE); // Clear local handles
    }
}
