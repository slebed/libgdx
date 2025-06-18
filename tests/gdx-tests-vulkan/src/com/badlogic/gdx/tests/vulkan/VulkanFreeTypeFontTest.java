package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backend.vulkan.VulkanGraphics;
import com.badlogic.gdx.backend.vulkan.VulkanPixmapPacker;
import com.badlogic.gdx.backend.vulkan.VulkanSpriteBatchInstanced;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.tests.utils.GdxTest;

public class VulkanFreeTypeFontTest extends GdxTest {
    private static final String TAG = "VulkanFreeTypeFontTest";
    private BitmapFont font;
    private VulkanPixmapPacker fontPacker;
    private VulkanGraphics gfx;
    private Batch batch;

    @Override
    public void create() {
        gfx = (VulkanGraphics) Gdx.graphics;
        font = createFont("data/DroidSerif-Regular.ttf", 24);
        //batch = new VulkanSpriteBatchInstanced(gfx, 1024);
    }

    private BitmapFont createFont(String fontName, int size) {
        Gdx.app.log("DEMO_SETUP", "Creating FreeTypeFont with VulkanPixmapPacker...");
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(Gdx.files.internal(fontName));
        FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();


        // Pass the correct graphics context to our custom packer.
        this.fontPacker = new VulkanPixmapPacker(1024, 1024, Pixmap.Format.RGBA8888, 2, false);
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
        return generatedFont;
    }

    @Override
    public void render() {
        batch.begin();
        String stats = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        font.draw(batch, stats, Gdx.graphics.getWidth() / 2, Gdx.graphics.getHeight() / 2);
        batch.end();
    }

    @Override
    public void dispose() {
        if (font != null) {
            font.dispose();
        }
        if (fontPacker != null) {
            fontPacker.dispose();
        }
    }
}
