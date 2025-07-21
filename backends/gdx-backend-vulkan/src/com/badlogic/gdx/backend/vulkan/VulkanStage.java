package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.SnapshotArray;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.badlogic.gdx.math.MathUtils;

public class VulkanStage extends Stage {
    private final boolean managesInternalBatch;

    public VulkanTexture whitePixel;
    private final float debugLineThickness = 1.0f;
    private final Color debugActorColor = new Color(0, 1, 0, 1);
    private final Color debugGroupColor = new Color(0, 0, 1, 1);

    private final Vector3 tmpV3 = new Vector3();
    private final Vector2 dbgCorner1 = new Vector2(), dbgCorner2 = new Vector2();
    private final Vector2 dbgCorner3 = new Vector2(), dbgCorner4 = new Vector2();
    private final Matrix4 idtMatrix = new Matrix4();

    public VulkanStage(Viewport viewport) {
        super(viewport, new VulkanSpriteBatch());
        this.managesInternalBatch = true;
        createWhitePixel();
    }

    public VulkanStage(Viewport viewport, VulkanSpriteBatch batch) {
        super(viewport, batch);
        this.managesInternalBatch = false;
        createWhitePixel();
    }

    @Override
    public void draw() {
        Camera camera = getCamera();
        Batch batch = getBatch();
        if (!getRoot().isVisible() || camera == null || batch == null) return;

        getViewport().apply();

        boolean batchWasDrawing = batch.isDrawing();
        if (!batchWasDrawing) batch.begin();

        batch.setProjectionMatrix(camera.combined);
        getRoot().draw(batch, 1.0f);
        batch.flush();

        if (isDebugAll()) {
            //System.out.println("\n\n--- DEBUG RENDER FRAME START ---");
            //System.out.println("Camera Combined Matrix:\n" + camera.combined);
            drawDebug(getRoot(), batch, new Matrix4(), 0);
        }

        if (!batchWasDrawing) batch.end();
    }

    private String indent(int depth) {
        char[] chars = new char[depth * 2];
        java.util.Arrays.fill(chars, ' ');
        return new String(chars);
    }

    private void drawDebug(Actor actor, Batch batch, Matrix4 parentTransform, int depth) {
        if (actor == null || !actor.isVisible()) return;

        String actorName = actor.getName() != null ? actor.getName() : actor.getClass().getSimpleName();
        //System.out.println(indent(depth) + "+ Processing: " + actorName);
        //System.out.println(indent(depth) + "  parentTransform:\n" + parentTransform.toString());

        Matrix4 worldTransform = computeWorldTransform(actor, parentTransform, depth);

        if (isDebugAll() || actor.getDebug()) {
            drawActorDebugLines(actor, batch, worldTransform, depth);
        }

        if (actor instanceof Group) {
            for (Actor child : ((Group) actor).getChildren()) {
                drawDebug(child, batch, worldTransform, depth + 1);
            }
        }
    }

    private Matrix4 computeWorldTransform(Actor actor, Matrix4 parentTransform, int depth) {
        Matrix4 localTransform = new Matrix4();
        float x = actor.getX(), y = actor.getY();
        float originX = actor.getOriginX(), originY = actor.getOriginY();
        float rotation = actor.getRotation();
        float scaleX = actor.getScaleX(), scaleY = actor.getScaleY();

        localTransform.setToTranslation(x, y, 0);
        if (rotation != 0 || scaleX != 1 || scaleY != 1) {
            localTransform.translate(originX, originY, 0);
            localTransform.rotate(Vector3.Z, rotation);
            localTransform.scale(scaleX, scaleY, 1);
            localTransform.translate(-originX, -originY, 0);
        }

        Matrix4 worldTransform = new Matrix4(parentTransform).mul(localTransform);
        //System.out.println(indent(depth) + "  localTransform:\n" + localTransform);
        //System.out.println(indent(depth) + "  worldTransform:\n" + worldTransform);

        return worldTransform;
    }

    private void drawActorDebugLines(Actor actor, Batch batch, Matrix4 worldTransform, int depth) {
        if (whitePixel == null) return;

        batch.setColor(actor instanceof Group ? debugGroupColor : debugActorColor);

        float w = actor.getWidth(), h = actor.getHeight();

        Vector2 c1 = dbgCorner1.set(0, 0);
        Vector2 c2 = dbgCorner2.set(w, 0);
        Vector2 c3 = dbgCorner3.set(w, h);
        Vector2 c4 = dbgCorner4.set(0, h);

        //System.out.println(indent(depth) + "  Drawing local rect (w,h): (" + w + ", " + h + ")");

        tmpV3.set(c1.x, c1.y, 0).mul(worldTransform); c1.set(tmpV3.x, tmpV3.y);
        tmpV3.set(c2.x, c2.y, 0).mul(worldTransform); c2.set(tmpV3.x, tmpV3.y);
        tmpV3.set(c3.x, c3.y, 0).mul(worldTransform); c3.set(tmpV3.x, tmpV3.y);
        tmpV3.set(c4.x, c4.y, 0).mul(worldTransform); c4.set(tmpV3.x, tmpV3.y);

        //System.out.println(indent(depth) + "  World Coords: c1" + c1 + " c2" + c2 + " c3" + c3 + " c4" + c4);

        batch.flush(); // Flush before drawing lines to ensure transform is set
        drawBatchLine(batch, c1, c2, debugLineThickness);
        drawBatchLine(batch, c2, c3, debugLineThickness);
        drawBatchLine(batch, c3, c4, debugLineThickness);
        drawBatchLine(batch, c4, c1, debugLineThickness);
        batch.flush(); // Flush after to ensure they are drawn
    }

    private void drawBatchLine(Batch batch, Vector2 start, Vector2 end, float thickness) {
        drawBatchLine(batch, start.x, start.y, end.x, end.y, thickness);
    }

    private void drawBatchLine(Batch batch, float x1, float y1, float x2, float y2, float thickness) {
        if (whitePixel == null) return;
        float dx = x2 - x1, dy = y2 - y1;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 0.0001f) return;
        float angleDeg = MathUtils.atan2(dy, dx) * MathUtils.radiansToDegrees;
        batch.draw(whitePixel, x1, y1, 0, thickness / 2f, dist, thickness, 1f, 1f, angleDeg, 0, 0, 1, 1, false, false);
    }

    private void createWhitePixel() {
        if (whitePixel != null) return;
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        whitePixel = new VulkanTexture(pixmap);
        pixmap.dispose();
    }

    @Override
    public void dispose() {
        if (managesInternalBatch && getBatch() != null) {
            getBatch().dispose();
        }
        if (whitePixel != null) {
            whitePixel.dispose();
            whitePixel = null;
        }
        super.dispose();
    }
}
