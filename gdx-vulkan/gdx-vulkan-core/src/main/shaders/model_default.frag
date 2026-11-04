#version 450
#extension GL_GOOGLE_include_directive : require
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#include "gdx_vk_common.glsl"
#include "gdx_vk_sets.glsl"

// VulkanModelBatch fragment shader: a port of libGDX's default.fragment.glsl (see model_default_vertex.glsl for the data layout). The material
// and lighting flags the stock shader chose with #define are specialization constants, the same ids as in the vertex shader.
layout(constant_id = 1) const bool C_LIGHTING = false;
layout(constant_id = 2) const bool C_DIFFUSE_TEX = false;
layout(constant_id = 3) const bool C_DIFFUSE_COLOR = false;
layout(constant_id = 4) const bool C_SPECULAR = false;
layout(constant_id = 5) const bool C_EMISSIVE = false;
layout(constant_id = 6) const bool C_BLENDED = false;
layout(constant_id = 7) const bool C_ALPHA_TEST = false;
layout(constant_id = 11) const bool C_COLOR = false; // the mesh has vertex colours

layout(location = 0) in vec4 v_color;
layout(location = 1) in vec2 v_diffuseUV;
layout(location = 2) in vec3 v_lightDiffuse;
layout(location = 3) in vec3 v_lightSpecular;

layout(location = 0) out vec4 outColor;

void main() {
    const int inst = gdx_pc.u_indices.x;
    const vec4 params = gdx_data[inst + 10];

    vec4 diffuse = vec4(1.0);
    if (C_DIFFUSE_TEX) diffuse = texture(GDX_TEXTURE(gdx_pc.u_indices.y), v_diffuseUV);
    if (C_DIFFUSE_COLOR) diffuse *= gdx_data[inst + 7];
    if (C_COLOR) diffuse *= v_color;

    vec4 emissive = vec4(0.0);
    if (C_EMISSIVE) emissive = gdx_data[inst + 9];

    vec3 rgb;
    if (!C_LIGHTING) {
        rgb = diffuse.rgb + emissive.rgb;
    } else if (!C_SPECULAR) {
        rgb = (diffuse.rgb * v_lightDiffuse) + emissive.rgb;
    } else {
        const vec3 specular = gdx_data[inst + 8].rgb * v_lightSpecular;
        rgb = (diffuse.rgb * v_lightDiffuse) + specular + emissive.rgb;
    }

    float a = 1.0;
    if (C_BLENDED) {
        a = diffuse.a * params.y;
        if (C_ALPHA_TEST && a <= params.z) discard;
    }
    outColor = vec4(rgb, a);
}
