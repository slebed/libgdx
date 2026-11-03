package io.github.slebed.gdxvulkan;

/**
 * Custom shading for the 2D batches, replacing a stock {@code ShaderProgram}'s GLSL (plan, Phase 2: the documented 2D exception).
 * A bundle is a vertex and fragment shader compiled to SPIR-V ahead of time, in both tier variants, plus the two user vec4s that reach
 * the shader as {@code u_custom0} and {@code u_custom1} push constants (see {@code gdx_vk_common.glsl}).
 *
 * <p>The batches' vertex layout is fixed (location 0 position, 1 colour, 2 texture coordinate, 3 texture slot on Tier A), so a bundle
 * is normally the stock {@code sprite.vert} with a different fragment shader. Compile the Tier A build with {@code -DGDX_TIER_A=1}.
 * Blend state is not part of a bundle: it follows the batch, as with stock.
 */
public final class VulkanShaderPipelineBundle {
    /** Pushed as {@code u_custom0} when the batch flushes: call {@code batch.flush()} before changing it, or queued sprites take the new value. */
    public final float[] custom0 = new float[4];
    /** Pushed as {@code u_custom1}. */
    public final float[] custom1 = new float[4];

    private final String key;
    private final String vertName, fragName; // resource names, or null when the SPIR-V was passed in
    private final byte[] vertB, vertA, fragB, fragA;

    private VulkanShaderPipelineBundle(String key, String vertName, String fragName, byte[] vertB, byte[] vertA, byte[] fragB, byte[] fragA) {
        this.key = key;
        this.vertName = vertName;
        this.fragName = fragName;
        this.vertB = vertB;
        this.vertA = vertA;
        this.fragB = fragB;
        this.fragA = fragA;
    }

    /** Shaders on the classpath under {@code /shaders/} (what the {@code compileShaders} task produces); {@code vert}, {@code frag} like {@code "sprite.vert"}. */
    public static VulkanShaderPipelineBundle fromResources(String key, String vert, String frag) {
        return new VulkanShaderPipelineBundle(key, vert, frag, null, null, null, null);
    }

    /** SPIR-V you built yourself. @param key identifies the bundle in the pipeline cache: use a different key for different shaders */
    public static VulkanShaderPipelineBundle fromSpirv(String key, byte[] vertTierB, byte[] vertTierA, byte[] fragTierB, byte[] fragTierA) {
        return new VulkanShaderPipelineBundle(key, null, null, vertTierB, vertTierA, fragTierB, fragTierA);
    }

    public String key() {
        return key;
    }

    byte[] vertex(boolean tierA) {
        return vertName != null ? GdxVkShaders.load(vertName, tierA) : tierA ? vertA : vertB;
    }

    byte[] fragment(boolean tierA) {
        return fragName != null ? GdxVkShaders.load(fragName, tierA) : tierA ? fragA : fragB;
    }
}
