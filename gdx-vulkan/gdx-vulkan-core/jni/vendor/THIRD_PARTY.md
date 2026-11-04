# Vendored third-party code

| Component | Version | Files |
|---|---|---|
| volk | vulkan-sdk-1.4.363.0 | `volk.h`, `volk.c` |
| Vulkan-Headers | v1.4.363 | `vulkan/`, `vk_video/` (C headers only) |
| Vulkan Memory Allocator | v3.5.0 | `vk_mem_alloc.h` |
| SPIRV-Reflect | Vulkan SDK 1.4.309.0 | `spirv-reflect/` (reflection only; Apache-2.0, plus the SPIR-V header) |

Licences are in `licenses/`. Update by copying the files from the tagged upstream release.

## glslang 16.6.0
`vendor/glslang`: the front end (`glslang/`) and the SPIR-V generator (`SPIRV/`) of KhronosGroup/glslang at tag 16.6.0 (commit e1b562a8bed273a02f30b59b66a5d499793cede5),
with its build files. Left out: HLSL, the command-line tools, the tests, the external dependencies (SPIRV-Tools, googletest) and the Python helpers. One header from its
`StandAlone` folder is kept because the C interface includes it. Licences: `vendor/glslang/LICENSE.txt` (glslang is under several permissive licences, listed there).
It is used through `src/gdxvk_shader_compiler.*`; see `docs/SHADER_COMPILER.md`.
