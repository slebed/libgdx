package io.github.slebed.gdxvulkan.demos;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/**
 * The scene of the two shadow demos: a long road lined with columns that carry spheres, a camera that flies along it and back, and a sun that circles slowly.
 * Plain libGDX models and camera; the demos differ only in how they light and shadow it. The columns go on for 200 units on purpose: a shadow map has to cover
 * both the column next to the camera and the one at the end of the road.
 */
final class ShadowWorld implements Disposable {
    static final float ROAD_LENGTH = 220f, COLUMN_SPACING = 11f;

    final Array<ModelInstance> instances = new Array<ModelInstance>();
    final PerspectiveCamera camera;
    private final Model floor, column, ball;
    private float time;

    ShadowWorld (int width, int height) {
        final int usage = Usage.Position | Usage.Normal;
        final ModelBuilder builder = new ModelBuilder();
        floor = builder.createBox(40f, 0.4f, ROAD_LENGTH + 40f, new Material(ColorAttribute.createDiffuse(0.78f, 0.74f, 0.66f, 1f)), usage);
        column = builder.createBox(1.4f, 8f, 1.4f, new Material(ColorAttribute.createDiffuse(0.86f, 0.32f, 0.24f, 1f)), usage);
        ball = builder.createSphere(2.4f, 2.4f, 2.4f, 24, 16, new Material(ColorAttribute.createDiffuse(0.25f, 0.45f, 0.9f, 1f)), usage);

        instances.add(new ModelInstance(floor, 0f, -0.2f, -ROAD_LENGTH / 2f));
        for (float z = -5f; z > -ROAD_LENGTH; z -= COLUMN_SPACING) {
            for (float x : new float[] {-7f, 7f}) {
                instances.add(new ModelInstance(column, x, 4f, z));
                instances.add(new ModelInstance(ball, x, 9.4f, z));
            }
        }

        camera = new PerspectiveCamera(60f, width, height);
        camera.near = 0.5f;
        camera.far = ROAD_LENGTH;
        update(0f, 0f);
    }

    /** Moves the camera along the road and the sun around the sky. */
    void update (float delta, float aspectScale) {
        time += delta;
        final float z = -60f + 70f * MathUtils.sin(time * 0.18f);
        camera.position.set(MathUtils.sin(time * 0.31f) * 3f, 4.5f + MathUtils.sin(time * 0.23f), z);
        camera.up.set(0f, 1f, 0f);
        camera.lookAt(MathUtils.sin(time * 0.17f) * 4f, 3f, z - 40f);
        camera.update();
    }

    /** The direction the sunlight travels in: low enough to throw long shadows across the road, turning slowly. */
    Vector3 sunDirection (Vector3 out) {
        final float a = time * 0.2f;
        return out.set(MathUtils.cos(a) * 0.7f, -0.8f, MathUtils.sin(a) * 0.5f - 0.35f).nor();
    }

    void resize (int width, int height) {
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.update();
    }

    @Override public void dispose () {
        floor.dispose();
        column.dispose();
        ball.dispose();
    }
}
