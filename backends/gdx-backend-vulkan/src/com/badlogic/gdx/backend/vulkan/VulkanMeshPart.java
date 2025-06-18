package com.badlogic.gdx.backend.vulkan;

// Assuming VulkanMesh and VulkanMaterial are in this package or imported
// import com.badlogic.gdx.backend.vulkan.VulkanMesh;
// import com.badlogic.gdx.backend.vulkan.VulkanMaterial;

import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST; // Default primitive

public class VulkanMeshPart {
    /** Optional identifier for this mesh part */
    public String id;

    /** The mesh that this part uses */
    public VulkanMesh mesh;

    /** The offset into the mesh's index buffer (if indexed) or vertex buffer (if not indexed) */
    public int indexOffset; // For indexed drawing, this is the first index to use

    /** The number of indices (if indexed) or vertices (if not indexed) to render for this part */
    public int numIndices;  // For indexed drawing, this is the count of indices

    /** The primitive type to render, e.g., VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST */
    public int primitiveType;

    /** The material to be applied to this mesh part */
    public VulkanMaterial material;

    public VulkanMeshPart() {
        this.primitiveType = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST; // Default to triangles
    }

    public VulkanMeshPart(String id, VulkanMesh mesh, int indexOffset, int numIndices, int primitiveType, VulkanMaterial material) {
        this.id = id;
        this.mesh = mesh;
        this.indexOffset = indexOffset;
        this.numIndices = numIndices;
        this.primitiveType = primitiveType;
        this.material = material;
    }
}