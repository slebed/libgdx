#version 450
#extension GL_GOOGLE_include_directive : require
#include "gdx_vk_common.glsl"

layout(location = 0) out vec4 outColor;

void main() {
    outColor = gdx_pc.u_color;
}
