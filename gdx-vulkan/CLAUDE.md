# gdx-vulkan: notes for Claude Code

Standalone Vulkan renderer for libGDX (stock libGDX, no fork; gdx 1.14.2 until 1.14.3 is published). Read `docs/PLAN.md` first. The full plan is
"libGDX Vulkan Backend Plan v5.0" in the user's Claude Project "libGDX Vulkan" (plans/ and claude/ docs hold v2-v5 and their reviews).
Work happens on branch `gdx-vulkan-phase1`; Phase 1a is committed, Phase 1b is in the working tree.

## Status
Phase 1a is complete on Windows, Linux (lavapipe) and Android. Phase 1b is implemented and verified on desktop GPUs and lavapipe; the
Android side of 1b compiles but has not run on a device. Open items are at the end of this section.

Native core (`gdx-vulkan-core/jni/src`), one file per subsystem, all owned by `Context` (`gdxvk_internal.h` has `ContextHandles`):
- `gdxvk_context` instance/device/VMA/tier; `gdxvk_presenter` surface + swapchain + per-frame loop (+ Phase 1a-only `createPipeline`/`draw`).
- `gdxvk_timeline` submit serials; GPU completion by timeline semaphore (Vulkan 1.2+) or one fence per submit. `forceTierB` selects fences
  so CI covers the 1.1 path. Env `GDXVK_COMPLETION=fence|timeline` overrides, for diagnosis.
- `gdxvk_deletion` deferred deletion. `retire(fn)` tags with the next submit's serial. **Anything that may still have a copy queued in the
  staging ring (images, buffer ranges) must be retired with `extraSerials = 1`**, because the copy is recorded in the *next* frame. This was
  a real use-after-free found by the soak test.
- `gdxvk_layout` the one pipeline layout (Set 0 dynamic UBO/SSBO, Set 1 textures: Tier A bindless table, Tier B per-texture sets).
- `gdxvk_rings` per-frame dynamic ring for Set 0 (grows by adding blocks + a new set). `gdxvk_staging` mutex-guarded staging ring, copies
  recorded at the start of the frame command buffer. `gdxvk_caches` sampler cache + disk-backed pipeline cache.
- `gdxvk_resources` buffers (VmaVirtualBlock sub-allocation) and RGBA8 textures behind int ids; `gdxvk_reflect` SPIRV-Reflect validator
  (vendored in `jni/vendor/spirv-reflect`); shaders compile twice (`name.spv` Tier B, `name.a.spv` with `-DGDX_TIER_A=1`).
- Java: `GdxVkContext`, `GdxVkPresenter`, `GdxVkResources`, `GdxVkSampler`, `GdxVkShaders`.

Tests (`gdx-vulkan-desktop`): `PresenterTest`, `TriangleTest`, `GoldenTest` (lavapipe goldens, exact on lavapipe, tolerance on GPUs),
`ResourcesTest` (all three sets reach the shader, deferred deletion, pipeline cache file, reflection rejects bad shaders), and `SoakTest`
via its own Gradle task `soakTest` (`-Dgdxvulkan.soakSeconds=600` for the full run). CI: `.github/workflows/gdx-vulkan.yml` ->
`ci/Dockerfile` + `ci/run-checks.sh` (see `ci/README.md`). Android: `gdx-vulkan-android` (separate Gradle 8.14/AGP 8.13 build).

Open items: Android device run of 1b and the 10-minute soak on devices; Wayland/macOS; debug-utils object names; armeabi-v7a/x86_64;
CI has never run on GitHub itself; the GL20 shim and everything after Phase 1b.

## Verification state
- **`-Dgdxvulkan.device=N` was not being forwarded to the test JVM until Phase 1b**, so every earlier "RTX 2080" result was really the
  RTX 5080. Fixed; index 0 = RTX 5080, index 1 = RTX 2080 on this machine, and the soak prints the device name it used.
- Windows RTX 5080 and RTX 2080, both tiers: `gradlew check -Dgdxvulkan.requireDriver=true [-Dgdxvulkan.forceTierB=true] [-Dgdxvulkan.device=1]`
  passes with validation + sync validation clean, goldens within tolerance. Soak (15 s): ~50,000 buffer cycles/s, 2,000 textures/s,
  60.0 fps, flat memory, 4 samplers, on both GPUs and both tiers. A 60 s run on the 5080 Tier A was flat after warm-up.
  The full 10-minute soak has not been run yet.
- Linux: `ci/run-checks.sh` in the Docker image passes on lavapipe, Mesa 25.2.8-0ubuntu0.24.04.4, both tiers, exact goldens. Soak rates are
  scaled down 10x on software devices (it checks hazards and leaks there, not throughput).
- Android (REDMAGIC NX809J, Adreno 840, Android 16, Vulkan 1.4; **not** the Galaxy S20 in the plan): Phase 1a SelfTest passed in both tiers
  and survived HOME/return x3. After Phase 1b the module only compile-checks for arm64 (phone unplugged). Logcat is restricted on that phone;
  the app writes `files/gdxvk-test.log` (`adb shell run-as io.github.slebed.gdxvulkan.testapp cat files/gdxvk-test.log`). No validation
  layer on the device.
- Validation-layer caveat: with fence completion, sync validation slows super-linearly under the soak load (60 fps -> <5 fps) although the
  same run with validation off is perfect, so `soakTest` turns sync validation off when `forceTierB` is set. Ubuntu's older layer also needs
  an explicit dependency between consecutive captures (added to the capture path).

## Decisions to keep
- Natives: plain CMake + hand-written JNI (gdx-jnigen deferred). Tier A needs core Vulkan 1.3.
- One pipeline layout; Set 0 = dynamic UBO + SSBO, Set 1 = textures; 128-byte push constants. `Gdx.gl30` stays null.
- Swapchain: negative-height viewport, CCW. Offscreen: positive-height, CW. UNORM swapchain format.
- Oracle: stock GL libGDX on llvmpipe vs gdx-vulkan on lavapipe, with tolerance. The old LWJGL Vulkan fork is perf reference only.
- Only upstream PR planned: make VBO/IBO buffer handle and dirty flag public (Phase 5).
- The shell tool mangles long heredocs and backslashes; make multi-line edits with the Edit/Write tools or a script file.
