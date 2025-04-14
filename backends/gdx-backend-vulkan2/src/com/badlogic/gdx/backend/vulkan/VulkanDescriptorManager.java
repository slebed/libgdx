
package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Manages Vulkan Descriptor Set Layouts, Pools, and Sets. (Initial version handles one default layout and pool
 * configuration). */
public class VulkanDescriptorManager implements Disposable {
	private final String logTag = "VulkanDescriptorManager";
	private final VulkanDevice device;
	private final VkDevice rawDevice;

	// Handles managed by this class
	private long descriptorPool = VK_NULL_HANDLE;
	private long singleTextureLayout = VK_NULL_HANDLE; // Layout for a single combined image sampler at binding 0

	public VulkanDescriptorManager (VulkanDevice device) {
		this.device = device;
		this.rawDevice = device.getRawDevice();

		try {
			createLayout(); // Create the specific layout we need now
			createPool(); // Create a reasonably sized pool
		} catch (Exception e) {
			// Ensure cleanup if constructor fails partially
			dispose();
			throw new GdxRuntimeException("Failed to initialize VulkanDescriptorManager", e);
		}
		Gdx.app.log(logTag, "Initialized successfully.");
	}

	/** Creates the specific hardcoded layout for a single Combined Image Sampler at binding 0. TODO: Extend this later to
	 * create/cache layouts based on input bindings. */
	private void createLayout () {
		Gdx.app.log(logTag, "Creating default descriptor set layout (1 CombinedImageSampler@0)...");
		try (MemoryStack stack = stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer samplerLayoutBinding = VkDescriptorSetLayoutBinding.calloc(1, stack);
			samplerLayoutBinding.get(0).binding(0) // Binding 0
				.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // Used
																																												// in
																																												// fragment
																																												// shader
				.pImmutableSamplers(null);

			VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
				.pBindings(samplerLayoutBinding);

			LongBuffer pDescriptorSetLayout = stack.mallocLong(1);
			vkCheck(vkCreateDescriptorSetLayout(rawDevice, layoutInfo, null, pDescriptorSetLayout),
				"Failed to create default descriptor set layout");
			this.singleTextureLayout = pDescriptorSetLayout.get(0);
			Gdx.app.log(logTag, "Default layout created: " + this.singleTextureLayout);
		}
	}

	/** Creates the main descriptor pool. Sized generously enough for common scenarios, but might need adjustment/extension
	 * later. */
	private void createPool () {
		Gdx.app.log(logTag, "Creating descriptor pool...");
		final int MAX_SETS = 100; // Max descriptor SETS we can allocate
		final int MAX_UBOS_PER_POOL = MAX_SETS; // Allow one UBO per set potentially

		try (MemoryStack stack = stackPush()) {
			// Define pool sizes (adjust counts as needed for your app)
			VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack); // Expand this if using more descriptor
																														// types

			// Size for Combined Image Samplers
			poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_SETS); // Allow up to MAX_SETS
																																		// samplers in total across
																																		// all sets

			// Size for Uniform Buffers (NEW)
			poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(MAX_UBOS_PER_POOL); // Max total UBOs

			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().pPoolSizes(poolSizes)
				.maxSets(MAX_SETS); // Total number of sets that can be allocated
			// Optional: .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT); // If you need to free individual sets

			LongBuffer pDescriptorPool = stack.mallocLong(1);
			vkCheck(vkCreateDescriptorPool(rawDevice, poolInfo, null, pDescriptorPool), "Failed to create descriptor pool");
			this.descriptorPool = pDescriptorPool.get(0);
			Gdx.app.log(logTag, "Descriptor pool created: " + this.descriptorPool);
		}
	}

	/** Allocates a descriptor set using the specified layout.
	 * @param layoutHandle The handle of the VkDescriptorSetLayout to use for allocation.
	 * @return The handle of the allocated VkDescriptorSet. */
	public long allocateSet (long layoutHandle) { // Changed signature
		if (descriptorPool == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("Descriptor pool not initialized before allocateSet");
		}
		if (layoutHandle == VK_NULL_HANDLE) {
			throw new IllegalArgumentException("Layout handle cannot be VK_NULL_HANDLE");
		}

		Gdx.app.log(logTag, "Allocating descriptor set with layout: " + layoutHandle);
		try (MemoryStack stack = stackPush()) {
			VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
				.descriptorPool(descriptorPool)
				// Use the PASSED-IN layout handle:
				.pSetLayouts(stack.longs(layoutHandle)); // <<<<< FIXED

			LongBuffer pDescriptorSet = stack.mallocLong(1);
			// Use vkAllocateDescriptorSets (plural) even for one set
			int err = vkAllocateDescriptorSets(rawDevice, allocInfo, pDescriptorSet);
			// Add specific error logging if needed
			if (err != VK_SUCCESS) {
				Gdx.app.error(logTag, "vkAllocateDescriptorSets failed with error code: " + err);
				// Consider checking for specific errors like OUT_OF_POOL_MEMORY
			}
			vkCheck(err, "Failed to allocate descriptor set (Layout: " + layoutHandle + ")"); // Pass error code to vkCheck

			long setHandle = pDescriptorSet.get(0);
			Gdx.app.log(logTag, "Descriptor set allocated: " + setHandle);
			return setHandle;
		}
	}

	/** Helper to update a descriptor set for a single combined image sampler. Assumes binding 0.
	 *
	 * @param device The VkDevice.
	 * @param set The VkDescriptorSet handle to update.
	 * @param imageView The VkImageView handle.
	 * @param sampler The VkSampler handle.
	 * @param imageLayout The layout the image is expected to be in (e.g., SHADER_READ_ONLY_OPTIMAL). *//*
																																			 * public static void
																																			 * updateCombinedImageSampler
																																			 * (VkDevice device, long
																																			 * set, int binding, long
																																			 * imageView, long
																																			 * sampler, int
																																			 * imageLayout) {
																																			 * Gdx.app.log(
																																			 * "VulkanDescriptorManager",
																																			 * "Updating descriptor set "
																																			 * + set + " binding " +
																																			 * binding); try
																																			 * (MemoryStack stack =
																																			 * stackPush()) {
																																			 * VkDescriptorImageInfo.
																																			 * Buffer imageInfo =
																																			 * VkDescriptorImageInfo.
																																			 * calloc(1, stack)
																																			 * .imageLayout(
																																			 * imageLayout)
																																			 * .imageView(imageView)
																																			 * .sampler(sampler);
																																			 * 
																																			 * VkWriteDescriptorSet.
																																			 * Buffer descriptorWrite
																																			 * =
																																			 * VkWriteDescriptorSet.
																																			 * calloc(1, stack);
																																			 * descriptorWrite.get(0)
																																			 * .sType$Default()
																																			 * .dstSet(set)
																																			 * .dstBinding(binding)
																																			 * // Use specified
																																			 * binding
																																			 * .dstArrayElement(0)
																																			 * .descriptorType(
																																			 * VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
																																			 * .descriptorCount(1)
																																			 * .pImageInfo(imageInfo)
																																			 * ;
																																			 * 
																																			 * vkUpdateDescriptorSets
																																			 * (device,
																																			 * descriptorWrite,
																																			 * null); // null for
																																			 * copies } }
																																			 */

	/** Helper to update a descriptor set for a single combined image sampler. Assumes binding 0.
	 *
	 * @param device The VkDevice.
	 * @param set The VkDescriptorSet handle to update.
	 * @param binding The destination binding index.
	 * @param texture The VulkanTexture containing the view and sampler. */
	public static void updateCombinedImageSampler (VkDevice device, long set, int binding, VulkanTexture texture) {
		if (texture == null || texture.getImageViewHandle() == VK_NULL_HANDLE || texture.getSamplerHandle() == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("Cannot update descriptor set, texture or its view/sampler handle is null.");
		}
		Gdx.app.log("VulkanDescriptorManager", "Updating descriptor set " + set + " binding " + binding + " with Texture");
		try (MemoryStack stack = stackPush()) {
			VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
				.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) // Expect shader read layout
				.imageView(texture.getImageViewHandle()) // Get view from texture
				.sampler(texture.getSamplerHandle()); // Get sampler from texture

			VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
			descriptorWrite.get(0).sType$Default().dstSet(set).dstBinding(binding).dstArrayElement(0)
				.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imageInfo);

			vkUpdateDescriptorSets(device, descriptorWrite, null);
		}
	}

	/** @return The handle for the default single-texture descriptor set layout. */
	public long getDefaultLayout () {
		return singleTextureLayout;
	}

	/** Gets the handle of the managed Vulkan descriptor pool.
	 * <p>
	 * Exposing the pool handle allows external components to allocate sets directly from this manager's pool, necessary for
	 * allocating multiple sets at once or for custom allocation strategies. Ensure the pool was created with sufficient size and
	 * types for all intended allocations.
	 *
	 * @return The VkDescriptorPool handle, or VK_NULL_HANDLE if the pool hasn't been created. */
	public long getPoolHandle () {
		// Optional: Add check if pool is actually created, though returning NULL might be okay
		// if the caller checks it. Throwing might be safer if it should always exist when called.
		if (descriptorPool == VK_NULL_HANDLE) {
			Gdx.app.error(logTag, "getPoolHandle() called before descriptor pool was created!");
			// Consider: throw new IllegalStateException("Descriptor pool handle requested before creation.");
		}
		return descriptorPool;
	}

	@Override
	public void dispose () {
		Gdx.app.log(logTag, "Disposing descriptor manager...");
		// Sets are implicitly freed when the pool is destroyed
		if (descriptorPool != VK_NULL_HANDLE) {
			Gdx.app.log(logTag, "Destroying descriptor pool: " + descriptorPool);
			vkDestroyDescriptorPool(rawDevice, descriptorPool, null);
			descriptorPool = VK_NULL_HANDLE;
		}
		// Layouts must be destroyed explicitly
		if (singleTextureLayout != VK_NULL_HANDLE) {
			Gdx.app.log(logTag, "Destroying descriptor layout: " + singleTextureLayout);
			vkDestroyDescriptorSetLayout(rawDevice, singleTextureLayout, null);
			singleTextureLayout = VK_NULL_HANDLE;
		}
		// TODO: If caching layouts, iterate through cache and destroy all.
		Gdx.app.log(logTag, "Descriptor manager disposed.");
	}
}
