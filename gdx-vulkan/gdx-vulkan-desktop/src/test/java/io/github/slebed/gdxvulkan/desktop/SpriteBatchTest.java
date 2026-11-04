package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.PolygonRegion;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.EarClippingTriangulator;
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.VulkanCaps;
import io.github.slebed.gdxvulkan.g2d.VulkanPolygonSpriteBatch;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.glfw.GLFW;

/**
 * Phase 2: stock libGDX 2D calls through {@link VulkanSpriteBatch}, the command stream and ordered GL state, read back from a captured frame.
 * Scenes are laid out on a 64x64 grid (the batch projection is set to it, origin bottom-left); the window is larger because the OS refuses
 * very small ones, so {@link #px} and the checks take scene coordinates and map them to the pixel at the centre of that cell.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpriteBatchTest {
    private static final int S = 64; // scene size
    private static final int WINDOW = 192;
    private int W, H; // framebuffer size in pixels
    private GdxVkContext ctx;
    private VulkanGL20 gl;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private long window;
    private boolean tierA;

    @BeforeAll void setUp() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        tierA = ctx.caps().tier == VulkanCaps.Tier.A;
        gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        window = GLFW.glfwCreateWindow(WINDOW, WINDOW, "gdx-vulkan sprite batch", 0, 0);
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

    private interface Scene {
        void draw(VulkanSpriteBatch batch);
    }

    private byte[] render(float[] clear, Scene scene) {
        GLFW.glfwPollEvents();
        assertTrue(frame.begin(clear[0], clear[1], clear[2], clear[3]));
        VulkanSpriteBatch batch = new VulkanSpriteBatch(frame);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
        scene.draw(batch);
        batch.dispose();
        return frame.endAndCapture();
    }

    private static final float[] BLACK = {0, 0, 0, 1};

    /** The pixel at the centre of scene cell (x, yFromTop). */
    private int[] px(byte[] frame, int x, int yFromTop) {
        int i = ((int)((yFromTop + 0.5) * H / S) * W + (int)((x + 0.5) * W / S)) * 4;
        return new int[] {frame[i] & 0xFF, frame[i + 1] & 0xFF, frame[i + 2] & 0xFF, frame[i + 3] & 0xFF};
    }

    private void assertPx(byte[] frame, int x, int yFromTop, int r, int g, int b, int tol, String what) {
        int[] c = px(frame, x, yFromTop);
        assertTrue(Math.abs(c[0] - r) <= tol && Math.abs(c[1] - g) <= tol && Math.abs(c[2] - b) <= tol,
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

    @Test void texturedQuadIsUprightAndExact() {
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.drawPixel(0, 0, 0xFF0000FF); // top-left red
        pm.drawPixel(1, 0, 0x00FF00FF); // top-right green
        pm.drawPixel(0, 1, 0x0000FFFF); // bottom-left blue
        pm.drawPixel(1, 1, 0xFFFF00FF); // bottom-right yellow
        Texture t = new Texture(pm);
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(t, 0, 0, S, S);
            b.end();
        });
        assertPx(f, 8, 8, 255, 0, 0, 1, "top-left quadrant");
        assertPx(f, 56, 8, 0, 255, 0, 1, "top-right quadrant");
        assertPx(f, 8, 56, 0, 0, 255, 1, "bottom-left quadrant");
        assertPx(f, 56, 56, 255, 255, 0, 1, "bottom-right quadrant");
        t.dispose();
        pm.dispose();
    }

    @Test void tintMultipliesAndBlendingUsesTheBlendFunction() {
        Texture white = solid(1, 1, 1, 1);
        byte[] f = render(new float[] {1, 0, 0, 1}, b -> { // red background
            b.begin();
            b.setColor(1, 1, 1, 0.5f);
            b.draw(white, 0, 0, 32, 64); // left half: 50% white over red
            b.setColor(0, 1, 0, 1);
            b.draw(white, 32, 0, 32, 64); // right half: opaque green tint
            b.end();
        });
        assertPx(f, 16, 32, 255, 128, 128, 3, "50% white over red");
        assertPx(f, 48, 32, 0, 255, 0, 1, "green tint");

        byte[] g = render(new float[] {1, 0, 0, 1}, b -> { // blending off: alpha is ignored, the sprite replaces
            b.begin();
            b.disableBlending();
            b.setColor(1, 1, 1, 0.5f);
            b.draw(white, 0, 0, 64, 64);
            b.end();
        });
        assertPx(g, 32, 32, 255, 255, 255, 1, "no blending");
        white.dispose();
    }

    @Test void textureChangesFlushOnlyOnTierB() {
        Texture r = solid(1, 0, 0, 1), g = solid(0, 1, 0, 1), bl = solid(0, 0, 1, 1);
        int[] calls = new int[1];
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(r, 0, 0, 20, 64);
            b.draw(g, 20, 0, 20, 64);
            b.draw(bl, 40, 0, 20, 64);
            b.draw(r, 60, 0, 4, 64); // back to the first texture
            b.end();
            calls[0] = b.renderCalls;
        });
        assertPx(f, 10, 32, 255, 0, 0, 1, "first");
        assertPx(f, 30, 32, 0, 255, 0, 1, "second");
        assertPx(f, 50, 32, 0, 0, 255, 1, "third");
        assertPx(f, 62, 32, 255, 0, 0, 1, "first again");
        // The point of the bindless table: four textures, one draw call. Tier B binds one descriptor set per draw, as stock does.
        assertEquals(tierA ? 1 : 4, calls[0], "draw calls on " + (tierA ? "Tier A" : "Tier B"));
        r.dispose();
        g.dispose();
        bl.dispose();
    }

    @Test void moreSpritesThanTheBatchHoldsFlushesInOrder() {
        Texture red = solid(1, 0, 0, 1), blue = solid(0, 0, 1, 1);
        int[] calls = new int[1];
        byte[] f = render(BLACK, b -> {
            b.begin();
            for (int i = 0; i < 2500; i++) b.draw(i % 2 == 0 ? red : blue, 0, 0, S, S); // a 1000-sprite batch overflows twice
            b.draw(red, 0, 0, 10, 10);
            b.end();
            calls[0] = b.renderCalls;
        });
        assertTrue(calls[0] >= 3, "the batch had to flush: " + calls[0]);
        assertPx(f, 5, 58, 255, 0, 0, 1, "the last sprite is on top");
        assertPx(f, 40, 20, 0, 0, 255, 1, "2500 is even-count: sprite 2499 (blue) covers the rest");
        red.dispose();
        blue.dispose();
    }

    @Test void scissorAndClearAreOrderedWithTheDraws() {
        Texture red = solid(1, 0, 0, 1), blue = solid(0, 0, 1, 1);
        byte[] f = render(new float[] {0, 0, 0, 1}, b -> {
            b.begin();
            b.draw(red, 0, 0, S, S);
            b.flush(); // everything above is in the stream before the state changes below
            Gdx.gl.glClearColor(0, 1, 0, 1);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT); // wipes the red
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glScissor(0, 0, W / 2, H); // left half only, GL pixel coordinates
            b.draw(blue, 0, 0, S, S);
            b.flush();
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            b.end();
        });
        assertPx(f, 48, 32, 0, 255, 0, 1, "right half: cleared green, never drawn over (scissored)");
        assertPx(f, 16, 32, 0, 0, 255, 1, "left half: blue drawn after the clear, inside the scissor");
        red.dispose();
        blue.dispose();
    }

    @Test void scissorUsesGlCoordinatesFromTheBottomLeft() {
        Texture red = solid(1, 0, 0, 1);
        byte[] f = render(BLACK, b -> {
            b.begin();
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glScissor(0, 0, W, H / 4); // the BOTTOM quarter in GL
            b.draw(red, 0, 0, S, S);
            b.flush();
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            b.end();
        });
        assertPx(f, 32, 60, 255, 0, 0, 1, "bottom rows are drawn");
        assertPx(f, 32, 20, 0, 0, 0, 0, "above the scissor stays clear");
        red.dispose();
    }

    @Test void viewportStateSurvivesIntoTheNextFrame() {
        Texture white = solid(1, 1, 1, 1);
        Gdx.gl.glViewport(0, 0, W / 2, H / 2); // bottom-left quarter; set before the frame, applied by replayState
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(white, 0, 0, S, S); // fills the whole viewport, which maps the 64x64 projection onto 32x32 pixels
            b.end();
        });
        assertPx(f, 16, 48, 255, 255, 255, 1, "inside the viewport (bottom-left)");
        assertPx(f, 48, 16, 0, 0, 0, 0, "outside the viewport (top-right)");
        Gdx.gl.glViewport(0, 0, W, H);
        white.dispose();
    }

    @Test void regionsSpritesFlipsAndRotation() {
        Pixmap pm = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
        pm.setColor(1, 0, 0, 1);
        pm.fillRectangle(0, 0, 4, 2); // top half red
        pm.setColor(0, 0, 1, 1);
        pm.fillRectangle(0, 2, 4, 2); // bottom half blue
        Texture t = new Texture(pm);
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        TextureRegion top = new TextureRegion(t, 0, 0, 4, 2);
        TextureRegion flipped = new TextureRegion(t);
        flipped.flip(false, true);
        Sprite spr = new Sprite(t);
        spr.setBounds(0, 0, 20, 20);
        spr.setOrigin(10, 10);
        spr.rotate90(true);
        spr.setPosition(40, 40);
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(top, 0, 0, 20, 20); // only the red half
            b.draw(flipped, 20, 0, 20, 20); // blue on top, red below
            spr.draw(b);
            b.end();
        });
        assertPx(f, 10, 54, 255, 0, 0, 1, "region of the red half fills the quad");
        assertPx(f, 30, 46, 0, 0, 255, 1, "flipped region: blue is now at the top of its quad");
        assertPx(f, 30, 62, 255, 0, 0, 1, "flipped region: red at the bottom");
        int[] rotated = px(f, 50, 14);
        assertTrue(rotated[0] + rotated[2] > 200, "the rotated sprite drew something: " + java.util.Arrays.toString(rotated));
        t.dispose();
        pm.dispose();
    }

    @Test void aTextureChangedMidBatch() {
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
            t.draw(green, 0, 0); // rewrites the texture while the batch still holds the quad above
            b.draw(t, 32, 0, 32, 64);
            b.end();
        });
        assertPx(f, 48, 32, 0, 255, 0, 1, "drawn after the change: new contents");
        if (tierA) {
            // Copy-on-write (plan, Core decision 4): the earlier quad keeps what the texture held when it was drawn.
            assertPx(f, 16, 32, 255, 0, 0, 1, "drawn before the change: old contents (Tier A)");
        } else {
            // One descriptor set per draw, bound at flush: like GL, pending quads see the texture's latest contents.
            assertPx(f, 16, 32, 0, 255, 0, 1, "Tier B binds the latest image at flush, as GL does");
        }
        t.dispose();
        base.dispose();
        green.dispose();
    }

    @Test void shaderBundleReplacesTheFragmentShaderAndCarriesCustomUniforms() {
        Texture orange = solid(1f, 0.5f, 0f, 1f);
        io.github.slebed.gdxvulkan.VulkanShaderPipelineBundle gray =
            io.github.slebed.gdxvulkan.VulkanShaderPipelineBundle.fromResources("gray", "sprite.vert", "sprite_gray.frag");
        byte[] f = render(BLACK, b -> {
            b.begin();
            b.draw(orange, 0, 32, 16, 16); // default shader: colour as is
            b.setShaderBundle(gray);
            gray.custom0[0] = 1f;
            b.draw(orange, 16, 32, 16, 16); // fully grayscale
            b.flush(); // custom values are read at flush time
            gray.custom0[0] = 0f;
            b.draw(orange, 32, 32, 16, 16); // bundle with the effect off
            b.setShaderBundle(null);
            b.draw(orange, 48, 32, 16, 16); // default again
            b.end();
        });
        int lum = Math.round(0.299f * 255 + 0.587f * 128);
        assertPx(f, 8, 24, 255, 128, 0, 1, "default shader");
        assertPx(f, 24, 24, lum, lum, lum, 2, "grayscale bundle");
        assertPx(f, 40, 24, 255, 128, 0, 1, "bundle with custom0.x = 0");
        assertPx(f, 56, 24, 255, 128, 0, 1, "default restored");
        orange.dispose();
    }

    @Test void shaderBundleWorksForTheShapeRenderer() {
        final io.github.slebed.gdxvulkan.VulkanShaderPipelineBundle invert =
            io.github.slebed.gdxvulkan.VulkanShaderPipelineBundle.fromResources("shape_invert", "shape.vert", "shape_invert.frag");
        byte[] f = render(BLACK, b -> {
            io.github.slebed.gdxvulkan.g2d.VulkanShapeRenderer shapes = new io.github.slebed.gdxvulkan.g2d.VulkanShapeRenderer(frame);
            shapes.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
            shapes.begin(io.github.slebed.gdxvulkan.g2d.VulkanShapeRenderer.ShapeType.Filled);
            shapes.setColor(1f, 0.5f, 0f, 1f);
            shapes.rect(0, 32, 16, 16); // default shader
            ((io.github.slebed.gdxvulkan.g2d.VulkanImmediateModeRenderer)shapes.getRenderer()).setShaderBundle(invert);
            invert.custom0[1] = 1f;
            shapes.rect(16, 32, 16, 16); // inverted
            ((io.github.slebed.gdxvulkan.g2d.VulkanImmediateModeRenderer)shapes.getRenderer()).setShaderBundle(null);
            shapes.rect(32, 32, 16, 16); // default again, with nothing left over from the bundle
            shapes.end();
            shapes.dispose();
        });
        assertPx(f, 8, 24, 255, 128, 0, 1, "default shader");
        assertPx(f, 24, 24, 0, 127, 255, 2, "inverting bundle");
        assertPx(f, 40, 24, 255, 128, 0, 1, "default restored");
    }

    @Test void debugOverlayReportsTheRendererAndDrawsText() {
        final io.github.slebed.gdxvulkan.g2d.VulkanDebugOverlay overlay = new io.github.slebed.gdxvulkan.g2d.VulkanDebugOverlay(frame);
        final String[] text = new String[1];
        byte[] f = render(BLACK, b -> {
            overlay.render();
            text[0] = overlay.text().toString();
        });
        assertTrue(text[0].contains("renames/frame") && text[0].contains("retired pending") && text[0].contains("samplers"), text[0]);
        assertTrue(text[0].contains(frame.isTierA() ? "tier A" : "tier B"), text[0]);
        int lit = 0; // glyphs are white on the black clear: the top-left corner must have some bright pixels
        for (int y = 0; y < H / 3; y++) {
            for (int x = 0; x < W / 2; x++) {
                if ((f[(y * W + x) * 4] & 0xFF) > 128) lit++;
            }
        }
        assertTrue(lit > 50, "the overlay drew text in the top-left corner (" + lit + " bright pixels)");
        overlay.dispose();
    }

    @Test void texturesRenamedDuringAFrameAreCounted() {
        Texture t = solid(1, 0, 0, 1);
        render(BLACK, b -> { // the first draw creates the image: not a rename
            b.begin();
            b.draw(t, 0, 0, 8, 8);
            b.end();
        });
        assertEquals(0, frame.getRenamesLastFrame(), "creating the image is not a rename");
        Pixmap green = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        green.setColor(0, 1, 0, 1);
        green.fill();
        render(BLACK, b -> {
            b.begin();
            b.draw(t, 0, 0, 8, 8);
            t.draw(green, 0, 0); // each pixel change after the image exists gives the texture a new image
            t.draw(green, 0, 0);
            b.end();
        });
        assertEquals(2, frame.getRenamesLastFrame(), "two pixel changes, two renames");
        render(BLACK, b -> {
            b.begin();
            b.draw(t, 0, 0, 8, 8);
            b.end();
        });
        assertEquals(0, frame.getRenamesLastFrame(), "a frame with no pixel changes renames nothing");
        green.dispose();
        t.dispose();
    }

    @Test void apiContractAroundShadersAndDrawing() {
        assertEquals(Color.WHITE_FLOAT_BITS, new VulkanSpriteBatch(frame).getPackedColor());
        GLFW.glfwPollEvents();
        assertTrue(frame.begin(0, 0, 0, 1));
        VulkanSpriteBatch b = new VulkanSpriteBatch(frame);
        assertEquals(null, b.getShader(), "there is no ShaderProgram: pipelines replace GLSL (plan, Phase 2)");
        b.setShader(null); // the default is accepted; a ShaderProgram cannot even be constructed on the shim
        Texture t = solid(1, 1, 1, 1);
        assertThrows(IllegalStateException.class, () -> b.draw(t, 0, 0)); // draw before begin
        b.begin();
        assertThrows(IllegalStateException.class, b::begin); // begin twice
        b.end();
        assertThrows(IllegalStateException.class, b::end); // end twice
        t.dispose();
        b.dispose();
        frame.end();
    }

    @Test void polygonBatchDrawsRealPolygons() {
        Texture red = solid(1, 0, 0, 1), blue = solid(0, 0, 1, 1);
        // a triangle (not a quad) and a pentagon, in different textures so Tier A mixes slots within one flush
        PolygonRegion triangle = new PolygonRegion(new TextureRegion(red), new float[] {0, 0, 40, 0, 20, 40}, new short[] {0, 1, 2});
        float[] pentagon = new float[10];
        for (int i = 0; i < 5; i++) {
            pentagon[i * 2] = 18 * (float)Math.cos(Math.PI / 2 + i * 2 * Math.PI / 5);
            pentagon[i * 2 + 1] = 18 * (float)Math.sin(Math.PI / 2 + i * 2 * Math.PI / 5);
        }
        PolygonRegion five = new PolygonRegion(new TextureRegion(blue), pentagon, new EarClippingTriangulator().computeTriangles(pentagon).toArray());
        int[] calls = new int[1];
        GLFW.glfwPollEvents();
        assertTrue(frame.begin(0, 0, 0, 1));
        VulkanPolygonSpriteBatch b = new VulkanPolygonSpriteBatch(frame);
        b.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
        b.begin();
        b.draw(triangle, 4, 4);
        b.draw(five, 44, 24);
        b.draw(new PolygonRegion(new TextureRegion(red), new float[] {0, 0, 10, 0, 10, 10, 0, 10}, new short[] {0, 1, 2, 2, 3, 0}), 40, 52);
        b.end();
        calls[0] = b.renderCalls;
        b.dispose();
        byte[] f = frame.endAndCapture();
        assertPx(f, 24, 64 - 12, 255, 0, 0, 1, "inside the triangle");
        assertPx(f, 6, 64 - 30, 0, 0, 0, 0, "outside the triangle, left of its slanted edge");
        assertPx(f, 44, 64 - 24, 0, 0, 255, 1, "inside the pentagon");
        assertPx(f, 44 + 22, 64 - 24, 0, 0, 0, 0, "outside the pentagon");
        assertPx(f, 45, 64 - 57, 255, 0, 0, 1, "an explicit quad polygon");
        assertEquals(tierA ? 1 : 3, calls[0], "draw calls: Tier A mixes textures in one draw, Tier B flushes per texture change");
        red.dispose();
        blue.dispose();
    }
}
