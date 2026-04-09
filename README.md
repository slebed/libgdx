# libGDX Vulkan Backend

This is a fork of [libGDX](https://github.com/libgdx/libgdx) that adds a **Vulkan rendering backend** for desktop (Windows/Linux). The goal is to provide a drop-in replacement for `Lwjgl3Application` — same `ApplicationListener`, `SpriteBatch`, `BitmapFont`, `Scene2D`, etc. — powered by Vulkan instead of OpenGL.

Developers get OpenGL-like simplicity by default, with opt-in access to low-level Vulkan when needed.

## Quick Start

Replace `Lwjgl3Application` with `VulkanApplication`:

```java
public class MyGame extends ApplicationAdapter {
    VulkanSpriteBatch batch;
    VulkanTexture texture;

    public void create() {
        batch = new VulkanSpriteBatch();
        texture = new VulkanTexture(Gdx.files.internal("sprite.png"));
    }

    public void render() {
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.begin();
        batch.draw(texture, 100, 100);
        batch.end();
    }
}

// Launch with Vulkan instead of OpenGL
new VulkanApplication(new MyGame(), new VulkanApplicationConfiguration());
```

## What's Implemented

### Core Infrastructure
- **VulkanApplication** — full application lifecycle, multi-window support, GLFW integration
- **VulkanGraphics** — complete `Graphics` interface implementation (FPS, delta time, display modes, monitors, HiDPI, fullscreen, vsync)
- **VulkanBootstrap** — Vulkan instance, physical/logical device selection, VMA allocator, validation layers, debug messenger
- **VulkanSwapchain** — swapchain creation/recreation with depth buffer, render pass management, resume render pass for FBO support
- **VulkanDevice** — logical device wrapper with single-time command execution
- **VulkanDeviceCapabilities** — runtime feature queries (descriptor indexing, timeline semaphores, dynamic rendering, etc.)

### Rendering
- **VulkanSpriteBatch** — streaming vertex upload, texture array batching, descriptor indexing, blend modes
- **VulkanSpriteBatchInstanced** — instanced rendering variant for particles/bullets
- **VulkanTexture** — extends libGDX `Texture`, Pixmap/file loading, mipmap generation, setFilter/setWrap with sampler recreation, in-place reload
- **VulkanFrameBuffer** — off-screen render targets with color + optional depth, LOAD_OP_LOAD resume render pass
- **VulkanPipelineManager** — pipeline caching by render state key, invalidation on swapchain recreation
- **VulkanDescriptorManager** — descriptor pool/set/layout management with partial binding support
- **VulkanShaderManager** — SPIR-V loading + runtime GLSL compilation via Shaderc

### Scene2D / UI
- **VulkanScrollPane** — touch/scroll interaction working
- **VulkanScreenViewport** / **VulkanExtendViewport** — viewport implementations that skip GL viewport calls
- **FreeType font rendering** via VulkanPixmapPacker

### 3D
- **VulkanModelBatch** — basic model rendering pipeline
- **SimpleColorShader** / **SimpleUnlitTextureShader** / **SimpleLitTextureShader** — vertex color, textured, and lit material shaders
- **VulkanMesh** / **VulkanVertexData** / **VulkanIndexData** — GPU buffer management
- **OBJ, G3DJ, G3DB loaders** — model loading with Vulkan texture integration

### Platform
- **VulkanWindow** — GLFW window management, input, resize, focus, iconify callbacks
- **DefaultVulkanInput** — keyboard, mouse, scroll, touch input via GLFW
- **VulkanClipboard** / **VulkanCursor** — clipboard and custom cursor support
- **VulkanGL20Impl** — GL20 compatibility shim (glClear, glActiveTexture, blend state as no-ops where Vulkan handles it natively)
- **VulkanApplicationConfiguration** — mirrors `Lwjgl3ApplicationConfiguration` with Vulkan-specific options

## Test Suite

Tests live in `tests/gdx-tests-vulkan/` and serve as both API verification and usage examples:

| Test | What it demonstrates |
|------|---------------------|
| VulkanClearScreenTest | Minimal Vulkan lifecycle |
| VulkanSpriteBatchTest | Basic sprite rendering |
| VulkanSpriteBatchStressTest | 10k-200k sprites, batcher comparison, frame metrics |
| VulkanSpriteBatchPerformanceTest | Batch performance measurement |
| VulkanSpriteBatchTextureSwitchTest | Multi-texture batch flushing |
| VulkanFreeTypeFontTest | FreeType font generation with VulkanPixmapPacker |
| VulkanScene2dTest | Scene2D UI with scrollpanes |
| Vulkan3DCubeTest | Rotating 3D cube |
| Vulkan3DTexturedCubeTest | Textured 3D cube |
| Vulkan3DLitTexturedCubeTest | Lit + textured 3D cube |
| VulkanBenchmark3dTest | OBJ/G3DJ/G3DB model loading |
| TechDemo_BulletHell | Instanced rendering stress test |

Run tests: `./gradlew :tests:gdx-tests-vulkan:run`

## TODO

### High Priority
- [ ] FBO post-processing pipeline (render to texture, then composite)
- [ ] GL20 compatibility layer — implement `glViewport`, `glScissor`, `glEnable`/`glDisable`
- [ ] Texture atlas loading with VulkanTexture (AssetManager integration)
- [ ] VulkanSkin for Scene2D (TextureAtlas-backed skin loading)

### Medium Priority
- [ ] Multi-window resource sharing (shared pipeline cache, descriptor layouts)
- [ ] Compute shader support
- [ ] MSAA (multi-sample anti-aliasing)
- [ ] Vulkan 1.3 dynamic rendering (remove render pass objects)
- [ ] SpriteBatch custom shader support

### Low Priority / Future
- [ ] Linux testing and validation
- [ ] Vulkan memory budget tracking and reporting
- [ ] Secondary command buffer recording (multi-threaded rendering)
- [ ] Bindless textures
- [ ] Ray tracing extensions (RTX)

## Requirements

- Java 17+
- Vulkan 1.1+ capable GPU and drivers
- LWJGL 3 (included via Gradle)

Tested on: NVIDIA GeForce RTX 2080, Windows 10, Vulkan 1.3.280

## Building

```bash
# Compile the Vulkan backend
./gradlew :backends:gdx-backend-vulkan:compileJava

# Compile and run tests
./gradlew :tests:gdx-tests-vulkan:run
```

## Project Structure

```
backends/gdx-backend-vulkan/    # Vulkan backend implementation (77 classes)
tests/gdx-tests-vulkan/         # Vulkan test suite and examples
tests/gdx-tests-android/assets/data/vulkan/  # Vulkan shaders and test assets
```

## Upstream

This fork is based on [libGDX](https://github.com/libgdx/libgdx) and maintains compatibility with the core `gdx` API. The Vulkan backend is additive — it does not modify the OpenGL backends or core framework behavior.

Licensed under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0.html).
