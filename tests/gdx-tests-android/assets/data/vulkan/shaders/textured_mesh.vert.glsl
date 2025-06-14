#version 450

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord; // Input for texture coordinates

layout(location = 0) out vec2 fragTexCoord; // Pass UVs to fragment shader

layout(set = 0, binding = 0) uniform UniformBufferObject {
    mat4 model;
    mat4 view;
    mat4 proj;
} ubo;

void main() {
    gl_Position = ubo.proj * ubo.view * ubo.model * vec4(inPosition, 1.0);
    fragTexCoord = inTexCoord;
}