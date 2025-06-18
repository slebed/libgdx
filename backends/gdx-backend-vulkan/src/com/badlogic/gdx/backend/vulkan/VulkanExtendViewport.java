package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.utils.Scaling;
import com.badlogic.gdx.utils.viewport.ExtendViewport;

/**
 * An {@link ExtendViewport} tailored for Vulkan.
 * <p>
 * It overrides {@code apply(boolean centerCamera)} and {@code apply()}
 * to ensure camera properties are set and the camera is updated, but crucially
 * O M I T S the call to {@code HdpiUtils.glViewport} or {@code Gdx.gl.glViewport}.
 * This is essential for Vulkan backends where viewport/scissor state is managed explicitly.
 * <p>
 * The {@code update(int, int, boolean)} method calls its superclass implementation,
 * which (for ExtendViewport) calculates the correct screen bounds and then calls
 * {@code apply(boolean centerCamera)}. This call will resolve to the overridden
 * version in this class.
 */
public class VulkanExtendViewport extends ExtendViewport {

    public VulkanExtendViewport(float minWorldWidth, float minWorldHeight) {
        super(minWorldWidth, minWorldHeight);
    }

    public VulkanExtendViewport(float minWorldWidth, float minWorldHeight, Camera camera) {
        super(minWorldWidth, minWorldHeight, camera);
    }

    public VulkanExtendViewport(float minWorldWidth, float minWorldHeight, float maxWorldWidth, float maxWorldHeight) {
        super(minWorldWidth, minWorldHeight, maxWorldWidth, maxWorldHeight);
    }

    public VulkanExtendViewport(float minWorldWidth, float minWorldHeight, float maxWorldWidth, float maxWorldHeight, Camera camera) {
        super(minWorldWidth, minWorldHeight, maxWorldWidth, maxWorldHeight, camera);
    }

    /**
     * Applies the viewport to the camera and updates the camera.
     * This method is called by {@link ExtendViewport#update(int, int, boolean)}
     * after screen bounds have been calculated.
     * <p>
     * Crucially, this override omits the call to {@code HdpiUtils.glViewport} and
     * does not call {@code super.apply(centerCamera)}.
     *
     * @param centerCamera If true, the camera position is centered within the world.
     */
    @Override
    public void apply(boolean centerCamera) {
        // screenX, screenY, screenWidth, screenHeight should have been set by
        // ExtendViewport.update() before this method is called.

        Camera camera = getCamera();
        camera.viewportWidth = getWorldWidth();
        camera.viewportHeight = getWorldHeight();
        if (centerCamera) {
            camera.position.set(getWorldWidth() / 2f, getWorldHeight() / 2f, 0);
        }
        camera.update();

        // NO HdpiUtils.glViewport() or Gdx.gl.glViewport() call.
        // NO super.apply(centerCamera) call.
    }

    /**
     * Applies the viewport to the camera and updates the camera.
     * <p>
     * Crucially, this override omits the call to {@code HdpiUtils.glViewport}.
     */
    @Override
    public void apply() {
        Camera camera = getCamera();
        camera.viewportWidth = getWorldWidth();
        camera.viewportHeight = getWorldHeight();
        camera.update();
        // NO HdpiUtils.glViewport() or Gdx.gl.glViewport() call.
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation calls {@code super.update(screenWidth, screenHeight, centerCamera)},
     * which is {@link ExtendViewport#update(int, int, boolean)}.
     * That method calculates the "extend" screen bounds and world size, then calls
     * {@code apply(centerCamera)}, which will resolve to this class's overridden version.
     */
    @Override
    public void update(int screenWidth, int screenHeight, boolean centerCamera) {
        super.update(screenWidth, screenHeight, centerCamera);
    }
}