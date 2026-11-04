// Le Hachibi entier de la maquette du géant (docs/hachibi-geant), mais qu'on peut poser : un poing qui frappe, la gueule
// ouverte, les huit queues levées ou abattues devant lui. Le modèle est en unités de modèle, × G pour passer en blocs ;
// G = 1,5 pour le géant (76 blocs), 0,375 pour la forme du joueur (4 fois plus petit).
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, PARTIES, texels, clair, mix, hache, norme, croix, tourner } from "../../fruits/generateur/moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const SKIN = lire(path.join(ICI, "..", "..", "marque-forme2", "generateur", "skins", "sasuke.png"));

export const DEG = Math.PI / 180;
export const ajoute = (a, b) => a.map((v, i) => v + b[i]);
export const sous = (a, b) => a.map((v, i) => v - b[i]);
export const fois = (a, k) => a.map((v) => v * k);
const milieu = (a, b) => fois(ajoute(a, b), 0.5);
const scal = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

// le rose des tentacules du niveau 4 de Hachibi 2 (ClientPoulpe), partout
export const PEAU = [178, 108, 124];
const PEAU_CLAIRE = [198, 132, 146];
const VENTRE = [222, 170, 176];
const TRAIT = [118, 60, 76];
const VENTOUSE = [242, 232, 232];
const VENTOUSE_CREUX = [214, 192, 196];
const VENTOUSE_BORD = [160, 90, 108];
const OEIL_TOUR = [232, 150, 168];
const OEIL = [248, 236, 238];
const PUPILLE = [46, 22, 28];
const DENT = [246, 244, 234];
const NEZ = [226, 166, 178];
const GORGE = [92, 30, 42];

const grain = (c, i, j, graine, force = 0.07) => clair(c, 1 - force + 2 * force * hache(i, j, graine));

// ------------------------------------------------------------------ les pavés
/** Un pavé orienté (centre, axes [X, Y, Z], demi-tailles), en cases de `pas` ; couleur(face, x, y, i, j) ou null. */
export function cube(sc, c, [X, Y, Z], [hx, hy, hz], couleur, pas) {
  const faces = [
    ["avant", Z, hz, X, hx, Y, hy], ["arriere", fois(Z, -1), hz, fois(X, -1), hx, Y, hy],
    ["gauche", X, hx, fois(Z, -1), hz, Y, hy], ["droite", fois(X, -1), hx, Z, hz, Y, hy],
    ["haut", Y, hy, X, hx, fois(Z, -1), hz], ["bas", fois(Y, -1), hy, X, hx, Z, hz],
  ];
  for (const [nom, n, hn, U, hu, V, hv] of faces) {
    const l = sc.lumiere(n, false);
    if (l < 0) continue;
    const cf = ajoute(c, fois(n, hn));
    const nu = Math.max(1, Math.round((2 * hu) / pas)), nv = Math.max(1, Math.round((2 * hv) / pas));
    for (let j = 0; j < nv; j++) for (let i = 0; i < nu; i++) {
      const col = couleur(nom, -hu + ((i + 0.5) * 2 * hu) / nu, -hv + ((j + 0.5) * 2 * hv) / nv, i, j);
      if (!col) continue;
      const p = (a, b) => ajoute(ajoute(cf, fois(U, -hu + (2 * hu * a) / nu)), fois(V, -hv + (2 * hv * b) / nv));
      sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], col[3] === 1 ? col.slice(0, 3) : clair(col, l));
    }
  }
}

export const DROIT = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
const penche = (a) => [[1, 0, 0], [0, Math.cos(a), Math.sin(a)], [0, -Math.sin(a), Math.cos(a)]];
function entre(bas, haut) {
  const Y = norme(sous(haut, bas));
  const X = norme(croix(Y, [0, 0, 1]));
  return [X, Y, croix(X, Y)];
}
function axesLe(d) {
  const Y = norme(d);
  let X = croix(Y, [0, 0, 1]);
  if (Math.hypot(...X) < 0.2) X = croix(Y, [1, 0, 0]);
  X = norme(X);
  return [X, Y, croix(X, Y)];
}

// ------------------------------------------------------------------ la bête posée
/**
 * Dessine le Hachibi. o.G : la taille (blocs par unité) ; o.pos : où sont ses pieds ; o.brasD / o.brasG : "sol" (le
 * poing posé, en gorille), "coup" (le bras tendu devant lui) ou "arme" (le poing ramené contre l'épaule) ; o.gueule :
 * 0 fermée, 1 grande ouverte ; o.tete : le penché de la tête (degrés) ; o.queues : "eventail", "levees" ou "abattues".
 * Rend { haut, gueule } : sa hauteur en blocs et le bout de sa gueule (en blocs).
 */
export function hachibi(sc, o = {}) {
  const G = o.G ?? 0.375, pos = o.pos ?? [0, 0, 0];
  const P = (c) => ajoute(pos, fois(c, G));
  const piece = (c, axes, demi, couleur, pas = 0.5) =>
    cube(sc, P(c), axes, fois(demi, G), (f, x, y, i, j) => couleur(f, x / G, y / G, i, j), pas * G);
  torse(piece);
  bras(piece, 1, o.brasG ?? "sol");
  bras(piece, -1, o.brasD ?? "sol");
  // o.teteHaute : la tête relevée de ce nombre d unités (celle de l ulti en jeu : 5)
  const T = { pivot: [0, 17 + (o.teteHaute ?? 0), 7 + (o.teteHaute ? 1.5 : 0)], k: 1.4, axes: penche((o.tete ?? 12) * DEG) };
  const bout = tete(piece, T, o.gueule ?? 0);
  let haut = 0;
  const q = o.queues ?? "eventail";
  if (q === "eventail") TENTACULES.forEach(([phi, p], k) => { const ph = o.phi?.[k] ?? phi; haut = Math.max(haut, dessinerTentacule(piece, eventail(ph, p), ph)); });
  else QUEUES[q].forEach((chemin, k) => { haut = Math.max(haut, dessinerTentacule(piece, suivre(chemin), k * 37)); });
  return { haut: haut * G, gueule: P(bout), devant: norme(T.axes[2]) };
}

const muscle = (graine, stries = true) => (face, x, y, i, j) => {
  if (stries && face !== "haut" && face !== "bas") {
    const t = x / 2.1 + 0.18 * Math.sin(y * 0.6 + graine);
    if (Math.abs(t - Math.round(t)) < 0.07) return grain(mix(PEAU, TRAIT, 0.55), i, j, graine);
  }
  return grain(PEAU, i, j, graine + face.length);
};
const ligne = (d, epais = 0.28) => Math.abs(d) < epais;

const torseCouleur = (face, x, y, i, j) => {
  const ax = Math.abs(x);
  if (face === "avant") {
    const pec = 2.4 + 0.055 * x * x;
    if ((ligne(y - pec) && ax < 8.6) || (ligne(x) && y > -8)) return grain(TRAIT, i, j, 3);
    if (ax < 4.2 && y < pec - 0.3 && y > -8.4) {
      if ([-0.9, -4].some((h) => ligne(y - h, 0.2))) return grain(mix(PEAU, TRAIT, 0.45), i, j, 4);
      return grain(mix(PEAU_CLAIRE, PEAU, ax / 5), i, j, 5);
    }
    if (ax > 5.2 && ax < 8.8 && y < 1 && y > -7 && ligne(((y + ax * 0.45) % 2.2) - 1.1, 0.22)) return grain(mix(PEAU, TRAIT, 0.5), i, j, 6);
    if (y > pec && ax < 8.6) return grain(PEAU_CLAIRE, i, j, 7);
  }
  if (face === "arriere" && (ligne(x, 0.3) || (ligne(ax - 4.5 - 0.2 * y, 0.25) && y > 1))) return grain(mix(PEAU, TRAIT, 0.6), i, j, 8);
  return muscle(9, face !== "avant")(face, x, y, i, j);
};

function torse(piece) {
  const H = [0, 8, -5];
  const axes = penche(35 * DEG), [, Y, Z] = axes;
  piece([0, 5.5, -6.5], DROIT, [7.5, 5.5, 6.5], muscle(11, false));
  piece(ajoute(H, fois(Y, 9)), axes, [9.5, 9, 6], torseCouleur);
  piece(ajoute(H, ajoute(fois(Y, 16.2), fois(Z, -1.2))), axes, [11, 3, 5.4], muscle(12));
  piece(ajoute(H, ajoute(fois(Y, 13), fois(Z, -5.2))), penche(60 * DEG), [8.5, 6.5, 3.2], muscle(13));
  piece([0, 22.5, 7], penche(50 * DEG), [3.4, 3.2, 3.6], muscle(14));
}

/** Les poses d'un bras (s = +1 : son bras gauche, côté +x ; −1 : le droit) : épaule, coude, poignet. */
const POSES_BRAS = {
  sol: (s) => [[s * 15.8, 11.5, 8], [s * 13.6, 4.8, 13.5]],
  coup: (s) => [[s * 13.5, 16.5, 13], [s * 9.5, 6, 23]],
  arme: (s) => [[s * 17, 13, 1], [s * 14, 19, 6]],
};

function bras(piece, s, pose) {
  const epaule = [s * 11.8, 20.5, 3.5], [coude, poignet] = POSES_BRAS[pose](s);
  piece(epaule, penche(20 * DEG), [4.4, 4.2, 4.6], muscle(20 + s));
  piece(milieu(epaule, coude), entre(coude, epaule), [3.5, Math.hypot(...sous(epaule, coude)) / 2 + 0.8, 3.7], muscle(22 + s));
  piece(ajoute(milieu(epaule, coude), [0, -0.5, 1.6]), entre(coude, epaule), [2.8, 3.2, 2.4], muscle(24 + s));
  piece(milieu(coude, poignet), entre(poignet, coude), [3.4, Math.hypot(...sous(coude, poignet)) / 2 + 0.6, 3.5], muscle(26 + s));
  // le poing : au sol comme le géant ; sinon dans l'axe de l'avant-bras, les jointures devant
  let c = [s * 13.6, 2.6, 14.6], F = DROIT;
  if (pose !== "sol") {
    const Z = norme(sous(poignet, coude)), Y = norme(sous([0, 1, 0], fois(Z, Z[1])));
    F = [croix(Y, Z), Y, Z];
    c = ajoute(poignet, fois(Z, 2.4));
  }
  const dans = (v) => ajoute(c, ajoute(ajoute(fois(F[0], v[0]), fois(F[1], v[1])), fois(F[2], v[2])));
  piece(c, F, [3.3, 2.6, 3.5], (face, x, y, i, j) => {
    if (face === "avant" && (ligne(y - 1.2, 0.26) || (y > -1.6 && [-1.65, 0, 1.65].some((h) => ligne(x - h, 0.26))))) return grain(TRAIT, i, j, 30);
    if (face === "haut" && y > 0 && [-1.65, 0, 1.65].some((h) => ligne(x - h, 0.26))) return grain(TRAIT, i, j, 31);
    return muscle(32, false)(face, x, y, i, j);
  }, 0.4);
  piece(dans([-s * 3.9, 0.8, 1.6]), F, [0.9, 1.1, 1.8], muscle(33, false), 0.4);
}

// ------------------------------------------------------------------ la tête de taureau
const stries = (x, graine, i, j, base = PEAU) => {
  const t = x / 0.9 + 0.5;
  return Math.abs(t - Math.round(t)) < 0.12 ? grain(mix(base, TRAIT, 0.45), i, j, graine) : grain(base, i, j, graine);
};
function oeil(x, y) {
  const d = Math.hypot(x, y) / 1.2;
  return d < 0.28 ? PUPILLE : d < 0.62 ? OEIL : d < 0.9 ? OEIL_TOUR : d < 1.15 ? TRAIT : null;
}
const dents = (face, x) => (face === "haut" || face === "bas" ? DENT : ligne(((x + 9) % 0.62) - 0.31, 0.07) ? GORGE : DENT);

/** La tête, dans le repère T (pivot, taille k, axes) ; g : la gueule ouverte de 0 à 1. Rend le bout de la gueule. */
function tete(piece, T, g) {
  const vers = (v) => ajoute(ajoute(fois(T.axes[0], v[0]), fois(T.axes[1], v[1])), fois(T.axes[2], v[2]));
  const m = (c, demi, couleur, pas = 0.3, axes = DROIT) =>
    piece(ajoute(T.pivot, fois(vers(c), T.k)), axes.map(vers), fois(demi, T.k), (f, x, y, i, j) => couleur(f, x / T.k, y / T.k, i, j), pas * T.k);
  m([0, 5.9, 1.2], [4.0, 2.5, 3.4], (face, x, y, i, j) => {
    if (face === "avant") return oeil(x - 2.2, y - 0.1) ?? oeil(x + 2.2, y - 0.1) ?? stries(x, 40, i, j);
    return stries(face === "haut" ? x : y, 41, i, j);
  });
  m([0, 1.9, 1.6], [3.0, 1.6, 3.0], (face, x, y, i, j) => stries(face === "gauche" || face === "droite" ? y : x, 39, i, j));
  for (const c of [-1, 1]) m([c * 2.2, 7.65, 4.85], [2.0, 0.45, 0.5], (f, x, y, i, j) => grain(mix(PEAU, TRAIT, 0.35), i, j, 44), 0.3, [norme([1, -c * 0.32, 0]), norme([c * 0.32, 1, 0]), [0, 0, 1]]);
  m([0, 8.9, 0.4], [2.4, 0.7, 2.6], (f, x, y, i, j) => stries(x, 45, i, j));
  m([0, 0.6, 2.4], [2.8, 1.6, 2.2], (f, x, y, i, j) => stries(f === "gauche" || f === "droite" ? x : y, 47, i, j));
  m([0, -2.4, 2.8], [2.3, 1.4, 1.9], (f, x, y, i, j) => stries(f === "gauche" || f === "droite" ? x : y, 46, i, j));
  m([0, 1.0, 5.6], [2.3, 1.9, 1.5], (face, x, y, i, j) => stries(face === "haut" ? x : face === "gauche" || face === "droite" ? y : x, 48, i, j));
  m([0, 1.1, 7.35], [2.1, 1.5, 0.3], (face, x, y, i, j) => {
    if (face === "avant") {
      const ax = Math.abs(x), u = (ax - 1.05) * 0.94 - (y + 0.1) * 0.34, v = (ax - 1.05) * 0.34 + (y + 0.1) * 0.94;
      const d = Math.hypot(u / 0.42, v / 0.8);
      if (d < 1) return PUPILLE;
      if (d < 1.35) return grain(mix(NEZ, TRAIT, 0.35), i, j, 49);
    }
    return grain(NEZ, i, j, 49);
  });
  // la gueule : les dents du haut restent, la mâchoire descend de 2,4 quand elle est grande ouverte
  const b = 2.4 * g;
  m([0, -1.35, 5.5], [2.15, 0.45, 1.5], dents, 0.2);
  m([0, -2.05 - b / 2, 5.0], [1.95, 0.4 + b / 2, 1.3], () => GORGE, 0.4);
  m([0, -2.65 - b, 5.3], [2.0, 0.35, 1.4], dents, 0.2);
  m([0, -3.6 - b, 4.8], [2.1, 0.7, 1.9], (f, x, y, i, j) => stries(f === "gauche" || f === "droite" ? y : x, 50, i, j));
  for (const c of [-1, 1]) {
    let p = [c * 3.8, 7.6, 1.0], d = norme([c, 0.35, 0.05]);
    const n = 20, pas = 18 / n;
    for (let k = 0; k < n; k++) {
      const w = 3.6 * (1 - (k / n) * 0.8);
      m(ajoute(p, fois(d, pas / 2)), [w / 2, pas * 0.7, w / 2], (f, x, y, i, j) => stries(x, 51 + k, i, j, mix(PEAU, PEAU_CLAIRE, k / n)), 0.25, axesLe(d));
      p = ajoute(p, fois(d, pas));
      d = norme(ajoute(tourner(d, [0, 0, 1], -c * 0.13), [0, 0, 0.04]));
    }
    let q = [c * 4.3, 5.4, 0.2];
    const e = norme([c, -0.25, 0.15]);
    for (let k = 0; k < 3; k++) {
      const w = 1.8 * (1 - k * 0.28);
      m(ajoute(q, fois(e, 0.6)), [w / 2, 0.75, w * 0.22], (f, x, y, i, j) => grain(f === "avant" ? NEZ : PEAU, i, j, 75 + k), 0.25, axesLe(e));
      q = ajoute(q, fois(e, 1.2));
    }
  }
  return ajoute(T.pivot, fois(vers([0, -2.2 - b / 2, 7.4]), T.k));
}

// ------------------------------------------------------------------ les tentacules
/**
 * Une tentacule, donnée par ses tranches { c, A (le long), B (le dessous, aux ventouses), ds, u (0 à la base, 1 au bout) }.
 * Rend sa hauteur (unités de modèle).
 */
function dessinerTentacule(piece, tranches, graine) {
  const L = tranches.reduce((s, t) => s + t.ds, 0);
  const largeur = (u) => 0.9 + 9 * (1 - u) ** 0.75;
  const ventouses = [];
  for (let s = 3; s < L - 0.6; s += 0.92 * largeur(s / L)) ventouses.push(s);
  let s0 = 0, haut = 0;
  tranches.forEach(({ c, A, B, ds, u }, i) => {
    haut = Math.max(haut, c[1]);
    const w = largeur(u), debut = s0 + ds / 2;
    s0 += ds;
    const pas = Math.min(0.55, Math.max(0.16, w / 8));
    piece(c, [croix(A, B), A, B], [w * 0.46, ds * 0.72, w / 2], (face, x, yy, ii, jj) => {
      const bord = face === "gauche" ? x + w / 2 : face === "droite" ? x - w / 2 : null;
      if (face !== "avant" && bord === null) return grain(PEAU, ii, jj, Math.round(graine) + i);
      const travers = face === "avant" ? x : bord * 1.6;
      const s = debut + yy;
      let meilleur = 9;
      for (const v of ventouses) meilleur = Math.min(meilleur, Math.hypot(s - v, travers) / (0.34 * largeur(v / L)));
      if (face !== "avant") return meilleur < 1 ? grain(VENTOUSE, ii, jj, 61, 0.03) : meilleur < 1.2 ? TRAIT : grain(PEAU, ii, jj, Math.round(graine) + i);
      if (meilleur < 0.45) return VENTOUSE_CREUX;
      if (meilleur < 1) return grain(VENTOUSE, ii, jj, 60, 0.03);
      if (meilleur < 1.2) return VENTOUSE_BORD;
      return grain(VENTRE, ii, jj, Math.round(graine) + i);
    }, pas);
  });
  return haut;
}

/** Les tranches d'une tentacule du géant, en éventail derrière lui (comme docs/hachibi-geant). */
function eventail(phi, { L, t0, u1, A1, u2, A2, C, lat = 0 }) {
  const o = [Math.sin(phi * DEG), 0, -Math.cos(phi * DEG)], cote = [Math.cos(phi * DEG), 0, Math.sin(phi * DEG)];
  const N = 110, ds = L / N, tranches = [];
  let r = 3.2, y = 5.8, th = t0 * DEG;
  for (let i = 0; i < N; i++) {
    const u = (i + 0.5) / N;
    let k;
    if (u < u1) k = A1 / u1;
    else if (u < u2) k = A2 / (u2 - u1);
    else { const f = (u - u2) / (1 - u2); k = (C * 2.6 * f ** 1.6) / (1 - u2); }
    th += (k * DEG) / N;
    const c = ajoute(ajoute([0, 0, -4.5], fois(o, r + (Math.cos(th) * ds) / 2)), ajoute([0, y + (Math.sin(th) * ds) / 2, 0], fois(cote, lat * u * u * 9)));
    r += Math.cos(th) * ds;
    y += Math.sin(th) * ds;
    const A = norme(ajoute(fois(o, Math.cos(th)), [0, Math.sin(th), 0]));
    const B = norme(sous(fois(o, Math.sin(th)), [0, Math.cos(th), 0]));
    tranches.push({ c, A, B, ds, u });
  }
  return tranches;
}

const TENTACULES = [
  [-16, { L: 70, t0: -8, u1: 0.3, A1: 92, u2: 0.6, A2: 0, C: -380, lat: -0.9 }],
  [16, { L: 68, t0: -8, u1: 0.3, A1: 94, u2: 0.6, A2: 0, C: 370, lat: 0.9 }],
  [-48, { L: 64, t0: -12, u1: 0.3, A1: 96, u2: 0.6, A2: 12, C: 380, lat: 0.6 }],
  [48, { L: 66, t0: -12, u1: 0.3, A1: 100, u2: 0.58, A2: 8, C: -390, lat: -0.4 }],
  [-82, { L: 58, t0: -14, u1: 0.32, A1: 90, u2: 0.6, A2: 4, C: -380, lat: 0.5 }],
  [82, { L: 60, t0: -14, u1: 0.32, A1: 88, u2: 0.6, A2: 8, C: 370, lat: -0.6 }],
  [-118, { L: 50, t0: -10, u1: 0.3, A1: 22, u2: 0.48, A2: 8, C: 380, lat: 0.6 }],
  [118, { L: 50, t0: -10, u1: 0.3, A1: 24, u2: 0.48, A2: 6, C: 380, lat: -0.6 }],
];

/** Un point d'une courbe de Bézier cubique. */
const bezier = ([a, b, c, d], t) => {
  const s = 1 - t;
  return a.map((_, i) => s * s * s * a[i] + 3 * s * s * t * b[i] + 3 * s * t * t * c[i] + t * t * t * d[i]);
};

/**
 * Les tranches d'une tentacule qui suit un chemin : une suite de Béziers cubiques bout à bout. Le dessous (les ventouses)
 * regarde vers `dessous`, redressé pour rester perpendiculaire à la tentacule.
 */
function suivre({ morceaux, dessous = [0, -1, -0.5], N = 120 }) {
  const pts = [];
  morceaux.forEach((m, k) => { for (let i = k ? 1 : 0; i <= 60; i++) pts.push(bezier(m, i / 60)); });
  const long = [0];
  for (let i = 1; i < pts.length; i++) long.push(long[i - 1] + Math.hypot(...sous(pts[i], pts[i - 1])));
  const L = long[long.length - 1], ds = L / N;
  const en = (s) => {
    let i = 1;
    while (i < long.length - 1 && long[i] < s) i++;
    const t = (s - long[i - 1]) / (long[i] - long[i - 1] || 1);
    return ajoute(pts[i - 1], fois(sous(pts[i], pts[i - 1]), t));
  };
  const tranches = [];
  for (let i = 0; i < N; i++) {
    const a = en(i * ds), b = en((i + 1) * ds);
    const A = norme(sous(b, a));
    let B = sous(norme(dessous), fois(A, scal(A, norme(dessous))));
    if (Math.hypot(...B) < 0.15) B = sous([0, 0, -1], fois(A, A[2] * -1));
    tranches.push({ c: milieu(a, b), A, B: norme(B), ds, u: (i + 0.5) / N });
  }
  return tranches;
}

// les huit queues, de gauche à droite (vues de dos) : levées au-dessus de lui, ou abattues devant lui
const RANGS = [-3.5, -2.5, -1.5, -0.5, 0.5, 1.5, 2.5, 3.5];
/** La hauteur d'une queue levée : celles du milieu plus haut, une sur deux un peu décalée. */
const h = (r) => 64 - Math.abs(r) * 3 + (Math.round(Math.abs(r) + 0.5) % 2 ? 4 : 0);
const QUEUES = {
  // il les lève toutes ensemble, très haut au-dessus de sa tête, les bouts recourbés vers l'avant (prêt à frapper)
  levees: RANGS.map((r) => ({
    morceaux: [[[r * 1.7, 6, -9], [r * 3.4, 24, -24], [r * 7.6, h(r) - 14, -22], [r * 8.5, h(r), -8]],
      [[r * 8.5, h(r), -8], [r * 8.8, h(r) + 4, 0], [r * 8.8, h(r) - 1, 8], [r * 8.3, h(r) - 7, 7]]],
    dessous: [0, 0, 1],
  })),
  // il les abat devant lui, comme une main qui se referme : elles partent en éventail de son dos, celles du milieu passent
  // par-dessus sa tête, celles des côtés autour de ses flancs, et toutes s'écrasent à plat sur le sol, loin devant
  abattues: TENTACULES.map(([phi]) => {
    const o = [Math.sin(phi * DEG), 0, -Math.cos(phi * DEG)], s = Math.sign(phi), rang = Math.abs(phi) / 118;
    const base = ajoute([0, 5.8, -4.5], fois(o, 3.2));
    const haut = 54 - rang * 42, cote = s * (4 + rang * 30), sol = s * (3 + rang * 26);
    return {
      morceaux: [[base, ajoute(base, ajoute(fois(o, 12), [0, 20, 0])), [cote, haut, 10 + rang * 6], [sol, 2.4, 40]],
        [[sol, 2.4, 40], [sol * 1.05, 0.6, 48], [sol * 1.15, 0.6, 60], [sol * 1.25, 0.6, 72 - rang * 8]]],
      dessous: [-s * rang, -1, 0.3],
    };
  }),
};

// ------------------------------------------------------------------ le joueur
/** Le joueur (la peau de Sasuke), à sa taille (1,8 bloc), regardant vers lacet. */
export function joueur(sc, pos, lacet = 0, teinte = null) {
  const k = 1.8 / 32;
  const rot = (v) => [v[0] * Math.cos(lacet) + v[2] * Math.sin(lacet), v[1], -v[0] * Math.sin(lacet) + v[2] * Math.cos(lacet)];
  for (const partie of PARTIES(false)) for (const couche of [2, 3]) {
    for (const t of texels(partie[1], partie[couche], couche === 3 ? partie[4] : 0, 1)) {
      const i = (t.ty * SKIN.w + t.tx) * 4;
      if (SKIN.px[i + 3] < 128) continue;
      const l = sc.lumiere(rot(t.N), false);
      if (l < 0) continue;
      let c = [SKIN.px[i], SKIN.px[i + 1], SKIN.px[i + 2]];
      if (teinte) c = mix(c, teinte.c, teinte.k);
      sc.quad(t.coins.map((v) => ajoute(fois(rot(v), k), pos)), clair(c, l));
    }
  }
}
