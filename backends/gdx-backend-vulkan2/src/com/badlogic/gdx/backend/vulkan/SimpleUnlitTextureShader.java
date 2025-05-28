package com.badlogic.gdx.backend.vulkan; // Assuming this is the correct package based on your test

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
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

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_CPU_TO_GPU;

public class SimpleUnlitTextureShader implements Disposable {
    private static final String TAG = "SimpleUnlitTextureShader";

    private final VulkanDevice vulkanDevice;
    private VulkanShaderManager shaderManager; // This instance will be created and owned by this class
    private final VulkanPipelineManager pipelineManager;
    private final VulkanDescriptorManager descriptorManager;
    private final VulkanApplication vulkanApplication;
    private final VulkanGraphics vulkanGraphics;


    private VulkanShaderPipelineBundle pipelineBundle;
    private long descriptorSet0 = VK_NULL_HANDLE;

    private VulkanBuffer transformUboBuffer;
    private ByteBuffer transformUboMapped;

    private VulkanBuffer materialUboBuffer;
    private ByteBuffer materialUboMapped;

    private VulkanTexture diffuseTexture;
    private boolean textureBindingNeedsUpdate = true; // Flag specifically for texture binding

    private static final String VERTEX_SHADER_PATH = "data/vulkan/shaders/textured_material_mesh.vert.glsl";
    private static final String FRAGMENT_SHADER_PATH = "data/vulkan/shaders/textured_material_mesh.frag.glsl";
    private final int materialUboSize;

    public SimpleUnlitTextureShader(VulkanVertexAttributes vertexAttributes) {
        this((VulkanGraphics) Gdx.graphics, vertexAttributes, ((VulkanGraphics) Gdx.graphics).getSwapchainRenderPass());
    }

    public SimpleUnlitTextureShader(VulkanVertexAttributes vertexAttributes, long compatibleRenderPass) {
        this((VulkanGraphics) Gdx.graphics, vertexAttributes, compatibleRenderPass);
    }

    // Main constructor
    public SimpleUnlitTextureShader(VulkanGraphics graphics, VulkanVertexAttributes vertexAttributes, long compatibleRenderPass) {
        this.vulkanGraphics = graphics;
        if (this.vulkanGraphics == null) {
            throw new GdxRuntimeException("VulkanGraphics instance is null.");
        }

        this.vulkanApplication = (VulkanApplication) Gdx.app;
        if (this.vulkanApplication == null) {
            throw new GdxRuntimeException("Gdx.app is not a VulkanApplication or is null.");
        }

        this.vulkanDevice = vulkanApplication.getVulkanDevice();
        this.shaderManager = new VulkanShaderManager();
        this.pipelineManager = vulkanApplication.getPipelineManager();
        this.descriptorManager = vulkanApplication.getDescriptorManager();

        if (vulkanDevice == null || shaderManager == null || pipelineManager == null || descriptorManager == null) {
            throw new GdxRuntimeException("One or more Vulkan managers are null, check VulkanApplication setup and SimpleUnlitTextureShader initialization.");
        }
        this.materialUboSize = 96;

        createUbos();
        createPipelineBundle(vertexAttributes, compatibleRenderPass);
        allocateDescriptorSet();
        updateStaticDescriptorBindings();
    }

    private void createUbos() {
        long transformUboSize = 3 * 16 * Float.BYTES;
        this.transformUboBuffer = VulkanResourceUtil.createManagedBuffer(vulkanApplication.getVmaAllocator(), transformUboSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        if (this.transformUboBuffer.getMappedByteBuffer() == null) {
            throw new GdxRuntimeException("Failed to map transform UBO for SimpleUnlitTextureShader");
        }
        this.transformUboMapped = this.transformUboBuffer.getMappedByteBuffer();

        this.materialUboBuffer = VulkanResourceUtil.createManagedBuffer(vulkanApplication.getVmaAllocator(), this.materialUboSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        if (this.materialUboBuffer.getMappedByteBuffer() == null) {
            throw new GdxRuntimeException("Failed to map material UBO for SimpleUnlitTextureShader");
        }
        this.materialUboMapped = this.materialUboBuffer.getMappedByteBuffer();
    }

    private void createPipelineBundle(VulkanVertexAttributes vertexAttributes, long compatibleRenderPass) {
        VulkanShaderPipelineBundle.Config bundleConfig = new VulkanShaderPipelineBundle.Config();
        bundleConfig.vertexShaderFile = Gdx.files.internal(VERTEX_SHADER_PATH);
        bundleConfig.fragmentShaderFile = Gdx.files.internal(FRAGMENT_SHADER_PATH);
        bundleConfig.vertexAttributes = vertexAttributes;
        bundleConfig.compatibleRenderPass = compatibleRenderPass;

        bundleConfig.descriptorSet0_Bindings = new VulkanShaderPipelineBundle.Config.BindingConfigPojo[3];
        bundleConfig.descriptorSet0_Bindings[0] = new VulkanShaderPipelineBundle.Config.BindingConfigPojo();
        bundleConfig.descriptorSet0_Bindings[0].binding = 0;
        bundleConfig.descriptorSet0_Bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        bundleConfig.descriptorSet0_Bindings[0].descriptorCount = 1;
        bundleConfig.descriptorSet0_Bindings[0].stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
        bundleConfig.descriptorSet0_Bindings[1] = new VulkanShaderPipelineBundle.Config.BindingConfigPojo();
        bundleConfig.descriptorSet0_Bindings[1].binding = 1;
        bundleConfig.descriptorSet0_Bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        bundleConfig.descriptorSet0_Bindings[1].descriptorCount = 1;
        bundleConfig.descriptorSet0_Bindings[1].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        bundleConfig.descriptorSet0_Bindings[2] = new VulkanShaderPipelineBundle.Config.BindingConfigPojo();
        bundleConfig.descriptorSet0_Bindings[2].binding = 2;
        bundleConfig.descriptorSet0_Bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bundleConfig.descriptorSet0_Bindings[2].descriptorCount = 1;
        bundleConfig.descriptorSet0_Bindings[2].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        bundleConfig.descriptorSet1_Bindings = null;
        bundleConfig.depthTestEnable = true;
        bundleConfig.depthWriteEnable = true;
        bundleConfig.cullMode = VK_CULL_MODE_BACK_BIT;
        bundleConfig.frontFace = VK_FRONT_FACE_CLOCKWISE;

        this.pipelineBundle = new VulkanShaderPipelineBundle(vulkanDevice, shaderManager, pipelineManager, bundleConfig);
    }

    private void allocateDescriptorSet() {
        long dslSet0Handle = this.pipelineBundle.getDescriptorSetLayoutHandle(0);
        if (dslSet0Handle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("DSL handle for Set 0 is null in SimpleUnlitTextureShader.");
        }
        this.descriptorSet0 = descriptorManager.allocateSet(dslSet0Handle);
        if (this.descriptorSet0 == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to allocate descriptorSet0 in SimpleUnlitTextureShader.");
        }
    }

    private void updateStaticDescriptorBindings() {
        if (descriptorSet0 == VK_NULL_HANDLE) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            VkDescriptorBufferInfo.Buffer transformBI = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(transformUboBuffer.getBufferHandle())
                    .offset(0)
                    .range(transformUboBuffer.getSize());
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet0)
                    .dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(transformBI);
            VkDescriptorBufferInfo.Buffer materialBI = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(materialUboBuffer.getBufferHandle())
                    .offset(0)
                    .range(materialUboBuffer.getSize());
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet0)
                    .dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(materialBI);
            vkUpdateDescriptorSets(vulkanDevice.getLogicalDevice(), writes, null);
        }
        textureBindingNeedsUpdate = true;
    }

    public void setDiffuseTexture(VulkanTexture texture) {
        if (this.diffuseTexture != texture) {
            this.diffuseTexture = texture;
            this.textureBindingNeedsUpdate = true;
        }
    }

    private void updateTextureBindingInDescriptorSet() {
        if (descriptorSet0 == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Cannot update texture binding, descriptorSet0 is null.");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            if (diffuseTexture != null && diffuseTexture.getImageViewHandle() != VK_NULL_HANDLE && diffuseTexture.getSamplerHandle() != VK_NULL_HANDLE) {
                imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .imageView(diffuseTexture.getImageViewHandle())
                        .sampler(diffuseTexture.getSamplerHandle());
            } else {
                imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .imageView(VK_NULL_HANDLE)
                        .sampler(VK_NULL_HANDLE);
                Gdx.app.debug(TAG, "Updating texture descriptor with NULL handles as diffuseTexture is not fully available.");
            }
            write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet0)
                    .dstBinding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo);
            vkUpdateDescriptorSets(vulkanDevice.getLogicalDevice(), write, null);
        }
        textureBindingNeedsUpdate = false;
    }

    public void updateTransformUBO(Matrix4 model, Matrix4 view, Matrix4 proj) {
        transformUboMapped.position(0);
        FloatBuffer fb = transformUboMapped.asFloatBuffer();
        fb.put(model.val);
        fb.position(16);
        fb.put(view.val);
        fb.position(32);
        fb.put(proj.val);
        Vma.vmaFlushAllocation(vulkanApplication.getVmaAllocator(), transformUboBuffer.getAllocationHandle(), 0, transformUboBuffer.getSize());
    }

    public void updateMaterialUBO(VulkanMaterial material) {
        materialUboMapped.position(0);
        Color color = material.getDiffuseColor();
        if (color == null) color = Color.WHITE;

        materialUboMapped.putFloat(color.r);
        materialUboMapped.putFloat(color.g);
        materialUboMapped.putFloat(color.b);
        materialUboMapped.putFloat(color.a);
        materialUboMapped.putFloat(this.diffuseTexture != null && this.diffuseTexture.getImageViewHandle() != VK_NULL_HANDLE ? 1.0f : 0.0f);
        materialUboMapped.putFloat(material.getOpacity());
        materialUboMapped.position(24);
        while (materialUboMapped.hasRemaining()) {
            materialUboMapped.put((byte) 0);
        }
        materialUboMapped.position(0);
        Vma.vmaFlushAllocation(vulkanApplication.getVmaAllocator(), materialUboBuffer.getAllocationHandle(), 0, materialUboBuffer.getSize());
    }

    public void render(VulkanMesh mesh, Matrix4 modelMatrix, Matrix4 viewMatrix, Matrix4 projectionMatrix, VulkanMaterial material) {
        if (vulkanGraphics == null || pipelineBundle == null || mesh == null) {
            Gdx.app.error(TAG, "Cannot render, essential resources missing (graphics, pipeline, or mesh).");
            return;
        }

        VkCommandBuffer commandBuffer = vulkanGraphics.getCurrentCommandBuffer();
        if (commandBuffer == null) {
            Gdx.app.error(TAG, "No command buffer available for rendering.");
            return;
        }

        // Update UBOs
        updateTransformUBO(modelMatrix, viewMatrix, projectionMatrix);
        updateMaterialUBO(material); // Assumes material data might change per-render

        // Update texture binding in descriptor set if it changed
        if (textureBindingNeedsUpdate) {
            // This update should ideally happen outside of per-object render if possible,
            // or if the texture is truly dynamic per object.
            // For now, it's here for simplicity if texture could change.
            updateTextureBindingInDescriptorSet();
        }

        // Set viewport and scissor (could be set by a higher-level renderer once per frame)
        // For this self-contained shader, we do it here.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0f).y(0f)
                    .width(Gdx.graphics.getWidth()) // Use Gdx.graphics for current dimensions
                    .height(Gdx.graphics.getHeight())
                    .minDepth(0f).maxDepth(1f);
            vkCmdSetViewport(commandBuffer, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack)
                    .offset(VkOffset2D.calloc(stack).set(0, 0))
                    .extent(VkExtent2D.calloc(stack).set(Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
            vkCmdSetScissor(commandBuffer, 0, scissor);
        }

        // Bind pipeline and descriptor sets
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineBundle.getGraphicsPipeline());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineBundle.getPipelineLayout(), 0, stack.longs(descriptorSet0), null);
        }

        // Bind mesh vertex and index buffers
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pVertexBuffers = stack.longs(mesh.getVertexBufferHandle());
            LongBuffer pOffsets = stack.longs(0L);
            vkCmdBindVertexBuffers(commandBuffer, 0, pVertexBuffers, pOffsets);
        }
        if (mesh.isIndexed()) {
            vkCmdBindIndexBuffer(commandBuffer, mesh.getIndexBufferHandle(), 0, VK_INDEX_TYPE_UINT16);
        }

        // Issue draw call
        if (mesh.isIndexed()) {
            vkCmdDrawIndexed(commandBuffer, mesh.getNumIndices(), 1, 0, 0, 0);
        } else {
            vkCmdDraw(commandBuffer, mesh.getNumVertices(), 1, 0, 0);
        }
    }


    @Override
    public void dispose() {
        if (vulkanDevice != null && vulkanDevice.isDeviceAvailable()) {
            try {
                vkDeviceWaitIdle(vulkanDevice.getLogicalDevice());
            } catch (Exception e) {
                Gdx.app.error(TAG, "Error during vkDeviceWaitIdle in SimpleUnlitTextureShader.dispose: " + e.getMessage());
            }
        }

        if (pipelineBundle != null) {
            pipelineBundle.dispose();
            pipelineBundle = null;
        }
        if (transformUboBuffer != null) {
            transformUboBuffer.dispose();
            transformUboBuffer = null;
        }
        if (materialUboBuffer != null) {
            materialUboBuffer.dispose();
            materialUboBuffer = null;
        }

        if (descriptorSet0 != VK_NULL_HANDLE && descriptorManager != null && vulkanDevice != null && vulkanDevice.isDeviceAvailable()) {
            descriptorManager.freeSets(Collections.singletonList(descriptorSet0));
            descriptorSet0 = VK_NULL_HANDLE;
        }
        /*if (descriptorSet0 != VK_NULL_HANDLE && descriptorManager != null &&
                vulkanDevice.isDeviceAvailable() && descriptorManager.getRawDeviceHandle() != VK_NULL_HANDLE) {
            descriptorManager.freeSets(Collections.singletonList(descriptorSet0));
            descriptorSet0 = VK_NULL_HANDLE;
        }*/

        if (shaderManager != null) {
            shaderManager.dispose();
            shaderManager = null;
        }

        diffuseTexture = null;
    }
}