package io.github.slebed.gdxvulkan.demos;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;

/**
 * One demo on stock libGDX and OpenGL: {@code gradlew :gdx-vulkan-demos:demoGl -Ddemo=boids}, or started from the demo app with C. The demos are the same classes
 * the demo app runs on gdx-vulkan, except where stock libGDX needs different code (the boids' compute shader, which {@link GpuBoidsOpenGlDemo} writes with
 * {@code Gdx.gl31}).
 *
 * <p>Arguments: the demo id ({@code shadows-stock} or {@code boids}), then optionally the window's x, y, width and height.
 */
public final class GlDemos {
    private GlDemos () {}

    static Demo create (String id) {
        if (id.equals("sprites")) return new SpritesDemo();
        if (id.equals("shadows-stock")) return new StockShadowsDemo();
        if (id.equals("boids")) return new GpuBoidsOpenGlDemo();
        throw new IllegalArgumentException("unknown demo '" + id + "': sprites, shadows-stock or boids");
    }

    public static void main (String[] args) {
        final String id = args.length > 0 ? args[0] : "shadows-stock";
        final Demo demo = create(id);

        final Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("stock libGDX, OpenGL: " + demo.title());
        config.setWindowedMode(Integer.getInteger("demo.width", args.length > 3 ? Integer.parseInt(args[3]) : 1280), Integer.getInteger("demo.height", args.length > 4 ? Integer.parseInt(args[4]) : 720));
        if (args.length > 2) config.setWindowPosition(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        config.useVsync(Boolean.parseBoolean(System.getProperty("demo.vsync", "false"))); // off: the frame rate on screen is what the GPU can do
        config.setForegroundFPS(0);
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL31, 4, 3); // compute shaders need 4.3
        config.setBackBufferConfig(8, 8, 8, 8, 24, 0, 0);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create () {
                // The same four fields as the gdx-vulkan main shows. The driver adds "/PCIe/SSE2" to the card's name (the bus the card is on and the CPU instruction set its
                // software paths use); the Vulkan device name has no such suffix, so the name is cut there to read the same.
                final String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
                Backend.lines = new String[] {"OpenGL " + Gdx.gl.glGetString(GL20.GL_VERSION) + ", stock libGDX", renderer.split("/")[0],
                    "driver " + Gdx.gl.glGetString(GL20.GL_VENDOR) + ", GLSL " + Gdx.gl.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION),
                    "core profile through libGDX's GL31 interface"};
                DesktopClocks.install();
                Compute.CURRENT[0] = new Gl31Compute();
                timer = new GlTimer();
                Backend.gpuNanos = timer::nanos;
                demo.create();
                demo.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            }

            @Override public void resize (int width, int height) {
                demo.resize(width, height);
            }

            private int frames;
            private GlTimer timer;

            @Override public void render () {
                if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.ESCAPE)) {
                    Gdx.app.exit();
                    return;
                }
                timer.begin();
                demo.render();
                timer.end();
                if (Screenshot.file() != null && ++frames == Screenshot.frames()) {
                    final int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
                    Screenshot.write(com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixels(0, 0, w, h, true), w, h, Screenshot.file());
                    System.out.println("[demo] " + Backend.lines[0] + " | " + String.join(" | ", FrameStats.latest));
                    Gdx.app.exit();
                }
            }

            @Override public void dispose () {
                demo.dispose();
                timer.dispose();
            }
        }, config);
    }
}
