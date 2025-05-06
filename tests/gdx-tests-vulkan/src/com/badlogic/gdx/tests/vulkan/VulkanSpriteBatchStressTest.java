package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backend.vulkan.VulkanSpriteBatch;
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.TimeUtils;
import java.text.DecimalFormat;
import java.util.Random;

public class VulkanSpriteBatchStressTest extends GdxTest {
    private static final String TAG = "VkStressTest";
    DecimalFormat df = new DecimalFormat("0.00");

    int SPRITES = 150000; // <<< High sprite count

    long lastLogTime = TimeUtils.nanoTime();
    int frames = 0;
    private OrthographicCamera camera;
    VulkanTexture texture;
    VulkanSpriteBatch spriteBatch;
    Sprite[] sprites; // Initialize in create
    Random random = new Random();

    @Override
    public void create() {
        Gdx.app.log(TAG, "[" + this.hashCode() + "] create() START");
        Gdx.app.log(TAG, "Testing with " + SPRITES + " sprites.");

        // Try creating batch *before* texture to catch potential issues
        try {
            spriteBatch = new VulkanSpriteBatch(SPRITES); // Size batch appropriately
        } catch (Throwable t) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Failed to create VulkanSpriteBatch!", t);
            throw t;
        }

        try {
            texture = new VulkanTexture(Gdx.files.internal("data/badlogicsmall.jpg"));
        } catch (Throwable t) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Failed to load texture!", t);
            if (spriteBatch != null) spriteBatch.dispose(); // Clean up if texture fails
            throw t;
        }

        camera = new OrthographicCamera();
        camera.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        Gdx.app.log(TAG, "[" + this.hashCode() + "] Camera created.");

        sprites = new Sprite[SPRITES];
        float width = texture.getWidth(); // Use texture dimensions
        float height = texture.getHeight();
        int screenW = Gdx.graphics.getWidth();
        int screenH = Gdx.graphics.getHeight();

        try {
            for (int i = 0; i < SPRITES; i++) {
                // Spread sprites across a larger area than the screen to test culling (if camera moves)
                float x = random.nextFloat() * screenW * 2f - screenW / 2f;
                float y = random.nextFloat() * screenH * 2f - screenH / 2f;
                sprites[i] = new Sprite(texture); // Use default size
                sprites[i].setPosition(x, y);
                // No rotation/scale needed for this stress test
            }
        } catch (Throwable t) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Error during sprite creation!", t);
            dispose(); // Clean up everything
            throw t;
        }
        Gdx.app.log(TAG, "[" + this.hashCode() + "] create() END");
    }

    @Override
    public void resize(int width, int height) {
        Gdx.app.log(TAG, "[" + this.hashCode() + "] resize(" + width + ", " + height + ")");
        if (camera != null) {
            camera.setToOrtho(false, width, height);
            camera.update(); // Apply changes immediately
        } else {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] resize() called but camera is NULL!");
        }
        if (spriteBatch != null) {
            spriteBatch.setProjectionMatrix(camera.combined); // Update projection matrix on resize
        }
    }

    @Override
    public void render() {
        // Optional: Allow camera movement to test view frustum culling interaction
        // handleInput(); // Implement basic camera movement if desired

        if (camera == null || spriteBatch == null) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] render() called but camera or spriteBatch is NULL!");
            return;
        }

        camera.update(); // Update camera if it moved
        spriteBatch.setProjectionMatrix(camera.combined); // Ensure matrix is set

        spriteBatch.begin();
        for (int i = 0; i < SPRITES; i++) {
            if (sprites[i] != null) { // Basic null check
                sprites[i].draw(spriteBatch);
            }
        }
        spriteBatch.end();

        frames++;
        long timeNow = TimeUtils.nanoTime();
        if (timeNow - lastLogTime > 1000000000) { // Log once per second
            float fps = frames / ((timeNow - lastLogTime) / 1000000000.0f);
            int renderCalls = spriteBatch.renderCalls; // Get calls *after* end()
            float avgSpritesPerCall = (renderCalls > 0) ? (float)SPRITES / renderCalls : SPRITES;

            Gdx.app.log(TAG, "fps: " + df.format(fps)
                    + ", render calls: " + renderCalls
                    + ", avg sprites/call: " + df.format(avgSpritesPerCall));
            frames = 0;
            lastLogTime = timeNow;
        }
    }

    // Optional: Basic camera movement
    private void handleInput() {
        float camSpeed = 200 * Gdx.graphics.getDeltaTime();
        if (Gdx.input.isKeyPressed(Input.Keys.LEFT)) camera.translate(-camSpeed, 0);
        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT)) camera.translate(camSpeed, 0);
        if (Gdx.input.isKeyPressed(Input.Keys.UP)) camera.translate(0, camSpeed);
        if (Gdx.input.isKeyPressed(Input.Keys.DOWN)) camera.translate(0, -camSpeed);
    }


    @Override
    public void dispose() {
        Gdx.app.log(TAG, "[" + this.hashCode() + "] dispose() called.");
        if (spriteBatch != null) {
            spriteBatch.dispose();
            spriteBatch = null;
        }
        if (texture != null) {
            texture.dispose();
            texture = null;
        }
        sprites = null; // Help GC
    }
}