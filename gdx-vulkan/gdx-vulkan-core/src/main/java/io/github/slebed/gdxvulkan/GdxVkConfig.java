package io.github.slebed.gdxvulkan;

/** Configuration for {@link GdxVkContext#create(GdxVkConfig)}. */
public class GdxVkConfig {
    /** Enable VK_LAYER_KHRONOS_validation when installed. CI requires zero messages. */
    public boolean enableValidation = false;
    /** Run the Tier B fallback paths even on devices that support Tier A. */
    public boolean forceTierB = false;
    /** Android: build the swapchain with the display rotation (no compositor rotation pass). Default on in {@code VulkanAndroidApplication}. */
    public boolean preRotate = true;

    /**
     * Translated shaders keep their {@code mediump} / {@code lowp} qualifiers as SPIR-V {@code RelaxedPrecision} (half precision on a mobile GPU, as under OpenGL ES): the same shader ran
     * 1.39x faster on an Adreno 650. Default on in {@code VulkanAndroidApplication}, off elsewhere. Applies to shaders translated after the context exists; part of the shader cache key.
     */
    public boolean relaxedPrecision = false;

    /** Android: pace frames with Swappy when the library was built with it and vsync is on. Default on in {@code VulkanAndroidApplication}. */
    public boolean framePacing = true;
    /** Index into the physical device list, or -1 to pick the best (discrete > integrated > virtual > cpu). */
    public int preferredDeviceIndex = -1;
    /** Highest Vulkan 1.x minor version to request (1..4). The effective version is min(device, this). */
    public int maxApiMinor = 3;
    public String applicationName = "gdx-vulkan";
    /** File for the VkPipelineCache: loaded at startup when it matches this device and driver, saved at shutdown. Null = memory only. */
    public String pipelineCachePath = null;

    public GdxVkConfig() {}

    /** Reads {@code gdxvulkan.validation}, {@code gdxvulkan.forceTierB} and {@code gdxvulkan.device} system properties. */
    public static GdxVkConfig fromSystemProperties() {
        GdxVkConfig c = new GdxVkConfig();
        c.enableValidation = Boolean.getBoolean("gdxvulkan.validation");
        c.forceTierB = Boolean.getBoolean("gdxvulkan.forceTierB");
        c.preferredDeviceIndex = Integer.getInteger("gdxvulkan.device", -1);
        c.pipelineCachePath = System.getProperty("gdxvulkan.pipelineCache");
        return c;
    }
}
