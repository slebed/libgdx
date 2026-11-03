package io.github.slebed.gdxvulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/**
 * Needs the native library and a Vulkan ICD (lavapipe in CI). Skips, not fails, when either is missing, unless
 * {@code -Dgdxvulkan.requireDriver=true} (set on the CI lavapipe job so a missing driver is a failure there).
 */
class GdxVkContextTest {
    private static final boolean REQUIRE = Boolean.getBoolean("gdxvulkan.requireDriver");

    private static GdxVkContext openOrSkip(GdxVkConfig cfg) {
        try {
            return GdxVkContext.create(cfg);
        } catch (VulkanLoaderException | VulkanDeviceException e) {
            if (REQUIRE) throw e;
            assumeTrue(false, "No Vulkan loader/driver available: " + e.getMessage());
            return null;
        }
    }

    @Test void rejectsBadApiMinor() {
        GdxVkConfig cfg = new GdxVkConfig();
        cfg.maxApiMinor = 7;
        assertThrows(IllegalArgumentException.class, () -> GdxVkContext.create(cfg));
    }

    @Test void createsContextAndReportsCaps() {
        try (GdxVkContext ctx = openOrSkip(GdxVkConfig.fromSystemProperties())) {
            VulkanCaps c = ctx.caps();
            assertNotNull(c.deviceName);
            assertTrue(c.effectiveApiVersion >= (1 << 22 | 1 << 12));
            assertTrue(c.maxPushConstantsSize >= 128);
            assertTrue(c.textureTableSize >= 16);
        }
    }

    @Test void forceTierBIsHonoured() {
        GdxVkConfig cfg = GdxVkConfig.fromSystemProperties();
        cfg.forceTierB = true;
        try (GdxVkContext ctx = openOrSkip(cfg)) {
            assertEquals(VulkanCaps.Tier.B, ctx.caps().tier);
            assertTrue(ctx.caps().tierForced);
            assertTrue(ctx.caps().tierReason.contains("forceTierB"));
        }
    }

    @Test void closeIsIdempotent() {
        GdxVkContext ctx = openOrSkip(GdxVkConfig.fromSystemProperties());
        ctx.close();
        ctx.close();
        assertTrue(ctx.isClosed());
    }
}
