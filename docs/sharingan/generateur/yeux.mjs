// Les trois éveils de l'œil (demandés le 2026-11-04, d'après trois planches de 8 étapes et une image de deux yeux dans le
// noir) : au moment de l'éveil, l'écran du joueur passe au noir, ses deux yeux s'ouvrent et l'iris se transforme.
//   Éveil 1 : le Sharingan (les trois tomoe tombent un à un, tournent, l'anneau rouge s'allume).
//   Éveil 2 : le Mangekyo (les tomoe s'étirent en tournant et deviennent trois lames autour d'un anneau ; une larme de sang).
//   Éveil 3 : le Rinnegan (le violet monte du bord, les lames s'effacent dans des ondes, six tomoe apparaissent sur l'anneau).
// L'iris est une texture de 48 × 48 (comme les textures 64 × 64 du mod), le reste de l'œil est lisse comme l'image.
// node yeux.mjs → planche-yeux.png ; node yeux.mjs video <dossier> → les images (24 par seconde) des trois éveils à la suite ;
// node yeux.mjs image <niveau> <t> [fichier.png] → une image seule.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, ecrireTexte, mix, hache, lisse } from "../../fruits/generateur/moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const TOUR = Math.PI * 2;
const borne = (u) => Math.max(0, Math.min(1, u));
const entre = (t, a, b) => borne((t - a) / (b - a));
const sortie = (u) => 1 - (1 - borne(u)) ** 3;

// ------------------------------------------------------------------ les couleurs
const ROUGE = [206, 18, 24], ROUGE_F = [112, 0, 8], ROUGE_C = [255, 74, 70], NOIR_R = [18, 0, 2];
const VIOLET = [150, 112, 224], VIOLET_F = [92, 58, 160], VIOLET_C = [206, 178, 250], NOIR_V = [32, 14, 52];

// ------------------------------------------------------------------ l'iris (texture N × N)
const N = 48;

/**
 * Une virgule (tomoe) ou une lame : des disques le long d'une courbe. m = 0 → tomoe (une grosse tête et une queue courte qui
 * suit le cercle), m = 1 → la lame du Mangekyo (accrochée à l'anneau du milieu, large, courbée, pointue au bord).
 */
function forme(th, m, s = 1, dr = 0, l = 1) {
  const r0 = mix([0.56], [0.2], m)[0] + dr, r1 = mix([0.63], [0.98], m)[0] + dr;
  const dth = (-0.95 - 0.4 * m) * l, h0 = (0.165 - 0.095 * m) * s, h1 = 0.16 * m * s;
  const pts = [];
  for (let k = 0; k <= 28; k++) {
    const q = k / 28;
    const r = r0 + (r1 - r0) * q, a = th + dth * q ** (1.25 - 0.35 * m);
    const rad = h0 * (1 - q) ** 0.85 + h1 * Math.sin(Math.PI * Math.min(1, q * 1.22)) ** 1.1 * (1 - q) ** 0.3;
    pts.push([Math.cos(a) * r, Math.sin(a) * r, rad]);
  }
  return pts;
}

/**
 * La texture de l'iris pour un état : couleur (0 rouge → 1 violet, ou une onde qui monte du bord), anneaux du Rinnegan,
 * pupille (0 point → 1 anneau du Mangekyo), formes, traînées de vitesse, anneau du bord qui s'allume.
 */
function iris(e) {
  const tex = new Float32Array(N * N * 4);
  const formes = (e.formes ?? []).map((f) => ({ a: f.a ?? 1, pts: forme(f.th + (e.rot ?? 0), f.m ?? 0, f.s ?? 1, f.dr ?? 0, f.l ?? 1) }));
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    // e.plein : le motif remplit tout le carré (la pupille en jeu), sans bord noir ni coins vides
    const k = e.plein ? 0.71 : 1;
    const u = (((i + 0.5) / N) * 2 - 1) * k, v = (((j + 0.5) / N) * 2 - 1) * k, r = Math.hypot(u, v), th = Math.atan2(v, u);
    const o = (j * N + i) * 4;
    if (r > 1 && !e.plein) continue;
    // le violet : partout (e.violet) ou en onde qui part du bord (e.onde de 0 à 1)
    const pv = Math.max(e.violet ?? 0, borne(((e.onde ?? 0) * 1.3 - (1 - r)) * 5));
    const base = mix(ROUGE, VIOLET, pv), fonce = mix(ROUGE_F, VIOLET_F, pv), vif = mix(ROUGE_C, VIOLET_C, pv), noir = mix(NOIR_R, NOIR_V, pv);
    // le dégradé : plus clair à mi-rayon, plus sombre au bord
    let c = mix(base, fonce, borne((r - 0.62) / 0.36) ** 1.4 * (0.75 - 0.45 * pv) + borne((0.2 - r) / 0.2) * 0.25);
    c = mix(c, vif, 0.18 * Math.max(0, 1 - Math.abs(r - 0.45) / 0.22));
    // les bandes du Mangekyo (cercles plus sombres)
    if (e.bandes) c = mix(c, fonce, e.bandes * 0.55 * (Math.abs(r - 0.42) < 0.03 || Math.abs(r - 0.74) < 0.035 ? 1 : 0));
    // les anneaux du Rinnegan (ils peuvent onduler vers l'extérieur avant de se poser)
    if (e.anneaux) for (const ra of e.rayons ?? [0.27, 0.47, 0.87]) if (Math.abs(r - ra) < 0.032) c = mix(c, noir, e.anneaux * 0.8);
    // les traînées de vitesse : des spirales claires qui tournent
    if (e.trainees && r > 0.3 && r < 0.96) {
      const s = Math.sin(3 * (th - (e.rot ?? 0) * 1.2) + 7 * r);
      if (s > 0.72) c = mix(c, vif, e.trainees * (s - 0.72) / 0.28);
    }
    // l'anneau du bord qui s'allume (un reflet qui tourne)
    if (e.bord && r > 0.84 && r < 0.97) c = mix(c, vif, e.bord * (0.55 + 0.45 * Math.cos(2 * (th - (e.rot ?? 0) - 0.6))));
    // les tomoe et les lames
    for (const f of formes) {
      if (f.a <= 0) continue;
      let dedans = false;
      for (const [x, y, rad] of f.pts) if ((u - x) ** 2 + (v - y) ** 2 < rad * rad) { dedans = true; break; }
      if (dedans) c = mix(c, noir, f.a);
    }
    // la pupille : un point, ou l'anneau noir du Mangekyo avec du rouge au milieu
    const pu = e.pupille ?? 0, rp = e.taillePupille ?? 1;
    if (r < (0.16 - 0.05 * (e.violet ?? 0)) * rp * (1 - pu)) c = noir;
    if (pu > 0 && r < 0.21 * rp && r > (0.21 - 0.09 * pu) * rp) c = mix(c, noir, pu);
    if (pu > 0 && r < 0.06 * rp) c = mix(c, noir, pu * 0.7);
    // le bord de l'iris
    if (r > 0.93 && !e.plein) c = mix(c, noir, 0.8);
    tex[o] = c[0]; tex[o + 1] = c[1]; tex[o + 2] = c[2]; tex[o + 3] = 1;
  }
  return tex;
}

// ------------------------------------------------------------------ les trois éveils, étape par étape
const TROIS = [0, TOUR / 3, (2 * TOUR) / 3].map((a) => a - Math.PI / 2);

/** L'état de l'œil au temps t de l'éveil n (1, 2, 3) : l'iris, l'ouverture, la lueur, les éclairs. */
function etat(n, t) {
  const e = { couleur: n === 3 ? VIOLET_C : ROUGE_C };
  if (n === 1) {
    e.ouvert = sortie(entre(t, 0.45, 1.05)) * (1 - 0.08 * Math.sin(entre(t, 3.3, 3.6) * Math.PI));
    // trois tomoe qui tombent du bord, un par un, en tournant un peu
    let rot = 0.4 * entre(t, 1.1, 2.5);
    const vite = entre(t, 2.5, 3.0), freine = entre(t, 3.0, 3.45);
    rot += 9 * vite * vite + 9 * freine * (2 - freine) * 0.5 + (t > 3.45 ? 0 : 0);
    e.rot = rot;
    e.formes = TROIS.map((th, k) => {
      const a = entre(t, 1.15 + 0.4 * k, 1.5 + 0.4 * k);
      return { th, s: sortie(a), dr: 0.38 * (1 - sortie(a)), a: borne(a * 3) };
    });
    e.trainees = Math.sin(Math.PI * entre(t, 2.5, 3.4)) * 0.9;
    e.bord = borne(entre(t, 3.3, 3.5) * 1.6) * (0.75 + 0.25 * Math.sin(t * 7));
    e.eclair = Math.max(0, 1 - Math.abs(t - 3.42) / 0.12) * 0.75;
    e.lueur = 0.25 + 0.75 * entre(t, 3.3, 3.6);
    e.fin = entre(t, 4.7, 5.2);
  } else if (n === 2) {
    e.ouvert = sortie(entre(t, 0.2, 0.7));
    const m = lisse(entre(t, 1.3, 3.0));
    const acc = entre(t, 0.6, 1.9), fr = entre(t, 1.9, 3.1);
    e.rot = 12 * acc * acc + 16 * (fr * (2 - fr)) * 0.5 - 0.25 * m;
    e.formes = TROIS.map((th) => ({ th, m }));
    e.trainees = Math.sin(Math.PI * entre(t, 0.7, 3.0)) * (1 - 0.4 * m);
    e.pupille = lisse(entre(t, 2.0, 3.0));
    e.bandes = lisse(entre(t, 2.8, 3.3));
    e.bord = 0.3 + 0.7 * Math.max(0, 1 - Math.abs(t - 3.15) / 0.5);
    e.eclair = Math.max(0, 1 - Math.abs(t - 3.12) / 0.13) * 0.8;
    e.larme = entre(t, 3.3, 5.3);
    e.lueur = 0.4 + 0.6 * entre(t, 3.0, 3.3);
    e.secousse = Math.max(0, 1 - Math.abs(t - 3.12) / 0.3);
    e.fin = entre(t, 5.6, 6.1);
  } else {
    e.ouvert = sortie(entre(t, 0.2, 0.7));
    // le Mangekyo bat comme un cœur, le violet monte du bord, les lames s'effacent dans les ondes
    const bat = Math.max(0, Math.sin(entre(t, 0.6, 1.6) * Math.PI * 2)) * (t < 1.6 ? 1 : 0);
    e.onde = lisse(entre(t, 0.9, 2.5));
    e.rot = 2.5 * lisse(entre(t, 0.6, 2.6));
    const efface = lisse(entre(t, 1.6, 2.7));
    e.pupille = 1 - efface;
    e.bandes = 1 - efface;
    e.taillePupille = 1 + 0.15 * bat;
    const lames = TROIS.map((th) => ({ th, m: 1, a: 1 - efface }));
    // les ondes : trois anneaux qui partent du centre et se posent
    const o = entre(t, 1.7, 3.0);
    e.anneaux = borne(o * 3);
    e.rayons = [0.27, 0.47, 0.87].map((ra, k) => ra * sortie(borne(o * 1.4 - k * 0.15)) + 0.04 * Math.sin(t * 9 + k) * (1 - o));
    // six tomoe : deux d'abord, puis les quatre autres
    const SIX = [-90, -30, 30, 90, 150, 210].map((d) => (d * Math.PI) / 180);
    const ordre = [0, 3, 1, 4, 2, 5];
    const tomoe = SIX.map((th, k) => {
      const a = entre(t, 3.25 + (ordre.indexOf(k) < 2 ? 0 : 0.5) + 0.12 * (ordre.indexOf(k) % 2), 3.5 + (ordre.indexOf(k) < 2 ? 0 : 0.5) + 0.12 * (ordre.indexOf(k) % 2));
      return { th: th - e.rot - 0.6 * (1 - sortie(a)), s: 0.8 * sortie(a), dr: 0.08, l: 0.5, a: borne(a * 3) };
    });
    e.formes = [...lames, ...tomoe];
    e.bord = 0.45 * bat + 0.5 * Math.max(0, 1 - Math.abs(t - 4.05) / 0.4);
    e.trainees = 0.5 * Math.sin(Math.PI * entre(t, 0.8, 2.4));
    e.eclair = Math.max(0, 1 - Math.abs(t - 4.05) / 0.14) * 0.8;
    e.lueur = 0.35 + 0.65 * entre(t, 3.9, 4.2);
    e.couleurLueur = mix(ROUGE_C, VIOLET_C, e.onde);
    e.secousse = Math.max(0, 1 - Math.abs(t - 4.05) / 0.3) * 0.7;
    e.fin = entre(t, 5.9, 6.5);
  }
  e.couleurLueur ??= e.couleur;
  return e;
}

export { iris, etat, N };
export const DUREES = [5.2, 6.1, 6.5];
export const NOMS = ["EVEIL 1 : SHARINGAN", "EVEIL 2 : MANGEKYO SHARINGAN", "EVEIL 3 : RINNEGAN (UN OEIL) + MANGEKYO"];

// ------------------------------------------------------------------ l'écran : deux yeux dans le noir
const W = 960, H = 540, A = 132, HT = 50, HB = 36, R = 54;

/** Une image de l'éveil n au temps t (960 × 540). */
export function image(n, t) {
  const e = etat(n, t);
  const tex = iris(e);
  const img = Buffer.alloc(W * H * 4);
  const sec = (e.secousse ?? 0) * 6;
  const dx0 = sec * Math.sin(t * 97), dy0 = sec * Math.cos(t * 71);
  const lueurC = e.couleurLueur;
  // à l'éveil 3, seul son œil gauche (à droite de l'écran) passe au Rinnegan ; le droit garde le Mangekyo, qui tourne doucement
  const texM = n === 3 ? iris({ ...etat(2, 4.4), rot: etat(2, 4.4).rot + 0.4 * t }) : tex;
  const yeux = [[300 + dx0, 262 + dy0, -1, texM, n === 3 ? ROUGE_C : lueurC], [660 + dx0, 262 + dy0, 1, tex, lueurC]];
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    // le fond : noir, avec une brume de la couleur de l'éveil
    let c = [3, 2, 4];
    const halo = Math.max(0, 1 - Math.hypot((x - 480) / 520, (y - 262) / 260)) ** 2;
    c = mix(c, lueurC, halo * 0.16 * e.lueur * (e.ouvert ?? 1));
    for (const [cx, cy, cote, tex, lueurC] of yeux) {
      const lx = (x - cx) * cote, ly = y - cy, nx = lx / A;
      if (Math.abs(nx) > 1.35 || Math.abs(ly) > 110) continue;
      const o = e.ouvert ?? 1;
      const haut = (q) => -HT * o * Math.max(0, 1 - q * q) ** 0.72 - 7 * q;
      const bas = (q) => HB * o * Math.max(0, 1 - q * q) ** 0.95 - 4 * q;
      // le halo blanc autour de l'œil (comme sur l'image), qui prend la couleur de l'éveil quand il s'allume
      const g = Math.max(0, 1 - Math.hypot(nx / 1.22, (ly + 4) / (HT * 1.9))) ** 1.6;
      c = mix(c, mix([235, 235, 245], lueurC, 0.35 + 0.4 * e.lueur), g * 0.55 * o);
      const dedans = Math.abs(nx) < 1 && ly > haut(nx) && ly < bas(nx);
      if (dedans) {
        const d = Math.min(ly - haut(nx), bas(nx) - ly);
        const bordA = borne(d / 1.3);
        // le blanc de l'œil, avec l'ombre de la paupière en haut
        let s = mix([226, 226, 232], [150, 150, 165], Math.exp(-(ly - haut(nx)) / 9) * 0.8);
        s = mix(s, [180, 180, 192], borne(Math.abs(nx) - 0.6) * 0.6);
        // l'iris (la texture, texel par texel), un peu vers le bas
        const ix = (x - (cx + 0)) / R, iy = (y - (cy + 3)) / R, ri = Math.hypot(ix, iy);
        if (ri < 1) {
          const ti = Math.min(N - 1, Math.floor((ix + 1) * 0.5 * N)), tj = Math.min(N - 1, Math.floor((iy + 1) * 0.5 * N));
          const k = (tj * N + ti) * 4;
          let ic = [tex[k], tex[k + 1], tex[k + 2]];
          // l'ombre de la paupière sur l'iris, et le reflet
          ic = mix(ic, [0, 0, 0], Math.exp(-(ly - haut(nx)) / 10) * 0.55);
          const ru = (ti + 0.5) / N * 2 - 1, rv = (tj + 0.5) / N * 2 - 1;
          if (Math.hypot(ru + 0.38, rv + 0.42) < 0.11) ic = mix(ic, [255, 255, 255], 0.85);
          // l'iris brille un peu de sa couleur quand il s'allume
          ic = mix(ic, lueurC, 0.08 * e.lueur);
          s = mix(s, ic, borne((1 - ri) * R / 1.2));
        }
        c = mix(c, s, bordA);
      }
      // le trait de la paupière du haut : épais, avec une pointe qui remonte au coin extérieur
      if (nx > -1.05 && nx < 1.32) {
        let yl, ep;
        if (nx <= 1) { yl = haut(Math.max(-1, nx)); ep = (3 + 7 * Math.sin(Math.PI * (Math.max(-1, nx) + 1) / 2.4)) * (0.5 + 0.5 * o); }
        else { yl = haut(1) - (nx - 1) * A * 0.32; ep = 6 * (1 - (nx - 1) / 0.32); }
        const dl = Math.abs(ly - (yl - ep * 0.4));
        if (dl < ep) c = mix(c, [8, 8, 10], borne(ep - dl));
      }
      // le trait du bas, fin, qui s'efface vers le coin intérieur
      if (Math.abs(nx) < 1 && o > 0.05) {
        const dl = Math.abs(ly - bas(nx) - 1);
        const ep = 1.6 * borne((nx + 0.7) / 0.6);
        if (dl < ep) c = mix(c, [10, 10, 12], borne(ep - dl) * 0.9);
      }
      // la larme de sang du Mangekyo : elle coule de la paupière du bas, sous l'iris
      if (e.larme && Math.abs(lx + 6) < 4) {
        const y0 = bas(-6 / A) + 1, lg = 150 * sortie(e.larme);
        const yy = ly - y0;
        const larg = 1.6 + 1.2 * borne(1 - yy / 30) + (Math.abs(yy - lg) < 5 ? 1.8 : 0);
        if (yy > 0 && yy < lg + 4 && Math.abs(lx + 6) < larg) c = mix(c, [150, 0, 6], 0.92);
      }
    }
    // des braises qui montent autour des yeux
    img[(y * W + x) * 4] = c[0]; img[(y * W + x) * 4 + 1] = c[1]; img[(y * W + x) * 4 + 2] = c[2]; img[(y * W + x) * 4 + 3] = 255;
  }
  braises(img, e, t, n);
  // l'éclair de l'éveil, puis le fondu vers le jeu
  const fl = e.eclair ?? 0, fin = e.fin ?? 0;
  for (let i = 0; i < W * H; i++) for (let k = 0; k < 3; k++) {
    let v = img[i * 4 + k];
    v = v + (lueurC[k] * 0.6 + 255 * 0.4 - v) * fl;
    v *= 1 - fin;
    img[i * 4 + k] = Math.round(v);
  }
  ecrireTexte(img, W, NOMS[n - 1], 30, H - 50, 4, [200, 200, 210, 255].map((v, k) => (k < 3 ? Math.round(v * (1 - fin) * borne(t * 3)) : 255)));
  return img;
}

/** Des braises de la couleur de l'éveil, carrées comme en jeu, qui montent doucement. */
function braises(img, e, t, n) {
  const nb = Math.round(70 * e.lueur);
  for (let q = 0; q < nb; q++) {
    const vie = 2.2 + 1.5 * hache(q, 1, 900 + n);
    const age = (t + vie * hache(q, 2, 900 + n)) % vie;
    const x = 180 + 600 * hache(q, 3, 900 + n) + 18 * Math.sin(age * 2 + q);
    const y = 400 - age * 70 - 60 * hache(q, 4, 900 + n);
    const a = Math.sin(Math.PI * age / vie) * 0.8 * (e.ouvert ?? 1);
    const s = 2 + Math.floor(3 * hache(q, 5, 900 + n));
    for (let dy = 0; dy < s; dy++) for (let dx = 0; dx < s; dx++) {
      const px = Math.round(x) + dx, py = Math.round(y) + dy;
      if (px < 0 || py < 0 || px >= W || py >= H) continue;
      const i = (py * W + px) * 4;
      for (let k = 0; k < 3; k++) img[i + k] = Math.round(img[i + k] + (e.couleurLueur[k] - img[i + k]) * a);
    }
  }
}

// ------------------------------------------------------------------ la planche : 8 étapes par éveil, comme les images
const MOMENTS = [
  [0.9, 1.3, 1.7, 2.1, 2.75, 3.0, 3.45, 4.2],
  [0.6, 1.2, 1.7, 2.1, 2.5, 2.8, 3.15, 4.4],
  [0.6, 1.3, 1.8, 2.3, 2.9, 3.45, 3.95, 4.8],
];

function rect(img, LW, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * LW + x) * 4);
}

function planche() {
  const T = 4, C = N * T + 40, M = 28, LW = C * 8 + M * 9, HAUT = 90, LIGNE = C + 70, LH = HAUT + LIGNE * 3 + 20;
  const img = Buffer.alloc(LW * LH * 4);
  rect(img, LW, 0, 0, LW, LH, [12, 10, 14, 255]);
  ecrireTexte(img, LW, "LES TROIS EVEILS DE L'OEIL : 8 ETAPES CHACUN (LA DERNIERE = L'OEIL FINAL)", M, 30, 5, [230, 230, 236, 255]);
  for (let n = 1; n <= 3; n++) {
    const y0 = HAUT + (n - 1) * LIGNE;
    ecrireTexte(img, LW, NOMS[n - 1], M, y0 + 8, 4, n === 3 ? [190, 160, 250, 255] : [255, 90, 80, 255]);
    MOMENTS[n - 1].forEach((t, k) => {
      const tex = iris(etat(n, t)), x0 = M + k * (C + M), yy = y0 + 50;
      // le cadre en métal sombre, comme sur les images
      rect(img, LW, x0, yy, x0 + C, yy + C, [58, 48, 52, 255]);
      rect(img, LW, x0 + 8, yy + 8, x0 + C - 8, yy + C - 8, [8, 4, 6, 255]);
      for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
        const q = (j * N + i) * 4;
        if (!tex[q + 3]) continue;
        rect(img, LW, x0 + 20 + i * T, yy + 20 + j * T, x0 + 20 + (i + 1) * T, yy + 20 + (j + 1) * T, [tex[q], tex[q + 1], tex[q + 2], 255].map(Math.round));
      }
      ecrireTexte(img, LW, String(k + 1), x0 + 6, yy + C + 6, 3, [150, 150, 160, 255]);
    });
  }
  ecrirePng(path.join(DOC, "planche-yeux.png"), LW, LH, img);
  console.log("ok planche", LW, LH);
}

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  const [mode, a, b, c] = process.argv.slice(2);
  if (mode === "image") {
    ecrirePng(c ?? path.join(DOC, "image.png"), W, H, image(Number(a), Number(b)));
    console.log("ok", a, b);
  } else if (mode === "video") {
    // les trois éveils à la suite, 24 images par seconde, avec un temps noir entre deux
    fs.mkdirSync(a, { recursive: true });
    let k = 0;
    for (let n = 1; n <= 3; n++) {
      const nb = Math.round(DUREES[n - 1] * 24);
      for (let i = 0; i < nb; i++) ecrirePng(path.join(a, String(k++).padStart(4, "0") + ".png"), W, H, image(n, i / 24));
    }
    console.log("ok video", k);
  } else planche();
}
