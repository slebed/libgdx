// VulkanGpuInstances vertex shader body, included by gpu_lit_v<HAS_UV>.vert (HAS_UV: the mesh has a texture coordinate attribute).
#ifndef GDX_GPU_LIT_VERTEX
#define GDX_GPU_LIT_VERTEX
#extension GL_GOOGLE_include_directive : require
#define GDX_SKIP_SET0
#include "gdx_vk_common.glsl"
#include "gpu_world.glsl"

// VulkanGpuInstances: draws the instances the cull pass kept (indirect, instanceCount = visible count). Vertex layout: position (0), normal (1),
// texture coordinate (3). Instance i = w[visible + gl_InstanceIndex]: {x, y, z, scale}, {quaternion}.
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec3 a_normal;
#if HAS_UV
layout(location = 3) in vec2 a_texCoord0;
#endif

layout(std430, set = 0, binding = 1) readonly buffer GdxWorld {
    uint w[];
};

layout(location = 0) out vec3 v_world;
layout(location = 1) out vec3 v_normal;
layout(location = 2) out vec2 v_uv;
layout(location = 3) out float v_viewZ;
layout(location = 4) flat out uint v_instance;

float f(uint i) {
    return uintBitsToFloat(w[i]);
}

vec3 rotate(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

void main() {
    const uint instance = w[w[H_OFFSETS + 1u] + uint(gl_InstanceIndex)];
    const uint b = w[H_OFFSETS] + instance * INSTANCE_UINTS;
    const vec4 posScale = vec4(f(b), f(b + 1u), f(b + 2u), f(b + 3u));
    const vec4 quat = vec4(f(b + 4u), f(b + 5u), f(b + 6u), f(b + 7u));
    const vec3 world = rotate(quat, a_position * posScale.w) + posScale.xyz;
    const mat4 viewProj = mat4(vec4(f(H_VIEWPROJ), f(H_VIEWPROJ + 1u), f(H_VIEWPROJ + 2u), f(H_VIEWPROJ + 3u)),
        vec4(f(H_VIEWPROJ + 4u), f(H_VIEWPROJ + 5u), f(H_VIEWPROJ + 6u), f(H_VIEWPROJ + 7u)),
        vec4(f(H_VIEWPROJ + 8u), f(H_VIEWPROJ + 9u), f(H_VIEWPROJ + 10u), f(H_VIEWPROJ + 11u)),
        vec4(f(H_VIEWPROJ + 12u), f(H_VIEWPROJ + 13u), f(H_VIEWPROJ + 14u), f(H_VIEWPROJ + 15u)));
    gl_Position = gdx_vk_clip(viewProj * vec4(world, 1.0));
    v_world = world;
    v_normal = rotate(quat, a_normal);
#if HAS_UV
    v_uv = a_texCoord0;
#else
    v_uv = vec2(0.0);
#endif
    // Linear view-space depth for the cluster lookup: the third row of the view matrix.
    v_viewZ = -(f(H_VIEW + 2u) * world.x + f(H_VIEW + 6u) * world.y + f(H_VIEW + 10u) * world.z + f(H_VIEW + 14u));
    v_instance = instance;
}

#endif
