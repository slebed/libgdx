package io.github.slebed.gdxvulkan.desktop;

import io.github.slebed.gdxvulkan.GdxVkConfig;

/** Settings of a {@link VulkanDesktopApplication}: the window and the Vulkan options. */
public class VulkanDesktopConfiguration {
    public String title = "gdx-vulkan";
    public int width = 640, height = 480;
    /** The window's position; -1 lets the system place it. */
    public int x = -1, y = -1;
    public boolean resizable = true;
    public boolean decorated = true;
    public boolean maximized = false;
    public boolean fullscreen = false;
    /** Wait for the display (FIFO) when true, present as fast as possible when false. */
    public boolean vsync = true;
    /** Caps the frame rate when above 0 (on top of vsync): sleeps to reach it. */
    public int foregroundFPS = 0;
    /** Redraws every frame when true; when false only after an event or {@code Gdx.graphics.requestRendering()}. */
    public boolean continuousRendering = true;
    /** Vulkan options: validation layers, tier, device index. Defaults come from the system properties like the tests'. */
    public GdxVkConfig vk = GdxVkConfig.fromSystemProperties();
    /** The colour the frame is cleared to before {@code render()}. */
    public float clearRed = 0f, clearGreen = 0f, clearBlue = 0f, clearAlpha = 1f;
    /** OpenAL: the number of sources and buffers, and the size of a streaming buffer. Ignored with {@link #disableAudio}. */
    public int audioDeviceSimultaneousSources = 16, audioDeviceBufferCount = 9, audioDeviceBufferSize = 512;
    public boolean disableAudio = false;
    /** Give the screen a depth buffer from the start (stock configurations have one by default). Without it the first 3D batch or GL_DEPTH_TEST turns it on, one frame late. */
    public boolean depthBuffer = false;
    /** Where {@code Gdx.app.getPreferences} keeps its files, relative to the working directory. */
    public String preferencesDirectory = ".prefs/";
}
