package io.github.slebed.gdxvulkan;

/**
 * GPU buffers and 2D textures, thread-safe. Uploads are staged and recorded at the start of the next frame (or by
 * {@link #flushUploads()}); disposals are deferred until the GPU has finished with the resource, so {@code destroy*} is safe to
 * call at any time from any thread.
 *
 * <p>Ids are small integers, valid until destroyed (then reused). The native layer is the base the GL20 shim sits on in Phase 2.
 */
public final class GdxVkResources {
    /** A snapshot of resource accounting. The soak test requires these to stay flat. */
    public static final class Stats {
        public final int buffersLive, texturesLive, slotsInUse, samplers, stagingBlocks;
        public final long bufferBytesLive, poolBytes, retiredPending, totalRetired, vmaBlockBytes, vmaAllocationBytes, completedSerial, textureRenames;

        Stats(long[] v) {
            buffersLive = (int)v[0];
            texturesLive = (int)v[1];
            slotsInUse = (int)v[2];
            samplers = (int)v[3];
            stagingBlocks = (int)v[4];
            bufferBytesLive = v[5];
            poolBytes = v[6];
            retiredPending = v[7];
            totalRetired = v[8];
            vmaBlockBytes = v[9];
            vmaAllocationBytes = v[10];
            completedSerial = v[11];
            textureRenames = v[12];
        }

        @Override public String toString() {
            return "buffers=" + buffersLive + " (" + bufferBytesLive + " B) textures=" + texturesLive + " slots=" + slotsInUse
                + " samplers=" + samplers + " staging=" + stagingBlocks + " retiredPending=" + retiredPending + " totalRetired="
                + totalRetired + " vmaBlock=" + vmaBlockBytes + " vmaAlloc=" + vmaAllocationBytes + " renames=" + textureRenames;
        }
    }

    private final GdxVkContext context;

    GdxVkResources(GdxVkContext context) {
        this.context = context;
    }

    /** @return a buffer id; the buffer's bytes are device-local and written with {@link #writeBuffer} */
    public int createBuffer(long size) {
        int id = GdxVkNative.nativeCreateBuffer(context.nativeHandle(), size);
        if (id < 0) throw new VulkanOutOfMemoryException("Could not allocate a " + size + " byte buffer");
        return id;
    }

    public void writeBuffer(int id, long offset, byte[] data, int start, int length) {
        if (!GdxVkNative.nativeWriteBuffer(context.nativeHandle(), id, offset, data, start, length)) {
            throw new IllegalArgumentException("writeBuffer failed: no such buffer " + id + " or range out of bounds");
        }
    }

    /** Writes {@code length} bytes starting at {@code start} of a direct buffer (its position and limit are ignored). */
    public void writeBuffer(int id, long offset, java.nio.ByteBuffer direct, int start, int length) {
        if (!direct.isDirect()) throw new IllegalArgumentException("writeBuffer needs a direct ByteBuffer");
        if (!GdxVkNative.nativeWriteBufferDirect(context.nativeHandle(), id, offset, direct, start, length)) {
            throw new IllegalArgumentException("writeBuffer failed: no such buffer " + id + " or range out of bounds");
        }
    }

    /**
     * Copies a range of a buffer back to the host, waiting for the GPU: for tests and diagnostics (it stalls). Everything recorded before it,
     * including a finished frame's compute work, is visible in the result.
     */
    public void readBuffer(int id, long offset, byte[] out, int start, int length) {
        if (!GdxVkNative.nativeReadBuffer(context.nativeHandle(), id, offset, out, start, length)) {
            throw new IllegalArgumentException("readBuffer failed: no such buffer " + id + " or range out of bounds");
        }
    }

    public void destroyBuffer(int id) {
        GdxVkNative.nativeDestroyBuffer(context.nativeHandle(), id);
    }

    /** R8G8B8A8, 4 bytes per texel. */
    public static final int FORMAT_RGBA8 = 0;
    /** R8, 1 byte per texel, sampled as (0, 0, 0, r): GL_ALPHA. */
    public static final int FORMAT_ALPHA = 1;
    /** R8G8, 2 bytes per texel, sampled as (r, r, r, g): GL_LUMINANCE_ALPHA. */
    public static final int FORMAT_LUMINANCE_ALPHA = 2;

    /** A block-compressed image; the Vulkan format and block geometry travel separately (see {@link #createCompressedTexture}). */
    public static final int FORMAT_COMPRESSED = 3;

    /** RGBA8 that can also be rendered into (a framebuffer's colour texture): one level, never renamed. Passed to createTexture like FORMAT_RGBA8. */
    public static final int FORMAT_RENDER_TARGET = 4;

    /** RGBA with 16-bit float channels: a render target that keeps values outside 0..1 (HDR), sampled like any texture. Targets only. */
    public static final int FORMAT_RGBA16F = 5;

    /** RGBA with 32-bit float channels, a render target; the device may only sample it with nearest filtering. Targets only. */
    public static final int FORMAT_RGBA32F = 6;

    /**
     * A block-compressed texture: {@code levels} mip levels of {@code vkFormat}, tightly packed, level 0 first. Throws if the device
     * cannot sample the format (BC on desktop GPUs, ETC2 and ASTC on mobile ones; check {@link #formatSupported}).
     *
     * @param blockBytes bytes per blockW x blockH block
     */
    public int createCompressedTexture(int width, int height, int vkFormat, int blockW, int blockH, int blockBytes, int levels, byte[] data,
        GdxVkSampler sampler) {
        int id = GdxVkNative.nativeCreateCompressedTexture(context.nativeHandle(), width, height, vkFormat, blockW, blockH, blockBytes, levels, data,
            sampler.minFilter, sampler.magFilter, sampler.mipFilter, sampler.wrapU, sampler.wrapV, sampler.anisotropy);
        if (id < 0) {
            throw new VulkanOutOfMemoryException("Could not create a " + width + "x" + height + " compressed texture (VkFormat " + vkFormat
                + " unsupported by this device, table full, or bad arguments)");
        }
        return id;
    }

    /** Can this device sample images of the given VkFormat (with linear filtering)? */
    public boolean formatSupported(int vkFormat) {
        return GdxVkNative.nativeFormatSupported(context.nativeHandle(), vkFormat);
    }

    public static int bytesPerTexel(int format) {
        return format == FORMAT_RGBA8 ? 4 : format == FORMAT_LUMINANCE_ALPHA ? 2 : format == FORMAT_RGBA16F ? 8 : format == FORMAT_RGBA32F ? 16 : 1;
    }

    /** @param rgba width * height * 4 bytes, RGBA8, row 0 at the top */
    public int createTexture(int width, int height, byte[] rgba, GdxVkSampler sampler) {
        return createTexture(width, height, FORMAT_RGBA8, 1, false, rgba, sampler);
    }

    /**
     * @param uploadedLevels how many mip levels {@code data} holds, tightly packed, level 0 first
     * @param generateMips build the rest of the chain on the GPU (then {@code uploadedLevels} must be 1)
     */
    public int createTexture(int width, int height, int format, int uploadedLevels, boolean generateMips, byte[] data, GdxVkSampler sampler) {
        int id = GdxVkNative.nativeCreateTexture(context.nativeHandle(), width, height, format, uploadedLevels, generateMips, data,
            sampler.minFilter, sampler.magFilter, sampler.mipFilter, sampler.wrapU, sampler.wrapV, sampler.anisotropy);
        if (id < 0) throw new VulkanOutOfMemoryException("Could not create a " + width + "x" + height + " texture (table full, or bad arguments)");
        return id;
    }

    /**
     * Rewrites a rectangle of one mip level (tightly packed in the texture's format). The texture is renamed (new image and
     * slot, old ones retired), so draws already recorded this frame still see the old contents.
     */
    public void updateTexture(int id, int level, int x, int y, int w, int h, byte[] data) {
        if (!GdxVkNative.nativeUpdateTexture(context.nativeHandle(), id, level, x, y, w, h, data)) {
            throw new IllegalArgumentException("updateTexture failed for texture " + id + " (no such texture, or region out of bounds)");
        }
    }

    /** Rebuilds mip levels 1.. from level 0 on the GPU, giving the texture a full chain. */
    public void regenerateMips(int id) {
        if (!GdxVkNative.nativeRegenerateMips(context.nativeHandle(), id)) {
            throw new IllegalArgumentException("regenerateMips failed for texture " + id + " (no such texture, or format cannot be blitted)");
        }
    }

    /** @return {width, height, levels}, or null if there is no such texture */
    public int[] textureInfo(int id) {
        return GdxVkNative.nativeTextureInfo(context.nativeHandle(), id);
    }

    /** Changes the sampler. The texture gets a new table slot or descriptor set; the old one is retired. */
    public void setSampler(int id, GdxVkSampler s) {
        if (!GdxVkNative.nativeSetTextureSampler(context.nativeHandle(), id, s.minFilter, s.magFilter, s.mipFilter, s.wrapU, s.wrapV,
            s.anisotropy)) {
            throw new IllegalArgumentException("setSampler failed for texture " + id);
        }
    }

    public void destroyTexture(int id) {
        GdxVkNative.nativeDestroyTexture(context.nativeHandle(), id);
    }

    /**
     * A depth image (plus a stencil aspect with {@code stencil}) for a framebuffer's renderbuffers. The device picks the format: D16 for 16 bits,
     * D32 otherwise, D24S8 or D32S8 with stencil. Never sampled.
     */
    public int createDepthImage(int width, int height, boolean stencil, int depthBits) {
        return createDepthImage(width, height, stencil, depthBits, false);
    }

    /** With {@code transient}: never loaded or stored between passes, and in lazily allocated (on-chip) memory where the GPU has it. */
    public int createDepthImage(int width, int height, boolean stencil, int depthBits, boolean transientDepth) {
        int id = GdxVkNative.nativeCreateDepthImage(context.nativeHandle(), width, height, stencil, depthBits, transientDepth);
        if (id < 0) throw new VulkanOutOfMemoryException("Could not create a " + width + "x" + height + " depth image");
        return id;
    }

    public void destroyDepthImage(int id) {
        GdxVkNative.nativeDestroyDepthImage(context.nativeHandle(), id);
    }

    /** The bindless slot (Tier A) or 0 (Tier B), or -1 if the texture does not exist. */
    public int slotOf(int texture) {
        return GdxVkNative.nativeTextureSlot(context.nativeHandle(), texture);
    }

    /** Records and submits queued uploads now and waits for them. Only for use without a running presenter. */
    public void flushUploads() {
        if (!GdxVkNative.nativeFlushUploads(context.nativeHandle())) throw new GdxVkException("Flushing uploads failed");
    }

    /** Runs deletions the GPU has finished with. A presenter does this every frame. @return how many ran */
    public int collectGarbage() {
        return GdxVkNative.nativeCollectGarbage(context.nativeHandle());
    }

    /**
     * Textures renamed since the context started (a pixel change after the image existed: copy-on-write, plan Core decision 4). Cheap to read,
     * unlike {@link #stats()}: {@code GdxVkFrame.getRenamesLastFrame()} uses it to give a per-frame figure for a debug overlay.
     */
    public long renameCount() {
        return GdxVkNative.nativeRenameCount(context.nativeHandle());
    }

    public Stats stats() {
        return new Stats(GdxVkNative.nativeResourceStats(context.nativeHandle()));
    }
}
