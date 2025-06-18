package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.IntMap;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the lifecycle of VulkanTexture objects created via the GL-emulation layer.
 */
public class VulkanTextureManager implements Disposable {
    private static final String TAG = "VulkanTextureManager";

    private final VulkanGraphics graphics;
    private final IntMap<VulkanTexture> managedTextures = new IntMap<>();
    private final AtomicInteger nextTextureHandle = new AtomicInteger(1);

    public VulkanTextureManager(VulkanGraphics graphics) {
        this.graphics = graphics;
        Gdx.app.log(TAG, "Initialized.");
    }

    /**
     * Creates a "shell" VulkanTexture, registers it with a new unique handle, and returns the handle.
     */
    public int generateAndRegisterTextureShell() {
        int handle = nextTextureHandle.getAndIncrement();

        // This factory method on VulkanGraphics creates the shell instance.
        VulkanTexture newVulkanTexture = graphics.createVulkanTextureShell(handle);

        // --- Diagnostic Logging ---
        Gdx.app.log(TAG, String.format(
                "Manager HC:%-11d | Storing shell HC:%-11d with handle %d",
                this.hashCode(), newVulkanTexture.hashCode(), handle
        ));

        managedTextures.put(handle, newVulkanTexture);
        return handle;
    }

    /**
     * Retrieves a managed VulkanTexture by its GL handle.
     */
    public VulkanTexture getTexture(int handle) {
        VulkanTexture foundTexture = managedTextures.get(handle);

        // --- Diagnostic Logging ---
        Gdx.app.log(TAG, String.format(
                "Manager HC:%-11d | Query for handle %d. Found: %s",
                this.hashCode(), handle, (foundTexture != null ? "Object HC:" + foundTexture.hashCode() : "NULL")
        ));

        return foundTexture;
    }

    /**
     * Disposes the Vulkan resources of a texture and removes it from management.
     */
    public void deleteTexture(int handle) {
        if (handle == 0) return;

        VulkanTexture texture = managedTextures.remove(handle);
        if (texture != null) {
            texture.dispose();
            Gdx.app.log(TAG, "Disposed and unregistered texture with handle: " + handle);
        } else {
            Gdx.app.error(TAG, "deleteTexture requested for handle not found in manager: " + handle);
        }
    }

    /**
     * Sets a texture parameter for a managed texture.
     * This is called from VulkanGraphics as part of the glTexParameteri emulation.
     *
     * @param texture The target VulkanTexture object.
     * @param pname The parameter name enum (e.g., GL_TEXTURE_MIN_FILTER).
     * @param param The integer value for the parameter.
     */
    public void setTextureParameter(VulkanTexture texture, int pname, int param) {
        if (texture == null) {
            Gdx.app.error(TAG, "setTextureParameter: VulkanTexture object is null!");
            return;
        }
        // Delegate to the texture instance's method designed for this purpose
        texture.GLESManagedSetParameter(pname, param);
    }

    @Override
    public void dispose() {
        Gdx.app.log(TAG, "Disposing all managed textures (" + managedTextures.size + ")...");
        for (VulkanTexture texture : managedTextures.values()) {
            if (texture != null) {
                texture.dispose();
            }
        }
        managedTextures.clear();
        Gdx.app.log(TAG, "VulkanTextureManager disposed.");
    }

    public boolean isTexture(int texture) {
        return managedTextures.containsKey(texture);
    }
}