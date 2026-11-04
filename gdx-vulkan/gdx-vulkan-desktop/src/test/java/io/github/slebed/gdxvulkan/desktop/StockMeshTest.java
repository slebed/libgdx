package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Matrix4;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g3d.StockMeshAccess;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.glfw.GLFW;

/**
 * Stock {@code Mesh} data on Vulkan (plan, Phase 5, stage 1): the GL buffer shim, {@link StockMeshAccess}, the swapchain depth buffer and depth
 * testing, and the {@code Mesh.dispose()} release path. Drawn with a flat-colour shader; the stock-compatible shading is {@code VulkanModelBatch}'s.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockMeshTest {
    private static final int N = 192;

    private GdxVkContext ctx;
    private VulkanGL20 gl;
    private long window;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private StockMeshAccess access;
    private PerspectiveCamera camera; // built after TestGdx.install loads libGDX's natives (Matrix4 needs them)

    @BeforeAll void setUp() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        window = GLFW.glfwCreateWindow(N, N, "gdx-vulkan stock mesh", 0, 0);
        assumeTrue(window != 0, "Could not create a window");
        presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        presenter.setDepth(GdxVkPresenter.DEPTH);
        frame = new GdxVkFrame(ctx, presenter, gl);
        access = new StockMeshAccess(gl);
        camera = new PerspectiveCamera(60, N, N);
        camera.position.set(0, 0, 6);
        camera.lookAt(0, 0, 0);
        camera.near = 1;
        camera.far = 20;
        camera.update();
    }

    @AfterAll void tearDown() {
        TestGdx.uninstall();
        if (presenter != null) presenter.close();
        if (ctx != null) {
            assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
            ctx.close();
        }
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    private int pipeline() {
        GdxVkPipelineDesc d = new GdxVkPipelineDesc();
        d.stride = 24;
        d.attribute(0, GdxVkPipelineDesc.FORMAT_R32G32B32_SFLOAT, 0).attribute(1, GdxVkPipelineDesc.FORMAT_R32G32B32_SFLOAT, 12);
        d.topology = GdxVkPipelineDesc.TRIANGLES;
        d.cullMode = GdxVkPipelineDesc.CULL_BACK;
        return frame.pipeline("meshflat", "meshflat", "mesh_flat.vert", "mesh_flat.frag", d);
    }

    private void drawMesh(Mesh mesh, Matrix4 model, float r, float g, float b) {
        CommandStream s = frame.stream();
        s.bindPipeline(pipeline());
        float[] push = new float[32];
        Matrix4 m = new Matrix4(camera.combined).mul(model);
        System.arraycopy(m.val, 0, push, 0, 16);
        push[16] = r;
        push[17] = g;
        push[18] = b;
        push[19] = 1;
        s.pushConstants(push);
        int count = access.bind(frame, mesh);
        s.drawIndexed(count, 0, 0);
    }

    private byte[] frameOf(boolean depthTest, Runnable draws) {
        TestGdx.width = N;
        TestGdx.height = N;
        GLFW.glfwPollEvents();
        assertTrue(frame.begin(0.1f, 0.2f, 0.3f, 1f));
        if (depthTest) gl.glEnable(GL20.GL_DEPTH_TEST);
        else gl.glDisable(GL20.GL_DEPTH_TEST);
        gl.glDepthFunc(GL20.GL_LESS);
        gl.glClearColor(0.1f, 0.2f, 0.3f, 1f);
        gl.glClearDepthf(1f);
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        draws.run();
        gl.glDisable(GL20.GL_DEPTH_TEST);
        return frame.endAndCapture();
    }

    private int[] px(byte[] f, int x, int y) {
        int o = (y * N + x) * 4;
        return new int[] {f[o] & 255, f[o + 1] & 255, f[o + 2] & 255};
    }

    private void assertRedish(int[] c, String what) {
        assertTrue(c[0] > 150 && c[1] < 30 && c[2] < 30, what + " should be red, was " + java.util.Arrays.toString(c));
    }

    private void assertBluish(int[] c, String what) {
        assertTrue(c[2] > 150 && c[0] < 30 && c[1] < 30, what + " should be blue, was " + java.util.Arrays.toString(c));
    }

    private Mesh box() {
        ModelBuilder mb = new ModelBuilder();
        Model model = mb.createBox(2f, 2f, 2f, new Material(), Usage.Position | Usage.Normal);
        return model.meshes.first();
    }

    @Test void depthTestingOrdersStockBoxesWhateverTheDrawOrder() {
        final Mesh box = box();
        final Matrix4 near = new Matrix4().setToTranslation(0, 0, 1);
        final Matrix4 far = new Matrix4().setToTranslation(0, 0, -2).scale(3f, 1f, 1f); // wider, so it shows on both sides of the near one

        byte[] a = frameOf(true, () -> {
            drawMesh(box, far, 0, 0, 1);
            drawMesh(box, near, 1, 0, 0);
        });
        assertRedish(px(a, N / 2, N / 2), "centre, far drawn first");
        assertBluish(px(a, 40, N / 2), "far box beside the near one");

        byte[] b = frameOf(true, () -> {
            drawMesh(box, near, 1, 0, 0);
            drawMesh(box, far, 0, 0, 1);
        });
        assertRedish(px(b, N / 2, N / 2), "centre, near drawn first: the far box fails the depth test");
        assertBluish(px(b, 40, N / 2), "far box beside the near one");

        byte[] c = frameOf(false, () -> {
            drawMesh(box, near, 1, 0, 0);
            drawMesh(box, far, 0, 0, 1);
        });
        assertBluish(px(c, N / 2, N / 2), "with the depth test off the later draw wins: proves the test above does something");
        box.dispose();
    }

    @Test void aMeshChangedAfterItWasDrawnShowsItsNewVerticesAndDisposeReleasesTheBuffers() {
        final int before = ctx.resources().stats().buffersLive;
        final Mesh quad = new Mesh(true, 4, 6, VertexAttribute.Position(), VertexAttribute.Normal());
        quad.setIndices(new short[] {0, 1, 2, 2, 3, 0});
        float[] left = quadAt(-3f);
        quad.setVertices(left);
        final Matrix4 id = new Matrix4();
        byte[] f = frameOf(true, () -> drawMesh(quad, id, 1, 0, 0));
        assertRedish(px(f, 40, N / 2), "the quad on the left");
        assertEquals(before + 2, ctx.resources().stats().buffersLive, "one vertex and one index buffer");

        quad.setVertices(quadAt(3f)); // dirty again: uploaded before the next draw
        byte[] g = frameOf(true, () -> drawMesh(quad, id, 1, 0, 0));
        assertRedish(px(g, N - 41, N / 2), "the quad moved to the right");
        int[] gone = px(g, 40, N / 2);
        assertTrue(gone[0] < 60, "nothing is left where it was: " + java.util.Arrays.toString(gone));

        quad.dispose(); // Mesh.dispose() calls glDeleteBuffer: the native buffers are released once the GPU is done
        for (int i = 0; i < 4; i++) frameOf(false, () -> { });
        assertEquals(before, ctx.resources().stats().buffersLive, "disposing the mesh released its buffers: " + ctx.resources().stats());
    }

    @Test void vertexArrayMeshesAreCopiedThroughTheRingEachDraw() {
        final Mesh quad = new Mesh(Mesh.VertexDataType.VertexArray, true, 4, 6, VertexAttribute.Position(), VertexAttribute.Normal());
        quad.setIndices(new short[] {0, 1, 2, 2, 3, 0});
        quad.setVertices(quadAt(0f));
        for (int i = 0; i < 3; i++) frameOf(false, () -> { }); // buffers given up by earlier tests are released at the next frames
        final int buffers = ctx.resources().stats().buffersLive;
        byte[] f = frameOf(true, () -> drawMesh(quad, new Matrix4(), 1, 0, 0));
        assertRedish(px(f, N / 2, N / 2), "the vertex-array quad");
        assertEquals(buffers, ctx.resources().stats().buffersLive, "no persistent buffers for a vertex array");
        quad.dispose();
    }

    /** A 2 x 2 quad in the z = 0 plane facing +z, centred on x. Position (3 floats) + normal (3 floats) per vertex. */
    private static float[] quadAt(float x) {
        return new float[] {x - 1, -1, 0, 0, 0, 1, x + 1, -1, 0, 0, 0, 1, x + 1, 1, 0, 0, 0, 1, x - 1, 1, 0, 0, 0, 1};
    }
}
