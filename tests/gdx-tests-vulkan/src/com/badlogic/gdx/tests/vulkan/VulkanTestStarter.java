package com.badlogic.gdx.tests.vulkan;

import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;

import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver;
import com.badlogic.gdx.backend.vulkan.VulkanApplication;
import com.badlogic.gdx.backend.vulkan.VulkanApplicationConfiguration;
import com.badlogic.gdx.backend.vulkan.VulkanDebugLogger;
import com.badlogic.gdx.backend.vulkan.VulkanFillViewport;

import com.badlogic.gdx.backend.vulkan.VulkanScrollPane;
import com.badlogic.gdx.backend.vulkan.VulkanStage;
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.backend.vulkan.VulkanTextureAtlasLoader;
import com.badlogic.gdx.backend.vulkan.VulkanTextureLoader;
import com.badlogic.gdx.backend.vulkan.VulkanWindow;
import com.badlogic.gdx.backend.vulkan.VulkanWindowConfiguration;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.tests.utils.CommandLineOptions;
import com.badlogic.gdx.tests.utils.GdxTestWrapper;

import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.viewport.Viewport;

public class VulkanTestStarter {
    static CommandLineOptions options;

    public static void main(String[] argv) {

        int windowWidth = 640;
        int windowHeight = 480;

        options = new CommandLineOptions(argv);
        VulkanApplicationConfiguration vkConfig = new VulkanApplicationConfiguration();
        vkConfig.setTitle("Vulkan Test"); // Default title
        vkConfig.setWindowedMode(windowWidth, windowHeight);
        vkConfig.enableValidationLayers(true, null);
        vkConfig.setPresentationMode(VulkanApplicationConfiguration.SwapchainPresentMode.FIFO);

        VulkanDebugLogger.enableAll();

        ApplicationListener listenerToStart;
        String testName = options.startupTestName;

        if (testName != null) {
            System.out.println("Attempting to launch Vulkan test from argument: " + testName);
            // Use your Vulkan test registry
            listenerToStart = GdxVulkanTests.newTest(testName);

            if (listenerToStart != null) {
                System.out.println("Launching Vulkan test: " + testName);
                vkConfig.setTitle(testName + " (Vulkan)"); // Set specific title
                // Wrap the test listener
                listenerToStart = new GdxTestWrapper(listenerToStart, false); // false = disable GL errors
                try {
                    new VulkanApplication(listenerToStart, vkConfig);
                } catch (Throwable t) {
                    System.err.println("Exception thrown during VulkanApplication creation:");
                    t.printStackTrace();
                    System.exit(1); // Exit if application creation fails critically
                }
            } else {
                System.err.println("ERROR: Vulkan test specified via argument not found: " + testName);
                System.err.println("Falling back to Test Chooser.");
            }
        }

        if (options.startupTestName != null) {
            ApplicationListener test = GdxVulkanTests.newTest(options.startupTestName);
            if (test != null) {
                new VulkanApplication(test, vkConfig);
                return;
            }
        }
        new VulkanApplication(new VulkanTestChooser(), vkConfig);
    }

    static class VulkanTestChooser extends ApplicationAdapter {
        private final String TAG = "VulkanTestChooser";

        private VulkanStage stage;
        private Skin skin;
        private AssetManager assetManager;

        @Override
        public void create() {
            Gdx.app.log(TAG, "create() called. Initializing UI...");

            FileHandleResolver resolver = new InternalFileHandleResolver();
            assetManager = new AssetManager();
            assetManager.setLoader(VulkanTexture.class, new VulkanTextureLoader(resolver));
            assetManager.setLoader(TextureAtlas.class, new VulkanTextureAtlasLoader(resolver));

            String skinPath = "data/uiskin.json";
            try {
                assetManager.load(skinPath, Skin.class);
                assetManager.finishLoading(); // Important to block here
                skin = assetManager.get(skinPath, Skin.class);
            } catch (Exception e) {
                if (assetManager != null) assetManager.dispose();
                throw new GdxRuntimeException("Failed to load uiskin via AssetManager", e);
            }

            Viewport viewport = new VulkanFillViewport(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            stage = new VulkanStage(viewport);

            Gdx.input.setInputProcessor(stage);

            Table tableTests = new Table(skin);
            final VulkanScrollPane scroll = new VulkanScrollPane(tableTests, skin);

            for (final String testName : GdxVulkanTests.getTestNames()) {
                final TextButton testButton = new TextButton(testName, skin);
                testButton.setName(testName);
                testButton.addListener(new ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, Actor actor) {
                        ApplicationListener test = GdxVulkanTests.newTest(testName);
                        if(test != null){
                            VulkanWindowConfiguration winConfig = new VulkanWindowConfiguration();
                            winConfig.setTitle(testName + " (Vulkan)");
                            winConfig.setWindowedMode(960, 640);
                            ((VulkanApplication)Gdx.app).newWindow(test, winConfig);
                        }
                    }
                });
                tableTests.add(testButton).expandX().fillX().padTop(4);
                tableTests.row();
            }

            Table container = new Table();
            container.setFillParent(true);
            container.pad(10);
            container.add(new Label("Vulkan Tests", skin)).top().row();
            container.add(scroll).expand().fill();

            stage.addActor(container);
            //stage.setDebugAll(true);
            Gdx.app.log(TAG, "Initialization complete.");
        }

        @Override
        public void render() {
            if(stage != null) {
                stage.act();
                stage.draw();
            }
        }

        @Override
        public void resize(int width, int height) {
            if (stage != null && stage.getViewport() != null) {
                stage.getViewport().update(width, height, true);
            }
        }

        @Override
        public void dispose() {
            Gdx.app.log(TAG, "Disposing VulkanTestChooser...");
            if (assetManager != null) {
                assetManager.dispose();
                assetManager = null;
            }
            if (stage != null) {
                stage.dispose();
                stage = null;
            }
        }
    }
}