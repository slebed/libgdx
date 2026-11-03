#version 450
#extension GL_GOOGLE_include_directive : require
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#define GDX_SKIP_SET0
#include "gdx_vk_common.glsl"
#include "gdx_vk_sets.glsl"

// Gate 3 post-processing: one direction of a separable 9-tap Gaussian blur. u_custom0.xy = the step between taps, in texture coordinates.

layout(location = 0) in vec4 v_color;
layout(location = 1) in vec2 v_texCoord;
#ifdef GDX_TIER_A
layout(location = 2) flat in uint v_slot;
#else
const uint v_slot = 0u;
#endif

layout(location = 0) out vec4 outColor;

void main() {
    const float w0 = 0.227027, w1 = 0.1945946, w2 = 0.1216216, w3 = 0.054054, w4 = 0.016216;
    vec2 s = gdx_pc.u_custom0.xy;
    vec4 sum = texture(GDX_TEXTURE(v_slot), v_texCoord) * w0;
    sum += (texture(GDX_TEXTURE(v_slot), v_texCoord + s) + texture(GDX_TEXTURE(v_slot), v_texCoord - s)) * w1;
    sum += (texture(GDX_TEXTURE(v_slot), v_texCoord + 2.0 * s) + texture(GDX_TEXTURE(v_slot), v_texCoord - 2.0 * s)) * w2;
    sum += (texture(GDX_TEXTURE(v_slot), v_texCoord + 3.0 * s) + texture(GDX_TEXTURE(v_slot), v_texCoord - 3.0 * s)) * w3;
    sum += (texture(GDX_TEXTURE(v_slot), v_texCoord + 4.0 * s) + texture(GDX_TEXTURE(v_slot), v_texCoord - 4.0 * s)) * w4;
    outColor = vec4(sum.rgb * v_color.rgb, 1.0);
}
