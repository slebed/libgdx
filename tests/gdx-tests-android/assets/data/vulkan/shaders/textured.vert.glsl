#version 450
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec3 inColor;
layout(location = 2) in vec2 inTexCoord; // <<< ADDED input

layout(location = 0) out vec3 fragColor;
layout(location = 1) out vec2 fragTexCoord; // <<< ADDED output

void main() {
    gl_Position = vec4(inPosition, 0.0, 1.0);
    fragColor = inColor;        // Pass color (optional, could remove)
    fragTexCoord = inTexCoord; // <<< Pass texture coordinate
}