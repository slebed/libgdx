package io.github.slebed.gdxvulkan;

/**
 * Owns the native Vulkan instance, device and allocator. Window surfaces attach through {@link #createPresenter};
 * the GL20 shim follows in later milestones. Not thread-safe except {@link #caps()}.
 */
public final class GdxVkContext implements AutoCloseable {
    private long handle;
    private final VulkanCaps caps;
    private final java.util.List<GdxVkPresenter> presenters = new java.util.ArrayList<GdxVkPresenter>();

    private GdxVkContext(long handle, VulkanCaps caps) {
        this.handle = handle;
        this.caps = caps;
    }

    /** @throws VulkanLoaderException, VulkanDeviceException, VulkanInitException */
    public static GdxVkContext create(GdxVkConfig config) {
        if (config.maxApiMinor < 1 || config.maxApiMinor > 4) {
            throw new IllegalArgumentException("maxApiMinor must be 1..4, was " + config.maxApiMinor);
        }
        GdxVk.loadNatives();
        long h = GdxVkNative.nativeCreate(config.enableValidation, config.forceTierB, config.preferredDeviceIndex,
            config.maxApiMinor, config.applicationName);
        if (h == 0) throw new VulkanInitException("Native context creation returned null without an exception");
        try {
            return new GdxVkContext(h, VulkanCaps.fromNative(GdxVkNative.nativeInfo(h)));
        } catch (RuntimeException e) {
            GdxVkNative.nativeDestroy(h);
            throw e;
        }
    }

    public VulkanCaps caps() { return caps; }

    /**
     * Creates a surface and swapchain for a native window.
     *
     * @param nativeDisplay X11 {@code Display*} on Linux; ignored on Windows
     * @param nativeWindow  {@code HWND} on Windows, X11 {@code Window} on Linux
     * @throws VulkanDeviceException if the device cannot present to the window
     */
    public synchronized GdxVkPresenter createPresenter(long nativeDisplay, long nativeWindow, int width, int height, boolean vsync) {
        if (handle == 0) throw new IllegalStateException("Context is closed");
        long p = GdxVkNative.nativeCreatePresenter(handle, nativeDisplay, nativeWindow, width, height, vsync);
        if (p == 0) throw new VulkanInitException("Native presenter creation returned null without an exception");
        GdxVkPresenter presenter = new GdxVkPresenter(this, p);
        presenters.add(presenter);
        return presenter;
    }

    /**
     * Android: creates a surface and swapchain for a {@code SurfaceView}'s surface. Destroy the presenter in
     * {@code surfaceDestroyed}; the context, and everything else created from it, can stay alive across that.
     *
     * @param surface an {@code android.view.Surface} (typed as Object so this module has no Android dependency)
     */
    public synchronized GdxVkPresenter createPresenterAndroid(Object surface, int width, int height, boolean vsync) {
        if (handle == 0) throw new IllegalStateException("Context is closed");
        long p = GdxVkNative.nativeCreatePresenterAndroid(handle, surface, width, height, vsync);
        if (p == 0) throw new VulkanInitException("Native presenter creation returned null without an exception");
        GdxVkPresenter presenter = new GdxVkPresenter(this, p);
        presenters.add(presenter);
        return presenter;
    }

    /** Validation-layer warnings and errors seen so far. Tests and CI require this to stay 0. */
    public synchronized int validationMessageCount() {
        if (handle == 0) throw new IllegalStateException("Context is closed");
        return GdxVkNative.nativeValidationCount(handle);
    }

    synchronized void presenterClosed(GdxVkPresenter p) { presenters.remove(p); }

    public boolean isClosed() { return handle == 0; }

    @Override public synchronized void close() {
        if (handle != 0) {
            // Presenters borrow the device, so they go first.
            for (GdxVkPresenter p : new java.util.ArrayList<GdxVkPresenter>(presenters)) p.close();
            GdxVkNative.nativeDestroy(handle);
            handle = 0;
        }
    }
}
