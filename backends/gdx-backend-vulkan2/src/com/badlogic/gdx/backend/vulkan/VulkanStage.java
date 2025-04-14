package com.badlogic.gdx.backend.vulkan; // Or com.badlogic.gdx.scenes.scene2d? Keep consistent

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Matrix4;
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
    private final String TAG = "VulkanStage";
    //private final Matrix4 vulkanOrthoMatrix = new Matrix4();

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
        // Get the viewport and camera associated with this stage
        Viewport viewport = getViewport();
        Camera camera = viewport.getCamera(); // Camera is managed by the viewport

        // Apply the viewport - updates the camera based on screen size, etc.
        // Also sets glViewport, but that might be ignored/handled differently in Vulkan backend.
        // Crucially, it updates the camera's projection and view matrices.
        viewport.apply();

        Batch batch = getBatch();
        if (batch != null) {
            if (!getRoot().isVisible()) return;

            if (batch.isDrawing()) {
                // Set the batch's projection matrix FROM THE CAMERA managed by the viewport
                batch.setProjectionMatrix(camera.combined);

                // Draw the actors using the batch and the consistent projection
                getRoot().draw(batch, 1.0f);
            } else {
                Gdx.app.error(TAG, "Stage.draw() called but Batch is not drawing!");
            }
        } else {
            Gdx.app.error(TAG, "Stage.draw() called but Batch is null!");
        }

        //Camera camera = getViewport().getCamera();
        //camera.update(); // Update camera matrix based on position, zoom, etc.

        /*float w1 = Gdx.graphics.getWidth();
        float h1 = Gdx.graphics.getHeight();

        if (w1 <= 0 || h1 <= 0) { // Use <= 0 for safety
            Gdx.app.error("VulkanStage", "WIDTH OR HEIGHT IS ZERO or invalid! Skipping matrix update.");
            System.exit(0);
        }

        if (!getRoot().isVisible()) return;

        Batch batch = getBatch(); // Get the batch instance stored in the superclass
        if (batch != null) {
            float w = Gdx.graphics.getWidth();
            float h = Gdx.graphics.getHeight();
            float near = 0.0f;
            float far = 1.0f;

            // The manual calculation block setting vulkanOrthoMatrix.val[...] should be here...
            // Make sure these lines result in M22 = 1.0f / (far - near) and M32 = -near / (far - near)
            // For near=0, far=1, this means M22 = 1.0, M32 = 0.0

            vulkanOrthoMatrix.val[Matrix4.M00] = 2.0f / w;
            vulkanOrthoMatrix.val[Matrix4.M11] = 2.0f / h;
            vulkanOrthoMatrix.val[Matrix4.M22] = 1.0f / (far - near); // Vulkan Z maps [0,1] input -> [0,1] NDC ( = 1.0f)
            vulkanOrthoMatrix.val[Matrix4.M30] = -1.0f;      // Translate X
            vulkanOrthoMatrix.val[Matrix4.M31] = -1.0f;      // Translate Y
            vulkanOrthoMatrix.val[Matrix4.M32] = -near / (far - near); // Vulkan Z ( = 0.0f)
            vulkanOrthoMatrix.val[Matrix4.M33] = 1.0f;
            // Clear others...
            vulkanOrthoMatrix.val[Matrix4.M01] = 0f;
            vulkanOrthoMatrix.val[Matrix4.M02] = 0f;
            vulkanOrthoMatrix.val[Matrix4.M03] = 0f;
            vulkanOrthoMatrix.val[Matrix4.M10] = 0f;
            vulkanOrthoMatrix.val[Matrix4.M12] = 0f;
            vulkanOrthoMatrix.val[Matrix4.M13] = 0f;
            vulkanOrthoMatrix.val[Matrix4.M20] = 0f;
            vulkanOrthoMatrix.val[Matrix4.M21] = 0f;
            vulkanOrthoMatrix.val[Matrix4.M23] = 0f;

            batch.setProjectionMatrix(vulkanOrthoMatrix);

            if (!getRoot().isVisible()) return;

            if (batch.isDrawing()) {
                getRoot().draw(batch, 1.0f);
            } else {
                Gdx.app.error("VulkanStage", "Stage.draw() called but Batch is not drawing!");
            }
        } else {
            Gdx.app.error("VulkanStage", "Stage.draw() called but Batch is null!");
        }*/
    }

    /**
     * Disposes the Stage. Note that because the Batch is provided externally
     * (ownsBatch=false), this dispose method will NOT dispose the Batch.
     * The VulkanSpriteBatch must be disposed separately.
     */
    @Override
    public void dispose() {
        super.dispose();
    }

    /**
     * Convenience method to get the batch cast to VulkanSpriteBatch.
     *
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