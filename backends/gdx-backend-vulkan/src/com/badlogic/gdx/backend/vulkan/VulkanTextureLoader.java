package com.badlogic.gdx.backend.vulkan; // Example package

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.SynchronousAssetLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;

public class VulkanTextureLoader extends SynchronousAssetLoader<VulkanTexture, VulkanTextureParameter> {

    private final VulkanDevice device;
    private final long vmaAllocator;

    public VulkanTextureLoader(FileHandleResolver resolver) {
        super(resolver);
        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
        this.device =  gfx.getVulkanDevice();
        this.vmaAllocator = gfx.getVmaAllocator();
        if (this.device == null || this.vmaAllocator == 0) {
            throw new IllegalArgumentException("VulkanDevice and VMA Allocator cannot be null/zero for VulkanTextureLoader.");
        }
    }

    /**
     * Constructor requires Vulkan context.
     * @param resolver Used to get FileHandles.
     * @param device The VulkanDevice.
     * @param vmaAllocator The VMA Allocator handle.
     */
    public VulkanTextureLoader(FileHandleResolver resolver, VulkanDevice device, long vmaAllocator) {
        super(resolver);
        if (device == null || vmaAllocator == 0) {
            throw new IllegalArgumentException("VulkanDevice and VMA Allocator cannot be null/zero for VulkanTextureLoader.");
        }
        this.device = device;
        this.vmaAllocator = vmaAllocator;
    }

    @Override
    public VulkanTexture load(AssetManager assetManager, String fileName, FileHandle file, VulkanTextureParameter parameter) { // CHANGE PARAMETER TYPE
        // Use parameter if needed in loadFromFile, e.g., parameter.genMipMaps
        try {
            // If VulkanTexture.loadFromFile needs parameters, adapt it or pass them
            return VulkanTexture.loadFromFile(file, device, vmaAllocator);
        } catch (Exception e) {
            throw new GdxRuntimeException("Couldn't load VulkanTexture '" + fileName + "' from file: " + file, e);
        }
    }

    @Override
    public Array<AssetDescriptor> getDependencies(String fileName, FileHandle file, VulkanTextureParameter parameter) { // CHANGE PARAMETER TYPE
        return null;
    }
}