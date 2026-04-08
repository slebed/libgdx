package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.backend.vulkan.VulkanGraphics;
import com.badlogic.gdx.backend.vulkan.VulkanPixmapPacker;
import com.badlogic.gdx.backend.vulkan.VulkanSpriteBatch;
import com.badlogic.gdx.backend.vulkan.VulkanSpriteBatchInstanced;
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.WindowedMean;
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.StringBuilder;
import com.badlogic.gdx.utils.TimeUtils;

/**
 * Stress test for VulkanSpriteBatch and VulkanSpriteBatchInstanced.
 *
 * <p>Renders a configurable number of moving, rotating sprites and measures performance.
 * Demonstrates how to use both batcher types, swap between them at runtime, and read back
 * performance metrics (FPS, draw calls, frame time).</p>
 *
 * <h3>Controls</h3>
 * <ul>
 *   <li><b>UP / DOWN</b> &mdash; increase / decrease sprite count by 10,000</li>
 *   <li><b>SHIFT + UP / DOWN</b> &mdash; increase / decrease by 1,000</li>
 *   <li><b>SPACE</b> &mdash; toggle between Regular and Instanced batchers</li>
 *   <li><b>T</b> &mdash; toggle texture switching stress (uses 4 textures instead of 1)</li>
 *   <li><b>P</b> &mdash; pause/resume sprite movement</li>
 * </ul>
 *
 * <h3>Architecture Notes</h3>
 * <p>{@link VulkanSpriteBatch} uploads vertex data each frame via a streaming VMA buffer.
 * {@link VulkanSpriteBatchInstanced} uses instanced rendering with a single quad and a
 * per-instance data buffer. This test lets you compare their throughput under identical
 * workloads.</p>
 */
public class VulkanSpriteBatchStressTest extends GdxTest {
    private static final String TAG = "VkBatchStress";

    // --- Configuration ---
    private static final int INITIAL_SPRITES    = 10_000;
    private static final int STEP_LARGE         = 10_000;
    private static final int STEP_SMALL         = 1_000;
    private static final int MAX_SPRITES        = 200_000;
    private static final int BATCH_CAPACITY     = MAX_SPRITES;

    // --- Core objects ---
    private OrthographicCamera camera;
    private VulkanSpriteBatch batchRegular;
    private VulkanSpriteBatchInstanced batchInstanced;
    private Batch activeBatch;
    private boolean useInstanced = false;

    // --- Textures ---
    private VulkanTexture[] textures;
    private boolean multiTexture = false;

    // --- HUD ---
    private VulkanSpriteBatch hudBatch;
    private BitmapFont font;
    private VulkanPixmapPacker fontPacker;
    private final StringBuilder sb = new StringBuilder();

    // --- Sprites (structure-of-arrays for cache friendliness) ---
    private int spriteCount = INITIAL_SPRITES;
    private float[] posX, posY;
    private float[] velX, velY;
    private float[] rotation, rotSpeed;
    private float[] scale;
    private int[] texIndex;
    private boolean paused = false;

    // --- Metrics ---
    private final WindowedMean frameTimeMean = new WindowedMean(120);
    private float worstFrameMs;
    private float bestFrameMs = Float.MAX_VALUE;
    private long lastResetTime;
    private int lastDrawCalls;

    @Override
    public void create() {
        // --- Camera ---
        camera = new OrthographicCamera();

        // --- Textures ---
        // Create 4 colored textures procedurally to avoid external asset dependency.
        // When multiTexture mode is on, the batcher must handle texture switches each frame,
        // which stresses the flush/rebind path.
        Color[] colors = {
            new Color(0.4f, 0.1f, 0.1f, 1f),  // dark red
            new Color(0.1f, 0.4f, 0.1f, 1f),  // dark green
            new Color(0.1f, 0.1f, 0.4f, 1f),  // dark blue
            new Color(0.4f, 0.4f, 0.1f, 1f),  // dark yellow
        };
        textures = new VulkanTexture[colors.length];
        for (int i = 0; i < colors.length; i++) {
            Pixmap pm = new Pixmap(32, 32, Pixmap.Format.RGBA8888);
            pm.setColor(colors[i]);
            pm.fillRectangle(0, 0, 32, 32);
            // Draw a small inner square to make sprites visually distinct
            pm.setColor(new Color(colors[i].r * 1.5f, colors[i].g * 1.5f, colors[i].b * 1.5f, 1f));
            pm.fillRectangle(8, 8, 16, 16);
            textures[i] = new VulkanTexture(pm);
            pm.dispose();
        }

        // --- Batchers ---
        // VulkanSpriteBatch: streaming upload each frame. Good general-purpose batcher.
        batchRegular = new VulkanSpriteBatch(BATCH_CAPACITY);

        // VulkanSpriteBatchInstanced: single quad + per-instance buffer. Best for many
        // identical or similar sprites (particles, bullets).
        batchInstanced = new VulkanSpriteBatchInstanced(BATCH_CAPACITY);

        activeBatch = batchRegular;

        // --- HUD font (FreeType -> VulkanPixmapPacker -> BitmapFont) ---
        hudBatch = new VulkanSpriteBatch();
        font = createHudFont();

        // --- Sprite data ---
        allocateSprites(MAX_SPRITES);
        randomizeSprites(0, spriteCount);

        // --- Input ---
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override
            public boolean keyDown(int keycode) {
                return handleKey(keycode);
            }
        });

        lastResetTime = TimeUtils.nanoTime();
        Gdx.app.log(TAG, "Created. " + spriteCount + " sprites. Press SPACE to toggle batcher, UP/DOWN to adjust count.");
    }

    // ---- Rendering ----

    @Override
    public void render() {
        long frameStart = TimeUtils.nanoTime();
        float delta = Gdx.graphics.getDeltaTime();

        // Update sprite positions
        if (!paused) updateSprites(delta);

        // Clear
        Gdx.gl.glClearColor(0.08f, 0.08f, 0.12f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // Draw sprites
        camera.update();
        activeBatch.setProjectionMatrix(camera.combined);
        activeBatch.begin();
        drawSprites();
        activeBatch.end();

        // Capture draw call count from the batcher that just rendered
        if (useInstanced) {
            lastDrawCalls = batchInstanced.renderCalls;
        } else {
            lastDrawCalls = batchRegular.renderCalls;
        }

        // Draw HUD overlay
        drawHud();

        // Record frame time
        float frameMs = (TimeUtils.nanoTime() - frameStart) / 1_000_000f;
        frameTimeMean.addValue(frameMs);
        if (frameMs > worstFrameMs) worstFrameMs = frameMs;
        if (frameMs < bestFrameMs) bestFrameMs = frameMs;

        // Reset min/max every 5 seconds to keep them fresh
        if (TimeUtils.nanoTime() - lastResetTime > 5_000_000_000L) {
            worstFrameMs = 0f;
            bestFrameMs = Float.MAX_VALUE;
            lastResetTime = TimeUtils.nanoTime();
        }
    }

    private void drawSprites() {
        float screenW = Gdx.graphics.getWidth();
        float screenH = Gdx.graphics.getHeight();
        float spriteW = 24f;
        float spriteH = 24f;
        float halfW = spriteW * 0.5f;
        float halfH = spriteH * 0.5f;

        if (multiTexture) {
            // Multi-texture path: sprites use different textures, forcing flushes on switch.
            for (int i = 0; i < spriteCount; i++) {
                VulkanTexture tex = textures[texIndex[i]];
                float s = scale[i];
                activeBatch.draw(tex,
                        posX[i] - halfW * s, posY[i] - halfH * s,
                        halfW * s, halfH * s,
                        spriteW * s, spriteH * s,
                        1f, 1f,
                        rotation[i],
                        0, 0, 32, 32,
                        false, false);
            }
        } else {
            // Single-texture path: all sprites share one texture, minimal flushes.
            VulkanTexture tex = textures[0];
            for (int i = 0; i < spriteCount; i++) {
                float s = scale[i];
                activeBatch.draw(tex,
                        posX[i] - halfW * s, posY[i] - halfH * s,
                        halfW * s, halfH * s,
                        spriteW * s, spriteH * s,
                        1f, 1f,
                        rotation[i],
                        0, 0, 32, 32,
                        false, false);
            }
        }
    }

    private void drawHud() {
        hudBatch.setProjectionMatrix(camera.combined);
        hudBatch.begin();

        sb.setLength(0);
        sb.append("Vulkan SpriteBatch Stress Test\n");
        sb.append("Batcher: ").append(useInstanced ? "INSTANCED" : "REGULAR").append('\n');
        sb.append("Sprites: ").append(spriteCount);
        sb.append("  Textures: ").append(multiTexture ? textures.length : 1).append('\n');
        sb.append("Draw calls: ").append(lastDrawCalls).append('\n');
        sb.append("FPS: ").append(Gdx.graphics.getFramesPerSecond());
        if (frameTimeMean.hasEnoughData()) {
            sb.append("  Frame: ").append(String.format("%.2f", frameTimeMean.getMean())).append("ms");
            sb.append("  Best: ").append(String.format("%.2f", bestFrameMs)).append("ms");
            sb.append("  Worst: ").append(String.format("%.2f", worstFrameMs)).append("ms");
        }
        sb.append('\n');
        sb.append(paused ? "[PAUSED] " : "");
        sb.append("[UP/DOWN] count  [SPACE] batcher  [T] textures  [P] pause\n");

        font.draw(hudBatch, sb, 10, Gdx.graphics.getHeight() - 10);
        hudBatch.end();
    }

    // ---- Sprite simulation ----

    private void updateSprites(float delta) {
        float screenW = Gdx.graphics.getWidth();
        float screenH = Gdx.graphics.getHeight();

        for (int i = 0; i < spriteCount; i++) {
            posX[i] += velX[i] * delta;
            posY[i] += velY[i] * delta;
            rotation[i] += rotSpeed[i] * delta;

            // Wrap around screen edges
            if (posX[i] < -32f) posX[i] += screenW + 64f;
            else if (posX[i] > screenW + 32f) posX[i] -= screenW + 64f;
            if (posY[i] < -32f) posY[i] += screenH + 64f;
            else if (posY[i] > screenH + 32f) posY[i] -= screenH + 64f;
        }
    }

    // ---- Sprite data management ----

    private void allocateSprites(int capacity) {
        posX     = new float[capacity];
        posY     = new float[capacity];
        velX     = new float[capacity];
        velY     = new float[capacity];
        rotation = new float[capacity];
        rotSpeed = new float[capacity];
        scale    = new float[capacity];
        texIndex = new int[capacity];
    }

    private void randomizeSprites(int from, int to) {
        float screenW = Gdx.graphics.getWidth();
        float screenH = Gdx.graphics.getHeight();
        if (screenW == 0) screenW = 1280;
        if (screenH == 0) screenH = 720;

        for (int i = from; i < to; i++) {
            posX[i]     = MathUtils.random(0f, screenW);
            posY[i]     = MathUtils.random(0f, screenH);
            velX[i]     = MathUtils.random(-80f, 80f);
            velY[i]     = MathUtils.random(-80f, 80f);
            rotation[i] = MathUtils.random(0f, 360f);
            rotSpeed[i] = MathUtils.random(-90f, 90f);
            scale[i]    = MathUtils.random(0.4f, 1.5f);
            texIndex[i] = MathUtils.random(0, textures.length - 1);
        }
    }

    // ---- Input handling ----

    private boolean handleKey(int keycode) {
        boolean shift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                     || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
        int step = shift ? STEP_SMALL : STEP_LARGE;

        switch (keycode) {
            case Input.Keys.UP:
                int newCount = Math.min(spriteCount + step, MAX_SPRITES);
                if (newCount > spriteCount) {
                    randomizeSprites(spriteCount, newCount);
                    spriteCount = newCount;
                    Gdx.app.log(TAG, "Sprite count: " + spriteCount);
                }
                return true;

            case Input.Keys.DOWN:
                spriteCount = Math.max(spriteCount - step, 0);
                Gdx.app.log(TAG, "Sprite count: " + spriteCount);
                return true;

            case Input.Keys.SPACE:
                useInstanced = !useInstanced;
                activeBatch = useInstanced ? batchInstanced : batchRegular;
                activeBatch.setProjectionMatrix(camera.combined);
                Gdx.app.log(TAG, "Switched to " + (useInstanced ? "INSTANCED" : "REGULAR") + " batcher");
                return true;

            case Input.Keys.T:
                multiTexture = !multiTexture;
                Gdx.app.log(TAG, "Multi-texture: " + multiTexture);
                return true;

            case Input.Keys.P:
                paused = !paused;
                Gdx.app.log(TAG, paused ? "Paused" : "Resumed");
                return true;

            default:
                return false;
        }
    }

    // ---- Lifecycle ----

    @Override
    public void resize(int width, int height) {
        if (camera != null) {
            camera.setToOrtho(false, width, height);
            camera.update();
        }
    }

    @Override
    public void dispose() {
        Gdx.app.log(TAG, "Disposing...");
        if (batchRegular   != null) batchRegular.dispose();
        if (batchInstanced != null) batchInstanced.dispose();
        if (hudBatch       != null) hudBatch.dispose();
        if (font           != null) font.dispose();
        if (fontPacker     != null) fontPacker.dispose();
        for (VulkanTexture tex : textures) {
            if (tex != null) tex.dispose();
        }
        Gdx.input.setInputProcessor(null);
    }

    // ---- Font helper ----

    /** Creates a BitmapFont using FreeType and VulkanPixmapPacker.
     * This demonstrates the recommended way to create fonts in the Vulkan backend. */
    private BitmapFont createHudFont() {
        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(Gdx.files.internal("data/DroidSerif-Regular.ttf"));
        FreeTypeFontGenerator.FreeTypeFontParameter param = new FreeTypeFontGenerator.FreeTypeFontParameter();

        fontPacker = new VulkanPixmapPacker(512, 512, Pixmap.Format.RGBA8888, 2, false);
        param.packer      = fontPacker;
        param.size         = 18;
        param.color        = Color.WHITE;
        param.incremental  = true;
        param.minFilter    = Texture.TextureFilter.Linear;
        param.magFilter    = Texture.TextureFilter.Linear;

        BitmapFont f = gen.generateFont(param);
        f.setUseIntegerPositions(false);
        gen.dispose();
        return f;
    }
}
