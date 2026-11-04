package io.github.slebed.gdxvulkan.desktop;

import io.github.slebed.gdxvulkan.GdxVk;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkPresenter;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWNativeX11;

/** Bridges a GLFW window to {@link GdxVkContext#createPresenter}. Wayland and macOS arrive with their own phases. */
public final class GlfwSurfaces {
    private GlfwSurfaces() {}

    /** Hints for a window that Vulkan renders into: no OpenGL context. Call before glfwCreateWindow. */
    public static void applyNoApiHints() {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
    }

    /** Creates a presenter sized to the window's framebuffer. */
    public static GdxVkPresenter createPresenter(GdxVkContext context, long glfwWindow, boolean vsync) {
        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetFramebufferSize(glfwWindow, w, h);
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return GdxVk.createSurface(context, 0, GLFWNativeWin32.glfwGetWin32Window(glfwWindow), w[0], h[0], vsync);
        }
        if (os.contains("linux")) {
            long display = GLFWNativeX11.glfwGetX11Display();
            long window = GLFWNativeX11.glfwGetX11Window(glfwWindow);
            if (display == 0 || window == 0) {
                throw new UnsupportedOperationException("GLFW is not running on X11; Wayland surfaces are not implemented yet");
            }
            return GdxVk.createSurface(context, display, window, w[0], h[0], vsync);
        }
        throw new UnsupportedOperationException("No surface support for " + os + " yet");
    }
}
