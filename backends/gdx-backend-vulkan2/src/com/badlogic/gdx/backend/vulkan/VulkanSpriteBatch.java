
package com.badlogic.gdx.backend.vulkan; // Example package

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram; // Needed for constants
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.NumberUtils;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.*;

import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanSpriteBatch implements Batch, Disposable {

	private final String TAG = "VulkanSpriteBatch";
	int cnt = 0;
	// --- Dependencies (obtained via Gdx.graphics) ---
	private final VulkanDevice device;
	private final VkDevice rawDevice;
	private final long vmaAllocator;
	private final VulkanPipelineManager pipelineManager;
	private final VulkanDescriptorManager descriptorManager;
	private final Color batchColor = new Color();
	private boolean textureDescriptorNeedsUpdate = true;
	private boolean pipelineAndSetBoundThisBatch = false;
	// --- Batch Resources ---
	private VulkanBuffer vertexBuffer; // Host-visible buffer for vertex data
	private VulkanBuffer indexBuffer; // Device-local buffer for quad indices
	private VulkanBuffer projMatrixUbo; // Host-visible buffer for projection matrix uniform

	private ByteBuffer mappedVertexByteBuffer; // Direct ByteBuffer mapped to vertexBuffer's memory
	private FloatBuffer vertices; // Float view of mappedVertexByteBuffer
	private final ShortBuffer indicesCpu; // CPU buffer to setup index buffer once

	private final int vertexSize; // Size of one vertex in bytes
	private final int vertexFloats; // Size of one vertex in floats
	private final int maxSprites; // Max sprites per flush

	private long batchPipeline = VK_NULL_HANDLE;
	private long batchPipelineLayout = VK_NULL_HANDLE;
	private long batchDescriptorLayout = VK_NULL_HANDLE;
	private final java.util.List<Long> batchDescriptorSets; // Use a List
	private final int maxFramesInFlight;

	// --- Batch State ---
	private boolean drawing = false; // Is begin() called?
	private VkCommandBuffer currentCommandBuffer = null;
	private final Matrix4 projectionMatrix = new Matrix4();
	private final Matrix4 transformMatrix = new Matrix4(); // Usually identity for SpriteBatch
	private final Matrix4 combinedMatrix = new Matrix4();
	private VulkanTexture lastTexture = null;
	private float invTexWidth = 0, invTexHeight = 0;
	private float colorPacked = Color.WHITE_FLOAT_BITS; // Packed float color

	private int spriteCount = 0; // Number of sprites added since last flush
	private int vertexBufferIdx = 0; // Current position in vertex buffer (in floats)

	// Define vertex layout for SpriteBatch (Pos2, Color1Packed, UV2)
	// Matches default libGDX SpriteBatch shader attributes
	public static final VertexAttributes BATCH_ATTRIBUTES = new VertexAttributes(
		new VertexAttribute(VertexAttributes.Usage.Position, 2, ShaderProgram.POSITION_ATTRIBUTE),
		new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, ShaderProgram.COLOR_ATTRIBUTE), // Stored as 1 float, needs
																																	// GL_UNSIGNED_BYTE type? Check
																																	// shader. Let's use GL_FLOAT
																																	// for the packed float
																																	// initially.
		new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE + "0"));
	public static final int POSITION_COMPONENTS = 2;
	public static final int COLOR_COMPONENTS = 1; // Packed into one float
	public static final int TEXCOORD_COMPONENTS = 2;
	public static final int COMPONENTS_PER_VERTEX = POSITION_COMPONENTS + COLOR_COMPONENTS + TEXCOORD_COMPONENTS; // 2 + 1 + 2 = 5
	public static final int VERTICES_PER_SPRITE = 4; // Using indexed drawing for quads
	public static final int INDICES_PER_SPRITE = 6;

	public VulkanSpriteBatch () {
		this(1024); // Default batch size
	}

	// Constructor for VulkanSpriteBatch
	public VulkanSpriteBatch (int size) {
		this.maxSprites = size;
		Gdx.app.log(TAG, "Initializing with size: " + size);

		// --- 1. Get Dependencies ---
		if (!(Gdx.graphics instanceof VulkanGraphics)) {
			throw new GdxRuntimeException("VulkanSpriteBatch requires the VulkanGraphics backend!");
		}
		VulkanGraphics gfx = (VulkanGraphics)Gdx.graphics;

		this.device = gfx.getVulkanDevice();
		this.vmaAllocator = gfx.getVmaAllocator();
		this.pipelineManager = gfx.getPipelineManager();
		this.descriptorManager = gfx.getDescriptorManager();

		if (device == null || vmaAllocator == VK_NULL_HANDLE || pipelineManager == null || descriptorManager == null) {
			throw new GdxRuntimeException("Failed to retrieve necessary Vulkan managers!");
		}
		this.rawDevice = device.getRawDevice();
		VulkanSwapchain swapchain = gfx.getSwapchain(); // Get swapchain via graphics
		if (swapchain == null) {
			throw new GdxRuntimeException("Cannot initialize VulkanSpriteBatch before swapchain exists.");
		}

		// --- MODIFICATION START ---
		// Original value based on swapchain, keep for logging context
		int actualSwapchainImageCount = swapchain.getImageCount();
		if (actualSwapchainImageCount <= 0) {
			throw new GdxRuntimeException("Swapchain image count is invalid: " + actualSwapchainImageCount);
		}
		// Force the batch to align with the MAX_FRAMES_IN_FLIGHT = 1 sync setup for now
		final int descriptorSetCount = 1;
		// Use the original swapchain count internally if needed elsewhere, but use '1' for sets
		this.maxFramesInFlight = descriptorSetCount; // Set internal field used later? Check if needed.
		this.batchDescriptorSets = new ArrayList<>(descriptorSetCount); // Size list for 1 set
		Gdx.app.log(TAG, "Swapchain images: " + actualSwapchainImageCount + ", BUT using Descriptor set buffer count: "
			+ descriptorSetCount + " (Aligned to MAX_FRAMES_IN_FLIGHT=1 TEMP FIX)");
		// --- MODIFICATION END ---

		// --- 2. Create Buffers ---
		// Vertex Buffer (Code remains the same)
		this.vertexSize = COMPONENTS_PER_VERTEX * Float.BYTES;
		this.vertexFloats = COMPONENTS_PER_VERTEX;
		int maxVertices = size * VERTICES_PER_SPRITE;
		long vertexBufferSizeBytes = (long)maxVertices * vertexSize;
		this.vertexBuffer = VulkanResourceUtil.createManagedBuffer(vmaAllocator, vertexBufferSizeBytes,
			VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, VMA_MEMORY_USAGE_AUTO,
			VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
		PointerBuffer pDataVB = MemoryUtil.memAllocPointer(1);
		try {
			vkCheck(vmaMapMemory(vmaAllocator, vertexBuffer.allocationHandle, pDataVB), "VMA Failed to map vertex buffer for batch");
			this.mappedVertexByteBuffer = MemoryUtil.memByteBuffer(pDataVB.get(0), (int)vertexBufferSizeBytes);
			this.vertices = this.mappedVertexByteBuffer.asFloatBuffer();
			Gdx.app.log(TAG, "Vertex buffer created and persistently mapped.");
		} finally {
			MemoryUtil.memFree(pDataVB);
		}

		// Index Buffer (Code remains the same)
		this.indicesCpu = BufferUtils.newShortBuffer(size * INDICES_PER_SPRITE);
		((Buffer)this.indicesCpu).clear();
		for (int i = 0, v = 0; i < size * INDICES_PER_SPRITE; i += INDICES_PER_SPRITE, v += 4) {
			indicesCpu.put((short)v);
			indicesCpu.put((short)(v + 1));
			indicesCpu.put((short)(v + 2));
			indicesCpu.put((short)(v + 2));
			indicesCpu.put((short)(v + 3));
			indicesCpu.put((short)v);
		}
		((Buffer)this.indicesCpu).flip();
		long indexBufferSize = (long)indicesCpu.limit() * Short.BYTES;
		VulkanBuffer stagingIndexBuffer = null;
		try {
			stagingIndexBuffer = VulkanResourceUtil.createManagedBuffer(vmaAllocator, indexBufferSize,
				VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VMA_MEMORY_USAGE_AUTO,
				VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
			PointerBuffer pDataIB = MemoryUtil.memAllocPointer(1);
			try {
				vkCheck(vmaMapMemory(vmaAllocator, stagingIndexBuffer.allocationHandle, pDataIB), "VMA map failed for index staging");
				ByteBuffer stagingIndexBytes = MemoryUtil.memByteBuffer(pDataIB.get(0), (int)indexBufferSize);
				stagingIndexBytes.asShortBuffer().put(indicesCpu);
				vmaUnmapMemory(vmaAllocator, stagingIndexBuffer.allocationHandle);
			} finally {
				MemoryUtil.memFree(pDataIB);
			}

			this.indexBuffer = VulkanResourceUtil.createManagedBuffer(vmaAllocator, indexBufferSize,
				VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT, VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0);

			final long srcHandle = stagingIndexBuffer.bufferHandle;
			final long dstHandle = this.indexBuffer.bufferHandle;
			device.executeSingleTimeCommands(cmd -> {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).size(indexBufferSize);
					vkCmdCopyBuffer(cmd, srcHandle, dstHandle, region);
				}
			});
			Gdx.app.log(TAG, "Index buffer created and uploaded.");
		} finally {
			if (stagingIndexBuffer != null) stagingIndexBuffer.dispose();
		}

		// Projection Matrix UBO (Code remains the same)
		long uboSize = 16 * Float.BYTES;
		this.projMatrixUbo = VulkanResourceUtil.createManagedBuffer(vmaAllocator, uboSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
			VMA_MEMORY_USAGE_AUTO, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT // No persistent mapping needed
		);
		if (this.projMatrixUbo == null) {
			throw new GdxRuntimeException("Failed to create Projection matrix UBO");
		}
		Gdx.app.log(TAG, "Projection matrix UBO created.");

		// --- 3. Create Descriptor Set Layout --- (Code remains the same)
		createBatchDescriptorSetLayout(); // Sets this.batchDescriptorLayout

		// --- 4. Allocate Descriptor Sets (Buffered) ---
		// --- MODIFICATION START ---
		// Use the 'descriptorSetCount' variable (set to 1 earlier)
		Gdx.app.log(TAG, "Allocating " + descriptorSetCount + " descriptor sets...");
		if (this.batchDescriptorLayout == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("Cannot allocate descriptor sets, layout handle is null.");
		}
		long poolHandle = descriptorManager.getPoolHandle();
		if (poolHandle == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("Cannot allocate descriptor sets, pool handle is null.");
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			// Allocate space for 'descriptorSetCount' layouts (which is 1)
			LongBuffer layouts = stack.mallocLong(descriptorSetCount);
			for (int i = 0; i < descriptorSetCount; i++) { // Loop runs once for i=0
				if (this.batchDescriptorLayout == VK_NULL_HANDLE) {
					throw new IllegalStateException("Batch DSL handle became null unexpectedly.");
				}
				layouts.put(i, this.batchDescriptorLayout);
			}
			// layouts.flip(); // Not needed for size 1, but doesn't hurt

			VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
			allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
			allocInfo.pNext(0);
			allocInfo.descriptorPool(descriptorManager.getPoolHandle());
			allocInfo.pSetLayouts(layouts); // Pass the buffer containing the single layout handle

			int reportedCount = allocInfo.descriptorSetCount();
			Gdx.app.log(TAG, "DEBUG: Count reported by allocInfo.descriptorSetCount() = " + reportedCount);

			// Apply HACK if needed, using descriptorSetCount (which is 1)
			if (reportedCount == 0 && descriptorSetCount > 0) {
				Gdx.app.error(TAG, "DEBUG: Implicit count setting failed! Using native static setter as a HACK.");
				VkDescriptorSetAllocateInfo.ndescriptorSetCount(allocInfo.address(), descriptorSetCount); // Set count to 1
				Gdx.app.log(TAG, "DEBUG: Count after native set = " + allocInfo.descriptorSetCount());
			}

			// Allocate space for 'descriptorSetCount' handles (which is 1)
			LongBuffer pSets = stack.mallocLong(descriptorSetCount);
			vkCheck(vkAllocateDescriptorSets(rawDevice, allocInfo, pSets), "Failed to allocate batch descriptor sets");

			// Loop runs once for i=0
			for (int i = 0; i < descriptorSetCount; i++) {
				long setHandle = pSets.get(i);
				this.batchDescriptorSets.add(setHandle); // Add the single handle to the list
				Gdx.app.log(TAG, "Allocated batch descriptor set [" + i + "]: " + setHandle);
				if (this.projMatrixUbo == null) {
					throw new IllegalStateException("projMatrixUbo is null before updateUboDescriptor!");
				}
				updateUboDescriptor(setHandle, this.projMatrixUbo); // Update UBO binding for the single set
			}
		}
		Gdx.app.log(TAG, "Batch descriptor sets allocated and initial UBO updated.");
		// --- MODIFICATION END ---

		// --- 5. Create Pipeline Layout --- (Code remains the same)
		createBatchPipeline(); // Sets this.batchPipelineLayout

		Gdx.app.log(TAG, "Initialization complete.");
	}

	private void createBatchDescriptorSetLayout () {
		Gdx.app.log(TAG, "Creating batch descriptor set layout...");
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
			// Binding 0: Uniform Buffer (Projection Matrix) - Vertex Stage
			bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
				.stageFlags(VK_SHADER_STAGE_VERTEX_BIT).pImmutableSamplers(null);
			// Binding 1: Combined Image Sampler (Texture) - Fragment Stage
			bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
				.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).pImmutableSamplers(null);

			VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
				.pBindings(bindings);

			LongBuffer pLayout = stack.mallocLong(1);
			vkCheck(vkCreateDescriptorSetLayout(rawDevice, layoutInfo, null, pLayout),
				"Failed to create batch descriptor set layout");
			this.batchDescriptorLayout = pLayout.get(0); // Store the handle
			Gdx.app.log(TAG, "Batch descriptor set layout created: " + this.batchDescriptorLayout);
		}
	}

	private void updateUboDescriptor (long descriptorSet, VulkanBuffer ubo) {
		Gdx.app.log(TAG, "Updating UBO descriptor for set " + descriptorSet);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(ubo.bufferHandle).offset(0)
				.range(ubo.size); // Use full buffer range

			VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
			descriptorWrite.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0) // UBO at binding 0
				.dstArrayElement(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(bufferInfo); // Link
																																										// buffer
																																										// info

			vkUpdateDescriptorSets(rawDevice, descriptorWrite, null);
		}
	}

	private void updateProjectionMatrixUBO () {
		if (projMatrixUbo == null || vmaAllocator == VK_NULL_HANDLE) {
			Gdx.app.error(TAG, "Cannot update UBO - buffer or allocator is null.");
			return; // Or throw exception
		}
		// Optional: Implement a check here to see if this.projectionMatrix has actually changed
		// since the last update to avoid redundant map/copy/flush operations.
		// For now, we update unconditionally in begin().

		// Gdx.app.log(TAG, "Updating projection matrix UBO with:\n" + this.projectionMatrix); // Add for debugging if needed

		PointerBuffer pData = MemoryUtil.memAllocPointer(1);
		try {
			// Map the buffer
			vkCheck(vmaMapMemory(vmaAllocator, projMatrixUbo.allocationHandle, pData), "VMA map failed for projection update");
			ByteBuffer uboByteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int)projMatrixUbo.size);
			FloatBuffer uboFloatBuffer = uboByteBuffer.asFloatBuffer();

			// Copy matrix data (column-major)
			uboFloatBuffer.put(this.projectionMatrix.val);
			// No flip() needed

			// Flush allocation to ensure GPU visibility (CRITICAL)
			Vma.vmaFlushAllocation(vmaAllocator, projMatrixUbo.allocationHandle, 0, projMatrixUbo.size);

			// Unmap the buffer
			vmaUnmapMemory(vmaAllocator, projMatrixUbo.allocationHandle);

		} catch (Exception e) {
			// Ensure pData is freed if mapping succeeded but subsequent steps failed
			if (pData.get(0) != 0) { // Check if mapping was successful before unmapping attempt
				try {
					vmaUnmapMemory(vmaAllocator, projMatrixUbo.allocationHandle);
				} catch (Exception unmapE) {
					/* Ignore */ }
			}
			MemoryUtil.memFree(pData); // Free the pointer buffer itself
			throw new GdxRuntimeException("Failed to update projection matrix UBO", e);
		} finally {
			// Ensure pData is always freed
			MemoryUtil.memFree(pData);
		}
	}

	private void updateTextureDescriptor (long descriptorSet, VulkanTexture texture) {
		if (texture == null) return; // Nothing to update
		Gdx.app.log(TAG, "Updating Texture descriptor for set " + descriptorSet + " with TexView: " + texture.getImageViewHandle()
			+ " and Sampler: " + texture.getSamplerHandle());
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
				.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(texture.getImageViewHandle())
				.sampler(texture.getSamplerHandle()); // Use texture's sampler

			VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
			descriptorWrite.get(0).sType$Default().dstSet(descriptorSet).dstBinding(1) // Texture sampler at binding 1
				.dstArrayElement(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
				.pImageInfo(imageInfo); // Link image info

			vkUpdateDescriptorSets(rawDevice, descriptorWrite, null);
		}
	}

	private void createBatchPipeline () {
		Gdx.app.log(TAG, "Requesting sprite batch pipeline...");
		// Ideally, request from PipelineManager based on a config
		// For now, assume PipelineManager can create/get a pipeline suitable for sprite batching
		// This requires defining the pipeline state (vertex input from BATCH_ATTRIBUTES, blending, etc.)
		// AND using the batchDescriptorLayout created above.
		// How to get the render pass? It changes. Pipeline must be compatible.
		// This suggests pipeline creation/lookup might need to happen inside begin() or flush()
		// OR PipelineManager needs a way to get a pipeline *compatible* with a render pass.

		// --- TEMPORARY HACK: Create pipeline directly here (violates manager pattern) ---
		// --- This should be moved to PipelineManager properly ---
		Gdx.app.log(TAG, "TODO: Pipeline creation should be moved to VulkanPipelineManager!");
		FileHandle vertShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.vert.spv"); // Adjust path if needed
		FileHandle fragShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.frag.spv"); // Adjust path if needed
		if (!vertShaderFile.exists() || !fragShaderFile.exists()) {
			throw new GdxRuntimeException("Default SpriteBatch SPIR-V shaders not found!");
		}
		// Need current render pass - cannot create pipeline fully here!
		// Let's assume PipelineManager can give us one based on layout + shaders + renderpass later.
		// We store the layout handle; pipeline handle will be retrieved/bound in flush().
		Gdx.app.log(TAG, "Deferring actual pipeline handle retrieval until rendering.");
		// this.batchPipeline = pipelineManager.getOrCreateSpriteBatchPipeline(vertShaderFile, fragShaderFile,
		// batchDescriptorLayout, currentRenderPassHandle);

		// Need pipeline layout handle for binding descriptor set
		this.batchPipelineLayout = pipelineManager.getOrCreatePipelineLayout(this.batchDescriptorLayout); // Assume PM provides this
		if (this.batchPipelineLayout == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("Failed to get/create pipeline layout for batch.");
		}
		Gdx.app.log(TAG, "Using pipeline layout: " + this.batchPipelineLayout);
	}

	// --- Batch Implementation Methods (Stubs for now) ---

	@Override
	public void begin () {
		if (drawing) throw new IllegalStateException("Batch.end must be called before begin.");
		// How to get command buffer? Assume it's passed externally or set somehow.
		// VulkanGraphics.beginFrame() returns it -> how does batch get it?
		// Simplest for now: Add begin(VkCommandBuffer)
		throw new GdxRuntimeException("begin() requires VkCommandBuffer parameter. Use begin(VkCommandBuffer).");
	}

	public void begin (VkCommandBuffer commandBuffer) {
		if (drawing) throw new IllegalStateException("Batch.end must be called before begin.");
		if (commandBuffer == null) throw new IllegalArgumentException("CommandBuffer cannot be null.");

		this.currentCommandBuffer = commandBuffer;
		this.textureDescriptorNeedsUpdate = true;
		this.pipelineAndSetBoundThisBatch = false;

		updateProjectionMatrixUBO();

		spriteCount = 0;
		vertexBufferIdx = 0; // Reset vertex buffer index
		drawing = true;
	}

	@Override
	public void end () {
		if (!drawing) throw new IllegalStateException("Batch.begin must be called before end.");
		if (spriteCount > 0) {
			flush(); // Flush remaining sprites
		}
		drawing = false;
		currentCommandBuffer = null; // Clear command buffer reference
	}

	@Override
	public void setColor (Color tint) {
		setColor(tint.r, tint.g, tint.b, tint.a);
	}

	@Override
	public void setColor (float r, float g, float b, float a) {
		this.colorPacked = Color.toFloatBits(r, g, b, a);
	}

	@Override
	public Color getColor () {
		int intBits = NumberUtils.floatToIntBits(this.colorPacked);
		Color color = this.batchColor; // Use the member field
		color.r = (intBits & 0xff) / 255f;
		color.g = ((intBits >>> 8) & 0xff) / 255f;
		color.b = ((intBits >>> 16) & 0xff) / 255f;
		color.a = ((intBits >>> 24) & 0xff) / 255f;
		return color;
	}

	@Override
	public void setPackedColor (float packedColor) {

	}

	@Override
	public float getPackedColor () {
		return colorPacked;
	}

	// --- Draw Overloads ---
	// Main internal draw logic:
	private void drawInternal (VulkanTexture texture, float[] spriteVertices, int numFloats) {
		if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");
		if (texture != lastTexture) { // Texture switch check
			flush(); // Flush previous batch
			lastTexture = texture;
			invTexWidth = 1.0f / texture.getWidth(); // Or use TextureRegion size?
			invTexHeight = 1.0f / texture.getHeight();
			this.textureDescriptorNeedsUpdate = true;
			Gdx.app.log(TAG, "Texture switch detected, flushing previous batch.");
		} else if (vertexBufferIdx + numFloats > vertices.capacity()) { // Buffer full check
			flush();
			Gdx.app.log(TAG, "Vertex buffer full, flushing previous batch.");
		}

		// Copy vertex data into mapped buffer
		vertices.position(vertexBufferIdx); // Set position
		vertices.put(spriteVertices, 0, numFloats); // Put data
		vertexBufferIdx += numFloats;
		spriteCount++;
	}

	// Example public draw overload
	@Override
	public void draw (Texture texture, float x, float y, float originX, float originY, float width, float height, float scaleX,
		float scaleY, float rotation, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {

		if (!(texture instanceof VulkanTexture)) {
			throw new GdxRuntimeException("Texture is not a VulkanTexture instance!");
		}
		VulkanTexture vkTexture = (VulkanTexture)texture;

		// --- Calculate vertices (Standard SpriteBatch math) ---
		// Based on SpriteBatch source:
		// https://github.com/libgdx/libgdx/blob/master/gdx/src/com/badlogic/gdx/graphics/g2d/SpriteBatch.java
		float[] verts = new float[4 * vertexFloats]; // 4 vertices * 5 floats/vertex = 20 floats
		int i = 0;

		final float worldOriginX = x + originX;
		final float worldOriginY = y + originY;
		float fx = -originX;
		float fy = -originY;
		float fx2 = width - originX;
		float fy2 = height - originY;

		if (scaleX != 1 || scaleY != 1) {
			fx *= scaleX;
			fy *= scaleY;
			fx2 *= scaleX;
			fy2 *= scaleY;
		}

		final float p1x = fx;
		final float p1y = fy;
		final float p2x = fx;
		final float p2y = fy2;
		final float p3x = fx2;
		final float p3y = fy2;
		final float p4x = fx2;
		final float p4y = fy;

		float x1, y1, x2, y2, x3, y3, x4, y4;

		if (rotation != 0) {
			final float cos = MathUtils.cosDeg(rotation);
			final float sin = MathUtils.sinDeg(rotation);
			x1 = cos * p1x - sin * p1y;
			y1 = sin * p1x + cos * p1y;
			x2 = cos * p2x - sin * p2y;
			y2 = sin * p2x + cos * p2y;
			x3 = cos * p3x - sin * p3y;
			y3 = sin * p3x + cos * p3y;
			x4 = x1 + (x3 - x2);
			y4 = y3 - (y2 - y1); // p4 = p1 + (p3 - p2)
		} else {
			x1 = p1x;
			y1 = p1y;
			x2 = p2x;
			y2 = p2y;
			x3 = p3x;
			y3 = p3y;
			x4 = p4x;
			y4 = p4y;
		}

		x1 += worldOriginX;
		y1 += worldOriginY;
		x2 += worldOriginX;
		y2 += worldOriginY;
		x3 += worldOriginX;
		y3 += worldOriginY;
		x4 += worldOriginX;
		y4 += worldOriginY;

		// Calculate UVs
		float u = srcX * invTexWidth;
		float v = (srcY + srcHeight) * invTexHeight; // V = 1 - v usually? Check coord system. Assume 0,0 is top-left for region.
		float u2 = (srcX + srcWidth) * invTexWidth;
		float v2 = srcY * invTexHeight;

		if (flipX) {
			float tmp = u;
			u = u2;
			u2 = tmp;
		}
		if (flipY) {
			float tmp = v;
			v = v2;
			v2 = tmp;
		}

		// Vertex 0 (Bottom Left)
		verts[i++] = x1;
		verts[i++] = y1;
		verts[i++] = colorPacked;
		verts[i++] = u;
		verts[i++] = v;
		// Vertex 1 (Top Left)
		verts[i++] = x2;
		verts[i++] = y2;
		verts[i++] = colorPacked;
		verts[i++] = u;
		verts[i++] = v2;
		// Vertex 2 (Top Right)
		verts[i++] = x3;
		verts[i++] = y3;
		verts[i++] = colorPacked;
		verts[i++] = u2;
		verts[i++] = v2;
		// Vertex 3 (Bottom Right)
		verts[i++] = x4;
		verts[i++] = y4;
		verts[i++] = colorPacked;
		verts[i++] = u2;
		verts[i++] = v;

		// Pass to internal draw method which handles batching/flushing
		drawInternal(vkTexture, verts, i); // Pass the 4 vertices (20 floats)
	}

	@Override
	public void draw (Texture texture, float x, float y, float width, float height, int srcX, int srcY, int srcWidth,
		int srcHeight, boolean flipX, boolean flipY) {

	}

	@Override
	public void draw (Texture texture, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {

	}

	@Override
	public void draw (Texture texture, float x, float y, float width, float height, float u, float v, float u2, float v2) {

	}

	@Override
	public void draw (Texture texture, float x, float y) {

	}

	@Override
	public void draw (Texture texture, float x, float y, float width, float height) {

	}

	/** Helper method to retrieve the current swapchain RenderPass handle via Gdx.graphics. Assumes rendering is happening within
	 * the main swapchain render pass context.
	 *
	 * @return The VkRenderPass handle.
	 * @throws GdxRuntimeException if Gdx.graphics is not VulkanGraphics or swapchain/renderpass is invalid. */
	private long getCurrentRenderPassHandle () {
		if (Gdx.graphics instanceof VulkanGraphics) {
			VulkanGraphics gfx = (VulkanGraphics)Gdx.graphics;
			// Ensure VulkanGraphics has a getter for the swapchain
			VulkanSwapchain swapchain = gfx.getSwapchain();
			if (swapchain != null) {
				// Ensure VulkanSwapchain has a getter for the render pass
				long rpHandle = swapchain.getRenderPass();
				if (rpHandle != VK_NULL_HANDLE) {
					return rpHandle;
				} else {
					throw new GdxRuntimeException(
						"VulkanSpriteBatch: Failed to get RenderPass handle from VulkanSwapchain (handle is VK_NULL_HANDLE)");
				}
			} else {
				throw new GdxRuntimeException("VulkanSpriteBatch: Failed to get VulkanSwapchain from VulkanGraphics");
			}
		} else {
			throw new GdxRuntimeException("VulkanSpriteBatch: Gdx.graphics is not an instance of VulkanGraphics");
		}
	}

	@Override
	public void dispose () {
		Gdx.app.log(TAG, "Disposing...");
		if (vertexBuffer != null && mappedVertexByteBuffer != null) {
			// Unmapping might not be strictly necessary if VMA handles it on buffer destroy, but good practice
			try {
				vmaUnmapMemory(vmaAllocator, vertexBuffer.allocationHandle);
			} catch (Exception e) {
				Gdx.app.error(TAG, "Error unmapping vertex buffer", e);
			}
			mappedVertexByteBuffer = null;
			vertices = null;
		}
		if (vertexBuffer != null) {
			vertexBuffer.dispose();
			vertexBuffer = null;
		}
		if (indexBuffer != null) {
			indexBuffer.dispose();
			indexBuffer = null;
		}
		if (projMatrixUbo != null) {
			projMatrixUbo.dispose();
			projMatrixUbo = null;
		}

		// Layouts are managed/cached by PipelineManager, no need to destroy batchPipelineLayout here
		// Descriptor Set Layout might need destruction if not cached/managed by DescriptorManager
		if (batchDescriptorLayout != VK_NULL_HANDLE) {
			// TODO: Check if DescriptorManager handles layout lifetime. If not, destroy here:
			// vkDestroyDescriptorSetLayout(rawDevice, batchDescriptorLayout, null);
			// For now, assume DescriptorManager or PipelineManager owns layout if retrieved from them.
			// If created directly here (as in current createBatchDescriptorSetLayout), destroy it.
			Gdx.app.log(TAG, "Destroying batch descriptor set layout: " + batchDescriptorLayout);
			vkDestroyDescriptorSetLayout(rawDevice, batchDescriptorLayout, null);
			batchDescriptorLayout = VK_NULL_HANDLE;
		}

		// Descriptor sets are freed when the pool is destroyed (in DescriptorManager.dispose)
		batchDescriptorSets.clear(); // Just clear the list of handles

		Gdx.app.log(TAG, "Disposed.");
	}

	/** Draws a polygon sprite described by the given vertices. Assumes the vertices contain all vertex attributes interleaved
	 * (pos, color, uv). Handles texture switches, buffer flushing, and copying vertex data respecting offset and count.
	 *
	 * @param texture The texture to use for this batch. Must be a VulkanTexture.
	 * @param spriteVertices The float array containing vertex data.
	 * @param offset The starting offset in the spriteVertices array.
	 * @param count The number of float components (not vertices) to read from the array. */
	@Override
	public void draw (Texture texture, float[] spriteVertices, int offset, int count) {
		// --- Precondition Checks ---
		if (!drawing) throw new IllegalStateException("VulkanSpriteBatch.begin must be called before draw.");
		if (texture == null) throw new IllegalArgumentException("Texture cannot be null.");
		if (!(texture instanceof VulkanTexture)) {
			throw new GdxRuntimeException("Texture needs to be a VulkanTexture. Got: " + texture.getClass().getSimpleName());
		}
		if (count <= 0) {
			// It's valid for BitmapFontCache to call with count=0 sometimes, just return.
			// Gdx.app.log(TAG, "Draw call with count <= 0, skipping.");
			return;
		}
		// Basic sanity check on parameters
		if (offset < 0 || count < 0 || offset + count > spriteVertices.length) {
			throw new IllegalArgumentException("Invalid offset/count for spriteVertices array. offset=" + offset + ", count=" + count
				+ ", arrayLength=" + spriteVertices.length);
		}
		// Ensure vertex structure constants are valid
		if (vertexFloats <= 0 || VERTICES_PER_SPRITE <= 0) {
			throw new IllegalStateException("Batch vertex structure constants not initialized correctly.");
		}

		VulkanTexture vkTexture = (VulkanTexture)texture;

		// --- Texture Switching ---
		if (vkTexture != lastTexture) {
			switchTexture(vkTexture);
			// After switchTexture, flush() was called, so vertexBufferIdx is 0.
		}

		// --- Vertex Copy Loop (Handles buffer full and processes 'count' floats) ---
		while (count > 0) {
			// Calculate remaining capacity in the vertex buffer (in floats)
			int remainingFloats = this.vertices.capacity() - this.vertexBufferIdx;

			// If the buffer is full (or cannot hold even one full vertex), flush it.
			// Using "< vertexFloats" handles the case where remaining is > 0 but too small.
			if (remainingFloats < vertexFloats && this.vertexBufferIdx > 0) {
				Gdx.app.log(TAG, "Vertex buffer cannot fit next vertex/is full (" + remainingFloats + " remaining), flushing.");
				flush(); // This resets vertexBufferIdx to 0
				remainingFloats = this.vertices.capacity(); // Buffer is now empty

				// After flush, re-check texture state as flush might have altered it indirectly
				// or if switchTexture logic needs refinement.
				if (vkTexture != lastTexture) {
					Gdx.app.error(TAG, "Texture changed unexpectedly after buffer-full flush! Reswitching.");
					switchTexture(vkTexture); // This will flush potentially empty buffer again, but ensures state
					remainingFloats = this.vertices.capacity();
				}
				if (remainingFloats < vertexFloats) { // Check capacity again after potential flush
					throw new GdxRuntimeException(
						"Vertex buffer capacity (" + remainingFloats + ") is too small for even one vertex (" + vertexFloats + ").");
				}
			} else if (remainingFloats == 0 && this.vertexBufferIdx > 0) {
				// Explicitly handle case where buffer was exactly filled by previous operation
				Gdx.app.log(TAG, "Vertex buffer exactly full, flushing.");
				flush();
				remainingFloats = this.vertices.capacity();
				if (vkTexture != lastTexture) { /* As above */
					Gdx.app.error(TAG, "...");
					switchTexture(vkTexture);
					remainingFloats = this.vertices.capacity();
				}
				if (remainingFloats == 0) { /* As above */
					throw new GdxRuntimeException("...");
				}
			}

			// Determine how many floats to copy in this iteration:
			int copyCount = Math.min(remainingFloats, count);

			// If somehow copyCount is still non-positive, break to avoid issues.
			if (copyCount <= 0) {
				Gdx.app.error(TAG,
					"Calculated copyCount is zero or negative (" + copyCount + ") mid-loop. Breaking draw loop. remainingFloats="
						+ remainingFloats + ", count=" + count + ", vertexBufferIdx=" + vertexBufferIdx);
				break;
			}

			// Calculate sprites represented by this copy BEFORE the copy happens
			int verticesInCopy = copyCount / vertexFloats;
			int spritesInCopy = verticesInCopy / VERTICES_PER_SPRITE;
			// Gdx.app.log(TAG, "Draw: Calculated spritesInCopy = " + spritesInCopy + " (copyCount=" + copyCount + ", vertexFloats="
			// + vertexFloats + ", VERTICES_PER_SPRITE=" + VERTICES_PER_SPRITE + ")"); // <-- ADD THIS
			// Warn if data isn't sprite-aligned, although we still copy the floats requested
			if (copyCount % (vertexFloats * VERTICES_PER_SPRITE) != 0) {
				Gdx.app.log(TAG, "Warning: copyCount (" + copyCount + ") in draw call does not align with full sprites.");
			}

			// Copy the vertex data using offset and copyCount
			try {
				// Bounds check for target buffer before setting position and putting data
				if (vertexBufferIdx < 0 || copyCount < 0 || vertexBufferIdx + copyCount > this.vertices.capacity()) {
					throw new IllegalArgumentException(
						"Internal Error: Invalid idx/copyCount for target vertex buffer PRE-COPY. vertexBufferIdx=" + vertexBufferIdx
							+ ", copyCount=" + copyCount + ", capacity=" + this.vertices.capacity());
				}

				this.vertices.position(this.vertexBufferIdx);
				// Source bounds check happened earlier
				this.vertices.put(spriteVertices, offset, copyCount); // Copy the slice

			} catch (IndexOutOfBoundsException | BufferOverflowException e) {
				Gdx.app.error(TAG,
					"Buffer Exception during vertices.put. vertexBufferIdx=" + vertexBufferIdx + ", offset=" + offset + ", copyCount="
						+ copyCount + ", vertices.capacity=" + vertices.capacity() + ", spriteVertices.length=" + spriteVertices.length,
					e);
				throw new GdxRuntimeException("Error copying vertex data", e);
			} catch (IllegalArgumentException e) { // Catch our explicit check
				Gdx.app.error(TAG, "Error copying vertex data due to internal bounds check", e);
				throw new GdxRuntimeException("Error copying vertex data", e);
			}

			// Update indices and remaining count for the next iteration
			this.vertexBufferIdx += copyCount; // Advance target buffer index
			this.spriteCount += spritesInCopy; // Increment sprite count based on full sprites copied
			count -= copyCount; // Decrease remaining floats in input
			offset += copyCount; // Advance offset in input array
		}
	}

	@Override
	public void draw (TextureRegion region, float x, float y) {
		draw(region.getTexture(), x, y, region.getRegionWidth(), region.getRegionHeight());
	}

	@Override
	public void draw (TextureRegion region, float x, float y, float width, float height) {
		draw(region, x, y, 0, 0, width, height, 1, 1, 0);
	}

	@Override
	public void draw (TextureRegion region, float x, float y, float originX, float originY, float width, float height,
		float scaleX, float scaleY, float rotation) {
		draw(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation, region.getRegionX(),
			region.getRegionY(), region.getRegionWidth(), region.getRegionHeight(), false, false);
	}

	@Override
	public void draw (TextureRegion region, float x, float y, float originX, float originY, float width, float height,
		float scaleX, float scaleY, float rotation, boolean clockwise) { /* TODO */
		throw new UnsupportedOperationException("Not implemented yet.");
	}

	@Override
	public void draw (TextureRegion region, float width, float height, com.badlogic.gdx.math.Affine2 transform) { /* TODO */
		if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");
		Texture tex = region.getTexture();
		if (!(tex instanceof VulkanTexture)) {
			throw new GdxRuntimeException("TextureRegion's texture is not a VulkanTexture instance for draw(Region, w, h, Affine2)");
		}
		VulkanTexture texture = (VulkanTexture)tex;

		if (texture != lastTexture) {
			flush();
			lastTexture = texture;
			invTexWidth = 1.0f / texture.getWidth();
			invTexHeight = 1.0f / texture.getHeight();
		} else if (vertexBufferIdx + 20 > vertices.capacity()) {
			flush();
		}

		final float m00 = transform.m00;
		final float m01 = transform.m01;
		final float m02 = transform.m02;
		final float m10 = transform.m10;
		final float m11 = transform.m11;
		final float m12 = transform.m12;
		float x1 = m02;
		float y1 = m12;
		float x2 = m00 * width + m02;
		float y2 = m10 * width + m12;
		float x3 = m00 * width + m01 * height + m02;
		float y3 = m10 * width + m11 * height + m12;
		float x4 = m01 * height + m02;
		float y4 = m11 * height + m12;

		float u = region.getU();
		float v = region.getV(); // Use v/v2 based on testing
		float u2 = region.getU2();
		float v2 = region.getV2();
		float color = this.colorPacked;

		vertices.put(x1);
		vertices.put(y1);
		vertices.put(color);
		vertices.put(u);
		vertices.put(v2);
		vertices.put(x2);
		vertices.put(y2);
		vertices.put(color);
		vertices.put(u2);
		vertices.put(v2);
		vertices.put(x3);
		vertices.put(y3);
		vertices.put(color);
		vertices.put(u2);
		vertices.put(v);
		vertices.put(x4);
		vertices.put(y4);
		vertices.put(color);
		vertices.put(u);
		vertices.put(v);

		this.vertexBufferIdx += 20;
		this.spriteCount++;
	}

	@Override
	public void flush () {
		// Use local variable for sprite count check
		int currentSpriteCount = this.spriteCount;
		if (currentSpriteCount == 0 && vertexBufferIdx == 0) { // Check both just in case
			// Gdx.app.log(TAG, "Flush called but no vertex data or sprites pending."); // Reduce log spam
			return; // Nothing to flush
		}
		// If idx > 0 but spriteCount is 0, log warning but proceed based on idx
		if (vertexBufferIdx > 0 && currentSpriteCount == 0) {
			Gdx.app.log(TAG, "Warning: Flushing with vertexBufferIdx > 0 but spriteCount == 0. Recalculating sprites.");
			// Need valid vertexFloats and VERTICES_PER_SPRITE here
			if (vertexFloats <= 0 || VERTICES_PER_SPRITE <= 0)
				throw new IllegalStateException("Vertex structure constants invalid in flush.");
			int verticesToDraw = vertexBufferIdx / vertexFloats;
			if (vertexBufferIdx % vertexFloats != 0)
				Gdx.app.error(TAG, "Flush Warning: vertexBufferIdx not multiple of vertexFloats!");
			currentSpriteCount = verticesToDraw / VERTICES_PER_SPRITE; // Recalculate based on index
			if (verticesToDraw % VERTICES_PER_SPRITE != 0)
				Gdx.app.error(TAG, "Flush Warning: vertex count not multiple of vertices per sprite!");
			if (currentSpriteCount == 0 && vertexBufferIdx > 0) {
				Gdx.app.log(TAG, "Flush: Still no full sprites to draw despite vertex data. Resetting buffer.");
				resetCountersAndLog(); // Reset without drawing
				return;
			}
			Gdx.app.log(TAG, "Recalculated sprite count: " + currentSpriteCount);
		}

		// --- Precondition Checks for Drawing ---
		if (currentCommandBuffer == null)
			throw new GdxRuntimeException("Cannot flush() batch, command buffer is null (missing begin?).");
		if (lastTexture == null)
			throw new GdxRuntimeException("Cannot flush() batch, lastTexture is null (must draw at least one sprite first).");
		if (this.vertexBuffer.bufferHandle == VK_NULL_HANDLE || this.indexBuffer.bufferHandle == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("Cannot flush, vertex or index buffer handle is null.");
		}
		if (this.batchPipelineLayout == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("Cannot flush, pipeline layout handle is null.");
		}
		if (this.batchDescriptorSets.isEmpty() || this.batchDescriptorSets.get(0) == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("Cannot flush, descriptor set handle is invalid.");
		}

		// --- Get Render Pass ---
		VulkanGraphics gfx = (VulkanGraphics)Gdx.graphics;
		long currentRenderPassHandle = gfx.getCurrentRenderPassHandle();
		if (currentRenderPassHandle == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("Cannot flush() batch, current render pass handle is null.");
		}

		// --- Calculate Draw Counts ---
		int verticesToDraw = vertexBufferIdx / vertexFloats; // Total vertices accumulated
		int calculatedSprites = verticesToDraw / VERTICES_PER_SPRITE; // Total sprites based on vertices
		int indexCountToDraw = calculatedSprites * INDICES_PER_SPRITE; // Total indices needed
		// Gdx.app.log(TAG, "Flush: vertexBufferIdx=" + vertexBufferIdx + ", vertexFloats=" + vertexFloats + " -> verticesToDraw=" +
		// verticesToDraw); // <-- ADD
		// Gdx.app.log(TAG, "Flush: VERTICES_PER_SPRITE=" + VERTICES_PER_SPRITE + " -> calculatedSprites=" + calculatedSprites); //
		// <-- ADD
		// Gdx.app.log(TAG, "Flush: INDICES_PER_SPRITE=" + INDICES_PER_SPRITE + " -> indexCountToDraw=" + indexCountToDraw); // <--
		// ADD
		// Gdx.app.log(TAG, "Flush: Tracked spriteCount=" + currentSpriteCount); // <-- ADD (or keep existing log)

		// Gdx.app.log(TAG, "Flushing based on vertexBufferIdx: " + calculatedSprites + " sprites (" + verticesToDraw + " vertices,
		// " + indexCountToDraw + " indices). " + "Tracked spriteCount: " + currentSpriteCount); // Log both for comparison

		// If no indices to draw (e.g., less than 4 vertices were added), skip draw but still reset buffer.
		if (indexCountToDraw <= 0) {
			Gdx.app.log(TAG, "Calculated indexCountToDraw is zero or negative, skipping draw but resetting buffer.");
			resetCountersAndLog();
			pipelineAndSetBoundThisBatch = false; // Reset bound state as flush technically completed
			return;
		}

		// --- Update/Bind Descriptors and Pipeline (if needed) ---
		int currentFrameIndex = 0; // Simplified for forced MAX_FRAMES_IN_FLIGHT = 1
		long currentFrameSet = batchDescriptorSets.get(currentFrameIndex);

		if (textureDescriptorNeedsUpdate) {
			if (this.lastTexture == null)
				throw new GdxRuntimeException("Consistency error: lastTexture is null in flush during texture update check.");
			// Gdx.app.log(TAG, "Flush: Updating texture descriptor for set " + currentFrameSet);
			updateTextureDescriptor(currentFrameSet, this.lastTexture);
			textureDescriptorNeedsUpdate = false;
			pipelineAndSetBoundThisBatch = false; // Force re-bind after descriptor update
		}

		if (!pipelineAndSetBoundThisBatch) {
			// Gdx.app.log(TAG, "Flush: Binding pipeline and descriptor set.");

			// Bind Pipeline
			long pipelineToUse = pipelineManager.getOrCreateSpriteBatchPipeline(this.batchDescriptorLayout, currentRenderPassHandle);
			if (pipelineToUse == VK_NULL_HANDLE) {
				throw new GdxRuntimeException("Could not get/create sprite batch pipeline in flush.");
			}
			Gdx.app.log("PipelineLayoutCheck", "Pipeline Handle: " + pipelineToUse);
			Gdx.app.log("PipelineLayoutCheck",
				"Layout used for Pipeline Creation (passed to manager): " + this.batchDescriptorLayout); // Assuming this was used to
																																		// get pipeline
			Gdx.app.log("PipelineLayoutCheck", "Layout used for vkCmdBindDescriptorSets: " + this.batchPipelineLayout);
			vkCmdBindPipeline(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineToUse);

			// Bind Descriptor Set
			try (MemoryStack stack = MemoryStack.stackPush()) {
				LongBuffer pSet = stack.longs(currentFrameSet);
				vkCmdBindDescriptorSets(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, this.batchPipelineLayout, 0, pSet,
					null);
			}
			pipelineAndSetBoundThisBatch = true;
		}

		// --- Bind Buffers ---
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer pBuffers = stack.longs(vertexBuffer.bufferHandle);
			LongBuffer pOffsets = stack.longs(0); // Always use the start of the vertex buffer
			vkCmdBindVertexBuffers(currentCommandBuffer, 0, pBuffers, pOffsets);
		}
		// Index buffer is static, bind offset 0
		vkCmdBindIndexBuffer(currentCommandBuffer, indexBuffer.bufferHandle, 0, VK_INDEX_TYPE_UINT16);

		// --- Flush Host Cache (VMA) ---
		long vertexBytesToFlush = (long)vertexBufferIdx * Float.BYTES;
		if (vertexBytesToFlush > 0) {
			// Ensure the offset and size are valid for the allocation
			if (vertexBuffer.allocationHandle != VK_NULL_HANDLE) {
				// Flush only the range we wrote into since the last flush (which starts from 0 up to vertexBufferIdx)
				vmaFlushAllocation(vmaAllocator, vertexBuffer.allocationHandle, 0, vertexBytesToFlush);
			} else {
				Gdx.app.error(TAG, "Cannot flush vertex buffer memory, VMA allocation handle is null.");
			}
		} else {
			Gdx.app.log(TAG, "Skipping VMA flush as vertexBytesToFlush is zero.");
		}

		// --- Issue Draw Call ---
		vkCmdDrawIndexed(currentCommandBuffer, indexCountToDraw, 1, 0, 0, 0); // instanceCount=1, firstIndex=0, vertexOffset=0,
																										// firstInstance=0

		// --- Reset Counters ---
		resetCountersAndLog();
	}

	// Helper to avoid repeating reset code/logs
	private void resetCountersAndLog () {
		// Gdx.app.log(TAG, "Flush: Resetting counters (pre-reset). Current idx = " + vertexBufferIdx + ", Current spriteCount = " +
		// this.spriteCount);
		vertexBufferIdx = 0;
		spriteCount = 0;
		// Gdx.app.log(TAG, "Flush: Counters reset attempted (post-reset). Current idx = " + vertexBufferIdx + ", Current
		// spriteCount = " + this.spriteCount);
	}

	/** Switches the texture and flushes the batch if necessary. Updates inverse texture size and flags descriptor for update.
	 *
	 * @param texture The new VulkanTexture to switch to. */
	protected void switchTexture (VulkanTexture texture) {
		// Ensure flush is called BEFORE changing lastTexture if spriteCount > 0
		if (this.spriteCount > 0) {
			Gdx.app.log(TAG, "Switching texture, flushing previous batch (" + this.spriteCount + " sprites).");
			flush(); // Flush existing data with the old texture
		} else {
			Gdx.app.log(TAG, "Switching texture (no previous sprites to flush).");
		}

		lastTexture = texture;
		// Check for division by zero if texture dimensions are invalid
		if (texture.getWidth() == 0 || texture.getHeight() == 0) {
			Gdx.app.error(TAG, "Texture dimensions are zero in switchTexture!");
			invTexWidth = 0;
			invTexHeight = 0;
		} else {
			invTexWidth = 1.0f / texture.getWidth();
			invTexHeight = 1.0f / texture.getHeight();
		}
		this.textureDescriptorNeedsUpdate = true; // Mark descriptor for update in next flush
		this.pipelineAndSetBoundThisBatch = false; // Force rebind of pipeline/descriptors after texture switch
	}

	@Override
	public void disableBlending () {
		Gdx.app.log(TAG, "WARN: disableBlending() not fully implemented."); /* TODO */
	}

	@Override
	public void enableBlending () {
		Gdx.app.log(TAG, "WARN: enableBlending() not fully implemented."); /* TODO */
	}

	@Override
	public void setBlendFunction (int srcFunc, int dstFunc) {
		Gdx.app.log(TAG, "WARN: setBlendFunction() not fully implemented."); /* TODO */
	}

	@Override
	public void setBlendFunctionSeparate (int srcFuncColor, int dstFuncColor, int srcFuncAlpha, int dstFuncAlpha) {
		Gdx.app.log(TAG, "WARN: setBlendFunctionSeparate() not fully implemented."); /* TODO */
	}

	@Override
	public int getBlendSrcFunc () {
		return -1; /* TODO */
	}

	@Override
	public int getBlendDstFunc () {
		return -1; /* TODO */
	}

	@Override
	public int getBlendSrcFuncAlpha () {
		return -1; /* TODO */
	}

	@Override
	public int getBlendDstFuncAlpha () {
		return -1; /* TODO */
	}

	@Override
	public Matrix4 getProjectionMatrix () {
		return projectionMatrix;
	}

	@Override
	public Matrix4 getTransformMatrix () {
		return transformMatrix;
	}

	@Override
	public void setProjectionMatrix (Matrix4 projection) {
		if (!this.projectionMatrix.equals(projection)) {
			this.projectionMatrix.set(projection);
			// If drawing, update UBO immediately. Otherwise, begin() will handle it.
			if (drawing) {
				updateProjectionMatrixUBO();
			}
			// You could also add a 'dirty' flag here if you implement change tracking
			// in updateProjectionMatrixUBO() to avoid redundant updates in begin().
		}
		/*
		 * if (projection == null) throw new IllegalArgumentException("projection matrix cannot be null.");
		 * 
		 * this.projectionMatrix.set(projection); // Update UBO if (projMatrixUbo != null) { PointerBuffer pData =
		 * MemoryUtil.memAllocPointer(1); try { vkCheck(vmaMapMemory(vmaAllocator, projMatrixUbo.allocationHandle, pData),
		 * "VMA map failed for projection update"); FloatBuffer uboData = MemoryUtil.memByteBuffer(pData.get(0), (int)
		 * projMatrixUbo.size).asFloatBuffer(); uboData.put(projection.val); // Put matrix values // uboData.flip(); // flip() not
		 * needed for FloatBuffer.put(float[])
		 * 
		 * // Flush the entire UBO allocation range since we rewrote it vmaFlushAllocation(vmaAllocator,
		 * projMatrixUbo.allocationHandle, 0, VK_WHOLE_SIZE); // No vkCheck needed vmaUnmapMemory(vmaAllocator,
		 * projMatrixUbo.allocationHandle);
		 * 
		 * } catch (Exception e) { throw new GdxRuntimeException("Failed to update projection matrix UBO", e); } finally {
		 * MemoryUtil.memFree(pData); } }
		 */
	}

	@Override
	public void setTransformMatrix (Matrix4 transform) {
		// Standard SpriteBatch ignores this, bakes transform into vertices.
		// We should do the same in draw() methods.
		// this.transformMatrix.set(transform); // Generally not used by SpriteBatch
	}

	@Override
	public void setShader (ShaderProgram shader) {
		Gdx.app.log(TAG, "WARN: setShader() not implemented."); /* TODO: Select pipeline based on shader */
	}

	@Override
	public ShaderProgram getShader () {
		return null; /* TODO */
	}

	@Override
	public boolean isBlendingEnabled () {
		return true; /* TODO: Track actual state */
	}

	@Override
	public boolean isDrawing () {
		return drawing;
	}

}
