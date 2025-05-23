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
import com.badlogic.gdx.backend.vulkan.VulkanScreenViewport;

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
        options = new CommandLineOptions(argv);
        VulkanApplicationConfiguration vkConfig = new VulkanApplicationConfiguration();
        vkConfig.setTitle("Vulkan Test"); // Default title
        vkConfig.setWindowedMode(640, 480);
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
        TextButton lastClickedTestButton;
        Preferences prefs;
        private int cnt = 0;

        @Override
        public void create() {
            Gdx.app.log(TAG, "create() called. Hash: " + this.hashCode());
            prefs = Gdx.app.getPreferences("vulkan-tests");

            FileHandleResolver resolver = new InternalFileHandleResolver();

            assetManager = new AssetManager();
            assetManager.setLoader(VulkanTexture.class, new VulkanTextureLoader(resolver));
            assetManager.setLoader(TextureAtlas.class, new VulkanTextureAtlasLoader(resolver));

            String skinPath = "data/uiskin.json";
            try {
                Gdx.app.log(TAG, "Queueing skin for loading: " + skinPath);
                assetManager.load(skinPath, Skin.class);
                assetManager.finishLoading();
                skin = assetManager.get(skinPath, Skin.class);
                Gdx.app.log(TAG, "Skin retrieved from AssetManager.");
            } catch (Exception e) {
                if (assetManager != null) assetManager.dispose();
                throw new GdxRuntimeException("Failed to load uiskin via AssetManager", e);
            }

            Viewport viewport = new VulkanScreenViewport();
            stage = new VulkanStage(viewport);
            /*stage.addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                    int screenX = Gdx.input.getX(pointer); // Get raw screen X
                    int screenY = Gdx.input.getY(pointer); // Get raw screen Y
                    String firstTestName = GdxVulkanTests.getNames().get(0);
                    Gdx.app.log("StageInputDebug", "touchDown Listener Coords (Screen): (" + screenX + ", " + screenY + ") " + firstTestName);

                    // Unproject manually using the stage's viewport
                    Vector2 stageCoords = new Vector2();
                    // Ensure stage and its viewport are not null here for safety
                    if (stage != null && stage.getViewport() != null) {
                        stage.getViewport().unproject(stageCoords.set(screenX, screenY));
                        Gdx.app.log("StageInputDebug", "touchDown Unprojected Coords: (" + stageCoords.x + ", " + stageCoords.y + ")");

                        // Optionally, test hit detection directly here
                        TextButton testButton = stage.getRoot().findActor(firstTestName);
                        if (testButton != null) {
                            boolean hit = (testButton.hit(stageCoords.x, stageCoords.y, true) != null);
                            Gdx.app.log("StageInputDebug", "Hit test on '" + testButton.getName() + "' with unprojected coords: " + hit);
                        } else {
                            Gdx.app.log("StageInputDebug", "Could not find test button for hit test.");
                        }

                    } else {
                        Gdx.app.log("StageInputDebug", "Stage or Viewport is null, cannot unproject.");
                    }


                    Gdx.app.log("StageInputDebug", "touchDown Screen Coords: (" + screenX + ", " + screenY + ")");
                    Gdx.app.log("StageInputDebug", "touchDown Listener Coords (Stage): (" + x + ", " + y + ")");

                    return false;
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                    Gdx.app.log("StageInputDebug", "touchUp Stage Coords: (" + x + ", " + y + ")");
                }
            });*/

            if (Gdx.input != null) {
                Gdx.app.log("VulkanTestChooser", "Setting InputProcessor on Gdx.input instance hash: " + Gdx.input.hashCode());
                Gdx.input.setInputProcessor(stage);

                Gdx.app.log("VulkanTestChooser", "InputProcessor set.");
                InputProcessor processorAfterSet = Gdx.input.getInputProcessor();
                Gdx.app.log("VulkanTestChooser", "Processor is now: " + (processorAfterSet == null ? "NULL" : processorAfterSet.getClass().getName()) + " on instance hash: " + Gdx.input.hashCode());
            } else {
                Gdx.app.log("VulkanTestChooser", "Cannot set InputProcessor, Gdx.input is NULL!");
            }

            Label label = new Label("Vulkan Tests", skin);

            Table container = new Table();
            stage.addActor(container);
            container.setFillParent(true);
            container.add(label).pad(10).row();
            //stage.setDebugAll(true);
            Table table = new Table();

            //ScrollPane scroll = new ScrollPane(table, skin);
            //scroll.setSmoothScrolling(false);
            //scroll.setFadeScrollBars(false);
            //stage.setScrollFocus(scroll);

            int tableSpace = 4;
            table.pad(10).defaults().expandX().space(tableSpace);
            Gdx.app.log(TAG, "Adding " + GdxVulkanTests.getNames().size() + " tests to container...");
            for (final String testName : GdxVulkanTests.getNames()) {
                final TextButton testButton = new TextButton(testName, skin);
                //testButton.setDisabled(!options.isTestCompatible(testName));
                testButton.setName(testName);
                table.add(testButton).fillX();
                table.row();
                testButton.addListener(new ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, Actor actor) {
                        launchTest(testName);
                        prefs.putString("LastTest", testName);
                        prefs.flush();
                        if (testButton != lastClickedTestButton) {
                            testButton.setColor(Color.CYAN);
                            if (lastClickedTestButton != null) {
                                lastClickedTestButton.setColor(Color.WHITE);
                            }
                            lastClickedTestButton = testButton;
                        }
                    }
                });
            }
            //Gdx.app.log(TAG, "Adding scroll pane to container...");
            container.add(table).grow();
            //container.add(scroll).grow();
            container.row();

            lastClickedTestButton = table.findActor(prefs.getString("LastTest"));
            if (lastClickedTestButton != null) {
                lastClickedTestButton.setColor(Color.CYAN);
                //scroll.layout();
                //float scrollY = lastClickedTestButton.getY() + scroll.getScrollHeight() / 2 + lastClickedTestButton.getHeight() / 2 + tableSpace * 2 + 20;
                //scroll.scrollTo(0, scrollY, 0, 0, false, false);

                //stage.act(1f);
                //stage.act(1f);
                //stage.draw();
            }

            Gdx.app.log(TAG, "Chooser create() finished.");
        }

        private void launchTest(String testName) {
            ApplicationListener test = GdxVulkanTests.newTest(testName);

            VulkanWindowConfiguration winConfig = new VulkanWindowConfiguration();

            winConfig.setTitle(testName + " (Vulkan Test)");
            winConfig.setWindowedMode(1920, 1080);

            VulkanApplication app = (VulkanApplication) Gdx.app;
            VulkanWindow primaryWin = app.getCurrentWindow();
            if (primaryWin != null) {
                int currentX = primaryWin.getPositionX();
                int currentY = primaryWin.getPositionY();
                winConfig.setWindowPosition(currentX + 80, currentY + 80);
                Gdx.app.log("VulkanTestChooser", "Positioning new window relative to: " + currentX + "," + currentY);
            } else {
                Gdx.app.error("VulkanTestChooser", "Could not get primary/current window reference to position new window.");
            }

            winConfig.setPresentMode(VulkanApplicationConfiguration.SwapchainPresentMode.IMMEDIATE);
            Gdx.app.log("VulkanTestChooser", "Setting present mode for '" + testName + "' to: " + winConfig.getPresentMode());

            app.newWindow(new GdxTestWrapper(test, options.logGLErrors), winConfig);
            System.out.println("Started test: " + testName + " with Present Mode: " + winConfig.getPresentMode());
        }

        @Override
        public void render() {
            stage.act(Gdx.graphics.getDeltaTime());
            stage.draw();
            /*cnt++;
            if (cnt == 10) {
                launchTest("Scene2dTest");
            }
            if (cnt>30){
                VulkanApplication app = (VulkanApplication) Gdx.app;
                app.getCurrentWindow().closeWindow();
            }*/
        }

        @Override
        public void resize(int width, int height) {
            // Log the incoming logical width/height for comparison
            System.out.println("--- Scene2dTest resize(" + width + ", " + height + ") CALLED (Logical Dims?) ---");

            if (stage != null && stage.getViewport() != null) {
                // Get the PHYSICAL back buffer dimensions
                int backBufferWidth = Gdx.graphics.getBackBufferWidth();
                int backBufferHeight = Gdx.graphics.getBackBufferHeight();
                System.out.println("    Using Physical BackBuffer Dimensions: " + backBufferWidth + "x" + backBufferHeight);

                System.out.println("    Viewport BEFORE update: screen=" + stage.getViewport().getScreenWidth() + "x" + stage.getViewport().getScreenHeight() + ", world=" + stage.getViewport().getWorldWidth() + "x" + stage.getViewport().getWorldHeight());
                try {
                    // Update viewport using PHYSICAL dimensions
                    stage.getViewport().update(backBufferWidth, backBufferHeight, true); // centerCamera=true

                    System.out.println("    Viewport AFTER update: screen=" + stage.getViewport().getScreenWidth() + "x" + stage.getViewport().getScreenHeight() + ", world=" + stage.getViewport().getWorldWidth() + "x" + stage.getViewport().getWorldHeight());

                    // Optional Camera Logging
                    if (stage.getCamera() != null) {
                        // ... (your existing camera logging) ...
                        stage.getCamera().update(); // Ensure camera matrices are updated based on viewport
                    } else {
                        System.out.println("    Camera is NULL after viewport update!");
                    }

                } catch (Exception e) {
                    System.err.println("    ERROR during viewport.update: " + e.getMessage());
                    e.printStackTrace(); // Print stack trace for more detail
                }
            } else {
                System.out.println("    Stage or Viewport is NULL during resize!");
            }
//            Gdx.app.log(TAG, "Resizing to: " + width + "x" + height + (stage != null ? "Stage is not null" : "Stage is null"));
//            if (stage != null && stage.getViewport() != null) {
//                stage.getViewport().update(width, height, true);
//            }
        }

        @Override
        public void dispose() {
            VulkanApplication app = (VulkanApplication) Gdx.app;
            vkDeviceWaitIdle(app.getVulkanDevice().getRawDevice());
            Gdx.app.log(TAG, "dispose() called. Hash: " + this.hashCode());
            Gdx.app.log(TAG, "Disposing...");
            if (assetManager != null) {
                assetManager.dispose(); // Disposes skin, atlas, textures loaded by it
                assetManager = null;
                skin = null; // Nullify reference
                Gdx.app.log(TAG, "AssetManager disposed.");
            } else if (skin != null) {
                skin.dispose(); // Should not happen if AM was used, but safety check
                skin = null;
            }
            if (stage != null) {
                stage.dispose();
                stage = null;
            }

            Gdx.app.log(TAG, "Chooser disposed.");
        }
    }
}