// Maquettes d'un nouveau fruit du démon (demandé le 2026-11-15) : le Tori Tori no Mi, modèle Phénix. Trois niveaux comme
// la lave et la glace, et les mêmes touches. Sa spécialité : les flammes bleues qui soignent (lui et ses alliés) ; en
// échange il frappe un peu moins fort que la lave. D'après les images envoyées : un fruit en flammes cyan aux traînées
// jaunes avec une flamme orange en guise de tige ; des ailes cyan tachées de jaune, une patte en serre, des chaînes de
// perles dorées ; le phénix entier a une crête de feu orange.
// node phenix.mjs → planche-phenix.png (le fruit, les niveaux 1 et 2) et planche-phenix3.png (le niveau 3) ;
// node phenix.mjs <n> [fichier.png] → la vignette n seule (dans l'ordre des deux planches)
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, mix, hache, norme, croix, scene, ecrireTexte, lire, lum } from "./moteur.mjs";
import { ajoute, sous, fois, TOUR, ID, brille, pave, cube, pointe, perso, sol, legende, regarde, bruit, couvert, brule, FOND, SKINS, STEVE } from "./rendu.mjs";
import { cadre, boite, couvert3, courbe } from "./niveau3.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");

// ------------------------------------------------------------------ les couleurs
const CYAN = [72, 214, 240], CYAN_C = [176, 248, 252], CYAN_F = [26, 146, 212], CYAN_N = [14, 92, 160];
const JAUNE = [255, 226, 96], OR = [255, 176, 40], ORANGE_P = [255, 128, 26], PERLE = [255, 248, 200];
const GRIFFE = [226, 222, 242], BOIS = [120, 84, 52];
const PEAU = SKINS.efe, ALLIE = SKINS.kai;
const lisse = (t) => { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); };

// ------------------------------------------------------------------ les briques
/** Une langue de flamme bleue : claire à la base, cyan, puis bleu profond au bout. */
const flammeB = (sc, b, dir, L, l) => pointe(sc, b, dir, L, l, (t) => (t < 0.4 ? mix(CYAN_C, CYAN, t / 0.4) : mix(CYAN, CYAN_F, (t - 0.4) / 0.6)), true, true);
/** Une flamme dorée (la crête, la tige du fruit, les taches jaunes). */
const flammeJ = (sc, b, dir, L, l) => pointe(sc, b, dir, L, l, (t) => mix(JAUNE, ORANGE_P, t * 0.9), true, true);

/** Un dégradé à plusieurs arrêts (de 0 à 1). */
function degrade(stops, t) {
  t = Math.max(0, Math.min(1, t)) * (stops.length - 1);
  const i = Math.min(stops.length - 2, Math.floor(t));
  return mix(stops[i], stops[i + 1], t - i);
}
const PLUME_A = [70, 206, 236], PLUME_B = [140, 236, 250];
/** Les couleurs des veines de flamme : cyan presque blanc, cyan, or, orange. */
const VEINES = [[255, 255, 255], [255, 244, 170], JAUNE, ORANGE_P];

/**
 * La peau du phénix (niveaux 2 et 3), comme la tenue de Ténèbres : un plumage bleu nuit, et par-dessus des veines de
 * flammes qui brillent, de plusieurs couleurs (cyan blanc au cœur, cyan, or, orange), et quelques taches de feu.
 */
function plumesT(i, j, k) {
  const v = Math.abs(bruit(i * 0.28 + k * 7, j * 0.28, 950 + k));
  const g = bruit(i * 0.22 + 3, j * 0.22, 960 + k);
  if (v < 0.11) {
    const c = degrade(VEINES, (g + 0.6) / 1.2);
    return brille(v < 0.04 ? mix(c, [255, 255, 255], 0.55) : c);
  }
  const tache = bruit(i * 0.35 + k * 5, j * 0.35, 970 + k);
  if (tache > 0.62) return brille(tache > 0.72 ? [255, 250, 200] : JAUNE);
  if (tache > 0.55) return brille(OR);
  const rang = Math.floor(j / 3), dec = (rang % 2) * 2, cel = Math.floor((i + dec) / 4);
  const bord = j % 3 === 2 ? (i + dec) % 4 !== 3 : (i + dec) % 4 === 3;
  const c = mix(PLUME_A, PLUME_B, hache(cel, rang, 902 + k));
  // près d'une veine, le plumage s'éclaire un peu (la lueur qui déborde)
  const pres = v < 0.2 ? (0.2 - v) / 0.2 : 0;
  return brille(mix(bord ? mix(c, CYAN_F, 0.35) : c, [255, 250, 210], pres * 0.35));
}

/** Les yeux qui brillent (H : la tête) ; cotes : -1 son œil droit, +1 son œil gauche. */
function yeux(sc, H, cotes = [-1, 1]) {
  for (const s of cotes) {
    const x0 = s < 0 ? -3 : 1;
    sc.quad([H.p([x0, 28, 4.08]), H.p([x0 + 2, 28, 4.08]), H.p([x0 + 2, 27, 4.08]), H.p([x0, 27, 4.08])], [226, 254, 255], 0.2, true);
  }
}

/**
 * Une plume plate de b vers d (n : la normale de son plan), longue de L, large de w, au bout arrondi : un dégradé du
 * bleu profond au cyan clair (o.stops pour d'autres), parfois une tache de flamme dorée. Elle brille (elle est en feu).
 */
function plume(sc, b, d, n, L, w, k, o = {}) {
  d = norme(d);
  const e = norme(croix(d, n)), N = 7, stops = o.stops ?? [CYAN_N, CYAN_F, CYAN, CYAN_C];
  const large = (t) => w * (t < 0.15 ? 0.6 + 2.67 * t : t > 0.86 ? 0.95 * Math.sqrt(Math.max(0, (1 - t) / 0.14)) : 1 - 0.3 * ((t - 0.15) / 0.71) ** 2);
  const jaune = hache(k, 7, 903) < (o.jaune ?? 0.2);
  const ecart = fois(n, 0.06);
  for (let j = 0; j < N; j++) {
    const t0 = j / N, t1 = (j + 1) / N, w0 = large(t0) / 2, w1 = large(t1) / 2;
    const p0 = ajoute(b, fois(d, L * t0)), p1 = ajoute(b, fois(d, L * t1));
    const c = degrade(stops, (t0 + t1) / 2);
    // deux moitiés : un côté un peu plus sombre (le relief de la plume)
    sc.quad([sous(p0, fois(e, w0)), p0, p1, sous(p1, fois(e, w1))], c.map((v) => v * 0.84), 0, true);
    sc.quad([p0, ajoute(p0, fois(e, w0)), ajoute(p1, fois(e, w1)), p1], c, 0, true);
    if (jaune && j >= 2 && j <= 4) {
      const cj = mix(JAUNE, ORANGE_P, (j - 2) / 3), a0 = w0 * 0.55, a1 = w1 * 0.55, q0 = ajoute(p0, ecart), q1 = ajoute(p1, ecart);
      sc.quad([sous(q0, fois(e, a0)), ajoute(q0, fois(e, a0)), ajoute(q1, fois(e, a1)), sous(q1, fois(e, a1))], cj, 0.1, true);
    }
  }
}

/**
 * Une aile de flammes, peinte comme dans l'animé : une grande surface lisse, quelques grandes plumes arrondies au
 * bord de fuite, un dégradé cyan clair (près de l'os) vers cyan profond, des taches de flamme jaune bordées d'orange,
 * et de grandes langues de feu sur le bord d'attaque. P : le repère où elle s'attache (P.p, P.n en coordonnées
 * locales) ; s = +1 ou -1 (le côté) ; W : l'envergure. Par défaut une aile de joueur (les plumes pendent) ; o.dir(t)
 * change le sens des plumes et o.normale le plan de l'aile (l'oiseau : ses plumes partent en arrière).
 */
function aile(sc, P, s, W, o = {}) {
  const leve = o.leve ?? 0.9, cle = s > 0 ? 0 : 37, nP = o.plumes ?? 7;
  const os = (u) => [s * W * u * 0.95, W * leve * u + W * 0.16 * Math.sin(Math.PI * u), -W * 0.08 * u];
  const dir = o.dir ?? ((u) => [s * (0.1 + 0.6 * u), -1, -0.18]);
  const longueur = o.longueur ?? ((u) => W * (0.42 + 0.5 * u ** 1.2));
  const normale = P.n(o.normale ?? [0, 0, 1]);
  // le bord de fuite : une grande plume arrondie par festons ; le bout de l'aile finit en pointe
  const bord = (u) => {
    const f = (u * nP) % 1;
    return Math.min(1, 0.8 + 0.2 * Math.sin(Math.PI * f) ** 0.6) * (u > 0.92 ? 1 - (u - 0.92) * 4 : 1);
  };
  const point = (u, v) => ajoute(P.p(os(u)), fois(P.n(dir(u)), longueur(u) * v));
  const couleur = (u, v) => {
    // les flammes jaunes : des taches qui suivent l'aile (cœur jaune, bord orange)
    const n = bruit(u * 20 + cle, v * 8, 980 + cle);
    if (n > 0.32) return mix(JAUNE, [255, 250, 200], (n - 0.32) * 1.4);
    if (n > 0.2) return mix(OR, ORANGE_P, 0.3);
    let c = degrade([[214, 252, 255], CYAN_C, CYAN, CYAN_F], v * 0.9 + 0.1 * u);
    // les grandes plumes : un trait plus sombre entre elles, et une ligne entre le rang du haut et celui du bas
    const f = (u * nP) % 1;
    if (v > 0.42 && (f < 0.05 || f > 0.97)) c = mix(c, CYAN_N, 0.45);
    if (Math.abs(v - 0.42 - 0.04 * Math.sin(u * 40)) < 0.025) c = mix(c, CYAN_F, 0.4);
    // le bout de chaque plume s'éclaircit (il flambe)
    if (v > bord(u) - 0.07) c = mix(c, CYAN_C, 0.6);
    return c;
  };
  const NU = Math.max(24, Math.round(W * 1.1)), NV = 16;
  for (let a = 0; a < NU; a++) for (let b = 0; b < NV; b++) {
    const u0 = a / NU, u1 = (a + 1) / NU, v0 = b / NV, v1 = (b + 1) / NV, um = (u0 + u1) / 2, vm = (v0 + v1) / 2;
    if (vm > bord(um)) continue;
    sc.quad([point(u0, v0), point(u1, v0), point(u1, v1), point(u0, v1)], couleur(um, vm), 0, true);
  }
  // les langues de feu du bord d'attaque, qui se courbent vers l'extérieur et vers le haut
  for (let q = 0; q < 9; q++) {
    const u = (q + 0.5) / 9, h = hache(q, 1, 981 + cle);
    const b = ajoute(P.p(os(u)), fois(normale, 0.3));
    flammeB(sc, b, P.n([s * (0.4 + 0.5 * u), 1, -0.1]), W * (0.1 + 0.12 * h), W * 0.05 + 1.4);
    if (h > 0.5) flammeJ(sc, ajoute(b, P.n([0, 0.5, 0])), P.n([s * 0.6, 1, 0]), W * 0.06, W * 0.025 + 1);
  }
  // quelques flammèches au bout des grandes plumes
  for (let q = 0; q < nP; q++) {
    const u = (q + 0.5) / nP;
    if (u > 0.95) continue;
    flammeB(sc, point(u, bord(u) * 0.98), P.n(dir(u)), W * (0.07 + 0.06 * hache(q, 2, 982 + cle)), W * 0.04 + 1.2);
  }
}

/** Les deux ailes dans le dos d'un joueur. */
function ailesDos(sc, T, W, o = {}) {
  for (const s of [-1, 1]) {
    const P = { p: (v) => T.corps.p(ajoute([s * 1.4, 21.4, -2.5], v)), n: (v) => T.corps.n(v) };
    aile(sc, P, s, W, o);
  }
}

/** Une serre de rapace à la place du pied (J : la jambe, cote "D" ou "G") : des anneaux d'écailles, trois doigts devant, un derrière. */
function serre(sc, J, cote, griffe = 3.2) {
  const cx = cote === "D" ? -2 : 2;
  const ecaille = (f, u, v, i, j) => (j % 2 ? mix(CYAN, CYAN_F, 0.4) : mix(CYAN, CYAN_C, 0.35 * hache(i, j, 907)));
  for (const y of [3, 6.2]) pave(sc, [cx - 2.35, y, -2.35], [cx + 2.35, y + 1.1, 2.35], J, () => CYAN_F, 0.5, 0.02, true);
  pave(sc, [cx - 2.4, 0, -1.8], [cx + 2.4, 2.2, 2.4], J, ecaille, 0.5, 0.03, true);
  const doigts = [[-1.6, -0.3], [0, 0], [1.6, 0.3]].map(([dx, a]) => [cadre(J, [cx + dx, 1.1, 2.2], [a, -0.08, 1]), 2.4]);
  doigts.push([cadre(J, [cx, 1.2, -1.6], [0, -0.05, -1]), 1.4]);
  for (const [F, l] of doigts) {
    boite(sc, F, [0, 0, l], [0.8, 0.8, l], ecaille, 0.5);
    pointe(sc, F.p([0, 0.1, 2 * l]), F.n([0, -0.7, 1]), griffe, 1.4, (t) => mix([196, 190, 220], [252, 252, 255], t), true, false);
  }
}

/** La crête de feu sur la tête (H : le repère de la tête). */
function crete(sc, H, n = 9, k = 1) {
  for (let q = 0; q < n; q++) {
    const h = hache(q, 1, 908), x = (q / (n - 1) - 0.5) * 5;
    flammeJ(sc, H.p([x, 32, -0.5 - 2.5 * h]), H.n([x * 0.08, 1, -0.55 - 0.4 * h]), (5 + 9 * hache(q, 2, 908) * (1 - Math.abs(x) / 4)) * k, 2.2 * k);
  }
  for (const s of [-1, 1]) for (let q = 0; q < 3; q++) flammeB(sc, H.p([s * 4.2, 27 + q * 1.5, -1]), H.n([s, 0.6, -0.6]), 3 + 2 * q, 1.6);
}

/** Une chaîne de perles dorées le long des points (le soin du phénix). */
function chaine(sc, pts, k = 0, s = 1) {
  pts.forEach((p, i) => cube(sc, p, (i % 2 ? 1.6 : 1.05) * s, [i * 0.5 + k, i * 0.8], brille(i % 2 ? PERLE : JAUNE), true));
}

/** Une hélice de perles autour de c (rayon r, de h0 à h1). */
function helice(c, r, h0, h1, tours, pas = 1.6) {
  const L = Math.hypot(TOUR * r * tours, h1 - h0), n = Math.round(L / pas), pts = [];
  for (let i = 0; i <= n; i++) {
    const t = i / n, a = t * TOUR * tours;
    pts.push([c[0] + Math.cos(a) * r, h0 + (h1 - h0) * t, c[2] + Math.sin(a) * r]);
  }
  return pts;
}

/** Brûler en bleu : des flammes bleues sur tout le corps. */
function bruleB(sc, T, n = 14) {
  for (let q = 0; q < n; q++) {
    const devant = q % 2 === 0, cote = hache(q, 9, 909) < 0.5 ? -1 : 1;
    const loc = devant ? [(hache(q, 1, 909) - 0.5) * 14, 0.5 + 27 * hache(q, 2, 909), cote * 2.6] : [cote * 8.6, 0.5 + 20 * hache(q, 2, 909), (hache(q, 3, 909) - 0.5) * 4];
    flammeB(sc, T.corps.p(loc), [0.25 * (hache(q, 4, 909) - 0.5), 1, 0.25 * (hache(q, 5, 909) - 0.5)], 3 + 3.5 * hache(q, 6, 909), 1.8 + 1.4 * hache(q, 7, 909));
  }
}

/** Un allié qui se soigne : une hélice de perles qui monte autour de lui, quelques flammes bleues aux pieds. */
function soigne(sc, p, k = 0) {
  chaine(sc, helice(p, 9, 1, 34, 1.6), k);
  for (let q = 0; q < 6; q++) {
    const a = (q / 6) * TOUR + k;
    flammeB(sc, [p[0] + Math.cos(a) * 6, 0.2, p[2] + Math.sin(a) * 6], [0, 1, 0], 4 + 4 * hache(q, k, 910), 2.2);
  }
}

/** Une cible : un Steve tourné vers v, brûlé en bleu si o.feu. */
function cible(sc, p, o = {}) {
  const T = perso(sc, o.peau ?? STEVE, { decale: p, lacet: regarde(p, o.vers ?? [0, 0, 0]), brasD: [-0.4, -0.3], brasG: [-0.4, 0.3], ...o.pose });
  if (o.feu) bruleB(sc, T, o.feu);
  return T;
}

const VOLE = { penche: -0.9, brasD: [-2.6, -0.4], brasG: [-2.6, 0.4], jambeD: 0.4, jambeG: -0.3 };

// ------------------------------------------------------------------ le phénix, niveau par niveau
/** Niveau 1 : l'avant-bras droit et la main en plumes, et une petite aile de flammes qui pend du bras. */
function couvert1(m, s, cote, nom, t) {
  if (nom !== "brasD") return 0;
  const [x, y, z] = t.centre, bord = 17.5 + 1.6 * Math.sin(z * 1.1 + x * 0.8) + 1.4 * bruit(z + x, y, 911);
  if (y < bord) return 1;
  return y < 22 && Math.abs(bruit(y * 1.3 + z, x * 1.3 - z, 912)) < 0.06 ? 2 : 0;
}

const MOITIE = (couv) => ({ sorte: "phenix", couvert: couv, texture: plumesT });

function phenix1(sc, o = {}) {
  const T = perso(sc, PEAU, { moitie: MOITIE(couvert1), brasD: [-0.5, -0.25], brasG: [0.08, 0.05], tete: [0.05, 0, 0.1], ...o });
  const B = T.brasD;
  // la petite aile : des plumes qui pendent sous l'avant-bras, vers l'arrière
  for (let q = 0; q < 9; q++) {
    const y = 11.5 + q * 1.3;
    plume(sc, B.p([-8.2, y, -1.6]), B.n([-0.45, -0.35 - q * 0.04, -1]), B.n([1, 0, 0]), 10 + (8 - q) * 1.2, 3.2, q + 300, { jaune: 0.4 });
    plume(sc, B.p([-8.4, y, -0.6]), B.n([-0.45, -0.2, -1]), B.n([1, 0, 0]), 5 + (8 - q) * 0.6, 2.6, q + 330, { base: CYAN, bout: CYAN_C });
  }
  for (let q = 0; q < 7; q++) flammeB(sc, B.p([-6 + (hache(q, 1, 913) - 0.5) * 4, 12 + 6 * hache(q, 2, 913), (hache(q, 3, 913) - 0.5) * 4]), B.n([0, 1, 0]), 2.4 + 3 * hache(q, 4, 913), 1.3);
  return Object.assign(T, { main: B.p([-6, 11.4, 0]), dir: B.n([0, -1, 0]) });
}

function phenix2(sc, o = {}) {
  const T = perso(sc, PEAU, { moitie: MOITIE(couvert), brasD: [-0.45, -0.2], brasG: [0.1, 0.06], tete: [0.05, 0, 0.12], ...o });
  ailesDos(sc, T, o.ailes ?? 24, o.aile ?? {});
  serre(sc, T.jambeD, "D");
  yeux(sc, T.tete, [-1]);
  for (let q = 0; q < 8; q++) flammeB(sc, T.corps.p([-4.2, 13 + 10 * hache(q, 1, 914), (hache(q, 2, 914) - 0.5) * 4]), [0, 1, 0], 2.4 + 3 * hache(q, 3, 914), 1.4);
  return Object.assign(T, { main: T.brasD.p([-6, 11.4, 0]), dir: T.brasD.n([0, -1, 0]) });
}

function phenix3(sc, o = {}) {
  const T = perso(sc, PEAU, { moitie: MOITIE(couvert3), brasD: [-0.4, -0.25], brasG: [-0.2, 0.2], tete: [0.05, 0, 0.1], ...o });
  ailesDos(sc, T, o.ailes ?? 40, { leve: 0.6, ...o.aile });
  serre(sc, T.jambeD, "D", 3.8);
  serre(sc, T.jambeG, "G", 3.8);
  crete(sc, T.tete);
  yeux(sc, T.tete);
  if (o.perles !== false) {
    const c = T.corps.p([0, 0, 0]);
    chaine(sc, helice([c[0], 0, c[2]], 13, 0.5, 9, 1.2), 3);
  }
  return Object.assign(T, { main: T.brasD.p([-6, 11.4, 0]), dir: T.brasD.n([0, -1, 0]) });
}

/**
 * Le phénix entier (le grand phénix du clic droit maintenu, l'ulti) : F, son repère (avant = où il vole, haut), k sa
 * taille. Un corps de plumes, une tête à bec doré et crête de feu, deux grandes ailes, une longue queue et des perles.
 */
function oiseau(sc, F, k, o = {}) {
  const peau = (n) => (f, u, v, i, j) => {
    const b = bruit(i * 0.5 + n * 3, j * 0.5, 990);
    if (b > 0.45) return brille(mix(JAUNE, OR, hache(i, j, 991) * 0.5));
    return brille(degrade([CYAN_C, CYAN, CYAN_F], f === "bas" ? 0.9 : f === "haut" ? 0.15 : v * 0.8 + 0.1));
  };
  // le corps : des anneaux de plumes, gros au poitrail, fins vers la queue
  for (let z = -12; z <= 6; z += 1.5) {
    const r = k * (z > 0 ? 4.6 - z * 0.35 : 4.6 + z * 0.22);
    if (r < 1) continue;
    boite(sc, F, [0, 0, z * k], [r, r * 0.86, k * 0.8], peau(Math.round(z * 2) + 40), (o.pas ?? Math.max(0.5, k * 0.6)));
  }
  // le cou et la tête
  const tete = [0, 4.2 * k, 9.5 * k];
  boite(sc, F, [0, 2.4 * k, 7.5 * k], [2.2 * k, 2.2 * k, 1.8 * k], peau(60), (o.pas ?? Math.max(0.5, k * 0.6)));
  boite(sc, F, tete, [2.4 * k, 2.3 * k, 2.4 * k], peau(61), (o.pas ?? Math.max(0.5, k * 0.6)));
  pointe(sc, F.p(ajoute(tete, [0, -0.4 * k, 2.2 * k])), F.n([0, -0.25, 1]), 3.2 * k, 1.8 * k, (t) => mix(OR, JAUNE, t), false, true);
  for (const s of [-1, 1]) cube(sc, F.p(ajoute(tete, [s * 2.3 * k, 0.5 * k, 0.9 * k])), 0.9 * k, [0, 0], brille([255, 255, 255]));
  for (let q = 0; q < 7; q++) {
    const h = hache(q, 1, 915);
    flammeJ(sc, F.p(ajoute(tete, [(q / 6 - 0.5) * 2.4 * k, 2 * k, -0.5 * k])), F.n([(q / 6 - 0.5) * 0.6, 1, -0.7 - 0.5 * h]), (5 + 7 * h) * k, 1.6 * k);
  }
  // les ailes : les plumes partent vers l'arrière
  const W = (o.envergure ?? 24) * k, leve = o.leve ?? 0.45;
  for (const s of [-1, 1]) {
    const P = { p: (v) => F.p(ajoute([s * 3 * k, 1.6 * k, 1.5 * k], v)), n: (v) => F.n(v) };
    aile(sc, P, s, W, { leve, normale: [0, 1, 0], dir: (t) => [s * 0.7 * t, -0.1, -1], longueur: (t) => W * (0.42 + 0.42 * t) });
  }
  // la queue : de longues plumes, et deux chaînes de perles qui traînent
  for (let q = 0; q < 7; q++) {
    const a = (q - 3) * 0.14;
    plume(sc, F.p([0, 0, -12 * k]), F.n([a, -0.12 - 0.05 * Math.abs(q - 3), -1]), F.n([0, 1, 0]), (26 + 10 * hache(q, 1, 916) - 2 * Math.abs(q - 3)) * k, 4.4 * k, q + 400, { jaune: 0.5 });
  }
  for (const s of [-1, 1]) {
    const c = courbe([[s * 2 * k, -1 * k, -10 * k], [s * 6 * k, -4 * k, -24 * k], [s * 2 * k, -8 * k, -38 * k], [s * 7 * k, -6 * k, -52 * k]].map((v) => F.p(v)), 1.8 * k);
    chaine(sc, c.pts, s, k * 0.9);
  }
  // les serres, sous le ventre
  for (const s of [-1, 1]) {
    const G = cadre(F, [s * 1.8 * k, -3.5 * k, 1 * k], [0, -0.6, 1]);
    boite(sc, G, [0, 0, 1.4 * k], [0.7 * k, 0.7 * k, 1.4 * k], () => CYAN_F, Math.max(0.5, k * 0.5));
    for (const a of [-0.4, 0, 0.4]) pointe(sc, G.p([a * 2 * k, 0, 2.8 * k]), G.n([a, -0.6, 1]), 2.4 * k, 0.9 * k, (t) => mix([196, 190, 220], [252, 252, 255], t), true, false);
  }
}

// ------------------------------------------------------------------ la planche 1 : le fruit, les niveaux 1 et 2
/** Le fruit : un œuf de flammes cyan aux traînées jaunes, une flamme orange pour tige, posé sur une table. */
function fruit(sc) {
  pave(sc, [-14, -4, -12], [14, 0, 12], ID, (f, u, v, i, j) => mix(BOIS, [150, 110, 70], 0.4 * hache(i, j >> 2, 917) + (i % 6 === 0 ? -0.3 : 0)), 1);
  for (let x = -7; x <= 7; x++) for (let y = 0; y <= 18; y++) for (let z = -7; z <= 7; z++) {
    const dans = (a, b, c) => (a / 6.4) ** 2 + ((b - 8.5) / (b < 8.5 ? 8.5 : 9.5)) ** 2 + (c / 6.4) ** 2 <= 1;
    if (!dans(x, y, z) || (dans(x + 1, y, z) && dans(x - 1, y, z) && dans(x, y + 1, z) && dans(x, y - 1, z) && dans(x, y, z + 1) && dans(x, y, z - 1))) continue;
    const a = Math.atan2(z, x), tourbillon = Math.sin(a * 5 + y * 0.55);
    const jaune = bruit(a * 6 + y * 0.4, y * 0.9, 918) > 0.5;
    const c = jaune ? brille(mix(JAUNE, OR, hache(x, y, 919))) : mix(tourbillon > 0 ? CYAN : CYAN_F, CYAN_C, 0.3 * hache(x + 9, y, z));
    cube(sc, [x, y + 0.5, z], 1.06, [0, 0], c.length === 4 ? c.slice(0, 3) : c, jaune);
  }
  // les languettes de flamme sur toute la surface
  for (let q = 0; q < 46; q++) {
    const a = hache(q, 1, 920) * TOUR, y = 1 + 15 * hache(q, 2, 920), r = 6.4 * Math.sqrt(Math.max(0.05, 1 - ((y - 8.5) / 9) ** 2));
    const n = norme([Math.cos(a), 0.9, Math.sin(a)]);
    pointe(sc, [Math.cos(a) * r, y, Math.sin(a) * r], n, 2 + 2.8 * hache(q, 3, 920), 1.6, (t) => mix(CYAN, CYAN_C, t), true, true);
  }
  // la tige : une flamme orange
  for (let q = 0; q < 9; q++) {
    const h = hache(q, 1, 921);
    flammeJ(sc, [(h - 0.5) * 3, 17, (hache(q, 2, 921) - 0.5) * 3], [(h - 0.5) * 0.7, 1, (hache(q, 3, 921) - 0.5) * 0.5], 6 + 7 * hache(q, 4, 921), 2.4);
  }
  sc.lueur(CYAN, 6, 1.1);
  legende(sc, "LE FRUIT : DONNE PAR L'HOTE, POSE, PRIS, MANGE");
}

function niveau1(sc) {
  phenix1(sc);
  sc.lueur(CYAN, 5, 1.2);
  legende(sc, "L'AVANT-BRAS DROIT EN AILE DE FLAMMES");
}

function niveau2(sc) {
  phenix2(sc);
  sc.lueur(CYAN, 6, 1.2);
  legende(sc, "LA MOITIE DROITE, LES AILES, LA SERRE");
}

function niveau3(sc) {
  phenix3(sc);
  sc.lueur(CYAN, 7, 1.3);
  legende(sc, "TOUT EN PLUMES, CRETE, DEUX SERRES");
}

/** Niveau 1, clic droit : le jet de flammes bleues (12 blocs). */
function jet1(sc) {
  sol(sc, -34, -20, 34, 136);
  const T = phenix1(sc, { brasD: [-1.57, -0.06], brasG: [0.3, 0.2], jambeD: -0.3, jambeG: 0.3 });
  const d = T.dir;
  for (let q = 0; q < 110; q++) {
    const t = q / 110, c = ajoute(T.main, fois(d, 3 + 100 * t)), r = 1.5 + 13 * t;
    const o = [(hache(q, 1, 922) - 0.5) * 2 * r, (hache(q, 2, 922) - 0.5) * 2 * r * 0.8, 0];
    flammeB(sc, ajoute(c, o), ajoute(d, [(hache(q, 3, 922) - 0.5) * 0.4, 0.15, 0]), 6 + 9 * t, 2 + 2.6 * t);
    if (q % 4 === 0) flammeJ(sc, ajoute(c, fois(o, 0.4)), d, 2 + 4 * t, 1.2 + t);
  }
  cible(sc, [-5, 0, 124], { feu: 18, pose: { penche: -0.3, brasD: [-1.2, -0.5], brasG: [-1.2, 0.5] } });
  sc.lueur(CYAN, 7, 1.4);
  legende(sc, "12 BLOCS : 6 ET BRULE BLEU 4 S. RECHARGE 6 S. RIEN NE BRULE AU SOL");
}

/** Niveau 1, accroupi : les flammes de régénération (lui et ses alliés à 5 blocs). */
function regen1(sc) {
  sol(sc, -66, -56, 66, 56, (x, z) => {
    const r = Math.hypot(x, z);
    if (Math.abs(r - 46) < 1.6) return brille(CYAN);
    return r < 46 ? mix([104, 170, 150], CYAN_C, 0.12 + 0.1 * hache(Math.floor(x), Math.floor(z), 923)) : null;
  });
  phenix1(sc, { decale: [0, -2, 0], buste: 0.25, jambeD: -0.6, jambeG: 0.5, brasD: [-0.9, -0.6], brasG: [-0.4, 0.4] });
  chaine(sc, helice([0, 0, 0], 11, 0.5, 30, 2), 2);
  for (let q = 0; q < 40; q++) {
    const a = (q / 40) * TOUR;
    flammeB(sc, [Math.cos(a) * 46, 0.2, Math.sin(a) * 46], [0, 1, 0], 4 + 7 * hache(q, 1, 924), 2.6);
  }
  for (const p of [[-34, 0, 18], [32, 0, -14]]) {
    perso(sc, ALLIE, { decale: p, lacet: regarde(p, [0, 0, 0]), brasD: [-0.2, -0.2], brasG: [-0.2, 0.2] });
    soigne(sc, p, p[0] > 0 ? 1 : 2);
  }
  sc.lueur(CYAN, 7, 1.3);
  legende(sc, "LUI +6 (3 ♥), SES ALLIES A 5 BLOCS +3. RECHARGE 20 S");
}

/** Niveau 2, clic droit : le coup de serre, une ruée de 8 blocs, la serre en avant. */
function serre2(sc) {
  sol(sc, -34, -30, 34, 136, (x, z) => (Math.abs(x) < 6 && z > -20 && z < 70 && hache(Math.floor(x), Math.floor(z), 925) < 0.3 ? brille(CYAN_C) : null));
  const P = [0, 10, 70];
  const T = phenix2(sc, { decale: P, penche: -0.5, jambeD: -1.5, jambeG: 0.6, brasD: [0.9, -0.3], brasG: [0.9, 0.3], ailes: 22, aile: { leve: 0.8 } });
  // la traînée de la ruée : des plumes et des flammes derrière lui
  for (let q = 0; q < 34; q++) {
    const t = q / 34, c = [(hache(q, 1, 926) - 0.5) * 14 * (1 - t * 0.5), 6 + 22 * hache(q, 2, 926), P[2] - 14 - 80 * t];
    if (q % 3 === 0) plume(sc, c, [0.2 * (hache(q, 3, 926) - 0.5), -0.3, -1], [0, 1, 0.2], 6 + 4 * hache(q, 4, 926), 2.4, q + 500);
    else flammeB(sc, c, [0, 0.15, -1], 9 + 14 * (1 - t), 3.4);
  }
  const pied = T.jambeD.p([-2, 0, 4]);
  for (let q = 0; q < 14; q++) {
    const a = (q / 14) * TOUR;
    flammeB(sc, ajoute(pied, [0, 0, 3]), norme([Math.cos(a) * 0.8, Math.sin(a) * 0.8, 0.6]), 5 + 6 * hache(q, 1, 927), 2.2);
  }
  cible(sc, [-2, 18, 110], { feu: 14, vers: P, pose: VOLE });
  sc.lueur(CYAN, 7, 1.4);
  legende(sc, "RUEE DE 8 BLOCS : 12 ET RECUL AU PREMIER TOUCHE. RECHARGE 8 S");
}

/** Niveau 2, accroupi : la pluie de plumes là où il vise. */
function pluie2(sc) {
  const C = [10, 0, 92];
  sol(sc, -40, -24, 64, 140, (x, z) => {
    const r = Math.hypot(x - C[0], z - C[2]);
    if (Math.abs(r - 40) < 1.3) return brille(CYAN);
    return r < 40 && hache(Math.floor(x), Math.floor(z), 928) < 0.08 ? brille(CYAN_C) : null;
  });
  phenix2(sc, { brasD: [-2.6, -0.2], brasG: [0.2, 0.1], tete: [-0.4, 0, 0] });
  for (let q = 0; q < 34; q++) {
    const a = hache(q, 1, 929) * TOUR, r = 38 * Math.sqrt(hache(q, 2, 929)), h = 6 + 100 * hache(q, 3, 929);
    const p = [C[0] + Math.cos(a) * r, h, C[2] + Math.sin(a) * r];
    plume(sc, p, [0.15, -1, 0.1], [1, 0, 0.3], 15, 5.5, q + 600, { jaune: 0.5 });
    flammeB(sc, ajoute(p, [0, 15, 0]), [0, 1, 0], 9 + 8 * hache(q, 4, 929), 3.4);
  }
  for (let q = 0; q < 10; q++) {
    const a = hache(q, 5, 929) * TOUR, r = 30 * hache(q, 6, 929);
    flammeB(sc, [C[0] + Math.cos(a) * r, 0.2, C[2] + Math.sin(a) * r], [0, 1, 0], 6 + 6 * hache(q, 7, 929), 3);
  }
  cible(sc, [C[0] - 22, 0, C[2] + 12], { feu: 12, pose: { brasD: [-2.4, -0.4], brasG: [-2.4, 0.4] } });
  cible(sc, [C[0] + 20, 0, C[2] - 16], { feu: 12, pose: { brasD: [-2.4, -0.4], brasG: [-2.4, 0.4] } });
  sc.lueur(CYAN, 6, 1.3);
  legende(sc, "LA OU IL VISE (30 BLOCS) : 3 S, 5 PAR PLUME (15 AU PLUS). 25 S");
}

// ------------------------------------------------------------------ la planche 2 : le niveau 3
/** Clic gauche, 3e coup : les serres en croix. */
function croix3(sc) {
  sol(sc, -50, -20, 50, 90);
  const T = phenix3(sc, { brasD: [-1.9, -0.6], brasG: [-1.2, 0.5], perles: false, ailes: 30 });
  const C = [0, 18, 30];
  for (const s of [-1, 1]) {
    for (let q = 0; q < 16; q++) {
      const t = q / 15 - 0.5, p = ajoute(C, [s * t * 30, -t * 26, 0]);
      flammeB(sc, p, [s * 0.6, -0.5, 0.4], 5 + 6 * (1 - Math.abs(t) * 1.6), 2.6);
      if (q % 3 === 0) flammeJ(sc, p, [0, 1, 0.3], 3, 1.4);
    }
  }
  cible(sc, [0, 0, 38], { feu: 16, pose: { penche: -0.35, brasD: [-1.2, -0.5], brasG: [-1.2, 0.5] } });
  chaine(sc, courbe([[0, 20, 30], [-14, 30, 14], [-6, 26, 2], [-1, 20, 0]], 1.6).pts, 4);
  sc.lueur(CYAN, 7, 1.4);
  legende(sc, "+6 ; AU 3E COUP : 8 A 2 BLOCS ET IL SE SOIGNE 4");
}

/** Accroupi : le cocon, des ailes repliées en œuf de flammes ; il se soigne, ceux d'à côté sont repoussés. */
function cocon3(sc) {
  sol(sc, -60, -40, 60, 80, (x, z) => (Math.hypot(x, z) < 20 && hache(Math.floor(x), Math.floor(z), 930) < 0.2 ? brille(CYAN_C) : null));
  // l'œuf : des plumes qui l'entourent, de bas en haut, refermées sur lui
  for (let r = 0; r < 7; r++) for (let q = 0; q < 14; q++) {
    const a = (q / 14) * TOUR + r * 0.22, y = 2 + r * 6.4, R = 15 * Math.sqrt(Math.max(0.15, 1 - ((y - 20) / 24) ** 2));
    const b = [Math.cos(a) * R, y, Math.sin(a) * R];
    plume(sc, b, [-Math.cos(a) * 0.25, 1, -Math.sin(a) * 0.25], [Math.cos(a), 0, Math.sin(a)], 11, 6, r * 20 + q + 700, { base: CYAN, bout: CYAN_C, jaune: 0.35 });
  }
  crete(sc, { p: (v) => ajoute(v, [0, 14, 0]), n: (v) => v }, 7, 1.2);
  chaine(sc, helice([0, 0, 0], 22, 1, 40, 1.4), 5);
  for (const p of [[-40, 6, 22], [38, 4, 26]]) cible(sc, p, { feu: 10, pose: VOLE });
  sc.lueur(CYAN, 8, 1.4);
  legende(sc, "3 S : IL SE SOIGNE 10, 4 ET RECUL A 4 BLOCS. 25 S");
}

/** Clic droit court : l'éventail de plumes (7 plumes de feu). */
function eventail3(sc) {
  sol(sc, -80, -30, 80, 170);
  const T = phenix3(sc, { brasD: [-1.57, -0.5], brasG: [0.3, 0.2], perles: false, ailes: 32 });
  for (let q = 0; q < 7; q++) {
    const a = (q - 3) * 0.16, d = norme([Math.sin(a), -0.02, Math.cos(a)]);
    const b = ajoute(T.main, fois(d, 40 + 10 * (q % 2)));
    plume(sc, b, d, [0, 1, 0], 26, 8, q + 800, { base: CYAN, bout: CYAN_C, jaune: 0.6 });
    for (let k = 1; k < 8; k++) flammeB(sc, ajoute(b, fois(d, -k * 5)), fois(d, -1), 10 - k * 0.8, 3.6 - k * 0.3);
  }
  for (const p of [[-28, 0, 128], [6, 0, 140], [36, 0, 118]]) cible(sc, p, { feu: 12, vers: [0, 0, 0], pose: { penche: -0.25, brasD: [-1.3, -0.4], brasG: [-1.3, 0.4] } });
  sc.lueur(CYAN, 7, 1.4);
  legende(sc, "7 PLUMES (20 BLOCS) : 6 CHACUNE, 12 AU PLUS SUR UN SEUL. RECHARGE 10 S");
}

/** Clic droit maintenu : le grand phénix sort de sa main et suit son regard ; relâché, il explose. */
function grand3(sc) {
  sol(sc, -90, -30, 90, 190);
  const T = phenix3(sc, { brasD: [-1.7, -0.1], brasG: [0.2, 0.2], perles: false, ailes: 30 });
  const pts = courbe([T.main, [-10, 40, 50], [16, 60, 110], [30, 52, 150]], 3).pts;
  for (let i = 0; i < pts.length - 4; i += 2) flammeB(sc, pts[i], sous(pts[i], pts[i + 2]), 5 + i * 0.15, 2.4 + i * 0.04);
  chaine(sc, pts.filter((_, i) => i % 1 === 0).slice(0, -4), 6, 0.9);
  oiseau(sc, cadre(ID, [30, 52, 150], [0.4, -0.15, 1]), 2.2, { envergure: 22 });
  sc.lueur(CYAN, 9, 1.5);
  legende(sc, "IL SUIT SON REGARD 5 S. RELACHE : 20 AU MILIEU, 12 AU BORD, ALLIES +6. 30 S");
}

/** Accroupi + clic droit : le piqué, il monte et pique là où il vise. */
function pique3(sc) {
  const C = [0, 0, 70];
  sol(sc, -80, 0, 80, 150, (x, z) => {
    const r = Math.hypot(x - C[0], z - C[2]);
    if (r < 64 && Math.abs(r - 12 - 20 * hache(Math.floor(Math.atan2(z - C[2], x) * 6), 1, 931)) < 1.4) return brille(CYAN);
    return r < 30 ? mix([90, 150, 150], CYAN_C, 0.2 + 0.3 * hache(Math.floor(x), Math.floor(z), 932)) : null;
  });
  phenix3(sc, { decale: [C[0], 46, C[2] - 6], penche: 1.2, jambeD: -1.1, jambeG: -1.1, brasD: [-2.9, -0.3], brasG: [-2.9, 0.3], perles: false, ailes: 34, aile: { leve: 0.9 } });
  for (let q = 0; q < 30; q++) flammeB(sc, [C[0] + (hache(q, 1, 933) - 0.5) * 16, 60 + 60 * hache(q, 2, 933), C[2] - 6 + (hache(q, 3, 933) - 0.5) * 10], [0, 1, 0], 6 + 10 * hache(q, 4, 933), 2.6);
  for (let q = 0; q < 30; q++) {
    const a = (q / 30) * TOUR;
    flammeB(sc, [C[0] + Math.cos(a) * 22, 0.2, C[2] + Math.sin(a) * 22], [Math.cos(a), 0.7, Math.sin(a)], 8 + 10 * hache(q, 5, 933), 3.4);
  }
  for (const [x, z] of [[-34, 50], [36, 60], [4, 112]]) cible(sc, [C[0] + x, 14, z], { feu: 10, vers: C, pose: VOLE });
  sc.lueur(CYAN, 9, 1.5);
  legende(sc, "IL MONTE, PUIS PIQUE LA OU IL VISE (25 BLOCS) : 12 A 4 BLOCS, EN L'AIR. 20 S");
}

/** Accroupi + clic gauche : le vol bleu, 15 blocs en ligne ; la traînée soigne les alliés. */
function vol3(sc) {
  sol(sc, -60, -40, 60, 200);
  const T = phenix3(sc, { decale: [0, 16, 120], penche: 1.35, brasD: [0.5, -0.2], brasG: [0.5, 0.2], perles: false, ailes: 34, aile: { leve: 0.15 } });
  for (let q = 0; q < 40; q++) {
    const t = q / 40, p = [(hache(q, 1, 934) - 0.5) * 10 * (1 - t), 18 + (hache(q, 2, 934) - 0.5) * 8, 100 - 110 * t];
    flammeB(sc, p, [0, 0.1, -1], 10 - 6 * t, 3 - 1.6 * t);
  }
  chaine(sc, courbe([[-4, 14, 104], [6, 12, 60], [-6, 10, 20], [4, 8, -20]], 1.7).pts, 7);
  chaine(sc, courbe([[4, 20, 104], [-6, 18, 60], [6, 16, 20], [-4, 14, -20]], 1.7).pts, 8);
  cible(sc, [-24, 0, 150], { feu: 12, vers: [0, 0, 120], pose: VOLE });
  for (const p of [[22, 0, 40], [-24, 0, 70]]) {
    perso(sc, ALLIE, { decale: p, lacet: regarde(p, [0, 0, 60]), brasD: [-0.2, -0.2], brasG: [-0.2, 0.2] });
    soigne(sc, p, p[0] > 0 ? 3 : 4);
  }
  sc.lueur(CYAN, 8, 1.4);
  legende(sc, "15 BLOCS : 10 A QUI IL TRAVERSE ; SA TRAINEE SOIGNE LES ALLIES 4. 12 S");
}

/** J : l'aube du phénix. Il devient un phénix géant, bat deux fois des ailes, puis pique : un dôme de flammes bleues. */
function ulti3(sc) {
  sol(sc, -150, -80, 150, 180, (x, z) => {
    const r = Math.hypot(x, z - 50);
    if (r < 110 && Math.abs(r - 110) < 1.6) return brille(CYAN);
    return r < 110 ? mix([70, 120, 140], CYAN_C, 0.15 + 0.25 * hache(Math.floor(x / 2), Math.floor(z / 2), 935)) : null;
  });
  // le dôme : des voiles de flammes en demi-sphère
  for (let r = 0; r < 6; r++) for (let q = 0; q < 26; q++) {
    const a = (q / 26) * TOUR + r * 0.12, el = (r / 6) * (Math.PI / 2) * 0.92;
    const p = [Math.cos(a) * Math.cos(el) * 92, Math.sin(el) * 70, 50 + Math.sin(a) * Math.cos(el) * 92];
    flammeB(sc, p, [Math.cos(a) * 0.3, 1, Math.sin(a) * 0.3], 8 + 10 * hache(q, r, 936), 5);
  }
  oiseau(sc, cadre(ID, [0, 112, 40], [0, -0.35, 1]), 4, { envergure: 26, leve: 0.5 });
  for (const [x, z] of [[-60, 80], [50, 100], [-20, 130], [70, 30]]) cible(sc, [x, 0, z], { feu: 14, vers: [0, 0, 50], pose: { penche: -0.3, brasD: [-2.4, -0.4], brasG: [-2.4, 0.4] } });
  for (const p of [[-36, 0, 20], [30, 0, 10]]) {
    perso(sc, ALLIE, { decale: p, lacet: regarde(p, [0, 0, 50]), brasD: [-2.5, -0.3], brasG: [-2.5, 0.3] });
    soigne(sc, p, p[0] > 0 ? 5 : 6);
  }
  sc.lueur(CYAN, 10, 1.6);
  legende(sc, "PHENIX GEANT : 2 VAGUES DE PLUMES (8 CHACUNE), PUIS LE DOME : 25, ALLIES SOIGNES A FOND");
}

/** Le passif : la renaissance. Un coup mortel ; il renaît dans une colonne de flammes bleues, une fois par éveil. */
function renaissance3(sc) {
  sol(sc, -90, -40, 90, 90, (x, z) => {
    const r = Math.hypot(x, z);
    if (r < 16) return mix([60, 60, 70], [110, 110, 120], hache(Math.floor(x), Math.floor(z), 937));
    return r < 34 && hache(Math.floor(x), Math.floor(z), 938) < 0.25 ? brille(CYAN) : null;
  });
  for (let q = 0; q < 40; q++) {
    const a = hache(q, 1, 939) * TOUR, r = 15 + 8 * hache(q, 2, 939);
    flammeB(sc, [Math.cos(a) * r, 2 + 70 * hache(q, 3, 939), Math.sin(a) * r], [Math.cos(a) * 0.15, 1, Math.sin(a) * 0.15], 10 + 14 * hache(q, 4, 939), 4);
  }
  phenix3(sc, { decale: [0, 14, 0], brasD: [-2.4, -0.6], brasG: [-2.4, 0.6], tete: [-0.3, 0, 0], ailes: 44, aile: { leve: 0.8 }, perles: false });
  chaine(sc, helice([0, 0, 0], 20, 1, 70, 2.2), 9);
  for (const p of [[-58, 10, 20], [56, 8, 30]]) cible(sc, p, { feu: 10, pose: VOLE });
  sc.lueur(CYAN, 10, 1.6);
  legende(sc, "UNE FOIS PAR EVEIL : IL RENAIT A 10 ♥, 6 ET RECUL A 4 BLOCS");
}

/** Le niveau 3 de dos : les ailes en grand. */
function dos3(sc) {
  phenix3(sc, { ailes: 44, aile: { leve: 0.7 } });
  sc.lueur(CYAN, 8, 1.4);
  legende(sc, "DE DOS : LES AILES, LA CRETE, LES PERLES");
}

/** Le niveau 3 de près : le plumage bleu nuit, les veines de flamme, les yeux, la crête. */
function pres3(sc) {
  phenix3(sc, { ailes: 40, perles: false, brasD: [-0.3, -0.2], brasG: [-0.15, 0.15] });
  sc.lueur(CYAN, 9, 1.4);
  legende(sc, "LES VEINES DE FLAMME, LES YEUX, LA CRETE");
}

/** Le vol (saut deux fois), comme le vent 3. */
function vole3(sc) {
  sol(sc, -60, -40, 60, 60);
  phenix3(sc, { decale: [0, 40, 0], penche: 0.4, jambeD: 0.3, jambeG: 0.15, brasD: [0.2, -0.3], brasG: [0.2, 0.3], ailes: 40, aile: { leve: 0.9 }, perles: false });
  for (let q = 0; q < 16; q++) flammeB(sc, [(hache(q, 1, 940) - 0.5) * 30, 2 + 26 * hache(q, 2, 940), (hache(q, 3, 940) - 0.5) * 20], [0, -1, 0], 4 + 4 * hache(q, 4, 940), 2);
  sc.lueur(CYAN, 8, 1.4);
  legende(sc, "SAUT 2 FOIS : IL VOLE, ACCROUPI POUR DESCENDRE");
}

// ------------------------------------------------------------------ la planche 3 : la renaissance, image par image
/** Un fond plus sombre pour l'animation : la nuit tombe un instant, les flammes éclairent tout. */
const NUIT = [70, 82, 124];

/**
 * L'œuf de flammes (comme le fruit, en grand) : centre c, taille k (1 = le fruit). o.fissures : des fêlures blanc doré
 * qui brillent (l'éclosion) ; o.ouvert : le haut a sauté.
 */
function oeuf(sc, c, k, o = {}) {
  const R = 6.4, H = 8.5;
  for (let x = -7; x <= 7; x++) for (let y = 0; y <= 18; y++) for (let z = -7; z <= 7; z++) {
    const dans = (a, b, cc) => (a / R) ** 2 + ((b - H) / (b < H ? H : 9.5)) ** 2 + (cc / R) ** 2 <= 1;
    if (!dans(x, y, z) || (dans(x + 1, y, z) && dans(x - 1, y, z) && dans(x, y + 1, z) && dans(x, y - 1, z) && dans(x, y, z + 1) && dans(x, y, z - 1))) continue;
    if (o.ouvert && y > 12 + 2 * Math.sin(Math.atan2(z, x) * 5)) continue;
    const a = Math.atan2(z, x), tour = Math.sin(a * 5 + y * 0.55);
    const fele = o.fissures && Math.abs(bruit(a * 7 + y * 0.3, y * 0.8, 1100)) < 0.12;
    const jaune = bruit(a * 6 + y * 0.4, y * 0.9, 918) > 0.5;
    const col = fele ? mix([255, 255, 255], JAUNE, hache(x, y, 1101) * 0.5) : jaune ? mix(JAUNE, OR, hache(x, y, 919)) : mix(tour > 0 ? CYAN : CYAN_F, CYAN_C, 0.3 * hache(x + 9, y, z));
    cube(sc, ajoute(c, fois([x, y + 0.5 - H, z], k)), 1.06 * k, [0, 0], col, true);
  }
  for (let q = 0; q < 36; q++) {
    const a = hache(q, 1, 920) * TOUR, y = 1 + 15 * hache(q, 2, 920), rr = R * Math.sqrt(Math.max(0.05, 1 - ((y - H) / 9) ** 2));
    if (o.ouvert && y > 11) continue;
    pointe(sc, ajoute(c, fois([Math.cos(a) * rr, y - H, Math.sin(a) * rr], k)), norme([Math.cos(a), 0.9, Math.sin(a)]), (2 + 2.8 * hache(q, 3, 920)) * k, 1.6 * k, (u) => mix(CYAN, CYAN_C, u), true, true);
  }
  if (!o.ouvert) for (let q = 0; q < 9; q++) {
    const h = hache(q, 1, 921);
    flammeJ(sc, ajoute(c, fois([(h - 0.5) * 3, 17 - H, (hache(q, 2, 921) - 0.5) * 3], k)), [(h - 0.5) * 0.7, 1, (hache(q, 3, 921) - 0.5) * 0.5], (6 + 7 * hache(q, 4, 921)) * k, 2.4 * k);
  }
}

/** Des rayons de lumière blanc doré qui partent de c (l'éclosion). */
function rayons(sc, c, n, L, k = 0) {
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, 1102 + k) * TOUR, e = (hache(q, 2, 1102 + k) - 0.3) * 1.4;
    const d = norme([Math.cos(a) * Math.cos(e), Math.sin(e) + 0.2, Math.sin(a) * Math.cos(e)]);
    pointe(sc, c, d, L * (0.5 + 0.7 * hache(q, 3, 1102 + k)), 1.6 + 1.6 * hache(q, 4, 1102 + k), (u) => mix([255, 255, 255], JAUNE, u), true, true);
  }
}

/** Un tas de cendres grises, avec des braises cyan qui brillent encore et des fumerolles. */
function cendres(sc, c, r = 12) {
  for (let q = 0; q < 70; q++) {
    const a = hache(q, 1, 1103) * TOUR, d = r * Math.sqrt(hache(q, 2, 1103)), h = Math.max(0, 3.5 * (1 - d / r));
    const braise = hache(q, 3, 1103) < 0.3;
    cube(sc, [c[0] + Math.cos(a) * d, h * hache(q, 4, 1103) + 0.6, c[2] + Math.sin(a) * d], 1.2 + 1.4 * hache(q, 5, 1103), [q, q * 0.4], braise ? brille(hache(q, 6, 1103) < 0.5 ? CYAN : JAUNE).slice(0, 3) : mix([86, 86, 96], [140, 140, 150], hache(q, 7, 1103)), braise);
  }
  for (let q = 0; q < 8; q++) cube(sc, [c[0] + (hache(q, 1, 1104) - 0.5) * 10, 8 + q * 5, c[2] + (hache(q, 2, 1104) - 0.5) * 8], 2 + q * 0.5, [q, q], [150, 154, 170]);
}

/** Des ennemis autour (ceux qui l'ont tué), qui regardent. */
function temoins(sc, c, o = {}) {
  for (const [p, k] of [[[-36, 0, 18], 0], [[34, 0, 26], 1], [[8, 0, -40], 2]]) {
    cible(sc, ajoute(c, p), { vers: c, pose: o.pose ?? { brasD: [-0.3, -0.2], brasG: [-0.3, 0.2] }, feu: o.feu ? 10 : 0 });
  }
}

const POSE_EPEE = { brasD: [-1.9, -0.2], brasG: [-0.3, 0.2] };

/** 1. Le coup mortel : il tombe en arrière, la flamme de ses yeux s'éteint. */
function r1(sc) {
  sol(sc, -60, -60, 60, 60);
  phenix3(sc, { decale: [0, 2, 0], penche: -0.55, brasD: [-2.2, -0.6], brasG: [-2.2, 0.6], tete: [-0.5, 0, 0], ailes: 30, aile: { leve: 0.4 }, perles: false });
  cible(sc, [0, 0, 26], { vers: [0, 0, 0], pose: POSE_EPEE });
  for (let q = 0; q < 10; q++) cube(sc, [(hache(q, 1, 1105) - 0.5) * 30, 10 + 30 * hache(q, 2, 1105), (hache(q, 3, 1105) - 0.5) * 20], 1.4, [q, q], [190, 196, 210]);
  sc.lueur(CYAN, 6, 1);
  legende(sc, "0 S : LE COUP MORTEL, IL NE MEURT PAS");
}

/** 2. Il s'embrase : tout son corps part en flammes bleues, ses plumes s'envolent. */
function r2(sc) {
  sol(sc, -60, -60, 60, 60, (x, z) => (Math.hypot(x, z) < 18 && hache(Math.floor(x), Math.floor(z), 1106) < 0.3 ? brille(CYAN) : null));
  const T = perso(sc, PEAU, { moitie: MOITIE(couvert3), decale: [0, -9, 0], penche: -1.5, brasD: [-0.3, -0.4], brasG: [-0.3, 0.4] });
  bruleB(sc, T, 46);
  for (let q = 0; q < 24; q++) {
    const a = hache(q, 1, 1107) * TOUR, h = 6 + 46 * hache(q, 2, 1107), d = 8 + 18 * hache(q, 3, 1107);
    plume(sc, [Math.cos(a) * d, h, Math.sin(a) * d], [Math.cos(a) * 0.3, 1, Math.sin(a) * 0.3], [Math.sin(a), 0, -Math.cos(a)], 7, 2.6, q + 1100, { jaune: 0.5 });
  }
  for (let q = 0; q < 30; q++) flammeB(sc, [(hache(q, 1, 1108) - 0.5) * 26, 1, (hache(q, 2, 1108) - 0.5) * 12], [0, 1, 0], 10 + 18 * hache(q, 3, 1108), 3.6);
  sc.lueur(CYAN, 9, 1.5);
  legende(sc, "0,5 S : IL S'EMBRASE, SES PLUMES S'ENVOLENT");
}

/** 3. Les cendres : il n'y a plus que des cendres, et un cercle de perles d'or qui s'allume autour. */
function r3(sc) {
  sol(sc, -60, -60, 60, 60, (x, z) => (Math.hypot(x, z) < 14 ? mix([70, 70, 80], [110, 110, 120], hache(Math.floor(x), Math.floor(z), 1109)) : null));
  cendres(sc, [0, 0, 0]);
  chaine(sc, helice([0, 0, 0], 22, 0.6, 0.6, 1, 1.8), 10);
  temoins(sc, [0, 0, 0]);
  sc.lueur(JAUNE, 6, 1);
  legende(sc, "1 S : DES CENDRES ; UN CERCLE D'OR S'ALLUME");
}

/** 4. L'œuf : les cendres remontent en spirale et forment un œuf de flammes qui flotte. */
function r4(sc) {
  sol(sc, -60, -60, 60, 60, (x, z) => (Math.abs(Math.hypot(x, z) - 22) < 1.4 ? brille(JAUNE) : null));
  oeuf(sc, [0, 30, 0], 1.6);
  for (let q = 0; q < 60; q++) {
    const u = q / 60, a = u * TOUR * 3, r = 20 * (1 - u) + 6;
    cube(sc, [Math.cos(a) * r, 2 + 26 * u, Math.sin(a) * r], 1 + 0.8 * hache(q, 1, 1110), [q, q], u > 0.5 ? CYAN_C : [150, 150, 160], u > 0.5);
  }
  chaine(sc, helice([0, 0, 0], 18, 16, 44, 1.4), 11);
  temoins(sc, [0, 0, 0], { pose: { brasD: [-0.9, -0.3], brasG: [-0.9, 0.3] } });
  sc.lueur(CYAN, 9, 1.5);
  legende(sc, "1,5 S : LES CENDRES FORMENT UN OEUF DE FEU");
}

/** 5. L'éclosion : l'œuf se fêle, de la lumière blanc doré jaillit des fentes. */
function r5(sc) {
  sol(sc, -60, -60, 60, 60, (x, z) => (Math.abs(Math.hypot(x, z) - 22) < 1.4 ? brille(JAUNE) : null));
  rayons(sc, [0, 30, 0], 22, 46);
  oeuf(sc, [0, 30, 0], 1.6, { fissures: true });
  chaine(sc, helice([0, 0, 0], 18, 16, 44, 1.4), 12);
  temoins(sc, [0, 0, 0], { pose: { brasD: [-1.6, -0.6], brasG: [-1.6, 0.6] } });
  sc.lueur(JAUNE, 10, 1.6);
  legende(sc, "2,5 S : L'OEUF SE FELE, LA LUMIERE JAILLIT");
}

/** 6. Le phénix : un phénix géant jaillit de l'œuf et monte en spirale. */
function r6(sc) {
  sol(sc, -80, -60, 80, 60, (x, z) => (Math.hypot(x, z) < 22 && hache(Math.floor(x), Math.floor(z), 1111) < 0.25 ? brille(CYAN) : null));
  oeuf(sc, [0, 18, 0], 1.6, { ouvert: true });
  for (let q = 0; q < 26; q++) flammeB(sc, [(hache(q, 1, 1112) - 0.5) * 18, 24 + 30 * hache(q, 2, 1112), (hache(q, 3, 1112) - 0.5) * 18], [0, 1, 0], 10 + 16 * hache(q, 4, 1112), 4);
  oiseau(sc, cadre(ID, [0, 76, 0], [0, 1, 0.25], [0, 0, -1]), 2.4, { envergure: 26, leve: 0.6 });
  chaine(sc, helice([0, 0, 0], 26, 10, 90, 1.6), 13);
  sc.lueur(CYAN, 11, 1.7);
  legende(sc, "3 S : UN PHENIX GEANT JAILLIT ET MONTE");
}

/** 7. Il renaît : il redescend, ailes grandes ouvertes ; le phénix se replie sur lui. */
function r7(sc) {
  sol(sc, -80, -60, 80, 60);
  for (let q = 0; q < 30; q++) {
    const a = hache(q, 1, 1113) * TOUR, d = 10 + 30 * hache(q, 2, 1113);
    flammeB(sc, [Math.cos(a) * d, 40 + 40 * hache(q, 3, 1113), Math.sin(a) * d], [-Math.cos(a), -0.6, -Math.sin(a)], 8 + 10 * hache(q, 4, 1113), 3);
  }
  phenix3(sc, { decale: [0, 20, 0], brasD: [-0.6, -0.9], brasG: [-0.6, 0.9], tete: [-0.15, 0, 0], ailes: 46, aile: { leve: 1.05 }, perles: false });
  chaine(sc, helice([0, 0, 0], 16, 18, 60, 1.6), 14);
  sc.lueur(CYAN, 10, 1.6);
  legende(sc, "3,5 S : IL REDESCEND, AILES OUVERTES");
}

/** 8. L'onde : il pose le pied, une onde de flammes repousse tout le monde ; ses alliés sont soignés. */
function r8(sc) {
  sol(sc, -90, -70, 90, 70, (x, z) => {
    const r = Math.hypot(x, z);
    if (Math.abs(r - 44) < 2) return brille(CYAN_C);
    if (Math.abs(r - 30) < 1.2) return brille(JAUNE);
    return r < 44 ? mix([90, 150, 160], CYAN_C, 0.2 + 0.2 * hache(Math.floor(x), Math.floor(z), 1114)) : null;
  });
  for (let q = 0; q < 48; q++) {
    const a = (q / 48) * TOUR;
    flammeB(sc, [Math.cos(a) * 44, 0.2, Math.sin(a) * 44], [Math.cos(a), 0.5, Math.sin(a)], 10 + 10 * hache(q, 1, 1115), 4.4);
    if (q % 3 === 0) plume(sc, [Math.cos(a) * 34, 10 + 10 * hache(q, 2, 1115), Math.sin(a) * 34], [Math.cos(a), 0.3, Math.sin(a)], [0, 1, 0], 8, 3, q + 1150, { jaune: 0.5 });
  }
  phenix3(sc, { brasD: [-0.4, -1.1], brasG: [-0.4, 1.1], ailes: 40, aile: { leve: 0.8 } });
  for (const p of [[-60, 12, 16], [58, 10, 30], [10, 14, -62]]) cible(sc, p, { feu: 10, pose: VOLE });
  const allie = [-30, 0, -36];
  perso(sc, ALLIE, { decale: allie, lacet: regarde(allie, [0, 0, 0]), brasD: [-0.2, -0.2], brasG: [-0.2, 0.2] });
  soigne(sc, allie, 15);
  sc.lueur(CYAN, 10, 1.6);
  legende(sc, "4 S : IL RENAIT A 10 ♥. 6 ET RECUL A 4 BLOCS");
}

/** L'éveil 3, en 4 images : les flammes montent des pieds, les plumes le couvrent, les ailes s'ouvrent, la crête s'allume. */
const couvertH = (h) => (m, s, cote, nom, tt) => {
  const r = couvert3(m, s, cote, nom, tt);
  return r && tt.centre[1] < h + 2.5 * bruit(tt.centre[0] + tt.centre[2], tt.centre[1], 1116) ? r : 0;
};
const POSE_EVEIL = { brasD: [-0.35, -0.5], brasG: [-0.35, 0.5], tete: [-0.15, 0, 0] };

function e1(sc) {
  sol(sc, -40, -40, 40, 40, (x, z) => (Math.hypot(x, z) < 14 && hache(Math.floor(x), Math.floor(z), 1117) < 0.35 ? brille(CYAN) : null));
  const T = perso(sc, PEAU, { moitie: MOITIE(couvertH(9)), ...POSE_EVEIL });
  serre(sc, T.jambeD, "D", 3.8);
  serre(sc, T.jambeG, "G", 3.8);
  for (let q = 0; q < 26; q++) {
    const a = (q / 26) * TOUR;
    flammeB(sc, [Math.cos(a) * 8, 0.2, Math.sin(a) * 8], [-Math.cos(a) * 0.3, 1, -Math.sin(a) * 0.3], 6 + 10 * hache(q, 1, 1118), 2.6);
  }
  sc.lueur(CYAN, 7, 1.3);
  legende(sc, "LES FLAMMES MONTENT DES PIEDS");
}

function e2(sc) {
  const T = perso(sc, PEAU, { moitie: MOITIE(couvertH(25)), ...POSE_EVEIL });
  serre(sc, T.jambeD, "D", 3.8);
  serre(sc, T.jambeG, "G", 3.8);
  chaine(sc, helice([0, 0, 0], 11, 1, 30, 2), 16);
  // les os des ailes : deux traits de feu qui sortent du dos
  for (const s of [-1, 1]) for (let q = 0; q < 8; q++) flammeB(sc, T.corps.p([s * (1.5 + q * 2.4), 21.5 + q * 2.2, -2.6]), T.corps.n([s * 0.5, 1, -0.2]), 4 + q * 0.6, 2.2);
  sc.lueur(CYAN, 8, 1.4);
  legende(sc, "LES PLUMES LE COUVRENT, LES AILES POUSSENT");
}

function e3(sc) {
  phenix3(sc, { ...POSE_EVEIL, ailes: 40, aile: { leve: 1.1 }, perles: false });
  for (let q = 0; q < 30; q++) {
    const a = hache(q, 1, 1119) * TOUR, d = 22 + 20 * hache(q, 2, 1119);
    plume(sc, [Math.cos(a) * d, 10 + 50 * hache(q, 3, 1119), Math.sin(a) * d * 0.4 - 10], [Math.cos(a), 0.4, Math.sin(a) * 0.4], [0, 0, 1], 7, 2.6, q + 1160, { jaune: 0.5 });
  }
  sc.lueur(CYAN, 9, 1.5);
  legende(sc, "LES AILES S'OUVRENT D'UN COUP");
}

function e4(sc) {
  sol(sc, -60, -50, 60, 50, (x, z) => (Math.abs(Math.hypot(x, z) - 30) < 1.5 ? brille(JAUNE) : null));
  phenix3(sc, { brasD: [-0.2, -0.4], brasG: [-0.2, 0.4], ailes: 40 });
  rayons(sc, [0, 34, 0], 10, 26, 5);
  sc.lueur(CYAN, 9, 1.5);
  legende(sc, "LA CRETE S'ALLUME : NIVEAU 3");
}

// ------------------------------------------------------------------ les flammes bleues (un vrai bloc en jeu)
/**
 * Le feu de Minecraft (sa première image, skins/fire_0.png, gardée en local), recolorié comme les flammes du phénix :
 * bleu profond aux bords, cyan, blanc au cœur, et un peu d'or tout au bout des plus chaudes. En jeu, ce sera la même
 * forme que le feu noir du Susano (la texture du feu), mais en bleu.
 */
const FEU_TEX = (() => {
  try {
    return lire(path.join(ICI, "skins", "fire_0.png"));
  } catch {
    return null;
  }
})();
function texelFeu(x, y) {
  if (!FEU_TEX) {
    const h = 1 - y / 16, w = Math.abs(x - 7.5) / 8;
    return h * (1 - w) > 0.15 + 0.2 * hache(x, y, 1200) ? degrade([CYAN_F, CYAN, CYAN_C], h) : null;
  }
  const i = (y * FEU_TEX.w + x) * 4;
  if (FEU_TEX.px[i + 3] < 100) return null;
  const L = lum([FEU_TEX.px[i], FEU_TEX.px[i + 1], FEU_TEX.px[i + 2]]) / 255;
  return L > 0.93 ? [255, 246, 190] : degrade([CYAN_N, CYAN_F, CYAN, CYAN_C, [240, 254, 255]], Math.min(1, L * 1.05));
}

/** Un bloc de flammes bleues, posé sur le bloc (bx, bz) du sol (en blocs) : quatre plans de feu, comme le feu du jeu. */
function feuBleu(sc, bx, bz, o = {}) {
  const x0 = bx * 16, z0 = bz * 16, h = o.haut ?? 16, rentre = 3;
  const plans = [
    (u, v) => [x0 + u, v, z0 + 0.8 + rentre * (v / h)],
    (u, v) => [x0 + 16 - u, v, z0 + 15.2 - rentre * (v / h)],
    (u, v) => [x0 + 0.8 + rentre * (v / h), v, z0 + 16 - u],
    (u, v) => [x0 + 15.2 - rentre * (v / h), v, z0 + u],
  ];
  for (const P of plans) for (let ty = 0; ty < 16; ty++) for (let tx = 0; tx < 16; tx++) {
    const c = texelFeu(tx, ty);
    if (!c) continue;
    const v0 = (h * (15 - ty)) / 16, v1 = (h * (16 - ty)) / 16;
    sc.quad([P(tx, v1), P(tx + 1, v1), P(tx + 1, v0), P(tx, v0)], c, 0, true);
  }
}

/** Les flammes bleues : elles soignent le phénix qui se tient dedans, et brûlent les autres comme du feu normal. */
function flammesBleues(sc) {
  sol(sc, -64, -48, 80, 48);
  const blocs = [[-2, -1], [-1, -1], [-2, 0], [-1, 0], [0, 0], [-1, 1], [2, 0], [3, 0], [2, -1], [3, 1], [2, 1]];
  for (const [x, z] of blocs) feuBleu(sc, x, z, { haut: 14 + 4 * hache(x, z, 1201) });
  const P = [-16, 0, 8];
  phenix3(sc, { decale: P, brasD: [-0.3, -0.4], brasG: [-0.3, 0.4], ailes: 30, perles: false });
  chaine(sc, helice(P, 10, 2, 34, 1.8), 17);
  const C = [48, 0, 8];
  const T = perso(sc, STEVE, { decale: C, lacet: regarde(C, P), brasD: [-1.2, -0.6], brasG: [-1.2, 0.6] });
  brule(sc, T, 16);
  sc.lueur(CYAN, 8, 1.4);
  legende(sc, "LUI : +1 ♥ PAR S DEDANS. LES AUTRES BRULENT COMME DANS DU FEU NORMAL. 3 A 5 S");
}

/** Virgule : le brasier, un cercle de flammes bleues autour de lui. */
function brasier3(sc) {
  sol(sc, -112, -96, 112, 96);
  for (let bx = -5; bx <= 4; bx++) for (let bz = -5; bz <= 4; bz++) {
    const d = Math.hypot(bx + 0.5, bz + 0.5);
    if (d > 2.6 && d < 4.7 && hache(bx, bz, 1202) < 0.85) feuBleu(sc, bx, bz, { haut: 13 + 6 * hache(bx, bz, 1203) });
  }
  phenix3(sc, { brasD: [-0.5, -1.2], brasG: [-0.5, 1.2], ailes: 36, aile: { leve: 1 } });
  for (const p of [[-58, 0, 30], [52, 0, -40], [60, 0, 44]]) {
    const T = perso(sc, STEVE, { decale: p, lacet: regarde(p, [0, 0, 0]), brasD: [-1.3, -0.5], brasG: [-1.3, 0.5] });
    brule(sc, T, 14);
  }
  sc.lueur(CYAN, 9, 1.5);
  legende(sc, "UN CERCLE DE FLAMMES BLEUES (4 BLOCS), 5 S : LUI +1 ♥ PAR S, LES AUTRES BRULENT. 20 S");
}

/** Point : le tourbillon de plumes, des plumes de feu qui tournent autour de lui. */
function tourbillon3(sc) {
  sol(sc, -60, -60, 60, 60, (x, z) => (Math.abs(Math.hypot(x, z) - 46) < 1.3 ? brille(CYAN) : null));
  phenix3(sc, { brasD: [-0.8, -1.4], brasG: [-0.8, 1.4], ailes: 28, perles: false });
  for (let q = 0; q < 54; q++) {
    const u = q / 54, a = u * TOUR * 3, r = 30 + 14 * Math.sin(u * 9), y = 2 + 44 * u;
    const p = [Math.cos(a) * r, y, Math.sin(a) * r], d = [-Math.sin(a), 0.15, Math.cos(a)];
    plume(sc, p, d, [Math.cos(a), 0, Math.sin(a)], 10, 3.6, q + 1210, { jaune: 0.45 });
    if (q % 3 === 0) flammeB(sc, p, fois(d, -1), 6, 2.2);
  }
  for (const p of [[-46, 6, 26], [44, 4, -30]]) cible(sc, p, { feu: 10, pose: VOLE });
  sc.lueur(CYAN, 8, 1.4);
  legende(sc, "4 S : 3 TOUTES LES 0,5 S A 3 BLOCS, 24 AU PLUS. 18 S");
}

/** Le vol limité : 30 s au plus, une jauge sur son panneau ; vide, il redescend et doit toucher le sol. */
function vol30(sc) {
  sol(sc, -60, -60, 60, 60);
  phenix3(sc, { decale: [0, 46, 0], penche: 0.35, jambeD: 0.3, jambeG: 0.15, brasD: [0.2, -0.3], brasG: [0.2, 0.3], ailes: 40, aile: { leve: 0.9 }, perles: false });
  // le panneau : la jauge de vol
  const x0 = 16, y0 = 18, W = sc.w;
  for (let y = y0; y < y0 + 46; y++) for (let x = x0; x < x0 + 190; x++) sc.img.set([30, 40, 70, 255], (y * W + x) * 4);
  ecrireTexte(sc.img, W, "VOLER  18 S", x0 + 10, y0 + 8, 2, [190, 250, 255, 255]);
  for (let y = y0 + 28; y < y0 + 38; y++) for (let x = x0 + 10; x < x0 + 180; x++) sc.img.set(x < x0 + 10 + 170 * 0.6 ? [96, 226, 248, 255] : [60, 70, 100, 255], (y * W + x) * 4);
  sc.lueur(CYAN, 8, 1.4);
  legende(sc, "30 S AU PLUS, LA JAUGE SE REMPLIT AU SOL");
}

// ------------------------------------------------------------------ les planches
const CW = 440, CH = 520, M = 34, HAUT = 700;
/** Un fond bleu du soir (comme le fond violet de Ténèbres) : les flammes et les veines y brillent mieux. */
const CREPUSCULE = [136, 150, 182];
const NOIR = [30, 30, 40, 255], BLEU = [24, 130, 196, 255], GRIS = [200, 200, 210, 255];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

const PORTRAIT = { echelle: 7.2, tangage: 10, pied: 455, centre: [0, 0, 0] };
const LARGE = { tangage: 16, pied: 300 };

const PLANCHES = [
  {
    fichier: "planche-phenix.png",
    titre: "FRUIT DU DEMON : TORI TORI NO MI, MODELE PHENIX",
    colonnes: [
      ["NIVEAU 1 (LISTE PHENIX)", [
        "L'AVANT-BRAS DROIT DEVIENT UNE AILE DE FLAMMES BLEUES",
        "CLIC GAUCHE : +2, BRULE BLEU 3 S",
        "CLIC DROIT : JET DE FLAMMES BLEUES, 12 BLOCS",
        "    6, BRULE BLEU 4 S. RECHARGE 6 S",
        "ACCROUPI : FLAMMES DE REGENERATION",
        "    LUI +6 (3 ♥), SES ALLIES A 5 BLOCS +3. 20 S",
        "PASSIFS : PAS DE DEGATS DE CHUTE (IL PLANE)",
        "    +1/2 ♥ TOUTES LES 4 S. DANS L'EAU : -1/2 ♥ PAR S",
      ]],
      ["NIVEAU 2 (LISTE PHENIX2)", [
        "LA MOITIE DROITE EN PLUMES, DES AILES, UNE SERRE",
        "CLIC GAUCHE : +4, ET 3 A 2 BLOCS",
        "CLIC DROIT : LE COUP DE SERRE, RUEE DE 8 BLOCS",
        "    12 ET RECUL AU PREMIER TOUCHE. RECHARGE 8 S",
        "ACCROUPI : LA PLUIE DE PLUMES LA OU IL VISE (30 BLOCS)",
        "    3 S, 5 PAR PLUME, 15 AU PLUS. RECHARGE 25 S",
        "SAUT 2 FOIS : 3 BATTEMENTS D'AILES, PUIS IL PLANE",
        "PASSIFS : +1/2 ♥ TOUTES LES 2 S. QUI LE FRAPPE BRULE 2 S",
      ]],
    ],
    bas: [
      "SA SPECIALITE : LES FLAMMES BLEUES SOIGNENT (LUI ET SES ALLIES). EN ECHANGE, IL FRAPPE UN PEU MOINS FORT QUE LA LAVE",
      "A COTE : LAVE 1 ERUPTION 8, LAVE 2 POING 14 + 4, PLUIE DE METEORES 5 (15 AU PLUS), GLACE 2 FLECHE 14",
      "COMME LES AUTRES FRUITS : DONNE PAR L'HOTE SEULEMENT, EVEIL 2 MIN PUIS REPOS, L'EAU LE BLESSE, MEMES TOUCHES",
      "LES DEGATS SONT AVANT LE RENFORT DES COMBATS (X 1,6 A X 2,4). LE SOIN, LUI, NE CHANGE PAS",
    ],
    vues: [
      ["LE FRUIT", fruit, { echelle: 11, tangage: 18, pied: 380, lacet: -24, centre: [0, 6, 0] }],
      ["NIVEAU 1", niveau1, { ...PORTRAIT, lacet: 34 }],
      ["NIVEAU 2", niveau2, { ...PORTRAIT, echelle: 5.6, lacet: 26 }],
      ["NIVEAU 3", niveau3, { ...PORTRAIT, echelle: 3.9, lacet: 24, pied: 462 }],
      ["NIVEAU 1, CLIC DROIT : LE JET DE FLAMMES BLEUES", jet1, { ...LARGE, echelle: 4.6, lacet: 66, pied: 290, centre: [0, 18, 60] }, 2],
      ["NIVEAU 1, ACCROUPI : LES FLAMMES DE REGENERATION", regen1, { ...LARGE, echelle: 4.6, lacet: 20, tangage: 26, pied: 320, centre: [0, 12, 0] }, 2],
      ["NIVEAU 2, CLIC DROIT : LE COUP DE SERRE", serre2, { ...LARGE, echelle: 4.4, lacet: 66, pied: 290, centre: [0, 20, 62] }, 2],
      ["NIVEAU 2, ACCROUPI : LA PLUIE DE PLUMES", pluie2, { ...LARGE, echelle: 3.3, lacet: 52, tangage: 20, pied: 300, centre: [10, 44, 66] }, 2],
    ],
  },
  {
    fichier: "planche-phenix3.png",
    titre: "FRUIT DU DEMON : PHENIX, NIVEAU 3 (LISTE PHENIX3)",
    colonnes: [
      ["LES ATTAQUES (ACCROUPI NE LANCE RIEN : IL DESCEND EN VOL)", [
        "CLIC GAUCHE : LES SERRES, +6. AU 3E COUP DE SUITE :",
        "    SERRES EN CROIX, 8 A 2 BLOCS, IL SE SOIGNE 4",
        "CLIC DROIT : L'EVENTAIL, 7 PLUMES DE FEU (20 BLOCS)",
        "    6 CHACUNE, 12 AU PLUS SUR UN SEUL. RECHARGE 10 S",
        "CLIC DROIT MAINTENU : LE GRAND PHENIX, SUIT SON REGARD",
        "    EXPLOSE : 20 / 12, FLAMMES BLEUES AU SOL. 30 S",
        "TECHNIQUE (SOUS ECHAP) : LE COCON, 3 S : IL SE SOIGNE 10",
        "    4 ET RECUL A 4 BLOCS. RECHARGE 25 S",
      ]],
      ["", [
        "L : LE PIQUE, LA OU IL VISE (25 BLOCS)",
        "    12 A 4 BLOCS, EN L'AIR, FLAMMES BLEUES. 20 S",
        "I : LE VOL BLEU, 15 BLOCS EN LIGNE, 10 A QUI IL TRAVERSE",
        "    SA TRAINEE SOIGNE LES ALLIES 4. RECHARGE 12 S",
        "VIRGULE : LE BRASIER, UN CERCLE DE FLAMMES BLEUES, 5 S",
        "    LUI +1 ♥ PAR S DEDANS, LES AUTRES BRULENT. 20 S",
        "POINT : LE TOURBILLON DE PLUMES, 4 S AUTOUR DE LUI",
        "    3 TOUTES LES 0,5 S A 3 BLOCS, 24 AU PLUS. 18 S",
      ]],
    ],
    bas: [
      "SAUT 2 FOIS : IL VOLE 30 S AU PLUS (UNE JAUGE SUR SON PANNEAU), ELLE SE REMPLIT AU SOL. PAREIL POUR VENT 3",
      "J : L'AUBE DU PHENIX. PASSIF : LA RENAISSANCE (SA PLANCHE). TOUTES LES TOUCHES SE CHANGENT DANS LES OPTIONS",
      "LES FLAMMES BLEUES : UN VRAI BLOC (LE FEU DU JEU EN BLEU, COMME LE FEU NOIR), 3 A 5 S PUIS ELLES S'ETEIGNENT",
      "    ELLES SOIGNENT LE PHENIX (+1 ♥ PAR S) ET BRULENT LES AUTRES COMME DU FEU NORMAL. ELLES NE S'ETENDENT PAS",
    ],
    vues: [
      ["3E COUP : SERRES EN CROIX", croix3, { ...PORTRAIT, echelle: 3.8, lacet: 34, pied: 340, centre: [0, 18, 20] }],
      ["TECHNIQUE : LE COCON", cocon3, { ...PORTRAIT, echelle: 3.4, lacet: 20, tangage: 16, pied: 360, centre: [0, 20, 0] }],
      ["CLIC DROIT : L'EVENTAIL DE PLUMES", eventail3, { ...LARGE, echelle: 3.4, lacet: 60, pied: 300, centre: [0, 22, 72] }, 2],
      ["CLIC DROIT MAINTENU : LE GRAND PHENIX", grand3, { ...LARGE, echelle: 2.9, lacet: 56, pied: 320, centre: [8, 44, 84] }, 2],
      ["L : LE PIQUE", pique3, { ...LARGE, echelle: 3, lacet: 40, tangage: 16, pied: 330, centre: [0, 40, 70] }, 2],
      ["I : LE VOL BLEU", vol3, { ...LARGE, echelle: 3.3, lacet: 70, pied: 300, centre: [0, 16, 84] }, 2],
      ["J : L'AUBE DU PHENIX", ulti3, { ...LARGE, echelle: 1.75, lacet: 18, tangage: 16, pied: 330, centre: [0, 64, 50] }, 2],
      ["VIRGULE : LE BRASIER", brasier3, { ...LARGE, echelle: 2.7, lacet: 24, tangage: 24, pied: 330, centre: [0, 24, 0] }, 2],
      ["POINT : LE TOURBILLON", tourbillon3, { ...PORTRAIT, echelle: 3.4, lacet: 22, tangage: 14, pied: 380, centre: [0, 20, 0] }],
      ["LE VOL : 30 S AU PLUS", vol30, { ...PORTRAIT, echelle: 3.1, lacet: 30, pied: 280, centre: [0, 50, 0] }],
      ["LES FLAMMES BLEUES", flammesBleues, { ...LARGE, echelle: 4.8, lacet: 18, tangage: 22, pied: 320, centre: [12, 16, 0] }, 2],
      ["PASSIF : LA RENAISSANCE", renaissance3, { ...LARGE, echelle: 3.3, lacet: 16, tangage: 14, pied: 330, centre: [0, 36, 0] }, 2],
      ["NIVEAU 3 DE DOS", dos3, { ...PORTRAIT, echelle: 3.3, lacet: 200, pied: 380, centre: [0, 24, 0] }],
      ["NIVEAU 3 DE PRES", pres3, { ...PORTRAIT, echelle: 11, lacet: 22, tangage: 6, pied: 300, centre: [0, 22, 0] }],
    ],
  },
  {
    fichier: "planche-phenix-renaissance.png",
    titre: "PHENIX : LA RENAISSANCE ET L'EVEIL 3",
    fond: NUIT,
    colonnes: [
      ["LA RENAISSANCE (NIVEAU 3, UNE FOIS PAR EVEIL)", [
        "UN COUP QUI DEVRAIT LE TUER : IL NE MEURT PAS, IL BRULE",
        "4 S D'ANIMATION, SUR PLACE (PAS DE RETOUR AU SPAWN)",
        "    IL S'EMBRASE, CENDRES, OEUF DE FEU, ECLOSION, LE PHENIX,",
        "    IL REDESCEND ET L'ONDE REPOUSSE TOUT LE MONDE",
        "PENDANT CES 4 S : PERSONNE NE PEUT LE TOUCHER (IL EST EN CENDRES)",
        "    IL GARDE SES OBJETS, IL NE MEURT PAS VRAIMENT",
        "A LA FIN : 10 ♥, REGENERATION II 5 S, 6 ET RECUL A 4 BLOCS",
        "    SES ALLIES A 6 BLOCS : +4",
      ]],
      ["CE QUE VOIENT LES JOUEURS", [
        "LUI : LA CAMERA SORT DE SON CORPS ET TOURNE AUTOUR DE L'OEUF",
        "    PUIS REVIENT DANS SES YEUX QUAND IL REDESCEND",
        "LES AUTRES (A 48 BLOCS) : LES CENDRES, L'OEUF, LE PHENIX EN JEU,",
        "    SANS CINEMATIQUE : ILS PEUVENT SE PREPARER",
        "UN SON DE BRASIER, PUIS UN CRI DE PHENIX A L'ECLOSION",
        "DANS LE CHAT : SON NOM, PUIS : RENAIT DE SES CENDRES",
        "APRES : LA RENAISSANCE EST USEE JUSQU'AU PROCHAIN EVEIL",
        "    (L'ICONE DE L'OEUF S'ETEINT SUR SON PANNEAU)",
      ]],
    ],
    bas: [
      "EN DESSOUS : L'EVEIL 3, EN 4 IMAGES (2 S) : LES FLAMMES MONTENT DES PIEDS, LES PLUMES LE COUVRENT, LES AILES S'OUVRENT,",
      "    LA CRETE S'ALLUME. AU REPOS (FIN DE L'EVEIL), TOUT S'ETEINT A L'ENVERS ET LES PLUMES TOMBENT EN CENDRES",
      "LA RENAISSANCE N'ANNULE AUCUN POUVOIR NI AUCUN COUP : ELLE LE SAUVE UNE FOIS, C'EST TOUT",
    ],
    vues: [
      ["1. LE COUP MORTEL", r1, { ...PORTRAIT, echelle: 4.2, lacet: 40, tangage: 14, pied: 420 }],
      ["2. IL S'EMBRASE", r2, { ...PORTRAIT, echelle: 4.4, lacet: 30, tangage: 20, pied: 420 }],
      ["3. LES CENDRES", r3, { ...PORTRAIT, echelle: 4, lacet: 24, tangage: 24, pied: 400 }],
      ["4. L'OEUF DE FEU", r4, { ...PORTRAIT, echelle: 3.8, lacet: 24, tangage: 14, pied: 440 }],
      ["5. L'ECLOSION", r5, { ...PORTRAIT, echelle: 3.8, lacet: 24, tangage: 14, pied: 440 }],
      ["6. LE PHENIX", r6, { ...PORTRAIT, echelle: 2.6, lacet: 20, tangage: 10, pied: 470 }],
      ["7. IL RENAIT", r7, { ...PORTRAIT, echelle: 3.2, lacet: 20, tangage: 8, pied: 470 }],
      ["8. L'ONDE", r8, { ...PORTRAIT, echelle: 2.6, lacet: 24, tangage: 22, pied: 420 }],
      ["EVEIL 3 : 1", e1, { ...PORTRAIT, echelle: 6.2, lacet: 26 }],
      ["EVEIL 3 : 2", e2, { ...PORTRAIT, echelle: 5.6, lacet: 26 }],
      ["EVEIL 3 : 3", e3, { ...PORTRAIT, echelle: 3.9, lacet: 24, pied: 462 }],
      ["EVEIL 3 : 4", e4, { ...PORTRAIT, echelle: 3.9, lacet: 24, pied: 462 }],
    ],
  },
];

function vignette([, dessin, o, span = 1], fond = CREPUSCULE) {
  const sc = scene({ w: CW * span, h: CH, fond, ...o });
  dessin(sc);
  return sc;
}

function planche(p) {
  const COLONNES = 4, places = [];
  let c = 0, l = 0;
  for (const v of p.vues) {
    const span = v[3] ?? 1;
    if (c + span > COLONNES) { c = 0; l++; }
    places.push([c, l]);
    c += span;
  }
  const W = CW * COLONNES, H = HAUT + (M + CH) * (l + 1);
  const img = Buffer.alloc(W * H * 4, 255);
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, p.titre, 30, 22, 5, NOIR);
  p.colonnes.forEach(([titre, lignes], k) => {
    const x = 30 + k * 880;
    if (titre) ecrireTexte(img, W, titre, x, 100, 4, BLEU);
    lignes.forEach((t, j) => ecrireTexte(img, W, t, x, 150 + j * 34, 3, NOIR));
  });
  rect(img, W, 30, 446, W - 30, 449, GRIS);
  p.bas.forEach((t, k) => ecrireTexte(img, W, t, 30, 468 + k * 34, 3, NOIR));
  p.vues.forEach((v, k) => {
    const [c0, l0] = places[k], sc = vignette(v, p.fond);
    for (let y = 0; y < CH; y++) sc.img.copy(img, ((HAUT + l0 * (M + CH) + M + y) * W + c0 * CW) * 4, y * sc.w * 4, (y + 1) * sc.w * 4);
    ecrireTexte(img, W, v[0], c0 * CW + 12, HAUT + l0 * (M + CH) + 10, 3, BLEU);
  });
  ecrirePng(path.join(DOC, p.fichier), W, H, img);
  console.log("ok", p.fichier, W, H);
}

const TOUTES = PLANCHES.flatMap((p) => p.vues);
const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL && process.argv[2] === "planche") {
  planche(PLANCHES[Number(process.argv[3]) - 1]);
} else if (PRINCIPAL && process.argv[2] !== undefined) {
  const v = TOUTES[Number(process.argv[2])];
  const sc = vignette(v);
  ecrirePng(process.argv[3] ?? path.join(DOC, "vignette.png"), sc.w, sc.h, sc.img);
  console.log("ok", v[0]);
} else if (PRINCIPAL) {
  for (const p of PLANCHES) planche(p);
}

export { oiseau, aile, serre, crete, yeux, plume, oeuf, flammeB, flammeJ, plumesT, chaine, CYAN, CYAN_C, CYAN_F, JAUNE, OR };
