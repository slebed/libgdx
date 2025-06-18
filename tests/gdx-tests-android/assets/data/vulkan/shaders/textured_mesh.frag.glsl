#version 450

layout(location = 0) in vec2 fragTexCoord; // Received from vertex shader

layout(set = 0, binding = 1) uniform sampler2D texSampler; // Texture sampler at binding 1

layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(texSampler, fragTexCoord);
    // Optional: Multiply by a tint color: outColor = texture(texSampler, fragTexCoord) * vec4(1.0, 0.8, 0.8, 1.0);
}