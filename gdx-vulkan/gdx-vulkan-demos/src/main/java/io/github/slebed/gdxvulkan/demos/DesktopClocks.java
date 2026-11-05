package io.github.slebed.gdxvulkan.demos;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** The CPU clocks of the desktop mains: the JVM's own beans (the Android app uses {@code android.os.Process} instead). */
final class DesktopClocks {
    private DesktopClocks () {}

    static void install () {
        final com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Backend.processCpuNanos = os::getProcessCpuTime;
        Backend.threadCpuNanos = threads::getCurrentThreadCpuTime;
    }
}
