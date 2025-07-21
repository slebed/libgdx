#version 450

// Input vertex attributes from the mesh
// location = 0 corresponds to the first attribute (Position)
layout(location = 0) in vec3 inPosition;
// location = 1 corresponds to the second attribute (ColorUnpacked)
layout(location = 1) in vec4 inColor;

// Output variable to be passed to the fragment shader
layout(location = 0) out vec4 fragColor;

// Uniform Buffer Object containing the transformation matrices
// Corresponds to set = 0, binding = 0 in the pipeline layout
layout(set = 0, binding = 0) uniform TransformUBO {
    mat4 model;
    mat4 view;
    mat4 proj;
} ubo;

void main() {
    // Transform the vertex position from model space to clip space
    gl_Position = ubo.proj * ubo.view * ubo.model * vec4(inPosition, 1.0);

    // Pass the vertex color directly to the fragment shader
    fragColor = inColor;
}
