// The one pipeline layout per device (plan, Core decision 2):
//   push constants 0..127, stages VERTEX | FRAGMENT | COMPUTE
//   set 0  binding 0 UNIFORM_BUFFER_DYNAMIC (camera/frame globals, <= 16 KB range)
//          binding 1 STORAGE_BUFFER_DYNAMIC (transforms, materials, bones, lights)
//   set 1  binding 0 COMBINED_IMAGE_SAMPLER
//            Tier A: a runtime array of tableSize entries, PARTIALLY_BOUND | UPDATE_AFTER_BIND | UPDATE_UNUSED_WHILE_PENDING,
//                    one global set bound once per command buffer; textures are slots in it.
//            Tier B: a single sampler; every texture owns a descriptor set that is rebound on a texture change.
// Dynamic buffers live in set 0 and the update-after-bind table in set 1 because one layout cannot hold both
// (VUID-VkDescriptorSetLayoutCreateInfo-descriptorType-03001 / -flags-00280).
#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "gdxvk_internal.h"

namespace gdxvk {

class DescriptorLayouts {
public:
    static constexpr uint32_t kUniformRange = 16384; // Set 0 binding 0 descriptor range
    static constexpr uint32_t kPushConstantBytes = 128;
    static constexpr VkShaderStageFlags kPushStages = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT;

    // Returns null and fills `error` on failure.
    static DescriptorLayouts* create(ContextHandles& h, uint32_t tableSize, std::string& error);
    ~DescriptorLayouts();
    DescriptorLayouts(const DescriptorLayouts&) = delete;
    DescriptorLayouts& operator=(const DescriptorLayouts&) = delete;

    VkDescriptorSetLayout set0Layout() const { return set0_; }
    VkDescriptorSetLayout set1Layout() const { return set1_; }
    VkPipelineLayout pipelineLayout() const { return pipelineLayout_; }
    uint32_t tableSize() const { return tableSize_; }   // Tier A slots (0 on Tier B)
    uint32_t uniformRange() const { return uniformRange_; } // min(kUniformRange, maxUniformBufferRange)

    // ---- Tier A: slots in the global table ------------------------------------------------------------------------------
    VkDescriptorSet globalTextureSet() const { return globalSet_; }
    int allocSlot();           // -1 when the table is full
    void freeSlot(int slot);   // call only once the GPU is done with it (via the deletion queue)
    void writeSlot(int slot, VkImageView view, VkSampler sampler);
    uint32_t slotsInUse();

    // ---- Tier B: one descriptor set per texture -------------------------------------------------------------------------
    struct TextureSet {
        VkDescriptorSet set = VK_NULL_HANDLE;
        uint32_t pool = 0;
    };
    bool allocTextureSet(TextureSet& out);
    void freeTextureSet(const TextureSet& s);
    void writeTextureSet(const TextureSet& s, VkImageView view, VkSampler sampler);

private:
    DescriptorLayouts(ContextHandles& h) : h_(h) {}
    ContextHandles& h_;
    VkDescriptorSetLayout set0_ = VK_NULL_HANDLE, set1_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    uint32_t tableSize_ = 0, uniformRange_ = kUniformRange;

    std::mutex mutex_;
    VkDescriptorPool globalPool_ = VK_NULL_HANDLE; // Tier A
    VkDescriptorSet globalSet_ = VK_NULL_HANDLE;
    std::vector<int> freeSlots_;
    uint32_t nextSlot_ = 0, slotsInUse_ = 0;

    std::vector<VkDescriptorPool> pools_; // Tier B; freed sets return to the pool they came from
};

} // namespace gdxvk
