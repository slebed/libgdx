package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatchInstanced;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.glfw.GLFW;

/**
 * Phase 2: {@code VulkanSpriteBatchInstanced}. The sprites are uploaded once, moved by a compute dispatch queued in the frame, and drawn by a
 * vertex shader that reads the same buffer; results are read back from a captured frame and compared with a CPU simulation of the same
 * movement. Scenes use a 64x64 grid like {@code SpriteBatchTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstancedBatchTest {
    private static final int S = 64, WINDOW = 192;
    private int W, H;
    private GdxVkContext ctx;
    private VulkanGL20 gl;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private long window;

    @BeforeAll void setUp() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        window = GLFW.glfwCreateWindow(WINDOW, WINDOW, "gdx-vulkan instanced batch", 0, 0);
        assumeTrue(window != 0, "Could not create a window");
        presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        W = presenter.width();
        H = presenter.height();
        TestGdx.width = W;
        TestGdx.height = H;
        frame = new GdxVkFrame(ctx, presenter, gl);
    }

    @AfterAll void tearDown() {
        TestGdx.uninstall();
        if (presenter != null) presenter.close();
        if (ctx != null) {
            assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
            ctx.close();
        }
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    private static Texture white() {
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.setColor(1, 1, 1, 1);
        pm.fill();
        Texture t = new Texture(pm);
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        pm.dispose();
        return t;
    }

    private interface Frame {
        void record(VulkanSpriteBatchInstanced b);
    }

    private byte[] render(VulkanSpriteBatchInstanced b, Frame f) {
        GLFW.glfwPollEvents();
        assertTrue(frame.begin(0, 0, 0, 1));
        f.record(b);
        return frame.endAndCapture();
    }

    private int[] px(byte[] f, double x, double yUp) { // scene coordinates, origin bottom-left
        int i = ((int)((S - yUp) * H / S) * W + (int)(x * W / S)) * 4;
        return new int[] {f[i] & 0xFF, f[i + 1] & 0xFF, f[i + 2] & 0xFF};
    }

    private void assertColor(byte[] f, double x, double yUp, int r, int g, int b, String what) {
        int[] c = px(f, x, yUp);
        assertTrue(Math.abs(c[0] - r) <= 2 && Math.abs(c[1] - g) <= 2 && Math.abs(c[2] - b) <= 2,
            what + ": (" + x + "," + yUp + ") was " + java.util.Arrays.toString(c) + ", expected " + r + "," + g + "," + b);
    }

    private VulkanSpriteBatchInstanced batch(int capacity) {
        VulkanSpriteBatchInstanced b = new VulkanSpriteBatchInstanced(frame, capacity);
        b.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
        b.setBounds(S, S);
        b.setSpriteSize(8, 8);
        return b;
    }

    @Test void spritesAreDrawnFromTheBufferWithTintAndTexture() {
        Texture t = white();
        VulkanSpriteBatchInstanced b = batch(16);
        b.setTexture(t);
        b.setColor(1, 0, 0, 1);
        b.setSprites(new float[] {4, 4, 0, 0, 40, 30, 0, 0}, 2); // lower-left corners
        byte[] f = render(b, x -> {
            x.update(0f); // dt 0: the compute pass runs but nothing moves
            x.draw();
        });
        assertColor(f, 8, 8, 255, 0, 0, "first sprite");
        assertColor(f, 44, 34, 255, 0, 0, "second sprite");
        assertColor(f, 20, 20, 0, 0, 0, "between the sprites");
        assertColor(f, 2, 2, 0, 0, 0, "outside the first sprite");
        assertColor(f, 13, 8, 0, 0, 0, "right of the first sprite (size 8)");
        b.dispose();
        t.dispose();
    }

    @Test void computeMovesAndBouncesSpritesLikeTheCpuSimulation() {
        Texture t = white();
        VulkanSpriteBatchInstanced b = batch(8);
        b.setTexture(t);
        b.setColor(0, 1, 0, 1);
        // Eight sprites in their own rows so none overlaps: x, y, vx, vy. Some bounce off the walls within the run.
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
        for (int step = 0; step < 6; step++) { // 1.5 s: the fastest sprites cross the 56-unit field more than once
            f = render(b, x -> {
                x.update(dt);
                x.draw();
            });
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
            assertColor(f, ref[i * 4] + 4, ref[i * 4 + 1] + 4, 0, 255, 0, "sprite " + i + " centre after 6 steps");
        }
        b.dispose();
        t.dispose();
    }

    @Test void uploadAndComputeInTheSameFrameAreOrdered() {
        Texture t = white();
        VulkanSpriteBatchInstanced b = batch(4);
        b.setTexture(t);
        b.setColor(0, 0, 1, 1);
        b.setSprites(new float[] {10, 10, 20, 0}, 1);
        byte[] f = render(b, x -> {
            x.update(0.5f); // moves 10 units: the upload staged just above has to be visible to this dispatch
            x.draw();
        });
        assertColor(f, 24, 14, 255 * 0, 0, 255, "moved sprite (x 10 -> 20, centre 24)");
        assertColor(f, 14, 14, 0, 0, 0, "where it started");
        // overwrite the sprite while earlier frames may still be in flight, then move it again
        b.setSprites(new float[] {40, 40, 0, -20}, 1);
        f = render(b, x -> {
            x.update(0.5f);
            x.draw();
        });
        assertColor(f, 44, 34, 0, 0, 255, "second upload, moved down 10");
        assertColor(f, 24, 14, 0, 0, 0, "first sprite is gone");
        b.dispose();
        t.dispose();
    }

    @Test void gravityPullsSpritesDown() {
        Texture t = white();
        VulkanSpriteBatchInstanced b = batch(2);
        b.setTexture(t);
        b.setColor(1, 1, 0, 1);
        b.setGravity(-40f);
        b.setSprites(new float[] {20, 40, 0, 0}, 1);
        byte[] f = null;
        for (int i = 0; i < 4; i++) {
            f = render(b, x -> {
                x.update(0.5f);
                x.draw();
            });
        }
        // v += g*dt then p += v*dt each step: y = 40 - 10, -30, -60, -100 -> clamped by the floor bounce
        float y = 40, vy = 0;
        for (int i = 0; i < 4; i++) {
            vy += -40f * 0.5f;
            y += vy * 0.5f;
            if (y < 0) {
                y = -y;
                vy = Math.abs(vy);
            }
            y = Math.min(y, S - 8);
        }
        assertColor(f, 24, y + 4, 255, 255, 0, "sprite after four steps with gravity");
        b.dispose();
        t.dispose();
    }

    @Test void aMillionSpritesUpdateAndDraw() {
        Texture t = white();
        final int n = 1_000_000;
        VulkanSpriteBatchInstanced b = batch(n);
        b.setTexture(t);
        b.setColor(1, 0, 1, 0.5f);
        b.setSpriteSize(1, 1);
        float[] s = new float[n * 4];
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < n; i++) {
            s[i * 4] = r.nextFloat() * (S - 1);
            s[i * 4 + 1] = r.nextFloat() * (S - 1);
            s[i * 4 + 2] = r.nextFloat() * 20 - 10;
            s[i * 4 + 3] = r.nextFloat() * 20 - 10;
        }
        b.setSprites(s, n);
        assertEquals(n, b.size());
        byte[] f = null;
        for (int i = 0; i < 3; i++) {
            f = render(b, x -> {
                x.update(1f / 60f);
                x.draw();
            });
        }
        // a million half-transparent magenta 1x1 sprites over a 64x64 field: every pixel has been hit many times, so it is saturated
        int[] c = px(f, 32, 32);
        assertTrue(c[0] > 200 && c[1] < 20 && c[2] > 200, "dense field: " + java.util.Arrays.toString(c));
        b.dispose();
        t.dispose();
    }
}
