package io.github.slebed.gdxvulkan.desktop;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.util.Arrays;

import org.lwjgl.glfw.GLFW;

/**
 * Stress test 3 (plan, Phase 3): the same 100,000 draws, each a state change (the cull mode), a full 128-byte push-constant block and an
 * indexed draw, recorded two ways in alternating frames:
 * <ul>
 * <li>the <b>floor</b>: one plain C++ loop straight into the frame's command buffer ({@code Presenter::benchmarkRecord});</li>
 * <li>the <b>stream</b>: encoded by Java into the command stream and replayed natively with one JNI call ({@code frame.flush()}).</li>
 * </ul>
 * The pass criterion is Java + JNI + C++ recording time &lt;= 1.3x the floor, on the same machine. Both are CPU recording times; the GPU's
 * work on the frames is not measured. Exit code 1 above 1.3x unless {@code -Dgdxvulkan.stress3.report=true}. Run on both tiers
 * ({@code -Dgdxvulkan.forceTierB=true}) and on either GPU ({@code -Dgdxvulkan.device=N}).
 *
 * <p>Properties: {@code gdxvulkan.stress3.draws} (100000), {@code .frames} (measured frames per method, 25).
 */
public final class Stress3Main {
    static final int DRAWS = Integer.getInteger("gdxvulkan.stress3.draws", 100_000);
    static final int FRAMES = Integer.getInteger("gdxvulkan.stress3.frames", 25);
    static final int WARMUP = 5;
    static final double TARGET = 1.3;

    private Stress3Main() {}

    public static void main(String[] args) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW could not initialise");
        GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        VulkanGL20 gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(640, 360, "stress 3: recording", 0, 0);
        GdxVkPresenter presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        TestGdx.width = presenter.width();
        TestGdx.height = presenter.height();
        GdxVkFrame frame = new GdxVkFrame(ctx, presenter, gl);
        System.out.println("[stress3] " + ctx.caps().deviceName + " tier " + ctx.caps().tier + ", " + DRAWS + " draws per frame");

        GdxVkPipelineDesc desc = new GdxVkPipelineDesc();
        desc.stride = 0; // vertices from gl_VertexIndex
        desc.cullMode = GdxVkPipelineDesc.CULL_BACK;
        final int pipeline = frame.pipeline("stress3", "triangle", "triangle.vert", "triangle.frag", desc);
        final int indexBuffer = frame.quadIndexBuffer(); // its first three u16 indices are 0, 1, 2
        final float[] push = new float[32];
        push[0] = push[5] = push[10] = push[15] = 1f;
        push[16] = push[17] = push[18] = push[19] = 1f;

        long[] floor = new long[FRAMES], stream = new long[FRAMES], encode = new long[FRAMES];
        CommandStream cs = frame.stream();
        int fi = 0, si = 0;
        for (int n = 0; fi < FRAMES || si < FRAMES; n++) {
            GLFW.glfwPollEvents();
            if (!frame.begin(0f, 0f, 0f, 1f)) continue;
            boolean floorFrame = (n & 1) == 0;
            if (floorFrame) {
                long ns = presenter.benchmarkRecord(pipeline, indexBuffer, DRAWS, push);
                if (n >= WARMUP * 2 && fi < FRAMES) floor[fi++] = ns;
            } else {
                long t0 = System.nanoTime();
                cs.bindPipeline(pipeline);
                cs.bindIndexBuffer(indexBuffer, 0, CommandStream.INDEX_U16);
                cs.pushConstants(push); // the whole block once, then only the word that changes
                for (int i = 0; i < DRAWS; i++) {
                    cs.cullMode((i & 1) != 0 ? 1 : 2);
                    push[16] = (float)i * 0.001f;
                    cs.pushRange(16, push, 16, 1);
                    cs.drawIndexed(3, 0, 0);
                }
                long t1 = System.nanoTime();
                frame.flush(); // one JNI call: native replay into the command buffer
                long ns = System.nanoTime() - t0;
                if (n >= WARMUP * 2 && si < FRAMES) {
                    encode[si] = t1 - t0;
                    stream[si++] = ns;
                }
            }
            frame.end();
        }
        Arrays.sort(floor);
        Arrays.sort(stream);
        Arrays.sort(encode);
        double floorMs = floor[FRAMES / 2] / 1e6, streamMs = stream[FRAMES / 2] / 1e6;
        double ratio = streamMs / floorMs;
        System.out.printf("[stress3] C++ floor      median %.2f ms (min %.2f)  = %.0f ns per draw%n", floorMs, floor[0] / 1e6, floor[FRAMES / 2] / (double)DRAWS);
        System.out.printf("[stress3] Java + replay  median %.2f ms (min %.2f)  = %.0f ns per draw%n", streamMs, stream[0] / 1e6, stream[FRAMES / 2] / (double)DRAWS);
        System.out.printf("[stress3]   of which Java encoding median %.2f ms = %.0f ns per draw; the rest is the JNI call and native replay%n", encode[FRAMES / 2] / 1e6, encode[FRAMES / 2] / (double)DRAWS);
        System.out.printf("[stress3] RESULT ratio=%.2f (best case %.2f) (target <= %.1f) %s%n", ratio, stream[0] / (double)floor[0], TARGET, ratio <= TARGET ? "PASS" : "MISS");

        TestGdx.uninstall();
        presenter.close();
        ctx.close();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        System.exit(ratio <= TARGET || Boolean.getBoolean("gdxvulkan.stress3.report") ? 0 : 1);
    }
}
