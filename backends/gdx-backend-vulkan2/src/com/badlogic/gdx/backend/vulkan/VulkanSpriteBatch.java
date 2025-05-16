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

/**
 * A Vulkan SpriteBatch implementation that renders sprites by generating unique vertices
 * for each sprite, but uses VulkanTextureBatch for efficient texture management,
 * reducing descriptor set updates compared to older methods. Compatible with Scene2D.
 */
public class VulkanSpriteBatch implements Batch, VulkanFrameResourcePreparer, Disposable {

    private static final String TAG = "VkSpriteBatchRegTexBatch"; // Tag indicating Regular + Texture Batching
    private static final boolean DEBUG = false;

    // --- Vertex Structure ---
    // float x, y (Position)
    // float color (Packed ABGR)
    // float u, v (Texture Coordinates)
    // float textureArrayIndex (Index into the descriptor set's texture array)
    // Total: 2 + 1 + 2 + 1 = 6 floats per vertex
    public static final int POSITION_COMPONENTS = 2;
    public static final int COLOR_COMPONENTS = 1; // Packed
    public static final int TEXCOORD_COMPONENTS = 2;
    public static final int TEXINDEX_COMPONENTS = 1; // Index into texture array
    public static final int COMPONENTS_PER_VERTEX = POSITION_COMPONENTS + COLOR_COMPONENTS + TEXCOORD_COMPONENTS + TEXINDEX_COMPONENTS; // 6
    public static final int BYTES_PER_VERTEX = COMPONENTS_PER_VERTEX * Float.BYTES; // 24

    public static final int VERTICES_PER_SPRITE = 4;
    public static final int INDICES_PER_SPRITE = 6;
    public static final int FLOATS_PER_INSTANCE = 15;
    public static final int BYTES_PER_INSTANCE = FLOATS_PER_INSTANCE * Float.BYTES;
    public static final int BASE_QUAD_VERTEX_COMPONENTS = 4;

    // Vertex attributes matching the structure above and the shader inputs
    public static final VertexAttributes BATCH_VERTEX_ATTRIBUTES = new VertexAttributes(
            new VertexAttribute(VertexAttributes.Usage.Position, POSITION_COMPONENTS, ShaderProgram.POSITION_ATTRIBUTE),         // loc 0: vec2 in_pos
            new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, ShaderProgram.COLOR_ATTRIBUTE),                             // loc 1: float in_packedColor
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, TEXCOORD_COMPONENTS, ShaderProgram.TEXCOORD_ATTRIBUTE + "0"), // loc 2: vec2 in_uv
            new VertexAttribute(VertexAttributes.Usage.Generic, TEXINDEX_COMPONENTS, GL20.GL_FLOAT, false, "in_texArrayIndex") // loc 3: float in_texArrayIndex
    );

    private final VkDevice rawDevice;
    private final long vmaAllocator;
    private final VulkanPipelineManager pipelineManager;
    private final VulkanDescriptorManager descriptorManager;

    private VulkanTextureBatch textureBatcher; // Manages textures and descriptor set

    private VulkanBuffer vertexBuffer; // Large, dynamic buffer for all sprite vertices
    private ByteBuffer mappedVertexByteBuffer;
    private FloatBuffer vertices;      // Float view of the vertex buffer

    private VulkanBuffer indexBuffer; // Large, static index buffer mapping quads

    private VulkanBuffer projMatrixUbo; // UBO for projection matrix
    private final Color batchColor = new Color();

    private boolean drawing = false;
    private final Matrix4 projectionMatrix = new Matrix4();
    private final Matrix4 transformMatrix = new Matrix4(); // Used by Scene2D

    private float colorPacked = Color.WHITE_FLOAT_BITS;

    private int spriteCount = 0; // Number of sprites submitted since last flush
    private int vertexBufferIdx = 0; // Current position (in floats) in the vertex buffer
    private int maxSpritesPerBatch; // Max sprites before vertex buffer *must* be flushed

    public int renderCalls = 0; // Statistic: number of actual vkCmdDrawIndexed

    private long batchPipelineLayout = VK_NULL_HANDLE;
    private long currentPipeline = VK_NULL_HANDLE;

    // Blending state
    private boolean blendingEnabled = true;
    private int blendSrcFunc = GL20.GL_SRC_ALPHA;
    private int blendDstFunc = GL20.GL_ONE_MINUS_SRC_ALPHA;
    private int blendSrcFuncAlpha = GL20.GL_SRC_ALPHA;
    private int blendDstFuncAlpha = GL20.GL_ONE_MINUS_SRC_ALPHA;
    private boolean blendFuncSeparate = false;

    // Temporary buffer for vertex calculation of a single sprite
    private final float[] singleSpriteVertices;

    public VulkanSpriteBatch() {
        this(256);
    }

    /**
     * Constructs a new VulkanSpriteBatch.
     * @param size The maximum number of sprites that can be drawn before flushing the batch.
     */
    public VulkanSpriteBatch(int size) {
        if (DEBUG) Gdx.app.log(TAG, "Initializing with size (max sprites): " + size);

        if (!(Gdx.app instanceof VulkanApplication)) {
            throw new GdxRuntimeException("VulkanSpriteBatch requires Gdx.app to be a VulkanApplication instance.");
        }
        VulkanApplication app = (VulkanApplication) Gdx.app;
        VulkanGraphics gfx = (VulkanGraphics) app.getGraphics();
        if (gfx == null) {
            throw new GdxRuntimeException("VulkanGraphics instance cannot be null!");
        }

        gfx.registerFrameResourcePreparer(this);

        VulkanDevice device = gfx.getVulkanDevice();
        this.vmaAllocator = gfx.getVmaAllocator();
        this.pipelineManager = gfx.getPipelineManager();
        this.descriptorManager = gfx.getDescriptorManager();

        if (device == null || vmaAllocator == VK_NULL_HANDLE || pipelineManager == null || descriptorManager == null) {
            throw new GdxRuntimeException("Failed to retrieve necessary Vulkan managers!");
        }
        this.rawDevice = device.getRawDevice();

        this.maxSpritesPerBatch = size;
        this.singleSpriteVertices = new float[VERTICES_PER_SPRITE * COMPONENTS_PER_VERTEX]; // 4 * 6 = 24 floats

        // --- Create VulkanTextureBatch ---
        int maxTexturesForBatcher = 256; // Software limit, adjust as needed
        maxTexturesForBatcher = Math.min(maxTexturesForBatcher, 1024);
        this.textureBatcher = new VulkanTextureBatch(descriptorManager, maxTexturesForBatcher, gfx);

        // --- Create Vertex Buffer (Dynamic, host-visible) ---
        int maxVertices = maxSpritesPerBatch * VERTICES_PER_SPRITE;
        long vertexBufferSizeBytes = (long)maxVertices * BYTES_PER_VERTEX;
        this.vertexBuffer = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, vertexBufferSizeBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
        );
        PointerBuffer pDataVB = MemoryUtil.memAllocPointer(1);
        try {
            vkCheck(vmaMapMemory(vmaAllocator, vertexBuffer.allocationHandle, pDataVB), "VMA Failed to map vertex buffer");
            this.mappedVertexByteBuffer = MemoryUtil.memByteBuffer(pDataVB.get(0), (int) vertexBufferSizeBytes);
            this.vertices = this.mappedVertexByteBuffer.asFloatBuffer();
            if (DEBUG) Gdx.app.log(TAG, "Vertex buffer created and mapped (Capacity: " + maxVertices + " vertices).");
        } finally {
            MemoryUtil.memFree(pDataVB);
        }

        // --- Create Index Buffer (Static, device-local) ---
        createIndexBuffer(device, maxSpritesPerBatch);

        // --- Create Projection Matrix UBO ---
        long uboSize = 16 * Float.BYTES;
        this.projMatrixUbo = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, uboSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
        );

        // --- Pipeline Layout ---
        this.batchPipelineLayout = pipelineManager.getOrCreatePipelineLayout(textureBatcher.getDescriptorSetLayout());
        if (this.batchPipelineLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to get/create pipeline layout using layout from TextureBatcher.");
        }

        if (DEBUG) Gdx.app.log(TAG, "VulkanSpriteBatch (Non-Instanced, Texture Batched) Initialization complete.");
    }

    /** Creates the large, static index buffer. */
    private void createIndexBuffer(VulkanDevice device, int size) {
        int maxIndices = size * INDICES_PER_SPRITE;
        ShortBuffer indicesCpu = BufferUtils.newShortBuffer(maxIndices);
        ((Buffer)indicesCpu).clear();
        for (int i = 0, v = 0; i < maxIndices; i += INDICES_PER_SPRITE, v += VERTICES_PER_SPRITE) {
            indicesCpu.put((short)v);
            indicesCpu.put((short)(v + 1));
            indicesCpu.put((short)(v + 2));
            indicesCpu.put((short)(v + 2));
            indicesCpu.put((short)(v + 3));
            indicesCpu.put((short)v);
        }
        ((Buffer)indicesCpu).flip();

        this.indexBuffer = VulkanResourceUtil.createDeviceLocalBuffer(
                device, vmaAllocator, indicesCpu, VK_BUFFER_USAGE_INDEX_BUFFER_BIT
        );
        if (DEBUG) Gdx.app.log(TAG, "Index buffer created (Size: " + maxIndices + " indices).");
    }

    private void updateProjectionMatrixUBO() {
        if (projMatrixUbo == null || projMatrixUbo.bufferHandle == VK_NULL_HANDLE || vmaAllocator == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Cannot update projection UBO: UBO or allocator is null.");
            return;
        }
        VulkanResourceUtil.updateBuffer(vmaAllocator, projMatrixUbo, this.projectionMatrix.val, 0, this.projectionMatrix.val.length * Float.BYTES);
    }

    @Override
    public void begin() {
        if (drawing) throw new IllegalStateException("Batch.end must be called before begin.");
        drawing = true;
        renderCalls = 0;
        spriteCount = 0;
        vertexBufferIdx = 0;
        currentPipeline = VK_NULL_HANDLE;
        textureBatcher.resetAndPrepareForFrame();
        updateProjectionMatrixUBO();
    }

    @Override
    public void end() {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before end.");
        if (spriteCount > 0) {
            flush();
        }
        drawing = false;
    }

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

    // --- Draw Methods ---
    // All draw methods calculate sprite vertices and call drawInternal

    @Override
    public void draw(Texture texture, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");

        VulkanTexture tex = (VulkanTexture) texture;
        if (tex == null) {
            tex = textureBatcher.getDefaultTexture();
            if (tex == null) throw new GdxRuntimeException("Null texture passed and no default texture available.");
        }

        int textureDeviceIndex = textureBatcher.addTexture(tex);
        float floatTexIndex = (float) textureDeviceIndex;

        // Check if buffer is full BEFORE generating vertices
        if (spriteCount >= maxSpritesPerBatch) {
            flush();
        }

        float[] verts = this.singleSpriteVertices;
        int i = 0;

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
            x1 = p1x; y1 = p1y;
            x2 = p2x; y2 = p2y;
            x3 = p3x; y3 = p3y;
            x4 = p4x; y4 = p4y;
        }

        x1 += worldOriginX; y1 += worldOriginY;
        x2 += worldOriginX; y2 += worldOriginY;
        x3 += worldOriginX; y3 += worldOriginY;
        x4 += worldOriginX; y4 += worldOriginY;

        if (flipX) { float tmp = u; u = u2; u2 = tmp; }
        if (flipY) { float tmp = v; v = v2; v2 = tmp; }

        // Top Left Vertex (Vert 0)
        verts[i++] = x1; verts[i++] = y1; verts[i++] = packedColor; verts[i++] = u; verts[i++] = v; verts[i++] = floatTexIndex;
        // Bottom Left Vertex (Vert 1)
        verts[i++] = x2; verts[i++] = y2; verts[i++] = packedColor; verts[i++] = u; verts[i++] = v2; verts[i++] = floatTexIndex;
        // Bottom Right Vertex (Vert 2)
        verts[i++] = x3; verts[i++] = y3; verts[i++] = packedColor; verts[i++] = u2; verts[i++] = v2; verts[i++] = floatTexIndex;
        // Top Right Vertex (Vert 3)
        verts[i++] = x4; verts[i++] = y4; verts[i++] = packedColor; verts[i++] = u2; verts[i++] = v; verts[i++] = floatTexIndex;

        drawInternal(verts, 0, i); // i will be 24
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {
        draw(texture, x, y, 0f, 0f, width, height, 1f, 1f, 0f, srcX, srcY, srcWidth, srcHeight, flipX, flipY);
    }

    @Override
    public void draw(Texture texture, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {
        draw(texture, x, y, (float)srcWidth, (float)srcHeight, srcX, srcY, srcWidth, srcHeight, false, false);
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, float u, float v, float u2, float v2) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");

        VulkanTexture tex = (VulkanTexture) texture;
        if (tex == null) {
            tex = textureBatcher.getDefaultTexture();
            if (tex == null) throw new GdxRuntimeException("Null texture passed and no default texture available.");
        }
        int textureDeviceIndex = textureBatcher.addTexture(tex);
        float floatTexIndex = (float) textureDeviceIndex;

        if (spriteCount >= maxSpritesPerBatch) flush();

        float[] verts = this.singleSpriteVertices;
        int i = 0;
        final float packedColor = this.colorPacked;

        verts[i++] = x;         verts[i++] = y;         verts[i++] = packedColor; verts[i++] = u;  verts[i++] = v;  verts[i++] = floatTexIndex;
        verts[i++] = x;         verts[i++] = y + height; verts[i++] = packedColor; verts[i++] = u;  verts[i++] = v2; verts[i++] = floatTexIndex;
        verts[i++] = x + width; verts[i++] = y + height; verts[i++] = packedColor; verts[i++] = u2; verts[i++] = v2; verts[i++] = floatTexIndex;
        verts[i++] = x + width; verts[i++] = y;         verts[i++] = packedColor; verts[i++] = u2; verts[i++] = v;  verts[i++] = floatTexIndex;

        drawInternal(verts, 0, i);
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
    public void draw(Texture texture, float[] spriteVertices, int offset, int count) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");

        // Original vertex format assumed by BitmapFontCache: x, y, color, u, v (5 floats)
        final int INPUT_COMPONENTS = 5;
        if (count % INPUT_COMPONENTS != 0) {
            throw new GdxRuntimeException("Incoming spriteVertices count (" + count + ") must be a multiple of the original input components (" + INPUT_COMPONENTS + ")");
        }
        int numInputVertices = count / INPUT_COMPONENTS;
        if (numInputVertices % VERTICES_PER_SPRITE != 0) {
            throw new GdxRuntimeException("Incoming spriteVertices count (" + count + ") must represent whole sprites (multiple of " + VERTICES_PER_SPRITE + " vertices)");
        }
        int numSprites = numInputVertices / VERTICES_PER_SPRITE;

        VulkanTexture tex = (VulkanTexture) texture;
        if (tex == null) {
            tex = textureBatcher.getDefaultTexture();
            if (tex == null) throw new GdxRuntimeException("Null texture passed and no default texture available.");
        }
        int textureDeviceIndex = textureBatcher.addTexture(tex);
        float floatTexIndex = (float) textureDeviceIndex;

        int verticesToCopy = numSprites * VERTICES_PER_SPRITE; // 4 vertices per sprite
        int floatsToCopy = verticesToCopy * COMPONENTS_PER_VERTEX; // 6 floats per vertex

        // Check if buffer needs flushing BEFORE copying data
        if (vertexBufferIdx + floatsToCopy > vertices.capacity()) {
            flush();
        }

        // Copy and interleave texture index
        int inputIdx = offset;
        vertices.position(vertexBufferIdx); // Set position in the main buffer
        for (int s = 0; s < numSprites; s++) {
            for (int v = 0; v < VERTICES_PER_SPRITE; v++) {
                vertices.put(spriteVertices[inputIdx++]); // x
                vertices.put(spriteVertices[inputIdx++]); // y
                vertices.put(spriteVertices[inputIdx++]); // color
                vertices.put(spriteVertices[inputIdx++]); // u
                vertices.put(spriteVertices[inputIdx++]); // v
                vertices.put(floatTexIndex);             // texIndex
            }
        }
        vertexBufferIdx += floatsToCopy;
        spriteCount += numSprites;
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
        draw(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation,
                region.getRegionX(), region.getRegionY(), region.getRegionWidth(), region.getRegionHeight(),
                false, false); // srcX/Y/W/H are from region, flips default to false
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation, boolean clockwise) {
        // Ignore clockwise for now, same as non-clockwise version
        draw(region, x, y, originX, originY, width, height, scaleX, scaleY, rotation);
    }

    @Override
    public void draw(TextureRegion region, float width, float height, Affine2 transform) {
        if (region == null) return;
        // Decompose Affine2 into properties needed by the complex draw call
        float x = transform.m02;
        float y = transform.m12;
        float scaleX = (float)Math.sqrt(transform.m00 * transform.m00 + transform.m10 * transform.m10);
        float scaleY = (float)Math.sqrt(transform.m01 * transform.m01 + transform.m11 * transform.m11);
        float rotation = MathUtils.atan2(transform.m10, transform.m00) * MathUtils.radiansToDegrees;

        // Use the complex draw call, assuming origin is (0,0) for Affine2 application
        draw(region.getTexture(), x, y, 0f, 0f, width, height, scaleX, scaleY, rotation,
                region.getRegionX(), region.getRegionY(), region.getRegionWidth(), region.getRegionHeight(),
                false, false);
    }

    /** Internal method to copy pre-calculated sprite vertices into the main buffer. */
    private void drawInternal(float[] spriteVertices, int offset, int count) {
        // Assumes count is always VERTICES_PER_SPRITE * COMPONENTS_PER_VERTEX (24)
        // Assumes buffer space check was done before calling

        try {
            vertices.position(vertexBufferIdx);
            vertices.put(spriteVertices, offset, count);
            vertexBufferIdx += count;
            spriteCount++; // Increment sprite count for this single sprite
        } catch (BufferOverflowException | IndexOutOfBoundsException | IllegalArgumentException e) {
            Gdx.app.error(TAG, "Buffer Exception during vertices.put. vertexBufferIdx=" + vertexBufferIdx + ", offset=" + offset + ", count=" + count + ", vertices.capacity=" + vertices.capacity(), e);
            throw new GdxRuntimeException("Error copying vertex data", e);
        }
    }

    @Override
    public void dispose() {
        if (DEBUG) Gdx.app.log(TAG, "Disposing VulkanSpriteBatch (Non-Instanced)...");
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
                try { vmaUnmapMemory(vmaAllocator, vertexBuffer.allocationHandle); } catch (Exception e) { Gdx.app.error(TAG, "Error unmapping vertex buffer", e); }
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
        if (DEBUG) Gdx.app.log(TAG, "VulkanSpriteBatch (Non-Instanced) disposed.");
    }

    @Override
    public void flush() {
        if (spriteCount == 0) return;

        VulkanGraphics gfx = null;
        if (Gdx.app instanceof VulkanApplication) {
            gfx = (VulkanGraphics) Gdx.app.getGraphics();
        }
        if (gfx == null) {
            Gdx.app.error(TAG, "Flush error: VulkanGraphics context is null!");
            spriteCount = 0; vertexBufferIdx = 0; return;
        }

        VkCommandBuffer currentCommandBuffer = gfx.getCurrentCommandBuffer();
        long currentRenderPassHandle = gfx.getCurrentRenderPassHandle();

        if (currentCommandBuffer == null || currentRenderPassHandle == VK_NULL_HANDLE || batchPipelineLayout == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Flush error: Invalid Vulkan context (CommandBuffer, RenderPass, or PipelineLayout is null).");
            spriteCount = 0; vertexBufferIdx = 0; return;
        }

        // Update and bind descriptor set //(UBO + Textures)
        textureBatcher.buildAndBind(currentCommandBuffer, batchPipelineLayout, projMatrixUbo);

        // Flush vertex buffer memory
        long bytesToFlush = (long)vertexBufferIdx * Float.BYTES;
        if (bytesToFlush > 0 && vertexBuffer.allocationHandle != VK_NULL_HANDLE && vmaAllocator != VK_NULL_HANDLE) {
            Vma.vmaFlushAllocation(vmaAllocator, vertexBuffer.allocationHandle, 0, bytesToFlush);
        }

        // Bind pipeline
        long pipelineToUse = pipelineManager.getOrCreateSpriteBatchPipeline( // Use the non-instanced pipeline getter
                batchPipelineLayout,
                currentRenderPassHandle,
                blendingEnabled,
                blendSrcFunc, blendDstFunc,
                blendSrcFuncAlpha, blendDstFuncAlpha,
                blendFuncSeparate
        );
        if (pipelineToUse == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Failed to get or create non-instanced sprite batch pipeline.");
            spriteCount = 0; vertexBufferIdx = 0; return;
        }
        if (pipelineToUse != currentPipeline) {
            vkCmdBindPipeline(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineToUse);
            currentPipeline = pipelineToUse;
        }

        // Bind vertex and index buffers
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pVertexBuffers = stack.longs(vertexBuffer.bufferHandle);
            LongBuffer pOffsets = stack.longs(0L);
            vkCmdBindVertexBuffers(currentCommandBuffer, 0, pVertexBuffers, pOffsets); // Binding 0
            vkCmdBindIndexBuffer(currentCommandBuffer, indexBuffer.bufferHandle, 0, VK_INDEX_TYPE_UINT16);
        }

        // Draw
        int indexCountToDraw = spriteCount * INDICES_PER_SPRITE;
        vkCmdDrawIndexed(currentCommandBuffer, indexCountToDraw, 1, 0, 0, 0);
        renderCalls++;

        spriteCount = 0;
        vertexBufferIdx = 0;
    }


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
        if (this.blendSrcFunc != srcFuncColor || this.blendDstFunc != dstFuncColor ||
                this.blendSrcFuncAlpha != srcFuncAlpha || this.blendDstFuncAlpha != dstFuncAlpha) {
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
    public int getBlendSrcFunc() { return blendSrcFunc; }
    @Override
    public int getBlendDstFunc() { return blendDstFunc; }
    @Override
    public int getBlendSrcFuncAlpha() { return blendFuncSeparate ? blendSrcFuncAlpha : blendSrcFunc; }
    @Override
    public int getBlendDstFuncAlpha() { return blendFuncSeparate ? blendDstFuncAlpha : blendDstFunc; }

    @Override
    public Matrix4 getProjectionMatrix() { return projectionMatrix; }
    @Override
    public Matrix4 getTransformMatrix() { return transformMatrix; }

    @Override
    public void setProjectionMatrix(Matrix4 projection) {
        if (projection == null) throw new IllegalArgumentException("Projection matrix cannot be null.");
        if (!projectionMatrix.equals(projection)) {
            if (drawing && spriteCount > 0) flush();
            this.projectionMatrix.set(projection);
            if (!drawing) updateProjectionMatrixUBO();
        }
    }

    @Override
    public void setTransformMatrix(Matrix4 transform) {
        if (transform == null) throw new IllegalArgumentException("Transform matrix cannot be null.");
        if (!transformMatrix.equals(transform)) {
            // Apply transform matrix immediately for non-instanced batch
            if (drawing && spriteCount > 0) flush(); // Flush before changing matrix
            this.transformMatrix.set(transform);
        }
    }

    @Override
    public void setShader(ShaderProgram shader) {
        // This batch uses an internal shader defined by the pipeline manager
        Gdx.app.error(TAG, "setShader() is not supported. Pipeline/shader is managed internally.");
    }

    @Override
    public ShaderProgram getShader() { return null; }
    @Override
    public boolean isBlendingEnabled() { return blendingEnabled; }
    @Override
    public boolean isDrawing() { return drawing; }

    @Override
    public void prepareResourcesForFrame(int frameIndex) {
        // UBO update is handled by begin() or setProjectionMatrix()
        // Texture descriptor set updates are handled by textureBatcher in flush()
        updateProjectionMatrixUBO(); // Ensure UBO is up-to-date if matrix changed outside begin/end
    }
}
