package io.github.slebed.gdxvulkan.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.ParticleEffect;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.RandomXS128;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.List;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * The Gate 2 scenes, written once against the stock {@link Batch} interface so the very same code drives stock {@code SpriteBatch} on
 * OpenGL (the reference) and {@code VulkanSpriteBatch} on gdx-vulkan. Nothing here knows which backend it runs on.
 */
final class Gate2Scenes {
    static final int SIZE = 256;
    static final float[] BACKGROUND = {0.18f, 0.2f, 0.26f, 1f};

    interface Scene {
        String name();

        /** Loads whatever the scene needs (skins, fonts, ...). Runs with {@code Gdx.gl} already pointing at the backend under test. */
        void prepare(FileHandle data, int width, int height) throws Exception;

        /** Draws one frame. The screen has been cleared to {@link #BACKGROUND}. */
        void draw(Batch batch, int width, int height) throws Exception;

        void dispose();
    }

    /** A scene drawn with a ShapeRenderer instead of a Batch. */
    interface ShapeScene extends Scene {
        void drawShapes(ShapeApi shapes, int width, int height) throws Exception;
    }

    private Gate2Scenes() {}

    static Scene[] all() {
        return new Scene[] {new Scene2dScene(), new WindowScene(), new BitmapFontScene(), new AtlasScene(), new ParticleScene(), new FreeTypeScene(),
            new ShapesScene()};
    }

    /** Points, lines, filled and outlined shapes, alpha blended: what stock's ShapeRenderer is used for (debug drawing, UI backdrops, charts). */
    private static final class ShapesScene implements ShapeScene {
        public String name() { return "gate2_shapes"; }

        public void prepare(FileHandle data, int w, int h) {}

        public void draw(Batch batch, int w, int h) {
            throw new UnsupportedOperationException("a shape scene is drawn with drawShapes");
        }

        public void drawShapes(ShapeApi s, int w, int h) {
            Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
            s.begin(ShapeApi.Kind.Filled);
            s.setColor(1f, 0.3f, 0.2f, 1f);
            s.rect(16, 150, 90, 90);
            s.setColor(0.2f, 0.6f, 1f, 0.6f); // translucent: shows the first rectangle through it
            s.rect(60, 120, 100, 100);
            s.setColor(0.3f, 1f, 0.4f, 1f);
            s.circle(200, 190, 36, 40);
            s.setColor(1f, 0.9f, 0.2f, 1f);
            s.triangle(20, 20, 100, 20, 60, 90);
            s.setColor(0.9f, 0.4f, 1f, 0.8f);
            s.arc(190, 70, 50, 20, 250, 30);
            s.end();

            s.begin(ShapeApi.Kind.Line);
            s.setColor(1f, 1f, 1f, 1f);
            s.rect(8, 8, 240, 240);
            s.line(110, 20, 240, 100);
            s.line(110, 100, 240, 20);
            s.setColor(0.4f, 1f, 1f, 1f);
            s.circle(200, 190, 44, 48);
            s.curve(120, 240, 150, 150, 170, 250, 240, 140, 32);
            s.end();

            s.begin(ShapeApi.Kind.Point);
            s.setColor(1f, 1f, 1f, 1f);
            for (int i = 0; i < 12; i++) s.point(110 + i * 4, 112);
            s.end();
            Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        }

        public void dispose() {}
    }

    private static final class Scene2dScene implements Scene {
        Skin skin;

        public String name() { return "gate2_scene2d"; }

        public void prepare(FileHandle data, int w, int h) {
            skin = new Skin(data.child("uiskin.json"));
        }

        public void draw(Batch batch, int w, int h) {
            Stage stage = new Stage(new ScreenViewport(), batch);
            stage.getViewport().update(w, h, true);
            Table root = new Table(skin);
            root.setFillParent(true);
            root.pad(8);
            root.add(new Label("gdx-vulkan Scene2D", skin)).colspan(2).padBottom(6).row();
            TextButton button = new TextButton("Button", skin);
            root.add(button).pad(2);
            root.add(new CheckBox(" check", skin)).pad(2).row();
            Slider slider = new Slider(0, 10, 1, false, skin);
            slider.setValue(6);
            root.add(slider).colspan(2).fillX().pad(2).row();
            root.add(new TextField("text field", skin)).colspan(2).fillX().pad(2).row();
            List<String> list = new List<String>(skin);
            Array<String> items = new Array<String>();
            for (int i = 1; i <= 30; i++) items.add("Item number " + i); // far taller than the pane: it must clip
            list.setItems(items);
            ScrollPane pane = new ScrollPane(list, skin);
            pane.setFadeScrollBars(false);
            root.add(pane).colspan(2).size(180, 90).pad(4).row();
            stage.addActor(root);
            stage.act(0);
            stage.draw();
            stage.dispose();
        }

        public void dispose() { skin.dispose(); }
    }

    private static final class WindowScene implements Scene {
        Skin skin;

        public String name() { return "gate2_window"; }

        public void prepare(FileHandle data, int w, int h) {
            skin = new Skin(data.child("uiskin.json"));
        }

        public void draw(Batch batch, int w, int h) {
            Stage stage = new Stage(new ScreenViewport(), batch);
            stage.getViewport().update(w, h, true);
            Window win = new Window("A window", skin);
            win.setBounds(20, 40, 210, 170);
            win.add(new Label("Inside a window", skin)).row();
            Table inner = new Table(skin);
            for (int i = 0; i < 12; i++) inner.add(new TextButton("Button " + i, skin)).row();
            ScrollPane pane = new ScrollPane(inner, skin);
            pane.setFadeScrollBars(false);
            pane.setScrollingDisabled(true, false);
            win.add(pane).size(180, 100);
            stage.addActor(win);
            stage.act(0);
            stage.draw();
            stage.dispose();
        }

        public void dispose() { skin.dispose(); }
    }

    private static final class BitmapFontScene implements Scene {
        BitmapFont font;

        public String name() { return "gate2_bitmapfont"; }

        public void prepare(FileHandle data, int w, int h) {
            font = new BitmapFont(data.child("lsans-15.fnt"));
        }

        public void draw(Batch batch, int w, int h) {
            batch.begin();
            font.setColor(Color.WHITE);
            font.draw(batch, "BitmapFont: lsans-15", 8, SIZE - 8);
            font.setColor(Color.YELLOW);
            font.draw(batch, "Coloured text, same page, one draw call on Tier A.", 8, SIZE - 32, SIZE - 16, Align.left, true);
            font.setColor(Color.CYAN);
            GlyphLayout layout = new GlyphLayout(font, "Centred");
            font.draw(batch, layout, (SIZE - layout.width) / 2, 120);
            font.getData().setScale(2f);
            font.setColor(1f, 0.5f, 0.2f, 1f);
            font.draw(batch, "Scaled 2x", 8, 80);
            font.getData().setScale(1f);
            batch.end();
        }

        public void dispose() { font.dispose(); }
    }

    private static final class AtlasScene implements Scene {
        TextureAtlas atlas;

        public String name() { return "gate2_atlas"; }

        public void prepare(FileHandle data, int w, int h) {
            atlas = new TextureAtlas(data.child("uiskin.atlas"));
        }

        public void draw(Batch batch, int w, int h) {
            batch.begin();
            float x = 8, y = SIZE - 40;
            for (TextureAtlas.AtlasRegion r : atlas.getRegions()) { // every region of the skin's atlas, laid out left to right
                if (r.getRegionWidth() > 64 || r.getRegionHeight() > 64) continue;
                if (x + r.getRegionWidth() > SIZE - 8) {
                    x = 8;
                    y -= 36;
                }
                if (y < 4) break;
                batch.draw(r, x, y);
                x += r.getRegionWidth() + 4;
            }
            batch.end();
        }

        public void dispose() { atlas.dispose(); }
    }

    private static final class ParticleScene implements Scene {
        ParticleEffect effect;

        public String name() { return "gate2_particles"; }

        public void prepare(FileHandle data, int w, int h) {
            MathUtils.random = new RandomXS128(1234); // the effect is random; make it repeatable
            effect = new ParticleEffect();
            effect.load(data.child("test.p"), data);
            effect.setPosition(SIZE / 2f, SIZE / 2f);
            effect.start();
            for (int i = 0; i < 40; i++) effect.update(1f / 30f);
        }

        public void draw(Batch batch, int w, int h) {
            batch.begin();
            effect.draw(batch);
            batch.end();
        }

        public void dispose() { effect.dispose(); }
    }

    private static final class FreeTypeScene implements Scene {
        FreeTypeFontGenerator generator;
        BitmapFont font;

        public String name() { return "gate2_freetype"; }

        public void prepare(FileHandle data, int w, int h) {
            generator = new FreeTypeFontGenerator(data.child("lsans.ttf"));
            FreeTypeFontParameter p = new FreeTypeFontParameter();
            p.size = 18;
            p.incremental = true; // glyphs are rasterised on first use: while the batch is running
            p.minFilter = TextureFilter.Nearest;
            p.magFilter = TextureFilter.Nearest;
            font = generator.generateFont(p);
        }

        public void draw(Batch batch, int w, int h) {
            batch.begin();
            font.setColor(Color.WHITE);
            font.draw(batch, "Incremental FreeType", 8, SIZE - 10); // creates these glyphs while the batch is open
            font.setColor(Color.GREEN);
            font.draw(batch, "More glyphs: QJZXW 0123456789", 8, SIZE - 40); // and these, after quads are already queued
            font.setColor(Color.ORANGE);
            font.draw(batch, "Incremental again!?", 8, SIZE - 70);
            batch.end();
        }

        public void dispose() {
            font.dispose();
            generator.dispose();
        }
    }
}
