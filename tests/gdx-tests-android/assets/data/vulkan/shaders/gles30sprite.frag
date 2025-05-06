#version 450
#extension GL_EXT_nonuniform_qualifier : enable // Enable if using nonuniformEXT, potentially needed later

// Input from Vertex Shader (matching output locations)
layout(location = 0) in vec4 v_color;
layout(location = 1) in vec2 v_texCoords;
layout(location = 2) in flat float v_texIndex; // NEW: Texture index (use 'flat')

// Output Fragment Color (No change in declaration)
layout(location = 0) out vec4 outColor;

// --- Texture Sampler Array ---
const int MAX_BATCH_TEXTURES = 16; // Or value from config - MUST MATCH LAYOUT
// Binding 1 now holds an array of samplers
layout(binding = 1) uniform sampler2D u_textureArray[MAX_BATCH_TEXTURES];

void main() {
    // Sample the correct texture from the array using the index
    // Note: You might need nonuniformEXT(v_texIndex) if validation/drivers complain,
    //       but start without it for simpler sprite batching.
    outColor = v_color * texture(u_textureArray[int(v_texIndex)], v_texCoords);
    // Alternative if nonuniformEXT is required:
    // outColor = v_color * texture(u_textureArray[nonuniformEXT(v_texIndex)], v_texCoords);
}