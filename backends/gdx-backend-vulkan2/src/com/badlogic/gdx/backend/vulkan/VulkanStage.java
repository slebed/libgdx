
package com.badlogic.gdx.backend.vulkan; // Or com.badlogic.gdx.scenes.scene2d? Keep consistent

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.Viewport;

/** A Scene2D Stage implementation for the Vulkan backend.
 * <p>
 * It utilizes a {@link VulkanSpriteBatch} for rendering. Unlike the default Stage, this implementation expects the Batch's
 * {@code begin()} and {@code end()} methods (along with Vulkan command buffer and render pass setup) to be handled externally by
 * the main rendering loop (e.g., in VulkanGraphics). The {@link #draw()} method only handles viewport application and drawing the
 * actor hierarchy. */
public class VulkanStage extends Stage {
	private final String TAG = "VulkanStage";

	private final boolean managesInternalBatch;

	/** Creates a VulkanStage with a {@link VulkanScreenViewport}, creating and owning its own internal {@link VulkanSpriteBatch}.
	 * This Stage will dispose the internally created batch when the stage itself is disposed.
	 * <p>
	 * Note: Requires VulkanScreenViewport or similar to be available. If VulkanScreenViewport isn't defined, use `new
	 * VulkanStage(new ScreenViewport())` and ensure ScreenViewport works correctly with your Vulkan backend's camera setup.
	 * Consider creating a specific VulkanScreenViewport if needed. */
	public VulkanStage () {
		this(new VulkanScreenViewport());
	}

	/** Creates a VulkanStage with the specified viewport, creating and owning its own internal {@link VulkanSpriteBatch}. This
	 * Stage will dispose the internally created batch when the stage itself is disposed.
	 *
	 * @param viewport The viewport to manage the stage's camera and projection. */
	public VulkanStage (Viewport viewport) {
		super(viewport, new VulkanSpriteBatch());// Super constructor will set ownsBatch = false
		this.managesInternalBatch = true;
	}

	/** Creates a VulkanStage with the specified viewport and an externally provided batch. This Stage will NOT own or dispose the
	 * provided batch.
	 *
	 * @param viewport The viewport to manage the stage's camera and projection.
	 * @param batch The VulkanSpriteBatch instance to use for drawing. Must not be null. */
	public VulkanStage (Viewport viewport, VulkanSpriteBatch batch) {
		super(viewport, batch);
		this.managesInternalBatch = false;
	}

	@Override
	public void draw () {
		if (!getRoot().isVisible()) return;

		Viewport viewport = getViewport();
		Camera camera = viewport.getCamera();
		Batch batch = getBatch();

		if (batch == null || !getRoot().isVisible()) {
			Gdx.app.error(TAG, "Stage.draw() called but Batch is null!");
			return;
		}

		viewport.apply();

		batch.setProjectionMatrix(camera.combined);
		batch.begin();
		getRoot().draw(batch, 1.0f);
		batch.end();
	}

	/** Disposes the Stage. Note that because the Batch is provided externally (ownsBatch=false), this dispose method will NOT
	 * dispose the Batch. The VulkanSpriteBatch must be disposed separately. */
	@Override
	public void dispose () {
		Gdx.app.log(TAG, "Disposing VulkanStage...");
		super.dispose();
		Batch batch = getBatch();
		if (managesInternalBatch && batch != null) {
			Gdx.app.log(TAG, "Disposing internally managed VulkanSpriteBatch.");
			batch.dispose(); // Dispose the batch only if we own it
		} else {
			Gdx.app.log(TAG,
				"Dispose: Not disposing batch (managed externally or null). managesInternalBatch=" + managesInternalBatch);
		}
	}

	/** Convenience method to get the batch cast to VulkanSpriteBatch.
	 *
	 * @return The VulkanSpriteBatch used by this stage, or null if the batch is not set or not a VulkanSpriteBatch. */
	public VulkanSpriteBatch getVulkanBatch () {
		Batch batch = getBatch();
		if (batch instanceof VulkanSpriteBatch) {
			return (VulkanSpriteBatch)batch;
		}
		return null;
	}
}
