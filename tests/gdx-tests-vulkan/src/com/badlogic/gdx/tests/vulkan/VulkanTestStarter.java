
package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

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

import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.backend.vulkan.VulkanTextureAtlasLoader;
import com.badlogic.gdx.backend.vulkan.VulkanTextureLoader;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.tests.utils.CommandLineOptions;
import com.badlogic.gdx.tests.utils.GdxTestWrapper;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.viewport.Viewport;

import org.lwjgl.vulkan.VkCommandBuffer;

public class VulkanTestStarter {
	static CommandLineOptions options;

	public static void main (String[] argv) {
		options = new CommandLineOptions(argv);
		VulkanApplicationConfiguration vkConfig = new VulkanApplicationConfiguration();
		vkConfig.setTitle("Vulkan Test"); // Generic title, will be updated
		vkConfig.setWindowedMode(640, 480);
		// vkConfig.setDebugMode(true); // Enable Vulkan validation layers for debugging

		String testName = options.startupTestName;
		ApplicationListener testInstance = null;

		if (testName != null) {
			System.out.println("Attempting to launch Vulkan test from argument: " + testName);
			// *** Use GdxVulkanTests ***
			testInstance = GdxVulkanTests.newTest(testName);
			if (testInstance == null) {
				System.err.println("ERROR: Vulkan test specified via argument not found: " + testName);
				// *** Refer to GdxVulkanTests ***
				System.err.println("Available Vulkan tests: " + GdxVulkanTests.getNames());
				System.exit(1);
			}
		} else {
			// Launch VulkanSpriteTest directly by default
			testName = "VulkanSpriteTest"; // Specify the test to launch by default
			System.out.println("No test specified, launching default Vulkan test: " + testName);
			// *** Use GdxVulkanTests ***
			testInstance = GdxVulkanTests.newTest(testName);
			if (testInstance == null) {
				System.err.println("ERROR: Default Vulkan test '" + testName + "' not found!");
				// *** Refer to GdxVulkanTests ***
				System.err.println("Ensure it's registered in GdxVulkanTests.java");
				System.exit(1);
			}
		}

		// --- Launch the selected test ---
		if (testInstance != null) {
			vkConfig.setTitle(testName + " (Vulkan)"); // Update title

			// Wrap test, disable GL error logging for Vulkan
			// GdxTestWrapper should still work fine as it just wraps ApplicationListener
			GdxTestWrapper wrapper = new GdxTestWrapper(testInstance, false);
			System.out.println("Launching Vulkan test: " + testName);
			new VulkanApplication(wrapper, vkConfig);
		} else {
			System.err.println("ERROR: Could not create Vulkan test instance for: " + testName);
			System.exit(1);
		}
	}

	static class VulkanTestChooser extends ApplicationAdapter {
		private final String TAG = "VulkanTestChooser";
		private VulkanStage stage;
		private Skin skin;
		private VulkanSpriteBatch batch;
		// TextButton lastClickedTestButton;
		private AssetManager assetManager;
		private boolean runOnce = true;

		@Override
		public void create () {
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
			VulkanGraphics gfx = (VulkanGraphics)Gdx.graphics;
			VulkanDevice device = gfx.getVulkanDevice();
			long vmaAllocator = gfx.getVmaAllocator();
			if (device == null || vmaAllocator == 0) {
				throw new GdxRuntimeException(
					"Cannot set up AssetManager, VulkanDevice or VMA Allocator not available from Graphics.");
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

			// Table container = new Table();
			// stage.addActor(container);
			// container.setFillParent(true);

			/*
			 * Table table = new Table(); ScrollPane scroll = new ScrollPane(table, skin); scroll.setSmoothScrolling(false); //
			 * Smoother scrolling might be desired later scroll.setFadeScrollBars(false); stage.setScrollFocus(scroll);
			 * Gdx.app.log(TAG, "setting scroll focus"); int tableSpace = 4; table.pad(10).defaults().expandX().space(tableSpace);
			 * for (final String testName : GdxTests.getNames()) { final TextButton testButton = new TextButton(testName, skin); //
			 * TODO: Implement Vulkan compatibility check if needed // testButton.setDisabled(!options.isTestCompatible(testName));
			 * testButton.setName(testName); table.add(testButton).fillX(); table.row(); testButton.addListener(new ChangeListener()
			 * {
			 * 
			 * @Override public void changed(ChangeEvent event, Actor actor) { ApplicationListener test = GdxTests.newTest(testName);
			 * if (test == null) { System.err.println("Failed to create test: " + testName); return; }
			 * 
			 * // Configuration for the new window launching the test // TODO: Create VulkanWindowConfiguration class if needed for
			 * more options // For now, use main config object and modify it. VulkanApplicationConfiguration testVkConfig = new
			 * VulkanApplicationConfiguration(); testVkConfig.setTitle(testName + " (Vulkan)"); testVkConfig.setWindowedMode(640,
			 * 480); // Position relative to chooser window? if (Gdx.graphics instanceof VulkanGraphics) { // This positioning logic
			 * might need adjustment based on how Vulkan window handles work // VulkanWindow window =
			 * ((VulkanGraphics)Gdx.graphics).getWindow(); // testVkConfig.setWindowPosition(window.getPositionX() + 40,
			 * window.getPositionY() + 40); // For now, let OS position it or use defaults } testVkConfig.useVsync(false); //
			 * Typically disable vsync for tests
			 * 
			 * // Launch test in new window (VulkanApplication must support this) try { // Wrap test, disable GL error logging for
			 * Vulkan GdxTestWrapper wrapper = new GdxTestWrapper(test, false); ((VulkanApplication) Gdx.app).newWindow(wrapper,
			 * testVkConfig); System.out.println("Started test: " + testName); prefs.putString("LastTest", testName); prefs.flush();
			 * 
			 * // Update button colors if (testButton != lastClickedTestButton) { testButton.setColor(Color.CYAN); if
			 * (lastClickedTestButton != null) { lastClickedTestButton.setColor(Color.WHITE); } lastClickedTestButton = testButton; }
			 * } catch (Exception e) { System.err.println("Failed to launch test '" + testName + "' in new window.");
			 * e.printStackTrace(); }
			 * 
			 * } }); }
			 */

			// container.add(scroll).expand().fill(); // Use expand().fill() for better layout

			// 6. Restore Scroll Position / Highlight
			/*
			 * lastClickedTestButton = (TextButton) table.findActor(prefs.getString("LastTest")); if (lastClickedTestButton != null)
			 * { lastClickedTestButton.setColor(Color.CYAN); // Scroll calculation might need slight adjustment scroll.layout(); //
			 * Ensure layout is done before calculating scroll pos float scrollY = lastClickedTestButton.getY() + scroll.getHeight()
			 * / 2f - lastClickedTestButton.getHeight() / 2f; // Clamp scrollY to valid range scrollY = Math.max(0,
			 * Math.min(scroll.getMaxY(), scrollY)); scroll.setScrollY(scrollY);
			 * 
			 * // Force update/draw to reflect scroll change immediately? Maybe not needed. // stage.act(0.01f); stage.draw(); }
			 */

			Label label = new Label("This is a LABEL", skin);
			label.setPosition(20, 50);
			label.pack();

			TextButton textButton = new TextButton("This is a TEXTBUTTON", skin);
			textButton.setPosition(20, 20);
			textButton.addListener(new ClickListener() {
				@Override
				public boolean touchDown (InputEvent event, float x, float y, int pointer, int button) {
					Gdx.app.log("ButtonClick", "Button Touch Down!");
					// Return true tells the input system the event was handled here,
					// which is needed for clicked() to fire reliably in ClickListener.
					return true;
				}

				@Override
				public void touchUp (InputEvent event, float x, float y, int pointer, int button) {
					Gdx.app.log("ButtonClick", "Button Touch Up!");
					super.touchUp(event, x, y, pointer, button);
				}

				@Override
				public void clicked (InputEvent event, float x, float y) {
					Gdx.app.log("ButtonClick", "Button Clicked!");
					// --- Add your actual button logic here ---
				}
			});
			textButton.pack();

			// stage.addActor(label);
			stage.addActor(textButton);

			Gdx.app.log("ActorBounds", String.format("Label Pos=(%.1f, %.1f) Size=(%.1f, %.1f) Top=%.1f", label.getX(), label.getY(),
				label.getWidth(), label.getHeight(), label.getTop()));

			Gdx.app.log("ActorBounds", String.format("TextButton Pos=(%.1f, %.1f) Size=(%.1f, %.1f) Top=%.1f", textButton.getX(),
				textButton.getY(), textButton.getWidth(), textButton.getHeight(), textButton.getTop()));

			// container.pack();
			// stage.addActor(container);

		}

		@Override
		public void render () {
			VulkanGraphics gfx = (VulkanGraphics)Gdx.graphics;
			VkCommandBuffer cmdBuf = gfx.getCurrentCommandBuffer();
			if (cmdBuf == null) {
				Gdx.app.error("VulkanTestChooser", "Failed to get current command buffer!");
				return;
			}

			// --- BEGIN DIAGNOSTIC CODE ---
			// batch.begin(cmdBuf);

			/*
			 * NinePatch patch = null; try { patch = skin.getPatch("default-round"); // Get the specific patch uiskin uses } catch
			 * (Exception e) { Gdx.app.error("DEBUG", "Failed to get patch 'default-round'", e); System.exit(0); } //Gdx.app.log(TAG,
			 * "patch is " + patch); if (patch != null) { //Gdx.app.log("DEBUG", "NinePatch 'default-round' found in skin: " +
			 * patch); // *** CHECK COLOR IMMEDIATELY *** if (patch.getColor() == null) { Gdx.app.error("DEBUG",
			 * "NinePatch 'default-round' color IS NULL after getting from skin!"); // Try setting it explicitly
			 * patch.setColor(Color.WHITE); Gdx.app.log("DEBUG", "Set patch color to WHITE explicitly."); } else { //
			 * Gdx.app.log("DEBUG", "NinePatch 'default-round' color is OK: " + patch.getColor()); } patch.setColor(Color.WHITE); //
			 * Set it regardless // Now try drawing it try { patch.draw(batch, 50, 50, 200, 200); // Draw test patch
			 * Gdx.app.log("DEBUG", "Successfully drew test patch."); } catch(Exception e) { Gdx.app.error("DEBUG",
			 * "Crash occurred during patch.draw()", e); System.exit(0); }
			 * 
			 * } else { Gdx.app.error("DEBUG", "Could not find patch 'default-round' in skin!"); System.exit(0); }
			 */
			// batch.end(); // End batch for diagnostic draw
			// --- END DIAGNOSTIC CODE ---

			stage.act(Gdx.graphics.getDeltaTime());
			batch.begin(cmdBuf);
			stage.draw(); // This might still crash if ScrollPane gets a different patch?
			batch.end();

			if (Gdx.input.justTouched()) {
				Vector2 screenCoords = new Vector2(Gdx.input.getX(), Gdx.input.getY());
				Vector2 stageCoords = new Vector2(screenCoords);
				stage.screenToStageCoordinates(stageCoords); // Unproject
				Gdx.app.log("Coords", "Screen: " + screenCoords + " -> Stage: " + stageCoords);

				Actor hitActor = stage.hit(stageCoords.x, stageCoords.y, true); // true = only touchable actors
				Gdx.app.log("Coords", "Hit Actor: " + (hitActor == null ? "None" : hitActor.toString()));
			}

		}

		@Override
		public void resize (int width, int height) {
			// Ensure stage and viewport exist before updating
			if (stage != null && stage.getViewport() != null) {
				stage.getViewport().update(width, height, true);
			}
		}

		@Override
		public void dispose () {
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
