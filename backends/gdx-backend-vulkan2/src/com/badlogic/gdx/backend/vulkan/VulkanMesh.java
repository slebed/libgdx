
package com.badlogic.gdx.backend.vulkan;

import static org.lwjgl.vulkan.VK10.vkCmdDraw;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;

import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.IndexData;
import com.badlogic.gdx.graphics.glutils.VertexData;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.vulkan.VkCommandBuffer;

// Primarily acts as a container and facade for VulkanVertexData and VulkanIndexData
public class VulkanMesh implements Disposable {

	private final VulkanVertexData vertices;
	private final VulkanIndexData indices;
	private final boolean isInstanced; // For potential future use

	// Constructor takes parameters needed by VertexData/IndexData constructors
	public VulkanMesh (VulkanDevice device, long vmaAllocator, boolean isStatic, int maxVertices, int maxIndices,
		VertexAttributes attributes) {
		this.vertices = new VulkanVertexData(device, vmaAllocator, isStatic, maxVertices, attributes);
		if (maxIndices > 0) {
			this.indices = new VulkanIndexData(device, vmaAllocator, isStatic, maxIndices);
		} else {
			this.indices = null; // No index buffer
		}
		this.isInstanced = false; // TODO: Add instancing support later if needed
	}

	public void setVertices (float[] vertices) {
		this.vertices.setVertices(vertices, 0, vertices.length);
	}

	public void setVertices (float[] vertices, int offset, int count) {
		this.vertices.setVertices(vertices, offset, count);
	}

	public void setIndices (short[] indices) {
		if (this.indices == null) throw new GdxRuntimeException("Cannot set indices on a mesh with no index buffer");
		this.indices.setIndices(indices, 0, indices.length);
	}

	public void setIndices (short[] indices, int offset, int count) {
		if (this.indices == null) throw new GdxRuntimeException("Cannot set indices on a mesh with no index buffer");
		this.indices.setIndices(indices, offset, count);
	}

	public int getNumVertices () {
		return vertices.getNumVertices();
	}

	public int getNumIndices () {
		return (indices != null) ? indices.getNumIndices() : 0;
	}

	public int getMaxVertices () {
		return vertices.getNumMaxVertices();
	}

	public int getMaxIndices () {
		return (indices != null) ? indices.getNumMaxIndices() : 0;
	}

	public VertexAttributes getVertexAttributes () {
		return vertices.getAttributes();
	}

	public VertexData getVertexData () {
		return vertices;
	}

	public IndexData getIndexData () {
		return indices;
	}

	/** Binds the vertex and index buffers (if available) to the command buffer.
	 * @param commandBuffer The active command buffer. */
	public void bind (VkCommandBuffer commandBuffer) {
		vertices.bind(commandBuffer); // Binds to location 0 by default
		if (indices != null) {
			indices.bind(commandBuffer);
		}
	}

	/** Renders the mesh. Assumes pipeline and descriptors are already bound. Call bind() first.
	 * @param commandBuffer The active command buffer (inside a render pass).
	 * @param primitiveType The Vulkan primitive topology (e.g., VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).
	 * @param offset The offset into the index buffer (or vertex buffer if not indexed).
	 * @param count The number of indices (or vertices) to draw. */
	public void render (VkCommandBuffer commandBuffer, int primitiveType, int offset, int count) {
		// TODO: Map libGDX GL primitive types (GL_TRIANGLES) to Vulkan topologies?
		// For now, assume caller provides correct Vulkan topology.

		if (indices != null) {
			if (count <= 0) count = getNumIndices(); // Draw all indices if count <= 0
			vkCmdDrawIndexed(commandBuffer, count, 1, offset, 0, 0); // (indexCount, instanceCount, firstIndex, vertexOffset,
																						// firstInstance)
		} else {
			if (count <= 0) count = getNumVertices(); // Draw all vertices if count <= 0
			vkCmdDraw(commandBuffer, count, 1, offset, 0); // (vertexCount, instanceCount, firstVertex, firstInstance)
		}
	}

	@Override
	public void dispose () {
		vertices.dispose();
		if (indices != null) {
			indices.dispose();
		}
	}
}
