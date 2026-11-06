package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g2d.VulkanShapeRenderer;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.glfw.GLFW;

/**
 * Pre-rotation (plan, Phase 4): the swapchain image is in the display's native orientation and the renderer rotates its output, viewport and
 * scissor rectangles into it, while the application keeps working in the logical orientation. A desktop window cannot be pre-rotated by the
 * compositor, so {@link GdxVkPresenter#debugForceRotation} renders as if it were, on a square window. The captured frame comes back in the
 * logical orientation, so the same scene must give the identical picture at every rotation: this proves the clip-space rotation, the
 * rectangle rotation and the capture inverse agree with each other. (That they agree with a real display's convention is checked on a phone.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreRotationTest {
    private static final int N = 192; // square, and above the smallest window Windows allows

    private GdxVkContext ctx;
    private VulkanGL20 gl;
    private long window;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;

    @BeforeAll void setUp() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        window = GLFW.glfwCreateWindow(N, N, "gdx-vulkan pre-rotation", 0, 0);
        assumeTrue(window != 0, "Could not create a window");
        presenter = GlfwSurfaces.createPresenter(ctx, window, false);
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

    private Texture quadrants() {
        Pixmap pm = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
        float[][] c = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {1, 1, 0}}; // top-left, top-right, bottom-left, bottom-right
        for (int q = 0; q < 4; q++) {
            pm.setColor(c[q][0], c[q][1], c[q][2], 1);
            pm.fillRectangle((q % 2) * 2, (q / 2) * 2, 2, 2);
        }
        Texture t = new Texture(pm);
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        pm.dispose();
        return t;
    }

    /** The same scene at the given rotation, captured in the logical orientation. */
    private byte[] scene(int rotation, Texture quads) {
        presenter.debugForceRotation(rotation);
        TestGdx.width = N;
        TestGdx.height = N;
        GLFW.glfwPollEvents();
        assertTrue(frame.begin(0.1f, 0.2f, 0.3f, 1f), "frame skipped at rotation " + rotation);
        assertEquals(rotation, presenter.rotation(), "the swapchain really is pre-rotated (otherwise this test proves nothing)");
        assertEquals(N, presenter.width(), "logical width at rotation " + rotation);
        assertEquals(N, presenter.height(), "logical height at rotation " + rotation);
        VulkanSpriteBatch b = new VulkanSpriteBatch(frame);
        b.getProjectionMatrix().setToOrtho2D(0, 0, N, N);
        b.begin();
        b.draw(quads, 0, 0, N, N); // the whole window: four coloured quadrants
        b.end();
        // A scissored fill: GL rectangle (origin bottom-left), not symmetric about the centre so a wrong rotation shows.
        gl.glEnable(GL20.GL_SCISSOR_TEST);
        gl.glScissor(12, 30, 60, 36);
        gl.glClearColor(1f, 1f, 1f, 1f);
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        gl.glDisable(GL20.GL_SCISSOR_TEST);
        // A sub-viewport: the same texture again, squeezed into the top-right 72 x 48 of the window.
        gl.glViewport(105, 135, 72, 48);
        VulkanSpriteBatch b2 = new VulkanSpriteBatch(frame);
        b2.getProjectionMatrix().setToOrtho2D(0, 0, 72, 48);
        b2.begin();
        b2.draw(quads, 0, 0, 72, 48);
        b2.end();
        gl.glViewport(0, 0, N, N);
        // A line in a corner, drawn by the shape renderer (another vertex shader).
        VulkanShapeRenderer shapes = new VulkanShapeRenderer(frame);
        shapes.getProjectionMatrix().setToOrtho2D(0, 0, N, N);
        shapes.begin(VulkanShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 0f, 1f, 1f);
        shapes.rect(150, 6, 30, 12);
        shapes.end();
        shapes.dispose();
        b2.dispose();
        b.dispose();
        return frame.endAndCapture();
    }

    @Test void theSameSceneComesOutIdenticalAtEveryRotation() {
        Texture quads = quadrants();
        byte[] upright = scene(0, quads);
        // Sanity: the unrotated picture is what was drawn (top-left red, bottom-right yellow, the scissor white, the rectangle magenta).
        assertPixel(upright, 30, 30, 255, 0, 0);
        assertPixel(upright, 165, 150, 255, 255, 0);
        assertPixel(upright, 40, 140, 255, 255, 255);
        assertPixel(upright, 165, 180, 255, 0, 255);
        for (int rot = 1; rot <= 3; rot++) {
            byte[] got = scene(rot, quads);
            assertArrayEquals(upright, got, "the picture at " + rot * 90 + " degrees differs from the upright one");
        }
        assertArrayEquals(upright, scene(0, quads), "back to no rotation gives the first picture again");
        quads.dispose();
    }

    /**
     * A translated shader that reads gl_FragCoord and the derivatives draws the same picture at every pre-rotation: the translator's rotation table agrees with the
     * presenter's (red grows to the right, green upwards, and the bottom-right shows +1 for dFdx and dFdy in GL's axes).
     */
    @Test void fragCoordAndDerivativesFollowOpenGlAtEveryRotation() {
        final String vertex = "attribute vec4 a_position;\nvoid main() {\n    gl_Position = a_position;\n}\n";
        final String fragment = "#ifdef GL_ES\nprecision highp float;\n#endif\nuniform vec2 u_resolution;\nvoid main() {\n    vec2 p = gl_FragCoord.xy / u_resolution;\n"
            + "    float up = dFdy(gl_FragCoord.y) * 0.5, right = dFdx(gl_FragCoord.x) * 0.5;\n"
            + "    if (p.x < 0.5) gl_FragColor = vec4(p.x * 2.0, p.y, 0.0, 1.0);\n    else if (p.y < 0.25) gl_FragColor = vec4(right, up, 0.0, 1.0);\n}\n";
        final com.badlogic.gdx.graphics.glutils.ShaderProgram program = new com.badlogic.gdx.graphics.glutils.ShaderProgram(vertex, fragment);
        assertTrue(program.isCompiled(), program.getLog());
        final com.badlogic.gdx.graphics.Mesh mesh = new com.badlogic.gdx.graphics.Mesh(true, 6, 0, new com.badlogic.gdx.graphics.VertexAttribute(1, 4, "a_position"));
        mesh.setVertices(new float[] {-1, -1, 0, 1, 1, -1, 0, 1, 1, 1, 0, 1, -1, -1, 0, 1, 1, 1, 0, 1, -1, 1, 0, 1});
        byte[] upright = null;
        for (int rot = 0; rot <= 3; rot++) {
            presenter.debugForceRotation(rot);
            TestGdx.width = N;
            TestGdx.height = N;
            GLFW.glfwPollEvents();
            assertTrue(frame.begin(0f, 0f, 0f, 1f), "frame skipped at rotation " + rot);
            assertEquals(rot, presenter.rotation(), "the swapchain really is pre-rotated at " + rot);
            program.bind();
            program.setUniformf("u_resolution", N, N);
            mesh.render(program, GL20.GL_TRIANGLES);
            final byte[] got = frame.endAndCapture();
            if (rot == 0) {
                upright = got;
                assertNear(got, 36, 12, 96, 236, 0, 3); // left half, near the top: green high
                assertNear(got, 36, 180, 96, 14, 0, 3); // near the bottom: green low
                assertNear(got, 144, 180, 128, 128, 0, 3); // bottom right: the derivatives
            } else {
                assertArrayEquals(upright, got, "the picture at " + rot * 90 + " degrees differs from the upright one");
            }
        }
        presenter.debugForceRotation(0);
        mesh.dispose();
        program.dispose();
    }

    private void assertNear(byte[] px, int x, int y, int r, int g, int b, int tolerance) {
        int o = (y * N + x) * 4;
        assertTrue(Math.abs((px[o] & 255) - r) <= tolerance && Math.abs((px[o + 1] & 255) - g) <= tolerance && Math.abs((px[o + 2] & 255) - b) <= tolerance,
            "pixel " + x + "," + y + " was " + (px[o] & 255) + "," + (px[o + 1] & 255) + "," + (px[o + 2] & 255) + ", expected about " + r + "," + g + "," + b);
    }

    private void assertPixel(byte[] px, int x, int y, int r, int g, int b) {
        int o = (y * N + x) * 4;
        assertEquals(r, px[o] & 255, "red at " + x + "," + y);
        assertEquals(g, px[o + 1] & 255, "green at " + x + "," + y);
        assertEquals(b, px[o + 2] & 255, "blue at " + x + "," + y);
    }
}
