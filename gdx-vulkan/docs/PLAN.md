# gdx-vulkan implementation plan

The authoritative plan is **libGDX Vulkan Backend Plan v5.0**. Its history, with the review for each revision, is in the
"libGDX Vulkan" Claude Project (`plans/vulkan_backend_implementation_plan_{2.0..5.0}.md`, `claude/vulkan-plan-v*-review.md`).

## Shape (one paragraph)
Standalone library on stock libGDX 1.14.3. A C++ Vulkan core (volk + VMA) behind a command-stream ByteBuffer replayed by one JNI
call per flush. A `GL20` shim (textures, buffers, FBOs, ordered state) lets stock `Texture`, `Mesh`, `FrameBuffer`, `BitmapFont`
and Scene2D run unmodified; `Gdx.gl30` is always null. Vulkan-native batches (`VulkanSpriteBatch`, `VulkanPolygonSpriteBatch`,
`VulkanShapeRenderer`, `VulkanModelBatch`) do the drawing. Tier A = bindless textures + dynamic rendering (core Vulkan 1.3);
Tier B = render-pass cache + per-texture descriptors; `forceTierB` runs it anywhere.

## Phases
| Phase | Scope | Status |
|---|---|---|
| 1a | Standalone build, volk/VMA, device + tier selection, Win32/X11/Android surface, clear + triangle, lavapipe CI + validation | **Done except**: CI unrun on GitHub, device is a REDMAGIC NX809J not the S20, Android arm64 only |
| 1b | Deletion queue, rings, Set 0/Set 1 layout, pipeline cache, reflection validation, soak test | |
| 2 | Unmodified 2D (SpriteBatch path, BitmapFont, Scene2D) | |
| 3 | Stock `FrameBuffer` via the FBO shim | |
| 4 | Android lifecycle, pre-rotation, async streamer | |
| 5 | 3D with stock models (`StockMeshAccess`, `VulkanModelBatch`) | |
| 6 | Apple (MoltenVK), stretch | |

## Phase 1a decisions taken while implementing
- **Natives use plain CMake + hand-written JNI**, not gdx-jnigen, until its cross-compile support is verified. The JNI surface is three functions.
- **Tier A requires core Vulkan 1.3.** Vulkan 1.2 + extensions devices run Tier B for now; revisit with real device data.
- Tier decision is a pure function (`gdxvk_tier.h`) with table-driven tests that need no GPU.
- `vkCreateInstance` returning `VK_ERROR_INCOMPATIBLE_DRIVER` is reported as `VulkanDeviceException` (no ICD), not an init failure.
