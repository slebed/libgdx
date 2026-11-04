package io.github.slebed.gdxvulkan.demos;

import java.io.File;
import java.io.IOException;

/**
 * Starts the OpenGL counterpart of a demo in a second JVM, in a window beside the demo app's, so both can be watched at once (stock libGDX's OpenGL window and
 * gdx-vulkan's cannot live in one process). The second JVM runs {@link GlDemos} with the same class path.
 */
public final class OpenGlLauncher {
    private final int x, y, width, height;
    private Process process;

    /** @param x, y, width, height where the OpenGL window goes */
    public OpenGlLauncher (int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** Starts a demo by its id, closing the OpenGL window that is still open from the last time. */
    public void start (String demoId) {
        stopAll();
        final String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        final ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), GlDemos.class.getName(), demoId, String.valueOf(x),
            String.valueOf(y), String.valueOf(width), String.valueOf(height));
        builder.inheritIO();
        for (String property : new String[] {"demo.boids", "demo.vsync", "demo.lights"}) {
            final String value = System.getProperty(property);
            if (value != null) builder.command().add(1, "-D" + property + "=" + value);
        }
        try {
            process = builder.start();
        } catch (IOException e) {
            System.err.println("[demos] could not start the OpenGL version: " + e.getMessage());
        }
    }

    /** Closes the OpenGL window, if it is open. */
    public void stopAll () {
        if (process != null && process.isAlive()) process.destroy();
        process = null;
    }
}
