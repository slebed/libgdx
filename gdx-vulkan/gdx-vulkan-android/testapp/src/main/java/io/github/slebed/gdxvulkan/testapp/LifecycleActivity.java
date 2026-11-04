package io.github.slebed.gdxvulkan.testapp;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Debug;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.android.VulkanAndroidApplication;
import io.github.slebed.gdxvulkan.android.VulkanAndroidGraphics;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;

/**
 * Phase 4 on-device check: a real {@link VulkanAndroidApplication} drawing a four-quadrant texture through stock-API calls, put through
 * rotations and background/foreground cycles while the pixels are read back. The texture is created once in {@code create()} and never
 * again, so a lost or stale texture shows as a wrong colour.
 *
 * <pre>
 * adb shell am start -n io.github.slebed.gdxvulkan.testapp/.LifecycleActivity --ei cycles 50 [--ez forceTierB true]
 * adb shell run-as io.github.slebed.gdxvulkan.testapp cat files/gdxvk-test.log      # look for LIFECYCLE PASS
 * </pre>
 */
public class LifecycleActivity extends VulkanAndroidApplication {
    static final String TAG = "GdxVkLifecycle";

    private volatile boolean created;
    private volatile int resizes, pauses, resumes;
    private volatile int streamLoads;
    private Thread driver;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TestLog.init(new java.io.File(getFilesDir(), "gdxvk-test.log"));
        GdxVkConfig cfg = new GdxVkConfig();
        cfg.forceTierB = getIntent().getBooleanExtra("forceTierB", false);
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        config.disableAudio = true;
        initialize(new Listener(), config, cfg, getIntent().getBooleanExtra("vsync", true));
        // Logcat is restricted on the test phone: the application's own log lines go to the test log as well.
        setApplicationLogger(new com.badlogic.gdx.ApplicationLogger() {
            @Override public void log(String tag, String message) { TestLog.i(tag, message); }
            @Override public void log(String tag, String message, Throwable e) { TestLog.e(tag, message, e); }
            @Override public void error(String tag, String message) { TestLog.e(tag, message); }
            @Override public void error(String tag, String message, Throwable e) { TestLog.e(tag, message, e); }
            @Override public void debug(String tag, String message) { TestLog.i(tag, message); }
            @Override public void debug(String tag, String message, Throwable e) { TestLog.e(tag, message, e); }
        });
        final int cycles = getIntent().getIntExtra("cycles", 0);
        if (cycles > 0) {
            driver = new Thread(new Runnable() {
                @Override public void run() { drive(cycles); }
            }, "lifecycle-driver");
            driver.start();
        }
    }

    @Override protected void onDestroy() {
        if (driver != null) driver.interrupt();
        super.onDestroy();
    }

    private final class Listener extends ApplicationAdapter {
        private VulkanSpriteBatch batch;
        private Texture quads;
        private Texture dot;
        // Stress test 4 streams textures while the surface comes and goes: an AssetManager loads and unloads the bundled particle textures
        // for as long as the app runs, and each finished texture is drawn once in a strip that the colour checks do not sample.
        private com.badlogic.gdx.assets.AssetManager assets;
        private final String[] streamNames = {"data/particle.png", "data/particle-fire.png", "data/particle-star.png", "data/particle-cloud.png", "data/uiskin.png"};
        private boolean streaming;

        @Override public void create() {
            batch = new VulkanSpriteBatch(getVulkanGraphics().getFrame());
            Pixmap pm = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
            int[][] c = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 0}}; // top-left, top-right, bottom-left, bottom-right
            for (int q = 0; q < 4; q++) {
                pm.setColor(c[q][0] / 255f, c[q][1] / 255f, c[q][2] / 255f, 1);
                pm.fillRectangle((q % 2) * 2, (q / 2) * 2, 2, 2);
            }
            quads = new Texture(pm);
            quads.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            pm.dispose();
            Pixmap w = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
            w.setColor(1, 1, 1, 1);
            w.fill();
            dot = new Texture(w);
            w.dispose();
            if (getIntent().getBooleanExtra("stream", true)) {
                assets = new com.badlogic.gdx.assets.AssetManager();
                streaming = true;
            }
            created = true;
            TestLog.i(TAG, "create()");
        }

        @Override public void resize(int width, int height) {
            resizes++;
            TestLog.i(TAG, "resize " + width + "x" + height + " preRotation=" + getVulkanGraphics().getPresenter().rotation() * 90 + " deg");
        }

        @Override public void pause() { pauses++; TestLog.i(TAG, "pause()"); }
        @Override public void resume() { resumes++; TestLog.i(TAG, "resume()"); }

        @Override public void render() {
            if (streaming && (streamRound == 0 && assets.getQueuedAssets() == 0 || reloadRequested)) {
                // Unloading disposes the textures, so it happens before this frame draws any (never between draw and flush).
                reloadRequested = false;
                if (streamRound > 0) streamLoads += streamNames.length;
                for (String n : streamNames) {
                    if (assets.isLoaded(n)) assets.unload(n);
                }
                for (String n : streamNames) assets.load(n, Texture.class);
            }
            Gdx.gl.glClearColor(0, 0, 0, 1);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            final int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
            batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            batch.begin();
            batch.draw(quads, 0, 0, w, h);
            // A moving marker so the picture is visibly alive; it sits in the corner of the screen where the checks do not look.
            float t = (System.nanoTime() / 1_000_000L % 2000) / 2000f;
            batch.setColor(1, 1, 1, 1);
            batch.draw(dot, t * (w - 24), h - 24, 24, 24);
            if (streaming) stream(w, h);
            batch.end();
        }

        private int streamRound;
        private boolean reloadRequested;

        /** Keeps one load/unload cycle of the bundled textures going, a few milliseconds of update() per frame. */
        private void stream(int w, int h) {
            if (assets.update(2)) {
                for (int i = 0; i < streamNames.length; i++) {
                    if (assets.isLoaded(streamNames[i])) {
                        Texture t = assets.get(streamNames[i], Texture.class);
                        if (t.getWidth() <= 0 || t.getHeight() <= 0) TestLog.e(TAG, "streamed texture " + streamNames[i] + " has no size");
                        batch.draw(t, w / 2f - 160 + i * 64, 8, 56, 56);
                    }
                }
                if (++streamRound % 90 == 0) reloadRequested = true; // about every 1.5 s at 60 fps; done at the start of the next frame
            }
        }

        @Override public void dispose() {
            if (assets != null) assets.dispose();
            batch.dispose();
            quads.dispose();
            dot.dispose();
        }
    }

    // ---- the driver: runs on its own thread, never on the UI or render thread

    private int[] pixel(byte[] px, int w, int x, int y) {
        int o = (y * w + x) * 4;
        return new int[] {px[o] & 255, px[o + 1] & 255, px[o + 2] & 255};
    }

    private boolean near(int[] got, int r, int g, int b) {
        return Math.abs(got[0] - r) <= 8 && Math.abs(got[1] - g) <= 8 && Math.abs(got[2] - b) <= 8;
    }

    /** Reads a frame back and checks the quadrant colours. Returns an empty string when they are right. */
    private String check() {
        VulkanAndroidGraphics g = getVulkanGraphics();
        byte[] px = g.captureFrame(3000);
        if (px == null) return "no frame within 3 s";
        final int w = g.getWidth(), h = g.getHeight();
        if (px.length != w * h * 4) return "frame is " + px.length / 4 + " pixels, expected " + w + "x" + h;
        int[][] expect = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 0}};
        int[][] at = {{w / 4, h / 4}, {3 * w / 4, h / 4}, {w / 4, 3 * h / 4}, {3 * w / 4, 3 * h / 4}};
        for (int q = 0; q < 4; q++) {
            int[] got = pixel(px, w, at[q][0], at[q][1]);
            if (!near(got, expect[q][0], expect[q][1], expect[q][2])) {
                return "quadrant " + q + " is (" + got[0] + "," + got[1] + "," + got[2] + "), expected (" + expect[q][0] + "," + expect[q][1] + ","
                    + expect[q][2] + ") at " + w + "x" + h;
            }
        }
        return "";
    }

    private boolean waitFor(long millis, java.util.concurrent.Callable<Boolean> cond) throws Exception {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < end) {
            if (cond.call()) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private void drive(int cycles) {
        final VulkanAndroidGraphics g = getVulkanGraphics();
        int failures = 0;
        long worstRecreate = 0, totalRecreate = 0;
        long heapStart = 0, heapEnd = 0;
        try {
            if (!waitFor(15000, new java.util.concurrent.Callable<Boolean>() {
                @Override public Boolean call() { return created && g.getPresenter() != null; }
            })) throw new IllegalStateException("the application never started");
            String first = check();
            if (!first.isEmpty()) throw new IllegalStateException("first frame wrong: " + first);
            Thread.sleep(500);
            heapStart = Debug.getNativeHeapAllocatedSize();
            TestLog.i(TAG, "LIFECYCLE start: " + cycles + " cycles, device=" + g.getContext().caps().deviceName + " tier=" + g.getContext().caps().tier);
            for (int i = 0; i < cycles; i++) {
                final boolean landscape = (i % 2) == 0;
                final int wBefore = g.getWidth();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    }
                });
                boolean turned = waitFor(4000, new java.util.concurrent.Callable<Boolean>() {
                    @Override public Boolean call() { return landscape ? g.getWidth() > g.getHeight() : g.getWidth() < g.getHeight(); }
                });
                if (!turned) {
                    TestLog.e(TAG, "cycle " + i + ": the surface did not change to " + (landscape ? "landscape" : "portrait") + " (was width " + wBefore + ")");
                    failures++;
                }
                Thread.sleep(150);
                String r = check();
                if (!r.isEmpty()) {
                    TestLog.e(TAG, "cycle " + i + " after rotation: " + r);
                    failures++;
                }
                // Background and foreground: an opaque activity on top pauses this one and destroys its surface.
                final int before = g.getRecreateCount();
                startActivity(new Intent(LifecycleActivity.this, CoverActivity.class));
                if (!waitFor(5000, new java.util.concurrent.Callable<Boolean>() {
                    @Override public Boolean call() { return g.getPresenter() == null; }
                })) {
                    TestLog.e(TAG, "cycle " + i + ": the surface was not destroyed when covered");
                    failures++;
                }
                if (!waitFor(8000, new java.util.concurrent.Callable<Boolean>() {
                    @Override public Boolean call() { return g.getPresenter() != null && g.getRecreateCount() > before; }
                })) {
                    TestLog.e(TAG, "cycle " + i + ": the surface did not come back");
                    failures++;
                    continue;
                }
                long ms = g.getLastRecreateNanos();
                worstRecreate = Math.max(worstRecreate, ms);
                totalRecreate += ms;
                Thread.sleep(150);
                r = check();
                if (!r.isEmpty()) {
                    TestLog.e(TAG, "cycle " + i + " after returning: " + r);
                    failures++;
                }
                if ((i + 1) % 25 == 0) {
                    TestLog.i(TAG, "cycle " + (i + 1) + "/" + cycles + " failures=" + failures + " worstRecreate=" + worstRecreate / 1_000_000L + " ms nativeHeap="
                        + Debug.getNativeHeapAllocatedSize() / 1024 + " KB");
                }
            }
            heapEnd = Debug.getNativeHeapAllocatedSize();
        } catch (InterruptedException ie) {
            return;
        } catch (Throwable t) {
            TestLog.e(TAG, "LIFECYCLE FAIL", t);
            return;
        }
        long avg = totalRecreate / Math.max(1, cycles);
        TestLog.i(TAG, "LIFECYCLE " + (failures == 0 ? "PASS" : "FAIL") + ": " + cycles + " cycles, failures=" + failures + ", surface recreation avg "
            + avg / 1_000_000L + " ms worst " + worstRecreate / 1_000_000L + " ms, native heap " + heapStart / 1024 + " -> " + heapEnd / 1024 + " KB, resizes="
            + resizes + " pauses=" + pauses + " resumes=" + resumes + " streamedTextures=" + streamLoads);
    }
}
