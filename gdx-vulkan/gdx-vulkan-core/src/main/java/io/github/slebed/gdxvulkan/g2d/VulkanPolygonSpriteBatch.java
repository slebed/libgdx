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
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.VulkanShaderPipelineBundle;
import io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.nio.ByteBuffer;

/**
 * A {@link com.badlogic.gdx.graphics.g2d.PolygonBatch} on the Vulkan command stream (plan, Phase 2). Like {@link VulkanSpriteBatch} the
 * geometry code is stock libGDX's ({@link VulkanPolygonSpriteBatchDraws}); vertices and indices go to the per-frame ring and are drawn
 * with one indexed draw per flush. It shares its pipelines and shaders with {@link VulkanSpriteBatch}.
 *
 * <p>Tier A: a texture change does not flush; every vertex carries its texture's slot. Because a polygon draw can be any size, and stock
 * relies on the flush to make room, a texture change still flushes when less than a quarter of the batch is free; a polygon larger than
 * that must not directly follow a texture change (the default batch holds 2000 vertices).
 */
public class VulkanPolygonSpriteBatch extends VulkanPolygonSpriteBatchDraws implements VulkanGL20.TextureRebindListener {
    private final GdxVkFrame frame;
    private final VulkanGL20 gl;
    private final boolean tierA;

    private final Matrix4 transformMatrix = new Matrix4();
    private final Matrix4 projectionMatrix = new Matrix4();
    private final Matrix4 combinedMatrix = new Matrix4();
    private final float[] push = new float[32];
    private VulkanShaderPipelineBundle bundle; // null: the default sprite shaders
    private VulkanShaderProgram program;       // a program written in libGDX's GLSL, translated at run time; excludes the bundle

    private boolean blendingDisabled;
    private int blendSrcFunc = GL20.GL_SRC_ALPHA;
    private int blendDstFunc = GL20.GL_ONE_MINUS_SRC_ALPHA;
    private int blendSrcFuncAlpha = GL20.GL_SRC_ALPHA;
    private int blendDstFuncAlpha = GL20.GL_ONE_MINUS_SRC_ALPHA;
    private final Color color = new Color(1, 1, 1, 1);

    // Tier A: from vertex markerVertex[i] onward the texture slot is markerSlot[i].
    private final int[] markerVertex, markerSlot;
    private final int[] expanded; // the vertices of a flush before they are copied out in one block
    private int markerCount;

    public int renderCalls = 0;
    public int totalRenderCalls = 0;
    public int maxTrianglesInBatch = 0;

    /** Like stock's {@code new PolygonSpriteBatch()}: draws into the running application's frame ({@link GdxVkFrame#current()}). */
    public VulkanPolygonSpriteBatch () {
        this(GdxVkFrame.current(), 2000);
    }

    public VulkanPolygonSpriteBatch (int size) {
        this(GdxVkFrame.current(), size);
    }

    public VulkanPolygonSpriteBatch (int maxVertices, int maxTriangles) {
        this(GdxVkFrame.current(), maxVertices, maxTriangles);
    }

    public VulkanPolygonSpriteBatch (GdxVkFrame frame) {
        this(frame, 2000);
    }

    public VulkanPolygonSpriteBatch (GdxVkFrame frame, int size) {
        this(frame, size, size * 2);
    }

    public VulkanPolygonSpriteBatch (GdxVkFrame frame, int maxVertices, int maxTriangles) {
        if (maxVertices > 32767) throw new IllegalArgumentException("Can't have more than 32767 vertices per batch: " + maxVertices);
        this.frame = frame;
        this.gl = frame.gl();
        this.tierA = frame.isTierA();
        vertices = new float[maxVertices * VERTEX_SIZE];
        triangles = new short[maxTriangles * 3];
        markerVertex = new int[maxVertices + 1];
        expanded = new int[maxVertices * 6];
        markerSlot = new int[maxVertices + 1];
        projectionMatrix.setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        gl.addTextureRebindListener(this);
    }

    @Override public void begin () {
        if (drawing) throw new IllegalStateException("VulkanPolygonSpriteBatch.end must be called before begin.");
        renderCalls = 0;
        setupMatrices();
        drawing = true;
    }

    @Override public void end () {
        if (!drawing) throw new IllegalStateException("VulkanPolygonSpriteBatch.begin must be called before end.");
        if (vertexIndex > 0) flush();
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

    private void addMarker (int vertex, int slot) {
        if (markerCount > 0 && markerVertex[markerCount - 1] == vertex) {
            markerSlot[markerCount - 1] = slot;
        } else {
            markerVertex[markerCount] = vertex;
            markerSlot[markerCount] = slot;
            markerCount++;
        }
    }

    @Override protected void switchTexture (Texture texture) {
        if (tierA && program == null) { // a program takes its texture slot from the push constants, one texture per draw, as stock does
            // Stock flushes here, which also makes room for the draw that follows. Keep room for it instead of flushing every time.
            if (vertexIndex > vertices.length * 3 / 4 || triangleIndex > triangles.length * 3 / 4) flush();
            lastTexture = texture;
            invTexWidth = 1.0f / texture.getWidth();
            invTexHeight = 1.0f / texture.getHeight();
            addMarker(vertexIndex / VERTEX_SIZE, gl.slotOf(nativeTexture(texture)));
        } else {
            flush();
            lastTexture = texture;
            invTexWidth = 1.0f / texture.getWidth();
            invTexHeight = 1.0f / texture.getHeight();
        }
    }

    @Override public void textureRebound (int glHandle) {
        if (!tierA || lastTexture == null || lastTexture.getTextureObjectHandle() != glHandle) return;
        if (vertexIndex == vertices.length) flush();
        addMarker(vertexIndex / VERTEX_SIZE, gl.slotOf(nativeTexture(lastTexture)));
    }

    // --------------------------------------------------------------------------------------------------------------- drawing

    @Override public void flush () {
        if (vertexIndex == 0) return;
        renderCalls++;
        totalRenderCalls++;
        final int indexCount = triangleIndex;
        if (indexCount > maxTrianglesInBatch) maxTrianglesInBatch = indexCount;
        final int vertexCount = vertexIndex / VERTEX_SIZE;

        final GdxVkPresenter presenter = frame.presenter();
        final int vertexBytes = program != null ? SpritePipelines.VERTEX_BYTES - 4 : SpritePipelines.VERTEX_BYTES; // a program has no texture slot in the vertex
        final long vBytes = (long)vertexCount * vertexBytes;
        final long va = presenter.allocVertex(vBytes);
        final int vBlock = GdxVkPresenter.blockOf(va);
        final long vOffset = GdxVkPresenter.offsetOf(va);
        final ByteBuffer vOut = frame.vertexBlock(vBlock);

        // Written into an int array and copied in one block: a put on a ByteBuffer view costs about 80 ns on ART.
        final float[] v = vertices;
        final int[] e = expanded;
        final int ints = vertexBytes / 4;
        int marker = 0, slot = tierA ? markerSlot[0] : 0;
        int nextChange = (tierA && markerCount > 1) ? markerVertex[1] : Integer.MAX_VALUE;
        int dst = 0, src = 0;
        for (int i = 0; i < vertexCount; i++, src += VERTEX_SIZE, dst += ints) {
            while (i >= nextChange) {
                marker++;
                slot = markerSlot[marker];
                nextChange = marker + 1 < markerCount ? markerVertex[marker + 1] : Integer.MAX_VALUE;
            }
            e[dst] = Float.floatToRawIntBits(v[src]);
            e[dst + 1] = Float.floatToRawIntBits(v[src + 1]);
            e[dst + 2] = Float.floatToRawIntBits(v[src + 2]);
            e[dst + 3] = Float.floatToRawIntBits(v[src + 3]);
            e[dst + 4] = Float.floatToRawIntBits(v[src + 4]);
            if (program == null) e[dst + 5] = slot;
        }
        final ByteBuffer vDst = vOut.duplicate().order(java.nio.ByteOrder.nativeOrder());
        vDst.position((int)vOffset);
        vDst.asIntBuffer().put(e, 0, dst);
        presenter.flushVertices(vBlock, vOffset, vBytes);

        final long iBytes = (long)indexCount * 2;
        final long ia = presenter.allocVertex(iBytes);
        final int iBlock = GdxVkPresenter.blockOf(ia);
        final long iOffset = GdxVkPresenter.offsetOf(ia);
        final ByteBuffer iOut = frame.vertexBlock(iBlock);
        final short[] t = triangles;
        final ByteBuffer iDst = iOut.duplicate().order(java.nio.ByteOrder.nativeOrder());
        iDst.position((int)iOffset);
        iDst.asShortBuffer().put(t, 0, indexCount);
        presenter.flushVertices(iBlock, iOffset, iBytes);

        final CommandStream s = frame.stream();
        s.bindPipeline(SpritePipelines.get(frame, bundle, program, false, blendingDisabled, blendSrcFunc, blendDstFunc, blendSrcFuncAlpha, blendDstFuncAlpha));
        if (program != null) {
            program.setUniformMatrix("u_projTrans", combinedMatrix);
            program.setUniformi("u_texture", 0);
            program.prepareDraw(s, push, lastTexture.getTextureObjectHandle());
            s.pushConstants(push);
        } else {
            System.arraycopy(combinedMatrix.val, 0, push, 0, 16);
            push[16] = push[17] = push[18] = push[19] = 1f;
            if (bundle != null) {
                System.arraycopy(bundle.custom0, 0, push, 24, 4);
                System.arraycopy(bundle.custom1, 0, push, 28, 4);
            }
            s.pushConstants(push);
            if (!tierA) s.bindTexture(nativeTexture(lastTexture));
        }
        s.bindVertex(vBlock, vOffset);
        s.bindIndexRing(iBlock, iOffset, CommandStream.INDEX_U16);
        s.drawIndexed(indexCount, 0, 0);

        vertexIndex = 0;
        triangleIndex = 0;
        markerCount = 0;
        if (tierA && lastTexture != null) addMarker(0, gl.slotOf(nativeTexture(lastTexture)));
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

    @Override public void setShader (ShaderProgram shader) {
        if (shader != null) {
            throw new GdxRuntimeException("gdx-vulkan: VulkanPolygonSpriteBatch does not run GLSL ShaderPrograms. Custom shading uses "
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

    /** See {@link VulkanSpriteBatch#setShaderProgram}: a shader written in libGDX's GLSL; the same rules and attributes. */
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
