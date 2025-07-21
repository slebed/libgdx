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

    private final long[] descriptorSetLayouts;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long graphicsPipeline = VK_NULL_HANDLE;

    public static class Config {
        public FileHandle vertexShaderFile;
        public FileHandle fragmentShaderFile;
        public VulkanVertexAttributes vertexAttributes;

        public static class BindingConfigPojo {
            public int binding;
            public int descriptorType;
            public int descriptorCount = 1;
            public int stageFlags;

            public BindingConfigPojo() {}

            /**
             * Convenience constructor to match usage in shader classes.
             */
            public BindingConfigPojo(int binding, int descriptorType, int descriptorCount, int stageFlags) {
                this.binding = binding;
                this.descriptorType = descriptorType;
                this.descriptorCount = descriptorCount;
                this.stageFlags = stageFlags;
            }
        }
        public BindingConfigPojo[] descriptorSet0_Bindings;
        public BindingConfigPojo[] descriptorSet1_Bindings;

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

        String bundleName = (config.vertexShaderFile != null ? config.vertexShaderFile.nameWithoutExtension() : "unknown_v") + "_" +
                (config.fragmentShaderFile != null ? config.fragmentShaderFile.nameWithoutExtension() : "unknown_f");
        if (DEBUG) Gdx.app.log(TAG, "Creating pipeline bundle for: " + bundleName);

        this.descriptorSetLayouts = new long[2]; // For Set 0 and Set 1

        boolean success = false;
        try (MemoryStack stack = stackPush()) {
            if (config.vertexShaderFile == null || config.fragmentShaderFile == null) {
                throw new GdxRuntimeException("Vertex and Fragment shader files must be provided in Config.");
            }
            this.vertShaderModule = shaderManager.getShaderModuleFromGlsl(config.vertexShaderFile, Shaderc.shaderc_vertex_shader);
            this.fragShaderModule = shaderManager.getShaderModuleFromGlsl(config.fragmentShaderFile, Shaderc.shaderc_fragment_shader);
            if (this.vertShaderModule == VK_NULL_HANDLE || this.fragShaderModule == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Failed to create shader modules for bundle: " + bundleName);
            }

            this.descriptorSetLayouts[0] = createDescriptorSetLayoutInternal(rawDevice, config.descriptorSet0_Bindings, stack, bundleName + "_Set0");
            this.descriptorSetLayouts[1] = createDescriptorSetLayoutInternal(rawDevice, config.descriptorSet1_Bindings, stack, bundleName + "_Set1");

            ArrayList<Long> validLayoutHandles = new ArrayList<>();
            if (this.descriptorSetLayouts[0] != VK_NULL_HANDLE) validLayoutHandles.add(this.descriptorSetLayouts[0]);
            if (this.descriptorSetLayouts[1] != VK_NULL_HANDLE) validLayoutHandles.add(this.descriptorSetLayouts[1]);

            LongBuffer pSetLayoutsForPipeline = null;
            if (!validLayoutHandles.isEmpty()) {
                pSetLayoutsForPipeline = stack.mallocLong(validLayoutHandles.size());
                for (Long handle : validLayoutHandles) {
                    pSetLayoutsForPipeline.put(handle);
                }
                pSetLayoutsForPipeline.flip();
            }

            VkPipelineLayoutCreateInfo pipelineLayoutCI = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(pSetLayoutsForPipeline);

            if (config.pushConstantRanges != null && config.pushConstantRanges.hasRemaining()) {
                pipelineLayoutCI.pPushConstantRanges(config.pushConstantRanges);
            }

            LongBuffer pPl = stack.mallocLong(1);
            vkCheck(vkCreatePipelineLayout(rawDevice, pipelineLayoutCI, null, pPl), "Failed to create Pipeline Layout for bundle: " + bundleName);
            this.pipelineLayout = pPl.get(0);

            this.graphicsPipeline = createFullGraphicsPipeline(config, pipelineManager.getVkPipelineCacheHandle());
            if (this.graphicsPipeline == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Failed to create graphics pipeline for bundle: " + bundleName);
            }

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
            return VK_NULL_HANDLE;
        }

        VkDescriptorSetLayoutBinding.Buffer dslBindingsOnStack = VkDescriptorSetLayoutBinding.calloc(pojoBindings.length, stack);
        for (int i = 0; i < pojoBindings.length; i++) {
            Config.BindingConfigPojo pojo = pojoBindings[i];
            if (pojo == null) throw new GdxRuntimeException("Null BindingConfigPojo at index " + i + " for " + layoutNameHint);
            dslBindingsOnStack.get(i)
                    .binding(pojo.binding)
                    .descriptorType(pojo.descriptorType)
                    .descriptorCount(pojo.descriptorCount)
                    .stageFlags(pojo.stageFlags)
                    .pImmutableSamplers(null);
        }

        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(dslBindingsOnStack);

        LongBuffer pDsl = stack.mallocLong(1);
        vkCheck(vkCreateDescriptorSetLayout(device, layoutInfo, null, pDsl), "Failed to create descriptor set layout: " + layoutNameHint);
        return pDsl.get(0);
    }

    private long createFullGraphicsPipeline(Config config, long vkPipelineCache) {
        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            shaderStages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(this.vertShaderModule).pName(stack.UTF8("main"));
            shaderStages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(this.fragShaderModule).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pVertexBindingDescriptions(config.vertexAttributes.getBindingDescription())
                    .pVertexAttributeDescriptions(config.vertexAttributes.getAttributeDescriptions());

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .topology(config.primitiveTopology).primitiveRestartEnable(config.primitiveRestartEnable);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .viewportCount(1).scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthClampEnable(false).rasterizerDiscardEnable(false)
                    .polygonMode(config.polygonMode).lineWidth(config.lineWidth)
                    .cullMode(config.cullMode).frontFace(config.frontFace)
                    .depthBiasEnable(config.depthBiasEnable);

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .sampleShadingEnable(config.sampleShadingEnable).rasterizationSamples(config.rasterizationSamples);

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(config.depthTestEnable).depthWriteEnable(config.depthWriteEnable)
                    .depthCompareOp(config.depthCompareOp)
                    .stencilTestEnable(config.stencilTestEnable);

            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachmentsToUse = (config.colorBlendAttachments != null && config.colorBlendAttachments.hasRemaining())
                    ? config.colorBlendAttachments
                    : VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(false);

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .logicOpEnable(config.colorLogicOpEnable).pAttachments(colorBlendAttachmentsToUse);

            IntBuffer pDynamicStatesBuffer = (config.dynamicStates != null && config.dynamicStates.hasRemaining())
                    ? config.dynamicStates
                    : stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(pDynamicStatesBuffer);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
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
            vkCheck(vkCreateGraphicsPipelines(rawDevice, vkPipelineCache, pipelineInfo, null, pGraphicsPipeline), "Failed to create graphics pipeline in bundle for " + config.vertexShaderFile.name());
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
        disposePartial();
    }
}
