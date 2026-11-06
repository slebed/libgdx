package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;

/**
 * GPU zones: marks written into the command stream give the GPU time of each stage of a frame. A frame that clears (zone 0), draws a full-screen pass with a long
 * loop (zone 1) and draws a trivial one (zone 2) must report zone 1 as by far the longest, and the zones must not add up to more than the frame's GPU time.
 */
class GpuZoneTest {
    private static final int W = 320, H = 240;

    private static final class Probe extends ApplicationAdapter {
        int frames;
        Throwable failure;
        Mesh mesh;
        ShaderProgram heavy, light;
        long frameNs;
        final long[] zoneNs = new long[3];

        @Override public void create () {
            final String vertex = "attribute vec4 a_position;\nvoid main() {\n    gl_Position = a_position;\n}\n";
            heavy = new ShaderProgram(vertex, "#ifdef GL_ES\nprecision highp float;\n#endif\nuniform float u_n;\nvoid main() {\n    float a = gl_FragCoord.x * 0.001;\n"
                + "    for (int i = 0; i < 3000; i++) a = fract(a * 1.0001 + sin(a) * 0.37 + float(i) * 1e-4);\n    gl_FragColor = vec4(a, 0.2, 0.3, 1.0);\n}\n");
            light = new ShaderProgram(vertex, "#ifdef GL_ES\nprecision mediump float;\n#endif\nvoid main() {\n    gl_FragColor = vec4(0.1, 0.2, 0.3, 1.0);\n}\n");
            if (!heavy.isCompiled()) throw new IllegalStateException(heavy.getLog());
            if (!light.isCompiled()) throw new IllegalStateException(light.getLog());
            mesh = new Mesh(true, 6, 0, new VertexAttribute(1, 4, "a_position"));
            mesh.setVertices(new float[] {-1, -1, 0, 1, 1, -1, 0, 1, 1, 1, 0, 1, -1, -1, 0, 1, 1, 1, 0, 1, -1, 1, 0, 1});
        }

        @Override public void render () {
            try {
                frames++;
                final GdxVkFrame frame = GdxVkFrame.current();
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                frame.stream().gpuZone(0);
                heavy.bind();
                mesh.render(heavy, GL20.GL_TRIANGLES);
                frame.stream().gpuZone(1);
                light.bind();
                mesh.render(light, GL20.GL_TRIANGLES);
                frame.stream().gpuZone(2);
                if (frames == 30) {
                    final GdxVkPresenter presenter = frame.presenter();
                    frameNs = presenter.gpuFrameNanos();
                    for (int z = 0; z < 3; z++) zoneNs[z] = presenter.gpuZoneNanos(z);
                    Gdx.app.exit();
                }
            } catch (Throwable t) {
                failure = t;
                Gdx.app.exit();
            }
        }

        @Override public void dispose () {
            if (mesh != null) mesh.dispose();
            if (heavy != null) heavy.dispose();
            if (light != null) light.dispose();
        }
    }

    @Test void zonesSplitTheGpuTimeOfAFrame () {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        GLFW.glfwTerminate();
        final VulkanDesktopConfiguration config = new VulkanDesktopConfiguration();
        config.width = W;
        config.height = H;
        config.resizable = false;
        config.disableAudio = true;
        config.vsync = false;
        final Probe probe = new Probe();
        new VulkanDesktopApplication(probe, config);
        if (probe.failure != null) throw new AssertionError("the listener failed", probe.failure);
        assertNotNull(probe.zoneNs);
        System.out.println("[gpu zones] frame " + probe.frameNs / 1000 + " us; clear " + probe.zoneNs[0] / 1000 + " us, heavy " + probe.zoneNs[1] / 1000 + " us, light " + probe.zoneNs[2] / 1000 + " us");
        assumeTrue(probe.frameNs > 0, "this device takes no GPU timestamps");
        assertTrue(probe.zoneNs[1] > 0 && probe.zoneNs[2] >= 0, "the marked zones report a time");
        assertTrue(probe.zoneNs[1] > 5 * Math.max(probe.zoneNs[0], probe.zoneNs[2]), "the long loop is by far the longest zone: " + probe.zoneNs[1] + " against " + probe.zoneNs[0] + " and " + probe.zoneNs[2]);
        assertTrue(probe.zoneNs[0] + probe.zoneNs[1] + probe.zoneNs[2] <= probe.frameNs + probe.frameNs / 20, "the zones do not add up to more than the frame");
    }
}
