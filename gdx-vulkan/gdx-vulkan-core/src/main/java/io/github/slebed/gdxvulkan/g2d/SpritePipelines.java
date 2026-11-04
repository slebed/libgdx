package io.github.slebed.gdxvulkan.g2d;

import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
import io.github.slebed.gdxvulkan.VulkanShaderPipelineBundle;
import io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram;

/** The pipelines of the 2D batches: one shader pair and vertex layout, one variant per blend state (blend is pipeline state in Vulkan). */
final class SpritePipelines {
    /** x, y (floats), colour (4 x unorm8), u, v (floats), texture slot (uint, Tier A only). */
    static final int VERTEX_BYTES = 24;

    private SpritePipelines() {}

    /**
     * @param single the batch uses one texture and no bundle: stock's 20-byte vertex, the Tier A slot taken from the push constants.
     *           Otherwise the 24-byte vertex with a per-vertex slot (Tier A).
     */
    static int get (GdxVkFrame frame, VulkanShaderPipelineBundle bundle, boolean single, boolean blendingDisabled, int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
        return get(frame, bundle, null, single, blendingDisabled, srcColor, dstColor, srcAlpha, dstAlpha);
    }

    /** The attributes the batches' vertices carry, by the names libGDX gives them: what a program's {@code attribute}s are matched with. */
    static final String POSITION = "a_position", COLOR = "a_color", TEXCOORD = "a_texCoord0";

    /** Whether the program only asks for attributes a sprite vertex has; null when it does, otherwise the first one it should not have. */
    static String unsupportedAttribute (VulkanShaderProgram program) {
        for (String name : program.getAttributes()) {
            if (!name.equals(POSITION) && !name.equals(COLOR) && !name.equals(TEXCOORD)) return name;
        }
        return null;
    }

    /** With a {@code program}, its own pipeline: the 20-byte vertex, each attribute at the location the program gave its name. */
    static int get (GdxVkFrame frame, VulkanShaderPipelineBundle bundle, VulkanShaderProgram program, boolean single, boolean blendingDisabled, int srcColor, int dstColor,
        int srcAlpha, int dstAlpha) {
        final boolean tierA = frame.isTierA();
        final String prefix = program != null ? "sprite:" + program.key() + "|" : bundle != null ? "sprite:" + bundle.key() + "x" + bundle.colorOutputs() + "|" : single ? "sprite1|" : "sprite|";
        final String key = blendingDisabled ? prefix + "opaque" : prefix + srcColor + "," + dstColor + "," + srcAlpha + "," + dstAlpha;
        GdxVkPipelineDesc d = new GdxVkPipelineDesc();
        d.stride = single ? VERTEX_BYTES - 4 : VERTEX_BYTES;
        if (program != null) {
            d.stride = VERTEX_BYTES - 4;
            final int position = program.getAttributeLocation(POSITION), color = program.getAttributeLocation(COLOR), uv = program.getAttributeLocation(TEXCOORD);
            if (position >= 0) d.attribute(position, GdxVkPipelineDesc.FORMAT_R32G32_SFLOAT, 0);
            if (color >= 0) d.attribute(color, GdxVkPipelineDesc.FORMAT_R8G8B8A8_UNORM, 8);
            if (uv >= 0) d.attribute(uv, GdxVkPipelineDesc.FORMAT_R32G32_SFLOAT, 12);
        } else {
            d.attribute(0, GdxVkPipelineDesc.FORMAT_R32G32_SFLOAT, 0).attribute(1, GdxVkPipelineDesc.FORMAT_R8G8B8A8_UNORM, 8)
                .attribute(2, GdxVkPipelineDesc.FORMAT_R32G32_SFLOAT, 12);
            if (tierA && !single) d.attribute(3, GdxVkPipelineDesc.FORMAT_R32_UINT, 20); // the slot only exists in the Tier A shaders
        }
        d.cullMode = GdxVkPipelineDesc.CULL_NONE;
        if (bundle != null) d.colorOutputs = bundle.colorOutputs();
        if (!blendingDisabled) {
            d.blend = true;
            d.srcColor = srcColor;
            d.dstColor = dstColor;
            d.srcAlpha = srcAlpha;
            d.dstAlpha = dstAlpha;
        }
        if (program != null) return program.pipeline(key.substring(("sprite:" + program.key() + "|").length()), d);
        if (bundle != null) return frame.pipeline(key, "sprite:" + bundle.key(), bundle, d);
        if (single) return frame.pipeline(key, "sprite1", "sprite_single.vert", "sprite_single.frag", d);
        return frame.pipeline(key, "sprite", "sprite.vert", "sprite.frag", d);
    }
}
