// Les yeux sur un vrai perso Minecraft (demandé le 2026-11-04) : en jeu, les yeux de la skin sont des pixels. Chaque œil
// garde son blanc ; seul le pixel de la pupille (la couleur à côté du blanc) prend le motif en 16 × 16 (comme les
// textures fines du mod) et qui brille. Éveil 1 : deux Sharingan ; éveil 2 : deux Mangekyo ; éveil 3 : Rinnegan à l'œil
// gauche, Mangekyo à l'œil droit.
// node perso.mjs → planche-perso.png ; node perso.mjs video <dossier> → les images (24 par seconde) du gros plan animé.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, ecrireTexte, mix, scene } from "../../fruits/generateur/moteur.mjs";
import { perso, SKINS } from "../../fruits/generateur/rendu.mjs";
import { iris, etat, N, DUREES } from "./yeux.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const C = 16;                       // le motif : 16 × 16 dans le carré de l'œil
const FOND_R = [26, 4, 8], FOND_V = [22, 10, 34];

/** Le motif carré de la pupille : l'iris de 48 × 48 qui remplit tout le carré, réduit en 16 × 16. */
function carre(e, violet) {
  const tex = iris({ ...e, plein: true }), k = N / C, out = [];
  for (let j = 0; j < C; j++) for (let i = 0; i < C; i++) {
    let r = 0, g = 0, b = 0, a = 0;
    for (let y = 0; y < k; y++) for (let x = 0; x < k; x++) {
      const q = ((j * k + y) * N + i * k + x) * 4;
      if (!tex[q + 3]) continue;
      r += tex[q]; g += tex[q + 1]; b += tex[q + 2]; a++;
    }
    const fond = violet ? FOND_V : FOND_R;
    out.push(a ? mix(fond, [r / a, g / a, b / a], a / (k * k)) : fond);
  }
  return out;
}

/** La pupille du skin (le pixel de couleur à côté du blanc) : de x0 à x0 + 1, de y 27 à 28, juste devant la face (z 4). */
// La lueur (choisie le 2026-11-04 : « la A avec la lueur ») : le blanc de l'œil, à côté de la pupille, prend la couleur de
// l'œil, plus fort contre la pupille ; elle ne déborde jamais du blanc.
function oeil(sc, T, x0, motif, force = 1, couleur = ROUGE_L) {
  const P = 1 / C;
  // le fond du pixel, un peu plus grand, cache la couleur de la pupille du skin sur les bords
  sc.quad([[x0 - 0.04, 28.04, 4.035], [x0 + 1.04, 28.04, 4.035], [x0 + 1.04, 26.96, 4.035], [x0 - 0.04, 26.96, 4.035]].map(T.tete.p), mix([0, 0, 0], motif[(C >> 1) * C], force), 0.28, true);
  for (let j = 0; j < C; j++) for (let i = 0; i < C; i++) {
    const x = x0 + i * P, y = 28 - j * P, c = motif[j * C + i];
    sc.quad([[x, y, 4.04], [x + P, y, 4.04], [x + P, y - P, 4.04], [x, y - P, 4.04]].map(T.tete.p), mix([0, 0, 0], c, force), 0.3, true);
  }
  // le blanc est à l'extérieur : à gauche de la pupille pour l'œil droit (x -3), à droite pour l'œil gauche (x 2)
  const xb = x0 < 0 ? x0 - 1 : x0 + 1;
  for (let q = 0; q < 4; q++) {
    const xq = xb < x0 ? xb + 1 - (q + 1) / 4 : xb + q / 4;
    sc.quad([[xq, 28, 4.03], [xq + 0.25, 28, 4.03], [xq + 0.25, 27, 4.03], [xq, 27, 4.03]].map(T.tete.p), mix([238, 238, 238], couleur, (0.55 - 0.13 * q) * force), 0.25, false);
  }
}
const ROUGE_L = [255, 60, 60], VIOLET_L = [190, 150, 255];

/** Les deux yeux à l'éveil n, au temps t (t grand = l'œil final) : [motif, couleur de la lueur] pour chaque œil. */
function yeux(n, t) {
  if (n === 1) { const m = carre(etat(1, t), false); return [[m, ROUGE_L], [m, ROUGE_L]]; }
  if (n === 2) { const m = carre(etat(2, t), false); return [[m, ROUGE_L], [m, ROUGE_L]]; }
  const e3 = etat(3, t), onde = e3.onde ?? 0;
  return [[carre({ ...etat(2, 4.4), rot: etat(2, 4.4).rot + 0.4 * t }, false), ROUGE_L], [carre(e3, onde > 0.5), mix(ROUGE_L, VIOLET_L, onde)]];
}

function dessine(sc, n, t, pose = {}) {
  const T = perso(sc, SKINS.kai, pose);
  const [[droit, cd], [gauche, cg]] = yeux(n, t);
  oeil(sc, T, -2, droit, 1, cd);
  oeil(sc, T, 1, gauche, 1, cg);
  return T;
}

const NOMS = ["EVEIL 1 : SHARINGAN", "EVEIL 2 : MANGEKYO", "EVEIL 3 : RINNEGAN + MANGEKYO"];
const FINS = [4.2, 4.4, 4.8];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}
function colle(img, W, sc, x0, y0) {
  for (let r = 0; r < sc.h; r++) sc.img.copy(img, ((y0 + r) * W + x0) * 4, r * sc.w * 4, (r + 1) * sc.w * 4);
}

function planche() {
  const CW = 520, M = 34, HAUT = 110, W = CW * 3, H = HAUT + 3 * (M + CW);
  const img = Buffer.alloc(W * H * 4, 255);
  rect(img, W, 0, 0, W, H, [236, 236, 242, 255]);
  ecrireTexte(img, W, "LES YEUX SUR UN PERSO MINECRAFT : SEULEMENT LA PUPILLE DU SKIN (1 PIXEL)", 30, 26, 4, [30, 30, 40, 255]);
  ecrireTexte(img, W, "MOTIF FIN 16 X 16 DANS LE PIXEL DE LA PUPILLE, LUEUR SUR LE BLANC DE L OEIL", 30, 70, 3, [80, 80, 96, 255]);
  for (let n = 1; n <= 3; n++) {
    const x0 = (n - 1) * CW;
    // de face, en gros plan
    const a = scene({ w: CW, h: CW, lacet: 0, tangage: 0, echelle: 52, pied: CW / 2, centre: [0, 28, 0], fond: [44, 44, 54] });
    dessine(a, n, FINS[n - 1]);
    colle(img, W, a, x0, HAUT + M);
    ecrireTexte(img, W, NOMS[n - 1], x0 + 14, HAUT + 8, 3, n === 3 ? [120, 70, 200, 255] : [200, 30, 30, 255]);
    // de trois quarts, la tête un peu tournée
    const b = scene({ w: CW, h: CW, lacet: -32, tangage: 8, echelle: 40, pied: CW / 2, centre: [0, 27, 0], fond: [44, 44, 54] });
    dessine(b, n, FINS[n - 1]);
    colle(img, W, b, x0, HAUT + 2 * M + CW);
    // le corps entier, à 3 blocs, en plein jour
    const c = scene({ w: CW, h: CW, lacet: -20, tangage: 6, echelle: 13, pied: CW * 0.9, centre: [0, 0, 0], fond: [150, 196, 242], recul: 60 });
    dessine(c, n, FINS[n - 1]);
    colle(img, W, c, x0, HAUT + 3 * M + 2 * CW);
  }
  ecrireTexte(img, W, "DE FACE", 14, HAUT + M + 10, 3, [200, 200, 210, 255]);
  ecrireTexte(img, W, "TROIS QUARTS", 14, HAUT + 2 * M + CW + 10, 3, [200, 200, 210, 255]);
  ecrireTexte(img, W, "A 3 BLOCS", 14, HAUT + 3 * M + 2 * CW + 10, 3, [40, 40, 60, 255]);
  ecrirePng(path.join(DOC, "planche-perso.png"), W, H, img);
  console.log("ok planche", W, H);
}

/** Le gros plan animé : la tête tourne doucement pendant les trois éveils. */
function video(dossier) {
  fs.mkdirSync(dossier, { recursive: true });
  let k = 0;
  const total = DUREES.reduce((s, d) => s + d, 0);
  for (let n = 1; n <= 3; n++) {
    const nb = Math.round(DUREES[n - 1] * 24);
    for (let i = 0; i < nb; i++) {
      const t = i / 24, g = k / 24 / total;
      const sc = scene({ w: 960, h: 540, lacet: -35 + 60 * g, tangage: 6, echelle: 30, pied: 300, centre: [0, 24, 0], fond: [40, 40, 50] });
      const T = perso(sc, SKINS.kai, {});
      // au début de l'éveil 1, les yeux de la skin ; puis le carré s'allume
      const [[droit, cd], [gauche, cg]] = yeux(n, t);
      const force = n === 1 ? Math.min(1, Math.max(0, (t - 0.5) / 0.5)) : 1;
      if (force > 0) { oeil(sc, T, -2, droit, force, cd); oeil(sc, T, 1, gauche, force, cg); }
      const e = etat(n, t), fl = e.eclair ?? 0;
      if (fl > 0) for (let q = 0; q < sc.w * sc.h; q++) for (let c = 0; c < 3; c++) sc.img[q * 4 + c] += ((e.couleur[c] * 0.5 + 128) - sc.img[q * 4 + c]) * fl * 0.6;
      ecrireTexte(sc.img, 960, NOMS[n - 1], 30, 490, 4, [210, 210, 220, 255]);
      ecrirePng(path.join(dossier, String(k++).padStart(4, "0") + ".png"), 960, 540, sc.img);
    }
  }
  console.log("ok video", k);
}

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  if (process.argv[2] === "video") video(process.argv[3]);
  else planche();
}
