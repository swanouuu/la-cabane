// Maquettes des nouvelles attaques du niveau 3 des fruits (demandées le 2026-11-14 : « ils sont trop en dessous ») :
// on garde le dragon (clic droit maintenu), l'accroupi (chien de l'enfer, capsule) et l'ultime (J) ; on change le clic
// gauche et le clic droit court, et on ajoute accroupi + clic droit et accroupi + clic gauche, comme Ténèbres 3 et
// Lumière 3.
// node attaques3.mjs → planche-attaques3.png ; node attaques3.mjs <n> [fichier.png] → la vignette n seule
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, mix, hache, norme, scene, ecrireTexte } from "./moteur.mjs";
import {
  ajoute, sous, fois, TOUR, ID, brille, pave, cube, flamme, cristal, perso, brule, givre, sol, legende, regarde, croissant,
  fissure, magma, glace, LAVE_R, LAVE_O, LAVE_J, GLACE, GLACE_C, BLANC, FUMEE, FOND, STEVE,
} from "./rendu.mjs";
import { roche, blocDeGlace } from "./niveau2.mjs";
import { cadre, boite, niveau3, glace3, GLACE_P } from "./niveau3.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const GIVRE_C = { c: GLACE_C, k: 0.45 };
const lisse = (t) => { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); };

// ------------------------------------------------------------------ les briques
/** Une colonne posée au sol (x, z), de largeur w et de hauteur h. */
function colonne(sc, x, z, w, h, couleur, y0 = 0, aile = true) {
  if (h <= 0.3) return;
  pave(sc, [x - w / 2, y0, z - w / 2], [x + w / 2, y0 + h, z + w / 2], ID, couleur, Math.max(1, w / 2), 0, aile);
}

/** La peau d'une colonne de magma : croûte fissurée ; la face avant (+z) brille plus (la lave qui coule). */
const peauMagma = (k, avant = 1.6) => (f, u, v, i, j) => magma(i + k * 11, j + k * 5, 500 + k, 3, f === "devant" ? avant : 0.75);
/** La peau d'une colonne de glace (la glace du niveau 3, des arêtes blanches en haut). */
const peauGlace = (k) => (f, u, v, i, j) => (f === "haut" ? mix(BLANC, GLACE_C, 0.3 * hache(i, j, k)) : glace3(i + k * 7, j, k % 6, v));

/** Une cible : un Steve tourné vers p0, et ce qu'il subit (brûle, gelé). */
function cible(sc, p, o = {}) {
  const T = perso(sc, STEVE, { decale: p, lacet: regarde(p, o.vers ?? [0, 0, 0]), brasD: [-0.4, -0.3], brasG: [-0.4, 0.3], ...o.pose, teinte: o.gel ? GIVRE_C : null });
  if (o.feu) brule(sc, T, o.feu);
  if (o.gel) givre(sc, T);
  return T;
}

/** Projeté : en l'air, bras et jambes ballants. */
const VOLE = { penche: -0.9, brasD: [-2.6, -0.4], brasG: [-2.6, 0.4], jambeD: 0.4, jambeG: -0.3 };

/** Une bombe de lave qui file vers dir, sa traînée de feu et de fumée derrière. */
function bombe(sc, p, dir, s, k) {
  const F = cadre(ID, p, dir);
  boite(sc, F, [0, 0, 0], [s, s * 0.85, s * 0.9], roche(20 + k), 1);
  const arriere = norme(fois(dir, -1));
  for (let q = 1; q < 8; q++) {
    const c = ajoute(p, fois(arriere, s + q * s * 1.1));
    cube(sc, ajoute(c, [(hache(q, k, 601) - 0.5) * s, (hache(q, k, 602) - 0.5) * s, 0]), s * 1.4 * (1 - q / 10), [q, k], brille(mix(LAVE_J, LAVE_R, q / 8)), true);
  }
}

/** Une lance de glace (les partisans) : une pointe longue, une hampe de glace, de la neige derrière. */
function partisan(sc, bout, dir, L = 76) {
  const d = norme(dir), F = cadre(ID, bout, fois(d, -1));
  cristal(sc, ajoute(bout, fois(d, -24)), d, 24, 9);
  boite(sc, F, [0, 0, 24 + (L - 24) / 2], [2.2, 2.2, (L - 24) / 2], (f, u, v, i, j) => glace3(i, j, 3, v), 0.7);
  for (let k = 0; k < 4; k++) {
    const a = (k / 4) * TOUR + 0.4;
    cristal(sc, F.p([Math.cos(a) * 2.2, Math.sin(a) * 2.2, 22]), F.n([Math.cos(a), Math.sin(a), 0.6]), 8, 2.4);
  }
  for (let q = 0; q < 5; q++) cube(sc, F.p([(hache(q, 1, 610) - 0.5) * 4, (hache(q, 2, 610) - 0.5) * 4, L + 2 + q * 4]), 1.2 - q * 0.15, [q, q], q % 2 ? BLANC : GLACE_C, true);
}

/** Les poses de base (la lave a sa main à gauche, la glace à droite). */
const TENDU = (lave) => (lave
  ? { brasG: [-1.57, 0.06], brasD: [0.4, -0.2], jambeD: 0.35, jambeG: -0.35, tete: [0, 0, -0.1] }
  : { brasD: [-1.57, -0.06], brasG: [0.4, 0.2], jambeD: -0.35, jambeG: 0.35, tete: [0, 0, 0.1] });

// ------------------------------------------------------------------ clic gauche : la lave, le poing volcanique
function poingLave(sc) {
  sol(sc, -50, -20, 50, 90, (x, z) => {
    const r = Math.hypot(x - 5, z - 32);
    if (r > 15 + 2 * Math.sin(Math.atan2(z - 32, x) * 5)) return null;
    const c = fissure(x + 200, z + 200, 603, 5);
    return c < 0.6 ? brille(mix(LAVE_J, LAVE_O, c)) : mix([60, 44, 36], [96, 74, 54], hache(Math.floor(x), Math.floor(z), 604) * 0.6);
  });
  const T = niveau3(sc, "lave", { ...TENDU(true), buste: 0.2 });
  const b = T.bouche;
  // l'éclat du coup : des flammes qui partent en étoile de son poing
  for (let q = 0; q < 16; q++) {
    const a = (q / 16) * TOUR;
    flamme(sc, ajoute(b, [0, 0, 2]), norme([Math.cos(a) * 0.9, Math.sin(a) * 0.9, 0.55]), 6 + 6 * hache(q, 1, 605), 2.4);
  }
  for (let q = 0; q < 12; q++) cube(sc, ajoute(b, [(hache(q, 1, 606) - 0.5) * 22, (hache(q, 2, 606) - 0.5) * 18, 8 + 18 * hache(q, 3, 606)]), 1 + 1.2 * hache(q, 4, 606), [q, q], brille(q % 2 ? LAVE_J : LAVE_O), true);
  cible(sc, [5, 0, 32], { feu: 18, pose: { penche: -0.35, brasD: [-1.2, -0.5], brasG: [-1.2, 0.5] } });
  sc.lueur(LAVE_O, 6, 1.4);
  legende(sc, "+6 DEGATS, IL BRULE 5 S");
}

/** Au 3e coup de suite : un pilier de magma jaillit sous la cible et l'envoie en l'air ; ceux d'à côté aussi. */
function pilierLave(sc) {
  const C = [4, 0, 46];
  sol(sc, -60, -20, 70, 110, (x, z) => {
    const r = Math.hypot(x - C[0], z - C[2]);
    if (r > 34 + 3 * Math.sin(Math.atan2(z - C[2], x) * 7)) return null;
    const c = fissure(x + 200, z + 200, 607, 6);
    return c < 0.7 ? brille(mix(LAVE_J, LAVE_R, c / 0.7)) : mix([56, 40, 32], [90, 70, 50], hache(Math.floor(x), Math.floor(z), 608) * 0.6);
  });
  niveau3(sc, "lave", { ...TENDU(true), buste: 0.2 });
  // le pilier : des colonnes de magma, plus hautes au milieu, qui se défont en haut
  for (let x = -12; x <= 12; x += 4) for (let z = -12; z <= 12; z += 4) {
    const r = Math.hypot(x, z);
    if (r > 13) continue;
    const h = 64 * (1 - (r / 13) ** 2 * 0.6) * (0.85 + 0.3 * hache(x, z, 609));
    colonne(sc, C[0] + x, C[2] + z, 4.2, h, peauMagma(Math.abs(x * 3 + z), 1.8));
  }
  for (let q = 0; q < 22; q++) {
    const a = (q / 22) * TOUR, r = 12;
    flamme(sc, [C[0] + Math.cos(a) * r, 4 + 50 * hache(q, 1, 611), C[2] + Math.sin(a) * r], [Math.cos(a) * 0.4, 1, Math.sin(a) * 0.4], 8 + 10 * hache(q, 2, 611), 3.4);
  }
  for (let q = 0; q < 18; q++) {
    const u = norme([hache(q, 1, 612) - 0.5, 0.6 + hache(q, 2, 612), hache(q, 3, 612) - 0.5]);
    cube(sc, ajoute([C[0], 66, C[2]], fois(u, 10 + 24 * hache(q, 4, 612))), 2 + 3 * hache(q, 5, 612), [q, q], q % 3 ? brille(LAVE_O) : brille(LAVE_J), true);
  }
  for (let q = 0; q < 6; q++) cube(sc, [C[0] + (hache(q, 1, 613) - 0.5) * 20, 92 + q * 6, C[2] + (hache(q, 2, 613) - 0.5) * 14], 3 + q * 0.5, [q, q], FUMEE);
  cible(sc, [C[0], 74, C[2]], { feu: 16, pose: VOLE });
  cible(sc, [C[0] - 34, 18, C[2] + 8], { feu: 10, vers: [C[0], 0, C[2]], pose: { ...VOLE, penche: -0.6 } });
  cible(sc, [C[0] + 36, 14, C[2] - 4], { feu: 10, vers: [C[0], 0, C[2]], pose: { ...VOLE, penche: -0.6 } });
  sc.lueur(LAVE_O, 7, 1.5);
  legende(sc, "AU 3E COUP : PILIER DE MAGMA, 8 A 2 BLOCS");
}

// ------------------------------------------------------------------ clic gauche : la glace, l'épée de glace
/** L'épée de glace qui pousse de sa main : une lame plate, des arêtes blanches, une garde de cristaux. */
function epeeGlace(sc, T, L = 40) {
  const b = T.bouche, F = cadre(ID, b, T.dir, T.brasD.n([1, 0, 0]));
  boite(sc, F, [0, 0, L / 2], [0.8, 3.2, L / 2], (f, u, v, i, j) => (f === "devant" || f === "dos" ? glace3(i, j, 2, v)
    : (v < 0.15 || v > 0.85 ? BLANC : mix(GLACE_C, BLANC, 0.5))), 0.8);
  cristal(sc, F.p([0, 0, L - 0.5]), F.n([0, 0, 1]), 10, 6);
  for (const s of [-1, 1]) cristal(sc, F.p([0, s * 2.5, 1]), F.n([0, s, -0.35]), 6, 2.4);
  for (let q = 0; q < 10; q++) cube(sc, F.p([(hache(q, 1, 614) - 0.5) * 8, (hache(q, 2, 614) - 0.5) * 10, L * hache(q, 3, 614)]), 0.8, [q, q], BLANC, true);
  return F;
}

function epeeCoup(sc) {
  sol(sc, -50, -20, 50, 90, (x, z) => (Math.hypot(x + 4, z - 36) < 22 + 3 * Math.sin(x * 0.7) ? mix(GLACE_C, BLANC, 0.2 + 0.6 * hache(Math.floor(x), Math.floor(z), 615)) : null));
  const T = niveau3(sc, "glace", { brasD: [-1.75, -0.35], brasG: [-0.3, 0.25], jambeD: -0.3, jambeG: 0.25, tete: [0, 0, 0.1] });
  epeeGlace(sc, T);
  cible(sc, [-4, 0, 50], { gel: true, pose: { penche: -0.2 } });
  sc.lueur(GLACE, 6, 1.3);
  croissant(sc, T.brasD, "D", [170, 220, 255]);
  legende(sc, "+6 DEGATS, LENTEUR 3 S");
}

/** Au 3e coup de suite : la cible est prise dans un bloc de glace, qui éclate en éclats sur ceux d'à côté. */
function eclatGlace(sc) {
  const C = [-4, 0, 44];
  sol(sc, -60, -20, 60, 110, (x, z) => {
    const r = Math.hypot(x - C[0], z - C[2]);
    if (r > 36 + 4 * Math.sin(Math.atan2(z - C[2], x - C[0]) * 6)) return null;
    return fissure(x + 300, z + 300, 616, 6) < 0.4 ? GLACE : mix(GLACE_C, BLANC, 0.2 + 0.6 * hache(Math.floor(x), Math.floor(z), 617));
  });
  const T = niveau3(sc, "glace", { brasD: [-1.75, -0.35], brasG: [-0.3, 0.25], jambeD: -0.3, jambeG: 0.25, tete: [0, 0, 0.1] });
  epeeGlace(sc, T, 32);
  cible(sc, C, { gel: true, pose: { brasD: [-0.7, -0.4], brasG: [-0.7, 0.4] } });
  // les éclats : des cristaux qui partent du bloc dans tous les sens
  for (let q = 0; q < 26; q++) {
    const a = (q / 26) * TOUR + 0.2, h = 4 + 20 * hache(q, 1, 618);
    const u = norme([Math.cos(a), 0.1 + 0.4 * hache(q, 2, 618), Math.sin(a)]);
    cristal(sc, ajoute([C[0], h, C[2]], fois(u, 10 + 8 * hache(q, 3, 618))), u, 6 + 9 * hache(q, 4, 618), 2.4);
  }
  for (const [x, z] of [[2, 42], [44, 30]]) cible(sc, [C[0] + x, 0, C[2] + z], { gel: true, vers: C, pose: { penche: -0.25 } });
  sc.lueur(GLACE, 7, 1.3);
  blocDeGlace(sc, C);
  legende(sc, "AU 3E COUP : GELE 1,5 S, ECLATS 6 A 2 BLOCS");
}

// ------------------------------------------------------------------ clic droit : la vague de magma
function vagueLave(sc) {
  const AV = 150, X = 52;
  // derrière la vague, la coulée qui refroidit déjà (rien ne reste : elle redevient de l'herbe)
  sol(sc, -90, -30, 90, 260, (x, z) => {
    if (z < 18 || z > AV - 40 || Math.abs(x) > X + 3 * Math.sin(z * 0.3)) return null;
    const t = (z - 18) / (AV - 58), c = fissure(x + 300, z + 300, 620, 7);
    if (c < 0.25 + 0.6 * t) return brille(mix(LAVE_O, LAVE_R, c));
    return mix([52, 40, 34], [92, 70, 52], hache(Math.floor(x), Math.floor(z), 621) * 0.6);
  });
  const T = niveau3(sc, "lave", { brasG: [-1.1, 0.25], brasD: [0.4, -0.2], jambeD: 0.4, jambeG: -0.4, buste: 0.3, tete: [0.1, 0, -0.1] });
  // de sa main à la vague, un flot de lave au ras du sol
  const b = T.bouche;
  for (let q = 0; q < 14; q++) {
    const t = q / 13, p = [mix([b[0]], [0], t)[0], mix([b[1]], [3], t)[0], mix([b[2]], [AV - 46], t)[0]];
    cube(sc, ajoute(p, [(hache(q, 1, 622) - 0.5) * 6 * t, 0, 0]), 2 + 5 * t, [q, q], brille(mix(LAVE_J, LAVE_O, hache(q, 2, 622))), true);
  }
  // la vague : des colonnes qui montent jusqu'à la crête, plus hautes au milieu
  for (let x = -X; x <= X; x += 4) for (let z = AV - 44; z <= AV; z += 4) {
    const t = (z - (AV - 44)) / 44, bord = 1 - (Math.abs(x) / (X + 6)) ** 3;
    const h = (6 + 72 * t ** 1.5) * bord * (0.9 + 0.2 * hache(x, z, 623));
    colonne(sc, x, z, 4.2, h, peauMagma(Math.abs(x) + z, 2));
  }
  // la crête qui s'enroule vers l'avant, des gouttes et des flammes qui s'en arrachent
  for (let x = -X + 4; x <= X - 4; x += 5) {
    const bord = 1 - (Math.abs(x) / (X + 6)) ** 3, R = 16 * bord, cy = 62 * bord, cz = AV - 4;
    for (let q = 0; q < 9; q++) {
      const th = -0.4 + q * 0.33, p = [x, cy + R * Math.cos(th), cz + R * Math.sin(th)];
      cube(sc, p, 6.5 - q * 0.45, [q, x], q < 6 ? brille(mix(LAVE_J, LAVE_O, q / 6)) : mix([70, 40, 30], LAVE_R, 0.5), true);
    }
    flamme(sc, [x, cy + R, cz], [0, 1, 0.5], 8 + 10 * hache(x, 1, 624), 3.2);
    for (let q = 0; q < 2; q++) cube(sc, [x + (hache(x, q, 625) - 0.5) * 6, cy + R + 8 + 16 * hache(x, q, 626), cz + 10 + 14 * hache(x, q, 627)], 1.6, [x, q], brille(LAVE_J), true);
  }
  // ceux qu'elle emporte, et celui qui va la prendre
  cible(sc, [-14, 58, AV + 8], { feu: 14, pose: { ...VOLE, penche: -0.7 } });
  cible(sc, [22, 44, AV + 6], { feu: 14, pose: { ...VOLE, penche: -1.1 } });
  cible(sc, [4, 0, AV + 52], { pose: { brasD: [-2.4, -0.3], brasG: [-2.4, 0.3], penche: -0.12 } });
  sc.lueur(LAVE_O, 8, 1.5);
  legende(sc, "20 BLOCS DEVANT LUI : 14 DEGATS, EMPORTE 6 BLOCS, BRULE 6 S. RIEN NE RESTE AU SOL");
}

// ------------------------------------------------------------------ clic droit : la vague gelée
function vagueGlace(sc) {
  const OUV = Math.PI / 6, R0 = 22, R1 = 236;
  const dans = (x, z) => { const r = Math.hypot(x, z), a = Math.atan2(x, z); return r > R0 && r < R1 && Math.abs(a) < OUV; };
  sol(sc, -150, -30, 150, 280, (x, z) => {
    const r = Math.hypot(x, z), a = Math.atan2(x, z);
    if (r < R1 + 20 && Math.abs(a) < OUV + 0.08 && r > 6) return fissure(x + 300, z + 300, 630, 8) < 0.45 ? GLACE : mix(GLACE_C, BLANC, 0.2 + 0.6 * hache(Math.floor(x), Math.floor(z), 631));
    return null;
  });
  const T = niveau3(sc, "glace", { brasD: [-1.35, -0.1], brasG: [-0.6, 0.3], jambeD: -0.35, jambeG: 0.35, buste: 0.25 });
  for (let q = 0; q < 10; q++) {
    const a = (q / 10) * TOUR;
    cristal(sc, T.bouche, norme(ajoute(T.dir, [Math.cos(a) * 0.8, Math.sin(a) * 0.8, 0])), 4 + 3 * hache(q, 1, 632), 1.3);
  }
  // la mer gelée : basse d'abord (jusqu'à la taille), puis la grande vague figée au bout du cône
  const CIBLES = [[-26, 112], [24, 128], [-4, 150]];
  for (let x = -130; x <= 130; x += 5) for (let z = R0; z <= R1; z += 5) {
    if (!dans(x, z)) continue;
    const r = Math.hypot(x, z), a = Math.abs(Math.atan2(x, z)) / OUV, bord = 1 - a ** 4;
    let h = (12 + 6 * hache(x, z, 633)) * lisse((r - R0) / 30);
    if (r > 170) h += 78 * lisse((r - 170) / 55) * bord;
    // autour des cibles, la glace monte jusqu'à la taille
    for (const [cx, cz] of CIBLES) if (Math.hypot(x - cx, z - cz) < 11) h = Math.max(h, 16 + 3 * hache(x, z, 634));
    colonne(sc, x, z, 5.2, h * Math.max(0.3, bord), peauGlace(Math.abs(x) + z));
  }
  // la crête figée, qui retombe vers l'avant en pics et en stalactites
  for (let q = 0; q < 34; q++) {
    const a = -OUV * 0.92 + (q / 33) * OUV * 1.84, bord = 1 - (Math.abs(a) / OUV) ** 4, r = R1 - 8;
    const p = [Math.sin(a) * r, 82 * bord + 4, Math.cos(a) * r];
    const avant = [Math.sin(a), 0, Math.cos(a)];
    cristal(sc, p, norme(ajoute(avant, [0, 0.9, 0])), 12 + 16 * hache(q, 1, 635), 6);
    cristal(sc, ajoute(p, fois(avant, 8)), norme(ajoute(avant, [0, -0.8, 0])), 10 + 12 * hache(q, 2, 635), 4);
    if (q % 2) cristal(sc, ajoute(p, [0, -18, 0]), norme(ajoute(avant, [0, 0.2, 0])), 8 + 8 * hache(q, 3, 635), 3);
  }
  for (const [x, z] of CIBLES) cible(sc, [x, 0, z], { gel: true, pose: { brasD: [-2.2, -0.4], brasG: [-1.2, 0.4], penche: -0.1 } });
  for (let q = 0; q < 40; q++) cube(sc, [(hache(q, 1, 636) - 0.5) * 200, 20 + 90 * hache(q, 2, 636), 60 + 180 * hache(q, 3, 636)], 1.2, [q, q], q % 3 ? BLANC : GLACE_C, true);
  sc.lueur(GLACE, 8, 1.3);
  legende(sc, "UN CONE DE 15 BLOCS : 12 DEGATS, COINCES DANS LA GLACE 2 S, PUIS TOUT ECLATE ET FOND");
}

// ------------------------------------------------------------------ accroupi + clic droit : le volcan
function volcan(sc) {
  const C = [0, 0, 190], RB = 84, H = 96, RC = 16;
  sol(sc, -150, -30, 150, 310, (x, z) => {
    // la fissure qui file de son poing jusqu'au volcan, et le sol brûlé autour
    const zig = 5 * Math.sin(z * 0.2) + 3 * Math.sin(z * 0.53);
    if (z > 8 && z < C[2] - RB + 6 && Math.abs(x - zig) < 2.4 + 1.2 * hache(Math.floor(z), 1, 640)) return brille(mix(LAVE_J, LAVE_O, hache(Math.floor(z), 2, 640)));
    const r = Math.hypot(x - C[0], z - C[2]);
    if (r < RB + 30 + 6 * Math.sin(Math.atan2(z - C[2], x) * 5)) {
      const c = fissure(x + 300, z + 300, 641, 8);
      return c < 0.5 ? brille(mix(LAVE_O, LAVE_R, c * 2)) : mix([56, 42, 34], [96, 74, 54], hache(Math.floor(x), Math.floor(z), 642) * 0.5);
    }
    return null;
  });
  niveau3(sc, "lave", { brasG: [-0.75, 0.15], brasD: [0.3, -0.2], buste: 0.55, jambeD: 0.3, jambeG: -0.2, tete: [-0.3, 0, 0] });
  // le cône, en colonnes ; des coulées qui descendent ses flancs
  for (let x = -RB; x <= RB; x += 4) for (let z = -RB; z <= RB; z += 4) {
    const r = Math.hypot(x, z);
    if (r > RB) continue;
    const a = Math.atan2(z, x), coulee = Math.abs(Math.sin(a * 3.5)) > 0.93;
    const h = r < RC ? H - 8 : H * (1 - (r - RC) / (RB - RC)) ** 0.9 * (0.92 + 0.16 * hache(x, z, 643));
    colonne(sc, C[0] + x, C[2] + z, 4.2, h, r < RC ? () => brille(mix(LAVE_J, [255, 250, 214], hache(x, z, 644))) : coulee ? peauMagma(x + z, 2.4) : (f, u, v, i, j) => magma(i + x, j + z, 645, 4, f === "haut" ? 0.5 : 0.3));
  }
  // l'éruption : une colonne de flammes, des bombes qui retombent, la fumée
  for (let q = 0; q < 26; q++) {
    const a = (q / 26) * TOUR, r = 4 + 8 * hache(q, 1, 646);
    flamme(sc, [C[0] + Math.cos(a) * r, H - 4 + 50 * hache(q, 2, 646), C[2] + Math.sin(a) * r], [Math.cos(a) * 0.2, 1, Math.sin(a) * 0.2], 20 + 28 * hache(q, 3, 646), 8);
  }
  const BOMBES = [[-80, 140, 150, -0.6], [90, 130, 190, -0.4], [-50, 170, 250, -0.2], [70, 90, 270, -0.9], [-110, 70, 220, -1], [30, 190, 150, 0.2], [120, 50, 230, -1.2]];
  BOMBES.forEach(([x, y, z, dy], k) => bombe(sc, [x, y, z], norme([x - C[0], dy * 60, z - C[2]]), 4 + 2 * hache(k, 1, 647), k));
  for (const [x, z] of [[-110, 170], [100, 270], [-60, 300]]) {
    for (let q = 0; q < 8; q++) { const a = (q / 8) * TOUR; flamme(sc, [x + Math.cos(a) * 6, 0, z + Math.sin(a) * 6], [Math.cos(a) * 0.5, 1, Math.sin(a) * 0.5], 6 + 6 * hache(q, 1, 648), 3); }
  }
  for (let q = 0; q < 14; q++) cube(sc, [C[0] + (hache(q, 1, 649) - 0.5) * 50, 170 + q * 6, C[2] + (hache(q, 2, 649) - 0.5) * 40], 4 + q * 0.5, [q, q], FUMEE);
  cible(sc, [C[0] + 6, 168, C[2] - 10], { feu: 16, pose: VOLE });
  cible(sc, [-96, 0, 140], { feu: 12, vers: C, pose: { penche: -0.3 } });
  cible(sc, [96, 0, 222], { feu: 12, vers: C, pose: { penche: -0.3 } });
  sc.lueur(LAVE_O, 9, 1.5);
  legende(sc, "LA OU IL VISE (25 BLOCS) : 12 AU MILIEU ET EN L'AIR, PUIS 3 S DE BOMBES : 5 CHACUNE, 15 AU PLUS");
}

// ------------------------------------------------------------------ accroupi + clic droit : les partisans
function partisans(sc) {
  const C = [0, 0, 150], R = 50;
  sol(sc, -150, -30, 150, 310, (x, z) => {
    const r = Math.hypot(x - C[0], z - C[2]);
    if (Math.abs(r - R) < 2.5) return BLANC;
    if (r < R) return fissure(x + 300, z + 300, 650, 8) < 0.45 ? GLACE : mix(GLACE_C, BLANC, 0.2 + 0.6 * hache(Math.floor(x), Math.floor(z), 651));
    return null;
  });
  const T = niveau3(sc, "glace", { brasD: [-2.9, -0.15], brasG: [0.3, 0.2], tete: [-0.35, 0, 0.1], jambeD: -0.1, jambeG: 0.1 });
  // de sa main levée, une spirale de givre qui monte
  for (let q = 0; q < 26; q++) {
    const a = q * 0.7, y = T.bouche[1] + 4 + q * 4;
    cube(sc, [T.bouche[0] + Math.cos(a) * 5, y, T.bouche[2] + Math.sin(a) * 5], 1.4 - q * 0.03, [q, a], q % 3 ? BLANC : GLACE_C, true);
  }
  // en haut, l'anneau de lances qui se forme ; en bas, celles qui sont tombées
  const N = 12;
  for (let k = 0; k < N; k++) {
    const a = (k / N) * TOUR + 0.2, r = R * 0.72;
    if (k % 2 === 0) {
      partisan(sc, [C[0] + Math.cos(a) * r, 120 + 8 * Math.sin(a * 2), C[2] + Math.sin(a) * r], [-Math.cos(a) * 0.15, -1, -Math.sin(a) * 0.15]);
    } else {
      const p = [C[0] + Math.cos(a) * r * 0.9, 0, C[2] + Math.sin(a) * r * 0.9];
      partisan(sc, ajoute(p, [0, -8, 0]), [Math.cos(a) * 0.12, -1, Math.sin(a) * 0.12]);
      for (let q = 0; q < 7; q++) { const b = (q / 7) * TOUR; cristal(sc, ajoute(p, [Math.cos(b) * 4, 0, Math.sin(b) * 4]), [Math.cos(b), 0.6, Math.sin(b)], 4 + 5 * hache(q, k, 652), 2.2); }
    }
  }
  // une qui tombe au milieu, sa traînée de neige
  partisan(sc, [C[0] + 4, 50, C[2] - 4], [0.05, -1, 0.02], 90);
  for (let q = 0; q < 50; q++) cube(sc, [C[0] + (hache(q, 1, 653) - 0.5) * 110, 20 + 130 * hache(q, 2, 653), C[2] + (hache(q, 3, 653) - 0.5) * 120], 1.2, [q, q], q % 3 ? BLANC : GLACE_C, true);
  const gelees = [[-22, 140], [26, 164], [6, 118]];
  for (const [x, z] of gelees) cible(sc, [x, 0, z], { gel: true, vers: [0, 0, 0], pose: { brasD: [-2.4, -0.4], brasG: [-2.4, 0.4] } });
  sc.lueur(GLACE, 8, 1.3);
  blocDeGlace(sc, [gelees[0][0], 0, gelees[0][1]]);
  legende(sc, "LA OU IL VISE (25 BLOCS) : 12 LANCES TOMBENT, 5 CHACUNE (15 AU PLUS), GELE 1 S");
}

// ------------------------------------------------------------------ accroupi + clic gauche : la comète
function comete(sc) {
  const P = [0, 22, 120];
  sol(sc, -110, -100, 110, 260, (x, z) => {
    if (z > P[2] - 4 || z < -90) return null;
    const w = 7 + 3 * Math.sin(z * 0.3);
    if (Math.abs(x) > w) return null;
    const c = fissure(x + 300, z + 300, 660, 5);
    return c < 0.7 ? brille(mix(LAVE_J, LAVE_R, c / 0.7)) : mix([56, 42, 34], [96, 74, 54], hache(Math.floor(x), Math.floor(z), 661) * 0.5);
  });
  // lui, couché, le poing de lave en avant ; tout autour, la tête de la comète
  niveau3(sc, "lave", { decale: sous(P, [0, 12, 0]), penche: 1.4, brasG: [-3.05, 0.05], brasD: [-0.1, -0.15], jambeD: 0.05, jambeG: -0.05, tete: [-0.9, 0, 0] });
  const nez = ajoute(P, [0, 0, 30]);
  // devant le poing, un cœur blanc-jaune ; autour de lui, des flammes couchées vers l'arrière (on le voit dedans)
  for (let q = 0; q < 26; q++) {
    const u = norme([hache(q, 1, 662) - 0.5, hache(q, 2, 662) - 0.5, hache(q, 3, 662) - 0.5]);
    cube(sc, ajoute(nez, fois(u, 6 * hache(q, 4, 662))), 3 + 3 * hache(q, 5, 662), [q, q], brille(mix([255, 250, 214], LAVE_J, hache(q, 6, 662))), true);
  }
  // des flammes qui partent de ses pieds vers l'arrière, et quelques-unes qui lèchent son dos
  for (let q = 0; q < 22; q++) {
    const a = (q / 22) * TOUR, r = 3 + 7 * hache(q, 1, 664);
    flamme(sc, [P[0] + Math.cos(a) * r, P[1] + Math.sin(a) * r * 0.8, P[2] - 14 - 8 * hache(q, 2, 664)], norme([Math.cos(a) * 0.3, Math.sin(a) * 0.3 + 0.1, -1]), 16 + 16 * hache(q, 3, 664), 5);
  }
  for (let q = 0; q < 8; q++) flamme(sc, [P[0] + (hache(q, 1, 665) - 0.5) * 6, P[1] + 4, P[2] - 10 + q * 4], [0, 0.6, -1], 6 + 5 * hache(q, 2, 665), 2.4);
  // la traînée : des flammes, des braises, de la fumée qui monte
  for (let q = 0; q < 34; q++) {
    const z = P[2] - 30 - q * 6, t = q / 34, r = 10 * (1 - t) + 3;
    const p = [(hache(q, 1, 663) - 0.5) * r * 2, P[1] - 6 * t + (hache(q, 2, 663) - 0.5) * r * 1.6, z];
    cube(sc, p, (8 - 5 * t) * (0.6 + 0.5 * hache(q, 3, 663)), [q, q * 0.7], brille(mix(LAVE_J, LAVE_R, t)), true);
    if (q % 2) flamme(sc, ajoute(p, [0, 2, 0]), [0, 1, -0.4], 6 + 8 * (1 - t), 3);
  }
  cible(sc, [44, 12, 30], { feu: 12, vers: [0, 0, 30], pose: { ...VOLE, penche: -0.7 } });
  cible(sc, [52, 26, 70], { feu: 12, vers: [0, 0, 70], pose: { ...VOLE, penche: -0.8 } });
  cible(sc, [4, 0, P[2] + 70], { vers: P, pose: { brasD: [-2.4, -0.3], brasG: [-2.4, 0.3], penche: -0.12 } });
  sc.lueur(LAVE_O, 9, 1.5);
  legende(sc, "15 BLOCS EN LIGNE : 10 ET BRULE A QUI IL TRAVERSE (EJECTES SUR LE COTE), 6 A L'ARRIVEE");
}

// ------------------------------------------------------------------ accroupi + clic gauche : la glissade
function glissade(sc) {
  const xc = (z) => 22 * Math.sin(z / 60), Z1 = 240, P = 150;
  sol(sc, -110, -30, 110, 320, (x, z) => (z > -4 && z < Z1 + 30 && Math.abs(x - xc(z)) < 24 ? mix(GLACE_C, BLANC, 0.3 + 0.5 * hache(Math.floor(x), Math.floor(z), 670)) : null));
  // la piste : une dalle de glace qui serpente, bordée de cristaux
  for (let z = 0; z <= Z1; z += 4) for (let x = -12; x <= 12; x += 4) colonne(sc, xc(z) + x, z, 4.2, 4 + (Math.abs(x) > 10 ? 2 : 0), peauGlace(z + x));
  for (let z = 6; z <= Z1; z += 12) for (const s of [-1, 1]) cristal(sc, [xc(z) + s * 15, 2, z], [s * 0.8, 1, -0.2], 5 + 6 * hache(z, s + 2, 671), 2.6);
  // lui, en train de glisser, penché, les bras en arrière
  const dz = 1, dx = (22 / 60) * Math.cos(P / 60);
  niveau3(sc, "glace", { decale: [xc(P), 6, P], lacet: Math.atan2(dx, dz), penche: 0.35, brasD: [0.9, -0.3], brasG: [0.9, 0.3], jambeD: -0.45, jambeG: 0.45, buste: 0.2 });
  // la gerbe de neige derrière ses pieds
  for (let q = 0; q < 30; q++) {
    const s = q % 2 ? 1 : -1, t = hache(q, 1, 672);
    cube(sc, [xc(P - 10 - t * 30) + s * (6 + 20 * t), 6 + 14 * Math.sin(t * Math.PI), P - 10 - t * 30], 1.4 + 1.6 * (1 - t), [q, q], q % 3 ? BLANC : GLACE_C, true);
  }
  // ceux qu'il a passés, gelés sur le côté
  const gelees = [[xc(70) - 28, 70], [xc(105) + 30, 105]];
  for (const [x, z] of gelees) cible(sc, [x, 0, z], { gel: true, vers: [xc(z), 0, z], pose: { penche: -0.25, brasD: [-1.4, -0.4], brasG: [-1.4, 0.4] } });
  // l'arrivée : une gerbe de pics
  for (let q = 0; q < 22; q++) {
    const a = (q / 22) * TOUR;
    cristal(sc, [xc(Z1) + Math.cos(a) * 8, 4, Z1 + Math.sin(a) * 8], [Math.cos(a), 0.9 + 0.5 * hache(q, 1, 673), Math.sin(a)], 14 + 16 * hache(q, 2, 673), 5);
  }
  sc.lueur(GLACE, 8, 1.3);
  for (const [x, z] of gelees) blocDeGlace(sc, [x, 0, z]);
  legende(sc, "18 BLOCS DE GLISSE : 8 ET GELE 1,5 S A QUI IL TOUCHE, 6 A L'ARRIVEE. LA PISTE FOND DERRIERE LUI");
}

// ------------------------------------------------------------------ la planche
const CW = 440, CH = 520, M = 34, HAUT = 660;
const NOIR = [30, 30, 40, 255], ORANGE = [214, 92, 20, 255], BLEU = [40, 110, 200, 255];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

function entete(img, W) {
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, "FRUITS DU DEMON : LES NOUVELLES ATTAQUES DU NIVEAU 3", 30, 22, 5, NOIR);
  const colonnes = [
    [30, ORANGE, "MAGU MAGU NO MI (LAVE 3)", [
      "CLIC GAUCHE : LE POING VOLCANIQUE, +6, BRULE 5 S",
      "    AU 3E COUP DE SUITE : PILIER DE MAGMA, 8 A 2 BLOCS, EN L'AIR",
      "CLIC DROIT : LA VAGUE DE MAGMA, 20 BLOCS DEVANT LUI",
      "    14, EMPORTE 6 BLOCS, BRULE 6 S. RECHARGE 10 S",
      "ACCROUPI + CLIC DROIT : LE VOLCAN, LA OU IL VISE (25 BLOCS)",
      "    12 AU MILIEU, PUIS DES BOMBES 3 S (5, 15 AU PLUS). 20 S",
      "ACCROUPI + CLIC GAUCHE : LA COMETE, 15 BLOCS EN LIGNE",
      "    10 A QUI IL TRAVERSE, 6 A L'ARRIVEE. RECHARGE 12 S",
    ]],
    [900, BLEU, "HIE HIE NO MI (GLACE 3)", [
      "CLIC GAUCHE : L'EPEE DE GLACE, +6, LENTEUR 3 S",
      "    AU 3E COUP DE SUITE : GELE 1,5 S, ECLATS 6 A 2 BLOCS",
      "CLIC DROIT : LA VAGUE GELEE, UN CONE DE 15 BLOCS",
      "    12, COINCES 2 S, PUIS TOUT ECLATE. RECHARGE 10 S",
      "ACCROUPI + CLIC DROIT : LES PARTISANS (25 BLOCS)",
      "    12 LANCES : 5 CHACUNE (15 AU PLUS), GELE 1 S. 20 S",
      "ACCROUPI + CLIC GAUCHE : LA GLISSADE, 18 BLOCS",
      "    8 ET GELE 1,5 S A QUI IL TOUCHE, 6 A L'ARRIVEE. 12 S",
    ]],
  ];
  for (const [x, c, titre, lignes] of colonnes) {
    ecrireTexte(img, W, titre, x, 100, 4, c);
    lignes.forEach((t, k) => ecrireTexte(img, W, t, x, 150 + k * 34, 3, NOIR));
  }
  rect(img, W, 30, 434, W - 30, 437, [200, 200, 210, 255]);
  [
    "ON GARDE : CLIC DROIT MAINTENU (LE DRAGON), ACCROUPI (CHIEN DE L'ENFER / CAPSULE), J (L'ULTIME), LES PASSIFS",
    "ON CHANGE : LE CLIC GAUCHE (AVANT +4 ET 3 A 2 BLOCS) ET LE CLIC DROIT COURT (AVANT LE POING / LA FLECHE DU NIVEAU 2)",
    "ON AJOUTE : ACCROUPI + CLIC DROIT ET ACCROUPI + CLIC GAUCHE, COMME TENEBRES 3, LUMIERE 3 ET MINATO 3",
    "A COTE : TENEBRES 3 +6 / LANCES 14 / OMBRES 3 X 5, LUMIERE 3 +6 / RAYON 16 / CROIX 15, VENT 3 +6 ET +5, FOUDRE 3 16 + 8",
    "LES DEGATS SONT AVANT LE RENFORT DES COMBATS (X 1,6 A X 2,4), COMME POUR LES AUTRES POUVOIRS",
  ].forEach((t, k) => ecrireTexte(img, W, t, 30, 456 + k * 34, 3, NOIR));
}

const PORTRAIT = { echelle: 5.6, tangage: 12, pied: 330 };
const LARGE = { tangage: 16, pied: 300 };
const vues = [
  ["CLIC GAUCHE : LAVE", poingLave, { ...PORTRAIT, lacet: -78, centre: [0, 16, 16] }],
  ["3E COUP : LAVE", pilierLave, { ...PORTRAIT, echelle: 3.3, lacet: -62, pied: 300, centre: [0, 40, 24] }],
  ["CLIC GAUCHE : GLACE", epeeCoup, { ...PORTRAIT, lacet: 78, centre: [0, 16, 18] }],
  ["3E COUP : GLACE", eclatGlace, { ...PORTRAIT, echelle: 4.2, lacet: 62, pied: 320, centre: [0, 16, 24] }],
  ["CLIC DROIT : LA VAGUE DE MAGMA", vagueLave, { ...LARGE, echelle: 2.7, lacet: -64, centre: [0, 36, 92] }, 2],
  ["CLIC DROIT : LA VAGUE GELEE (GLACE)", vagueGlace, { ...LARGE, echelle: 2.3, lacet: 60, tangage: 20, centre: [0, 34, 128] }, 2],
  ["ACCROUPI + CLIC DROIT : LE VOLCAN (LAVE)", volcan, { ...LARGE, echelle: 1.75, lacet: -58, centre: [0, 86, 120] }, 2],
  ["ACCROUPI + CLIC DROIT : LES PARTISANS (GLACE)", partisans, { ...LARGE, echelle: 2, lacet: 60, pied: 280, centre: [0, 74, 96] }, 2],
  ["ACCROUPI + CLIC GAUCHE : LA COMETE (LAVE)", comete, { ...LARGE, echelle: 3.2, lacet: -72, pied: 270, centre: [0, 24, 84] }, 2],
  ["ACCROUPI + CLIC GAUCHE : LA GLISSADE (GLACE)", glissade, { ...LARGE, echelle: 3, lacet: 66, tangage: 22, pied: 270, centre: [0, 14, 125] }, 2],
];

/** Dessine une vignette (largeur span colonnes) et rend son image. */
function vignette([, dessin, o, span = 1]) {
  const sc = scene({ w: CW * span, h: CH, fond: FOND, ...o });
  dessin(sc);
  return sc;
}

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
  ecrirePng(path.join(DOC, "planche-attaques3.png"), W, H, img);
  console.log("ok", W, H);
}
