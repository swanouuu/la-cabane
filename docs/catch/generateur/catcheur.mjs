// Les catcheurs des maquettes : un corps gonflé (épaules rondes, pecs, abdos en tablette, biceps, gros mollets), torse
// nu de la couleur du visage du skin, les 5 tenues par-dessus (slips, short, jean, collant, bottes, genouillères) et les
// 5 masques. Et le public : des gens simples aux couleurs tirées au hasard.
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, hache, corps, ton, melange, pointFace, DEG } from "./moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
/** Un skin uni (peau, cheveux bruns), quand les skins des maquettes ne sont pas là (ils ne sont pas dans le dépôt). */
function skinUni() {
  const px = Buffer.alloc(64 * 64 * 4);
  for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) {
    const tete = y < 16 && x < 32, cheveux = tete && (y < 10 || (y < 16 && (x < 8 || x >= 24)) );
    px.set(y < 16 && x >= 32 ? [0, 0, 0, 0] : cheveux ? [60, 40, 28, 255] : [214, 162, 120, 255], (y * 64 + x) * 4);
  }
  for (const x of [9, 10, 13, 14]) px.set([40, 40, 60, 255], (12 * 64 + x) * 4);
  return { w: 64, h: 64, px };
}
export const SKINS = Object.fromEntries(["steve", "efe", "kai"].map((n) => {
  try { return [n, lire(path.join(ICI, "../../fruits/generateur/skins", n + ".png"))]; } catch { return [n, skinUni()]; }
}));

const pix = (skin, x, y) => { const i = (y * 64 + x) * 4; return skin.px[i + 3] < 128 ? null : [skin.px[i], skin.px[i + 1], skin.px[i + 2]]; };

/** La couleur de peau d'un skin : la teinte la plus fréquente du bas du visage. */
export function teintePeau(skin) {
  const seaux = new Map();
  for (let y = 11; y < 16; y++) for (let x = 8; x < 16; x++) {
    const c = pix(skin, x, y);
    if (!c || 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2] < 50) continue;
    const cle = c.map((v) => v >> 4).join(","), s = seaux.get(cle) ?? { n: 0, c: [0, 0, 0] };
    s.n++; s.c = s.c.map((v, i) => v + c[i]); seaux.set(cle, s);
  }
  const m = [...seaux.values()].sort((a, b) => b.n - a.n)[0];
  return m.c.map((v) => v / m.n);
}

// les faces d'un pavé dans la texture d'un skin
const UVF = {
  devant: (u, v, w, h, d, i, j) => [u + d + i, v + d + j], dos: (u, v, w, h, d, i, j) => [u + 2 * d + w + i, v + d + j],
  droite: (u, v, w, h, d, i, j) => [u + i, v + d + j], gauche: (u, v, w, h, d, i, j) => [u + d + w + i, v + d + j],
  haut: (u, v, w, h, d, i, j) => [u + d + i, v + j], bas: (u, v, w, h, d, i, j) => [u + d + w + i, v + j],
};
const texSkin = (skin, [u, v], w, h, d) => (f, uu, vv, i, j) => pix(skin, ...UVF[f](u, v, w, h, d, i, j));

/** Une partie : la couleur reçoit le point local p de la case, la face et (u, v, i, j). */
const partie = (os, a, b, coul, pas = 0.5, decale = 0, role = "tenue") => ({ os, a, b, pas, decale, role, couleur: (f, u, v, i, j, nu, nv) => coul(pointFace(a, b, f, u, v), f, u, v, i, j, nu, nv) });
/** Le même pavé de l'autre côté du corps (x → -x). */
const miroir = (a, b) => [[-b[0], a[1], a[2]], [-a[0], b[1], b[2]]];

// ------------------------------------------------------------------ le corps gonflé
export const OS_GONFLE = {
  corps: { pivot: [0, 12, 0] },
  tete: { pivot: [0, 25, 0], parent: "corps" },
  brasD: { pivot: [-8.4, 23, 0], parent: "corps" },
  brasG: { pivot: [8.4, 23, 0], parent: "corps" },
  jambeD: { pivot: [-2.4, 12, 0] },
  jambeG: { pivot: [2.4, 12, 0] },
};

/** Les boîtes du corps gonflé, côté droit (le gauche est en miroir) : [os, a, b, muscle]. */
const MUSCLES = [
  ["corps", [-4.7, 12, -2.5], [4.7, 18.7, 2.5], "ventre"],
  ["corps", [-2.85, 13.9, 2.5], [2.85, 18.5, 3.15], "abdos"],
  ["corps", [-6.1, 18.6, -3.0], [6.1, 24.6, 3.0], "poitrine"],
  ["corps", [-5.7, 19.3, 3.0], [-0.12, 24.1, 4.1], "pecD"],
  ["corps", [0.12, 19.3, 3.0], [5.7, 24.1, 4.1], "pecG"],
  ["corps", [-4.3, 24.6, -2.8], [4.3, 26.0, 1.0], "trapezes"],
  ["corps", [-2.4, 24.6, -1.9], [2.4, 26.2, 1.9], "cou"],
  ["brasD", [-12.0, 19.3, -3.2], [-5.9, 25.3, 3.2], "delto"],
  ["brasD", [-11.3, 14.3, -2.8], [-6.3, 19.6, 2.8], "brasHaut"],
  ["brasD", [-10.9, 14.9, 2.8], [-6.7, 19.2, 3.7], "biceps"],
  ["brasD", [-11.1, 8.9, -2.6], [-6.5, 14.6, 2.6], "avantBras"],
  ["brasD", [-10.6, 6.4, -2.2], [-7.0, 8.9, 2.2], "main"],
  ["jambeD", [-5.1, 6.0, -2.8], [0.3, 12.3, 2.8], "cuisse"],
  ["jambeD", [-4.7, 0, -2.4], [-0.1, 6.1, 2.4], "jambe"],
  ["jambeD", [-4.3, 2.3, -3.05], [-0.5, 5.9, -2.4], "mollet"],
];

/** Les ombres et reflets qui dessinent les muscles sur la peau. */
function ombreMuscle(nom, p, f, u, v) {
  if (f === "bas") return 0.62;
  if (f === "haut") return 1.05;
  const bord = (x) => Math.pow(Math.abs(2 * x - 1), 2.5);
  switch (nom) {
    case "ventre": return f === "devant" ? 1 - 0.28 * bord(u) : f === "dos" ? (Math.abs(u - 0.5) < 0.05 ? 0.8 : 1 - 0.12 * bord(u)) : 0.9;
    case "abdos": {
      if (f !== "devant") return 0.86;
      if (Math.abs(u - 0.5) < 0.055) return 0.68;
      const rang = v * 3, dans = rang % 1;
      if (dans < 0.12 && rang > 0.5) return 0.72;
      if (Math.hypot((u - 0.5) * 5.7, (v - 0.9) * 4.6) < 0.32) return 0.55;
      return 1.1 - 0.16 * dans - 0.08 * bord(u);
    }
    case "poitrine": return f === "dos" ? (Math.abs(u - 0.5) < 0.04 ? 0.78 : 1.02 - 0.2 * bord(u) * v - 0.1 * (Math.abs(u - 0.5) < 0.3 && v > 0.15 && v < 0.55 ? 1 : 0)) : 0.96;
    case "pecD": case "pecG": {
      if (f !== "devant") return f === "gauche" || f === "droite" ? 0.86 : 1;
      const tc = nom === "pecD" ? 0.55 : 0.45;
      if (Math.hypot((u - tc) * 5.6, (v - 0.68) * 4.8) < 0.38) return [0.78, 0.6, 0.6];
      return v > 0.86 ? 0.74 : 1.1 - 0.14 * v;
    }
    case "trapezes": return 0.95;
    case "cou": return 0.88;
    case "delto": return f === "devant" || f === "dos" || f === "droite" || f === "gauche" ? 1.1 - 0.2 * v : 1;
    case "biceps": return f === "devant" ? 1.14 - 0.5 * (u - 0.5) ** 2 - 0.16 * v : 0.84;
    case "brasHaut": return 0.96 - 0.08 * v;
    case "avantBras": return f === "devant" ? 1.04 - 0.1 * v : 0.95;
    case "cuisse": return f === "devant" ? 1.08 - 0.1 * v - 0.12 * bord(u) : 0.95;
    case "jambe": return 0.95;
    case "mollet": return 1.06 - 0.1 * v;
    default: return 1;
  }
}

/** Le tatouage tribal du haut des bras (la tenue du slip noir). */
const tatouage = (p) => {
  const t = Math.atan2(p[2], Math.abs(p[0]) - 8.6) * 2.2 + p[1] * 0.55;
  return p[1] > 15.5 && Math.abs(Math.sin(t * 1.6)) < 0.3 && Math.sin(p[1] * 0.9 + t) > -0.2;
};

// ------------------------------------------------------------------ les tenues
const NOIR = [26, 26, 32], ARGENT = [206, 210, 222], ROUGE = [196, 30, 38], JAUNE = [252, 204, 40], BLEU = [36, 74, 196],
  BLANC = [238, 238, 242], JEAN = [74, 108, 162], VERT = [60, 168, 70], ORANGE = [250, 132, 30], OR = [240, 190, 50];

/** L'étoile à 5 branches dans un carré de côté 1 (pour le slip étoilé et le masque du champion). */
function etoile(x, y) {
  const a = Math.atan2(y, x), r = Math.hypot(x, y);
  const k = Math.cos((2 * Math.PI) / 10) / Math.cos(((a + Math.PI / 2) % ((2 * Math.PI) / 5) + 4 * Math.PI) % ((2 * Math.PI) / 5) - Math.PI / 5);
  return r < 0.5 * (0.45 + 0.55 * Math.max(0, k * k * k));
}
/** Des flammes qui montent : hauteur du feu selon la position autour de la jambe s. */
const hautFlamme = (s, base) => base + 2.8 * Math.abs(Math.sin(s * 0.8)) + 1.6 * Math.sin(s * 2.1 + 1) + 0.8 * Math.sin(s * 4.7);
function couleurFlamme(y, h) {
  const d = h - y;
  if (d < 0) return null;
  return d < 1.1 ? [250, 90, 20] : d < 2.6 ? [255, 150, 30] : d < 4 ? [255, 212, 70] : [255, 236, 150];
}

/**
 * Les 5 tenues. bas : "slip" (coupe droite ou échancrée), "short" (jusqu'à y = fin, ample ou pas), "collant".
 * couleur(p, f) donne le tissu ; bottes, genoux, poignets, coudes, brassard, gant : couleur ou null.
 */
export const TENUES = {
  slipNoir: {
    nom: "LE SLIP NOIR", bas: "slip", coupe: "droite", ceinture: ARGENT,
    couleur: (p, f) => (f === "gauche" && p[0] > 0) || (f === "droite" && p[0] < 0) ? (Math.abs(p[1] - 10.4) < 0.5 ? ARGENT : NOIR) : ton(NOIR, 1 + 0.5 * Math.max(0, 1 - Math.abs(p[1] - 11.2))),
    bottes: { haut: 5.2, c: NOIR, lacets: [120, 120, 130] }, genoux: NOIR, tatouage: true,
    texte: ["SLIP NOIR BRILLANT", "GENOUILLERES NOIRES", "TATOUAGES SUR LES BRAS"],
  },
  slipEtoile: {
    nom: "LE SLIP ETOILE", bas: "slip", coupe: "echancree", ceinture: JAUNE,
    couleur: (p) => {
      const gx = ((p[0] + p[2] * 0.7 + (Math.floor(p[1] / 1.9) % 2) * 0.95) / 1.9) % 1, gy = (p[1] / 1.9) % 1;
      return etoile(((gx < 0 ? gx + 1 : gx) - 0.5) * 1.35, (gy - 0.5) * 1.35) ? JAUNE : ROUGE;
    },
    bottes: { haut: 5.0, c: JAUNE, lacets: ROUGE }, genoux: ROUGE, poignets: JAUNE,
    texte: ["SLIP ROUGE A ETOILES", "BOTTES JAUNES", "GENOUILLERES ROUGES"],
  },
  shortLuchador: {
    nom: "LE SHORT LUCHADOR", bas: "short", fin: 3.4, ample: true, ceinture: ARGENT,
    couleur: (p, f) => {
      if (p[1] < 4.3) return ARGENT;
      const cote = (f === "droite" && p[0] < 0) || (f === "gauche" && p[0] > 0);
      if (cote) { const c = couleurFlamme(p[1], hautFlamme(p[2] * 1.3, 8)); return c ? melange(ARGENT, c, 0.25) : BLEU; }
      return Math.abs(Math.sin(p[1] * 1.1 + p[0] * 0.8)) < 0.12 ? [120, 150, 236] : BLEU;
    },
    bottes: { haut: 4.6, c: BLANC, lacets: BLEU }, poignets: BLEU, chaussettes: BLANC,
    texte: ["SHORT LARGE BLEU", "FLAMMES ARGENT", "BOTTES BLANCHES"],
  },
  jean: {
    nom: "LE SHORT EN JEAN", bas: "short", fin: 6.0, ample: false, ceinture: [70, 46, 30],
    couleur: (p, f, i, j) => {
      if (p[1] < 6.6) return hache(Math.round(p[0] * 2), Math.round(p[2] * 2), 3) < 0.5 ? [214, 222, 230] : ton(JEAN, 1.2);
      const poche = f === "dos" && p[1] > 9.2 && p[1] < 11.6 && Math.abs(Math.abs(p[0]) - 2.5) < 1.4;
      const k = (0.86 + 0.24 * hache(Math.round(p[0] * 3), Math.round(p[1] * 3), Math.round(p[2] * 3))) * (poche ? 0.82 : 1);
      return Math.abs(Math.abs(p[0]) - 2.5) < 0.12 && f === "devant" ? [222, 190, 110] : ton(JEAN, k);
    },
    bottes: { baskets: true, haut: 2.4, c: VERT, bande: ORANGE, lacets: BLANC }, chaussettes: BLANC, poignets: ORANGE, brassard: VERT,
    texte: ["SHORT EN JEAN", "BASKETS VERT-ORANGE", "POIGNETS EPONGE"],
  },
  collantFlammes: {
    nom: "LE COLLANT FLAMMES", bas: "collant", ceinture: ROUGE,
    couleur: (p) => couleurFlamme(p[1], hautFlamme(Math.atan2(p[2], p[0] - Math.sign(p[0]) * 2.4) * 3, 6.5)) ?? ton(NOIR, 1 + 0.3 * Math.max(0, 1 - Math.abs(p[1] - 10))),
    bottes: { haut: 5.6, c: NOIR, lacets: ROUGE }, coudes: NOIR, gant: NOIR,
    texte: ["COLLANT NOIR", "FLAMMES QUI MONTENT", "UN GANT, UNE COUDIERE"],
  },
};

// ------------------------------------------------------------------ les masques (16×16 par face)
/** f, i, j → couleur du masque, null = un trou (on voit le skin dessous). */
const yeux = (i, j, l = 0) => j >= 6 - l && j <= 9 + l && ((i >= 2 - l && i <= 6) || (i >= 9 && i <= 13 + l));
const oeilD = (i) => i < 8;
const bouche = (i, j) => j >= 11 && j <= 13 && i >= 5 && i <= 10;
export const MASQUES = {
  mysterio: {
    nom: "MYSTERIO", prise: "LE 619", couleurs: [BLEU, ARGENT],
    c: (f, i, j) => {
      if (f === "devant") {
        if (yeux(i, j)) return null;
        if (bouche(i, j)) return null;
        // les ailes d'argent autour des yeux, la croix blanche au front, le bord de la bouche
        const ail = j >= 4 && j <= 11 && ((i <= 7 && j - 4 < (8 - i) * 0.9 + 3 && !(i >= 2 && i <= 6 && j >= 6 && j <= 9)) || (i >= 8 && j - 4 < (i - 7) * 0.9 + 3 && !(i >= 9 && i <= 13 && j >= 6 && j <= 9)));
        if (ail && (i <= 1 || i >= 14 || j === 5 || j === 10 || j === 4 || j === 11)) return ARGENT;
        if ((i === 7 || i === 8) && j >= 0 && j <= 4) return BLANC;
        if (j === 2 && i >= 6 && i <= 9) return BLANC;
        if (j >= 10 && j <= 14 && i >= 4 && i <= 11) return ROUGE;
        return BLEU;
      }
      if (f === "dos") return (i === 7 || i === 8) && j > 2 && j < 14 ? (j % 2 ? BLANC : [40, 40, 50]) : (Math.abs(i - 7.5) < 3 && (j + i) % 4 === 0 && j > 2 ? ARGENT : BLEU);
      if (f === "haut") return Math.abs(i - 7.5) < 1.2 ? BLANC : BLEU;
      if (f === "bas") return null;
      return j > 4 && j < 12 && (i + j) % 5 === 0 ? ARGENT : BLEU;
    },
  },
  vipere: {
    nom: "VIPERE", prise: "LE RKO", couleurs: [[40, 116, 60], [244, 220, 40]],
    c: (f, i, j) => {
      const ecaille = ((i + (j % 2) * 1) >> 1) % 2 === 0 && j % 2 === 0;
      const vert = ecaille ? [64, 150, 78] : [34, 104, 54];
      if (f === "devant") {
        if (j >= 7 && j <= 8 && ((i >= 2 && i <= 6) || (i >= 9 && i <= 13))) return i === (oeilD(i) ? 5 : 10) ? [10, 10, 10, 1] : [250, 222, 40, 1];
        if ((j === 6 || j === 9) && ((i >= 2 && i <= 6) || (i >= 9 && i <= 13))) return [16, 40, 22];
        if (j === 12 && i >= 4 && i <= 11) return [20, 20, 20];
        if ((i === 4 || i === 11) && (j === 13 || j === 14)) return BLANC;
        if (j >= 11 && i >= 3 && i <= 12) return [150, 200, 110];
        return vert;
      }
      if (f === "bas") return null;
      if (f === "dos") return Math.abs(i - 7.5) < 2 ? (j % 3 === 0 ? [20, 60, 30] : [210, 190, 60]) : vert;
      return vert;
    },
  },
  champion: {
    nom: "CHAMPION", prise: "LE AA", couleurs: [VERT, ORANGE],
    c: (f, i, j) => {
      const camo = hache(i >> 2, j >> 2, f.length) < 0.33 ? [44, 110, 50] : hache(i >> 1, j >> 1, 7 + f.length) < 0.25 ? [100, 160, 70] : VERT;
      if (f === "devant") {
        if (yeux(i, j)) return null;
        if (bouche(i, j)) return null;
        if (j >= 5 && j <= 10 && (i === 1 || i === 14 || i === 7 || i === 8) || j === 5 && i > 0 && i < 15 || j === 10 && i > 0 && i < 15) return ORANGE;
        if (j <= 4 && etoile((i - 7.5) / 5, (j - 2.2) / 5)) return [255, 255, 255];
        return camo;
      }
      if (f === "bas") return null;
      if (f === "dos") return j >= 6 && j <= 8 ? ORANGE : camo;
      return j >= 6 && j <= 8 ? ORANGE : camo;
    },
  },
  demon: {
    nom: "DEMON", prise: "LE CHOKESLAM", couleurs: [ROUGE, NOIR],
    c: (f, i, j) => {
      const h = 9 - 3.4 * Math.abs(Math.sin(i * 0.9 + (f === "devant" ? 0 : 2))) - 1.8 * Math.sin(i * 2.3);
      const flamme = 15 - j < h;
      if (f === "devant") {
        if (j >= 6 && j <= 9 && ((i >= 2 && i <= 6) || (i >= 9 && i <= 13))) return j === 6 || j === 9 || i === 2 || i === 6 || i === 9 || i === 13 ? [150, 10, 14] : null;
        if (j >= 11 && j <= 12 && i >= 6 && i <= 9) return [12, 12, 12];
        if (Math.abs(i - 7.5) < 0.6 && j < 6) return [150, 10, 14];
        return flamme ? (15 - j < h - 2 ? [210, 24, 28] : [255, 110, 30]) : [22, 20, 24];
      }
      if (f === "bas") return null;
      if (f === "haut") return hache(i, j, 4) < 0.35 ? [40, 26, 20] : [22, 20, 24];
      return flamme ? (15 - j < h - 2 ? [210, 24, 28] : [255, 110, 30]) : [22, 20, 24];
    },
  },
  taureau: {
    nom: "TAUREAU", prise: "LE SPEAR", couleurs: [NOIR, OR],
    c: (f, i, j) => {
      if (f === "devant") {
        if (j >= 6 && j <= 8 && ((i >= 2 && i <= 6) || (i >= 9 && i <= 13))) return [255, 50, 40, 1];
        if (j >= 4 && j <= 5 && ((i >= 1 && i <= 6) || (i >= 9 && i <= 14))) return [80, 60, 30];
        if (j >= 10 && j <= 13 && i >= 4 && i <= 11) return j === 13 ? [30, 22, 18] : [70, 50, 40];
        if ((j === 11 || j === 12) && (i === 6 || i === 9)) return [10, 8, 8];
        if (Math.abs(i - 7.5) < 0.6 && j < 4) return OR;
        return [30, 28, 32];
      }
      if (f === "bas") return null;
      if (f === "dos") return Math.abs(i - 7.5) < 1.4 && j < 12 ? OR : [30, 28, 32];
      return j === 7 ? OR : [30, 28, 32];
    },
    cornes: true,
  },
};

// ------------------------------------------------------------------ le catcheur entier
/**
 * Les parties d'un catcheur : skin (pour la tête et la couleur de peau), tenue (clé de TENUES ou null : un slip gris),
 * masque (clé de MASQUES ou null : le visage du skin).
 */
export function catcheur(skin, tenue = null, masque = null, { peauBrute = false } = {}) {
  const P = peauBrute ? [1000, 1000, 1000] : teintePeau(skin), T = tenue ? TENUES[tenue] : { bas: "slip", coupe: "droite", couleur: () => [110, 110, 118], ceinture: [90, 90, 96], bottes: { haut: 4.2, c: [56, 56, 62], lacets: [130, 130, 136] }, genoux: [70, 70, 78] };
  const M = masque ? MASQUES[masque] : null;
  const parties = [];
  // la peau, avec les muscles
  for (const [os, a, b, nom] of MUSCLES) {
    const coul = (p, f, u, v, i, j) => {
      const k = ombreMuscle(nom, p, f, u, v);
      const g = 0.96 + 0.08 * hache(Math.round(p[0] * 2), Math.round(p[1] * 2), Math.round(p[2] * 2));
      if (T.tatouage && (nom === "delto" || nom === "brasHaut" || nom === "biceps") && tatouage(p)) return ton([40, 36, 44], g);
      return Array.isArray(k) ? P.map((c, q) => c * k[q] * g) : ton(P, k * g);
    };
    parties.push(partie(os, a, b, coul, 0.5, 0, "peau"));
    if (os.endsWith("D")) { const [a2, b2] = miroir(a, b); parties.push(partie(os.slice(0, -1) + "G", a2, b2, (p, f, u, v, i, j) => coul([-p[0], p[1], p[2]], f === "gauche" ? "droite" : f === "droite" ? "gauche" : f, f === "devant" || f === "dos" || f === "haut" || f === "bas" ? 1 - u : u, v, i, j), 0.5, 0, "peau")); }
    if (nom === "pecD" || nom === "pecG") continue;
  }
  const deuxCotes = (os, a, b, coul, pas = 0.5) => {
    parties.push(partie(os + "D", a, b, coul, pas));
    const [a2, b2] = miroir(a, b);
    parties.push(partie(os + "G", a2, b2, (p, f, ...r) => coul(p, f, ...r), pas));
  };
  // le bas
  const tissu = (p, f, u, v, i, j) => T.couleur(p, f, i, j);
  if (T.bas === "slip") {
    const coupe = (p, f) => {
      const ext = Math.min(1, Math.max(0, (Math.abs(p[0]) - 0.3) / 4.9));
      const bas = T.coupe === "echancree" ? 9.2 + 2.3 * ext * ext + (f === "dos" ? -0.4 : 0) : 9.0;
      return p[1] >= bas;
    };
    deuxCotes("jambe", [-5.3, 8.4, -3.0], [0.5, 12.5, 3.0], (p, f, ...r) => (coupe(p, f) ? tissu(p, f, ...r) : null), 0.4);
    parties.push(partie("corps", [-4.95, 11.9, -2.75], [4.95, 13.9, 2.75], (p, f, u, v, i, j) => (p[1] > 13.3 ? T.ceinture : T.couleur(p, f, i, j)), 0.4));
  } else if (T.bas === "short") {
    const ample = T.ample ? 0.55 : 0.25;
    deuxCotes("jambe", [-5.1 - ample, T.fin, -2.8 - ample], [0.3 + ample, 12.5, 2.8 + ample], (p, f, ...r) => tissu(p, f, ...r), 0.4);
    if (T.ample) deuxCotes("jambe", [-5.9, T.fin - 0.2, -3.6], [0.95, T.fin + 2.2, 3.6], (p, f, ...r) => (f === "haut" ? null : tissu(p, f, ...r)), 0.4);
    parties.push(partie("corps", [-5.1, 11.8, -2.85], [5.1, 14.1, 2.85], (p, f, u, v, i, j) => (p[1] > 13.3 ? (T.ceinture ?? T.couleur(p, f, i, j)) : T.couleur(p, f, i, j)), 0.4));
    if (T.ceinture && T.nom.includes("JEAN")) parties.push(partie("corps", [-0.8, 12.9, 2.85], [0.8, 14.0, 3.05], () => [210, 212, 220], 0.3));
  } else {
    deuxCotes("jambe", [-5.25, 5.8, -2.95], [0.45, 12.45, 2.95], tissu, 0.4);
    deuxCotes("jambe", [-4.85, 0, -2.55], [0.05, 6.2, 2.55], tissu, 0.4);
    deuxCotes("jambe", [-4.45, 2.2, -3.2], [-0.35, 6.0, -2.5], tissu, 0.4);
    parties.push(partie("corps", [-4.95, 11.9, -2.75], [4.95, 13.9, 2.75], (p, f, u, v, i, j) => (p[1] > 13.3 ? T.ceinture : T.couleur(p, f, i, j)), 0.4));
  }
  // les bottes, les chaussettes, les genouillères
  const B = T.bottes;
  if (T.chaussettes) deuxCotes("jambe", [-4.85, 0, -2.55], [0.05, B.haut + 2.2, 2.55], (p, f) => (f === "haut" ? null : ton(T.chaussettes, 0.95 + 0.05 * Math.sin(p[1] * 3))), 0.5);
  deuxCotes("jambe", [-5.0, -0.02, -2.75], [0.2, B.haut, B.baskets ? 3.3 : 2.85], (p, f, u, v) => {
    if (p[1] < (B.baskets ? 0.9 : 0.6)) return B.baskets ? BLANC : [20, 20, 22];
    if (B.bande && Math.abs(p[1] - 1.5) < 0.35) return B.bande;
    if (f === "devant" && Math.abs(Math.abs(p[0]) - 2.4) < 0.9 && Math.round(p[1] * 2) % 2 === 0 && p[1] > 1.2) return B.lacets;
    if (f === "haut") return ton(B.c, 0.8);
    return ton(B.c, 1 + 0.15 * (1 - v));
  }, 0.4);
  if (T.genoux) deuxCotes("jambe", [-5.25, 4.5, -2.6], [0.45, 7.6, 3.3], (p, f, u, v) => (f === "devant" ? ton(T.genoux, Math.abs(v - 0.5) < 0.12 ? 0.75 : 1.05) : ton(T.genoux, 0.9)), 0.4);
  // les bras
  if (T.poignets) deuxCotes("bras", [-11.4, 9.3, -2.9], [-6.2, 11.5, 2.9], (p, f) => ton(T.poignets, 0.95 + 0.1 * Math.sin(p[1] * 4)), 0.4);
  if (T.coudes) parties.push(partie("brasD", [-11.5, 13.3, -2.95], [-6.1, 15.9, 2.95], () => T.coudes, 0.4));
  if (T.brassard) deuxCotes("bras", [-11.6, 17.0, -3.1], [-5.9, 18.3, 3.9], () => T.brassard, 0.4);
  if (T.gant) parties.push(partie("brasD", [-10.85, 6.2, -2.45], [-6.75, 9.7, 2.45], (p) => ton(T.gant, p[1] > 9.1 ? 0.8 : 1), 0.4));
  // la tête : le skin, puis la seconde couche ou le masque
  parties.push({ os: "tete", role: "visage", a: [-4, 25, -4], b: [4, 33, 4], pas: 1, couleur: texSkin(skin, [0, 0], 8, 8, 8) });
  if (!M) parties.push({ os: "tete", role: "visage", a: [-4.5, 24.5, -4.5], b: [4.5, 33.5, 4.5], pas: 9 / 8, decale: 0.02, couleur: texSkin(skin, [32, 0], 8, 8, 8) });
  else {
    parties.push({ os: "tete", role: "masque", a: [-4.35, 24.65, -4.35], b: [4.35, 33.35, 4.35], pas: 8.7 / 16, decale: 0.02, couleur: (f, u, v, i, j) => M.c(f, i, j) });
    if (masque === "vipere") for (const s of [-1, 1]) parties.push(partie("tete", s < 0 ? [-6.3, 25.5, -2.2] : [4.35, 25.5, -2.2], s < 0 ? [-4.35, 32.6, 0.6] : [6.3, 32.6, 0.6], (p, f) => (f === "devant" ? (Math.round(p[1]) % 2 ? [244, 220, 40] : [34, 104, 54]) : [40, 116, 60]), 0.5, 0, "extra"));
    if (M.cornes) for (const s of [-1, 1]) {
      const bx = (x0, x1) => (s < 0 ? [-x1, -x0] : [x0, x1]);
      const [a0, a1] = bx(4.3, 6.6), [b0, b1] = bx(6.0, 7.8), [c0, c1] = bx(6.9, 8.0);
      parties.push(partie("tete", [a0, 29.6, -1.1], [a1, 31.5, 1.1], () => [236, 226, 196], 0.4, 0, "extra"));
      parties.push(partie("tete", [b0, 30.8, -0.9], [b1, 33.6, 0.9], () => [226, 212, 176], 0.4, 0, "extra"));
      parties.push(partie("tete", [c0, 33.2, -0.6], [c1, 35.6, 0.6], (p) => (p[1] > 34.6 ? OR : [216, 200, 160]), 0.4, 0, "extra"));
    }
    if (masque === "taureau") {
      parties.push(partie("tete", [-0.9, 26.0, 4.3], [0.9, 26.5, 4.8], () => OR, 0.25, 0, "extra"));
      parties.push(partie("tete", [-1.2, 25.4, 4.3], [-0.7, 26.3, 4.8], () => OR, 0.25, 0, "extra"));
      parties.push(partie("tete", [0.7, 25.4, 4.3], [1.2, 26.3, 4.8], () => OR, 0.25, 0, "extra"));
    }
  }
  return { os: OS_GONFLE, parties, peau: P };
}

/** Dessine un catcheur (ses parties) dans une pose : pos (hanches, en unités), lacet…, et les os en degrés. */
export const dessiner = (sc, c, pose) => corps(sc, c.os, c.parties, pose);

// ------------------------------------------------------------------ le joueur normal (avant la tenue)
export const OS_NORMAL = {
  corps: { pivot: [0, 12, 0] }, tete: { pivot: [0, 24, 0], parent: "corps" },
  brasD: { pivot: [-5, 22, 0], parent: "corps" }, brasG: { pivot: [5, 22, 0], parent: "corps" },
  jambeD: { pivot: [-2, 12, 0] }, jambeG: { pivot: [2, 12, 0] },
};
const BOITES = [
  ["tete", [-4, 24, -4], [4, 32, 4], [0, 0], [32, 0], 8, 8, 8, 0.5],
  ["corps", [-4, 12, -2], [4, 24, 2], [16, 16], [16, 32], 8, 12, 4, 0.25],
  ["brasD", [-8, 12, -2], [-4, 24, 2], [40, 16], [40, 32], 4, 12, 4, 0.25],
  ["brasG", [4, 12, -2], [8, 24, 2], [32, 48], [48, 48], 4, 12, 4, 0.25],
  ["jambeD", [-4, 0, -2], [0, 12, 2], [0, 16], [0, 32], 4, 12, 4, 0.25],
  ["jambeG", [0, 0, -2], [4, 12, 2], [16, 48], [0, 48], 4, 12, 4, 0.25],
];
export function joueur(skin) {
  const parties = [];
  for (const [os, a, b, uv1, uv2, w, h, d, g] of BOITES) {
    parties.push({ os, a, b, pas: 1, couleur: texSkin(skin, uv1, w, h, d) });
    parties.push({ os, a: a.map((v) => v - g), b: b.map((v) => v + g), pas: (w + 2 * g) / w, decale: 0.02, couleur: texSkin(skin, uv2, w, h, d) });
  }
  return { os: OS_NORMAL, parties };
}

// ------------------------------------------------------------------ le public
const PEAUX = [[244, 204, 170], [226, 178, 140], [198, 146, 104], [150, 100, 66], [108, 70, 46], [236, 190, 150]];
const CHEVEUX = [[40, 28, 20], [90, 60, 30], [20, 18, 18], [200, 160, 80], [130, 70, 30], [60, 50, 46], [160, 160, 160]];
const HABITS = [[200, 36, 40], [36, 70, 180], [30, 30, 34], [236, 236, 236], [250, 200, 40], [60, 150, 70], [130, 60, 170], [250, 130, 40], [120, 124, 130], [60, 200, 210], [236, 90, 150]];
const PANTALONS = [[50, 70, 120], [40, 40, 46], [90, 80, 70], [70, 96, 150]];

/** Un spectateur tiré au hasard (graine n) : ses parties sur le corps normal, à gros pixels (vu de loin). */
export function spectateur(n) {
  const h = (k) => hache(n, k, 911);
  const peau = PEAUX[Math.floor(h(1) * PEAUX.length)], cheveux = CHEVEUX[Math.floor(h(2) * CHEVEUX.length)];
  const habit = HABITS[Math.floor(h(3) * HABITS.length)], pantalon = PANTALONS[Math.floor(h(4) * PANTALONS.length)];
  const masque = h(5) < 0.08 ? MASQUES[["mysterio", "vipere", "champion", "demon", "taureau"][Math.floor(h(6) * 5)]] : null;
  const imprime = h(7) < 0.35, casquette = h(8) < 0.15;
  const tete = (f, u, v, i, j) => {
    if (masque) return masque.c(f, Math.min(15, i * 4 + 1), Math.min(15, j * 4 + 1)) ?? peau;
    if (casquette && (f === "haut" || j === 0)) return habit;
    if (f === "haut" || f === "dos" || (j === 0 && f !== "bas")) return cheveux;
    if ((f === "droite" || f === "gauche") && j < 2 && (f === "droite" ? i < 2 : i > 1)) return cheveux;
    if (f === "devant" && j === 2 && (i === 1 || i === 2)) return i === 1 ? [30, 30, 40] : [30, 30, 40];
    return peau;
  };
  const parties = [
    { os: "tete", a: [-4, 24, -4], b: [4, 32, 4], pas: 2, couleur: tete },
    { os: "corps", a: [-4, 12, -2], b: [4, 24, 2], pas: 2, couleur: (f, u, v, i, j) => (imprime && f === "devant" && i > 0 && i < 3 && j > 0 && j < 3 ? [240, 240, 240] : habit) },
    { os: "brasD", a: [-8, 12, -2], b: [-4, 24, 2], pas: 2, couleur: (f, u, v, i, j) => (j < 2 && f !== "bas" ? habit : peau) },
    { os: "brasG", a: [4, 12, -2], b: [8, 24, 2], pas: 2, couleur: (f, u, v, i, j) => (j < 2 && f !== "bas" ? habit : peau) },
    { os: "jambeD", a: [-4, 0, -2], b: [0, 12, 2], pas: 2, couleur: (f, u, v, i, j) => (j === 5 ? [40, 36, 36] : pantalon) },
    { os: "jambeG", a: [0, 0, -2], b: [4, 12, 2], pas: 2, couleur: (f, u, v, i, j) => (j === 5 ? [40, 36, 36] : pantalon) },
  ];
  return { os: OS_NORMAL, parties, habit };
}

/** L'arbitre : chemise rayée noir et blanc, pantalon noir. */
export function arbitre() {
  const peau = [226, 178, 140], ch = [50, 36, 26];
  const raye = (f, u, v, i, j) => ((f === "devant" || f === "dos") ? (i % 2 ? [24, 24, 28] : [236, 236, 236]) : (f === "haut" ? [236, 236, 236] : i % 2 ? [24, 24, 28] : [236, 236, 236]));
  const parties = [
    { os: "tete", a: [-4, 24, -4], b: [4, 32, 4], pas: 1, couleur: (f, u, v, i, j) => (f === "haut" || f === "dos" || (j < 2 && f !== "bas") ? ch : f === "devant" && j === 4 && (i === 2 || i === 5) ? [30, 30, 40] : f === "devant" && j === 6 && i > 2 && i < 5 ? [150, 90, 70] : peau) },
    { os: "corps", a: [-4, 12, -2], b: [4, 24, 2], pas: 1, couleur: raye },
    { os: "brasD", a: [-8, 12, -2], b: [-4, 24, 2], pas: 1, couleur: (f, u, v, i, j) => (j < 5 ? raye(f, u, v, i, j) : peau) },
    { os: "brasG", a: [4, 12, -2], b: [8, 24, 2], pas: 1, couleur: (f, u, v, i, j) => (j < 5 ? raye(f, u, v, i, j) : peau) },
    { os: "jambeD", a: [-4, 0, -2], b: [0, 12, 2], pas: 1, couleur: (f, u, v, i, j) => (j > 9 ? [14, 14, 16] : [30, 30, 34]) },
    { os: "jambeG", a: [0, 0, -2], b: [4, 12, 2], pas: 1, couleur: (f, u, v, i, j) => (j > 9 ? [14, 14, 16] : [30, 30, 34]) },
  ];
  return { os: OS_NORMAL, parties };
}
