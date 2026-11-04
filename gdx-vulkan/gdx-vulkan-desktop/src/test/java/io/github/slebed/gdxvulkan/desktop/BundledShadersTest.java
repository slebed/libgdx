package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram;

/**
 * libGDX's {@code DefaultShader} and {@code DepthShader}, through the stock {@code ModelBatch}, for the matrix of materials, vertex layouts and lights in
 * {@link RecordShaderBundleMain}: every program comes out of the bundle compiled with the library, so the run-time translator and compiler are not called (the
 * disk cache is off for this test). Fails when the bundle is stale; run {@code gradlew :gdx-vulkan-desktop:recordShaderBundle} then.
 */
class BundledShadersTest {
    @Test void theStockModelBatchNeverCallsTheRunTimeCompilerForTheBundledVariants () {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        GLFW.glfwTerminate();
        final File before = VulkanShaderProgram.cacheDirectory;
        VulkanShaderProgram.setCacheDirectory(null);
        try {
            final VulkanDesktopConfiguration config = new VulkanDesktopConfiguration();
            config.width = 128;
            config.height = 128;
            config.resizable = false;
            config.disableAudio = true;
            config.vsync = false;
            config.depthBuffer = true;
            final int hits = VulkanShaderProgram.bundledShaderHits();
            final RecordShaderBundleMain.Recorder recorder = new RecordShaderBundleMain.Recorder();
            new VulkanDesktopApplication(recorder, config);
            assertNull(recorder.firstFailure, "every combination draws");
            assertTrue(recorder.combinations > 100, "the matrix ran: " + recorder.combinations);
            assertEquals(0, recorder.compilerCallsAtEnd - recorder.compilerCallsAtStart,
                "the run-time compiler was not called: the bundle is out of date if it was (gradlew :gdx-vulkan-desktop:recordShaderBundle)");
            assertTrue(VulkanShaderProgram.bundledShaderHits() - hits > 20, "programs came from the bundle: " + (VulkanShaderProgram.bundledShaderHits() - hits));
        } finally {
            VulkanShaderProgram.setCacheDirectory(before);
        }
    }
}
