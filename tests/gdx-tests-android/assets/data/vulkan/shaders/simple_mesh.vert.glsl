#version 450

// Input vertex position from your VulkanMesh
// Assumes 'Position' attribute is at location 0
layout(location = 0) in vec3 inPosition;

// Uniform Buffer Object for transformation matrices
// Assumes this UBO is bound to descriptor set 0, binding 0
layout(set = 0, binding = 0) uniform UniformBufferObject {
    mat4 model; // Model transformation matrix
    mat4 view;  // View (camera) matrix
    mat4 proj;  // Projection matrix
} ubo;

void main() {
    // Transform vertex position to clip space
    gl_Position = ubo.proj * ubo.view * ubo.model * vec4(inPosition, 1.0);
}