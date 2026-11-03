#version 450
#extension GL_GOOGLE_include_directive : require
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#define GDX_SKIP_SET0
#include "gdx_vk_common.glsl"
#include "gdx_vk_sets.glsl"

layout(location = 0) out vec4 outColor;

// Writes one texture sample out untouched, so a test can read back exactly what the shim stored and how the sampler treats it.
//   texture   chosen by u_indices.y (Tier A slot; ignored on Tier B, which binds the set)
//   uv        u_custom1.xy
//   level     u_custom0.x (textureLod, so no derivatives are involved)
void main() {
    outColor = textureLod(GDX_TEXTURE(gdx_pc.u_indices.y), gdx_pc.u_custom1.xy, gdx_pc.u_custom0.x);
}
