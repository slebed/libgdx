#version 450
layout(location = 0) in vec3 fragColor;
layout(location = 1) in vec2 fragTexCoord; // <<< ADDED input

// <<< ADDED uniform sampler binding (matches descriptor set layout)
layout(binding = 0) uniform sampler2D texSampler;

layout(location = 0) out vec4 outColor;

void main() {
    // Sample the texture using the UV coords and combine with vertex color (optional)
    outColor = texture(texSampler, fragTexCoord); // Just texture color
    // OR: outColor = texture(texSampler, fragTexCoord) * vec4(fragColor, 1.0); // Modulate with vertex color
}