package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.GdxVkResources;
import io.github.slebed.gdxvulkan.GdxVkSampler;
import io.github.slebed.gdxvulkan.GdxVkShaders;
import io.github.slebed.gdxvulkan.VulkanCaps;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * Stress test 1b (plan): allocate, write and dispose 50,000 buffer sub-allocations and create 2,000 small textures per second while
 * rendering at 60 fps, including disposals from background threads and repeated filter changes.
 *
 * <p>Pass: flat VMA usage and a bounded sampler count, zero validation or sync messages, clean shutdown (nothing left alive or
 * retired). Run it long with {@code -Dgdxvulkan.soakSeconds=600}; the default keeps {@code gradlew check} quick.
 * Other properties: {@code gdxvulkan.soakBufferRate} (default 50000/s), {@code gdxvulkan.soakTextureRate} (2000/s),
 * {@code gdxvulkan.soakStrict=true} to require the achieved rates to reach 90% of the targets.
 */
class SoakTest {
    private static final int SECONDS = Integer.getInteger("gdxvulkan.soakSeconds", 15);
    private static int bufferRate = Integer.getInteger("gdxvulkan.soakBufferRate", 50_000);
    private static int textureRate = Integer.getInteger("gdxvulkan.soakTextureRate", 2_000);
    private static final boolean STRICT = Boolean.getBoolean("gdxvulkan.soakStrict");
    private static final int BUFFER_THREADS = 3;

    private static final GdxVkSampler[] SAMPLERS = {
        new GdxVkSampler(GdxVkSampler.NEAREST, GdxVkSampler.NEAREST, GdxVkSampler.NEAREST, GdxVkSampler.CLAMP, GdxVkSampler.CLAMP, 1),
        new GdxVkSampler(GdxVkSampler.LINEAR, GdxVkSampler.LINEAR, GdxVkSampler.LINEAR, GdxVkSampler.REPEAT, GdxVkSampler.REPEAT, 1),
        new GdxVkSampler(GdxVkSampler.LINEAR, GdxVkSampler.NEAREST, GdxVkSampler.NEAREST, GdxVkSampler.MIRROR, GdxVkSampler.CLAMP, 4),
        new GdxVkSampler(GdxVkSampler.LINEAR, GdxVkSampler.LINEAR, GdxVkSampler.LINEAR, GdxVkSampler.CLAMP, GdxVkSampler.REPEAT, 16),
    };

    private static final class Pacer {
        private final long period;
        private long next = System.nanoTime();

        Pacer(double perSecond) {
            period = (long)(1e9 / perSecond);
        }

        void await() {
            next += period;
            long now = System.nanoTime();
            if (now - next > 100_000_000L) next = now; // fell behind: do not burst to catch up
            long wait = next - now;
            if (wait > 50_000) LockSupport.parkNanos(wait);
        }
    }

    private static byte[] floats(float... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return b.array();
    }

    @Test void soak() throws Exception {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        long window = 0;
        try (GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties())) {
            GdxVkResources res = ctx.resources();
            if (GoldenImages.isLavapipe(ctx.caps().deviceName)) {
                // A CPU rasterizer with the validation layers cannot take a GPU-sized load. CI runs this to catch hazards, leaks and
                // validation messages, not throughput, so the defaults drop tenfold (explicit properties still win).
                if (System.getProperty("gdxvulkan.soakBufferRate") == null) bufferRate /= 10;
                if (System.getProperty("gdxvulkan.soakTextureRate") == null) textureRate /= 10;
                System.out.println("[soak] software device: default rates scaled to " + bufferRate + " buffer cycles/s, " + textureRate + " textures/s");
            }
            boolean tierA = ctx.caps().tier == VulkanCaps.Tier.A;
            GlfwSurfaces.applyNoApiHints();
            window = GLFW.glfwCreateWindow(128, 128, "gdx-vulkan soak", 0, 0);
            assumeTrue(window != 0, "Could not create a window");

            AtomicLong bufferCycles = new AtomicLong(), textureCycles = new AtomicLong(), samplerChanges = new AtomicLong();
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            List<GdxVkResources.Stats> samples = new ArrayList<>();
            volatile_stop stop = new volatile_stop();

            try (GdxVkPresenter p = GlfwSurfaces.createPresenter(ctx, window, false)) {
                int pipeline = p.createPipeline("probe", GdxVkShaders.load("triangle.vert", tierA), GdxVkShaders.load("probe.frag", tierA));
                byte[] solid = new byte[2 * 2 * 4];
                int drawTexture = res.createTexture(2, 2, solid, SAMPLERS[1]);
                byte[] ubo = floats(0.5f, 0, 0, 0, 0, 0, 0, 0), ssbo = floats(0, 0, 0, 0, 0, 0.5f, 0, 0);

                List<Thread> workers = new ArrayList<>();
                for (int t = 0; t < (bufferRate > 0 ? BUFFER_THREADS : 0); t++) {
                    final long seed = 1000 + t;
                    workers.add(new Thread(() -> bufferWorker(res, new Random(seed), bufferRate / (double)BUFFER_THREADS, stop, bufferCycles, failures),
                        "soak-buffers-" + t));
                }
                if (textureRate > 0) workers.add(new Thread(() -> textureWorker(res, new Random(7), textureRate, stop, textureCycles, samplerChanges, failures), "soak-textures"));
                for (Thread w : workers) w.start();

                long worstFrameNanos = 0, slowReports = 0;
                long start = System.nanoTime(), frames = 0, nextSample = start + 1_000_000_000L, nextFrame = start;
                final long end = start + SECONDS * 1_000_000_000L;
                float[] push = new float[32];
                push[0] = push[5] = push[10] = push[15] = 1f;
                while (System.nanoTime() < end && failures.isEmpty()) {
                    long c0 = System.nanoTime();
                    GLFW.glfwPollEvents();
                    long c1 = System.nanoTime();
                    if (p.beginFrame(0.05f, 0.05f, 0.1f, 1f)) {
                        long c2 = System.nanoTime();
                        p.bindFrameData(ubo, ubo.length, ssbo, ssbo.length);
                        long c3 = System.nanoTime();
                        push[21] = Float.intBitsToFloat(p.bindTexture(drawTexture));
                        long c4 = System.nanoTime();
                        p.draw(pipeline, 3, push);
                        long c5 = System.nanoTime();
                        p.endFrame();
                        long c6 = System.nanoTime();
                        frames++;
                        long worst = Math.max(Math.max(c1 - c0, c2 - c1), Math.max(Math.max(c3 - c2, c4 - c3), Math.max(c5 - c4, c6 - c5)));
                        worstFrameNanos = Math.max(worstFrameNanos, worst);
                        if (worst > 50_000_000L && slowReports++ < 10) {
                            System.out.printf("[soak] slow frame at %.1fs: poll=%.0f begin=%.0f bindFrameData=%.0f bindTexture=%.0f draw=%.0f end=%.0f ms%n",
                                (c0 - start) / 1e9, (c1 - c0) / 1e6, (c2 - c1) / 1e6, (c3 - c2) / 1e6, (c4 - c3) / 1e6, (c5 - c4) / 1e6, (c6 - c5) / 1e6);
                        }
                    }
                    long now = System.nanoTime();
                    if (now >= nextSample) {
                        samples.add(res.stats());
                        nextSample += 1_000_000_000L;
                    }
                    nextFrame += 16_666_667L; // 60 fps
                    long sleep = nextFrame - System.nanoTime();
                    if (sleep > 0) LockSupport.parkNanos(sleep);
                    else if (sleep < -100_000_000L) nextFrame = System.nanoTime();
                }
                stop.stop = true;
                for (Thread w : workers) w.join(30_000);
                double seconds = (System.nanoTime() - start) / 1e9;

                // Shutdown: with the workers stopped, a few frames drain everything they retired.
                for (int i = 0; i < 10; i++) {
                    GLFW.glfwPollEvents();
                    if (p.beginFrame(0, 0, 0, 1)) p.endFrame();
                }
                res.destroyTexture(drawTexture);
                for (int i = 0; i < 10; i++) {
                    if (p.beginFrame(0, 0, 0, 1)) p.endFrame();
                }
                res.flushUploads();
                res.flushUploads();
                res.collectGarbage();

                GdxVkResources.Stats last = res.stats();
                System.out.printf("[soak] %s %s: %.0fs, %d frames (%.1f fps), buffer cycles %.0f/s (target %d), textures %.0f/s (target %d), "
                    + "sampler changes %.0f/s, worst single call %.1f ms%n[soak] final: %s%n", ctx.caps().deviceName, ctx.caps().tier, seconds, frames, frames / seconds,
                    bufferCycles.get() / seconds, bufferRate, textureCycles.get() / seconds, textureRate, samplerChanges.get() / seconds, worstFrameNanos / 1e6, last);

                for (int i = 0; i < samples.size(); i += Math.max(1, samples.size() / 8)) {
                    GdxVkResources.Stats s = samples.get(i);
                    System.out.printf("[soak] t=%3ds pools=%dMB vmaBlock=%dMB vmaAlloc=%dMB staging=%d samplers=%d slots=%d retiredPending=%d%n", i + 1,
                        s.poolBytes >> 20, s.vmaBlockBytes >> 20, s.vmaAllocationBytes >> 20, s.stagingBlocks, s.samplers, s.slotsInUse, s.retiredPending);
                }
                assertTrue(failures.isEmpty(), "worker or render failure: " + failures);
                assertTrue(samples.size() >= Math.min(SECONDS, 4) - 1, "too few samples: " + samples.size());

                // Flat memory: after warm-up the typical (median) usage may not grow. A leak is a trend; a momentary burst of staging
                // blocks during a scheduling hitch is not, so peaks are not compared.
                if (samples.size() >= 8) {
                    int n = samples.size();
                    long[] mid = new long[n / 2 - n / 4], late = new long[n - n / 2];
                    long midPool = 0, latePool = 0;
                    for (int i = n / 4; i < n / 2; i++) {
                        mid[i - n / 4] = samples.get(i).vmaBlockBytes;
                        midPool = Math.max(midPool, samples.get(i).poolBytes);
                    }
                    for (int i = n / 2; i < n; i++) {
                        late[i - n / 2] = samples.get(i).vmaBlockBytes;
                        latePool = Math.max(latePool, samples.get(i).poolBytes);
                    }
                    java.util.Arrays.sort(mid);
                    java.util.Arrays.sort(late);
                    long midMedian = mid[mid.length / 2], lateMedian = late[late.length / 2];
                    // VMA grows its image memory blocks by doubling as the working set peaks, at an arbitrary moment: one step of up to
                    // 64 MB is a high-water mark, not a leak. Runs under a minute may not have warmed up yet, so they get that headroom;
                    // long runs (the 10-minute soak) must be flat to within one staging block.
                    long slack = (SECONDS < 60 ? 80L : 16L) * 1024 * 1024;
                    assertTrue(lateMedian <= midMedian + slack, "VMA usage keeps growing: median " + midMedian + " -> " + lateMedian);
                    assertTrue(latePool <= midPool + 32L * 1024 * 1024, "buffer pools keep growing: " + midPool + " -> " + latePool);
                    int maxRetired = 0;
                    for (GdxVkResources.Stats s : samples) maxRetired = (int)Math.max(maxRetired, s.retiredPending);
                    assertTrue(maxRetired < 200_000, "deferred deletions pile up: " + maxRetired);
                }
                assertTrue(last.samplers <= SAMPLERS.length + 1, "sampler count must stay bounded: " + last.samplers);

                // Clean shutdown: the workers' leftovers are all that may remain, and they are destroyed below.
                if (STRICT) {
                    assertTrue(bufferCycles.get() / seconds >= 0.9 * bufferRate, "buffer rate below 90% of target");
                    assertTrue(textureCycles.get() / seconds >= 0.9 * textureRate, "texture rate below 90% of target");
                    assertTrue(frames / seconds >= 55, "frame rate below 55 fps");
                }
                assertTrue(bufferRate <= 0 || bufferCycles.get() > 0, "the buffer workers did no work");
                assertTrue(textureRate <= 0 || textureCycles.get() > 0, "the texture workers did no work");
                assertEquals(0, last.retiredPending, "everything retired must have been freed: " + last);
                assertEquals(0, last.slotsInUse, "table slots leaked: " + last);
            }
            assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    /** A volatile flag holder, so lambdas can share it. */
    private static final class volatile_stop {
        volatile boolean stop;
    }

    private static void bufferWorker(GdxVkResources res, Random rnd, double rate, volatile_stop stop, AtomicLong cycles, List<Throwable> failures) {
        ArrayDeque<Integer> live = new ArrayDeque<>();
        byte[] data = new byte[1024];
        rnd.nextBytes(data);
        Pacer pacer = new Pacer(rate);
        try {
            while (!stop.stop) {
                int size = 64 + rnd.nextInt(961);
                int id = res.createBuffer(size);
                res.writeBuffer(id, 0, data, 0, size);
                live.add(id);
                if (live.size() > 1500) res.destroyBuffer(live.poll()); // disposed from this background thread
                cycles.incrementAndGet();
                pacer.await();
            }
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            for (int id : live) res.destroyBuffer(id);
        }
    }

    private static void textureWorker(GdxVkResources res, Random rnd, double rate, volatile_stop stop, AtomicLong cycles, AtomicLong changes,
        List<Throwable> failures) {
        ArrayDeque<Integer> live = new ArrayDeque<>();
        byte[] px = new byte[8 * 8 * 4];
        Pacer pacer = new Pacer(rate);
        try {
            while (!stop.stop) {
                rnd.nextBytes(px);
                int id = res.createTexture(8, 8, px, SAMPLERS[rnd.nextInt(SAMPLERS.length)]);
                live.add(id);
                if (rnd.nextBoolean() && !live.isEmpty()) { // repeated filter changes on a live texture
                    Integer[] ids = live.toArray(new Integer[0]);
                    res.setSampler(ids[rnd.nextInt(ids.length)], SAMPLERS[rnd.nextInt(SAMPLERS.length)]);
                    changes.incrementAndGet();
                }
                if (live.size() > 200) res.destroyTexture(live.poll());
                cycles.incrementAndGet();
                pacer.await();
            }
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            for (int id : live) res.destroyTexture(id);
        }
    }
}
