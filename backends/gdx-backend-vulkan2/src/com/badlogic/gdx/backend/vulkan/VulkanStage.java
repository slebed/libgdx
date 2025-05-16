
package com.badlogic.gdx.backend.vulkan; // Or com.badlogic.gdx.scenes.scene2d? Keep consistent

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
    private final boolean debug = false;

    private final boolean managesInternalBatch;
    public VulkanTexture whitePixel; // Made package-private for debug draw test access
    private final Vector2 tmpCoords = new Vector2();
    private final Color tmpColor = new Color();

    // ---> Flag to track if the first input has been processed <---
    // private boolean firstInputProcessed = false;
    // ---> Temporary vectors for coordinate logging <---
    //private final Vector2 tempScreenCoordsLog = new Vector2();
    //private final Vector2 tempStageCoordsLog = new Vector2();

    /** Creates a VulkanStage with a {@link VulkanScreenViewport}, creating and owning its own internal {@link VulkanSpriteBatch}.
     * This Stage will dispose the internally created batch when the stage itself is disposed.
     * <p>
     * Note: Requires VulkanScreenViewport or similar to be available. If VulkanScreenViewport isn't defined, use `new
     * VulkanStage(new ScreenViewport())` and ensure ScreenViewport works correctly with your Vulkan backend's camera setup.
     * Consider creating a specific VulkanScreenViewport if needed. */
    public VulkanStage() {
        // Use VulkanScreenViewport by default if available, otherwise fallback
        // This requires VulkanScreenViewport to be defined in the backend package
        this(new VulkanScreenViewport());
        if (debug) Gdx.app.log(TAG, "create() called. Hash: " + this.hashCode());
        // If you have a specific VulkanScreenViewport class:
        // this(new VulkanScreenViewport());
    }

    /** Creates a VulkanStage with the specified viewport, creating and owning its own internal {@link VulkanSpriteBatch}. This
     * Stage will dispose the internally created batch when the stage itself is disposed.
     *
     * @param viewport The viewport to manage the stage's camera and projection. */
    public VulkanStage(Viewport viewport) {
        super(viewport, new VulkanSpriteBatch());// Super constructor will set ownsBatch = false
        this.managesInternalBatch = true;
        createWhitePixel();
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

        viewport.apply(); // Apply viewport changes (updates camera viewport dimensions)
        camera.update(); // Update camera matrices based on viewport state
        batch.setProjectionMatrix(camera.combined); // Set matrix for batch

        boolean batchWasDrawing = batch.isDrawing();
        if (!batchWasDrawing) batch.begin();
        getRoot().draw(batch, 1.0f);
        if (isDebugAll() && whitePixel != null) {
            try {
                drawDebugVulkanRecursive(getRoot(), batch);
            } catch (Exception e) {
                Gdx.app.error(TAG, "Error during debug draw: " + e.getMessage(), e);
            }
        }
        if (!batchWasDrawing) batch.end();
    }

    // --- Dispose Method (Modified to dispose whitePixel) ---
    @Override
    public void dispose() {
        if (debug) Gdx.app.log(TAG, "Disposing VulkanStage...");
        if (debug) Gdx.app.log(TAG, "dispose() called. Hash: " + this.hashCode());
        // Dispose internal batch FIRST if we own it
        Batch batch = getBatch();
        if (managesInternalBatch && batch != null) {
            if (debug) Gdx.app.log(TAG, "Disposing internally managed VulkanSpriteBatch.");
            batch.dispose();
            // Cannot directly set super.batch = null, Stage doesn't allow it.
            // Rely on Stage internals or potentially reflection if needed, but usually okay.
        } else {
            if (debug) Gdx.app.log(TAG, "Dispose: Not disposing batch (managed externally or null). managesInternalBatch=" + managesInternalBatch);
        }

        // Dispose the white pixel texture
        if (whitePixel != null) {
            whitePixel.dispose();
            whitePixel = null;
            if (debug) Gdx.app.log(TAG, "Disposed whitePixel texture.");
        }

        // Call super.dispose() AFTER handling batch/resources owned by this subclass
        // Though super.dispose() currently does nothing, it's good practice.
        super.dispose();
        if (debug) Gdx.app.log(TAG, "VulkanStage disposed.");
    }

    // --- Vulkan-Compatible Debug Drawing Logic (Copied In) ---

    private void createWhitePixel() {
        // Avoid creating if already exists (e.g., multiple constructor calls)
        if (whitePixel != null) return;
        try {
            Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pixmap.setColor(Color.WHITE);
            pixmap.fill();
            whitePixel = new VulkanTexture(pixmap); // Assumes VulkanTexture constructor takes Pixmap
            pixmap.dispose();
            if (debug) Gdx.app.log(TAG, "Created whitePixel texture.");
        } catch (Exception e) {
            Gdx.app.error(TAG, "Failed to create whitePixel texture", e);
            whitePixel = null; // Ensure it's null on failure
        }
    }

    /** Recursive method to draw debug info for actors using VulkanSpriteBatch */
    private void drawDebugVulkanRecursive(Actor actor, Batch batch) {
        if (actor == null) return;

        // Determine if we should draw debug for this actor based on existing flags
        boolean actorIsVisible = actor.isVisible();
        boolean drawDebug = actor.getDebug(); // Check the actor's specific flag

        // If the actor's flag isn't set, check the stage's global flag,
        // but only draw if the actor is also visible in that case.
        if (!drawDebug && isDebugAll() && actorIsVisible) {
            drawDebug = true;
        }

        // If the actor is invisible, only draw debug if its own debug flag is true.
        if (!actorIsVisible && !actor.getDebug()) {
            return;
        }

        // Draw debug visuals for this actor if needed
        if (drawDebug) {
            drawActorDebugVulkan(actor, batch);
        }

        // Recurse for groups
        if (actor instanceof Group) {
            SnapshotArray<Actor> children = ((Group) actor).getChildren();
            for (int i = 0; i < children.size; i++) {
                drawDebugVulkanRecursive(children.get(i), batch);
            }
        }
    }

    /** Draws debug bounds for a single actor using VulkanSpriteBatch */
    private void drawActorDebugVulkan(Actor actor, Batch batch) {
        // Store original batch color
        tmpColor.set(batch.getColor());

        // Use a fixed debug color since actor.getDebugColor() doesn't exist.
        float debugAlpha = 0.4f;
        batch.setColor(0, 0, 1, debugAlpha); // Blue, 40% opacity

        // Calculate bottom-left position in STAGE coordinates
        tmpCoords.set(0, 0);
        actor.localToStageCoordinates(tmpCoords);
        float stageX = tmpCoords.x;
        float stageY = tmpCoords.y;

        float width = actor.getWidth();
        float height = actor.getHeight();

        if (whitePixel != null) {
            batch.draw(whitePixel, stageX, stageY, width, height);
        } else {
            Gdx.app.error(TAG, "whitePixel texture is null during debug draw!");
            batch.setColor(tmpColor); // Restore color before returning
            return;
        }

        // Draw origin marker if needed
        if (actor.getOriginX() != 0 || actor.getOriginY() != 0 || actor.getRotation() != 0 || actor.getScaleX() != 1
                || actor.getScaleY() != 1) {
            tmpCoords.set(actor.getOriginX(), actor.getOriginY());
            actor.localToStageCoordinates(tmpCoords);
            batch.setColor(Color.YELLOW.r, Color.YELLOW.g, Color.YELLOW.b, Math.max(debugAlpha, 0.8f));
            batch.draw(whitePixel, tmpCoords.x - 2.5f, tmpCoords.y - 2.5f, 5, 5);
        }

        // Restore original batch color
        batch.setColor(tmpColor);
    }

    /** Convenience method to get the batch cast to VulkanSpriteBatch.
     *
     * @return The VulkanSpriteBatch used by this stage, or null if the batch is not set or not a VulkanSpriteBatch. */
    public VulkanSpriteBatch getVulkanBatch() {
        Batch batch = getBatch();
        if (batch instanceof VulkanSpriteBatch) {
            return (VulkanSpriteBatch) batch;
        }
        return null;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        // Original logging for raw coords
        System.out.println("_____________________\n>>> VulkanStage.touchDown Received: screen(" + screenX + ", " + screenY + ")");

        // Optional: Keep logging viewport/camera state and calculated coords if helpful
        Viewport viewport = getViewport();
        Camera camera = getCamera();
        if (viewport != null) {
            System.out.println("    Viewport State: screenX=" + viewport.getScreenX() + ", screenY=" + viewport.getScreenY()
                    + ", screenWidth=" + viewport.getScreenWidth() + ", screenHeight=" + viewport.getScreenHeight() + ", worldWidth="
                    + viewport.getWorldWidth() + ", worldHeight=" + viewport.getWorldHeight());

            // Use temporary vectors for calculation without logging matrices here
            Vector2 tempVec = new Vector2(screenX, screenY);
            screenToStageCoordinates(tempVec); // Use standard method
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
        // Optional: Could also add the firstInputProcessed check here if needed,
        // but touchDown/screenToStageCoordinates are usually sufficient.
        return super.mouseMoved(screenX, screenY);
    }
}

/*
 * package com.badlogic.gdx.backend.vulkan; // Or com.badlogic.gdx.scenes.scene2d? Keep consistent
 *
 * import com.badlogic.gdx.Application; import com.badlogic.gdx.Gdx; import com.badlogic.gdx.graphics.Camera; import
 * com.badlogic.gdx.graphics.Color; import com.badlogic.gdx.graphics.Pixmap; import com.badlogic.gdx.graphics.g2d.Batch; import
 * com.badlogic.gdx.math.Matrix4; import com.badlogic.gdx.math.Vector2; import com.badlogic.gdx.scenes.scene2d.Actor; import
 * com.badlogic.gdx.scenes.scene2d.Group; import com.badlogic.gdx.scenes.scene2d.Stage; import
 * com.badlogic.gdx.utils.SnapshotArray; import com.badlogic.gdx.utils.viewport.Viewport;
 *
 */
/** A Scene2D Stage implementation for the Vulkan backend.
 * <p>
 * It utilizes a {@link VulkanSpriteBatch} for rendering. Unlike the default Stage, this implementation expects the Batch's
 * {@code begin()} and {@code end()} methods (along with Vulkan command buffer and render pass setup) to be handled externally by
 * the main rendering loop (e.g., in VulkanGraphics). The {@link #draw()} method only handles viewport application and drawing the
 * actor hierarchy. *//*
 *
 * public class VulkanStage extends Stage { private final String TAG = "VulkanStage";
 *
 * private final boolean managesInternalBatch; public VulkanTexture whitePixel; private final Vector2
 * tmpCoords = new Vector2(); private final Color tmpColor = new Color(); private Vector2
 * tempScreenCoords=new Vector2(); private Vector2 tempStageCoords= new Vector2();
 *
 */
/** Creates a VulkanStage with a {@link VulkanScreenViewport}, creating and owning its own internal {@link VulkanSpriteBatch}.
 * This Stage will dispose the internally created batch when the stage itself is disposed.
 * <p>
 * Note: Requires VulkanScreenViewport or similar to be available. If VulkanScreenViewport isn't defined, use `new VulkanStage(new
 * ScreenViewport())` and ensure ScreenViewport works correctly with your Vulkan backend's camera setup. Consider creating a
 * specific VulkanScreenViewport if needed. *//*
 *
 * public VulkanStage() { this(new VulkanScreenViewport()); }
 *
 */
/** Creates a VulkanStage with the specified viewport, creating and owning its own internal {@link VulkanSpriteBatch}. This Stage
 * will dispose the internally created batch when the stage itself is disposed.
 *
 * @param viewport The viewport to manage the stage's camera and projection. *//*
 *
 * public VulkanStage(Viewport viewport) {
 * super(viewport, new VulkanSpriteBatch());//
 * Super constructor will set ownsBatch = false
 * this.managesInternalBatch = true;
 * createWhitePixel(); }
 *
 */
/** Creates a VulkanStage with the specified viewport and an externally provided batch. This Stage will NOT own or dispose the
 * provided batch.
 *
 * @param viewport The viewport to manage the stage's camera and projection.
 * @param batch The VulkanSpriteBatch instance to use for drawing. Must not be null. *//*
 *
 * public VulkanStage(Viewport viewport,
 * VulkanSpriteBatch batch) {
 * super(viewport, batch);
 * this.managesInternalBatch = false;
 * createWhitePixel(); }
 *
 * @Override public void draw() { if
 * (!getRoot().isVisible()) return;
 *
 * Viewport viewport = getViewport();
 * Camera camera = viewport.getCamera();
 * Batch batch = getBatch(); // Should be
 * VulkanSpriteBatch
 *
 * if (batch == null) { Gdx.app.error(TAG,
 * "Stage.draw() called but Batch is null!"
 * ); return; }
 *
 * // 1. Setup camera and batch projection
 * viewport.apply(); camera.update();
 * batch.setProjectionMatrix(camera.
 * combined);
 *
 * // 2. Begin batch (VulkanStage class
 * comment implies begin/end are external,
 * // but the original draw() method called
 * them. Let's keep that pattern.) boolean
 * batchWasDrawing = batch.isDrawing(); //
 * Should ideally be false here if
 * (!batchWasDrawing) { batch.begin(); }
 * else { // If it was already drawing,
 * maybe flush? Or log warning? //
 * Gdx.app.log(TAG,
 * "Warning: Batch was already drawing in Stage.draw()"
 * ); }
 *
 *
 * // 3. Draw actors normally
 * getRoot().draw(batch, 1.0f);
 *
 * // 4. Draw debug visuals if enabled,
 * BEFORE ending the batch if (isDebugAll()
 * && whitePixel != null) { // Use the
 * stage's debug flag try {
 * drawDebugVulkanRecursive(getRoot(),
 * batch); // Call the recursive debug draw
 * } catch (Exception e) {
 * Gdx.app.error(TAG,
 * "Error during debug draw: " +
 * e.getMessage(), e); // Potentially end
 * batch here if error is severe } }
 *
 * // 5. End batch if we started it (or if
 * it was always meant to be ended here) if
 * (!batchWasDrawing) { batch.end(); } else
 * { // If it was already drawing, should
 * we end it? Based on class comment, maybe
 * not. // This depends heavily on how the
 * external render loop uses the stage. //
 * For consistency with original code
 * block, we will end it. batch.end(); }
 *
 * VulkanApplication app =
 * (VulkanApplication) Gdx.app; if
 * (app.getCurrentWindow().runOnce) {
 * //Gdx.app.log(TAG,
 * app.getCurrentWindow().getConfig().title
 * + "\tCamera: " +
 * viewport.getScreenWidth() + " x " +
 * viewport.getScreenHeight());
 * app.getCurrentWindow().runOnce = false;
 * }
 *
 * }
 *
 *
 * // --- Dispose Method (Modified to
 * dispose whitePixel) ---
 *
 * @Override public void dispose() {
 * Gdx.app.log(TAG,
 * "Disposing VulkanStage..."); // Dispose
 * internal batch FIRST if we own it Batch
 * batch = getBatch(); if
 * (managesInternalBatch && batch != null)
 * { Gdx.app.log(TAG,
 * "Disposing internally managed VulkanSpriteBatch."
 * ); batch.dispose(); // Important: Set
 * batch to null in superclass AFTER
 * disposing it // super.setBatch(null); //
 * Requires Stage.setBatch to be accessible
 * or use reflection } else {
 * Gdx.app.log(TAG,
 * "Dispose: Not disposing batch (managed externally or null). managesInternalBatch="
 * + managesInternalBatch); }
 *
 * // Dispose the white pixel texture if
 * (whitePixel != null) {
 * whitePixel.dispose(); whitePixel = null;
 * Gdx.app.log(TAG,
 * "Disposed whitePixel texture."); }
 *
 * // Call super.dispose() AFTER handling
 * batch/resources owned by this subclass
 * // Though super.dispose() currently does
 * nothing, it's good practice.
 * super.dispose(); }
 *
 * // --- Vulkan-Compatible Debug Drawing
 * Logic (Copied In) ---
 *
 * private void createWhitePixel() { //
 * Avoid creating if already exists (e.g.,
 * multiple constructor calls) if
 * (whitePixel != null) return; try {
 * Pixmap pixmap = new Pixmap(1, 1,
 * Pixmap.Format.RGBA8888);
 * pixmap.setColor(Color.WHITE);
 * pixmap.fill(); whitePixel = new
 * VulkanTexture(pixmap); pixmap.dispose();
 * Gdx.app.log(TAG,
 * "Created whitePixel texture."); } catch
 * (Exception e) { Gdx.app.error(TAG,
 * "Failed to create whitePixel texture",
 * e); whitePixel = null; // Ensure it's
 * null on failure } }
 *
 */
/** Recursive method to draw debug info for actors using VulkanSpriteBatch *//*
 *
 * private void drawDebugVulkanRecursive(Actor
 * actor, Batch batch) { if (actor == null) return;
 *
 * // Determine if we should draw debug for this
 * actor based on existing flags boolean
 * actorIsVisible = actor.isVisible(); boolean
 * drawDebug = actor.getDebug(); // Check the
 * actor's specific flag
 *
 * // If the actor's flag isn't set, check the
 * stage's global flag, // but only draw if the
 * actor is also visible in that case. if
 * (!drawDebug && isDebugAll() && actorIsVisible) {
 * drawDebug = true; }
 *
 * // If the actor is invisible, only draw debug if
 * its own debug flag is true. // (Standard behavior
 * often skips invisible actors entirely when
 * isDebugAll is true, // but respecting the actor's
 * specific flag seems reasonable). if
 * (!actorIsVisible && !actor.getDebug()) { return;
 * }
 *
 * // Draw debug visuals for this actor if needed if
 * (drawDebug) { drawActorDebugVulkan(actor, batch);
 * }
 *
 * // Recurse for groups if (actor instanceof Group)
 * { SnapshotArray<Actor> children = ((Group)
 * actor).getChildren(); // Use iterator or indexed
 * loop for (int i = 0; i < children.size; i++) {
 * drawDebugVulkanRecursive(children.get(i), batch);
 * } } }
 *
 */
/** Draws debug bounds for a single actor using VulkanSpriteBatch *//*
 *
 * private void drawActorDebugVulkan(Actor actor, Batch
 * batch) { // Store original batch color
 * tmpColor.set(batch.getColor());
 *
 * // Use a fixed debug color since actor.getDebugColor()
 * doesn't exist. // Let's use a semi-transparent blue for
 * bounds. float debugAlpha = 0.4f; batch.setColor(0, 0, 1,
 * debugAlpha); // Blue, 40% opacity
 *
 * // --- Draw Axis-Aligned Bounding Box (Filled) --- //
 * Calculate bottom-left position in STAGE coordinates
 * tmpCoords.set(0, 0);
 * actor.localToStageCoordinates(tmpCoords); float stageX =
 * tmpCoords.x; float stageY = tmpCoords.y;
 *
 * float width = actor.getWidth(); float height =
 * actor.getHeight();
 *
 * // Draw filled rectangle representing logical bounds
 * (ignores actor transform) if (whitePixel != null) { //
 * Draw the rectangle covering the actor's logical area at
 * its stage position batch.draw(whitePixel, stageX, stageY,
 * width, height); } else { Gdx.app.error(TAG,
 * "whitePixel texture is null during debug draw!");
 * batch.setColor(tmpColor); return; }
 *
 * //if (actor.getOriginX() != 0 || actor.getOriginY() != 0
 * || actor.getRotation() != 0 || actor.getScaleX() != 1 ||
 * actor.getScaleY() != 1) { // Calculate origin position in
 * STAGE coordinates tmpCoords.set(actor.getOriginX(),
 * actor.getOriginY());
 * actor.localToStageCoordinates(tmpCoords);
 *
 * // Draw marker slightly more opaque and yellow
 * batch.setColor(Color.YELLOW.r, Color.YELLOW.g,
 * Color.YELLOW.b, Math.max(debugAlpha, 0.8f)); // Draw 5x5
 * square centered roughly on origin batch.draw(whitePixel,
 * tmpCoords.x - 2.5f, tmpCoords.y - 2.5f, 5, 5); //}
 *
 * // Restore original batch color batch.setColor(tmpColor);
 * }
 *
 */
/** Convenience method to get the batch cast to VulkanSpriteBatch.
 *
 * @return The VulkanSpriteBatch used by this stage, or null if the batch is not set or not a VulkanSpriteBatch. *//*
 *
 * public
 * VulkanSpriteBatch
 * getVulkanBatch
 * () { Batch
 * batch =
 * getBatch()
 * ; if
 * (batch
 * instanceof
 * VulkanSpriteBatch)
 * { return
 * (VulkanSpriteBatch)
 * batch; }
 * return
 * null; }
 *
 * @Override
 * public
 * boolean
 * touchDown(
 * int
 * screenX,
 * int
 * screenY,
 * int
 * pointer,
 * int
 * button) {
 * // ---
 * START
 * DEBUG
 * LOGGING
 * ---
 * System.out
 * .
 * println("_____________________\n>>> VulkanStage.touchDown Received: screen("
 * + screenX
 * + ", " +
 * screenY +
 * ")"); //
 * Raw coords
 * received
 *
 * Viewport
 * viewport =
 * getViewport
 * (); Camera
 * camera =
 * getCamera(
 * );
 *
 * if
 * (viewport
 * != null) {
 * // Log the
 * viewport
 * state
 * *before*
 * unprojection
 * System.out
 * .
 * println("    Viewport State: screenX="
 * +
 * viewport.
 * getScreenX
 * () +
 * ", screenY="
 * +
 * viewport.
 * getScreenY
 * () +
 * ", screenWidth="
 * +
 * viewport.
 * getScreenWidth
 * () +
 * ", screenHeight="
 * +
 * viewport.
 * getScreenHeight
 * () +
 * ", worldWidth="
 * +
 * viewport.
 * getWorldWidth
 * () +
 * ", worldHeight="
 * +
 * viewport.
 * getWorldHeight
 * ());
 *
 * // Perform
 * the
 * conversion
 * tempScreenCoords
 * .set(
 * screenX,
 * screenY);
 * // Use the
 * viewport's
 * unproject
 * method
 * directly
 * for
 * clarity,
 * or stage.
 * screenToStageCoordinates
 * viewport.
 * unproject(
 * tempScreenCoords
 * ); //
 * Modifies
 * tempScreenCoords
 * in-place
 * to stage
 * coords
 * tempStageCoords
 * .set(
 * tempScreenCoords
 * ); // Copy
 * result to
 * avoid
 * modification
 * if
 * screenToStageCoords
 * is used
 * later
 *
 * System.out
 * .
 * println("    Calculated Stage Coords: ("
 * +
 * tempStageCoords
 * .x + ", "
 * +
 * tempStageCoords
 * .y + ")");
 *
 * // Log
 * camera
 * state if
 * relevant
 * if (camera
 * != null) {
 * System.out
 * .
 * println("    Camera State: pos="
 * + camera.
 * position +
 * ", dir=" +
 * camera.
 * direction
 * + ", up="
 * +
 * camera.up)
 * ; //
 * Logging
 * camera.
 * combined
 * matrix
 * might be
 * too
 * verbose
 * unless
 * needed }
 *
 * } else {
 * System.out
 * .
 * println("    Viewport is NULL, cannot calculate stage coordinates or log state."
 * ); } //
 * --- END
 * DEBUG
 * LOGGING
 * ---
 *
 * //
 * Original
 * processing
 * continues.
 * .. return
 * super.
 * touchDown(
 * screenX,
 * screenY,
 * pointer,
 * button);
 *
 * }
 *
 * @Override
 * public
 * boolean
 * mouseMoved
 * (int
 * screenX,
 * int
 * screenY) {
 * //System.
 * out.
 * println(">>> Window 2 InputProcessor: Raw Event screen Coords: ("
 * + screenX
 * + ", " +
 * screenY +
 * ")");
 * return
 * super.
 * mouseMoved
 * (screenX,
 * screenY);
 * // Or
 * whatever
 * your
 * handler
 * does } }
 */
