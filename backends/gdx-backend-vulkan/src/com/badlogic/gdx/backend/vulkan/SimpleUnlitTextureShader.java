package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Collections;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class SimpleUnlitTextureShader implements Disposable {
    private final VulkanGraphics vulkanGraphics;
    private final VulkanDevice vulkanDevice;
    private final long vmaAllocator;
    private final VulkanDescriptorManager descriptorManager;

     final VulkanShaderPipelineBundle pipelineBundle;

    // UBOs are now split to match descriptor sets
    private final VulkanBuffer cameraUbo;       // For Set 0
    private final VulkanBuffer modelTransformUbo; // For Set 1
    private final VulkanBuffer materialUbo;       // For Set 1

    private final ByteBuffer cameraUboMapped;
    private final ByteBuffer modelTransformUboMapped;
    private final ByteBuffer materialUboMapped;

    // We now manage two descriptor sets
    private long descriptorSet0; // Per-Frame (Camera)
    private long descriptorSet1; // Per-Object (Model, Material, Texture)

    private VulkanTexture currentTexture = null;
    private boolean textureBindingNeedsUpdate = true;

    private static final String VERTEX_SHADER_PATH = "data/vulkan/shaders/textured_material_mesh.vert.glsl";
    private static final String FRAGMENT_SHADER_PATH = "data/vulkan/shaders/textured_material_mesh.frag.glsl";

    public SimpleUnlitTextureShader(VulkanVertexAttributes vertexAttributes) {
        this(vertexAttributes, ((VulkanGraphics) Gdx.graphics).getSwapchainRenderPass());
    }

    public SimpleUnlitTextureShader(VulkanVertexAttributes vertexAttributes, long compatibleRenderPass) {
        VulkanApplication app = (VulkanApplication) Gdx.app;
        this.vulkanGraphics = (VulkanGraphics) Gdx.graphics;
        this.vulkanDevice = app.getVulkanDevice();
        this.vmaAllocator = app.getVmaAllocator();
        this.descriptorManager = app.getDescriptorManager();

        // Create UBOs
        cameraUbo = createUbo(2 * 16 * Float.BYTES, "Camera UBO");
        cameraUboMapped = cameraUbo.getMappedByteBuffer();

        modelTransformUbo = createUbo(16 * Float.BYTES, "Model Transform UBO");
        modelTransformUboMapped = modelTransformUbo.getMappedByteBuffer();

        materialUbo = createUbo(256, "Material UBO"); // 256 bytes for safety
        materialUboMapped = materialUbo.getMappedByteBuffer();

        // Create the pipeline bundle with the corrected two-set configuration
        this.pipelineBundle = createPipelineBundle(vertexAttributes, compatibleRenderPass, app.getShaderManager(), app.getPipelineManager());

        allocateDescriptorSets();
        updateStaticDescriptorBindings();
    }

    private VulkanBuffer createUbo(long size, String name) {
        VulkanBuffer ubo = VulkanResourceUtil.createManagedBuffer(vmaAllocator, size,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        if (ubo.getMappedByteBuffer() == null) {
            throw new GdxRuntimeException("Failed to map " + name);
        }
        return ubo;
    }

    private VulkanShaderPipelineBundle createPipelineBundle(VulkanVertexAttributes vertexAttributes, long compatibleRenderPass, VulkanShaderManager sm, VulkanPipelineManager pm) {
        VulkanShaderPipelineBundle.Config config = new VulkanShaderPipelineBundle.Config();
        config.vertexShaderFile = Gdx.files.internal(VERTEX_SHADER_PATH);
        config.fragmentShaderFile = Gdx.files.internal(FRAGMENT_SHADER_PATH);
        config.vertexAttributes = vertexAttributes;
        config.compatibleRenderPass = compatibleRenderPass;

        // Define bindings for Set 0 (Per-Frame)
        config.descriptorSet0_Bindings = new VulkanShaderPipelineBundle.Config.BindingConfigPojo[1];
        config.descriptorSet0_Bindings[0] = new VulkanShaderPipelineBundle.Config.BindingConfigPojo(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_VERTEX_BIT);

        // Define bindings for Set 1 (Per-Object)
        config.descriptorSet1_Bindings = new VulkanShaderPipelineBundle.Config.BindingConfigPojo[3];
        config.descriptorSet1_Bindings[0] = new VulkanShaderPipelineBundle.Config.BindingConfigPojo(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_VERTEX_BIT);
        config.descriptorSet1_Bindings[1] = new VulkanShaderPipelineBundle.Config.BindingConfigPojo(1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_FRAGMENT_BIT);
        config.descriptorSet1_Bindings[2] = new VulkanShaderPipelineBundle.Config.BindingConfigPojo(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT);

        config.depthTestEnable = true;
        config.depthWriteEnable = true;
        config.cullMode = VK_CULL_MODE_BACK_BIT;
        config.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE; // Use standard CCW winding

        return new VulkanShaderPipelineBundle(vulkanDevice, sm, pm, config);
    }

    private void allocateDescriptorSets() {
        this.descriptorSet0 = descriptorManager.allocateSet(pipelineBundle.getDescriptorSetLayoutHandle(0));
        this.descriptorSet1 = descriptorManager.allocateSet(pipelineBundle.getDescriptorSetLayoutHandle(1));
    }

    private void updateStaticDescriptorBindings() {
        // Statically bind UBOs to their respective sets
        VulkanDescriptorManager.updateUniformBuffer(vulkanDevice.getLogicalDevice(), descriptorSet0, 0, cameraUbo);
        VulkanDescriptorManager.updateUniformBuffer(vulkanDevice.getLogicalDevice(), descriptorSet1, 0, modelTransformUbo);
        VulkanDescriptorManager.updateUniformBuffer(vulkanDevice.getLogicalDevice(), descriptorSet1, 1, materialUbo);
        textureBindingNeedsUpdate = true; // Texture will be bound on first render
    }

    public void begin(Camera camera) {
        VkCommandBuffer commandBuffer = vulkanGraphics.getCurrentCommandBuffer();
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineBundle.getGraphicsPipeline());

        // Update Camera UBO (View & Projection matrices)
        FloatBuffer fb = cameraUboMapped.asFloatBuffer();
        fb.position(0);
        fb.put(camera.view.val);
        fb.position(16);
        fb.put(camera.projection.val);
        Vma.vmaFlushAllocation(vmaAllocator, cameraUbo.getAllocationHandle(), 0, cameraUbo.getSize());

        // Bind the Per-Frame descriptor set (Set 0)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineBundle.getPipelineLayout(), 0, stack.longs(descriptorSet0), null);
        }
    }

    public void render(VulkanMesh mesh, VulkanMaterial material, Matrix4 worldTransform) {
        VkCommandBuffer commandBuffer = vulkanGraphics.getCurrentCommandBuffer();

        // Update Model Transform UBO
        FloatBuffer modelFb = modelTransformUboMapped.asFloatBuffer();
        modelFb.position(0);
        modelFb.put(worldTransform.val);
        Vma.vmaFlushAllocation(vmaAllocator, modelTransformUbo.getAllocationHandle(), 0, modelTransformUbo.getSize());

        // Update Material UBO
        materialUboMapped.position(0);
        material.writeToUbo(materialUboMapped, 0);
        Vma.vmaFlushAllocation(vmaAllocator, materialUbo.getAllocationHandle(), 0, materialUbo.getSize());

        // Update texture binding if it has changed
        if (currentTexture != material.getDiffuseTexture()) {
            currentTexture = material.getDiffuseTexture();
            textureBindingNeedsUpdate = true;
        }
        if (textureBindingNeedsUpdate && currentTexture != null) {
            VulkanDescriptorManager.updateCombinedImageSampler(vulkanDevice.getLogicalDevice(), descriptorSet1, 2, currentTexture);
            textureBindingNeedsUpdate = false;
        }

        // Bind the Per-Object descriptor set (Set 1)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineBundle.getPipelineLayout(), 1, stack.longs(descriptorSet1), null);
        }

        // Bind mesh buffers and draw
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pVertexBuffers = stack.longs(mesh.getVertexBuffer().getBufferHandle());
            LongBuffer pOffsets = stack.longs(0L);
            vkCmdBindVertexBuffers(commandBuffer, 0, pVertexBuffers, pOffsets);

            if (mesh.isIndexed()) {
                vkCmdBindIndexBuffer(commandBuffer, mesh.getIndexBuffer().getBufferHandle(), 0, VK_INDEX_TYPE_UINT16);
                vkCmdDrawIndexed(commandBuffer, mesh.getNumIndices(), 1, 0, 0, 0);
            } else {
                vkCmdDraw(commandBuffer, mesh.getNumVertices(), 1, 0, 0);
            }
        }
    }

    public void end() {
        // No-op for this simple shader
    }

    @Override
    public void dispose() {
        if (vulkanDevice != null && vulkanDevice.isDeviceAvailable()) {
            vkDeviceWaitIdle(vulkanDevice.getLogicalDevice());
        }

        if (pipelineBundle != null) pipelineBundle.dispose();
        if (cameraUbo != null) cameraUbo.dispose();
        if (modelTransformUbo != null) modelTransformUbo.dispose();
        if (materialUbo != null) materialUbo.dispose();

        if (descriptorManager != null) {
            if (descriptorSet0 != VK_NULL_HANDLE) descriptorManager.freeSets(Collections.singletonList(descriptorSet0));
            if (descriptorSet1 != VK_NULL_HANDLE) descriptorManager.freeSets(Collections.singletonList(descriptorSet1));
        }
    }

    // This method is now only needed by VulkanModel
    public VulkanShaderPipelineBundle getPipelineBundle() {
        return pipelineBundle;
    }
}