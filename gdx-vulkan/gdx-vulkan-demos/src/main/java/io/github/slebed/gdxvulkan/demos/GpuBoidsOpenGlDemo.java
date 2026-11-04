package io.github.slebed.gdxvulkan.demos;

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
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * The boids of {@link GpuBoidsDemo} on stock libGDX and OpenGL 4.3: the same simulation in a compute shader, through {@code Gdx.gl31}. The boids stay in a
 * shader storage buffer; the vertex shader reads them from there and one instanced draw call draws all of them, so nothing travels to the CPU.
 *
 * <p>What stock libGDX does not give you is visible in the code: a compute program is made with raw GL calls (libGDX's {@code ShaderProgram} takes only vertex
 * and fragment shaders), there is no culling on the GPU, so every boid is drawn, and no clustered lights, so there is one directional light.
 * Needs OpenGL 4.3, which {@link GlDemos} asks the window for.
 */
public final class GpuBoidsOpenGlDemo implements Demo {
    private static final int GL_COMPUTE_SHADER = 0x91B9; // libGDX's GL31 has the bit but not this constant

    private final int count = BoidSwarm.count();
    private Model cone;
    private Texture texture;
    private Mesh mesh;
    private ShaderProgram drawShader;
    private int computeProgram, boidBuffer, gridBuffer;
    private int uMode, uCount, uDt, uHalf, uMaxSpeed, uParams;
    private boolean paused;
    private float time;
    private PerspectiveCamera camera;
    private Hud hud;

    @Override public String title () {
        return "GPU boids, stock libGDX";
    }

    @Override public String summary () {
        return count + " boids simulated by an OpenGL compute shader and drawn with one instanced draw call. stock libGDX, no gdx-vulkan classes.";
    }

    @Override public String[] notes () {
        return new String[] {"Every boid is drawn, with no culling on the GPU.", "One directional light: libGDX has no clustered lights.", "Space pauses."};
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

        // The boids and the simulation's grid are two shader storage buffers.
        final float[] state = BoidSwarm.initialState(count);
        final FloatBuffer data = BufferUtils.newFloatBuffer(state.length);
        data.put(state).flip();
        boidBuffer = gl.glGenBuffer();
        gl.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, boidBuffer);
        gl.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, state.length * 4, data, GL20.GL_DYNAMIC_DRAW);
        gridBuffer = gl.glGenBuffer();
        gl.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, gridBuffer);
        gl.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, BoidSwarm.GRID * BoidSwarm.GRID * BoidSwarm.GRID * 8 * 4, null, GL20.GL_DYNAMIC_DRAW);
        gl.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, 0, boidBuffer);
        gl.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, 1, gridBuffer);

        computeProgram = linkCompute(gl, Gdx.files.classpath("demos/boids.comp").readString());
        uMode = gl.glGetUniformLocation(computeProgram, "u_mode");
        uCount = gl.glGetUniformLocation(computeProgram, "u_count");
        uDt = gl.glGetUniformLocation(computeProgram, "u_dt");
        uHalf = gl.glGetUniformLocation(computeProgram, "u_half");
        uMaxSpeed = gl.glGetUniformLocation(computeProgram, "u_maxSpeed");
        uParams = gl.glGetUniformLocation(computeProgram, "u_params");

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

        // The simulation: three dispatches, each waiting for the one before (a barrier on the storage buffers).
        gl.glUseProgram(computeProgram);
        gl.glUniform1i(uCount, count);
        gl.glUniform1f(uDt, dt);
        gl.glUniform1f(uHalf, BoidSwarm.HALF);
        gl.glUniform1f(uMaxSpeed, BoidSwarm.MAX_SPEED);
        gl.glUniform4f(uParams, BoidSwarm.COHESION, BoidSwarm.ALIGNMENT, BoidSwarm.SEPARATION, BoidSwarm.SWIRL);
        final int gridUints = BoidSwarm.GRID * BoidSwarm.GRID * BoidSwarm.GRID * 8;
        gl.glUniform1i(uMode, 0);
        gl.glDispatchCompute((gridUints + 255) / 256, 1, 1);
        gl.glMemoryBarrier(GL31.GL_SHADER_STORAGE_BARRIER_BIT);
        gl.glUniform1i(uMode, 1);
        gl.glDispatchCompute((count + 255) / 256, 1, 1);
        gl.glMemoryBarrier(GL31.GL_SHADER_STORAGE_BARRIER_BIT);
        gl.glUniform1i(uMode, 2);
        gl.glDispatchCompute((count + 255) / 256, 1, 1);
        gl.glMemoryBarrier(GL31.GL_SHADER_STORAGE_BARRIER_BIT);

        final float angle = time * 0.25f;
        camera.position.set((float)Math.cos(angle) * BoidSwarm.HALF * 1.25f, BoidSwarm.HALF * 0.35f, (float)Math.sin(angle) * BoidSwarm.HALF * 1.25f);
        camera.lookAt(0f, 0f, 0f);
        camera.update();

        Gdx.gl.glClearColor(0.02f, 0.03f, 0.06f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        drawShader.bind();
        drawShader.setUniformMatrix("u_projView", camera.combined);
        texture.bind(0);
        drawShader.setUniformi("u_texture", 0);
        mesh.bind(drawShader);
        Gdx.gl30.glDrawElementsInstanced(GL20.GL_TRIANGLES, mesh.getNumIndices(), GL20.GL_UNSIGNED_SHORT, 0, count);
        mesh.unbind(drawShader);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);

        hud.draw(title(), summary(), notes(), Backend.name, Gdx.graphics.getFramesPerSecond() + " fps", "boids: " + count + ", all drawn", "lights: 1 directional");
    }

    @Override public void pause () {}

    @Override public void resume () {}

    @Override public void dispose () {
        final GL31 gl = Gdx.gl31;
        hud.dispose();
        drawShader.dispose();
        gl.glDeleteProgram(computeProgram);
        gl.glDeleteBuffer(boidBuffer);
        gl.glDeleteBuffer(gridBuffer);
        cone.dispose();
        texture.dispose();
    }
}
