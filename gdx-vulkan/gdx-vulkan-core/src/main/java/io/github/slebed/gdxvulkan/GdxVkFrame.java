package io.github.slebed.gdxvulkan;

import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * One window's frame loop for stock-style rendering: it owns the command stream that the GL20 shim's ordered state and the batches
 * both write into, so everything replays in the order the application issued it.
 *
 * <pre>
 * if (frame.begin(0, 0, 0, 1)) {
 *     // application draws: Gdx.gl.glClear, batch.begin() ... batch.end(), Stage.draw(), ...
 *     frame.end();
 * }
 * </pre>
 * Render thread only.
 */
public final class GdxVkFrame {
    /** Quads the shared static index buffer covers (u16 indices: 4 vertices per quad). */
    public static final int MAX_QUADS = 16383;

    private final GdxVkContext context;
    private final GdxVkPresenter presenter;
    private final VulkanGL20 gl;
    private final CommandStream stream = new CommandStream();
    private final boolean tierA;
    private final Map<String, Integer> pipelines = new HashMap<String, Integer>();
    private int quadIndexBuffer = -1;
    private long frameNumber;
    private long cachedFrame = -1;
    private final ByteBuffer[] blockCache = new ByteBuffer[8];
    private boolean open;

    public GdxVkFrame(GdxVkContext context, GdxVkPresenter presenter, VulkanGL20 gl) {
        this.context = context;
        this.presenter = presenter;
        this.gl = gl;
        this.tierA = context.caps().tier == VulkanCaps.Tier.A;
        gl.setCommandStream(stream);
    }

    /** Starts a frame cleared to the colour. @return false if there is nothing to draw into (minimized); skip the frame. */
    public boolean begin(float r, float g, float b, float a) {
        if (open) throw new IllegalStateException("begin called twice without end");
        if (!presenter.beginFrame(r, g, b, a)) return false;
        open = true;
        frameNumber++;
        stream.clear();
        gl.setCommandStream(stream);
        gl.replayState();
        return true;
    }

    /** Replays what the application issued, then submits and presents. */
    public void end() {
        presenter.submit(stream);
        presenter.endFrame();
        open = false;
    }

    /** Like {@link #end()}, returning the frame as RGBA8 (row 0 at the top). For tests and goldens. */
    public byte[] endAndCapture() {
        presenter.submit(stream);
        byte[] px = presenter.endFrameAndCapture();
        open = false;
        return px;
    }

    /** Replays the stream recorded so far without ending the frame. */
    public void flush() {
        presenter.submit(stream);
    }

    public CommandStream stream() { return stream; }

    public GdxVkPresenter presenter() { return presenter; }

    public GdxVkContext context() { return context; }

    public VulkanGL20 gl() { return gl; }

    public boolean isTierA() { return tierA; }

    public int width() { return presenter.width(); }

    public int height() { return presenter.height(); }

    public boolean isOpen() { return open; }

    /** The mapped memory of a vertex block of this frame; the same buffer object is returned until the frame ends. */
    public ByteBuffer vertexBlock(int block) {
        if (cachedFrame != frameNumber) {
            java.util.Arrays.fill(blockCache, null); // blocks are reorganised between frames
            cachedFrame = frameNumber;
        }
        if (block >= blockCache.length) return presenter.vertexBlock(block);
        ByteBuffer b = blockCache[block];
        if (b == null) b = blockCache[block] = presenter.vertexBlock(block);
        return b;
    }

    /** A device-local u16 index buffer holding the quad pattern 0,1,2,2,3,0 for {@link #MAX_QUADS} quads, created on first use. */
    public int quadIndexBuffer() {
        if (quadIndexBuffer < 0) {
            ByteBuffer idx = ByteBuffer.allocate(MAX_QUADS * 6 * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int q = 0, v = 0; q < MAX_QUADS; q++, v += 4) {
                idx.putShort((short)v).putShort((short)(v + 1)).putShort((short)(v + 2));
                idx.putShort((short)(v + 2)).putShort((short)(v + 3)).putShort((short)v);
            }
            GdxVkResources res = context.resources();
            quadIndexBuffer = res.createBuffer(idx.capacity());
            res.writeBuffer(quadIndexBuffer, 0, idx.array(), 0, idx.capacity());
        }
        return quadIndexBuffer;
    }

    /**
     * A pipeline by key, created on first use. {@code vertName}/{@code fragName} are shader resources ({@code name.vert}); the Tier A or
     * Tier B build is chosen to match the device.
     */
    public int pipeline(String key, String name, VulkanShaderPipelineBundle bundle, GdxVkPipelineDesc desc) {
        Integer id = pipelines.get(key);
        if (id == null) {
            id = presenter.createPipeline(name, bundle.vertex(tierA), bundle.fragment(tierA), desc);
            pipelines.put(key, id);
        }
        return id;
    }

    public int pipeline(String key, String name, String vertName, String fragName, GdxVkPipelineDesc desc) {
        Integer id = pipelines.get(key);
        if (id == null) {
            id = presenter.createPipeline(name, GdxVkShaders.load(vertName, tierA), GdxVkShaders.load(fragName, tierA), desc);
            pipelines.put(key, id);
        }
        return id;
    }
}
