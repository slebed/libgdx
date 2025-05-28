#version 450
layout(location = 0) in vec2 fragTexCoord;

layout(set = 0, binding = 1) uniform MaterialUBO { // Material UBO now at binding 1
    vec4 u_diffuseColor;
// ... other material fields ...
    float u_hasDiffuseTexture;
    float u_opacity;
// ... other flags and padding
} materialData;

layout(set = 0, binding = 2) uniform sampler2D u_diffuseSampler; // Sampler now at binding 2

layout(location = 0) out vec4 outColor;

void main() {
    vec4 texColor = vec4(1.0);
    if (materialData.u_hasDiffuseTexture > 0.5) {
        texColor = texture(u_diffuseSampler, fragTexCoord);
    }
    outColor = texColor * materialData.u_diffuseColor;
    outColor.a *= materialData.u_opacity;
}