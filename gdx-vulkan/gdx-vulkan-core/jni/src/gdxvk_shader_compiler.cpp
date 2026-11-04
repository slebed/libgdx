#include "gdxvk_shader_compiler.h"

#include <mutex>

#ifdef GDXVK_GLSLANG
#include <glslang/Public/ResourceLimits.h>
#include <glslang/Public/ShaderLang.h>
#include <SPIRV/GlslangToSpv.h>
#endif

namespace gdxvk {

#ifdef GDXVK_GLSLANG

namespace {

// glslang's process-wide setup, once for the life of the process and shared by every compiler object.
void ensureInitialized() {
    static std::once_flag once;
    std::call_once(once, [] { glslang::InitializeProcess(); });
}

EShLanguage languageOf(ShaderStage stage) {
    switch (stage) {
        case ShaderStage::Vertex: return EShLangVertex;
        case ShaderStage::Compute: return EShLangCompute;
        default: return EShLangFragment;
    }
}

class GlslangCompiler final : public ShaderCompiler {
public:
    GlslangCompiler() { ensureInitialized(); }

    PreprocessResult preprocess(const CompileRequest& request) override {
        PreprocessResult result;
        const EShLanguage language = languageOf(request.stage);
        glslang::TShader shader(language);
        const char* text = request.source.c_str();
        const char* name = request.name.c_str();
        shader.setStringsWithLengthsAndNames(&text, nullptr, &name, 1);
        glslang::TShader::ForbidIncluder includer;
        // Legacy GLSL has no #version: read it as desktop 1.10, which accepts attribute/varying; only macros and conditionals are processed here.
        std::string out;
        if (!shader.preprocess(GetDefaultResources(), 110, ENoProfile, false, false, EShMsgOnlyPreprocessor, &out, includer)) {
            result.log = std::string(shader.getInfoLog()) + shader.getInfoDebugLog();
            return result;
        }
        result.text = out;
        result.log = shader.getInfoLog();
        result.ok = true;
        return result;
    }

    CompileResult compile(const CompileRequest& request) override {
        CompileResult result;
        const EShLanguage language = languageOf(request.stage);
        glslang::TShader shader(language);
        const char* text = request.source.c_str();
        const char* name = request.name.c_str();
        shader.setStringsWithLengthsAndNames(&text, nullptr, &name, 1);
        // Vulkan 1.1 and SPIR-V 1.3: every device the library supports takes them (the compatibility path is Vulkan 1.1).
        shader.setEnvInput(glslang::EShSourceGlsl, language, glslang::EShClientVulkan, 100);
        shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_1);
        shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_3);

        const EShMessages messages = static_cast<EShMessages>(EShMsgSpvRules | EShMsgVulkanRules);
        if (!shader.parse(GetDefaultResources(), 450, false, messages)) {
            result.log = std::string(shader.getInfoLog()) + shader.getInfoDebugLog();
            return result;
        }
        glslang::TProgram program;
        program.addShader(&shader);
        if (!program.link(messages)) {
            result.log = std::string(program.getInfoLog()) + program.getInfoDebugLog();
            return result;
        }
        glslang::TIntermediate* intermediate = program.getIntermediate(language);
        if (intermediate == nullptr) {
            result.log = "glslang produced no intermediate representation";
            return result;
        }
        glslang::SpvOptions options;
        options.generateDebugInfo = false;
        options.stripDebugInfo = true;
        options.disableOptimizer = true; // the SPIRV-Tools optimizer is not part of the build yet
        options.optimizeSize = false;
        options.validate = false;
        spv::SpvBuildLogger logger;
        glslang::GlslangToSpv(*intermediate, result.spirv, &logger, &options);
        result.log = std::string(shader.getInfoLog()) + logger.getAllMessages();
        result.ok = !result.spirv.empty();
        return result;
    }
};

} // namespace

std::unique_ptr<ShaderCompiler> ShaderCompiler::createGlslang() { return std::make_unique<GlslangCompiler>(); }

#else

std::unique_ptr<ShaderCompiler> ShaderCompiler::createGlslang() { return nullptr; }

#endif

} // namespace gdxvk
