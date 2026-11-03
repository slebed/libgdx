# libGDX Vulkan Backend Plan v5.0

Nov 3, 2026 · @Stephen Lebed

A standalone `gdx-vulkan` library for stock libGDX `1.14.3+`: one C++ Vulkan core reached through gdx-jnigen, a GL20 shim that makes stock `Texture`, `Mesh`, `FrameBuffer`, `BitmapFont` and Scene2D work unmodified, and Vulkan-native batches for drawing. This version is the build baseline: v4.0 plus the final review fixes.

## At a glance

&#91;embedded content: gdx-vulkan architecture · 3 tiers, 3 platform modules\]

Game code keeps the stock API. The shim turns its GL resource calls into native records and its state calls into stream packets; batches do the drawing; everything crosses JNI into one C++ core shared by every platform.

## Core decisions

These decisions hold across every phase. Changing one later means revisiting the phases built on it.

### 1. Standalone library, no fork

- Depends on upstream `com.badlogicgames.gdx:gdx:1.14.3+`; core `gdx` is never patched.
- Reuses `gdx-backend-lwjgl3` (input, files, OpenAL audio) and `gdx-backend-android` (input, audio, files). The copied `backend/vulkan/audio/*` classes and the unused `jni/VulkanNative.*` bindings are deleted.
- Drops `lwjgl-vulkan`, `lwjgl-vma` and `lwjgl-shaderc`. GLFW stays for desktop windowing only.
- The old LWJGL Vulkan backend in `slebed/libgdx` is frozen and kept for performance comparisons only. It is not the behavioural reference (see 8).

### 2. One pipeline layout per device, two descriptor sets

A set layout with update-after-bind or push-descriptor bindings cannot also hold dynamic buffers (VUID-VkDescriptorSetLayoutCreateInfo-descriptorType-03001 and -flags-00280). Buffers and textures therefore live in separate sets of one fixed `VkPipelineLayout`. Binding Set 0 never disturbs Set 1.

| Slot | Contents | Notes |
| --- | --- | --- |
| Push constants, bytes 0–63 | `mat4 u_transform` | Per draw |
| Push constants, bytes 64–79 | `vec4 u_color` | Tint and opacity |
| Push constants, bytes 80–95 | `ivec4 u_indices` | material offset, diffuse slot, normal slot, flags |
| Push constants, bytes 96–127 | `vec4 u_custom0`, `vec4 u_custom1` | User data; 128 B is the guaranteed minimum |
| Set 0, binding 0 | `UNIFORM_BUFFER_DYNAMIC` | Camera and frame globals, ≤ 16 KB range, includes the pre-rotation matrix |
| Set 0, binding 1 | `STORAGE_BUFFER_DYNAMIC` | Instance transforms, materials, bones, lights |
| Set 1, Tier A | Global `COMBINED_IMAGE_SAMPLER` table | `PARTIALLY_BOUND \| UPDATE_AFTER_BIND \| UPDATE_UNUSED_WHILE_PENDING`, bound once per command buffer |
| Set 1, Tier B | One cached set per texture, or a push descriptor | Rebound on texture change |

- Tier A table size = min(4096, the four update-after-bind sampled-image and sampler limits, per stage and per set).
- Tier A requires all of: `descriptorBindingPartiallyBound`, `descriptorBindingSampledImageUpdateAfterBind`, `descriptorBindingUpdateUnusedWhilePending`, `shaderSampledImageArrayNonUniformIndexing`, `runtimeDescriptorArray`, `dynamicRendering`, `extendedDynamicState`. Missing any one selects Tier B.
- SPIRV-Reflect is a validator only: at shader load, every declared `(set, binding)` and type must exist in this layout, or loading fails with the shader's file name.

### 3. GL20 shim scope

`VulkanGL20` implements the parts of GL that are resource management or ordered state, which is what stock libGDX classes call internally. It does not emulate GL drawing or GLSL programs.

| Group | Calls | Behaviour |
| --- | --- | --- |
| Textures | `glGenTexture`, `glBindTexture`, `glTexImage2D`, `glTexSubImage2D`, `glCompressedTexImage2D`, `glTexParameteri`, `glGenerateMipmap`, `glDeleteTexture`, `glPixelStorei` | GL handle → native image record → Set 1 slot. `VkImage` created lazily, at `glGenerateMipmap` or first draw, once the mip count is known. Supports level > 0 uploads |
| Ordered state | `glClear`, `glClearColor`, `glViewport`, `glScissor`, `glEnable`/`glDisable` (scissor, blend, depth), `glBindFramebuffer` | Encoded as packets in the same command stream as draws, so `ScreenUtils.clear`, `ScissorStack` and `FrameBuffer.begin/end` run in order |
| Buffers | `glGenBuffer`, `glBindBuffer`, `glBufferData`, `glBufferSubData`, `glDeleteBuffer` | GL handle → VMA buffer record. Stock meshes are fed through StockMeshAccess (see 5) |
| Framebuffers | `glGenFramebuffer`, `glFramebufferTexture2D`, `glGenRenderbuffer`, `glBindRenderbuffer`, `glRenderbufferStorage`, `glFramebufferRenderbuffer`, `glCheckFramebufferStatus`, deletes | Attachment records. Binding emits `END_PASS` / `BEGIN_PASS` |
| Queries | `glGetIntegerv`, `glGetFloatv`, `glGetString`, `Graphics.supportsExtension` | A documented table (below). Unknown enums log once and return 0 |

Shim query table (complete list lives in `VulkanGL20Queries.java`):

| Query | Answer |
| --- | --- |
| `supportsExtension("GL_ARB_framebuffer_object")`, `("GL_OES_framebuffer_object")` | true, so `MipMapGenerator` uses `glGenerateMipmap` |
| `supportsExtension("GL_OES_packed_depth_stencil")`, `("GL_EXT_packed_depth_stencil")` | true, so `FrameBuffer` requests one combined depth-stencil buffer |
| `supportsExtension("GL_EXT_texture_filter_anisotropic")` | true when `samplerAnisotropy` is enabled |
| `GL_MAX_TEXTURE_SIZE` | `maxImageDimension2D` |
| `GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT` | `maxSamplerAnisotropy` |
| `GL_VIEWPORT` | The current logical viewport |
| `GL_FRAMEBUFFER_BINDING` | The bound shim FBO handle, 0 for the swapchain |
| `glGetString(GL_VERSION)` | `"OpenGL ES 2.0 gdx-vulkan <version>"`, so `GLVersion` parses as GLES 2.0 |

`Gdx.gl30` is always null. A non-null value would switch stock `Mesh` to VAOs and pull in GL30-only paths. Stock `TextureArray`, float or multi-target `FrameBuffer`, and `Mesh.enableInstancedRendering` are therefore unsupported. Their replacements are `VulkanTextureArray`, `VulkanMultiTargetFrameBuffer`, `VulkanSpriteBatchInstanced` and `VulkanModelBatch` instancing.

### 4. Copy-on-write renaming

GL guarantees a draw sees a texture or buffer as it was when the draw was issued. Uploads are recorded at the start of the frame command buffer, ahead of the draws, so updating something already drawn this frame needs a rename.

- **Trigger:** the resource was referenced by a recorded draw or dispatch since the frame command buffer's last `vkQueueSubmit`. Earlier frames need no rename: the upload's leading barrier orders it after their reads on the same queue.
- **Applies to CPU-side changes only:** `glTexImage2D`, `glTexSubImage2D`, `glBufferData`, `glBufferSubData`, and sampler changes via `glTexParameteri`. Images written by a render pass (FBO attachments, including ping-pong chains) are never renamed. Their hazards are handled by barriers at `BEGIN_PASS` (wait on fragment-shader reads, go to `COLOR_ATTACHMENT_OPTIMAL`) and `END_PASS` (go to `SHADER_READ_ONLY_OPTIMAL`).
- **Full upload:** new image and slot, the GL handle points at the new record, and the old image and slot are retired through the deletion queue.
- **Sub-image upload:** new image and slot, `vkCmdCopyImage` from the old image, then the sub-region copy on top.
- **Sampler change:** a new slot pairing the existing image view with a cached sampler. The old slot is retired.
- Pending uploads are queued per image record, not per GL handle, so an upload made after a rename lands in the new image.
- A renames-per-frame counter is shown in the debug overlay. Heavy FreeType glyph baking can trigger several full-page copies in one frame.

### 5. Stock Mesh access (StockMeshAccess)

Stock `VertexBufferObject` and `IndexBufferObject` call only `glGenBuffer` when constructed. `setVertices` and `setIndices` fill a CPU-side buffer and set `isDirty`. `glBufferData` runs only inside `bind(ShaderProgram)`, which a Vulkan batch never calls. So the batch, not the shim, moves mesh data.

- `StockMeshAccess` caches reflected `Field`s for `bufferHandle` and `isDirty` on `VertexBufferObject`, `VertexBufferObjectSubData` and `IndexBufferObject`.
- At draw time `VulkanModelBatch` reads the handle. If `isDirty` is set, it uploads `mesh.getVerticesBuffer(false)` / `getIndicesBuffer(false)` into the shim's buffer record for that handle (with copy-on-write), then clears `isDirty`.
- `Mesh.dispose()` calls `glDeleteBuffer(handle)`, which releases the native buffer through the shim.
- `VertexDataType.VertexArray` meshes have no GL buffer. They are copied into the per-frame ring on each draw.
- Unit tests pin the field names and types for every supported libGDX version.
- Upstream PR (filed in Phase 5): public `getBufferHandle()` and a dirty or version accessor on both classes. Once merged, the reflection becomes a fallback.

### 6. Coordinate, winding, depth and colour conventions

| Target | Viewport | Front face | `glViewport` / `glScissor` y |
| --- | --- | --- | --- |
| Swapchain | Negative height (`y = h`, `height = -h`) | `COUNTER_CLOCKWISE` | `vkY = targetH - (glY + glH)` |
| Offscreen FBO | Positive height (`y = 0`, `height = h`) | `CLOCKWISE` | `vkY = glY` |

- Offscreen targets keep GL's bottom-up row order, so the stock `region.flip(false, true)` idiom shows FBO contents the right way up. Vulkan's facing formula has the opposite sign to GL's, so the clockwise front face gives the same culling.
- On Tier A the front face is dynamic state. On Tier B it is part of the pipeline-variant key.
- Depth is always remapped from \[-1, 1\] to \[0, 1\] in the vertex shader: `gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w)` from the shared `gdx_vk_common.glsl` include. No extension branch.
- Swapchain formats are `B8G8R8A8_UNORM` or `R8G8B8A8_UNORM`, never sRGB, to match GL gamma.

### 7. Hardware tiers

|  | Tier A | Tier B |
| --- | --- | --- |
| Typical devices | Desktop Vulkan 1.3/1.4, modern Android | Android Vulkan Profile 2022 (Vulkan 1.1/1.2) |
| Rendering | Dynamic rendering | Internal `VkRenderPass` cache built from formats and samples |
| Raster state | Dynamic cull mode, front face, depth test and write | Pipeline variants keyed by state |
| Blend | Pipeline variants (blend is not dynamic without EDS3) | Pipeline variants |
| Textures | Bindless table, no flush on texture switch | Per-texture sets, flush on texture switch |

`VulkanApplicationConfiguration.forceTierB` runs the Tier B path on any device. CI runs every test in both tiers. The Galaxy S20 is tested both natively (likely Tier A on current firmware) and with `forceTierB`.

### 8. Test oracle

- **Compatibility reference = stock GL libGDX.** The pinned Mesa CI image provides llvmpipe (OpenGL) and lavapipe (Vulkan). Each compatibility test runs under stock `gdx-backend-lwjgl3` on llvmpipe and under `gdx-vulkan` on lavapipe. Images are compared with a tolerance (per-channel threshold plus a maximum share of differing pixels).
- **Regression reference = committed lavapipe goldens**, compared exactly. Goldens are regenerated only when the Mesa pin changes.
- Validation and synchronization layers (`VK_LAYER_KHRONOS_validation` with sync validation) run on every CI test; any message fails the build.

## Phase 1a: Extraction, window surfaces and CI

Goal: a clear screen and a triangle from one C++ core on Windows, Linux and Android, with CI gating every change.

1. **Standalone build**
   - Modules: `gdx-vulkan-core` (Java API + `jni/` C++ tree), `gdx-vulkan-desktop` (Windows, Linux), `gdx-vulkan-android`.
   - gdx-jnigen builds the natives for Windows x64, Linux x64/arm64 and Android arm64-v8a/armeabi-v7a/x86\_64.
   - Delete the copied audio classes and `jni/VulkanNative.*`. `Gdx.gl30` is null from the first commit.
2. **Native core boot**
   - Vendor `volk` and `vk_mem_alloc.h`; compile with `VK_NO_PROTOTYPES`, so no Vulkan SDK link libraries are needed.
   - Instance and device creation with tier detection (Core decision 7) and `forceTierB`.
   - UNORM swapchain, negative-height viewport, `beginRendering`/`endRendering` with the render-pass-cache fallback on Tier B.
   - `VK_EXT_debug_utils` names on every native object; RenderDoc markers per batch flush.
   - `VK_ERROR_DEVICE_LOST`, `VK_ERROR_OUT_OF_DATE_KHR`, `VK_ERROR_SURFACE_LOST_KHR` and out-of-memory surface as typed Java exceptions.
3. **Surfaces from native handles**
   - Desktop: `GLFWNativeWin32`, `GLFWNativeX11`, `GLFWNativeWayland` handles passed to `GdxVk.createSurface()`.
   - Android: `ANativeWindow_fromSurface()` from a minimal `SurfaceView` host (hardened in Phase 4).
4. **CI**
   - GitHub Actions on Linux with a version-pinned Mesa image (llvmpipe + lavapipe). This is the required check.
   - Every test runs in Tier A and `forceTierB`, with validation and sync validation enabled.
   - Golden harness: GL-vs-Vulkan tolerance comparison plus exact lavapipe goldens (Core decision 8).
   - Windows (RTX 2080) and Android (Galaxy S20) runs are manual or self-hosted. GitHub's hosted Windows runners have no GPU, and emulator Vulkan is too flaky to gate on.

**Gate 1a:** clear screen and triangle match their goldens in both tiers on lavapipe CI, and render correctly on the RTX 2080 and the Galaxy S20.

## Phase 1b: Memory, layout and caches

Goal: the production resource systems everything else builds on, proven by a 10-minute soak.

1. **Two-set pipeline layout** exactly as in Core decision 2, including the Tier A feature check and the clamp.
2. **Deferred deletion**
   - A `uint64_t frameSerial` increments on every `vkQueueSubmit`. Completion is tracked by a timeline semaphore (Vulkan 1.2+) or a fence per serial (1.1).
   - Retired `VkBuffer`, `VkImage`, `VkImageView`, `VkFramebuffer`, render-pass cache entries and Set 1 slots are tagged with the current serial and freed once `completedSerial >= retirementSerial`.
   - Retirement is thread-safe, so `dispose()` works from `create()`, between frames, across windows and from loader threads.
3. **Allocators**
   - Per-frame dynamic ring for the Set 0 UBO and SSBO (persistently mapped, aligned to `minUniformBufferOffsetAlignment` / `minStorageBufferOffsetAlignment`, grows when exceeded).
   - Mutex-guarded staging ring: any thread can copy bytes in; the render thread records all pending `vkCmdCopyBufferToImage` / `vkCmdCopyBuffer` at the start of the frame command buffer.
4. **Caches**
   - `VkSampler` cache keyed by `(minFilter, magFilter, wrapU, wrapV, maxAnisotropy)`.
   - `VkPipelineCache` saved to disk and pre-warmed at startup.
5. **Shader toolchain**
   - Gradle task runs `glslc` over `.glsl` sources into `.spv` assets; `gdx_vk_common.glsl` carries the depth remap and the layout declarations.
   - SPIRV-Reflect validation at load (Core decision 2).

**Stress test 1b:** allocate, write and dispose 50,000 buffer sub-allocations and 2,000 small textures per second while rendering at 60 fps, including disposals from background threads and repeated filter changes.

**Pass:** flat VMA usage and a bounded sampler count over 10 minutes, zero validation or sync messages, clean shutdown. Measured on the RTX 2080 and the Galaxy S20 in both tiers.

## Phase 2: Unmodified 2D

Goal: stock libGDX 2D code runs unchanged on `VulkanSpriteBatch`, with no casts and no `Vulkan*` wrappers.

1. **Texture and state shim** (Core decision 3)
   - Texture calls with lazy image creation, level > 0 uploads, and copy-on-write (Core decision 4).
   - Format conversion during staging: `RGB888` → `RGBA8888`; `Alpha` → `R8_UNORM` with swizzle `(0,0,0,R)`; `LuminanceAlpha` → `R8G8_UNORM` with swizzle `(R,R,R,G)`; `RGBA4444` and `RGB565` to packed formats where supported, otherwise expanded.
   - Compressed uploads: ETC2/ASTC on Android, BC on desktop, chosen by format support.
   - Ordered state packets for clear, viewport, scissor and enable/disable.
   - The query table from Core decision 3.
   - Delete `VulkanStage`, `VulkanScrollPane`, the `Vulkan*Viewport` classes, `VulkanTextureLoader` and `VulkanPixmapPacker`.
2. **Command stream**
   - Java encodes packets into a direct `ByteBuffer`: state (`VIEWPORT`, `SCISSOR`, `CLEAR`, `BEGIN_PASS`, `END_PASS`) and draws (`BIND_PIPELINE`, `PUSH_CONSTANTS`, `BIND_TEXTURE_SET`, `DRAW_INDEXED`).
   - One `GdxVk.submitCommandStream(ptr, bytes)` call per flush replays them natively. This only records; `vkQueueSubmit` happens once per frame.
3. **Batches**
   - `VulkanSpriteBatch` (implements `Batch`) and `VulkanPolygonSpriteBatch` (implements `PolygonBatch`) write into the per-frame vertex ring and resolve textures via `texture.getTextureObjectHandle()`.
   - Tier A: the slot index travels per vertex, so texture switches never flush.
   - Tier B: a texture switch flushes and emits `BIND_TEXTURE_SET`.
   - `VulkanShapeRenderer` replaces `ShapeRenderer`. Custom `ShaderProgram` GLSL is replaced by `VulkanShaderPipelineBundle`. These are the documented 2D exceptions.

**Gate 2:** unmodified stock tests in both tiers, compared against stock GL on llvmpipe: Scene2D (`Table`, `Skin`, clipped `ScrollPane` through `ScissorStack`), `BitmapFont`, `FreeTypeFontGenerator` with glyphs added mid-frame, `TextureAtlas`, `ParticleEffect`.

**Stress test 2:** sprites drawn from 128 un-atlased textures, measured against stock `SpriteBatch` on `gdx-backend-lwjgl3` on the same machine.

| Path | Workload | Target |
| --- | --- | --- |
| `VulkanSpriteBatch`, Tier A | 128 textures | ≥ 3× stock GL fps |
| `VulkanSpriteBatch`, Tier A | 1 texture | ≥ 1.0× stock GL fps |
| `VulkanSpriteBatch`, Tier B | 128 textures and 1 texture | ≥ 1.0× stock GL fps |
| `VulkanSpriteBatchInstanced` | 1,000,000 sprites moved by compute | 60+ fps on RTX 2080 |

## Phase 3: Stock FrameBuffer

Goal: stock `com.badlogic.gdx.graphics.glutils.FrameBuffer`, and post-processing libraries built on it, run unmodified with GL's orientation.

1. **Framebuffer shim** (Core decision 3)
   - `glBindFramebuffer` emits `END_PASS`, the layout barriers, and `BEGIN_PASS` into the stream. Render-target images are never renamed (Core decision 4).
   - Offscreen passes use the positive-height viewport and clockwise front face; the swapchain keeps negative height and counter-clockwise (Core decision 6).
2. **Depth and stencil**
   - Packed depth-stencil is reported as supported, so `FrameBuffer` requests one `GL_DEPTH24_STENCIL8_OES` renderbuffer. Separate depth and stencil renderbuffers on one FBO are folded into a single combined image, since dynamic rendering requires one image view for both.
   - Format by device support: `D24_UNORM_S8_UINT`, else `D32_SFLOAT_S8_UINT` (AMD desktop lacks D24S8). `GL_DEPTH_COMPONENT16` → `D16_UNORM`.
   - Renderbuffers can never be sampled, so they are created as `TRANSIENT_ATTACHMENT` with `LAZILY_ALLOCATED` memory where available and `STORE_OP_DONT_CARE`. Mobile GPUs keep that depth in on-chip memory with no user API.
3. **Pass scheduling**
   - Offscreen passes recorded before the swapchain pass begins wherever stream order allows, to avoid suspending and resuming the main pass on mobile GPUs.
   - Explicit load/store ops: `CLEAR` when the pass starts with a full clear, `DONT_CARE` when nothing reads the old contents, `LOAD` otherwise.
4. **Vulkan-only extras:** `VulkanMultiTargetFrameBuffer` for multiple render targets and float attachments (stock versions need GL30).

**Gate 3:**

- Render a 2D and a 3D scene into a stock `FrameBuffer`, draw it with `region.flip(false, true)`, and match stock GL on llvmpipe within tolerance, in both tiers.
- A clipped Scene2D UI over four ping-pong bloom/CRT passes on stock `FrameBuffer`s: lavapipe CI, RTX 2080, Galaxy S20.

**Stress test 3:** record 100,000 draws (push constants, cull state, `vkCmdDrawIndexed`) once as a pure C++ loop to set the floor, then replay the same draws from Java through the command stream.

**Pass:** Java + JNI + C++ recording time ≤ 1.3× the C++ floor on the same machine.

## Phase 4: Android lifecycle

Goal: survive every Android lifecycle event without losing resources, before any 3D work lands on top.

1. **`VulkanAndroidApplication`**
   - Reuses `AndroidInput`, `AndroidAudio` and `AndroidFiles`, with its own `SurfaceView` implementing `SurfaceHolder.Callback2`.
   - `surfaceDestroyed`: wait for the queue, destroy only `VkSwapchainKHR` and `VkSurfaceKHR`. The device, VMA allocations and every shim texture, buffer and FBO stay resident, so there is no managed-texture reload as on GL.
   - `surfaceCreated` / `surfaceChanged`: recreate surface and swapchain.
2. **Pre-rotation**
   - Set `preTransform` to `currentTransform` so the compositor skips its rotation pass.
   - The backend applies the matching rotation in clip space through the frame UBO, after the user's projection. User cameras and offscreen passes are untouched.
   - `Gdx.graphics.getWidth/Height`, `glViewport` and `glScissor` stay in logical orientation; the shim rotates the rectangles for the swapchain.
3. **Frame pacing and memory:** `VK_KHR_swapchain_maintenance1` where available, Android Frame Pacing (Swappy), `onTrimMemory()` releasing idle staging memory.
4. **`VulkanAsyncTextureLoader`** for `AssetManager`: worker threads decode files and copy pixels into staging-ring slices. The render thread records the copies at the start of the next frame, so no second queue or ownership transfer is involved.

**Gate 4:** all Phase 1–3 tests on a physical device with pre-rotation active, confirmed in Android GPU Inspector.

**Stress test 4:** an ADB script rotates portrait/landscape, backgrounds and foregrounds, and toggles split-screen 500 times in a row while `VulkanAsyncTextureLoader` streams textures.

**Pass:** zero crashes, zero lost textures, flat native heap, swapchain recreation under 50 ms.

## Phase 5: 3D with stock models

Goal: stock `Mesh`, `ModelBuilder`, `G3dModelLoader`, `Model` and `ModelInstance` render through `VulkanModelBatch`, with GPU-driven instancing and clustered lighting.

1. **Mesh data**
   - Buffer shim calls with copy-on-write (Core decision 3).
   - `StockMeshAccess` uploads dirty stock meshes at draw time (Core decision 5).
   - File the upstream libGDX PR adding a public buffer handle and dirty accessor.
2. **`VulkanModelBatch`**
   - Accepts stock `RenderableProvider`s. A `VulkanShaderProvider` maps each `Renderable`'s `VertexAttributes` and `Material` to a cached `VulkanShaderPipelineBundle`.
   - Lights, materials and bone matrices live in the Set 0 SSBO.
   - Renderables are sorted by pipeline, then mesh, then material. Repeated meshes become instanced or `vkCmdDrawIndexedIndirect` draws reading transforms from the SSBO.
3. **Lighting and shadows**
   - Tiled/clustered light culling in compute for point and spot lights.
   - Directional shadow cascades; up to 4 point-light cubemap shadow casters on desktop, 1 directional cascade on mobile.
4. **`VulkanComputeShader`** with automatic barriers from `COMPUTE_SHADER` to `DRAW_INDIRECT | VERTEX_ATTRIBUTE_INPUT`.

**Gate 5:** an animated `knight.g3db` loaded with unmodified `G3dModelLoader`, plus a `ModelBuilder` scene, with normal mapping, skeletal animation and directional shadows, compared against stock GL within tolerance (shadows use their own lavapipe golden, since they are new rendering with no stock equivalent).

**Stress test 5:** 250,000 instanced, textured meshes moved by a compute boids simulation that writes transforms and indirect draw commands in GPU memory, lit by 500 point lights culled in compute.

**Pass:** 60+ fps on the RTX 2080; 50,000 instances and 64 lights at 60 fps on the Galaxy S20; zero Java allocations per frame.

## Phase 6 (stretch): macOS and iOS

Starts only once desktop and Android pass every gate above.

- macOS: `CAMetalLayer` from `GLFWNativeCocoa` into `vkCreateMetalSurfaceEXT`, with `VK_KHR_portability_enumeration` and `VK_KHR_portability_subset`.
- iOS: a `UIView` backed by `CAMetalLayer` through RoboVM/MobiVM. The FBO shim must answer `GL_FRAMEBUFFER_BINDING`, which `GLFrameBuffer` queries on iOS.
- Drivers: MoltenVK, and Mesa's KosmicKrisp (Vulkan 1.4 conformant) as the higher-tier option.

## Changes from v4.0

| Area | v4.0 | v5.0 |
| --- | --- | --- |
| Stock `Mesh` data | Address map populated in `glBufferData` | `StockMeshAccess` uploads dirty meshes at draw time; stock VBOs only call `glBufferData` inside `bind(ShaderProgram)`, which never runs |
| Copy-on-write scope | Textures and buffers, scope unstated | CPU-side updates only; render targets use barriers and are never renamed |
| Pending uploads | Per GL handle | Per image record, so post-rename uploads hit the new image |
| FBO depth-stencil | Not specified | Packed depth-stencil reported; separate buffers folded into one image; D24S8 → D32S8 fallback; transient lazily allocated renderbuffers |
| Shim queries | Two extension strings | Documented query table (`supportsExtension`, `glGetIntegerv`, `glGetFloatv`, `glGetString`) |
| Oracle | Frozen LWJGL Vulkan fork | Stock GL on llvmpipe vs `gdx-vulkan` on lavapipe, with tolerance; the fork kept for performance only |
| Tier A requirements | Descriptor-indexing features | Adds `runtimeDescriptorArray`, `dynamicRendering`, `extendedDynamicState` |
| Debugging | — | Renames-per-frame counter in the debug overlay |
| Upstream | — | PR for a public buffer handle and dirty accessor (Phase 5) |
