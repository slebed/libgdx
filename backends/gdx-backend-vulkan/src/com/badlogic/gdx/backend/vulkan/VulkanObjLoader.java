package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.AsynchronousAssetLoader;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.utils.Array;

public class VulkanObjLoader extends AsynchronousAssetLoader<VulkanModel, VulkanObjLoader.VulkanObjParameters> {

    // This loader is created once in the constructor and reused.
    private final com.badlogic.gdx.graphics.g3d.loader.ObjLoader internalLoader;
    private ModelData modelData;

    public VulkanObjLoader(FileHandleResolver resolver) {
        super(resolver);
        // CORRECTED: Create the internal loader here, where the resolver is accessible.
        this.internalLoader = new com.badlogic.gdx.graphics.g3d.loader.ObjLoader(resolver);
    }

    @Override
    public Array<AssetDescriptor> getDependencies(String fileName, FileHandle file, VulkanObjParameters parameter) {
        com.badlogic.gdx.graphics.g3d.loader.ObjLoader.ObjLoaderParameters objParams = new com.badlogic.gdx.graphics.g3d.loader.ObjLoader.ObjLoaderParameters();
        if (parameter != null) objParams.flipV = parameter.flipV;
        // Now we use the internalLoader field.
        return internalLoader.getDependencies(fileName, file, objParams);
    }

    @Override
    public void loadAsync(AssetManager manager, String fileName, FileHandle file, VulkanObjParameters parameter) {
        com.badlogic.gdx.graphics.g3d.loader.ObjLoader.ObjLoaderParameters objParams = new com.badlogic.gdx.graphics.g3d.loader.ObjLoader.ObjLoaderParameters();
        if (parameter != null) objParams.flipV = parameter.flipV;
        // Now we use the internalLoader field.
        this.modelData = internalLoader.loadModelData(file, objParams);
    }

    @Override
    public VulkanModel loadSync(AssetManager manager, String fileName, FileHandle file, VulkanObjParameters parameter) {
        if (modelData == null) return null;
        VulkanModel model = new VulkanModel(modelData, manager);
        this.modelData = null;
        return model;
    }

    static public class VulkanObjParameters extends AssetLoaderParameters<VulkanModel> {
        public boolean flipV = true;
    }
}