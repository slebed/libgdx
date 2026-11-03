package io.github.slebed.gdxvulkan.desktop;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatchInstanced;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.util.Random;

import org.lwjgl.glfw.GLFW;

/**
 * Instanced sprite stress test (plan, Phase 2): {@code VulkanSpriteBatchInstanced} with 1,000,000 sprites moved by compute every frame, drawn into a
 * 1280x720 window with vsync off. The plan's target is 60+ fps on the RTX 2080 ({@code -Dgdxvulkan.device=1}). Exit code 1 below target
 * unless {@code -Dgdxvulkan.stressInstanced.report=true}.
 *
 * <p>Properties: {@code gdxvulkan.stressInstanced.sprites} (default 1000000), {@code .seconds} (5), {@code .size} (sprite edge in pixels, 3).
 */
public final class StressInstancedMain {
    static final int WIDTH = 1280, HEIGHT = 720;
    static final int SPRITES = Integer.getInteger("gdxvulkan.stressInstanced.sprites", 1_000_000);
    static final double SECONDS = Double.parseDouble(System.getProperty("gdxvulkan.stressInstanced.seconds", "5"));
    static final float SIZE = Float.parseFloat(System.getProperty("gdxvulkan.stressInstanced.size", "3"));

    private StressInstancedMain() {}

    public static void main(String[] args) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW could not initialise");
        GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        VulkanGL20 gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "stress 3: instanced sprites", 0, 0);
        GdxVkPresenter presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        TestGdx.width = presenter.width();
        TestGdx.height = presenter.height();
        GdxVkFrame frame = new GdxVkFrame(ctx, presenter, gl);
        System.out.println("[stressInstanced] " + ctx.caps().deviceName + " tier " + ctx.caps().tier + ", " + SPRITES + " sprites at "
            + presenter.width() + "x" + presenter.height());

        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.setColor(1, 1, 1, 1);
        pm.fill();
        Texture texture = new Texture(pm);
        pm.dispose();

        VulkanSpriteBatchInstanced batch = new VulkanSpriteBatchInstanced(frame, SPRITES);
        batch.setTexture(texture);
        batch.setSpriteSize(SIZE, SIZE);
        batch.setBounds(presenter.width(), presenter.height());
        batch.setColor(0.3f, 0.8f, 1f, 0.5f);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, presenter.width(), presenter.height());
        float[] data = new float[SPRITES * 4];
        Random r = new Random(11);
        for (int i = 0; i < SPRITES; i++) {
            data[i * 4] = r.nextFloat() * (presenter.width() - SIZE);
            data[i * 4 + 1] = r.nextFloat() * (presenter.height() - SIZE);
            data[i * 4 + 2] = (r.nextFloat() - 0.5f) * 300f;
            data[i * 4 + 3] = (r.nextFloat() - 0.5f) * 300f;
        }
        batch.setSprites(data, SPRITES);

        long start = System.nanoTime(), measureStart = 0, frames = 0;
        double worst = 0;
        long last = start;
        while (true) {
            GLFW.glfwPollEvents();
            if (!frame.begin(0.02f, 0.02f, 0.05f, 1f)) continue;
            batch.update(1f / 60f);
            batch.draw();
            frame.end();
            long now = System.nanoTime();
            if ((now - start) / 1e9 >= 2.0) { // two seconds of warm-up
                if (measureStart == 0) {
                    measureStart = now;
                } else {
                    frames++;
                    worst = Math.max(worst, (now - last) / 1e6);
                    if ((now - measureStart) / 1e9 >= SECONDS) break;
                }
            }
            last = now;
        }
        double fps = frames / ((System.nanoTime() - measureStart) / 1e9);
        System.out.printf("[stressInstanced] RESULT fps=%.1f worstMs=%.1f (target 60+ on the RTX 2080)%n", fps, worst);

        batch.dispose();
        texture.dispose();
        TestGdx.uninstall();
        presenter.close();
        ctx.close();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        boolean report = Boolean.getBoolean("gdxvulkan.stressInstanced.report");
        System.exit(fps >= 60 || report ? 0 : 1);
    }
}
