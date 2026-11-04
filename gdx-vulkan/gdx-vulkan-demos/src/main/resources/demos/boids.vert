#version 430 core
// Draws one boid per instance. Both the boids and the list of the ones that passed the culling stay in buffers on the GPU: the vertex shader reads them from
// there, so nothing travels back to the CPU and nothing is uploaded per frame.
layout(std430, binding = 0) readonly buffer Boids { uint b[]; };
layout(std430, binding = 2) readonly buffer Visible {
    uint command[5];
    uint index[];
};

in vec3 a_position;
in vec3 a_normal;
in vec2 a_texCoord0;

uniform mat4 u_projView;

out vec3 v_normal;
out vec2 v_texCoords;

float f(uint i) {
    return uintBitsToFloat(b[i]);
}

vec3 rotate(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

void main() {
    const uint base = index[gl_InstanceID] * 12u;
    const vec3 position = vec3(f(base), f(base + 1u), f(base + 2u));
    const float scale = f(base + 3u);
    const vec4 rotation = vec4(f(base + 4u), f(base + 5u), f(base + 6u), f(base + 7u));
    v_normal = rotate(rotation, a_normal);
    v_texCoords = a_texCoord0;
    gl_Position = u_projView * vec4(position + rotate(rotation, a_position * scale), 1.0);
}
