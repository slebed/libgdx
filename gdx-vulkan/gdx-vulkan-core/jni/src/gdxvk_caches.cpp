#include "gdxvk_caches.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <vector>

namespace gdxvk {

SamplerCache::~SamplerCache() {
    for (auto& kv : samplers_) vkDestroySampler(h_.device, kv.second, nullptr);
}

VkSampler SamplerCache::get(SamplerKey key) {
    const uint32_t maxAniso = static_cast<uint32_t>(h_.limits.maxSamplerAnisotropy);
    key.anisotropy = anisotropy_ ? std::min(std::max(key.anisotropy, 1u), std::max(maxAniso, 1u)) : 1u;
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = samplers_.find(key);
    if (it != samplers_.end()) return it->second;

    VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = key.magFilter;
    si.minFilter = key.minFilter;
    si.mipmapMode = key.mipmapMode;
    si.addressModeU = key.wrapU;
    si.addressModeV = key.wrapV;
    si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.anisotropyEnable = key.anisotropy > 1 ? VK_TRUE : VK_FALSE;
    si.maxAnisotropy = static_cast<float>(key.anisotropy);
    si.maxLod = VK_LOD_CLAMP_NONE;
    si.borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
    VkSampler s = VK_NULL_HANDLE;
    if (vkCreateSampler(h_.device, &si, nullptr, &s) != VK_SUCCESS) return VK_NULL_HANDLE;
    samplers_.emplace(key, s);
    return s;
}

size_t SamplerCache::size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return samplers_.size();
}

PipelineCacheStore::PipelineCacheStore(ContextHandles& h, const std::string& path) : h_(h), path_(path) {
    std::vector<char> data;
    if (!path_.empty()) {
        if (FILE* f = std::fopen(path_.c_str(), "rb")) {
            std::fseek(f, 0, SEEK_END);
            const long n = std::ftell(f);
            std::fseek(f, 0, SEEK_SET);
            if (n > 0) {
                data.resize(static_cast<size_t>(n));
                if (std::fread(data.data(), 1, data.size(), f) != data.size()) data.clear();
            }
            std::fclose(f);
        }
    }
    // The driver validates the blob too, but checking the header ourselves means a stale file from another GPU or driver
    // is ignored cleanly instead of being fed to the driver.
    if (data.size() >= VK_UUID_SIZE + 16) {
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(h_.physical, &p);
        uint32_t header[4];
        std::memcpy(header, data.data(), sizeof(header)); // length, version (1), vendorID, deviceID
        const bool ok = header[1] == VK_PIPELINE_CACHE_HEADER_VERSION_ONE && header[2] == p.vendorID && header[3] == p.deviceID &&
                        std::memcmp(data.data() + 16, p.pipelineCacheUUID, VK_UUID_SIZE) == 0;
        if (!ok) data.clear();
    } else {
        data.clear();
    }

    VkPipelineCacheCreateInfo ci{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
    ci.initialDataSize = data.size();
    ci.pInitialData = data.empty() ? nullptr : data.data();
    if (vkCreatePipelineCache(h_.device, &ci, nullptr, &cache_) == VK_SUCCESS) {
        loadedBytes_ = data.size();
    } else if (!data.empty()) { // driver rejected the blob: start empty
        ci.initialDataSize = 0;
        ci.pInitialData = nullptr;
        vkCreatePipelineCache(h_.device, &ci, nullptr, &cache_);
    }
}

PipelineCacheStore::~PipelineCacheStore() {
    save();
    if (cache_ != VK_NULL_HANDLE) vkDestroyPipelineCache(h_.device, cache_, nullptr);
}

bool PipelineCacheStore::save() {
    if (path_.empty() || cache_ == VK_NULL_HANDLE) return false;
    size_t size = 0;
    if (vkGetPipelineCacheData(h_.device, cache_, &size, nullptr) != VK_SUCCESS || size == 0) return false;
    std::vector<char> data(size);
    if (vkGetPipelineCacheData(h_.device, cache_, &size, data.data()) != VK_SUCCESS) return false;
    const std::string tmp = path_ + ".tmp";
    FILE* f = std::fopen(tmp.c_str(), "wb");
    if (!f) return false;
    const bool wrote = std::fwrite(data.data(), 1, size, f) == size;
    std::fclose(f);
    if (!wrote) {
        std::remove(tmp.c_str());
        return false;
    }
    std::remove(path_.c_str()); // rename() does not replace on Windows
    return std::rename(tmp.c_str(), path_.c_str()) == 0;
}

} // namespace gdxvk
