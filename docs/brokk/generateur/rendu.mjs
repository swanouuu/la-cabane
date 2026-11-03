// Maquette : la nouvelle dégaine de Brokk le Forgeron, en orque de guerre (à la place du chevalier de fer de Mowzie's),
// d'après la capture de l'utilisateur. Peau verte, grandes cornes en croissant qui partent des côtés du crâne et remontent
// vers le ciel, petits yeux rouges, gueule pleine de crocs jaunes, grosse mâchoire qui avance avec deux défenses,
// énormes épaulières carrées de cuir, col de cuir déchiqueté, collier de boules à pointes, ceinturon à boucle de fer,
// pagne, jambes vertes, grosses bottes, et un hachoir noir dans chaque main. Tout est fait de pavés texturés pixel par
// pixel, comme le modèle qu'on aura en jeu. Il fait 2,2 fois la taille d'un orque normal, pour dépasser de son enclume
// monumentale (le dessus de l'enclume est à 3 blocs du sol).
// Coordonnées du modèle en pixels (1/16 de bloc, avant le × G) : pieds à y = 0, il regarde vers +z, sa gauche vers +x.
// node rendu.mjs [capture.png] → ../planche-brokk.png (la capture, si on la donne, s'affiche à côté pour comparer)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, PARTIES, texels, clair, mix, hache, norme, croix, tourner, scene, ecrireTexte, POLICE } from "../../hachibi2/generateur/moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const SKIN = lire(path.join(ICI, "..", "..", "marque-forme2", "generateur", "skins", "sasuke.png"));
Object.assign(POLICE, { 0: "111101101101111", 7: "111001010010010", 8: "111101111101111", 9: "111101111001110", ",": "000000000010100" });

const DEG = Math.PI / 180;
const ajoute = (a, b) => a.map((v, i) => v + b[i]);
const fois = (a, k) => a.map((v) => v * k);

// ------------------------------------------------------------------ les couleurs (prises sur la capture)
const PEAU = [84, 132, 52];
const PEAU_SOMBRE = [62, 102, 38];
const PEAU_CLAIRE = [104, 154, 64];
const PEAU_TRAIT = [44, 76, 28];
const CUIR = [156, 84, 44];
const CUIR_SOMBRE = [112, 58, 30];
const CUIR_CLAIR = [180, 104, 60];
const CUIR_NOIR = [62, 36, 22];
const CORNE = [228, 204, 126];
const CORNE_OMBRE = [196, 168, 92];
const CORNE_CLAIRE = [244, 230, 170];
const FER = [140, 140, 146];
const FER_SOMBRE = [86, 86, 94];
const FER_CLAIR = [188, 188, 194];
const DENT = [232, 206, 118];
const OEIL = [222, 200, 168];
const ROUGE = [196, 34, 30];
const GUEULE = [112, 28, 24];
const GUEULE_FOND = [62, 16, 14];
const LAME = [52, 52, 60];
const LAME_CLAIRE = [92, 92, 102];

/** Un peu de grain sur chaque pixel, comme une vraie texture. */
const grain = (c, i, j, graine, force = 0.07) => clair(c, 1 - force + 2 * force * hache(i, j, graine));

// ------------------------------------------------------------------ les pavés
/**
 * Un pavé orienté : son centre, ses axes [X, Y, Z], ses demi-tailles, découpé en pixels de `pas`.
 * `peint(face, u, v, nu, nv)` donne la couleur de chaque pixel (u de gauche à droite et v de haut en bas, vus de dehors),
 * ou null pour un trou.
 */
function cube(sc, c, [X, Y, Z], [hx, hy, hz], peint, pas) {
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
      const col = peint(nom, i, nv - 1 - j, nu, nv);
      if (!col) continue;
      const p = (a, b) => ajoute(ajoute(cf, fois(U, -hu + (2 * hu * a) / nu)), fois(V, -hv + (2 * hv * b) / nv));
      sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], clair(col, l));
    }
  }
}

const rx = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
const ry = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];
const rz = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];

/**
 * Une partie du modèle (comme un ModelPart) : un pivot, une rotation (en degrés, dans l'ordre Z, Y, X de Minecraft), et
 * un parent éventuel. `boite` y pose un pavé donné par ses deux coins, dans le repère de la partie.
 */
function partie(sc, pivot, [ax, ay, az] = [0, 0, 0], parent = null) {
  const tourne = (v) => rz(ry(rx(v, ax * DEG), ay * DEG), az * DEG);
  const versMonde = parent ? (v) => parent.pt(ajoute(pivot, tourne(v))) : (v) => ajoute(pivot, tourne(v));
  const axe = parent ? (v) => parent.axe(tourne(v)) : tourne;
  return {
    pt: versMonde,
    axe,
    boite(a, b, peint) {
      const c = fois(ajoute(a, b), 0.5);
      const demi = [0, 1, 2].map((i) => Math.abs(b[i] - a[i]) / 2);
      cube(sc, fois(versMonde(c), G), [axe([1, 0, 0]), axe([0, 1, 0]), axe([0, 0, 1])], fois(demi, G), peint, G);
    },
  };
}

/** Les axes d'un pavé allongé le long de d. */
function axesLe(d) {
  const Y = norme(d);
  let X = croix(Y, [0, 0, 1]);
  if (Math.hypot(...X) < 0.2) X = croix(Y, [1, 0, 0]);
  X = norme(X);
  return [X, Y, croix(X, Y)];
}

/** Les deux coins d'un pavé posé du côté s (s = 1 : sa gauche, +x ; s = -1 : sa droite), donnés vers l'extérieur. */
const cote = (s, [a0, y0, z0], [a1, y1, z1]) => [[Math.min(s * a0, s * a1), y0, z0], [Math.max(s * a0, s * a1), y1, z1]];

// ------------------------------------------------------------------ Brokk (en pixels, × G en blocs)
const G = 2.2 / 16;

/** La jambe : le haut du pantalon de cuir au bord déchiqueté, puis le tibia vert (les bottes sont un pavé à part). */
const jambe = (graine) => (face, u, v) => {
  if (face === "haut") return grain(CUIR_SOMBRE, u, v, graine);
  if (face === "bas") return grain(CUIR_NOIR, u, v, graine);
  if (v < 5 || (v === 5 && hache(u, face.length, graine) < 0.55)) return grain(v === 4 ? CUIR_SOMBRE : CUIR, u, v, graine + v, 0.09);
  return grain(v === 6 ? PEAU_SOMBRE : PEAU, u, v, graine + 1);
};
/** Les bottes : du cuir épais, un revers clair en haut, la semelle sombre, une couture. */
const botte = (graine) => (face, u, v, nu, nv) => {
  if (face === "bas") return grain(CUIR_NOIR, u, v, graine);
  if (face === "haut") return grain(CUIR_CLAIR, u, v, graine);
  if (v === nv - 1) return grain(CUIR_NOIR, u, v, graine);
  if (v === 0) return grain(CUIR_CLAIR, u, v, graine);
  if (face === "avant" && u === Math.floor(nu / 2)) return grain(CUIR_SOMBRE, u, v, graine);
  return grain(CUIR, u, v, graine + 2, 0.09);
};

/**
 * Le torse, rangée par rangée en partant du haut (le haut du torse est à 27 px) : le col de cuir qui couvre les épaules,
 * son bord déchiqueté, la poitrine et les abdos verts, le ceinturon, puis le haut du pantalon.
 */
function torse(face, u, v, nu) {
  const mid = (nu - 1) / 2, du = Math.abs(u - mid);
  if (face === "haut") return grain(CUIR, u, v, 3, 0.09);
  if (face === "bas") return grain(CUIR_SOMBRE, u, v, 4);
  if (v <= 3) return grain(v === 3 ? CUIR_SOMBRE : CUIR, u, v, 5, 0.09);
  if (v === 4) return hache(u, face.length, 6) < 0.5 ? grain(CUIR_SOMBRE, u, v, 6) : grain(PEAU_SOMBRE, u, v, 7);
  if (v <= 10) {
    if (face === "avant") {
      // la cordelette du collier, les pectoraux, la ligne du milieu et les abdos
      if (v === 5 && (u + 1) % 3 === 0) return grain(CUIR_NOIR, u, v, 8);
      if (v === 7 && du <= 4.5) return grain(PEAU_TRAIT, u, v, 9);
      if (v >= 8 && du < 0.6) return grain(PEAU_TRAIT, u, v, 10);
      if (v === 9 && du <= 3) return grain(PEAU_SOMBRE, u, v, 11);
      return grain(v === 8 || v === 10 ? PEAU : PEAU_CLAIRE, u, v, 12);
    }
    if (face === "arriere" && du < 0.6) return grain(PEAU_SOMBRE, u, v, 13);
    return grain(PEAU, u, v, 14 + v);
  }
  if (v <= 12) return grain(v === 11 ? CUIR_SOMBRE : CUIR, u, v, 15, 0.09);
  return grain(CUIR_SOMBRE, u, v, 16);
}

/** Le pagne de cuir devant et derrière, au bas déchiqueté. */
const pagne = (face, u, v, nu, nv) => {
  if (v === nv - 1 && u % 2 === 1) return null;
  if (u === 0 || u === nu - 1) return grain(CUIR_SOMBRE, u, v, 20);
  return grain(v % 3 === 2 ? mix(CUIR, CUIR_SOMBRE, 0.35) : CUIR, u, v, 21, 0.09);
};

/** Le bras vert : l'épaule cachée sous l'épaulière, le biceps, l'avant-bras, la main plus sombre. */
const brasCouleur = (graine) => (face, u, v, nu, nv) => {
  if (face === "bas" || v >= nv - 2) return grain(face === "avant" && v === nv - 2 && u % 2 === 1 ? PEAU_TRAIT : PEAU_SOMBRE, u, v, graine);
  if (face === "avant" && v === 5) return grain(PEAU_SOMBRE, u, v, graine + 1);
  return grain(v < 2 ? PEAU_SOMBRE : PEAU, u, v, graine + 2);
};
/** Le cuir épais (épaulières, bracelet) : quelques taches sombres, un bord sombre en bas, le dessus plus clair. */
const cuirEpais = (graine) => (face, u, v, nu, nv) => {
  if (face === "haut") return grain(hache(u, v, graine) < 0.06 ? CUIR : CUIR_CLAIR, u, v, graine, 0.06);
  if (face === "bas") return grain(CUIR_SOMBRE, u, v, graine);
  if (v === nv - 1) return grain(CUIR_SOMBRE, u, v, graine + 1);
  if (hache(u, v, graine + face.length) < 0.05) return grain(mix(CUIR, CUIR_SOMBRE, 0.5), u, v, graine + 2);
  return grain(v === 0 ? CUIR_CLAIR : CUIR, u, v, graine + 3, 0.06);
};

/** Le hachoir : le manche gainé de cuir, la lame noire au dos plus clair. */
const lameCouleur = (face, u, v, nu, nv) => {
  if (face === "haut") return grain(LAME_CLAIRE, u, v, 30);
  if (face === "bas") return grain(mix(LAME, LAME_CLAIRE, 0.5), u, v, 31);
  if (face !== "gauche" && face !== "droite") return grain(LAME, u, v, 32);
  if (v === 0) return grain(LAME_CLAIRE, u, v, 33);
  return grain(hache(u, v, 34) < 0.12 ? mix(LAME, LAME_CLAIRE, 0.4) : LAME, u, v, 35, 0.05);
};
const mancheCouleur = (face, u, v) => ((u + v) % 2 ? grain(CUIR_NOIR, u, v, 36) : grain(CUIR_SOMBRE, u, v, 37));

/**
 * Le crâne, de haut en bas sur la face avant : le bord du bonnet de cuir, le front, les sourcils froncés, les petits
 * yeux rouges, les joues, puis la gueule pleine de crocs jaunes.
 */
const crane = (face, u, v, nu, nv) => {
  if (face === "haut") return grain(CUIR, u, v, 40, 0.09);
  if (face === "bas") return grain(GUEULE_FOND, u, v, 41);
  if (v === 0) return grain(CUIR_SOMBRE, u, v, 42);
  if (face !== "avant") return grain(v >= nv - 2 ? PEAU_SOMBRE : PEAU, u, v, 43);
  if (v === 3) return u === 1 || u === 2 || u === 5 || u === 6 ? grain(PEAU_TRAIT, u, v, 44) : grain(PEAU_SOMBRE, u, v, 45);
  if (v === 4) {
    if (u === 1 || u === 6) return OEIL;
    if (u === 2 || u === 5) return ROUGE;
    return grain(PEAU, u, v, 46);
  }
  if (v === 6) return u === 0 || u === nu - 1 ? grain(PEAU_SOMBRE, u, v, 47) : u % 2 === 1 ? DENT : GUEULE;
  if (v === 7) return u === 0 || u === nu - 1 ? grain(PEAU_SOMBRE, u, v, 48) : u === 1 || u === nu - 2 ? DENT : GUEULE_FOND;
  return grain(v <= 2 ? PEAU_SOMBRE : PEAU, u, v, 49);
};

function brokk(sc, { tete: aTete = 0 } = {}) {
  const racine = partie(sc, [0, 0, 0]);
  // les jambes, serrées, et les grosses bottes
  for (const s of [-1, 1]) {
    const j = partie(sc, [s * 2.75, 13, 0], [0, 0, s * 2]);
    j.boite([-2.5, -13, -2.5], [2.5, 0, 2.5], jambe(s > 0 ? 50 : 51));
    j.boite([-3, -13, -3], [3, -10, 3.5], botte(s > 0 ? 52 : 53));
  }
  // le torse, la boucle du ceinturon, les pagnes, le collier de boules à pointes
  racine.boite([-6, 13, -3.5], [6, 27, 3.5], torse);
  racine.boite([-2, 13.5, 3.5], [2, 16.5, 4.2], (f, u, v, nu, nv) => (f === "avant" && v === 1 && (u === 1 || u === 2) ? FER_SOMBRE : grain(u === 0 || v === 0 ? FER_CLAIR : FER, u, v, 22)));
  racine.boite([-2.5, 6, 3.5], [2.5, 13.5, 4], pagne);
  racine.boite([-2.5, 7, -4], [2.5, 13.5, -3.5], pagne);
  for (const [x, y] of [[-4.5, 20], [-1.5, 19.5], [1.5, 19.5], [4.5, 20]]) {
    racine.boite([x - 1, y, 3.5], [x + 1, y + 2, 4.5], (f, u, v) => grain(f === "haut" || v === 0 ? FER_CLAIR : v === 1 && u === 1 ? FER_SOMBRE : FER, u, v, 23));
    racine.boite([x - 0.3, y + 2, 3.7], [x + 0.3, y + 2.8, 4.3], () => FER_CLAIR);
  }

  // les bras, droits le long du corps, les épaulières, les bracelets et les hachoirs
  for (const s of [-1, 1]) {
    const bras = partie(sc, [s * 8.5, 25, 0], [0, 0, s * 4]);
    bras.boite([-2.5, -12, -2.5], [2.5, 2, 2.5], brasCouleur(s > 0 ? 60 : 61));
    bras.boite([-3, -8, -3], [3, -5, 3], cuirEpais(s > 0 ? 62 : 63));
    bras.boite(...cote(s, [-3, -3, -4.5], [5, 4, 4.5]), cuirEpais(s > 0 ? 64 : 65));
    // le hachoir dans la main, pointé devant lui et vers l'extérieur, à l'horizontale
    const main = partie(sc, [0, -11, 0], [0, s * 55, -s * 4], bras);
    main.boite([-0.75, -0.75, -2], [0.75, 0.75, 1.5], mancheCouleur);
    main.boite([-0.5, -2, 1.5], [0.5, 1, 11.5], lameCouleur);
  }

  // la tête, en avant entre les épaulières, comme un orque voûté
  const tete = partie(sc, [0, 24.5, 1], [aTete, 0, 0]);
  tete.boite([-4, 2, -4], [4, 10, 4], crane);
  tete.boite([-4.5, 9.5, -4.5], [4.5, 10.5, 4.5], (f, u, v) => grain(f === "haut" ? CUIR : CUIR_SOMBRE, u, v, 54, 0.09));
  // la grosse mâchoire du bas, qui avance, ses crocs, et les deux défenses
  tete.boite([-4.5, -1, -3], [4.5, 2, 5.5], (f, u, v, nu, nv) => {
    if (f === "haut") return v === nv - 1 ? (u % 2 === 0 ? DENT : GUEULE) : grain(GUEULE_FOND, u, v, 55);
    return grain(f === "avant" ? (v === 0 ? PEAU_SOMBRE : PEAU) : PEAU_SOMBRE, u, v, 56);
  });
  for (const s of [-1, 1]) {
    tete.boite(...cote(s, [3, 2, 4.4], [4.2, 4.5, 5.4]), (f, u, v, nu, nv) => (v === 0 ? CORNE_CLAIRE : v === nv - 1 ? CORNE_OMBRE : DENT));
  }
  // les cornes en croissant : elles partent des côtés du crâne, un peu vers le bas, puis remontent vers le ciel
  let sommet = 0;
  for (const s of [-1, 1]) {
    let p = [s * 3.6, 8, 0], d = norme([s, -0.4, 0]);
    const n = 11, pas = 1.75;
    for (let k = 0; k < n; k++) {
      const w = 3.9 * (1 - (k / n) * 0.62);
      const c = ajoute(p, fois(d, pas / 2));
      const [X, Y, Z] = axesLe(d).map((v) => tete.axe(v));
      const col = k >= n - 2 ? CORNE_CLAIRE : k % 4 === 3 ? CORNE_OMBRE : CORNE;
      cube(sc, fois(tete.pt(c), G), [X, Y, Z], fois([w / 2, pas * 0.68, w / 2], G), (f, u, v) => grain(col, u + k, v, 70 + k, 0.05), G * Math.max(0.7, w / 3.5));
      p = ajoute(p, fois(d, pas));
      sommet = Math.max(sommet, tete.pt(p)[1] + w / 2);
      d = norme(tourner(d, [0, 0, 1], s * 11 * DEG));
    }
  }
  return sommet * G;
}

// ------------------------------------------------------------------ la forge : le sol, l'enclume monumentale, le marteau géant
/** Un bloc de 1 × 1 × 1 (ou moins), texturé en 8 × 8 par face. */
function bloc(sc, [x0, y0, z0], [x1, y1, z1], peint) {
  cube(sc, [(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2], [[1, 0, 0], [0, 1, 0], [0, 0, 1]], [(x1 - x0) / 2, (y1 - y0) / 2, (z1 - z0) / 2], peint, 1 / 8);
}
const graineBloc = (x, y, z) => Math.round(x * 31 + y * 17 + z * 7);
const pierreNoire = (g) => (f, u, v) => {
  const brique = v % 4 === 0 || (v % 8 < 4 ? u === 0 : u === 4);
  return brique ? grain([30, 27, 32], u, v, g) : grain([52, 47, 54], u, v, g, 0.12);
};
const noirLisse = (g) => (f, u, v) => (u === 0 || v === 0 || u === 7 || v === 7 ? grain([40, 36, 42], u, v, g) : grain([58, 53, 60], u, v, g, 0.08));
const fer = (g) => (f, u, v) => (u === 0 || v === 0 || u === 7 || v === 7 ? grain([170, 170, 172], u, v, g) : grain([220, 220, 222], u, v, g, 0.05));
const basalte = (g) => (f, u, v) => (f === "haut" || f === "bas" ? grain([80, 80, 86], u, v, g) : grain(u % 3 === 0 ? [52, 52, 58] : [74, 74, 80], u, v, g, 0.1));
const enclumeCouleur = (g) => (f, u, v) => grain(f === "haut" ? [92, 92, 96] : [62, 62, 66], u, v, g, 0.12);
const chene = (g) => (f, u, v) => (f === "haut" || f === "bas" ? grain(u === 0 || v === 0 || u === 7 || v === 7 ? [60, 42, 24] : [92, 66, 38], u, v, g) : grain(u % 2 ? [70, 50, 28] : [84, 60, 34], u, v, g));

/** Une enclume de Minecraft : le pied, la taille fine, et la table allongée. */
function enclume(sc, x, y, z) {
  const g = graineBloc(x, y, z), b = (a, c) => bloc(sc, ajoute([x, y, z], a), ajoute([x, y, z], c), enclumeCouleur(g));
  b([-0.375, 0, -0.375], [0.375, 0.25, 0.375]);
  b([-0.25, 0.25, -0.3125], [0.25, 0.3125, 0.3125]);
  b([-0.125, 0.3125, -0.25], [0.125, 0.625, 0.25]);
  b([-0.5, 0.625, -0.3125], [0.5, 1, 0.3125]);
}

/**
 * Le coin de la forge où il travaille, comme dans HubBuilder.forge() : Brokk regarde l'entrée, son enclume monumentale
 * devant lui (un socle de 5 sur 3, puis 3 sur 3, puis deux enclumes et un bloc de fer), le marteau géant debout à sa gauche.
 */
const ARENE = { x: [-7, 7], z: [-5, 9] };
function forge(sc) {
  for (let x = ARENE.x[0]; x < ARENE.x[1]; x++) for (let z = ARENE.z[0]; z < ARENE.z[1]; z++) {
    const magma = hache(x, z, 90) < 0.05 && Math.abs(x) > 3;
    bloc(sc, [x, -1, z], [x + 1, 0, z + 1], magma ? (f, u, v) => grain(hache(u, v, x * 9 + z) < 0.3 ? [120, 40, 16] : [220, 110, 30], u, v, 91) : pierreNoire(graineBloc(x, 0, z)));
  }
  for (let x = -2; x <= 2; x++) for (let z = 1; z <= 3; z++) {
    if (Math.abs(x) === 2) {
      // les marches, qui montent vers le milieu
      bloc(sc, [x - 0.5, 0, z - 0.5], [x + 0.5, 0.5, z + 0.5], noirLisse(graineBloc(x, 0, z)));
      bloc(sc, [x > 0 ? x - 0.5 : x, 0.5, z - 0.5], [x > 0 ? x : x + 0.5, 1, z + 0.5], noirLisse(graineBloc(x, 1, z)));
      continue;
    }
    bloc(sc, [x - 0.5, 0, z - 0.5], [x + 0.5, 1, z + 0.5], noirLisse(graineBloc(x, 0, z)));
    bloc(sc, [x - 0.5, 1, z - 0.5], [x + 0.5, 2, z + 0.5], z === 2 ? fer(graineBloc(x, 1, z)) : basalte(graineBloc(x, 1, z)));
  }
  enclume(sc, -1, 2, 2);
  enclume(sc, 1, 2, 2);
  bloc(sc, [-0.5, 2, 1.5], [0.5, 3, 2.5], fer(5));
  for (let y = 0; y < 4; y++) bloc(sc, [2.5, y, -0.5], [3.5, y + 1, 0.5], chene(graineBloc(3, y, 0)));
  for (let x = 2; x <= 4; x++) bloc(sc, [x - 0.5, 4, -0.5], [x + 0.5, 5, 0.5], fer(graineBloc(x, 4, 0)));
}

/** Le joueur (la peau de Sasuke), devant l'enclume. */
function joueur(sc, pos, lacet) {
  const k = 1.8 / 32;
  const rot = (v) => [v[0] * Math.cos(lacet) + v[2] * Math.sin(lacet), v[1], -v[0] * Math.sin(lacet) + v[2] * Math.cos(lacet)];
  for (const p of PARTIES(false)) for (const couche of [2, 3]) {
    for (const t of texels(p[1], p[couche], couche === 3 ? p[4] : 0, 1)) {
      const i = (t.ty * SKIN.w + t.tx) * 4;
      if (SKIN.px[i + 3] < 128) continue;
      const l = sc.lumiere(rot(t.N), false);
      if (l < 0) continue;
      sc.quad(t.coins.map((v) => ajoute(fois(rot(v), k), pos)), clair([SKIN.px[i], SKIN.px[i + 1], SKIN.px[i + 2]], l));
    }
  }
}

/** Écrit les étiquettes (texte, x, y, taille) sur l'image de la vue, sur un fond clair. */
function ecrire(sc, etiquettes) {
  for (const [texte, x, y, t] of etiquettes) {
    const larg = texte.length * 4 * t;
    const x0 = Math.round(Math.max(2, Math.min(sc.w - larg - 2, x))), y0 = Math.round(Math.max(2, Math.min(sc.h - 6 * t, y)));
    for (let yy = y0 - t; yy < y0 + 6 * t; yy++) for (let xx = x0 - t; xx < x0 + larg; xx++) {
      const i = (yy * sc.w + xx) * 4;
      for (let c = 0; c < 3; c++) sc.img[i + c] = sc.img[i + c] * 0.25 + 255 * 0.75;
    }
    ecrireTexte(sc.img, sc.w, texte, x0, y0, t, [30, 30, 40, 255]);
  }
}

// ------------------------------------------------------------------ la planche
const W = 1500, M = 40;
const JOUEUR = [-4.6, 0, 4.2];
const grande = scene({ lacet: 32, tangage: 15, echelle: 84, w: W, h: 860, pied: 730, fond: [74, 54, 46], centre: [-0.6, 0, 1.4] });
const haut = brokk(grande);
forge(grande);
joueur(grande, JOUEUR, Math.PI - 0.9);
const etiquettes = [];
const [bx, by] = grande.vue([0, haut + 0.2, 0]);
etiquettes.push([`BROKK  ${Math.round(haut)} BLOCS AVEC LES CORNES`, bx - 230, by - 30, 3]);
const [jx, jy] = grande.vue(ajoute(JOUEUR, [0, 2.1, 0]));
etiquettes.push(["TOI", jx - 18, jy - 26, 3]);
const [ex, ey] = grande.vue([0, 0.4, 3.6]);
etiquettes.push(["SON ENCLUME", ex - 70, ey + 40, 3]);
ecrire(grande, etiquettes);

const capture = process.argv[2] && fs.existsSync(process.argv[2]) ? lire(process.argv[2]) : null;
const petites = [
  ["DE FACE", { lacet: -20, tangage: 2, echelle: 80, pied: 575, centre: [0, 0, 0] }],
  ["LA TETE", { lacet: -26, tangage: 4, echelle: 150, pied: 330, centre: [0, 4.3, 0.6] }],
  ["DE DOS", { lacet: 155, tangage: 8, echelle: 80, pied: 575, centre: [0, 0, 0] }],
];
const PW = capture ? 375 : 500, PH = 600;
const H = M + grande.h + M + PH;
const planche = Buffer.alloc(W * H * 4, 255);
const coller = (img, w, h, x0, y0) => { for (let y = 0; y < h; y++) img.copy(planche, ((y0 + y) * W + x0) * 4, y * w * 4, (y + 1) * w * 4); };
coller(grande.img, grande.w, grande.h, 0, M);
ecrireTexte(planche, W, "BROKK LE FORGERON  LA NOUVELLE DEGAINE", 14, 10, 4, [30, 30, 40, 255]);
let x0 = 0;
if (capture) {
  // la capture, agrandie, au milieu de sa case
  const k = Math.min(Math.floor((PW - 20) / capture.w), Math.floor((PH - 30) / capture.h));
  const w = capture.w * k, h = capture.h * k, img = Buffer.alloc(PW * PH * 4, 255);
  const ox = Math.floor((PW - w) / 2), oy = 20;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const s = (Math.floor(y / k) * capture.w + Math.floor(x / k)) * 4;
    img.set([capture.px[s], capture.px[s + 1], capture.px[s + 2], 255], ((oy + y) * PW + ox + x) * 4);
  }
  coller(img, PW, PH, 0, M + grande.h + M);
  ecrireTexte(planche, W, "TON SCREEN", 14, M + grande.h + 10, 4, [30, 30, 40, 255]);
  x0 = PW;
}
petites.forEach(([titre, o], k) => {
  const sc = scene({ w: PW, h: PH, fond: [226, 224, 230], ...o });
  brokk(sc);
  coller(sc.img, sc.w, sc.h, x0 + k * PW, M + grande.h + M);
  ecrireTexte(planche, W, titre, x0 + k * PW + 14, M + grande.h + 10, 4, [30, 30, 40, 255]);
});
ecrirePng(path.join(ICI, "..", "planche-brokk.png"), W, H, planche);
console.log("ok", W, H, "hauteur", haut.toFixed(2), "blocs");
