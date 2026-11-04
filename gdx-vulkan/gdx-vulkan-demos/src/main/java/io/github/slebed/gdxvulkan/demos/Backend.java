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

    /** "1.4.309" for a packed Vulkan version. */
    public static String vulkanVersion (int packed) {
        return (packed >>> 22) + "." + ((packed >>> 12) & 0x3ff) + "." + (packed & 0xfff);
    }
}
