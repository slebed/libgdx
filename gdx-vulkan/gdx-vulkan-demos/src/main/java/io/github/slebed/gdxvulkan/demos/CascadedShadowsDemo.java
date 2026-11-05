package io.github.slebed.gdxvulkan.demos;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.math.Vector3;

import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.g3d.VulkanCascadedShadowLight;
import io.github.slebed.gdxvulkan.g3d.VulkanModelBatch;

/**
 * The scene of {@link StockShadowsDemo} with cascaded shadow maps, which stock libGDX does not have. The view is cut into four slices by distance and each slice
 * gets a map of its own, fitted to it: sharp shadows next to the camera and shadows all the way to the end of the road, from the same four maps' worth of memory
 * a single large map would use.
 *
 * <p>Compared with the stock recipe the code changes in three places: {@code VulkanCascadedShadowLight} instead of {@code DirectionalShadowLight}, a loop over the
 * cascades in the shadow pass, and {@code VulkanModelBatch} instead of {@code ModelBatch}.
 */
public final class CascadedShadowsDemo implements Demo {
    private static final int MAP_SIZE = 2048, CASCADES = 4;

    private ShadowWorld world;
    private VulkanModelBatch modelBatch, shadowBatch;
    private VulkanCascadedShadowLight shadowLight;
    private Environment environment;
    private Hud hud;
    private boolean paused;
    private final Vector3 sun = new Vector3();

    @Override public String title () {
        return "Cascaded shadows";
    }

    @Override public String summary () {
        return "The same road as the stock demo, with " + CASCADES + " cascaded shadow maps. stock libGDX has one map; here every distance gets the resolution it needs.";
    }

    @Override public String[] notes () {
        return new String[] {"Every column to the end of the road has a shadow.", "Shadows next to the camera are sharp.",
            "Press C to start the stock OpenGL version beside this window.", "Space pauses the sun and the camera."};
    }

    @Override public String openGlCounterpart () {
        return "shadows-stock"; // the closest stock libGDX can get: one shadow map
    }

    @Override public void create () {
        world = new ShadowWorld(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        environment = new Environment();
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.32f, 0.34f, 0.4f, 1f));
        // Four maps of 2048 x 2048 over the whole depth of the view; the casters are collected up to 90 units toward the sun.
        shadowLight = new VulkanCascadedShadowLight(MAP_SIZE, CASCADES, world.camera.near, world.camera.far, 0.7f, 90f);
        shadowLight.set(0.95f, 0.9f, 0.8f, -0.5f, -0.8f, -0.3f);
        environment.add(shadowLight);
        environment.shadowMap = shadowLight;
        modelBatch = new VulkanModelBatch();
        shadowBatch = VulkanModelBatch.depth(GdxVkFrame.current());
        hud = new Hud();
    }

    @Override public void resize (int width, int height) {
        if (world != null) world.resize(width, height);
    }

    @Override public void render () {
        if (Controls.justPressed(Input.Keys.SPACE)) paused = !paused;
        world.update(paused ? 0f : Gdx.graphics.getDeltaTime(), 0f);
        shadowLight.direction.set(world.sunDirection(sun));

        // The shadow pass: one depth map per slice of the view.
        shadowLight.update(world.camera);
        for (int i = 0; i < shadowLight.cascades(); i++) {
            shadowLight.begin(i);
            shadowBatch.begin(shadowLight.camera(i));
            shadowBatch.render(world.instances);
            shadowBatch.end();
            shadowLight.end(i);
        }

        // The picture; the shader picks the cascade for each pixel from its distance.
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glClearColor(0.55f, 0.7f, 0.9f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        modelBatch.begin(world.camera);
        modelBatch.render(world.instances, environment);
        modelBatch.end();

        hud.draw(title(), summary(), notes(), "shadow maps: " + CASCADES + " x " + MAP_SIZE + " x " + MAP_SIZE);
    }

    @Override public void pause () {}

    @Override public void resume () {}

    @Override public void dispose () {
        hud.dispose();
        modelBatch.dispose();
        shadowBatch.dispose();
        shadowLight.dispose();
        world.dispose();
    }
}
