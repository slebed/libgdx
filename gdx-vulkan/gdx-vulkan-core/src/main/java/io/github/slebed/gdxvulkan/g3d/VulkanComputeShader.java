package io.github.slebed.gdxvulkan.g3d;

import io.github.slebed.gdxvulkan.GdxVkFrame;

/**
 * A compute shader on the shared pipeline layout (plan, Phase 5). A shader is a {@code .comp} resource built with the others (see {@code
 * gdx-vulkan-core/src/main/shaders}); it sees one storage buffer, a resource buffer bound as Set 0's storage binding
 * ({@code layout(std430, set = 0, binding = 1) buffer ...}), and the 128-byte push-constant block ({@code gdx_pc}, from {@code gdx_vk_common.glsl}).
 *
 * <p>Dispatches queued during a frame run in queue order at the start of that frame's GPU work, after the frame's staged uploads and before its first
 * render pass. A barrier separates each dispatch from the next (so a later one reads what an earlier one wrote), and a final one makes everything
 * visible to the frame's draws: vertex and fragment shaders, indirect draw arguments ({@code COMPUTE_SHADER -> DRAW_INDIRECT}), vertex and index
 * data ({@code VERTEX_ATTRIBUTE_INPUT}), and the next frame's compute. Nothing else is needed from the caller.
 *
 * <p>Integers travel in the push block as float bit patterns: {@link #ints} puts them in {@code u_indices} (words 20-23). Like the batches it
 * holds a {@link GdxVkFrame}, so it survives the swapchain being replaced.
 */
public final class VulkanComputeShader {
    private static final float[] ZERO_PUSH = new float[32];

    private final GdxVkFrame frame;
    private final String key, resource;

    /** @param key identifies the shader in the frame's pipeline cache; @param resource the shader resource, such as {@code "gpu_cull.comp"} */
    public VulkanComputeShader (String key, String resource) {
        this(GdxVkFrame.current(), key, resource);
    }

    /** @param key identifies the shader in the frame's pipeline cache; @param resource the shader resource, such as {@code "gpu_cull.comp"} */
    public VulkanComputeShader (GdxVkFrame frame, String key, String resource) {
        this.frame = frame;
        this.key = key;
        this.resource = resource;
        id(); // created now, so a bad shader fails here and not in the middle of a frame
    }

    private int pipeline = -1;
    private Object pipelineOwner;

    /** The pipeline on the frame's current presenter: looked up again only when the presenter was replaced (no allocation per dispatch). */
    private int id () {
        final Object presenter = frame.presenter();
        if (presenter != pipelineOwner) {
            pipeline = frame.computePipeline(key, key, resource);
            pipelineOwner = presenter;
        }
        return pipeline;
    }

    /**
     * Queues {@code groupsX} workgroups (the shader's {@code local_size_x} invocations each) over the storage buffer.
     * @param push the 32 floats of the push block, or null for zeros; build one with {@link #newPush} and {@link #ints}
     * @param storageBuffer a resource buffer id
     */
    public void dispatch (int groupsX, float[] push, int storageBuffer) {
        frame.dispatch(id(), push == null ? ZERO_PUSH : push, groupsX, storageBuffer);
    }

    public static float[] newPush () {
        return new float[32];
    }

    /** Sets {@code u_indices} (push words 20-23) to up to four integers. */
    public static float[] ints (float[] push, int x, int y, int z, int w) {
        push[20] = Float.intBitsToFloat(x);
        push[21] = Float.intBitsToFloat(y);
        push[22] = Float.intBitsToFloat(z);
        push[23] = Float.intBitsToFloat(w);
        return push;
    }

    /** Workgroups of {@code localSize} invocations needed to cover {@code count} items. */
    public static int groups (int count, int localSize) {
        return (count + localSize - 1) / localSize;
    }
}
