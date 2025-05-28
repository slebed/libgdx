#version 450
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;
layout(location = 0) out vec2 fragTexCoord;

layout(set = 0, binding = 0) uniform TransformUBO { // Was GlobalUBO + ObjectUBO
    mat4 model; // Model matrix now here
    mat4 view;
    mat4 proj;
} transformData;

void main() {
    gl_Position = transformData.proj * transformData.view * transformData.model * vec4(inPosition, 1.0);
    fragTexCoord = inTexCoord;
}