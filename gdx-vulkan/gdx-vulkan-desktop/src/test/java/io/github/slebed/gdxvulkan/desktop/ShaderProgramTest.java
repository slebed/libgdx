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
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.VulkanCaps;
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
}
