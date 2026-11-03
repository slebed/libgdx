package io.github.slebed.gdxvulkan;

/** Sampler state for a texture. Equal states share one VkSampler, so the sampler count stays bounded however often it changes. */
public final class GdxVkSampler {
    public static final int NEAREST = 0, LINEAR = 1;
    /** For {@link #mipFilter}: sample level 0 only. */
    public static final int MIP_NONE = 2;
    public static final int REPEAT = 0, CLAMP = 1, MIRROR = 2;

    public int minFilter = LINEAR, magFilter = LINEAR, mipFilter = LINEAR;
    public int wrapU = CLAMP, wrapV = CLAMP;
    /** 1 = off. Clamped to the device limit (and ignored when the device lacks samplerAnisotropy). */
    public int anisotropy = 1;

    public GdxVkSampler() {}

    public GdxVkSampler(int minFilter, int magFilter, int mipFilter, int wrapU, int wrapV, int anisotropy) {
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.mipFilter = mipFilter;
        this.wrapU = wrapU;
        this.wrapV = wrapV;
        this.anisotropy = anisotropy;
    }
}
