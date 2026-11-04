// The shadow-map depth pass of VulkanModelBatch: a port of libGDX's depth.vertex.glsl (packed depth for DepthShader). Included by
// model_depth_u<UV>[b<B>].vert (HAS_UV: a texture-coordinate attribute for the alpha test; HAS_BONES: bone-weight attributes). Same data layout as
// model_default_vertex.glsl: the header's projViewTrans is the shadow camera's, the records are the renderables'.
#ifndef GDX_MODEL_DEPTH_VERTEX
#define GDX_MODEL_DEPTH_VERTEX

#extension GL_GOOGLE_include_directive : require
#include "gdx_vk_common.glsl"
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#include "gdx_vk_sets.glsl"

layout(location = 0) in vec3 a_position;
#if HAS_UV
layout(location = 3) in vec2 a_texCoord0;
#endif
#ifndef HAS_BONES
#define HAS_BONES 0
#endif
#if HAS_BONES > 0
layout(location = 4) in vec2 a_boneWeight0;
#endif
#if HAS_BONES > 1
layout(location = 5) in vec2 a_boneWeight1;
#endif
#if HAS_BONES > 2
layout(location = 6) in vec2 a_boneWeight2;
#endif
#if HAS_BONES > 3
layout(location = 7) in vec2 a_boneWeight3;
#endif

layout(location = 1) out vec2 v_texCoords0;
layout(location = 6) out float v_depth;
layout(location = 7) flat out int v_inst;

const int RECORD = 15, HEADER = 55; // the record of an instance: HEADER + gl_InstanceIndex * RECORD (see model_default_vertex.glsl)

mat4 loadMat4(int at) {
    return mat4(gdx_data[at], gdx_data[at + 1], gdx_data[at + 2], gdx_data[at + 3]);
}

void main() {
    const int inst = HEADER + gl_InstanceIndex * RECORD;
    v_inst = inst;
    const mat4 world = loadMat4(inst);
#if HAS_UV
    v_texCoords0 = a_texCoord0;
#else
    v_texCoords0 = vec2(0.0);
#endif
#if HAS_BONES > 0
    const int boneBase = int(gdx_data[inst + 10].w);
    mat4 skinning = a_boneWeight0.y * loadMat4(boneBase + 4 * int(a_boneWeight0.x));
#if HAS_BONES > 1
    skinning += a_boneWeight1.y * loadMat4(boneBase + 4 * int(a_boneWeight1.x));
#endif
#if HAS_BONES > 2
    skinning += a_boneWeight2.y * loadMat4(boneBase + 4 * int(a_boneWeight2.x));
#endif
#if HAS_BONES > 3
    skinning += a_boneWeight3.y * loadMat4(boneBase + 4 * int(a_boneWeight3.x));
#endif
    const vec4 pos = world * skinning * vec4(a_position, 1.0);
#else
    const vec4 pos = world * vec4(a_position, 1.0);
#endif
    const vec4 clip = loadMat4(0) * pos;
    v_depth = clip.z / clip.w * 0.5 + 0.5; // GL's normalized depth, as the stock shader writes it
    gl_Position = gdx_vk_clip(clip);
}

#endif
