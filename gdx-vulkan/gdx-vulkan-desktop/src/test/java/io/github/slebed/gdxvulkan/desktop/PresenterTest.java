package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.VulkanCaps;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** Needs a display and a Vulkan device. Skips without a display; a missing device fails when requireDriver is set. */
class PresenterTest {
    private static final int VK_FORMAT_R8G8B8A8_UNORM = 37, VK_FORMAT_B8G8R8A8_UNORM = 44;

    @Test void clearsResizesAndSurvivesMinimize() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        long window = 0;
        try (GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties())) {
            if (Boolean.getBoolean("gdxvulkan.validation")) {
                assertTrue(ctx.caps().validationEnabled, "validation layer is not installed; this test would prove nothing");
            }
            if (Boolean.getBoolean("gdxvulkan.forceTierB")) assertEquals(VulkanCaps.Tier.B, ctx.caps().tier);

            GlfwSurfaces.applyNoApiHints();
            window = GLFW.glfwCreateWindow(320, 240, "gdx-vulkan test", 0, 0);
            assumeTrue(window != 0, "Could not create a window");
            try (GdxVkPresenter p = GlfwSurfaces.createPresenter(ctx, window, false)) {
                int fmt = p.vkFormat();
                assertTrue(fmt == VK_FORMAT_B8G8R8A8_UNORM || fmt == VK_FORMAT_R8G8B8A8_UNORM, "UNORM swapchain, was " + fmt);
                assertTrue(p.width() > 0 && p.height() > 0);

                renderFrames(p, 30);

                GLFW.glfwSetWindowSize(window, 480, 360);
                pump(50);
                int[] w = new int[1], h = new int[1];
                GLFW.glfwGetFramebufferSize(window, w, h);
                p.resize(w[0], h[0]);
                renderFrames(p, 10);
                assertEquals(w[0], p.width());
                assertEquals(h[0], p.height());

                // Zero-area surface: frames are skipped and rendering resumes after restore.
                GLFW.glfwIconifyWindow(window);
                pump(100);
                GLFW.glfwGetFramebufferSize(window, w, h);
                p.resize(w[0], h[0]);
                for (int i = 0; i < 5; i++) {
                    if (p.beginFrame(0, 0, 0, 1)) p.endFrame();
                }
                GLFW.glfwRestoreWindow(window);
                pump(100);
                GLFW.glfwGetFramebufferSize(window, w, h);
                p.resize(w[0], h[0]);
                renderFrames(p, 10);
                assertTrue(p.width() > 0);
            }
            assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void renderFrames(GdxVkPresenter p, int n) {
        float t = 0;
        for (int i = 0; i < n; i++, t += 0.1f) {
            GLFW.glfwPollEvents();
            if (p.beginFrame(t % 1f, 0.25f, 0.5f, 1f)) p.endFrame();
        }
    }

    private static void pump(int ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            GLFW.glfwPollEvents();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
