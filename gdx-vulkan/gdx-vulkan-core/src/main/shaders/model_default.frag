#version 450
#extension GL_GOOGLE_include_directive : require
#ifdef GDX_TIER_A
#extension GL_EXT_nonuniform_qualifier : require
#endif
#include "gdx_vk_common.glsl"
#include "gdx_vk_sets.glsl"
#include "gdx_vk_frag.glsl"

// VulkanModelBatch fragment shader: a port of libGDX's default.fragment.glsl (see model_default_vertex.glsl for the data layout). The material
// and lighting flags the stock shader chose with #define are specialization constants, the same ids as in the vertex shader.
layout(constant_id = 1) const bool C_LIGHTING = false;
layout(constant_id = 2) const bool C_DIFFUSE_TEX = false;
layout(constant_id = 3) const bool C_DIFFUSE_COLOR = false;
layout(constant_id = 4) const bool C_SPECULAR = false;     // specularFlag: a specular colour or texture
layout(constant_id = 5) const bool C_EMISSIVE = false;     // an emissive colour
layout(constant_id = 6) const bool C_BLENDED = false;
layout(constant_id = 7) const bool C_ALPHA_TEST = false;
layout(constant_id = 11) const bool C_COLOR = false;       // the mesh has vertex colours
layout(constant_id = 12) const bool C_FOG = false;
layout(constant_id = 13) const bool C_SPECULAR_TEX = false;
layout(constant_id = 14) const bool C_EMISSIVE_TEX = false;
layout(constant_id = 15) const bool C_SPECULAR_COLOR = false;
layout(constant_id = 16) const bool C_SHADOW = false;
layout(constant_id = 18) const int C_CASCADES = 1;          // cascades of the shadow map (1: the plain map; 2..4: VulkanCascadedShadowLight)
layout(constant_id = 17) const bool C_NORMAL_TEX = false;  // a normal map: lighting is done per pixel (stock's default shader has no normal mapping)
layout(constant_id = 9) const int C_NUM_DIR = 0;
layout(constant_id = 10) const int C_NUM_POINT = 0;
layout(constant_id = 8) const bool C_SHININESS = false;

layout(location = 0) in vec4 v_color;
layout(location = 1) in vec2 v_diffuseUV;
layout(location = 2) in vec3 v_lightDiffuse;
layout(location = 3) in vec3 v_lightSpecular;
layout(location = 4) in vec2 v_specularUV;
layout(location = 5) in vec2 v_emissiveUV;
layout(location = 6) in float v_fog;
layout(location = 7) flat in int v_inst;
layout(location = 8) in vec3 v_shadowUv;
layout(location = 9) in vec3 v_ambientLight;
layout(location = 10) in vec3 v_worldPos;
layout(location = 11) in vec3 v_worldNormal;
layout(location = 12) in vec2 v_normalUV;

layout(location = 0) out vec4 outColor;

const int H_AMBIENT = 5, H_DIR = 11, H_POINT = 19, H_FOGCOLOR = 35, H_SHADOWP = 40;

// The normal from the normal map, in world space, through a tangent frame built from the screen-space derivatives of the position and the
// texture coordinate ("normal mapping without precomputed tangents"); the mesh needs no tangent attribute.
vec3 mappedNormal(vec3 N) {
    const vec3 mapN = texture(GDX_TEXTURE(floatBitsToInt(gdx_pc.u_custom0.x)), v_normalUV).xyz * 2.0 - 1.0;
    const vec3 dp1 = gdx_dFdx(v_worldPos), dp2 = gdx_dFdy(v_worldPos);
    const vec2 duv1 = gdx_dFdx(v_normalUV), duv2 = gdx_dFdy(v_normalUV);
    const vec3 dp2perp = cross(dp2, N), dp1perp = cross(N, dp1);
    const vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
    const vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
    const float invmax = inversesqrt(max(max(dot(T, T), dot(B, B)), 1e-20));
    return normalize(mat3(T * invmax, B * invmax, N) * mapN);
}

// The vertex shader's lighting, per pixel: ambient cubemap, directional and point lights, specular.
void lightPixel(vec3 normal, float shininess, out vec3 ambient, out vec3 diffuseLight, out vec3 specularLight) {
    const vec3 squaredNormal = normal * normal;
    const vec3 isPositive = step(0.0, normal);
    ambient = squaredNormal.x * mix(gdx_data[H_AMBIENT + 0].xyz, gdx_data[H_AMBIENT + 1].xyz, isPositive.x)
            + squaredNormal.y * mix(gdx_data[H_AMBIENT + 2].xyz, gdx_data[H_AMBIENT + 3].xyz, isPositive.y)
            + squaredNormal.z * mix(gdx_data[H_AMBIENT + 4].xyz, gdx_data[H_AMBIENT + 5].xyz, isPositive.z);
    diffuseLight = vec3(0.0);
    specularLight = vec3(0.0);
    const vec3 viewVec = normalize(gdx_data[4].xyz - v_worldPos);
    for (int i = 0; i < C_NUM_DIR; i++) {
        const vec3 lightDir = -gdx_data[H_DIR + i * 2 + 1].xyz;
        const float NdotL = clamp(dot(normal, lightDir), 0.0, 1.0);
        const vec3 value = gdx_data[H_DIR + i * 2].xyz * NdotL;
        diffuseLight += value;
        specularLight += value * pow(max(0.0, dot(normal, normalize(lightDir + viewVec))), shininess);
    }
    for (int i = 0; i < C_NUM_POINT; i++) {
        vec3 lightDir = gdx_data[H_POINT + i * 2 + 1].xyz - v_worldPos;
        const float dist2 = dot(lightDir, lightDir);
        lightDir *= inversesqrt(dist2);
        const float NdotL = clamp(dot(normal, lightDir), 0.0, 1.0);
        const vec3 value = gdx_data[H_POINT + i * 2].xyz * (NdotL / (1.0 + dist2));
        diffuseLight += value;
        specularLight += value * pow(max(0.0, dot(normal, normalize(lightDir + viewVec))), shininess);
    }
}

float getShadowness(vec2 offset) {
    const vec4 bitShifts = vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0);
    const int slot = floatBitsToInt(gdx_data[H_SHADOWP].y);
    return step(v_shadowUv.z, dot(texture(GDX_TEXTURE(slot), v_shadowUv.xy + offset), bitShifts));
}

// Cascaded shadow maps: the cascade is chosen by the fragment's distance along the view direction (perspective projection:
// 1 / gl_FragCoord.w is the clip-space w, which is the view depth). Cascade 0 is the plain shadow map's matrix and slot.
mat4 loadMat4(int at) {
    return mat4(gdx_data[at], gdx_data[at + 1], gdx_data[at + 2], gdx_data[at + 3]);
}

float getCascadedShadow() {
    const float depth = 1.0 / gl_FragCoord.w;
    const vec4 splits = gdx_data[53];
    int c = 0;
    if (depth > splits.x) c = 1;
    if (depth > splits.y) c = 2;
    if (depth > splits.z) c = 3;
    c = min(c, C_CASCADES - 1);
    const mat4 m = c == 0 ? loadMat4(36) : loadMat4(41 + (c - 1) * 4);
    const vec4 sp = m * vec4(v_worldPos, 1.0);
    vec3 uv = (sp.xyz / sp.w) * 0.5 + 0.5;
    uv.z = min(uv.z, 0.998);
    const int slot = c == 0 ? floatBitsToInt(gdx_data[H_SHADOWP].y) : floatBitsToInt(gdx_data[54][c - 1]);
    const float o = gdx_data[H_SHADOWP].x;
    const vec4 bitShifts = vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0);
    float lit = 0.0;
    lit += step(uv.z, dot(texture(GDX_TEXTURE(slot), uv.xy + vec2(o, o)), bitShifts));
    lit += step(uv.z, dot(texture(GDX_TEXTURE(slot), uv.xy + vec2(-o, o)), bitShifts));
    lit += step(uv.z, dot(texture(GDX_TEXTURE(slot), uv.xy + vec2(o, -o)), bitShifts));
    lit += step(uv.z, dot(texture(GDX_TEXTURE(slot), uv.xy + vec2(-o, -o)), bitShifts));
    return lit * 0.25;
}

float getShadow() {
    if (C_CASCADES > 1) return getCascadedShadow();
    const float o = gdx_data[H_SHADOWP].x;
    return (getShadowness(vec2(o, o)) + getShadowness(vec2(-o, o)) + getShadowness(vec2(o, -o)) + getShadowness(vec2(-o, -o))) * 0.25;
}

void main() {
    const int inst = v_inst;
    const vec4 params = gdx_data[inst + 10];

    vec4 diffuse = vec4(1.0);
    if (C_DIFFUSE_TEX) diffuse = texture(GDX_TEXTURE(gdx_pc.u_indices.y), v_diffuseUV);
    if (C_DIFFUSE_COLOR) diffuse *= gdx_data[inst + 7];
    if (C_COLOR) diffuse *= v_color;

    vec4 emissive = vec4(0.0);
    if (C_EMISSIVE_TEX && C_EMISSIVE) emissive = texture(GDX_TEXTURE(gdx_pc.u_indices.w), v_emissiveUV) * gdx_data[inst + 9];
    else if (C_EMISSIVE_TEX) emissive = texture(GDX_TEXTURE(gdx_pc.u_indices.w), v_emissiveUV);
    else if (C_EMISSIVE) emissive = gdx_data[inst + 9];

    vec3 rgb;
    if (C_LIGHTING && C_NORMAL_TEX) {
        vec3 ambientLight, diffuseLight, specularLight;
        lightPixel(mappedNormal(normalize(v_worldNormal)), C_SHININESS ? params.x : 20.0, ambientLight, diffuseLight, specularLight);
        vec3 specular = vec3(0.0);
        if (C_SPECULAR) {
            specular = specularLight;
            if (C_SPECULAR_TEX && C_SPECULAR_COLOR) specular = texture(GDX_TEXTURE(gdx_pc.u_indices.z), v_specularUV).rgb * gdx_data[inst + 8].rgb * specularLight;
            else if (C_SPECULAR_TEX) specular = texture(GDX_TEXTURE(gdx_pc.u_indices.z), v_specularUV).rgb * specularLight;
            else if (C_SPECULAR_COLOR) specular = gdx_data[inst + 8].rgb * specularLight;
        }
        const float shadow = C_SHADOW ? getShadow() : 1.0;
        rgb = diffuse.rgb * (ambientLight + shadow * diffuseLight) + specular + emissive.rgb;
    } else if (!C_LIGHTING) {
        rgb = diffuse.rgb + emissive.rgb;
    } else if (!C_SPECULAR) {
        if (C_SHADOW) rgb = (diffuse.rgb * (v_ambientLight + getShadow() * v_lightDiffuse)) + emissive.rgb;
        else rgb = (diffuse.rgb * v_lightDiffuse) + emissive.rgb;
    } else {
        vec3 specular = v_lightSpecular;
        if (C_SPECULAR_TEX && C_SPECULAR_COLOR) specular = texture(GDX_TEXTURE(gdx_pc.u_indices.z), v_specularUV).rgb * gdx_data[inst + 8].rgb * v_lightSpecular;
        else if (C_SPECULAR_TEX) specular = texture(GDX_TEXTURE(gdx_pc.u_indices.z), v_specularUV).rgb * v_lightSpecular;
        else if (C_SPECULAR_COLOR) specular = gdx_data[inst + 8].rgb * v_lightSpecular;
        if (C_SHADOW) rgb = (diffuse.rgb * (getShadow() * v_lightDiffuse + v_ambientLight)) + specular + emissive.rgb;
        else rgb = (diffuse.rgb * v_lightDiffuse) + specular + emissive.rgb;
    }

    if (C_FOG) rgb = mix(rgb, gdx_data[H_FOGCOLOR].rgb, v_fog);

    float a = 1.0;
    if (C_BLENDED) {
        a = diffuse.a * params.y;
        if (C_ALPHA_TEST && a <= params.z) discard;
    }
    outColor = vec4(rgb, a);
}
