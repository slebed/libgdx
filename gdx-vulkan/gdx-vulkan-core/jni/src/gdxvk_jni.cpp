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
#include "gdxvk_presenter.h"

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

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreate(JNIEnv* env, jclass, jboolean validation,
                                                                                  jboolean forceTierB, jint deviceIndex,
                                                                                  jint requestedMinor, jstring appName) {
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
JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_GdxVkNative_nativeCreatePipeline(JNIEnv* env, jclass, jlong handle, jbyteArray vert,
                                                                                         jbyteArray frag) {
    const jsize vn = env->GetArrayLength(vert), fn = env->GetArrayLength(frag);
    if (vn % 4 != 0 || fn % 4 != 0) return -1;
    std::vector<uint32_t> v(static_cast<size_t>(vn) / 4), f(static_cast<size_t>(fn) / 4);
    env->GetByteArrayRegion(vert, 0, vn, reinterpret_cast<jbyte*>(v.data()));
    env->GetByteArrayRegion(frag, 0, fn, reinterpret_cast<jbyte*>(f.data()));
    return static_cast<jint>(reinterpret_cast<gdxvk::Presenter*>(handle)->createPipeline(v.data(), v.size(), f.data(), f.size()));
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

} // extern "C"
