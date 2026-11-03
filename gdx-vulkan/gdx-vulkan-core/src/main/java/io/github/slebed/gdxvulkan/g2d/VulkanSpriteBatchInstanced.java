package io.github.slebed.gdxvulkan.g2d;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
import io.github.slebed.gdxvulkan.GdxVkResources;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Up to millions of identical sprites, moved on the GPU (plan, Phase 2). The sprites live in one device-local buffer (four floats each:
 * x, y of the lower-left corner and vx, vy); {@link #update} queues a compute dispatch that moves them and bounces them off the bounds,
 * and {@link #draw} expands them into quads in the vertex shader, so the CPU touches no per-sprite data after {@link #setSprites}.
 *
 * <p>All sprites share one texture (the whole of it), one size and one tint. For per-sprite variety use {@link VulkanSpriteBatch}.
 * Call {@code update} and {@code draw} between {@code frame.begin} and {@code frame.end}; the compute work runs at the start of the frame's
 * GPU work, so {@code draw} in the same frame sees the moved sprites regardless of call order.
 */
public final class VulkanSpriteBatchInstanced implements Disposable {
    private static final int SPRITE_BYTES = 16, GROUP = 256;

    private final GdxVkFrame frame;
    private final GdxVkResources resources;
    private final int capacity;
    private int buffer;
    private int count;
    private Texture texture;
    private float spriteWidth = 16, spriteHeight = 16, boundsWidth = 1, boundsHeight = 1, gravity;
    private final Color color = new Color(1, 1, 1, 1);
    private final Matrix4 projectionMatrix = new Matrix4();
    private final float[] push = new float[32];
    private boolean disposed;

    /** @param capacity the most sprites this batch can hold; the buffer is {@code 16 * capacity} bytes */
    public VulkanSpriteBatchInstanced(GdxVkFrame frame, int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.frame = frame;
        this.resources = frame.context().resources();
        this.capacity = capacity;
        buffer = resources.createBuffer((long)capacity * SPRITE_BYTES);
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return count;
    }

    /**
     * Uploads sprite state: {@code count} sprites of four floats (x, y, vx, vy) from {@code data}, replacing the sprites from {@code first}
     * on. The batch then holds {@code first + count} sprites. Staged and copied before the next frame's compute work.
     */
    public void setSprites(int first, float[] data, int dataOffset, int count) {
        if (first < 0 || count < 0 || first + count > capacity) throw new IllegalArgumentException("sprites " + first + ".." + (first + count) + " exceed capacity " + capacity);
        if (dataOffset < 0 || dataOffset + (long)count * 4 > data.length) throw new IllegalArgumentException("data too short for " + count + " sprites");
        final int chunk = 1 << 16; // sprites per write: 1 MB
        byte[] bytes = new byte[Math.min(count, chunk) * SPRITE_BYTES];
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int done = 0; done < count; done += chunk) {
            int n = Math.min(chunk, count - done);
            bb.clear();
            bb.asFloatBuffer().put(data, dataOffset + done * 4, n * 4);
            resources.writeBuffer(buffer, (long)(first + done) * SPRITE_BYTES, bytes, 0, n * SPRITE_BYTES);
        }
        this.count = Math.max(this.count, first + count);
    }

    /** Replaces all sprites: the first {@code count} entries of {@code data} (four floats each). */
    public void setSprites(float[] data, int count) {
        this.count = 0;
        setSprites(0, data, 0, count);
    }

    /** The texture every sprite shows, in full. */
    public void setTexture(Texture texture) {
        this.texture = texture;
    }

    /** Sprite size in world units. */
    public void setSpriteSize(float width, float height) {
        spriteWidth = width;
        spriteHeight = height;
    }

    /** The rectangle {@code (0, 0, width, height)} the sprites bounce inside. */
    public void setBounds(float width, float height) {
        boundsWidth = width;
        boundsHeight = height;
    }

    /** Added to every sprite's vy each second (world units per second squared; negative pulls down). */
    public void setGravity(float gravityY) {
        gravity = gravityY;
    }

    public void setColor(float r, float g, float b, float a) {
        color.set(r, g, b, a);
    }

    public Matrix4 getProjectionMatrix() {
        return projectionMatrix;
    }

    private void fillPush() {
        System.arraycopy(projectionMatrix.val, 0, push, 0, 16);
        push[16] = color.r;
        push[17] = color.g;
        push[18] = color.b;
        push[19] = color.a;
        push[20] = Float.intBitsToFloat(count);
        push[24] = 0;
        push[25] = boundsWidth;
        push[26] = boundsHeight;
        push[27] = gravity;
        push[28] = spriteWidth;
        push[29] = spriteHeight;
    }

    /** Queues the move for this frame: every sprite advances by {@code dt} seconds. */
    public void update(float dt) {
        if (disposed) throw new IllegalStateException("disposed");
        if (count == 0) return;
        fillPush();
        push[24] = dt;
        int pipeline = frame.computePipeline("sprite_move", "sprite_move", "sprite_move.comp");
        frame.dispatch(pipeline, push, (count + GROUP - 1) / GROUP, buffer);
    }

    /** Records the draw of all sprites with the current texture, tint and projection. */
    public void draw() {
        if (disposed) throw new IllegalStateException("disposed");
        if (count == 0) return;
        if (texture == null) throw new GdxRuntimeException("VulkanSpriteBatchInstanced: no texture set");
        int nativeTexture = frame.gl().nativeTexture(texture.getTextureObjectHandle());
        if (nativeTexture < 0) throw new GdxRuntimeException("gdx-vulkan: texture has no storage (nothing was uploaded to it)");
        fillPush();
        push[21] = frame.isTierA() ? Float.intBitsToFloat(frame.gl().slotOf(nativeTexture)) : 0f;

        GdxVkPipelineDesc d = new GdxVkPipelineDesc();
        d.stride = 0; // vertices come from gl_VertexIndex and the sprite buffer
        d.cullMode = GdxVkPipelineDesc.CULL_NONE;
        d.blend = true;
        d.srcColor = d.srcAlpha = GL20.GL_SRC_ALPHA;
        d.dstColor = d.dstAlpha = GL20.GL_ONE_MINUS_SRC_ALPHA;
        int pipeline = frame.pipeline("sprite_instanced", "sprite_instanced", "sprite_instanced.vert", "sprite.frag", d);

        CommandStream s = frame.stream();
        s.bindPipeline(pipeline);
        s.pushConstants(push);
        s.bindStorage(buffer);
        if (!frame.isTierA()) s.bindTexture(nativeTexture);
        s.draw(count * 6, 0);
    }

    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        resources.destroyBuffer(buffer);
    }
}
