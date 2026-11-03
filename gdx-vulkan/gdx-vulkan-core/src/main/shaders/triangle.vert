#version 450
#extension GL_GOOGLE_include_directive : require
#include "gdx_vk_common.glsl"

// Phase 1a triangle. Vertices come from gl_VertexIndex, written in GL conventions (+Y up, counter-clockwise front
// face). If it renders upright and is not culled, the swapchain viewport and winding conventions are right.
// u_indices.x != 0 swaps two vertices to reverse the winding, which must then be culled.
const vec2 kPos[3] = vec2[](vec2(0.0, 0.5), vec2(-0.5, -0.5), vec2(0.5, -0.5));

void main() {
    int i = gl_VertexIndex;
    if (gdx_pc.u_indices.x != 0 && i > 0) i = 3 - i;
    gl_Position = gdx_vk_clip(gdx_pc.u_transform * vec4(kPos[i], 0.0, 1.0));
}
