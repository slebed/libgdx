package io.github.slebed.gdxvulkan.testapp;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Logcat plus a file. Some devices restrict logcat over adb entirely, so results are also written to
 * {@code files/gdxvk-test.log} and read with {@code adb shell run-as io.github.slebed.gdxvulkan.testapp cat files/gdxvk-test.log}.
 */
final class TestLog {
    private static File file;

    private TestLog() {}

    static synchronized void init(File f) {
        file = f;
        write("--- log started");
    }

    static void i(String tag, String msg) {
        Log.i(tag, msg);
        write("I " + msg);
    }

    static void e(String tag, String msg) {
        Log.e(tag, msg);
        write("E " + msg);
    }

    static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        write("E " + msg + "\n" + sw);
    }

    private static synchronized void write(String line) {
        if (file == null) return;
        try (FileWriter w = new FileWriter(file, !line.startsWith("--- log started"))) {
            w.write(line + "\n");
        } catch (IOException ignored) {
            // nothing sensible left to do
        }
    }
}
