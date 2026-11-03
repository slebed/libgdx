package io.github.slebed.gdxvulkan.desktop;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.GdxVkShaders;

import org.lwjgl.glfw.GLFW;

/** Phase 1a milestone: a spinning triangle on a cleared window. Run with {@code gradlew :gdx-vulkan-desktop:runTriangleDemo}. */
public final class TriangleDemo {
    public static void main(String[] args) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");
        long window = 0;
        try (GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties())) {
            System.out.println("Device " + ctx.caps().deviceName + ", tier " + ctx.caps().tier);
            GlfwSurfaces.applyNoApiHints();
            window = GLFW.glfwCreateWindow(960, 540, "gdx-vulkan triangle", 0, 0);
            if (window == 0) throw new IllegalStateException("glfwCreateWindow failed");
            try (GdxVkPresenter presenter = GlfwSurfaces.createPresenter(ctx, window, true)) {
                int pipeline = presenter.createPipeline("triangle", GdxVkShaders.load("triangle.vert"), GdxVkShaders.load("triangle.frag"));
                float[] push = new float[32];
                int[] w = new int[1], h = new int[1];
                while (!GLFW.glfwWindowShouldClose(window)) {
                    GLFW.glfwPollEvents();
                    GLFW.glfwGetFramebufferSize(window, w, h);
                    if (w[0] != presenter.width() || h[0] != presenter.height()) presenter.resize(w[0], h[0]);
                    if (w[0] == 0 || h[0] == 0) continue;

                    // Rotate about Z, with an aspect-ratio correction so the triangle keeps its shape in a non-square window.
                    double a = GLFW.glfwGetTime();
                    float c = (float)Math.cos(a), s = (float)Math.sin(a), aspect = (float)h[0] / w[0];
                    push[0] = c * aspect; push[1] = s;          // column 0
                    push[4] = -s * aspect; push[5] = c;         // column 1
                    push[10] = 1f; push[15] = 1f;
                    push[16] = 1f; push[17] = 0.6f; push[18] = 0.1f; push[19] = 1f; // u_color

                    if (presenter.beginFrame(0.08f, 0.08f, 0.2f, 1f)) {
                        presenter.draw(pipeline, 3, push);
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
