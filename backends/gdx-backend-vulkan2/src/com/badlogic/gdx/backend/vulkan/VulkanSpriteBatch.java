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

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanSpriteBatch implements Batch, Disposable {

    private final String logTag = "VulkanSpriteBatch";

    // --- Dependencies (obtained via Gdx.graphics) ---
    private final VulkanDevice device;
    private final VkDevice rawDevice;
    private final long vmaAllocator;
    private final VulkanPipelineManager pipelineManager;
    private final VulkanDescriptorManager descriptorManager;

    // --- Batch Resources ---
    private VulkanBuffer vertexBuffer; // Host-visible buffer for vertex data
    private VulkanBuffer indexBuffer;  // Device-local buffer for quad indices
    private VulkanBuffer projMatrixUbo; // Host-visible buffer for projection matrix uniform

    private ByteBuffer mappedVertexByteBuffer; // Direct ByteBuffer mapped to vertexBuffer's memory
    private FloatBuffer vertices;              // Float view of mappedVertexByteBuffer
    private ShortBuffer indicesCpu;            // CPU buffer to setup index buffer once

    private final int vertexSize; // Size of one vertex in bytes
    private final int vertexFloats; // Size of one vertex in floats
    private final int maxSprites;   // Max sprites per flush

    private long batchPipeline = VK_NULL_HANDLE;
    private long batchPipelineLayout = VK_NULL_HANDLE;
    private long batchDescriptorLayout = VK_NULL_HANDLE;
    private long batchDescriptorSet = VK_NULL_HANDLE; // The single set we'll update (initially)

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
            new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, ShaderProgram.COLOR_ATTRIBUTE), // Stored as 1 float, needs GL_UNSIGNED_BYTE type? Check shader. Let's use GL_FLOAT for the packed float initially.
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE + "0")
    );
    public static final int POSITION_COMPONENTS = 2;
    public static final int COLOR_COMPONENTS = 1; // Packed into one float
    public static final int TEXCOORD_COMPONENTS = 2;
    public static final int COMPONENTS_PER_VERTEX = POSITION_COMPONENTS + COLOR_COMPONENTS + TEXCOORD_COMPONENTS; // 2 + 1 + 2 = 5
    public static final int VERTICES_PER_SPRITE = 6; // Using indexed drawing for quads
    public static final int INDICES_PER_SPRITE = 6;


    public VulkanSpriteBatch() {
        this(1000); // Default batch size
    }

    public VulkanSpriteBatch(int size) {
        this.maxSprites = size;

        // --- 1. Get Dependencies from Gdx.graphics ---
        if (!(Gdx.graphics instanceof VulkanGraphics)) {
            throw new GdxRuntimeException("VulkanSpriteBatch requires the VulkanGraphics backend to be active!");
        }
        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;

        this.device = gfx.getVulkanDevice();
        this.vmaAllocator = gfx.getVmaAllocator();
        this.pipelineManager = gfx.getPipelineManager();
        this.descriptorManager = gfx.getDescriptorManager();
        this.rawDevice = device.getRawDevice(); // Convenience

        if (device == null || vmaAllocator == VK_NULL_HANDLE || pipelineManager == null || descriptorManager == null) {
            throw new GdxRuntimeException("Failed to retrieve necessary Vulkan managers from VulkanGraphics!");
        }

        // --- 2. Initialize Internal Batch Resources ---
        this.vertexSize = COMPONENTS_PER_VERTEX * Float.BYTES; // 5 floats * 4 bytes/float = 20 bytes
        this.vertexFloats = COMPONENTS_PER_VERTEX;
        int maxVertices = size * VERTICES_PER_SPRITE; // Max vertices needed
        long vertexBufferSizeBytes = (long) maxVertices * vertexSize;

        // Create Vertex Buffer (Host Visible & Persistently Mapped)
        this.vertexBuffer = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, vertexBufferSizeBytes,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, // Only needs to be vertex buffer on GPU
                VMA_MEMORY_USAGE_AUTO,             // Let VMA choose (likely CPU or BAR for host visible)
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT // Host access + mapped
        );
        // Get the mapped ByteBuffer from VMA
        PointerBuffer pData = MemoryUtil.memAllocPointer(1);
        try {
            vkCheck(vmaMapMemory(vmaAllocator, vertexBuffer.allocationHandle, pData), "VMA Failed to map vertex buffer for batch");
            this.mappedVertexByteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int) vertexBufferSizeBytes);
            this.vertices = this.mappedVertexByteBuffer.asFloatBuffer();
            Gdx.app.log(logTag, "Vertex buffer created and persistently mapped.");
        } finally {
            MemoryUtil.memFree(pData); // Free the pointer buffer, NOT the mapped memory yet
            // We keep it mapped until dispose()
        }

        // Create and Upload Index Buffer (Device Local is best)
        this.indicesCpu = BufferUtils.newShortBuffer(size * INDICES_PER_SPRITE);
        ((Buffer) this.indicesCpu).clear();
        for (int i = 0, v = 0; i < size * INDICES_PER_SPRITE; i += INDICES_PER_SPRITE, v += 4) { // 4 vertices per quad
            indicesCpu.put((short) v);
            indicesCpu.put((short) (v + 1));
            indicesCpu.put((short) (v + 2));
            indicesCpu.put((short) (v + 2));
            indicesCpu.put((short) (v + 3));
            indicesCpu.put((short) v);
        }
        ((Buffer) this.indicesCpu).flip();
        long indexBufferSize = (long) indicesCpu.limit() * Short.BYTES;

        VulkanBuffer stagingIndexBuffer = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, indexBufferSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VMA_MEMORY_USAGE_AUTO, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
        );
        try {
            pData = MemoryUtil.memAllocPointer(1);
            try {
                vkCheck(vmaMapMemory(vmaAllocator, stagingIndexBuffer.allocationHandle, pData), "VMA map failed for index staging");
                ByteBuffer stagingIndexBytes = MemoryUtil.memByteBuffer(pData.get(0), (int) indexBufferSize);
                stagingIndexBytes.asShortBuffer().put(indicesCpu);
                vmaUnmapMemory(vmaAllocator, stagingIndexBuffer.allocationHandle);
            } finally {
                MemoryUtil.memFree(pData);
            }

            this.indexBuffer = VulkanResourceUtil.createManagedBuffer(
                    vmaAllocator, indexBufferSize,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                    VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0
            );

            final long srcHandle = stagingIndexBuffer.bufferHandle;
            final long dstHandle = this.indexBuffer.bufferHandle;
            device.executeSingleTimeCommands(cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).size(indexBufferSize);
                    vkCmdCopyBuffer(cmd, srcHandle, dstHandle, region);
                }
            });
            Gdx.app.log(logTag, "Index buffer created and uploaded.");
        } finally {
            if (stagingIndexBuffer != null) stagingIndexBuffer.dispose();
            // Keep cpuBuffer? Might not be needed after initial upload. Set to null?
            // this.indicesCpu = null; // Optional: Free CPU memory
        }


        // Create Uniform Buffer for Projection Matrix
        long uboSize = 16 * Float.BYTES; // 4x4 matrix
        this.projMatrixUbo = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, uboSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
        );
        // Get mapped buffer for UBO
        pData = MemoryUtil.memAllocPointer(1);
        try {
            vkCheck(vmaMapMemory(vmaAllocator, projMatrixUbo.allocationHandle, pData), "VMA Failed to map projection UBO");
            // Store the FloatBuffer view for easy updates
            // this.projMatrixUboMapped = MemoryUtil.memByteBuffer(pData.get(0), (int) uboSize).asFloatBuffer();
            // Let's not store it, map/unmap in setProjectionMatrix instead for now (simpler state)
        } finally {
            // Unmap immediately if not storing mapped buffer
            vmaUnmapMemory(vmaAllocator, projMatrixUbo.allocationHandle);
            MemoryUtil.memFree(pData);
        }
        Gdx.app.log(logTag, "Projection matrix UBO created.");


        // Create Descriptor Set Layout
        // TODO: Get this from DescriptorManager, maybe based on a predefined spec?
        // Layout needs binding 0 = UBO (vertex stage), binding 1 = Sampler (fragment stage)
        createBatchDescriptorSetLayout(); // Call helper method

        // Allocate the initial (single) Descriptor Set
        // TODO: Get pool from DescriptorManager? For now assume default pool works.
        this.batchDescriptorSet = descriptorManager.allocateSet(this.batchDescriptorLayout);
        Gdx.app.log(logTag, "Allocated descriptor set: " + this.batchDescriptorSet);

        // Update descriptor set for the UBO initially
        updateUboDescriptor(this.batchDescriptorSet, this.projMatrixUbo);
        Gdx.app.log(logTag, "Updated descriptor set for UBO.");


        // Create Pipeline
        // TODO: Get pipeline from PipelineManager based on required state
        // Needs: batch shader files, batch vertex attributes, blend enabled state,
        //        batch descriptor set layout, swapchain render pass.
        createBatchPipeline(); // Call helper method

        Gdx.app.log(logTag, "Initialization complete.");
    }


    // --- Helper Methods for Internal Resource Creation ---

    private void createBatchDescriptorSetLayout() {
        Gdx.app.log(logTag, "Creating batch descriptor set layout...");
        // If DescriptorManager has caching:
        // Define bindings... create bindings buffer...
        // this.batchDescriptorLayout = descriptorManager.getOrCreateLayout(bindings);
        // For now, create directly:
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            // Binding 0: Uniform Buffer (Projection Matrix) - Vertex Stage
            bindings.get(0).binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                    .pImmutableSamplers(null);
            // Binding 1: Combined Image Sampler (Texture) - Fragment Stage
            bindings.get(1).binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .pImmutableSamplers(null);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            vkCheck(vkCreateDescriptorSetLayout(rawDevice, layoutInfo, null, pLayout), "Failed to create batch descriptor set layout");
            this.batchDescriptorLayout = pLayout.get(0);
            Gdx.app.log(logTag, "Batch descriptor set layout created: " + this.batchDescriptorLayout);
        }
    }

    private void updateUboDescriptor(long descriptorSet, VulkanBuffer ubo) {
        Gdx.app.log(logTag, "Updating UBO descriptor for set " + descriptorSet);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(ubo.bufferHandle)
                    .offset(0)
                    .range(ubo.size); // Use full buffer range

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
            descriptorWrite.get(0).sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(0) // UBO at binding 0
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(bufferInfo); // Link buffer info

            vkUpdateDescriptorSets(rawDevice, descriptorWrite, null);
        }
    }

    private void updateTextureDescriptor(long descriptorSet, VulkanTexture texture) {
        if (texture == null) return; // Nothing to update
        Gdx.app.log(logTag, "Updating Texture descriptor for set " + descriptorSet + " with TexView: " + texture.getImageViewHandle());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(texture.getImageViewHandle())
                    .sampler(texture.getSamplerHandle()); // Use texture's sampler

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack);
            descriptorWrite.get(0).sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(1) // Texture sampler at binding 1
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo); // Link image info

            vkUpdateDescriptorSets(rawDevice, descriptorWrite, null);
        }
    }

    private void createBatchPipeline() {
        Gdx.app.log(logTag, "Requesting sprite batch pipeline...");
        // Ideally, request from PipelineManager based on a config
        // For now, assume PipelineManager can create/get a pipeline suitable for sprite batching
        // This requires defining the pipeline state (vertex input from BATCH_ATTRIBUTES, blending, etc.)
        // AND using the batchDescriptorLayout created above.
        // How to get the render pass? It changes. Pipeline must be compatible.
        // This suggests pipeline creation/lookup might need to happen inside begin() or flush()
        // OR PipelineManager needs a way to get a pipeline *compatible* with a render pass.

        // --- TEMPORARY HACK: Create pipeline directly here (violates manager pattern) ---
        // --- This should be moved to PipelineManager properly ---
        Gdx.app.log(logTag, "TODO: Pipeline creation should be moved to VulkanPipelineManager!");
        FileHandle vertShaderFile = Gdx.files.internal("data/vulkan/shaders/sprite.vert.spv"); // Adjust path if needed
        FileHandle fragShaderFile = Gdx.files.internal("data/vulkan/shaders/sprite.vert.spv"); // Adjust path if needed
        if (!vertShaderFile.exists() || !fragShaderFile.exists()) {
            throw new GdxRuntimeException("Default SpriteBatch SPIR-V shaders not found!");
        }
        // Need current render pass - cannot create pipeline fully here!
        // Let's assume PipelineManager can give us one based on layout + shaders + renderpass later.
        // We store the layout handle; pipeline handle will be retrieved/bound in flush().
        Gdx.app.log(logTag, "Deferring actual pipeline handle retrieval until rendering.");
        // this.batchPipeline = pipelineManager.getOrCreateSpriteBatchPipeline(vertShaderFile, fragShaderFile, batchDescriptorLayout, currentRenderPassHandle);

        // Need pipeline layout handle for binding descriptor set
        this.batchPipelineLayout = pipelineManager.getOrCreatePipelineLayout(this.batchDescriptorLayout); // Assume PM provides this
        if (this.batchPipelineLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to get/create pipeline layout for batch.");
        }
        Gdx.app.log(logTag, "Using pipeline layout: " + this.batchPipelineLayout);
    }


    // --- Batch Implementation Methods (Stubs for now) ---

    @Override
    public void begin() {
        if (drawing) throw new IllegalStateException("Batch.end must be called before begin.");
        // How to get command buffer? Assume it's passed externally or set somehow.
        // VulkanGraphics.beginFrame() returns it -> how does batch get it?
        // Simplest for now: Add begin(VkCommandBuffer)
        throw new GdxRuntimeException("begin() requires VkCommandBuffer parameter. Use begin(VkCommandBuffer).");
    }

    public void begin(VkCommandBuffer commandBuffer) {
        if (drawing) throw new IllegalStateException("Batch.end must be called before begin.");
        if (commandBuffer == null) throw new IllegalArgumentException("CommandBuffer cannot be null.");

        this.currentCommandBuffer = commandBuffer;

        // TODO: Get the correct pipeline handle from PipelineManager based on current blend state?
        //       Needs current swapchain render pass handle! Pass it in?
        //       For now, assume pipeline is fetched/bound in flush()

        // Bind the descriptor set (with UBO already updated, texture will be updated in flush)
        // vkCmdBindDescriptorSets(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, batchPipelineLayout, 0, batchDescriptorSet...)

        //idx = 0;
        spriteCount = 0;
        lastTexture = null;
        drawing = true;
    }

    @Override
    public void end() {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before end.");
        if (spriteCount > 0) {
            flush(); // Flush remaining sprites
        }
        drawing = false;
        currentCommandBuffer = null; // Clear command buffer reference
    }

    @Override
    public void setColor(Color tint) {
        setColor(tint.r, tint.g, tint.b, tint.a);
    }

    @Override
    public void setColor(float r, float g, float b, float a) {
        this.colorPacked = Color.toFloatBits(r, g, b, a);
    }

    @Override
    public Color getColor() {
        return null;
    }

    @Override
    public void setPackedColor(float packedColor) {

    }

    public void setColor(float color) {
        this.colorPacked = color;
    }

    @Override
    public float getPackedColor() {
        return colorPacked;
    }

    // --- Draw Overloads ---
    // Main internal draw logic:
    private void drawInternal(VulkanTexture texture, float[] spriteVertices, int numFloats) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");
        if (texture != lastTexture) { // Texture switch check
            flush(); // Flush previous batch
            lastTexture = texture;
            invTexWidth = 1.0f / texture.getWidth(); // Or use TextureRegion size?
            invTexHeight = 1.0f / texture.getHeight();
        } else if (vertexBufferIdx + numFloats > vertices.capacity()) { // Buffer full check
            flush();
        }

        // Copy vertex data into mapped buffer
        vertices.position(vertexBufferIdx); // Set position
        vertices.put(spriteVertices, 0, numFloats); // Put data
        vertexBufferIdx += numFloats;
        spriteCount++;
    }

    // Example public draw overload
    @Override
    public void draw(Texture texture, float x, float y, float originX, float originY, float width, float height, float scaleX,
                     float scaleY, float rotation, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {

        if (!(texture instanceof VulkanTexture)) {
            throw new GdxRuntimeException("Texture is not a VulkanTexture instance!");
        }
        VulkanTexture vkTexture = (VulkanTexture) texture;

        // --- Calculate vertices (Standard SpriteBatch math) ---
        // Based on SpriteBatch source: https://github.com/libgdx/libgdx/blob/master/gdx/src/com/badlogic/gdx/graphics/g2d/SpriteBatch.java
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
    public void draw(Texture texture, float x, float y, float width, float height, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {

    }

    @Override
    public void draw(Texture texture, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {

    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, float u, float v, float u2, float v2) {

    }

    @Override
    public void draw(Texture texture, float x, float y) {

    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height) {

    }

    /**
     * Helper method to retrieve the current swapchain RenderPass handle via Gdx.graphics.
     * Assumes rendering is happening within the main swapchain render pass context.
     *
     * @return The VkRenderPass handle.
     * @throws GdxRuntimeException if Gdx.graphics is not VulkanGraphics or swapchain/renderpass is invalid.
     */
    private long getCurrentRenderPassHandle() {
        if (Gdx.graphics instanceof VulkanGraphics) {
            VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
            // Ensure VulkanGraphics has a getter for the swapchain
            VulkanSwapchain swapchain = gfx.getSwapchain();
            if (swapchain != null) {
                // Ensure VulkanSwapchain has a getter for the render pass
                long rpHandle = swapchain.getRenderPass();
                if (rpHandle != VK_NULL_HANDLE) {
                    return rpHandle;
                } else {
                    throw new GdxRuntimeException("VulkanSpriteBatch: Failed to get RenderPass handle from VulkanSwapchain (handle is VK_NULL_HANDLE)");
                }
            } else {
                throw new GdxRuntimeException("VulkanSpriteBatch: Failed to get VulkanSwapchain from VulkanGraphics");
            }
        } else {
            throw new GdxRuntimeException("VulkanSpriteBatch: Gdx.graphics is not an instance of VulkanGraphics");
        }
    }

    @Override
    public void dispose() {
        Gdx.app.log(logTag, "Disposing...");
        // Unmap vertex buffer if persistently mapped
        if (vertexBuffer != null && mappedVertexByteBuffer != null) {
            vmaUnmapMemory(vmaAllocator, vertexBuffer.allocationHandle);
            mappedVertexByteBuffer = null; // Allow GC
            vertices = null;
        }
        // Dispose VMA buffers
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

        // Destroy pipeline layout (if created directly - better if managed by PipelineManager)
        if (batchDescriptorLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(rawDevice, batchDescriptorLayout, null);
            batchDescriptorLayout = VK_NULL_HANDLE;
        }
        // Descriptor set is freed with pool (managed by DescriptorManager)
        batchDescriptorSet = VK_NULL_HANDLE;

        // Pipeline is managed by PipelineManager, no need to destroy here

        Gdx.app.log(logTag, "Disposed.");
    }

    // Stubs for missing methods
    @Override
    public void draw(Texture texture, float[] spriteVertices, int offset, int count) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public void draw(TextureRegion region, float x, float y) {
        draw(region.getTexture(), x, y, region.getRegionWidth(), region.getRegionHeight());
    } // Simple overload example

    @Override
    public void draw(TextureRegion region, float x, float y, float width, float height) {
        draw(region, x, y, 0, 0, width, height, 1, 1, 0);
    } // Simple overload example

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation) {
        draw(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation, region.getRegionX(), region.getRegionY(), region.getRegionWidth(), region.getRegionHeight(), false, false);
    } // Full overload example

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation, boolean clockwise) { /* TODO */
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public void draw(TextureRegion region, float width, float height, com.badlogic.gdx.math.Affine2 transform) { /* TODO */
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public void flush() {
        if (spriteCount == 0) return;
        if (currentCommandBuffer == null) throw new GdxRuntimeException("Cannot flush() batch, not inside begin/end.");
        if (lastTexture == null) throw new GdxRuntimeException("Cannot flush() batch, lastTexture is null (should have been set by draw).");

        // *** Get the current RenderPass handle via Gdx.graphics ***
        long currentRenderPassHandle = getCurrentRenderPassHandle(); // Call helper

        Gdx.app.log(logTag, "Flushing " + spriteCount + " sprites.");

        // 1. Update Texture Descriptor in Set
        updateTextureDescriptor(this.batchDescriptorSet, this.lastTexture);

        // 2. Bind Pipeline (Get/Create the specific SpriteBatch pipeline)
        // Pass the BATCH DESCRIPTOR SET LAYOUT and the current RENDER PASS handle
        long pipelineToUse = pipelineManager.getOrCreateSpriteBatchPipeline(
                this.batchDescriptorLayout,  // The layout defining bindings (UBO, Sampler)
                currentRenderPassHandle      // The render pass the pipeline must be compatible with
        );
        if (pipelineToUse == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Could not get/create sprite batch pipeline.");
        }
        vkCmdBindPipeline(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineToUse);

        // 3. Bind Descriptor Set
        // Use the PIPELINE LAYOUT handle associated with the pipeline/descriptor set layout
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSet = stack.longs(this.batchDescriptorSet);
            vkCmdBindDescriptorSets(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    this.batchPipelineLayout, // The PIPELINE layout handle
                    0, pSet, null);
        }

        // 4. Bind Vertex/Index Buffers for this batch
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pBuffers = stack.longs(vertexBuffer.bufferHandle);
            LongBuffer pOffsets = stack.longs(0);
            vkCmdBindVertexBuffers(currentCommandBuffer, 0, pBuffers, pOffsets);
        }
        vkCmdBindIndexBuffer(currentCommandBuffer, indexBuffer.bufferHandle, 0, VK_INDEX_TYPE_UINT16);

        // 5. Issue Draw Call
        int indexCountToDraw = spriteCount * INDICES_PER_SPRITE;
        vkCmdDrawIndexed(currentCommandBuffer, indexCountToDraw, 1, 0, 0, 0);

        // 6. Reset counters
        vertexBufferIdx = 0;
        spriteCount = 0;

        // Note: We keep the vertex buffer mapped. VMA handles coherency if needed, or we flush.
    }

    @Override
    public void disableBlending() {
        Gdx.app.log(logTag, "WARN: disableBlending() not fully implemented."); /* TODO */
    }

    @Override
    public void enableBlending() {
        Gdx.app.log(logTag, "WARN: enableBlending() not fully implemented."); /* TODO */
    }

    @Override
    public void setBlendFunction(int srcFunc, int dstFunc) {
        Gdx.app.log(logTag, "WARN: setBlendFunction() not fully implemented."); /* TODO */
    }

    @Override
    public void setBlendFunctionSeparate(int srcFuncColor, int dstFuncColor, int srcFuncAlpha, int dstFuncAlpha) {
        Gdx.app.log(logTag, "WARN: setBlendFunctionSeparate() not fully implemented."); /* TODO */
    }

    @Override
    public int getBlendSrcFunc() {
        return -1; /* TODO */
    }

    @Override
    public int getBlendDstFunc() {
        return -1; /* TODO */
    }

    @Override
    public int getBlendSrcFuncAlpha() {
        return -1; /* TODO */
    }

    @Override
    public int getBlendDstFuncAlpha() {
        return -1; /* TODO */
    }

    @Override
    public Matrix4 getProjectionMatrix() {
        return projectionMatrix;
    }

    @Override
    public Matrix4 getTransformMatrix() {
        return transformMatrix;
    }

    @Override
    public void setProjectionMatrix(Matrix4 projection) {
        if (projection == null) throw new IllegalArgumentException("projection matrix cannot be null.");
        this.projectionMatrix.set(projection);
        // Update UBO
        if (projMatrixUbo != null) {
            PointerBuffer pData = MemoryUtil.memAllocPointer(1);
            try {
                vkCheck(vmaMapMemory(vmaAllocator, projMatrixUbo.allocationHandle, pData), "VMA map failed for projection update");
                FloatBuffer uboData = MemoryUtil.memByteBuffer(pData.get(0), (int) projMatrixUbo.size).asFloatBuffer();
                uboData.put(projection.val); // Put matrix values
                uboData.flip();
                vmaUnmapMemory(vmaAllocator, projMatrixUbo.allocationHandle);
                // Consider flushing if memory is not coherent
            } catch (Exception e) {
                throw new GdxRuntimeException("Failed to update projection matrix UBO", e);
            } finally {
                MemoryUtil.memFree(pData);
            }
        }
        // If drawing, might need to flush here if UBO change affects current batch? Or assume it affects next flush.
    }

    @Override
    public void setTransformMatrix(Matrix4 transform) {
        // Standard SpriteBatch ignores this, bakes transform into vertices.
        // We should do the same in draw() methods.
        // this.transformMatrix.set(transform); // Generally not used by SpriteBatch
    }

    @Override
    public void setShader(ShaderProgram shader) {
        Gdx.app.log(logTag, "WARN: setShader() not implemented."); /* TODO: Select pipeline based on shader */
    }

    @Override
    public ShaderProgram getShader() {
        return null; /* TODO */
    }

    @Override
    public boolean isBlendingEnabled() {
        return true; /* TODO: Track actual state */
    }

    @Override
    public boolean isDrawing() {
        return drawing;
    }

}