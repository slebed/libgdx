package io.github.slebed.gdxvulkan.desktop;

import java.util.Arrays;

import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

/**
 * Stress test 2 for the GL shim (checklist, Phase 5b, section E, performance gate): the same 20,000 sprites drawn through the {@code Batch} interface by the
 * generated {@code VulkanSpriteBatch} and by libGDX's own {@code SpriteBatch} running on the shim (its shader replaced by the dedicated one, its mesh drawn with
 * {@code glDrawElements}). Two workloads: one texture, and eight textures in blocks (the stock batch flushes at each switch). vsync off, the order alternates
 * between rounds so a slow start or clock drift does not favour one. Passes when the stock batch reaches at least 1/1.5 of the generated one on one texture.
 *
 * <p>Properties: {@code gdxvulkan.stressShim.sprites} (20000), {@code .seconds} per measurement (4), {@code .warmup} (1), {@code .report=true} (never fail).
 */
public final class StressShimMain {
    private static final int WIDTH = 800, HEIGHT = 600;

    private StressShimMain () {}

    private static final class Result {
        final double fps, low;

        Result (double fps, double low) {
            this.fps = fps;
            this.low = low;
        }
    }

    public static void main (String[] args) {
        final int sprites = Integer.getInteger("gdxvulkan.stressShim.sprites", 20000);
        final double seconds = Double.parseDouble(System.getProperty("gdxvulkan.stressShim.seconds", "4"));
        final double warmup = Double.parseDouble(System.getProperty("gdxvulkan.stressShim.warmup", "1"));
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW could not initialise");
        final GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        final VulkanGL20 gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        final long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "stress: stock batch on the shim", 0, 0);
        final GdxVkPresenter presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        TestGdx.width = presenter.width();
        TestGdx.height = presenter.height();
        final GdxVkFrame frame = new GdxVkFrame(ctx, presenter, gl);
        System.out.println("[stressShim] " + ctx.caps().deviceName + " tier " + ctx.caps().tier + ", " + sprites + " sprites, " + seconds + " s each");

        final Texture[] textures = new Texture[8];
        for (int t = 0; t < textures.length; t++) {
            final Pixmap pm = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
            pm.setColor(0.2f + 0.1f * t, 0.7f, 0.4f, 1f);
            pm.fill();
            textures[t] = new Texture(pm);
            pm.dispose();
        }
        final Batch generated = new VulkanSpriteBatch(frame, 8191);
        final Batch stock = new SpriteBatch(8191);
        final float[] xs = new float[sprites], ys = new float[sprites];
        final java.util.Random random = new java.util.Random(1234);
        for (int i = 0; i < sprites; i++) {
            xs[i] = random.nextFloat() * (WIDTH - 12);
            ys[i] = random.nextFloat() * (HEIGHT - 12);
        }

        final String[] loads = {"1 texture", "8 textures"};
        final int[] textureCount = {1, 8};
        final double[][] mean = new double[2][];
        for (int load = 0; load < 2; load++) {
            final double[] sum = new double[2];
            for (int round = 0; round < 2; round++) {
                for (int k = 0; k < 2; k++) {
                    final int which = (round + k) % 2; // alternate the order
                    final Result r = measure(frame, which == 0 ? generated : stock, textures, textureCount[load], xs, ys, warmup, seconds);
                    sum[which] += r.fps / 2;
                    System.out.printf("[stressShim] %-10s round %d: %-18s %8.1f fps (1%% low %.1f)%n", loads[load], round + 1,
                        which == 0 ? "VulkanSpriteBatch" : "stock SpriteBatch", r.fps, r.low);
                }
            }
            mean[load] = sum;
            System.out.printf("[stressShim] %-10s mean: generated %.1f fps, stock on the shim %.1f fps (stock is %.2fx of generated)%n", loads[load], sum[0], sum[1],
                sum[1] / sum[0]);
        }
        final boolean ok = mean[0][1] * 1.5 >= mean[0][0];
        System.out.println(ok ? "[stressShim] PASS: the stock SpriteBatch is within 1.5x of the generated one (1 texture)"
            : "[stressShim] FAIL: the stock SpriteBatch is more than 1.5x slower than the generated one (1 texture)");

        generated.dispose();
        stock.dispose();
        for (Texture t : textures) t.dispose();
        TestGdx.uninstall();
        presenter.close();
        ctx.close();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        System.exit(ok || Boolean.getBoolean("gdxvulkan.stressShim.report") ? 0 : 1);
    }

    static long phaseDraw, phaseEnd, phaseFrames;

    private static Result measure (GdxVkFrame frame, Batch batch, Texture[] textures, int textureCount, float[] xs, float[] ys, double warmup, double seconds) {
        final long start = System.nanoTime();
        final long measureFrom = start + (long)(warmup * 1e9), end = measureFrom + (long)(seconds * 1e9);
        final double[] frameMs = new double[1 << 20];
        int frames = 0;
        long last = System.nanoTime();
        final int per = Math.max(1, xs.length / textureCount);
        while (true) {
            GLFW.glfwPollEvents();
            if (!frame.begin(0.1f, 0.1f, 0.1f, 1f)) continue;
            batch.getProjectionMatrix().setToOrtho2D(0, 0, WIDTH, HEIGHT);
            final long t0 = System.nanoTime();
            batch.begin();
            for (int i = 0; i < xs.length; i++) batch.draw(textures[Math.min(textureCount - 1, i / per)], xs[i], ys[i], 12, 12);
            batch.end();
            final long t1 = System.nanoTime();
            frame.end();
            if (System.nanoTime() >= measureFrom) { phaseDraw += t1 - t0; phaseEnd += System.nanoTime() - t1; phaseFrames++; }
            final long now = System.nanoTime();
            if (now >= end) break;
            if (now >= measureFrom && frames < frameMs.length) frameMs[frames++] = (now - last) / 1e6;
            last = now;
        }
        final double[] sorted = Arrays.copyOf(frameMs, frames);
        Arrays.sort(sorted);
        double total = 0;
        for (double v : sorted) total += v;
        final double fps = frames / (total / 1000.0);
        final int slow = Math.max(1, frames / 100);
        double slowTotal = 0;
        for (int i = frames - slow; i < frames; i++) slowTotal += sorted[i];
        System.out.printf("[stressShim]    batch %.3f ms, frame.end %.3f ms per frame%n", phaseDraw / 1e6 / Math.max(1, phaseFrames), phaseEnd / 1e6 / Math.max(1, phaseFrames));
        phaseDraw = phaseEnd = phaseFrames = 0;
        return new Result(fps, slow / (slowTotal / 1000.0));
    }
}
