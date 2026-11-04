package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

import io.github.slebed.gdxvulkan.g2d.VulkanShapeRenderer;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;

/**
 * Stock libGDX classes, unchanged, in a gdx-vulkan application: {@code SpriteBatch}, {@code ShapeRenderer}, {@code BitmapFont} and a custom
 * {@code ShaderProgram} create their shaders and meshes and draw through the GL shim. The same scene is drawn a frame later by the Vulkan classes, and the two
 * pictures must be identical.
 */
class StockClassesTest {
    private static final int W = 192, H = 128;

    private static final class Probe extends ApplicationAdapter {
        int frames;
        Throwable failure;
        byte[] stockPicture, vulkanPicture, customPicture;
        String stockError;
        int knownHits, compilerCalls;
        boolean customCompiled;
        String customLog;

        Texture quads;
        BitmapFont font;
        SpriteBatch stockBatch;
        ShapeRenderer stockShapes;
        VulkanSpriteBatch vulkanBatch;
        VulkanShapeRenderer vulkanShapes;
        ShaderProgram custom;

        @Override public void create () {
            final Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
            pm.drawPixel(0, 0, 0xFF0000FF);
            pm.drawPixel(1, 0, 0x00FF00FF);
            pm.drawPixel(0, 1, 0x0000FFFF);
            pm.drawPixel(1, 1, 0xFFFF00FF);
            quads = new Texture(pm);
            quads.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            pm.dispose();
            font = new BitmapFont();
            final int hitsBefore = io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram.knownShaderHits();
            final int callsBefore = io.github.slebed.gdxvulkan.GlslCompiler.callCount();
            try {
                stockBatch = new SpriteBatch();
                stockShapes = new ShapeRenderer();
                new com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch().dispose();
            } catch (Throwable t) {
                stockError = String.valueOf(t);
                throw t;
            }
            knownHits = io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram.knownShaderHits() - hitsBefore;
            compilerCalls = io.github.slebed.gdxvulkan.GlslCompiler.callCount() - callsBefore;
            vulkanBatch = new VulkanSpriteBatch();
            vulkanShapes = new VulkanShapeRenderer();

            // A game's own shader, in stock libGDX's class: a tint from a uniform.
            custom = new ShaderProgram("attribute vec4 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\nuniform mat4 u_projTrans;\n"
                + "varying vec4 v_color;\nvarying vec2 v_texCoords;\nvoid main() {\n   v_color = a_color;\n   v_texCoords = a_texCoord0;\n   gl_Position = u_projTrans * a_position;\n}\n",
                "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\nuniform sampler2D u_texture;\nuniform vec4 u_tint;\n"
                    + "void main() {\n  gl_FragColor = v_color * texture2D(u_texture, v_texCoords) * u_tint;\n}\n");
            customCompiled = custom.isCompiled();
            customLog = custom.getLog();
        }

        private void sceneWithStock () {
            stockBatch.begin();
            stockBatch.draw(quads, 0, 0, 96, 96);
            font.draw(stockBatch, "Vulkan", 100, 110);
            stockBatch.end();
            stockShapes.begin(ShapeRenderer.ShapeType.Filled);
            stockShapes.setColor(1f, 0.5f, 0.25f, 1f);
            stockShapes.rect(110, 10, 60, 40);
            stockShapes.end();
            stockShapes.begin(ShapeRenderer.ShapeType.Line);
            stockShapes.setColor(0f, 1f, 1f, 1f);
            stockShapes.line(5, 5, 180, 120);
            stockShapes.line(0, 64, 190, 64);
            stockShapes.rect(100, 60, 30, 20);
            stockShapes.end();
        }

        private void sceneWithVulkan () {
            vulkanBatch.begin();
            vulkanBatch.draw(quads, 0, 0, 96, 96);
            font.draw(vulkanBatch, "Vulkan", 100, 110);
            vulkanBatch.end();
            vulkanShapes.begin(VulkanShapeRenderer.ShapeType.Filled);
            vulkanShapes.setColor(1f, 0.5f, 0.25f, 1f);
            vulkanShapes.rect(110, 10, 60, 40);
            vulkanShapes.end();
            vulkanShapes.begin(VulkanShapeRenderer.ShapeType.Line);
            vulkanShapes.setColor(0f, 1f, 1f, 1f);
            vulkanShapes.line(5, 5, 180, 120);
            vulkanShapes.line(0, 64, 190, 64);
            vulkanShapes.rect(100, 60, 30, 20);
            vulkanShapes.end();
        }

        @Override public void render () {
            try {
                frames++;
                final VulkanDesktopApplication app = (VulkanDesktopApplication)Gdx.app;
                if (frames == 2) {
                    app.requestCapture();
                    sceneWithStock();
                } else if (frames == 3) {
                    stockPicture = app.capturedFrame();
                    app.requestCapture();
                    sceneWithVulkan();
                } else if (frames == 4) {
                    vulkanPicture = app.capturedFrame();
                    app.requestCapture();
                    custom.bind();
                    custom.setUniformMatrix("u_projTrans", stockBatch.getProjectionMatrix());
                    custom.setUniformi("u_texture", 0);
                    custom.setUniformf("u_tint", 0.5f, 1f, 1f, 1f);
                    stockBatch.setShader(custom);
                    stockBatch.begin();
                    stockBatch.draw(quads, 0, 0, 96, 96);
                    stockBatch.end();
                    stockBatch.setShader(null);
                } else if (frames == 5) {
                    customPicture = app.capturedFrame();
                    Gdx.app.exit();
                }
                if (frames > 60) Gdx.app.exit();
            } catch (Throwable t) {
                failure = t;
                Gdx.app.exit();
            }
        }

        @Override public void dispose () {
            if (stockBatch != null) stockBatch.dispose();
            if (stockShapes != null) stockShapes.dispose();
            if (vulkanBatch != null) vulkanBatch.dispose();
            if (vulkanShapes != null) vulkanShapes.dispose();
            if (font != null) font.dispose();
            if (quads != null) quads.dispose();
            if (custom != null) custom.dispose();
        }
    }

    @Test void stockClassesRunUnchangedAndDrawWhatTheVulkanClassesDraw () {
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
        assertNull(probe.stockError, "stock SpriteBatch and ShapeRenderer are created");
        assertEquals(3, probe.knownHits, "stock SpriteBatch, PolygonSpriteBatch and ShapeRenderer run on the library's dedicated shaders");
        assertEquals(0, probe.compilerCalls, "and none of them went through the run-time translator and compiler");
        assertNotNull(probe.stockPicture, "a picture of the stock classes");
        assertNotNull(probe.vulkanPicture, "a picture of the Vulkan classes");
        assertEquals(W * H * 4, probe.stockPicture.length);
        if (!Arrays.equals(probe.stockPicture, probe.vulkanPicture)) {
            int differing = 0;
            for (int i = 0; i < probe.stockPicture.length; i += 4)
                if (probe.stockPicture[i] != probe.vulkanPicture[i] || probe.stockPicture[i + 1] != probe.vulkanPicture[i + 1]
                    || probe.stockPicture[i + 2] != probe.vulkanPicture[i + 2]) differing++;
            dump("stock", probe.stockPicture);
            dump("vulkan", probe.vulkanPicture);
            throw new AssertionError(differing + " pixels differ between the stock classes and the Vulkan ones (pictures in build/stockclasses-*.png)");
        }
        // Something was drawn: the four colours, the orange rectangle.
        assertColour(probe.stockPicture, 24, 50, 255, 0, 0);
        assertColour(probe.stockPicture, 72, 50, 0, 255, 0);
        assertColour(probe.stockPicture, 140, 100, 255, 128, 64);

        assertTrue(probe.customCompiled, "a custom ShaderProgram compiles: " + probe.customLog);
        assertNotNull(probe.customPicture, "a picture with the custom shader");
        assertColour(probe.customPicture, 24, 50, 128, 0, 0);   // red times the tint (0.5, 1, 1)
        assertColour(probe.customPicture, 72, 50, 0, 255, 0);
    }

    private static void dump (String name, byte[] rgba) {
        try {
            final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++) {
                    final int o = (y * W + x) * 4;
                    img.setRGB(x, y, ((rgba[o] & 255) << 16) | ((rgba[o + 1] & 255) << 8) | (rgba[o + 2] & 255));
                }
            javax.imageio.ImageIO.write(img, "png", new java.io.File("build/stockclasses-" + name + ".png"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void assertColour (byte[] px, int x, int yFromTop, int r, int g, int b) {
        final int o = (yFromTop * W + x) * 4;
        final String where = "pixel (" + x + "," + yFromTop + ") = " + (px[o] & 255) + "," + (px[o + 1] & 255) + "," + (px[o + 2] & 255);
        assertTrue(Math.abs((px[o] & 255) - r) <= 2 && Math.abs((px[o + 1] & 255) - g) <= 2 && Math.abs((px[o + 2] & 255) - b) <= 2,
            where + ", expected " + r + "," + g + "," + b);
    }
}
