package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20; // Needed for blend function constants
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

// import java.nio.ByteBuffer; // No longer directly used here for shader code
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects; // For Objects.hash

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the creation, caching, and cleanup of Vulkan Pipeline Layouts and Graphics Pipelines.
 * It now uses a VulkanShaderManager to obtain shader modules.
 */
public class VulkanPipelineManager implements Disposable {
    private final String TAG = "VulkanPipelineManager";
    private final VulkanDevice device; // Keep VulkanDevice for higher-level context if needed
    private final VkDevice rawDevice;
    private final VulkanShaderManager shaderManager; // New: Reference to the shader manager

    // Caches for pipeline-related objects
    private final Map<Long, Long> pipelineLayoutCache; // Cache: DescriptorSetLayout Handle -> PipelineLayout Handle
    private final Map<NonInstancedPipelineCacheKey, Long> spriteBatchPipelineCache; // Cache for non-instanced SpriteBatch
    private final Map<InstancedPipelineCacheKey, Long> instancedSpriteBatchPipelineCache; // Cache for instanced SpriteBatch
    private long vkPipelineCacheHandle = VK_NULL_HANDLE; // Vulkan's native pipeline cache object

    // --- Cache Key Classes (remain the same) ---
    private static class NonInstancedPipelineCacheKey {
        final long layout;
        final long renderPass;
        final boolean blendingEnabled;
        final int blendSrcFunc;
        final int blendDstFunc;
        final int blendSrcFuncAlpha;
        final int blendDstFuncAlpha;

        NonInstancedPipelineCacheKey(long layout, long renderPass, boolean blendingEnabled, int blendSrcFunc, int blendDstFunc, int blendSrcFuncAlpha, int blendDstFuncAlpha) {
            this.layout = layout;
            this.renderPass = renderPass;
            this.blendingEnabled = blendingEnabled;
            this.blendSrcFunc = blendSrcFunc;
            this.blendDstFunc = blendDstFunc;
            this.blendSrcFuncAlpha = blendSrcFuncAlpha;
            this.blendDstFuncAlpha = blendDstFuncAlpha;
        }
        @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; NonInstancedPipelineCacheKey that = (NonInstancedPipelineCacheKey) o; return layout == that.layout && renderPass == that.renderPass && blendingEnabled == that.blendingEnabled && blendSrcFunc == that.blendSrcFunc && blendDstFunc == that.blendDstFunc && blendSrcFuncAlpha == that.blendSrcFuncAlpha && blendDstFuncAlpha == that.blendDstFuncAlpha; }
        @Override public int hashCode() { return Objects.hash(layout, renderPass, blendingEnabled, blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha); }
    }

    private static class InstancedPipelineCacheKey {
        final long layout;
        final long renderPass;
        final boolean blendingEnabled;
        final int blendSrcFunc;
        final int blendDstFunc;
        final int blendSrcFuncAlpha;
        final int blendDstFuncAlpha;

        InstancedPipelineCacheKey(long layout, long renderPass, boolean blendingEnabled, int blendSrcFunc, int blendDstFunc, int blendSrcFuncAlpha, int blendDstFuncAlpha) {
            this.layout = layout;
            this.renderPass = renderPass;
            this.blendingEnabled = blendingEnabled;
            this.blendSrcFunc = blendSrcFunc;
            this.blendDstFunc = blendDstFunc;
            this.blendSrcFuncAlpha = blendSrcFuncAlpha;
            this.blendDstFuncAlpha = blendDstFuncAlpha;
        }
        @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; InstancedPipelineCacheKey that = (InstancedPipelineCacheKey) o; return layout == that.layout && renderPass == that.renderPass && blendingEnabled == that.blendingEnabled && blendSrcFunc == that.blendSrcFunc && blendDstFunc == that.blendDstFunc && blendSrcFuncAlpha == that.blendSrcFuncAlpha && blendDstFuncAlpha == that.blendDstFuncAlpha; }
        @Override public int hashCode() { return Objects.hash(layout, renderPass, blendingEnabled, blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha); }
    }

    /**
     * Constructs a VulkanPipelineManager.
     *
     * @param device The VulkanDevice wrapper.
     * @param shaderManager The VulkanShaderManager instance for obtaining shader modules.
     */
    public VulkanPipelineManager(VulkanDevice device, VulkanShaderManager shaderManager) {
        this.device = device;
        this.rawDevice = device.getRawDevice();
        this.shaderManager = Objects.requireNonNull(shaderManager, "VulkanShaderManager cannot be null.");
        this.pipelineLayoutCache = new HashMap<>();
        this.spriteBatchPipelineCache = new HashMap<>();
        this.instancedSpriteBatchPipelineCache = new HashMap<>();

        createPipelineCache();
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Initialized with ShaderManager.");
    }

    /**
     * Returns the handle to the managed Vulkan pipeline cache object.
     * This can be used by external pipeline creation processes.
     *
     * @return The handle to the VkPipelineCache, or VK_NULL_HANDLE if not created.
     */
    public long getVkPipelineCacheHandle() {
        return this.vkPipelineCacheHandle;
    }

    @Override
    public void dispose() {
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Disposing pipeline manager...");
        // Shader modules are now disposed by VulkanShaderManager, so no need to iterate shaderModuleCache here.

        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Cleaning up cached pipeline layouts (" + pipelineLayoutCache.size() + ")...");
        for (long layoutHandle : pipelineLayoutCache.values()) {
            if (layoutHandle != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(rawDevice, layoutHandle, null);
            }
        }
        pipelineLayoutCache.clear();
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Pipeline layout cache cleared.");

        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Cleaning up cached Non-Instanced SpriteBatch pipelines (" + spriteBatchPipelineCache.size() + ")...");
        for (long pipelineHandle : spriteBatchPipelineCache.values()) {
            if (pipelineHandle != VK_NULL_HANDLE) {
                vkDestroyPipeline(rawDevice, pipelineHandle, null);
            }
        }
        spriteBatchPipelineCache.clear();
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Non-Instanced SpriteBatch pipeline cache cleared.");

        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Cleaning up cached Instanced SpriteBatch pipelines (" + instancedSpriteBatchPipelineCache.size() + ")...");
        for (long pipelineHandle : instancedSpriteBatchPipelineCache.values()) {
            if (pipelineHandle != VK_NULL_HANDLE) {
                vkDestroyPipeline(rawDevice, pipelineHandle, null);
            }
        }
        instancedSpriteBatchPipelineCache.clear();
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Instanced SpriteBatch pipeline cache cleared.");

        if (shaderManager != null) {
            shaderManager.dispose();
            VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Disposed associated VulkanShaderManager.");
        }

        destroyPipelineCache();
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Pipeline manager disposed.");
    }

    private void createPipelineCache() {
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Creating Vulkan pipeline cache...");
        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            LongBuffer pCache = stack.mallocLong(1);
            int result = vkCreatePipelineCache(rawDevice, cacheInfo, null, pCache);
            if (result == VK_SUCCESS) {
                this.vkPipelineCacheHandle = pCache.get(0);
                VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Pipeline cache created: " + this.vkPipelineCacheHandle);
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
            VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Destroying Vulkan pipeline cache: " + vkPipelineCacheHandle);
            vkDestroyPipelineCache(rawDevice, vkPipelineCacheHandle, null);
            vkPipelineCacheHandle = VK_NULL_HANDLE;
        }
    }

    // Shader module loading methods (loadShaderModule, readFileToByteBuffer, createShaderModuleInternal) are REMOVED.
    // They are now part of VulkanShaderManager.

    public synchronized long getOrCreatePipelineLayout(long descriptorSetLayoutHandle) {
        if (descriptorSetLayoutHandle == VK_NULL_HANDLE) {
            throw new IllegalArgumentException("DescriptorSetLayout handle cannot be VK_NULL_HANDLE");
        }
        Long cachedLayout = pipelineLayoutCache.get(descriptorSetLayoutHandle);
        if (cachedLayout != null) return cachedLayout;

        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Creating new PipelineLayout for DescriptorSetLayout: " + descriptorSetLayoutHandle);
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayoutHandle));
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            vkCheck(vkCreatePipelineLayout(rawDevice, pipelineLayoutInfo, null, pPipelineLayout),
                    "Failed to create pipeline layout for DSL: " + descriptorSetLayoutHandle);
            long newPipelineLayoutHandle = pPipelineLayout.get(0);
            pipelineLayoutCache.put(descriptorSetLayoutHandle, newPipelineLayoutHandle);
            VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Created and cached PipelineLayout: " + newPipelineLayoutHandle);
            return newPipelineLayoutHandle;
        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to create pipeline layout for DSL: " + descriptorSetLayoutHandle, e);
        }
    }

    public synchronized long getOrCreateSpriteBatchPipeline(
            long batchPipelineLayoutHandle, long renderPassHandle,
            boolean blendingEnabled, int blendSrcFunc, int blendDstFunc,
            int blendSrcFuncAlpha, int blendDstFuncAlpha, boolean blendFuncSeparate)
    {
        if (batchPipelineLayoutHandle == VK_NULL_HANDLE || renderPassHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot create SpriteBatch pipeline with NULL layout or render pass handle.");
        }
        NonInstancedPipelineCacheKey key = new NonInstancedPipelineCacheKey(
                batchPipelineLayoutHandle, renderPassHandle, blendingEnabled,
                blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha
        );
        Long cachedPipeline = spriteBatchPipelineCache.get(key);
        if (cachedPipeline != null) return cachedPipeline;

        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Creating NEW (Non-Instanced) SpriteBatch graphics pipeline for Key Hash: " + key.hashCode());

        // Use ShaderManager to get shader modules
        FileHandle vertShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.vert.spv"); // Shader for non-instanced
        FileHandle fragShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.frag.spv"); // Shader for non-instanced
        long vertModuleHandle = shaderManager.getShaderModule(vertShaderFile);
        long fragModuleHandle = shaderManager.getShaderModule(fragShaderFile);

        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = createShaderStages(stack, vertModuleHandle, fragModuleHandle);
            VkPipelineVertexInputStateCreateInfo vertexInputInfo = createNonInstancedVertexInputInfo(stack);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = createInputAssemblyInfo(stack);
            VkPipelineViewportStateCreateInfo viewportState = createViewportInfo(stack);
            VkPipelineRasterizationStateCreateInfo rasterizer = createRasterizationInfo(stack).cullMode(VK_CULL_MODE_NONE);
            VkPipelineMultisampleStateCreateInfo multisampling = createMultisampleInfo(stack);
            VkPipelineDepthStencilStateCreateInfo depthStencil = createDepthStencilInfo(stack);
            VkPipelineDynamicStateCreateInfo dynamicState = createDynamicStateInfo(stack);
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = createColorBlendAttachmentState(stack, blendingEnabled, blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha);
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachment);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default().pStages(shaderStages).pVertexInputState(vertexInputInfo)
                    .pInputAssemblyState(inputAssembly).pViewportState(viewportState)
                    .pRasterizationState(rasterizer).pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil).pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState).layout(batchPipelineLayoutHandle)
                    .renderPass(renderPassHandle).subpass(0)
                    .basePipelineHandle(VK_NULL_HANDLE).basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            vkCheck(vkCreateGraphicsPipelines(rawDevice, this.vkPipelineCacheHandle, pipelineInfo, null, pGraphicsPipeline),
                    "Failed to create (Non-Instanced) SpriteBatch graphics pipeline");
            long newPipelineHandle = pGraphicsPipeline.get(0);
            spriteBatchPipelineCache.put(key, newPipelineHandle);
            VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"(Non-Instanced) SpriteBatch graphics pipeline created and cached: " + newPipelineHandle + " for Key Hash: " + key.hashCode());
            return newPipelineHandle;
        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to create (Non-Instanced) SpriteBatch graphics pipeline for Key Hash: " + key.hashCode(), e);
        }
    }

    public synchronized long getOrCreateSpriteBatchInstancedPipeline(
            long batchPipelineLayoutHandle, long renderPassHandle,
            boolean blendingEnabled, int blendSrcFunc, int blendDstFunc,
            int blendSrcFuncAlpha, int blendDstFuncAlpha, boolean blendFuncSeparate)
    {
        if (batchPipelineLayoutHandle == VK_NULL_HANDLE || renderPassHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot create Instanced SpriteBatch pipeline with NULL layout or render pass handle.");
        }
        InstancedPipelineCacheKey key = new InstancedPipelineCacheKey(
                batchPipelineLayoutHandle, renderPassHandle, blendingEnabled,
                blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha
        );
        Long cachedPipeline = instancedSpriteBatchPipelineCache.get(key);
        if (cachedPipeline != null) return cachedPipeline;

        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Creating NEW INSTANCED SpriteBatch graphics pipeline for Key Hash: " + key.hashCode());

        // Use ShaderManager to get shader modules
        FileHandle vertShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch_instanced.vert.spv");
        FileHandle fragShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch_instanced.frag.spv");
        long vertModuleHandle = shaderManager.getShaderModule(vertShaderFile);
        long fragModuleHandle = shaderManager.getShaderModule(fragShaderFile);

        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = createShaderStages(stack, vertModuleHandle, fragModuleHandle);
            VkPipelineVertexInputStateCreateInfo vertexInputInfo = createInstancedVertexInputInfo(stack);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = createInputAssemblyInfo(stack);
            VkPipelineViewportStateCreateInfo viewportState = createViewportInfo(stack);
            VkPipelineRasterizationStateCreateInfo rasterizer = createRasterizationInfo(stack).cullMode(VK_CULL_MODE_NONE);
            VkPipelineMultisampleStateCreateInfo multisampling = createMultisampleInfo(stack);
            VkPipelineDepthStencilStateCreateInfo depthStencil = createDepthStencilInfo(stack);
            VkPipelineDynamicStateCreateInfo dynamicState = createDynamicStateInfo(stack);
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = createColorBlendAttachmentState(stack, blendingEnabled, blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha);
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachment);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default().pStages(shaderStages).pVertexInputState(vertexInputInfo)
                    .pInputAssemblyState(inputAssembly).pViewportState(viewportState)
                    .pRasterizationState(rasterizer).pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil).pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState).layout(batchPipelineLayoutHandle)
                    .renderPass(renderPassHandle).subpass(0)
                    .basePipelineHandle(VK_NULL_HANDLE).basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            vkCheck(vkCreateGraphicsPipelines(rawDevice, this.vkPipelineCacheHandle, pipelineInfo, null, pGraphicsPipeline),
                    "Failed to create Instanced SpriteBatch graphics pipeline");
            long newPipelineHandle = pGraphicsPipeline.get(0);
            instancedSpriteBatchPipelineCache.put(key, newPipelineHandle);
            VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Instanced SpriteBatch graphics pipeline created and cached: " + newPipelineHandle + " for Key Hash: " + key.hashCode());
            return newPipelineHandle;
        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to create Instanced SpriteBatch graphics pipeline for Key Hash: " + key.hashCode(), e);
        }
    }

    private VkPipelineShaderStageCreateInfo.Buffer createShaderStages(MemoryStack stack, long vertModuleHandle, long fragModuleHandle) {
        VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        shaderStages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModuleHandle).pName(stack.UTF8("main"));
        shaderStages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModuleHandle).pName(stack.UTF8("main"));
        return shaderStages;
    }

    private VkPipelineVertexInputStateCreateInfo createNonInstancedVertexInputInfo(MemoryStack stack) {
        VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack)
                .binding(0).stride(VulkanSpriteBatch.BYTES_PER_VERTEX).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(4, stack);
        attributeDescriptions.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
        attributeDescriptions.get(1).binding(0).location(1).format(VK_FORMAT_R32_SFLOAT).offset(VulkanSpriteBatch.POSITION_COMPONENTS * Float.BYTES);
        attributeDescriptions.get(2).binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset((VulkanSpriteBatch.POSITION_COMPONENTS + VulkanSpriteBatch.COLOR_COMPONENTS) * Float.BYTES);
        attributeDescriptions.get(3).binding(0).location(3).format(VK_FORMAT_R32_SFLOAT).offset((VulkanSpriteBatch.POSITION_COMPONENTS + VulkanSpriteBatch.COLOR_COMPONENTS + VulkanSpriteBatch.TEXCOORD_COMPONENTS) * Float.BYTES);
        return VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                .pVertexBindingDescriptions(bindingDescription).pVertexAttributeDescriptions(attributeDescriptions);
    }

    private VkPipelineVertexInputStateCreateInfo createInstancedVertexInputInfo(MemoryStack stack) {
        VkVertexInputBindingDescription.Buffer bindingDescriptions = VkVertexInputBindingDescription.calloc(2, stack);
        bindingDescriptions.get(0).binding(0).stride(4 * Float.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        bindingDescriptions.get(1).binding(1).stride(VulkanSpriteBatchInstanced.BYTES_PER_INSTANCE).inputRate(VK_VERTEX_INPUT_RATE_INSTANCE);
        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(10, stack);
        attributeDescriptions.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
        attributeDescriptions.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(2 * Float.BYTES);
        int currentOffset = 0;
        attributeDescriptions.get(2).binding(1).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset(currentOffset); currentOffset += 2 * Float.BYTES;
        attributeDescriptions.get(3).binding(1).location(3).format(VK_FORMAT_R32G32_SFLOAT).offset(currentOffset); currentOffset += 2 * Float.BYTES;
        attributeDescriptions.get(4).binding(1).location(4).format(VK_FORMAT_R32G32_SFLOAT).offset(currentOffset); currentOffset += 2 * Float.BYTES;
        attributeDescriptions.get(5).binding(1).location(5).format(VK_FORMAT_R32G32_SFLOAT).offset(currentOffset); currentOffset += 2 * Float.BYTES;
        attributeDescriptions.get(6).binding(1).location(6).format(VK_FORMAT_R32_SFLOAT).offset(currentOffset);    currentOffset += 1 * Float.BYTES;
        attributeDescriptions.get(7).binding(1).location(7).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(currentOffset); currentOffset += 4 * Float.BYTES;
        attributeDescriptions.get(8).binding(1).location(8).format(VK_FORMAT_R32_SFLOAT).offset(currentOffset); currentOffset += 1 * Float.BYTES;
        attributeDescriptions.get(9).binding(1).location(9).format(VK_FORMAT_R32_SFLOAT).offset(currentOffset);
        return VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                .pVertexBindingDescriptions(bindingDescriptions).pVertexAttributeDescriptions(attributeDescriptions);
    }

    private VkPipelineInputAssemblyStateCreateInfo createInputAssemblyInfo(MemoryStack stack) {
        return VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
    }

    private VkPipelineViewportStateCreateInfo createViewportInfo(MemoryStack stack) {
        return VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
    }

    private VkPipelineRasterizationStateCreateInfo createRasterizationInfo(MemoryStack stack) {
        return VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                .depthClampEnable(false).rasterizerDiscardEnable(false).polygonMode(VK_POLYGON_MODE_FILL)
                .lineWidth(1.0f).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable(false);
    }

    private VkPipelineMultisampleStateCreateInfo createMultisampleInfo(MemoryStack stack) {
        return VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                .sampleShadingEnable(false).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
    }

    private VkPipelineDepthStencilStateCreateInfo createDepthStencilInfo(MemoryStack stack) {
        return VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                .depthTestEnable(false).depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                .depthBoundsTestEnable(false).stencilTestEnable(false);
    }

    private VkPipelineDynamicStateCreateInfo createDynamicStateInfo(MemoryStack stack) {
        IntBuffer pDynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
        return VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(pDynamicStates);
    }

    private VkPipelineColorBlendAttachmentState.Buffer createColorBlendAttachmentState(MemoryStack stack, boolean blendingEnabled, int blendSrcFunc, int blendDstFunc, int blendSrcFuncAlpha, int blendDstFuncAlpha) {
        VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                .blendEnable(blendingEnabled);
        if (blendingEnabled) {
            colorBlendAttachment.srcColorBlendFactor(mapGLBlendFactorToVulkan(blendSrcFunc))
                    .dstColorBlendFactor(mapGLBlendFactorToVulkan(blendDstFunc)).colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(mapGLBlendFactorToVulkan(blendSrcFuncAlpha))
                    .dstAlphaBlendFactor(mapGLBlendFactorToVulkan(blendDstFuncAlpha)).alphaBlendOp(VK_BLEND_OP_ADD);
        } else {
            colorBlendAttachment.srcColorBlendFactor(VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK_BLEND_FACTOR_ZERO).colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO).alphaBlendOp(VK_BLEND_OP_ADD);
        }
        return colorBlendAttachment;
    }

    private int mapGLBlendFactorToVulkan(int glBlendFactor) {
        switch (glBlendFactor) {
            case GL20.GL_ZERO: return VK_BLEND_FACTOR_ZERO;
            case GL20.GL_ONE: return VK_BLEND_FACTOR_ONE;
            case GL20.GL_SRC_COLOR: return VK_BLEND_FACTOR_SRC_COLOR;
            case GL20.GL_ONE_MINUS_SRC_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case GL20.GL_DST_COLOR: return VK_BLEND_FACTOR_DST_COLOR;
            case GL20.GL_ONE_MINUS_DST_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case GL20.GL_SRC_ALPHA: return VK_BLEND_FACTOR_SRC_ALPHA;
            case GL20.GL_ONE_MINUS_SRC_ALPHA: return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case GL20.GL_DST_ALPHA: return VK_BLEND_FACTOR_DST_ALPHA;
            case GL20.GL_ONE_MINUS_DST_ALPHA: return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case GL20.GL_SRC_ALPHA_SATURATE: return VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            default: Gdx.app.error(TAG, "Unsupported GL blend factor: " + glBlendFactor + ". Defaulting to ONE."); return VK_BLEND_FACTOR_ONE;
        }
    }
}


/*
package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20; // Needed for blend function constants
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects; // For Objects.hash

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

*/
/**
 * Manages the creation, caching, and cleanup of Vulkan Shader Modules,
 * Pipeline Layouts, and Graphics Pipelines.
 *//*

public class VulkanPipelineManager implements Disposable {
    private final String TAG = "VulkanPipelineManager";
    private final VulkanDevice device;
    private final VkDevice rawDevice;

    private final Map<String, Long> shaderModuleCache; // Cache: File path -> Module Handle
    private final Map<Long, Long> pipelineLayoutCache; // Cache: DescriptorSetLayout Handle -> PipelineLayout Handle
    private final Map<NonInstancedPipelineCacheKey, Long> spriteBatchPipelineCache; // Cache for non-instanced SpriteBatch
    private final Map<InstancedPipelineCacheKey, Long> instancedSpriteBatchPipelineCache; // Cache for instanced SpriteBatch
    private long vkPipelineCacheHandle = VK_NULL_HANDLE; // Vulkan's pipeline cache object

    // --- Cache Key Classes ---

    // Key for non-instanced pipelines (includes blending state) - RENAMED
    private static class NonInstancedPipelineCacheKey {
        final long layout;
        final long renderPass;
        final boolean blendingEnabled;
        final int blendSrcFunc;
        final int blendDstFunc;
        final int blendSrcFuncAlpha;
        final int blendDstFuncAlpha;

        NonInstancedPipelineCacheKey(long layout, long renderPass, boolean blendingEnabled, int blendSrcFunc, int blendDstFunc, int blendSrcFuncAlpha, int blendDstFuncAlpha) {
            this.layout = layout;
            this.renderPass = renderPass;
            this.blendingEnabled = blendingEnabled;
            this.blendSrcFunc = blendSrcFunc;
            this.blendDstFunc = blendDstFunc;
            this.blendSrcFuncAlpha = blendSrcFuncAlpha;
            this.blendDstFuncAlpha = blendDstFuncAlpha;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NonInstancedPipelineCacheKey that = (NonInstancedPipelineCacheKey) o;
            return layout == that.layout && renderPass == that.renderPass && blendingEnabled == that.blendingEnabled && blendSrcFunc == that.blendSrcFunc && blendDstFunc == that.blendDstFunc && blendSrcFuncAlpha == that.blendSrcFuncAlpha && blendDstFuncAlpha == that.blendDstFuncAlpha;
        }

        @Override
        public int hashCode() {
            return Objects.hash(layout, renderPass, blendingEnabled, blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha);
        }
    }

    // Key for instanced pipelines (includes blending state)
    private static class InstancedPipelineCacheKey {
        final long layout;
        final long renderPass;
        final boolean blendingEnabled;
        final int blendSrcFunc;
        final int blendDstFunc;
        final int blendSrcFuncAlpha;
        final int blendDstFuncAlpha;

        InstancedPipelineCacheKey(long layout, long renderPass, boolean blendingEnabled, int blendSrcFunc, int blendDstFunc, int blendSrcFuncAlpha, int blendDstFuncAlpha) {
            this.layout = layout;
            this.renderPass = renderPass;
            this.blendingEnabled = blendingEnabled;
            this.blendSrcFunc = blendSrcFunc;
            this.blendDstFunc = blendDstFunc;
            this.blendSrcFuncAlpha = blendSrcFuncAlpha;
            this.blendDstFuncAlpha = blendDstFuncAlpha;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            InstancedPipelineCacheKey that = (InstancedPipelineCacheKey) o;
            return layout == that.layout &&
                    renderPass == that.renderPass &&
                    blendingEnabled == that.blendingEnabled &&
                    blendSrcFunc == that.blendSrcFunc &&
                    blendDstFunc == that.blendDstFunc &&
                    blendSrcFuncAlpha == that.blendSrcFuncAlpha &&
                    blendDstFuncAlpha == that.blendDstFuncAlpha;
        }

        @Override
        public int hashCode() {
            return Objects.hash(layout, renderPass, blendingEnabled, blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha);
        }
    }

    // --- Constructor ---

    public VulkanPipelineManager(VulkanDevice device) {
        this.device = device;
        this.rawDevice = device.getRawDevice();
        this.shaderModuleCache = new HashMap<>();
        this.pipelineLayoutCache = new HashMap<>();
        this.spriteBatchPipelineCache = new HashMap<>();
        this.instancedSpriteBatchPipelineCache = new HashMap<>();

        createPipelineCache();
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Initialized.");
    }

    // --- Cleanup Methods ---

    @Override
    public void dispose() {
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Disposing pipeline manager...");

        // Clean up cached shader modules
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Cleaning up cached shader modules (" + shaderModuleCache.size() + ")...");
        for (Map.Entry<String, Long> entry : shaderModuleCache.entrySet()) {
            long moduleHandle = entry.getValue();
            if (moduleHandle != VK_NULL_HANDLE) {
                vkDestroyShaderModule(rawDevice, moduleHandle, null);
            }
        }
        shaderModuleCache.clear();
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Shader module cache cleared.");

        // Clean up cached pipeline layouts
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Cleaning up cached pipeline layouts (" + pipelineLayoutCache.size() + ")...");
        for (long layoutHandle : pipelineLayoutCache.values()) {
            if (layoutHandle != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(rawDevice, layoutHandle, null);
            }
        }
        pipelineLayoutCache.clear();
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Pipeline layout cache cleared.");

        // Clean up cached non-instanced SpriteBatch pipelines
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Cleaning up cached SpriteBatch pipelines (" + spriteBatchPipelineCache.size() + ")...");
        for (long pipelineHandle : spriteBatchPipelineCache.values()) {
            if (pipelineHandle != VK_NULL_HANDLE) {
                vkDestroyPipeline(rawDevice, pipelineHandle, null);
            }
        }
        spriteBatchPipelineCache.clear();
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"SpriteBatch pipeline cache cleared.");

        // Clean up cached instanced SpriteBatch pipelines
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Cleaning up cached Instanced SpriteBatch pipelines (" + instancedSpriteBatchPipelineCache.size() + ")...");
        for (long pipelineHandle : instancedSpriteBatchPipelineCache.values()) {
            if (pipelineHandle != VK_NULL_HANDLE) {
                vkDestroyPipeline(rawDevice, pipelineHandle, null);
            }
        }
        instancedSpriteBatchPipelineCache.clear();
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Instanced SpriteBatch pipeline cache cleared.");


        // Destroy Vulkan pipeline cache object
        destroyPipelineCache();

        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Pipeline manager disposed.");
    }

    private void createPipelineCache() {
        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Creating Vulkan pipeline cache...");
        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            LongBuffer pCache = stack.mallocLong(1);
            int result = vkCreatePipelineCache(rawDevice, cacheInfo, null, pCache);
            if (result == VK_SUCCESS) {
                this.vkPipelineCacheHandle = pCache.get(0);
                VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Pipeline cache created: " + this.vkPipelineCacheHandle);
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
            VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Destroying Vulkan pipeline cache: " + vkPipelineCacheHandle);
            vkDestroyPipelineCache(rawDevice, vkPipelineCacheHandle, null);
            vkPipelineCacheHandle = VK_NULL_HANDLE;
        }
    }

    // --- Shader Module Handling ---

    private long loadShaderModule(FileHandle shaderFile) {
        if (shaderFile == null) {
            throw new GdxRuntimeException("Shader FileHandle cannot be null.");
        }
        String pathKey = shaderFile.path();

        synchronized (shaderModuleCache) {
            Long cachedHandle = shaderModuleCache.get(pathKey);
            if (cachedHandle != null) {
                return cachedHandle;
            }

            VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Loading shader module from: " + pathKey);
            try {
                ByteBuffer shaderCode = readFileToByteBuffer(shaderFile);
                long moduleHandle = createShaderModuleInternal(shaderCode);
                shaderModuleCache.put(pathKey, moduleHandle);
                VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Shader module loaded and cached: " + moduleHandle + " [" + pathKey + "]");
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

    // --- Pipeline Layout Handling ---

    public synchronized long getOrCreatePipelineLayout(long descriptorSetLayoutHandle) {
        if (descriptorSetLayoutHandle == VK_NULL_HANDLE) {
            throw new IllegalArgumentException("DescriptorSetLayout handle cannot be VK_NULL_HANDLE");
        }

        Long cachedLayout = pipelineLayoutCache.get(descriptorSetLayoutHandle);
        if (cachedLayout != null) {
            return cachedLayout;
        }

        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Creating new PipelineLayout for DescriptorSetLayout: " + descriptorSetLayoutHandle);
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayoutHandle));

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            vkCheck(vkCreatePipelineLayout(rawDevice, pipelineLayoutInfo, null, pPipelineLayout),
                    "Failed to create pipeline layout for DSL: " + descriptorSetLayoutHandle);
            long newPipelineLayoutHandle = pPipelineLayout.get(0);

            pipelineLayoutCache.put(descriptorSetLayoutHandle, newPipelineLayoutHandle);
            VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Created and cached PipelineLayout: " + newPipelineLayoutHandle);
            return newPipelineLayoutHandle;

        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to create pipeline layout for DSL: " + descriptorSetLayoutHandle, e);
        }
    }

    // --- Pipeline Creation ---

    */
/**
 * Gets or creates a graphics pipeline specifically configured for the NON-INSTANCED VulkanSpriteBatch.
 * Uses a cache keyed by layout, render pass, and blending state.
 *
 * @param batchPipelineLayoutHandle The pipeline layout handle used by VulkanSpriteBatch.
 * @param renderPassHandle Handle to a compatible render pass.
 * @param blendingEnabled Whether blending should be enabled.
 * @param blendSrcFunc GL constant for source color blend factor.
 * @param blendDstFunc GL constant for destination color blend factor.
 * @param blendSrcFuncAlpha GL constant for source alpha blend factor.
 * @param blendDstFuncAlpha GL constant for destination alpha blend factor.
 * @param blendFuncSeparate Whether separate alpha blend functions are used.
 * @return The VkPipeline handle for the non-instanced SpriteBatch pipeline.
 *//*

    public synchronized long getOrCreateSpriteBatchPipeline(
            long batchPipelineLayoutHandle, long renderPassHandle,
            boolean blendingEnabled, int blendSrcFunc, int blendDstFunc,
            int blendSrcFuncAlpha, int blendDstFuncAlpha, boolean blendFuncSeparate) // Added blending parameters
    {
        if (batchPipelineLayoutHandle == VK_NULL_HANDLE || renderPassHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot create SpriteBatch pipeline with NULL layout or render pass handle.");
        }

        // Use the new cache key that includes blending state
        NonInstancedPipelineCacheKey key = new NonInstancedPipelineCacheKey(
                batchPipelineLayoutHandle, renderPassHandle, blendingEnabled,
                blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha
        );
        Long cachedPipeline = spriteBatchPipelineCache.get(key);
        if (cachedPipeline != null) {
            return cachedPipeline;
        }

        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Creating NEW (Non-Instanced) SpriteBatch graphics pipeline for Key Hash: " + key.hashCode());

        // --- Load Shaders (Use shaders appropriate for the non-instanced vertex format) ---
        // These shaders expect pos, color, uv, texArrayIndex as vertex inputs.
        FileHandle vertShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.vert.spv"); // Example name
        FileHandle fragShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.frag.spv"); // Example name
        long vertModuleHandle = loadShaderModule(vertShaderFile);
        long fragModuleHandle = loadShaderModule(fragShaderFile);

        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = createShaderStages(stack, vertModuleHandle, fragModuleHandle);

            // --- Vertex Input for Non-Instanced Batch ---
            VkPipelineVertexInputStateCreateInfo vertexInputInfo = createNonInstancedVertexInputInfo(stack);

            // --- Standard Pipeline Stages ---
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = createInputAssemblyInfo(stack);
            VkPipelineViewportStateCreateInfo viewportState = createViewportInfo(stack);
            VkPipelineRasterizationStateCreateInfo rasterizer = createRasterizationInfo(stack).cullMode(VK_CULL_MODE_NONE);
            VkPipelineMultisampleStateCreateInfo multisampling = createMultisampleInfo(stack);
            VkPipelineDepthStencilStateCreateInfo depthStencil = createDepthStencilInfo(stack);
            VkPipelineDynamicStateCreateInfo dynamicState = createDynamicStateInfo(stack);

            // --- Color Blending (Configurable) ---
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = createColorBlendAttachmentState(stack, blendingEnabled, blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha);
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachment);
            // --- End Color Blending ---

            // --- Create Graphics Pipeline ---
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
                    .layout(batchPipelineLayoutHandle)
                    .renderPass(renderPassHandle)
                    .subpass(0)
                    .basePipelineHandle(VK_NULL_HANDLE)
                    .basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            vkCheck(vkCreateGraphicsPipelines(rawDevice, this.vkPipelineCacheHandle, pipelineInfo, null, pGraphicsPipeline),
                    "Failed to create (Non-Instanced) SpriteBatch graphics pipeline");

            long newPipelineHandle = pGraphicsPipeline.get(0);
            spriteBatchPipelineCache.put(key, newPipelineHandle); // Use the key with blending info
            VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"(Non-Instanced) SpriteBatch graphics pipeline created and cached: " + newPipelineHandle + " for Key Hash: " + key.hashCode());
            return newPipelineHandle;

        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to create (Non-Instanced) SpriteBatch graphics pipeline for Key Hash: " + key.hashCode(), e);
        }
    }


    */
/**
 * Gets or creates a graphics pipeline specifically configured for the INSTANCED VulkanSpriteBatch.
 * Uses a cache keyed by layout, render pass, and blending state.
 * (Signature and implementation remain the same as before)
 *//*

    public synchronized long getOrCreateSpriteBatchInstancedPipeline(
            long batchPipelineLayoutHandle, long renderPassHandle,
            boolean blendingEnabled, int blendSrcFunc, int blendDstFunc,
            int blendSrcFuncAlpha, int blendDstFuncAlpha, boolean blendFuncSeparate) // blendFuncSeparate is implicitly handled by factors
    {
        if (batchPipelineLayoutHandle == VK_NULL_HANDLE || renderPassHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot create Instanced SpriteBatch pipeline with NULL layout or render pass handle.");
        }

        InstancedPipelineCacheKey key = new InstancedPipelineCacheKey(
                batchPipelineLayoutHandle, renderPassHandle, blendingEnabled,
                blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha
        );
        Long cachedPipeline = instancedSpriteBatchPipelineCache.get(key);
        if (cachedPipeline != null) {
            return cachedPipeline;
        }

        VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Creating NEW INSTANCED SpriteBatch graphics pipeline for Key Hash: " + key.hashCode());

        FileHandle vertShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch_instanced.vert.spv");
        FileHandle fragShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch_instanced.frag.spv");
        long vertModuleHandle = loadShaderModule(vertShaderFile);
        long fragModuleHandle = loadShaderModule(fragShaderFile);

        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = createShaderStages(stack, vertModuleHandle, fragModuleHandle);
            VkPipelineVertexInputStateCreateInfo vertexInputInfo = createInstancedVertexInputInfo(stack);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = createInputAssemblyInfo(stack);
            VkPipelineViewportStateCreateInfo viewportState = createViewportInfo(stack);
            VkPipelineRasterizationStateCreateInfo rasterizer = createRasterizationInfo(stack).cullMode(VK_CULL_MODE_NONE);
            VkPipelineMultisampleStateCreateInfo multisampling = createMultisampleInfo(stack);
            VkPipelineDepthStencilStateCreateInfo depthStencil = createDepthStencilInfo(stack);
            VkPipelineDynamicStateCreateInfo dynamicState = createDynamicStateInfo(stack);

            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = createColorBlendAttachmentState(stack, blendingEnabled, blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha);
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachment);

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
                    .layout(batchPipelineLayoutHandle)
                    .renderPass(renderPassHandle)
                    .subpass(0)
                    .basePipelineHandle(VK_NULL_HANDLE)
                    .basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            vkCheck(vkCreateGraphicsPipelines(rawDevice, this.vkPipelineCacheHandle, pipelineInfo, null, pGraphicsPipeline),
                    "Failed to create Instanced SpriteBatch graphics pipeline");

            long newPipelineHandle = pGraphicsPipeline.get(0);
            instancedSpriteBatchPipelineCache.put(key, newPipelineHandle);
            VulkanDebugLogger.debug(VulkanLogCategory.PIPELINE,"Instanced SpriteBatch graphics pipeline created and cached: " + newPipelineHandle + " for Key Hash: " + key.hashCode());
            return newPipelineHandle;

        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to create Instanced SpriteBatch graphics pipeline for Key Hash: " + key.hashCode(), e);
        }
    }

    // --- Private Helper Methods for Pipeline Creation ---

    private VkPipelineShaderStageCreateInfo.Buffer createShaderStages(MemoryStack stack, long vertModuleHandle, long fragModuleHandle) {
        VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        shaderStages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModuleHandle).pName(stack.UTF8("main"));
        shaderStages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModuleHandle).pName(stack.UTF8("main"));
        return shaderStages;
    }

    // Helper for NON-Instanced Vertex Input (Matches VulkanSpriteBatch.BATCH_VERTEX_ATTRIBUTES)
    private VkPipelineVertexInputStateCreateInfo createNonInstancedVertexInputInfo(MemoryStack stack) {
        VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack)
                .binding(0)
                .stride(VulkanSpriteBatch.BYTES_PER_VERTEX) // Use stride from non-instanced batch
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(4, stack); // 4 attributes

        attributeDescriptions.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0); // Position
        attributeDescriptions.get(1).binding(0).location(1).format(VK_FORMAT_R32_SFLOAT).offset(VulkanSpriteBatch.POSITION_COMPONENTS * Float.BYTES); // Color (packed)
        attributeDescriptions.get(2).binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset((VulkanSpriteBatch.POSITION_COMPONENTS + VulkanSpriteBatch.COLOR_COMPONENTS) * Float.BYTES); // UV
        attributeDescriptions.get(3).binding(0).location(3).format(VK_FORMAT_R32_SFLOAT).offset((VulkanSpriteBatch.POSITION_COMPONENTS + VulkanSpriteBatch.COLOR_COMPONENTS + VulkanSpriteBatch.TEXCOORD_COMPONENTS) * Float.BYTES); // TexIndex

        return VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                .pVertexBindingDescriptions(bindingDescription)
                .pVertexAttributeDescriptions(attributeDescriptions);
    }


    // Helper for Instanced Vertex Input (Matches VulkanSpriteBatch.INSTANCE_VERTEX_ATTRIBUTES)
    private VkPipelineVertexInputStateCreateInfo createInstancedVertexInputInfo(MemoryStack stack) {
        VkVertexInputBindingDescription.Buffer bindingDescriptions = VkVertexInputBindingDescription.calloc(2, stack);
        bindingDescriptions.get(0).binding(0).stride(4 * Float.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX); // Binding 0: pos(2)+uv(2)=4 floats
        bindingDescriptions.get(1).binding(1).stride(VulkanSpriteBatch.BYTES_PER_INSTANCE).inputRate(VK_VERTEX_INPUT_RATE_INSTANCE); // Binding 1: instance data

        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(10, stack); // 2 vert + 8 inst = 10 total

        // Per-Vertex Attributes (Binding 0)
        attributeDescriptions.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0); // in_localPos
        attributeDescriptions.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(2 * Float.BYTES); // in_localUV

        // Per-Instance Attributes (Binding 1) - Ensure locations match shader
        int currentOffset = 0;
        attributeDescriptions.get(2).binding(1).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset(currentOffset); // instance_worldPos
        currentOffset += 2 * Float.BYTES;
        attributeDescriptions.get(3).binding(1).location(3).format(VK_FORMAT_R32G32_SFLOAT).offset(currentOffset); // instance_size
        currentOffset += 2 * Float.BYTES;
        attributeDescriptions.get(4).binding(1).location(4).format(VK_FORMAT_R32G32_SFLOAT).offset(currentOffset); // instance_origin
        currentOffset += 2 * Float.BYTES;
        attributeDescriptions.get(5).binding(1).location(5).format(VK_FORMAT_R32G32_SFLOAT).offset(currentOffset); // instance_scale
        currentOffset += 2 * Float.BYTES;
        attributeDescriptions.get(6).binding(1).location(6).format(VK_FORMAT_R32_SFLOAT).offset(currentOffset);    // instance_rotation
        currentOffset += 1 * Float.BYTES;
        attributeDescriptions.get(7).binding(1).location(7).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(currentOffset); // instance_uvRegion (vec4)
        currentOffset += 4 * Float.BYTES;
        attributeDescriptions.get(8).binding(1).location(8).format(VK_FORMAT_R32_SFLOAT).offset(currentOffset); // instance_packedColor (float)
        currentOffset += 1 * Float.BYTES;
        attributeDescriptions.get(9).binding(1).location(9).format(VK_FORMAT_R32_SFLOAT).offset(currentOffset); // instance_texArrayIndex (float)

        return VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                .pVertexBindingDescriptions(bindingDescriptions)
                .pVertexAttributeDescriptions(attributeDescriptions);
    }


    private VkPipelineInputAssemblyStateCreateInfo createInputAssemblyInfo(MemoryStack stack) {
        return VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                .primitiveRestartEnable(false);
    }

    private VkPipelineViewportStateCreateInfo createViewportInfo(MemoryStack stack) {
        return VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                .viewportCount(1)
                .scissorCount(1);
    }

    private VkPipelineRasterizationStateCreateInfo createRasterizationInfo(MemoryStack stack) {
        return VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                .depthClampEnable(false)
                .rasterizerDiscardEnable(false)
                .polygonMode(VK_POLYGON_MODE_FILL)
                .lineWidth(1.0f)
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .depthBiasEnable(false);
        // Note: cullMode is set specifically in the pipeline creation methods
    }

    private VkPipelineMultisampleStateCreateInfo createMultisampleInfo(MemoryStack stack) {
        return VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                .sampleShadingEnable(false)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
    }

    private VkPipelineDepthStencilStateCreateInfo createDepthStencilInfo(MemoryStack stack) {
        return VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                .depthTestEnable(false)
                .depthWriteEnable(false)
                .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                .depthBoundsTestEnable(false)
                .stencilTestEnable(false);
    }

    private VkPipelineDynamicStateCreateInfo createDynamicStateInfo(MemoryStack stack) {
        IntBuffer pDynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
        return VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                .pDynamicStates(pDynamicStates);
    }

    // Helper to create the color blend attachment state based on parameters
    private VkPipelineColorBlendAttachmentState.Buffer createColorBlendAttachmentState(MemoryStack stack, boolean blendingEnabled, int blendSrcFunc, int blendDstFunc, int blendSrcFuncAlpha, int blendDstFuncAlpha) {
        VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                .blendEnable(blendingEnabled);

        if (blendingEnabled) {
            colorBlendAttachment
                    .srcColorBlendFactor(mapGLBlendFactorToVulkan(blendSrcFunc))
                    .dstColorBlendFactor(mapGLBlendFactorToVulkan(blendDstFunc))
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(mapGLBlendFactorToVulkan(blendSrcFuncAlpha))
                    .dstAlphaBlendFactor(mapGLBlendFactorToVulkan(blendDstFuncAlpha))
                    .alphaBlendOp(VK_BLEND_OP_ADD);
        } else {
            colorBlendAttachment
                    .srcColorBlendFactor(VK_BLEND_FACTOR_ONE)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ZERO)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                    .alphaBlendOp(VK_BLEND_OP_ADD);
        }
        return colorBlendAttachment;
    }


    // Helper to map GL blend factors to Vulkan blend factors
    private int mapGLBlendFactorToVulkan(int glBlendFactor) {
        switch (glBlendFactor) {
            case GL20.GL_ZERO: return VK_BLEND_FACTOR_ZERO;
            case GL20.GL_ONE: return VK_BLEND_FACTOR_ONE;
            case GL20.GL_SRC_COLOR: return VK_BLEND_FACTOR_SRC_COLOR;
            case GL20.GL_ONE_MINUS_SRC_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case GL20.GL_DST_COLOR: return VK_BLEND_FACTOR_DST_COLOR;
            case GL20.GL_ONE_MINUS_DST_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case GL20.GL_SRC_ALPHA: return VK_BLEND_FACTOR_SRC_ALPHA;
            case GL20.GL_ONE_MINUS_SRC_ALPHA: return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case GL20.GL_DST_ALPHA: return VK_BLEND_FACTOR_DST_ALPHA;
            case GL20.GL_ONE_MINUS_DST_ALPHA: return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case GL20.GL_SRC_ALPHA_SATURATE: return VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            default:
                Gdx.app.error(TAG, "Unsupported GL blend factor: " + glBlendFactor + ". Defaulting to ONE.");
                return VK_BLEND_FACTOR_ONE;
        }
    }
}
*/
