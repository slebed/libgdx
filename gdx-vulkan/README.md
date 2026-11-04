# gdx-vulkan

A Vulkan renderer for [libGDX](https://libgdx.com). It draws with Vulkan instead of OpenGL, on **unmodified libGDX** (1.14.2): no fork, no patched jars.
Your `Texture`, `Pixmap`, `FrameBuffer`, `BitmapFont`, Scene2D `Stage`, `ModelBatch` code and the rest of the libGDX API keep working; you swap the
renderer underneath them.

## Why

- **Speed where OpenGL is slow.** Switching textures is cheap in Vulkan. A scene drawn from many separate textures runs up to 24 times faster than stock libGDX
  on the same machine (see [Performance](#performance)), and a game that already uses atlases still gains 20%.
- **The GPU can do work OpenGL could not give you.** Compute shaders, indirect draws and GPU-side culling are available from libGDX: one million sprites
  moved by a compute shader at 722 frames per second on a 2018 graphics card; 250,000 lit 3D meshes at 495.
- **No fork to maintain.** gdx-vulkan implements libGDX's own `GL20` interface on top of Vulkan, so the stock classes run as they are. When libGDX is updated,
  you update libGDX.
- **Android that survives the lifecycle.** The Vulkan surface can be destroyed and recreated (rotation, background, foreground) without reloading a single
  texture: recreation takes about 2 ms on the phone we test with. There is no "managed texture" reload.

## Status

Beta. Everything below is built and tested against stock libGDX on OpenGL, with the Vulkan validation layers on.

| Works today | |
|---|---|
| 2D | `SpriteBatch`, `PolygonSpriteBatch`, `ShapeRenderer`, `BitmapFont`, Scene2D, `TextureAtlas`, particles, compressed textures (ETC2, ASTC, ...), custom shaders (as SPIR-V) |
| Framebuffers | the stock `FrameBuffer` (colour, depth, stencil), multiple and floating-point colour attachments |
| 3D | `ModelBatch` with `ModelInstance`, `Environment`, directional and point lights, textures, normal maps, fog, skeletal animation, blending, shadow maps (one, or cascaded), instancing; models from `G3dModelLoader` or `ModelBuilder` |
| GPU-driven | compute shaders, GPU culling, indirect drawing, clustered lighting for hundreds of lights |
| Platforms | Windows x64, Linux x64 (X11), Android 10+ (arm64) |
| Application | a desktop `Application` (`VulkanDesktopApplication`) and an Android one (`VulkanAndroidApplication`), with stock input, audio and files |

| Not yet | |
|---|---|
| macOS and iOS | needs a Mac to build and test; not started |
| Wayland | X11 only on Linux |
| Custom GLSL at run time | shaders are compiled to SPIR-V at build time; `new ShaderProgram(vertexSource, fragmentSource)` is not supported. **In progress:** a `VulkanShaderProgram` that takes your existing libGDX GLSL and translates it to Vulkan at run time (tracked in `docs/CHECKLIST.md`, "Phase 5b") |
| Direct OpenGL drawing | `glDrawArrays`, `glUseProgram`, `Mesh.render(ShaderProgram)` and similar throw an exception that says which Vulkan class to use |
| Spot lights, point-light shadows | libGDX's own default shader has neither |
| Maven Central | not published yet; see [Getting it](#getting-it) |

The item-by-item list, including what is partial and why, is in [`docs/CHECKLIST.md`](docs/CHECKLIST.md) (160 of 208 items done).

**Tested on** an NVIDIA RTX 5080 and RTX 2080 (Windows), a software Vulkan driver (lavapipe, on Linux, in CI on every commit), and a REDMAGIC phone with an
Adreno 840 (Android 16). Other GPUs and drivers should work but have not been tried.

## Performance

Measured against stock libGDX 1.14.2 on OpenGL, on the same machine, same sprites, vsync off, 800x600. Reproduce with the commands in
[`docs/TESTING.md`](docs/TESTING.md).

| Test | Stock libGDX (OpenGL) | gdx-vulkan | |
|---|---|---|---|
| 20,000 sprites, each from one of **128 separate textures** (no atlas), RTX 5080 | 17.5 fps | 423 fps | **24x** |
| 20,000 sprites, 128 separate textures, on drivers limited to Vulkan 1.1 | 17.5 fps | 61-70 fps | 3.5-4x |
| 20,000 sprites, **one texture** (an atlas), RTX 5080 | 1,064 fps | 1,306 fps | 1.2x |
| 1,000,000 sprites moved by a compute shader, RTX 2080 | not possible | 722 fps | |
| 1,000,000 sprites moved by a compute shader, RTX 5080 / Adreno 840 phone | not possible | 1,879 fps / 208-225 fps | |
| 250,000 instanced 3D meshes moved by compute, culled on the GPU, 500 point lights, RTX 2080 | not possible | 495 fps (1% low: 259) | |
| The same on the phone, 1216x2688 | not possible | 52 fps; 781 fps with 50,000 meshes and 64 lights | |

How to read these:
- The first row is the case Vulkan helps most: every sprite switches texture. A game that packs its art into atlases is closer to the third row, about 1.2x.
  The renderer started out slower than stock OpenGL on that row (0.91x) until vertex data got a copy in GPU memory.
- Stock OpenGL cannot run the compute rows at all, so there is nothing to compare; they show what the extra control buys.
- The Java layer is not the bottleneck: recording 100,000 draws through the Java API costs 1.0-1.3x a hand-written C++ loop, and the 3D benchmark allocates about 1
  byte of Java garbage per frame.
- The phone numbers are gdx-vulkan only. The same sprite test on the phone runs at 548 fps with one texture and 38 fps with 128; a stock OpenGL run on the phone
  has not been done yet.
- Android lifecycle: 50 rotate and background/foreground cycles with textures streaming in and out, 0 failures, surface recreation 2 ms on average (5 ms worst),
  memory flat.

Rendering matches stock libGDX: the test scenes are drawn by stock OpenGL and by gdx-vulkan and compared pixel by pixel. The framebuffer scenes are identical;
the 2D and 3D scenes differ in at most 0.2% of pixels, at the ends of lines and edges.

## Getting it

gdx-vulkan is not on Maven Central yet. For now, add it to your Gradle build as a source dependency:

```
git clone https://github.com/slebed/gdx-vulkan
```
```groovy
// settings.gradle of your project
includeBuild('../gdx-vulkan')

// build.gradle of your project
dependencies {
    implementation 'io.github.slebed.gdx-vulkan:gdx-vulkan-core'
    implementation 'io.github.slebed.gdx-vulkan:gdx-vulkan-desktop'   // the desktop Application, GLFW and OpenAL (desktop only)
}
```

Building needs a C++17 compiler, CMake 3.20+ and `glslc` (see [`docs/TESTING.md`](docs/TESTING.md)); the build compiles the native library for your machine's platform and bundles it into the jar, and compiles the shaders. At run time you
need a GPU and driver with Vulkan 1.1 or newer (1.3 for the fastest path). Android uses its own Gradle build in `gdx-vulkan-android/`; see its README.

## Using it

### Desktop

The change from a stock libGDX desktop game is the application class and the batch:

```java
// Before: stock libGDX
public class MyGame extends ApplicationAdapter {
    SpriteBatch batch;
    Texture img;

    @Override public void create() {
        batch = new SpriteBatch();
        img = new Texture("badlogic.jpg");
    }

    @Override public void render() {
        ScreenUtils.clear(0, 0, 0, 1);
        batch.begin();
        batch.draw(img, 0, 0);
        batch.end();
    }
}

public static void main(String[] args) {
    new Lwjgl3Application(new MyGame(), new Lwjgl3ApplicationConfiguration());
}
```

```java
// After: gdx-vulkan
public class MyGame extends ApplicationAdapter {
    VulkanSpriteBatch batch;                                       // was SpriteBatch
    Texture img;                                                   // unchanged: the stock Texture

    @Override public void create() {
        batch = new VulkanSpriteBatch();                           // was new SpriteBatch()
        img = new Texture("badlogic.jpg");
    }

    @Override public void render() {                               // unchanged
        ScreenUtils.clear(0, 0, 0, 1);
        batch.begin();
        batch.draw(img, 0, 0);
        batch.end();
    }
}

public static void main(String[] args) {
    new VulkanDesktopApplication(new MyGame(), new Lwjgl3ApplicationConfiguration());   // was Lwjgl3Application; the configuration is unchanged
}
```

`VulkanSpriteBatch` implements the stock `Batch` interface, so anything that takes a `Batch` takes it: `BitmapFont.draw`, `ParticleEffect.draw`, `Image`, and
so on. The exception is `new Stage(viewport)`, which makes a stock `SpriteBatch` for itself and cannot run here; use `new VulkanStage(viewport)`, the same `Stage`.

A game's existing `Lwjgl3ApplicationConfiguration` is used as it is: window size and title, vsync, resizable, fullscreen, audio and preferences settings (the
OpenGL context settings are ignored). `VulkanDesktopConfiguration` holds the same settings in a class of ours, all optional.

The batches find the running application's frame through `Gdx.graphics`, so create them in `create()` or later, as you would stock ones. If you build the
Vulkan context yourself (a test, a tool), pass the frame explicitly: `new VulkanSpriteBatch(frame)`.

### Moving an existing game over

| Stock libGDX | gdx-vulkan | Notes |
|---|---|---|
| `Lwjgl3Application` | `VulkanDesktopApplication` | `Gdx.graphics`, `Gdx.input`, `Gdx.files`, `Gdx.audio`, `Gdx.net` are all set as usual |
| `AndroidApplication` | `VulkanAndroidApplication` | see below |
| `SpriteBatch` | `VulkanSpriteBatch` | a `Batch`: pass it to `Stage`, fonts, particles. Same constructors: `()` and `(size)` |
| `PolygonSpriteBatch` | `VulkanPolygonSpriteBatch` | |
| `ShapeRenderer` | `VulkanShapeRenderer` | |
| `ModelBatch` | `VulkanModelBatch` | same `begin / render(instances, environment) / end` |
| `Stage` | `VulkanStage` | the stock `Stage` with a `VulkanSpriteBatch`; same constructors |
| `Texture`, `Pixmap`, `TextureAtlas`, `FrameBuffer`, `Mesh`, `BitmapFont`, `Skin`, `AssetManager`, `Model`, `Environment`, `Camera` and the Scene2D actors | **unchanged** | these run on the shim as they are |
| `ShaderProgram` (GLSL source) | SPIR-V shader bundle (`VulkanShaderPipelineBundle`) | write GLSL as before, compile it with `glslc` at build time; the batches' own shaders in `gdx-vulkan-core/src/main/shaders` are the examples |
| `Gdx.gl.glDrawArrays`, `glUseProgram`, ... | not supported | use the batches, or a compute shader |

Rules that carry over from stock libGDX: dispose textures and batches yourself; do not dispose a texture between drawing with it and the batch's `end()`.
Stock batches keep the projection they were created with, so on a window resize update it the way you already do.

### Android

```java
public class MyActivity extends VulkanAndroidApplication {          // was AndroidApplication
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        initialize(new MyGame());
    }
}

// in MyGame.create(), exactly as on the desktop:
batch = new VulkanSpriteBatch();
```

Declare `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|keyboard|density"` on the activity so a rotation arrives
as a surface change. The batches stay valid for the life of the app; the swapchain behind them is replaced whenever the surface is, and textures and
buffers survive that. Pre-rotation (the shaders, not the compositor, rotate the image) is on by default, and `Gdx.graphics.getWidth/Height`, `glViewport` and
`glScissor` stay in the logical orientation. Frames are paced with Android Frame Pacing (Swappy) when vsync is on.

### 3D

```java
VulkanModelBatch batch = new VulkanModelBatch();                   // the stock ModelBatch API
Environment env = new Environment();
env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.3f, 0.3f, 0.35f, 1f));
env.add(new DirectionalLight().set(1f, 1f, 0.9f, -0.5f, -1f, -0.3f));

Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
batch.begin(camera);
batch.render(instances, env);                                      // ModelInstance, Environment, animation: as stock
batch.end();
```
The screen gets a depth buffer when the first `VulkanModelBatch` is created (create it in `create()`), so there is no setup call. Shadows use the stock
`DirectionalShadowLight`, or `VulkanCascadedShadowLight` for several shadow maps over distance.

### Compute and GPU-driven rendering

This is the part with no OpenGL equivalent in libGDX:

```java
VulkanGpuInstances gpu = new VulkanGpuInstances(mesh, texture, 250_000, 500, userUints);
gpu.setInstances(data, count);               // position + scale, rotation, and 4 floats of your own state per instance
gpu.setLights(lights, lightCount);           // position + radius, colour + intensity
new VulkanComputeShader("boids", "boids.comp").dispatch(groups, push, gpu.buffer());   // your simulation, on the GPU
gpu.render(camera, width, height);           // culls, sorts the lights into clusters, draws once
```
The transforms never leave GPU memory. `Stress5Main` is a complete program; `VulkanSpriteBatchInstanced` does the same for a million sprites.

## How it works

gdx-vulkan implements libGDX's `GL20` interface on Vulkan for everything that is not drawing: textures, framebuffers, buffers, state. The drawing classes
(`VulkanSpriteBatch` and the others) are generated from libGDX's own source, so their behaviour is the stock behaviour. A small C++ core owns the Vulkan objects
(memory, descriptors, pipelines, a pipeline cache, deferred deletion). Java records draw commands into an array and hands them over once per frame, so the Java
side allocates almost nothing. The validation layers, a stock-OpenGL comparison and a leak/hazard soak test run in CI on every commit.

`docs/PLAN.md` is the full design.

## Layout
- `gdx-vulkan-core/` the Java API and the C++ core (`jni/`: CMake, with volk, VMA, SPIRV-Reflect and the Vulkan headers vendored); shaders in `src/main/shaders`
- `gdx-vulkan-desktop/` the desktop `Application`, plus the desktop tests and benchmarks
- `gdx-vulkan-android/` the Android library and a test app (its own Gradle build)
- `ci/`, `.github/` the Docker image and workflow that run every test on a software Vulkan driver
- `tools/` generators that copy code from stock libGDX (so the comparison stays honest)
- `test-assets/` libGDX's own test assets, used by the scene tests (see `NOTICE`)
- `docs/` [`PLAN.md`](docs/PLAN.md) the design, [`CHECKLIST.md`](docs/CHECKLIST.md) the detailed status, [`TESTING.md`](docs/TESTING.md) building, testing and benchmarking,
  [`RELEASE.md`](docs/RELEASE.md) the manual checks before a release

## Known limitations
- Custom shaders must be precompiled to SPIR-V (a bundle of vertex and fragment shaders); there is no run-time GLSL compilation. A custom shader that
  rotates its own output must call `gdx_vk_clip()` like the bundled ones, for Android pre-rotation.
- 3D draws with the port of libGDX's default shader (plus normal maps). Custom 3D `Shader` and `ShaderProvider` classes are not accepted.
- A few features need a driver with Vulkan 1.3: shadow maps and specular, emissive and normal-map textures. On a Vulkan 1.1 driver they are skipped.
- On Android, the on-screen keyboard through `Gdx.input` does not work yet.
- Natives are built for Windows x64, Linux x64 and Android arm64. Android armeabi-v7a and x86_64 compile but have never been run.
- Offscreen framebuffer passes recorded after a draw to the screen keep their order: a framebuffer pass in the middle of a frame interrupts the main pass.

## Contributing and licence
Issues and pull requests are welcome; run the tests first (`docs/TESTING.md`). Apache License 2.0, see `LICENSE`. Third-party code is vendored in
`gdx-vulkan-core/jni/vendor` (volk, Vulkan-Headers, Vulkan Memory Allocator, SPIRV-Reflect), each under its own permissive licence; see `NOTICE`.
