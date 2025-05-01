package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20; // Import GL20 for buffer clearing constants
import com.badlogic.gdx.tests.utils.GdxTest;

/**
 * A minimal Vulkan test that only clears the screen to a solid color.
 * Used to test basic Vulkan context initialization and frame rendering
 * with minimal resource allocation.
 */
public class VulkanClearScreenTest extends GdxTest {
    // TAG for logging, consistent with other tests
    private static final String TAG = "VulkanClearScreenTest";

    // Fields to hold frame count for periodic logging (optional)
    private long frameCount = 0;

    @Override
    public void create() {
        // No specific resources need to be created for this simple test.
        // Log that create was called, including instance hash for debugging multiple windows.
        Gdx.app.log(TAG, "[" + this.hashCode() + "] create() called.");
    }

    @Override
    public void resize(int width, int height) {
        // The Vulkan backend handles viewport resizing implicitly for the swapchain.
        // No camera or viewport setup is needed here just for clearing.
        Gdx.app.log(TAG, "[" + this.hashCode() + "] resize(" + width + ", " + height + ") called.");
    }

    @Override
    public void render() {
        // 1. Set the desired clear color.
        // Using classic Cornflower Blue (RGB: 100, 149, 237) normalized to 0.0-1.0
        // Alpha is set to 1.0 (opaque).
        Gdx.gl.glClearColor(100f / 255f, 149f / 255f, 237f / 255f, 1f);
        // Alternative: Gdx.gl.glClearColor(0.39f, 0.58f, 0.93f, 1f); // Approximate values

        // 2. Clear the color buffer.
        // The LibGDX Vulkan backend translates this into the appropriate Vulkan
        // commands within its render pass structure.
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // 3. Optional: Log occasionally to show it's running, but not every frame.
        frameCount++;
        if (frameCount % 300 == 0) { // Log roughly every 5 seconds at 60fps
            Gdx.app.log(TAG, "[" + this.hashCode() + "] rendering frame " + frameCount);
        }

        // No other drawing needed. The frame presentation is handled by the backend.
    }

    @Override
    public void dispose() {
        // No specific resources were created, so nothing explicit to dispose.
        Gdx.app.log(TAG, "[" + this.hashCode() + "] dispose() called.");
    }
}