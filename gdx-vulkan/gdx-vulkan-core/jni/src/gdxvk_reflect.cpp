#include "gdxvk_reflect.h"

#include <vector>

#include "gdxvk_layout.h"
#include "spirv-reflect/spirv_reflect.h"

namespace gdxvk {

namespace {

const char* typeName(SpvReflectDescriptorType t) {
    switch (t) {
        case SPV_REFLECT_DESCRIPTOR_TYPE_SAMPLER: return "sampler";
        case SPV_REFLECT_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER: return "combined image sampler";
        case SPV_REFLECT_DESCRIPTOR_TYPE_SAMPLED_IMAGE: return "sampled image";
        case SPV_REFLECT_DESCRIPTOR_TYPE_STORAGE_IMAGE: return "storage image";
        case SPV_REFLECT_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER: return "uniform texel buffer";
        case SPV_REFLECT_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER: return "storage texel buffer";
        case SPV_REFLECT_DESCRIPTOR_TYPE_UNIFORM_BUFFER: return "uniform buffer";
        case SPV_REFLECT_DESCRIPTOR_TYPE_STORAGE_BUFFER: return "storage buffer";
        case SPV_REFLECT_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC: return "dynamic uniform buffer";
        case SPV_REFLECT_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC: return "dynamic storage buffer";
        case SPV_REFLECT_DESCRIPTOR_TYPE_INPUT_ATTACHMENT: return "input attachment";
        default: return "unknown";
    }
}

const char* stageName(VkShaderStageFlagBits s) {
    switch (s) {
        case VK_SHADER_STAGE_VERTEX_BIT: return "vertex";
        case VK_SHADER_STAGE_FRAGMENT_BIT: return "fragment";
        case VK_SHADER_STAGE_COMPUTE_BIT: return "compute";
        default: return "other";
    }
}

} // namespace

std::string validateShader(const std::string& name, const uint32_t* spirv, size_t words, VkShaderStageFlagBits expectedStage, bool tierA,
                           uint32_t tableSize) {
    const std::string prefix = "shader '" + name + "': ";
    SpvReflectShaderModule module;
    if (spvReflectCreateShaderModule(words * sizeof(uint32_t), spirv, &module) != SPV_REFLECT_RESULT_SUCCESS) {
        return prefix + "not valid SPIR-V (reflection failed)";
    }
    std::string error;

    if (static_cast<VkShaderStageFlagBits>(module.shader_stage) != expectedStage) {
        error = prefix + "is a " + stageName(static_cast<VkShaderStageFlagBits>(module.shader_stage)) + " shader but was loaded as a " +
                stageName(expectedStage) + " shader";
    }

    uint32_t count = 0;
    spvReflectEnumerateDescriptorBindings(&module, &count, nullptr);
    std::vector<SpvReflectDescriptorBinding*> bindings(count);
    spvReflectEnumerateDescriptorBindings(&module, &count, bindings.data());

    for (const SpvReflectDescriptorBinding* b : bindings) {
        if (!error.empty()) break;
        const std::string where = prefix + "set " + std::to_string(b->set) + " binding " + std::to_string(b->binding) + " (" +
                                  typeName(b->descriptor_type) + ") ";
        if (b->set == 0 && b->binding == 0) {
            if (b->descriptor_type != SPV_REFLECT_DESCRIPTOR_TYPE_UNIFORM_BUFFER || b->count != 1) {
                error = where + "must be the frame uniform buffer (UNIFORM_BUFFER_DYNAMIC, one descriptor)";
            } else if (b->block.size > DescriptorLayouts::kUniformRange) {
                error = where + "is " + std::to_string(b->block.size) + " bytes; the frame uniform range is " +
                        std::to_string(DescriptorLayouts::kUniformRange);
            }
        } else if (b->set == 0 && b->binding == 1) {
            if (b->descriptor_type != SPV_REFLECT_DESCRIPTOR_TYPE_STORAGE_BUFFER || b->count != 1) {
                error = where + "must be the frame storage buffer (STORAGE_BUFFER_DYNAMIC, one descriptor)";
            }
        } else if (b->set == 1 && b->binding == 0) {
            if (b->descriptor_type != SPV_REFLECT_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
                error = where + "must be a combined image sampler";
            } else if (tierA && b->count != 0 && b->count > tableSize) {
                error = where + "declares " + std::to_string(b->count) + " textures but the Tier A table has " + std::to_string(tableSize);
            } else if (tierA && b->count == 1) {
                error = where + "declares one sampler but the Tier A layout is a bindless table (runtime array); "
                                "compile this shader with -DGDX_TIER_A=1";
            } else if (!tierA && b->count != 1) {
                error = where + "declares a texture array but the Tier B layout binds one texture per descriptor set; "
                                "compile this shader without GDX_TIER_A";
            }
        } else {
            error = where + "is not part of the gdx-vulkan pipeline layout (set 0: bindings 0-1, set 1: binding 0)";
        }
    }

    if (error.empty()) {
        uint32_t pcCount = 0;
        spvReflectEnumeratePushConstantBlocks(&module, &pcCount, nullptr);
        std::vector<SpvReflectBlockVariable*> blocks(pcCount);
        spvReflectEnumeratePushConstantBlocks(&module, &pcCount, blocks.data());
        for (const SpvReflectBlockVariable* blk : blocks) {
            if (blk->offset + blk->padded_size > DescriptorLayouts::kPushConstantBytes) {
                error = prefix + "push constants use " + std::to_string(blk->offset + blk->padded_size) + " bytes; the layout has " +
                        std::to_string(DescriptorLayouts::kPushConstantBytes);
                break;
            }
        }
    }
    spvReflectDestroyShaderModule(&module);
    return error;
}

} // namespace gdxvk
