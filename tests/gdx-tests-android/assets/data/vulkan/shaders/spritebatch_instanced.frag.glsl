#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#extension GL_EXT_nonuniform_qualifier : enable // Core in Vulkan 1.2

// Inputs from Vertex Shader
layout(location = 0) in vec2 frag_uv;
layout(location = 1) in vec4 frag_color;
layout(location = 2) flat in int frag_texArrayIndex;

// Output Fragment Color
layout(location = 0) out vec4 out_Color;

// Texture Sampler Array (Set 0, Binding 1 - from VulkanTextureBatch)
// The actual size is determined by the descriptor set layout.
layout(set = 0, binding = 1) uniform sampler2D u_textures[];

void main() {
    // Sample the correct texture from the array using the instance-provided index
    // nonuniformEXT is essential for accessing an array with a non-compile-time-constant index.
    vec4 texColor = texture(u_textures[nonuniformEXT(frag_texArrayIndex)], frag_uv);
    out_Color = texColor * frag_color;

    // Optional: Discard fully transparent pixels for potential early-z/stencil benefits
    if (out_Color.a < 0.01) {
         discard;
    }
}