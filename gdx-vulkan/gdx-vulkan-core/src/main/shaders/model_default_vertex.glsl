// VulkanModelBatch vertex shader body: a port of libGDX's default.vertex.glsl (per-vertex lighting with the ambient cubemap, directional and point
// lights, specular, fog, shadow map coordinates, skinning). Included by model_default_v<N>[b<B>].vert, which defines which vertex inputs the mesh has
// (HAS_NORMAL, HAS_COLOR, HAS_UV; N = the three bits normal=1, colour=2, uv=4; HAS_BONES = B bone-weight attributes) so the inputs always match the
// pipeline's vertex layout. Everything else the stock shader chose with #define flags is a specialization constant here (ids 1..16), set per pipeline.
//
// Data in Set 0's storage binding (gdx_data, vec4 units), written by VulkanModelBatch at each flush:
//   0..3    projViewTrans (columns)
//   4       camera position (xyz), w = fog coefficient
//   5..10   ambient cubemap, 6 faces (rgb)
//   11..18  directional lights, 4 x {colour, direction}
//   19..34  point lights, 8 x {colour * intensity, position}
//   35      fog colour
//   36..39  shadow map projViewTrans (columns)
//   40      {shadow PCF offset, shadow texture slot (int bits), -, -}
//   41..52  cascades 1..3 of a cascaded shadow map: projViewTrans (columns), 4 vec4s each
//   53      {split 0, split 1, split 2 (view-space distances), -}
//   54      {texture slot of cascade 1, 2, 3 (int bits), -}
//   41..    one record of 15 per renderable (instances are consecutive records), selected by gdx_pc.u_indices.x + gl_InstanceIndex * 15:
//           0..3 world, 4..6 normal matrix (columns), 7 diffuse colour, 8 specular colour, 9 emissive colour,
//           10 {shininess, opacity, alphaTest, bone matrices start (vec4 index, 4 per matrix)}, 11 diffuse UV transform {offsetU, offsetV, scaleU, scaleV},
//           12 specular UV transform, 13 emissive UV transform, 14 normal map UV transform
// Texture slots (Tier A): u_indices.y diffuse, .z specular, .w emissive, u_custom0.x (int bits) the normal map; the shadow map's is in the header.
#ifndef GDX_MODEL_DEFAULT_VERTEX
#define GDX_MODEL_DEFAULT_VERTEX

#extension GL_GOOGLE_include_directive : require
#include "gdx_vk_common.glsl"
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#include "gdx_vk_sets.glsl"

layout(constant_id = 1) const bool C_LIGHTING = false;     // lightingFlag: a normal and an environment
layout(constant_id = 2) const bool C_DIFFUSE_TEX = false;
layout(constant_id = 3) const bool C_DIFFUSE_COLOR = false;
layout(constant_id = 4) const bool C_SPECULAR = false;     // specularFlag: a specular colour or texture
layout(constant_id = 5) const bool C_EMISSIVE = false;
layout(constant_id = 6) const bool C_BLENDED = false;
layout(constant_id = 7) const bool C_ALPHA_TEST = false;
layout(constant_id = 8) const bool C_SHININESS = false;    // shininessFlag: the material sets it, else 20
layout(constant_id = 9) const int C_NUM_DIR = 0;
layout(constant_id = 10) const int C_NUM_POINT = 0;
layout(constant_id = 12) const bool C_FOG = false;
layout(constant_id = 16) const bool C_SHADOW = false;      // shadowMapFlag (also separates the ambient light)

layout(location = 0) in vec3 a_position;
#if HAS_NORMAL
layout(location = 1) in vec3 a_normal;
#endif
#if HAS_COLOR
layout(location = 2) in vec4 a_color;
#endif
#if HAS_UV
layout(location = 3) in vec2 a_texCoord0;
#endif
#ifndef HAS_BONES
#define HAS_BONES 0
#endif
// HAS_BONES = how many bone-weight attributes the mesh has (a_boneWeight0.. = {bone index, weight}), at locations 4..
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

layout(location = 0) out vec4 v_color;
layout(location = 1) out vec2 v_diffuseUV;
layout(location = 2) out vec3 v_lightDiffuse;
layout(location = 3) out vec3 v_lightSpecular;
layout(location = 4) out vec2 v_specularUV;
layout(location = 5) out vec2 v_emissiveUV;
layout(location = 6) out float v_fog;
layout(location = 7) flat out int v_inst;
layout(location = 8) out vec3 v_shadowUv;
layout(location = 9) out vec3 v_ambientLight;
layout(location = 10) out vec3 v_worldPos;
layout(location = 11) out vec3 v_worldNormal;
layout(location = 12) out vec2 v_normalUV;

const int H_AMBIENT = 5, H_DIR = 11, H_POINT = 19, H_FOGCOLOR = 35, H_SHADOW = 36, HEADER = 55, RECORD = 15;

mat4 loadMat4(int at) {
    return mat4(gdx_data[at], gdx_data[at + 1], gdx_data[at + 2], gdx_data[at + 3]);
}

void main() {
    const int inst = gdx_pc.u_indices.x + gl_InstanceIndex * RECORD;
    v_inst = inst;
    const mat4 world = loadMat4(inst);
    const vec4 params = gdx_data[inst + 10];

#if HAS_COLOR
    v_color = a_color;
#else
    v_color = vec4(1.0);
#endif
#if HAS_UV
    const vec4 uvT = gdx_data[inst + 11];
    v_diffuseUV = uvT.xy + a_texCoord0 * uvT.zw;
    const vec4 uvS = gdx_data[inst + 12];
    v_specularUV = uvS.xy + a_texCoord0 * uvS.zw;
    const vec4 uvE = gdx_data[inst + 13];
    v_emissiveUV = uvE.xy + a_texCoord0 * uvE.zw;
    const vec4 uvN = gdx_data[inst + 14];
    v_normalUV = uvN.xy + a_texCoord0 * uvN.zw;
#else
    v_diffuseUV = vec2(0.0);
    v_specularUV = vec2(0.0);
    v_emissiveUV = vec2(0.0);
    v_normalUV = vec2(0.0);
#endif

#if HAS_BONES > 0
    const int boneBase = int(params.w);
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
    gl_Position = gdx_vk_clip(loadMat4(0) * pos);
    v_worldPos = pos.xyz;
    v_worldNormal = vec3(0.0, 1.0, 0.0);

    v_shadowUv = vec3(0.0);
    if (C_SHADOW) {
        const vec4 spos = loadMat4(H_SHADOW) * pos;
        v_shadowUv = (spos.xyz / spos.w) * 0.5 + 0.5;
        v_shadowUv.z = min(v_shadowUv.z, 0.998);
    }

    v_fog = 0.0;
    if (C_FOG) {
        const vec3 flen = gdx_data[4].xyz - pos.xyz;
        v_fog = min(dot(flen, flen) * gdx_data[4].w, 1.0);
    }

    v_lightDiffuse = vec3(0.0);
    v_lightSpecular = vec3(0.0);
    v_ambientLight = vec3(0.0);
#if HAS_NORMAL
    if (C_LIGHTING) {
#if HAS_BONES > 0
        const vec3 normal = normalize((world * skinning * vec4(a_normal, 0.0)).xyz);
#else
        const mat3 normalMatrix = mat3(gdx_data[inst + 4].xyz, gdx_data[inst + 5].xyz, gdx_data[inst + 6].xyz);
        const vec3 normal = normalize(normalMatrix * a_normal);
#endif
        v_worldNormal = normal;
        const float shininess = C_SHININESS ? params.x : 20.0;

        // ambientCubemapFlag: the stock config always defines it with lighting
        const vec3 squaredNormal = normal * normal;
        const vec3 isPositive = step(0.0, normal);
        vec3 ambientLight = squaredNormal.x * mix(gdx_data[H_AMBIENT + 0].xyz, gdx_data[H_AMBIENT + 1].xyz, isPositive.x)
                          + squaredNormal.y * mix(gdx_data[H_AMBIENT + 2].xyz, gdx_data[H_AMBIENT + 3].xyz, isPositive.y)
                          + squaredNormal.z * mix(gdx_data[H_AMBIENT + 4].xyz, gdx_data[H_AMBIENT + 5].xyz, isPositive.z);
        if (C_SHADOW) v_ambientLight = ambientLight; // separateAmbientFlag
        else v_lightDiffuse = ambientLight;

        vec3 viewVec = vec3(0.0);
        if (C_SPECULAR) viewVec = normalize(gdx_data[4].xyz - pos.xyz);

        for (int i = 0; i < C_NUM_DIR; i++) {
            const vec3 lightDir = -gdx_data[H_DIR + i * 2 + 1].xyz;
            const float NdotL = clamp(dot(normal, lightDir), 0.0, 1.0);
            const vec3 value = gdx_data[H_DIR + i * 2].xyz * NdotL;
            v_lightDiffuse += value;
            if (C_SPECULAR) {
                const float halfDotView = max(0.0, dot(normal, normalize(lightDir + viewVec)));
                v_lightSpecular += value * pow(halfDotView, shininess);
            }
        }

        for (int i = 0; i < C_NUM_POINT; i++) {
            vec3 lightDir = gdx_data[H_POINT + i * 2 + 1].xyz - pos.xyz;
            const float dist2 = dot(lightDir, lightDir);
            lightDir *= inversesqrt(dist2);
            const float NdotL = clamp(dot(normal, lightDir), 0.0, 1.0);
            const vec3 value = gdx_data[H_POINT + i * 2].xyz * (NdotL / (1.0 + dist2));
            v_lightDiffuse += value;
            if (C_SPECULAR) {
                const float halfDotView = max(0.0, dot(normal, normalize(lightDir + viewVec)));
                v_lightSpecular += value * pow(halfDotView, shininess);
            }
        }
    }
#endif
}

#endif
