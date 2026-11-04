package io.github.slebed.gdxvulkan.testapp;

import android.os.Debug;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Application.ApplicationType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;

import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.VulkanCaps;
import io.github.slebed.gdxvulkan.VulkanShaderPipelineBundle;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;
import io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatchInstanced;
import io.github.slebed.gdxvulkan.desktop.Gate3Scenes;
import io.github.slebed.gdxvulkan.desktop.Gate5Scenes;
import io.github.slebed.gdxvulkan.g3d.VulkanComputeShader;
import io.github.slebed.gdxvulkan.g3d.VulkanGpuInstances;
import io.github.slebed.gdxvulkan.desktop.ShapeApi;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * On-device run of the Phase 1b and Phase 2 checks: the desktop SpriteBatchTest / TextureShimTest scenes (pixel exact where the desktop is),
 * compressed ETC2 and ASTC textures (the formats desktop GPUs cannot sample), a texture-churn soak and the Stress 2 sprite workload
 * (without the stock-GL half, which needs a GL context). Phase 1b's machinery (staging ring, deferred deletion, frame ring, caches,
 * reflection) is exercised underneath all of it. Logs {@code DEVICESUITE PASS|FAIL} and one line per check.
 */
final class DeviceSuite {
    private static final String TAG = MainActivity.TAG;
    private static final int S = 64; // scene size; the batch projection maps it onto the whole surface

    private final GdxVkContext ctx;
    private final GdxVkPresenter presenter;
    private final VulkanGL20 gl;
    private final GdxVkFrame frame;
    private final int W, H;
    private final boolean tierA;
    private int passed, failed, skipped;

    private final android.content.Context app;
    private static volatile int gfxW, gfxH; // what the stock classes see as the back buffer

    private DeviceSuite(android.content.Context app, GdxVkContext ctx, GdxVkPresenter presenter) {
        this.app = app;
        this.ctx = ctx;
        this.presenter = presenter;
        tierA = ctx.caps().tier == VulkanCaps.Tier.A;
        System.loadLibrary("gdx"); // gdx2d, for Pixmap
        gfxW = presenter.width();
        gfxH = presenter.height();
        gl = installGdx(app, ctx);
        W = presenter.width();
        H = presenter.height();
        frame = new GdxVkFrame(ctx, presenter, gl);
    }

    /** When set (intent extra {@code only}, comma separated), only checks whose name contains one of the words run: for measuring one thing without the rest. */
    static String[] only;

    static void run(android.content.Context app, GdxVkContext ctx, GdxVkPresenter presenter, int soakSeconds, int stressSeconds) {
        DeviceSuite s = new DeviceSuite(app, ctx, presenter);
        TestLog.i(TAG, "DEVICESUITE tier " + ctx.caps().tier + " on " + ctx.caps().deviceName);
        s.check("texturedQuadIsUprightAndExact", s::texturedQuad);
        s.check("tintAndBlending", s::tintAndBlending);
        s.check("textureChangesAndDrawCalls", s::textureChanges);
        s.check("moreSpritesThanTheBatchHoldsFlushesInOrder", s::overflow);
        s.check("scissorAndClearAreOrdered", s::scissorAndClear);
        s.check("aTextureChangedMidBatch", s::textureChangedMidBatch);
        s.check("shaderBundleGrayscale", s::shaderBundle);
        s.check("compressedEtc2", s::compressedEtc2);
        s.check("compressedAstc", s::compressedAstc);
        s.check("compressedFormatQuery", s::formatQuery);
        s.check("mipmapsAndFiltering", s::mipmaps);
        s.check("instancedSpritesComputeAndDraw", s::instanced);
        s.check("frameBufferColor", s::frameBufferColor);
        s.check("frameBufferDepthAndStencil", s::frameBufferDepthAndStencil);
        s.check("multiTargetFrameBuffer", s::multiTargetFrameBuffer);
        s.check("gpuTimestamps", s::gpuTimestamps);
        s.check("shaderCompileTime", s::shaderCompileTime);
        s.check("gate3Scenes", s::gate3);
        s.check("stockClassesOnTheShim", s::stockClasses);
        s.check("gate3StockClassesOnTheShim", s::gate3Stock);
        s.check("gate5Scenes", s::gate5);
        s.check("gate5StockModelBatchOnTheShim", s::gate5Stock);
        s.check("gpuInstancesAgainstACpuReference", s::gpuInstancesCorrectness);
        s.check("stress3Recording", s::stress3);
        s.check("soakTextureChurn", () -> s.soak(soakSeconds));
        s.check("stress2Single", () -> s.stress(1, stressSeconds));
        s.check("stress2Textures128", () -> s.stress(128, stressSeconds));
        s.check("shaderProgramsAgainstBuiltIn", () -> s.shaderPrograms(stressSeconds));
        s.check("stockSpriteBatchAgainstGenerated", () -> s.stockSpriteBatch(stressSeconds));
        s.check("instancedMillionSprites", () -> s.instancedMillion(stressSeconds));
        s.check("stress5Instances50k", () -> s.stress5(50_000, 64, stressSeconds));
        s.check("stress5Instances250k", () -> s.stress5(250_000, 500, stressSeconds));
        TestLog.i(TAG, "validationMessages=" + ctx.validationMessageCount());
        TestLog.i(TAG, (s.failed == 0 ? "DEVICESUITE PASS " : "DEVICESUITE FAIL ") + s.passed + " passed, " + s.failed + " failed, "
            + s.skipped + " skipped");
    }

    // ----------------------------------------------------------------------------------------------------------- Phase 5 (3D)

    /** Gate 5: stock models through VulkanModelBatch, each scene against its lavapipe golden (and the normal-map scene's own check). */
    private void gate5() throws Exception {
        final int size = Gate5Scenes.SIZE;
        com.badlogic.gdx.files.FileHandle data = Gdx.files.internal("data");
        presenter.setDepth(GdxVkPresenter.DEPTH);
        final java.util.List<String> failures = new java.util.ArrayList<String>();
        try {
            for (final Gate5Scenes.Scene scene : Gate5Scenes.all()) {
                if (!tierA && !scene.runsOnTierB()) {
                    TestLog.i(TAG, scene.name() + ": needs a second texture per draw, Tier A only");
                    continue;
                }
                try { // one scene failing must not hide how the others do
                scene.prepare(data);
                gfxW = gfxH = size;
                Gate5Scenes.ModelApi api = null;
                try {
                    float[] bg = Gate5Scenes.BACKGROUND;
                    beginFrame(bg[0], bg[1], bg[2]);
                    Gdx.gl.glViewport(0, 0, size, size);
                    api = scene.name().equals("gate5_fog") ? Gate5Scenes.vulkanInstancing(frame) : Gate5Scenes.vulkan(frame);
                    scene.draw(api, size, size);
                    if (scene.name().equals("gate5_fog")) require(api.instancedDraws() > 0, "the grid of identical boxes became instanced draws");
                    api.dispose();
                    api = null;
                    byte[] px = frame.endAndCapture();
                    final byte[] corner = new byte[size * size * 4]; // the scene was drawn into the bottom-left corner
                    for (int y = 0; y < size; y++) System.arraycopy(px, ((H - size + y) * W) * 4, corner, y * size * 4, size * 4);
                    scene.verify(corner, size, size);
                    compareWithGolden(scene.name(), px, size, Math.round(bg[0] * 255), Math.round(bg[1] * 255), Math.round(bg[2] * 255), 0.02);
                } finally {
                    if (api != null) api.dispose();
                    gfxW = W;
                    gfxH = H;
                    Gdx.gl.glViewport(0, 0, W, H);
                    scene.dispose();
                }
                TestLog.i(TAG, "gate5 scene ok: " + scene.name());
                } catch (Throwable t) {
                    closeOpenFrame();
                    failures.add(scene.name() + ": " + t);
                    TestLog.e(TAG, "gate5 scene failed: " + scene.name(), t);
                }
            }
        } finally {
            presenter.setDepth(GdxVkPresenter.DEPTH_NONE);
            closeOpenFrame(); // a scene that failed mid-frame left it open; do not let begin() replace the real error
            beginFrame(0, 0, 0);
            frame.end(); // the swapchain loses its depth buffer at the next frame
        }
        require(failures.isEmpty(), "gate5 scenes failed: " + failures);
    }

    /** The GPU's frustum cull and light clustering against the same computed on the CPU (the desktop GpuInstancesTest, on the phone's driver). */
    private void gpuInstancesCorrectness() throws Exception {
        presenter.setDepth(GdxVkPresenter.DEPTH);
        final int grid = 24;
        com.badlogic.gdx.graphics.g3d.Model model = new com.badlogic.gdx.graphics.g3d.utils.ModelBuilder().createBox(1f, 1f, 1f,
            new com.badlogic.gdx.graphics.g3d.Material(), com.badlogic.gdx.graphics.VertexAttributes.Usage.Position | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);
        com.badlogic.gdx.graphics.PerspectiveCamera cam = new com.badlogic.gdx.graphics.PerspectiveCamera(30f, 256, 256);
        cam.position.set(0f, 0f, 28f);
        cam.lookAt(0f, 0f, 0f);
        cam.near = 1f;
        cam.far = 80f;
        cam.update();
        VulkanGpuInstances gpu = new VulkanGpuInstances(frame, model.meshes.first(), null, grid * grid, 6, 0);
        try {
            float[] d = new float[grid * grid * VulkanGpuInstances.INSTANCE_FLOATS];
            for (int y = 0; y < grid; y++) {
                for (int x = 0; x < grid; x++) {
                    final int i = (y * grid + x) * VulkanGpuInstances.INSTANCE_FLOATS;
                    d[i] = (x - grid / 2f + 0.5f) * 1.6f;
                    d[i + 1] = (y - grid / 2f + 0.5f) * 1.6f;
                    d[i + 3] = 0.8f;
                    d[i + 7] = 1f;
                }
            }
            gpu.setInstances(d, grid * grid);
            final float[][] spec = {{-6f, 3f, 4f, 5f}, {5f, -2f, 8f, 4f}, {0f, 0f, 2f, 3f}, {9f, 6f, -3f, 6f}, {-3f, -7f, 10f, 7f}, {2f, 4f, 20f, 9f}};
            float[] lights = new float[6 * VulkanGpuInstances.LIGHT_FLOATS];
            for (int l = 0; l < 6; l++) {
                System.arraycopy(spec[l], 0, lights, l * VulkanGpuInstances.LIGHT_FLOATS, 4);
                lights[l * VulkanGpuInstances.LIGHT_FLOATS + 4] = 1f;
                lights[l * VulkanGpuInstances.LIGHT_FLOATS + 7] = 2f;
            }
            gpu.setLights(lights, 6);
            beginFrame(0.05f, 0.06f, 0.1f);
            Gdx.gl.glClearColor(0.05f, 0.06f, 0.1f, 1f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            gpu.render(cam, W, H);
            frame.endAndCapture();

            int expected = 0;
            final float radius = (float)Math.sqrt(3) * 0.5f * 0.8f;
            final java.util.HashSet<Integer> want = new java.util.HashSet<Integer>();
            for (int i = 0; i < grid * grid; i++) {
                final int b = i * VulkanGpuInstances.INSTANCE_FLOATS;
                if (cam.frustum.sphereInFrustum(d[b], d[b + 1], d[b + 2], radius)) {
                    want.add(i);
                    expected++;
                }
            }
            require(expected > 50 && expected < grid * grid, "the camera sees part of the grid: " + expected);
            final int visible = gpu.readVisibleCount();
            require(visible == expected, "GPU frustum cull kept " + visible + ", the CPU's keeps " + expected);
            for (int idx : gpu.readVisible(visible)) require(want.remove(idx), "instance " + idx + " should not be visible");
            require(want.isEmpty(), want.size() + " visible instances are missing");

            int nonEmpty = 0, mismatches = 0;
            final int cx = VulkanGpuInstances.CLUSTERS_X, cy = VulkanGpuInstances.CLUSTERS_Y, cz = VulkanGpuInstances.CLUSTERS_Z;
            final float p00 = cam.projection.val[Matrix4.M00], p11 = cam.projection.val[Matrix4.M11];
            final com.badlogic.gdx.math.Vector3[] view = new com.badlogic.gdx.math.Vector3[6];
            for (int l = 0; l < 6; l++) view[l] = new com.badlogic.gdx.math.Vector3(spec[l][0], spec[l][1], spec[l][2]).mul(cam.view);
            for (int z = 0; z < cz; z++) {
                for (int y = 0; y < cy; y++) {
                    for (int x = 0; x < cx; x++) {
                        final double d0 = cam.near * Math.pow(cam.far / cam.near, z / (double)cz), d1 = cam.near * Math.pow(cam.far / cam.near, (z + 1) / (double)cz);
                        final double nx0 = -1 + 2.0 * x / cx, nx1 = -1 + 2.0 * (x + 1) / cx, ny1 = 1 - 2.0 * y / cy, ny0 = 1 - 2.0 * (y + 1) / cy;
                        double loX = 1e30, loY = 1e30, loZ = 1e30, hiX = -1e30, hiY = -1e30, hiZ = -1e30;
                        for (int k = 0; k < 8; k++) {
                            final double dd = (k & 4) != 0 ? d1 : d0;
                            final double px = ((k & 1) != 0 ? nx1 : nx0) * dd / p00, py = ((k & 2) != 0 ? ny1 : ny0) * dd / p11, pz = -dd;
                            loX = Math.min(loX, px); loY = Math.min(loY, py); loZ = Math.min(loZ, pz);
                            hiX = Math.max(hiX, px); hiY = Math.max(hiY, py); hiZ = Math.max(hiZ, pz);
                        }
                        final java.util.ArrayList<Integer> w = new java.util.ArrayList<Integer>();
                        for (int l = 0; l < 6; l++) {
                            final double qx = Math.min(Math.max(view[l].x, loX), hiX), qy = Math.min(Math.max(view[l].y, loY), hiY), qz = Math.min(Math.max(view[l].z, loZ), hiZ);
                            final double dx = view[l].x - qx, dy = view[l].y - qy, dz = view[l].z - qz;
                            if (dx * dx + dy * dy + dz * dz <= (double)spec[l][3] * spec[l][3]) w.add(l);
                        }
                        final int[] got = gpu.readCluster(x + cx * (y + cy * z));
                        if (!w.isEmpty()) nonEmpty++;
                        if (got.length != w.size()) mismatches++;
                        else for (int i = 0; i < got.length; i++) if (got[i] != w.get(i)) mismatches++;
                    }
                }
            }
            require(nonEmpty > 50, "the lights reach clusters: " + nonEmpty);
            require(mismatches <= 2, "cluster lists differing from the CPU's: " + mismatches);
            TestLog.i(TAG, "gpu cull kept " + visible + " of " + grid * grid + "; " + nonEmpty + " clusters hold lights, " + mismatches + " mismatches");
        } finally {
            gpu.dispose();
            model.dispose();
            presenter.setDepth(GdxVkPresenter.DEPTH_NONE);
            beginFrame(0, 0, 0);
            frame.end();
        }
    }

    /** Stress test 5 on the phone: instances moved by a compute boids simulation, culled and lit on the GPU, for {@code seconds}. */
    private void stress5(int instances, int lights, int seconds) throws Exception {
        presenter.setDepth(GdxVkPresenter.DEPTH);
        final float half = 100f;
        final int gridSide = 32, gridUints = gridSide * gridSide * gridSide * 8;
        com.badlogic.gdx.graphics.g3d.utils.ModelBuilder mb = new com.badlogic.gdx.graphics.g3d.utils.ModelBuilder();
        mb.begin();
        com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder part = mb.part("cone", GL20.GL_TRIANGLES,
            com.badlogic.gdx.graphics.VertexAttributes.Usage.Position | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal, new com.badlogic.gdx.graphics.g3d.Material());
        part.setVertexTransform(new Matrix4().setToRotation(com.badlogic.gdx.math.Vector3.X, 90f));
        part.cone(0.8f, 2.4f, 0.8f, 8);
        com.badlogic.gdx.graphics.g3d.Model model = mb.end();
        VulkanGpuInstances gpu = new VulkanGpuInstances(frame, model.meshes.first(), null, instances, lights, gridUints);
        try {
            Random rnd = new Random(42);
            float[] d = new float[instances * VulkanGpuInstances.INSTANCE_FLOATS];
            for (int i = 0; i < instances; i++) {
                final int b = i * VulkanGpuInstances.INSTANCE_FLOATS;
                float x, y, z;
                do {
                    x = rnd.nextFloat() * 2 - 1;
                    y = rnd.nextFloat() * 2 - 1;
                    z = rnd.nextFloat() * 2 - 1;
                } while (x * x + y * y + z * z > 1);
                d[b] = x * 0.7f * half;
                d[b + 1] = y * 0.7f * half;
                d[b + 2] = z * 0.7f * half;
                d[b + 3] = 0.6f;
                d[b + 7] = 1f;
                d[b + 8] = (rnd.nextFloat() - 0.5f) * 24f;
                d[b + 9] = (rnd.nextFloat() - 0.5f) * 24f;
                d[b + 10] = (rnd.nextFloat() - 0.5f) * 24f;
            }
            gpu.setInstances(d, instances);
            float[] l = new float[lights * VulkanGpuInstances.LIGHT_FLOATS];
            for (int i = 0; i < lights; i++) {
                final int b = i * VulkanGpuInstances.LIGHT_FLOATS;
                l[b] = (rnd.nextFloat() * 2 - 1) * half * 0.8f;
                l[b + 1] = (rnd.nextFloat() * 2 - 1) * half * 0.8f;
                l[b + 2] = (rnd.nextFloat() * 2 - 1) * half * 0.8f;
                l[b + 3] = 14f + rnd.nextFloat() * 10f;
                l[b + 4] = 0.5f + 0.5f * rnd.nextFloat();
                l[b + 5] = 0.5f + 0.5f * rnd.nextFloat();
                l[b + 6] = 0.5f + 0.5f * rnd.nextFloat();
                l[b + 7] = 2.5f;
            }
            gpu.setLights(l, lights);
            final VulkanComputeShader boids = new VulkanComputeShader(frame, "boids", "boids.comp");
            final float[][] push = new float[3][];
            for (int m = 0; m < 3; m++) {
                push[m] = VulkanComputeShader.ints(VulkanComputeShader.newPush(), m, instances, gpu.instanceOffsetUints(), gpu.userOffsetUints());
                push[m][24] = 1f / 60f;
                push[m][25] = half;
                push[m][27] = 14f;
                push[m][28] = 0.6f;
                push[m][29] = 1.2f;
                push[m][30] = 0.8f;
                push[m][31] = 40f;
            }
            final int clearGroups = VulkanComputeShader.groups(gridUints, 256), groups = VulkanComputeShader.groups(instances, 256);
            com.badlogic.gdx.graphics.PerspectiveCamera cam = new com.badlogic.gdx.graphics.PerspectiveCamera(60f, W, H);
            cam.near = 1f;
            cam.far = 600f;
            int frames = 0, total = 0;
            long start = 0;
            final long end = System.nanoTime() + (long)(seconds + 1) * 1_000_000_000L;
            while (System.nanoTime() < end) {
                final float t = total * 0.004f;
                cam.position.set((float)Math.cos(t) * half * 1.25f, half * 0.35f, (float)Math.sin(t) * half * 1.25f);
                cam.lookAt(0f, 0f, 0f);
                cam.update();
                beginFrame(0.02f, 0.03f, 0.06f);
                Gdx.gl.glViewport(0, 0, W, H);
                Gdx.gl.glClearColor(0.02f, 0.03f, 0.06f, 1f);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                boids.dispatch(clearGroups, push[0], gpu.buffer());
                boids.dispatch(groups, push[1], gpu.buffer());
                boids.dispatch(groups, push[2], gpu.buffer());
                gpu.render(cam, W, H);
                frame.end();
                total++;
                if (total == 30) start = System.nanoTime();
                else if (total > 30) frames++;
            }
            final double secs = (System.nanoTime() - start) / 1e9, fps = frames / secs;
            final int visible = gpu.readVisibleCount();
            TestLog.i(TAG, String.format("stress 5, %d instances and %d lights at %dx%d: %.1f fps (%d frames in %.1f s), %d instances visible in the last frame", instances, lights, W, H,
                fps, frames, secs, visible));
            require(visible > 0, "the cull kept something");
            // The plan's mobile target is 60 fps at 50,000 instances and 64 lights (the phone's display may cap it at its refresh rate).
            if (instances <= 50_000) require(fps >= 55, "fps " + fps + " is below the 60 fps target for " + instances + " instances");
        } finally {
            gpu.dispose();
            model.dispose();
            presenter.setDepth(GdxVkPresenter.DEPTH_NONE);
            beginFrame(0, 0, 0);
            frame.end();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- harness

    private interface Check {
        void run() throws Exception;
    }

    private static final class Skip extends RuntimeException {
        Skip(String why) {
            super(why);
        }
    }

    private void check(String name, Check c) {
        if (only != null) {
            boolean wanted = false;
            for (String w : only) wanted |= name.toLowerCase().contains(w.toLowerCase());
            if (!wanted) return;
        }
        try {
            c.run();
            passed++;
            TestLog.i(TAG, "PASS " + name);
        } catch (Skip s) {
            skipped++;
            TestLog.i(TAG, "SKIP " + name + ": " + s.getMessage());
        } catch (Throwable t) {
            failed++;
            TestLog.e(TAG, "FAIL " + name, t);
            closeOpenFrame(); // the next check starts with no frame open
        }
    }

    private void closeOpenFrame() {
        try {
            if (frame != null && frame.isOpen()) frame.end();
        } catch (Throwable ignored) {
        }
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private interface Scene {
        void draw(VulkanSpriteBatch batch);
    }

    private byte[] render(float[] clear, Scene scene) {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!frame.begin(clear[0], clear[1], clear[2], clear[3])) continue;
            VulkanSpriteBatch batch = new VulkanSpriteBatch(frame);
            batch.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
            scene.draw(batch);
            batch.dispose();
            return frame.endAndCapture();
        }
        throw new IllegalStateException("no frame could be started");
    }

    private static final float[] BLACK = {0, 0, 0, 1};

    private int[] px(byte[] f, int x, int yFromTop) {
        int i = ((int)((yFromTop + 0.5) * H / S) * W + (int)((x + 0.5) * W / S)) * 4;
        return new int[] {f[i] & 0xFF, f[i + 1] & 0xFF, f[i + 2] & 0xFF, f[i + 3] & 0xFF};
    }

    private void assertPx(byte[] f, int x, int yFromTop, int r, int g, int b, int tol, String what) {
        int[] c = px(f, x, yFromTop);
        require(Math.abs(c[0] - r) <= tol && Math.abs(c[1] - g) <= tol && Math.abs(c[2] - b) <= tol,
            what + ": pixel (" + x + "," + yFromTop + ") was " + java.util.Arrays.toString(c) + ", expected " + r + "," + g + "," + b);
    }

    private static Texture solid(float r, float g, float b, float a) {
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.setColor(r, g, b, a);
        pm.fill();
        Texture t = new Texture(pm);
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        pm.dispose();
        return t;
    }

    // --------------------------------------------------------------------------------------------------------------- scenes

    private void texturedQuad() {
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.drawPixel(0, 0, 0xFF0000FF);
        pm.drawPixel(1, 0, 0x00FF00FF);
        pm.drawPixel(0, 1, 0x0000FFFF);
        pm.drawPixel(1, 1, 0xFFFF00FF);
        Texture t = new Texture(pm);
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(t, 0, 0, S, S);
            b.end();
        });
        assertPx(f, 8, 8, 255, 0, 0, 1, "top-left");
        assertPx(f, 56, 8, 0, 255, 0, 1, "top-right");
        assertPx(f, 8, 56, 0, 0, 255, 1, "bottom-left");
        assertPx(f, 56, 56, 255, 255, 0, 1, "bottom-right");
        t.dispose();
        pm.dispose();
    }

    private void tintAndBlending() {
        Texture white = solid(1, 1, 1, 1);
        byte[] f = render(new float[] {1, 0, 0, 1}, b -> {
            b.begin();
            b.setColor(1, 1, 1, 0.5f);
            b.draw(white, 0, 0, 32, 64);
            b.setColor(0, 1, 0, 1);
            b.draw(white, 32, 0, 32, 64);
            b.end();
        });
        assertPx(f, 16, 32, 255, 128, 128, 3, "50% white over red");
        assertPx(f, 48, 32, 0, 255, 0, 1, "green tint");
        byte[] g = render(new float[] {1, 0, 0, 1}, b -> {
            b.begin();
            b.disableBlending();
            b.setColor(1, 1, 1, 0.5f);
            b.draw(white, 0, 0, 64, 64);
            b.end();
        });
        assertPx(g, 32, 32, 255, 255, 255, 1, "no blending");
        white.dispose();
    }

    private void textureChanges() {
        Texture r = solid(1, 0, 0, 1), g = solid(0, 1, 0, 1), bl = solid(0, 0, 1, 1);
        int[] calls = new int[1];
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(r, 0, 0, 20, 64);
            b.draw(g, 20, 0, 20, 64);
            b.draw(bl, 40, 0, 20, 64);
            b.draw(r, 60, 0, 4, 64);
            b.end();
            calls[0] = b.renderCalls;
        });
        assertPx(f, 10, 32, 255, 0, 0, 1, "first");
        assertPx(f, 30, 32, 0, 255, 0, 1, "second");
        assertPx(f, 50, 32, 0, 0, 255, 1, "third");
        assertPx(f, 62, 32, 255, 0, 0, 1, "first again");
        require(calls[0] == (tierA ? 1 : 4), "draw calls were " + calls[0] + " on Tier " + ctx.caps().tier);
        r.dispose();
        g.dispose();
        bl.dispose();
    }

    private void overflow() {
        Texture red = solid(1, 0, 0, 1), blue = solid(0, 0, 1, 1);
        int[] calls = new int[1];
        byte[] f = render(BLACK, b -> {
            b.begin();
            for (int i = 0; i < 2500; i++) b.draw(i % 2 == 0 ? red : blue, 0, 0, S, S);
            b.draw(red, 0, 0, 10, 10);
            b.end();
            calls[0] = b.renderCalls;
        });
        require(calls[0] >= 3, "the batch had to flush: " + calls[0]);
        assertPx(f, 5, 58, 255, 0, 0, 1, "last sprite on top");
        assertPx(f, 40, 20, 0, 0, 255, 1, "sprite 2499 covers the rest");
        red.dispose();
        blue.dispose();
    }

    private void scissorAndClear() {
        Texture red = solid(1, 0, 0, 1), blue = solid(0, 0, 1, 1);
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(red, 0, 0, S, S);
            b.flush();
            Gdx.gl.glClearColor(0, 1, 0, 1);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glScissor(0, 0, W / 2, H);
            b.draw(blue, 0, 0, S, S);
            b.flush();
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            b.end();
        });
        assertPx(f, 48, 32, 0, 255, 0, 1, "right half cleared green");
        assertPx(f, 16, 32, 0, 0, 255, 1, "left half blue inside the scissor");
        red.dispose();
        blue.dispose();
    }

    private void textureChangedMidBatch() {
        Pixmap base = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
        base.setColor(1, 0, 0, 1);
        base.fill();
        Texture t = new Texture(base);
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        Pixmap green = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
        green.setColor(0, 1, 0, 1);
        green.fill();
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(t, 0, 0, 32, 64);
            t.draw(green, 0, 0);
            b.draw(t, 32, 0, 32, 64);
            b.end();
        });
        assertPx(f, 48, 32, 0, 255, 0, 1, "after the change");
        if (tierA) assertPx(f, 16, 32, 255, 0, 0, 1, "before the change keeps old contents (copy-on-write)");
        else assertPx(f, 16, 32, 0, 255, 0, 1, "Tier B sees the latest contents, as GL does");
        t.dispose();
        base.dispose();
        green.dispose();
    }

    private void shaderBundle() {
        Texture orange = solid(1f, 0.5f, 0f, 1f);
        VulkanShaderPipelineBundle gray = VulkanShaderPipelineBundle.fromResources("gray", "sprite.vert", "sprite_gray.frag");
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(orange, 0, 32, 16, 16);
            b.setShaderBundle(gray);
            gray.custom0[0] = 1f;
            b.draw(orange, 16, 32, 16, 16);
            b.flush();
            gray.custom0[0] = 0f;
            b.draw(orange, 32, 32, 16, 16);
            b.setShaderBundle(null);
            b.draw(orange, 48, 32, 16, 16);
            b.end();
        });
        int lum = Math.round(0.299f * 255 + 0.587f * 128);
        assertPx(f, 8, 24, 255, 128, 0, 2, "default shader");
        assertPx(f, 24, 24, lum, lum, lum, 3, "grayscale bundle");
        assertPx(f, 40, 24, 255, 128, 0, 2, "bundle, effect off");
        assertPx(f, 56, 24, 255, 128, 0, 2, "default restored");
        orange.dispose();
    }

    // ------------------------------------------------------------------------------------------------------- compressed textures

    private static final int GL_ETC2_RGB = 0x9274, VK_ETC2_RGB = 147, GL_ASTC_4x4 = 0x93B0, VK_ASTC_4x4 = 157;

    /** A 4x4 compressed texture drawn over the whole scene; the centre pixel comes back. */
    private int[] drawCompressed(int glFormat, byte[] block) {
        Texture t = new Texture(1, 1, Pixmap.Format.RGBA8888);
        t.bind();
        ByteBuffer data = BufferUtils.newByteBuffer(block.length);
        data.put(block).flip();
        gl.glCompressedTexImage2D(GL20.GL_TEXTURE_2D, 0, glFormat, 4, 4, 0, block.length, data);
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(t, 0, 0, S, S);
            b.end();
        });
        t.dispose();
        return px(f, 32, 32);
    }

    private void compressedEtc2() {
        if (!ctx.resources().formatSupported(VK_ETC2_RGB)) throw new Skip("device cannot sample ETC2");
        // Differential block, base (31,0,0), every pixel index 0 = +2: red with 2 in the others.
        byte[] red = {(byte)0xF8, 0, 0, 0x02, 0, 0, 0, 0};
        int[] c = drawCompressed(GL_ETC2_RGB, red);
        require(c[0] >= 253 && c[1] <= 6 && c[2] <= 6, "ETC2 red block sampled as " + java.util.Arrays.toString(c));
        byte[] white = {(byte)0xF8, (byte)0xF8, (byte)0xF8, 0x02, 0, 0, 0, 0};
        c = drawCompressed(GL_ETC2_RGB, white);
        require(c[0] >= 253 && c[1] >= 253 && c[2] >= 253, "ETC2 white block sampled as " + java.util.Arrays.toString(c));
    }

    /** An ASTC void-extent block: one constant colour, 16-bit channels. */
    private static byte[] astcSolid(int r8, int g8, int b8, int a8) {
        byte[] blk = new byte[16];
        blk[0] = (byte)0xFC;
        blk[1] = (byte)0xFD;
        for (int i = 2; i < 8; i++) blk[i] = (byte)0xFF;
        int[] ch = {r8, g8, b8, a8};
        for (int i = 0; i < 4; i++) {
            int v = ch[i] * 257;
            blk[8 + i * 2] = (byte)v;
            blk[9 + i * 2] = (byte)(v >> 8);
        }
        return blk;
    }

    private void compressedAstc() {
        if (!ctx.resources().formatSupported(VK_ASTC_4x4)) throw new Skip("device cannot sample ASTC");
        int[] c = drawCompressed(GL_ASTC_4x4, astcSolid(0, 200, 100, 255));
        require(Math.abs(c[0]) <= 2 && Math.abs(c[1] - 200) <= 2 && Math.abs(c[2] - 100) <= 2, "ASTC block sampled as " + java.util.Arrays.toString(c));
        c = drawCompressed(GL_ASTC_4x4, astcSolid(255, 0, 255, 255));
        require(c[0] >= 253 && c[1] <= 2 && c[2] >= 253, "ASTC magenta block sampled as " + java.util.Arrays.toString(c));
    }

    private void formatQuery() {
        StringBuilder sb = new StringBuilder();
        sb.append("BC1=").append(ctx.resources().formatSupported(131)).append(" ETC2=").append(ctx.resources().formatSupported(VK_ETC2_RGB))
            .append(" ASTC4x4=").append(ctx.resources().formatSupported(VK_ASTC_4x4));
        TestLog.i(TAG, "formats " + sb);
        if (ctx.resources().formatSupported(131)) return;
        // A format the device cannot sample must be refused with a message, not crash.
        Texture t = new Texture(1, 1, Pixmap.Format.RGBA8888);
        t.bind();
        try {
            gl.glCompressedTexImage2D(GL20.GL_TEXTURE_2D, 0, 0x83F1, 4, 4, 0, 8, BufferUtils.newByteBuffer(8));
            throw new AssertionError("BC1 upload should have been refused");
        } catch (com.badlogic.gdx.utils.GdxRuntimeException e) {
            require(e.getMessage().contains("cannot sample"), e.getMessage());
        } finally {
            t.dispose();
        }
    }

    private void mipmaps() {
        // 8x8 texture, level 1 averages to the mean of a red/blue checker: sampled at LOD 1 through trilinear-style filtering.
        Pixmap pm = new Pixmap(8, 8, Pixmap.Format.RGBA8888);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) pm.drawPixel(x, y, ((x + y) & 1) == 0 ? 0xFF0000FF : 0x0000FFFF);
        }
        Texture t = new Texture(pm, true); // useMipMaps: generated on the GPU or the CPU depending on the device
        t.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(t, 0, 0, 2, 2); // 8 texels squeezed into 2 pixels of a 64-pixel scene: heavy minification
            b.end();
        });
        int[] c = px(f, 0, 63);
        require(c[0] > 40 && c[2] > 40, "minified checker should blend red and blue, got " + java.util.Arrays.toString(c));
        t.dispose();
        pm.dispose();
    }

    // ---------------------------------------------------------------------------------------------------------- instanced sprites

    private void instanced() {
        Texture t = solid(1, 1, 1, 1);
        VulkanSpriteBatchInstanced b = new VulkanSpriteBatchInstanced(frame, 8);
        b.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
        b.setBounds(S, S);
        b.setSpriteSize(8, 8);
        b.setTexture(t);
        b.setColor(0, 1, 0, 1);
        float[] s = new float[8 * 4];
        for (int i = 0; i < 8; i++) {
            s[i * 4] = 6 + i * 3f;
            s[i * 4 + 1] = 4 + i * 7f;
            s[i * 4 + 2] = (i % 2 == 0 ? 1 : -1) * (10 + i * 4f);
            s[i * 4 + 3] = (i % 3 == 0 ? 1 : -1) * 2f;
        }
        b.setSprites(s, 8);
        float[] ref = s.clone();
        final float dt = 0.25f, max = S - 8;
        byte[] f = null;
        for (int step = 0; step < 6; step++) {
            for (int attempt = 0; attempt < 100 && !frame.begin(0, 0, 0, 1); attempt++) { }
            b.update(dt);
            b.draw();
            f = frame.endAndCapture();
            for (int i = 0; i < 8; i++) {
                for (int axis = 0; axis < 2; axis++) {
                    int p = i * 4 + axis, v = p + 2;
                    ref[p] += ref[v] * dt;
                    if (ref[p] < 0) {
                        ref[p] = -ref[p];
                        ref[v] = Math.abs(ref[v]);
                    } else if (ref[p] > max) {
                        ref[p] = 2 * max - ref[p];
                        ref[v] = -Math.abs(ref[v]);
                    }
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            int[] c = px(f, (int)(ref[i * 4] + 4), S - 1 - (int)(ref[i * 4 + 1] + 4));
            require(c[0] <= 2 && c[1] >= 253 && c[2] <= 2, "instanced sprite " + i + " centre was " + java.util.Arrays.toString(c));
        }
        b.dispose();
        t.dispose();
    }

    private void instancedMillion(int seconds) {
        Texture t = solid(1, 1, 1, 1);
        final int n = 1_000_000;
        VulkanSpriteBatchInstanced b = new VulkanSpriteBatchInstanced(frame, n);
        b.setTexture(t);
        b.setSpriteSize(3, 3);
        b.setBounds(W, H);
        b.setColor(0.3f, 0.8f, 1f, 0.5f);
        b.getProjectionMatrix().setToOrtho2D(0, 0, W, H);
        float[] data = new float[n * 4];
        Random r = new Random(11);
        for (int i = 0; i < n; i++) {
            data[i * 4] = r.nextFloat() * (W - 3);
            data[i * 4 + 1] = r.nextFloat() * (H - 3);
            data[i * 4 + 2] = (r.nextFloat() - 0.5f) * 300f;
            data[i * 4 + 3] = (r.nextFloat() - 0.5f) * 300f;
        }
        b.setSprites(data, n);
        long warm = System.nanoTime() + 1_000_000_000L, end = 0, frames = 0, measure = 0;
        while (true) {
            if (!frame.begin(0.02f, 0.02f, 0.05f, 1f)) continue;
            b.update(1f / 60f);
            b.draw();
            frame.end();
            long now = System.nanoTime();
            if (measure == 0) {
                if (now >= warm) {
                    measure = now;
                    end = now + seconds * 1_000_000_000L;
                }
            } else {
                frames++;
                if (now >= end) break;
            }
        }
        double fps = frames / ((System.nanoTime() - measure) / 1e9);
        TestLog.i(TAG, String.format("instanced %d sprites at %dx%d: %.1f fps (Tier %s)", n, W, H, fps, ctx.caps().tier));
        b.dispose();
        t.dispose();
        require(fps > 5, "instanced million too slow: " + fps);
    }

    // ---------------------------------------------------------------------------------------------------------------- framebuffers

    private void beginFrame(float r, float g, float b) {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (frame.begin(r, g, b, 1)) return;
        }
        throw new IllegalStateException("no frame could be started");
    }

    private void fill(VulkanSpriteBatch b, Texture white, float z, float x, float y, float w, float h, float r, float g, float bl) {
        Matrix4 m = new Matrix4().setToOrtho2D(0, 0, S, S);
        m.val[Matrix4.M23] = z;
        b.setProjectionMatrix(m);
        b.begin();
        b.setColor(r, g, bl, 1);
        b.draw(white, x, y, w, h);
        b.end();
    }

    private byte[] showFlipped(VulkanSpriteBatch b, FrameBuffer fb) {
        TextureRegion region = new TextureRegion(fb.getColorBufferTexture());
        region.flip(false, true);
        b.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, S, S));
        b.begin();
        b.setColor(1, 1, 1, 1);
        b.draw(region, 0, 0, S, S);
        b.end();
        return frame.endAndCapture();
    }

    private void frameBufferColor() {
        Texture white = solid(1, 1, 1, 1);
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        beginFrame(0, 0, 1);
        VulkanSpriteBatch b = new VulkanSpriteBatch(frame);
        b.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
        fill(b, white, 0, 0, 0, 20, 64, 1, 0, 0); // on the swapchain, before the offscreen pass
        fb.begin();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        fill(b, white, 0, 0, 0, 16, 16, 0, 1, 0); // GL bottom-left of the framebuffer
        fb.end();
        fill(b, white, 0, 44, 0, 20, 64, 1, 1, 0); // on the swapchain, after it
        byte[] f = showFlipped(b, fb);
        // the framebuffer is drawn over the whole screen, flipped back: green is at the bottom-left; the strips are covered
        assertPx(f, 8, 56, 0, 255, 0, 1, "framebuffer corner, GL orientation");
        assertPx(f, 56, 8, 0, 0, 0, 1, "framebuffer is otherwise cleared");
        b.dispose();
        fb.dispose();
        white.dispose();
    }

    private void frameBufferDepthAndStencil() {
        Texture white = solid(1, 1, 1, 1);
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, true, true);
        beginFrame(0, 0, 0);
        VulkanSpriteBatch b = new VulkanSpriteBatch(frame);
        fb.begin();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClearDepthf(1f);
        Gdx.gl.glClearStencil(0);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT | GL20.GL_STENCIL_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        fill(b, white, -0.5f, 0, 0, 32, 64, 1, 0, 0); // near first, far over it: near stays
        fill(b, white, 0.5f, 0, 0, 32, 64, 0, 0, 1);
        fill(b, white, 0.5f, 32, 0, 32, 64, 0, 1, 0); // far first, near over it: near wins
        fill(b, white, -0.5f, 32, 0, 32, 64, 1, 1, 0);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_STENCIL_TEST); // stencil: mark a square, then draw only inside it
        Gdx.gl.glStencilFunc(GL20.GL_ALWAYS, 1, 0xFF);
        Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_REPLACE);
        fill(b, white, 0, 24, 24, 16, 16, 1, 0, 1);
        Gdx.gl.glStencilFunc(GL20.GL_EQUAL, 1, 0xFF);
        Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_KEEP);
        fill(b, white, 0, 0, 0, 64, 20, 1, 1, 1); // a white band along the bottom: only the part inside the marked square would show
        Gdx.gl.glDisable(GL20.GL_STENCIL_TEST);
        fb.end();
        byte[] f = showFlipped(b, fb);
        assertPx(f, 16, 12, 255, 0, 0, 1, "depth: near drawn first stays on top");
        assertPx(f, 48, 12, 255, 255, 0, 1, "depth: near drawn last wins");
        assertPx(f, 32, 32, 255, 0, 255, 1, "stencil: the marked square");
        assertPx(f, 8, 56, 255, 0, 0, 1, "stencil: the white band outside the marked square was rejected (the red from the depth part remains)");
        b.dispose();
        fb.dispose();
        white.dispose();
    }

    // ---------------------------------------------------------------------------------------------------------------------- Gate 3

    /**
     * The Gate 3 scenes of the desktop tests, unchanged, on the device: stock FrameBuffer, Scene2D with a skin from the APK, depth-tested boxes
     * and the four post-processing passes. The scenes believe the back buffer is 256x256 and draw into the swapchain's bottom-left 256x256
     * (a GL viewport is anchored there); that corner of the captured frame is compared with the lavapipe golden, with the desktop's GPU tolerance.
     */
    private void gate3() throws Exception {
        final int size = Gate3Scenes.SIZE;
        final Gate3Scenes.Fx fx = Gate3Scenes.vulkanFx();
        com.badlogic.gdx.files.FileHandle data = Gdx.files.internal("data");
        try {
            for (final Gate3Scenes.Scene scene : Gate3Scenes.all()) {
                scene.prepare(data, size, size);
                gfxW = gfxH = size;
                try {
                    float[] bg = Gate3Scenes.BACKGROUND;
                    beginFrame(bg[0], bg[1], bg[2]);
                    Gdx.gl.glViewport(0, 0, size, size);
                    final VulkanSpriteBatch batch = new VulkanSpriteBatch(frame);
                    batch.getProjectionMatrix().setToOrtho2D(0, 0, size, size);
                    scene.draw(new Gate3Scenes.Env() {
                        public com.badlogic.gdx.graphics.g2d.Batch batch() { return batch; }

                        public Gate3Scenes.Fx fx() { return fx; }

                        public ShapeApi shapes() { return ShapeApi.vulkan(frame); }
                    }, size, size);
                    batch.dispose();
                    byte[] px = frame.endAndCapture();
                    compareWithGolden(scene.name(), px, size);
                } finally {
                    gfxW = W;
                    gfxH = H;
                    Gdx.gl.glViewport(0, 0, W, H);
                    scene.dispose();
                }
            }
        } finally {
            fx.dispose();
        }
    }


    // ------------------------------------------------------------------------------------------------------- stock classes on the GL shim

    /**
     * How long a translated shader takes to become a program on this device: the first one (it starts the compiler), the next, and the same source again from the disk
     * cache. The times are logged; the check only requires that the programs build.
     */
    private void shaderCompileTime() {
        final java.io.File saved = VulkanShaderProgram.cacheDirectory;
        final java.io.File dir = new java.io.File(app.getCacheDir(), "gdxvk-compile-time-" + System.nanoTime());
        final String vert = "attribute vec4 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\nuniform mat4 u_projTrans;\n"
            + "varying vec4 v_color;\nvarying vec2 v_texCoords;\nvoid main() {\n   v_color = a_color;\n   v_texCoords = a_texCoord0;\n   gl_Position = u_projTrans * a_position;\n}\n";
        final String[] frag = new String[4];
        for (int k = 0; k < frag.length; k++) {
            frag[k] = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\nuniform sampler2D u_texture;\nuniform vec4 u_tint;\n"
                + "void main() {\n  gl_FragColor = v_color * texture2D(u_texture, v_texCoords) * u_tint * " + (k + 1) + ".0;\n}\n";
        }
        final double[] ms = new double[4];
        try {
            VulkanShaderProgram.setCacheDirectory(null); // no disk cache for the first two
            // A game does other work (assets, the first frames) before it creates its shaders; the compiler starts up in the background meanwhile (GlslCompiler.warmUp).
            try {
                Thread.sleep(Long.getLong("gdxvulkan.shaderCompileDelayMs", 2000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ms[0] = timeProgram(vert, frag[0]);
            ms[1] = timeProgram(vert, frag[1]);
            VulkanShaderProgram.setCacheDirectory(dir);
            ms[2] = timeProgram(vert, frag[2]); // not in the cache yet: built and saved
            ms[3] = timeProgram(vert, frag[2]); // the same source again: from the disk cache
        } finally {
            VulkanShaderProgram.setCacheDirectory(saved);
            final java.io.File[] files = dir.listFiles();
            if (files != null) for (java.io.File f : files) f.delete();
            dir.delete();
        }
        TestLog.i(TAG, String.format("shaderCompileTime: first program (2 s after start, the compiler warmed up in the background) %.1f ms, second %.1f ms, third (built and saved) %.1f ms, the same source from the disk cache %.1f ms",
            ms[0], ms[1], ms[2], ms[3]));
    }

    private double timeProgram(String vert, String frag) {
        final long t0 = System.nanoTime();
        final VulkanShaderProgram p = new VulkanShaderProgram(frame, vert, frag);
        final double elapsed = (System.nanoTime() - t0) / 1e6;
        require(p.isCompiled(), "the shader built: " + p.getLog());
        p.dispose();
        return elapsed;
    }

    /** The device's GPU timestamps work: frames that clear the screen report a plausible GPU time (a frame or two behind). */
    private void gpuTimestamps() {
        for (int i = 0; i < 30; i++) {
            beginFrame(0.1f, 0.2f, 0.3f);
            frame.end();
        }
        final long ns = presenter.gpuFrameNanos();
        TestLog.i(TAG, "gpuTimestamps: the GPU took " + ns / 1e6 + " ms for the last frame (an empty one: a clear and a present)");
        require(ns > 0, "the device reports GPU time (timestamps are supported on this queue)");
        require(ns < 500_000_000L, "an empty frame does not take half a second of GPU time: " + ns);
    }

    /** At most {@code fraction} of the pixels may differ by more than {@code tolerance} in a colour channel. */
    private static void requireSimilar(String what, byte[] expected, byte[] actual, int tolerance, double fraction) {
        require(expected.length == actual.length, what + ": picture sizes");
        int off = 0, worst = 0;
        for (int i = 0; i < expected.length; i += 4) {
            int d = 0;
            for (int c = 0; c < 3; c++) d = Math.max(d, Math.abs((expected[i + c] & 255) - (actual[i + c] & 255)));
            worst = Math.max(worst, d);
            if (d > tolerance) off++;
        }
        final double share = off / (double)(expected.length / 4);
        TestLog.i(TAG, what + ": " + off + " pixels differ by more than " + tolerance + " (" + share + "), worst " + worst);
        require(share <= fraction, what + ": " + off + " pixels (" + share + ") differ by more than " + tolerance + ", worst " + worst);
    }

    /** libGDX's own SpriteBatch, PolygonSpriteBatch and ShapeRenderer, unmodified, next to the Vulkan classes: the same picture, and no run-time compiler call. */
    private void stockClasses() throws Exception {
        final int hits = io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram.knownShaderHits();
        final int calls = io.github.slebed.gdxvulkan.GlslCompiler.callCount();
        final Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.drawPixel(0, 0, 0xFF0000FF);
        pm.drawPixel(1, 0, 0x00FF00FF);
        pm.drawPixel(0, 1, 0x0000FFFF);
        pm.drawPixel(1, 1, 0xFFFF00FF);
        final Texture quads = new Texture(pm);
        quads.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        pm.dispose();
        final com.badlogic.gdx.graphics.g2d.BitmapFont font = new com.badlogic.gdx.graphics.g2d.BitmapFont();
        try {
            final com.badlogic.gdx.graphics.g2d.Batch[] stock = {new com.badlogic.gdx.graphics.g2d.SpriteBatch(), new com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch()};
            require(io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram.knownShaderHits() - hits == 2, "stock batches take the dedicated shaders");
            final ShapeApi stockShapes = ShapeApi.stock();
            final com.badlogic.gdx.graphics.g2d.Batch[] generated = {new VulkanSpriteBatch(frame), new io.github.slebed.gdxvulkan.g2d.VulkanPolygonSpriteBatch(frame)};
            final ShapeApi generatedShapes = ShapeApi.vulkan(frame);
            require(io.github.slebed.gdxvulkan.GlslCompiler.callCount() == calls, "no run-time compiler call for the stock classes");
            for (int variant = 0; variant < 2; variant++) {
                beginFrame(0.1f, 0.1f, 0.1f);
                drawStockScene(stock[variant], stockShapes, quads, font);
                final byte[] a = frame.endAndCapture();
                beginFrame(0.1f, 0.1f, 0.1f);
                drawStockScene(generated[variant], generatedShapes, quads, font);
                final byte[] b = frame.endAndCapture();
                requireSimilar("stockClasses variant " + variant, b, a, 2, 0.0005);
            }
            for (com.badlogic.gdx.graphics.g2d.Batch b : stock) b.dispose();
            for (com.badlogic.gdx.graphics.g2d.Batch b : generated) b.dispose();
            stockShapes.dispose();
            generatedShapes.dispose();
        } finally {
            font.dispose();
            quads.dispose();
        }
    }

    private void drawStockScene(com.badlogic.gdx.graphics.g2d.Batch batch, ShapeApi shapes, Texture quads, com.badlogic.gdx.graphics.g2d.BitmapFont font) {
        batch.getProjectionMatrix().setToOrtho2D(0, 0, W, H);
        batch.begin();
        batch.draw(quads, W * 0.1f, H * 0.1f, W * 0.4f, W * 0.4f);
        font.draw(batch, "Vulkan", W * 0.55f, H * 0.5f);
        batch.end();
        shapes.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, W, H));
        shapes.begin(ShapeApi.Kind.Filled);
        shapes.setColor(1f, 0.5f, 0.25f, 1f);
        shapes.rect(W * 0.55f, H * 0.1f, W * 0.3f, W * 0.2f);
        shapes.end();
        shapes.begin(ShapeApi.Kind.Line);
        shapes.setColor(0f, 1f, 1f, 1f);
        shapes.line(5, 5, W - 5, H * 0.6f);
        shapes.end();
    }

    /** The Gate 3 scenes drawn by libGDX's own SpriteBatch, ShapeRenderer and GLSL effects on the shim, against the same scenes drawn by the Vulkan classes. */
    private void gate3Stock() throws Exception {
        final int size = Gate3Scenes.SIZE;
        final Gate3Scenes.Fx vulkanFx = Gate3Scenes.vulkanFx();
        final Gate3Scenes.Fx stockFx = Gate3Scenes.glFx();
        final com.badlogic.gdx.files.FileHandle data = Gdx.files.internal("data");
        try {
            for (final Gate3Scenes.Scene scene : Gate3Scenes.all()) {
                scene.prepare(data, size, size);
                gfxW = gfxH = size;
                try {
                    final float[] bg = Gate3Scenes.BACKGROUND;
                    beginFrame(bg[0], bg[1], bg[2]);
                    Gdx.gl.glViewport(0, 0, size, size);
                    final VulkanSpriteBatch vb = new VulkanSpriteBatch(frame);
                    vb.getProjectionMatrix().setToOrtho2D(0, 0, size, size);
                    scene.draw(new Gate3Scenes.Env() {
                        public com.badlogic.gdx.graphics.g2d.Batch batch() { return vb; }

                        public Gate3Scenes.Fx fx() { return vulkanFx; }

                        public ShapeApi shapes() { return ShapeApi.vulkan(frame); }
                    }, size, size);
                    vb.dispose();
                    final byte[] expected = frame.endAndCapture();

                    beginFrame(bg[0], bg[1], bg[2]);
                    Gdx.gl.glViewport(0, 0, size, size);
                    final com.badlogic.gdx.graphics.g2d.SpriteBatch sb = new com.badlogic.gdx.graphics.g2d.SpriteBatch();
                    sb.getProjectionMatrix().setToOrtho2D(0, 0, size, size);
                    scene.draw(new Gate3Scenes.Env() {
                        public com.badlogic.gdx.graphics.g2d.Batch batch() { return sb; }

                        public Gate3Scenes.Fx fx() { return stockFx; }

                        public ShapeApi shapes() { return ShapeApi.stock(); }
                    }, size, size);
                    sb.dispose();
                    final byte[] actual = frame.endAndCapture();
                    requireSimilar(scene.name() + " (stock classes)", expected, actual, 8, 0.01);
                } finally {
                    gfxW = W;
                    gfxH = H;
                    Gdx.gl.glViewport(0, 0, W, H);
                    scene.dispose();
                }
            }
        } finally {
            vulkanFx.dispose();
            stockFx.dispose();
        }
    }

    /** The Gate 5 scenes through libGDX's own ModelBatch, DefaultShader and DepthShader on the shim, against VulkanModelBatch. */
    private void gate5Stock() throws Exception {
        final int size = Gate5Scenes.SIZE;
        final com.badlogic.gdx.files.FileHandle data = Gdx.files.internal("data");
        presenter.setDepth(GdxVkPresenter.DEPTH);
        final java.util.List<String> failures = new java.util.ArrayList<String>();
        for (final Gate5Scenes.Scene scene : Gate5Scenes.all()) {
            if (!scene.hasStockReference()) continue;
            if (!tierA && !scene.runsOnTierB()) continue;
            try {
                scene.prepare(data);
                gfxW = gfxH = size;
                final float[] bg = Gate5Scenes.BACKGROUND;
                beginFrame(bg[0], bg[1], bg[2]);
                Gdx.gl.glViewport(0, 0, size, size);
                Gate5Scenes.ModelApi api = scene.name().equals("gate5_fog") ? Gate5Scenes.vulkanInstancing(frame) : Gate5Scenes.vulkan(frame);
                scene.draw(api, size, size);
                api.dispose();
                final byte[] expected = frame.endAndCapture();
                beginFrame(bg[0], bg[1], bg[2]);
                Gdx.gl.glViewport(0, 0, size, size);
                api = Gate5Scenes.stock();
                scene.draw(api, size, size);
                api.dispose();
                final byte[] actual = frame.endAndCapture();
                requireSimilar(scene.name() + " (stock ModelBatch)", expected, actual, 8, 0.01);
            } catch (Throwable t) {
                closeOpenFrame();
                failures.add(scene.name() + ": " + t);
                TestLog.i(TAG, scene.name() + " (stock ModelBatch) failed: " + t);
            } finally {
                gfxW = W;
                gfxH = H;
                Gdx.gl.glViewport(0, 0, W, H);
                scene.dispose();
            }
        }
        require(failures.isEmpty(), failures.toString());
    }

    /** Stress test 2 for the shim on the device: 20,000 sprites from one texture through libGDX's own SpriteBatch and through VulkanSpriteBatch, the order alternating. */
    private void stockSpriteBatch(int seconds) {
        final int sprites = 20_000, size = 24, each = Math.max(2, seconds / 2);
        final Pixmap pm = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
        pm.setColor(0.9f, 0.7f, 0.4f, 1f);
        pm.fill();
        final Texture texture = new Texture(pm);
        pm.dispose();
        final Random r = new Random(7);
        final float[] x = new float[sprites], y = new float[sprites];
        for (int i = 0; i < sprites; i++) {
            x[i] = r.nextFloat() * (W - size);
            y[i] = r.nextFloat() * (H - size);
        }
        final com.badlogic.gdx.graphics.g2d.Batch[] batches = {new VulkanSpriteBatch(frame, 8191), new com.badlogic.gdx.graphics.g2d.SpriteBatch(8191)};
        for (long settleEnd = System.nanoTime() + 6_000_000_000L; System.nanoTime() < settleEnd; ) { // phones need about six seconds to settle
            if (!frame.begin(0.1f, 0.1f, 0.1f, 1f)) continue;
            batches[0].getProjectionMatrix().setToOrtho2D(0, 0, W, H);
            batches[0].begin();
            for (int i = 0; i < sprites; i++) batches[0].draw(texture, x[i], y[i], size, size);
            batches[0].end();
            frame.end();
        }
        final double[] fps = new double[2];
        final int rounds = 4;
        for (int round = 0; round < rounds; round++) {
            for (int k = 0; k < 2; k++) {
                final int c = round % 2 == 0 ? k : 1 - k;
                final com.badlogic.gdx.graphics.g2d.Batch b = batches[c];
                long warmEnd = System.nanoTime() + 1_000_000_000L, end = 0, measureStart = 0;
                long frames = 0;
                while (true) {
                    if (!frame.begin(0.1f, 0.1f, 0.1f, 1f)) continue;
                    b.getProjectionMatrix().setToOrtho2D(0, 0, W, H);
                    b.begin();
                    for (int i = 0; i < sprites; i++) b.draw(texture, x[i], y[i], size, size);
                    b.end();
                    frame.end();
                    final long now = System.nanoTime();
                    if (measureStart == 0 && now >= warmEnd) {
                        measureStart = now;
                        end = now + each * 1_000_000_000L;
                    } else if (measureStart != 0) {
                        frames++;
                        if (now >= end) break;
                    }
                }
                fps[c] += frames / ((System.nanoTime() - measureStart) / 1e9) / rounds;
                if (round == rounds - 1) TestLog.i(TAG, (c == 0 ? "VulkanSpriteBatch" : "stock SpriteBatch") + ": GPU " + presenter.gpuFrameNanos() / 1e6 + " ms per frame");
            }
        }
        for (com.badlogic.gdx.graphics.g2d.Batch b : batches) b.dispose();
        texture.dispose();
        final double ratio = fps[1] / fps[0];
        TestLog.i(TAG, String.format("stockSpriteBatch: VulkanSpriteBatch %.1f fps, stock SpriteBatch on the shim %.1f fps (%.2fx)", fps[0], fps[1], ratio));
        require(ratio >= 1.0 / 1.5, "the stock SpriteBatch is within 1.5x of the generated one (" + ratio + ")");
    }

    private void compareWithGolden(String name, byte[] px, int size) throws java.io.IOException {
        compareWithGolden(name, px, size, 10, 10, 18, 0.25);
    }

    /**
     * The captured frame's bottom-left size x size pixels against {@code goldens/<name>.png} (a lavapipe render).
     * @param bgR the background colour (not counted as drawn), and the share of the picture that must be something else
     */
    private void compareWithGolden(String name, byte[] px, int size, int bgR, int bgG, int bgB, double minDrawn) throws java.io.IOException {
        android.graphics.Bitmap golden;
        try (java.io.InputStream in = app.getAssets().open("goldens/" + name + ".png")) {
            golden = android.graphics.BitmapFactory.decodeStream(in);
        }
        require(golden != null && golden.getWidth() == size && golden.getHeight() == size, name + ": golden missing or the wrong size");
        int[] g = new int[size * size];
        golden.getPixels(g, 0, size, 0, 0, size, size);
        int differing = 0, maxDelta = 0, drawn = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int i = ((H - size + y) * W + x) * 4; // GL's bottom-left corner: the last `size` rows of the capture
                int e = g[y * size + x];
                int d = Math.max(Math.max(Math.abs((px[i] & 0xFF) - ((e >> 16) & 0xFF)), Math.abs((px[i + 1] & 0xFF) - ((e >> 8) & 0xFF))),
                    Math.abs((px[i + 2] & 0xFF) - (e & 0xFF)));
                maxDelta = Math.max(maxDelta, d);
                if (d > 12) differing++; // the desktop's GPU-vs-lavapipe tolerance
                if (Math.abs((px[i] & 0xFF) - bgR) > 2 || Math.abs((px[i + 1] & 0xFF) - bgG) > 2 || Math.abs((px[i + 2] & 0xFF) - bgB) > 2) drawn++;
            }
        }
        double share = differing / (double)(size * size);
        TestLog.i(TAG, String.format("%s vs lavapipe golden: differing=%d (%.3f%%) maxDelta=%d, %d of %d pixels drawn", name, differing, share * 100,
            maxDelta, drawn, size * size));
        require(drawn > size * size * minDrawn, name + " is nearly blank");
        require(share <= 0.01, name + " differs from the golden: " + differing + " pixels (" + share * 100 + "%), max delta " + maxDelta);
    }

    // ------------------------------------------------------------------------------------------------------------ stress test 3

    /** Stress test 3 on the device: 100,000 draws recorded as a plain native loop (the floor) and through the Java command stream. */
    private void stress3() {
        final int draws = 100_000, frames = 15, warmup = 4;
        io.github.slebed.gdxvulkan.GdxVkPipelineDesc desc = new io.github.slebed.gdxvulkan.GdxVkPipelineDesc();
        desc.stride = 0;
        desc.cullMode = io.github.slebed.gdxvulkan.GdxVkPipelineDesc.CULL_BACK;
        final int pipeline = frame.pipeline("stress3", "triangle", "triangle.vert", "triangle.frag", desc);
        final int indexBuffer = frame.quadIndexBuffer();
        final float[] push = new float[32];
        push[0] = push[5] = push[10] = push[15] = 1f;
        final io.github.slebed.gdxvulkan.CommandStream cs = frame.stream();
        long[] floor = new long[frames], stream = new long[frames], encode = new long[frames];
        int fi = 0, si = 0;
        for (int n = 0; fi < frames || si < frames; n++) {
            if (!frame.begin(0, 0, 0, 1)) continue;
            if ((n & 1) == 0) {
                long ns = frame.presenter().benchmarkRecord(pipeline, indexBuffer, draws, push);
                if (n >= warmup * 2 && fi < frames) floor[fi++] = ns;
            } else {
                long t0 = System.nanoTime();
                cs.bindPipeline(pipeline);
                cs.bindIndexBuffer(indexBuffer, 0, io.github.slebed.gdxvulkan.CommandStream.INDEX_U16);
                cs.pushConstants(push);
                for (int i = 0; i < draws; i++) {
                    cs.cullMode((i & 1) != 0 ? 1 : 2);
                    push[16] = (float)i * 0.001f;
                    cs.pushRange(16, push, 16, 1);
                    cs.drawIndexed(3, 0, 0);
                }
                long t1 = System.nanoTime();
                frame.flush();
                long ns = System.nanoTime() - t0;
                if (n >= warmup * 2 && si < frames) {
                    encode[si] = t1 - t0;
                    stream[si++] = ns;
                }
            }
            frame.end();
        }
        java.util.Arrays.sort(floor);
        java.util.Arrays.sort(stream);
        java.util.Arrays.sort(encode);
        double ratio = stream[frames / 2] / (double)floor[frames / 2];
        TestLog.i(TAG, String.format("stress3 (Tier %s): C++ floor %.2f ms, Java + replay %.2f ms for %d draws: ratio %.2f (target <= 1.3)",
            ctx.caps().tier, floor[frames / 2] / 1e6, stream[frames / 2] / 1e6, draws, ratio));
        TestLog.i(TAG, String.format("stress3: Java encoding %.2f ms (%.0f ns per draw), native replay %.2f ms (%.0f ns per draw); floor %.0f ns per draw", encode[frames / 2] / 1e6,
            encode[frames / 2] / (double)draws, (stream[frames / 2] - encode[frames / 2]) / 1e6, (stream[frames / 2] - encode[frames / 2]) / (double)draws, floor[frames / 2] / (double)draws));
        require(ratio < 2.0, "recording through the command stream is far above the floor: " + ratio);
    }

    /** Three colour attachments (RGBA8, RGBA16F, RGBA32F) from one draw; the float ones keep a value above 1.0 that scaling by a quarter then recovers. */
    private void multiTargetFrameBuffer() {
        Texture white = solid(1, 1, 1, 1);
        io.github.slebed.gdxvulkan.glutils.VulkanMultiTargetFrameBuffer.ColorFormat[] fmts = {
            io.github.slebed.gdxvulkan.glutils.VulkanMultiTargetFrameBuffer.ColorFormat.RGBA8888,
            io.github.slebed.gdxvulkan.glutils.VulkanMultiTargetFrameBuffer.ColorFormat.RGBA16F,
            io.github.slebed.gdxvulkan.glutils.VulkanMultiTargetFrameBuffer.ColorFormat.RGBA32F};
        io.github.slebed.gdxvulkan.glutils.VulkanMultiTargetFrameBuffer mt = new io.github.slebed.gdxvulkan.glutils.VulkanMultiTargetFrameBuffer(S, S, true,
            false, fmts);
        VulkanShaderPipelineBundle mrt = VulkanShaderPipelineBundle.fromResources("mrt3", "sprite.vert", "sprite_mrt.frag").withColorOutputs(3);
        VulkanShaderPipelineBundle scale = VulkanShaderPipelineBundle.fromResources("scale", "sprite.vert", "fx_scale.frag");
        beginFrame(0, 0, 0);
        VulkanSpriteBatch b = new VulkanSpriteBatch(frame);
        b.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
        mt.begin();
        Gdx.gl.glClearColor(0.2f, 0.4f, 0.6f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        mrt.custom0[0] = 4f;
        b.setShaderBundle(mrt);
        b.begin();
        b.setColor(0.5f, 0.25f, 0.75f, 1);
        b.draw(white, 0, 0, 32, 64);
        b.end();
        b.setShaderBundle(null);
        mt.end();
        b.begin();
        b.setColor(1, 1, 1, 1);
        scale.custom0[0] = 0.25f;
        for (int i = 0; i < 3; i++) {
            TextureRegion r = new TextureRegion(mt.getColorBufferTexture(i), 0, 0, S / 2, S);
            r.flip(false, true);
            b.setShaderBundle(i == 0 ? null : scale);
            b.draw(r, i * 21, 0, i == 2 ? 22 : 21, 64);
        }
        b.setShaderBundle(null);
        b.end();
        byte[] f = frame.endAndCapture();
        assertPx(f, 10, 32, 128, 64, 191, 2, "RGBA8888 attachment");
        assertPx(f, 31, 32, 128, 64, 191, 2, "RGBA16F attachment keeps values above 1.0");
        assertPx(f, 52, 32, 128, 64, 191, 2, "RGBA32F attachment keeps values above 1.0");
        b.dispose();
        mt.dispose();
        white.dispose();
    }

    // -------------------------------------------------------------------------------------------------------------- soak / perf

    private void soak(int seconds) {
        System.gc();
        long nativeStart = Debug.getNativeHeapAllocatedSize();
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        Random rnd = new Random(3);
        Texture[] live = new Texture[8];
        long frames = 0, created = 0;
        long nativeMid = 0;
        while (System.nanoTime() < end) {
            if (!frame.begin(0.1f, 0.1f, 0.1f, 1f)) continue;
            VulkanSpriteBatch b = new VulkanSpriteBatch(frame);
            b.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
            b.begin();
            for (int k = 0; k < 4; k++) {
                int slot = rnd.nextInt(live.length);
                if (live[slot] != null) live[slot].dispose();
                Pixmap pm = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
                pm.setColor(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), 1f);
                pm.fill();
                live[slot] = new Texture(pm);
                pm.dispose();
                created++;
            }
            for (int i = 0; i < 200; i++) {
                Texture t = live[rnd.nextInt(live.length)];
                if (t != null) b.draw(t, rnd.nextInt(S - 4), rnd.nextInt(S - 4), 4, 4);
            }
            b.end();
            b.dispose();
            frame.end();
            frames++;
            if (nativeMid == 0 && frames == 300) nativeMid = Debug.getNativeHeapAllocatedSize();
        }
        for (Texture t : live) if (t != null) t.dispose();
        long nativeEnd = Debug.getNativeHeapAllocatedSize();
        TestLog.i(TAG, "soak " + seconds + "s: " + frames + " frames (" + (frames / Math.max(1, seconds)) + " fps), " + created
            + " textures, native heap KB start/frame300/end " + nativeStart / 1024 + "/" + nativeMid / 1024 + "/" + nativeEnd / 1024);
        require(frames > 0, "no frames rendered");
        // Flat after warm-up: allow 8 MB of drift for allocator noise, anything more is a leak.
        require(nativeEnd - nativeMid < 8L << 20, "native heap grew " + (nativeEnd - nativeMid) / 1024 + " KB after warm-up");
    }

    private void stress(int textures, int seconds) {
        final int sprites = 20_000, size = 24;
        Texture[] tex = new Texture[textures];
        for (int i = 0; i < textures; i++) {
            Pixmap pm = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
            pm.setColor(0.2f + ((i * 37) & 127) / 255f, 0.2f + ((i * 91) & 127) / 255f, 0.2f + ((i * 53) & 127) / 255f, 1f); // never black
            pm.fill();
            tex[i] = new Texture(pm);
            pm.dispose();
        }
        Random r = new Random(7);
        int[] ti = new int[sprites];
        float[] x = new float[sprites], y = new float[sprites];
        for (int i = 0; i < sprites; i++) {
            ti[i] = r.nextInt(textures);
            x[i] = r.nextFloat() * (W - size);
            y[i] = r.nextFloat() * (H - size);
        }
        VulkanSpriteBatch b = new VulkanSpriteBatch(frame, 8191);
        // A phone needs about six seconds to settle (the JIT compiles the draw path, the GPU clock rises): measure after that, not during it.
        long warmEnd = System.nanoTime() + 6_000_000_000L, end = 0;
        long frames = 0, measureStart = 0, lastReport = System.nanoTime(), lastFrames = 0;
        int second = 0;
        while (true) {
            if (!frame.begin(0.1f, 0.1f, 0.1f, 1f)) continue;
            b.getProjectionMatrix().setToOrtho2D(0, 0, W, H);
            b.begin();
            for (int i = 0; i < sprites; i++) b.draw(tex[ti[i]], x[i], y[i], size, size);
            b.end();
            frame.end();
            long now = System.nanoTime();
            if (measureStart == 0) {
                if (now >= warmEnd) {
                    measureStart = now;
                    end = now + seconds * 1_000_000_000L;
                }
            } else {
                frames++;
                if (now - lastReport >= 1_000_000_000L) { // per second, to see whether the speed changes during the run
                    TestLog.i(TAG, String.format("stress2 %d textures, second %d: %.1f fps", textures, ++second, (frames - lastFrames) / ((now - lastReport) / 1e9)));
                    lastReport = now;
                    lastFrames = frames;
                }
                if (now >= end) break;
            }
        }
        double fps = frames / ((System.nanoTime() - measureStart) / 1e9);
        TestLog.i(TAG, String.format("stress2 %d textures, %d sprites/frame at %dx%d: %.1f fps (Tier %s; vsync-capped by the display if near its refresh rate)",
            textures, sprites, W, H, fps, ctx.caps().tier));
        b.dispose();
        for (Texture t : tex) t.dispose();
        require(fps > 5, "stress workload too slow: " + fps + " fps");
    }

    /**
     * Translated shaders against the built-in one on this device: 20,000 sprites from one texture drawn with the batch's own shader, with libGDX's sprite shader
     * translated at run time, and with a custom shader (a tint in the push constants, a palette in the uniform block), four times each in turn, the order alternating.
     */
    private void shaderPrograms(int seconds) {
        final String vert = "attribute vec4 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\nuniform mat4 u_projTrans;\n"
            + "varying vec4 v_color;\nvarying vec2 v_texCoords;\nvoid main() {\n   v_color = a_color;\n   v_color.a = v_color.a * (255.0/254.0);\n"
            + "   v_texCoords = a_texCoord0;\n   gl_Position =  u_projTrans * a_position;\n}\n";
        final String fragStock = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\n"
            + "uniform sampler2D u_texture;\nvoid main() {\n  gl_FragColor = v_color * texture2D(u_texture, v_texCoords);\n}\n";
        final String fragCustom = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\n"
            + "uniform sampler2D u_texture;\nuniform vec4 u_tint;\nuniform vec4 u_palette[4];\nuniform int u_index;\n"
            + "void main() {\n  gl_FragColor = v_color * texture2D(u_texture, v_texCoords) * u_tint * u_palette[u_index];\n}\n";
        final VulkanShaderProgram stock = new VulkanShaderProgram(frame, vert, fragStock);
        final VulkanShaderProgram custom = new VulkanShaderProgram(frame, vert, fragCustom);
        require(stock.isCompiled() && custom.isCompiled(), "the shaders translate and compile on this device: " + stock.getLog() + custom.getLog());
        custom.setUniformf("u_tint", 1f, 1f, 1f, 1f);
        custom.setUniform4fv("u_palette", new float[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, 0, 16);
        custom.setUniformi("u_index", 1);

        final int sprites = 20_000, size = 24, each = Math.max(2, seconds / 2);
        Pixmap pm = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
        pm.setColor(0.9f, 0.7f, 0.4f, 1f);
        pm.fill();
        final Texture texture = new Texture(pm);
        pm.dispose();
        final Random r = new Random(7);
        final float[] x = new float[sprites], y = new float[sprites];
        for (int i = 0; i < sprites; i++) {
            x[i] = r.nextFloat() * (W - size);
            y[i] = r.nextFloat() * (H - size);
        }
        final VulkanSpriteBatch b = new VulkanSpriteBatch(frame, 8191);
        final VulkanShaderProgram[] programs = {null, stock, custom};
        // Settle first: the first seconds of a run are slower (JIT, GPU clock) and would count against whichever shader happens to run first.
        for (long settleEnd = System.nanoTime() + 6_000_000_000L; System.nanoTime() < settleEnd; ) {
            if (!frame.begin(0.1f, 0.1f, 0.1f, 1f)) continue;
            b.getProjectionMatrix().setToOrtho2D(0, 0, W, H);
            b.begin();
            for (int i = 0; i < sprites; i++) b.draw(texture, x[i], y[i], size, size);
            b.end();
            frame.end();
        }
        final String[] names = {"built-in shader", "translated stock shader", "translated custom shader"};
        final double[] sum = new double[3];
        final int rounds = 4;
        for (int round = 0; round < rounds; round++) {
            for (int k = 0; k < 3; k++) {
                // The order alternates, so a phone that warms up and slows down does not favour whichever shader runs first.
                final int c = round % 2 == 0 ? k : 2 - k;
                b.setShaderProgram(programs[c]);
                long warmEnd = System.nanoTime() + 1_000_000_000L, end = 0, measureStart = 0;
                long frames = 0;
                while (true) {
                    if (!frame.begin(0.1f, 0.1f, 0.1f, 1f)) continue;
                    b.getProjectionMatrix().setToOrtho2D(0, 0, W, H);
                    b.begin();
                    for (int i = 0; i < sprites; i++) b.draw(texture, x[i], y[i], size, size);
                    b.end();
                    frame.end();
                    final long now = System.nanoTime();
                    if (measureStart == 0) {
                        if (now >= warmEnd) {
                            measureStart = now;
                            end = now + each * 1_000_000_000L;
                        }
                    } else {
                        frames++;
                        if (now >= end) break;
                    }
                }
                final double fps = frames / ((System.nanoTime() - measureStart) / 1e9);
                sum[c] += fps / rounds;
                TestLog.i(TAG, String.format("shader programs round %d: %s %.1f fps", round + 1, names[c], fps));
            }
        }
        TestLog.i(TAG, String.format("shader programs at %dx%d, %d sprites (Tier %s, vsync-capped by the display if near its refresh rate): built-in %.1f fps, "
            + "translated stock %.1f fps (%.3fx), translated custom %.1f fps (%.3fx)", W, H, sprites, ctx.caps().tier, sum[0], sum[1], sum[1] / sum[0], sum[2], sum[2] / sum[0]));
        b.dispose();
        texture.dispose();
        require(sum[1] >= sum[0] * 0.85, "the translated stock shader is much slower than the built-in one: " + sum[1] + " against " + sum[0] + " fps");
    }

    // ------------------------------------------------------------------------------------------------------------------- Gdx

    private static VulkanGL20 installGdx(android.content.Context app, GdxVkContext ctx) {
        final VulkanGL20 gl = new VulkanGL20(ctx);
        Gdx.app = proxy(Application.class, (p, m, a) -> m.getName().equals("getType") ? ApplicationType.Android : defaultFor(m.getReturnType()));
        Gdx.graphics = proxy(Graphics.class, (p, m, a) -> {
            switch (m.getName()) {
            case "supportsExtension": return gl.supportsExtension((String)a[0]);
            case "getGL20": return gl;
            case "getWidth":
            case "getBackBufferWidth": return gfxW;
            case "getHeight":
            case "getBackBufferHeight": return gfxH;
            case "getDensity": return 1f;
            case "getDeltaTime": return 1f / 60f;
            default: return defaultFor(m.getReturnType());
            }
        });
        Gdx.input = proxy(Input.class, (p, m, a) -> defaultFor(m.getReturnType()));
        Gdx.files = new com.badlogic.gdx.backends.android.DefaultAndroidFiles(app.getAssets(), (android.content.ContextWrapper)app, false);
        Gdx.gl = gl;
        Gdx.gl20 = gl;
        Gdx.gl30 = null;
        return gl;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler h) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, h);
    }

    private static Object defaultFor(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == float.class) return 0f;
        if (t == long.class) return 0L;
        if (t == double.class) return 0.0;
        return null;
    }
}
