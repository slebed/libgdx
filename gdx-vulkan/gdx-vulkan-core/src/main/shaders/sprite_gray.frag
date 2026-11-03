#version 450
#extension GL_GOOGLE_include_directive : require
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#define GDX_SKIP_SET0
#include "gdx_vk_common.glsl"
#include "gdx_vk_sets.glsl"

// Example VulkanShaderPipelineBundle fragment shader: the sprite shader with u_custom0.x blending the texel towards its luminance
// (0 = unchanged, 1 = grayscale).

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
    float g = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    outColor = vec4(mix(c.rgb, vec3(g), gdx_pc.u_custom0.x), c.a);
}
