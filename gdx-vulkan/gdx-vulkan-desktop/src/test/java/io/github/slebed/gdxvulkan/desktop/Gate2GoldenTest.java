package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.files.FileHandle;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g2d.VulkanPolygonSpriteBatch;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.glfw.GLFW;

/**
 * Gate 2 (plan, Phase 2): the stock classes the plan names, unmodified, drawing through {@link VulkanSpriteBatch}: Scene2D ({@code Table},
 * {@code Skin}, a clipped {@code ScrollPane} through {@code ScissorStack}), {@code BitmapFont}, {@code FreeTypeFontGenerator} with glyphs
 * added mid-frame, {@code TextureAtlas} and {@code ParticleEffect} (see {@link Gate2Scenes}). Each scene must
 * <ul>
 * <li>not be blank,</li>
 * <li>match its lavapipe golden (exact on lavapipe, tolerance on a GPU), and</li>
 * <li>match what stock libGDX draws on OpenGL ({@link GlReferenceMain}) within tolerance, when those reference images are available
 * ({@code -Dgdxvulkan.glReferenceDir}; required with {@code -Dgdxvulkan.requireGl=true}).</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Gate2GoldenTest {
    private GdxVkContext ctx;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private long window;
    private int w, h;
    private FileHandle data;

    @BeforeAll void setUp() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        String assets = System.getProperty("gdxvulkan.assetsDir");
        assumeTrue(assets != null && new java.io.File(assets, "data/uiskin.json").isFile(), "libGDX test assets not found: " + assets);
        data = new FileHandle(new java.io.File(assets, "data"));
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        VulkanGL20 gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        window = GLFW.glfwCreateWindow(Gate2Scenes.SIZE, Gate2Scenes.SIZE, "gdx-vulkan gate 2", 0, 0);
        assumeTrue(window != 0, "Could not create a window");
        presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        w = presenter.width();
        h = presenter.height();
        TestGdx.width = w;
        TestGdx.height = h;
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

    @TestFactory List<DynamicTest> scenes() {
        List<DynamicTest> tests = new ArrayList<DynamicTest>();
        for (final Gate2Scenes.Scene scene : Gate2Scenes.all()) {
            tests.add(DynamicTest.dynamicTest(scene.name(), () -> check(scene)));
        }
        return tests;
    }

    /** Draws the scene once with each 2D batch. Their output must be the same image: a sprite is just two triangles to the polygon batch. */
    private void check(Gate2Scenes.Scene scene) throws Exception {
        try {
            scene.prepare(data, w, h);
            checkWith(scene, false);
            if (!(scene instanceof Gate2Scenes.ShapeScene)) checkWith(scene, true); // shapes do not go through a batch
        } finally {
            scene.dispose();
        }
    }

    private void checkWith(Gate2Scenes.Scene scene, boolean polygonBatch) throws Exception {
        float[] bg = Gate2Scenes.BACKGROUND;
        String label = scene.name() + (polygonBatch ? " (VulkanPolygonSpriteBatch)" : " (VulkanSpriteBatch)");
        GLFW.glfwPollEvents();
        assertTrue(frame.begin(bg[0], bg[1], bg[2], bg[3]));
        if (scene instanceof Gate2Scenes.ShapeScene) {
            ShapeApi shapes = ShapeApi.vulkan(frame);
            try {
                ((Gate2Scenes.ShapeScene)scene).drawShapes(shapes, w, h);
            } finally {
                shapes.dispose();
            }
        } else {
            com.badlogic.gdx.graphics.g2d.Batch batch = polygonBatch ? new VulkanPolygonSpriteBatch(frame) : new VulkanSpriteBatch(frame);
            try {
                scene.draw(batch, w, h);
            } finally {
                batch.dispose();
            }
        }
        byte[] px = frame.endAndCapture();

        int r = Math.round(bg[0] * 255), g = Math.round(bg[1] * 255), b = Math.round(bg[2] * 255), drawn = 0;
        for (int i = 0; i < px.length; i += 4) {
            if (Math.abs((px[i] & 0xFF) - r) > 2 || Math.abs((px[i + 1] & 0xFF) - g) > 2 || Math.abs((px[i + 2] & 0xFF) - b) > 2) drawn++;
        }
        assertTrue(drawn > w * h / 50, label + " is nearly blank: " + drawn + " of " + w * h + " pixels differ from the background");

        // Both checks always run and report, so a failure of one never hides the numbers of the other.
        Throwable first = null;
        try {
            boolean compared = OracleImages.compare(scene.name(), px, w, h);
            if (!compared && Boolean.getBoolean("gdxvulkan.requireGl")) {
                throw new AssertionError("No stock-GL reference image for " + scene.name() + " in gdxvulkan.glReferenceDir");
            }
        } catch (Throwable t) {
            first = t;
        }
        try {
            GoldenImages.assertMatches(scene.name(), px, w, h, ctx.caps().deviceName);
        } catch (Throwable t) {
            if (first == null) first = t;
            else first.addSuppressed(t);
        }
        if (first instanceof Exception) throw (Exception)first;
        if (first instanceof Error) throw (Error)first;
    }
}
