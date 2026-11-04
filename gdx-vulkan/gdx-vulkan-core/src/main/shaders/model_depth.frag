#version 450
#extension GL_GOOGLE_include_directive : require
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#include "gdx_vk_common.glsl"
#include "gdx_vk_sets.glsl"

// The shadow-map depth pass: libGDX's depth.fragment.glsl (packed depth in RGBA8). C_ALPHA_TEST: the material is blended with a diffuse
// texture, so texels under the alpha-test threshold (record vec4 10, z) are discarded.
layout(constant_id = 7) const bool C_ALPHA_TEST = false;

layout(location = 1) in vec2 v_texCoords0;
layout(location = 6) in float v_depth;
layout(location = 7) flat in int v_inst;

layout(location = 0) out vec4 outColor;

void main() {
    if (C_ALPHA_TEST && texture(GDX_TEXTURE(gdx_pc.u_indices.y), v_texCoords0).a < gdx_data[v_inst + 10].z) discard;
    const float depth = v_depth;
    const vec4 bias = vec4(1.0 / 255.0, 1.0 / 255.0, 1.0 / 255.0, 0.0);
    const vec4 color = vec4(depth, fract(depth * 255.0), fract(depth * 65025.0), fract(depth * 16581375.0));
    outColor = color - (color.yzww * bias);
}
