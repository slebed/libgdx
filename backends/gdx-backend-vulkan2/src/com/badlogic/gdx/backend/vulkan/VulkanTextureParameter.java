package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture; // For Filter/Wrap enums

/** Parameters for {@link VulkanTextureLoader}. */
public class VulkanTextureParameter extends AssetLoaderParameters<VulkanTexture> {
    /** The format of the final texture. Uses the source image's format if null. **/
    public Pixmap.Format format = null; // Example: You might not need this if loadFromFile always uses SRGB etc.
    /** Whether to generate mipmaps. **/
    public boolean genMipMaps = false; // Example: VulkanTexture.loadFromFile doesn't support this yet
    /** The texture filter setting for minification **/
    public Texture.TextureFilter minFilter = Texture.TextureFilter.Nearest; // Example default
    /** The texture filter setting for magnification **/
    public Texture.TextureFilter magFilter = Texture.TextureFilter.Nearest; // Example default
    /** The texture wrap setting for u **/
    public Texture.TextureWrap wrapU = Texture.TextureWrap.ClampToEdge; // Example default
    /** The texture wrap setting for v **/
    public Texture.TextureWrap wrapV = Texture.TextureWrap.ClampToEdge; // Example default

    // Add any other Vulkan-specific parameters if needed
}