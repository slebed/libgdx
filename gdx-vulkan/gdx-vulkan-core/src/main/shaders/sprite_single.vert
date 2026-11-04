#version 450
#extension GL_GOOGLE_include_directive : require
#include "gdx_vk_common.glsl"

// VulkanSpriteBatch vertex for a batch whose sprites all use one texture: stock's 5-float vertex as is (20 bytes), and on Tier A the
// texture slot comes from the push constants (u_indices.y) instead of from every vertex, read by sprite_single.frag. Lets the batch copy its vertices in one block.
layout(location = 0) in vec2 a_position;
layout(location = 1) in vec4 a_color;
layout(location = 2) in vec2 a_texCoord;

layout(location = 0) out vec4 v_color;
layout(location = 1) out vec2 v_texCoord;

void main() {
    v_color = a_color;
    v_color.a = v_color.a * (255.0 / 254.0); // stock SpriteBatch's shader does this: the packed alpha tops out at 254
    v_texCoord = a_texCoord;
    gl_Position = gdx_vk_clip(gdx_pc.u_transform * vec4(a_position, 0.0, 1.0));
}
