#version 430 core
// The texture times a tint per boid, one directional light and an ambient term: the same arithmetic as the gdx-vulkan version (gpu_lit.frag), so the two pictures
// match. The gdx-vulkan version can add 500 coloured point lights, sorted into clusters on the GPU (key L).
in vec3 v_normal;
in vec2 v_texCoords;
flat in uint v_instance;

uniform sampler2D u_texture;

out vec4 fragColor;

void main() {
    const vec3 toLight = normalize(vec3(0.4, 0.8, 0.3));
    const vec3 light = vec3(0.12) + vec3(0.55, 0.52, 0.48) * max(dot(normalize(v_normal), toLight), 0.0);
    const float i = float(v_instance);
    const vec3 tint = 0.55 + 0.45 * fract(vec3(i * 0.1031, i * 0.11369, i * 0.13787));
    fragColor = vec4(texture(u_texture, v_texCoords).rgb * tint * light, 1.0);
}
