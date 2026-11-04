package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g2d.VulkanStage;
import io.github.slebed.gdxvulkan.g3d.VulkanModelBatch;

/**
 * What a game ported from stock libGDX keeps: its {@code Lwjgl3ApplicationConfiguration}, a Scene2D stage (as {@code VulkanStage}), and a 3D batch
 * that needs no depth-buffer setup. A stock {@code SpriteBatch} gives an error that says what to use instead.
 */
class StockPortingTest {
    private static final class Probe extends ApplicationAdapter {
        int width, height, depthBeforeModelBatch = -1, depthAfterModelBatch = -1, frames;
        String stockBatchError;
        byte[] picture;
        Throwable failure;
        VulkanStage stage;
        Texture red;
        VulkanModelBatch models;

        @Override public void create () {
            final VulkanDesktopApplication app = (VulkanDesktopApplication)Gdx.app;
            depthBeforeModelBatch = app.getPresenter().depthMode();
            models = new VulkanModelBatch();
            depthAfterModelBatch = app.getPresenter().depthMode();
            try {
                new SpriteBatch();
            } catch (GdxRuntimeException e) {
                stockBatchError = e.getMessage();
            }
            Pixmap pm = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
            pm.setColor(Color.RED);
            pm.fill();
            red = new Texture(pm);
            pm.dispose();
            stage = new VulkanStage(new ScreenViewport());
            Image image = new Image(red);
            image.setBounds(0, 0, 1000, 1000); // covers the window
            stage.addActor(image);
        }

        @Override public void resize (int w, int h) {
            width = w;
            height = h;
            stage.getViewport().update(w, h, true);
        }

        @Override public void render () {
            try {
                frames++;
                if (frames == 3) ((VulkanDesktopApplication)Gdx.app).requestCapture();
                stage.act();
                stage.draw();
                if (frames == 4) {
                    picture = ((VulkanDesktopApplication)Gdx.app).capturedFrame();
                    Gdx.app.exit();
                }
                if (frames > 60) Gdx.app.exit();
            } catch (Throwable t) {
                failure = t;
                Gdx.app.exit();
            }
        }

        @Override public void dispose () {
            if (stage != null) stage.dispose();
            if (red != null) red.dispose();
            if (models != null) models.dispose();
        }
    }

    @Test void aStockConfigurationAStageAndA3DBatchPortWithoutSetup () {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        GLFW.glfwTerminate();

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("stock configuration");
        config.setWindowedMode(200, 120);
        config.useVsync(false);
        config.setForegroundFPS(0);
        config.setInitialBackgroundColor(Color.BLACK);
        config.setResizable(false);
        config.disableAudio(true);

        Probe probe = new Probe();
        new VulkanDesktopApplication(probe, config);

        if (probe.failure != null) throw new AssertionError("the listener failed", probe.failure);
        assertEquals(200, probe.width, "window width from the stock configuration");
        assertEquals(120, probe.height, "window height from the stock configuration");
        assertEquals(GdxVkPresenter.DEPTH_NONE, probe.depthBeforeModelBatch);
        assertEquals(GdxVkPresenter.DEPTH, probe.depthAfterModelBatch, "creating a VulkanModelBatch turns the depth buffer on");
        assertTrue(probe.stockBatchError != null && probe.stockBatchError.contains("VulkanSpriteBatch") && probe.stockBatchError.contains("VulkanStage"),
            "the stock SpriteBatch error: " + probe.stockBatchError);
        assertTrue(probe.picture != null && probe.picture.length == 200 * 120 * 4, "a captured frame");
        // The stage's red image fills the window.
        final int o = (60 * 200 + 100) * 4;
        assertTrue((probe.picture[o] & 255) > 240 && (probe.picture[o + 1] & 255) < 15 && (probe.picture[o + 2] & 255) < 15,
            "the stage drew its red image: " + (probe.picture[o] & 255) + "," + (probe.picture[o + 1] & 255) + "," + (probe.picture[o + 2] & 255));
    }
}
