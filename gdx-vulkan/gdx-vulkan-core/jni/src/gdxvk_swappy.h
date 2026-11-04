// Android Frame Pacing (Swappy) behind a small interface. Without GDXVK_SWAPPY every call is a no-op and `available()` is false, so the
// desktop and non-Android builds do not know about it.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "volk.h"

#ifdef __ANDROID__
#include <jni.h>
#include <android/native_window.h>
#endif

namespace gdxvk::swappy {

// True when the library was built with Swappy.
bool available();

#ifdef __ANDROID__
// The activity Swappy needs to read the display's refresh rates (a global reference is kept; null forgets it).
void setActivity(JNIEnv* env, jobject activity);
bool hasActivity();
// Attaches the swapchain to Swappy; `periodNs` receives the display's refresh period. False: Swappy declined, present normally.
bool attach(VkPhysicalDevice physical, VkDevice device, VkQueue queue, uint32_t family, VkSwapchainKHR swapchain, ANativeWindow* window,
            uint64_t* periodNs);
#endif

// The device extensions Swappy asks for (VK_GOOGLE_display_timing); appended to `out` (names are owned by the returned storage).
void deviceExtensions(VkPhysicalDevice physical, const std::vector<VkExtensionProperties>& available, std::vector<std::string>& out);

VkResult present(VkQueue queue, const VkPresentInfoKHR* info);
void detach(VkDevice device, VkSwapchainKHR swapchain);
void destroyDevice(VkDevice device);

} // namespace gdxvk::swappy
