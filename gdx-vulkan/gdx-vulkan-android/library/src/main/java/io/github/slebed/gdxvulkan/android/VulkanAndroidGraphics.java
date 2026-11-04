package io.github.slebed.gdxvulkan.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.backends.android.AndroidApplicationBase;
import com.badlogic.gdx.backends.android.AndroidInput;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GL31;
import com.badlogic.gdx.graphics.GL32;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.SnapshotArray;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.VulkanSurfaceLostException;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The {@link Graphics} of a {@link VulkanAndroidApplication}: a {@code SurfaceView} and the render thread that owns every Vulkan call.
 *
 * <p>The surface comes and goes with the Android lifecycle; the renderer does not. On {@code surfaceDestroyed} only the presenter (swapchain
 * and surface) is destroyed. The context, the textures, buffers and framebuffers, and the application's own objects stay resident, so
 * nothing is "managed" and nothing is reloaded when the surface comes back: a new presenter is created and the application resumes drawing.
 */
public class VulkanAndroidGraphics implements Graphics, SurfaceHolder.Callback, io.github.slebed.gdxvulkan.GdxVkFrameSource {
    private static final String TAG = "VulkanAndroidGraphics";

    final VulkanSurfaceView view;
    private final VulkanAndroidApplication app;
    private final GdxVkConfig vkConfig;
    private final boolean vsync;
    private final ApplicationListener listener;

    // Render thread only, unless noted.
    private Thread thread;
    private GdxVkContext context;
    private VulkanGL20 gl;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private boolean created, resumed, destroyed;
    private int width, height; // logical size of the surface, as the application sees it
    private int lastResizeW = -1, lastResizeH = -1;

    // Shared: guarded by lock.
    private final Object lock = new Object();
    private final ArrayDeque<Runnable> events = new ArrayDeque<Runnable>();

    private volatile boolean continuous = true;
    private volatile boolean rendering;
    private volatile long frameId;
    private volatile float deltaTime;
    private volatile int fps;
    private long lastFrameNanos = System.nanoTime(), fpsStart = System.nanoTime();
    private int fpsFrames;
    private volatile float density = 1f, ppiX = 160f, ppiY = 160f, ppcX = 63f, ppcY = 63f;
    private volatile int safeLeft, safeTop, safeRight, safeBottom;

    /** Nanoseconds the last surface recreation took (presenter creation, from surface callback to first frame ready). */
    volatile long lastRecreateNanos;
    volatile int recreateCount;
    private long surfaceChangedNanos;

    VulkanAndroidGraphics(VulkanAndroidApplication app, ApplicationListener listener, GdxVkConfig vkConfig, boolean vsync) {
        this.app = app;
        this.listener = listener;
        this.vkConfig = vkConfig;
        this.vsync = vsync;
        view = new VulkanSurfaceView(app);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.getHolder().addCallback(this);
        DisplayMetrics m = new DisplayMetrics();
        app.getWindowManager().getDefaultDisplay().getMetrics(m);
        density = m.density;
        ppiX = m.xdpi;
        ppiY = m.ydpi;
        ppcX = m.xdpi / 2.54f;
        ppcY = m.ydpi / 2.54f;
    }

    /** Give the screen a depth buffer from the start: set from the stock configuration's depth bits. */
    volatile boolean depthBuffer;

    public View getView() { return view; }

    // ---- lifecycle, called from the UI thread

    void start() {
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "gdxvk-render");
        thread.start();
    }

    private void post(Runnable r) {
        synchronized (lock) {
            events.add(r);
            lock.notifyAll();
        }
    }

    /** Posts and waits for the render thread to have run it (bounded, so a stuck renderer cannot hang the UI thread forever). */
    private void postAndWait(Runnable r) {
        final CountDownLatch done = new CountDownLatch(1);
        final Runnable inner = r;
        post(new Runnable() {
            @Override public void run() {
                try {
                    inner.run();
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            if (!done.await(5, TimeUnit.SECONDS)) Gdx.app.error(TAG, "the render thread did not finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void onResume() {
        post(new Runnable() {
            @Override public void run() {
                if (resumed) return;
                resumed = true;
                lastFrameNanos = System.nanoTime();
                if (created) {
                    notifyLifecycle(0);
                    listener.resume();
                }
            }
        });
    }

    void onPause() {
        postAndWait(new Runnable() {
            @Override public void run() {
                if (!resumed) return;
                resumed = false;
                if (created) {
                    notifyLifecycle(1);
                    listener.pause();
                }
            }
        });
    }

    /** Memory pressure ({@code onTrimMemory} / {@code onLowMemory}): frees idle staging memory on the render thread. Everything the application holds stays. */
    void trimMemory() {
        post(new Runnable() {
            @Override public void run() {
                if (context == null) return;
                int freed = context.trimMemory();
                Gdx.app.log(TAG, "trim memory: " + freed + " idle staging block(s) freed");
            }
        });
    }

    /** The activity is finishing: dispose the application, then everything Vulkan, on the render thread. */
    void destroy() {
        postAndWait(new Runnable() {
            @Override public void run() {
                if (created) {
                    notifyLifecycle(2);
                    listener.dispose();
                    created = false;
                }
                closePresenter();
                if (context != null) {
                    context.close();
                    context = null;
                }
                destroyed = true;
            }
        });
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 0 resume, 1 pause, 2 dispose. begin() hands back the backing array, so only the first {@code size} entries are listeners. */
    private void notifyLifecycle(int what) {
        final SnapshotArray<LifecycleListener> listeners = app.getLifecycleListeners();
        synchronized (listeners) {
            LifecycleListener[] items = listeners.begin();
            for (int i = 0, n = listeners.size; i < n; i++) {
                if (what == 0) items[i].resume();
                else if (what == 1) items[i].pause();
                else items[i].dispose();
            }
            listeners.end();
        }
    }

    // ---- SurfaceHolder.Callback, UI thread

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceChangedNanos = System.nanoTime();
    }

    @Override public void surfaceChanged(final SurfaceHolder holder, int format, final int w, final int h) {
        final long t0 = surfaceChangedNanos != 0 ? surfaceChangedNanos : System.nanoTime();
        surfaceChangedNanos = 0;
        updateInsets();
        post(new Runnable() {
            @Override public void run() {
                try {
                    surfaceReady(holder.getSurface(), w, h, t0);
                } catch (Throwable t) {
                    fatal("surfaceChanged failed", t);
                }
            }
        });
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        // The swapchain must be gone before the surface is (Android frees the buffers when this returns).
        postAndWait(new Runnable() {
            @Override public void run() {
                closePresenter();
                Gdx.app.log(TAG, "surface destroyed; the renderer stays resident");
            }
        });
    }

    // ---- the render thread

    private void surfaceReady(Surface surface, int w, int h, long t0) {
        if (destroyed) return;
        if (context == null) {
            context = GdxVkContext.create(vkConfig);
            gl = new VulkanGL20(context);
            Gdx.gl = gl;
            Gdx.gl20 = gl;
            Gdx.gl30 = null; // always null: a non-null value would switch stock Mesh to VAOs
            Gdx.app.log(TAG, "device=" + context.caps().deviceName + " tier=" + context.caps().tier);
        }
        if (presenter != null && lastResizeW == w && lastResizeH == h) return; // a repeated callback for the same surface
        if (presenter != null) {
            presenter.resize(w, h);
        } else {
            presenter = context.createPresenterAndroid(surface, w, h, vsync, vkConfig.preRotate);
            if (frame == null) {
                frame = new GdxVkFrame(context, presenter, gl);
                if (depthBuffer) frame.requireDepth(io.github.slebed.gdxvulkan.GdxVkPresenter.DEPTH);
            }
            else frame.replacePresenter(presenter); // batches keep their frame; only its swapchain side is new
            recreateCount++;
            lastRecreateNanos = System.nanoTime() - t0;
        }
        width = w;
        height = h;
        lastResizeW = w;
        lastResizeH = h;
        if (!created) {
            created = true;
            listener.create();
        }
        listener.resize(w, h);
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void closePresenter() {
        if (frame != null && frame.isOpen()) {
            try {
                frame.end();
            } catch (Throwable ignored) {
            }
        }
        if (presenter != null) {
            presenter.close();
            presenter = null;
        }
        lastResizeW = lastResizeH = -1;
    }

    private void fatal(String what, Throwable t) {
        Gdx.app.error(TAG, what, t);
        app.exit();
    }

    private void loop() {
        try {
            while (true) {
                Runnable e;
                synchronized (lock) {
                    while ((e = events.poll()) == null && !destroyed && !shouldRender()) lock.wait();
                }
                if (e != null) {
                    e.run();
                    continue;
                }
                if (destroyed) return;
                renderFrame();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            fatal("render loop failed", t);
        }
    }

    private boolean shouldRender() {
        return resumed && created && presenter != null && frame != null && (continuous || rendering);
    }

    private void renderFrame() {
        final long now = System.nanoTime();
        deltaTime = (now - lastFrameNanos) / 1e9f;
        lastFrameNanos = now;
        rendering = false;
        try {
            if (!frame.begin(0, 0, 0, 1)) {
                try {
                    Thread.sleep(8); // nothing to draw into (zero-area surface); do not spin
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
        } catch (VulkanSurfaceLostException lost) {
            Gdx.app.log(TAG, "surface lost; waiting for a new one");
            closePresenter();
            return;
        }
        try {
            runRunnables();
            AndroidInput input = app.getInput();
            if (input instanceof com.badlogic.gdx.backends.android.DefaultAndroidInput) {
                ((com.badlogic.gdx.backends.android.DefaultAndroidInput)input).processEvents();
            }
            listener.render();
        } finally {
            try {
                final CountDownLatch want = captureWanted;
                if (want != null) {
                    captureWanted = null;
                    captured = frame.endAndCapture();
                    want.countDown();
                } else {
                    frame.end();
                }
            } catch (VulkanSurfaceLostException lost) {
                closePresenter();
            }
        }
        frameId++;
        fpsFrames++;
        if (now - fpsStart >= 1_000_000_000L) {
            fps = fpsFrames;
            fpsFrames = 0;
            fpsStart = now;
        }
    }

    private void runRunnables() {
        Array<Runnable> runnables = app.getRunnables();
        Array<Runnable> executed = app.getExecutedRunnables();
        synchronized (runnables) {
            executed.clear();
            executed.addAll(runnables);
            runnables.clear();
        }
        for (int i = 0; i < executed.size; i++) {
            try {
                executed.get(i).run();
            } catch (Throwable t) {
                Gdx.app.error(TAG, "posted runnable failed", t);
            }
        }
    }

    private void updateInsets() {
        if (Build.VERSION.SDK_INT >= 28) {
            WindowInsets insets = view.getRootWindowInsets();
            DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
            if (cutout != null) {
                safeLeft = cutout.getSafeInsetLeft();
                safeTop = cutout.getSafeInsetTop();
                safeRight = cutout.getSafeInsetRight();
                safeBottom = cutout.getSafeInsetBottom();
            } else {
                safeLeft = safeTop = safeRight = safeBottom = 0;
            }
        }
    }

    // ---- Graphics

    @Override public boolean isGL30Available() { return false; }
    @Override public boolean isGL31Available() { return false; }
    @Override public boolean isGL32Available() { return false; }
    @Override public GL20 getGL20() { return gl; }
    @Override public GL30 getGL30() { return null; }
    @Override public GL31 getGL31() { return null; }
    @Override public GL32 getGL32() { return null; }
    @Override public void setGL20(GL20 gl20) {}
    @Override public void setGL30(GL30 gl30) {}
    @Override public void setGL31(GL31 gl31) {}
    @Override public void setGL32(GL32 gl32) {}
    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }
    @Override public int getBackBufferWidth() { return width; }
    @Override public int getBackBufferHeight() { return height; }
    @Override public float getBackBufferScale() { return 1f; }
    @Override public int getSafeInsetLeft() { return safeLeft; }
    @Override public int getSafeInsetTop() { return safeTop; }
    @Override public int getSafeInsetBottom() { return safeBottom; }
    @Override public int getSafeInsetRight() { return safeRight; }
    @Override public long getFrameId() { return frameId; }
    @Override public float getDeltaTime() { return deltaTime; }
    @Override public float getRawDeltaTime() { return deltaTime; }
    @Override public int getFramesPerSecond() { return fps; }
    @Override public GraphicsType getType() { return GraphicsType.AndroidGL; }

    @Override public GLVersion getGLVersion() {
        return new GLVersion(com.badlogic.gdx.Application.ApplicationType.Android, "OpenGL ES 2.0 (gdx-vulkan)", "gdx-vulkan",
            context != null ? context.caps().deviceName : "Vulkan");
    }

    @Override public float getPpiX() { return ppiX; }
    @Override public float getPpiY() { return ppiY; }
    @Override public float getPpcX() { return ppcX; }
    @Override public float getPpcY() { return ppcY; }
    @Override public float getDensity() { return density; }
    @Override public boolean supportsDisplayModeChange() { return false; }

    private final class VkMonitor extends Monitor {
        VkMonitor() { super(0, 0, "Primary Monitor"); }
    }

    private final class VkDisplayMode extends DisplayMode {
        VkDisplayMode(int w, int h, int refresh, int bpp) { super(w, h, refresh, bpp); }
    }

    @Override public Monitor getPrimaryMonitor() { return new VkMonitor(); }
    @Override public Monitor getMonitor() { return getPrimaryMonitor(); }
    @Override public Monitor[] getMonitors() { return new Monitor[] {getPrimaryMonitor()}; }
    @Override public DisplayMode[] getDisplayModes() { return new DisplayMode[] {getDisplayMode()}; }
    @Override public DisplayMode[] getDisplayModes(Monitor monitor) { return getDisplayModes(); }

    @Override public DisplayMode getDisplayMode() {
        DisplayMetrics m = new DisplayMetrics();
        Display d = app.getWindowManager().getDefaultDisplay();
        d.getRealMetrics(m);
        return new VkDisplayMode(m.widthPixels, m.heightPixels, (int)d.getRefreshRate(), 32);
    }

    @Override public DisplayMode getDisplayMode(Monitor monitor) { return getDisplayMode(); }
    @Override public boolean setFullscreenMode(DisplayMode displayMode) { return false; }
    @Override public boolean setWindowedMode(int width, int height) { return false; }
    @Override public void setTitle(String title) {}
    @Override public void setUndecorated(boolean undecorated) {}
    @Override public void setResizable(boolean resizable) {}
    @Override public void setVSync(boolean vsync) {}
    @Override public void setForegroundFPS(int fps) {}
    @Override public BufferFormat getBufferFormat() { return new BufferFormat(8, 8, 8, 8, 0, 0, 0, false); }

    @Override public boolean supportsExtension(String extension) {
        return gl != null && gl.supportsExtension(extension);
    }

    @Override public void setContinuousRendering(boolean isContinuous) { continuous = isContinuous; if (isContinuous) wake(); }
    @Override public boolean isContinuousRendering() { return continuous; }
    @Override public void requestRendering() { rendering = true; wake(); }

    private void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    @Override public boolean isFullscreen() { return true; }
    @Override public Cursor newCursor(Pixmap pixmap, int xHotspot, int yHotspot) { return null; }
    @Override public void setCursor(Cursor cursor) {}
    @Override public void setSystemCursor(Cursor.SystemCursor systemCursor) {}

    // ---- for tests and diagnostics

    private volatile CountDownLatch captureWanted;
    private volatile byte[] captured;

    /** The next frame as RGBA8 (row 0 at the top), or null if none was drawn within the timeout. Call from any thread but the render thread. */
    public byte[] captureFrame(long timeoutMillis) {
        CountDownLatch l = new CountDownLatch(1);
        captured = null;
        captureWanted = l;
        requestRendering();
        try {
            if (!l.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                captureWanted = null;
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return captured;
    }

    /**
     * The frame the batches draw into. Valid from {@code create()} on and the same object for the life of the application: the swapchain behind
     * it is replaced when the surface is recreated.
     */
    public GdxVkFrame getFrame() { return frame; }

    public GdxVkContext getContext() { return context; }
    public GdxVkPresenter getPresenter() { return presenter; }
    public long getLastRecreateNanos() { return lastRecreateNanos; }
    public int getRecreateCount() { return recreateCount; }
}
