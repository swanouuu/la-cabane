// La forme du grand serpent de l'ulti de la marque 3 (en blocs) : la tête (crâne, yeux, cornes, crocs, mâchoire, langue)
// et le corps (des anneaux), leurs couleurs, et les cubes de surface. Sert à ulti.html (l'aperçu) et à jeu.mjs (le mod).
import { hache } from '../apercu/main-voxel.mjs';
export const clamp = (x, a, b) => Math.max(a, Math.min(b, x));
export const lisse = (t) => { t = clamp(t, 0, 1); return t * t * (3 - 2 * t); };
export const lerp = (a, b, k) => a + (b - a) * k;
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const lerpV = (a, b, k) => [lerp(a[0], b[0], k), lerp(a[1], b[1], k), lerp(a[2], b[2], k)];
const C = (r, g, b) => [r / 255, g / 255, b / 255];
const mixC = (a, b, k) => lerpV(a, b, clamp(k, 0, 1));

export const LH = 17, CHARN = 2.5, LB = 100;
export const largeur = (x) => { const k = clamp(x / LH, 0, 1); let w = k < 0.3 ? lerp(4.3, 6.3, lisse(k / 0.3)) : lerp(6.3, 2.7, Math.pow((k - 0.3) / 0.7, 1.15)); if (k > 0.83) w *= Math.sqrt(Math.max(0, 1 - ((k - 0.83) / 0.175) ** 2)); return w; };
export const hauteur = (x) => { const k = clamp(x / LH, 0, 1); let h = lerp(3.7, 2.0, k) + 0.7 * Math.exp(-(((k - 0.52) / 0.1) ** 2)); if (k > 0.86) h *= 0.45 + 0.55 * Math.sqrt(Math.max(0, 1 - ((k - 0.86) / 0.15) ** 2)); return h; };
export const hMach = (x) => { const k = clamp(x / LH, 0, 1); let h = lerp(2.7, 1.1, k); if (k > 0.86) h *= 0.45 + 0.55 * Math.sqrt(Math.max(0, 1 - ((k - 0.86) / 0.13) ** 2)); return h; };
/** Le rayon du corps à la distance s du cou : un peu plus fin au cou, énorme au milieu, la queue en pointe. */
export const rayon = (s) => (s < 12 ? lerp(4.1, 5.2, lisse(s / 12)) : s < 38 ? 5.2 : Math.max(0.3, 5.2 * Math.pow(1 - (s - 38) / (LB - 38), 0.85)));

// les couleurs (le violet du serpent d'Orochimaru, le ventre crème)
export const DOS = C(98, 52, 146), FLANC = C(146, 96, 188), MOTIF = C(68, 32, 104), TRAIT = C(36, 16, 56), VENTRE = C(226, 210, 182);
export const PALAIS = C(112, 26, 48), BOUCHE = C(158, 44, 68), LEVRE = C(214, 196, 186), NOIR = C(18, 8, 16), DENT = C(246, 242, 228);
const OEIL = C(255, 214, 40), OEIL_BORD = C(236, 128, 18), LANGUE = C(200, 30, 52);

function capsule(p, a, b, ra, rb) {
  const ab = sub(b, a), k = clamp(dot(sub(p, a), ab) / dot(ab, ab), 0, 1);
  return [Math.hypot(...sub(p, add(a, mul(ab, k)))) - lerp(ra, rb, k), k];
}
function chaine(p, pts, rs) {
  let m = 1e9, kk = 0;
  for (let i = 0; i + 1 < pts.length; i++) { const [d, k] = capsule(p, pts[i], pts[i + 1], rs[i], rs[i + 1]); if (d < m) { m = d; kk = (i + k) / (pts.length - 1); } }
  return [m, kk];
}
// les cornes au-dessus des yeux, qui partent vers l'arrière (deux grandes, deux petites)
export const CORNES = [
  [[[10.2, 2.9, 3.3], [5.2, 5.6, 4.9], [-0.8, 7.5, 6.0], [-4.6, 8.0, 6.4]], [1.3, 1.0, 0.55, 0.12]],
  [[[7.6, 3.0, 1.5], [3.2, 5.8, 2.4], [-1.2, 6.8, 2.9]], [0.85, 0.55, 0.12]],
];
export const OEIL_X = 9.2, OEIL_Y = 2.05, OEIL_R = 1.45;
export const oeilZ = () => largeur(OEIL_X) - 0.45;
export function crocs() {
  const l = [];
  const xf = LH - 3.2, wf = largeur(xf);
  for (const s of [-1, 1]) {
    l.push([[xf, 0.3, s * (wf - 1.3)], [xf - 0.9, -3.7, s * (wf - 1.5)], 0.75, 0.07]);
    for (let x = 4; x < LH - 4.4; x += 1.25) l.push([[x, 0.2, s * (largeur(x) - 0.55)], [x - 0.15, -0.95, s * (largeur(x) - 0.6)], 0.33, 0.05]);
  }
  return l;
}
export function dentsBas() {
  const l = [];
  for (const s of [-1, 1]) for (let x = 4.6; x < LH - 2.5; x += 1.25) { const w = largeur(x) * 0.93; l.push([[x, -0.2, s * (w - 0.55)], [x + 0.1, 0.85, s * (w - 0.6)], 0.32, 0.05]); }
  return l;
}

/** Ce qu'il y a au point (x, y, z) du crâne (repère de la tête, x vers l'avant depuis le cou) : 0 rien, sinon une région. */
export function dansCrane(x, y, z, CR) {
  const ez = oeilZ();
  for (const s of [-1, 1]) if (Math.hypot(x - OEIL_X, y - OEIL_Y, z - s * ez) < OEIL_R) return 2;
  for (const [pts, rs] of CORNES) for (const s of [-1, 1]) if (chaine([x, y, z * s], pts, rs)[0] < 0) return 3;
  for (const [a, b, ra, rb] of CR) if (capsule([x, y, z], a, b, ra, rb)[0] < 0) return 4;
  if (x < -1.5 || x > LH || y < 0) return 0;
  const w = largeur(x), h = hauteur(x);
  return Math.pow(Math.abs(z) / w, 2.4) + (y / h) ** 2 <= 1 ? 1 : 0;
}
export function dansMachoire(x, y, z, DB) {
  for (const [a, b, ra, rb] of DB) if (capsule([x, y, z], a, b, ra, rb)[0] < 0) return 4;
  if (x < -1.5 || x > LH * 0.97 || y >= 0) return 0;
  const w = largeur(x) * 0.93, h = hMach(x);
  return Math.pow(Math.abs(z) / w, 2.4) + (y / h) ** 2 <= 1 ? 1 : 0;
}
// la langue (repère de sa base) : longue, rouge, fourchue
const LANGUE_PTS = [[0, 0, 0], [LH - 4, 0.2, 0]];
export function dansLangue(x, y, z) {
  if (capsule([x, y, z], LANGUE_PTS[0], LANGUE_PTS[1], 0.55, 0.42)[0] < 0) return 1;
  for (const s of [-1, 1]) if (capsule([x, y, z], LANGUE_PTS[1], [LH + 0.6, 0.5, s * 1.1], 0.42, 0.1)[0] < 0) return 1;
  return 0;
}

export function couleurCrane(x, y, z, region) {
  const h = hache(Math.round(x * 8), Math.round(y * 8), Math.round(z * 8));
  if (region === 3) {
    let k = 0;
    for (const [pts, rs] of CORNES) { const [d, kk] = chaine([x, y, Math.abs(z)], pts, rs); if (d < 0.4) k = Math.max(k, kk); }
    return mul(mixC(C(64, 32, 90), C(206, 184, 222), k * 1.1), 0.9 + 0.15 * h);
  }
  if (region === 4) return mul(DENT, 0.92 + 0.08 * h);
  const w = largeur(x), hh = hauteur(x), az = Math.abs(z);
  if (y < 0.55) return az < w - 0.8 ? mul(PALAIS, 0.85 + 0.2 * h) : LEVRE;
  if (x > LH - 1.8 && x < LH - 0.6 && Math.abs(az - 1.15) < 0.42 && y > hh - 1.1) return NOIR;
  if (x < OEIL_X + 0.6 && x > -1.6 && Math.abs(y - OEIL_Y - (OEIL_X - x) * 0.06) < 0.62 && az > w * 0.66) return mul(TRAIT, 0.9 + 0.2 * h);
  // les grandes écailles du dessus
  const gx = Math.floor(x / 1.5), gz = Math.floor((az + (gx & 1) * 0.75) / 1.5);
  let c = mixC(DOS, FLANC, lisse((az / w - 0.45) / 0.55));
  // une marque sombre en V sur le dessus du crâne
  if (az < 2.4 - (x - 2) * 0.22 && az > 1.2 - (x - 2) * 0.22 && x > 1 && x < 10) c = MOTIF;
  c = mul(c, 0.86 + 0.18 * hache(gx, gz, 3));
  if (x / 1.5 - gx < 0.17) c = mul(c, 0.74);
  return c;
}
export function couleurMachoire(x, y, z, region) {
  const h = hache(Math.round(x * 8), Math.round(y * 8), Math.round(z * 8));
  if (region === 4) return mul(DENT, 0.92 + 0.08 * h);
  const w = largeur(x) * 0.93, az = Math.abs(z);
  if (y > -0.6) return az < w - 0.85 ? mul(BOUCHE, 0.85 + 0.2 * h) : LEVRE;
  if (y < -hMach(x) * 0.55 || az < w * 0.5) return mul(VENTRE, (x % 1.3 < 0.26 ? 0.75 : 1) * (0.94 + 0.08 * h));
  return mul(FLANC, 0.9 + 0.15 * h);
}

/** Les cubes de surface d'un volume : region(x, y, z) dans la boîte b, au pas p. */
export function voxeliser(b, p, region, couleur) {
  const [x0, y0, z0, x1, y1, z1] = b;
  const nx = Math.ceil((x1 - x0) / p), ny = Math.ceil((y1 - y0) / p), nz = Math.ceil((z1 - z0) / p);
  const g = new Uint8Array((nx + 2) * (ny + 2) * (nz + 2));
  const I = (i, j, k) => ((i + 1) * (ny + 2) + (j + 1)) * (nz + 2) + (k + 1);
  for (let i = 0; i < nx; i++) for (let j = 0; j < ny; j++) for (let k = 0; k < nz; k++) g[I(i, j, k)] = region(x0 + (i + 0.5) * p, y0 + (j + 0.5) * p, z0 + (k + 0.5) * p);
  // faces : les côtés libres de chaque cube (+x, -x, +y, -y, +z, -z), idx : sa place dans la grille (pour le jeu)
  const pos = [], col = [], reg = [], faces = [], idx = [];
  for (let i = 0; i < nx; i++) for (let j = 0; j < ny; j++) for (let k = 0; k < nz; k++) {
    const r = g[I(i, j, k)];
    if (!r) continue;
    const m = (g[I(i + 1, j, k)] ? 0 : 1) | (g[I(i - 1, j, k)] ? 0 : 2) | (g[I(i, j + 1, k)] ? 0 : 4) | (g[I(i, j - 1, k)] ? 0 : 8) | (g[I(i, j, k + 1)] ? 0 : 16) | (g[I(i, j, k - 1)] ? 0 : 32);
    if (!m) continue;
    const x = x0 + (i + 0.5) * p, y = y0 + (j + 0.5) * p, z = z0 + (k + 0.5) * p;
    pos.push(x, y, z);
    col.push(...couleur(x, y, z, r));
    reg.push(r);
    faces.push(m);
    idx.push(i, j, k);
  }
  return { pos, col, reg, faces, idx, origine: [x0, y0, z0] };
}

/** Le corps : des anneaux de cubes (s le long du corps, a vers le dos, b sur le côté), le ventre crème, des losanges sur le dos. */
export function construireCorps(p) {
  const S = [], Aa = [], Bb = [], col = [], anneaux = [];
  let n = 0;
  for (let s = 0, ia = 0; s <= LB; s += p, ia++) {
    const R = rayon(s);
    const per = 2 * Math.PI * Math.sqrt((R * R + (0.86 * R) ** 2) / 2);
    const m = Math.max(5, Math.round(per / p));
    const debut = n;
    const ajouter = (a, b, c) => { S.push(s); Aa.push(a); Bb.push(b); col.push(...c); n++; };
    for (let i = 0; i < m; i++) {
      const th = (2 * Math.PI * (i + (ia & 1) * 0.5)) / m - Math.PI;
      const a = Math.cos(th) >= 0 ? 0.86 * R * Math.cos(th) : 0.64 * R * Math.cos(th);
      ajouter(a, R * Math.sin(th), couleurCorps(s, th, ia, i));
    }
    // une petite crête sur le dos
    if (s > 1.5 && s < LB - 10 && ia % 3 === 0) ajouter(0.86 * R + p * 0.8, 0, mul(TRAIT, 1.1));
    anneaux.push([s, debut, n]);
  }
  return { S: new Float32Array(S), A: new Float32Array(Aa), B: new Float32Array(Bb), col, anneaux, n };
}
export function couleurCorps(s, th, ia, i) {
  const at = Math.abs(th), h = hache(ia, i, 5);
  let c;
  if (at > 2.3) c = mul(VENTRE, s % 1.3 < 0.28 ? 0.74 : 1);
  else if (at > 2.08) c = mul(TRAIT, 1.4);
  else {
    c = mixC(DOS, FLANC, lisse((at - 0.5) / 1.3));
    // une chaîne de losanges sur le dos
    const u = ((s + 1.5) % 7) - 3.5, d = Math.abs(u) / 3.2 + at / 0.95;
    if (d < 1) c = d > 0.7 ? TRAIT : MOTIF;
    // des taches sur les flancs, en quinconce
    const v = ((s + 5) % 7) - 3.5, e = (v / 2.2) ** 2 + ((at - 1.55) / 0.4) ** 2;
    if (e < 1) c = e > 0.5 ? TRAIT : MOTIF;
  }
  return mul(c, (0.9 + 0.14 * h) * ((ia + (i >> 1)) & 1 ? 0.95 : 1.03));
}

export function construireSerpent(p) {
  const CR = crocs(), DB = dentsBas();
  const crane = voxeliser([-1.6, -4.3, -7.4, LH + 0.4, 8.6, 7.4], p, (x, y, z) => dansCrane(x, y, z, CR), (x, y, z, r) => {
    if (r === 2) {
      const ez = oeilZ(), s = Math.sign(z), dd = Math.hypot(x - OEIL_X, y - OEIL_Y, z - s * ez) / OEIL_R;
      return Math.abs(x - OEIL_X) < 0.3 ? NOIR : dd > 0.8 ? OEIL_BORD : OEIL;
    }
    return couleurCrane(x, y, z, r);
  });
  const machoire = voxeliser([-1.6, -3.0, -6.6, LH, 1.2, 6.6], p, (x, y, z) => dansMachoire(x, y, z, DB), couleurMachoire);
  const langue = voxeliser([-0.8, -0.8, -1.8, LH + 1.2, 1.2, 1.8], Math.min(p, 0.25), dansLangue, (x) => mul(LANGUE, x > LH - 4 ? 0.8 : 1));
  return { p, crane, machoire, langue, corps: construireCorps(p) };
}

