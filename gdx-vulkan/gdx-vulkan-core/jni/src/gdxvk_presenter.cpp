#include "gdxvk_presenter.h"
#include "gdxvk_swappy.h"

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
#include <chrono>
#include <cstdlib>
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
    VkExtent2D extent{0, 0}; // the swapchain image, in the display native orientation
    // Pre-rotation (Android): the swapchain is built with the display's current rotation so the compositor does not rotate it. The
    // application still sees the logical (unrotated) size; the output is rotated in clip space (specialization constant 0 of every vertex
    // shader that calls gdx_vk_clip) and viewport/scissor rectangles are rotated into the image. `rot` is quarter turns clockwise.
    // Optional depth(-stencil) image of the swapchain's size (plan, Phase 5), so stock 3D code can depth-test on screen. It is a resource depth
    // image like a framebuffer's, recreated with the swapchain. Its contents are not kept between frames: the first pass of a frame clears or
    // discards it; a pass resumed after an offscreen pass loads it.
    int swapDepthWanted = 0; // 0 none, 1 depth, 2 depth + stencil
    int swapDepthId = -1;
    VkFormat swapDepthFmt = VK_FORMAT_UNDEFINED;
    bool preRotate = false;
    int forcedRot = -1; // diagnostics/tests: render as if the display were rotated by this many quarter turns (the surface keeps its transform)
    uint32_t rot = 0;
    uint32_t logicalW() const { return (rot & 1u) ? extent.height : extent.width; }
    uint32_t logicalH() const { return (rot & 1u) ? extent.width : extent.height; }
    VkExtent2D requested{0, 0};
    bool vsync = true;
    bool dirty = true; // swapchain needs (re)creation before the next frame

    // VK_KHR_swapchain_maintenance1: every present carries a fence (one per frame slot) that signals when the presentation engine is done
    // with the swapchain's images and semaphores. A swapchain that was replaced is parked here, with the fences of its last presents,
    // and destroyed when they have all signalled: resizing then never waits for the device.
    uint64_t swappyPeriodNs = 0;
    bool swappyOn = false; // the current swapchain presents through Swappy (frame pacing); its present info cannot carry our fence
    bool fencesOn() const { return h.presentFences && !swappyOn; }
    VkFence slotFence[kFramesInFlight] = {};
    bool slotFencePending[kFramesInFlight] = {};
    struct RetiredSwap {
        VkSwapchainKHR swapchain = VK_NULL_HANDLE;
        std::vector<VkImageView> views;
        std::vector<VkFramebuffer> framebuffers;
        std::vector<VkSemaphore> semaphores;
        std::vector<VkFence> fences; // owned: signalled when the last present of this swapchain has finished
    };
    std::vector<RetiredSwap> retiredSwaps;
    std::vector<VkFence> strayFences; // present fences of failed presents that never signalled; destroyed with the presenter

    void destroyRetired(RetiredSwap& r) {
        for (VkFramebuffer f : r.framebuffers) vkDestroyFramebuffer(h.device, f, nullptr);
        for (VkImageView v : r.views) vkDestroyImageView(h.device, v, nullptr);
        for (VkSemaphore s : r.semaphores) vkDestroySemaphore(h.device, s, nullptr);
        for (VkFence f : r.fences) vkDestroyFence(h.device, f, nullptr);
        if (r.swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(h.device, r.swapchain, nullptr);
        r = RetiredSwap();
    }

    // Destroys the parked swapchains whose fences have signalled (all of them with `force`, after a device wait).
    void collectRetired(bool force) {
        for (size_t i = 0; i < retiredSwaps.size();) {
            bool done = true;
            for (VkFence f : retiredSwaps[i].fences) {
                if (force) vkWaitForFences(h.device, 1, &f, VK_TRUE, 1000000000ull); // a device wait does not retire a present fence
                else if (vkGetFenceStatus(h.device, f) != VK_SUCCESS) { done = false; break; }
            }
            if (done) {
                destroyRetired(retiredSwaps[i]);
                retiredSwaps.erase(retiredSwaps.begin() + static_cast<std::ptrdiff_t>(i));
            } else {
                ++i;
            }
        }
    }

    // Moves the current swapchain and its per-image objects into the parked list. The fences of the last presents go with it.
    void parkSwapchain() {
        // A slot with no pending present fence means the last frame of that slot is not covered (a present that failed, or too few frames
        // since the swapchain was built): fall back to waiting for the device.
        bool covered = true;
        for (uint32_t i = 0; i < kFramesInFlight; ++i) covered = covered && slotFencePending[i];
        if (!covered) vkDeviceWaitIdle(h.device);
        RetiredSwap r;
        r.swapchain = swapchain;
        r.views.swap(views);
        r.framebuffers.swap(framebuffers);
        r.semaphores.swap(renderDone);
        for (uint32_t i = 0; i < kFramesInFlight; ++i) {
            if (slotFence[i] == VK_NULL_HANDLE) continue;
            if (slotFencePending[i]) r.fences.push_back(slotFence[i]);
            else vkDestroyFence(h.device, slotFence[i], nullptr);
            slotFence[i] = VK_NULL_HANDLE;
            slotFencePending[i] = false;
        }
        images.clear();
        swapchain = VK_NULL_HANDLE;
        retiredSwaps.push_back(std::move(r));
    }

    std::vector<VkImage> images;
    std::vector<VkImageView> views;
    std::vector<VkSemaphore> renderDone; // one per swapchain image, signalled by submit, waited by present
    VkRenderPass renderPass = VK_NULL_HANDLE;       // Tier B only
    std::vector<VkFramebuffer> framebuffers;        // Tier B only

    // The colour attachment formats of a render target (the swapchain has one). A pipeline writes exactly as many colour outputs as its target has.
    static constexpr uint32_t kMaxColor = 8;
    struct ColorSet {
        uint32_t count = 1;
        VkFormat f[kMaxColor] = {};
        bool operator==(const ColorSet& o) const {
            if (count != o.count) return false;
            for (uint32_t i = 0; i < count; ++i) if (f[i] != o.f[i]) return false;
            return true;
        }
    };

    struct PipelineRec {
        VkShaderModule vs = VK_NULL_HANDLE, fs = VK_NULL_HANDLE;
        PipelineDesc desc;
        std::string name;
        struct Variant {
            ColorSet colors;
            VkFormat depthFormat;
            bool offscreen;  // Tier B only: the front face is baked in
            uint32_t rot;    // pre-rotation of the swapchain this was built for (0 for render targets)
            uint32_t ds;     // Tier B only: the packed depth/stencil state (see DsState::pack)
            uint32_t cull;   // Tier B only: the cull mode (VkCullModeFlags) baked into the pipeline
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

    // GDXVK_PROFILE=1 prints, when the presenter closes, where a frame's CPU time went in beginFrame and endFrame (milliseconds per frame).
    const bool profOn = std::getenv("GDXVK_PROFILE") != nullptr;
    double prof[8] = {};
    uint64_t profFrames = 0;
    std::chrono::steady_clock::time_point profLast;
    void profStart() { if (profOn) profLast = std::chrono::steady_clock::now(); }
    void profLap(int slot) {
        if (!profOn) return;
        const auto n = std::chrono::steady_clock::now();
        prof[slot] += std::chrono::duration<double, std::milli>(n - profLast).count();
        profLast = n;
    }

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

    // The application's cull state, when it set any (glEnable(GL_CULL_FACE), glCullFace): a VkCullModeFlags that replaces what each
    // pipeline was created with. -1 = the pipelines' own.
    int cullOverride = -1;
    uint32_t effectiveCull(int id) const { return cullOverride >= 0 ? static_cast<uint32_t>(cullOverride) : pipelines[id].desc.cullMode; }

    ColorSet swapColors() const {
        ColorSet c;
        c.count = 1;
        c.f[0] = format;
        return c;
    }

    VkPipeline pipelineFor(int id, const ColorSet& colors, VkFormat depthFmt, bool offscreen) {
        PipelineRec& rec = pipelines[id];
        const bool bakedKind = !h.tierA && offscreen;
        // Without a depth attachment the depth and stencil state does nothing: one variant serves every state.
        const uint32_t bakedDs = (!h.tierA && depthFmt != VK_FORMAT_UNDEFINED) ? dsState.pack() : 0u;
        const uint32_t bakedCull = h.tierA ? 0u : effectiveCull(id); // Tier A sets the cull mode dynamically
        const uint32_t vrot = offscreen ? 0u : rot; // render targets are never pre-rotated
        for (const PipelineRec::Variant& v : rec.variants) {
            if (v.colors == colors && v.depthFormat == depthFmt && v.offscreen == bakedKind && v.rot == vrot && v.ds == bakedDs && v.cull == bakedCull) return v.pipeline;
        }
        VkPipeline p = buildPipeline(rec, colors, depthFmt, offscreen, bakedDs, bakedCull, vrot);
        if (p != VK_NULL_HANDLE) {
            rec.variants.push_back({colors, depthFmt, bakedKind, vrot, bakedDs, bakedCull, p});
            nameObject(h, VK_OBJECT_TYPE_PIPELINE, (uint64_t)p,
                       (rec.name + (offscreen ? " (render target, " : " (swapchain, ") + std::to_string(colors.count) + " colour" + (depthFmt != VK_FORMAT_UNDEFINED ? ", depth)" : ")")).c_str());
        }
        return p;
    }

    VkPipeline buildPipeline(const PipelineRec& rec, const ColorSet& colors, VkFormat depthFmt, bool offscreen, uint32_t bakedDs, uint32_t bakedCull, uint32_t vrot) {
        const PipelineDesc& desc = rec.desc;
        if (desc.colorOutputs != colors.count) {
            error = "pipeline " + rec.name + " writes " + std::to_string(desc.colorOutputs) + " colour output(s) but its render target has " +
                    std::to_string(colors.count) + " colour attachment(s)";
            return VK_NULL_HANDLE;
        }
        VkPipelineShaderStageCreateInfo stages[2]{{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO},
                                                  {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}};
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = rec.vs;
        stages[0].pName = "main";
        // Specialization constant 0 is the pre-rotation (gdx_vk_common.glsl). A shader without that constant ignores the entry.
        // Constants 1..n are the pipeline's own flags (desc.spec), the same for both stages; a shader ignores ids it does not declare.
        std::vector<int32_t> specData;
        std::vector<VkSpecializationMapEntry> specEntries;
        specData.push_back(static_cast<int32_t>(vrot));
        for (size_t k = 0; k < desc.spec.size(); ++k) specData.push_back(desc.spec[k]);
        for (uint32_t k = 0; k < specData.size(); ++k) specEntries.push_back({k, static_cast<uint32_t>(k * sizeof(int32_t)), sizeof(int32_t)});
        const VkSpecializationInfo specInfo{static_cast<uint32_t>(specEntries.size()), specEntries.data(), specData.size() * sizeof(int32_t), specData.data()};
        stages[0].pSpecializationInfo = &specInfo;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = rec.fs;
        stages[1].pName = "main";
        stages[1].pSpecializationInfo = &specInfo;

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
        rs.cullMode = static_cast<VkCullModeFlags>(h.tierA ? desc.cullMode : bakedCull); // baked on Tier B, set dynamically on Tier A
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
        VkPipelineColorBlendAttachmentState cbas[kMaxColor];
        for (uint32_t i = 0; i < colors.count; ++i) cbas[i] = cba; // the same blend state on every attachment
        VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        cb.attachmentCount = colors.count;
        cb.pAttachments = cbas;

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
        rendering.colorAttachmentCount = colors.count;
        rendering.pColorAttachmentFormats = colors.f;
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
            VkRenderPass pass = (offscreen || depthFmt != VK_FORMAT_UNDEFINED) ? offscreenPass(colors, depthFmt, false, DepthOps()) : renderPass;
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

    // What a pass does with the depth/stencil attachment at its start and end. Load: 0 load, 1 clear, 2 do not care. A framebuffer's depth is
    // loaded and stored, as GL keeps it between passes; a transient one (opt-in) and a depth image never used before are not.
    struct DepthOps {
        uint8_t depthLoad = 0, stencilLoad = 0;
        bool store = true;
        bool operator==(const DepthOps& o) const { return depthLoad == o.depthLoad && stencilLoad == o.stencilLoad && store == o.store; }
    };
    struct OffPass {
        ColorSet colors;
        VkFormat depth = VK_FORMAT_UNDEFINED;
        bool clearColor = false;
        DepthOps ops;
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
        uint32_t count = 0; // colour attachments, all the same size
        VkImage image[kMaxColor] = {};
        VkImageView view[kMaxColor] = {};
        VkFormat format[kMaxColor] = {};
        uint32_t w = 0, h = 0;
        ColorSet colorSet() const {
            ColorSet c;
            c.count = count;
            for (uint32_t i = 0; i < count; ++i) c.f[i] = format[i];
            return c;
        }
        int depthId = -1;                           // the depth image, -1 = none
        VkImage depthImage = VK_NULL_HANDLE;
        VkImageView depthView = VK_NULL_HANDLE;
        VkFormat depthFormat = VK_FORMAT_UNDEFINED;
        bool hasStencil = false;
        bool depthTransient = false; // never loaded, never stored: lives in on-chip memory on tile-based GPUs (opt-in, see Resources::createDepthImage)
    };
    int curOff = -1;           // texture id of the offscreen target's first colour attachment, -1 = the swapchain
    int offIds[kMaxColor] = {}; // texture ids of all its colour attachments
    OffTarget off;             // valid while curOff >= 0
    bool swapOpen = false;     // a pass on the swapchain image is recording
    bool offOpen = false;      // a pass on the offscreen image is recording
    VkFramebuffer offFb = VK_NULL_HANDLE; // Tier B, for the open offscreen pass
    // Pass scheduling: the frame's swapchain pass starts at the first thing drawn or cleared on the swapchain, not at beginFrame, so framebuffer
    // passes that come first (post-processing chains, shadow maps) cost no suspend and resume of the main pass (expensive on tile-based GPUs).
    bool swapStarted = false;  // the frame's first swapchain pass has begun
    float frameClear[4] = {};  // what beginFrame was asked to clear to
    uint32_t passCount[2] = {}, lastPassCount[2] = {}; // passes begun this frame and in the last finished one: swapchain, offscreen
    int curPipeline = -1;      // last pipeline bound by the stream
    int32_t pushShadow[32] = {}; // the push-constant block as the stream built it (raw words); pushed in full before a draw when it changed
    bool pushDirty = false;
    void flushPush(VkCommandBuffer cmd) {
        if (!pushDirty) return;
        vkCmdPushConstants(cmd, h.layouts->pipelineLayout(), DescriptorLayouts::kPushStages, 0, DescriptorLayouts::kPushConstantBytes, pushShadow);
        pushDirty = false;
    }
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
    VkRenderPass offscreenPass(const ColorSet& colors, VkFormat depth, bool clearColor, DepthOps ops) {
        if (depth == VK_FORMAT_UNDEFINED) ops = DepthOps();
        if (!formatHasStencil(depth)) ops.stencilLoad = 2; // no stencil aspect: nothing to load
        for (const OffPass& p : offPasses) {
            if (p.colors == colors && p.depth == depth && p.clearColor == clearColor && p.ops == ops) return p.pass;
        }
        const uint32_t n = colors.count;
        VkAttachmentDescription att[kMaxColor + 1]{};
        VkAttachmentReference crefs[kMaxColor];
        for (uint32_t i = 0; i < n; ++i) {
            att[i].format = colors.f[i];
            att[i].samples = VK_SAMPLE_COUNT_1_BIT;
            att[i].loadOp = clearColor ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD; // every attachment, as GL clears every draw buffer
            att[i].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            att[i].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            att[i].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
            att[i].initialLayout = att[i].finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            crefs[i] = {i, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        }
        const bool hasDepth = depth != VK_FORMAT_UNDEFINED;
        const bool stencil = formatHasStencil(depth);
        VkAttachmentDescription& da = att[n];
        if (hasDepth) {
            da.format = depth;
            da.samples = VK_SAMPLE_COUNT_1_BIT;
            const VkAttachmentLoadOp loadOps[3] = {VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_LOAD_OP_DONT_CARE};
            const VkAttachmentStoreOp storeOp = ops.store ? VK_ATTACHMENT_STORE_OP_STORE : VK_ATTACHMENT_STORE_OP_DONT_CARE;
            da.loadOp = loadOps[ops.depthLoad];
            da.storeOp = storeOp;
            da.stencilLoadOp = loadOps[ops.stencilLoad];
            da.stencilStoreOp = stencil ? storeOp : VK_ATTACHMENT_STORE_OP_DONT_CARE;
            da.initialLayout = da.finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        }
        VkAttachmentReference dref{n, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{};
        sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = n;
        sub.pColorAttachments = crefs;
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
        ri.attachmentCount = n + (hasDepth ? 1 : 0);
        ri.pAttachments = att;
        ri.subpassCount = 1;
        ri.pSubpasses = &sub;
        ri.dependencyCount = 1;
        ri.pDependencies = &dep;
        OffPass p;
        p.colors = colors;
        p.depth = depth;
        p.clearColor = clearColor;
        p.ops = ops;
        if (vkCreateRenderPass(h.device, &ri, nullptr, &p.pass) != VK_SUCCESS) return VK_NULL_HANDLE;
        offPasses.push_back(p);
        return p.pass;
    }

    // The size GL sees: a render target's, or the swapchain's logical size. physW/physH are the image the pass renders into.
    uint32_t targetW() const { return curOff >= 0 ? off.w : logicalW(); }
    uint32_t targetH() const { return curOff >= 0 ? off.h : logicalH(); }
    uint32_t physW() const { return curOff >= 0 ? off.w : extent.width; }
    uint32_t physH() const { return curOff >= 0 ? off.h : extent.height; }
    // A rectangle in the logical swapchain image (origin top-left) as the same rectangle in the pre-rotated image.
    VkRect2D rotateRect(int32_t x, int32_t y, int32_t w, int32_t h) const {
        const int32_t lw = static_cast<int32_t>(logicalW()), lh = static_cast<int32_t>(logicalH());
        switch (rot) {
            case 1: return VkRect2D{{lh - (y + h), x}, {static_cast<uint32_t>(h), static_cast<uint32_t>(w)}};
            case 2: return VkRect2D{{lw - (x + w), lh - (y + h)}, {static_cast<uint32_t>(w), static_cast<uint32_t>(h)}};
            case 3: return VkRect2D{{y, lw - (x + w)}, {static_cast<uint32_t>(h), static_cast<uint32_t>(w)}};
            default: return VkRect2D{{x, y}, {static_cast<uint32_t>(w), static_cast<uint32_t>(h)}};
        }
    }
    ColorSet targetColors() const { return curOff >= 0 ? off.colorSet() : swapColors(); }
    VkFormat targetDepthFormat() const { return curOff >= 0 ? off.depthFormat : swapDepthFmt; }
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
        const int32_t cw = std::max(0, x1 - x0), ch = std::max(0, y1 - y0);
        if (curOff < 0 && rot != 0) return rotateRect(x0, y0, cw, ch);
        return VkRect2D{{x0, y0}, {static_cast<uint32_t>(cw), static_cast<uint32_t>(ch)}};
    }
    VkRect2D currentScissor() const {
        return scissorOn ? toVkRect(glScissor[0], glScissor[1], glScissor[2], glScissor[3]) : VkRect2D{{0, 0}, {physW(), physH()}};
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
        else if (rot == 0) v = {x, th - y, w, -hh, 0.0f, 1.0f};            // negative height: the image comes out upright
        else {
            // The same viewport rectangle (top-left origin in the logical image), rotated into the pre-rotated image.
            const VkRect2D r = rotateRect(static_cast<int32_t>(x), static_cast<int32_t>(th - y - hh), static_cast<int32_t>(w), static_cast<int32_t>(hh));
            v = {static_cast<float>(r.offset.x), static_cast<float>(r.offset.y + static_cast<int32_t>(r.extent.height)), static_cast<float>(r.extent.width),
                 -static_cast<float>(r.extent.height), 0.0f, 1.0f};
        }
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
        VkPipeline vk = pipelineFor(id, targetColors(), targetDepthFormat(), curOff >= 0);
        if (vk == VK_NULL_HANDLE) return false;
        curPipeline = id;
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, vk);
        if (h.tierA) {
            vkCmdSetCullMode(cmd, static_cast<VkCullModeFlags>(effectiveCull(id)));
            vkCmdSetFrontFace(cmd, frontFace());
        }
        return true;
    }

    // Ends whichever pass is recording. An offscreen image goes back to SHADER_READ_ONLY_OPTIMAL for sampling.
    void endPass(VkCommandBuffer cmd) {
        if (swapOpen) {
            if (h.tierA) vkCmdEndRendering(cmd); else vkCmdEndRenderPass(cmd);
            swapOpen = false;
            if (passLabelOpen) endLabel(h, cmd);
            passLabelOpen = false;
        }
        if (offOpen) {
            if (h.tierA) vkCmdEndRendering(cmd); else vkCmdEndRenderPass(cmd);
            offOpen = false;
            if (passLabelOpen) endLabel(h, cmd);
            passLabelOpen = false;
            for (uint32_t i = 0; i < off.count; ++i) {
                imageBarrier(cmd, off.image[i], VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                             VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
            if (offFb != VK_NULL_HANDLE) {
                VkDevice dev = h.device;
                VkFramebuffer fb = offFb;
                h.deletion->retire([dev, fb]() { vkDestroyFramebuffer(dev, fb, nullptr); });
                offFb = VK_NULL_HANDLE;
            }
        }
    }

    // Starts a pass on the current target, clearing what `clear` names and loading the rest. False on failure (error set).
    bool passLabelOpen = false;

    // The swapchain pass when the swapchain has a depth image. Colour is handled as in the other swapchain pass; depth is cleared by a clear
    // that starts the pass, discarded when it is the frame's first pass without one (GL leaves it undefined too), and loaded when the pass
    // resumes after an offscreen pass.
    bool beginSwapPassWithDepth(VkCommandBuffer cmd, bool cc, bool dc, bool sc, const VkClearValue* cv, const VkRect2D& area) {
        VkImage dimg;
        VkImageView dview;
        VkFormat dfmt;
        bool dstencil, initialized, transient;
        h.resources->depthImageInfo(swapDepthId, &dimg, &dview, &dfmt, &dstencil, &initialized, &transient);
        const bool first = !swapStarted;
        DepthOps ops;
        ops.depthLoad = dc ? 1 : first ? 2 : 0;
        ops.stencilLoad = !dstencil ? 2 : sc ? 1 : first ? 2 : 0;
        ops.store = true; // a resumed pass needs it; not storing on the last pass of the frame is an optimisation for tile-based GPUs
        const VkImageAspectFlags aspect = VK_IMAGE_ASPECT_DEPTH_BIT | (dstencil ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
        const VkPipelineStageFlags tests = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
        const VkAccessFlags rw = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        if (!initialized) {
            imageBarrier(cmd, dimg, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, tests,
                         rw, aspect);
            h.resources->markDepthInitialized(swapDepthId);
        } else {
            imageBarrier(cmd, dimg, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, tests,
                         VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, tests, rw, aspect);
        }
        const VkAttachmentLoadOp loadOps[3] = {VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_LOAD_OP_DONT_CARE};
        if (h.tierA) {
            VkRenderingAttachmentInfo ca{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
            ca.imageView = views[imageIndex];
            ca.imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            ca.loadOp = cc ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
            ca.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            ca.clearValue = cv[0];
            VkRenderingAttachmentInfo da{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
            da.imageView = dview;
            da.imageLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
            da.loadOp = loadOps[ops.depthLoad];
            da.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            da.clearValue = cv[1];
            VkRenderingAttachmentInfo sa = da;
            sa.loadOp = loadOps[ops.stencilLoad];
            VkRenderingInfo ri{VK_STRUCTURE_TYPE_RENDERING_INFO};
            ri.renderArea = area;
            ri.layerCount = 1;
            ri.colorAttachmentCount = 1;
            ri.pColorAttachments = &ca;
            ri.pDepthAttachment = &da;
            if (dstencil) ri.pStencilAttachment = &sa;
            vkCmdBeginRendering(cmd, &ri);
        } else {
            VkRenderPass pass = offscreenPass(swapColors(), swapDepthFmt, cc, ops);
            if (pass == VK_NULL_HANDLE) {
                error = "vkCreateRenderPass failed for the swapchain with depth";
                return false;
            }
            VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
            rp.renderPass = pass;
            rp.framebuffer = framebuffers[imageIndex];
            rp.renderArea = area;
            VkClearValue cvs[2] = {cv[0], cv[1]};
            rp.clearValueCount = 2;
            rp.pClearValues = cvs;
            vkCmdBeginRenderPass(cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);
        }
        swapOpen = true;
        swapStarted = true;
        ++passCount[0];
        return true;
    }

    bool beginPass(VkCommandBuffer cmd, const PassClear* clear) {
        if (h.debugUtils) {
            const std::string label = curOff < 0 ? std::string("gdx: swapchain pass") : "gdx: render target pass (texture " + std::to_string(curOff) +
                                                                                           (off.count > 1 ? " and " + std::to_string(off.count - 1) + " more)" : ")");
            beginLabel(h, cmd, label.c_str(), curOff < 0 ? 0.3f : 0.9f, 0.5f, curOff < 0 ? 0.9f : 0.4f);
            passLabelOpen = true;
        }
        bool cc = clear && clear->color;
        const bool dc = clear && clear->depth, sc = clear && clear->stencil;
        VkClearValue cv[2]{};
        if (clear) {
            for (int i = 0; i < 4; ++i) cv[0].color.float32[i] = clear->rgba[i];
            cv[1].depthStencil = {clear->depthValue, clear->stencilValue};
        }
        if (curOff < 0 && !swapStarted && !cc) { // the frame's first swapchain pass clears to the frame's colour, unless a clear that starts it names another
            cc = true;
            for (int i = 0; i < 4; ++i) cv[0].color.float32[i] = frameClear[i];
        }
        const VkRect2D area{{0, 0}, {physW(), physH()}};
        if (curOff < 0 && swapDepthFmt != VK_FORMAT_UNDEFINED) return beginSwapPassWithDepth(cmd, cc, dc, sc, cv, area);
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
            swapStarted = true;
            ++passCount[0];
            return true;
        }

        for (uint32_t i = 0; i < off.count; ++i) {
            imageBarrier(cmd, off.image[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, 0,
                         VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT);
        }
        const bool hasDepth = off.depthFormat != VK_FORMAT_UNDEFINED;
        DepthOps ops;
        if (hasDepth) {
            VkImage img;
            VkImageView view;
            VkFormat fmt;
            bool stencil, initialized, transient;
            h.resources->depthImageInfo(off.depthId, &img, &view, &fmt, &stencil, &initialized, &transient);
            // Clear what the pass clears; otherwise load, except where there is nothing worth loading: a depth image no pass has used yet
            // (its contents are undefined, as in GL) and a transient one, which keeps nothing between passes.
            const bool nothingToLoad = !initialized || transient;
            ops.depthLoad = dc ? 1 : nothingToLoad ? 2 : 0;
            ops.stencilLoad = sc ? 1 : nothingToLoad ? 2 : 0;
            ops.store = !transient;
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
            VkRenderingAttachmentInfo cas[kMaxColor];
            for (uint32_t i = 0; i < off.count; ++i) {
                VkRenderingAttachmentInfo& ca = cas[i];
                ca = {VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
                ca.imageView = off.view[i];
                ca.imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                ca.loadOp = cc ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD; // a clear reaches every attachment, as GL clears every draw buffer
                ca.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
                ca.clearValue = cv[0];
            }
            VkRenderingAttachmentInfo da{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
            da.imageView = off.depthView;
            da.imageLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
            const VkAttachmentLoadOp loadOps[3] = {VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_LOAD_OP_DONT_CARE};
            da.loadOp = loadOps[ops.depthLoad];
            da.storeOp = ops.store ? VK_ATTACHMENT_STORE_OP_STORE : VK_ATTACHMENT_STORE_OP_DONT_CARE;
            da.clearValue = cv[1];
            VkRenderingAttachmentInfo sa = da;
            sa.loadOp = loadOps[ops.stencilLoad];
            VkRenderingInfo ri{VK_STRUCTURE_TYPE_RENDERING_INFO};
            ri.renderArea = area;
            ri.layerCount = 1;
            ri.colorAttachmentCount = off.count;
            ri.pColorAttachments = cas;
            if (hasDepth) ri.pDepthAttachment = &da;
            if (hasDepth && off.hasStencil) ri.pStencilAttachment = &sa;
            vkCmdBeginRendering(cmd, &ri);
        } else {
            VkRenderPass pass = offscreenPass(off.colorSet(), off.depthFormat, cc, ops);
            VkRenderPass compatible = offscreenPass(off.colorSet(), off.depthFormat, false, DepthOps());
            if (pass == VK_NULL_HANDLE || compatible == VK_NULL_HANDLE) {
                error = "vkCreateRenderPass failed for a render target";
                return false;
            }
            VkImageView atts[kMaxColor + 1];
            for (uint32_t i = 0; i < off.count; ++i) atts[i] = off.view[i];
            atts[off.count] = off.depthView;
            VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            fi.renderPass = compatible;
            fi.attachmentCount = off.count + (hasDepth ? 1 : 0);
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
            VkClearValue cvs[kMaxColor + 1];
            for (uint32_t i = 0; i < off.count; ++i) cvs[i] = cv[0];
            cvs[off.count] = cv[1];
            rp.clearValueCount = off.count + (hasDepth ? 1 : 0);
            rp.pClearValues = cvs;
            vkCmdBeginRenderPass(cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);
        }
        offOpen = true;
        ++passCount[1];
        return true;
    }

    // Records the frame's queued dispatches (after the staged uploads, in `cmd`). Returns whether anything was recorded.
    bool recordCompute(VkCommandBuffer cmd) {
        if (computeQueue.empty()) return false;
        beginLabel(h, cmd, "gdx: compute", 0.9f, 0.6f, 0.2f);
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
        // The frame's draws (the next command buffer in the submit) read the results: in vertex and fragment shaders, as indirect draw arguments
        // and as vertex and index data (plan, Phase 5: COMPUTE_SHADER -> DRAW_INDIRECT | VERTEX_ATTRIBUTE_INPUT).
        barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                kShaderStages | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                kRw | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT);
        computeQueue.clear();
        endLabel(h, cmd);
        return true;
    }

    void destroyAll() {
        if (h.device == VK_NULL_HANDLE) return;
        if (profOn && profFrames > 0) {
            const double n = static_cast<double>(profFrames);
            std::fprintf(stderr,
                         "[gdx-vulkan][profile] per frame over %llu frames (ms): begin: wait for the frame slot %.3f, deletion/staging/ring %.3f, acquire %.3f, "
                         "command buffer setup %.3f | end: close passes %.3f, uploads+compute record %.3f, submit %.3f, present %.3f\n",
                         static_cast<unsigned long long>(profFrames), prof[0] / n, prof[1] / n, prof[2] / n, prof[3] / n, prof[4] / n, prof[5] / n, prof[6] / n,
                         prof[7] / n);
        }
        vkDeviceWaitIdle(h.device);
        collectRetired(true);
        for (VkFence f : strayFences) vkDestroyFence(h.device, f, nullptr);
        strayFences.clear();
        for (uint32_t i = 0; i < kFramesInFlight; ++i) {
            if (slotFence[i] == VK_NULL_HANDLE) continue;
            if (slotFencePending[i]) vkWaitForFences(h.device, 1, &slotFence[i], VK_TRUE, 1000000000ull);
            vkDestroyFence(h.device, slotFence[i], nullptr);
            slotFence[i] = VK_NULL_HANDLE;
            slotFencePending[i] = false;
        }
        destroySwapchainViews();
        if (swapDepthId >= 0) h.resources->destroyDepthImage(swapDepthId);
        swapDepthId = -1;
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
        if (swapchain != VK_NULL_HANDLE) {
            if (swappyOn) swappy::detach(h.device, swapchain);
            vkDestroySwapchainKHR(h.device, swapchain, nullptr);
        }
        swapchain = VK_NULL_HANDLE;
        swappyOn = false;
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
            const std::string idx = std::to_string(&f - frames);
            nameObject(h, VK_OBJECT_TYPE_COMMAND_BUFFER, reinterpret_cast<uint64_t>(f.cmd), ("gdx frame " + idx).c_str());
            nameObject(h, VK_OBJECT_TYPE_COMMAND_BUFFER, reinterpret_cast<uint64_t>(f.uploadCmd), ("gdx uploads and compute " + idx).c_str());
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
        if (rot != 0) { // hand back the logical orientation
            const uint32_t pw = extent.width, lw = logicalW(), lh = logicalH();
            std::vector<uint8_t> out(n);
            for (uint32_t y = 0; y < lh; ++y) {
                for (uint32_t x = 0; x < lw; ++x) {
                    uint32_t px, py;
                    if (rot == 1) { px = lh - 1 - y; py = x; }
                    else if (rot == 2) { px = lw - 1 - x; py = lh - 1 - y; }
                    else { px = y; py = lw - 1 - x; }
                    std::memcpy(&out[(static_cast<size_t>(y) * lw + x) * 4], &captured[(static_cast<size_t>(py) * pw + px) * 4], 4);
                }
            }
            captured.swap(out);
        }
    }

    // (Re)builds the swapchain for the current surface size. Returns Skipped when the surface has zero area.
    FrameStatus recreateSwapchain() {
        const bool parked = fencesOn() && swapchain != VK_NULL_HANDLE;
        if (!parked) vkDeviceWaitIdle(h.device);
        else collectRetired(false);

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
            // GDXVK_PRESENT=immediate|mailbox picks the mode for diagnosis (immediate may tear; mailbox never does but adds a frame of latency).
            const char* want = std::getenv("GDXVK_PRESENT");
            const bool preferImmediate = want != nullptr && std::string(want) == "immediate";
            if (preferImmediate && has(VK_PRESENT_MODE_IMMEDIATE_KHR)) mode = VK_PRESENT_MODE_IMMEDIATE_KHR;
            else if (has(VK_PRESENT_MODE_MAILBOX_KHR)) mode = VK_PRESENT_MODE_MAILBOX_KHR;
            else if (has(VK_PRESENT_MODE_IMMEDIATE_KHR)) mode = VK_PRESENT_MODE_IMMEDIATE_KHR;
        }

        uint32_t count = caps.minImageCount + 1;
        if (const char* images = std::getenv("GDXVK_IMAGES")) count = std::max<uint32_t>(caps.minImageCount, static_cast<uint32_t>(std::atoi(images))); // diagnosis
        if (caps.maxImageCount > 0) count = std::min(count, caps.maxImageCount);

        VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        if (!(caps.supportedCompositeAlpha & alpha)) {
            for (uint32_t bit = 1; bit <= VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR; bit <<= 1) {
                if (caps.supportedCompositeAlpha & bit) { alpha = static_cast<VkCompositeAlphaFlagBitsKHR>(bit); break; }
            }
        }

        if (forcedRot >= 0 && (forcedRot & 1) && ext.width != ext.height && caps.minImageExtent.width <= ext.height && ext.height <= caps.maxImageExtent.width &&
            caps.minImageExtent.height <= ext.width && ext.width <= caps.maxImageExtent.height) {
            std::swap(ext.width, ext.height); // a surface that allows the display native size (not a fixed-size desktop window)
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
        // Identity whenever the surface allows it: the compositor rotates for us. (Taking currentTransform without rotating the
        // output in clip space, as this did before, shows a rotated image on a rotated display.)
        sci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                                                                                                : caps.currentTransform;
        uint32_t newRot = 0;
        if (preRotate && (caps.supportedTransforms & caps.currentTransform)) {
            switch (caps.currentTransform) {
                case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR: newRot = 1; break;
                case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR: newRot = 2; break;
                case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR: newRot = 3; break;
                default: break;
            }
            if (newRot != 0) {
                sci.preTransform = caps.currentTransform;
                // The window size is reported in the logical orientation; the image is in the display native one.
                if ((newRot & 1u) && ext.width == requested.width && ext.height == requested.height && ext.width != ext.height) std::swap(ext.width, ext.height);
                sci.imageExtent = ext;
            }
        }
        sci.compositeAlpha = alpha;
        sci.presentMode = mode;
        sci.clipped = VK_TRUE;
        sci.oldSwapchain = swapchain;

        VkSwapchainKHR fresh = VK_NULL_HANDLE;
        r = vkCreateSwapchainKHR(h.device, &sci, nullptr, &fresh);
        if (parked) {
            parkSwapchain(); // destroyed once its last presents have finished
        } else {
            destroySwapchainViews(); // old views/framebuffers go before the old swapchain they point into
            if (swapchain != VK_NULL_HANDLE) {
                if (swappyOn) swappy::detach(h.device, swapchain);
                vkDestroySwapchainKHR(h.device, swapchain, nullptr);
            }
            swapchain = VK_NULL_HANDLE;
            swappyOn = false;
        }
        if (r != VK_SUCCESS) return mapResult(r) == FrameStatus::Error ? fail("vkCreateSwapchainKHR failed (VkResult " + std::to_string(r) + ")") : mapResult(r);
        swapchain = fresh;
#ifdef __ANDROID__
        // Frame pacing: only with vsync (Swappy paces against the display) and when the application handed us its activity.
        if (vsync && swappy::available() && swappy::hasActivity() && !std::getenv("GDXVK_NO_SWAPPY")) {
            uint64_t period = 0;
            swappyOn = swappy::attach(h.physical, h.device, h.graphicsQueue, h.graphicsQueueFamily, swapchain,
                                      reinterpret_cast<ANativeWindow*>(native.window), &period);
            swappyPeriodNs = swappyOn ? period : 0;
        }
#endif
        extent = ext;
        rot = (sci.preTransform == caps.currentTransform) ? newRot : 0u;
        if (forcedRot >= 0) rot = static_cast<uint32_t>(forcedRot);

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
        // The depth image follows the swapchain's size.
        if (swapDepthId >= 0) {
            h.resources->destroyDepthImage(swapDepthId);
            swapDepthId = -1;
            swapDepthFmt = VK_FORMAT_UNDEFINED;
        }
        VkImageView swapDepthView = VK_NULL_HANDLE;
        if (swapDepthWanted > 0) {
            swapDepthId = h.resources->createDepthImage(ext.width, ext.height, swapDepthWanted > 1, 24, false);
            if (swapDepthId < 0) return fail("could not create the swapchain depth image");
            VkImage di;
            bool st, init, tr;
            h.resources->depthImageInfo(swapDepthId, &di, &swapDepthView, &swapDepthFmt, &st, &init, &tr);
        }
        if (!h.tierA) {
            framebuffers.resize(n);
            // With depth the pass objects are the offscreen kind (colour and depth both stay in their attachment layouts); the pipelines
            // for the swapchain were built against the same family, so the framebuffer must be compatible with it.
            const VkRenderPass fbPass = swapDepthFmt != VK_FORMAT_UNDEFINED ? offscreenPass(swapColors(), swapDepthFmt, false, DepthOps()) : renderPass;
            for (uint32_t i = 0; i < n; ++i) {
                VkImageView fbViews[2] = {views[i], swapDepthView};
                VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
                fi.renderPass = fbPass;
                fi.attachmentCount = swapDepthFmt != VK_FORMAT_UNDEFINED ? 2 : 1;
                fi.pAttachments = fbViews;
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
                                             bool vsync, bool preRotate, std::string& error, std::string& errorKind) {
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
    s.preRotate = preRotate;
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

void Presenter::setDepth(int mode) {
    if (mode < 0 || mode > 2) mode = 0;
    if (impl_->swapDepthWanted == mode) return;
    impl_->swapDepthWanted = mode;
    impl_->dirty = true; // the depth image is (re)built with the swapchain, before the next frame
}

int Presenter::depthMode() const { return impl_->swapDepthWanted; }

void Presenter::debugForceRotation(int quarterTurns) {
    impl_->forcedRot = quarterTurns;
    impl_->dirty = true;
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
    if (!s.retiredSwaps.empty()) s.collectRetired(false);

    s.profStart();
    Frame& f = s.frames[s.frameIndex];
    VkResult r = h.timeline->waitSerial(f.serial);
    s.profLap(0);
    if (r != VK_SUCCESS) return mapResult(r);
    h.deletion->collect(); // the GPU has finished with everything retired before the frames that are now complete
    h.staging->collect(h.timeline->completed());
    s.rings[s.frameIndex]->reset(); // this slot's previous frame has completed
    s.computeQueue.clear();
    s.profLap(1);

    r = vkAcquireNextImageKHR(h.device, s.swapchain, UINT64_MAX, f.acquired, VK_NULL_HANDLE, &s.imageIndex);
    s.profLap(2);
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
    if (h.tierA || s.swapDepthFmt != VK_FORMAT_UNDEFINED) { // Tier B without depth lets its first render pass leave UNDEFINED itself
        imageBarrier(f.cmd, s.images[s.imageIndex], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                     VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                     VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    }
    s.swapStarted = false; // the pass begins at the first swapchain draw or clear, with this colour
    for (int c = 0; c < 4; ++c) s.frameClear[c] = rgba[c];
    s.passCount[0] = s.passCount[1] = 0;
    // Defaults for the command stream: the whole target, GL-style (Y up) through the negative-height viewport.
    s.applyViewport(f.cmd);
    s.applyScissor(f.cmd);
    s.dsState = Impl::DsState(); // GL's defaults; the application's state is replayed by the stream
    s.cullOverride = -1;
    s.pushDirty = false; // a new command buffer: the stream pushes the whole block again before its first draw
    std::memset(s.pushShadow, 0, sizeof(s.pushShadow));
    s.applyDepthStencil(f.cmd);
    s.frameOpen = true;
    s.profLap(3);
    return FrameStatus::Ok;
}

FrameStatus Presenter::endFrame(bool capture) {
    Impl& s = *impl_;
    const ContextHandles& h = s.h;
    if (!s.frameOpen) return s.fail("endFrame called without beginFrame");
    s.profStart();
    if (capture) {
        if (!s.canCapture) return s.fail("Swapchain images do not support TRANSFER_SRC; cannot capture");
        if (!s.ensureCaptureBuffer()) return s.fail("Allocating the capture buffer failed");
    }
    s.frameOpen = false;
    Frame& f = s.frames[s.frameIndex];
    VkImage image = s.images[s.imageIndex];

    // Both tiers leave the swapchain image in COLOR_ATTACHMENT_OPTIMAL (the passes end there, so a pass can be resumed). Whichever
    // pass is still open ends here; if the stream left an offscreen target bound, that image goes back to being sampleable.
    if (!s.swapStarted) { // nothing was drawn to the swapchain: it still shows the frame's clear colour
        s.endPass(f.cmd);
        const int bound = s.curOff;
        s.curOff = -1;
        if (!s.beginPass(f.cmd, nullptr)) return s.fail(s.error);
        s.endPass(f.cmd);
        s.curOff = bound;
    }
    s.endPass(f.cmd);
    std::memcpy(s.lastPassCount, s.passCount, sizeof(s.lastPassCount));
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
    s.profLap(4);

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
    beginLabel(h, f.uploadCmd, "gdx: staged uploads", 0.5f, 0.8f, 0.4f);
    s.stagingRecorded = h.staging->recordPending(f.uploadCmd);
    endLabel(h, f.uploadCmd);
    const bool mirrored = s.rings[s.frameIndex]->recordVertexCopies(f.uploadCmd);
    const bool computed = s.recordCompute(f.uploadCmd);
    vkEndCommandBuffer(f.uploadCmd);
    s.profLap(5);
    const bool preCmd = s.stagingRecorded || computed || mirrored; // the upload command buffer has something in it
    VkCommandBuffer cmds[2] = {f.uploadCmd, f.cmd};
    si.commandBufferCount = preCmd ? 2 : 1;
    si.pCommandBuffers = preCmd ? cmds : &f.cmd;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &s.renderDone[s.imageIndex];
    VkResult r = h.timeline->submit(si, &f.serial);
    s.profLap(6);
    if (r == VK_SUCCESS && s.stagingRecorded) h.staging->markSubmitted(f.serial);
    s.stagingRecorded = false;
    if (r != VK_SUCCESS) return mapResult(r) == FrameStatus::Error ? s.fail("vkQueueSubmit failed (VkResult " + std::to_string(r) + ")") : mapResult(r);

    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &s.renderDone[s.imageIndex];
    pi.swapchainCount = 1;
    pi.pSwapchains = &s.swapchain;
    pi.pImageIndices = &s.imageIndex;
    const uint32_t slot = s.frameIndex;
    VkSwapchainPresentFenceInfoKHR presentFence{VK_STRUCTURE_TYPE_SWAPCHAIN_PRESENT_FENCE_INFO_KHR};
    if (s.fencesOn()) {
        if (s.slotFence[slot] == VK_NULL_HANDLE) {
            VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            if (vkCreateFence(h.device, &fi, nullptr, &s.slotFence[slot]) != VK_SUCCESS) s.slotFence[slot] = VK_NULL_HANDLE;
        } else if (s.slotFencePending[slot]) { // the present from kFramesInFlight frames ago: long finished, the wait is a formality
            vkWaitForFences(h.device, 1, &s.slotFence[slot], VK_TRUE, UINT64_MAX);
            vkResetFences(h.device, 1, &s.slotFence[slot]);
            s.slotFencePending[slot] = false;
        }
        if (s.slotFence[slot] != VK_NULL_HANDLE) {
            presentFence.swapchainCount = 1;
            presentFence.pFences = &s.slotFence[slot];
            pi.pNext = &presentFence;
        }
    }
    {
        std::lock_guard<std::mutex> queueLock(h.queueMutex);
        r = s.swappyOn ? swappy::present(h.graphicsQueue, &pi) : vkQueuePresentKHR(h.graphicsQueue, &pi);
    }
    s.profLap(7);
    if (pi.pNext && (r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR)) s.slotFencePending[slot] = true;
    else if (pi.pNext) { // the present failed (out of date): the fence may never signal, so give it a moment and otherwise keep it for the end
        if (vkWaitForFences(h.device, 1, &s.slotFence[slot], VK_TRUE, 20000000ull) == VK_SUCCESS) vkDestroyFence(h.device, s.slotFence[slot], nullptr);
        else s.strayFences.push_back(s.slotFence[slot]);
        s.slotFence[slot] = VK_NULL_HANDLE;
    }
    ++s.profFrames;
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
    // The swapchain variant is built now, so a bad shader or state fails here and not in the middle of a frame. A pipeline that writes several
    // colour outputs can never draw to the swapchain: it is built when it is first bound to a target that has that many attachments.
    if (desc.colorOutputs == 1 && s.pipelineFor(id, s.swapColors(), s.swapDepthFmt, false) == VK_NULL_HANDLE) {
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
    nameObject(h, VK_OBJECT_TYPE_PIPELINE, (uint64_t)pipeline, (std::string(name ? name : "compute") + " (compute)").c_str());
    s.computePipelines.push_back(pipeline);
    return static_cast<int>(s.computePipelines.size() - 1);
}

int64_t Presenter::benchmarkRecord(int pipeline, int indexBuffer, uint32_t draws, const float push[32]) {
    Impl& s = *impl_;
    if (!s.frameOpen) {
        s.fail("benchmarkRecord called outside beginFrame/endFrame");
        return -1;
    }
    if (pipeline < 0 || static_cast<size_t>(pipeline) >= s.pipelines.size()) {
        s.fail("benchmarkRecord: unknown pipeline id");
        return -1;
    }
    VkBuffer buf;
    uint64_t base, size;
    if (!s.h.resources->bufferInfo(indexBuffer, buf, base, size) || size < 6) {
        s.fail("benchmarkRecord: unknown or too small index buffer");
        return -1;
    }
    VkCommandBuffer cmd = s.frames[s.frameIndex].cmd;
    // Setup, outside the timed loop: the stream's own replay does the same once per frame.
    if (!s.passOpen() && !s.beginPass(cmd, nullptr)) return -1;
    s.cullOverride = -1;
    if (!s.bindPipelineVariant(cmd, pipeline)) return -1;
    vkCmdBindIndexBuffer(cmd, buf, base, VK_INDEX_TYPE_UINT16);
    float block[32];
    std::memcpy(block, push, sizeof(block));

    const auto t0 = std::chrono::steady_clock::now();
    for (uint32_t i = 0; i < draws; ++i) {
        s.cullOverride = (i & 1u) ? 1 : 2; // the state change: front, back, front, ...
        if (s.h.tierA) vkCmdSetCullMode(cmd, static_cast<VkCullModeFlags>(s.cullOverride));
        else if (!s.bindPipelineVariant(cmd, pipeline)) return -1; // Tier B bakes the cull mode, so a change is a pipeline switch
        block[16] = static_cast<float>(i) * 0.001f; // one word changes per draw, as in a real draw loop
        vkCmdPushConstants(cmd, s.h.layouts->pipelineLayout(), DescriptorLayouts::kPushStages, 0, DescriptorLayouts::kPushConstantBytes, block);
        vkCmdDrawIndexed(cmd, 3, 1, 0, 0, 0);
    }
    const auto t1 = std::chrono::steady_clock::now();
    s.cullOverride = -1;
    if (s.h.tierA) vkCmdSetCullMode(cmd, static_cast<VkCullModeFlags>(s.effectiveCull(pipeline)));
    return std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();
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
    if (!s.passOpen() && !s.beginPass(cmd, nullptr)) return s.fail(s.error);
    VkPipeline vk = s.pipelineFor(pipeline, s.swapColors(), s.swapDepthFmt, false);
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
                if (!need(1)) return s.fail("command stream truncated (BindTarget)");
                const int count = w[i];
                if (count < 0 || count > static_cast<int>(Impl::kMaxColor)) return s.fail("command stream: BindTarget with a bad colour attachment count");
                if (!need(static_cast<size_t>(count) + 2)) return s.fail("command stream truncated (BindTarget)");
                const int32_t* ids = w + i + 1;
                const int depth = w[i + 1 + count];
                i += static_cast<size_t>(count) + 2;
                const int tex = count > 0 ? ids[0] : -1; // the swapchain is count 0
                bool same = tex == s.curOff;
                if (same && count > 0) {
                    same = depth == s.off.depthId && static_cast<uint32_t>(count) == s.off.count;
                    for (int k = 0; same && k < count; ++k) same = ids[k] == s.offIds[k];
                }
                if (same) break;
                s.endPass(cmd);
                s.curOff = tex;
                if (tex >= 0) {
                    Impl::OffTarget t;
                    t.count = static_cast<uint32_t>(count);
                    for (int k = 0; k < count; ++k) {
                        uint32_t tw, th;
                        if (!s.h.resources->renderTargetInfo(ids[k], &t.image[k], &t.view[k], &t.format[k], &tw, &th)) {
                            s.curOff = -1;
                            return s.fail("command stream: BindTarget names a texture that is not a render target");
                        }
                        if (k == 0) {
                            t.w = tw;
                            t.h = th;
                        } else if (tw != t.w || th != t.h) {
                            s.curOff = -1;
                            return s.fail("command stream: the colour attachments of a target must all be the same size");
                        }
                        s.offIds[k] = ids[k];
                    }
                    if (depth >= 0) {
                        bool initialized;
                        if (!s.h.resources->depthImageInfo(depth, &t.depthImage, &t.depthView, &t.depthFormat, &t.hasStencil, &initialized, &t.depthTransient)) {
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
                // Pipelines belong to a colour/depth format (and on Tier B to a render pass): bind the variant for this target. One that writes a
                // different number of outputs than this target has attachments does not fit it: the stream binds the right pipeline before drawing.
                if (s.curPipeline >= 0) {
                    if (s.pipelines[s.curPipeline].desc.colorOutputs != s.targetColors().count) s.curPipeline = -1;
                    else if (!s.bindPipelineVariant(cmd, s.curPipeline)) return s.fail(s.error);
                }
                break;
            }
            case cmd::CullMode: {
                if (!need(1)) return s.fail("command stream truncated (CullMode)");
                s.cullOverride = w[i++];
                if (s.curPipeline >= 0) {
                    if (s.h.tierA) vkCmdSetCullMode(cmd, static_cast<VkCullModeFlags>(s.effectiveCull(s.curPipeline)));
                    else if (!s.bindPipelineVariant(cmd, s.curPipeline)) return s.fail(s.error); // Tier B bakes the cull mode into the variant
                }
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
                std::memcpy(s.pushShadow, w + i, sizeof(s.pushShadow)); // pushed in full before the next draw
                s.pushDirty = true;
                i += 32;
                break;
            case cmd::PushRange: {
                if (!need(2)) return s.fail("command stream truncated (PushRange)");
                const int32_t first = w[i], count = w[i + 1];
                i += 2;
                if (first < 0 || count < 0 || first + count > 32 || !need(static_cast<size_t>(count))) return s.fail("command stream: bad PushRange");
                for (int32_t k = 0; k < count; ++k) s.pushShadow[first + k] = w[i + k]; // a few words: cheaper than a library call
                s.pushDirty = true;
                i += static_cast<size_t>(count);
                break;
            }
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
                VkClearAttachment atts[Impl::kMaxColor + 1]{};
                uint32_t count = 0;
                if (pc.color) { // GL clears every draw buffer
                    const uint32_t n = s.curOff >= 0 ? s.off.count : 1;
                    for (uint32_t a = 0; a < n; ++a, ++count) {
                        atts[count].aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
                        atts[count].colorAttachment = a;
                        for (int c = 0; c < 4; ++c) atts[count].clearValue.color.float32[c] = pc.rgba[c];
                    }
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
            case cmd::Set0Data: {
                if (!need(2)) return s.fail("command stream truncated (Set0Data)");
                const uint32_t ubytes = static_cast<uint32_t>(w[i]), sbytes = static_cast<uint32_t>(w[i + 1]);
                const uint32_t uwords = (ubytes + 3) / 4, swords = (sbytes + 3) / 4;
                i += 2;
                if (!need(uwords + swords)) return s.fail("command stream truncated (Set0Data payload)");
                FrameRing::Binding b;
                if (!ring.allocate(w + i, ubytes, w + i + uwords, sbytes, b)) return s.fail("command stream: Set0Data allocation failed (uniform data over 16 KB, or out of memory)");
                i += uwords + swords;
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, s.h.layouts->pipelineLayout(), 0, 1, &b.set, 2, b.offsets);
                break;
            }
            case cmd::BindVertexBuffer: {
                if (!need(3)) return s.fail("command stream truncated (BindVertexBuffer)");
                VkBuffer buf;
                uint64_t base, size;
                if (!s.h.resources->bufferInfo(w[i], buf, base, size)) return s.fail("command stream: unknown vertex buffer");
                const VkDeviceSize off = base + offset64(w[i + 1], w[i + 2]);
                i += 3;
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
                s.flushPush(cmd);
                vkCmdDrawIndexed(cmd, static_cast<uint32_t>(w[i]), 1, static_cast<uint32_t>(w[i + 1]), w[i + 2], 0);
                i += 3;
                break;
            case cmd::DrawIndexedIndirect: {
                if (!need(5)) return s.fail("command stream truncated (DrawIndexedIndirect)");
                if (!s.passOpen() && !s.beginPass(cmd, nullptr)) return s.fail(s.error);
                s.flushPush(cmd);
                VkBuffer buf;
                uint64_t base, size;
                if (!s.h.resources->bufferInfo(w[i], buf, base, size)) return s.fail("command stream: unknown indirect buffer");
                const VkDeviceSize off = base + offset64(w[i + 1], w[i + 2]);
                const uint32_t count = static_cast<uint32_t>(w[i + 3]), stride = static_cast<uint32_t>(w[i + 4]);
                i += 5;
                // One command per draw: several in one call need the multiDrawIndirect feature, which is not required.
                for (uint32_t d = 0; d < count; ++d) vkCmdDrawIndexedIndirect(cmd, buf, off + static_cast<VkDeviceSize>(d) * stride, 1, stride);
                break;
            }
            case cmd::DrawIndexedInstanced:
                if (!need(4)) return s.fail("command stream truncated (DrawIndexedInstanced)");
                if (!s.passOpen() && !s.beginPass(cmd, nullptr)) return s.fail(s.error);
                s.flushPush(cmd);
                vkCmdDrawIndexed(cmd, static_cast<uint32_t>(w[i]), static_cast<uint32_t>(w[i + 3]), static_cast<uint32_t>(w[i + 1]), w[i + 2], 0);
                i += 4;
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
                s.flushPush(cmd);
                vkCmdDraw(cmd, static_cast<uint32_t>(w[i]), 1, static_cast<uint32_t>(w[i + 1]), 0);
                i += 2;
                break;
            default: return s.fail("command stream: unknown opcode " + std::to_string(op));
        }
    }
    return FrameStatus::Ok;
}

void Presenter::lastPassCounts(uint32_t out[2]) const {
    out[0] = impl_->lastPassCount[0];
    out[1] = impl_->lastPassCount[1];
}
const std::vector<uint8_t>& Presenter::capturedPixels() const { return impl_->captured; }

uint32_t Presenter::rotation() const { return impl_->rot; }
uint32_t Presenter::framePacingPeriodMicros() const { return impl_->swappyOn ? static_cast<uint32_t>(impl_->swappyPeriodNs / 1000u) : 0u; }
bool Presenter::presentFencesActive() const { return impl_->fencesOn(); }
uint32_t Presenter::width() const { return impl_->logicalW(); }
uint32_t Presenter::height() const { return impl_->logicalH(); }
uint32_t Presenter::swapchainFormat() const { return static_cast<uint32_t>(impl_->format); }
uint32_t Presenter::imageCount() const { return static_cast<uint32_t>(impl_->images.size()); }
const std::string& Presenter::lastError() const { return impl_->error; }

} // namespace gdxvk
