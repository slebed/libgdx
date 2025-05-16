package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backend.vulkan.VulkanSpriteBatch; // Regular batch
import com.badlogic.gdx.backend.vulkan.VulkanSpriteBatchInstanced; // Instanced batch
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.Batch; // Common interface
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.TimeUtils;
import java.text.DecimalFormat;
import java.util.Random;

public class VulkanSpriteBatchStressTest extends GdxTest implements InputProcessor {
    private static final String TAG = "VkStressTestSwitch";
    DecimalFormat df = new DecimalFormat("0.00");

    int SPRITES = 50000; // Adjusted for potentially more demanding instanced setup initially, can be increased

    long lastLogTime = TimeUtils.nanoTime();
    int frames = 0;
    private OrthographicCamera camera;
    VulkanTexture texture;

    // Batcher instances
    VulkanSpriteBatch spriteBatchRegular;
    VulkanSpriteBatchInstanced spriteBatchInstanced;
    Batch activeBatch; // The currently used batcher, typed to the common interface
    boolean useInstancedBatcher = false; // Start with regular batcher

    Sprite[] sprites;
    Random random = new Random();

    @Override
    public void create() {
        Gdx.app.log(TAG, "[" + this.hashCode() + "] create() START");
        Gdx.app.log(TAG, "Testing with " + SPRITES + " sprites. Press SPACE to switch batcher.");

        Gdx.input.setInputProcessor(this); // Set input processor to this class

        try {
            texture = new VulkanTexture(Gdx.files.internal("data/badlogicsmall.jpg"));
        } catch (Throwable t) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Failed to load texture!", t);
            throw t;
        }

        try {
            Gdx.app.log(TAG, "Creating Regular VulkanSpriteBatch...");
            spriteBatchRegular = new VulkanSpriteBatch(SPRITES);
            Gdx.app.log(TAG, "Creating Instanced VulkanSpriteBatchInstanced...");
            spriteBatchInstanced = new VulkanSpriteBatchInstanced(SPRITES);
        } catch (Throwable t) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Failed to create one or both SpriteBatch instances!", t);
            if (texture != null) texture.dispose();
            throw t;
        }

        // Set initial active batch
        activeBatch = useInstancedBatcher ? spriteBatchInstanced : spriteBatchRegular;
        Gdx.app.log(TAG, "Initial active batch: " + (useInstancedBatcher ? "Instanced" : "Regular"));


        camera = new OrthographicCamera();
        // Initial resize will set camera and projection matrix
        Gdx.app.log(TAG, "[" + this.hashCode() + "] Camera created.");

        sprites = new Sprite[SPRITES];
        int screenW = Gdx.graphics.getWidth();
        int screenH = Gdx.graphics.getHeight();
        if (screenW == 0 || screenH == 0) { // Handle headless or early init
            screenW = 640; screenH = 480;
        }


        try {
            for (int i = 0; i < SPRITES; i++) {
                float x = random.nextFloat() * screenW * 1.5f - screenW * 0.25f; // Slightly wider spread
                float y = random.nextFloat() * screenH * 1.5f - screenH * 0.25f;
                sprites[i] = new Sprite(texture);
                sprites[i].setPosition(x, y);
                // Optional: Add slight variations for visual interest if needed
                sprites[i].setSize(texture.getWidth() * (0.5f + random.nextFloat() * 0.5f), texture.getHeight() * (0.5f + random.nextFloat() * 0.5f));
                sprites[i].setRotation(random.nextFloat() * 15f);
            }
        } catch (Throwable t) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Error during sprite creation!", t);
            dispose();
            throw t;
        }
        Gdx.app.log(TAG, "[" + this.hashCode() + "] create() END");
    }

    @Override
    public void resize(int width, int height) {
        Gdx.app.log(TAG, "[" + this.hashCode() + "] resize(" + width + ", " + height + ")");
        if (camera != null) {
            camera.setToOrtho(false, width, height);
            camera.update();
            // Update projection matrix for both batchers, or at least the active one
            if (spriteBatchRegular != null) {
                spriteBatchRegular.setProjectionMatrix(camera.combined);
            }
            if (spriteBatchInstanced != null) {
                spriteBatchInstanced.setProjectionMatrix(camera.combined);
            }
        } else {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] resize() called but camera is NULL!");
        }
    }

    @Override
    public void render() {
        handleCameraInput(); // Optional: Allow camera movement

        if (camera == null || activeBatch == null) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] render() called but camera or activeBatch is NULL!");
            return;
        }

        camera.update();
        // The activeBatch's projection matrix should be set in resize or after switching
        // For safety, can set it here too, but might be redundant if resize handles it.
        // activeBatch.setProjectionMatrix(camera.combined);

        activeBatch.begin();
        for (int i = 0; i < SPRITES; i++) {
            if (sprites[i] != null) {
                sprites[i].draw(activeBatch);
            }
        }
        activeBatch.end();

        frames++;
        long timeNow = TimeUtils.nanoTime();
        if (timeNow - lastLogTime > 1000000000) {
            float fps = frames / ((timeNow - lastLogTime) / 1000000000.0f);
            int currentRenderCalls = 0;

            if (useInstancedBatcher && spriteBatchInstanced != null) {
                currentRenderCalls = spriteBatchInstanced.renderCalls;
            } else if (!useInstancedBatcher && spriteBatchRegular != null) {
                currentRenderCalls = spriteBatchRegular.renderCalls;
            }

            float avgSpritesPerCall = (currentRenderCalls > 0) ? (float)SPRITES / currentRenderCalls : SPRITES;
            String batcherType = useInstancedBatcher ? "Instanced" : "Regular";

            Gdx.app.log(TAG, "Batcher: " + batcherType
                    + ", fps: " + df.format(fps)
                    + ", render calls: " + currentRenderCalls
                    + ", avg sprites/call: " + df.format(avgSpritesPerCall));
            frames = 0;
            lastLogTime = timeNow;
        }
    }

    private void handleCameraInput() {
        float camSpeed = 300 * Gdx.graphics.getDeltaTime(); // Increased speed
        if (Gdx.input.isKeyPressed(Input.Keys.A)) camera.translate(-camSpeed, 0);
        if (Gdx.input.isKeyPressed(Input.Keys.D)) camera.translate(camSpeed, 0);
        if (Gdx.input.isKeyPressed(Input.Keys.W)) camera.translate(0, camSpeed);
        if (Gdx.input.isKeyPressed(Input.Keys.S)) camera.translate(0, -camSpeed);
        if (Gdx.input.isKeyPressed(Input.Keys.Q)) camera.zoom += 0.02f;
        if (Gdx.input.isKeyPressed(Input.Keys.E)) camera.zoom -= 0.02f;
        camera.zoom = Math.max(0.1f, camera.zoom); // Prevent zoom from becoming too small or negative
    }


    @Override
    public void dispose() {
        Gdx.app.log(TAG, "[" + this.hashCode() + "] dispose() called.");
        if (spriteBatchRegular != null) {
            spriteBatchRegular.dispose();
            spriteBatchRegular = null;
        }
        if (spriteBatchInstanced != null) {
            spriteBatchInstanced.dispose();
            spriteBatchInstanced = null;
        }
        activeBatch = null;

        if (texture != null) {
            texture.dispose();
            texture = null;
        }
        sprites = null;
        if (Gdx.input.getInputProcessor() == this) {
            Gdx.input.setInputProcessor(null);
        }
    }

    // --- InputProcessor Methods ---
    @Override
    public boolean keyDown(int keycode) {
        if (keycode == Input.Keys.SPACE) {
            // End current batch if it's drawing
            if (activeBatch != null && activeBatch.isDrawing()) {
                activeBatch.end();
            }

            useInstancedBatcher = !useInstancedBatcher; // Toggle the flag

            // Switch the active batch reference
            activeBatch = useInstancedBatcher ? spriteBatchInstanced : spriteBatchRegular;

            Gdx.app.log(TAG, "Switched to " + (useInstancedBatcher ? "INSTANCED" : "REGULAR") + " SpriteBatch");

            // Ensure the new active batch has the correct projection matrix
            if (activeBatch != null && camera != null) {
                activeBatch.setProjectionMatrix(camera.combined);
                // It's good practice to call begin() if the batch is expected to be active immediately,
                // but our render loop handles begin()/end().
                // If begin() was called here, render() would need to check if already drawing.
            }
            return true; // Event handled
        }
        return false; // Event not handled
    }

    @Override
    public boolean keyUp(int keycode) {
        return false;
    }

    @Override
    public boolean keyTyped(char character) {
        return false;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        return false;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        return false;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        return false;
    }

    @Override
    public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
        return false;
    }
}
