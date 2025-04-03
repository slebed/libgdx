#version 450

layout(location = 0) in vec2 inPosition;    // Position of the vertex (x, y)
layout(location = 1) in vec3 inColor;       // Color of the vertex (r, g, b)

layout(location = 0) out vec3 fragColor;    // Pass color to the fragment shader

layout(push_constant) uniform PushConstants {
    mat4 model;
    mat4 view;
    mat4 proj;
} pushConstants;

void main() {
    fragColor = inColor;
    gl_Position = pushConstants.proj * pushConstants.view * pushConstants.model * vec4(inPosition, 0.0, 1.0);
}
