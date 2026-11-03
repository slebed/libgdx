package io.github.slebed.gdxvulkan;

/** Raw JNI entry points. Not public API; use {@link GdxVkContext}. */
final class GdxVkNative {
    private GdxVkNative() {}

    /** @throws VulkanLoaderException / VulkanDeviceException / VulkanInitException thrown from native code */
    static native long nativeCreate(boolean validation, boolean forceTierB, int deviceIndex, int requestedMinor, String appName);

    static native void nativeDestroy(long handle);

    static native String[] nativeInfo(long handle);

    static native int nativeValidationCount(long handle);

    /** @throws VulkanDeviceException / VulkanInitException thrown from native code */
    static native long nativeCreatePresenter(long ctx, long display, long window, int width, int height, boolean vsync);

    /** Android only (the symbol is absent from desktop builds). {@code surface} is an android.view.Surface. */
    static native long nativeCreatePresenterAndroid(long ctx, Object surface, int width, int height, boolean vsync);

    static native void nativeDestroyPresenter(long handle);

    static native void nativeResize(long handle, int width, int height);

    /** Returns a native FrameStatus code. */
    static native int nativeBeginFrame(long handle, float r, float g, float b, float a);

    static native int nativeEndFrame(long handle, boolean capture);

    /** @return pipeline id, or -1 (see nativeLastError) */
    static native int nativeCreatePipeline(long handle, byte[] vertSpv, byte[] fragSpv);

    /** @param push exactly 32 floats (the 128-byte push-constant block). Returns a native FrameStatus code. */
    static native int nativeDraw(long handle, int pipeline, int vertexCount, float[] push);

    /** RGBA8 pixels of the last captured frame. */
    static native byte[] nativeCapturedPixels(long handle);

    /** [width, height, VkFormat, imageCount] */
    static native int[] nativePresenterInfo(long handle);

    static native String nativeLastError(long handle);
}
