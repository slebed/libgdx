package io.github.slebed.gdxvulkan.desktop;

/** What {@link VulkanDesktopInput} needs from its window (stock's input reads these from an Lwjgl3Window, which this renderer does not have). */
public interface DesktopInputHost {
    long windowHandle ();

    void requestRendering ();

    /** True when the window reports sizes in pixels that differ from the logical size (HdpiMode.Pixels). */
    boolean hdpiPixels ();

    int backBufferWidth ();

    int backBufferHeight ();

    int logicalWidth ();

    int logicalHeight ();
}
