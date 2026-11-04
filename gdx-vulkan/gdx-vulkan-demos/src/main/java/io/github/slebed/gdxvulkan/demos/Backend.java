package io.github.slebed.gdxvulkan.demos;

/** Which renderer the running demo is on, for the numbers on screen. Set by the two mains. */
public final class Backend {
    private Backend () {}

    /** For example "gdx-vulkan on NVIDIA GeForce RTX 5080" or "stock libGDX, OpenGL 4.6". */
    public static volatile String name = "";
}
