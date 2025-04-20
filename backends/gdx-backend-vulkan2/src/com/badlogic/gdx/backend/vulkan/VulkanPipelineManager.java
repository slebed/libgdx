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

/**
 * Manages the creation, caching (optional), and cleanup of Vulkan
 * Shader Modules, Pipeline Layouts, and Graphics Pipelines.
 * (Initial version handles one default pipeline configuration and basic shader caching).
 */
public class VulkanPipelineManager implements Disposable {
    private final String TAG = "VulkanPipelineManager";
    private final VulkanDevice device;
    private final VkDevice rawDevice;

    private long graphicsPipeline = VK_NULL_HANDLE;
    private final Map<String, Long> shaderModuleCache; // Cache: File path -> Module Handle
    private long vkPipelineCacheHandle = VK_NULL_HANDLE; // Optional Vulkan pipeline cache object
    private final Map<Long, Long> pipelineLayoutCache = new HashMap<>();

    private long spriteBatchPipeline = VK_NULL_HANDLE;

    /**
     * Constructs a VulkanPipelineManager.
     *
     * @param device The VulkanDevice wrapper.
     */
    public VulkanPipelineManager(VulkanDevice device) {
        this.device = device;
        this.rawDevice = device.getRawDevice();
        this.shaderModuleCache = new HashMap<>();

        createPipelineCache();
        Gdx.app.log(TAG, "Initialized.");
    }

    /**
     * Creates the default graphics pipeline based on shader files and layouts.
     * Cleans up any existing default graphics pipeline first. The pipeline layout
     * lifecycle is managed by the cache via getOrCreatePipelineLayout.
     * Loads shader modules from the provided FileHandles, utilizing an internal cache.
     * Expects descriptor set layout to be created beforehand.
     *
     * @param vertShaderFile            FileHandle for the vertex shader SPIR-V code.
     * @param fragShaderFile            FileHandle for the fragment shader SPIR-V code.
     * @param descriptorSetLayoutHandle Handle to the descriptor set layout to use for creating/retrieving the pipeline layout.
     * @param renderPassHandle          Handle to a compatible render pass.
     */
    public void createDefaultPipeline(FileHandle vertShaderFile, FileHandle fragShaderFile, long descriptorSetLayoutHandle, long renderPassHandle) {
        long vertModuleHandle = loadShaderModule(vertShaderFile);
        long fragModuleHandle = loadShaderModule(fragShaderFile);

        if (descriptorSetLayoutHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Descriptor Set Layout handle cannot be NULL for pipeline creation.");
        }
        if (renderPassHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("RenderPass handle cannot be NULL for pipeline creation.");
        }

        Gdx.app.log(TAG, "Creating default graphics pipeline...");

        // Cleanup existing default graphics pipeline first.
        // The corresponding Pipeline Layout is managed by the cache and cleaned in dispose().
        if (graphicsPipeline != VK_NULL_HANDLE) {
            Gdx.app.log(TAG, "Destroying previous default graphics pipeline: " + graphicsPipeline);
            vkDestroyPipeline(rawDevice, graphicsPipeline, null);
            graphicsPipeline = VK_NULL_HANDLE; // Reset the handle
        }

        try (MemoryStack stack = stackPush()) {

            // --- Get or Create Pipeline Layout via Cache ---
            // Obtain the pipeline layout handle using the caching method.
            // This ensures layout reuse and centralizes management.
            long layoutHandle = getOrCreatePipelineLayout(descriptorSetLayoutHandle); // <<< Uses the caching method
            Gdx.app.log(TAG, "Using pipeline layout: " + layoutHandle); // Log the handle being used

            // --- Define Pipeline States (Using internal helpers) ---
            // These helper methods define vertex input, blending, rasterization etc.
            // Ensure they match the requirements of your default shaders and rendering.
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = createShaderStages(stack, vertModuleHandle, fragModuleHandle);
            VkPipelineVertexInputStateCreateInfo vertexInputInfo = createVertexInputInfo(stack); // Ensure this matches SpriteBatch vertex format if used here
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = createInputAssemblyInfo(stack);
            VkPipelineViewportStateCreateInfo viewportState = createViewportInfo(stack);
            VkPipelineRasterizationStateCreateInfo rasterizer = createRasterizationInfo(stack);
            VkPipelineMultisampleStateCreateInfo multisampling = createMultisampleInfo(stack);
            VkPipelineDepthStencilStateCreateInfo depthStencil = createDepthStencilInfo(stack);
            VkPipelineColorBlendStateCreateInfo colorBlending = createColorBlendInfo(stack);
            VkPipelineDynamicStateCreateInfo dynamicState = createDynamicStateInfo(stack);

            // --- Create Graphics Pipeline ---
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .pStages(shaderStages)
                    .pVertexInputState(vertexInputInfo)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil) // null if not needed, but using helper is fine
                    .pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState) // null if no dynamic states
                    .layout(layoutHandle)        // <<< Use the handle obtained from getOrCreatePipelineLayout
                    .renderPass(renderPassHandle) // Must be compatible with this pipeline
                    .subpass(0)                   // Index of the subpass where this pipeline will be used
                    .basePipelineHandle(VK_NULL_HANDLE) // Optional: for pipeline derivatives
                    .basePipelineIndex(-1);             // Optional: for pipeline derivatives

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            // Use the Vulkan pipeline cache object (vkPipelineCacheHandle) if available for potentially faster creation
            vkCheck(vkCreateGraphicsPipelines(rawDevice, this.vkPipelineCacheHandle, pipelineInfo, null, pGraphicsPipeline),
                    "Failed to create graphics pipeline");
            this.graphicsPipeline = pGraphicsPipeline.get(0); // Store handle for the newly created default pipeline

            Gdx.app.log(TAG, "Default graphics pipeline created successfully: " + this.graphicsPipeline);

        } catch (Exception e) {
            // If pipeline creation fails partway, ensure the graphicsPipeline handle is reset.
            // The layout is managed by the cache, no need to clean it here.
            if (graphicsPipeline != VK_NULL_HANDLE) {
                // This might not be strictly necessary if vkCreateGraphicsPipelines fails cleanly,
                // but it's safer to ensure the handle is null on failure.
                vkDestroyPipeline(rawDevice, graphicsPipeline, null);
                graphicsPipeline = VK_NULL_HANDLE;
            }
            throw new GdxRuntimeException("Failed to create default graphics pipeline", e);
        }
    }

    /**
     * Gets the handle of the currently managed default graphics pipeline.
     *
     * @return VkPipeline handle, or VK_NULL_HANDLE if not created.
     */
    public long getGraphicsPipeline() {
        // Optional: Add check/warning if called when handle is null
        // if (graphicsPipeline == VK_NULL_HANDLE) { Gdx.app.error(logTag, "getGraphicsPipeline() called when handle is NULL!"); }
        return graphicsPipeline;
    }

    /**
     * Destroys the currently managed graphics pipeline and pipeline layout.
     * Safe to call even if handles are VK_NULL_HANDLE.
     */
    public void cleanupLayoutAndPipeline() {
        if (graphicsPipeline != VK_NULL_HANDLE) {
            Gdx.app.log(TAG, "Destroying graphics pipeline: " + graphicsPipeline);
            vkDestroyPipeline(rawDevice, graphicsPipeline, null);
            graphicsPipeline = VK_NULL_HANDLE; // Reset the handle
        }
    }

    /**
     * Disposes all resources managed by this manager, including cached shader modules,
     * the current pipeline/layout, and the Vulkan pipeline cache (if created).
     */
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

        // Destroy SpriteBatch pipeline if created
        if (spriteBatchPipeline != VK_NULL_HANDLE) {
            Gdx.app.log(TAG, "Destroying SpriteBatch graphics pipeline: " + spriteBatchPipeline);
            vkDestroyPipeline(rawDevice, spriteBatchPipeline, null);
            spriteBatchPipeline = VK_NULL_HANDLE;
        }

        // Optional: Destroy VkPipelineCache
        destroyPipelineCache();

        Gdx.app.log(TAG, "Pipeline manager disposed.");
    }

    /**
     * Loads or retrieves a cached shader module.
     *
     * @param shaderFile FileHandle for the shader.
     * @return VkShaderModule handle.
     */
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
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code);

            LongBuffer pShaderModule = stack.mallocLong(1);
            vkCheck(vkCreateShaderModule(rawDevice, createInfo, null, pShaderModule),
                    "Failed to create shader module.");
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
        VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack)
                .binding(0).stride(7 * Float.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(3, stack);
        attributeDescriptions.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0); // Pos (vec2)
        attributeDescriptions.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32_SFLOAT).offset(2 * Float.BYTES); // Color (vec3)
        attributeDescriptions.get(2).binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset(5 * Float.BYTES); // TexCoord (vec2)

        return VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType$Default()
                .pVertexBindingDescriptions(bindingDescription)
                .pVertexAttributeDescriptions(attributeDescriptions);
    }

    private VkPipelineInputAssemblyStateCreateInfo createInputAssemblyInfo(MemoryStack stack) {
        return VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType$Default()
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                .primitiveRestartEnable(false);
    }

    private VkPipelineViewportStateCreateInfo createViewportInfo(MemoryStack stack) {
        // Viewport and Scissor are dynamic states, so counts matter but contents don't here.
        return VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType$Default()
                .viewportCount(1)
                .scissorCount(1);
    }

    private VkPipelineRasterizationStateCreateInfo createRasterizationInfo(MemoryStack stack) {
        return VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default()
                .depthClampEnable(false)
                .rasterizerDiscardEnable(false)
                .polygonMode(VK_POLYGON_MODE_FILL)
                .lineWidth(1.0f)
                .cullMode(VK_CULL_MODE_NONE) // No culling for simple quad example
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE) // Match vertex data winding
                .depthBiasEnable(false);
    }

    private VkPipelineMultisampleStateCreateInfo createMultisampleInfo(MemoryStack stack) {
        return VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default()
                .sampleShadingEnable(false)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT); // No MSAA
    }

    private VkPipelineDepthStencilStateCreateInfo createDepthStencilInfo(MemoryStack stack) {
        return VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType$Default()
                .depthTestEnable(false) // No depth testing
                .depthWriteEnable(false)
                .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL) // Irrelevant if test disabled
                .depthBoundsTestEnable(false)
                .stencilTestEnable(false);
    }

    private VkPipelineColorBlendStateCreateInfo createColorBlendInfo(MemoryStack stack) {
        VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
        colorBlendAttachment.get(0)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                .blendEnable(false); // No blending (opaque overwrite)

        return VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType$Default()
                .logicOpEnable(false)
                .pAttachments(colorBlendAttachment);
    }

    private VkPipelineDynamicStateCreateInfo createDynamicStateInfo(MemoryStack stack) {
        IntBuffer pDynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
        return VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType$Default()
                .pDynamicStates(pDynamicStates);
    }

    private void createPipelineCache() {
        Gdx.app.log(TAG, "Creating Vulkan pipeline cache...");
        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            // TODO: Implement loading cache data from file for faster startup
            // cacheInfo.pInitialData(...)
            // cacheInfo.initialDataSize(...)
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

    /**
     * Gets an existing VkPipelineLayout for the given descriptor set layout,
     * or creates and caches a new one if it doesn't exist.
     *
     * @param descriptorSetLayoutHandle The handle of the VkDescriptorSetLayout.
     * @return The handle of the corresponding VkPipelineLayout.
     */
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
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
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
     * Assumes standard alpha blending and vertex layout defined in VulkanSpriteBatch.
     * NOTE: Simple implementation assumes pipeline compatibility with the provided renderPassHandle.
     * More complex caching might be needed if used with multiple different render passes.
     *
     * @param batchDescriptorSetLayoutHandle The descriptor set layout handle used by VulkanSpriteBatch.
     * @param renderPassHandle               Handle to a compatible render pass.
     * @return The VkPipeline handle for the SpriteBatch pipeline.
     */
    public synchronized long getOrCreateSpriteBatchPipeline(long batchDescriptorSetLayoutHandle, long renderPassHandle) {
        // Basic Caching: If already created, return it.
        // Assumes renderPassHandle is compatible. If not, caching needs to be more complex (e.g., Map<Long, Long> renderPassToPipelineCache)
        if (this.spriteBatchPipeline != VK_NULL_HANDLE) {
            return this.spriteBatchPipeline;
        }

        Gdx.app.log(TAG, "getOrCreateSpriteBatchPipeline()\t" + "Creating SpriteBatch graphics pipeline...");

        FileHandle vertShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.vert.spv"); // Adjust path if needed
        FileHandle fragShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.frag.spv"); // Adjust path if needed

        if (!vertShaderFile.exists()) {
            throw new GdxRuntimeException("SpriteBatch VERTEX shader not found at: " + vertShaderFile.path());
        }
        if (!fragShaderFile.exists()) {
            throw new GdxRuntimeException("SpriteBatch FRAGMENT shader not found at: " + fragShaderFile.path());
        }
        long vertModuleHandle = loadShaderModule(vertShaderFile);
        long fragModuleHandle = loadShaderModule(fragShaderFile);

        // Validate input handles
        if (batchDescriptorSetLayoutHandle == VK_NULL_HANDLE || renderPassHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot create SpriteBatch pipeline with NULL layout or render pass handle.");
        }

        try (MemoryStack stack = stackPush()) {
            long pipelineLayoutHandle = getOrCreatePipelineLayout(batchDescriptorSetLayoutHandle); // Use the layout cache

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = createShaderStages(stack, vertModuleHandle, fragModuleHandle); // Standard helper likely OK

            VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0)
                    .stride(VulkanSpriteBatch.COMPONENTS_PER_VERTEX * Float.BYTES) // 5 * 4 = 20 bytes
                    .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(3, stack);
            attributeDescriptions.get(0) // Position (vec2)
                    .binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
            attributeDescriptions.get(1) // Color (packed float -> interpreted as vec4 in shader)
                    .binding(0).location(1).format(VK_FORMAT_R32_SFLOAT).offset(VulkanSpriteBatch.POSITION_COMPONENTS * Float.BYTES); // Offset 8
            attributeDescriptions.get(2) // TexCoord (vec2)
                    .binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset((VulkanSpriteBatch.POSITION_COMPONENTS + VulkanSpriteBatch.COLOR_COMPONENTS) * Float.BYTES); // Offset 12 (2*4 + 1*4)

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pVertexBindingDescriptions(bindingDescription)
                    .pVertexAttributeDescriptions(attributeDescriptions);

            // Input Assembly (Standard triangles)
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = createInputAssemblyInfo(stack); // Standard helper likely OK

            // Viewport/Scissor (Dynamic)
            VkPipelineViewportStateCreateInfo viewportState = createViewportInfo(stack); // Standard helper likely OK

            // Rasterizer (No culling usually for 2D)
            VkPipelineRasterizationStateCreateInfo rasterizer = createRasterizationInfo(stack) // Use helper but override cull mode
                    .cullMode(VK_CULL_MODE_NONE); // Ensure no backface culling

            // Multisampling (Usually off)
            VkPipelineMultisampleStateCreateInfo multisampling = createMultisampleInfo(stack); // Standard helper likely OK

            // Depth/Stencil (Usually off for SpriteBatch)
            VkPipelineDepthStencilStateCreateInfo depthStencil = createDepthStencilInfo(stack) // Use helper but ensure disabled
                    .depthTestEnable(false)
                    .depthWriteEnable(false);

            // Color Blending (CRUCIAL - Enable Alpha Blending)
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.get(0)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(true) // Enable blending
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE) // Optional: Often One/Zero or One/OneMinusSrcAlpha for alpha
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                    .alphaBlendOp(VK_BLEND_OP_ADD);

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .logicOpEnable(false)
                    .attachmentCount(colorBlendAttachment.remaining())
                    .pAttachments(colorBlendAttachment);
            // Optional: Set blend constants if needed

            // Dynamic States (Viewport, Scissor)
            VkPipelineDynamicStateCreateInfo dynamicState = createDynamicStateInfo(stack);

            // --- Create Graphics Pipeline ---
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .pStages(shaderStages)
                    .pVertexInputState(vertexInputInfo).pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState).pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling).pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlending).pDynamicState(dynamicState)
                    .layout(pipelineLayoutHandle)
                    .renderPass(renderPassHandle)
                    .subpass(0)
                    .basePipelineHandle(VK_NULL_HANDLE).basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            vkCheck(vkCreateGraphicsPipelines(rawDevice, this.vkPipelineCacheHandle, pipelineInfo, null, pGraphicsPipeline), "Failed to create SpriteBatch graphics pipeline");

            this.spriteBatchPipeline = pGraphicsPipeline.get(0); // Store handle
            // Optional: Store layout handle used for validation
            // this.spriteBatchLayoutHandle = batchDescriptorSetLayoutHandle;
            Gdx.app.log(TAG, "SpriteBatch graphics pipeline created successfully: " + this.spriteBatchPipeline);

            return this.spriteBatchPipeline;

        } catch (Exception e) {
            // Reset handle on failure
            this.spriteBatchPipeline = VK_NULL_HANDLE;
            throw new GdxRuntimeException("Failed to create SpriteBatch graphics pipeline", e);
        }
    }

}