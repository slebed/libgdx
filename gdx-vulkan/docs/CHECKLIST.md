# libGDX Vulkan Backend Plan v5.0: implementation checklist

Source: `docs/libGDX Vulkan Backend Plan v5.0.md`. Marks reflect what has actually been built **and verified**, not what is merely written.

- `[x]` done and verified   - `[~]` partial, or done differently from the plan (the note says how)   - `[ ]` not started

"Verified" means: passes `gradlew check -Dgdxvulkan.requireDriver=true` (validation + sync validation clean) on the RTX 5080 and RTX 2080 in both
tiers, and `ci/run-checks.sh` on lavapipe in Docker. Android items were verified on a REDMAGIC NX809J (Adreno 840), which is **not** the Galaxy S20
the plan names.

## Summary

| Section | Done | Partial | Not started | Total |
|---|---|---|---|---|
| Core decisions | 42 | 5 | 9 | 56 |
| Phase 1a: Extraction, window surfaces and CI | 17 | 5 | 0 | 22 |
| Phase 1b: Memory, layout and caches | 18 | 2 | 0 | 20 |
| Phase 2: Unmodified 2D | 23 | 2 | 0 | 25 |
| Phase 3: Stock FrameBuffer | 12 | 1 | 0 | 13 |
| Phase 4: Android lifecycle | 0 | 2 | 10 | 12 |
| Phase 5: 3D with stock models | 0 | 0 | 12 | 12 |
| Phase 6 (stretch): macOS and iOS | 0 | 0 | 4 | 4 |
| Cross-cutting and housekeeping | 1 | 0 | 4 | 5 |
| **All** | **113** | **17** | **39** | **169** |
---

## Core decisions

### 1. Standalone library, no fork
- [x] Depends on upstream `com.badlogicgames.gdx:gdx` (1.14.2: 1.14.3 is not on Maven Central; bump when it ships)
- [x] Core `gdx` is never patched
- [ ] Reuses `gdx-backend-lwjgl3` for input, files and OpenAL audio (the desktop module only uses its GLFW; no Gdx input/files/audio integration yet)
- [ ] Reuses `gdx-backend-android` for input, audio and files
- [x] Copied `backend/vulkan/audio/*` and `jni/VulkanNative.*` are not carried over (this is a fresh project)
- [x] No `lwjgl-vulkan`, `lwjgl-vma` or `lwjgl-shaderc` dependency; GLFW used for desktop windowing only
- [x] Old LWJGL Vulkan backend untouched, kept for performance comparison only

### 2. One pipeline layout per device, two descriptor sets
- [x] One fixed `VkPipelineLayout` for the device
- [x] Push constants 0-127 bytes (`u_transform`, `u_color`, `u_indices`, `u_custom0/1`) declared in `gdx_vk_common.glsl`
- [x] Set 0 binding 0 `UNIFORM_BUFFER_DYNAMIC`, range <= 16 KB
- [x] Set 0 binding 1 `STORAGE_BUFFER_DYNAMIC`
- [x] Set 1 Tier A: global `COMBINED_IMAGE_SAMPLER` table, `PARTIALLY_BOUND | UPDATE_AFTER_BIND | UPDATE_UNUSED_WHILE_PENDING`, bound once per command buffer
- [x] Set 1 Tier B: one descriptor set per texture, rebound on texture change
- [ ] Set 1 Tier B alternative: push descriptor (not needed so far)
- [x] Tier A table size = min(4096, the update-after-bind sampled-image and sampler limits, per stage and per set) (`gdxvk_tier.h`, unit-tested)
- [x] Tier A requires all of the listed features, else Tier B (core Vulkan 1.3 supplies dynamic rendering and extended dynamic state)
- [x] SPIRV-Reflect validates every declared `(set, binding, type)` at shader load and fails naming the shader

### 3. GL20 shim scope (`VulkanGL20`)
- [x] `glGenTexture`, `glBindTexture`, `glActiveTexture`, `glTexImage2D`, `glTexSubImage2D`, `glTexParameteri/f`, `glGenerateMipmap`, `glDeleteTexture`, `glPixelStorei`, `glCompressedTexImage2D`/`SubImage2D` done and tested with stock `Texture`/`Pixmap`; only `GL_TEXTURE_2D` (no cube maps)
- [x] Lazy `VkImage` creation at first use (`VulkanGL20.nativeTexture`), once the mip chain is known; level > 0 uploads; `glGenerateMipmap` by GPU blit chain
- [x] Ordered state packets: `glClear` (colour, depth, stencil), `glClearColor`, `glViewport`, `glScissor`, `glEnable`/`glDisable` (scissor, depth, stencil, cull face), `glDepthFunc`/`Mask`, `glStencil*`, `glCullFace`, `glBindFramebuffer`: all stream packets, replayed in order with the draws
- [ ] Buffers: `glGenBuffer`, `glBindBuffer`, `glBufferData`, `glBufferSubData`, `glDeleteBuffer`
- [x] Framebuffers: `glGenFramebuffer`, `glFramebufferTexture2D`, renderbuffers, `glCheckFramebufferStatus`, deletes (Phase 3)
- [x] Query table (in `VulkanGL20`, not a separate `VulkanGL20Queries`): extension strings, `GL_MAX_TEXTURE_SIZE`, anisotropy, `GL_VIEWPORT` (last `glViewport`), `GL_FRAMEBUFFER_BINDING` (0), `glGetString(GL_VERSION)`; unknown enums log once and return 0
- [~] `Gdx.gl30` always null: true in every test harness (`TestGdx`, the device suite); a real `Application` that installs `Gdx.gl*` arrives with `VulkanApplication` (Phase 4/5)
- [~] Whole interface covered: all 162 `GL20` methods exist (generated from libGDX's `GL20.java` by `tools/generate_gl20_base.py`); drawing, programs and vertex state throw a message naming the call and pointing at the Vulkan batches. Buffer calls (`glGenBuffer`, `glBindBuffer`, `glBufferData`, ...) still throw: they arrive with stock `Mesh` support in Phase 5. Textures, framebuffers and ordered state are implemented

### 4. Copy-on-write renaming
- [x] Sampler change: new slot/set, old one retired (soak-tested ~1,000/s, bounded sampler count)
- [~] Rename trigger: every pixel change after the image exists renames it (conservative); the "referenced by a recorded draw since the last submit" optimisation is not done, so a change before first use is applied in place on the CPU copy and costs nothing
- [x] Full-upload rename: same-shape `glTexImage2D` reload renames; a different size or format redefines the texture, as GL does
- [x] Sub-image upload rename (`vkCmdCopyImage` from the old image, then the region); old image restored to read layout for draws recorded earlier in the frame
- [~] Pending uploads queued per image/buffer request (not per GL handle); post-rename routing not applicable until renaming exists
- [x] Renames-per-frame counter: `Resources::renameCount` (cheap atomic), `GdxVkFrame.getRenamesLastFrame()` for a debug overlay to show, and `Stats.textureRenames`. Tested (`SpriteBatchTest.texturesRenamedDuringAFrameAreCounted`: creating an image is not a rename, two pixel changes are two)

### 5. Stock Mesh access (`StockMeshAccess`)
- [ ] Reflected `Field`s for `bufferHandle` and `isDirty` on `VertexBufferObject`, `VertexBufferObjectSubData`, `IndexBufferObject`
- [ ] Dirty meshes uploaded at draw time (with copy-on-write); `Mesh.dispose()` releases the native buffer
- [ ] `VertexDataType.VertexArray` meshes copied into the per-frame ring each draw
- [ ] Unit tests pinning the field names/types per supported libGDX version
- [ ] Upstream PR: public `getBufferHandle()` and dirty/version accessor (filed in Phase 5)

### 6. Coordinate, winding, depth and colour conventions
- [x] Swapchain: negative-height viewport, `COUNTER_CLOCKWISE` front face (pixel-tested upright + culling, both tiers)
- [x] Offscreen FBO: positive-height viewport, `CLOCKWISE` front face (`FrameBufferTest`, `Gate3GoldenTest`; the cull test checks the winding against GL's on both target kinds)
- [x] `glViewport`/`glScissor` y mapping (`vkY = targetH - (glY + glH)` swapchain, `vkY = glY` offscreen), re-derived on every target switch
- [x] Tier A: front face is dynamic state; Tier B: baked in the pipeline
- [x] Depth remapped `[-1,1] -> [0,1]` in the vertex shader via `gdx_vk_common.glsl`, no extension branch
- [x] Swapchain formats `B8G8R8A8_UNORM` / `R8G8B8A8_UNORM`, never sRGB

### 7. Hardware tiers
- [x] Tier A: dynamic rendering
- [x] Tier B: `VkRenderPass`; swapchain clear/load passes plus an offscreen cache keyed by colour and depth format and the clear flags (all variants share identical subpass dependencies, which the validation layer requires for compatibility)
- [x] Dynamic raster state on Tier A: cull mode, front face, depth test/write/compare, stencil test and ops; Tier B bakes them into pipeline variants (stencil reference and masks dynamic on both)
- [x] Blend as pipeline variants (`SpritePipelines`: one pipeline per blend state, built on first use)
- [x] Tier A bindless table with no flush on texture switch (the batch side is Phase 2); Tier B per-texture sets
- [x] Tier B flush on texture switch (`SpriteBatchTest.textureChangesFlushOnlyOnTierB`: four textures take four draw calls on Tier B, one on Tier A)
- [x] `forceTierB` runs Tier B anywhere (`GdxVkConfig.forceTierB`; the plan calls it `VulkanApplicationConfiguration.forceTierB`)
- [x] CI runs every test in both tiers (`ci/run-checks.sh`; first executed on GitHub on 2026-11-03: workflow run 41735391607 for commit 61b156d58 passed in 2m24s)
- [~] Device tests: RTX 5080 and RTX 2080 both tiers; Android phone (Adreno 840) both tiers, Phase 1a self-test and the Phase 1b/2 device suite; **no Galaxy S20**

### 8. Test oracle
- [x] Compatibility reference = stock GL libGDX on llvmpipe (`GlReferenceMain`, `OracleImages`; on CI the GL side is llvmpipe, on a workstation the installed driver)
- [x] GL-vs-Vulkan image comparison with per-channel threshold (16) plus a maximum differing share (0.5%); Gate 2 and Gate 3 scenes
- [x] Regression reference = committed lavapipe goldens, compared exactly on lavapipe (4 scenes)
- [x] Goldens regenerated only on a Mesa pin change (`ci/mesa-version.txt` guard, `UPDATE_GOLDENS` flow)
- [x] Validation layer with sync validation on every test; any message fails (soak run in fence mode uses core validation only, documented)

---

## Phase 1a: Extraction, window surfaces and CI

### 1. Standalone build
- [x] Modules `gdx-vulkan-core` (Java + `jni/`), `gdx-vulkan-desktop`, `gdx-vulkan-android`
- [~] Natives via plain CMake + hand-written JNI instead of gdx-jnigen (deliberate; revisit when jnigen cross-compile is verified)
- [x] Natives built: Windows x64, Linux x64 (Docker), Android arm64-v8a
- [~] Natives: Android armeabi-v7a and x86_64 compile and link with the NDK (the library builds all three ABIs; fixed a 32-bit assumption in `VmaVirtualAllocation`); neither has been run, as the only device is arm64. Linux arm64 not built
- [x] No copied audio classes; `Gdx.gl30` untouched from the first commit

### 2. Native core boot
- [x] volk + `vk_mem_alloc.h` vendored, `VK_NO_PROTOTYPES`, no Vulkan SDK link libraries
- [x] Instance and device creation with tier detection and `forceTierB`
- [x] UNORM swapchain, negative-height viewport, begin/end rendering with the render-pass fallback on Tier B
- [x] `VK_EXT_debug_utils`: enabled whenever the instance has it (the validation messenger only with the layer). Pipelines, textures, depth images, buffer pools and frame command buffers have names; render passes, the staged-upload batch and the compute section are labelled regions, so a RenderDoc or AGI capture reads. No per-batch-flush labels
- [x] Typed Java exceptions: device lost, surface lost, out of memory (`OUT_OF_DATE` is handled inside the presenter, not thrown)

### 3. Surfaces from native handles
- [x] Desktop: Win32 and X11 handles from GLFW (via `GlfwSurfaces`); [ ] Wayland
- [~] `GdxVkContext.createPresenter()` (the plan names it `GdxVk.createSurface()`)
- [x] Android: `ANativeWindow_fromSurface()` from a minimal `SurfaceView` host

### 4. CI
- [x] GitHub Actions workflow with version-pinned Mesa image: runs on every push touching `gdx-vulkan/**` (first green run 2026-11-03; a Maven Central or wrapper-validation network flake needs a re-run, not a code change)
- [x] llvmpipe (OpenGL) side of the Mesa image is the Gate 2 / Gate 3 GL oracle
- [x] Every test in Tier A and `forceTierB` with validation and sync validation (lavapipe, Docker)
- [x] Golden harness: exact lavapipe goldens, plus the GL-vs-Vulkan tolerance comparison
- [x] Windows (RTX) runs, manual
- [~] Android run, manual: REDMAGIC NX809J, not the Galaxy S20

### Gate 1a
- [x] Clear screen and triangle match goldens in both tiers on lavapipe (Docker run of the CI script)
- [x] Render correctly on the RTX 2080 (real device index 1, goldens within tolerance, both tiers; earlier claims about the 2080 were invalid, see CLAUDE.md)
- [~] Render correctly on the "Galaxy S20": passed on a REDMAGIC NX809J instead

---

## Phase 1b: Memory, layout and caches

### 1. Two-set pipeline layout
- [x] Exactly as Core decision 2, including the Tier A feature check and the table-size clamp

### 2. Deferred deletion
- [x] `uint64` serial incremented on every `vkQueueSubmit`
- [x] Completion by timeline semaphore (1.2+) or a fence per serial (1.1; `forceTierB` selects fences)
- [x] Retired `VkBuffer` ranges, `VkImage`, `VkImageView` and Set 1 slots/sets freed once `completed >= retirement serial`
- [~] Retired `VkFramebuffer`: offscreen framebuffers go through the deletion queue; swapchain framebuffers are still destroyed after `vkDeviceWaitIdle` at resize. Render passes live as long as the presenter
- [x] Retirement thread-safe: `dispose()` from `create()`, between frames and from loader threads (soak: 4 worker threads)
- [x] Retirement across multiple windows: `MultiWindowTest` (two windows, two presenters, one context): textures drawn in one window and disposed before the other draws, and a rename while both windows have frames in flight; validation clean in both tiers

### 3. Allocators
- [x] Per-frame dynamic ring for the Set 0 UBO and SSBO, persistently mapped, aligned to the device minimums, grows when exceeded
- [x] Mutex-guarded staging ring; the render thread records every queued copy into an upload command buffer at `endFrame`, submitted ahead of the frame command buffer

### 4. Caches
- [x] `VkSampler` cache keyed by (filters, wrap, anisotropy), bounded (4 samplers over ~1,000 changes/s)
- [x] `VkPipelineCache` saved to disk, header-validated against device/driver on load
- [~] Pre-warmed at startup: the cache is loaded; no list of pipelines is rebuilt eagerly

### 5. Shader toolchain
- [x] Gradle task runs `glslc` over sources into `.spv` resources (Tier B and Tier A `-DGDX_TIER_A` variants)
- [x] `gdx_vk_common.glsl` carries the depth remap; `gdx_vk_sets.glsl` carries the set declarations
- [x] SPIRV-Reflect validation at load

### Stress test 1b and pass criteria
- [x] 50,000 buffer sub-allocation cycles/s and 2,000 small textures/s at 60 fps, with background-thread disposals and repeated filter changes (15-60 s runs, both GPUs, both tiers)
- [x] 10-minute run, RTX 5080, Tier A, strict (>=90% of target rates, >=55 fps): 36,000 frames at 60.0 fps, 50,000 buffer cycles/s, 2,000 textures/s, ~1,000 sampler changes/s, 33 M retirements, VMA flat at 176 MB from minute 1, 4 samplers, zero validation messages, clean shutdown
- [x] 10-minute run, RTX 2080 (device index 1), Tier A, same strict criteria and the same flat result (36,001 frames, 60.0 fps)
- [x] 10-minute run in Tier B (fence completion path), RTX 5080: 36,001 frames at 60.0 fps, 50,000 buffer cycles/s, 2,000 textures/s, 998 sampler changes/s, 33 M retirements, VMA flat at 208 MB from the first minute, 4 samplers, no validation messages (core validation only: sync validation is off in fence mode, documented)
- [x] Android device run: Adreno 840, both tiers, `DeviceSuite` (21 checks: sprite batches, shader bundle, ETC2/ASTC, instanced sprites, framebuffers with depth, stencil and multiple float attachments, the Gate 3 scenes, Stress test 3) passes. 10-minute soaks: Tier A 36,016 frames at 60 fps with the native heap flat (24,939 -> 25,000 KB); Tier B 35,984 frames at 59 fps, native heap 26,694 -> 26,798 KB; no validation messages. The Galaxy S20 the plan names is not available (this is the REDMAGIC)

---

## Phase 2: Unmodified 2D

### 1. Texture and state shim
- [x] Uploads recorded ahead of the frame's draws in the same submit, whenever they were queued during the frame (separate upload command buffer recorded at `endFrame`)
- [x] Texture calls with lazy image creation, level > 0 uploads, copy-on-write (15 shim tests drive unmodified stock `Texture` and `Pixmap`; both tiers; RTX 5080/2080; lavapipe)
- [~] Format conversion: `RGB888 -> RGBA8888`, `Alpha -> R8_UNORM` swizzle `(0,0,0,R)`, `LuminanceAlpha -> R8G8_UNORM` swizzle `(R,R,R,G)` done; `RGBA4444` and `RGB565` are expanded to RGBA8 (the plan's fallback), packed Vulkan formats where supported are not used yet; `GL_UNPACK_ALIGNMENT` honoured
- [x] Compressed uploads (`glCompressedTexImage2D`/`SubImage2D`): DXT1/3/5 -> BC1/2/3, ETC1/ETC2, ASTC 4x4..12x12, picked by what the device samples; an unsupported format fails with a message naming it. Verified with hand-built BC1 blocks incl. a block-aligned rename update (RTX 5080); ETC2 and ASTC 4x4 verified on the Adreno 840 with hand-built blocks (ETC2 differential block, ASTC void-extent block); the Adreno also samples BC1
- [x] Ordered state packets for clear, viewport, scissor, enable/disable: recorded into the command stream so they interleave with batch draws; replayed across frames
- [x] The shim query table (see Core decision 3)
- [x] `VulkanStage`, `VulkanScrollPane`, `Vulkan*Viewport`, `VulkanTextureLoader`, `VulkanPixmapPacker` not carried over (fresh project; stock classes are the goal)

### 2. Command stream
- [x] Java encodes packets (`int[]` scratch copied in bulk into a direct `ByteBuffer`): pipelines, push constants (whole block or a range), viewport, scissor, clear, vertex/index/storage binds, textures, draws, `BindTarget`, depth/stencil/cull state; pass begin/end are driven by `BindTarget` and the first draw or clear
- [x] One `nativeSubmitCommands` call per frame; one queue submit per frame (uploads ahead of it in the same submit)
- [x] The batches and renderers all record through the stream (the direct presenter calls remain for the low-level tests)

### 3. Batches
- [x] `VulkanSpriteBatch` (implements `Batch`); Tier A slot index per vertex, Tier B flush on texture switch. Draw code is generated from stock's `SpriteBatch` (`tools/generate_spritebatch_draws.py`). A batch that uses one texture sends stock's 20-byte vertices in one block copy with the slot in the push constants (`sprite_single.vert`)
- [x] `VulkanPolygonSpriteBatch` (implements `PolygonBatch`), generated from stock's `PolygonSpriteBatch`
- [x] `VulkanShapeRenderer` (generated from stock's `ShapeRenderer`) and `VulkanImmediateModeRenderer`; points and lines are 1 px (wide lines are an optional Vulkan feature)
- [~] `VulkanShaderPipelineBundle` replacing custom `ShaderProgram` GLSL: bundles of precompiled SPIR-V (both tiers) with `u_custom0/1` push values, set with `setShaderBundle` on `VulkanSpriteBatch` and `VulkanPolygonSpriteBatch`; tested with the example `sprite_gray.frag`. No GLSL-to-SPIR-V at run time, and no bundle support in the shape renderer yet
- [x] Per-frame vertex ring (host-cached system memory, per-frame blocks that grow)

### Gate 2 (stock tests, both tiers, compared to stock GL on llvmpipe)
- [x] Scene2D `Table`, `Skin`, clipped `ScrollPane` through `ScissorStack` (scenes `gate2_scene2d`, `gate2_window`)
- [x] `BitmapFont`
- [x] `FreeTypeFontGenerator` with glyphs added mid-frame (incremental glyphs while the batch is open)
- [x] `TextureAtlas`
- [x] `ParticleEffect`
- [x] Compared against stock GL: oracle diffs 0 px for the Batch scenes (particles within 13/255 per channel), shapes 0.18% of pixels; exact goldens on lavapipe, both tiers (CI)

### Stress test 2 (vs stock `SpriteBatch` on `gdx-backend-lwjgl3`)
- [x] `VulkanSpriteBatch` Tier A, 128 textures: >= 3x stock fps. RTX 5080, 20,000 sprites/frame, 800x600, vsync off: 17.5 fps stock GL, 413 fps (23.6x). (`gradlew :gdx-vulkan-desktop:stress2`)
- [x] `VulkanSpriteBatch` Tier A, 1 texture: >= 1.0x. RTX 5080: 1.23-1.26x stock GL (1,306-1,312 fps against 1,042-1,064); it was 0.91x. Cause found with `GDXVK_PROFILE=1` and sprite-size, present-mode, image-count and frames-in-flight sweeps: the GPU waited on vertex fetches across PCIe from the cached system-memory vertex ring (the CPU wait for a frame slot was 0.28 ms; empty frames were already faster than GL). The ring now has a device-local mirror, copied at the start of the frame, on discrete GPUs (not on unified-memory devices such as phones); the wait is 0.07 ms
- [x] `VulkanSpriteBatch` Tier B (fence path): 128 textures 61-70 fps vs 17.5 (3.5-4.0x), 1 texture 1.22x. RTX 2080 not compared: its Vulkan run is capped at its 120 Hz presentation while the GL run uses the display GPU
- [x] `VulkanSpriteBatchInstanced`, 1,000,000 sprites moved by compute: 60+ fps on RTX 2080. **722 fps** (Tier A) and 726 fps (Tier B) on the RTX 2080 at 1280x720, vsync off; 1,879 fps on the RTX 5080; 208-225 fps on the Adreno 840 at 1216x2688 (`gradlew :gdx-vulkan-desktop:stressInstanced -Dgdxvulkan.device=1`). 5 tests (`InstancedBatchTest`: pixel-exact against a CPU simulation of the same bouncing, upload-then-dispatch ordering in one frame, gravity, 1,000,000 sprites) pass on both GPUs, both tiers, lavapipe, and the Adreno (device suite)

---

## Phase 3: Stock FrameBuffer

### 1. Framebuffer shim
- [x] `glBindFramebuffer` emits `BindTarget` into the stream; the native side ends the open pass, moves the image between SHADER_READ_ONLY and COLOR_ATTACHMENT with barriers, and starts the next pass lazily at the first draw or clear (a full clear that starts a pass becomes its load op). Render targets are never renamed. Colour attachment 0, RGBA8 only (depth and stencil next). `FrameBufferTest`: 7 tests on stock `FrameBuffer` (GL orientation, ordering with the swapchain pass, loading across frames, ping-pong, scissor and viewport) pass on both GPUs, both tiers, lavapipe
- [x] Offscreen passes use the positive-height viewport and clockwise front face (Tier A dynamic, Tier B pipeline variants per target kind)

### 2. Depth and stencil
- [x] Packed depth-stencil reported supported; separate depth and stencil renderbuffers on one framebuffer are folded into one combined image (`depthImageOf` in the shim). Depth and stencil state (`glEnable`/`glDepthFunc`/`glDepthMask`/`glClearDepthf`/`glStencil*`/`glClearStencil`) are ordered stream packets: dynamic state on Tier A, pipeline variants on Tier B. 5 depth/stencil tests in `FrameBufferTest` (12 in all) pass on both GPUs, both tiers, lavapipe; the Adreno 840 device suite (18 checks) passes both tiers. The swapchain itself has no depth buffer yet (Phase 5), so depth state is ignored there
- [x] Format by device support: `D24_UNORM_S8_UINT`, else `D32_SFLOAT_S8_UINT`; `DEPTH_COMPONENT16 -> D16_UNORM` (24/32-bit depth without stencil is `D32_SFLOAT`)
- [x] Renderbuffers: by default ordinary device-local depth images that are loaded and stored (GL keeps a framebuffer's depth between its passes). The plan's transient, lazily allocated, `STORE_OP_DONT_CARE` depth is an explicit opt-in per framebuffer, `VulkanGL20.setFramebufferDepthTransient(handle, true)`: every pass must clear the depth, and nothing is kept. Tested on both GPUs, both tiers; lazily allocated memory only exists on tile-based GPUs

### 3. Pass scheduling
- [~] Offscreen passes before the swapchain pass: the frame's swapchain pass now begins at the first swapchain draw or clear, not at `beginFrame`, so framebuffer passes that come first (post-processing chains, shadow maps) never suspend and resume it; `lastPassCounts()` proves it (4 framebuffer passes then the screen = 1 swapchain pass; a screen draw between them = 2). Offscreen passes that come after a swapchain draw are not reordered ahead of it
- [x] Explicit load/store ops: a full clear that starts a pass is its CLEAR load op, a pass otherwise LOADs, and a depth image with nothing in it yet or a transient one uses DONT_CARE; transient depth also stores nothing (`FrameBufferTest`)

### 4. Extras
- [x] `VulkanMultiTargetFrameBuffer` (1-8 colour attachments of RGBA8888, RGBA16F or RGBA32F, optional depth/stencil; built from stock GL calls, `Gdx.gl30` stays null): a pipeline writes as many outputs as its target has attachments (a mismatch is refused with a message that says so), a clear reaches every attachment, and floating-point attachments keep values above 1.0. `FrameBufferTest` on both GPUs, both tiers, lavapipe; the Adreno 840 device suite. A 32-bit float attachment that the device cannot filter is sampled nearest. Images need no zero upload
- [x] Tier B render-pass cache keyed by colour and depth format and the clear flags (samples are always 1); pipelines are built per target variant and cached on their record

### Gate 3 and stress test 3
- [x] `gate3_2d3d`: a 2D scene (opaque, translucent and rotated sprites, text) and a 3D scene (perspective camera, two interpenetrating filled boxes and a floor under the depth test) each in a stock `FrameBuffer`, drawn with `region.flip(false, true)`. Matches stock GL exactly (0 differing pixels) on lavapipe vs llvmpipe in both tiers, and on the RTX 5080 and RTX 2080 vs GL on the RTX 5080 (`Gate3GoldenTest`, `Gate3Scenes`, `GlReferenceMain`)
- [x] `gate3_postfx`: a clipped Scene2D UI (ScrollPane through ScissorStack) into a `FrameBuffer`, then four passes through ping-pong framebuffers (bright pass, horizontal blur, vertical blur, CRT scanlines and vignette with additive bloom). The post shaders are GLSL on stock GL and SPIR-V bundles (`fx_*.frag`) on gdx-vulkan. Exact match with stock GL on lavapipe CI (both tiers) and on the RTX 2080 (and 5080). On the Adreno 840 phone (the REDMAGIC, not the Galaxy S20) both Gate 3 scenes run in the device suite, with the skin loaded from the APK and the scene source shared with the desktop tests: within 5/255 of the lavapipe golden, no pixel beyond the tolerance, both tiers
- [x] Stress test 3: 100,000 draws (a cull-mode change, a full 128-byte push-constant block, an indexed draw) recorded as a plain C++ loop straight into the command buffer (`Presenter::benchmarkRecord`, the floor) against the same draws encoded by Java and replayed through the command stream (`gradlew :gdx-vulkan-desktop:stress3`, `Stress3Main`)
- [x] Pass: Java + JNI + C++ recording time <= 1.3x the C++ floor. Desktop, medians over 100-200 frames: RTX 5080 Tier A 1.22-1.26x, Tier B 1.04-1.07x; RTX 2080 Tier A 1.24-1.25x, Tier B 1.01-1.10x. The Java encoder costs ~15 ns per draw on HotSpot. Adreno 840 (informational, the floor swings 385-554 ns between runs): Tier B 1.21-1.26x, Tier A 1.24-1.80x, so Tier A on the phone is not reliably under 1.3x. Java encoding there is ~105 ns per draw on ART (it was 730 ns with buffer views: the stream now encodes into an int[] and copies in bulk)

---

## Phase 4: Android lifecycle

### 1. `VulkanAndroidApplication`
- [ ] Reuses `AndroidInput`, `AndroidAudio`, `AndroidFiles`; own `SurfaceView` implementing `SurfaceHolder.Callback2`
- [~] `surfaceDestroyed` destroys only swapchain + surface, device/VMA/resources stay resident (proven in the Phase 1a test app, HOME/return x3)
- [~] `surfaceCreated`/`surfaceChanged` recreate surface and swapchain (same test app; not the real application class)

### 2. Pre-rotation
- [ ] `preTransform = currentTransform`; backend rotates in clip space through the frame UBO after the user's projection
- [ ] `Gdx.graphics.getWidth/Height`, `glViewport`, `glScissor` stay logical; shim rotates the rectangles

### 3. Frame pacing and memory
- [ ] `VK_KHR_swapchain_maintenance1` where available
- [ ] Android Frame Pacing (Swappy)
- [ ] `onTrimMemory()` releases idle staging memory

### 4. Async loading
- [ ] `VulkanAsyncTextureLoader` for `AssetManager` (worker decode into staging-ring slices)

### Gate 4 and stress test 4
- [ ] All Phase 1-3 tests on a physical device with pre-rotation active, confirmed in Android GPU Inspector
- [ ] ADB script: 500 rotations / background-foreground / split-screen cycles while streaming textures
- [ ] Pass: zero crashes, zero lost textures, flat native heap, swapchain recreation under 50 ms

---

## Phase 5: 3D with stock models

### 1. Mesh data
- [ ] Buffer shim calls with copy-on-write
- [ ] `StockMeshAccess` uploads dirty stock meshes at draw time
- [ ] File the upstream libGDX PR (public buffer handle + dirty accessor)

### 2. `VulkanModelBatch`
- [ ] Accepts stock `RenderableProvider`s; `VulkanShaderProvider` maps `VertexAttributes` + `Material` to cached `VulkanShaderPipelineBundle`s
- [ ] Lights, materials, bone matrices in the Set 0 SSBO
- [ ] Sorted by pipeline, mesh, material; repeated meshes instanced or `vkCmdDrawIndexedIndirect`

### 3. Lighting and shadows
- [ ] Tiled/clustered point and spot light culling in compute
- [ ] Directional shadow cascades; up to 4 point-light cubemap shadow casters (desktop), 1 cascade (mobile)

### 4. Compute
- [ ] `VulkanComputeShader` with automatic barriers `COMPUTE_SHADER -> DRAW_INDIRECT | VERTEX_ATTRIBUTE_INPUT` (groundwork exists from `VulkanSpriteBatchInstanced`: compute pipelines on the shared layout, dispatches queued per frame and recorded ahead of the render pass, a buffer bound as Set 0 storage for compute and vertex shaders; no indirect draws or public API yet)

### Gate 5 and stress test 5
- [ ] Animated `knight.g3db` via unmodified `G3dModelLoader` + a `ModelBuilder` scene: normal mapping, skeletal animation, directional shadows, vs stock GL within tolerance (own lavapipe golden for shadows)
- [ ] 250,000 instanced textured meshes moved by compute boids, 500 point lights culled in compute
- [ ] Pass: 60+ fps RTX 2080; 50,000 instances and 64 lights at 60 fps on the Galaxy S20; zero Java allocations per frame

---

## Phase 6 (stretch): macOS and iOS

- [ ] Starts only after desktop and Android pass every gate above
- [ ] macOS: `CAMetalLayer` from `GLFWNativeCocoa` into `vkCreateMetalSurfaceEXT`, `VK_KHR_portability_enumeration` / `_subset` (instance and device extension names already handled in the context)
- [ ] iOS: `UIView` backed by `CAMetalLayer` through RoboVM/MobiVM; FBO shim answers `GL_FRAMEBUFFER_BINDING`
- [ ] Drivers: MoltenVK, and Mesa KosmicKrisp as the higher-tier option

---

## Cross-cutting and housekeeping

- [x] Work committed on branch `gdx-vulkan-phase1` (Phase 1a `66def8e73`, Phase 1b `6405ae0da`); not pushed
- [ ] CI workflow executed at least once on GitHub; Mesa pin and goldens confirmed there
- [ ] Bump `gdxVersion` to 1.14.3 when published
- [ ] Windows/Android manual-run procedure documented as a release checklist
- [ ] Debug overlay (renames per frame, retired-queue depth, sampler count, staging blocks) built on `GdxVkResources.Stats`
