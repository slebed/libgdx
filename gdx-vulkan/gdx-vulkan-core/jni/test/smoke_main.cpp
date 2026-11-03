// Headless smoke test: create a context on whatever ICD is present, print the report, destroy it.
// Exit codes: 0 ok, 2 no Vulkan loader/ICD (CI treats as "skipped" locally, failure on the lavapipe job), 1 other error.
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "gdxvk_context.h"

int main(int argc, char** argv) {
    gdxvk::ContextConfig cfg;
    for (int i = 1; i < argc; ++i) {
        if (!std::strcmp(argv[i], "--validation")) cfg.enableValidation = true;
        else if (!std::strcmp(argv[i], "--force-tier-b")) cfg.forceTierB = true;
    }
    std::string err, kind;
    auto ctx = gdxvk::Context::create(cfg, err, kind);
    if (!ctx) {
        std::fprintf(stderr, "smoke: %s: %s\n", kind.c_str(), err.c_str());
        return (kind == "loader" || kind == "device") ? 2 : 1;
    }
    const gdxvk::DeviceInfo& i = ctx->info();
    std::printf("device=%s\ndriver=%s\napi=%u.%u\ntier=%s\ntierReason=%s\ntextureTable=%u\nvalidation=%d\n", i.deviceName.c_str(),
                i.driverInfo.c_str(), i.effectiveApiVersion >> 22, (i.effectiveApiVersion >> 12) & 0x3ff,
                i.tier.tier == gdxvk::Tier::A ? "A" : "B", i.tier.reason.c_str(), i.tier.textureTableSize, i.validationEnabled);
    return 0;
}
