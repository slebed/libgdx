package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK11.*;

public class VulkanMesh implements Disposable {

	private final VulkanDevice vulkanDevice;
	private final long vmaAllocator;

	private VulkanBuffer vertexBuffer;
	private VulkanBuffer indexBuffer;

	private int numVertices;
	private int numIndices;
	private VulkanVertexAttributes vulkanVertexAttributes;

	// These are heap-allocated as they need to persist for pipeline creation.
	private VkVertexInputBindingDescription.Buffer bindingDescription;
	private VkVertexInputAttributeDescription.Buffer attributeDescriptions;

	public VulkanMesh() {
		VulkanApplication vulkanApp = (VulkanApplication) Gdx.app;
		this.vulkanDevice = vulkanApp.getVkDevice();
		this.vmaAllocator = vulkanApp.getVmaAllocator();
	}

	public VulkanMesh(VulkanDevice device, long vmaAllocator) {
		this.vulkanDevice = device;
		this.vmaAllocator = vmaAllocator;
	}

	/**
	 * Sets the vertex data for this mesh. This method now correctly uses a staging buffer
	 * pattern via VulkanResourceUtil to upload data to a device-local buffer.
	 *
	 * @param vertices The vertex data.
	 * @param attributes The layout of the vertex data.
	 */
	public void setVertices(float[] vertices, VulkanVertexAttributes attributes) {
		if (vertexBuffer != null) {
			vertexBuffer.dispose();
			vertexBuffer = null;
		}

		this.vulkanVertexAttributes = attributes;
		if (attributes.vertexSize == 0) {
			throw new GdxRuntimeException("VulkanVertexAttributes.vertexSize cannot be 0.");
		}

		long bufferSize = (long) vertices.length * Float.BYTES;
		this.numVertices = (int) (bufferSize / attributes.vertexSize);

		if (bufferSize == 0) {
			this.numVertices = 0;
			generateVertexInputDescriptions();
			return;
		}

		// Create a direct FloatBuffer and copy the vertex data into it.
		FloatBuffer data = BufferUtils.newFloatBuffer(vertices.length);
		data.put(vertices);
		data.flip();

		// Use the utility to create a device-local buffer and upload the data.
		// This handles the staging buffer creation, copy, and cleanup internally.
		vertexBuffer = VulkanResourceUtil.createDeviceLocalBuffer(vulkanDevice, vmaAllocator,
				data, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);

		generateVertexInputDescriptions();
	}

	/**
	 * Sets the index data for this mesh. This method now correctly uses a staging buffer
	 * pattern via VulkanResourceUtil to upload data to a device-local buffer.
	 *
	 * @param indices The index data.
	 */
	public void setIndices(short[] indices) {
		if (indexBuffer != null) {
			indexBuffer.dispose();
			indexBuffer = null;
		}

		this.numIndices = indices.length;
		if (numIndices == 0) {
			return;
		}

		// Create a direct ShortBuffer and copy the index data into it.
		ShortBuffer data = BufferUtils.newShortBuffer(indices.length);
		data.put(indices);
		data.flip();

		// Use the utility to create a device-local buffer and upload the data.
		indexBuffer = VulkanResourceUtil.createDeviceLocalBuffer(vulkanDevice, vmaAllocator,
				data, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
	}

	private void generateVertexInputDescriptions() {
		if (this.bindingDescription != null) this.bindingDescription.free();
		if (this.attributeDescriptions != null) this.attributeDescriptions.free();
		this.bindingDescription = null;
		this.attributeDescriptions = null;

		if (this.vulkanVertexAttributes == null || this.vulkanVertexAttributes.size() == 0) {
			this.bindingDescription = VkVertexInputBindingDescription.calloc(0);
			this.attributeDescriptions = VkVertexInputAttributeDescription.calloc(0);
			return;
		}

		this.bindingDescription = VkVertexInputBindingDescription.calloc(1);
		this.bindingDescription.get(0)
				.binding(0)
				.stride(this.vulkanVertexAttributes.vertexSize)
				.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

		this.attributeDescriptions = VkVertexInputAttributeDescription.calloc(this.vulkanVertexAttributes.size());
		for (int i = 0; i < this.vulkanVertexAttributes.size(); i++) {
			VulkanVertexAttribute attribute = this.vulkanVertexAttributes.get(i);
			if (attribute.location < 0) {
				throw new GdxRuntimeException("VulkanVertexAttribute has an invalid location: " + attribute.location);
			}
			this.attributeDescriptions.get(i)
					.binding(0)
					.location(attribute.location)
					.format(getVkFormat(attribute))
					.offset(attribute.offset);
		}
	}

	public static int getVkFormat(VulkanVertexAttribute attribute) {
		int numComponents = attribute.numComponents;
		boolean normalized = attribute.normalized;
		int type = attribute.type;

		if (type == VulkanVertexAttribute.GL_FLOAT) {
			switch (numComponents) {
				case 1: return VK_FORMAT_R32_SFLOAT;
				case 2: return VK_FORMAT_R32G32_SFLOAT;
				case 3: return VK_FORMAT_R32G32B32_SFLOAT;
				case 4: return VK_FORMAT_R32G32B32A32_SFLOAT;
			}
		} else if (type == VulkanVertexAttribute.GL_UNSIGNED_BYTE) {
			if (attribute.usage == VulkanVertexAttributes.Usage.ColorPacked && numComponents == 4 && normalized) {
				return VK_FORMAT_R8G8B8A8_UNORM;
			}
			if (numComponents == 4) return normalized ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_R8G8B8A8_UINT;
			if (numComponents == 3) return normalized ? VK_FORMAT_R8G8B8_UNORM : VK_FORMAT_R8G8B8_UINT;
			if (numComponents == 2) return normalized ? VK_FORMAT_R8G8_UNORM : VK_FORMAT_R8G8_UINT;
			if (numComponents == 1) return normalized ? VK_FORMAT_R8_UNORM : VK_FORMAT_R8_UINT;
		} else if (type == VulkanVertexAttribute.GL_SHORT) {
			if (numComponents == 4) return normalized ? VK_FORMAT_R16G16B16A16_SNORM : VK_FORMAT_R16G16B16A16_SINT;
			if (numComponents == 2) return normalized ? VK_FORMAT_R16G16_SNORM : VK_FORMAT_R16G16_SINT;
		} else if (type == VulkanVertexAttribute.GL_UNSIGNED_SHORT) {
			if (numComponents == 4) return normalized ? VK_FORMAT_R16G16B16A16_UNORM : VK_FORMAT_R16G16B16A16_UINT;
			if (numComponents == 2) return normalized ? VK_FORMAT_R16G16_UNORM : VK_FORMAT_R16G16_UINT;
		}
		throw new GdxRuntimeException("Unsupported VulkanVertexAttribute for VkFormat: alias=" + attribute.alias);
	}

	// --- Accessors ---
	public VulkanBuffer getVertexBuffer() { return vertexBuffer; }
	public VulkanBuffer getIndexBuffer() { return indexBuffer; }
	public int getNumVertices() { return numVertices; }
	public int getNumIndices() { return numIndices; }
	public boolean isIndexed() { return indexBuffer != null && numIndices > 0; }
	public VkVertexInputBindingDescription.Buffer getBindingDescription() { return bindingDescription; }
	public VkVertexInputAttributeDescription.Buffer getAttributeDescriptions() { return attributeDescriptions; }
	public VulkanVertexAttributes getVulkanVertexAttributes() { return this.vulkanVertexAttributes; }

	@Override
	public void dispose() {
		if (vertexBuffer != null) {
			vertexBuffer.dispose();
			vertexBuffer = null;
		}
		if (indexBuffer != null) {
			indexBuffer.dispose();
			indexBuffer = null;
		}
		if (bindingDescription != null) {
			bindingDescription.free();
			bindingDescription = null;
		}
		if (attributeDescriptions != null) {
			attributeDescriptions.free();
			attributeDescriptions = null;
		}
	}
}
