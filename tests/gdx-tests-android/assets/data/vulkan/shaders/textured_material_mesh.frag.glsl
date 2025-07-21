#version 450

// Inputs from the Vertex Shader
layout(location = 0) in vec3 fragNormal;
layout(location = 1) in vec2 fragTexCoord;

// Per-Object Data
layout(set = 1, binding = 1) uniform MaterialUBO {
    vec4 u_diffuseColor;
// other material properties would go here
    float u_hasDiffuseTexture;
    float u_opacity;
} materialData;

layout(set = 1, binding = 2) uniform sampler2D u_diffuseSampler;

// Output
layout(location = 0) out vec4 outColor;

void main() {
    vec4 texColor = vec4(1.0);
    if (materialData.u_hasDiffuseTexture > 0.5) {
        texColor = texture(u_diffuseSampler, fragTexCoord);
    }

    // Simple lighting placeholder
    vec3 lightDir = normalize(vec3(0.5, 1.0, 0.5));
    float diffuse = max(dot(normalize(fragNormal), lightDir), 0.1); // Added ambient term

    outColor = texColor * materialData.u_diffuseColor * diffuse;
    outColor.a *= materialData.u_opacity;
}