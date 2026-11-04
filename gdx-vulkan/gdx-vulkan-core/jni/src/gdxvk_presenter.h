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

// What a pipeline draws with. Vertex input is described by hand (stride + attributes); blend factors are OpenGL enums. Depth is not
// used yet. `topology` and `format` are raw Vulkan enum values (VK_PRIMITIVE_TOPOLOGY_*, VK_FORMAT_*).
struct VertexAttribute {
    uint32_t location = 0, format = 0, offset = 0;
};
struct PipelineDesc {
    uint32_t stride = 0;
    std::vector<VertexAttribute> attributes;
    uint32_t topology = 3; // VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
    uint32_t cullMode = 2; // VK_CULL_MODE_BACK_BIT (0 = none, 1 = front, 2 = back)
    bool blend = false;
    uint32_t srcColor = 1, dstColor = 0, srcAlpha = 1, dstAlpha = 0; // GL_ONE, GL_ZERO
    uint32_t colorOutputs = 1; // colour outputs the fragment shader writes; the render target it is used with must have as many attachments
    // Specialization constants 1..n of both shaders (constant 0 is the pre-rotation, set by the presenter). Material and light flags of the model shaders.
    std::vector<int32_t> spec;
};

class Presenter {
public:
    // Returns null and fills `error` / `errorKind` ("device" or "init") on failure.
    static std::unique_ptr<Presenter> create(Context& ctx, const SurfaceHandles& handles, uint32_t width, uint32_t height,
                                             bool vsync, bool preRotate, std::string& error, std::string& errorKind);
    ~Presenter();
    Presenter(const Presenter&) = delete;
    Presenter& operator=(const Presenter&) = delete;

    // The swapchain is rebuilt at the start of the next beginFrame.
    void resize(uint32_t width, uint32_t height);
    // Diagnostics and tests: render as if the display were rotated by `quarterTurns` (0-3, clockwise); -1 follows the surface. The swapchain is
    // rebuilt on the next frame. Only meaningful on a square surface (a desktop window cannot change its image size).
    void debugForceRotation(int quarterTurns);
    // A depth buffer on the swapchain itself (0 none, 1 depth, 2 depth + stencil), so 3D can depth-test on screen. Takes effect at the next frame.
    void setDepth(int mode);
    int depthMode() const;

    // Waits for a free frame slot, acquires an image and starts a pass that clears it to `rgba`.
    FrameStatus beginFrame(const float rgba[4]);
    // Ends the pass, submits and presents. With `capture`, the frame is also copied back to the CPU (needs a swapchain
    // that supports TRANSFER_SRC) and waits for the GPU; read it with capturedPixels().
    FrameStatus endFrame(bool capture = false);

    // Builds a graphics pipeline for the swapchain's format from SPIR-V (no vertex inputs; vertices come from
    // gl_VertexIndex). Triangle list, back-face culling with CCW front faces, no blending, no depth. Phase 1a only:
    // Phase 1b replaces this with the shared two-set layout, the pipeline cache and VulkanShaderPipelineBundle.
    // Returns the pipeline id, or -1 with lastError() set.
    // Both shaders are reflected first and must fit the device's pipeline layout (gdxvk_reflect.h); `name` appears in the error.
    int createPipeline(const char* name, const uint32_t* vertSpv, size_t vertWords, const uint32_t* fragSpv, size_t fragWords);

    // The general form: vertex layout, topology, cull mode and blend come from `desc`. Used by the batches.
    int createPipelineEx(const char* name, const uint32_t* vertSpv, size_t vertWords, const uint32_t* fragSpv, size_t fragWords,
                         const PipelineDesc& desc);

    // Compute (plan, Phase 2: VulkanSpriteBatchInstanced). A compute pipeline uses the shared layout; the shader is reflected like the
    // others. Returns the compute pipeline id, or -1 with lastError() set.
    int createComputePipeline(const char* name, const uint32_t* spv, size_t words);

    // Queues a dispatch for this frame. Dispatches run in queue order, at the start of the frame's GPU work: after the staged uploads
    // and before any draw, outside the render pass. Set 0's storage binding reads the resource buffer `buffer` (read/write for the
    // shader); the vertex shaders of the frame's draws see the results. `push` is the full 128-byte block.
    FrameStatus dispatch(int computePipeline, const float push[32], uint32_t groupsX, int buffer);

    // Vertex/index memory for the command stream: host-visible, mapped, valid for the frame being recorded. Returns false when
    // out of memory. A block's address changes only when the ring grows; query it with vertexBlock().
    bool allocVertex(uint64_t bytes, uint32_t* block, uint64_t* offset);
    uint8_t* vertexBlock(uint32_t block, uint64_t* size);
    void flushVertices(uint32_t block, uint64_t offset, uint64_t bytes);

    // Replays a command stream (gdxvk_commands.h) into the open frame. Many streams may be replayed per frame.
    FrameStatus submitCommandStream(const uint8_t* data, size_t bytes);

    // Render passes begun in the last finished frame: out[0] on the swapchain, out[1] on offscreen targets. The swapchain count is 1 when the
    // frame's offscreen passes all came before its first swapchain draw (pass scheduling), 2 or more when they interrupted it.
    void lastPassCounts(uint32_t out[2]) const;

    // Stress test 3 (plan, Phase 3): the cost of recording `draws` indexed draws, each with a state change (the cull mode, alternating) and
    // a full 128-byte push-constant block, as one plain C++ loop straight into the frame's command buffer. This is the floor the command
    // stream is compared with. Needs an open frame and a bound index buffer (`indexBuffer` is a resource buffer id of u16 indices, at
    // least 3 of them). Returns the recording time in nanoseconds, or -1 on error (see lastError()).
    int64_t benchmarkRecord(int pipeline, int indexBuffer, uint32_t draws, const float push[32]);

    // Records a non-indexed draw inside an open frame. `push` is the full 128-byte block (32 floats). Sets the
    // swapchain viewport (negative height, plan decision 6) and scissor, so it needs no other state.
    FrameStatus draw(int pipeline, uint32_t vertexCount, const float push[32]);

    // Set 0: copies the bytes into this frame's dynamic ring and binds them (uniform = camera/frame globals, at most 16 KB;
    // storage = transforms, materials, ...). Applies to the draws that follow. Inside an open frame.
    bool bindFrameData(const void* uniform, uint32_t uniformBytes, const void* storage, uint64_t storageBytes);

    // Set 1: makes the texture current. Tier A returns its slot in the bindless table, to be passed in u_indices.y; Tier B binds
    // the texture's descriptor set and returns 0. -1 if the texture does not exist. Inside an open frame.
    int bindTexture(int texture);

    // RGBA8, row 0 at the top of the screen, width() * height() * 4 bytes, from the last endFrame(capture = true).
    const std::vector<uint8_t>& capturedPixels() const;

    uint32_t width() const;
    uint32_t height() const;
    uint32_t swapchainFormat() const; // VkFormat
    uint32_t imageCount() const;
    // Quarter turns clockwise the swapchain image is pre-rotated by (0 when the compositor rotates).
    uint32_t rotation() const;
    const std::string& lastError() const;

private:
    Presenter(Context& ctx);
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace gdxvk
