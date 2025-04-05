package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the creation and cleanup of Vulkan Pipeline Layouts and Graphics Pipelines.
 * (Initial version handles one default pipeline configuration)
 */
public class VulkanPipelineManager implements Disposable {
    private final String logTag = "VulkanPipelineManager";
    private final VulkanDevice device;
    private final VkDevice rawDevice;

    // Handles managed by this class
    private long pipelineLayout = VK_NULL_HANDLE;
    private long graphicsPipeline = VK_NULL_HANDLE;
    // Optional: private long pipelineCache = VK_NULL_HANDLE; // For VkPipelineCache

    public VulkanPipelineManager(VulkanDevice device) {
        this.device = device;
        this.rawDevice = device.getRawDevice();
        // Optional: Create VkPipelineCache here
        // try (MemoryStack stack = stackPush()) {
        //    VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
        //    LongBuffer pCache = stack.mallocLong(1);
        //    vkCheck(vkCreatePipelineCache(rawDevice, cacheInfo, null, pCache), "Failed to create pipeline cache");
        //    this.pipelineCache = pCache.get(0);
        // }
    }

    /**
     * Creates the default graphics pipeline and its layout.
     * Cleans up any existing pipeline/layout first.
     * Expects shader modules and descriptor set layout to be created beforehand.
     *
     * @param vertShaderModule Handle to the vertex shader module.
     * @param fragShaderModule Handle to the fragment shader module.
     * @param descriptorSetLayoutHandle Handle to the descriptor set layout.
     * @param renderPassHandle Handle to a compatible render pass.
     */
    public void createDefaultPipeline(long vertShaderModule, long fragShaderModule, long descriptorSetLayoutHandle, long renderPassHandle) {
        if (vertShaderModule == VK_NULL_HANDLE || fragShaderModule == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Shader modules cannot be NULL for pipeline creation.");
        }
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

            // --- Define Pipeline States (Copied & Adapted from VulkanGraphics) ---

            // 1. Shader Stages
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = createShaderStages(stack, vertShaderModule, fragShaderModule);

            // 2. Vertex Input State (Hardcoded for textured quad example)
            VkPipelineVertexInputStateCreateInfo vertexInputInfo = createVertexInputInfo(stack);

            // 3. Input Assembly State (Triangle List)
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = createInputAssemblyInfo(stack);

            // 4. Viewport State (Dynamic)
            VkPipelineViewportStateCreateInfo viewportState = createViewportInfo(stack);

            // 5. Rasterization State (Fill, No Cull)
            VkPipelineRasterizationStateCreateInfo rasterizer = createRasterizationInfo(stack);

            // 6. Multisample State (No MSAA)
            VkPipelineMultisampleStateCreateInfo multisampling = createMultisampleInfo(stack);

            // 7. Depth/Stencil State (Disabled)
            VkPipelineDepthStencilStateCreateInfo depthStencil = createDepthStencilInfo(stack);

            // 8. Color Blend State (No Blend)
            VkPipelineColorBlendStateCreateInfo colorBlending = createColorBlendInfo(stack);

            // 9. Dynamic State (Viewport, Scissor)
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
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState)
                    .layout(this.pipelineLayout) // Use the layout created above
                    .renderPass(renderPassHandle) // Use provided render pass
                    .subpass(0)
                    .basePipelineHandle(VK_NULL_HANDLE)
                    .basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            // Use the optional pipeline cache handle if created
            long cacheHandle = VK_NULL_HANDLE; // Replace with this.pipelineCache if using cache
            vkCheck(vkCreateGraphicsPipelines(rawDevice, cacheHandle, pipelineInfo, null, pGraphicsPipeline),
                    "Failed to create graphics pipeline");
            this.graphicsPipeline = pGraphicsPipeline.get(0); // Store handle

            Gdx.app.log(logTag, "Default graphics pipeline and layout created successfully.");

        } catch (Exception e) {
            // Ensure partial cleanup on failure
            cleanupLayoutAndPipeline(); // Clean up potentially created layout/pipeline
            throw new GdxRuntimeException("Failed to create default graphics pipeline", e);
        }
    }

    // --- Getters ---
    public long getPipelineLayout() {
        if (pipelineLayout == VK_NULL_HANDLE) {
            // Or maybe throw an exception if called before creation?
            Gdx.app.error(logTag,"getPipelineLayout() called but handle is NULL!");
        }
        return pipelineLayout;
    }

    public long getGraphicsPipeline() {
        if (graphicsPipeline == VK_NULL_HANDLE) {
            Gdx.app.error(logTag,"getGraphicsPipeline() called but handle is NULL!");
        }
        return graphicsPipeline;
    }


    // --- Internal Helper Methods for Pipeline State Creation ---
    // (These directly contain the state logic previously in VulkanGraphics.createGraphicsPipeline)

    private VkPipelineShaderStageCreateInfo.Buffer createShaderStages(MemoryStack stack, long vert, long frag) {
        VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        shaderStages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
        shaderStages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));
        return shaderStages;
    }

    private VkPipelineVertexInputStateCreateInfo createVertexInputInfo(MemoryStack stack) {
        VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack);
        bindingDescription.get(0).binding(0).stride(7 * Float.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(3, stack);
        attributeDescriptions.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0); // Pos
        attributeDescriptions.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32_SFLOAT).offset(2 * Float.BYTES); // Color
        attributeDescriptions.get(2).binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset(5 * Float.BYTES); // TexCoord

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
                .cullMode(VK_CULL_MODE_NONE) // Assuming simple quad, adjust if needed
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE) // Match vertex winding
                .depthBiasEnable(false);
    }

    private VkPipelineMultisampleStateCreateInfo createMultisampleInfo(MemoryStack stack) {
        return VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default()
                .sampleShadingEnable(false)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
    }

    private VkPipelineDepthStencilStateCreateInfo createDepthStencilInfo(MemoryStack stack) {
        return VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType$Default()
                .depthTestEnable(false) // No depth test for simple quad
                .depthWriteEnable(false)
                .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                .depthBoundsTestEnable(false)
                .stencilTestEnable(false);
    }

    private VkPipelineColorBlendStateCreateInfo createColorBlendInfo(MemoryStack stack) {
        VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
        colorBlendAttachment.get(0)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                .blendEnable(false); // No blending

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


    /**
     * Destroys the managed graphics pipeline and pipeline layout.
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

    @Override
    public void dispose() {
        Gdx.app.log(logTag,"Disposing pipeline manager...");
        cleanupLayoutAndPipeline();
        // Optional: Destroy VkPipelineCache
        // if (pipelineCache != VK_NULL_HANDLE) {
        //     vkDestroyPipelineCache(rawDevice, pipelineCache, null);
        //     pipelineCache = VK_NULL_HANDLE;
        // }
        Gdx.app.log(logTag,"Pipeline manager disposed.");
    }
}