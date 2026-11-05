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
    /** Colour outputs the fragment shader writes (location 0..n-1): the render target it draws into must have as many attachments. */
    public int colorOutputs = 1;
    /**
     * Values of specialization constants 1..n (constant 0 is the presenter's pre-rotation) for both shaders, so one shader serves many material
     * and lighting variants. A shader ignores ids it does not declare. Part of the pipeline's identity: use it in the cache key.
     */
    public int[] specConstants = new int[0];
    private final List<int[]> attributes = new ArrayList<int[]>();

    /** The attribute list itself, so a caller that built a default layout can replace it. */
    public List<int[]> attributes() {
        return attributes;
    }

    /**
     * A vec4 that is the same for every vertex (what {@code glVertexAttrib4f} sets), read at {@code offset} bytes into the block {@link CommandStream#bindConstants} binds. The
     * pipeline needs at least one ordinary attribute as well.
     */
    public GdxVkPipelineDesc constantAttribute(int location, int offset) {
        attributes.add(new int[] {location, 0x40000000 | FORMAT_R32G32B32A32_SFLOAT, offset});
        return this;
    }

    public GdxVkPipelineDesc attribute(int location, int vkFormat, int offset) {
        attributes.add(new int[] {location, vkFormat, offset});
        return this;
    }

    /** An attribute read from another vertex buffer binding than 0 (1 to 14): its stride is set with {@link #bindingStride}. */
    public GdxVkPipelineDesc attribute(int location, int vkFormat, int offset, int binding) {
        if (binding < 0 || binding > 14) throw new IllegalArgumentException("binding " + binding);
        attributes.add(new int[] {location, vkFormat | (binding << 24), offset});
        if (binding >= bindingStrides.length) bindingStrides = java.util.Arrays.copyOf(bindingStrides, binding + 1);
        return this;
    }

    private int[] bindingStrides = new int[1];

    /** The stride of a binding from 1 to 14 (binding 0 is {@link #stride}). */
    public GdxVkPipelineDesc bindingStride(int binding, int stride) {
        if (binding < 1 || binding > 14) throw new IllegalArgumentException("binding " + binding);
        if (binding >= bindingStrides.length) bindingStrides = java.util.Arrays.copyOf(bindingStrides, binding + 1);
        bindingStrides[binding] = stride;
        return this;
    }

    int[] encode() {
        final int tail = 10 + attributes.size() * 3;
        final int stridesAt = tail + 1 + specConstants.length;
        int[] d = new int[stridesAt + 1 + bindingStrides.length];
        d[0] = stride;
        d[1] = topology;
        d[2] = cullMode;
        d[3] = blend ? 1 : 0;
        d[4] = srcColor;
        d[5] = dstColor;
        d[6] = srcAlpha;
        d[7] = dstAlpha;
        d[8] = attributes.size();
        d[9] = colorOutputs;
        for (int i = 0; i < attributes.size(); i++) System.arraycopy(attributes.get(i), 0, d, 10 + i * 3, 3);
        d[tail] = specConstants.length;
        System.arraycopy(specConstants, 0, d, tail + 1, specConstants.length);
        d[stridesAt] = bindingStrides.length;
        bindingStrides[0] = stride;
        System.arraycopy(bindingStrides, 0, d, stridesAt + 1, bindingStrides.length);
        return d;
    }
}
