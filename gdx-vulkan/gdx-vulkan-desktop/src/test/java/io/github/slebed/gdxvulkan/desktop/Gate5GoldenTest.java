package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
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
 * Gate 5 (plan, Phase 5): stock {@code Mesh}, {@code ModelBuilder} and {@code ModelInstance} drawn by {@code VulkanModelBatch}. Each scene must
 * not be blank, match its lavapipe golden (exact on lavapipe, tolerance on a GPU) and match stock libGDX's {@code ModelBatch} on OpenGL within
 * tolerance ({@link GlReferenceMain}; required with {@code -Dgdxvulkan.requireGl=true}). Runs in both tiers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Gate5GoldenTest {
    private GdxVkContext ctx;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private long window;
    private int w, h;
    private com.badlogic.gdx.files.FileHandle data;

    @BeforeAll void setUp() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        String assets = System.getProperty("gdxvulkan.assetsDir");
        assumeTrue(assets != null && new java.io.File(assets, "data/g3d/knight.g3db").isFile(), "libGDX test assets not found: " + assets);
        data = new com.badlogic.gdx.files.FileHandle(new java.io.File(assets, "data"));
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        VulkanGL20 gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        window = GLFW.glfwCreateWindow(Gate5Scenes.SIZE, Gate5Scenes.SIZE, "gdx-vulkan gate 5", 0, 0);
        assumeTrue(window != 0, "Could not create a window");
        presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        presenter.setDepth(GdxVkPresenter.DEPTH);
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
        for (final Gate5Scenes.Scene scene : Gate5Scenes.all()) {
            tests.add(DynamicTest.dynamicTest(scene.name(), () -> check(scene)));
        }
        return tests;
    }

    private void check(Gate5Scenes.Scene scene) throws Exception {
        float[] bg = Gate5Scenes.BACKGROUND;
        try {
            scene.prepare(data);
            GLFW.glfwPollEvents();
            assertTrue(frame.begin(bg[0], bg[1], bg[2], bg[3]));
            final Gate5Scenes.ModelApi api = Gate5Scenes.vulkan(frame);
            try {
                com.badlogic.gdx.Gdx.gl.glViewport(0, 0, w, h);
                scene.draw(api, w, h);
            } finally {
                api.dispose();
            }
            byte[] px = frame.endAndCapture();

            int r = Math.round(bg[0] * 255), g = Math.round(bg[1] * 255), b = Math.round(bg[2] * 255), drawn = 0;
            for (int i = 0; i < px.length; i += 4) {
                if (Math.abs((px[i] & 0xFF) - r) > 2 || Math.abs((px[i + 1] & 0xFF) - g) > 2 || Math.abs((px[i + 2] & 0xFF) - b) > 2) drawn++;
            }
            assertTrue(drawn > w * h / 50, scene.name() + " is nearly blank: " + drawn + " of " + w * h + " pixels differ from the background");

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
        } finally {
            scene.dispose();
        }
    }
}
