package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
// --- Vulkan specific imports ---
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver;
import com.badlogic.gdx.backend.vulkan.VulkanApplication;
import com.badlogic.gdx.backend.vulkan.VulkanApplicationConfiguration;
import com.badlogic.gdx.backend.vulkan.VulkanDevice;
import com.badlogic.gdx.backend.vulkan.VulkanGraphics;
import com.badlogic.gdx.backend.vulkan.VulkanScreenViewport;
import com.badlogic.gdx.backend.vulkan.VulkanSpriteBatch;
import com.badlogic.gdx.backend.vulkan.VulkanStage;
// Might need VulkanWindowConfiguration if you create it
// import com.badlogic.gdx.backend.vulkan.VulkanWindowConfiguration;
// --- Scene2D imports ---
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.backend.vulkan.VulkanTextureAtlasLoader;
import com.badlogic.gdx.backend.vulkan.VulkanTextureLoader;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.scenes.scene2d.Actor;
// Use VulkanStage below
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.tests.utils.CommandLineOptions;
import com.badlogic.gdx.tests.utils.GdxTestWrapper;
import com.badlogic.gdx.tests.utils.GdxTests;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.viewport.Viewport;
// --- Vulkan specific for render ---
import org.lwjgl.vulkan.VkCommandBuffer; // If getting CB handle

public class VulkanTestStarter {
    static CommandLineOptions options;

    // ... (static block for natives) ...

    public static void main(String[] argv) {
        options = new CommandLineOptions(argv);
        VulkanApplicationConfiguration vkConfig = new VulkanApplicationConfiguration();
        // Set default window config if needed (can be overridden)
        vkConfig.setTitle("Vulkan Test Selector");
        vkConfig.setWindowedMode(640, 480);
        // Add any other default Vulkan config here

        if (options.startupTestName != null) {
            ApplicationListener test = GdxTests.newTest(options.startupTestName);
            if (test != null) {
                vkConfig.setTitle(options.startupTestName + " (Vulkan)"); // Update title
                // Wrap test, disable GL error logging for Vulkan
                new VulkanApplication(new GdxTestWrapper(test, false), vkConfig);
            } else {
                System.err.println("ERROR: Test specified via argument not found: " + options.startupTestName);
                System.exit(1);
            }
        } else {
            // *** LAUNCH TEST CHOOSER INSTEAD OF EXITING ***
            new VulkanApplication(new VulkanTestChooser(), vkConfig);
        }
    }

    // --- INNER CLASS ---
    static class VulkanTestChooser extends ApplicationAdapter {
        private VulkanStage stage;
        private Skin skin;
        private VulkanSpriteBatch batch;
        TextButton lastClickedTestButton;
        private AssetManager assetManager; // Add AssetManager field

        @Override
        public void create() {
            // 1. Create VulkanSpriteBatch (Keep this, maybe needs AssetManager later?)
            try {
                batch = new VulkanSpriteBatch();
            } catch (Exception e) {
                throw new GdxRuntimeException("Failed to create VulkanSpriteBatch for TestChooser", e);
            }

            // 2. Setup AssetManager and Loaders
            assetManager = new AssetManager();
            FileHandleResolver resolver = new InternalFileHandleResolver();
            // Get device/allocator needed by VulkanTextureLoader
            VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
            VulkanDevice device = gfx.getVulkanDevice();
            long vmaAllocator = gfx.getVmaAllocator();
            if (device == null || vmaAllocator == 0) {
                throw new GdxRuntimeException("Cannot set up AssetManager, VulkanDevice or VMA Allocator not available from Graphics.");
            }

            // Register your custom loaders
            assetManager.setLoader(VulkanTexture.class, new VulkanTextureLoader(resolver, device, vmaAllocator));
            assetManager.setLoader(TextureAtlas.class, new VulkanTextureAtlasLoader(resolver));
            // The default SkinLoader should work now as it will use the registered TextureAtlasLoader

            // 3. Load Skin via AssetManager
            String skinPath = "data/uiskin.json"; // Ensure this path is correct
            try {
                Gdx.app.log("VulkanTestChooser", "Queueing skin for loading: " + skinPath);
                assetManager.load(skinPath, Skin.class);

                Gdx.app.log("VulkanTestChooser", "Loading assets...");
                assetManager.finishLoading(); // Block until all assets (textures, atlas, skin) are loaded
                Gdx.app.log("VulkanTestChooser", "Assets finished loading.");

                skin = assetManager.get(skinPath, Skin.class);
                Gdx.app.log("VulkanTestChooser", "Skin retrieved from AssetManager.");

            } catch (Exception e) {
                if (assetManager != null) assetManager.dispose();
                throw new GdxRuntimeException("Failed to load uiskin via AssetManager", e);
            }


            // 4. Create Viewport and Stage
            Viewport viewport = new VulkanScreenViewport(); // Use the Vulkan-safe viewport
            stage = new VulkanStage(viewport, batch);

            // 5. Set Input Processor
            Gdx.input.setInputProcessor(stage);

            // 5. Build Scene2D UI (Same logic as Lwjgl3TestStarter)
            final Preferences prefs = Gdx.app.getPreferences("vulkan-tests"); // Use different pref file name

            Table container = new Table();
            stage.addActor(container);
            container.setFillParent(true);

            Table table = new Table();
            ScrollPane scroll = new ScrollPane(table, skin);
            scroll.setSmoothScrolling(false); // Smoother scrolling might be desired later
            scroll.setFadeScrollBars(false);
            stage.setScrollFocus(scroll);

            int tableSpace = 4;
            table.pad(10).defaults().expandX().space(tableSpace);

            for (final String testName : GdxTests.getNames()) {
                final TextButton testButton = new TextButton(testName, skin);
                // TODO: Implement Vulkan compatibility check if needed
                // testButton.setDisabled(!options.isTestCompatible(testName));
                testButton.setName(testName);
                table.add(testButton).fillX();
                table.row();
                testButton.addListener(new ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, Actor actor) {
                        ApplicationListener test = GdxTests.newTest(testName);
                        if (test == null) {
                            System.err.println("Failed to create test: " + testName);
                            return;
                        }

                        // Configuration for the new window launching the test
                        // TODO: Create VulkanWindowConfiguration class if needed for more options
                        // For now, use main config object and modify it.
                        VulkanApplicationConfiguration testVkConfig = new VulkanApplicationConfiguration();
                        testVkConfig.setTitle(testName + " (Vulkan)");
                        testVkConfig.setWindowedMode(640, 480);
                        // Position relative to chooser window?
                        if (Gdx.graphics instanceof VulkanGraphics) {
                            // This positioning logic might need adjustment based on how Vulkan window handles work
                            // VulkanWindow window = ((VulkanGraphics)Gdx.graphics).getWindow();
                            // testVkConfig.setWindowPosition(window.getPositionX() + 40, window.getPositionY() + 40);
                            // For now, let OS position it or use defaults
                        }
                        testVkConfig.useVsync(false); // Typically disable vsync for tests

                        // Launch test in new window (VulkanApplication must support this)
                        try {
                            // Wrap test, disable GL error logging for Vulkan
                            GdxTestWrapper wrapper = new GdxTestWrapper(test, false);
                            ((VulkanApplication) Gdx.app).newWindow(wrapper, testVkConfig);
                            System.out.println("Started test: " + testName);
                            prefs.putString("LastTest", testName);
                            prefs.flush();

                            // Update button colors
                            if (testButton != lastClickedTestButton) {
                                testButton.setColor(Color.CYAN);
                                if (lastClickedTestButton != null) {
                                    lastClickedTestButton.setColor(Color.WHITE);
                                }
                                lastClickedTestButton = testButton;
                            }
                        } catch (Exception e) {
                            System.err.println("Failed to launch test '" + testName + "' in new window.");
                            e.printStackTrace();
                        }

                    }
                });
            }

            container.add(scroll).expand().fill(); // Use expand().fill() for better layout

            // 6. Restore Scroll Position / Highlight
            lastClickedTestButton = (TextButton) table.findActor(prefs.getString("LastTest"));
            if (lastClickedTestButton != null) {
                lastClickedTestButton.setColor(Color.CYAN);
                // Scroll calculation might need slight adjustment
                scroll.layout(); // Ensure layout is done before calculating scroll pos
                float scrollY = lastClickedTestButton.getY() + scroll.getHeight() / 2f - lastClickedTestButton.getHeight() / 2f;
                // Clamp scrollY to valid range
                scrollY = Math.max(0, Math.min(scroll.getMaxY(), scrollY));
                scroll.setScrollY(scrollY);

                // Force update/draw to reflect scroll change immediately? Maybe not needed.
                // stage.act(0.01f); stage.draw();
            }
        }

        @Override
        public void render() {
            // Render pass begin/end and screen clear are handled by VulkanGraphics.drawFrame

            // Get Vulkan context (Command Buffer)
            VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
            VkCommandBuffer cmdBuf = gfx.getCurrentCommandBuffer();

            // If frame is not ready (e.g., swapchain recreating), cmdBuf might be null
            if (cmdBuf == null) {
                // Optionally log? Should happen rarely if beginFrame handles returns correctly
                // Gdx.app.debug("VulkanTestChooser", "Skipping render, command buffer not available.");
                return;
            }

            // Update Scene2D
            stage.act(Gdx.graphics.getDeltaTime()); // Use actual delta time

            // Draw Scene2D (Manage batch begin/end here)
            batch.begin(cmdBuf);
            stage.draw();
            batch.end();
        }

        @Override
        public void resize(int width, int height) {
            // Ensure stage and viewport exist before updating
            if (stage != null && stage.getViewport() != null) {
                stage.getViewport().update(width, height, true);
            }
        }

        @Override
        public void dispose() {
            Gdx.app.log("VulkanTestChooser", "Disposing...");

            // Dispose AssetManager FIRST - this should dispose assets it loaded (skin, atlas, textures)
            if (assetManager != null) {
                assetManager.dispose();
                assetManager = null;
                Gdx.app.log("VulkanTestChooser", "AssetManager disposed.");
            } else {
                // If asset manager wasn't used, dispose skin manually
                if (skin != null) {
                    skin.dispose();
                    skin = null;
                }
            }

            // Stage dispose calls dispose() on actors, listeners etc. but NOT the batch
            if (stage != null) {
                stage.dispose();
                stage = null;
            }
            // Dispose the batch owned by this chooser
            if (batch != null) {
                batch.dispose();
                batch = null;
            }
            Gdx.app.log("VulkanTestChooser", "Chooser disposed.");
        }
    }
}