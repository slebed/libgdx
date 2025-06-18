package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxRuntimeException;
// import com.badlogic.gdx.utils.ObjectMap; // For material overrides later

public class VulkanModelInstance {
    public final VulkanModel model; // The model data this instance refers to
    public final Matrix4 transform = new Matrix4(); // Instance-specific world transform, initialized to identity
    public String id; // Optional identifier for this instance

    // Future:
    // public ObjectMap<String, VulkanMaterial> materialOverrides; // Key: meshPart.id or material.id
    // public AnimationController animationController;
    // public Object userData;

    /**
     * Creates an instance of the
     *
     * @param model The {@link VulkanModel} to instance.
     */
    public VulkanModelInstance(VulkanModel model) {
        if (model == null) throw new GdxRuntimeException("Model cannot be null for VulkanModelInstance");
        this.model = model;
    }

    /**
     * Creates an instance of the
     *
     * @param model The {@link VulkanModel} to instance.
     * @param id    Optional ID for this instance.
     */
    public VulkanModelInstance(VulkanModel model, String id) {
        this(model);
        this.id = id;
    }

    // --- Transform Convenience Methods ---
    public VulkanModelInstance setTransform(Matrix4 transform) {
        this.transform.set(transform);
        return this;
    }

    public VulkanModelInstance translate(float x, float y, float z) {
        this.transform.translate(x, y, z);
        return this;
    }

    public VulkanModelInstance rotate(Vector3 axis, float degrees) {
        this.transform.rotate(axis, degrees);
        return this;
    }

    public VulkanModelInstance scale(float scaleX, float scaleY, float scaleZ) {
        this.transform.scale(scaleX, scaleY, scaleZ);
        return this;
    }

    // Add more transform methods as needed
}