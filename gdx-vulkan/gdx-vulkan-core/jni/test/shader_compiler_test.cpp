// The run-time compiler: Vulkan GLSL in, SPIR-V out, errors with the source's own line numbers. No GPU needed.
#include <chrono>
#include <cstdio>
#include <string>

#include "gdxvk_shader_compiler.h"

using gdxvk::CompileRequest;
using gdxvk::ShaderStage;

namespace {
int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

const char* kVertex = R"(#version 450
layout(location = 0) in vec2 a_position;
layout(location = 1) in vec2 a_texCoord0;
layout(location = 0) out vec2 v_texCoords;
layout(std140, set = 0, binding = 0) uniform Params { mat4 u_projTrans; };
void main() {
    v_texCoords = a_texCoord0;
    gl_Position = u_projTrans * vec4(a_position, 0.0, 1.0);
}
)";

const char* kFragment = R"(#version 450
layout(location = 0) in vec2 v_texCoords;
layout(location = 0) out vec4 outColor;
layout(set = 1, binding = 0) uniform sampler2D u_texture;
void main() { outColor = texture(u_texture, v_texCoords); }
)";
} // namespace

int main() {
    auto compiler = gdxvk::ShaderCompiler::createGlslang();
    check(compiler != nullptr, "a glslang compiler exists");
    if (!compiler) return 1;

    CompileRequest vertex;
    vertex.stage = ShaderStage::Vertex;
    vertex.source = kVertex;
    vertex.name = "sprite.vert";
    const auto t0 = std::chrono::steady_clock::now();
    auto v = compiler->compile(vertex);
    const double firstMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    check(v.ok, "the vertex shader compiles");
    check(!v.spirv.empty() && v.spirv[0] == 0x07230203u, "the output starts with the SPIR-V magic number");

    CompileRequest fragment;
    fragment.stage = ShaderStage::Fragment;
    fragment.source = kFragment;
    fragment.name = "sprite.frag";
    auto f = compiler->compile(fragment);
    check(f.ok, "the fragment shader compiles");

    // Many compiles for a stable timing figure.
    const int runs = 50;
    const auto t1 = std::chrono::steady_clock::now();
    for (int i = 0; i < runs; ++i) compiler->compile(vertex);
    const double eachMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t1).count() / runs;
    std::printf("vertex: %zu words; first compile %.2f ms, then %.2f ms each\n", v.spirv.size(), firstMs, eachMs);

    // An error names the file and the line of the source as given.
    CompileRequest broken;
    broken.stage = ShaderStage::Fragment;
    broken.name = "broken.frag";
    broken.source = "#version 450\nlayout(location = 0) out vec4 outColor;\nvoid main() {\n    outColor = vec4(undefinedName);\n}\n";
    auto b = compiler->compile(broken);
    check(!b.ok, "a shader with an undefined name fails");
    check(b.log.find("broken.frag") != std::string::npos && b.log.find(":4") != std::string::npos, "the log names broken.frag and line 4");
    check(b.log.find("undefinedName") != std::string::npos, "the log names the undefined identifier");

    // The compute stage.
    CompileRequest compute;
    compute.stage = ShaderStage::Compute;
    compute.name = "c.comp";
    compute.source = "#version 450\nlayout(local_size_x = 64) in;\nlayout(std430, set = 0, binding = 1) buffer B { uint data[]; };\nvoid main() { data[gl_GlobalInvocationID.x] += 1u; }\n";
    check(compiler->compile(compute).ok, "a compute shader compiles");

    if (failures == 0) std::printf("shader_compiler_test: ok\n");
    return failures == 0 ? 0 : 1;
}
