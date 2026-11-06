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
- **[done 2026-11-05] The GL shim ignored `glVertexAttrib*` constants.** An attribute without an array now reads its constant (GL's default is 0, 0, 0, 1) from a second vertex binding of stride 0, bound per draw (`BindConstants`, `GdxVkPipelineDesc.constantAttribute`); `ConstantAttributeTest`.
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
- **[done 2026-11-05] Stock `ModelBatch` with point and spot lights**: point lights are in Gate 5 (`gate5_models`, against the OpenGL reference and `VulkanModelBatch`). **Spot lights: libGDX's default shader has no code for them** (`DefaultShader.Config.numSpotLights` exists, `default.fragment.glsl` never mentions a spot light), so on OpenGL a spot light changes nothing; `gate5_spot` pins that the shim draws exactly what OpenGL draws (0 differing pixels). Textures in `DefaultShader` and skinned models are in `gate5_textures` and `gate5_knight`.
- **[verify] Android GPU Inspector capture** of the pre-rotated swapchain (AGI is not installed; the presenter's `rotation()` and the logs are the evidence now).
- **[done 2026-11-05] Android x86_64 runs**: on the Android emulator (API 34 google_apis x86_64 image, SwiftShader Vulkan 1.1, Tier B, installed under `C:\android-sdk`), `-Pabi=x86_64` passes 29 of the 32 device-suite checks; the three failures (`stress2Textures128`, `instancedMillionSprites`, `stress5Instances50k`) are throughput thresholds (22 fps at 1080 x 2209 on a software renderer).
- **[done 2026-11-05, partly] Android armeabi-v7a runs**: the test app built with `-Pabi=armeabi-v7a` passes the whole device suite (32 checks) as a 32-bit process on the Galaxy S20 FE (`primaryCpuAbi=armeabi-v7a`). **x86_64 has not been run** (no emulator image here). **Linux arm64:** natives are built and their native tests run in CI (`arm64-natives`); the Java suite does not run there, so GPU timestamps are unchecked on that platform.
- **[done 2026-11-05] The 10-minute soak on the RTX 2080, compatibility path**: 600 s, 36,000 frames at 60.0 fps, 50,000 buffer cycles/s and 2,000 textures/s, nothing live at the end, no validation message.
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
- **[build] Shim coverage:** instancing through GL (needs `Gdx.gl30`, which stays null), vertex formats other than float and
  normalised ubyte, unsigned-byte indices, query objects.
- **[done 2026-11-05] Translator:** `#version 300 es` / `330` / `430` shaders pass through (a real game's custom shaders run); `gl_FragCoord` and `dFdx`/`dFdy` follow OpenGL's axes; fragment outputs start at zero; an unwritten varying reads zero; the optional build without the compiler exists (`-Pgdxvulkan.noCompiler=true`); the spirv-opt pass is **dropped** (the one measurement shows nothing to win, see `docs/CHECKLIST.md`).
  **[build]** `#define` flags as specialization constants so variants share SPIR-V (the preprocessor decides the declarations first, so this means restructuring the translator); precision qualifiers to `RelaxedPrecision` on mobile (needs a phone to measure);
  `VulkanShaderProgram` setter overloads still missing (`FloatBuffer` / `IntBuffer`, by-location multi-value forms).
- **[build] A test that fails the build if the model batch, the effects or compute call the run-time compiler** (only the sprite batch, polygon batch and shape renderer are covered).

### 3D and rendering
- **[partly done 2026-11-05] Clustered lights together with shadows in one scene.** The GPU-driven instances now receive the directional light's shadow (`VulkanGpuInstances.setShadow`). **[build]** the instances casting into the shadow map (a second cull against the light's frustum and a depth variant of the indirect draw), shadows for the point lights, and the cluster lookup in the model shader (many lights for any model).
- **[decided 2026-11-05] No spot lights in `VulkanModelBatch`**: libGDX's own default shader does not implement them either (see above), so a port that adds them would draw what stock cannot. Point-light shadows remain unbuilt (clustered lights and shadows in one scene, above).
- **[build] True reordering of offscreen framebuffer passes** recorded after a screen draw (pass scheduling today only moves the swapchain pass's start).

### Platforms
- **[build] Wayland** (X11 only on Linux).
- **[build] macOS and iOS (Phase 6, tabled: needs a Mac):** `CAMetalLayer` surface, `VK_KHR_portability_*`, MoltenVK or KosmicKrisp, RoboVM/MobiVM. Starts only after desktop and Android pass every gate.

## 4. Demos (`gdx-vulkan-demos`)

Built: stock shadow recipe (both backends), cascaded shadows, GPU boids with an OpenGL 4.3 version. Decisions are in the checklist "Demos" section.
- **[decide] Owner's review of the demo code and looks** (clean, copyable, visually interesting, readable text at your screen size). Prepared 2026-11-05: `docs/DEMO_REVIEW.md` (what to run, what to look at, known weak spots, three questions) and screenshots in `docs/images/` (in the README).
- **[done 2026-11-04] The text on screen was expensive on gdx-vulkan** (1.7 ms per frame on the REDMAGIC against 0.2 on OpenGL ES). Cause: the text uses two fonts, so a flush had two textures and the batch
  wrote its 24-byte vertices through a `ByteBuffer` (six puts per vertex, about 80 ns each on ART). Fixed in `VulkanSpriteBatch`: the expansion goes into an `int[]` and is copied in one block. Now 0.4 ms
  against 0.2. The rest is `ShapeRenderer` through the shim and the glyph vertex copy; `VulkanPolygonSpriteBatch` has the same per-vertex puts (line 196) and is not changed.
- **[done 2026-11-04] Sprites sorted by texture on the Adreno 840 were 0.2 to 0.3x of OpenGL ES.** A flush whose texture runs average 16 sprites or more now goes out as one draw per run with the slot in the
  push constants (`docs/DRIVER_NOTES.md`): 952 fps against 470 without text, up from 71 to 116. Scattered textures went from 62 to 180 fps on the same phone (the faster expansion).
- **[perf] One texture atlas on the Adreno 840 is 0.81x of OpenGL ES** (985 against 1,217 fps, 10,000 sprites, no text). At that frame rate (about 1 ms) the render thread is the limit on gdx-vulkan (0.92 ms of CPU
  against 0.73 ms), so the gap looks like a fixed cost per frame (present, acquire, submit: 0.2 to 0.3 ms) rather than the GPU; its "GPU time" (0.80 against 0.47 ms) spans the whole frame while the OpenGL timer
  covers the work only. It disappears at the display's rate. **Not investigated on the phone yet** (the REDMAGIC was not connected for this round): record per-stage CPU time with `GDXVK_PROFILE=1` there.
- **[found 2026-11-05] Swappy accounts for most of the extra CPU per frame on gdx-vulkan at the display's rate** (Galaxy S20, 10,000 boids, 118 fps, vsync): per-thread ticks over 8 s were 258 for the render thread with Swappy and 227 without, plus two Swappy threads (an unnamed Java `Thread-5` of 32 ticks and a native thread of 20); without Swappy the total is about 304 ticks against 279 for OpenGL ES. The battery did not show a difference (above). `--ez pacing false` on the demo app turns it off; the library's `GdxVkConfig.framePacing` stays on by default for smooth frames. Decide whether the default should depend on the refresh rate.
- **[perf] Scattered textures on the Adreno 650** (Tier B, 10,000 sprites): 22 fps, 38 ms of GPU time: Tier B binds a descriptor set per texture change; a bindless table needs the 1.3 features the driver lacks.
- **[verify] The Galaxy S20's OpenGL ES boids are 3.7x slower when no 2D draw follows the scene** (142 against 529 fps): any draw after the scene (even two filled rectangles) fixes it, the layout work alone does
  not, and `glMapBufferRange` is not the cause. A tile-based driver behaviour (binning mode?); the README reads the rows with the text on screen. Nothing is wrong in gdx-vulkan.
- **[done 2026-11-05] Energy**: `tools/energy-phone.sh` (unplugged phone, wireless adb, screen kept on, mean battery power over a minute at the display's rate): the same on both backends within about 5% for boids, an atlas of sprites and the stock shadow demo on the REDMAGIC (numbers in the README). **Not measured:** the Galaxy S20 (it needs wireless adb and no charger; USB charges it). Phone tests must stay short: a full energy set takes 40 minutes of the phone.
- **[done 2026-11-04] GPU time for OpenGL ES on the phones**: `GlesTimer` (JNI, `GL_EXT_disjoint_timer_query`). It times the demo's work; gdx-vulkan's timestamps span the whole frame, so the two are not
  comparable and the README says so.
- **[done 2026-11-04] The comparison demos**: sprites, boids and shadows run the same scene on both backends (`tools/compare-demos.sh`, `tools/compare-phone.sh`, `GlDemosActivity`); the cascaded shadows demo is
  labelled a feature demo with no OpenGL counterpart. Results are in the README ("Demo measurements"), including the cases gdx-vulkan does not win.
- **[done 2026-11-04] The demo app on Android** (`gdx-vulkan-android/demoapp`, `tools/demos-test.sh`; touch menu, three demos, Back returns). Passes on the Galaxy S20 FE (Adreno 650, Tier B); not yet run on the REDMAGIC (USB only). The owner's review of the demos' code and looks is still to do.
- **[done 2026-11-05] More demos:** sprite storm (150,000 sprites from one atlas) and post-processing over a Scene2D UI (menu keys 5 and 6, OpenGL counterparts `storm` and `postfx`, smoke tests). Numbers on the phones and both RTX cards are still to be recorded in the README. **[build]** a scene with clustered lights and shadows, now that the instances can receive shadows (casting is still missing).
- **[build] Demo numbers on the second GPU** (the RTX 2080 has no fair OpenGL comparison on this PC; phones and the RTX 5080 are in the README).
- **[done 2026-11-05] Demo screenshots in the README** (`docs/images/`, 1280 x 720 on the RTX 5080, quantised to 128 colours to stay small).

## 5. Release and adoption (only when the owner says so)

Plan in the checklist "Releases and adoption testing". Nothing is released; the owner wants others to test and wants to try the library in several of their own projects.
Decisions of the owner, 2026-11-05: the release criteria in the checklist are the bar; versions `v0.1.0-beta.1` and so on; **GitHub releases only**; platforms are whatever libGDX supports and the library can address (Windows x64, Linux x64 and arm64, Android arm64-v8a, armeabi-v7a, x86_64; macOS and iOS need a Mac); **no upstream libGDX change**.
- **[done 2026-11-05] The release machinery:** `.github/workflows/release.yml` (tag or dry run: tests, natives, AAR, one jar with every desktop native, checksums, draft release), `docs/TRYING_IT.md` for testers, the issue template, `tools/release-notes.py`, `docs/RELEASE.md` section 5. **Not run yet on GitHub:** do the dry run first (RELEASE.md section 5).
- **[owner] When to cut the first pre-release:** after the checklist in `docs/RELEASE.md` sections 1 to 4 has been done on the commit (real GPUs, a phone round), and the unexplained measurements in section 8 below are looked at, if the owner wants them first.
- **[build] Adoption testing in the owner's projects:** `docs/ADOPTION.md` (what had to change, what broke, how long it took), build and run each project on the desktop (and Android),
  compare with stock, switch stock and gdx-vulkan with a flag to measure one game both ways (frame time CPU and GPU, 1% lows, memory), list the custom GLSL each project uses, correct the
  README migration section from the experience. (The owner keeps the notes about their own projects out of this repository.)
- **[decided 2026-11-05] No upstream libGDX PR:** the library must not rely on changes to the libGDX API. The prepared patch (`docs/upstream/`) is kept as a record; gdx-vulkan reads those fields through JNI field ids.
- **[build] Bump `gdxVersion` to 1.14.3** when it is on Maven Central (still 404 on the last check); procedure at the end of `docs/RELEASE.md`. Regenerates the GL20 base and the batches from the new sources.

## 6. Documents that disagree with the code

- **[done 2026-11-05] `docs/CHECKLIST.md` boxes:** the four named here are ticked, and the summary table is recomputed from the boxes (218 done, 20 partial, 20 not started, of 258).
- **[done 2026-11-05] `docs/PLAN.md` has a "What was built differently from the plan" section** (translator, stock classes, bundle, GPU timing and zones, demos, the OpenGL behaviours, the compiler-free build). The v5.0 plan document itself, kept in the Claude Project, is unchanged: it is the original design.
- **[done] The checklist's Android device-suite figures** say 21 checks "when this was run" and 32 since.
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

## 8. Measurements still unexplained (2026-11-05, from running one game on both renderers)
- **[verify] Memory:** the gdx-vulkan process held about 0.6 GB more working set than OpenGL in the same scene (0.44 against 1.06 GB in a menu, 1.8 against 2.4 GB in a scene). The rings of the library are 4 to 32 MB, so the rest is the driver, glslang and the caches; split it (a vmmap, or the process with and without -Pgdxvulkan.noCompiler=true) before anyone optimises it.
- **[verify] Render thread:** 0.97 cores busy in an uncapped menu at 2,000 fps against 0.35 on OpenGL (whose driver threads take the rest: the process totals are alike). Probably the wait for the GPU spinning; check with vsync on and with GDXVK_PROFILE=1.
- **[verify] Fixed per-frame cost:** at 2,000 fps a frame is 10% slower than OpenGL (0.49 against 0.44 ms): acquire, submit, present and the per-frame bookkeeping. Only visible when frames are this short.
- **[done 2026-11-05] Pre-rotation of the fragment-shader fixes** (gl_FragCoord, gdx_dFdx, gdx_dFdy): `PreRotationTest.fragCoordAndDerivativesFollowOpenGlAtEveryRotation` draws one shader at the four forced rotations and the pictures are identical (both tiers); the phone checks `gpuZones` and `fragCoordAndDerivativesFollowOpenGl` pass on the Galaxy S20 FE (Adreno 650, Tier B, portrait, rotation 0 over USB). Still to see on a real rotated display (landscape on a phone): the same checks, which the portrait-locked test activity cannot do; `LifecycleActivity` rotates but runs another workload.
