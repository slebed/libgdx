package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backend.vulkan.VulkanSpriteBatch;
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.TimeUtils;

import java.text.DecimalFormat;
import java.util.Random;

public class VulkanSpriteBatchTextureSwitchTest extends GdxTest implements InputProcessor {
    private static final String TAG = "VkTexSwitchTest";
    DecimalFormat df = new DecimalFormat("0.00");

    int SPRITES_PER_TEXTURE = 500; // Moderate number per texture
    int NUM_TEXTURES = 3; // Number of different textures to load

    long lastLogTime = TimeUtils.nanoTime();
    int frames = 0;
    private OrthographicCamera camera;
    Array<VulkanTexture> textures = new Array<>(NUM_TEXTURES);
    VulkanSpriteBatch spriteBatch;
    Array<Sprite> sprites = new Array<>();
    Random random = new Random();
    boolean interleaveDraw = true; // Toggle drawing order

    @Override
    public void create() {
        Gdx.app.log(TAG, "[" + this.hashCode() + "] create() START");
        Gdx.app.log(TAG, "Testing with " + NUM_TEXTURES + " textures, " + SPRITES_PER_TEXTURE + " sprites each.");

        Gdx.input.setInputProcessor(this);
        Gdx.app.log(TAG, "[" + this.hashCode() + "] InputProcessor set to this test instance.");

        // Load textures
        try {
            // Ensure you have these files or change paths
            textures.add(new VulkanTexture(Gdx.files.internal("data/badlogicsmall.jpg")));
            textures.add(new VulkanTexture(Gdx.files.internal("data/particle-star.png"))); // Needs alpha blending
            textures.add(new VulkanTexture(Gdx.files.internal("data/tile.png")));          // Needs alpha blending
            if (textures.size < NUM_TEXTURES) {
                Gdx.app.error(TAG, "Could not load all required textures!");
                NUM_TEXTURES = textures.size; // Adjust count if some failed
            }
        } catch (Throwable t) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Failed to load textures!", t);
            dispose(); // Clean up anything loaded
            throw t;
        }

        try {
            spriteBatch = new VulkanSpriteBatch(SPRITES_PER_TEXTURE * NUM_TEXTURES);
        } catch (Throwable t) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Failed to create VulkanSpriteBatch!", t);
            dispose();
            throw t;
        }
        // Enable blending by default for PNGs
        spriteBatch.enableBlending();
        // Set standard alpha blending
        spriteBatch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);


        camera = new OrthographicCamera();
        camera.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        Gdx.app.log(TAG, "[" + this.hashCode() + "] Camera created.");

        // Create sprites, assigning textures
        int screenW = Gdx.graphics.getWidth();
        int screenH = Gdx.graphics.getHeight();
        try {
            for (int t = 0; t < NUM_TEXTURES; t++) {
                Texture currentTex = textures.get(t);
                for (int i = 0; i < SPRITES_PER_TEXTURE; i++) {
                    float x = random.nextFloat() * screenW;
                    float y = random.nextFloat() * screenH;
                    Sprite sprite = new Sprite(currentTex);
                    sprite.setPosition(x, y);
                    sprite.setOriginCenter();
                    // Add some rotation/scale variation
                    sprite.setRotation(random.nextFloat() * 360);
                    sprite.setScale(0.5f + random.nextFloat() * 0.5f);
                    sprites.add(sprite);
                }
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
            camera.update();
        }
        if (spriteBatch != null && camera != null) {
            spriteBatch.setProjectionMatrix(camera.combined);
        }
    }

    @Override
    public void render() {
        if (camera == null || spriteBatch == null || textures.isEmpty()) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] render() called with null resources!");
            return;
        }

        camera.update();
        spriteBatch.setProjectionMatrix(camera.combined);

        spriteBatch.begin();

        if (interleaveDraw) {
            // Draw all sprites in the order they were created (interleaved textures)
            // This should cause NUM_TEXTURES * SPRITES_PER_TEXTURE switches if batch size is 1
            // But with batching, it should be less, closer to NUM_TEXTURES if sprites of same texture are consecutive enough
            for (Sprite sprite : sprites) {
                sprite.rotate(10 * Gdx.graphics.getDeltaTime()); // Simple animation
                sprite.draw(spriteBatch);
            }
        } else {
            // Draw sprites grouped by texture (less switching)
            // This should cause NUM_TEXTURES switches (flushes)
            for (int t = 0; t < NUM_TEXTURES; t++) {
                Texture currentTex = textures.get(t);
                // Iterate through sprites, drawing only those with the current texture
                for (Sprite sprite : sprites) {
                    if (sprite.getTexture() == currentTex) {
                        sprite.rotate(10 * Gdx.graphics.getDeltaTime()); // Simple animation
                        sprite.draw(spriteBatch);
                    }
                }
                // This point implicitly marks where a flush *should* happen IF the next
                // texture group uses a different texture (which it will).
            }
        }

        spriteBatch.end();

        frames++;
        long timeNow = TimeUtils.nanoTime();
        if (timeNow - lastLogTime > 1000000000) {
            float fps = frames / ((timeNow - lastLogTime) / 1000000000.0f);
            int renderCalls = spriteBatch.renderCalls;
            int totalSprites = sprites.size;
            float avgSpritesPerCall = (renderCalls > 0) ? (float) totalSprites / renderCalls : totalSprites;

            Gdx.app.log(TAG, "DrawOrder: " + (interleaveDraw ? "Interleaved" : "Grouped")
                    + ", fps: " + df.format(fps)
                    + ", render calls: " + renderCalls
                    + ", avg sprites/call: " + df.format(avgSpritesPerCall));
            frames = 0;
            lastLogTime = timeNow;
        }
    }

    @Override
    public void dispose() {
        Gdx.app.log(TAG, "[" + this.hashCode() + "] dispose() called.");
        if (spriteBatch != null) {
            spriteBatch.dispose();
            spriteBatch = null;
        }
        // Dispose all loaded textures
        for (VulkanTexture tex : textures) {
            if (tex != null) {
                tex.dispose();
            }
        }
        textures.clear();
        sprites.clear();

        if (Gdx.input.getInputProcessor() == this) {
            Gdx.input.setInputProcessor(null);
        }
    }

    @Override
    public boolean keyDown(int keycode) {
        if (keycode == com.badlogic.gdx.Input.Keys.SPACE) {
            Gdx.app.log(TAG, "*** InputProcessor: keyDown SPACE detected! ***");
            // ACTUALLY TOGGLE THE BOOLEAN HERE
            interleaveDraw = !interleaveDraw;
            Gdx.app.log(TAG, "Draw order toggled (on keyDown). Interleaved now: " + interleaveDraw);
            return true; // We handled the input
        }
        return false; // Not handled
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

    // In LibGDX >= 1.9.11 touchCancelled exists
    @Override
    public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
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
}