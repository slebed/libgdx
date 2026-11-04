# gdx-vulkan: notes for Claude Code

Standalone Vulkan renderer for libGDX (stock libGDX, no fork; gdx 1.14.2 until 1.14.3 is published). Read `docs/PLAN.md` first. The full plan is
"libGDX Vulkan Backend Plan v5.0" in the user's Claude Project "libGDX Vulkan" (plans/ and claude/ docs hold v2-v5 and their reviews).
The project lives in its own repository, `slebed/gdx-vulkan` (public, branch `main`; GitHub Actions runs the lavapipe suite on every push). It was split out of the user's fork `slebed/libgdx`, where this directory is still on branch `gdx-vulkan-phase1`; the fork also holds the user's old LWJGL Vulkan backend work, which must not be touched. Commits made in the fork's `gdx-vulkan/` directory are mirrored to the standalone repo by committing the tree `HEAD:gdx-vulkan` on top of its `main` (`git commit-tree`, same author, date and message).

## Status
Phase 1a is complete on Windows, Linux (lavapipe) and Android. Phase 1b is implemented and verified on desktop GPUs and lavapipe; the
Android side of 1b to 3 runs on the Adreno 840 (`DeviceSuite`, 21 checks, both tiers; 10-minute soaks passed in both tiers). Open items are at the end of this section.

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
  Tier B render pass variants must have identical subpass dependencies to stay compatible. Depth/stencil: `Resources::createDepthImage` (one combined image per framebuffer), `BindTarget` carries the depth id, `DepthState`/`StencilState` ops, `Impl::dsState` (Tier A dynamic, Tier B baked into pipeline variants; stencil reference and masks dynamic on both), render pass variants per clear flags. Gate 3: `Gate3Scenes` (2D+3D into framebuffers; Scene2D UI through four ping-pong post passes; GLSL `Fx` on stock GL, SPIR-V `fx_*.frag` bundles on gdx-vulkan), `Gate3GoldenTest`, `GlReferenceMain` renders the references; exact vs stock GL everywhere. The test assets Gate 2/3 load are bundled in `test-assets/` (13 files, provenance in `NOTICE`). Stress 3 (`gradlew :gdx-vulkan-desktop:stress3`): `Presenter::benchmarkRecord` is the C++ floor; `CommandStream` encodes into an `int[]` and copies in bulk (buffer views cost ~80 ns per put on ART), `PushRange` sends only changed push words and the native side keeps the block (`pushShadow`) and pushes it before each draw; `CullMode` is an ordered op (GL cull state replaces the pipelines' own). Not done: swapchain depth (Phase 5), pass scheduling, `VulkanMultiTargetFrameBuffer`. Device suite: 20 checks incl. Gate 3 (needs `android.useAndroidX=true`, set).
  Phase 3 is done apart from true reordering of offscreen passes: `BindTarget` takes 0-8 colour textures plus a depth image; the swapchain pass starts at the
  first swapchain draw or clear (pass scheduling, `lastPassCounts()`); `Impl::ColorSet` is part of the pipeline variant key and a pipeline writes exactly as many
  outputs as its target has attachments (`PipelineDesc::colorOutputs`); float targets are `TextureDesc::vkFormat`; render-target images need no upload (null data).
  `VulkanMultiTargetFrameBuffer` (package `glutils`) builds a multi-attachment framebuffer from stock GL calls. Transient depth is an opt-in
  (`VulkanGL20.setFramebufferDepthTransient`). Vertex ring: cached system memory plus, on discrete GPUs, a device-local mirror copied at the start of the frame
  (`FrameRing::recordVertexCopies`): reading vertices across PCIe was the whole 1-texture gap (now 1.2x stock GL). Debug names and labels
  (`nameObject`/`beginLabel` in `gdxvk_internal.h`) need `VK_EXT_debug_utils`, enabled whenever present. Diagnostics: `GDXVK_PROFILE=1` prints per-frame CPU time of
  beginFrame/endFrame at close, `GDXVK_PRESENT=immediate|mailbox`, `GDXVK_IMAGES=n`. The Gradle `test` task rebuilds natives; run `:gdx-vulkan-core:buildNatives` first when
  only C++ changed and you run a single test. `transient` is a Java keyword: do not use it as a parameter name.

Tests (`gdx-vulkan-desktop`): `TextureShimTest`, `SpriteBatchTest` (incl. shape-renderer bundles and `VulkanDebugOverlay`), `PreRotationTest`, `AssetManagerTest`, `InstancedBatchTest`, `FrameBufferTest`, `Gate2GoldenTest` (7 scenes: stock-GL oracle with tolerance, then exact lavapipe goldens),
`PresenterTest`, `TriangleTest`, `GoldenTest` (lavapipe goldens, exact on lavapipe, tolerance on GPUs),
`ResourcesTest` (all three sets reach the shader, deferred deletion, pipeline cache file, reflection rejects bad shaders), and `SoakTest`
via its own Gradle task `soakTest` (`-Dgdxvulkan.soakSeconds=600` for the full run). CI: `.github/workflows/gdx-vulkan.yml` ->
`ci/Dockerfile` + `ci/run-checks.sh` (see `ci/README.md`). Android: `gdx-vulkan-android` (separate Gradle 8.14/AGP 8.13 build). `docs/RELEASE.md` lists the manual runs a release needs.

Phase 4 (Android lifecycle): `VulkanAndroidApplication` + `VulkanAndroidGraphics` in `gdx-vulkan-android/library` (render thread owns every Vulkan call; events from the UI thread are posted to it,
`surfaceDestroyed`/`onPause`/`onDestroy` wait for it). Only the presenter is destroyed with the surface; `GdxVkFrame.replacePresenter` keeps the batches valid and clears the frame's pipeline map.
`SnapshotArray.begin()` returns the backing array: iterate to `size`, not the array (this was a real NPE). Pre-rotation: `Presenter::create(..., preRotate)` builds the swapchain with the display
transform; `Impl::rot` (quarter turns clockwise) rotates viewport and scissor rectangles into the image (`rotateRect`), and specialization constant 0 of every vertex shader that calls
`gdx_vk_clip` (`GDX_PREROT`, part of the pipeline variant key) rotates the clip-space output; captures are un-rotated so tests see the logical orientation. A bundle whose vertex shader skips
`gdx_vk_clip` is not rotated. `GdxVkPresenter.debugForceRotation` lets the desktop test (`PreRotationTest`, square window) prove the pieces agree; the phone confirmed the direction at all four angles.
`presenter.rotation()` reports it (it lags `resize()` by one frame: the swapchain is rebuilt in the next `beginFrame`). `GdxVkContext.trimMemory()` backs `onTrimMemory`. The phone tests are
`LifecycleActivity` (+ `CoverActivity`) driven by `tools/stress4.sh` (50 cycles by default; the user judged 500 far too long). Unloading a texture between drawing it and flushing the batch throws
"texture has no storage": dispose only between frames or after `end()`.

Phase 5 (3D) so far: `VulkanGL20` buffer calls (GL name -> native buffer, a new native buffer when one is redefined after being drawn this frame), `g3d/StockMeshAccess` (reads `bufferHandle`/`isDirty`/`byteBuffer` of stock VBOs and IBOs by
reflection, uploads dirty meshes at draw time, vertex/index arrays go through the ring; **the data size is the typed buffer's limit, never the byte buffer's**, and `uploadBuffer` must set the copy's limit, both were real bugs),
`CommandStream.bindVertexBuffer` (op 19) and `set0Data` (op 20: payload copied into the frame ring and bound as Set 0), `GdxVkPipelineDesc.specConstants` (ids 1..n of both stages; id 0 stays the pre-rotation), a swapchain depth image
(`Presenter::setDepth`, `beginSwapPassWithDepth`; Tier B uses the offscreen-pass family for the swapchain when it has depth, with the frame-start layout barrier Tier A also does) and `g3d/VulkanModelBatch`.
Phase 5 is done apart from shadow cascades and point-light shadows. Later notes: `StockMeshAccess` reads the stock fields through JNI field ids (`FieldAccess`; JDK 18+ reflection allocates per call, which broke the zero-allocation
criterion); `StockMeshFieldsTest` pins the names. `FrameRing::allocate` binds non-empty storage data with an exact-range descriptor set (a WHOLE_SIZE dynamic storage descriptor with a non-zero dynamic offset is invalid; 512 sets per frame).
`VulkanModelBatch` data blob (Set 0 `gdx_data`, vec4s): header 41, record 15 per renderable, bones after; vertex shaders are generated `model_default_v<mask>[b<n>].vert` wrappers, material/light flags are spec constants 1..17. Specular/emissive/normal
textures and shadow maps are Tier A only (Tier B: one texture per draw; untextured draws bind a shared white texture because Tier B shaders reference the texture binding statically). `InstancingSorter` + `drawIndexedInstanced` merge identical renderables.
GPU-driven path: `VulkanComputeShader` (queue order, automatic barriers), `VulkanGpuInstances` (one world buffer: header, instances, visible list, indirect command, lights, 16x9x24 cluster lists), `gpu_cull.comp`, `light_cull.comp`, `gpu_lit_*`,
example `boids.comp`; `GdxVkResources.readBuffer` is for tests. `Gate5Scenes` holds the scenes (`hasStockReference` false for normal mapping), `Stress5Main` is `gradlew :gdx-vulkan-desktop:stress5`. The upstream patch is in `docs/upstream/` and is NOT filed.
The model shaders are ports of libGDX's default shaders: `model_default_vertex.glsl` (body) + generated wrappers `model_default_v<mask>[b<n>].vert` (mask bits normal=1 colour=2 uv=4, n bone weights; regenerate with the shell loop in
the commit that added them, they only differ in two defines), `model_default.frag`; the blob layout is documented at the top of the vertex body. Tier B shaders reference the texture binding, so every Tier B draw binds a texture
(a shared 1x1 white one when there is none). Do not destroy anything the recorded stream names before the frame is submitted (the white texture is per context for that reason). Gate 5: `Gate5Scenes` + `Gate5GoldenTest`
(`GDXVK_G5_ONLY=knight` runs one scene); goldens come from the Docker lavapipe run (`UPDATE_GOLDENS=1`, then copy only the new files after checking the others are byte-identical).

Open items: Adreno Tier A throughput (see below); Wayland/macOS; running armeabi-v7a/x86_64 (they compile); Linux arm64 natives (CI job `arm64-natives`); Phase 6 (tabled: no Mac).
Phase 5 finish: cascaded shadows (`g3d/VulkanCascadedShadowLight`; header grew to 55 vec4s, cascade matrices 41..52, splits 53, slots 54, spec constant 18 = cascade count, the cascade is picked in the fragment shader from `1/gl_FragCoord.w`);
`VK_KHR_swapchain_maintenance1` present fences (`Impl::fencesOn()`, parked swapchains, a device wait is kept when a slot has no pending fence; a device wait does NOT retire a present fence, wait on the fences before destroying them);
Swappy (`gdxvk_swappy.cpp`, only with `-DGDXVK_SWAPPY=ON` from the Android Gradle build; needs the shared STL; `GdxVkContext.setFramePacingActivity` before the context exists; present fences are off on a Swappy swapchain);
the desktop `Application` (`VulkanDesktopApplication`; `VulkanDesktopInput` is GENERATED by `tools/generate_desktop_input.py` from stock `DefaultLwjgl3Input`, do not edit).
`./gradlew :gdx-vulkan-desktop:stressInstanced -Dgdxvulkan.device=1` is the instanced sprite stress test (1M sprites; not the plan's Stress test 3, which is Phase 3). `./gradlew :gdx-vulkan-desktop:stress2` is Stress test 2 (stock GL vs VulkanSpriteBatch, each in its own JVM). Note GL runs on the display GPU, so a
second-GPU Vulkan comparison is capped by presentation and means nothing.

## Verification state
- **`-Dgdxvulkan.device=N` was not being forwarded to the test JVM until Phase 1b**, so every earlier "RTX 2080" result was really the
  RTX 5080. Fixed; index 0 = RTX 5080, index 1 = RTX 2080 on this machine, and the soak prints the device name it used.
- Windows RTX 5080 and RTX 2080, both tiers: `gradlew check -Dgdxvulkan.requireDriver=true [-Dgdxvulkan.forceTierB=true] [-Dgdxvulkan.device=1]`
  passes with validation + sync validation clean, goldens within tolerance. Soak (15 s): ~50,000 buffer cycles/s, 2,000 textures/s,
  60.0 fps, flat memory, 4 samplers, on both GPUs and both tiers. A 60 s run on the 5080 Tier A was flat after warm-up.
  The 10-minute soak: RTX 5080 Tier A and Tier B, RTX 2080 Tier A, Adreno 840 Tier A and Tier B, all flat; not run: RTX 2080 Tier B.
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
- **Minimise changes to libGDX and to the code that uses it** (the owner's design goal). Never fork or patch libGDX; the only upstream change planned is two public accessors (`docs/upstream/`). Prefer making stock classes work unchanged (the GL20 shim, stock `Texture`, `FrameBuffer`, `Mesh`, `Stage`, ...) over adding library classes; where a replacement class is unavoidable (batches, application classes) give it the stock class's constructors and behaviour, so porting is a rename. Any API that asks users for something stock does not (an extra argument, an extra setup call) needs a reason.
- Natives: plain CMake + hand-written JNI (gdx-jnigen deferred). Tier A needs core Vulkan 1.3.
- One pipeline layout; Set 0 = dynamic UBO + SSBO, Set 1 = textures; 128-byte push constants. `Gdx.gl30` stays null.
- Swapchain: negative-height viewport, CCW. Offscreen: positive-height, CW. UNORM swapchain format.
- Oracle: stock GL libGDX on llvmpipe vs gdx-vulkan on lavapipe, with tolerance. The old LWJGL Vulkan fork is perf reference only.
- Only upstream PR planned: make VBO/IBO buffer handle and dirty flag public (Phase 5).
- The shell tool mangles long heredocs and backslashes; make multi-line edits with the Edit/Write tools or a script file.
