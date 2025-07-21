package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.AsynchronousAssetLoader;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonReader;

public class VulkanModelLoader extends AsynchronousAssetLoader<VulkanModel, VulkanModelLoader.VulkanModelParameters> {

    // This loader is created once in the constructor and reused.
    private final G3dModelLoader internalLoader;
    private ModelData modelData;

    public VulkanModelLoader(FileHandleResolver resolver) {
        super(resolver);
        // CORRECTED: Create the internal loader here, where the resolver is accessible.
        this.internalLoader = new G3dModelLoader(new JsonReader(), resolver);
    }

    @Override
    public Array<AssetDescriptor> getDependencies(String fileName, FileHandle file, VulkanModelParameters parameter) {
        // This method can be removed as G3dModelLoader's getDependencies is what we want,
        // but for clarity on how to parse dependencies manually, it can stay.
        // For simplicity, let's delegate to the internal loader now.
        return internalLoader.getDependencies(fileName, file, null);
    }

    @Override
    public void loadAsync(AssetManager manager, String fileName, FileHandle file, VulkanModelParameters parameter) {
        this.modelData = internalLoader.loadModelData(file, null);
    }

    @Override
    public VulkanModel loadSync(AssetManager manager, String fileName, FileHandle file, VulkanModelParameters parameter) {
        if (modelData == null) return null;
        VulkanModel model = new VulkanModel(modelData, manager);
        this.modelData = null;
        return model;
    }

    static public class VulkanModelParameters extends AssetLoaderParameters<VulkanModel> {}
}