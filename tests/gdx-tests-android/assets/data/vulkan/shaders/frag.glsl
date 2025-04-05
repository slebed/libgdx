#version 450
// Input from vertex shader (location must match vertex shader output)
layout(location = 0) in vec3 fragColor;

// Output fragment color (location 0 corresponds to the framebuffer color attachment)
layout(location = 0) out vec4 outColor;

void main() {
    // Output the interpolated color with full alpha
    outColor = vec4(fragColor, 1.0);
}