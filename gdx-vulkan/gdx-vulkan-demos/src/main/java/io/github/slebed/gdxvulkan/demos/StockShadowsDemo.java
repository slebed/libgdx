package io.github.slebed.gdxvulkan.demos;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.math.Vector3;

/**
 * Shadows the way stock libGDX does them: one {@code DirectionalShadowLight}, whose single shadow map covers a patch of the scene around the camera. Nothing in
 * this class is specific to gdx-vulkan; it is the classic libGDX shadow recipe, and it runs unchanged on OpenGL ({@link GlDemos}) and on gdx-vulkan.
 *
 * <p>Watch the far end of the road: the map is spread over {@link #COVERED} units, so the columns beyond it have no shadows, and the shadows near the camera
 * are blocky because the map is stretched over a large area. {@link CascadedShadowsDemo} draws this same scene without either problem.
 */
public final class StockShadowsDemo implements Demo {
    private static final int MAP_SIZE = 2048;
    private static final float COVERED = 60f;

    private ShadowWorld world;
    private ModelBatch modelBatch, shadowBatch;
    private DirectionalShadowLight shadowLight;
    private Environment environment;
    private Hud hud;
    private boolean paused;
    private final Vector3 sun = new Vector3(), focus = new Vector3();

    @Override public String title () {
        return "Shadows, stock libGDX code";
    }

    @Override public String summary () {
        return "libGDX's own shadow recipe: one DirectionalShadowLight and a DepthShaderProvider. This code is the same on OpenGL and on gdx-vulkan.";
    }

    @Override public String[] notes () {
        return new String[] {"One shadow map covers " + (int)COVERED + " x " + (int)COVERED + " units around the camera.",
            "Look down the road: far columns have no shadows, near ones are blocky.", "Space pauses the sun and the camera."};
    }

    @Override public String openGlCounterpart () {
        return "shadows-stock";
    }

    @Override public void create () {
        world = new ShadowWorld(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        environment = new Environment();
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.32f, 0.34f, 0.4f, 1f));
        shadowLight = new DirectionalShadowLight(MAP_SIZE, MAP_SIZE, COVERED, COVERED, 1f, 150f);
        shadowLight.set(0.95f, 0.9f, 0.8f, -0.5f, -0.8f, -0.3f);
        environment.add(shadowLight);
        environment.shadowMap = shadowLight;
        modelBatch = new ModelBatch();
        shadowBatch = new ModelBatch(new DepthShaderProvider());
        hud = new Hud();
    }

    @Override public void resize (int width, int height) {
        if (world != null) world.resize(width, height);
    }

    @Override public void render () {
        if (Controls.justPressed(Input.Keys.SPACE)) paused = !paused;
        world.update(paused ? 0f : Gdx.graphics.getDeltaTime(), 0f);
        shadowLight.direction.set(world.sunDirection(sun));

        // The shadow pass: the scene seen from the sun, into the light's depth map, around the point the camera looks at.
        focus.set(world.camera.direction).scl(COVERED * 0.4f).add(world.camera.position);
        shadowLight.begin(focus, world.camera.direction);
        shadowBatch.begin(shadowLight.getCamera());
        shadowBatch.render(world.instances);
        shadowBatch.end();
        shadowLight.end();

        // The picture: the scene from the camera, reading the depth map.
        Gdx.gl.glClearColor(0.55f, 0.7f, 0.9f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        modelBatch.begin(world.camera);
        modelBatch.render(world.instances, environment);
        modelBatch.end();

        hud.draw(title(), summary(), notes(), "shadow map: 1 x " + MAP_SIZE + " x " + MAP_SIZE);
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
