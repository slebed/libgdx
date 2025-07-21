package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.AsynchronousAssetLoader;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.ModelLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.UBJsonReader;

public class VulkanG3dbLoader extends AsynchronousAssetLoader<VulkanModel, VulkanG3dbLoader.VulkanG3dbParameters> {

    // This loader is created once in the constructor and reused.
    private final G3dModelLoader internalLoader;
    private ModelData modelData;

    public VulkanG3dbLoader(FileHandleResolver resolver) {
        super(resolver);
        // CORRECTED: Create the internal loader here, where the resolver is accessible.
        this.internalLoader = new G3dModelLoader(new UBJsonReader(), resolver);
    }

    @Override
    public Array<AssetDescriptor> getDependencies(String fileName, FileHandle file, VulkanG3dbParameters parameter) {
        // Now we use the internalLoader field.
        return internalLoader.getDependencies(fileName, file, new ModelLoader.ModelParameters());
    }

    @Override
    public void loadAsync(AssetManager manager, String fileName, FileHandle file, VulkanG3dbParameters parameter) {
        // Now we use the internalLoader field.
        this.modelData = internalLoader.loadModelData(file, null);
    }

    @Override
    public VulkanModel loadSync(AssetManager manager, String fileName, FileHandle file, VulkanG3dbParameters parameter) {
        if (modelData == null) return null;
        VulkanModel model = new VulkanModel(modelData, manager);
        this.modelData = null;
        return model;
    }

    static public class VulkanG3dbParameters extends AssetLoaderParameters<VulkanModel> {}
}