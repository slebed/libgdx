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
import io.github.slebed.gdxvulkan.VulkanShaderPipelineBundle;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;
import io.github.slebed.gdxvulkan.glutils.VulkanMultiTargetFrameBuffer;
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

    // ---------------------------------------------------------------------------------------------------------------- load / store ops

    /** A transient depth buffer (opt-in) behaves like any other inside a pass, as long as every pass clears it. */
    @Test void transientDepthWorksWhenEveryPassClearsIt() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, true, true);
        gl.setFramebufferDepthTransient(fb.getFramebufferHandle(), true);
        VulkanSpriteBatch b = batch();
        for (int frameNo = 0; frameNo < 2; frameNo++) { // twice: the second pass must not depend on what the first left behind
            assertTrue(begin(0, 0, 0));
            fb.begin();
            clearAll();
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            drawAt(b, -0.5f, 0, 0, 32, 64, 1, 0, 0);
            drawAt(b, 0.5f, 0, 0, 32, 64, 0, 0, 1); // far, drawn second: hidden by the near one
            drawAt(b, 0.5f, 32, 0, 32, 64, 0, 1, 0);
            drawAt(b, -0.5f, 32, 0, 32, 64, 1, 1, 0); // near, drawn second: wins
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            fb.end();
            byte[] f = show(b, fb);
            assertPx(f, 16, 32, 255, 0, 0, "transient depth: near first stays (pass " + frameNo + ")");
            assertPx(f, 48, 32, 255, 255, 0, "transient depth: near last wins (pass " + frameNo + ")");
        }
        b.dispose();
        fb.dispose();
    }

    /** A pass that never touches the depth buffer of a framebuffer that has one (nothing to load the first time) is fine. */
    @Test void aDepthFramebufferUsedWithoutEverClearingItsDepth() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, true);
        VulkanSpriteBatch b = batch();
        for (int i = 0; i < 2; i++) {
            assertTrue(begin(0, 0, 0));
            fb.begin();
            Gdx.gl.glClearColor(0, 0, 0, 1);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT); // colour only: the depth image has never held anything
            drawAt(b, 0f, 8, 8, 48, 48, 0, 1, 1);
            fb.end();
            byte[] f = show(b, fb);
            assertPx(f, 32, 32, 0, 255, 255, "colour-only use of a depth framebuffer, pass " + i);
        }
        b.dispose();
        fb.dispose();
    }

    // ------------------------------------------------------------------------------------------------------------ multi-target framebuffer

    private TextureRegion halfOf(Texture t, boolean left) {
        TextureRegion r = new TextureRegion(t, left ? 0 : t.getWidth() / 2, 0, t.getWidth() / 2, t.getHeight());
        r.flip(false, true);
        return r;
    }

    /**
     * One draw writes three attachments (RGBA8, RGBA16F, RGBA32F) of a framebuffer that also has a depth buffer. The shader multiplies the colour by
     * 4 for the float ones; scaling them by a quarter afterwards gives the colour back only if the value above 1.0 was kept. Clamped to 1.0 it would
     * come back as 64.
     */
    @Test void threeColourAttachmentsAreWrittenByOneDrawAndFloatsKeepValuesAboveOne() {
        VulkanMultiTargetFrameBuffer mt = new VulkanMultiTargetFrameBuffer(S, S, true, false, VulkanMultiTargetFrameBuffer.ColorFormat.RGBA8888,
            VulkanMultiTargetFrameBuffer.ColorFormat.RGBA16F, VulkanMultiTargetFrameBuffer.ColorFormat.RGBA32F);
        VulkanShaderPipelineBundle mrt = VulkanShaderPipelineBundle.fromResources("mrt3", "sprite.vert", "sprite_mrt.frag").withColorOutputs(3);
        VulkanShaderPipelineBundle scale = VulkanShaderPipelineBundle.fromResources("scale", "sprite.vert", "fx_scale.frag");
        VulkanSpriteBatch b = batch();
        assertEquals(3, mt.getColorBufferCount());

        assertTrue(begin(0, 0, 0));
        mt.begin();
        Gdx.gl.glClearColor(0.2f, 0.4f, 0.6f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT); // every attachment is cleared
        mrt.custom0[0] = 4f;
        b.setShaderBundle(mrt);
        b.begin();
        b.setColor(0.5f, 0.25f, 0.75f, 1);
        b.draw(white, 0, 0, 32, 64); // the left half of all three attachments
        b.end();
        b.setShaderBundle(null);
        mt.end();

        // the sprite, from each attachment: plain from the 8-bit one, scaled by a quarter from the float ones
        b.begin();
        b.setColor(1, 1, 1, 1);
        b.draw(halfOf(mt.getColorBufferTexture(0), true), 0, 0, 21, 64);
        scale.custom0[0] = 0.25f;
        b.setShaderBundle(scale);
        b.draw(halfOf(mt.getColorBufferTexture(1), true), 21, 0, 21, 64);
        b.draw(halfOf(mt.getColorBufferTexture(2), true), 42, 0, 22, 64);
        b.setShaderBundle(null);
        b.end();
        byte[] f = frame.endAndCapture();
        assertPx(f, 10, 32, 128, 64, 191, "RGBA8888 attachment");
        assertPx(f, 31, 32, 128, 64, 191, "RGBA16F attachment: 4x the colour, kept above 1.0, scaled back");
        assertPx(f, 52, 32, 128, 64, 191, "RGBA32F attachment: the same");

        // and the clear reached all three, outside the sprite
        assertTrue(begin(0, 0, 0));
        b.begin();
        b.setColor(1, 1, 1, 1);
        b.draw(halfOf(mt.getColorBufferTexture(0), false), 0, 0, 21, 64);
        scale.custom0[0] = 1f;
        b.setShaderBundle(scale);
        b.draw(halfOf(mt.getColorBufferTexture(1), false), 21, 0, 21, 64);
        b.draw(halfOf(mt.getColorBufferTexture(2), false), 42, 0, 22, 64);
        b.setShaderBundle(null);
        b.end();
        f = frame.endAndCapture();
        assertPx(f, 10, 32, 51, 102, 153, "the clear colour in the RGBA8888 attachment");
        assertPx(f, 31, 32, 51, 102, 153, "the clear colour in the RGBA16F attachment");
        assertPx(f, 52, 32, 51, 102, 153, "the clear colour in the RGBA32F attachment");
        b.dispose();
        mt.dispose();
    }

    /** A pipeline writes as many outputs as its target has attachments; anything else is refused with a message that says so. */
    @Test void aPipelineWithTheWrongNumberOfOutputsIsRefusedClearly() {
        VulkanMultiTargetFrameBuffer mt = new VulkanMultiTargetFrameBuffer(S, S, false, false, VulkanMultiTargetFrameBuffer.ColorFormat.RGBA8888,
            VulkanMultiTargetFrameBuffer.ColorFormat.RGBA16F);
        VulkanSpriteBatch b = batch();
        assertTrue(begin(0, 0, 0));
        mt.begin();
        b.begin();
        rect(b, 0, 0, 32, 32, 1, 0, 0); // the default sprite shader writes one output, the target has two
        b.end();
        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> frame.flush());
        assertTrue(e.getMessage().contains("colour output") && e.getMessage().contains("2 colour attachment"), e.getMessage());
        mt.end();
        frame.end();
        b.dispose();
        mt.dispose();
    }

    // ------------------------------------------------------------------------------------------------------------- pass scheduling

    /** Four framebuffer passes, then the screen: the swapchain pass begins once, after them, instead of being suspended and resumed. */
    @Test void framebufferPassesBeforeTheFirstSwapchainDrawNeverSuspendIt() {
        FrameBuffer a = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false), c = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        VulkanSpriteBatch b = batch();
        assertTrue(begin(0, 0, 0.5f));
        FrameBuffer[] chain = {a, c, a, c};
        for (int i = 0; i < chain.length; i++) {
            chain[i].begin();
            Gdx.gl.glClearColor(0, i == 0 ? 1 : 0, 0, 1); // only the first pass has green of its own; the others copy it forward
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            if (i > 0) { // sample the previous pass's result
                b.begin();
                b.draw(chain[i - 1].getColorBufferTexture(), 0, 0, S, S);
                b.end();
            }
            chain[i].end();
        }
        byte[] f = show(b, c);
        int[] passes = presenter.lastPassCounts();
        assertEquals(1, passes[0], "the swapchain pass began once, after the framebuffer passes");
        assertEquals(4, passes[1], "four framebuffer passes");
        assertPx(f, 32, 32, 0, 255, 0, "the first pass's green travelled through the chain to the screen");
        b.dispose();
        a.dispose();
        c.dispose();
    }

    @Test void drawingToTheScreenBetweenFramebufferPassesDoesSuspendIt() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        VulkanSpriteBatch b = batch();
        assertTrue(begin(0, 0, 0));
        b.begin();
        rect(b, 0, 0, 16, 64, 1, 0, 0);
        b.end();
        fb.begin();
        Gdx.gl.glClearColor(0, 1, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        fb.end();
        b.begin();
        rect(b, 48, 0, 16, 64, 0, 0, 1);
        b.end();
        byte[] f = frame.endAndCapture();
        int[] passes = presenter.lastPassCounts();
        assertEquals(2, passes[0], "the swapchain pass was interrupted by the framebuffer pass and resumed");
        assertEquals(1, passes[1]);
        assertPx(f, 8, 32, 255, 0, 0, "drawn before the interruption");
        assertPx(f, 56, 32, 0, 0, 255, "drawn after it");
        b.dispose();
        fb.dispose();
    }

    @Test void aFrameThatNeverDrawsToTheScreenStillShowsItsClearColour() {
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        assertTrue(begin(0, 0, 1));
        fb.begin();
        Gdx.gl.glClearColor(1, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        fb.end();
        byte[] f = frame.endAndCapture();
        assertEquals(1, presenter.lastPassCounts()[0], "an otherwise empty swapchain pass clears the image");
        assertPx(f, 32, 32, 0, 0, 255, "the frame's clear colour");

        assertTrue(begin(0, 1, 0));
        fb.begin(); // and the framebuffer is left bound at the end of the frame
        Gdx.gl.glClearColor(1, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        f = frame.endAndCapture();
        assertPx(f, 32, 32, 0, 255, 0, "the clear colour with a framebuffer still bound");
        fb.end();
        fb.dispose();
    }

    @Test void aFullClearThatStartsTheSwapchainPassReplacesTheFrameClear() {
        assertTrue(begin(1, 0, 0));
        Gdx.gl.glClearColor(0, 1, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        byte[] f = frame.endAndCapture();
        assertEquals(1, presenter.lastPassCounts()[0], "one pass: the clear became its load op");
        assertPx(f, 32, 32, 0, 255, 0, "the application's clear colour");
    }

    /**
     * GL cull state replaces the pipelines' own. A stock SpriteBatch quad (bottom-left, top-left, top-right, bottom-right) is clockwise in GL's
     * y-up space, so with GL's default counter-clockwise front faces GL_BACK culls it and GL_FRONT keeps it, on the swapchain and in a
     * framebuffer alike (a framebuffer is drawn without the flip, with the opposite Vulkan winding).
     */
    @Test void glCullStateAppliesToTheSwapchainAndToFrameBuffers() {
        VulkanSpriteBatch b = batch();
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, S, S, false);
        for (int target = 0; target < 2; target++) {
            boolean inFb = target == 1;
            int[] modes = {GL20.GL_BACK, GL20.GL_FRONT, GL20.GL_FRONT_AND_BACK};
            boolean[] visible = {false, true, false};
            for (int m = 0; m < modes.length; m++) {
                assertTrue(begin(0, 0, 0));
                if (inFb) {
                    fb.begin();
                    Gdx.gl.glClearColor(0, 0, 0, 1);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                }
                Gdx.gl.glEnable(GL20.GL_CULL_FACE);
                Gdx.gl.glCullFace(modes[m]);
                b.begin();
                rect(b, 8, 8, 48, 48, 1, 0, 0);
                b.end();
                Gdx.gl.glDisable(GL20.GL_CULL_FACE);
                byte[] f;
                if (inFb) {
                    fb.end();
                    f = show(b, fb);
                } else {
                    f = frame.endAndCapture();
                }
                String what = (inFb ? "framebuffer" : "swapchain") + ", glCullFace 0x" + Integer.toHexString(modes[m]);
                assertPx(f, 32, 32, visible[m] ? 255 : 0, 0, 0, what + (visible[m] ? ": the quad is front-facing for GL" : ": the quad is culled"));
            }
            // culling off again: back to normal drawing
            assertTrue(begin(0, 0, 0));
            if (inFb) {
                fb.begin();
                Gdx.gl.glClearColor(0, 0, 0, 1);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            }
            b.begin();
            rect(b, 8, 8, 48, 48, 0, 1, 0);
            b.end();
            byte[] f;
            if (inFb) {
                fb.end();
                f = show(b, fb);
            } else {
                f = frame.endAndCapture();
            }
            assertPx(f, 32, 32, 0, 255, 0, (inFb ? "framebuffer" : "swapchain") + " with culling disabled draws as before");
        }
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
