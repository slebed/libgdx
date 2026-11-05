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
 * The same {@code Batch} code on both backends: thousands of moving sprites, each drawn from one of 128 small textures, in an order a game does not control (particles of
 * many kinds, UI icons, characters from separate files). The only line that differs is the batch: {@code new SpriteBatch()} on OpenGL, {@code new VulkanSpriteBatch()}
 * on gdx-vulkan; porting is a rename.
 *
 * <p>Stock libGDX has to flush the batch every time the texture changes, which is a draw call per sprite here; gdx-vulkan binds textures through a table and draws
 * the lot in a few calls. The mode (key L, or a tap on the right half) shows the two things a careful developer does about it: sorting the sprites by texture, and packing
 * the textures into one atlas. With an atlas the two backends are close, which is the honest size of the gain for a game that already packs its art.
 */
public final class SpritesDemo implements Demo {
    private static final int TEXTURES = 128, TILE = 32, ATLAS_COLUMNS = 16;
    private static final String[] MODES = {"128 separate textures, in the order the sprites come", "128 separate textures, sorted by texture", "one texture atlas"};

    private final int count = Integer.getInteger("demo.sprites", Controls.touch() ? 10_000 : 20_000);
    private Batch batch;
    private Texture[] textures;
    private Texture atlas;
    private TextureRegion[] regions;
    private float[] x, y, vx, vy;
    private int[] textureOf, sortedOrder;
    private int mode = Integer.getInteger("demo.mode", 0);
    private boolean paused;
    private Hud hud;

    @Override public String title () {
        return "Sprites from many textures";
    }

    @Override public String summary () {
        return count + " sprites move on the CPU and are drawn with the batch every frame, each from one of " + TEXTURES + " small textures. The drawing code is the same on both "
            + "backends; the batch is " + (Backend.vulkan ? "VulkanSpriteBatch" : "stock SpriteBatch") + ".";
    }

    @Override public String[] notes () {
        return new String[] {"Stock libGDX draws again every time the texture changes; gdx-vulkan does not have to.",
            (Controls.touch() ? "A tap on the right half of the screen" : "L") + " cycles the mode: scattered, sorted by texture, one atlas. With an atlas the two are close.",
            (Controls.touch() ? "A tap on the left half" : "Space") + " pauses." + (Controls.touch() ? "" : " C starts the stock OpenGL version beside this window.")};
    }

    @Override public String openGlCounterpart () {
        return "sprites";
    }

    @Override public void create () {
        batch = Backend.vulkan ? new VulkanSpriteBatch() : new SpriteBatch();
        textures = new Texture[TEXTURES];
        final int rows = (TEXTURES + ATLAS_COLUMNS - 1) / ATLAS_COLUMNS;
        final Pixmap atlasPixmap = new Pixmap(ATLAS_COLUMNS * TILE, rows * TILE, Pixmap.Format.RGBA8888);
        regions = new TextureRegion[TEXTURES];
        for (int t = 0; t < TEXTURES; t++) {
            final Pixmap tile = tile(t);
            textures[t] = new Texture(tile);
            atlasPixmap.drawPixmap(tile, (t % ATLAS_COLUMNS) * TILE, (t / ATLAS_COLUMNS) * TILE);
            tile.dispose();
        }
        atlas = new Texture(atlasPixmap);
        atlasPixmap.dispose();
        for (int t = 0; t < TEXTURES; t++) regions[t] = new TextureRegion(atlas, (t % ATLAS_COLUMNS) * TILE, (t / ATLAS_COLUMNS) * TILE, TILE, TILE);

        final java.util.Random random = new java.util.Random(11);
        x = new float[count];
        y = new float[count];
        vx = new float[count];
        vy = new float[count];
        textureOf = new int[count];
        final int w = Math.max(Gdx.graphics.getWidth(), 1), h = Math.max(Gdx.graphics.getHeight(), 1);
        for (int i = 0; i < count; i++) {
            x[i] = random.nextFloat() * w;
            y[i] = random.nextFloat() * h;
            vx[i] = (random.nextFloat() - 0.5f) * 240f;
            vy[i] = (random.nextFloat() - 0.5f) * 240f;
            textureOf[i] = random.nextInt(TEXTURES);
        }
        // The same sprites in texture order, for the sorted mode (a counting sort).
        sortedOrder = new int[count];
        final int[] start = new int[TEXTURES + 1];
        for (int i = 0; i < count; i++) start[textureOf[i] + 1]++;
        for (int t = 0; t < TEXTURES; t++) start[t + 1] += start[t];
        for (int i = 0; i < count; i++) sortedOrder[start[textureOf[i]]++] = i;
        hud = new Hud();
    }

    /** A 32 x 32 disc with a ring and a notch, its colour and its notch angle depending on the index: 128 textures that are all different. */
    private static Pixmap tile (int index) {
        final Pixmap pixmap = new Pixmap(TILE, TILE, Pixmap.Format.RGBA8888);
        final Color color = new Color();
        color.fromHsv(index * 360f / TEXTURES, 0.75f, 0.95f);
        color.a = 1f;
        pixmap.setColor(color);
        pixmap.fillCircle(TILE / 2, TILE / 2, TILE / 2 - 1);
        pixmap.setColor(color.mul(0.45f, 0.45f, 0.45f, 1f));
        pixmap.fillCircle(TILE / 2, TILE / 2, TILE / 4);
        final float angle = index * 0.37f;
        pixmap.setColor(1f, 1f, 1f, 1f);
        pixmap.fillCircle(TILE / 2 + Math.round(MathUtils.cos(angle) * TILE * 0.32f), TILE / 2 + Math.round(MathUtils.sin(angle) * TILE * 0.32f), 3);
        return pixmap;
    }

    @Override public void resize (int width, int height) {
    }

    @Override public void render () {
        if (Controls.justPressed(Input.Keys.SPACE)) paused = !paused;
        if (Controls.justPressed(Input.Keys.L)) mode = (mode + 1) % MODES.length;
        final int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        final float dt = paused ? 0f : Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        final float size = Math.max(h / 30f, 12f);
        for (int i = 0; i < count; i++) {
            float px = x[i] + vx[i] * dt, py = y[i] + vy[i] * dt;
            if (px < 0 || px > w) vx[i] = -vx[i];
            if (py < 0 || py > h) vy[i] = -vy[i];
            x[i] = MathUtils.clamp(px, 0, w);
            y[i] = MathUtils.clamp(py, 0, h);
        }

        Gdx.gl.glClearColor(0.06f, 0.07f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        batch.begin();
        if (mode == 2) {
            for (int i = 0; i < count; i++) batch.draw(regions[textureOf[i]], x[i], y[i], size, size);
        } else {
            final int[] order = mode == 1 ? sortedOrder : null;
            for (int k = 0; k < count; k++) {
                final int i = order != null ? order[k] : k;
                batch.draw(textures[textureOf[i]], x[i], y[i], size, size);
            }
        }
        batch.end();
        hud.draw(title(), summary(), notes(), "mode: " + MODES[mode], "sprites: " + count + ", batch flushes this frame: " + renderCalls());
    }

    /** How many times the batch handed its vertices to the GPU this frame (every texture change and every full buffer is one). */
    private int renderCalls () {
        return batch instanceof SpriteBatch ? ((SpriteBatch)batch).renderCalls : ((VulkanSpriteBatch)batch).renderCalls;
    }

    @Override public void pause () {}

    @Override public void resume () {}

    @Override public void dispose () {
        hud.dispose();
        batch.dispose();
        for (Texture t : textures) t.dispose();
        atlas.dispose();
    }
}
