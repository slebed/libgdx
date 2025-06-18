
package com.badlogic.gdx.backend.vulkan; // Or your preferred package

import com.badlogic.gdx.graphics.Camera;
// Can likely remove if not calling it
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/** A ScreenViewport tailored for Vulkan. It overrides the apply() method to perform camera updates but skips the call to
 * Gdx.gl.glViewport, as viewport/scissor state is handled separately in Vulkan (e.g., dynamic states). */
public class VulkanScreenViewport extends ScreenViewport {

	// Add constructors matching superclass if needed
	public VulkanScreenViewport () {
		super();
	}

	public VulkanScreenViewport (Camera camera) {
		super(camera);
	}

	/** Updates the viewport's camera and calculates screen coordinates, but importantly O M I T S the call to HdpiUtils.glViewport
	 * / Gdx.gl.glViewport.
	 * @param centerCamera If true, the camera position is centered within the world. */
	@Override
	public void apply (boolean centerCamera) {
		Camera camera = getCamera();
		camera.viewportWidth = getWorldWidth();
		camera.viewportHeight = getWorldHeight();
		if (centerCamera) {
			camera.position.set(getWorldWidth() / 2f, getWorldHeight() / 2f, 0);
		}
		camera.update(); // Standard camera update
		// --- End Revert ---

		// Screen bounds are still needed for unproject calculations
		setScreenBounds(getScreenX(), getScreenY(), getScreenWidth(), getScreenHeight());
		/*
		 * Camera camera = getCamera(); camera.viewportWidth = getWorldWidth(); camera.viewportHeight = getWorldHeight(); if
		 * (centerCamera) { camera.position.set(getWorldWidth() / 2f, getWorldHeight() / 2f, 0); } // Set projection for Y-up world
		 * [0,h] mapping to Y-up clip [-1,1] // Use near/far appropriate for your app camera.projection.setToOrtho(0,
		 * getWorldWidth(), 0, getWorldHeight(), camera.near, camera.far); // Set view matrix
		 * camera.view.setToLookAt(camera.position, camera.position.cpy().add(camera.direction), camera.up); // Combine
		 * camera.combined.set(camera.projection).mul(camera.view);
		 * 
		 * setScreenBounds(getScreenX(), getScreenY(), getScreenWidth(), getScreenHeight());
		 */
	}

	@Override
	public void update (int screenWidth, int screenHeight, boolean centerCamera) {
		setScreenSize(screenWidth, screenHeight);
		setWorldSize(screenWidth, screenHeight);
		apply(centerCamera);
	}
}
