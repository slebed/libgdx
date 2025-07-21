package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Collections;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A simple shader for rendering geometry with vertex colors, but no textures or lighting.
 * Used for debug rendering, like coordinate axes. This version has a begin/render/end API.
 */
public class SimpleColorShader implements Disposable {

    private final VulkanApplication vulkanApplication;
    private final VulkanGraphics vulkanGraphics;
    private final VulkanDevice vulkanDevice;
    private final VulkanShaderManager shaderManager;
    private final VulkanPipelineManager pipelineManager;
    private final VulkanDescriptorManager descriptorManager;

    private final VulkanShaderPipelineBundle pipelineBundle;
    private long descriptorSet0 = VK_NULL_HANDLE;

    private final VulkanBuffer transformUboBuffer;
    private final ByteBuffer transformUboMapped;

    private static final String VERTEX_SHADER_PATH = "data/vulkan/shaders/vertex_color.vert.glsl";
    private static final String FRAGMENT_SHADER_PATH = "data/vulkan/shaders/vertex_color.frag.glsl";

    public SimpleColorShader(VulkanVertexAttributes vertexAttributes, long compatibleRenderPass) {
        this.vulkanApplication = (VulkanApplication) Gdx.app;
        this.vulkanGraphics = (VulkanGraphics) Gdx.graphics;
        this.vulkanDevice = vulkanApplication.getVulkanDevice();
        this.shaderManager = vulkanApplication.getShaderManager();
        this.pipelineManager = vulkanApplication.getPipelineManager();
        this.descriptorManager = vulkanApplication.getDescriptorManager();

        transformUboBuffer = VulkanResourceUtil.createManagedBuffer(vulkanApplication.getVmaAllocator(), 3 * 16 * Float.BYTES,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        transformUboMapped = transformUboBuffer.getMappedByteBuffer();

        pipelineBundle = createPipelineBundle(vertexAttributes, compatibleRenderPass);
        allocateDescriptorSet();
        updateStaticDescriptorBindings();
    }

    private VulkanShaderPipelineBundle createPipelineBundle(VulkanVertexAttributes vertexAttributes, long compatibleRenderPass) {
        VulkanShaderPipelineBundle.Config config = new VulkanShaderPipelineBundle.Config();
        config.vertexShaderFile = Gdx.files.internal(VERTEX_SHADER_PATH);
        config.fragmentShaderFile = Gdx.files.internal(FRAGMENT_SHADER_PATH);
        config.vertexAttributes = vertexAttributes;
        config.compatibleRenderPass = compatibleRenderPass;

        config.descriptorSet0_Bindings = new VulkanShaderPipelineBundle.Config.BindingConfigPojo[1];
        config.descriptorSet0_Bindings[0] = new VulkanShaderPipelineBundle.Config.BindingConfigPojo(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_VERTEX_BIT);

        config.primitiveTopology = VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
        config.depthTestEnable = true;
        config.depthWriteEnable = true;
        config.cullMode = VK_CULL_MODE_NONE;

        return new VulkanShaderPipelineBundle(vulkanDevice, shaderManager, pipelineManager, config);
    }

    private void allocateDescriptorSet() {
        long dslSet0Handle = this.pipelineBundle.getDescriptorSetLayoutHandle(0);
        if (dslSet0Handle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("DSL handle for Set 0 is null in SimpleColorShader.");
        }
        this.descriptorSet0 = descriptorManager.allocateSet(dslSet0Handle);
    }

    private void updateStaticDescriptorBindings() {
        VulkanDescriptorManager.updateUniformBuffer(vulkanDevice.getLogicalDevice(), descriptorSet0, 0, transformUboBuffer);
    }

    public VulkanShaderPipelineBundle getPipelineBundle() {
        return pipelineBundle;
    }

    public void begin(Camera camera) {
        VkCommandBuffer commandBuffer = vulkanGraphics.getCurrentCommandBuffer();
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineBundle.getGraphicsPipeline());

        FloatBuffer fb = transformUboMapped.asFloatBuffer();
        fb.position(16); // Offset to View matrix
        fb.put(camera.view.val);
        fb.position(32); // Offset to Proj matrix
        fb.put(camera.projection.val);
        Vma.vmaFlushAllocation(vulkanApplication.getVmaAllocator(), transformUboBuffer.getAllocationHandle(), 16 * Float.BYTES, 2 * 16 * Float.BYTES);
    }

    public void render(VulkanModelInstance instance) {
        VkCommandBuffer commandBuffer = vulkanGraphics.getCurrentCommandBuffer();

        FloatBuffer fb = transformUboMapped.asFloatBuffer();
        fb.position(0);
        fb.put(instance.transform.val);
        Vma.vmaFlushAllocation(vulkanApplication.getVmaAllocator(), transformUboBuffer.getAllocationHandle(), 0, 16 * Float.BYTES);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineBundle.getPipelineLayout(), 0, stack.longs(descriptorSet0), null);
        }

        for (VulkanMeshPart part : instance.model.meshParts) {
            VulkanMesh mesh = part.mesh;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(mesh.getVertexBuffer().getBufferHandle()), stack.longs(0));
                vkCmdBindIndexBuffer(commandBuffer, mesh.getIndexBuffer().getBufferHandle(), 0, VK_INDEX_TYPE_UINT16);
                vkCmdDrawIndexed(commandBuffer, part.size, 1, part.offset, 0, 0);
            }
        }
    }

    public void end() {
        // No-op
    }

    @Override
    public void dispose() {
        if (pipelineBundle != null) pipelineBundle.dispose();
        if (transformUboBuffer != null) transformUboBuffer.dispose();
        if (descriptorSet0 != VK_NULL_HANDLE && descriptorManager != null) {
            descriptorManager.freeSets(Collections.singletonList(descriptorSet0));
        }
    }
}
