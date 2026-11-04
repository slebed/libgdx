package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.PolygonRegion;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.EarClippingTriangulator;
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.VulkanCaps;
import io.github.slebed.gdxvulkan.g2d.VulkanImmediateModeRenderer;
import io.github.slebed.gdxvulkan.g2d.VulkanPolygonSpriteBatch;
import io.github.slebed.gdxvulkan.g2d.VulkanShapeRenderer;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;
import io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram;

/**
 * A shader written in libGDX's GLSL, run by {@link VulkanShaderProgram} through a {@link VulkanSpriteBatch}: the stock sprite shader draws what the built-in
 * one does, uniforms set from Java reach the shader (push constants and the uniform block), two programs in one frame keep their own data, and a shader that does not
 * compile says why. Scenes are laid out on a 64x64 grid like in the batch test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShaderProgramTest {
    private static final int S = 64, WINDOW = 192;
    private int W, H;
    private GdxVkContext ctx;
    private VulkanGL20 gl;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private long window;
    private boolean tierA;

    private static final String SPRITE_VERT = "attribute vec4 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\nuniform mat4 u_projTrans;\n"
        + "varying vec4 v_color;\nvarying vec2 v_texCoords;\nvoid main() {\n   v_color = a_color;\n   v_color.a = v_color.a * (255.0/254.0);\n"
        + "   v_texCoords = a_texCoord0;\n   gl_Position =  u_projTrans * a_position;\n}\n";
    private static final String SPRITE_FRAG = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\n"
        + "uniform sampler2D u_texture;\nvoid main() {\n  gl_FragColor = v_color * texture2D(u_texture, v_texCoords);\n}\n";

    /** One uniform in the push constants (u_tint), an array and an int in the uniform block (u_palette, u_index), a bool. */
    private static final String CUSTOM_FRAG = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\n"
        + "uniform sampler2D u_texture;\nuniform vec4 u_tint;\nuniform vec4 u_palette[4];\nuniform int u_index;\nuniform bool u_invert;\n"
        + "void main() {\n  vec4 c = texture2D(u_texture, v_texCoords) * u_tint * u_palette[u_index];\n"
        + "  if (u_invert) c.rgb = vec3(1.0) - c.rgb;\n  gl_FragColor = c;\n}\n";

    @BeforeAll void setUp () {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        tierA = ctx.caps().tier == VulkanCaps.Tier.A;
        gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        window = GLFW.glfwCreateWindow(WINDOW, WINDOW, "gdx-vulkan shader program", 0, 0);
        assumeTrue(window != 0, "Could not create a window");
        presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        W = presenter.width();
        H = presenter.height();
        TestGdx.width = W;
        TestGdx.height = H;
        frame = new GdxVkFrame(ctx, presenter, gl);
    }

    @AfterAll void tearDown () {
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
        void draw (VulkanSpriteBatch batch);
    }

    private byte[] render (Scene scene) {
        GLFW.glfwPollEvents();
        assertTrue(frame.begin(0, 0, 0, 1));
        gl.glViewport(0, 0, W, H);
        final VulkanSpriteBatch batch = new VulkanSpriteBatch(frame);
        try {
            batch.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
            scene.draw(batch);
            batch.dispose();
            return frame.endAndCapture();
        } catch (RuntimeException | Error e) {
            if (frame.isOpen()) frame.end(); // the next test starts with no frame open, and this one's error stays the one reported
            throw e;
        }
    }

    private int[] px (byte[] f, int x, int yFromTop) {
        final int i = ((int)((yFromTop + 0.5) * H / S) * W + (int)((x + 0.5) * W / S)) * 4;
        return new int[] {f[i] & 0xFF, f[i + 1] & 0xFF, f[i + 2] & 0xFF};
    }

    private void assertPx (byte[] f, int x, int yFromTop, int r, int g, int b, String what) {
        final int[] c = px(f, x, yFromTop);
        assertTrue(Math.abs(c[0] - r) <= 2 && Math.abs(c[1] - g) <= 2 && Math.abs(c[2] - b) <= 2,
            what + ": pixel (" + x + "," + yFromTop + ") was " + Arrays.toString(c) + ", expected " + r + "," + g + "," + b);
    }

    private static Texture solid (float r, float g, float b) {
        final Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.setColor(r, g, b, 1f);
        pm.fill();
        final Texture t = new Texture(pm);
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        pm.dispose();
        return t;
    }

    @Test void theStockSpriteShaderDrawsWhatTheBuiltInOneDraws () {
        final Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.drawPixel(0, 0, 0xFF0000FF);
        pm.drawPixel(1, 0, 0x00FF00FF);
        pm.drawPixel(0, 1, 0x0000FFFF);
        pm.drawPixel(1, 1, 0xFFFF00FF);
        final Texture t = new Texture(pm);
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        pm.dispose();
        final VulkanShaderProgram program = new VulkanShaderProgram(frame, SPRITE_VERT, SPRITE_FRAG);
        assertTrue(program.isCompiled(), program.getLog());

        final byte[] builtIn = render(b -> {
            b.begin();
            b.draw(t, 0, 0, S, S);
            b.end();
        });
        final byte[] translated = render(b -> {
            b.setShaderProgram(program);
            b.begin();
            b.draw(t, 0, 0, S, S);
            b.end();
        });
        assertEquals(W * H * 4, translated.length);
        assertTrue(Arrays.equals(builtIn, translated), "the translated stock shader and the built-in one draw the same picture");
        assertPx(translated, 8, 8, 255, 0, 0, "top-left quadrant is red");
        assertPx(translated, 56, 56, 255, 255, 0, "bottom-right quadrant is yellow");
        t.dispose();
    }

    @Test void uniformsReachTheShaderFromPushConstantsAndTheUniformBlock () {
        final VulkanShaderProgram program = new VulkanShaderProgram(frame, SPRITE_VERT, CUSTOM_FRAG);
        assertTrue(program.isCompiled(), program.getLog());
        assertTrue(program.translation().uniform("u_tint").push, "u_tint is a push constant");
        assertFalse(program.translation().uniform("u_palette").push, "the array is in the uniform block");
        final Texture white = solid(1, 1, 1);

        program.setUniformf("u_tint", 1f, 0.5f, 0.25f, 1f);
        program.setUniform4fv("u_palette", new float[] {1, 1, 1, 1, /**/ 0.5f, 1, 1, 1, /**/ 1, 0.5f, 1, 1, /**/ 1, 1, 0.5f, 1}, 0, 16);
        program.setUniformi("u_invert", 0);

        final byte[] f = render(b -> {
            b.setShaderProgram(program);
            b.begin();
            program.setUniformi("u_index", 0);
            b.setColor(1, 1, 1, 1);
            b.draw(white, 0, 32, 32, 32);   // top-left quadrant: tint only
            b.flush();
            program.setUniformi("u_index", 1);
            b.draw(white, 32, 32, 32, 32);  // top-right: tint times palette[1] (red halved)
            b.flush();
            program.setUniformi("u_index", 2);
            program.setUniformi("u_invert", 1);
            b.draw(white, 0, 0, 32, 32);    // bottom-left: palette[2] and inverted
            b.flush();
            program.setUniformi("u_invert", 0);
            program.setUniformf("u_tint", 0f, 0f, 1f, 1f);
            program.setUniformi("u_index", 3);
            b.draw(white, 32, 0, 32, 32);   // bottom-right: a new tint, palette[3]
            b.end();
        });
        assertPx(f, 16, 16, 255, 128, 64, "tint (1, 0.5, 0.25)");
        assertPx(f, 48, 16, 128, 128, 64, "tint times palette[1] (0.5, 1, 1)");
        // palette[2] = (1, 0.5, 1): colour (1, 0.25, 0.25); inverted: (0, 0.75, 0.75)
        assertPx(f, 16, 48, 0, 191, 191, "palette[2], inverted");
        assertPx(f, 48, 48, 0, 0, 128, "tint (0, 0, 1) times palette[3] (1, 1, 0.5)");
        white.dispose();
    }

    @Test void twoProgramsKeepTheirOwnUniformBlocksWithinOneFrame () {
        final VulkanShaderProgram a = new VulkanShaderProgram(frame, SPRITE_VERT, CUSTOM_FRAG);
        final VulkanShaderProgram bProgram = new VulkanShaderProgram(frame, SPRITE_VERT, CUSTOM_FRAG);
        for (VulkanShaderProgram p : new VulkanShaderProgram[] {a, bProgram}) {
            p.setUniformf("u_tint", 1f, 1f, 1f, 1f);
            p.setUniformi("u_invert", 0);
            p.setUniformi("u_index", 0);
        }
        a.setUniform4fv("u_palette", new float[] {1, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1}, 0, 16);       // red
        bProgram.setUniform4fv("u_palette", new float[] {0, 1, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1}, 0, 16); // green
        final Texture white = solid(1, 1, 1);
        final byte[] f = render(b -> {
            b.begin();
            b.setShaderProgram(a);
            b.draw(white, 0, 32, 32, 32);
            b.setShaderProgram(bProgram);
            b.draw(white, 32, 32, 32, 32);
            b.setShaderProgram(a);          // back to a: its block must be bound again, not the one b left
            b.draw(white, 0, 0, 32, 32);
            b.setShaderProgram(bProgram);
            b.draw(white, 32, 0, 32, 32);
            b.setShaderProgram(null);       // and the built-in shader after them
            b.draw(white, 24, 24, 16, 16);
            b.end();
        });
        assertPx(f, 16, 16, 255, 0, 0, "a");
        assertPx(f, 48, 16, 0, 255, 0, "b");
        assertPx(f, 8, 56, 255, 0, 0, "a again");
        assertPx(f, 56, 56, 0, 255, 0, "b again");
        assertPx(f, 32, 32, 255, 255, 255, "the built-in shader after the programs");
        white.dispose();
    }

    @Test void aSecondTextureIsReadFromItsTextureUnit () {
        assumeTrue(tierA, "two textures in one draw need the modern path");
        final String frag = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\n"
            + "uniform sampler2D u_texture;\nuniform sampler2D u_mask;\nvoid main() {\n"
            + "  gl_FragColor = v_color * texture2D(u_texture, v_texCoords) * texture2D(u_mask, v_texCoords);\n}\n";
        final VulkanShaderProgram program = new VulkanShaderProgram(frame, SPRITE_VERT, frag);
        assertTrue(program.isCompiled(), program.getLog());
        final Texture red = solid(1, 0, 0);
        final Texture half = solid(0.5f, 0.5f, 1f);
        program.setUniformi("u_mask", 1);
        final byte[] f = render(b -> {
            b.setShaderProgram(program);
            half.bind(1); // texture unit 1, as in stock
            gl.glActiveTexture(GL20.GL_TEXTURE0);
            b.begin();
            b.draw(red, 0, 0, S, S);
            b.end();
        });
        assertPx(f, 32, 32, 128, 0, 0, "red times the mask on unit 1 (0.5, 0.5, 1)");
        red.dispose();
        half.dispose();
    }

    @Test void aShaderThatDoesNotCompileSaysWhyAndIsRefusedByTheBatch () {
        final String bad = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\nuniform sampler2D u_texture;\n"
            + "void main() {\n  gl_FragColor = v_color * texture2D(u_texture, v_texCoords) * oops;\n}\n";
        final VulkanShaderProgram program = new VulkanShaderProgram(frame, SPRITE_VERT, bad);
        assertFalse(program.isCompiled());
        assertTrue(program.getLog().contains("oops") && program.getLog().contains(":8"), program.getLog());
        final VulkanSpriteBatch batch = new VulkanSpriteBatch(frame);
        assertThrows(GdxRuntimeException.class, () -> batch.setShaderProgram(program));
        batch.dispose();
    }

    @Test void stockSemanticsOfTheSetters () {
        final VulkanShaderProgram program = new VulkanShaderProgram(frame, SPRITE_VERT, CUSTOM_FRAG);
        assertThrows(IllegalArgumentException.class, () -> program.setUniformf("u_nothing", 1f), "pedantic, as in stock");
        assertEquals(-1, program.fetchUniformLocation("u_nothing", false));
        assertTrue(program.hasUniform("u_tint"));
        assertTrue(program.hasAttribute("a_color"));
        assertEquals(0, program.getAttributeLocation("a_position"));
        assertThrows(GdxRuntimeException.class, () -> program.setUniformf("u_tint", 1f, 2f), "a vec4 cannot be set with two values");
        final boolean was = VulkanShaderProgram.pedantic;
        VulkanShaderProgram.pedantic = false;
        try {
            program.setUniformf("u_nothing", 1f); // ignored
        } finally {
            VulkanShaderProgram.pedantic = was;
        }
    }

    /** A frame drawn by {@code draw}, read back; a failure inside closes the frame so the error that is reported is the first one. */
    private byte[] renderRaw (Runnable draw) {
        GLFW.glfwPollEvents();
        assertTrue(frame.begin(0, 0, 0, 1));
        gl.glViewport(0, 0, W, H);
        try {
            draw.run();
            return frame.endAndCapture();
        } catch (RuntimeException | Error e) {
            if (frame.isOpen()) frame.end();
            throw e;
        }
    }

    @Test void aProgramIsReadFromTheCacheTheSecondTimeWithoutTheCompiler () throws Exception {
        final java.io.File dir = java.nio.file.Files.createTempDirectory("gdxvk-shader-cache").toFile();
        VulkanShaderProgram.setCacheDirectory(dir);
        try {
            final int hits = VulkanShaderProgram.cacheHits;
            final VulkanShaderProgram first = new VulkanShaderProgram(frame, SPRITE_VERT, CUSTOM_FRAG);
            assertTrue(first.isCompiled(), first.getLog());
            assertEquals(hits, VulkanShaderProgram.cacheHits, "the first one was compiled");
            assertEquals(1, dir.listFiles((d, name) -> name.endsWith(".spvprog")).length, "and saved");

            final int calls = io.github.slebed.gdxvulkan.GlslCompiler.callCount();
            final VulkanShaderProgram second = new VulkanShaderProgram(frame, SPRITE_VERT, CUSTOM_FRAG);
            assertTrue(second.isCompiled(), second.getLog());
            assertEquals(hits + 1, VulkanShaderProgram.cacheHits, "the second one came from the cache");
            assertEquals(calls, io.github.slebed.gdxvulkan.GlslCompiler.callCount(), "without a call to the compiler");
            assertEquals(first.translation().pushBytes, second.translation().pushBytes);
            assertEquals(first.translation().blockBytes, second.translation().blockBytes);
            assertEquals(first.getAttributeLocation("a_color"), second.getAttributeLocation("a_color"));
            assertEquals(first.translation().uniform("u_palette").offset, second.translation().uniform("u_palette").offset);

            // The cached program draws like the compiled one.
            final Texture white = solid(1, 1, 1);
            final byte[][] pictures = new byte[2][];
            final VulkanShaderProgram[] both = {first, second};
            for (int i = 0; i < 2; i++) {
                final VulkanShaderProgram p = both[i];
                p.setUniformf("u_tint", 1f, 0.5f, 0.25f, 1f);
                p.setUniform4fv("u_palette", new float[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, 0, 16);
                p.setUniformi("u_index", 2);
                p.setUniformi("u_invert", 0);
                pictures[i] = render(b -> {
                    b.setShaderProgram(p);
                    b.begin();
                    b.draw(white, 0, 0, S, S);
                    b.end();
                });
            }
            assertTrue(Arrays.equals(pictures[0], pictures[1]), "the cached program draws the same picture");
            assertPx(pictures[1], 32, 32, 255, 128, 64, "the cached program's uniforms work");
            white.dispose();

            // A different source is a different entry; a damaged file is ignored and replaced.
            final VulkanShaderProgram other = new VulkanShaderProgram(frame, SPRITE_VERT, SPRITE_FRAG);
            assertTrue(other.isCompiled(), other.getLog());
            assertEquals(2, dir.listFiles((d, name) -> name.endsWith(".spvprog")).length);
            for (java.io.File f : dir.listFiles((d, name) -> name.endsWith(".spvprog"))) {
                java.nio.file.Files.write(f.toPath(), new byte[] {1, 2, 3});
            }
            final int hitsBefore = VulkanShaderProgram.cacheHits;
            final VulkanShaderProgram healed = new VulkanShaderProgram(frame, SPRITE_VERT, CUSTOM_FRAG);
            assertTrue(healed.isCompiled(), healed.getLog());
            assertEquals(hitsBefore, VulkanShaderProgram.cacheHits, "a damaged file is not a hit");
        } finally {
            VulkanShaderProgram.setCacheDirectory(null);
            final java.io.File[] files = dir.listFiles();
            if (files != null) for (java.io.File f : files) f.delete();
            dir.delete();
        }
    }

    @Test void aProgramPreparedInTheBackgroundIsTakenWithoutCompilingOnTheRenderThread () throws Exception {
        VulkanShaderProgram.setCacheDirectory(null); // the memory path, not the disk one
        final String source = CUSTOM_FRAG + "// prepared ahead\n";
        final java.util.concurrent.Future<String> warm = VulkanShaderProgram.warmUp(frame);
        final java.util.concurrent.Future<String> future = VulkanShaderProgram.prepareAsync(frame, SPRITE_VERT, source);
        assertEquals("", warm.get(60, java.util.concurrent.TimeUnit.SECONDS), "the warm-up compiles");
        assertEquals("", future.get(60, java.util.concurrent.TimeUnit.SECONDS), "the program compiled in the background");

        final int calls = io.github.slebed.gdxvulkan.GlslCompiler.callCount();
        final int hits = VulkanShaderProgram.preparedHits;
        final VulkanShaderProgram program = new VulkanShaderProgram(frame, SPRITE_VERT, source);
        assertTrue(program.isCompiled(), program.getLog());
        assertEquals(hits + 1, VulkanShaderProgram.preparedHits, "the constructor took the prepared result");
        assertEquals(calls, io.github.slebed.gdxvulkan.GlslCompiler.callCount(), "and did not call the compiler");

        program.setUniformf("u_tint", 0.5f, 0.5f, 0.5f, 1f);
        program.setUniform4fv("u_palette", new float[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, 0, 16);
        program.setUniformi("u_index", 0);
        program.setUniformi("u_invert", 0);
        final Texture white = solid(1, 1, 1);
        final byte[] f = render(b -> {
            b.setShaderProgram(program);
            b.begin();
            b.draw(white, 0, 0, S, S);
            b.end();
        });
        assertPx(f, 32, 32, 128, 128, 128, "the prepared program draws");
        white.dispose();

        // A shader that does not compile reports why through the future, and is not kept.
        final String bad = "varying vec2 v_texCoords;\nvoid main() { gl_FragColor = vec4(oops); }\n";
        final String log = VulkanShaderProgram.prepareAsync(frame, SPRITE_VERT, bad).get(60, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(log.contains("oops"), log);
        final int hitsBefore = VulkanShaderProgram.preparedHits;
        assertFalse(new VulkanShaderProgram(frame, SPRITE_VERT, bad).isCompiled());
        assertEquals(hitsBefore, VulkanShaderProgram.preparedHits, "a failure is not kept as a prepared program");
    }

    @Test void theLibrarysOwnShadersNeverUseTheRunTimeCompiler () {
        final Texture t = solid(0.5f, 0.5f, 0.5f);
        final float[] square = {0, 0, 16, 0, 16, 16, 0, 16};
        final PolygonRegion region = new PolygonRegion(new TextureRegion(t), square, new EarClippingTriangulator().computeTriangles(square).toArray());
        final int calls = io.github.slebed.gdxvulkan.GlslCompiler.callCount();
        renderRaw(() -> {
            final VulkanSpriteBatch sprites = new VulkanSpriteBatch(frame);
            sprites.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
            sprites.begin();
            sprites.draw(t, 0, 0, 16, 16);
            sprites.end();
            sprites.dispose();
            final VulkanPolygonSpriteBatch polygons = new VulkanPolygonSpriteBatch(frame);
            polygons.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
            polygons.begin();
            polygons.draw(region, 20, 20);
            polygons.end();
            polygons.dispose();
            final VulkanShapeRenderer shapes = new VulkanShapeRenderer(frame);
            shapes.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
            shapes.begin(VulkanShapeRenderer.ShapeType.Line);
            shapes.rect(40, 40, 10, 10);
            shapes.end();
            shapes.dispose();
        });
        assertEquals(calls, io.github.slebed.gdxvulkan.GlslCompiler.callCount(), "the batches and the shape renderer draw with shaders compiled at build time");
        t.dispose();
    }

    @Test void thePolygonBatchTakesAProgramToo () {
        final VulkanShaderProgram program = new VulkanShaderProgram(frame, SPRITE_VERT, SPRITE_FRAG);
        assertTrue(program.isCompiled(), program.getLog());
        final Texture blue = solid(0.25f, 0.5f, 1f);
        final float[] square = {0, 0, 32, 0, 32, 32, 0, 32};
        final PolygonRegion region = new PolygonRegion(new TextureRegion(blue), square, new EarClippingTriangulator().computeTriangles(square).toArray());
        final boolean[] withProgram = {false};
        final Runnable draw = () -> {
            final VulkanPolygonSpriteBatch polygons = new VulkanPolygonSpriteBatch(frame);
            polygons.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
            if (withProgram[0]) polygons.setShaderProgram(program);
            polygons.begin();
            polygons.draw(region, 8, 8);
            polygons.end();
            polygons.dispose();
        };
        final byte[] builtIn = renderRaw(draw);
        withProgram[0] = true;
        final byte[] translated = renderRaw(draw);
        assertTrue(Arrays.equals(builtIn, translated), "the polygon batch draws the same picture with the translated stock shader");
        assertPx(translated, 24, 40, 64, 128, 255, "the polygon's colour");
        blue.dispose();
    }

    @Test void theShapeRendererTakesAProgramToo () {
        final String vert = "attribute vec4 a_position;\nattribute vec4 a_color;\nuniform mat4 u_projModelView;\nvarying vec4 v_col;\n"
            + "void main() {\n   gl_Position = u_projModelView * a_position;\n   v_col = a_color;\n   v_col.a *= 255.0 / 254.0;\n   gl_PointSize = 1.0;\n}\n";
        final String frag = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_col;\nuniform vec4 u_tint;\n"
            + "void main() {\n   gl_FragColor = v_col * u_tint;\n}\n";
        final VulkanShaderProgram program = new VulkanShaderProgram(frame, vert, frag);
        assertTrue(program.isCompiled(), program.getLog());
        program.setUniformf("u_tint", 0.5f, 1f, 1f, 1f);
        final byte[] f = renderRaw(() -> {
            final VulkanShapeRenderer shapes = new VulkanShapeRenderer(frame);
            shapes.getProjectionMatrix().setToOrtho2D(0, 0, S, S);
            final VulkanImmediateModeRenderer renderer = (VulkanImmediateModeRenderer)shapes.getRenderer();
            renderer.setShaderProgram(program);
            shapes.begin(VulkanShapeRenderer.ShapeType.Filled);
            shapes.setColor(1f, 0.5f, 0.25f, 1f);
            shapes.rect(8, 8, 32, 32);
            shapes.end();
            renderer.setShaderProgram(null);
            shapes.begin(VulkanShapeRenderer.ShapeType.Filled);
            shapes.setColor(0f, 1f, 0f, 1f);
            shapes.rect(44, 44, 16, 16);
            shapes.end();
            shapes.dispose();
        });
        assertPx(f, 24, 40, 128, 128, 64, "the shape, tinted by the program (0.5, 1, 1) on (1, 0.5, 0.25)");
        assertPx(f, 52, 12, 0, 255, 0, "the built-in shader draws after the program is removed");
    }
}
