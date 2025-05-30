package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Affine2;
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

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanSpriteBatch implements Batch, VulkanFrameResourcePreparer, Disposable {

    private static final String TAG = "VkSpriteBatchStreaming"; // Updated Tag
    private static final boolean DEBUG = false; // Enable for verbose logging

    public static final int POSITION_COMPONENTS = 2;
    public static final int COLOR_COMPONENTS = 1; // Represents 1 float for packed color
    public static final int TEXCOORD_COMPONENTS = 2;
    public static final int TEXINDEX_COMPONENTS = 1;
    public static final int COMPONENTS_PER_VERTEX = POSITION_COMPONENTS + COLOR_COMPONENTS + TEXCOORD_COMPONENTS + TEXINDEX_COMPONENTS; // 6
    public static final int BYTES_PER_VERTEX = COMPONENTS_PER_VERTEX * Float.BYTES; // 24
    public static final int VERTICES_PER_SPRITE = 4;
    public static final int INDICES_PER_SPRITE = 6;

    // Vertex attributes used to inform pipeline creation if not using a custom shader.
    // If pipeline manager creates pipeline using specific info, this is for reference/consistency.
    public static final VertexAttributes BATCH_VERTEX_ATTRIBUTES = new VertexAttributes(
            new VertexAttribute(VertexAttributes.Usage.Position, POSITION_COMPONENTS, ShaderProgram.POSITION_ATTRIBUTE),
            new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, ShaderProgram.COLOR_ATTRIBUTE), // Assumes type GL_UNSIGNED_BYTE, normalized=true, 4 components (4 bytes)
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, TEXCOORD_COMPONENTS, ShaderProgram.TEXCOORD_ATTRIBUTE + "0"),
            new VertexAttribute(VertexAttributes.Usage.Generic, TEXINDEX_COMPONENTS, GL20.GL_FLOAT, false, "in_texArrayIndex")
    );

    private final VkDevice rawDevice;
    private final long vmaAllocator;
    private final VulkanPipelineManager pipelineManager;
    private final VulkanDescriptorManager descriptorManager;
    private VulkanTextureBatch textureBatcher;

    private VulkanBuffer vertexBuffer; // Large, mapped buffer for an entire frame's worth of vertex data
    private ByteBuffer mappedVertexByteBuffer;
    private FloatBuffer vertices;      // Float view of mappedVertexByteBuffer

    private VulkanBuffer indexBuffer;  // Sized for maxSpritesInOneFlush, pattern is reused

    private VulkanBuffer projMatrixUbo;
    private final Color batchColor = new Color();
    private boolean drawing = false;
    private final Matrix4 projectionMatrix = new Matrix4();
    private final Matrix4 transformMatrix = new Matrix4();
    private float colorPacked = Color.WHITE_FLOAT_BITS;

    private final int maxSpritesInOneFlush; // Max sprites per individual draw call
    // private final int totalVertexBufferCapacityInSprites; // For reference, actual capacity is in vertices.capacity()

    // State for managing the large streaming vertex buffer
    private int cpuBufferWritePositionFloats = 0; // Current write index in 'vertices' FloatBuffer (in floats)
    private int currentBatchStartVertexIndex = 0; // Starting *vertex index* for the current sub-batch (for vkCmdDrawIndexed vertexOffset)
    private int spritesInCurrentSubBatch = 0;   // Sprites accumulated for the *current* flush

    public int renderCalls = 0;
    private long batchPipelineLayout = VK_NULL_HANDLE;
    private long currentPipeline = VK_NULL_HANDLE;

    private boolean blendingEnabled = true;
    private int blendSrcFunc = GL20.GL_SRC_ALPHA;
    private int blendDstFunc = GL20.GL_ONE_MINUS_SRC_ALPHA;
    private int blendSrcFuncAlpha = GL20.GL_SRC_ALPHA;
    private int blendDstFuncAlpha = GL20.GL_ONE_MINUS_SRC_ALPHA;
    private boolean blendFuncSeparate = false;

    private final float[] singleSpriteVertices; // Temp array for calculating one sprite's vertices

    public VulkanSpriteBatch() {
        this(256, 4096); // Default: flush every 256 sprites, total buffer capacity for ~4096 sprites
    }

    public VulkanSpriteBatch(int flushTriggerSpriteCount) {
        this(flushTriggerSpriteCount, Math.min(flushTriggerSpriteCount * 4, 4096)); // Sets total buffer capacity to 4 times sprite count up to 4096 sprites
    }

    public VulkanSpriteBatch(int flushTriggerSpriteCount, int totalBufferCapacityInSprites) {
        if (DEBUG) Gdx.app.log(TAG, "Initializing. Flush trigger: " + flushTriggerSpriteCount + " sprites, Total buffer capacity: " + totalBufferCapacityInSprites + " sprites.");

        if (flushTriggerSpriteCount <= 0) throw new IllegalArgumentException("flushTriggerSpriteCount must be > 0");
        if (totalBufferCapacityInSprites <= 0) throw new IllegalArgumentException("totalBufferCapacityInSprites must be > 0");
        if (totalBufferCapacityInSprites < flushTriggerSpriteCount) {
            Gdx.app.log(TAG, "Warning: totalBufferCapacityInSprites (" + totalBufferCapacityInSprites + ") is less than flushTriggerSpriteCount (" + flushTriggerSpriteCount + "). Total capacity will be based on flush trigger size.");
            totalBufferCapacityInSprites = flushTriggerSpriteCount;
        }

        this.maxSpritesInOneFlush = flushTriggerSpriteCount;
        // this.totalVertexBufferCapacityInSprites = totalBufferCapacityInSprites; // Store for reference if needed

        this.singleSpriteVertices = new float[VERTICES_PER_SPRITE * COMPONENTS_PER_VERTEX];

        VulkanApplication app = (VulkanApplication) Gdx.app;
        VulkanGraphics gfx = (VulkanGraphics) app.getGraphics();
        if (gfx == null) throw new GdxRuntimeException("VulkanGraphics instance cannot be null!");
        gfx.registerFrameResourcePreparer(this);

        VulkanDevice device = gfx.getVulkanDevice();
        this.vmaAllocator = gfx.getVmaAllocator();
        this.pipelineManager = gfx.getPipelineManager();
        this.descriptorManager = gfx.getDescriptorManager();
        if (device == null || vmaAllocator == VK_NULL_HANDLE || pipelineManager == null || descriptorManager == null) {
            throw new GdxRuntimeException("Failed to retrieve necessary Vulkan managers!");
        }
        this.rawDevice = device.getRawDevice();

        int maxTexturesForTextureBatcher = 256; // Example, make configurable if needed
        this.textureBatcher = new VulkanTextureBatch(descriptorManager, maxTexturesForTextureBatcher, gfx);

        long vertexBufferSizeBytes = (long) totalBufferCapacityInSprites * VERTICES_PER_SPRITE * BYTES_PER_VERTEX;
        this.vertexBuffer = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, vertexBufferSizeBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO, // Should resolve to CPU_TO_GPU or similar with HOST_ACCESS
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
        PointerBuffer pDataVB = MemoryUtil.memAllocPointer(1);
        try {
            vkCheck(vmaMapMemory(vmaAllocator, vertexBuffer.allocationHandle, pDataVB), "VMA Failed to map vertex buffer");
            this.mappedVertexByteBuffer = MemoryUtil.memByteBuffer(pDataVB.get(0), (int) vertexBufferSizeBytes);
            this.vertices = this.mappedVertexByteBuffer.asFloatBuffer();
            if (DEBUG) Gdx.app.log(TAG, "Vertex buffer created/mapped. Floats capacity: " + this.vertices.capacity() +
                    " (enough for " + (this.vertices.capacity() / (VERTICES_PER_SPRITE * COMPONENTS_PER_VERTEX)) + " sprites).");
        } finally {
            MemoryUtil.memFree(pDataVB);
        }

        createIndexBuffer(device, this.maxSpritesInOneFlush); // Index buffer sized for one flush trigger

        long uboSize = 16 * Float.BYTES; // For one Matrix4
        this.projMatrixUbo = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, uboSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);

        this.batchPipelineLayout = pipelineManager.getOrCreatePipelineLayout(textureBatcher.getDescriptorSetLayout());
        if (this.batchPipelineLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to get/create pipeline layout from TextureBatcher.");
        }
        if (DEBUG) Gdx.app.log(TAG, "VulkanSpriteBatch Initialization complete.");
    }

    private void createIndexBuffer(VulkanDevice device, int numSpritesForIndexBuffer) {
        int maxIndices = numSpritesForIndexBuffer * INDICES_PER_SPRITE;
        ShortBuffer indicesCpu = BufferUtils.newShortBuffer(maxIndices);
        ((Buffer) indicesCpu).clear();
        for (int i = 0, v = 0; i < numSpritesForIndexBuffer; i++, v += VERTICES_PER_SPRITE) { // Iterate per sprite
            indicesCpu.put((short) v);
            indicesCpu.put((short) (v + 1));
            indicesCpu.put((short) (v + 2));
            indicesCpu.put((short) (v + 2));
            indicesCpu.put((short) (v + 3));
            indicesCpu.put((short) v);
        }
        ((Buffer) indicesCpu).flip();

        this.indexBuffer = VulkanResourceUtil.createDeviceLocalBuffer(
                device, vmaAllocator, indicesCpu, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        if (DEBUG) Gdx.app.log(TAG, "Index buffer created (Indices for " + numSpritesForIndexBuffer + " sprites).");
    }

    private void updateProjectionMatrixUBO() {
        if (projMatrixUbo == null || projMatrixUbo.bufferHandle == VK_NULL_HANDLE || vmaAllocator == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Cannot update projection UBO: UBO or allocator is null.");
            return;
        }
        // This writes to the UBO buffer's mapped memory if it's persistently mapped,
        // or handles map/unmap if not. The descriptor set update happens in TextureBatcher.
        VulkanResourceUtil.updateBuffer(vmaAllocator, projMatrixUbo, this.projectionMatrix.val, 0, 16 * Float.BYTES);
    }

    @Override
    public void begin() {
        if (drawing) throw new IllegalStateException("Batch.end must be called before begin.");
        drawing = true;
        renderCalls = 0;
        spritesInCurrentSubBatch = 0;
        cpuBufferWritePositionFloats = 0;
        currentBatchStartVertexIndex = 0;
        currentPipeline = VK_NULL_HANDLE;
        textureBatcher.resetAndPrepareForFrame();
        updateProjectionMatrixUBO(); // Update UBO buffer content
        if (DEBUG) Gdx.app.debug(TAG, "Begin. cpuWritePos=" + cpuBufferWritePositionFloats +
                ", batchStartVert=" + currentBatchStartVertexIndex +
                ", spritesInSubBatch=" + spritesInCurrentSubBatch);
    }

    @Override
    public void end() {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before end.");
        if (spritesInCurrentSubBatch > 0) {
            flush();
        }
        drawing = false;
    }

    // --- Standard Color and Projection/Transform Matrix Methods (Mostly Unchanged) ---
    @Override
    public void setColor(Color tint) {
        this.colorPacked = tint.toFloatBits();
    }

    @Override
    public void setColor(float r, float g, float b, float a) {
        this.colorPacked = Color.toFloatBits(r, g, b, a);
    }

    @Override
    public Color getColor() {
        batchColor.set(NumberUtils.floatToIntBits(this.colorPacked));
        return batchColor;
    }

    @Override
    public void setPackedColor(float packedColor) {
        this.colorPacked = packedColor;
    }

    @Override
    public float getPackedColor() {
        return colorPacked;
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
        if (projection == null) throw new IllegalArgumentException("Projection matrix cannot be null.");
        if (drawing && !projectionMatrix.equals(projection)) { // Only flush if matrix actually changes
            flush();
        }
        projectionMatrix.set(projection);
        if (drawing) { // If drawing, UBO buffer content will be updated by prepareResourcesForFrame before next flush.
            updateProjectionMatrixUBO(); // Update UBO content now if drawing, TextureBatcher will handle DS update
        } else {
            updateProjectionMatrixUBO(); // Update UBO content if not drawing
        }
    }

    @Override
    public void setTransformMatrix(Matrix4 transform) {
        if (transform == null) throw new IllegalArgumentException("Transform matrix cannot be null.");
        if (drawing && !transformMatrix.equals(transform)) { // Only flush if matrix actually changes
            flush();
        }
        transformMatrix.set(transform);
        // Transform matrix is applied CPU-side, no UBO update needed here for it.
    }


    // --- Draw Methods ---
    private void checkFlush(VulkanTexture tex) {
        // This texture index acquisition might affect textureBatcher's internal state
        // if it decides a new texture requires a new descriptor set build (though current logic defers that).
        // For now, we assume addTexture() is cheap if the texture is already known for the cycle.
        int tempTexIdx = textureBatcher.addTexture(tex); // Ensure texture is known to batcher

        if (spritesInCurrentSubBatch >= maxSpritesInOneFlush ||
                cpuBufferWritePositionFloats + (VERTICES_PER_SPRITE * COMPONENTS_PER_VERTEX) > vertices.capacity()) {
            if (DEBUG) {
                //   Gdx.app.debug(TAG, "Flushing in draw: spritesInSubBatch=" + spritesInCurrentSubBatch +
                //           ", cpuWritePos=" + cpuBufferWritePositionFloats + ", capacity=" + vertices.capacity());
            }
            flush();
            // After flush, ensure the texture index is re-acquired/re-confirmed for the new sub-batch state
            // as textureBatcher.resetAndPrepareForFrame() isn't called by flush().
            // However, textureBatcher.addTexture() is designed to be called multiple times.
            textureBatcher.addTexture(tex); // This ensures it's in the current uniqueTexturesForCurrentDrawCycle
        }
    }

    @Override
    public void draw(Texture texture, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");

        VulkanTexture tex = (VulkanTexture) texture;
        if (tex == null) tex = textureBatcher.getDefaultTexture();
        if (tex == null) throw new GdxRuntimeException("Null texture and no default texture.");

        checkFlush(tex); // Check if flush is needed BEFORE calculating vertices for *this* sprite

        int textureDeviceIndex = textureBatcher.addTexture(tex); // Get final index after potential flush
        float floatTexIndex = (float) textureDeviceIndex;

        float[] localVerts = this.singleSpriteVertices;
        int i = 0;
        // ... (Vertex calculation logic into localVerts as before, using floatTexIndex) ...
        float invTexWidth = 1.0f / tex.getWidth();
        float invTexHeight = 1.0f / tex.getHeight();
        float u = srcX * invTexWidth;
        float v = srcY * invTexHeight;
        float u2 = (srcX + srcWidth) * invTexWidth;
        float v2 = (srcY + srcHeight) * invTexHeight;
        final float packedColor = this.colorPacked;
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
        final float p1x = fx, p1y = fy;
        final float p2x = fx, p2y = fy2;
        final float p3x = fx2, p3y = fy2;
        final float p4x = fx2, p4y = fy;
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
            y4 = y3 - (y2 - y1);
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
        localVerts[i++] = x1;
        localVerts[i++] = y1;
        localVerts[i++] = packedColor;
        localVerts[i++] = u;
        localVerts[i++] = v;
        localVerts[i++] = floatTexIndex;
        localVerts[i++] = x2;
        localVerts[i++] = y2;
        localVerts[i++] = packedColor;
        localVerts[i++] = u;
        localVerts[i++] = v2;
        localVerts[i++] = floatTexIndex;
        localVerts[i++] = x3;
        localVerts[i++] = y3;
        localVerts[i++] = packedColor;
        localVerts[i++] = u2;
        localVerts[i++] = v2;
        localVerts[i++] = floatTexIndex;
        localVerts[i++] = x4;
        localVerts[i++] = y4;
        localVerts[i++] = packedColor;
        localVerts[i++] = u2;
        localVerts[i++] = v;
        localVerts[i++] = floatTexIndex;

        // Copy to the main mapped buffer
        try {
            vertices.position(cpuBufferWritePositionFloats);
            vertices.put(localVerts, 0, i); // i should be 24
        } catch (BufferOverflowException e) {
            throw new GdxRuntimeException("BufferOverflow while writing single sprite. cpuPos=" + cpuBufferWritePositionFloats + ", spriteFloats=" + i + ", capacity=" + vertices.capacity(), e);
        }
        cpuBufferWritePositionFloats += i;
        spritesInCurrentSubBatch++;
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, float u, float v, float u2, float v2) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");
        VulkanTexture tex = (VulkanTexture) texture;
        if (tex == null) tex = textureBatcher.getDefaultTexture();
        if (tex == null) throw new GdxRuntimeException("Null texture and no default texture.");

        checkFlush(tex);
        int textureDeviceIndex = textureBatcher.addTexture(tex);
        float floatTexIndex = (float) textureDeviceIndex;

        float[] localVerts = this.singleSpriteVertices;
        int i = 0;
        final float packedColor = this.colorPacked;
        localVerts[i++] = x;
        localVerts[i++] = y;
        localVerts[i++] = packedColor;
        localVerts[i++] = u;
        localVerts[i++] = v;
        localVerts[i++] = floatTexIndex;
        localVerts[i++] = x;
        localVerts[i++] = y + height;
        localVerts[i++] = packedColor;
        localVerts[i++] = u;
        localVerts[i++] = v2;
        localVerts[i++] = floatTexIndex;
        localVerts[i++] = x + width;
        localVerts[i++] = y + height;
        localVerts[i++] = packedColor;
        localVerts[i++] = u2;
        localVerts[i++] = v2;
        localVerts[i++] = floatTexIndex;
        localVerts[i++] = x + width;
        localVerts[i++] = y;
        localVerts[i++] = packedColor;
        localVerts[i++] = u2;
        localVerts[i++] = v;
        localVerts[i++] = floatTexIndex;

        try {
            vertices.position(cpuBufferWritePositionFloats);
            vertices.put(localVerts, 0, i);
        } catch (BufferOverflowException e) {
            throw new GdxRuntimeException("BufferOverflow while writing simple sprite. cpuPos=" + cpuBufferWritePositionFloats + ", spriteFloats=" + i + ", capacity=" + vertices.capacity(), e);
        }
        cpuBufferWritePositionFloats += i;
        spritesInCurrentSubBatch++;
    }

    @Override
    public void draw(Texture texture, float[] spriteVertices, int offset, int count) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");

        final int inputComponentsPerVertex = 5; // x, y, color, u, v
        final int outputFloatsPerSprite = VERTICES_PER_SPRITE * COMPONENTS_PER_VERTEX;

        if (count % (inputComponentsPerVertex * VERTICES_PER_SPRITE) != 0) {
            throw new GdxRuntimeException("Incoming spriteVertices count (" + count +
                    ") must be a multiple of floats per sprite (" + (inputComponentsPerVertex * VERTICES_PER_SPRITE) + ")");
        }
        int numSpritesInCall = count / (inputComponentsPerVertex * VERTICES_PER_SPRITE);
        if (numSpritesInCall == 0) return;

        VulkanTexture tex = (VulkanTexture) texture;
        if (tex == null) {
            tex = textureBatcher.getDefaultTexture();
            if (tex == null) throw new GdxRuntimeException("Null texture and no default texture available in draw(float[]).");
        }

        int inputVertexDataReadOffset = offset;

        for (int spriteNum = 0; spriteNum < numSpritesInCall; spriteNum++) {
            // Check flush condition *before* processing this sprite from the input array
            checkFlush(tex);
            int textureDeviceIndex = textureBatcher.addTexture(tex); // Get index for current sub-batch state
            float floatTexIndex = (float) textureDeviceIndex;

            try {
                vertices.position(cpuBufferWritePositionFloats);
                for (int v = 0; v < VERTICES_PER_SPRITE; v++) {
                    vertices.put(spriteVertices[inputVertexDataReadOffset++]); // x
                    vertices.put(spriteVertices[inputVertexDataReadOffset++]); // y
                    vertices.put(spriteVertices[inputVertexDataReadOffset++]); // color (packed float)
                    vertices.put(spriteVertices[inputVertexDataReadOffset++]); // u
                    vertices.put(spriteVertices[inputVertexDataReadOffset++]); // v
                    vertices.put(floatTexIndex);
                }
            } catch (BufferOverflowException e) {
                throw new GdxRuntimeException("BufferOverflow in draw(float[]). cpuPos=" + cpuBufferWritePositionFloats +
                        ", spriteFloats=" + outputFloatsPerSprite + ", capacity=" + vertices.capacity(), e);
            }
            cpuBufferWritePositionFloats += outputFloatsPerSprite;
            spritesInCurrentSubBatch++;
        }
    }

    // Other draw overloads (TextureRegion, simple x/y, x/y/width/height) should call the more detailed ones above.
    @Override
    public void draw(Texture texture, float x, float y, float width, float height, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {
        draw(texture, x, y, 0f, 0f, width, height, 1f, 1f, 0f, srcX, srcY, srcWidth, srcHeight, flipX, flipY);
    }

    @Override
    public void draw(Texture texture, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {
        draw(texture, x, y, (float) srcWidth, (float) srcHeight, srcX, srcY, srcWidth, srcHeight, false, false);
    }

    @Override
    public void draw(Texture texture, float x, float y) {
        if (texture == null) return;
        draw(texture, x, y, texture.getWidth(), texture.getHeight());
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height) {
        draw(texture, x, y, width, height, 0f, 0f, 1f, 1f);
    }

    @Override
    public void draw(TextureRegion region, float x, float y) {
        if (region == null) return;
        draw(region, x, y, region.getRegionWidth(), region.getRegionHeight());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float width, float height) {
        if (region == null) return;
        draw(region.getTexture(), x, y, width, height, region.getU(), region.getV(), region.getU2(), region.getV2());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation) {
        if (region == null) return;
        draw(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation, region.getRegionX(), region.getRegionY(), region.getRegionWidth(), region.getRegionHeight(), false, false);
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation, boolean clockwise) {
        draw(region, x, y, originX, originY, width, height, scaleX, scaleY, rotation);
    }

    @Override
    public void draw(TextureRegion region, float width, float height, Affine2 transform) { /* ... implementation as before ... */
        if (region == null) return;
        float x = transform.m02;
        float y = transform.m12;
        float scaleX = (float) Math.sqrt(transform.m00 * transform.m00 + transform.m10 * transform.m10);
        float scaleY = (float) Math.sqrt(transform.m01 * transform.m01 + transform.m11 * transform.m11);
        float rotation = MathUtils.atan2(transform.m10, transform.m00) * MathUtils.radiansToDegrees;
        draw(region.getTexture(), x, y, 0f, 0f, width, height, scaleX, scaleY, rotation, region.getRegionX(), region.getRegionY(), region.getRegionWidth(), region.getRegionHeight(), false, false);
    }

    // drawInternal is no longer needed as its logic is integrated into the main draw methods with checkFlush

    @Override
    public void flush() {
        if (spritesInCurrentSubBatch == 0) {
            if (DEBUG && renderCalls == 0 && cpuBufferWritePositionFloats > 0) { // Log if buffer has data but no sprites were "counted" for sub-batch
                Gdx.app.debug(TAG, "Flush called with 0 spritesInCurrentSubBatch, but cpuBufferWritePositionFloats = " + cpuBufferWritePositionFloats + ". This might indicate an issue if data was expected to be drawn.");
            } else if (DEBUG) {
                // Gdx.app.debug(TAG, "Flush called with 0 sprites in current sub-batch. Skipping draw call.");
            }
            return; // Nothing to flush for this sub-batch
        }

        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
        if (gfx == null) { /* ... error ... */
            spritesInCurrentSubBatch = 0;
            return;
        }
        VkCommandBuffer currentCommandBuffer = gfx.getCurrentCommandBuffer();
        long currentRenderPassHandle = gfx.getCurrentRenderPassHandle(); // Use the correct getter
        if (currentCommandBuffer == null || currentRenderPassHandle == VK_NULL_HANDLE || batchPipelineLayout == VK_NULL_HANDLE) {
            /* ... error ... */
            spritesInCurrentSubBatch = 0;
            return;
        }

        textureBatcher.buildAndBind(currentCommandBuffer, batchPipelineLayout, projMatrixUbo);

        long bytesInThisSubBatch = (long) spritesInCurrentSubBatch * BYTES_PER_VERTEX;
        long vmaFlushOffsetBytes = (long) currentBatchStartVertexIndex * BYTES_PER_VERTEX;

        if (DEBUG) {
            // Gdx.app.debug(TAG, "Flushing: " + spritesInCurrentSubBatch + " sprites. VMA Flush Offset: " + vmaFlushOffsetBytes + ", Size: " + bytesInThisSubBatch +
            //         ". Draw Vertex Offset: " + currentBatchStartVertexIndex + ". CPU Write Pos (floats): " + cpuBufferWritePositionFloats);
        }

        if (bytesInThisSubBatch > 0 && vertexBuffer.allocationHandle != VK_NULL_HANDLE && vmaAllocator != VK_NULL_HANDLE) {
            Vma.vmaFlushAllocation(vmaAllocator, vertexBuffer.allocationHandle, vmaFlushOffsetBytes, bytesInThisSubBatch);
        }

        long pipelineToUse = pipelineManager.getOrCreateSpriteBatchPipeline(
                batchPipelineLayout, currentRenderPassHandle, blendingEnabled,
                blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha, blendFuncSeparate);
        if (pipelineToUse == VK_NULL_HANDLE) { /* ... error ... */
            spritesInCurrentSubBatch = 0;
            return;
        }
        if (pipelineToUse != currentPipeline) {
            vkCmdBindPipeline(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineToUse);
            currentPipeline = pipelineToUse;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pVertexBuffers = stack.longs(vertexBuffer.bufferHandle);
            LongBuffer pOffsets = stack.longs(0L); // Vertex buffer is always bound from its start
            vkCmdBindVertexBuffers(currentCommandBuffer, 0, pVertexBuffers, pOffsets);
            vkCmdBindIndexBuffer(currentCommandBuffer, indexBuffer.bufferHandle, 0, VK_INDEX_TYPE_UINT16);
        }

        int indexCountToDraw = spritesInCurrentSubBatch * INDICES_PER_SPRITE;
        vkCmdDrawIndexed(currentCommandBuffer, indexCountToDraw, 1, 0, currentBatchStartVertexIndex, 0);
        renderCalls++;

        currentBatchStartVertexIndex += spritesInCurrentSubBatch * VERTICES_PER_SPRITE;
        spritesInCurrentSubBatch = 0;
        // cpuBufferWritePositionFloats is NOT reset here. It's reset in begin().
        // This allows the next sub-batch to continue writing into the large buffer.
    }

    // Blending methods (disableBlending, enableBlending, setBlendFunction, setBlendFunctionSeparate) remain the same.
    // They correctly call flush() before changing state that affects the pipeline.
    @Override
    public void disableBlending() {
        if (blendingEnabled) {
            flush();
            blendingEnabled = false;
            currentPipeline = VK_NULL_HANDLE;
        }
    }

    @Override
    public void enableBlending() {
        if (!blendingEnabled) {
            flush();
            blendingEnabled = true;
            currentPipeline = VK_NULL_HANDLE;
        }
    }

    @Override
    public void setBlendFunction(int srcFunc, int dstFunc) {
        setBlendFunctionSeparate(srcFunc, dstFunc, srcFunc, dstFunc);
        blendFuncSeparate = false;
    }

    @Override
    public void setBlendFunctionSeparate(int srcFuncColor, int dstFuncColor, int srcFuncAlpha, int dstFuncAlpha) {
        if (this.blendSrcFunc != srcFuncColor || this.blendDstFunc != dstFuncColor || this.blendSrcFuncAlpha != srcFuncAlpha || this.blendDstFuncAlpha != dstFuncAlpha) {
            flush();
            this.blendSrcFunc = srcFuncColor;
            this.blendDstFunc = dstFuncColor;
            this.blendSrcFuncAlpha = srcFuncAlpha;
            this.blendDstFuncAlpha = dstFuncAlpha;
            this.blendFuncSeparate = true;
            currentPipeline = VK_NULL_HANDLE;
        }
    }

    @Override
    public int getBlendSrcFunc() {
        return blendSrcFunc;
    }

    @Override
    public int getBlendDstFunc() {
        return blendDstFunc;
    }

    @Override
    public int getBlendSrcFuncAlpha() {
        return blendFuncSeparate ? blendSrcFuncAlpha : blendSrcFunc;
    }

    @Override
    public int getBlendDstFuncAlpha() {
        return blendFuncSeparate ? blendDstFuncAlpha : blendDstFunc;
    }


    // Shader and drawing state getters remain the same
    @Override
    public void setShader(ShaderProgram shader) {
        Gdx.app.error(TAG, "setShader() is not supported.");
    }

    @Override
    public ShaderProgram getShader() {
        return null;
    }

    @Override
    public boolean isBlendingEnabled() {
        return blendingEnabled;
    }

    @Override
    public boolean isDrawing() {
        return drawing;
    }

    @Override
    public void prepareResourcesForFrame(int frameIndex) {
        // This is called by VulkanGraphics before rendering for this window starts for the frame.
        // If projection matrix could have changed externally (e.g. camera moved by UI, then batch set to it)
        // updating UBO buffer content here is a good idea.
        updateProjectionMatrixUBO();
    }

    @Override
    public void dispose() {
        if (DEBUG) Gdx.app.log(TAG, "Disposing VulkanSpriteBatch...");
        if (Gdx.app instanceof VulkanApplication) {
            VulkanGraphics gfx = (VulkanGraphics) Gdx.app.getGraphics();
            if (gfx != null) gfx.unregisterFrameResourcePreparer(this);
        }
        if (textureBatcher != null) {
            textureBatcher.dispose();
            textureBatcher = null;
        }
        if (vertexBuffer != null) {
            if (mappedVertexByteBuffer != null && vertexBuffer.allocationHandle != VK_NULL_HANDLE && vmaAllocator != VK_NULL_HANDLE) {
                try {
                    vmaUnmapMemory(vmaAllocator, vertexBuffer.allocationHandle);
                } catch (Exception e) {
                    Gdx.app.error(TAG, "Error unmapping vertex buffer", e);
                }
            }
            vertexBuffer.dispose();
            vertexBuffer = null;
            mappedVertexByteBuffer = null;
            vertices = null;
        }
        if (indexBuffer != null) {
            indexBuffer.dispose();
            indexBuffer = null;
        }
        if (projMatrixUbo != null) {
            projMatrixUbo.dispose();
            projMatrixUbo = null;
        }
        batchPipelineLayout = VK_NULL_HANDLE;
        currentPipeline = VK_NULL_HANDLE;
        if (DEBUG) Gdx.app.log(TAG, "VulkanSpriteBatch disposed.");
    }
}