#version 450
#extension GL_GOOGLE_include_directive : require
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#include "gdx_vk_common.glsl"
#include "gdx_vk_sets.glsl"

layout(location = 0) out vec4 outColor;

// Reads one value from each set so a test can tell all three are bound correctly:
//   red   from the frame uniform buffer (Set 0 binding 0)
//   green from element 1 of the storage buffer (Set 0 binding 1)
//   blue  from the texture chosen by u_indices.y (Set 1)
void main() {
    vec4 texel = texture(GDX_TEXTURE(gdx_pc.u_indices.y), vec2(0.5));
    outColor = vec4(gdx_frame.u_frameColor.r, gdx_data[1].g, texel.b, 1.0);
}
