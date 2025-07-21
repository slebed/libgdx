package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.SynchronousAssetLoader;
import com.badlogic.gdx.assets.loaders.TextureLoader;
import com.badlogic.gdx.assets.loaders.TextureAtlasLoader.TextureAtlasParameter;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData;
import com.badlogic.gdx.utils.Array;

/**
 * AssetLoader for {@link TextureAtlas} instances in a Vulkan context.
 *
 * This loader ensures that the textures loaded for the atlas are {@link VulkanTexture}
 * instances by correctly declaring dependencies to the {@link AssetManager}.
 */
public class VulkanTextureAtlasLoader extends SynchronousAssetLoader<TextureAtlas, TextureAtlasParameter> {

    private TextureAtlasData data;

    public VulkanTextureAtlasLoader(FileHandleResolver resolver) {
        super(resolver);
    }

    @Override
    public TextureAtlas load(AssetManager assetManager, String fileName, FileHandle file, TextureAtlasParameter parameter) {
        // The TextureAtlasData was loaded by getDependencies. Now we get the loaded
        // textures from the asset manager and create the atlas.
        for (TextureAtlasData.Page page : data.getPages()) {
            // The asset manager will have loaded this as a VulkanTexture because of the
            // VulkanTextureLoader registered for the base Texture class.
            page.texture = assetManager.get(page.textureFile.path(), Texture.class);
        }

        TextureAtlas atlas = new TextureAtlas(data);
        data = null; // Clear the data after use
        return atlas;
    }

    @Override
    public Array<AssetDescriptor> getDependencies(String fileName, FileHandle file, TextureAtlasParameter parameter) {
        // Parse the atlas file to find all the texture files it depends on.
        FileHandle atlasFile = resolve(fileName);
        data = new TextureAtlasData(atlasFile, atlasFile.parent(), parameter != null && parameter.flip);

        Array<AssetDescriptor> dependencies = new Array<>();
        for (TextureAtlasData.Page page : data.getPages()) {
            // CORRECTED: Create a TextureParameter for each texture dependency.
            TextureLoader.TextureParameter textureParams = new TextureLoader.TextureParameter();
            textureParams.format = page.format;
            textureParams.genMipMaps = page.useMipMaps;
            textureParams.minFilter = page.minFilter;
            textureParams.magFilter = page.magFilter;
            textureParams.wrapU = page.uWrap;
            textureParams.wrapV = page.vWrap;

            // For each page (texture file) in the atlas, add it as a dependency.
            // The AssetManager will use the appropriate loader (VulkanTextureLoader) for it.
            dependencies.add(new AssetDescriptor<Texture>(page.textureFile, Texture.class, textureParams));
        }
        return dependencies;
    }
}
