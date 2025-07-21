package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
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
import java.util.Objects;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_CPU_TO_GPU;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanModelBatch implements Disposable {
    private static final String TAG = "VulkanModelBatch";
    private static final boolean DEBUG = false;

    private VulkanDevice vulkanDevice;
    private VkDevice rawDevice;
    private VulkanDescriptorManager descriptorManager;
    private long vmaAllocator;
    private VulkanGraphics vulkanGraphics; // To get currentFrameIndex

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
    private long[] perFrameGlobalDescriptorSets;
    private long[] perFrameObjectMaterialDescriptorSets;
    private int maxFramesInFlight;
    private int currentFrameIndex = 0;

    private long lastBoundPipelineHandle = VK_NULL_HANDLE;

    // --- PROFILING COUNTERS ---
    public int renderCalls = 0;
    public int shaderSwitches = 0;
    public int numVertices = 0;
    // ---

    public VulkanModelBatch() {
        VulkanApplication app = (VulkanApplication) Gdx.app;
        VulkanDevice device = app.getVkDevice();
        VulkanDescriptorManager descriptorManager = app.getDescriptorManager();
        VulkanGraphics graphics = (VulkanGraphics) app.getGraphics();
        init(device, descriptorManager, graphics, app.getVmaAllocator());
    }

    public VulkanModelBatch(VulkanDevice device, VulkanDescriptorManager descriptorManager, VulkanGraphics graphics, long vmaAllocator) {
        init(device, descriptorManager, graphics, vmaAllocator);
    }

    private void init(VulkanDevice device, VulkanDescriptorManager descriptorManager, VulkanGraphics graphics, long vmaAllocator) {
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
        this.defaultDiffuseTexture = new VulkanTexture(pixmap);
        pixmap.dispose();
        if (DEBUG && Gdx.app != null) Gdx.app.log(TAG, "Default diffuse texture created.");
    }

    private void createUboBuffers() {
        long globalUboSize = 2 * 16 * Float.BYTES; // View, Proj
        this.globalUbo = VulkanResourceUtil.createManagedBuffer(vmaAllocator, globalUboSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        this.globalUboMapped = this.globalUbo.getMappedByteBuffer();

        long objectUboSize = 1 * 16 * Float.BYTES; // Model Matrix
        this.objectUbo = VulkanResourceUtil.createManagedBuffer(vmaAllocator, objectUboSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        this.objectUboMapped = this.objectUbo.getMappedByteBuffer();

        long materialUboSize = 256; // Ensure fits VulkanMaterial.writeToUbo()
        this.materialUbo = VulkanResourceUtil.createManagedBuffer(vmaAllocator, materialUboSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        this.materialUboMapped = this.materialUbo.getMappedByteBuffer();
    }

    public void begin(Camera camera) {
        this.camera = Objects.requireNonNull(camera, "Camera cannot be null.");
        this.currentCommandBuffer = Objects.requireNonNull(vulkanGraphics.getCurrentCommandBuffer(), "VkCommandBuffer cannot be null.");
        this.lastBoundPipelineHandle = VK_NULL_HANDLE;
        this.currentFrameIndex = vulkanGraphics.getCurrentFrameIndex();

        /*// Update and prepare Global UBO (Set 0)
        globalUboMapped.position(0);
        FloatBuffer globalFb = globalUboMapped.asFloatBuffer();
        camera.view.get(globalFb);
        globalFb.position(16);
        camera.projection.get(globalFb);*/
        // Update and prepare Global UBO (Set 0)
        globalUboMapped.position(0);
        FloatBuffer globalFb = globalUboMapped.asFloatBuffer();
        globalFb.put(camera.view.val);
        globalFb.position(16); // Move position by 16 floats (size of a 4x4 matrix)
        globalFb.put(camera.projection.val);
        Vma.vmaFlushAllocation(vmaAllocator, globalUbo.getAllocationHandle(), 0, globalUbo.getSize());

        if (DEBUG) Gdx.app.debug(TAG, "Begin batch for frame: " + currentFrameIndex);
    }

    public void render(VulkanModelInstance instance) {
        render(instance, null);
    }

    public void render(VulkanModelInstance instance, Environment environment) {
        if (instance == null || instance.model == null) return;
        if (camera == null || currentCommandBuffer == null) throw new GdxRuntimeException("begin() must be called before render()");

        for (VulkanMeshPart meshPart : instance.model.meshParts) {
            renderMeshPart(instance.transform, meshPart, environment);
        }
    }

    public void render(Array<VulkanModelInstance> instances) {
        render(instances, null);
    }

    public void render(Array<VulkanModelInstance> instances, Environment environment) {
        for (VulkanModelInstance instance : instances) {
            render(instance, environment);
        }
    }

    private void renderMeshPart(Matrix4 modelWorldTransform, VulkanMeshPart meshPart, Environment environment) {
        VulkanMesh mesh = meshPart.mesh;
        VulkanMaterial material = meshPart.material;
        if (mesh == null || material == null) return;

        if (material.pipelineBundle == null) {
            Gdx.app.error(TAG, "Cannot render mesh part '" + meshPart.id + "', its material has no pipeline bundle assigned.");
            return;
        }

        VulkanShaderPipelineBundle bundle = material.pipelineBundle;
        long pipelineToBind = bundle.getGraphicsPipeline();
        long currentPipelineLayout = bundle.getPipelineLayout();

        if (pipelineToBind == VK_NULL_HANDLE || currentPipelineLayout == VK_NULL_HANDLE) return;

        if (pipelineToBind != lastBoundPipelineHandle) {
            vkCmdBindPipeline(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineToBind);
            lastBoundPipelineHandle = pipelineToBind;
            shaderSwitches++; // Increment shader switch count
        }

        // --- Prepare Set 0: Global UBO (View, Projection) ---
        long dslSet0 = bundle.getDescriptorSetLayoutHandle(0);
        if (perFrameGlobalDescriptorSets[currentFrameIndex] == VK_NULL_HANDLE) {
            perFrameGlobalDescriptorSets[currentFrameIndex] = descriptorManager.allocateSet(dslSet0);
        }
        long globalSet = perFrameGlobalDescriptorSets[currentFrameIndex];
        VulkanDescriptorManager.updateUniformBuffer(rawDevice, globalSet, 0, globalUbo.getBufferHandle(), 0, globalUbo.getSize());

        // --- Prepare Set 1: Object UBO, Material UBO, Texture ---
        objectUboMapped.position(0);
        //modelWorldTransform.get(objectUboMapped.asFloatBuffer());
        objectUboMapped.asFloatBuffer().put(modelWorldTransform.val);
        Vma.vmaFlushAllocation(vmaAllocator, objectUbo.getAllocationHandle(), 0, objectUbo.getSize());

        materialUboMapped.position(0);
        int materialBytesWritten = material.writeToUbo(materialUboMapped, 0);
        if (materialBytesWritten > 0) {
            Vma.vmaFlushAllocation(vmaAllocator, materialUbo.getAllocationHandle(), 0, materialBytesWritten);
        }

        long dslSet1 = bundle.getDescriptorSetLayoutHandle(1);
        if (perFrameObjectMaterialDescriptorSets[currentFrameIndex] == VK_NULL_HANDLE) {
            perFrameObjectMaterialDescriptorSets[currentFrameIndex] = descriptorManager.allocateSet(dslSet1);
        }
        long objectMaterialSet = perFrameObjectMaterialDescriptorSets[currentFrameIndex];

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(3, stack);

            // Object UBO
            VkDescriptorBufferInfo.Buffer objectBI = VkDescriptorBufferInfo.calloc(1, stack).buffer(objectUbo.getBufferHandle()).offset(0).range(objectUbo.getSize());
            descriptorWrites.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(objectMaterialSet).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(objectBI);

            // Material UBO
            VkDescriptorBufferInfo.Buffer materialBI = VkDescriptorBufferInfo.calloc(1, stack).buffer(materialUbo.getBufferHandle()).offset(0).range(materialUbo.getSize());
            descriptorWrites.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(objectMaterialSet).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(materialBI);

            // Diffuse Texture
            VulkanTexture diffuseTex = material.diffuseTexture != null ? material.diffuseTexture : this.defaultDiffuseTexture;
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(diffuseTex.getImageViewHandle()).sampler(diffuseTex.getSamplerHandle());
            descriptorWrites.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(objectMaterialSet).dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imageInfo);

            vkUpdateDescriptorSets(rawDevice, descriptorWrites, null);

            LongBuffer pSets = stack.longs(globalSet, objectMaterialSet);
            vkCmdBindDescriptorSets(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, currentPipelineLayout, 0, pSets, null);
        }

        // --- Bind Buffers and Draw ---
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Get the VulkanBuffer object first, then its handle
            LongBuffer pBuffers = stack.longs(mesh.getVertexBuffer().getBufferHandle());
            LongBuffer pOffsets = stack.longs(0);
            vkCmdBindVertexBuffers(currentCommandBuffer, 0, pBuffers, pOffsets);

            if (mesh.isIndexed()) {
                // Get the VulkanBuffer object first, then its handle
                vkCmdBindIndexBuffer(currentCommandBuffer, mesh.getIndexBuffer().getBufferHandle(), 0, VK_INDEX_TYPE_UINT16);
                // Use the 'size' and 'offset' fields from the corrected VulkanMeshPart
                vkCmdDrawIndexed(currentCommandBuffer, meshPart.size, 1, meshPart.offset, 0, 0);
                numVertices += meshPart.size;
            } else {
                // Use the 'size' and 'offset' fields from the corrected VulkanMeshPart
                vkCmdDraw(currentCommandBuffer, meshPart.size, 1, meshPart.offset, 0);
                numVertices += meshPart.size;
            }
            renderCalls++;
        }
    }

    public void end() {
        this.camera = null;
        this.currentCommandBuffer = null;
        if (DEBUG) Gdx.app.debug(TAG, "End. Total render calls this batch cycle: " + renderCalls);
    }

    /**
     * Resets the profiling counters. Called by the test.
     */
    public void resetCounts() {
        renderCalls = 0;
        shaderSwitches = 0;
        numVertices = 0;
    }

    @Override
    public void dispose() {
        if (DEBUG) Gdx.app.log(TAG, "Disposing ModelBatchVulkan...");
        if (vulkanDevice != null && vulkanDevice.getLogicalDevice() != null) {
            vkDeviceWaitIdle(vulkanDevice.getLogicalDevice());
        }

        if (globalUbo != null) globalUbo.dispose();
        if (objectUbo != null) objectUbo.dispose();
        if (materialUbo != null) materialUbo.dispose();
        if (defaultDiffuseTexture != null) defaultDiffuseTexture.dispose();

        if (descriptorManager != null) {
            ArrayList<Long> allSetsToFree = new ArrayList<>();
            for (long setHandle : perFrameGlobalDescriptorSets) if (setHandle != VK_NULL_HANDLE) allSetsToFree.add(setHandle);
            for (long setHandle : perFrameObjectMaterialDescriptorSets) if (setHandle != VK_NULL_HANDLE) allSetsToFree.add(setHandle);
            if (!allSetsToFree.isEmpty()) descriptorManager.freeSets(allSetsToFree);
        }
    }
}
