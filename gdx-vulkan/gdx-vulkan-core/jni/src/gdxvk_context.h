// Native Vulkan context: instance, physical device, device, VMA, tier decision. Surfaces and swapchains
// live in gdxvk_presenter.h and borrow the handles from here.
#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include "gdxvk_tier.h"

namespace gdxvk {

struct ContextConfig {
    bool enableValidation = false;
    bool forceTierB = false;
    uint32_t requestedApiVersion = kApi13; // instance apiVersion; effective = min(device, requested)
    int preferredDeviceIndex = -1;         // -1 = pick best (discrete > integrated > virtual > cpu)
    std::string appName = "gdx-vulkan";
    std::string pipelineCachePath; // empty = in-memory only; otherwise loaded at startup and saved at shutdown
};

struct DeviceInfo {
    std::string deviceName;
    std::string driverInfo;
    uint32_t vendorId = 0;
    uint32_t deviceId = 0;
    uint32_t deviceApiVersion = 0;
    uint32_t effectiveApiVersion = 0;
    uint32_t deviceType = 0; // VkPhysicalDeviceType
    bool validationEnabled = false;
    TierDecision tier;
    uint32_t maxImageDimension2D = 0;
    float maxSamplerAnisotropy = 0.f;
    uint32_t maxPushConstantsSize = 0;
    uint32_t minUniformBufferOffsetAlignment = 0;
    uint32_t minStorageBufferOffsetAlignment = 0;
    uint32_t graphicsQueueFamily = 0;
    uint32_t maxUniformBufferRange = 0;
    bool timelineSemaphores = false; // GPU completion tracked by a timeline semaphore (else one fence per submit)
    uint32_t pipelineCacheLoadedBytes = 0;
};

struct ContextHandles;

class Context {
public:
    // Returns null and fills `error` on failure. `errorKind` is one of: "loader", "device", "init".
    static std::unique_ptr<Context> create(const ContextConfig& cfg, std::string& error, std::string& errorKind);
    ~Context();
    Context(const Context&) = delete;
    Context& operator=(const Context&) = delete;

    const DeviceInfo& info() const;

    // Number of validation-layer warnings/errors seen so far. CI requires this to stay 0.
    uint32_t validationMessageCount() const;

    // Raw handles for native code; include gdxvk_internal.h to use them.
    const ContextHandles& handles() const;

private:
    Context();
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace gdxvk
