#version 150

// Le temps arrêté de Minato 3 (l'ulti Mille Flashs) : tout devient gris, sauf le rouge vif (lui, son chakra, ses traits),
// avec les bords de l'écran assombris.
uniform sampler2D DiffuseSampler;

in vec2 texCoord;

out vec4 fragColor;

void main(){
    vec4 c = texture(DiffuseSampler, texCoord);
    float l = dot(c.rgb, vec3(0.3, 0.59, 0.11));
    float rouge = smoothstep(0.2, 0.42, c.r - max(c.g, c.b));
    vec3 o = mix(vec3(l) * vec3(0.9, 0.93, 1.0) * 0.85, c.rgb, rouge);
    float v = 1.0 - 0.5 * smoothstep(0.3, 0.8, length(texCoord - 0.5));
    fragColor = vec4(o * v, 1.0);
}
