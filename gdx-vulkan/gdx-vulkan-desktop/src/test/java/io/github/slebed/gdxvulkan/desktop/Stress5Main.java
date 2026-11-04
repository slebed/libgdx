package io.github.slebed.gdxvulkan.desktop;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g3d.VulkanComputeShader;
import io.github.slebed.gdxvulkan.g3d.VulkanGpuInstances;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;

import org.lwjgl.glfw.GLFW;

/**
 * Stress test 5 (plan, Phase 5): 250,000 instanced, textured meshes moved by a compute boids simulation that leaves their transforms in GPU
 * memory, culled against the frustum by compute, drawn with one indirect call and lit by 500 point lights that a second compute pass sorts into
 * clusters. The CPU's part per frame is a 224-byte header write, five dispatches and one indirect draw.
 *
 * <p>The pass criterion is 60 or more frames per second on the RTX 2080 and no Java allocation per frame. Prints the average and 1 % low frame rate
 * and the bytes the render thread allocated per frame. Exit code 1 below {@code -Dgdxvulkan.stress5.minFps} (60) unless
 * {@code -Dgdxvulkan.stress5.report=true}. Properties: {@code gdxvulkan.stress5.instances} (250000), {@code .lights} (500), {@code .seconds} (10),
 * {@code .width} / {@code .height} (1280 x 720). Vsync is off, so the rate is what the GPU can do.
 */
public final class Stress5Main {
    static final int INSTANCES = Integer.getInteger("gdxvulkan.stress5.instances", 250_000);
    static final int LIGHTS = Integer.getInteger("gdxvulkan.stress5.lights", 500);
    static final int SECONDS = Integer.getInteger("gdxvulkan.stress5.seconds", 10);
    static final int WIDTH = Integer.getInteger("gdxvulkan.stress5.width", 1280), HEIGHT = Integer.getInteger("gdxvulkan.stress5.height", 720);
    static final double MIN_FPS = Double.parseDouble(System.getProperty("gdxvulkan.stress5.minFps", "60"));
    static final float HALF = 100f; // the volume the boids live in: a cube of 2 * HALF
    static final int GRID = 32;

    private Stress5Main() {}

    public static void main(String[] args) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW could not initialise");
        GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        VulkanGL20 gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "stress 5: GPU-driven instances", 0, 0);
        GdxVkPresenter presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        presenter.setDepth(GdxVkPresenter.DEPTH);
        final int w = presenter.width(), h = presenter.height();
        TestGdx.width = w;
        TestGdx.height = h;
        GdxVkFrame frame = new GdxVkFrame(ctx, presenter, gl);
        System.out.println("[stress5] " + ctx.caps().deviceName + " tier " + ctx.caps().tier + ", " + INSTANCES + " instances, " + LIGHTS + " lights, " + w + "x" + h);

        // A low-poly cone pointing along +Z (the boids' heading), with a texture.
        Pixmap pm = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                pm.setColor(((x / 4 + y / 4) & 1) == 0 ? Color.WHITE : new Color(0.7f, 0.8f, 1f, 1f));
                pm.drawPixel(x, y);
            }
        }
        Texture texture = new Texture(pm);
        pm.dispose();
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder part = mb.part("cone", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal | Usage.TextureCoordinates, new Material());
        part.setVertexTransform(new Matrix4().setToRotation(Vector3.X, 90f));
        part.cone(0.8f, 2.4f, 0.8f, 8);
        Model model = mb.end();
        Mesh mesh = model.meshes.first();

        final int gridUints = GRID * GRID * GRID * 8;
        VulkanGpuInstances gpu = new VulkanGpuInstances(frame, mesh, texture, INSTANCES, LIGHTS, gridUints);
        final int gridOffset = gpu.userOffsetUints(), instanceOffset = gpu.instanceOffsetUints();

        Random rnd = new Random(42);
        float[] data = new float[INSTANCES * VulkanGpuInstances.INSTANCE_FLOATS];
        for (int i = 0; i < INSTANCES; i++) {
            final int b = i * VulkanGpuInstances.INSTANCE_FLOATS;
            // A point in a sphere of 0.7 * HALF, heading in a random direction.
            float x, y, z;
            do {
                x = rnd.nextFloat() * 2 - 1;
                y = rnd.nextFloat() * 2 - 1;
                z = rnd.nextFloat() * 2 - 1;
            } while (x * x + y * y + z * z > 1);
            data[b] = x * 0.7f * HALF;
            data[b + 1] = y * 0.7f * HALF;
            data[b + 2] = z * 0.7f * HALF;
            data[b + 3] = 0.6f;
            data[b + 7] = 1f;
            float dx = rnd.nextFloat() * 2 - 1, dy = rnd.nextFloat() * 2 - 1, dz = rnd.nextFloat() * 2 - 1;
            final float len = (float)Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-3f;
            data[b + 8] = dx / len * 12f;
            data[b + 9] = dy / len * 12f;
            data[b + 10] = dz / len * 12f;
        }
        gpu.setInstances(data, INSTANCES);
        float[] lights = new float[LIGHTS * VulkanGpuInstances.LIGHT_FLOATS];
        for (int l = 0; l < LIGHTS; l++) {
            final int b = l * VulkanGpuInstances.LIGHT_FLOATS;
            lights[b] = (rnd.nextFloat() * 2 - 1) * HALF * 0.8f;
            lights[b + 1] = (rnd.nextFloat() * 2 - 1) * HALF * 0.8f;
            lights[b + 2] = (rnd.nextFloat() * 2 - 1) * HALF * 0.8f;
            lights[b + 3] = 14f + rnd.nextFloat() * 10f;
            final float hue = rnd.nextFloat();
            lights[b + 4] = 0.4f + 0.6f * (float)Math.abs(Math.sin(hue * 6.28));
            lights[b + 5] = 0.4f + 0.6f * (float)Math.abs(Math.sin(hue * 6.28 + 2.1));
            lights[b + 6] = 0.4f + 0.6f * (float)Math.abs(Math.sin(hue * 6.28 + 4.2));
            lights[b + 7] = 2.5f;
        }
        gpu.setLights(lights, LIGHTS);

        final VulkanComputeShader boids = new VulkanComputeShader(frame, "boids", "boids.comp");
        final float[] clearPush = VulkanComputeShader.newPush(), accumulatePush = VulkanComputeShader.newPush(), updatePush = VulkanComputeShader.newPush();
        VulkanComputeShader.ints(clearPush, 0, INSTANCES, instanceOffset, gridOffset);
        VulkanComputeShader.ints(accumulatePush, 1, INSTANCES, instanceOffset, gridOffset);
        VulkanComputeShader.ints(updatePush, 2, INSTANCES, instanceOffset, gridOffset);
        for (float[] p : new float[][] {clearPush, accumulatePush, updatePush}) {
            p[24] = 1f / 60f;   // dt
            p[25] = HALF;
            p[27] = 14f;        // max speed
            p[28] = 0.6f;       // cohesion
            p[29] = 1.2f;       // alignment
            p[30] = 0.8f;       // separation
            p[31] = 40f;        // swirl
        }
        final int clearGroups = VulkanComputeShader.groups(gridUints, 256), boidGroups = VulkanComputeShader.groups(INSTANCES, 256);

        PerspectiveCamera camera = new PerspectiveCamera(60f, w, h);
        camera.near = 1f;
        camera.far = 600f;

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().getId();
        final int warmup = 120;
        long[] times = new long[1_000_000];
        int frames = 0;
        long allocStart = 0, allocated = 0;
        final long[] section = new long[8]; // bytes the render thread allocated in each part of the frame, after warm-up
        long measuredStart = 0, last = System.nanoTime();
        int total = 0;
        final long end = System.nanoTime() + (long)(SECONDS + 3) * 1_000_000_000L;
        while (System.nanoTime() < end && frames < times.length) {
            long a0 = threads.getThreadAllocatedBytes(thread);
            GLFW.glfwPollEvents();
            long a1 = threads.getThreadAllocatedBytes(thread);
            final float t = total * 0.004f;
            camera.position.set((float)Math.cos(t) * HALF * 1.25f, HALF * 0.35f, (float)Math.sin(t) * HALF * 1.25f);
            camera.lookAt(0f, 0f, 0f);
            camera.update();
            long a2 = threads.getThreadAllocatedBytes(thread);
            if (!frame.begin(0.02f, 0.03f, 0.06f, 1f)) continue;
            long a3 = threads.getThreadAllocatedBytes(thread);
            gl.glViewport(0, 0, w, h);
            gl.glClearColor(0.02f, 0.03f, 0.06f, 1f);
            gl.glClearDepthf(1f);
            gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            long a4 = threads.getThreadAllocatedBytes(thread);
            boids.dispatch(clearGroups, clearPush, gpu.buffer());
            boids.dispatch(boidGroups, accumulatePush, gpu.buffer());
            boids.dispatch(boidGroups, updatePush, gpu.buffer());
            long a5 = threads.getThreadAllocatedBytes(thread);
            gpu.render(camera, w, h);
            long a6 = threads.getThreadAllocatedBytes(thread);
            frame.end();
            long a7 = threads.getThreadAllocatedBytes(thread);
            if (total >= warmup) {
                section[0] += a1 - a0; section[1] += a2 - a1; section[2] += a3 - a2; section[3] += a4 - a3; section[4] += a5 - a4; section[5] += a6 - a5; section[6] += a7 - a6;
            }
            total++;
            if (total == warmup) {
                measuredStart = System.nanoTime();
                last = measuredStart;
                allocStart = threads.getThreadAllocatedBytes(thread);
            } else if (total > warmup) {
                final long now = System.nanoTime();
                times[frames++] = now - last;
                last = now;
            }
        }
        allocated = threads.getThreadAllocatedBytes(thread) - allocStart;
        final long measuredNs = System.nanoTime() - measuredStart;

        long[] sorted = Arrays.copyOf(times, frames);
        Arrays.sort(sorted);
        final double avgFps = frames * 1e9 / measuredNs;
        final double p50 = sorted[frames / 2] / 1e6, p99 = sorted[(int)(frames * 0.99)] / 1e6;
        final double low1 = 1000.0 / p99;
        final int visible = gpu.readVisibleCount();
        System.out.printf("[stress5] %d frames in %.1f s: %.1f fps average, median %.2f ms, 99th percentile %.2f ms (1%% low %.1f fps)%n", frames, measuredNs / 1e9, avgFps,
            p50, p99, low1);
        System.out.printf("[stress5] visible instances in the last frame: %d of %d (%.0f%%); render thread allocated %.1f bytes per frame%n", visible, INSTANCES,
            100.0 * visible / INSTANCES, allocated / (double)Math.max(frames, 1));
        System.out.printf("[stress5] allocation per frame by part (bytes): glfwPollEvents %.1f, camera %.1f, frame.begin %.1f, GL clear %.1f, boids dispatches %.1f, gpu.render %.1f, frame.end %.1f%n",
            section[0] / (double)frames, section[1] / (double)frames, section[2] / (double)frames, section[3] / (double)frames, section[4] / (double)frames,
            section[5] / (double)frames, section[6] / (double)frames);
        final int[] indirect = gpu.readIndirect();
        System.out.println("[stress5] indirect command: " + Arrays.toString(indirect) + "; validation messages: " + ctx.validationMessageCount());

        gpu.dispose();
        model.dispose();
        texture.dispose();
        TestGdx.uninstall();
        presenter.close();
        ctx.close();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        final boolean ok = avgFps >= MIN_FPS && indirect[1] > 0;
        System.out.println("[stress5] " + (ok ? "PASS" : "BELOW the " + MIN_FPS + " fps target") + " (the target applies to the RTX 2080)");
        if (!ok && !Boolean.getBoolean("gdxvulkan.stress5.report")) System.exit(1);
    }
}
