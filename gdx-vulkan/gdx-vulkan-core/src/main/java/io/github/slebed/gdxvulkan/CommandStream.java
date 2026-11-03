package io.github.slebed.gdxvulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The command stream (plan, Phase 2): packets of int32 words in a direct buffer, replayed natively by one call per flush. Layout and
 * opcodes mirror {@code jni/src/gdxvk_commands.h}. Viewport and scissor rectangles use OpenGL conventions: pixels, origin at the
 * bottom-left of the target.
 *
 * <p>Allocation-free once the buffer has grown to the frame's size; {@link #clear()} rewinds it.
 */
public final class CommandStream {
    public static final int BIND_PIPELINE = 1, PUSH_CONSTANTS = 2, VIEWPORT = 3, SCISSOR = 4, SCISSOR_ENABLE = 5, CLEAR = 6, BIND_VERTEX = 7,
        BIND_INDEX_BUFFER = 8, BIND_INDEX_RING = 9, BIND_TEXTURE = 10, DRAW_INDEXED = 11, DRAW = 12, BIND_STORAGE = 13, BIND_TARGET = 14, DEPTH_STATE = 15, STENCIL_STATE = 16;
    public static final int INDEX_U16 = 0, INDEX_U32 = 1;

    private ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
    private int words;

    private void ensure(int more) {
        if ((words + more) * 4 <= buffer.capacity()) return;
        ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(buffer.capacity() * 2, (words + more) * 4)).order(ByteOrder.LITTLE_ENDIAN);
        buffer.limit(words * 4);
        buffer.position(0);
        bigger.put(buffer);
        buffer = bigger;
    }

    private void put(int v) {
        buffer.putInt(words * 4, v);
        words++;
    }

    private void putF(float v) {
        put(Float.floatToRawIntBits(v));
    }

    public void bindPipeline(int id) {
        ensure(2);
        put(BIND_PIPELINE);
        put(id);
    }

    /** @param block32 the whole 128-byte push-constant block, as 32 floats */
    public void pushConstants(float[] block32) {
        ensure(33);
        put(PUSH_CONSTANTS);
        for (int i = 0; i < 32; i++) putF(block32[i]);
    }

    public void viewport(int x, int y, int w, int h) {
        ensure(5);
        put(VIEWPORT);
        put(x);
        put(y);
        put(w);
        put(h);
    }

    public void scissor(int x, int y, int w, int h) {
        ensure(5);
        put(SCISSOR);
        put(x);
        put(y);
        put(w);
        put(h);
    }

    public void scissorEnable(boolean on) {
        ensure(2);
        put(SCISSOR_ENABLE);
        put(on ? 1 : 0);
    }

    /**
     * @param glMask GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT. Depth and stencil are ignored on a target that has
     *           none (the swapchain), as in GL. A full clear that starts a pass becomes the pass's load op.
     */
    public void clear(int glMask, float r, float g, float b, float a, float depth, int stencil) {
        ensure(8);
        put(CLEAR);
        put(glMask);
        putF(r);
        putF(g);
        putF(b);
        putF(a);
        putF(depth);
        put(stencil);
    }

    /** OpenGL depth state: test enable, write mask and compare function (a GL enum, GL_LESS etc.). */
    public void depthState(boolean test, boolean write, int glFunc) {
        ensure(4);
        put(DEPTH_STATE);
        put(test ? 1 : 0);
        put(write ? 1 : 0);
        put(glFunc);
    }

    /** OpenGL stencil state, front and back alike. Operations and the function are GL enums. */
    public void stencilState(boolean test, int glFunc, int ref, int compareMask, int writeMask, int glFail, int glDepthFail, int glPass) {
        ensure(9);
        put(STENCIL_STATE);
        put(test ? 1 : 0);
        put(glFunc);
        put(ref);
        put(compareMask);
        put(writeMask);
        put(glFail);
        put(glDepthFail);
        put(glPass);
    }

    public void bindVertex(int block, long offset) {
        ensure(4);
        put(BIND_VERTEX);
        put(block);
        put((int)offset);
        put((int)(offset >>> 32));
    }

    public void bindIndexBuffer(int resourceBuffer, long offset, int indexType) {
        ensure(5);
        put(BIND_INDEX_BUFFER);
        put(resourceBuffer);
        put((int)offset);
        put((int)(offset >>> 32));
        put(indexType);
    }

    public void bindIndexRing(int block, long offset, int indexType) {
        ensure(5);
        put(BIND_INDEX_RING);
        put(block);
        put((int)offset);
        put((int)(offset >>> 32));
        put(indexType);
    }

    /** Tier B binds the texture's descriptor set; on Tier A this is a no-op (the slot travels in the vertices). */
    public void bindTexture(int nativeTexture) {
        ensure(2);
        put(BIND_TEXTURE);
        put(nativeTexture);
    }

    /** Set 0's storage binding reads the whole resource buffer for the draws that follow (instanced sprites, compute-driven data). */
    public void bindStorage(int resourceBuffer) {
        ensure(2);
        put(BIND_STORAGE);
        put(resourceBuffer);
    }

    /**
     * Draws go to this render-target texture (a native texture id) from here on, or to the swapchain with -1. The open pass ends; the
     * next one starts at the first draw or clear, and a full clear that starts it becomes its load op. The depth image (from
     * {@code GdxVkResources.createDepthImage}) may be -1.
     */
    public void bindTarget(int nativeTextureOrMinus1, int depthImageOrMinus1) {
        ensure(3);
        put(BIND_TARGET);
        put(nativeTextureOrMinus1);
        put(depthImageOrMinus1);
    }

    public void drawIndexed(int indexCount, int firstIndex, int vertexOffset) {
        ensure(4);
        put(DRAW_INDEXED);
        put(indexCount);
        put(firstIndex);
        put(vertexOffset);
    }

    public void draw(int vertexCount, int firstVertex) {
        ensure(3);
        put(DRAW);
        put(vertexCount);
        put(firstVertex);
    }

    public boolean isEmpty() { return words == 0; }

    public int sizeInBytes() { return words * 4; }

    public void clear() { words = 0; }

    ByteBuffer buffer() { return buffer; }
}
