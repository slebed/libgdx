package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
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
        for (final Page page : getPages()) { // Page must be final to be used in inner class
            try {
                Texture texture = (Texture) pageTextureField.get(page);
                boolean isDirty = (boolean) pageDirtyField.get(page);

                if (texture != null && !isDirty) {
                    continue;
                }

                final Pixmap pixmap = (Pixmap) pageImageField.get(page); // Pixmap must also be final
                if (pixmap == null) {
                    Gdx.app.error("VulkanPixmapPacker", "Page's pixmap is null, cannot update/create texture.");
                    continue;
                }

                // *** FIX #1: The 'disposePixmap' argument MUST be false. ***
                PixmapTextureData textureData = new PixmapTextureData(pixmap, pixmap.getFormat(), useMipMaps, false);

                if (texture != null) {
                    texture.load(textureData);
                } else {
                    // *** FIX #2: Override dispose() to also dispose the page's pixmap. ***
                    texture = new Texture(textureData) {
                        @Override
                        public void dispose() {
                            super.dispose();
                            pixmap.dispose(); // This now disposes the page's underlying pixmap
                        }
                    };

                    texture.setFilter(minFilter, magFilter);
                    pageTextureField.set(page, texture);
                }

                pageDirtyField.set(page, false);

            } catch (Exception e) {
                throw new GdxRuntimeException("Error updating page textures in VulkanPixmapPacker", e);
            }
        }
    }
}

/*

*/
/**
 * A custom PixmapPacker that creates VulkanTexture instances for its pages.
 * This version takes a VulkanGraphics context to ensure textures are created correctly
 * in a multi-window environment. It uses reflection to access package-private fields.
 *//*

public class VulkanPixmapPacker extends PixmapPacker {

    private final VulkanGraphics gfx; // Store the correct graphics context
    private final Field pageTextureField;
    private final Field pageImageField;
    private final Field pageDirtyField;

    public VulkanPixmapPacker(VulkanGraphics gfx, int width, int height, Pixmap.Format format, int padding, boolean duplicateBorder) {
        super(width, height, format, padding, duplicateBorder);
        this.gfx = gfx; // Assign the graphics context
        try {
            // Get the 'texture', 'image', and 'dirty' fields from the Page class once and cache them.
            pageTextureField = Page.class.getDeclaredField("texture");
            pageTextureField.setAccessible(true); // Bypass the package-private restriction

            pageImageField = Page.class.getDeclaredField("image");
            pageImageField.setAccessible(true);

            pageDirtyField = Page.class.getDeclaredField("dirty");
            pageDirtyField.setAccessible(true);

        } catch (NoSuchFieldException e) {
            throw new GdxRuntimeException("Failed to get required fields from PixmapPacker.Page via reflection. " +
                    "This might happen if the LibGDX version has changed.", e);
        }
    }

    */
/**
     * Overridden to create and update VulkanTexture instances for font pages, bypassing the default Texture creation.
     * This logic now correctly handles dirty pages by re-uploading the pixmap data.
     *//*

    @Override
    public synchronized void updatePageTextures(TextureFilter minFilter, TextureFilter magFilter, boolean useMipMaps) {
        for (Page page : getPages()) {
            try {
                Texture texture = (Texture) pageTextureField.get(page);
                boolean isDirty = (boolean) pageDirtyField.get(page);

                if (texture != null) {
                    if (!isDirty) continue; // Page is not dirty, no update needed.

                    // If page IS dirty, re-upload the pixmap data to the existing texture.
                    // This is the crucial step that was missing.
                    Pixmap pixmap = (Pixmap) pageImageField.get(page);
                    // The 'load' method on our VulkanTexture will trigger the GLES-emulated upload path.
                    texture.load(new PixmapTextureData(pixmap, pixmap.getFormat(), false, false));

                } else {
                    // Texture doesn't exist for this page, so create one.
                    Pixmap pixmap = (Pixmap) pageImageField.get(page);
                    if (pixmap == null) {
                        Gdx.app.error("VulkanPixmapPacker", "Page's pixmap is null, cannot create texture.");
                        continue;
                    }

                    // Create our custom VulkanTexture using the direct constructor that takes a graphics context.
                    VulkanTexture newTexture = new VulkanTexture(pixmap, this.gfx);
                    newTexture.setFilter(minFilter, magFilter);
                    newTexture.setWrap(TextureWrap.ClampToEdge, TextureWrap.ClampToEdge);

                    // Use reflection to set the new VulkanTexture
                    pageTextureField.set(page, newTexture);
                }

                // Mark the page as clean after the update/creation.
                pageDirtyField.set(page, false);

            } catch (IllegalAccessException e) {
                // This should not happen because we call setAccessible(true) in the constructor.
                throw new GdxRuntimeException("Failed to access PixmapPacker.Page fields via reflection.", e);
            }
        }
    }
}
*/
