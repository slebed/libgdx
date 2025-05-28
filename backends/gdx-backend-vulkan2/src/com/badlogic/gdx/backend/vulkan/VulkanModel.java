package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

public class VulkanModel implements Disposable {
    public final String id; // Optional ID for the model

    /** Meshes owned and used by this model. Disposal of these is handled by this model. */
    public final Array<VulkanMesh> meshes = new Array<>(true, 1, VulkanMesh.class);

    /** Materials used by this model.
     * Ownership/disposal depends: if loaded uniquely for this model, model disposes.
     * If shared from an AssetManager, AssetManager disposes.
     * For now, let's assume if added here, this model has a responsibility towards them,
     * but actual VulkanTexture disposal within VulkanMaterial is separate.
     */
    public final Array<VulkanMaterial> materials = new Array<>(true, 1, VulkanMaterial.class);

    /** The drawable parts of this model, combining meshes and materials. */
    public final Array<VulkanMeshPart> meshParts = new Array<>(true, 1, VulkanMeshPart.class);

    // Future additions:
    // public final Array<VulkanNode> nodes = new Array<>();
    // public final Array<VulkanAnimation> animations = new Array<>();

    /**
     * Creates a VulkanModel.
     * The caller is responsible for populating the meshes, materials, and meshParts arrays.
     * This is typically done by a model loader.
     * @param id An optional identifier for the model.
     */
    public VulkanModel(String id) {
        this.id = id;
    }

    /**
     * Adds a mesh to this model. The model will be responsible for disposing this mesh.
     */
    public void addMesh(VulkanMesh mesh) {
        if (mesh == null) throw new GdxRuntimeException("Mesh cannot be null");
        meshes.add(mesh);
    }

    /**
     * Adds a material to this model.
     */
    public void addMaterial(VulkanMaterial material) {
        if (material == null) throw new GdxRuntimeException("Material cannot be null");
        materials.add(material);
    }

    /**
     * Adds a mesh part to this model.
     */
    public void addMeshPart(VulkanMeshPart meshPart) {
        if (meshPart == null) throw new GdxRuntimeException("MeshPart cannot be null");
        if (meshPart.mesh == null || meshPart.material == null) {
            throw new GdxRuntimeException("MeshPart must have a valid mesh and material assigned.");
        }
        meshParts.add(meshPart);
    }


    /**
     * Disposes all {@link VulkanMesh} objects this model owns.
     * Materials are typically data containers and might not own heavy resources themselves
     * (textures they reference are usually managed externally).
     */
    @Override
    public void dispose() {
        for (VulkanMesh mesh : meshes) {
            if (mesh != null) {
                mesh.dispose();
            }
        }
        meshes.clear();

        // Materials themselves might not own Vulkan resources needing dispose,
        // but if they did (e.g., uniquely generated textures or UBOs), they'd be disposed here.
        // For now, VulkanMaterial's dispose method is likely empty.
        for (VulkanMaterial material : materials) {
            // if (material instanceof Disposable) ((Disposable) material).dispose();
        }
        materials.clear();
        meshParts.clear(); // MeshParts don't own resources, just reference them
    }
}