package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.Batch;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
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
 * Gate 3 (plan, Phase 3): stock {@code FrameBuffer}, unmodified. A 2D scene and a 3D scene (perspective, depth test) rendered into
 * framebuffers and drawn with {@code region.flip(false, true)}; a clipped Scene2D UI through four ping-pong post-processing passes. Each scene
 * must not be blank, match its lavapipe golden (exact on lavapipe, tolerance on a GPU) and match stock libGDX on OpenGL within tolerance
 * ({@link GlReferenceMain}; required with {@code -Dgdxvulkan.requireGl=true}). Runs in both tiers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Gate3GoldenTest {
    private GdxVkContext ctx;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private long window;
    private int w, h;
    private FileHandle data;
    private Gate3Scenes.Fx fx;

    @BeforeAll void setUp() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        String assets = System.getProperty("gdxvulkan.assetsDir");
        assumeTrue(assets != null && new java.io.File(assets, "data/uiskin.json").isFile(), "libGDX test assets not found: " + assets);
        data = new FileHandle(new java.io.File(assets, "data"));
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        VulkanGL20 gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        window = GLFW.glfwCreateWindow(Gate3Scenes.SIZE, Gate3Scenes.SIZE, "gdx-vulkan gate 3", 0, 0);
        assumeTrue(window != 0, "Could not create a window");
        presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        w = presenter.width();
        h = presenter.height();
        TestGdx.width = w;
        TestGdx.height = h;
        frame = new GdxVkFrame(ctx, presenter, gl);
        fx = Gate3Scenes.vulkanFx();
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
        for (final Gate3Scenes.Scene scene : Gate3Scenes.all()) {
            tests.add(DynamicTest.dynamicTest(scene.name(), () -> check(scene)));
        }
        return tests;
    }

    private void check(Gate3Scenes.Scene scene) throws Exception {
        float[] bg = Gate3Scenes.BACKGROUND;
        try {
            scene.prepare(data, w, h);
            GLFW.glfwPollEvents();
            assertTrue(frame.begin(bg[0], bg[1], bg[2], bg[3]));
            final VulkanSpriteBatch batch = new VulkanSpriteBatch(frame);
            batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            try {
                scene.draw(new Gate3Scenes.Env() {
                    public Batch batch() { return batch; }

                    public Gate3Scenes.Fx fx() { return fx; }

                    public ShapeApi shapes() { return ShapeApi.vulkan(frame); }
                }, w, h);
            } finally {
                batch.dispose();
            }
            byte[] px = frame.endAndCapture();

            int r = Math.round(bg[0] * 255), g = Math.round(bg[1] * 255), b = Math.round(bg[2] * 255), drawn = 0;
            for (int i = 0; i < px.length; i += 4) {
                if (Math.abs((px[i] & 0xFF) - r) > 2 || Math.abs((px[i + 1] & 0xFF) - g) > 2 || Math.abs((px[i + 2] & 0xFF) - b) > 2) drawn++;
            }
            assertTrue(drawn > w * h / 4, scene.name() + " is nearly blank: " + drawn + " of " + w * h + " pixels differ from the background");

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

            // The same scene again with libGDX's own classes on the GL shim: SpriteBatch, ShapeRenderer, and the effects as GLSL programs, translated.
            // The effect shaders are not the same text as the SPIR-V ones, so the pictures agree within a small tolerance, not bit for bit.
            GLFW.glfwPollEvents();
            assertTrue(frame.begin(bg[0], bg[1], bg[2], bg[3]));
            final com.badlogic.gdx.graphics.g2d.SpriteBatch stockBatch = new com.badlogic.gdx.graphics.g2d.SpriteBatch();
            stockBatch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            final Gate3Scenes.Fx stockFx = Gate3Scenes.glFx();
            try {
                scene.draw(new Gate3Scenes.Env() {
                    public Batch batch() { return stockBatch; }

                    public Gate3Scenes.Fx fx() { return stockFx; }

                    public ShapeApi shapes() { return ShapeApi.stock(); }
                }, w, h);
            } finally {
                stockBatch.dispose();
                stockFx.dispose();
            }
            final byte[] stockPx = frame.endAndCapture();
            PictureCompare.assertSimilar(scene.name() + "_stock", px, stockPx, w, h, 6, 0.005);
        } finally {
            scene.dispose();
        }
    }
}
