package io.github.slebed.gdxvulkan.demos;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;

/**
 * The demo app's menu and the loop around a running demo. A demo is started with its number key, Esc returns to the menu (and leaves the app from the menu), and
 * C starts the OpenGL counterpart of the running demo in a window beside this one.
 */
public final class DemoMenu implements ApplicationListener {
    /** Makes a new demo each time it is started, so a demo never sees the state of its last run. */
    private interface Factory {
        Demo create ();
    }

    private static final Factory[] DEMOS = {
        () -> new StockShadowsDemo(),
        () -> new CascadedShadowsDemo(),
        () -> new GpuBoidsDemo()};

    private final Demo[] listed = new Demo[DEMOS.length];
    private final Launcher launcher;
    private Demo running;
    private Hud hud;
    private int width, height;

    /** @param launcher starts the OpenGL counterparts; null leaves the C key out */
    public DemoMenu (Launcher launcher) {
        this.launcher = launcher;
    }

    @Override public void create () {
        for (int i = 0; i < DEMOS.length; i++) listed[i] = DEMOS[i].create(); // only read for their titles
        hud = new Hud();
    }

    @Override public void resize (int width, int height) {
        this.width = width;
        this.height = height;
        if (running != null) running.resize(width, height);
    }

    @Override public void render () {
        if (running != null) {
            if (Controls.justPressed(Input.Keys.ESCAPE)) {
                stop();
                return; // this Esc has been used: it must not also leave the app
            } else {
                if (Gdx.input.isKeyJustPressed(Input.Keys.C) && launcher != null && running.openGlCounterpart() != null) launcher.start(running.openGlCounterpart());
                running.render();
                return;
            }
        }
        if (Controls.justPressed(Input.Keys.ESCAPE)) {
            Gdx.app.exit();
            return;
        }
        for (int i = 0; i < DEMOS.length; i++) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1 + i)) start(i);
        }
        drawMenu();
        if (Controls.touch() && Gdx.input.justTouched()) {
            final int hit = hud.buttonAt(Gdx.input.getX(), Gdx.input.getY());
            if (hit >= 0) start(hit);
        }
    }

    /** Starts the demo with this index (0 is the first in the menu). */
    public void start (int index) {
        running = DEMOS[index].create();
        running.create();
        running.resize(width, height);
    }

    private void stop () {
        running.dispose();
        running = null;
    }

    private void drawMenu () {
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        final String[] lines = new String[DEMOS.length + 1];
        for (int i = 0; i < DEMOS.length; i++) lines[i] = (i + 1) + "   " + listed[i].title();
        lines[DEMOS.length] = "Esc leaves.";
        if (Controls.touch()) {
            final String[] labels = new String[DEMOS.length];
            for (int i = 0; i < DEMOS.length; i++) labels[i] = listed[i].title();
            hud.draw("gdx-vulkan demos", "Tap a demo. In a demo, the Back button comes back here (from the menu it leaves).", new String[0]);
            hud.drawButtons(labels);
            return;
        }
        hud.draw("gdx-vulkan demos", "Press the number of a demo. In a demo, Esc comes back here and C starts the stock OpenGL version beside it.", lines);
    }

    @Override public void pause () {
        if (running != null) running.pause();
    }

    @Override public void resume () {
        if (running != null) running.resume();
    }

    @Override public void dispose () {
        if (running != null) stop();
        hud.dispose();
        if (launcher != null) launcher.stopAll();
    }
}
