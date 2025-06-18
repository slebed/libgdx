#version 450

layout(location = 0) in vec2 a_position;
layout(location = 1) in float a_color;
layout(location = 2) in vec2 a_texCoord0;
layout(location = 3) in float a_texId;

layout(location = 0) out vec4 v_color;
layout(location = 1) out vec2 v_texCoord0;
layout(location = 2) out flat float v_texId;

layout(set = 0, binding = 0) uniform UniformBufferObject {
    mat4 u_projTrans;
} ubo;

void main() {
    // Unpack the 4x8-bit color from the float
    v_color = unpackUnorm4x8(floatBitsToUint(a_color));
    v_texCoord0 = a_texCoord0;
    v_texId = a_texId;
    gl_Position = ubo.u_projTrans * vec4(a_position, 0.0, 1.0);
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5; // Convert depth range
}