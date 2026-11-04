# Driver notes

What particular drivers refuse or do slowly, how it was found, and the rule the code follows. Each rule is kept in the shaders and, where a test can check it
without the device, in a test. When a new device misbehaves, start with the method at the end.

## Adreno 650 (Galaxy S20 FE, Vulkan 1.1 driver): addresses built from a push constant

**Symptom.** Every `VulkanModelBatch` pipeline failed with `VK_ERROR_INITIALIZATION_FAILED` and, in logcat, `Adreno-GSL: GPUOBJ_ALLOC Invalid argument`; the
driver's compiler also crashed the app on pipelines created after a refused one. 2D, framebuffers, compute, instancing and the stock `ModelBatch` through the GL shim
worked.

**Cause.** A vertex shader that reads the data buffer (`gdx_data`, Set 0 binding 1) at an address that depends on a push constant, and uses several of those reads
in arithmetic with per-vertex data, is refused. The model shader found its per-renderable record with `gdx_pc.u_indices.x + gl_InstanceIndex * 15`.

What was measured on the device (a probe pipeline per case; "refused" is the failure above):

| Reads of the buffer, multiplied with vertex data | Result |
|---|---|
| constant indices, 4 to 32 `vec4`s, storage buffer or uniform buffer | builds |
| index from `gl_InstanceIndex` (`gl_InstanceIndex * 40 + k`), 8 and 32 `vec4`s | builds |
| index from the vertex (`(gl_VertexIndex >> 30) + k`), 8 and 32 `vec4`s | builds |
| index from a push constant (`gdx_pc.u_indices.x + k`), storage buffer, 4 to 32 `vec4`s | **refused** |
| the same through a uniform buffer | builds up to 6 `vec4`s, **refused** from 7 |
| a branch on a push constant choosing between two windows of constant reads | **refused** |
| one buffer matrix times one push-constant matrix | builds |
| two buffer matrices multiplied together, written as `mat4` operators or column by column | **refused** |
| 16 reads at a push-constant address that are only summed (no arithmetic with vertex data) | builds |

Not the shader's size or features: the full model shader and a stub with thirteen constant outputs behaved the same; neither the fragment shader, the 13 varyings,
`flat` varyings, `discard`, derivatives, specialization constants (18 values to a shader that declares none), the depth buffer, the vertex layout nor the cull mode
mattered.

**Rule.** A buffer read in a vertex shader is addressed from `gl_InstanceIndex` (or a constant), never from a push constant. A draw names its record with its
**first instance**: `CommandStream.drawIndexedInstanced(..., instanceCount, firstInstance)` and `drawInstanced(...)`; `model_default_vertex.glsl` and
`model_depth_vertex.glsl` use `HEADER + gl_InstanceIndex * RECORD`. Checked by `DriverRulesTest` (no `gdx_pc` inside a `gdx_data[...]` in the model shaders) and, on the device,
by the Gate 5 scenes of the device suite. Desktop GPUs and the Adreno 840 are unaffected by the change (their goldens are unchanged).

## Adreno 840 (REDMAGIC): a bindless index through a varying

The sprite batch's single-texture fragment shader got its texture slot through a flat varying, which the compiler could not prove was the same for every pixel, so
indexing the bindless table took a slow path (548 fps against 1,428 on the compatibility path). **Rule:** an index that is the same for the whole draw comes from the
push constants (`sprite_single.frag`), never through a varying.

## Windows: a runtime DLL from the JVM's folder

Android Studio's bundled Java has an old `msvcp140.dll` in its own `bin`; Windows loads that one first, and a library built with a newer Visual C++ crashes in it the
first time it creates a `std::mutex`. **Rule:** the Windows natives link the C++ runtime statically (`CMAKE_MSVC_RUNTIME_LIBRARY` in `jni/CMakeLists.txt`).

## Method: finding what a driver refuses

1. Reproduce it in the smallest place that fails. The device suite builds pipelines through `GdxVkFrame.pipeline(key, name, vertName, fragName, desc)`; a check that
   builds one from a hand-written shader pair, with the model batch's pipeline description (two vertex attributes, back-face culling, the swapchain depth buffer),
   is the harness. Log before the call: a refused pipeline can take the app down and the log must still say which shader it was.
2. Run each case in its own process (`tools/device-suite.sh <serial> --es only <check name>`): after one refusal the compiler may crash on the next pipeline.
3. Change one thing at a time from a case that builds towards one that fails (the "taken apart" stub: position from the buffer, world from the buffer, a varying from the
   buffer, `gl_InstanceIndex`, the unused attribute), then vary the one construct (how many reads, which address, which buffer type).
4. When the real shader is too big to reason about, replace both stages with trivial ones that keep only the suspect (the failure survived that here, which showed the
   cause was in the vertex stage's buffer addressing and not in the fragment shader or the pipeline state).
5. Keep the result as a table like the one above, a rule, and a check that fails without the device if the rule can be stated on the source.
