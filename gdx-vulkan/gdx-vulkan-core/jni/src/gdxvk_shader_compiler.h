// GLSL to SPIR-V at run time, behind an interface so the compiler (glslang today, docs/SHADER_COMPILER.md) can change without touching its users.
// The input is Vulkan GLSL (#version 450): what the stock-GLSL translator produces, or what a caller wrote for Vulkan.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace gdxvk {

enum class ShaderStage { Vertex, Fragment, Compute };

struct CompileRequest {
    ShaderStage stage = ShaderStage::Fragment;
    std::string source;
    // Shown in the log in front of the line number. The line numbers are those of `source` as given: a caller that rewrote the text maps them back.
    std::string name = "shader";
};

struct CompileResult {
    bool ok = false;
    std::vector<uint32_t> spirv;
    std::string log; // the compiler's messages: errors and warnings, empty when it had nothing to say
};

class ShaderCompiler {
public:
    virtual ~ShaderCompiler() = default;
    // Thread safe: callers may compile on worker threads.
    virtual CompileResult compile(const CompileRequest& request) = 0;
    // The glslang compiler, or null when the library was built without it (GDXVK_GLSLANG=OFF).
    static std::unique_ptr<ShaderCompiler> createGlslang();
};

} // namespace gdxvk
