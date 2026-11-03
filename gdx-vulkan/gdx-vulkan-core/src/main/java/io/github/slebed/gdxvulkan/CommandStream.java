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
        BIND_INDEX_BUFFER = 8, BIND_INDEX_RING = 9, BIND_TEXTURE = 10, DRAW_INDEXED = 11, DRAW = 12;
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

    /** @param glMask GL_COLOR_BUFFER_BIT etc.; only the colour bit is acted on so far */
    public void clear(int glMask, float r, float g, float b, float a) {
        ensure(6);
        put(CLEAR);
        put(glMask);
        putF(r);
        putF(g);
        putF(b);
        putF(a);
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
