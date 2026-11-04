#include "gdxvk_swappy.h"

#if defined(GDXVK_SWAPPY) && defined(__ANDROID__)
#include <swappy/swappyVk.h>

#include <mutex>

namespace gdxvk::swappy {

namespace {
std::mutex gMutex;
JavaVM* gVm = nullptr;
jobject gActivity = nullptr;
} // namespace

bool available() { return true; }

void setActivity(JNIEnv* env, jobject activity) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gActivity != nullptr && gVm != nullptr) {
        env->DeleteGlobalRef(gActivity);
        gActivity = nullptr;
    }
    if (activity != nullptr) {
        env->GetJavaVM(&gVm);
        gActivity = env->NewGlobalRef(activity);
    }
}

bool hasActivity() {
    std::lock_guard<std::mutex> lock(gMutex);
    return gActivity != nullptr;
}

void deviceExtensions(VkPhysicalDevice physical, const std::vector<VkExtensionProperties>& available, std::vector<std::string>& out) {
    uint32_t n = 0;
    std::vector<VkExtensionProperties> avail(available);
    SwappyVk_determineDeviceExtensions(physical, static_cast<uint32_t>(avail.size()), avail.data(), &n, nullptr);
    if (n == 0) return;
    std::vector<std::vector<char>> storage(n, std::vector<char>(VK_MAX_EXTENSION_NAME_SIZE, 0));
    std::vector<char*> names(n);
    for (uint32_t i = 0; i < n; ++i) names[i] = storage[i].data();
    SwappyVk_determineDeviceExtensions(physical, static_cast<uint32_t>(avail.size()), avail.data(), &n, names.data());
    for (uint32_t i = 0; i < n; ++i) out.emplace_back(names[i]);
}

bool attach(VkPhysicalDevice physical, VkDevice device, VkQueue queue, uint32_t family, VkSwapchainKHR swapchain, ANativeWindow* window,
            uint64_t* periodNs) {
    JNIEnv* env = nullptr;
    jobject activity = nullptr;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gVm == nullptr || gActivity == nullptr) return false;
        if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return false; // the render thread is attached already
        activity = gActivity;
    }
    SwappyVk_setQueueFamilyIndex(device, queue, family);
    if (!SwappyVk_initAndGetRefreshCycleDuration(env, activity, physical, device, swapchain, periodNs)) return false;
    SwappyVk_setWindow(device, swapchain, window);
    SwappyVk_setAutoSwapInterval(true);
    SwappyVk_setSwapIntervalNS(device, swapchain, *periodNs);
    return true;
}

VkResult present(VkQueue queue, const VkPresentInfoKHR* info) { return SwappyVk_queuePresent(queue, info); }
void detach(VkDevice device, VkSwapchainKHR swapchain) { SwappyVk_destroySwapchain(device, swapchain); }
void destroyDevice(VkDevice device) { SwappyVk_destroyDevice(device); }

} // namespace gdxvk::swappy

#else

namespace gdxvk::swappy {
bool available() { return false; }
#ifdef __ANDROID__
void setActivity(JNIEnv*, jobject) {}
bool hasActivity() { return false; }
bool attach(VkPhysicalDevice, VkDevice, VkQueue, uint32_t, VkSwapchainKHR, ANativeWindow*, uint64_t*) { return false; }
#endif
void deviceExtensions(VkPhysicalDevice, const std::vector<VkExtensionProperties>&, std::vector<std::string>&) {}
VkResult present(VkQueue queue, const VkPresentInfoKHR* info) { return vkQueuePresentKHR(queue, info); }
void detach(VkDevice, VkSwapchainKHR) {}
void destroyDevice(VkDevice) {}
} // namespace gdxvk::swappy

#endif
