package com.badlogic.gdx.backend.vulkan; // Or your package

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.SynchronousAssetLoader;
import com.badlogic.gdx.assets.loaders.TextureAtlasLoader.TextureAtlasParameter; // Use standard parameter class
import com.badlogic.gdx.assets.loaders.TextureLoader; // For TextureParameter access if needed
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;
// Import your VulkanTextureParameter class



public class VulkanTextureAtlasLoader extends SynchronousAssetLoader<TextureAtlas, TextureAtlasParameter> {

    // NO 'loadedData' member variable here

    public VulkanTextureAtlasLoader(FileHandleResolver resolver) {
        super(resolver);
    }

    @Override
    public TextureAtlas load(AssetManager assetManager, String fileName, FileHandle atlasFile, TextureAtlasParameter parameter) {
        FileHandle imagesDir = atlasFile.parent();
        boolean flip = (parameter != null) ? parameter.flip : false;

        // Load the data AGAIN here in load()
        TextureAtlasData data = new TextureAtlasData(atlasFile, imagesDir, flip);

        try {
            // Get the already-loaded VulkanTextures (dependencies) from the AssetManager
            for (TextureAtlasData.Page page : data.getPages()) {
                if (page.textureFile != null) {

                    // --- CORRECTED LOGIC ---
                    // 1. Resolve the texture file path relative to the atlas file
                    //    using the loader's resolver to get the correct asset name.
                    FileHandle resolvedTextureFile = resolve(page.textureFile.path());
                    String textureAssetId = resolvedTextureFile.path();

                    // 2. Get the VulkanTexture using the resolved path as the ID
                    Texture texture = assetManager.get(textureAssetId, VulkanTexture.class);
                    // --- END CORRECTION ---

                    if (texture == null) {
                        // This might happen if the texture failed to load earlier
                        throw new GdxRuntimeException("Dependency texture not found in AssetManager for page: "
                                + page.textureFile.path() + " (resolved as: " + textureAssetId +") in atlas " + fileName);
                    }
                    // Assign the loaded texture to the page data BEFORE creating the atlas
                    page.texture = texture;
                } else {
                    throw new GdxRuntimeException("TextureAtlas page has null textureFile in atlas " + fileName);
                }
            }

            // Create the TextureAtlas using the data where pages now have VulkanTextures assigned
            TextureAtlas atlas = new TextureAtlas(data);
            return atlas;

        } catch (Exception e) {
            throw new GdxRuntimeException("Error loading TextureAtlas '" + fileName + "'", e);
        }
    }

    @Override
    public Array<AssetDescriptor> getDependencies(String fileName, FileHandle atlasFile, TextureAtlasParameter parameter) {
        FileHandle imagesDir = atlasFile.parent();
        boolean flip = (parameter != null) ? parameter.flip : false;

        // Load data *temporarily* and locally ONLY to find dependencies
        TextureAtlasData data;
        try {
            data = new TextureAtlasData(atlasFile, imagesDir, flip);
        } catch (Exception e) {
            throw new GdxRuntimeException("Error reading texture atlas file for dependencies: " + atlasFile, e);
        }


        Array<AssetDescriptor> dependencies = new Array<>();
        for (TextureAtlasData.Page page : data.getPages()) { // Use local 'data'
            if (page.textureFile != null) {
                // Use the VulkanTextureParameter we created earlier
                VulkanTextureParameter params = new VulkanTextureParameter();
                params.format = page.format;
                params.genMipMaps = page.useMipMaps;
                params.minFilter = page.minFilter;
                params.magFilter = page.magFilter;
                params.wrapU = page.uWrap;
                params.wrapV = page.vWrap;
                // Add other params if needed

                // Add dependency descriptor, specifying VulkanTexture.class and Vulkan params
                dependencies.add(new AssetDescriptor<VulkanTexture>(page.textureFile, VulkanTexture.class, params));
            }
        }
        // DO NOT store 'data' in a member variable
        return dependencies;
    }
}