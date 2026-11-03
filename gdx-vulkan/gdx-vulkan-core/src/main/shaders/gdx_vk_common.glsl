// Shared declarations for every gdx-vulkan shader (plan, Core decision 2 and 6).
// The push-constant block is the one fixed 128-byte layout; the descriptor sets join it in Phase 1b.
#ifndef GDX_VK_COMMON_GLSL
#define GDX_VK_COMMON_GLSL

layout(push_constant) uniform GdxPush {
    mat4 u_transform;  // bytes 0-63
    vec4 u_color;      // 64-79   tint and opacity
    ivec4 u_indices;   // 80-95   material offset, diffuse slot, normal slot, flags
    vec4 u_custom0;    // 96-111  user data
    vec4 u_custom1;    // 112-127
} gdx_pc;

// GL clip depth is [-1, 1], Vulkan's is [0, 1]. Remapped in every vertex shader, no extension branch.
vec4 gdx_vk_clip(vec4 p) {
    p.z = 0.5 * (p.z + p.w);
    return p;
}

#endif
