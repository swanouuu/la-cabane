// Croquis de la refonte d'Ichibi en trois niveaux + l'ulti (rien en jeu pour l'instant).
//   toujours : la grosse jarre de Gaara sur le dos ; à l'éveil le bouchon saute et le sable tourne autour de lui.
//   niveau 1 : le sable de Gaara (poings de sable droite / gauche, cercueil de sable), pas de rire.
//   niveau 2 : la moitié d'Ichibi d'aujourd'hui, avec la jarre et le rire ; la grande main ; le cercueil ; le mausolée.
//   niveau 3 : Ichibi prend le contrôle (bête de sable à quatre pattes, deux grands bras, grosse queue) ; balle d'air.
//   ulti (J) : Shukaku entier, 28 blocs, lui debout sur son front.
// node refonte.mjs → ../planche-refonte.png ; node refonte.mjs essai 0,5 sortie.png : quelques vignettes en grand.
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, clair, mix, hache, norme, croix, scene, ecrireTexte } from "../../fruits/generateur/moteur.mjs";
import { ajoute, sous, fois, rotX, rotY, TOUR, ID, pave, cube, pointe, perso, sol, legende, FOND, HERBE, STEVE } from "../../fruits/generateur/rendu.mjs";
import {
  bruit, sable, SABLE, SABLE_C, SABLE_F, ENCRE, NOIR, VERT, VERT_C, VERT_F, PUPILLE, TOUCHE, PEAU, griffe, motif, joueur, membre,
  brasGeant, main, oreille, queue, grains, eclat, prise, proie, tenir, nuage, auSol, POING, PROIE,
} from "./rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");

// ------------------------------------------------------------------ couleurs
const GOURDE = [206, 160, 104];
const GOURDE_C = [226, 186, 132];
const GOURDE_F = [150, 106, 64];
const SCEAU = [96, 62, 36];
const SANGLE = [118, 114, 140];
const BOUCHON = [92, 64, 44];
// le sable de Gaara : plus clair et plus jaune que celui d'Ichibi, sans les traits d'encre
const DUNE = [222, 196, 150];
const DUNE_C = [238, 218, 178];
const DUNE_F = [186, 156, 112];
const AIR = [214, 234, 246];
const AIR_F = [150, 190, 222];
const BOUCHE = [70, 22, 26];
const DENT = [246, 242, 228];
const JAUNE = [250, 206, 60];
const OR = [255, 214, 92];

// ------------------------------------------------------------------ les volumes (champs de distance → cubes)
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const lon = (v) => Math.hypot(v[0], v[1], v[2]);
const borne = (x, a, b) => Math.max(a, Math.min(b, x));
const boule = (c, r) => (p) => lon(sous(p, c)) - r;
/** Un ellipsoïde (distance approchée). */
const ellip = (c, [a, b, e]) => (p) => (Math.hypot((p[0] - c[0]) / a, (p[1] - c[1]) / b, (p[2] - c[2]) / e) - 1) * Math.min(a, b, e);
/** Un boudin de a à b, le rayon passe de ra à rb. */
const boudin = (a, b, ra, rb = ra) => {
  const ba = sous(b, a), bb = dot(ba, ba);
  return (p) => {
    const pa = sous(p, a), h = borne(dot(pa, ba) / bb, 0, 1);
    return lon(sous(pa, fois(ba, h))) - (ra + (rb - ra) * h);
  };
};
/** Une boîte arrondie (centre, demi-tailles, rayon des coins). */
const boite = (c, d, r = 0) => (p) => {
  const q = [0, 1, 2].map((i) => Math.abs(p[i] - c[i]) - d[i] + r);
  return lon(q.map((v) => Math.max(v, 0))) + Math.min(Math.max(q[0], q[1], q[2]), 0) - r;
};
const union = (...f) => (p) => { let m = 1e9; for (const g of f) { const d = g(p); if (d < m) m = d; } return m; };
/** Une union lissée (k : la largeur du raccord). */
const fondu = (k, ...f) => (p) => {
  let m = f[0](p);
  for (let i = 1; i < f.length; i++) {
    const d = f[i](p), h = borne(0.5 + (0.5 * (d - m)) / k, 0, 1);
    m = d + (m - d) * h - k * h * (1 - h);
  }
  return m;
};
/** Une chaîne de boudins le long d'une courbe de Bézier (quatre points), du rayon r0 au rayon r1. */
function bezier(P, r0, r1, n = 14) {
  const B = (t) => [0, 1, 2].map((i) => (1 - t) ** 3 * P[0][i] + 3 * (1 - t) ** 2 * t * P[1][i] + 3 * (1 - t) * t * t * P[2][i] + t ** 3 * P[3][i]);
  const f = [];
  for (let k = 0; k < n; k++) f.push(boudin(B(k / n), B((k + 1) / n), r0 + (r1 - r0) * (k / n), r0 + (r1 - r0) * ((k + 1) / n)));
  return { f: union(...f), B };
}
/** Des bosses de sable : un bruit doux en 3D (de -1 à 1). */
const bosse = (p, s, k) => 0.5 * (bruit(p[0] / s + 30, p[1] / s + p[2] / (2 * s), k) + bruit(p[2] / s + 60, p[1] / s - p[0] / (2 * s), k + 5));

/**
 * Dessine le volume f < 0 de la boîte bb, en cubes de pas (dans le repère T). couleur(p, n) donne la couleur de chaque
 * face visible (p : le centre du cube, n : la normale) ; [r, g, b, 1] brille (pas d'ombre). Les coins rentrants sont un
 * peu plus sombres. Une grille grossière d'abord : on ne recalcule finement que près du bord.
 */
/** Pour le jeu (jeu.mjs) : les cubes sont PAS.k fois plus gros (moins de faces). */
const PAS = { k: 1 };
function volume(sc, T, f, bb, pas, couleur, decale = 0) {
  pas *= PAS.k;
  const [x0, y0, z0, x1, y1, z1] = bb;
  const nx = Math.ceil((x1 - x0) / pas), ny = Math.ceil((y1 - y0) / pas), nz = Math.ceil((z1 - z0) / pas);
  const G = new Uint8Array(nx * ny * nz);
  const id = (i, j, k) => (k * ny + j) * nx + i;
  const C = 4, S = C * pas;
  for (let K = 0; K < nz; K += C) for (let J = 0; J < ny; J += C) for (let I = 0; I < nx; I += C) {
    const c = [x0 + (I + C / 2) * pas, y0 + (J + C / 2) * pas, z0 + (K + C / 2) * pas];
    const d = f(c);
    if (d > S * 1.6) continue;
    const tout = d < -S * 1.6;
    for (let k = K; k < Math.min(nz, K + C); k++) for (let j = J; j < Math.min(ny, J + C); j++) for (let i = I; i < Math.min(nx, I + C); i++) {
      G[id(i, j, k)] = tout || f([x0 + (i + 0.5) * pas, y0 + (j + 0.5) * pas, z0 + (k + 0.5) * pas]) < 0 ? 1 : 0;
    }
  }
  const plein = (i, j, k) => i >= 0 && j >= 0 && k >= 0 && i < nx && j < ny && k < nz && G[id(i, j, k)] === 1;
  const DIRS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
  const L = DIRS.map((n) => sc.lumiere(T.n(n), false));
  const h = pas / 2;
  for (let k = 0; k < nz; k++) for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) {
    if (!G[id(i, j, k)]) continue;
    const c = [x0 + (i + 0.5) * pas, y0 + (j + 0.5) * pas, z0 + (k + 0.5) * pas];
    for (let d = 0; d < 6; d++) {
      if (L[d] < 0) continue;
      const n = DIRS[d], a = d >> 1;
      if (plein(i + n[0], j + n[1], k + n[2])) continue;
      const u = [0, 0, 0], v = [0, 0, 0];
      u[(a + 1) % 3] = 1; v[(a + 2) % 3] = 1;
      let occ = 0;
      for (const du of [-1, 0, 1]) for (const dv of [-1, 0, 1]) {
        if (!du && !dv) continue;
        if (plein(i + n[0] + du * u[0] + dv * v[0], j + n[1] + du * u[1] + dv * v[1], k + n[2] + du * u[2] + dv * v[2])) occ++;
      }
      const col = couleur(c, n);
      if (!col) continue;
      const f0 = ajoute(c, fois(n, h));
      const coin = (su, sv) => T.p(ajoute(ajoute(f0, fois(u, su * h)), fois(v, sv * h)));
      const lu = col[3] === 1 ? col.slice(0, 3) : clair(col, L[d] * (1 - 0.06 * occ));
      sc.quad([coin(-1, -1), coin(1, -1), coin(1, 1), coin(-1, 1)], lu, decale, false, { normale: T.n(n), emissif: col[3] === 1 });
    }
  }
}

/** Les deux coordonnées d'une face (selon sa normale), pour y poser une texture. */
const plan = (p, n) => (Math.abs(n[2]) > 0.5 ? [p[0], p[1]] : Math.abs(n[0]) > 0.5 ? [p[2], p[1]] : [p[0], p[2]]);
/** Le sable d'Ichibi (traits d'encre) ; s : l'échelle (plus petit = traits plus larges). */
const sableI = (k, s = 1, base = SABLE) => (p, n) => { const [a, b] = plan(p, n); return sable(a * s + 40, b * s + 40, k + n[0] + 2 * n[1] + 3 * n[2], base); };
/** Le sable de Gaara : un grain fin, des marbrures, pas d'encre. */
const dune = (k, s = 1) => (p, n) => {
  const [a, b] = plan(p, n);
  const g = hache(Math.floor(a * 2 * s + 400), Math.floor(b * 2 * s + 400), 700 + k), m = bruit(a * s * 0.6 + 9, b * s * 0.6, 710 + k);
  return mix(mix(DUNE, m > 0 ? DUNE_C : DUNE_F, Math.abs(m) * 0.7), g > 0.9 ? DUNE_F : DUNE_C, g > 0.9 ? 0.5 : 0.25 * g);
};

// ------------------------------------------------------------------ la jarre (dans le repère du buste)
const J_BAS = [1, 15.6, -8.7], J_RB = 6.5, J_HAUT = [-1.6, 25.2, -8.1], J_RH = 4.8, J_COL = [-2.5, 29.4, -7.9], J_BOUT = [-2.7, 31.2, -7.9];
const J_MILIEU = [-0.3, 20.4, -8.4];
/** Le sceau sur la jarre (un cadre et un zigzag, comme sur la première image), autour du centre c, tourné vers dir. */
function sceau(p, c, r, ang) {
  const v = sous(p, c), th = Math.atan2(v[0], -v[2]) - ang, u = th * r, hh = v[1] - 0.6;
  if (Math.abs(u) > 3.9 || Math.abs(hh) > 1.9) return false;
  const cadre = Math.abs(u) > 3.3 || Math.abs(hh) > 1.35;
  if (cadre) return Math.abs(u) < 3.9 && Math.abs(hh) < 1.9 && (Math.abs(u) > 3.3 ? true : Math.abs(hh) > 1.35);
  const z = 0.75 * (Math.abs(((u + 2.6) / 0.85) % 2 - 1) * 2 - 1);
  return Math.abs(hh - z) < 0.36 && Math.abs(u) < 2.7;
}
function jarre(sc, T, o = {}) {
  const corps = fondu(1.6, boule(J_BAS, J_RB), boule(J_HAUT, J_RH), boudin(J_BAS, J_HAUT, 3.1), boudin(J_HAUT, J_COL, 2.2, 1.7), boudin(J_COL, J_BOUT, 1.75));
  // le goulot est creux quand elle est ouverte
  const f = o.ouverte ? (p) => Math.max(corps(p), -boudin(J_COL, ajoute(J_BOUT, [0, 1, 0]), 1.05)(p)) : corps;
  const casse = o.casse ?? 0;
  const brut = (p, n) => {
    if (sceau(p, J_BAS, J_RB, -0.25) || sceau(p, J_HAUT, J_RH, -0.55)) return SCEAU;
    if (o.ouverte && n[1] > 0.5 && lon(sous([p[0], 0, p[2]], [J_BOUT[0], 0, J_BOUT[2]])) < 1.3 && p[1] > 30) return [60, 44, 32];
    const g = hache(Math.floor(p[0] * 3 + 99), Math.floor(p[1] * 3 + p[2] * 3 + 99), 800);
    const m = bruit(p[0] * 0.5 + 20, p[1] * 0.5 + p[2] * 0.4, 810);
    // une fissure sur la boule du haut, comme sur la figurine
    const fis = Math.abs(p[0] + 3.8 + 0.6 * Math.sin(p[1] * 2.1)) < 0.28 && p[1] > 25.5 && p[1] < 28.6 && p[2] < -6;
    if (fis) return GOURDE_F;
    return mix(mix(GOURDE, m > 0 ? GOURDE_C : GOURDE_F, Math.abs(m) * 0.55), GOURDE_C, 0.2 * g);
  };
  // cassée : des fissures sombres, des morceaux qui manquent ; qui brille : dorée, sans ombre
  const coul = (p, n) => {
    if (casse && Math.abs(bosse(p, 1.1, 840)) < 0.04 + 0.07 * casse) return [40, 28, 18];
    const c = brut(p, n);
    return o.brille ? [...mix(c, OR, 0.55), 1] : c;
  };
  const trou = (p) => (casse ? casse * 7 * (0.5 + 0.5 * bosse(p, 1.4, 845)) - casse * 3.4 : 0);
  volume(sc, T, (p) => f(p) + 0.12 * bosse(p, 0.9, 830) + trou(p), [-8.5, 8, -16, 8.5, 33, -1.5], 0.4, coul);
  if (o.casse) return;
  // la sangle : un anneau au creux de la jarre, puis en travers du torse (de l'épaule droite à la hanche gauche)
  const anneau = (p) => {
    const ax = norme(sous(J_HAUT, J_BAS)), v = sous(p, J_MILIEU), y = dot(v, ax), r = lon(sous(v, fois(ax, y)));
    return Math.hypot(r - 3.9, y / 1.6) - 0.8;
  };
  const bande = union(anneau, boudin([-2.4, 21.4, -5.4], [-4.5, 24.5, -1.6], 0.8), boudin([-4.5, 24.5, -1.6], [-4.4, 24.3, 2.4], 0.8),
    boudin([-4.4, 24.3, 2.4], [4.4, 13.6, 2.4], 0.8), boudin([4.4, 13.6, 2.4], [4.5, 13.6, -2.3], 0.8), boudin([4.5, 13.6, -2.3], [2.6, 18.6, -5.6], 0.8));
  volume(sc, T, bande, [-6, 11.5, -13, 6, 26.5, 3.6], 0.4, (p) => mix(SANGLE, [160, 156, 180], 0.25 * hache(Math.floor(p[1] * 2), Math.floor(p[0] * 2), 820)), 0.03);
  if (!o.ouverte) volume(sc, T, boudin(ajoute(J_BOUT, [0, -0.4, 0]), ajoute(J_BOUT, [0.15, 1.4, 0]), 1.25, 1.05), [-5, 29, -11, 0, 34, -5], 0.3, () => BOUCHON, 0.02);
}

/** Le bouchon qui saute, ailleurs (dans le monde). */
function bouchon(sc, p, a) {
  const T = { p: (v) => ajoute(p, rotX(rotY(v, a), a * 0.7)), n: (v) => norme(rotX(rotY(v, a), a * 0.7)) };
  volume(sc, T, boudin([0, -1, 0], [0, 1, 0], 1.25, 1.05), [-2, -2.5, -2, 2, 2.5, 2], 0.3, () => BOUCHON);
}

// ------------------------------------------------------------------ le sable qui tourne
/**
 * Un ruban de sable en hélice autour de c : rayon r0, de y0 à y0 + haut, tours tours ; ep = [épaisseur, hauteur] du ruban.
 * Il est plus épais au milieu, il ondule, il a des bosses.
 */
function helice(c, { r0 = 13, y0 = 1, haut = 28, tours = 2, ep = [1.3, 2.6], k = 0, debut = 0, fin = 1, vague = 2.2 } = {}) {
  return (p) => {
    const dx = p[0] - c[0], dz = p[2] - c[2], r = Math.hypot(dx, dz);
    let th = Math.atan2(dz, dx); if (th < 0) th += TOUR;
    let m = 1e9;
    for (let n = 0; n < tours + 1; n++) {
      const t = (th + TOUR * n) / (TOUR * tours);
      if (t < debut || t > fin) continue;
      const g = Math.sin(Math.PI * (t - debut) / (fin - debut)) ** 0.6;
      const rc = r0 + vague * Math.sin(t * 9 + k), yc = c[1] + y0 + haut * t + 1.6 * Math.sin(t * 13 + k);
      const e0 = ep[0] * (0.45 + 0.75 * g), e1 = ep[1] * (0.35 + 0.8 * g);
      const d = (Math.hypot((r - rc) / e0, (p[1] - yc) / e1) - 1) * Math.min(e0, e1) + 0.35 * bosse(p, 2, 900 + k);
      if (d < m) m = d;
    }
    return m;
  };
}
/** Des grains qui volent autour (dans le monde). */
function poussiere(sc, c, n, r, h, k = 0, couleurs = [DUNE_C, DUNE, DUNE_F]) {
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, 950 + k) * TOUR, rr = r * (0.6 + 0.6 * hache(q, 2, 950 + k));
    cube(sc, [c[0] + Math.cos(a) * rr, c[1] + h * hache(q, 3, 950 + k), c[2] + Math.sin(a) * rr], 0.45 + 0.5 * hache(q, 4, 950 + k), [q, a], couleurs[q % couleurs.length]);
  }
}
/** Un filet de sable le long de points (un tube qui s'affine), dans le repère T. */
function filet(sc, T, pts, r0, r1, couleur, pas = 0.5) {
  const f = [];
  for (let k = 0; k + 1 < pts.length; k++) {
    const t0 = k / (pts.length - 1), t1 = (k + 1) / (pts.length - 1);
    f.push(boudin(pts[k], pts[k + 1], r0 + (r1 - r0) * t0, r0 + (r1 - r0) * t1));
  }
  const g = union(...f), bb = [1e9, 1e9, 1e9, -1e9, -1e9, -1e9], R = Math.max(r0, r1) + 1.5;
  for (const p of pts) for (let i = 0; i < 3; i++) { bb[i] = Math.min(bb[i], p[i] - R); bb[i + 3] = Math.max(bb[i + 3], p[i] + R); }
  volume(sc, T, (p) => g(p) + 0.3 * bosse(p, 1.5, 960), bb, pas, couleur);
}

// ------------------------------------------------------------------ les poings de sable (niveau 1)
/**
 * Un gros poing de sable au repère (o : le centre, f : vers l'avant, cote : +1 son poing gauche, -1 le droit), avec le
 * bras de sable qui le relie à l'épaule de sable s. use : 0 entier ; jusqu'à 1, il s'effrite.
 */
function poingSable(sc, o, f, cote, s, use = 0, k = 0, couleur = dune(k)) {
  const F = norme(f), lat = norme(croix([0, 1, 0], F)), up = croix(F, lat);
  const W = (v) => ajoute(o, ajoute(ajoute(fois(lat, v[0]), fois(up, v[1])), fois(F, v[2])));
  const E = 2.3;
  const parties = [
    boite(W([0, 0, 0]), [0, 0, 0], 0), // remplacé ci-dessous (repère tourné)
  ];
  const loc = (p) => { const v = sous(p, o); return [dot(v, lat) / E, dot(v, up) / E, dot(v, F) / E]; };
  const poing = fondu(0.6,
    boite([0, 0, 0], [3.8, 3.4, 3], 1.3),
    ...[-2.85, -0.95, 0.95, 2.85].map((x) => boule([x, 1.3, 2.9], 1.45)),
    boudin([-3.4, -0.9, 2.6], [3.4, -0.9, 2.6], 1.55),
    boudin([cote * 3.9, -1.2, -0.4], [cote * 2.2, -2.3, 2.3], 1.3),
    boudin([0, 0, -1.5], [0, -0.3, -6.5], 2.6, 2.3));
  void parties;
  const bras = (() => {
    const P = [s, ajoute(s, [cote * 6, 4, 6]), W([0, -0.5, -18]), W([0, 0, -8])];
    return bezier(P, 3, 5.2, 10).f;
  })();
  const ronge = (p) => (use > 0 ? use * 6 * (0.55 + 0.45 * bosse(p, 1.2, 970 + k)) - use * 2 : 0);
  const f2 = (p) => Math.min(poing(loc(p)) * E, bras(p)) + 0.3 * bosse(p, 1.4, 975 + k) + ronge(p);
  const bb = [Math.min(o[0], s[0]) - 14, Math.min(o[1], s[1]) - 12, Math.min(o[2], s[2]) - 14, Math.max(o[0], s[0]) + 14, Math.max(o[1], s[1]) + 14, Math.max(o[2], s[2]) + 14];
  volume(sc, ID, f2, bb, 0.6, couleur);
}

// ------------------------------------------------------------------ le mausolée (la statue de sable)
/**
 * La statue de la troisième image : une femme de sable, cheveux longs, yeux fermés, qui sort d'un tourbillon. o : ses
 * pieds (au sol) ; elle regarde vers -z. haut : de 0 (sous terre) à 1 (sortie) ; mains : 0 ouvertes de chaque côté,
 * 1 refermées l'une sur l'autre autour de la cible (à 24 devant elle).
 */
/** La statue, dans son repère (pieds en 0, elle regarde vers -z, taille 1 : 5,5 blocs). */
function statueLocale(haut, ferme) {
  const lev = (1 - haut) * 92;
  const P = (v) => [v[0], v[1] - lev, v[2]];
  const bout = (s) => P([s * (13 - 8 * ferme), 34, -26 + 2 * ferme]);
  const parts = [
    // le tourbillon du bas, d'où elle sort
    ellip(P([0, 5, 0]), [32, 10, 28]),
    boudin(P([0, 6, 0]), P([0, 28, 1]), 19, 13.5),
    // le buste : la poitrine, les épaules rondes, la taille
    ellip(P([0, 41, 1.5]), [19, 17, 11.5]),
    ellip(P([0, 30, 1]), [14, 10, 10]),
    boudin(P([0, 54, 2]), P([0, 63, 0]), 5.4, 4.8),
    // la tête un peu penchée, le nez, le menton
    ellip(P([0, 72.5, -1.5]), [9.4, 11.5, 9.6]),
    ellip(P([0, 71, -10.4]), [1.3, 2.4, 1.6]),
    ellip(P([0, 64.6, -6.5]), [4.6, 3, 4]),
    // les cheveux : la masse derrière, la raie, des mèches qui tombent sur les épaules et dans le dos
    ellip(P([0, 74, 3.2]), [12.4, 14.5, 10.4]),
    ellip(P([0, 82.4, -1]), [10.4, 3.4, 9.6]),
    boudin(P([0, 70, 7]), P([0, 40, 14]), 9, 6),
  ];
  for (const s of [-1, 1]) {
    parts.push(ellip(P([s * 16.5, 50, 1.5]), [8, 6.5, 7.5]));
    parts.push(bezier([P([s * 9.5, 76, 0]), P([s * 13, 62, -2]), P([s * 13, 54, -5]), P([s * 16, 40, -7])], 3.4, 2, 6).f);
    parts.push(bezier([P([s * 6, 80, -4]), P([s * 10.5, 70, -8.6]), P([s * 10, 60, -6]), P([s * 12, 48, -8])], 2.6, 1.6, 6).f);
    parts.push(boudin(P([s * 11, 70, 5]), P([s * 17, 44, 9]), 4, 2.4));
    // les bras : l'épaule, le coude dehors, la main devant
    const ep = P([s * 19, 50, 0]), co = P([s * 30, 34, -9]), ma = bout(s);
    parts.push(boudin(ep, co, 5.8, 4.8), boudin(co, ma, 4.8, 4));
    parts.push(ellip(ma, [3.6 + 2 * ferme, 11, 9]));
    // les doigts, longs et fins, qui se referment autour de lui
    for (let d = 0; d < 4; d++) {
      const y = 25 + d * 5.4, a = ajoute(ma, [0, y - 34, 4]);
      const b = ajoute(a, [-s * (6 + 5 * ferme), 0, 7 - 2 * ferme]);
      parts.push(boudin(a, b, 2, 1.4));
    }
  }
  const corps = fondu(3.2, ...parts);
  // des rubans de sable qui montent du tourbillon en spirale autour d'elle (comme sur la troisième image)
  const rubans = [];
  for (let r = 0; r < 4; r++) {
    const a0 = r * 1.57 + 0.4, s = r % 2 ? 1 : -1;
    const pt = (t) => { const a = a0 + s * t * 3.4, R = 30 - 12 * t + 6 * Math.sin(t * 5 + r); return P([Math.cos(a) * R, 4 + t * (36 + 8 * r), Math.sin(a) * R * 0.85]); };
    rubans.push(bezier([pt(0), pt(0.33), pt(0.66), pt(1)], 3.4, 0.7, 10).f);
  }
  return { f: (p) => Math.min(corps(p), ...rubans.map((g) => g(p))), lev };
}
/**
 * La statue de la troisième image : une femme de sable, cheveux longs, yeux fermés, qui sort d'un tourbillon, des rubans
 * de sable autour. o : ses pieds (au sol) ; elle regarde vers -z. haut : de 0 (sous terre) à 1 (sortie) ; mains : 0
 * ouvertes de chaque côté, 1 refermées l'une sur l'autre autour de la cible (à 26 × taille devant elle, à 34 de haut).
 */
function statue(sc, o, haut = 1, mains = 1, k = 0, taille = 1.6) {
  const { f: fl, lev } = statueLocale(haut, mains);
  const loc = (p) => [(p[0] - o[0]) / taille, (p[1] - o[1]) / taille, (p[2] - o[2]) / taille];
  const f = (p) => { const q = loc(p); return taille * (Math.max(fl(q) + 1 * bosse(q, 3, 990 + k), -(q[1] + 0.1))); };
  const sab = dune(50 + k, 0.8 / taille);
  const T = taille;
  volume(sc, ID, f, [o[0] - 44 * T, o[1] - 2, o[2] - 44 * T, o[0] + 44 * T, o[1] + (90 - lev) * T, o[2] + 36 * T], 0.7 * T, (p, n) => {
    // le visage : yeux fermés (deux arcs), les sourcils, un petit sourire, vers -z
    const [x, y] = loc(p), yy = y + lev, z = loc(p)[2];
    const enc = [146, 114, 76];
    if (n[2] < -0.5 && yy > 63 && yy < 82 && Math.abs(x) < 8 && z < -5) {
      for (const s of [-1, 1]) {
        if (Math.abs(yy - (74.6 - 0.11 * (x - s * 3.8) ** 2)) < 0.7 && Math.abs(x - s * 3.8) < 2.7) return enc;
        if (Math.abs(yy - (77.6 - 0.05 * (x - s * 3.6) ** 2)) < 0.5 && Math.abs(x - s * 3.6) < 2.6) return mix(enc, DUNE, 0.35);
      }
      if (Math.abs(yy - (67.6 + 0.08 * x * x)) < 0.6 && Math.abs(x) < 2.6) return enc;
    }
    return sab(p, n);
  }, 0.05);
}
/** Où tenir la cible pour la statue (ses pieds), selon la taille. */
const tenuStatue = (o, taille = 1.6) => [o[0], o[1] + 34 * taille - 17, o[2] - 26 * taille];

// ------------------------------------------------------------------ la bête (niveau 3)
/** Tout le corps est pris (niveau 3) : le sable recouvre tout. */
const toutPris = () => true;
/** La tête de bête par-dessus la tête du joueur : museau, babines, crocs, deux yeux d'Ichibi, deux oreilles. */
function teteBete(sc, Tt, rire = 0) {
  const f = fondu(1.4, boite([0, 28.3, 0], [4.7, 4.5, 4.6], 1.6), ellip([0, 26.4, 5.4], [3.6, 2.8 + rire, 3.2]), ellip([0, 31.6, -0.6], [4.8, 1.8, 4.2]));
  volume(sc, Tt, f, [-7, 21, -7, 7, 35, 10], 0.32, (p, n) => {
    const [x, y, z] = p;
    if (n[2] > 0.5 || (n[1] < 0.6 && z > 4.6)) {
      // la gueule : une grande fente qui remonte sur les côtés, des crocs pointus
      const yc = 25.9 - rire + 0.09 * x * x;
      if (Math.abs(x) < 3.6 && Math.abs(y - yc) < 0.55 + rire * 0.8) {
        const dent = Math.abs(((x + 4) / 0.9) % 2 - 1);
        return y - yc > 0.1 - dent * 0.5 || y - yc < -0.4 + dent * 0.4 - rire ? DENT : BOUCHE;
      }
      for (const s of [-1, 1]) {
        const dx = x - s * 2.3, dy = y - 29.2;
        const u = -s * 0.97 * dx + 0.24 * dy, w = s * 0.24 * dx + 0.97 * dy;
        const r = Math.hypot(dx, dy);
        if (r < 0.62) return r < 0.22 ? PUPILLE : r > 0.48 ? VERT_F : mix(VERT_C, VERT, r / 0.48);
        if (Math.abs(u) < 1.7 && Math.abs(w) < 0.85 && Math.abs(u) + 0.5 * Math.abs(w) < 2) return NOIR;
        if (Math.abs(w - 1.3) < 0.2 && u > -1.3 && u < 1.2) return ENCRE;
      }
    }
    return sable(x * 1.5 + z + 40, y * 1.5 + 40, 1000 + n[0] + 2 * n[1] + 3 * n[2]);
  });
  for (const s of [-1, 1]) {
    const d = norme([s * 0.42, 1, -0.15]), base = [s * 3, 31.6, -0.6];
    for (let k = 0; k < 4; k++) {
      const a = Tt.p(ajoute(base, fois(d, k * 1.7))), b = Tt.p(ajoute(base, fois(d, k * 1.7 + 1.9)));
      const sab = motif(130 + k + (s > 0 ? 10 : 0));
      membre(sc, a, b, 4.4 - k, 1.6 - k * 0.15, Tt.n([0, 0, 1]), (f, u, v, i, j) => (k === 3 || (f === "devant" && Math.abs(u - 0.5) < 0.17 && k > 0) ? ENCRE : sab(f, u, v, i, j)), 0.1);
    }
  }
}
/** La grosse queue du niveau 3 : du bas du dos, elle passe au-dessus de lui en crosse. */
function grosseQueue(sc, T, k = 0) {
  const P = [[0, 12, -4], [-4, 2, -26], [-6, 52, -40], [-2, 54, -8]];
  const B = (t) => [0, 1, 2].map((i) => (1 - t) ** 3 * P[0][i] + 3 * (1 - t) ** 2 * t * P[1][i] + 3 * (1 - t) * t * t * P[2][i] + t ** 3 * P[3][i]);
  const n = 16;
  for (let q = 0; q < n; q++) {
    const w = 12.5 - 7 * (q / (n - 1));
    membre(sc, T.p(B(q / n)), T.p(B((q + 1) / n)), w, w, T.n([1, 0, 0]), motif(200 + q + k), 0.9);
  }
  const bout = T.p(B(1));
  pointe(sc, bout, norme(sous(bout, T.p(B(0.95)))), 6, 5.6, (t) => mix(SABLE, ENCRE, t * 0.9), false, false);
}
/**
 * Un grand bras du niveau 3 (dans le monde) : de l'épaule S, il monte en arc (le coude au-dessus, comme sur la quatrième
 * image) puis redescend à la main H, grosse, quatre doigts écartés, des griffes. dir : où pointent les doigts.
 */
function bras3(sc, S, H, s, dir = null) {
  const D = norme(dir ?? [s * 0.25, -0.35, 1]);
  const P = [S, ajoute(S, [s * 7, 12, -1]), ajoute(H, ajoute(fois(D, -12), [s * 4, 13, 0])), H];
  const b = bezier(P, 3.3, 4.8, 14);
  const lat = norme(croix([0, 1, 0], D)), up = croix(D, lat);
  const W = (v) => ajoute(H, ajoute(ajoute(fois(lat, v[0]), fois(up, v[1])), fois(D, v[2])));
  const doigts = [], bouts = [];
  for (let d = 0; d < 4; d++) {
    const a = W([(d - 1.5) * 2.3, 0, 3]), e = (d - 1.5) * 0.32;
    const t = ajoute(a, fois(norme(ajoute(ajoute(D, fois(lat, e)), fois(up, -0.35))), 5.5));
    doigts.push(boudin(a, t, 1.45, 1.1));
    bouts.push([t, norme(ajoute(ajoute(D, fois(lat, e)), fois(up, -0.7)))]);
  }
  const pouce = W([-s * 4.2, 0.5, 0.5]), pt = ajoute(pouce, fois(norme(ajoute(fois(lat, -s), D)), 4));
  doigts.push(boudin(pouce, pt, 1.4, 1.1));
  bouts.push([pt, norme(ajoute(fois(lat, -s), D))]);
  const f0 = fondu(1.6, b.f, ellip(W([0, 0, 0.5]), [5, 2.8, 5.2]), ...doigts);
  const f = (p) => f0(p) + 0.3 * bosse(p, 1.3, 1350 + s);
  const bb = [Math.min(S[0], H[0]) - 16, Math.min(S[1], H[1]) - 8, Math.min(S[2], H[2]) - 16, Math.max(S[0], H[0]) + 16, Math.max(S[1], H[1]) + 22, Math.max(S[2], H[2]) + 16];
  volume(sc, ID, f, bb, 0.5, sableI(320 + s));
  for (const [t, d] of bouts) pointe(sc, t, d, 3.6, 1.7, griffe, false, false);
  // des piques sur le dessus du bras
  for (let q = 1; q < 4; q++) pointe(sc, b.B(q * 0.18), [s * 0.6, 1, -0.4], 4.2 - q * 0.6, 2, (t) => mix(SABLE, SABLE_F, t * 0.8), false, false);
}
/** Les jambes ne sont prises qu'en haut : on voit encore ses jambes (bas du pantalon, chaussures) sous le sable. */
const prisBete = (nom, t) => (nom.startsWith("jambe") ? t.centre[1] > 6 + 2.6 * bruit(t.centre[0] * 1.3 + t.centre[2], t.centre[1], 1370) : true);
/** Le joueur au niveau 3 : penché en avant, en sable sauf le bas des jambes, deux grands bras, la tête de bête, la queue. Plus de jarre (o.jarre pour la garder). */
function bete(sc, o = {}) {
  // o.morceau : "corps" (sans jambes ni bras), "jambeD", "jambeG" ou "bras" seulement (pour l'animation 3D)
  const m = o.morceau, veut = (q) => !m || m === q;
  const CACHE = { corps: ["brasG", "jambeD", "jambeG"], jambeD: ["tete", "corps", "brasG", "jambeG"], jambeG: ["tete", "corps", "brasG", "jambeD"], bras: ["tete", "corps", "brasG", "jambeD", "jambeG"] };
  const T = joueur(sc, PEAU, {
    etape: 3, pris: prisBete, extras: false, cache: m ? CACHE[m] : ["brasG"], buste: o.buste ?? 0.62, penche: o.penche ?? 0.08, lacet: o.lacet ?? 0,
    decale: o.decale ?? [0, -1.5, 0], jambeD: o.jambeD ?? 0.55, jambeG: o.jambeG ?? -0.25, tete: o.tete ?? [-0.55, 0, 0],
  });
  const dec = o.decale ?? [0, -1.5, 0];
  // plus de masse : le torse et les cuisses gonflés de sable
  if (veut("corps")) volume(sc, T.corps, fondu(2, ellip([0, 18.5, 0.6], [6.6, 7.6, 4.8]), ellip([-5.5, 22, 0], [4.2, 3.6, 4.2]), ellip([5.5, 22, 0], [4.2, 3.6, 4.2])),
    [-12, 9, -7, 12, 28, 7], 0.45, sableI(300));
  for (const [nom, s] of [["jambeD", -1], ["jambeG", 1]]) if (veut(nom)) volume(sc, T[nom], (q) => Math.max(ellip([s * 2, 9.5, 0], [3.4, 4.2, 3.6])(q) + 0.5 * bosse(q, 1, 1375 + s), 6.4 - q[1] + 1.4 * bruit(q[0] * 2 + q[2] * 2, 3, 1376)), [-7, 0, -6, 7, 15, 6], 0.45, sableI(310 + s));
  if (veut("bras")) for (const s of [-1, 1]) {
    const H = ajoute(o.mains?.[s < 0 ? 0 : 1] ?? [s * 19, 3, 17], dec);
    bras3(sc, T.corps.p([s * 6.4, 21, 0]), H, s, s < 0 ? o.devant : (o.devantG ?? null));
  }
  if (!veut("corps")) return T;
  teteBete(sc, T.tete, o.rire ?? 0);
  grosseQueue(sc, T.corps);
  if (o.jarre) jarre(sc, T.corps, { ouverte: true, brille: o.jarreBrille });
  for (const s of [-1, 1]) for (const [p, L] of [[[s * 2.6, 23, -2], 4.2], [[s * 1.4, 19.5, -2], 3.4], [[s * 3, 16.5, -2], 3]]) {
    pointe(sc, T.corps.p(p), T.corps.n([s * 0.5, 0.5, -1]), L, 1.9, (t) => mix(SABLE, SABLE_F, t * 0.8), false, false);
  }
  return T;
}

// ------------------------------------------------------------------ Shukaku entier (l'ulti)
/** Shukaku géant (28 blocs), en sable, au point o, tourné vers +z. bouche : la gueule ouverte ; gonfle : le ventre. */
function shukaku(sc, o = [0, 0, 0], { bouche = 0, gonfle = 0, brasD = 0 } = {}) {
  const P = (v) => ajoute(o, v);
  const parts = [
    ellip(P([0, 150, 10]), [118 + 10 * gonfle, 110 + 6 * gonfle, 105 + 12 * gonfle]),
    ellip(P([0, 240, 14]), [96, 82, 84]),
    ellip(P([0, 330, 30]), [80, 66, 70]),
    ellip(P([0, 300 - bouche * 4, 86]), [52, 30 + bouche * 6, 36]),
  ];
  for (const s of [-1, 1]) {
    parts.push(ellip(P([s * 72, 72, 0]), [52, 64, 54]), ellip(P([s * 80, 15, 32]), [42, 16, 54]));
    parts.push(boudin(P([s * 46, 366, 22]), P([s * 80, 446, 4]), 24, 3));
    const leve = s < 0 ? brasD : 0;
    const co = P([s * 145, 200 + 90 * leve, 60 + 30 * leve]), ma = P([s * 122, 150 + 200 * leve, 132 + 10 * leve]);
    parts.push(boudin(P([s * 90, 262, 20]), co, 31, 26), boudin(co, ma, 26, 22), ellip(ma, [25, 21, 27]));
  }
  const q = bezier([P([0, 120, -80]), P([0, 40, -270]), P([0, 340, -320]), P([0, 440, -190])], 62, 18, 22);
  const f0 = fondu(14, ...parts);
  const f = (p) => Math.min(f0(p), q.f(p)) + 2.4 * bosse(p, 12, 1100);
  volume(sc, ID, f, [o[0] - 185, o[1], o[2] - 350, o[0] + 185, o[1] + 475, o[2] + 190], 3.2, (p, n) => {
    const x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
    if (n[2] > 0.5 && z > 70 && y > 270 && y < 330) {
      // la gueule : très large, des crocs en scie en haut et en bas
      const yc = 299 - bouche * 4 + 0.006 * x * x, ep = 4 + bouche * 9;
      if (Math.abs(x) < 46 && Math.abs(y - yc) < ep) {
        const dent = Math.abs(((x + 60) / 5) % 2 - 1);
        return y - yc > ep - 4 * dent || y - yc < -ep + 4 * dent ? DENT : BOUCHE;
      }
    }
    if (n[2] > 0.3 && y > 320 && y < 368) {
      for (const s of [-1, 1]) {
        const dx = x - s * 34, dy = y - 342;
        if (Math.abs(dx) / 19 + Math.abs(dy) / 13 < 1 || (Math.abs(dx + s * 6) < 16 && Math.abs(dy) < 6)) {
          // un œil noir en losange, l'iris jaune en étoile à quatre branches, la pupille
          const r = Math.sqrt(Math.abs(dx)) + Math.sqrt(Math.abs(dy));
          if (Math.hypot(dx, dy) < 1.8) return [20, 14, 10, 1];
          if (r < 3.3) return [...JAUNE, 1];
          return NOIR;
        }
      }
    }
    const [a, b] = plan([x, y, z], n);
    return sable(a / 7 + 40, b / 7 + 40, 1200 + n[0] + 2 * n[1] + 3 * n[2]);
  });
  // les griffes des mains et des pieds
  for (const s of [-1, 1]) {
    const leve = s < 0 ? brasD : 0;
    const ma = P([s * 122, 150 + 200 * leve, 132 + 10 * leve]);
    for (let d = 0; d < 4; d++) pointe(sc, ajoute(ma, [s * (-14 + d * 9), -8 + 16 * leve, 22]), [s * 0.1, -0.5 + 1.2 * leve, 1], 18, 7, griffe, false, false);
    for (let d = 0; d < 3; d++) pointe(sc, P([s * (64 + d * 15), 10, 82]), [0, -0.1, 1], 14, 7, griffe, false, false);
  }
}

// ------------------------------------------------------------------ les vignettes
const ENVIRON = { lacet: 32, echelle: 8.6, pied: 470, centre: [-2, 0, -4] };
const BRAS_POSE = [0.08, 0.06];

// --- la jarre
function jarreDos(sc) {
  const T = perso(sc, PEAU, { tete: [0.05, 0, -0.1], brasG: BRAS_POSE, brasD: [0.05, -0.06] });
  jarre(sc, T.corps);
  legende(sc, "UNE GROSSE JARRE, LA SANGLE, LE SCEAU");
}
function jarreFace(sc) {
  const T = perso(sc, PEAU, { tete: [0.05, 0, 0.12], brasG: BRAS_POSE, brasD: [0.05, -0.06] });
  jarre(sc, T.corps);
  legende(sc, "DE FACE : LA SANGLE EN TRAVERS DU TORSE");
}
function jarreSaute(sc) {
  const T = perso(sc, PEAU, { tete: [-0.15, 0, 0.3], brasG: [0.15, 0.1], brasD: [0.1, -0.1] });
  jarre(sc, T.corps, { ouverte: true });
  const col = T.corps.p(J_BOUT);
  bouchon(sc, ajoute(col, [-7, 9, -3]), 0.9);
  for (let q = 0; q < 6; q++) cube(sc, ajoute(col, [-1 - q * 1.1, 1.4 + q * 1.5, -0.4 * q]), 0.4, [q, q], [180, 170, 160]);
  filet(sc, ID, [col, ajoute(col, [0.5, 3.5, 0]), ajoute(col, [2, 6, 1]), ajoute(col, [3.5, 7, 3])], 1.1, 0.5, dune(3));
  poussiere(sc, ajoute(col, [0, 3, 0]), 14, 4, 5, 3);
  legende(sc, "EVEIL : LE BOUCHON SAUTE (PAS DE RIRE)");
}
function jarreTourne(sc) {
  const T = perso(sc, PEAU, { tete: [-0.1, 0, 0.2], brasG: [0.1, 0.5], brasD: [0.1, -0.5] });
  jarre(sc, T.corps, { ouverte: true });
  const col = T.corps.p(J_BOUT);
  filet(sc, ID, [col, ajoute(col, [0, 4, 0]), ajoute(col, [5, 6, 3]), ajoute(col, [11, 3, 4]), ajoute(col, [14, -4, 2])], 1.2, 1.6, dune(4));
  volume(sc, ID, helice([0, 0, 0], { r0: 13, haut: 26, tours: 1.6, k: 1, debut: 0.05, ep: [1.1, 2.2] }), [-20, -2, -20, 20, 34, 20], 0.6, dune(5));
  poussiere(sc, [0, 0, 0], 30, 14, 30, 4);
  legende(sc, "LE SABLE SORT ET TOURNE AUTOUR DE LUI");
}

// --- niveau 1 : le sable tourne toujours autour de lui
/** Le sable normal qui tourne autour de lui (au niveau 1, tout le temps). */
function tourne(sc, c = [0, 0, 0], k = 0, o = {}) {
  volume(sc, ID, helice(c, { r0: o.r0 ?? 15, haut: o.haut ?? 22, tours: o.tours ?? 1.4, k: 2 + k, ep: o.ep ?? [1.1, 2.6], debut: o.debut ?? 0, fin: o.fin ?? 1 }),
    [c[0] - 24, c[1] - 2, c[2] - 24, c[0] + 24, c[1] + 40, c[2] + 24], 0.6, dune(7 + k));
  poussiere(sc, c, 26, (o.r0 ?? 15) + 1, (o.haut ?? 22) + 8, 5 + k);
}
function niveau1(sc) {
  const T = perso(sc, PEAU, { tete: [0.05, 0, 0.12], brasG: [0.1, 0.3], brasD: [0.1, -0.3] });
  jarre(sc, T.corps, { ouverte: true });
  const col = T.corps.p(J_BOUT);
  filet(sc, ID, [col, ajoute(col, [0, 5, 0]), ajoute(col, [6, 7, 4]), ajoute(col, [13, 3, 6])], 1.2, 1.6, dune(6));
  tourne(sc);
  legende(sc, "NIVEAU 1 : LE SABLE TOURNE TOUT LE TEMPS");
}
/** Un coup de poing de sable : cote -1 à sa droite, +1 à sa gauche. Le bras de sable sort du goulot de la jarre. */
function coupSable(cote) {
  return (sc) => {
    sol(sc, -36, -20, 36, 110);
    const T = perso(sc, PEAU, {
      brasD: cote < 0 ? [-1.45, 0.1] : [0.35, -0.2], brasG: cote > 0 ? [-1.45, -0.1] : [0.35, 0.2], buste: 0.1, tete: [0.05],
      jambeD: cote < 0 ? -0.3 : 0.25, jambeG: cote < 0 ? 0.25 : -0.3, lacet: -cote * 0.15,
    });
    jarre(sc, T.corps, { ouverte: true });
    tourne(sc, [0, 0, 0], 3, { r0: 13, haut: 20 });
    const o = [cote * 9, 22, 66];
    poingSable(sc, o, [0, 0, 1], cote, T.corps.p(J_BOUT), 0, 10 + cote);
    perso(sc, STEVE, { decale: [cote * 8, 3, 84], lacet: Math.PI, penche: -0.5, brasD: [-2.2, -0.7], brasG: [-2.3, 0.7], jambeD: 0.5, jambeG: -0.3, teinte: TOUCHE });
    poussiere(sc, [cote * 9, 16, 80], 22, 8, 14, 20 + cote);
    legende(sc, cote < 0 ? "CLIC GAUCHE : LE POING SORT DE LA JARRE" : "CLIC SUIVANT : A GAUCHE. 7 DEGATS, 7 BLOCS");
  };
}
function poingsTombent(sc) {
  sol(sc, -36, -20, 36, 70);
  const T = perso(sc, PEAU, { brasD: [0.1, -0.3], brasG: [0.1, 0.3], tete: [0.05] });
  jarre(sc, T.corps, { ouverte: true });
  tourne(sc, [0, 0, 0], 4, { r0: 13, haut: 20 });
  for (const c of [-1, 1]) {
    poingSable(sc, [c * 12, 20, 34], [c * 0.2, -0.1, 1], c, T.corps.p(J_BOUT), 0.55, 40 + c);
    for (let q = 0; q < 26; q++) {
      const t = hache(q, 1, 1300 + c);
      cube(sc, [c * (12 + 6 * (hache(q, 2, 1300 + c) - 0.5)), 20 - 20 * t, 34 - 18 * t * hache(q, 3, 1300 + c)], 0.5 + 0.6 * hache(q, 4, 1300 + c), [q, t], q % 3 ? DUNE : DUNE_F);
    }
  }
  legende(sc, "IL ARRETE : LES POINGS RETOMBENT EN SABLE");
}

// --- le cercueil
const CIBLE = [0, 0, 70];
/** Le cocon plein autour de lui (c : ses pieds) : jusque, la hauteur du sable (24,5 : jusqu'au cou, seule la tête dépasse). */
function cocon(sc, c, jusque, serre = 0, k = 0, couleur = dune(30 + k)) {
  const corps = ellip(ajoute(c, [0, 13.5, 0]), [11.2 - 1.2 * serre, 16.8 - serre, 8.6 - serre]);
  const f = (p) => Math.max(corps(p) + 0.8 * bosse(p, 1.8, 980 + k), p[1] - (c[1] + jusque + 1.4 * bruit(p[0] + 50, p[2] + 50, 985 + k)));
  volume(sc, ID, f, [c[0] - 12, c[1] - 6, c[2] - 10, c[0] + 12, c[1] + 36, c[2] + 10], 0.55, couleur, 0.05);
}
function cercueil(etape, fort = false) {
  return (sc) => {
    sol(sc, -36, -20, 36, 100, auSolDune(CIBLE[0], CIBLE[2], 14));
    const leve = etape === 0 ? 0 : 26;
    const T = fort
      ? joueur(sc, PEAU, { brasG: [-1.3, -0.2], tete: [0.05], grains: 6 })
      : perso(sc, PEAU, { brasD: [-1.5, 0.12], brasG: [0.15, 0.2], tete: [etape ? -0.25 : 0.05], buste: etape === 2 ? 0.08 : 0 });
    jarre(sc, T.corps, { ouverte: true });
    if (!fort) tourne(sc, [0, 0, 0], 6 + etape, { r0: 13, haut: 20 });
    const c = ajoute(CIBLE, [0, leve, 0]);
    perso(sc, STEVE, { decale: c, lacet: Math.PI, brasD: etape ? [0, -0.05] : [-0.2, -0.3], brasG: etape ? [0, 0.05] : [-0.2, 0.3], tete: [-0.2], teinte: etape === 2 ? TOUCHE : null });
    const coul = fort ? sableI(31, 1) : dune(31);
    cocon(sc, c, etape === 0 ? 11 : 24.4, etape === 2 ? 1 : 0, etape, coul);
    if (etape >= 1) {
      // la colonne de sable qui le porte
      volume(sc, ID, (p) => boudin([c[0], 0, c[2]], [c[0], leve + 4, c[2]], 6.5, 4.5)(p) + 0.6 * bosse(p, 1.5, 1310), [c[0] - 9, -1, c[2] - 9, c[0] + 9, leve + 8, c[2] + 9], 0.7, fort ? sableI(32) : dune(32));
    }
    // le sable part de la jarre, file au-dessus du sol jusqu'à lui
    const col = T.corps.p(J_BOUT);
    if (etape === 0) filet(sc, ID, [col, ajoute(col, [-4, 8, 8]), [-8, 14, 30], [-6, 6, 52], [-2, 3, 62]], 1.2, 2.6, dune(33), 0.6);
    if (etape === 2) {
      for (let q = 0; q < 26; q++) {
        const a = hache(q, 1, 1320) * TOUR, b = (hache(q, 2, 1320) - 0.3) * 1.6;
        const d = [Math.cos(a) * Math.cos(b), Math.sin(b), Math.sin(a) * Math.cos(b)];
        const base = ajoute(ajoute(c, [0, 13 + 7 * Math.sin(b), 0]), [d[0] * 7.5, d[1] * 6, d[2] * 6]);
        pointe(sc, base, d, 8 + 6 * hache(q, 3, 1320), 3, (t) => mix(fort ? SABLE : DUNE, fort ? SABLE_F : DUNE_F, t), false, false);
      }
      eclat(sc, ajoute(c, [0, 16, 0]), 22, 14, 7);
    }
    const txt = fort ? "ACCROUPI + CLIC DROIT : 15 BLOCS, 16 DEGATS"
      : ["CLIC DROIT : LE SABLE PART DE LA JARRE", "IL LE REMPLIT JUSQU'AU COU, LE SOULEVE", "IL SERRE LE POING : 12 DEGATS D'UN COUP"][etape];
    legende(sc, txt);
  };
}
const auSolDune = (cx, cz, r) => (x, z) => (Math.hypot(x - cx, z - cz) < r + 3 * bruit(x + 80, z + 80, 1330) ? dune(60)([x, 0, z], [0, 1, 0]) : null);

function nuage1(sc) {
  sol(sc, -40, -26, 40, 40, auSolDune(0, 0, 12));
  const h = 15;
  nuage(sc, [0, h, 0], 15, 11, 1);
  const T = perso(sc, PEAU, { decale: [0, h + 2.8, 0], brasG: [-0.2, 0.3], brasD: [0.1, -0.2], tete: [-0.2] });
  jarre(sc, T.corps, { ouverte: true });
  for (let q = 0; q < 24; q++) {
    const a = q * 0.7, r = 11 - q * 0.2;
    cube(sc, [Math.cos(a) * r, (q / 24) * h, Math.sin(a) * r * 0.75], 0.8, [q, a], q % 3 ? DUNE : DUNE_F);
  }
  legende(sc, "ACCROUPI : UN PLUS PETIT NUAGE (20 S)");
}

// --- niveau 2
function niveau2(sc) {
  const T = joueur(sc, PEAU, { tete: [0.05, 0, 0.12], brasG: BRAS_POSE, extras: false });
  brasGeant(sc, T, { E: [-7.6, 21.6, 0], C: [-9.6, 14.4, 1.4], P: [-9.4, 8.2, 4], f: [0.05, -0.8, 0.6], n: [1, 0, 0.1], main: "griffe" });
  oreille(sc, T.tete);
  queue(sc, T.corps);
  jarre(sc, T.corps, { ouverte: true });
  tourne(sc, [0, 0, 0], 8, { r0: 19, haut: 14, tours: 1.2, ep: [0.8, 1.8], fin: 0.8 });
  grains(sc, T.corps, 14, 2);
  legende(sc, "NIVEAU 2 : LA MOITIE D'ICHIBI + LA JARRE");
}
/** L'éveil 2 : le sable coule du goulot sur l'épaule, puis descend petit à petit sur la moitié du corps. */
function eveil2(sc) {
  const cols = [];
  const T0 = perso(sc, PEAU, { decale: [-30, 0, 0], brasG: BRAS_POSE, brasD: [0.05, -0.06], tete: [-0.2] });
  jarre(sc, T0.corps, { ouverte: true });
  cols.push([T0, 0]);
  const T1 = joueur(sc, PEAU, { etape: 1, decale: [0, 0, 0], brasG: BRAS_POSE, tete: [-0.3] });
  jarre(sc, T1.corps, { ouverte: true });
  cols.push([T1, 1]);
  const T = joueur(sc, PEAU, { decale: [32, 0, 0], brasG: BRAS_POSE, tete: [-0.35], grains: 6, extras: false });
  brasGeant(sc, T, { E: [-7.6, 21.6, 0], C: [-11, 17, 3], P: [-12, 12, 8], f: [-0.3, -0.5, 0.8], n: [1, 0, 0.1], main: "griffe" });
  oreille(sc, T.tete);
  queue(sc, T.corps);
  jarre(sc, T.corps, { ouverte: true });
  // le filet de sable : du goulot, par-dessus l'épaule droite, puis le long du bras
  for (const [Tk, e] of cols) {
    const pts = [J_BOUT, [-3.6, 32.4, -5], [-6.2, 25.6, -0.6], [-6.6, 20, 0.8], [-6.4, 14 - 2 * e, 1.2]].map(Tk.corps.p);
    filet(sc, ID, e ? pts : pts.slice(0, 4), 1.5, 1.9, sableI(70 + e), 0.4);
  }
  filet(sc, ID, [J_BOUT, [-3.6, 32.6, -5], [-5, 29, -3]].map(T.corps.p), 1, 1.2, sableI(73), 0.4);
  for (const [x, t] of [[-30, "0 S"], [0, "1 S"], [32, "2 S"]]) {
    const [sx, sy] = sc.vue([x, 42, 0]);
    ecrireTexte(sc.img, sc.w, t, Math.round(sx) - 16, Math.max(4, Math.round(sy) - 20), 3, [40, 40, 50, 255]);
  }
  const [hx, hy] = sc.vue([30, 48, 0]);
  ecrireTexte(sc.img, sc.w, "HA HA HA", Math.round(hx) - 40, Math.round(hy) - 34, 3, [160, 112, 40, 255]);
  legende(sc, "LE SABLE COULE DE LA JARRE SUR LUI");
}
function prise2(sc) {
  sol(sc, -34, -16, 26, 46, (x, z) => (Math.hypot((x - PROIE[0]) / 1.6, z - PROIE[2]) < 3.4 ? clair(HERBE, 0.62) : null));
  const yv = 31;
  const T = joueur(sc, PEAU, { bras: tenir(yv), brasG: [0.2, 0.2], jambeD: -0.2, jambeG: 0.2 });
  jarre(sc, T.corps, { ouverte: true });
  proie(sc, [0, 0, 0], yv, false);
  prise(sc, T, yv, false);
  legende(sc, "CLIC DROIT : LA GRANDE MAIN, COMME AVANT");
}
/** Un gros rocher de sable d'Ichibi (rayon r). */
function rocher(sc, c, r, k = 0) {
  volume(sc, ID, (p) => boule(c, r)(p) + r * 0.18 * bosse(p, r * 0.35, 1400 + k), [c[0] - r * 1.3, c[1] - r * 1.3, c[2] - r * 1.3, c[0] + r * 1.3, c[1] + r * 1.3, c[2] + r * 1.3], Math.max(0.6, r / 14), sableI(80 + k, 9 / Math.max(9, r)));
}
/** Accroupi + clic gauche (niveau 2) : le bras d'Ichibi arrache un rocher de sable et le lance. */
function lancer2(sc) {
  sol(sc, -40, -20, 40, 150, auSolDune(-6, 6, 9));
  const T = joueur(sc, PEAU, { bras: { E: [-7.6, 21.6, 0], C: [-11, 28, 3], P: [-9, 34, 10], f: [0, 0.6, 0.8], n: [0.3, -0.8, 0.5], main: "ouverte" }, brasG: [0.5, 0.2], jambeD: -0.45, jambeG: 0.4, buste: 0.2 });
  jarre(sc, T.corps, { ouverte: true });
  const A = T.corps.p([-8, 40, 18]), B = [4, 14, 122];
  for (let q = 1; q < 16; q++) {
    const t = q / 16, p = ajoute(ajoute(A, fois(sous(B, A), t)), [0, 22 * 4 * t * (1 - t), 0]);
    if (t < 0.55) cube(sc, p, 1.4 - 0.5 * t, [q, t], q % 2 ? SABLE_C : SABLE_F);
  }
  rocher(sc, [2, 34, 84], 9, 1);
  for (const [x, z, a] of [[-6, 128, 0.6], [12, 134, 0.9]]) perso(sc, STEVE, { decale: [x, 0, z], lacet: Math.PI, penche: -a * 0.4, brasD: [-2.4, -0.6], brasG: [-2.4, 0.6], teinte: TOUCHE });
  legende(sc, "ACCROUPI + CLIC GAUCHE : UN ROCHER DE SABLE");
}

// --- le mausolée
const MAUS = [0, 0, 104];
function mausolee(etape, taille = 1.6, num = "") {
  return (sc) => {
    sol(sc, -60, -16, 60, 160, auSolDune(MAUS[0], MAUS[2] - 6, 44));
    const T = joueur(sc, PEAU, { brasG: [-1.6, -0.3], tete: [-0.1], grains: 4 });
    jarre(sc, T.corps, { ouverte: true });
    const tenu = tenuStatue(MAUS, taille), sol0 = [tenu[0], 0, tenu[2]];
    const cible = etape ? tenu : sol0;
    perso(sc, STEVE, { decale: cible, lacet: Math.PI, brasD: etape ? [-2.6, -0.4] : [-0.3, -0.3], brasG: etape ? [-2.6, 0.4] : [-0.3, 0.3], tete: [etape ? -0.4 : 0.1], jambeD: etape ? 0.3 : 0, jambeG: etape ? -0.2 : 0, teinte: etape === 2 ? TOUCHE : null });
    statue(sc, MAUS, etape === 0 ? 0.55 : 1, etape === 0 ? 0 : etape === 1 ? 0.55 : 1, etape, taille);
    if (etape === 2) {
      for (let q = 0; q < 10; q++) cube(sc, ajoute(cible, [(hache(q, 1, 1400) - 0.5) * 6, 34 + q * 2.4, (hache(q, 2, 1400) - 0.5) * 4]), 0.8 - q * 0.04, [q, q], [236, 240, 246]);
    }
    poussiere(sc, ajoute(MAUS, [0, 0, -6]), 50, 40 * taille / 1.6, etape === 0 ? 60 : 40, 30 + etape);
    legende(sc, ["TECHNIQUE : LA STATUE SORT DU SOL" + num, "ELLE LE PREND DANS SES MAINS", "IL ETOUFFE : 2 / S + 6 A LA FIN"][etape]);
  };
}

// --- niveau 3
/** L'éveil 3 : la jarre se fissure et s'effondre, tout son sable tombe et le recouvre. */
function eveil3(sc) {
  sol(sc, -40, -30, 40, 40, auSolDune(0, -6, 18));
  const T = joueur(sc, PEAU, { tete: [-0.5, 0, 0.1], brasG: [-0.4, 0.5], grains: 8, extras: false, pris: (nom, t) => (nom.startsWith("jambe") ? t.centre[1] > 7 : true) });
  brasGeant(sc, T, { E: [-7.6, 21.6, 0], C: [-12, 26, 2], P: [-15, 33, 6], f: [-0.3, 0.8, 0.3], n: [1, 0, 0.1], main: "griffe" });
  oreille(sc, T.tete);
  queue(sc, T.corps);
  jarre(sc, T.corps, { casse: 0.75 });
  // des morceaux de jarre et des paquets de sable qui tombent
  for (let q = 0; q < 14; q++) {
    const p = T.corps.p([(hache(q, 1, 1500) - 0.5) * 16, 6 + 22 * hache(q, 2, 1500), -9 - 6 * hache(q, 3, 1500)]);
    cube(sc, p, 1 + 1.2 * hache(q, 4, 1500), [q, q * 0.6], q % 3 ? GOURDE : GOURDE_F);
  }
  for (let q = 0; q < 50; q++) {
    const p = T.corps.p([(hache(q, 1, 1510) - 0.5) * 14, 2 + 26 * hache(q, 2, 1510), -6 - 8 * hache(q, 3, 1510)]);
    cube(sc, p, 0.6 + 0.6 * hache(q, 4, 1510), [q, q], q % 3 ? SABLE : SABLE_F);
  }
  volume(sc, ID, (p) => ellip([0, 0, -12], [12, 4, 8])(p) + 1 * bosse(p, 2, 1520), [-14, -1, -22, 14, 5, -2], 0.6, sableI(90));
  const [hx, hy] = sc.vue([0, 46, 0]);
  ecrireTexte(sc.img, sc.w, "HA HA HA HA", Math.round(hx) - 64, Math.round(hy) - 10, 3, [160, 112, 40, 255]);
  legende(sc, "EVEIL 3 : LE RIRE, LA JARRE S'EFFONDRE");
}
function niveau3(dos) {
  return (sc) => {
    sol(sc, -40, -40, 40, 40);
    bete(sc, {});
    poussiere(sc, [0, 0, 0], 40, 20, 40, 40, [SABLE_C, SABLE, SABLE_F]);
    legende(sc, dos ? "DE DOS : PLUS DE JARRE, LA GROSSE QUEUE" : "NIVEAU 3 : ON VOIT ENCORE SES JAMBES");
  };
}
function griffes3(sc) {
  sol(sc, -50, -30, 50, 90);
  bete(sc, { mains: [[-4, 16, 30], [19, 3, 17]], devant: [0.9, -0.1, 0.4] });
  for (let q = 0; q < 46; q++) {
    const a = -1.2 + q * 0.055, r = 40 + 3 * hache(q, 1, 1500);
    for (let l = 0; l < 3; l++) cube(sc, [Math.sin(a) * r - 6, 16 + l * 3 - q * 0.08, Math.cos(a) * r], 1.2 - l * 0.2, [q, a], l === 1 ? SABLE_C : ENCRE);
  }
  for (const [x, z, s] of [[-14, 58, -1], [18, 54, 1]]) {
    perso(sc, STEVE, { decale: [x, 3, z], lacet: Math.PI + s * 0.5, penche: -0.5, brasD: [-2.2, -0.7], brasG: [-2.3, 0.7], jambeD: 0.5, jambeG: -0.3, teinte: TOUCHE });
  }
  legende(sc, "CLIC GAUCHE : GRIFFES, 13 A TOUT CE QUI EST DEVANT");
}
/**
 * Une vague de sable qui avance vers +z (ou -z si sens = -1) : son front en zf, sa hauteur h, sa largeur l (en x). Le
 * haut s'enroule vers l'avant ; de l'écume de sable au-dessus.
 */
function vagueSable(sc, zf, h, l, sens = 1, pas = 0.8, k = 0, couleur = null) {
  // le profil (z vers l'avant, y vers le haut) : un dos en pente douce, une crête qui s'enroule vers l'avant, le creux dessous
  const H = h;
  const dos = ellip([0, 0, -0.62 * H], [1e4, 0.78 * H, 0.8 * H]);
  const crete = boudin([0, 0.7 * H, -0.62 * H], [0, 1.0 * H, 0.02 * H], 0.2 * H, 0.17 * H);
  const levre = boudin([0, 1.0 * H, 0.02 * H], [0, 0.66 * H, 0.32 * H], 0.15 * H, 0.05 * H);
  const creux = ellip([0, 0.34 * H, 0.16 * H], [1e4, 0.3 * H, 0.24 * H]);
  const f = (p) => {
    const q = [0, p[1], sens * (p[2] - zf) - 0.0016 * p[0] * p[0] * (16 / h)];
    const prof = Math.max(Math.min(dos(q), crete(q), levre(q)), -creux(q));
    return Math.max(prof, Math.abs(p[0]) - l / 2, -p[1]) + h * 0.04 * bosse(p, h * 0.1, 1600 + k);
  };
  const za = zf - sens * h * 1.5, zb = zf + sens * h * 0.6;
  const sab = dune(100 + k, 12 / h);
  volume(sc, ID, f, [-l / 2 - 4, -1, Math.min(za, zb), l / 2 + 4, h * 1.25, Math.max(za, zb)], pas, couleur ?? ((p, n) => {
    // l'écume claire sur la crête
    if (p[1] > 0.82 * h && n[1] > -0.2) return mix(DUNE_C, [250, 240, 214], 0.5);
    return sab(p, n);
  }));
  for (let q = 0; q < 50; q++) {
    const x = (hache(q, 1, 1610 + k) - 0.5) * l, y = h * (0.95 + 0.35 * hache(q, 2, 1610 + k)), z = zf + sens * h * (0.25 * hache(q, 3, 1610 + k) - 0.15);
    cube(sc, [x, y, z], h * 0.035 * (0.5 + hache(q, 4, 1610 + k)), [q, x], q % 3 ? DUNE_C : DUNE);
  }
}
function vague3(etape) {
  return (sc) => {
    sol(sc, -50, -30, 50, 170, auSolDune(0, 60, 50));
    bete(sc, { buste: 0.45, mains: [[-16, 3, 22], [16, 3, 22]], devant: [0, -0.6, 1], rire: etape ? 0 : 1 });
    vagueSable(sc, etape ? 116 : 52, etape ? 60 : 34, 100, 1, 0.9, etape);
    for (const [x, z, a] of [[-20, etape ? 150 : 130, 0.2], [14, etape ? 160 : 136, 0.4]]) {
      perso(sc, STEVE, { decale: [x, etape ? 30 : 0, z], lacet: Math.PI, penche: etape ? -1.4 - a : 0, brasD: [-2.6, -0.5], brasG: [-2.6, 0.5], teinte: etape ? TOUCHE : null });
    }
    legende(sc, etape ? "ELLE LES EMPORTE : 14 + RECUL + LENTEUR" : "ACCROUPI + CLIC GAUCHE : GRANDE VAGUE");
  };
}
/** Accroupi + clic droit (niveau 3) : le cercueil sur trois cibles à la fois. */
function groupe3(sc) {
  sol(sc, -60, -30, 60, 130, (x, z) => [[-30, 84], [0, 100], [30, 84]].some(([a, b]) => Math.hypot(x - a, z - b) < 12) ? dune(61)([x, 0, z], [0, 1, 0]) : null);
  bete(sc, { buste: 0.3, mains: [[-20, 18, 24], [20, 18, 24]], devant: [0, 0.4, 1], tete: [-0.4, 0, 0] });
  for (const [x, z, k] of [[-30, 84, 0], [0, 100, 1], [30, 84, 2]]) {
    const c = [x, 22, z];
    perso(sc, STEVE, { decale: c, lacet: Math.PI, brasD: [0, -0.05], brasG: [0, 0.05], tete: [-0.2], teinte: TOUCHE });
    cocon(sc, c, 24.4, 1, 10 + k, sableI(110 + k));
    volume(sc, ID, (p) => boudin([x, 0, z], [x, 26, z], 6.5, 4.5)(p) + 0.6 * bosse(p, 1.5, 1310 + k), [x - 9, -1, z - 9, x + 9, 30, z + 9], 0.8, sableI(115 + k));
    eclat(sc, ajoute(c, [0, 16, 0]), 14, 12, 20 + k);
  }
  legende(sc, "ACCROUPI + CLIC DROIT : 3 CERCUEILS D'UN COUP");
}

// --- l'ulti, comme dans la vidéo
/** Un voile jaune sur toute la vignette (dans la tempête de sable). */
function brume(sc, c, a) {
  for (let i = 0; i < sc.w * sc.h; i++) for (let k = 0; k < 3; k++) sc.img[i * 4 + k] = Math.round(sc.img[i * 4 + k] * (1 - a) + c[k] * a);
}
/** Des rayons dorés autour d'un point. */
function rayons(sc, c, n, L, k = 0) {
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, 1700 + k) * TOUR, b = (hache(q, 2, 1700 + k) - 0.4) * 2.4;
    pointe(sc, c, [Math.cos(a) * Math.cos(b), Math.sin(b), Math.sin(a) * Math.cos(b)], L * (0.5 + 0.7 * hache(q, 3, 1700 + k)), L * 0.12, () => [...OR, 1], false, true);
  }
}
/** Un pilier de lumière dorée (voiles transparents), du sol jusqu'en haut. */
function pilier(sc, c, r, h) {
  for (let q = 0; q < 6; q++) {
    const a = (q / 6) * Math.PI;
    const dx = Math.cos(a) * r, dz = Math.sin(a) * r;
    sc.voile([[c[0] - dx, c[1] + h, c[2] - dz], [c[0] + dx, c[1] + h, c[2] + dz], [c[0] + dx, c[1], c[2] + dz], [c[0] - dx, c[1], c[2] - dz]], [255, 240, 150], 0.16);
  }
}
/** Le mur de la tempête : une falaise de sable qui roule, le haut en grosses bouffées. */
function murSable(sc, zf, h, l, k = 0) {
  const f = (p) => {
    const z = p[2] - zf + 0.0012 * p[0] * p[0];
    const haut = h * (0.75 + 0.25 * (0.5 + 0.5 * bosse([p[0], 0, 0], 30, 1900 + k)));
    const bouffe = 18 * bosse(p, 22, 1910 + k) + 8 * bosse(p, 9, 1920 + k);
    return Math.max(Math.abs(z) - 50 + bouffe, p[1] - haut + bouffe, -p[1], Math.abs(p[0]) - l / 2);
  };
  const sab = dune(140 + k, 0.08);
  volume(sc, ID, f, [-l / 2 - 10, -1, zf - 90, l / 2 + 10, h + 40, zf + 90], 5, (p, n) => mix(mix(DUNE_F, [150, 112, 66], 0.5), sab(p, n), Math.min(1, p[1] / h + 0.2)));
}
function tempete1(sc) {
  sol(sc, -40, -40, 40, 40, auSolDune(0, 0, 26));
  bete(sc, { buste: 0.15, tete: [-0.6, 0, 0], rire: 1.4, jambeD: 0.2, jambeG: -0.2, mains: [[-22, 40, 8], [22, 40, 8]], devant: [-0.3, 0.8, 0.5] });
  volume(sc, ID, helice([0, 0, 0], { r0: 24, haut: 44, tours: 2.4, k: 30, ep: [1.6, 3.4] }), [-32, -2, -32, 32, 56, 32], 0.7, dune(150));
  poussiere(sc, [0, 0, 0], 60, 26, 56, 90);
  const [hx, hy] = sc.vue([0, 64, 0]);
  ecrireTexte(sc.img, sc.w, "HA HA HA HA", Math.round(hx) - 64, Math.round(hy), 3, [160, 112, 40, 255]);
  legende(sc, "J : IL RIT, LE SABLE MONTE AUTOUR DE LUI");
}
function tempete2(sc) {
  sol(sc, -260, -60, 260, 700, auSolDune(0, 200, 500));
  bete(sc, { tete: [-0.2, 0, 0] });
  murSable(sc, 520, 360, 760);
  for (const [x, z] of [[-40, 220], [30, 190], [90, 250]]) perso(sc, STEVE, { decale: [x, 0, z], lacet: Math.PI, brasD: [-0.4, -0.3], brasG: [-0.4, 0.3] });
  legende(sc, "UN MUR DE SABLE GEANT ARRIVE DE L'HORIZON");
}
function tempete3(sc) {
  sol(sc, -120, -60, 120, 200, auSolDune(0, 60, 300));
  bete(sc, {});
  for (const [x, z, k] of [[-30, 70, 0], [10, 90, 1], [40, 64, 2]]) perso(sc, STEVE, { decale: [x, 0, z], lacet: Math.PI + 0.4 * (k - 1), penche: -0.3, brasD: [-1.6, -0.5], brasG: [-1.7, 0.5], tete: [-0.3], teinte: TOUCHE });
  for (let q = 0; q < 260; q++) {
    const a = hache(q, 1, 1930) * TOUR, r = 14 + 110 * hache(q, 2, 1930);
    cube(sc, [Math.cos(a) * r, 2 + 70 * hache(q, 3, 1930), 40 + Math.sin(a) * r], 1 + 1.6 * hache(q, 4, 1930), [q, a], q % 3 ? DUNE : DUNE_F);
  }
  brume(sc, [204, 160, 96], 0.55);
  legende(sc, "DEDANS : TOUT EST BOOSTE POUR LUI");
}
function tempete4(sc) {
  sol(sc, -120, -60, 120, 200);
  bete(sc, { buste: 0.15, tete: [-0.6, 0, 0], rire: 1.4, jambeD: 0.2, jambeG: -0.2, mains: [[-22, 40, 8], [22, 40, 8]], devant: [-0.3, 0.8, 0.5] });
  for (const [x, z, k] of [[-30, 70, 0], [10, 90, 1], [40, 64, 2]]) perso(sc, STEVE, { decale: [x, 0, z + 14], lacet: Math.PI, penche: -0.2 - 0.1 * k, brasD: [-0.4, -0.3], brasG: [-0.4, 0.3], teinte: TOUCHE });
  for (let q = 0; q < 120; q++) cube(sc, [(hache(q, 1, 1940) - 0.5) * 220, 4 + 60 * hache(q, 2, 1940), -20 + 200 * hache(q, 3, 1940)], 0.8 + hache(q, 4, 1940), [q, q], q % 3 ? DUNE : DUNE_F);
  brume(sc, [214, 178, 110], 0.18);
  legende(sc, "AU BOUT DE 45 S, ELLE RETOMBE");
}

// ------------------------------------------------------------------ la planche
const CW = 440, CH = 520, M = 34;
const TITRE = [30, 30, 40, 255], BRUN = [160, 112, 40, 255], BLEU = [40, 60, 108, 255], ROUGE = [150, 40, 40, 255];
const ACTION = { echelle: 5, tangage: 16, pied: 330 };
const MAUS_VUE = (y) => ({ tangage: 10, echelle: 1.75, lacet: 212, pied: 470, centre: [4, y, 70] });

const SECTIONS = [
  {
    titre: "ICHIBI : LA REFONTE EN 3 NIVEAUX + L'ULTI (CROQUIS, VERSION 2)", couleur: TITRE, grand: true, lignes: [
      "TOUCHE EVEIL : NIVEAU 1, PUIS 2, PUIS 3. J AU NIVEAU 3 : L'ULTI. EVEILLE 1 MIN PAR NIVEAU, PUIS 1 MIN DE REPOS (COMME TOUS LES POUVOIRS)",
      "PLUS D'EPEE : EVEILLE, IL NE SE BAT QU'AVEC LE SABLE. RIEN NE CASSE LE SOL, LE SABLE S'EFFRITE APRES. PAS DE CONTRE-POUVOIR",
      "L'ANIMATION 3D DE L'ULTI (LA TEMPETE DE SABLE) EST DANS DOCS/ICHIBI/APERCU",
    ], vues: [],
  },
  {
    titre: "TOUJOURS : LA JARRE DE GAARA", couleur: BRUN, lignes: [
      "DES QU'IL A LE POUVOIR (MEME PAS EVEILLE), UNE GROSSE JARRE SUR LE DOS : DEUX BOULES, LA SANGLE EN TRAVERS DU TORSE, LE SCEAU, LE BOUCHON",
      "EVEIL : LE BOUCHON SAUTE, LE SABLE SORT DU GOULOT ET SE MET A TOURNER AUTOUR DE LUI. PAS DE RIRE AU NIVEAU 1",
    ], vues: [
      ["DE DOS", jarreDos, { ...ENVIRON, lacet: 205 }],
      ["DE FACE", jarreFace, ENVIRON],
      ["EVEIL : LE BOUCHON SAUTE", jarreSaute, { ...ENVIRON, lacet: 150, centre: [-2, 4, -4] }],
      ["LE SABLE SORT ET TOURNE", jarreTourne, { lacet: 30, tangage: 14, echelle: 7, pied: 450, centre: [0, 2, 0] }],
    ],
  },
  {
    titre: "NIVEAU 1 : LE SABLE DE GAARA (IL TOURNE TOUJOURS AUTOUR DE LUI)", couleur: BRUN, lignes: [
      "LE SABLE TOURNE AUTOUR DE LUI TOUT LE NIVEAU 1. TOUT CE QU'IL LANCE PART DE LA JARRE. ARMURE DE SABLE : DIAMANT",
      "CLIC GAUCHE : UN GROS POING DE SABLE SORT DE LA JARRE A SA DROITE, PUIS A SA GAUCHE... 7 DEGATS, 7 BLOCS, UN COUP TOUTES LES 0,45 S",
      "    LES POINGS N'EXISTENT QUE PENDANT LES CLICS : APRES 1 S SANS CLIQUER, ILS RETOMBENT EN SABLE ET REJOIGNENT LE TOURBILLON",
      "CLIC DROIT : CERCUEIL DE SABLE (12 BLOCS) : LE SABLE LE REMPLIT JUSQU'AU COU (SEULE LA TETE DEPASSE), LE SOULEVE DE 2 BLOCS,",
      "    PUIS IL SERRE LE POING : LE SABLE LE TRANSPERCE, 12 DEGATS D'UN COUP. RECHARGE 12 S",
      "ACCROUPI + CLIC GAUCHE : BOULE DE SABLE (20 BLOCS) : 8 DEGATS, IL EST AVEUGLE 1 S. RECHARGE 6 S",
      "ACCROUPI : UN PLUS PETIT NUAGE QU'AU NIVEAU 2, 20 S DE VOL, RECHARGE 30 S",
    ], vues: [
      ["NIVEAU 1", niveau1, { lacet: 30, tangage: 12, echelle: 7.6, pied: 460, centre: [0, 0, 0] }],
      ["CLIC GAUCHE : A DROITE", coupSable(-1), { ...ACTION, echelle: 3.1, lacet: 62, pied: 360, centre: [0, 14, 44] }],
      ["CLIC SUIVANT : A GAUCHE", coupSable(1), { ...ACTION, echelle: 3.1, lacet: 62, pied: 360, centre: [0, 14, 44] }],
      ["IL ARRETE DE CLIQUER", poingsTombent, { ...ACTION, echelle: 4, lacet: 50, pied: 360, centre: [0, 12, 22] }],
      ["CLIC DROIT : CERCUEIL (1)", cercueil(0), { ...ACTION, echelle: 3.5, lacet: 64, pied: 380, centre: [0, 14, 36] }],
      ["CERCUEIL (2) : PLEIN DE SABLE", cercueil(1), { ...ACTION, echelle: 3.5, lacet: 64, pied: 380, centre: [0, 22, 36] }],
      ["CERCUEIL (3) : IL SERRE", cercueil(2), { ...ACTION, echelle: 3.5, lacet: 64, pied: 380, centre: [0, 22, 36] }],
      ["ACCROUPI : LE PETIT NUAGE", nuage1, { lacet: 30, tangage: 16, echelle: 6, pied: 400, centre: [0, 14, 0] }],
    ],
  },
  {
    titre: "NIVEAU 2 : LA MOITIE D'ICHIBI (LA FORME D'AUJOURD'HUI), LA JARRE RESTE", couleur: BLEU, lignes: [
      "EVEIL 2 : LE RIRE (LE SON DE TA VIDEO). LE SABLE COULE DU GOULOT SUR SON EPAULE ET DESCEND PETIT A PETIT SUR LA MOITIE DU CORPS",
      "    DU SABLE NORMAL TOURNE ENCORE AUTOUR DE LUI. ARMURE DE SABLE : NETHERITE. LE NUAGE : 30 S",
      "CLIC GAUCHE : LE BRAS D'ICHIBI, 11 DEGATS, 6 BLOCS. CLIC DROIT : LA GRANDE MAIN COMME AVANT (SERRE 14, JETTE 10, RECHARGE 12 S)",
      "ACCROUPI + CLIC DROIT : LE CERCUEIL, PLUS FORT : 15 BLOCS, 16 DEGATS, RECHARGE 12 S",
      "ACCROUPI + CLIC GAUCHE : ROCHER DE SABLE. LE BRAS D'ICHIBI EN ARRACHE UN ET LE LANCE (25 BLOCS) : 14 EN ZONE DE 3. RECHARGE 8 S",
      "TOUCHE TECHNIQUE : LE MAUSOLEE. UNE SEULE CIBLE, CELLE QU'IL VISE (20 BLOCS). LA STATUE (9 BLOCS) SORT DU SOL ET LE PREND DANS SES MAINS :",
      "    IL ETOUFFE 4 S SANS BOUGER, 2 DEGATS / S + 6 A LA FIN (14). RECHARGE 25 S",
    ], vues: [
      ["NIVEAU 2", niveau2, { ...ENVIRON, echelle: 7.4, pied: 460 }],
      ["EVEIL 2 : LE SABLE COULE", eveil2, { lacet: 24, tangage: 8, echelle: 4.8, pied: 410, centre: [0, 12, -6] }],
      ["CLIC DROIT : LA GRANDE MAIN", prise2, { ...ACTION, echelle: 4.3, lacet: 82, centre: [-4, 20, 16] }],
      ["ACCROUPI + CLIC DROIT", cercueil(2, true), { ...ACTION, echelle: 3.5, lacet: 64, pied: 380, centre: [0, 22, 36] }],
      ["ACCROUPI + CLIC GAUCHE : ROCHER", lancer2, { ...ACTION, echelle: 2.6, lacet: 60, pied: 380, centre: [0, 18, 64] }],
      ["TECHNIQUE : MAUSOLEE (1)", mausolee(0), MAUS_VUE(30)],
      ["MAUSOLEE (2)", mausolee(1), MAUS_VUE(36)],
      ["MAUSOLEE (3) : IL ETOUFFE", mausolee(2), MAUS_VUE(36)],
    ],
  },
  {
    titre: "NIVEAU 3 : ICHIBI PREND LE CONTROLE (TA QUATRIEME IMAGE), PLUS DE JARRE", couleur: ROUGE, lignes: [
      "EVEIL 3 : LE RIRE. LA JARRE SE FISSURE ET S'EFFONDRE, TOUT SON SABLE TOMBE SUR LUI : TETE DE BETE, DEUX GRANDS BRAS EN ARC, GROSSE QUEUE.",
      "    LE SABLE NE PREND QUE LE HAUT DES JAMBES : ON VOIT ENCORE SES JAMBES. IL COURT PENCHE (VITESSE II). ARMURE : CHEVALIER (24 / 14)",
      "CLIC GAUCHE : GRIFFES DROITE / GAUCHE, 13 DEGATS A TOUT CE QUI EST DEVANT (7 BLOCS). CLIC DROIT : LA GRANDE MAIN (SERRE 18, JETTE 12)",
      "ACCROUPI + CLIC GAUCHE : GRANDE VAGUE DE SABLE, 4 BLOCS DE HAUT, 15 BLOCS DEVANT : 14 + RECUL + LENTEUR 3 S. RECHARGE 14 S",
      "ACCROUPI + CLIC DROIT : 3 CERCUEILS D'UN COUP (LES 3 PLUS PROCHES DEVANT, 15 BLOCS) : 16 CHACUN. RECHARGE 15 S",
      "TOUCHE TECHNIQUE : LE MAUSOLEE, STATUE DE 11 BLOCS, 6 S : 2,5 / S + 8 (23). RECHARGE 25 S",
    ], vues: [
      ["EVEIL 3 : LA JARRE S'EFFONDRE", eveil3, { lacet: 150, tangage: 10, echelle: 6.6, pied: 450, centre: [-2, 4, -6] }],
      ["NIVEAU 3", niveau3(false), { lacet: 34, tangage: 10, echelle: 5.4, pied: 430, centre: [0, 6, 4] }],
      ["DE DOS", niveau3(true), { lacet: 205, tangage: 10, echelle: 5.4, pied: 430, centre: [0, 6, 4] }],
      ["CLIC GAUCHE : GRIFFES", griffes3, { ...ACTION, echelle: 3.4, lacet: 70, pied: 380, centre: [0, 10, 30] }],
      ["GRANDE VAGUE (1)", vague3(0), { ...ACTION, echelle: 2.4, lacet: 80, pied: 400, centre: [0, 14, 74] }],
      ["GRANDE VAGUE (2)", vague3(1), { ...ACTION, echelle: 2.4, lacet: 80, pied: 400, centre: [0, 14, 74] }],
      ["3 CERCUEILS D'UN COUP", groupe3, { ...ACTION, echelle: 2.6, lacet: 40, pied: 380, centre: [0, 16, 56] }],
      ["MAUSOLEE AU NIVEAU 3", mausolee(1, 2.1, " (11 BLOCS)"), { ...MAUS_VUE(44), echelle: 1.4 }],
    ],
  },
  {
    titre: "L'ULTI (J AU NIVEAU 3) : UNE GROSSE TEMPETE DE SABLE, C'EST TOUT", couleur: ROUGE, lignes: [
      "IL RIT, LE SABLE MONTE. UN MUR DE SABLE GEANT ARRIVE DE L'HORIZON ET AVALE TOUT : 45 S DE TEMPETE, 30 BLOCS AUTOUR DE LUI, ELLE LE SUIT",
      "DANS SA TEMPETE, TOUT EST BOOSTE POUR LUI : DEGATS X1,5, VITESSE III, GRANDS SAUTS, ATTAQUES PLUS RAPIDES, PORTEE X1,5, RECHARGES / 2,",
      "    ARMURE +8 ET REGENERATION II. IL VOIT A TRAVERS LE SABLE. PAS DE JARRE",
      "LES AUTRES NE VOIENT PRESQUE RIEN, SONT LENTS, LE VENT LES POUSSE, 1 DEGAT TOUTES LES 2 S. UNE FOIS PAR EVEIL, RECHARGE 5 MIN",
    ], vues: [
      ["J : IL RIT", tempete1, { lacet: 24, tangage: 10, echelle: 4.4, pied: 440, centre: [0, 10, 0] }],
      ["LE MUR DE SABLE", tempete2, { lacet: 192, tangage: 8, echelle: 0.9, pied: 420, centre: [0, 60, 200], recul: 1100 }],
      ["DANS LA TEMPETE", tempete3, { lacet: 30, tangage: 12, echelle: 2.6, pied: 420, centre: [0, 14, 40] }],
      ["ELLE RETOMBE", tempete4, { lacet: 30, tangage: 12, echelle: 2.6, pied: 420, centre: [0, 14, 40] }],
    ],
  },
];

// les briques servent aussi à l'animation 3D (../apercu/exporter.mjs)
export { volume, jarre, bete, statue, vagueSable, perso, PEAU, STEVE, J_BOUT, boule, bosse, sableI, dune, ellip, boudin, fondu };
// et au jeu (jeu.mjs)
export { PAS, boite, union, bezier, helice, cocon, rocher, teteBete, grosseQueue, bras3, J_BAS, J_HAUT, J_COL, J_MILIEU, DUNE, DUNE_C, DUNE_F };

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
const TOUTES = SECTIONS.flatMap((s) => s.vues);
const COLONNES = 4, LIGNE = 30;

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

if (PRINCIPAL && process.argv[2] === "essai") {
  const choix = process.argv[3].split(",").map(Number), k2 = 2;
  const W2 = CW * k2 * choix.length, H2 = CH * k2, img2 = Buffer.alloc(W2 * H2 * 4, 255);
  choix.forEach((k, c) => {
    const [, dessin, o] = TOUTES[k];
    const sc = scene({ w: CW * k2, h: CH * k2, fond: FOND, ...o, echelle: (o.echelle ?? 9) * k2, pied: (o.pied ?? CH * 0.88) * k2 });
    dessin(sc);
    for (let y = 0; y < H2; y++) sc.img.copy(img2, (y * W2 + c * CW * k2) * 4, y * CW * k2 * 4, (y + 1) * CW * k2 * 4);
  });
  ecrirePng(process.argv[4], W2, H2, img2);
} else if (PRINCIPAL) {
  const W = CW * COLONNES;
  const hauteurs = SECTIONS.map((s) => 90 + s.lignes.length * LIGNE + (s.grand ? 20 : 0) + Math.ceil(s.vues.length / COLONNES) * (M + CH));
  const H = hauteurs.reduce((a, b) => a + b, 0) + 20;
  const img = Buffer.alloc(W * H * 4, 255);
  let y = 0, n = 0;
  for (const [i, s] of SECTIONS.entries()) {
    const haut = 90 + s.lignes.length * LIGNE + (s.grand ? 20 : 0);
    rect(img, W, 0, y, W, y + haut, [236, 236, 242, 255]);
    if (i > 0) rect(img, W, 0, y, W, y + 6, s.couleur);
    ecrireTexte(img, W, s.titre, 30, y + 26, s.grand ? 5 : 4, s.couleur);
    s.lignes.forEach((t, k) => ecrireTexte(img, W, t, 30, y + 84 + (s.grand ? 20 : 0) + k * LIGNE, 3, TITRE));
    y += haut;
    s.vues.forEach(([titre, dessin, o], k) => {
      const c = k % COLONNES, l = Math.floor(k / COLONNES);
      const sc = scene({ w: CW, h: CH, fond: FOND, ...o });
      dessin(sc);
      for (let yy = 0; yy < CH; yy++) sc.img.copy(img, ((y + l * (M + CH) + M + yy) * W + c * CW) * 4, yy * CW * 4, (yy + 1) * CW * 4);
      ecrireTexte(img, W, titre, c * CW + 12, y + l * (M + CH) + 10, 3, k % 2 ? BLEU : BRUN);
      process.stdout.write(`${++n} `);
    });
    y += Math.ceil(s.vues.length / COLONNES) * (M + CH);
  }
  ecrirePng(path.join(DOC, "planche-refonte.png"), W, H, img);
  console.log("\nok", W, H);
}
