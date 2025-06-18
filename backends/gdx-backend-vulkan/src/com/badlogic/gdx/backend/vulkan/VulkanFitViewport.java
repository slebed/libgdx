package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.utils.Scaling;
import com.badlogic.gdx.utils.viewport.FitViewport;

/**
 * A {@link FitViewport} tailored for Vulkan.
 * <p>
 * It overrides {@code apply(boolean centerCamera)} and {@code apply()}
 * to ensure camera properties are set and the camera is updated, but crucially
 * O M I T S the call to {@code HdpiUtils.glViewport} or {@code Gdx.gl.glViewport}.
 * This is essential for Vulkan backends where viewport/scissor state is managed explicitly.
 * <p>
 * The {@code update(int, int, boolean)} method calls its superclass implementation,
 * which (for FitViewport) calculates the correct screen bounds and then calls
 * {@code apply(boolean centerCamera)}. This call will resolve to the overridden
 * version in this class.
 */
public class VulkanFitViewport extends FitViewport {

    public VulkanFitViewport(float worldWidth, float worldHeight) {
        super(worldWidth, worldHeight);
    }

    public VulkanFitViewport(float worldWidth, float worldHeight, Camera camera) {
        super(worldWidth, worldHeight, camera);
    }

    /**
     * Applies the viewport to the camera and updates the camera.
     * This method is called by {@link FitViewport#update(int, int, boolean)}
     * after screen bounds have been calculated.
     * <p>
     * Crucially, this override omits the call to {@code HdpiUtils.glViewport} and
     * does not call {@code super.apply(centerCamera)} to prevent the base
     * {@link com.badlogic.gdx.utils.viewport.Viewport#apply(boolean)} from being invoked.
     *
     * @param centerCamera If true, the camera position is centered within the world.
     */
    @Override
    public void apply(boolean centerCamera) {
        // screenX, screenY, screenWidth, screenHeight should have been set by
        // FitViewport.update() before this method is called.

        Camera camera = getCamera();
        camera.viewportWidth = getWorldWidth();  // Set camera's viewport to the world dimensions
        camera.viewportHeight = getWorldHeight();
        if (centerCamera) {
            camera.position.set(getWorldWidth() / 2f, getWorldHeight() / 2f, 0);
        }
        camera.update(); // Update camera projection and view matrices

        // NO HdpiUtils.glViewport() or Gdx.gl.glViewport() call.
        // NO super.apply(centerCamera) call.
    }

    /**
     * Applies the viewport to the camera and updates the camera.
     * This is a simplified version typically called if screen dimensions haven't changed
     * but camera properties might need re-application.
     * <p>
     * Crucially, this override omits the call to {@code HdpiUtils.glViewport}.
     */
    @Override
    public void apply() {
        // screenX, screenY, screenWidth, screenHeight are assumed to be current.

        Camera camera = getCamera();
        camera.viewportWidth = getWorldWidth();
        camera.viewportHeight = getWorldHeight();
        // Per Viewport.apply() contract, this version does not typically center the camera.
        camera.update();

        // NO HdpiUtils.glViewport() or Gdx.gl.glViewport() call.
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation calls {@code super.update(screenWidth, screenHeight, centerCamera)},
     * which is {@link FitViewport#update(int, int, boolean)}.
     * That method calculates the "fit" screen bounds and then calls
     * {@code apply(centerCamera)}, which will resolve to this class's overridden version.
     */
    @Override
    public void update(int screenWidth, int screenHeight, boolean centerCamera) {
        super.update(screenWidth, screenHeight, centerCamera);
    }
}