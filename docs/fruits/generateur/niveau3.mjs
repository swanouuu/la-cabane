// Maquettes de l'éveil 3 des fruits du démon (demandé le 2026-11-03, le visuel seulement) : la lave (ou la glace)
// couvre presque tout le corps ; seul l'autre bras reste en partie à nu, avec un bord irrégulier, et les yeux restent
// libres. Un casque en tête de dragon (une seule tête : pas sur le bras ni l'épaule, à la demande), des plaques et des
// pics sur l'armure, par-dessus l'armure portée. L'attaque : clic droit maintenu, un dragon géant sans pattes (une tête
// et un long corps de serpent) sort de sa main et suit son regard ; relâché, il explose.
// node niveau3.mjs → planche-niveau3.png ; node niveau3.mjs <n> [fichier.png] → la vignette n seule
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, mix, hache, norme, croix, scene, ecrireTexte } from "./moteur.mjs";
import {
  ajoute, sous, fois, rotX, TOUR, ID, brille, pave, cube, pointe, flamme, cristal, perso, brule, givre, sol, legende, regarde,
  fissure, magma, glace, bruit, FACES, LAVE_R, LAVE_O, LAVE_J, GLACE, GLACE_C, BLANC, FUMEE, FOND,
  PEAU_LAVE, PEAU_GLACE, STEVE,
} from "./rendu.mjs";
import { niveau2, blocDeGlace } from "./niveau2.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const OEIL_LAVE = [255, 246, 170], OEIL_GLACE = [150, 248, 255], GORGE_GLACE = [196, 240, 255], GLACE_P = [90, 156, 224];
const CORNE_LAVE = (t) => mix([58, 34, 30], [190, 84, 36], t * t);
const GIVRE_C = { c: GLACE_C, k: 0.45 };

// ------------------------------------------------------------------ le corps couvert
/**
 * L'éveil 3 : tout est pris (1), sauf l'autre bras, qui garde la peau sous un bord irrégulier (l'épaule et le haut du
 * bras sont pris, avec quelques îles plus bas, et des veines sur la peau : 2), et une bande autour des yeux.
 */
function couvert3(m, s, cote, nom, t) {
  const [x, y, z] = t.centre, k = s > 0 ? 0 : 50, autre = cote === "G" ? "D" : "G";
  if (nom === "bras" + autre) {
    const bord = 18.2 + 2 * Math.sin(z * 0.9 + x * 0.7 + y * 0.35) + 2.6 * bruit(z + x, y, 330 + k);
    if (y > bord) return 1;
    if (y > 9 && bruit(y * 1.3 + z, z - x * 1.3, 331 + k) > 0.6) return 1;
    return y > 12.5 && Math.abs(bruit(y * 1.3 + z, x * 1.3 - z, 332 + k)) < 0.05 ? 2 : 0;
  }
  if (nom === "tete" && t.face === "devant") {
    const haut = 28.6 + 0.6 * bruit(x * 1.5, 1, 333 + k), bas = 26.3 + 0.6 * bruit(x * 1.5, 2, 334 + k);
    if (y < haut && y > bas && Math.abs(x) < 3.3 + 0.7 * bruit(y, x, 335 + k)) return 0;
  }
  return 1;
}

// ------------------------------------------------------------------ les repères
/** Un repère posé sur la partie P (ou le monde, ID) : origine o, nez vers avant, dessus vers haut, échelle k. */
function cadre(P, o, avant, haut = [0, 1, 0], k = 1) {
  const f = norme(avant);
  let x = croix(haut, f);
  if (Math.hypot(...x) < 0.2) x = croix([0, 0, 1], f);
  x = norme(x);
  const y = croix(f, x);
  const vers = (v) => ajoute(ajoute(fois(x, v[0]), fois(y, v[1])), fois(f, v[2]));
  return { p: (v) => P.p(ajoute(o, fois(vers(v), k))), n: (v) => P.n(vers(v)), k };
}

/** Le même repère, tourné de a autour de l'axe x passant par pivot (la mâchoire qui s'ouvre). */
function charniere(H, pivot, a) {
  return { p: (v) => H.p(ajoute(pivot, rotX(sous(v, pivot), a))), n: (v) => H.n(rotX(v, a)), k: H.k };
}

const boite = (sc, T, c, d, couleur, pas = 0.5, aile = true) => pave(sc, sous(c, d), ajoute(c, d), T, couleur, pas, 0, aile);

// ------------------------------------------------------------------ la tête de dragon
/**
 * Une tête de dragon dans le repère H (le nez vers +z, environ 13 de long × k) : le crâne, les arcades et les yeux qui
 * brillent, le museau et ses naseaux, la mâchoire (ouverte de o.gueule) avec ses dents et la gorge qui brille, deux
 * paires de cornes, les moustaches et la crinière (du feu, ou des cristaux). o.casque : pas de mâchoire du bas (le
 * visage du joueur est dessous), des crocs plus courts. Rend la bouche et sa direction.
 */
function teteDragon(sc, H, sorte, o = {}) {
  const lave = sorte === "lave", k = H.k, aile = o.aile ?? true, g = o.gueule ?? 0.5;
  // les têtes sont plus sombres que le corps (de la croûte, de la glace plus profonde), pour qu'elles se détachent ;
  // la glace a des arêtes blanches en haut des faces
  const peau = (n) => (lave
    ? (f, u, v, i, j) => magma(i + n * 13, j + n * 7, FACES[f] + n * 17, 4, o.ouvert ?? 0.42)
    : (f, u, v, i, j) => (f !== "haut" && f !== "bas" && v < 0.2 ? mix(BLANC, GLACE_C, 0.4) : mix(glace(i + n * 13, j, FACES[f] + n, f === "haut" ? 0.1 : v), GLACE_P, 0.38)));
  const feu = (i, j) => (lave ? brille(mix(LAVE_J, LAVE_O, hache(i, j, 341) * 0.8)) : brille(mix(GORGE_GLACE, BLANC, hache(i, j, 341))));
  const dedans = (base, face) => (f, u, v, i, j) => (f === face ? feu(i, j) : base(f, u, v, i, j));
  const dent = lave ? (t) => mix([255, 244, 200], LAVE_J, t) : (t) => mix(GLACE_C, BLANC, t);
  const corne = lave ? CORNE_LAVE : (t) => mix(GLACE, BLANC, t);
  // des cases d'environ un pixel du joueur (pas plus fines que 0,5 sur les grandes têtes)
  const pas = o.pas ?? Math.max(0.5, 1 / k);
  const B = (T, c, d, couleur) => boite(sc, T, c, d, couleur, pas, aile);

  // le crâne, plus haut derrière, le front qui descend vers le museau, la nuque
  B(H, [0, 1.2, -1.5], [2.8, 2.2, 2.5], peau(1));
  B(H, [0, 2.1, 1.6], [2.3, 1.2, 0.9], peau(9));
  B(H, [0, 0.8, -4.6], [2.1, 1.6, 0.8], peau(2));
  // les arcades, qui avancent au-dessus des yeux ; les yeux, et leur pupille fendue
  for (const s of [-1, 1]) {
    B(H, [s * 2.25, 3.25, 0.6], [0.95, 0.5, 1.6], peau(3));
    B(H, [s * 2.85, 2.05, 0.7], [0.12, 0.55, 0.9], () => brille(lave ? OEIL_LAVE : OEIL_GLACE));
    B(H, [s * 2.99, 2.05, 0.75], [0.04, 0.5, 0.14], () => brille(lave ? [90, 20, 10] : [20, 60, 120]));
  }
  // le museau qui s'affine, brillant dessous quand la gueule s'ouvre ; l'arête du nez, sa corne ; les naseaux
  B(H, [0, 1.1, 4.1], [1.9, 1.1, 2.5], dedans(peau(4), "bas"));
  B(H, [0, 0.95, 7.4], [1.5, 0.95, 0.9], dedans(peau(5), "bas"));
  B(H, [0, 2.4, 4.6], [0.6, 0.25, 2.2], peau(6));
  pointe(sc, H.p([0, 1.9, 7.2]), H.n([0, 1, 0.45]), 1.8 * k, 0.9 * k, corne, aile, false);
  for (const s of [-1, 1]) B(H, [s * 0.75, 1.92, 8.0], [0.3, 0.06, 0.3], () => brille(lave ? LAVE_J : BLANC));
  // les dents du haut, et les deux crocs
  for (const s of [-1, 1]) for (const z of [2.2, 3.4, 4.6, 5.8, 7.0, 7.9]) {
    const croc = z === 7.0;
    const L = croc ? (o.casque ? 1.5 : 2.6) : 1.0;
    pointe(sc, H.p([s * (z > 6.5 ? 1.25 : 1.6), 0.05, z]), H.n([0, -1, 0.12]), L * k, (croc ? 0.75 : 0.5) * k, dent, aile, lave);
  }
  if (!o.casque) {
    // la mâchoire du bas, plus courte que le museau
    const J = charniere(H, [0, -0.1, -1.5], g);
    B(J, [0, -0.75, 2.5], [1.7, 0.6, 4], dedans(peau(7), "haut"));
    B(J, [0, -1.45, 5.6], [1.1, 0.4, 0.9], peau(8));
    for (const s of [-1, 1]) {
      for (const z of [1.2, 2.5, 3.8, 5.1, 6.1]) pointe(sc, J.p([s * 1.4, -0.15, z]), J.n([0, 1, 0.1]), (z > 6 ? 1.7 : 0.9) * k, 0.45 * k, dent, aile, lave);
      // la barbe : des pointes sous le menton
      pointe(sc, J.p([s * 0.7, -1.8, 4.6]), J.n([s * 0.2, -1, -0.7]), 2.2 * k, 0.7 * k, corne, aile, false);
    }
    B(H, [0, -0.1, 0.2], [1.6, 0.6, 1], () => brille(lave ? LAVE_J : GORGE_GLACE));
  }
  // les cornes, qui se recourbent vers le haut ; une petite paire sur les côtés ; des pointes aux joues
  for (const s of [-1, 1]) {
    const base = H.p([s * 1.9, 3.1, -2.8]), d1 = H.n([s * 0.3, 0.35, -1]);
    pointe(sc, base, d1, 5 * k, 1.5 * k, corne, aile, false);
    pointe(sc, ajoute(base, fois(d1, 3.6 * k)), H.n([s * 0.25, 1, -0.55]), 4 * k, 0.9 * k, corne, aile, false);
    pointe(sc, H.p([s * 2.7, 0.9, -3.6]), H.n([s * 0.7, 0.1, -1]), 3.2 * k, 0.9 * k, corne, aile, false);
    pointe(sc, H.p([s * 2.8, -0.3, -0.9]), H.n([s, -0.35, -0.5]), 1.9 * k, 0.7 * k, corne, aile, false);
    // les moustaches : de longues langues de feu (des fils de givre) qui partent du museau vers l'arrière
    if (lave) pointe(sc, H.p([s * 1.8, 0.6, 6.6]), H.n([s * 0.75, -0.3, -1]), 6.5 * k, 0.5 * k, (t) => mix(LAVE_J, LAVE_R, t), aile, true);
    else cristal(sc, H.p([s * 1.8, 0.6, 6.6]), H.n([s * 0.75, -0.3, -1]), 6 * k, 0.45 * k, aile);
  }
  // la crinière, sur le crâne et vers l'arrière
  for (let q = 0; q < 5; q++) {
    const b = H.p([(q % 2 ? 1 : -1) * 0.8 * hache(q, 1, 342), 3.2, -0.6 - q * 1.0]);
    if (lave) flamme(sc, b, H.n([0, 1, -0.5 - 0.1 * q]), (2.4 + 2 * hache(q, 2, 342)) * k, 1.2 * k);
    else cristal(sc, b, H.n([(hache(q, 3, 342) - 0.5) * 0.5, 0.8, -1]), (2.2 + 1.6 * hache(q, 2, 342)) * k, 1 * k, aile);
  }
  // de la fumée (du givre) qui sort des naseaux
  if (o.fumee) {
    for (let q = 0; q < 4; q++) {
      const p = H.p([(q % 2 ? 1 : -1) * 0.9, 2.4 + q * 1.3, 8.3 + q * 0.6]);
      cube(sc, ajoute(p, [0, q * 0.6 * k, 0]), (0.5 + 0.3 * q) * k, [q, q * 1.7], lave ? FUMEE : BLANC, !lave);
    }
  }
  // le souffle : du feu (du givre) qui sort de la gueule
  if (o.souffle) {
    const bouche = H.p([0, -0.4, 8.5]), d = H.n([0, -0.1, 1]);
    for (let q = 0; q < 12; q++) {
      const t = q / 12, p = ajoute(bouche, ajoute(fois(d, t * o.souffle * k), fois(H.n([hache(q, 1, 343) - 0.5, hache(q, 2, 343) - 0.5, 0]), t * 4 * k)));
      if (lave) flamme(sc, p, ajoute(d, [0, 0.3, 0]), (2 + 3 * t) * k, (1 + 1.5 * t) * k);
      else cube(sc, p, (0.6 + 1.6 * t) * k, [q, t * 3], q % 3 ? BLANC : GLACE_C, true);
    }
  }
  return { bouche: H.p([0, -0.4, 8.6]), dir: H.n([0, 0, 1]) };
}

// ------------------------------------------------------------------ le joueur au niveau 3
/**
 * L'armure de lave (de glace) : des plaques de croûte sombre (de glace plus profonde) posées dessus, avec des pics —
 * aux deux épaules, sur l'avant-bras du fruit, sur la poitrine, aux genoux, et une crête le long du dos. g : de combien
 * tout ressort quand il porte une armure (la lave passe par-dessus, les plaques aussi).
 */
function plaques(sc, T, lave, g = 0) {
  const s = lave ? 1 : -1, cote = lave ? "G" : "D";
  const croute = (n) => (lave
    ? (f, u, v, i, j) => magma(i + n * 13, j + n * 7, FACES[f] + n * 17, 4, 0.42)
    : (f, u, v, i, j) => (f !== "haut" && f !== "bas" && v < 0.25 ? mix(BLANC, GLACE_C, 0.4) : mix(glace(i + n * 13, j, FACES[f] + n, f === "haut" ? 0.1 : v), GLACE_P, 0.38)));
  const plaque = (P, c, d, n) => boite(sc, P, c, d.map((x) => x + g), croute(n), 0.5);
  const pic = (P, b, d, L, l) => {
    const base = P.p(ajoute(b, fois(norme(d), g))), dir = P.n(d);
    if (lave) pointe(sc, base, dir, L, l, CORNE_LAVE, true, false);
    else cristal(sc, base, dir, L * 1.15, l);
  };
  // les épaulières : deux plaques l'une sous l'autre, trois pics vers le haut et le dehors
  for (const c of ["G", "D"]) {
    const P = T["bras" + c], e = c === "G" ? 1 : -1, x = 6 * e;
    plaque(P, [x + e * 0.3, 23.3, 0], [2.4, 1.3, 2.45], c === "G" ? 1 : 2);
    plaque(P, [x + e * 0.7, 21.3, 0], [2.0, 0.8, 2.35], 3);
    pic(P, [x + e * 0.8, 24.6, 0.4], [e * 0.45, 1, 0.1], 4.2, 1.5);
    pic(P, [x + e * 2.6, 23.4, -0.6], [e, 0.45, -0.2], 3.2, 1.2);
    pic(P, [x - e * 0.6, 24.6, -1.4], [e * 0.1, 1, -0.6], 2.8, 1.1);
  }
  // l'avant-bras du fruit : un brassard, des pics vers le dehors et l'arrière
  const B = T["bras" + cote], xb = 6 * s;
  plaque(B, [xb, 15.4, 0], [2.4, 1.9, 2.4], 4);
  pic(B, [xb + s * 2.4, 16.4, 0], [s, 0.35, 0], 2.8, 1.1);
  pic(B, [xb + s * 2.4, 13.8, 0.6], [s, -0.1, 0.4], 2.2, 0.9);
  pic(B, [xb, 16.2, -2.4], [0, 0.4, -1], 2.4, 1);
  // la poitrine : deux plaques
  for (const e of [-1, 1]) plaque(T.corps, [e * 2, 21.2, 2.1], [1.7, 1.9, 0.3], 5 + (e > 0 ? 1 : 0));
  // les genoux : une plaque, un pic vers l'avant
  for (const c of ["G", "D"]) {
    const J = T["jambe" + c], x = c === "G" ? 2 : -2;
    plaque(J, [x, 6.6, 2.05], [1.9, 1.5, 0.35], c === "G" ? 7 : 8);
    pic(J, [x, 6.8, 2.4], [0, 0.25, 1], 2.2, 1);
  }
  // la crête dans le dos
  for (let q = 0; q < 6; q++) pic(T.corps, [(q % 2 ? 0.5 : -0.5), 23 - q * 1.9, -2.1], [0, 0.6, -1], 3.2 - q * 0.3, 1.6);
}

/** Ce qui vit sur la lave au niveau 3 : des flammes partout, des braises qui tournent autour de lui, de la fumée. */
function vie3Lave(sc, T) {
  const OU = [[T.corps, [3, 24, -1]], [T.corps, [-3, 24, 0]], [T.corps, [3.5, 17, -2]], [T.jambeG, [3, 9, 0]], [T.brasD, [-7, 24, 0]], [T.corps, [-2, 21, -2.2]], [T.jambeD, [-3, 10, -1]]];
  for (let q = 0; q < 9; q++) {
    const [P, b0] = OU[q % OU.length];
    const b = ajoute(b0, [(hache(q, 1, 350) - 0.5) * 3, (hache(q, 2, 350) - 0.5) * 2, (hache(q, 3, 350) - 0.5) * 2]);
    flamme(sc, P.p(b), [0.2 * (hache(q, 4, 350) - 0.5), 1, 0.2 * (hache(q, 5, 350) - 0.5)], 2.4 + 3 * hache(q, 6, 350), 1.2 + 0.7 * hache(q, 7, 350));
  }
  for (let q = 0; q < 12; q++) {
    const a = q * 2.4, r = 12 + 6 * hache(q, 1, 351), y = 2 + 36 * hache(q, 2, 351);
    cube(sc, T.corps.p([Math.cos(a) * r, y, Math.sin(a) * r]), 0.6 + 0.5 * hache(q, 3, 351), [q, a], brille(q % 3 ? LAVE_O : LAVE_J), true);
  }
  for (let q = 0; q < 4; q++) cube(sc, T.tete.p([(hache(q, 1, 352) - 0.5) * 6, 40 + q * 3, -5 - q * 1.5]), 0.9 + 0.3 * q, [q, q * 1.3], FUMEE);
}

/** Ce qui vit sur la glace au niveau 3 : des cristaux, des flocons qui tournent en spirale, de la brume aux pieds. */
function vie3Glace(sc, T) {
  const ECLATS = [[T.corps, [3.5, 15, -2], [0.3, 0.4, -1], 3.2, 1.2], [T.corps, [-4.2, 15, 0], [-1, 0.3, 0], 3.2, 1.2], [T.jambeD, [-4.2, 10, -1.5], [-1, 0.5, -0.5], 2.6, 1.1]];
  for (const [P, b, d, L, l] of ECLATS) cristal(sc, P.p(b), P.n(d), L, l);
  for (let q = 0; q < 24; q++) {
    const a = q * 0.85, r = 10 + 3 * Math.sin(q), y = 1 + q * 1.55;
    cube(sc, T.corps.p([Math.cos(a) * r, y, Math.sin(a) * r * 0.8]), 0.55 + 0.3 * hache(q, 1, 353), [q, a], q % 4 ? BLANC : GLACE_C, true);
  }
  for (let q = 0; q < 10; q++) {
    const a = (q / 10) * TOUR;
    cube(sc, T.corps.p([Math.cos(a) * 6.5, 0.8 + hache(q, 2, 354), Math.sin(a) * 4.5]), 1.6 + hache(q, 3, 354), [q, a], mix(BLANC, GLACE_C, 0.3), true);
  }
}

/**
 * La glace du corps au niveau 3, proche de celle du casque (sinon elle fait trop lisse à côté) : des facettes bordées de
 * givre blanc, une glace plus profonde et tachetée, plus claire le long des bords.
 */
function glace3(i, j, k, v) {
  const f = fissure(i, j, 430 + k, 4);
  if (f < 0.3) return mix(BLANC, GLACE_C, 0.35);
  const c = mix(glace(i, j, k, v), GLACE_P, 0.26 + 0.22 * hache(Math.floor(i / 3), Math.floor(j / 3), 431 + k));
  return f < 0.75 ? mix(c, GLACE_C, 0.35) : c;
}

/**
 * Un fruit éveillé au niveau 3 : tout le corps pris, le casque en tête de dragon, les plaques et les pics, et ce qui vit
 * dessus. o comme pour perso (o.armure : la lave passe par-dessus). Rend les transformations, avec en plus bouche et dir
 * (la paume de la main du fruit, d'où sort le dragon).
 */
function niveau3(sc, sorte, o = {}) {
  const lave = sorte === "lave", s = lave ? 1 : -1, cote = lave ? "G" : "D", g = o.armure ? 1 : 0;
  const T = perso(sc, lave ? PEAU_LAVE : PEAU_GLACE, { moitie: { sorte, couvert: couvert3, ...(lave ? { taille: 5, ouvert: 1.9 } : { texture: glace3 }), ...o.corps3 }, ...o });
  teteDragon(sc, cadre(T.tete, [0, 31.4 + g, 0.6], [0, 0.04, 1], [0, 1, 0], 1.18), sorte, { casque: true });
  plaques(sc, T, lave, g);
  (lave ? vie3Lave : vie3Glace)(sc, T);
  const B = T["bras" + cote];
  return Object.assign(T, { bouche: B.p([s * 6, 11.2, 0]), dir: B.n([0, -1, 0]) });
}

// ------------------------------------------------------------------ le grand dragon
/** Une courbe douce (Catmull-Rom) par les points de ctrl, rééchantillonnée tous les `pas` ; rend les points et la longueur. */
function courbe(ctrl, pas) {
  const dense = [];
  for (let i = 0; i < ctrl.length - 1; i++) {
    const p0 = ctrl[Math.max(0, i - 1)], p1 = ctrl[i], p2 = ctrl[i + 1], p3 = ctrl[Math.min(ctrl.length - 1, i + 2)];
    for (let n = 0; n < 40; n++) {
      const t = n / 40, t2 = t * t, t3 = t2 * t;
      dense.push(p1.map((_, c) => 0.5 * (2 * p1[c] + (p2[c] - p0[c]) * t + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * t2 + (3 * p1[c] - p0[c] - 3 * p2[c] + p3[c]) * t3)));
    }
  }
  dense.push(ctrl[ctrl.length - 1]);
  const cumul = [0];
  for (let i = 1; i < dense.length; i++) cumul.push(cumul[i - 1] + Math.hypot(...sous(dense[i], dense[i - 1])));
  const L = cumul[cumul.length - 1], n = Math.max(2, Math.round(L / pas)), pts = [];
  for (let q = 0, i = 0; q <= n; q++) {
    const s = (q / n) * L;
    while (i < dense.length - 2 && cumul[i + 1] < s) i++;
    const t = (s - cumul[i]) / (cumul[i + 1] - cumul[i] || 1);
    pts.push(dense[i].map((v, c) => v + (dense[i + 1][c] - v) * t));
  }
  return { pts, L };
}

/** Les écailles d'un anneau du corps : de la croûte fissurée (ou de la glace) dessus, un ventre de plaques qui brillent. */
function ecailles(lave, n) {
  if (lave) return (f, u, v, i, j) => (f === "bas" ? brille(mix(LAVE_O, LAVE_J, (j + n) % 2 ? 0.15 : 0.8)) : magma(i + n * 5, j + n * 3, FACES[f] + 300, 2, 0.8));
  return (f, u, v, i, j) => (f === "bas" ? mix(BLANC, GLACE_C, (j + n) % 2 ? 0.15 : 0.6)
    : fissure(i + n * 5, j + n * 3, 410, 2) < 0.22 ? mix(GLACE, GLACE_P, 0.3) : mix(glace(i + n * 3, j, FACES[f] + n, f === "haut" ? 0.1 : v), GLACE_P, 0.3));
}

/**
 * Le dragon géant, sans pattes : la tête au premier point de ctrl, puis le corps de serpent jusqu'au dernier (la queue).
 * R : le rayon du corps. Le corps est fait d'anneaux qui se chevauchent comme des écailles, avec une crête de flammes
 * (ou de cristaux) sur le dos. o.sortie : le bout de la queue est encore dans la gueule de la main (il ne s'affine pas
 * en pointe, il reste un flot). o.gueule, o.souffle : pour la tête. Rend les points du corps.
 */
function grandDragon(sc, ctrl, R, sorte, o = {}) {
  const lave = sorte === "lave";
  const { pts, L } = courbe(ctrl, R * 0.55);
  const N = pts.length;
  const rayon = (t) => {
    if (o.sortie) return R * (t < 0.15 ? 0.82 + 0.18 * (t / 0.15) : 1 - 0.72 * ((t - 0.15) / 0.85) ** 1.4);
    return R * (t < 0.15 ? 0.82 + 0.18 * (t / 0.15) : 1 - 0.93 * ((t - 0.15) / 0.85) ** 1.3);
  };
  for (let i = 0; i < N - 1; i++) {
    const t = i / (N - 1), a = pts[i], b = pts[i + 1];
    const f = norme(sous(a, b)), len = Math.hypot(...sous(a, b)), r = rayon(t);
    const F = cadre(ID, fois(ajoute(a, b), 0.5), f, [0, 1, 0], 1);
    const pas = Math.max(0.5, r / 3);
    boite(sc, F, [0, 0, 0], [r, r * 0.74, len * 0.64], ecailles(lave, i), pas);
    boite(sc, F, [0, 0, 0], [r * 0.74, r, len * 0.6], ecailles(lave, i + 50), pas);
    // la crête du dos
    if (lave) flamme(sc, F.p([0, r * 0.9, 0]), F.n([0, 1, -0.55]), r * (1.1 + 0.9 * hache(i, 1, 360)), r * 0.62);
    else {
      cristal(sc, F.p([0, r * 0.9, 0]), F.n([0, 1, -0.6]), r * (i % 2 ? 0.8 : 1.4), r * 0.5);
      if (i % 3 === 0) for (const s of [-1, 1]) cristal(sc, F.p([s * r * 0.8, r * 0.3, 0]), F.n([s, 0.5, -0.6]), r * 0.7, r * 0.32);
    }
    // ce qui s'en détache : des gouttes de lave et de la fumée, ou du givre et des éclats
    if (i % 3 === 1) {
      const h = hache(i, 2, 361);
      if (lave) {
        cube(sc, F.p([(h - 0.5) * r, -r - 2 - 8 * h, 0]), 0.35 * r * (1 - t * 0.6), [i, h * 6], brille(h > 0.5 ? LAVE_O : LAVE_J), true);
        if (i % 6 === 1) cube(sc, F.p([(h - 0.5) * r * 2, r * (1.8 + 1.5 * h), -r * (1 + h)]), r * (0.35 + 0.3 * h), [i, h * 4], FUMEE);
      } else {
        cube(sc, F.p([(h - 0.5) * r * 2, -r - 3 - 9 * h, 0]), 0.25 * r, [i, h * 6], BLANC, true);
        cristal(sc, F.p([(h - 0.5) * r * 3, r * 1.6 * (h - 0.3), -r * 1.2]), F.n([0, -0.2, -1]), r * 0.5, r * 0.22);
      }
    }
  }
  // des braises (ou des flocons) tout autour du corps
  for (let q = 0; q < 50; q++) {
    const i = Math.floor(hache(q, 1, 362) * (N - 1)), r = rayon(i / (N - 1));
    const p = ajoute(pts[i], [(hache(q, 2, 362) - 0.5) * r * 5, (hache(q, 3, 362) - 0.5) * r * 5, (hache(q, 4, 362) - 0.5) * r * 5]);
    cube(sc, p, 0.12 * R + 0.12 * R * hache(q, 5, 362), [q, q * 1.3], lave ? brille(q % 3 ? LAVE_O : LAVE_J) : q % 3 ? BLANC : GLACE_C, true);
  }
  // le bout de la queue : une touffe de flammes, ou un éventail de cristaux
  if (!o.sortie) {
    const bout = pts[N - 1], d = norme(sous(pts[N - 1], pts[N - 3]));
    const Q = cadre(ID, bout, d, [0, 1, 0], 1);
    for (let q = 0; q < 5; q++) {
      const a = (q - 2) * 0.45;
      if (lave) flamme(sc, bout, Q.n([Math.sin(a), 0.6, Math.cos(a)]), R * (1.2 + 0.5 * (2 - Math.abs(q - 2))), R * 0.45);
      else cristal(sc, bout, Q.n([Math.sin(a), 0.35, Math.cos(a)]), R * (1.1 + 0.5 * (2 - Math.abs(q - 2))), R * 0.35);
    }
  }
  // la tête, plus large que le cou
  const f0 = norme(sous(pts[0], pts[1])), k = R * 0.42;
  teteDragon(sc, cadre(ID, ajoute(pts[0], fois(f0, 3.4 * k)), f0, o.haut ?? [0, 1, 0], k), sorte, { gueule: o.gueule ?? 0.7, souffle: o.souffle, ouvert: 0.6, fumee: true });
  return pts;
}

/** Un flot de lave (ou de givre) de a à b : ce qui reste entre la gueule de la main et la queue du dragon. */
function flot(sc, a, b, lave, r0 = 1, r1 = 3) {
  const d = sous(b, a), L = Math.hypot(...d), n = Math.max(3, Math.round(L / 2.4));
  for (let q = 0; q <= n; q++) {
    const t = q / n, r = r0 + (r1 - r0) * t, p = ajoute(a, fois(d, t));
    for (let k = 0; k < 2; k++) {
      const j = ajoute(p, [(hache(q, k, 363) - 0.5) * r * 1.4, (hache(q, k, 364) - 0.5) * r * 1.4, (hache(q, k, 365) - 0.5) * r * 1.4]);
      cube(sc, j, r * (0.7 + 0.5 * hache(q, k, 366)), [q, k * 2], lave ? brille(mix(LAVE_J, LAVE_O, hache(q, k, 367))) : k ? BLANC : GLACE_C, true);
    }
  }
}

/** Le sol sous le passage du dragon : brûlé, fissuré de lave (ou gelé, en bandes de givre). */
function sillage(pts, lave, large = 16) {
  const seg = [];
  for (let i = 0; i < pts.length - 1; i += 2) seg.push([pts[i][0], pts[i][2], pts[Math.min(pts.length - 1, i + 2)][0], pts[Math.min(pts.length - 1, i + 2)][2], pts[i][1]]);
  return (x, z) => {
    let d = 1e9, hMin = 1e9;
    for (const [ax, az, bx, bz, h] of seg) {
      const vx = bx - ax, vz = bz - az, l2 = vx * vx + vz * vz || 1;
      const t = Math.max(0, Math.min(1, ((x - ax) * vx + (z - az) * vz) / l2));
      const e = Math.hypot(x - ax - vx * t, z - az - vz * t);
      if (e < d) { d = e; hMin = h; }
    }
    // plus le dragon passe bas, plus la bande est large
    const w = large * (1.3 - Math.min(1, hMin / 100)) + 3 * Math.sin(x * 0.4 + z * 0.3);
    if (d > w) return null;
    if (lave) {
      const c = fissure(x + 300, z + 300, 368, 6);
      if (c < 0.5) return brille(mix(LAVE_J, LAVE_O, c * 2));
      if (c < 0.9) return brille(LAVE_R);
      return mix([52, 40, 32], [90, 70, 50], hache(Math.floor(x), Math.floor(z), 369) * 0.5);
    }
    return fissure(x + 300, z + 300, 370, 7) < 0.5 ? GLACE : mix(GLACE_C, BLANC, 0.2 + 0.6 * hache(Math.floor(x), Math.floor(z), 371));
  };
}

// ------------------------------------------------------------------ les vignettes
const POSE_LAVE = { brasG: [-0.45, 0.15], brasD: [0.08, -0.05], tete: [0.05, 0, -0.15] };
const POSE_GLACE = { brasD: [-0.45, -0.15], brasG: [0.08, 0.05], tete: [0.05, 0, 0.15] };
const TENDU = (lave) => (lave
  ? { brasG: [-1.57, 0.06], brasD: [0.35, -0.2], jambeD: 0.3, jambeG: -0.35, tete: [0, 0, -0.1] }
  : { brasD: [-1.57, -0.06], brasG: [0.35, 0.2], jambeD: -0.35, jambeG: 0.3, tete: [0, 0, 0.1] });

function enPied(sorte, texte) {
  return (sc) => {
    const lave = sorte === "lave";
    niveau3(sc, sorte, lave ? POSE_LAVE : POSE_GLACE);
    sc.lueur(lave ? LAVE_O : GLACE, 7, lave ? 1.5 : 1.2);
    legende(sc, texte);
  };
}

/** De près : le casque en tête de dragon, les épaulières et leurs pics. */
function casque(sorte) {
  return (sc) => {
    const lave = sorte === "lave";
    niveau3(sc, sorte, lave ? { brasG: [-0.3, 0.2], brasD: [0.1, -0.05], tete: [0, 0, -0.25] } : { brasD: [-0.3, -0.2], brasG: [0.1, 0.05], tete: [0, 0, 0.25] });
    sc.lueur(lave ? LAVE_O : GLACE, 9, 1.4);
    legende(sc, "LE CASQUE DRAGON, LES PLAQUES ET LES PICS");
  };
}

/** Avec une armure : la lave (la glace) passe par-dessus, le casque et les plaques aussi ; le bras nu la montre. */
function enArmure(sorte) {
  return (sc) => {
    const lave = sorte === "lave";
    niveau3(sc, sorte, { armure: lave ? [92, 219, 213] : [74, 68, 76], ...(lave ? POSE_LAVE : POSE_GLACE) });
    sc.lueur(lave ? LAVE_O : GLACE, 7, lave ? 1.5 : 1.2);
    legende(sc, lave ? "EN DIAMANT : LA LAVE PASSE PAR-DESSUS" : "EN NETHERITE : LA GLACE PASSE PAR-DESSUS");
  };
}

/** Le niveau 2 à côté du niveau 3 : la lave (la glace) finit de tout prendre. */
function montee(sorte) {
  return (sc) => {
    const lave = sorte === "lave";
    niveau2(sc, sorte, { decale: [-15, 0, 0], ...(lave ? POSE_LAVE : POSE_GLACE) });
    niveau3(sc, sorte, { decale: [15, 0, 0], ...(lave ? POSE_LAVE : POSE_GLACE) });
    sc.lueur(lave ? LAVE_O : GLACE, 6, 1.3);
    for (const [x, t] of [[-15, "NIV. 2"], [15, "NIV. 3"]]) {
      const [sx, sy] = sc.vue([x, 62, 0]);
      ecrireTexte(sc.img, sc.w, t, Math.max(4, Math.min(sc.w - 76, Math.round(sx) - 34)), Math.max(4, Math.round(sy) - 20), 3, [40, 40, 50, 255]);
    }
    legende(sc, lave ? "LA LAVE PREND TOUT, SAUF LE BRAS DROIT" : "LA GLACE PREND TOUT, SAUF LE BRAS GAUCHE");
  };
}

/** Un morceau du dragon qui se détache : un bloc de croûte fissurée (ou de glace), tourné au hasard. */
function morceau(sc, c, t, n, lave) {
  const F = cadre(ID, c, [hache(n, 1, 380) - 0.5, hache(n, 2, 380) - 0.5, hache(n, 3, 380) - 0.5], [0, 1, 0], 1);
  boite(sc, F, [0, 0, 0], [t, t * 0.8, t * 0.9], ecailles(lave, n), Math.max(0.5, t / 2.5));
}

/** Il reste appuyé : le bras tendu, et le dragon qui sort de sa main en grandissant. */
function sortie(sorte) {
  return (sc) => {
    const lave = sorte === "lave", s = lave ? 1 : -1;
    sol(sc, -60, -30, 60, 150);
    const T = niveau3(sc, sorte, TENDU(lave));
    const b = T.bouche;
    const ctrl = [[s * -12, 84, 116], [s * -20, 86, 92], [s * -12, 70, 70], [s * 0, 48, 52], [s * 4, 32, 36], ajoute(b, [0, 1, 8]), ajoute(b, [0, 0, 1])];
    grandDragon(sc, ctrl, 12, sorte, { gueule: 0.95, sortie: true });
    // la main éclate de lumière
    for (let q = 0; q < 10; q++) {
      const a = (q / 10) * TOUR;
      const e = norme(ajoute(T.dir, [Math.cos(a) * 0.9, Math.sin(a) * 0.9, 0]));
      if (lave) flamme(sc, b, e, 3 + 3 * hache(q, 1, 374), 1.4);
      else cristal(sc, b, e, 3 + 3 * hache(q, 1, 374), 1.2);
    }
    sc.lueur(lave ? LAVE_O : GLACE, 7, 1.4);
    legende(sc, "IL RESTE APPUYE : LE DRAGON SORT");
  };
}

/**
 * Tant qu'il reste appuyé, le dragon suit son regard : il est parti tout droit, il a tourné la tête vers les cibles, et
 * le dragon vire vers elles. Le regard est une ligne de points, de ses yeux à l'endroit qu'il vise.
 */
function regard(sorte) {
  return (sc) => {
    const lave = sorte === "lave", s = lave ? 1 : -1, R = 15, vise = [s * 170, 0, 190];
    const ctrl = [[s * 96, 40, 166], [s * 64, 50, 150], [s * 30, 56, 134], [s * 8, 52, 106], [s * 3, 40, 72], [s * 6, 30, 44], [s * 6, 23, 26]];
    const { pts } = courbe(ctrl, 4);
    const trace = sillage(pts, lave, 14);
    sol(sc, s > 0 ? -60 : -230, -30, s > 0 ? 230 : 60, 250, (x, z) => {
      if (Math.abs(Math.hypot(x - vise[0], z - vise[2]) - 16) < 1.6) return brille(lave ? LAVE_J : BLANC);
      return trace(x, z);
    });
    const T = niveau3(sc, sorte, { ...TENDU(lave), tete: [0.1, 0, s * 0.75] });
    grandDragon(sc, ctrl, R, sorte, { gueule: 0.8, souffle: lave ? 8 : 6 });
    flot(sc, T.bouche, ctrl[ctrl.length - 1], lave, 0.8, 2.6);
    // le regard : des points de ses yeux à l'endroit qu'il vise
    const oeil = T.tete.p([0, 28, 4]), d = sous(vise, oeil), L = Math.hypot(...d);
    for (let q = 2; q < L / 7; q++) cube(sc, ajoute(oeil, fois(d, (q * 7) / L)), 1.3, [0, 0], brille([255, 255, 255]), false);
    const [mx, my] = sc.vue(ajoute(oeil, fois(d, 0.42)));
    ecrireTexte(sc.img, sc.w, "SON REGARD", Math.round(mx) + 10, Math.round(my) + 8, 2, [40, 40, 50, 255]);
    for (const [x, z] of [[-10, 4], [10, -6], [2, 12]]) {
      const p = ajoute(vise, [x, 0, z]);
      const C = perso(sc, STEVE, { decale: p, lacet: regarde(p, [0, 0, 0]), brasD: [-0.4, -0.3], brasG: [-0.4, 0.3], teinte: lave ? null : GIVRE_C });
      if (!lave) givre(sc, C);
    }
    sc.lueur(lave ? LAVE_O : GLACE, 7, 1.4);
    legende(sc, "TANT QU'IL RESTE APPUYE, LE DRAGON VIRE VERS L'ENDROIT QU'IL REGARDE");
  };
}

/**
 * Il relâche : le dragon explose (5 blocs autour). La lave : une boule de feu, des blocs de magma qui volent, une
 * couronne de flammes au bord, le sol fissuré ; la glace : une gerbe de cristaux dans tous les sens, des pics au bord,
 * les cibles prises dans la glace. Derrière, le reste du corps se défait en morceaux.
 */
function explosion(sorte) {
  return (sc) => {
    const lave = sorte === "lave", s = lave ? 1 : -1, C = [0, 26, 0], RX = 80;
    sol(sc, -160, -120, 160, 110, (x, z) => {
      const r = Math.hypot(x, z), a = Math.atan2(z, x);
      if (r > RX + 6 * Math.sin(a * 7)) return null;
      if (lave) {
        if (r < 34 + 4 * Math.sin(a * 5)) return magma(Math.floor(x + 200), Math.floor(z + 200), 381, 5, 2.2);
        const c = fissure(x + 300, z + 300, 382, 9);
        return c < 0.6 ? brille(mix(LAVE_O, LAVE_R, c / 0.6)) : mix([56, 42, 34], [96, 74, 54], hache(Math.floor(x), Math.floor(z), 383) * 0.5);
      }
      return fissure(x + 300, z + 300, 384, 8) < 0.5 ? GLACE : mix(GLACE_C, BLANC, 0.2 + 0.6 * hache(Math.floor(x), Math.floor(z), 385));
    });
    // le reste du corps, qui se défait en morceaux en remontant vers l'arrière
    const reste = courbe([[s * 14, 40, -30], [s * 40, 70, -70], [s * 20, 104, -110], [s * 50, 130, -150]], 9).pts;
    reste.forEach((p, n) => {
      const t = n / (reste.length - 1);
      for (let q = 0; q < 2; q++) morceau(sc, ajoute(p, [(hache(n, q, 386) - 0.5) * 18 * (1 - t), (hache(n, q, 387) - 0.5) * 14, (hache(n, q, 388) - 0.5) * 18]), 7 * (0.4 + 0.6 * t) * (0.6 + 0.4 * hache(n, q, 389)), n * 3 + q, lave);
    });
    // le bord : une couronne de flammes, ou de grands pics de glace
    for (let q = 0; q < 28; q++) {
      const a = (q / 28) * TOUR, p = [Math.cos(a) * RX * 0.92, 0, Math.sin(a) * RX * 0.92];
      if (lave) flamme(sc, p, [Math.cos(a) * 0.35, 1, Math.sin(a) * 0.35], 10 + 12 * hache(q, 1, 390), 5);
      else cristal(sc, p, [Math.cos(a) * 0.6, 1, Math.sin(a) * 0.6], 14 + 16 * hache(q, 1, 390), 6);
    }
    // le cœur
    const BOULE = 46;
    for (let q = 0; q < 320; q++) {
      const u = norme([hache(q, 1, 391) - 0.5, hache(q, 2, 391) - 0.5, hache(q, 3, 391) - 0.5]);
      const k = Math.cbrt(hache(q, 4, 391)), p = ajoute(C, fois(u, BOULE * k));
      if (lave) {
        const c = k < 0.4 ? [255, 250, 214] : k < 0.65 ? LAVE_J : k < 0.85 ? LAVE_O : LAVE_R;
        cube(sc, p, 7 + 5 * hache(q, 5, 391), [q, q * 0.7], brille(c), true);
      } else cube(sc, p, 5 + 5 * hache(q, 5, 391), [q, q * 0.7], k < 0.5 ? brille(BLANC) : k < 0.8 ? GLACE_C : mix(GLACE, GLACE_P, 0.3), true);
    }
    // ce qui part dans tous les sens : des flammes et des blocs de magma, ou une gerbe de cristaux et des éclats
    for (let q = 0; q < 34; q++) {
      const u = norme([hache(q, 1, 392) - 0.5, Math.abs(hache(q, 2, 392) - 0.35), hache(q, 3, 392) - 0.5]);
      if (lave) {
        flamme(sc, ajoute(C, fois(u, BOULE * 0.85)), u, 16 + 18 * hache(q, 4, 392), 7);
        morceau(sc, ajoute(C, fois(u, BOULE * (1.3 + 0.9 * hache(q, 5, 392)))), 3 + 3 * hache(q, 6, 392), 200 + q, true);
      } else {
        cristal(sc, ajoute(C, fois(u, BOULE * 0.4)), u, BOULE * (1 + 0.8 * hache(q, 4, 392)), 7 + 4 * hache(q, 5, 392));
        morceau(sc, ajoute(C, fois(u, BOULE * (1.6 + 0.8 * hache(q, 5, 392)))), 2 + 2.5 * hache(q, 6, 392), 200 + q, false);
      }
    }
    if (lave) for (let q = 0; q < 10; q++) cube(sc, ajoute(C, [(hache(q, 1, 393) - 0.5) * 50, BOULE + 6 + q * 6, (hache(q, 2, 393) - 0.5) * 40]), 5 + q * 0.9, [q, q * 1.1], FUMEE);
    // les cibles : projetées en feu, ou gelées sur place
    const CIBLES = [[-s * 100, 30, 30, 0.3], [s * 108, 22, 20, -0.4], [-s * 60, 0, 70, 0], [s * 70, 0, 60, 0]];
    const gelees = [];
    for (const [x, y, z, a] of CIBLES) {
      const p = [x, lave ? Math.max(y, 14) : 0, z];
      if (lave) brule(sc, perso(sc, STEVE, { decale: p, lacet: regarde(p, [0, 0, 0]) + Math.PI, penche: -0.8 + a, brasD: [-2.6, -0.4], brasG: [-2.6, 0.4], jambeD: 0.3, jambeG: -0.2 }), 12);
      else {
        givre(sc, perso(sc, STEVE, { decale: p, lacet: regarde(p, [0, 0, 0]), brasD: [-0.7, -0.4], brasG: [-0.7, 0.4], teinte: { c: GLACE_C, k: 0.5 } }));
        gelees.push(p);
      }
    }
    sc.lueur(lave ? LAVE_O : GLACE, 9, 1.5);
    for (const p of gelees) blocDeGlace(sc, p);
    legende(sc, lave ? "IL RELACHE : LE DRAGON EXPLOSE, 5 BLOCS AUTOUR, TOUT BRULE" : "IL RELACHE : LE DRAGON EXPLOSE, 5 BLOCS AUTOUR, TOUT GELE SUR PLACE");
  };
}

// ------------------------------------------------------------------ la planche
const CW = 440, CH = 520, M = 34, HAUT = 600;
const NOIR = [30, 30, 40, 255], ORANGE = [214, 92, 20, 255], BLEU = [40, 110, 200, 255];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

function entete(img, W) {
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, "FRUITS DU DEMON : EVEIL 3", 30, 22, 5, NOIR);
  const colonnes = [
    [30, ORANGE, "MAGU MAGU NO MI : NIVEAU 3", [
      "LISTE LAVE3, TOUCHE EVEIL ENCORE : LA LAVE PREND TOUT",
      "SEUL LE BRAS DROIT GARDE DE LA PEAU (BORD IRREGULIER)",
      "UN CASQUE EN TETE DE DRAGON, DES PLAQUES ET DES PICS",
      "    (EPAULES, AVANT-BRAS, POITRINE, GENOUX, DOS)",
      "MAINTENIR CLIC DROIT : UN DRAGON DE LAVE SORT DE SA MAIN",
      "    ET SUIT SON REGARD TANT QU'IL RESTE APPUYE",
      "RELACHER : IL EXPLOSE, 5 BLOCS AUTOUR, TOUT BRULE 8 S",
    ]],
    [900, BLEU, "HIE HIE NO MI : NIVEAU 3", [
      "LISTE GLACE3, TOUCHE EVEIL ENCORE : LA GLACE PREND TOUT",
      "SEUL LE BRAS GAUCHE GARDE DE LA PEAU (BORD IRREGULIER)",
      "UN CASQUE EN TETE DE DRAGON, DES PLAQUES ET DES PICS",
      "    (EPAULES, AVANT-BRAS, POITRINE, GENOUX, DOS)",
      "MAINTENIR CLIC DROIT : UN DRAGON DE GLACE SORT DE SA MAIN",
      "    ET SUIT SON REGARD TANT QU'IL RESTE APPUYE",
      "RELACHER : IL EXPLOSE, 5 BLOCS AUTOUR, TOUT GELE SUR PLACE 4 S",
    ]],
  ];
  for (const [x, c, titre, lignes] of colonnes) {
    ecrireTexte(img, W, titre, x, 100, 4, c);
    lignes.forEach((t, k) => ecrireTexte(img, W, t, x, 160 + k * 34, 3, NOIR));
  }
  rect(img, W, 30, 420, W - 30, 423, [200, 200, 210, 255]);
  [
    "LES DEUX : EN ROUTE, LE DRAGON FAIT 8 DEGATS A QUI IL TOUCHE (UNE FOIS). L'EXPLOSION : 20 AU MILIEU, 12 AU BORD",
    "IL SE GUIDE 5 S AU PLUS, EN AVANCANT AU PAS. IL EXPLOSE AUSSI S'IL TOUCHE UN MUR. RECHARGE 30 S. RIEN NE RESTE AU SOL",
    "CLIC DROIT COURT, CLIC GAUCHE ET ACCROUPI : COMME AU NIVEAU 2. ARMURE CHEVALIER, LA LAVE (LA GLACE) PASSE PAR-DESSUS",
    "EVEIL 3 : 2 MIN, PUIS 1 MIN DE REPOS. A COTE, LES NIVEAUX 3 : SUSANO 3 GRAND COUP 22, MINATO 2 BIJUU DAMA 14 + 14",
  ].forEach((t, k) => ecrireTexte(img, W, t, 30, 442 + k * 34, 3, NOIR));
}

const PORTRAIT = { echelle: 9, pied: 470 };
const vues = [
  ["NIVEAU 3 : LAVE", enPied("lave", "LA LAVE PREND TOUT LE CORPS"), { ...PORTRAIT, lacet: -28 }],
  ["LAVE : DE DOS", enPied("lave", "LA CRETE ET LE BRAS DROIT A NU"), { ...PORTRAIT, lacet: 145 }],
  ["NIVEAU 3 : GLACE", enPied("glace", "LA GLACE PREND TOUT LE CORPS"), { ...PORTRAIT, lacet: 28 }],
  ["GLACE : DE DOS", enPied("glace", "LA CRETE ET LE BRAS GAUCHE A NU"), { ...PORTRAIT, lacet: -145 }],
  ["LE CASQUE DRAGON : LAVE", casque("lave"), { lacet: -35, tangage: 6, echelle: 12.5, pied: 250, centre: [2, 23, 2] }],
  ["LE CASQUE DRAGON : GLACE", casque("glace"), { lacet: 35, tangage: 6, echelle: 12.5, pied: 250, centre: [-2, 23, 2] }],
  ["AVEC UNE ARMURE : LAVE", enArmure("lave"), { ...PORTRAIT, lacet: -28 }],
  ["AVEC UNE ARMURE : GLACE", enArmure("glace"), { ...PORTRAIT, lacet: 28 }],
  ["NIVEAU 2 PUIS 3 : LAVE", montee("lave"), { lacet: -20, tangage: 6, echelle: 6, pied: 410, centre: [0, 14, 0] }],
  ["NIVEAU 2 PUIS 3 : GLACE", montee("glace"), { lacet: 20, tangage: 6, echelle: 6, pied: 410, centre: [0, 14, 0] }],
  ["MAINTENU : LE DRAGON DE LAVE", sortie("lave"), { lacet: -62, tangage: 10, echelle: 2.05, pied: 290, centre: [0, 52, 90] }],
  ["MAINTENU : LE DRAGON DE GLACE", sortie("glace"), { lacet: 62, tangage: 10, echelle: 2.05, pied: 290, centre: [0, 52, 90] }],
  ["MAINTENU : IL SUIT SON REGARD (LAVE)", regard("lave"), { lacet: -90, tangage: 44, echelle: 2.05, pied: 245, centre: [85, 24, 95] }, 2],
  ["MAINTENU : IL SUIT SON REGARD (GLACE)", regard("glace"), { lacet: 90, tangage: 44, echelle: 2.05, pied: 245, centre: [-85, 24, 95] }, 2],
  ["RELACHE : IL EXPLOSE (LAVE)", explosion("lave"), { lacet: -25, tangage: 20, echelle: 2.3, pied: 300, centre: [0, 40, 0] }, 2],
  ["RELACHE : IL EXPLOSE (GLACE)", explosion("glace"), { lacet: 25, tangage: 20, echelle: 2.3, pied: 300, centre: [0, 40, 0] }, 2],
];

/** Dessine une vignette (largeur span colonnes) et rend son image. */
function vignette([, dessin, o, span = 1]) {
  const sc = scene({ w: CW * span, h: CH, fond: FOND, ...o });
  dessin(sc);
  return sc;
}

export { couvert3, cadre, charniere, boite, teteDragon, plaques, niveau3, courbe, grandDragon, ecailles, morceau, glace3, GLACE_P };

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL && process.argv[2] !== undefined) {
  const v = vues[Number(process.argv[2])];
  const sc = vignette(v);
  ecrirePng(process.argv[3] ?? path.join(DOC, "vignette.png"), sc.w, sc.h, sc.img);
  console.log("ok", v[0]);
} else if (PRINCIPAL) {
  const COLONNES = 4, places = [];
  let c = 0, l = 0;
  for (const v of vues) {
    const span = v[3] ?? 1;
    if (c + span > COLONNES) { c = 0; l++; }
    places.push([c, l]);
    c += span;
  }
  const W = CW * COLONNES, H = HAUT + (M + CH) * (l + 1);
  const img = Buffer.alloc(W * H * 4, 255);
  entete(img, W);
  vues.forEach((v, k) => {
    const [c0, l0] = places[k], sc = vignette(v);
    for (let y = 0; y < CH; y++) sc.img.copy(img, ((HAUT + l0 * (M + CH) + M + y) * W + c0 * CW) * 4, y * sc.w * 4, (y + 1) * sc.w * 4);
    ecrireTexte(img, W, v[0], c0 * CW + 12, HAUT + l0 * (M + CH) + 10, 3, v[0].includes("GLACE") ? BLEU : ORANGE);
  });
  ecrirePng(path.join(DOC, "planche-niveau3.png"), W, H, img);
  console.log("ok", W, H);
}
