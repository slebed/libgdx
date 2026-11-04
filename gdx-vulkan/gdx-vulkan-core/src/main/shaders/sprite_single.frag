#version 450
#extension GL_GOOGLE_include_directive : require
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#define GDX_SKIP_SET0
#include "gdx_vk_common.glsl"
#include "gdx_vk_sets.glsl"

// VulkanSpriteBatch fragment for a batch whose sprites all use one texture. The texture slot is read from the push constants here, not passed down from the
// vertex shader: a value the compiler can see is the same for the whole draw lets the GPU read the bindless table directly. Through a flat varying it has to
// assume every pixel may use another texture, which on Adreno (checked on the 840) made this shader about 2.8 times slower.
layout(location = 0) in vec4 v_color;
layout(location = 1) in vec2 v_texCoord;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = v_color * texture(GDX_TEXTURE(uint(gdx_pc.u_indices.y)), v_texCoord);
}
