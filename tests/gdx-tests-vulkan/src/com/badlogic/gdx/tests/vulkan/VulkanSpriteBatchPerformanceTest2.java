package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backend.vulkan.VulkanSpriteBatch;
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.RandomXS128;
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.TimeUtils;

import java.text.DecimalFormat;

public class VulkanSpriteBatchPerformanceTest2 extends GdxTest {
    private static final String TAG = "VulkanSpriteBatchTest";
    private final DecimalFormat df = new DecimalFormat("00.0000000");
    private final RandomXS128 rand = new RandomXS128();
    private Sprite[] sprites;
    private float[] scales;
    private OrthographicCamera camera;
    private VulkanTexture texture;
    private VulkanSpriteBatch spriteBatch;
    private long startTime = TimeUtils.nanoTime();
    private int frames = 0;

    private int SPRITES = 100000;

    private float angle = 15;
    private float ROTATION_SPEED = 2;
    private float scale = 1;
    private float SCALE_SPEED = -1;
    private boolean setLevel = true;
    private int width;
    private int height;
    private final int spriteWidth = 32;
    private final int spriteHeight = 32;

    private final float INTERVAL = 1000f * 1000f * 1000f;

    @Override
    public void create() {
        texture = new VulkanTexture(Gdx.files.internal("data/badlogicsmall.jpg"));
        camera = new OrthographicCamera();
    }

    @Override
    public void resize(int width, int height) {
        this.width = width;
        this.height = height;

        if (camera != null) {
            camera.setToOrtho(false, width, height);
        }
    }

    @Override
    public void render() {
        if (setLevel) {
            setSprites();
        }
        renderSprites();
    }

    private void setSprites() {
        spriteBatch = new VulkanSpriteBatch(SPRITES);
        sprites = new Sprite[SPRITES];
        scales = new float[SPRITES];

        rand.setState(0, 0);

        for (int i = 0; i < SPRITES; i++) {
            scales[i]= MathUtils.random(0.25f,2f);
            int x = (int) (Math.random() * (Gdx.graphics.getWidth() - spriteWidth + spriteWidth * 0.5f));
            int y = (int) (Math.random() * (Gdx.graphics.getHeight() - spriteHeight + spriteHeight * 0.5f));
            sprites[i] = new Sprite(texture);//, spriteWidth, spriteHeight);
            sprites[i].setPosition(x, y);
            sprites[i].setOrigin(spriteWidth * 0.5f, spriteHeight * 0.5f);

            //sprites[rand.nextInt(SPRITES)] = sprites[i];

        }

        setLevel = false;
    }

    private void renderSprites() {
        if (camera == null) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] renderSprites() called but camera is NULL! Cannot update.");
            return;
        }

        camera.update();
        spriteBatch.setProjectionMatrix(camera.combined);

        float begin = 0;
        float end = 0;
        float draw1 = 0;

        long start = TimeUtils.nanoTime();
        long totalStart = start;
        spriteBatch.begin();
        begin = (TimeUtils.nanoTime() - start) / INTERVAL;

        float delta = Gdx.graphics.getDeltaTime();

        float angleInc = ROTATION_SPEED * delta;
        angle += angleInc;

        if (angle > 360) {
            angle -= 360;
        }

        scale += SCALE_SPEED * delta;

        if (scale < 0.25f) {
            scale = 0.25f;
            SCALE_SPEED = 1;
        }
        if (scale > 2.0f) {
            scale = 2.0f;
            SCALE_SPEED = -1;
        }

        start = TimeUtils.nanoTime();
        for (int i = 0; i < SPRITES; i++) {
            float shift = (i / (float) SPRITES) * 1000f * delta;

            sprites[i].rotate(angle);
            sprites[i].setScale(scale);
            sprites[i].setPosition(sprites[i].getX() + shift, sprites[i].getY() + shift);

            if (sprites[i].getX() > width) {
                sprites[i].setX(0);
                sprites[i].setY((int) (Math.random() * (Gdx.graphics.getHeight() - spriteHeight + spriteHeight * 0.5f)));
            }
            if (sprites[i].getY() > height) {
                sprites[i].setX((int) (Math.random() * (Gdx.graphics.getWidth() - spriteWidth + spriteWidth * 0.5f)));
                sprites[i].setY(0);
            }

            sprites[i].draw(spriteBatch);
        }
        draw1 = (TimeUtils.nanoTime() - start) / INTERVAL;

        start = TimeUtils.nanoTime();
        spriteBatch.end();
        end = (TimeUtils.nanoTime() - start) / INTERVAL;

        if (TimeUtils.nanoTime() - startTime > INTERVAL) {
            Gdx.app.log(TAG, "fps: " + frames + ", \trender calls: " + spriteBatch.renderCalls + ", \tTotal: " + (TimeUtils.nanosToMillis(TimeUtils.timeSinceNanos(totalStart))) + ", \tbegin: " + (begin) + ", \tdraw time: " + (draw1) + ", \tend: " + (end));
            frames = 0;
            startTime = TimeUtils.nanoTime();
        }
        frames++;
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
    }
}