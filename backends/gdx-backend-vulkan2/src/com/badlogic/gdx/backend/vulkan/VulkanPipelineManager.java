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
    private final String logTag = "VulkanPipelineManager";
    private final VulkanDevice device;
    private final VkDevice rawDevice;

    // Handles managed by this class
    private long pipelineLayout = VK_NULL_HANDLE;
    private long graphicsPipeline = VK_NULL_HANDLE;
    private final Map<String, Long> shaderModuleCache; // Cache: File path -> Module Handle
    private long vkPipelineCacheHandle = VK_NULL_HANDLE; // Optional Vulkan pipeline cache object

    /**
     * Constructs a VulkanPipelineManager.
     *
     * @param device The VulkanDevice wrapper.
     */
    public VulkanPipelineManager(VulkanDevice device) {
        this.device = device;
        this.rawDevice = device.getRawDevice();
        this.shaderModuleCache = new HashMap<>();
        // Optional: Create VkPipelineCache for better performance
        createPipelineCache();
        Gdx.app.log(logTag, "Initialized.");
    }

    // --- Public Methods ---


    /**
     * Creates the default graphics pipeline and its layout based on shader files.
     * Cleans up any existing default pipeline/layout first.
     * Loads shader modules from the provided FileHandles, utilizing an internal cache.
     * Expects descriptor set layout to be created beforehand.
     *
     * @param vertShaderFile FileHandle for the vertex shader SPIR-V code.
     * @param fragShaderFile FileHandle for the fragment shader SPIR-V code.
     * @param descriptorSetLayoutHandle Handle to the descriptor set layout to use.
     * @param renderPassHandle Handle to a compatible render pass.
     */
    public void createDefaultPipeline(FileHandle vertShaderFile, FileHandle fragShaderFile, long descriptorSetLayoutHandle, long renderPassHandle) {
        // Load shader modules using the internal loader/cache
        long vertModuleHandle = loadShaderModule(vertShaderFile);
        long fragModuleHandle = loadShaderModule(fragShaderFile);

        // Validate input handles
        if (descriptorSetLayoutHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Descriptor Set Layout handle cannot be NULL for pipeline creation.");
        }
        if (renderPassHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("RenderPass handle cannot be NULL for pipeline creation.");
        }

        Gdx.app.log(logTag, "Creating default graphics pipeline...");

        // Cleanup existing layout and pipeline first to prevent leaks on recreation
        cleanupLayoutAndPipeline();

        try (MemoryStack stack = stackPush()) {

            // --- Create Pipeline Layout ---
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayoutHandle)); // Use provided layout

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            vkCheck(vkCreatePipelineLayout(rawDevice, pipelineLayoutInfo, null, pPipelineLayout),
                    "Failed to create pipeline layout");
            this.pipelineLayout = pPipelineLayout.get(0); // Store handle
            Gdx.app.log(logTag,"Created pipeline layout: " + this.pipelineLayout);

            // --- Define Pipeline States (Using internal helpers) ---
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = createShaderStages(stack, vertModuleHandle, fragModuleHandle);
            VkPipelineVertexInputStateCreateInfo vertexInputInfo = createVertexInputInfo(stack);
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
                    .pVertexInputState(vertexInputInfo).pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState).pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling).pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlending).pDynamicState(dynamicState)
                    .layout(this.pipelineLayout)   // Use the layout created above
                    .renderPass(renderPassHandle) // Use provided render pass
                    .subpass(0)
                    .basePipelineHandle(VK_NULL_HANDLE)
                    .basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            // Use the Vulkan pipeline cache handle if available
            vkCheck(vkCreateGraphicsPipelines(rawDevice, this.vkPipelineCacheHandle, pipelineInfo, null, pGraphicsPipeline),
                    "Failed to create graphics pipeline");
            this.graphicsPipeline = pGraphicsPipeline.get(0); // Store handle

            Gdx.app.log(logTag, "Default graphics pipeline created successfully: " + this.graphicsPipeline);

        } catch (Exception e) {
            // Ensure partial cleanup on failure
            cleanupLayoutAndPipeline(); // Clean up potentially created layout/pipeline
            throw new GdxRuntimeException("Failed to create default graphics pipeline", e);
        }
    }

    /**
     * Gets the handle of the currently managed default pipeline layout.
     * @return VkPipelineLayout handle, or VK_NULL_HANDLE if not created.
     */
    public long getPipelineLayout() {
        // Optional: Add check/warning if called when handle is null
        // if (pipelineLayout == VK_NULL_HANDLE) { Gdx.app.error(logTag, "getPipelineLayout() called when handle is NULL!"); }
        return pipelineLayout;
    }

    /**
     * Gets the handle of the currently managed default graphics pipeline.
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
        // Destroy Pipeline first as it depends on Layout
        if (graphicsPipeline != VK_NULL_HANDLE) {
            Gdx.app.log(logTag,"Destroying graphics pipeline: " + graphicsPipeline);
            vkDestroyPipeline(rawDevice, graphicsPipeline, null);
            graphicsPipeline = VK_NULL_HANDLE;
        }
        // Then destroy Layout
        if (pipelineLayout != VK_NULL_HANDLE) {
            Gdx.app.log(logTag,"Destroying pipeline layout: " + pipelineLayout);
            vkDestroyPipelineLayout(rawDevice, pipelineLayout, null);
            pipelineLayout = VK_NULL_HANDLE;
        }
    }

    /**
     * Disposes all resources managed by this manager, including cached shader modules,
     * the current pipeline/layout, and the Vulkan pipeline cache (if created).
     */
    @Override
    public void dispose() {
        Gdx.app.log(logTag,"Disposing pipeline manager...");
        cleanupLayoutAndPipeline(); // Clean current pipeline and layout first

        // Clean up cached shader modules
        Gdx.app.log(logTag,"Cleaning up cached shader modules (" + shaderModuleCache.size() + ")...");
        for (Map.Entry<String, Long> entry : shaderModuleCache.entrySet()) {
            long moduleHandle = entry.getValue();
            if (moduleHandle != VK_NULL_HANDLE) {
                Gdx.app.log(logTag, "Destroying shader module: " + moduleHandle + " (" + entry.getKey() + ")");
                vkDestroyShaderModule(rawDevice, moduleHandle, null);
            }
        }
        shaderModuleCache.clear();
        Gdx.app.log(logTag,"Shader module cache cleared.");

        // Optional: Destroy VkPipelineCache
        destroyPipelineCache();

        Gdx.app.log(logTag,"Pipeline manager disposed.");
    }


    // --- Private Shader Module Handling ---

    /**
     * Loads or retrieves a cached shader module.
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
                Gdx.app.debug(logTag, "Shader module cache hit for: " + pathKey);
                return cachedHandle;
            }

            Gdx.app.log(logTag, "Loading shader module from: " + pathKey);
            try {
                ByteBuffer shaderCode = readFileToByteBuffer(shaderFile); // Use helper
                long moduleHandle = createShaderModuleInternal(shaderCode); // Use helper
                shaderModuleCache.put(pathKey, moduleHandle); // Store in cache
                Gdx.app.log(logTag, "Shader module loaded and cached: " + moduleHandle + " [" + pathKey + "]");
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


    // --- Private Pipeline State Creation Helpers ---
    // (Contain the fixed state definitions for the default pipeline)

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

    // --- Optional Pipeline Cache ---
    private void createPipelineCache() {
        Gdx.app.log(logTag, "Creating Vulkan pipeline cache...");
        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            // TODO: Implement loading cache data from file for faster startup
            // cacheInfo.pInitialData(...)
            // cacheInfo.initialDataSize(...)
            LongBuffer pCache = stack.mallocLong(1);
            int result = vkCreatePipelineCache(rawDevice, cacheInfo, null, pCache);
            if (result == VK_SUCCESS) {
                this.vkPipelineCacheHandle = pCache.get(0);
                Gdx.app.log(logTag, "Pipeline cache created: " + this.vkPipelineCacheHandle);
            } else {
                Gdx.app.error(logTag, "Failed to create pipeline cache, result: " + VkResultDecoder.decode(result));
                this.vkPipelineCacheHandle = VK_NULL_HANDLE;
            }
        } catch (Exception e) {
            Gdx.app.error(logTag, "Exception creating pipeline cache", e);
            this.vkPipelineCacheHandle = VK_NULL_HANDLE;
        }
    }

    private void destroyPipelineCache() {
        if (vkPipelineCacheHandle != VK_NULL_HANDLE) {
            Gdx.app.log(logTag, "Destroying Vulkan pipeline cache: " + vkPipelineCacheHandle);
            // TODO: Implement saving cache data to file
            // vkGetPipelineCacheData(...)
            vkDestroyPipelineCache(rawDevice, vkPipelineCacheHandle, null);
            vkPipelineCacheHandle = VK_NULL_HANDLE;
        }
    }

} // End VulkanPipelineManager class