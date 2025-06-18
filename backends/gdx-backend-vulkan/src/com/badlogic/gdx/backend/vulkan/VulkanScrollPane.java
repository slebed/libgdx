/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.badlogic.gdx.backend.vulkan;


import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.utils.Cullable;
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack;
import com.badlogic.gdx.utils.Null;
import com.badlogic.gdx.utils.Pools;

/**
 * A Vulkan-specific ScrollPane that provides custom clipping logic via {@link ScissorStack}
 * required for the Vulkan backend. It otherwise functions identically to the standard
 * {@link com.badlogic.gdx.scenes.scene2d.ui.ScrollPane} and uses its {@link ScrollPaneStyle}.
 * @author mzechner
 * @author Nathan Sweet
 */
public class VulkanScrollPane extends ScrollPane {
    private final Rectangle actorCullingArea = new Rectangle();

    public VulkanScrollPane (@Null Actor actor) {
        super(actor);
    }

    public VulkanScrollPane (@Null Actor actor, Skin skin) {
        super(actor, skin);
    }

    public VulkanScrollPane (@Null Actor actor, Skin skin, String styleName) {
        super(actor, skin, styleName);
    }

    public VulkanScrollPane (@Null Actor actor, ScrollPaneStyle style) {
        super(actor, style);
    }

    @Override
    public void layout () {
        super.layout();
        final Actor actor = getActor();
        if (actor instanceof Cullable) {
            actorCullingArea.x = -actor.getX() + getScrollX();
            actorCullingArea.y = -actor.getY() + getScrollY();
            actorCullingArea.width = getScrollWidth();
            actorCullingArea.height = getScrollHeight();
            ((Cullable)actor).setCullingArea(actorCullingArea);
        }
    }


    /** Overridden to use the Vulkan-compatible ScissorStack for clipping. */
    @Override
    public boolean clipBegin (float x, float y, float width, float height) {
        if (width <= 0 || height <= 0) return false;
        Stage stage = getStage();
        if (stage == null) return false;
        Rectangle tableBounds = Rectangle.tmp;
        tableBounds.x = x;
        tableBounds.y = y;
        tableBounds.width = width;
        tableBounds.height = height;
        Rectangle scissorBounds = Pools.obtain(Rectangle.class);
        stage.calculateScissors(tableBounds, scissorBounds);
        if (ScissorStack.pushScissors(scissorBounds)) return true;
        Pools.free(scissorBounds);
        return false;
    }

    /** Overridden to use the Vulkan-compatible ScissorStack for clipping. */
    @Override
    public void clipEnd () {
        Pools.free(ScissorStack.popScissors());
    }
}