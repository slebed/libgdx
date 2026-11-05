package io.github.slebed.gdxvulkan.demos;

/** Starts the OpenGL counterpart of a demo beside the demo app: desktop only ({@link OpenGlLauncher}); the phone app has none. */
public interface Launcher {
    void start (String demoId);

    void stopAll ();
}
