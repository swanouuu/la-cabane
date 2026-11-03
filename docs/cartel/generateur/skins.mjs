// Les skins des gars du cartel (de vrais skins 64×64, deux couches) : le patron qui vend les armes, le sicario au
// masque tête de mort, le narco à chemise à fleurs, le carioca au maillot du Brésil, le garde à la cartouchière, la
// reine du cartel et l'instructeur du stand de tir.
// node skins.mjs → ../skins/*.png
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, hache } from "../../spies/generateur/moteur.mjs";

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

// ------------------------------------------------------------------ les gars
export const GARS = {
  patron: {
    nom: "El Patron", role: "Le vendeur d'armes, dans sa hacienda",
    dessin(s) {
      const P = [196, 140, 100], BLANC = [238, 234, 222], ROUGE = [168, 28, 36];
      tete(s, { peau: P, cheveux: [26, 20, 18], style: "plaque", barbe: "grosse", couleurBarbe: [22, 16, 14], lunettes: "aviateur" });
      // le chapeau de cowboy blanc, son ruban noir et sa plume
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut") return grain(entre(x, 2, 5) ? ton(BLANC, 0.92) : BLANC, x, y, 21, 0.03);
        if (face === "bas") return null;
        if (y < 2) return grain(BLANC, x, y, 22, 0.03);
        if (y === 2) return face === "gauche" && x === 2 ? [190, 40, 40] : [34, 28, 24];
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return BLANC;
        if (y >= 10) return grain(BLANC, x, y, 23, 0.03);
        if (y === 9) return face === "devant" && entre(x, 3, 4) ? OR : [66, 40, 26];
        if (face === "devant") {
          const c = chaine(x, y);
          if (c && entre(x, 2, 5) && y < 4) return c;
          if (entre(x, 3, 4)) return y < 2 ? (y === 0 ? ton(P, 0.9) : P) : grain(ROUGE, x, y, 24, 0.04);
          if ((x === 2 || x === 5) && y < 7) return ton(BLANC, 0.86); // les revers
          if (x === 6 && y === 3) return ROUGE; // la pochette
          return grain(BLANC, x, y, 25, 0.03);
        }
        return grain(face === "dos" && x === 4 ? ton(BLANC, 0.93) : BLANC, x, y, 26, 0.03);
      });
      bras(s, { peau: P, manche: (face, x, y, nom) => {
        if (y >= 10) {
          if (nom === "brasD" && y === 11 && face === "devant" && x === 1) return OR; // la chevalière
          return null;
        }
        if (y === 9) return nom === "brasG" ? (face === "devant" || face === "gauche" ? OR_CLAIR : OR) : ROUGE;
        return grain(face === "haut" ? BLANC : y === 0 ? ton(BLANC, 1.02) : BLANC, x, y, 27, 0.03);
      } });
      for (const nom of ["jambeD", "jambeG"]) s.peindre(nom, 1, (face, x, y) => {
        if (y >= 10 || face === "bas") return y === 10 && face === "devant" ? [140, 52, 36] : grain([108, 38, 28], x, y, 28, 0.08);
        return grain(face === "devant" && x === (nom === "jambeD" ? 2 : 1) ? ton(BLANC, 0.9) : BLANC, x, y, 29, 0.03);
      });
    },
  },
  sicario: {
    nom: "Sicario", role: "Le tueur au masque tete de mort",
    dessin(s) {
      const P = [150, 100, 70], NOIR = [34, 34, 40], OLIVE = [88, 94, 58], OLIVE_F = [64, 70, 42], KAKI = [126, 112, 78];
      tete(s, { peau: P, cheveux: [24, 20, 18], style: "court", yeux: [52, 34, 24], sourcils: [20, 16, 14] });
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut") return grain([26, 26, 30], x, y, 31, 0.04);
        if (face === "bas") return null;
        if (y < 2) return face === "devant" && y === 1 && entre(x, 3, 4) ? [230, 226, 214] : grain([26, 26, 30], x, y, 32, 0.04);
        if (y === 2 && face === "devant") return [16, 16, 18];
        // le bandana tête de mort : la mâchoire, le nez creux, les dents
        if (y >= 5) {
          if (face !== "devant") return face === "dos" && y === 5 && entre(x, 3, 4) ? [20, 20, 22] : grain([24, 24, 28], x, y, 33, 0.05);
          const W = [232, 228, 216], K = [20, 20, 22];
          if (y === 5) return x === 0 || x === 7 || entre(x, 3, 4) ? K : W;
          if (y === 6) return x === 0 || x === 7 ? K : x % 2 === 0 ? ton(W, 0.8) : W;
          return x === 0 || x === 7 ? K : x % 2 === 1 ? W : K;
        }
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => {
        if (y >= 10) return y === 10 ? [30, 26, 22] : grain(KAKI, x, y, 34, 0.05);
        if (face === "haut" || face === "bas") return NOIR;
        return grain(NOIR, x, y, 35, 0.05);
      });
      // le gilet pare-balles et ses poches à chargeurs
      s.peindre("corps", 2, (face, x, y) => {
        if (face === "haut" || face === "bas" || y > 9) return null;
        if (face === "devant" || face === "dos") {
          if (y === 0) return entre(x, 1, 2) || entre(x, 5, 6) ? OLIVE : null;
          if (face === "devant" && y >= 6 && y <= 8 && (entre(x, 1, 2) || entre(x, 5, 6))) return y === 6 ? ton(OLIVE_F, 1.25) : grain(OLIVE_F, x, y, 36, 0.06);
          if (face === "devant" && y === 3 && x === 6) return [30, 30, 30]; // la radio
          return grain(y === 9 ? OLIVE_F : OLIVE, x, y, 37, 0.06);
        }
        return y >= 2 ? grain(OLIVE_F, x, y, 38, 0.06) : null;
      });
      bras(s, { peau: P, manche: (face, x, y) => (y >= 10 ? (face === "devant" && y === 10 ? [44, 44, 46] : [22, 22, 24]) : grain(NOIR, x, y, 39, 0.05)) });
      for (const nom of ["jambeD", "jambeG"]) s.peindre(nom, 1, (face, x, y) => {
        if (y >= 9 || face === "bas") return y === 9 ? [40, 34, 28] : grain([28, 24, 22], x, y, 40, 0.06);
        const poche = ((nom === "jambeD" && face === "droite") || (nom === "jambeG" && face === "gauche")) && entre(y, 3, 6) && entre(x, 1, 2);
        return grain(poche ? ton(KAKI, y === 3 ? 1.1 : 0.85) : y === 5 && face === "devant" ? ton(KAKI, 0.8) : KAKI, x, y, 41, 0.06);
      });
    },
  },
  narco: {
    nom: "El Flaco", role: "Le narco a la chemise a fleurs",
    dessin(s) {
      const P = [214, 164, 120], ROUGE = [200, 42, 46], JEAN = [58, 84, 142];
      const fleur = (x, y, k) => {
        const h = hache(Math.floor(x / 3), Math.floor(y / 3), k);
        if (h < 0.42 && x % 3 === 1 && y % 3 === 1) return [250, 212, 60];
        if (h < 0.42 && ((x % 3 === 1) !== (y % 3 === 1))) return [244, 236, 222];
        if (h > 0.85 && (x + y) % 2 === 0) return [40, 120, 60];
        return null;
      };
      tete(s, { peau: P, cheveux: [52, 36, 26], style: "rase", barbe: "bouc", couleurBarbe: [50, 34, 24], yeux: [92, 60, 34] });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut") return ROUGE;
        if (face === "bas") return JEAN;
        if (y >= 10) return grain(JEAN, x, y, 42, 0.06);
        if (y === 9) return face === "devant" && entre(x, 3, 4) ? (x === 3 ? OR_CLAIR : OR) : [92, 56, 30];
        if (face === "devant") {
          const c = chaine(x, y);
          if (c && y < 4) return c;
          if (entre(x, 3, 4)) return y === 0 ? ton(P, 0.9) : y === 1 ? P : grain([238, 236, 228], x, y, 43, 0.03);
          if ((x === 2 || x === 5) && y < 3) return ton(ROUGE, 0.75);
        }
        return fleur(x + (face === "dos" ? 20 : face.length * 5), y, 44) ?? grain(ROUGE, x, y, 45, 0.05);
      });
      bras(s, { peau: P, manche: (face, x, y, nom) => {
        if (y < 4) return y === 3 ? ton(ROUGE, 0.8) : fleur(x + nom.length * 3 + face.length, y, 46) ?? grain(ROUGE, x, y, 47, 0.05);
        // un tatouage sur l'avant-bras droit, un bracelet en or à gauche
        if (nom === "brasD" && (face === "droite" || face === "devant") && entre(y, 5, 8) && ((x + y) % 3 === 0 || (y === 6 && x > 0))) return [52, 62, 82];
        if (nom === "brasG" && y === 9) return OR;
        return null;
      } });
      for (const nom of ["jambeD", "jambeG"]) s.peindre(nom, 1, (face, x, y) => {
        if (y >= 8 || face === "bas") return y === 9 ? [176, 120, 64] : grain([124, 72, 36], x, y, 48, 0.06);
        return grain(entre(y, 2, 4) && face === "devant" ? ton(JEAN, 1.18) : JEAN, x, y, 49, 0.07);
      });
    },
  },
  carioca: {
    nom: "Carioca", role: "Le gars de la favela, maillot du Bresil",
    dessin(s) {
      const P = [104, 66, 44], JAUNE = [250, 212, 40], VERT = [22, 140, 68], BLEU = [34, 72, 170];
      tete(s, { peau: P, cheveux: [18, 14, 12], style: "court", yeux: [40, 26, 18], bouche: (x) => (x === 3 || x === 4 ? [240, 238, 230] : [60, 30, 24]) });
      // la casquette à l'envers
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut") return grain([28, 28, 32], x, y, 50, 0.04);
        if (face === "bas" || y > 2) return null;
        if (face === "devant") return y === 2 ? null : y === 1 && entre(x, 2, 5) ? [200, 200, 204] : [28, 28, 32];
        if (face === "dos" && y === 2) return [20, 20, 22];
        return y < 2 ? [28, 28, 32] : null;
      });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut") return JAUNE;
        if (face === "bas") return BLEU;
        if (y >= 10) return face === "devant" && y === 10 && entre(x, 3, 4) ? [240, 240, 240] : grain(BLEU, x, y, 51, 0.05);
        if (face === "devant") {
          const c = chaine(x, y, [214, 218, 226]);
          if (c && y < 4) return c;
          if ((y === 0 && entre(x, 2, 5)) || (y === 1 && entre(x, 3, 4))) return VERT;
          if (y === 3 && x === 6) return [40, 90, 190]; // l'écusson
          if (y === 4 && x === 6) return VERT;
        }
        if (face === "dos") {
          // le 10 dans le dos
          const chiffres = ["010111", "111101", "010101", "010101", "010101", "111111"];
          const d = chiffres[y - 2];
          if (d && entre(x, 1, 6) && d[x - 1] === "1") return VERT;
          if (y === 0 && entre(x, 2, 5)) return VERT;
        }
        if ((face === "gauche" || face === "droite") && entre(x, 1, 2)) return VERT;
        return grain(JAUNE, x, y, 52, 0.04);
      });
      bras(s, { peau: P, manche: (face, x, y, nom) => (y < 3 ? grain(JAUNE, x, y, 53, 0.04) : y === 3 ? VERT : nom === "brasD" && y === 9 ? [214, 218, 226] : null) });
      for (const nom of ["jambeD", "jambeG"]) s.peindre(nom, 1, (face, x, y) => {
        if (face === "bas") return [30, 110, 60];
        if (y < 6) return ((nom === "jambeD" && face === "droite") || (nom === "jambeG" && face === "gauche")) && entre(x, 1, 2) ? [240, 240, 240] : grain(BLEU, x, y, 54, 0.05);
        if (y === 11) return [30, 110, 60]; // les tongs
        if (y === 10 && face === "devant" && entre(x, 1, 2)) return JAUNE;
        return grain(P, x, y, 55, 0.04);
      });
    },
  },
  garde: {
    nom: "Le garde", role: "Bandana, lunettes noires, cartouchiere",
    dessin(s) {
      const P = [180, 124, 88], NOIR = [30, 30, 34], ROUGE = [184, 32, 32];
      const camo = (x, y, k) => {
        const h = hache(Math.floor((x + 1) / 2), Math.floor(y / 2), k), h2 = hache(Math.floor(x / 2), Math.floor((y + 1) / 2), k + 1);
        return h < 0.25 ? [62, 80, 46] : h2 < 0.2 ? [110, 86, 56] : h > 0.88 ? [34, 34, 30] : [104, 112, 74];
      };
      tete(s, { peau: P, cheveux: [30, 22, 18], style: "court", barbe: "3jours", lunettes: "noires" });
      s.peindre("tete", 2, (face, x, y) => {
        const motif = (x + y * 3) % 5 === 0 ? [236, 226, 210] : ROUGE;
        if (face === "haut") return grain(motif, x, y, 56, 0.04);
        if (face === "bas") return null;
        if (y < 2) return grain(motif, x, y, 57, 0.04);
        if (face === "dos" && y < 5 && entre(x, 3, 4)) return ton(ROUGE, 0.85); // le nœud et ses pans
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut") return NOIR;
        if (face === "bas" || y >= 10) return y === 10 ? (face === "devant" && entre(x, 3, 4) ? [200, 204, 210] : [24, 24, 24]) : camo(x, y, 58);
        if ((face === "devant" || face === "dos") && y < 3 && (x < 2 || x > 5)) return grain(P, x, y, 59, 0.04); // le débardeur
        return grain(ton(NOIR, face === "devant" && entre(y, 2, 4) && x !== 3 && x !== 4 ? 1.25 : 1), x, y, 60, 0.05);
      });
      // la cartouchière en travers du torse
      s.peindre("corps", 2, (face, x, y) => {
        if (face !== "devant" && face !== "dos") return null;
        const d = face === "devant" ? x + y : (7 - x) + y;
        if (y > 9) return null;
        if (d === 6 || d === 7) return (x + y) % 2 === 0 ? OR : [104, 66, 36];
        return null;
      });
      bras(s, { peau: P, manche: (face, x, y) => {
        if (y >= 10) return y === 10 ? [24, 24, 24] : null; // les mitaines
        if ((face === "devant" || face === "droite" || face === "gauche") && entre(y, 2, 6) && hache(x, y, 61) < 0.35) return [52, 60, 70]; // les tatouages
        return null;
      } });
      for (const nom of ["jambeD", "jambeG"]) s.peindre(nom, 1, (face, x, y) => (y >= 10 || face === "bas" ? grain([26, 24, 22], x, y, 62, 0.06) : camo(x + nom.length * 2 + face.length, y, 63)));
    },
  },
  reina: {
    nom: "La Reina", role: "La reine du cartel",
    dessin(s) {
      const P = [222, 176, 140], CH = [26, 20, 22], CUIR = [30, 28, 32], LEO = [206, 154, 82];
      tete(s, { peau: P, cheveux: CH, style: "long", yeux: [56, 34, 22], sourcils: [16, 12, 12], bouche: (x) => (x === 3 || x === 4 ? [196, 26, 48] : ton(P, 0.9)) });
      s.peindre("tete", 2, (face, x, y) => {
        // les lunettes relevées sur la tête, les créoles en or
        if (face === "devant" && y === 1) return entre(x, 1, 2) || entre(x, 5, 6) ? [40, 30, 26] : OR;
        if ((face === "droite" && x === 4) || (face === "gauche" && x === 3)) return y === 6 ? OR : null;
        return null;
      });
      const leopard = (x, y) => (hache(Math.floor(x / 2), Math.floor(y / 2), 64) < 0.3 && (x + y) % 2 === 0 ? [50, 34, 20] : grain(LEO, x, y, 65, 0.06));
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return CUIR;
        if (y >= 9) return grain([36, 34, 40], x, y, 66, 0.04);
        if (face === "devant") {
          const c = chaine(x, y, OR, false);
          if (c && y < 4) return c;
          if (entre(x, 2, 5) && y < 2) return P;
          if (entre(x, 3, 4)) return leopard(x, y);
          if (x === 2 || x === 5) return y < 8 ? leopard(x + 7, y) : CUIR;
        }
        const reflet = (face === "devant" || face === "dos") && (x === 1 || x === 6) ? 1.6 : 1;
        return grain(ton(CUIR, reflet), x, y, 67, 0.05);
      });
      // les cheveux longs qui tombent dans le dos
      s.peindre("corps", 2, (face, x, y) => (face === "dos" && y < 5 - (x === 0 || x === 7 ? 1 : 0) ? grain(CH, x, y, 68, 0.05) : null));
      bras(s, { peau: P, manche: (face, x, y) => {
        if (y === 11) return face === "devant" && (x === 1 || x === 2) ? [196, 26, 48] : null; // le vernis
        if (y === 10) return OR;
        return grain(ton(CUIR, (face === "devant" || face === "droite") && x === 1 ? 1.6 : 1), x, y, 69, 0.05);
      } });
      for (const nom of ["jambeD", "jambeG"]) s.peindre(nom, 1, (face, x, y) => {
        if (y >= 10 || face === "bas") return y === 11 && face === "dos" && entre(x, 1, 2) ? OR : grain([180, 22, 40], x, y, 70, 0.05);
        return grain([36, 34, 40], x, y, 71, 0.04);
      });
    },
  },
  instructeur: {
    nom: "L'instructeur", role: "Le chef du stand de tir",
    dessin(s) {
      const P = [176, 120, 84], NOIR = [32, 32, 36], ORANGE = [240, 120, 30], SABLE = [190, 170, 120];
      const camo = (x, y) => { const h = hache(Math.floor(x / 2), Math.floor(y / 2), 72); return h < 0.3 ? [146, 116, 74] : h > 0.8 ? [222, 204, 156] : SABLE; };
      tete(s, { peau: P, cheveux: [120, 110, 100], style: "rase", barbe: "moustache", couleurBarbe: [110, 100, 92], lunettes: "tir" });
      // le casque antibruit vert
      s.peindre("tete", 2, (face, x, y) => {
        if (face === "haut") return entre(y, 3, 4) ? [24, 24, 26] : null;
        if ((face === "droite" || face === "gauche") && (y < 3 ? entre(x, 3, 4) : entre(y, 3, 6) && entre(x, 2, 5))) return y < 3 ? [24, 24, 26] : grain([62, 112, 62], x, y, 73, 0.05);
        return null;
      });
      s.peindre("corps", 1, (face, x, y) => {
        if (face === "haut" || face === "bas") return NOIR;
        if (y >= 10) return y === 10 ? [40, 34, 28] : camo(x, y);
        return grain(NOIR, x, y, 74, 0.05);
      });
      // le gilet orange avec ses bandes réfléchissantes
      s.peindre("corps", 2, (face, x, y) => {
        if (face === "haut" || face === "bas" || y > 9) return null;
        if (face === "devant" && entre(x, 3, 4)) return null;
        return y === 6 || y === 8 ? [214, 218, 224] : grain(ORANGE, x, y, 75, 0.05);
      });
      bras(s, { peau: P, manche: (face, x, y, nom) => (y < 3 ? grain(NOIR, x, y, 76, 0.05) : nom === "brasG" && y === 9 ? [20, 20, 20] : null) });
      for (const nom of ["jambeD", "jambeG"]) s.peindre(nom, 1, (face, x, y) => (y >= 10 || face === "bas" ? grain([150, 118, 76], x, y, 77, 0.06) : camo(x + face.length * 3 + nom.length, y)));
    },
  },
};

export function skins() {
  return Object.fromEntries(Object.entries(GARS).map(([id, g]) => { const s = skin(); g.dessin(s); return [id, { w: 64, h: 64, px: s.px }]; }));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const DOSSIER = path.join(ICI, "..", "skins");
  fs.mkdirSync(DOSSIER, { recursive: true });
  for (const [id, sk] of Object.entries(skins())) ecrirePng(path.join(DOSSIER, id + ".png"), 64, 64, sk.px);
  console.log("skins dessinés dans", DOSSIER);
}
