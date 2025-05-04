package com.badlogic.gdx.backend.vulkan; // Example package

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
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
import java.util.Arrays;
import java.util.List;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanSpriteBatch implements Batch, VulkanFrameResourcePreparer, Disposable {

    private final String TAG = "VulkanSpriteBatch";

    private final VulkanDevice device;
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
    private final ShortBuffer indicesCpu;

    private final int vertexSize;
    private final int vertexFloats;

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

    private final float[] affineVertices = new float[VERTICES_PER_SPRITE * COMPONENTS_PER_VERTEX]; // 4 * 5 = 20 floats

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

    private final VulkanTexture[] currentTextureInSet;

    public VulkanSpriteBatch() {
        this(1024); // Default batch size
    }

    public VulkanSpriteBatch(int size) {
        Gdx.app.log(TAG, "Initializing with size: " + size);

        if (!(Gdx.graphics instanceof VulkanGraphics)) {
            throw new GdxRuntimeException("VulkanSpriteBatch requires the VulkanGraphics backend!");
        }
        VulkanGraphics gfx = (VulkanGraphics) Gdx.graphics;

        gfx.registerFrameResourcePreparer(this); // Register self
        Gdx.app.log(TAG, "Initialization complete and registered for frame preparation.");

        this.device = gfx.getVulkanDevice();
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
        this.batchDescriptorSets = new long[this.frameCount]; // Initialize array

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
        Gdx.app.log(TAG, "Created vertexBuffer handle: " + this.vertexBuffer.bufferHandle + ", Allocation: " + this.vertexBuffer.allocationHandle);

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

        Gdx.app.log(TAG, "Allocating " + frameCount + " descriptor sets using manager...");
        if (this.batchDescriptorLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Cannot allocate descriptor sets, layout handle is null.");
        }
        Gdx.app.log(TAG, "Created projMatrixUbo handle: " + this.projMatrixUbo.bufferHandle + ", Allocation: " + this.projMatrixUbo.allocationHandle);

        Gdx.app.log(TAG, "Allocating " + this.frameCount + " descriptor sets using manager...");
        defaultTexture = findDefaultWhiteTexture(); // <<< Helper needed

        currentTextureInSet = new VulkanTexture[this.frameCount];

        for (int i = 0; i < this.frameCount; i++) {
            long setHandle = descriptorManager.allocateSet(this.batchDescriptorLayout);
            if (setHandle == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Failed to allocate descriptor set [" + i + "] via manager.");
            }
            this.batchDescriptorSets[i] = setHandle; // Store handle in array
            Gdx.app.log(TAG, "Allocated batch descriptor set [" + i + "]: " + setHandle);

            // Perform initial UBO update for each set
            if (this.projMatrixUbo == null || this.projMatrixUbo.bufferHandle == VK_NULL_HANDLE) {
                throw new IllegalStateException("projMatrixUbo invalid before initial descriptor update for set [" + i + "]!");
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

            Gdx.app.log(TAG, "Performing initial Sampler update for set [" + i + "]: " + setHandle);
            VulkanDescriptorManager.updateCombinedImageSampler(
                    rawDevice,
                    setHandle,
                    1, // Binding 1 for sampler
                    defaultTexture // Use the default texture
            );

            this.currentTextureInSet[i] = defaultTexture;
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

    private VulkanTexture findDefaultWhiteTexture() {
        Pixmap white = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        white.setColor(Color.WHITE);
        white.fill();
        VulkanTexture tex = new VulkanTexture(white);
        white.dispose();
        return tex;
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
            descriptorWrite.get(0)
                    .sType$Default()
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
        // Gdx.app.log(TAG, "updateTextureDescriptor called for Set=" + descriptorSet); // Optional Debug
        if (texture == null || rawDevice == null) {
            Gdx.app.error(TAG, "Cannot update texture descriptor - texture or rawDevice is null");
            return;
        }
        // Calls the static helper in VulkanDescriptorManager
        VulkanDescriptorManager.updateCombinedImageSampler(rawDevice, descriptorSet, 1, texture);
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

    // Make sure lastTexture is accessible if needed from VulkanWindow
    public VulkanTexture getLastTexture() {
        return lastTexture;
    }

    @Override
    public void begin() {
        if (drawing) throw new IllegalStateException("Batch.end must be called before begin.");

        drawing = true;
        renderCalls = 0;
        spriteCount = 0;
        vertexBufferIdx = 0;
        pipelineAndSetBoundThisBatch = false;

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
        if (!(texture instanceof VulkanTexture)) throw new GdxRuntimeException("Texture is not a VulkanTexture instance!");
        VulkanTexture vkTexture = (VulkanTexture) texture;

        // Use affineVertices as temporary storage to avoid heap allocation
        float[] verts = this.affineVertices; // Reuse the member array
        int i = 0; // Index into verts array

        // Calculate texture coordinates first (potentially needed for switchTexture)
        float u, v, u2, v2;
        if (vkTexture != lastTexture) {
            // If texture is switching, calculate invTexWidth/Height *before* using them
            if (vkTexture.getWidth() == 0 || vkTexture.getHeight() == 0) {
                invTexWidth = 0;
                invTexHeight = 0;
                Gdx.app.error(TAG, "Texture dimensions are zero when calculating UVs!");
            } else {
                invTexWidth = 1.0f / vkTexture.getWidth();
                invTexHeight = 1.0f / vkTexture.getHeight();
            }
        }
        // Check again *after* potential update in case dimensions were 0
        if (invTexWidth == 0 || invTexHeight == 0) {
            Gdx.app.error(TAG, "Cannot draw, invTexWidth or invTexHeight is zero.");
            return; // Avoid division by zero
        }

        u = srcX * invTexWidth;
        // Vulkan/GL default UV origin (0,0) is bottom-left. LibGDX TextureRegion assumes top-left.
        // Adjust v/v2 accordingly if needed based on your texture loading / coordinate system.
        // Assuming standard LibGDX region coords (0,0 top-left):
        v = srcY * invTexHeight; // Top Y coord becomes V start
        u2 = (srcX + srcWidth) * invTexWidth;
        v2 = (srcY + srcHeight) * invTexHeight; // Bottom Y coord becomes V end

        // --- Vertex Position Calculation (same as before) ---
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
            y4 = y3 - (y2 - y1); // parallelogram completion
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
        // --- End Vertex Position Calculation ---

        // Handle texture coordinate flipping
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

        // Populate vertex array (X, Y, Color, U, V) - counter-clockwise quad
        // Vertex 0 (maps to bottom-left UV: u, v2 - assuming standard GL/Vulkan UVs)
        verts[i++] = x1;
        verts[i++] = y1;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v;
        // Vertex 1 (maps to top-left UV: u, v)
        verts[i++] = x2;
        verts[i++] = y2;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v2;
        // Vertex 2 (maps to top-right UV: u2, v)
        verts[i++] = x3;
        verts[i++] = y3;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v2;
        // Vertex 3 (maps to bottom-right UV: u2, v2)
        verts[i++] = x4;
        verts[i++] = y4;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v;

        // Call internal draw with the prepared vertices
        drawInternal(vkTexture, verts, 0, i); // Pass offset 0 and the actual count i
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {
        // Call the detailed version with defaults for origin, scale, rotation
        draw(texture, x, y, 0f, 0f, width, height, 1f, 1f, 0f, srcX, srcY, srcWidth, srcHeight, flipX, flipY);
    }

    @Override
    public void draw(Texture texture, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {
        // Call the detailed version with defaults for size matching src, origin, scale, rotation, flips
        draw(texture, x, y, 0f, 0f, srcWidth, srcHeight, 1f, 1f, 0f, srcX, srcY, srcWidth, srcHeight, false, false);
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, float u, float v, float u2, float v2) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");
        if (!(texture instanceof VulkanTexture)) throw new GdxRuntimeException("Texture is not a VulkanTexture instance!");
        VulkanTexture vkTexture = (VulkanTexture) texture;

        // Use affineVertices as temporary storage
        float[] verts = this.affineVertices;
        int i = 0;

        // Vertex 0 (Bottom Left)
        verts[i++] = x;
        verts[i++] = y;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v2; // Map to u, v2
        // Vertex 1 (Top Left)
        verts[i++] = x;
        verts[i++] = y + height;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v; // Map to u, v
        // Vertex 2 (Top Right)
        verts[i++] = x + width;
        verts[i++] = y + height;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v; // Map to u2, v
        // Vertex 3 (Bottom Right)
        verts[i++] = x + width;
        verts[i++] = y;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v2; // Map to u2, v2

        drawInternal(vkTexture, verts, 0, i);
    }

    @Override
    public void draw(Texture texture, float x, float y) {
        // Call draw with texture's width/height
        draw(texture, x, y, texture.getWidth(), texture.getHeight());
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");
        if (!(texture instanceof VulkanTexture)) throw new GdxRuntimeException("Texture is not a VulkanTexture instance!");
        VulkanTexture vkTexture = (VulkanTexture) texture;

        // Use affineVertices as temporary storage
        float[] verts = this.affineVertices;
        int i = 0;

        // Default UVs (0,0) to (1,1) -> maps to (0,1) to (1,0) in typical GL/Vulkan unless flipped
        float u = 0, v = 0, u2 = 1, v2 = 1; // These might need adjustment based on texture coord conventions

        // Vertex 0 (Bottom Left)
        verts[i++] = x;
        verts[i++] = y;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v2;
        // Vertex 1 (Top Left)
        verts[i++] = x;
        verts[i++] = y + height;
        verts[i++] = colorPacked;
        verts[i++] = u;
        verts[i++] = v;
        // Vertex 2 (Top Right)
        verts[i++] = x + width;
        verts[i++] = y + height;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v;
        // Vertex 3 (Bottom Right)
        verts[i++] = x + width;
        verts[i++] = y;
        verts[i++] = colorPacked;
        verts[i++] = u2;
        verts[i++] = v2;

        drawInternal(vkTexture, verts, 0, i);
    }

    @Override
    public void draw(Texture texture, float[] spriteVertices, int offset, int count) {
        // This is the core implementation used by other draw methods when rotation/scaling are involved
        drawInternal((VulkanTexture) texture, spriteVertices, offset, count);
    }

    @Override
    public void draw(TextureRegion region, float x, float y) {
        draw(region, x, y, region.getRegionWidth(), region.getRegionHeight());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float width, float height) {
        // Call the detailed version with defaults
        draw(region, x, y, 0f, 0f, width, height, 1f, 1f, 0f);
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation) {
        // Pass through to the core Texture draw method, extracting region info
        draw(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation,
                region.getRegionX(), region.getRegionY(), region.getRegionWidth(), region.getRegionHeight(),
                false, false); // Assuming non-flipped TextureRegion data initially
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation,
                     boolean clockwise) {
        // This method requires calculating vertices differently if clockwise rotation means something specific
        Gdx.app.error(TAG, "draw(..., clockwise) not fully implemented.");
        // Fallback to non-clockwise for now
        draw(region, x, y, originX, originY, width, height, scaleX, scaleY, rotation);
    }

    @Override
    public void draw(TextureRegion region, float width, float height, com.badlogic.gdx.math.Affine2 transform) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw.");
        if (!(region.getTexture() instanceof VulkanTexture)) throw new GdxRuntimeException("TextureRegion's texture is not a VulkanTexture!");
        VulkanTexture vkTexture = (VulkanTexture) region.getTexture();

        // Use affineVertices as temporary storage
        float[] verts = this.affineVertices;
        int idx = 0;

        // --- 1. Calculate Vertex Positions ---
        final float m00 = transform.m00;
        final float m01 = transform.m01;
        final float m02 = transform.m02; // x translation
        final float m10 = transform.m10;
        final float m11 = transform.m11;
        final float m12 = transform.m12; // y translation

        float x1 = m02;
        float y1 = m12;                     // Top-left (0,0)
        float x2 = m01 * height + m02;
        float y2 = m11 * height + m12;      // Bottom-left (0,height)
        float x3 = m00 * width + m01 * height + m02;
        float y3 = m10 * width + m11 * height + m12; // Bottom-right (width,height)
        float x4 = m00 * width + m02;
        float y4 = m10 * width + m12;      // Top-right (width,0)

        // --- 2. Get Texture Coordinates ---
        float u = region.getU();
        float v = region.getV(); // Top-left UV
        float u2 = region.getU2();
        float v2 = region.getV2(); // Bottom-right UV

        // --- 3. Get Packed Color ---
        float color = this.colorPacked;

        // --- 4. Populate Temporary Vertex Array (X, Y, Color, U, V) - counter-clockwise quad ---
        verts[idx++] = x1;
        verts[idx++] = y1;
        verts[idx++] = color;
        verts[idx++] = u;
        verts[idx++] = v;  // Top-left vertex, maps to u,v
        verts[idx++] = x2;
        verts[idx++] = y2;
        verts[idx++] = color;
        verts[idx++] = u;
        verts[idx++] = v2; // Bottom-left vertex, maps to u,v2
        verts[idx++] = x3;
        verts[idx++] = y3;
        verts[idx++] = color;
        verts[idx++] = u2;
        verts[idx++] = v2; // Bottom-right vertex, maps to u2,v2
        verts[idx++] = x4;
        verts[idx++] = y4;
        verts[idx++] = color;
        verts[idx++] = u2;
        verts[idx++] = v;  // Top-right vertex, maps to u2,v

        // Call internal draw with the prepared vertices
        drawInternal(vkTexture, verts, 0, idx); // Pass offset 0 and the actual count idx
    }

    private void drawInternal(VulkanTexture texture, float[] spriteVertices, int offset, int count) {
        // --- Switch Texture Logic ---
        if (texture != lastTexture) {
            switchTexture(texture); // This calls flush() if needed
        }
        // ---

        // --- Buffer Full Check ---
        // Check if adding the current sprite vertices would exceed capacity
        if (vertexBufferIdx + count > vertices.capacity()) {
            Gdx.app.log(TAG, "Buffer full or requires flush. vertexBufferIdx=" + vertexBufferIdx + ", count=" + count + ", capacity=" + vertices.capacity());
            flush(); // Flush before adding new data

            // After flush, re-check if texture changed unexpectedly (shouldn't happen with switchTexture logic)
            if (texture != lastTexture) {
                Gdx.app.error(TAG, "Texture changed unexpectedly after buffer-full flush! This indicates a logic error.");
                switchTexture(texture); // Re-switch just in case, though flush should handle it
            }
        }
        // ---

        // --- Copy Vertex Data ---
        try {
            // Ensure indices are valid before putting data
            if (vertexBufferIdx < 0 || offset < 0 || count <= 0 || vertexBufferIdx + count > vertices.capacity() || offset + count > spriteVertices.length) {
                throw new IllegalArgumentException("Invalid indices for vertices.put: vertexBufferIdx=" + vertexBufferIdx
                        + ", offset=" + offset + ", count=" + count + ", vertices.capacity=" + vertices.capacity()
                        + ", spriteVertices.length=" + spriteVertices.length);
            }

            vertices.position(vertexBufferIdx); // Set position in target buffer
            vertices.put(spriteVertices, offset, count); // Copy data
            vertexBufferIdx += count; // Increment target index

            // Increment sprite count based on vertices added
            if (count % vertexFloats != 0) {
                Gdx.app.error(TAG, "drawInternal count (" + count + ") not multiple of vertexFloats (" + vertexFloats + ")!");
            }
            int verticesAdded = count / vertexFloats;
            if (verticesAdded % VERTICES_PER_SPRITE != 0) {
                Gdx.app.error(TAG, "drawInternal verticesAdded (" + verticesAdded + ") not multiple of VERTICES_PER_SPRITE (" + VERTICES_PER_SPRITE + ")!");
            }
            spriteCount += verticesAdded / VERTICES_PER_SPRITE;

        } catch (BufferOverflowException | IndexOutOfBoundsException | IllegalArgumentException e) {
            Gdx.app.error(TAG, "Buffer Exception during vertices.put. vertexBufferIdx=" + vertexBufferIdx + ", offset=" + offset + ", count=" + count + ", vertices.capacity=" + vertices.capacity() + ", spriteVertices.length=" + spriteVertices.length, e);
            // Depending on severity, you might want to re-throw or just log and continue/reset state
            throw new GdxRuntimeException("Error copying vertex data", e);
        }
        // ---
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

        // --- Context and Resource Validation ---
        if (currentRenderPassHandle == VK_NULL_HANDLE || currentCommandBuffer == null || lastTexture == null
                || this.vertexBuffer == null || this.vertexBuffer.bufferHandle == VK_NULL_HANDLE
                || this.indexBuffer == null || this.indexBuffer.bufferHandle == VK_NULL_HANDLE
                || this.batchPipelineLayout == VK_NULL_HANDLE
                || batchDescriptorSets == null || currentFrameIndex < 0 || currentFrameIndex >= batchDescriptorSets.length
                || batchDescriptorSets[currentFrameIndex] == VK_NULL_HANDLE) {
            // Log specific details for easier debugging
            Gdx.app.error(TAG, "Flush Error: Invalid Vulkan context or resource state."
                    + " RP=" + currentRenderPassHandle + ", CB=" + (currentCommandBuffer != null ? currentCommandBuffer.address() : "null")
                    + ", Texture=" + (lastTexture != null ? lastTexture.hashCode() : "null")
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
            throw new GdxRuntimeException("Cannot flush() batch due to invalid Vulkan context or resource state.");
        }
        // --- End Validation ---

        long currentFrameSet = batchDescriptorSets[currentFrameIndex]; // Get the set for THIS frame

        // --- Bind Pipeline and Descriptor Set if Needed ---
        if (!pipelineAndSetBoundThisBatch) {
            /*Gdx.app.log(TAG, "Flush/BindPipeline: Requesting pipeline for Layout=" + this.batchPipelineLayout
                    + ", Current Context RenderPass=" + currentRenderPassHandle + " (0x" + Long.toHexString(currentRenderPassHandle) + ")"
                    + " on CmdBuffer 0x" + Long.toHexString(currentCommandBuffer.address()));*/

            // Get pipeline from manager
            long pipelineToUse = pipelineManager.getOrCreateSpriteBatchPipeline(this.batchPipelineLayout, currentRenderPassHandle);
            if (pipelineToUse == VK_NULL_HANDLE) {
                resetCountersAndLog(); // Reset before throwing
                throw new GdxRuntimeException("Could not get/create sprite batch pipeline in flush.");
            }
            vkCmdBindPipeline(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineToUse);

            // Bind the descriptor set for the current frame
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer pSet = stack.longs(currentFrameSet); // Use the frame-specific set handle
                vkCmdBindDescriptorSets(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, this.batchPipelineLayout, 0, pSet, null);
                //Gdx.app.log(TAG, "Flush: Bound Pipeline=" + pipelineToUse + " and DescriptorSet=" + currentFrameSet + " (Frame " + currentFrameIndex + ")");
            }
            pipelineAndSetBoundThisBatch = true; // Mark as bound for this flush sequence
        }
        // ---

        // --- Bind Vertex and Index Buffers ---
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // --- Corrected Vertex Buffer Binding (Matching YOUR Environment) ---

            // Create a LongBuffer for the buffer handle(s)
            // Using stack.longs() is concise for single values
            LongBuffer pBuffers = stack.longs(vertexBuffer.bufferHandle);

            // Create a LongBuffer for the offset(s)
            LongBuffer pOffsets = stack.longs(0L); // Use 0L for long literal zero

            // Call the signature your IDE finds: VkCommandBuffer, firstBinding, LongBuffer, LongBuffer
            // NOTE: No 'bindingCount' (the '1') argument here!
            vkCmdBindVertexBuffers(currentCommandBuffer, 0, pBuffers, pOffsets);
            // --- End Corrected Vertex Buffer Binding ---

            // Index buffer binding remains the same
            vkCmdBindIndexBuffer(currentCommandBuffer, indexBuffer.bufferHandle, 0, VK_INDEX_TYPE_UINT16);
        }

        // ---

        // --- Flush Mapped Vertex Buffer Memory ---
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
        // ---

        // --- Issue Draw Call ---
        int indexCountToDraw = currentSpriteCount * INDICES_PER_SPRITE;
        // Gdx.app.log(TAG, "Flush: vkCmdDrawIndexed(indices=" + indexCountToDraw + ", sprites=" + currentSpriteCount +")"); // Verbose log
        vkCmdDrawIndexed(currentCommandBuffer, indexCountToDraw, 1, 0, 0, 0); // Draw the vertices
        renderCalls++;
        // ---

        // --- Reset Counters for Next Batch ---
        resetCountersAndLog();
        // ---
    }

    private void resetCountersAndLog() {
        vertexBufferIdx = 0;
        spriteCount = 0;
    }

    /**
     * Records the specified texture as the last one requested for drawing globally.
     * If the new texture is different from the last one recorded, the batch is flushed
     * if it contains pending vertices. The actual descriptor set update(s) will happen
     * later during prepareResourcesForFrame when necessary.
     *
     * @param texture The new VulkanTexture to switch to. Must not be null and must
     * have valid internal Vulkan handles.
     * @throws IllegalArgumentException if the provided texture is null.
     * @throws GdxRuntimeException if the provided texture has invalid internal handles.
     */
    protected void switchTexture(VulkanTexture texture) {
        // --- Input Validation ---
        if (texture == null) {
            throw new IllegalArgumentException("Cannot switch to a null texture.");
        }

        // --- Check if Switch is Needed (Comparing with immediate last texture) ---
        if (lastTexture == texture) {
            // No change needed in the globally last requested texture
            return;
        }

        long newImageViewHandle = texture.getImageViewHandle();
        long newSamplerHandle = texture.getSamplerHandle();
        Gdx.app.log(TAG, "switchTexture: Recording switch from " + (lastTexture != null ? lastTexture.hashCode() : "null") + " to " + texture.hashCode()
                + " | NewTex Valid? ImgView=" + newImageViewHandle + " Sampler=" + newSamplerHandle);

        if (newImageViewHandle == VK_NULL_HANDLE || newSamplerHandle == VK_NULL_HANDLE) {
            // If the incoming texture is already invalid (e.g., disposed prematurely),
            // we cannot use it. This indicates an error at the call site.
            throw new GdxRuntimeException("switchTexture called with a VulkanTexture that has invalid handles (ImageView/Sampler is VK_NULL_HANDLE). Texture hash: " + texture.hashCode());
        }

        if (this.spriteCount > 0 || this.vertexBufferIdx > 0) {
            Gdx.app.log(TAG, "switchTexture: Flushing " + spriteCount + " sprites (" + vertexBufferIdx + " floats) before texture state change.");
            flush(); // This will use the *previous* lastTexture state for binding
        } else {
            // Even if no flush happened, ensure counters are zero
            resetCountersAndLog();
        }

        lastTexture = texture;

        // Update inverse texture size cache based on the new texture
        if (texture.getWidth() <= 0 || texture.getHeight() <= 0) {
            Gdx.app.error(TAG, "Texture dimensions are zero or negative (" + texture.getWidth() + "x" + texture.getHeight() + ") in switchTexture for texture " + texture.hashCode());
            invTexWidth = 0; // Avoid NaN/Infinity
            invTexHeight = 0;
        } else {
            invTexWidth = 1.0f / texture.getWidth();
            invTexHeight = 1.0f / texture.getHeight();
        }

        this.pipelineAndSetBoundThisBatch = false;

        Gdx.app.log(TAG, "switchTexture: Completed recording switch. lastTexture=" + lastTexture.hashCode() + ", needsRebind=true");
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
            Gdx.app.log(TAG, "Transform matrix changed mid-batch, flushing.");
            flush();
        }
        this.transformMatrix.set(transform);
        // Note: Basic SpriteBatch doesn't use the transform matrix directly in the shader usually.
        // If you modify the shader/pipeline to use it, you'd need a separate UBO for it.
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

        // Check if the descriptor set for the upcoming frame (frameIndex)
        // already contains the required texture state.
        // Compare with the texture we *recorded* as being in the set during the *last* update for this frameIndex.
        if (currentTextureInSet[frameIndex] != requiredTexture) {

            long frameSet = batchDescriptorSets[frameIndex]; // Get the handle for the set to update

            if (frameSet != VK_NULL_HANDLE) {
                // Final check: Ensure the texture we are about to bind is actually valid *now*.
                if (requiredTexture.getImageViewHandle() == VK_NULL_HANDLE || requiredTexture.getSamplerHandle() == VK_NULL_HANDLE) {
                    Gdx.app.error(TAG,"prepareResourcesForFrame: Required texture hash=" + requiredTexture.hashCode() + " has invalid handles! Cannot update Set " + frameIndex);
                } else {
                    // Log the update attempt
                    Gdx.app.log(TAG, "prepareResourcesForFrame: Updating texture descriptor for set " + frameSet + " (Frame " + frameIndex
                            + ") from Texture " + (currentTextureInSet[frameIndex] != null ? currentTextureInSet[frameIndex].hashCode() : "null (init)")
                            + " to Texture " + requiredTexture.hashCode());

                    // Perform the actual descriptor set update for Binding 1 (Sampler)
                    VulkanDescriptorManager.updateCombinedImageSampler(rawDevice, frameSet, 1, requiredTexture);

                    // Record that this set now contains the required texture state
                    currentTextureInSet[frameIndex] = requiredTexture;
                }
            } else {
                Gdx.app.error(TAG, "prepareResourcesForFrame: Cannot update descriptor set, handle is null for frame " + frameIndex);
            }
        } else {
            // Log that no update was needed (optional verbose log)
            // Gdx.app.log(TAG, "prepareResourcesForFrame(" + frameIndex + "): Set already contains required texture " + requiredTexture.hashCode());
        }
    }
}