#ifdef GL_ES
precision mediump float;
#endif
varying vec2 v_uv;
varying vec4 v_tint;
uniform sampler2D u_diffuse;
uniform sampler2D u_mask;
uniform float u_time;
void main() {
#ifdef MASKED
    float m = texture2D(u_mask, v_uv).r;
#else
    float m = 1.0;
#endif
    gl_FragColor = texture2D(u_diffuse, v_uv) * v_tint * m * (0.5 + 0.5 * sin(u_time));
}
