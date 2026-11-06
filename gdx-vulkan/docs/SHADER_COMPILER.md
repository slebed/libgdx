# Choosing the GLSL-to-SPIR-V compiler

Decision (owner, after this comparison): **glslang**, used directly, behind a small `ShaderCompiler` interface in the native core so that shaderc could replace
it without touching the translator. The SPIRV-Tools optimizer is an optional second step (see "What is still to measure").

## What the candidates are
- **glslang** (Khronos): the reference GLSL front end. It parses GLSL and emits SPIR-V, and it has a reflection API for the shader it parsed.
- **shaderc** (Google): a C API around glslang and the SPIRV-Tools optimizer. `glslc`, which the build already uses for the precompiled shaders, is its command line.

## The original backend
`backends/gdx-backend-vulkan` (the LWJGL backend) used **shaderc** through LWJGL's `lwjgl-shaderc` bindings: `shaderc_compile_into_spv`, target Vulkan 1.0,
the optimization level left at the default (the performance level is commented out), the SPIR-V cached as `.spv` files by the GLSL file's modified time, and no
include callbacks. That worked because LWJGL ships prebuilt shaderc natives for Windows, Linux and macOS. It is not an option here: gdx-vulkan builds its own native
library, also for Android, and LWJGL has no Android natives. Either compiler has to be built from source into our library.

## Comparison
| | glslang | shaderc |
|---|---|---|
| Maintenance (checked on GitHub, November 2026) | releases every month or two (16.6.0 on 2026-09-11, 16.5.0 on 2026-08-03); commits daily | commits within the last week; no GitHub releases, only tags that follow SPIRV-Tools (v2026.4) |
| New language and SPIR-V features | first: it is where they are implemented | after glslang, when its pinned glslang is updated |
| What the build needs | glslang sources only (CMake, C++, no Python); SPIRV-Tools only if the optimizer is wanted | glslang, SPIRV-Tools and SPIRV-Headers, fetched by a Python script (`git-sync-deps`), or the Android NDK's copy of the sources, which is built with `ndk-build` |
| API | C++ (`TShader`, `TProgram`, `GlslangToSpv`): more code to write, about 150 lines | one C function and an options object: less code; include callbacks built in |
| Reflection of the parsed shader (which uniforms are live, their types) | yes, `TProgram` | none; use SPIRV-Reflect on the output (already vendored) |
| Optimizer | separate call into SPIRV-Tools (`spvtools::Optimizer`), optional | built in, one option |
| Control for our translator | can compile in steps, can read the AST-level reflection, can set the exact client and target versions | the options it exposes (target env, optimization level, macros, auto-bind and auto-map locations) |
| Binary size | to be measured (the optimizer is most of it, so leaving it out is possible) | to be measured; the optimizer is always linked |

## Why glslang
- It is the newer-first source of both languages and fixes; shaderc follows it. For a library that wants to be current with Vulkan, that is the direct line.
- It does not force the optimizer. The checklist already allows dropping the optimization pass if a benchmark shows it does not help; with shaderc the
  optimizer is in the binary regardless, which matters for the Android library size.
- The translator needs to know which loose uniforms the shader actually uses to pack the uniform block and choose push constants. glslang's reflection answers
  that before any SPIR-V is read back; shaderc would need a second pass over the SPIR-V.
- The build is simpler: no Python dependency fetch, and the same CMake path on Windows, Linux and Android.

What shaderc does better, and what we give up: a smaller API and ready-made include handling (we write our own; stock libGDX shaders do not use `#include`).

## Measured so far (glslang 16.6.0 vendored, Windows x64, MSVC Release, no optimizer, no HLSL)
- **Size:** a test program that links the compiler is 1.82 MB, against 0.11 MB for one that does not: about **1.7 MB** for the front end and the SPIR-V generator.
  (`gdxvk.dll` itself is unchanged until the translator is linked into it.) The static library is 35 MB with debug symbols, which are not in the product.
- **Time:** the first compile in a process takes **152 ms** (glslang builds its built-in symbol tables on first use); after that a small shader (the
  sprite vertex shader) compiles in **0.88 ms**. Plan: warm the compiler on a worker thread at startup, and cache the SPIR-V on disk by source hash so a
  second launch does not compile at all.
- **Behaviour:** `shader_compiler_test` (a native test, no GPU) checks vertex, fragment and compute compilation, the SPIR-V magic number, and that an error names the
  shader and the line of the source as given.

## Building without the compiler (2026-11-05)
`gradlew ... -Pgdxvulkan.noCompiler=true` (CMake option `GDXVK_GLSLANG=OFF`; the build goes to `gdx-vulkan-core/build/native-nocompiler`) leaves glslang out of `gdxvk.dll`:
**0.55 MB instead of 2.5 MB** on Windows x64, and a much shorter native build. Everything that ships as SPIR-V works (sprite, polygon and instanced batches, shape renderer,
model batch, shader bundle, the stock classes whose shaders the library recognises); a custom `ShaderProgram` and any shader the bundle lacks report
`this build of gdx-vulkan has no run-time GLSL compiler (GDXVK_GLSLANG=OFF)`. `NoCompilerTest` pins the message and is skipped on a normal build.

## Still to measure
1. Android arm64 and Linux x64 library sizes (the build is wired for them; not measured).
2. The cost of adding the SPIRV-Tools optimizer: size, compile time, and whether its output runs faster (Stress test 2 with a translated sprite shader
   against the precompiled one).
3. First-launch and cached-launch compile times on the phones.

## Pinning
Vendor a pinned glslang release (16.6.0 today) with the SPIRV-Tools and SPIRV-Headers commits that its `known_good.json` names, under `gdx-vulkan-core/jni/vendor`
like volk, VMA and SPIRV-Reflect, with the licences in `NOTICE`. Build it as static libraries inside the existing CMake tree; no HLSL, no tools, no tests.
