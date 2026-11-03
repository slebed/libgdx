// The descriptor sets of the one pipeline layout (plan, Core decision 2). Include after gdx_vk_common.glsl, only in shaders that use them.
// Every shader is compiled twice: plain (Tier B) and with -DGDX_TIER_A=1 (<name>.a.spv). Tier A shaders that sample must also
// declare `#extension GL_EXT_nonuniform_qualifier : require` before any other token.
#ifndef GDX_VK_SETS_GLSL
#define GDX_VK_SETS_GLSL

#ifndef GDX_SKIP_SET0 // define before including to use set 1 only (a shader that declares set 0 expects it to be bound)
// Set 0 binding 0: camera and frame globals. At most 16 KB. UNIFORM_BUFFER_DYNAMIC.
layout(std140, set = 0, binding = 0) uniform GdxFrame {
    vec4 u_frameColor;
    vec4 u_frameParams;
} gdx_frame;

// Set 0 binding 1: transforms, materials, bones, lights. STORAGE_BUFFER_DYNAMIC.
layout(std430, set = 0, binding = 1) readonly buffer GdxData {
    vec4 gdx_data[];
};

#endif

// Set 1 binding 0: textures.
#ifdef GDX_TIER_A
layout(set = 1, binding = 0) uniform sampler2D gdx_textures[]; // global bindless table
#define GDX_TEXTURE(slot) gdx_textures[nonuniformEXT(slot)]
#else
layout(set = 1, binding = 0) uniform sampler2D gdx_texture;    // the one bound texture
#define GDX_TEXTURE(slot) gdx_texture
#endif

#endif
