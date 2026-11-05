# Adoption notes

A log of moving real libGDX games over to gdx-vulkan: what had to change, what broke, what was faster or slower. One section per project; keep it factual and dated. The
README's "Moving an existing game over" section is corrected from what is written here.

## How to try a project

1. Copy the project, build it once as it is and record the frame time (`Gdx.graphics.getDeltaTime()` averaged over a minute), the CPU time per frame and the GPU time if the device has a timer query.
2. Change the application class (`Lwjgl3Application` to `VulkanDesktopApplication`, `AndroidApplication` to `VulkanAndroidApplication`) and nothing else. Run it. Everything the stock classes use runs through the GL shim.
3. List what failed, with the message (the shim names the unsupported call or the GLSL line).
4. Rename the hot classes (`SpriteBatch` to `VulkanSpriteBatch`, and so on) and measure again.
5. List the custom GLSL the project uses, and which of it was refused or slow to build.
6. Compare with `tools/compare-demos.sh`-style numbers on the same machine: frame time, CPU per frame, GPU time, and the first 10 seconds (shader builds at run time).

## Template

### <project>, <date>, <commit of gdx-vulkan>
- Machine / phone, GPU, driver:
- Renderer before: libGDX <version>, <backend>
- Changes needed to run: 
- What broke, and what fixed it:
- Custom GLSL (files, anything refused):
- Frame time, CPU per frame, GPU time: stock / shim only / with the Vulkan classes
- First-run cost (shader builds, pipeline builds):
- Visual differences against stock (screenshots):
- Verdict:

---

## Luna (the owner's moon-base strategy game), 2026-11-05, gdx-vulkan `master`-line commit of that day

A first attempt, on the branch `gdx-vulkan` of `G:\Projects\Games\Luna` (local, not pushed). libGDX 1.14.0, gdx-gltf, VisUI, Scene2D, Bullet, Box2D, artemis-odb, gdx-vfx; the renderer is a
OpenGL 3.3 to 4.3 engine of its own (custom `ShaderProgram`s in `#version 300 es`, `330 core` and `430 core`, a terrain quadtree drawn from several vertex buffers, instanced buildings, a
PBR shader, a shadow map in a float framebuffer, object-id picking through `glReadPixels`, lighting probes in a shader storage buffer).

**What had to change in the game**
- `gdxVersion` 1.14.0 to 1.14.2 (the version gdx-vulkan is built against).
- `lwjgl3/build.gradle` takes the two gdx-vulkan jars (`gdx-vulkan-core`, `gdx-vulkan-desktop`) from a local build, and has a `runVulkan` task.
- `VulkanLauncher` next to `Lwjgl3Launcher`: `VulkanDesktopApplication` and `VulkanDesktopConfiguration` instead of the LWJGL3 pair (it also has `-Dluna.screenshot`, `-Dluna.launch` for looking at a frame without watching the window).
- A switch, `luna.noSsbo`, that keeps the lighting probes (shader storage buffer, `#define USE_SH_PROBES`) off: three one-line changes.

**What runs**: the whole start-up (definitions, 9,199 named features, models, the Scene2D UI) and the **level select screen with the textured Moon globe, its labels and the panel**, drawn by the game's own
code and shaders, unchanged. Loading a level builds the whole `GameScreen`: every shader compiles and links, the float shadow framebuffer is created, the first frame is recorded.

**What the attempt fixed in gdx-vulkan** (each has a test): float colour attachments through the stock `FrameBuffer` builder (the extension names it asks for); a `mat3` varying took one location, not three
(a real shader of the game made glslang refuse the program); `glReadPixels` from a framebuffer and from the screen (the frame is submitted, waited for and carried on in a new command buffer); constant vertex attributes (`glVertexAttrib*`).

**What stops it next** (in the order the game meets them; each is a feature of the shim, not a small fix):
1. `GLUtils.isGL3()` in the game chooses vertex-array-object classes when `Gdx.gl30` exists and falls back to client-side arrays (`VertexArray`, `IndexArray`) when it does not; the terrain draws with an index offset
   into a bound index buffer, which the fallback cannot do. gdx-vulkan keeps `Gdx.gl30` null on purpose.
2. The terrain draws from **three vertex buffers at once** (a grid, a height buffer and a normal buffer per leaf): several vertex buffers per draw are not supported by the shim yet.
3. Buildings are drawn **instanced** through `Gdx.gl30.glDrawElementsInstanced` and attribute divisors (`MyMesh`).
4. `RadianceCascades` and the shadow code use **depth texture** and **multiple-render-target** framebuffer attachments, which the stock `FrameBuffer` builder only allows when `isGL30Available()` is true.
5. Lighting probes, a path tracer and baked emissive data use **shader storage buffers** and a **compute shader** (`#version 430`); the PBR shaders sample **cube maps** (image-based lighting), which the translator refuses.

**Verdict so far**: the 2D and UI parts and the main screen port with a launcher change. The 3D game screen needs a `Gdx.gl30` layer in gdx-vulkan (vertex array objects, instancing, draw buffers, depth textures) and
several vertex buffers per draw, or changes in the game to go through gdx-vulkan's own classes (`VulkanGpuInstances`, `VulkanComputeShader`, `VulkanModelBatch`).
