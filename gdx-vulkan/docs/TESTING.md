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
- **Stock classes through the shim.** `StockClassesTest` and `StockScenesTest` draw a scene with libGDX's own `SpriteBatch`, `PolygonSpriteBatch`, `ShapeRenderer`, `BitmapFont`, `Stage`, a custom `ShaderProgram` and `ModelBatch` (lit, with `DefaultShader`) and compare it with the `Vulkan...` classes; Gate 2, 3 and 5 each draw every scene a second time with the stock classes and compare it with the first (`PictureCompare`: within 2, 6 and 4 levels on all but 0.05%, 0.5% and 0.5% of the pixels). `BundledShadersTest` draws stock `ModelBatch` through a matrix of materials, vertex layouts and lights with the disk cache off and fails if the run-time compiler is called: run `recordShaderBundle` when it does.
- **Goldens.** Committed PNGs rendered on lavapipe: exact on lavapipe, within a tolerance on a real GPU or a phone.
- **Driver rules.** `DriverRulesTest` keeps the shader patterns a driver refused (`docs/DRIVER_NOTES.md`) out of the source; the device itself is the only full test, through the Gate 5 scenes of the device suite.
- **Demos.** `DemosSmokeTest` (module `gdx-vulkan-demos`) starts each demo of the demo app, draws 20 frames and checks the picture is not blank; it is part of `check`.
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
gradlew :gdx-vulkan-desktop:stressShaders                             # a translated libGDX shader against the built-in one
gradlew :gdx-vulkan-desktop:stressShim                                # libGDX's own SpriteBatch on the shim against VulkanSpriteBatch (within 1.5x, one texture)
gradlew :gdx-vulkan-desktop:soakTest -Dgdxvulkan.soakSeconds=600      # the full soak (leaks and synchronization hazards)
```

## Demos
```
gradlew :gdx-vulkan-demos:demos                            # the demo app on gdx-vulkan: keys 1-3 start a demo, C starts its OpenGL version beside it, Esc comes back
gradlew :gdx-vulkan-demos:demoGl -Ddemo=shadows-stock      # one demo on stock libGDX and OpenGL (or -Ddemo=boids)
gradlew :gdx-vulkan-demos:demos -Ddemo.start=3 -Ddemo.screenshot=out.png -Ddemo.frames=4000   # run one demo, save a picture, leave
```
Options (system properties, also passed on to the OpenGL window): `demo.boids` (250000), `demo.vsync` (false: the frame rate shown is what the GPU can do), `demo.lights` (true), `demo.width`
and `demo.height`. The fps on screen is still in JIT warm-up for the first seconds; read it after about 4,000 frames. Both backends show the same fields (the API and version, device, driver,
code path, then frame time, CPU time of the process and of the render thread per frame, and GPU time per frame), so two windows can be compared line by line. To profile the demo's own
JVM: `-DdemoJvmArgs="-XX:StartFlightRecording=filename=demo.jfr,settings=profile,stackdepth=64"`.

## Shader bundle
`gradlew :gdx-vulkan-desktop:recordShaderBundle` translates and compiles the programs libGDX's `DefaultShader` and `DepthShader` ask for, for a matrix of materials, vertex layouts and lights, on both code paths,
and keeps them in `gdx-vulkan-core/src/main/resources/shaders/bundle`. Run it after changing the translator, the glslang version or the matrix in `RecordShaderBundleMain`; `BundledShadersTest` fails when the bundle is stale.

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
tools/keyboard-test.sh <adb serial>                                                  # the on-screen keyboard: shown, a key arrives as libGDX input, hidden
tools/demos-test.sh <adb serial> [seconds]                                          # the four demos on one phone: the numbers they show and a screenshot of each
tools/compare-phone.sh <adb serial> sprites|boids|shadows [count] [seconds] [vsync] [rounds]   # the same demo on stock libGDX (OpenGL ES) and on gdx-vulkan, on one phone; MODE=0|1|2, HUD=true|false|layout|shapes|text
tools/energy-phone.sh <adb serial> sprites|boids|shadows [count] [seconds] [rounds]    # battery power of the same two, on a phone that is not plugged in (wireless adb), at the display's rate
tools/compare-demos.sh sprites|boids|shadows [count] [frames] [vsync]                  # the same on the desktop: stock OpenGL against gdx-vulkan, with the GPU's power draw from nvidia-smi
tools/device-suite.sh <adb serial> [--es only stress2,shaderPrograms]              # the device suite on one phone, or only the checks whose names contain those words
tools/device-suite.sh <adb serial> --es only stockClasses,gate3Stock,stockSpriteBatch   # libGDX's own classes on the shim: same picture as the Vulkan classes, and the speed (2D; the 3D check is gate5Stock)
```
Both scripts end the same way: the test app is stopped and the phone goes back to its home screen, so whoever holds the phone can see the test is over, also after a
failure or a timeout. Phones need about six seconds to settle (the JIT compiles the draw path, the GPU clock rises), so the benchmarks warm up for six seconds; read a
comparison between two shaders inside one clock state, and alternate their order, as the shader benchmark does.
`docs/RELEASE.md` is the checklist of manual runs (real GPUs, the phone) that CI cannot do.

## Runtime options
System properties: `gdxvulkan.shaderWarmUp` (false turns off the compiler's background start-up), `gdxvulkan.validation`, `gdxvulkan.forceTierB`, `gdxvulkan.device` (index), `gdxvulkan.pipelineCache` (file), `gdxvulkan.native.path`
(directory or file of the built `gdxvk` library). Environment: `GDXVK_COMPLETION=fence|timeline` (GPU completion tracking), `GDXVK_PRESENT=immediate|mailbox`,
`GDXVK_PROFILE=1` (per-frame CPU time), `GDXVK_NO_PRESENT_FENCES=1`, `GDXVK_NO_SWAPPY=1`.
