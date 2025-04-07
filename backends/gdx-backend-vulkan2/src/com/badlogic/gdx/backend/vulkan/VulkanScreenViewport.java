package com.badlogic.gdx.backend.vulkan; // Or your preferred package

import com.badlogic.gdx.graphics.Camera;
// Can likely remove if not calling it
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * A ScreenViewport tailored for Vulkan. It overrides the apply() method
 * to perform camera updates but skips the call to Gdx.gl.glViewport,
 * as viewport/scissor state is handled separately in Vulkan (e.g., dynamic states).
 */
public class VulkanScreenViewport extends ScreenViewport {

    // Add constructors matching superclass if needed
    public VulkanScreenViewport() {
        super();
    }

    public VulkanScreenViewport(Camera camera) {
        super(camera);
    }

    /**
     * Updates the viewport's camera and calculates screen coordinates, but
     * importantly O M I T S the call to HdpiUtils.glViewport / Gdx.gl.glViewport.
     * @param centerCamera If true, the camera position is centered within the world.
     */
    @Override
    public void apply(boolean centerCamera) {
        // --- Replicate necessary logic from Viewport.apply() & ScreenViewport ---

        // 1. Update camera projection based on world size
        Camera camera = getCamera();
        camera.viewportWidth = getWorldWidth();   // Usually world == screen for ScreenViewport
        camera.viewportHeight = getWorldHeight();
        if (centerCamera) camera.position.set(getWorldWidth() / 2f, getWorldHeight() / 2f, 0);
        camera.update(); // This updates the camera's view and combined matrices

        // 2. Update Batch projection matrix
        // This happens automatically when the camera is updated IF the batch
        // projection is tied to camera.combined (which Stage does by default).
        // VulkanSpriteBatch's setProjectionMatrix handles the UBO update.

        // 3. Calculate and set screen bounds (needed for viewport coordinate transforms)
        // Use the setters provided by the Viewport class itself
        setScreenBounds(getScreenX(), getScreenY(), getScreenWidth(), getScreenHeight());

        // 4. *** The GL call is OMITTED here ***
        // Original would be: HdpiUtils.glViewport(getScreenX(), getScreenY(), getScreenWidth(), getScreenHeight());
    }

    // Overriding update ensures our overridden apply is called.
    @Override
    public void update(int screenWidth, int screenHeight, boolean centerCamera) {
        setScreenSize(screenWidth, screenHeight); // Store screen size for apply() calculation
        // For ScreenViewport, world size typically matches screen size
        setWorldSize(screenWidth, screenHeight);
        // Apply the viewport logic (calls our overridden apply method)
        apply(centerCamera);
    }
}