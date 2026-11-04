# Building, testing and benchmarking

## Prerequisites
JDK 11+ (class files target Java 8), CMake 3.20+, a C++17 compiler, the Vulkan loader, `glslc` (from the Vulkan SDK or the `glslc` package), and on
Linux `libx11-dev`. For the validation layers install the Vulkan SDK or your distribution's validation-layer package. For a software device on Linux
install `mesa-vulkan-drivers` (lavapipe).

## Tests
```
gradlew check -Dgdxvulkan.requireDriver=true                          # every test, with validation and synchronization validation
gradlew check -Dgdxvulkan.requireDriver=true -Dgdxvulkan.forceTierB=true   # the Vulkan 1.1 code path on any GPU
gradlew check -Dgdxvulkan.requireDriver=true -Dgdxvulkan.device=1     # the second GPU
gradlew check ... -Dgdxvulkan.requireGl=true                          # fail instead of skip when the stock-OpenGL reference images are missing
```
Native only: `cmake -S gdx-vulkan-core/jni -B build/native && cmake --build build/native && ctest --test-dir build/native`.

Without a Vulkan driver the driver tests skip; `-Dgdxvulkan.requireDriver=true` turns that into a failure (CI does). The 2D and framebuffer scenes use the
skin, fonts and particle effects in `test-assets/` (see `NOTICE`); `-Dgdxvulkan.assetsDir=` points elsewhere, and the scenes skip if the directory is missing.

### What the tests compare
- **Stock OpenGL as the oracle.** The 2D, framebuffer and 3D scenes are written once against the stock libGDX API and drawn by stock libGDX on
  OpenGL (llvmpipe) and by gdx-vulkan (lavapipe). The framebuffer scenes match with zero differing pixels, the 3D scenes with at most a few pixels, and the
  2D scenes match except the shape renderer, which differs from OpenGL by 0.18% of pixels at line ends (within tolerance).
- **Goldens.** Committed PNGs rendered on lavapipe: exact on lavapipe, within a tolerance on a real GPU or a phone.
- **Validation.** The Vulkan validation layers run in every test; any message fails it.

### Two code paths
The library has two code paths so it can run on older drivers: the *modern path* (Vulkan 1.3: dynamic rendering, a bindless texture table) and the
*compatibility path* (Vulkan 1.1: render passes, one descriptor set per texture). It picks the best one the device supports; `-Dgdxvulkan.forceTierB=true`
forces the compatibility path. The tests run in both (the code and the test names call them Tier A and Tier B). Features that need several textures in one draw
(shadow maps, specular, emissive and normal-map textures) need the modern path.

## Benchmarks
Each prints a table; `-Dgdxvulkan.<name>.report=true` makes it report without failing the build.
```
gradlew :gdx-vulkan-desktop:stress2                                   # VulkanSpriteBatch against stock SpriteBatch, each in its own JVM
gradlew :gdx-vulkan-desktop:stressInstanced -Dgdxvulkan.device=1      # 1,000,000 sprites moved by a compute shader
gradlew :gdx-vulkan-desktop:stress3                                   # command stream recording against a C++ floor
gradlew :gdx-vulkan-desktop:stress5                                   # 250,000 instanced meshes with compute culling and 500 lights
gradlew :gdx-vulkan-desktop:soakTest -Dgdxvulkan.soakSeconds=600      # the full soak (leaks and synchronization hazards)
```

## Lavapipe in Docker (what CI runs)
```
docker build -t gdx-vulkan-ci ci
docker run --rm -v <repo>:/src:ro -v <out>:/out -v gdxvk-gradle:/root/.gradle gdx-vulkan-ci bash /src/ci/run-checks.sh
```
See `ci/README.md`, including how to regenerate the goldens.

## Android
`gdx-vulkan-android/` is its own Gradle build.
```
gradlew :testapp:assembleDebug && adb install -r testapp/build/outputs/apk/debug/testapp-debug.apk
adb shell am start -n io.github.slebed.gdxvulkan.testapp/.MainActivity --ez suite true [--ez forceTierB true] [--ez vsync false]
adb shell run-as io.github.slebed.gdxvulkan.testapp cat files/gdxvk-test.log      # look for "DEVICESUITE PASS"
tools/stress4.sh [cycles=50] [--tier-b] [--no-prerotate] [--no-stream]             # rotate + background/foreground while textures stream
```
`docs/RELEASE.md` is the checklist of manual runs (real GPUs, the phone) that CI cannot do.

## Runtime options
System properties: `gdxvulkan.validation`, `gdxvulkan.forceTierB`, `gdxvulkan.device` (index), `gdxvulkan.pipelineCache` (file), `gdxvulkan.native.path`
(directory or file of the built `gdxvk` library). Environment: `GDXVK_COMPLETION=fence|timeline` (GPU completion tracking), `GDXVK_PRESENT=immediate|mailbox`,
`GDXVK_PROFILE=1` (per-frame CPU time), `GDXVK_NO_PRESENT_FENCES=1`, `GDXVK_NO_SWAPPY=1`.
