package io.github.slebed.gdxvulkan.demoapp;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import android.os.Debug;
import android.os.Process;

import io.github.slebed.gdxvulkan.demos.Backend;

/** What both demo activities share: the CPU clocks the on-screen numbers need and the log file the test scripts read. */
final class PhoneStats {
    private final File file;
    private final int seconds;
    private long last;

    /** @param seconds write the numbers on screen to {@code files/gdxvk-demos.log} every this many seconds; 0 for never */
    PhoneStats (File dir, int seconds) {
        this.file = new File(dir, "gdxvk-demos.log");
        this.seconds = seconds;
        if (seconds > 0) file.delete();
        Backend.processCpuNanos = () -> Process.getElapsedCpuTime() * 1_000_000L; // milliseconds: coarse, but a window is half a second
        Backend.threadCpuNanos = Debug::threadCpuTimeNanos;
    }

    /** Call once per frame. */
    void frame () {
        if (seconds <= 0) return;
        final long now = System.nanoTime();
        if (now - last < seconds * 1_000_000_000L) return;
        last = now;
        try (FileWriter w = new FileWriter(file, true)) {
            w.write("[demo] " + Backend.lines[0] + " | " + Backend.lines[3] + " | " + String.join(" | ", Backend.latestStats()) + "\n");
        } catch (IOException ignored) {
        }
    }
}
