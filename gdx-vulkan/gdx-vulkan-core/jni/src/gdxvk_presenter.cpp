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

struct Frame {
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkSemaphore acquired = VK_NULL_HANDLE;
    VkFence inFlight = VK_NULL_HANDLE;
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

    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE; // push constants only until Phase 1b adds the descriptor sets
    std::vector<VkPipeline> pipelines;

    bool canCapture = false; // swapchain images support TRANSFER_SRC
    VkBuffer captureBuf = VK_NULL_HANDLE;
    VmaAllocation captureAlloc = nullptr;
    VkDeviceSize captureSize = 0;
    std::vector<uint8_t> captured;

    Frame frames[kFramesInFlight];
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
        for (VkPipeline pl : pipelines) vkDestroyPipeline(h.device, pl, nullptr);
        pipelines.clear();
        if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(h.device, pipelineLayout, nullptr);
        pipelineLayout = VK_NULL_HANDLE;
        if (captureBuf != VK_NULL_HANDLE) vmaDestroyBuffer(h.allocator, captureBuf, captureAlloc);
        captureBuf = VK_NULL_HANDLE;
        if (renderPass != VK_NULL_HANDLE) vkDestroyRenderPass(h.device, renderPass, nullptr);
        renderPass = VK_NULL_HANDLE;
        if (swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(h.device, swapchain, nullptr);
        swapchain = VK_NULL_HANDLE;
        for (Frame& f : frames) {
            if (f.inFlight != VK_NULL_HANDLE) vkDestroyFence(h.device, f.inFlight, nullptr);
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
            VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            if (vkCreateSemaphore(h.device, &si, nullptr, &f.acquired) != VK_SUCCESS) return false;
            VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
            if (vkCreateFence(h.device, &fi, nullptr, &f.inFlight) != VK_SUCCESS) return false;
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
    VkPushConstantRange pcr{VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, 128};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &pcr;
    if (vkCreatePipelineLayout(h.device, &pli, nullptr, &s.pipelineLayout) != VK_SUCCESS) {
        errorKind = "init";
        error = "vkCreatePipelineLayout failed";
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
    VkResult r = vkWaitForFences(h.device, 1, &f.inFlight, VK_TRUE, UINT64_MAX);
    if (r != VK_SUCCESS) return mapResult(r);

    r = vkAcquireNextImageKHR(h.device, s.swapchain, UINT64_MAX, f.acquired, VK_NULL_HANDLE, &s.imageIndex);
    if (r == VK_ERROR_OUT_OF_DATE_KHR) {
        s.dirty = true;
        return FrameStatus::Skipped;
    }
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return mapResult(r) == FrameStatus::Error ? s.fail("vkAcquireNextImageKHR failed (VkResult " + std::to_string(r) + ")") : mapResult(r);
    if (r == VK_SUBOPTIMAL_KHR) s.dirty = true; // image is still usable this frame; rebuild before the next one

    vkResetFences(h.device, 1, &f.inFlight);
    vkResetCommandPool(h.device, f.pool, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(f.cmd, &bi);

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
    si.commandBufferCount = 1;
    si.pCommandBuffers = &f.cmd;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &s.renderDone[s.imageIndex];
    VkResult r = vkQueueSubmit(h.graphicsQueue, 1, &si, f.inFlight);
    if (r != VK_SUCCESS) return mapResult(r) == FrameStatus::Error ? s.fail("vkQueueSubmit failed (VkResult " + std::to_string(r) + ")") : mapResult(r);

    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &s.renderDone[s.imageIndex];
    pi.swapchainCount = 1;
    pi.pSwapchains = &s.swapchain;
    pi.pImageIndices = &s.imageIndex;
    r = vkQueuePresentKHR(h.graphicsQueue, &pi);
    s.frameIndex = (s.frameIndex + 1) % kFramesInFlight;
    FrameStatus result = FrameStatus::Ok;
    if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) {
        s.dirty = true; // the frame was submitted; just rebuild before the next one
    } else if (r != VK_SUCCESS) {
        result = mapResult(r) == FrameStatus::Error ? s.fail("vkQueuePresentKHR failed (VkResult " + std::to_string(r) + ")") : mapResult(r);
    }

    if (capture && result == FrameStatus::Ok) {
        r = vkWaitForFences(h.device, 1, &f.inFlight, VK_TRUE, UINT64_MAX);
        if (r != VK_SUCCESS) return mapResult(r);
        vmaInvalidateAllocation(h.allocator, s.captureAlloc, 0, VK_WHOLE_SIZE);
        s.readCapture();
    }
    return result;
}

int Presenter::createPipeline(const uint32_t* vertSpv, size_t vertWords, const uint32_t* fragSpv, size_t fragWords) {
    Impl& s = *impl_;
    const ContextHandles& h = s.h;
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

    VkPipelineVertexInputStateCreateInfo vin{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1;
    vp.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_BACK_BIT;            // baked on Tier B, overridden dynamically on Tier A
    rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE; // swapchain convention (plan decision 6)
    rs.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState cba{};
    cba.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
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
    gi.layout = s.pipelineLayout;
    gi.renderPass = h.tierA ? VK_NULL_HANDLE : s.renderPass;
    gi.subpass = 0;

    VkPipeline pipeline = VK_NULL_HANDLE;
    const VkResult r = vkCreateGraphicsPipelines(h.device, VK_NULL_HANDLE, 1, &gi, nullptr, &pipeline);
    vkDestroyShaderModule(h.device, vs, nullptr);
    vkDestroyShaderModule(h.device, fs, nullptr);
    if (r != VK_SUCCESS) {
        s.error = "vkCreateGraphicsPipelines failed (VkResult " + std::to_string(r) + ")";
        return -1;
    }
    s.pipelines.push_back(pipeline);
    return static_cast<int>(s.pipelines.size() - 1);
}

FrameStatus Presenter::draw(int pipeline, uint32_t vertexCount, const float push[32]) {
    Impl& s = *impl_;
    if (!s.frameOpen) return s.fail("draw called outside beginFrame/endFrame");
    if (pipeline < 0 || static_cast<size_t>(pipeline) >= s.pipelines.size()) return s.fail("draw: unknown pipeline id");
    VkCommandBuffer cmd = s.frames[s.frameIndex].cmd;
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, s.pipelines[pipeline]);
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
    vkCmdPushConstants(cmd, s.pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, 128, push);
    vkCmdDraw(cmd, vertexCount, 1, 0, 0);
    return FrameStatus::Ok;
}

const std::vector<uint8_t>& Presenter::capturedPixels() const { return impl_->captured; }

uint32_t Presenter::width() const { return impl_->extent.width; }
uint32_t Presenter::height() const { return impl_->extent.height; }
uint32_t Presenter::swapchainFormat() const { return static_cast<uint32_t>(impl_->format); }
uint32_t Presenter::imageCount() const { return static_cast<uint32_t>(impl_->images.size()); }
const std::string& Presenter::lastError() const { return impl_->error; }

} // namespace gdxvk
