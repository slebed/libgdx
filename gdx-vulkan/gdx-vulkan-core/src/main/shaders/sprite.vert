#version 450
#extension GL_GOOGLE_include_directive : require
#include "gdx_vk_common.glsl"

// VulkanSpriteBatch vertex: position, packed colour, texture coordinates, and the texture's slot in the Tier A bindless table
// (Tier B has no slot: its texture is the one bound descriptor set, so the attribute does not exist in that build).
layout(location = 0) in vec2 a_position;
layout(location = 1) in vec4 a_color;
layout(location = 2) in vec2 a_texCoord;
#ifdef GDX_TIER_A
layout(location = 3) in uint a_slot;
#endif

layout(location = 0) out vec4 v_color;
layout(location = 1) out vec2 v_texCoord;
#ifdef GDX_TIER_A
layout(location = 2) flat out uint v_slot;
#endif

void main() {
    v_color = a_color;
    v_color.a = v_color.a * (255.0 / 254.0); // stock SpriteBatch's shader does this: the packed alpha tops out at 254
    v_texCoord = a_texCoord;
#ifdef GDX_TIER_A
    v_slot = a_slot;
#endif
    gl_Position = gdx_vk_clip(gdx_pc.u_transform * vec4(a_position, 0.0, 1.0));
}
