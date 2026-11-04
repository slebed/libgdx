#version 450
#extension GL_GOOGLE_include_directive : require
#include "gdx_vk_common.glsl"

// Example VulkanShaderPipelineBundle fragment shader for VulkanImmediateModeRenderer / VulkanShapeRenderer (pair it with shape.vert):
// u_custom0.y blends the shape colour towards its inverse (0 = unchanged, 1 = inverted). custom0.x belongs to the vertex shader.

layout(location = 0) in vec4 v_color;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(mix(v_color.rgb, vec3(1.0) - v_color.rgb, gdx_pc.u_custom0.y), v_color.a);
}
