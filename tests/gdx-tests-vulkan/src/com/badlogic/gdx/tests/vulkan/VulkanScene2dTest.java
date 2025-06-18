/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver;
import com.badlogic.gdx.backend.vulkan.VulkanApplication;
import com.badlogic.gdx.backend.vulkan.VulkanDevice;
import com.badlogic.gdx.backend.vulkan.VulkanScreenViewport;
import com.badlogic.gdx.backend.vulkan.VulkanStage;
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.backend.vulkan.VulkanTextureAtlasLoader;
import com.badlogic.gdx.backend.vulkan.VulkanTextureLoader;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.viewport.Viewport;

import org.lwjgl.vulkan.VK10;

public class VulkanScene2dTest extends GdxTest {
    private static final String TAG = "Scene2dTest";
    private AssetManager assetManager;
    private VulkanStage testStage;
    private VulkanTexture badlogicTexture;

    @Override
    public void create() {
        Gdx.app.log(TAG, "create() method ENTERED."); // <<< ADD THIS
        try {
            Gdx.app.log(TAG, "Setting up FileHandleResolver...");
            FileHandleResolver resolver = new InternalFileHandleResolver();

            Gdx.app.log(TAG, "Creating AssetManager...");
            assetManager = new AssetManager(resolver);
            assetManager.setLoader(VulkanTexture.class, new VulkanTextureLoader(resolver));
            assetManager.setLoader(TextureAtlas.class, new VulkanTextureAtlasLoader(resolver));

            String skinPath = "data/uiskin.json";
            Skin skin = null; // Initialize to null

            //Gdx.app.log(TAG, "Queueing skin for loading: " + skinPath);
            assetManager.load(skinPath, Skin.class);
            //Gdx.app.log(TAG, "Calling assetManager.finishLoading()...");
            assetManager.finishLoading(); // Block
            //Gdx.app.log(TAG, "assetManager.finishLoading() completed.");
            skin = assetManager.get(skinPath, Skin.class);
            //Gdx.app.log(TAG, "Skin retrieved from AssetManager. Skin is null? " + (skin == null));

            //Gdx.app.log(TAG, "Attempting to create VulkanStage...");
            Viewport viewport = new VulkanScreenViewport(); // Consider using a simpler ScreenViewport first?
            testStage = new VulkanStage(viewport);
            //Gdx.app.log(TAG, "VulkanStage CREATED. Hash: " + testStage.hashCode());

            //Gdx.app.log(TAG, "Setting InputProcessor...");
            Gdx.input.setInputProcessor(testStage);
            //Gdx.app.log(TAG, "InputProcessor SET.");

            //Gdx.app.log(TAG, "Creating Table...");
            Table testTable = new Table();
            testTable.setFillParent(true);
            //Gdx.app.log(TAG, "Creating TextButton...");
            TextButton textButton = new TextButton("Click Me", skin); // Requires skin
            //textButton.setPosition(50, 50);
            //textButton.setSize(150, 50);
            textButton.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    Gdx.app.log(TAG, "TextButton clicked!");
                }
            });
            //Gdx.app.log(TAG, "TextButton CREATED.");

            //Gdx.app.log(TAG, "Attempting to create ImageButton texture...");
            //badlogicTexture = new VulkanTexture(Gdx.files.internal("data/badlogic.jpg"));
            //TextureRegionDrawable buttonDrawable = new TextureRegionDrawable(badlogicTexture); // Check file exists!
            //Gdx.app.log(TAG, "ImageButton texture CREATED.");
            //Gdx.app.log(TAG, "Creating ImageButton...");
            //ImageButton imageButton = new ImageButton(buttonDrawable);
            //Gdx.app.log(TAG, "ImageButton CREATED.");

            CheckBox checkBox = new CheckBox("Checkbox", skin);
            testTable.add(checkBox).pad(8).row();

            SelectBox<String> selectBox = new SelectBox<>(skin);
            selectBox.setItems("Item 1", "Item 2", "Item 3");
            testTable.add(selectBox).pad(8).row();

            //Gdx.app.log(TAG, "Adding actors to stage...");
            testTable.add(textButton).pad(8);
            //testTable.add(imageButton); // Still commented out
            //Gdx.app.log(TAG, "Actors added.");

            testTable.pack();
testStage.setDebugAll(true);
            testStage.addActor(testTable);


        } catch (Throwable t) {
            Gdx.app.error(TAG, "EXCEPTION occurred during create()!", t);
            // Make sure to re-throw or handle appropriately if needed,
            // but logging it is key.
        }
        //Gdx.app.log(TAG, "create() method EXITED. testStage is null? " + (testStage == null));

    }

    @Override
    public void render() {
        if (testStage == null) {
            Gdx.app.log(TAG, "testStage is NULL!");
            return;
        }

        testStage.act(Gdx.graphics.getDeltaTime());

        Batch batch = testStage.getBatch();
// Ensure whitePixel texture exists and is accessible from VulkanStage or here
// You might need to make VulkanStage.whitePixel accessible or recreate one here
        VulkanTexture whitePixel = testStage.whitePixel; // Example access

        if (whitePixel != null && batch != null) {
            boolean batchWasDrawing = batch.isDrawing();
            if (!batchWasDrawing) batch.begin(); // Begin if needed

            batch.setColor(Color.RED);
            batch.draw(whitePixel, 10, 10, 10, 10); // Draw 10x10 red square at logical origin
            batch.setColor(Color.WHITE); // Reset color

            if (!batchWasDrawing) batch.end(); // End if we started it
        }

        testStage.draw();

        /*cnt++;
        if (cnt == 10) {
            VulkanApplication app = (VulkanApplication) Gdx.app;
            app.getCurrentWindow().closeWindow();
        }*/
    }


    @Override
    public void resize(int width, int height) {
        //boolean wasFirst = firstResize;
        //firstResize = false; // Only log extensively the first time
        //if (wasFirst) System.out.println("--- Scene2dTest INITIAL resize(" + width + ", " + height + ") ---");
        System.out.println("--- Scene2dTest resize(" + width + ", " + height + ") CALLED (Logical Dims?) ---");

        if (testStage != null && testStage.getViewport() != null) {
            // Get the PHYSICAL back buffer dimensions
            int backBufferWidth = Gdx.graphics.getBackBufferWidth();
            int backBufferHeight = Gdx.graphics.getBackBufferHeight();
            System.out.println("    Using Physical BackBuffer Dimensions: " + backBufferWidth + "x" + backBufferHeight);

            System.out.println("    Viewport BEFORE update: screen=" + testStage.getViewport().getScreenWidth() + "x" + testStage.getViewport().getScreenHeight() + ", world=" + testStage.getViewport().getWorldWidth() + "x" + testStage.getViewport().getWorldHeight());
            try {
                // Update viewport using PHYSICAL dimensions
                testStage.getViewport().update(width, height, true);

                System.out.println("    Viewport AFTER update: screen=" + testStage.getViewport().getScreenWidth() + "x" + testStage.getViewport().getScreenHeight() + ", world=" + testStage.getViewport().getWorldWidth() + "x" + testStage.getViewport().getWorldHeight());

                if (testStage.getCamera() != null) {
                    testStage.getCamera().update();
                    System.out.println("    Camera updated immediately after viewport update.");
                    // Optional logging: Print camera pos/zoom after update
                    System.out.println("    Camera state: pos=" + testStage.getCamera().position + ", zoom=" + ((com.badlogic.gdx.graphics.OrthographicCamera) testStage.getCamera()).zoom);
                } else {
                    System.out.println("    Camera is NULL, cannot update.");
                }

            } catch (Exception e) {
                System.err.println("    ERROR during viewport.update: " + e.getMessage());
                //e.printStackTrace(); // Print stack trace for more detail
            }
        } else {
            System.out.println("    Stage or Viewport is NULL during resize!");
        }
        //if (wasFirst) System.out.println("    INITIAL Viewport AFTER update: screen=" + testStage.getViewport().getScreenWidth() + "x" + testStage.getViewport().getScreenHeight());
        //if (wasFirst && testStage.getCamera() != null) System.out.println("    INITIAL Camera state: pos=" + testStage.getCamera().position);

    }

    @Override
    public void dispose() {
        Gdx.app.log(TAG, "dispose() method ENTERED.");

        if (Gdx.app instanceof VulkanApplication app) {
            VulkanDevice device = app.getVulkanDevice();
            if (device != null && device.getRawDevice() != null) {
                Gdx.app.log(TAG, "Waiting for device idle before test disposal...");
                VK10.vkDeviceWaitIdle(device.getRawDevice()); // Wait for GPU
                Gdx.app.log(TAG, "Device idle.");
            } else {
                Gdx.app.error(TAG, "Cannot vkDeviceWaitIdle, device is null!");
            }
        }

        if (testStage != null) {
            Gdx.app.log(TAG, "Disposing VulkanStage (Hash: " + this.hashCode() + ")");
            testStage.dispose();
            testStage = null;
            Gdx.app.log(TAG, "VulkanStage disposed.");
        } else {
            Gdx.app.log(TAG, "testStage was NULL, skipping dispose.");
        }

        if (badlogicTexture != null) {
            Gdx.app.log(TAG, "Disposing badlogicTexture...");
            badlogicTexture.dispose();
            badlogicTexture = null;
            Gdx.app.log(TAG, "badlogicTexture disposed.");
        }

        if (assetManager != null) {
            Gdx.app.log(TAG, "Disposing AssetManager...");
            assetManager.dispose();
            assetManager = null;
            Gdx.app.log(TAG, "AssetManager disposed.");
        }
        Gdx.app.log(TAG, "dispose() method EXITED.");
    }
}
