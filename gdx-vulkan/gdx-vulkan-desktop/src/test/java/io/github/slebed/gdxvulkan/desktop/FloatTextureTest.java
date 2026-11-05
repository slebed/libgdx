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
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;

/**
 * A one-channel float texture (GL_R32F, GL_RED, GL_FLOAT, a height map) that pixel data is uploaded to and a shader samples, and a sampler with no texture bound, which
 * reads black as in OpenGL.
 */
class FloatTextureTest {
    private static final int W = 192, H = 64, GL_R32F = 0x822E, GL_RED = 0x1903;

    private static final class Probe extends ApplicationAdapter {
        int frames;
        Throwable failure;
        byte[] picture;
        ShaderProgram program;
        Mesh left, right;
        int texture;

        private static Mesh quad (float x0, float x1) {
            final Mesh mesh = new Mesh(true, 4, 6, new VertexAttribute(1, 4, "a_position"), new VertexAttribute(16, 2, "a_uv"));
            mesh.setVertices(new float[] {x0, -1, 0, 1, 0, 0, x1, -1, 0, 1, 1, 0, x1, 1, 0, 1, 1, 1, x0, 1, 0, 1, 0, 1});
            mesh.setIndices(new short[] {0, 1, 2, 2, 3, 0});
            return mesh;
        }

        @Override public void create () {
            program = new ShaderProgram("attribute vec4 a_position;\nattribute vec2 a_uv;\nvarying vec2 v_uv;\nvoid main() {\n    v_uv = a_uv;\n    gl_Position = a_position;\n}\n",
                "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec2 v_uv;\nuniform sampler2D u_tex;\nvoid main() {\n    gl_FragColor = vec4(texture2D(u_tex, v_uv).r, 0.0, 1.0, 1.0);\n}\n");
            if (!program.isCompiled()) throw new IllegalStateException(program.getLog());
            left = quad(-1f, 0f);
            right = quad(0f, 1f);
            final FloatBuffer data = BufferUtils.newFloatBuffer(2);
            data.put(0.25f).put(1f).flip();
            texture = Gdx.gl.glGenTexture();
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, texture);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE);
            Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL_R32F, 2, 1, 0, GL_RED, GL20.GL_FLOAT, data);
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
        }

        @Override public void render () {
            try {
                frames++;
                if (frames == 2) {
                    ((VulkanDesktopApplication)Gdx.app).requestCapture();
                    Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                    program.bind();
                    program.setUniformi("u_tex", 0);
                    // The left half samples the float texture: u = 0..1 over two texels, 0.25 then 1.0 in red.
                    Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
                    Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, texture);
                    left.render(program, GL20.GL_TRIANGLES);
                    // The right half has nothing bound to the unit: black, so red 0.
                    Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
                    right.render(program, GL20.GL_TRIANGLES);
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
            if (left != null) left.dispose();
            if (right != null) right.dispose();
        }
    }

    @Test void aSingleChannelFloatTextureIsUploadedAndSampled () {
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
        assertColour(probe.picture, 20, 32, 64, 0, 255);   // 0.25 of 255 in red (the left texel, u under 0.5 of the left half)
        assertColour(probe.picture, 80, 32, 255, 0, 255);  // 1.0 (the right texel)
        assertColour(probe.picture, 150, 32, 0, 0, 255);   // no texture bound: black, red 0
    }

    private static void assertColour (byte[] px, int x, int y, int r, int g, int b) {
        final int o = (y * W + x) * 4;
        final String where = "pixel (" + x + "," + y + ") = " + (px[o] & 255) + "," + (px[o + 1] & 255) + "," + (px[o + 2] & 255);
        assertTrue(Math.abs((px[o] & 255) - r) <= 3 && Math.abs((px[o + 1] & 255) - g) <= 3 && Math.abs((px[o + 2] & 255) - b) <= 3, where + ", expected " + r + "," + g + "," + b);
    }
}
