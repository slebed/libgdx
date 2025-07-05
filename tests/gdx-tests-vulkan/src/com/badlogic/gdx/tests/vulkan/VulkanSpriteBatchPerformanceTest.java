package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input; // Added for potential exit key
import com.badlogic.gdx.backend.vulkan.VulkanGraphics;
import com.badlogic.gdx.backend.vulkan.VulkanSpriteBatch;
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
// BitmapFont will likely NOT work out-of-the-box with VulkanSpriteBatch
// unless its texture is loaded as a VulkanTexture. Commenting out for now.
// import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion; // Useful for drawing specific parts
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.TimeUtils; // For performance timing
import com.badlogic.gdx.utils.StringBuilder; // Keep if needed for logging
import org.lwjgl.vulkan.VkCommandBuffer; // Import Vulkan command buffer

public class VulkanSpriteBatchPerformanceTest extends GdxTest {

    private VulkanTexture texture;
    private TextureRegion textureRegion; // Often useful with SpriteBatch
    private VulkanSpriteBatch spriteBatch;
    // private BitmapFont bitmapFont; // Needs Vulkan-compatible texture handling
    private com.badlogic.gdx.utils.StringBuilder stringBuilder = new StringBuilder();

    // Performance tracking
    private long lastLogTime = 0;
    private int frameCount = 0;
    private float accumulatedTime = 0; // Time for drawing operations within a frame/batch
    private int drawCallsInFrame = 0;
    private int spritesInFrame = 0;

    final int SPRITES_PER_BATCH = 8190; // Max sprites before flush (adjust based on VulkanSpriteBatch size)
    final int BATCHES_PER_FRAME = 10;   // How many full batches to draw per frame

    @Override
    public void create() {
        // Load texture using VulkanTexture loader (implicitly handled by AssetManager usually,
        // but direct creation works too if AssetManager isn't set up for VulkanTexture)
        // Ensure the Vulkan backend is initialized before this point.
        try {
            // You might need to ensure VulkanDevice and VMA Allocator are available
            // if creating textures directly AFTER the VulkanApplication starts.
            // Here, we assume it's okay within the create() method of the ApplicationListener.
            VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics; // Should be safe here
            if (gfx == null || gfx.getVulkanDevice() == null || gfx.getVmaAllocator() == 0) {
                throw new IllegalStateException("Vulkan backend not ready for texture creation.");
            }
            // NOTE: Direct creation like this bypasses AssetManager. If you use AssetManager
            // elsewhere, make sure VulkanTextureLoader is registered first.
            texture = new VulkanTexture(Gdx.files.internal("data/sys.png"));//badlogic.jpg"));//, gfx.getVulkanDevice(), gfx.getVmaAllocator());
            textureRegion = new TextureRegion(texture); // Use TextureRegion
        } catch (Exception e) {
            Gdx.app.error("VulkanSpriteTest", "Failed to load VulkanTexture", e);
            // Handle error appropriately - maybe exit or use a placeholder?
            Gdx.app.exit();
            return; // Prevent further execution if texture fails
        }


        // Create the VulkanSpriteBatch
        // The size (8191 vertices) determines max sprites between flushes.
        // Each sprite needs 4 vertices. Max sprites = (size - 1) / 4 = 8190 / 4 = 2047.5 -> 2047?
        // Let's stick to the original size, but be aware of the vertex limit.
        // The size is actually the number of *indices* (6 per quad) or *vertices* (4 per quad).
        // If size is 8191, it's likely vertices. 8191 / 4 = ~2047 sprites.
        // Let's assume the original test's 8190 was sprite count, needing 8190*4 vertices.
        // Create batch large enough for SPRITES_PER_BATCH:
        int batchVertexSize = SPRITES_PER_BATCH * 4 + 4; // Need enough vertices
        spriteBatch = new VulkanSpriteBatch(SPRITES_PER_BATCH); // Adjust size as needed

        // bitmapFont = new BitmapFont(); // Standard font likely uses Texture, not VulkanTexture.
        // Loading fonts that work with VulkanSpriteBatch requires ensuring their
        // texture pages are VulkanTextures, typically via AssetManager setup.

        Gdx.app.log("VulkanSpriteTest", "Test created. Drawing " + BATCHES_PER_FRAME + " batches of " + SPRITES_PER_BATCH + " sprites per frame.");
        Gdx.app.log("VulkanSpriteTest", "Press ESC to exit.");

        lastLogTime = TimeUtils.nanoTime();
    }

    @Override
    public void dispose() {
        Gdx.app.log("VulkanSpriteTest", "Disposing...");
        if (spriteBatch != null) spriteBatch.dispose();
        if (texture != null) texture.dispose();
        // if (bitmapFont != null) bitmapFont.dispose();
    }

    @Override
    public void render() {
        // Exit check
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            Gdx.app.exit();
        }

        // Clearing the screen is typically handled by the Vulkan backend before calling render()
        // based on VulkanApplicationConfiguration settings or defaults.

        // Get the current command buffer for this frame
        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
        VkCommandBuffer cmdBuf = gfx.getCurrentCommandBuffer();
        if (cmdBuf == null) {
            Gdx.app.error("VulkanSpriteTest", "Failed to get current command buffer!");
            return; // Skip rendering if buffer isn't ready
        }

        accumulatedTime = 0;
        drawCallsInFrame = 0;
        spritesInFrame = 0;
        long frameStartTime = System.nanoTime(); // Use System.nanoTime for timings

        // Begin the sprite batch for the frame
        spriteBatch.begin(); // Pass the command buffer

        long batchTimingStart = System.nanoTime();
        for (int b = 0; b < BATCHES_PER_FRAME; b++) {
            for (int i = 0; i < SPRITES_PER_BATCH; i++) {
                // Draw sprites. Vary position slightly to avoid z-fighting if needed,
                // though less relevant for 2D ortho. Simple draw:
                spriteBatch.draw(textureRegion,
                        (i % 100) * 6, // Example positioning - vary as needed
                        (i / 100) * 6,
                        textureRegion.getRegionWidth() * 0.1f, // Example scaling
                        textureRegion.getRegionHeight() * 0.1f);
                spritesInFrame++;
            }
            // Flush the batch explicitly after drawing SPRITES_PER_BATCH
            // This submits the draw commands recorded so far for this batch.
            spriteBatch.flush(); // This is where the actual draw call happens in OpenGL terms
            drawCallsInFrame++;
        }
        accumulatedTime += (System.nanoTime() - batchTimingStart); // Time spent in draw+flush loops

        spriteBatch.end(); // Finalize batch for the frame (might do a final flush if needed)

        // Performance Logging
        frameCount++;
        long currentTime = TimeUtils.nanoTime();
        if (currentTime - lastLogTime > 1000000000) { // Log every second
            float elapsedSeconds = (currentTime - lastLogTime) / 1000000000.0f;
            float fps = frameCount / elapsedSeconds;
            float avgDrawTimeMs = (accumulatedTime / 1000000.0f) / drawCallsInFrame; // Avg time per flush
            float avgFrameTimeMs = (TimeUtils.nanosToMillis(currentTime - lastLogTime)) / (float)frameCount;


            stringBuilder.setLength(0);
            stringBuilder.append("FPS: ").append((int)fps);
            stringBuilder.append(" | FrameTime(ms): ").append(String.format("%.2f", avgFrameTimeMs));
            stringBuilder.append(" | Sprites: ").append(spritesInFrame);
            stringBuilder.append(" | Flushes: ").append(drawCallsInFrame);
            // stringBuilder.append(" | Avg Flush Time(ms): ").append(String.format("%.4f", avgDrawTimeMs)); // Can be noisy
            Gdx.app.log("Perf", stringBuilder.toString());


            // --- Text Rendering (Requires Vulkan-compatible font) ---
            /*
            if (bitmapFont != null) {
                // Need a separate batch or ensure the main batch is ended before UI
                // For simplicity, could use a separate VulkanSpriteBatch for UI if needed.
                // Or render text after the main batch.end()
                spriteBatch.begin(cmdBuf); // Restart or use a UI batch
                bitmapFont.draw(spriteBatch, stringBuilder, 10, Gdx.graphics.getHeight() - 20);
                spriteBatch.end();
            }
            */

            frameCount = 0;
            lastLogTime = currentTime;
        }
    }

    @Override
    public void resize(int width, int height) {
        Gdx.app.log("VulkanSpriteTest", "resize() called with: " + width + "x" + height); // Check if called
        if (spriteBatch != null) {
            spriteBatch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
            Gdx.app.log("VulkanSpriteTest", "Projection matrix after setToOrtho2D:\n" + spriteBatch.getProjectionMatrix());
        }
    }
}