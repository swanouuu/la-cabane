// Trois façons de dessiner le motif dans le pixel de la pupille du skin (2026-11-04), pour choisir la plus lisible, sans
// jamais déborder de l'œil (le blanc + la pupille) :
//   A : le motif fin, en 16 × 16 (la version d'avant) ;
//   B : un motif plus gros et plus épais, en 8 × 8 (les tomoe et les lames en gros pixels) ;
//   C : B, avec une lueur de la couleur de l'œil qui déborde sur le blanc de l'œil (et pas plus loin).
// node pupilles.mjs → planche-pupilles.png
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, ecrireTexte, mix, lum, scene } from "../../fruits/generateur/moteur.mjs";
import { perso, SKINS } from "../../fruits/generateur/rendu.mjs";
import { iris, etat, N } from "./yeux.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");

/** Le motif de la pupille en n × n. gras : une case devient noire dès qu'un tiers de la case est noir (formes épaisses). */
function motif(e, n, gras) {
  const tex = iris({ ...e, plein: true }), k = N / n, out = [];
  for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) {
    let clair = [0, 0, 0], nc = 0, sombre = [0, 0, 0], ns = 0;
    for (let y = 0; y < k; y++) for (let x = 0; x < k; x++) {
      const q = ((j * k + y) * N + i * k + x) * 4, c = [tex[q], tex[q + 1], tex[q + 2]];
      if (lum(c) < 55) { sombre = sombre.map((v, z) => v + c[z]); ns++; } else { clair = clair.map((v, z) => v + c[z]); nc++; }
    }
    const fs = ns / (k * k);
    if (gras) out.push(fs > 0.33 || !nc ? sombre.map((v) => v / ns) : clair.map((v) => Math.min(255, (v / nc) * 1.08)));
    else out.push(mix(nc ? clair.map((v) => v / nc) : [0, 0, 0], ns ? sombre.map((v) => v / ns) : [0, 0, 0], fs));
  }
  return out;
}

/** La pupille du skin : de x0 à x0 + 1, de y 27 à 28. lueur : le blanc à côté (de xb à xb + 1) prend la couleur de l'œil. */
function oeil(sc, T, x0, m, n, lueur, xb, couleur) {
  const P = 1 / n;
  // le fond du pixel, un peu plus grand, cache la couleur de la pupille du skin sur les bords
  sc.quad([[x0 - 0.04, 28.04, 4.035], [x0 + 1.04, 28.04, 4.035], [x0 + 1.04, 26.96, 4.035], [x0 - 0.04, 26.96, 4.035]].map(T.tete.p), m[(n >> 1) * n], 0.28, true);
  for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) {
    const x = x0 + i * P, y = 28 - j * P;
    sc.quad([[x, y, 4.04], [x + P, y, 4.04], [x + P, y - P, 4.04], [x, y - P, 4.04]].map(T.tete.p), m[j * n + i], 0.3, true);
  }
  if (lueur) for (let q = 0; q < 4; q++) {
    // de la pupille vers le coin de l'œil, la lueur s'éteint
    const pres = xb < x0 ? xb + 1 - (q + 1) / 4 : xb + q / 4;
    sc.quad([[pres, 28, 4.03], [pres + 0.25, 28, 4.03], [pres + 0.25, 27, 4.03], [pres, 27, 4.03]].map(T.tete.p), mix([238, 238, 238], couleur, 0.55 - 0.13 * q), 0.25, false);
  }
}

const ROUGE = [255, 60, 60], VIOLET = [190, 150, 255];
const MANG = () => ({ ...etat(2, 4.4) });

/** Les deux yeux à l'éveil n : [motif de l'œil droit, couleur], [motif de l'œil gauche, couleur]. */
function yeux(niv, n, gras) {
  if (niv === 1) { const m = motif(etat(1, 4.2), n, gras); return [[m, ROUGE], [m, ROUGE]]; }
  if (niv === 2) { const m = motif(MANG(), n, gras); return [[m, ROUGE], [m, ROUGE]]; }
  return [[motif(MANG(), n, gras), ROUGE], [motif(etat(3, 4.8), n, gras), VIOLET]];
}

function dessine(sc, niv, o) {
  const T = perso(sc, SKINS.kai, {});
  const [[md, cd], [mg, cg]] = yeux(niv, o.n, o.gras);
  // œil droit : blanc en colonne 1 (x -3), pupille en colonne 2 (x -2) ; œil gauche : pupille x 1, blanc x 2
  oeil(sc, T, -2, md, o.n, o.lueur, -3, cd);
  oeil(sc, T, 1, mg, o.n, o.lueur, 2, cg);
}

const OPTIONS = [
  { nom: "A : MOTIF FIN 16 X 16 (AVANT)", n: 16, gras: false, lueur: false },
  { nom: "B : MOTIF GROS ET EPAIS 8 X 8", n: 8, gras: true, lueur: false },
  { nom: "C : B + LUEUR SUR LE BLANC DE L'OEIL", n: 8, gras: true, lueur: true },
];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

const CW = 300, M = 40, HAUT = 120, W = CW * 6, LIGNE = M + CW, H = HAUT + 3 * LIGNE;
const img = Buffer.alloc(W * H * 4, 255);
rect(img, W, 0, 0, W, H, [236, 236, 242, 255]);
ecrireTexte(img, W, "LE MOTIF DANS LA PUPILLE DU SKIN : 3 FACONS, ON NE DEBORDE JAMAIS DE L'OEIL", 24, 20, 4, [30, 30, 40, 255]);
["EVEIL 1", "EVEIL 2", "EVEIL 3"].forEach((t, k) => {
  ecrireTexte(img, W, t + " : DE PRES", k * 2 * CW + 12, 82, 3, [80, 80, 96, 255]);
  ecrireTexte(img, W, "A 3 BLOCS", (k * 2 + 1) * CW + 12, 82, 3, [80, 80, 96, 255]);
});
OPTIONS.forEach((o, l) => {
  const y0 = HAUT + l * LIGNE;
  ecrireTexte(img, W, o.nom, 12, y0 + 10, 4, [180, 30, 40, 255]);
  for (let niv = 1; niv <= 3; niv++) {
    const pres = scene({ w: CW, h: CW, lacet: -18, tangage: 4, echelle: 34, pied: CW / 2, centre: [0, 27.5, 0], fond: [44, 44, 54] });
    dessine(pres, niv, o);
    const loin = scene({ w: CW, h: CW, lacet: -16, tangage: 4, echelle: 7.5, pied: CW * 0.94, centre: [0, 0, 0], fond: [150, 196, 242], recul: 60 });
    dessine(loin, niv, o);
    for (const [sc, c] of [[pres, (niv - 1) * 2], [loin, (niv - 1) * 2 + 1]])
      for (let r = 0; r < CW; r++) sc.img.copy(img, ((y0 + M + r) * W + c * CW) * 4, r * CW * 4, (r + 1) * CW * 4);
  }
});
ecrirePng(path.join(DOC, "planche-pupilles.png"), W, H, img);
console.log("ok", W, H);
