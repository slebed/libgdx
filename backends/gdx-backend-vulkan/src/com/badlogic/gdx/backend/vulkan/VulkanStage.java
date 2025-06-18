package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.SnapshotArray;
import com.badlogic.gdx.utils.viewport.Viewport;

/** A Scene2D Stage implementation for the Vulkan backend.
 * <p>
 * It utilizes a {@link VulkanSpriteBatch} for rendering. Unlike the default Stage, this implementation expects the Batch's
 * {@code begin()} and {@code end()} methods (along with Vulkan command buffer and render pass setup) to be handled externally by
 * the main rendering loop (e.g., in VulkanGraphics). The {@link #draw()} method only handles viewport application and drawing the
 * actor hierarchy. */
public class VulkanStage extends Stage {
    private final String TAG = "VulkanStage";
    private final boolean debug = false; // Your existing debug flag for constructor/dispose logging

    private final boolean managesInternalBatch;
    public VulkanTexture whitePixel; // Made package-private for debug draw test access
    private final Vector2 tmpCoords = new Vector2(); // General purpose temporary vector
    private final Color tmpBatchColor = new Color(); // To save and restore batch color

    // --- Debug drawing specific fields ---
    private final Vector2 dbgCorner1 = new Vector2();
    private final Vector2 dbgCorner2 = new Vector2();
    private final Vector2 dbgCorner3 = new Vector2();
    private final Vector2 dbgCorner4 = new Vector2();

    private float debugLineThickness = 1.0f;
    private final Color debugActorColor = new Color(0, 1, 0, 1); // Green, Opaque (like LibGDX default)
    private final Color debugGroupColor = new Color(0, 0, 1, 1); // Blue, Opaque (like LibGDX default)
    private final Color debugOriginColor = new Color(Color.YELLOW); // Yellow for origin

    /** Creates a VulkanStage with a {@link VulkanScreenViewport}, creating and owning its own internal {@link VulkanSpriteBatch}.
     * This Stage will dispose the internally created batch when the stage itself is disposed.
     * <p>
     * Note: Requires VulkanScreenViewport or similar to be available. If VulkanScreenViewport isn't defined, use `new
     * VulkanStage(new ScreenViewport())` and ensure ScreenViewport works correctly with your Vulkan backend's camera setup.
     * Consider creating a specific VulkanScreenViewport if needed. */
    public VulkanStage() {
        this(new VulkanScreenViewport()); // Assumes VulkanScreenViewport is defined
        if (debug) Gdx.app.log(TAG, "create() called. Hash: " + this.hashCode());
    }

    /** Creates a VulkanStage with the specified viewport, creating and owning its own internal {@link VulkanSpriteBatch}. This
     * Stage will dispose the internally created batch when the stage itself is disposed.
     *
     * @param viewport The viewport to manage the stage's camera and projection. */
    public VulkanStage(Viewport viewport) {
        super(viewport, new VulkanSpriteBatch());// Super constructor will set ownsBatch = false
        this.managesInternalBatch = true;
        createWhitePixel();
        registerViewportWithCurrentWindow(viewport);
        if (debug) Gdx.app.log(TAG, "create() called. Hash: " + this.hashCode());
    }

    /** Creates a VulkanStage with the specified viewport and an externally provided batch. This Stage will NOT own or dispose the
     * provided batch.
     *
     * @param viewport The viewport to manage the stage's camera and projection.
     * @param batch The VulkanSpriteBatch instance to use for drawing. Must not be null. */
    public VulkanStage(Viewport viewport, VulkanSpriteBatch batch) {
        super(viewport, batch);
        this.managesInternalBatch = false;
        createWhitePixel();
        registerViewportWithCurrentWindow(viewport);
    }

    @Override
    public void draw() {
        if (!getRoot().isVisible()) return;
        Viewport viewport = getViewport();
        Camera camera = getCamera();
        Batch batch = getBatch();
        if (batch == null || camera == null || viewport == null) {
            Gdx.app.error(TAG, "Stage.draw() called but Batch, Camera, or Viewport is null!");
            return;
        }

        viewport.apply();
        //camera.update();
        batch.setProjectionMatrix(camera.combined);

        boolean batchWasDrawing = batch.isDrawing();
        if (!batchWasDrawing) batch.begin();

        getRoot().draw(batch, 1.0f);

        if (isDebugAll() && whitePixel != null) {
            try {
                // Note: LibGDX's default Stage.drawDebug also flushes the batch
                // and uses a ShapeRenderer. Here we continue with the same batch.
                // This might be fine, or might require a flush if you mix with other
                // non-batched rendering primitives (which is not the case here).
                drawDebugVulkanRecursive(getRoot(), batch);
            } catch (Exception e) {
                Gdx.app.error(TAG, "Error during debug draw: " + e.getMessage(), e);
            }
        }
        if (!batchWasDrawing) batch.end();
    }

    @Override
    public void dispose() {
        if (debug) Gdx.app.log(TAG, "Disposing VulkanStage... Hash: " + this.hashCode());
        Batch batch = getBatch();
        if (managesInternalBatch && batch != null) {
            if (debug) Gdx.app.log(TAG, "Disposing internally managed VulkanSpriteBatch.");
            batch.dispose();
        } else {
            if (debug) Gdx.app.log(TAG, "Dispose: Not disposing batch (managed externally or null). managesInternalBatch=" + managesInternalBatch);
        }

        if (whitePixel != null) {
            whitePixel.dispose();
            whitePixel = null;
            if (debug) Gdx.app.log(TAG, "Disposed whitePixel texture.");
        }
        super.dispose();
        if (debug) Gdx.app.log(TAG, "VulkanStage disposed.");
    }

    @Override
    public void setViewport(com.badlogic.gdx.utils.viewport.Viewport viewport) {
        super.setViewport(viewport);
        registerViewportWithCurrentWindow(viewport); // Add this call
    }

    private void createWhitePixel() {
        if (whitePixel != null) return;
        try {
            Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pixmap.setColor(Color.WHITE);
            pixmap.fill();
            whitePixel = new VulkanTexture(pixmap);
            pixmap.dispose();
            if (debug) Gdx.app.log(TAG, "Created whitePixel texture.");
        } catch (Exception e) {
            Gdx.app.error(TAG, "Failed to create whitePixel texture", e);
            whitePixel = null;
        }
    }

    /** Recursive method to draw debug info for actors using VulkanSpriteBatch */
    private void drawDebugVulkanRecursive(Actor actor, Batch batch) {
        if (actor == null) return;

        boolean actorIsVisible = actor.isVisible();
        boolean drawDebug = actor.getDebug(); // Check the actor's specific flag

        if (!drawDebug && isDebugAll() && actorIsVisible) {
            drawDebug = true;
        }

        // If the actor is invisible, only draw debug if its own debug flag is true.
        if (!actorIsVisible && !actor.getDebug()) {
            return;
        }

        if (drawDebug) {
            drawActorDebugLines(actor, batch);
        }

        if (actor instanceof Group) {
            SnapshotArray<Actor> children = ((Group) actor).getChildren();
            for (int i = 0; i < children.size; i++) {
                drawDebugVulkanRecursive(children.get(i), batch);
            }
        }
    }

    /** Draws debug boundary lines for a single actor. */
    private void drawActorDebugLines(Actor actor, Batch batch) {
        if (whitePixel == null) {
            Gdx.app.error(TAG, "whitePixel texture is null during debug draw!");
            return;
        }

        // Store original batch color and set debug color
        tmpBatchColor.set(batch.getColor());
        if (actor instanceof Group) {
            batch.setColor(debugGroupColor);
        } else {
            batch.setColor(debugActorColor);
        }

        // Get actor's dimensions
        float width = actor.getWidth();
        float height = actor.getHeight();

        // Define actor's local corners
        // (0,0) in actor's local space is its bottom-left.
        dbgCorner1.set(0, 0);                     // Bottom-left
        dbgCorner2.set(width, 0);                 // Bottom-right
        dbgCorner3.set(width, height);            // Top-right
        dbgCorner4.set(0, height);                // Top-left

        // Transform corners to stage coordinates
        actor.localToStageCoordinates(dbgCorner1);
        actor.localToStageCoordinates(dbgCorner2);
        actor.localToStageCoordinates(dbgCorner3);
        actor.localToStageCoordinates(dbgCorner4);

        // Draw lines between transformed corners
        drawBatchLine(batch, dbgCorner1, dbgCorner2, debugLineThickness); // Bottom
        drawBatchLine(batch, dbgCorner2, dbgCorner3, debugLineThickness); // Right
        drawBatchLine(batch, dbgCorner3, dbgCorner4, debugLineThickness); // Top
        drawBatchLine(batch, dbgCorner4, dbgCorner1, debugLineThickness); // Left

        // Draw origin marker if actor has non-default origin, rotation, or scale
        if (actor.getOriginX() != 0 || actor.getOriginY() != 0 || actor.getRotation() != 0 ||
                actor.getScaleX() != 1 || actor.getScaleY() != 1) {
            tmpCoords.set(actor.getOriginX(), actor.getOriginY());
            actor.localToStageCoordinates(tmpCoords); // Origin in stage coordinates

            batch.setColor(debugOriginColor); // Use specific color for origin
            float originMarkerHalfSize = 2.5f; // Makes a 5x5 pixel marker
            // Draw a small cross for the origin
            drawBatchLine(batch, tmpCoords.x - originMarkerHalfSize, tmpCoords.y, tmpCoords.x + originMarkerHalfSize, tmpCoords.y, debugLineThickness);
            drawBatchLine(batch, tmpCoords.x, tmpCoords.y - originMarkerHalfSize, tmpCoords.x, tmpCoords.y + originMarkerHalfSize, debugLineThickness);
        }

        // Restore original batch color
        batch.setColor(tmpBatchColor);
    }

    /**
     * Draws a line between two points using the Batch and a 1x1 white pixel texture.
     * Assumes the batch color is already set.
     */
    private void drawBatchLine(Batch batch, Vector2 start, Vector2 end, float thickness) {
        drawBatchLine(batch, start.x, start.y, end.x, end.y, thickness);
    }

    /**
     * Draws a line between (x1,y1) and (x2,y2) using the Batch and a 1x1 white pixel texture.
     * Assumes the batch color is already set.
     */
    private void drawBatchLine(Batch batch, float x1, float y1, float x2, float y2, float thickness) {
        if (whitePixel == null) return;

        float dx = x2 - x1;
        float dy = y2 - y1;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        if (dist < 0.0001f) return; // Avoid drawing zero-length lines or NaN rotation

        float angleRad = (float) Math.atan2(dy, dx);
        float angleDeg = (float) Math.toDegrees(angleRad);

        // The batch.draw method takes origin relative to the drawing position (x,y)
        // To center the line thickness, the originY is thickness / 2.
        // The texture (whitePixel) is assumed to be 1x1.
        batch.draw(whitePixel,      // Texture (VulkanTexture should be a subclass of Texture or Batch should handle it)
                x1,                 // x-coordinate of the bottom-left corner
                y1,                 // y-coordinate of the bottom-left corner
                0,                  // originX for rotation (relative to x,y) - line starts at x1
                thickness / 2f,     // originY for rotation (relative to x,y) - center thickness
                dist,               // width of the region (length of the line)
                thickness,          // height of the region (thickness of the line)
                1f,                 // scaleX
                1f,                 // scaleY
                angleDeg,           // rotation in degrees
                0,                  // srcX (for the 1x1 whitePixel texture)
                0,                  // srcY
                1,                  // srcWidth
                1,                  // srcHeight
                false,              // flipX
                false               // flipY
        );
    }


    /** Convenience method to get the batch cast to VulkanSpriteBatch. */
    public VulkanSpriteBatch getVulkanBatch() {
        Batch batch = getBatch();
        if (batch instanceof VulkanSpriteBatch) {
            return (VulkanSpriteBatch) batch;
        }
        return null;
    }

    private void registerViewportWithCurrentWindow(com.badlogic.gdx.utils.viewport.Viewport viewportToRegister) {
        if (Gdx.app instanceof VulkanApplication) {
            VulkanApplication app = (VulkanApplication) Gdx.app;
            VulkanWindow window = app.getCurrentWindow();

            if (window != null && viewportToRegister != null) {
                // Log BEFORE calling setViewportForVkCommands
                Gdx.app.debug("VulkanStage", "Attempting to register viewport. Hash: " + viewportToRegister.hashCode() +
                        ", Type: " + viewportToRegister.getClass().getSimpleName() +
                        ", Screen W/H at registration time: " + viewportToRegister.getScreenWidth() + "/" + viewportToRegister.getScreenHeight() +
                        ", With Window Hash: " + window.hashCode());
                window.setViewportForVkCommands(viewportToRegister);
            } else {
                Gdx.app.error("VulkanStage", "FAIL in registerViewport: Window null? " + (window == null) +
                        ", ViewportToRegister null? " + (viewportToRegister == null));
            }
        } else {
            Gdx.app.error("VulkanStage", "Gdx.app is not VulkanApplication. Cannot register viewport.");
        }
    }

    // --- Input Methods (unchanged from your provided code) ---
    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        // Original logging for raw coords
        System.out.println("_____________________\n>>> VulkanStage.touchDown Received: screen(" + screenX + ", " + screenY + ")");

        Viewport viewport = getViewport();
        Camera camera = getCamera();
        if (viewport != null) {
            System.out.println("    Viewport State: screenX=" + viewport.getScreenX() + ", screenY=" + viewport.getScreenY()
                    + ", screenWidth=" + viewport.getScreenWidth() + ", screenHeight=" + viewport.getScreenHeight() + ", worldWidth="
                    + viewport.getWorldWidth() + ", worldHeight=" + viewport.getWorldHeight());

            Vector2 tempVec = new Vector2(screenX, screenY);
            screenToStageCoordinates(tempVec);
            System.out.println("    Calculated Stage Coords: (" + tempVec.x + ", " + tempVec.y + ")");

            if (camera != null) {
                System.out.println(
                        "    Camera State (pos/dir/up): pos=" + camera.position + ", dir=" + camera.direction + ", up=" + camera.up);
            }
        }
        return super.touchDown(screenX, screenY, pointer, button);
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        return super.mouseMoved(screenX, screenY);
    }

    // --- Getters/Setters for debug properties if you want to customize them at runtime ---
    public float getDebugLineThickness() {
        return debugLineThickness;
    }

    public void setDebugLineThickness(float debugLineThickness) {
        this.debugLineThickness = debugLineThickness;
    }

    public Color getDebugActorColor() {
        return debugActorColor;
    }

    public void setDebugActorColor(Color debugActorColor) {
        this.debugActorColor.set(debugActorColor);
    }

    public Color getDebugGroupColor() {
        return debugGroupColor;
    }

    public void setDebugGroupColor(Color debugGroupColor) {
        this.debugGroupColor.set(debugGroupColor);
    }

    public Color getDebugOriginColor() {
        return debugOriginColor;
    }

    public void setDebugOriginColor(Color debugOriginColor) {
        this.debugOriginColor.set(debugOriginColor);
    }
}