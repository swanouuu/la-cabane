// Maquettes de l'éveil 2 des fruits du démon : la touche Éveil, encore une fois, et la moitié du corps devient de la
// lave (la gauche) ou de la glace (la droite). De nouvelles attaques, plus fortes : le clic gauche éclabousse (ou le
// givre s'étend), le clic droit lance un grand poing de magma (ou cinq lances de glace), accroupi, il fait pleuvoir des
// météores (ou lance un faisan de glace géant) ; et qui les frappe prend feu (ou gèle). Équilibré sur les niveaux 2 des
// autres pouvoirs, les espionnes comptant comme un niveau 3.
// node niveau2.mjs → planche-niveau2.png
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, mix, hache, norme, croix, scene, ecrireTexte } from "./moteur.mjs";
import {
  ajoute, fois, TOUR, brille, pave, cube, flamme, cristal, perso, brule, givre, sol, legende, epee, magma, glace, jetLave, croissant,
  aretes, bain, regarde, FACES, LAVE_R, LAVE_O, LAVE_J, GLACE, GLACE_C, BLANC, FUMEE, FOND, PEAU_LAVE, PEAU_GLACE, STEVE, LAVE, GEL,
} from "./rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const FEU = { sorte: "lave" }, GIVRE = { sorte: "glace" };

// ------------------------------------------------------------------ la moitié du corps
/** Ce qui monte de la moitié de lave : des flammes sur l'épaule, la tête et le flanc, de la fumée, des gouttes. */
function vieLave(sc, T, n = 12) {
  const OU = [[T.brasG, () => [5.5 + (Math.random() - 0.5), 23.6, 0]], [T.tete, () => [2.5, 32, 0]], [T.corps, () => [3, 23.8, 0]]];
  for (let q = 0; q < n; q++) {
    const [P, base] = OU[q % 3];
    const b = base();
    b[0] += (hache(q, 1, 201) - 0.5) * 3;
    b[2] += (hache(q, 2, 201) - 0.5) * (q % 3 === 1 ? 6 : 3);
    flamme(sc, P.p(b), [0.25 * (hache(q, 3, 201) - 0.3), 1, 0.2 * (hache(q, 4, 201) - 0.5)], 2.6 + 3.4 * hache(q, 5, 201), 1.3 + 0.8 * hache(q, 6, 201));
  }
  for (let q = 0; q < 4; q++) {
    cube(sc, ajoute(T.tete.p([3, 32, 0]), [(hache(q, 7, 201) - 0.2) * 4, 6 + q * 2.8, (hache(q, 8, 201) - 0.5) * 4]), 1.1 + 0.35 * q, [q, q * 1.3], FUMEE);
  }
  for (let q = 0; q < 2; q++) cube(sc, ajoute(T.jambeG.p([2, 4 + q * 3, 2.3]), [0, -1 - q * 1.5, 0.6]), 0.7, [q, q], brille(LAVE_O), true);
}

/** Ce qui sort de la moitié de glace : des cristaux sur l'épaule, la tête, le genou ; du givre qui flotte. */
function vieGlace(sc, T) {
  const ECLATS = [
    [T.brasD, [-6.5, 24, -0.5], [-0.5, 1, -0.1], 7, 2.2], [T.brasD, [-7.5, 22.5, 1], [-1, 0.6, 0.3], 5.5, 1.8],
    [T.brasD, [-5, 24, 1.5], [-0.2, 1, 0.5], 4.5, 1.6], [T.corps, [-3, 24, -1.5], [-0.3, 1, -0.4], 4, 1.4],
    [T.tete, [-3.4, 32, 1], [-0.4, 1, 0.1], 4.2, 1.4], [T.tete, [-4, 30, -1.5], [-1, 0.5, -0.2], 3, 1.1],
    [T.jambeD, [-4.2, 6, -1.5], [-1, 0.2, -0.5], 3.2, 1.2], [T.brasD, [-8.2, 17, 0], [-1, -0.1, 0], 3, 1.1],
  ];
  for (const [P, b, d, L, l] of ECLATS) cristal(sc, P.p(b), P.n(d), L, l);
  const c = T.corps.p([-4, 18, 0]);
  for (let q = 0; q < 9; q++) {
    const a = q * 1.7, r = 7 + 3 * hache(q, 1, 202);
    cube(sc, ajoute(c, [Math.cos(a) * r - 2, (hache(q, 2, 202) - 0.4) * 22, Math.sin(a) * r * 0.7]), 0.6, [q, q], q % 3 ? BLANC : GLACE_C, true);
  }
}

/** Un fruit éveillé au niveau 2 (sa moitié, sa main, ce qui en sort) ; o comme pour perso. */
function niveau2(sc, sorte, o = {}) {
  const lave = sorte === "lave";
  const T = perso(sc, lave ? PEAU_LAVE : PEAU_GLACE, { moitie: lave ? FEU : GIVRE, main: lave ? LAVE({ flammes: 7, gouttes: false }) : GEL({ givre: false }), ...o });
  (lave ? vieLave : vieGlace)(sc, T);
  return T;
}

// ------------------------------------------------------------------ les attaques
/** Un repère qui regarde vers dir (pour poser un poing ou un oiseau dans le bon sens). */
function repere(centre, dir) {
  const f = norme(dir), d = norme(croix([0, 1, 0], f)), h = croix(f, d);
  const vers = (v) => ajoute(ajoute(fois(d, v[0]), fois(h, v[1])), fois(f, v[2]));
  return { p: (v) => ajoute(centre, vers(v)), n: (v) => norme(vers(v)) };
}

const roche = (k) => (f, u, v, i, j) => magma(i + k * 7, j, FACES[f] + k, 3, 0.9);

/** Le grand poing de magma : un poing fermé, phalanges en avant, plus gros qu'un joueur. */
function poingMagma(sc, centre, dir, s) {
  const T = repere(centre, dir);
  pave(sc, [-s, -s * 0.8, -s * 1.1], [s, s * 0.8, s * 0.6], T, roche(1), 1, 0, true);
  for (let k = 0; k < 4; k++) {
    pave(sc, [-s + (k * s) / 2 + 0.25, -s * 0.15, s * 0.6], [-s + ((k + 1) * s) / 2 - 0.25, s * 0.75, s * 1.15], T, roche(2 + k), 1, 0, true);
  }
  pave(sc, [s * 0.75, -s * 0.85, -s * 0.2], [s * 1.25, -s * 0.15, s * 0.75], T, roche(7), 1, 0, true);
  // des gouttes et des flammes qui s'en arrachent
  for (let q = 0; q < 10; q++) {
    const p = T.p([(hache(q, 1, 203) - 0.5) * 2.4 * s, (hache(q, 2, 203) - 0.5) * 1.8 * s, -s * (1.2 + 1.6 * hache(q, 3, 203))]);
    cube(sc, p, 0.8 + 1.2 * hache(q, 4, 203), [q, q * 2], brille(q % 2 ? LAVE_O : LAVE_J), true);
  }
  for (let q = 0; q < 6; q++) flamme(sc, T.p([(hache(q, 5, 203) - 0.5) * 2 * s, s * 0.8, (hache(q, 6, 203) - 0.5) * 2 * s]), [0, 1, 0], 3 + 3 * hache(q, 7, 203), 2);
}

/** Une roche de magma qui tombe du ciel, avec sa traînée de feu et de fumée. */
function meteore(sc, centre, chute, s, k) {
  const T = repere(centre, chute);
  pave(sc, [-s, -s, -s], [s, s, s], T, roche(10 + k), 1, 0, true);
  const arriere = norme(fois(chute, -1));
  for (let q = 1; q < 9; q++) {
    const p = ajoute(centre, fois(arriere, s + q * s * 0.9));
    cube(sc, ajoute(p, [(hache(q, k, 204) - 0.5) * s, (hache(q, k, 205) - 0.5) * s, 0]), s * (1 - q / 11), [q, k], brille(mix(LAVE_J, LAVE_R, q / 9)), true);
    if (q > 4) cube(sc, ajoute(p, [0, s, 0]), s * 0.9, [q, q], FUMEE);
  }
}

/** Un impact au sol : une gerbe de lave qui retombe en couronne. */
function impact(sc, p, r = 8) {
  for (let q = 0; q < 10; q++) {
    const a = (q / 10) * TOUR;
    flamme(sc, ajoute(p, [Math.cos(a) * r * 0.6, 0, Math.sin(a) * r * 0.6]), [Math.cos(a) * 0.5, 1, Math.sin(a) * 0.5], 4 + 4 * hache(q, 1, 206), 2.2);
    cube(sc, ajoute(p, [Math.cos(a) * r, 3 + 4 * hache(q, 2, 206), Math.sin(a) * r]), 1, [q, a], brille(LAVE_O), true);
  }
}

/** Le faisan de glace : un grand oiseau de cristaux, ailes ouvertes, la longue queue derrière. */
function faisan(sc, centre, dir, k0 = 1) {
  const R = repere(centre, dir);
  const T = { p: (v) => R.p(fois(v, k0)), n: R.n };
  pave(sc, [-5, -4, -12], [5, 4, 10], T, (f, u, v, i, j) => glace(i, j, FACES[f], v), 1, 0, true);
  pave(sc, [-3, -1, 10], [3, 5, 16], T, (f, u, v, i, j) => glace(i + 3, j, FACES[f], v), 1, 0, true);
  cristal(sc, T.p([0, 1.5, 16]), T.n([0, -0.1, 1]), 7 * k0, 3 * k0);
  for (const s of [-1, 1]) {
    for (let k = 0; k < 7; k++) {
      cristal(sc, T.p([s * 4.5, 2, 6 - k * 2.6]), T.n([s, 0.35 - 0.04 * k, -0.15 - 0.18 * k]), (34 - k * 2.5) * k0, (4.2 - k * 0.3) * k0);
    }
  }
  for (let k = 0; k < 5; k++) cristal(sc, T.p([(k - 2) * 1.6, 0, -12]), T.n([(k - 2) * 0.12, 0.08, -1]), (26 + 8 * (1 - Math.abs(k - 2) / 2)) * k0, 3 * k0);
  for (let q = 0; q < 16; q++) {
    cube(sc, T.p([(hache(q, 1, 207) - 0.5) * 50, (hache(q, 2, 207) - 0.5) * 14, -10 - 30 * hache(q, 3, 207)]), 0.8, [q, q], q % 3 ? BLANC : GLACE_C, true);
  }
}

/** Une lance de glace : longue, fine, avec un peu de givre derrière elle. */
function lance(sc, base, dir, L) {
  cristal(sc, base, dir, L, 3.4);
  const arriere = norme(fois(dir, -1));
  for (let q = 0; q < 4; q++) cube(sc, ajoute(base, ajoute(fois(arriere, 2 + q * 3), [(hache(q, 1, 208) - 0.5) * 2, (hache(q, 2, 208) - 0.5) * 2, 0])), 0.6, [q, q], BLANC, true);
}

/** Pris dans un bloc de glace (l'éveil 2 gèle plus fort). */
function blocDeGlace(sc, p) {
  aretes(sc, ajoute(p, [-8, 0, -8]), ajoute(p, [8, 20, 8]));
  bain(sc, ajoute(p, [-8, 0, -8]), ajoute(p, [8, 20, 8]), [176, 222, 255], 0.32);
}

// ------------------------------------------------------------------ les vignettes
const GIVRE_C = { c: GLACE_C, k: 0.42 };

function enPied(sorte) {
  return (sc) => {
    const lave = sorte === "lave";
    niveau2(sc, sorte, lave ? { brasG: [-0.5, 0.15], brasD: [0.08, -0.05], tete: [0.05, 0, -0.15] } : { brasD: [-0.5, -0.15], brasG: [0.08, 0.05], tete: [0.05, 0, 0.15] });
    sc.lueur(lave ? LAVE_O : GLACE, 6, lave ? 1.4 : 1.1);
    legende(sc, lave ? "LA MOITIE GAUCHE EN LAVE" : "LA MOITIE DROITE EN GLACE");
  };
}

/** Avec une armure : la lave (ou la glace) passe par-dessus, elle l'avale de son côté. */
function enArmure(sorte) {
  return (sc) => {
    const lave = sorte === "lave";
    const armure = lave ? [92, 219, 213] : [74, 68, 76];
    niveau2(sc, sorte, { armure, ...(lave ? { brasG: [-0.5, 0.15], brasD: [0.08, -0.05], tete: [0.05, 0, -0.15] } : { brasD: [-0.5, -0.15], brasG: [0.08, 0.05], tete: [0.05, 0, 0.15] }) });
    sc.lueur(lave ? LAVE_O : GLACE, 6, lave ? 1.4 : 1.1);
    legende(sc, lave ? "ARMURE EN DIAMANT : LA LAVE L'AVALE" : "EN NETHERITE : LA GLACE L'AVALE");
  };
}

/** La touche Éveil, encore : la main (niveau 1), puis le bras, puis la moitié du corps. */
function montee(sorte) {
  return (sc) => {
    const lave = sorte === "lave", peau = lave ? PEAU_LAVE : PEAU_GLACE;
    const main = lave ? LAVE({ flammes: 5, gouttes: false }) : GEL({ givre: false });
    const pose = lave ? { brasG: [-0.3, 0.15] } : { brasD: [-0.3, -0.15] };
    perso(sc, peau, { decale: [-24, 0, 0], main, ...pose });
    perso(sc, peau, { decale: [0, 0, 0], main, moitie: { sorte, bras: true }, ...pose });
    const T = perso(sc, peau, { decale: [24, 0, 0], main, moitie: lave ? FEU : GIVRE, ...pose });
    (lave ? vieLave : vieGlace)(sc, T, 8);
    sc.lueur(lave ? LAVE_O : GLACE, 5, 1.2);
    for (const [x, t] of [[-24, "NIV. 1"], [24, "NIV. 2"]]) {
      const [sx, sy] = sc.vue([x, 40, 0]);
      ecrireTexte(sc.img, sc.w, t, Math.max(4, Math.min(sc.w - 76, Math.round(sx) - 34)), Math.max(4, Math.round(sy) - 20), 3, [40, 40, 50, 255]);
    }
    legende(sc, lave ? "LA LAVE MONTE DU BRAS AU CORPS" : "LA GLACE MONTE DU BRAS AU CORPS");
  };
}

/** Clic gauche : le coup d'épée, et ce qui part de la cible vers les deux d'à côté. */
function coup(sorte) {
  return (sc) => {
    const lave = sorte === "lave";
    sol(sc, -40, -16, 40, 64, lave ? null : (x, z) => {
      const r = Math.hypot(x + 2, z - 38);
      return r < 24 + 3 * Math.sin(Math.atan2(z - 38, x) * 6) ? mix(GLACE_C, BLANC, 0.2 + 0.6 * hache(Math.floor(x), Math.floor(z), 209)) : null;
    });
    const T = niveau2(sc, sorte, { brasD: [-1.75, -0.25], brasG: [-0.3, 0.25], jambeD: -0.3, jambeG: 0.25 });
    const pts = epee(sc, T.brasD, "D", 15);
    for (const p of pts) lave ? flamme(sc, p, [0, 1, 0.3], 3, 1.3) : cristal(sc, p, [0, 0.6, 1], 2.4, 1.1);
    const cibles = [[-2, 34], [-20, 44], [17, 46]];
    cibles.forEach(([x, z], k) => {
      const p = [x, 0, z];
      const C = perso(sc, STEVE, { decale: p, lacet: regarde(p, [0, 0, 0]), penche: k ? -0.08 : -0.18, brasD: [-0.4, -0.3], brasG: [-0.4, 0.3], teinte: lave ? null : GIVRE_C });
      lave ? brule(sc, C, k ? 9 : 16) : givre(sc, C);
    });
    // l'éclaboussure : des gouttes de magma qui volent de la cible aux deux autres
    if (lave) {
      for (const [x, z] of cibles.slice(1)) {
        for (let q = 0; q < 7; q++) {
          const t = (q + 0.5) / 7;
          const p = [mix([-2], [x], t)[0], 20 + 12 * Math.sin(t * Math.PI), mix([34], [z], t)[0]];
          cube(sc, p, 1.4 - 0.6 * t, [q, t], brille(q % 2 ? LAVE_O : LAVE_J), true);
        }
      }
    }
    sc.lueur(lave ? LAVE_O : GLACE, 4, 1.3);
    croissant(sc, T.brasD, "D", lave ? [255, 140, 40] : [170, 220, 255]);
    legende(sc, lave ? "+4 DEGATS, ECLABOUSSE A 2 BLOCS" : "+4 DEGATS, LE GIVRE PREND A 2 BLOCS");
  };
}

function grandPoing(sc) {
  sol(sc, -30, -16, 30, 120);
  const T = niveau2(sc, "lave", { brasG: [-1.57, 0.06], brasD: [0.3, -0.15], jambeD: 0.3, jambeG: -0.35 });
  const main = T.brasG.p([6, 11, 0]);
  const cible = perso(sc, STEVE, { decale: [4, 0, 104], lacet: Math.PI, penche: -0.25, brasD: [-1.1, -0.5], brasG: [-1.1, 0.5] });
  brule(sc, cible, 10);
  const poing = [3, 22, 66];
  jetLave(sc, main, ajoute(poing, [0, 0, -14]));
  poingMagma(sc, poing, [0.05, -0.02, 1], 11);
  sc.lueur(LAVE_O, 5, 1.4);
  legende(sc, "24 BLOCS, 12 DEGATS, EXPLOSE, BRULE 6 S");
}

function lances(sc) {
  sol(sc, -40, -16, 40, 110);
  const T = niveau2(sc, "glace", { brasD: [-1.57, -0.06], brasG: [0.3, 0.15], jambeD: -0.35, jambeG: 0.3 });
  const main = T.brasD.p([-6, 11, 0]);
  const cible = [-4, 0, 92];
  const C = perso(sc, STEVE, { decale: cible, lacet: Math.PI, penche: -0.15, brasD: [-0.5, -0.3], brasG: [-0.5, 0.3], teinte: GIVRE_C });
  givre(sc, C);
  cristal(sc, [-6, 18, 82], [0.1, 0.05, 1], 14, 2.4);
  cristal(sc, [0, 24, 81], [-0.15, 0, 1], 12, 2.2);
  for (let k = -2; k <= 2; k++) {
    const a = k * 0.2;
    const dir = [Math.sin(a) - 0.05, -0.02, Math.cos(a)];
    const loin = 30 + 10 * hache(k + 3, 1, 210);
    lance(sc, ajoute(main, fois(dir, loin)), dir, 32);
  }
  sc.lueur(GLACE, 5, 1.2);
  legende(sc, "5 LANCES, 6 DEGATS CHACUNE, TRES LENT");
}

function pluie(sc) {
  const Z = 66;
  const IMPACTS = [[-10, Z - 6], [14, Z + 10], [-2, Z + 22]];
  sol(sc, -60, -20, 60, 120, (x, z) => {
    for (const [ix, iz] of IMPACTS) {
      const r = Math.hypot(x - ix, z - iz);
      if (r < 5) return magma(Math.floor(x + 100), Math.floor(z + 100), 211, 3, 1.6);
      if (r < 8) return mix([90, 140, 50], [40, 30, 24], 0.7);
    }
    return null;
  });
  const T = niveau2(sc, "lave", { brasG: [-2.9, 0.15], brasD: [0.4, -0.3], tete: [-0.6] });
  jetLave(sc, T.brasG.p([6, 11, 0]), [8, 110, 10]);
  for (const [ix, iz] of IMPACTS) impact(sc, [ix, 0, iz], 9);
  const CHUTE = [0.35, -1, 0.25];
  [[-44, 74, Z - 26], [34, 96, Z - 32], [-12, 56, Z - 6], [44, 58, Z + 12], [-36, 104, Z + 22], [12, 116, Z + 30], [-22, 40, Z + 36]].forEach((p, k) => meteore(sc, p, CHUTE, 6 + (k % 3) * 1.5, k));
  for (const [x, z, ang] of [[-14, Z - 2, 0.3], [10, Z + 16, -0.4]]) {
    const C = perso(sc, STEVE, { decale: [x, 0, z], lacet: ang + Math.PI, penche: -0.2, brasD: [-1.5, -0.6], brasG: [-1.5, 0.6] });
    brule(sc, C, 10);
  }
  sc.lueur(LAVE_O, 5, 1.4);
  legende(sc, "3 S DE METEORES, 5 DEGATS PAR IMPACT");
}

function oiseau(sc) {
  sol(sc, -60, -20, 60, 150, (x, z) => {
    if (z < 8 || Math.abs(x - (z - 8) * 0.05) > 14 + 3 * Math.sin(z * 0.4)) return null;
    return mix(GLACE_C, BLANC, 0.2 + 0.6 * hache(Math.floor(x), Math.floor(z), 212));
  });
  for (let k = 0; k < 14; k++) {
    const z = 14 + k * 5.5, s = k % 2 ? 1 : -1;
    cristal(sc, [s * (12 + 3 * hache(k, 1, 213)), -1, z], [s * 0.5, 1, 0.1], 6 + 8 * hache(k, 2, 213), 3);
  }
  niveau2(sc, "glace", { brasD: [-2.2, -0.4], brasG: [-0.5, 0.3], jambeD: -0.3, jambeG: 0.3 });
  const p = [3, 0, 46];
  const C = perso(sc, STEVE, { decale: p, lacet: regarde(p, [0, 0, 0]), brasD: [-0.6, -0.45], brasG: [-0.6, 0.45], teinte: { c: GLACE_C, k: 0.5 } });
  givre(sc, C);
  faisan(sc, [6, 30, 88], [0.06, -0.06, 1], 1.7);
  const q = [-6, 0, 128];
  perso(sc, STEVE, { decale: q, lacet: Math.PI, penche: -0.3, brasD: [-1.6, -0.5], brasG: [-1.6, 0.5] });
  sc.lueur(GLACE, 5, 1.2);
  blocDeGlace(sc, p);
  legende(sc, "24 BLOCS, 12 DEGATS, GELE SUR PLACE 3 S");
}

/** Le passif : qui le frappe prend feu (ou gèle). */
function riposte(sorte) {
  return (sc) => {
    const lave = sorte === "lave";
    sol(sc, -30, -20, 30, 40);
    niveau2(sc, sorte, { lacet: 0, brasD: [-0.2, -0.2], brasG: [-0.2, 0.2] });
    const p = [0, 0, 18];
    const C = perso(sc, STEVE, { decale: p, lacet: Math.PI, penche: 0.1, brasD: [-2.2, -0.1], brasG: [-0.3, 0.2], teinte: lave ? null : GIVRE_C });
    const pts = epee(sc, C.brasD, "D", 13);
    if (lave) {
      brule(sc, C, 16);
    } else {
      givre(sc, C);
      for (const p2 of pts) cristal(sc, p2, [0, 1, 0.2], 2.5, 1.2);
    }
    sc.lueur(lave ? LAVE_O : GLACE, 5, 1.3);
    legende(sc, lave ? "QUI LE FRAPPE PREND FEU 3 S" : "QUI LE FRAPPE GELE, RALENTI 2 S");
  };
}

// ------------------------------------------------------------------ la planche
const CW = 440, CH = 520, M = 34, HAUT = 640;
const NOIR = [30, 30, 40, 255], ORANGE = [214, 92, 20, 255], BLEU = [40, 110, 200, 255];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

function entete(img, W) {
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, "FRUITS DU DEMON : EVEIL 2", 30, 22, 5, NOIR);
  const colonnes = [
    [30, ORANGE, "MAGU MAGU NO MI : NIVEAU 2", [
      "LISTE LAVE2, TOUCHE EVEIL ENCORE : LA MOITIE GAUCHE EN LAVE",
      "CLIC GAUCHE : TOUTE ARME +4 DEGATS, ENFLAMME 6 S,",
      "    ECLABOUSSE 3 DEGATS A 2 BLOCS AUTOUR DE LA CIBLE",
      "CLIC DROIT : GRAND POING DE MAGMA, 24 BLOCS, 12 DEGATS,",
      "    BRULE 6 S, EXPLOSE (4 AUTOUR). RECHARGE 8 S",
      "ACCROUPI : PLUIE DE METEORES LA OU IL VISE (30 BLOCS),",
      "    3 S, 5 DEGATS PAR IMPACT, 15 AU PLUS. RECHARGE 25 S",
      "PASSIF : QUI LE FRAPPE PREND FEU 3 S",
    ]],
    [900, BLEU, "HIE HIE NO MI : NIVEAU 2", [
      "LISTE GLACE2, TOUCHE EVEIL ENCORE : LA MOITIE DROITE EN GLACE",
      "CLIC GAUCHE : TOUTE ARME +4 DEGATS, TRES LENT 4 S,",
      "    LE GIVRE PREND 3 DEGATS A 2 BLOCS AUTOUR DE LA CIBLE",
      "CLIC DROIT : 5 LANCES DE GLACE EN EVENTAIL, 24 BLOCS,",
      "    6 DEGATS CHACUNE (12 AU PLUS), TRES LENT. RECHARGE 8 S",
      "ACCROUPI : UN FAISAN DE GLACE GEANT FONCE TOUT DROIT,",
      "    24 BLOCS, 12 DEGATS, GELE SUR PLACE 3 S. RECHARGE 25 S",
      "PASSIF : QUI LE FRAPPE GELE, RALENTI 2 S",
    ]],
  ];
  for (const [x, c, titre, lignes] of colonnes) {
    ecrireTexte(img, W, titre, x, 100, 4, c);
    lignes.forEach((t, k) => ecrireTexte(img, W, t, x, 160 + k * 34, 3, NOIR));
  }
  rect(img, W, 30, 448, W - 30, 451, [200, 200, 210, 255]);
  [
    "LES DEUX : ARMURE COMME LA MARQUE 2 (FER X2). LA LAVE OU LA GLACE PASSE PAR-DESSUS L'ARMURE PORTEE. RIEN NE RESTE AU SOL",
    "NIVEAU 2 : 2 MIN, PUIS 1 MIN DE REPOS (S'IL RESTE AU NIVEAU 1 : 2 MIN, PUIS 2 MIN)",
    "A COTE : SUSANO 2 FRAPPE 14 TOUTES LES 0,4 S ET ECRASE 16. MARQUE 2 : +3 PARTOUT ET LE CHIDORI. HACHIBI 2 : GRIFFE 13, BOMBE 18",
    "LES ESPIONNES COMPTENT COMME UN NIVEAU 3 (AVEC LE SUSANO 3 : EPEE 16, GRAND COUP 18) : LE FRUIT 2 RESTE EN DESSOUS",
  ].forEach((t, k) => ecrireTexte(img, W, t, 30, 470 + k * 34, 3, NOIR));
}

const ACTION = { echelle: 5, tangage: 18, pied: 310 };
const vues = [
  ["NIVEAU 2 : LAVE", enPied("lave"), { lacet: -28, echelle: 10.5, pied: 490 }],
  ["NIVEAU 2 : GLACE", enPied("glace"), { lacet: 28, echelle: 10.5, pied: 490 }],
  ["AVEC UNE ARMURE : LAVE", enArmure("lave"), { lacet: -28, echelle: 10.5, pied: 490 }],
  ["AVEC UNE ARMURE : GLACE", enArmure("glace"), { lacet: 28, echelle: 10.5, pied: 490 }],
  ["LAVE2 : EVEIL 1 PUIS 2", montee("lave"), { lacet: -18, tangage: 8, echelle: 5.6, pied: 400, centre: [0, 14, 0] }],
  ["GLACE2 : EVEIL 1 PUIS 2", montee("glace"), { lacet: 18, tangage: 8, echelle: 5.6, pied: 400, centre: [0, 14, 0] }],
  ["CLIC GAUCHE : ECLABOUSSE", coup("lave"), { ...ACTION, lacet: 60, centre: [0, 13, 24] }],
  ["CLIC GAUCHE : LE GIVRE S'ETEND", coup("glace"), { ...ACTION, lacet: 60, centre: [0, 13, 24] }],
  ["CLIC DROIT : GRAND POING DE MAGMA", grandPoing, { ...ACTION, echelle: 3.3, lacet: -62, centre: [0, 14, 52] }],
  ["CLIC DROIT : LANCES DE GLACE", lances, { ...ACTION, echelle: 3.3, lacet: 62, centre: [0, 14, 48] }],
  ["ACCROUPI : PLUIE DE METEORES", pluie, { lacet: -38, tangage: 20, echelle: 2.5, pied: 330, centre: [0, 30, 50] }],
  ["ACCROUPI : FAISAN DE GLACE", oiseau, { lacet: 48, tangage: 18, echelle: 2.6, pied: 340, centre: [0, 24, 72] }],
  ["PASSIF : CORPS DE MAGMA", riposte("lave"), { lacet: -80, tangage: 10, echelle: 6.6, pied: 450, centre: [0, 0, 9] }],
  ["PASSIF : CORPS DE GLACE", riposte("glace"), { lacet: 80, tangage: 10, echelle: 6.6, pied: 450, centre: [0, 0, 9] }],
];

// les briques servent aussi à la planche des nouvelles attaques (attaques2.mjs)
export { niveau2, repere, roche, poingMagma, impact, lance, blocDeGlace };

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  const COLONNES = 4, lignes = Math.ceil(vues.length / COLONNES);
  const W = CW * COLONNES, H = HAUT + (M + CH) * lignes;
  const img = Buffer.alloc(W * H * 4, 255);
  entete(img, W);
  vues.forEach(([titre, dessin, o], k) => {
    const c = k % COLONNES, l = Math.floor(k / COLONNES);
    const sc = scene({ w: CW, h: CH, fond: FOND, ...o });
    dessin(sc);
    for (let y = 0; y < CH; y++) sc.img.copy(img, ((HAUT + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(img, W, titre, c * CW + 12, HAUT + l * (M + CH) + 10, 3, titre.includes("GLACE") || titre.includes("GIVRE") ? BLEU : ORANGE);
  });
  ecrirePng(path.join(DOC, "planche-niveau2.png"), W, H, img);
  console.log("ok", W, H);
}
