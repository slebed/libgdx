package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanShaderPipelineBundle implements Disposable {
    private static final String TAG = "VkShaderPipelineBundle";
    private static final boolean DEBUG = true;

    private final VulkanDevice vulkanDevice;
    private final VkDevice rawDevice;

    private long vertShaderModule = VK_NULL_HANDLE;
    private long fragShaderModule = VK_NULL_HANDLE;

    private final long[] descriptorSetLayouts; // Handles for Set 0 and Set 1 layouts
    private long pipelineLayout = VK_NULL_HANDLE;
    private long graphicsPipeline = VK_NULL_HANDLE;

    public static class Config {
        public FileHandle vertexShaderFile;
        public FileHandle fragmentShaderFile;
        public VulkanVertexAttributes vertexAttributes;

        public static class BindingConfigPojo {
            public int binding;
            public int descriptorType;
            public int descriptorCount = 1; // Default to 1
            public int stageFlags;
        }
        // Bindings for Descriptor Set 0
        public BindingConfigPojo[] descriptorSet0_Bindings;
        // Bindings for Descriptor Set 1
        public BindingConfigPojo[] descriptorSet1_Bindings;
        // Optional: public List<BindingConfigPojo[]> allDescriptorSetBindings; for more sets

        public VkPushConstantRange.Buffer pushConstantRanges;
        public long compatibleRenderPass;
        public int subpassIndex = 0;

        public int primitiveTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        public boolean primitiveRestartEnable = false;
        public int polygonMode = VK_POLYGON_MODE_FILL;
        public int cullMode = VK_CULL_MODE_BACK_BIT;
        public int frontFace = VK_FRONT_FACE_CLOCKWISE;
        public boolean depthBiasEnable = false;
        public float lineWidth = 1.0f;
        public int rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        public boolean sampleShadingEnable = false;
        public boolean depthTestEnable = true;
        public boolean depthWriteEnable = true;
        public int depthCompareOp = VK_COMPARE_OP_LESS;
        public boolean stencilTestEnable = false;
        public VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachments;
        public boolean colorLogicOpEnable = false;
        public IntBuffer dynamicStates;
    }

    public VulkanShaderPipelineBundle(VulkanDevice device,
                                      VulkanShaderManager shaderManager,
                                      VulkanPipelineManager pipelineManager,
                                      Config config) {

        this.vulkanDevice = Objects.requireNonNull(device, "VulkanDevice cannot be null.");
        this.rawDevice = device.getLogicalDevice();
        Objects.requireNonNull(shaderManager, "VulkanShaderManager cannot be null.");
        Objects.requireNonNull(pipelineManager, "VulkanPipelineManager cannot be null.");
        Objects.requireNonNull(config, "Config cannot be null.");
        // ... (other null checks for essential config fields) ...

        String bundleName = (config.vertexShaderFile != null ? config.vertexShaderFile.nameWithoutExtension() : "unknown_v") + "_" +
                (config.fragmentShaderFile != null ? config.fragmentShaderFile.nameWithoutExtension() : "unknown_f");
        if (DEBUG) Gdx.app.log(TAG, "Creating pipeline bundle for: " + bundleName);

        this.descriptorSetLayouts = new long[2]; // For Set 0 and Set 1

        boolean success = false;
        try (MemoryStack stack = stackPush()) {
            // 1. Load Shader Modules
            if (config.vertexShaderFile == null || config.fragmentShaderFile == null) {
                throw new GdxRuntimeException("Vertex and Fragment shader files must be provided in Config.");
            }
            this.vertShaderModule = shaderManager.getShaderModuleFromGlsl(config.vertexShaderFile, Shaderc.shaderc_vertex_shader);
            this.fragShaderModule = shaderManager.getShaderModuleFromGlsl(config.fragmentShaderFile, Shaderc.shaderc_fragment_shader);
            if (this.vertShaderModule == VK_NULL_HANDLE || this.fragShaderModule == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Failed to create shader modules for bundle: " + bundleName);
            }
            if (DEBUG) Gdx.app.debug(TAG, "  Shader modules loaded for " + bundleName);

            // 2. Create VkDescriptorSetLayouts
            this.descriptorSetLayouts[0] = createDescriptorSetLayoutInternal(rawDevice, config.descriptorSet0_Bindings, stack, bundleName + "_Set0");
            this.descriptorSetLayouts[1] = createDescriptorSetLayoutInternal(rawDevice, config.descriptorSet1_Bindings, stack, bundleName + "_Set1");
            if (DEBUG) {
                Gdx.app.debug(TAG, "  DSL for Set 0 created: " + this.descriptorSetLayouts[0]);
                Gdx.app.debug(TAG, "  DSL for Set 1 created: " + this.descriptorSetLayouts[1]);
            }
            ArrayList<Long> validLayoutHandles = new ArrayList<>();
            if (this.descriptorSetLayouts[0] != VK_NULL_HANDLE) {
                validLayoutHandles.add(this.descriptorSetLayouts[0]);
            }
            if (this.descriptorSetLayouts[1] != VK_NULL_HANDLE) {
                // If shaders only use Set 0, this.descriptorSetLayouts[1] might be VK_NULL_HANDLE
                // and shouldn't be added if your Config reflects that.
                validLayoutHandles.add(this.descriptorSetLayouts[1]);
            }

            // 3. Create VkPipelineLayout using all defined DescriptorSetLayouts
            // Count how many valid DSLs we have to pass to pSetLayouts
            int validDslCount = 0;
            if (this.descriptorSetLayouts[0] != VK_NULL_HANDLE) validDslCount++;
            if (this.descriptorSetLayouts[1] != VK_NULL_HANDLE) validDslCount++;
            // Extend this if you support more than 2 sets

            LongBuffer pSetLayoutArray = null;
            if (validDslCount > 0) {
                pSetLayoutArray = stack.mallocLong(validDslCount);
                int currentIdx = 0;
                if (this.descriptorSetLayouts[0] != VK_NULL_HANDLE) pSetLayoutArray.put(currentIdx++, this.descriptorSetLayouts[0]);
                if (this.descriptorSetLayouts[1] != VK_NULL_HANDLE) pSetLayoutArray.put(currentIdx++, this.descriptorSetLayouts[1]);
                pSetLayoutArray.flip(); // Prepare for reading
            }

            if (DEBUG) {
                if (config.dynamicStates != null && config.dynamicStates.hasRemaining()) {
                    StringBuilder dsString = new StringBuilder("Config provided dynamicStates: ");
                    for (int i = 0; i < config.dynamicStates.limit(); i++) {
                        dsString.append(config.dynamicStates.get(i)).append(" ");
                    }
                    Gdx.app.debug(TAG, dsString.toString() + "(Viewport=" + VK_DYNAMIC_STATE_VIEWPORT + ", Scissor=" + VK_DYNAMIC_STATE_SCISSOR + ")");
                } else {
                    Gdx.app.debug(TAG, "Config.dynamicStates is null or empty, defaulting to dynamic Viewport & Scissor.");
                }
            }

            LongBuffer pSetLayoutsForPipeline = null;
            if (!validLayoutHandles.isEmpty()) {
                pSetLayoutsForPipeline = stack.mallocLong(validLayoutHandles.size());
                for (Long handle : validLayoutHandles) {
                    pSetLayoutsForPipeline.put(handle);
                }
                pSetLayoutsForPipeline.flip(); // Prepare for reading by pSetLayouts
            }

            VkPipelineLayoutCreateInfo pipelineLayoutCI = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pSetLayoutsForPipeline); // This correctly sets count and pointers

            if (config.pushConstantRanges != null && config.pushConstantRanges.hasRemaining()) {
                pipelineLayoutCI.pPushConstantRanges(config.pushConstantRanges);
            }

            LongBuffer pPl = stack.mallocLong(1);
            vkCheck(vkCreatePipelineLayout(rawDevice, pipelineLayoutCI, null, pPl),
                    "Failed to create Pipeline Layout for bundle: " + bundleName);
            this.pipelineLayout = pPl.get(0);
            if (DEBUG) Gdx.app.debug(TAG, "  PipelineLayout created for " + bundleName + ": " + this.pipelineLayout +
                    " with " + pipelineLayoutCI.setLayoutCount() + " set layouts.");

            // 4. Create VkGraphicsPipeline
            this.graphicsPipeline = createFullGraphicsPipeline(config, pipelineManager.getVkPipelineCacheHandle());
            if (this.graphicsPipeline == VK_NULL_HANDLE) { /* ... throw ... */ }

            success = true;
            if (DEBUG) Gdx.app.log(TAG, "Pipeline bundle fully created for: " + bundleName);

        } finally {
            if (!success) {
                if (DEBUG) Gdx.app.error(TAG, "Constructor failed for " + bundleName + ", attempting partial cleanup.");
                disposePartial();
            }
        }
    }

    private long createDescriptorSetLayoutInternal(VkDevice device, Config.BindingConfigPojo[] pojoBindings, MemoryStack stack, String layoutNameHint) {
        if (pojoBindings == null || pojoBindings.length == 0) {
            if (DEBUG) Gdx.app.debug(TAG, "    No bindings for DSL: " + layoutNameHint + ", creating empty layout might be an error if shader expects it.");
            // Creating an empty DSL is valid but may not be what's intended if shaders expect bindings.
            // To create a truly empty layout (if that's ever needed and valid for a set):
            // VkDescriptorSetLayoutCreateInfo emptyLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
            // .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
            // .bindingCount(0).pBindings(null);
            // ... create ...
            // For now, if no pojoBindings, return NULL_HANDLE, pipeline layout creation will adapt.
            return VK_NULL_HANDLE;
        }

        VkDescriptorSetLayoutBinding.Buffer dslBindingsOnStack = VkDescriptorSetLayoutBinding.calloc(pojoBindings.length, stack);
        for (int i = 0; i < pojoBindings.length; i++) {
            Config.BindingConfigPojo pojo = pojoBindings[i];
            if (pojo == null) throw new GdxRuntimeException("Null BindingConfigPojo at index " + i + " for " + layoutNameHint);

            if (DEBUG) {
                Gdx.app.debug(TAG, "    Populating native binding " + i + " for " + layoutNameHint +
                        ": binding=" + pojo.binding +
                        ", type=" + pojo.descriptorType +
                        ", count=" + pojo.descriptorCount +
                        ", stages=0x" + Integer.toHexString(pojo.stageFlags));
            }
            dslBindingsOnStack.get(i)
                    .binding(pojo.binding)
                    .descriptorType(pojo.descriptorType)
                    .descriptorCount(pojo.descriptorCount)
                    .stageFlags(pojo.stageFlags)
                    .pImmutableSamplers(null);
        }

        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(dslBindingsOnStack); // Automatically sets bindingCount from buffer.remaining()

        LongBuffer pDsl = stack.mallocLong(1);
        vkCheck(vkCreateDescriptorSetLayout(device, layoutInfo, null, pDsl),
                "Failed to create descriptor set layout: " + layoutNameHint);
        return pDsl.get(0);
    }

    private long createFullGraphicsPipeline(Config config, long vkPipelineCache) {
        // This method implementation remains largely the same as the one provided in
        // "Okay, let's flesh out the VulkanShaderPipelineBundle class..."
        // Ensure it uses this.vertShaderModule, this.fragShaderModule, this.pipelineLayout,
        // config.vertexAttributes, config.compatibleRenderPass, and other config states.
        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            shaderStages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(this.vertShaderModule).pName(stack.UTF8("main"));
            shaderStages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(this.fragShaderModule).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(config.vertexAttributes.getBindingDescription())
                    .pVertexAttributeDescriptions(config.vertexAttributes.getAttributeDescriptions());

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(config.primitiveTopology).primitiveRestartEnable(config.primitiveRestartEnable);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1).scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false).rasterizerDiscardEnable(false)
                    .polygonMode(config.polygonMode).lineWidth(config.lineWidth)
                    .cullMode(config.cullMode).frontFace(config.frontFace)
                    .depthBiasEnable(config.depthBiasEnable);

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .sampleShadingEnable(config.sampleShadingEnable).rasterizationSamples(config.rasterizationSamples);

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(config.depthTestEnable).depthWriteEnable(config.depthWriteEnable)
                    .depthCompareOp(config.depthCompareOp)
                    .stencilTestEnable(config.stencilTestEnable);

            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachmentsToUse;
            if (config.colorBlendAttachments != null && config.colorBlendAttachments.hasRemaining()) {
                colorBlendAttachmentsToUse = config.colorBlendAttachments;
            } else {
                colorBlendAttachmentsToUse = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                        .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                        .blendEnable(false);
            }
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(config.colorLogicOpEnable).pAttachments(colorBlendAttachmentsToUse);

            IntBuffer pDynamicStatesBuffer;
            if (config.dynamicStates != null && config.dynamicStates.hasRemaining()) {
                pDynamicStatesBuffer = config.dynamicStates;
            } else {
                pDynamicStatesBuffer = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
            }
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(pDynamicStatesBuffer);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(shaderStages).pVertexInputState(vertexInputInfo)
                    .pInputAssemblyState(inputAssembly).pViewportState(viewportState)
                    .pRasterizationState(rasterizer).pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil).pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState)
                    .layout(this.pipelineLayout)
                    .renderPass(config.compatibleRenderPass)
                    .subpass(config.subpassIndex)
                    .basePipelineHandle(VK_NULL_HANDLE).basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            vkCheck(vkCreateGraphicsPipelines(rawDevice, vkPipelineCache, pipelineInfo, null, pGraphicsPipeline),
                    "Failed to create graphics pipeline in bundle for " + config.vertexShaderFile.name());
            return pGraphicsPipeline.get(0);
        }
    }

    private void disposePartial() {
        if (graphicsPipeline != VK_NULL_HANDLE) vkDestroyPipeline(rawDevice, graphicsPipeline, null);
        if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(rawDevice, pipelineLayout, null);
        if (descriptorSetLayouts != null) {
            for (long dsl : descriptorSetLayouts) {
                if (dsl != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(rawDevice, dsl, null);
            }
        }
        // Shader modules are managed by VulkanShaderManager
        graphicsPipeline = VK_NULL_HANDLE;
        pipelineLayout = VK_NULL_HANDLE;
        if (descriptorSetLayouts != null) Arrays.fill(descriptorSetLayouts, VK_NULL_HANDLE);
    }

    public long getGraphicsPipeline() { return graphicsPipeline; }
    public long getPipelineLayout() { return pipelineLayout; }

    public long getDescriptorSetLayoutHandle(int setIndex) {
        if (descriptorSetLayouts == null || setIndex < 0 || setIndex >= descriptorSetLayouts.length) {
            throw new IllegalArgumentException("Invalid set index " + setIndex + " or layouts not initialized.");
        }
        return descriptorSetLayouts[setIndex];
    }

    @Override
    public void dispose() {
        if (DEBUG) Gdx.app.log(TAG, "Disposing pipeline bundle: Pipeline=" + graphicsPipeline + ", Layout=" + pipelineLayout);
        disposePartial(); // Calls the same cleanup logic
        if (DEBUG) Gdx.app.log(TAG, "Pipeline bundle resources disposed.");
    }
}