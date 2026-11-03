// Maquettes des deux attaques ultimes du niveau 3 (demandées le 2026-11-03), accroupi une fois, une seule fois par forme.
// La glace, le Hyoton (un Suiton en glace, d'après deux vidéos de Zenkai) : il colle ses deux mains et les pousse en
// avant ; sous la cible, un anneau de givre, un pylône de glace qui la projette en l'air, des jets de glace qui montent sur
// elle et forment une grosse boule, la boule devient un dragon qui claque au sol, et une tempête de glace monte très, très
// haut. La lave, le soleil de magma : il bondit très haut, lève une main et reste suspendu ; le magma et les flammes
// montent du sol, petit à petit, et forment une sphère énorme ; il la lance où il vise : une explosion géante.
// Tout se passe en jeu, sans cinématique : la caméra reste celle du joueur (vue de dos).
// node ultimes.mjs → planche-ultimes.png ; node ultimes.mjs video glace|lave → images dans le dossier donné en 3e argument
// ; node ultimes.mjs image glace|lave <t> [fichier.png] → une image seule.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, mix, hache, norme, scene, ecrireTexte, clair } from "./moteur.mjs";
import {
  ajoute, fois, sous, TOUR, ID, brille, pave, cube, flamme, cristal, perso, brule, givre, regarde, fissure, magma,
  FACES, LAVE_R, LAVE_O, LAVE_J, GLACE, GLACE_C, BLANC, FUMEE, HERBE, TERRE, STEVE, CROUTE, CROUTE_R,
} from "./rendu.mjs";
import { blocDeGlace } from "./niveau2.mjs";
import { cadre, boite, niveau3, grandDragon, ecailles, morceau, glace3, GLACE_P } from "./niveau3.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const CIEL = [150, 196, 242], GLACE_V = [120, 236, 255];

const borne = (u) => Math.max(0, Math.min(1, u));
const entre = (t, a, b) => borne((t - a) / (b - a));
const sortie = (u) => 1 - (1 - borne(u)) ** 3;
const entree = (u) => borne(u) ** 2;
const bezier = (a, b, c, d, u) => a.map((_, i) => (1 - u) ** 3 * a[i] + 3 * (1 - u) ** 2 * u * b[i] + 3 * (1 - u) * u * u * c[i] + u ** 3 * d[i]);

// ------------------------------------------------------------------ le décor
/** Une grande plaine (des cases de `pas`) ; dessus(x, z) change la couleur du haut. */
function terrain(sc, x0, z0, x1, z1, pas, dessus) {
  pave(sc, [x0, -24, z0], [x1, 0, z1], ID, (f, u, v, i, j) => {
    if (f === "bas") return null;
    if (f === "haut") {
      const x = x0 + (i + 0.5) * pas, z = z0 + (j + 0.5) * pas;
      return dessus?.(x, z) ?? clair(HERBE, 0.84 + 0.18 * hache(Math.floor(x / pas), Math.floor(z / pas), 3));
    }
    return clair(TERRE, 0.86 + 0.18 * hache(i, j, 5));
  }, pas);
}

/** Des nuages en blocs, loin. */
function nuages(sc) {
  for (let q = 0; q < 9; q++) {
    const x = (hache(q, 1, 500) - 0.5) * 3000, y = 700 + 500 * hache(q, 2, 500), z = 1600 + 900 * hache(q, 3, 500), l = 120 + 140 * hache(q, 4, 500);
    pave(sc, [x - l, y, z - l * 0.6], [x + l, y + l * 0.22, z + l * 0.6], ID, () => [250, 251, 253], l / 2);
    pave(sc, [x - l * 0.5, y + l * 0.22, z - l * 0.4], [x + l * 0.55, y + l * 0.4, z + l * 0.4], ID, () => [244, 246, 250], l / 2);
  }
}

/** Une coquille d'ellipsoïde en cubes (de `pas`), couleur(p, d) par cube (d de 0 au centre à 1 au bord), ou null. */
function coquille(sc, c, [rx, ry, rz], pas, couleur, aile = true) {
  const nx = Math.ceil(rx / pas), ny = Math.ceil(ry / pas), nz = Math.ceil(rz / pas);
  const ep = (pas * 1.8) / Math.min(rx, ry, rz);
  for (let i = -nx; i <= nx; i++) for (let j = -ny; j <= ny; j++) for (let k = -nz; k <= nz; k++) {
    const d = Math.hypot((i * pas) / rx, (j * pas) / ry, (k * pas) / rz);
    if (d > 1 || d < 1 - ep) continue;
    const p = [c[0] + i * pas, c[1] + j * pas, c[2] + k * pas];
    const col = couleur(p, d);
    if (col) cube(sc, p, pas, [0, 0], col, aile);
  }
}

/** Un voile blanc et léger en cylindre autour de l'axe (x, z), de y0 à y1 (la brume de la tempête). */
function brume(sc, x, z, r, y0, y1, alpha) {
  for (let q = 0; q < 14; q++) {
    const a0 = (q / 14) * TOUR, a1 = ((q + 1) / 14) * TOUR;
    const p = (a, y) => [x + Math.cos(a) * r, y, z + Math.sin(a) * r];
    sc.voile([p(a0, y1), p(a1, y1), p(a1, y0), p(a0, y0)], [240, 248, 255], alpha);
  }
}

/** Un éclair blanc sur toute l'image (l'impact). */
function flash(sc, a, c = [255, 255, 255]) {
  if (a <= 0) return;
  for (let i = 0; i < sc.w * sc.h; i++) for (let k = 0; k < 3; k++) sc.img[i * 4 + k] = sc.img[i * 4 + k] * (1 - a) + c[k] * a;
}

/** Un joueur visé : debout, projeté, ou gelé. */
const cibleDebout = (sc, p, vers, teinte) => perso(sc, STEVE, { decale: p, lacet: regarde(p, vers), brasD: [-0.3, -0.2], brasG: [-0.3, 0.2], teinte });
const cibleEnLAir = (sc, p, penche = -0.5) => perso(sc, STEVE, { decale: p, lacet: Math.PI, penche, brasD: [-2.8, -0.5], brasG: [-2.8, 0.5], jambeD: 0.4, jambeG: -0.3 });

// ------------------------------------------------------------------ la glace : le Hyoton
const CIBLE = [0, 0, 230], BOULE = [0, 350, 230], H_PYLONE = 236;

/** Le pylône de glace : une colonne à huit pans, des cristaux à sa base et une couronne en haut. */
function pylone(sc, h) {
  if (h < 2) return;
  const tex = (n) => (f, u, v, i, j) => glace3(i + n * 7, j + n * 3, FACES[f] + n, v);
  for (const [n, d] of [[1, [0, 0, 1]], [2, [1, 0, 1]]]) boite(sc, cadre(ID, [CIBLE[0], h / 2, CIBLE[2]], d, [0, 1, 0], 1), [0, 0, 0], [16, h / 2, 16], tex(n), 6);
  for (let q = 0; q < 10; q++) {
    const a = (q / 10) * TOUR + 0.3;
    cristal(sc, [CIBLE[0] + Math.cos(a) * 20, -2, CIBLE[2] + Math.sin(a) * 20], [Math.cos(a) * 0.5, 1, Math.sin(a) * 0.5], (26 + 26 * hache(q, 1, 501)) * Math.min(1, h / 80), 10);
  }
  for (let q = 0; q < 6; q++) {
    const a = (q / 6) * TOUR;
    cristal(sc, [CIBLE[0] + Math.cos(a) * 12, h - 8, CIBLE[2] + Math.sin(a) * 12], [Math.cos(a) * 0.45, 1, Math.sin(a) * 0.45], 20, 7);
  }
}

/** L'anneau de givre sous la cible, et sa colonne de lumière. */
function anneau(sc, t) {
  const r = 46 * sortie(entre(t, 0.12, 0.42)), fin = 1 - entre(t, 2.6, 3);
  if (r < 1 || fin <= 0) return;
  for (const [rr, l, n] of [[r, 3, 40], [r * 0.72, 2, 30], [r * 1.18, 1.5, 48]]) {
    for (let q = 0; q < n; q++) {
      const a = (q / n) * TOUR + t * (rr === r ? 1.5 : -2);
      cube(sc, [CIBLE[0] + Math.cos(a) * rr, 0.6, CIBLE[2] + Math.sin(a) * rr], l * 1.6 * fin, [0, a], brille(q % 4 ? GLACE_V : BLANC), true);
    }
  }
  const lum = entre(t, 0.25, 0.4) * (1 - entre(t, 0.6, 0.9));
  if (lum > 0) for (let y = 0; y < 520; y += 9) cube(sc, [CIBLE[0], y, CIBLE[2]], 7 * lum * (1 - y / 700), [0, y], brille(mix(GLACE_V, BLANC, y / 520)), true);
}

/** Les douze jets de glace : ils sortent du sol autour de la cible et montent en courbe jusqu'à la boule. */
function jets(sc, t) {
  const epais = 1 - entre(t, 2.15, 2.5);
  if (t < 1 || epais <= 0) return;
  for (let q = 0; q < 12; q++) {
    const a = (q / 12) * TOUR + 0.26;
    const S = [CIBLE[0] + Math.cos(a) * 130, 0, CIBLE[2] + Math.sin(a) * 130];
    const C1 = ajoute(S, [Math.cos(a) * 30, 190, Math.sin(a) * 30]), C2 = ajoute(BOULE, [Math.cos(a) * 120, 40, Math.sin(a) * 120]);
    const debut = 1.0 + 0.035 * q, u = sortie(entre(t, debut, debut + 0.6));
    if (u <= 0) continue;
    const n = Math.max(2, Math.round(30 * u));
    for (let k = 0; k < n; k++) {
      const s0 = (u * k) / n, s1 = (u * (k + 1)) / n, p0 = bezier(S, C1, C2, BOULE, s0), p1 = bezier(S, C1, C2, BOULE, s1);
      const r = (3 + 4 * (s1 / u)) * epais, F = cadre(ID, fois(ajoute(p0, p1), 0.5), sous(p1, p0), [0, 1, 0], 1);
      boite(sc, F, [0, 0, 0], [r, r, Math.hypot(...sous(p1, p0)) * 0.62], ecailles(false, q * 40 + k), Math.max(1, r));
      if (k % 3 === 1) cube(sc, ajoute(p0, [(hache(q, k, 502) - 0.5) * 14, (hache(q, k, 503) - 0.5) * 14, (hache(q, k, 504) - 0.5) * 14]), 2.5, [k, q], BLANC, true);
    }
    if (u < 1) cristal(sc, bezier(S, C1, C2, BOULE, u), sous(bezier(S, C1, C2, BOULE, u), bezier(S, C1, C2, BOULE, u * 0.97)), 20, 10);
    // la gerbe là où il sort du sol
    const g = entre(t, debut, debut + 0.25) * (1 - entre(t, debut + 0.4, debut + 0.8));
    if (g > 0) for (let k = 0; k < 6; k++) {
      const b = (k / 6) * TOUR;
      cristal(sc, S, [Math.cos(b) * 0.7, 1, Math.sin(b) * 0.7], 16 * g, 6);
    }
  }
}

/** La grosse boule : de la glace qui tourne en bandes, deux anneaux autour, des pics qui en sortent. */
function bouleGlace(sc, R, t) {
  if (R < 4) return;
  coquille(sc, BOULE, [R, R, R], Math.max(4, R / 8), (p) => {
    const x = p[0] - BOULE[0], y = p[1] - BOULE[1], z = p[2] - BOULE[2];
    const b = Math.sin((Math.atan2(z, x) + y * 0.02 + t * 5) * 3 + y * 0.04);
    return b > 0.55 ? brille(BLANC) : b > 0.05 ? brille(mix(GLACE_C, GLACE_V, 0.4)) : b > -0.5 ? mix(GLACE, GLACE_C, 0.5) : mix(GLACE, GLACE_P, 0.45);
  });
  for (const [inc, ph] of [[0.45, 0], [-0.7, 1.7]]) {
    for (let q = 0; q < 40; q++) {
      const a = (q / 40) * TOUR + t * 3 + ph, rr = R * 1.4;
      const p = [Math.cos(a) * rr, Math.sin(a) * rr * Math.sin(inc), Math.sin(a) * rr * Math.cos(inc)];
      cube(sc, ajoute(BOULE, [p[0], p[1] * 0.5 + p[2] * 0.2, p[2]]), R * 0.06, [q, a], brille(q % 3 ? BLANC : GLACE_V), true);
    }
  }
  for (let q = 0; q < 16; q++) {
    const u = norme([hache(q, 1, 505) - 0.5, hache(q, 2, 505) - 0.5, hache(q, 3, 505) - 0.5]);
    cristal(sc, ajoute(BOULE, fois(u, R * 0.85)), u, R * (0.4 + 0.3 * hache(q, 4, 505)), R * 0.12);
  }
}

/** La tempête de glace : une colonne qui tourne, faite d'éclats, d'un cœur blanc et de neige en spirale, très haut. */
function tempete(sc, t) {
  const u = entre(t, 2.95, 4.4), H = 3400 * sortie(u), R0 = 72 + 46 * sortie(entre(t, 2.95, 3.7));
  if (H < 10) return;
  const r = (y) => R0 * (1 + y / 1300);
  for (let y = 6, n = 0; y < H; y += 26, n++) {
    const rr = r(y), haut = y > H - 120 ? (H - y) / 120 : 1;
    for (let q = 0; q < 9; q++) {
      const phi = (q / 9) * TOUR + y * 0.011 - t * 4 + hache(n, q, 506);
      const p = [CIBLE[0] + Math.cos(phi) * rr * (0.75 + 0.35 * hache(n, q, 507)), y, CIBLE[2] + Math.sin(phi) * rr * (0.75 + 0.35 * hache(n, q, 507))];
      cristal(sc, p, [-Math.sin(phi), 0.8, Math.cos(phi)], (34 + 30 * hache(n, q, 508)) * haut * (1 + y / 1500), (11 + 5 * hache(n, q, 509)) * (1 + y / 1500));
    }
    for (let q = 0; q < 3; q++) {
      const phi = (q / 3) * TOUR - y * 0.02 + t * 6;
      cube(sc, [CIBLE[0] + Math.cos(phi) * rr * 0.32, y, CIBLE[2] + Math.sin(phi) * rr * 0.32], rr * 0.42, [n, q], brille(mix(BLANC, GLACE_V, hache(n, q, 510) * 0.5)), true);
      const psi = (q / 3) * TOUR + y * 0.03 - t * 7;
      cube(sc, [CIBLE[0] + Math.cos(psi) * rr * 1.25, y, CIBLE[2] + Math.sin(psi) * rr * 1.25], 5 * (1 + y / 1500), [n, q], BLANC, true);
    }
  }
  // en haut, la tempête s'étale comme un nuage
  if (H > 2400) {
    const e = entre(H, 2400, 3400);
    coquille(sc, [CIBLE[0], H, CIBLE[2]], [300 + 500 * e, 90 + 60 * e, 300 + 500 * e], 50, (p, d) => (hache(Math.floor(p[0]), Math.floor(p[2]), 511) < 0.3 ? null : mix(BLANC, GLACE_C, 0.3 * d)));
  }
  // au pied, des blocs de glace qui tournent
  for (let q = 0; q < 24; q++) {
    const phi = (q / 24) * TOUR - t * 3, rr = R0 * (1.5 + 0.5 * hache(q, 1, 512));
    morceau(sc, [CIBLE[0] + Math.cos(phi) * rr, 10 + 60 * hache(q, 2, 512), CIBLE[2] + Math.sin(phi) * rr], 6 + 6 * hache(q, 3, 512), q, false);
  }
}

/** Le lanceur : il colle ses deux mains et les pousse en avant, puis lève la tête vers la boule. */
function lanceurGlace(sc, t) {
  const p = sortie(entre(t, 0, 0.28)), relache = entre(t, 4.5, 5.2);
  const rx = -0.6 - 0.95 * p + 0.9 * relache;
  const T = niveau3(sc, "glace", {
    brasD: [rx, 0, 0.32 * (1 - relache)], brasG: [rx, 0, -0.32 * (1 - relache)], penche: 0.14 * p * (1 - relache),
    jambeD: -0.35 * p, jambeG: 0.35 * p, tete: [-0.35 * entre(t, 0.9, 1.6) * (1 - entre(t, 2.6, 3)), 0, 0],
  });
  // le givre qui part de ses mains
  const g = entre(t, 0.18, 0.3) * (1 - entre(t, 0.55, 0.9));
  if (g > 0) {
    const m = fois(ajoute(T.brasD.p([-6, 11, 0]), T.brasG.p([6, 11, 0])), 0.5);
    for (let q = 0; q < 12; q++) {
      const a = (q / 12) * TOUR;
      cristal(sc, m, [Math.cos(a) * 0.6, Math.sin(a) * 0.6, 1], (5 + 6 * hache(q, 1, 513)) * g, 2.2);
      cube(sc, ajoute(m, [Math.cos(a) * 8 * g, Math.sin(a) * 8 * g, 6 + 10 * g]), 1.2, [q, a], BLANC, true);
    }
  }
}

/** Le Hyoton, au temps t (en secondes). */
function hyoton(sc, t) {
  nuages(sc);
  const Rf = 300 * sortie(entre(t, 2.88, 3.8));
  terrain(sc, -900, -300, 900, 1400, 10, (x, z) => {
    const r = Math.hypot(x - CIBLE[0], z - CIBLE[2]);
    if (r > Rf + 18 * Math.sin(Math.atan2(z - CIBLE[2], x) * 7)) return null;
    return fissure(x + 500, z + 500, 514, 30) < 0.5 ? GLACE : mix(GLACE_C, BLANC, 0.2 + 0.6 * hache(Math.floor(x / 10), Math.floor(z / 10), 515));
  });
  lanceurGlace(sc, t);
  anneau(sc, t);
  // le pylône, puis ses morceaux quand le dragon passe
  const hp = H_PYLONE * sortie(entre(t, 0.5, 0.78));
  if (t < 2.5) pylone(sc, hp);
  else if (t < 3.3) for (let q = 0; q < 18; q++) {
    const c = entre(t, 2.5, 3.3), y = (q / 18) * H_PYLONE * (1 - c * 0.7) - 200 * c * c;
    if (y > 0) morceau(sc, [CIBLE[0] + (hache(q, 1, 516) - 0.5) * 80 * c, y, CIBLE[2] + (hache(q, 2, 516) - 0.5) * 80 * c], 12, q + 100, false);
  }
  // la cible : debout, projetée, tirée dans la boule ; après, gelée au milieu de la tempête
  const Rb = 132 * sortie(entre(t, 1.5, 2.05)) * (1 - entre(t, 2.4, 2.85));
  if (t < 0.5) cibleDebout(sc, CIBLE, [0, 0, 0]);
  else if (Rb < 40 && t < 2.4) {
    const y = t < 1.3 ? hp : hp + (BOULE[1] - 16 - hp) * sortie(entre(t, 1.3, 1.75));
    givre(sc, cibleEnLAir(sc, [CIBLE[0], y, CIBLE[2]], -0.4 - 0.6 * entre(t, 0.5, 1.3)));
  }
  // deux autres, à côté : ils bougent pendant ce temps, puis gèlent
  const gelees = [[-170, 0, 290], [180, 0, 250]];
  for (const p of gelees) {
    const C = cibleDebout(sc, p, CIBLE, t > 3 ? { c: GLACE_C, k: 0.5 } : null);
    if (t > 3) givre(sc, C);
  }
  jets(sc, t);
  bouleGlace(sc, Rb, t);
  // le dragon : il sort de la boule par le côté, fait une boucle et plonge en diagonale ; son corps suit le chemin de sa
  // tête (on le voit de profil, de la caméra du joueur)
  const v = sortie(entre(t, 2.36, 2.88));
  if (t >= 2.36 && t < 3.05) {
    const Q = (s) => bezier(BOULE, ajoute(BOULE, [230, 70, -40]), ajoute(CIBLE, [190, 360, -20]), ajoute(CIBLE, [62, 118, 0]), s);
    const ctrl = [];
    for (let q = 0; q <= 8; q++) ctrl.push(Q(Math.max(0, v - (q / 8) * Math.min(v, 0.85))));
    if (v > 0.04) grandDragon(sc, ctrl, 28, "glace", { gueule: 1.05 });
  }
  // l'impact : une gerbe d'éclats énormes, une onde au sol, et la tempête
  if (t >= 2.88) {
    const g = sortie(entre(t, 2.88, 3.15)), fin = 1 - 0.5 * entre(t, 4, 6);
    for (let q = 0; q < 46; q++) {
      const a = (q / 46) * TOUR + hache(q, 1, 517), k = 0.35 + 0.65 * hache(q, 2, 517);
      const u = norme([Math.cos(a) * k, 0.3 + 1.0 * hache(q, 3, 517), Math.sin(a) * k]);
      cristal(sc, ajoute(CIBLE, fois(u, 12)), u, (90 + 190 * hache(q, 4, 517)) * g * fin, 16 + 12 * hache(q, 5, 517));
    }
    const ro = 520 * sortie(entre(t, 2.88, 3.6)), al = 1 - entre(t, 3.2, 3.8);
    if (al > 0) for (let q = 0; q < 70; q++) {
      const a = (q / 70) * TOUR;
      cube(sc, [CIBLE[0] + Math.cos(a) * ro, 4, CIBLE[2] + Math.sin(a) * ro], 12 * al, [0, a], brille(BLANC), true);
    }
    tempete(sc, t);
    for (const p of gelees) blocDeGlace(sc, p);
  }
  sc.lueur(GLACE_V, 10, 1.6);
  if (t >= 2.95) for (let y = 0; y < Math.min(1600, 3400 * sortie(entre(t, 2.95, 4.4))); y += 160) brume(sc, CIBLE[0], CIBLE[2], (90 + 40 * sortie(entre(t, 2.95, 3.7))) * (1.6 + y / 1300), y, y + 160, 0.1);
  flash(sc, 0.75 * (1 - entre(t, 2.88, 3.12)) * (t >= 2.88 ? 1 : 0), [235, 248, 255]);
}

// ------------------------------------------------------------------ la lave : le soleil de magma
const ZONE = [60, 0, 1150], H_BOND = 270;

/** La hauteur du lanceur : le bond, la pause en l'air, puis il redescend doucement. */
const hauteurLave = (t) => H_BOND * sortie(entre(t, 0.05, 0.6)) * (1 - entree(entre(t, 4.6, 6.4)));

/** Le rayon de la sphère : elle grossit petit à petit, tant que le magma arrive. */
const rayonSoleil = (t) => 380 * entre(t, 0.75, 3.2) ** 0.85;

/** Le centre de la sphère : au-dessus de sa main, puis lancée vers la zone qu'il vise. */
function centreSoleil(t, main) {
  const tenu = ajoute(main, [0, rayonSoleil(t) + 14, 0]);
  const v = entree(entre(t, 3.55, 4.35));
  if (v <= 0) return tenu;
  const fin = ajoute(ZONE, [0, 60, 0]), haut = ajoute(fois(ajoute(tenu, fin), 0.5), [0, 140, 0]);
  return bezier(tenu, haut, haut, fin, v);
}

/** La couleur du magma de la sphère : de grandes plaques de croûte sombre qui glissent, et des fissures qui brillent. */
function surfaceSoleil(c, R, t) {
  return (p) => {
    const x = p[0] - c[0], y = p[1] - c[1], z = p[2] - c[2], l = Math.hypot(x, y, z) || 1;
    const u = (Math.atan2(z, x) + t * 0.5) * (R / 22), v = Math.acos(y / l) * (R / 22);
    const f = fissure(u + 100, v + 100, 520, 4);
    if (f < 0.5) return brille(mix([255, 250, 214], LAVE_J, f / 0.5));
    if (f < 0.95) return brille(mix(LAVE_J, LAVE_O, (f - 0.5) / 0.45));
    if (f < 1.35) return brille(mix(LAVE_O, LAVE_R, (f - 0.95) / 0.4));
    return mix(CROUTE, CROUTE_R, 0.3 + 0.5 * hache(Math.floor(u * 2), Math.floor(v * 2), 521));
  };
}

/** La sphère de magma : la surface, une couronne de flammes qui en sort, des braises qui tournent autour. */
function soleil(sc, c, R, t) {
  if (R < 3) return;
  coquille(sc, c, [R, R, R], Math.max(4, R / 15), surfaceSoleil(c, R, t));
  for (let q = 0; q < 70; q++) {
    const u = norme([hache(q, 1, 522) - 0.5, hache(q, 2, 522) - 0.5, hache(q, 3, 522) - 0.5]);
    flamme(sc, ajoute(c, fois(u, R * 0.93)), norme(ajoute(u, [0, 0.6, 0])), R * (0.18 + 0.32 * hache(q, 4, 522)), R * 0.1);
  }
  for (let q = 0; q < 50; q++) {
    const a = q * 2.4 + t * 2, rr = R * (1.2 + 0.5 * hache(q, 1, 523)), y = (hache(q, 2, 523) - 0.5) * R * 2.2;
    cube(sc, ajoute(c, [Math.cos(a) * rr, y, Math.sin(a) * rr]), R * 0.025 + 1.5, [q, a], brille(q % 3 ? LAVE_O : LAVE_J), true);
  }
}

/**
 * Le magma qui monte du sol vers la sphère : des coulées en arc, l'une après l'autre (de plus en plus nombreuses), qui
 * partent de geysers devant et autour de lui.
 */
function affluents(sc, t, c) {
  if (t < 0.75 || t > 3.45) return;
  for (let q = 0; q < 40; q++) {
    const debut = 0.75 + (q / 40) * 2.2, u = entre(t, debut, debut + 0.9);
    if (u <= 0 || u >= 1) continue;
    const a = -0.25 * Math.PI + 1.5 * Math.PI * hache(q, 1, 524), d = 300 + 700 * hache(q, 2, 524);
    const S = [Math.cos(a) * d, 0, 320 + Math.sin(a) * d * 0.7];
    const C1 = ajoute(S, [0, 240 + 220 * hache(q, 3, 524), 0]), C2 = ajoute(c, [(S[0] - c[0]) * 0.4, 260, (S[2] - c[2]) * 0.3]);
    const tete = sortie(u), queue = Math.max(0, tete - 0.32);
    for (let k = 0; k <= 14; k++) {
      const s = queue + ((tete - queue) * k) / 14, p = bezier(S, C1, C2, c, s), r = 3 + 6 * (k / 14);
      cube(sc, p, r * 1.2, [k, q], k % 5 === 2 ? mix(CROUTE, CROUTE_R, 0.6) : brille(mix(LAVE_R, LAVE_J, k / 14)), true);
      if (k % 3 === 0) flamme(sc, p, [0, 1, 0], r * 2.4, r * 1.1);
    }
    // le geyser au sol, au départ
    const g = 1 - entre(u, 0.15, 0.55);
    if (g > 0) for (let k = 0; k < 6; k++) flamme(sc, ajoute(S, [(hache(q, k, 525) - 0.5) * 34, 0, (hache(q, k, 526) - 0.5) * 34]), [0, 1, 0], (36 + 40 * hache(q, k, 527)) * g, 13);
  }
}

/** La couleur du feu de l'explosion : blanc au cœur, puis jaune, orange, rouge, et de la fumée en refroidissant. */
function feu(k) {
  k = borne(k);
  if (k < 0.22) return brille([255, 252, 228]);
  if (k < 0.42) return brille(mix([255, 246, 190], LAVE_J, (k - 0.22) / 0.2));
  if (k < 0.6) return brille(mix(LAVE_J, LAVE_O, (k - 0.42) / 0.18));
  if (k < 0.74) return brille(mix(LAVE_O, LAVE_R, (k - 0.6) / 0.14));
  if (k < 0.86) return mix([150, 50, 26], [84, 50, 42], (k - 0.74) / 0.12);
  return mix([84, 66, 60], [52, 46, 46], (k - 0.86) / 0.14);
}

/**
 * L'explosion : une boule de feu tourmentée (une coquille trouée sur un cœur blanc), puis un champignon qui monte —
 * le dessous rougeoie, le dessus fume —, une onde de poussière au sol, des blocs de magma qui volent en cloche.
 */
function explosionLave(sc, t) {
  const e = entre(t, 4.35, 5.0), ch = entre(t, 4.6, 7.4);
  if (e <= 0) return;
  const Rx = 700 * sortie(e), bruitV = (p, n) => hache(Math.floor(p[0] / 30), Math.floor(p[1] / 30) * 7 + Math.floor(p[2] / 30), n) - 0.5;
  // la boule de feu, qui s'aplatit en montant
  const cb = [ZONE[0], 40 + Rx * 0.55 * (1 - 0.5 * ch), ZONE[2]], ry = Rx * (0.85 - 0.35 * ch);
  coquille(sc, cb, [Rx * 0.62, ry * 0.62, Rx * 0.62], 40, (p) => (p[1] < -8 ? null : feu(0.1 + 0.5 * ch)));
  coquille(sc, cb, [Rx, ry, Rx], 36, (p, d) => (p[1] < -8 || bruitV(p, 540) > 0.3 ? null : feu(0.3 + 0.35 * ch + (p[1] - cb[1]) / 2200 + bruitV(p, 541) * 0.4)));
  for (let q = 0; q < 50; q++) {
    const u = norme([hache(q, 1, 542) - 0.5, Math.abs(hache(q, 2, 542) - 0.3), hache(q, 3, 542) - 0.5]);
    flamme(sc, ajoute(cb, [u[0] * Rx * 0.9, u[1] * ry * 0.9, u[2] * Rx * 0.9]), u, Rx * (0.18 + 0.25 * hache(q, 4, 542)) * (1 - 0.6 * ch), Rx * 0.08);
  }
  // le champignon
  if (ch > 0) {
    const yc = 400 + 1700 * sortie(ch), rc = Rx * (0.7 + 0.7 * sortie(ch));
    coquille(sc, [ZONE[0], yc, ZONE[2]], [rc, rc * 0.42, rc], 44, (p, d) => (bruitV(p, 543) > 0.38 ? null : feu(0.45 + 0.4 * ch + (p[1] - yc) / (rc * 0.6) + bruitV(p, 544) * 0.3)));
    for (let y = cb[1]; y < yc; y += 46) {
      const r = Rx * (0.28 + 0.06 * Math.sin(y * 0.02));
      coquille(sc, [ZONE[0], y, ZONE[2]], [r, 28, r], 30, (p) => feu(0.35 + 0.35 * ch + (y / yc) * 0.25 + bruitV(p, 545) * 0.3));
    }
  }
  // l'onde de poussière au sol
  const ro = 2000 * sortie(entre(t, 4.35, 5.7)), al = 1 - entre(t, 5.3, 6.6);
  if (al > 0) for (let q = 0; q < 110; q++) {
    const a = (q / 110) * TOUR, h = hache(q, 1, 546);
    cube(sc, [ZONE[0] + Math.cos(a) * ro * (0.95 + 0.1 * h), 20 * h, ZONE[2] + Math.sin(a) * ro * (0.95 + 0.1 * h)], (34 + 30 * h) * al, [q, a], h < 0.3 ? brille(LAVE_O) : mix([160, 132, 108], [118, 98, 84], h), h < 0.3);
  }
  // des blocs de magma qui volent en cloche
  for (let q = 0; q < 50; q++) {
    const a = hache(q, 1, 527) * TOUR, v = 500 + 800 * hache(q, 2, 527), up = 500 + 700 * hache(q, 3, 527), s = (t - 4.35) * 1.3;
    const p = [ZONE[0] + Math.cos(a) * v * s, 30 + up * s - 520 * s * s, ZONE[2] + Math.sin(a) * v * s];
    if (p[1] > 0) {
      morceau(sc, p, 12 + 16 * hache(q, 4, 527), q + 300, true);
      flamme(sc, p, [0, 1, 0], 30, 14);
    }
  }
  // des flammes partout au sol
  for (let q = 0; q < 50; q++) {
    const a = hache(q, 1, 528) * TOUR, d = Rx * (0.5 + 0.9 * hache(q, 2, 528));
    flamme(sc, [ZONE[0] + Math.cos(a) * d, 0, ZONE[2] + Math.sin(a) * d], [0, 1, 0], 50 + 80 * hache(q, 3, 528), 26);
  }
}

/** Le lanceur de lave : le bond, la main levée, puis le lancer vers l'avant. */
function lanceurLave(sc, t) {
  const y = hauteurLave(t), leve = sortie(entre(t, 0.45, 0.8)), lance = sortie(entre(t, 3.45, 3.75));
  const rx = -0.2 - 2.85 * leve + 1.6 * lance;
  const T = niveau3(sc, "lave", {
    decale: [0, y, 0], brasG: [rx, 0.25 * leve, 0], brasD: [0.5 - 0.4 * leve, -0.5, 0], penche: 0.25 * lance,
    jambeD: 0.5 * (1 - leve) + 0.25, jambeG: -0.6 * (1 - leve) - 0.1, tete: [-0.5 * leve * (1 - lance), 0, 0],
  });
  // le bond : un cratère de flammes au départ, une traînée sous lui
  const b = 1 - entre(t, 0.3, 0.9);
  if (b > 0) for (let q = 0; q < 16; q++) {
    const a = (q / 16) * TOUR;
    flamme(sc, [Math.cos(a) * 24, 0, Math.sin(a) * 24], [Math.cos(a) * 0.4, 1, Math.sin(a) * 0.4], (20 + 20 * hache(q, 1, 529)) * b, 9);
  }
  if (t < 0.7) for (let q = 0; q < 10; q++) cube(sc, [(hache(q, 1, 530) - 0.5) * 16, y * (q / 10), (hache(q, 2, 530) - 0.5) * 16], 3, [q, q], brille(q % 2 ? LAVE_O : LAVE_J), true);
  return T.brasG.p([6, 11, 0]);
}

/** Le soleil de magma, au temps t. */
function soleilDeMagma(sc, t) {
  nuages(sc);
  const rBrule = 520 * sortie(entre(t, 4.35, 5.4));
  terrain(sc, -1500, -400, 1500, 2200, 14, (x, z) => {
    const r = Math.hypot(x - ZONE[0], z - ZONE[2]);
    if (r < rBrule) {
      if (r < rBrule * 0.55) return magma(Math.floor(x / 14) + 200, Math.floor(z / 14) + 200, 531, 4, 1.8);
      return fissure(x / 14 + 300, z / 14 + 300, 532, 5) < 0.6 ? brille(LAVE_O) : mix([56, 42, 34], [90, 70, 52], hache(Math.floor(x / 14), Math.floor(z / 14), 533) * 0.5);
    }
    // sous lui, le sol se fend pendant que le magma monte
    const rc = Math.hypot(x, z - 180);
    if (t > 0.75 && t < 4.6 && rc < 560 * entre(t, 0.75, 2.6)) {
      const f = fissure(x / 14 + 600, z / 14 + 600, 534, 9);
      if (f < 0.32) return brille(f < 0.16 ? LAVE_J : LAVE_O);
      if (f < 0.9) return mix(HERBE, [62, 46, 34], 0.55 + 0.2 * hache(Math.floor(x / 14), Math.floor(z / 14), 535));
    }
    // la zone visée, en rouge
    if (t > 2.6 && t < 4.4 && Math.abs(Math.hypot(x - ZONE[0], z - ZONE[2]) - 190) < 9) return brille([255, 70, 40]);
    return null;
  });
  const main = lanceurLave(sc, t);
  // des joueurs autour de la zone, soufflés par l'explosion
  for (const [x, z, k] of [[-120, 1080, 0], [240, 1220, 1], [30, 1320, 2], [340, 1020, 3]]) {
    const s = entre(t, 4.35, 5.4);
    if (s <= 0) cibleDebout(sc, [x, 0, z], main);
    else {
      const d = norme([x - ZONE[0], 0, z - ZONE[2]]), p = [x + d[0] * 1000 * s, 500 * s - 360 * s * s + 60 * s, z + d[2] * 1000 * s];
      brule(sc, cibleEnLAir(sc, p, -1.2 + 0.3 * k), 12);
    }
  }
  affluents(sc, t, centreSoleil(t, main));
  if (t < 4.35) {
    const c = centreSoleil(t, main);
    soleil(sc, c, rayonSoleil(t), t);
    // la traînée de feu et de fumée derrière la sphère lancée
    if (t > 3.55) for (let q = 1; q < 14; q++) {
      const p = centreSoleil(t - q * 0.04, main);
      cube(sc, p, rayonSoleil(t) * (0.5 - q * 0.03), [q, q], q < 8 ? brille(q < 4 ? LAVE_J : LAVE_O) : mix(FUMEE, [60, 50, 46], 0.4), q < 8);
    }
  }
  explosionLave(sc, t);
  sc.lueur(LAVE_O, 12, 1.6);
  flash(sc, 0.8 * (1 - entre(t, 4.35, 4.6)) * (t >= 4.35 ? 1 : 0), [255, 236, 190]);
}

// ------------------------------------------------------------------ les caméras (celle du joueur, de dos)
/** Le centre de visée d'une caméra posée en pos, qui regarde selon lacet et tangage (en degrés), à recul de ce centre. */
function depuis(pos, lacet, tangage, recul) {
  const a = (lacet * Math.PI) / 180, b = (tangage * Math.PI) / 180;
  return sous(pos, fois([-Math.sin(a) * Math.cos(b), Math.sin(b), Math.cos(a) * Math.cos(b)], recul));
}

/** Une valeur qui passe en douceur d'une clé à l'autre : l = [[t, valeur], ...]. */
function cles(t, l) {
  if (t <= l[0][0]) return l[0][1];
  for (let i = 1; i < l.length; i++) {
    if (t > l[i][0]) continue;
    const u = (t - l[i - 1][0]) / (l[i][0] - l[i - 1][0]);
    return l[i - 1][1] + (l[i][1] - l[i - 1][1]) * u * u * (3 - 2 * u);
  }
  return l[l.length - 1][1];
}

/**
 * La caméra du joueur de glace : posée derrière lui, au-dessus de l'épaule ; elle ne bouge pas, elle lève les yeux vers la
 * cible et la boule, suit le dragon, puis la tempête. k = largeur / 960. Une secousse à l'impact.
 */
function cameraGlace(t, k = 1, secousse = true) {
  const s = secousse && t >= 2.88 ? 10 * (1 - entre(t, 2.88, 3.4)) : 0;
  const tangage = cles(t, [[0, -3], [0.6, -5], [1.7, -21], [2.4, -21], [2.85, -7], [3.3, -9], [4.6, -24]]);
  const pos = [44 + s * Math.sin(t * 90), 66 + s * Math.cos(t * 70), -120];
  return { lacet: 188, tangage, echelle: 0.85 * k, recul: 380, pied: 270 * k, centre: depuis(pos, 188, tangage, 380), fond: CIEL };
}

/** La caméra du joueur de lave : elle le suit quand il bondit, lève les yeux vers la sphère, puis regarde où il la lance. */
function cameraLave(t, k = 1, secousse = true) {
  const s = secousse && t >= 4.35 ? 18 * (1 - entre(t, 4.35, 5)) : 0;
  const tangage = cles(t, [[0, -2], [0.5, -10], [1.4, -32], [3.4, -32], [3.9, 0], [4.4, 3], [5.0, -6], [6.6, -22]]);
  const pos = [-60 + s * Math.sin(t * 90), hauteurLave(t) * 0.92 + 44 + s * Math.cos(t * 70), -300];
  return { lacet: 176, tangage, echelle: 0.5 * k, recul: 420, pied: 270 * k, centre: depuis(pos, 176, tangage, 420), fond: CIEL };
}

// ------------------------------------------------------------------ la planche
const TECH = { glace: { dessin: hyoton, camera: cameraGlace, duree: 6.5 }, lave: { dessin: soleilDeMagma, camera: cameraLave, duree: 7.5 } };

function image(sorte, t, w = 960, h = 540, secousse = true) {
  const { dessin, camera } = TECH[sorte];
  const sc = scene({ w, h, ...camera(t, w / 960, secousse) });
  dessin(sc, t);
  return sc;
}

const VW = 640, VH = 360, M = 34, LARGE = 760, HAUT = 690;
const NOIR = [30, 30, 40, 255], ORANGE = [214, 92, 20, 255], BLEU = [40, 110, 200, 255];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

const GLACE_ETAPES = [
  [0.32, "1. IL COLLE SES MAINS, LES POUSSE : L'ANNEAU"], [0.8, "2. LE PYLONE LE PROJETTE EN L'AIR"], [1.42, "3. LES JETS DE GLACE MONTENT SUR LUI"],
  [2.1, "4. ILS FORMENT UNE GROSSE BOULE"], [2.72, "5. LA BOULE DEVIENT UN DRAGON QUI PIQUE"], [3.6, "6. LA TEMPETE DE GLACE MONTE"],
];
const LAVE_ETAPES = [
  [0.5, "1. IL BONDIT TRES HAUT"], [1.6, "2. MAIN LEVEE, LE MAGMA MONTE VERS LUI"], [3.0, "3. UNE SPHERE ENORME AU-DESSUS DE LUI"],
  [3.9, "4. IL LA LANCE OU IL VISE"], [4.55, "5. L'IMPACT"], [5.6, "6. L'EXPLOSION EN CHAMPIGNON"],
];

/** Les vues de loin : où regarder, et où poser la règle en blocs ([x, z, hauteur max]). */
const LOIN = {
  glace: [{ t: 5.2, w: VW * 3, lacet: 200, echelle: 0.19, centre: [0, 0, 230], regle: [-900, 230, 3600], titre: "DE TRES LOIN : LA TEMPETE MONTE A PLUS DE 200 BLOCS" }],
  lave: [
    { t: 3.2, w: VW * 1.5, lacet: 150, echelle: 0.55, centre: [0, 0, 0], regle: [800, 0, 1200], titre: "DE LOIN : LA SPHERE, 48 BLOCS DE LARGE" },
    { t: 6.4, w: VW * 1.5, lacet: 150, echelle: 0.26, centre: [0, 0, 1150], regle: [1700, 1150, 2400], titre: "DE LOIN : LE CHAMPIGNON, 130 BLOCS DE HAUT" },
  ],
};

/** Une vue de très loin (la tempête, la sphère, le champignon en entier), avec une règle : un trait tous les 25 blocs. */
function vueDeLoin(sorte, v) {
  const sc = scene({ w: v.w, h: LARGE, lacet: v.lacet, tangage: 2, echelle: v.echelle, pied: LARGE - 70, centre: v.centre, fond: CIEL });
  TECH[sorte].dessin(sc, v.t);
  const [x, z, max] = v.regle;
  for (let y = 0; y <= max; y += 400) {
    const [sx, sy] = sc.vue([x, y, z]);
    if (sy < 12 || sy > sc.h - 50 || sx < 4 || sx > sc.w - 220) continue;
    rect(sc.img, sc.w, Math.round(sx) - 3, Math.round(sy) - 1, Math.round(sx) + 40, Math.round(sy) + 2, [40, 40, 50, 255]);
    ecrireTexte(sc.img, sc.w, (y / 16) + " BLOCS", Math.round(sx) + 48, Math.round(sy) - 8, 3, [40, 40, 50, 255]);
  }
  return sc;
}

function entete(img, W) {
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, "FRUITS DU DEMON : LES ATTAQUES ULTIMES DU NIVEAU 3", 30, 22, 5, NOIR);
  const colonnes = [
    [30, BLEU, "GLACE : HYOTON, LA TEMPETE DU DRAGON", [
      "ACCROUPI UNE FOIS : IL COLLE SES DEUX MAINS ET LES POUSSE EN AVANT",
      "LA CIBLE VISEE A MI-DISTANCE (6 A 20 BLOCS) : UN ANNEAU DE GIVRE SOUS ELLE",
      "UN PYLONE DE GLACE LA PROJETTE A 15 BLOCS DE HAUT",
      "12 JETS DE GLACE MONTENT SUR ELLE : UNE BOULE DE 16 BLOCS DE LARGE",
      "LA BOULE DEVIENT UN DRAGON QUI CLAQUE AU SOL AVEC ELLE DEDANS",
      "UNE TEMPETE DE GLACE EN SORT ET MONTE A PLUS DE 200 BLOCS",
      "DEGATS : PYLONE 4, CLAQUE 24 A LA CIBLE ET 14 A 5 BLOCS AUTOUR,",
      "    TEMPETE 6 BLOCS : 1 PAR 1/2 S PENDANT 4 S, GELE SUR PLACE",
    ]],
    [990, ORANGE, "LAVE : LE SOLEIL DE MAGMA", [
      "ACCROUPI UNE FOIS : IL BONDIT A 17 BLOCS, LEVE UNE MAIN, RESTE EN L'AIR 3 S",
      "LE MAGMA ET LES FLAMMES MONTENT DU SOL, PETIT A PETIT : UNE SPHERE",
      "    DE 48 BLOCS DE LARGE AU-DESSUS DE SA MAIN",
      "IL VISE (UN CERCLE ROUGE AU SOL, JUSQU'A 80 BLOCS) ET LA LANCE",
      "BOULE DE FEU DE 90 BLOCS DE LARGE, CHAMPIGNON A 130 BLOCS, ONDE AU SOL",
      "IL REDESCEND DOUCEMENT, SANS DEGAT DE CHUTE",
      "DEGATS : 30 AU MILIEU, 16 AU BORD (20 BLOCS), BRULE 10 S, SOUFFLE",
      "    LE SOL BRULE 5 S PUIS REDEVIENT NORMAL",
    ]],
  ];
  for (const [x, c, titre, lignes] of colonnes) {
    ecrireTexte(img, W, titre, x, 100, 4, c);
    lignes.forEach((t, k) => ecrireTexte(img, W, t, x, 160 + k * 34, 3, NOIR));
  }
  rect(img, W, 30, 450, W - 30, 453, [200, 200, 210, 255]);
  [
    "LES ULTIMES : ACCROUPI UNE FOIS, UNE SEULE FOIS PAR EVEIL 3, ET 5 MIN DE RECHARGE (LE REPOS COMPRIS). EN DESSUS DES NIVEAUX 3 : UN NIVEAU 4",
    "PAS DE CINEMATIQUE : TOUT SE PASSE EN JEU, LA CAMERA RESTE LA SIENNE, LES AUTRES BOUGENT PENDANT CE TEMPS (LES IMAGES SONT VUES PAR SA CAMERA)",
    "LE DRAGON AU CLIC DROIT RESTE A VOLONTE AVEC SA RECHARGE, QUE JE PASSE A 20 S (PLUSIEURS FOIS PAR EVEIL)",
    "A COTE : SUSANO 3 GRAND COUP 22, MINATO 2 BIJUU DAMA 2 + 14 + 14 ET LE DOME 12, LE DRAGON 8 + 20",
  ].forEach((t, k) => ecrireTexte(img, W, t, 30, 472 + k * 34, 3, NOIR));
  ecrireTexte(img, W, "LES VIDEOS : HYOTON.MP4 ET SOLEIL.MP4, A COTE DE CETTE PLANCHE", 30, 472 + 4 * 34 + 20, 3, [90, 90, 110, 255]);
}

function planche() {
  const W = VW * 3, bloc = 2 * (M + VH) + M + LARGE, H = HAUT + 2 * bloc;
  const img = Buffer.alloc(W * H * 4, 255);
  entete(img, W);
  let y = HAUT;
  for (const [sorte, etapes, c] of [["glace", GLACE_ETAPES, BLEU], ["lave", LAVE_ETAPES, ORANGE]]) {
    etapes.forEach(([t, titre], k) => {
      const sc = image(sorte, t, VW, VH, false), c0 = k % 3, l0 = Math.floor(k / 3);
      for (let r = 0; r < VH; r++) sc.img.copy(img, ((y + l0 * (M + VH) + M + r) * W + c0 * VW) * 4, r * VW * 4, (r + 1) * VW * 4);
      ecrireTexte(img, W, titre, c0 * VW + 12, y + l0 * (M + VH) + 10, 3, c);
    });
    y += 2 * (M + VH);
    let x0 = 0;
    for (const v of LOIN[sorte]) {
      const sc = vueDeLoin(sorte, v);
      for (let r = 0; r < LARGE; r++) sc.img.copy(img, ((y + M + r) * W + x0) * 4, r * sc.w * 4, (r + 1) * sc.w * 4);
      ecrireTexte(img, W, v.titre, x0 + 12, y + 10, 3, c);
      x0 += sc.w;
    }
    y += M + LARGE;
  }
  ecrirePng(path.join(DOC, "planche-ultimes.png"), W, H, img);
  console.log("ok", W, H);
}

export { hyoton, soleilDeMagma, image, vueDeLoin, TECH };

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  const [mode, sorte, a3, a4] = process.argv.slice(2);
  if (mode === "image") {
    const sc = image(sorte, Number(a3));
    ecrirePng(a4 ?? path.join(DOC, "image.png"), sc.w, sc.h, sc.img);
    console.log("ok", sorte, a3);
  } else if (mode === "loin") {
    const sc = vueDeLoin(sorte, LOIN[sorte][Number(a3)]);
    ecrirePng(a4 ?? path.join(DOC, "image.png"), sc.w, sc.h, sc.img);
    console.log("ok loin", sorte, a3);
  } else if (mode === "video") {
    // 24 images par seconde, dans le dossier a3 (ffmpeg les assemble ensuite)
    fs.mkdirSync(a3, { recursive: true });
    const n = Math.round(TECH[sorte].duree * 24), de = Number(a4 ?? 0), pas = Number(process.argv[7] ?? 1);
    for (let i = de; i < n; i += pas) {
      const sc = image(sorte, i / 24);
      ecrirePng(path.join(a3, String(i).padStart(4, "0") + ".png"), sc.w, sc.h, sc.img);
    }
    console.log("ok video", sorte, n);
  } else planche();
}
