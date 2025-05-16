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

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Objects;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanSpriteBatchInstanced implements Batch, VulkanFrameResourcePreparer, Disposable {

    private static final String TAG = "VkSpriteBatchInst";

    public static final int FLOATS_PER_INSTANCE = 15;
    public static final int BYTES_PER_INSTANCE = FLOATS_PER_INSTANCE * Float.BYTES;

    private static final int BASE_QUAD_VERTEX_COMPONENTS = 4;
    private static final int BASE_QUAD_VERTICES_COUNT = 4;
    private static final int BASE_QUAD_INDICES_COUNT = 6;
    private VulkanBuffer baseQuadVertexBuffer;
    private VulkanBuffer baseQuadIndexBuffer;

    private final VkDevice rawDevice;
    private final long vmaAllocator;
    private final VulkanPipelineManager pipelineManager;
    private final VulkanDescriptorManager descriptorManager;

    private VulkanTextureBatch textureBatcher;

    private VulkanBuffer instanceDataBuffer;
    private ByteBuffer mappedInstanceDataByteBuffer;
    private FloatBuffer instanceDataView;

    private VulkanBuffer projMatrixUbo;
    private final Color batchColor = new Color();

    private boolean drawing = false;
    private final Matrix4 projectionMatrix = new Matrix4();
    private final Matrix4 transformMatrix = new Matrix4();

    private float colorPacked = Color.WHITE_FLOAT_BITS;

    private int instanceCount = 0;
    private int maxInstancesPerBatch;

    public int renderCalls = 0;

    public static final VertexAttributes INSTANCE_VERTEX_ATTRIBUTES = new VertexAttributes(
            new VertexAttribute(VertexAttributes.Usage.Position, 2, ShaderProgram.POSITION_ATTRIBUTE, 0),
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE + "0", 0),
            new VertexAttribute(VertexAttributes.Usage.Generic, 2, GL20.GL_FLOAT, false, "instance_worldPos", 1),
            new VertexAttribute(VertexAttributes.Usage.Generic, 2, GL20.GL_FLOAT, false, "instance_size", 1),
            new VertexAttribute(VertexAttributes.Usage.Generic, 2, GL20.GL_FLOAT, false, "instance_origin", 1),
            new VertexAttribute(VertexAttributes.Usage.Generic, 2, GL20.GL_FLOAT, false, "instance_scale", 1),
            new VertexAttribute(VertexAttributes.Usage.Generic, 1, GL20.GL_FLOAT, false, "instance_rotation", 1),
            new VertexAttribute(VertexAttributes.Usage.Generic, 4, GL20.GL_FLOAT, false, "instance_uvRegion", 1),
            new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, ShaderProgram.COLOR_ATTRIBUTE, 1),
            new VertexAttribute(VertexAttributes.Usage.Generic, 1, GL20.GL_FLOAT, false, "instance_texArrayIndex", 1)
    );

    private long batchPipelineLayout = VK_NULL_HANDLE;
    private long currentPipeline = VK_NULL_HANDLE;

    private boolean blendingEnabled = true;
    private int blendSrcFunc = GL20.GL_SRC_ALPHA;
    private int blendDstFunc = GL20.GL_ONE_MINUS_SRC_ALPHA;
    private int blendSrcFuncAlpha = GL20.GL_SRC_ALPHA;
    private int blendDstFuncAlpha = GL20.GL_ONE_MINUS_SRC_ALPHA;
    private boolean blendFuncSeparate = false;

    public VulkanSpriteBatchInstanced(int maxSpritesInFlight) {
        if (!(Gdx.app instanceof VulkanApplication)) {
            throw new GdxRuntimeException("VulkanSpriteBatchInstanced requires Gdx.app to be a VulkanApplication instance.");
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
        this.maxInstancesPerBatch = maxSpritesInFlight;

        int maxTexturesForBatcher = 256;
        maxTexturesForBatcher = Math.min(maxTexturesForBatcher, 1024);
        this.textureBatcher = new VulkanTextureBatch(descriptorManager, maxTexturesForBatcher, gfx);

        createBaseQuadMesh(device);

        long instanceBufferSizeBytes = (long) this.maxInstancesPerBatch * BYTES_PER_INSTANCE;
        this.instanceDataBuffer = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, instanceBufferSizeBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
        );
        PointerBuffer pDataVB = MemoryUtil.memAllocPointer(1);
        try {
            vkCheck(vmaMapMemory(vmaAllocator, instanceDataBuffer.getAllocationHandle(), pDataVB), "VMA Failed to map instance data buffer");
            this.mappedInstanceDataByteBuffer = MemoryUtil.memByteBuffer(pDataVB.get(0), (int) instanceBufferSizeBytes);
            this.instanceDataView = this.mappedInstanceDataByteBuffer.asFloatBuffer();
        } finally {
            MemoryUtil.memFree(pDataVB);
        }

        long uboSize = 16 * Float.BYTES;
        this.projMatrixUbo = VulkanResourceUtil.createManagedBuffer(
                vmaAllocator, uboSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
        );

        this.batchPipelineLayout = pipelineManager.getOrCreatePipelineLayout(textureBatcher.getDescriptorSetLayout());
        if (this.batchPipelineLayout == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("Failed to get/create pipeline layout for instanced batch.");
        }
    }

    private void createBaseQuadMesh(VulkanDevice device) {
        final float[] quadVertices = {
                0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 1.0f,
                1.0f, 1.0f, 1.0f, 1.0f,
                1.0f, 0.0f, 1.0f, 0.0f
        };
        final short[] quadIndices = {0, 1, 2, 2, 3, 0};

        FloatBuffer fb = BufferUtils.newFloatBuffer(quadVertices.length);
        fb.put(quadVertices).flip();
        baseQuadVertexBuffer = VulkanResourceUtil.createDeviceLocalBuffer(
                device, vmaAllocator, fb, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
        );

        ShortBuffer sb = BufferUtils.newShortBuffer(quadIndices.length);
        sb.put(quadIndices).flip();
        baseQuadIndexBuffer = VulkanResourceUtil.createDeviceLocalBuffer(
                device, vmaAllocator, sb, VK_BUFFER_USAGE_INDEX_BUFFER_BIT
        );
    }

    private void updateProjectionMatrixUBO() {
        if (projMatrixUbo == null || projMatrixUbo.getBufferHandle() == VK_NULL_HANDLE || vmaAllocator == VK_NULL_HANDLE) {
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
        instanceCount = 0;
        currentPipeline = VK_NULL_HANDLE;
        transformMatrix.idt();
        textureBatcher.resetAndPrepareForFrame();
    }

    @Override
    public void end() {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before end.");
        if (instanceCount > 0) {
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

    private void addInstanceData(VulkanTexture texture,
                                 float x, float y,
                                 float width, float height,
                                 float originX, float originY,
                                 float scaleX, float scaleY,
                                 float rotation,
                                 float u, float v, float u2, float v2) {
        if (!drawing) throw new IllegalStateException("Batch.begin must be called before draw methods.");

        if (instanceCount >= maxInstancesPerBatch) {
            flush();
        }

        VulkanTexture texToUse = texture;
        if (texToUse == null) {
            texToUse = textureBatcher.getDefaultTexture();
            if (texToUse == null) {
                throw new GdxRuntimeException("Null texture provided and no default texture available in VulkanTextureBatch.");
            }
        }

        int textureDeviceIndex = textureBatcher.addTexture(texToUse);

        instanceDataView.position(instanceCount * FLOATS_PER_INSTANCE);
        instanceDataView.put(x);
        instanceDataView.put(y);
        instanceDataView.put(width);
        instanceDataView.put(height);
        instanceDataView.put(originX);
        instanceDataView.put(originY);
        instanceDataView.put(scaleX);
        instanceDataView.put(scaleY);
        instanceDataView.put(rotation);
        instanceDataView.put(u);
        instanceDataView.put(v);
        instanceDataView.put(u2);
        instanceDataView.put(v2);
        instanceDataView.put(colorPacked);
        instanceDataView.put((float) textureDeviceIndex);

        instanceCount++;
    }

    @Override
    public void draw(Texture texture, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {
        if (texture == null) return;
        float invTexWidth = 1.0f / texture.getWidth();
        float invTexHeight = 1.0f / texture.getHeight();
        float u = srcX * invTexWidth;
        float v = srcY * invTexHeight;
        float u2 = (srcX + srcWidth) * invTexWidth;
        float v2 = (srcY + srcHeight) * invTexHeight;

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

        addInstanceData((VulkanTexture) texture, x, y, width, height, originX, originY, scaleX, scaleY, rotation, u, v, u2, v2);
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {
        draw(texture, x, y, 0f, 0f, width, height, 1f, 1f, 0f, srcX, srcY, srcWidth, srcHeight, flipX, flipY);
    }

    @Override
    public void draw(Texture texture, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {
        draw(texture, x, y, (float) srcWidth, (float) srcHeight, srcX, srcY, srcWidth, srcHeight, false, false);
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, float u, float v, float u2, float v2) {
        addInstanceData((VulkanTexture) texture, x, y, width, height, 0f, 0f, 1f, 1f, 0f, u, v, u2, v2);
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

        // This is the path often taken by Sprite.draw() for non-rotated/non-scaled sprites,
        // and by BitmapFontCache.
        // spriteVertices typically contains 4 vertices, 5 floats each: x, y, color, u, v
        // Total count is usually 20.
        final int floatsPerInputVertex = 5;
        if (count % (floatsPerInputVertex * 4) != 0) { // Must be a multiple of 4 vertices (1 sprite)
            Gdx.app.error(TAG, "draw(Texture, float[], ...) called with unexpected vertex count: " + count + ". Expected a multiple of " + (floatsPerInputVertex * 4));
            return;
        }

        int numSprites = count / (floatsPerInputVertex * 4);

        for (int i = 0; i < numSprites; i++) {
            int currentOffset = offset + i * (floatsPerInputVertex * 4);

            // Extract properties assuming a simple quad structure without prior rotation/scale
            // This is a simplification. If spriteVertices are arbitrarily transformed, this deconstruction is insufficient.
            float x1 = spriteVertices[currentOffset + 0]; // Bottom-left x
            float y1 = spriteVertices[currentOffset + 1]; // Bottom-left y
            float c = spriteVertices[currentOffset + 2];  // Color (packed)
            float u1 = spriteVertices[currentOffset + 3]; // UV bottom-left u
            float v1 = spriteVertices[currentOffset + 4]; // UV bottom-left v

            // float x2 = spriteVertices[currentOffset + 5]; // Top-left x
            float y2 = spriteVertices[currentOffset + 6]; // Top-left y
            // float c2 = spriteVertices[currentOffset + 7];
            // float u2_ = spriteVertices[currentOffset + 8]; // Should be same as u1
            // float v2_ = spriteVertices[currentOffset + 9]; // UV top-left v

            float x3 = spriteVertices[currentOffset + 10]; // Top-right x
            // float y3 = spriteVertices[currentOffset + 11]; // Top-right y
            // float c3 = spriteVertices[currentOffset + 12];
            float u2 = spriteVertices[currentOffset + 13]; // UV top-right u
            float v2 = spriteVertices[currentOffset + 14]; // UV top-right v

            // For a simple, unrotated, unscaled quad from Sprite.draw():
            // worldPos is the bottom-left corner
            float worldX = x1;
            float worldY = y1;

            // width and height
            float spriteWidth = x3 - x1; // x of top-right - x of bottom-left
            float spriteHeight = y2 - y1; // y of top-left - y of bottom-left

            // Default origin, scale, rotation for this simplified path
            float originX = 0f;
            float originY = 0f;
            float scaleX = 1f;
            float scaleY = 1f;
            float rotation = 0f;

            // UVs (ensure correct winding, Sprite typically provides them ready for a quad)
            // Assuming: u1,v1 = bottom-left; u1,v2 = top-left; u2,v2 = top-right; u2,v1 = bottom-right
            // The instance_uvRegion expects u,v (top-left of region) and u2,v2 (bottom-right of region)
            // For Sprite.draw, the incoming v1 is usually bottom-v, v2 is top-v.
            // Our shader expects u,v (top-left of region) and u2,v2 (bottom-right of region)
            // So, if spriteVertices are:
            // V0 (x1,y1) color (u,v)   -- BL
            // V1 (x,y+h) color (u,v2)  -- TL
            // V2 (x+w,y+h) color (u2,v2)-- TR
            // V3 (x+w,y) color (u2,v)  -- BR
            // Then:
            // instance_uvRegion.x = u (from V0/V1)
            // instance_uvRegion.y = v2 (from V1/V2 - top v)
            // instance_uvRegion.z = u2 (from V2/V3)
            // instance_uvRegion.w = v (from V0/V3 - bottom v)
            // This needs careful mapping based on how Sprite.java generates its vertices.
            // Let's assume the input u,v are for the region's top-left, and u2,v2 for bottom-right.
            // Sprite.java vertices:
            // idx 0: x1, y1, color, u, v2  (bottom left, but with top-v)
            // idx 1: x1, y2, color, u, v   (top left, with bottom-v)
            // idx 2: x2, y2, color, u2, v  (top right, with bottom-v)
            // idx 3: x2, y1, color, u2, v2 (bottom right, with top-v)
            // Our shader expects uvRegion: u_topLeft, v_topLeft, u_bottomRight, v_bottomRight
            // So, instance_uvRegion.x = spriteVertices[currentOffset + 3] (u)
            //     instance_uvRegion.y = spriteVertices[currentOffset + 9] (v of top-left vertex)
            //     instance_uvRegion.z = spriteVertices[currentOffset + 13] (u2)
            //     instance_uvRegion.w = spriteVertices[currentOffset + 4] (v2 of bottom-left vertex)

            float u_param = spriteVertices[currentOffset + 3];  // u from first vertex
            float v_param = spriteVertices[currentOffset + 9];  // v from second vertex (top-left v)
            float u2_param = spriteVertices[currentOffset + 13]; // u2 from third vertex
            float v2_param = spriteVertices[currentOffset + 4];  // v2 from first vertex (bottom-left v)


            setPackedColor(c); // Use the color from the vertices
            addInstanceData((VulkanTexture) texture, worldX, worldY, spriteWidth, spriteHeight,
                    originX, originY, scaleX, scaleY, rotation,
                    u_param, v_param, u2_param, v2_param);
        }
        // Restore original batch color if it was changed by setPackedColor
        // This is tricky as the global colorPacked is used by other draw calls.
        // For now, assume color is consistent or set per draw call group.
    }


    @Override
    public void draw(TextureRegion region, float x, float y) {
        if (region == null) return;
        draw(region, x, y, region.getRegionWidth(), region.getRegionHeight());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float width, float height) {
        if (region == null) return;
        addInstanceData((VulkanTexture) region.getTexture(), x, y, width, height,
                0f, 0f, 1f, 1f, 0f,
                region.getU(), region.getV(), region.getU2(), region.getV2());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation) {
        if (region == null) return;
        addInstanceData((VulkanTexture) region.getTexture(), x, y, width, height,
                originX, originY, scaleX, scaleY, rotation,
                region.getU(), region.getV(), region.getU2(), region.getV2());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation, boolean clockwise) {
        draw(region, x, y, originX, originY, width, height, scaleX, scaleY, rotation);
    }

    @Override
    public void draw(TextureRegion region, float width, float height, Affine2 transform) {
        if (region == null) return;
        float x = transform.m02;
        float y = transform.m12;
        float scaleX = (float) Math.sqrt(transform.m00 * transform.m00 + transform.m10 * transform.m10);
        float scaleY = (float) Math.sqrt(transform.m01 * transform.m01 + transform.m11 * transform.m11);
        float rotation = MathUtils.atan2(transform.m10, transform.m00) * MathUtils.radiansToDegrees;

        addInstanceData((VulkanTexture) region.getTexture(), x, y, width, height,
                0f, 0f,
                scaleX, scaleY, rotation,
                region.getU(), region.getV(), region.getU2(), region.getV2());
    }

    @Override
    public void dispose() {
        if (Gdx.app instanceof VulkanApplication) {
            VulkanGraphics gfx = (VulkanGraphics) Gdx.app.getGraphics();
            if (gfx != null) {
                gfx.unregisterFrameResourcePreparer(this);
            }
        }

        if (textureBatcher != null) {
            textureBatcher.dispose();
            textureBatcher = null;
        }
        if (baseQuadVertexBuffer != null) {
            baseQuadVertexBuffer.dispose();
            baseQuadVertexBuffer = null;
        }
        if (baseQuadIndexBuffer != null) {
            baseQuadIndexBuffer.dispose();
            baseQuadIndexBuffer = null;
        }
        if (instanceDataBuffer != null) {
            if (mappedInstanceDataByteBuffer != null && instanceDataBuffer.getAllocationHandle() != VK_NULL_HANDLE && vmaAllocator != VK_NULL_HANDLE) {
                try {
                    vmaUnmapMemory(vmaAllocator, instanceDataBuffer.getAllocationHandle());
                } catch (Exception e) {
                    Gdx.app.error(TAG, "Error unmapping instance data buffer", e);
                }
            }
            instanceDataBuffer.dispose();
            instanceDataBuffer = null;
            mappedInstanceDataByteBuffer = null;
            instanceDataView = null;
        }
        if (projMatrixUbo != null) {
            projMatrixUbo.dispose();
            projMatrixUbo = null;
        }
        batchPipelineLayout = VK_NULL_HANDLE;
        currentPipeline = VK_NULL_HANDLE;
    }

    @Override
    public void flush() {
        if (instanceCount == 0) return;

        // Grab current Vulkan command buffer & render pass
        VulkanGraphics gfx = (VulkanGraphics) Gdx.app.getGraphics();
        VkCommandBuffer cmd = gfx.getCurrentCommandBuffer();
        long currentRenderPass = gfx.getCurrentRenderPassHandle();

        if (cmd == null || currentRenderPass == VK_NULL_HANDLE || batchPipelineLayout == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Flush error: Invalid Vulkan context.");
            instanceCount = 0;
            return;
        }

        // First bind all textures & UBOs
        textureBatcher.buildAndBind(cmd, batchPipelineLayout, projMatrixUbo);

        // Ensure our instance buffer is visible to the GPU
        long bytesToFlush = (long)instanceCount * BYTES_PER_INSTANCE;
        if (bytesToFlush > 0 && instanceDataBuffer.getAllocationHandle() != VK_NULL_HANDLE && vmaAllocator != VK_NULL_HANDLE) {
            Vma.vmaFlushAllocation(vmaAllocator, instanceDataBuffer.getAllocationHandle(), 0, bytesToFlush);
        }

        // Acquire (or create) the instanced pipeline for this render pass + blend state
        long pipelineToUse = pipelineManager.getOrCreateSpriteBatchInstancedPipeline(
                batchPipelineLayout, currentRenderPass,
                blendingEnabled, blendSrcFunc, blendDstFunc,
                blendSrcFuncAlpha, blendDstFuncAlpha, blendFuncSeparate
        );
        if (pipelineToUse == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Failed to get or create instanced sprite batch pipeline.");
            instanceCount = 0;
            return;
        }

        // Bind pipeline if it changed
        if (pipelineToUse != currentPipeline) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineToUse);
            currentPipeline = pipelineToUse;

            // *** DYNAMIC STATE: viewport & scissor ***
            try ( MemoryStack stack = stackPush() ) {
                // Full‐frame viewport
                VkViewport.Buffer vp = VkViewport.calloc(1, stack)
                        .x(0f).y(0f)
                        .width((float)Gdx.graphics.getBackBufferWidth())
                        .height((float)Gdx.graphics.getBackBufferHeight())
                        .minDepth(0f).maxDepth(1f);
                vkCmdSetViewport(cmd, 0, vp);

                // Full‐frame scissor
                VkRect2D.Buffer sc = VkRect2D.calloc(1, stack)
                        .offset(VkOffset2D.calloc(stack).set(0, 0))
                        .extent(VkExtent2D.calloc(stack)
                                .width(Gdx.graphics.getBackBufferWidth())
                                .height(Gdx.graphics.getBackBufferHeight()));
                vkCmdSetScissor(cmd, 0, sc);
            }
        }

        // Bind our two vertex buffers: quad‐vertex + per‐instance data
        try ( MemoryStack stack = stackPush() ) {
            LongBuffer pBuffers = stack.longs(
                    baseQuadVertexBuffer.getBufferHandle(),
                    instanceDataBuffer.getBufferHandle()
            );
            LongBuffer pOffsets = stack.longs(0L, 0L);
            vkCmdBindVertexBuffers(cmd, 0, pBuffers, pOffsets);
            vkCmdBindIndexBuffer(cmd, baseQuadIndexBuffer.getBufferHandle(), 0, VK_INDEX_TYPE_UINT16);
        }

        // Issue the instanced draw
        vkCmdDrawIndexed(cmd, BASE_QUAD_INDICES_COUNT, instanceCount, 0, 0, 0);
        renderCalls++;
        instanceCount = 0;
    }

    /*public void flush() {
        if (instanceCount == 0) return;

        VulkanGraphics gfx = null;
        if (Gdx.app instanceof VulkanApplication) {
            gfx = (VulkanGraphics) Gdx.app.getGraphics();
        }
        if (gfx == null) {
            Gdx.app.error(TAG, "Flush error: VulkanGraphics context is null!");
            instanceCount = 0;
            return;
        }

        VkCommandBuffer currentCommandBuffer = gfx.getCurrentCommandBuffer();
        long currentRenderPassHandle = gfx.getCurrentRenderPassHandle();

        if (currentCommandBuffer == null || currentRenderPassHandle == VK_NULL_HANDLE || batchPipelineLayout == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Flush error: Invalid Vulkan context.");
            instanceCount = 0;
            return;
        }

        textureBatcher.buildAndBind(currentCommandBuffer, batchPipelineLayout, projMatrixUbo);

        long bytesToFlush = (long) instanceCount * BYTES_PER_INSTANCE;
        if (bytesToFlush > 0 && instanceDataBuffer.getAllocationHandle() != VK_NULL_HANDLE && vmaAllocator != VK_NULL_HANDLE) {
            Vma.vmaFlushAllocation(vmaAllocator, instanceDataBuffer.getAllocationHandle(), 0, bytesToFlush);
        }

        long pipelineToUse = pipelineManager.getOrCreateSpriteBatchInstancedPipeline(
                batchPipelineLayout, currentRenderPassHandle,
                blendingEnabled, blendSrcFunc, blendDstFunc,
                blendSrcFuncAlpha, blendDstFuncAlpha, blendFuncSeparate
        );

        if (pipelineToUse == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Failed to get or create instanced sprite batch pipeline.");
            instanceCount = 0;
            return;
        }
        if (pipelineToUse != currentPipeline) {
            vkCmdBindPipeline(currentCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineToUse);
            currentPipeline = pipelineToUse;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            //LongBuffer pVertexBuffers = stack.longs(baseQuadVertexBuffer.getBufferHandle(), instanceDataBuffer.getBufferHandle());
            LongBuffer buffers = stack.longs(
                    baseQuadVertexBuffer.getBufferHandle(),
                    instanceDataBuffer.getBufferHandle()
            );
            LongBuffer pOffsets = stack.longs(0L, 0L);
            //vkCmdBindVertexBuffers(currentCommandBuffer, 0, pVertexBuffers, pOffsets);
            vkCmdBindVertexBuffers(currentCommandBuffer, 0, buffers, pOffsets);

            vkCmdBindIndexBuffer(currentCommandBuffer, baseQuadIndexBuffer.getBufferHandle(), 0, VK_INDEX_TYPE_UINT16);

        }

        vkCmdDrawIndexed(currentCommandBuffer, BASE_QUAD_INDICES_COUNT, instanceCount, 0, 0, 0);
        renderCalls++;
        instanceCount = 0;
    }*/

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
        if (!projectionMatrix.equals(projection)) {
            if (drawing && instanceCount > 0) flush();
            this.projectionMatrix.set(projection);
        }
    }

    @Override
    public void setTransformMatrix(Matrix4 transform) {
        if (transform == null) throw new IllegalArgumentException("Transform matrix cannot be null.");
        if (!transformMatrix.equals(transform)) {
            if (drawing && instanceCount > 0) flush();
            this.transformMatrix.set(transform);
        }
    }

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
        updateProjectionMatrixUBO();
        if (textureBatcher != null) {
            // No longer call updateDescriptorSetIfNeeded here.
            // All descriptor set updates (UBO and textures) are now consolidated
            // within textureBatcher.buildAndBind(), which is called by flush().
        }
    }
}
