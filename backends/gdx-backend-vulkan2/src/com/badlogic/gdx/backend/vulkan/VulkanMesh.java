package com.badlogic.gdx.backend.vulkan; // Your package

// Your VulkanVertexAttribute and VulkanVertexAttributes are in this package.

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanMesh implements Disposable {

	private final VulkanDevice vulkanDevice;

	private long vertexBufferHandle;
	private long vertexBufferMemoryHandle;
	private long indexBufferHandle;
	private long indexBufferMemoryHandle;

	private int numVertices;
	private int numIndices;
	// Store your Vulkan-specific attributes collection
	private VulkanVertexAttributes vulkanVertexAttributes; // THIS WILL USE YOUR NEW CLASS

	// These will be heap-allocated as they need to persist
	private VkVertexInputBindingDescription.Buffer bindingDescription;
	private VkVertexInputAttributeDescription.Buffer attributeDescriptions;

	public VulkanMesh(){
		VulkanApplication vulkanApp = (VulkanApplication) Gdx.app;
        this.vulkanDevice = vulkanApp.getVulkanDevice();
		this.vertexBufferHandle = VK_NULL_HANDLE;
		this.vertexBufferMemoryHandle = VK_NULL_HANDLE;
		this.indexBufferHandle = VK_NULL_HANDLE;
		this.indexBufferMemoryHandle = VK_NULL_HANDLE;
	}

	public VulkanMesh(VulkanDevice vulkanDevice) {
		this.vulkanDevice = vulkanDevice;
		this.vertexBufferHandle = VK_NULL_HANDLE;
		this.vertexBufferMemoryHandle = VK_NULL_HANDLE;
		this.indexBufferHandle = VK_NULL_HANDLE;
		this.indexBufferMemoryHandle = VK_NULL_HANDLE;
	}

	// --- Helper methods (findMemoryType, createBufferAndAllocateMemory, copyBuffer) ---
	// These remain the same as previously discussed.
	private int findMemoryType(int typeFilter, int properties) {
		try (MemoryStack stack = stackPush()) {
			VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
			vkGetPhysicalDeviceMemoryProperties(vulkanDevice.getPhysicalDevice(), memProperties);
			for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
				if ((typeFilter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
					return i;
				}
			}
		}
		throw new GdxRuntimeException("Failed to find suitable memory type!");
	}

	private void createBufferAndAllocateMemory(long size, int usage, int memoryProperties,
											   LongBuffer pBufferHandle, LongBuffer pBufferMemoryHandle) {
		try (MemoryStack stack = stackPush()) {
			VkDevice device = vulkanDevice.getLogicalDevice();
			VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
					.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(size).usage(usage)
					.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			if (vkCreateBuffer(device, bufferInfo, null, pBufferHandle) != VK_SUCCESS) {
				throw new GdxRuntimeException("Failed to create buffer (size: " + size + ", usage: " + usage + ")!");
			}
			long buffer = pBufferHandle.get(0);
			VkMemoryRequirements memRequirements = VkMemoryRequirements.malloc(stack);
			vkGetBufferMemoryRequirements(device, buffer, memRequirements);
			VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
					.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(memRequirements.size())
					.memoryTypeIndex(findMemoryType(memRequirements.memoryTypeBits(), memoryProperties));
			if (vkAllocateMemory(device, allocInfo, null, pBufferMemoryHandle) != VK_SUCCESS) {
				throw new GdxRuntimeException("Failed to allocate buffer memory (size: " + memRequirements.size() + ")!");
			}
			long bufferMemory = pBufferMemoryHandle.get(0);
			if (vkBindBufferMemory(device, buffer, bufferMemory, 0) != VK_SUCCESS) {
				throw new GdxRuntimeException("Failed to bind buffer memory!");
			}
		}
	}

	private void copyBuffer(long srcBuffer, long dstBuffer, long size) {
		vulkanDevice.executeSingleTimeCommands(commandBuffer -> {
			try (MemoryStack stack = stackPush()) {
				VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack)
						.srcOffset(0).dstOffset(0).size(size);
				vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, copyRegion);
			}
		});
	}

	/**
	 * Sets the vertex data for this mesh.
	 * @param vertices The raw vertex data as a float array.
	 * @param attributes Your {@link VulkanVertexAttributes} instance defining the layout.
	 * The {@code vertexSize} from this object will be used as the stride.
	 * The {@code offset} within each {@code VulkanVertexAttribute} must have been
	 * calculated by your {@code VulkanVertexAttributes} constructor.
	 */
	public void setVertices(float[] vertices, VulkanVertexAttributes attributes) {
		if (this.vertexBufferHandle != VK_NULL_HANDLE) {
			vkDestroyBuffer(vulkanDevice.getLogicalDevice(), this.vertexBufferHandle, null);
			this.vertexBufferHandle = VK_NULL_HANDLE;
		}
		if (this.vertexBufferMemoryHandle != VK_NULL_HANDLE) {
			vkFreeMemory(vulkanDevice.getLogicalDevice(), this.vertexBufferMemoryHandle, null);
			this.vertexBufferMemoryHandle = VK_NULL_HANDLE;
		}

		this.vulkanVertexAttributes = attributes; // Store your VulkanVertexAttributes collection

		if (attributes.vertexSize == 0) {
			throw new GdxRuntimeException("VulkanVertexAttributes.vertexSize cannot be 0.");
		}
		// Calculate number of vertices using the vertexSize from your VulkanVertexAttributes
		this.numVertices = (vertices.length * Float.BYTES) / attributes.vertexSize;
		if ((vertices.length * Float.BYTES) % attributes.vertexSize != 0) {
			throw new GdxRuntimeException("Vertex data size (" + (vertices.length * Float.BYTES) +
					") is not a multiple of vertex stride (" + attributes.vertexSize + ").");
		}

		long bufferSize = (long)vertices.length * Float.BYTES;
		if (bufferSize == 0) {
			this.numVertices = 0;
			generateVertexInputDescriptions(); // Generate empty descriptions
			return;
		}

		// Staging buffer creation and copy logic remains the same
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer pStagingBuffer = stack.mallocLong(1);
			LongBuffer pStagingBufferMemory = stack.mallocLong(1);

			createBufferAndAllocateMemory(bufferSize,
					VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
					VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
					pStagingBuffer, pStagingBufferMemory);
			long stagingBuffer = pStagingBuffer.get(0);
			long stagingBufferMemory = pStagingBufferMemory.get(0);

			PointerBuffer pData = stack.mallocPointer(1);
			vkMapMemory(vulkanDevice.getLogicalDevice(), stagingBufferMemory, 0, bufferSize, 0, pData);
			ByteBuffer byteBuffer = pData.getByteBuffer(0, (int) bufferSize);
			FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
			floatBuffer.put(vertices);
			vkUnmapMemory(vulkanDevice.getLogicalDevice(), stagingBufferMemory);

			LongBuffer pDeviceLocalVertexBuffer = stack.mallocLong(1);
			LongBuffer pDeviceLocalVertexBufferMemory = stack.mallocLong(1);
			createBufferAndAllocateMemory(bufferSize,
					VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
					VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
					pDeviceLocalVertexBuffer, pDeviceLocalVertexBufferMemory);
			this.vertexBufferHandle = pDeviceLocalVertexBuffer.get(0);
			this.vertexBufferMemoryHandle = pDeviceLocalVertexBufferMemory.get(0);

			copyBuffer(stagingBuffer, this.vertexBufferHandle, bufferSize);

			vkDestroyBuffer(vulkanDevice.getLogicalDevice(), stagingBuffer, null);
			vkFreeMemory(vulkanDevice.getLogicalDevice(), stagingBufferMemory, null);
		}
		generateVertexInputDescriptions(); // Generate descriptions based on the new attributes
	}

	// setIndices method remains the same as before...
	public void setIndices(short[] indices) {
		if (this.indexBufferHandle != VK_NULL_HANDLE) {
			vkDestroyBuffer(vulkanDevice.getLogicalDevice(), this.indexBufferHandle, null);
			this.indexBufferHandle = VK_NULL_HANDLE;
		}
		if (this.indexBufferMemoryHandle != VK_NULL_HANDLE) {
			vkFreeMemory(vulkanDevice.getLogicalDevice(), this.indexBufferMemoryHandle, null);
			this.indexBufferMemoryHandle = VK_NULL_HANDLE;
		}
		this.numIndices = indices.length;
		long bufferSize = (long)indices.length * Short.BYTES;
		if (bufferSize == 0) {
			this.numIndices = 0;
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer pStagingBuffer = stack.mallocLong(1);
			LongBuffer pStagingBufferMemory = stack.mallocLong(1);
			createBufferAndAllocateMemory(bufferSize,
					VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
					VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
					pStagingBuffer, pStagingBufferMemory);
			long stagingBuffer = pStagingBuffer.get(0);
			long stagingBufferMemory = pStagingBufferMemory.get(0);

			PointerBuffer pData = stack.mallocPointer(1);
			vkMapMemory(vulkanDevice.getLogicalDevice(), stagingBufferMemory, 0, bufferSize, 0, pData);
			ByteBuffer byteBuffer = pData.getByteBuffer(0, (int) bufferSize);
			ShortBuffer shortBuffer = byteBuffer.asShortBuffer();
			shortBuffer.put(indices);
			vkUnmapMemory(vulkanDevice.getLogicalDevice(), stagingBufferMemory);

			LongBuffer pDeviceLocalIndexBuffer = stack.mallocLong(1);
			LongBuffer pDeviceLocalIndexBufferMemory = stack.mallocLong(1);
			createBufferAndAllocateMemory(bufferSize,
					VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
					VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
					pDeviceLocalIndexBuffer, pDeviceLocalIndexBufferMemory);
			this.indexBufferHandle = pDeviceLocalIndexBuffer.get(0);
			this.indexBufferMemoryHandle = pDeviceLocalIndexBufferMemory.get(0);

			copyBuffer(stagingBuffer, this.indexBufferHandle, bufferSize);

			vkDestroyBuffer(vulkanDevice.getLogicalDevice(), stagingBuffer, null);
			vkFreeMemory(vulkanDevice.getLogicalDevice(), stagingBufferMemory, null);
		}
	}


	/**
	 * Generates VkVertexInputBindingDescription and VkVertexInputAttributeDescription
	 * based on the stored {@link VulkanVertexAttributes}.
	 */
	private void generateVertexInputDescriptions() {
		if (this.bindingDescription != null) this.bindingDescription.free();
		if (this.attributeDescriptions != null) this.attributeDescriptions.free();
		this.bindingDescription = null;
		this.attributeDescriptions = null;

		if (this.vulkanVertexAttributes == null || this.vulkanVertexAttributes.size() == 0) {
			// No attributes, create empty (but valid for LWJGL) descriptions
			this.bindingDescription = VkVertexInputBindingDescription.calloc(0);
			this.attributeDescriptions = VkVertexInputAttributeDescription.calloc(0);
			return;
		}

		this.bindingDescription = VkVertexInputBindingDescription.calloc(1); // Heap allocated
		this.bindingDescription.get(0)
				.binding(0) // Using a single binding for interleaved vertex data
				.stride(this.vulkanVertexAttributes.vertexSize) // Get stride from your collection
				.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

		this.attributeDescriptions = VkVertexInputAttributeDescription.calloc(this.vulkanVertexAttributes.size()); // Heap allocated
		for (int i = 0; i < this.vulkanVertexAttributes.size(); i++) {
			// Get your VulkanVertexAttribute (which now includes 'location')
			VulkanVertexAttribute attribute = this.vulkanVertexAttributes.get(i);

			if (attribute.location < 0) { // Assuming location was set in VulkanVertexAttribute
				throw new GdxRuntimeException("VulkanVertexAttribute has an invalid location: " + attribute.location +
						" for alias: " + attribute.alias + ". Explicit location is required.");
			}

			this.attributeDescriptions.get(i)
					.binding(0) // All attributes from the same interleaved buffer
					.location(attribute.location) // *** Use the explicit location ***
					.format(getVkFormat(attribute)) // Pass your VulkanVertexAttribute
					.offset(attribute.offset); // Use offset calculated by VulkanVertexAttributes
		}
	}

	/**
	 * Maps your {@link VulkanVertexAttribute} to a {@code VkFormat}.
	 * Uses the GL constants defined within your {@code VulkanVertexAttribute} class.
	 */
	public static int getVkFormat(VulkanVertexAttribute attribute) {
		int numComponents = attribute.numComponents;
		boolean normalized = attribute.normalized;
		// Uses GL constants defined in your VulkanVertexAttribute class
		int type = attribute.type;

		if (type == VulkanVertexAttribute.GL_FLOAT) {
			switch (numComponents) {
				case 1: return VK_FORMAT_R32_SFLOAT;
				case 2: return VK_FORMAT_R32G32_SFLOAT;
				case 3: return VK_FORMAT_R32G32B32_SFLOAT;
				case 4: return VK_FORMAT_R32G32B32A32_SFLOAT;
			}
		} else if (type == VulkanVertexAttribute.GL_UNSIGNED_BYTE) {
			// Check usage for ColorPacked specifically, as it implies R8G8B8A8_UNORM
			if (attribute.usage == VulkanVertexAttributes.Usage.ColorPacked && numComponents == 4 && normalized) {
				return VK_FORMAT_R8G8B8A8_UNORM;
			}
			// General cases for unsigned byte:
			if (numComponents == 4) return normalized ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_R8G8B8A8_UINT;
			if (numComponents == 3) return normalized ? VK_FORMAT_R8G8B8_UNORM : VK_FORMAT_R8G8B8_UINT; // Note: R8G8B8 formats are less common, ensure support
			if (numComponents == 2) return normalized ? VK_FORMAT_R8G8_UNORM : VK_FORMAT_R8G8_UINT;
			if (numComponents == 1) return normalized ? VK_FORMAT_R8_UNORM : VK_FORMAT_R8_UINT;

		} else if (type == VulkanVertexAttribute.GL_BYTE) {
			if (numComponents == 4) return normalized ? VK_FORMAT_R8G8B8A8_SNORM : VK_FORMAT_R8G8B8A8_SINT;
			// ... other component counts
		} else if (type == VulkanVertexAttribute.GL_UNSIGNED_SHORT) {
			if (numComponents == 4) return normalized ? VK_FORMAT_R16G16B16A16_UNORM : VK_FORMAT_R16G16B16A16_UINT;
			if (numComponents == 2) return normalized ? VK_FORMAT_R16G16_UNORM : VK_FORMAT_R16G16_UINT;
			// ...
		} else if (type == VulkanVertexAttribute.GL_SHORT) {
			if (numComponents == 4) return normalized ? VK_FORMAT_R16G16B16A16_SNORM : VK_FORMAT_R16G16B16A16_SINT;
			if (numComponents == 2) return normalized ? VK_FORMAT_R16G16_SNORM : VK_FORMAT_R16G16_SINT;
			// ...
		} else if (type == VulkanVertexAttribute.GL_FIXED) {
			// VK_FORMAT_SFIXED types are not standard. Typically, fixed-point data
			// is either converted to float on CPU or handled by specific shader instructions
			// if hardware supports it. For simplicity, often treated as SFLOAT if normalized
			// to a float range, or specific integer formats if not.
			// Assuming it's treated as float for now if it's 32-bit.
			if (numComponents == 1) return VK_FORMAT_R32_SFLOAT;
			if (numComponents == 2) return VK_FORMAT_R32G32_SFLOAT;
			if (numComponents == 3) return VK_FORMAT_R32G32B32_SFLOAT;
			if (numComponents == 4) return VK_FORMAT_R32G32B32A32_SFLOAT;
		}

		throw new GdxRuntimeException("Unsupported VulkanVertexAttribute for VkFormat: alias=" + attribute.alias
				+ ", numComponents=" + numComponents + ", type=0x" + Integer.toHexString(type) + ", normalized=" + normalized);
	}

	// --- Accessors ---
	public long getVertexBufferHandle() { return vertexBufferHandle; }
	public long getIndexBufferHandle() { return indexBufferHandle; }
	public int getNumVertices() { return numVertices; }
	public int getNumIndices() { return numIndices; }
	public boolean isIndexed() { return indexBufferHandle != VK_NULL_HANDLE && numIndices > 0; }

	public VkVertexInputBindingDescription.Buffer getBindingDescription() {
		if (bindingDescription == null && vulkanVertexAttributes != null && numVertices > 0) {
			generateVertexInputDescriptions();
		}
		return bindingDescription;
	}

	public VkVertexInputAttributeDescription.Buffer getAttributeDescriptions() {
		if (attributeDescriptions == null && vulkanVertexAttributes != null && numVertices > 0) {
			generateVertexInputDescriptions();
		}
		return attributeDescriptions;
	}

	/**
	 * Returns the {@link VulkanVertexAttributes} instance defining the structure of this mesh's vertices.
	 * @return The VulkanVertexAttributes for this mesh, or null if not set.
	 */
	public VulkanVertexAttributes getVulkanVertexAttributes() {
		return this.vulkanVertexAttributes;
	}

	@Override
	public void dispose() {
		VkDevice device = vulkanDevice.getLogicalDevice();
		if (vertexBufferHandle != VK_NULL_HANDLE) {
			vkDestroyBuffer(device, vertexBufferHandle, null);
			vertexBufferHandle = VK_NULL_HANDLE;
		}
		if (vertexBufferMemoryHandle != VK_NULL_HANDLE) {
			vkFreeMemory(device, vertexBufferMemoryHandle, null);
			vertexBufferMemoryHandle = VK_NULL_HANDLE;
		}
		if (indexBufferHandle != VK_NULL_HANDLE) {
			vkDestroyBuffer(device, indexBufferHandle, null);
			indexBufferHandle = VK_NULL_HANDLE;
		}
		if (indexBufferMemoryHandle != VK_NULL_HANDLE) {
			vkFreeMemory(device, indexBufferMemoryHandle, null);
			indexBufferMemoryHandle = VK_NULL_HANDLE;
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