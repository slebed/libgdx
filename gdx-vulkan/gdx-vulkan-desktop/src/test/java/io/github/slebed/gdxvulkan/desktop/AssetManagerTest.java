package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.resolvers.AbsoluteFileHandleResolver;
import com.badlogic.gdx.graphics.Texture;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * Stock {@code AssetManager} and {@code TextureLoader} on Vulkan (plan, Phase 4, async loading): worker threads decode the files into Pixmaps,
 * the thread that calls {@code update()} builds the Textures, and the render thread draws them. Nothing in that path is GL-specific, so the stock
 * loader is the loader; this test pins that, including textures disposed while a later frame is still in flight.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssetManagerTest {
    private GdxVkContext ctx;
    private VulkanGL20 gl;
    private long window;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private File dataDir;

    @BeforeAll void setUp() {
        String assets = System.getProperty("gdxvulkan.assetsDir");
        assumeTrue(assets != null && new File(assets, "data/particle.png").exists(), "bundled test assets not found (-Dgdxvulkan.assetsDir)");
        dataDir = new File(assets, "data");
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        window = GLFW.glfwCreateWindow(192, 192, "gdx-vulkan asset manager", 0, 0);
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

    private int litPixels(Texture t) {
        TestGdx.width = 192;
        TestGdx.height = 192;
        GLFW.glfwPollEvents();
        assertTrue(frame.begin(0, 0, 0, 1));
        VulkanSpriteBatch b = new VulkanSpriteBatch(frame);
        b.getProjectionMatrix().setToOrtho2D(0, 0, 192, 192);
        b.begin();
        b.draw(t, 0, 0, 192, 192);
        b.end();
        b.dispose();
        byte[] px = frame.endAndCapture();
        int lit = 0;
        for (int i = 0; i < px.length; i += 4) {
            if ((px[i] & 255) + (px[i + 1] & 255) + (px[i + 2] & 255) > 60) lit++;
        }
        return lit;
    }

    @Test void texturesLoadedAsynchronouslyDrawAndUnload() throws Exception {
        AssetManager assets = new AssetManager(new AbsoluteFileHandleResolver());
        String[] names = {"particle.png", "particle-fire.png", "particle-star.png", "particle-cloud.png", "default.png", "uiskin.png"};
        for (String n : names) assets.load(new File(dataDir, n).getAbsolutePath(), Texture.class);
        long end = System.nanoTime() + 20_000_000_000L;
        while (!assets.update(10)) { // update() builds the Textures here, after worker threads decoded the files
            assertTrue(System.nanoTime() < end, "loading did not finish: " + assets.getProgress());
        }
        for (String n : names) {
            Texture t = assets.get(new File(dataDir, n).getAbsolutePath(), Texture.class);
            assertNotNull(t);
            assertTrue(t.getWidth() > 0 && t.getHeight() > 0, n + " has a size");
            assertTrue(litPixels(t) > 100, n + " draws something visible");
        }
        assertEquals(names.length, ctx.resources().stats().texturesLive, "one native texture each: " + ctx.resources().stats());
        assets.dispose();
        for (int i = 0; i < 3; i++) { // retirement is deferred; frames let the queue run
            GLFW.glfwPollEvents();
            frame.begin(0, 0, 0, 1);
            frame.end();
        }
        assertEquals(0, ctx.resources().stats().texturesLive, "every texture is released: " + ctx.resources().stats());
    }
}
