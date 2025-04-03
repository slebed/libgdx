package com.badlogic.gdx.tests;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.tests.utils.GdxTest;

public class VulkanQuadTest extends GdxTest {
    @Override
    public void create() {
        Gdx.app.log("VulkanQuadTest", "create()");
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public void render() { /* Backend handles rendering */ }

    @Override
    public void pause() {
    }

    @Override
    public void resume() {
    }

    @Override
    public void dispose() {
        Gdx.app.log("VulkanQuadTest", "dispose()");
    }
}