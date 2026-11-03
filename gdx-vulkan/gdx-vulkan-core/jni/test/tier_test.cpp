// Table-driven tests for gdxvk::decideTier. No Vulkan device required.
#include "gdxvk_tier.h"

#include <cstdio>
#include <string>

using namespace gdxvk;

static int failures = 0;

#define CHECK(cond)                                                                       \
    do {                                                                                  \
        if (!(cond)) {                                                                    \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);         \
            ++failures;                                                                   \
        }                                                                                 \
    } while (0)

static TierInputs capableDesktop() {
    TierInputs in;
    in.effectiveApiVersion = kApi13;
    in.descriptorBindingPartiallyBound = true;
    in.descriptorBindingSampledImageUpdateAfterBind = true;
    in.descriptorBindingUpdateUnusedWhilePending = true;
    in.shaderSampledImageArrayNonUniformIndexing = true;
    in.runtimeDescriptorArray = true;
    in.dynamicRendering = true;
    in.maxPerStageDescriptorUpdateAfterBindSampledImages = 1000000;
    in.maxPerStageDescriptorUpdateAfterBindSamplers = 1000000;
    in.maxDescriptorSetUpdateAfterBindSampledImages = 1000000;
    in.maxDescriptorSetUpdateAfterBindSamplers = 1000000;
    in.maxPerStageDescriptorSampledImages = 1000000;
    in.maxPerStageDescriptorSamplers = 1000000;
    return in;
}

int main() {
    {   // Capable desktop device -> Tier A, table capped at 4096.
        TierDecision d = decideTier(capableDesktop());
        CHECK(d.tier == Tier::A);
        CHECK(!d.forced);
        CHECK(d.textureTableSize == 4096);
        CHECK(d.reason.empty());
    }
    {   // forceTierB wins even on a capable device and says so.
        TierInputs in = capableDesktop();
        in.forceTierB = true;
        TierDecision d = decideTier(in);
        CHECK(d.tier == Tier::B);
        CHECK(d.forced);
        CHECK(d.reason == "forceTierB");
        CHECK(d.textureTableSize == 4096);
    }
    {   // Vulkan 1.4 is fine.
        TierInputs in = capableDesktop();
        in.effectiveApiVersion = kApi14;
        CHECK(decideTier(in).tier == Tier::A);
    }
    {   // Vulkan 1.2 with every feature still falls to Tier B (core 1.3 required).
        TierInputs in = capableDesktop();
        in.effectiveApiVersion = kApi12;
        in.dynamicRendering = false; // not core in 1.2
        TierDecision d = decideTier(in);
        CHECK(d.tier == Tier::B);
        CHECK(d.reason.find("Vulkan 1.3 core") != std::string::npos);
        CHECK(d.reason.find("dynamicRendering") != std::string::npos);
    }
    {   // Missing UPDATE_UNUSED_WHILE_PENDING alone forces Tier B (v3 review bug #1).
        TierInputs in = capableDesktop();
        in.descriptorBindingUpdateUnusedWhilePending = false;
        TierDecision d = decideTier(in);
        CHECK(d.tier == Tier::B);
        CHECK(d.reason == "descriptorBindingUpdateUnusedWhilePending");
    }
    {   // Each single missing feature is reported by name and forces Tier B.
        struct Case { bool TierInputs::*field; const char* name; };
        const Case cases[] = {
            {&TierInputs::descriptorBindingPartiallyBound, "descriptorBindingPartiallyBound"},
            {&TierInputs::descriptorBindingSampledImageUpdateAfterBind, "descriptorBindingSampledImageUpdateAfterBind"},
            {&TierInputs::shaderSampledImageArrayNonUniformIndexing, "shaderSampledImageArrayNonUniformIndexing"},
            {&TierInputs::runtimeDescriptorArray, "runtimeDescriptorArray"},
            {&TierInputs::dynamicRendering, "dynamicRendering"},
        };
        for (const Case& c : cases) {
            TierInputs in = capableDesktop();
            in.*(c.field) = false;
            TierDecision d = decideTier(in);
            CHECK(d.tier == Tier::B);
            CHECK(d.reason == c.name);
        }
    }
    {   // Table size = min of all four update-after-bind limits (sampler limit is the tightest here).
        TierInputs in = capableDesktop();
        in.maxPerStageDescriptorUpdateAfterBindSamplers = 500;
        TierDecision d = decideTier(in);
        CHECK(d.tier == Tier::A);
        CHECK(d.textureTableSize == 500);
    }
    {   // Per-set limit can be the tightest one too.
        TierInputs in = capableDesktop();
        in.maxDescriptorSetUpdateAfterBindSampledImages = 300;
        CHECK(decideTier(in).textureTableSize == 300);
    }
    {   // Table below 128 slots is not worth bindless -> Tier B.
        TierInputs in = capableDesktop();
        in.maxPerStageDescriptorUpdateAfterBindSampledImages = 127;
        TierDecision d = decideTier(in);
        CHECK(d.tier == Tier::B);
        CHECK(d.reason.find("< 128 slots") != std::string::npos);
    }
    {   // Baseline mobile (Vulkan 1.1, nothing queryable): Tier B, limit clamps to the spec minimum 16.
        TierInputs in;
        in.effectiveApiVersion = kApi11;
        in.maxPerStageDescriptorSampledImages = 16;
        in.maxPerStageDescriptorSamplers = 16;
        TierDecision d = decideTier(in);
        CHECK(d.tier == Tier::B);
        CHECK(!d.forced);
        CHECK(d.textureTableSize == 16);
    }
    {   // Tier B limit takes the tighter of image and sampler limits and caps at 4096.
        TierInputs in;
        in.effectiveApiVersion = kApi11;
        in.maxPerStageDescriptorSampledImages = 1000000;
        in.maxPerStageDescriptorSamplers = 64;
        CHECK(decideTier(in).textureTableSize == 64);
        in.maxPerStageDescriptorSamplers = 1000000;
        CHECK(decideTier(in).textureTableSize == 4096);
    }

    if (failures == 0) std::printf("tier_test: all checks passed\n");
    return failures == 0 ? 0 : 1;
}
