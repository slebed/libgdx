package io.github.slebed.gdxvulkan.demos;

import java.util.Random;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Model;

import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.g3d.VulkanComputeShader;
import io.github.slebed.gdxvulkan.g3d.VulkanGpuInstances;

/**
 * A swarm of boids that lives on the GPU. A compute shader moves them, a second one removes the ones outside the view, a third sorts 500 point lights into
 * screen clusters, and one indirect draw call draws what is left. The CPU's work for a frame is a few dispatches and one draw: the boids are never read back.
 *
 * <p>libGDX has no class for this, but OpenGL 4.3 can do the simulation, the culling and the indirect draw with compute shaders, and {@link GpuBoidsOpenGlDemo} does,
 * through {@code Gdx.gl31}. It has no clustered lights: it is lit by a single directional light.
 */
public final class GpuBoidsDemo implements Demo {
    private static final int LIGHTS = 500;

    private final int count = BoidSwarm.count();
    private Model cone;
    private Texture texture;
    private VulkanGpuInstances swarm;
    private VulkanComputeShader boids;
    private final float[] clearPush = VulkanComputeShader.newPush(), accumulatePush = VulkanComputeShader.newPush(), updatePush = VulkanComputeShader.newPush();
    private int clearGroups, boidGroups, visible;
    private float[] lights;
    private boolean lit = Boolean.parseBoolean(System.getProperty("demo.lights", "false")), paused; // -Ddemo.lights=true starts with the lights on
    private float time;
    private int frames;
    private PerspectiveCamera camera;
    private Hud hud;

    @Override public String title () {
        return "GPU boids";
    }

    @Override public String summary () {
        return count + " boids are simulated, culled and drawn on the GPU: three compute dispatches, a culling pass and one indirect draw. Exactly the scene of the OpenGL version, "
            + (lit ? "with " + LIGHTS + " point lights added, which OpenGL has no equivalent for: this picture is no longer like for like (L switches them off)."
                : "pixel for pixel, so the numbers below can be compared.");
    }

    @Override public String[] notes () {
        return new String[] {"libGDX has no class for this; the OpenGL version is raw Gdx.gl31 code doing the same work." + (Controls.touch() ? "" : " Press C to start it beside this window."),
            "Compare the frame, CPU and GPU lines at the bottom.", "L adds " + LIGHTS + " coloured point lights sorted into clusters: OpenGL has no equivalent here, so it is the one thing that is not like for like. Space pauses."};
    }

    @Override public String openGlCounterpart () {
        return "boids";
    }

    @Override public void create () {
        final GdxVkFrame frame = GdxVkFrame.current();
        cone = BoidSwarm.cone();
        texture = BoidSwarm.checkerboard();
        final int gridUints = BoidSwarm.GRID * BoidSwarm.GRID * BoidSwarm.GRID * 8;
        // The boids and the lights live in one buffer on the GPU; "user" is room in it for the simulation's grid.
        swarm = new VulkanGpuInstances(frame, cone.meshes.first(), texture, count, LIGHTS, gridUints);
        swarm.setInstances(BoidSwarm.initialState(count), count);
        lights = lights();
        swarm.setLights(lights, lit ? LIGHTS : 0);

        boids = new VulkanComputeShader(frame, "boids", "boids.comp");
        final int instanceOffset = swarm.instanceOffsetUints(), gridOffset = swarm.userOffsetUints();
        VulkanComputeShader.ints(clearPush, 0, count, instanceOffset, gridOffset);
        VulkanComputeShader.ints(accumulatePush, 1, count, instanceOffset, gridOffset);
        VulkanComputeShader.ints(updatePush, 2, count, instanceOffset, gridOffset);
        for (float[] push : new float[][] {clearPush, accumulatePush, updatePush}) {
            push[25] = BoidSwarm.HALF;
            push[27] = BoidSwarm.MAX_SPEED;
            push[28] = BoidSwarm.COHESION;
            push[29] = BoidSwarm.ALIGNMENT;
            push[30] = BoidSwarm.SEPARATION;
            push[31] = BoidSwarm.SWIRL;
        }
        clearGroups = VulkanComputeShader.groups(gridUints, 256);
        boidGroups = VulkanComputeShader.groups(count, 256);

        camera = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 1f;
        camera.far = 600f;
        hud = new Hud();
    }

    /** 500 lights spread through the volume, each a different colour. */
    private static float[] lights () {
        final Random random = new Random(7);
        final float[] data = new float[LIGHTS * VulkanGpuInstances.LIGHT_FLOATS];
        for (int l = 0; l < LIGHTS; l++) {
            final int b = l * VulkanGpuInstances.LIGHT_FLOATS;
            data[b] = (random.nextFloat() * 2 - 1) * BoidSwarm.HALF * 0.8f;     // position
            data[b + 1] = (random.nextFloat() * 2 - 1) * BoidSwarm.HALF * 0.8f;
            data[b + 2] = (random.nextFloat() * 2 - 1) * BoidSwarm.HALF * 0.8f;
            data[b + 3] = 14f + random.nextFloat() * 10f;                          // radius
            final float hue = random.nextFloat() * 6.28f;
            data[b + 4] = 0.4f + 0.6f * (float)Math.abs(Math.sin(hue));            // colour
            data[b + 5] = 0.4f + 0.6f * (float)Math.abs(Math.sin(hue + 2.1f));
            data[b + 6] = 0.4f + 0.6f * (float)Math.abs(Math.sin(hue + 4.2f));
            data[b + 7] = 2.5f;                                                    // intensity
        }
        return data;
    }

    @Override public void resize (int width, int height) {
        if (camera == null) return;
        camera.viewportWidth = width;
        camera.viewportHeight = height;
    }

    @Override public void render () {
        if (Controls.justPressed(Input.Keys.SPACE)) paused = !paused;
        if (Controls.justPressed(Input.Keys.L)) {
            lit = !lit;
            swarm.setLights(lights, lit ? LIGHTS : 0);
        }
        final float delta = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        final float dt = paused ? 0f : delta;
        time += paused ? 0f : delta;
        for (float[] push : new float[][] {clearPush, accumulatePush, updatePush}) push[24] = dt;

        final float angle = time * 0.25f;
        camera.position.set((float)Math.cos(angle) * BoidSwarm.HALF * 1.25f, BoidSwarm.HALF * 0.35f, (float)Math.sin(angle) * BoidSwarm.HALF * 1.25f);
        camera.lookAt(0f, 0f, 0f);
        camera.update();

        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glClearColor(0.02f, 0.03f, 0.06f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        // The simulation: three dispatches over the buffer the boids are drawn from. They are queued here and run on the GPU before the draw.
        boids.dispatch(clearGroups, clearPush, swarm.buffer());
        boids.dispatch(boidGroups, accumulatePush, swarm.buffer());
        boids.dispatch(boidGroups, updatePush, swarm.buffer());
        // The culling, the light sorting and the indirect draw.
        swarm.render(camera, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());

        if (frames++ % 30 == 0) visible = swarm.readVisibleCount(); // the one read back, twice a second, for the number on screen
        hud.draw(title(), summary(), notes(), "boids: " + count + ", drawn after GPU culling: " + visible,
            lit ? "lights: 1 directional + " + LIGHTS + " clustered point lights (not in the OpenGL version)" : "lights: 1 directional (as in the OpenGL version; L adds point lights)");
    }

    @Override public void pause () {}

    @Override public void resume () {}

    @Override public void dispose () {
        hud.dispose();
        swarm.dispose();
        cone.dispose();
        texture.dispose();
    }
}
