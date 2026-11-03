package io.github.slebed.gdxvulkan;

import java.util.ArrayList;
import java.util.List;

/** What a graphics pipeline draws with: vertex layout, topology, culling and blending. Blend factors are OpenGL enum values. */
public final class GdxVkPipelineDesc {
    // VkFormat values for vertex attributes.
    public static final int FORMAT_R32_UINT = 98, FORMAT_R32G32_SFLOAT = 103, FORMAT_R32G32B32_SFLOAT = 106,
        FORMAT_R32G32B32A32_SFLOAT = 109, FORMAT_R8G8B8A8_UNORM = 37;
    // VkPrimitiveTopology values.
    public static final int POINTS = 0, LINES = 1, LINE_STRIP = 2, TRIANGLES = 3, TRIANGLE_STRIP = 4, TRIANGLE_FAN = 5;
    // VkCullModeFlags values.
    public static final int CULL_NONE = 0, CULL_FRONT = 1, CULL_BACK = 2;

    public int stride;
    public int topology = TRIANGLES;
    public int cullMode = CULL_BACK;
    public boolean blend;
    public int srcColor = 1, dstColor = 0, srcAlpha = 1, dstAlpha = 0; // GL_ONE, GL_ZERO
    private final List<int[]> attributes = new ArrayList<int[]>();

    public GdxVkPipelineDesc attribute(int location, int vkFormat, int offset) {
        attributes.add(new int[] {location, vkFormat, offset});
        return this;
    }

    int[] encode() {
        int[] d = new int[9 + attributes.size() * 3];
        d[0] = stride;
        d[1] = topology;
        d[2] = cullMode;
        d[3] = blend ? 1 : 0;
        d[4] = srcColor;
        d[5] = dstColor;
        d[6] = srcAlpha;
        d[7] = dstAlpha;
        d[8] = attributes.size();
        for (int i = 0; i < attributes.size(); i++) System.arraycopy(attributes.get(i), 0, d, 9 + i * 3, 3);
        return d;
    }
}
