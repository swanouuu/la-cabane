// Maquette : le Hachibi entier, version Minecraft, en géant (plus haut qu'un grand arbre de la jungle, 31 blocs).
// Le corps musclé penché en avant sur ses poings, la tête de bœuf aux cornes recourbées, et huit tentacules de poulpe
// en éventail derrière lui, qui montent et s'enroulent au bout. Tout est fait de pavés, comme un mob.
// Coordonnées en blocs : le sol à y = 0, la bête regarde vers +z.
// node rendu.mjs → ../planche-hachibi-geant.png
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, PARTIES, texels, clair, mix, hache, norme, croix, tourner, scene, ecrireTexte, POLICE } from "../../hachibi2/generateur/moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const SKIN = lire(path.join(ICI, "..", "..", "marque-forme2", "generateur", "skins", "sasuke.png"));
Object.assign(POLICE, { 0: "111101101101111", 7: "111001010010010", 8: "111101111101111", 9: "111101111001110" });

const DEG = Math.PI / 180;
const ajoute = (a, b) => a.map((v, i) => v + b[i]);
const sous = (a, b) => a.map((v, i) => v - b[i]);
const fois = (a, k) => a.map((v) => v * k);
const milieu = (a, b) => fois(ajoute(a, b), 0.5);

// le rose des tentacules du niveau 4 de Hachibi 2 (ClientPoulpe), partout
const PEAU = [178, 108, 124];
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

/** Un peu de grain sur chaque case, comme les pixels d'une texture. */
const grain = (c, i, j, graine, force = 0.07) => clair(c, 1 - force + 2 * force * hache(i, j, graine));

// ------------------------------------------------------------------ les pavés
/**
 * Un pavé orienté : son centre, ses axes [X (côté), Y (haut), Z (avant)] et ses demi-tailles, découpé en cases de `pas`.
 * `couleur(face, x, y, i, j)` donne la couleur de chaque case (x, y : la position sur la face, depuis son centre), ou null.
 */
function cube(sc, c, [X, Y, Z], [hx, hy, hz], couleur, pas) {
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
      sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], clair(col, l));
    }
  }
}

const DROIT = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
/** Les axes d'un pavé penché en avant (le haut part vers +z). */
const penche = (a) => [[1, 0, 0], [0, Math.cos(a), Math.sin(a)], [0, -Math.sin(a), Math.cos(a)]];
/** Les axes d'un membre, du bas vers le haut. */
function entre(bas, haut) {
  const Y = norme(sous(haut, bas));
  const X = norme(croix(Y, [0, 0, 1]));
  return [X, Y, croix(X, Y)];
}

// ------------------------------------------------------------------ le Hachibi (en unités de modèle, × G en blocs)
const G = 1.5;
// les couleurs se calculent en unités de modèle (avant le × G)
const piece = (sc, c, axes, demi, couleur, pas = 0.5) =>
  cube(sc, fois(c, G), axes, fois(demi, G), (f, x, y, i, j) => couleur(f, x / G, y / G, i, j), pas * G);

/** La peau brune, avec ses stries de muscles le long du membre. */
const muscle = (graine, stries = true) => (face, x, y, i, j) => {
  if (stries && face !== "haut" && face !== "bas") {
    const t = x / 2.1 + 0.18 * Math.sin(y * 0.6 + graine);
    if (Math.abs(t - Math.round(t)) < 0.07) return grain(mix(PEAU, TRAIT, 0.55), i, j, graine);
  }
  return grain(PEAU, i, j, graine + face.length);
};
const ligne = (d, epais = 0.28) => Math.abs(d) < epais;

/** Le torse : les pectoraux et les abdos devant, la colonne et les omoplates dans le dos. */
const torseCouleur = (face, x, y, i, j) => {
  const ax = Math.abs(x);
  if (face === "avant") {
    const pec = 2.4 + 0.055 * x * x;
    if ((ligne(y - pec) && ax < 8.6) || (ligne(x) && y > -8)) return grain(TRAIT, i, j, 3);
    if (ax < 4.2 && y < pec - 0.3 && y > -8.4) {
      if ([-0.9, -4].some((h) => ligne(y - h, 0.2))) return grain(mix(PEAU, TRAIT, 0.45), i, j, 4);
      return grain(mix(PEAU_CLAIRE, PEAU, ax / 5), i, j, 5);
    }
    // les côtes sur les flancs
    if (ax > 5.2 && ax < 8.8 && y < 1 && y > -7 && ligne(((y + ax * 0.45) % 2.2) - 1.1, 0.22)) return grain(mix(PEAU, TRAIT, 0.5), i, j, 6);
    if (y > pec && ax < 8.6) return grain(PEAU_CLAIRE, i, j, 7);
  }
  if (face === "arriere" && (ligne(x, 0.3) || (ligne(ax - 4.5 - 0.2 * y, 0.25) && y > 1))) return grain(mix(PEAU, TRAIT, 0.6), i, j, 8);
  return muscle(9, face !== "avant")(face, x, y, i, j);
};

function torse(sc) {
  const H = [0, 8, -5];
  const axes = penche(35 * DEG), [, Y, Z] = axes;
  // le bassin, d'où partent les tentacules
  piece(sc, [0, 5.5, -6.5], DROIT, [7.5, 5.5, 6.5], muscle(11, false));
  piece(sc, ajoute(H, fois(Y, 9)), axes, [9.5, 9, 6], torseCouleur);
  // les trapèzes, plus larges, et la grosse bosse du haut du dos
  piece(sc, ajoute(H, ajoute(fois(Y, 16.2), fois(Z, -1.2))), axes, [11, 3, 5.4], muscle(12));
  piece(sc, ajoute(H, ajoute(fois(Y, 13), fois(Z, -5.2))), penche(60 * DEG), [8.5, 6.5, 3.2], muscle(13));
  // le cou, épais, qui pousse la tête en avant
  piece(sc, [0, 22.5, 7], penche(50 * DEG), [3.4, 3.2, 3.6], muscle(14));
}

/** Un bras de gorille : l'épaule, le bras, l'avant-bras énorme, et le poing posé par terre. */
function bras(sc, s) {
  const epaule = [s * 11.8, 20.5, 3.5], coude = [s * 15.8, 11.5, 8], poignet = [s * 13.6, 4.8, 13.5];
  piece(sc, epaule, penche(20 * DEG), [4.4, 4.2, 4.6], muscle(20 + s));
  piece(sc, milieu(epaule, coude), entre(coude, epaule), [3.5, Math.hypot(...sous(epaule, coude)) / 2 + 0.8, 3.7], muscle(22 + s));
  // le biceps, qui gonfle devant
  piece(sc, ajoute(milieu(epaule, coude), [0, -0.5, 1.6]), entre(coude, epaule), [2.8, 3.2, 2.4], muscle(24 + s));
  piece(sc, milieu(coude, poignet), entre(poignet, coude), [3.4, Math.hypot(...sous(coude, poignet)) / 2 + 0.6, 3.5], muscle(26 + s));
  // le poing : les jointures devant, les doigts repliés dessus, le pouce à l'intérieur
  piece(sc, [s * 13.6, 2.6, 14.6], DROIT, [3.3, 2.6, 3.5], (face, x, y, i, j) => {
    if (face === "avant" && (ligne(y - 1.2, 0.26) || (y > -1.6 && [-1.65, 0, 1.65].some((h) => ligne(x - h, 0.26))))) return grain(TRAIT, i, j, 30);
    if (face === "haut" && y > 0 && [-1.65, 0, 1.65].some((h) => ligne(x - h, 0.26))) return grain(TRAIT, i, j, 31);
    return muscle(32, false)(face, x, y, i, j);
  }, 0.4);
  piece(sc, [s * 9.7, 3.4, 16.2], DROIT, [0.9, 1.1, 1.8], muscle(33, false), 0.4);
}

/**
 * La tête de taureau, penchée vers l'avant : le front large et strié, les yeux ronds sous une arcade en colère, le long
 * mufle qui avance avec ses gros naseaux, la gueule entrouverte pleine de dents (le grand sourire), les cornes épaisses
 * qui partent du haut du crâne et se recourbent vers le bas autour du visage, et les oreilles à l'horizontale.
 */
const stries = (x, graine, i, j, base = PEAU) => {
  const t = x / 0.9 + 0.5;
  return Math.abs(t - Math.round(t)) < 0.12 ? grain(mix(base, TRAIT, 0.45), i, j, graine) : grain(base, i, j, graine);
};
function oeil(x, y) {
  const d = Math.hypot(x, y) / 1.2;
  return d < 0.28 ? PUPILLE : d < 0.62 ? OEIL : d < 0.9 ? OEIL_TOUR : d < 1.15 ? TRAIT : null;
}
/** Des dents : blanches, séparées par de fins traits sombres. */
const dents = (face, x, y, i, j) => (face === "haut" || face === "bas" ? DENT : ligne(((x + 9) % 0.62) - 0.31, 0.07) ? GORGE : DENT);
/** La tête : son pivot (la nuque), sa taille, et son penché vers l'avant. */
const TETE = { pivot: [0, 17, 7], k: 1.4, axes: penche(12 * DEG) };
const versTete = (v) => ajoute(ajoute(fois(TETE.axes[0], v[0]), fois(TETE.axes[1], v[1])), fois(TETE.axes[2], v[2]));
/** Un morceau de la tête, donné dans le repère de la tête. */
const morceauTete = (sc, c, demi, couleur, pas, axes = DROIT) =>
  piece(sc, ajoute(TETE.pivot, fois(versTete(c), TETE.k)), axes.map(versTete), fois(demi, TETE.k),
    (f, x, y, i, j) => couleur(f, x / TETE.k, y / TETE.k, i, j), pas * TETE.k);
function tete(sc) {
  const m = (c, demi, couleur, pas = 0.3, axes) => morceauTete(sc, c, demi, couleur, pas, axes);
  // le front, large, avec les yeux ; puis l'arête du nez, plus étroite : le visage s'affine vers le bas
  m([0, 5.9, 1.2], [4.0, 2.5, 3.4], (face, x, y, i, j) => {
    if (face === "avant") return oeil(x - 2.2, y - 0.1) ?? oeil(x + 2.2, y - 0.1) ?? stries(x, 40, i, j);
    return stries(face === "haut" ? x : y, 41, i, j);
  });
  m([0, 1.9, 1.6], [3.0, 1.6, 3.0], (face, x, y, i, j) => stries(face === "gauche" || face === "droite" ? y : x, 39, i, j));
  // l'arcade en V, comme un taureau en colère, et le toupet sur le haut du crâne
  for (const c of [-1, 1]) m([c * 2.2, 7.65, 4.85], [2.0, 0.45, 0.5], (f, x, y, i, j) => grain(mix(PEAU, TRAIT, 0.35), i, j, 44), 0.3, [norme([1, -c * 0.32, 0]), norme([c * 0.32, 1, 0]), [0, 0, 1]]);
  m([0, 8.9, 0.4], [2.4, 0.7, 2.6], (f, x, y, i, j) => stries(x, 45, i, j));
  // les joues, de plus en plus étroites jusqu'à la mâchoire
  m([0, 0.6, 2.4], [2.8, 1.6, 2.2], (f, x, y, i, j) => stries(f === "gauche" || f === "droite" ? x : y, 47, i, j));
  m([0, -2.4, 2.8], [2.3, 1.4, 1.9], (f, x, y, i, j) => stries(f === "gauche" || f === "droite" ? x : y, 46, i, j));
  // le mufle de taureau : étroit et court, avec ses gros naseaux
  m([0, 1.0, 5.6], [2.3, 1.9, 1.5], (face, x, y, i, j) => stries(face === "haut" ? x : face === "gauche" || face === "droite" ? y : x, 48, i, j));
  m([0, 1.1, 7.35], [2.1, 1.5, 0.3], (face, x, y, i, j) => {
    if (face === "avant") {
      // les naseaux : deux grosses virgules penchées vers l'extérieur
      const ax = Math.abs(x), u = (ax - 1.05) * 0.94 - (y + 0.1) * 0.34, v = (ax - 1.05) * 0.34 + (y + 0.1) * 0.94;
      const d = Math.hypot(u / 0.42, v / 0.8);
      if (d < 1) return PUPILLE;
      if (d < 1.35) return grain(mix(NEZ, TRAIT, 0.35), i, j, 49);
    }
    return grain(NEZ, i, j, 49);
  });
  // la gueule entrouverte, en grand sourire : les dents du haut, le fond sombre, les dents du bas, la mâchoire
  m([0, -1.35, 5.5], [2.15, 0.45, 1.5], dents, 0.2);
  m([0, -2.05, 5.0], [1.95, 0.4, 1.3], () => GORGE, 0.4);
  m([0, -2.65, 5.3], [2.0, 0.35, 1.4], dents, 0.2);
  m([0, -3.6, 4.8], [2.1, 0.7, 1.9], (f, x, y, i, j) => stries(f === "gauche" || f === "droite" ? y : x, 50, i, j));
  for (const c of [-1, 1]) {
    // la corne : épaisse à la base, sur le haut du crâne ; elle part sur le côté, puis se recourbe vers le bas
    // autour du visage
    let p = [c * 3.8, 7.6, 1.0], d = norme([c, 0.35, 0.05]);
    const n = 20, pas = 18 / n;
    for (let k = 0; k < n; k++) {
      const w = 3.6 * (1 - (k / n) * 0.8);
      m(ajoute(p, fois(d, pas / 2)), [w / 2, pas * 0.7, w / 2], (f, x, y, i, j) => stries(x, 51 + k, i, j, mix(PEAU, PEAU_CLAIRE, k / n)), 0.25, axesLe(d));
      p = ajoute(p, fois(d, pas));
      d = norme(ajoute(tourner(d, [0, 0, 1], -c * 0.13), [0, 0, 0.04]));
    }
    // l'oreille, à l'horizontale sous la corne, rose clair dedans
    let q = [c * 4.3, 5.4, 0.2];
    const e = norme([c, -0.25, 0.15]);
    for (let k = 0; k < 3; k++) {
      const w = 1.8 * (1 - k * 0.28);
      m(ajoute(q, fois(e, 0.6)), [w / 2, 0.75, w * 0.22], (f, x, y, i, j) => grain(f === "avant" ? NEZ : PEAU, i, j, 75 + k), 0.25, axesLe(e));
      q = ajoute(q, fois(e, 1.2));
    }
  }
}
/** Les axes d'un pavé allongé le long de d. */
function axesLe(d) {
  const Y = norme(d);
  let X = croix(Y, [0, 0, 1]);
  if (Math.hypot(...X) < 0.2) X = croix(Y, [1, 0, 0]);
  X = norme(X);
  return [X, Y, croix(X, Y)];
}

/**
 * Une tentacule : elle part du bassin vers l'extérieur, frôle le sol, se redresse vers le ciel, puis s'enroule au bout
 * (vers l'extérieur ou vers l'intérieur). Les ventouses sont sur le dessous, qui devient l'intérieur de la spirale.
 */
function tentacule(sc, phi, { L, t0, u1, A1, u2, A2, C, lat = 0 }) {
  const o = [Math.sin(phi * DEG), 0, -Math.cos(phi * DEG)], cote = [Math.cos(phi * DEG), 0, Math.sin(phi * DEG)];
  const N = 110, ds = L / N;
  const largeur = (u) => 0.9 + 9 * (1 - u) ** 0.75;
  // les ventouses, espacées selon la largeur
  const ventouses = [];
  for (let s = 3; s < L - 0.6; s += 0.92 * largeur(s / L)) ventouses.push(s);
  let r = 3.2, y = 5.8, th = t0 * DEG, haut = 0;
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
    haut = Math.max(haut, y);
    const A = norme(ajoute(fois(o, Math.cos(th)), [0, Math.sin(th), 0]));
    const B = norme(sous(fois(o, Math.sin(th)), [0, Math.cos(th), 0]));
    const w = largeur(u), s0 = i * ds + ds / 2;
    const pas = Math.min(0.55, Math.max(0.16, w / 8));
    piece(sc, c, [croix(A, B), A, B], [w * 0.46, ds * 0.72, w / 2], (face, x, yy, ii, jj) => {
      // sur le dessous, en plein ; sur les côtés, la moitié qui dépasse du bord
      const bord = face === "gauche" ? x + w / 2 : face === "droite" ? x - w / 2 : null;
      if (face !== "avant" && bord === null) return grain(PEAU, ii, jj, Math.round(phi) + i);
      const travers = face === "avant" ? x : bord * 1.6;
      const s = s0 + yy;
      let meilleur = 9;
      for (const v of ventouses) meilleur = Math.min(meilleur, Math.hypot(s - v, travers) / (0.34 * largeur(v / L)));
      if (face !== "avant") return meilleur < 1 ? grain(VENTOUSE, ii, jj, 61, 0.03) : meilleur < 1.2 ? TRAIT : grain(PEAU, ii, jj, Math.round(phi) + i);
      if (meilleur < 0.45) return VENTOUSE_CREUX;
      if (meilleur < 1) return grain(VENTOUSE, ii, jj, 60, 0.03);
      if (meilleur < 1.2) return VENTOUSE_BORD;
      return grain(VENTRE, ii, jj, Math.round(phi) + i);
    }, pas);
  }
  return haut;
}

/** Les huit tentacules, en éventail derrière lui (l'angle part de l'arrière). */
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

function hachibi(sc) {
  torse(sc);
  bras(sc, -1);
  bras(sc, 1);
  tete(sc);
  let haut = 0;
  for (const [phi, p] of TENTACULES) haut = Math.max(haut, tentacule(sc, phi, p));
  return haut * G;
}

// ------------------------------------------------------------------ le décor : une île de terre, l'arbre, le joueur
const ARBRE = [-60, -6];
/** L'ombre de la bête et de l'arbre sur l'herbe. */
function ombre(x, z) {
  let k = 1;
  const d = Math.hypot(x / 44, (z + 8) / 38);
  if (d < 1) k *= 1 - 0.38 * (1 - d * d);
  const a = Math.hypot(x - ARBRE[0] - 1, z - ARBRE[1] - 1) / 8;
  if (a < 1) k *= 1 - 0.3 * (1 - a * a);
  return k;
}

function sol(sc, [x0, x1], [z0, z1]) {
  const herbe = (bx, bz, i, j) => {
    const h = hache(bx * 2 + i, bz * 2 + j, 3);
    return clair(h < 0.15 ? [84, 142, 48] : [100, 164, 60], (0.92 + 0.12 * hache(bx * 2 + i, bz * 2 + j, 4)) * ombre(bx + 0.5, bz + 0.5));
  };
  const l = sc.lumiere([0, 1, 0], false);
  for (let bx = x0; bx < x1; bx++) for (let bz = z0; bz < z1; bz++) {
    for (let j = 0; j < 2; j++) for (let i = 0; i < 2; i++) {
      const a = bx + i / 2, b = bz + j / 2;
      sc.quad([[a, 0, b], [a + 0.5, 0, b], [a + 0.5, 0, b + 0.5], [a, 0, b + 0.5]], clair(herbe(bx, bz, i, j), l));
    }
  }
  // les bords de l'île : l'herbe qui dépasse, la terre, puis la pierre
  const bords = [
    [[0, 0, 1], (t) => [x0 + t, z1], x1 - x0], [[0, 0, -1], (t) => [x1 - t, z0], x1 - x0],
    [[1, 0, 0], (t) => [x1, z1 - t], z1 - z0], [[-1, 0, 0], (t) => [x0, z0 + t], z1 - z0],
  ];
  for (const [n, debut, longueur] of bords) {
    const lb = sc.lumiere(n, false);
    if (lb < 0) continue;
    const dir = n[2] !== 0 ? [n[2], 0, 0] : [0, 0, -n[0]];
    for (let t = 0; t < longueur; t++) for (let by = -1; by >= -5; by--) {
      for (let j = 0; j < 4; j++) for (let i = 0; i < 4; i++) {
        const [px, pz] = debut(t);
        const p = (a, b) => [px + dir[0] * (a / 4), by + 1 - b / 4, pz + dir[2] * (a / 4)];
        const h = hache(t * 4 + i, by * 4 + j, 7 + n[0] * 3 + n[2]);
        let c = by < -3 ? [122, 122, 122] : [134, 96, 66];
        if (by === -1 && (j === 0 || (j === 1 && h < 0.5))) c = [100, 164, 60];
        sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], clair(c, lb * (0.9 + 0.16 * h)));
      }
    }
  }
}

/** Un grand arbre de la jungle : tronc de 2×2, 31 blocs de haut, branches, feuillage et lianes. */
function arbre(sc) {
  const [tx, tz] = ARBRE;
  const blocs = new Map();
  const mettre = (x, y, z, t) => { if (!blocs.has(`${x},${y},${z}`) || t === "bois") blocs.set(`${x},${y},${z}`, t); };
  const boule = (cx, cy, cz, couches) => couches.forEach((r, k) => {
    for (let x = Math.floor(cx - r); x <= cx + r; x++) for (let z = Math.floor(cz - r); z <= cz + r; z++) {
      const d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
      if (d < r && (d < r - 1 || hache(x, cy + k, z) < 0.7)) mettre(x, cy + k, z, "feuille");
    }
  });
  for (let y = 0; y < 30; y++) for (const [dx, dz] of [[0, 0], [1, 0], [0, 1], [1, 1]]) mettre(tx + dx, y, tz + dz, "bois");
  boule(tx + 1, 27, tz + 1, [5.5, 6.8, 6.5, 5.6, 4, 2.2]);
  for (const [y, sx, sz, n] of [[18, 1, 1, 5], [22, -1, -1, 5], [14, -1, 1, 4]]) {
    let x = sx > 0 ? tx + 1 : tx, z = sz > 0 ? tz + 1 : tz, yy = y;
    for (let k = 0; k < n; k++) { x += sx; if (k % 2) z += sz; if (k % 2) yy++; mettre(x, yy, z, "bois"); }
    boule(x + 0.5, yy, z + 0.5, [3.6, 3.2, 2]);
  }
  const plein = (x, y, z) => blocs.has(`${x},${y},${z}`);
  const NORMALES = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
  for (const [cle, type] of blocs) {
    const [x, y, z] = cle.split(",").map(Number);
    for (const n of NORMALES) {
      if (plein(x + n[0], y + n[1], z + n[2]) && (type === "feuille" || blocs.get(`${x + n[0]},${y + n[1]},${z + n[2]}`) === "bois")) continue;
      const l = sc.lumiere(n, false);
      if (l < 0) continue;
      const c = [x + 0.5 + n[0] * 0.5, y + 0.5 + n[1] * 0.5, z + 0.5 + n[2] * 0.5];
      const U = n[1] !== 0 ? [1, 0, 0] : n[0] !== 0 ? [0, 0, 1] : [1, 0, 0];
      const V = croix(n, U);
      for (let j = 0; j < 4; j++) for (let i = 0; i < 4; i++) {
        const h = hache(x * 4 + i, y * 4 + j, z * 7 + n[0] * 3 + n[2] * 5 + n[1] * 11);
        let col;
        if (type === "feuille") col = h < 0.25 ? [38, 92, 22] : h < 0.6 ? [56, 122, 32] : [70, 142, 40];
        else if (n[1] !== 0) col = h < 0.5 ? [150, 108, 58] : [120, 86, 44];
        else {
          col = (i + (x + z) * 2) % 3 === 0 ? [112, 86, 36] : h < 0.3 ? [62, 46, 18] : [86, 66, 26];
          // les lianes qui pendent le long du tronc
          if (y < 27 && hache(x * 4 + i, 0, z * 4 + n[0] + n[2] * 2) < 0.22 && hache(x * 4 + i, y, z) < 0.85) col = [52, 104, 24];
        }
        const p = (a, b) => ajoute(c, ajoute(fois(U, a / 4 - 0.5), fois(V, b / 4 - 0.5)));
        sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], clair(col, l));
      }
    }
  }
}

/** Le joueur (la peau de Sasuke), tout petit, debout devant la bête. */
function joueur(sc, pos, lacet) {
  const k = 1.8 / 32;
  const rot = (v) => [v[0] * Math.cos(lacet) + v[2] * Math.sin(lacet), v[1], -v[0] * Math.sin(lacet) + v[2] * Math.cos(lacet)];
  for (const partie of PARTIES(false)) for (const couche of [2, 3]) {
    for (const t of texels(partie[1], partie[couche], couche === 3 ? partie[4] : 0, 1)) {
      const i = (t.ty * SKIN.w + t.tx) * 4;
      if (SKIN.px[i + 3] < 128) continue;
      const l = sc.lumiere(rot(t.N), false);
      if (l < 0) continue;
      sc.quad(t.coins.map((v) => ajoute(fois(rot(v), k), pos)), clair([SKIN.px[i], SKIN.px[i + 1], SKIN.px[i + 2]], l));
    }
  }
}

/** Une règle graduée (rouge et blanc tous les 5 blocs), et ses chiffres tous les 10. */
function regle(sc, [x, z], hauteur, etiquettes) {
  cube(sc, [x, hauteur / 2, z], DROIT, [0.45, hauteur / 2, 0.45], (f, xx, y) => (Math.floor((y + hauteur / 2) / 5) % 2 ? [236, 236, 236] : [200, 40, 40]), 0.5);
  for (let y = 10; y <= hauteur; y += 10) {
    const [sx, sy] = sc.vue([x + 1.2, y, z]);
    etiquettes.push([String(y), sx + 4, sy - 7, 3]);
  }
}

const FOND = [208, 226, 242];
const ILE = { x: [-82, 70], z: [-66, 46] };
const JOUEUR = [8, 0, 34];

function monde(sc, { decor = true } = {}) {
  const etiquettes = [];
  const haut = hachibi(sc);
  joueur(sc, JOUEUR, Math.PI);
  if (decor) {
    sol(sc, ILE.x, ILE.z);
    arbre(sc);
    regle(sc, [62, 30], 70, etiquettes);
  }
  return { etiquettes, haut };
}

/** Écrit les étiquettes (texte, x, y, taille) sur l'image de la vue. */
function ecrire(sc, etiquettes) {
  for (const [texte, x, y, t] of etiquettes) {
    const larg = texte.length * 4 * t;
    const x0 = Math.round(Math.max(2, Math.min(sc.w - larg - 2, x))), y0 = Math.round(Math.max(2, Math.min(sc.h - 6 * t, y)));
    for (let yy = y0 - t; yy < y0 + 6 * t; yy++) for (let xx = x0 - t; xx < x0 + larg; xx++) {
      const i = (yy * sc.w + xx) * 4;
      for (let c = 0; c < 3; c++) sc.img[i + c] = sc.img[i + c] * 0.35 + 255 * 0.65;
    }
    ecrireTexte(sc.img, sc.w, texte, x0, y0, t, [30, 30, 40, 255]);
  }
}

/** La flèche qui montre le joueur, tellement il est petit. */
function fleche(sc, pos, texte, etiquettes) {
  const [x, y] = sc.vue(ajoute(pos, [0, 2.6, 0]));
  for (let k = 0; k < 14; k++) for (let dx = -k / 2; dx <= k / 2; dx++) {
    const xx = Math.round(x + dx), yy = Math.round(y - 14 + k);
    if (xx >= 0 && yy >= 0 && xx < sc.w && yy < sc.h) sc.img.set([220, 30, 30, 255], (yy * sc.w + xx) * 4);
  }
  etiquettes.push([texte, x - texte.length * 6, y - 40, 3]);
}

// ------------------------------------------------------------------ la planche
const W = 1500, M = 40;
const grande = scene({ lacet: -34, tangage: 16, echelle: 7.2, w: W, h: 900, pied: 690, fond: FOND, centre: [-8, 0, -6] });
const { etiquettes, haut } = monde(grande);
const [ax, ay] = grande.vue([ARBRE[0] + 1, 34, ARBRE[1] + 1]);
etiquettes.push(["ARBRE DE LA JUNGLE 31 BLOCS", ax - 160, ay - 30, 3]);
const [hx, hy] = grande.vue([0, haut + 3, -20]);
etiquettes.push([`HACHIBI ${Math.round(haut)} BLOCS`, hx - 110, hy - 30, 3]);
fleche(grande, JOUEUR, "TOI", etiquettes);
ecrire(grande, etiquettes);

const petites = [
  ["DE FACE", { lacet: 0, tangage: 4, echelle: 5, centre: [0, 0, 0] }],
  ["LA TETE", { lacet: -26, tangage: 6, echelle: 10, pied: 290, centre: [0, 32, 22] }],
  ["DE DOS", { lacet: 160, tangage: 14, echelle: 4.6, centre: [0, 0, -8] }],
];
const PW = 500, PH = 560;
const H = M + grande.h + M + PH;
const planche = Buffer.alloc(W * H * 4, 255);
const coller = (sc, x0, y0) => { for (let y = 0; y < sc.h; y++) sc.img.copy(planche, ((y0 + y) * W + x0) * 4, y * sc.w * 4, (y + 1) * sc.w * 4); };
coller(grande, 0, M);
ecrireTexte(planche, W, "HACHIBI GEANT  PLUS HAUT QU UN ARBRE DE LA JUNGLE", 14, 10, 4, [30, 30, 40, 255]);
petites.forEach(([titre, o], k) => {
  const sc = scene({ w: PW, h: PH, pied: 500, fond: FOND, ...o });
  hachibi(sc);
  coller(sc, k * PW, M + grande.h + M);
  ecrireTexte(planche, W, titre, k * PW + 14, M + grande.h + 10, 4, [30, 30, 40, 255]);
});
ecrirePng(path.join(ICI, "..", "planche-hachibi-geant.png"), W, H, planche);
console.log("ok", W, H, "hauteur", Math.round(haut));
