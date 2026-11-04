package io.github.slebed.gdxvulkan.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backends.android.AndroidApplicationBase;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidApplicationLogger;
import com.badlogic.gdx.backends.android.AndroidAudio;
import com.badlogic.gdx.backends.android.AndroidClipboard;
import com.badlogic.gdx.backends.android.AndroidEventListener;
import com.badlogic.gdx.backends.android.AndroidFiles;
import com.badlogic.gdx.backends.android.AndroidInput;
import com.badlogic.gdx.backends.android.AndroidNet;
import com.badlogic.gdx.backends.android.AndroidPreferences;
import com.badlogic.gdx.backends.android.DefaultAndroidAudio;
import com.badlogic.gdx.backends.android.DefaultAndroidFiles;
import com.badlogic.gdx.backends.android.DefaultAndroidInput;
import com.badlogic.gdx.backends.android.DisabledAndroidAudio;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.SnapshotArray;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;

/**
 * An {@link Application} for Android that renders with Vulkan. Use it like {@code AndroidApplication}:
 *
 * <pre>
 * public class MyActivity extends VulkanAndroidApplication {
 *     protected void onCreate(Bundle b) { super.onCreate(b); initialize(new MyGame()); }
 * }
 * </pre>
 *
 * It reuses the stock {@code AndroidInput}, {@code AndroidAudio}, {@code AndroidFiles} and {@code AndroidNet}. Declare
 * {@code android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|keyboard|density"} on the activity so a
 * rotation arrives as a surface change and not as an activity restart.
 *
 * <p>The surface lifecycle and the renderer's lifetime are separate. When the surface is destroyed (the app is backgrounded, the screen is
 * locked) the swapchain and surface go; the device and every texture, buffer and framebuffer stay, so {@code ApplicationListener.resume()} has
 * nothing to reload. Not yet supported: the on-screen keyboard through {@code Gdx.input.setOnscreenKeyboardVisible} and native text input,
 * because stock {@code DefaultAndroidInput} casts the graphics object to its own GL class there.
 */
public class VulkanAndroidApplication extends Activity implements AndroidApplicationBase {
    protected VulkanAndroidGraphics graphics;
    protected DefaultAndroidInput input;
    protected AndroidAudio audio;
    protected AndroidFiles files;
    protected AndroidNet net;
    protected AndroidClipboard clipboard;
    protected ApplicationListener listener;
    public Handler handler;
    protected boolean firstResume = true;
    protected final Array<Runnable> runnables = new Array<Runnable>();
    protected final Array<Runnable> executedRunnables = new Array<Runnable>();
    protected final SnapshotArray<LifecycleListener> lifecycleListeners = new SnapshotArray<LifecycleListener>(LifecycleListener.class);
    private final Array<AndroidEventListener> androidEventListeners = new Array<AndroidEventListener>();
    protected int logLevel = LOG_INFO;
    protected ApplicationLogger applicationLogger;
    protected boolean useImmersiveMode;

    /** Call from {@code onCreate} with the application. Uses the defaults. */
    public void initialize(ApplicationListener listener) {
        initialize(listener, new AndroidApplicationConfiguration(), new GdxVkConfig(), true);
    }

    public void initialize(ApplicationListener listener, AndroidApplicationConfiguration config) {
        initialize(listener, config, new GdxVkConfig(), true);
    }

    /**
     * @param vkConfig Vulkan options ({@code forceTierB}, validation, device index)
     * @param vsync false asks for a present mode that does not wait for the display
     */
    public void initialize(ApplicationListener listener, AndroidApplicationConfiguration config, GdxVkConfig vkConfig, boolean vsync) {
        if (getVersion() < MINIMUM_SDK) throw new GdxRuntimeException("libGDX requires Android API Level " + MINIMUM_SDK + " or later.");
        config.nativeLoader.load();
        setApplicationLogger(new AndroidApplicationLogger());
        if (vkConfig.framePacing) GdxVkContext.setFramePacingActivity(this); // before the context exists: Swappy asks for a device extension
        graphics = new VulkanAndroidGraphics(this, listener, vkConfig, vsync);
        input = (DefaultAndroidInput)createInput(this, this, graphics.view, config);
        audio = createAudio(this, config);
        files = createFiles();
        net = new AndroidNet(this, config);
        this.listener = listener;
        this.handler = new Handler();
        this.useImmersiveMode = config.useImmersiveMode;
        this.clipboard = new AndroidClipboard(this);

        addLifecycleListener(new LifecycleListener() {
            @Override public void resume() {}
            @Override public void pause() { audio.pause(); }
            @Override public void dispose() { audio.dispose(); }
        });

        installGdx();

        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Exception ex) {
            log("VulkanAndroidApplication", "Content already displayed, cannot request FEATURE_NO_TITLE", ex);
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        setContentView(graphics.getView(), new FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        if (config.useWakelock) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        useImmersiveMode(useImmersiveMode);
        if (getResources().getConfiguration().keyboard != Configuration.KEYBOARD_NOKEYS) input.setKeyboardAvailable(true);
        graphics.start();
    }

    private void installGdx() {
        Gdx.app = this;
        Gdx.input = input;
        Gdx.audio = audio;
        Gdx.files = files;
        Gdx.graphics = graphics;
        Gdx.net = net;
    }

    @Override protected void onResume() {
        installGdx();
        input.onResume();
        if (!firstResume) {
            // The surface comes back through surfaceChanged; this only tells the application it is running again.
        } else {
            firstResume = false;
        }
        graphics.onResume();
        audio.resume();
        super.onResume();
    }

    @Override protected void onPause() {
        graphics.onPause();
        input.onPause();
        if (isFinishing()) graphics.destroy();
        super.onPause();
    }

    @Override protected void onDestroy() {
        GdxVkContext.setFramePacingActivity(null);
        if (!isFinishing()) graphics.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && graphics != null) graphics.trimMemory(); // RUNNING_MODERATE is too early to give things back
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (graphics != null) graphics.trimMemory();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        useImmersiveMode(useImmersiveMode);
    }

    @Override public void useImmersiveMode(boolean use) {
        if (!use) return;
        View view = getWindow().getDecorView();
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        input.setKeyboardAvailable(config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO);
    }

    @Override public ApplicationListener getApplicationListener() { return listener; }
    @Override public Audio getAudio() { return audio; }
    @Override public AndroidInput getInput() { return input; }
    @Override public Files getFiles() { return files; }
    @Override public Graphics getGraphics() { return graphics; }
    @Override public Net getNet() { return net; }
    @Override public ApplicationType getType() { return ApplicationType.Android; }
    @Override public int getVersion() { return Build.VERSION.SDK_INT; }
    @Override public long getJavaHeap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    @Override public long getNativeHeap() { return Debug.getNativeHeapAllocatedSize(); }

    @Override public Preferences getPreferences(String name) {
        return new AndroidPreferences(getSharedPreferences(name, Context.MODE_PRIVATE));
    }

    @Override public Clipboard getClipboard() { return clipboard; }

    @Override public void postRunnable(Runnable runnable) {
        synchronized (runnables) {
            runnables.add(runnable);
            Gdx.graphics.requestRendering();
        }
    }

    @Override public void exit() {
        handler.post(new Runnable() {
            @Override public void run() { VulkanAndroidApplication.this.finish(); }
        });
    }

    @Override public void debug(String tag, String message) { if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message); }
    @Override public void debug(String tag, String message, Throwable e) { if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message, e); }
    @Override public void log(String tag, String message) { if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message); }
    @Override public void log(String tag, String message, Throwable e) { if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message, e); }
    @Override public void error(String tag, String message) { if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message); }
    @Override public void error(String tag, String message, Throwable e) { if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message, e); }
    @Override public void setLogLevel(int logLevel) { this.logLevel = logLevel; }
    @Override public int getLogLevel() { return logLevel; }
    @Override public void setApplicationLogger(ApplicationLogger applicationLogger) { this.applicationLogger = applicationLogger; }
    @Override public ApplicationLogger getApplicationLogger() { return applicationLogger; }

    @Override public void addLifecycleListener(LifecycleListener listener) {
        synchronized (lifecycleListeners) {
            lifecycleListeners.add(listener);
        }
    }

    @Override public void removeLifecycleListener(LifecycleListener listener) {
        synchronized (lifecycleListeners) {
            lifecycleListeners.removeValue(listener, true);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        synchronized (androidEventListeners) {
            for (int i = 0; i < androidEventListeners.size; i++) androidEventListeners.get(i).onActivityResult(requestCode, resultCode, data);
        }
    }

    public void addAndroidEventListener(AndroidEventListener listener) {
        synchronized (androidEventListeners) {
            androidEventListeners.add(listener);
        }
    }

    public void removeAndroidEventListener(AndroidEventListener listener) {
        synchronized (androidEventListeners) {
            androidEventListeners.removeValue(listener, true);
        }
    }

    @Override public Context getContext() { return this; }
    @Override public Array<Runnable> getRunnables() { return runnables; }
    @Override public Array<Runnable> getExecutedRunnables() { return executedRunnables; }
    @Override public SnapshotArray<LifecycleListener> getLifecycleListeners() { return lifecycleListeners; }
    @Override public Window getApplicationWindow() { return getWindow(); }
    @Override public Handler getHandler() { return handler; }

    @Override public AndroidAudio createAudio(Context context, AndroidApplicationConfiguration config) {
        return config.disableAudio ? new DisabledAndroidAudio() : new DefaultAndroidAudio(context, config);
    }

    @Override public AndroidInput createInput(Application activity, Context context, Object view, AndroidApplicationConfiguration config) {
        return new DefaultAndroidInput(this, this, graphics.view, config);
    }

    protected AndroidFiles createFiles() {
        getFilesDir(); // workaround for Android bug #10515463
        return new DefaultAndroidFiles(getAssets(), this, true);
    }

    /** The Vulkan graphics, for diagnostics (recreation timing, the context). */
    public VulkanAndroidGraphics getVulkanGraphics() { return graphics; }
}
