# gdx-vulkan: notes for Claude Code

Standalone Vulkan renderer for libGDX 1.14.3 (stock libGDX, no fork). Read `docs/PLAN.md` first. The full plan is
"libGDX Vulkan Backend Plan v5.0" in the user's Claude Project "libGDX Vulkan" (plans/ and claude/ docs hold v2-v5 and their reviews).

## Status
Phase 1a is functionally complete on Windows, Linux (lavapipe) and Android; open items are below.
- Native core (`gdx-vulkan-core/jni`): context + tier logic, `Presenter` (Win32/Xlib/Android surface, UNORM swapchain, resize/minimize/
  out-of-date, Tier A dynamic rendering / Tier B VkRenderPass), a Phase 1a-only `createPipeline`/`draw`, `endFrameAndCapture` readback.
  Shaders in `gdx-vulkan-core/src/main/shaders`, compiled by `gradle/compile-shaders.gradle` (glslc).
- Desktop (`gdx-vulkan-desktop`): GLFW glue, `runClearDemo`/`runTriangleDemo`, `PresenterTest`, `TriangleTest`, `GoldenTest`.
- Golden harness: lavapipe goldens in `gdx-vulkan-desktop/src/test/resources/goldens`; exact on lavapipe, tolerance on real GPUs.
- CI: `.github/workflows/gdx-vulkan.yml` -> `ci/Dockerfile` + `ci/run-checks.sh` (lavapipe, Xvfb, validation + sync validation, both tiers,
  Mesa version pinned in `ci/mesa-version.txt`). See `ci/README.md`.
- Android (`gdx-vulkan-android`, separate Gradle 8.14/AGP 8.13 build): `:library` + `:testapp` (SelfTest, then spinning triangle).
Not done: Phase 1b onward; Wayland/macOS; debug-utils object names; armeabi-v7a/x86_64; CI has never run on GitHub itself.

## Verification state
- Windows (RTX 5080 + RTX 2080 via `-Dgdxvulkan.device=1`), both tiers: `gradlew check -Dgdxvulkan.requireDriver=true` passes, validation
  and sync validation clean. Goldens pass with tolerance on both GPUs.
- Linux: the same script CI runs (`ci/run-checks.sh` in the Docker image) passes on lavapipe, Mesa 25.2.8-0ubuntu0.24.04.4, both tiers,
  exact goldens. This is also the first time the Xlib surface path ran.
- Android: REDMAGIC NX809J (Adreno 840, Android 16, Vulkan 1.4), arm64-v8a. SelfTest passes in Tier A and `forceTierB`; HOME/return x3
  destroys and recreates the presenter with the context kept alive. NOTE this is not the Galaxy S20 the plan names.
  Logcat is restricted on this phone; the app writes `files/gdxvk-test.log`:
  `adb shell run-as io.github.slebed.gdxvulkan.testapp cat files/gdxvk-test.log`. No validation layer on the device, so its
  validation count is trivially 0. Phone connected via wireless debugging (`adb devices`).
- Run on phone: `cd gdx-vulkan-android && gradlew :testapp:assembleDebug`, `adb install -r testapp/build/outputs/apk/debug/testapp-debug.apk`,
  `adb shell am start -n io.github.slebed.gdxvulkan.testapp/.MainActivity [--ez forceTierB true]`.
- Maven Central's newest gdx is 1.14.2 (1.14.3 is not published), so `gdxVersion=1.14.2`; bump when 1.14.3 ships.
- The `gdx-vulkan/` directory is untracked in git; nothing is committed.

## Decisions to keep
- Natives: plain CMake + hand-written JNI (gdx-jnigen deferred). Tier A needs core Vulkan 1.3.
- One pipeline layout; Set 0 = dynamic UBO + SSBO, Set 1 = textures; 128-byte push constants. `Gdx.gl30` stays null.
- Swapchain: negative-height viewport, CCW. Offscreen: positive-height, CW. UNORM swapchain format.
- Oracle: stock GL libGDX on llvmpipe vs gdx-vulkan on lavapipe, with tolerance. The old LWJGL Vulkan fork is perf reference only.
- Only upstream PR planned: make VBO/IBO buffer handle and dirty flag public (Phase 5).
