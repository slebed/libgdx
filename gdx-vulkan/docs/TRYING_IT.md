# Trying gdx-vulkan

For testers of a pre-release. gdx-vulkan draws a stock libGDX 1.14 game with Vulkan instead of OpenGL. Nothing in libGDX is patched: you add two jars (or an AAR),
change the application class and, for the fast path, the batch. Reports of what broke are what a pre-release is for.

## What you need
- libGDX **1.14.2** (the same `gdx` version the release was built against; it is named in the release notes).
- A GPU and driver with **Vulkan 1.1 or newer**. Vulkan 1.3 gets the modern path (bindless textures, dynamic rendering); 1.1 and 1.2 get the compatibility path, which draws the same picture.
- Platforms in the release: **Windows x64**, **Linux x64**, **Linux arm64**, and **Android** (arm64-v8a, armeabi-v7a and x86_64, Android 10 and later: Vulkan 1.1 is mandatory from there). macOS and iOS are not
  supported (no Vulkan driver there without MoltenVK, which this project has not been able to try).

## Desktop
Download from the release page: `gdx-vulkan-core-<version>.jar` (it carries the natives of every desktop platform) and `gdx-vulkan-desktop-<version>.jar`. Check them against `SHA256SUMS.txt`.

```groovy
dependencies {
    implementation files('libs/gdx-vulkan-core-0.1.0-beta.1.jar', 'libs/gdx-vulkan-desktop-0.1.0-beta.1.jar')
    implementation "com.badlogicgames.gdx:gdx:1.14.2"
    implementation "com.badlogicgames.gdx:gdx-backend-lwjgl3:1.14.2"
    implementation "com.badlogicgames.gdx:gdx-platform:1.14.2:natives-desktop"
}
```

Start the game with `VulkanDesktopApplication` where you used `Lwjgl3Application`, and use `VulkanSpriteBatch` where you used `SpriteBatch` (a rename; the constructors and behaviour are the same):

```java
VulkanDesktopConfiguration config = new VulkanDesktopConfiguration();
config.title = "My game";
config.width = 1280; config.height = 720;
new VulkanDesktopApplication(new MyGame(), config);
```
Stock classes you do not rename (`SpriteBatch`, `ShapeRenderer`, `Stage`, `ModelBatch`, custom `ShaderProgram`s) run unchanged through the GL shim, a little slower than the `Vulkan...`
classes; the README's "Moving an existing game over" and "Known limitations" say what the shim does and does not cover.

## Android
Add the `.aar` (`gdx-vulkan-android-<version>.aar`) as a dependency and extend `VulkanAndroidApplication` instead of `AndroidApplication` (see "Android" in the README). The release also has
`gdx-vulkan-demos-<version>.apk`, the demo app: install it to see whether the phone runs the library at all before changing a game.

## Seeing what it does
- Set `-Dgdxvulkan.validation=true` to turn the Khronos validation layer on (when installed); a message in the log is worth reporting.
- `GDXVK_PROFILE=1` prints the per-frame CPU time at close. `GdxVkPresenter.gpuFrameNanos()` and `gpuZoneNanos(n)` give GPU times (README, "Measuring where a frame goes").
- The first run translates and compiles custom GLSL, which takes a moment; the result is cached in `gdxvk-shader-cache` next to the game.
- To compare with OpenGL, keep both application classes in the game and pick one at start-up, as the demos do.

## Reporting a problem
Open an issue with the template (bug report). The useful facts are the GPU and driver version, the operating system, which path ran (`GdxVkFrame.current().isTierA()`: true is the modern path), the
log, and for a picture that differs from OpenGL, a screenshot of each. A custom shader that does not translate: include its source.

## Known limits of a first pre-release
- No custom 3D `Shader` / `ShaderProvider` on `VulkanModelBatch` (stock `ModelBatch` accepts them through the shim).
- The GPU-driven instances receive but do not cast shadows; point lights are not shadowed.
- `Gdx.gl30` is always null: code that needs instanced draws or storage buffers through GL does not run; use `VulkanGpuInstances` and `VulkanComputeShader`.
- More in the README, "Known limitations".
