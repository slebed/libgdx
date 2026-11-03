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
                  VkAccessFlags srcAccess, VkPipelineStageFlags dstStage, VkAccessFlags dstAccess) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = srcAccess;
    b.dstAccessMask = dstAccess;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
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
        VkPipeline pipeline;
        VkCullModeFlags cull;
    };
    std::vector<PipelineRec> pipelines;
    VkRect2D scissor{};      // command stream state, reset every frame
    bool scissorOn = false;

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

    void destroyAll() {
        if (h.device == VK_NULL_HANDLE) return;
        vkDeviceWaitIdle(h.device);
        destroySwapchainViews();
        for (const PipelineRec& pl : pipelines) vkDestroyPipeline(h.device, pl.pipeline, nullptr);
        pipelines.clear();
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

    bool createRenderPass() {
        VkAttachmentDescription att{};
        att.format = format;
        att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        att.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
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
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo ri{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        ri.attachmentCount = 1;
        ri.pAttachments = &att;
        ri.subpassCount = 1;
        ri.pSubpasses = &sub;
        ri.dependencyCount = 1;
        ri.pDependencies = &dep;
        return vkCreateRenderPass(h.device, &ri, nullptr, &renderPass) == VK_SUCCESS;
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

    VkClearValue clear;
    for (int i = 0; i < 4; ++i) clear.color.float32[i] = rgba[i];
    const VkRect2D area{{0, 0}, s.extent};

    if (h.tierA) {
        imageBarrier(f.cmd, s.images[s.imageIndex], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                     VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                     VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        VkRenderingAttachmentInfo ca{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
        ca.imageView = s.views[s.imageIndex];
        ca.imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        ca.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        ca.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        ca.clearValue = clear;
        VkRenderingInfo ri{VK_STRUCTURE_TYPE_RENDERING_INFO};
        ri.renderArea = area;
        ri.layerCount = 1;
        ri.colorAttachmentCount = 1;
        ri.pColorAttachments = &ca;
        vkCmdBeginRendering(f.cmd, &ri);
    } else {
        VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        rp.renderPass = s.renderPass;
        rp.framebuffer = s.framebuffers[s.imageIndex];
        rp.renderArea = area;
        rp.clearValueCount = 1;
        rp.pClearValues = &clear;
        vkCmdBeginRenderPass(f.cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);
    }
    // Defaults for the command stream: the whole target, GL-style (Y up) through the negative-height viewport.
    const VkViewport vpDefault{0.0f, static_cast<float>(s.extent.height), static_cast<float>(s.extent.width), -static_cast<float>(s.extent.height), 0.0f, 1.0f};
    s.scissor = {{0, 0}, s.extent};
    s.scissorOn = false;
    vkCmdSetViewport(f.cmd, 0, 1, &vpDefault);
    vkCmdSetScissor(f.cmd, 0, 1, &s.scissor);
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

    // Layout after the pass: Tier A leaves COLOR_ATTACHMENT_OPTIMAL, Tier B's render pass already went to PRESENT_SRC.
    VkImageLayout layout;
    if (h.tierA) {
        vkCmdEndRendering(f.cmd);
        layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    } else {
        vkCmdEndRenderPass(f.cmd);
        layout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    }
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
    } else if (h.tierA) {
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
    vkEndCommandBuffer(f.uploadCmd);
    VkCommandBuffer cmds[2] = {f.uploadCmd, f.cmd};
    si.commandBufferCount = s.stagingRecorded ? 2 : 1;
    si.pCommandBuffers = s.stagingRecorded ? cmds : &f.cmd;
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
    VkShaderModule vs = VK_NULL_HANDLE, fs = VK_NULL_HANDLE;
    if (!module(vertSpv, vertWords, vs) || !module(fragSpv, fragWords, fs)) {
        if (vs != VK_NULL_HANDLE) vkDestroyShaderModule(h.device, vs, nullptr);
        s.error = "vkCreateShaderModule failed (invalid SPIR-V?)";
        return -1;
    }
    VkPipelineShaderStageCreateInfo stages[2]{{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO},
                                              {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}};
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vs;
    stages[0].pName = "main";
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fs;
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
    rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE; // swapchain convention (plan decision 6)
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

    // Blend is pipeline state on both tiers; cull mode and front face are dynamic on Tier A only (plan decision 7).
    VkDynamicState dyn[4] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_CULL_MODE, VK_DYNAMIC_STATE_FRONT_FACE};
    VkPipelineDynamicStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    ds.dynamicStateCount = h.tierA ? 4 : 2;
    ds.pDynamicStates = dyn;

    VkPipelineRenderingCreateInfo rendering{VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO};
    rendering.colorAttachmentCount = 1;
    rendering.pColorAttachmentFormats = &s.format;

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
    gi.pDynamicState = &ds;
    gi.layout = h.layouts->pipelineLayout();
    gi.renderPass = h.tierA ? VK_NULL_HANDLE : s.renderPass;
    gi.subpass = 0;

    VkPipeline pipeline = VK_NULL_HANDLE;
    const VkResult r = vkCreateGraphicsPipelines(h.device, h.pipelineCache->handle(), 1, &gi, nullptr, &pipeline);
    vkDestroyShaderModule(h.device, vs, nullptr);
    vkDestroyShaderModule(h.device, fs, nullptr);
    if (r != VK_SUCCESS) {
        s.error = "vkCreateGraphicsPipelines failed (VkResult " + std::to_string(r) + ")";
        return -1;
    }
    s.pipelines.push_back({pipeline, static_cast<VkCullModeFlags>(desc.cullMode)});
    return static_cast<int>(s.pipelines.size() - 1);
}

FrameStatus Presenter::draw(int pipeline, uint32_t vertexCount, const float push[32]) {
    Impl& s = *impl_;
    if (!s.frameOpen) return s.fail("draw called outside beginFrame/endFrame");
    if (pipeline < 0 || static_cast<size_t>(pipeline) >= s.pipelines.size()) return s.fail("draw: unknown pipeline id");
    VkCommandBuffer cmd = s.frames[s.frameIndex].cmd;
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, s.pipelines[pipeline].pipeline);
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
    const int32_t targetW = static_cast<int32_t>(s.extent.width), targetH = static_cast<int32_t>(s.extent.height);

    auto applyScissor = [&]() {
        const VkRect2D full{{0, 0}, s.extent};
        const VkRect2D r = s.scissorOn ? s.scissor : full;
        vkCmdSetScissor(cmd, 0, 1, &r);
    };
    // GL rectangle (origin bottom-left) -> Vulkan (origin top-left), clamped into the target.
    auto toVk = [&](int32_t x, int32_t y, int32_t rw, int32_t rh) {
        int32_t x0 = std::max(0, x), x1 = std::min(targetW, x + std::max(0, rw));
        int32_t top = targetH - (y + std::max(0, rh)), bottom = targetH - y;
        int32_t y0 = std::max(0, top), y1 = std::min(targetH, bottom);
        return VkRect2D{{x0, y0}, {static_cast<uint32_t>(std::max(0, x1 - x0)), static_cast<uint32_t>(std::max(0, y1 - y0))}};
    };
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
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, s.pipelines[id].pipeline);
                if (s.h.tierA) {
                    vkCmdSetCullMode(cmd, s.pipelines[id].cull);
                    vkCmdSetFrontFace(cmd, VK_FRONT_FACE_COUNTER_CLOCKWISE);
                }
                break;
            }
            case cmd::PushConstants:
                if (!need(32)) return s.fail("command stream truncated (PushConstants)");
                vkCmdPushConstants(cmd, s.h.layouts->pipelineLayout(), DescriptorLayouts::kPushStages, 0, DescriptorLayouts::kPushConstantBytes, w + i);
                i += 32;
                break;
            case cmd::Viewport: {
                if (!need(4)) return s.fail("command stream truncated (Viewport)");
                // Negative height: y is the bottom edge in Vulkan's top-left coordinates.
                const VkViewport v{static_cast<float>(w[i]), static_cast<float>(targetH - w[i + 1]), static_cast<float>(w[i + 2]),
                                   -static_cast<float>(w[i + 3]), 0.0f, 1.0f};
                vkCmdSetViewport(cmd, 0, 1, &v);
                i += 4;
                break;
            }
            case cmd::Scissor:
                if (!need(4)) return s.fail("command stream truncated (Scissor)");
                s.scissor = toVk(w[i], w[i + 1], w[i + 2], w[i + 3]);
                i += 4;
                applyScissor();
                break;
            case cmd::ScissorEnable:
                if (!need(1)) return s.fail("command stream truncated (ScissorEnable)");
                s.scissorOn = w[i++] != 0;
                applyScissor();
                break;
            case cmd::Clear: {
                if (!need(5)) return s.fail("command stream truncated (Clear)");
                const int32_t mask = w[i];
                VkClearAttachment att{};
                att.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
                att.colorAttachment = 0;
                for (int c = 0; c < 4; ++c) att.clearValue.color.float32[c] = bits(w[i + 1 + c]);
                i += 5;
                if (mask & cmd::kGlColorBufferBit) {
                    VkClearRect rect{};
                    rect.rect = s.scissorOn ? s.scissor : VkRect2D{{0, 0}, s.extent};
                    rect.layerCount = 1;
                    if (rect.rect.extent.width > 0 && rect.rect.extent.height > 0) vkCmdClearAttachments(cmd, 1, &att, 1, &rect);
                }
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
                vkCmdDrawIndexed(cmd, static_cast<uint32_t>(w[i]), 1, static_cast<uint32_t>(w[i + 1]), w[i + 2], 0);
                i += 3;
                break;
            case cmd::Draw:
                if (!need(2)) return s.fail("command stream truncated (Draw)");
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
