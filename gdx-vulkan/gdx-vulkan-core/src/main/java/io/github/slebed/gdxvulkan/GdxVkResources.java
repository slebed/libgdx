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
        public final long bufferBytesLive, poolBytes, retiredPending, totalRetired, vmaBlockBytes, vmaAllocationBytes, completedSerial;

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
        }

        @Override public String toString() {
            return "buffers=" + buffersLive + " (" + bufferBytesLive + " B) textures=" + texturesLive + " slots=" + slotsInUse
                + " samplers=" + samplers + " staging=" + stagingBlocks + " retiredPending=" + retiredPending + " totalRetired="
                + totalRetired + " vmaBlock=" + vmaBlockBytes + " vmaAlloc=" + vmaAllocationBytes;
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

    public void destroyBuffer(int id) {
        GdxVkNative.nativeDestroyBuffer(context.nativeHandle(), id);
    }

    /** @param rgba width * height * 4 bytes, RGBA8, row 0 at the top */
    public int createTexture(int width, int height, byte[] rgba, GdxVkSampler sampler) {
        int id = GdxVkNative.nativeCreateTexture(context.nativeHandle(), width, height, rgba, sampler.minFilter, sampler.magFilter,
            sampler.mipFilter, sampler.wrapU, sampler.wrapV, sampler.anisotropy);
        if (id < 0) throw new VulkanOutOfMemoryException("Could not create a " + width + "x" + height + " texture (table full?)");
        return id;
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

    public Stats stats() {
        return new Stats(GdxVkNative.nativeResourceStats(context.nativeHandle()));
    }
}
