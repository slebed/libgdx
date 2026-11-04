package io.github.slebed.gdxvulkan.demos;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GL31;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * The boids of {@link GpuBoidsDemo} on stock libGDX and OpenGL 4.3, doing the same work: the same simulation in a compute shader, a second compute shader that culls
 * the boids against the camera's frustum, and one indirect draw call whose instance count comes from the GPU. Everything goes through {@code Gdx.gl31}; libGDX has
 * no class for any of it, so the compute programs are made with raw GL calls ({@code ShaderProgram} takes only vertex and fragment shaders) and the buffers are
 * plain GL buffers. The one thing this version does not have is the 500 clustered point lights: it is lit by a single directional light.
 *
 * <p>Needs OpenGL 4.3, which {@link GlDemos} asks the window for.
 */
public final class GpuBoidsOpenGlDemo implements Demo {
    private static final int GL_COMPUTE_SHADER = 0x91B9;        // libGDX's GL31 has the bit but not this constant
    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;
    private static final int GL_COMMAND_BARRIER_BIT = 0x40;
    private static final float BOID_RADIUS = 2.4f;              // bounding radius of the cone, before a boid's scale

    private final int count = BoidSwarm.count();
    private Model cone;
    private Texture texture;
    private Mesh mesh;
    private ShaderProgram drawShader;
    private int simulate, cull;
    private int simMode, simCount, simDt, simHalf, simMaxSpeed, simParams;
    private int cullMode, cullCount, cullIndexCount, cullRadius, cullPlanes;
    private int boidBuffer, gridBuffer, visibleBuffer;
    private final FloatBuffer planes = BufferUtils.newFloatBuffer(24);
    private boolean paused;
    private float time;
    private int frames, visible;
    private PerspectiveCamera camera;
    private Hud hud;

    @Override public String title () {
        return "GPU boids, stock libGDX";
    }

    @Override public String summary () {
        return count + " boids simulated, culled and drawn on the GPU with OpenGL 4.3 compute shaders and one indirect draw call. libGDX has no class for this: "
            + "it is raw Gdx.gl31 code.";
    }

    @Override public String[] notes () {
        return new String[] {"The same work as the gdx-vulkan version: simulation, GPU culling, indirect draw.", "One directional light; the gdx-vulkan version has 500 clustered point lights.",
            "Space pauses."};
    }

    @Override public String openGlCounterpart () {
        return null; // this is the OpenGL side
    }

    @Override public void create () {
        if (Gdx.gl31 == null) throw new GdxRuntimeException("this demo needs OpenGL 4.3 (compute shaders)");
        final GL31 gl = Gdx.gl31;
        cone = BoidSwarm.cone();
        mesh = cone.meshes.first();
        texture = BoidSwarm.checkerboard();

        // Three shader storage buffers: the boids, the simulation's grid, and the list of boids that are inside the view (headed by the indirect draw command).
        final float[] state = BoidSwarm.initialState(count);
        final FloatBuffer data = BufferUtils.newFloatBuffer(state.length);
        data.put(state).flip();
        boidBuffer = gl.glGenBuffer();
        gl.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, boidBuffer);
        gl.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, state.length * 4, data, GL20.GL_DYNAMIC_DRAW);
        gridBuffer = gl.glGenBuffer();
        gl.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, gridBuffer);
        gl.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, BoidSwarm.GRID * BoidSwarm.GRID * BoidSwarm.GRID * 8 * 4, null, GL20.GL_DYNAMIC_DRAW);
        visibleBuffer = gl.glGenBuffer();
        gl.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, visibleBuffer);
        gl.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, (5 + count) * 4, null, GL20.GL_DYNAMIC_DRAW);
        gl.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, 0, boidBuffer);
        gl.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, 1, gridBuffer);
        gl.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, 2, visibleBuffer);

        simulate = linkCompute(gl, Gdx.files.classpath("demos/boids.comp").readString());
        simMode = gl.glGetUniformLocation(simulate, "u_mode");
        simCount = gl.glGetUniformLocation(simulate, "u_count");
        simDt = gl.glGetUniformLocation(simulate, "u_dt");
        simHalf = gl.glGetUniformLocation(simulate, "u_half");
        simMaxSpeed = gl.glGetUniformLocation(simulate, "u_maxSpeed");
        simParams = gl.glGetUniformLocation(simulate, "u_params");
        cull = linkCompute(gl, Gdx.files.classpath("demos/boids_cull.comp").readString());
        cullMode = gl.glGetUniformLocation(cull, "u_mode");
        cullCount = gl.glGetUniformLocation(cull, "u_count");
        cullIndexCount = gl.glGetUniformLocation(cull, "u_indexCount");
        cullRadius = gl.glGetUniformLocation(cull, "u_radius");
        cullPlanes = gl.glGetUniformLocation(cull, "u_planes");

        drawShader = new ShaderProgram(Gdx.files.classpath("demos/boids.vert"), Gdx.files.classpath("demos/boids.frag"));
        if (!drawShader.isCompiled()) throw new GdxRuntimeException("the draw shader did not compile:\n" + drawShader.getLog());

        camera = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 1f;
        camera.far = 600f;
        hud = new Hud();
    }

    /** libGDX's ShaderProgram has no compute stage: a compute program is compiled and linked with the GL calls themselves. */
    private static int linkCompute (GL31 gl, String source) {
        final int shader = gl.glCreateShader(GL_COMPUTE_SHADER);
        gl.glShaderSource(shader, source);
        gl.glCompileShader(shader);
        final IntBuffer status = BufferUtils.newIntBuffer(1);
        gl.glGetShaderiv(shader, GL20.GL_COMPILE_STATUS, status);
        if (status.get(0) == 0) throw new GdxRuntimeException("the compute shader did not compile:\n" + gl.glGetShaderInfoLog(shader));
        final int program = gl.glCreateProgram();
        gl.glAttachShader(program, shader);
        gl.glLinkProgram(program);
        gl.glGetProgramiv(program, GL20.GL_LINK_STATUS, status);
        if (status.get(0) == 0) throw new GdxRuntimeException("the compute program did not link:\n" + gl.glGetProgramInfoLog(program));
        gl.glDeleteShader(shader);
        return program;
    }

    @Override public void resize (int width, int height) {
        if (camera == null) return;
        camera.viewportWidth = width;
        camera.viewportHeight = height;
    }

    @Override public void render () {
        final GL31 gl = Gdx.gl31;
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) paused = !paused;
        final float delta = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        final float dt = paused ? 0f : delta;
        time += dt;

        final float angle = time * 0.25f;
        camera.position.set((float)Math.cos(angle) * BoidSwarm.HALF * 1.25f, BoidSwarm.HALF * 0.35f, (float)Math.sin(angle) * BoidSwarm.HALF * 1.25f);
        camera.lookAt(0f, 0f, 0f);
        camera.update();

        // The simulation: three dispatches, each waiting for the one before (a barrier on the storage buffers).
        gl.glUseProgram(simulate);
        gl.glUniform1i(simCount, count);
        gl.glUniform1f(simDt, dt);
        gl.glUniform1f(simHalf, BoidSwarm.HALF);
        gl.glUniform1f(simMaxSpeed, BoidSwarm.MAX_SPEED);
        gl.glUniform4f(simParams, BoidSwarm.COHESION, BoidSwarm.ALIGNMENT, BoidSwarm.SEPARATION, BoidSwarm.SWIRL);
        final int gridUints = BoidSwarm.GRID * BoidSwarm.GRID * BoidSwarm.GRID * 8;
        gl.glUniform1i(simMode, 0);
        gl.glDispatchCompute((gridUints + 255) / 256, 1, 1);
        gl.glMemoryBarrier(GL31.GL_SHADER_STORAGE_BARRIER_BIT);
        gl.glUniform1i(simMode, 1);
        gl.glDispatchCompute((count + 255) / 256, 1, 1);
        gl.glMemoryBarrier(GL31.GL_SHADER_STORAGE_BARRIER_BIT);
        gl.glUniform1i(simMode, 2);
        gl.glDispatchCompute((count + 255) / 256, 1, 1);
        gl.glMemoryBarrier(GL31.GL_SHADER_STORAGE_BARRIER_BIT);

        // The culling: clear the draw command, then append every boid inside the frustum to the list.
        planes.clear();
        for (Plane plane : camera.frustum.planes) planes.put(plane.normal.x).put(plane.normal.y).put(plane.normal.z).put(plane.d);
        planes.flip();
        gl.glUseProgram(cull);
        gl.glUniform1i(cullCount, count);
        gl.glUniform1i(cullIndexCount, mesh.getNumIndices());
        gl.glUniform1f(cullRadius, BOID_RADIUS);
        gl.glUniform4fv(cullPlanes, 6, planes);
        gl.glUniform1i(cullMode, 0);
        gl.glDispatchCompute(1, 1, 1);
        gl.glMemoryBarrier(GL31.GL_SHADER_STORAGE_BARRIER_BIT);
        gl.glUniform1i(cullMode, 1);
        gl.glDispatchCompute((count + 255) / 256, 1, 1);
        gl.glMemoryBarrier(GL31.GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);

        Gdx.gl.glClearColor(0.02f, 0.03f, 0.06f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        drawShader.bind();
        drawShader.setUniformMatrix("u_projView", camera.combined);
        texture.bind(0);
        drawShader.setUniformi("u_texture", 0);
        mesh.bind(drawShader);
        gl.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, visibleBuffer);
        gl.glDrawElementsIndirect(GL20.GL_TRIANGLES, GL20.GL_UNSIGNED_SHORT, 0); // the instance count is in the buffer, written by the culling shader
        gl.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        mesh.unbind(drawShader);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);

        if (frames++ % 30 == 0) visible = readVisibleCount(gl); // the one read back, twice a second, for the number on screen
        hud.draw(title(), summary(), notes(), "boids: " + count + ", drawn after GPU culling: " + visible, "lights: 1 directional");
    }

    /** The instance count the culling shader wrote into the draw command. */
    private int readVisibleCount (GL31 gl) {
        gl.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, visibleBuffer);
        final ByteBuffer mapped = (ByteBuffer)gl.glMapBufferRange(GL31.GL_SHADER_STORAGE_BUFFER, 4, 4, GL30.GL_MAP_READ_BIT);
        final int n = mapped.order(ByteOrder.nativeOrder()).getInt(0);
        gl.glUnmapBuffer(GL31.GL_SHADER_STORAGE_BUFFER);
        return n;
    }

    @Override public void pause () {}

    @Override public void resume () {}

    @Override public void dispose () {
        final GL31 gl = Gdx.gl31;
        hud.dispose();
        drawShader.dispose();
        gl.glDeleteProgram(simulate);
        gl.glDeleteProgram(cull);
        gl.glDeleteBuffer(boidBuffer);
        gl.glDeleteBuffer(gridBuffer);
        gl.glDeleteBuffer(visibleBuffer);
        cone.dispose();
        texture.dispose();
    }
}
