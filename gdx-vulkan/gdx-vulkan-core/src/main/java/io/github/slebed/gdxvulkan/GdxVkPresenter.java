package io.github.slebed.gdxvulkan;

/**
 * A window surface with its swapchain and the per-frame submit/present loop. Create one with
 * {@link GdxVkContext#createPresenter}. Render thread only.
 *
 * <p>Out-of-date and suboptimal swapchains are handled internally: {@link #beginFrame} rebuilds the swapchain after a
 * {@link #resize} or a window-system invalidation. It returns {@code false} when there is nothing to draw into
 * (window minimized); skip the frame and call again.
 */
public final class GdxVkPresenter implements AutoCloseable {
    // Mirrors gdxvk::FrameStatus in gdxvk_presenter.h.
    private static final int OK = 0, SKIPPED = 1, SURFACE_LOST = 2, DEVICE_LOST = 3, OUT_OF_MEMORY = 4;

    private final GdxVkContext context;
    private long handle;
    private boolean frameOpen;

    GdxVkPresenter(GdxVkContext context, long handle) {
        this.context = context;
        this.handle = handle;
    }

    /** Starts a frame that clears the swapchain image to the given colour. @return false if the frame was skipped. */
    public boolean beginFrame(float r, float g, float b, float a) {
        check();
        if (frameOpen) throw new IllegalStateException("beginFrame called twice without endFrame");
        boolean ok = translate(GdxVkNative.nativeBeginFrame(handle, r, g, b, a));
        frameOpen = ok;
        return ok;
    }

    /** Ends the pass, submits and presents. */
    public void endFrame() {
        check();
        if (!frameOpen) throw new IllegalStateException("endFrame called without beginFrame");
        frameOpen = false;
        translate(GdxVkNative.nativeEndFrame(handle, false));
    }

    /**
     * Like {@link #endFrame()}, then waits for the GPU and returns the frame as RGBA8, row 0 at the top,
     * {@code width() * height() * 4} bytes. For tests and the golden-image harness; it stalls the pipeline.
     */
    public byte[] endFrameAndCapture() {
        check();
        if (!frameOpen) throw new IllegalStateException("endFrameAndCapture called without beginFrame");
        frameOpen = false;
        translate(GdxVkNative.nativeEndFrame(handle, true));
        return GdxVkNative.nativeCapturedPixels(handle);
    }

    /**
     * Phase 1a: builds a pipeline from SPIR-V for the swapchain's format. Triangle list, no vertex inputs (vertices come from
     * {@code gl_VertexIndex}), back-face culling with CCW front faces. Phase 1b replaces this with shader bundles.
     *
     * Both shaders are reflected against the device's pipeline layout first; a mismatch (a Tier A shader on Tier B, an unknown
     * binding, oversized push constants, a swapped stage) throws a {@link GdxVkException} that names {@code name + ".vert"}.
     *
     * @param name label used in error messages, normally the shader's base name
     * @return a pipeline id for {@link #draw}; freed with the presenter
     */
    public int createPipeline(String name, byte[] vertSpv, byte[] fragSpv) {
        check();
        int id = GdxVkNative.nativeCreatePipeline(handle, name, vertSpv, fragSpv);
        if (id < 0) throw new GdxVkException(GdxVkNative.nativeLastError(handle));
        return id;
    }

    /**
     * Records a draw between {@link #beginFrame} and {@link #endFrame}. {@code push} is the whole 128-byte push-constant block
     * as 32 floats (u_transform 0-15, u_color 16-19, u_indices 20-23 as raw int bits, u_custom0/1 24-31).
     * Sets the swapchain viewport (negative height) and scissor itself.
     */
    public void draw(int pipeline, int vertexCount, float[] push) {
        check();
        if (!frameOpen) throw new IllegalStateException("draw called outside beginFrame/endFrame");
        if (push.length != 32) throw new IllegalArgumentException("push must hold 32 floats, was " + push.length);
        translate(GdxVkNative.nativeDraw(handle, pipeline, vertexCount, push));
    }

    /**
     * Set 0: copies the bytes into this frame's dynamic ring and binds them for the draws that follow.
     *
     * @param uniform camera and frame globals, at most 16 KB (std140 layout); may be null with 0 bytes
     * @param storage transforms, materials, lights (std430 layout); may be null with 0 bytes
     */
    public void bindFrameData(byte[] uniform, int uniformBytes, byte[] storage, int storageBytes) {
        check();
        if (!frameOpen) throw new IllegalStateException("bindFrameData called outside beginFrame/endFrame");
        if (!GdxVkNative.nativeBindFrameData(handle, uniform, uniformBytes, storage, storageBytes)) {
            throw new GdxVkException(GdxVkNative.nativeLastError(handle));
        }
    }

    /**
     * Set 1: makes a texture current. On Tier A returns its slot in the bindless table, to put in {@code u_indices.y} of the push
     * constants; on Tier B binds the texture's descriptor set and returns 0.
     */
    public int bindTexture(int texture) {
        check();
        if (!frameOpen) throw new IllegalStateException("bindTexture called outside beginFrame/endFrame");
        int slot = GdxVkNative.nativeBindTexture(handle, texture);
        if (slot < 0) throw new IllegalArgumentException("No such texture: " + texture);
        return slot;
    }

    /** Tells the presenter the window size changed. The swapchain is rebuilt on the next {@link #beginFrame}. */
    public void resize(int width, int height) {
        check();
        GdxVkNative.nativeResize(handle, width, height);
    }

    /** Current swapchain width in pixels (0 while minimized). Updated when the swapchain is rebuilt. */
    public int width() { check(); return GdxVkNative.nativePresenterInfo(handle)[0]; }

    public int height() { check(); return GdxVkNative.nativePresenterInfo(handle)[1]; }

    /** The swapchain's VkFormat value (always a UNORM format). */
    public int vkFormat() { check(); return GdxVkNative.nativePresenterInfo(handle)[2]; }

    public int imageCount() { check(); return GdxVkNative.nativePresenterInfo(handle)[3]; }

    @Override public synchronized void close() {
        if (handle != 0) {
            GdxVkNative.nativeDestroyPresenter(handle);
            handle = 0;
            context.presenterClosed(this);
        }
    }

    private void check() {
        if (handle == 0) throw new IllegalStateException("Presenter is closed");
    }

    /** @return true when a frame was recorded/submitted, false when skipped. */
    private boolean translate(int status) {
        switch (status) {
            case OK: return true;
            case SKIPPED: return false;
            case SURFACE_LOST: throw new VulkanSurfaceLostException("Vulkan surface lost");
            case DEVICE_LOST: throw new VulkanDeviceLostException("Vulkan device lost");
            case OUT_OF_MEMORY: throw new VulkanOutOfMemoryException("Vulkan out of memory");
            default: throw new GdxVkException(GdxVkNative.nativeLastError(handle));
        }
    }
}
