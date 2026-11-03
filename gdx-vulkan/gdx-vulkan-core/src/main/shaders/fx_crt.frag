#version 450
#extension GL_GOOGLE_include_directive : require
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#define GDX_SKIP_SET0
#include "gdx_vk_common.glsl"
#include "gdx_vk_sets.glsl"

// Gate 3 post-processing: CRT look. u_custom0.x = scanline count, .y = vignette strength.

layout(location = 0) in vec4 v_color;
layout(location = 1) in vec2 v_texCoord;
#ifdef GDX_TIER_A
layout(location = 2) flat in uint v_slot;
#else
const uint v_slot = 0u;
#endif

layout(location = 0) out vec4 outColor;

void main() {
    vec4 c = v_color * texture(GDX_TEXTURE(v_slot), v_texCoord);
    float scan = 0.85 + 0.15 * sin(v_texCoord.y * gdx_pc.u_custom0.x * 6.2831853);
    vec2 d = v_texCoord - vec2(0.5);
    float vignette = 1.0 - dot(d, d) * gdx_pc.u_custom0.y;
    outColor = vec4(c.rgb * scan * vignette, c.a);
}
