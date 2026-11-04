#version 430 core
// The texture, one directional light and an ambient term. The gdx-vulkan version of this demo also has 500 coloured point lights, sorted into clusters on the GPU.
in vec3 v_normal;
in vec2 v_texCoords;

uniform sampler2D u_texture;

out vec4 fragColor;

void main() {
    const vec3 toLight = normalize(vec3(0.4, 0.8, 0.3));
    const float light = 0.35 + 0.65 * max(dot(normalize(v_normal), toLight), 0.0);
    fragColor = vec4(texture(u_texture, v_texCoords).rgb * light, 1.0);
}
