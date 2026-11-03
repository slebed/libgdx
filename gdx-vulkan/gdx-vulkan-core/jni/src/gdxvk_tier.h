// Tier selection for gdx-vulkan. Deliberately free of Vulkan headers so it can be unit-tested
// without a device or driver (see test/tier_test.cpp).
//
// Tier A: Vulkan >= 1.3 core + bindless texture table + dynamic rendering (no flush on texture switch).
// Tier B: everything else (render-pass cache, per-texture descriptor sets, flush on texture switch).
//
// Decision (v5.0 plan, Phase 1a): Tier A requires *core* 1.3. Devices that are 1.2 + extensions
// (VK_KHR_dynamic_rendering, VK_EXT_extended_dynamic_state) run Tier B for now; revisit with data.
#pragma once

#include <algorithm>
#include <cstdint>
#include <string>

namespace gdxvk {

enum class Tier : int { A = 0, B = 1 };

constexpr uint32_t makeApiVersion(uint32_t major, uint32_t minor) {
    return (major << 22u) | (minor << 12u);
}
constexpr uint32_t kApi11 = makeApiVersion(1, 1);
constexpr uint32_t kApi12 = makeApiVersion(1, 2);
constexpr uint32_t kApi13 = makeApiVersion(1, 3);
constexpr uint32_t kApi14 = makeApiVersion(1, 4);

constexpr uint32_t kMaxTextureTable = 4096; // upper bound on Set 1 table size
constexpr uint32_t kMinTierATable = 128;    // below this, bindless is not worth it -> Tier B

struct TierInputs {
    // Effective API version = min(device version, version the instance was created with), patch stripped.
    uint32_t effectiveApiVersion = 0;
    bool forceTierB = false;

    // Reported by the device (VkPhysicalDeviceVulkan12Features / Vulkan13Features). False when the
    // struct could not be queried because the effective version is too low.
    bool descriptorBindingPartiallyBound = false;
    bool descriptorBindingSampledImageUpdateAfterBind = false;
    bool descriptorBindingUpdateUnusedWhilePending = false;
    bool shaderSampledImageArrayNonUniformIndexing = false;
    bool runtimeDescriptorArray = false;
    bool dynamicRendering = false;

    // VkPhysicalDeviceVulkan12Properties (0 when not queryable).
    uint32_t maxPerStageDescriptorUpdateAfterBindSampledImages = 0;
    uint32_t maxPerStageDescriptorUpdateAfterBindSamplers = 0;
    uint32_t maxDescriptorSetUpdateAfterBindSampledImages = 0;
    uint32_t maxDescriptorSetUpdateAfterBindSamplers = 0;

    // VkPhysicalDeviceLimits (always available).
    uint32_t maxPerStageDescriptorSampledImages = 0;
    uint32_t maxPerStageDescriptorSamplers = 0;
};

struct TierDecision {
    Tier tier = Tier::B;
    bool forced = false;          // Tier B only because forceTierB was set
    uint32_t textureTableSize = 0; // Tier A: Set 1 table slots. Tier B: per-stage texture limit (clamped).
    std::string reason;           // Tier B: comma-separated list of what is missing. Empty for Tier A.
};

inline void appendReason(std::string& out, const char* what) {
    if (!out.empty()) out += ", ";
    out += what;
}

inline TierDecision decideTier(const TierInputs& in) {
    TierDecision d;

    const uint32_t tierBLimit =
        std::min({in.maxPerStageDescriptorSampledImages, in.maxPerStageDescriptorSamplers, kMaxTextureTable});

    // Table size is the minimum of all four update-after-bind limits (combined image-samplers count
    // against both the sampled-image and the sampler limits), capped at kMaxTextureTable.
    const uint32_t tierATable = std::min({kMaxTextureTable, in.maxPerStageDescriptorUpdateAfterBindSampledImages,
                                          in.maxPerStageDescriptorUpdateAfterBindSamplers,
                                          in.maxDescriptorSetUpdateAfterBindSampledImages,
                                          in.maxDescriptorSetUpdateAfterBindSamplers});

    std::string missing;
    if (in.forceTierB) {
        d.forced = true;
        appendReason(missing, "forceTierB");
    }
    if (in.effectiveApiVersion < kApi13) appendReason(missing, "Vulkan 1.3 core");
    if (!in.descriptorBindingPartiallyBound) appendReason(missing, "descriptorBindingPartiallyBound");
    if (!in.descriptorBindingSampledImageUpdateAfterBind)
        appendReason(missing, "descriptorBindingSampledImageUpdateAfterBind");
    if (!in.descriptorBindingUpdateUnusedWhilePending)
        appendReason(missing, "descriptorBindingUpdateUnusedWhilePending");
    if (!in.shaderSampledImageArrayNonUniformIndexing)
        appendReason(missing, "shaderSampledImageArrayNonUniformIndexing");
    if (!in.runtimeDescriptorArray) appendReason(missing, "runtimeDescriptorArray");
    if (!in.dynamicRendering) appendReason(missing, "dynamicRendering");
    if (tierATable < kMinTierATable) appendReason(missing, "update-after-bind texture table < 128 slots");

    if (missing.empty()) {
        d.tier = Tier::A;
        d.textureTableSize = tierATable;
    } else {
        d.tier = Tier::B;
        d.textureTableSize = tierBLimit;
        d.reason = missing;
    }
    return d;
}

} // namespace gdxvk
