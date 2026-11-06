package io.github.slebed.gdxvulkan.demos;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;

import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;

/**
 * A sprite storm: a hundred thousand and more sprites from one texture atlas, each with its own position, size, rotation and tint, drawn with the long form of
 * {@code Batch.draw}. This is the best case for stock libGDX (one texture, so no flush per texture change), which makes it the fair test of how much the batch itself costs:
 * the work per sprite on the CPU and the bytes the GPU reads. The batch is the only line that differs between the backends.
 */
public final class SpriteStormDemo implements Demo {
    private static final int TILES = 16, TILE = 32;

    private final int count = Integer.getInteger("demo.storm", Controls.touch() ? 40_000 : 150_000);
    private Batch batch;
    private Texture atlas;
    private TextureRegion[] regions;
    private float[] x, y, vx, vy, angle, spin, size, tint;
    private int[] region;
    private boolean paused;
    private Hud hud;

    @Override public String title () {
        return "Sprite storm";
    }

    @Override public String summary () {
        return count + " sprites from one atlas, each moving, spinning and tinted on its own. The drawing code is the same on both backends; the batch is "
            + (Backend.vulkan ? "VulkanSpriteBatch" : "stock SpriteBatch") + ".";
    }

    @Override public String[] notes () {
        return new String[] {"One texture: stock libGDX never has to flush for a texture change, so this measures the batch itself.",
            (Controls.touch() ? "A tap on the left half" : "Space") + " pauses." + (Controls.touch() ? "" : " C starts the stock OpenGL version beside this window.")};
    }

    @Override public String openGlCounterpart () {
        return "storm";
    }

    @Override public void create () {
        batch = Backend.vulkan && !Boolean.getBoolean("demo.stockBatch") ? new VulkanSpriteBatch() : new SpriteBatch();
        final Pixmap pixmap = new Pixmap(TILES * TILE, TILES * TILE, Pixmap.Format.RGBA8888);
        final Color color = new Color();
        for (int t = 0; t < TILES * TILES; t++) {
            color.fromHsv(t * 360f / (TILES * TILES), 0.55f, 1f);
            color.a = 1f;
            pixmap.setColor(color);
            final int cx = (t % TILES) * TILE + TILE / 2, cy = (t / TILES) * TILE + TILE / 2;
            pixmap.fillTriangle(cx, cy - TILE / 2 + 2, cx - TILE / 2 + 3, cy + TILE / 2 - 3, cx + TILE / 2 - 3, cy + TILE / 2 - 3);
        }
        atlas = new Texture(pixmap);
        pixmap.dispose();
        atlas.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        regions = new TextureRegion[TILES * TILES];
        for (int t = 0; t < regions.length; t++) regions[t] = new TextureRegion(atlas, (t % TILES) * TILE, (t / TILES) * TILE, TILE, TILE);

        final java.util.Random random = new java.util.Random(5);
        x = new float[count];
        y = new float[count];
        vx = new float[count];
        vy = new float[count];
        angle = new float[count];
        spin = new float[count];
        size = new float[count];
        tint = new float[count];
        region = new int[count];
        final int w = Math.max(Gdx.graphics.getWidth(), 1), h = Math.max(Gdx.graphics.getHeight(), 1);
        for (int i = 0; i < count; i++) {
            x[i] = random.nextFloat() * w;
            y[i] = random.nextFloat() * h;
            vx[i] = (random.nextFloat() - 0.5f) * 200f;
            vy[i] = (random.nextFloat() - 0.5f) * 200f;
            angle[i] = random.nextFloat() * 360f;
            spin[i] = (random.nextFloat() - 0.5f) * 360f;
            size[i] = 6f + random.nextFloat() * 10f;
            tint[i] = Color.toFloatBits(0.55f + random.nextFloat() * 0.45f, 0.55f + random.nextFloat() * 0.45f, 0.55f + random.nextFloat() * 0.45f, 0.4f + random.nextFloat() * 0.6f);
            region[i] = random.nextInt(regions.length);
        }
        hud = new Hud();
    }

    @Override public void resize (int width, int height) {
    }

    @Override public void render () {
        if (Controls.justPressed(Input.Keys.SPACE)) paused = !paused;
        final int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        final float dt = paused ? 0f : Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        for (int i = 0; i < count; i++) {
            float px = x[i] + vx[i] * dt, py = y[i] + vy[i] * dt;
            if (px < 0 || px > w) vx[i] = -vx[i];
            if (py < 0 || py > h) vy[i] = -vy[i];
            x[i] = MathUtils.clamp(px, 0, w);
            y[i] = MathUtils.clamp(py, 0, h);
            angle[i] += spin[i] * dt;
        }
        Gdx.gl.glClearColor(0.03f, 0.03f, 0.06f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        batch.begin();
        for (int i = 0; i < count; i++) {
            batch.setPackedColor(tint[i]);
            final float s = size[i];
            batch.draw(regions[region[i]], x[i] - s / 2, y[i] - s / 2, s / 2, s / 2, s, s, 1f, 1f, angle[i]);
        }
        batch.end();
        hud.draw(title(), summary(), notes(), "sprites: " + count, "batch flushes this frame: " + renderCalls());
    }

    private int renderCalls () {
        return batch instanceof SpriteBatch ? ((SpriteBatch)batch).renderCalls : ((VulkanSpriteBatch)batch).renderCalls;
    }

    @Override public void pause () {}

    @Override public void resume () {}

    @Override public void dispose () {
        hud.dispose();
        batch.dispose();
        atlas.dispose();
    }
}
