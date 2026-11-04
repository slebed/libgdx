package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;

/**
 * The desktop application end to end: window, Vulkan context, {@code Gdx.*} statics, the stock input (events fed through the same callbacks GLFW
 * calls), posted runnables, window resizing, a frame drawn with a stock {@code Texture} and read back, and an orderly shutdown with no validation messages.
 */
class DesktopApplicationTest {
    private static final class Probe extends ApplicationAdapter {
        final List<String> events = new ArrayList<String>();
        final int[] resizes = new int[4];
        int resizeCount, frames;
        Throwable failure;
        boolean posted, pressedSeen;
        byte[] picture;
        int validationMessages = -1;
        long window;
        VulkanSpriteBatch batch;
        Texture quads;
        VulkanDesktopApplication app;
        int lastW, lastH;

        @Override public void create () {
            app = (VulkanDesktopApplication)Gdx.app;
            window = app.getWindowHandle();
            batch = new VulkanSpriteBatch(app.getFrame());
            Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
            int[][] c = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 0}};
            for (int q = 0; q < 4; q++) {
                pm.setColor(c[q][0] / 255f, c[q][1] / 255f, c[q][2] / 255f, 1);
                pm.drawPixel(q % 2, q / 2);
            }
            quads = new Texture(pm);
            quads.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            pm.dispose();
            Gdx.input.setInputProcessor(new InputAdapter() {
                @Override public boolean keyDown (int keycode) { events.add("down " + Input.Keys.toString(keycode)); return true; }

                @Override public boolean keyUp (int keycode) { events.add("up " + Input.Keys.toString(keycode)); return true; }

                @Override public boolean keyTyped (char character) { events.add("typed " + character); return true; }

            });
        }

        @Override public void resize (int width, int height) {
            resizeCount++;
            lastW = width;
            lastH = height;
            if (batch != null) batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height); // stock batches keep the projection they were made with
        }

        @Override public void render () {
            try {
                frames++;
                final VulkanDesktopInput in = app.getVulkanInput();
                if (frames == 1) {
                    // The callbacks GLFW makes, called by hand: the events travel the stock queue and reach the processor in the next update().
                    in.keyCallback(window, GLFW.GLFW_KEY_A, 0, GLFW.GLFW_PRESS, 0);
                    pressedSeen = Gdx.input.isKeyPressed(Input.Keys.A);
                    in.keyCallback(window, GLFW.GLFW_KEY_A, 0, GLFW.GLFW_RELEASE, 0);
                    Gdx.app.postRunnable(new Runnable() {
                        @Override public void run () { posted = true; }
                    });
                }
                if (frames == 2) Gdx.graphics.setWindowedMode(240, 160);
                if (frames == 5) app.requestCapture();

                batch.begin();
                batch.draw(quads, 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                batch.end();
                if (frames == 6) {
                    picture = app.capturedFrame();
                    Gdx.app.exit();
                }
                if (frames > 60) Gdx.app.exit(); // the capture never arrived
            } catch (Throwable t) {
                failure = t;
                Gdx.app.exit();
            }
        }

        @Override public void dispose () {
            if (batch != null) batch.dispose();
            if (quads != null) quads.dispose();
            if (app != null) validationMessages = app.getContext().validationMessageCount();
        }
    }

    @Test void runsAnApplicationWithStockInputAndDrawsAFrame () {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        GLFW.glfwTerminate();
        VulkanDesktopConfiguration config = new VulkanDesktopConfiguration();
        config.title = "gdx-vulkan desktop application test";
        config.width = 320;
        config.height = 200;
        config.disableAudio = true;
        config.vk = GdxVkConfig.fromSystemProperties();
        Probe probe = new Probe();
        new VulkanDesktopApplication(probe, config);

        if (probe.failure != null) throw new AssertionError("the listener failed", probe.failure);
        assertTrue(probe.frames >= 6, "frames rendered: " + probe.frames);
        assertTrue(probe.pressedSeen, "isKeyPressed(A) right after the callback");
        assertTrue(probe.events.contains("down A") && probe.events.contains("up A"), "events: " + probe.events);
        assertTrue(probe.posted, "a posted runnable ran");
        assertTrue(probe.resizeCount >= 2, "resize() at the start and after setWindowedMode: " + probe.resizeCount);
        assertEquals(240, probe.lastW, "logical width after setWindowedMode");
        assertEquals(160, probe.lastH, "logical height after setWindowedMode");

        assertNotNull(probe.picture, "a captured frame");
        // The texture's four texels (2x2 nearest) fill the window: red top-left, green top-right, blue bottom-left, yellow bottom-right.
        final int w = probe.lastW, h = probe.lastH;
        assertEquals(w * h * 4, probe.picture.length, "capture size");
        assertColour(probe.picture, w, w / 4, h / 4, 255, 0, 0);
        assertColour(probe.picture, w, 3 * w / 4, h / 4, 0, 255, 0);
        assertColour(probe.picture, w, w / 4, 3 * h / 4, 0, 0, 255);
        assertColour(probe.picture, w, 3 * w / 4, 3 * h / 4, 255, 255, 0);
        assertEquals(0, probe.validationMessages, "validation layer reported messages");
    }

    private static void assertColour (byte[] px, int width, int x, int y, int r, int g, int b) {
        final int o = (y * width + x) * 4;
        final String where = "pixel (" + x + "," + y + ") = " + (px[o] & 255) + "," + (px[o + 1] & 255) + "," + (px[o + 2] & 255);
        assertTrue(Math.abs((px[o] & 255) - r) <= 2 && Math.abs((px[o + 1] & 255) - g) <= 2 && Math.abs((px[o + 2] & 255) - b) <= 2,
            where + ", expected " + r + "," + g + "," + b);
    }
}
