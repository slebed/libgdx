# libGDX Vulkan Backend Plan v5.0: implementation checklist

Source: `docs/libGDX Vulkan Backend Plan v5.0.md`. Marks reflect what has actually been built **and verified**, not what is merely written.

- `[x]` done and verified   - `[~]` partial, or done differently from the plan (the note says how)   - `[ ]` not started

"Verified" means: passes `gradlew check -Dgdxvulkan.requireDriver=true` (validation + sync validation clean) on the RTX 5080 and RTX 2080 in both
tiers, and `ci/run-checks.sh` on lavapipe in Docker. Android items were verified on a REDMAGIC NX809J (Adreno 840), which is **not** the Galaxy S20
the plan names.

## Summary

| Section | Done | Partial | Not started | Total |
|---|---|---|---|---|
| Core decisions | 54 | 2 | 0 | 56 |
| Phase 1a: Extraction, window surfaces and CI | 21 | 1 | 0 | 22 |
| Phase 1b: Memory, layout and caches | 20 | 0 | 0 | 20 |
| Phase 2: Unmodified 2D | 25 | 0 | 0 | 25 |
| Phase 3: Stock FrameBuffer | 13 | 0 | 0 | 13 |
| Phase 4: Android lifecycle | 12 | 1 | 0 | 13 |
| Phase 5: 3D with stock models | 10 | 3 | 0 | 13 |
| Phase 5b: Stock GLSL shaders (runtime translation to Vulkan) | 28 | 9 | 9 | 46 |
| Releases and adoption testing | 0 | 0 | 12 | 12 |
| Demos (gdx-vulkan-demos) | 7 | 3 | 2 | 12 |
| Phase 6 (stretch, tabled: needs a Mac): macOS and iOS | 0 | 0 | 4 | 4 |
| Cross-cutting and housekeeping | 4 | 0 | 1 | 5 |
| **All** | **195** | **19** | **28** | **242** |
---

## Core decisions

### 1. Standalone library, no fork
- [x] Depends on upstream `com.badlogicgames.gdx:gdx` (1.14.2: 1.14.3 is not on Maven Central; bump when it ships)
- [x] Core `gdx` is never patched
- [x] Reuses `gdx-backend-lwjgl3` for files, networking, preferences, clipboard, OpenAL audio, GLFW and natives loading; `VulkanDesktopApplication` is the desktop `Application`. Input is the stock key mapping, event queue and polling, **as a generated copy** (`tools/generate_desktop_input.py` turns libGDX's `DefaultLwjgl3Input` into `VulkanDesktopInput`): the stock class needs an `Lwjgl3Window`, whose constructor is package-private and whose graphics object creates an OpenGL context, so it could not be used as it is. `DesktopApplicationTest` runs an application end to end (window, input events, posted runnables, resize, a captured frame, clean shutdown with no validation messages)
- [x] The on-screen keyboard: `VulkanSurfaceView` offers the keyboard an input connection and `VulkanAndroidInput` shows and hides it (stock input casts the view to a GL class); `tools/keyboard-test.sh` on the Galaxy S20 FE (Android 13) and the REDMAGIC (Android 16): the system reports it shown and hidden, a key event arrives as libGDX `keyDown` and `keyTyped`
- [x] Reuses `gdx-backend-android` for input, audio, files and net: `VulkanAndroidApplication` builds `DefaultAndroidInput`, `DefaultAndroidAudio`, `DefaultAndroidFiles`, `AndroidNet`. Known gap: the on-screen keyboard and native text input (`DefaultAndroidInput` casts the graphics object to its own GL class there)
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
- [x] Set 1 Tier B alternative: push descriptor: **decided against**. Tier B keeps one descriptor set per texture; all measurements (including the Adreno 840 Tier B numbers) are good enough that a third path would only add test surface
- [x] Tier A table size = min(4096, the update-after-bind sampled-image and sampler limits, per stage and per set) (`gdxvk_tier.h`, unit-tested)
- [x] Tier A requires all of the listed features, else Tier B (core Vulkan 1.3 supplies dynamic rendering and extended dynamic state)
- [x] SPIRV-Reflect validates every declared `(set, binding, type)` at shader load and fails naming the shader

### 3. GL20 shim scope (`VulkanGL20`)
- [x] `glGenTexture`, `glBindTexture`, `glActiveTexture`, `glTexImage2D`, `glTexSubImage2D`, `glTexParameteri/f`, `glGenerateMipmap`, `glDeleteTexture`, `glPixelStorei`, `glCompressedTexImage2D`/`SubImage2D` done and tested with stock `Texture`/`Pixmap`; only `GL_TEXTURE_2D` (no cube maps)
- [x] Lazy `VkImage` creation at first use (`VulkanGL20.nativeTexture`), once the mip chain is known; level > 0 uploads; `glGenerateMipmap` by GPU blit chain
- [x] Ordered state packets: `glClear` (colour, depth, stencil), `glClearColor`, `glViewport`, `glScissor`, `glEnable`/`glDisable` (scissor, depth, stencil, cull face), `glDepthFunc`/`Mask`, `glStencil*`, `glCullFace`, `glBindFramebuffer`: all stream packets, replayed in order with the draws
- [x] Buffers: `glGenBuffer`, `glBindBuffer`, `glBufferData`, `glBufferSubData`, `glDeleteBuffer(s)`: GL name to a native buffer, created on the first data; a whole-buffer redefinition after the buffer was drawn this frame gets a new native buffer (the old one is retired), `glBufferSubData` writes in place (`StockMeshTest`)
- [x] Framebuffers: `glGenFramebuffer`, `glFramebufferTexture2D`, renderbuffers, `glCheckFramebufferStatus`, deletes (Phase 3)
- [x] Query table (in `VulkanGL20`, not a separate `VulkanGL20Queries`): extension strings, `GL_MAX_TEXTURE_SIZE`, anisotropy, `GL_VIEWPORT` (last `glViewport`), `GL_FRAMEBUFFER_BINDING` (0), `glGetString(GL_VERSION)`; unknown enums log once and return 0
- [x] `Gdx.gl30` always null: true in every test harness and in `VulkanAndroidApplication` (which installs `Gdx.gl`, `Gdx.gl20` and leaves `Gdx.gl30` null)
- [x] Whole interface covered: all 162 `GL20` methods exist (generated from libGDX's `GL20.java` by `tools/generate_gl20_base.py`); buffer calls (`glGenBuffer`, `glBindBuffer`, `glBufferData`, `glBufferSubData`, ...) are implemented since Phase 5 for stock `Mesh` data; drawing, programs and vertex state throw a message naming the call and pointing at the Vulkan batches (by design: there is no GLSL to run). Textures, framebuffers and ordered state are implemented

### 4. Copy-on-write renaming
- [x] Sampler change: new slot/set, old one retired (soak-tested ~1,000/s, bounded sampler count)
- [x] Rename trigger: every pixel change after the image exists renames it, which is always correct. The "referenced by a recorded draw since the last submit" refinement is **not adopted**: a change before first use already lands in place on the CPU copy for free, and tracking per-draw references would put a hash lookup in the hot path for a saving nobody has measured
- [x] Full-upload rename: same-shape `glTexImage2D` reload renames; a different size or format redefines the texture, as GL does
- [x] Sub-image upload rename (`vkCmdCopyImage` from the old image, then the region); old image restored to read layout for draws recorded earlier in the frame
- [x] Pending uploads queued per image/buffer request (not per GL handle), which makes post-rename routing unnecessary: a request names the native image it was recorded for, and a rename creates a new one
- [x] Renames-per-frame counter: `Resources::renameCount` (cheap atomic), `GdxVkFrame.getRenamesLastFrame()` for a debug overlay to show, and `Stats.textureRenames`. Tested (`SpriteBatchTest.texturesRenamedDuringAFrameAreCounted`: creating an image is not a rename, two pixel changes are two)

### 5. Stock Mesh access (`StockMeshAccess`)
- [x] Reflected `Field`s for `bufferHandle`, `isDirty` and `byteBuffer` on `VertexBufferObject`, `VertexBufferObjectSubData`, `IndexBufferObject`, `IndexBufferObjectSubData`, and `Mesh.vertices`/`indices` (`StockMeshAccess`). The data size is the typed buffer's limit, not the byte buffer's: loaders fill the buffers through `getBuffer(true)` and stock only applies the limit inside `bind(ShaderProgram)`
- [x] Dirty meshes uploaded at draw time (a new native buffer when the old one was already drawn this frame); `Mesh.dispose()` releases the native buffers through `glDeleteBuffer` (`StockMeshTest`)
- [x] `VertexDataType.VertexArray` meshes (and index arrays) copied into the per-frame ring on each draw (`StockMeshTest.vertexArrayMeshesAreCopiedThroughTheRingEachDraw`)
- [x] Unit tests pinning the field names/types (`StockMeshFieldsTest`, no GPU needed); `StockMeshAccess` also checks them when it is constructed. Per libGDX version: only 1.14.2 exists to pin
- [~] Upstream PR: public `getBufferHandle()` and dirty accessor: patch written and checked (`docs/upstream/`), **not filed** (the owner's decision; until it ships gdx-vulkan reads the fields through JNI field ids)

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
- [~] Device tests: RTX 5080 and RTX 2080 both tiers; Android phones (Adreno 840 both tiers; Adreno 650 compatibility path) with the Phase 1a self-test and the device suite

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
- [x] Natives via plain CMake + hand-written JNI instead of gdx-jnigen: a decision, recorded in `docs/PLAN.md` and `CLAUDE.md`. The build is one CMake file per platform with no generator step, which is what lets CI build Linux arm64 and the NDK ABIs with a single command. Revisit only if a consumer needs jnigen's packaging
- [x] Natives built: Windows x64, Linux x64 (Docker), Android arm64-v8a
- [~] Natives: Android armeabi-v7a and x86_64 compile and link with the NDK (the library builds all three ABIs; fixed a 32-bit assumption in `VmaVirtualAllocation`); neither has been **run**: the only device is arm64 and running them needs an emulator or a second phone. Linux arm64: built and its native tests run by the `arm64-natives` CI job on GitHub's arm64 runner (the Java suite does not run there)
- [x] No copied audio classes; `Gdx.gl30` untouched from the first commit

### 2. Native core boot
- [x] volk + `vk_mem_alloc.h` vendored, `VK_NO_PROTOTYPES`, no Vulkan SDK link libraries
- [x] Instance and device creation with tier detection and `forceTierB`
- [x] UNORM swapchain, negative-height viewport, begin/end rendering with the render-pass fallback on Tier B
- [x] `VK_EXT_debug_utils`: enabled whenever the instance has it (the validation messenger only with the layer). Pipelines, textures, depth images, buffer pools and frame command buffers have names; render passes, the staged-upload batch and the compute section are labelled regions, so a RenderDoc or AGI capture reads. No per-batch-flush labels
- [x] Typed Java exceptions: device lost, surface lost, out of memory (`OUT_OF_DATE` is handled inside the presenter, not thrown)

### 3. Surfaces from native handles
- [x] Desktop: Win32 and X11 handles from GLFW (via `GlfwSurfaces`); [ ] Wayland
- [x] `GdxVk.createSurface(context, display, window, width, height, vsync)` (the plan's name) wraps `GdxVkContext.createPresenter()`; every desktop test goes through it. Android uses `createPresenterAndroid` or `VulkanAndroidApplication`
- [x] Android: `ANativeWindow_fromSurface()` from a minimal `SurfaceView` host

### 4. CI
- [x] GitHub Actions workflow with version-pinned Mesa image: runs on every push touching `gdx-vulkan/**` (first green run 2026-11-03; a Maven Central or wrapper-validation network flake needs a re-run, not a code change)
- [x] llvmpipe (OpenGL) side of the Mesa image is the Gate 2 / Gate 3 GL oracle
- [x] Every test in Tier A and `forceTierB` with validation and sync validation (lavapipe, Docker)
- [x] Golden harness: exact lavapipe goldens, plus the GL-vs-Vulkan tolerance comparison
- [x] Windows (RTX) runs, manual
- [x] Android run, manual: REDMAGIC NX809J (Adreno 840) and a Samsung Galaxy S20 FE 5G (SM-G781W, Adreno 650, Android 13)

### Gate 1a
- [x] Clear screen and triangle match goldens in both tiers on lavapipe (Docker run of the CI script)
- [x] Render correctly on the RTX 2080 (real device index 1, goldens within tolerance, both tiers; earlier claims about the 2080 were invalid, see CLAUDE.md)
- [x] Render correctly on the "Galaxy S20": the plan's device was not available; the S20 FE 5G (Adreno 650, Vulkan 1.1 driver, so the compatibility path only) passes the whole device suite, 32 of 32 checks, with 3D (models, knight and fog match the lavapipe goldens, 0 to 2 pixels differ; the GPU-driven instancing runs; stock `ModelBatch` on the shim matches), the lifecycle stress (8 cycles, 0 failures, 13 ms surface recreation, Swappy on at 60 Hz). Until 2026-11-04 every `VulkanModelBatch` pipeline failed there with `VK_ERROR_INITIALIZATION_FAILED` (`Adreno-GSL: GPUOBJ_ALLOC Invalid argument`): the driver refuses a vertex shader whose buffer reads are addressed from a push constant (the model shader found its record from `gdx_pc.u_indices.x`). Found by building probe pipelines on the device (docs/DRIVER_NOTES.md); fixed by naming the record with the draw's first instance and reading it from `gl_InstanceIndex`; `DriverRulesTest` keeps the pattern out of the model shaders

---

## Phase 1b: Memory, layout and caches

### 1. Two-set pipeline layout
- [x] Exactly as Core decision 2, including the Tier A feature check and the table-size clamp

### 2. Deferred deletion
- [x] `uint64` serial incremented on every `vkQueueSubmit`
- [x] Completion by timeline semaphore (1.2+) or a fence per serial (1.1; `forceTierB` selects fences)
- [x] Retired `VkBuffer` ranges, `VkImage`, `VkImageView` and Set 1 slots/sets freed once `completed >= retirement serial`
- [x] Retired `VkFramebuffer`: offscreen framebuffers go through the deletion queue; with `VK_KHR_swapchain_maintenance1` the replaced swapchain, its views, framebuffers and semaphores are parked and destroyed when the fences of its last presents have signalled, so a resize no longer waits for the device (without the extension, or while Swappy paces the swapchain, the old `vkDeviceWaitIdle` remains, 2-5 ms at resize only). Render passes live as long as the presenter
- [x] Retirement thread-safe: `dispose()` from `create()`, between frames and from loader threads (soak: 4 worker threads)
- [x] Retirement across multiple windows: `MultiWindowTest` (two windows, two presenters, one context): textures drawn in one window and disposed before the other draws, and a rename while both windows have frames in flight; validation clean in both tiers

### 3. Allocators
- [x] Per-frame dynamic ring for the Set 0 UBO and SSBO, persistently mapped, aligned to the device minimums, grows when exceeded
- [x] Mutex-guarded staging ring; the render thread records every queued copy into an upload command buffer at `endFrame`, submitted ahead of the frame command buffer

### 4. Caches
- [x] `VkSampler` cache keyed by (filters, wrap, anisotropy), bounded (4 samplers over ~1,000 changes/s)
- [x] `VkPipelineCache` saved to disk, header-validated against device/driver on load
- [x] Pre-warmed at startup: the disk-backed `VkPipelineCache` is loaded, so rebuilding a pipeline the cache has seen is a lookup. A list of pipelines to rebuild eagerly is **not kept**: pipelines are created from shaders the application supplies, only the first run compiles anything, and measured first-frame cost with a warm cache is below a frame

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
- [x] Format conversion: `RGB888 -> RGBA8888`, `Alpha -> R8_UNORM` swizzle `(0,0,0,R)`, `LuminanceAlpha -> R8G8_UNORM` swizzle `(R,R,R,G)` done; `RGBA4444` and `RGB565` are expanded to RGBA8 with GL's own bit replication (`x * 17`, `(x << 3) | (x >> 2)`), which is the plan's stated fallback and bit-exact with stock GL; the native packed formats would only halve the memory of 16-bit textures, which libGDX applications rarely use, and are **not used**; `GL_UNPACK_ALIGNMENT` honoured
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
- [x] `VulkanShaderPipelineBundle` replacing custom `ShaderProgram` GLSL: bundles of precompiled SPIR-V (both tiers) with `u_custom0/1` push values, set with `setShaderBundle` on `VulkanSpriteBatch`, `VulkanPolygonSpriteBatch` and (new) `VulkanImmediateModeRenderer`, which `VulkanShapeRenderer.getRenderer()` returns; tested with the example `sprite_gray.frag` and `shape_invert.frag`. There is no GLSL-to-SPIR-V at run time (the plan's documented 2D exception)
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
- [x] Offscreen passes before the swapchain pass: the frame's swapchain pass begins at the first swapchain draw or clear, not at `beginFrame`, so framebuffer passes that come first (post-processing chains, shadow maps) never suspend and resume it; `lastPassCounts()` proves it (4 framebuffer passes then the screen = 1 swapchain pass; a screen draw between them = 2). Passes recorded after a swapchain draw keep stream order: libGDX applications that interleave them are drawing in an order that matters, and reordering would change their picture
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

- [x] Adreno one-texture sprite speed: the single-texture fragment shader reads its bindless slot from the push constants instead of a flat varying (`sprite_single.frag`), which the Adreno compiler can treat as uniform: the REDMAGIC (Adreno 840, modern path) runs the 20,000-sprite test at 1,402 to 1,545 fps in steady state instead of 548; the picture is unchanged (exact goldens and the stock-OpenGL comparison pass on both code paths)

### 1. `VulkanAndroidApplication`
- [x] Reuses `AndroidInput`, `AndroidAudio`, `AndroidFiles`; own `SurfaceView` implementing `SurfaceHolder.Callback`: `VulkanAndroidApplication` + `VulkanAndroidGraphics` (a render thread owns every Vulkan call; pause/resume/dispose reach the `ApplicationListener` on it). `Callback2` is not needed: the renderer redraws through the swapchain, not through `surfaceRedrawNeeded`
- [x] `surfaceDestroyed` destroys only swapchain + surface, device/VMA/resources stay resident: the 100-cycle run (Tier A) and 20-cycle run (Tier B) on the Adreno 840 read the picture back after every cycle, zero lost textures; `GdxVkFrame.replacePresenter` keeps the batches valid across it
- [x] `surfaceCreated`/`surfaceChanged` recreate surface and swapchain: 4 ms on average, 6 ms worst on the Adreno 840 (plan: under 50 ms), measured from the callback to a presenter ready to draw

### 2. Pre-rotation
- [x] `preTransform = currentTransform`, output rotated in clip space after the user's projection: done with specialization constant 0 of every vertex shader that calls `gdx_vk_clip` (`GDX_PREROT`, set per pipeline variant) instead of the frame UBO, because the 128-byte push block is full and a UBO read would cost every vertex. Verified on the Adreno 840 at 0, 90, 180 and 270 degrees (screenshots show the upright picture; the log shows the presenter pre-rotated), and on the desktop by `PreRotationTest` (same scene identical at all four forced rotations)
- [x] `Gdx.graphics.getWidth/Height`, `glViewport`, `glScissor` stay logical; the presenter rotates the viewport and scissor rectangles into the image, and a capture comes back in the logical orientation. Custom SPIR-V that does not call `gdx_vk_clip` is not rotated (documented on `VulkanShaderPipelineBundle`)

### 3. Frame pacing and memory
- [x] `VK_KHR_swapchain_maintenance1` where available (instance: `VK_KHR_surface_maintenance1`, `VK_KHR_get_surface_capabilities2`; device feature `swapchainMaintenance1`): present fences, one per frame slot; `GDXVK_NO_PRESENT_FENCES=1` turns it off. Active on the RTX 5080 and RTX 2080 and the Adreno 840 (the whole desktop suite and the device suite run with it); the Ubuntu and SDK validation layers that predate the extension warn "not a known extension string", which the message counter ignores
- [x] Android Frame Pacing (Swappy): `androidx.games:games-frame-pacing` through prefab, `gdxvk_swappy.cpp` (device extensions, attach per swapchain, `SwappyVk_queuePresent`), switched on by `VulkanAndroidApplication` when `GdxVkConfig.framePacing` (default) and vsync are on. On the Adreno 840 `framePacingPeriodMicros` reads 16,666 (60 Hz) and the lifecycle stress (rotate/background cycles, surface recreation) passes in both tiers. Swappy replaces present fences on that swapchain. The library is now built against the **shared** C++ STL, which the Swappy prefab package requires
- [x] `onTrimMemory()` releases idle staging memory: `GdxVkContext.trimMemory()` runs finished deletions and frees idle staging blocks (`ResourcesTest.trimMemoryFreesIdleStagingAndKeepsEverythingElse`); `VulkanAndroidApplication` calls it from `onTrimMemory(>= RUNNING_LOW)` and `onLowMemory` on the render thread

### 4. Async loading
- [x] `VulkanAsyncTextureLoader` for `AssetManager`: not needed as a class. The stock `TextureLoader` already decodes on worker threads and builds the `Texture` where `update()` runs, and nothing in that path is GL-specific (`AssetManagerTest` on the desktop, texture streaming in `LifecycleActivity` on the phone)

### Gate 4 and stress test 4
- [~] All Phase 1-3 tests on a physical device with pre-rotation active, confirmed in Android GPU Inspector: the 21-check device suite passes on the Adreno 840 in landscape with the swapchain pre-rotated 90 degrees (logical 2688x1216) in Tier A and Tier B, and screenshots at four angles show the upright picture. Not done: an Android GPU Inspector capture (AGI is not installed here; the presenter's own `rotation()` and the logs are the evidence instead)
- [x] ADB script: `tools/stress4.sh [cycles] [--tier-b]` runs N rotate + background/foreground cycles (default 50; the plan's 500 is available as an argument, about 45 minutes, and judged excessive) while an `AssetManager` streams textures in and out. Split-screen is not driven by it: adb cannot toggle it reliably, so it stays a manual check in `docs/RELEASE.md`
- [x] Pass: zero crashes, zero lost textures, flat native heap, swapchain recreation under 50 ms: `tools/stress4.sh` on the Adreno 840 with pre-rotation on and textures streaming, 50 cycles in Tier A (0 failures, 2 ms average and 5 ms worst recreation, heap 26,091 -> 26,117 KB between cycle 25 and 50) and 20 in Tier B (0 failures, 2 ms / 4 ms). The plan says 500 cycles; 50 was run (500 takes about 45 minutes)

---

## Phase 5: 3D with stock models

### 1. Mesh data
- [x] Buffer shim calls with copy-on-write (see Core decisions 3)
- [x] `StockMeshAccess` uploads dirty stock meshes at draw time
- [~] The upstream libGDX PR (public buffer handle + dirty accessor): the patch is written and checked (`docs/upstream/`, applies cleanly to the libGDX sources, five classes, two small public accessors each) but **not filed**: opening a pull request on `libgdx/libgdx` is the owner's decision

### 2. `VulkanModelBatch`
- [x] Accepts stock `RenderableProvider`s (`ModelInstance` and friends, the stock `RenderableSorter`s, `Environment`): `VulkanModelBatch`, a port of libGDX's default shader. It maps `VertexAttributes` + `Material` to pipelines itself (40 vertex shader variants for normal/colour/texcoord and 0-4 bone weights, specialization constants for the material and light flags) instead of through a `VulkanShaderProvider` and `VulkanShaderPipelineBundle`s, so custom `Shader`s and `ShaderProvider`s are not accepted. Supported: position, normal, colour, texcoord0, bones (4 weights); diffuse/specular/emissive colours and textures, shininess, normal maps (beyond stock), blending, alpha test, cull and depth attributes; ambient cubemap, directional and point lights, fog, one directional shadow map (`DirectionalShadowLight`, with `VulkanModelBatch.depth` as the `DepthShaderProvider` equivalent). Tier B samples one texture per draw, so on Tier B only the diffuse texture works and specular/emissive/normal textures and shadow maps are ignored. Not supported: spot lights (stock's default shader has none either), reflection, custom shaders
- [x] Lights, materials, bone matrices in the Set 0 SSBO: one blob per flush group (frame header + one record per renderable + bone matrices) written by a `Set0Data` stream op into the frame ring; draws pick their record through the push constants
- [x] Sorted by the stock sorter (distance, blended last) or by `InstancingSorter` (mesh part and material first); identical renderables that follow each other become one instanced draw (`drawIndexedInstanced`, `gate5_fog` asserts it happens), and GPU-resident instances are drawn with `vkCmdDrawIndexedIndirect` (`VulkanGpuInstances`)

### 3. Lighting and shadows
- [~] Clustered point-light culling in compute: `light_cull.comp` sorts the point lights into a 16 x 9 x 24 cluster grid (exponential depth slices), the fragment shader reads its cluster (`VulkanGpuInstances`). Every one of the 3,456 cluster lists equals a CPU brute force (`GpuInstancesTest`, and on the phone, 0 mismatches). Spot lights are not done, and the stock `ModelBatch` path still lights per vertex with at most 2 directional and 5 point lights as stock does
- [x] Shadows: one directional shadow map through stock `DirectionalShadowLight` (stock's packed-depth shader and 4-tap PCF ported), Tier A only: it matches stock GL within 1 pixel and has a lavapipe golden (`gate5_shadows`). **Cascades** (beyond stock): `VulkanCascadedShadowLight`, 2 to 4 maps of one size, the frustum split by a lambda blend of uniform and logarithmic, each slice fitted with a bounding sphere and moved in whole texels so shadows do not shimmer; the fragment shader picks the cascade from `1 / gl_FragCoord.w` (perspective cameras). `gate5_cascades` has a lavapipe golden and a check that shadow shows in both a near and a far band; the phone matches the golden within 1 pixel. Not done: point-light cubemap shadow casters (the GL shim has no cube textures), spot-light shadows (no spot lights)

### 4. Compute
- [x] `VulkanComputeShader` with automatic barriers `COMPUTE_SHADER -> COMPUTE_SHADER` between dispatches and `-> DRAW_INDIRECT | VERTEX_ATTRIBUTE_INPUT | VERTEX/FRAGMENT_SHADER` after the last, `DrawIndexedIndirect` and `DrawIndexedInstanced` stream ops, `GdxVkResources.readBuffer` for tests; used by `VulkanGpuInstances` (cull, light cull) and the boids example

### Gate 5 and stress test 5
- [x] Animated `knight.g3db` via unmodified `G3dModelLoader` + a `ModelBuilder` scene, normal mapping, skeletal animation, directional shadows: `gate5_models`, `gate5_knight`, `gate5_fog` match stock `ModelBatch` on OpenGL with 0 differing pixels and `gate5_shadows` with 1 (all within tolerance), `gate5_textures` exactly; `gate5_normalmap` has no stock equivalent and has its own lavapipe golden and a check that the mapped box shows far more detail than the plain one. All have lavapipe goldens; on the Adreno 840 they match them in both tiers (the Tier A-only scenes in Tier A)
- [x] 250,000 instanced textured meshes moved by compute boids, 500 point lights culled in compute: `Stress5Main` (`gradlew :gdx-vulkan-desktop:stress5`), `boids.comp` (grid-based boids), `gpu_cull.comp`, `light_cull.comp`, `gpu_lit.vert/.frag`
- [x] `VulkanModelBatch` pipelines build on the Adreno 650 (Galaxy S20 FE): fixed, see "Render correctly on the Galaxy S20" above and docs/DRIVER_NOTES.md
- [~] Pass: 60+ fps on the RTX 2080 (495 fps average, 1% low 259, both tiers; RTX 5080 957-988 fps), 50,000 instances and 64 lights at 60 fps on a phone (Adreno 840, 1216x2688, vsync off: 781 fps in Tier A, 785 in Tier B; 250,000 instances and 500 lights: 52 fps; **not the Galaxy S20**), zero Java allocations per frame (0.6-1.2 bytes per frame measured over 12 s, the rest is noise in the measurement; found and removed: JDK 18+ reflection allocation in `StockMeshAccess`, now JNI field reads)

---

## Phase 5b: Stock GLSL shaders (runtime translation to Vulkan)

Goal: a game's own libGDX shaders (`ShaderProgram`, written in the old `attribute` / `varying` / `uniform` GLSL) run unchanged, and in the end the stock
`SpriteBatch`, `ShapeRenderer` and `Stage` run without being renamed. Design rule (owner's decision): the translator produces **Vulkan-native** shaders, it
does not emulate OpenGL. Second rule (owner's decision): **base functionality never goes through the translator**. The shaders for the sprite batches, the shape renderer, the model batch (default and shadow-depth), the post-processing effects and the compute work are dedicated Vulkan shaders written for this library and compiled at build time. The translator is for shaders a game supplies itself. Every item below is judged against that: nothing may cost a GL-style per-call price at draw time (uniform calls, state replay,
descriptor writes), and the Vulkan features the library already uses (bindless textures, push constants, uniform rings, pipeline cache) are the targets.

### Porting ergonomics (done)
- [x] Batches, shape renderer, model batch, compute shader and GPU instances find the application's frame themselves (`new VulkanSpriteBatch()`, `GdxVkFrame.current()`), with stock's constructors
- [x] `VulkanDesktopApplication` accepts a stock `Lwjgl3ApplicationConfiguration` (`VulkanConfigurations` reads its package-private fields; libGDX is not changed)
- [x] `VulkanModelBatch` turns the screen's depth buffer on itself (kept across swapchain replacement); no `setDepth` call
- [x] `VulkanStage`: the stock `Stage` with a `VulkanSpriteBatch`, same constructors, disposes the batch
- [x] ~~Creating a stock `SpriteBatch`, `ShaderProgram` or `Stage(viewport)` fails with a message that names the class to use instead~~ Replaced by section E: the stock classes run, so there is nothing to refuse. Calls the shim still cannot serve (client-side vertex or index arrays, vertex data from several buffers) fail with a message that says what to use

### Built-in shaders (done)
- [x] Built-in shaders are dedicated Vulkan GLSL, not translations: `sprite.*`, `shape.*`, `mesh_flat.*`, `model_default*` (a port of libGDX's default shader, with the data in a storage buffer and the material and light flags as specialization constants), `model_depth*` (the shadow-map pass), `fx_*`, `gpu_*`, `boids.comp`; compiled with `glslc` at build time to SPIR-V, loaded by name; nothing is compiled or translated at run time for them
- [~] A test fails the build if a built-in code path calls the run-time compiler: `GlslCompiler.callCount()` is asserted unchanged around a frame drawn with the sprite batch, the polygon batch and the shape renderer (`ShaderProgramTest.theLibrarysOwnShadersNeverUseTheRunTimeCompiler`); the model batch, the effects and compute are not covered yet

### A. Translating stock GLSL
- [x] Dialect: GLSL ES 1.00 / desktop 1.10-1.20 as libGDX ships it: `attribute` to `in`, `varying` to matched `out`/`in` locations across the two stages, `gl_FragColor` and `gl_FragData[n]` to declared outputs, `texture2D`/`texture2DProj`/`texture2DLod` to `texture`/`textureProj`/`textureLod`, `#ifdef GL_ES` and `precision` lines (`GlslTranslator`; the design is in `docs/SHADER_TRANSLATOR.md`). `textureCube` is refused until the texture shim has cube textures
- [x] Struct uniforms and arrays of structs (`DefaultShader`'s `u_dirLights[n]`, `u_pointLights[n]`, `u_spotLights[n]`, members float, int and vecN): the struct is declared in the uniform block (std140: the struct starts on 16 bytes, members follow their own alignment, size and array stride round up to 16) and each member is a uniform named as GL names it (`u_dirLights[1].color`), listed element by element so that the location of one light's member minus the next's is the light stride `BaseShader` computes (`GlslTranslatorTest.theModelShadersTranslateWithAndWithoutLights`, both code paths)
- [ ] `#version 300 es` / `330` style shaders pass through with only the Vulkan declarations added
- [x] `ShaderProgram.prependVertexCode` / `prependFragmentCode`, `#define`s and `#extension` lines are honoured: the source is expanded with glslang's preprocessor before the declarations are read, so libGDX's flag-driven shaders (`DefaultShader`) resolve exactly; `GL_OES_*` extension lines are dropped
- [x] Compile errors come back as `ShaderProgram`-style logs with the line numbers of the **original** source, not of the translated one: the translation never changes the number of lines and restarts numbering at 1 after its declarations; the prepend text counts as lines, as in stock (`GlslTranslatorTest.errorsKeepTheLineNumbersOfTheOriginalSource`)
- [x] Fails loudly (a clear message with the shader, line and construct) on GLSL it cannot translate: cube and 3D samplers, structs that are not uniform types or have array or matrix members, bool arrays, array varyings, a varying the vertex shader does not declare, more textures than the path can bind; no silent mis-compiles

### B. Vulkan-native mapping (the optimisation requirements)
- [~] Loose uniforms become one std140 uniform block per program, laid out from reflection and placed in the existing per-frame ring; at draw time only the changed byte ranges are written, never one call per uniform: the layout is done (arrays and the uniforms that no longer fit go to a std140 block at set 0, binding 0, with explicit offsets, `Translation.uniforms`); writing the changed ranges at draw time belongs to `VulkanShaderProgram` (section D)
- [~] Small, hot uniforms (a projection matrix and a few scalars) are placed in push constants within the shared 128-byte budget: done by declaration order, first fit, with the texture slots after them and their room reserved; the rule does not look at update rates (a shader that declares a rarely-set uniform first spends push space on it). A usage-based choice is left for when measurements ask for it
- [~] Samplers use the existing bindless texture table (modern path) or the one-texture set (compatibility path); texture units are slots, so binding a texture is not a descriptor write: the translation does this (`u_texture` becomes `gdx_textures[nonuniformEXT(slot)]`, the slot a 4-byte push member; two textures on the compatibility path are refused); binding the textures belongs to `VulkanShaderProgram`
- [~] Vertex input comes from the program's reflected attributes and the mesh's `VertexAttributes` as pipeline vertex-input state; `glVertexAttribPointer` calls are not replayed: the attribute table (name, type, location in declaration order) is produced; building the vertex input from a mesh belongs to `VulkanShaderProgram`
- [ ] `#define` flags that an application sets through `prependCode` can become specialization constants, so variants share SPIR-V
- [ ] Precision qualifiers are mapped to `RelaxedPrecision` where the device benefits (mobile)
- [ ] SPIR-V goes through an optimisation pass (spirv-opt performance recipe, debug info stripped); a benchmark shows the effect, or the pass is dropped
- [x] Compiled SPIR-V is cached on disk by a hash of (source, prepend text, code path, translator and compiler versions): `ShaderCache` keeps both modules and the translation tables, so a program found in the cache is created without the preprocessor, the translator or the compiler (`VulkanShaderProgram.cacheDirectory`, default `gdxvk-shader-cache` in local storage; `ShaderProgramTest` checks the compiler is not called, the cached program draws the same picture, and a damaged file is ignored and replaced). The existing pipeline cache keeps handling pipelines
- [x] glslang's start-up (0.15 s on a PC, 0.6 to 1.1 s on the phones) runs on a background thread from the moment the frame is created (`GlslCompiler.warmUp()`; it calls the native entry points directly, so `callCount()` does not see it): the first shader 2 s after start takes 40 ms on the Galaxy S20 instead of 0.86 to 1.09 s
- [x] Compilation can run off the render thread; pipelines are still built lazily at first use: `VulkanShaderProgram.prepareAsync(vertex, fragment)` translates and compiles on a worker thread and returns a future (the log: empty when it compiled); the constructor then takes the prepared result and calls neither the translator nor the compiler (`preparedHits`, `GlslCompiler.callCount()` in `ShaderProgramTest`); a shader that fails reports why through the future and is not kept. `warmUp()` compiles a tiny shader early so glslang's 150 ms first-use start-up is not paid by the first real program
- [x] Measured: a translated stock shader is not slower than the built-in one (`gradlew :gdx-vulkan-desktop:stressShaders`, 20,000 sprites from one texture, vsync off, three shaders in turn, twice): RTX 5080 0.990x (modern path) and 0.973x (compatibility path), RTX 2080 0.998x; a custom shader with a push-constant tint and a uniform-block palette 0.98x to 1.01x. The test is CPU-bound by design (the shaders are trivial), so it shows that the translation, the uniform handling and the pipeline add no cost; it does not show shader arithmetic differences. On the Galaxy S20 FE (Adreno 650, compatibility path, display at 120 Hz, vsync off) the three shaders agree within 2% while the phone's clocks stay in one state (cool: 721, 713 and 719 fps; throttled: 520, 511 and 512 fps); the phone steps between those states during a run, so an average across rounds is not meaningful and the figure to trust is the comparison inside a state. Not yet run on the REDMAGIC.

### C. The compiler
- [x] Choose the compiler: **glslang** used directly behind a `ShaderCompiler` interface, with the reasons and the comparison with shaderc (and with the original backend, which used shaderc through LWJGL) in `docs/SHADER_COMPILER.md`
- [~] Vendor a pinned glslang (16.6.0) into the native library and build it for Windows x64, Linux x64 and Android arm64; record the size cost with and without the SPIRV-Tools optimizer: vendored, built and tested on Windows x64 (1.7 MB, first compile 152 ms, then 0.9 ms; `shader_compiler_test`); Linux (Docker, CI) and Android builds not run yet, the optimizer not added
- [x] Existing SPIRV-Reflect validation runs on every translated shader: creating a pipeline validates both modules against the library's pipeline layout, and it already caught a real translation bug (a texture table declared in a stage that never reads it, which glslang sized to one element); nothing extra is needed
- [ ] The build still works without the compiler's sources for people who only use precompiled bundles (optional component), or the dependency is vendored

### D. `VulkanShaderProgram` API
- [x] `new VulkanShaderProgram(vertexSource, fragmentSource)` and the `FileHandle` form (and with an explicit frame), with `isCompiled`, `getLog`, `getAttributes`, `getAttributeLocation`, `getUniforms`, `hasUniform`, `dispose`, and the statics `prependVertexCode`, `prependFragmentCode`, `pedantic` (a missing uniform throws, as in stock). A shader that fails does not throw: `isCompiled()` is false and `getLog()` has the message with your line numbers
- [~] The `ShaderProgram` setters: `setUniformi`/`setUniformf` with 1 to 4 values (by name; by location for one value), `setUniformf` with `Vector2`/`Vector3`/`Color`, `setUniform1fv` to `setUniform4fv`, `setUniformMatrix` with `Matrix4` and `Matrix3` (and `transpose`), `setUniformMatrix2fv`/`3fv`/`4fv`, `fetchUniformLocation`, `bind`/`begin`/`end`; uniform values are kept by the program and reach the GPU as push-constant words or one uniform block, bound again only when the data, the frame, or another Set 0 binding changed (`ShaderProgramTest`). Missing: the `FloatBuffer` and `IntBuffer` overloads and the by-location forms of the multi-value setters
- [~] Custom shaders for the batches the way stock does it: `setShaderProgram(program)` on `VulkanSpriteBatch`, `VulkanPolygonSpriteBatch` and, through `((VulkanImmediateModeRenderer)shapeRenderer.getRenderer())`, the shape renderer; tested on the GPU against the built-in shaders (the translated stock sprite shader draws a picture identical to the built-in one, in the sprite and the polygon batch; uniforms from push constants and the uniform block; two programs and the built-in shader alternating in one frame; a second texture from texture unit 1; a shader that does not compile is refused with its log; a shape shader with a tint), on both code paths and both GPUs. The batch method is `setShaderProgram`, not stock's `setShader`: an overload would make `batch.setShader(null)` ambiguous for existing code. Not done: a constructor taking the program, a stock-named `setShader`, and programs for `ModelBatch`
- [ ] Custom 3D shaders: `ShaderProvider` / `Shader` for `VulkanModelBatch` built on `VulkanShaderProgram`
- [ ] `VulkanShaderPipelineBundle` (precompiled SPIR-V) stays supported as the build-time path

### E. Stock classes with no rename
- [x] GL shim: `glCreateShader`, `glShaderSource`, `glCompileShader`, `glCreateProgram`, `glAttachShader`, `glLinkProgram`, `glGetProgramiv`/`glGetShaderInfoLog`, `glGetActiveUniform`/`Attrib`, `glGetUniformLocation`, `glUniform*`, `glUseProgram` route to the translator (`gl/StockPrograms`), so the stock `ShaderProgram` works: a custom shader compiles and draws through a stock `SpriteBatch` (`StockClassesTest`)
- [x] GL shim: array buffers, `glVertexAttribPointer`, `glEnableVertexAttribArray`, `glDrawElements`, `glDrawArrays` become command-stream draws; the pipeline comes from (program, vertex layout, blend state) and is cached per program, so equal state costs one map lookup. Floats and normalised unsigned bytes as vertex formats; vertex data from one buffer. A buffer changed after it was drawn this frame is copied on write (`glBufferData` and `glBufferSubData`; dynamic buffers keep a CPU copy for this) and a native buffer id is not reused until the frame that names it is submitted. Vertex and index data in memory (`Mesh` of the `VertexArray` type, which `PolygonSpriteBatch` uses) is copied into the frame's vertex ring by the draw that reads it, up to the highest index drawn. Not supported, with a message: several vertex buffers, other vertex formats, index type unsigned byte; the `glVertexAttrib*` constants for disabled attributes are ignored (a Vulkan pipeline has none; a draw that reads such an attribute gets undefined values, as an unset one does in GL ES)
- [x] Stock `SpriteBatch`, `PolygonSpriteBatch`, `ShapeRenderer` and `new Stage(viewport)` run unmodified, and libGDX's own shaders in them are **recognised by a hash of the whitespace-normalised source and replaced by the dedicated Vulkan shaders** (`glsl/KnownShaders`: `sprite_single` for both batches, `shape` for the shape renderer, with hand-written tables); the run-time compiler is not called (`StockClassesTest` asserts three replacements and zero compiler calls). Not yet recognised, so translated: `ImmediateModeRenderer20` with normals or texture coordinates, and any shader that is not libGDX's own text
- [x] Stock `ModelBatch` with libGDX's `DefaultShader` (lit by a directional light, through the translator) and a stock `Stage` draw what `VulkanModelBatch` and `VulkanStage` draw (`StockScenesTest`: the 3D picture within 6 levels on all but 1% of the pixels, the Scene2D picture identical). Not yet run: `DepthShader` / shadows through the stock path, skinned models, point and spot lights, textures in `DefaultShader`
- [x] The screen's depth buffer follows the stock configuration: `Lwjgl3ApplicationConfiguration` / `AndroidApplicationConfiguration` depth bits above zero turn it on from the start (`VulkanDesktopConfiguration.depthBuffer` for the new class); `glEnable(GL_DEPTH_TEST)` on a screen without depth prints one warning that says what to set
- [x] The shaders of stock `ModelBatch` (`DefaultShader` and the shadow `DepthShader`) do not reach the run-time translator or compiler: they are translated and compiled when the library is built, for a matrix of vertex layouts (position+normal, +texture coordinates, +packed colour), materials (colour, texture, specular and shininess, emissive, blending, alpha test) and environments (none, ambient, 1-2 directional, point, spot, fog), and shipped in the jar (`shaders/bundle`, 57 programs per code path, 1.4 MB: the matrix includes the skinned knight with every environment). `VulkanShaderProgram` finds a program by the same hash the disk cache uses, so a variant outside the matrix is translated at run time as before and cached on disk. The flag-driven source makes one dedicated GLSL file per variant impractical (the flags change struct array sizes), so this is ahead-of-time translation rather than a hand-written port. `gradlew :gdx-vulkan-desktop:recordShaderBundle` rebuilds the bundle (run it after changing the translator, the compiler version or the matrix in `RecordShaderBundleMain`); `BundledShadersTest` replays the whole matrix with the disk cache off and fails if the compiler is called, so a stale bundle is caught
- [x] Gate: the Gate 2, 3 and 5 scene sets are drawn a second time by libGDX's own classes on the shim (`Gate2GoldenTest`, `Gate3GoldenTest`, `Gate5GoldenTest`: `SpriteBatch`, `PolygonSpriteBatch`, `ShapeRenderer`, Scene2D, `BitmapFont`, FreeType, particles, the post-processing effects as translated GLSL programs, `ModelBatch` with `DefaultShader`/`DepthShader`: skinned knight, textures, fog, shadows) and compared with the picture the Vulkan classes drew in the same test: Gate 2 within 2 levels on all but 0.05% of the pixels (the shaders are the same dedicated ones), Gate 3 within 6 levels on all but 0.5%, Gate 5 within 4 levels on all but 0.5%; both code paths on the RTX 5080, modern path on the RTX 2080. The normal-mapping scene has no stock equivalent and is skipped
- [x] Performance gate: a stock `SpriteBatch` on the shim is within 1.5x of `VulkanSpriteBatch` (`gradlew :gdx-vulkan-desktop:stressShim`, 20,000 sprites, vsync off, order alternated, two rounds): one texture, the stock batch reaches 0.76x of the generated one on the RTX 5080 modern path, 0.72x on the compatibility path and 0.96x on the RTX 2080; eight textures, 1.23x, 0.78x and 1.28x (the stock batch flushes at each texture change, the generated one batches the textures into one draw, and on desktop GPUs that is not always the faster way). It stayed above 1/1.5 only after a fix: a CPU copy kept for every dynamic buffer cost 1.1 ms a frame, so it is kept only for buffers defined without data (the sub-range-update classes). On the phones (2026-11-04): the stock `SpriteBatch` on the shim reaches 0.88x to 0.89x of `VulkanSpriteBatch` on the Adreno 840 (both code paths) and 0.89x on the Adreno 650. First use of a translated shader: the first program of a run takes 0.6 s (Adreno 840) and 1.1 s (Adreno 650), because it starts the compiler (`warmUp()` moves that off the render thread); each further typical shader takes 10 to 25 ms, under the 50 ms target; a cached shader 0.2 to 0.8 ms (`shaderCompileTime` device check)
- [x] README and the migration section drop the renames: the README says the stock classes run unchanged, and gives the measured cost against the generated classes, which stay as the fast path

### F. Tests
- [~] Translator tests with the shaders libGDX ships: done for the `SpriteBatch`/`PolygonSpriteBatch`, `ShapeRenderer` and `ImmediateModeRenderer20` shaders, `DefaultShader` (unlit variants) and `DepthShader`, and a custom shader with arrays, a bool, an int, two samplers and `#ifdef` flags, each compiled by glslang on both code paths (`GlslTranslatorTest`, no GPU needed); not yet the Gate 3 post-processing shaders
- [ ] Every test runs on both code paths with the validation layers clean
- [x] Android: translated shaders compile and render on the phone: both phones, the whole device suite (32 checks) passes, the stock classes on the shim draw the same picture as the Vulkan classes (SpriteBatch, PolygonSpriteBatch, ShapeRenderer, the Gate 3 effects as translated GLSL, and on the Adreno 840 the stock `ModelBatch` scenes: models, knight, fog and, in the modern path, textures and shadows: 0 pixels differ). Adreno 840 (over wireless, allowed by the owner for that run, both code paths) and Adreno 650 (USB, compatibility path; no 3D, see Gate 1a). Compile time: the first program of a run 0.6 s (Adreno 840) and 1.1 s (Adreno 650), each further shader 10 to 25 ms, a cached shader 0.2 to 0.8 ms
- [ ] CI (lavapipe) runs the translator tests and the golden scenes

---

## Releases and adoption testing

Owner's plan: publish release builds on GitHub, when it is safe, so others can try the library in their own projects; and try the library in several of the
owner's own projects to prove that moving over is clean, and hopefully faster than stock libGDX. A release is made only when the owner says so.

### Release builds
- [ ] Release criteria agreed ("safe"): CI green on the tagged commit; no open bug that crashes or corrupts on a supported platform; the known limitations are in the release notes; the licences and `NOTICE` are complete; no personal paths or credentials in the repository
- [ ] Version scheme: pre-releases first (`v0.1.0-beta.1`), marked as pre-release on GitHub
- [ ] One jar that carries the native library of every desktop platform (`natives/<os>-<arch>/`); today the jar holds only the platform of the machine that built it
- [ ] A release workflow that runs on a tag: builds the natives on Windows x64, Linux x64 and (arm64 if wanted), the Android library as an AAR (arm64-v8a and the other ABIs), runs the tests, and attaches the jars, the AAR and their checksums
- [ ] Release notes written from the checklist (what works, what does not, the known problems) and a "how to try it" page for testers (`docs/RELEASE.md` updated)
- [ ] Issue template that asks for what is needed to reproduce (GPU, driver, OS, which path it used, the log)
- [ ] Publishing to a Maven repository (GitHub Packages, JitPack or Maven Central) decided; until then the releases are the download

### Adoption testing in the owner's projects
- [ ] `docs/ADOPTION.md`: one entry per project: what had to change (class swaps, shaders, configuration), anything unclear or broken, how long it took
- [ ] Each project builds and runs with gdx-vulkan on the desktop (and on Android where the project has it); output compared with stock OpenGL
- [ ] Performance compared with stock: the same build can switch between stock and gdx-vulkan (a flag), so one game is measured both ways: frame time on the CPU and the GPU, 1% lows, memory
- [ ] Everything found becomes a checklist item or an issue; the README's migration section is corrected from the real experience
- [ ] Custom GLSL in those projects listed, since it decides how much of the shader translator (Phase 5b) each project needs

---

## Demos (`gdx-vulkan-demos`)

Decisions of the owner, 2026-11-04: each demo is **one class written against stock libGDX**, with a main for `Lwjgl3Application` (OpenGL) and one for `VulkanDesktopApplication`, started side by side from the demo app (key C); **one demo app with a menu**; first the 3D scene with cascaded shadows and the GPU boids, which may have an OpenGL compute comparison. Built: three demos, each on gdx-vulkan, two with an OpenGL counterpart. Run with `gradlew :gdx-vulkan-demos:demos` (menu; keys 1 to 3, C, Esc) and `gradlew :gdx-vulkan-demos:demoGl -Ddemo=shadows-stock|boids`; `-Ddemo.start=N -Ddemo.screenshot=file.png` runs one demo and saves a picture.

- [~] Demos showcase what the library can do, and run on the desktop and on Android: three demos on the desktop (stock shadow recipe, cascaded shadows, GPU boids); not on Android yet
- [x] Where a stock libGDX equivalent exists, the demo shows it next to the Vulkan version: the stock shadow demo is the same class on OpenGL and on gdx-vulkan; the boids demo has an OpenGL 4.3 version with a compute shader written with `Gdx.gl31`; C starts the OpenGL window beside the Vulkan one, same size
- [x] Where libGDX has no class for what a demo does (cascaded shadow maps, GPU culling, indirect drawing, clustered lights), the demo says so on screen and what stock libGDX offers instead; the boids demo has an OpenGL 4.3 version with the same culling and indirect draw, written with raw `Gdx.gl31` calls
- [~] The demo code is clean, clear and easy to follow: short classes, one concern each, comments where Vulkan differs from stock (the owner reviews)
- [~] Every demo is visually interesting (the owner judges)
- [x] Every demo has easily readable text that says what it is about; the size follows the window height (`Hud`: body text is a 36th of the height, regenerated with FreeType when the window changes), plain libGDX (SpriteBatch, ShapeRenderer, FreeType) so it is the same on both backends
- [x] The demos are built by CI (`DemosSmokeTest` starts each, draws 20 frames, checks the picture) and listed in the README
- [ ] Clustered lights together with shadows in one scene: lights are sorted into clusters only on the GPU-driven path (one mesh, no shadows), `VulkanModelBatch` has up to 8 point lights and cascaded shadows; a scene with both needs the cluster lookup in the model shader, or shadows in `gpu_lit`
- [ ] More demos: sprite storm (100k+ sprites, stock against Vulkan), post-processing over a Scene2D UI
- [x] Comparisons that match: the boids' shaders compute the same colours on both backends (screenshots equal), the sprite demo is the same `Batch` code, `GlDemosActivity` runs the stock side on the phone; `tools/compare-demos.sh`, `tools/compare-phone.sh`; results in the README (20x for many textures on desktop and phone, 1.75x for 250,000 boids on the RTX 5080, 0.8 to 0.93x for boids on the Adreno 650, 0.66x for stock shadows through the shim)
- [x] REDMAGIC (Adreno 840, Tier A and Tier B, over wifi, 2026-11-04): device suite 32 passed in both tiers; all four demos run (`tools/demos-test.sh`); comparisons against stock OpenGL ES in the README (many textures 20x; boids 2.0x and, at 60,000, 4.5x without text; sorted sprites 0.2 to 0.3x, atlas 0.82x, stock shadows through the shim 0.63x; with the text on screen the boids are 0.28x, see `TODO.md`)
- [x] The demo app on Android: `gdx-vulkan-android/demoapp` compiles the desktop demos' source (the LWJGL mains and OpenGL counterparts left out); touch menu, tap controls, Back; Galaxy S20 FE (Adreno 650, Tier B): shadows 372 fps, cascaded shadows 194 fps, boids (10,000) 60 fps; `tools/demos-test.sh`

Both backends show the same fields on screen (`Backend`, `Hud`, `FrameStats`): API and version, device, driver, code path; frame time; CPU time per frame for the whole process (every thread, the driver's included) and for the render thread; GPU time per frame (gdx-vulkan: timestamp queries, `GdxVkPresenter.gpuFrameNanos()`; OpenGL: a timer query in `GlTimer`); then the demo's own lines. The OpenGL boids demo does what the gdx-vulkan one does apart from the lights: compute simulation, compute culling, indirect draw (an earlier version drew every boid, and the text said stock libGDX "has no GPU culling or indirect drawing", which was wrong: libGDX has no class for it, but `Gdx.gl31` has the calls).

Measured (RTX 5080, window 1280 x 720, vsync off, 4,000 frames, the numbers on screen):

| | frame | CPU per frame, all threads | render thread | GPU per frame |
|---|---|---|---|---|
| Stock shadow code, gdx-vulkan (GL shim) | 0.86 ms | 0.96 ms (1.1 cores) | 0.83 ms | 0.06 ms |
| Stock shadow code, OpenGL | 0.55 ms | 0.97 ms (1.7 cores) | 0.33 ms | 0.04 ms |
| Cascaded shadows, gdx-vulkan | 1.11 ms | 1.35 ms (1.2 cores) | 1.10 ms | 0.10 ms |
| Boids, gdx-vulkan, 500 lights | 1.51 ms | 0.52 ms (0.3 cores) | 0.52 ms | 1.39 ms |
| Boids, gdx-vulkan, no lights | 1.40 ms | 0.52 ms (0.4 cores) | 0.48 ms | 1.27 ms |
| Boids, OpenGL (simulation, culling, indirect draw, one light) | 2.03 ms | 2.53 ms (1.2 cores) | 0.57 ms | 1.73 ms |

What it shows. **CPU:** with the same GPU work (boids), gdx-vulkan spends about a fifth of the CPU (0.5 ms against 2.5 ms a frame); the render thread costs the same (0.5 ms), the difference is the OpenGL driver's own threads, which part-time spin and wait, so some of it is waiting, not work. Stock libGDX code on the shim does **not** use less CPU than on OpenGL (0.96 against 0.97 ms in total, and 0.83 against 0.33 ms on the render thread): the less-CPU claim holds for gdx-vulkan's own classes and the GPU-driven path, not for the shim. **GPU:** the boids take 1.27 ms of GPU time on gdx-vulkan against 1.73 ms on OpenGL, but the two versions run different shader code (the OpenGL one was written for this demo), so that is not a measure of the API; the shadow demos are CPU-bound (the GPU is idle 90% of the frame). The frame-time difference in the boids demo (1.40 against 2.03 ms) is mostly that GPU difference.

An earlier cascaded-shadows run found a real bug: `VulkanModelBatch` recomputed every mesh part's bounds on every `render()` (96% of the frame); it is now measured once, as stock libGDX does.

---

## Phase 6 (stretch, tabled: needs a Mac): macOS and iOS

- [ ] Starts only after desktop and Android pass every gate above
- [ ] macOS: `CAMetalLayer` from `GLFWNativeCocoa` into `vkCreateMetalSurfaceEXT`, `VK_KHR_portability_enumeration` / `_subset` (instance and device extension names already handled in the context)
- [ ] iOS: `UIView` backed by `CAMetalLayer` through RoboVM/MobiVM; FBO shim answers `GL_FRAMEBUFFER_BINDING`
- [ ] Drivers: MoltenVK, and Mesa KosmicKrisp as the higher-tier option

---

## Cross-cutting and housekeeping

- [x] Work committed on branch `gdx-vulkan-phase1` of the fork `slebed/libgdx` and published as its own repository, `slebed/gdx-vulkan` (history rebuilt from the commits that touched this directory; `main` there is the canonical home from now on)
- [x] CI workflow executed on GitHub (`slebed/gdx-vulkan`, `.github/workflows/ci.yml`, lavapipe, Mesa pinned): green on the first run from a clean checkout using only the bundled `test-assets/`
- [ ] Bump `gdxVersion` to 1.14.3 when published (checked: still 404 on Maven Central; the procedure is at the end of `docs/RELEASE.md`)
- [x] Windows/Android manual-run procedure documented as a release checklist: `docs/RELEASE.md`
- [x] Debug overlay (renames per frame, retired-queue depth, sampler count, staging blocks, textures, VMA bytes) built on `GdxVkResources.Stats`: `VulkanDebugOverlay` (`SpriteBatchTest.debugOverlayReportsTheRendererAndDrawsText`)
