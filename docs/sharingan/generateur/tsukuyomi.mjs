// Le genjutsu (demandé le 2026-11-04, sur le modèle du Tsukuyomi d'Itachi) : lui et sa cible voient l'animation des yeux,
// puis tous les deux sont téléportés dans le genjutsu pendant 30 s. Le monde y est en négatif : ciel rouge sang, lune noire
// cerclée de rouge, sol noir quadrillé de rouge, brume rouge. La cible est attachée à une croix de bois ; lui est debout
// devant elle ; ses clones sortent du sol un à un, tout autour, un tantō à la main, et le pointent sur elle. À la fin,
// l'illusion se fissure comme du verre, éclair blanc, et on revient dans le monde.
// node tsukuyomi.mjs video <dossier> [de] [pas] ; node tsukuyomi.mjs image <t> [fichier.png] ; node tsukuyomi.mjs → planche.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, ecrireTexte, mix, hache, norme, scene, clair } from "../../fruits/generateur/moteur.mjs";
import { ajoute, fois, sous, TOUR, ID, brille, pave, cube, perso, SKINS, STEVE } from "../../fruits/generateur/rendu.mjs";
import { image as imageYeux } from "./yeux.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const borne = (u) => Math.max(0, Math.min(1, u));
const entre = (t, a, b) => borne((t - a) / (b - a));
const sortie = (u) => 1 - (1 - borne(u)) ** 3;
const lisse = (u) => { u = borne(u); return u * u * (3 - 2 * u); };

const ROUGE_CIEL = [176, 14, 22], NOIR = [10, 4, 6], LIGNE = [96, 6, 12], BOIS = [70, 42, 30], CORDE = [150, 120, 80], ACIER = [190, 190, 200];
const OEIL = [255, 40, 40];
const PRELUDE = 1.5, DUREE = PRELUDE + 11;   // les yeux, puis le genjutsu (30 s en jeu, raccourci ici à 11 s)

// ------------------------------------------------------------------ le décor
function sol(sc) {
  // près : des cases d'un bloc, une ligne rouge tous les 4 blocs ; loin : de grandes cases noires
  pave(sc, [-1024, -40, -1024], [1024, 0, 1024], ID, (f, u, v, i, j) => {
    if (f !== "haut") return null;
    const ligne = i % 6 === 0 || j % 6 === 0;
    return ligne ? clair(LIGNE, 0.8 + 0.2 * hache(i, j, 7)) : clair(NOIR, 0.8 + 0.4 * hache(i, j, 8));
  }, 16);
  for (const [x0, z0, x1, z1] of [[-9000, -9000, -1024, 9000], [1024, -9000, 9000, 9000], [-1024, 1024, 1024, 9000], [-1024, -9000, 1024, -1024]])
    pave(sc, [x0, -40, z0], [x1, 0, z1], ID, (f, u, v, i, j) => (f === "haut" ? (i % 6 === 0 || j % 6 === 0 ? clair(LIGNE, 0.5) : NOIR) : null), 64, -1e6);
}

/** La lune noire, cerclée de rouge, très loin derrière la croix (des anneaux de quadrilatères pleins). */
function lune(sc, cam) {
  // elle reste toujours derrière la croix, vue d ici : un rêve, elle suit le regard
  const d = norme([-cam[0], 0, -cam[2]]);
  const c = [d[0] * 7000 + d[2] * 1400, 2300, d[2] * 7000 - d[0] * 1400], R = 1500;
  const n = norme(sous(cam, c));
  const e1 = norme([n[2], 0, -n[0]]), e2 = [n[1] * e1[2] - n[2] * e1[1], n[2] * e1[0] - n[0] * e1[2], n[0] * e1[1] - n[1] * e1[0]];
  const p = (a, r) => ajoute(c, ajoute(fois(e1, Math.cos(a) * r), fois(e2, Math.sin(a) * r)));
  for (let q = 0; q < 48; q++) {
    const a0 = (q / 48) * TOUR, a1 = ((q + 1) / 48) * TOUR;
    sc.quad([p(a0, 0), p(a1, 0), p(a1, R * 0.92), p(a0, R * 0.92)], [6, 2, 4], -5e5);
    sc.quad([p(a0, R * 0.92), p(a1, R * 0.92), p(a1, R), p(a0, R)], [255, 70, 60], -5e5, true);
  }
}

/** La croix de bois : un poteau de 6 blocs et une traverse ; la cible y est attachée, les bras en croix. */
function croix(sc, t) {
  pave(sc, [-4, 0, -9], [4, 104, -3], ID, (f, u, v, i, j) => clair(BOIS, 0.8 + 0.3 * hache(i, j, 20)), 2);
  pave(sc, [-46, 44, -9], [46, 52, -3], ID, (f, u, v, i, j) => clair(BOIS, 0.8 + 0.3 * hache(i, j, 21)), 2);
  // elle se débat un peu
  const deb = 0.06 * Math.sin(t * 9) * (1 - entre(t, 9, 10));
  const T = perso(sc, STEVE, { decale: [0, 26, -1], brasD: [0, -1.57 + deb, 0], brasG: [0, 1.57 - deb, 0], tete: [0.25 + 0.1 * Math.sin(t * 3), 0.1 * Math.sin(t * 2), 0] });
  // les cordes : aux poignets, à la taille, aux chevilles
  for (const [x, y] of [[-17, 48], [-11, 48], [11, 48], [17, 48]]) cube(sc, [x, y, -1], 4.6, [0, 0], CORDE);
  pave(sc, [-4.6, 37, -3.4], [4.6, 39, 2.6], ID, () => CORDE, 2);
  pave(sc, [-4.6, 29, -3.4], [4.6, 31, 2.6], ID, () => CORDE, 2);
  return T;
}

/** Un tantō dans la main droite d'un joueur (T), la lame dans le prolongement du bras. */
function tanto(sc, T) {
  for (let q = 0; q < 9; q++) {
    const p = T.brasD.p([-6, 11 - q * 1.6, 0]);
    cube(sc, p, q < 2 ? 1.6 : 1.1, [0, 0], q < 2 ? [40, 30, 30] : ACIER, false);
  }
}

/** Ses yeux qui brillent : Mangekyo (les deux, rouge) dans le genjutsu. */
function yeuxRouges(sc, T) {
  for (const x0 of [-2, 1]) sc.quad([[x0, 28, 4.06], [x0 + 1, 28, 4.06], [x0 + 1, 27, 4.06], [x0, 27, 4.06]].map(T.tete.p), OEIL, 0.3, true);
}

const NCLONES = 12;
/** Le lanceur devant la croix, et ses clones qui sortent du sol tout autour, un à un, puis lèvent leur tantō. */
function lanceurs(sc, t, cam) {
  const L = perso(sc, SKINS.kai, { decale: [0, 0, 150], lacet: Math.PI, brasD: [-0.25, 0, 0], brasG: [0.1, 0, 0] });
  yeuxRouges(sc, L);
  for (let k = 0; k < NCLONES; k++) {
    const t0 = 3.0 + k * 0.22;
    if (t < t0) continue;
    const a = (k / NCLONES) * TOUR + 0.26, r = 120;
    const p = [Math.sin(a) * r, 0, Math.cos(a) * r - 10];
    // un clone collé à la caméra cacherait tout
    if (Math.hypot(p[0] - cam[0], p[2] - cam[2]) < 45) continue;
    const monte = sortie(entre(t, t0, t0 + 0.5));
    const leve = sortie(entre(t, 6.0 + k * 0.05, 6.5 + k * 0.05));
    const frappe = Math.max(0, Math.sin(Math.PI * entre(t, 8.0, 8.5)));
    const lac = Math.atan2(-p[0], -10 - p[2]);
    const T = perso(sc, SKINS.kai, { decale: [p[0], -32 * (1 - monte), p[2]], lacet: lac, brasD: [-1.5 * leve - 0.6 * frappe, 0, 0], brasG: [0.1, 0, 0], penche: 0.15 * frappe });
    if (monte > 0.4) { tanto(sc, T); yeuxRouges(sc, T); }
    // la fumée noire d'où il sort
    if (monte < 1) for (let q = 0; q < 8; q++) {
      const b = q * 0.8;
      cube(sc, [p[0] + Math.cos(b) * 10, 4 + q * 3 * monte, p[2] + Math.sin(b) * 10], 6 * (1 - monte) + 1, [q, q], [20, 6, 10]);
    }
  }
}

/** La brume rouge qui flotte au ras du sol. */
function brume(sc, t) {
  for (let q = 0; q < 26; q++) {
    const a = q * 2.4 + t * 0.08, r = 300 + 900 * hache(q, 1, 30);
    const c = [Math.cos(a) * r, 12 + 20 * hache(q, 2, 30), Math.sin(a) * r];
    const n = [0, 1, 0], l = 140 + 120 * hache(q, 3, 30);
    sc.voile([ajoute(c, [-l, 0, -l]), ajoute(c, [l, 0, -l]), ajoute(c, [l, 0, l]), ajoute(c, [-l, 0, l])], [200, 20, 30], 0.12);
  }
}

// ------------------------------------------------------------------ la caméra
function depuis(pos, lacet, tangage, recul) {
  const a = (lacet * Math.PI) / 180, b = (tangage * Math.PI) / 180;
  return sous(pos, fois([-Math.sin(a) * Math.cos(b), Math.sin(b), Math.cos(a) * Math.cos(b)], recul));
}
function vise(pos, c) {
  const d = norme(sous(c, pos));
  return [(Math.atan2(d[0], -d[2]) * 180) / Math.PI, (-Math.asin(d[1]) * 180) / Math.PI];
}
function cles(t, l) {
  if (t <= l[0][0]) return l[0][1];
  for (let i = 1; i < l.length; i++) {
    if (t > l[i][0]) continue;
    const u = (t - l[i - 1][0]) / (l[i][0] - l[i - 1][0]);
    return l[i - 1][1] + (l[i][1] - l[i - 1][1]) * u * u * (3 - 2 * u);
  }
  return l[l.length - 1][1];
}
/** Elle arrive par-dessus l'épaule du lanceur, tourne autour de la croix, descend pendant que les clones sortent, puis serre sur la cible. */
function camera(t, k = 1) {
  const ang = cles(t, [[0, -0.15], [3, 0.35], [6, 1.1], [8.2, 0.55], [11, 0.25]]);
  const d = cles(t, [[0, 190], [3, 210], [6, 250], [8.2, 150], [11, 105]]);
  const h = cles(t, [[0, 46], [3, 70], [6, 52], [8.2, 36], [11, 44]]);
  const pos = [Math.sin(ang) * d, h, Math.cos(ang) * d];
  const cible = [0, cles(t, [[0, 46], [6, 40], [11, 48]]), 0];
  const [lacet, tangage] = vise(pos, cible);
  return { lacet, tangage, echelle: cles(t, [[0, 1.0], [6, 0.95], [11, 1.15]]) * k, recul: 420, pied: 270 * k, centre: depuis(pos, lacet, tangage, 420), fond: ROUGE_CIEL, pos };
}

// ------------------------------------------------------------------ une image
function fissures(img, w, h, u) {
  // des fissures blanches qui partent du centre et se ramifient (le genjutsu se brise comme du verre)
  for (let q = 0; q < 14; q++) {
    let x = w / 2, y = h / 2, a = (q / 14) * TOUR + hache(q, 1, 40);
    const n = Math.floor(190 * u);
    for (let s = 0; s < n; s++) {
      a += (hache(q, s, 41) - 0.5) * (s % 12 === 0 ? 1.2 : 0.12);
      x += Math.cos(a) * 2.5; y += Math.sin(a) * 2.5;
      for (let e = -1; e <= 1; e++) for (let f = -1; f <= 1; f++) {
        const px = Math.round(x) + e, py = Math.round(y) + f;
        if (px >= 0 && py >= 0 && px < w && py < h) img.set([255, 240, 240], (py * w + px) * 4);
      }
    }
  }
}

export function image(T, w = 960, h = 540) {
  // le prélude : les yeux (le Mangekyo qui s'allume), vus par les deux
  if (T < PRELUDE) {
    const img = imageYeux(2, 1.7 + T * 1.0);
    const fl = entre(T, PRELUDE - 0.25, PRELUDE);
    if (fl > 0) for (let i = 0; i < img.length; i += 4) for (let c = 0; c < 3; c++) img[i + c] = img[i + c] + ([255, 60, 60][c] - img[i + c]) * fl;
    return { img, w: 960, h: 540 };
  }
  const t = T - PRELUDE;
  const cam = camera(t, w / 960);
  const sc = scene({ w, h, ...cam });
  lune(sc, cam.pos);
  sol(sc);
  croix(sc, t);
  lanceurs(sc, t, cam.pos);
  brume(sc, t);
  sc.lueur(OEIL, 6, 1.2);
  // le haut du ciel s'assombrit, la scène est teintée de rouge
  for (let y = 0; y < h; y++) {
    const k = 0.35 + 0.65 * Math.min(1, y / (h * 0.55));
    for (let x = 0; x < w; x++) { const i = (y * w + x) * 4; for (let c = 0; c < 3; c++) sc.img[i + c] *= k; }
  }
  // l'arrivée : un éclair rouge qui s'éteint ; la fin : les fissures, puis l'éclair blanc
  const arr = 1 - entre(t, 0, 0.6), fin = entre(t, 9.6, 10.6), blanc = entre(t, 10.3, 10.6) * (1 - entre(t, 10.7, 11));
  for (let i = 0; i < w * h; i++) for (let c = 0; c < 3; c++) {
    let v = sc.img[i * 4 + c];
    v += ([255, 50, 50][c] - v) * arr * 0.9;
    v += (255 - v) * blanc;
    sc.img[i * 4 + c] = v;
  }
  if (fin > 0 && blanc < 0.5) fissures(sc.img, w, h, fin);
  // le compte à rebours (30 s en jeu)
  const reste = Math.max(0, Math.ceil(30 * (1 - entre(t, 0.5, 10.4))));
  if (w >= 900) ecrireTexte(sc.img, w, "GENJUTSU : " + reste + " S", 26, 24, 4, [255, 200, 200, 255]);
  return sc;
}

// ------------------------------------------------------------------ la planche
const ETAPES = [
  [0.9, "1. IL CROISE SON REGARD : LES YEUX"], [PRELUDE + 1.2, "2. TELEPORTES TOUS LES DEUX DANS LE GENJUTSU"], [PRELUDE + 2.6, "3. ELLE EST ATTACHEE A LA CROIX"],
  [PRELUDE + 4.4, "4. SES CLONES SORTENT DU SOL"], [PRELUDE + 7.2, "5. ILS LEVENT LEUR TANTO"], [PRELUDE + 10.1, "6. 30 S PLUS TARD : L'ILLUSION SE BRISE"],
];
function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}
function planche() {
  const VW = 640, VH = 360, M = 34, HAUT = 120, W = VW * 3, H = HAUT + 2 * (M + VH);
  const img = Buffer.alloc(W * H * 4, 255);
  rect(img, W, 0, 0, W, H, [20, 6, 10, 255]);
  ecrireTexte(img, W, "LE GENJUTSU : TSUKUYOMI", 30, 24, 6, [255, 90, 90, 255]);
  ecrireTexte(img, W, "LES YEUX POUR LES DEUX, PUIS TELEPORTES 30 S DANS UN MONDE EN NEGATIF, ET RETOUR", 30, 78, 3, [220, 200, 200, 255]);
  ETAPES.forEach(([t, titre], k) => {
    const sc = image(t, VW, VH);
    let src = sc.img;
    if (sc.w !== VW) {
      // le prélude est rendu en 960 × 540 : on le réduit
      src = Buffer.alloc(VW * VH * 4);
      for (let y = 0; y < VH; y++) for (let x = 0; x < VW; x++) sc.img.copy(src, (y * VW + x) * 4, (Math.floor(y * 1.5) * 960 + Math.floor(x * 1.5)) * 4, (Math.floor(y * 1.5) * 960 + Math.floor(x * 1.5)) * 4 + 4);
    }
    const c0 = k % 3, l0 = Math.floor(k / 3);
    for (let r = 0; r < VH; r++) src.copy(img, ((HAUT + l0 * (M + VH) + M + r) * W + c0 * VW) * 4, r * VW * 4, (r + 1) * VW * 4);
    ecrireTexte(img, W, titre, c0 * VW + 12, HAUT + l0 * (M + VH) + 10, 3, [255, 140, 140, 255]);
  });
  ecrirePng(path.join(DOC, "planche-tsukuyomi.png"), W, H, img);
  console.log("ok planche", W, H);
}

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  const [mode, a, b, c] = process.argv.slice(2);
  if (mode === "image") {
    const sc = image(Number(a));
    ecrirePng(b ?? path.join(DOC, "image.png"), sc.w, sc.h, sc.img);
    console.log("ok", a);
  } else if (mode === "video") {
    fs.mkdirSync(a, { recursive: true });
    const n = Math.round(DUREE * 24), de = Number(b ?? 0), pas = Number(c ?? 1);
    for (let i = de; i < n; i += pas) {
      const sc = image(i / 24);
      ecrirePng(path.join(a, String(i).padStart(4, "0") + ".png"), sc.w, sc.h, sc.img);
    }
    console.log("ok video", n);
  } else planche();
}
