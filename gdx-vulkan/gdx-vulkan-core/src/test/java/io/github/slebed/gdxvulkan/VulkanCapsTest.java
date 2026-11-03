package io.github.slebed.gdxvulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VulkanCapsTest {
    private static String[] sample(String tier, String reason) {
        return new String[] {
            "deviceName", "Test GPU", "driverInfo", "drv 1.0", "vendorId", "4318", "deviceId", "7",
            "deviceApiVersion", String.valueOf((1 << 22) | (3 << 12) | 250), "effectiveApiVersion", String.valueOf((1 << 22) | (3 << 12)),
            "deviceType", "2", "validationEnabled", "true", "tier", tier, "tierForced", "false", "tierReason", reason,
            "textureTableSize", "4096", "maxImageDimension2D", "16384", "maxSamplerAnisotropy", "16.0",
            "maxPushConstantsSize", "256", "minUniformBufferOffsetAlignment", "64", "minStorageBufferOffsetAlignment", "16",
            "graphicsQueueFamily", "0"
        };
    }

    @Test void parsesTierAAndVersion() {
        VulkanCaps c = VulkanCaps.fromNative(sample("A", ""));
        assertEquals(VulkanCaps.Tier.A, c.tier);
        assertEquals(1, c.apiMajor());
        assertEquals(3, c.apiMinor());
        assertEquals(4096, c.textureTableSize);
        assertEquals(4318, c.vendorId);
        assertTrue(c.validationEnabled);
        assertFalse(c.tierForced);
        assertEquals("Test GPU (Vulkan 1.3, Tier A)", c.toString());
    }

    @Test void parsesTierBReason() {
        VulkanCaps c = VulkanCaps.fromNative(sample("B", "dynamicRendering"));
        assertEquals(VulkanCaps.Tier.B, c.tier);
        assertEquals("Test GPU (Vulkan 1.3, Tier B: dynamicRendering)", c.toString());
    }
}
