package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.BufferUtils;

/**
 * {@code glReadPixels} on the stock classes: a stock {@code FrameBuffer} and {@code ShapeRenderer} draw, the pixels are read from the framebuffer and from the screen in the
 * middle of the frame (the frame is submitted and waited for), and the frame goes on afterwards: what is drawn after the read shows in the finished frame.
 */
class ReadPixelsTest {
    private static final int W = 192, H = 128;

    private static final class Probe extends ApplicationAdapter {
        int frames;
        Throwable failure;
        ByteBuffer fbPixels, screenPixels;
        byte[] finalFrame;
        FrameBuffer fb;
        ShapeRenderer shapes;

        @Override public void create () {
            fb = new FrameBuffer(Pixmap.Format.RGBA8888, 64, 48, false);
            shapes = new ShapeRenderer();
        }

        private void rect (int width, int height, float x, float y, float w, float h, float r, float g, float b) {
            shapes.setProjectionMatrix(new com.badlogic.gdx.math.Matrix4().setToOrtho2D(0, 0, width, height));
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(r, g, b, 1f);
            shapes.rect(x, y, w, h);
            shapes.end();
        }

        @Override public void render () {
            try {
                frames++;
                if (frames == 2) {
                    ((VulkanDesktopApplication)Gdx.app).requestCapture();
                    // 1. the framebuffer: red, with a green rectangle at the bottom left; read in the middle of the frame
                    fb.begin();
                    Gdx.gl.glClearColor(1f, 0f, 0f, 1f);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                    rect(64, 48, 0, 0, 32, 24, 0f, 1f, 0f);
                    fbPixels = BufferUtils.newByteBuffer(64 * 48 * 4);
                    Gdx.gl.glReadPixels(0, 0, 64, 48, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, fbPixels);
                    fb.end();
                    // 2. the screen: blue with a yellow rectangle at the bottom left, read, then a magenta one drawn after the read
                    Gdx.gl.glViewport(0, 0, W, H);
                    Gdx.gl.glClearColor(0f, 0f, 1f, 1f);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                    rect(W, H, 0, 0, 96, 64, 1f, 1f, 0f);
                    screenPixels = BufferUtils.newByteBuffer(W * H * 4);
                    Gdx.gl.glReadPixels(0, 0, W, H, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, screenPixels);
                    rect(W, H, 120, 80, 60, 40, 1f, 0f, 1f);
                } else if (frames == 3) {
                    finalFrame = ((VulkanDesktopApplication)Gdx.app).capturedFrame();
                    Gdx.app.exit();
                }
                if (frames > 30) Gdx.app.exit();
            } catch (Throwable t) {
                failure = t;
                Gdx.app.exit();
            }
        }

        @Override public void dispose () {
            if (fb != null) fb.dispose();
            if (shapes != null) shapes.dispose();
        }
    }

    private static String px (ByteBuffer b, int width, int x, int y) {
        final int o = (y * width + x) * 4;
        return (b.get(o) & 255) + "," + (b.get(o + 1) & 255) + "," + (b.get(o + 2) & 255);
    }

    @Test void pixelsComeBackFromTheFramebufferAndTheScreenAndTheFrameGoesOn () {
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
        assertNotNull(probe.fbPixels);
        assertNotNull(probe.screenPixels);
        System.out.println("[readpixels] fb (10,10)=" + px(probe.fbPixels, 64, 10, 10) + " fb (50,40)=" + px(probe.fbPixels, 64, 50, 40) + " screen (10,10)=" + px(probe.screenPixels, W, 10, 10) + " (150,100)="
            + px(probe.screenPixels, W, 150, 100) + " (150,30)=" + px(probe.screenPixels, W, 150, 30) + " (100,10)=" + px(probe.screenPixels, W, 100, 10) + " (10,100)=" + px(probe.screenPixels, W, 10, 100));
        // The framebuffer, rows from the bottom: green at the bottom left, red elsewhere.
        assertEquals("0,255,0", px(probe.fbPixels, 64, 10, 10));
        assertEquals("255,0,0", px(probe.fbPixels, 64, 50, 40));
        // The screen, rows from the bottom: yellow at the bottom left, blue elsewhere, and nothing of the magenta rectangle (drawn after the read).
        assertEquals("255,255,0", px(probe.screenPixels, W, 10, 10));
        assertEquals("0,0,255", px(probe.screenPixels, W, 150, 100));
        assertEquals("0,0,255", px(probe.screenPixels, W, 150, 30)); // where the magenta one is, from the bottom: y 80..120 of H = 128 is the top part: row 30 is blue
        // The finished frame (row 0 at the top) has all three: yellow, blue, and the magenta rectangle drawn after the read.
        assertNotNull(probe.finalFrame);
        assertColour(probe.finalFrame, 10, H - 1 - 10, 255, 255, 0);
        assertColour(probe.finalFrame, 150, H - 1 - 10, 0, 0, 255);
        assertColour(probe.finalFrame, 150, H - 1 - 100, 255, 0, 255);
    }

    private static void assertColour (byte[] px, int x, int yFromTop, int r, int g, int b) {
        final int o = (yFromTop * W + x) * 4;
        final String where = "pixel (" + x + "," + yFromTop + ") = " + (px[o] & 255) + "," + (px[o + 1] & 255) + "," + (px[o + 2] & 255);
        assertTrue(Math.abs((px[o] & 255) - r) <= 2 && Math.abs((px[o + 1] & 255) - g) <= 2 && Math.abs((px[o + 2] & 255) - b) <= 2, where + ", expected " + r + "," + g + "," + b);
    }
}
