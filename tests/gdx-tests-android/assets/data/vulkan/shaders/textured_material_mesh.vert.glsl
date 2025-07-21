#version 450

// Vertex attributes from the mesh
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal; // We'll pass the normal through
layout(location = 2) in vec2 inTexCoord;

// Per-Frame Data
layout(set = 0, binding = 0) uniform CameraUBO {
    mat4 view;
    mat4 proj;
} cameraData;

// Per-Object Data
layout(set = 1, binding = 0) uniform ModelUBO {
    mat4 model;
} modelData;

// Outputs to the Fragment Shader
layout(location = 0) out vec3 fragNormal;
layout(location = 1) out vec2 fragTexCoord;

void main() {
    gl_Position = cameraData.proj * cameraData.view * modelData.model * vec4(inPosition, 1.0);
    fragNormal = mat3(transpose(inverse(modelData.model))) * inNormal;
    fragTexCoord = inTexCoord;
}