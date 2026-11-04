#version 450
#extension GL_GOOGLE_include_directive : require
#include "gdx_vk_common.glsl"

// A stock Mesh with Position and Normal attributes, drawn in one flat colour (u_color) with a hint of shading from the normal. For tests and
// debugging: the stock-compatible shading of ModelBatch lives in the default shader.
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec3 a_normal;

layout(location = 0) out vec4 v_color;

void main() {
    float shade = 0.75 + 0.25 * normalize(a_normal).y;
    v_color = vec4(gdx_pc.u_color.rgb * shade, gdx_pc.u_color.a);
    gl_Position = gdx_vk_clip(gdx_pc.u_transform * vec4(a_position, 1.0));
}
