package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_CPU_TO_GPU;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanModelBatch implements Disposable {
    private static final String TAG = "VulkanModelBatch";
    private static final boolean DEBUG = true;

    private final VulkanDevice vulkanDevice;
    private final VkDevice rawDevice;
    private final VulkanDescriptorManager descriptorManager;
    private final long vmaAllocator;
    private final VulkanGraphics vulkanGraphics; // To get currentFrameIndex

    private Camera camera;
    private VkCommandBuffer currentCommandBuffer;

    // UBOs
    private VulkanBuffer globalUbo; // For View, Projection (Set 0)
    private ByteBuffer globalUboMapped;
    private VulkanBuffer objectUbo; // For Model matrix (Set 1)
    private ByteBuffer objectUboMapped;
    private VulkanBuffer materialUbo; // For Material properties (Set 1)
    private ByteBuffer materialUboMapped;

    private VulkanTexture defaultDiffuseTexture;

    // Descriptor Sets (per frame-in-flight)
    private final long[] perFrameGlobalDescriptorSets;     // For Set 0 (GlobalUBO)
    private final long[] perFrameObjectMaterialDescriptorSets; // For Set 1 (ObjectUBO, MaterialUBO, Textures)
    private final int maxFramesInFlight;
    private int currentFrameIndex = 0; // Current frame index for selecting DS

    private long lastBoundPipelineHandle = VK_NULL_HANDLE;
    private long lastBoundPipelineLayoutHandle = VK_NULL_HANDLE; // Stored from bundle

    public int renderCallsThisFrame = 0;

    public VulkanModelBatch(VulkanDevice device, VulkanDescriptorManager descriptorManager, VulkanGraphics graphics, long vmaAllocator) {
        this.vulkanDevice = Objects.requireNonNull(device, "VulkanDevice cannot be null.");
        this.rawDevice = device.getLogicalDevice();
        this.descriptorManager = Objects.requireNonNull(descriptorManager, "VulkanDescriptorManager cannot be null.");
        this.vulkanGraphics = Objects.requireNonNull(graphics, "VulkanGraphics cannot be null.");
        this.vmaAllocator = vmaAllocator;
        if (this.vmaAllocator == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VMA Allocator handle cannot be null.");
        }

        this.maxFramesInFlight = this.vulkanGraphics.config.MAX_FRAMES_IN_FLIGHT;
        this.perFrameGlobalDescriptorSets = new long[maxFramesInFlight];
        this.perFrameObjectMaterialDescriptorSets = new long[maxFramesInFlight];
        Arrays.fill(this.perFrameGlobalDescriptorSets, VK_NULL_HANDLE);
        Arrays.fill(this.perFrameObjectMaterialDescriptorSets, VK_NULL_HANDLE);

        createDefaultTextureInternal();
        createUboBuffers();
    }

    private void createDefaultTextureInternal() {
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        try {
            this.defaultDiffuseTexture = new VulkanTexture(pixmap);
        } catch (Exception e) {
            pixmap.dispose();
            throw new GdxRuntimeException("Failed to create default diffuse texture for ModelBatch", e);
        }
        pixmap.dispose();
        if (DEBUG && Gdx.app != null) Gdx.app.log(TAG, "Default diffuse texture created.");
    }

    private void createUboBuffers() {
        long globalUboSize = 2 * 16 * Float.BYTES; // View, Proj
        this.globalUbo = VulkanResourceUtil.createManagedBuffer(vmaAllocator, globalUboSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        this.globalUboMapped = this.globalUbo.getMappedByteBuffer();
        if (this.globalUboMapped == null) throw new GdxRuntimeException("Global UBO mapped buffer is null.");

        long objectUboSize = 1 * 16 * Float.BYTES; // Model Matrix
        this.objectUbo = VulkanResourceUtil.createManagedBuffer(vmaAllocator, objectUboSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        this.objectUboMapped = this.objectUbo.getMappedByteBuffer();
        if (this.objectUboMapped == null) throw new GdxRuntimeException("Object UBO mapped buffer is null.");

        long materialUboSize = 256; // Ensure fits VulkanMaterial.writeToUbo()
        this.materialUbo = VulkanResourceUtil.createManagedBuffer(vmaAllocator, materialUboSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        this.materialUboMapped = this.materialUbo.getMappedByteBuffer();
        if (this.materialUboMapped == null) throw new GdxRuntimeException("Material UBO mapped buffer is null.");
    }

    public void begin(Camera camera, VkCommandBuffer commandBuffer) {
        this.camera = Objects.requireNonNull(camera, "Camera cannot be null.");
        this.currentCommandBuffer = Objects.requireNonNull(commandBuffer, "VkCommandBuffer cannot be null.");
        this.lastBoundPipelineHandle = VK_NULL_HANDLE;
        this.renderCallsThisFrame = 0;
        this.currentFrameIndex = vulkanGraphics.getCurrentFrameIndex(); // Get current frame for DS selection

        // Update and prepare Global UBO (Set 0)
        globalUboMapped.position(0);
        FloatBuffer globalFb = globalUboMapped.asFloatBuffer();
        globalFb.put(camera.view.val);
        globalFb.position(16);
        globalFb.put(camera.projection.val);
        Vma.vmaFlushAllocation(vmaAllocator, globalUbo.getAllocationHandle(), 0, globalUbo.getSize());

        if (DEBUG) Gdx.app.debug(TAG, "Begin batch for frame: " + currentFrameIndex);
    }

    public void render(VulkanModelInstance instance) {
        if (instance == null || instance.model == null) { /* ... error ... */
            return;
        }
        if (camera == null || currentCommandBuffer == null) { /* ... throw GdxRuntimeException ... */ }

        for (VulkanMeshPart meshPart : instance.model.meshParts) {
            renderMeshPart(instance.transform, meshPart);
        }
    }

    private void renderMeshPart(Matrix4 modelWorldTransform, VulkanMeshPart meshPart) {
        VulkanMesh mesh = meshPart.mesh;
        VulkanMaterial material = meshPart.material;
        if (mesh == null || material == null || material.pipelineBundle == null) { /* ... error ... */
            return;
        }

        VulkanShaderPipelineBundle bundle = material.pipelineBundle;
        long pipelineToBind = bundle.getGraphicsPipeline();
        long currentPipelineLayout  = bundle.getPipelineLayout(); // Layout for binding BOTH sets

        if (pipelineToBind == VK_NULL_HANDLE || currentPipelineLayout  == VK_NULL_HANDLE) { /* ... error ... */
            return;
        }

        if (pipelineToBind != lastBoundPipelineHandle) {
            vkCmdBindPipeline(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineToBind);
            lastBoundPipelineHandle = pipelineToBind;
        }

        // --- Prepare Set 0: Global UBO (View, Projection) ---
        // This set should ideally be bound once per frame if Begin() handles it and if the bundle's
        // pipelineLayout allows for it. Here, we assume the pipelineLayout expects set 0 and set 1.
        long dslSet0 = bundle.getDescriptorSetLayoutHandle(0); // Assumes bundle exposes this
        if (perFrameGlobalDescriptorSets[currentFrameIndex] == VK_NULL_HANDLE) {
            perFrameGlobalDescriptorSets[currentFrameIndex] = descriptorManager.allocateSet(dslSet0);
        }
        long globalSet = perFrameGlobalDescriptorSets[currentFrameIndex];
        VulkanDescriptorManager.updateUniformBuffer(rawDevice, globalSet, 0, globalUbo.getBufferHandle(), 0, globalUbo.getSize());


        // --- Prepare Set 1: Object UBO (Model), Material UBO, Diffuse Sampler ---
        objectUboMapped.position(0);
        objectUboMapped.asFloatBuffer().put(modelWorldTransform.val);
        Vma.vmaFlushAllocation(vmaAllocator, objectUbo.getAllocationHandle(), 0, objectUbo.getSize());

        materialUboMapped.position(0);
        int materialBytesWritten = material.writeToUbo(materialUboMapped, 0);
        if (materialBytesWritten > 0) {
            Vma.vmaFlushAllocation(vmaAllocator, materialUbo.getAllocationHandle(), 0, materialBytesWritten);
        }

        long dslSet1 = bundle.getDescriptorSetLayoutHandle(1); // Assumes bundle exposes this
        if (perFrameObjectMaterialDescriptorSets[currentFrameIndex] == VK_NULL_HANDLE) {
            perFrameObjectMaterialDescriptorSets[currentFrameIndex] = descriptorManager.allocateSet(dslSet1);
        }
        long objectMaterialSet = perFrameObjectMaterialDescriptorSets[currentFrameIndex];

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer descriptorWritesSet1 = VkWriteDescriptorSet.calloc(3, stack);

            VkDescriptorBufferInfo.Buffer objectBI = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(objectUbo.getBufferHandle()).offset(0).range(objectUbo.getSize());
            descriptorWritesSet1.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(objectMaterialSet)
                    .dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(objectBI);

            VkDescriptorBufferInfo.Buffer materialBI = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(materialUbo.getBufferHandle()).offset(0).range(materialBytesWritten > 0 ? materialBytesWritten : materialUbo.getSize());
            descriptorWritesSet1.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(objectMaterialSet)
                    .dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(materialBI);

            VulkanTexture diffuseTex = material.diffuseTexture != null ? material.diffuseTexture : this.defaultDiffuseTexture;
            if (diffuseTex == null || diffuseTex.getImageViewHandle() == VK_NULL_HANDLE) throw new GdxRuntimeException("No valid diffuse texture.");
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(diffuseTex.getImageViewHandle())
                    .sampler(diffuseTex.getSamplerHandle());
            descriptorWritesSet1.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(objectMaterialSet)
                    .dstBinding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo);

            vkUpdateDescriptorSets(rawDevice, descriptorWritesSet1, null);

            // Bind both descriptor sets
            LongBuffer pSets = stack.longs(globalSet, objectMaterialSet);
            vkCmdBindDescriptorSets(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, currentPipelineLayout , 0, pSets, null);
        }

        // --- Bind Buffers and Draw ---
        try (MemoryStack stack = MemoryStack.stackPush()) { /* ... bind vertex/index buffers ... */ }
        // ... (draw call as before) ...
        if (mesh.isIndexed()) {
            vkCmdDrawIndexed(currentCommandBuffer, meshPart.numIndices, 1, meshPart.indexOffset, 0, 0);
        } else {
            vkCmdDraw(currentCommandBuffer, meshPart.numIndices, 1, meshPart.indexOffset, 0);
        }
        renderCallsThisFrame++;
    }

    public void end() {
        this.camera = null;
        this.currentCommandBuffer = null;
        if (DEBUG) Gdx.app.debug(TAG, "End. Total render calls this batch cycle: " + renderCallsThisFrame);
    }

    @Override
    public void dispose() {
        if (DEBUG) Gdx.app.log(TAG, "Disposing ModelBatchVulkan...");
        if (vulkanDevice != null && vulkanDevice.getLogicalDevice() != null) {
            vkDeviceWaitIdle(vulkanDevice.getLogicalDevice());
        }

        if (globalUbo != null) {
            globalUbo.dispose();
            globalUbo = null;
        }
        if (objectUbo != null) {
            objectUbo.dispose();
            objectUbo = null;
        }
        if (materialUbo != null) {
            materialUbo.dispose();
            materialUbo = null;
        }
        if (defaultDiffuseTexture != null) {
            defaultDiffuseTexture.dispose();
            defaultDiffuseTexture = null;
        }

        if (descriptorManager != null) {
            ArrayList<Long> allSetsToFree = new ArrayList<>();
            for (long setHandle : perFrameGlobalDescriptorSets) if (setHandle != VK_NULL_HANDLE) allSetsToFree.add(setHandle);
            for (long setHandle : perFrameObjectMaterialDescriptorSets) if (setHandle != VK_NULL_HANDLE) allSetsToFree.add(setHandle);
            if (!allSetsToFree.isEmpty()) descriptorManager.freeSets(allSetsToFree);
        }
        Arrays.fill(perFrameGlobalDescriptorSets, VK_NULL_HANDLE);
        Arrays.fill(perFrameObjectMaterialDescriptorSets, VK_NULL_HANDLE);

        if (DEBUG) Gdx.app.log(TAG, "ModelBatchVulkan disposed.");
    }
}