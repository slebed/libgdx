package io.github.slebed.gdxvulkan.desktop;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkPresenter;

import org.lwjgl.glfw.GLFW;

/** Phase 1a milestone: a window that cycles its clear colour. Resizing and minimizing must keep working. */
public final class ClearScreenDemo {
    public static void main(String[] args) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");
        long window = 0;
        try (GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties())) {
            System.out.println("Device " + ctx.caps().deviceName + ", tier " + ctx.caps().tier);
            GlfwSurfaces.applyNoApiHints();
            window = GLFW.glfwCreateWindow(960, 540, "gdx-vulkan clear", 0, 0);
            if (window == 0) throw new IllegalStateException("glfwCreateWindow failed");
            try (GdxVkPresenter presenter = GlfwSurfaces.createPresenter(ctx, window, true)) {
                System.out.printf("Swapchain %dx%d, VkFormat %d, %d images%n", presenter.width(), presenter.height(), presenter.vkFormat(),
                    presenter.imageCount());
                int[] w = new int[1], h = new int[1];
                while (!GLFW.glfwWindowShouldClose(window)) {
                    GLFW.glfwPollEvents();
                    GLFW.glfwGetFramebufferSize(window, w, h);
                    if (w[0] != presenter.width() || h[0] != presenter.height()) presenter.resize(w[0], h[0]);
                    double t = GLFW.glfwGetTime();
                    if (presenter.beginFrame((float)(0.5 + 0.5 * Math.sin(t)), (float)(0.5 + 0.5 * Math.sin(t * 0.7 + 2)),
                        (float)(0.5 + 0.5 * Math.sin(t * 1.3 + 4)), 1f)) {
                        presenter.endFrame();
                    }
                }
            }
            System.out.println("Validation messages: " + ctx.validationMessageCount());
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
