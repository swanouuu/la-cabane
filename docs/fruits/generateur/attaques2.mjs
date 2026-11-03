// Maquettes des nouveaux clics droits du niveau 2 (demandés le 2026-11-03) : la glace tire une grande flèche de glace
// (au lieu des cinq lances), la lave lance un poing de magma deux fois plus gros, en flammes ; et l'arc du Susano 3
// reprend la même grande flèche, en flammes noires de la couleur du Susano.
// node attaques2.mjs → planche-attaques2.png
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, mix, hache, norme, scene, ecrireTexte } from "./moteur.mjs";
import {
  ajoute, fois, TOUR, brille, pave, cube, pointe, flamme, cristal, perso, brule, givre, sol, legende, glace, jetLave, regarde,
  FACES, LAVE_R, LAVE_O, LAVE_J, GLACE, GLACE_C, BLANC, FUMEE, FOND, STEVE,
} from "./rendu.mjs";
import { niveau2, repere, roche, poingMagma, impact, blocDeGlace } from "./niveau2.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const GIVRE_C = { c: GLACE_C, k: 0.42 };
/** Les flammes noires du Susano (Amaterasu), bordées de sa couleur (ici le violet de Sasuke). */
const NOIR_F = [22, 8, 30], VIOLET = [176, 86, 255];
const feuNoir = (t) => brille(mix(NOIR_F, VIOLET, Math.pow(t, 1.6)));

// ------------------------------------------------------------------ la grande flèche
/**
 * Une grande flèche (4 blocs et demi) dont la pointe est en `bout` et qui file vers `dir` : une hampe, une large tête
 * à facettes et ses quatre barbes, quatre pennes au bout, une spirale autour de la hampe et une traînée derrière.
 * « glace » : du cristal, du givre ; « feu » : des flammes noires qui lèchent la hampe vers l'arrière.
 */
function grandeFleche(sc, bout, dir, matiere, L = 72) {
  const feu = matiere === "feu";
  const tete = 24, T = repere(bout, dir);
  const hampe = feu ? () => brille(NOIR_F) : (f, u, v, i, j) => glace(i + 5, j, FACES[f], v);
  pave(sc, [-1.8, -1.8, -L], [1.8, 1.8, -tete + 2], T, hampe, 1.2, 0, feu);
  // la tête : une pointe large, et quatre barbes qui partent en arrière
  const pointeDe = (b, d, lg, la) => (feu ? pointe(sc, b, d, lg, la, feuNoir, true, true) : cristal(sc, b, d, lg, la));
  pointeDe(T.p([0, 0, -tete]), T.n([0, 0, 1]), tete, 9);
  for (let k = 0; k < 4; k++) {
    const a = (k / 4) * TOUR + TOUR / 8, x = Math.cos(a), y = Math.sin(a);
    pointeDe(T.p([x * 2.5, y * 2.5, -tete + 3]), T.n([x * 0.8, y * 0.8, -1]), 12, 3.4);
    // les pennes, au bout de la hampe
    pointeDe(T.p([x * 1.8, y * 1.8, -L + 16]), T.n([x * 0.6, y * 0.6, -1]), 20, 5);
  }
  // une spirale de givre (ou de braises violettes) autour de la hampe
  for (let q = 0; q < 18; q++) {
    const a = q * 0.95, z = -tete - (q / 18) * (L - tete), r = 4 + 1.2 * Math.sin(q);
    cube(sc, T.p([Math.cos(a) * r, Math.sin(a) * r, z]), 1.1, [q, a], feu ? brille(q % 2 ? VIOLET : [90, 40, 140]) : q % 3 ? BLANC : GLACE_C, true);
  }
  if (feu) {
    // des flammes noires qui lèchent la hampe vers l'arrière
    for (let q = 0; q < 14; q++) {
      const z = -tete - 2 - (q / 14) * (L - tete), a = q * 2.3;
      pointe(sc, T.p([Math.cos(a) * 1.6, Math.sin(a) * 1.6, z]), T.n([Math.cos(a) * 0.5, Math.sin(a) * 0.5 + 0.3, -1]), 8 + 8 * hache(q, 1, 230), 3.4, feuNoir, true, true);
    }
  }
  // la traînée : de la neige (ou des braises) qui s'efface derrière
  for (let q = 0; q < 16; q++) {
    const z = -L - 4 - q * 4.5, s = 2.2 * (1 - q / 18);
    const p = T.p([(hache(q, 2, 231) - 0.5) * 6, (hache(q, 3, 231) - 0.5) * 6, z]);
    cube(sc, p, s, [q, q * 1.7], feu ? brille(q % 3 ? NOIR_F : VIOLET) : q % 2 ? BLANC : GLACE_C, true);
  }
}

/** Le sol gèle sous le passage de la flèche : une bande de givre, et quelques pics de chaque côté. */
function sillageGele(x0, z0, z1) {
  return (x, z) => {
    if (z < z0 || z > z1 || Math.abs(x - x0) > 5 + 2 * Math.sin(z * 0.5)) return null;
    return mix(GLACE_C, BLANC, 0.2 + 0.6 * hache(Math.floor(x), Math.floor(z), 232));
  };
}

// ------------------------------------------------------------------ la glace : la grande flèche
function flecheGlace(sc) {
  sol(sc, -40, -16, 40, 150, sillageGele(-4, 10, 150));
  const T = niveau2(sc, "glace", { brasD: [-1.57, -0.06], brasG: [0.3, 0.15], jambeD: -0.35, jambeG: 0.3 });
  const main = T.brasD.p([-6, 11, 0]);
  for (let k = 0; k < 8; k++) cristal(sc, [(k % 2 ? 1 : -1) * (7 + 2 * hache(k, 1, 233)) - 4, -1, 14 + k * 6], [(k % 2 ? 0.4 : -0.4), 1, 0.15], 4 + 5 * hache(k, 2, 233), 2.4);
  grandeFleche(sc, ajoute(main, [-1, 1, 96]), [0, 0.01, 1], "glace");
  perso(sc, STEVE, { decale: [-6, 0, 140], lacet: Math.PI, penche: -0.1, brasD: [-0.4, -0.3], brasG: [-0.4, 0.3] });
  sc.lueur(GLACE, 5, 1.3);
  legende(sc, "30 BLOCS, 14 DEGATS, ELLE TRAVERSE");
}

function flecheDePres(matiere) {
  return (sc) => {
    sol(sc, -60, -60, 60, 60);
    grandeFleche(sc, [6, 22, 40], [0.05, 0.02, 1], matiere);
    sc.lueur(matiere === "feu" ? VIOLET : GLACE, 6, 1.5);
    legende(sc, matiere === "feu" ? "FLAMMES NOIRES, COULEUR DU SUSANO" : "4 BLOCS ET DEMI, TETE, BARBES, PENNES");
  };
}

/** Elle traverse le premier (gelé dans la glace) et file sur le second (givré). */
function flecheTouche(sc) {
  sol(sc, -40, -16, 40, 170, sillageGele(-2, 0, 170));
  const p = [-2, 0, 40];
  const C = perso(sc, STEVE, { decale: p, lacet: Math.PI, penche: -0.15, brasD: [-0.6, -0.4], brasG: [-0.6, 0.4], teinte: GIVRE_C });
  givre(sc, C);
  blocDeGlace(sc, p);
  // les éclats de glace de l'impact
  for (let q = 0; q < 12; q++) {
    const a = (q / 12) * TOUR;
    cristal(sc, ajoute(p, [Math.cos(a) * 9, 10 + 8 * hache(q, 1, 234), 4 + Math.sin(a) * 9]), [Math.cos(a), 0.4 + hache(q, 2, 234), Math.sin(a) * 0.6 + 0.4], 4 + 3 * hache(q, 3, 234), 1.6);
  }
  const q2 = [2, 0, 118];
  const C2 = perso(sc, STEVE, { decale: q2, lacet: Math.PI, penche: -0.2, brasD: [-1.2, -0.5], brasG: [-1.2, 0.5], teinte: GIVRE_C });
  givre(sc, C2);
  grandeFleche(sc, [0, 20, 108], [0.02, 0, 1], "glace", 66);
  sc.lueur(GLACE, 5, 1.3);
  legende(sc, "GELE SUR PLACE 1,5 S, PUIS 6 AUX SUIVANTS");
}

// ------------------------------------------------------------------ la lave : le poing en flammes
/** Le poing de magma, deux fois plus gros, pris dans les flammes, avec sa traînée de feu et de fumée. */
function poingEnFlammes(sc, centre, dir, s) {
  poingMagma(sc, centre, dir, s);
  const T = repere(centre, dir);
  // des flammes tout autour, tirées vers l'arrière par la vitesse
  for (let q = 0; q < 22; q++) {
    const a = (q / 22) * TOUR, r = s * (0.9 + 0.3 * hache(q, 1, 235));
    flamme(sc, T.p([Math.cos(a) * r, Math.sin(a) * r * 0.85, (hache(q, 2, 235) - 0.6) * s]), T.n([Math.cos(a) * 0.45, Math.sin(a) * 0.45 + 0.2, -1]), s * (0.9 + 0.8 * hache(q, 3, 235)), s * 0.35);
  }
  // la traînée : du feu, puis de la fumée
  for (let q = 1; q < 12; q++) {
    const p = T.p([(hache(q, 4, 235) - 0.5) * s, (hache(q, 5, 235) - 0.5) * s, -s * (1.4 + q * 0.7)]);
    if (q < 7) cube(sc, p, s * (0.9 - q * 0.09), [q, q * 1.3], brille(mix(LAVE_J, LAVE_R, q / 7)), true);
    else cube(sc, ajoute(p, [0, s * 0.3, 0]), s * 0.7, [q, q], FUMEE);
  }
}

function grosPoing(sc) {
  sol(sc, -40, -16, 40, 130);
  const T = niveau2(sc, "lave", { brasG: [-1.57, 0.06], brasD: [0.3, -0.15], jambeD: 0.3, jambeG: -0.35 });
  const main = T.brasG.p([6, 11, 0]);
  const poing = [4, 24, 74];
  jetLave(sc, main, ajoute(poing, [0, 0, -30]));
  poingEnFlammes(sc, poing, [0.05, -0.02, 1], 20);
  const cible = perso(sc, STEVE, { decale: [6, 0, 122], lacet: Math.PI, penche: -0.3, brasD: [-1.3, -0.5], brasG: [-1.3, 0.5] });
  brule(sc, cible, 12);
  sc.lueur(LAVE_O, 6, 1.5);
  legende(sc, "3 BLOCS DE LARGE, EN FLAMMES");
}

/** Le jet du niveau 1, l'ancien poing, et le nouveau, côte à côte, à côté d'un joueur. */
function avantApres(sc) {
  sol(sc, -70, -30, 70, 40);
  perso(sc, STEVE, { decale: [-52, 0, 0], lacet: 0 });
  jetLave(sc, [-40, 18, 0], [-24, 16, 0]);
  poingMagma(sc, [-6, 16, 0], [0, 0, 1], 11);
  poingEnFlammes(sc, [40, 24, 0], [0, 0, 1], 20);
  sc.lueur(LAVE_O, 5, 1.3);
  legende(sc, "JET (NIV. 1), ANCIEN POING, NOUVEAU POING");
}

function explosion(sc) {
  const P = [0, 0, 40];
  sol(sc, -60, -20, 60, 100, (x, z) => {
    const r = Math.hypot(x - P[0], z - P[2]);
    if (r < 12) return brille(mix(LAVE_J, LAVE_R, r / 12));
    if (r < 18) return mix([60, 30, 20], [30, 22, 18], hache(Math.floor(x), Math.floor(z), 236));
    return null;
  });
  impact(sc, P, 18);
  for (let q = 0; q < 14; q++) {
    const a = (q / 14) * TOUR;
    flamme(sc, ajoute(P, [Math.cos(a) * 8, 0, Math.sin(a) * 8]), [Math.cos(a) * 0.3, 1, Math.sin(a) * 0.3], 14 + 12 * hache(q, 1, 237), 5);
  }
  for (let q = 0; q < 6; q++) cube(sc, ajoute(P, [(hache(q, 2, 237) - 0.5) * 20, 30 + q * 6, (hache(q, 3, 237) - 0.5) * 16]), 5 + q, [q, q], FUMEE);
  for (const [x, z, a] of [[-22, 30, 0.6], [20, 52, -0.7]]) {
    const C = perso(sc, STEVE, { decale: [x, 0, z], lacet: regarde([x, 0, z], P) + Math.PI, penche: 0.35, brasD: [-1.8, -0.6], brasG: [-1.8, 0.6] });
    brule(sc, C, 12);
  }
  sc.lueur(LAVE_O, 6, 1.5);
  legende(sc, "14 AU PREMIER, 4 AUTOUR (3,5 BLOCS)");
}

// ------------------------------------------------------------------ le Susano 3 : la flèche de feu
function flecheSusano(sc) {
  sol(sc, -40, -16, 40, 150, (x, z) => (z > 112 && z < 136 && Math.abs(x) < 12 ? brille(mix(NOIR_F, [60, 20, 80], hache(Math.floor(x), Math.floor(z), 238))) : null));
  // le joueur, dans l'ombre violette de son Susano (l'arc est celui du Susano 3)
  const T = perso(sc, STEVE, { brasD: [-1.57, -0.3], brasG: [-1.57, 0.3], jambeD: -0.3, jambeG: 0.3 });
  for (let q = 0; q < 10; q++) {
    const a = (q / 10) * TOUR;
    pointe(sc, [Math.cos(a) * 12, 0, Math.sin(a) * 12 - 4], [Math.cos(a) * 0.2, 1, Math.sin(a) * 0.2], 30 + 14 * hache(q, 1, 239), 6, (t) => brille(mix([120, 50, 200], [200, 140, 255], t)), true, true);
  }
  grandeFleche(sc, ajoute(T.brasD.p([-6, 11, 0]), [3, 1, 90]), [0, 0.01, 1], "feu");
  const C = perso(sc, STEVE, { decale: [0, 0, 124], lacet: Math.PI, penche: -0.25, brasD: [-1.2, -0.5], brasG: [-1.2, 0.5] });
  for (let q = 0; q < 9; q++) {
    const p = C.corps.p([(hache(q, 1, 240) - 0.5) * 10, 12 + 14 * hache(q, 2, 240), 2.5]);
    pointe(sc, p, [0, 1, 0], 6 + 8 * hache(q, 3, 240), 3, feuNoir, true, true);
  }
  sc.lueur(VIOLET, 6, 1.5);
  legende(sc, "MEMES DEGATS : 10 + FEU NOIR 10 S");
}

// ------------------------------------------------------------------ la planche
const CW = 440, CH = 520, M = 34, HAUT = 420;
const NOIR = [30, 30, 40, 255], ORANGE = [214, 92, 20, 255], BLEU = [40, 110, 200, 255], POURPRE = [130, 50, 190, 255];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

function entete(img, W) {
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, "NIVEAU 2 : LES NOUVEAUX CLICS DROITS", 30, 22, 5, NOIR);
  [
    [BLEU, "GLACE : UNE GRANDE FLECHE DE GLACE (AU LIEU DES 5 LANCES), 4 BLOCS ET DEMI DE LONG, TETE A FACETTES ET PENNES"],
    [BLEU, "  30 BLOCS, 14 DEGATS AU PREMIER (GELE SUR PLACE 1,5 S), ELLE LE TRAVERSE : 6 AUX SUIVANTS. RECHARGE 8 S. LE SOL GELE DESSOUS"],
    [ORANGE, "LAVE : LE GRAND POING DE MAGMA, DEUX FOIS PLUS GROS (3 BLOCS DE LARGE), PRIS DANS LES FLAMMES, TRAINEE DE FEU"],
    [ORANGE, "  MEMES DEGATS (14, EXPLOSION 4 AUTOUR), MAIS L'EXPLOSION EST PLUS LARGE (3,5 BLOCS) ET PLUS BELLE. RECHARGE 8 S"],
    [POURPRE, "SUSANO 3 : L'ARC TIRE LA MEME GRANDE FLECHE, EN FLAMMES NOIRES BORDEES DE LA COULEUR DU SUSANO"],
    [POURPRE, "  MEMES DEGATS QU'AUJOURD'HUI (10 + FEU NOIR 10 S, RECHARGE 8 S) : C'EST LE DESSIN QUI CHANGE"],
    [NOIR, "A COTE : FAISAN 14 + GEL 3 S, POING 14, CHIDORI 20, SUSANO 3 GRAND COUP 22. LA FLECHE RESTE UN NIVEAU 2"],
  ].forEach(([c, t], k) => ecrireTexte(img, W, t, 30, 100 + k * 40, 3, c));
}

const ACTION = { echelle: 3.1, tangage: 16, pied: 310 };
const vues = [
  ["CLIC DROIT : GRANDE FLECHE DE GLACE", flecheGlace, { ...ACTION, lacet: 62, centre: [0, 14, 66] }],
  ["LA FLECHE DE GLACE DE PRES", flecheDePres("glace"), { lacet: 70, tangage: 14, echelle: 4.6, pied: 330, centre: [6, 22, 6] }],
  ["ELLE TRAVERSE ET GELE", flecheTouche, { ...ACTION, echelle: 2.8, lacet: 58, centre: [0, 14, 80] }],
  ["SUSANO 3 : GRANDE FLECHE DE FEU", flecheSusano, { ...ACTION, echelle: 2.8, lacet: -58, centre: [0, 18, 64] }],
  ["CLIC DROIT : POING DE FLAMMES", grosPoing, { ...ACTION, echelle: 2.8, lacet: -62, centre: [0, 18, 66] }],
  ["AVANT ET MAINTENANT", avantApres, { lacet: -10, tangage: 10, echelle: 3.6, pied: 330, centre: [0, 18, 0] }],
  ["L'IMPACT", explosion, { lacet: -30, tangage: 26, echelle: 3.4, pied: 330, centre: [0, 14, 40] }],
  ["LA FLECHE DE FEU DE PRES", flecheDePres("feu"), { lacet: -70, tangage: 14, echelle: 4.6, pied: 330, centre: [6, 22, 6] }],
];

const COLONNES = 4, lignes = Math.ceil(vues.length / COLONNES);
const W = CW * COLONNES, H = HAUT + (M + CH) * lignes;
const img = Buffer.alloc(W * H * 4, 255);
entete(img, W);
vues.forEach(([titre, dessin, o], k) => {
  const c = k % COLONNES, l = Math.floor(k / COLONNES);
  const sc = scene({ w: CW, h: CH, fond: FOND, ...o });
  dessin(sc);
  for (let y = 0; y < CH; y++) sc.img.copy(img, ((HAUT + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
  ecrireTexte(img, W, titre, c * CW + 12, HAUT + l * (M + CH) + 10, 3, titre.includes("SUSANO") || titre.includes("FEU DE") ? POURPRE : titre.includes("GLACE") || titre.includes("TRAVERSE") ? BLEU : ORANGE);
});
ecrirePng(path.join(DOC, "planche-attaques2.png"), W, H, img);
console.log("ok", W, H);
