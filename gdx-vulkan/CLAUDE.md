# gdx-vulkan: notes for Claude Code

Standalone Vulkan renderer for libGDX (stock libGDX, no fork; gdx 1.14.2 until 1.14.3 is published). Read `docs/PLAN.md` first. The full plan is
"libGDX Vulkan Backend Plan v5.0" in the user's Claude Project "libGDX Vulkan" (plans/ and claude/ docs hold v2-v5 and their reviews).
Work happens on branch `gdx-vulkan-phase1` (pushed to the fork `slebed/libgdx`; GitHub Actions runs the lavapipe suite on every push).

## Status
Phase 1a is complete on Windows, Linux (lavapipe) and Android. Phase 1b is implemented and verified on desktop GPUs and lavapipe; the
Android side of 1b and Phase 2 runs on the Adreno 840 (`DeviceSuite`, both tiers; 10-minute Tier A soak passed). Open items are at the end of this section.

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
- Phase 2 (`io.github.slebed.gdxvulkan.gl` and `.g2d`): `VulkanGL20` (texture shim incl. compressed formats, query table, ordered state into the
  command stream) over `GdxVkResources`, `PixelConversion`, and `VulkanGL20Base`, **generated** from libGDX's `GL20.java` by
  `tools/generate_gl20_base.py` (regenerate, do not edit). Textures are recorded as GL state in Java and the native image is created at first
  use (`nativeTexture(glHandle)`); pixel changes afterwards rename the image natively. Uploads are recorded at `endFrame` into a separate
  command buffer submitted ahead of the frame. Draws go through `CommandStream` (`gdxvk_commands.h`) and one `nativeSubmitCommands` per frame.
  `VulkanSpriteBatch`, `VulkanPolygonSpriteBatch`, `VulkanShapeRenderer` have their draw code **generated from stock source**
  (`tools/generate_*.py`; keep it that way so the Gate 2 oracle stays honest). `VulkanShaderPipelineBundle` = custom SPIR-V for the batches.
  `VulkanSpriteBatchInstanced` (1M sprites moved by compute, 722 fps on the RTX 2080): compute dispatches are queued with `GdxVkFrame.dispatch`
  and recorded at `endFrame` in the upload command buffer (before the render pass); `CommandStream.bindStorage` binds a resource buffer as Set 0 storage.
  Phase 3 stage 1 (colour framebuffers): `CommandStream.bindTarget` + `BindTarget` op; `Presenter::Impl` owns the pass state machine (`beginPass`/`endPass`,
  lazy begin, viewport/scissor/front face re-derived per target), pipelines are built per (colour format, offscreen) variant from kept shader modules,
  Tier B render pass variants must have identical subpass dependencies to stay compatible. Depth/stencil: `Resources::createDepthImage` (one combined image per framebuffer), `BindTarget` carries the depth id, `DepthState`/`StencilState` ops, `Impl::dsState` (Tier A dynamic, Tier B baked into pipeline variants; stencil reference and masks dynamic on both), render pass variants per clear flags. Gate 3: `Gate3Scenes` (2D+3D into framebuffers; Scene2D UI through four ping-pong post passes; GLSL `Fx` on stock GL, SPIR-V `fx_*.frag` bundles on gdx-vulkan), `Gate3GoldenTest`, `GlReferenceMain` renders the references; exact vs stock GL everywhere. Note: Docker runs need `-v <repo>/tests/gdx-tests-android/assets:/assets:ro` or Gate 2/3 are skipped. Stress 3 (`gradlew :gdx-vulkan-desktop:stress3`): `Presenter::benchmarkRecord` is the C++ floor; `CommandStream` encodes into an `int[]` and copies in bulk (buffer views cost ~80 ns per put on ART), `PushRange` sends only changed push words and the native side keeps the block (`pushShadow`) and pushes it before each draw; `CullMode` is an ordered op (GL cull state replaces the pipelines' own). Not done: swapchain depth (Phase 5), pass scheduling, `VulkanMultiTargetFrameBuffer`. Device suite: 20 checks incl. Gate 3 (needs `android.useAndroidX=true`, set).
  Not done: Tier A 1-texture parity with stock GL (0.91x)

Tests (`gdx-vulkan-desktop`): `TextureShimTest`, `SpriteBatchTest`, `InstancedBatchTest`, `FrameBufferTest`, `Gate2GoldenTest` (7 scenes: stock-GL oracle with tolerance, then exact lavapipe goldens),
`PresenterTest`, `TriangleTest`, `GoldenTest` (lavapipe goldens, exact on lavapipe, tolerance on GPUs),
`ResourcesTest` (all three sets reach the shader, deferred deletion, pipeline cache file, reflection rejects bad shaders), and `SoakTest`
via its own Gradle task `soakTest` (`-Dgdxvulkan.soakSeconds=600` for the full run). CI: `.github/workflows/gdx-vulkan.yml` ->
`ci/Dockerfile` + `ci/run-checks.sh` (see `ci/README.md`). Android: `gdx-vulkan-android` (separate Gradle 8.14/AGP 8.13 build).

Open items: 10-minute soak in Tier B (desktop and Android); Adreno Tier A throughput (see below); Wayland/macOS; debug-utils object names; armeabi-v7a/x86_64;
`VulkanSpriteBatchInstanced`; Phases 3-6.
`./gradlew :gdx-vulkan-desktop:stressInstanced -Dgdxvulkan.device=1` is the instanced sprite stress test (1M sprites; not the plan's Stress test 3, which is Phase 3). `./gradlew :gdx-vulkan-desktop:stress2` is Stress test 2 (stock GL vs VulkanSpriteBatch, each in its own JVM). Note GL runs on the display GPU, so a
second-GPU Vulkan comparison is capped by presentation and means nothing.

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
  and survived HOME/return x3. The phone connects over wireless debugging; USB was flaky. `DeviceSuite` runs the Phase 1b/2 checks on it:
  `adb shell am start -n io.github.slebed.gdxvulkan.testapp/.MainActivity --ez suite true [--ez forceTierB true] [--ez vsync false]
  [--ei soakSeconds N] [--ei stressSeconds N]`, then read the log. 14/14 pass in both tiers (sprite batch scenes, shader bundle, mipmaps,
  ETC2 and ASTC textures, 20 s texture-churn soak with flat native heap, Stress 2 workload without the GL half). Logcat is restricted on that
  phone; the app writes `files/gdxvk-test.log` (`adb shell run-as io.github.slebed.gdxvulkan.testapp cat files/gdxvk-test.log`). Native crashes:
  `adb shell dumpsys dropbox --print data_app_native_crash`. No validation layer on the device.
  The device found a bug desktop never showed: `DeletionQueue::collect()` self-moved a `std::function` (empty on the NDK's libc++).
  Adreno 840 throughput, 20,000 sprites at 1216x2688, vsync off: Tier A 548 fps with 1 texture but 38 fps with 128; Tier B 1,428 and 8.6 fps.
  Tier A 1-texture being 2.6x slower than Tier B is not investigated (suspect the bindless table on Adreno).
- Validation-layer caveat: sync validation cost grows with the number of barrier commands (batching image-upload barriers took the Tier A soak from 0.1 fps back to 60 fps), and with fence completion sync validation slows super-linearly under the soak load (60 fps -> <5 fps) although the
  same run with validation off is perfect, so `soakTest` turns sync validation off when `forceTierB` is set. Ubuntu's older layer also needs
  an explicit dependency between consecutive captures (added to the capture path).

## Decisions to keep
- Natives: plain CMake + hand-written JNI (gdx-jnigen deferred). Tier A needs core Vulkan 1.3.
- One pipeline layout; Set 0 = dynamic UBO + SSBO, Set 1 = textures; 128-byte push constants. `Gdx.gl30` stays null.
- Swapchain: negative-height viewport, CCW. Offscreen: positive-height, CW. UNORM swapchain format.
- Oracle: stock GL libGDX on llvmpipe vs gdx-vulkan on lavapipe, with tolerance. The old LWJGL Vulkan fork is perf reference only.
- Only upstream PR planned: make VBO/IBO buffer handle and dirty flag public (Phase 5).
- The shell tool mangles long heredocs and backslashes; make multi-line edits with the Edit/Write tools or a script file.
