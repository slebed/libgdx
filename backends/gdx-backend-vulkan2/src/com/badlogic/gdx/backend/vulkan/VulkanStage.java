package com.badlogic.gdx.backend.vulkan; // Or com.badlogic.gdx.scenes.scene2d? Keep consistent

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.Viewport;

/**
 * A Scene2D Stage implementation for the Vulkan backend.
 * <p>
 * It utilizes a {@link VulkanSpriteBatch} for rendering. Unlike the default Stage,
 * this implementation expects the Batch's {@code begin()} and {@code end()} methods
 * (along with Vulkan command buffer and render pass setup) to be handled externally
 * by the main rendering loop (e.g., in VulkanGraphics). The {@link #draw()} method
 * only handles viewport application and drawing the actor hierarchy.
 */
public class VulkanStage extends Stage {

    // The batch is stored in the superclass 'batch' field, but we know its type.
    // No need for a separate field unless accessing VulkanSpriteBatch specific methods.

    /**
     * Creates a VulkanStage with the specified viewport and batch.
     *
     * @param viewport The viewport to manage the stage's camera and projection.
     * @param batch    The VulkanSpriteBatch instance to use for drawing. Must not be null.
     */
    public VulkanStage(Viewport viewport, VulkanSpriteBatch batch) {
        // Use the constructor that takes an existing Batch instance.
        // IMPORTANT: This constructor sets ownsBatch=false, meaning this Stage
        // will NOT dispose the batch when the stage is disposed. The batch
        // must be managed and disposed externally (e.g., by VulkanGraphics).
        super(viewport, batch);

        if (batch == null) {
            throw new IllegalArgumentException("VulkanSpriteBatch cannot be null for VulkanStage.");
        }
    }

    /**
     * Draws the stage. Applies the viewport, updates the camera, and draws the actor hierarchy.
     * <p>
     * Assumes that {@code batch.begin(VkCommandBuffer)} has already been called externally
     * and that a Vulkan render pass is active on that command buffer. This method does
     * NOT call {@code batch.begin()} or {@code batch.end()}.
     */
    @Override
    public void draw() {
        Camera camera = getViewport().getCamera();
        camera.update(); // Update camera matrix based on position, zoom, etc.

        // Apply viewport transformations. This updates the camera projection
        // and view matrices. The Viewport implementation should internally call
        // batch.setProjectionMatrix(camera.combined) which updates the Vulkan UBO.
        // It also typically sets glViewport, which has no effect here.
        getViewport().apply(); // Use apply() without centering by default

        if (!getRoot().isVisible()) return;

        Batch batch = getBatch(); // Get the batch instance stored in the superclass
        if (batch != null) {
            // Check if the batch was correctly started externally
            if (batch.isDrawing()) {
                // Draw the actor hierarchy using the begun batch
                getRoot().draw(batch, 1.0f);
                // Optional: Consider if an explicit flush is needed here under certain
                // conditions (e.g., if an actor forces a pipeline state change that
                // isn't yet implemented via pipeline switching). For now, rely on
                // the external batch.end() in VulkanGraphics.drawFrame().
                // batch.flush();
            } else {
                // Log an error if called when the batch isn't ready
                Gdx.app.error("VulkanStage", "Stage.draw() called but Batch is not drawing! Check external batch.begin() call.");
            }
        } else {
            Gdx.app.error("VulkanStage", "Stage.draw() called but Batch is null!");
        }
        // IMPORTANT: No batch.end() here - it must be called externally after stage drawing.
    }

    /**
     * Disposes the Stage. Note that because the Batch is provided externally
     * (ownsBatch=false), this dispose method will NOT dispose the Batch.
     * The VulkanSpriteBatch must be disposed separately.
     */
    @Override
    public void dispose() {
        // Superclass dispose handles cleaning up listeners etc.
        // It will NOT dispose the batch because we used the constructor where ownsBatch = false.
        super.dispose();
    }

    /**
     * Convenience method to get the batch cast to VulkanSpriteBatch.
     * @return The VulkanSpriteBatch used by this stage, or null if the batch is not set or not a VulkanSpriteBatch.
     */
    public VulkanSpriteBatch getVulkanBatch() {
        Batch batch = getBatch();
        if (batch instanceof VulkanSpriteBatch) {
            return (VulkanSpriteBatch) batch;
        }
        return null;
    }
}