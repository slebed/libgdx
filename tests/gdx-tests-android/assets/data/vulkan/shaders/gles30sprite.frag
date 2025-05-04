#version 450

// Input from Vertex Shader (matching output locations)
layout(location = 0) in vec4 v_color;
layout(location = 1) in vec2 v_texCoords;

// Output Fragment Color
layout(location = 0) out vec4 outColor;

// Texture Sampler (Matches VulkanSpriteBatch texture binding)
layout(binding = 1) uniform sampler2D u_texture;

void main() {
    // Sample texture and multiply by interpolated vertex color
    outColor = v_color * texture(u_texture, v_texCoords);
    //outColor = vec4(1.0, 0.0, 0.0, 1.0);
}