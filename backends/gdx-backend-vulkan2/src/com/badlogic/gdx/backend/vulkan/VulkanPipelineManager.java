
package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Manages the creation, caching (optional), and cleanup of Vulkan Shader Modules, Pipeline Layouts, and Graphics Pipelines.
 * (Initial version handles one default pipeline configuration and basic shader caching). */
public class VulkanPipelineManager implements Disposable {
    private final String TAG = "VulkanPipelineManager";
    private final VulkanDevice device;
    private final VkDevice rawDevice;

    private final Map<String, Long> shaderModuleCache; // Cache: File path -> Module Handle
    private final Map<Long, Long> pipelineLayoutCache = new HashMap<>();
    private final Map<PipelineCacheKey, Long> spriteBatchPipelineCache = new HashMap<>();
    private long vkPipelineCacheHandle = VK_NULL_HANDLE;
    private long graphicsPipeline = VK_NULL_HANDLE;

    private static class PipelineCacheKey {
        final long layout;
        final long renderPass;

        PipelineCacheKey(long layout, long renderPass) {
            this.layout = layout;
            this.renderPass = renderPass;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PipelineCacheKey that = (PipelineCacheKey) o;
            return layout == that.layout && renderPass == that.renderPass;
        }

        @Override
        public int hashCode() {
            // Simple hash combiner
            int result = Long.hashCode(layout);
            result = 31 * result + Long.hashCode(renderPass);
            return result;
        }
    }

    /** Constructs a VulkanPipelineManager.
     *
     * @param device The VulkanDevice wrapper. */
    public VulkanPipelineManager(VulkanDevice device) {
        this.device = device;
        this.rawDevice = device.getRawDevice();
        this.shaderModuleCache = new HashMap<>();

        createPipelineCache();
        Gdx.app.log(TAG, "Initialized.");
    }

    /** Destroys the currently managed graphics pipeline and pipeline layout. Safe to call even if handles are VK_NULL_HANDLE. */
    public void cleanupLayoutAndPipeline() {
        if (graphicsPipeline != VK_NULL_HANDLE) {
            Gdx.app.log(TAG, "Destroying graphics pipeline: " + graphicsPipeline);
            vkDestroyPipeline(rawDevice, graphicsPipeline, null);
            graphicsPipeline = VK_NULL_HANDLE; // Reset the handle
        }
    }

    /** Disposes all resources managed by this manager, including cached shader modules, the current pipeline/layout, and the
     * Vulkan pipeline cache (if created). */
    @Override
    public void dispose() {
        Gdx.app.log(TAG, "Disposing pipeline manager...");
        cleanupLayoutAndPipeline(); // Clean the default graphicsPipeline

        // Clean up cached shader modules
        Gdx.app.log(TAG, "Cleaning up cached shader modules (" + shaderModuleCache.size() + ")...");
        for (Map.Entry<String, Long> entry : shaderModuleCache.entrySet()) {
            long moduleHandle = entry.getValue();
            if (moduleHandle != VK_NULL_HANDLE) {
                Gdx.app.log(TAG, "Destroying shader module: " + moduleHandle + " (" + entry.getKey() + ")");
                vkDestroyShaderModule(rawDevice, moduleHandle, null);
            }
        }
        shaderModuleCache.clear(); // Clear map AFTER destroying contents
        Gdx.app.log(TAG, "Shader module cache cleared.");

        // Clean up cached pipeline layouts
        Gdx.app.log(TAG, "Cleaning up cached pipeline layouts (" + pipelineLayoutCache.size() + ")...");
        for (long layoutHandle : pipelineLayoutCache.values()) { // Iterate through the handles
            if (layoutHandle != VK_NULL_HANDLE) {
                Gdx.app.log(TAG, "Destroying cached pipeline layout: " + layoutHandle);
                vkDestroyPipelineLayout(rawDevice, layoutHandle, null); // Destroy the layout
            }
        }

        pipelineLayoutCache.clear(); // Clear map AFTER destroying contents
        Gdx.app.log(TAG, "Pipeline layout cache cleared.");

        Gdx.app.log(TAG, "Cleaning up cached SpriteBatch pipelines (" + spriteBatchPipelineCache.size() + ")...");
        for (long pipelineHandle : spriteBatchPipelineCache.values()) {
            if (pipelineHandle != VK_NULL_HANDLE) {
                Gdx.app.log(TAG, "Destroying cached SpriteBatch pipeline: " + pipelineHandle);
                vkDestroyPipeline(rawDevice, pipelineHandle, null);
            }
        }
        spriteBatchPipelineCache.clear();
        Gdx.app.log(TAG, "SpriteBatch pipeline cache cleared.");

        // Optional: Destroy VkPipelineCache
        destroyPipelineCache();

        Gdx.app.log(TAG, "Pipeline manager disposed.");
    }

    /** Loads or retrieves a cached shader module.
     *
     * @param shaderFile FileHandle for the shader.
     * @return VkShaderModule handle. */
    private long loadShaderModule(FileHandle shaderFile) {
        if (shaderFile == null) {
            throw new GdxRuntimeException("Shader FileHandle cannot be null.");
        }
        String pathKey = shaderFile.path(); // Use path as cache key

        synchronized (shaderModuleCache) { // Synchronize cache access
            Long cachedHandle = shaderModuleCache.get(pathKey);
            if (cachedHandle != null) {
                Gdx.app.debug(TAG, "Shader module cache hit for: " + pathKey);
                return cachedHandle;
            }

            Gdx.app.log(TAG, "Loading shader module from: " + pathKey);
            try {
                ByteBuffer shaderCode = readFileToByteBuffer(shaderFile); // Use helper
                long moduleHandle = createShaderModuleInternal(shaderCode); // Use helper
                shaderModuleCache.put(pathKey, moduleHandle); // Store in cache
                Gdx.app.log(TAG, "Shader module loaded and cached: " + moduleHandle + " [" + pathKey + "]");
                return moduleHandle;
            } catch (Exception e) {
                throw new GdxRuntimeException("Failed to load shader module: " + pathKey, e);
            }
        }
    }

    private ByteBuffer readFileToByteBuffer(FileHandle fileHandle) {
        if (!fileHandle.exists()) {
            throw new GdxRuntimeException("Shader file not found: " + fileHandle.path() + " (" + fileHandle.type() + ")");
        }
        byte[] bytes = fileHandle.readBytes();
        ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    private long createShaderModuleInternal(ByteBuffer code) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);

            LongBuffer pShaderModule = stack.mallocLong(1);
            vkCheck(vkCreateShaderModule(rawDevice, createInfo, null, pShaderModule), "Failed to create shader module.");
            return pShaderModule.get(0);
        }
    }

    private VkPipelineShaderStageCreateInfo.Buffer createShaderStages(MemoryStack stack, long vertModuleHandle, long fragModuleHandle) {
        VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        shaderStages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModuleHandle).pName(stack.UTF8("main"));
        shaderStages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModuleHandle).pName(stack.UTF8("main"));
        return shaderStages;
    }

    private VkPipelineVertexInputStateCreateInfo createVertexInputInfo(MemoryStack stack) {
        // Corresponds to: float x,y + float r,g,b + float u,v (7 floats total)
        VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack).binding(0)
                .stride(7 * Float.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(3, stack);
        attributeDescriptions.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
        attributeDescriptions.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32_SFLOAT).offset(2 * Float.BYTES);
        attributeDescriptions.get(2).binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset(5 * Float.BYTES);

        return VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default().pVertexBindingDescriptions(bindingDescription)
                .pVertexAttributeDescriptions(attributeDescriptions);
    }

    private VkPipelineInputAssemblyStateCreateInfo createInputAssemblyInfo(MemoryStack stack) {
        return VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                .primitiveRestartEnable(false);
    }

    private VkPipelineViewportStateCreateInfo createViewportInfo(MemoryStack stack) {
        // Viewport and Scissor are dynamic states, so counts matter but contents don't here.
        return VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
    }

    private VkPipelineRasterizationStateCreateInfo createRasterizationInfo(MemoryStack stack) {
        return VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().depthClampEnable(false)
                .rasterizerDiscardEnable(false).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1.0f).cullMode(VK_CULL_MODE_NONE)
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .depthBiasEnable(false);
    }

    private VkPipelineMultisampleStateCreateInfo createMultisampleInfo(MemoryStack stack) {
        return VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().sampleShadingEnable(false)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT); // No MSAA
    }

    private VkPipelineDepthStencilStateCreateInfo createDepthStencilInfo(MemoryStack stack) {
        return VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default().depthTestEnable(false) // No depth testing
                .depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL) // Irrelevant if test disabled
                .depthBoundsTestEnable(false).stencilTestEnable(false);
    }

    private VkPipelineDynamicStateCreateInfo createDynamicStateInfo(MemoryStack stack) {
        IntBuffer pDynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
        return VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(pDynamicStates);
    }

    private void createPipelineCache() {
        Gdx.app.log(TAG, "Creating Vulkan pipeline cache...");
        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            // TODO: Implement loading cache data from file for faster startup
            LongBuffer pCache = stack.mallocLong(1);
            int result = vkCreatePipelineCache(rawDevice, cacheInfo, null, pCache);
            if (result == VK_SUCCESS) {
                this.vkPipelineCacheHandle = pCache.get(0);
                Gdx.app.log(TAG, "Pipeline cache created: " + this.vkPipelineCacheHandle);
            } else {
                Gdx.app.error(TAG, "Failed to create pipeline cache, result: " + VkResultDecoder.decode(result));
                this.vkPipelineCacheHandle = VK_NULL_HANDLE;
            }
        } catch (Exception e) {
            Gdx.app.error(TAG, "Exception creating pipeline cache", e);
            this.vkPipelineCacheHandle = VK_NULL_HANDLE;
        }
    }

    private void destroyPipelineCache() {
        if (vkPipelineCacheHandle != VK_NULL_HANDLE) {
            Gdx.app.log(TAG, "Destroying Vulkan pipeline cache: " + vkPipelineCacheHandle);
            // TODO: Implement saving cache data to file
            // vkGetPipelineCacheData(...)
            vkDestroyPipelineCache(rawDevice, vkPipelineCacheHandle, null);
            vkPipelineCacheHandle = VK_NULL_HANDLE;
        }
    }

    /** Gets an existing VkPipelineLayout for the given descriptor set layout, or creates and caches a new one if it doesn't exist.
     *
     * @param descriptorSetLayoutHandle The handle of the VkDescriptorSetLayout.
     * @return The handle of the corresponding VkPipelineLayout. */
    public synchronized long getOrCreatePipelineLayout(long descriptorSetLayoutHandle) {
        if (descriptorSetLayoutHandle == VK_NULL_HANDLE) {
            throw new IllegalArgumentException("DescriptorSetLayout handle cannot be VK_NULL_HANDLE");
        }

        // Check cache first
        Long cachedLayout = pipelineLayoutCache.get(descriptorSetLayoutHandle);
        if (cachedLayout != null) {
            Gdx.app.debug(TAG, "PipelineLayout cache hit for DescriptorSetLayout: " + descriptorSetLayoutHandle);
            return cachedLayout;
        }

        // Not in cache, create it
        Gdx.app.log(TAG, "Creating new PipelineLayout for DescriptorSetLayout: " + descriptorSetLayoutHandle);
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayoutHandle)); // Use provided layout

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            vkCheck(vkCreatePipelineLayout(rawDevice, pipelineLayoutInfo, null, pPipelineLayout),
                    "Failed to create pipeline layout for DSL: " + descriptorSetLayoutHandle);
            long newPipelineLayoutHandle = pPipelineLayout.get(0);

            // Store in cache
            pipelineLayoutCache.put(descriptorSetLayoutHandle, newPipelineLayoutHandle);
            Gdx.app.log(TAG, "Created and cached PipelineLayout: " + newPipelineLayoutHandle);
            return newPipelineLayoutHandle;

        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to create pipeline layout for DSL: " + descriptorSetLayoutHandle, e);
        }
    }

    /**
     * Gets or creates a graphics pipeline specifically configured for VulkanSpriteBatch.
     * Uses a cache keyed by both pipeline layout and render pass handle.
     *
     * @param batchPipelineLayoutHandle The pipeline layout handle used by VulkanSpriteBatch.
     * @param renderPassHandle Handle to a compatible render pass.
     * @return The VkPipeline handle for the SpriteBatch pipeline.
     */
    public synchronized long getOrCreateSpriteBatchPipeline(long batchPipelineLayoutHandle, long renderPassHandle) {
        // Validate input handles
        if (batchPipelineLayoutHandle == VK_NULL_HANDLE || renderPassHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot create SpriteBatch pipeline with NULL layout or render pass handle.");
        }

        PipelineCacheKey key = new PipelineCacheKey(batchPipelineLayoutHandle, renderPassHandle);
        Long cachedPipeline = spriteBatchPipelineCache.get(key);

        if (cachedPipeline != null) {
            return cachedPipeline;
        }

        Gdx.app.log(TAG, "getOrCreateSpriteBatchPipeline() Creating NEW SpriteBatch graphics pipeline for Layout=" + batchPipelineLayoutHandle + ", RP=" + renderPassHandle);

        // --- Load Shaders (Can still use shader cache) ---
        FileHandle vertShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.vert.spv");
        FileHandle fragShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.frag.spv");
        long vertModuleHandle = loadShaderModule(vertShaderFile); // Uses shader cache
        long fragModuleHandle = loadShaderModule(fragShaderFile); // Uses shader cache

        try (MemoryStack stack = stackPush()) {
            long pipelineLayoutHandle = batchPipelineLayoutHandle;

            // --- Configure Pipeline States (Vertex Input, Blending etc.) ---
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = createShaderStages(stack, vertModuleHandle, fragModuleHandle);

            // Define vertex input specific to VulkanSpriteBatch
            final int POSITION_COMPONENTS_TEST = 2;
            final int COLOR_COMPONENTS_TEST = 4; // CHANGED from 1
            final int TEXCOORD_COMPONENTS_TEST = 2;
            final int TOTAL_COMPONENTS_TEST = POSITION_COMPONENTS_TEST + COLOR_COMPONENTS_TEST + TEXCOORD_COMPONENTS_TEST; // 2 + 4 + 2 = 8

            VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0)
                    .stride(VulkanSpriteBatch.COMPONENTS_PER_VERTEX * Float.BYTES) // Should use 5 * 4 = 20 bytes
                    .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(3, stack);
            // Location 0: Position (vec2) - Offset 0
            attributeDescriptions.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
            // Location 1: Color (Packed Float) - Offset 8
            attributeDescriptions.get(1).binding(0).location(1).format(VK_FORMAT_R32_SFLOAT).offset(VulkanSpriteBatch.POSITION_COMPONENTS * Float.BYTES); // Offset 8
            // Location 2: TexCoord (vec2) - Offset 12 (after pos + packed color)
            attributeDescriptions.get(2).binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset((VulkanSpriteBatch.POSITION_COMPONENTS + VulkanSpriteBatch.COLOR_COMPONENTS) * Float.BYTES); // Offset 12 (2*4 + 1*4)

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pVertexBindingDescriptions(bindingDescription)
                    .pVertexAttributeDescriptions(attributeDescriptions);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = createInputAssemblyInfo(stack);
            VkPipelineViewportStateCreateInfo viewportState = createViewportInfo(stack);
            VkPipelineRasterizationStateCreateInfo rasterizer = createRasterizationInfo(stack).cullMode(VK_CULL_MODE_NONE); // No Culling
            VkPipelineMultisampleStateCreateInfo multisampling = createMultisampleInfo(stack);
            VkPipelineDepthStencilStateCreateInfo depthStencil = createDepthStencilInfo(stack).depthTestEnable(false).depthWriteEnable(false); // No Depth

            // Alpha Blending State
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(true) // <<< ENABLE BLENDING
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE) // Common alpha blend factors
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                    .alphaBlendOp(VK_BLEND_OP_ADD);

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachment);

            VkPipelineDynamicStateCreateInfo dynamicState = createDynamicStateInfo(stack); // Viewport, Scissor

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .pStages(shaderStages)
                    .pVertexInputState(vertexInputInfo)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState)
                    .layout(pipelineLayoutHandle)
                    .renderPass(renderPassHandle)
                    .subpass(0)
                    .basePipelineHandle(VK_NULL_HANDLE)
                    .basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            Gdx.app.log(TAG, "Using layout handle " + pipelineLayoutHandle + " in VkGraphicsPipelineCreateInfo.");
            vkCheck(vkCreateGraphicsPipelines(rawDevice, this.vkPipelineCacheHandle, pipelineInfo, null, pGraphicsPipeline), "Failed to create SpriteBatch graphics pipeline");

            long newPipelineHandle = pGraphicsPipeline.get(0);

            spriteBatchPipelineCache.put(key, newPipelineHandle);
            Gdx.app.log(TAG, "SpriteBatch graphics pipeline created and cached successfully: " + newPipelineHandle + " for Key(Layout=" + key.layout + ", RP=" + key.renderPass + ")");

            return newPipelineHandle;

        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to create SpriteBatch graphics pipeline for Layout="
                    + batchPipelineLayoutHandle + ", RP=" + renderPassHandle, e);
        }
    }
}
