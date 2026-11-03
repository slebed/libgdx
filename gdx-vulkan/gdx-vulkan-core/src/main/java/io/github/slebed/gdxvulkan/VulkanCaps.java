package io.github.slebed.gdxvulkan;

import java.util.Map;

/** Immutable snapshot of what the selected device offers and which tier the backend chose. */
public final class VulkanCaps {
    public enum Tier { A, B }

    public final String deviceName;
    public final String driverInfo;
    public final int vendorId;
    public final int deviceId;
    /** Vulkan version the device reports, packed as VK_MAKE_API_VERSION. */
    public final int deviceApiVersion;
    /** min(device, requested instance version), patch stripped. */
    public final int effectiveApiVersion;
    /** VkPhysicalDeviceType: 1 integrated, 2 discrete, 3 virtual, 4 cpu. */
    public final int deviceType;
    public final boolean validationEnabled;
    public final Tier tier;
    public final boolean tierForced;
    /** Tier B only: comma-separated list of what the device lacks (or "forceTierB"). Empty for Tier A. */
    public final String tierReason;
    public final int textureTableSize;
    public final int maxImageDimension2D;
    public final float maxSamplerAnisotropy;
    public final int maxPushConstantsSize;
    public final int minUniformBufferOffsetAlignment;
    public final int minStorageBufferOffsetAlignment;
    public final int graphicsQueueFamily;

    private VulkanCaps(Map<String, String> m) {
        deviceName = m.get("deviceName");
        driverInfo = m.get("driverInfo");
        vendorId = (int) Long.parseLong(m.get("vendorId"));
        deviceId = (int) Long.parseLong(m.get("deviceId"));
        deviceApiVersion = (int) Long.parseLong(m.get("deviceApiVersion"));
        effectiveApiVersion = (int) Long.parseLong(m.get("effectiveApiVersion"));
        deviceType = Integer.parseInt(m.get("deviceType"));
        validationEnabled = Boolean.parseBoolean(m.get("validationEnabled"));
        tier = "A".equals(m.get("tier")) ? Tier.A : Tier.B;
        tierForced = Boolean.parseBoolean(m.get("tierForced"));
        tierReason = m.get("tierReason");
        textureTableSize = Integer.parseInt(m.get("textureTableSize"));
        maxImageDimension2D = Integer.parseInt(m.get("maxImageDimension2D"));
        maxSamplerAnisotropy = Float.parseFloat(m.get("maxSamplerAnisotropy"));
        maxPushConstantsSize = Integer.parseInt(m.get("maxPushConstantsSize"));
        minUniformBufferOffsetAlignment = Integer.parseInt(m.get("minUniformBufferOffsetAlignment"));
        minStorageBufferOffsetAlignment = Integer.parseInt(m.get("minStorageBufferOffsetAlignment"));
        graphicsQueueFamily = Integer.parseInt(m.get("graphicsQueueFamily"));
    }

    /** Parses the flat [key, value, ...] array returned by the native layer. */
    static VulkanCaps fromNative(String[] kv) {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new VulkanCaps(m);
    }

    public int apiMajor() { return effectiveApiVersion >>> 22; }
    public int apiMinor() { return (effectiveApiVersion >>> 12) & 0x3ff; }

    @Override public String toString() {
        return deviceName + " (Vulkan " + apiMajor() + "." + apiMinor() + ", Tier " + tier
            + (tier == Tier.B && !tierReason.isEmpty() ? ": " + tierReason : "") + ")";
    }
}
