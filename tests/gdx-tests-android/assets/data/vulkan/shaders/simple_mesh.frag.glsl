#version 450

// Output color for the fragment
layout(location = 0) out vec4 outColor;

void main() {
    // Output a fixed color (e.g., light blue)
    outColor = vec4(0.6, 0.8, 1.0, 1.0);
}
