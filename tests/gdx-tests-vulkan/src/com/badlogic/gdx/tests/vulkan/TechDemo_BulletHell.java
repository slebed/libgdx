package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backend.vulkan.VulkanDevice;
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
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;

import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;

public class TechDemo_BulletHell extends ApplicationAdapter {

    // --- Configuration ---
    private static final boolean USE_VULKAN_INSTANCED_BATCHING = true;
    private static final float PLAYER_SPEED = 400f;
    private static final float PLAYER_SIZE = 24f;
    private static final float BULLET_GRAPHIC_SIZE = 8f;
    private static final float BULLET_HITBOX_SIZE = 5f;
    private static final float BULLET_SPEED = 300f;
    private static final int EMITTERS_COUNT = 7;
    private static final int BULLETS_PER_EMITTER_WAVE = 25;
    private static final float EMITTER_SPAWN_INTERVAL = 0.05f;
    private static final int MAX_BULLETS_ON_SCREEN = 50000;
    private static final int VK_STREAMING_FLUSH_TRIGGER = 2048;

    // --- Core Objects ---
    private OrthographicCamera camera;
    private Batch batch; // Will be initialized in the first render call
    private BitmapFont font;
    private VulkanPixmapPacker fontPacker;

    private VulkanTexture playerTexture;
    private VulkanTexture bulletTexture;

    // --- Game Objects ---
    private Rectangle playerHitbox;
    private final Array<Bullet> bullets = new Array<>(false, MAX_BULLETS_ON_SCREEN);
    private final Array<Vector2> emitterPositions = new Array<>();
    private float emitterSpawnTimer;
    private float emitterRotationAngle = 0f;
    private final Vector2 playerVelocity = new Vector2();

    @Override
    public void create() {
        // In create(), only initialize objects that do NOT depend on Gdx.graphics.
        // All resource creation (textures, fonts, batches) is deferred to lazyInit().
        camera = new OrthographicCamera();
        playerHitbox = new Rectangle();
    }

    /**
     * Initializes all graphics-dependent resources. This is called only once, on the first
     * frame of rendering, to ensure Gdx.graphics points to the correct window context.
     */
    private void lazyInit() {
        if (batch != null) {
            return; // Already initialized, do nothing.
        }

        Gdx.app.log("DEMO_SETUP", "Lazily initializing all graphics resources...");

        // At this point, Gdx.graphics is guaranteed to be the one for this demo window.
        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;

        // --- Font Creation ---
        //font = createFont("data/DroidSerif-Regular.ttf", 24, gfx);

        // --- Batch Creation ---
        if (USE_VULKAN_INSTANCED_BATCHING) {
            Gdx.app.log("DEMO_SETUP", "Using VulkanSpriteBatchInstanced. Max instances: " + MAX_BULLETS_ON_SCREEN);
            //batch = new VulkanSpriteBatchInstanced(gfx, MAX_BULLETS_ON_SCREEN);
        } else {
            Gdx.app.log("DEMO_SETUP", "Using VulkanSpriteBatch (Streaming). Flush trigger: " + VK_STREAMING_FLUSH_TRIGGER);
            // batch = new VulkanSpriteBatch(gfx, VK_STREAMING_FLUSH_TRIGGER, MAX_BULLETS_ON_SCREEN);
        }

        // --- Texture Creation ---
        Pixmap playerPixmap = new Pixmap((int) PLAYER_SIZE, (int) PLAYER_SIZE, Pixmap.Format.RGBA8888);
        playerPixmap.setColor(Color.CYAN);
        playerPixmap.fillCircle(playerPixmap.getWidth() / 2, playerPixmap.getHeight() / 2, (int) (PLAYER_SIZE / 2 - 1));
        playerTexture = new VulkanTexture(playerPixmap);
        playerPixmap.dispose();

        Pixmap bulletPixmap = new Pixmap((int) BULLET_GRAPHIC_SIZE, (int) BULLET_GRAPHIC_SIZE, Pixmap.Format.RGBA8888);
        bulletPixmap.setColor(0.9f, 0.2f, 0.2f, 1f);
        bulletPixmap.fillCircle(bulletPixmap.getWidth() / 2, bulletPixmap.getHeight() / 2, (int) (BULLET_GRAPHIC_SIZE / 2 - 1));
        bulletTexture = new VulkanTexture(bulletPixmap);
        bulletPixmap.dispose();

        // --- Game Object Sizing and Positioning ---
        playerHitbox.set(
                (Gdx.graphics.getWidth() - PLAYER_SIZE) / 2f,
                50f,
                PLAYER_SIZE,
                PLAYER_SIZE
        );
        setupEmitters();
    }

    private BitmapFont createFont(String fontName, int size, VulkanGraphics gfx) {
        Gdx.app.log("DEMO_SETUP", "Creating FreeTypeFont with VulkanPixmapPacker...");
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(Gdx.files.internal(fontName));
        FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();

        // Pass the correct graphics context to our custom packer.
        //this.fontPacker = new VulkanPixmapPacker(gfx, 1024, 1024, Pixmap.Format.RGBA8888, 2, false);
        parameter.packer = this.fontPacker;

        parameter.size = size;
        parameter.color = Color.WHITE;
        parameter.incremental = true;
        parameter.minFilter = Texture.TextureFilter.Linear;
        parameter.magFilter = Texture.TextureFilter.Linear;
        parameter.kerning = true;

        BitmapFont generatedFont = generator.generateFont(parameter);
        generatedFont.setUseIntegerPositions(false);

        generator.dispose();
        Gdx.app.log("DEMO_SETUP", "BitmapFont created successfully using VulkanPixmapPacker.");
        return generatedFont;
    }

    @Override
    public void render() {
        // This ensures all resources are created with the correct context on the first frame.
        lazyInit();

        float delta = Gdx.graphics.getDeltaTime();
        handleInput();
        updateGameLogic(delta);

        Gdx.gl.glClearColor(0.1f, 0.1f, 0.15f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        camera.update();
        batch.setProjectionMatrix(camera.combined);

        batch.begin();
        // This render order ensures bullets are drawn under the player and UI
        for (int i = 0; i < bullets.size; i++) {
            Bullet bullet = bullets.get(i);
            batch.draw(bulletTexture,
                    bullet.hitbox.x + (BULLET_HITBOX_SIZE - BULLET_GRAPHIC_SIZE) / 2f,
                    bullet.hitbox.y + (BULLET_HITBOX_SIZE - BULLET_GRAPHIC_SIZE) / 2f,
                    BULLET_GRAPHIC_SIZE, BULLET_GRAPHIC_SIZE);
        }
        batch.draw(playerTexture, playerHitbox.x, playerHitbox.y, playerHitbox.width, playerHitbox.height);

        //String stats = "FPS: " + Gdx.graphics.getFramesPerSecond() + "  |  Bullets: " + bullets.size + " / " + MAX_BULLETS_ON_SCREEN;
        //font.draw(batch, stats, Gdx.graphics.getWidth() / 2, Gdx.graphics.getHeight() / 2);

        batch.end();
    }

    @Override
    public void dispose() {
        Gdx.app.log("DEMO_DISPOSE", "Disposing all resources...");

        // Ensure GPU is idle before destroying resources to prevent validation errors.
        if (Gdx.graphics != null) {
            VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
            if (gfx != null) {
                VulkanDevice device = gfx.getVulkanDevice();
                if (device != null && device.isDeviceAvailable()) {
                    vkDeviceWaitIdle(device.getLogicalDevice());
                }
            }
        }

        // Dispose resources in reverse order of creation
        if (batch != null) batch.dispose();
        if (font != null) font.dispose();
        if (fontPacker != null) fontPacker.dispose();
        if (playerTexture != null) playerTexture.dispose();
        if (bulletTexture != null) bulletTexture.dispose();

        bullets.clear();
        Gdx.app.log("DEMO_DISPOSE", "Demo disposed.");
    }

    // --- Unchanged methods ---
    @Override
    public void resize(int width, int height) {
        if (camera != null) {
            camera.setToOrtho(false, width, height);
            camera.update();
        }
        setupEmitters();
        Gdx.app.log("DEMO_RESIZE", "Resized to: " + width + "x" + height);
    }

    private void setupEmitters() {
        emitterPositions.clear();
        float screenWidth = Gdx.graphics.getWidth();
        float screenHeight = Gdx.graphics.getHeight();
        float spacing = screenWidth / (EMITTERS_COUNT + 1f);
        for (int i = 0; i < EMITTERS_COUNT; i++) {
            emitterPositions.add(new Vector2((i + 1) * spacing, screenHeight - 70f));
        }
    }

    private void handleInput() {
        playerVelocity.set(0, 0);
        if (Gdx.input.isKeyPressed(Input.Keys.LEFT) || Gdx.input.isKeyPressed(Input.Keys.A)) playerVelocity.x -= 1;
        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT) || Gdx.input.isKeyPressed(Input.Keys.D)) playerVelocity.x += 1;
        if (Gdx.input.isKeyPressed(Input.Keys.UP) || Gdx.input.isKeyPressed(Input.Keys.W)) playerVelocity.y += 1;
        if (Gdx.input.isKeyPressed(Input.Keys.DOWN) || Gdx.input.isKeyPressed(Input.Keys.S)) playerVelocity.y -= 1;
        playerVelocity.nor().scl(PLAYER_SPEED);
    }

    private void updateGameLogic(float delta) {
        if (playerHitbox == null) return;
        playerHitbox.x += playerVelocity.x * delta;
        playerHitbox.y += playerVelocity.y * delta;
        playerHitbox.x = MathUtils.clamp(playerHitbox.x, 0, Gdx.graphics.getWidth() - playerHitbox.width);
        playerHitbox.y = MathUtils.clamp(playerHitbox.y, 0, Gdx.graphics.getHeight() - playerHitbox.height);

        emitterSpawnTimer -= delta;
        emitterRotationAngle += 60f * delta;

        if (emitterSpawnTimer <= 0 && bullets.size < MAX_BULLETS_ON_SCREEN) {
            emitterSpawnTimer = EMITTER_SPAWN_INTERVAL;
            for (Vector2 emitterPos : emitterPositions) {
                float angleStep = 360f / BULLETS_PER_EMITTER_WAVE;
                for (int i = 0; i < BULLETS_PER_EMITTER_WAVE; i++) {
                    if (bullets.size >= MAX_BULLETS_ON_SCREEN) break;
                    float currentAngle = emitterRotationAngle + (i * angleStep);
                    float velX = MathUtils.cosDeg(currentAngle) * BULLET_SPEED;
                    float velY = MathUtils.sinDeg(currentAngle) * BULLET_SPEED;
                    bullets.add(new Bullet(emitterPos.x, emitterPos.y, velX, velY));
                }
            }
        }

        for (int i = bullets.size - 1; i >= 0; i--) {
            Bullet bullet = bullets.get(i);
            bullet.update(delta);
            if (bullet.hitbox.x < -BULLET_GRAPHIC_SIZE || bullet.hitbox.x > Gdx.graphics.getWidth() ||
                    bullet.hitbox.y < -BULLET_GRAPHIC_SIZE || bullet.hitbox.y > Gdx.graphics.getHeight()) {
                bullets.removeIndex(i);
            }
        }
    }

    private static class Bullet {
        Rectangle hitbox;
        Vector2 velocity;

        Bullet(float x, float y, float velX, float velY) {
            hitbox = new Rectangle(x - BULLET_HITBOX_SIZE / 2f, y - BULLET_HITBOX_SIZE / 2f, BULLET_HITBOX_SIZE, BULLET_HITBOX_SIZE);
            velocity = new Vector2(velX, velY);
        }

        void update(float delta) {
            hitbox.x += velocity.x * delta;
            hitbox.y += velocity.y * delta;
        }
    }
}
