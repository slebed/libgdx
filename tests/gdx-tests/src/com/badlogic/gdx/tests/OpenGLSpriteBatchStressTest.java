package com.badlogic.gdx.tests; // Changed package

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
// import com.badlogic.gdx.InputProcessor; // No longer strictly needed if SPACE switch is removed
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture; // Standard Texture
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.SpriteBatch; // Standard SpriteBatch
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.TimeUtils;
import java.text.DecimalFormat;
import java.util.Random;

// Changed class name and removed InputProcessor if only camera polling is used
public class OpenGLSpriteBatchStressTest extends GdxTest {
    private static final String TAG = "GLStressTest"; // Changed TAG
    DecimalFormat df = new DecimalFormat("0.00");

    int SPRITES = 8000; // Same number of sprites for comparison

    long lastLogTime = TimeUtils.nanoTime();
    int frames = 0;
    private OrthographicCamera camera;
    Texture texture; // Standard Texture

    // Batcher instance
    SpriteBatch spriteBatch; // Standard SpriteBatch

    Sprite[] sprites;
    Random random = new Random();

    @Override
    public void create() {
        Gdx.app.log(TAG, "[" + this.hashCode() + "] create() START");
        Gdx.app.log(TAG, "Testing with " + SPRITES + " sprites using standard OpenGL SpriteBatch.");

        // Gdx.input.setInputProcessor(this); // Only needed if keyDown etc. are used for more than polling

        try {
            texture = new Texture(Gdx.files.internal("data/badlogicsmall.jpg")); // Use standard Texture
        } catch (Throwable t) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Failed to load texture!", t);
            throw t;
        }

        try {
            Gdx.app.log(TAG, "Creating OpenGL SpriteBatch...");
            // The constructor argument is the max sprites between begin/end if texture changes.
            // For a single texture draw, it's less critical but good for pre-allocation.
            spriteBatch = new SpriteBatch(SPRITES);
        } catch (Throwable t) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Failed to create SpriteBatch instance!", t);
            if (texture != null) texture.dispose();
            throw t;
        }

        Gdx.app.log(TAG, "Active batch: OpenGL Standard SpriteBatch");

        camera = new OrthographicCamera();
        Gdx.app.log(TAG, "[" + this.hashCode() + "] Camera created.");

        sprites = new Sprite[SPRITES];
        int screenW = Gdx.graphics.getWidth();
        int screenH = Gdx.graphics.getHeight();
        if (screenW == 0 || screenH == 0) { // Handle headless or early init
            screenW = 640; screenH = 480;
        }

        try {
            for (int i = 0; i < SPRITES; i++) {
                float x = random.nextFloat() * screenW * 1.5f - screenW * 0.25f;
                float y = random.nextFloat() * screenH * 1.5f - screenH * 0.25f;
                sprites[i] = new Sprite(texture);
                sprites[i].setPosition(x, y);
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
            if (spriteBatch != null) {
                spriteBatch.setProjectionMatrix(camera.combined);
            }
        } else {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] resize() called but camera is NULL!");
        }
    }

    @Override
    public void render() {
        handleCameraInput(); // Camera movement

        if (camera == null || spriteBatch == null) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] render() called but camera or spriteBatch is NULL!");
            return;
        }

        camera.update();
        // Projection matrix is set in resize, and on camera update if needed,
        // but SpriteBatch typically takes it once. For safety, ensure it's set.
        // spriteBatch.setProjectionMatrix(camera.combined); // Usually set in resize

        spriteBatch.begin();
        for (int i = 0; i < SPRITES; i++) {
            if (sprites[i] != null) {
                sprites[i].draw(spriteBatch);
            }
        }
        spriteBatch.end();

        frames++;
        long timeNow = TimeUtils.nanoTime();
        if (timeNow - lastLogTime > 1000000000) { // Log once per second
            float fps = frames / ((timeNow - lastLogTime) / 1000000000.0f);
            int currentRenderCalls = 0;

            if (spriteBatch != null) {
                currentRenderCalls = spriteBatch.renderCalls; // Standard SpriteBatch tracks render calls
            }

            float avgSpritesPerCall = (currentRenderCalls > 0) ? (float)SPRITES / currentRenderCalls : SPRITES;
            String batcherType = "OpenGL Standard";

            Gdx.app.log(TAG, "Batcher: " + batcherType
                    + ", fps: " + df.format(fps)
                    + ", render calls: " + currentRenderCalls
                    + ", avg sprites/call: " + df.format(avgSpritesPerCall));
            frames = 0;
            lastLogTime = timeNow;
        }
    }

    private void handleCameraInput() {
        if (camera == null) return; // Guard clause
        float camSpeed = 300 * Gdx.graphics.getDeltaTime();
        if (Gdx.input.isKeyPressed(Input.Keys.A)) camera.translate(-camSpeed, 0);
        if (Gdx.input.isKeyPressed(Input.Keys.D)) camera.translate(camSpeed, 0);
        if (Gdx.input.isKeyPressed(Input.Keys.W)) camera.translate(0, camSpeed);
        if (Gdx.input.isKeyPressed(Input.Keys.S)) camera.translate(0, -camSpeed);
        if (Gdx.input.isKeyPressed(Input.Keys.Q)) camera.zoom += 0.02f;
        if (Gdx.input.isKeyPressed(Input.Keys.E)) camera.zoom -= 0.02f;
        camera.zoom = Math.max(0.1f, camera.zoom);
        // camera.update(); // Camera is updated at the start of render()
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
        sprites = null;
        // If you had set an InputProcessor:
        // if (Gdx.input.getInputProcessor() == this) {
        // Gdx.input.setInputProcessor(null);
        // }
    }

    // --- InputProcessor Methods (No longer needed if class doesn't implement InputProcessor) ---
    // If you decide to keep InputProcessor for other reasons, uncomment and implement these.
    // Otherwise, you can remove the 'implements InputProcessor' and these methods.

    // @Override public boolean keyDown(int keycode) { return false; }
    // @Override public boolean keyUp(int keycode) { return false; }
    // @Override public boolean keyTyped(char character) { return false; }
    // @Override public boolean touchDown(int screenX, int screenY, int pointer, int button) { return false; }
    // @Override public boolean touchUp(int screenX, int screenY, int pointer, int button) { return false; }
    // @Override public boolean touchDragged(int screenX, int screenY, int pointer) { return false; }
    // @Override public boolean mouseMoved(int screenX, int screenY) { return false; }
    // @Override public boolean scrolled(float amountX, float amountY) { return false; }
    // @Override public boolean touchCancelled(int screenX, int screenY, int pointer, int button) { return false;}
}