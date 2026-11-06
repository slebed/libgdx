#version 450
#extension GL_GOOGLE_include_directive : require
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#define GDX_SKIP_SET0
#include "gdx_vk_common.glsl"
#include "gdx_vk_sets.glsl"
#include "gpu_world.glsl"

// VulkanGpuInstances: texture * a per-instance tint, a constant directional light and ambient term, plus the point lights of the fragment's cluster
// (light_cull.comp). The cluster is found from the pixel (the screen's top row first, as the swapchain is drawn) and the exponential depth slice.
layout(constant_id = 1) const bool C_TEXTURE = false;
layout(constant_id = 2) const bool C_LIGHTS = true; // false when there are no point lights: the cluster lists are not read
layout(constant_id = 3) const bool C_SHADOW = false; // the directional light is shadowed by the maps of VulkanGpuInstances.setShadow (modern path only)
layout(constant_id = 4) const int C_CASCADES = 1;    // cascades of that shadow (1: the plain map)

layout(location = 0) in vec3 v_world;
layout(location = 1) in vec3 v_normal;
layout(location = 2) in vec2 v_uv;
layout(location = 3) in float v_viewZ;
layout(location = 4) flat in uint v_instance;

layout(std430, set = 0, binding = 1) readonly buffer GdxWorld {
    uint w[];
};

layout(location = 0) out vec4 outColor;

float f(uint i) {
    return uintBitsToFloat(w[i]);
}

mat4 loadMat4(uint at) {
    return mat4(vec4(f(at), f(at + 1u), f(at + 2u), f(at + 3u)), vec4(f(at + 4u), f(at + 5u), f(at + 6u), f(at + 7u)),
        vec4(f(at + 8u), f(at + 9u), f(at + 10u), f(at + 11u)), vec4(f(at + 12u), f(at + 13u), f(at + 14u), f(at + 15u)));
}

// The same lookup as model_default.frag's cascaded shadows: the cascade is picked by the view depth (1 / gl_FragCoord.w is the clip-space w), four PCF taps of the packed depth.
float shadowFactor() {
    const float depth = 1.0 / gl_FragCoord.w;
    int c = 0;
    if (depth > f(H_SHADOW_SPLITS)) c = 1;
    if (depth > f(H_SHADOW_SPLITS + 1u)) c = 2;
    if (depth > f(H_SHADOW_SPLITS + 2u)) c = 3;
    c = min(c, C_CASCADES - 1);
    const vec4 sp = loadMat4(H_SHADOW + uint(c) * 16u) * vec4(v_world, 1.0);
    vec3 uv = (sp.xyz / sp.w) * 0.5 + 0.5;
    uv.z = min(uv.z, 0.998);
    const int slot = int(w[H_SHADOW_SLOTS + uint(c)]);
    const float o = f(H_SHADOW_PARAMS);
    const vec4 bitShifts = vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0);
    float lit = 0.0;
    lit += step(uv.z, dot(texture(GDX_TEXTURE(slot), uv.xy + vec2(o, o)), bitShifts));
    lit += step(uv.z, dot(texture(GDX_TEXTURE(slot), uv.xy + vec2(-o, o)), bitShifts));
    lit += step(uv.z, dot(texture(GDX_TEXTURE(slot), uv.xy + vec2(o, -o)), bitShifts));
    lit += step(uv.z, dot(texture(GDX_TEXTURE(slot), uv.xy + vec2(-o, -o)), bitShifts));
    return lit * 0.25;
}

void main() {
    vec3 base = vec3(1.0);
    if (C_TEXTURE) base = texture(GDX_TEXTURE(gdx_pc.u_indices.y), v_uv).rgb;
    // A tint per instance, from a hash of its index.
    const vec3 tint = 0.55 + 0.45 * fract(vec3(float(v_instance) * 0.1031, float(v_instance) * 0.11369, float(v_instance) * 0.13787));
    const vec3 albedo = base * tint;

    const vec3 n = normalize(v_normal);
    const float shadow = C_SHADOW ? shadowFactor() : 1.0;
    vec3 light = vec3(0.12) + vec3(0.55, 0.52, 0.48) * max(dot(n, normalize(vec3(0.4, 0.8, 0.3))), 0.0) * shadow;

    if (!C_LIGHTS) {
        outColor = vec4(albedo * light, 1.0);
        return;
    }
    const uint cx = w[H_CLUSTER], cy = w[H_CLUSTER + 1u], cz = w[H_CLUSTER + 2u], maxPer = w[H_CLUSTER + 3u];
    const float near = f(H_CAMERA + 3u), far = f(H_PROJ);
    const uint tx = min(uint(gl_FragCoord.x / float(w[H_COUNTS + 2u]) * float(cx)), cx - 1u);
    const uint ty = min(uint(gl_FragCoord.y / float(w[H_COUNTS + 3u]) * float(cy)), cy - 1u);
    const uint tz = clamp(uint(max(log(max(v_viewZ, near) / near) / log(far / near) * float(cz), 0.0)), 0u, cz - 1u);
    const uint list = w[H_OFFSETS2] + (tx + cx * (ty + cy * tz)) * (1u + maxPer);
    const uint count = w[list];
    const uint lights = w[H_OFFSETS + 3u];
    for (uint i = 0u; i < count; i++) {
        const uint b = lights + w[list + 1u + i] * 8u;
        const vec4 pr = vec4(f(b), f(b + 1u), f(b + 2u), f(b + 3u));
        const vec4 cl = vec4(f(b + 4u), f(b + 5u), f(b + 6u), f(b + 7u));
        vec3 toLight = pr.xyz - v_world;
        const float dist = length(toLight);
        toLight /= max(dist, 1e-4);
        const float x = clamp(1.0 - (dist * dist) / (pr.w * pr.w), 0.0, 1.0);
        light += cl.rgb * cl.w * x * x * max(dot(n, toLight), 0.0);
    }
    outColor = vec4(albedo * light, 1.0);
}
