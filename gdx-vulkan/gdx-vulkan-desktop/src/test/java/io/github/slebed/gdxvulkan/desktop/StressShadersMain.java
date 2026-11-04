package io.github.slebed.gdxvulkan.desktop;

import java.util.Arrays;

import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;
import io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram;

/**
 * Translated shaders against the built-in one (checklist, Phase 5b, section B: "a translated stock shader is not slower than the same shader written as SPIR-V"):
 * 20,000 sprites per frame from one texture, vsync off, drawn with the batch's built-in shaders, with libGDX's own sprite shader translated at run time, and with
 * a custom shader that also reads a push-constant tint and a uniform-block palette. The three run in turn, twice, so a slow start or thermal drift shows as a
 * difference between the rounds and not between the shaders.
 *
 * <p>Properties: {@code gdxvulkan.stressShaders.sprites} (20000), {@code .seconds} per measurement (4), {@code .warmup} (1), {@code .report=true} (never fail).
 * Fails when the translated stock shader is more than 5% slower than the built-in one, unless {@code report} is set.
 */
public final class StressShadersMain {
    private static final int WIDTH = 800, HEIGHT = 600;

    private static final String VERT = "attribute vec4 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\nuniform mat4 u_projTrans;\n"
        + "varying vec4 v_color;\nvarying vec2 v_texCoords;\nvoid main() {\n   v_color = a_color;\n   v_color.a = v_color.a * (255.0/254.0);\n"
        + "   v_texCoords = a_texCoord0;\n   gl_Position =  u_projTrans * a_position;\n}\n";
    private static final String FRAG = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\n"
        + "uniform sampler2D u_texture;\nvoid main() {\n  gl_FragColor = v_color * texture2D(u_texture, v_texCoords);\n}\n";
    private static final String CUSTOM_FRAG = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\n"
        + "uniform sampler2D u_texture;\nuniform vec4 u_tint;\nuniform vec4 u_palette[4];\nuniform int u_index;\n"
        + "void main() {\n  gl_FragColor = v_color * texture2D(u_texture, v_texCoords) * u_tint * u_palette[u_index];\n}\n";

    private StressShadersMain () {}

    private static final class Result {
        final String name;
        final double fps, low;

        Result (String name, double fps, double low) {
            this.name = name;
            this.fps = fps;
            this.low = low;
        }
    }

    public static void main (String[] args) {
        final int sprites = Integer.getInteger("gdxvulkan.stressShaders.sprites", 20000);
        final double seconds = Double.parseDouble(System.getProperty("gdxvulkan.stressShaders.seconds", "4"));
        final double warmup = Double.parseDouble(System.getProperty("gdxvulkan.stressShaders.warmup", "1"));
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW could not initialise");
        final GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        final VulkanGL20 gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        final long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "stress: translated shaders", 0, 0);
        final GdxVkPresenter presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        TestGdx.width = presenter.width();
        TestGdx.height = presenter.height();
        final GdxVkFrame frame = new GdxVkFrame(ctx, presenter, gl);
        System.out.println("[stressShaders] " + ctx.caps().deviceName + " tier " + ctx.caps().tier + ", " + sprites + " sprites, " + seconds + " s each");

        final Pixmap pm = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
        pm.setColor(0.9f, 0.7f, 0.4f, 1f);
        pm.fill();
        final Texture texture = new Texture(pm);
        pm.dispose();

        final VulkanShaderProgram stock = new VulkanShaderProgram(frame, VERT, FRAG);
        final VulkanShaderProgram custom = new VulkanShaderProgram(frame, VERT, CUSTOM_FRAG);
        if (!stock.isCompiled() || !custom.isCompiled()) throw new IllegalStateException(stock.getLog() + custom.getLog());
        custom.setUniformf("u_tint", 1f, 1f, 1f, 1f);
        custom.setUniform4fv("u_palette", new float[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, 0, 16);
        custom.setUniformi("u_index", 1);

        final VulkanSpriteBatch batch = new VulkanSpriteBatch(frame, 8191);
        final float[] xs = new float[sprites], ys = new float[sprites];
        final java.util.Random random = new java.util.Random(1234);
        for (int i = 0; i < sprites; i++) {
            xs[i] = random.nextFloat() * (WIDTH - 12);
            ys[i] = random.nextFloat() * (HEIGHT - 12);
        }

        final String[] names = {"built-in shader", "translated stock shader", "translated custom shader"};
        final VulkanShaderProgram[] programs = {null, stock, custom};
        final Result[][] results = new Result[2][3];
        for (int round = 0; round < 2; round++) {
            for (int c = 0; c < 3; c++) {
                batch.setShaderProgram(programs[c]);
                results[round][c] = measure(names[c], frame, batch, texture, xs, ys, warmup, seconds);
                System.out.printf("[stressShaders] round %d: %-26s %8.1f fps (1%% low %.1f)%n", round + 1, names[c], results[round][c].fps, results[round][c].low);
            }
        }
        final double[] mean = new double[3];
        for (int c = 0; c < 3; c++) mean[c] = (results[0][c].fps + results[1][c].fps) / 2;
        System.out.printf("[stressShaders] mean: built-in %.1f fps, translated stock %.1f fps (%.3fx), translated custom %.1f fps (%.3fx)%n", mean[0], mean[1],
            mean[1] / mean[0], mean[2], mean[2] / mean[0]);
        final boolean ok = mean[1] >= mean[0] * 0.95;
        System.out.println(ok ? "[stressShaders] PASS: the translated stock shader is within 5% of the built-in one"
            : "[stressShaders] FAIL: the translated stock shader is more than 5% slower than the built-in one");

        batch.dispose();
        texture.dispose();
        TestGdx.uninstall();
        presenter.close();
        ctx.close();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        System.exit(ok || Boolean.getBoolean("gdxvulkan.stressShaders.report") ? 0 : 1);
    }

    private static Result measure (String name, GdxVkFrame frame, VulkanSpriteBatch batch, Texture texture, float[] xs, float[] ys, double warmup, double seconds) {
        final long start = System.nanoTime();
        final long measureFrom = start + (long)(warmup * 1e9), end = measureFrom + (long)(seconds * 1e9);
        final double[] frameMs = new double[1 << 20];
        int frames = 0;
        long last = System.nanoTime();
        while (true) {
            GLFW.glfwPollEvents();
            if (!frame.begin(0.1f, 0.1f, 0.1f, 1f)) continue;
            batch.getProjectionMatrix().setToOrtho2D(0, 0, WIDTH, HEIGHT);
            batch.begin();
            for (int i = 0; i < xs.length; i++) batch.draw(texture, xs[i], ys[i], 12, 12);
            batch.end();
            frame.end();
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
        return new Result(name, fps, slow / (slowTotal / 1000.0));
    }
}
