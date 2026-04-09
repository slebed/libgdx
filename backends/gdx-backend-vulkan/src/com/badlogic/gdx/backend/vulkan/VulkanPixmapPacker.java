package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.lang.reflect.Field;

public class VulkanPixmapPacker extends PixmapPacker {

    private final Field pageTextureField;
    private final Field pageImageField;
    private final Field pageDirtyField;

    public VulkanPixmapPacker(int width, int height, Pixmap.Format format, int padding, boolean duplicateBorder) {
        super(width, height, format, padding, duplicateBorder);
        try {
            pageTextureField = Page.class.getDeclaredField("texture");
            pageTextureField.setAccessible(true);

            pageImageField = Page.class.getDeclaredField("image");
            pageImageField.setAccessible(true);

            pageDirtyField = Page.class.getDeclaredField("dirty");
            pageDirtyField.setAccessible(true);

        } catch (NoSuchFieldException e) {
            throw new GdxRuntimeException("Failed to get required fields from PixmapPacker.Page via reflection.", e);
        }
    }

    @Override
    public synchronized void updatePageTextures(TextureFilter minFilter, TextureFilter magFilter, boolean useMipMaps) {
        for (Page page : getPages()) {
            try {
                Texture texture = (Texture) pageTextureField.get(page);
                boolean isDirty = (boolean) pageDirtyField.get(page);

                if (texture != null && !isDirty) {
                    continue;
                }

                Pixmap pixmap = (Pixmap) pageImageField.get(page);
                if (pixmap == null) {
                    Gdx.app.error("VulkanPixmapPacker", "Page's pixmap is null, cannot update/create texture.");
                    continue;
                }

                if (texture instanceof VulkanTexture) {
                    // Page is dirty — reload new pixmap data into the existing VulkanTexture.
                    // This preserves the Java object identity so BitmapFont glyph regions
                    // that reference this texture remain valid.
                    ((VulkanTexture) texture).reloadFromPixmap(pixmap);
                    texture.setFilter(minFilter, magFilter);
                } else {
                    if (texture != null) texture.dispose();
                    // Create a real VulkanTexture so VulkanSpriteBatch can cast it correctly.
                    VulkanTexture newTexture = new VulkanTexture(pixmap);
                    newTexture.setFilter(minFilter, magFilter);
                    pageTextureField.set(page, newTexture);
                }

                pageDirtyField.set(page, false);

            } catch (Exception e) {
                throw new GdxRuntimeException("Error updating page textures in VulkanPixmapPacker", e);
            }
        }
    }
}
