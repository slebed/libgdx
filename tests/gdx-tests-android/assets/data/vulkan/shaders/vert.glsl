#version 450 // Target Vulkan compatible GLSL version
#extension GL_ARB_separate_shader_objects : enable

// Input vertex attributes (matching pipeline setup)
layout(location = 0) in vec2 inPosition;
layout(location = 1) in float inPackedColor; // Expects packed float
layout(location = 2) in vec2 inTexCoord;

// Uniform Buffer Object (matching descriptor set layout binding 0)
layout(binding = 0) uniform Ubo {
    mat4 projectionMatrix;
} ubo;

// Output to fragment shader
layout(location = 0) out vec4 fragColor; // Use vec4 for color+alpha
layout(location = 1) out vec2 fragTexCoord;

void main() {
    // Apply projection matrix
    gl_Position = ubo.projectionMatrix * vec4(inPosition, 0.0, 1.0);

    // Pass texture coordinate through
    fragTexCoord = inTexCoord;

    // Unpack color (LibGDX packs ABGR into the float bits)
    uint packedInt = floatBitsToUint(inPackedColor);
    fragColor.a = float((packedInt >> 24) & 0xFF) / 255.0;
    fragColor.b = float((packedInt >> 16) & 0xFF) / 255.0;
    fragColor.g = float((packedInt >> 8)  & 0xFF) / 255.0;
    fragColor.r = float( packedInt        & 0xFF) / 255.0;
}
/*
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
}*/
