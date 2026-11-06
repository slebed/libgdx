# The stock GLSL translator

Status: steps 1 to 4 below (assemble, preprocess, translate, compile) and the program class are built and tested on both GPUs and both code paths; step 5 (the reflection check) and the GL-shim calls are not. The checklist section "Phase 5b" tracks the work. Related: `docs/SHADER_COMPILER.md` (why glslang).

## What it is for
A game's own libGDX shaders: the `attribute` / `varying` / `uniform` GLSL that goes into `new ShaderProgram(vertexSource, fragmentSource)`. The translator turns them
into Vulkan GLSL, glslang compiles that to SPIR-V, and the result runs on the library's one pipeline layout.

What it is **not** for: the library's own shaders. The sprite batches, shape renderer, model batch, shadow pass, effects and compute work are dedicated Vulkan
shaders compiled at build time; stock classes that carry libGDX's own shader text will be recognised and given those (checklist, section E). Base functionality
never depends on the translator.

## The pipeline
1. **Assemble** the source the way `ShaderProgram` does: `prependVertexCode` / `prependFragmentCode`, then the source.
2. **Preprocess** with glslang's preprocessor (`#ifdef`, `#define`, `#if`, `#extension`, macros). libGDX's shaders choose their variants with `#ifdef` flags
   (`DefaultShader` has dozens), so the translator reads the **expanded** text, where each declaration is final. The preprocessor keeps `#line` directives, so every
   later error still names the line of the original source.
3. **Translate** the declarations (text, line by line; the number of lines never changes):
   - drop `#version`, `precision` and the `GL_ES` conventions; the translated text is `#version 450`;
   - `attribute T name;` becomes `layout(location = N) in T name;`, N in declaration order;
   - `varying T name;` becomes `out` in the vertex shader and `in` in the fragment shader, with the **same location** for the same name in both stages (a varying
     that the fragment shader declares and the vertex shader does not write is a zero global, as OpenGL drivers read it; when the fragment shader reads it, a note
     goes to stderr);
   - `gl_FragColor` becomes a declared `layout(location = 0) out vec4`; `gl_FragData[n]` becomes outputs 0..n; each output is set to zero at the start of `main`, so
     a fragment that writes nothing on some path leaves (0, 0, 0, 0), as OpenGL does in practice, not whatever the register held;
   - `gl_FragCoord` becomes `gdx_FragCoordGL(gdx_fragTarget)`: OpenGL counts from the bottom left, the swapchain pass of Vulkan from the top left. The implicit uniform
     `gdx_fragTarget` (screen width, height, 1 on the swapchain; 0 offscreen, where the layout already is OpenGL's) is set by the shim at every draw; it takes the
     pre-rotation into account;
   - `dFdx(v)` and `dFdy(v)` become `gdx_dFdx(gdx_fragTarget, v)` and `gdx_dFdy(...)`: the hardware takes them along the framebuffer's axes, which on the swapchain
     run y downwards (and turn with the pre-rotation), so every shader that builds a tangent frame from derivatives (normal mapping without tangents) would show its
     relief inverted. They are returned along OpenGL's axes, x to the right and y upwards;
   - `texture2D`, `texture2DProj`, `texture2DLod`, `textureCube` become `texture`, `textureProj`, `textureLod`;
   - `gl_Position = x;` becomes `gl_Position = gdx_vk_clip(x);` (depth range and the Android pre-rotation, the one line every built-in vertex shader also has);
   - loose `uniform` declarations are removed and re-declared as described next.
4. **Compile** with glslang (`#version 450`, Vulkan 1.1 target, SPIR-V 1.3), once per code path (modern: bindless textures; compatibility: one bound texture).
5. **Reflect**: the existing SPIRV-Reflect validation checks the result (block layout, vertex inputs, sampler count).

## Where the uniforms go (the optimisation rules)
OpenGL sets uniforms one call at a time and re-uploads them at the driver's pleasure. Vulkan has two good places for them, and the translator uses both:

- **Push constants** (128 bytes, no descriptor, no memory to manage): every non-array uniform that fits, in declaration order, laid out with explicit offsets. A
  `SpriteBatch`-style shader (`u_projTrans`, 64 bytes) lives entirely here, so setting it is a push at draw time, not a descriptor update.
- **The uniform ring** (set 0, binding 0, the per-frame dynamic uniform buffer, at most 16 KB): arrays, and any uniform that no longer fits the push budget. The
  block is written into the frame ring only when something in it changed, and only the bytes that changed are rewritten on the CPU side; unchanged draws reuse the
  last binding.
- **Texture slots** (modern path): each `sampler2D` uniform becomes an index into the bindless table, kept as a 4-byte member of the push block; `u_texture` in the
  body becomes `gdx_textures[nonuniformEXT(slot)]`. Binding a texture is then a 4-byte push, not a descriptor write. On the compatibility path the texture is the one
  bound descriptor, so a program with more than one sampler is refused there with a clear message (the same limit the model batch has).
- `bool` uniforms are stored as `int` and read through a macro (`#define u_flag (gdx_b_u_flag != 0)`): blocks cannot hold `bool` in Vulkan.
- Matrices of 2x2 and 3x3 keep their column padding (16 bytes per column); the program class that sets them knows the padding from the layout table.
- Uniforms the shader never reads (found after preprocessing) take no space.

The result of a translation carries the table the program class needs: for each uniform its name, type, where it lives (push or ring), byte offset, array size and column
stride; for each attribute its name, type and location; for each sampler its name and push offset; and the line map. Setting a uniform by name is then a copy into a
byte array plus a dirty flag.

## Struct uniforms
`uniform DirectionalLight u_dirLights[2];` (libGDX's `DefaultShader` lights) works. The struct definition is taken out of the sources and declared once in the prelude
of both stages; the uniform becomes a member of the std140 block at set 0, binding 0, with its explicit offset (the struct starts on 16 bytes, members follow their own
alignment, the struct size and the array stride round up to 16). The program's table lists every member as a uniform of its own, named the way GL names it
(`u_dirLights[1].color`), element by element, so the location of a member in one element minus the location in the next is the same for every member (the light
stride `BaseShader` computes from two locations). Members may be float, int, vec2-4 and ivec2-4.

## Known shaders
libGDX's own sprite and shape shaders are not translated. `KnownShaders` hashes the vertex and fragment source (white space reduced to one space) as `glShaderSource`
or `new VulkanShaderProgram` gets it, and a match gets the dedicated, build-time compiled shader with hand-written tables (`sprite_single` for `SpriteBatch` and
`PolygonSpriteBatch`, `shape` for `ShapeRenderer`'s `ImmediateModeRenderer20`; the shape shader's sub-pixel line nudge is written by the GL shim for lines and points).
Any other source goes through the translator.

## The bundle
libGDX's `DefaultShader` and `DepthShader` are flag-driven (the flags are `#define`s that also size the light arrays), so they are not replaced by hand-written
shaders. They are translated and compiled at build time instead: `gradlew :gdx-vulkan-desktop:recordShaderBundle` draws a matrix of materials, vertex layouts and lights
through a stock `ModelBatch` and keeps every program in `gdx-vulkan-core/src/main/resources/shaders/bundle`, one file per code path and variant, named by the
same hash the disk cache uses (it includes the translator and compiler versions, so a changed translator makes the files unreachable and
`BundledShadersTest` fails). A variant outside the matrix is translated at run time and cached on disk.

## What it refuses (with a message naming the shader, the line and the construct)
`samplerCube` and 3D samplers (the texture shim has no cube textures yet), samplers passed to functions, structs with array or matrix members or used as anything but a uniform type (a uniform struct of floats, ints and vectors, or an array of them, is supported), `bool` arrays, a
program with more samplers than the path allows, uniform data over 16 KB, desktop GL built-ins that have no Vulkan meaning (`gl_ModelViewMatrix`, `ftransform`).
Everything else stays a glslang error with the original line number.

## Tests
- Every shader libGDX ships through `ShaderProgram` (the `SpriteBatch` and `ShapeRenderer` shaders, the `ImmediateModeRenderer20` ones, `DefaultShader` and
  `DepthShader` with their flag variants) is translated and compiled on both paths; the layout table is checked (the sprite shader: one push uniform, one slot).
- A custom shader that uses arrays, a `bool`, an `int`, two samplers and `#ifdef` flags.
- Errors: the line number of the original source survives preprocessing and translation, including through `prependVertexCode`.
- The run-time compiler call count is zero in every built-in code path (the translator is never on the path of base functionality).

## The program class (step 2, built)
`VulkanShaderProgram` owns a translation and the SPIR-V, and keeps the uniform values: setting one writes a word into the program's push-constant array or its
uniform-block array. A batch asks the program to get ready for a draw: it writes each texture's slot (modern path) or binds the one texture (compatibility path),
copies the push words into the batch's push block, and emits the uniform block when it changed since the program last bound it in this frame. The batch sets
`u_projTrans` and `u_texture` first, as stock's `SpriteBatch` does, and flushes on every texture change, as stock does. Pipelines are built per program and blend
state, with the vertex attributes (`a_position`, `a_color`, `a_texCoord0`) placed at the locations the program gave them.

## The cache
The cache key includes `ShaderCache.VERSIONS`; `TranslatorVersionTest` hashes `GlslTranslator.java` and fails when it changed without a new version, a bundle re-record and the new hash in `src/test/resources/translator-source.sha256`.

`ShaderCache` writes one file per program (`<sha-256 prefix>.spvprog`): both SPIR-V modules and the translation tables, named from a hash of the sources, the
prepend text, the code path and the translator and compiler versions. Finding the file skips the preprocessor, the translator and the compiler, including
glslang's first-use start-up (152 ms in the native test). `VulkanShaderProgram.cacheDirectory` chooses the folder (null turns it off); the default is
`gdxvk-shader-cache` in local storage. Files that cannot be read or have another format number are ignored and replaced; failing to write is ignored.

## Speed
`stressShaders` draws 20,000 sprites from one texture with the built-in shader, the translated stock sprite shader and a custom shader, twice each in turn: the
translated stock shader runs at 0.97x to 1.00x of the built-in one on the two PC GPUs and both code paths. The workload is CPU-bound and the shaders trivial, so
this shows the translation adds no cost of its own.

## Not yet
Programs for the model batch, the SPIRV-Reflect check on translated shaders, compiling off the render thread, and the GL-shim calls that let a stock `ShaderProgram`
run unchanged (checklist sections B, C and E).
