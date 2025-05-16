#version 450

// Vertex Attributes
layout(location = 0) in vec2 a_position;   // Usage.Position (vec2)
layout(location = 1) in float a_color;     // Usage.ColorPacked (float) - Packed ABGR
layout(location = 2) in vec2 a_texCoord0;  // Usage.TextureCoordinates (vec2)
layout(location = 3) in float a_texIndex;    // NEW: Texture index per vertex

// UBO Binding (No change)
layout(std140, binding = 0) uniform UniformBufferObject {
    mat4 projTrans;
} ubo;

// Output to Fragment Shader
layout(location = 0) out vec4 v_color;
layout(location = 1) out vec2 v_texCoords;
layout(location = 2) out flat float v_texIndex; // NEW: Pass texture index (use 'flat' for integers)

// Function to unpack the ABGR float color (No change)
vec4 unpackColor(float packedColor) {
    uint intBits = floatBitsToUint(packedColor);
    float a = float((intBits >> 24) & 0xFFu) / 255.0;
    float b = float((intBits >> 16) & 0xFFu) / 255.0;
    float g = float((intBits >> 8)  & 0xFFu) / 255.0;
    float r = float( intBits        & 0xFFu) / 255.0;
    return vec4(r, g, b, a);
}

void main() {
    // Unpack color (No change)
    v_color = unpackColor(a_color);
    v_color.a = v_color.a * (255.0 / 254.0);

    // Pass through texture coordinates (No change)
    v_texCoords = a_texCoord0;

    // Pass through texture index
    v_texIndex = a_texIndex;

    // Calculate position (No change)
    gl_Position = ubo.projTrans * vec4(a_position.xy, 0.0, 1.0);
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5; // Convert depth range
}