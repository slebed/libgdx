package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkException;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.GdxVkResources;
import io.github.slebed.gdxvulkan.GdxVkSampler;
import io.github.slebed.gdxvulkan.GdxVkShaders;
import io.github.slebed.gdxvulkan.VulkanCaps;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** Phase 1b: the layout's two sets, the staging path, deferred deletion, and the pipeline cache file. */
class ResourcesTest {
    private static byte[] floats(float... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return b.array();
    }

    private static byte[] solid(int w, int h, int r, int g, int b, int a) {
        byte[] px = new byte[w * h * 4];
        for (int i = 0; i < px.length; i += 4) {
            px[i] = (byte)r;
            px[i + 1] = (byte)g;
            px[i + 2] = (byte)b;
            px[i + 3] = (byte)a;
        }
        return px;
    }

    @Test void headlessLifecycleAndDeferredDeletion() {
        try (GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties())) {
            GdxVkResources r = ctx.resources();
            boolean tierA = ctx.caps().tier == VulkanCaps.Tier.A;

            int buf = r.createBuffer(4096);
            r.writeBuffer(buf, 0, new byte[4096], 0, 4096);
            int tex = r.createTexture(8, 8, solid(8, 8, 1, 2, 3, 255), new GdxVkSampler());
            r.flushUploads();

            GdxVkResources.Stats s = r.stats();
            assertEquals(1, s.buffersLive);
            assertEquals(1, s.texturesLive);
            assertEquals(tierA ? 1 : 0, s.slotsInUse, "Tier A uses one table slot per texture, Tier B none");
            assertTrue(r.slotOf(tex) >= 0, "the texture is bound");

            // Sampler changes allocate a new binding and retire the old one; equal states share a VkSampler.
            int samplersBefore = s.samplers;
            GdxVkSampler nearest = new GdxVkSampler(GdxVkSampler.NEAREST, GdxVkSampler.NEAREST, GdxVkSampler.NEAREST, GdxVkSampler.REPEAT,
                GdxVkSampler.REPEAT, 1);
            for (int i = 0; i < 100; i++) r.setSampler(tex, i % 2 == 0 ? nearest : new GdxVkSampler());
            assertTrue(r.stats().samplers <= samplersBefore + 1, "repeated filter changes must not create new samplers");
            assertTrue(r.stats().retiredPending > 0, "old bindings are retired, not freed, until the GPU is done");

            r.destroyTexture(tex);
            r.destroyBuffer(buf);
            assertEquals(0, r.stats().buffersLive);
            assertEquals(0, r.stats().texturesLive);

            // Retirement tags are the next submit's serial, so two flushes (each submits) complete everything.
            r.flushUploads();
            r.flushUploads();
            r.collectGarbage();
            s = r.stats();
            assertEquals(0, s.retiredPending, "everything retired must be freed once the GPU has finished: " + s);
            assertEquals(0, s.slotsInUse, "table slots return to the free list: " + s);
            assertEquals(0, ctx.validationMessageCount());
        }
    }

    @Test void bufferRangesAreReusedAfterCompletion() {
        try (GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties())) {
            GdxVkResources r = ctx.resources();
            for (int round = 0; round < 50; round++) {
                int[] ids = new int[200];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = r.createBuffer(256 * 1024); // 200 x 256 KB = 50 MB per round, over one 32 MB pool
                    r.writeBuffer(ids[i], 0, new byte[64], 0, 64);
                }
                for (int id : ids) r.destroyBuffer(id);
                r.flushUploads();
                r.flushUploads();
                r.collectGarbage();
            }
            GdxVkResources.Stats s = r.stats();
            assertTrue(s.poolBytes <= 3L * 32 * 1024 * 1024, "pools must be reused, not grown without bound: " + s);
            assertEquals(0, ctx.validationMessageCount());
        }
    }

    @Test void reflectionRejectsShadersThatDoNotFitTheLayout() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        long window = 0;
        try (GdxVkContext ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties())) {
            boolean tierA = ctx.caps().tier == VulkanCaps.Tier.A;
            GlfwSurfaces.applyNoApiHints();
            window = GLFW.glfwCreateWindow(64, 64, "gdx-vulkan reflection", 0, 0);
            assumeTrue(window != 0, "Could not create a window");
            try (GdxVkPresenter p = GlfwSurfaces.createPresenter(ctx, window, false)) {
                byte[] vert = GdxVkShaders.load("triangle.vert", tierA);

                // The build for the other tier declares the wrong texture binding for this device's layout.
                GdxVkException wrongTier = assertThrows(GdxVkException.class,
                    () -> p.createPipeline("probe", vert, GdxVkShaders.load("probe.frag", !tierA)));
                assertTrue(wrongTier.getMessage().contains("probe.frag"), "names the shader: " + wrongTier.getMessage());
                assertTrue(wrongTier.getMessage().contains("set 1 binding 0"), "names the binding: " + wrongTier.getMessage());

                // A fragment shader handed over as the vertex shader.
                GdxVkException wrongStage = assertThrows(GdxVkException.class,
                    () -> p.createPipeline("swapped", GdxVkShaders.load("triangle.frag", tierA), GdxVkShaders.load("triangle.frag", tierA)));
                assertTrue(wrongStage.getMessage().contains("swapped.vert") && wrongStage.getMessage().contains("fragment shader"),
                    wrongStage.getMessage());

                // Garbage is reported, not passed to the driver.
                GdxVkException garbage = assertThrows(GdxVkException.class, () -> p.createPipeline("junk", new byte[16], vert));
                assertTrue(garbage.getMessage().contains("junk.vert"), garbage.getMessage());

                // And the right build still works afterwards.
                p.createPipeline("probe", vert, GdxVkShaders.load("probe.frag", tierA));
            }
            assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    @Test void allThreeSetsReachTheShader() throws IOException {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        File cache = File.createTempFile("gdxvk", ".pipelinecache");
        cache.delete();
        long window = 0;
        try {
            GdxVkConfig cfg = GdxVkConfig.fromSystemProperties();
            cfg.pipelineCachePath = cache.getAbsolutePath();
            try (GdxVkContext ctx = GdxVkContext.create(cfg)) {
                assertEquals(0, ctx.caps().pipelineCacheLoadedBytes, "nothing to load on a first run");
                boolean tierA = ctx.caps().tier == VulkanCaps.Tier.A;
                GlfwSurfaces.applyNoApiHints();
                window = GLFW.glfwCreateWindow(128, 128, "gdx-vulkan probe", 0, 0);
                assumeTrue(window != 0, "Could not create a window");
                try (GdxVkPresenter p = GlfwSurfaces.createPresenter(ctx, window, false)) {
                    int pipeline = p.createPipeline("probe", GdxVkShaders.load("triangle.vert", tierA), GdxVkShaders.load("probe.frag", tierA));
                    int tex = ctx.resources().createTexture(2, 2, solid(2, 2, 10, 20, 200, 255), new GdxVkSampler());

                    // red from the UBO, green from storage[1].g, blue from the texture
                    byte[] ubo = floats(0.8f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
                    byte[] ssbo = floats(0, 0, 0, 0, 0, 0.4f, 0, 0);

                    byte[] px = null;
                    for (int frame = 0; frame < 3; frame++) { // the first frame records the uploads; later ones must also be clean
                        GLFW.glfwPollEvents();
                        assertTrue(p.beginFrame(0f, 0f, 0f, 1f));
                        p.bindFrameData(ubo, ubo.length, ssbo, ssbo.length);
                        int slot = p.bindTexture(tex);
                        float[] push = new float[32];
                        push[0] = push[5] = push[10] = push[15] = 1f;
                        push[21] = Float.intBitsToFloat(slot); // u_indices.y
                        p.draw(pipeline, 3, push);
                        px = p.endFrameAndCapture();
                    }
                    int w = p.width(), h = p.height();
                    int i = ((int)(h * 0.55) * w + w / 2) * 4; // inside the triangle
                    assertEquals(204, px[i] & 0xFF, 2, "red comes from the uniform buffer (Set 0 binding 0)");
                    assertEquals(102, px[i + 1] & 0xFF, 2, "green comes from the storage buffer (Set 0 binding 1)");
                    assertEquals(200, px[i + 2] & 0xFF, 2, "blue comes from the texture (Set 1)");
                    ctx.resources().destroyTexture(tex);
                }
                assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
            }
            assertTrue(cache.length() > 0, "the pipeline cache is saved at shutdown");

            try (GdxVkContext ctx = GdxVkContext.create(cfg)) {
                assertTrue(ctx.caps().pipelineCacheLoadedBytes > 0, "the saved pipeline cache is loaded at startup");
            }
            // A cache from another device or driver is ignored, not fed to the driver.
            Files.write(cache.toPath(), new byte[64]);
            try (GdxVkContext ctx = GdxVkContext.create(cfg)) {
                assertEquals(0, ctx.caps().pipelineCacheLoadedBytes, "a mismatched cache header must be rejected");
            }
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            cache.delete();
        }
    }
}
