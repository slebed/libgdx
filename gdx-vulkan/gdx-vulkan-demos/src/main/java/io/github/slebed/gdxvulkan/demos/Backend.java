package io.github.slebed.gdxvulkan.demos;

import java.util.function.LongSupplier;

/**
 * What the running demo is on, for the numbers on screen. The two mains fill it in with the same fields, so a demo shows the same information on both backends:
 * the graphics API and its version, the device, the driver, and which code path runs.
 */
public final class Backend {
    private Backend () {}

    /** Four lines: the API and version, the device, the driver, the code path. */
    public static volatile String[] lines = {"", "", "", ""};

    /** The GPU time of a recent frame in nanoseconds, 0 when it is not known. gdx-vulkan reads timestamps from the device; the OpenGL main uses a timer query. */
    public static volatile LongSupplier gpuNanos = () -> 0L;

    /** True in the gdx-vulkan apps: the demos that name a batch use the Vulkan one (a rename of the stock class) instead of stock. */
    public static volatile boolean vulkan;

    /** False when the app has no demo menu (the OpenGL activity on a phone runs one demo): the on-screen text then leaves out the menu hints. */
    public static volatile boolean menu = true;

    /** Set by the Android activity when the system Back button was pressed and the view did not take it; the demos read it as Esc. */
    public static volatile boolean back;

    /** CPU time of the whole process in nanoseconds, 0 when the platform cannot say. The desktop mains use the JVM's beans, the Android app {@code android.os.Process}. */
    public static volatile LongSupplier processCpuNanos = () -> 0L;

    /** CPU time of the calling thread in nanoseconds, 0 when the platform cannot say. */
    public static volatile LongSupplier threadCpuNanos = () -> 0L;

    /** The numbers the running demo shows at the bottom of the screen (frame, CPU, render thread, GPU), as of the last half second. */
    public static String[] latestStats () {
        return FrameStats.latest;
    }

    /** "1.4.309" for a packed Vulkan version. */
    public static String vulkanVersion (int packed) {
        return (packed >>> 22) + "." + ((packed >>> 12) & 0x3ff) + "." + (packed & 0xfff);
    }
}
