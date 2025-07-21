package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack;
import com.badlogic.gdx.utils.Null;
import com.badlogic.gdx.utils.Pools;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

public class VulkanScrollPane extends ScrollPane {

    public VulkanScrollPane(@Null Actor actor) { super(actor); }
    public VulkanScrollPane(@Null Actor actor, Skin skin) { super(actor, skin); }
    public VulkanScrollPane(@Null Actor actor, Skin skin, String styleName) { super(actor, skin, styleName); }
    public VulkanScrollPane(@Null Actor actor, ScrollPaneStyle style) { super(actor, style); }

    /**
     * Called by VulkanStage to handle the debug rendering of this widget and its children.
     */
    public void drawDebug (VulkanStage stage, Batch batch) {
        // Draw the border of the scroll pane itself.
        //stage.drawLocalRect(this, batch);

        // Calculate the clipping area using public methods.
        validate();
        Drawable bg = getStyle().background;
        float areaX = (bg == null) ? 0 : bg.getLeftWidth();
        float areaY = (bg == null) ? 0 : bg.getBottomHeight();
        float areaWidth = getScrollWidth();
        float areaHeight = getScrollHeight();

        // Apply clipping.
        if (clipBegin(areaX, areaY, areaWidth, areaHeight)) {
            Actor actor = getActor();
            if (actor != null) {
                // This is the critical change:
                // We calculate the child's scrolled position using the same formula as the real draw pass.
                float x = areaX - (isScrollX() ? (int)getVisualScrollX() : 0);
                float y = areaY - (int)(isScrollY() ? getMaxY() - getVisualScrollY() : getMaxY());

                // We create a temporary transform for the child, apply it, and then recurse.
                actor.setPosition(x, y); // Temporarily set the correct position for the debug renderer.
                //stage.drawDebugRecursive(actor, batch);
            }
            clipEnd();
        }
    }

    @Override
    public boolean clipBegin(float x, float y, float width, float height) {
        Stage stage = getStage();
        if (stage == null || width <= 0 || height <= 0) return false;

        Rectangle localBounds = Pools.obtain(Rectangle.class).set(x, y, width, height);
        Rectangle scissorBounds = Pools.obtain(Rectangle.class);
        stage.calculateScissors(localBounds, scissorBounds);
        Pools.free(localBounds);

        if (stage.getBatch() != null) stage.getBatch().flush();
        if (ScissorStack.pushScissors(scissorBounds)) return true;

        Pools.free(scissorBounds);
        return false;
    }

    @Override
    public void clipEnd() {
        Stage stage = getStage();
        if (stage != null && stage.getBatch() != null) {
            stage.getBatch().flush();
        }
        Pools.free(ScissorStack.popScissors());
    }
}