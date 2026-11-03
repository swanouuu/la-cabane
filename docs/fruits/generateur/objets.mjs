// Les deux fruits eux-mêmes. L'icône est en 64×64 (le jeu l'affiche pixel pour pixel en 1080p) : le Hie Hie no Mi,
// couvert de tourbillons blancs et bleus comme sur le dessin, et le Magu Magu no Mi, même forme, en rouge et orange de
// lave. Shift + clic droit le pose en 3D (sur une table) ; clic droit maintenu le mange. Celui qui en mange un est mis
// tout seul sur la whitelist de la glace (ou de la lave), avec une petite animation. On ne les trouve nulle part : seul
// l'hôte les donne.
// node objets.mjs → hie_hie_no_mi.png, magu_magu_no_mi.png, planche-objets.png, et les fichiers du mod (icônes, fruit
// posé, gants de lave et de glace, butins)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, clair, mix, hache, norme, croix, scene, ecrireTexte } from "./moteur.mjs";
import {
  ajoute, sous, fois, rotY, TOUR, ID, brille, pave, cube, flamme, cristal, perso, sol, legende, EPEE, fissure,
  CROUTE, CROUTE_R, LAVE_O, LAVE_J, LAVE_R, GLACE, GLACE_C, BLANC, FUMEE, HERBE, FOND, PEAU_LAVE, PEAU_GLACE, STEVE, SKINS, LAVE, GEL,
} from "./rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");

// ------------------------------------------------------------------ les tourbillons
const TIGE = { contour: [44, 96, 30], fonce: [86, 152, 48], milieu: [138, 206, 74], clair: [204, 238, 132] };
const PALETTES = {
  glace: { contour: [26, 50, 96], fonce: [44, 96, 158], ligne: [78, 146, 202], ombre: [152, 206, 234], milieu: [208, 236, 249], clair: [250, 253, 255] },
  lave: { contour: [44, 8, 6], fonce: [104, 16, 10], ligne: [160, 32, 14], ombre: [210, 64, 20], milieu: [246, 124, 32], clair: [255, 206, 86] },
};

/**
 * Les tourbillons, serrés en nid d'abeilles : chaque pixel prend le centre le plus proche. Rend, par pixel, son
 * tourbillon et la position dedans. `boucle` : le motif se répète (la peau du fruit posé).
 */
function cellules(centres, N, boucle) {
  const id = new Int16Array(N * N), dx = new Float32Array(N * N), dy = new Float32Array(N * N);
  const decal = boucle ? [-N, 0, N] : [0];
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    let mieux = 1e9;
    const i = y * N + x;
    centres.forEach((c, k) => {
      for (const ox of decal) for (const oy of decal) {
        const ex = x + 0.5 - c.x - ox, ey = y + 0.5 - c.y - oy, d = Math.hypot(ex, ey);
        if (d < mieux) { mieux = d; id[i] = k; dx[i] = ex; dy[i] = ey; }
      }
    });
  }
  return { id, dx, dy };
}

/** La couleur d'un pixel de fruit : le trait entre deux tourbillons, la spirale, ou le blanc bombé (g = ombre). */
function teinte(P, B, centres, N, x, y, g, boucle) {
  const i = y * N + x, c = centres[B.id[i]];
  const dx = B.dx[i], dy = B.dy[i], r = Math.hypot(dx, dy);
  let bord = false;
  for (const [ax, ay] of [[1, 0], [0, 1]]) {
    let nx = x + ax, ny = y + ay;
    if (boucle) { nx %= N; ny %= N; } else if (nx >= N || ny >= N) continue;
    if (B.id[ny * N + nx] !== B.id[i]) bord = true;
  }
  // la spirale : elle part du centre et s'ouvre en un tour et demi jusqu'au bord du tourbillon
  const rmax = 4.5, b = rmax / (TOUR * 1.6);
  const theta = (((Math.atan2(dy, dx) - c.phase) % TOUR) + TOUR) % TOUR;
  let spirale = r < 0.75;
  for (let n = 0; n < 3; n++) {
    const rs = b * (theta + TOUR * n);
    if (rs > 0.9 && rs <= rmax && Math.abs(r - rs) < 0.6) spirale = true;
  }
  if (bord) return g > 0.45 ? P.contour : P.fonce;
  if (spirale) return g > 0.25 ? P.fonce : P.ligne;
  const v = 0.55 * g + 0.5 * ((dy / 4.8) * 0.75 + (dx / 4.8) * 0.35);
  return v < -0.12 ? P.clair : v < 0.34 ? P.milieu : P.ombre;
}

/** L'icône : un fruit rond un peu plus étroit en haut, couvert de tourbillons, et sa tige qui s'enroule en vrille. */
function icone(P, graine) {
  const N = 64;
  const couleur = new Array(N * N).fill(null);
  const sorte = new Uint8Array(N * N); // 0 vide, 1 fruit, 2 tige
  const dans = (x, y) => x >= 0 && x < N && y >= 0 && y < N;

  // la tige : elle monte en ondulant un peu, puis s'enroule vers la droite
  const chemin = [];
  const haut = (y) => 32 - 1.4 * ((24 - y) / 15) ** 1.6;
  for (let y = 24; y >= 9; y -= 0.2) chemin.push([haut(y), y, 1.45 - 0.02 * (24 - y)]);
  for (let A = Math.PI; A <= Math.PI * 3.3; A += 0.03) {
    const t = (A - Math.PI) / (Math.PI * 2.3);
    const r = 5 - 3.9 * t;
    chemin.push([haut(9) + 5 + r * Math.cos(A), 9 + r * Math.sin(A), 1.15 - 0.4 * t]);
  }
  for (const [cx, cy, e] of chemin) {
    for (let y = Math.floor(cy - e - 1); y <= cy + e + 1; y++) for (let x = Math.floor(cx - e - 1); x <= cx + e + 1; x++) {
      if (!dans(x, y)) continue;
      const dx = x + 0.5 - cx, dy = y + 0.5 - cy;
      if (Math.hypot(dx, dy) > Math.max(0.78, e)) continue;
      sorte[y * N + x] = 2;
      const c = -dx * 0.7 - dy * 0.7;
      couleur[y * N + x] = c > 0.45 ? TIGE.clair : c < -0.5 ? TIGE.fonce : TIGE.milieu;
    }
  }

  // le fruit
  const CY = 41, RY = 21.5;
  const rx = (y) => 23 * (y < CY ? 0.84 + 0.16 * Math.sqrt(Math.max(0, (y - (CY - RY)) / RY)) : 1);
  const centres = [];
  for (let r = 0; r < 7; r++) {
    const y = 19.5 + r * 7.8, decal = r % 2 ? 4.5 : 0;
    for (let x = 32 - 5 * 9 + decal; x <= 32 + 5 * 9; x += 9) {
      if (((x - 32) / (rx(y) + 3)) ** 2 + ((y - CY) / (RY + 3)) ** 2 > 1) continue;
      const h = (k) => hache(r, Math.round(x * 10), graine + k);
      centres.push({ x: x + (h(0) - 0.5) * 1.4, y: y + (h(1) - 0.5) * 1.2, phase: h(3) * TOUR });
    }
  }
  const B = cellules(centres, N, false);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const i = y * N + x;
    const e = ((x + 0.5 - 32) / rx(y + 0.5)) ** 2 + ((y + 0.5 - CY) / RY) ** 2;
    if (e > 1) continue;
    sorte[i] = 1;
    const g = ((x - 32) * 0.55 + (y - CY) * 0.85) / 25;
    couleur[i] = teinte(P, B, centres, N, x, y, g, false);
  }

  // le contour, tout autour (du fruit et de la tige)
  const px = Buffer.alloc(N * N * 4);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const i = y * N + x;
    if (!sorte[i]) continue;
    let bordure = false;
    for (const [ax, ay] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) if (!dans(x + ax, y + ay) || !sorte[(y + ay) * N + x + ax]) bordure = true;
    const c = bordure ? (sorte[i] === 2 ? TIGE.contour : P.contour) : couleur[i];
    px.set([...c, 255], i * 4);
  }
  return { w: N, h: N, px };
}

/** La peau du fruit posé (le modèle 3D) : les mêmes tourbillons, en motif qui se répète, sans ombre ni contour. */
function peau(P, graine) {
  const N = 64, centres = [];
  for (let r = 0; r < 8; r++) for (let k = 0; k < 7; k++) {
    const h = (n) => hache(r, k, graine + n);
    centres.push({ x: k * (N / 7) + (r % 2 ? N / 14 : 0) + (h(0) - 0.5) * 1.2, y: r * (N / 8) + (h(1) - 0.5) * 1.0, phase: h(3) * TOUR });
  }
  const B = cellules(centres, N, true);
  const px = Buffer.alloc(N * N * 4);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) px.set([...teinte(P, B, centres, N, x, y, 0, true), 255], (y * N + x) * 4);
  return { w: N, h: N, px };
}

export const FRUITS = {
  glace: { nom: "HIE HIE NO MI", icone: icone(PALETTES.glace, 11), peau: peau(PALETTES.glace, 12), P: PALETTES.glace },
  lave: { nom: "MAGU MAGU NO MI", icone: icone(PALETTES.lave, 23), peau: peau(PALETTES.lave, 24), P: PALETTES.lave },
};

// ------------------------------------------------------------------ le fruit en 3D
const lirePx = (im, x, y) => { const k = (y * im.w + x) * 4; return im.px[k + 3] < 128 ? null : [im.px[k], im.px[k + 1], im.px[k + 2]]; };

/**
 * Un objet tenu ou par terre, comme dans Minecraft : l'icône découpée et épaissie (1/16 de sa taille). Son centre, son
 * axe droite et son axe haut sont dans le repère de T.
 */
function plaque(sc, ic, T, centre, droite, haut, taille) {
  const N = ic.w, n = croix(droite, haut), e = taille / 32;
  const pt = (i, j, k) => T.p(ajoute(centre, ajoute(ajoute(fois(droite, (i / N - 0.5) * taille), fois(haut, (0.5 - j / N) * taille)), fois(n, k * e))));
  const lf = sc.lumiere(T.n(n), true);
  const COTES = [[1, 0, droite], [-1, 0, fois(droite, -1)], [0, 1, fois(haut, -1)], [0, -1, haut]];
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const c = lirePx(ic, i, j);
    if (!c) continue;
    for (const k of [1, -1]) sc.quad([pt(i, j, k), pt(i + 1, j, k), pt(i + 1, j + 1, k), pt(i, j + 1, k)], clair(c, lf), 0.02);
    for (const [ax, ay, d] of COTES) {
      if (i + ax >= 0 && i + ax < N && j + ay >= 0 && j + ay < N && lirePx(ic, i + ax, j + ay)) continue;
      const a = ax > 0 ? i + 1 : ax < 0 ? i : null, b = ay > 0 ? j + 1 : ay < 0 ? j : null;
      const [p0, p1] = a !== null ? [[a, j], [a, j + 1]] : [[i, b], [i + 1, b]];
      sc.quad([pt(...p0, 1), pt(...p1, 1), pt(...p1, -1), pt(...p0, -1)], clair(c, sc.lumiere(T.n(d), true) * 0.82), 0.02);
    }
  }
}

/**
 * Le modèle posé (unités de bloc) : une boule un peu plus étroite en haut, en tranches d'une unité ; chaque tranche fait
 * trois pavés croisés, presque ronde. La tige est fine et finit en vrille.
 */
const MODELE = [];
for (let y = 0; y < 10; y++) {
  const t = (y + 0.5 - 5) / 5.3, r = 5.3 * Math.sqrt(Math.max(0.06, 1 - t * t)) * (t > 0 ? 1 - 0.1 * t : 1);
  const q = (v) => Math.round(v * 4) / 4;
  for (const [a, b] of [[r, 0.45 * r], [0.45 * r, r], [0.8 * r, 0.8 * r]]) MODELE.push([[q(8 - a), y, q(8 - b)], [q(8 + a), y + 1, q(8 + b)]]);
}
const VRILLE = [
  [[7.7, 9.5, 7.7], [8.3, 12.6, 8.3]],
  [[8.3, 12.1, 7.7], [9.7, 12.7, 8.3]],
  [[9.7, 11.3, 7.7], [10.3, 12.7, 8.3]],
  [[9.0, 11.1, 7.7], [9.7, 11.7, 8.3]],
];

/** Un pavé texturé comme dans un modèle de bloc : chaque face prend la texture selon sa position (4 pixels par unité). */
function paveTex(sc, a, b, T, tex) {
  const [x0, y0, z0] = a, [x1, y1, z1] = b;
  const w = x1 - x0, h = y1 - y0, d = z1 - z0;
  const monde = {
    devant: (u, v) => [x0 + u * w, y1 - v * h, z1], dos: (u, v) => [x1 - u * w, y1 - v * h, z0],
    droite: (u, v) => [x0, y1 - v * h, z0 + u * d], gauche: (u, v) => [x1, y1 - v * h, z1 - u * d],
    haut: (u, v) => [x0 + u * w, y1, z0 + v * d], bas: (u, v) => [x0 + u * w, y0, z1 - v * d],
  };
  pave(sc, a, b, T, (f, u, v) => {
    const [x, y, z] = monde[f](u, v);
    const [s, t] = f === "devant" || f === "dos" ? [x, 16 - y] : f === "haut" || f === "bas" ? [x, z] : [z, 16 - y];
    return tex(f, Math.floor(s * 4) & 63, Math.floor(t * 4) & 63);
  }, 0.25);
}

/** Le fruit posé sur un bloc : `base` est le coin du bloc (au-dessus de la table), `ang` le fait tourner sur lui-même. */
function fruitPose(sc, fr, base, ang = 0) {
  const T = { p: (v) => ajoute(rotY(sous(v, [8, 0, 8]), ang), ajoute(base, [8, 0, 8])), n: (v) => norme(rotY(v, ang)) };
  for (const [a, b] of MODELE) paveTex(sc, a, b, T, (f, s, t) => lirePx(fr.peau, s, t));
  for (const [a, b] of VRILLE) paveTex(sc, a, b, T, (f) => (f === "haut" ? TIGE.clair : f === "devant" || f === "gauche" ? TIGE.milieu : TIGE.fonce));
}

/** Une table en chêne : un plateau de planches et quatre pieds, de x0 à x1 et z0 à z1 (le dessus à y = 16). */
function table(sc, x0, z0, x1, z1) {
  const PLANCHE = [168, 134, 82], JOINT = [118, 90, 52], PIED = [102, 78, 46];
  pave(sc, [x0, 13, z0], [x1, 16, z1], ID, (f, u, v, i, j) => {
    if (f === "haut") return (i % 16 === 15 || j % 4 === 3) ? JOINT : clair(PLANCHE, 0.92 + 0.14 * hache(i >> 2, j, 7));
    return clair(PLANCHE, 0.82 + 0.1 * hache(i, j, 8));
  }, 1);
  for (const [x, z] of [[x0 + 1, z0 + 1], [x1 - 3, z0 + 1], [x0 + 1, z1 - 3], [x1 - 3, z1 - 3]]) {
    pave(sc, [x, 0, z], [x + 2, 13, z + 2], ID, (f, u, v, i, j) => clair(PIED, 0.9 + 0.12 * hache(i, j, 9)), 1);
  }
}

/** Tenir l'icône dans une main : la main D (vue de sa droite) ou G (vue de sa gauche). */
function tenir(sc, fr, T, cote, taille = 11, decal = [0, 0, 0]) {
  const x = cote === "D" ? -6 : 6;
  plaque(sc, fr.icone, T, ajoute([x, 10.6, 2.4], decal), [0, 0, cote === "D" ? 1 : -1], [0, 1, 0.25], taille);
}

/** Les miettes quand on mange : des petits carrés pris dans l'icône, qui tombent devant la bouche. */
function miettes(sc, fr, bouche, avant) {
  const ic = fr.icone;
  for (let q = 0; q < 16; q++) {
    let c = null;
    for (let essai = 0; !c && essai < 40; essai++) c = lirePx(ic, 8 + Math.floor(hache(q, essai, 5) * 48), 20 + Math.floor(hache(q, essai, 6) * 40));
    const p = ajoute(bouche, ajoute(fois(avant, 1.5 + 5 * hache(q, 1, 7)), [(hache(q, 2, 7) - 0.5) * 5, -1 - 8 * hache(q, 3, 7), (hache(q, 4, 7) - 0.5) * 3]));
    cube(sc, p, 0.7 + 0.5 * hache(q, 5, 7), [q, q * 0.7], c ?? fr.P.milieu);
  }
}

// ------------------------------------------------------------------ le 2D (inventaire, écran)
const AQUA = [85, 255, 255, 255], OR = [255, 170, 0, 255], GRIS = [170, 170, 170, 255], BLANC_T = [255, 255, 255, 255], JAUNE = [255, 255, 85, 255];

function rect(img, W, x0, y0, x1, y1, c, a = 1) {
  for (let y = Math.max(0, y0); y < y1; y++) for (let x = Math.max(0, x0); x < Math.min(W, x1); x++) {
    const i = (y * W + x) * 4;
    for (let k = 0; k < 3; k++) img[i + k] = Math.round(img[i + k] * (1 - a) + c[k] * a);
  }
}

/** Un texte avec son ombre, comme les textes de Minecraft. */
function texte(img, W, t, x, y, k, c) {
  const o = Math.ceil(k / 2);
  ecrireTexte(img, W, t, x + o, y + o, k, [c[0] * 0.25, c[1] * 0.25, c[2] * 0.25, 255].map(Math.round));
  ecrireTexte(img, W, t, x, y, k, c);
}
const centrer = (W, t, k) => Math.round((W - t.length * 4 * k + k) / 2);

/** Une icône collée à plat, agrandie k fois (ou réduite si k < 1, au plus proche). */
function coller(img, W, ic, x0, y0, taille) {
  for (let y = 0; y < taille; y++) for (let x = 0; x < taille; x++) {
    const c = lirePx(ic, Math.floor((x * ic.w) / taille), Math.floor((y * ic.h) / taille));
    if (c) img.set([...c, 255], ((y0 + y) * W + x0 + x) * 4);
  }
}
const EPEE_IC = { w: 16, h: 16, px: EPEE };

/** Le fond d'un écran de jeu : ciel, collines au loin, herbe. */
function paysage(sc) {
  const { img, w, h } = sc;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const colline = h * 0.52 + 18 * Math.sin(x / 47) + 10 * Math.sin(x / 19 + 1);
    let c;
    if (y < colline) c = mix([120, 168, 255], [196, 220, 255], y / (h * 0.55));
    else if (y < h * 0.6) c = mix([74, 120, 52], [96, 146, 62], hache(x >> 3, y >> 3, 1));
    else c = clair(mix(HERBE, [86, 138, 50], hache(x >> 4, y >> 4, 2)), 0.9 + 0.15 * ((y - h * 0.6) / (h * 0.4)));
    img.set([...c.map(Math.round), 255], (y * w + x) * 4);
  }
}

/** La barre d'objets : n cases de `t` pixels, la case `choisie` en blanc. */
function barre(img, W, x0, y0, t, objets, choisie) {
  rect(img, W, x0, y0, x0 + objets.length * t + t / 10, y0 + t + t / 10, [20, 20, 20], 0.8);
  objets.forEach((ob, k) => {
    const x = x0 + k * t, b = Math.round(t / 20);
    rect(img, W, x + b, y0 + b, x + t - b, y0 + t - b, [110, 110, 110], 0.9);
    rect(img, W, x + 2 * b, y0 + 2 * b, x + t - 2 * b, y0 + t - 2 * b, [60, 60, 60], 0.85);
    if (ob) coller(img, W, ob, x + Math.round(t * 0.1), y0 + Math.round(t * 0.1), Math.round(t * 0.8));
  });
  const x = x0 + choisie * t, b = Math.max(2, Math.round(t / 20));
  for (const [a, c, d, e] of [[x - b, y0 - b, x + t + b, y0], [x - b, y0 + t, x + t + b, y0 + t + b], [x - b, y0, x, y0 + t], [x + t, y0, x + t + b, y0 + t]]) rect(img, W, a, c, d, e, [236, 236, 236]);
}

/** Une bulle d'info d'objet (le violet de Minecraft). */
function bulle(img, W, x0, y0, lignes) {
  const w = Math.max(...lignes.map(([t, , k]) => t.length * 4 * k)) + 24, h = lignes.reduce((s, [, , k]) => s + 6 * k + 8, 16);
  rect(img, W, x0, y0, x0 + w, y0 + h, [16, 0, 16], 0.94);
  for (let y = y0; y < y0 + h; y++) {
    const c = mix([80, 0, 255], [40, 0, 127], (y - y0) / h);
    rect(img, W, x0 + 3, y, x0 + 6, y + 1, c); rect(img, W, x0 + w - 6, y, x0 + w - 3, y + 1, c);
  }
  rect(img, W, x0 + 3, y0 + 3, x0 + w - 3, y0 + 6, [80, 0, 255]); rect(img, W, x0 + 3, y0 + h - 6, x0 + w - 3, y0 + h - 3, [40, 0, 127]);
  let y = y0 + 12;
  for (const [t, c, k] of lignes) { texte(img, W, t, x0 + 12, y, k, c); y += 6 * k + 8; }
}

// ------------------------------------------------------------------ les vignettes
const { glace: HIE, lave: MAGU } = FRUITS;

function grandeIcone(fr) {
  return (sc) => {
    coller(sc.img, sc.w, fr.icone, 60, 64, 320);
    legende(sc, fr === HIE ? "64X64, TOURBILLONS BLANCS ET BLEUS" : "64X64, TOURBILLONS ROUGES ET ORANGE");
  };
}

function inventaire(sc) {
  paysage(sc);
  const { img, w } = sc;
  bulle(img, w, 22, 24, [[HIE.nom, AQUA, 4], ["FRUIT DU DEMON : LA GLACE", GRIS, 3], ["CLIC DROIT : LE MANGER", GRIS, 3], ["SHIFT + CLIC DROIT : LE POSER", GRIS, 3]]);
  bulle(img, w, 22, 196, [[MAGU.nom, OR, 4], ["FRUIT DU DEMON : LA LAVE", GRIS, 3], ["CLIC DROIT : LE MANGER", GRIS, 3], ["SHIFT + CLIC DROIT : LE POSER", GRIS, 3]]);
  barre(img, w, 20, 382, 80, [EPEE_IC, HIE.icone, MAGU.icone, null, null], 1);
  legende(sc, "EN 1080P : 64X64, PIXEL POUR PIXEL");
}

function enMain(sc) {
  sol(sc, -40, -20, 40, 20);
  const A = perso(sc, STEVE, { decale: [-13, 0, 0], lacet: 0.62, brasD: [-0.6, 0.05], brasG: [0.1, 0.05] });
  tenir(sc, HIE, A.brasD, "D");
  const B = perso(sc, SKINS.steve, { decale: [13, 0, 0], lacet: -0.62, brasG: [-0.6, -0.05], brasD: [0.1, -0.05] });
  tenir(sc, MAGU, B.brasG, "G");
  legende(sc, "COMME UNE POMME : DANS LA MAIN");
}

function poseProche(sc) {
  table(sc, -24, -12, 24, 12);
  fruitPose(sc, HIE, [-17, 16, -8], 0.2);
  fruitPose(sc, MAGU, [1, 16, -8], -0.25);
  legende(sc, "SHIFT + CLIC DROIT : POSE EN 3D");
}

function devantLesGens(sc) {
  sol(sc, -56, -40, 56, 40);
  table(sc, -24, -6, 24, 10);
  fruitPose(sc, HIE, [-18, 16, -6], 0.15);
  fruitPose(sc, MAGU, [2, 16, -6], -0.2);
  perso(sc, STEVE, { decale: [-30, 0, -26], lacet: 0.35, brasD: [-0.2, -0.05], tete: [0.35] });
  perso(sc, SKINS.efe, { decale: [0, 0, -30], lacet: 0, brasD: [-0.1, -0.05], brasG: [-0.1, 0.05], tete: [0.4] });
  perso(sc, SKINS.kai, { decale: [30, 0, -26], lacet: -0.35, brasG: [-0.2, 0.05], tete: [0.35] });
  legende(sc, "CLIC DROIT DESSUS : ON LE REPREND");
}

function mange(fr) {
  return (sc) => {
    sol(sc, -30, -20, 30, 30);
    const glace = fr === HIE, cote = glace ? "D" : "G";
    const bras = [-1.25, glace ? 0.5 : -0.5];
    const T = perso(sc, glace ? PEAU_GLACE : PEAU_LAVE, { ["bras" + cote]: bras, tete: [0.1, 0, glace ? 0.15 : -0.15] });
    tenir(sc, fr, T["bras" + cote], cote, 9, [0, 0, 1.5]);
    miettes(sc, fr, [glace ? -1 : 1, 25, 6], [0, -0.3, 1]);
    legende(sc, "CLIC DROIT MAINTENU : IL LE MANGE");
  };
}

/** Le pouvoir qui entre : il s'élève un peu, une spirale monte autour de lui, sa main change un instant. */
function pouvoir(fr) {
  return (sc) => {
    const glace = fr === HIE;
    sol(sc, -40, -40, 40, 40, (x, z) => {
      const r = Math.hypot(x, z);
      if (r > 13 + 2 * Math.sin(Math.atan2(z, x) * 5)) return null;
      return glace ? mix(GLACE_C, BLANC, 0.3 + 0.5 * hache(Math.floor(x), Math.floor(z), 3)) : mix([60, 40, 30], [110, 50, 30], hache(Math.floor(x), Math.floor(z), 3));
    });
    const haut = [0, 5, 0];
    perso(sc, glace ? PEAU_GLACE : PEAU_LAVE, {
      decale: haut, tete: [-0.45], brasD: [-0.25, -0.55], brasG: [-0.25, 0.55], jambeD: 0.12, jambeG: -0.08,
      main: glace ? GEL({ givre: false }) : LAVE({ gouttes: false, flammes: 7 }),
    });
    // la spirale qui monte, et un cercle au sol
    for (let q = 0; q < 76; q++) {
      const a = (q >> 1) * 0.45 + (q % 2) * Math.PI, y = 1 + (q >> 1) * 0.95, r = 12.5 - (q >> 1) * 0.16;
      const p = [Math.cos(a) * r, y, Math.sin(a) * r];
      if (glace) cube(sc, p, 0.9 + 0.5 * hache(q, 1, 3), [q, q * 0.6], q % 3 ? BLANC : GLACE_C, true);
      else cube(sc, p, 0.9 + 0.5 * hache(q, 1, 3), [q, q * 0.6], brille(q % 3 ? LAVE_O : LAVE_J), true);
    }
    for (let q = 0; q < 12; q++) {
      const a = (q / 12) * TOUR, p = [Math.cos(a) * 14, 0, Math.sin(a) * 14];
      if (glace) cristal(sc, p, [Math.cos(a) * 0.4, 1, Math.sin(a) * 0.4], 3 + 3 * hache(q, 2, 4), 1.6);
      else flamme(sc, p, [0, 1, 0], 3 + 3.5 * hache(q, 2, 4), 1.8);
    }
    sc.lueur(glace ? GLACE : LAVE_O, 5, 1.3);
    legende(sc, glace ? "3 S : LE GIVRE MONTE EN LUI" : "3 S : LA LAVE MONTE EN LUI");
  };
}

/** Ce qu'il voit pendant l'animation : un bord de givre (ou de chaleur), le nom du fruit, et le message. */
function ecran(fr) {
  return (sc) => {
    const glace = fr === HIE, { img, w, h } = sc;
    paysage(sc);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const bruit = hache(x >> 3, y >> 3, glace ? 31 : 32);
      const d = Math.min(x, w - 1 - x, y, h - 1 - y) / (70 + 40 * bruit);
      if (d >= 1) continue;
      const a = (1 - d) ** 1.8;
      const c = glace ? mix(GLACE_C, BLANC, bruit) : mix(LAVE_R, LAVE_O, bruit);
      rect(img, w, x, y, x + 1, y + 1, c, glace ? a * 0.9 : a * 0.7);
    }
    if (!glace) for (let q = 0; q < 26; q++) {
      const x = Math.floor(hache(q, 1, 33) * w), y = Math.floor(h * (0.35 + 0.6 * hache(q, 2, 33)));
      rect(img, w, x, y, x + 4, y + 4, q % 3 ? LAVE_O : LAVE_J);
    }
    texte(img, w, fr.nom, centrer(w, fr.nom, glace ? 7 : 6), 140, glace ? 7 : 6, glace ? AQUA : OR);
    const sous_ = glace ? "LA GLACE ENTRE EN TOI" : "LA LAVE ENTRE EN TOI";
    texte(img, w, sous_, centrer(w, sous_, 3), 200, 3, BLANC_T);
    rect(img, w, 14, 372, 426, 412, [0, 0, 0], 0.5);
    texte(img, w, "TU AS MANGE LE " + fr.nom, 20, 378, 2, BLANC_T);
    texte(img, w, glace ? "TU ES SUR LA WHITELIST GLACE" : "TU ES SUR LA WHITELIST LAVE", 20, 396, 2, JAUNE);
    barre(img, w, 40, 424, 40, [EPEE_IC, null, null, null, null, null, null, null, null], 1);
    legende(sc, "TITRE, MESSAGE ET BORD " + (glace ? "GIVRE" : "BRULANT") + " 3 S");
  };
}

// ------------------------------------------------------------------ la planche
const CW = 440, CH = 520, M = 34, HAUT = 470;
const NOIR = [30, 30, 40, 255], ORANGE = [214, 92, 20, 255], BLEU = [40, 110, 200, 255];

function entete(img, W) {
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242]);
  ecrireTexte(img, W, "FRUITS DU DEMON : LES FRUITS", 30, 22, 5, NOIR);
  const colonnes = [
    [30, BLEU, "HIE HIE NO MI : LA GLACE (AOKIJI)", [
      "TOURBILLONS BLANCS ET BLEUS, TIGE VERTE EN VRILLE",
      "LE MANGER : WHITELIST GLACE, TOUT SEUL",
      "ANIMATION : LE GIVRE MONTE EN SPIRALE AUTOUR DE",
      "    LUI ET SA MAIN DROITE GELE UN INSTANT",
    ]],
    [900, ORANGE, "MAGU MAGU NO MI : LA LAVE (AKAINU)", [
      "MEME FORME, TOURBILLONS ROUGES ET ORANGE",
      "LE MANGER : WHITELIST LAVE, TOUT SEUL",
      "ANIMATION : DES BRAISES MONTENT EN SPIRALE AUTOUR",
      "    DE LUI ET SA MAIN GAUCHE BRULE UN INSTANT",
    ]],
  ];
  for (const [x, c, titre, lignes] of colonnes) {
    ecrireTexte(img, W, titre, x, 100, 4, c);
    lignes.forEach((t, k) => ecrireTexte(img, W, t, x, 160 + k * 34, 3, NOIR));
  }
  rect(img, W, 30, 310, W - 30, 313, [200, 200, 210]);
  [
    "INTROUVABLE : AUCUN COFFRE, AUCUNE RECETTE, AUCUN MARCHAND. SEUL L'HOTE LES DONNE",
    "SHIFT + CLIC DROIT SUR UN BLOC : IL SE POSE EN 3D (SUR UNE TABLE). CLIC DROIT DESSUS : ON LE REPREND",
    "CLIC DROIT MAINTENU : ON LE MANGE. 3 S : IL S'ELEVE UN PEU, LE POUVOIR MONTE, PUIS IL EST SUR LA WHITELIST",
  ].forEach((t, k) => ecrireTexte(img, W, t, 30, 332 + k * 34, 3, NOIR));
}

const vues = [
  [HIE.nom, grandeIcone(HIE), {}],
  [MAGU.nom, grandeIcone(MAGU), {}],
  ["DANS L'INVENTAIRE", inventaire, {}],
  ["EN MAIN", enMain, { lacet: 0, tangage: 10, echelle: 7.6, pied: 330, centre: [0, 14, 0] }],
  ["POSE SUR UNE TABLE", poseProche, { lacet: -24, tangage: 24, echelle: 12.5, pied: 290, centre: [0, 20, 0] }],
  ["DEVANT LES GENS", devantLesGens, { lacet: -18, tangage: 22, echelle: 4.6, pied: 360, centre: [0, 10, -10] }],
  ["ON LE MANGE : GLACE", mange(HIE), { lacet: 58, tangage: 8, echelle: 12, pied: 300, centre: [0, 21, 3] }],
  ["ON LE MANGE : LAVE", mange(MAGU), { lacet: -58, tangage: 8, echelle: 12, pied: 300, centre: [0, 21, 3] }],
  ["LE POUVOIR ENTRE : GLACE", pouvoir(HIE), { lacet: 28, tangage: 16, echelle: 6.4, pied: 300, centre: [0, 14, 0] }],
  ["LE POUVOIR ENTRE : LAVE", pouvoir(MAGU), { lacet: -28, tangage: 16, echelle: 6.4, pied: 300, centre: [0, 14, 0] }],
  ["A L'ECRAN : GLACE", ecran(HIE), {}],
  ["A L'ECRAN : LAVE", ecran(MAGU), {}],
];

// ------------------------------------------------------------------ les gants (la main du fruit, en jeu)
/**
 * Les textures des gants : 128×128 pour une taille déclarée de 32×32 dans le mod (quatre pixels par pixel du modèle).
 * Tout est en motif, sauf la bande des veines (y de 80 à 112), qui ne garde que ses traits. La lave a une seconde
 * texture, ses fissures seules, qui brillent dans le noir.
 */
function gants() {
  const N = 128, veine = (y) => y >= 80 && y < 112;
  const lave = Buffer.alloc(N * N * 4), lueur = Buffer.alloc(N * N * 4), glace = Buffer.alloc(N * N * 4);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const i = (y * N + x) * 4;
    // la lave : des plaques de croûte noire, des fissures orange et jaunes (fines, deux pixels du modèle de large)
    const f = fissure(x, y, 140, 9);
    const fente = f < 1.6, coeur = f < 0.8;
    const croute = mix(CROUTE, CROUTE_R, 0.2 + 0.5 * hache(x >> 2, y >> 2, 141));
    if (veine(y)) {
      // les veines : des traits de lave qui remontent le bras, de moins en moins vers le haut (y = 80)
      const garde = fente && hache(x >> 2, y >> 2, 142) < (y - 76) / 36;
      if (garde) {
        lave.set([...mix(LAVE_O, LAVE_J, coeur ? 0.6 : 0).map(Math.round), 255], i);
        lueur.set([...mix(LAVE_O, LAVE_J, coeur ? 0.6 : 0).map(Math.round), 255], i);
      }
    } else if (fente) {
      const c = coeur ? mix(LAVE_J, LAVE_O, f / 0.8) : mix(LAVE_O, LAVE_R, (f - 0.8) / 0.8);
      lave.set([...c.map(Math.round), 255], i);
      lueur.set([...c.map(Math.round), 255], i);
    } else {
      lave.set([...croute.map(Math.round), 255], i);
    }
    // la glace : bleu clair, plus blanche en haut de chaque face, des reflets en biais ; les éclats plus clairs
    if (veine(y)) {
      if (hache(x >> 2, y >> 2, 143) < (y - 78) / 40) glace.set([...mix(GLACE_C, BLANC, hache(x, y, 144)).map(Math.round), 210], i);
    } else {
      const reflet = ((x >> 2) - (y >> 2) + 64) % 7;
      const eclat = x >= 96 && y < 20;
      const c = reflet === 0 ? BLANC : reflet === 1 ? mix(GLACE_C, BLANC, 0.6) : mix(mix(GLACE_C, GLACE, ((y >> 2) % 5) / 5), BLANC, eclat ? 0.45 : 0.3 * hache(x >> 3, y >> 3, 145));
      glace.set([...c.map(Math.round), 255], i);
    }
  }
  return { lave, lueur, glace };
}

/** Une petite texture de tige (16×16) pour la vrille du fruit posé : vert, plus clair d'un côté. */
function tige() {
  const px = Buffer.alloc(16 * 16 * 4);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const c = x % 4 === 0 ? TIGE.fonce : x % 4 === 1 ? TIGE.clair : TIGE.milieu;
    px.set([...c, 255], (y * 16 + x) * 4);
  }
  return px;
}

/** Les fichiers du mod : les icônes, le fruit posé (modèle 3D, états, butin), les gants. */
function fichiersDuMod() {
  const RES = path.join(DOC, "..", "..", "mod-cabane", "src", "main", "resources");
  const ASSETS = path.join(RES, "assets", "cabane"), DATA = path.join(RES, "data", "cabane");
  const json = (fichier, contenu) => { fs.mkdirSync(path.dirname(fichier), { recursive: true }); fs.writeFileSync(fichier, JSON.stringify(contenu, null, 2) + "\n"); };
  const png = (fichier, w, h, px) => { fs.mkdirSync(path.dirname(fichier), { recursive: true }); ecrirePng(fichier, w, h, px); };
  const arrondi = (v) => Math.round(v * 1000) / 1000;
  for (const [id, fr] of [["hie_hie_no_mi", HIE], ["magu_magu_no_mi", MAGU]]) {
    png(path.join(ASSETS, "textures", "item", id + ".png"), 64, 64, fr.icone.px);
    png(path.join(ASSETS, "textures", "block", id + ".png"), 64, 64, fr.peau.px);
    json(path.join(ASSETS, "models", "item", id + ".json"), { parent: "minecraft:item/generated", textures: { layer0: "cabane:item/" + id } });
    const faces = (t) => Object.fromEntries(["north", "south", "east", "west", "up", "down"].map((f) => [f, { texture: t }]));
    json(path.join(ASSETS, "models", "block", id + ".json"), {
      textures: { peau: "cabane:block/" + id, tige: "cabane:block/fruit_tige", particle: "cabane:block/" + id },
      elements: [
        ...MODELE.map(([a, b]) => ({ from: a.map(arrondi), to: b.map(arrondi), faces: faces("#peau") })),
        ...VRILLE.map(([a, b]) => ({ from: a.map(arrondi), to: b.map(arrondi), faces: faces("#tige") })),
      ],
    });
    json(path.join(ASSETS, "blockstates", id + ".json"), { variants: Object.fromEntries(
      [["north", 0], ["east", 90], ["south", 180], ["west", 270]].map(([f, y]) => ["facing=" + f, y ? { model: "cabane:block/" + id, y } : { model: "cabane:block/" + id }])) });
    json(path.join(DATA, "loot_table", "blocks", id + ".json"), { type: "minecraft:block", pools: [{ rolls: 1,
      entries: [{ type: "minecraft:item", name: "cabane:" + id }], conditions: [{ condition: "minecraft:survives_explosion" }] }] });
  }
  png(path.join(ASSETS, "textures", "block", "fruit_tige.png"), 16, 16, tige());
  const g = gants();
  png(path.join(ASSETS, "textures", "entity", "fruit", "gant_lave.png"), 128, 128, g.lave);
  png(path.join(ASSETS, "textures", "entity", "fruit", "gant_lave_lueur.png"), 128, 128, g.lueur);
  png(path.join(ASSETS, "textures", "entity", "fruit", "gant_glace.png"), 128, 128, g.glace);
  for (const [id, msg] of [["magma", "cabane.magma"], ["givre", "cabane.givre"], ["eau", "cabane.eau"]]) {
    json(path.join(DATA, "damage_type", id + ".json"), { exhaustion: 0.1, message_id: msg, scaling: "never" });
  }
}

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  fichiersDuMod();
  ecrirePng(path.join(DOC, "hie_hie_no_mi.png"), 64, 64, HIE.icone.px);
  ecrirePng(path.join(DOC, "magu_magu_no_mi.png"), 64, 64, MAGU.icone.px);
  const COLONNES = 4, lignes = Math.ceil(vues.length / COLONNES);
  const W = CW * COLONNES, H = HAUT + (M + CH) * lignes;
  const img = Buffer.alloc(W * H * 4, 255);
  entete(img, W);
  vues.forEach(([titre, dessin, o], k) => {
    const c = k % COLONNES, l = Math.floor(k / COLONNES);
    const sc = scene({ w: CW, h: CH, fond: FOND, ...o });
    dessin(sc);
    for (let y = 0; y < CH; y++) sc.img.copy(img, ((HAUT + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(img, W, titre, c * CW + 12, HAUT + l * (M + CH) + 10, 3, titre.includes("GLACE") || titre === HIE.nom ? BLEU : titre.includes("LAVE") || titre === MAGU.nom ? ORANGE : NOIR);
  });
  ecrirePng(path.join(DOC, "planche-objets.png"), W, H, img);
  console.log("ok", W, H);
}
