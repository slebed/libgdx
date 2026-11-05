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
 * An attribute the mesh does not have takes its value from {@code glVertexAttrib*} (and GL's default, 0 0 0 1, when nothing was set): a stock {@code ShaderProgram}
 * with an {@code a_color} attribute draws a mesh that has only positions, three times, in three colours.
 */
class ConstantAttributeTest {
    private static final int W = 192, H = 64;

    private static final class Probe extends ApplicationAdapter {
        int frames;
        Throwable failure;
        byte[] picture;
        Mesh mesh;
        ShaderProgram program;

        @Override public void create () {
            program = new ShaderProgram("attribute vec4 a_position;\nattribute vec4 a_color;\nvarying vec4 v_color;\nvoid main() {\n    v_color = a_color;\n    gl_Position = a_position;\n}\n",
                "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvoid main() {\n    gl_FragColor = v_color;\n}\n");
            if (!program.isCompiled()) throw new IllegalStateException(program.getLog());
            mesh = new Mesh(true, 18, 0, new VertexAttribute(1, 4, "a_position"));
            // Three quads side by side in clip space, two triangles each.
            final float[] v = new float[18 * 4];
            final float[][] x = {{-1f, -0.34f}, {-0.33f, 0.33f}, {0.34f, 1f}};
            int o = 0;
            for (float[] q : x) {
                final float[][] corners = {{q[0], -1}, {q[1], -1}, {q[1], 1}, {q[0], -1}, {q[1], 1}, {q[0], 1}};
                for (float[] c : corners) {
                    v[o++] = c[0];
                    v[o++] = c[1];
                    v[o++] = 0f;
                    v[o++] = 1f;
                }
            }
            mesh.setVertices(v);
        }

        @Override public void render () {
            try {
                frames++;
                if (frames == 2) {
                    ((VulkanDesktopApplication)Gdx.app).requestCapture();
                    Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                    final int color = program.getAttributeLocation("a_color");
                    program.bind();
                    Gdx.gl.glVertexAttrib4f(color, 1f, 0f, 0f, 1f);
                    mesh.render(program, GL20.GL_TRIANGLES, 0, 6);
                    Gdx.gl.glVertexAttrib4f(color, 0f, 0.5f, 1f, 1f);
                    mesh.render(program, GL20.GL_TRIANGLES, 6, 6);
                    Gdx.gl.glVertexAttrib3f(color, 0.25f, 1f, 0.25f); // three values: alpha is 1
                    mesh.render(program, GL20.GL_TRIANGLES, 12, 6);
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

    @Test void anAttributeWithoutAnArrayReadsItsGlVertexAttribValue () {
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
        final StringBuilder seen = new StringBuilder();
        for (int x : new int[] {32, 96, 160}) seen.append(" (").append(probe.picture[(32 * W + x) * 4] & 255).append(",").append(probe.picture[(32 * W + x) * 4 + 1] & 255).append(",").append(probe.picture[(32 * W + x) * 4 + 2] & 255).append(")");
        System.out.println("[constant attribute] colours" + seen);
        assertColour(probe.picture, 32, 32, 255, 0, 0);
        assertColour(probe.picture, 96, 32, 0, 128, 255);
        assertColour(probe.picture, 160, 32, 64, 255, 64);
    }

    private static void assertColour (byte[] px, int x, int y, int r, int g, int b) {
        final int o = (y * W + x) * 4;
        final String where = "pixel (" + x + "," + y + ") = " + (px[o] & 255) + "," + (px[o + 1] & 255) + "," + (px[o + 2] & 255);
        assertTrue(Math.abs((px[o] & 255) - r) <= 2 && Math.abs((px[o + 1] & 255) - g) <= 2 && Math.abs((px[o + 2] & 255) - b) <= 2, where + ", expected " + r + "," + g + "," + b);
    }
}
