package io.github.slebed.gdxvulkan.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.GdxVkShaders;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Phase 1a on-device check. All Vulkan work runs on one render thread. When the surface appears it runs {@link SelfTest}
 * (result in logcat, tag {@code GdxVkTest}), then spins the triangle. The context outlives the surface: on
 * {@code surfaceDestroyed} only the presenter (surface + swapchain) is destroyed.
 *
 * <pre>
 * adb shell am start -n io.github.slebed.gdxvulkan.testapp/.MainActivity --ez forceTierB true
 * adb logcat -s GdxVkTest
 * </pre>
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    static final String TAG = "GdxVkTest";

    private HandlerThread thread;
    private Handler render;
    private GdxVkContext context; // render thread only
    private GdxVkPresenter presenter; // render thread only
    private int pipeline;
    private final float[] push = new float[32];
    private long startNanos;
    private boolean forceTierB, validation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TestLog.init(new java.io.File(getFilesDir(), "gdxvk-test.log"));
        forceTierB = getIntent().getBooleanExtra("forceTierB", false);
        validation = getIntent().getBooleanExtra("validation", false);

        thread = new HandlerThread("gdxvk-render");
        thread.start();
        render = new Handler(thread.getLooper());

        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        setContentView(view);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {}

    @Override public void surfaceChanged(final SurfaceHolder holder, int format, final int width, final int height) {
        render.post(new Runnable() {
            @Override public void run() {
                try {
                    if (presenter == null) start(holder, width, height);
                    else presenter.resize(width, height);
                } catch (Throwable t) {
                    TestLog.e(TAG, "surfaceChanged failed", t);
                }
            }
        });
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        runAndWait(new Runnable() {
            @Override public void run() {
                if (presenter != null) {
                    presenter.close();
                    presenter = null;
                    TestLog.i(TAG, "presenter destroyed; context kept alive");
                }
            }
        });
    }

    @Override protected void onDestroy() {
        runAndWait(new Runnable() {
            @Override public void run() {
                if (presenter != null) presenter.close();
                presenter = null;
                if (context != null) context.close();
                context = null;
            }
        });
        thread.quitSafely();
        super.onDestroy();
    }

    private void start(SurfaceHolder holder, int width, int height) {
        if (context == null) {
            GdxVkConfig cfg = new GdxVkConfig();
            cfg.forceTierB = forceTierB;
            cfg.enableValidation = validation;
            context = GdxVkContext.create(cfg);
            TestLog.i(TAG, "device=" + context.caps().deviceName + " driver=" + context.caps().driverInfo + " tier=" + context.caps().tier
                + " forced=" + context.caps().tierForced + " validation=" + context.caps().validationEnabled);
        }
        presenter = context.createPresenterAndroid(holder.getSurface(), width, height, true);
        pipeline = presenter.createPipeline(GdxVkShaders.load("triangle.vert"), GdxVkShaders.load("triangle.frag"));
        TestLog.i(TAG, "swapchain " + presenter.width() + "x" + presenter.height() + " vkFormat=" + presenter.vkFormat() + " images="
            + presenter.imageCount());
        SelfTest.run(presenter, pipeline);
        TestLog.i(TAG, "validationMessages=" + context.validationMessageCount());
        startNanos = System.nanoTime();
        render.post(frame);
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            GdxVkPresenter p = presenter;
            if (p == null) return;
            try {
                double a = (System.nanoTime() - startNanos) / 1e9;
                float c = (float)Math.cos(a), s = (float)Math.sin(a), aspect = (float)p.width() / p.height();
                // Rotate about Z, then scale Y by width/height so the triangle stays undistorted on the tall surface.
                push[0] = c;  push[1] = s * aspect;  // column 0
                push[4] = -s; push[5] = c * aspect;  // column 1
                push[10] = 1f; push[15] = 1f;
                push[16] = 1f; push[17] = 0.5f; push[18] = 0f; push[19] = 1f;
                if (p.beginFrame(0.1f, 0.1f, 0.3f, 1f)) {
                    p.draw(pipeline, 3, push);
                    p.endFrame();
                    render.post(this);
                } else {
                    render.postDelayed(this, 16);
                }
            } catch (Throwable t) {
                TestLog.e(TAG, "frame failed; stopping the render loop", t);
            }
        }
    };

    private void runAndWait(Runnable r) {
        final CountDownLatch done = new CountDownLatch(1);
        final Runnable inner = r;
        render.post(new Runnable() {
            @Override public void run() {
                try {
                    inner.run();
                } catch (Throwable t) {
                    TestLog.e(TAG, "cleanup failed", t);
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            if (!done.await(5, TimeUnit.SECONDS)) TestLog.e(TAG, "render thread did not finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
