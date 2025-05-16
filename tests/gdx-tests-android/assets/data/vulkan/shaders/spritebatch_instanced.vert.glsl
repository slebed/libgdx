#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

// Binding 0: Base Quad Vertex Data (per-vertex)
layout(location = 0) in vec2 in_localPos;               // Unit quad vertex position (e.g., 0,0 to 1,1)
layout(location = 1) in vec2 in_localUV;                // Unit quad UV (e.g., 0,0 to 1,1)

// Binding 1: Instance Data (per-instance)
layout(location = 2) in vec2 instance_worldPos;         // World position of the sprite's anchor
layout(location = 3) in vec2 instance_size;             // width, height
layout(location = 4) in vec2 instance_origin;           // Normalized origin (0,0 to 1,1) relative to size, or actual pixel offset
layout(location = 5) in vec2 instance_scale;
layout(location = 6) in float instance_rotation;        // Degrees
layout(location = 7) in vec4 instance_uvRegion;         // u, v, u2, v2 for texture mapping
layout(location = 8) in float instance_packedColor;     // Packed ABGR
layout(location = 9) in float instance_texArrayIndex;   // Integer index passed as float

// Uniforms (Set 0, Binding 0 - from VulkanTextureBatch)
layout(set = 0, binding = 0) uniform UniformBufferObject {
    mat4 projectionMatrix;
} ubo;

// Outputs to Fragment Shader
layout(location = 0) out vec2 frag_uv;
layout(location = 1) out vec4 frag_color;
layout(location = 2) flat out int frag_texArrayIndex;   // 'flat' for integer varyings

// Function to unpack the ABGR float color
vec4 unpackColor(float packedColor) {
    uint intBits = floatBitsToUint(packedColor);
    float a = float((intBits >> 24) & 0xFFu) / 255.0;
    float b = float((intBits >> 16) & 0xFFu) / 255.0;
    float g = float((intBits >> 8)  & 0xFFu) / 255.0;
    float r = float( intBits        & 0xFFu) / 255.0;
    return vec4(r, g, b, a);
}

void main() {

    vec2 pos = (in_localPos * instance_size) - instance_origin;

    pos *= instance_scale;

    float radRotation = radians(instance_rotation);
    float cosRot = cos(radRotation);
    float sinRot = sin(radRotation);
    mat2 rotationMatrix = mat2(cosRot, -sinRot, sinRot, cosRot); // Standard 2D rotation
    pos = rotationMatrix * pos;
    pos += instance_origin + instance_worldPos;

    gl_Position = ubo.projectionMatrix * vec4(pos, 0.0, 1.0);
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5; // Convert depth range

    frag_uv.x = mix(instance_uvRegion.x, instance_uvRegion.z, in_localUV.x); // u to u2
    frag_uv.y = mix(instance_uvRegion.y, instance_uvRegion.w, in_localUV.y); // v to v2

    frag_color = unpackColor(instance_packedColor);

    frag_texArrayIndex = int(instance_texArrayIndex);
}