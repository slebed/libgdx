# libGDX Vulkan Backend Plan v5.0: implementation checklist

Source: `docs/libGDX Vulkan Backend Plan v5.0.md`. Marks reflect what has actually been built **and verified**, not what is merely written.

- `[x]` done and verified   - `[~]` partial, or done differently from the plan (the note says how)   - `[ ]` not started

"Verified" means: passes `gradlew check -Dgdxvulkan.requireDriver=true` (validation + sync validation clean) on the RTX 5080 and RTX 2080 in both
tiers, and `ci/run-checks.sh` on lavapipe in Docker. Android items were verified on a REDMAGIC NX809J (Adreno 840), which is **not** the Galaxy S20
the plan names; the phone is currently unplugged.

## Summary

| Section | Done | Partial | Not started | Total |
|---|---|---|---|---|
| Core decisions | 30 | 7 | 19 | 56 |
| Phase 1a: Extraction, window surfaces and CI | 13 | 7 | 2 | 22 |
| Phase 1b: Memory, layout and caches | 15 | 1 | 4 | 20 |
| Phase 2: Unmodified 2D | 4 | 2 | 18 | 24 |
| Phase 3: Stock FrameBuffer | 0 | 0 | 13 | 13 |
| Phase 4: Android lifecycle | 0 | 2 | 10 | 12 |
| Phase 5: 3D with stock models | 0 | 0 | 12 | 12 |
| Phase 6 (stretch): macOS and iOS | 0 | 0 | 4 | 4 |
| Cross-cutting and housekeeping | 1 | 0 | 4 | 5 |
| **All** | **63** | **19** | **86** | **168** |
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
- [~] `glGenTexture`, `glBindTexture`, `glActiveTexture`, `glTexImage2D`, `glTexSubImage2D`, `glTexParameteri/f`, `glGenerateMipmap`, `glDeleteTexture`, `glPixelStorei` done and tested with stock `Texture`/`Pixmap`; `glCompressedTexImage2D` throws (not started); only `GL_TEXTURE_2D` (no cube maps)
- [x] Lazy `VkImage` creation at first use (`VulkanGL20.nativeTexture`), once the mip chain is known; level > 0 uploads; `glGenerateMipmap` by GPU blit chain
- [ ] Ordered state packets: `glClear`, `glClearColor`, `glViewport`, `glScissor`, `glEnable`/`glDisable` (scissor, blend, depth), `glBindFramebuffer`
- [ ] Buffers: `glGenBuffer`, `glBindBuffer`, `glBufferData`, `glBufferSubData`, `glDeleteBuffer`
- [ ] Framebuffers: `glGenFramebuffer`, `glFramebufferTexture2D`, renderbuffers, `glCheckFramebufferStatus`, deletes
- [x] Query table (in `VulkanGL20`, not a separate `VulkanGL20Queries`): extension strings, `GL_MAX_TEXTURE_SIZE`, anisotropy, `GL_VIEWPORT` (last `glViewport`), `GL_FRAMEBUFFER_BINDING` (0), `glGetString(GL_VERSION)`; unknown enums log once and return 0
- [ ] `Gdx.gl30` always null (nothing sets `Gdx.gl*` yet)
- [~] Whole interface covered: all 162 `GL20` methods exist (generated from libGDX's `GL20.java` by `tools/generate_gl20_base.py`); drawing, programs and vertex state throw a message naming the call and pointing at the Vulkan batches; pure state (blend, depth, scissor, clear, ...) is accepted and ignored until the command stream exists. Buffer and framebuffer GL calls still throw

### 4. Copy-on-write renaming
- [x] Sampler change: new slot/set, old one retired (soak-tested ~1,000/s, bounded sampler count)
- [~] Rename trigger: every pixel change after the image exists renames it (conservative); the "referenced by a recorded draw since the last submit" optimisation is not done, so a change before first use is applied in place on the CPU copy and costs nothing
- [x] Full-upload rename: same-shape `glTexImage2D` reload renames; a different size or format redefines the texture, as GL does
- [x] Sub-image upload rename (`vkCmdCopyImage` from the old image, then the region); old image restored to read layout for draws recorded earlier in the frame
- [~] Pending uploads queued per image/buffer request (not per GL handle); post-rename routing not applicable until renaming exists
- [ ] Renames-per-frame counter in the debug overlay

### 5. Stock Mesh access (`StockMeshAccess`)
- [ ] Reflected `Field`s for `bufferHandle` and `isDirty` on `VertexBufferObject`, `VertexBufferObjectSubData`, `IndexBufferObject`
- [ ] Dirty meshes uploaded at draw time (with copy-on-write); `Mesh.dispose()` releases the native buffer
- [ ] `VertexDataType.VertexArray` meshes copied into the per-frame ring each draw
- [ ] Unit tests pinning the field names/types per supported libGDX version
- [ ] Upstream PR: public `getBufferHandle()` and dirty/version accessor (filed in Phase 5)

### 6. Coordinate, winding, depth and colour conventions
- [x] Swapchain: negative-height viewport, `COUNTER_CLOCKWISE` front face (pixel-tested upright + culling, both tiers)
- [ ] Offscreen FBO: positive-height viewport, `CLOCKWISE` front face
- [ ] `glViewport`/`glScissor` y mapping (`vkY = targetH - (glY + glH)` swapchain, `vkY = glY` offscreen)
- [x] Tier A: front face is dynamic state; Tier B: baked in the pipeline
- [x] Depth remapped `[-1,1] -> [0,1]` in the vertex shader via `gdx_vk_common.glsl`, no extension branch
- [x] Swapchain formats `B8G8R8A8_UNORM` / `R8G8B8A8_UNORM`, never sRGB

### 7. Hardware tiers
- [x] Tier A: dynamic rendering
- [~] Tier B: `VkRenderPass`; one swapchain pass only, not the "cache built from formats and samples" yet (needed with FBOs, Phase 3)
- [~] Dynamic raster state on Tier A: cull mode and front face; depth test/write dynamic state arrives with depth attachments
- [ ] Blend as pipeline variants (blend not exercised yet)
- [x] Tier A bindless table with no flush on texture switch (the batch side is Phase 2); Tier B per-texture sets
- [ ] Tier B flush on texture switch (a batch concern, Phase 2)
- [x] `forceTierB` runs Tier B anywhere (`GdxVkConfig.forceTierB`; the plan calls it `VulkanApplicationConfiguration.forceTierB`)
- [x] CI runs every test in both tiers (`ci/run-checks.sh`; not yet executed on GitHub itself)
- [~] Device tests: RTX 5080 and RTX 2080 both tiers; Android phone (Adreno 840) both tiers for Phase 1a only; **no Galaxy S20**

### 8. Test oracle
- [ ] Compatibility reference = stock GL libGDX on llvmpipe (needs the GL harness, Phase 2)
- [ ] GL-vs-Vulkan image comparison with per-channel threshold plus maximum differing share
- [x] Regression reference = committed lavapipe goldens, compared exactly on lavapipe (4 scenes)
- [x] Goldens regenerated only on a Mesa pin change (`ci/mesa-version.txt` guard, `UPDATE_GOLDENS` flow)
- [x] Validation layer with sync validation on every test; any message fails (soak run in fence mode uses core validation only, documented)

---

## Phase 1a: Extraction, window surfaces and CI

### 1. Standalone build
- [x] Modules `gdx-vulkan-core` (Java + `jni/`), `gdx-vulkan-desktop`, `gdx-vulkan-android`
- [~] Natives via plain CMake + hand-written JNI instead of gdx-jnigen (deliberate; revisit when jnigen cross-compile is verified)
- [x] Natives built: Windows x64, Linux x64 (Docker), Android arm64-v8a
- [ ] Natives: Linux arm64, Android armeabi-v7a, Android x86_64
- [x] No copied audio classes; `Gdx.gl30` untouched from the first commit

### 2. Native core boot
- [x] volk + `vk_mem_alloc.h` vendored, `VK_NO_PROTOTYPES`, no Vulkan SDK link libraries
- [x] Instance and device creation with tier detection and `forceTierB`
- [x] UNORM swapchain, negative-height viewport, begin/end rendering with the render-pass fallback on Tier B
- [~] `VK_EXT_debug_utils`: validation messenger done; object names and RenderDoc markers per batch flush not done
- [x] Typed Java exceptions: device lost, surface lost, out of memory (`OUT_OF_DATE` is handled inside the presenter, not thrown)

### 3. Surfaces from native handles
- [x] Desktop: Win32 and X11 handles from GLFW (via `GlfwSurfaces`); [ ] Wayland
- [~] `GdxVkContext.createPresenter()` (the plan names it `GdxVk.createSurface()`)
- [x] Android: `ANativeWindow_fromSurface()` from a minimal `SurfaceView` host

### 4. CI
- [~] GitHub Actions workflow with version-pinned Mesa image: written; run only through the same script in local Docker, **never on GitHub**
- [ ] llvmpipe (OpenGL) side of the Mesa image is unused until the GL oracle exists
- [x] Every test in Tier A and `forceTierB` with validation and sync validation (lavapipe, Docker)
- [~] Golden harness: exact lavapipe goldens done; GL-vs-Vulkan tolerance comparison not done
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
- [ ] Retired `VkFramebuffer` and render-pass cache entries (swapchain framebuffers still destroyed after `vkDeviceWaitIdle`)
- [x] Retirement thread-safe: `dispose()` from `create()`, between frames and from loader threads (soak: 4 worker threads)
- [ ] Retirement across multiple windows (single presenter tested)

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
- [ ] 10-minute run in Tier B (fence completion path) on desktop
- [ ] Android device run, Tier A and Tier B (the plan names the Galaxy S20)

---

## Phase 2: Unmodified 2D

### 1. Texture and state shim
- [x] Uploads recorded ahead of the frame's draws in the same submit, whenever they were queued during the frame (separate upload command buffer recorded at `endFrame`)
- [x] Texture calls with lazy image creation, level > 0 uploads, copy-on-write (15 shim tests drive unmodified stock `Texture` and `Pixmap`; both tiers; RTX 5080/2080; lavapipe)
- [~] Format conversion: `RGB888 -> RGBA8888`, `Alpha -> R8_UNORM` swizzle `(0,0,0,R)`, `LuminanceAlpha -> R8G8_UNORM` swizzle `(R,R,R,G)` done; `RGBA4444` and `RGB565` are expanded to RGBA8 (the plan's fallback), packed Vulkan formats where supported are not used yet; `GL_UNPACK_ALIGNMENT` honoured
- [ ] Compressed uploads: ETC2/ASTC on Android, BC on desktop, chosen by format support
- [ ] Ordered state packets for clear, viewport, scissor, enable/disable (the calls are accepted and ignored; viewport is recorded for queries)
- [x] The shim query table (see Core decision 3)
- [x] `VulkanStage`, `VulkanScrollPane`, `Vulkan*Viewport`, `VulkanTextureLoader`, `VulkanPixmapPacker` not carried over (fresh project; stock classes are the goal)

### 2. Command stream
- [ ] Java encodes packets into a direct `ByteBuffer`: `VIEWPORT`, `SCISSOR`, `CLEAR`, `BEGIN_PASS`, `END_PASS`, `BIND_PIPELINE`, `PUSH_CONSTANTS`, `BIND_TEXTURE_SET`, `DRAW_INDEXED`
- [ ] One `GdxVk.submitCommandStream(ptr, bytes)` call per flush; submit once per frame
- [~] Interim: direct `createPipeline`/`draw`/`bindFrameData`/`bindTexture` calls on the presenter (to be replaced by the stream)

### 3. Batches
- [ ] `VulkanSpriteBatch` (implements `Batch`); Tier A slot index per vertex, Tier B flush on texture switch
- [ ] `VulkanPolygonSpriteBatch` (implements `PolygonBatch`)
- [ ] `VulkanShapeRenderer`
- [ ] `VulkanShaderPipelineBundle` replacing custom `ShaderProgram` GLSL
- [ ] Per-frame vertex ring

### Gate 2 (stock tests, both tiers, compared to stock GL on llvmpipe)
- [ ] Scene2D `Table`, `Skin`, clipped `ScrollPane` through `ScissorStack`
- [ ] `BitmapFont`
- [ ] `FreeTypeFontGenerator` with glyphs added mid-frame
- [ ] `TextureAtlas`
- [ ] `ParticleEffect`

### Stress test 2 (vs stock `SpriteBatch` on `gdx-backend-lwjgl3`)
- [ ] `VulkanSpriteBatch` Tier A, 128 textures: >= 3x stock fps
- [ ] `VulkanSpriteBatch` Tier A, 1 texture: >= 1.0x
- [ ] `VulkanSpriteBatch` Tier B, 128 textures and 1 texture: >= 1.0x
- [ ] `VulkanSpriteBatchInstanced`, 1,000,000 sprites moved by compute: 60+ fps on RTX 2080

---

## Phase 3: Stock FrameBuffer

### 1. Framebuffer shim
- [ ] `glBindFramebuffer` emits `END_PASS`, layout barriers, `BEGIN_PASS`; render targets never renamed
- [ ] Offscreen passes use the positive-height viewport and clockwise front face

### 2. Depth and stencil
- [ ] Packed depth-stencil reported supported; separate depth/stencil renderbuffers folded into one image
- [ ] Format by device support: `D24_UNORM_S8_UINT`, else `D32_SFLOAT_S8_UINT`; `DEPTH_COMPONENT16 -> D16_UNORM`
- [ ] Renderbuffers as `TRANSIENT_ATTACHMENT` + `LAZILY_ALLOCATED`, `STORE_OP_DONT_CARE`

### 3. Pass scheduling
- [ ] Offscreen passes recorded before the swapchain pass where stream order allows
- [ ] Explicit load/store ops (`CLEAR` / `DONT_CARE` / `LOAD`)

### 4. Extras
- [ ] `VulkanMultiTargetFrameBuffer` (multiple render targets, float attachments)
- [ ] Tier B render-pass cache keyed by formats and samples

### Gate 3 and stress test 3
- [ ] 2D and 3D scene into a stock `FrameBuffer`, `region.flip(false, true)`, matches stock GL on llvmpipe within tolerance, both tiers
- [ ] Clipped Scene2D UI over four ping-pong bloom/CRT passes on stock `FrameBuffer`s: lavapipe CI, RTX 2080, Galaxy S20
- [ ] Stress: 100,000 draws recorded as a pure C++ loop (floor) vs replayed from Java through the command stream
- [ ] Pass: Java + JNI + C++ recording time <= 1.3x the C++ floor

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
- [ ] `VulkanComputeShader` with automatic barriers `COMPUTE_SHADER -> DRAW_INDIRECT | VERTEX_ATTRIBUTE_INPUT`

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
