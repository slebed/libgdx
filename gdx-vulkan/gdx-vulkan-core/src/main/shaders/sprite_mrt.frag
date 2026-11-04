#version 450
#extension GL_GOOGLE_include_directive : require
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#define GDX_SKIP_SET0
#include "gdx_vk_common.glsl"
#include "gdx_vk_sets.glsl"

// Writes three colour attachments, for a VulkanMultiTargetFrameBuffer: 0 the sprite colour, 1 and 2 the same colour times u_custom0.x (a gain
// above 1 only survives in a floating-point attachment).

layout(location = 0) in vec4 v_color;
layout(location = 1) in vec2 v_texCoord;
#ifdef GDX_TIER_A
layout(location = 2) flat in uint v_slot;
#else
const uint v_slot = 0u;
#endif

layout(location = 0) out vec4 outColor0;
layout(location = 1) out vec4 outColor1;
layout(location = 2) out vec4 outColor2;

void main() {
    vec4 c = v_color * texture(GDX_TEXTURE(v_slot), v_texCoord);
    outColor0 = c;
    outColor1 = vec4(c.rgb * gdx_pc.u_custom0.x, c.a);
    outColor2 = vec4(c.rgb * gdx_pc.u_custom0.x, c.a);
}
