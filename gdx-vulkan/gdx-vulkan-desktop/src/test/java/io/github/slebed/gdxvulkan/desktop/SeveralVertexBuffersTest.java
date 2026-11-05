package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.FloatBuffer;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;

/**
 * A draw that reads its attributes from two vertex buffers (positions in one, colours in the other), as a game with separate buffers per attribute does through plain GL calls:
 * the shim gives each buffer a binding of its own.
 */
class SeveralVertexBuffersTest {
    private static final int W = 192, H = 64;

    private static final class Probe extends ApplicationAdapter {
        int frames;
        Throwable failure;
        byte[] picture;
        ShaderProgram program;
        int positions, colours;

        @Override public void create () {
            program = new ShaderProgram("attribute vec4 a_position;\nattribute vec4 a_color;\nvarying vec4 v_color;\nvoid main() {\n    v_color = a_color;\n    gl_Position = a_position;\n}\n",
                "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvoid main() {\n    gl_FragColor = v_color;\n}\n");
            if (!program.isCompiled()) throw new IllegalStateException(program.getLog());
            final float[][] x = {{-1f, -0.34f}, {-0.33f, 0.33f}, {0.34f, 1f}};
            final float[][] colour = {{1f, 0f, 0f}, {0f, 1f, 0f}, {0f, 0f, 1f}};
            final FloatBuffer p = BufferUtils.newFloatBuffer(18 * 4), c = BufferUtils.newFloatBuffer(18 * 4);
            for (int q = 0; q < 3; q++) {
                final float[][] corners = {{x[q][0], -1}, {x[q][1], -1}, {x[q][1], 1}, {x[q][0], -1}, {x[q][1], 1}, {x[q][0], 1}};
                for (float[] corner : corners) {
                    p.put(corner[0]).put(corner[1]).put(0f).put(1f);
                    c.put(colour[q][0]).put(colour[q][1]).put(colour[q][2]).put(1f);
                }
            }
            p.flip();
            c.flip();
            positions = Gdx.gl.glGenBuffer();
            Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, positions);
            Gdx.gl.glBufferData(GL20.GL_ARRAY_BUFFER, 18 * 16, p, GL20.GL_STATIC_DRAW);
            colours = Gdx.gl.glGenBuffer();
            Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, colours);
            Gdx.gl.glBufferData(GL20.GL_ARRAY_BUFFER, 18 * 16, c, GL20.GL_STATIC_DRAW);
            Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        }

        @Override public void render () {
            try {
                frames++;
                if (frames == 2) {
                    ((VulkanDesktopApplication)Gdx.app).requestCapture();
                    Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                    program.bind();
                    final int a = program.getAttributeLocation("a_position"), b = program.getAttributeLocation("a_color");
                    Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, positions);
                    Gdx.gl.glEnableVertexAttribArray(a);
                    Gdx.gl.glVertexAttribPointer(a, 4, GL20.GL_FLOAT, false, 16, 0);
                    Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, colours);
                    Gdx.gl.glEnableVertexAttribArray(b);
                    Gdx.gl.glVertexAttribPointer(b, 4, GL20.GL_FLOAT, false, 16, 0);
                    Gdx.gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 18);
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
            if (program != null) program.dispose();
        }
    }

    @Test void aDrawCanReadItsAttributesFromTwoVertexBuffers () {
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
        assertNotNull(probe.picture);
        assertColour(probe.picture, 32, 32, 255, 0, 0);
        assertColour(probe.picture, 96, 32, 0, 255, 0);
        assertColour(probe.picture, 160, 32, 0, 0, 255);
    }

    private static void assertColour (byte[] px, int x, int y, int r, int g, int b) {
        final int o = (y * W + x) * 4;
        final String where = "pixel (" + x + "," + y + ") = " + (px[o] & 255) + "," + (px[o + 1] & 255) + "," + (px[o + 2] & 255);
        assertTrue(Math.abs((px[o] & 255) - r) <= 2 && Math.abs((px[o + 1] & 255) - g) <= 2 && Math.abs((px[o + 2] & 255) - b) <= 2, where + ", expected " + r + "," + g + "," + b);
    }
}
