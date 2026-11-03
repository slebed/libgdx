# gdx-vulkan

A Vulkan renderer for libGDX, built as a standalone library on **stock libGDX 1.14.3**. No fork of libGDX is required.
See `docs/PLAN.md` for the architecture and phases.

**Status: Phase 1a.** Context, tier selection, window surface, swapchain and a triangle work on Windows, Linux (lavapipe CI) and Android, in both tiers. Golden-image tests and a CI workflow exist. Phase 1b (two-set layout, deferred deletion, rings and staging, caches, shader reflection, soak test) is implemented and verified on desktop GPUs and lavapipe; Android device verification of 1b is pending.

## Layout
- `gdx-vulkan-core/` Java API and the C++ core (`jni/`: CMake, vendored volk, VMA and Vulkan headers)
- `gdx-vulkan-desktop/` desktop surface glue on stock `gdx-backend-lwjgl3` GLFW window handles + clear-screen demo (`gradlew :gdx-vulkan-desktop:runClearDemo`)
- `gdx-vulkan-android/` separate Gradle build (AGP): `:library` and a `:testapp` that runs the pixel self-test on a device. See its README

## Build and test
Prerequisites: JDK 11+ (class files target Java 8), CMake 3.20+, a C++17 compiler, the Vulkan loader, `glslc` (Vulkan SDK or `glslc` package), and on Linux `libx11-dev`. For validation layers install the
Vulkan SDK or your distro's validation layer package. For a software device on Linux: `mesa-vulkan-drivers` (lavapipe).

```
gradle :gdx-vulkan-core:test                       # builds natives, runs tier tests + Java tests
gradle :gdx-vulkan-core:test -Dgdxvulkan.forceTierB=true
```
Native only: `cmake -S gdx-vulkan-core/jni -B build/native && cmake --build build/native && ctest --test-dir build/native`.

Without a Vulkan driver, the smoke and context tests skip. `-Dgdxvulkan.requireDriver=true` turns that skip into a failure (used by CI).

## Runtime options (system properties)
`gdxvulkan.validation`, `gdxvulkan.forceTierB`, `gdxvulkan.device` (index), `gdxvulkan.pipelineCache` (file), `gdxvulkan.native.path` (dir or file of the built `gdxvk` library). Environment `GDXVK_COMPLETION=fence|timeline` overrides the GPU completion tracking (diagnostics).

## Third-party code (vendored in `gdx-vulkan-core/jni/vendor`)
volk, Vulkan-Headers, Vulkan Memory Allocator. Each is under its own permissive licence; see the upstream repositories for versions
and licence texts. Versions are pinned by the vendored copies.
