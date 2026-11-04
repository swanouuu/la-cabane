// Le Susano parfait en cubes fins (1/8 de bloc), d'après le Susano parfait de Sasuke (modèles Sketchfab donnés par
// l'utilisateur) : un masque d'oni aux yeux jaunes, une crinière en pointes, un kimono au col croisé, des épaulières en
// lames, une large ceinture, une petite jupe de plaques, un pantalon bouffant ; d'immenses ailes dont le bras finit en une
// main qui serre une poignée d'épée, une nappe de tuiles qui se rejoint dans le dos. L'épée de flammes (main droite) et
// l'épée sombre (main gauche) se cachent. Chaque cube garde sa teinte, sa lueur, sa sorte, sa pièce et deux
// articulations avec un poids (les ailes se plient en douceur).
// node susano-voxel.mjs → susano.bin + susano-os.json   (PAS=0.25 : plus gros cubes, plus vite)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const len = (a) => Math.hypot(a[0], a[1], a[2]);
const norm = (a) => mul(a, 1 / (len(a) || 1));
const clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
const lerp = (a, b, t) => add(a, mul(sub(b, a), t));
const fract = (x) => x - Math.floor(x);
const DEG = Math.PI / 180;
const hache = (i, j, k) => { let h = (i * 374761393 + j * 668265263 + k * 2147483647) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };

/** Les pièces (l'aperçu peut cacher chacune). */
const PIECES = ["corps", "armure", "pans", "ceinture", "tete", "cheveux", "ailes", "plumes", "poignees", "flamme", "sombre"];
const P = Object.fromEntries(PIECES.map((n, i) => [n, i]));

// ================================================================== les articulations
const POIGNET = { D: [-5.25, 12.3, 2.7], G: [5.25, 12.3, 2.7] };
/** Les ailes : un plan presque vertical derrière le dos, qui recule un peu vers le bout. */
const ZAILE = (x) => -2.3 - 0.13 * Math.abs(x);
const AILE = (s) => [s * 1.6, 19.8, ZAILE(1.6)];
const MAIN_AILE = (s) => [s * 9.4, 28.2, ZAILE(9.4)];
const OS = [
  ["bassin", [0, 12.8, 0], -1], ["torse", [0, 14.2, 0], 0], ["tete", [0, 20.4, 0], 1],
  ["brasD", [-4.3, 19.0, 0], 1], ["brasG", [4.3, 19.0, 0], 1], ["avantD", [-5.6, 15.3, 0.6], 3], ["avantG", [5.6, 15.3, 0.6], 4],
  ["mainD", POIGNET.D, 5], ["mainG", POIGNET.G, 6], ["cuisseD", [-1.7, 12.6, 0], 0], ["cuisseG", [1.7, 12.6, 0], 0],
  ["tibiaD", [-2.2, 7.2, 0.4], 9], ["tibiaG", [2.2, 7.2, 0.4], 10], ["aileD", AILE(-1), 1], ["aileG", AILE(1), 1],
  ["boutD", MAIN_AILE(-1), 13], ["boutG", MAIN_AILE(1), 14],
];
const O = Object.fromEntries(OS.map(([n], i) => [n, i]));
/** L'articulation à laquelle les volumes créés appartiennent (on la change avant chaque partie). */
let os = 0;

// ================================================================== les volumes

function axes(d, face = [0, 0, 1]) {
  const Y = norm(d);
  let X = cross(Y, face);
  if (len(X) < 0.2) X = cross(Y, [1, 0, 0]);
  X = norm(X);
  return [X, Y, cross(X, Y)];
}
const rotZ = (a) => [[Math.cos(a), Math.sin(a), 0], [-Math.sin(a), Math.cos(a), 0], [0, 0, 1]];
const ID = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];

const VOLUMES = [];
function boite(piece, c, ax, h, r, teinte) {
  VOLUMES.push({
    piece, os, c, R: Math.hypot(...h) + r + 0.3, teinte,
    d(p) {
      const q = sub(p, c), l = [dot(q, ax[0]), dot(q, ax[1]), dot(q, ax[2])];
      const e = l.map((v, i) => Math.abs(v) - h[i]);
      return [Math.hypot(Math.max(e[0], 0), Math.max(e[1], 0), Math.max(e[2], 0)) + Math.min(Math.max(...e), 0) - r, l, h];
    },
  });
}
function cone(piece, a, b, ra, rb, teinte) {
  const ab = sub(b, a), l2 = dot(ab, ab), [X, , Z] = axes(ab);
  VOLUMES.push({
    piece, os, c: lerp(a, b, 0.5), R: Math.sqrt(l2) / 2 + Math.max(ra, rb) + 0.3, teinte,
    d(p) {
      const ap = sub(p, a), t = clamp(dot(ap, ab) / l2), q = sub(ap, mul(ab, t)), dq = len(q);
      return [dq - (ra + (rb - ra) * t), [dq, t, Math.atan2(dot(q, Z), dot(q, X))], null];
    },
  });
}
function chaine(piece, pts, rayons, teinte) {
  for (let i = 0; i + 1 < pts.length; i++) {
    const t0 = i / (pts.length - 1), t1 = (i + 1) / (pts.length - 1);
    cone(piece, pts[i], pts[i + 1], rayons[i], rayons[i + 1], (l) => teinte([l[0], t0 + (t1 - t0) * l[1], l[2]]));
  }
}
function libre(piece, c, R, d, teinte) { VOLUMES.push({ piece, os, c, R, d, teinte }); }
const dBoite = (l, h, r = 0) => { const e = l.map((v, i) => Math.abs(v) - h[i]); return Math.hypot(Math.max(e[0], 0), Math.max(e[1], 0), Math.max(e[2], 0)) + Math.min(Math.max(...e), 0) - r; };

const T = (t, e = 0, s = 0) => ({ t, e, s });
/** Une plaque d'armure : un liseré de lumière au bord, des rangs (lamelles) séparés par des joints sombres. */
const plaque = (rangs = 0, bord = 0.16, base = 0.55) => (l, h) => {
  if (!h) return T(base);
  const ex = Math.min(h[0] - Math.abs(l[0]), h[1] - Math.abs(l[1]));
  if (ex < bord) return T(0.95, 0.25);
  if (rangs) {
    const f = ((l[1] + h[1]) / (2 * h[1])) * rangs;
    if (fract(f) < 0.1) return T(0.18);
    return T(base - 0.1 + 0.3 * fract(f));
  }
  return T(base);
};
/** Des lamelles lacées : des rangs, et dans chaque rang des petites plaques séparées par des lacets. */
const lamelles = (rangs, cols, base = 0.55) => (l, h) => {
  const ex = Math.min(h[0] - Math.abs(l[0]), h[1] - Math.abs(l[1]));
  if (ex < 0.14) return T(0.95, 0.25);
  const fy = ((l[1] + h[1]) / (2 * h[1])) * rangs, fx = ((l[0] + h[0]) / (2 * h[0])) * cols;
  if (fract(fy) < 0.12) return T(0.16);
  if (fract(fx) < 0.08) return T(fract(fy) < 0.3 ? 0.85 : 0.25);
  return T(base - 0.12 + 0.32 * fract(fy));
};
const tissu = (base = 0.4) => () => T(base);
const flamme = (l) => T(0.6 + 0.4 * l[1], 0.12 + 0.6 * l[1] ** 1.3);

// ================================================================== le Susano (d'après le Susano parfait de Sasuke de Sketchfab)

const BASE = 0.66;
/** Le chakra uni, avec des lignes blanches (coutures, plis) là où ligne(l) est vrai. */
const uni = (ligne, base = BASE) => (l, h) => (ligne(l, h) ? T(1, 0.35) : T(base + 0.04 * Math.sin((l[0] + l[1] * 1.7) * 2)));
const plein = (base = BASE) => () => T(base);
/** Un ellipsoïde (distance approchée). */
const dEll = (q, r) => {
  const k0 = Math.hypot(q[0] / r[0], q[1] / r[1], q[2] / r[2]), k1 = Math.hypot(q[0] / (r[0] * r[0]), q[1] / (r[1] * r[1]), q[2] / (r[2] * r[2]));
  return (k0 * (k0 - 1)) / (k1 || 1);
};
function ell(piece, c, r, teinte) { libre(piece, c, Math.max(...r) + 0.3, (p) => { const q = sub(p, c); return [dEll(q, r), q, r]; }, teinte); }
/** Une courbe douce qui passe par les points [[x, y], ...] (pentes de Catmull-Rom). */
const table = (pts) => (x) => {
  const n = pts.length;
  if (x <= pts[0][0]) return pts[0][1];
  if (x >= pts[n - 1][0]) return pts[n - 1][1];
  let i = 0;
  while (x > pts[i + 1][0]) i++;
  const [x0, y0] = pts[i], [x1, y1] = pts[i + 1], dx = x1 - x0, t = (x - x0) / dx;
  const m = (k) => (k <= 0 || k >= n - 1 ? 0 : (pts[k + 1][1] - pts[k - 1][1]) / (pts[k + 1][0] - pts[k - 1][0]));
  const m0 = m(i) * dx, m1 = m(i + 1) * dx, t2 = t * t, t3 = t2 * t;
  return (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * m1;
};
const dSeg2 = (x, y, a, b) => {
  const abx = b[0] - a[0], aby = b[1] - a[1], t = clamp(((x - a[0]) * abx + (y - a[1]) * aby) / (abx * abx + aby * aby));
  return Math.hypot(x - a[0] - abx * t, y - a[1] - aby * t);
};
const angle = (a) => Math.atan2(Math.sin(a), Math.cos(a));

// ---- les jambes : un pantalon bouffant plissé jusque sous le genou, une bande, le bas de la jambe bandé, des bottes pointues
for (const s of [-1, 1]) {
  const hanche = [s * 1.7, 12.6, 0], genou = [s * 2.2, 7.2, 0.4], serre = [s * 2.2, 4.3, 0.3], cheville = [s * 2.1, 1.35, 0.15];
  const plis = (n) => uni((l) => fract(l[2] / (Math.PI * 2) * n + 0.5) < 0.05);
  os = s < 0 ? O.cuisseD : O.cuisseG;
  cone(P.corps, hanche, genou, 1.5, 1.42, plis(8));
  os = s < 0 ? O.tibiaD : O.tibiaG;
  cone(P.corps, genou, lerp(genou, serre, 0.82), 1.42, 1.25, plis(8));
  cone(P.corps, lerp(genou, serre, 0.82), serre, 1.32, 1.24, (l) => (l[1] < 0.18 || l[1] > 0.82 ? T(1, 0.3) : T(BASE - 0.06)));
  cone(P.corps, serre, cheville, 1.02, 0.92, (l) => (fract(l[1] * 5 + l[2] / Math.PI * 0.5) < 0.1 ? T(1, 0.25) : T(BASE - 0.02)));
  // la botte : une semelle claire, un bout qui s'affine
  libre(P.corps, [s * 2.1, 0.7, 0.9], 3.2, (p) => {
    const q = sub(p, [s * 2.1, 0, 0.75]), z = q[2], f = clamp((z - 0.2) / 1.7);
    const larg = 0.88 - 0.48 * f ** 1.5, haut = 1.2 - 0.82 * f;
    return [dBoite([q[0], q[1] - haut / 2, z], [larg, haut / 2, 1.75], 0.18), [q[0], q[1], z], null];
  }, (l) => (l[1] < 0.22 ? T(1, 0.3) : Math.abs(l[0]) < 0.06 && l[2] > 0.5 ? T(0.95, 0.2) : T(BASE - 0.04)));
}

// ---- les pans d'armure : des plaques raides sous la ceinture jusqu'à mi-cuisse (lignes verticales, liseré clair) ;
// ceux de devant et des côtés suivent la cuisse, ceux de derrière le bassin
function pan(am, ah, yh, yb) {
  libre(P.pans, [Math.sin(am) * 3.2, (yh + yb) / 2, Math.cos(am) * 3.2], Math.max(ah * 3.6, (yh - yb) / 2) + 1.2, (p) => {
    const x = p[0], z = p[2] - 0.05, r = Math.hypot(x, z), da = angle(Math.atan2(x, z) - am);
    const t = clamp((yh - p[1]) / (yh - yb)), rad = 2.95 + 0.65 * t;
    const e = [Math.abs(da) * r - ah * r, Math.abs(p[1] - (yh + yb) / 2) - (yh - yb) / 2, Math.abs(r - rad) - 0.17];
    return [Math.min(Math.max(...e), 0) + Math.hypot(Math.max(e[0], 0), Math.max(e[1], 0), Math.max(e[2], 0)), [da * r, p[1] - yb, ah * r], null];
  }, ([u, v, demi]) => {
    if (v < 0.18 || demi - Math.abs(u) < 0.14) return T(1, 0.32);
    if (fract(u / 0.62 + 0.5) < 0.1) return T(0.95, 0.2);
    return T(BASE - 0.06 + 0.08 * clamp(v / 4));
  });
}
for (const s of [-1, 1]) {
  os = s < 0 ? O.cuisseD : O.cuisseG;
  pan(s * 0.45, 0.4, 13.1, 10.2);
  pan(s * 1.3, 0.38, 13.1, 10.4);
  os = O.bassin;
  pan(s * 2.35, 0.48, 13.1, 10.3);
}
os = O.bassin;
pan(Math.PI, 0.22, 13.1, 10.3);

// ---- la ceinture : une large bande à liserés, une corde par-dessus, un nœud et ses deux bouts sur le côté
os = O.bassin;
libre(P.ceinture, [0, 14.0, 0.05], 4.5, (p) => {
  const rr = Math.hypot(p[0] / 2.95, (p[2] - 0.05) / 2.35);
  return [Math.max((rr - 1) * 2.35, Math.abs(p[1] - 14.0) - 1.05), [p[0], p[1] - 14.0], null];
}, ([x, dy]) => (Math.abs(Math.abs(dy) - 1.0) < 0.1 ? T(1, 0.32) : T(BASE - 0.05 + 0.03 * Math.sin(x * 3))));
libre(P.ceinture, [0, 14.45, 0.05], 4.5, (p) => {
  const rr = Math.hypot(p[0] / 3.08, (p[2] - 0.05) / 2.48);
  return [Math.max((rr - 1) * 2.48, Math.abs(p[1] - 14.45) - 0.24), [p[0], p[1]], null];
}, (l) => (fract(l[0] * 1.6 + l[1] * 2) < 0.2 ? T(0.95, 0.2) : T(BASE + 0.08)));
ell(P.ceinture, [1.25, 14.3, 2.45], [0.7, 0.55, 0.38], uni((l) => Math.abs(l[0]) < 0.06));
for (const k of [0, 1]) boite(P.ceinture, [1.0 + k * 0.7, 12.6, 2.5 + k * 0.08], rotZ(0.1 - k * 0.22), [0.32, 1.35, 0.1], 0.07, uni((l, h) => Math.abs(l[0]) > h[0] - 0.08 || l[1] < -h[1] + 0.1));

// ---- le torse : un kimono au col croisé (en relief, bordé de blanc), des lignes verticales, un quadrillage sur le ventre
os = O.torse;
const LT = table([[12.6, 2.6], [14.5, 2.55], [16.2, 2.95], [17.8, 3.35], [19.2, 3.5], [20.3, 3.05], [21.0, 2.3]]);
const PT = table([[12.6, 1.95], [14.5, 1.85], [16.5, 2.0], [18.2, 2.15], [19.6, 1.95], [21.0, 1.45]]);
const ZT = table([[12.6, 0], [16, 0.08], [18.2, 0.2], [21, -0.1]]);
const COLS = [[[1.75, 21.2], [-1.0, 13.8]], [[-1.75, 21.2], [0.15, 16.1]]];
libre(P.corps, [0, 16.8, 0], 6.5, (p) => {
  const y = clamp(p[1], 12.6, 21.0), wx = LT(y), wz = PT(y), z = p[2] - ZT(y), n = 2.5;
  const r = (Math.abs(p[0] / wx) ** n + Math.abs(z / wz) ** n) ** (1 / n);
  let d = Math.max((r - 1) * Math.min(wx, wz) * 0.95, p[1] - 21.0, 12.5 - p[1]);
  let col = 9;
  if (p[2] > 0.3) for (const [a, b] of COLS) col = Math.min(col, dSeg2(p[0], p[1], a, b));
  d -= 0.22 * clamp((0.62 - col) / 0.12);
  return [d, [p[0], p[1], p[2], col], null];
}, ([x, y, z, col]) => {
  if (col < 0.64 && col > 0.48) return T(1, 0.35);
  if (col <= 0.48) return T(BASE + 0.07);
  if (fract((Math.atan2(x, z) * 2.6) / 0.85) < 0.07) return T(1, 0.3);
  if (y < 16.4 && z > 0 && fract(y / 1.05) < 0.08) return T(1, 0.3);
  return T(BASE + 0.03 * Math.sin(x * 2 + y));
});
for (const s of [-1, 1]) ell(P.corps, [s * 1.9, 20.55, -0.2], [1.55, 0.75, 1.2], plein());
// le col qui fait le tour du cou
libre(P.corps, [0, 20.95, 0], 2.4, (p) => {
  const q = sub(p, [0, 20.95, -0.05]), rr = Math.hypot(q[0] / 1.55, q[2] / 1.45);
  return [Math.max(Math.abs(rr - 1) * 1.45 - 0.22, Math.abs(q[1]) - 0.32), [q[0], q[1]], null];
}, (l) => (Math.abs(l[1]) > 0.22 ? T(1, 0.35) : T(BASE + 0.06)));

// ---- les bras : une grande épaulière en trois lames bombées, une manche large jusqu'au coude, l'avant-bras nu bagué au
// poignet, une main griffue
for (const s of [-1, 1]) {
  const ep = [s * 4.3, 19.0, 0], coude = [s * 5.6, 15.3, 0.6], w = s < 0 ? POIGNET.D : POIGNET.G;
  os = s < 0 ? O.brasD : O.brasG;
  for (let i = 0; i < 3; i++) {
    const C = [s * (3.95 + 0.3 * i), 19.0 - 0.72 * i, 0.0], R = 1.75 + 0.1 * i, nO = norm([s * 0.72, 0.7, 0]), lim = 0.55 - 0.03 * i;
    libre(P.armure, C, R + 0.6, (p) => {
      const q = sub(p, C), dq = len(q) || 1e-6, cosA = dot(q, nO) / dq;
      return [Math.max(Math.abs(dq - R) - 0.15, (lim - cosA) * dq), [cosA - lim, q[2], dq], null];
    }, ([marge, z]) => (marge < 0.07 ? T(1, 0.35) : fract(z * 0.9 + 0.5) < 0.06 ? T(0.95, 0.2) : T(BASE + 0.02 + 0.1 * marge)));
  }
  cone(P.corps, ep, coude, 1.15, 1.6, uni((l) => fract(l[2] / (Math.PI * 2) * 6) < 0.05 || l[1] > 0.93));
  os = s < 0 ? O.avantD : O.avantG;
  cone(P.corps, lerp(coude, w, -0.05), w, 0.92, 0.74, (l) => (l[1] > 0.84 && l[1] < 0.94 ? T(1, 0.3) : T(BASE - 0.02)));
  os = s < 0 ? O.mainD : O.mainG;
  const d = norm(sub(w, coude)), [X, , Z] = axes(d, [0, 0, 1]), paume = add(w, mul(d, 0.45));
  boite(P.corps, paume, [X, d, Z], [0.72, 0.55, 0.4], 0.25, plein());
  for (let k = 0; k < 4; k++) {
    const base = add(add(paume, mul(d, 0.55)), mul(X, (k - 1.5) * 0.36));
    chaine(P.corps, [base, add(base, add(mul(d, 0.45), mul(Z, 0.35))), add(base, add(mul(d, 0.3), mul(Z, 0.8)))], [0.2, 0.17, 0.04], (l) => (l[1] > 0.7 ? T(0.95, 0.2) : T(BASE)));
  }
  const pouce = add(paume, mul(X, -s * 0.75));
  chaine(P.corps, [pouce, add(pouce, add(mul(d, 0.4), mul(Z, 0.3))), add(pouce, add(mul(d, 0.55), mul(Z, 0.65)))], [0.2, 0.16, 0.04], (l) => (l[1] > 0.7 ? T(0.95, 0.2) : T(BASE)));
}

// ---- la tête : le cou ; un masque d'oni (sourcils froncés, yeux jaunes dans leurs creux, nez, pommettes, une muselière à
// dents et deux crocs) ; des pointes sur les joues ; une crinière de cheveux en pointes qui montent
os = O.tete;
cone(P.corps, [0, 20.2, -0.15], [0, 21.9, 0.1], 1.05, 0.95, plein(0.6));
const debutTete = VOLUMES.length;
ell(P.tete, [0, 23.0, -0.15], [1.5, 1.75, 1.65], plein(0.6));
ell(P.tete, [0, 22.55, 0.95], [1.32, 1.42, 0.98], uni((l) => Math.abs(l[0]) < 0.05 && l[1] > 0.6, 0.72));
ell(P.tete, [0, 23.55, 0.75], [1.4, 0.75, 1.1], plein(0.7));
for (const s of [-1, 1]) {
  boite(P.tete, [s * 0.62, 23.38, 1.85], rotZ(s * 0.36), [0.62, 0.17, 0.24], 0.07, (l, h) => (l[1] > h[1] - 0.06 ? T(1, 0.3) : T(0.8)));
  boite(P.tete, [s * 0.6, 22.95, 1.74], rotZ(s * 0.3), [0.56, 0.25, 0.14], 0.04, () => T(0.12, 0, 2));
  boite(P.tete, [s * 0.6, 22.95, 1.86], rotZ(s * 0.3), [0.44, 0.14, 0.07], 0.03, () => T(1, 1.3, 1));
  boite(P.tete, [s * 0.66, 22.95, 1.93], rotZ(s * 0.3), [0.1, 0.1, 0.03], 0.02, () => T(0.05, 0, 2));
  ell(P.tete, [s * 0.95, 22.35, 1.5], [0.5, 0.38, 0.4], plein(0.76));
  chaine(P.tete, [[s * 0.62, 21.42, 1.98], [s * 0.66, 21.85, 2.06], [s * 0.7, 22.2, 2.04]], [0.17, 0.11, 0.02], () => T(1, 0.4));
  for (let k = 0; k < 3; k++) {
    const b0 = [s * 1.35, 21.95 + 0.55 * k, 0.55 - 0.18 * k], dir = norm([s * 0.85, 0.3 + 0.25 * k, -0.5]), lp = 1.4 + 0.45 * k;
    chaine(P.tete, [b0, add(b0, mul(dir, lp * 0.5)), add(add(b0, mul(dir, lp)), [0, 0.15, -0.2])], [0.42, 0.24, 0.02], (l) => T(BASE + 0.2 * l[1], 0.04 + 0.3 * l[1] ** 2));
  }
}
chaine(P.tete, [[0, 23.3, 1.82], [0, 22.85, 2.45], [0, 22.45, 3.15], [0, 22.15, 3.75], [0, 22.0, 4.05]], [0.26, 0.32, 0.24, 0.13, 0.03], (l) => (l[1] > 0.75 ? T(0.95, 0.2) : T(0.8)));
for (const s of [-1, 1]) ell(P.tete, [s * 0.3, 22.25, 2.2], [0.24, 0.17, 0.2], plein(0.78));
libre(P.tete, [0, 21.75, 1.45], 1.6, (p) => {
  const q = sub(p, [0, 21.75, 1.45]);
  return [dBoite(q, [0.8, 0.4, 0.32], 0.32), q, [0.8, 0.4, 0.32]];
}, (l) => {
  const dy = Math.abs(l[1] + 0.02);
  if (l[2] > 0.25 && dy < 0.16 && Math.abs(l[0]) < 0.75) return Math.abs(fract(l[0] * 3.4) - 0.5) * 2 < 1 - dy / 0.16 ? T(1, 0.35) : T(0.08, 0, 2);
  return T(0.74);
});
ell(P.tete, [0, 21.15, 1.2], [0.72, 0.45, 0.62], plein(0.7));
{
  const CT = [0, 23.1, -0.2];
  for (let i = 0; i < 70; i++) {
    const u = (i + 0.5) / 70, el = (88 - 72 * u) * DEG, az = i * 2.39996;
    const dirB = [Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az)];
    if (dirB[2] > 0.3 && el < 52 * DEG) continue;
    const base = add(CT, [dirB[0] * 1.4, dirB[1] * 1.55, dirB[2] * 1.45]);
    const dir = norm(add(mul(dirB, 0.95), [0, 1.0, -0.5]));
    const lp = 0.85 * (1.7 + 2.7 * hache(i, 7, 3)) * (0.5 + 0.5 * Math.sin(el));
    chaine(P.cheveux, [base, add(base, mul(dir, lp * 0.5)), add(add(base, mul(dir, lp)), [dirB[0] * 0.35, 0, -0.35])], [0.66, 0.38, 0.03],
      (l) => T(BASE + 0.26 * l[1], 0.05 + 0.45 * l[1] ** 2));
  }
}
{
  const C0 = [0, 21.0, 0], k = 1.2;
  for (let i = debutTete; i < VOLUMES.length; i++) {
    const V = VOLUMES[i], d0 = V.d;
    V.d = (p) => { const r = d0(add(C0, mul(sub(p, C0), 1 / k))); return [r[0] * k, r[1], r[2]]; };
    V.c = add(C0, mul(sub(V.c, C0), k));
    V.R *= k;
  }
}

// ---- les ailes : depuis l'omoplate, un bras lisse monte jusqu'à une main qui tient la poignée d'une épée ; de là, un bord
// épais redescend jusqu'à la pointe ; dessous, une nappe de plaques en tuiles (liseré clair) qui se rejoint dans le dos.
// Les tuiles se plient doucement entre le dos, le bras de l'aile et son bout (poids d'articulation par cube).
const HAUT_AILE = table([[0, 20.4], [1.6, 20.4], [4, 22.8], [7, 25.9], [9.4, 28.4], [11.5, 28.75], [14.5, 27.8], [18, 25.2], [21, 20.6], [23.3, 14.2], [24.2, 10.2]]);
const BAS_AILE = table([[0, 14.2], [5, 13.2], [10, 12.0], [15, 11.0], [20, 10.3], [24.2, 10.0]]);
const PA = (s, [x, y], dz = 0) => [s * x, y, ZAILE(x) + dz];
const lisse = (a, b, x) => { const t = clamp((x - a) / (b - a)); return t * t * (3 - 2 * t); };
for (const s of [-1, 1]) {
  const AI = s < 0 ? O.aileD : O.aileG, BO = s < 0 ? O.boutD : O.boutG;
  os = AI;
  chaine(P.ailes, [[1.6, 19.8], [4.0, 22.6], [7.0, 25.7], [9.4, 28.2]].map((q) => PA(s, q)), [1.05, 0.9, 0.78, 0.68], plein(0.72));
  ell(P.ailes, PA(s, [1.9, 19.6], 0.25), [1.2, 1.35, 0.8], plein(0.68));
  os = BO;
  chaine(P.ailes, [[9.4, 28.2], [11.5, 28.6], [14.5, 27.7], [18, 25.1], [21, 20.5], [23.3, 14.1], [24.2, 10.2], [24.35, 8.9]].map((q) => PA(s, q)),
    [0.68, 0.62, 0.55, 0.47, 0.38, 0.27, 0.12, 0.03], plein(0.72));

  // la main de l'aile et la poignée qu'elle serre
  os = AI;
  const H = PA(s, [9.9, 29.6]), W = PA(s, [9.4, 28.2]), hd = norm([-s * 0.94, -0.3, 0.12]);
  const e1 = norm(cross(hd, [0, 0, 1])), e2 = norm(cross(hd, e1));
  const cM = add(H, mul(hd, 1.25));
  libre(P.poignees, cM, 3.0, (p) => {
    const q = sub(p, cM), a = dot(q, hd), rr = len(sub(q, mul(hd, a)));
    return [Math.max(rr - 0.44, Math.abs(a) - 2.15), [a, Math.atan2(dot(q, e2), dot(q, e1))], null];
  }, ([a, an]) => {
    if (a > 1.9) return T(0.95, 0.3);
    const f1 = fract(a * 1.25 + an / Math.PI), f2 = fract(a * 1.25 - an / Math.PI);
    return f1 < 0.11 || f2 < 0.11 ? T(1, 0.3) : T(0.42);
  });
  const tsuba = add(H, mul(hd, -0.95));
  libre(P.poignees, tsuba, 1.4, (p) => {
    const q = sub(p, tsuba), a = dot(q, hd), rr = len(sub(q, mul(hd, a)));
    return [Math.max(rr - 1.0, Math.abs(a) - 0.13) - 0.04, [rr], null];
  }, ([rr]) => (rr > 0.82 ? T(1, 0.35) : T(0.5)));
  {
    // la lame : le dos vers le haut de l'aile, le tranchant vers le bas, une courbe douce
    const Lk = 12.5, dirB = mul(hd, -1);
    let haut = norm(sub([0, 1, 0], mul(dirB, dot([0, 1, 0], dirB))));
    const ame = (v) => add(add(tsuba, mul(dirB, 0.15 + Lk * v)), mul(haut, 0.9 * v * v));
    const n = 16;
    for (let i = 0; i < n; i++) {
      const a = i / n, b = (i + 1) / n, ca = ame(a), cb = ame(b), seg = len(sub(cb, ca)), dd = norm(sub(cb, ca));
      const hh = norm(sub(haut, mul(dd, dot(haut, dd)))), ep3 = cross(dd, hh), mil = lerp(ca, cb, 0.5);
      libre(P.poignees, mil, seg / 2 + 1.0, (p) => {
        const q = sub(p, mil), l = [dot(q, hh), dot(q, dd), dot(q, ep3)];
        const v = clamp(a + (b - a) * (l[1] / seg + 0.5));
        const larg = v < 0.9 ? 0.48 : 0.48 * Math.sqrt(Math.max(0, 1 - (v - 0.9) / 0.1)) + 0.02;
        const bas = -larg, haut2 = v < 0.9 ? 0.46 : 0.46 - 1.8 * (v - 0.9) * 2.6;
        const cy = (bas + haut2) / 2, demi = Math.max(0.03, (haut2 - bas) / 2);
        const ep = 0.16 * (1 - 0.7 * clamp((cy - l[0]) / demi));
        return [Math.max(Math.abs(l[0] - cy) - demi, Math.abs(l[1]) - seg / 2 - 0.02, Math.abs(l[2]) - Math.max(0.04, ep)), [l[0] - bas, v], null];
      }, ([dBas, v]) => (dBas < 0.16 ? T(1, 0.6) : Math.abs(dBas - 0.34 - 0.06 * Math.sin(v * 60)) < 0.05 ? T(0.95, 0.3) : T(0.55, 0.06)));
    }
  }
  const wd = sub(W, H), wdp = norm(sub(wd, mul(hd, dot(wd, hd)))), thP = Math.atan2(dot(wdp, e2), dot(wdp, e1));
  ell(P.ailes, add(add(H, mul(wdp, 0.95)), mul(hd, 0.25)), [0.8, 0.8, 0.6], plein(0.72));
  const doigt = (off, th0, th1, r0) => {
    const pts = [];
    for (let i = 0; i <= 5; i++) { const th = th0 + ((th1 - th0) * i) / 5; pts.push(add(add(H, mul(hd, off)), mul(add(mul(e1, Math.cos(th)), mul(e2, Math.sin(th))), 0.8))); }
    chaine(P.ailes, pts, [r0, r0 * 0.95, r0 * 0.88, r0 * 0.78, r0 * 0.6, 0.04], (l) => (l[1] > 0.8 ? T(1, 0.35) : T(0.72)));
  };
  for (let k = 0; k < 4; k++) doigt(-0.5 + 0.42 * k, thP + 0.55, thP + 4.2, 0.26);
  doigt(-0.85, thP - 0.5, thP - 2.6, 0.27);

  // les tuiles, rang par rang sous le bord, décalées d'une demi-tuile d'un rang à l'autre
  os = AI;
  for (let r = 0; r < 22; r++) {
    const yr = (x) => HAUT_AILE(x) - 0.85 - 1.08 * r;
    let x = 0, arc = 0, prochain = r % 2 ? 0 : 0.85, prec = [0, yr(0)];
    while (x < 24.3) {
      x += 0.04;
      const cur = [x, yr(x)];
      arc += Math.hypot(cur[0] - prec[0], cur[1] - prec[1]);
      prec = cur;
      if (arc < prochain) continue;
      prochain += 1.62;
      const jag = 0.55 * hache(r, Math.round(x * 10), s + 3);
      if (cur[1] < BAS_AILE(x) - 0.3 + jag) continue;
      const c = PA(s, cur, -0.05 - 0.05 * r), c2 = PA(s, [x + 0.05, yr(x + 0.05)], -0.05 - 0.05 * r);
      const X = norm(sub(c2, c));
      let nw = [0, 0, -1];
      nw = norm(sub(nw, mul(X, dot(nw, X))));
      let u = norm(cross(nw, X));
      if (u[1] < 0) u = mul(u, -1);
      const inc = 0.24 + 0.08 * hache(r, Math.round(x * 7), 9), Y = norm(sub(mul(u, Math.cos(inc)), mul(nw, Math.sin(inc))));
      const Z = cross(X, Y), teinteTuile = BASE - 0.08 + 0.12 * hache(r, Math.round(x * 3), s + 11);
      const xa = x;
      boite(P.plumes, c, [X, Y, Z], [0.84, 0.7, 0.07], 0.04, (l, h) => {
        const ex = Math.min(h[0] - Math.abs(l[0]), h[1] - Math.abs(l[1]));
        return ex < 0.13 ? T(1, 0.3) : T(teinteTuile + 0.06 * (l[1] / h[1]));
      });
      const V = VOLUMES[VOLUMES.length - 1];
      V.peau = () => (xa < 3 ? [O.torse, AI, lisse(0.4, 3, xa)] : [AI, BO, lisse(7.5, 14, xa)]);
    }
  }
}

// ---- les épées (dans les poings) : la poignée tressée, le pommeau, une garde ouvragée à pointes
function poignee(piece, w, d, ax, noire) {
  const sombre = noire ? T(0.08, 0, 2) : T(0.28), clair = noire ? T(0.5, 0.12) : T(0.8, 0.12);
  libre(piece, add(w, mul(d, -0.65)), 2, (p) => {
    const q = sub(p, add(w, mul(d, -0.65))), l = [dot(q, ax[0]), dot(q, ax[1]), dot(q, ax[2])];
    return [Math.hypot(l[0], l[2]) - 0.36 + Math.max(0, Math.abs(l[1]) - 1.15) * 3, l, null];
  }, (l) => (fract(l[1] * 2.2 + Math.atan2(l[2], l[0]) / Math.PI) < 0.5 ? sombre : clair));
  cone(piece, add(w, mul(d, -1.95)), add(w, mul(d, -2.25)), 0.55, 0.3, () => clair);
  boite(piece, add(w, mul(d, 0.65)), ax, [1.25, 0.14, 0.55], 0.08, () => clair);
  boite(piece, add(w, mul(d, 0.88)), ax, [0.85, 0.12, 0.42], 0.06, () => (noire ? T(0.1, 0, 2) : T(0.6)));
  for (const sc of [-1, 1]) cone(piece, add(add(w, mul(d, 0.65)), mul(ax[0], sc * 1.2)), add(add(w, mul(d, 0.2)), mul(ax[0], sc * 1.75)), 0.18, 0.03, () => clair);
}
// les deux épées de flammes : une âme qui ondule, des langues de flammes serrées le long des deux tranchants qui remontent
// vers la pointe, une longue pointe de flamme, un tourbillon de flammes à la garde. À droite l'épée de flammes du
// chevalier (claire) ; à gauche l'épée sombre d'Amaterasu (flammes noires, bouts lumineux, une fumée noire au bout).
function epeeFlammes(piece, w, d, noire) {
  const ax = axes(d, [1, 0, 0]), plat = ax[0], ind = ax[2];
  poignee(piece, w, d, ax, noire);
  if (noire) ell(piece, add(w, mul(d, 0.66)), [0.3, 0.3, 0.3], () => T(0.6, 0.9));
  const coeur = noire ? () => T(0.04, 0.0, 2) : () => T(0.95, 0.4);
  const langue = noire ? (l) => T(0.05, 0.02 + 0.55 * l[1] ** 3, 2) : flamme;
  const L = 10.5, ame = (x) => add(w, mul(d, 1.0 + L * x)), largeur = (x) => 0.52 * (1 - 0.3 * x);
  for (let i = 0; i < 12; i++) {
    const a = i / 12, b = (i + 1) / 12;
    cone(piece, add(ame(a), mul(plat, 0.08 * Math.sin(a * 37))), add(ame(b), mul(plat, 0.08 * Math.sin(b * 37))), largeur(a), largeur(b), coeur);
  }
  for (const cote of [-1, 1]) for (let n = 0; n < 15; n++) {
    const s0 = 0.03 + n * 0.062 + hache(n, cote + 5, noire ? 4 : 1) * 0.02;
    if (s0 > 0.95) continue;
    const base = add(ame(s0), mul(plat, cote * largeur(s0) * 0.8)), lf = (1.0 + 1.3 * hache(n, cote + 3, noire ? 6 : 2)) * (1 - 0.45 * s0);
    const dir = norm(add(mul(d, 0.8), mul(plat, cote * (0.45 + 0.3 * hache(n, 7, cote + 2)))));
    chaine(piece, [base, add(base, mul(dir, lf * 0.5)), add(add(base, mul(dir, lf)), mul(plat, -cote * 0.2 * lf))], [0.32, 0.2, 0.03], langue);
  }
  // la pointe : une vraie pointe de lame bien nette, et des flammes qui s'enroulent autour pour s'y rejoindre
  const pointe = add(ame(1), mul(d, 1.9));
  cone(piece, ame(0.97), pointe, largeur(0.97), 0.02, coeur);
  for (let n = 0; n < 4; n++) {
    const a0 = (n / 4) * Math.PI * 2 + 0.4, pts = [];
    for (let i = 0; i <= 5; i++) {
      const u = i / 5, ang = a0 + u * 1.9, rr = (largeur(0.85) + 0.32) * (1 - u) ** 1.2;
      pts.push(add(lerp(ame(0.84), add(pointe, mul(d, 0.5)), u), add(mul(plat, Math.cos(ang) * rr), mul(ind, Math.sin(ang) * rr))));
    }
    chaine(piece, pts, [0.24, 0.22, 0.18, 0.13, 0.08, 0.02], langue);
  }
  for (let n = 0; n < 6; n++) {
    const a = (n / 6) * Math.PI * 2, r = add(mul(plat, Math.cos(a)), mul(ind, Math.sin(a))), autour = norm(cross(d, r)), base = add(w, mul(d, 1.0)), pts = [];
    for (let i = 0; i <= 4; i++) { const u = i / 4, t = add(mul(r, Math.cos(u * 1.6)), mul(autour, Math.sin(u * 1.6))); pts.push(add(add(base, mul(t, 0.9 * u + 0.2)), mul(d, 1.1 * u * u))); }
    chaine(piece, pts, [0.28, 0.24, 0.18, 0.1, 0.02], langue);
  }
}
os = O.mainD;
epeeFlammes(P.flamme, POIGNET.D, norm([-0.4, -0.8, 0.45]), false);
os = O.mainG;
epeeFlammes(P.sombre, POIGNET.G, norm([0.4, -0.8, 0.45]), true);

// ================================================================== les cubes, articulation par articulation

const PAS = Number(process.env.PAS ?? 0.125);
const sortie = [];
const debut = Date.now();
/** Pour le mod (MOD=1) : les faces visibles de chaque cube (coin, sens, teinte, lueur, sorte, pièce), groupées par articulations. */
const FACES = process.env.MOD ? new Map() : null;
const SENS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
for (let b = 0; b < OS.length; b++) {
  const vols = VOLUMES.filter((v) => v.os === b);
  if (!vols.length) continue;
  const distance = (p) => {
    let best = 1e9;
    for (const V of vols) {
      const dc = Math.hypot(p[0] - V.c[0], p[1] - V.c[1], p[2] - V.c[2]) - V.R;
      if (dc > best) continue;
      const d = V.d(p)[0];
      if (d < best) best = d;
    }
    return best;
  };
  const proche = (p) => {
    let best = 1e9, vol = null, loc = null;
    for (const V of vols) {
      const dc = Math.hypot(p[0] - V.c[0], p[1] - V.c[1], p[2] - V.c[2]) - V.R;
      if (dc > best + 0.3) continue;
      const r = V.d(p);
      if (r[0] < best) { best = r[0]; vol = V; loc = r; }
    }
    return { vol, loc };
  };
  const MIN = [0, 1, 2].map((i) => Math.floor(Math.min(...vols.map((v) => v.c[i] - v.R)))), MAX = [0, 1, 2].map((i) => Math.ceil(Math.max(...vols.map((v) => v.c[i] + v.R))));
  MIN[1] = Math.max(MIN[1], 0);
  const nx = Math.ceil((MAX[0] - MIN[0]) / PAS), ny = Math.ceil((MAX[1] - MIN[1]) / PAS), nz = Math.ceil((MAX[2] - MIN[2]) / PAS);
  const plein = new Uint8Array(nx * ny * nz);
  const centre = (i, j, k) => [MIN[0] + (i + 0.5) * PAS, MIN[1] + (j + 0.5) * PAS, MIN[2] + (k + 0.5) * PAS];
  const B = 8, marge = (B / 2) * PAS * Math.sqrt(3) + 0.6;
  for (let bk = 0; bk < nz; bk += B) for (let bj = 0; bj < ny; bj += B) for (let bi = 0; bi < nx; bi += B) {
    const d = distance([MIN[0] + (bi + B / 2) * PAS, MIN[1] + (bj + B / 2) * PAS, MIN[2] + (bk + B / 2) * PAS]);
    if (d > marge) continue;
    for (let k = bk; k < Math.min(nz, bk + B); k++) for (let j = bj; j < Math.min(ny, bj + B); j++) for (let i = bi; i < Math.min(nx, bi + B); i++) {
      if (d < -marge || distance(centre(i, j, k)) < 0) plein[(k * ny + j) * nx + i] = 1;
    }
  }
  const at = (i, j, k) => i >= 0 && j >= 0 && k >= 0 && i < nx && j < ny && k < nz && plein[(k * ny + j) * nx + i] === 1;
  const pasAO = Math.max(1, Math.round(0.375 / PAS));
  let n0 = sortie.length;
  for (let k = 0; k < nz; k++) for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) {
    if (!at(i, j, k)) continue;
    if (at(i + 1, j, k) && at(i - 1, j, k) && at(i, j + 1, k) && at(i, j - 1, k) && at(i, j, k + 1) && at(i, j, k - 1)) continue;
    let n = 0, tot = 0;
    for (let c = -pasAO; c <= pasAO; c += pasAO) for (let bb = -pasAO; bb <= pasAO; bb += pasAO) for (let a = -pasAO; a <= pasAO; a += pasAO) { tot++; if (at(i + a, j + bb, k + c)) n++; }
    const creux = Math.max(0, n / tot - 0.45);
    const c0 = centre(i, j, k), h = PAS * 0.8, f = (dx, dy, dz) => distance([c0[0] + dx, c0[1] + dy, c0[2] + dz]);
    const nr = norm([f(h, 0, 0) - f(-h, 0, 0), f(0, h, 0) - f(0, -h, 0), f(0, 0, h) - f(0, 0, -h)]);
    const { vol, loc } = proche(c0), c = vol.teinte(loc[1], loc[2]), pk = vol.peau ? vol.peau(c0) : [b, b, 0];
    const grain = (hache(i + MIN[0] * 97, j, k + b * 13) - 0.5) * 0.08;
    const teinte = clamp(c.t + grain) * (1 - creux * 0.5);
    sortie.push(c0[0], c0[1], c0[2], nr[0], nr[1], nr[2], teinte, c.e, c.s, vol.piece, pk[0], pk[1], pk[2]);
    if (FACES) {
      // le poids arrondi au quart (le jeu dessine un morceau par mélange d'articulations) ; les épées à part (elles apparaissent après)
      let [a, bb, w] = pk;
      w = Math.round(w * 4) / 4;
      if (w <= 0) bb = a, w = 0;
      if (w >= 1) a = bb, w = 0;
      const epee = vol.piece === P.flamme || vol.piece === P.sombre ? 1 : 0, cle = a + "," + bb + "," + w + "," + epee;
      if (!FACES.has(cle)) FACES.set(cle, { a, b: bb, w, epee, f: [] });
      const g = FACES.get(cle).f;
      SENS.forEach(([dx, dy, dz], d) => {
        if (at(i + dx, j + dy, k + dz)) return;
        g.push(Math.round((c0[0] - PAS / 2) / PAS), Math.round((c0[1] - PAS / 2) / PAS), Math.round((c0[2] - PAS / 2) / PAS), d,
          Math.round(teinte * 255), Math.round(clamp(c.e / 1.5) * 255), c.s);
      });
    }
  }
  console.log(OS[b][0], (sortie.length - n0) / 13, "cubes");
}
const n = sortie.length / 13, out = new Float32Array(2 + sortie.length);
out[0] = n;
out[1] = PAS;
out.set(sortie, 2);
fs.writeFileSync(path.join(ICI, "susano.bin"), Buffer.from(out.buffer));
if (FACES) {
  // le fichier du mod : PAS, les articulations (pivot, parent), puis les groupes (articulations a et b, poids, épée ?, faces)
  const groupes = [...FACES.values()], nf = groupes.reduce((t, g) => t + g.f.length / 7, 0);
  const vue = new DataView(new ArrayBuffer(8 + OS.length * 16 + 4 + groupes.length * 17 + nf * 10));
  let o = 0;
  vue.setFloat32(o, PAS); o += 4;
  vue.setInt32(o, OS.length); o += 4;
  for (const [, pv, parent] of OS) { for (const x of pv) { vue.setFloat32(o, x); o += 4; } vue.setInt32(o, parent); o += 4; }
  vue.setInt32(o, groupes.length); o += 4;
  for (const g of groupes) {
    vue.setInt32(o, g.a); o += 4; vue.setInt32(o, g.b); o += 4; vue.setFloat32(o, g.w); o += 4; vue.setUint8(o++, g.epee); vue.setInt32(o, g.f.length / 7); o += 4;
    for (let q = 0; q < g.f.length; q += 7) {
      for (let k = 0; k < 3; k++) { vue.setInt16(o, g.f[q + k]); o += 2; }
      vue.setUint8(o++, g.f[q + 3]); vue.setUint8(o++, g.f[q + 4]); vue.setUint8(o++, g.f[q + 5]); vue.setUint8(o++, g.f[q + 6]);
    }
  }
  const dest = path.join(ICI, "../../../mod-cabane/src/main/resources/assets/cabane/susano4");
  fs.mkdirSync(dest, { recursive: true });
  fs.writeFileSync(path.join(dest, "susano.bin"), Buffer.from(vue.buffer));
  console.log("mod :", groupes.length, "groupes,", nf, "faces,", Math.round(vue.byteLength / 1024), "Ko");
}
fs.writeFileSync(path.join(ICI, "susano-os.json"), JSON.stringify({ os: OS.map(([nom, pivot, parent]) => ({ nom, pivot, parent })), pieces: PIECES }));
console.log("surface", n, "cubes,", Math.round(out.byteLength / 1024 / 1024 * 10) / 10, "Mo,", Math.round((Date.now() - debut) / 1000), "s");
