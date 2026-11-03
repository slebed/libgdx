#include "gdxvk_layout.h"

#include <algorithm>
#include <memory>

namespace gdxvk {

namespace {
constexpr uint32_t kTierBSetsPerPool = 1024;
}

DescriptorLayouts* DescriptorLayouts::create(ContextHandles& h, uint32_t tableSize, std::string& error) {
    std::unique_ptr<DescriptorLayouts> d(new DescriptorLayouts(h));
    d->uniformRange_ = std::min(kUniformRange, h.limits.maxUniformBufferRange);

    // Set 0: dynamic buffers.
    const VkShaderStageFlags stages = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutBinding b0[2] = {};
    b0[0].binding = 0;
    b0[0].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
    b0[0].descriptorCount = 1;
    b0[0].stageFlags = stages;
    b0[1].binding = 1;
    b0[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
    b0[1].descriptorCount = 1;
    b0[1].stageFlags = stages;
    VkDescriptorSetLayoutCreateInfo l0{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    l0.bindingCount = 2;
    l0.pBindings = b0;
    if (vkCreateDescriptorSetLayout(h.device, &l0, nullptr, &d->set0_) != VK_SUCCESS) {
        error = "vkCreateDescriptorSetLayout (set 0) failed";
        return nullptr;
    }

    // Set 1: textures.
    VkDescriptorSetLayoutBinding b1{};
    b1.binding = 0;
    b1.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b1.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo l1{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    VkDescriptorSetLayoutBindingFlagsCreateInfo flags{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO};
    VkDescriptorBindingFlags bindingFlags = VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT | VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT |
                                            VK_DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING_BIT;
    if (h.tierA) {
        d->tableSize_ = tableSize;
        b1.descriptorCount = tableSize;
        flags.bindingCount = 1;
        flags.pBindingFlags = &bindingFlags;
        l1.pNext = &flags;
        l1.flags = VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT;
    } else {
        b1.descriptorCount = 1;
    }
    l1.bindingCount = 1;
    l1.pBindings = &b1;
    if (vkCreateDescriptorSetLayout(h.device, &l1, nullptr, &d->set1_) != VK_SUCCESS) {
        error = "vkCreateDescriptorSetLayout (set 1) failed";
        return nullptr;
    }

    VkDescriptorSetLayout sets[2] = {d->set0_, d->set1_};
    VkPushConstantRange pcr{kPushStages, 0, kPushConstantBytes};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 2;
    pli.pSetLayouts = sets;
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &pcr;
    if (vkCreatePipelineLayout(h.device, &pli, nullptr, &d->pipelineLayout_) != VK_SUCCESS) {
        error = "vkCreatePipelineLayout failed";
        return nullptr;
    }

    if (h.tierA) {
        VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, tableSize};
        VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pi.flags = VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT;
        pi.maxSets = 1;
        pi.poolSizeCount = 1;
        pi.pPoolSizes = &size;
        VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        if (vkCreateDescriptorPool(h.device, &pi, nullptr, &d->globalPool_) != VK_SUCCESS) {
            error = "vkCreateDescriptorPool (texture table) failed";
            return nullptr;
        }
        ai.descriptorPool = d->globalPool_;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts = &d->set1_;
        if (vkAllocateDescriptorSets(h.device, &ai, &d->globalSet_) != VK_SUCCESS) {
            error = "vkAllocateDescriptorSets (texture table) failed";
            return nullptr;
        }
    }
    return d.release();
}

DescriptorLayouts::~DescriptorLayouts() {
    if (h_.device == VK_NULL_HANDLE) return;
    for (VkDescriptorPool p : pools_) vkDestroyDescriptorPool(h_.device, p, nullptr);
    if (globalPool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(h_.device, globalPool_, nullptr);
    if (pipelineLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(h_.device, pipelineLayout_, nullptr);
    if (set1_ != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(h_.device, set1_, nullptr);
    if (set0_ != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(h_.device, set0_, nullptr);
}

int DescriptorLayouts::allocSlot() {
    std::lock_guard<std::mutex> lock(mutex_);
    int slot;
    if (!freeSlots_.empty()) {
        slot = freeSlots_.back();
        freeSlots_.pop_back();
    } else if (nextSlot_ < tableSize_) {
        slot = static_cast<int>(nextSlot_++);
    } else {
        return -1;
    }
    ++slotsInUse_;
    return slot;
}

void DescriptorLayouts::freeSlot(int slot) {
    std::lock_guard<std::mutex> lock(mutex_);
    freeSlots_.push_back(slot);
    --slotsInUse_;
}

uint32_t DescriptorLayouts::slotsInUse() {
    std::lock_guard<std::mutex> lock(mutex_);
    return slotsInUse_;
}

void DescriptorLayouts::writeSlot(int slot, VkImageView view, VkSampler sampler) {
    VkDescriptorImageInfo ii{sampler, view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = globalSet_;
    w.dstBinding = 0;
    w.dstArrayElement = static_cast<uint32_t>(slot);
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w.pImageInfo = &ii;
    vkUpdateDescriptorSets(h_.device, 1, &w, 0, nullptr);
}

bool DescriptorLayouts::allocTextureSet(TextureSet& out) {
    std::lock_guard<std::mutex> lock(mutex_);
    for (int attempt = 0; attempt < 2; ++attempt) {
        if (pools_.empty() || attempt == 1) {
            VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kTierBSetsPerPool};
            VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            pi.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
            pi.maxSets = kTierBSetsPerPool;
            pi.poolSizeCount = 1;
            pi.pPoolSizes = &size;
            VkDescriptorPool p;
            if (vkCreateDescriptorPool(h_.device, &pi, nullptr, &p) != VK_SUCCESS) return false;
            pools_.push_back(p);
        }
        // Newest pool first; freed sets make older pools usable again, so fall back through them.
        for (size_t i = pools_.size(); i-- > 0;) {
            VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            ai.descriptorPool = pools_[i];
            ai.descriptorSetCount = 1;
            ai.pSetLayouts = &set1_;
            if (vkAllocateDescriptorSets(h_.device, &ai, &out.set) == VK_SUCCESS) {
                out.pool = static_cast<uint32_t>(i);
                return true;
            }
        }
    }
    return false;
}

void DescriptorLayouts::freeTextureSet(const TextureSet& s) {
    std::lock_guard<std::mutex> lock(mutex_);
    vkFreeDescriptorSets(h_.device, pools_[s.pool], 1, &s.set);
}

void DescriptorLayouts::writeTextureSet(const TextureSet& s, VkImageView view, VkSampler sampler) {
    VkDescriptorImageInfo ii{sampler, view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = s.set;
    w.dstBinding = 0;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w.pImageInfo = &ii;
    vkUpdateDescriptorSets(h_.device, 1, &w, 0, nullptr);
}

} // namespace gdxvk
