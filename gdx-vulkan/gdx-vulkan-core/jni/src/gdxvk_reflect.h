// Shader load-time validation (plan, Core decision 2): SPIRV-Reflect is a validator only. Every descriptor a shader declares must exist
// in the one pipeline layout, with the right type, and the push-constant block must fit in 128 bytes; otherwise loading fails
// naming the shader. This catches a Tier A shader (bindless texture array) loaded on Tier B and the reverse, which Vulkan itself would
// only report as a validation-layer message at draw time.
#pragma once

#include <cstdint>
#include <string>

#include "gdxvk_internal.h"

namespace gdxvk {

// Returns an empty string when the shader fits the layout, otherwise a message that names `shaderName`.
std::string validateShader(const std::string& shaderName, const uint32_t* spirv, size_t words, VkShaderStageFlagBits expectedStage, bool tierA,
                           uint32_t tableSize);

} // namespace gdxvk
