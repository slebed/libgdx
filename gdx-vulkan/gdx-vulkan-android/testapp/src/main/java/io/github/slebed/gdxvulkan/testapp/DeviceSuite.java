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
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatchInstanced;
import io.github.slebed.gdxvulkan.desktop.Gate3Scenes;
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
        tierA = ctx.caps().tier == VulkanCaps.Tier.A;
        System.loadLibrary("gdx"); // gdx2d, for Pixmap
        gfxW = presenter.width();
        gfxH = presenter.height();
        gl = installGdx(app, ctx);
        W = presenter.width();
        H = presenter.height();
        frame = new GdxVkFrame(ctx, presenter, gl);
    }

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
        s.check("gate3Scenes", s::gate3);
        s.check("stress3Recording", s::stress3);
        s.check("soakTextureChurn", () -> s.soak(soakSeconds));
        s.check("stress2Single", () -> s.stress(1, stressSeconds));
        s.check("stress2Textures128", () -> s.stress(128, stressSeconds));
        s.check("instancedMillionSprites", () -> s.instancedMillion(stressSeconds));
        TestLog.i(TAG, "validationMessages=" + ctx.validationMessageCount());
        TestLog.i(TAG, (s.failed == 0 ? "DEVICESUITE PASS " : "DEVICESUITE FAIL ") + s.passed + " passed, " + s.failed + " failed, "
            + s.skipped + " skipped");
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

    /** The captured frame's bottom-left size x size pixels against {@code goldens/<name>.png} (a lavapipe render). */
    private void compareWithGolden(String name, byte[] px, int size) throws java.io.IOException {
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
                if ((px[i] & 0xFF) != 10 || (px[i + 1] & 0xFF) != 10 || (px[i + 2] & 0xFF) != 18) drawn++;
            }
        }
        double share = differing / (double)(size * size);
        TestLog.i(TAG, String.format("%s vs lavapipe golden: differing=%d (%.3f%%) maxDelta=%d, %d of %d pixels drawn", name, differing, share * 100,
            maxDelta, drawn, size * size));
        require(drawn > size * size / 4, name + " is nearly blank");
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
            pm.setColor(((i * 37) & 255) / 255f, ((i * 91) & 255) / 255f, ((i * 53) & 255) / 255f, 1f);
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
        long warmEnd = System.nanoTime() + 1_000_000_000L, end = 0;
        long frames = 0, measureStart = 0;
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
