package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

/**
 * {@code gl_FragCoord} has OpenGL's origin, the bottom left, on the screen as well as in a framebuffer: a full-screen quad that paints its position into red and
 * green shows red growing to the right and green growing upwards. The right half writes no colour at all and must stay black (an output that is never written is
 * zero here as it is in practice on OpenGL).
 */
class FragCoordTest {
    private static final int W = 192, H = 96;

    private static final class Probe extends ApplicationAdapter {
        int frames;
        Throwable failure;
        byte[] picture;
        Mesh mesh;
        ShaderProgram program;

        @Override public void create () {
            program = new ShaderProgram("attribute vec4 a_position;\nvoid main() {\n    gl_Position = a_position;\n}\n",
                "#ifdef GL_ES\nprecision mediump float;\n#endif\nuniform vec2 u_resolution;\nvoid main() {\n    vec2 p = gl_FragCoord.xy / u_resolution;\n"
                    + "    if (p.x < 0.5) gl_FragColor = vec4(p.x * 2.0, p.y, 0.0, 1.0);\n}\n");
            if (!program.isCompiled()) throw new IllegalStateException(program.getLog());
            mesh = new Mesh(true, 6, 0, new VertexAttribute(1, 4, "a_position"));
            mesh.setVertices(new float[] {-1, -1, 0, 1, 1, -1, 0, 1, 1, 1, 0, 1, -1, -1, 0, 1, 1, 1, 0, 1, -1, 1, 0, 1});
        }

        @Override public void render () {
            try {
                frames++;
                if (frames == 2) {
                    ((VulkanDesktopApplication)Gdx.app).requestCapture();
                    Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                    program.bind();
                    program.setUniformf("u_resolution", Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                    mesh.render(program, GL20.GL_TRIANGLES);
                } else if (frames == 3) {
                    picture = ((VulkanDesktopApplication)Gdx.app).capturedFrame();
                    Gdx.app.exit();
                }
                if (frames > 30) Gdx.app.exit();
            } catch (Throwable t) {
                failure = t;
                Gdx.app.exit();
            }
        }

        @Override public void dispose () {
            if (mesh != null) mesh.dispose();
            if (program != null) program.dispose();
        }
    }

    @Test void fragCoordCountsFromTheBottomLeftOnTheScreen () {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        GLFW.glfwTerminate();
        final VulkanDesktopConfiguration config = new VulkanDesktopConfiguration();
        config.width = W;
        config.height = H;
        config.resizable = false;
        config.disableAudio = true;
        config.vsync = false;
        final Probe probe = new Probe();
        new VulkanDesktopApplication(probe, config);
        if (probe.failure != null) throw new AssertionError("the listener failed", probe.failure);
        assertNotNull(probe.picture, "a picture");
        // The picture is upright, row 0 on top. Left half: red = x, green = y counted from the bottom.
        final int top = green(probe.picture, 20, 2), bottom = green(probe.picture, 20, H - 3);
        System.out.println("[fragcoord] green near the top " + top + ", near the bottom " + bottom + ", red at x=20 " + red(probe.picture, 20, 48) + ", at x=80 " + red(probe.picture, 80, 48));
        assertTrue(top > 230 && bottom < 25, "green grows upwards: top " + top + ", bottom " + bottom);
        assertTrue(red(probe.picture, 20, 48) < 60 && red(probe.picture, 80, 48) > 180, "red grows to the right");
        // The half that writes nothing is black.
        for (int y : new int[] {5, 48, 90}) for (int x : new int[] {110, 150, 185}) {
            final int o = (y * W + x) * 4;
            assertTrue((probe.picture[o] & 255) + (probe.picture[o + 1] & 255) + (probe.picture[o + 2] & 255) == 0, "pixel (" + x + "," + y + ") is black");
        }
    }

    private static int red (byte[] px, int x, int y) {
        return px[(y * W + x) * 4] & 255;
    }

    private static int green (byte[] px, int x, int y) {
        return px[(y * W + x) * 4 + 1] & 255;
    }
}
