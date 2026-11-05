# gdx-vulkan implementation plan

The authoritative plan is **libGDX Vulkan Backend Plan v5.0**. Its history, with the review for each revision, is in the
"libGDX Vulkan" Claude Project (`plans/vulkan_backend_implementation_plan_{2.0..5.0}.md`, `claude/vulkan-plan-v*-review.md`).

## Shape (one paragraph)
Standalone library on stock libGDX (1.14.2 today; 1.14.3 when it is published). A C++ Vulkan core (volk + VMA) behind a command-stream ByteBuffer replayed by one JNI
call per flush. A `GL20` shim (textures, buffers, FBOs, ordered state) lets stock `Texture`, `Mesh`, `FrameBuffer`, `BitmapFont`
and Scene2D run unmodified; `Gdx.gl30` is always null. Vulkan-native batches (`VulkanSpriteBatch`, `VulkanPolygonSpriteBatch`,
`VulkanShapeRenderer`, `VulkanModelBatch`) do the drawing. Tier A = bindless textures + dynamic rendering (core Vulkan 1.3);
Tier B = render-pass cache + per-texture descriptors; `forceTierB` runs it anywhere.

## Phases
| Phase | Scope | Status |
|---|---|---|
| 1a | Standalone build, volk/VMA, device + tier selection, Win32/X11/Android surface, clear + triangle, lavapipe CI + validation | **Done except**: CI unrun on GitHub, device is a REDMAGIC NX809J not the S20, Android arm64 only |
| 1b | Deletion queue, rings, Set 0/Set 1 layout, pipeline cache, reflection validation, soak test | **Implemented**; verified on RTX 5080/2080 and lavapipe (15-60 s soaks). Pending: 10-minute soak, Android device run |
| 2 | Unmodified 2D (SpriteBatch path, BitmapFont, Scene2D) | |
| 3 | Stock `FrameBuffer` via the FBO shim | |
| 4 | Android lifecycle, pre-rotation, async streamer | |
| 5 | 3D with stock models (`StockMeshAccess`, `VulkanModelBatch`) | |
| 6 | Apple (MoltenVK), stretch | |

## What was built differently from the plan (as of 2026-11)
The plan above is the original design; the code differs in these ways, and `CHECKLIST.md` is the authority on what exists.
- **Stock classes run with no rename.** The plan had the Vulkan-native batches do all drawing and left stock `SpriteBatch`, `ShapeRenderer`, `Stage` and `ModelBatch` unsupported. The GL shim now also serves
  programs, uniforms, attributes and `glDrawElements`/`glDrawArrays` (`gl/StockPrograms`, one vertex buffer per draw), so unmodified libGDX code draws. The `Vulkan...` classes stay as the fast path.
  `Gdx.gl30` and `gl31` are still null.
- **A run-time GLSL translator** (Phase 5b, `docs/SHADER_TRANSLATOR.md`): libGDX's old GLSL is translated to Vulkan GLSL and compiled with a vendored glslang, cached on disk by source hash, off the render thread
  where possible. libGDX's own sprite, shape and model shaders never reach it: they are recognised by a hash of their source (`glsl/KnownShaders`) and replaced by dedicated shaders, and the programs
  `DefaultShader` and `DepthShader` ask for are compiled when the library is built (`resources/shaders/bundle`).
- **The GPU-driven path is larger than planned**: `VulkanComputeShader`, `VulkanGpuInstances` (GPU culling, indirect draws, clustered lights), `VulkanSpriteBatchInstanced`, cascaded shadows
  (`VulkanCascadedShadowLight`).
- **Applications:** `VulkanDesktopApplication` (GLFW, a generated copy of the stock input) and `VulkanAndroidApplication` (with Swappy frame pacing, `VK_KHR_swapchain_maintenance1` present fences, an on-screen keyboard).
- **GPU time per frame** comes from timestamp queries in the presenter (`GdxVkPresenter.gpuFrameNanos()`), not planned.
- **Demos and comparisons** (`gdx-vulkan-demos`, the Android demo app, `tools/compare-*.sh`, `tools/energy-phone.sh`) measure the library against stock libGDX on the same machine; the README reports the cases it loses.
- **Driver findings drove design:** a push constant must not address a vertex-shader buffer read (Adreno 650); a bindless index must not come through a varying (Adreno 840); see `DRIVER_NOTES.md`.
- **The repository** is `slebed/gdx-vulkan`, split out of the fork `slebed/libgdx`; CI runs lavapipe in both tiers and builds the arm64 natives.

## Phase 1a decisions taken while implementing
- **Natives use plain CMake + hand-written JNI**, not gdx-jnigen, until its cross-compile support is verified. The JNI surface is three functions.
- **Tier A requires core Vulkan 1.3.** Vulkan 1.2 + extensions devices run Tier B for now; revisit with real device data.
- Tier decision is a pure function (`gdxvk_tier.h`) with table-driven tests that need no GPU.
- `vkCreateInstance` returning `VK_ERROR_INCOMPATIBLE_DRIVER` is reported as `VulkanDeviceException` (no ICD), not an init failure.
