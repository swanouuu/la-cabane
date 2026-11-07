// Les skins des missions (de vrais skins 64×64, deux couches) : les agents de sécurité de Glamtek et les employées, les
// mannequins robots et le vigile du Méga Mall, les sbires en combinaison orange de l'île ; les ninjas de la Brume et les
// villageois, les déserteurs de la forêt et leur otage, les samouraïs et les archers du château. Et les héros : les
// espionnes en combinaison (la tête du joueur sur la tenue du mod), les ANBU au masque blanc.
// node skins.mjs → ../skins/*.png
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, hache, lire } from "../../spies/generateur/moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));

// [u, v, largeur, hauteur, profondeur] de chaque partie, première couche puis seconde
const PARTIES = {
  tete: [[0, 0], [32, 0], 8, 8, 8], corps: [[16, 16], [16, 32], 8, 12, 4],
  brasD: [[40, 16], [40, 32], 4, 12, 4], brasG: [[32, 48], [48, 48], 4, 12, 4],
  jambeD: [[0, 16], [0, 32], 4, 12, 4], jambeG: [[16, 48], [0, 48], 4, 12, 4],
};
const ton = (c, k) => c.map((v) => Math.max(0, Math.min(255, Math.round(v * k))));
const grain = (c, x, y, s, f = 0.06) => ton(c, 1 - f + 2 * f * hache(x, y, s));

/**
 * Un skin : peindre(partie, couche, f) appelle f(face, x, y, L, H) pour chaque pixel de chaque face (x de gauche à
 * droite quand on regarde la face, y de haut en bas) ; f rend une couleur ou null (rien / transparent).
 */
function skin() {
  const px = Buffer.alloc(64 * 64 * 4);
  const s = {
    px,
    peindre(nom, couche, f) {
      const [uv1, uv2, w, h, d] = PARTIES[nom], [u, v] = couche === 2 ? uv2 : uv1;
      const faces = { haut: [u + d, v, w, d], bas: [u + d + w, v, w, d], droite: [u, v + d, d, h], devant: [u + d, v + d, w, h], gauche: [u + d + w, v + d, d, h], dos: [u + d + w + d, v + d, w, h] };
      for (const [face, [x0, y0, L, H]] of Object.entries(faces)) for (let y = 0; y < H; y++) for (let x = 0; x < L; x++) {
        const c = f(face, x, y, L, H);
        if (c) px.set([...c.map(Math.round), 255], ((y0 + y) * 64 + x0 + x) * 4);
      }
      return s;
    },
  };
  return s;
}

// ------------------------------------------------------------------ la tête
/**
 * Une tête : peau, cheveux (style : "plaque", "court", "rase", "long"), sourcils, yeux, bouche, barbe ("moustache",
 * "grosse", "bouc", "3jours"), lunettes ("aviateur", "noires", "tir").
 */
function tete(s, o) {
  const P = o.peau, C = o.cheveux, ombre = ton(P, 0.86), joue = ton(P, 0.93);
  const yeux = o.yeux ?? [70, 46, 30];
  s.peindre("tete", 1, (face, x, y) => {
    const g = (c) => grain(c, x + face.length * 8, y, 3, 0.05);
    if (face === "haut") return g(o.chauve ? P : C);
    if (face === "bas") return ombre;
    const style = o.style ?? "court";
    if (face === "devant") {
      // les cheveux sur le front
      if (style === "long" && (y < 2 || (y < 8 && (x === 0 || x === 7)))) return g(C);
      if ((style === "plaque" || style === "court") && (y === 0 || (y === 1 && (style === "plaque" || x < 2 || x > 5)))) return g(C);
      if (style === "rase" && y === 0) return g(ton(C, 1.1));
      if (y === 3 && (x === 1 || x === 2 || x === 5 || x === 6)) return o.sourcils ?? ton(C, 0.9);
      if (y === 4) {
        if (x === 1 || x === 6) return [236, 236, 236];
        if (x === 2 || x === 5) return yeux;
      }
      if (y === 5 && (x === 3 || x === 4)) return ton(P, 0.8);
      if (y === 6 && x >= 2 && x <= 5) return o.bouche ? o.bouche(x) : ton(P, 0.68);
      if (y === 7) return x >= 2 && x <= 5 ? ombre : joue;
      return y > 4 && (x === 0 || x === 7) ? joue : g(P);
    }
    // les côtés et le dos
    const dos = face === "dos";
    if (style === "long") return g(C);
    if (style === "rase") return y < 2 ? g(ton(C, 1.1)) : (y === 4 || y === 5) && !dos && (x === 3 || x === 4) ? ton(P, 0.78) : g(P);
    const limite = dos ? 5 : style === "plaque" ? 3 : 2;
    if (y <= limite) return g(C);
    if (!dos && y <= limite + 2 && ((face === "droite" && x >= 6) || (face === "gauche" && x <= 1))) return g(C); // les pattes
    if (!dos && (y === 4 || y === 5) && (x === 3 || x === 4)) return ton(P, 0.78); // l'oreille
    return g(dos ? ton(P, 0.95) : P);
  });
  // la barbe et les lunettes, dans la première couche (par-dessus la peau)
  const barbe = o.barbe, B = o.couleurBarbe ?? C;
  if (barbe || o.lunettes) s.peindre("tete", 1, (face, x, y) => {
    if (face !== "devant") {
      if (barbe === "3jours" && face !== "dos" && face !== "haut" && face !== "bas" && y >= 5) return grain(ton(o.peau, 0.72), x, y, 9, 0.12);
      if (o.lunettes && face !== "dos" && face !== "haut" && face !== "bas" && y === 4 && ((face === "droite" && x >= 4) || (face === "gauche" && x <= 3)))
        return o.lunettes === "tir" ? [40, 40, 40] : o.lunettes === "aviateur" ? [214, 172, 60] : [20, 20, 22];
      return null;
    }
    if (o.lunettes === "aviateur") {
      if (y === 4) return x === 1 ? [118, 70, 34] : x === 2 ? [78, 42, 20] : x === 5 ? [118, 70, 34] : x === 6 ? [78, 42, 20] : [222, 180, 64];
      if (y === 5 && (x === 1 || x === 2 || x === 5 || x === 6)) return [96, 56, 28];
    }
    if (o.lunettes === "noires" && (y === 4 || (y === 5 && x !== 3 && x !== 4))) return x === 1 && y === 4 ? [90, 92, 104] : x === 3 || x === 4 ? [44, 44, 48] : [16, 16, 18];
    if (o.lunettes === "tir" && y === 4) return x === 2 || x === 5 ? [150, 108, 26] : [244, 204, 58];
    if (barbe === "moustache" && y === 6 && x >= 1 && x <= 6) return grain(B, x, y, 5);
    if (barbe === "grosse" && ((y === 6 && x >= 1 && x <= 6) || (y === 5 && x >= 2 && x <= 5 && x !== 3 && x !== 4) || (y === 7 && (x === 1 || x === 6)))) return grain(B, x, y, 5);
    if (barbe === "bouc" && ((y === 6 && (x === 1 || x === 6)) || (y === 7 && x >= 2 && x <= 5))) return grain(B, x, y, 5);
    if (barbe === "3jours" && y >= 5 && !(y === 6 && x >= 2 && x <= 5) && !(y === 5 && x >= 3 && x <= 4)) return grain(ton(o.peau, 0.74), x, y, 9, 0.12);
    return null;
  });
}

/** Les bras : manche(face, x, y) rend la couleur de la manche, ou null pour la peau ; les mains (2 rangs du bas). */
function bras(s, o) {
  for (const nom of ["brasD", "brasG"]) s.peindre(nom, 1, (face, x, y) => {
    const m = o.manche ? o.manche(face, x, y, nom) : null;
    if (m) return m;
    if (face === "bas") return ton(o.peau, 0.85);
    const muscle = face === "devant" || face === "dos" ? 1 + 0.06 * Math.sin(y * 0.9) : 0.95;
    return grain(ton(o.peau, muscle * (y >= 10 ? 0.97 : 1)), x + (nom === "brasG" ? 9 : 0), y, 7, 0.04);
  });
}
const entre = (v, a, b) => v >= a && v <= b;
const OR = [232, 184, 56], OR_CLAIR = [255, 226, 120], OR_FONCE = [176, 126, 34];
/** Une chaîne en or qui pend en V sur le devant du torse (x, y sur la face avant), avec son médaillon. */
const chaine = (x, y, couleur = OR, medaillon = true) => {
  if ((y === 0 && (x === 2 || x === 5)) || (y === 1 && (x === 2 || x === 5)) || (y === 2 && (x === 3 || x === 4))) return y === 0 ? ton(couleur, 1.1) : couleur;
  if (medaillon && y === 3 && (x === 3 || x === 4)) return x === 3 ? ton(couleur, 1.15) : ton(couleur, 0.85);
  return null;
};


const jambes = (s, f) => { for (const nom of ["jambeD", "jambeG"]) s.peindre(nom, 1, (face, x, y) => f(face, x, y, nom)); };
const MANNEQUIN = [236, 230, 220];

// ------------------------------------------------------------------ les gens des missions
export const GENS = {
  // ================================================================ WOOHP
  agent: {
    nom: "Agent Glamtek", role: "La securite de la tour. Costume, oreillette.", serie: "whoop",
    dessin(s) {
      const P = [206, 162, 124], NOIR = [32, 32, 38], BLANC = [236, 236, 240], ROSE = [240, 84, 164];
      tete(s, { peau: P, cheveux: [30, 24, 20], style: "rase", lunettes: "noires" });
      s.peindre("tete", 2, (face, x, y) => (face === "droite" && x === 5 && (y === 4 || y === 5) ? [214, 214, 220] : face === "droite" && x === 5 && y === 6 ? [170, 170, 176] : null));
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return NOIR;
        if (face === "devant") {
          if (entre(x, 3, 4) && y <= 7) return y === 0 ? BLANC : y === 1 ? [26, 26, 30] : grain([20, 20, 26], x, y, 3, 0.04);
          if ((x === 2 || x === 5) && y <= 4) return y === 0 ? BLANC : ton(NOIR, 0.8);
          if (x === 6 && y === 2) return ROSE;
          if (entre(x, 3, 4) && y === 8) return [60, 60, 66];
        }
        return grain(NOIR, x, y, 4, 0.04);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y >= 10 ? null : y === 9 ? BLANC : grain(NOIR, x, y, 5, 0.04)) });
      jambes(s, (face, x, y) => (y >= 10 || face === "bas" ? (y === 10 ? [50, 50, 56] : [18, 18, 20]) : grain([28, 28, 34], x, y, 6, 0.04)));
    },
  },
  employe: {
    nom: "Employee", role: "Elle se balade. Si elle te voit, elle crie.", serie: "whoop",
    dessin(s) {
      const P = [236, 196, 164], ROSE = [232, 120, 172], BLANC = [244, 240, 236], GRIS = [96, 96, 108];
      tete(s, { peau: P, cheveux: [120, 72, 40], style: "long", yeux: [60, 110, 70] });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return ROSE;
        if (y >= 9) return grain(GRIS, x, y, 7, 0.04);
        if (face === "devant") {
          if (entre(x, 3, 4) && y <= 6) return BLANC;
          if ((x === 2 && y === 2) || (x === 5 && y === 2) || (x === 3 && y === 4)) return [60, 90, 200];
          if (entre(x, 3, 4) && y === 5) return [250, 250, 250];
        }
        return grain(ROSE, x, y, 8, 0.04);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y < 9 ? grain(ROSE, x, y, 9, 0.04) : null) });
      jambes(s, (face, x, y) => (y >= 11 || face === "bas" ? [26, 22, 24] : y <= 3 ? grain(GRIS, x, y, 10, 0.04) : grain(ton(P, 0.92), x, y, 11, 0.03)));
    },
  },
  mannequin: {
    nom: "Mannequin robot", role: "Il ne bouge que quand personne ne le regarde.", serie: "whoop",
    dessin(s) {
      const B = MANNEQUIN, J = [168, 160, 150], ROUGE = [255, 40, 40];
      s.peindre("tete", 1, (face, x, y) => {
        if (face === "devant" && y === 4 && entre(x, 1, 6)) return x === 1 || x === 6 ? [150, 20, 20] : ROUGE;
        if (y === 7 && face !== "haut") return J;
        return grain(face === "haut" ? ton(B, 1.04) : B, x, y, 12, 0.02);
      });
      s.peindre("corps", 1, (face, x, y) => (y === 8 && face !== "haut" && face !== "bas" ? J : grain(B, x, y, 13, 0.02)));
      for (const nom of ["brasD", "brasG"]) s.peindre(nom, 1, (face, x, y) => (y === 0 || y === 6 || y === 10 ? J : grain(B, x, y, 14, 0.02)));
      jambes(s, (face, x, y) => (y === 6 || y === 0 ? J : grain(B, x, y, 15, 0.02)));
    },
  },
  vigile: {
    nom: "Le vigile", role: "Il fait sa ronde la nuit avec sa lampe.", serie: "whoop",
    dessin(s) {
      const P = [150, 104, 74], BLEU = [40, 52, 92], JAUNE = [240, 200, 60];
      tete(s, { peau: P, cheveux: [30, 24, 20], style: "rase", barbe: "moustache", couleurBarbe: [30, 24, 20] });
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut") return grain(BLEU, x, y, 16, 0.04);
        if (face === "bas") return null;
        if (y <= 1) return face === "devant" && y === 1 && entre(x, 3, 4) ? JAUNE : grain(BLEU, x, y, 17, 0.04);
        if (y === 2 && face === "devant") return [18, 18, 22];
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return BLEU;
        if (y === 9) return face === "devant" && x === 6 ? [30, 30, 30] : [20, 20, 22];
        if (face === "devant" && x === 6 && entre(y, 2, 3)) return JAUNE;
        if (face === "devant" && entre(x, 3, 4) && y % 3 === 1) return ton(BLEU, 1.4);
        if (face === "devant" && entre(x, 1, 2) && y === 3) return [200, 200, 210];
        return grain(BLEU, x, y, 18, 0.05);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y < 4 ? grain(BLEU, x, y, 19, 0.05) : y === 1 ? JAUNE : null) });
      jambes(s, (face, x, y) => (y >= 10 || face === "bas" ? [22, 20, 20] : grain([44, 44, 52], x, y, 20, 0.04)));
    },
  },
  sbire: {
    nom: "Sbire", role: "Les hommes de main de l'ile.", serie: "whoop",
    dessin(s) {
      const P = [222, 180, 140], OR = [226, 112, 34], NOIR = [28, 28, 32];
      tete(s, { peau: P, cheveux: [60, 40, 30], style: "court", barbe: "3jours" });
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut") return grain(NOIR, x, y, 21, 0.04);
        if (face === "bas") return null;
        if (y <= 2) return grain(NOIR, x, y, 22, 0.04);
        if (y === 3 || y === 4) return face === "devant" ? (x === 3 || x === 4 ? NOIR : y === 3 ? [70, 200, 220] : [40, 120, 140]) : face !== "dos" ? NOIR : null;
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return OR;
        if (y === 9) return face === "devant" && entre(x, 3, 4) ? [200, 200, 200] : NOIR;
        if (face === "devant" && Math.hypot(x - 3.5, y - 3.5) < 2.2) return Math.hypot(x - 3.5, y - 3.5) < 0.9 ? [255, 255, 255] : NOIR;
        if (face === "devant" && x === 3 && y > 5) return ton(OR, 0.8);
        return grain(OR, x, y, 23, 0.05);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y >= 9 ? NOIR : grain(OR, x, y, 24, 0.05)) });
      jambes(s, (face, x, y) => (y >= 9 || face === "bas" ? grain(NOIR, x, y, 25, 0.04) : grain(OR, x, y, 26, 0.05)));
    },
  },
  // ================================================================ ANBU
  ninjaBrume: {
    nom: "Ninja de la Brume", role: "Les gardes du village, lanterne en main.", serie: "anbu",
    dessin(s) {
      const P = [226, 198, 172], NAVY = [36, 40, 62], GILET = [104, 118, 112], ACIER = [176, 180, 190], BANDE = [210, 212, 214];
      tete(s, { peau: P, cheveux: [44, 46, 60], style: "court" });
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut" || face === "bas") return null;
        if (y === 1 || y === 2) {
          if (face === "devant" && entre(x, 1, 6)) return (x === 3 || x === 4) && y === 1 ? [70, 74, 86] : (x === 2 || x === 5) && y === 2 ? [70, 74, 86] : ACIER;
          return [30, 34, 56];
        }
        if (y >= 5 && face !== "dos") return (x + y) % 3 === 0 ? ton(BANDE, 0.86) : BANDE;
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => grain(NAVY, x, y, 27, 0.05));
      s.peindre("corps", 2, (face, x, y) => {
        if (face === "haut" || face === "bas" || y > 9) return null;
        if (face === "devant" && entre(x, 3, 4)) return y === 0 ? GILET : null;
        if (face === "devant" && (y === 5 || y === 6) && (entre(x, 0, 1) || entre(x, 6, 7))) return ton(GILET, 0.8);
        return grain(y === 0 ? ton(GILET, 1.15) : GILET, x, y, 28, 0.06);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y >= 10 ? null : y >= 6 ? ((x + y) % 2 ? [60, 62, 70] : [80, 82, 92]) : grain(NAVY, x, y, 29, 0.05)) });
      jambes(s, (face, x, y) => {
        if (y === 11 || face === "bas") return [40, 32, 28];
        if (y === 10) return P;
        if (y >= 7) return y % 2 ? BANDE : ton(BANDE, 0.85);
        return (x + y * 2) % 5 < 2 ? [150, 160, 172] : [92, 102, 118];
      });
    },
  },
  villageois: {
    nom: "Villageois", role: "S'il te voit, il appelle les gardes.", serie: "anbu",
    dessin(s) {
      const P = [222, 184, 150], INDIGO = [58, 64, 112], OBI = [140, 92, 50];
      tete(s, { peau: P, cheveux: [150, 150, 150], style: "court", barbe: "bouc", couleurBarbe: [190, 190, 190] });
      s.peindre("tete", 2, (face, x, y) => (y === 1 && face !== "haut" && face !== "bas" ? [236, 232, 220] : null));
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return INDIGO;
        if (y === 7 || y === 8) return grain(OBI, x, y, 30, 0.05);
        if (face === "devant" && ((x === 2 && y <= 6 - 0) || (x === 3 && y >= 3 && y <= 6))) return ton(INDIGO, 0.7);
        if (face === "devant" && x < 2 && y < 3) return P;
        return (x * 2 + y) % 7 === 0 ? [200, 206, 230] : grain(INDIGO, x, y, 31, 0.05);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y < 9 ? grain(INDIGO, x, y, 32, 0.05) : null) });
      jambes(s, (face, x, y) => (y >= 11 || face === "bas" ? [130, 96, 60] : grain([66, 62, 64], x, y, 33, 0.05)));
    },
  },
  deserteur: {
    nom: "Deserteur", role: "Ils guettent dans les arbres.", serie: "anbu",
    dessin(s) {
      const P = [204, 160, 124], VIOLET = [58, 42, 70], ROUGE = [170, 30, 34], ACIER = [150, 154, 162];
      tete(s, { peau: P, cheveux: [110, 40, 30], style: "long", barbe: "3jours", yeux: [140, 40, 40] });
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut" || face === "bas") return null;
        if (y === 1 || y === 2) {
          if (face === "devant" && entre(x, 1, 6)) return y === 1 && x === 7 - y * 2 ? [40, 40, 44] : (x + y) % 4 === 0 ? [40, 40, 44] : ACIER;
          return [24, 24, 30];
        }
        if (y >= 6 && face !== "dos") return grain(ROUGE, x, y, 34, 0.08);
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => (hache(x, y, 35 + face.length) < 0.08 ? [30, 22, 36] : grain(VIOLET, x, y, 36, 0.06)));
      s.peindre("corps", 2, (face, x, y) => (y <= 1 && face !== "bas" ? grain(ROUGE, x, y, 37, 0.08) : face === "devant" && y === 2 && entre(x, 1, 3) ? ROUGE : null));
      bras(s, { peau: P, manche: (face, x, y) => (y < 4 ? grain(VIOLET, x, y, 38, 0.06) : y < 10 ? (y % 2 ? [230, 226, 214] : [206, 200, 186]) : null) });
      jambes(s, (face, x, y) => (y >= 10 || face === "bas" ? [36, 30, 26] : hache(x, y, 39) < 0.1 ? [20, 18, 20] : grain([34, 30, 40], x, y, 40, 0.05)));
    },
  },
  otage: {
    nom: "L'otage", role: "Le fils du seigneur. Libere, il te suit.", serie: "anbu",
    dessin(s) {
      const P = [240, 212, 184], ROUGE = [190, 40, 46], OR = [232, 190, 70];
      tete(s, { peau: P, cheveux: [24, 20, 22], style: "plaque", yeux: [50, 40, 30] });
      s.peindre("tete", 2, (face, x, y) => (face === "haut" && entre(x, 3, 4) && entre(y, 3, 4) ? [24, 20, 22] : null));
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return ROUGE;
        if (y === 7 || y === 8) return OR;
        if (face === "devant" && ((x === 2 && y < 7) || (x === 3 && y >= 3 && y < 7))) return ton(OR, 0.9);
        return (x + y) % 5 === 0 ? ton(OR, 0.95) : grain(ROUGE, x, y, 41, 0.05);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y < 9 ? ((x + y) % 5 === 0 ? OR : grain(ROUGE, x, y, 42, 0.05)) : null) });
      jambes(s, (face, x, y) => (y >= 10 || face === "bas" ? [244, 244, 240] : grain([220, 210, 190], x, y, 43, 0.04)));
    },
  },
  samourai: {
    nom: "Samourai", role: "Les gardes du chateau, lance a la main.", serie: "anbu",
    dessin(s) {
      const P = [214, 172, 132], ROUGE = [150, 30, 30], NOIR = [26, 24, 26], OR = [228, 186, 64];
      tete(s, { peau: P, cheveux: [20, 18, 18], style: "court" });
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut") return grain(ton(ROUGE, 0.8), x, y, 44, 0.05);
        if (face === "bas") return null;
        if (y <= 2) {
          if (face === "devant" && y === 0 && (x === 1 || x === 6)) return OR;
          if (face === "devant" && y === 1 && entre(x, 2, 5)) return OR;
          return y === 2 ? NOIR : grain(ton(ROUGE, 0.8), x, y, 45, 0.05);
        }
        if (face !== "devant" && face !== "dos" && y <= 6) return y % 2 ? ROUGE : ton(ROUGE, 0.7);
        if (face === "devant" && y >= 5) return y === 5 && entre(x, 1, 6) ? [230, 230, 230] : grain(ton(ROUGE, 0.75), x, y, 46, 0.05);
        return null;
      });
      const plaques = (x, y, k = 0) => (y % 3 === 2 ? ton(ROUGE, 0.62) : (x + k) % 3 === 1 && y % 3 === 0 ? OR : grain(ROUGE, x, y, 47, 0.05));
      s.peindre("corps", 1, (face, x, y) => (face === "haut" || face === "bas" ? ROUGE : y >= 9 ? grain(NOIR, x, y, 48, 0.05) : plaques(x, y)));
      bras(s, { peau: P, manche: (face, x, y) => (y < 5 ? plaques(x, y, 1) : y < 10 ? grain(NOIR, x, y, 49, 0.05) : null) });
      jambes(s, (face, x, y) => (y >= 10 || face === "bas" ? [40, 32, 26] : y >= 6 && face === "devant" ? plaques(x, y) : grain(NOIR, x, y, 50, 0.05)));
    },
  },
  archer: {
    nom: "Archer", role: "Sur les remparts. Il tire de loin.", serie: "anbu",
    dessin(s) {
      const P = [210, 168, 130], INDIGO = [50, 58, 100], CUIR = [120, 80, 44], BLANC = [238, 234, 224];
      tete(s, { peau: P, cheveux: [24, 20, 20], style: "court", barbe: "moustache", couleurBarbe: [24, 20, 20] });
      s.peindre("tete", 2, (face, x, y) => (face !== "haut" && face !== "bas" && y === 1 ? BLANC : face === "dos" && y >= 2 && y <= 4 && (x === 3 || x === 4) ? BLANC : null));
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return INDIGO;
        if (face === "dos" && entre(x, 4, 6)) return y <= 1 ? (x % 2 ? BLANC : [200, 60, 50]) : grain(CUIR, x, y, 51, 0.06);
        if (face === "devant" && y - x === 1) return CUIR;
        if (y === 9) return [30, 26, 24];
        return y % 3 === 2 ? ton(INDIGO, 0.75) : grain(INDIGO, x, y, 52, 0.05);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y < 4 ? grain(INDIGO, x, y, 53, 0.05) : y < 10 && y > 6 ? CUIR : null) });
      jambes(s, (face, x, y) => (y >= 10 || face === "bas" ? [40, 32, 26] : grain([80, 80, 88], x, y, 54, 0.05)));
    },
  },
};

// ------------------------------------------------------------------ les boss (les méchants que l'hôte et son pote jouent : /skin whoop1…anbu3)
export const BOSS = {
  whoop1: {
    nom: "La Patronne", role: "La patronne de Glamtek : tailleur rose, perles, rouge à lèvres.",
    dessin(s) {
      const P = [240, 204, 176], BLOND = [236, 206, 120], ROSE = [222, 58, 140], ROSE_C = [246, 150, 196], BLANC = [248, 244, 240], PERLE = [250, 248, 252];
      tete(s, { peau: P, cheveux: BLOND, style: "long", yeux: [70, 120, 170], sourcils: [170, 130, 70], bouche: (x) => [200, 30, 70] });
      // le chignon et les boucles d'oreilles
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut" && entre(x, 2, 5) && entre(y, 5, 7)) return ton(BLOND, 0.9);
        if (face === "dos" && entre(x, 2, 5) && entre(y, 0, 2)) return ton(BLOND, 0.92);
        if ((face === "droite" && x === 4 && y === 6) || (face === "gauche" && x === 3 && y === 6)) return PERLE;
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return ROSE;
        if (face === "devant") {
          if (y === 0 && entre(x, 2, 5)) return x === 2 || x === 5 ? PERLE : ton(P, 0.95);
          if (y === 1 && entre(x, 3, 4)) return PERLE;
          if (entre(x, 3, 4) && entre(y, 2, 4)) return BLANC;
          if ((x === 2 || x === 5) && entre(y, 1, 5)) return ROSE_C;
          if (x === 3 && (y === 6 || y === 8)) return [255, 214, 90];
          if (y === 7) return [60, 20, 40];
        }
        if (y === 7) return [60, 20, 40];
        return grain(ROSE, x, y, 61, 0.05);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y < 9 ? grain(ROSE, x, y, 62, 0.05) : y === 9 ? ROSE_C : face === "devant" && y === 11 && x === 1 ? [200, 30, 70] : null) });
      jambes(s, (face, x, y) => (y >= 11 || face === "bas" ? ROSE : y <= 4 ? grain(ROSE, x, y, 63, 0.05) : grain(ton(P, 0.93), x, y, 64, 0.03)));
    },
  },
  whoop2: {
    nom: "Le Styliste", role: "Le styliste du Méga Mall : manteau violet, mètre ruban, barbichette.",
    dessin(s) {
      const P = [226, 186, 150], VIOLET = [104, 46, 150], VIOLET_F = [64, 26, 96], NOIR = [24, 22, 28], OR = [236, 190, 70], JAUNE = [250, 214, 60];
      tete(s, { peau: P, cheveux: [146, 70, 200], style: "plaque", barbe: "bouc", couleurBarbe: [110, 50, 160], yeux: [40, 40, 50], sourcils: [110, 50, 160] });
      s.peindre("tete", 2, (face, x, y) => (face === "devant" && y === 4 && (x === 5 || x === 6) ? [30, 30, 34] : face === "devant" && y === 4 && x === 4 ? [200, 200, 210] : null));
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return VIOLET;
        if (face === "devant") {
          if (entre(x, 2, 5) && y <= 9) {
            if ((x === 2 || x === 5) && y <= 6) return JAUNE; // le mètre ruban autour du cou
            if ((x === 2 || x === 5) && y === 7) return ton(JAUNE, 0.8);
            return grain(NOIR, x, y, 71, 0.04);
          }
          if (x === 1 && y % 3 === 1) return OR;
        }
        return y >= 10 ? grain(VIOLET_F, x, y, 72, 0.05) : grain(VIOLET, x, y, 73, 0.05);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y < 9 ? grain(VIOLET, x, y, 74, 0.05) : y === 9 ? OR : null) });
      jambes(s, (face, x, y) => (y >= 11 || face === "bas" ? [140, 20, 40] : grain(NOIR, x, y, 75, 0.04)));
    },
  },
  whoop3: {
    nom: "Baron Magma", role: "Le maître du volcan : chauve, cache-œil, combinaison noire à rayures de lave.",
    dessin(s) {
      const P = [214, 170, 136], NOIR = [30, 28, 30], LAVE = [255, 110, 20], LAVE_C = [255, 200, 60], ACIER = [130, 130, 140];
      tete(s, { peau: P, cheveux: P, chauve: true, style: "rase", barbe: "3jours", yeux: [140, 30, 20], sourcils: [60, 40, 30] });
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "devant" && entre(x, 1, 3) && y === 4) return NOIR; // le cache-œil
        if (face === "devant" && ((x === 0 && y === 3) || (x === 4 && y === 2) || (x === 5 && y === 1) || (x === 6 && y === 0))) return NOIR;
        if (face === "droite" && y === 3) return NOIR;
        if (face === "gauche" && y === 3 && x >= 4) return NOIR;
        if (face === "devant" && x === 6 && entre(y, 5, 6)) return [170, 110, 90]; // la cicatrice
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return NOIR;
        if (face === "devant" && entre(x, 3, 4) && entre(y, 2, 4)) return y === 3 ? LAVE_C : LAVE; // l'emblème
        if (y === 8) return x % 2 ? ACIER : [70, 70, 76];
        if ((face === "devant" || face === "dos") && (x === 0 || x === 7)) return LAVE;
        return grain(NOIR, x, y, 81, 0.05);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y >= 9 ? [50, 50, 54] : face === "devant" && x === 1 ? LAVE : grain(NOIR, x, y, 82, 0.05)) });
      jambes(s, (face, x, y) => (y >= 10 || face === "bas" ? [60, 60, 66] : (face === "devant" || face === "dos") && x === 0 ? LAVE : grain(NOIR, x, y, 83, 0.05)));
    },
  },
  anbu1: {
    nom: "Le Mizukage", role: "Le chef du village de la Brume : grande robe blanche et bleue, chapeau de kage.",
    dessin(s) {
      const P = [232, 200, 172], BLEU = [52, 92, 170], BLEU_C = [120, 170, 230], BLANC = [242, 242, 246], GRIS = [180, 186, 196];
      tete(s, { peau: P, cheveux: [40, 54, 90], style: "long", yeux: [70, 110, 160] });
      // le chapeau de kage : un large bord blanc et la plaque bleue « eau »
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut") return (x + y) % 3 ? BLANC : GRIS;
        if (face === "bas") return null;
        if (y === 0) return face === "devant" && entre(x, 3, 4) ? BLEU : BLANC;
        if (y === 1) return face === "devant" ? (entre(x, 2, 5) ? BLEU_C : BLANC) : GRIS;
        if (face === "devant" && y === 7 && entre(x, 1, 6)) return null;
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return BLANC;
        if (face === "devant" && (y - x === 1 || x + y === 6) && y <= 6) return BLEU;
        if (y === 6) return BLEU;
        if (face === "dos" && entre(x, 2, 5) && entre(y, 1, 4)) return BLEU_C;
        return grain(BLANC, x, y, 91, 0.03);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y < 10 ? (y === 9 ? BLEU : grain(BLANC, x, y, 92, 0.03)) : null) });
      jambes(s, (face, x, y) => (y >= 11 || face === "bas" ? [60, 50, 40] : y === 0 ? BLEU : grain(BLANC, x, y, 93, 0.03)));
    },
  },
  anbu2: {
    nom: "Le Déserteur", role: "Le chef des déserteurs : bandeau barré, gilet vert, bras bandés.",
    dessin(s) {
      const P = [204, 160, 120], VERT = [70, 84, 56], VERT_F = [46, 56, 38], NOIR = [28, 28, 32], BANDE = [226, 218, 196], METAL = [176, 180, 190];
      tete(s, { peau: P, cheveux: [26, 26, 30], style: "court", barbe: "3jours", yeux: [120, 40, 30] });
      // le bandeau frontal rayé d'un trait (le ninja renégat)
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut" && (x + y) % 2 === 0) return [20, 20, 24];
        if (face === "devant" && y === 2) return entre(x, 2, 5) ? (x - 2 === 3 - (x - 2) || x === 3 ? [70, 70, 76] : METAL) : NOIR;
        if (face === "devant" && y === 1 && entre(x, 2, 5)) return METAL;
        if ((face === "droite" || face === "gauche" || face === "dos") && y === 2) return NOIR;
        if (face === "dos" && entre(x, 3, 4) && entre(y, 3, 5)) return NOIR;
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return VERT;
        if (face === "devant") {
          if (entre(x, 3, 4)) return grain(NOIR, x, y, 101, 0.04);
          if (entre(y, 2, 4) && (x === 1 || x === 6)) return VERT_F;
        }
        if (y === 9) return [60, 40, 26];
        return grain(VERT, x, y, 102, 0.06);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y < 4 ? grain(NOIR, x, y, 103, 0.04) : y % 2 === 0 && y < 11 ? BANDE : y < 11 ? ton(BANDE, 0.88) : null) });
      jambes(s, (face, x, y) => (y >= 11 || face === "bas" ? [36, 30, 26] : y >= 8 ? BANDE : grain(NOIR, x, y, 104, 0.05)));
    },
  },
  anbu3: {
    nom: "Le Seigneur", role: "Le seigneur du château : chignon, kimono rouge et or, hakama noir.",
    dessin(s) {
      const P = [226, 192, 158], ROUGE = [168, 30, 36], OR = [232, 184, 56], NOIR = [26, 22, 24], BLANC = [240, 236, 228];
      tete(s, { peau: P, cheveux: [18, 16, 18], style: "plaque", barbe: "moustache", couleurBarbe: [18, 16, 18], yeux: [40, 30, 24] });
      s.peindre("tete", 2, (face, x, y) => (face === "haut" && entre(x, 3, 4) && entre(y, 1, 6) ? [10, 10, 12] : face === "dos" && entre(x, 3, 4) && y === 0 ? [10, 10, 12] : null));
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return ROUGE;
        if (face === "devant" && (y - x === 1 || x + y === 6) && y <= 6) return BLANC;
        if (y === 7) return face === "devant" && entre(x, 3, 4) ? OR : NOIR;
        if (y >= 8) return grain(NOIR, x, y, 111, 0.04);
        if ((x + y) % 4 === 0) return OR;
        return grain(ROUGE, x, y, 112, 0.05);
      });
      bras(s, { peau: P, manche: (face, x, y) => (y < 10 ? ((x + y) % 4 === 0 ? OR : grain(ROUGE, x, y, 113, 0.05)) : null) });
      jambes(s, (face, x, y) => (y >= 11 || face === "bas" ? BLANC : grain(NOIR, x, y, 114, 0.04)));
    },
  },
};

/** Les gardes et les gens seulement (sans les héros, qui prennent la tête des joueurs). */
export function skinsGens() {
  return Object.fromEntries(Object.entries(GENS).map(([id, g]) => { const s = skin(); g.dessin(s); return [id, { w: 64, h: 64, px: s.px }]; }));
}

export function skinsBoss() {
  return Object.fromEntries(Object.entries(BOSS).map(([id, b]) => { const s = skin(); b.dessin(s); return [id, { w: 64, h: 64, px: s.px }]; }));
}

// ------------------------------------------------------------------ les héros
const JOUEURS = path.join(ICI, "..", "..", "spies", "generateur", "skins");
const TENUES = path.join(ICI, "..", "..", "spies", "textures");

/** Une espionne : la tête du joueur (les deux couches) sur la combinaison du mod. */
function espionne(tenue, joueur) {
  const t = lire(path.join(TENUES, "tenue_" + tenue + ".png")), j = lire(path.join(JOUEURS, joueur + ".png"));
  const px = Buffer.from(t.px);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 64; x++) { const o = (y * 64 + x) * 4; j.px.copy(px, o, o, o + 4); }
  return { w: 64, h: 64, px };
}

/** Un ANBU : le skin du joueur, et par-dessus le masque blanc du chasseur (avec ses traits rouges). */
function anbu(joueur) {
  const j = lire(path.join(JOUEURS, joueur + ".png")), px = Buffer.from(j.px);
  const B = [240, 238, 232], R = [200, 30, 30];
  for (let y = 0; y < 8; y++) for (let x = 0; x < 8; x++) {
    let c = grain(B, x, y, 60, 0.02);
    if (y === 4 && (x === 1 || x === 2 || x === 5 || x === 6)) c = x === 1 || x === 6 ? [30, 30, 30] : [14, 14, 14];
    else if ((x === 1 || x === 6) && (y === 2 || y === 3)) c = R;
    else if ((x === 2 || x === 5) && y === 6) c = R;
    else if (y === 7 && (x === 0 || x === 7)) continue;
    px.set([...c, 255], ((8 + y) * 64 + 40 + x) * 4);
  }
  return { w: 64, h: 64, px };
}

export function skins() {
  const r = Object.fromEntries(Object.entries(GENS).map(([id, g]) => { const s = skin(); g.dessin(s); return [id, { w: 64, h: 64, px: s.px }]; }));
  r.espionneVerte = espionne("verte", "efe");
  r.espionneRouge = espionne("rouge", "kai");
  r.espionneJaune = espionne("jaune", "steve");
  r.anbu1 = anbu("efe");
  r.anbu2 = anbu("kai");
  r.anbu3 = anbu("steve");
  r.toi = lire(path.join(JOUEURS, "steve.png"));
  return r;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const DOSSIER = path.join(ICI, "..", "skins");
  fs.mkdirSync(DOSSIER, { recursive: true });
  for (const [id, sk] of Object.entries(skins())) ecrirePng(path.join(DOSSIER, id + ".png"), 64, 64, sk.px);
  console.log("skins dessinés dans", DOSSIER);
}
