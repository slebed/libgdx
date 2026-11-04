package io.github.slebed.gdxvulkan.g2d;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
import io.github.slebed.gdxvulkan.VulkanShaderPipelineBundle;
import io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram;
import io.github.slebed.gdxvulkan.GdxVkPresenter;

import java.nio.ByteBuffer;

/**
 * {@link ImmediateModeRenderer} on the Vulkan command stream, for coloured geometry (position and colour; texture coordinates and
 * normals are accepted and ignored). Blending follows what the application set through {@code glEnable(GL_BLEND)} and
 * {@code glBlendFunc}, as stock's renderer does, because those calls land in the GL20 shim.
 *
 * <p>Points are one pixel and lines one pixel wide (wide lines are an optional Vulkan feature; stock's GL lines are 1px too).
 */
public class VulkanImmediateModeRenderer implements ImmediateModeRenderer {
    private static final int FLOATS = 4; // x, y, z, packed colour
    private static final int BYTES = 16;

    // GL primitive types
    private static final int GL_POINTS = 0, GL_LINES = 1, GL_LINE_STRIP = 3, GL_TRIANGLES = 4, GL_TRIANGLE_STRIP = 5, GL_TRIANGLE_FAN = 6;

    private final GdxVkFrame frame;
    private final float[] vertices;
    private final int maxVertices;
    private int numVertices;
    private int primitiveType;
    private final Matrix4 projModelView = new Matrix4();
    private final float[] push = new float[32];
    private float colorBits = Color.WHITE_FLOAT_BITS;
    private VulkanShaderPipelineBundle bundle;
    private VulkanShaderProgram program;

    /**
     * Custom shading for what this renderer draws: the bundle's shaders replace {@code shape.vert}/{@code shape.frag}. The vertex layout is the
     * renderer's own (location 0 position, 1 colour) and the bundle's {@code custom0}/{@code custom1} reach the shader as {@code u_custom0}/
     * {@code u_custom1}, except {@code custom0.x}, which carries the thin-line nudge {@code shape.vert} applies (a bundle that reuses
     * {@code shape.vert} keeps it; use y, z, w and {@code custom1}). Null restores the default. Flushes what is queued first.
     */
    public void setShaderBundle (VulkanShaderPipelineBundle bundle) {
        if (bundle == this.bundle) return;
        flush();
        this.bundle = bundle;
    }

    public VulkanShaderPipelineBundle getShaderBundle () {
        return bundle;
    }

    /**
     * Draws with a shader written in libGDX's GLSL, like stock's {@code ShapeRenderer(maxVertices, shader)}: it may read {@code a_position} and {@code a_color}
     * and no texture, and it gets {@code u_projModelView} before every draw. Null restores the default. The thin-line nudge of {@code shape.vert} is not applied
     * to a program. Reach this renderer with {@code ((VulkanImmediateModeRenderer)shapeRenderer.getRenderer())}.
     */
    public void setShaderProgram (VulkanShaderProgram program) {
        if (program == this.program) return;
        if (program != null) {
            program.requireCompiled();
            for (String name : program.getAttributes()) {
                if (!name.equals("a_position") && !name.equals("a_color")) {
                    throw new GdxRuntimeException("gdx-vulkan: the shader reads the attribute '" + name + "', which the shape renderer's vertex does not have (it has a_position and a_color)");
                }
            }
            for (io.github.slebed.gdxvulkan.glsl.GlslTranslator.Sampler sampler : program.translation().samplers) {
                if (sampler.active) throw new GdxRuntimeException("gdx-vulkan: the shader samples the texture '" + sampler.name + "'; the shape renderer draws without textures");
            }
        }
        flush();
        this.program = program;
        if (program != null) bundle = null;
    }

    public VulkanShaderProgram getShaderProgram () {
        return program;
    }

    public VulkanImmediateModeRenderer (GdxVkFrame frame, int maxVertices) {
        this.frame = frame;
        this.maxVertices = maxVertices;
        this.vertices = new float[maxVertices * FLOATS];
    }

    @Override public void begin (Matrix4 projModelView, int primitiveType) {
        this.projModelView.set(projModelView);
        this.primitiveType = primitiveType;
    }

    @Override public void color (Color color) {
        colorBits = color.toFloatBits();
    }

    @Override public void color (float r, float g, float b, float a) {
        colorBits = Color.toFloatBits(r, g, b, a);
    }

    @Override public void color (float colorBits) {
        this.colorBits = colorBits;
    }

    @Override public void texCoord (float u, float v) {
        // not part of this renderer's vertex
    }

    @Override public void normal (float x, float y, float z) {
        // not part of this renderer's vertex
    }

    @Override public void vertex (float x, float y, float z) {
        if (numVertices >= maxVertices) throw new GdxRuntimeException("gdx-vulkan: immediate-mode vertex buffer is full (" + maxVertices + ")");
        int i = numVertices * FLOATS;
        vertices[i] = x;
        vertices[i + 1] = y;
        vertices[i + 2] = z;
        vertices[i + 3] = colorBits;
        numVertices++;
    }

    @Override public void flush () {
        if (numVertices == 0) return;
        final GdxVkPresenter presenter = frame.presenter();
        final long bytes = (long)numVertices * BYTES;
        final long a = presenter.allocVertex(bytes);
        final int block = GdxVkPresenter.blockOf(a);
        final long offset = GdxVkPresenter.offsetOf(a);
        final ByteBuffer out = frame.vertexBlock(block);
        for (int v = 0, src = 0, pos = (int)offset; v < numVertices; v++, src += FLOATS, pos += BYTES) {
            out.putFloat(pos, vertices[src]);
            out.putFloat(pos + 4, vertices[src + 1]);
            out.putFloat(pos + 8, vertices[src + 2]);
            out.putInt(pos + 12, Float.floatToRawIntBits(vertices[src + 3]));
        }
        presenter.flushVertices(block, offset, bytes);

        final CommandStream s = frame.stream();
        s.bindPipeline(pipeline());
        if (program != null) {
            program.setUniformMatrix("u_projModelView", projModelView);
            program.prepareDraw(s, push, 0);
            s.pushConstants(push);
            s.bindVertex(block, offset);
            s.draw(numVertices, 0);
            numVertices = 0;
            return;
        }
        System.arraycopy(projModelView.val, 0, push, 0, 16);
        push[16] = push[17] = push[18] = push[19] = 1f;
        // Lines and points: move them 1/256 pixel in GL's downward direction so a primitive on a pixel boundary lands on the pixel GL
        // picks (see shape.vert). Triangles are not nudged.
        final boolean thin = primitiveType == GL_POINTS || primitiveType == GL_LINES || primitiveType == GL_LINE_STRIP;
        if (bundle != null) {
            System.arraycopy(bundle.custom0, 0, push, 24, 4);
            System.arraycopy(bundle.custom1, 0, push, 28, 4);
        } else {
            java.util.Arrays.fill(push, 25, 32, 0f);
        }
        push[24] = thin ? -2f / 256f / Math.max(1, frame.height()) : 0f;
        s.pushConstants(push);
        s.bindVertex(block, offset);
        s.draw(numVertices, 0);
        numVertices = 0;
    }

    private int pipeline () {
        final boolean blend = frame.gl().isBlendEnabled();
        final int[] f = frame.gl().blendFunctions();
        final int topology = topology(primitiveType);
        final String key = "shape|" + topology + "|" + (blend ? f[0] + "," + f[1] + "," + f[2] + "," + f[3] : "opaque");
        GdxVkPipelineDesc d = new GdxVkPipelineDesc();
        d.stride = BYTES;
        d.attribute(0, GdxVkPipelineDesc.FORMAT_R32G32B32_SFLOAT, 0).attribute(1, GdxVkPipelineDesc.FORMAT_R8G8B8A8_UNORM, 12);
        d.topology = topology;
        d.cullMode = GdxVkPipelineDesc.CULL_NONE;
        if (blend) {
            d.blend = true;
            d.srcColor = f[0];
            d.dstColor = f[1];
            d.srcAlpha = f[2];
            d.dstAlpha = f[3];
        }
        if (program != null) {
            d.attributes().clear(); // the program decides where each attribute goes
            final int position = program.getAttributeLocation("a_position"), color = program.getAttributeLocation("a_color");
            if (position >= 0) d.attribute(position, GdxVkPipelineDesc.FORMAT_R32G32B32_SFLOAT, 0);
            if (color >= 0) d.attribute(color, GdxVkPipelineDesc.FORMAT_R8G8B8A8_UNORM, 12);
            return program.pipeline(key, d);
        }
        if (bundle != null) {
            d.colorOutputs = bundle.colorOutputs();
            return frame.pipeline(key + "|" + bundle.key(), "shape:" + bundle.key(), bundle, d);
        }
        return frame.pipeline(key, "shape", "shape.vert", "shape.frag", d);
    }

    private static int topology (int glPrimitive) {
        switch (glPrimitive) {
        case GL_POINTS: return GdxVkPipelineDesc.POINTS;
        case GL_LINES: return GdxVkPipelineDesc.LINES;
        case GL_LINE_STRIP: return GdxVkPipelineDesc.LINE_STRIP;
        case GL_TRIANGLES: return GdxVkPipelineDesc.TRIANGLES;
        case GL_TRIANGLE_STRIP: return GdxVkPipelineDesc.TRIANGLE_STRIP;
        case GL_TRIANGLE_FAN: return GdxVkPipelineDesc.TRIANGLE_FAN;
        default: throw new GdxRuntimeException("gdx-vulkan: unsupported primitive type 0x" + Integer.toHexString(glPrimitive)
            + " (GL_LINE_LOOP has no Vulkan equivalent)");
        }
    }

    @Override public void end () {
        flush();
    }

    @Override public int getNumVertices () {
        return numVertices;
    }

    @Override public int getMaxVertices () {
        return maxVertices;
    }

    @Override public void dispose () {
    }
}
