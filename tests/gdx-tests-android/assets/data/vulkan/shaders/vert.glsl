#version 450 // Target Vulkan compatible GLSL version
// Input vertex attributes (location must match pipeline setup)
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec3 inColor;

// Output to fragment shader (location must match fragment shader input)
layout(location = 0) out vec3 fragColor;

void main() {
    // Output position directly (no matrices yet)
    gl_Position = vec4(inPosition, 0.0, 1.0);
    // Pass color through
    fragColor = inColor;
}