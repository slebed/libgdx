package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.GdxVkShaders;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * Gate 1a, pixel level: the triangle is written in GL conventions (+Y up, CCW front face). It must come out upright and
 * visible on the swapchain, and the same triangle with reversed winding must be culled.
 */
class TriangleTest {
    // Clear colour and triangle colour as 8-bit UNORM; 0.5 rounds to 127 or 128 depending on the driver.
    private static final float[] CLEAR = {0.1f, 0.1f, 0.3f, 1f};
    private static final int[] CLEAR8 = {26, 26, 77, 255};
    private static final float[] TRI = {1f, 0.5f, 0f, 1f};
    private static final int[] TRI8 = {255, 128, 0, 255};

    private static float[] push(boolean reverseWinding) {
        float[] p = new float[32];
        p[0] = p[5] = p[10] = p[15] = 1f; // identity u_transform
        System.arraycopy(TRI, 0, p, 16, 4);
        p[20] = Float.intBitsToFloat(reverseWinding ? 1 : 0); // u_indices.x
        return p;
    }

    @Test void triangleIsUprightAndWindingIsCulled() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        long window = 0;
        try (GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties())) {
            GlfwSurfaces.applyNoApiHints();
            window = GLFW.glfwCreateWindow(320, 240, "gdx-vulkan triangle test", 0, 0);
            assumeTrue(window != 0, "Could not create a window");
            try (GdxVkPresenter p = GlfwSurfaces.createPresenter(ctx, window, false)) {
                int pipeline = p.createPipeline("triangle", GdxVkShaders.load("triangle.vert"), GdxVkShaders.load("triangle.frag"));
                int w = p.width(), h = p.height();

                byte[] front = renderCapture(p, pipeline, false);
                assertEquals(w * h * 4, front.length);
                // Apex at 25% from the top, base at 75%, spanning 25%..75% of the width.
                assertPixel(front, w, 0.50, 0.45, TRI8, "centre of the triangle");
                assertPixel(front, w, 0.50, 0.70, TRI8, "just above the base, centre");
                assertPixel(front, w, 0.50, 0.10, CLEAR8, "above the apex: the triangle must not be flipped");
                assertPixel(front, w, 0.50, 0.90, CLEAR8, "below the base: the triangle must not be flipped");
                assertPixel(front, w, 0.10, 0.50, CLEAR8, "left of the triangle");
                assertPixel(front, w, 0.90, 0.50, CLEAR8, "right of the triangle");
                assertPixel(front, w, 0.05, 0.05, CLEAR8, "top-left corner");

                byte[] culled = renderCapture(p, pipeline, true);
                assertPixel(culled, w, 0.50, 0.45, CLEAR8, "reversed winding must be back-face culled");
                assertPixel(culled, w, 0.50, 0.70, CLEAR8, "reversed winding must be back-face culled");

                // The capture path must not disturb presentation: a few ordinary frames afterwards.
                for (int i = 0; i < 5; i++) {
                    if (p.beginFrame(CLEAR[0], CLEAR[1], CLEAR[2], CLEAR[3])) {
                        p.draw(pipeline, 3, push(false));
                        p.endFrame();
                    }
                }
            }
            assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static byte[] renderCapture(GdxVkPresenter p, int pipeline, boolean reverse) {
        GLFW.glfwPollEvents();
        assertTrue(p.beginFrame(CLEAR[0], CLEAR[1], CLEAR[2], CLEAR[3]), "frame was skipped");
        p.draw(pipeline, 3, push(reverse));
        return p.endFrameAndCapture();
    }

    /** fx, fy are fractions of the width and height from the top-left. Allows +-2 per channel. */
    private static void assertPixel(byte[] rgba, int width, double fx, double fy, int[] expected, String what) {
        int height = rgba.length / 4 / width;
        int x = (int)(fx * width), y = (int)(fy * height);
        int i = (y * width + x) * 4;
        for (int c = 0; c < 4; c++) {
            int actual = rgba[i + c] & 0xFF;
            assertTrue(Math.abs(actual - expected[c]) <= 2, String.format("%s: pixel (%d,%d) channel %d was %d, expected %d", what, x, y, c,
                actual, expected[c]));
        }
    }
}
