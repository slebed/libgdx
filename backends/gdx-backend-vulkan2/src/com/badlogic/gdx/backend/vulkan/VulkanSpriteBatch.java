package com.badlogic.gdx.backend.vulkan; // Example package

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram; // Needed for constants
import com.badlogic.gdx.math.Affine2;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.NumberUtils;
import com.badlogic.gdx.utils.ObjectMap;

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
import java.util.Arrays;
import java.util.List;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanSpriteBatch implements Batch, VulkanFrameResourcePreparer, Disposable {

    public static final int MAX_BATCH_TEXTURES = 16;
    private final String TAG = "VulkanSpriteBatch";

    private final VkDevice rawDevice;
    private final long vmaAllocator;
    private final VulkanPipelineManager pipelineManager;
    private final VulkanDescriptorManager descriptorManager;
    private final Color batchColor = new Color();

    private boolean pipelineAndSetBoundThisBatch = false;

    private VulkanBuffer vertexBuffer;
    private VulkanBuffer indexBuffer;
    private VulkanBuffer projMatrixUbo;

    private ByteBuffer mappedVertexByteBuffer;
    private FloatBuffer vertices;

    private final int vertexFloats; // Total float components per vertex (now 6)
    private final int vertexSizeInBytes; // Size of one vertex in bytes

    private final int frameCount; // Store MAX_FRAMES_IN_FLIGHT
    private final long[] batchDescriptorSets; // Array for descriptor sets per frame

    private long batchPipelineLayout = VK_NULL_HANDLE;
    private long batchDescriptorLayout = VK_NULL_HANDLE;

    private boolean drawing = false; // Is begin() called?
    private final Matrix4 projectionMatrix = new Matrix4();
    private final Matrix4 transformMatrix = new Matrix4(); // Usually identity for SpriteBatch

    private VulkanTexture defaultTexture;
    private VulkanTexture lastTexture = null;
    private float invTexWidth = 0, invTexHeight = 0;
    private float colorPacked = Color.WHITE_FLOAT_BITS; // Packed float color

    private int spriteCount = 0;
    private int vertexBufferIdx = 0;

    public static final int POSITION_COMPONENTS = 2;
    public static final int COLOR_COMPONENTS = 1; // Packed into one float
    public static final int TEXCOORD_COMPONENTS = 2;
    public static final int TEXINDEX_COMPONENTS = 1; // NEW: One float for the index

    public static final int COMPONENTS_PER_VERTEX = POSITION_COMPONENTS + COLOR_COMPONENTS + TEXCOORD_COMPONENTS + TEXINDEX_COMPONENTS; // 2 + 1 + 2 + 1 = 6

    public static final int VERTICES_PER_SPRITE = 4;
    public static final int INDICES_PER_SPRITE = 6;
    public int renderCalls;

    public static final VertexAttributes BATCH_ATTRIBUTES = new VertexAttributes(
            new VertexAttribute(VertexAttributes.Usage.Position, POSITION_COMPONENTS, ShaderProgram.POSITION_ATTRIBUTE), // Location 0
            new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, ShaderProgram.COLOR_ATTRIBUTE), // Location 1 (Still float, but shader unpacks)
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, TEXCOORD_COMPONENTS, ShaderProgram.TEXCOORD_ATTRIBUTE + "0"), // Location 2
            new VertexAttribute(VertexAttributes.Usage.Generic, TEXINDEX_COMPONENTS, GL20.GL_FLOAT, false, "a_texIndex", 0)
    );

    private final VulkanTexture[] currentTextureInSet;

    private boolean blendingEnabled = true; // Default state
    private int blendSrcFunc = GL20.GL_SRC_ALPHA; // Default libgdx blend func
    private int blendDstFunc = GL20.GL_ONE_MINUS_SRC_ALPHA; // Default libgdx blend func
    private int blendSrcFuncAlpha = GL20.GL_SRC_ALPHA; // Default separate alpha
    private int blendDstFuncAlpha = GL20.GL_ONE_MINUS_SRC_ALPHA; // Default separate alpha
    private boolean blendFuncSeparate = false; // Flag to track if separate func was used

    private final VulkanTexture[] currentBatchTextures; // Textures active in the current batch segment
    private final ObjectMap<VulkanTexture, Integer> textureToIndex; // Map active textures to their index [0..MAX_BATCH_TEXTURES-1]
    private int nextTextureSlot; // Next available slot in currentBatchTextures array

    private final float[] affineVertices; // Temporary buffer per sprite, needs updated size

    public VulkanSpriteBatch() {
        this(1024); // Default batch size
    }

    public VulkanSpriteBatch(int size) {
        Gdx.app.log(TAG, "Initializing with size: " + size + ", MAX_BATCH_TEXTURES=" + MAX_BATCH_TEXTURES);

        if (!(Gdx.graphics instanceof VulkanGraphics)) {
            throw new GdxRuntimeException("VulkanSpriteBatch requires the VulkanGraphics backend!");
        }
        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;

        gfx.registerFrameResourcePreparer(this); // Register self for prepareResourcesForFrame
        Gdx.app.log(TAG, "Registered for frame preparation.");

        VulkanDevice device = gfx.getVulkanDevice();
        this.vmaAllocator = gfx.getVmaAllocator();
        this.pipelineManager = gfx.getPipelineManager();
        this.descriptorManager = gfx.getDescriptorManager();

        if (device == null || vmaAllocator == VK_NULL_HANDLE || pipelineManager == null || descriptorManager == null) {
            throw new GdxRuntimeException("Failed to retrieve necessary Vulkan managers!");
        }
        this.rawDevice = device.getRawDevice();

        this.frameCount = gfx.config.MAX_FRAMES_IN_FLIGHT;
        if (this.frameCount <= 0) {
            throw new GdxRuntimeException("MAX_FRAMES_IN_FLIGHT must be positive, was: " + this.frameCount);
        }
        Gdx.app.log(TAG, "Using Descriptor set buffer count based on MAX_FRAMES_IN_FLIGHT: " + this.frameCount);
        this.batchDescriptorSets = new long[this.frameCount];

        this.currentBatchTextures = new VulkanTexture[MAX_BATCH_TEXTURES];
        this.textureToIndex = new ObjectMap<>(MAX_BATCH_TEXTURES);
        this.nextTextureSlot = 0;

        this.vertexFloats = COMPONENTS_PER_VERTEX; // Now 6
        this.vertexSizeInBytes = BATCH_ATTRIBUTES.vertexSize;
        this.affineVertices = new float[VERTICES_PER_SPRITE * COMPONENTS_PER_VERTEX]; // Temp buffer size updated
        Gdx.app.log(TAG, "Vertex size: " + vertexSizeInBytes + " bytes (" + vertexFloats + " float components)");

        int maxVertices = size * VERTICES_PER_SPRITE;
        long vertexBufferSizeBytes = (long) maxVertices * vertexSizeInBytes; // Use bytes

        this.vertexBuffer = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, vertexBufferSizeBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
        );
        PointerBuffer pDataVB = MemoryUtil.memAllocPointer(1);
        try {
            vkCheck(vmaMapMemory(vmaAllocator, vertexBuffer.allocationHandle, pDataVB), "VMA Failed to map vertex buffer for batch");
            this.mappedVertexByteBuffer = MemoryUtil.memByteBuffer(pDataVB.get(0), (int) vertexBufferSizeBytes);
            this.vertices = this.mappedVertexByteBuffer.asFloatBuffer(); // Still use FloatBuffer
            Gdx.app.log(TAG, "Vertex buffer created and persistently mapped (Capacity: " + vertices.capacity() + " floats).");
        } finally {
            MemoryUtil.memFree(pDataVB);
        }
        Gdx.app.log(TAG, "Created vertexBuffer handle: " + this.vertexBuffer.bufferHandle + ", Allocation: " + this.vertexBuffer.allocationHandle);

        ShortBuffer indicesCpu = BufferUtils.newShortBuffer(size * INDICES_PER_SPRITE);
        ((Buffer) indicesCpu).clear();
        for (int i = 0, v = 0; i < size * INDICES_PER_SPRITE; i += INDICES_PER_SPRITE, v += VERTICES_PER_SPRITE) { // Use constant
            indicesCpu.put((short) v);
            indicesCpu.put((short) (v + 1));
            indicesCpu.put((short) (v + 2));
            indicesCpu.put((short) (v + 2));
            indicesCpu.put((short) (v + 3));
            indicesCpu.put((short) v);
        }
        ((Buffer) indicesCpu).flip();
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
                    vmaAllocator, indexBufferSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT, VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0);

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
        Gdx.app.log(TAG, "Created indexBuffer handle: " + this.indexBuffer.bufferHandle + ", Allocation: " + this.indexBuffer.allocationHandle);

        long uboSize = 16 * Float.BYTES; // Size of mat4
        this.projMatrixUbo = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, uboSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
        );
        Gdx.app.log(TAG, "Projection matrix UBO created.");
        Gdx.app.log(TAG, "Created projMatrixUbo handle: " + this.projMatrixUbo.bufferHandle + ", Allocation: " + this.projMatrixUbo.allocationHandle);

        Gdx.app.log(TAG, "Getting SpriteBatch descriptor set layout (indexing) from manager...");
        this.batchDescriptorLayout = descriptorManager.getOrCreateSpriteBatchLayout(); // Should return the indexed layout now
        if (this.batchDescriptorLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to get Indexed SpriteBatch descriptor set layout from manager.");
        }
        Gdx.app.log(TAG, "Obtained batch descriptor set layout: " + this.batchDescriptorLayout);

        Gdx.app.log(TAG, "Allocating " + this.frameCount + " descriptor sets using manager...");
        defaultTexture = findDefaultWhiteTexture(); // Create default texture

        currentTextureInSet = new VulkanTexture[this.frameCount];

        for (int i = 0; i < this.frameCount; i++) {
            long setHandle = descriptorManager.allocateSet(this.batchDescriptorLayout);
            if (setHandle == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Failed to allocate descriptor set [" + i + "] via manager.");
            }
            this.batchDescriptorSets[i] = setHandle;
            Gdx.app.log(TAG, "Allocated batch descriptor set [" + i + "]: " + setHandle);

            if (this.projMatrixUbo == null || this.projMatrixUbo.bufferHandle == VK_NULL_HANDLE) {
                throw new IllegalStateException("projMatrixUbo invalid before initial descriptor update for set [" + i + "]!");
            }
            Gdx.app.log(TAG, "Performing initial UBO update for set [" + i + "]: " + setHandle);
            VulkanDescriptorManager.updateUniformBuffer(rawDevice, setHandle, 0, this.projMatrixUbo.bufferHandle, 0, this.projMatrixUbo.size
            );

            Gdx.app.log(TAG, "Performing initial Sampler updates [0.." + (MAX_BATCH_TEXTURES - 1) + "] for set [" + i + "]: " + setHandle);
            if (defaultTexture == null || defaultTexture.getImageViewHandle() == VK_NULL_HANDLE || defaultTexture.getSamplerHandle() == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Default texture is invalid during descriptor set initialization!");
            }
            for (int slot = 0; slot < MAX_BATCH_TEXTURES; slot++) {
                VulkanDescriptorManager.updateCombinedImageSampler(rawDevice, setHandle, 1, slot, defaultTexture);
            }

            this.currentTextureInSet[i] = defaultTexture;
        }
        Gdx.app.log(TAG, "Batch descriptor sets allocated and initial updates applied.");

        Gdx.app.log(TAG, "Getting pipeline layout from PipelineManager...");
        this.batchPipelineLayout = pipelineManager.getOrCreatePipelineLayout(this.batchDescriptorLayout); // Uses the indexed layout handle
        if (this.batchPipelineLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to get/create pipeline layout for batch from PipelineManager.");
        }
        Gdx.app.log(TAG, "Obtained pipeline layout: " + this.batchPipelineLayout);

        Gdx.app.log(TAG, "VulkanSpriteBatch Initialization complete.");
    }

    private VulkanTexture findDefaultWhiteTexture() {
        Pixmap white = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        white.setColor(Color.WHITE);
        white.fill();
        VulkanTexture tex = new VulkanTexture(white);
        white.dispose();
        return tex;
    }

    private void updateProjectionMatrixUBO() {
        if (projMatrixUbo == null || vmaAllocator == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Cannot update UBO - buffer or allocator is null.");
            return;
        }

        PointerBuffer pData = MemoryUtil.memAllocPointer(1);
        try {

            vkCheck(vmaMapMemory(vmaAllocator, projMatrixUbo.allocationHandle, pData), "VMA map failed for projection update");
            ByteBuffer uboByteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int) projMatrixUbo.size);
            FloatBuffer uboFloatBuffer = uboByteBuffer.asFloatBuffer();

            uboFloatBuffer.put(this.projectionMatrix.val);

            Vma.vmaFlushAllocation(vmaAllocator, projMatrixUbo.allocationHandle, 0, projMatrixUbo.size);

            vmaUnmapMemory(vmaAllocator, projMatrixUbo.allocationHandle);

        } catch (Exception e) {

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

    /*private void updateTextureDescriptor(long descriptorSet, VulkanTexture texture) {
        if (texture == null || rawDevice == null) {
            Gdx.app.error(TAG, "Cannot update texture descriptor - texture or rawDevice is null");
            return;
        }
        VulkanDescriptorManager.updateCombinedImageSampler(rawDevice, descriptorSet, 1, texture);
    }

    private void createBatchPipeline() {
        Gdx.app.log(TAG, "Requesting sprite batch pipeline...");

        //Gdx.app.log(TAG, "TODO: Pipeline creation should be moved to VulkanPipelineManager!");
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

    public VulkanTexture getLastTexture() {
        return lastTexture;
    }*/

    @Override
    public void begin() {
        if (drawing) throw new IllegalStateException("Batch.end must be called before begin.");

        drawing = true;
        renderCalls = 0; // Keep this
        lastTexture = null; // Reset last texture submitted

        spriteCount = 0;
        vertexBufferIdx = 0;

        nextTextureSlot = 0;
        textureToIndex.clear();
        Arrays.fill(currentBatchTextures, null); // Clear texture slots

        pipelineAndSetBoundThisBatch = false; // Reset pipeline binding state

        updateProjectionMatrixUBO(); // Update UBO at start
    }

    @Override
    public void end() {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before end.");

        if (spriteCount > 0 || vertexBufferIdx > 0) { // Check vertexBufferIdx too
            flush();
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
        this.colorPacked = packedColor;
    }

    @Override
    public float getPackedColor() {
        return colorPacked;
    }

    @Override
    public void draw(Texture texture, float x, float y,
                     float originX, float originY,
                     float width, float height,
                     float scaleX, float scaleY,
                     float rotation,
                     int srcX, int srcY,
                     int srcWidth, int srcHeight,
                     boolean flipX, boolean flipY) {

        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");

        VulkanTexture vkTexture = (VulkanTexture) texture; // Can be null

        int texIndex = getTextureIndex(vkTexture);
        if (texIndex == -1) { // -1 indicates flush was triggered
            texIndex = getTextureIndex(vkTexture); // Retry
            if (texIndex == -1) throw new GdxRuntimeException("Failed to obtain texture index slot even after flush!");
        }
        float floatTexIndex = (float) texIndex;

        float[] verts = this.affineVertices;
        int i = 0;

        VulkanTexture textureInSlot = currentBatchTextures[texIndex];
        if (textureInSlot == null) throw new GdxRuntimeException("No valid texture found in slot " + texIndex);

        // Calculate invTexWidth/Height only if texture changed (or first draw)
        // This check might be simplified or removed if performance isn't critical here
        if (textureInSlot != lastTexture || invTexWidth == 0) { // Check against lastTexture used for *this specific draw call logic*
            if (textureInSlot.getWidth() == 0 || textureInSlot.getHeight() == 0) {
                invTexWidth = 0;
                invTexHeight = 0;
                Gdx.app.error(TAG, "Texture dimensions are zero when calculating UVs for texture in slot " + texIndex);
            } else {
                invTexWidth = 1.0f / textureInSlot.getWidth();
                invTexHeight = 1.0f / textureInSlot.getHeight();
            }
            // Note: We are NOT updating the global `this.lastTexture` here, only the cached inv sizes
        }

        if (invTexWidth == 0 || invTexHeight == 0) {
            Gdx.app.error(TAG, "Cannot draw, invTexWidth or invTexHeight is zero.");
            return;
        }

        float u = srcX * invTexWidth;
        float v = srcY * invTexHeight;
        float u2 = (srcX + srcWidth) * invTexWidth;
        float v2 = (srcY + srcHeight) * invTexHeight;

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

        // Populate vertex array (X, Y, Color, U, V, TexIndex)
        verts[i++] = x1;
        verts[i++] = y1;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v;
        verts[i++] = floatTexIndex;
        verts[i++] = x2;
        verts[i++] = y2;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v2;
        verts[i++] = floatTexIndex;
        verts[i++] = x3;
        verts[i++] = y3;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v2;
        verts[i++] = floatTexIndex;
        verts[i++] = x4;
        verts[i++] = y4;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v;
        verts[i++] = floatTexIndex;

        drawInternal(verts, 0, i);
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {
        draw(texture, x, y, 0f, 0f, width, height, 1f, 1f, 0f, srcX, srcY, srcWidth, srcHeight, flipX, flipY);
    }

    @Override
    public void draw(Texture texture, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {
        draw(texture, x, y, 0f, 0f, srcWidth, srcHeight, 1f, 1f, 0f, srcX, srcY, srcWidth, srcHeight, false, false);
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, float u, float v, float u2, float v2) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");

        VulkanTexture vkTexture = (VulkanTexture) texture;

        int texIndex = getTextureIndex(vkTexture);
        if (texIndex == -1) {
            texIndex = getTextureIndex(vkTexture);
        }
        if (texIndex == -1) throw new GdxRuntimeException("Failed to obtain texture index slot even after flush!");
        float floatTexIndex = (float) texIndex;

        float[] verts = this.affineVertices;
        int i = 0;

        // Populate vertex array (X, Y, Color, U, V, TexIndex)
        verts[i++] = x;
        verts[i++] = y;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v2;
        verts[i++] = floatTexIndex;
        verts[i++] = x;
        verts[i++] = y + height;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v;
        verts[i++] = floatTexIndex;
        verts[i++] = x + width;
        verts[i++] = y + height;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v;
        verts[i++] = floatTexIndex;
        verts[i++] = x + width;
        verts[i++] = y;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v2;
        verts[i++] = floatTexIndex;

        drawInternal(verts, 0, i);
    }

    @Override
    public void draw(Texture texture, float x, float y) {
        draw(texture, x, y, texture.getWidth(), texture.getHeight(), 0f, 0f, 1f, 1f);
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height) {
        // Default UVs (0,0) to (1,1) -> maps to (0,1) to (1,0) in typical GL unless flipped
        // LibGDX convention often uses (0,0) top-left, (1,1) bottom-right for UVs
        // Let's use the standard LibGDX convention u=0,v=0 top-left, u2=1,v2=1 bottom-right
        draw(texture, x, y, width, height, 0f, 0f, 1f, 1f);
    }

    @Override
    public void draw(Texture texture, float[] spriteVertices, int offset, int count) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");

        final int INPUT_COMPONENTS = VulkanSpriteBatch.POSITION_COMPONENTS + VulkanSpriteBatch.COLOR_COMPONENTS + VulkanSpriteBatch.TEXCOORD_COMPONENTS;

        if (count % INPUT_COMPONENTS != 0) { // Check if count is multiple of 5
            throw new GdxRuntimeException("Incoming spriteVertices count (" + count + ") must be a multiple of the expected input components (" + INPUT_COMPONENTS + ")");
        }

        VulkanTexture vkTexture = (VulkanTexture) texture;

        int texIndex = getTextureIndex(vkTexture);
        if (texIndex == -1) {
            texIndex = getTextureIndex(vkTexture);
        }
        if (texIndex == -1) throw new GdxRuntimeException("Failed to obtain texture index slot even after flush!");
        float floatTexIndex = (float) texIndex;

        // We need to copy the input vertices and insert the texture index
        int numVertices = count / (COMPONENTS_PER_VERTEX - 1); // Original components per vertex
        if (numVertices * (COMPONENTS_PER_VERTEX - 1) != count) {
            throw new GdxRuntimeException("spriteVertices count does not match expected components per vertex for input.");
        }
        int outputCount = numVertices * COMPONENTS_PER_VERTEX; // Target count including index

        // Use affineVertices as a temporary buffer if large enough, otherwise allocate
        float[] vertsWithIndex;
        if (outputCount <= affineVertices.length) {
            vertsWithIndex = affineVertices;
        } else {
            // This case should be rare for single sprite draws, but handle it
            vertsWithIndex = new float[outputCount];
            Gdx.app.debug(TAG, "Allocating temporary vertex buffer for draw(spriteVertices) size: " + outputCount);
        }

        int inputIdx = offset;
        int outputIdx = 0;
        for (int v = 0; v < numVertices; v++) {
            // Copy Position (2 floats)
            vertsWithIndex[outputIdx++] = spriteVertices[inputIdx++];
            vertsWithIndex[outputIdx++] = spriteVertices[inputIdx++];
            // Copy Color (1 float)
            vertsWithIndex[outputIdx++] = spriteVertices[inputIdx++];
            // Copy TexCoords (2 floats)
            vertsWithIndex[outputIdx++] = spriteVertices[inputIdx++];
            vertsWithIndex[outputIdx++] = spriteVertices[inputIdx++];
            // Add TexIndex (1 float)
            vertsWithIndex[outputIdx++] = floatTexIndex;
        }

        drawInternal(vertsWithIndex, 0, outputCount);
    }

    @Override
    public void draw(TextureRegion region, float x, float y) {
        draw(region, x, y, region.getRegionWidth(), region.getRegionHeight());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float width, float height) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");
        if (!(region.getTexture() instanceof VulkanTexture)) throw new GdxRuntimeException("TextureRegion's texture is not a VulkanTexture!");
        VulkanTexture vkTexture = (VulkanTexture) region.getTexture();

        int texIndex = getTextureIndex(vkTexture);
        if (texIndex == -1) {
            texIndex = getTextureIndex(vkTexture);
        }
        if (texIndex == -1) throw new GdxRuntimeException("Failed to obtain texture index slot even after flush!");
        float floatTexIndex = (float) texIndex;

        float[] verts = this.affineVertices;
        int i = 0;

        float u = region.getU();
        float v = region.getV();
        float u2 = region.getU2();
        float v2 = region.getV2();

        // Populate vertex array (X, Y, Color, U, V, TexIndex)
        verts[i++] = x;
        verts[i++] = y;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v2;
        verts[i++] = floatTexIndex;
        verts[i++] = x;
        verts[i++] = y + height;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v;
        verts[i++] = floatTexIndex;
        verts[i++] = x + width;
        verts[i++] = y + height;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v;
        verts[i++] = floatTexIndex;
        verts[i++] = x + width;
        verts[i++] = y;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v2;
        verts[i++] = floatTexIndex;

        drawInternal(verts, 0, i);
    }

    // Inside VulkanSpriteBatch.java

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");

        Texture texture = region.getTexture(); // Get texture from region
        if (!(texture instanceof VulkanTexture)) {
            throw new GdxRuntimeException("TextureRegion's texture is not a VulkanTexture instance!");
        }
        VulkanTexture vkTexture = (VulkanTexture) texture;

        // --- Get Texture Index ---
        int texIndex = getTextureIndex(vkTexture);
        if (texIndex == -1) { // Handle potential flush and retry
            texIndex = getTextureIndex(vkTexture);
            if (texIndex == -1) throw new GdxRuntimeException("Failed to obtain texture index slot even after flush!");
        }
        float floatTexIndex = (float) texIndex;
        // --- End Texture Index ---

        float[] verts = this.affineVertices; // Use temporary vertex array
        int i = 0;

        // --- Calculate Vertex Positions (Identical to complex Texture draw method) ---
        final float worldOriginX = x + originX;
        final float worldOriginY = y + originY;
        float fx = -originX;
        float fy = -originY;
        float fx2 = width - originX;
        float fy2 = height - originY;

        // Apply scale
        if (scaleX != 1 || scaleY != 1) {
            fx *= scaleX;
            fy *= scaleY;
            fx2 *= scaleX;
            fy2 *= scaleY;
        }

        // Local sprite coordinates relative to origin
        final float p1x = fx;
        final float p1y = fy;
        final float p2x = fx;
        final float p2y = fy2;
        final float p3x = fx2;
        final float p3y = fy2;
        final float p4x = fx2;
        final float p4y = fy;

        float x1, y1, x2, y2, x3, y3, x4, y4;

        // Apply rotation
        if (rotation != 0) {
            final float cos = MathUtils.cosDeg(rotation);
            final float sin = MathUtils.sinDeg(rotation);
            x1 = cos * p1x - sin * p1y;
            y1 = sin * p1x + cos * p1y; // Top left
            x2 = cos * p2x - sin * p2y;
            y2 = sin * p2x + cos * p2y; // Bottom left
            x3 = cos * p3x - sin * p3y;
            y3 = sin * p3x + cos * p3y; // Bottom right
            // Top right vertex is derived from the other three: = V1 + (V3 - V2)
            x4 = x1 + (x3 - x2);
            y4 = y1 + (y3 - y2);               // Top right
        } else { // No rotation, just use scaled local coords
            x1 = p1x;
            y1 = p1y;
            x2 = p2x;
            y2 = p2y;
            x3 = p3x;
            y3 = p3y;
            x4 = p4x;
            y4 = p4y;
        }

        // Add world origin
        x1 += worldOriginX;
        y1 += worldOriginY;
        x2 += worldOriginX;
        y2 += worldOriginY;
        x3 += worldOriginX;
        y3 += worldOriginY;
        x4 += worldOriginX;
        y4 += worldOriginY;
        // --- End Vertex Position Calculation ---

        // --- Get Texture Coordinates from Region ---
        float u = region.getU();
        float v = region.getV();
        float u2 = region.getU2();
        float v2 = region.getV2();
        // --- End UV Calculation ---

        // --- Populate Vertex Array (X, Y, Color, U, V, TexIndex) ---
        // Order matches the calculated positions: BL, TL, TR, BR for the quad indices (0,1,2, 2,3,0) typically
        // But UVs need careful mapping. Let's use the standard LibGDX mapping:
        // x1,y1 (rotated p1: top-left)   -> u, v
        // x2,y2 (rotated p2: bottom-left) -> u, v2
        // x3,y3 (rotated p3: bottom-right)-> u2, v2
        // x4,y4 (rotated p4: top-right)  -> u2, v

        verts[i++] = x1;
        verts[i++] = y1;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v;
        verts[i++] = floatTexIndex; // Top-Left vertex
        verts[i++] = x2;
        verts[i++] = y2;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v2;
        verts[i++] = floatTexIndex; // Bottom-Left vertex
        verts[i++] = x3;
        verts[i++] = y3;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v2;
        verts[i++] = floatTexIndex; // Bottom-Right vertex
        verts[i++] = x4;
        verts[i++] = y4;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v;
        verts[i++] = floatTexIndex; // Top-Right vertex

        // --- Call Internal Draw ---
        drawInternal(verts, 0, i); // Pass the populated vertex data
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation, boolean clockwise) {
        // Clockwise rotation affects vertex order or UVs, but not the indexing logic itself.
        // For now, delegate to non-clockwise version as before.
        Gdx.app.error(TAG, "draw(..., clockwise) not fully implemented with correct vertex/UV order.");
        draw(region, x, y, originX, originY, width, height, scaleX, scaleY, rotation);
    }

    @Override
    public void draw(TextureRegion region, float width, float height, Affine2 transform) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");
        if (!(region.getTexture() instanceof VulkanTexture)) throw new GdxRuntimeException("TextureRegion's texture is not a VulkanTexture!");
        VulkanTexture vkTexture = (VulkanTexture) region.getTexture();

        int texIndex = getTextureIndex(vkTexture);
        if (texIndex == -1) {
            texIndex = getTextureIndex(vkTexture);
        }
        if (texIndex == -1) throw new GdxRuntimeException("Failed to obtain texture index slot even after flush!");
        float floatTexIndex = (float) texIndex;

        float[] verts = this.affineVertices;
        int idx = 0;

        // --- 1. Calculate Vertex Positions (same as before) ---
        final float m00 = transform.m00, m01 = transform.m01, m02 = transform.m02;
        final float m10 = transform.m10, m11 = transform.m11, m12 = transform.m12;
        float x1 = m02, y1 = m12;
        float x2 = m01 * height + m02, y2 = m11 * height + m12;
        float x3 = m00 * width + m01 * height + m02, y3 = m10 * width + m11 * height + m12;
        float x4 = m00 * width + m02, y4 = m10 * width + m12;
        // --- End Position Calculation ---

        // --- 2. Get Texture Coordinates (same as before) ---
        float u = region.getU(), v = region.getV();
        float u2 = region.getU2(), v2 = region.getV2();
        // --- End UV Calculation ---

        // --- 3. Get Packed Color (same as before) ---
        float color = this.colorPacked;

        // --- 4. Populate Vertex Array (X, Y, Color, U, V, TexIndex) ---
        verts[idx++] = x1;
        verts[idx++] = y1;
        verts[idx++] = color;
        verts[idx++] = u;
        verts[idx++] = v;
        verts[idx++] = floatTexIndex;
        verts[idx++] = x2;
        verts[idx++] = y2;
        verts[idx++] = color;
        verts[idx++] = u;
        verts[idx++] = v2;
        verts[idx++] = floatTexIndex;
        verts[idx++] = x3;
        verts[idx++] = y3;
        verts[idx++] = color;
        verts[idx++] = u2;
        verts[idx++] = v2;
        verts[idx++] = floatTexIndex;
        verts[idx++] = x4;
        verts[idx++] = y4;
        verts[idx++] = color;
        verts[idx++] = u2;
        verts[idx++] = v;
        verts[idx++] = floatTexIndex;

        drawInternal(verts, 0, idx);
    }

    /**
     * Copies the specified sprite vertex data into the main vertex buffer.
     * Handles buffer-full conditions by flushing.
     * Assumes the texture index is already baked into the spriteVertices array.
     *
     * @param spriteVertices The vertex data array (X, Y, Color, U, V, TexIndex).
     * @param offset         The starting offset in spriteVertices.
     * @param count          The number of float components to copy from spriteVertices.
     */
    private void drawInternal(float[] spriteVertices, int offset, int count) {
        // Check if the vertex buffer is full BEFORE adding new data
        if (vertexBufferIdx + count > vertices.capacity()) {
            Gdx.app.log(TAG, "Vertex Buffer full (cap=" + vertices.capacity() + ", current=" + vertexBufferIdx + ", adding=" + count + "). Flushing.");
            flush(); // This resets spriteCount and vertexBufferIdx

            // After flush, the pipeline/set needs rebinding
            pipelineAndSetBoundThisBatch = false;
        }

        try {
            // Basic validation before putting data
            if (vertexBufferIdx < 0 || offset < 0 || count <= 0 || vertexBufferIdx + count > vertices.capacity() || offset + count > spriteVertices.length) {
                throw new IllegalArgumentException("Invalid indices for vertices.put: vertexBufferIdx=" + vertexBufferIdx
                        + ", offset=" + offset + ", count=" + count + ", vertices.capacity=" + vertices.capacity()
                        + ", spriteVertices.length=" + spriteVertices.length);
            }

            // Position the buffer and put the data
            vertices.position(vertexBufferIdx);
            vertices.put(spriteVertices, offset, count);
            vertexBufferIdx += count; // Increment float index

            // Update sprite count based on vertices added (using the NEW vertexFloats size)
            int verticesAdded = count / this.vertexFloats; // vertexFloats is now 6
            spriteCount += verticesAdded / VERTICES_PER_SPRITE; // VERTICES_PER_SPRITE is still 4

        } catch (BufferOverflowException | IndexOutOfBoundsException | IllegalArgumentException e) {
            Gdx.app.error(TAG, "Buffer Exception during vertices.put. vertexBufferIdx=" + vertexBufferIdx + ", offset=" + offset + ", count=" + count + ", vertices.capacity=" + vertices.capacity() + ", spriteVertices.length=" + spriteVertices.length, e);
            throw new GdxRuntimeException("Error copying vertex data", e);
        }
    }

    /**
     * Gets the index for the given texture within the current batch segment [0..MAX_BATCH_TEXTURES-1].
     * If the texture is not yet in the batch and the batch texture limit is reached,
     * this method triggers a flush() and resets the texture state, returning -1
     * to signal the caller needs to retry getting the index (which will then be 0).
     * If the texture is null, returns index 0 (assuming default texture is at slot 0).
     *
     * @param texture The texture to get an index for. Can be null (uses defaultTexture).
     * @return The index (0 to MAX_BATCH_TEXTURES-1) or -1 if a flush occurred and retry is needed.
     * @throws GdxRuntimeException if the texture has invalid handles or defaultTexture is null/invalid when needed.
     */
    private int getTextureIndex(VulkanTexture texture) {
        // Use default texture if input is null
        VulkanTexture tex = (texture == null) ? defaultTexture : texture;

        // Basic validation of the texture we intend to use
        if (tex == null) {
            throw new GdxRuntimeException("Cannot get texture index: Input texture and defaultTexture are both null!");
        }
        if (tex.getImageViewHandle() == VK_NULL_HANDLE || tex.getSamplerHandle() == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot get texture index: Texture object (hash=" + tex.hashCode() + ") has invalid handles!");
        }

        Integer index = textureToIndex.get(tex);

        if (index != null) {
            // Texture already in this batch segment, return existing index
            return index;
        }

        // --- Texture is NOT in the current batch segment ---

        if (nextTextureSlot >= MAX_BATCH_TEXTURES) {
            Gdx.app.log(TAG, "Max batch textures (" + MAX_BATCH_TEXTURES + ") reached. Flushing.");
            flush(); // Flush current data using the textures currently mapped

            // Reset state for the next segment
            nextTextureSlot = 0;
            textureToIndex.clear();
            Arrays.fill(currentBatchTextures, null);
            pipelineAndSetBoundThisBatch = false; // Force rebind (pipeline and set) after flush

            return -1; // Signal caller to retry getTextureIndex
        }

        index = nextTextureSlot;
        textureToIndex.put(tex, index); // Add to map
        currentBatchTextures[index] = tex; // Store in array for potential future descriptor updates
        nextTextureSlot++; // Increment next available slot

        lastTexture = tex;

        return index;
    }

    @Override
    public void dispose() {
        Gdx.app.log(TAG, "VulkanSpriteBatch dispose() called. Hash: " + this.hashCode());

        if (Gdx.graphics instanceof VulkanGraphics) {
            ((VulkanGraphics) Gdx.graphics).unregisterFrameResourcePreparer(this);
        }

        if (defaultTexture != null) { // <<< Add disposal
            Gdx.app.log(TAG, "Disposing internally created default white texture.");
            defaultTexture.dispose();
            defaultTexture = null;
        }

        // --- Unmap Persistent Buffer ---
        if (vertexBuffer != null && mappedVertexByteBuffer != null && vertexBuffer.allocationHandle != VK_NULL_HANDLE) {
            // Unmapping before destroying the buffer is good practice with VMA
            try {
                vmaUnmapMemory(vmaAllocator, vertexBuffer.allocationHandle);
                Gdx.app.log(TAG, "Unmapped vertex buffer allocation: " + vertexBuffer.allocationHandle);
            } catch (Exception e) {
                Gdx.app.error(TAG, "Error unmapping vertex buffer during dispose", e);
            }
            mappedVertexByteBuffer = null;
            vertices = null;
        }
        // ---

        // --- Dispose Buffers ---
        if (vertexBuffer != null) {
            Gdx.app.log(TAG, "Disposing vertexBuffer handle: " + vertexBuffer.bufferHandle);
            vertexBuffer.dispose();
            vertexBuffer = null;
        }
        if (indexBuffer != null) {
            Gdx.app.log(TAG, "Disposing indexBuffer handle: " + indexBuffer.bufferHandle);
            indexBuffer.dispose();
            indexBuffer = null;
        }
        if (projMatrixUbo != null) {
            Gdx.app.log(TAG, "Disposing projMatrixUbo handle: " + projMatrixUbo.bufferHandle);
            projMatrixUbo.dispose();
            projMatrixUbo = null;
        }
        // ---

        // --- Queue Descriptor Sets for Freeing ---
        if (descriptorManager != null && batchDescriptorSets != null) {
            List<Long> setsToQueue = new ArrayList<>(batchDescriptorSets.length);
            for (long setHandle : batchDescriptorSets) {
                if (setHandle != VK_NULL_HANDLE) {
                    setsToQueue.add(setHandle);
                }
            }
            if (!setsToQueue.isEmpty()) {
                Gdx.app.log(TAG, "Queuing " + setsToQueue.size() + " descriptor sets for deferred freeing.");
                descriptorManager.freeSets(setsToQueue); // Use the manager's deferred free
            }
            // Clear local references
            Arrays.fill(batchDescriptorSets, VK_NULL_HANDLE);
        }

        batchDescriptorLayout = VK_NULL_HANDLE;
        batchPipelineLayout = VK_NULL_HANDLE;

        Gdx.app.log(TAG, "VulkanSpriteBatch Disposed.");
    }

    @Override
    public void flush() {
        // --- Pre-checks ---
        int currentSpriteCount = this.spriteCount;
        if (currentSpriteCount == 0 && vertexBufferIdx == 0) { // Nothing to draw and buffer empty
            // Gdx.app.log(TAG, "Flush: No sprites and buffer empty, skipping.");
            return;
        }
        // Handle case where buffer has data but sprite count is 0 (maybe due to partial sprite additions)
        if (vertexBufferIdx > 0 && currentSpriteCount == 0) {
            Gdx.app.log(TAG, "Flush Warning: Flushing with vertex data (" + vertexBufferIdx + " floats) but spriteCount == 0. Resetting buffer instead of drawing.");
            resetCountersAndLog(); // Just reset, don't attempt draw
            return;
        }
        // If somehow spriteCount > 0 but buffer is empty, reset.
        if (currentSpriteCount > 0 && vertexBufferIdx == 0) {
            Gdx.app.log(TAG, "Flush Error: spriteCount > 0 (" + currentSpriteCount + ") but vertexBufferIdx is 0. Resetting state.");
            resetCountersAndLog(); // Reset inconsistent state
            return;
        }
        // Final check if counts are still valid after potential adjustments
        if (currentSpriteCount <= 0 || vertexBufferIdx <= 0) {
            // Gdx.app.log(TAG, "Flush: No valid sprites/vertices to draw after checks. Skipping.");
            resetCountersAndLog(); // Ensure reset if state became invalid
            return;
        }
        // --- End Pre-checks ---

        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;
        if (gfx == null) { // Safety check
            Gdx.app.error(TAG, "Flush error: Gdx.graphics context is null! Cannot flush.");
            resetCountersAndLog();
            return;
        }
        long currentRenderPassHandle = gfx.getCurrentRenderPassHandle();
        VkCommandBuffer currentCommandBuffer = gfx.getCurrentCommandBuffer();
        int currentFrameIndex = gfx.getCurrentFrameIndex();

        if (currentRenderPassHandle == VK_NULL_HANDLE || currentCommandBuffer == null
                // || lastTexture == null // <<<--- REMOVE THIS CHECK
                || this.vertexBuffer == null || this.vertexBuffer.bufferHandle == VK_NULL_HANDLE
                || this.indexBuffer == null || this.indexBuffer.bufferHandle == VK_NULL_HANDLE
                || this.batchPipelineLayout == VK_NULL_HANDLE
                || batchDescriptorSets == null || currentFrameIndex < 0 || currentFrameIndex >= batchDescriptorSets.length
                || batchDescriptorSets[currentFrameIndex] == VK_NULL_HANDLE) {

            // Log specific details for easier debugging (adjusted log message)
            Gdx.app.error(TAG, "Flush Error: Invalid Vulkan context or resource state."
                    + " RP=" + currentRenderPassHandle + ", CB=" + (currentCommandBuffer != null ? currentCommandBuffer.address() : "null")
                    // + ", Texture=" + (lastTexture != null ? lastTexture.hashCode() : "null") // Removed texture from log message too
                    + ", VB=" + (this.vertexBuffer != null ? this.vertexBuffer.bufferHandle : "null")
                    + ", IB=" + (this.indexBuffer != null ? this.indexBuffer.bufferHandle : "null")
                    + ", PipelineLayout=" + this.batchPipelineLayout
                    + ", DescSetsNull?=" + (batchDescriptorSets == null)
                    + ", FrameIdx=" + currentFrameIndex
                    + ", DescSetsLength=" + (batchDescriptorSets != null ? batchDescriptorSets.length : "N/A")
                    + ", CurrentFrameSet=" + (batchDescriptorSets != null && currentFrameIndex >= 0 && currentFrameIndex < batchDescriptorSets.length ? batchDescriptorSets[currentFrameIndex] : "N/A")
            );
            // Reset state and throw to prevent further errors in this frame
            resetCountersAndLog();
            // Maybe throw a more specific error or just return if recoverable? For now, throw.
            throw new GdxRuntimeException("Cannot flush() batch due to invalid Vulkan context or resource state (Check RP, CB, Buffers, Layout, Set Handle).");
        }

        long currentFrameSet = batchDescriptorSets[currentFrameIndex]; // Get the set for THIS frame

        // --- Bind Pipeline and Descriptor Set if Needed ---
        if (!pipelineAndSetBoundThisBatch) {

            // Get pipeline from manager
            long pipelineToUse = pipelineManager.getOrCreateSpriteBatchPipeline(this.batchPipelineLayout, currentRenderPassHandle);
            if (pipelineToUse == VK_NULL_HANDLE) {
                resetCountersAndLog(); // Reset before throwing
                throw new GdxRuntimeException("Could not get/create sprite batch pipeline in flush.");
            }
            vkCmdBindPipeline(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineToUse);

            // Bind the descriptor set for the current frame
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer pSet = stack.longs(currentFrameSet);
                vkCmdBindDescriptorSets(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, this.batchPipelineLayout, 0, pSet, null);
            }

            pipelineAndSetBoundThisBatch = true; // Mark as bound for this flush sequence
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {

            LongBuffer pBuffers = stack.longs(vertexBuffer.bufferHandle);
            LongBuffer pOffsets = stack.longs(0L); // Use 0L for long literal zero
            vkCmdBindVertexBuffers(currentCommandBuffer, 0, pBuffers, pOffsets);
            vkCmdBindIndexBuffer(currentCommandBuffer, indexBuffer.bufferHandle, 0, VK_INDEX_TYPE_UINT16);
        }

        long vertexBytesToFlush = (long) vertexBufferIdx * Float.BYTES;
        if (vertexBytesToFlush > 0) {
            if (vertexBuffer.allocationHandle != VK_NULL_HANDLE) {
                // Gdx.app.log(TAG, "Flushing VMA allocation: " + vertexBuffer.allocationHandle + ", Size: " + vertexBytesToFlush); // Verbose log
                vmaFlushAllocation(vmaAllocator, vertexBuffer.allocationHandle, 0, vertexBytesToFlush);
            } else {
                Gdx.app.error(TAG, "Cannot flush vertex buffer memory, VMA allocation handle is null.");
                // Decide if this is fatal or recoverable (e.g., reset and skip draw)
                resetCountersAndLog();
                throw new GdxRuntimeException("VMA Allocation handle invalid during flush.");
            }
        }

        int indexCountToDraw = currentSpriteCount * INDICES_PER_SPRITE;
        // Gdx.app.log(TAG, "Flush: vkCmdDrawIndexed(indices=" + indexCountToDraw + ", sprites=" + currentSpriteCount +")"); // Verbose log
        vkCmdDrawIndexed(currentCommandBuffer, indexCountToDraw, 1, 0, 0, 0); // Draw the vertices
        renderCalls++;

        vertexBufferIdx = 0;
        spriteCount = 0;
    }

    private void resetCountersAndLog() {
        vertexBufferIdx = 0;
        spriteCount = 0;
    }

    /*protected void switchTexture(VulkanTexture texture) {
        if (texture == null) {
            throw new IllegalArgumentException("Cannot switch to a null texture.");
        }
        if (texture.getImageViewHandle() == VK_NULL_HANDLE || texture.getSamplerHandle() == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("switchTexture called with a VulkanTexture that has invalid handles.");
        }

        // Update cache if needed, but NO flush here.
        if (lastTexture != texture) {
            // Update lastTexture tracked for prepareResourcesForFrame (also updated in getTextureIndex)
            lastTexture = texture;
            // Update inverse texture size cache if still needed
            if (texture.getWidth() <= 0 || texture.getHeight() <= 0) {
                invTexWidth = 0;
                invTexHeight = 0;
            } else {
                invTexWidth = 1.0f / texture.getWidth();
                invTexHeight = 1.0f / texture.getHeight();
            }
        }
    }*/

    @Override
    public void disableBlending() {
        if (blendingEnabled) {
            flush(); // Flush pending draws with the old state
            blendingEnabled = false;
        }
    }

    @Override
    public void enableBlending() {
        if (!blendingEnabled) {
            flush(); // Flush pending draws with the old state
            blendingEnabled = true;
        }
    }

    @Override
    public void setBlendFunction(int srcFunc, int dstFunc) {
        setBlendFunctionSeparate(srcFunc, dstFunc, srcFunc, dstFunc); // Delegate to separate
        blendFuncSeparate = false; // Mark as non-separate for getters if needed
    }

    @Override
    public void setBlendFunctionSeparate(int srcFuncColor, int dstFuncColor, int srcFuncAlpha, int dstFuncAlpha) {
        if (this.blendSrcFunc != srcFuncColor || this.blendDstFunc != dstFuncColor ||
                this.blendSrcFuncAlpha != srcFuncAlpha || this.blendDstFuncAlpha != dstFuncAlpha) {

            flush(); // Flush pending draws with the old state
            this.blendSrcFunc = srcFuncColor;
            this.blendDstFunc = dstFuncColor;
            this.blendSrcFuncAlpha = srcFuncAlpha;
            this.blendDstFuncAlpha = dstFuncAlpha;
            this.blendFuncSeparate = true; // Mark as separate
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
        // Return color func if separate wasn't used, mimicking OpenGL behavior
        return blendFuncSeparate ? blendSrcFuncAlpha : blendSrcFunc;
    }

    @Override
    public int getBlendDstFuncAlpha() {
        // Return color func if separate wasn't used
        return blendFuncSeparate ? blendDstFuncAlpha : blendDstFunc;
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
        // Flush if projection changes mid-batch
        if (drawing && (spriteCount > 0 || vertexBufferIdx > 0)) {
            Gdx.app.log(TAG, "Projection matrix changed mid-batch, flushing.");
            flush();
        }
        this.projectionMatrix.set(projection);
        // Update UBO immediately if not drawing, otherwise begin() will handle it
        if (!drawing) {
            updateProjectionMatrixUBO();
        }
    }

    @Override
    public void setTransformMatrix(Matrix4 transform) {
        if (transform == null) throw new IllegalArgumentException("transform matrix cannot be null.");
        // Flush if transform changes mid-batch
        if (drawing && (spriteCount > 0 || vertexBufferIdx > 0)) {
            VulkanApplication app = (VulkanApplication) Gdx.app;
            //Gdx.app.log(TAG, "Window " + app.getCurrentWindow().hashCode() + "\tTransform matrix changed mid-batch, flushing.");
            flush();
        }
        this.transformMatrix.set(transform);
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
        return blendingEnabled;
    }

    @Override
    public boolean isDrawing() {
        return drawing;
    }

    /**
     * Implements VulkanFrameResourcePreparer. Called once per frame after the
     * corresponding fence wait and before command buffer recording begins.
     * Updates the UBO and checks if the texture descriptor set for this frame
     * needs to be updated to match the globally last requested texture.
     *
     * @param frameIndex The index of the frame about to be rendered (0 to MAX_FRAMES_IN_FLIGHT - 1).
     */
    @Override
    public void prepareResourcesForFrame(int frameIndex) {
        // --- Input Validation ---
        if (frameIndex < 0 || frameIndex >= frameCount) {
            Gdx.app.error(TAG, "prepareResourcesForFrame: Invalid frame index " + frameIndex);
            return;
        }
        if (currentTextureInSet == null || frameIndex >= currentTextureInSet.length) {
            Gdx.app.error(TAG, "prepareResourcesForFrame: currentTextureInSet array not initialized or index out of bounds for frame " + frameIndex);
            return;
        }
        if (batchDescriptorSets == null || frameIndex >= batchDescriptorSets.length) {
            Gdx.app.error(TAG, "prepareResourcesForFrame: batchDescriptorSets array not initialized or index out of bounds for frame " + frameIndex);
            return;
        }
        // --- End Input Validation ---


        // Always update UBO (or add dirty flag later)
        updateProjectionMatrixUBO();

        // Determine the texture that *should* be bound in the upcoming frame N,
        // based on the state at the end of the *previous* frame's rendering logic (lastTexture).
        VulkanTexture requiredTexture = this.lastTexture;
        if (requiredTexture == null) {
            // If no texture was ever drawn/set in the previous frame(s), use the initial default.
            requiredTexture = this.defaultTexture;
        }

        // This check should ideally not fail if switchTexture validates input,
        // but double-check before comparing/using.
        if (requiredTexture == null) {
            Gdx.app.error(TAG, "prepareResourcesForFrame: Cannot determine required texture (lastTexture and defaultTexture are null) for frame " + frameIndex);
            return;
        }

        VulkanTexture textureForSlot0 = this.lastTexture != null ? this.lastTexture : this.defaultTexture;
        if (textureForSlot0 == null) {
            Gdx.app.error(TAG, "prepareResourcesForFrame: Cannot determine texture for slot 0 for frame " + frameIndex);
            return;
        }

        // Check if texture for slot 0 needs update (simplified check)
        if (currentTextureInSet[frameIndex] != textureForSlot0) { // Still comparing single texture tracking
            long frameSet = batchDescriptorSets[frameIndex];
            if (frameSet != VK_NULL_HANDLE) {
                if (textureForSlot0.getImageViewHandle() == VK_NULL_HANDLE || textureForSlot0.getSamplerHandle() == VK_NULL_HANDLE) {
                    Gdx.app.error(TAG, "prepareResourcesForFrame: Required texture for slot 0 (hash=" + textureForSlot0.hashCode() + ") has invalid handles! Cannot update Set " + frameIndex);
                } else {
                    Gdx.app.log(TAG, "prepareResourcesForFrame: Updating texture descriptor SLOT 0 for set " + frameSet + " (Frame " + frameIndex
                            + ") from Texture " + (currentTextureInSet[frameIndex] != null ? currentTextureInSet[frameIndex].hashCode() : "null (init)")
                            + " to Texture " + textureForSlot0.hashCode());

                    // Assuming an updated method that takes an array index (element)
                    VulkanDescriptorManager.updateCombinedImageSampler(rawDevice, frameSet, 1, 0, textureForSlot0); // Update element 0

                    // Record only the texture updated in slot 0
                    currentTextureInSet[frameIndex] = textureForSlot0; // This tracking is now insufficient
                }
            } else {
                Gdx.app.error(TAG, "prepareResourcesForFrame: Cannot update descriptor set, handle is null for frame " + frameIndex);
            }
        }
    }

    /*private int mapGLBlendFactorToVulkan(int glBlendFactor) {
        switch (glBlendFactor) {
            case GL20.GL_ZERO:
                return VK_BLEND_FACTOR_ZERO;
            case GL20.GL_ONE:
                return VK_BLEND_FACTOR_ONE;
            case GL20.GL_SRC_COLOR:
                return VK_BLEND_FACTOR_SRC_COLOR;
            case GL20.GL_ONE_MINUS_SRC_COLOR:
                return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case GL20.GL_DST_COLOR:
                return VK_BLEND_FACTOR_DST_COLOR;
            case GL20.GL_ONE_MINUS_DST_COLOR:
                return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case GL20.GL_SRC_ALPHA:
                return VK_BLEND_FACTOR_SRC_ALPHA;
            case GL20.GL_ONE_MINUS_SRC_ALPHA:
                return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case GL20.GL_DST_ALPHA:
                return VK_BLEND_FACTOR_DST_ALPHA;
            case GL20.GL_ONE_MINUS_DST_ALPHA:
                return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case GL20.GL_SRC_ALPHA_SATURATE:
                return VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            // Add GL30 constants if needed (GL_CONSTANT_COLOR etc.) -> map to VK equivalents
            default:
                Gdx.app.error(TAG, "Unsupported blend factor: " + glBlendFactor);
                return VK_BLEND_FACTOR_ONE; // Sensible default?
        }
    }*/
}