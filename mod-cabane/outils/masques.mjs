// Les masques ninja (accessoire/Masques.java), d'après les images de l'hôte : le démon Hannya (cornes et serpent), le
// renard Kitsune (oreilles, pompons et grelots), le masque jaune de Tobi, la spirale orange, le masque de guerre d'Obito et
// le masque blanc du chasseur à la vague rouge. Pour chacun, écrit :
//  - modeles/masque_<nom>.json et textures/entity/masque/<nom>.png : ses pavés, dans le repère de la tête d'un joueur, quatre
//    fois plus fins qu'un pixel (client/RenduMasques les dessine 4 fois plus petits) ;
//  - textures/item/masque_<nom>.png (64 × 64) et son modèle d'objet : le masque vu de face.
// Repère de la tête (celui de Minecraft) : x de -4 à 4 (-x = la droite du joueur), y de -8 (le haut) à 0 (le cou), z = -4
// le visage. Le masque est en relief : une surface bombée faite de petits pavés de 0,5 pixel, chacun avancé selon le relief
// du masque (le nez, les arcades, les pommettes, le museau du renard, les sillons de la spirale…), et éclairée d'en haut à
// gauche. Les trous des yeux sont transparents. Appelé par generer.mjs (ou tout seul : node mod-cabane/outils/masques.mjs).
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const RES = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources", "assets", "cabane");
/** Quatre texels par pixel du modèle : un peu plus fin qu'un skin, mais ça reste du pixel (on est sur Minecraft). */
const K = 4;
const DEG = Math.PI / 180;

const hache = (i, j, k) => { let h = (i * 374761393 + j * 668265263 + k * 2147483647) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };
const clair = (c, k) => c.slice(0, 3).map((v) => Math.max(0, Math.min(255, v * k)));
const mix = (a, b, k) => a.map((v, i) => v + (b[i] - v) * Math.max(0, Math.min(1, k)));
const grain = (c, x, y, graine, force = 0.035) => clair(c, 1 - force + 2 * force * hache(Math.round(x * K), Math.round(y * K), graine));
const norme = (v) => { const l = Math.hypot(...v) || 1; return v.map((c) => c / l); };
const ajoute = (a, b) => a.map((v, i) => v + b[i]);
const fois = (a, k) => a.map((v) => v * k);
const tournerZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];
const dans = (x, y, cx, cy, rx, ry) => ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1;
/** Une bosse douce, de hauteur h, autour de (cx, cy). */
const bosse = (x, y, cx, cy, sx, sy, h) => h * Math.exp(-(((x - cx) / sx) ** 2 + ((y - cy) / sy) ** 2) / 2);
/** Distance d'un point au segment ab. */
const auSegment = (x, y, ax, ay, bx, by) => {
  const dx = bx - ax, dy = by - ay, t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
  return Math.hypot(x - ax - t * dx, y - ay - t * dy);
};
const NOIR = [28, 26, 30];
const INTERIEUR = [40, 36, 40];

// ---------------------------------------------------------------- un modèle : des parties et leurs pavés
function nouveauModele() {
  const parties = [];
  const partie = (nom, pivot = [0, 0, 0], rotation = [0, 0, 0], parent = null) => {
    const p = { nom, pivot, rotation, parent, boites: [] };
    p.boite = (a, b, peint) => { p.boites.push({ a, b, peint }); return p; };
    parties.push(p);
    return p;
  };
  return { parties, partie };
}

/**
 * Le masque, en volume. L'ovale (demi-largeur a, demi-hauteur b, centré en cy ; {@code profil} le resserre par endroits)
 * est un bloc plein posé contre le visage, bombé vers l'avant, et {@code relief(x, y)} l'avance encore (en pixels, doublé
 * par {@code force}) : le nez, les arcades, les pommettes, le museau… Il est découpé en pavés de 0,5 pixel, chacun avancé à
 * la hauteur de son milieu ; ce qui dépasse sur les côtés de la tête repart vers l'arrière le long des joues, et le haut
 * déborde sur le dessus de la tête : le masque enveloppe le visage. Un lien fait le tour de la tête. L'avant de chaque pavé
 * est peint, et ombré d'un seul ton d'après la pente du relief en son milieu (la lumière vient d'en haut à gauche) : ça
 * reste pixelisé, comme du Minecraft.
 * {@code face(x, y)} : la couleur, ou null pour un trou.
 */
function plaque(racine, { a = 4.35, b = 4.7, cy = -4.0, profil = () => 1, relief = () => 0, face, bord, force = 2, bombe = 0.8, lien = [44, 36, 38] }) {
  const pas = 0.5;
  const H = (x, y) => 0.3 + bombe * Math.pow(Math.max(0, 1 - (x / a) ** 2), 0.55) * Math.pow(Math.max(0, 1 - ((y - cy) / b) ** 2), 0.55) + force * relief(x, y);
  const ombre = (x, y) => {
    const e = 0.06, dx = (H(x + e, y) - H(x - e, y)) / (2 * e), dy = (H(x, y + e) - H(x, y - e)) / (2 * e);
    return Math.max(0.55, Math.min(1.25, 1 + 0.45 * dx + 0.6 * dy));
  };
  const avant = (x, y, cx, cy) => { const c = face(x, y); return c && clair(c, ombre(cx, cy)); };
  const cases = [];
  for (let y0 = cy - b; y0 < cy + b - 0.01; y0 += pas) {
    const ym = y0 + pas / 2, t = (ym - cy) / b;
    const demi = Math.round(a * Math.sqrt(Math.max(0, 1 - t * t)) * profil(t) * K) / K;
    if (demi < 0.5) continue;
    const xs = [-demi];
    for (let x = Math.ceil((-demi + 0.2) / pas) * pas; x < demi - 0.2; x += pas) xs.push(x);
    xs.push(demi);
    for (let k = 0; k < xs.length - 1; k++) {
      const x0 = xs[k], x1 = xs[k + 1], xm = (x0 + x1) / 2;
      // une case tout entière dans un trou : rien
      if ([[x0, y0], [x1, y0], [x0, y0 + pas], [x1, y0 + pas], [xm, ym]].every(([px, py]) => face(Math.max(x0 + 0.03, Math.min(x1 - 0.03, px)),
        Math.max(y0 + 0.03, Math.min(y0 + pas - 0.03, py))) === null)) continue;
      {
      const y1 = y0 + pas;
      // plein jusqu'au visage ; ce qui dépasse sur les côtés et au-dessus de la tête repart un peu vers l'arrière
      const zb = x0 >= 3.99 || x1 <= -3.99 ? -3.5 : y1 <= -7.99 ? -2.8 : -4.05;
      cases.push({ x0, x1, y0, y1, zf: Math.round((-4.12 - H(xm, ym)) * K) / K, zb });
    }
    }
  }
  for (const c of cases) {
    const { x0, x1, y0, y1, zf, zb } = c, ym = (y0 + y1) / 2, xm = (x0 + x1) / 2;
    // les tranches : sombres dans un trou, sinon la couleur du bord du pavé, à l'ombre
    const tranche = (dehors, dedans, k) => (face(...dehors) === null ? INTERIEUR : clair(face(...dedans) ?? bord, k));
    racine.boite([x0, y0, zf], [x1, y1, zb], (f, u, v) => {
      if (f === "avant") return avant(x0 + (u + 0.5) / K, y0 + (v + 0.5) / K, xm, ym);
      if (f === "arriere") return face(x1 - (u + 0.5) / K, y0 + (v + 0.5) / K) === null ? null : INTERIEUR;
      if (f === "droite") return tranche([x0 - 0.04, ym], [x0 + 0.03, ym], 0.88);
      if (f === "gauche") return tranche([x1 + 0.04, ym], [x1 - 0.03, ym], 0.88);
      if (f === "haut") return tranche([xm, y0 - 0.04], [xm, y0 + 0.03], 0.96);
      return tranche([xm, y1 + 0.04], [xm, y1 - 0.03], 0.78);
    });
  }
  // le lien, à hauteur des oreilles : des joues jusque derrière la tête
  const yl = cy - 0.55;
  for (const s of [-1, 1]) {
    racine.boite([s > 0 ? 4.0 : -4.28, yl - 0.2, -1.8], [s > 0 ? 4.28 : -4.0, yl + 0.2, 4.28], (f, u, v) => (v === 1 ? clair(lien, 1.25) : lien));
  }
  racine.boite([-4.0, yl - 0.2, 4.0], [4.0, yl + 0.2, 4.28], (f, u, v) => (v === 1 ? clair(lien, 1.25) : lien));
}

/** Une corne, une oreille, un serpent… : des tronçons le long d'un chemin, chacun tourné dans le sens du chemin. */
function troncons(m, racine, nom, points, epaisseur, peint) {
  for (let k = 0; k < points.length - 1; k++) {
    const p = points[k], q = points[k + 1], d = norme(q.map((v, i) => v - p[i]));
    const l = Math.hypot(...q.map((v, i) => v - p[i]));
    const w = typeof epaisseur === "function" ? epaisseur(k / (points.length - 1)) : epaisseur;
    const c = fois(ajoute(p, q), 0.5);
    // l'axe y du tronçon le long de d : rotation autour de x (pour z), puis de z
    const rx = Math.asin(Math.max(-1, Math.min(1, d[2]))), rz = Math.atan2(-d[0], d[1]);
    const ww = Math.round(w * K) / K;
    m.partie(`${nom}${k}`, c, [rx / DEG, 0, rz / DEG], racine)
      .boite([-ww / 2, -l / 2 - 0.08, -ww / 2], [ww / 2, l / 2 + 0.08, ww / 2], (f, u, v, nu, nv) => peint(f, u, v, k, nu, nv));
  }
}

/** Une courbe de corne : part de p0 dans la direction d0, tourne de {@code virage} degrés à chaque tronçon. */
function courbe(p0, d0, n, pas, virage) {
  const l = [p0];
  let p = p0, d = norme(d0);
  for (let k = 0; k < n; k++) {
    p = ajoute(p, fois(d, pas));
    l.push(p);
    d = norme(tournerZ(d, virage * DEG));
  }
  return l;
}

/** Un chemin lisse (Catmull-Rom) passant par ces points, découpé en n tronçons. */
function lisse(points, n) {
  const l = [];
  for (let s = 0; s <= n; s++) {
    const t = (s / n) * (points.length - 1), i = Math.min(points.length - 2, Math.floor(t)), f = t - i;
    const p0 = points[Math.max(0, i - 1)], p1 = points[i], p2 = points[i + 1], p3 = points[Math.min(points.length - 1, i + 2)];
    l.push([0, 1, 2].map((k) => 0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * f + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * f * f
      + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * f * f * f)));
  }
  return l;
}

// ---------------------------------------------------------------- les six masques
const MASQUES = {};

// le démon Hannya : visage blanc creusé de rides, cheveux gris foncé, sourire à crocs, cornes olive, un serpent qui l'enlace
MASQUES.hannya = (m) => {
  const r = m.partie("masque");
  const BLANC = [230, 230, 226], GRIS = [82, 82, 92], DENT = [214, 202, 116], GENCIVE = [96, 34, 34];
  const haut = (x) => -2.6 + 1.0 * (x / 2.9) ** 2, bas = (x) => -0.7 - 0.35 * (1 - (x / 2.9) ** 2);
  const dent = (x, decale) => 1 - Math.abs((((x + decale) / 0.52 + 20) % 1) * 2 - 1);
  const cheveux = (x, y) => y < -6.95 + 0.9 * Math.max(0, 1 - Math.abs(x) / 1.4);
  const bouche = (x, y) => Math.abs(x) < 2.9 && y > haut(x) && y < bas(x);
  plaque(r, {
    a: 4.35, b: 4.8, cy: -3.9, bord: [176, 176, 172],
    relief: (x, y) => {
      let h = bosse(x, y, 0, -3.3, 0.5, 0.95, 0.55) + bosse(x, y, 0, -2.75, 0.7, 0.3, 0.2)
        + bosse(x, y, -2.5, -3.3, 0.75, 0.55, 0.32) + bosse(x, y, 2.5, -3.3, 0.75, 0.55, 0.32)
        + bosse(x, y, 0, -0.05, 1.1, 0.4, 0.3) + (cheveux(x, y) ? 0.22 : 0);
      for (const s of [-1, 1]) h += bosse(x, y, s * 1.7, -5.25 + 0.15 * Math.abs(x - s * 1.7), 1.0, 0.28, 0.32) - bosse(x, y, s * 1.7, -4.35, 0.8, 0.45, 0.25);
      // les lèvres avancent, l'intérieur de la bouche est creusé
      if (Math.abs(x) < 3.1) h += 0.28 * Math.exp(-(((y - haut(x)) / 0.22) ** 2)) + 0.24 * Math.exp(-(((y - bas(x)) / 0.22) ** 2));
      if (bouche(x, y)) h -= 0.22;
      return h;
    },
    face: (x, y) => {
      if (cheveux(x, y)) return grain(mix(GRIS, [110, 110, 120], (y + 8.7) / 2), x, y, 1);
      for (const s of [-1, 1]) {
        // les sourcils tristes, qui remontent vers le milieu
        if (auSegment(x, y, s * 2.95, -5.0, s * 0.8, -5.75) < 0.15) return [56, 54, 60];
        // les yeux : des creux sombres, la pupille jaune
        if (dans(x, y, s * 1.55, -4.25, 0.25, 0.25)) return [232, 218, 96];
        if (dans(x, y, s * 1.7, -4.35, 0.95, 0.56)) return NOIR;
        if (dans(x, y, s * 1.7, -4.35, 1.12, 0.72)) return [150, 140, 140];
      }
      // les rides du front, du nez et des joues
      if (Math.abs(y + 6.1 - 0.12 * Math.abs(x)) < 0.06 && Math.abs(x) < 0.7) return [150, 150, 148];
      if (Math.abs(y + 2.95) < 0.06 && Math.abs(x) < 0.75) return [150, 150, 148];
      for (const s of [-1, 1]) if (auSegment(x, y, s * 0.6, -3.6, s * 1.25, -2.7) < 0.06) return [160, 160, 156];
      // la bouche : un grand sourire, des crocs en haut et en bas, les gencives au fond
      if (bouche(x, y)) {
        if (y < haut(x) + 0.75 * dent(x, 0) || y > bas(x) - 0.62 * dent(x, 0.26)) return grain(mix(DENT, [240, 232, 170], 0.3 - (y - haut(x))), x, y, 2);
        return y < (haut(x) + bas(x)) / 2 ? GENCIVE : [58, 20, 22];
      }
      if (Math.abs(x) < 3.05 && y > haut(x) - 0.14 && y < bas(x) + 0.14) return [52, 40, 40];
      return grain(BLANC, x, y, 3);
    },
  });
  // le nœud de ruban sur le haut
  r.boite([-0.25, -9.05, -4.0], [0.25, -8.6, -3.6], () => [168, 168, 178]);
  for (const s of [-1, 1]) r.boite([s * 0.25, -9.2, -3.95], [s * 0.85, -8.9, -3.65].map((v, i) => (i === 0 ? v : v)), () => [190, 190, 200]);
  // les cornes, en croissant vers le ciel, de plus en plus fines, cerclées de rainures
  for (const s of [-1, 1]) {
    troncons(m, r, `corne_${s > 0 ? "g" : "d"}`, courbe([s * 3.0, -7.55, -4.2], [s * 0.6, -1, 0], 12, 0.5, s * 6.5),
      (t) => 1.45 - t * 1.15, (f, u, v, k, nu, nv) => {
        const base = mix([132, 126, 64], [196, 190, 120], k / 12);
        if (f === "droite" || f === "arriere") return clair(base, 0.75);
        return v === 0 && k % 3 === 1 ? clair(base, 0.7) : clair(base, f === "avant" ? 1 : 0.88);
      });
  }
  // le serpent : il passe derrière la tête, sa tête posée contre la joue droite (à gauche vue de face)
  const chemin = lisse([[-4.75, -2.6, -4.6], [-5.2, -4.0, -3.4], [-5.1, -5.8, -1.8], [-4.2, -7.6, 0.2], [-2.0, -8.9, 1.8], [0.6, -9.2, 2.2],
    [3.1, -8.4, 1.2], [4.9, -6.6, -0.4], [5.3, -4.2, -1.6], [5.1, -1.8, -2.4], [4.8, 0.4, -3.0], [4.4, 2.2, -3.0]], 26);
  troncons(m, r, "serpent", chemin, (t) => 1.15 - 0.45 * Math.max(0, t - 0.55),
    (f, u, v, k, nu, nv) => {
      if (k === 0 && f === "avant") return (u === 2 || u === nu - 3) && v < 3 ? [214, 196, 70] : [60, 74, 50];
      if (f === "droite") return mix([176, 156, 130], [150, 130, 108], (v % 3) / 3);
      const ecaille = (u + Math.floor(v / 2) + k) % 4 === 0 || v % 4 === 0;
      return ecaille ? [40, 50, 34] : grain([60, 74, 50], u, v + k * 9, 4, 0.08);
    });
};

// le renard Kitsune : blanc, museau en avant, marques rouges et noires, oreilles pointues, pompons rouges et grelots dorés
MASQUES.kitsune = (m) => {
  const r = m.partie("masque");
  const BLANC = [248, 246, 242], ROUGE = [212, 30, 40], ROUGE_SOMBRE = [160, 18, 28];
  plaque(r, {
    a: 4.3, b: 4.65, cy: -4.2, bord: [214, 212, 208], lien: [176, 22, 32],
    // le museau : plus étroit vers le bas
    profil: (t) => (t > 0 ? 1 - 0.4 * t : 1),
    relief: (x, y) => bosse(x, y, 0, -1.9, 1.15, 1.25, 0.95) + bosse(x, y, 0, -1.55, 0.42, 0.32, 0.25)
      + bosse(x, y, -1.8, -5.0, 0.95, 0.35, 0.25) + bosse(x, y, 1.8, -5.0, 0.95, 0.35, 0.25)
      + bosse(x, y, -2.6, -3.0, 0.8, 0.6, 0.22) + bosse(x, y, 2.6, -3.0, 0.8, 0.6, 0.22) + bosse(x, y, 0, -6.7, 0.9, 0.9, 0.18),
    face: (x, y) => {
      for (const s of [-1, 1]) {
        // les yeux en amande (des trous), bordés de noir dessus, puis la flamme rouge qui remonte vers l'oreille
        if (dans(x, y, s * 1.75, -4.35, 0.78, 0.38)) return null;
        if (dans(x, y, s * 1.8, -4.45, 1.05, 0.6) && y < -4.3) return NOIR;
        if (dans(x, y, s * 1.95, -4.6, 1.45, 0.9) && (y < -4.4 || Math.abs(x) > 2.4)) return mix(ROUGE, ROUGE_SOMBRE, Math.abs(x) / 4);
        if (auSegment(x, y, s * 2.85, -4.9, s * 3.6, -6.5) < 0.17) return ROUGE;
        // les moustaches : trois traits sur chaque joue, noir au milieu
        for (let k = 0; k < 3; k++) if (auSegment(x, y, s * 1.9, -2.95 + k * 0.45, s * 3.55, -3.3 + k * 0.62) < 0.1) return k === 1 ? NOIR : ROUGE;
      }
      // la flamme rouge du front, en trois langues
      if (dans(x, y, 0, -6.85, 0.42, 0.85) || auSegment(x, y, -0.75, -6.25, -0.05, -7.95) < 0.14 || auSegment(x, y, 0.75, -6.25, 0.05, -7.95) < 0.14) return ROUGE;
      // la truffe noire et la bouche rouge, en petit sourire
      if (y > -2.0 && y < -1.32 && Math.abs(x) < 0.58 - (y + 2.0) * 0.62) return NOIR;
      if (Math.abs(x) < 0.06 && y > -1.35 && y < -0.95) return NOIR;
      if (Math.abs(x) < 1.15 && Math.abs(y - (-0.92 - 0.3 * (Math.abs(x) / 1.15) ** 2)) < 0.08) return ROUGE;
      return grain(BLANC, x, y, 5);
    },
  });
  // les oreilles : blanches, rouges dedans, en pointe
  for (const s of [-1, 1]) {
    const o = m.partie(`oreille_${s > 0 ? "g" : "d"}`, [s * 2.35, -8.0, -4.6], [6, 0, -s * 12], r);
    const n = 9;
    for (let k = 0; k < n; k++) {
      const w = 2.35 * (1 - k / n) + 0.12;
      o.boite([-w / 2, -0.28 * (k + 1), -0.32], [w / 2, -0.28 * k, 0.32],
        (f, u, v, nu) => (f === "avant" && u >= 2 && u < nu - 2 && k < n - 2 ? (k < 2 ? ROUGE_SOMBRE : ROUGE) : f === "arriere" ? [226, 222, 218] : BLANC));
    }
  }
  // les pompons : une cordelette rouge, une bague dorée, la frange en fils, et le grelot doré fendu
  for (const s of [-1, 1]) {
    const x = s * 4.45;
    r.boite([x - 0.18, -4.5, -4.18], [x + 0.18, -1.85, -3.82], (f, u, v) => (v % 3 === 0 ? ROUGE_SOMBRE : ROUGE))
      .boite([x - 0.3, -1.85, -4.3], [x + 0.3, -1.45, -3.7], (f, u, v) => (v === 1 ? [252, 220, 110] : [214, 168, 56]));
    for (const [dx, dz] of [[-0.26, -0.18], [0.0, -0.3], [0.26, -0.18], [-0.2, 0.18], [0.2, 0.18]]) {
      r.boite([x + dx - 0.12, -1.45, -4.0 + dz - 0.12], [x + dx + 0.12, 0.95 + 0.15 * Math.abs(dx), -4.0 + dz + 0.12], (f, u, v) => ((v + 7 * dx) % 5 < 1 ? ROUGE_SOMBRE : ROUGE));
    }
    r.boite([x - 0.38, 1.05, -4.38], [x + 0.38, 1.85, -3.62], (f, u, v, nu, nv) => (v === 4 && u > 1 && u < nu - 2 ? [110, 80, 24] : v < 2 ? [252, 226, 130] : [230, 190, 72]))
      .boite([x - 0.28, 0.95, -4.28], [x + 0.28, 1.95, -3.72], () => [218, 176, 60]);
  }
};

// le masque jaune de Tobi : un seul trou, à droite, et des bandes noires qui ondulent, un peu creusées
const BANDES_TOBI = [[-0.7, 4.2, -6.75, 0.32, -0.02, 0.42], [0.2, 4.3, -5.1, 0.16, 0, 0.38], [-4.3, 3.7, -3.55, 0.1, 0.07, 0.5],
  [-4.2, 4.1, -2.0, -0.04, 0.05, 0.45], [-2.9, 2.5, -0.65, 0.05, 0.04, 0.34]];
const surBandeTobi = (x, y) => {
  for (const [x0, x1, y0, pente, creux, e] of BANDES_TOBI) {
    if (x < x0 || x > x1) continue;
    const t = (x - x0) / (x1 - x0), yc = y0 + pente * x + creux * x * x;
    const ep = e * Math.pow(Math.sin(Math.PI * t), 0.6);
    if (Math.abs(y - yc) < ep) return 1 - Math.abs(y - yc) / ep;
  }
  return 0;
};
MASQUES.tobi = (m) => {
  const r = m.partie("masque");
  const JAUNE = [240, 184, 44];
  plaque(r, {
    a: 4.2, b: 4.75, cy: -4.0, bord: [190, 140, 30],
    relief: (x, y) => bosse(x, y, 0, -3.6, 1.6, 2.6, 0.35) - (surBandeTobi(x, y) > 0 ? 0.1 : 0)
      - bosse(x, y, -1.85, -4.75, 0.9, 0.9, 0.18),
    face: (x, y) => {
      if (dans(x, y, -1.85, -4.75, 0.55, 0.55)) return null;
      if (dans(x, y, -1.85, -4.75, 0.82, 0.82)) return NOIR;
      // le croissant autour du trou
      const d = Math.hypot(x + 1.85, y + 4.75);
      if (d > 1.22 && d < 1.58 && x > -1.6) return NOIR;
      if (surBandeTobi(x, y) > 0) return NOIR;
      return grain(mix(JAUNE, [214, 138, 28], (y + 1) / 3), x, y, 7);
    },
  });
};

// la spirale orange de Tobi : des sillons creusés qui tournent jusqu'au trou de l'œil droit
const spirale = (x, y) => {
  const dx = x + 1.6, dy = y + 4.7, rayon = Math.hypot(dx, dy), angle = (Math.atan2(dy, dx) + Math.PI) / (2 * Math.PI), pas = 1.05;
  return { rayon, f: ((((rayon - pas * angle) % pas) + pas) % pas) / pas };
};
MASQUES.spirale = (m) => {
  const r = m.partie("masque");
  const ORANGE = [236, 114, 32], SILLON = [140, 54, 16];
  plaque(r, {
    a: 4.2, b: 4.75, cy: -4.0, bord: [180, 80, 22],
    relief: (x, y) => {
      const { rayon, f } = spirale(x, y);
      // chaque tour est un bourrelet arrondi, séparé du suivant par un sillon
      return 0.2 * Math.sin(Math.PI * Math.min(1, Math.max(0, (f - 0.18) / 0.82))) - (f < 0.18 ? 0.08 : 0) + bosse(x, y, -1.6, -4.7, 2.5, 2.5, 0.25) * Math.min(1, rayon);
    },
    face: (x, y) => {
      const { rayon, f } = spirale(x, y);
      if (rayon < 0.5) return null;
      if (f < 0.16) return SILLON;
      return grain(ORANGE, x, y, 9);
    },
  });
};

// le masque de guerre d'Obito : blanc, des cercles gravés autour du haut, deux petits trous, deux traits en bas
MASQUES.obito = (m) => {
  const r = m.partie("masque");
  const BLANC = [238, 238, 242];
  const anneaux = (x, y) => { const d = Math.hypot(x, y + 6.0); return d < 0.42 || Math.abs(d - 1.35) < 0.12 || Math.abs(d - 2.45) < 0.12; };
  const traits = (x, y) => [-2.6, -1.25].some((y0) => Math.abs(y - (y0 + 0.22 * (x / 4) ** 2)) < 0.11);
  plaque(r, {
    a: 4.2, b: 4.75, cy: -4.0, bord: [190, 190, 198],
    relief: (x, y) => bosse(x, y, 0, -6.0, 2.8, 2.8, 0.3) + bosse(x, y, 0, -3.2, 0.4, 0.8, 0.22) - (anneaux(x, y) || traits(x, y) ? 0.1 : 0)
      - (y > -2.5 && y < -1.35 ? 0.06 : 0),
    face: (x, y) => {
      for (const s of [-1, 1]) {
        if (dans(x, y, s * 1.6, -4.15, 0.42, 0.42)) return null;
        if (dans(x, y, s * 1.6, -4.15, 0.62, 0.62)) return NOIR;
      }
      if (anneaux(x, y) || traits(x, y)) return NOIR;
      if (y > -2.5 && y < -1.35) return grain([212, 212, 220], x, y, 11);
      return grain(BLANC, x, y, 12);
    },
  });
};

// le masque blanc du chasseur : des yeux en fentes, quatre petites vagues au front, la grande vague rouge sur la joue
MASQUES.chasseur = (m) => {
  const r = m.partie("masque");
  const BLANC = [246, 246, 248], ROUGE = [214, 30, 36];
  const vague = (x, y) => {
    const yb = -2.1 + 0.12 * x;
    if (x < 0.6 && Math.abs(y - yb) < 0.32 + 0.38 * Math.min(1, (0.6 - x) / 3)) return true;
    const d = Math.hypot(x - 1.15, y + 2.55), a = Math.atan2(y + 2.55, x - 1.15);
    if (d > 0.42 && d < 1.05 && !(a > 1.9 && a < 3.2)) return true;
    return x > 2.6 && x < 3.2 && y > -3.1 && y < 0.6 - 1.2 * (x - 2.6);
  };
  plaque(r, {
    a: 4.1, b: 4.95, cy: -4.0, bord: [200, 200, 206],
    relief: (x, y) => bosse(x, y, 0, -3.2, 0.42, 0.9, 0.3) + bosse(x, y, -1.8, -4.9, 0.9, 0.3, 0.18) + bosse(x, y, 1.8, -4.9, 0.9, 0.3, 0.18)
      + (vague(x, y) ? 0.06 : 0),
    face: (x, y) => {
      // les yeux : de fines fentes en arc
      for (const s of [-1, 1]) {
        const yc = -4.25 - 0.35 * (1 - ((x - s * 1.8) / 1.1) ** 2);
        if (Math.abs(x - s * 1.8) < 1.05 && Math.abs(y - yc) < 0.13) return null;
        if (Math.abs(x - s * 1.8) < 1.2 && Math.abs(y - yc) < 0.28) return NOIR;
      }
      // les quatre petites vagues du front (deux rangées de deux)
      for (const [x0, y0] of [[0.1, -7.1], [1.3, -7.1], [0.1, -6.25], [1.3, -6.25]]) {
        if (x > x0 - 0.45 && x < x0 + 0.45 && Math.abs(y - (y0 + 0.16 * Math.sin((x - x0) * 7))) < 0.1) return NOIR;
      }
      if (vague(x, y)) return mix(ROUGE, [170, 16, 24], (x + 4) / 8);
      return grain(BLANC, x, y, 13);
    },
  });
};

export const NOMS_MASQUES = {
  hannya: "Masque du démon Hannya",
  kitsune: "Masque de renard Kitsune",
  tobi: "Masque de Tobi",
  spirale: "Masque spirale de Tobi",
  obito: "Masque de guerre d'Obito",
  chasseur: "Masque de chasseur ninja",
};

// ---------------------------------------------------------------- vers Minecraft
function png(w, h, rgba) {
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let j = 0; j < h; j++) rgba.copy(brut, j * (1 + w * 4) + 1, j * w * 4, (j + 1) * w * 4);
  const t = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const o of buf) c = t[(c ^ o) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

/** Les boîtes en texels entiers, rangées dans la texture ; puis la texture, le modèle, et l'icône vue de face. */
function construire(nom, dessin) {
  const m = nouveauModele();
  dessin(m);
  const boites = m.parties.flatMap((p) => p.boites.map((b) => {
    const dim = [0, 1, 2].map((i) => Math.abs(b.b[i] - b.a[i]) * K), n = dim.map((v) => Math.max(1, Math.round(v)));
    return Object.assign(b, { c: fois(ajoute(b.a, b.b), 0.5 * K), dim, n, uw: 2 * (n[2] + n[0]), uh: n[2] + n[1], partie: p });
  }));
  const LARGEUR = 256;
  let x = 0, y = 0, rangee = 0;
  for (const b of [...boites].sort((a, c) => c.uh - a.uh || c.uw - a.uw)) {
    if (x + b.uw > LARGEUR) { x = 0; y += rangee; rangee = 0; }
    Object.assign(b, { u: x, v: y });
    x += b.uw;
    rangee = Math.max(rangee, b.uh);
  }
  const HAUTEUR = [64, 128, 256, 512, 1024, 2048].find((h) => h >= y + rangee);
  const px = Buffer.alloc(LARGEUR * HAUTEUR * 4);
  for (const b of boites) {
    const [w, h, d] = b.n;
    const zone = (x0, y0, lw, lh, face) => {
      for (let j = 0; j < lh; j++) for (let i = 0; i < lw; i++) {
        const col = b.peint(face, i, j, lw, lh);
        if (col) px.set([...col.map(Math.round), 255], ((y0 + j) * LARGEUR + x0 + i) * 4);
      }
    };
    zone(b.u + d, b.v, w, d, "haut");
    zone(b.u + d + w, b.v, w, d, "bas");
    zone(b.u, b.v + d, d, h, "droite");
    zone(b.u + d, b.v + d, w, h, "avant");
    zone(b.u + d + w, b.v + d, d, h, "gauche");
    zone(b.u + 2 * d + w, b.v + d, w, h, "arriere");
  }
  const arrondi = (v) => Math.round(v * 10000) / 10000;
  const modele = {
    texture: [LARGEUR, HAUTEUR],
    echelle: K,
    parties: m.parties.map((p) => ({
      nom: p.nom,
      parent: p.parent?.nom ?? null,
      pivot: p.pivot.map((v) => arrondi(v * K)),
      rotation: p.rotation.map((a) => arrondi(a * DEG)),
      // les boîtes sont dans le repère de leur partie
      boites: p.boites.map((b) => ({
        uv: [b.u, b.v],
        coin: [0, 1, 2].map((i) => arrondi(b.c[i] - b.n[i] / 2)),
        taille: b.n,
        gonfle: b.dim.map((v, i) => arrondi((v - b.n[i]) / 2)),
      })),
    })),
  };
  fs.mkdirSync(path.join(RES, "textures", "entity", "masque"), { recursive: true });
  fs.mkdirSync(path.join(RES, "modeles"), { recursive: true });
  fs.writeFileSync(path.join(RES, "textures", "entity", "masque", nom + ".png"), png(LARGEUR, HAUTEUR, px));
  const { parties, ...entete } = modele;
  fs.writeFileSync(path.join(RES, "modeles", `masque_${nom}.json`),
    JSON.stringify(entete).slice(0, -1) + ',"parties":[\n' + parties.map((p) => "  " + JSON.stringify(p)).join(",\n") + "\n]}\n");
  icone(nom, boites);
  if (process.env.MASQUES_APERCU) apercus(nom, boites, process.env.MASQUES_APERCU);
  return boites.length;
}

/**
 * Pour régler les dessins (MASQUES_APERCU=<dossier>) : le masque porté sur une tête de joueur, vu de face, de trois quarts
 * et de profil, comme en jeu (les mêmes pavés, les mêmes texels ; les faces ombrées comme Minecraft les ombre).
 */
function apercus(nom, boites, dossier) {
  const vues = [[0, 6], [-38, 12], [-80, 6], [35, -22]];
  const N = 360, echelle = 22, W = N * vues.length;
  const px = Buffer.alloc(W * N * 4);
  for (let i = 0; i < W * N; i++) px.set([206, 210, 218, 255], i * 4);
  vues.forEach(([lacet, tangage], k) => {
    const prof = new Float32Array(N * N).fill(Infinity);
    const vue = (p) => {
      let [x, y, z] = p;
      [x, z] = [x * Math.cos(lacet * DEG) + z * Math.sin(lacet * DEG), -x * Math.sin(lacet * DEG) + z * Math.cos(lacet * DEG)];
      [y, z] = [y * Math.cos(tangage * DEG) - z * Math.sin(tangage * DEG), y * Math.sin(tangage * DEG) + z * Math.cos(tangage * DEG)];
      return [x, y, z];
    };
    const plot = (p, col, taille) => {
      const [x, y, z] = vue(p);
      const ci = N / 2 + x * echelle, cj = N / 2 + (y + 4) * echelle;
      for (let dj = -taille; dj <= taille; dj++) for (let di = -taille; di <= taille; di++) {
        const i = Math.round(ci + di), j = Math.round(cj + dj);
        if (i < 0 || j < 0 || i >= N || j >= N || z >= prof[j * N + i]) continue;
        prof[j * N + i] = z;
        px.set([...col.map(Math.round), 255], (j * W + k * N + i) * 4);
      }
    };
    // la tête du joueur : peau devant, cheveux dessus et derrière
    for (let a = -4; a <= 4; a += 0.125) for (let b = -8; b <= 0; b += 0.125) {
      plot([a, b, -4], b < -6.5 ? [86, 58, 36] : [198, 148, 112], 2);
      plot([a, b, 4], [76, 50, 30], 2);
      plot([-4, b, a], b < -6 ? [80, 54, 32] : [180, 132, 98], 2);
      plot([4, b, a], b < -6 ? [80, 54, 32] : [180, 132, 98], 2);
      if (b > -4.2) plot([a, -8, b + 4], [92, 62, 38], 2);
    }
    for (const bt of boites) {
      const lo = [0, 1, 2].map((i) => Math.min(bt.a[i], bt.b[i])), hi = [0, 1, 2].map((i) => Math.max(bt.a[i], bt.b[i]));
      const [w, h, d] = bt.n;
      const lerp = (a, c, t) => a + (c - a) * t;
      const faces = [["avant", w, h, 0.95, (i, j) => [lerp(lo[0], hi[0], (i + 0.5) / w), lerp(lo[1], hi[1], (j + 0.5) / h), lo[2]]],
        ["arriere", w, h, 0.95, (i, j) => [lerp(hi[0], lo[0], (i + 0.5) / w), lerp(lo[1], hi[1], (j + 0.5) / h), hi[2]]],
        ["gauche", d, h, 0.72, (i, j) => [hi[0], lerp(lo[1], hi[1], (j + 0.5) / h), lerp(lo[2], hi[2], (i + 0.5) / d)]],
        ["droite", d, h, 0.72, (i, j) => [lo[0], lerp(lo[1], hi[1], (j + 0.5) / h), lerp(hi[2], lo[2], (i + 0.5) / d)]],
        ["haut", w, d, 1.05, (i, j) => [lerp(lo[0], hi[0], (i + 0.5) / w), lo[1], lerp(lo[2], hi[2], (j + 0.5) / d)]],
        ["bas", w, d, 0.6, (i, j) => [lerp(lo[0], hi[0], (i + 0.5) / w), hi[1], lerp(lo[2], hi[2], (j + 0.5) / d)]]];
      for (const [face, nu, nv, lum, pos] of faces) {
        for (let j = 0; j < nv; j++) for (let i = 0; i < nu; i++) {
          const col = bt.peint(face, i, j, nu, nv);
          if (!col) continue;
          let q = pos(i, j);
          for (let pa = bt.partie; pa; pa = pa.parent) {
            const r = pa.rotation;
            let [x, y, z] = q;
            [y, z] = [y * Math.cos(r[0] * DEG) - z * Math.sin(r[0] * DEG), y * Math.sin(r[0] * DEG) + z * Math.cos(r[0] * DEG)];
            [x, z] = [x * Math.cos(r[1] * DEG) + z * Math.sin(r[1] * DEG), -x * Math.sin(r[1] * DEG) + z * Math.cos(r[1] * DEG)];
            [x, y] = [x * Math.cos(r[2] * DEG) - y * Math.sin(r[2] * DEG), x * Math.sin(r[2] * DEG) + y * Math.cos(r[2] * DEG)];
            q = ajoute([x, y, z], pa.pivot);
          }
          plot(q, clair(col, lum), 2);
        }
      }
    }
  });
  fs.mkdirSync(dossier, { recursive: true });
  fs.writeFileSync(path.join(dossier, nom + ".png"), png(W, N, px));
}

/** L'icône 64 × 64 : chaque texel de chaque face, placé là où il est vu de face, de trois quarts (le plus en avant gagne). */
function icone(nom, boites) {
  const points = [];
  const tourner = (p, r) => {
    let [x, y, z] = p;
    // comme ModelPart : x, puis y, puis z
    [y, z] = [y * Math.cos(r[0] * DEG) - z * Math.sin(r[0] * DEG), y * Math.sin(r[0] * DEG) + z * Math.cos(r[0] * DEG)];
    [x, z] = [x * Math.cos(r[1] * DEG) + z * Math.sin(r[1] * DEG), -x * Math.sin(r[1] * DEG) + z * Math.cos(r[1] * DEG)];
    [x, y] = [x * Math.cos(r[2] * DEG) - y * Math.sin(r[2] * DEG), x * Math.sin(r[2] * DEG) + y * Math.cos(r[2] * DEG)];
    return [x, y, z];
  };
  const monde = (p, partie) => {
    let q = p;
    for (let pa = partie; pa; pa = pa.parent) q = ajoute(tourner(q, pa.rotation), pa.pivot);
    // un léger trois quarts : on voit le relief
    return tourner(q, [8, -14, 0]);
  };
  for (const b of boites) {
    const lo = [0, 1, 2].map((i) => Math.min(b.a[i], b.b[i])), hi = [0, 1, 2].map((i) => Math.max(b.a[i], b.b[i]));
    // le lien, derrière la tête, n'a rien à faire sur l'icône
    if (b.partie.nom === "masque" && lo[2] > -2) continue;
    const [w, h, d] = b.n;
    const lerp = (a, c, t) => a + (c - a) * t;
    const faces = [["avant", w, h, 1, (i, j) => [lerp(lo[0], hi[0], (i + 0.5) / w), lerp(lo[1], hi[1], (j + 0.5) / h), lo[2]]],
      ["gauche", d, h, 0.78, (i, j) => [hi[0], lerp(lo[1], hi[1], (j + 0.5) / h), lerp(lo[2], hi[2], (i + 0.5) / d)]],
      ["droite", d, h, 0.9, (i, j) => [lo[0], lerp(lo[1], hi[1], (j + 0.5) / h), lerp(hi[2], lo[2], (i + 0.5) / d)]],
      ["haut", w, d, 1.08, (i, j) => [lerp(lo[0], hi[0], (i + 0.5) / w), lo[1], lerp(lo[2], hi[2], (j + 0.5) / d)]],
      ["bas", w, d, 0.7, (i, j) => [lerp(lo[0], hi[0], (i + 0.5) / w), hi[1], lerp(lo[2], hi[2], (j + 0.5) / d)]]];
    for (const [face, nu, nv, lum, pos] of faces) {
      for (let j = 0; j < nv; j++) for (let i = 0; i < nu; i++) {
        const col = b.peint(face, i, j, nu, nv);
        if (col) points.push([...monde(pos(i, j), b.partie), clair(col, lum)]);
      }
    }
  }
  const xs = points.map((p) => p[0]), ys = points.map((p) => p[1]);
  const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
  const N = 64, taille = Math.max(x1 - x0, y1 - y0) * 1.05, cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
  const px = Buffer.alloc(N * N * 4), prof = new Float32Array(N * N).fill(Infinity);
  for (const [x, y, z, col] of points) {
    const i = Math.floor((x - cx) / taille * N + N / 2), j = Math.floor((y - cy) / taille * N + N / 2);
    if (i < 0 || j < 0 || i >= N || j >= N || z >= prof[j * N + i]) continue;
    prof[j * N + i] = z;
    px.set([...col.map(Math.round), 255], (j * N + i) * 4);
  }
  // un liseré sombre autour, pour qu'elle se détache dans l'inventaire
  const plein = (i, j) => i >= 0 && j >= 0 && i < N && j < N && px[(j * N + i) * 4 + 3] > 0;
  const bord = [];
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    if (!plein(i, j) && (plein(i - 1, j) || plein(i + 1, j) || plein(i, j - 1) || plein(i, j + 1))) bord.push(j * N + i);
  }
  for (const k of bord) px.set([24, 22, 28, 255], k * 4);
  fs.writeFileSync(path.join(RES, "textures", "item", `masque_${nom}.png`), png(N, N, px));
  fs.writeFileSync(path.join(RES, "models", "item", `masque_${nom}.json`),
    JSON.stringify({ parent: "minecraft:item/generated", textures: { layer0: `cabane:item/masque_${nom}` } }, null, 2) + "\n");
}

export function genererMasques({ ecrire, lang }) {
  for (const [nom, texte] of Object.entries(NOMS_MASQUES)) {
    lang[`item.cabane.masque_${nom}`] = texte;
  }
  lang["item.cabane.masque.desc"] = "Accessoire : sur la tête, la voix devient grave ; à la ceinture, juste pour le style.";
  // un deuxième slot de ceinture : un masque sur le côté, et le grappin des espionnes à côté
  ecrire("data/cabane/curios/slots/belt.json", { size: 1, operation: "ADD" });
  return Object.entries(MASQUES).map(([nom, dessin]) => construire(nom, dessin)).reduce((a, b) => a + b, 0);
}

// lancé tout seul : seulement les modèles et les textures (le reste passe par generer.mjs)
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const total = Object.entries(MASQUES).map(([nom, dessin]) => construire(nom, dessin)).reduce((a, b) => a + b, 0);
  console.log(`Masques : ${Object.keys(MASQUES).length}, ${total} pavés`);
}
