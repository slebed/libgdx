# gdx-vulkan: remaining work and open issues

Reviewed 2026-11-04, at the end of the stock-classes-on-the-shim work and the first demos. This is the short list to plan from; the item-by-item status (what is
built and how it was verified) stays in [`docs/CHECKLIST.md`](docs/CHECKLIST.md), and each entry below says where to look. Nothing here is a release blocker that
CI can see: CI is green on every commit. The blockers are the things CI cannot run (phones, real GPUs) and the decisions only the owner can make.

Legend: **[bug]** something is wrong today, **[verify]** built but not checked on the device or in the way that matters, **[build]** not built yet,
**[decide]** needs the owner, **[docs]** documents that disagree with the code.

---

## 1. Known bugs and problems

- **[bug] Samsung Galaxy S20 FE (Adreno 650): every `VulkanModelBatch` pipeline fails** with `VK_ERROR_INITIALIZATION_FAILED` and `Adreno-GSL: GPUOBJ_ALLOC Invalid
  argument`; the driver's compiler also crashes on later pipelines. Narrowed down (stub vertex shader builds; one dynamic storage-buffer read plus a fragment shader
  that consumes any vertex output fails; not the light loops, cascades or varying count). 2D, framebuffers, compute, instancing and the stock classes on the shim work
  there. Deferred by the owner ("deal with it along with the other outstanding bugs"). Next step: find the trigger or work around it (for example reading the blob
  through a uniform buffer or vertex attributes on this driver); decide whether stock `ModelBatch` on the shim (a different shader layout) works on the phone, which would be a fallback.
  Checklist: Gate 1a "Render correctly on the Galaxy S20", Gate 5 "pipelines build on the Adreno 650".
- **[bug] Adreno 840 (REDMAGIC), modern path, 128 separate textures: 38 fps** (compatibility path 8.6 fps). The one-texture case was fixed (bindless index from
  push constants instead of a varying); the many-texture case was not investigated. Optional, but it is the weak spot of the modern path on that GPU.
- **[bug] Android: the on-screen keyboard through `Gdx.input` does not work.**
- **[bug] The GL shim ignores `glVertexAttrib*` constants**, so a draw that reads an attribute the mesh does not have gets undefined values (OpenGL gives the
  constant). Stock `DefaultShader` sets such constants for bone weights "in case a mesh has missing attributes". Vulkan has no constant attributes; options: a second
  vertex binding with stride 0 (native change) or a translator rewrite of the attribute to a uniform when the draw leaves it disabled.
- **[bug, minor] The shader bundle covers a matrix of common variants only** (no skinned models, for example), so other `DefaultShader` variants are translated and
  compiled at run time on first use (cached on disk afterwards). Extend the matrix in `RecordShaderBundleMain` (bones, more lights, more material attributes) once real
  projects show which variants they use.
- **[bug, minor] The `msvcp140.dll` crash is fixed on Windows** (runtime linked statically); nothing else is open there. Keep an eye on the Linux and Android runtimes
  if a similar mismatch shows up (libstdc++ / libc++ versions).

## 2. Built but not verified where it matters

- **[verify] The REDMAGIC has not run any of this session's work.** It was only connected over wireless, and the rule is USB only. Needs, over USB: the device suite
  in both tiers (it grew from 21 to 30 checks), including `stockClassesOnTheShim`, `gate3StockClassesOnTheShim`, `gate5StockModelBatchOnTheShim`,
  `stockSpriteBatchAgainstGenerated`, and `shaderProgramsAgainstBuiltIn` after the sprite shader fix (the earlier full re-run was never done).
- **[verify] GPU timestamps (`GdxVkPresenter.gpuFrameNanos`) on Android and Linux arm64.** Tested on Windows (RTX 5080) and in CI (lavapipe). Check a phone returns plausible
  GPU times and that always submitting the upload command buffer costs nothing measurable there.
- **[verify] The demos on a phone** (they are desktop only: see section 4) and the **stock `ModelBatch` on any phone** (only the S20 ran the 2D stock checks).
- **[verify] Stock `ModelBatch` with point and spot lights, textures in `DefaultShader`, skinned models**: they draw without error (the bundle recorder runs them) but only
  the directional-light scenes and Gate 5 are compared with a picture. Add scenes with point and spot lights against OpenGL.
- **[verify] Not measured:** the first-use compile time of a translated shader (target under 50 ms; glslang's own start-up is about 150 ms, `warmUp()` hides it), the
  second launch with a warm disk cache, the translator's compile time on a phone, the glslang size cost with and without the SPIRV-Tools optimizer.
- **[verify] Android GPU Inspector capture** of the pre-rotated swapchain (AGI is not installed; the presenter's `rotation()` and the logs are the evidence now).
- **[verify] Android armeabi-v7a and x86_64** compile and link but have never been run (needs an emulator or a second phone). **Linux arm64:** natives are built and
  their native tests run in CI (`arm64-natives`); the Java suite does not run there.
- **[verify] The 10-minute soak on the RTX 2080, compatibility path** was never run (every other GPU/path combination was).
- **[verify] Validation message count in the demo smoke tests** is not asserted (the layers run, a message prints, but it does not fail `DemosSmokeTest`).

## 3. Features not built

### Shaders and the GL shim
- **[build] Custom 3D shaders for `VulkanModelBatch`** (`Shader` / `ShaderProvider` built on `VulkanShaderProgram`). Today the answer is "use stock `ModelBatch` through the
  shim", which accepts any stock shader but costs more CPU. Decide whether `VulkanModelBatch` still needs its own provider API.
- **[build] Shim performance.** Stock code through the shim costs more render-thread CPU than OpenGL (0.83 against 0.33 ms in the shadow demo; the stock `SpriteBatch` reaches
  0.72x to 0.96x of `VulkanSpriteBatch`). Known cost: a pipeline-key string is built per draw (use an integer key); profile the rest (`DefaultShader` uniform writes,
  pipeline lookup, per-draw push/ring writes).
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
- **[build] The demo app on Android** (menu and the three demos; the OpenGL counterparts stay desktop).
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
- **[docs] README performance numbers for RM-11P and S20-FE predate the sprite shader fix and this session's work**; re-measure when the phones are connected over USB.
- **[housekeeping] The fork's working tree** (`slebed/libgdx`, branch `gdx-vulkan-phase1`) has uncommitted changes to the owner's old LWJGL Vulkan backend (`backends/gdx-backend-vulkan`,
  `tests/gdx-tests-android` assets). They are not part of this project and were left alone; decide separately what to do with them.

## 7. Suggested order

1. **Phone round over USB** (REDMAGIC): the device suite in both tiers, the stock-class checks, the demos' numbers, timestamps. It closes most of section 2 and refreshes README numbers.
2. **S20 3D problem** (section 1): it is the one open correctness bug, and the stock-`ModelBatch` fallback may settle it quickly.
3. **Shim performance** (section 3): integer pipeline keys and a profile of the stock `ModelBatch` path, since "port by changing nothing" is only attractive if it is not slow.
4. **Demo app on Android and the owner's review**, then the extra demos.
5. **Documents** (section 6): tick the stale boxes, add the as-built section to the plan.
6. **Release work** (section 5) when the owner decides: the multi-platform jar and workflow first, then a first pre-release to testers, adoption testing in parallel.
7. **Later:** custom 3D shader API decision, clustered lights with shadows, offscreen pass reordering, Wayland, macOS and iOS.
