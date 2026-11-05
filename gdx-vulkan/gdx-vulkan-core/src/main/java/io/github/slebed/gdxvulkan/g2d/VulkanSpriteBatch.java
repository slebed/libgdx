package io.github.slebed.gdxvulkan.g2d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.VulkanShaderPipelineBundle;
import io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A {@link com.badlogic.gdx.graphics.g2d.Batch} that draws through the Vulkan command stream (plan, Phase 2). Application code is the stock
 * libGDX API; the quad-writing code is stock SpriteBatch's own (see {@link VulkanSpriteBatchDraws}).
 *
 * <p>What differs from stock:
 * <ul>
 * <li><b>Tier A</b> (bindless): changing texture does not flush. Every vertex carries its texture's slot in the global table, so a batch
 * can mix any number of textures in one draw call. A texture whose image is renamed or whose sampler changes mid-batch gets a new slot
 * for the quads after the change; quads before it keep the old contents.</li>
 * <li><b>Tier B</b>: changing texture flushes, as in stock, and each draw binds the texture's descriptor set.</li>
 * <li>Blend state is a pipeline variant, created on first use. There is no {@code ShaderProgram}: custom GLSL is replaced by Vulkan
 * pipelines (a documented 2D exception in the plan).</li>
 * </ul>
 */
public class VulkanSpriteBatch extends VulkanSpriteBatchDraws implements VulkanGL20.TextureRebindListener {
    private static final int VERTEX_BYTES = 24; // x, y (floats), colour (4 x unorm8), u, v (floats), slot (uint)

    private final GdxVkFrame frame;
    private final VulkanGL20 gl;
    private final boolean tierA;

    private final Matrix4 transformMatrix = new Matrix4();
    private final Matrix4 projectionMatrix = new Matrix4();
    private final Matrix4 combinedMatrix = new Matrix4();
    private final float[] push = new float[32];
    private VulkanShaderPipelineBundle bundle; // null: the default sprite shaders
    private VulkanShaderProgram program;       // a program written in libGDX's GLSL, translated at run time; excludes the bundle

    private boolean blendingDisabled = false;
    private int blendSrcFunc = GL20.GL_SRC_ALPHA;
    private int blendDstFunc = GL20.GL_ONE_MINUS_SRC_ALPHA;
    private int blendSrcFuncAlpha = GL20.GL_SRC_ALPHA;
    private int blendDstFuncAlpha = GL20.GL_ONE_MINUS_SRC_ALPHA;
    private final Color color = new Color(1, 1, 1, 1);

    // Tier A: where in the batch the texture slot changes. Quad markerQuad[i] onward uses markerSlot[i].
    private final int[] markerQuad, markerSlot;
    private final int[] expanded; // the 24-byte vertices of a flush that has several textures, before they are copied out
    private int markerCount;

    public int renderCalls = 0;
    public int totalRenderCalls = 0;
    public int maxSpritesInBatch = 0;

    /** Like stock's {@code new SpriteBatch()}: draws into the running application's frame ({@link GdxVkFrame#current()}). */
    public VulkanSpriteBatch () {
        this(GdxVkFrame.current(), 1000);
    }

    /** Like stock's {@code new SpriteBatch(size)}. */
    public VulkanSpriteBatch (int size) {
        this(GdxVkFrame.current(), size);
    }

    public VulkanSpriteBatch (GdxVkFrame frame) {
        this(frame, 1000);
    }

    public VulkanSpriteBatch (GdxVkFrame frame, int size) {
        if (size > GdxVkFrame.MAX_QUADS) throw new IllegalArgumentException("Can't have more than " + GdxVkFrame.MAX_QUADS + " sprites per batch: " + size);
        this.frame = frame;
        this.gl = frame.gl();
        this.tierA = frame.isTierA();
        vertices = new float[size * 20];
        markerQuad = new int[size + 1];
        markerSlot = new int[size + 1];
        expanded = new int[size * 4 * 6];
        projectionMatrix.setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        gl.addTextureRebindListener(this);
    }

    @Override public void begin () {
        if (drawing) throw new IllegalStateException("VulkanSpriteBatch.end must be called before begin.");
        renderCalls = 0;
        setupMatrices();
        drawing = true;
    }

    @Override public void end () {
        if (!drawing) throw new IllegalStateException("VulkanSpriteBatch.begin must be called before end.");
        if (idx > 0) flush();
        lastTexture = null;
        markerCount = 0;
        drawing = false;
    }

    @Override public void setColor (Color tint) {
        color.set(tint);
        colorPacked = tint.toFloatBits();
    }

    @Override public void setColor (float r, float g, float b, float a) {
        color.set(r, g, b, a);
        colorPacked = color.toFloatBits();
    }

    @Override public Color getColor () {
        return color;
    }

    @Override public void setPackedColor (float packedColor) {
        Color.abgr8888ToColor(color, packedColor);
        this.colorPacked = packedColor;
    }

    @Override public float getPackedColor () {
        return colorPacked;
    }

    // ------------------------------------------------------------------------------------------------------------- textures

    private int nativeTexture (Texture texture) {
        int id = gl.nativeTexture(texture.getTextureObjectHandle());
        if (id < 0) throw new GdxRuntimeException("gdx-vulkan: texture has no storage (nothing was uploaded to it)");
        return id;
    }

    private void addMarker (int quad, int slot) {
        if (markerCount > 0 && markerQuad[markerCount - 1] == quad) { // nothing was drawn since the last change: replace it
            markerSlot[markerCount - 1] = slot;
        } else {
            markerQuad[markerCount] = quad;
            markerSlot[markerCount] = slot;
            markerCount++;
        }
    }

    @Override protected void switchTexture (Texture texture) {
        if (tierA && program == null) { // a program takes its texture slot from the push constants, one texture per draw, as stock does
            if (idx == vertices.length) flush(); // stock's draw code only checks for a full batch when the texture is unchanged
            lastTexture = texture;
            invTexWidth = 1.0f / texture.getWidth();
            invTexHeight = 1.0f / texture.getHeight();
            addMarker(idx / 20, gl.slotOf(nativeTexture(texture)));
        } else {
            flush();
            lastTexture = texture;
            invTexWidth = 1.0f / texture.getWidth();
            invTexHeight = 1.0f / texture.getHeight();
        }
    }

    /** The shim renamed or re-sampled a texture: from the next quad on, use its new slot (Tier B binds the latest set at flush). */
    @Override public void textureRebound (int glHandle) {
        if (!tierA || lastTexture == null || lastTexture.getTextureObjectHandle() != glHandle) return;
        if (idx == vertices.length) flush();
        addMarker(idx / 20, gl.slotOf(nativeTexture(lastTexture)));
    }

    // --------------------------------------------------------------------------------------------------------------- drawing

    @Override public void flush () {
        if (idx == 0) return;
        renderCalls++;
        totalRenderCalls++;
        final int sprites = idx / 20;
        if (sprites > maxSpritesInBatch) maxSpritesInBatch = sprites;
        // One texture in the whole batch and no custom shaders: stock's vertices go out as they are (20 bytes, one block copy); the
        // Tier A slot rides in the push constants. Several textures: expand to 24 bytes with the slot in every vertex.
        // Tier A with several textures: when each texture is used for a good run of sprites, one draw per run takes its slot from the push constants (the shader's index is then
        // the same for the whole draw, which Adreno compilers need to be fast) and the vertices go out as they are; when the textures change every few sprites, one draw with the
        // slot in every vertex draws them all (a draw per run would be a draw per sprite).
        perRun = tierA && program == null && bundle == null && markerCount > 1 && sprites >= markerCount * RUN_MIN_SPRITES;
        single = program != null || (bundle == null && (!tierA || markerCount <= 1 || perRun));
        final int vertexBytes = single ? VERTEX_BYTES - 4 : VERTEX_BYTES;
        final long bytes = (long)sprites * 4 * vertexBytes;

        final GdxVkPresenter presenter = frame.presenter();
        final long a = presenter.allocVertex(bytes);
        final int block = GdxVkPresenter.blockOf(a);
        final long offset = GdxVkPresenter.offsetOf(a);
        final ByteBuffer out = frame.vertexBlock(block);

        if (single) {
            ByteBuffer dst = out.duplicate().order(ByteOrder.nativeOrder());
            dst.position((int)offset);
            dst.asFloatBuffer().put(vertices, 0, idx);
        } else {
            expandVertices(out, (int)offset, sprites);
        }
        presenter.flushVertices(block, offset, bytes);

        final CommandStream s = frame.stream();
        s.bindPipeline(pipeline());
        if (program != null) {
            // What stock's SpriteBatch.setupMatrices does for its shader, then the program writes its push constants and binds its textures.
            program.setUniformMatrix("u_projTrans", combinedMatrix);
            program.setUniformi("u_texture", 0);
            program.prepareDraw(s, push, lastTexture.getTextureObjectHandle());
            s.pushConstants(push);
        } else {
            System.arraycopy(combinedMatrix.val, 0, push, 0, 16);
            push[16] = push[17] = push[18] = push[19] = 1f;
            push[21] = tierA && single ? Float.intBitsToFloat(markerCount > 0 ? markerSlot[0] : 0) : 0f; // u_indices.y
            if (bundle != null) {
                System.arraycopy(bundle.custom0, 0, push, 24, 4);
                System.arraycopy(bundle.custom1, 0, push, 28, 4);
            }
            s.pushConstants(push);
            if (!tierA) s.bindTexture(nativeTexture(lastTexture));
        }
        s.bindVertex(block, offset);
        s.bindIndexBuffer(frame.quadIndexBuffer(), 0, CommandStream.INDEX_U16);
        if (perRun) {
            for (int r = 0; r < markerCount; r++) {
                final int first = markerQuad[r], end = r + 1 < markerCount ? markerQuad[r + 1] : sprites;
                if (end <= first) continue;
                if (r > 0) {
                    runSlot[0] = Float.intBitsToFloat(markerSlot[r]);
                    s.pushRange(21, runSlot, 0, 1);
                }
                s.drawIndexed((end - first) * 6, first * 6, 0);
            }
        } else {
            s.drawIndexed(sprites * 6, 0, 0);
        }

        idx = 0;
        markerCount = 0;
        if (tierA && lastTexture != null) addMarker(0, gl.slotOf(nativeTexture(lastTexture))); // the batch carries on with this texture
    }

    /** Expands stock's 5-float vertices to 24 bytes by adding the texture slot, which changes at the recorded markers (Tier A). */
    private void expandVertices (ByteBuffer out, int offset, int sprites) {
        // Written into an int array and copied in one block: a put on a ByteBuffer view costs about 80 ns on ART, and this loop made six of them per vertex.
        final float[] v = vertices;
        final int[] e = expanded;
        int marker = 0, slot = tierA ? markerSlot[0] : 0;
        int nextChange = (tierA && markerCount > 1) ? markerQuad[1] : Integer.MAX_VALUE;
        int dst = 0, src = 0;
        for (int q = 0; q < sprites; q++) {
            while (q >= nextChange) {
                marker++;
                slot = markerSlot[marker];
                nextChange = marker + 1 < markerCount ? markerQuad[marker + 1] : Integer.MAX_VALUE;
            }
            for (int k = 0; k < 4; k++, src += 5, dst += 6) {
                e[dst] = Float.floatToRawIntBits(v[src]);
                e[dst + 1] = Float.floatToRawIntBits(v[src + 1]);
                e[dst + 2] = Float.floatToRawIntBits(v[src + 2]);
                e[dst + 3] = Float.floatToRawIntBits(v[src + 3]);
                e[dst + 4] = Float.floatToRawIntBits(v[src + 4]);
                e[dst + 5] = slot;
            }
        }
        final ByteBuffer d = out.duplicate().order(ByteOrder.nativeOrder());
        d.position(offset);
        d.asIntBuffer().put(e, 0, dst);
    }

    private boolean single; // this flush takes the 20-byte path
    private boolean perRun; // ... with one draw per texture run
    private final float[] runSlot = new float[1];
    private static final int RUN_MIN_SPRITES = 16; // the average run a draw per run needs

    private int pipeline () {
        return SpritePipelines.get(frame, bundle, program, single, blendingDisabled, blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha);
    }

    @Override public void disableBlending () {
        if (blendingDisabled) return;
        flush();
        blendingDisabled = true;
    }

    @Override public void enableBlending () {
        if (!blendingDisabled) return;
        flush();
        blendingDisabled = false;
    }

    @Override public void setBlendFunction (int srcFunc, int dstFunc) {
        setBlendFunctionSeparate(srcFunc, dstFunc, srcFunc, dstFunc);
    }

    @Override public void setBlendFunctionSeparate (int srcFuncColor, int dstFuncColor, int srcFuncAlpha, int dstFuncAlpha) {
        if (blendSrcFunc == srcFuncColor && blendDstFunc == dstFuncColor && blendSrcFuncAlpha == srcFuncAlpha
            && blendDstFuncAlpha == dstFuncAlpha) return;
        flush();
        blendSrcFunc = srcFuncColor;
        blendDstFunc = dstFuncColor;
        blendSrcFuncAlpha = srcFuncAlpha;
        blendDstFuncAlpha = dstFuncAlpha;
    }

    @Override public int getBlendSrcFunc () {
        return blendSrcFunc;
    }

    @Override public int getBlendDstFunc () {
        return blendDstFunc;
    }

    @Override public int getBlendSrcFuncAlpha () {
        return blendSrcFuncAlpha;
    }

    @Override public int getBlendDstFuncAlpha () {
        return blendDstFuncAlpha;
    }

    @Override public void dispose () {
        gl.removeTextureRebindListener(this);
    }

    @Override public Matrix4 getProjectionMatrix () {
        return projectionMatrix;
    }

    @Override public Matrix4 getTransformMatrix () {
        return transformMatrix;
    }

    @Override public void setProjectionMatrix (Matrix4 projection) {
        if (drawing) flush();
        projectionMatrix.set(projection);
        if (drawing) setupMatrices();
    }

    @Override public void setTransformMatrix (Matrix4 transform) {
        if (drawing) flush();
        transformMatrix.set(transform);
        if (drawing) setupMatrices();
    }

    private void setupMatrices () {
        combinedMatrix.set(projectionMatrix).mul(transformMatrix);
    }

    /** Custom {@code ShaderProgram}s are not supported; use a Vulkan pipeline instead. Passing null is accepted (it means the default). */
    @Override public void setShader (ShaderProgram shader) {
        if (shader != null) {
            throw new GdxRuntimeException("gdx-vulkan: VulkanSpriteBatch does not run GLSL ShaderPrograms. Custom shading uses "
                + "VulkanShaderPipelineBundle (plan, Phase 2).");
        }
    }

    @Override public ShaderProgram getShader () {
        return null;
    }

    /** Custom shading: draws after this call use the bundle's shaders. Null restores the default. Flushes what is queued first. */
    public void setShaderBundle (VulkanShaderPipelineBundle bundle) {
        if (bundle == this.bundle && (bundle == null || program == null)) return;
        if (drawing) flush();
        this.bundle = bundle;
        if (bundle != null) program = null;
    }

    /**
     * Draws after this call use the program, a shader written in libGDX's GLSL, as with stock's {@code setShader}; null restores the default shaders. The batch
     * sets {@code u_projTrans} and {@code u_texture} on it before every draw, as stock does. The program may read the attributes {@code a_position},
     * {@code a_color} and {@code a_texCoord0} (the sprite vertex); another attribute is refused here. With a program a texture change flushes the batch, as in
     * stock. Flushes what is queued first.
     */
    public void setShaderProgram (VulkanShaderProgram program) {
        if (program == this.program) return;
        if (program != null) {
            program.requireCompiled();
            final String extra = SpritePipelines.unsupportedAttribute(program);
            if (extra != null) {
                throw new GdxRuntimeException("gdx-vulkan: the shader reads the attribute '" + extra + "', which a sprite vertex does not have (it has "
                    + SpritePipelines.POSITION + ", " + SpritePipelines.COLOR + " and " + SpritePipelines.TEXCOORD + ")");
            }
        }
        if (drawing) flush();
        this.program = program;
        if (program != null) bundle = null;
    }

    public VulkanShaderProgram getShaderProgram () {
        return program;
    }

    public VulkanShaderPipelineBundle getShaderBundle () {
        return bundle;
    }

    @Override public boolean isBlendingEnabled () {
        return !blendingDisabled;
    }

    @Override public boolean isDrawing () {
        return drawing;
    }
}
