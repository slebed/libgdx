package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;

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
 * Phase 3: stock libGDX {@code FrameBuffer} on the GL20 shim. Scenes use the 64x64 grid of {@code SpriteBatchTest}; framebuffers are 64x64
 * too, so one projection serves both. Orientation is the point of several tests: a framebuffer texture keeps GL's row order (row 0 at the
 * bottom), so it appears upside down unless drawn with {@code region.flip(false, true)}, exactly as on stock GL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FrameBufferTest {
    private static final int S = 64, WINDOW = 192;
    private int W, H;
    private GdxVkContext ctx;
    private VulkanGL20 gl;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private long window;
    private Texture white;

    @BeforeAll void setUp() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        window = GLFW.glfwCreateWindow(WINDOW, WINDOW, "gdx-vulkan framebuffer", 0, 0);
        assumeTrue(window != 0, "Could not create a window");
        presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        W = presenter.width();
        H = presenter.height();
        TestGdx.width = W;
        TestGdx.height = H;
        frame = new GdxVkFrame(ctx, presenter, gl);
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.setColor(1, 1, 1, 1);
        pm.fill();
        white = new Texture(pm);
        white.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        pm.dispose();
    }

    @AfterAll void tearDown() {
        white.dispose();
        TestGdx.uninstall();
        if (presenter != null) presenter.close();
        if (ctx != null) {
            assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
            ctx.close();
        }
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    private int[] px(byte[] f, int x, int yFromTop) {
        int i = ((int)((yFromTop + 0.5) * H / S) * W + (int)((x + 0.5) * W / S)) * 4;
        return new int[] {f[i] & 0xFF, f[i + 1] & 0xFF, f[i + 2] & 0xFF};
    }

    private void assertPx(byte[] f, int x, int yFromTop, int r, int g, int b, String what) {
        int[] c = px(f, x, yFromTop);
        assertTrue(Math.abs(c[0] - r) <= 2 && Math.abs(c[1] - g) <= 2 && Math.abs(c[2] - b) <= 2,
            what + ": pixel (" + x + "," + yFromTop + ") was " + java.util.Arrays.toString(c) + ", expected " + r + "," + g + "," + b);
    }

    private VulkanSpriteBatch batch() {
        VulkanSpriteBatch b = new VulkanSpriteBatch(frame);
        b.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
        return b;
    }

    private boolean begin(float r, float g, float bl) {
        GLFW.glfwPollEvents();
        return frame.begin(r, g, bl, 1);
    }

    /** Fills a rectangle (scene units, origin bottom-left) with a colour. */
    private void rect(VulkanSpriteBatch b, float x, float y, float w, float h, float r, float g, float bl) {
        b.setColor(r, g, bl, 1);
        b.draw(white, x, y, w, h);
    }

    @Test void renderingIntoAFrameBufferKeepsGlOrientation() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        assertTrue(begin(0, 0, 0));
        VulkanSpriteBatch b = batch();
        fb.begin();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        b.begin();
        rect(b, 0, 0, 16, 16, 1, 0, 0); // bottom-left corner of the framebuffer, in GL terms
        rect(b, 48, 48, 16, 16, 0, 1, 0); // top-right corner
        b.end();
        fb.end();

        TextureRegion region = new TextureRegion(fb.getColorBufferTexture());
        b.begin();
        b.setColor(1, 1, 1, 1);
        b.draw(region, 0, 0, S, S); // unflipped: the framebuffer's row 0 (GL bottom) ends up at the top of the screen
        b.end();
        byte[] f = frame.endAndCapture();
        // upside down: the red corner (GL bottom-left) is at the top-left of the screen, green at the bottom-right
        assertPx(f, 8, 8, 255, 0, 0, "unflipped: GL bottom-left shows at the top-left");
        assertPx(f, 56, 56, 0, 255, 0, "unflipped: GL top-right shows at the bottom-right");
        assertPx(f, 56, 8, 0, 0, 0, "unflipped: empty corner");

        assertTrue(begin(0, 0, 0));
        b.begin();
        region.flip(false, true);
        b.draw(region, 0, 0, S, S);
        b.end();
        f = frame.endAndCapture();
        assertPx(f, 8, 56, 255, 0, 0, "flipped: red is at the bottom-left, as drawn");
        assertPx(f, 56, 8, 0, 255, 0, "flipped: green is at the top-right, as drawn");
        assertPx(f, 8, 8, 0, 0, 0, "flipped: empty corner");
        b.dispose();
        fb.dispose();
    }

    @Test void swapchainContentSurvivesAnOffscreenPassInTheSameFrame() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        assertTrue(begin(0, 0, 1)); // blue clear
        VulkanSpriteBatch b = batch();
        b.begin();
        rect(b, 0, 0, 20, 64, 1, 0, 0); // red strip on the screen, before the offscreen pass
        b.end();
        fb.begin();
        Gdx.gl.glClearColor(0, 1, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        fb.end();
        b.begin();
        rect(b, 44, 0, 20, 64, 1, 1, 0); // yellow strip after it
        b.end();
        byte[] f = frame.endAndCapture();
        assertPx(f, 10, 32, 255, 0, 0, "drawn before the offscreen pass");
        assertPx(f, 32, 32, 0, 0, 255, "the frame's clear colour is still there in between");
        assertPx(f, 54, 32, 255, 255, 0, "drawn after the offscreen pass");
        // and the framebuffer itself took the green clear
        assertTrue(begin(0, 0, 0));
        b.begin();
        b.setColor(1, 1, 1, 1);
        b.draw(fb.getColorBufferTexture(), 0, 0, S, S);
        b.end();
        f = frame.endAndCapture();
        assertPx(f, 32, 32, 0, 255, 0, "the framebuffer holds the clear colour");
        b.dispose();
        fb.dispose();
    }

    @Test void framebufferContentsPersistAcrossFramesAndLoad() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        assertTrue(begin(0, 0, 0));
        VulkanSpriteBatch b = batch();
        fb.begin();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        b.begin();
        rect(b, 0, 0, 32, 64, 1, 0, 0); // left half red
        b.end();
        fb.end();
        frame.end();

        assertTrue(begin(0, 0, 0)); // second frame: add to the framebuffer without clearing it
        fb.begin();
        b.begin();
        rect(b, 32, 0, 32, 64, 0, 0, 1); // right half blue; the red half must still be there (the pass loads)
        b.end();
        fb.end();
        b.begin();
        b.setColor(1, 1, 1, 1);
        b.draw(fb.getColorBufferTexture(), 0, 0, S, S);
        b.end();
        byte[] f = frame.endAndCapture();
        assertPx(f, 16, 32, 255, 0, 0, "left half survived a frame");
        assertPx(f, 48, 32, 0, 0, 255, "right half was added");
        b.dispose();
        fb.dispose();
    }

    @Test void pingPongThroughTwoFrameBuffers() {
        FrameBuffer a = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        FrameBuffer c = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        assertTrue(begin(0, 0, 0));
        VulkanSpriteBatch b = batch();
        a.begin();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        b.begin();
        rect(b, 8, 8, 24, 24, 1, 1, 1); // white square
        b.end();
        a.end();
        // pass 2: a -> c, tinted red
        c.begin();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        b.begin();
        b.setColor(1, 0, 0, 1);
        b.draw(a.getColorBufferTexture(), 0, 0, S, S);
        b.end();
        c.end();
        // pass 3: c -> a (reusing a as a target after sampling it), tinted half green
        a.begin();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        b.begin();
        b.setColor(0.5f, 1, 0.5f, 1);
        b.draw(c.getColorBufferTexture(), 0, 0, S, S);
        b.end();
        a.end();
        // to the screen, flipped
        TextureRegion r = new TextureRegion(a.getColorBufferTexture());
        r.flip(false, true);
        b.begin();
        b.setColor(1, 1, 1, 1);
        b.draw(r, 0, 0, S, S);
        b.end();
        byte[] f = frame.endAndCapture();
        // white * red = red; red * (0.5, 1, 0.5) = (0.5, 0, 0) -> 128 red. The square was at GL (8..32, 8..32): screen rows 32..56.
        assertPx(f, 20, 44, 128, 0, 0, "the square after three passes");
        assertPx(f, 50, 20, 0, 0, 0, "outside the square");
        b.dispose();
        a.dispose();
        c.dispose();
    }

    @Test void scissorInsideAFrameBufferUsesGlRows() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        assertTrue(begin(0, 0, 0));
        VulkanSpriteBatch b = batch();
        fb.begin();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(0, 0, S / 2, S / 2); // GL's bottom-left quarter of the framebuffer
        b.begin();
        rect(b, 0, 0, S, S, 1, 1, 0);
        b.end();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        fb.end();
        TextureRegion r = new TextureRegion(fb.getColorBufferTexture());
        r.flip(false, true);
        b.begin();
        b.setColor(1, 1, 1, 1);
        b.draw(r, 0, 0, S, S);
        b.end();
        byte[] f = frame.endAndCapture();
        assertPx(f, 16, 48, 255, 255, 0, "the scissored quarter, at the screen's bottom-left once flipped back");
        assertPx(f, 48, 48, 0, 0, 0, "outside the scissor");
        assertPx(f, 16, 16, 0, 0, 0, "outside the scissor (top)");
        b.dispose();
        fb.dispose();
    }

    @Test void viewportInsideAFrameBufferAndRestoredAfter() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        assertTrue(begin(0, 0, 0));
        VulkanSpriteBatch b = batch();
        fb.begin();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glViewport(0, 0, S / 2, S / 2); // draw the whole scene into the framebuffer's bottom-left quarter
        b.begin();
        rect(b, 0, 0, S, S, 0, 1, 1);
        b.end();
        fb.end(); // stock restores the back buffer viewport here
        b.begin();
        rect(b, 0, 0, 8, 8, 1, 0, 1); // lands at the screen's bottom-left, full-size viewport again
        b.end();
        byte[] f = frame.endAndCapture();
        assertPx(f, 4, 60, 255, 0, 255, "swapchain draw after fb.end() uses the restored viewport");
        assertPx(f, 40, 20, 0, 0, 0, "nothing else on the screen");
        assertTrue(begin(0, 0, 0));
        TextureRegion r = new TextureRegion(fb.getColorBufferTexture());
        r.flip(false, true);
        b.begin();
        b.setColor(1, 1, 1, 1);
        b.draw(r, 0, 0, S, S);
        b.end();
        f = frame.endAndCapture();
        assertPx(f, 16, 48, 0, 255, 255, "the quarter viewport filled the framebuffer's GL bottom-left");
        assertPx(f, 48, 16, 0, 0, 0, "the rest of the framebuffer stayed cleared");
        b.dispose();
        fb.dispose();
    }

    // ------------------------------------------------------------------------------------------------------------- depth and stencil

    /** A projection that puts everything the batch draws at the given depth (GL clip z in [-1, 1]: -1 is the nearest). */
    private Matrix4 atDepth(float z) {
        Matrix4 m = new Matrix4().setToOrtho2D(0, 0, S, S);
        m.val[Matrix4.M23] = z;
        return m;
    }

    private void drawAt(VulkanSpriteBatch b, float z, float x, float y, float w, float h, float r, float g, float bl) {
        b.setProjectionMatrix(atDepth(z));
        b.begin();
        rect(b, x, y, w, h, r, g, bl);
        b.end();
    }

    private TextureRegion flipped(FrameBuffer fb) {
        TextureRegion r = new TextureRegion(fb.getColorBufferTexture());
        r.flip(false, true);
        return r;
    }

    private byte[] show(VulkanSpriteBatch b, FrameBuffer fb) {
        b.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, S, S));
        b.begin();
        b.setColor(1, 1, 1, 1);
        b.draw(flipped(fb), 0, 0, S, S);
        b.end();
        return frame.endAndCapture();
    }

    private void clearAll() {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClearDepthf(1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT | GL20.GL_STENCIL_BUFFER_BIT);
    }

    @Test void depthTestMakesTheNearerQuadWinWhateverTheDrawOrder() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, true);
        VulkanSpriteBatch b = batch();
        assertTrue(begin(0, 0, 0));
        fb.begin();
        clearAll();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LESS);
        // left half: near (red) drawn first, then far (blue) over it; right half: far (green) first, then near (yellow)
        drawAt(b, -0.5f, 0, 0, 32, 64, 1, 0, 0);
        drawAt(b, 0.5f, 0, 0, 32, 64, 0, 0, 1);
        drawAt(b, 0.5f, 32, 0, 32, 64, 0, 1, 0);
        drawAt(b, -0.5f, 32, 0, 32, 64, 1, 1, 0);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        fb.end();
        byte[] f = show(b, fb);
        assertPx(f, 16, 32, 255, 0, 0, "near drawn first stays on top");
        assertPx(f, 48, 32, 255, 255, 0, "near drawn last wins");
        b.dispose();
        fb.dispose();
    }

    @Test void withoutDepthTestTheLastQuadWinsAndDepthFuncAndMaskAreHonoured() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, true);
        VulkanSpriteBatch b = batch();
        assertTrue(begin(0, 0, 0));
        fb.begin();
        clearAll();
        // test off: the far quad drawn last covers the near one
        drawAt(b, -0.5f, 0, 0, 16, 64, 1, 0, 0);
        drawAt(b, 0.5f, 0, 0, 16, 64, 0, 0, 1);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        // GL_GREATER: the farther quad passes against the cleared depth of 0 only after being written; first quad with the mask off
        // does not write, so the second (far) one is not rejected by it
        Gdx.gl.glDepthMask(false);
        drawAt(b, -0.5f, 16, 0, 16, 64, 1, 0, 0); // near, tested but not written
        Gdx.gl.glDepthMask(true);
        drawAt(b, 0.5f, 16, 0, 16, 64, 0, 0, 1); // far: nothing was written, so it shows
        Gdx.gl.glDepthFunc(GL20.GL_ALWAYS);
        drawAt(b, 0.9f, 32, 0, 16, 64, 0, 1, 0); // writes a far depth
        drawAt(b, 0.95f, 32, 0, 16, 64, 1, 0, 1); // ALWAYS passes even though it is farther
        Gdx.gl.glDepthFunc(GL20.GL_LESS);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        fb.end();
        byte[] f = show(b, fb);
        assertPx(f, 8, 32, 0, 0, 255, "no depth test: last wins");
        assertPx(f, 24, 32, 0, 0, 255, "depth mask off: the near quad left no depth behind");
        assertPx(f, 40, 32, 255, 0, 255, "GL_ALWAYS passes against anything");
        b.dispose();
        fb.dispose();
    }

    @Test void clearDepthValueAndDepthSurvivesAcrossPasses() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, true);
        VulkanSpriteBatch b = batch();
        assertTrue(begin(0, 0, 0));
        fb.begin();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClearDepthf(0.2f); // nearer than anything drawn below (depth 0.25 and 0.75 after the remap)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        drawAt(b, -0.5f, 0, 0, 64, 64, 1, 0, 0); // 0.25 > 0.2: rejected
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        fb.end();
        byte[] f = show(b, fb);
        assertPx(f, 32, 32, 0, 0, 0, "the clear depth rejected the quad");

        // second pass on the same framebuffer: depth written in the first pass is still there (the pass loads it)
        assertTrue(begin(0, 0, 0));
        fb.begin();
        clearAll();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        drawAt(b, -0.5f, 0, 0, 32, 64, 1, 0, 0); // pass A writes depth 0.25 on the left half
        fb.end();
        fb.begin();
        drawAt(b, 0.5f, 0, 0, 64, 64, 0, 0, 1); // pass B: far quad over the whole framebuffer
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        fb.end();
        f = show(b, fb);
        assertPx(f, 16, 32, 255, 0, 0, "left half: depth from the earlier pass rejected the far quad");
        assertPx(f, 48, 32, 0, 0, 255, "right half: nothing was in front");
        b.dispose();
        fb.dispose();
    }

    @Test void stencilMasksLaterDrawing() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, true, true);
        VulkanSpriteBatch b = batch();
        assertTrue(begin(0, 0, 0));
        fb.begin();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClearStencil(0);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT | GL20.GL_STENCIL_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_STENCIL_TEST);
        Gdx.gl.glStencilFunc(GL20.GL_ALWAYS, 1, 0xFF);
        Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_REPLACE);
        drawAt(b, 0f, 16, 16, 32, 32, 1, 0, 0); // red square in the middle, stencil = 1 there
        Gdx.gl.glStencilFunc(GL20.GL_EQUAL, 1, 0xFF);
        Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_KEEP);
        drawAt(b, 0f, 0, 0, 64, 64, 0, 0, 1); // blue over everything, but only where the stencil is 1
        Gdx.gl.glDisable(GL20.GL_STENCIL_TEST);
        fb.end();
        byte[] f = show(b, fb);
        assertPx(f, 32, 32, 0, 0, 255, "inside the stencil: blue replaced red");
        assertPx(f, 4, 4, 0, 0, 0, "outside the stencil: blue was rejected");
        assertPx(f, 60, 60, 0, 0, 0, "outside the stencil, other corner");
        b.dispose();
        fb.dispose();
    }

    @Test void depthStateOnTheSwapchainIsIgnoredAndDepthlessDrawsWorkInADepthFramebuffer() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, true, true);
        VulkanSpriteBatch b = batch();
        assertTrue(begin(0, 0, 0));
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST); // the swapchain has no depth buffer yet: nothing to test against, so everything draws
        drawAt(b, 0.5f, 0, 0, 32, 64, 1, 0, 0);
        drawAt(b, -0.5f, 0, 0, 32, 64, 0, 1, 0);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        fb.begin(); // a framebuffer with depth and stencil, drawn with both tests off, as a 2D scene would
        clearAll();
        drawAt(b, 0f, 8, 8, 16, 16, 1, 1, 1);
        fb.end();
        b.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, S, S));
        b.begin();
        b.setColor(1, 1, 1, 1);
        b.draw(flipped(fb), 32, 0, 32, 64);
        b.end();
        byte[] f = frame.endAndCapture();
        assertPx(f, 16, 32, 0, 255, 0, "swapchain: the second quad is drawn over the first");
        assertPx(f, 40, 52, 255, 255, 255, "the depth framebuffer's white square (GL (8..24, 8..24) mapped into the right half)");
        b.dispose();
        fb.dispose();
    }

    @Test void frameBuffersCanBeCreatedAndDisposedRepeatedly() {
        for (int i = 0; i < 20; i++) {
            FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, 16 + i, 16 + i, false);
            assertTrue(begin(0, 0, 0));
            VulkanSpriteBatch b = batch();
            fb.begin();
            Gdx.gl.glClearColor(i / 20f, 0, 0, 1);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            fb.end();
            b.dispose();
            frame.end();
            fb.dispose();
        }
    }
}
