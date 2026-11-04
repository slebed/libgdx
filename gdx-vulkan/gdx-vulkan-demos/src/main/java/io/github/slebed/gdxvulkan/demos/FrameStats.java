package io.github.slebed.gdxvulkan.demos;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * The same four measurements for every demo on either backend, averaged over half a second so the numbers can be read:
 * <ul>
 * <li>the time of a frame,</li>
 * <li>the CPU time of the whole process per frame, every thread, the graphics driver's own threads included: what the CPU spends on the frame,</li>
 * <li>the CPU time of the render thread per frame: what the application code and the calls into the API cost,</li>
 * <li>the GPU time per frame, from {@link Backend#gpuNanos}.</li>
 * </ul>
 * The CPU numbers count work, not waiting: a frame limited by the GPU leaves the CPU idle and shows a small CPU time.
 */
final class FrameStats {
    private static final long WINDOW_NS = 500_000_000L;

    private final com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private long windowStart, processCpuStart, threadCpuStart, gpuSum;
    private int frames, gpuSamples;
    private String[] lines = {"measuring...", "", "", ""};
    /** The latest numbers of whichever demo is running, for the log line a screenshot run prints. */
    static volatile String[] latest = {};

    /** Call once per rendered frame, on the render thread. */
    void frame () {
        final long now = System.nanoTime();
        if (windowStart == 0) {
            reset(now);
            return;
        }
        final long gpu = Backend.gpuNanos.getAsLong();
        if (gpu > 0) {
            gpuSum += gpu;
            gpuSamples++;
        }
        frames++;
        final long wall = now - windowStart;
        if (wall < WINDOW_NS) return;
        final double frameMs = wall / 1e6 / frames;
        final double processMs = (os.getProcessCpuTime() - processCpuStart) / 1e6 / frames;
        final double threadMs = (threads.getCurrentThreadCpuTime() - threadCpuStart) / 1e6 / frames;
        final double cores = (os.getProcessCpuTime() - processCpuStart) / (double)wall;
        final String gpuLine = gpuSamples == 0 ? "GPU: not measured" : String.format("GPU: %.2f ms per frame (%.0f%% of the frame)", gpuSum / 1e6 / gpuSamples,
            100.0 * gpuSum / gpuSamples / (wall / (double)frames));
        lines = new String[] {String.format("frame: %.2f ms (%.0f fps)", frameMs, 1000.0 / frameMs),
            String.format("CPU: %.2f ms per frame, %.1f cores busy (all threads)", processMs, cores), String.format("render thread: %.2f ms per frame", threadMs), gpuLine};
        latest = lines;
        reset(now);
    }

    private void reset (long now) {
        windowStart = now;
        processCpuStart = os.getProcessCpuTime();
        threadCpuStart = threads.getCurrentThreadCpuTime();
        frames = 0;
        gpuSum = 0;
        gpuSamples = 0;
    }

    String[] lines () {
        return lines;
    }
}
