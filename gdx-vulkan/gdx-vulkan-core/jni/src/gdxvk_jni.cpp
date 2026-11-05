// JNI entry points for io.github.slebed.gdxvulkan.GdxVkNative. Hand-written (Phase 1a decision: plain
// CMake instead of gdx-jnigen until its cross-compile support is verified).
#include <jni.h>
#ifdef __ANDROID__
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "gdxvk_swappy.h"
#endif

#include <memory>
#include <string>
#include <vector>

#include "gdxvk_context.h"
#include "gdxvk_internal.h"
#include "gdxvk_shader_compiler.h"
#include "gdxvk_presenter.h"
#include "gdxvk_deletion.h"
#include "gdxvk_resources.h"
#include "gdxvk_staging.h"
#include "gdxvk_timeline.h"

namespace {

constexpr const char* kPkg = "io/github/slebed/gdxvulkan/";

void throwKind(JNIEnv* env, const std::string& kind, const std::string& msg) {
    const char* cls = "VulkanInitException";
    if (kind == "loader") cls = "VulkanLoaderException";
    else if (kind == "device") cls = "VulkanDeviceException";
    std::string full = std::string(kPkg) + cls;
    jclass c = env->FindClass(full.c_str());
    if (c == nullptr) return; // FindClass already raised NoClassDefFoundError
    env->ThrowNew(c, msg.c_str());
    env->DeleteLocalRef(c);
}

jstring utf(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

gdxvk::Resources& res(jlong ctx) { return *reinterpret_cast<gdxvk::Context*>(ctx)->handles().resources; }

// Java passes small ints: filters 0 nearest / 1 linear, wrap 0 repeat / 1 clamp / 2 mirror.
gdxvk::SamplerKey samplerKey(jint minF, jint magF, jint mipF, jint wrapU, jint wrapV, jint aniso) {
    auto filter = [](jint v) { return v == 0 ? VK_FILTER_NEAREST : VK_FILTER_LINEAR; };
    auto wrap = [](jint v) {
        return v == 0 ? VK_SAMPLER_ADDRESS_MODE_REPEAT
                      : v == 2 ? VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    };
    gdxvk::SamplerKey k;
    k.minFilter = filter(minF);
    k.magFilter = filter(magF);
    // mip filter: 0 nearest, 1 linear, 2 = no mipmapping (level 0 only)
    k.mipmapMode = mipF == 1 ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST;
    k.mipmapped = mipF != 2;
    k.wrapU = wrap(wrapU);
    k.wrapV = wrap(wrapV);
    k.anisotropy = static_cast<uint32_t>(aniso < 1 ? 1 : aniso);
    return k;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreate(JNIEnv* env, jclass, jboolean validation,
                                                                                  jboolean forceTierB, jint deviceIndex,
                                                                                  jint requestedMinor, jstring appName,
                                                                                  jstring pipelineCachePath) {
    gdxvk::ContextConfig cfg;
    cfg.enableValidation = validation == JNI_TRUE;
    cfg.forceTierB = forceTierB == JNI_TRUE;
    cfg.preferredDeviceIndex = deviceIndex;
    cfg.requestedApiVersion = gdxvk::makeApiVersion(1, static_cast<uint32_t>(requestedMinor));
    if (appName != nullptr) {
        const char* chars = env->GetStringUTFChars(appName, nullptr);
        if (chars != nullptr) {
            cfg.appName = chars;
            env->ReleaseStringUTFChars(appName, chars);
        }
    }
    if (pipelineCachePath != nullptr) {
        const char* chars = env->GetStringUTFChars(pipelineCachePath, nullptr);
        if (chars != nullptr) {
            cfg.pipelineCachePath = chars;
            env->ReleaseStringUTFChars(pipelineCachePath, chars);
        }
    }
    std::string error, kind;
    std::unique_ptr<gdxvk::Context> ctx = gdxvk::Context::create(cfg, error, kind);
    if (!ctx) {
        throwKind(env, kind, error);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx.release());
}

JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeValidationCount(JNIEnv*, jclass, jlong handle) {
    return static_cast<jint>(reinterpret_cast<gdxvk::Context*>(handle)->validationMessageCount());
}

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeDebugForceRotation(JNIEnv*, jclass, jlong presenter, jint quarterTurns) {
    reinterpret_cast<gdxvk::Presenter*>(presenter)->debugForceRotation(static_cast<int>(quarterTurns));
}

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeSetDepth(JNIEnv*, jclass, jlong presenter, jint mode) {
    reinterpret_cast<gdxvk::Presenter*>(presenter)->setDepth(static_cast<int>(mode));
}

// Reading another class's fields without allocating (stock Mesh data: private fields, read every frame). JDK 18+ reflection runs on method handles
// and allocates on every Field access; a JNI field ID read does not, and works the same on Android.
JNIEXPORT jlong JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeFieldId(JNIEnv* env, jclass, jclass type, jstring name, jstring sig) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    const char* s = env->GetStringUTFChars(sig, nullptr);
    jfieldID id = env->GetFieldID(type, n, s);
    env->ReleaseStringUTFChars(name, n);
    env->ReleaseStringUTFChars(sig, s);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return reinterpret_cast<jlong>(id);
}

JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeGetIntField(JNIEnv* env, jclass, jobject o, jlong id) {
    return env->GetIntField(o, reinterpret_cast<jfieldID>(id));
}

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeGetBooleanField(JNIEnv* env, jclass, jobject o, jlong id) {
    return env->GetBooleanField(o, reinterpret_cast<jfieldID>(id));
}

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeSetBooleanField(JNIEnv* env, jclass, jobject o, jlong id, jboolean v) {
    env->SetBooleanField(o, reinterpret_cast<jfieldID>(id), v);
}

JNIEXPORT jobject JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeGetObjectField(JNIEnv* env, jclass, jobject o, jlong id) {
    return env->GetObjectField(o, reinterpret_cast<jfieldID>(id));
}

// Memory pressure: runs what the GPU has finished with and frees idle staging blocks. Call it on the render thread.
// ---- run-time GLSL (the stock-GLSL translator's compiler; no context needed) ------------------------------------------------------------------

namespace {
gdxvk::ShaderCompiler* glslCompiler() {
    static std::unique_ptr<gdxvk::ShaderCompiler> compiler = gdxvk::ShaderCompiler::createGlslang();
    return compiler.get();
}

std::string javaString(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

gdxvk::CompileRequest glslRequest(JNIEnv* env, jint stage, jstring name, jstring source) {
    gdxvk::CompileRequest request;
    request.stage = stage == 0 ? gdxvk::ShaderStage::Vertex : stage == 2 ? gdxvk::ShaderStage::Compute : gdxvk::ShaderStage::Fragment;
    request.name = javaString(env, name);
    request.source = javaString(env, source);
    return request;
}
} // namespace

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeGlslAvailable(JNIEnv*, jclass) {
    return glslCompiler() != nullptr ? JNI_TRUE : JNI_FALSE;
}

// Returns {expanded text or null, log}.
JNIEXPORT jobjectArray JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeGlslPreprocess(JNIEnv* env, jclass, jint stage, jstring name,
                                                                                               jstring source) {
    jobjectArray out = env->NewObjectArray(2, env->FindClass("java/lang/String"), nullptr);
    gdxvk::ShaderCompiler* compiler = glslCompiler();
    if (compiler == nullptr) {
        env->SetObjectArrayElement(out, 1, utf(env, "this build of gdx-vulkan has no run-time GLSL compiler (GDXVK_GLSLANG=OFF)"));
        return out;
    }
    const gdxvk::PreprocessResult r = compiler->preprocess(glslRequest(env, stage, name, source));
    if (r.ok) env->SetObjectArrayElement(out, 0, utf(env, r.text));
    env->SetObjectArrayElement(out, 1, utf(env, r.log));
    return out;
}

// Returns {int[] spirv or null, String log}.
JNIEXPORT jobjectArray JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeGlslCompile(JNIEnv* env, jclass, jint stage, jstring name,
                                                                                            jstring source) {
    jobjectArray out = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
    gdxvk::ShaderCompiler* compiler = glslCompiler();
    if (compiler == nullptr) {
        env->SetObjectArrayElement(out, 1, utf(env, "this build of gdx-vulkan has no run-time GLSL compiler (GDXVK_GLSLANG=OFF)"));
        return out;
    }
    const gdxvk::CompileResult r = compiler->compile(glslRequest(env, stage, name, source));
    if (r.ok) {
        jintArray words = env->NewIntArray(static_cast<jsize>(r.spirv.size()));
        env->SetIntArrayRegion(words, 0, static_cast<jsize>(r.spirv.size()), reinterpret_cast<const jint*>(r.spirv.data()));
        env->SetObjectArrayElement(out, 0, words);
    }
    env->SetObjectArrayElement(out, 1, utf(env, r.log));
    return out;
}

JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeTrimMemory(JNIEnv*, jclass, jlong handle) {
    const gdxvk::ContextHandles& h = reinterpret_cast<gdxvk::Context*>(handle)->handles();
    h.deletion->collect();
    h.staging->collect(h.timeline->completed());
    return static_cast<jint>(h.staging->trim());
}

// ---- Presenter (window surface + swapchain) -------------------------------------------------------------------

JNIEXPORT jlong JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreatePresenter(JNIEnv* env, jclass, jlong ctx, jlong display,
                                                                                           jlong window, jint width, jint height,
                                                                                           jboolean vsync) {
    gdxvk::SurfaceHandles handles;
    handles.display = static_cast<uintptr_t>(display);
    handles.window = static_cast<uintptr_t>(window);
    std::string error, kind;
    std::unique_ptr<gdxvk::Presenter> p = gdxvk::Presenter::create(*reinterpret_cast<gdxvk::Context*>(ctx), handles,
                                                                   static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                                                                   vsync == JNI_TRUE, false, error, kind);
    if (!p) {
        throwKind(env, kind, error);
        return 0;
    }
    return reinterpret_cast<jlong>(p.release());
}

#ifdef __ANDROID__
// The activity Swappy reads the display's refresh rates from; null forgets it. Call before the context is created.
JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeSetFramePacingActivity(JNIEnv* env, jclass, jobject activity) {
    gdxvk::swappy::setActivity(env, activity);
}

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeFramePacingAvailable(JNIEnv*, jclass) {
    return gdxvk::swappy::available() ? JNI_TRUE : JNI_FALSE;
}

// `surface` is an android.view.Surface. The ANativeWindow reference taken here is released when the presenter is destroyed.
JNIEXPORT jlong JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreatePresenterAndroid(JNIEnv* env, jclass, jlong ctx,
                                                                                                  jobject surface, jint width,
                                                                                                  jint height, jboolean vsync, jboolean preRotate) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        throwKind(env, "init", "ANativeWindow_fromSurface returned null (surface already released?)");
        return 0;
    }
    gdxvk::SurfaceHandles handles;
    handles.window = reinterpret_cast<uintptr_t>(window);
    handles.releaseWindow = [](uintptr_t w) { ANativeWindow_release(reinterpret_cast<ANativeWindow*>(w)); };
    std::string error, kind;
    std::unique_ptr<gdxvk::Presenter> p = gdxvk::Presenter::create(*reinterpret_cast<gdxvk::Context*>(ctx), handles,
                                                                   static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                                                                   vsync == JNI_TRUE, preRotate == JNI_TRUE, error, kind);
    if (!p) { // Presenter::create has already released the window through ~Presenter
        throwKind(env, kind, error);
        return 0;
    }
    return reinterpret_cast<jlong>(p.release());
}
#endif

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeDestroyPresenter(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<gdxvk::Presenter*>(handle);
}

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeResize(JNIEnv*, jclass, jlong handle, jint width, jint height) {
    reinterpret_cast<gdxvk::Presenter*>(handle)->resize(static_cast<uint32_t>(width), static_cast<uint32_t>(height));
}

// Returns a gdxvk::FrameStatus as an int; Java turns the failure codes into typed exceptions.
JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeBeginFrame(JNIEnv*, jclass, jlong handle, jfloat r, jfloat g,
                                                                                     jfloat b, jfloat a) {
    const float rgba[4] = {r, g, b, a};
    return static_cast<jint>(reinterpret_cast<gdxvk::Presenter*>(handle)->beginFrame(rgba));
}

JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeEndFrame(JNIEnv*, jclass, jlong handle, jboolean capture) {
    return static_cast<jint>(reinterpret_cast<gdxvk::Presenter*>(handle)->endFrame(capture == JNI_TRUE));
}

// SPIR-V arrives as byte[] (little-endian words); returns the pipeline id or -1.
JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreatePipeline(JNIEnv* env, jclass, jlong handle, jstring name,
                                                                                         jbyteArray vert, jbyteArray frag) {
    const jsize vn = env->GetArrayLength(vert), fn = env->GetArrayLength(frag);
    if (vn % 4 != 0 || fn % 4 != 0) return -1;
    std::vector<uint32_t> v(static_cast<size_t>(vn) / 4), f(static_cast<size_t>(fn) / 4);
    env->GetByteArrayRegion(vert, 0, vn, reinterpret_cast<jbyte*>(v.data()));
    env->GetByteArrayRegion(frag, 0, fn, reinterpret_cast<jbyte*>(f.data()));
    std::string label = "(unnamed)";
    if (name != nullptr) {
        const char* chars = env->GetStringUTFChars(name, nullptr);
        if (chars != nullptr) {
            label = chars;
            env->ReleaseStringUTFChars(name, chars);
        }
    }
    return static_cast<jint>(
        reinterpret_cast<gdxvk::Presenter*>(handle)->createPipeline(label.c_str(), v.data(), v.size(), f.data(), f.size()));
}

JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreateComputePipeline(JNIEnv* env, jclass, jlong handle, jstring name,
                                                                                               jbyteArray spv) {
    const jsize n = env->GetArrayLength(spv);
    if (n % 4 != 0) return -1;
    std::vector<uint32_t> code(static_cast<size_t>(n) / 4);
    env->GetByteArrayRegion(spv, 0, n, reinterpret_cast<jbyte*>(code.data()));
    std::string label = "(unnamed)";
    if (name != nullptr) {
        const char* chars = env->GetStringUTFChars(name, nullptr);
        if (chars != nullptr) {
            label = chars;
            env->ReleaseStringUTFChars(name, chars);
        }
    }
    return static_cast<jint>(reinterpret_cast<gdxvk::Presenter*>(handle)->createComputePipeline(label.c_str(), code.data(), code.size()));
}

JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeDispatch(JNIEnv* env, jclass, jlong handle, jint pipeline, jfloatArray push,
                                                                                   jint groupsX, jint buffer) {
    float block[32];
    env->GetFloatArrayRegion(push, 0, 32, block);
    return static_cast<jint>(reinterpret_cast<gdxvk::Presenter*>(handle)->dispatch(pipeline, block, static_cast<uint32_t>(groupsX), buffer));
}

JNIEXPORT jlong JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeBenchmarkRecord(JNIEnv* env, jclass, jlong handle, jint pipeline, jint indexBuffer,
                                                                                              jint draws, jfloatArray push) {
    float block[32];
    env->GetFloatArrayRegion(push, 0, 32, block);
    return static_cast<jlong>(reinterpret_cast<gdxvk::Presenter*>(handle)->benchmarkRecord(pipeline, indexBuffer, static_cast<uint32_t>(draws), block));
}

JNIEXPORT jlong JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeGpuFrameNanos(JNIEnv*, jclass, jlong handle) {
    return static_cast<jlong>(reinterpret_cast<gdxvk::Presenter*>(handle)->gpuFrameNanos());
}

JNIEXPORT jintArray JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeLastPassCounts(JNIEnv* env, jclass, jlong handle) {
    uint32_t c[2];
    reinterpret_cast<gdxvk::Presenter*>(handle)->lastPassCounts(c);
    jint v[2] = {static_cast<jint>(c[0]), static_cast<jint>(c[1])};
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, v);
    return out;
}

JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeDraw(JNIEnv* env, jclass, jlong handle, jint pipeline,
                                                                               jint vertexCount, jfloatArray push) {
    float block[32];
    env->GetFloatArrayRegion(push, 0, 32, block);
    return static_cast<jint>(
        reinterpret_cast<gdxvk::Presenter*>(handle)->draw(pipeline, static_cast<uint32_t>(vertexCount), block));
}

JNIEXPORT jbyteArray JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCapturedPixels(JNIEnv* env, jclass, jlong handle) {
    const std::vector<uint8_t>& px = reinterpret_cast<gdxvk::Presenter*>(handle)->capturedPixels();
    jbyteArray out = env->NewByteArray(static_cast<jsize>(px.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(px.size()), reinterpret_cast<const jbyte*>(px.data()));
    return out;
}

// [width, height, VkFormat, imageCount]
JNIEXPORT jintArray JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativePresenterInfo(JNIEnv* env, jclass, jlong handle) {
    const gdxvk::Presenter* p = reinterpret_cast<gdxvk::Presenter*>(handle);
    const jint v[7] = {static_cast<jint>(p->width()), static_cast<jint>(p->height()), static_cast<jint>(p->swapchainFormat()),
                       static_cast<jint>(p->imageCount()), static_cast<jint>(p->rotation()), static_cast<jint>(p->framePacingPeriodMicros()),
                       p->presentFencesActive() ? 1 : 0};
    jintArray out = env->NewIntArray(7);
    env->SetIntArrayRegion(out, 0, 7, v);
    return out;
}

JNIEXPORT jstring JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeLastError(JNIEnv* env, jclass, jlong handle) {
    return utf(env, reinterpret_cast<gdxvk::Presenter*>(handle)->lastError());
}

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<gdxvk::Context*>(handle);
}

// Returns a flat [key0, value0, key1, value1, ...] array. Keeps the JNI surface to one call; Java parses it.
JNIEXPORT jobjectArray JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeInfo(JNIEnv* env, jclass, jlong handle) {
    const gdxvk::DeviceInfo& i = reinterpret_cast<gdxvk::Context*>(handle)->info();
    std::vector<std::string> kv = {
        "deviceName", i.deviceName,
        "driverInfo", i.driverInfo,
        "vendorId", std::to_string(i.vendorId),
        "deviceId", std::to_string(i.deviceId),
        "deviceApiVersion", std::to_string(i.deviceApiVersion),
        "effectiveApiVersion", std::to_string(i.effectiveApiVersion),
        "deviceType", std::to_string(i.deviceType),
        "validationEnabled", i.validationEnabled ? "true" : "false",
        "tier", i.tier.tier == gdxvk::Tier::A ? "A" : "B",
        "tierForced", i.tier.forced ? "true" : "false",
        "tierReason", i.tier.reason,
        "textureTableSize", std::to_string(i.tier.textureTableSize),
        "maxImageDimension2D", std::to_string(i.maxImageDimension2D),
        "maxSamplerAnisotropy", std::to_string(i.maxSamplerAnisotropy),
        "maxPushConstantsSize", std::to_string(i.maxPushConstantsSize),
        "minUniformBufferOffsetAlignment", std::to_string(i.minUniformBufferOffsetAlignment),
        "minStorageBufferOffsetAlignment", std::to_string(i.minStorageBufferOffsetAlignment),
        "graphicsQueueFamily", std::to_string(i.graphicsQueueFamily),
        "maxUniformBufferRange", std::to_string(i.maxUniformBufferRange),
        "timelineSemaphores", i.timelineSemaphores ? "true" : "false",
        "pipelineCacheLoadedBytes", std::to_string(i.pipelineCacheLoadedBytes),
    };
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(kv.size()), strCls, nullptr);
    for (size_t n = 0; n < kv.size(); ++n) {
        jstring js = utf(env, kv[n]);
        env->SetObjectArrayElement(out, static_cast<jsize>(n), js);
        env->DeleteLocalRef(js);
    }
    env->DeleteLocalRef(strCls);
    return out;
}

// ---- Resources (buffers and textures) --------------------------------------------------------------------------------------

JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreateBuffer(JNIEnv*, jclass, jlong ctx, jlong size) {
    return static_cast<jint>(res(ctx).createBuffer(static_cast<uint64_t>(size)));
}

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeWriteBuffer(JNIEnv* env, jclass, jlong ctx, jint id,
                                                                                          jlong offset, jbyteArray data, jint start,
                                                                                          jint length) {
    jbyte* p = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(data, nullptr));
    if (p == nullptr) return JNI_FALSE;
    const bool ok = res(ctx).writeBuffer(id, static_cast<uint64_t>(offset), p + start, static_cast<uint64_t>(length));
    env->ReleasePrimitiveArrayCritical(data, p, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeReadBuffer(JNIEnv* env, jclass, jlong ctx, jint id, jlong offset,
                                                                                         jbyteArray out, jint start, jint length) {
    std::vector<uint8_t> tmp(static_cast<size_t>(length));
    if (!res(ctx).readBuffer(id, static_cast<uint64_t>(offset), tmp.data(), static_cast<uint64_t>(length))) return JNI_FALSE;
    env->SetByteArrayRegion(out, start, length, reinterpret_cast<const jbyte*>(tmp.data()));
    return JNI_TRUE;
}

// The same, from a direct ByteBuffer (stock Mesh data lives in one): no copy through the Java heap.
JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeWriteBufferDirect(JNIEnv* env, jclass, jlong ctx, jint id,
                                                                                                jlong offset, jobject data, jint start,
                                                                                                jint length) {
    const uint8_t* p = static_cast<const uint8_t*>(env->GetDirectBufferAddress(data));
    if (p == nullptr) return JNI_FALSE;
    return res(ctx).writeBuffer(id, static_cast<uint64_t>(offset), p + start, static_cast<uint64_t>(length)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeDestroyBuffer(JNIEnv*, jclass, jlong ctx, jint id) {
    res(ctx).destroyBuffer(id);
}

// `data` holds `uploadedLevels` tightly packed mip levels, level 0 first. format: 0 RGBA8, 1 alpha (R8), 2 luminance-alpha (RG8).
JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreateTexture(JNIEnv* env, jclass, jlong ctx, jint width,
                                                                                        jint height, jint format, jint uploadedLevels,
                                                                                        jboolean generateMips, jbyteArray data,
                                                                                        jint minFilter, jint magFilter, jint mipFilter,
                                                                                        jint wrapU, jint wrapV, jint anisotropy) {
    // format 0..2 are the TexFormat values; 4 = an RGBA8 render target (a colour attachment as well as sampled), 5 = RGBA16F, 6 = RGBA32F targets.
    // A render target may come without data (null): it is then only given its sampling layout.
    const bool target = format >= 4 && format <= 6;
    if ((data == nullptr && !target) || format < 0 || format > 6 || format == 3) return -1;
    gdxvk::TextureDesc desc;
    desc.width = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.renderTarget = target;
    desc.vkFormat = format == 5 ? 97 : format == 6 ? 109 : 0; // VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT
    desc.format = static_cast<gdxvk::TexFormat>(target ? 0 : format);
    desc.uploadedLevels = static_cast<uint32_t>(uploadedLevels);
    desc.generateMips = generateMips == JNI_TRUE;
    if (data == nullptr) { // a render target with no initial contents
        return res(ctx).createTexture(desc, nullptr, 0, samplerKey(minFilter, magFilter, mipFilter, wrapU, wrapV, anisotropy));
    }
    const jsize bytes = env->GetArrayLength(data);
    jbyte* p = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(data, nullptr));
    if (p == nullptr) return -1;
    const int id = res(ctx).createTexture(desc, p, static_cast<uint64_t>(bytes), samplerKey(minFilter, magFilter, mipFilter, wrapU, wrapV, anisotropy));
    env->ReleasePrimitiveArrayCritical(data, p, JNI_ABORT);
    return id;
}

// A block-compressed texture: `levels` tightly packed mip levels of the given Vulkan format (blockW x blockH texels in blockBytes).
JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreateCompressedTexture(JNIEnv* env, jclass, jlong ctx, jint width,
                                                                                                  jint height, jint vkFormat, jint blockW,
                                                                                                  jint blockH, jint blockBytes, jint levels,
                                                                                                  jbyteArray data, jint minFilter,
                                                                                                  jint magFilter, jint mipFilter, jint wrapU,
                                                                                                  jint wrapV, jint anisotropy) {
    if (data == nullptr) return -1;
    gdxvk::TextureDesc desc;
    desc.width = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.format = gdxvk::TexFormat::Compressed;
    desc.vkFormat = static_cast<uint32_t>(vkFormat);
    desc.blockW = static_cast<uint32_t>(blockW);
    desc.blockH = static_cast<uint32_t>(blockH);
    desc.blockBytes = static_cast<uint32_t>(blockBytes);
    desc.uploadedLevels = static_cast<uint32_t>(levels);
    if (data == nullptr) { // a render target with no initial contents
        return res(ctx).createTexture(desc, nullptr, 0, samplerKey(minFilter, magFilter, mipFilter, wrapU, wrapV, anisotropy));
    }
    const jsize bytes = env->GetArrayLength(data);
    jbyte* p = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(data, nullptr));
    if (p == nullptr) return -1;
    const int id = res(ctx).createTexture(desc, p, static_cast<uint64_t>(bytes), samplerKey(minFilter, magFilter, mipFilter, wrapU, wrapV, anisotropy));
    env->ReleasePrimitiveArrayCritical(data, p, JNI_ABORT);
    return id;
}

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeFormatSupported(JNIEnv*, jclass, jlong ctx, jint vkFormat) {
    return res(ctx).formatSupported(static_cast<uint32_t>(vkFormat)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeUpdateTexture(JNIEnv* env, jclass, jlong ctx, jint id,
                                                                                            jint level, jint x, jint y, jint w, jint h,
                                                                                            jbyteArray data) {
    if (data == nullptr) return JNI_FALSE;
    const jsize bytes = env->GetArrayLength(data);
    jbyte* p = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(data, nullptr));
    if (p == nullptr) return JNI_FALSE;
    const bool ok = res(ctx).updateTexture(id, static_cast<uint32_t>(level), static_cast<uint32_t>(x), static_cast<uint32_t>(y),
                                           static_cast<uint32_t>(w), static_cast<uint32_t>(h), p, static_cast<uint64_t>(bytes));
    env->ReleasePrimitiveArrayCritical(data, p, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeRegenerateMips(JNIEnv*, jclass, jlong ctx, jint id) {
    return res(ctx).regenerateMips(id) ? JNI_TRUE : JNI_FALSE;
}

// [width, height, levels] or null
JNIEXPORT jintArray JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeTextureInfo(JNIEnv* env, jclass, jlong ctx, jint id) {
    uint32_t w, h, l;
    if (!res(ctx).textureInfo(id, &w, &h, &l)) return nullptr;
    const jint v[3] = {static_cast<jint>(w), static_cast<jint>(h), static_cast<jint>(l)};
    jintArray out = env->NewIntArray(3);
    env->SetIntArrayRegion(out, 0, 3, v);
    return out;
}

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeSetTextureSampler(JNIEnv*, jclass, jlong ctx, jint id,
                                                                                                jint minFilter, jint magFilter,
                                                                                                jint mipFilter, jint wrapU, jint wrapV,
                                                                                                jint anisotropy) {
    return res(ctx).setTextureSampler(id, samplerKey(minFilter, magFilter, mipFilter, wrapU, wrapV, anisotropy)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreateDepthImage(JNIEnv*, jclass, jlong ctx, jint width, jint height,
                                                                                          jboolean stencil, jint depthBits, jboolean transient) {
    return res(ctx).createDepthImage(static_cast<uint32_t>(width), static_cast<uint32_t>(height), stencil == JNI_TRUE, static_cast<uint32_t>(depthBits),
                                     transient == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeDestroyDepthImage(JNIEnv*, jclass, jlong ctx, jint id) {
    res(ctx).destroyDepthImage(id);
}

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeDestroyTexture(JNIEnv*, jclass, jlong ctx, jint id) {
    res(ctx).destroyTexture(id);
}

// Tier A: the slot in the bindless table. Tier B: 0 (the texture is bound by descriptor set). -1: no such texture.
JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeTextureSlot(JNIEnv*, jclass, jlong ctx, jint id) {
    int slot = -1;
    VkDescriptorSet set = VK_NULL_HANDLE;
    if (!res(ctx).textureBinding(id, &slot, &set)) return -1;
    return reinterpret_cast<gdxvk::Context*>(ctx)->handles().tierA ? slot : 0;
}

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeFlushUploads(JNIEnv*, jclass, jlong ctx) {
    return res(ctx).flushUploads() ? JNI_TRUE : JNI_FALSE;
}

// Runs the deletions the GPU has finished with; returns how many ran.
JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCollectGarbage(JNIEnv*, jclass, jlong ctx) {
    const gdxvk::ContextHandles& h = reinterpret_cast<gdxvk::Context*>(ctx)->handles();
    h.staging->collect(h.timeline->completed());
    return static_cast<jint>(h.deletion->collect());
}

// [buffersLive, texturesLive, slotsInUse, samplers, stagingBlocks, bufferBytesLive, poolBytes, retiredPending, totalRetired,
//  vmaBlockBytes, vmaAllocationBytes, completedSerial, textureRenames]
JNIEXPORT jlongArray JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeResourceStats(JNIEnv* env, jclass, jlong ctx) {
    const gdxvk::Resources::Stats s = res(ctx).stats();
    const jlong v[13] = {s.buffersLive, s.texturesLive, s.slotsInUse, s.samplers, s.stagingBlocks,
                         static_cast<jlong>(s.bufferBytesLive), static_cast<jlong>(s.poolBytes), static_cast<jlong>(s.retiredPending),
                         static_cast<jlong>(s.totalRetired), static_cast<jlong>(s.vmaBlockBytes),
                         static_cast<jlong>(s.vmaAllocationBytes), static_cast<jlong>(s.completedSerial), static_cast<jlong>(s.textureRenames)};
    jlongArray out = env->NewLongArray(13);
    env->SetLongArrayRegion(out, 0, 13, v);
    return out;
}

// Textures renamed so far (see Resources::renameCount): the per-frame renames counter reads it at the start and end of a frame.
JNIEXPORT jlong JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeRenameCount(JNIEnv*, jclass, jlong ctx) {
    return static_cast<jlong>(res(ctx).renameCount());
}

// ---- Presenter: Set 0 and Set 1 -------------------------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeBindFrameData(JNIEnv* env, jclass, jlong handle,
                                                                                            jbyteArray uniform, jint uniformBytes,
                                                                                            jbyteArray storage, jint storageBytes) {
    std::vector<jbyte> u(static_cast<size_t>(uniformBytes)), s(static_cast<size_t>(storageBytes));
    if (uniformBytes > 0) env->GetByteArrayRegion(uniform, 0, uniformBytes, u.data());
    if (storageBytes > 0) env->GetByteArrayRegion(storage, 0, storageBytes, s.data());
    return reinterpret_cast<gdxvk::Presenter*>(handle)->bindFrameData(u.data(), static_cast<uint32_t>(uniformBytes), s.data(),
                                                                       static_cast<uint64_t>(storageBytes))
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeBindTexture(JNIEnv*, jclass, jlong handle, jint texture) {
    return static_cast<jint>(reinterpret_cast<gdxvk::Presenter*>(handle)->bindTexture(texture));
}

// ---- Command stream, general pipelines, vertex ring -----------------------------------------------------------------------

// desc: [stride, topology, cullMode, blend, srcColor, dstColor, srcAlpha, dstAlpha, attributeCount, (location, format, offset)...]
JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreatePipelineEx(JNIEnv* env, jclass, jlong handle, jstring name,
                                                                                           jbyteArray vert, jbyteArray frag,
                                                                                           jintArray desc) {
    const jsize vn = env->GetArrayLength(vert), fn = env->GetArrayLength(frag), dn = env->GetArrayLength(desc);
    if (vn % 4 != 0 || fn % 4 != 0 || dn < 10) return -1;
    std::vector<uint32_t> v(static_cast<size_t>(vn) / 4), f(static_cast<size_t>(fn) / 4);
    env->GetByteArrayRegion(vert, 0, vn, reinterpret_cast<jbyte*>(v.data()));
    env->GetByteArrayRegion(frag, 0, fn, reinterpret_cast<jbyte*>(f.data()));
    std::vector<jint> d(static_cast<size_t>(dn));
    env->GetIntArrayRegion(desc, 0, dn, d.data());
    gdxvk::PipelineDesc pd;
    pd.stride = static_cast<uint32_t>(d[0]);
    pd.topology = static_cast<uint32_t>(d[1]);
    pd.cullMode = static_cast<uint32_t>(d[2]);
    pd.blend = d[3] != 0;
    pd.srcColor = static_cast<uint32_t>(d[4]);
    pd.dstColor = static_cast<uint32_t>(d[5]);
    pd.srcAlpha = static_cast<uint32_t>(d[6]);
    pd.dstAlpha = static_cast<uint32_t>(d[7]);
    pd.colorOutputs = static_cast<uint32_t>(d[9]);
    const int count = d[8];
    if (dn < 10 + count * 3) return -1;
    // Optional tail: the number of specialization constants, then their values (ids 1..n).
    const int tail = 10 + count * 3;
    if (dn > tail) {
        const int n = d[tail];
        if (n < 0 || dn < tail + 1 + n) return -1;
        for (int k = 0; k < n; ++k) pd.spec.push_back(d[tail + 1 + k]);
    }
    for (int a = 0; a < count; ++a) {
        gdxvk::VertexAttribute attr;
        attr.location = static_cast<uint32_t>(d[10 + a * 3]);
        attr.format = static_cast<uint32_t>(d[11 + a * 3]);
        if (attr.format & 0x40000000u) { // a constant attribute (GdxVkPipelineDesc.constantAttribute)
            attr.binding = 1;
            attr.format &= ~0x40000000u;
        }
        attr.offset = static_cast<uint32_t>(d[12 + a * 3]);
        pd.attributes.push_back(attr);
    }
    std::string label = "(unnamed)";
    if (name != nullptr) {
        const char* chars = env->GetStringUTFChars(name, nullptr);
        if (chars != nullptr) {
            label = chars;
            env->ReleaseStringUTFChars(name, chars);
        }
    }
    return static_cast<jint>(reinterpret_cast<gdxvk::Presenter*>(handle)->createPipelineEx(label.c_str(), v.data(), v.size(), f.data(), f.size(), pd));
}

// (block << 48) | offset, or -1
JNIEXPORT jlong JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeAllocVertex(JNIEnv*, jclass, jlong handle, jlong bytes) {
    uint32_t block;
    uint64_t offset;
    if (!reinterpret_cast<gdxvk::Presenter*>(handle)->allocVertex(static_cast<uint64_t>(bytes), &block, &offset)) return -1;
    return (static_cast<jlong>(block) << 48) | static_cast<jlong>(offset);
}

// The whole block as a direct ByteBuffer over the mapped memory (valid for the frame), or null.
JNIEXPORT jobject JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeVertexBlock(JNIEnv* env, jclass, jlong handle, jint block) {
    uint64_t size = 0;
    uint8_t* p = reinterpret_cast<gdxvk::Presenter*>(handle)->vertexBlock(static_cast<uint32_t>(block), &size);
    return p == nullptr ? nullptr : env->NewDirectByteBuffer(p, static_cast<jlong>(size));
}

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeFlushVertices(JNIEnv*, jclass, jlong handle, jint block, jlong offset,
                                                                                        jlong bytes) {
    reinterpret_cast<gdxvk::Presenter*>(handle)->flushVertices(static_cast<uint32_t>(block), static_cast<uint64_t>(offset), static_cast<uint64_t>(bytes));
}

// `stream` is a direct ByteBuffer; returns a FrameStatus code.
JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeSubmitCommands(JNIEnv* env, jclass, jlong handle, jobject stream,
                                                                                         jint bytes) {
    const uint8_t* p = static_cast<const uint8_t*>(env->GetDirectBufferAddress(stream));
    if (p == nullptr) return static_cast<jint>(gdxvk::FrameStatus::Error);
    return static_cast<jint>(reinterpret_cast<gdxvk::Presenter*>(handle)->submitCommandStream(p, static_cast<size_t>(bytes)));
}

} // extern "C"
