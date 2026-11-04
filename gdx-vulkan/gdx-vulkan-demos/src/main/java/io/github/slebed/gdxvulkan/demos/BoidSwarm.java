package io.github.slebed.gdxvulkan.demos;

import java.util.Random;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

/**
 * What the two boids demos have in common: the swarm's starting state, the simulation's settings and the cone every boid is drawn as. The simulation itself
 * (a compute shader: {@code boids.comp} in gdx-vulkan, {@code boids.comp} in this module's resources for OpenGL) is the same algorithm in both.
 */
final class BoidSwarm {
    private BoidSwarm () {}

    /** Half the side of the cube the swarm lives in. */
    static final float HALF = 100f;
    /** Floats per boid: position and scale, the rotation (a quaternion), the velocity and one spare. */
    static final int FLOATS = 12;
    /** The simulation groups boids by the cell of a 32 x 32 x 32 grid they are in. */
    static final int GRID = 32;
    static final float MAX_SPEED = 14f, COHESION = 0.6f, ALIGNMENT = 1.2f, SEPARATION = 0.8f, SWIRL = 40f;

    /** How many boids: 250,000 unless {@code -Ddemo.boids=N}. */
    static int count () {
        return Integer.getInteger("demo.boids", 250_000);
    }

    /** The starting state of {@code count} boids: spread through a sphere, heading in random directions. */
    static float[] initialState (int count) {
        final Random random = new Random(42);
        final float[] data = new float[count * FLOATS];
        for (int i = 0; i < count; i++) {
            final int b = i * FLOATS;
            float x, y, z;
            do {
                x = random.nextFloat() * 2 - 1;
                y = random.nextFloat() * 2 - 1;
                z = random.nextFloat() * 2 - 1;
            } while (x * x + y * y + z * z > 1);
            data[b] = x * 0.7f * HALF;
            data[b + 1] = y * 0.7f * HALF;
            data[b + 2] = z * 0.7f * HALF;
            data[b + 3] = 0.6f; // scale
            data[b + 7] = 1f; // the rotation's w: no turn yet
            final float dx = random.nextFloat() * 2 - 1, dy = random.nextFloat() * 2 - 1, dz = random.nextFloat() * 2 - 1;
            final float length = (float)Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-3f;
            data[b + 8] = dx / length * 12f;
            data[b + 9] = dy / length * 12f;
            data[b + 10] = dz / length * 12f;
        }
        return data;
    }

    /** A low cone pointing along +Z, the direction a boid heads in. */
    static Model cone () {
        final ModelBuilder builder = new ModelBuilder();
        builder.begin();
        final MeshPartBuilder part = builder.part("cone", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal | Usage.TextureCoordinates, new Material());
        part.setVertexTransform(new Matrix4().setToRotation(Vector3.X, 90f));
        part.cone(0.8f, 2.4f, 0.8f, 8);
        return builder.end();
    }

    /** A small blue and white checkerboard, so that the boids show their turns. */
    static Texture checkerboard () {
        final Pixmap pixmap = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                pixmap.setColor(((x / 4 + y / 4) & 1) == 0 ? Color.WHITE : new Color(0.7f, 0.8f, 1f, 1f));
                pixmap.drawPixel(x, y);
            }
        }
        final Texture texture = new Texture(pixmap);
        pixmap.dispose();
        return texture;
    }
}
