# gdx-vulkan-android

A **separate Gradle build** (Gradle 8.14.3, AGP 8.13.2): AGP needs a released Gradle, while the desktop build runs on 9.0-milestone.
- `:library` compiles the core Java sources, builds `libgdxvk.so` from `gdx-vulkan-core/jni` with the NDK (arm64-v8a only for now),
  and packages the shaders from `gdx-vulkan-core/src/main/shaders`.
- `:testapp` is a `SurfaceView` host. On the first surface it runs `SelfTest` (the desktop `TriangleTest`, resolution independent), then spins
  the triangle. `surfaceDestroyed` destroys only the presenter; the context stays alive.

```
gradlew :testapp:assembleDebug
adb install -r testapp/build/outputs/apk/debug/testapp-debug.apk
adb shell am start -n io.github.slebed.gdxvulkan.testapp/.MainActivity --ez forceTierB true   # omit the extra for Tier A
adb shell run-as io.github.slebed.gdxvulkan.testapp cat files/gdxvk-test.log                   # look for "SELFTEST PASS"
```
The result goes to `files/gdxvk-test.log` as well as logcat, because some devices restrict logcat over adb. There is no validation layer on
a stock device, so the validation count there is always 0; Android GPU debug layers can be added later.
Phase 4 adds the real `VulkanAndroidApplication`, pre-rotation and the lifecycle stress test.
