package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil; // For memAlloc, memFree
import org.lwjgl.util.shaderc.Shaderc; // Shaderc functions and constants
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanShaderManager implements Disposable {
    private static final String TAG = "VulkanShaderManager";
    private static final boolean DEBUG = true; // Set to true for verbose logging

    private VkDevice rawDevice;
    private Map<String, Long> shaderModuleCache;

    private long shadercCompiler;
    private long shadercOptions;

    public VulkanShaderManager() {
        VulkanApplication vulkanApp = (VulkanApplication) Gdx.app;
        VulkanDevice vulkanDevice = vulkanApp.getVulkanDevice();
        initialize(vulkanDevice.getRawDevice());
    }

    public VulkanShaderManager(VkDevice rawDevice) {
        initialize(rawDevice);
    }

    public void initialize(VkDevice rawDevice){
        this.rawDevice = rawDevice;
        this.shaderModuleCache = new HashMap<>();

        // Initialize Shaderc compiler and options once
        this.shadercCompiler = Shaderc.shaderc_compiler_initialize();
        if (this.shadercCompiler == 0L) { // or if (this.shadercCompiler == NULL)
            throw new GdxRuntimeException("Failed to initialize Shaderc compiler.");
        }

        this.shadercOptions = Shaderc.shaderc_compile_options_initialize();
        if (this.shadercOptions == 0L) { // or if (this.shadercOptions == NULL)
            Shaderc.shaderc_compiler_release(this.shadercCompiler); // Clean up compiler if options fail
            throw new GdxRuntimeException("Failed to initialize Shaderc compile options.");
        }
        // Configure default options if desired (can also be set per-compile)
        // Example: Target Vulkan 1.0, you can adjust this
        Shaderc.shaderc_compile_options_set_target_env(this.shadercOptions, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_0);
        // Shaderc.shaderc_compile_options_set_optimization_level(this.shadercOptions, Shaderc.shaderc_optimization_level_performance);
        // For #include support, you'd use Shaderc.shaderc_compile_options_set_include_callbacks here

        if (DEBUG) Gdx.app.log(TAG, "Initialized with Shaderc.");
    }

    /**
     * Loads a shader module from a pre-compiled SPIR-V file or retrieves it from the cache.
     */
    public synchronized long getShaderModule(FileHandle spirvShaderFile) {
        if (spirvShaderFile == null) {
            throw new IllegalArgumentException("SPIR-V FileHandle cannot be null.");
        }
        String pathKey = spirvShaderFile.path() + "[spirv]"; // Differentiate key

        Long cachedHandle = shaderModuleCache.get(pathKey);
        if (cachedHandle != null) {
            if (DEBUG) Gdx.app.debug(TAG, "SPIR-V shader module cache hit for: " + pathKey);
            return cachedHandle;
        }

        if (DEBUG) Gdx.app.log(TAG, "Loading SPIR-V shader module from: " + pathKey);
        try {
            ByteBuffer shaderCode = readFileToByteBuffer(spirvShaderFile);
            long moduleHandle = createShaderModuleInternal(shaderCode);
            shaderModuleCache.put(pathKey, moduleHandle);
            if (DEBUG) Gdx.app.log(TAG, "SPIR-V shader module loaded and cached: " + moduleHandle + " [" + pathKey + "]");
            return moduleHandle;
        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to load SPIR-V shader module: " + pathKey, e);
        }
    }

    /**
     * Loads a shader module from a GLSL source file.
     * Compiles GLSL to SPIR-V if not found in cache or if cached SPIR-V is outdated.
     *
     * @param glslShaderFile FileHandle pointing to the GLSL shader file.
     * @param shaderKind The kind of shader (e.g., Shaderc.shaderc_vertex_shader, Shaderc.shaderc_fragment_shader).
     * @return The handle of the created or cached VkShaderModule.
     */
    public synchronized long getShaderModuleFromGlsl(FileHandle glslShaderFile, int shaderKind) {
        if (glslShaderFile == null) {
            throw new IllegalArgumentException("GLSL FileHandle cannot be null.");
        }
        String glslPathKey = glslShaderFile.path();

        FileHandle cachedSpirvFile = getCachedSpirvFile(glslShaderFile);
        if (cachedSpirvFile.exists() && cachedSpirvFile.lastModified() >= glslShaderFile.lastModified()) {
            if (DEBUG) Gdx.app.debug(TAG, "Found up-to-date cached SPIR-V for: " + glslPathKey + " at " + cachedSpirvFile.path());
            try {
                return getShaderModule(cachedSpirvFile);
            } catch (Exception e) {
                if (DEBUG) Gdx.app.error(TAG, "Failed to load from cached SPIR-V: " + cachedSpirvFile.path() + ". Recompiling GLSL.", e);
            }
        }

        Long cachedHandle = shaderModuleCache.get(glslPathKey + "[glsl_compiled]");
        if (cachedHandle != null) {
            if (DEBUG) Gdx.app.debug(TAG, "In-memory GLSL compiled shader module cache hit for: " + glslPathKey);
            return cachedHandle;
        }

        if (DEBUG) Gdx.app.log(TAG, "Compiling GLSL to SPIR-V from: " + glslPathKey);
        String glslSource;
        try {
            glslSource = glslShaderFile.readString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to read GLSL shader file: " + glslPathKey, e);
        }

        ByteBuffer spirvCode = null; // This is the heap-allocated copy from compileGlslToSpirvInternal
        long moduleHandle = VK_NULL_HANDLE;

        try {
            spirvCode = compileGlslToSpirvInternal(
                    glslSource,
                    shaderKind,
                    glslShaderFile.name() // For error messages
            );

            // Save the compiled SPIR-V to disk cache
            if (spirvCode != null) {
                try {
                    // ** CORRECTED PART TO GET byte[] from ByteBuffer **
                    byte[] spirvBytes = new byte[spirvCode.remaining()];
                    spirvCode.get(spirvBytes); // Read data into byte array
                    spirvCode.rewind(); // !!! IMPORTANT: Rewind buffer for next read by createShaderModuleInternal

                    cachedSpirvFile.writeBytes(spirvBytes, false);
                    if (DEBUG) Gdx.app.debug(TAG, "Saved compiled SPIR-V to cache: " + cachedSpirvFile.path());
                } catch (Exception e) {
                    Gdx.app.error(TAG, "Failed to write compiled SPIR-V to cache: " + cachedSpirvFile.path(), e);
                    // Continue to create shader module from memory even if cache write fails
                }
            }

            moduleHandle = createShaderModuleInternal(spirvCode); // spirvCode is now rewound and ready
            shaderModuleCache.put(glslPathKey + "[glsl_compiled]", moduleHandle);
            if (DEBUG) Gdx.app.log(TAG, "GLSL shader module compiled and cached: " + moduleHandle + " [" + glslPathKey + "]");
            return moduleHandle;

        } catch (Exception e) {
            // Ensure module handle is not incorrectly cached if creation fails partway
            if (moduleHandle != VK_NULL_HANDLE) {
                // This case should ideally not happen if createShaderModuleInternal throws,
                // but as a safeguard if some other exception occurs after module creation but before caching.
                vkDestroyShaderModule(rawDevice, moduleHandle, null);
            }
            shaderModuleCache.remove(glslPathKey + "[glsl_compiled]"); // Ensure no invalid handle is cached on error
            throw new GdxRuntimeException("Failed to compile or load GLSL shader module: " + glslPathKey, e);
        } finally {
            if (spirvCode != null) {
                MemoryUtil.memFree(spirvCode); // Free the heap-allocated SPIR-V ByteBuffer
            }
        }
    }

    private FileHandle getCachedSpirvFile(FileHandle glslFile) {
        // Example: store in a "shader_cache" subdirectory in local storage
        // Ensure the directory exists
        FileHandle cacheDir = Gdx.files.local("shader_cache/");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return cacheDir.child(glslFile.name() + ".spv");
    }

    private ByteBuffer compileGlslToSpirvInternal(String glslSource, int shaderKind, String sourceNameForErrors) {
        // Shaderc compiler and options are now member variables, initialized in constructor
        long resultHandle = Shaderc.shaderc_compile_into_spv(
                this.shadercCompiler,
                glslSource,
                shaderKind,
                sourceNameForErrors, // Used by Shaderc for error messages
                "main",              // Default entry point name
                this.shadercOptions  // Use pre-configured options
        );

        if (Shaderc.shaderc_result_get_compilation_status(resultHandle) != Shaderc.shaderc_compilation_status_success) {
            String errorMessage = Shaderc.shaderc_result_get_error_message(resultHandle);
            Shaderc.shaderc_result_release(resultHandle); // MUST release result object
            throw new GdxRuntimeException("Shaderc compilation failed for '" + sourceNameForErrors + "':\n" + errorMessage);
        }

        ByteBuffer spirvResultBytes = Shaderc.shaderc_result_get_bytes(resultHandle);
        // The ByteBuffer from shaderc_result_get_bytes is only valid until shaderc_result_release.
        // We need to copy it to a new ByteBuffer that we manage.
        ByteBuffer spirvCodeCopy = MemoryUtil.memAlloc(spirvResultBytes.remaining()); // Allocate on heap
        spirvCodeCopy.put(spirvResultBytes).flip(); // Copy and flip for reading

        Shaderc.shaderc_result_release(resultHandle); // Release the Shaderc result object
        return spirvCodeCopy; // This new ByteBuffer is owned by the caller and must be freed
    }

    private ByteBuffer readFileToByteBuffer(FileHandle fileHandle) {
        if (!fileHandle.exists()) {
            throw new GdxRuntimeException("Shader file not found: " + fileHandle.path() + " (type: " + fileHandle.type() + ")");
        }
        byte[] bytes = fileHandle.readBytes();
        ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    private long createShaderModuleInternal(ByteBuffer code) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code); // LWJGL's VkShaderModuleCreateInfo takes ByteBuffer directly

            LongBuffer pShaderModule = stack.mallocLong(1);
            vkCheck(vkCreateShaderModule(rawDevice, createInfo, null, pShaderModule),
                    "Failed to create shader module.");
            return pShaderModule.get(0);
        }
    }

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

        // Release Shaderc resources
        if (this.shadercOptions != 0L) { // or if (this.shadercOptions != NULL)
            Shaderc.shaderc_compile_options_release(this.shadercOptions);
            // this.shadercOptions = 0L; // Optional: Mark as released
        }
        if (this.shadercCompiler != 0L) { // or if (this.shadercCompiler != NULL)
            Shaderc.shaderc_compiler_release(this.shadercCompiler);
            // this.shadercCompiler = 0L; // Optional: Mark as released
        }
        if (DEBUG) Gdx.app.log(TAG, "Shaderc resources released.");
        if (DEBUG) Gdx.app.log(TAG, "VulkanShaderManager disposed.");
    }
}