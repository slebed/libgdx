#version 450
#extension GL_EXT_nonuniform_qualifier : require

layout(location = 0) in vec4 v_color;
layout(location = 1) in vec2 v_texCoord0;
layout(location = 2) in flat float v_texId;

layout(location = 0) out vec4 out_color;

layout(set = 0, binding = 1) uniform sampler2D u_textures[];

void main() {
    // Sample the correct texture from the array using the index from the vertex shader
    out_color = v_color * texture(u_textures[int(v_texId)], v_texCoord0);
}