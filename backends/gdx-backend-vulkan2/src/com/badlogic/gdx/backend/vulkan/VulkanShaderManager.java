package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the loading, caching, and cleanup of Vulkan Shader Modules.
 */
public class VulkanShaderManager implements Disposable {
    private static final String TAG = "VulkanShaderManager";
    private static final boolean DEBUG = false; // Or read from a config

    private final VkDevice rawDevice;
    private final Map<String, Long> shaderModuleCache; // Cache: File path or unique ID -> Module Handle

    /**
     * Constructs a VulkanShaderManager.
     * @param rawDevice The raw Vulkan logical device.
     */
    public VulkanShaderManager(VkDevice rawDevice) {
        this.rawDevice = rawDevice;
        this.shaderModuleCache = new HashMap<>();
        if (DEBUG) Gdx.app.log(TAG, "Initialized.");
    }

    /**
     * Loads a shader module from a file or retrieves it from the cache.
     * The file is expected to contain SPIR-V bytecode.
     *
     * @param shaderFile FileHandle pointing to the SPIR-V shader file.
     * @return The handle of the created or cached VkShaderModule.
     * @throws GdxRuntimeException if the file cannot be read or shader module creation fails.
     */
    public synchronized long getShaderModule(FileHandle shaderFile) {
        if (shaderFile == null) {
            throw new IllegalArgumentException("Shader FileHandle cannot be null.");
        }
        String pathKey = shaderFile.path(); // Use the file path as the cache key

        Long cachedHandle = shaderModuleCache.get(pathKey);
        if (cachedHandle != null) {
            if (DEBUG) Gdx.app.debug(TAG, "Shader module cache hit for: " + pathKey);
            return cachedHandle;
        }

        if (DEBUG) Gdx.app.log(TAG, "Loading shader module from: " + pathKey);
        try {
            ByteBuffer shaderCode = readFileToByteBuffer(shaderFile);
            long moduleHandle = createShaderModuleInternal(shaderCode);

            shaderModuleCache.put(pathKey, moduleHandle);
            if (DEBUG) Gdx.app.log(TAG, "Shader module loaded and cached: " + moduleHandle + " [" + pathKey + "]");
            return moduleHandle;
        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to load shader module: " + pathKey, e);
        }
    }

    /**
     * Helper method to read a file into a direct ByteBuffer.
     * @param fileHandle The FileHandle to read.
     * @return A ByteBuffer containing the file's contents, flipped for reading.
     */
    private ByteBuffer readFileToByteBuffer(FileHandle fileHandle) {
        if (!fileHandle.exists()) {
            throw new GdxRuntimeException("Shader file not found: " + fileHandle.path() + " (type: " + fileHandle.type() + ")");
        }
        byte[] bytes = fileHandle.readBytes();
        ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
        buffer.put(bytes);
        buffer.flip(); // Prepare for reading by Vulkan
        return buffer;
    }

    /**
     * Internal method to create a VkShaderModule from SPIR-V bytecode.
     * @param code ByteBuffer containing the SPIR-V code.
     * @return The handle of the created VkShaderModule.
     */
    private long createShaderModuleInternal(ByteBuffer code) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code); // Pass the ByteBuffer directly

            LongBuffer pShaderModule = stack.mallocLong(1);
            vkCheck(vkCreateShaderModule(rawDevice, createInfo, null, pShaderModule),
                    "Failed to create shader module.");
            return pShaderModule.get(0);
        }
    }

    /**
     * Disposes all cached shader modules.
     * Should be called during application cleanup.
     */
    @Override
    public void dispose() {
        if (DEBUG) Gdx.app.log(TAG, "Disposing VulkanShaderManager...");
        if (DEBUG) Gdx.app.log(TAG, "Cleaning up cached shader modules (" + shaderModuleCache.size() + ")...");

        for (Map.Entry<String, Long> entry : shaderModuleCache.entrySet()) {
            long moduleHandle = entry.getValue();
            if (moduleHandle != VK_NULL_HANDLE) {
                if (DEBUG) Gdx.app.log(TAG, "  Destroying shader module: " + moduleHandle + " (for " + entry.getKey() + ")");
                vkDestroyShaderModule(rawDevice, moduleHandle, null);
            }
        }
        shaderModuleCache.clear();
        if (DEBUG) Gdx.app.log(TAG, "Shader module cache cleared.");
        if (DEBUG) Gdx.app.log(TAG, "VulkanShaderManager disposed.");
    }
}
