// Window surface + swapchain + the per-frame submit/present loop. One Presenter per window; it borrows the
// device from a Context, which must outlive it.
//
// Rendering path follows the tier (plan, Core decision 7): Tier A uses dynamic rendering, Tier B uses a
// VkRenderPass with one framebuffer per swapchain image. Swapchain images are UNORM, never sRGB (decision 6).
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "gdxvk_context.h"

namespace gdxvk {

// Native window-system handles, as the platform reports them.
//   Win32:  window = HWND (display unused)
//   Xlib:   display = Display*, window = Window (XID)
//   Android: window = ANativeWindow* (the caller holds a reference)
struct SurfaceHandles {
    uintptr_t display = 0;
    uintptr_t window = 0;
    // Called with `window` once the VkSurfaceKHR is destroyed, if set. Android uses it for ANativeWindow_release.
    void (*releaseWindow)(uintptr_t window) = nullptr;
};

enum class FrameStatus : int {
    Ok = 0,
    Skipped = 1, // nothing was recorded (window minimized, or the swapchain was out of date); try again next frame
    SurfaceLost = 2,
    DeviceLost = 3,
    OutOfMemory = 4,
    Error = 5, // see Presenter::lastError()
};

class Presenter {
public:
    // Returns null and fills `error` / `errorKind` ("device" or "init") on failure.
    static std::unique_ptr<Presenter> create(Context& ctx, const SurfaceHandles& handles, uint32_t width, uint32_t height,
                                             bool vsync, std::string& error, std::string& errorKind);
    ~Presenter();
    Presenter(const Presenter&) = delete;
    Presenter& operator=(const Presenter&) = delete;

    // The swapchain is rebuilt at the start of the next beginFrame.
    void resize(uint32_t width, uint32_t height);

    // Waits for a free frame slot, acquires an image and starts a pass that clears it to `rgba`.
    FrameStatus beginFrame(const float rgba[4]);
    // Ends the pass, submits and presents. With `capture`, the frame is also copied back to the CPU (needs a swapchain
    // that supports TRANSFER_SRC) and waits for the GPU; read it with capturedPixels().
    FrameStatus endFrame(bool capture = false);

    // Builds a graphics pipeline for the swapchain's format from SPIR-V (no vertex inputs; vertices come from
    // gl_VertexIndex). Triangle list, back-face culling with CCW front faces, no blending, no depth. Phase 1a only:
    // Phase 1b replaces this with the shared two-set layout, the pipeline cache and VulkanShaderPipelineBundle.
    // Returns the pipeline id, or -1 with lastError() set.
    int createPipeline(const uint32_t* vertSpv, size_t vertWords, const uint32_t* fragSpv, size_t fragWords);

    // Records a non-indexed draw inside an open frame. `push` is the full 128-byte block (32 floats). Sets the
    // swapchain viewport (negative height, plan decision 6) and scissor, so it needs no other state.
    FrameStatus draw(int pipeline, uint32_t vertexCount, const float push[32]);

    // RGBA8, row 0 at the top of the screen, width() * height() * 4 bytes, from the last endFrame(capture = true).
    const std::vector<uint8_t>& capturedPixels() const;

    uint32_t width() const;
    uint32_t height() const;
    uint32_t swapchainFormat() const; // VkFormat
    uint32_t imageCount() const;
    const std::string& lastError() const;

private:
    Presenter(Context& ctx);
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace gdxvk
