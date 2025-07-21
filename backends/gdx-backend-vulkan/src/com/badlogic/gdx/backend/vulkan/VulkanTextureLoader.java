package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.SynchronousAssetLoader;
import com.badlogic.gdx.assets.loaders.TextureLoader.TextureParameter;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Array;

/**
 * AssetLoader for VulkanTexture instances.
 *
 * This loader is synchronous because the VulkanTexture constructor performs
 * Vulkan API calls that must be executed on the main rendering thread.
 */
public class VulkanTextureLoader extends SynchronousAssetLoader<Texture, TextureParameter> {

    public VulkanTextureLoader(FileHandleResolver resolver) {
        super(resolver);
    }

    /**
     * This method is called on the main rendering thread. It creates the VulkanTexture,
     * which handles all the complex Vulkan operations like staging buffer creation,
     * data upload, and image view creation.
     */
    @Override
    public Texture load(AssetManager manager, String fileName, FileHandle file, TextureParameter parameter) {
        // The actual loading logic is encapsulated within the VulkanTexture constructor.
        // Because this is a SynchronousAssetLoader, this code will run on the main thread.
        return new VulkanTexture(file);
    }

    /**
     * Specifies any other assets that this asset depends on. For this simple
     * texture loader, there are no dependencies.
     */
    @Override
    public Array<AssetDescriptor> getDependencies(String fileName, FileHandle file, TextureParameter parameter) {
        return null;
    }
}
