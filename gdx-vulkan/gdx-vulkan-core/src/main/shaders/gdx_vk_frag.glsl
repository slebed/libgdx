// Fragment-shader helpers shared by the library's own shaders (include after gdx_vk_common.glsl).
#ifndef GDX_VK_FRAG_GLSL
#define GDX_VK_FRAG_GLSL

// 1 when the pipeline draws to the swapchain, 0 for a render target: set per pipeline by the presenter (specialization constant 1000, outside the ids 0..n above).
// The swapchain pass uses a negative-height viewport, so its framebuffer y runs downwards and turns with the pre-rotation; a render target keeps OpenGL's layout.
layout(constant_id = 1000) const int GDX_SWAPCHAIN = 0;

// Screen-space derivatives along OpenGL's axes (x to the right, y upwards) on every target, as a shader written for OpenGL expects them. The raw dFdy of the swapchain
// has the opposite sign, which turns a tangent frame made from derivatives inside out (normal mapping without tangents shows its relief inverted).
// Framebuffer axes for the pre-rotations 0..3: GL x is (x, y, -x, -y), GL y is (-y, x, y, -x).
#define GDX_DERIVATIVES(T) \
    T gdx_dFdx(T v) { \
        if (GDX_SWAPCHAIN == 0 || GDX_PREROT == 0) return dFdx(v); \
        if (GDX_PREROT == 1) return dFdy(v); \
        if (GDX_PREROT == 2) return -dFdx(v); \
        return -dFdy(v); \
    } \
    T gdx_dFdy(T v) { \
        if (GDX_SWAPCHAIN == 0) return dFdy(v); \
        if (GDX_PREROT == 0) return -dFdy(v); \
        if (GDX_PREROT == 1) return dFdx(v); \
        if (GDX_PREROT == 2) return dFdy(v); \
        return -dFdx(v); \
    }
GDX_DERIVATIVES(float)
GDX_DERIVATIVES(vec2)
GDX_DERIVATIVES(vec3)
GDX_DERIVATIVES(vec4)

#endif
