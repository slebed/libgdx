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

    private final VulkanDevice device;
    private final VkDevice rawDevice;
    private final long vmaAllocator;
    private final VulkanPipelineManager pipelineManager;
    private final VulkanDescriptorManager descriptorManager;
    private final Color batchColor = new Color();
    private boolean textureDescriptorNeedsUpdate = true;
    private boolean pipelineAndSetBoundThisBatch = false;

    private VulkanBuffer vertexBuffer;
    private VulkanBuffer indexBuffer;
    private VulkanBuffer projMatrixUbo;

    private ByteBuffer mappedVertexByteBuffer;
    private FloatBuffer vertices;
    private final ShortBuffer indicesCpu;

    private final int vertexSize;
    private final int vertexFloats;

    private long batchPipeline = VK_NULL_HANDLE;
    private long batchPipelineLayout = VK_NULL_HANDLE;
    private long batchDescriptorLayout = VK_NULL_HANDLE;
    private final java.util.List<Long> batchDescriptorSets; // Use a List

    private boolean drawing = false; // Is begin() called?
    private final Matrix4 projectionMatrix = new Matrix4();
    private final Matrix4 transformMatrix = new Matrix4(); // Usually identity for SpriteBatch

    private VulkanTexture lastTexture = null;
    private float invTexWidth = 0, invTexHeight = 0;
    private float colorPacked = Color.WHITE_FLOAT_BITS; // Packed float color

    private int spriteCount = 0;
    private int vertexBufferIdx = 0;

    public static final VertexAttributes BATCH_ATTRIBUTES = new VertexAttributes(
            new VertexAttribute(VertexAttributes.Usage.Position, 2, ShaderProgram.POSITION_ATTRIBUTE),
            new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, ShaderProgram.COLOR_ATTRIBUTE), // Stored as 1 float, needs GL_UNSIGNED_BYTE type? Check shader. Let's use GL_FLOAT for the packed float initially.
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE + "0")
    );
    public static final int POSITION_COMPONENTS = 2;
    public static final int COLOR_COMPONENTS = 1; // Packed into one float
    public static final int TEXCOORD_COMPONENTS = 2;
    public static final int COMPONENTS_PER_VERTEX = POSITION_COMPONENTS + COLOR_COMPONENTS + TEXCOORD_COMPONENTS; // 2 + 1 + 2 = 5
    public static final int VERTICES_PER_SPRITE = 4; // Using indexed drawing for quads
    public static final int INDICES_PER_SPRITE = 6;
    public int renderCalls;

    public VulkanSpriteBatch() {
        this(1024); // Default batch size
    }

    public VulkanSpriteBatch(int size) {
        Gdx.app.log(TAG, "Initializing with size: " + size);

        if (!(Gdx.graphics instanceof VulkanGraphics)) {
            throw new GdxRuntimeException("VulkanSpriteBatch requires the VulkanGraphics backend!");
        }
        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;

        this.device = gfx.getVulkanDevice();
        this.vmaAllocator = gfx.getVmaAllocator();
        this.pipelineManager = gfx.getPipelineManager();
        this.descriptorManager = gfx.getDescriptorManager();

        if (device == null || vmaAllocator == VK_NULL_HANDLE || pipelineManager == null || descriptorManager == null) {
            throw new GdxRuntimeException("Failed to retrieve necessary Vulkan managers!");
        }
        this.rawDevice = device.getRawDevice();

        final int FRAMES_IN_FLIGHT_FOR_BATCH = 2; // Use 2 or 3 typically
        final int descriptorSetCount = FRAMES_IN_FLIGHT_FOR_BATCH;

        this.batchDescriptorSets = new ArrayList<>(descriptorSetCount); // Size list for 1 set
        Gdx.app.log(TAG, "Using fixed Descriptor set buffer count: " + descriptorSetCount);

        this.vertexSize = COMPONENTS_PER_VERTEX * Float.BYTES;
        this.vertexFloats = COMPONENTS_PER_VERTEX;
        int maxVertices = size * VERTICES_PER_SPRITE;
        long vertexBufferSizeBytes = (long) maxVertices * vertexSize;
        this.vertexBuffer = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, vertexBufferSizeBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
        );
        PointerBuffer pDataVB = MemoryUtil.memAllocPointer(1);
        try {
            vkCheck(vmaMapMemory(vmaAllocator, vertexBuffer.allocationHandle, pDataVB), "VMA Failed to map vertex buffer for batch");
            this.mappedVertexByteBuffer = MemoryUtil.memByteBuffer(pDataVB.get(0), (int) vertexBufferSizeBytes);
            this.vertices = this.mappedVertexByteBuffer.asFloatBuffer();
            Gdx.app.log(TAG, "Vertex buffer created and persistently mapped.");
        } finally {
            MemoryUtil.memFree(pDataVB);
        }

        this.indicesCpu = BufferUtils.newShortBuffer(size * INDICES_PER_SPRITE);
        ((Buffer) this.indicesCpu).clear();
        for (int i = 0, v = 0; i < size * INDICES_PER_SPRITE; i += INDICES_PER_SPRITE, v += 4) {
            indicesCpu.put((short) v);
            indicesCpu.put((short) (v + 1));
            indicesCpu.put((short) (v + 2));
            indicesCpu.put((short) (v + 2));
            indicesCpu.put((short) (v + 3));
            indicesCpu.put((short) v);
        }
        ((Buffer) this.indicesCpu).flip();
        long indexBufferSize = (long) indicesCpu.limit() * Short.BYTES;
        VulkanBuffer stagingIndexBuffer = null;
        try {
            stagingIndexBuffer = VulkanResourceUtil.createManagedBuffer(
                    vmaAllocator, indexBufferSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VMA_MEMORY_USAGE_AUTO, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
            );
            PointerBuffer pDataIB = MemoryUtil.memAllocPointer(1);
            try {
                vkCheck(vmaMapMemory(vmaAllocator, stagingIndexBuffer.allocationHandle, pDataIB), "VMA map failed for index staging");
                ByteBuffer stagingIndexBytes = MemoryUtil.memByteBuffer(pDataIB.get(0), (int) indexBufferSize);
                stagingIndexBytes.asShortBuffer().put(indicesCpu);
                vmaUnmapMemory(vmaAllocator, stagingIndexBuffer.allocationHandle);
            } finally {
                MemoryUtil.memFree(pDataIB);
            }

            this.indexBuffer = VulkanResourceUtil.createManagedBuffer(
                    vmaAllocator, indexBufferSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                    VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0);

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

        long uboSize = 16 * Float.BYTES;
        this.projMatrixUbo = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, uboSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT // No persistent mapping needed
        );
        if (this.projMatrixUbo == null) {
            throw new GdxRuntimeException("Failed to create Projection matrix UBO");
        }
        Gdx.app.log(TAG, "Projection matrix UBO created.");

        Gdx.app.log(TAG, "Getting SpriteBatch descriptor set layout from manager...");
        this.batchDescriptorLayout = descriptorManager.getOrCreateSpriteBatchLayout();
        if (this.batchDescriptorLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to get SpriteBatch descriptor set layout from manager.");
        }
        Gdx.app.log(TAG, "Obtained batch descriptor set layout: " + this.batchDescriptorLayout);

        Gdx.app.log(TAG, "Allocating " + descriptorSetCount + " descriptor sets using manager...");
        if (this.batchDescriptorLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot allocate descriptor sets, layout handle is null.");
        }

        for (int i = 0; i < descriptorSetCount; i++) {
            // Use the manager's allocation method
            long setHandle = descriptorManager.allocateSet(this.batchDescriptorLayout);
            if (setHandle == VK_NULL_HANDLE) {
                // Manager's allocateSet should throw on failure, but double-check
                throw new GdxRuntimeException("Failed to allocate descriptor set [" + i + "] via manager.");
            }
            this.batchDescriptorSets.add(setHandle); // Add the handle to the list
            Gdx.app.log(TAG, "Allocated batch descriptor set [" + i + "]: " + setHandle);

            if (this.projMatrixUbo == null) {
                throw new IllegalStateException("projMatrixUbo is null before initial descriptor update!");
            }
            Gdx.app.log(TAG, "Performing initial UBO update for set [" + i + "]: " + setHandle);

            VulkanDescriptorManager.updateUniformBuffer(
                    rawDevice,                        // Pass VkDevice
                    setHandle,                        // The set to update
                    0,                                // Binding index for UBO
                    this.projMatrixUbo.bufferHandle,  // Extract handle from VulkanBuffer
                    0,                                // Offset within the buffer (assuming 0)
                    this.projMatrixUbo.size           // Range/Size of the buffer (assuming VulkanBuffer has a 'size' field)
            );
        }
        Gdx.app.log(TAG, "Batch descriptor sets allocated and initial UBO updated.");

        Gdx.app.log(TAG, "Getting pipeline layout from PipelineManager...");

        this.batchPipelineLayout = pipelineManager.getOrCreatePipelineLayout(this.batchDescriptorLayout);
        if (this.batchPipelineLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to get/create pipeline layout for batch from PipelineManager.");
        }
        Gdx.app.log(TAG, "Obtained pipeline layout: " + this.batchPipelineLayout);

        Gdx.app.log(TAG, "Initialization complete.");
    }

    private void createBatchDescriptorSetLayout() {
        Gdx.app.log(TAG, "Creating batch descriptor set layout...");
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
            this.batchDescriptorLayout = pLayout.get(0); // Store the handle
            Gdx.app.log(TAG, "Batch descriptor set layout created: " + this.batchDescriptorLayout);
        }
    }

    private void updateUboDescriptor(long descriptorSet, VulkanBuffer ubo) {
        //Gdx.app.log(TAG, "Updating UBO descriptor for set " + descriptorSet);
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

    private void updateProjectionMatrixUBO() {
        if (projMatrixUbo == null || vmaAllocator == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Cannot update UBO - buffer or allocator is null.");
            return; // Or throw exception
        }

        PointerBuffer pData = MemoryUtil.memAllocPointer(1);
        try {
            // Map the buffer
            vkCheck(vmaMapMemory(vmaAllocator, projMatrixUbo.allocationHandle, pData), "VMA map failed for projection update");
            ByteBuffer uboByteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int) projMatrixUbo.size);
            FloatBuffer uboFloatBuffer = uboByteBuffer.asFloatBuffer();

            //Gdx.app.log(TAG, "Updating UBO with Matrix:\n" + this.projectionMatrix); // Log matrix
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
                } catch (Exception unmapE) { /* Ignore */ }
            }
            MemoryUtil.memFree(pData); // Free the pointer buffer itself
            throw new GdxRuntimeException("Failed to update projection matrix UBO", e);
        } finally {
            // Ensure pData is always freed
            MemoryUtil.memFree(pData);
        }
    }

    private void updateTextureDescriptor(long descriptorSet, VulkanTexture texture) {
        if (texture == null) return; // Nothing to update
        //Gdx.app.log(TAG, "UpdateTexDesc: Set=" + descriptorSet
        //        + ", ImgView=" + texture.getImageViewHandle()
        //        + ", Sampler=" + texture.getSamplerHandle());

        //Gdx.app.log(TAG, "Updating Texture descriptor for set " + descriptorSet + " with TexView: " + texture.getImageViewHandle() + " and Sampler: " + texture.getSamplerHandle());
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
        Gdx.app.log(TAG, "Requesting sprite batch pipeline...");

        Gdx.app.log(TAG, "TODO: Pipeline creation should be moved to VulkanPipelineManager!");
        FileHandle vertShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.vert.spv"); // Adjust path if needed
        FileHandle fragShaderFile = Gdx.files.internal("data/vulkan/shaders/spritebatch.frag.spv"); // Adjust path if needed
        if (!vertShaderFile.exists() || !fragShaderFile.exists()) {
            throw new GdxRuntimeException("Default SpriteBatch SPIR-V shaders not found!");
        }

        Gdx.app.log(TAG, "Deferring actual pipeline handle retrieval until rendering.");

        this.batchPipelineLayout = pipelineManager.getOrCreatePipelineLayout(this.batchDescriptorLayout); // Assume PM provides this
        if (this.batchPipelineLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to get/create pipeline layout for batch.");
        }
        Gdx.app.log(TAG, "Using pipeline layout: " + this.batchPipelineLayout);
    }

    @Override
    public void begin() {
        if (drawing) throw new IllegalStateException("Batch.end must be called before begin.");

        drawing = true; // Set drawing flag FIRST

        renderCalls = 0;
        spriteCount = 0;
        vertexBufferIdx = 0;
        lastTexture = null; // Reset last texture, force switch on first draw
        textureDescriptorNeedsUpdate = true; // Force descriptor update on first flush
        pipelineAndSetBoundThisBatch = false; // Force pipeline/set binding on first flush

        updateProjectionMatrixUBO();
    }

    @Override
    public void end() {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before end.");
        if (spriteCount > 0) {
            flush(); // Flush remaining sprites
        }
        drawing = false;
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
        int intBits = NumberUtils.floatToIntBits(this.colorPacked);
        Color color = this.batchColor; // Use the member field
        color.r = (intBits & 0xff) / 255f;
        color.g = ((intBits >>> 8) & 0xff) / 255f;
        color.b = ((intBits >>> 16) & 0xff) / 255f;
        color.a = ((intBits >>> 24) & 0xff) / 255f;
        return color;
    }

    @Override
    public void setPackedColor(float packedColor) {

    }

    @Override
    public float getPackedColor() {
        return colorPacked;
    }

    private void drawInternal(VulkanTexture texture, float[] spriteVertices, int numFloats) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");
        if (texture != lastTexture) { // Texture switch check
            flush(); // Flush previous batch
            lastTexture = texture;
            invTexWidth = 1.0f / texture.getWidth(); // Or use TextureRegion size?
            invTexHeight = 1.0f / texture.getHeight();
            this.textureDescriptorNeedsUpdate = true;
            //Gdx.app.log(TAG, "Texture switch detected, flushing previous batch.");
        } else if (vertexBufferIdx + numFloats > vertices.capacity()) { // Buffer full check
            flush();
            //Gdx.app.log(TAG, "Vertex buffer full, flushing previous batch.");
        }

        try {
            vertices.position(vertexBufferIdx);
            vertices.put(spriteVertices, 0, numFloats);
            vertexBufferIdx += numFloats;
            spriteCount++;
        } catch (BufferOverflowException e) {
            Gdx.app.error(TAG, "BufferOverflowException during vertices.put. vertexBufferIdx=" + vertexBufferIdx + ", numFloats=" + numFloats + ", capacity=" + vertices.capacity(), e);
            throw new GdxRuntimeException("Error copying vertex data (buffer overflow)", e);
        } catch (IllegalArgumentException e) { // Catch potential negative index etc.
            Gdx.app.error(TAG, "IllegalArgumentException during vertices.put. vertexBufferIdx=" + vertexBufferIdx + ", numFloats=" + numFloats + ", capacity=" + vertices.capacity(), e);
            throw new GdxRuntimeException("Error copying vertex data (illegal argument)", e);
        }
    }

    // Example public draw overload
    @Override
    public void draw(Texture texture, float x, float y, float originX, float originY, float width, float height, float scaleX,
                     float scaleY, float rotation, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {

        if (!(texture instanceof VulkanTexture)) {
            throw new GdxRuntimeException("Texture is not a VulkanTexture instance!");
        }
        VulkanTexture vkTexture = (VulkanTexture) texture;

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

        drawInternal(vkTexture, verts, i);
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {
        Gdx.app.log(TAG, "HERE");
    }

    @Override
    public void draw(Texture texture, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {
        Gdx.app.log(TAG, "HERE");
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, float u, float v, float u2, float v2) {
        Gdx.app.log(TAG, "HERE");
    }

    @Override
    public void draw(Texture texture, float x, float y) {
        Gdx.app.log(TAG, "HERE");
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height) {
        Gdx.app.log(TAG, "HERE");
    }

    /**
     * Helper method to retrieve the current swapchain RenderPass handle via Gdx.graphics.
     * Assumes rendering is happening within the main swapchain render pass context.
     *
     * @return The VkRenderPass handle.
     * @throws GdxRuntimeException if Gdx.graphics is not VulkanGraphics or swapchain/renderpass is invalid.
     */
    /*private long getCurrentRenderPassHandle() {
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
    }*/

    @Override
    public void dispose() {
        Gdx.app.log(TAG, "VulkanSpriteBatch dispose() called. Hash: " + this.hashCode());

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
            Gdx.app.log(TAG, "Disposing vertexBuffer handle: " + vertexBuffer.bufferHandle); // Log handle
            vertexBuffer.dispose();
            vertexBuffer = null;
        }
        if (indexBuffer != null) {
            Gdx.app.log(TAG, "Disposing indexBuffer handle: " + indexBuffer.bufferHandle); // Log handle
            indexBuffer.dispose();
            indexBuffer = null;
        }
        if (projMatrixUbo != null) {
            Gdx.app.log(TAG, "Disposing projMatrixUbo handle: " + projMatrixUbo.bufferHandle); // Log handle
            projMatrixUbo.dispose();
            projMatrixUbo = null;
        }

        if (batchDescriptorLayout != VK_NULL_HANDLE) {
            Gdx.app.log(TAG, "Destroying batch descriptor set layout: " + batchDescriptorLayout);
            vkDestroyDescriptorSetLayout(rawDevice, batchDescriptorLayout, null);
            batchDescriptorLayout = VK_NULL_HANDLE;
        }

        batchDescriptorSets.clear(); // Just clear the list of handles

        Gdx.app.log(TAG, "Disposed.");
    }

    /**
     * Draws a polygon sprite described by the given vertices. Assumes the vertices
     * contain all vertex attributes interleaved (pos, color, uv). Handles texture
     * switches, buffer flushing, and copying vertex data respecting offset and count.
     *
     * @param texture        The texture to use for this batch. Must be a VulkanTexture.
     * @param spriteVertices The float array containing vertex data.
     * @param offset         The starting offset in the spriteVertices array.
     * @param count          The number of float components (not vertices) to read from the array.
     */
    @Override
    public void draw(Texture texture, float[] spriteVertices, int offset, int count) {
        if (!drawing) {
            throw new IllegalStateException("VulkanSpriteBatch.begin must be called before draw.");
        }
        if (texture == null) {
            throw new IllegalArgumentException("Texture cannot be null.");
        }
        if (!(texture instanceof VulkanTexture)) {
            throw new GdxRuntimeException("Texture needs to be a VulkanTexture. Got: " + texture.getClass().getSimpleName());
        }
        if (count <= 0) {            // Note: It's valid for BitmapFontCache to call with count=0 sometimes, just return.
            return;
        }
        // Basic sanity check on parameters
        if (offset < 0 || offset + count > spriteVertices.length) {
            throw new IllegalArgumentException("Invalid offset/count for spriteVertices array. offset=" + offset + ", count=" + count + ", arrayLength=" + spriteVertices.length);
        }
        // Ensure vertex structure constants are valid
        if (vertexFloats <= 0 || VERTICES_PER_SPRITE <= 0) {
            throw new IllegalStateException("Batch vertex structure constants not initialized correctly.");
        }

        VulkanTexture vkTexture = (VulkanTexture) texture;

        if (vkTexture != lastTexture) {
            switchTexture(vkTexture);
        }

        // Vertex Copy Loop (Handles buffer full and processes 'count' floats)
        while (count > 0) {
            int remainingFloats = this.vertices.capacity() - this.vertexBufferIdx;

            if (remainingFloats < vertexFloats && this.vertexBufferIdx > 0) {
                flush(); // This resets vertexBufferIdx to 0
                remainingFloats = this.vertices.capacity(); // Buffer is now empty

                if (vkTexture != lastTexture) {
                    Gdx.app.error(TAG, "Texture changed unexpectedly after buffer-full flush! Reswitching.");
                    switchTexture(vkTexture); // This will flush potentially empty buffer again, but ensures state
                    remainingFloats = this.vertices.capacity();
                }
                if (remainingFloats < vertexFloats) { // Check capacity again after potential flush
                    throw new GdxRuntimeException("Vertex buffer capacity (" + remainingFloats + ") is too small for even one vertex (" + vertexFloats + ").");
                }
            } else if (remainingFloats == 0) {
                // Explicitly handle case where buffer was exactly filled by previous operation
                flush();
                remainingFloats = this.vertices.capacity();
                if (vkTexture != lastTexture) {
                    Gdx.app.error(TAG, "Texture changed unexpectedly after buffer-full flush! Reswitching.");
                    switchTexture(vkTexture);
                    remainingFloats = this.vertices.capacity();
                }
                if (remainingFloats == 0) {
                    throw new GdxRuntimeException("Vertex buffer capacity (" + remainingFloats + ") is too small for even one vertex (" + vertexFloats + ").");
                }
            }

            int copyCount = Math.min(remainingFloats, count);

            if (copyCount <= 0) {
                Gdx.app.error(TAG, "Calculated copyCount is zero or negative (" + copyCount + ") mid-loop. Breaking draw loop. remainingFloats=" + remainingFloats + ", count=" + count + ", vertexBufferIdx=" + vertexBufferIdx);
                break;
            }

            int verticesInCopy = copyCount / vertexFloats;
            int spritesInCopy = verticesInCopy / VERTICES_PER_SPRITE;

            if (copyCount % (vertexFloats * VERTICES_PER_SPRITE) != 0) {
                Gdx.app.log(TAG, "Warning: copyCount (" + copyCount + ") in draw call does not align with full sprites.");
            }

            try {
                // Bounds check for target buffer before setting position and putting data
                if (vertexBufferIdx < 0 || copyCount < 0 || vertexBufferIdx + copyCount > this.vertices.capacity()) {
                    throw new IllegalArgumentException("Internal Error: Invalid idx/copyCount for target vertex buffer PRE-COPY. vertexBufferIdx=" + vertexBufferIdx + ", copyCount=" + copyCount + ", capacity=" + this.vertices.capacity());
                }

                this.vertices.position(this.vertexBufferIdx);
                // Source bounds check happened earlier
                this.vertices.put(spriteVertices, offset, copyCount); // Copy the slice

            } catch (IndexOutOfBoundsException | BufferOverflowException e) {
                Gdx.app.error(TAG, "Buffer Exception during vertices.put. vertexBufferIdx=" + vertexBufferIdx + ", offset=" + offset + ", copyCount=" + copyCount + ", vertices.capacity=" + vertices.capacity() + ", spriteVertices.length=" + spriteVertices.length, e);
                throw new GdxRuntimeException("Error copying vertex data", e);
            } catch (IllegalArgumentException e) { // Catch our explicit check
                Gdx.app.error(TAG, "Error copying vertex data due to internal bounds check", e);
                throw new GdxRuntimeException("Error copying vertex data", e);
            }

            this.vertexBufferIdx += copyCount; // Advance target buffer index
            this.spriteCount += spritesInCopy; // Increment sprite count based on full sprites copied
            count -= copyCount;                // Decrease remaining floats in input
            offset += copyCount;               // Advance offset in input array
        }
    }

    @Override
    public void draw(TextureRegion region, float x, float y) {
        draw(region.getTexture(), x, y, region.getRegionWidth(), region.getRegionHeight());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float width, float height) {
        draw(region, x, y, 0, 0, width, height, 1, 1, 0);
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation) {
        draw(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation, region.getRegionX(), region.getRegionY(), region.getRegionWidth(), region.getRegionHeight(), false, false);
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation, boolean clockwise) { /* TODO */
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    // Inside VulkanSpriteBatch.java

    // Temporary array to hold vertex data for the Affine2 draw method.
    // Declared as a field to avoid allocations per call, but ensure it's not used concurrently if threading were added.
    private final float[] affineVertices = new float[VERTICES_PER_SPRITE * COMPONENTS_PER_VERTEX]; // 4 * 5 = 20 floats

    @Override
    public void draw(TextureRegion region, float width, float height, com.badlogic.gdx.math.Affine2 transform) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");

        // --- 1. Calculate Vertex Positions ---
        final float m00 = transform.m00;
        final float m01 = transform.m01;
        final float m02 = transform.m02; // x translation
        final float m10 = transform.m10;
        final float m11 = transform.m11;
        final float m12 = transform.m12; // y translation

        // Calculate corner positions relative to origin (0,0) then apply transform's translation
        float x1 = m02;             // Top-left X (0*m00 + 0*m01 + m02)
        float y1 = m12;             // Top-left Y (0*m10 + 0*m11 + m12)
        float x2 = m01 * height + m02; // Bottom-left X (0*m00 + height*m01 + m02)
        float y2 = m11 * height + m12; // Bottom-left Y (0*m10 + height*m11 + m12)
        float x3 = m00 * width + m01 * height + m02; // Bottom-right X
        float y3 = m10 * width + m11 * height + m12; // Bottom-right Y
        float x4 = m00 * width + m02; // Top-right X
        float y4 = m10 * width + m12; // Top-right Y

        // --- 2. Get Texture Coordinates ---
        float u = region.getU();
        float v = region.getV();
        float u2 = region.getU2();
        float v2 = region.getV2();

        // --- 3. Get Packed Color ---
        float color = this.colorPacked;

        // --- 4. Populate Temporary Vertex Array ---
        // Follows the order: X, Y, Color, U, V
        int idx = 0;
        // Top-left vertex (maps to u, v)
        affineVertices[idx++] = x1;
        affineVertices[idx++] = y1;
        affineVertices[idx++] = color;
        affineVertices[idx++] = u;
        affineVertices[idx++] = v;
        // Bottom-left vertex (maps to u, v2)
        affineVertices[idx++] = x2;
        affineVertices[idx++] = y2;
        affineVertices[idx++] = color;
        affineVertices[idx++] = u;
        affineVertices[idx++] = v2;
        // Bottom-right vertex (maps to u2, v2)
        affineVertices[idx++] = x3;
        affineVertices[idx++] = y3;
        affineVertices[idx++] = color;
        affineVertices[idx++] = u2;
        affineVertices[idx++] = v2;
        // Top-right vertex (maps to u2, v)
        affineVertices[idx++] = x4;
        affineVertices[idx++] = y4;
        affineVertices[idx++] = color;
        affineVertices[idx++] = u2;
        affineVertices[idx++] = v;

        // --- 5. Call the main draw method ---
        // This method handles texture switching and buffer flushing correctly.
        draw(region.getTexture(), affineVertices, 0, idx); // idx will be 20 here
    }

    @Override
    public void flush() {
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
            if (vertexFloats <= 0 || VERTICES_PER_SPRITE <= 0) throw new IllegalStateException("Vertex structure constants invalid in flush.");
            int verticesToDraw = vertexBufferIdx / vertexFloats;
            if (vertexBufferIdx % vertexFloats != 0) Gdx.app.error(TAG, "Flush Warning: vertexBufferIdx not multiple of vertexFloats!");
            currentSpriteCount = verticesToDraw / VERTICES_PER_SPRITE; // Recalculate based on index
            if (verticesToDraw % VERTICES_PER_SPRITE != 0) Gdx.app.error(TAG, "Flush Warning: vertex count not multiple of vertices per sprite!");
            if (currentSpriteCount == 0 && vertexBufferIdx > 0) {
                Gdx.app.log(TAG, "Flush: Still no full sprites to draw despite vertex data. Resetting buffer.");
                resetCountersAndLog(); // Reset without drawing
                return;
            }
            Gdx.app.log(TAG, "Recalculated sprite count: " + currentSpriteCount);
        }

        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
        long currentRenderPassHandle = gfx.getCurrentRenderPassHandle();
        //long currentRenderPassHandle = -2537500213581447150L;
        //Gdx.app.log(TAG, "Flush Check: RP Handle obtained = " + currentRenderPassHandle + ", VK_NULL_HANDLE = " + VK_NULL_HANDLE);

        //if (currentRenderPassHandle == VK_NULL_HANDLE) {
        //    throw new GdxRuntimeException("Cannot flush() batch, current render pass handle is null.");
        //}
        if (currentRenderPassHandle == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Flush Error: Condition (currentRenderPassHandle == VK_NULL_HANDLE) was TRUE! Handle value was: " + currentRenderPassHandle); // Log if condition is true
            throw new GdxRuntimeException("Cannot flush() batch, current render pass handle is null.");
        } else {
            //Gdx.app.log(TAG, "Flush Check: Condition (currentRenderPassHandle == VK_NULL_HANDLE) was FALSE. Handle is valid."); // Log if condition is false
        }

        VkCommandBuffer currentCommandBuffer = gfx.getCurrentCommandBuffer();
        if (currentCommandBuffer == null){
            throw new GdxRuntimeException("Cannot flush() batch, command buffer is null (missing begin?).");
        }
        if (lastTexture == null){
            throw new GdxRuntimeException("Cannot flush() batch, lastTexture is null (must draw at least one sprite first).");
        }
        if (this.vertexBuffer.bufferHandle == VK_NULL_HANDLE || this.indexBuffer.bufferHandle == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot flush, vertex or index buffer handle is null.");
        }
        if (this.batchPipelineLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot flush, pipeline layout handle is null.");
        }
        if (this.batchDescriptorSets.isEmpty() || this.batchDescriptorSets.get(0) == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot flush, descriptor set handle is invalid.");
        }

        int verticesToDraw = vertexBufferIdx / vertexFloats; // Total vertices accumulated
        int calculatedSprites = verticesToDraw / VERTICES_PER_SPRITE; // Total sprites based on vertices
        int indexCountToDraw = calculatedSprites * INDICES_PER_SPRITE; // Total indices needed

        if (indexCountToDraw <= 0) {
            Gdx.app.log(TAG, "Calculated indexCountToDraw is zero or negative, skipping draw but resetting buffer.");
            resetCountersAndLog();
            pipelineAndSetBoundThisBatch = false; // Reset bound state as flush technically completed
            return;
        }

        int currentFrameIndex = gfx.getCurrentFrameIndex(); // <<< Get current frame index
        if (currentFrameIndex < 0 || currentFrameIndex >= batchDescriptorSets.size()) {
            throw new GdxRuntimeException("Invalid currentFrameIndex [" + currentFrameIndex + "] for descriptor sets");
        }
        long currentFrameSet = batchDescriptorSets.get(currentFrameIndex); // Use correct index

        if (textureDescriptorNeedsUpdate) {
            if (this.lastTexture == null) throw new GdxRuntimeException("Consistency error: lastTexture is null in flush during texture update check.");
            //Gdx.app.log(TAG, "Flush: Updating texture descriptor for set " + currentFrameSet);
            updateTextureDescriptor(currentFrameSet, this.lastTexture);
            textureDescriptorNeedsUpdate = false;
            pipelineAndSetBoundThisBatch = false; // Force re-bind after descriptor update
        }

        if (!pipelineAndSetBoundThisBatch) {
            long pipelineToUse = pipelineManager.getOrCreateSpriteBatchPipeline(this.batchDescriptorLayout, currentRenderPassHandle);
            if (pipelineToUse == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Could not get/create sprite batch pipeline in flush.");
            }

            vkCmdBindPipeline(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineToUse);

            // Bind Descriptor Set
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer pSet = stack.longs(currentFrameSet);
                vkCmdBindDescriptorSets(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, this.batchPipelineLayout, 0, pSet, null);
            }
            pipelineAndSetBoundThisBatch = true;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pBuffers = stack.longs(vertexBuffer.bufferHandle);
            LongBuffer pOffsets = stack.longs(0); // Always use the start of the vertex buffer
            vkCmdBindVertexBuffers(currentCommandBuffer, 0, pBuffers, pOffsets);
        }
        // Index buffer is static, bind offset 0
        vkCmdBindIndexBuffer(currentCommandBuffer, indexBuffer.bufferHandle, 0, VK_INDEX_TYPE_UINT16);

        // --- Flush Host Cache (VMA) ---
        long vertexBytesToFlush = (long) vertexBufferIdx * Float.BYTES;
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

        //vkCmdDrawIndexed(currentCommandBuffer, indexCountToDraw, 1, 0, 0, 0); // instanceCount=1, firstIndex=0, vertexOffset=0, firstInstance=0

        //Gdx.app.log(TAG, "Flush: Attempting vkCmdDrawIndexed with indexCount=" + indexCountToDraw); // Log index count
        if (indexCountToDraw > 0) {
            vkCmdDrawIndexed(currentCommandBuffer, indexCountToDraw, 1, 0, 0, 0);
            renderCalls++; // Moved increment here
        } else {
            Gdx.app.log(TAG, "Flush: Skipping vkCmdDrawIndexed because indexCountToDraw is zero.");
        }

        //renderCalls++;

        resetCountersAndLog();
    }

    // Helper to avoid repeating reset code/logs
    private void resetCountersAndLog() {
        //Gdx.app.log(TAG, "Flush: Resetting counters (pre-reset). Current idx = " + vertexBufferIdx + ", Current spriteCount = " + this.spriteCount);
        vertexBufferIdx = 0;
        spriteCount = 0;
        //Gdx.app.log(TAG, "Flush: Counters reset attempted (post-reset). Current idx = " + vertexBufferIdx + ", Current spriteCount = " + this.spriteCount);
    }

    /**
     * Switches the texture and flushes the batch if necessary.
     * Updates inverse texture size and flags descriptor for update.
     *
     * @param texture The new VulkanTexture to switch to.
     */
    protected void switchTexture(VulkanTexture texture) {
        // Ensure flush is called BEFORE changing lastTexture if spriteCount > 0
        if (this.spriteCount > 0) {
            Gdx.app.log(TAG, "Switching texture, flushing previous batch (" + this.spriteCount + " sprites).");
            flush(); // Flush existing data with the old texture
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
    public void disableBlending() {
        Gdx.app.log(TAG, "WARN: disableBlending() not fully implemented."); /* TODO */
    }

    @Override
    public void enableBlending() {
        Gdx.app.log(TAG, "WARN: enableBlending() not fully implemented."); /* TODO */
    }

    @Override
    public void setBlendFunction(int srcFunc, int dstFunc) {
        Gdx.app.log(TAG, "WARN: setBlendFunction() not fully implemented."); /* TODO */
    }

    @Override
    public void setBlendFunctionSeparate(int srcFuncColor, int dstFuncColor, int srcFuncAlpha, int dstFuncAlpha) {
        Gdx.app.log(TAG, "WARN: setBlendFunctionSeparate() not fully implemented."); /* TODO */
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
        // --- REMOVE check for 'drawing' and call to updateProjectionMatrixUBO ---
        // Only update the internal field. 'begin()' will handle uploading it.
        if (!this.projectionMatrix.equals(projection)) {
            //Gdx.app.log(TAG, "Projection matrix changed, updating internal matrix."); // Optional log
            this.projectionMatrix.set(projection);
        }
        // if (drawing) { // REMOVE THIS BLOCK
        //     Gdx.app.log(TAG, "Projection matrix changed DURING drawing, updating UBO."); // Add Log
        //     updateProjectionMatrixUBO();
        // }
    }

    @Override
    public void setTransformMatrix(Matrix4 transform) {
        // Standard SpriteBatch ignores this, bakes transform into vertices.
        // We should do the same in draw() methods.
        // this.transformMatrix.set(transform); // Generally not used by SpriteBatch
    }

    @Override
    public void setShader(ShaderProgram shader) {
        Gdx.app.log(TAG, "WARN: setShader() not implemented."); /* TODO: Select pipeline based on shader */
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