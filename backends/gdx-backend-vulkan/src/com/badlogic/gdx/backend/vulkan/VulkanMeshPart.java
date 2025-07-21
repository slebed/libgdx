package com.badlogic.gdx.backend.vulkan;

import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

public class VulkanMeshPart {
    /** Optional identifier for this mesh part */
    public String id;

    /** The mesh that this part uses */
    public VulkanMesh mesh;

    /** The offset into the mesh's index buffer (if indexed) or vertex buffer (if not indexed) */
    public int offset;

    /** The number of indices (if indexed) or vertices (if not indexed) to render for this part */
    public int size;

    /** The primitive type to render, e.g., VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST */
    public int primitiveType;

    /** The material to be applied to this mesh part */
    public VulkanMaterial material;

    public VulkanMeshPart() {
        this.primitiveType = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST; // Default to triangles
    }

    public VulkanMeshPart(String id, VulkanMesh mesh, int offset, int size, int primitiveType, VulkanMaterial material) {
        this.id = id;
        this.mesh = mesh;
        this.offset = offset;
        this.size = size;
        this.primitiveType = primitiveType;
        this.material = material;
    }
}
