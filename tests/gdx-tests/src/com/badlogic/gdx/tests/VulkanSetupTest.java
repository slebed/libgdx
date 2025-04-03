package com.badlogic.gdx.tests;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.tests.utils.GdxTest;

public class VulkanSetupTest extends GdxTest {

    @Override
    public void create() {
        // If this point is reached, VulkanApplication likely initialized core components successfully.
        Gdx.app.log("VulkanSetupTest", "create() SUCCESS!");
        Gdx.app.log("VulkanSetupTest", "Gdx.graphics type: " + Gdx.graphics.getClass().getSimpleName());
        Gdx.app.log("VulkanSetupTest", "Window dimensions: " + Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight());

        // Optional: Set a clear color just to see if graphics context is somewhat usable
        // Note: This relies on your VulkanGraphics implementing the clear operation.
        // Gdx.gl.glClearColor(0.1f, 0.1f, 0.2f, 1f); // If implementing GL interface
    }

    @Override
    public void render() {

    }

    @Override
    public void dispose() {
        Gdx.app.log("VulkanSetupTest", "dispose() called.");
    }

}