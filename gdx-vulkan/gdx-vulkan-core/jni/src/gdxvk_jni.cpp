// JNI entry points for io.github.slebed.gdxvulkan.GdxVkNative. Hand-written (Phase 1a decision: plain
// CMake instead of gdx-jnigen until its cross-compile support is verified).
#include <jni.h>
#ifdef __ANDROID__
#include <android/native_window.h>
#include <android/native_window_jni.h>
#endif

#include <memory>
#include <string>
#include <vector>

#include "gdxvk_context.h"
#include "gdxvk_internal.h"
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
    k.mipmapMode = mipF == 0 ? VK_SAMPLER_MIPMAP_MODE_NEAREST : VK_SAMPLER_MIPMAP_MODE_LINEAR;
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
                                                                   vsync == JNI_TRUE, error, kind);
    if (!p) {
        throwKind(env, kind, error);
        return 0;
    }
    return reinterpret_cast<jlong>(p.release());
}

#ifdef __ANDROID__
// `surface` is an android.view.Surface. The ANativeWindow reference taken here is released when the presenter is destroyed.
JNIEXPORT jlong JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreatePresenterAndroid(JNIEnv* env, jclass, jlong ctx,
                                                                                                  jobject surface, jint width,
                                                                                                  jint height, jboolean vsync) {
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
                                                                   vsync == JNI_TRUE, error, kind);
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
    const jint v[4] = {static_cast<jint>(p->width()), static_cast<jint>(p->height()), static_cast<jint>(p->swapchainFormat()),
                       static_cast<jint>(p->imageCount())};
    jintArray out = env->NewIntArray(4);
    env->SetIntArrayRegion(out, 0, 4, v);
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

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeDestroyBuffer(JNIEnv*, jclass, jlong ctx, jint id) {
    res(ctx).destroyBuffer(id);
}

JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreateTexture(JNIEnv* env, jclass, jlong ctx, jint width,
                                                                                        jint height, jbyteArray rgba, jint minFilter,
                                                                                        jint magFilter, jint mipFilter, jint wrapU,
                                                                                        jint wrapV, jint anisotropy) {
    if (rgba == nullptr || env->GetArrayLength(rgba) < width * height * 4) return -1;
    jbyte* p = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(rgba, nullptr));
    if (p == nullptr) return -1;
    const int id = res(ctx).createTexture(static_cast<uint32_t>(width), static_cast<uint32_t>(height), p,
                                          samplerKey(minFilter, magFilter, mipFilter, wrapU, wrapV, anisotropy));
    env->ReleasePrimitiveArrayCritical(rgba, p, JNI_ABORT);
    return id;
}

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeSetTextureSampler(JNIEnv*, jclass, jlong ctx, jint id,
                                                                                                jint minFilter, jint magFilter,
                                                                                                jint mipFilter, jint wrapU, jint wrapV,
                                                                                                jint anisotropy) {
    return res(ctx).setTextureSampler(id, samplerKey(minFilter, magFilter, mipFilter, wrapU, wrapV, anisotropy)) ? JNI_TRUE : JNI_FALSE;
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
//  vmaBlockBytes, vmaAllocationBytes, completedSerial]
JNIEXPORT jlongArray JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeResourceStats(JNIEnv* env, jclass, jlong ctx) {
    const gdxvk::Resources::Stats s = res(ctx).stats();
    const jlong v[12] = {s.buffersLive, s.texturesLive, s.slotsInUse, s.samplers, s.stagingBlocks,
                         static_cast<jlong>(s.bufferBytesLive), static_cast<jlong>(s.poolBytes), static_cast<jlong>(s.retiredPending),
                         static_cast<jlong>(s.totalRetired), static_cast<jlong>(s.vmaBlockBytes),
                         static_cast<jlong>(s.vmaAllocationBytes), static_cast<jlong>(s.completedSerial)};
    jlongArray out = env->NewLongArray(12);
    env->SetLongArrayRegion(out, 0, 12, v);
    return out;
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

} // extern "C"
