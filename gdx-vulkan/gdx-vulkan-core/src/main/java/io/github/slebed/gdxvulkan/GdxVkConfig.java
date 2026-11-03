package io.github.slebed.gdxvulkan;

/** Configuration for {@link GdxVkContext#create(GdxVkConfig)}. */
public class GdxVkConfig {
    /** Enable VK_LAYER_KHRONOS_validation when installed. CI requires zero messages. */
    public boolean enableValidation = false;
    /** Run the Tier B fallback paths even on devices that support Tier A. */
    public boolean forceTierB = false;
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
