package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import io.github.slebed.gdxvulkan.g2d.VulkanStage;
import io.github.slebed.gdxvulkan.g3d.VulkanModelBatch;

/**
 * A stock {@code ModelBatch} (libGDX's {@code DefaultShader}, translated at run time through the GL shim) and a stock {@code Stage} next to their Vulkan
 * counterparts: the pictures must agree. The 3D scene is lit by a directional light, so the light structs of the default shader are exercised.
 */
class StockScenesTest {
    private static final int W = 192, H = 128;

    private static final class Probe extends ApplicationAdapter {
        int frames;
        Throwable failure;
        byte[] stock3D, vulkan3D, stockUi, vulkanUi;

        PerspectiveCamera camera;
        Environment environment;
        Model model;
        ModelInstance[] instances;
        ModelBatch stockModels;
        VulkanModelBatch vulkanModels;
        Stage stockStage;
        VulkanStage vulkanStage;
        BitmapFont font;
        Texture red;

        @Override public void create () {
            camera = new PerspectiveCamera(60f, W, H);
            camera.position.set(2.5f, 2f, 4f);
            camera.lookAt(0f, 0f, 0f);
            camera.near = 0.1f;
            camera.far = 50f;
            camera.update();
            environment = new Environment();
            environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.25f, 0.25f, 0.3f, 1f));
            environment.add(new DirectionalLight().set(0.9f, 0.85f, 0.8f, -0.5f, -1f, -0.4f));
            final ModelBuilder mb = new ModelBuilder();
            model = mb.createBox(1.6f, 1.6f, 1.6f, new Material(ColorAttribute.createDiffuse(0.85f, 0.2f, 0.15f, 1f)), Usage.Position | Usage.Normal);
            instances = new ModelInstance[] {new ModelInstance(model, 0f, 0f, 0f)};
            instances[0].transform.rotate(0f, 1f, 0f, 30f);
            stockModels = new ModelBatch();
            vulkanModels = new VulkanModelBatch();

            final Pixmap pm = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
            pm.setColor(Color.RED);
            pm.fill();
            red = new Texture(pm);
            pm.dispose();
            font = new BitmapFont();
            stockStage = new Stage(new ScreenViewport());
            vulkanStage = new VulkanStage(new ScreenViewport());
            fill(stockStage);
            fill(vulkanStage);
        }

        private void fill (Stage stage) {
            final Image image = new Image(red);
            image.setBounds(10, 10, 60, 40);
            stage.addActor(image);
            final Label.LabelStyle style = new Label.LabelStyle(font, Color.WHITE);
            final Label label = new Label("Scene2D", style);
            label.setPosition(90, 80);
            stage.addActor(label);
        }

        private static void clear () {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
            Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT | com.badlogic.gdx.graphics.GL20.GL_DEPTH_BUFFER_BIT);
        }

        @Override public void resize (int width, int height) {
            stockStage.getViewport().update(width, height, true);
            vulkanStage.getViewport().update(width, height, true);
        }

        @Override public void render () {
            try {
                frames++;
                final VulkanDesktopApplication app = (VulkanDesktopApplication)Gdx.app;
                if (frames == 3) {
                    app.requestCapture();
                    clear();
                    stockModels.begin(camera);
                    stockModels.render(instances[0], environment);
                    stockModels.end();
                } else if (frames == 4) {
                    stock3D = app.capturedFrame();
                    app.requestCapture();
                    clear();
                    vulkanModels.begin(camera);
                    vulkanModels.render(instances[0], environment);
                    vulkanModels.end();
                } else if (frames == 5) {
                    vulkan3D = app.capturedFrame();
                    app.requestCapture();
                    stockStage.act(0f);
                    stockStage.draw();
                } else if (frames == 6) {
                    stockUi = app.capturedFrame();
                    app.requestCapture();
                    vulkanStage.act(0f);
                    vulkanStage.draw();
                } else if (frames == 7) {
                    vulkanUi = app.capturedFrame();
                    Gdx.app.exit();
                }
                if (frames > 60) Gdx.app.exit();
            } catch (Throwable t) {
                failure = t;
                Gdx.app.exit();
            }
        }

        @Override public void dispose () {
            if (stockModels != null) stockModels.dispose();
            if (vulkanModels != null) vulkanModels.dispose();
            if (stockStage != null) stockStage.dispose();
            if (vulkanStage != null) vulkanStage.dispose();
            if (model != null) model.dispose();
            if (font != null) font.dispose();
            if (red != null) red.dispose();
        }
    }

    @Test void stockModelBatchAndStockStageDrawWhatTheVulkanClassesDraw () {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        GLFW.glfwTerminate();
        final VulkanDesktopConfiguration config = new VulkanDesktopConfiguration();
        config.width = W;
        config.height = H;
        config.resizable = false;
        config.disableAudio = true;
        config.vsync = false;
        config.depthBuffer = true;
        final Probe probe = new Probe();
        new VulkanDesktopApplication(probe, config);

        if (probe.failure != null) throw new AssertionError("the listener failed", probe.failure);
        assertNotNull(probe.stock3D);
        assertNotNull(probe.vulkan3D);
        assertNotNull(probe.stockUi);
        assertNotNull(probe.vulkanUi);

        dump("3D-stock", probe.stock3D);
        dump("3D-vulkan", probe.vulkan3D);
        dump("UI-stock", probe.stockUi);
        dump("UI-vulkan", probe.vulkanUi);
        // Something was drawn at all: the box covers the middle of the window and is reddish.
        final int o = (H / 2 * W + W / 2) * 4;
        assertTrue((probe.stock3D[o] & 255) > 100 && (probe.stock3D[o + 1] & 255) < 120, "the stock ModelBatch drew the box");

        assertSimilar("3D", probe.stock3D, probe.vulkan3D, 6, 0.01);
        assertSimilar("UI", probe.stockUi, probe.vulkanUi, 0, 0.0);
    }

    /** At most {@code fraction} of the pixels may differ by more than {@code tolerance} in any channel. */
    private static void assertSimilar (String what, byte[] a, byte[] b, int tolerance, double fraction) {
        assertEquals(a.length, b.length);
        int off = 0, worst = 0;
        for (int i = 0; i < a.length; i += 4) {
            int d = 0;
            for (int c = 0; c < 3; c++) d = Math.max(d, Math.abs((a[i + c] & 255) - (b[i + c] & 255)));
            worst = Math.max(worst, d);
            if (d > tolerance) off++;
        }
        final double share = off / (double)(a.length / 4);
        if (share > fraction) {
            dump(what + "-stock", a);
            dump(what + "-vulkan", b);
        }
        assertTrue(share <= fraction, what + ": " + off + " pixels (" + share + ") differ by more than " + tolerance + ", worst " + worst + " (build/stockscenes-*.png)");
    }

    private static void dump (String name, byte[] rgba) {
        try {
            final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++) {
                    final int o = (y * W + x) * 4;
                    img.setRGB(x, y, ((rgba[o] & 255) << 16) | ((rgba[o + 1] & 255) << 8) | (rgba[o + 2] & 255));
                }
            javax.imageio.ImageIO.write(img, "png", new java.io.File("build/stockscenes-" + name + ".png"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
