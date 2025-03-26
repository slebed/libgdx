/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backends.vulkan;

import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;

import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.IntBuffer;

import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.backends.vulkan.audio.OpenALLwjgl3Audio;
import com.badlogic.gdx.backends.vulkan.audio.VulkanAudio;
import com.badlogic.gdx.graphics.glutils.GLVersion;

import com.badlogic.gdx.utils.*;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.AMDDebugOutput;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.system.Callback;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backends.vulkan.audio.mock.MockAudio;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.SharedLibraryLoader;

public class VulkanApplication implements VulkanApplicationBase {
    //private final VulkanApplicationConfiguration config;
    final Array<VulkanWindow> windows = new Array<VulkanWindow>();
    //final Array<Lwjgl3Window> windows = new Array<Lwjgl3Window>();
    private volatile VulkanWindow currentWindow;
    private VulkanAudio audio;
    private final Files files;
    private final Net net;
    private final ObjectMap<String, Preferences> preferences = new ObjectMap<String, Preferences>();
    //private final VulkanClipboard clipboard;
    private int logLevel = LOG_INFO;
    private ApplicationLogger applicationLogger;
    private volatile boolean running = true;
    private final Array<Runnable> runnables = new Array<Runnable>();
    private final Array<Runnable> executedRunnables = new Array<Runnable>();
    private final Array<LifecycleListener> lifecycleListeners = new Array<LifecycleListener>();
    private static GLFWErrorCallback errorCallback;
    private static GLVersion glVersion;
    private static Callback glDebugCallback;
    //private final Sync sync;

    private final VulkanClipboard clipboard;
    private final VulkanApplicationConfiguration config;
    private long window;
    private VulkanWindow gdxWindow;

    static void initializeGlfw() {
        if (errorCallback == null) {
            VulkanNativesLoader.load();
            errorCallback = GLFWErrorCallback.createPrint(VulkanApplicationConfiguration.errorStream);
            GLFW.glfwSetErrorCallback(errorCallback);
            if (SharedLibraryLoader.os == Os.MacOsX)
                GLFW.glfwInitHint(GLFW.GLFW_ANGLE_PLATFORM_TYPE, GLFW.GLFW_ANGLE_PLATFORM_TYPE_METAL);
            GLFW.glfwInitHint(GLFW.GLFW_JOYSTICK_HAT_BUTTONS, GLFW.GLFW_FALSE);
            if (!GLFW.glfwInit()) {
                throw new GdxRuntimeException("Unable to initialize GLFW");
            }
        }
    }

    /*static void loadANGLE() {
        try {
            Class angleLoader = Class.forName("com.badlogic.gdx.backends.lwjgl3.angle.ANGLELoader");
            Method load = angleLoader.getMethod("load");
            load.invoke(angleLoader);
        } catch (ClassNotFoundException t) {
            return;
        } catch (Throwable t) {
            throw new GdxRuntimeException("Couldn't load ANGLE.", t);
        }
    }

    static void postLoadANGLE() {
        try {
            Class angleLoader = Class.forName("com.badlogic.gdx.backends.lwjgl3.angle.ANGLELoader");
            Method load = angleLoader.getMethod("postGlfwInit");
            load.invoke(angleLoader);
        } catch (ClassNotFoundException t) {
            return;
        } catch (Throwable t) {
            throw new GdxRuntimeException("Couldn't load ANGLE.", t);
        }
    }*/

    public VulkanApplication(ApplicationListener listener) {
        this(listener, new VulkanApplicationConfiguration());
    }

    public VulkanApplication(ApplicationListener listener, VulkanApplicationConfiguration config) {
        initializeGlfw();
        setApplicationLogger(new VulkanApplicationLogger());

        this.config = config = VulkanApplicationConfiguration.copy(config);
        if (config.title == null) config.title = listener.getClass().getSimpleName();

        Gdx.app = this;

        // Step 2: Initialize libGDX window with Vulkan handle injection
        //createLibGDXWindow(listener, config);

        // Step 3: Now set Gdx.app to this VulkanLwjgl3Application instance
        Gdx.app = this;
        if (!config.disableAudio) {
            try {
                this.audio = createAudio(config);
            } catch (Throwable t) {
                log("Lwjgl3Application", "Couldn't initialize audio, disabling audio", t);
                this.audio = new MockAudio();
            }
        } else {
            this.audio = new MockAudio();
        }
        Gdx.audio = audio;
        this.files = Gdx.files = createFiles();
        this.net = Gdx.net = new VulkanNet(config);
        this.clipboard = new VulkanClipboard();

        // Step 4: Call listener's create() method
        listener.create();

        VulkanWindow window = createWindow(config, listener, 0);
        windows.add(window);

        // Step 5: Start the rendering loop
        runMainLoop();

        /*if (config.glEmulation == GLEmulation.ANGLE_GLES20)
            loadANGLE();
        initializeGlfw();
        setApplicationLogger(new VulkanApplicationLogger());

        this.config = config = VulkanApplicationConfiguration.copy(config);
        if (config.title == null) config.title = listener.getClass().getSimpleName();

        Gdx.app = this;
        if (!config.disableAudio) {
            try {
                this.audio = createAudio(config);
            } catch (Throwable t) {
                log("VulkanApplication", "Couldn't initialize audio, disabling audio", t);
                this.audio = new MockAudio();
            }
        } else {
            this.audio = new MockAudio();
        }
        Gdx.audio = audio;
        this.files = Gdx.files = createFiles();
        this.net = Gdx.net = new VulkanNet(config);
        this.clipboard = new VulkanClipboard();

        this.sync = new Sync();

        VulkanWindow window = createWindow(config, listener, 0);
        if (config.glEmulation == GLEmulation.ANGLE_GLES20)
            postLoadANGLE();
        windows.add(window);
        try {
            loop();
            cleanupWindows();
        } catch (Throwable t) {
            if (t instanceof RuntimeException)
                throw (RuntimeException) t;
            else
                throw new GdxRuntimeException(t);
        } finally {
            cleanup();
        }*/
    }

    public void runMainLoop() {
        try {
            loop();
            cleanupWindows();
        } catch (Throwable t) {
            if (t instanceof RuntimeException)
                throw (RuntimeException) t;
            else
                throw new GdxRuntimeException(t);
        } finally {
            cleanup();
        }
    }

    public void loop() {
        Array<VulkanWindow> closedWindows = new Array<VulkanWindow>();
        while (running && windows.size > 0) {
            // FIXME put it on a separate thread
            audio.update();

            boolean haveWindowsRendered = false;
            closedWindows.clear();
            int targetFramerate = -2;
            for (VulkanWindow window : windows) {
                if (currentWindow != window) {
                    window.makeCurrent();
                    currentWindow = window;
                }
                if (targetFramerate == -2) targetFramerate = window.getConfig().foregroundFPS;
                synchronized (lifecycleListeners) {
                    haveWindowsRendered |= window.update();
                }
                if (window.shouldClose()) {
                    closedWindows.add(window);
                }
            }
            GLFW.glfwPollEvents();

            boolean shouldRequestRendering;
            synchronized (runnables) {
                shouldRequestRendering = runnables.size > 0;
                executedRunnables.clear();
                executedRunnables.addAll(runnables);
                runnables.clear();
            }
            for (Runnable runnable : executedRunnables) {
                runnable.run();
            }
            if (shouldRequestRendering) {
                // Must follow Runnables execution so changes done by Runnables are reflected
                // in the following render.
                for (VulkanWindow window : windows) {
                    if (!window.getGraphics().isContinuousRendering()) window.requestRendering();
                }
            }

            for (VulkanWindow closedWindow : closedWindows) {
                if (windows.size == 1) {
                    // Lifecycle listener methods have to be called before ApplicationListener methods. The
                    // application will be disposed when _all_ windows have been disposed, which is the case,
                    // when there is only 1 window left, which is in the process of being disposed.
                    for (int i = lifecycleListeners.size - 1; i >= 0; i--) {
                        LifecycleListener l = lifecycleListeners.get(i);
                        l.pause();
                        l.dispose();
                    }
                    lifecycleListeners.clear();
                }
                closedWindow.dispose();

                windows.removeValue(closedWindow, false);
            }

            if (!haveWindowsRendered) {
                // Sleep a few milliseconds in case no rendering was requested
                // with continuous rendering disabled.
                try {
                    Thread.sleep(1000 / config.idleFPS);
                } catch (InterruptedException e) {
                    // ignore
                }
            } else if (targetFramerate > 0) {
                //sync.sync(targetFramerate); // sleep as needed to meet the target framerate
            }
        }
        /*while (running && windows.size > 0) {
        //while (!glfwWindowShouldClose(window) && running) {
            glfwPollEvents();
            listener.render();
        }*/
        //dispose();
    }

    /*protected void loop() {
        Array<VulkanWindow> closedWindows = new Array<VulkanWindow>();
        while (running && windows.size > 0) {
            // FIXME put it on a separate thread
            audio.update();

            boolean haveWindowsRendered = false;
            closedWindows.clear();
            int targetFramerate = -2;
            for (VulkanWindow window : windows) {
                if (currentWindow != window) {
                    window.makeCurrent();
                    currentWindow = window;
                }
                if (targetFramerate == -2) targetFramerate = window.getConfig().foregroundFPS;
                synchronized (lifecycleListeners) {
                    haveWindowsRendered |= window.update();
                }
                if (window.shouldClose()) {
                    closedWindows.add(window);
                }
            }
            GLFW.glfwPollEvents();

            boolean shouldRequestRendering;
            synchronized (runnables) {
                shouldRequestRendering = runnables.size > 0;
                executedRunnables.clear();
                executedRunnables.addAll(runnables);
                runnables.clear();
            }
            for (Runnable runnable : executedRunnables) {
                runnable.run();
            }
            if (shouldRequestRendering) {
                // Must follow Runnables execution so changes done by Runnables are reflected
                // in the following render.
                for (VulkanWindow window : windows) {
                    if (!window.getGraphics().isContinuousRendering()) window.requestRendering();
                }
            }

            for (VulkanWindow closedWindow : closedWindows) {
                if (windows.size == 1) {
                    // Lifecycle listener methods have to be called before ApplicationListener methods. The
                    // application will be disposed when _all_ windows have been disposed, which is the case,
                    // when there is only 1 window left, which is in the process of being disposed.
                    for (int i = lifecycleListeners.size - 1; i >= 0; i--) {
                        LifecycleListener l = lifecycleListeners.get(i);
                        l.pause();
                        l.dispose();
                    }
                    lifecycleListeners.clear();
                }
                closedWindow.dispose();

                windows.removeValue(closedWindow, false);
            }

            if (!haveWindowsRendered) {
                // Sleep a few milliseconds in case no rendering was requested
                // with continuous rendering disabled.
                try {
                    Thread.sleep(1000 / config.idleFPS);
                } catch (InterruptedException e) {
                    // ignore
                }
            } else if (targetFramerate > 0) {
                sync.sync(targetFramerate); // sleep as needed to meet the target framerate
            }
        }
    }*/

    protected void cleanupWindows() {
        synchronized (lifecycleListeners) {
            for (LifecycleListener lifecycleListener : lifecycleListeners) {
                lifecycleListener.pause();
                lifecycleListener.dispose();
            }
        }
        for (VulkanWindow window : windows) {
            window.dispose();
        }
        windows.clear();
    }

    protected void cleanup() {
        VulkanCursor.disposeSystemCursors();
        audio.dispose();
        errorCallback.free();
        errorCallback = null;
        if (glDebugCallback != null) {
            glDebugCallback.free();
            glDebugCallback = null;
        }
        if (window != 0) {
            glfwDestroyWindow(window);
        }
        GLFW.glfwTerminate();
    }

    @Override
    public ApplicationListener getApplicationListener() {
        return currentWindow.getListener();
    }

    @Override
    public Graphics getGraphics() {
        return currentWindow.getGraphics();
    }

    @Override
    public Audio getAudio() {
        return audio;
    }

    @Override
    public Input getInput() {
        return currentWindow.getInput();
    }

    @Override
    public Files getFiles() {
        return files;
    }

    @Override
    public Net getNet() {
        return net;
    }

    @Override
    public void debug(String tag, String message) {
        if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message);
    }

    @Override
    public void debug(String tag, String message, Throwable exception) {
        if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message, exception);
    }

    @Override
    public void log(String tag, String message) {
        if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message);
    }

    @Override
    public void log(String tag, String message, Throwable exception) {
        if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message, exception);
    }

    @Override
    public void error(String tag, String message) {
        if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message);
    }

    @Override
    public void error(String tag, String message, Throwable exception) {
        if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message, exception);
    }

    @Override
    public void setLogLevel(int logLevel) {
        this.logLevel = logLevel;
    }

    @Override
    public int getLogLevel() {
        return logLevel;
    }

    @Override
    public void setApplicationLogger(ApplicationLogger applicationLogger) {
        this.applicationLogger = applicationLogger;
    }

    @Override
    public ApplicationLogger getApplicationLogger() {
        return applicationLogger;
    }

    @Override
    public ApplicationType getType() {
        return ApplicationType.Desktop;
    }

    @Override
    public int getVersion() {
        return 0;
    }

    @Override
    public long getJavaHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    @Override
    public long getNativeHeap() {
        return getJavaHeap();
    }

    @Override
    public Preferences getPreferences(String name) {
        if (preferences.containsKey(name)) {
            return preferences.get(name);
        } else {
            Preferences prefs = new VulkanPreferences(
                    new VulkanFileHandle(new File(config.preferencesDirectory, name), config.preferencesFileType));
            preferences.put(name, prefs);
            return prefs;
        }
    }

    @Override
    public Clipboard getClipboard() {
        return clipboard;
    }

    @Override
    public void postRunnable(Runnable runnable) {
        synchronized (runnables) {
            runnables.add(runnable);
        }
    }

    @Override
    public void exit() {
        running = false;
    }

    @Override
    public void addLifecycleListener(LifecycleListener listener) {
        synchronized (lifecycleListeners) {
            lifecycleListeners.add(listener);
        }
    }

    @Override
    public void removeLifecycleListener(LifecycleListener listener) {
        synchronized (lifecycleListeners) {
            lifecycleListeners.removeValue(listener, true);
        }
    }

    @Override
    public VulkanAudio createAudio(VulkanApplicationConfiguration config) {
        return new OpenALLwjgl3Audio(config.audioDeviceSimultaneousSources, config.audioDeviceBufferCount,
                config.audioDeviceBufferSize);
    }

    @Override
    public VulkanInput createInput(VulkanWindow window) {
        return new DefaultVulkanInput(window);
    }

    protected Files createFiles() {
        return new VulkanFiles();
    }

    /** Creates a new {@link VulkanWindow} using the provided listener and {@link VulkanWindowConfiguration}.
     *
     * This function only just instantiates a {@link VulkanWindow} and returns immediately. The actual window creation is postponed
     * with {@link Application#postRunnable(Runnable)} until after all existing windows are updated. */
    public VulkanWindow newWindow(ApplicationListener listener, VulkanWindowConfiguration config) {
        VulkanApplicationConfiguration appConfig = VulkanApplicationConfiguration.copy(this.config);
        appConfig.setWindowConfiguration(config);
        if (appConfig.title == null) appConfig.title = listener.getClass().getSimpleName();
        return createWindow(appConfig, listener, windows.get(0).getWindowHandle());
    }

    private VulkanWindow createWindow(final VulkanApplicationConfiguration config, ApplicationListener listener, final long sharedContext) {
        final VulkanWindow window = new VulkanWindow(listener, lifecycleListeners, config, this);
        if (sharedContext == 0) {
            // the main window is created immediately
            createWindow(window, config, sharedContext);
        } else {
            // creation of additional windows is deferred to avoid GL context trouble
            postRunnable(new Runnable() {
                public void run() {
                    createWindow(window, config, sharedContext);
                    windows.add(window);
                }
            });
        }
        return window;
    }

    void createWindow(VulkanWindow window, VulkanApplicationConfiguration config, long sharedContext) {
        long windowHandle = createGlfwWindow(config, sharedContext);
        window.create(windowHandle);
        window.setVisible(config.initialVisible);

        for (int i = 0; i < 2; i++) {
            window.getGraphics().gl20.glClearColor(config.initialBackgroundColor.r, config.initialBackgroundColor.g,
                    config.initialBackgroundColor.b, config.initialBackgroundColor.a);
            window.getGraphics().gl20.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GLFW.glfwSwapBuffers(windowHandle);
        }

        if (currentWindow != null) {
            // the call above to createGlfwWindow switches the OpenGL context to the newly created window,
            // ensure that the invariant "currentWindow is the window with the current active OpenGL context" holds
            currentWindow.makeCurrent();
        }
    }

    /*static long createGlfwWindow(VulkanApplicationConfiguration config, long sharedContextWindow) {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, config.windowResizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_MAXIMIZED, config.windowMaximized ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_AUTO_ICONIFY, config.autoIconify ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);

        GLFW.glfwWindowHint(GLFW.GLFW_RED_BITS, config.r);
        GLFW.glfwWindowHint(GLFW.GLFW_GREEN_BITS, config.g);
        GLFW.glfwWindowHint(GLFW.GLFW_BLUE_BITS, config.b);
        GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, config.a);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, config.stencil);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, config.depth);
        GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, config.samples);

        if (config.glEmulation == GLEmulation.GL30
                || config.glEmulation == GLEmulation.GL31
                || config.glEmulation == GLEmulation.GL32) {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, config.gles30ContextMajorVersion);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, config.gles30ContextMinorVersion);
            if (SharedLibraryLoader.os == Os.MacOsX) {
                // hints mandatory on OS X for GL 3.2+ context creation, but fail on Windows if the
                // WGL_ARB_create_context extension is not available
                // see: http://www.glfw.org/docs/latest/compat.html
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            }
        } else {
            if (config.glEmulation == GLEmulation.ANGLE_GLES20) {
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API);
                GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_ES_API);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 2);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 0);
            }
        }

        if (config.transparentFramebuffer) {
            GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);
        }

        if (config.debug) {
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, GLFW.GLFW_TRUE);
        }

        long windowHandle = 0;

        if (config.fullscreenMode != null) {
            GLFW.glfwWindowHint(GLFW.GLFW_REFRESH_RATE, config.fullscreenMode.refreshRate);
            windowHandle = GLFW.glfwCreateWindow(config.fullscreenMode.width, config.fullscreenMode.height, config.title,
                    config.fullscreenMode.getMonitor(), sharedContextWindow);
        } else {
            GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, config.windowDecorated ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
            windowHandle = GLFW.glfwCreateWindow(config.windowWidth, config.windowHeight, config.title, 0, sharedContextWindow);
        }
        if (windowHandle == 0) {
            throw new GdxRuntimeException("Couldn't create window");
        }
        VulkanWindow.setSizeLimits(windowHandle, config.windowMinWidth, config.windowMinHeight, config.windowMaxWidth,
                config.windowMaxHeight);
        if (config.fullscreenMode == null) {
            if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND) {
                if (config.windowX == -1 && config.windowY == -1) { // i.e., center the window
                    int windowWidth = Math.max(config.windowWidth, config.windowMinWidth);
                    int windowHeight = Math.max(config.windowHeight, config.windowMinHeight);
                    if (config.windowMaxWidth > -1)
                        windowWidth = Math.min(windowWidth, config.windowMaxWidth);
                    if (config.windowMaxHeight > -1)
                        windowHeight = Math.min(windowHeight, config.windowMaxHeight);

                    long monitorHandle = GLFW.glfwGetPrimaryMonitor();
                    if (config.windowMaximized && config.maximizedMonitor != null) {
                        monitorHandle = config.maximizedMonitor.monitorHandle;
                    }

                    GridPoint2 newPos = VulkanApplicationConfiguration.calculateCenteredWindowPosition(
                            VulkanApplicationConfiguration.toVulkanMonitor(monitorHandle), windowWidth, windowHeight);
                    GLFW.glfwSetWindowPos(windowHandle, newPos.x, newPos.y);
                } else {
                    GLFW.glfwSetWindowPos(windowHandle, config.windowX, config.windowY);
                }
            }

            if (config.windowMaximized) {
                GLFW.glfwMaximizeWindow(windowHandle);
            }
        }
        if (config.windowIconPaths != null) {
            VulkanWindow.setIcon(windowHandle, config.windowIconPaths, config.windowIconFileType);
        }
        GLFW.glfwMakeContextCurrent(windowHandle);
        GLFW.glfwSwapInterval(config.vSyncEnabled ? 1 : 0);
        if (config.glEmulation == GLEmulation.ANGLE_GLES20) {
            try {
                Class gles = Class.forName("org.lwjgl.opengles.GLES");
                gles.getMethod("createCapabilities").invoke(gles);
            } catch (Throwable e) {
                throw new GdxRuntimeException("Couldn't initialize GLES", e);
            }
        } else {
            GL.createCapabilities();
        }

        //initiateGL(config.glEmulation == GLEmulation.ANGLE_GLES20);
        initVulkan();
        if (!glVersion.isVersionEqualToOrHigher(2, 0))
            throw new GdxRuntimeException("OpenGL 2.0 or higher with the FBO extension is required. OpenGL version: "
                    + glVersion.getVersionString() + "\n" + glVersion.getDebugVersionString());

        if (config.glEmulation != GLEmulation.ANGLE_GLES20 && !supportsFBO()) {
            throw new GdxRuntimeException("OpenGL 2.0 or higher with the FBO extension is required. OpenGL version: "
                    + glVersion.getVersionString() + ", FBO extension: false\n" + glVersion.getDebugVersionString());
        }

        if (config.debug) {
            if (config.glEmulation == GLEmulation.ANGLE_GLES20) {
                throw new IllegalStateException(
                        "ANGLE currently can't be used with with VulkanApplicationConfiguration#enableGLDebugOutput");
            }
            glDebugCallback = GLUtil.setupDebugMessageCallback(config.debugStream);
            setGLDebugMessageControl(GLDebugMessageSeverity.NOTIFICATION, false);
        }

        return windowHandle;
    }*/

    private long createGlfwWindow(VulkanApplicationConfiguration config, long sharedContextWindow) {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, config.windowResizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_MAXIMIZED, config.windowMaximized ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_AUTO_ICONIFY, config.autoIconify ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        if (!GLFW.glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        System.out.println("Creating Vulkan-compatible GLFW window...");
        long window = GLFW.glfwCreateWindow(config.windowWidth, config.windowHeight, "GdxVk", 0, 0);

        if (window == 0) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        System.out.println("Vulkan-compatible window created. Window handle: " + window);

        return window;
    }

    private void createLibGDXWindow(ApplicationListener listener, VulkanApplicationConfiguration config) {
        // Initialize the array to hold VulkanWindow instances
        windows.clear();

        // Create the VulkanWindow instance directly
        gdxWindow = new VulkanWindow(listener, lifecycleListeners, config, this);

        // Add the window to the internal windows list
        windows.add(gdxWindow);

        System.out.println("LibGDX Vulkan window successfully created.");
    }

    /*private void createLibGDXWindow(ApplicationListener listener, VulkanApplicationConfiguration config) {
        try {
            windows = new Array<>();

            Constructor<Lwjgl3Window> constructor = Lwjgl3Window.class.getDeclaredConstructor(
                    ApplicationListener.class,
                    Array.class,
                    Lwjgl3ApplicationConfiguration.class,
                    Lwjgl3ApplicationBase.class
            );

            constructor.setAccessible(true);

            // Create the Lwjgl3Window instance
            gdxWindow = constructor.newInstance(listener, windows, config.copyToOrig(), this);

            // Inject the Vulkan-created window handle into Lwjgl3Window
            Field[] fields = Lwjgl3Window.class.getDeclaredFields();
            boolean injected = false;

            for (Field field : fields) {
                if (field.getType() == long.class) {  // Identify the field holding the window handle
                    field.setAccessible(true);
                    field.set(gdxWindow, window);  // Inject the Vulkan-created window
                    System.out.println("Injected Vulkan window handle into Lwjgl3Window: " + window);
                    injected = true;
                    break;
                }
            }

            if (!injected) {
                throw new RuntimeException("Failed to find the GLFW window handle field in Lwjgl3Window.");
            }

            // Add this window to the internal windows list
            windows.add(gdxWindow);
            System.out.println("LibGDX window successfully injected with Vulkan window handle.");

        } catch (Exception e) {
            throw new RuntimeException("Failed to create Lwjgl3Window using Reflection", e);
        }
    }*/

    /*private static void initiateGL(boolean useGLES20) {
        if (!useGLES20) {
            String versionString = GL11.glGetString(GL11.GL_VERSION);
            String vendorString = GL11.glGetString(GL11.GL_VENDOR);
            String rendererString = GL11.glGetString(GL11.GL_RENDERER);
            glVersion = new GLVersion(Application.ApplicationType.Desktop, versionString, vendorString, rendererString);
        } else {
            try {
                Class gles = Class.forName("org.lwjgl.opengles.GLES20");
                Method getString = gles.getMethod("glGetString", int.class);
                String versionString = (String) getString.invoke(gles, GL11.GL_VERSION);
                String vendorString = (String) getString.invoke(gles, GL11.GL_VENDOR);
                String rendererString = (String) getString.invoke(gles, GL11.GL_RENDERER);
                glVersion = new GLVersion(Application.ApplicationType.Desktop, versionString, vendorString, rendererString);
            } catch (Throwable e) {
                throw new GdxRuntimeException("Couldn't get GLES version string.", e);
            }
        }
    }*/

    private static boolean supportsFBO() {
        // FBO is in core since OpenGL 3.0, see https://www.opengl.org/wiki/Framebuffer_Object
        return glVersion.isVersionEqualToOrHigher(3, 0) || GLFW.glfwExtensionSupported("GL_EXT_framebuffer_object")
                || GLFW.glfwExtensionSupported("GL_ARB_framebuffer_object");
    }

    public enum GLDebugMessageSeverity {
        HIGH(GL43.GL_DEBUG_SEVERITY_HIGH, KHRDebug.GL_DEBUG_SEVERITY_HIGH, ARBDebugOutput.GL_DEBUG_SEVERITY_HIGH_ARB,
                AMDDebugOutput.GL_DEBUG_SEVERITY_HIGH_AMD), MEDIUM(GL43.GL_DEBUG_SEVERITY_MEDIUM, KHRDebug.GL_DEBUG_SEVERITY_MEDIUM,
                ARBDebugOutput.GL_DEBUG_SEVERITY_MEDIUM_ARB, AMDDebugOutput.GL_DEBUG_SEVERITY_MEDIUM_AMD), LOW(
                GL43.GL_DEBUG_SEVERITY_LOW, KHRDebug.GL_DEBUG_SEVERITY_LOW, ARBDebugOutput.GL_DEBUG_SEVERITY_LOW_ARB,
                AMDDebugOutput.GL_DEBUG_SEVERITY_LOW_AMD), NOTIFICATION(GL43.GL_DEBUG_SEVERITY_NOTIFICATION,
                KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION, -1, -1);

        final int gl43, khr, arb, amd;

        GLDebugMessageSeverity(int gl43, int khr, int arb, int amd) {
            this.gl43 = gl43;
            this.khr = khr;
            this.arb = arb;
            this.amd = amd;
        }
    }

    /** Enables or disables GL debug messages for the specified severity level. Returns false if the severity level could not be
     * set (e.g. the NOTIFICATION level is not supported by the ARB and AMD extensions).
     *
     * See {@link VulkanApplicationConfiguration#enableGLDebugOutput(boolean, PrintStream)} */
    public static boolean setGLDebugMessageControl(GLDebugMessageSeverity severity, boolean enabled) {
        GLCapabilities caps = GL.getCapabilities();
        final int GL_DONT_CARE = 0x1100; // not defined anywhere yet

        if (caps.OpenGL43) {
            GL43.glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, severity.gl43, (IntBuffer) null, enabled);
            return true;
        }

        if (caps.GL_KHR_debug) {
            KHRDebug.glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, severity.khr, (IntBuffer) null, enabled);
            return true;
        }

        if (caps.GL_ARB_debug_output && severity.arb != -1) {
            ARBDebugOutput.glDebugMessageControlARB(GL_DONT_CARE, GL_DONT_CARE, severity.arb, (IntBuffer) null, enabled);
            return true;
        }

        if (caps.GL_AMD_debug_output && severity.amd != -1) {
            AMDDebugOutput.glDebugMessageEnableAMD(GL_DONT_CARE, severity.amd, (IntBuffer) null, enabled);
            return true;
        }

        return false;
    }

}
