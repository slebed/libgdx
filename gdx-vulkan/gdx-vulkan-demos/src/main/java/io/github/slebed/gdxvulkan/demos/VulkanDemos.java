package io.github.slebed.gdxvulkan.demos;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;

import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.VulkanCaps;
import io.github.slebed.gdxvulkan.desktop.VulkanDesktopApplication;
import io.github.slebed.gdxvulkan.desktop.VulkanDesktopConfiguration;

/**
 * The demo app on gdx-vulkan: a menu of demos in the left half of the screen. Pressing C in a demo starts its stock OpenGL counterpart in the right half
 * ({@link GlDemos}). Run it with {@code gradlew :gdx-vulkan-demos:demos}.
 */
public final class VulkanDemos {
    private VulkanDemos () {}

    public static void main (String[] args) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW could not initialise");
        final GLFWVidMode mode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
        final int screenWidth = mode.width(), screenHeight = mode.height(); // read before glfwTerminate frees the mode
        GLFW.glfwTerminate();
        // Two windows side by side, each a little less than half of the screen wide and 70% of its height.
        final int width = Integer.getInteger("demo.width", screenWidth / 2 - 20), height = Integer.getInteger("demo.height", screenHeight * 7 / 10), top = screenHeight / 10;

        final VulkanDesktopConfiguration config = new VulkanDesktopConfiguration();
        config.title = "gdx-vulkan demos";
        config.width = width;
        config.height = height;
        config.x = 10;
        config.y = top;
        config.vsync = Boolean.parseBoolean(System.getProperty("demo.vsync", "false")); // off: the frame rate on screen is what the GPU can do
        config.depthBuffer = true;
        final DemoMenu menu = new DemoMenu(new OpenGlLauncher(screenWidth / 2 + 10, top, width, height));
        new VulkanDesktopApplication(new ApplicationAdapter() {
            private int frames;

            @Override public void create () {
                final VulkanCaps caps = GdxVkFrame.current().context().caps();
                Backend.lines = new String[] {"Vulkan " + Backend.vulkanVersion(caps.deviceApiVersion) + ", gdx-vulkan", caps.deviceName,
                    "driver " + (caps.driverInfo == null || caps.driverInfo.isEmpty() ? "(not reported)" : caps.driverInfo),
                    caps.tier == VulkanCaps.Tier.A ? "modern code path: bindless textures, dynamic rendering" : "compatibility code path: " + caps.tierReason};
                Backend.gpuNanos = () -> GdxVkFrame.current().presenter().gpuFrameNanos();
                Backend.vulkan = true;
                DesktopClocks.install();
                menu.create();
                final int start = Integer.getInteger("demo.start", 0); // -Ddemo.start=2 opens the second demo at once
                if (start > 0) menu.start(start - 1);
            }

            @Override public void resize (int width, int height) {
                menu.resize(width, height);
            }

            @Override public void render () {
                menu.render();
                if (Screenshot.file() == null) return;
                final VulkanDesktopApplication app = (VulkanDesktopApplication)Gdx.app;
                if (++frames == Screenshot.frames()) app.requestCapture();
                else if (frames == Screenshot.frames() + 1) {
                    Screenshot.write(app.capturedFrame(), Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), Screenshot.file());
                    System.out.println("[demo] " + Backend.lines[0] + " | " + String.join(" | ", FrameStats.latest));
                    Gdx.app.exit();
                }
            }

            @Override public void dispose () {
                menu.dispose();
            }
        }, config);
    }
}
