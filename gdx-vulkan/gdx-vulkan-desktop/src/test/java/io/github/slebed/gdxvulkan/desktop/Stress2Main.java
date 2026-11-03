package io.github.slebed.gdxvulkan.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.ScreenUtils;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.lwjgl.glfw.GLFW;

/**
 * Stress test 2 (plan, Phase 2): sprites drawn from N un-atlased textures, {@code VulkanSpriteBatch} against stock {@code SpriteBatch} on
 * {@code gdx-backend-lwjgl3}, same machine, vsync off, same sprite sequence.
 *
 * <p>No arguments: runs every configuration (each in its own JVM, so the GL and Vulkan contexts never share a process) and prints a
 * table with the plan's targets. Child modes: {@code gl <textures>} and {@code vk <textures>} (add {@code -Dgdxvulkan.forceTierB=true}
 * through the parent's property for Tier B). Exit code 1 if a target is missed, unless {@code -Dgdxvulkan.stress2.report=true}.
 *
 * <p>Properties: {@code gdxvulkan.stress2.sprites} (per frame, default 20000), {@code .warmup} / {@code .seconds} (2 / 5).
 */
public final class Stress2Main {
    static final int WIDTH = 800, HEIGHT = 600, TEX_SIZE = 16, SPRITE = Integer.getInteger("gdxvulkan.stress2.size", 24);
    static final int SPRITES = Integer.getInteger("gdxvulkan.stress2.sprites", 20_000);
    static final double WARMUP = Double.parseDouble(System.getProperty("gdxvulkan.stress2.warmup", "2"));
    static final double SECONDS = Double.parseDouble(System.getProperty("gdxvulkan.stress2.seconds", "5"));

    private Stress2Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.exit(parent());
        } else if (args[0].equals("gl")) {
            gl(Integer.parseInt(args[1]));
        } else {
            vk(Integer.parseInt(args[1]));
        }
    }

    // --------------------------------------------------------------------------------------------------------------- workload

    /** N distinct small textures (un-atlased: one GL texture each), and a fixed pseudo-random sprite sequence over them. */
    static final class Workload {
        final Texture[] textures;
        final int[] tex = new int[SPRITES];
        final float[] x = new float[SPRITES], y = new float[SPRITES];

        Workload(int textureCount) {
            textures = new Texture[textureCount];
            for (int i = 0; i < textureCount; i++) {
                Pixmap pm = new Pixmap(TEX_SIZE, TEX_SIZE, Pixmap.Format.RGBA8888);
                pm.setColor(((i * 37) & 255) / 255f, ((i * 91) & 255) / 255f, ((i * 53) & 255) / 255f, 1f);
                pm.fill();
                textures[i] = new Texture(pm);
                pm.dispose();
            }
            Random r = new Random(7);
            for (int i = 0; i < SPRITES; i++) {
                tex[i] = r.nextInt(textureCount);
                x[i] = r.nextFloat() * (WIDTH - SPRITE);
                y[i] = r.nextFloat() * (HEIGHT - SPRITE);
            }
        }

        void draw(Batch batch) {
            batch.begin();
            for (int i = 0; i < SPRITES; i++) batch.draw(textures[tex[i]], x[i], y[i], SPRITE, SPRITE);
            batch.end();
        }

        void dispose() {
            for (Texture t : textures) t.dispose();
        }
    }

    /** Counts frames over the measurement window and prints one RESULT line. */
    static final class Meter {
        private final long start = System.nanoTime();
        private long measureStart;
        private long frames;
        private double worst;
        private long last = start;

        /** @return true when the run is finished */
        boolean frame() {
            long now = System.nanoTime();
            double sinceStart = (now - start) / 1e9;
            if (sinceStart >= WARMUP) {
                if (measureStart == 0) {
                    measureStart = now;
                } else {
                    frames++;
                    worst = Math.max(worst, (now - last) / 1e6);
                }
            }
            last = now;
            return measureStart != 0 && (now - measureStart) / 1e9 >= SECONDS;
        }

        long totalFrames() {
            return Math.max(1, frames);
        }

        void report(String mode, int textures) {
            double secs = (last - measureStart) / 1e9;
            System.out.printf("RESULT %s %d fps=%.1f worstMs=%.1f%n", mode, textures, frames / secs, worst);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ stock GL

    private static void gl(final int textures) {
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("stress 2: stock GL");
        cfg.setWindowedMode(WIDTH, HEIGHT);
        cfg.setResizable(false);
        cfg.useVsync(false);
        cfg.setForegroundFPS(0);
        cfg.setIdleFPS(0);
        final int[] code = {1};
        new Lwjgl3Application(new ApplicationAdapter() {
            SpriteBatch batch;
            Workload work;
            Meter meter;

            @Override public void create() {
                batch = new SpriteBatch(8191);
                work = new Workload(textures);
                meter = new Meter();
                System.out.println("[stress2] gl " + Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_RENDERER));
            }

            @Override public void render() {
                ScreenUtils.clear(0.1f, 0.1f, 0.1f, 1f);
                batch.getProjectionMatrix().setToOrtho2D(0, 0, WIDTH, HEIGHT);
                work.draw(batch);
                if (meter.frame()) {
                    meter.report("gl", textures);
                    code[0] = 0;
                    Gdx.app.exit();
                }
            }

            @Override public void dispose() {
                work.dispose();
                batch.dispose();
            }
        }, cfg);
        System.exit(code[0]);
    }

    // ----------------------------------------------------------------------------------------------------------------- gdx-vulkan

    private static void vk(int textures) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW could not initialise");
        GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        VulkanGL20 gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "stress 2: gdx-vulkan", 0, 0);
        GdxVkPresenter presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        TestGdx.width = presenter.width();
        TestGdx.height = presenter.height();
        GdxVkFrame frame = new GdxVkFrame(ctx, presenter, gl);
        System.out.println("[stress2] vk " + ctx.caps().deviceName + " tier " + ctx.caps().tier);
        Workload work = new Workload(textures);
        VulkanSpriteBatch batch = new VulkanSpriteBatch(frame, Integer.getInteger("gdxvulkan.stress2.batch", 8191));
        Meter meter = new Meter();
        long tBegin = 0, tDraw = 0, tEnd = 0;
        while (true) {
            long t0 = System.nanoTime();
            GLFW.glfwPollEvents();
            if (!frame.begin(0.1f, 0.1f, 0.1f, 1f)) continue;
            long t1 = System.nanoTime();
            batch.getProjectionMatrix().setToOrtho2D(0, 0, WIDTH, HEIGHT);
            work.draw(batch);
            long t2 = System.nanoTime();
            frame.end();
            long t3 = System.nanoTime();
            tBegin += t1 - t0;
            tDraw += t2 - t1;
            tEnd += t3 - t2;
            if (meter.frame()) break;
        }
        if (Boolean.getBoolean("gdxvulkan.stress2.breakdown")) {
            System.out.printf("[stress2] breakdown (ms/frame, whole run): begin %.3f draw %.3f end %.3f%n", tBegin / 1e6 / meter.totalFrames(),
                tDraw / 1e6 / meter.totalFrames(), tEnd / 1e6 / meter.totalFrames());
        }
        meter.report(ctx.caps().tier + "", textures);
        batch.dispose();
        work.dispose();
        TestGdx.uninstall();
        presenter.close();
        ctx.close();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        System.exit(0);
    }

    // ---------------------------------------------------------------------------------------------------------------------- parent

    private static double run(String mode, int textures, boolean tierB) throws Exception {
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        for (String p : System.getProperties().stringPropertyNames()) {
            if (p.startsWith("gdxvulkan.")) cmd.add("-D" + p + "=" + System.getProperty(p));
        }
        cmd.add("-Dgdxvulkan.validation=false"); // measure the library, not the validation layer
        cmd.add("-Dgdxvulkan.forceTierB=" + tierB);
        cmd.add(Stress2Main.class.getName());
        cmd.add(mode);
        cmd.add(Integer.toString(textures));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().remove("VK_LAYER_ENABLES");
        Process p = pb.start();
        double fps = -1;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            for (String line; (line = r.readLine()) != null;) {
                if (line.startsWith("RESULT")) {
                    fps = Double.parseDouble(line.substring(line.indexOf("fps=") + 4, line.indexOf(" worstMs")));
                    System.out.println("    " + line);
                } else if (line.startsWith("[stress2]")) {
                    System.out.println("    " + line);
                }
            }
        }
        if (p.waitFor() != 0 || fps < 0) throw new IllegalStateException(mode + " " + textures + " textures failed");
        return fps;
    }

    private static int parent() throws Exception {
        boolean report = Boolean.getBoolean("gdxvulkan.stress2.report");
        System.out.println("Stress test 2: " + SPRITES + " sprites per frame, " + WIDTH + "x" + HEIGHT + ", vsync off");
        double gl1 = run("gl", 1, false), gl128 = run("gl", 128, false);
        double a1 = run("vk", 1, false), a128 = run("vk", 128, false);
        double b1 = run("vk", 1, true), b128 = run("vk", 128, true);
        // The device may be Tier B only; the printed tier above says which runs were which.
        boolean ok = true;
        System.out.println();
        System.out.printf("%-34s %9s %9s %7s  %s%n", "workload", "stock GL", "gdx-vk", "ratio", "target");
        ok &= row("Tier A, 128 textures", gl128, a128, 3.0);
        ok &= row("Tier A, 1 texture", gl1, a1, 1.0);
        ok &= row("Tier B (fences), 128 textures", gl128, b128, 1.0);
        ok &= row("Tier B (fences), 1 texture", gl1, b1, 1.0);
        return ok || report ? 0 : 1;
    }

    private static boolean row(String name, double gl, double vk, double target) {
        double ratio = vk / gl;
        boolean pass = ratio >= target;
        System.out.printf("%-34s %9.1f %9.1f %6.2fx  >= %.1fx  %s%n", name, gl, vk, ratio, target, pass ? "PASS" : "MISS");
        return pass;
    }
}
