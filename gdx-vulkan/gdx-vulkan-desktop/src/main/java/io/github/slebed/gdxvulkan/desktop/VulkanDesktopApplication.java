package io.github.slebed.gdxvulkan.desktop;

import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;

import com.badlogic.gdx.AbstractGraphics;
import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationLogger;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Clipboard;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3NativesLoader;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Net;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Preferences;
import com.badlogic.gdx.backends.lwjgl3.audio.OpenALLwjgl3Audio;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GL31;
import com.badlogic.gdx.graphics.GL32;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectMap;

import io.github.slebed.gdxvulkan.GdxVk;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.VulkanSurfaceLostException;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

/**
 * A libGDX desktop application that renders with Vulkan: one GLFW window, the stock OpenAL audio, files, networking and preferences, and the
 * input of the stock LWJGL3 backend (key mapping, event queue and polling are the same code, see {@code tools/generate_desktop_input.py}).
 * {@code Gdx.gl} is a {@link VulkanGL20}: textures, framebuffers, {@code Mesh} data and the batches of this library work as on Android.
 *
 * <pre>
 * VulkanDesktopConfiguration config = new VulkanDesktopConfiguration();
 * config.title = "My game";
 * new VulkanDesktopApplication(new MyGame(), config); // returns when the window closes
 * </pre>
 *
 * It runs the loop on the calling thread, and that must be the thread that started the JVM's main (GLFW's rule on macOS and a good habit
 * elsewhere). Everything touching Vulkan happens on that thread: {@code create}, {@code render}, {@code resize}, {@code dispose}.
 */
public class VulkanDesktopApplication implements Application {
    private final ApplicationListener listener;
    private final VulkanDesktopConfiguration config;
    private final Array<Runnable> runnables = new Array<Runnable>();
    private final Array<Runnable> executed = new Array<Runnable>();
    private final Array<LifecycleListener> lifecycleListeners = new Array<LifecycleListener>();
    private final ObjectMap<String, Preferences> preferences = new ObjectMap<String, Preferences>();

    private final Files files;
    private final Net net;
    private final Audio audio;
    private final Clipboard clipboard = new Lwjgl3Clipboard();

    private long window;
    private GdxVkContext context;
    private VulkanGL20 gl;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private VulkanDesktopInput input;
    private Gfx graphics;
    private volatile boolean running = true;
    private int logLevel = LOG_INFO;
    private ApplicationLogger logger = new Lwjgl3ApplicationLogger();
    private volatile byte[] captured;
    private volatile boolean captureWanted;
    private GLFWErrorCallback errorCallback;

    /** For a game that already has a stock {@code Lwjgl3ApplicationConfiguration}: its window, vsync, audio and preferences settings are used. */
    public VulkanDesktopApplication (ApplicationListener listener, Lwjgl3ApplicationConfiguration config) {
        this(listener, com.badlogic.gdx.backends.lwjgl3.VulkanConfigurations.from(config));
    }

    /** Creates the window with the defaults of {@link VulkanDesktopConfiguration} and runs the application until it closes. */
    public VulkanDesktopApplication (ApplicationListener listener) {
        this(listener, new VulkanDesktopConfiguration());
    }

    /** Creates the window and runs the application until it closes. */
    public VulkanDesktopApplication (ApplicationListener listener, VulkanDesktopConfiguration config) {
        this.listener = listener;
        this.config = config;
        Lwjgl3NativesLoader.load();
        GdxVk.loadNatives();
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        GLFW.glfwSetErrorCallback(errorCallback);
        if (!GLFW.glfwInit()) throw new GdxRuntimeException("Unable to initialize GLFW");

        Gdx.app = this;
        files = new Lwjgl3Files();
        net = new Lwjgl3Net(new Lwjgl3ApplicationConfiguration());
        audio = config.disableAudio ? null : new OpenALLwjgl3Audio(config.audioDeviceSimultaneousSources, config.audioDeviceBufferCount,
            config.audioDeviceBufferSize);
        try {
            run();
        } finally {
            cleanup();
        }
    }

    // ---- the loop

    private void run () {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API); // Vulkan renders into the window's surface itself
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, config.resizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, config.decorated ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_MAXIMIZED, config.maximized ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        long monitor = config.fullscreen ? GLFW.glfwGetPrimaryMonitor() : 0;
        window = GLFW.glfwCreateWindow(config.width, config.height, config.title, monitor, 0);
        if (window == 0) throw new GdxRuntimeException("Couldn't create the window");
        if (!config.fullscreen && config.x >= 0 && config.y >= 0) GLFW.glfwSetWindowPos(window, config.x, config.y);

        context = GdxVkContext.create(config.vk);
        gl = new VulkanGL20(context);
        Gdx.gl = gl;
        Gdx.gl20 = gl;
        Gdx.gl30 = null; // always null: a non-null value would switch stock Mesh to VAOs
        graphics = new Gfx();
        input = new VulkanDesktopInput(graphics);
        Gdx.graphics = graphics;
        Gdx.input = input;
        Gdx.files = files;
        Gdx.net = net;
        Gdx.audio = audio;

        presenter = GlfwSurfaces.createPresenter(context, window, config.vsync);
        frame = new GdxVkFrame(context, presenter, gl);
        if (config.depthBuffer) frame.requireDepth(GdxVkPresenter.DEPTH);
        GLFW.glfwShowWindow(window);
        graphics.refreshSizes();
        graphics.continuous = config.continuousRendering;

        listener.create();
        listener.resize(graphics.getWidth(), graphics.getHeight());
        long last = System.nanoTime();

        while (running && !GLFW.glfwWindowShouldClose(window)) {
            if (graphics.continuous || graphics.rendering) GLFW.glfwPollEvents();
            else GLFW.glfwWaitEventsTimeout(0.1);
            final long now = System.nanoTime();
            graphics.deltaTime = (now - last) / 1e9f;
            last = now;

            if (graphics.refreshSizes()) {
                if (graphics.backW > 0 && graphics.backH > 0) {
                    presenter.resize(graphics.backW, graphics.backH);
                    listener.resize(graphics.getWidth(), graphics.getHeight());
                }
            }
            final boolean iconified = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE || graphics.backW == 0 || graphics.backH == 0;
            if (iconified) {
                sleep(16);
                continue;
            }
            if (!graphics.continuous && !graphics.rendering && !graphics.firstFrame && !captureWanted) continue;
            graphics.rendering = false;
            graphics.firstFrame = false;

            try {
                if (!frame.begin(config.clearRed, config.clearGreen, config.clearBlue, config.clearAlpha)) {
                    sleep(8);
                    continue;
                }
            } catch (VulkanSurfaceLostException lost) {
                rebuildPresenter();
                continue;
            }
            try {
                runRunnables();
                input.update();
                listener.render();
            } finally {
                try {
                    if (captureWanted) {
                        captureWanted = false;
                        captured = frame.endAndCapture();
                    } else {
                        frame.end();
                    }
                } catch (VulkanSurfaceLostException lost) {
                    rebuildPresenter();
                }
            }
            input.prepareNext();
            graphics.frameId++;
            graphics.countFrame(now);
            if (config.foregroundFPS > 0) {
                final long budget = 1_000_000_000L / config.foregroundFPS, spent = System.nanoTime() - now;
                if (spent < budget) sleep((budget - spent) / 1_000_000L);
            }
        }
    }

    private void rebuildPresenter () {
        presenter.close();
        presenter = GlfwSurfaces.createPresenter(context, window, graphics.vsync);
        frame.replacePresenter(presenter);
    }

    private static void sleep (long millis) {
        try {
            Thread.sleep(Math.max(1, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runRunnables () {
        synchronized (runnables) {
            executed.clear();
            executed.addAll(runnables);
            runnables.clear();
        }
        for (int i = 0; i < executed.size; i++) executed.get(i).run();
    }

    private void cleanup () {
        try {
            if (listener != null && graphics != null) {
                synchronized (lifecycleListeners) {
                    for (LifecycleListener l : lifecycleListeners) l.pause();
                }
                listener.pause();
                synchronized (lifecycleListeners) {
                    for (LifecycleListener l : lifecycleListeners) l.dispose();
                }
                listener.dispose();
            }
        } finally {
            if (frame != null && frame.isOpen()) {
                try {
                    frame.end();
                } catch (RuntimeException ignored) {
                }
            }
            if (presenter != null) presenter.close();
            if (context != null) context.close();
            if (audio instanceof OpenALLwjgl3Audio) ((OpenALLwjgl3Audio)audio).dispose();
            if (input != null) input.dispose();
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            if (errorCallback != null) errorCallback.free();
            Gdx.app = null;
            Gdx.graphics = null;
            Gdx.input = null;
            Gdx.gl = null;
            Gdx.gl20 = null;
            Gdx.files = null;
            Gdx.net = null;
            Gdx.audio = null;
        }
    }

    // ---- for tests and tools

    /** The next frame as RGBA8 (row 0 at the top), captured when it is presented; null until one was drawn. Call from the render thread. */
    public void requestCapture () { captureWanted = true; captured = null; }

    public byte[] capturedFrame () { return captured; }

    public GdxVkFrame getFrame () { return frame; }

    public GdxVkContext getContext () { return context; }

    public GdxVkPresenter getPresenter () { return presenter; }

    public long getWindowHandle () { return window; }

    public VulkanDesktopInput getVulkanInput () { return input; }

    // ---- Application

    @Override public ApplicationListener getApplicationListener () { return listener; }
    @Override public Graphics getGraphics () { return graphics; }
    @Override public Audio getAudio () { return audio; }
    @Override public Input getInput () { return input; }
    @Override public Files getFiles () { return files; }
    @Override public Net getNet () { return net; }

    @Override public void log (String tag, String message) { if (logLevel >= LOG_INFO) logger.log(tag, message); }
    @Override public void log (String tag, String message, Throwable exception) { if (logLevel >= LOG_INFO) logger.log(tag, message, exception); }
    @Override public void error (String tag, String message) { if (logLevel >= LOG_ERROR) logger.error(tag, message); }
    @Override public void error (String tag, String message, Throwable exception) { if (logLevel >= LOG_ERROR) logger.error(tag, message, exception); }
    @Override public void debug (String tag, String message) { if (logLevel >= LOG_DEBUG) logger.debug(tag, message); }
    @Override public void debug (String tag, String message, Throwable exception) { if (logLevel >= LOG_DEBUG) logger.debug(tag, message, exception); }
    @Override public void setLogLevel (int logLevel) { this.logLevel = logLevel; }
    @Override public int getLogLevel () { return logLevel; }
    @Override public void setApplicationLogger (ApplicationLogger applicationLogger) { this.logger = applicationLogger; }
    @Override public ApplicationLogger getApplicationLogger () { return logger; }
    @Override public ApplicationType getType () { return ApplicationType.Desktop; }

    @Override public int getVersion () { return 0; }

    @Override public long getJavaHeap () { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }

    @Override public long getNativeHeap () { return getJavaHeap(); }

    @Override public Preferences getPreferences (String name) {
        Preferences p = preferences.get(name);
        if (p == null) {
            p = new Lwjgl3Preferences(name, config.preferencesDirectory);
            preferences.put(name, p);
        }
        return p;
    }

    @Override public Clipboard getClipboard () { return clipboard; }

    @Override public void postRunnable (Runnable runnable) {
        synchronized (runnables) {
            runnables.add(runnable);
        }
        GLFW.glfwPostEmptyEvent(); // wakes a loop that is waiting for events
    }

    @Override public void exit () { running = false; }

    @Override public void addLifecycleListener (LifecycleListener listener) {
        synchronized (lifecycleListeners) {
            lifecycleListeners.add(listener);
        }
    }

    @Override public void removeLifecycleListener (LifecycleListener listener) {
        synchronized (lifecycleListeners) {
            lifecycleListeners.removeValue(listener, true);
        }
    }

    // ---- Graphics

    private final class Gfx extends AbstractGraphics implements DesktopInputHost, io.github.slebed.gdxvulkan.GdxVkFrameSource {
        @Override public GdxVkFrame getFrame () { return frame; }

        int logicalW, logicalH, backW, backH;
        float deltaTime;
        long frameId;
        int fps, fpsFrames;
        long fpsStart = System.nanoTime();
        volatile boolean continuous = true, rendering, firstFrame = true;
        boolean vsync = config.vsync;
        private final IntBuffer w = BufferUtils.createIntBuffer(1), h = BufferUtils.createIntBuffer(1);

        /** Reads the window's sizes; true when either changed. */
        boolean refreshSizes () {
            GLFW.glfwGetWindowSize(window, w, h);
            final int lw = w.get(0), lh = h.get(0);
            GLFW.glfwGetFramebufferSize(window, w, h);
            final int bw = w.get(0), bh = h.get(0);
            final boolean changed = lw != logicalW || lh != logicalH || bw != backW || bh != backH;
            logicalW = lw;
            logicalH = lh;
            backW = bw;
            backH = bh;
            return changed;
        }

        void countFrame (long now) {
            fpsFrames++;
            if (now - fpsStart >= 1_000_000_000L) {
                fps = fpsFrames;
                fpsFrames = 0;
                fpsStart = now;
            }
        }

        // DesktopInputHost
        @Override public long windowHandle () { return window; }
        @Override public boolean hdpiPixels () { return false; }
        @Override public int backBufferWidth () { return backW; }
        @Override public int backBufferHeight () { return backH; }
        @Override public int logicalWidth () { return logicalW; }
        @Override public int logicalHeight () { return logicalH; }

        @Override public boolean isGL30Available () { return false; }
        @Override public boolean isGL31Available () { return false; }
        @Override public boolean isGL32Available () { return false; }
        @Override public GL20 getGL20 () { return gl; }
        @Override public GL30 getGL30 () { return null; }
        @Override public GL31 getGL31 () { return null; }
        @Override public GL32 getGL32 () { return null; }
        @Override public void setGL20 (GL20 gl20) {}
        @Override public void setGL30 (GL30 gl30) {}
        @Override public void setGL31 (GL31 gl31) {}
        @Override public void setGL32 (GL32 gl32) {}
        @Override public int getWidth () { return logicalW; }
        @Override public int getHeight () { return logicalH; }
        @Override public int getBackBufferWidth () { return backW; }
        @Override public int getBackBufferHeight () { return backH; }
        @Override public float getBackBufferScale () { return logicalW == 0 ? 1f : backW / (float)logicalW; }
        @Override public int getSafeInsetLeft () { return 0; }
        @Override public int getSafeInsetTop () { return 0; }
        @Override public int getSafeInsetBottom () { return 0; }
        @Override public int getSafeInsetRight () { return 0; }
        @Override public long getFrameId () { return frameId; }
        @Override public float getDeltaTime () { return deltaTime; }
        @Override public float getRawDeltaTime () { return deltaTime; }
        @Override public int getFramesPerSecond () { return fps; }
        @Override public GraphicsType getType () { return GraphicsType.LWJGL3; }

        @Override public GLVersion getGLVersion () {
            return new GLVersion(ApplicationType.Desktop, "OpenGL ES 2.0 (gdx-vulkan)", "gdx-vulkan", context.caps().deviceName);
        }

        @Override public float getPpiX () { return getPpcX() * 2.54f; }
        @Override public float getPpiY () { return getPpcY() * 2.54f; }

        @Override public float getPpcX () {
            final long m = GLFW.glfwGetPrimaryMonitor();
            GLFW.glfwGetMonitorPhysicalSize(m, w, h);
            final GLFWVidMode mode = GLFW.glfwGetVideoMode(m);
            return w.get(0) == 0 || mode == null ? 38f : mode.width() / (w.get(0) / 10f);
        }

        @Override public float getPpcY () {
            final long m = GLFW.glfwGetPrimaryMonitor();
            GLFW.glfwGetMonitorPhysicalSize(m, w, h);
            final GLFWVidMode mode = GLFW.glfwGetVideoMode(m);
            return h.get(0) == 0 || mode == null ? 38f : mode.height() / (h.get(0) / 10f);
        }

        @Override public float getDensity () { return getPpiX() / 160f; }
        @Override public boolean supportsDisplayModeChange () { return true; }

        private final class VkMonitor extends Monitor {
            final long handle;

            VkMonitor (long handle) {
                super(0, 0, String.valueOf(GLFW.glfwGetMonitorName(handle)));
                this.handle = handle;
            }
        }

        private final class VkDisplayMode extends DisplayMode {
            final long monitor;

            VkDisplayMode (long monitor, int width, int height, int refresh, int bpp) {
                super(width, height, refresh, bpp);
                this.monitor = monitor;
            }
        }

        @Override public Monitor getPrimaryMonitor () { return new VkMonitor(GLFW.glfwGetPrimaryMonitor()); }

        @Override public Monitor getMonitor () {
            final long m = GLFW.glfwGetWindowMonitor(window);
            return new VkMonitor(m != 0 ? m : GLFW.glfwGetPrimaryMonitor());
        }

        @Override public Monitor[] getMonitors () {
            final org.lwjgl.PointerBuffer all = GLFW.glfwGetMonitors();
            final Monitor[] out = new Monitor[all == null ? 0 : all.limit()];
            for (int i = 0; i < out.length; i++) out[i] = new VkMonitor(all.get(i));
            return out;
        }

        @Override public DisplayMode[] getDisplayModes () { return getDisplayModes(getPrimaryMonitor()); }

        @Override public DisplayMode[] getDisplayModes (Monitor monitor) {
            final long m = ((VkMonitor)monitor).handle;
            final GLFWVidMode.Buffer modes = GLFW.glfwGetVideoModes(m);
            final DisplayMode[] out = new DisplayMode[modes == null ? 0 : modes.limit()];
            for (int i = 0; i < out.length; i++) {
                final GLFWVidMode v = modes.get(i);
                out[i] = new VkDisplayMode(m, v.width(), v.height(), v.refreshRate(), v.redBits() + v.greenBits() + v.blueBits());
            }
            return out;
        }

        @Override public DisplayMode getDisplayMode () { return getDisplayMode(getMonitor()); }

        @Override public DisplayMode getDisplayMode (Monitor monitor) {
            final long m = ((VkMonitor)monitor).handle;
            final GLFWVidMode v = GLFW.glfwGetVideoMode(m);
            return new VkDisplayMode(m, v.width(), v.height(), v.refreshRate(), v.redBits() + v.greenBits() + v.blueBits());
        }

        @Override public boolean setFullscreenMode (DisplayMode displayMode) {
            if (!(displayMode instanceof VkDisplayMode)) return false;
            final VkDisplayMode d = (VkDisplayMode)displayMode;
            GLFW.glfwSetWindowMonitor(window, d.monitor, 0, 0, d.width, d.height, d.refreshRate);
            return true;
        }

        @Override public boolean setWindowedMode (int width, int height) {
            if (GLFW.glfwGetWindowMonitor(window) == 0) {
                GLFW.glfwSetWindowSize(window, width, height);
            } else {
                GLFW.glfwSetWindowMonitor(window, 0, 100, 100, width, height, GLFW.GLFW_DONT_CARE);
            }
            return true;
        }

        @Override public void setTitle (String title) { GLFW.glfwSetWindowTitle(window, title == null ? "" : title); }
        @Override public void setUndecorated (boolean undecorated) { GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, undecorated ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE); }
        @Override public void setResizable (boolean resizable) { GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_RESIZABLE, resizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE); }

        @Override public void setVSync (boolean vsync) {
            if (this.vsync == vsync) return;
            this.vsync = vsync;
            postRunnable(new Runnable() {
                @Override public void run () {
                    rebuildPresenter(); // the present mode is chosen when the swapchain is created
                }
            });
        }

        @Override public void setForegroundFPS (int fps) { config.foregroundFPS = fps; }
        @Override public BufferFormat getBufferFormat () { return new BufferFormat(8, 8, 8, 8, 0, 0, 0, false); }
        @Override public boolean supportsExtension (String extension) { return gl.supportsExtension(extension); }
        @Override public void setContinuousRendering (boolean isContinuous) { continuous = isContinuous; }
        @Override public boolean isContinuousRendering () { return continuous; }

        @Override public void requestRendering () {
            rendering = true;
            GLFW.glfwPostEmptyEvent();
        }

        @Override public boolean isFullscreen () { return GLFW.glfwGetWindowMonitor(window) != 0; }

        @Override public Cursor newCursor (Pixmap pixmap, int xHotspot, int yHotspot) { return null; }
        @Override public void setCursor (Cursor cursor) {}

        @Override public void setSystemCursor (Cursor.SystemCursor systemCursor) {
            final int shape;
            switch (systemCursor) {
            case Ibeam: shape = GLFW.GLFW_IBEAM_CURSOR; break;
            case Crosshair: shape = GLFW.GLFW_CROSSHAIR_CURSOR; break;
            case Hand: shape = GLFW.GLFW_HAND_CURSOR; break;
            case HorizontalResize: shape = GLFW.GLFW_HRESIZE_CURSOR; break;
            case VerticalResize: shape = GLFW.GLFW_VRESIZE_CURSOR; break;
            default: shape = GLFW.GLFW_ARROW_CURSOR; break;
            }
            GLFW.glfwSetCursor(window, GLFW.glfwCreateStandardCursor(shape));
        }
    }
}
