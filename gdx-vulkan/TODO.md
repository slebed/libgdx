# gdx-vulkan: remaining work and open issues

Reviewed 2026-11-04, at the end of the stock-classes-on-the-shim work and the first demos. This is the short list to plan from; the item-by-item status (what is
built and how it was verified) stays in [`docs/CHECKLIST.md`](docs/CHECKLIST.md), and each entry below says where to look. Nothing here is a release blocker that
CI can see: CI is green on every commit. The blockers are the things CI cannot run (phones, real GPUs) and the decisions only the owner can make.

Legend: **[bug]** something is wrong today, **[verify]** built but not checked on the device or in the way that matters, **[build]** not built yet,
**[decide]** needs the owner, **[docs]** documents that disagree with the code.

---

## 1. Known bugs and problems

- **[fixed 2026-11-04] Samsung Galaxy S20 FE (Adreno 650): every `VulkanModelBatch` pipeline failed.** The driver refuses a vertex shader whose buffer reads are addressed from a push constant; the model shaders now find their record from `gl_InstanceIndex` (the draw's first instance). The S20 passes the whole device suite, 3D included. Cause, measurements and rule: `docs/DRIVER_NOTES.md`; kept out of the shaders by `DriverRulesTest`.
- **[not a bug, measure more] Adreno 840 (REDMAGIC), modern path, 128 separate textures: 37 fps** (compatibility path 10.4 fps). The benchmark gives every sprite a random one of the 128 textures, so neighbouring sprites always differ: the modern path then indexes the bindless table with a different value per quad (a divergent lookup, slow on Adreno) and the compatibility path makes 20,000 draws a frame. It is the deliberate worst case ("no atlas"). Still to do, on the REDMAGIC over USB: the same 20,000 sprites sorted by texture (what a game that does not use an atlas but draws layer by layer sends), to put a number on the case between T1 and T2.
- **[fixed 2026-11-04] Android: the on-screen keyboard through `Gdx.input` did not work.** The stock input cast the view to a GL class; `VulkanSurfaceView` (an input connection, like the stock GL view) and `VulkanAndroidInput` (shows and hides the keyboard for it) replace that. `tools/keyboard-test.sh <serial>` passes on both phones (keyboard shown and hidden, a key arrives as `keyDown` and `keyTyped`). Not covered: a newer libGDX's `openTextInputField` overlay, which needs the stock `AndroidApplication`.
- **[bug] The GL shim ignores `glVertexAttrib*` constants**, so a draw that reads an attribute the mesh does not have gets undefined values (OpenGL gives the
  constant). Stock `DefaultShader` sets such constants for bone weights "in case a mesh has missing attributes". Vulkan has no constant attributes; options: a second
  vertex binding with stride 0 (native change) or a translator rewrite of the attribute to a uniform when the draw leaves it disabled.
- **[minor] The shader bundle covers a matrix of common variants only** (57 programs per code path: vertex layouts, materials, lights, fog, spot lights, and the skinned knight with every environment); other `DefaultShader` variants are translated and
  compiled at run time on first use (cached on disk afterwards). Extend the matrix in `RecordShaderBundleMain` once real projects show which variants they use.
- **[bug, minor] The `msvcp140.dll` crash is fixed on Windows** (runtime linked statically); nothing else is open there. Keep an eye on the Linux and Android runtimes
  if a similar mismatch shows up (libstdc++ / libc++ versions).

## 2. Built but not verified where it matters

Done 2026-11-04 (REDMAGIC over wireless, allowed by the owner for that run; S20 over USB): the whole device suite, 32 checks, passes in both code paths on the REDMAGIC with the
stock classes on the shim (0 pixels differ from the Vulkan classes, including the stock `ModelBatch` scenes), GPU timestamps work on both phones (an empty frame reports 0.14 to
0.35 ms), the stock `SpriteBatch` on the shim reaches 0.88x to 0.89x of `VulkanSpriteBatch` on both phones, and the first-use shader costs are measured (first program of a run 0.6 s
on the Adreno 840 and 1.1 s on the Adreno 650, each further shader 10 to 25 ms, cached 0.2 to 0.8 ms). Results are in `README.md` and `docs/CHECKLIST.md`.

- **[fixed 2026-11-04] The first translated shader of a run took 0.6 to 1.1 s on the phones.** glslang now starts up on a background thread when the frame is created (`GlslCompiler.warmUp()`, off with `-Dgdxvulkan.shaderWarmUp=false`): on the Galaxy S20 the first program 2 s after start takes 40 ms instead of 0.86 to 1.09 s, the next ones 18 ms. A shader created in the very first moments (before the warm-up finishes) still waits for what is left of it.
- **[verify] Demos on a phone**: they are desktop only (section 4).
- **[verify] Stock `ModelBatch` with point and spot lights, textures in `DefaultShader`, skinned models** on the desktop: they draw without error (the bundle recorder runs them) but only
  the directional-light scenes and Gate 5 are compared with a picture. Add scenes with point and spot lights against OpenGL.
- **[verify] Android GPU Inspector capture** of the pre-rotated swapchain (AGI is not installed; the presenter's `rotation()` and the logs are the evidence now).
- **[verify] Android armeabi-v7a and x86_64** compile and link but have never been run (needs an emulator or a second phone). **Linux arm64:** natives are built and
  their native tests run in CI (`arm64-natives`); the Java suite does not run there, so GPU timestamps are unchecked on that platform.
- **[verify] The 10-minute soak on the RTX 2080, compatibility path** was never run (every other GPU/path combination was).
- **[verify] Validation message count in the demo smoke tests** is not asserted (the layers run, a message prints, but it does not fail `DemosSmokeTest`).
- **[verify] The glslang size cost with and without the SPIRV-Tools optimizer** is not recorded (the library grew from 2.1 MB to 2.5 MB on Windows when the runtime became static).

## 3. Features not built

### Shaders and the GL shim
- **[build] Custom 3D shaders for `VulkanModelBatch`** (`Shader` / `ShaderProvider` built on `VulkanShaderProgram`). Today the answer is "use stock `ModelBatch` through the
  shim", which accepts any stock shader but costs more CPU. Decide whether `VulkanModelBatch` still needs its own provider API.
- **[done 2026-11-04, mostly] Shim performance.** Pipelines are found by a 64-bit signature (no string per draw), the draw walks the program's attribute array, push constants go
  out as words, matrix uniforms compare words. Stock `ModelBatch` shadow demo, render thread: 0.69 to 0.72 ms (was 0.75 to 0.83; OpenGL's render thread is 0.20 ms but its driver thread
  does the rest: total process CPU per frame is equal, 0.69 against 0.70 ms). What remains is `vkQueuePresentKHR` (about 0.27 ms per frame, 38% of the render thread; not shim specific) and the stock
  `SpriteBatch` staging copies (0.75x to 0.93x of `VulkanSpriteBatch`: every flush copies its 655 KB of vertices through the staging ring). Ideas not done: dynamic VBOs through the vertex ring,
  a second vertex binding for `glVertexAttrib` constants, a present thread (considered; risk with Swappy/resizing/present fences, and it only matters above 1,000 fps).
- **[build] Shim coverage:** instancing through GL (needs `Gdx.gl30`, which stays null), several vertex buffers per draw, vertex formats other than float and
  normalised ubyte, unsigned-byte indices, query objects.
- **[build] Translator:** `#version 300 es` / `330` shaders passing through; `#define` flags as specialization constants so variants share SPIR-V; precision qualifiers to
  `RelaxedPrecision` on mobile; a spirv-opt pass (keep only if a benchmark shows an effect); optional build without the compiler's sources for precompiled-bundle users;
  `VulkanShaderProgram` setter overloads still missing (`FloatBuffer` / `IntBuffer`, by-location multi-value forms).
- **[build] A test that fails the build if the model batch, the effects or compute call the run-time compiler** (only the sprite batch, polygon batch and shape renderer are covered).

### 3D and rendering
- **[build] Clustered lights together with shadows in one scene.** Clusters exist only on the GPU-driven path (one mesh, no shadows); cascaded shadows and up to 8 point lights
  work in `VulkanModelBatch`. Options: the cluster lookup in the model shader (many lights for any model), or cascade sampling in `gpu_lit`.
- **[build] Spot lights and point-light shadows in `VulkanModelBatch`** (stock's default shader has neither; stock `ModelBatch` through the shim compiled and drew a spot light without error, unchecked against OpenGL).
- **[build] True reordering of offscreen framebuffer passes** recorded after a screen draw (pass scheduling today only moves the swapchain pass's start).

### Platforms
- **[build] Wayland** (X11 only on Linux).
- **[build] macOS and iOS (Phase 6, tabled: needs a Mac):** `CAMetalLayer` surface, `VK_KHR_portability_*`, MoltenVK or KosmicKrisp, RoboVM/MobiVM. Starts only after desktop and Android pass every gate.

## 4. Demos (`gdx-vulkan-demos`)

Built: stock shadow recipe (both backends), cascaded shadows, GPU boids with an OpenGL 4.3 version. Decisions are in the checklist "Demos" section.
- **[decide] Owner's review of the demo code and looks** (clean, copyable, visually interesting, readable text at your screen size).
- **[done 2026-11-04] The demo app on Android** (`gdx-vulkan-android/demoapp`, `tools/demos-test.sh`; touch menu, three demos, Back returns). Passes on the Galaxy S20 FE (Adreno 650, Tier B); not yet run on the REDMAGIC (USB only). The owner's review of the demos' code and looks is still to do.
- **[build] More demos:** sprite storm (100k+ sprites, stock against Vulkan), post-processing over a Scene2D UI; a scene with clustered lights and shadows once section 3 allows.
- **[build] Demo numbers on a phone and on the second GPU** (the table in the README is the RTX 5080 only).
- **[decide] Demo screenshots in the README** (a 1280 x 720 PNG per demo; the repository has none yet).

## 5. Release and adoption (only when the owner says so)

Plan in the checklist "Releases and adoption testing". Nothing is released; the owner wants others to test and wants to try the library in several of their own projects.
- **[decide] Release criteria** ("safe"): CI green on the tag, no open crash/corruption bug on a supported platform, known limitations in the notes, licences and `NOTICE` complete, no personal paths.
- **[build] One jar with the natives of every desktop platform** (`natives/<os>-<arch>/`; today the jar holds the build machine's platform only).
- **[build] A release workflow on a tag:** natives for Windows x64, Linux x64 (arm64 if wanted), the Android AAR (all ABIs), tests, jars and AAR attached.
- **[decide] Version scheme** (pre-releases first, `v0.1.0-beta.1`) and **where to publish** (GitHub releases only, GitHub Packages, JitPack, Maven Central).
- **[build] Release notes from the checklist, a "how to try it" page for testers, an issue template** (GPU, driver, OS, code path, log).
- **[build] Adoption testing in the owner's projects:** `docs/ADOPTION.md` (what had to change, what broke, how long it took), build and run each project on the desktop (and Android),
  compare with stock, switch stock and gdx-vulkan with a flag to measure one game both ways (frame time CPU and GPU, 1% lows, memory), list the custom GLSL each project uses, correct the
  README migration section from the experience.
- **[decide] Upstream libGDX PR** (public buffer handle and dirty accessor on VBO/IBO; patch ready in `docs/upstream/`, applies cleanly, **not filed**). Until it ships gdx-vulkan reads those fields through JNI field ids.
- **[build] Bump `gdxVersion` to 1.14.3** when it is on Maven Central (still 404 on the last check); procedure at the end of `docs/RELEASE.md`. Regenerates the GL20 base and the batches from the new sources.

## 6. Documents that disagree with the code

- **[docs] `docs/CHECKLIST.md` has boxes that are probably done but not ticked.** Verify and tick: "CI (lavapipe) runs the translator tests and the golden scenes" (it does:
  `check` runs them), "Every test runs on both code paths with the validation layers clean" (CI does both paths), the glslang item "Linux and Android builds not run yet" (Linux CI and the
  S20 run translated shaders; only the size cost is unrecorded), "custom 3D shaders ... `VulkanShaderPipelineBundle` stays supported" (the bundle path is supported and tested).
  The summary counts at the top (187 of 238) follow the boxes, so they move when these do.
- **[docs] `docs/PLAN.md` and `libGDX Vulkan Backend Plan v5.0.md` describe the plan, not what exists:** the shader translator (Phase 5b), stock classes through the shim, the shader bundle, GPU timing and the demos
  are not in them. Add a "what was built differently" section, or mark the plan as the original design.
- **[docs] The checklist's Android "21-check device suite" figures are older** than the 30-check suite (`docs/RELEASE.md` and `CLAUDE.md` were updated; the Phase 4 text was not).
- **[docs] README performance numbers for RM-11P predate the sprite shader fix and this session's work in part**; the S20-FE was re-run on 2026-11-04 (see the README).
- **[housekeeping] The fork's working tree** (`slebed/libgdx`, branch `gdx-vulkan-phase1`) has uncommitted changes to the owner's old LWJGL Vulkan backend (`backends/gdx-backend-vulkan`,
  `tests/gdx-tests-android` assets). They are not part of this project and were left alone; decide separately what to do with them.

## 7. Suggested order

1. **Phone round over USB** (REDMAGIC): the device suite in both tiers, the stock-class checks, the demos' numbers, timestamps. It closes most of section 2 and refreshes README numbers.
2. ~~S20 3D problem~~ (fixed, see section 1).
3. **Shim performance** (section 3): integer pipeline keys and a profile of the stock `ModelBatch` path, since "port by changing nothing" is only attractive if it is not slow.
4. **Demo app on Android and the owner's review**, then the extra demos.
5. **Documents** (section 6): tick the stale boxes, add the as-built section to the plan.
6. **Release work** (section 5) when the owner decides: the multi-platform jar and workflow first, then a first pre-release to testers, adoption testing in parallel.
7. **Later:** custom 3D shader API decision, clustered lights with shadows, offscreen pass reordering, Wayland, macOS and iOS.
