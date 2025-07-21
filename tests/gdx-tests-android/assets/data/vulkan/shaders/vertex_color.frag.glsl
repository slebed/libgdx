#version 450

// Input variable from the vertex shader (will be interpolated)
layout(location = 0) in vec4 fragColor;

// Output variable for the final color of the fragment
layout(location = 0) out vec4 outColor;

void main() {
    // The final color is simply the interpolated color from the vertex data.
    outColor = fragColor;
}
