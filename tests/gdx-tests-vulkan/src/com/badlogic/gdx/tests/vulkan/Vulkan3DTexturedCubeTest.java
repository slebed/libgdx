package com.badlogic.gdx.tests.vulkan; // Or your preferred test package

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_BACK_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkMapMemory;
import static org.lwjgl.vulkan.VK10.vkUnmapMemory;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backend.vulkan.VulkanApplication;
import com.badlogic.gdx.backend.vulkan.VulkanDescriptorManager;
import com.badlogic.gdx.backend.vulkan.VulkanDevice;
import com.badlogic.gdx.backend.vulkan.VulkanGraphics;
import com.badlogic.gdx.backend.vulkan.VulkanMesh;
import com.badlogic.gdx.backend.vulkan.VulkanPipelineManager;
import com.badlogic.gdx.backend.vulkan.VulkanShaderManager;
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.backend.vulkan.VulkanVertexAttribute;
import com.badlogic.gdx.backend.vulkan.VulkanVertexAttributes;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Collections;

public class Vulkan3DTexturedCubeTest extends GdxTest {
    private static final String TAG = "VulkanCubeTest";

    private PerspectiveCamera camera;
    private CameraInputController cameraInputController;

    private VulkanApplication vulkanApp;
    private VulkanDevice vulkanDevice;
    private VulkanGraphics vulkanGraphics;
    private VulkanShaderManager shaderManager; // Test will create its own instance
    private VulkanPipelineManager pipelineManager;
    private VulkanDescriptorManager descriptorManager;

    private VulkanMesh cubeMesh;

    private long vertShaderModule = VK_NULL_HANDLE;
    private long fragShaderModule = VK_NULL_HANDLE;

    private long mvpUboBuffer = VK_NULL_HANDLE;
    private long mvpUboMemory = VK_NULL_HANDLE;
    private ByteBuffer mvpUboMapped;

    private long mvpDescriptorSetLayout = VK_NULL_HANDLE; // Renamed from descriptorSetLayout
    private long pipelineLayout = VK_NULL_HANDLE;
    private long graphicsPipeline = VK_NULL_HANDLE;
    // descriptorPool is managed by VulkanDescriptorManager
    private long mvpDescriptorSet = VK_NULL_HANDLE;

    private Matrix4 modelMatrix = new Matrix4();
    private float rotationAngleDeg = 0f;
    // Re-usable buffer for converting Matrix4.val to what UBO expects
    //private final FloatBuffer matrixConversionBuffer = BufferUtils.newFloatBuffer(16);
    private VulkanTexture cubeTexture;

    @Override
    public void create() {
        vulkanApp = (VulkanApplication) Gdx.app;
        vulkanDevice = vulkanApp.getVulkanDevice();
        pipelineManager = vulkanApp.getPipelineManager();     // Get from app
        descriptorManager = vulkanApp.getDescriptorManager(); // Get from app
        shaderManager = new VulkanShaderManager(vulkanDevice.getLogicalDevice()); // Test creates its own

        setupCamera();
        setupShaders();
        setupCubeMesh();
        loadCubeTexture();
        setupUniformBuffers();
        setupDescriptors();

        if (Gdx.graphics != null) {
            vulkanGraphics = (VulkanGraphics) Gdx.graphics;
            setupGraphicsPipeline();
        } else {
            Gdx.app.error(TAG, "Gdx.graphics not available during create(). Pipeline setup deferred/may fail.");
        }

        Gdx.input.setInputProcessor(cameraInputController);
        Gdx.app.log(TAG, "VulkanCubeTest created.");
    }

    private void setupCamera() {
        camera = new PerspectiveCamera(67, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.position.set(2f, 1.5f, 2f);
        camera.lookAt(0, 0, 0);
        camera.near = 0.1f;
        camera.far = 100f;
        camera.update();
        cameraInputController = new CameraInputController(camera);
    }

    void setupShaders() {
        shaderManager = new VulkanShaderManager(vulkanDevice.getLogicalDevice());
        vertShaderModule = shaderManager.getShaderModuleFromGlsl(
                Gdx.files.internal("data/vulkan/shaders/textured_mesh.vert.glsl"), // Use new vertex shader
                Shaderc.shaderc_vertex_shader
        );
        fragShaderModule = shaderManager.getShaderModuleFromGlsl(
                Gdx.files.internal("data/vulkan/shaders/textured_mesh.frag.glsl"), // Use new fragment shader
                Shaderc.shaderc_fragment_shader
        );
        if (vertShaderModule == VK_NULL_HANDLE || fragShaderModule == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to load/compile textured shader modules.");
        }
    }

    void setupCubeMesh() {
        // Each face has 4 vertices. 6 faces * 4 vertices/face = 24 vertices
        // Each vertex: X, Y, Z, U, V (5 floats)
        // Total floats: 24 * 5 = 120
        float[] vertices = {
                // Front face (Z+)
                -0.5f, -0.5f,  0.5f,  0.0f, 0.0f, // Bottom-left
                0.5f, -0.5f,  0.5f,  1.0f, 0.0f, // Bottom-right
                0.5f,  0.5f,  0.5f,  1.0f, 1.0f, // Top-right
                -0.5f,  0.5f,  0.5f,  0.0f, 1.0f, // Top-left

                // Back face (Z-)
                -0.5f, -0.5f, -0.5f,  1.0f, 0.0f, // Bottom-left (viewed from outside, so UVs are mirrored compared to front)
                -0.5f,  0.5f, -0.5f,  1.0f, 1.0f, // Top-left
                0.5f,  0.5f, -0.5f,  0.0f, 1.0f, // Top-right
                0.5f, -0.5f, -0.5f,  0.0f, 0.0f, // Bottom-right

                // Top face (Y+)
                -0.5f,  0.5f, -0.5f,  0.0f, 1.0f, // Back-left
                -0.5f,  0.5f,  0.5f,  0.0f, 0.0f, // Front-left
                0.5f,  0.5f,  0.5f,  1.0f, 0.0f, // Front-right
                0.5f,  0.5f, -0.5f,  1.0f, 1.0f, // Back-right

                // Bottom face (Y-)
                -0.5f, -0.5f, -0.5f,  0.0f, 0.0f, // Back-left
                0.5f, -0.5f, -0.5f,  1.0f, 0.0f, // Back-right
                0.5f, -0.5f,  0.5f,  1.0f, 1.0f, // Front-right
                -0.5f, -0.5f,  0.5f,  0.0f, 1.0f, // Front-left

                // Right face (X+)
                0.5f, -0.5f, -0.5f,  1.0f, 0.0f, // Back-bottom
                0.5f,  0.5f, -0.5f,  1.0f, 1.0f, // Back-top
                0.5f,  0.5f,  0.5f,  0.0f, 1.0f, // Front-top
                0.5f, -0.5f,  0.5f,  0.0f, 0.0f, // Front-bottom

                // Left face (X-)
                -0.5f, -0.5f, -0.5f,  0.0f, 0.0f, // Back-bottom
                -0.5f, -0.5f,  0.5f,  1.0f, 0.0f, // Front-bottom
                -0.5f,  0.5f,  0.5f,  1.0f, 1.0f, // Front-top
                -0.5f,  0.5f, -0.5f,  0.0f, 1.0f  // Back-top
        };

        // 6 faces * 2 triangles/face * 3 indices/triangle = 36 indices
        short[] indices = {
                // Front
                0, 1, 2,  2, 3, 0,
                // Back (winding adjusted for outside view)
                4, 5, 6,  6, 7, 4,
                // Top
                8, 9, 10, 10, 11, 8,
                // Bottom (winding adjusted)
                12, 13, 14, 14, 15, 12,
                // Right
                16, 17, 18, 18, 19, 16,
                // Left (winding adjusted)
                20, 21, 22, 22, 23, 20
        };

        // VertexAttributes definition remains the same if locations are consistent
        VulkanVertexAttribute posAttr = VulkanVertexAttribute.Position(0);      // Location 0 for position
        VulkanVertexAttribute texCoordAttr = VulkanVertexAttribute.TexCoords(0, 1); // Location 1 for texCoord0

        VulkanVertexAttributes attributes = new VulkanVertexAttributes(posAttr, texCoordAttr);

        // If cubeMesh is already created, you might need to dispose it first
        // or ensure setVertices/setIndices handles buffer recreation properly.
        if (cubeMesh != null) {
            cubeMesh.dispose(); // Dispose old mesh if re-setting
        }
        cubeMesh = new VulkanMesh(vulkanDevice);
        cubeMesh.setVertices(vertices, attributes);
        cubeMesh.setIndices(indices);
    }

    void loadCubeTexture() {
        // Replace "data/my_cube_texture.png" with your actual texture file
        FileHandle textureFile = Gdx.files.internal("data/badlogic.jpg"); // Example
        if (!textureFile.exists()) {
            throw new GdxRuntimeException("Cube texture file not found: " + textureFile.path());
        }
        cubeTexture = new VulkanTexture(textureFile); // Uses your VulkanTexture class
        Gdx.app.log(TAG, "Cube texture loaded: " + cubeTexture.getFilePath());
    }

    private int findMemoryType(int typeFilter, int properties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(vulkanDevice.getPhysicalDevice(), memProperties);
            for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
                if ((typeFilter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                    return i;
                }
            }
        }
        throw new GdxRuntimeException("Failed to find suitable memory type!");
    }

    private void setupUniformBuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long bufferSize = 3 * 16 * Float.BYTES; // Model, View, Projection matrices

            LongBuffer pBuffer = stack.mallocLong(1);
            LongBuffer pMemory = stack.mallocLong(1);

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(bufferSize)
                    .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            if (vkCreateBuffer(vulkanDevice.getLogicalDevice(), bufferInfo, null, pBuffer) != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to create MVP UBO buffer.");
            }
            mvpUboBuffer = pBuffer.get(0);

            VkMemoryRequirements memReq = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(vulkanDevice.getLogicalDevice(), mvpUboBuffer, memReq);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(memReq.size())
                    .memoryTypeIndex(findMemoryType(memReq.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            if (vkAllocateMemory(vulkanDevice.getLogicalDevice(), allocInfo, null, pMemory) != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to allocate MVP UBO memory.");
            }
            mvpUboMemory = pMemory.get(0);
            if (vkBindBufferMemory(vulkanDevice.getLogicalDevice(), mvpUboBuffer, mvpUboMemory, 0) != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to bind MVP UBO memory.");
            }

            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(vulkanDevice.getLogicalDevice(), mvpUboMemory, 0, bufferSize, 0, pData);
            mvpUboMapped = pData.getByteBuffer(0, (int) bufferSize);
        }
    }

    void setupDescriptors() { // Renamed or keep old name if preferred
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. DescriptorSetLayout for MVP UBO and Texture Sampler
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);

            // Binding 0: MVP UBO (Vertex Shader)
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                    .pImmutableSamplers(null);

            // Binding 1: Texture Sampler (Fragment Shader)
            bindings.get(1)
                    .binding(1) // Must match layout(binding=1) in fragment shader
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .pImmutableSamplers(null);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings); // Pass both bindings

            LongBuffer pSetLayout = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(vulkanDevice.getLogicalDevice(), layoutInfo, null, pSetLayout) != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to create descriptor set layout for MVP UBO and Texture.");
            }
            mvpDescriptorSetLayout = pSetLayout.get(0);

            // 2. PipelineLayout (using the VulkanPipelineManager)
            pipelineLayout = pipelineManager.getOrCreatePipelineLayout(mvpDescriptorSetLayout);
            if (pipelineLayout == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Failed to get or create pipeline layout from VulkanPipelineManager.");
            }

            // 3. Allocate DescriptorSet FROM THE MANAGER
            // The pool in VulkanDescriptorManager needs to be able to allocate for this new layout.
            // Its default pool sizes include MAX_SAMPLERS_PER_POOL.
            mvpDescriptorSet = descriptorManager.allocateSet(mvpDescriptorSetLayout);
            if (mvpDescriptorSet == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Failed to allocate MVP and Texture descriptor set from VulkanDescriptorManager.");
            }

            // 4. Update DescriptorSet to point to the UBO buffer and Texture
            VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(2, stack);

            // Write for UBO
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(mvpUboBuffer)
                    .offset(0)
                    .range(3 * 16 * Float.BYTES);

            descriptorWrites.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(mvpDescriptorSet)
                    .dstBinding(0) // UBO binding
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(bufferInfo);

            // Write for Texture Sampler
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(cubeTexture.getImageViewHandle())
                    .sampler(cubeTexture.getSamplerHandle());

            descriptorWrites.get(1)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(mvpDescriptorSet)
                    .dstBinding(1) // Texture sampler binding
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo);

            vkUpdateDescriptorSets(vulkanDevice.getLogicalDevice(), descriptorWrites, null);
        }
    }

    private void setupGraphicsPipeline() {
        if (vulkanGraphics == null) {
            vulkanGraphics = (VulkanGraphics) Gdx.graphics;
            if (vulkanGraphics == null) {
                throw new GdxRuntimeException("VulkanGraphics context not available for pipeline creation in setupGraphicsPipeline.");
            }
        }

        long currentRenderPass = vulkanGraphics.getSwapchainRenderPass();
        if (currentRenderPass == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("SwapchainRenderPass not available from VulkanGraphics for pipeline creation.");
        }
        long currentPipelineCache = pipelineManager.getVkPipelineCacheHandle();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            shaderStages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertShaderModule).pName(stack.UTF8("main"));
            shaderStages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragShaderModule).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(cubeMesh.getBindingDescription())
                    .pVertexAttributeDescriptions(cubeMesh.getAttributeDescriptions());

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1).scissorCount(1); // Dynamic

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false).rasterizerDiscardEnable(false)
                    .polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1.0f)
                    .cullMode(VK_CULL_MODE_BACK_BIT).frontFace(VK_FRONT_FACE_CLOCKWISE)
                    .depthBiasEnable(false);

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .sampleShadingEnable(false).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS)
                    .depthBoundsTestEnable(false).stencilTestEnable(false);

            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(false);
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false).pAttachments(colorBlendAttachment);

            IntBuffer pDynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(pDynamicStates);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(shaderStages).pVertexInputState(vertexInputInfo)
                    .pInputAssemblyState(inputAssembly).pViewportState(viewportState)
                    .pRasterizationState(rasterizer).pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil).pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState).layout(pipelineLayout)
                    .renderPass(currentRenderPass)
                    .subpass(0);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            if (vkCreateGraphicsPipelines(vulkanDevice.getLogicalDevice(), currentPipelineCache, pipelineInfo, null, pGraphicsPipeline) != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to create graphics pipeline.");
            }
            graphicsPipeline = pGraphicsPipeline.get(0);
        }
    }

    @Override
    public void render() {
        // 1. Ensure VulkanGraphics context is available and pipeline is ready
        if (vulkanGraphics == null) {
            if (Gdx.graphics instanceof VulkanGraphics) {
                vulkanGraphics = (VulkanGraphics) Gdx.graphics;

                if (graphicsPipeline == VK_NULL_HANDLE) {
                    Gdx.app.log(TAG, "Graphics pipeline was null in render(), attempting setup...");
                    try {
                        setupGraphicsPipeline(); // This method needs vulkanGraphics to get the RenderPass
                    } catch (Exception e) {
                        Gdx.app.error(TAG, "Failed to setup graphics pipeline in render()", e);
                        return;
                    }
                }
            } else {
                Gdx.app.error(TAG, "Render call with invalid Gdx.graphics instance, cannot proceed.");
                return; // Critical error, cannot render
            }
        }

        // If pipeline is still not ready after attempting setup, abort.
        if (graphicsPipeline == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Graphics pipeline is still null after setup attempt, skipping render frame.");
            return;
        }

        // 2. Get the current command buffer from VulkanGraphics
        // This assumes VulkanGraphics.beginFrame() or equivalent has been called by VulkanApplication/VulkanWindow,
        // and the command buffer is in the recording state, and a render pass has been started.
        VkCommandBuffer commandBuffer = vulkanGraphics.getCurrentCommandBuffer();
        if (commandBuffer == null) {
            Gdx.app.error(TAG, "No command buffer available from VulkanGraphics for rendering.");
            return; // Cannot record commands
        }

        // 3. Update camera (if using CameraInputController or other logic)
        if (cameraInputController != null) {
            cameraInputController.update();
        }
        camera.update(); // Always update camera matrices

        // 4. Update model matrix for cube rotation
        rotationAngleDeg = (rotationAngleDeg + Gdx.graphics.getDeltaTime() * 45f) % 360f; // Rotate 45 degrees per second
        modelMatrix.setToRotation(Vector3.Y, rotationAngleDeg);
        // modelMatrix.rotate(Vector3.X, 15f); // Optional static tilt for better visibility

        // 5. Update Uniform Buffer Object (UBO) with Model, View, Projection matrices
        // Shader UBO structure: mat4 model, mat4 view, mat4 proj;
        FloatBuffer uboFloatView = mvpUboMapped.asFloatBuffer(); // Get a FloatBuffer view of the mapped ByteBuffer

        // Model Matrix (Offset 0 floats / 0 bytes)
        uboFloatView.position(0);
        uboFloatView.put(modelMatrix.val);

        // View Matrix (Offset 16 floats / 64 bytes)
        uboFloatView.position(16);
        uboFloatView.put(camera.view.val);

        // Projection Matrix (Offset 32 floats / 128 bytes)
        uboFloatView.position(32);
        uboFloatView.put(camera.projection.val);

        // The mvpUboMapped ByteBuffer's position is not directly relevant here
        // as asFloatBuffer() creates a view, and we use absolute positioning within that view.

        // --- Begin Recording Draw Commands ---

        // 6. Set Dynamic Viewport and Scissor (should match current window/framebuffer size)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0f)
                    .y(0f) // For Vulkan, Y is often flipped (bottom-up) if not handled by projection matrix.
                    // If using a standard LibGDX projection, it handles Y-flip.
                    // If viewport needs Y-flip: .y(Gdx.graphics.getHeight()).height(-Gdx.graphics.getHeight())
                    .width(Gdx.graphics.getWidth())
                    .height(Gdx.graphics.getHeight())
                    .minDepth(0f)
                    .maxDepth(1f);
            vkCmdSetViewport(commandBuffer, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack)
                    .offset(VkOffset2D.calloc(stack).set(0, 0))
                    .extent(VkExtent2D.calloc(stack).set(Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
            vkCmdSetScissor(commandBuffer, 0, scissor);
        }

        // 7. Bind the Graphics Pipeline
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);

        // 8. Bind Vertex Buffer(s)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pVertexBuffers = stack.longs(cubeMesh.getVertexBufferHandle());
            LongBuffer pOffsets = stack.longs(0L); // Vertex buffer offset is 0
            vkCmdBindVertexBuffers(commandBuffer, 0, pVertexBuffers, pOffsets); // Binding 0
        }

        // 9. Bind Index Buffer (if using indexed drawing)
        if (cubeMesh.isIndexed()) {
            vkCmdBindIndexBuffer(commandBuffer, cubeMesh.getIndexBufferHandle(), 0, VK_INDEX_TYPE_UINT16);
        }

        // 10. Bind Descriptor Sets (for UBOs, textures, etc.)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pDescriptorSets = stack.longs(mvpDescriptorSet);
            vkCmdBindDescriptorSets(commandBuffer,
                    VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipelineLayout,
                    0, // firstSet
                    pDescriptorSets,
                    null); // pDynamicOffsets (not used in this simple case)
        }

        // 11. Issue Draw Call
        if (cubeMesh.isIndexed()) {
            vkCmdDrawIndexed(commandBuffer, cubeMesh.getNumIndices(), 1, 0, 0, 0);
            // (indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)
        } else {
            vkCmdDraw(commandBuffer, cubeMesh.getNumVertices(), 1, 0, 0);
            // (vertexCount, instanceCount, firstVertex, firstInstance)
        }

        // --- End Recording Draw Commands ---
        // The VulkanApplication/VulkanGraphics framework will handle:
        // - Ending the Render Pass
        // - Ending the Command Buffer
        // - Submitting the Command Buffer
        // - Presenting the Swapchain Image
    }

    @Override
    public void resize(int width, int height) {
        if (camera != null) {
            camera.viewportWidth = width;
            camera.viewportHeight = height;
            camera.update();
        }
    }

    @Override
    public void dispose() {
        if (vulkanDevice != null && vulkanDevice.getLogicalDevice() != null) {
            vkDeviceWaitIdle(vulkanDevice.getLogicalDevice());
        }

        if (graphicsPipeline != VK_NULL_HANDLE) vkDestroyPipeline(vulkanDevice.getLogicalDevice(), graphicsPipeline, null);
        // PipelineLayout is managed by VulkanPipelineManager if obtained via getOrCreatePipelineLayout
        // if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(vulkanDevice.getLogicalDevice(), pipelineLayout, null); // Don't destroy if manager owns it
        if (mvpDescriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(vulkanDevice.getLogicalDevice(), mvpDescriptorSetLayout, null); // Test created this

        if (mvpDescriptorSet != VK_NULL_HANDLE && descriptorManager != null) {
            descriptorManager.freeSets(Collections.singletonList(mvpDescriptorSet));
        }
        // DescriptorPool is managed by VulkanDescriptorManager

        if (mvpUboBuffer != VK_NULL_HANDLE) {
            if (mvpUboMapped != null) vkUnmapMemory(vulkanDevice.getLogicalDevice(), mvpUboMemory);
            vkDestroyBuffer(vulkanDevice.getLogicalDevice(), mvpUboBuffer, null);
        }
        if (mvpUboMemory != VK_NULL_HANDLE) vkFreeMemory(vulkanDevice.getLogicalDevice(), mvpUboMemory, null);

        if (cubeTexture != null) {
            cubeTexture.dispose();
            cubeTexture = null;
        }

        if (cubeMesh != null) cubeMesh.dispose();

        // Shader modules should be disposed by the shaderManager IF this test doesn't take ownership.
        // If shaderManager.getShaderModuleFromGlsl just returns a handle without caching,
        // or if this test needs to ensure they are gone, destroy them.
        // For a local shaderManager instance, it will be disposed, cleaning its cache.
        if (shaderManager != null) shaderManager.dispose();
        else { // Fallback if shaderManager was null but modules were loaded
            if (vertShaderModule != VK_NULL_HANDLE && vulkanDevice != null) vkDestroyShaderModule(vulkanDevice.getLogicalDevice(), vertShaderModule, null);
            if (fragShaderModule != VK_NULL_HANDLE && vulkanDevice != null) vkDestroyShaderModule(vulkanDevice.getLogicalDevice(), fragShaderModule, null);
        }

        if (Gdx.input.getInputProcessor() == cameraInputController) {
            Gdx.input.setInputProcessor(null);
        }
        Gdx.app.log(TAG, "VulkanCubeTest disposed.");
    }
}