package io.github.slebed.gdxvulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * The command stream (plan, Phase 2): packets of int32 words in a direct buffer, replayed natively by one call per flush. Layout and
 * opcodes mirror {@code jni/src/gdxvk_commands.h}. Viewport and scissor rectangles use OpenGL conventions: pixels, origin at the
 * bottom-left of the target.
 *
 * <p>Allocation-free once the buffer has grown to the frame's size; {@link #clear()} rewinds it.
 */
public final class CommandStream {
    public static final int BIND_PIPELINE = 1, PUSH_CONSTANTS = 2, VIEWPORT = 3, SCISSOR = 4, SCISSOR_ENABLE = 5, CLEAR = 6, BIND_VERTEX = 7,
        BIND_INDEX_BUFFER = 8, BIND_INDEX_RING = 9, BIND_TEXTURE = 10, DRAW_INDEXED = 11, DRAW = 12, BIND_STORAGE = 13, BIND_TARGET = 14, DEPTH_STATE = 15, STENCIL_STATE = 16, CULL_MODE = 17, PUSH_RANGE = 18;
    public static final int INDEX_U16 = 0, INDEX_U32 = 1;

    private ByteBuffer buffer;   // the stream as the native side reads it
    private IntBuffer ints;      // a view of `buffer`, used only to copy whole blocks in
    private int words;           // words already copied into `buffer`
    // Words are written into a plain int[] first and copied into the direct buffer in bulk. Array stores are what every JIT handles best;
    // single puts through a direct buffer's view cost ~13 ns on HotSpot but ~80 ns on Android's ART.
    private int[] a = new int[16 * 1024];
    private int n;               // words in `a`, not yet in `buffer`

    public CommandStream() {
        use(ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN));
    }

    private void use(ByteBuffer b) {
        buffer = b;
        ints = b.asIntBuffer();
    }

    /** Room for `more` words in the scratch array. */
    private void ensure(int more) {
        if (n + more <= a.length) return;
        spill();
        if (more > a.length) a = new int[Math.max(more, a.length * 2)];
    }

    /** Copies the words written so far into the direct buffer, growing it if needed. */
    private void spill() {
        if (n == 0) return;
        if ((words + n) * 4 > buffer.capacity()) {
            ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(buffer.capacity() * 2, (words + n) * 4)).order(ByteOrder.LITTLE_ENDIAN);
            buffer.limit(words * 4);
            buffer.position(0);
            bigger.put(buffer);
            buffer.limit(buffer.capacity());
            bigger.clear(); // the view below starts at the buffer's position, which the copy moved
            use(bigger);
        }
        ints.position(words);
        ints.put(a, 0, n);
        words += n;
        n = 0;
    }

    private void put(int v) {
        a[n++] = v;
    }

    private void putF(float v) {
        a[n++] = Float.floatToRawIntBits(v);
    }

    public void bindPipeline(int id) {
        ensure(2);
        put(BIND_PIPELINE);
        put(id);
    }

    /** @param block32 the whole 128-byte push-constant block, as 32 floats */
    public void pushConstants(float[] block32) {
        ensure(33);
        final int[] w = a;
        int p = n;
        w[p++] = PUSH_CONSTANTS;
        for (int i = 0; i < 32; i++) w[p++] = Float.floatToRawIntBits(block32[i]);
        n = p;
    }

    /**
     * Changes {@code count} words of the push-constant block, from word {@code firstWord} (0-31), leaving the rest as it was: a draw loop that
     * changes one colour or one matrix per draw sends a few words, not 33. The native side keeps the block and pushes it, in full, once
     * before each draw that follows a change, so the GPU-facing calls are the same as with {@link #pushConstants}. The block is empty at the
     * start of every frame: send the whole of it with {@code pushConstants} first.
     */
    public void pushRange(int firstWord, float[] values, int offset, int count) {
        ensure(3 + count);
        final int[] w = a;
        int p = n;
        w[p++] = PUSH_RANGE;
        w[p++] = firstWord;
        w[p++] = count;
        for (int i = 0; i < count; i++) w[p++] = Float.floatToRawIntBits(values[offset + i]);
        n = p;
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

    /**
     * Replaces the cull mode of every pipeline from here on with a VkCullModeFlags value (0 none, 1 front, 2 back, 3 both); -1 goes back to
     * the cull mode each pipeline was created with. Dynamic state on Tier A, a pipeline switch on Tier B.
     */
    public void cullMode(int vkCullMode) {
        ensure(2);
        final int[] w = a; // locals: ART's JIT keeps these in registers where it would reload the fields after every store
        int p = n;
        w[p++] = CULL_MODE;
        w[p++] = vkCullMode;
        n = p;
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
     * Draws go to these render-target textures (native texture ids, one colour attachment each, all the same size) from here on, or to the
     * swapchain with a count of 0. The open pass ends; the next one starts at the first draw or clear, and a full clear that starts it becomes
     * its load op. The depth image (from {@code GdxVkResources.createDepthImage}) may be -1.
     */
    public void bindTarget(int[] colorTextures, int count, int depthImageOrMinus1) {
        ensure(3 + count);
        put(BIND_TARGET);
        put(count);
        for (int i = 0; i < count; i++) put(colorTextures[i]);
        put(depthImageOrMinus1);
    }

    /** The swapchain. */
    public void bindSwapchain() {
        bindTarget(NO_TEXTURES, 0, -1);
    }

    private static final int[] NO_TEXTURES = new int[0];

    public void drawIndexed(int indexCount, int firstIndex, int vertexOffset) {
        ensure(4);
        final int[] w = a;
        int p = n;
        w[p++] = DRAW_INDEXED;
        w[p++] = indexCount;
        w[p++] = firstIndex;
        w[p++] = vertexOffset;
        n = p;
    }

    public void draw(int vertexCount, int firstVertex) {
        ensure(3);
        put(DRAW);
        put(vertexCount);
        put(firstVertex);
    }

    public boolean isEmpty() { return words + n == 0; }

    public int sizeInBytes() { return (words + n) * 4; }

    public void clear() { words = 0; n = 0; }

    /** The stream as the native side reads it: everything written so far is in the buffer when this returns. */
    ByteBuffer buffer() {
        spill();
        return buffer;
    }
}
