#version 150

// Les flammes du phénix (voir client/pouvoir/phenix/Feu.java) : chaque sommet porte ses réglages.
// Color = (graine, alpha, or / 2, fondu) pour un ruban ; la couleur et l'alpha pour une lueur ou une pièce pleine.
// UV0 = (travers, long) pour un ruban ; le coin (-1..1) pour une lueur ; le bruit pour l'aura.
// UV2 = (répétition × 64, vitesse × 64 + sorte × 4096).
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vC;
out vec2 vUv;
flat out ivec2 vP;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vC = Color;
    vUv = UV0;
    vP = UV2;
}
