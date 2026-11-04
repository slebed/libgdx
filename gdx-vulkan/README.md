# gdx-vulkan

A Vulkan renderer for libGDX, built as a standalone library on **stock libGDX** (1.14.2 today; 1.14.3 is not on Maven Central yet). No fork of
libGDX is needed: unmodified `Texture`, `Pixmap`, `SpriteBatch`, `FrameBuffer`, Scene2D, `BitmapFont` and friends run against Vulkan through a
GL20 shim, and the result is compared with stock libGDX on OpenGL.

`docs/PLAN.md` has the architecture and the phases; `docs/CHECKLIST.md` is the item-by-item state (126 done, 16 partial, 27 not started, of 169; what is left is Phases 5 and 6, the desktop `Application`, the Galaxy S20, or a stated limit),
marking only what has been built **and verified**.

## Status

| Phase | State |
|---|---|
| 1a Surfaces, swapchain, golden harness, CI | done; Windows, Linux (lavapipe) and Android; the only gaps are other ABIs and Wayland/macOS |
| 1b Memory, layout and caches | done: two-set layout, deferred deletion, frame rings, staging, pipeline cache, shader reflection, 10-minute soaks (RTX 5080 in both tiers, RTX 2080 in Tier A, Adreno 840 in both tiers) |
| 2 Unmodified 2D | done: sprite batches, polygon batch, shape renderer, compressed textures, shader bundles, `VulkanSpriteBatchInstanced` (1,000,000 sprites moved by compute); every Stress 2 target met |
| 3 Stock `FrameBuffer` | done: colour, depth and stencil framebuffers, `VulkanMultiTargetFrameBuffer` (several and floating-point attachments), GL cull/depth/stencil state, pass scheduling, Gate 3 scenes, Stress test 3. Not done: reordering offscreen passes that come after a swapchain draw |
| 4 Android lifecycle | done apart from Swappy frame pacing, `VK_KHR_swapchain_maintenance1` and an Android GPU Inspector capture: `VulkanAndroidApplication` (stock input, audio and files), surface loss and recreation with everything resident (2 ms average), pre-rotation verified at all four angles on the phone, `onTrimMemory`, stock `AssetManager` texture streaming, Stress test 4 at 50 cycles |
| 5 3D with stock models | not started |
| 6 macOS / iOS | not started (stretch) |

**Two tiers.** Tier A (Vulkan 1.3: dynamic rendering, a bindless texture table, dynamic raster state) and Tier B (Vulkan 1.1: render passes,
one descriptor set per texture, pipeline variants). `forceTierB` runs Tier B on any device, and every test runs in both.

**Verified on** an RTX 5080 and an RTX 2080 (Windows), lavapipe in Docker and on GitHub Actions (Mesa pinned, exact goldens), and a REDMAGIC with
an Adreno 840 (Android 16). Not the Galaxy S20 the plan names.

## What the tests compare

- **Stock GL oracle:** the Gate 2 and Gate 3 scenes are written once against stock `Batch`, `ShapeRenderer` and `FrameBuffer` and drawn by stock
  libGDX on OpenGL and by gdx-vulkan. Both Gate 3 scenes match with zero differing pixels on lavapipe versus llvmpipe and on both GPUs, in both
  tiers. Gate 2 matches too, except the shapes scene on lavapipe, which differs from llvmpipe by 116 pixels (0.18%) at line ends, within tolerance.
- **Lavapipe goldens:** committed PNGs, exact on lavapipe, within a tolerance on a real GPU or the phone.
- **Validation:** the layers, with synchronization validation, run in every test; any message fails it.

## Measured

| What | Result |
|---|---|
| Stress 2, 20,000 sprites, 128 textures, Tier A | 24x stock GL (423 fps against 17.5) on the RTX 5080; Tier B 3.5-4x |
| Stress 2, 1 texture | 1.2x stock GL (1,306 fps against 1,064; the target is 1.0x). It was 0.91x until the vertex ring got a device-local mirror |
| 1,000,000 sprites moved by compute | 722 fps on the RTX 2080, 1,879 fps on the RTX 5080, 208-225 fps on the Adreno 840 (target 60+) |
| Stress 3, 100,000 draws through the Java command stream | 1.0-1.3x a plain C++ loop on both GPUs (limit 1.3x); the phone is 1.2-1.8x run to run |
| Stress 4, 50 rotate + background/foreground cycles with textures streaming (Adreno 840, Tier A, pre-rotation on) | 0 failures, picture read back after every step; surface recreation 2 ms average, 5 ms worst (target under 50 ms); native heap flat (26,091 KB at cycle 25, 26,117 KB at cycle 50). Tier B, 20 cycles: 0 failures, 2 ms average, 4 ms worst |

## Layout
- `gdx-vulkan-core/` Java API and the C++ core (`jni/`: CMake, vendored volk, VMA, SPIRV-Reflect and Vulkan headers); shaders in `src/main/shaders`
- `gdx-vulkan-desktop/` desktop glue on stock `gdx-backend-lwjgl3` GLFW window handles, and the desktop tests and stress programs
- `gdx-vulkan-android/` separate Gradle build (AGP): `:library` and a `:testapp` that runs the self-test and the device suite. See its README
- `ci/` the Docker image and script that run every test on lavapipe; `.github/workflows/ci.yml` runs it on GitHub
- `tools/` generators for the GL20 base class and the batch draw code (generated from stock libGDX source so the oracle stays honest); see `tools/README.md`
- `test-assets/` the skin, fonts and particle effects the Gate 2 and Gate 3 scenes load (libGDX's own test assets; see `NOTICE`)
- `docs/` the plan, the checklist and the notes for Claude Code (`CLAUDE.md`)

## Using it on Android
```java
public class MyActivity extends VulkanAndroidApplication {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        initialize(new MyGame()); // or initialize(listener, androidConfig, new GdxVkConfig(), vsync)
    }
}
```
Declare `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|keyboard|density"` on the activity so a
rotation arrives as a surface change. In `create()`, make the batches from `getVulkanGraphics().getFrame()`; that frame object stays valid for the life of
the application, while the swapchain behind it is replaced whenever the surface is. Textures, buffers and framebuffers survive the surface being
destroyed (no managed-texture reload). Pre-rotation is on by default (`GdxVkConfig.preRotate`): the compositor does not rotate, the shaders do, and
`Gdx.graphics.getWidth/Height`, `glViewport` and `glScissor` stay in the logical orientation. A custom shader bundle must call `gdx_vk_clip()` (as
`sprite.vert` does) for its output to be rotated. `VulkanDebugOverlay` shows renames, retired objects, samplers and staging blocks while you develop.

## Build and test
Prerequisites: JDK 11+ (class files target Java 8), CMake 3.20+, a C++17 compiler, the Vulkan loader, `glslc` (Vulkan SDK or the `glslc`
package), and on Linux `libx11-dev`. For validation layers install the Vulkan SDK or your distro's validation layer package. For a software
device on Linux: `mesa-vulkan-drivers` (lavapipe).

```
gradlew check -Dgdxvulkan.requireDriver=true                          # every test, validation + sync validation
gradlew check -Dgdxvulkan.requireDriver=true -Dgdxvulkan.forceTierB=true
gradlew check -Dgdxvulkan.requireDriver=true -Dgdxvulkan.device=1     # the second GPU
gradlew check ... -Dgdxvulkan.requireGl=true                          # fail instead of skip when the stock-GL images are missing
```
Native only: `cmake -S gdx-vulkan-core/jni -B build/native && cmake --build build/native && ctest --test-dir build/native`.

Without a Vulkan driver the driver tests skip; `-Dgdxvulkan.requireDriver=true` turns that into a failure (CI does). Gate 2 and Gate 3 use the skin, fonts and particle effects in
`test-assets/` (see `NOTICE`); `-Dgdxvulkan.assetsDir=` points elsewhere, and they skip if the directory is missing.

Benchmarks and soak (each prints a table; `-Dgdxvulkan.<name>.report=true` never fails the build):
```
gradlew :gdx-vulkan-desktop:stress2                                   # VulkanSpriteBatch against stock SpriteBatch, each in its own JVM
gradlew :gdx-vulkan-desktop:stressInstanced -Dgdxvulkan.device=1      # 1,000,000 sprites moved by compute
gradlew :gdx-vulkan-desktop:stress3                                   # command stream recording against a C++ floor
gradlew :gdx-vulkan-desktop:soakTest -Dgdxvulkan.soakSeconds=600      # the full soak
```

Lavapipe, as CI runs it (see `ci/README.md`):
```
docker build -t gdx-vulkan-ci ci
docker run --rm -v <repo>:/src:ro -v <out>:/out -v gdxvk-gradle:/root/.gradle gdx-vulkan-ci bash /src/ci/run-checks.sh
```

Android (`gdx-vulkan-android/`, its own Gradle build):
```
gradlew :testapp:assembleDebug && adb install -r testapp/build/outputs/apk/debug/testapp-debug.apk
adb shell am start -n io.github.slebed.gdxvulkan.testapp/.MainActivity --ez suite true [--ez forceTierB true] [--ez vsync false]
adb shell run-as io.github.slebed.gdxvulkan.testapp cat files/gdxvk-test.log      # look for "DEVICESUITE PASS"
tools/stress4.sh [cycles=50] [--tier-b] [--no-prerotate] [--no-stream]             # Stress test 4: rotate + background/foreground while textures stream
```
`docs/RELEASE.md` is the checklist of manual runs (real GPUs, the phone) that CI cannot do.

## Runtime options (system properties)
`gdxvulkan.validation`, `gdxvulkan.forceTierB`, `gdxvulkan.device` (index), `gdxvulkan.pipelineCache` (file), `gdxvulkan.native.path` (dir or file
of the built `gdxvk` library). Environment `GDXVK_COMPLETION=fence|timeline` overrides the GPU completion tracking (diagnostics).

## Known gaps
- No GL buffer calls yet (`glBufferData` and friends): they arrive with stock `Mesh` support in Phase 5, so 3D models are not drawable yet.
- The swapchain has no depth buffer (framebuffers do); Phase 5 adds it.
- Custom shaders are precompiled SPIR-V bundles (`VulkanShaderPipelineBundle`); there is no run-time GLSL compilation.
- Natives run on Windows x64, Linux x64 and Android arm64-v8a. The Android library also compiles for armeabi-v7a and x86_64, which have never been run.
- Android: the on-screen keyboard and native text input through `Gdx.input` do not work yet (stock `DefaultAndroidInput` casts the graphics object to its own GL class there). No Swappy frame pacing, and swapchains are rebuilt after `vkDeviceWaitIdle` (2-5 ms measured).
- There is no desktop `Application` yet: desktop programs build the context, presenter and frame themselves, as the tests do (see `gdx-vulkan-desktop`).
- Linux arm64 natives are built and their native tests run by CI on GitHub's arm64 runner; the Java suite does not run there.
- Offscreen passes that come after a swapchain draw are not reordered ahead of it (a framebuffer pass in the middle of a frame still suspends the main pass).
- A framebuffer's depth keeps its contents between passes, as in GL; the on-chip (transient) depth of tile-based GPUs is an opt-in that needs every pass to clear it.

## Third-party code (vendored in `gdx-vulkan-core/jni/vendor`)
volk, Vulkan-Headers, Vulkan Memory Allocator, SPIRV-Reflect. Each is under its own permissive licence; see the upstream repositories for versions
and licence texts. Versions are pinned by the vendored copies.
