#include "gdxvk_presenter.h"

#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

#include <algorithm>
#include <utility>
#include <vector>

#include "gdxvk_internal.h"

#include <cstring>

#include "gdxvk_commands.h"

#include "gdxvk_caches.h"
#include "gdxvk_deletion.h"
#include "gdxvk_layout.h"
#include "gdxvk_resources.h"
#include "gdxvk_reflect.h"
#include "gdxvk_rings.h"
#include "gdxvk_staging.h"
#include "gdxvk_timeline.h"

namespace gdxvk {

namespace {

constexpr uint32_t kFramesInFlight = 2;

FrameStatus mapResult(VkResult r) {
    switch (r) {
        case VK_SUCCESS: return FrameStatus::Ok;
        case VK_ERROR_SURFACE_LOST_KHR: return FrameStatus::SurfaceLost;
        case VK_ERROR_DEVICE_LOST: return FrameStatus::DeviceLost;
        case VK_ERROR_OUT_OF_HOST_MEMORY:
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return FrameStatus::OutOfMemory;
        default: return FrameStatus::Error;
    }
}

VkBlendFactor blendFactor(uint32_t gl) {
    switch (gl) {
        case 0: return VK_BLEND_FACTOR_ZERO;
        case 1: return VK_BLEND_FACTOR_ONE;
        case 0x0300: return VK_BLEND_FACTOR_SRC_COLOR;
        case 0x0301: return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
        case 0x0302: return VK_BLEND_FACTOR_SRC_ALPHA;
        case 0x0303: return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        case 0x0304: return VK_BLEND_FACTOR_DST_ALPHA;
        case 0x0305: return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
        case 0x0306: return VK_BLEND_FACTOR_DST_COLOR;
        case 0x0307: return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
        case 0x0308: return VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
        case 0x8001: return VK_BLEND_FACTOR_CONSTANT_COLOR; // blend constants are not set by the stream yet (zero)
        case 0x8002: return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
        case 0x8003: return VK_BLEND_FACTOR_CONSTANT_ALPHA;
        case 0x8004: return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
        default: return VK_BLEND_FACTOR_ONE;
    }
}

struct Frame {
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkCommandBuffer uploadCmd = VK_NULL_HANDLE; // staged uploads, recorded at endFrame, submitted ahead of `cmd`
    VkSemaphore acquired = VK_NULL_HANDLE;
    uint64_t serial = 0; // submit serial of the last frame recorded in this slot; 0 = never submitted
};

void imageBarrier(VkCommandBuffer cmd, VkImage image, VkImageLayout from, VkImageLayout to, VkPipelineStageFlags srcStage,
                  VkAccessFlags srcAccess, VkPipelineStageFlags dstStage, VkAccessFlags dstAccess,
                  VkImageAspectFlags aspect = VK_IMAGE_ASPECT_COLOR_BIT) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = srcAccess;
    b.dstAccessMask = dstAccess;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange = {aspect, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
}

} // namespace

struct Presenter::Impl {
    Context& ctx;
    const ContextHandles& h;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    SurfaceHandles native; // kept so releaseWindow can run after the surface is gone
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkColorSpaceKHR colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    VkExtent2D extent{0, 0};
    VkExtent2D requested{0, 0};
    bool vsync = true;
    bool dirty = true; // swapchain needs (re)creation before the next frame

    std::vector<VkImage> images;
    std::vector<VkImageView> views;
    std::vector<VkSemaphore> renderDone; // one per swapchain image, signalled by submit, waited by present
    VkRenderPass renderPass = VK_NULL_HANDLE;       // Tier B only
    std::vector<VkFramebuffer> framebuffers;        // Tier B only

    struct PipelineRec {
        VkShaderModule vs = VK_NULL_HANDLE, fs = VK_NULL_HANDLE;
        PipelineDesc desc;
        std::string name;
        struct Variant {
            VkFormat format;
            VkFormat depthFormat;
            bool offscreen;  // Tier B only: the front face is baked in
            uint32_t ds;     // Tier B only: the packed depth/stencil state (see DsState::pack)
            VkPipeline pipeline;
        };
        std::vector<Variant> variants;
    };
    std::vector<PipelineRec> pipelines;
    std::vector<VkPipeline> computePipelines;
    struct ComputeJob {
        VkPipeline pipeline;
        VkDescriptorSet set;
        float push[32];
        uint32_t groups;
    };
    std::vector<ComputeJob> computeQueue; // this frame's dispatches, recorded into the upload command buffer at endFrame
    bool scissorOn = false;  // command stream state, reset every frame

    bool canCapture = false; // swapchain images support TRANSFER_SRC
    VkBuffer captureBuf = VK_NULL_HANDLE;
    VmaAllocation captureAlloc = nullptr;
    VkDeviceSize captureSize = 0;
    std::vector<uint8_t> captured;

    Frame frames[kFramesInFlight];
    std::unique_ptr<FrameRing> rings[kFramesInFlight]; // Set 0 dynamic buffers, one per frame in flight
    bool stagingRecorded = false;
    uint32_t frameIndex = 0;
    uint32_t imageIndex = 0;
    bool frameOpen = false;
    std::string error;

    explicit Impl(Context& c) : ctx(c), h(c.handles()) {}

    FrameStatus fail(const std::string& msg) {
        error = msg;
        return FrameStatus::Error;
    }

    void destroySwapchainViews() {
        for (VkFramebuffer f : framebuffers) vkDestroyFramebuffer(h.device, f, nullptr);
        framebuffers.clear();
        for (VkImageView v : views) vkDestroyImageView(h.device, v, nullptr);
        views.clear();
        for (VkSemaphore s : renderDone) vkDestroySemaphore(h.device, s, nullptr);
        renderDone.clear();
        images.clear();
    }

    // ---- Pipelines: one record per createPipeline call, one VkPipeline per kind of render target it is used with ----

    // Tier B needs a render pass compatible with the one drawn into (same colour format) and bakes the front face; Tier A has dynamic
    // rendering and dynamic front face, so only the colour format matters.
    // The OpenGL depth and stencil state. Tier A sets it with dynamic state; Tier B bakes the parts that are not dynamic into the pipeline
    // (reference and masks stay dynamic on both tiers), so a change rebinds the matching variant.
    struct DsState {
        bool depthTest = false, depthWrite = true;
        uint32_t depthFunc = 1;                         // VkCompareOp (GL_LESS)
        bool stencilTest = false;
        uint32_t stencilFunc = 7;                       // VkCompareOp (GL_ALWAYS)
        uint32_t sfail = 0, dpfail = 0, dppass = 0;     // VkStencilOp (GL_KEEP)
        uint32_t ref = 0, compareMask = 0xFFFFFFFFu, writeMask = 0xFFFFFFFFu;

        uint32_t pack() const {
            return (depthTest ? 1u : 0u) | (depthWrite ? 2u : 0u) | (depthFunc << 2) | (stencilTest ? 32u : 0u) | (stencilFunc << 6) |
                   (sfail << 9) | (dpfail << 12) | (dppass << 15);
        }
    } dsState;

    static VkCompareOp compareOp(uint32_t glFunc) { return static_cast<VkCompareOp>(glFunc & 7u); } // GL_NEVER..GL_ALWAYS = 0x200..0x207
    static VkStencilOp stencilOp(uint32_t gl) {
        switch (gl) {
            case 0x0000: return VK_STENCIL_OP_ZERO;
            case 0x1E01: return VK_STENCIL_OP_REPLACE;
            case 0x1E02: return VK_STENCIL_OP_INCREMENT_AND_CLAMP;
            case 0x1E03: return VK_STENCIL_OP_DECREMENT_AND_CLAMP;
            case 0x150A: return VK_STENCIL_OP_INVERT;
            case 0x8507: return VK_STENCIL_OP_INCREMENT_AND_WRAP;
            case 0x8508: return VK_STENCIL_OP_DECREMENT_AND_WRAP;
            default: return VK_STENCIL_OP_KEEP; // GL_KEEP
        }
    }

    VkPipeline pipelineFor(int id, VkFormat fmt, VkFormat depthFmt, bool offscreen) {
        PipelineRec& rec = pipelines[id];
        const bool bakedKind = !h.tierA && offscreen;
        // Without a depth attachment the depth and stencil state does nothing: one variant serves every state.
        const uint32_t bakedDs = (!h.tierA && depthFmt != VK_FORMAT_UNDEFINED) ? dsState.pack() : 0u;
        for (const PipelineRec::Variant& v : rec.variants) {
            if (v.format == fmt && v.depthFormat == depthFmt && v.offscreen == bakedKind && v.ds == bakedDs) return v.pipeline;
        }
        VkPipeline p = buildPipeline(rec, fmt, depthFmt, offscreen, bakedDs);
        if (p != VK_NULL_HANDLE) rec.variants.push_back({fmt, depthFmt, bakedKind, bakedDs, p});
        return p;
    }

    VkPipeline buildPipeline(const PipelineRec& rec, VkFormat fmt, VkFormat depthFmt, bool offscreen, uint32_t bakedDs) {
        const PipelineDesc& desc = rec.desc;
        VkPipelineShaderStageCreateInfo stages[2]{{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO},
                                                  {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}};
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = rec.vs;
        stages[0].pName = "main";
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = rec.fs;
        stages[1].pName = "main";

        VkVertexInputBindingDescription binding{0, desc.stride, VK_VERTEX_INPUT_RATE_VERTEX};
        std::vector<VkVertexInputAttributeDescription> attrs;
        for (const VertexAttribute& a : desc.attributes) attrs.push_back({a.location, 0, static_cast<VkFormat>(a.format), a.offset});
        VkPipelineVertexInputStateCreateInfo vin{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        if (desc.stride > 0) {
            vin.vertexBindingDescriptionCount = 1;
            vin.pVertexBindingDescriptions = &binding;
            vin.vertexAttributeDescriptionCount = static_cast<uint32_t>(attrs.size());
            vin.pVertexAttributeDescriptions = attrs.data();
        }
        VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        ia.topology = static_cast<VkPrimitiveTopology>(desc.topology);
        VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        vp.viewportCount = 1;
        vp.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        rs.polygonMode = VK_POLYGON_MODE_FILL;
        rs.cullMode = static_cast<VkCullModeFlags>(desc.cullMode); // baked on Tier B, set dynamically on Tier A
        // The swapchain uses a negative-height viewport and counter-clockwise front faces. An offscreen target keeps GL's memory
        // layout (row 0 at the bottom) with a positive height, which turns GL's counter-clockwise faces clockwise (plan decision 6).
        rs.frontFace = offscreen ? VK_FRONT_FACE_CLOCKWISE : VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rs.lineWidth = 1.0f;
        VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState cba{};
        cba.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        if (desc.blend) {
            cba.blendEnable = VK_TRUE;
            cba.srcColorBlendFactor = blendFactor(desc.srcColor);
            cba.dstColorBlendFactor = blendFactor(desc.dstColor);
            cba.colorBlendOp = VK_BLEND_OP_ADD;
            cba.srcAlphaBlendFactor = blendFactor(desc.srcAlpha);
            cba.dstAlphaBlendFactor = blendFactor(desc.dstAlpha);
            cba.alphaBlendOp = VK_BLEND_OP_ADD;
        }
        VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        cb.attachmentCount = 1;
        cb.pAttachments = &cba;

        const bool hasDepth = depthFmt != VK_FORMAT_UNDEFINED;
        const bool hasStencil = depthFmt == VK_FORMAT_D24_UNORM_S8_UINT || depthFmt == VK_FORMAT_D32_SFLOAT_S8_UINT;
        // Tier A sets depth and stencil state, cull mode and front face dynamically (Vulkan 1.3); Tier B bakes them (plan decision 7).
        // The stencil reference and masks are dynamic on both tiers.
        VkPipelineDepthStencilStateCreateInfo dss{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
        if (!h.tierA && hasDepth) {
            dss.depthTestEnable = (bakedDs & 1u) ? VK_TRUE : VK_FALSE;
            dss.depthWriteEnable = (bakedDs & 2u) ? VK_TRUE : VK_FALSE;
            dss.depthCompareOp = static_cast<VkCompareOp>((bakedDs >> 2) & 7u);
            if (hasStencil) {
                dss.stencilTestEnable = (bakedDs & 32u) ? VK_TRUE : VK_FALSE;
                dss.front.compareOp = static_cast<VkCompareOp>((bakedDs >> 6) & 7u);
                dss.front.failOp = static_cast<VkStencilOp>((bakedDs >> 9) & 7u);
                dss.front.depthFailOp = static_cast<VkStencilOp>((bakedDs >> 12) & 7u);
                dss.front.passOp = static_cast<VkStencilOp>((bakedDs >> 15) & 7u);
                dss.back = dss.front;
            }
        }
        VkDynamicState dyn[12] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_STENCIL_COMPARE_MASK,
                                  VK_DYNAMIC_STATE_STENCIL_WRITE_MASK, VK_DYNAMIC_STATE_STENCIL_REFERENCE};
        uint32_t dynCount = 5;
        if (h.tierA) {
            const VkDynamicState a[] = {VK_DYNAMIC_STATE_CULL_MODE, VK_DYNAMIC_STATE_FRONT_FACE, VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE,
                                        VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE, VK_DYNAMIC_STATE_DEPTH_COMPARE_OP, VK_DYNAMIC_STATE_STENCIL_TEST_ENABLE,
                                        VK_DYNAMIC_STATE_STENCIL_OP};
            for (VkDynamicState d : a) dyn[dynCount++] = d;
        }
        VkPipelineDynamicStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        ds.dynamicStateCount = dynCount;
        ds.pDynamicStates = dyn;

        VkPipelineRenderingCreateInfo rendering{VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO};
        rendering.colorAttachmentCount = 1;
        rendering.pColorAttachmentFormats = &fmt;
        rendering.depthAttachmentFormat = depthFmt;
        rendering.stencilAttachmentFormat = hasStencil ? depthFmt : VK_FORMAT_UNDEFINED;

        VkGraphicsPipelineCreateInfo gi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        gi.pNext = h.tierA ? &rendering : nullptr;
        gi.stageCount = 2;
        gi.pStages = stages;
        gi.pVertexInputState = &vin;
        gi.pInputAssemblyState = &ia;
        gi.pViewportState = &vp;
        gi.pRasterizationState = &rs;
        gi.pMultisampleState = &ms;
        gi.pColorBlendState = &cb;
        gi.pDepthStencilState = &dss;
        gi.pDynamicState = &ds;
        gi.layout = h.layouts->pipelineLayout();
        if (!h.tierA) {
            VkRenderPass pass = offscreen ? offscreenPass(fmt, depthFmt, false, false, false) : renderPass;
            if (pass == VK_NULL_HANDLE) {
                error = "no render pass for pipeline " + rec.name;
                return VK_NULL_HANDLE;
            }
            gi.renderPass = pass;
        }
        gi.subpass = 0;

        VkPipeline pipeline = VK_NULL_HANDLE;
        const VkResult r = vkCreateGraphicsPipelines(h.device, h.pipelineCache->handle(), 1, &gi, nullptr, &pipeline);
        if (r != VK_SUCCESS) {
            error = "vkCreateGraphicsPipelines failed for " + rec.name + " (VkResult " + std::to_string(r) + ")";
            return VK_NULL_HANDLE;
        }
        return pipeline;
    }


    // ---- Render passes and the command stream's current target (plan, Phase 3) ----
    //
    // The stream draws into the swapchain until a BindTarget names a render-target texture (and optionally a depth image). Switching ends
    // the open pass; the new target's pass starts at the first thing that needs one (a draw, or a clear that can become the load op), so
    // binding a target and leaving it costs nothing. The frame's first swapchain pass is begun by beginFrame; later swapchain passes load
    // what was drawn. Offscreen colour images rest in SHADER_READ_ONLY_OPTIMAL and are moved to COLOR_ATTACHMENT_OPTIMAL for the pass and
    // back after it; depth images stay in DEPTH_STENCIL_ATTACHMENT_OPTIMAL, they are never sampled.

    struct OffPass {
        VkFormat color = VK_FORMAT_UNDEFINED, depth = VK_FORMAT_UNDEFINED;
        bool clearColor = false, clearDepth = false, clearStencil = false;
        VkRenderPass pass = VK_NULL_HANDLE;
    };
    std::vector<OffPass> offPasses;               // Tier B
    VkRenderPass renderPassLoad = VK_NULL_HANDLE; // Tier B: swapchain pass that keeps the contents (resume after an offscreen pass)

    // What a pass clears when it starts; a pass loads whatever is not listed.
    struct PassClear {
        bool color = false, depth = false, stencil = false;
        float rgba[4] = {0, 0, 0, 0};
        float depthValue = 1.0f;
        uint32_t stencilValue = 0;
    };

    struct OffTarget {
        VkImage image = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkFormat format = VK_FORMAT_UNDEFINED;
        uint32_t w = 0, h = 0;
        int depthId = -1;                           // the depth image, -1 = none
        VkImage depthImage = VK_NULL_HANDLE;
        VkImageView depthView = VK_NULL_HANDLE;
        VkFormat depthFormat = VK_FORMAT_UNDEFINED;
        bool hasStencil = false;
    };
    int curOff = -1;           // texture id of the offscreen target, -1 = the swapchain
    OffTarget off;             // valid while curOff >= 0
    bool swapOpen = false;     // a pass on the swapchain image is recording
    bool offOpen = false;      // a pass on the offscreen image is recording
    VkFramebuffer offFb = VK_NULL_HANDLE; // Tier B, for the open offscreen pass
    int curPipeline = -1;      // last pipeline bound by the stream
    // GL-convention state from the stream (pixels, origin bottom-left); converted for whichever target is current.
    int32_t glViewport[4] = {0, 0, 0, 0};
    int32_t glScissor[4] = {0, 0, 0, 0};
    bool viewportSet = false;

    // Always ends in COLOR_ATTACHMENT_OPTIMAL: the layout changes around passes are explicit barriers, in both tiers.
    VkRenderPass makeRenderPass(VkFormat fmt, bool clear, VkImageLayout initial) {
        VkAttachmentDescription att{};
        att.format = fmt;
        att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
        att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = initial;
        att.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{};
        sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1;
        sub.pColorAttachments = &ref;
        VkSubpassDependency dep{};
        dep.srcSubpass = VK_SUBPASS_EXTERNAL;
        dep.dstSubpass = 0;
        dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        // Identical in every variant: render passes are only compatible (usable with each other's pipelines and framebuffers) if their
        // dependencies match, and the clear and load variants must be compatible.
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
        VkRenderPassCreateInfo ri{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        ri.attachmentCount = 1;
        ri.pAttachments = &att;
        ri.subpassCount = 1;
        ri.pSubpasses = &sub;
        ri.dependencyCount = 1;
        ri.pDependencies = &dep;
        VkRenderPass pass = VK_NULL_HANDLE;
        return vkCreateRenderPass(h.device, &ri, nullptr, &pass) == VK_SUCCESS ? pass : VK_NULL_HANDLE;
    }

    bool createRenderPass() {
        renderPass = makeRenderPass(format, true, VK_IMAGE_LAYOUT_UNDEFINED);
        renderPassLoad = makeRenderPass(format, false, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        return renderPass != VK_NULL_HANDLE && renderPassLoad != VK_NULL_HANDLE;
    }

    static bool formatHasStencil(VkFormat f) { return f == VK_FORMAT_D24_UNORM_S8_UINT || f == VK_FORMAT_D32_SFLOAT_S8_UINT; }

    // Offscreen render pass: a colour attachment, and a depth(-stencil) one when `depth` is given. Colour and depth stay in their attachment
    // layouts at both ends; the clear flags choose the load ops, everything else loads. All variants share the same dependency.
    VkRenderPass offscreenPass(VkFormat color, VkFormat depth, bool clearColor, bool clearDepth, bool clearStencil) {
        if (depth == VK_FORMAT_UNDEFINED) clearDepth = clearStencil = false;
        if (!formatHasStencil(depth)) clearStencil = false;
        for (const OffPass& p : offPasses) {
            if (p.color == color && p.depth == depth && p.clearColor == clearColor && p.clearDepth == clearDepth && p.clearStencil == clearStencil) return p.pass;
        }
        VkAttachmentDescription att[2]{};
        att[0].format = color;
        att[0].samples = VK_SAMPLE_COUNT_1_BIT;
        att[0].loadOp = clearColor ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
        att[0].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att[0].initialLayout = att[0].finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        const bool hasDepth = depth != VK_FORMAT_UNDEFINED;
        const bool stencil = formatHasStencil(depth);
        if (hasDepth) {
            att[1].format = depth;
            att[1].samples = VK_SAMPLE_COUNT_1_BIT;
            att[1].loadOp = clearDepth ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
            att[1].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            att[1].stencilLoadOp = stencil ? (clearStencil ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD) : VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            att[1].stencilStoreOp = stencil ? VK_ATTACHMENT_STORE_OP_STORE : VK_ATTACHMENT_STORE_OP_DONT_CARE;
            att[1].initialLayout = att[1].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        }
        VkAttachmentReference cref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkAttachmentReference dref{1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{};
        sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1;
        sub.pColorAttachments = &cref;
        if (hasDepth) sub.pDepthStencilAttachment = &dref;
        const VkPipelineStageFlags stages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
                                            VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
        VkSubpassDependency dep{};
        dep.srcSubpass = VK_SUBPASS_EXTERNAL;
        dep.dstSubpass = 0;
        dep.srcStageMask = dep.dstStageMask = stages;
        dep.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
        VkRenderPassCreateInfo ri{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        ri.attachmentCount = hasDepth ? 2 : 1;
        ri.pAttachments = att;
        ri.subpassCount = 1;
        ri.pSubpasses = &sub;
        ri.dependencyCount = 1;
        ri.pDependencies = &dep;
        OffPass p;
        p.color = color;
        p.depth = depth;
        p.clearColor = clearColor;
        p.clearDepth = clearDepth;
        p.clearStencil = clearStencil;
        if (vkCreateRenderPass(h.device, &ri, nullptr, &p.pass) != VK_SUCCESS) return VK_NULL_HANDLE;
        offPasses.push_back(p);
        return p.pass;
    }

    uint32_t targetW() const { return curOff >= 0 ? off.w : extent.width; }
    uint32_t targetH() const { return curOff >= 0 ? off.h : extent.height; }
    VkFormat targetFormat() const { return curOff >= 0 ? off.format : format; }
    VkFormat targetDepthFormat() const { return curOff >= 0 ? off.depthFormat : VK_FORMAT_UNDEFINED; }
    bool passOpen() const { return curOff >= 0 ? offOpen : swapOpen; }

    // A GL rectangle (origin bottom-left) as a Vulkan scissor rectangle on the current target, clamped into it. The swapchain is drawn
    // upside down by the negative-height viewport, so its rectangles flip; an offscreen target keeps GL's row order.
    VkRect2D toVkRect(int32_t x, int32_t y, int32_t rw, int32_t rh) const {
        const int32_t tw = static_cast<int32_t>(targetW()), th = static_cast<int32_t>(targetH());
        int32_t x0 = std::max(0, x), x1 = std::min(tw, x + std::max(0, rw));
        int32_t y0, y1;
        if (curOff >= 0) {
            y0 = std::max(0, y);
            y1 = std::min(th, y + std::max(0, rh));
        } else {
            y0 = std::max(0, th - (y + std::max(0, rh)));
            y1 = std::min(th, th - y);
        }
        return VkRect2D{{x0, y0}, {static_cast<uint32_t>(std::max(0, x1 - x0)), static_cast<uint32_t>(std::max(0, y1 - y0))}};
    }
    VkRect2D currentScissor() const {
        return scissorOn ? toVkRect(glScissor[0], glScissor[1], glScissor[2], glScissor[3]) : VkRect2D{{0, 0}, {targetW(), targetH()}};
    }
    void applyScissor(VkCommandBuffer cmd) {
        const VkRect2D r = currentScissor();
        vkCmdSetScissor(cmd, 0, 1, &r);
    }
    void applyViewport(VkCommandBuffer cmd) {
        const float tw = static_cast<float>(targetW()), th = static_cast<float>(targetH());
        float x = 0, y = 0, w = tw, hh = th;
        if (viewportSet) {
            x = static_cast<float>(glViewport[0]);
            y = static_cast<float>(glViewport[1]);
            w = static_cast<float>(glViewport[2]);
            hh = static_cast<float>(glViewport[3]);
        }
        VkViewport v;
        if (curOff >= 0) v = {x, y, w, hh, 0.0f, 1.0f};                   // positive height: GL's memory layout
        else v = {x, th - y, w, -hh, 0.0f, 1.0f};                           // negative height: the image comes out upright
        vkCmdSetViewport(cmd, 0, 1, &v);
    }
    VkFrontFace frontFace() const { return curOff >= 0 ? VK_FRONT_FACE_CLOCKWISE : VK_FRONT_FACE_COUNTER_CLOCKWISE; }

    // Depth and stencil state reaches the GPU as dynamic state on Tier A, and through the pipeline variant on Tier B (rebound by the caller).
    // The stencil reference and masks are dynamic on both tiers. Safe to call with no pipeline bound.
    void applyDepthStencil(VkCommandBuffer cmd) {
        if (h.tierA) {
            vkCmdSetDepthTestEnable(cmd, dsState.depthTest ? VK_TRUE : VK_FALSE);
            vkCmdSetDepthWriteEnable(cmd, dsState.depthWrite ? VK_TRUE : VK_FALSE);
            vkCmdSetDepthCompareOp(cmd, static_cast<VkCompareOp>(dsState.depthFunc));
            vkCmdSetStencilTestEnable(cmd, dsState.stencilTest ? VK_TRUE : VK_FALSE);
            vkCmdSetStencilOp(cmd, VK_STENCIL_FACE_FRONT_AND_BACK, static_cast<VkStencilOp>(dsState.sfail), static_cast<VkStencilOp>(dsState.dppass),
                              static_cast<VkStencilOp>(dsState.dpfail), static_cast<VkCompareOp>(dsState.stencilFunc));
        }
        vkCmdSetStencilCompareMask(cmd, VK_STENCIL_FACE_FRONT_AND_BACK, dsState.compareMask);
        vkCmdSetStencilWriteMask(cmd, VK_STENCIL_FACE_FRONT_AND_BACK, dsState.writeMask);
        vkCmdSetStencilReference(cmd, VK_STENCIL_FACE_FRONT_AND_BACK, dsState.ref);
    }

    // Binds the pipeline variant for the current target and state. False on failure (error set).
    bool bindPipelineVariant(VkCommandBuffer cmd, int id) {
        VkPipeline vk = pipelineFor(id, targetFormat(), targetDepthFormat(), curOff >= 0);
        if (vk == VK_NULL_HANDLE) return false;
        curPipeline = id;
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, vk);
        if (h.tierA) {
            vkCmdSetCullMode(cmd, static_cast<VkCullModeFlags>(pipelines[id].desc.cullMode));
            vkCmdSetFrontFace(cmd, frontFace());
        }
        return true;
    }

    // Ends whichever pass is recording. An offscreen image goes back to SHADER_READ_ONLY_OPTIMAL for sampling.
    void endPass(VkCommandBuffer cmd) {
        if (swapOpen) {
            if (h.tierA) vkCmdEndRendering(cmd); else vkCmdEndRenderPass(cmd);
            swapOpen = false;
        }
        if (offOpen) {
            if (h.tierA) vkCmdEndRendering(cmd); else vkCmdEndRenderPass(cmd);
            offOpen = false;
            imageBarrier(cmd, off.image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                         VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            if (offFb != VK_NULL_HANDLE) {
                VkDevice dev = h.device;
                VkFramebuffer fb = offFb;
                h.deletion->retire([dev, fb]() { vkDestroyFramebuffer(dev, fb, nullptr); });
                offFb = VK_NULL_HANDLE;
            }
        }
    }

    // Starts a pass on the current target, clearing what `clear` names and loading the rest. False on failure (error set).
    bool beginPass(VkCommandBuffer cmd, const PassClear* clear) {
        const bool cc = clear && clear->color, dc = clear && clear->depth, sc = clear && clear->stencil;
        VkClearValue cv[2]{};
        if (clear) {
            for (int i = 0; i < 4; ++i) cv[0].color.float32[i] = clear->rgba[i];
            cv[1].depthStencil = {clear->depthValue, clear->stencilValue};
        }
        const VkRect2D area{{0, 0}, {targetW(), targetH()}};
        if (curOff < 0) {
            if (h.tierA) {
                VkRenderingAttachmentInfo ca{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
                ca.imageView = views[imageIndex];
                ca.imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                ca.loadOp = cc ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
                ca.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
                ca.clearValue = cv[0];
                VkRenderingInfo ri{VK_STRUCTURE_TYPE_RENDERING_INFO};
                ri.renderArea = area;
                ri.layerCount = 1;
                ri.colorAttachmentCount = 1;
                ri.pColorAttachments = &ca;
                vkCmdBeginRendering(cmd, &ri);
            } else {
                VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
                rp.renderPass = cc ? renderPass : renderPassLoad;
                rp.framebuffer = framebuffers[imageIndex];
                rp.renderArea = area;
                rp.clearValueCount = cc ? 1 : 0;
                rp.pClearValues = cv;
                vkCmdBeginRenderPass(cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);
            }
            swapOpen = true;
            return true;
        }

        imageBarrier(cmd, off.image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                     VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, 0,
                     VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT);
        const bool hasDepth = off.depthFormat != VK_FORMAT_UNDEFINED;
        if (hasDepth) {
            VkImage img;
            VkImageView view;
            VkFormat fmt;
            bool stencil, initialized;
            h.resources->depthImageInfo(off.depthId, &img, &view, &fmt, &stencil, &initialized);
            const VkImageAspectFlags aspect = VK_IMAGE_ASPECT_DEPTH_BIT | (off.hasStencil ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
            const VkPipelineStageFlags tests = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            const VkAccessFlags rw = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            if (!initialized) { // first use: out of UNDEFINED into the layout the passes keep it in
                imageBarrier(cmd, off.depthImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                             VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, tests, rw, aspect);
                h.resources->markDepthInitialized(off.depthId);
            } else { // an earlier pass on this image wrote it: its depth writes must land before this pass tests against them
                imageBarrier(cmd, off.depthImage, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                             tests, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, tests, rw, aspect);
            }
        }

        if (h.tierA) {
            VkRenderingAttachmentInfo ca{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
            ca.imageView = off.view;
            ca.imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            ca.loadOp = cc ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
            ca.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            ca.clearValue = cv[0];
            VkRenderingAttachmentInfo da{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
            da.imageView = off.depthView;
            da.imageLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
            da.loadOp = dc ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
            da.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            da.clearValue = cv[1];
            VkRenderingAttachmentInfo sa = da;
            sa.loadOp = sc ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
            VkRenderingInfo ri{VK_STRUCTURE_TYPE_RENDERING_INFO};
            ri.renderArea = area;
            ri.layerCount = 1;
            ri.colorAttachmentCount = 1;
            ri.pColorAttachments = &ca;
            if (hasDepth) ri.pDepthAttachment = &da;
            if (hasDepth && off.hasStencil) ri.pStencilAttachment = &sa;
            vkCmdBeginRendering(cmd, &ri);
        } else {
            VkRenderPass pass = offscreenPass(off.format, off.depthFormat, cc, dc, sc);
            VkRenderPass compatible = offscreenPass(off.format, off.depthFormat, false, false, false);
            if (pass == VK_NULL_HANDLE || compatible == VK_NULL_HANDLE) {
                error = "vkCreateRenderPass failed for a render target";
                return false;
            }
            VkImageView atts[2] = {off.view, off.depthView};
            VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            fi.renderPass = compatible;
            fi.attachmentCount = hasDepth ? 2 : 1;
            fi.pAttachments = atts;
            fi.width = off.w;
            fi.height = off.h;
            fi.layers = 1;
            if (vkCreateFramebuffer(h.device, &fi, nullptr, &offFb) != VK_SUCCESS) {
                error = "vkCreateFramebuffer failed for a render target";
                return false;
            }
            VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
            rp.renderPass = pass;
            rp.framebuffer = offFb;
            rp.renderArea = area;
            rp.clearValueCount = hasDepth ? 2 : 1;
            rp.pClearValues = cv;
            vkCmdBeginRenderPass(cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);
        }
        offOpen = true;
        return true;
    }

    // Records the frame's queued dispatches (after the staged uploads, in `cmd`). Returns whether anything was recorded.
    bool recordCompute(VkCommandBuffer cmd) {
        if (computeQueue.empty()) return false;
        constexpr VkPipelineStageFlags kShaderStages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;
        auto barrier = [&](VkPipelineStageFlags srcStage, VkAccessFlags srcAccess, VkPipelineStageFlags dstStage, VkAccessFlags dstAccess) {
            VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            mb.srcAccessMask = srcAccess;
            mb.dstAccessMask = dstAccess;
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 1, &mb, 0, nullptr, 0, nullptr);
        };
        constexpr VkAccessFlags kRw = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        // Earlier frames' vertex shaders may still be reading what the first dispatch writes, and the staged uploads above write
        // the same buffers (initial sprite data).
        barrier(kShaderStages | VK_PIPELINE_STAGE_TRANSFER_BIT, kRw | VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, kRw);
        VkPipeline bound = VK_NULL_HANDLE;
        for (size_t i = 0; i < computeQueue.size(); ++i) {
            const ComputeJob& job = computeQueue[i];
            if (job.pipeline != bound) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, job.pipeline);
                bound = job.pipeline;
            }
            const uint32_t offsets[2] = {0, 0};
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, h.layouts->pipelineLayout(), 0, 1, &job.set, 2, offsets);
            vkCmdPushConstants(cmd, h.layouts->pipelineLayout(), DescriptorLayouts::kPushStages, 0, DescriptorLayouts::kPushConstantBytes, job.push);
            vkCmdDispatch(cmd, job.groups, 1, 1);
            if (i + 1 < computeQueue.size()) barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, kRw);
        }
        // The frame's draws (the next command buffer in the submit) read the results in their vertex shaders.
        barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, kShaderStages, kRw);
        computeQueue.clear();
        return true;
    }

    void destroyAll() {
        if (h.device == VK_NULL_HANDLE) return;
        vkDeviceWaitIdle(h.device);
        destroySwapchainViews();
        for (const PipelineRec& pl : pipelines) {
            for (const PipelineRec::Variant& v : pl.variants) vkDestroyPipeline(h.device, v.pipeline, nullptr);
            vkDestroyShaderModule(h.device, pl.vs, nullptr);
            vkDestroyShaderModule(h.device, pl.fs, nullptr);
        }
        pipelines.clear();
        for (const OffPass& p : offPasses) {
            vkDestroyRenderPass(h.device, p.pass, nullptr);
        }
        offPasses.clear();
        if (renderPassLoad != VK_NULL_HANDLE) vkDestroyRenderPass(h.device, renderPassLoad, nullptr);
        renderPassLoad = VK_NULL_HANDLE;
        for (VkPipeline p : computePipelines) vkDestroyPipeline(h.device, p, nullptr);
        computePipelines.clear();
        computeQueue.clear();
        if (captureBuf != VK_NULL_HANDLE) vmaDestroyBuffer(h.allocator, captureBuf, captureAlloc);
        captureBuf = VK_NULL_HANDLE;
        if (renderPass != VK_NULL_HANDLE) vkDestroyRenderPass(h.device, renderPass, nullptr);
        renderPass = VK_NULL_HANDLE;
        if (swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(h.device, swapchain, nullptr);
        swapchain = VK_NULL_HANDLE;
        for (Frame& f : frames) {
            if (f.acquired != VK_NULL_HANDLE) vkDestroySemaphore(h.device, f.acquired, nullptr);
            if (f.pool != VK_NULL_HANDLE) vkDestroyCommandPool(h.device, f.pool, nullptr);
            f = Frame();
        }
        if (surface != VK_NULL_HANDLE) vkDestroySurfaceKHR(h.instance, surface, nullptr);
        surface = VK_NULL_HANDLE;
        if (native.releaseWindow && native.window) native.releaseWindow(native.window);
        native.releaseWindow = nullptr;
    }

    bool createFrames() {
        for (auto& r : rings) r.reset(new FrameRing(h));
        for (Frame& f : frames) {
            VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
            pi.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
            pi.queueFamilyIndex = h.graphicsQueueFamily;
            if (vkCreateCommandPool(h.device, &pi, nullptr, &f.pool) != VK_SUCCESS) return false;
            VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            ai.commandPool = f.pool;
            ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            ai.commandBufferCount = 1;
            if (vkAllocateCommandBuffers(h.device, &ai, &f.cmd) != VK_SUCCESS) return false;
            if (vkAllocateCommandBuffers(h.device, &ai, &f.uploadCmd) != VK_SUCCESS) return false;
            VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            if (vkCreateSemaphore(h.device, &si, nullptr, &f.acquired) != VK_SUCCESS) return false;
        }
        return true;
    }

    bool ensureCaptureBuffer() {
        const VkDeviceSize need = static_cast<VkDeviceSize>(extent.width) * extent.height * 4;
        if (captureBuf != VK_NULL_HANDLE && captureSize >= need) return true;
        vkDeviceWaitIdle(h.device); // the old buffer may still be referenced by an in-flight copy
        if (captureBuf != VK_NULL_HANDLE) vmaDestroyBuffer(h.allocator, captureBuf, captureAlloc);
        captureBuf = VK_NULL_HANDLE;
        VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bi.size = need;
        bi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        VmaAllocationCreateInfo ai{};
        ai.usage = VMA_MEMORY_USAGE_AUTO;
        ai.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
        if (vmaCreateBuffer(h.allocator, &bi, &ai, &captureBuf, &captureAlloc, nullptr) != VK_SUCCESS) {
            captureBuf = VK_NULL_HANDLE;
            return false;
        }
        captureSize = need;
        return true;
    }

    // Copies the capture buffer into `captured` as RGBA8.
    void readCapture() {
        void* mapped = nullptr;
        if (vmaMapMemory(h.allocator, captureAlloc, &mapped) != VK_SUCCESS) {
            captured.clear();
            return;
        }
        const size_t n = static_cast<size_t>(extent.width) * extent.height * 4;
        captured.assign(static_cast<const uint8_t*>(mapped), static_cast<const uint8_t*>(mapped) + n);
        vmaUnmapMemory(h.allocator, captureAlloc);
        if (format == VK_FORMAT_B8G8R8A8_UNORM) {
            for (size_t i = 0; i < n; i += 4) std::swap(captured[i], captured[i + 2]);
        }
    }

    // (Re)builds the swapchain for the current surface size. Returns Skipped when the surface has zero area.
    FrameStatus recreateSwapchain() {
        vkDeviceWaitIdle(h.device);

        VkSurfaceCapabilitiesKHR caps;
        VkResult r = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(h.physical, surface, &caps);
        if (r != VK_SUCCESS) return mapResult(r) == FrameStatus::Error ? fail("vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed") : mapResult(r);

        VkExtent2D ext = caps.currentExtent;
        if (ext.width == 0xFFFFFFFFu) {
            ext.width = std::clamp(requested.width, caps.minImageExtent.width, caps.maxImageExtent.width);
            ext.height = std::clamp(requested.height, caps.minImageExtent.height, caps.maxImageExtent.height);
        }
        if (ext.width == 0 || ext.height == 0) {
            extent = ext;
            return FrameStatus::Skipped; // minimized; keep dirty so we retry
        }

        if (format == VK_FORMAT_UNDEFINED) {
            uint32_t n = 0;
            vkGetPhysicalDeviceSurfaceFormatsKHR(h.physical, surface, &n, nullptr);
            std::vector<VkSurfaceFormatKHR> fmts(n);
            vkGetPhysicalDeviceSurfaceFormatsKHR(h.physical, surface, &n, fmts.data());
            // UNORM only (plan decision 6): matches GL gamma behaviour, no implicit sRGB encode.
            const VkFormat preferred[] = {VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM};
            for (VkFormat want : preferred) {
                for (const VkSurfaceFormatKHR& f : fmts) {
                    if (f.format == want && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                        format = f.format;
                        colorSpace = f.colorSpace;
                        break;
                    }
                }
                if (format != VK_FORMAT_UNDEFINED) break;
            }
            if (format == VK_FORMAT_UNDEFINED) return fail("Surface offers neither B8G8R8A8_UNORM nor R8G8B8A8_UNORM");
            if (!h.tierA && !createRenderPass()) return fail("vkCreateRenderPass failed");
        }

        uint32_t pmCount = 0;
        vkGetPhysicalDeviceSurfacePresentModesKHR(h.physical, surface, &pmCount, nullptr);
        std::vector<VkPresentModeKHR> modes(pmCount);
        vkGetPhysicalDeviceSurfacePresentModesKHR(h.physical, surface, &pmCount, modes.data());
        auto has = [&](VkPresentModeKHR m) { return std::find(modes.begin(), modes.end(), m) != modes.end(); };
        VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR; // always available
        if (!vsync) {
            if (has(VK_PRESENT_MODE_MAILBOX_KHR)) mode = VK_PRESENT_MODE_MAILBOX_KHR;
            else if (has(VK_PRESENT_MODE_IMMEDIATE_KHR)) mode = VK_PRESENT_MODE_IMMEDIATE_KHR;
        }

        uint32_t count = caps.minImageCount + 1;
        if (caps.maxImageCount > 0) count = std::min(count, caps.maxImageCount);

        VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        if (!(caps.supportedCompositeAlpha & alpha)) {
            for (uint32_t bit = 1; bit <= VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR; bit <<= 1) {
                if (caps.supportedCompositeAlpha & bit) { alpha = static_cast<VkCompositeAlphaFlagBitsKHR>(bit); break; }
            }
        }

        VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        sci.surface = surface;
        sci.minImageCount = count;
        sci.imageFormat = format;
        sci.imageColorSpace = colorSpace;
        sci.imageExtent = ext;
        sci.imageArrayLayers = 1;
        // TRANSFER_SRC lets the golden-image harness read frames back.
        sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | (caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        canCapture = (caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0;
        sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        // Phase 4 switches this to caps.currentTransform plus clip-space pre-rotation; identity until then.
        sci.preTransform = (caps.currentTransform & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                                                                                              : caps.currentTransform;
        sci.compositeAlpha = alpha;
        sci.presentMode = mode;
        sci.clipped = VK_TRUE;
        sci.oldSwapchain = swapchain;

        VkSwapchainKHR fresh = VK_NULL_HANDLE;
        r = vkCreateSwapchainKHR(h.device, &sci, nullptr, &fresh);
        destroySwapchainViews(); // old views/framebuffers go before the old swapchain they point into
        if (swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(h.device, swapchain, nullptr);
        swapchain = VK_NULL_HANDLE;
        if (r != VK_SUCCESS) return mapResult(r) == FrameStatus::Error ? fail("vkCreateSwapchainKHR failed (VkResult " + std::to_string(r) + ")") : mapResult(r);
        swapchain = fresh;
        extent = ext;

        uint32_t n = 0;
        vkGetSwapchainImagesKHR(h.device, swapchain, &n, nullptr);
        images.resize(n);
        vkGetSwapchainImagesKHR(h.device, swapchain, &n, images.data());
        views.resize(n);
        renderDone.resize(n);
        for (uint32_t i = 0; i < n; ++i) {
            VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            vi.image = images[i];
            vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
            vi.format = format;
            vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            if (vkCreateImageView(h.device, &vi, nullptr, &views[i]) != VK_SUCCESS) return fail("vkCreateImageView failed");
            VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            if (vkCreateSemaphore(h.device, &si, nullptr, &renderDone[i]) != VK_SUCCESS) return fail("vkCreateSemaphore failed");
        }
        if (!h.tierA) {
            framebuffers.resize(n);
            for (uint32_t i = 0; i < n; ++i) {
                VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
                fi.renderPass = renderPass;
                fi.attachmentCount = 1;
                fi.pAttachments = &views[i];
                fi.width = ext.width;
                fi.height = ext.height;
                fi.layers = 1;
                if (vkCreateFramebuffer(h.device, &fi, nullptr, &framebuffers[i]) != VK_SUCCESS) return fail("vkCreateFramebuffer failed");
            }
        }
        dirty = false;
        return FrameStatus::Ok;
    }
};

Presenter::Presenter(Context& ctx) : impl_(new Impl(ctx)) {}

Presenter::~Presenter() {
    if (impl_) impl_->destroyAll();
}

std::unique_ptr<Presenter> Presenter::create(Context& ctx, const SurfaceHandles& handles, uint32_t width, uint32_t height,
                                             bool vsync, std::string& error, std::string& errorKind) {
    const ContextHandles& h = ctx.handles();
    if (!h.swapchainEnabled) {
        errorKind = "device";
        error = "Context has no swapchain support (VK_KHR_surface / VK_KHR_swapchain missing)";
        if (handles.releaseWindow && handles.window) handles.releaseWindow(handles.window); // no Presenter owns it yet
        return nullptr;
    }
    std::unique_ptr<Presenter> p(new Presenter(ctx));
    Impl& s = *p->impl_;
    s.requested = {width, height};
    s.vsync = vsync;
    s.native = handles; // from here on ~Presenter releases the window, including on the early-return failure paths

#if defined(VK_USE_PLATFORM_WIN32_KHR)
    VkWin32SurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR};
    sci.hinstance = GetModuleHandleW(nullptr);
    sci.hwnd = reinterpret_cast<HWND>(handles.window);
    const VkResult sr = vkCreateWin32SurfaceKHR(h.instance, &sci, nullptr, &s.surface);
#elif defined(VK_USE_PLATFORM_ANDROID_KHR)
    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = reinterpret_cast<ANativeWindow*>(handles.window);
    const VkResult sr = vkCreateAndroidSurfaceKHR(h.instance, &sci, nullptr, &s.surface);
#elif defined(VK_USE_PLATFORM_XLIB_KHR)
    VkXlibSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR};
    sci.dpy = reinterpret_cast<Display*>(handles.display);
    sci.window = static_cast<Window>(handles.window);
    const VkResult sr = vkCreateXlibSurfaceKHR(h.instance, &sci, nullptr, &s.surface);
#else
    (void)handles;
    const VkResult sr = VK_ERROR_EXTENSION_NOT_PRESENT;
#endif
    if (sr != VK_SUCCESS) {
        errorKind = "init";
        error = "Surface creation failed (VkResult " + std::to_string(static_cast<int>(sr)) + ")";
        s.surface = VK_NULL_HANDLE;
        return nullptr;
    }

    VkBool32 canPresent = VK_FALSE;
    vkGetPhysicalDeviceSurfaceSupportKHR(h.physical, h.graphicsQueueFamily, s.surface, &canPresent);
    if (!canPresent) {
        errorKind = "device";
        error = "The graphics queue family cannot present to this surface";
        return nullptr; // ~Presenter destroys the surface
    }
    if (!s.createFrames()) {
        errorKind = "init";
        error = "Creating per-frame command buffers and sync objects failed";
        return nullptr;
    }
    FrameStatus st = s.recreateSwapchain();
    if (st == FrameStatus::Error || st == FrameStatus::DeviceLost || st == FrameStatus::OutOfMemory || st == FrameStatus::SurfaceLost) {
        errorKind = "init";
        error = s.error.empty() ? "Swapchain creation failed" : s.error;
        return nullptr;
    }
    return p;
}

void Presenter::resize(uint32_t width, uint32_t height) {
    impl_->requested = {width, height};
    impl_->dirty = true;
}

FrameStatus Presenter::beginFrame(const float rgba[4]) {
    Impl& s = *impl_;
    const ContextHandles& h = s.h;
    if (s.frameOpen) return s.fail("beginFrame called twice without endFrame");

    if (s.dirty) {
        FrameStatus st = s.recreateSwapchain();
        if (st != FrameStatus::Ok) return st;
    }

    Frame& f = s.frames[s.frameIndex];
    VkResult r = h.timeline->waitSerial(f.serial);
    if (r != VK_SUCCESS) return mapResult(r);
    h.deletion->collect(); // the GPU has finished with everything retired before the frames that are now complete
    h.staging->collect(h.timeline->completed());
    s.rings[s.frameIndex]->reset(); // this slot's previous frame has completed
    s.computeQueue.clear();

    r = vkAcquireNextImageKHR(h.device, s.swapchain, UINT64_MAX, f.acquired, VK_NULL_HANDLE, &s.imageIndex);
    if (r == VK_ERROR_OUT_OF_DATE_KHR) {
        s.dirty = true;
        return FrameStatus::Skipped;
    }
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return mapResult(r) == FrameStatus::Error ? s.fail("vkAcquireNextImageKHR failed (VkResult " + std::to_string(r) + ")") : mapResult(r);
    if (r == VK_SUBOPTIMAL_KHR) s.dirty = true; // image is still usable this frame; rebuild before the next one

    vkResetCommandPool(h.device, f.pool, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(f.cmd, &bi);

    if (h.tierA) { // Tier A: one global update-after-bind table, bound once per command buffer
        VkDescriptorSet table = h.layouts->globalTextureSet();
        vkCmdBindDescriptorSets(f.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, h.layouts->pipelineLayout(), 1, 1, &table, 0, nullptr);
    }

    // Stream state restarts every frame: the swapchain is the target, nothing set yet. The first pass clears to the frame's colour;
    // Tier B's render pass moves the image out of UNDEFINED itself, Tier A does it with a barrier.
    s.curOff = -1;
    s.swapOpen = s.offOpen = false;
    s.curPipeline = -1;
    s.viewportSet = false;
    s.scissorOn = false;
    if (h.tierA) {
        imageBarrier(f.cmd, s.images[s.imageIndex], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                     VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                     VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    }
    Impl::PassClear first;
    first.color = true;
    for (int c = 0; c < 4; ++c) first.rgba[c] = rgba[c];
    if (!s.beginPass(f.cmd, &first)) return s.fail(s.error);
    // Defaults for the command stream: the whole target, GL-style (Y up) through the negative-height viewport.
    s.applyViewport(f.cmd);
    s.applyScissor(f.cmd);
    s.dsState = Impl::DsState(); // GL's defaults; the application's state is replayed by the stream
    s.applyDepthStencil(f.cmd);
    s.frameOpen = true;
    return FrameStatus::Ok;
}

FrameStatus Presenter::endFrame(bool capture) {
    Impl& s = *impl_;
    const ContextHandles& h = s.h;
    if (!s.frameOpen) return s.fail("endFrame called without beginFrame");
    if (capture) {
        if (!s.canCapture) return s.fail("Swapchain images do not support TRANSFER_SRC; cannot capture");
        if (!s.ensureCaptureBuffer()) return s.fail("Allocating the capture buffer failed");
    }
    s.frameOpen = false;
    Frame& f = s.frames[s.frameIndex];
    VkImage image = s.images[s.imageIndex];

    // Both tiers leave the swapchain image in COLOR_ATTACHMENT_OPTIMAL (the passes end there, so a pass can be resumed). Whichever
    // pass is still open ends here; if the stream left an offscreen target bound, that image goes back to being sampleable.
    s.endPass(f.cmd);
    const VkImageLayout layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    if (capture) {
        imageBarrier(f.cmd, image, layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                     VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.imageExtent = {s.extent.width, s.extent.height, 1};
        // The capture buffer is rewritten every capture. The host waited for the previous one, but say so on the queue as well: an
        // explicit dependency on the earlier submit's copy (older validation layers do not infer it from the host wait).
        VkBufferMemoryBarrier reuse{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        reuse.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_HOST_READ_BIT;
        reuse.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        reuse.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        reuse.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        reuse.buffer = s.captureBuf;
        reuse.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(f.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0,
                             nullptr, 1, &reuse, 0, nullptr);
        vkCmdCopyImageToBuffer(f.cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, s.captureBuf, 1, &region);
        imageBarrier(f.cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                     VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
        VkBufferMemoryBarrier toHost{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toHost.buffer = s.captureBuf;
        toHost.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(f.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1, &toHost, 0, nullptr);
    } else {
        imageBarrier(f.cmd, image, layout, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                     VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
    }
    vkEndCommandBuffer(f.cmd);

    const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &f.acquired;
    si.pWaitDstStageMask = &waitStage;
    // Everything staged up to now, by any thread, goes into its own command buffer that runs ahead of the frame's draws in the same
    // submit. Recording it here, not at beginFrame, means a texture created or changed mid-frame is already uploaded when the frame's
    // draws run; a renamed image keeps its old contents for the draws that were recorded before the change.
    VkCommandBufferBeginInfo ubi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    ubi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(f.uploadCmd, &ubi);
    s.stagingRecorded = h.staging->recordPending(f.uploadCmd);
    const bool computed = s.recordCompute(f.uploadCmd);
    vkEndCommandBuffer(f.uploadCmd);
    const bool preCmd = s.stagingRecorded || computed; // the upload command buffer has something in it
    VkCommandBuffer cmds[2] = {f.uploadCmd, f.cmd};
    si.commandBufferCount = preCmd ? 2 : 1;
    si.pCommandBuffers = preCmd ? cmds : &f.cmd;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &s.renderDone[s.imageIndex];
    VkResult r = h.timeline->submit(si, &f.serial);
    if (r == VK_SUCCESS && s.stagingRecorded) h.staging->markSubmitted(f.serial);
    s.stagingRecorded = false;
    if (r != VK_SUCCESS) return mapResult(r) == FrameStatus::Error ? s.fail("vkQueueSubmit failed (VkResult " + std::to_string(r) + ")") : mapResult(r);

    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &s.renderDone[s.imageIndex];
    pi.swapchainCount = 1;
    pi.pSwapchains = &s.swapchain;
    pi.pImageIndices = &s.imageIndex;
    {
        std::lock_guard<std::mutex> queueLock(h.queueMutex);
        r = vkQueuePresentKHR(h.graphicsQueue, &pi);
    }
    s.frameIndex = (s.frameIndex + 1) % kFramesInFlight;
    FrameStatus result = FrameStatus::Ok;
    if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) {
        s.dirty = true; // the frame was submitted; just rebuild before the next one
    } else if (r != VK_SUCCESS) {
        result = mapResult(r) == FrameStatus::Error ? s.fail("vkQueuePresentKHR failed (VkResult " + std::to_string(r) + ")") : mapResult(r);
    }

    if (capture && result == FrameStatus::Ok) {
        r = h.timeline->waitSerial(f.serial);
        if (r != VK_SUCCESS) return mapResult(r);
        vmaInvalidateAllocation(h.allocator, s.captureAlloc, 0, VK_WHOLE_SIZE);
        s.readCapture();
    }
    return result;
}

int Presenter::createPipeline(const char* name, const uint32_t* vertSpv, size_t vertWords, const uint32_t* fragSpv, size_t fragWords) {
    return createPipelineEx(name, vertSpv, vertWords, fragSpv, fragWords, PipelineDesc());
}

int Presenter::createPipelineEx(const char* name, const uint32_t* vertSpv, size_t vertWords, const uint32_t* fragSpv, size_t fragWords,
                                const PipelineDesc& desc) {
    Impl& s = *impl_;
    const ContextHandles& h = s.h;
    const std::string base = name ? name : "(unnamed)";
    const uint32_t table = h.layouts->tableSize();
    s.error = validateShader(base + ".vert", vertSpv, vertWords, VK_SHADER_STAGE_VERTEX_BIT, h.tierA, table);
    if (s.error.empty()) s.error = validateShader(base + ".frag", fragSpv, fragWords, VK_SHADER_STAGE_FRAGMENT_BIT, h.tierA, table);
    if (!s.error.empty()) return -1;
    auto module = [&](const uint32_t* code, size_t words, VkShaderModule& out) {
        VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        mi.codeSize = words * sizeof(uint32_t);
        mi.pCode = code;
        return vkCreateShaderModule(h.device, &mi, nullptr, &out) == VK_SUCCESS;
    };
    Impl::PipelineRec rec;
    rec.desc = desc;
    rec.name = base;
    if (!module(vertSpv, vertWords, rec.vs) || !module(fragSpv, fragWords, rec.fs)) {
        if (rec.vs != VK_NULL_HANDLE) vkDestroyShaderModule(h.device, rec.vs, nullptr);
        s.error = "vkCreateShaderModule failed (invalid SPIR-V?)";
        return -1;
    }
    // The modules stay alive with the record: the same pipeline is built again for each other render target it is used with.
    s.pipelines.push_back(rec);
    const int id = static_cast<int>(s.pipelines.size() - 1);
    // The swapchain variant is built now, so a bad shader or state fails here and not in the middle of a frame.
    if (s.pipelineFor(id, s.format, VK_FORMAT_UNDEFINED, false) == VK_NULL_HANDLE) {
        vkDestroyShaderModule(h.device, rec.vs, nullptr);
        vkDestroyShaderModule(h.device, rec.fs, nullptr);
        s.pipelines.pop_back();
        return -1;
    }
    return id;
}

int Presenter::createComputePipeline(const char* name, const uint32_t* spv, size_t words) {
    Impl& s = *impl_;
    const ContextHandles& h = s.h;
    s.error = validateShader((name ? std::string(name) : std::string("(unnamed)")) + ".comp", spv, words, VK_SHADER_STAGE_COMPUTE_BIT, h.tierA,
                             h.layouts->tableSize());
    if (!s.error.empty()) return -1;
    VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    mi.codeSize = words * sizeof(uint32_t);
    mi.pCode = spv;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(h.device, &mi, nullptr, &module) != VK_SUCCESS) {
        s.error = "vkCreateShaderModule failed (invalid SPIR-V?)";
        return -1;
    }
    VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    ci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    ci.stage.module = module;
    ci.stage.pName = "main";
    ci.layout = h.layouts->pipelineLayout();
    VkPipeline pipeline = VK_NULL_HANDLE;
    const VkResult r = vkCreateComputePipelines(h.device, h.pipelineCache->handle(), 1, &ci, nullptr, &pipeline);
    vkDestroyShaderModule(h.device, module, nullptr);
    if (r != VK_SUCCESS) {
        s.error = "vkCreateComputePipelines failed (VkResult " + std::to_string(r) + ")";
        return -1;
    }
    s.computePipelines.push_back(pipeline);
    return static_cast<int>(s.computePipelines.size() - 1);
}

FrameStatus Presenter::dispatch(int computePipeline, const float push[32], uint32_t groupsX, int buffer) {
    Impl& s = *impl_;
    if (!s.frameOpen) return s.fail("dispatch called outside beginFrame/endFrame");
    if (computePipeline < 0 || static_cast<size_t>(computePipeline) >= s.computePipelines.size()) return s.fail("dispatch: unknown compute pipeline id");
    VkBuffer buf;
    uint64_t base, size;
    if (!s.h.resources->bufferInfo(buffer, buf, base, size)) return s.fail("dispatch: unknown buffer");
    Impl::ComputeJob job{};
    job.pipeline = s.computePipelines[computePipeline];
    if (!s.rings[s.frameIndex]->externalStorageSet(buf, base, size, job.set)) return s.fail("dispatch: out of descriptor sets");
    std::memcpy(job.push, push, sizeof(job.push));
    job.groups = groupsX;
    s.computeQueue.push_back(job);
    return FrameStatus::Ok;
}

FrameStatus Presenter::draw(int pipeline, uint32_t vertexCount, const float push[32]) {
    Impl& s = *impl_;
    if (!s.frameOpen) return s.fail("draw called outside beginFrame/endFrame");
    if (pipeline < 0 || static_cast<size_t>(pipeline) >= s.pipelines.size()) return s.fail("draw: unknown pipeline id");
    VkCommandBuffer cmd = s.frames[s.frameIndex].cmd;
    VkPipeline vk = s.pipelineFor(pipeline, s.format, VK_FORMAT_UNDEFINED, false);
    if (vk == VK_NULL_HANDLE) return FrameStatus::Error;
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, vk);
    // Negative height flips Y so GL clip space (+Y up) lands the right way up (VK_KHR_maintenance1, core in 1.1).
    const float w = static_cast<float>(s.extent.width), hgt = static_cast<float>(s.extent.height);
    const VkViewport viewport{0.0f, hgt, w, -hgt, 0.0f, 1.0f};
    const VkRect2D scissor{{0, 0}, s.extent};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    if (s.h.tierA) {
        vkCmdSetCullMode(cmd, VK_CULL_MODE_BACK_BIT);
        vkCmdSetFrontFace(cmd, VK_FRONT_FACE_COUNTER_CLOCKWISE);
    }
    vkCmdPushConstants(cmd, s.h.layouts->pipelineLayout(), DescriptorLayouts::kPushStages, 0, DescriptorLayouts::kPushConstantBytes, push);
    vkCmdDraw(cmd, vertexCount, 1, 0, 0);
    return FrameStatus::Ok;
}

bool Presenter::bindFrameData(const void* uniform, uint32_t uniformBytes, const void* storage, uint64_t storageBytes) {
    Impl& s = *impl_;
    if (!s.frameOpen) {
        s.fail("bindFrameData called outside beginFrame/endFrame");
        return false;
    }
    FrameRing::Binding b;
    if (!s.rings[s.frameIndex]->allocate(uniform, uniformBytes, storage, storageBytes, b)) {
        s.fail("bindFrameData: allocation failed (uniform data over 16 KB, or out of memory)");
        return false;
    }
    vkCmdBindDescriptorSets(s.frames[s.frameIndex].cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, s.h.layouts->pipelineLayout(), 0, 1, &b.set, 2,
                            b.offsets);
    return true;
}

int Presenter::bindTexture(int texture) {
    Impl& s = *impl_;
    int slot = -1;
    VkDescriptorSet set = VK_NULL_HANDLE;
    if (!s.frameOpen || !s.h.resources->textureBinding(texture, &slot, &set)) return -1;
    if (s.h.tierA) return slot; // the table is already bound; the slot travels in the push constants
    vkCmdBindDescriptorSets(s.frames[s.frameIndex].cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, s.h.layouts->pipelineLayout(), 1, 1, &set, 0,
                            nullptr);
    return 0;
}

bool Presenter::allocVertex(uint64_t bytes, uint32_t* block, uint64_t* offset) {
    Impl& s = *impl_;
    return s.frameOpen && s.rings[s.frameIndex]->allocVertex(bytes, block, offset);
}

uint8_t* Presenter::vertexBlock(uint32_t block, uint64_t* size) { return impl_->rings[impl_->frameIndex]->vertexBlockPtr(block, size); }

void Presenter::flushVertices(uint32_t block, uint64_t offset, uint64_t bytes) { impl_->rings[impl_->frameIndex]->flushVertices(block, offset, bytes); }

FrameStatus Presenter::submitCommandStream(const uint8_t* data, size_t bytes) {
    Impl& s = *impl_;
    if (!s.frameOpen) return s.fail("submitCommandStream called outside beginFrame/endFrame");
    VkCommandBuffer cmd = s.frames[s.frameIndex].cmd;
    FrameRing& ring = *s.rings[s.frameIndex];
    const int32_t* w = reinterpret_cast<const int32_t*>(data);
    const size_t n = bytes / 4;
    // Viewport, scissor and front face follow the current target (a render target flips them relative to the swapchain), so the
    // GL-convention values are kept in `s` and converted on every change and every target switch.
    auto bits = [](int32_t v) {
        float f;
        std::memcpy(&f, &v, sizeof(f));
        return f;
    };
    auto offset64 = [](int32_t lo, int32_t hi) { return (static_cast<uint64_t>(static_cast<uint32_t>(hi)) << 32) | static_cast<uint32_t>(lo); };

    size_t i = 0;
    while (i < n) {
        const int32_t op = w[i++];
        auto need = [&](size_t count) { return i + count <= n; };
        switch (op) {
            case cmd::BindPipeline: {
                if (!need(1)) return s.fail("command stream truncated (BindPipeline)");
                const int id = w[i++];
                if (id < 0 || static_cast<size_t>(id) >= s.pipelines.size()) return s.fail("command stream: unknown pipeline id");
                if (!s.bindPipelineVariant(cmd, id)) return s.fail(s.error);
                break;
            }
            case cmd::BindTarget: {
                if (!need(2)) return s.fail("command stream truncated (BindTarget)");
                const int tex = w[i], depth = w[i + 1];
                i += 2;
                if (tex == s.curOff && (tex < 0 || depth == s.off.depthId)) break;
                s.endPass(cmd);
                s.curOff = tex;
                if (tex >= 0) {
                    Impl::OffTarget t;
                    if (!s.h.resources->renderTargetInfo(tex, &t.image, &t.view, &t.format, &t.w, &t.h)) {
                        s.curOff = -1;
                        return s.fail("command stream: BindTarget names a texture that is not a render target");
                    }
                    if (depth >= 0) {
                        bool initialized;
                        if (!s.h.resources->depthImageInfo(depth, &t.depthImage, &t.depthView, &t.depthFormat, &t.hasStencil, &initialized)) {
                            s.curOff = -1;
                            return s.fail("command stream: BindTarget names an unknown depth image");
                        }
                        t.depthId = depth;
                    }
                    s.off = t;
                }
                // Dynamic state is per command buffer, not per pass: re-derive what depends on the target.
                s.applyViewport(cmd);
                s.applyScissor(cmd);
                if (s.h.tierA) vkCmdSetFrontFace(cmd, s.frontFace());
                // Pipelines belong to a colour/depth format (and on Tier B to a render pass): bind the variant for this target
                if (s.curPipeline >= 0 && !s.bindPipelineVariant(cmd, s.curPipeline)) return s.fail(s.error);
                break;
            }
            case cmd::DepthState: {
                if (!need(3)) return s.fail("command stream truncated (DepthState)");
                s.dsState.depthTest = w[i] != 0;
                s.dsState.depthWrite = w[i + 1] != 0;
                s.dsState.depthFunc = static_cast<uint32_t>(w[i + 2]) & 7u;
                i += 3;
                s.applyDepthStencil(cmd);
                if (!s.h.tierA && s.curPipeline >= 0 && !s.bindPipelineVariant(cmd, s.curPipeline)) return s.fail(s.error);
                break;
            }
            case cmd::StencilState: {
                if (!need(8)) return s.fail("command stream truncated (StencilState)");
                s.dsState.stencilTest = w[i] != 0;
                s.dsState.stencilFunc = static_cast<uint32_t>(w[i + 1]) & 7u;
                s.dsState.ref = static_cast<uint32_t>(w[i + 2]);
                s.dsState.compareMask = static_cast<uint32_t>(w[i + 3]);
                s.dsState.writeMask = static_cast<uint32_t>(w[i + 4]);
                s.dsState.sfail = Impl::stencilOp(static_cast<uint32_t>(w[i + 5]));
                s.dsState.dpfail = Impl::stencilOp(static_cast<uint32_t>(w[i + 6]));
                s.dsState.dppass = Impl::stencilOp(static_cast<uint32_t>(w[i + 7]));
                i += 8;
                s.applyDepthStencil(cmd);
                if (!s.h.tierA && s.curPipeline >= 0 && !s.bindPipelineVariant(cmd, s.curPipeline)) return s.fail(s.error);
                break;
            }
            case cmd::PushConstants:
                if (!need(32)) return s.fail("command stream truncated (PushConstants)");
                vkCmdPushConstants(cmd, s.h.layouts->pipelineLayout(), DescriptorLayouts::kPushStages, 0, DescriptorLayouts::kPushConstantBytes, w + i);
                i += 32;
                break;
            case cmd::Viewport: {
                if (!need(4)) return s.fail("command stream truncated (Viewport)");
                std::memcpy(s.glViewport, w + i, sizeof(s.glViewport));
                s.viewportSet = true;
                i += 4;
                s.applyViewport(cmd);
                break;
            }
            case cmd::Scissor:
                if (!need(4)) return s.fail("command stream truncated (Scissor)");
                std::memcpy(s.glScissor, w + i, sizeof(s.glScissor));
                i += 4;
                s.applyScissor(cmd);
                break;
            case cmd::ScissorEnable:
                if (!need(1)) return s.fail("command stream truncated (ScissorEnable)");
                s.scissorOn = w[i++] != 0;
                s.applyScissor(cmd);
                break;
            case cmd::Clear: {
                if (!need(7)) return s.fail("command stream truncated (Clear)");
                const int32_t mask = w[i];
                Impl::PassClear pc;
                for (int c = 0; c < 4; ++c) pc.rgba[c] = bits(w[i + 1 + c]);
                pc.depthValue = bits(w[i + 5]);
                pc.stencilValue = static_cast<uint32_t>(w[i + 6]);
                i += 7;
                // Depth and stencil only exist on a render target that has them; clearing them elsewhere is a no-op, as in GL.
                const VkFormat depthFmt = s.targetDepthFormat();
                pc.color = (mask & cmd::kGlColorBufferBit) != 0;
                pc.depth = (mask & cmd::kGlDepthBufferBit) != 0 && depthFmt != VK_FORMAT_UNDEFINED;
                pc.stencil = (mask & cmd::kGlStencilBufferBit) != 0 && Impl::formatHasStencil(depthFmt);
                if (!pc.color && !pc.depth && !pc.stencil) break;
                if (!s.passOpen() && !s.scissorOn) { // a full clear that starts the pass is the pass's load op: no clear command at all
                    if (!s.beginPass(cmd, &pc)) return s.fail(s.error);
                    break;
                }
                if (!s.passOpen() && !s.beginPass(cmd, nullptr)) return s.fail(s.error);
                VkClearAttachment atts[2]{};
                uint32_t count = 0;
                if (pc.color) {
                    atts[count].aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
                    atts[count].colorAttachment = 0;
                    for (int c = 0; c < 4; ++c) atts[count].clearValue.color.float32[c] = pc.rgba[c];
                    ++count;
                }
                if (pc.depth || pc.stencil) {
                    atts[count].aspectMask = (pc.depth ? VK_IMAGE_ASPECT_DEPTH_BIT : 0) | (pc.stencil ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
                    atts[count].clearValue.depthStencil = {pc.depthValue, pc.stencilValue};
                    ++count;
                }
                VkClearRect rect{};
                rect.rect = s.currentScissor();
                rect.layerCount = 1;
                if (rect.rect.extent.width > 0 && rect.rect.extent.height > 0) vkCmdClearAttachments(cmd, count, atts, 1, &rect);
                break;
            }
            case cmd::BindVertex: {
                if (!need(3)) return s.fail("command stream truncated (BindVertex)");
                VkBuffer buf = ring.vertexBlockBuffer(static_cast<uint32_t>(w[i]));
                const VkDeviceSize off = offset64(w[i + 1], w[i + 2]);
                i += 3;
                if (buf == VK_NULL_HANDLE) return s.fail("command stream: unknown vertex block");
                vkCmdBindVertexBuffers(cmd, 0, 1, &buf, &off);
                break;
            }
            case cmd::BindIndexBuffer: {
                if (!need(4)) return s.fail("command stream truncated (BindIndexBuffer)");
                VkBuffer buf;
                uint64_t base, size;
                if (!s.h.resources->bufferInfo(w[i], buf, base, size)) return s.fail("command stream: unknown index buffer");
                vkCmdBindIndexBuffer(cmd, buf, base + offset64(w[i + 1], w[i + 2]), w[i + 3] == 0 ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);
                i += 4;
                break;
            }
            case cmd::BindIndexRing: {
                if (!need(4)) return s.fail("command stream truncated (BindIndexRing)");
                VkBuffer buf = ring.vertexBlockBuffer(static_cast<uint32_t>(w[i]));
                if (buf == VK_NULL_HANDLE) return s.fail("command stream: unknown index block");
                vkCmdBindIndexBuffer(cmd, buf, offset64(w[i + 1], w[i + 2]), w[i + 3] == 0 ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);
                i += 4;
                break;
            }
            case cmd::BindTexture: {
                if (!need(1)) return s.fail("command stream truncated (BindTexture)");
                const int tex = w[i++];
                if (!s.h.tierA) { // Tier A: the table is bound for the whole frame and the slot is in the vertices
                    int slot;
                    VkDescriptorSet set;
                    if (!s.h.resources->textureBinding(tex, &slot, &set)) return s.fail("command stream: unknown texture");
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, s.h.layouts->pipelineLayout(), 1, 1, &set, 0, nullptr);
                }
                break;
            }
            case cmd::DrawIndexed:
                if (!need(3)) return s.fail("command stream truncated (DrawIndexed)");
                if (!s.passOpen() && !s.beginPass(cmd, nullptr)) return s.fail(s.error);
                vkCmdDrawIndexed(cmd, static_cast<uint32_t>(w[i]), 1, static_cast<uint32_t>(w[i + 1]), w[i + 2], 0);
                i += 3;
                break;
            case cmd::BindStorage: {
                if (!need(1)) return s.fail("command stream truncated (BindStorage)");
                VkBuffer buf;
                uint64_t base, size;
                if (!s.h.resources->bufferInfo(w[i++], buf, base, size)) return s.fail("command stream: unknown storage buffer");
                VkDescriptorSet set;
                if (!ring.externalStorageSet(buf, base, size, set)) return s.fail("command stream: out of descriptor sets");
                const uint32_t offsets[2] = {0, 0};
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, s.h.layouts->pipelineLayout(), 0, 1, &set, 2, offsets);
                break;
            }
            case cmd::Draw:
                if (!need(2)) return s.fail("command stream truncated (Draw)");
                if (!s.passOpen() && !s.beginPass(cmd, nullptr)) return s.fail(s.error);
                vkCmdDraw(cmd, static_cast<uint32_t>(w[i]), 1, static_cast<uint32_t>(w[i + 1]), 0);
                i += 2;
                break;
            default: return s.fail("command stream: unknown opcode " + std::to_string(op));
        }
    }
    return FrameStatus::Ok;
}

const std::vector<uint8_t>& Presenter::capturedPixels() const { return impl_->captured; }

uint32_t Presenter::width() const { return impl_->extent.width; }
uint32_t Presenter::height() const { return impl_->extent.height; }
uint32_t Presenter::swapchainFormat() const { return static_cast<uint32_t>(impl_->format); }
uint32_t Presenter::imageCount() const { return static_cast<uint32_t>(impl_->images.size()); }
const std::string& Presenter::lastError() const { return impl_->error; }

} // namespace gdxvk
