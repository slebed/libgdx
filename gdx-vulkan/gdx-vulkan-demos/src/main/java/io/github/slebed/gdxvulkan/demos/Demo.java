package io.github.slebed.gdxvulkan.demos;

import com.badlogic.gdx.ApplicationListener;

/**
 * A demo is an ordinary libGDX {@code ApplicationListener}: {@code create()} builds it, {@code render()} draws a frame, {@code dispose()} releases it. The demo app
 * and the OpenGL launcher both run it that way, which is why a demo written against stock libGDX needs no change to run on either backend.
 */
public interface Demo extends ApplicationListener {
    /** The name in the menu and the headline on screen. */
    String title ();

    /** One or two sentences: what the demo shows. */
    String summary ();

    /** What to look at, or what the controls are; one short line each. */
    String[] notes ();

    /**
     * The id of the demo that draws the same scene on stock libGDX and OpenGL ({@link GlDemos}), or null when stock libGDX has nothing to compare with. The demo app
     * starts it in a window beside its own when you press C.
     */
    String openGlCounterpart ();
}
