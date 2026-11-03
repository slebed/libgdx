package io.github.slebed.gdxvulkan.g2d;

import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;

/** The pipelines of the 2D batches: one shader pair and vertex layout, one variant per blend state (blend is pipeline state in Vulkan). */
final class SpritePipelines {
    /** x, y (floats), colour (4 x unorm8), u, v (floats), texture slot (uint, Tier A only). */
    static final int VERTEX_BYTES = 24;

    private SpritePipelines() {}

    static int get (GdxVkFrame frame, boolean blendingDisabled, int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
        final boolean tierA = frame.isTierA();
        final String key = blendingDisabled ? "sprite|opaque" : "sprite|" + srcColor + "," + dstColor + "," + srcAlpha + "," + dstAlpha;
        GdxVkPipelineDesc d = new GdxVkPipelineDesc();
        d.stride = VERTEX_BYTES;
        d.attribute(0, GdxVkPipelineDesc.FORMAT_R32G32_SFLOAT, 0).attribute(1, GdxVkPipelineDesc.FORMAT_R8G8B8A8_UNORM, 8)
            .attribute(2, GdxVkPipelineDesc.FORMAT_R32G32_SFLOAT, 12);
        if (tierA) d.attribute(3, GdxVkPipelineDesc.FORMAT_R32_UINT, 20); // the slot only exists in the Tier A shaders
        d.cullMode = GdxVkPipelineDesc.CULL_NONE;
        if (!blendingDisabled) {
            d.blend = true;
            d.srcColor = srcColor;
            d.dstColor = dstColor;
            d.srcAlpha = srcAlpha;
            d.dstAlpha = dstAlpha;
        }
        return frame.pipeline(key, "sprite", "sprite.vert", "sprite.frag", d);
    }
}
