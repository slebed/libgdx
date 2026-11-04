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
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.glfw.GLFW;

/**
 * Two windows, two presenters and two frames on one context (plan, Phase 1b: retirement across multiple windows). Textures are shared by the whole
 * context; a texture disposed after window 0 drew it must stay alive for work still in flight, a rename must not disturb the other window's
 * frame, and nothing may leak or trip the validation layer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiWindowTest {
    private GdxVkContext ctx;
    private VulkanGL20 gl;
    private final long[] windows = new long[2];
    private final GdxVkPresenter[] presenters = new GdxVkPresenter[2];
    private final GdxVkFrame[] frames = new GdxVkFrame[2];

    @BeforeAll void setUp() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        for (int i = 0; i < 2; i++) {
            windows[i] = GLFW.glfwCreateWindow(160 + 32 * i, 128, "gdx-vulkan window " + i, 0, 0);
            assumeTrue(windows[i] != 0, "Could not create a window");
            presenters[i] = GlfwSurfaces.createPresenter(ctx, windows[i], false);
            frames[i] = new GdxVkFrame(ctx, presenters[i], gl);
        }
    }

    @AfterAll void tearDown() {
        TestGdx.uninstall();
        for (int i = 0; i < 2; i++) if (presenters[i] != null) presenters[i].close();
        if (ctx != null) {
            assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
            ctx.close();
        }
        for (long win : windows) if (win != 0) GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();
    }

    private static Texture solid(float r, float g, float b) {
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.setColor(r, g, b, 1);
        pm.fill();
        Texture t = new Texture(pm);
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        pm.dispose();
        return t;
    }

    /** Draws the texture over the whole of window i, so the capture is one colour, and returns its centre pixel. */
    private int[] drawFull(int i, Texture t) {
        final int pw = presenters[i].width(), ph = presenters[i].height();
        TestGdx.width = pw;
        TestGdx.height = ph;
        GLFW.glfwPollEvents();
        assertTrue(frames[i].begin(0, 0, 0, 1));
        VulkanSpriteBatch b = new VulkanSpriteBatch(frames[i]);
        b.getProjectionMatrix().setToOrtho2D(0, 0, pw, ph);
        b.begin();
        b.draw(t, 0, 0, pw, ph);
        b.end();
        b.dispose();
        byte[] px = frames[i].endAndCapture();
        int o = ((ph / 2) * pw + pw / 2) * 4;
        return new int[] {px[o] & 0xFF, px[o + 1] & 0xFF, px[o + 2] & 0xFF};
    }

    @Test void aTextureDrawnInOneWindowAndDisposedBeforeTheOtherDrawsIsSafe() {
        for (int round = 0; round < 30; round++) {
            Texture t = solid(round % 2 == 0 ? 1 : 0, 0, 1);
            int[] a = drawFull(0, t);
            t.dispose(); // the context's textures are shared, and window 0's frame may still be on the GPU
            Texture u = solid(1, 1, 0);
            int[] b = drawFull(1, u);
            u.dispose();
            assertEquals(round % 2 == 0 ? 255 : 0, a[0], "window 0 shows its texture, round " + round);
            assertEquals(255, a[2], "window 0 blue, round " + round);
            assertEquals(255, b[0], "window 1 shows its own texture, round " + round);
            assertEquals(255, b[1], "window 1 green, round " + round);
        }
    }

    @Test void oneTextureLivesAcrossBothWindowsThenIsRenamedAndDisposed() {
        Texture t = solid(0, 1, 0);
        int[] a = drawFull(0, t);
        int[] b = drawFull(1, t);
        assertEquals(255, a[1], "window 0");
        assertEquals(255, b[1], "window 1");
        Pixmap red = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        red.setColor(1, 0, 0, 1);
        red.fill();
        t.draw(red, 0, 0); // a rename while both windows have frames in flight that use the old image
        a = drawFull(0, t);
        b = drawFull(1, t);
        assertEquals(255, a[0], "window 0 sees the new contents");
        assertEquals(255, b[0], "window 1 sees the new contents");
        red.dispose();
        t.dispose();
        for (int i = 0; i < 6; i++) { // frames alternating on both windows run the deletion queue
            Texture blue = solid(0, 0, 1);
            drawFull(i & 1, blue);
            blue.dispose();
        }
        assertTrue(ctx.resources().stats().retiredPending < 50, "retired objects are being released: " + ctx.resources().stats());
    }
}
