package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.GdxVkShaders;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * Gate 1a: the clear screen and the triangle match their goldens in whichever tier the run selects
 * ({@code -Dgdxvulkan.forceTierB}); CI runs both. One golden per scene serves both tiers, which must render identically.
 */
class GoldenTest {
    private static final int SIZE = 256;

    private interface Scene {
        void draw(GdxVkPresenter p, int pipeline);
    }

    private static float[] push(float angle, boolean reverse) {
        float c = (float)Math.cos(angle), s = (float)Math.sin(angle);
        float[] p = new float[32];
        p[0] = c; p[1] = s;      // column 0
        p[4] = -s; p[5] = c;     // column 1
        p[10] = 1f; p[15] = 1f;
        p[16] = 1f; p[17] = 0.5f; p[18] = 0f; p[19] = 1f; // u_color
        p[20] = Float.intBitsToFloat(reverse ? 1 : 0);
        return p;
    }

    @Test void goldenScenes() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        long window = 0;
        try (GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties())) {
            GlfwSurfaces.applyNoApiHints();
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
            window = GLFW.glfwCreateWindow(SIZE, SIZE, "gdx-vulkan golden", 0, 0);
            assumeTrue(window != 0, "Could not create a window");
            try (GdxVkPresenter p = GlfwSurfaces.createPresenter(ctx, window, false)) {
                int pipeline = p.createPipeline(GdxVkShaders.load("triangle.vert"), GdxVkShaders.load("triangle.frag"));
                String device = ctx.caps().deviceName;

                check(p, pipeline, device, "clear", 0.2f, 0.4f, 0.6f, (q, pl) -> {});
                check(p, pipeline, device, "triangle", 0.1f, 0.1f, 0.3f, (q, pl) -> q.draw(pl, 3, push(0f, false)));
                check(p, pipeline, device, "triangle_rotated", 0.1f, 0.1f, 0.3f, (q, pl) -> q.draw(pl, 3, push(0.5f, false)));
                check(p, pipeline, device, "triangle_culled", 0.1f, 0.1f, 0.3f, (q, pl) -> q.draw(pl, 3, push(0f, true)));
            }
            assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void check(GdxVkPresenter p, int pipeline, String device, String name, float r, float g, float b, Scene scene) {
        GLFW.glfwPollEvents();
        if (!p.beginFrame(r, g, b, 1f)) throw new IllegalStateException("frame skipped for " + name);
        scene.draw(p, pipeline);
        byte[] px = p.endFrameAndCapture();
        GoldenImages.assertMatches(name, px, p.width(), p.height(), device);
    }
}
