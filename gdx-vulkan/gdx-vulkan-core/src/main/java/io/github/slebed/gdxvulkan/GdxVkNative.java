package io.github.slebed.gdxvulkan;

/** Raw JNI entry points. Not public API; use {@link GdxVkContext}. */
final class GdxVkNative {
    private GdxVkNative() {}

    /** @throws VulkanLoaderException / VulkanDeviceException / VulkanInitException thrown from native code */
    static native long nativeCreate(boolean validation, boolean forceTierB, int deviceIndex, int requestedMinor, String appName,
        String pipelineCachePath);

    static native void nativeDestroy(long handle);

    static native String[] nativeInfo(long handle);

    static native int nativeValidationCount(long handle);

    /** @throws VulkanDeviceException / VulkanInitException thrown from native code */
    static native long nativeCreatePresenter(long ctx, long display, long window, int width, int height, boolean vsync);

    /** Android only (the symbol is absent from desktop builds). {@code surface} is an android.view.Surface. */
    static native int nativeTrimMemory(long ctx);
    static native long nativeFieldId(Class<?> type, String name, String signature);
    static native int nativeGetIntField(Object o, long id);
    static native boolean nativeGetBooleanField(Object o, long id);
    static native void nativeSetBooleanField(Object o, long id, boolean v);
    static native Object nativeGetObjectField(Object o, long id);
    static native void nativeSetDepth(long presenter, int mode);
    static native void nativeDebugForceRotation(long presenter, int quarterTurns);
    static native void nativeSetFramePacingActivity(Object activity);
    static native boolean nativeFramePacingAvailable();
    static native long nativeCreatePresenterAndroid(long ctx, Object surface, int width, int height, boolean vsync, boolean preRotate);

    static native int nativeCreateBuffer(long ctx, long size);
    static native boolean nativeReadBuffer(long ctx, int id, long offset, byte[] out, int start, int length);
    static native boolean nativeWriteBufferDirect(long ctx, int id, long offset, java.nio.ByteBuffer data, int start, int length);
    static native boolean nativeWriteBuffer(long ctx, int id, long offset, byte[] data, int start, int length);
    static native void nativeDestroyBuffer(long ctx, int id);
    static native int nativeCreateTexture(long ctx, int width, int height, int format, int uploadedLevels, boolean generateMips, byte[] data,
        int minFilter, int magFilter, int mipFilter, int wrapU, int wrapV, int anisotropy);
    static native int nativeCreateCompressedTexture(long ctx, int width, int height, int vkFormat, int blockW, int blockH, int blockBytes, int levels,
        byte[] data, int minFilter, int magFilter, int mipFilter, int wrapU, int wrapV, int anisotropy);
    static native boolean nativeFormatSupported(long ctx, int vkFormat);
    static native boolean nativeUpdateTexture(long ctx, int id, int level, int x, int y, int w, int h, byte[] data);
    static native boolean nativeRegenerateMips(long ctx, int id);
    static native int[] nativeTextureInfo(long ctx, int id);
    static native boolean nativeSetTextureSampler(long ctx, int id, int minFilter, int magFilter, int mipFilter, int wrapU, int wrapV,
        int anisotropy);
    static native void nativeDestroyTexture(long ctx, int id);
    /** A depth (or depth+stencil) image for a framebuffer; -1 on failure. */
    static native int nativeCreateDepthImage(long ctx, int width, int height, boolean stencil, int depthBits, boolean transientDepth);
    static native void nativeDestroyDepthImage(long ctx, int id);
    static native int nativeTextureSlot(long ctx, int id);
    static native boolean nativeFlushUploads(long ctx);
    static native int nativeCollectGarbage(long ctx);
    static native long[] nativeResourceStats(long ctx);
    static native long nativeRenameCount(long ctx);

    static native boolean nativeBindFrameData(long presenter, byte[] uniform, int uniformBytes, byte[] storage, int storageBytes);
    static native int nativeBindTexture(long presenter, int texture);

    static native void nativeDestroyPresenter(long handle);

    static native void nativeResize(long handle, int width, int height);

    /** Returns a native FrameStatus code. */
    static native int nativeBeginFrame(long handle, float r, float g, float b, float a);

    static native int nativeEndFrame(long handle, boolean capture);

    /** @return pipeline id, or -1 (see nativeLastError) */
    static native int nativeCreatePipeline(long handle, String name, byte[] vertSpv, byte[] fragSpv);

    /** Render passes begun in the last finished frame: {on the swapchain, on offscreen targets}. */
    static native int[] nativeLastPassCounts(long handle);

    /** Stress test 3: nanoseconds to record `draws` state-change + push-constants + indexed-draw triples as a plain C++ loop; -1 on error. */
    static native long nativeBenchmarkRecord(long handle, int pipeline, int indexBuffer, int draws, float[] push);

    /** @return compute pipeline id, or -1 (see nativeLastError) */
    static native int nativeCreateComputePipeline(long handle, String name, byte[] spv);

    /** Queues a dispatch for the open frame. @param push 32 floats. Returns a native FrameStatus code. */
    static native int nativeDispatch(long handle, int pipeline, float[] push, int groupsX, int buffer);

    /** @param push exactly 32 floats (the 128-byte push-constant block). Returns a native FrameStatus code. */
    static native int nativeCreatePipelineEx(long handle, String name, byte[] vertSpv, byte[] fragSpv, int[] desc);

    /** (block << 48) | offset, or -1 */
    static native long nativeAllocVertex(long handle, long bytes);

    static native java.nio.ByteBuffer nativeVertexBlock(long handle, int block);

    static native void nativeFlushVertices(long handle, int block, long offset, long bytes);

    /** Replays a command stream held in a direct ByteBuffer. Returns a native FrameStatus code. */
    static native int nativeSubmitCommands(long handle, java.nio.ByteBuffer stream, int bytes);

    static native int nativeDraw(long handle, int pipeline, int vertexCount, float[] push);

    /** RGBA8 pixels of the last captured frame. */
    static native byte[] nativeCapturedPixels(long handle);

    /** [width, height, VkFormat, imageCount] */
    static native int[] nativePresenterInfo(long handle);

    static native String nativeLastError(long handle);
}
