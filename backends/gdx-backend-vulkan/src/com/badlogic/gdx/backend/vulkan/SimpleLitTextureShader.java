package com.badlogic.gdx.backend.vulkan; // Assuming this is the correct package

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3; // For light direction
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

public class SimpleLitTextureShader implements Disposable {
    private static final String TAG = "SimpleLitTextureShader";

    private final VulkanDevice vulkanDevice;
    private VulkanShaderManager shaderManager;
    private final VulkanPipelineManager pipelineManager;
    private final VulkanDescriptorManager descriptorManager;
    private final VulkanApplication vulkanApplication;
    private final VulkanGraphics vulkanGraphics;

    private VulkanShaderPipelineBundle pipelineBundle;
    private long descriptorSet0 = VK_NULL_HANDLE;

    // UBOs
    private VulkanBuffer transformUboBuffer;
    private ByteBuffer transformUboMapped;

    private VulkanBuffer materialUboBuffer;
    private ByteBuffer materialUboMapped;

    private VulkanBuffer lightingUboBuffer;
    private ByteBuffer lightingUboMapped;

    private VulkanTexture diffuseTexture; // This shader's currently bound diffuse texture
    private boolean textureBindingNeedsUpdate = true;

    private static final String VERTEX_SHADER_PATH = "data/vulkan/shaders/lit_textured_material_mesh.vert.glsl";
    private static final String FRAGMENT_SHADER_PATH = "data/vulkan/shaders/lit_textured_material_mesh.frag.glsl";

    private final int materialUboSize;
    private final int lightingUboSize;

    private final Vector3 defaultLightDirection = new Vector3(-0.5f, -0.8f, -0.2f).nor();
    private final Color defaultLightColor = new Color(0.8f, 0.8f, 0.8f, 1.0f);
    private final Color defaultAmbientLightColor = new Color(0.2f, 0.2f, 0.2f, 1.0f);


    public SimpleLitTextureShader(VulkanVertexAttributes vertexAttributes) {
        this((VulkanGraphics) Gdx.graphics, vertexAttributes, ((VulkanGraphics) Gdx.graphics).getSwapchainRenderPass());
    }

    public SimpleLitTextureShader(VulkanVertexAttributes vertexAttributes, long compatibleRenderPass) {
        this((VulkanGraphics) Gdx.graphics, vertexAttributes, compatibleRenderPass);
    }

    public SimpleLitTextureShader(VulkanGraphics graphics, VulkanVertexAttributes vertexAttributes, long compatibleRenderPass) {
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
            throw new GdxRuntimeException("One or more Vulkan managers are null.");
        }

        // Material UBO: diffuseColor (vec4), specularColorAndShininess (vec4), opacity (float), hasDiffuseTexture (float), padding (vec2)
        // Total: 16 + 16 + 4 + 4 + 8 (padding) = 48 bytes
        this.materialUboSize = 3 * 16; // 48 bytes
        this.lightingUboSize = 3 * 16; // 48 bytes (Ambient vec4, DirLight.direction vec4, DirLight.color vec4)

        createUbos();
        setAmbientLight(defaultAmbientLightColor.r, defaultAmbientLightColor.g, defaultAmbientLightColor.b, defaultAmbientLightColor.a);
        setDirectionalLight(0, defaultLightDirection, defaultLightColor.r, defaultLightColor.g, defaultLightColor.b, defaultLightColor.a);

        createPipelineBundle(vertexAttributes, compatibleRenderPass);
        allocateDescriptorSet();
        updateStaticDescriptorBindings();
    }

    private void createUbos() {
        long transformUboSize = 3 * 16 * Float.BYTES;
        this.transformUboBuffer = VulkanResourceUtil.createManagedBuffer(vulkanApplication.getVmaAllocator(), transformUboSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        this.transformUboMapped = this.transformUboBuffer.getMappedByteBuffer();

        this.materialUboBuffer = VulkanResourceUtil.createManagedBuffer(vulkanApplication.getVmaAllocator(), this.materialUboSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        this.materialUboMapped = this.materialUboBuffer.getMappedByteBuffer();

        this.lightingUboBuffer = VulkanResourceUtil.createManagedBuffer(vulkanApplication.getVmaAllocator(), this.lightingUboSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        this.lightingUboMapped = this.lightingUboBuffer.getMappedByteBuffer();

        if (this.transformUboMapped == null || this.materialUboMapped == null || this.lightingUboMapped == null) {
            throw new GdxRuntimeException("Failed to map one or more UBOs for SimpleLitTextureShader");
        }
    }

    private void createPipelineBundle(VulkanVertexAttributes vertexAttributes, long compatibleRenderPass) {
        VulkanShaderPipelineBundle.Config bundleConfig = new VulkanShaderPipelineBundle.Config();
        bundleConfig.vertexShaderFile = Gdx.files.internal(VERTEX_SHADER_PATH);
        bundleConfig.fragmentShaderFile = Gdx.files.internal(FRAGMENT_SHADER_PATH);
        bundleConfig.vertexAttributes = vertexAttributes;
        bundleConfig.compatibleRenderPass = compatibleRenderPass;

        bundleConfig.descriptorSet0_Bindings = new VulkanShaderPipelineBundle.Config.BindingConfigPojo[4];

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

        bundleConfig.descriptorSet0_Bindings[3] = new VulkanShaderPipelineBundle.Config.BindingConfigPojo();
        bundleConfig.descriptorSet0_Bindings[3].binding = 3;
        bundleConfig.descriptorSet0_Bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        bundleConfig.descriptorSet0_Bindings[3].descriptorCount = 1;
        bundleConfig.descriptorSet0_Bindings[3].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_VERTEX_BIT;

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
            throw new GdxRuntimeException("DSL handle for Set 0 is null in SimpleLitTextureShader.");
        }
        this.descriptorSet0 = descriptorManager.allocateSet(dslSet0Handle);
        if (this.descriptorSet0 == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to allocate descriptorSet0 in SimpleLitTextureShader.");
        }
    }

    private void updateStaticDescriptorBindings() {
        if (descriptorSet0 == VK_NULL_HANDLE) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);

            VkDescriptorBufferInfo.Buffer transformBI = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(transformUboBuffer.getBufferHandle()).offset(0).range(transformUboBuffer.getSize());
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet0)
                    .dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(transformBI);

            VkDescriptorBufferInfo.Buffer materialBI = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(materialUboBuffer.getBufferHandle()).offset(0).range(materialUboBuffer.getSize());
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet0)
                    .dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(materialBI);

            VkDescriptorBufferInfo.Buffer lightingBI = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(lightingUboBuffer.getBufferHandle()).offset(0).range(lightingUboBuffer.getSize());
            writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet0)
                    .dstBinding(3).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(lightingBI);

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
        fb.put(model.val); fb.position(16);
        fb.put(view.val); fb.position(32);
        fb.put(proj.val);
        Vma.vmaFlushAllocation(vulkanApplication.getVmaAllocator(), transformUboBuffer.getAllocationHandle(), 0, transformUboBuffer.getSize());
    }

    public void updateMaterialUBO(VulkanMaterial material) {
        materialUboMapped.position(0);

        Color diffuse = material.getDiffuseColor();
        if (diffuse == null) diffuse = Color.WHITE;
        materialUboMapped.putFloat(diffuse.r); // u_diffuseColor.r
        materialUboMapped.putFloat(diffuse.g); // u_diffuseColor.g
        materialUboMapped.putFloat(diffuse.b); // u_diffuseColor.b
        materialUboMapped.putFloat(diffuse.a); // u_diffuseColor.a (opacity from diffuse alpha for now)

        Color specular = material.getSpecularColor(); // Assumes VulkanMaterial has getSpecularColor()
        if (specular == null) specular = Color.WHITE; // Default if not set
        float shininess = material.getShininess();    // Assumes VulkanMaterial has getShininess()
        if (shininess <= 0) shininess = 32f;       // Default shininess, ensure > 0

        materialUboMapped.putFloat(specular.r); // u_specularColorAndShininess.r
        materialUboMapped.putFloat(specular.g); // u_specularColorAndShininess.g
        materialUboMapped.putFloat(specular.b); // u_specularColorAndShininess.b
        materialUboMapped.putFloat(Math.max(0.001f, shininess) / 256.0f); // u_specularColorAndShininess.a (shininess normalized)

        // u_opacity and u_hasDiffuseTexture
        // The current diffuseTexture is the one set on this shader instance,
        // not necessarily the one from material.getDiffuseTexture() if they differ.
        // For consistency, the shader should use its own diffuseTexture field.
        materialUboMapped.putFloat(material.getOpacity());
        materialUboMapped.putFloat(this.diffuseTexture != null && this.diffuseTexture.getImageViewHandle() != VK_NULL_HANDLE ? 1.0f : 0.0f);

        // Padding to fill up to materialUboSize (48 bytes)
        // Current usage: diffuse(16) + specular(16) + opacity(4) + hasTex(4) = 40 bytes. Need 8 bytes padding.
        materialUboMapped.putFloat(0.0f); // padding
        materialUboMapped.putFloat(0.0f); // padding

        materialUboMapped.position(0);
        Vma.vmaFlushAllocation(vulkanApplication.getVmaAllocator(), materialUboBuffer.getAllocationHandle(), 0, materialUboBuffer.getSize());
    }

    public void setAmbientLight(float r, float g, float b, float intensity) {
        lightingUboMapped.position(0);
        lightingUboMapped.putFloat(r);
        lightingUboMapped.putFloat(g);
        lightingUboMapped.putFloat(b);
        lightingUboMapped.putFloat(intensity);
        Vma.vmaFlushAllocation(vulkanApplication.getVmaAllocator(), lightingUboBuffer.getAllocationHandle(), 0, lightingUboSize);
    }

    public void setDirectionalLight(int index, Vector3 direction, float r, float g, float b, float intensity) {
        if (index != 0) {
            Gdx.app.error(TAG, "Only one directional light (index 0) is currently supported.");
            return;
        }
        int offset = 16;
        lightingUboMapped.position(offset);

        lightingUboMapped.putFloat(direction.x);
        lightingUboMapped.putFloat(direction.y);
        lightingUboMapped.putFloat(direction.z);
        lightingUboMapped.putFloat(0.0f);

        lightingUboMapped.putFloat(r);
        lightingUboMapped.putFloat(g);
        lightingUboMapped.putFloat(b);
        lightingUboMapped.putFloat(intensity);

        Vma.vmaFlushAllocation(vulkanApplication.getVmaAllocator(), lightingUboBuffer.getAllocationHandle(), 0, lightingUboSize);
    }


    public void render(VulkanMesh mesh, Matrix4 modelMatrix, Matrix4 viewMatrix, Matrix4 projectionMatrix, VulkanMaterial material) {
        if (vulkanGraphics == null || pipelineBundle == null || mesh == null) {
            Gdx.app.error(TAG, "Cannot render, essential resources missing.");
            return;
        }
        VkCommandBuffer commandBuffer = vulkanGraphics.getCurrentCommandBuffer();
        if (commandBuffer == null) {
            Gdx.app.error(TAG, "No command buffer available for rendering.");
            return;
        }

        updateTransformUBO(modelMatrix, viewMatrix, projectionMatrix);
        updateMaterialUBO(material);

        if (textureBindingNeedsUpdate) {
            updateTextureBindingInDescriptorSet();
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0f).y(0f).width(Gdx.graphics.getWidth()).height(Gdx.graphics.getHeight())
                    .minDepth(0f).maxDepth(1f);
            vkCmdSetViewport(commandBuffer, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack)
                    .offset(VkOffset2D.calloc(stack).set(0,0))
                    .extent(VkExtent2D.calloc(stack).set(Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
            vkCmdSetScissor(commandBuffer, 0, scissor);
        }

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineBundle.getGraphicsPipeline());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipelineBundle.getPipelineLayout(), 0, stack.longs(descriptorSet0), null);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pVertexBuffers = stack.longs(mesh.getVertexBufferHandle());
            LongBuffer pOffsets = stack.longs(0L);
            vkCmdBindVertexBuffers(commandBuffer, 0, pVertexBuffers, pOffsets);
        }
        if (mesh.isIndexed()) {
            vkCmdBindIndexBuffer(commandBuffer, mesh.getIndexBufferHandle(), 0, VK_INDEX_TYPE_UINT16);
        }

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
                Gdx.app.error(TAG, "Error during vkDeviceWaitIdle in SimpleLitTextureShader.dispose: " + e.getMessage());
            }
        }

        if (pipelineBundle != null) pipelineBundle.dispose();
        if (transformUboBuffer != null) transformUboBuffer.dispose();
        if (materialUboBuffer != null) materialUboBuffer.dispose();
        if (lightingUboBuffer != null) lightingUboBuffer.dispose();

        if (descriptorSet0 != VK_NULL_HANDLE && descriptorManager != null &&
                vulkanDevice != null && vulkanDevice.isDeviceAvailable()) {
            descriptorManager.freeSets(Collections.singletonList(descriptorSet0));
        }

        if (shaderManager != null) shaderManager.dispose();

        pipelineBundle = null;
        transformUboBuffer = null;
        materialUboBuffer = null;
        lightingUboBuffer = null;
        descriptorSet0 = VK_NULL_HANDLE;
        shaderManager = null;
        diffuseTexture = null;
    }
}
