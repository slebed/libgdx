#version 450
#extension GL_GOOGLE_include_directive : require
#include "gdx_vk_common.glsl"

// VulkanImmediateModeRenderer / VulkanShapeRenderer: position, packed colour. Points are one pixel, as in stock's ShapeRenderer.
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec4 a_color;

layout(location = 0) out vec4 v_color;

void main() {
    v_color = a_color;
    v_color.a = v_color.a * (255.0 / 254.0); // as in stock's immediate-mode shader
    gl_PointSize = 1.0;
    gl_Position = gdx_vk_clip(gdx_pc.u_transform * vec4(a_position, 1.0));
    // GL and Vulkan resolve a line or point that lies exactly on a pixel boundary to different pixels (GL's diamond-exit rule against
    // Vulkan's rules, mirrored by the Y flip). The renderer passes a sub-pixel nudge, in clip units per w, in u_custom0.x for lines and
    // points so the tie goes GL's way; it is 0 for triangles, which have no such ambiguity.
    gl_Position.y += gdx_pc.u_custom0.x * gl_Position.w;
}
