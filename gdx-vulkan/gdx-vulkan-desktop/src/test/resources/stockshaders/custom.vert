attribute vec3 a_position;
attribute vec2 a_texCoord0;
uniform mat4 u_worldView;
uniform vec4 u_palette[4];
uniform float u_time;
uniform int u_paletteIndex;
uniform bool u_flip;
varying vec2 v_uv;
varying vec4 v_tint;
void main() {
    vec2 uv = a_texCoord0;
    if (u_flip) uv.y = 1.0 - uv.y;
    v_uv = uv;
    v_tint = u_palette[u_paletteIndex];
    vec3 p = a_position;
    p.y += sin(u_time + p.x) * 0.1;
    gl_Position = u_worldView * vec4(p, 1.0);
}
