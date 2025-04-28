
package com.badlogic.gdx.backend.vulkan;

import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.system.MemoryStack.*;

public class VulkanDescriptorManager {

	private static final int MAX_SETS_PER_POOL = 1000;
	private static final int MAX_UBOS_PER_POOL = 1000;
	private static final int MAX_SAMPLERS_PER_POOL = 1000;

	private final VkDevice device;
	private long descriptorPool;

	// Cache layouts to avoid recreating identical ones
	private final Map<String, Long> layoutCache = new HashMap<>();
	// Define keys for common layouts
	public static final String LAYOUT_KEY_SPRITEBATCH = "SpriteBatch_UBO0_Sampler1";
	public static final String LAYOUT_KEY_SINGLE_SAMPLER = "SingleSampler0"; // Keep if needed elsewhere

	public VulkanDescriptorManager (VkDevice device) {
		this.device = Objects.requireNonNull(device);
		createPool();
		// Layouts are now created on demand via getOrCreate methods
	}

	// --- Layout Creation/Retrieval ---

	public long getOrCreateSpriteBatchLayout () {
		return layoutCache.computeIfAbsent(LAYOUT_KEY_SPRITEBATCH, k -> createSpriteBatchLayout());
	}

	// Example: Keep the old single sampler layout if needed elsewhere
	public long getOrCreateSingleSamplerLayout () {
		return layoutCache.computeIfAbsent(LAYOUT_KEY_SINGLE_SAMPLER, k -> createSingleSamplerLayout());
	}

	// You could add a more generic method if needed:
	// public synchronized long getOrCreateLayout(String key, VkDescriptorSetLayoutBinding.Buffer bindings) { ... }

	private long createSpriteBatchLayout () {
		try (MemoryStack stack = stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);

			// Binding 0: Uniform Buffer (Vertex Shader)
			VkDescriptorSetLayoutBinding uboBinding = bindings.get(0);
			uboBinding.binding(0);
			uboBinding.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
			uboBinding.descriptorCount(1);
			uboBinding.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
			uboBinding.pImmutableSamplers(null); // Optional

			// Binding 1: Combined Image Sampler (Fragment Shader)
			VkDescriptorSetLayoutBinding samplerBinding = bindings.get(1);
			samplerBinding.binding(1);
			samplerBinding.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
			samplerBinding.descriptorCount(1);
			samplerBinding.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
			samplerBinding.pImmutableSamplers(null); // Optional

			VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
			layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
			layoutInfo.pBindings(bindings);
			// layoutInfo.flags(...) // Optional flags

			LongBuffer pLayout = stack.mallocLong(1);
			int result = vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
			if (result != VK_SUCCESS) {
				throw new RuntimeException("Failed to create SpriteBatch descriptor set layout: " + result);
			}
			System.out.println("Created SpriteBatch Descriptor Set Layout: " + pLayout.get(0));
			return pLayout.get(0);
		}
	}

	private long createSingleSamplerLayout () {
		try (MemoryStack stack = stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);

			// Binding 0: Combined Image Sampler (Fragment Shader)
			VkDescriptorSetLayoutBinding samplerBinding = bindings.get(0);
			samplerBinding.binding(0); // Original binding was 0
			samplerBinding.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
			samplerBinding.descriptorCount(1);
			samplerBinding.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
			samplerBinding.pImmutableSamplers(null);

			VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
			layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
			layoutInfo.pBindings(bindings);

			LongBuffer pLayout = stack.mallocLong(1);
			int result = vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
			if (result != VK_SUCCESS) {
				throw new RuntimeException("Failed to create Single Sampler descriptor set layout: " + result);
			}
			System.out.println("Created Single Sampler Descriptor Set Layout: " + pLayout.get(0));
			return pLayout.get(0);
		}
	}

	// --- Pool and Allocation ---

	private void createPool () {
		try (MemoryStack stack = stackPush()) {
			VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);

			// Size for Uniform Buffers
			poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
			poolSizes.get(0).descriptorCount(MAX_UBOS_PER_POOL);

			// Size for Combined Image Samplers
			poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
			poolSizes.get(1).descriptorCount(MAX_SAMPLERS_PER_POOL);

			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
			poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
			poolInfo.pPoolSizes(poolSizes);
			poolInfo.maxSets(MAX_SETS_PER_POOL); // Max total sets from this pool
			poolInfo.flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT); // Optional: Allows freeing individual sets

			LongBuffer pDescriptorPool = stack.mallocLong(1);
			int result = vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool);
			if (result != VK_SUCCESS) {
				throw new RuntimeException("Failed to create descriptor pool: " + result);
			}
			this.descriptorPool = pDescriptorPool.get(0);
		}
	}

	public long allocateSet (long layoutHandle) {
		try (MemoryStack stack = stackPush()) {
			VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
			allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
			allocInfo.descriptorPool(descriptorPool);
			allocInfo.pSetLayouts(stack.longs(layoutHandle)); // Pass the specific layout handle

			LongBuffer pDescriptorSet = stack.mallocLong(1);
			int result = vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);
			if (result != VK_SUCCESS) {
				// Consider specific error handling, maybe pool is full?
				throw new RuntimeException("Failed to allocate descriptor set: " + result);
			}
			System.out.println("Allocated Descriptor Set: " + pDescriptorSet.get(0) + " with Layout: " + layoutHandle);
			return pDescriptorSet.get(0);
		}
	}

	// --- Update Helpers ---

	/** Static helper to update a Combined Image Sampler descriptor. */
	public static void updateCombinedImageSampler (VkDevice device, long set, int binding, VulkanTexture texture) {
		if (texture == null || texture.getImageViewHandle() == VK_NULL_HANDLE || texture.getSamplerHandle() == VK_NULL_HANDLE) {
			System.err.println("WARN: Attempting to update sampler binding " + binding + " with invalid texture.");
			return; // Avoid crash, but log this!
		}
		try (MemoryStack stack = stackPush()) {
			VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
			imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			imageInfo.imageView(texture.getImageViewHandle()); // Method from your VulkanTexture class
			imageInfo.sampler(texture.getSamplerHandle()); // Method from your VulkanTexture class

			VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
			descriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
			descriptorWrite.dstSet(set);
			descriptorWrite.dstBinding(binding); // Use the provided binding
			descriptorWrite.dstArrayElement(0);
			descriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
			descriptorWrite.descriptorCount(1);
			descriptorWrite.pImageInfo(imageInfo);
			descriptorWrite.pBufferInfo(null);
			descriptorWrite.pTexelBufferView(null);

			vkUpdateDescriptorSets(device, descriptorWrite, null);
			// System.out.println("Updated Sampler Set: " + set + " Binding: " + binding + " Texture: " + texture.getId()); // Debug
			// logging
		}
	}

	/** Static helper to update a Uniform Buffer descriptor. (NEW) */
	public static void updateUniformBuffer (VkDevice device, long set, int binding, VulkanUniformBuffer ubo) {
		if (ubo == null || ubo.getBufferHandle() == VK_NULL_HANDLE) {
			System.err.println("WARN: Attempting to update UBO binding " + binding + " with invalid buffer.");
			return; // Avoid crash
		}
		updateUniformBuffer(device, set, binding, ubo.getBufferHandle(), ubo.getOffset(), ubo.getRange());
	}

	/** Static helper to update a Uniform Buffer descriptor with explicit details. (NEW) */
	public static void updateUniformBuffer (VkDevice device, long set, int binding, long bufferHandle, long offset, long range) {
		if (bufferHandle == VK_NULL_HANDLE) {
			System.err.println("WARN: Attempting to update UBO binding " + binding + " with null buffer handle.");
			return; // Avoid crash
		}
		if (range <= 0) {
			System.err
				.println("WARN: Attempting to update UBO binding " + binding + " with zero or negative range (" + range + ").");
			// This might be valid if allowed by spec/extensions, but often indicates an error.
			// Decide whether to return or proceed based on your engine's logic.
			// return;
		}

		try (MemoryStack stack = stackPush()) {
			VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
			bufferInfo.buffer(bufferHandle); // Handle from your VulkanUniformBuffer
			bufferInfo.offset(offset); // Offset from your VulkanUniformBuffer
			bufferInfo.range(range); // Range/Size from your VulkanUniformBuffer

			VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
			descriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
			descriptorWrite.dstSet(set);
			descriptorWrite.dstBinding(binding); // Use the provided binding
			descriptorWrite.dstArrayElement(0);
			descriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
			descriptorWrite.descriptorCount(1);
			descriptorWrite.pBufferInfo(bufferInfo);
			descriptorWrite.pImageInfo(null);
			descriptorWrite.pTexelBufferView(null);

			vkUpdateDescriptorSets(device, descriptorWrite, null);
			// System.out.println("Updated UBO Set: " + set + " Binding: " + binding + " Buffer: " + bufferHandle); // Debug logging
		}
	}

	public void freeSets (List<Long> setHandles) {
		if (setHandles == null || setHandles.isEmpty() || this.descriptorPool == VK_NULL_HANDLE) {
			return;
		}
		try (MemoryStack stack = stackPush()) {
			LongBuffer pSets = stack.mallocLong(setHandles.size());
			for (int i = 0; i < setHandles.size(); i++) {
				pSets.put(i, setHandles.get(i)); // Use indexed put
			}
			// No flip needed if using indexed put correctly

			// Requires VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT on pool
			int result = vkFreeDescriptorSets(device, descriptorPool, pSets);
			if (result != VK_SUCCESS) {
				System.err.println("VulkanDescriptorManager WARN: vkFreeDescriptorSets failed with result: " + result);
			} else {
				// Optional: Log success
				System.out.println("VulkanDescriptorManager: Freed " + setHandles.size() + " descriptor sets.");
			}
		} catch (Exception e) {
			System.err.println("VulkanDescriptorManager ERROR: Exception during vkFreeDescriptorSets: " + e.getMessage());
			e.printStackTrace(); // Log stack trace for unexpected errors
		}
	}

	public void dispose () {
		// Destroy all cached layouts
		for (long layoutHandle : layoutCache.values()) {
			vkDestroyDescriptorSetLayout(device, layoutHandle, null);
		}
		layoutCache.clear();

		// Destroy the pool (implicitly frees all sets allocated from it)
		if (descriptorPool != VK_NULL_HANDLE) {
			vkDestroyDescriptorPool(device, descriptorPool, null);
			descriptorPool = VK_NULL_HANDLE;
		}
	}
}
