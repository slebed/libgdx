/*
package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver;
import com.badlogic.gdx.backend.vulkan.VulkanApplication;
import com.badlogic.gdx.backend.vulkan.VulkanApplicationConfiguration;
import com.badlogic.gdx.backend.vulkan.VulkanScreenViewport;
import com.badlogic.gdx.backend.vulkan.VulkanStage;
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.backend.vulkan.VulkanTextureAtlasLoader;
import com.badlogic.gdx.backend.vulkan.VulkanTextureLoader;
import com.badlogic.gdx.graphics.Color;
// Need Texture for AssetManager key
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage; // Use Stage as type, VulkanStage is implementation
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.GdxRuntimeException;
// For clear color if needed outside render pass
// Use core ScreenViewport
import com.badlogic.gdx.utils.viewport.Viewport;

*/
/**
 * A minimal Vulkan backend test case rendering a single Scene2D TextButton.
 * Helps isolate rendering loop issues.
 * Assumes uiskin assets are in a 'data' folder accessible to the application.
 *//*

public class VulkanMinimalTest implements ApplicationListener {

    private final String TAG = "VulkanMinimalTest";

    private Stage stage; // Use core Stage type
    private Skin skin;
    private AssetManager assetManager;
    private TextButton button;

    @Override
    public void create() {
        Gdx.app.log(TAG, "Minimal test starting up...");

        // --- Asset Loading ---
        // Use internal resolver, assuming assets are in classpath/internal storage
        FileHandleResolver resolver = new InternalFileHandleResolver();
        assetManager = new AssetManager(resolver);

        // Register loaders for Vulkan types
        // NOTE: Adjust package names if necessary for your backend structure
        assetManager.setLoader(Texture.class, new VulkanTextureLoader(resolver));
        assetManager.setLoader(TextureAtlas.class, new VulkanTextureAtlasLoader(resolver));
        // TextureAtlasLoader requires TextureAtlas.AtlasData, Texture.class might be needed implicitly

        String skinPath = "data/uiskin.json"; // Ensure this path is correct
        Gdx.app.log(TAG, "Queueing skin for loading: " + skinPath);
        try {
            // Explicitly tell AssetManager about Texture dependency if needed by loaders
            assetManager.load(skinPath, Skin.class);

            // Block until loading is complete
            assetManager.finishLoading();

            skin = assetManager.get(skinPath, Skin.class);
            Gdx.app.log(TAG, "Skin loaded successfully.");

        } catch (Exception e) {
            Gdx.app.error(TAG, "Failed to load skin: " + skinPath, e);
            // Clean up if loading failed
            if (assetManager != null) assetManager.dispose();
            if (skin != null) skin.dispose(); // Should be null, but safety
            throw new GdxRuntimeException("Failed to load uiskin assets for minimal test.", e);
        }

        // --- Stage and Actor Setup ---
        // Use VulkanScreenViewport if available and needed, otherwise core ScreenViewport
        Viewport viewport = new VulkanScreenViewport(); // Or new ScreenViewport();
        // Create VulkanStage, which uses VulkanSpriteBatch internally
        stage = new VulkanStage(viewport); // Stage owns the batch by default
        Gdx.app.log(TAG, "VulkanStage created. Hash: " + stage.hashCode());

        button = new TextButton("Test Button", skin);
        // Center the button roughly
        button.setPosition(
                (Gdx.graphics.getWidth() - button.getWidth()) / 2f,
                (Gdx.graphics.getHeight() - button.getHeight()) / 2f
        );

        button.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Gdx.app.log(TAG, "Button Clicked!");
                // Toggle button color or text as feedback
                button.setColor(Color.CYAN);
            }
        });

        stage.addActor(button);

        // --- Set Input Processor ---
        Gdx.input.setInputProcessor(stage);
        Gdx.app.log(TAG, "Input processor set to Stage.");

        Gdx.app.log(TAG, "Minimal test create() finished.");
    }

    @Override
    public void resize(int width, int height) {
        Gdx.app.log(TAG, "Resizing to: " + width + "x" + height);
        if (stage != null && stage.getViewport() != null) {
            // Use physical back buffer dimensions for viewport update in Vulkan
            int backBufferWidth = Gdx.graphics.getBackBufferWidth();
            int backBufferHeight = Gdx.graphics.getBackBufferHeight();
            Gdx.app.log(TAG, "Updating viewport with BackBuffer dimensions: " + backBufferWidth + "x" + backBufferHeight);
            stage.getViewport().update(backBufferWidth, backBufferHeight, true); // centerCamera=true

            // Recenter button after resize (optional)
            if(button != null) {
                button.setPosition(
                        (stage.getViewport().getWorldWidth() - button.getWidth()) / 2f,
                        (stage.getViewport().getWorldHeight() - button.getHeight()) / 2f
                );
            }
        } else {
            Gdx.app.log(TAG, "Stage or Viewport is null during resize!");
        }
    }

    @Override
    public void render() {
        // Vulkan backend handles clearing via render pass LoadOp=CLEAR configured in VulkanWindow
        // ScreenUtils.clear(0.2f, 0.2f, 0.2f, 1); // Not needed if render pass clears

        // Update and draw the stage
        // Stage internally uses its VulkanSpriteBatch
        if (stage != null) {
            stage.act(Gdx.graphics.getDeltaTime()); // Update actors (animations, actions)
            stage.draw(); // Draw actors
        }
    }

    @Override
    public void pause() {
        Gdx.app.log(TAG, "pause() called.");
    }

    @Override
    public void resume() {
        Gdx.app.log(TAG, "resume() called.");
    }

    @Override
    public void dispose() {
        Gdx.app.log(TAG, "Disposing minimal test...");

        // Dispose order: Stage -> Assets (Skin/Textures via AssetManager)
        if (stage != null) {
            Gdx.app.log(TAG, "Disposing Stage (Hash: " + stage.hashCode() + ")");
            stage.dispose(); // Disposes internal VulkanSpriteBatch and white pixel texture
            stage = null;
        } else {
            Gdx.app.log(TAG, "Stage was null, cannot dispose.");
        }

        if (assetManager != null) {
            Gdx.app.log(TAG, "Disposing AssetManager...");
            assetManager.dispose(); // Disposes Skin, TextureAtlas, VulkanTextures loaded by it
            assetManager = null;
            skin = null; // Nullify reference after manager disposal
            Gdx.app.log(TAG, "AssetManager disposed.");
        } else if (skin != null) {
            // Should not happen if AssetManager was used, but safety check
            Gdx.app.log(TAG, "AssetManager was null, disposing Skin directly.");
            skin.dispose();
            skin = null;
        }

        button = null; // Nullify actor reference

        Gdx.app.log(TAG, "Minimal test disposed.");
    }

    // --- Main Method to Launch This Test ---
    public static void main(String[] args) {
        VulkanApplicationConfiguration config = new VulkanApplicationConfiguration();
        config.setTitle("Vulkan Minimal Test");
        config.setWindowedMode(800, 600);
        config.setResizable(true);
        config.enableValidationLayers(true, null); // Enable validation layers

        // --- IMPORTANT: Set Presentation Mode ---
        // Use FIFO as it seemed more stable in previous tests, or MAILBOX to test that specifically
        config.setPresentationMode(VulkanApplicationConfiguration.SwapchainPresentMode.FIFO);
        // config.setPresentationMode(VulkanApplicationConfiguration.SwapchainPresentMode.MAILBOX);

        System.out.println("VulkanMinimalTest"+ "\tLaunching application...");
        try {
            new VulkanApplication(new VulkanMinimalTest(), config);
        } catch (Throwable t) {
            System.err.println("Exception during VulkanApplication creation:");
            t.printStackTrace();
            System.exit(1); // Exit if application creation fails critically
        }
    }
}*/
