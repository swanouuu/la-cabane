#version 150

// Mélange « prémultiplié » (ONE, ONE_MINUS_SRC_ALPHA) : la flamme garde sa couleur là où elles se superposent,
// une lueur (alpha 0) s'ajoute à la lumière.
uniform sampler2D Sampler0;
uniform float Temps;

in vec4 vC;
in vec2 vUv;
flat in ivec2 vP;

out vec4 fragColor;

void main() {
    int sorte = vP.y / 4096;
    float vit = float(vP.y - sorte * 4096) / 64.0;
    float rep = float(vP.x) / 64.0;
    if (sorte == 0) {
        // un ruban de flamme : du bruit qui défile le long, plus fort au milieu, qui s'use vers la pointe
        float g = vC.r, al = vC.g, orr = vC.b * 2.0, fondu = vC.a, h = vUv.y;
        float n = texture(Sampler0, vec2(vUv.x * 0.45 + g, h * rep * 0.5 - Temps * vit * 0.55)).r * 0.62
                + texture(Sampler0, vec2(vUv.x * 0.7 + g * 1.37, h * rep * 0.8 - Temps * vit * 1.05)).g * 0.55;
        float bord = 1.0 - abs(vUv.x * 2.0 - 1.0);
        float i = clamp((n * 1.25 + bord * 0.95 - h * fondu * 1.05 - 0.5) * 1.7, 0.0, 1.0);
        vec3 c = mix(vec3(0.0, 0.07, 0.78), vec3(0.0, 0.48, 1.0), smoothstep(0.2, 0.7, i));
        c = mix(c, vec3(0.55, 0.9, 1.0), smoothstep(0.9, 1.0, i));
        c = mix(c, vec3(1.15, 0.71, 0.12), min(orr, 1.0) * smoothstep(0.38, 0.92, h) * smoothstep(0.05, 0.4, i));
        if (orr > 1.5) {
            c = mix(vec3(1.0, 0.32, 0.04), vec3(1.0, 0.86, 0.35), smoothstep(0.45, 1.0, i));
        }
        float a = clamp(i * al, 0.0, 1.0);
        if (a < 0.004) {
            discard;
        }
        fragColor = vec4(c * a, a * 0.72);
    } else if (sorte == 1) {
        // l'aura : transparente de face, lumineuse sur les bords (l'alpha du sommet), du bleu avec de l'orange qui passe
        float n = texture(Sampler0, vec2(vUv.x, vUv.y - Temps * 0.7)).r;
        float a = clamp(vC.g * (0.35 + n), 0.0, 1.0);
        float o = smoothstep(0.55, 0.8, texture(Sampler0, vec2(vUv.x * 0.6 + 0.5, vUv.y * 0.6 - Temps * 0.45)).g);
        vec3 c = mix(vec3(0.05, 0.55, 1.0), vec3(1.0, 0.45, 0.08), o * 0.75);
        if (a < 0.004) {
            discard;
        }
        fragColor = vec4(c * a, a * 0.45);
    } else if (sorte <= 4) {
        // une lueur : ronde (2), en étoile (3), en anneau (4), qui s'ajoute
        float d = length(vUv);
        float a;
        if (sorte == 2) {
            a = pow(max(0.0, 1.0 - d), 2.2);
        } else if (sorte == 3) {
            a = pow(max(0.0, 1.0 - abs(vUv.x) * 7.0), 2.0) * max(0.0, 1.0 - abs(vUv.y))
              + pow(max(0.0, 1.0 - abs(vUv.y) * 7.0), 2.0) * max(0.0, 1.0 - abs(vUv.x)) + pow(max(0.0, 1.0 - d), 3.0);
        } else {
            a = smoothstep(0.72, 0.88, d) * smoothstep(1.0, 0.9, d) * 1.4;
        }
        a *= vC.a;
        if (a < 0.003) {
            discard;
        }
        fragColor = vec4(vC.rgb * a, 0.0);
    } else {
        // une pièce pleine (griffes, bec), ou de la cendre
        fragColor = vec4(vC.rgb * vC.a, vC.a);
    }
}
