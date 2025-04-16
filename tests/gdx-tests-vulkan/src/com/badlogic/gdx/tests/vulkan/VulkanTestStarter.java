package com.badlogic.gdx.tests.vulkan;

import static com.badlogic.gdx.scenes.scene2d.ui.Table.Debug.table;

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
import com.badlogic.gdx.backend.vulkan.VulkanDevice;
import com.badlogic.gdx.backend.vulkan.VulkanGraphics;
import com.badlogic.gdx.backend.vulkan.VulkanScreenViewport;

import com.badlogic.gdx.backend.vulkan.VulkanStage;
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.backend.vulkan.VulkanTextureAtlasLoader;
import com.badlogic.gdx.backend.vulkan.VulkanTextureLoader;
import com.badlogic.gdx.backend.vulkan.VulkanWindow;
import com.badlogic.gdx.backend.vulkan.VulkanWindowConfiguration;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
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

        // vkConfig.setVSync(true); // Example

        ApplicationListener listenerToStart = null;
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
                // Optionally list available tests if GdxVulkanTests provides a way
                // System.err.println("Available Vulkan tests: " + GdxVulkanTests.getNames());
                System.err.println("Falling back to Test Chooser.");
                // listenerToStart remains null, chooser will be launched
            }
        }

        if (options.startupTestName != null) {
            ApplicationListener test = GdxVulkanTests.newTest(options.startupTestName);
            if (test != null) {
                new VulkanApplication(test, vkConfig);
                return;
            }
            // Otherwise, fall back to showing the list
        }
        new VulkanApplication(new VulkanTestChooser(), vkConfig);
    }

    static class VulkanTestChooser extends ApplicationAdapter {
        private final String TAG = "VulkanTestChooser";
        private VulkanStage stage;
        private Skin skin;
        private AssetManager assetManager;
        TextButton lastClickedTestButton;

        @Override
        public void create() {
            final Preferences prefs = Gdx.app.getPreferences("vulkan-tests");

            assetManager = new AssetManager();
            FileHandleResolver resolver = new InternalFileHandleResolver();
            VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
            VulkanDevice device = gfx.getVulkanDevice();
            long vmaAllocator = gfx.getVmaAllocator();
            if (device == null || vmaAllocator == 0) {
                throw new GdxRuntimeException("Cannot set up AssetManager, VulkanDevice or VMA Allocator not available from Graphics.");
            }
            assetManager.setLoader(VulkanTexture.class, new VulkanTextureLoader(resolver, device, vmaAllocator));
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
            stage.addListener(new InputListener() {
                @Override
                public boolean touchDown (InputEvent event, float x, float y, int pointer, int button) {
                    int screenX = Gdx.input.getX(pointer); // Get raw screen X
                    int screenY = Gdx.input.getY(pointer); // Get raw screen Y
                    String firstTestName = GdxVulkanTests.getNames().get(0);
                    Gdx.app.log("StageInputDebug", "touchDown Listener Coords (Screen): (" + screenX + ", " + screenY + ") "+ firstTestName);

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

                    return false; // Let event propagate
                }
                @Override
                public void touchUp (InputEvent event, float x, float y, int pointer, int button) {
                    Gdx.app.log("StageInputDebug", "touchUp Stage Coords: (" + x + ", " + y + ")");
                }
            });

            // Inside VulkanTestChooser.create()
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
                        ApplicationListener test = GdxVulkanTests.newTest(testName);
                        VulkanWindowConfiguration winConfig = new VulkanWindowConfiguration();
                        winConfig.setTitle(testName);
                        winConfig.setWindowedMode(640, 480);

                        VulkanApplication app = (VulkanApplication) Gdx.app;
                        VulkanWindow primaryWin = app.getCurrentWindow();

                        if (primaryWin != null) {
                            // Get position from the VulkanWindow instance
                            int currentX = primaryWin.getPositionX();
                            int currentY = primaryWin.getPositionY();
                            winConfig.setWindowPosition(currentX + 40, currentY + 40);
                            Gdx.app.log("VulkanTestChooser", "Positioning new window relative to: " + currentX + "," + currentY);
                        } else {
                            Gdx.app.error("VulkanTestChooser", "Could not get primary window reference to position new window.");
                        }

                        winConfig.useVsync(false);
                        ((VulkanApplication) Gdx.app).newWindow(new GdxTestWrapper(test, options.logGLErrors), winConfig);
                        System.out.println("Started test: " + testName);
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

        @Override
        public void render() {
            stage.act(Gdx.graphics.getDeltaTime());
            stage.draw();
        }

        @Override
        public void resize(int width, int height) {
            if (stage != null && stage.getViewport() != null) {
                stage.getViewport().update(width, height, true);
            }
        }

        @Override
        public void dispose() {
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