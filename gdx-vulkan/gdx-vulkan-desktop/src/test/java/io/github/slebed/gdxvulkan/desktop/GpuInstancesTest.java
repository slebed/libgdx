package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.g3d.VulkanCascadedShadowLight;
import io.github.slebed.gdxvulkan.g3d.VulkanGpuInstances;
import io.github.slebed.gdxvulkan.g3d.VulkanModelBatch;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The GPU-driven path (plan, Phase 5): instances culled against the frustum by a compute shader, an indirect draw of the survivors, point lights
 * sorted into clusters by another. The GPU's answers are compared with a CPU computation of the same things: the visible set against stock's
 * {@code Frustum}, every one of the 3,456 cluster light lists against a brute-force test, and a rendered pixel against the background.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GpuInstancesTest {
    private static final int N = 256;
    private static final int GRID = 24; // 24 x 24 instances

    private GdxVkContext ctx;
    private VulkanGL20 gl;
    private long window;
    private GdxVkPresenter presenter;
    private GdxVkFrame frame;
    private Model model;
    private Mesh mesh;
    private PerspectiveCamera camera;

    @BeforeAll void setUp() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        window = GLFW.glfwCreateWindow(N, N, "gdx-vulkan gpu instances", 0, 0);
        assumeTrue(window != 0, "Could not create a window");
        presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        presenter.setDepth(GdxVkPresenter.DEPTH);
        frame = new GdxVkFrame(ctx, presenter, gl);
        model = new ModelBuilder().createBox(1f, 1f, 1f, new Material(), Usage.Position | Usage.Normal);
        mesh = model.meshes.first();
        camera = new PerspectiveCamera(30f, N, N);
        camera.position.set(0f, 0f, 28f);
        camera.lookAt(0f, 0f, 0f);
        camera.near = 1f;
        camera.far = 80f;
        camera.update();
    }

    @AfterAll void tearDown() {
        model.dispose();
        TestGdx.uninstall();
        if (presenter != null) presenter.close();
        if (ctx != null) {
            assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
            ctx.close();
        }
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    /** A grid of instances in the z = 0 plane, 1.6 units apart, scale 0.8, no rotation. */
    private float[] grid() {
        float[] d = new float[GRID * GRID * VulkanGpuInstances.INSTANCE_FLOATS];
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                final int i = (y * GRID + x) * VulkanGpuInstances.INSTANCE_FLOATS;
                d[i] = (x - GRID / 2f + 0.5f) * 1.6f;
                d[i + 1] = (y - GRID / 2f + 0.5f) * 1.6f;
                d[i + 2] = 0f;
                d[i + 3] = 0.8f;
                d[i + 7] = 1f; // quaternion w
            }
        }
        return d;
    }

    private byte[] render(VulkanGpuInstances gpu) {
        TestGdx.width = N;
        TestGdx.height = N;
        GLFW.glfwPollEvents();
        org.junit.jupiter.api.Assertions.assertTrue(frame.begin(0.05f, 0.06f, 0.10f, 1f));
        gl.glViewport(0, 0, N, N);
        gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        gl.glClearDepthf(1f);
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        gpu.render(camera, N, N);
        return frame.endAndCapture();
    }

    /** The window pixel under the centre of the instance at grid cell (x, y): the cells are 1.6 apart, so the window centre is a gap between four. */
    private int[] pixelOfInstance(byte[] f, int gx, int gy) {
        final Vector3 p = new Vector3((gx - GRID / 2f + 0.5f) * 1.6f, (gy - GRID / 2f + 0.5f) * 1.6f, 0.4f); // the front face of the cube
        camera.project(p, 0, 0, N, N);
        return px(f, Math.round(p.x), N - 1 - Math.round(p.y));
    }

    private int[] px(byte[] f, int x, int y) {
        final int o = (y * N + x) * 4;
        return new int[] {f[o] & 255, f[o + 1] & 255, f[o + 2] & 255};
    }

    @Test void theGpuCullKeepsExactlyWhatTheFrustumKeeps() {
        final VulkanGpuInstances gpu = new VulkanGpuInstances(frame, mesh, null, GRID * GRID, 8, 0);
        final float[] data = grid();
        gpu.setInstances(data, GRID * GRID);
        byte[] f = render(gpu);

        final List<Integer> expected = new ArrayList<Integer>();
        final float radius = (float)Math.sqrt(3) * 0.5f * 0.8f; // the box's bounding sphere, scaled
        for (int i = 0; i < GRID * GRID; i++) {
            final int b = i * VulkanGpuInstances.INSTANCE_FLOATS;
            if (camera.frustum.sphereInFrustum(data[b], data[b + 1], data[b + 2], radius)) expected.add(i);
        }
        assertTrue(expected.size() > 50 && expected.size() < GRID * GRID, "the camera sees some of the grid, not all or none: " + expected.size());
        final int visible = gpu.readVisibleCount();
        assertEquals(expected.size(), visible, "the visible count");
        final int[] got = gpu.readVisible(visible);
        Arrays.sort(got); // the compute threads append in no fixed order
        final int[] want = new int[expected.size()];
        for (int i = 0; i < want.length; i++) want[i] = expected.get(i);
        assertArrayEquals(want, got, "the visible set");

        // The picture: the instance at the middle of the grid is lit, and the corner of the window is not any of it.
        final int[] mid = pixelOfInstance(f, GRID / 2, GRID / 2);
        assertTrue(mid[0] + mid[1] + mid[2] > 120, "an instance is drawn: " + Arrays.toString(mid));
        final int[] gap = px(f, N / 2, N / 2); // between four of them: the background
        assertTrue(gap[0] + gap[1] + gap[2] < 80, "the gap between instances shows the background: " + Arrays.toString(gap));
        gpu.dispose();
    }

    @Test void everyClusterListMatchesABruteForceTest() {
        final int lightCount = 6;
        final VulkanGpuInstances gpu = new VulkanGpuInstances(frame, mesh, null, GRID * GRID, lightCount, 0);
        gpu.setInstances(grid(), GRID * GRID);
        final float[] lights = new float[lightCount * VulkanGpuInstances.LIGHT_FLOATS];
        final float[][] spec = {{-6f, 3f, 4f, 5f}, {5f, -2f, 8f, 4f}, {0f, 0f, 2f, 3f}, {9f, 6f, -3f, 6f}, {-3f, -7f, 10f, 7f}, {2f, 4f, 20f, 9f}};
        for (int l = 0; l < lightCount; l++) {
            final int b = l * VulkanGpuInstances.LIGHT_FLOATS;
            lights[b] = spec[l][0];
            lights[b + 1] = spec[l][1];
            lights[b + 2] = spec[l][2];
            lights[b + 3] = spec[l][3];
            lights[b + 4] = 1f;
            lights[b + 5] = 0.8f;
            lights[b + 6] = 0.6f;
            lights[b + 7] = 2f;
        }
        gpu.setLights(lights, lightCount);
        render(gpu);

        final int cx = VulkanGpuInstances.CLUSTERS_X, cy = VulkanGpuInstances.CLUSTERS_Y, cz = VulkanGpuInstances.CLUSTERS_Z;
        final float near = camera.near, far = camera.far;
        final float p00 = camera.projection.val[com.badlogic.gdx.math.Matrix4.M00], p11 = camera.projection.val[com.badlogic.gdx.math.Matrix4.M11];
        final Vector3[] view = new Vector3[lightCount];
        for (int l = 0; l < lightCount; l++) {
            view[l] = new Vector3(spec[l][0], spec[l][1], spec[l][2]).mul(camera.view);
        }
        int nonEmpty = 0, mismatches = 0;
        for (int z = 0; z < cz; z++) {
            for (int y = 0; y < cy; y++) {
                for (int x = 0; x < cx; x++) {
                    final double d0 = near * Math.pow(far / near, z / (double)cz), d1 = near * Math.pow(far / near, (z + 1) / (double)cz);
                    final double nx0 = -1 + 2.0 * x / cx, nx1 = -1 + 2.0 * (x + 1) / cx, ny1 = 1 - 2.0 * y / cy, ny0 = 1 - 2.0 * (y + 1) / cy;
                    double loX = 1e30, loY = 1e30, loZ = 1e30, hiX = -1e30, hiY = -1e30, hiZ = -1e30;
                    for (int k = 0; k < 8; k++) {
                        final double d = (k & 4) != 0 ? d1 : d0;
                        final double px = ((k & 1) != 0 ? nx1 : nx0) * d / p00, py = ((k & 2) != 0 ? ny1 : ny0) * d / p11, pz = -d;
                        loX = Math.min(loX, px); loY = Math.min(loY, py); loZ = Math.min(loZ, pz);
                        hiX = Math.max(hiX, px); hiY = Math.max(hiY, py); hiZ = Math.max(hiZ, pz);
                    }
                    final List<Integer> want = new ArrayList<Integer>();
                    for (int l = 0; l < lightCount; l++) {
                        final double qx = Math.min(Math.max(view[l].x, loX), hiX), qy = Math.min(Math.max(view[l].y, loY), hiY),
                            qz = Math.min(Math.max(view[l].z, loZ), hiZ);
                        final double dx = view[l].x - qx, dy = view[l].y - qy, dz = view[l].z - qz;
                        if (dx * dx + dy * dy + dz * dz <= (double)spec[l][3] * spec[l][3]) want.add(l);
                    }
                    final int[] got = gpu.readCluster(x + cx * (y + cy * z));
                    if (!want.isEmpty()) nonEmpty++;
                    if (got.length != want.size()) {
                        mismatches++;
                        continue;
                    }
                    for (int i = 0; i < got.length; i++) if (got[i] != want.get(i)) mismatches++;
                }
            }
        }
        assertTrue(nonEmpty > 50, "the lights reach a good number of clusters: " + nonEmpty);
        assertTrue(mismatches <= 2, "cluster lists that differ from the brute-force test (a sphere exactly touching a box edge may round either way): " + mismatches);
        gpu.dispose();
    }

    /**
     * One frame with a shadow: a slab at {@code slabZ} is rendered into the cascaded shadow light's maps with the model batch's depth pass (the light shines along the
     * direction that gpu_lit.frag's directional light comes from), then the instances are drawn with the shadow set (or none, for null).
     */
    private byte[] renderWithShadow(VulkanGpuInstances gpu, VulkanCascadedShadowLight light, ModelInstance slab) {
        TestGdx.width = N;
        TestGdx.height = N;
        GLFW.glfwPollEvents();
        assertTrue(frame.begin(0.05f, 0.06f, 0.10f, 1f));
        if (light != null) {
            light.update(camera);
            final VulkanModelBatch depth = VulkanModelBatch.depth(frame);
            for (int i = 0; i < light.cascades(); i++) {
                light.begin(i);
                depth.begin(light.camera(i));
                if (slab != null) depth.render(slab);
                depth.end();
                light.end(i);
            }
            depth.dispose();
        }
        gl.glViewport(0, 0, N, N);
        gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        gl.glClearDepthf(1f);
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        gpu.setShadow(light);
        gpu.render(camera, N, N);
        return frame.endAndCapture();
    }

    private static int luma(int[] c) {
        return (299 * c[0] + 587 * c[1] + 114 * c[2]) / 1000;
    }

    @Test void instancesInAShadowAreDarkerAndTheRestAreUntouched() {
        assumeTrue(frame.isTierA(), "shadows on the instances need the modern code path");
        final VulkanGpuInstances gpu = new VulkanGpuInstances(frame, mesh, null, GRID * GRID, 4, 0);
        gpu.setInstances(grid(), GRID * GRID);
        gpu.setLights(new float[8], 0);
        final VulkanCascadedShadowLight light = new VulkanCascadedShadowLight(512, 2, 1f, 80f, 0.6f, 40f);
        light.set(1f, 1f, 1f, -0.4f, -0.8f, -0.3f); // the direction gpu_lit.frag's light comes from, reversed
        final Model slabModel = new ModelBuilder().createBox(8f, 8f, 0.3f, new Material(), Usage.Position | Usage.Normal);
        final ModelInstance slab = new ModelInstance(slabModel, -6f, 3f, 1.5f); // between the light and the left of the grid
        final ModelInstance far = new ModelInstance(slabModel, -6f, 3f, 60f); // out of the shadow map's range, so nothing is shadowed
        final byte[] unshadowed = renderWithShadow(gpu, null, null);
        final byte[] shadowed = renderWithShadow(gpu, light, slab);
        final byte[] noCaster = renderWithShadow(gpu, light, null);
        int darker = 0, changedWithoutCaster = 0, compared = 0;
        for (int gy = 8; gy < 16; gy++) {
            for (int gx = 8; gx < 16; gx++) { // the cells the camera sees
                final int a = luma(pixelOfInstance(unshadowed, gx, gy)), b = luma(pixelOfInstance(shadowed, gx, gy)), c = luma(pixelOfInstance(noCaster, gx, gy));
                compared++;
                if (b < a * 0.8) darker++;
                if (Math.abs(c - a) > 6) changedWithoutCaster++;
            }
        }
        System.out.println("[gpu shadows] " + darker + " of " + compared + " visible instances darker under the slab's shadow, " + changedWithoutCaster + " changed with no caster");
        assertTrue(darker >= 6 && darker < compared - 6, "some instances are in the shadow and some are not: " + darker + " of " + compared);
        assertEquals(0, changedWithoutCaster, "a shadow map with nothing in it changes nothing");
        slabModel.dispose();
        light.dispose();
        gpu.dispose();
    }

    @Test void aLightBrightensTheInstancesAroundIt() {
        final VulkanGpuInstances gpu = new VulkanGpuInstances(frame, mesh, null, GRID * GRID, 4, 0);
        gpu.setInstances(grid(), GRID * GRID);
        gpu.setLights(new float[8], 0);
        final byte[] dark = render(gpu);
        // A strong red light just in front of the middle of the grid.
        gpu.setLights(new float[] {0.2f, 0.2f, 1.5f, 4f, 1f, 0.05f, 0.05f, 6f}, 1);
        final byte[] lit = render(gpu);
        final int[] a = pixelOfInstance(dark, GRID / 2, GRID / 2), b = pixelOfInstance(lit, GRID / 2, GRID / 2);
        assertTrue(b[0] > a[0] + 40, "the red channel rises under a red light: " + Arrays.toString(a) + " -> " + Arrays.toString(b));
        assertTrue(b[0] - a[0] > 3 * Math.max(b[2] - a[2], 1), "red gains far more than blue: " + Arrays.toString(a) + " -> " + Arrays.toString(b));
        gpu.dispose();
    }
}
