// Device-level caches (plan, Phase 1b): VkSampler keyed by filter/wrap/anisotropy, and the VkPipelineCache persisted to disk.
#pragma once

#include <cstdint>
#include <map>
#include <mutex>
#include <string>
#include <tuple>

#include "gdxvk_internal.h"

namespace gdxvk {

struct SamplerKey {
    VkFilter minFilter = VK_FILTER_LINEAR;
    VkFilter magFilter = VK_FILTER_LINEAR;
    VkSamplerMipmapMode mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    VkSamplerAddressMode wrapU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    VkSamplerAddressMode wrapV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    uint32_t anisotropy = 1; // whole numbers only, so the key is exact; clamped to the device limit
    bool mipmapped = true;   // false: only level 0 is ever sampled (GL min filters NEAREST and LINEAR)

    auto tie() const { return std::make_tuple(minFilter, magFilter, mipmapMode, wrapU, wrapV, anisotropy, mipmapped); }
    bool operator<(const SamplerKey& o) const { return tie() < o.tie(); }
};

// Samplers are never destroyed individually: the key space is tiny, so the count stays bounded (the soak test checks it).
class SamplerCache {
public:
    explicit SamplerCache(ContextHandles& h, bool anisotropyEnabled) : h_(h), anisotropy_(anisotropyEnabled) {}
    ~SamplerCache();
    VkSampler get(SamplerKey key); // thread-safe
    size_t size() const;

private:
    ContextHandles& h_;
    bool anisotropy_;
    mutable std::mutex mutex_;
    std::map<SamplerKey, VkSampler> samplers_;
};

// Loaded at startup if the file's header matches this device and driver, saved at shutdown.
class PipelineCacheStore {
public:
    PipelineCacheStore(ContextHandles& h, const std::string& path);
    ~PipelineCacheStore();
    VkPipelineCache handle() const { return cache_; }
    size_t loadedBytes() const { return loadedBytes_; }
    bool save(); // atomic: temp file, then rename. No-op without a path.

private:
    ContextHandles& h_;
    std::string path_;
    VkPipelineCache cache_ = VK_NULL_HANDLE;
    size_t loadedBytes_ = 0;
};

} // namespace gdxvk
