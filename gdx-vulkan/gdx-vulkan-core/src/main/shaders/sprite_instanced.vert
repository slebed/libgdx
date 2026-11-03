#version 450
#extension GL_GOOGLE_include_directive : require
#define GDX_SKIP_SET0
#include "gdx_vk_common.glsl"

// VulkanSpriteBatchInstanced: no vertex buffer. Six vertices per sprite, expanded from the sprite buffer (see sprite_move.comp) by
// gl_VertexIndex. The tint is u_color, the texture slot (Tier A) is u_indices.y and the sprite size is u_custom1.xy.
layout(std430, set = 0, binding = 1) readonly buffer GdxSprites {
    vec4 sprites[];
};

layout(location = 0) out vec4 v_color;
layout(location = 1) out vec2 v_texCoord;
#ifdef GDX_TIER_A
layout(location = 2) flat out uint v_slot;
#endif

const vec2 kCorner[6] = vec2[6](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(1.0, 1.0), vec2(0.0, 1.0), vec2(0.0, 0.0));

void main() {
    uint sprite = uint(gl_VertexIndex) / 6u;
    vec2 c = kCorner[uint(gl_VertexIndex) % 6u];
    vec2 pos = sprites[sprite].xy + c * gdx_pc.u_custom1.xy;
    v_color = gdx_pc.u_color;
    v_texCoord = vec2(c.x, 1.0 - c.y); // the lower-left corner shows the texture's bottom-left, as the stock SpriteBatch draws a texture
#ifdef GDX_TIER_A
    v_slot = uint(gdx_pc.u_indices.y);
#endif
    gl_Position = gdx_vk_clip(gdx_pc.u_transform * vec4(pos, 0.0, 1.0));
}
