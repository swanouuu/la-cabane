// Les petits outils de construction communs aux six missions (sur un Monde de commun.mjs) : murs creux, dalles,
// arbres, palmiers, cerisiers, bambous, toits japonais aux coins relevés, lanternes, gardes avec leur champ de vision.
import { hache } from "../../spies/generateur/moteur.mjs";
import { lampe, vision, ronde } from "./commun.mjs";

/** Un pavé plein. */
export const bloc = (m, x0, y0, z0, x1, y1, z1, nom) => m.remplir(x0, y0, z0, x1, y1, z1, nom);
/** Une dalle horizontale (sol, plafond). */
export const dalle = (m, x0, z0, x1, z1, y, nom) => m.remplir(x0, y, z0, x1, y, z1, nom);
/** Les quatre murs d'une boîte (sans sol ni plafond) ; nom peut être une fonction (x, y, z) → nom. */
export function murs(m, x0, y0, z0, x1, y1, z1, nom) {
  for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) for (let z = z0; z <= z1; z++) {
    if (x !== x0 && x !== x1 && z !== z0 && z !== z1) continue;
    m.set(x, y, z, typeof nom === "function" ? nom(x, y, z) : nom);
  }
}
/** Vide un pavé (portes, fenêtres). */
export const vider = (m, x0, y0, z0, x1, y1, z1) => m.remplir(x0, y0, z0, x1, y1, z1, null);

/** Un arbre rond : tronc, boule de feuilles. */
export function arbre(m, x, y, z, h = 5, feuilles = "feuilles", r = 2.4, tronc = "tronc") {
  for (let k = 0; k < h; k++) m.set(x, y + k, z, tronc);
  const R = Math.ceil(r);
  for (let dx = -R; dx <= R; dx++) for (let dy = -R; dy <= R; dy++) for (let dz = -R; dz <= R; dz++) {
    const d = Math.hypot(dx, dy * 1.2, dz);
    if (d <= r && !(d > r - 0.8 && hache(x + dx, y + dy, z + dz) < 0.35) && !m.plein(x + dx, y + h + dy, z + dz)) m.set(x + dx, y + h + dy, z + dz, feuilles);
  }
}

/** Un palmier : tronc qui penche un peu, palmes en étoile. */
export function palmier(m, x, y, z, h = 6) {
  for (let k = 0; k < h; k++) m.set(x + (k > h * 0.6 ? 1 : 0), y + k, z, "tronc");
  const tx = x + 1, ty = y + h;
  m.set(tx, ty, z, "palme");
  for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [-1, -1], [1, -1], [-1, 1]]) {
    m.set(tx + dx, ty, z + dz, "palme");
    m.set(tx + 2 * dx, ty - (Math.abs(dx) + Math.abs(dz) > 1 ? 1 : 0), z + 2 * dz, "palme");
    if (Math.abs(dx) + Math.abs(dz) === 1) m.set(tx + 3 * dx, ty - 1, z + 3 * dz, "palme");
  }
}

/** Un bosquet de bambous. */
export function bambous(m, x0, z0, x1, z1, y = 0, dens = 0.3, graine = 1) {
  for (let x = x0; x <= x1; x++) for (let z = z0; z <= z1; z++) {
    if (hache(x, z, graine) > dens) continue;
    const h = 6 + Math.floor(hache(x, z, graine + 1) * 6);
    for (let k = 0; k < h; k++) m.set(x, y + k, z, "bambou");
    m.set(x, y + h, z, "feuilles");
    if (hache(x, z, graine + 2) < 0.5) m.set(x + 1, y + h - 1, z, "feuilles");
  }
}

/**
 * Un toit japonais à quatre pans (irimoya simplifié) au-dessus du rectangle x0..x1, z0..z1, posé à y : il déborde d'un
 * bloc, monte par marches, et ses quatre coins se relèvent d'un cran.
 */
export function toitJapon(m, x0, z0, x1, z1, y, nom = "tuileGrise", deborde = 1) {
  let a = x0 - deborde, b = x1 + deborde, c = z0 - deborde, d = z1 + deborde, k = 0;
  while (a <= b && c <= d) {
    for (let x = a; x <= b; x++) for (let z = c; z <= d; z++) {
      const bord = x === a || x === b || z === c || z === d;
      if (k === 0 && !bord) continue;
      m.set(x, y + k, z, nom);
    }
    if (k === 0) for (const [cx, cz] of [[a, c], [a, d], [b, c], [b, d]]) m.set(cx, y + 1, cz, nom);
    a++; b--; c++; d--; k++;
    if (b - a < 1 || d - c < 1) {
      // le faîte
      for (let x = a - 1; x <= b + 1; x++) for (let z = c - 1; z <= d + 1; z++) if (x >= x0 - deborde && z >= z0 - deborde && x <= x1 + deborde && z <= z1 + deborde) m.set(x, y + k, z, nom);
      break;
    }
  }
  return y + k;
}

/** Une lanterne (bloc qui brille) et sa lumière. */
export function lanterne(m, x, y, z, nom = "lanternePapier", o = {}) {
  m.set(x, y, z, nom);
  const c = nom === "lanterneRouge" ? [1.0, 0.45, 0.3] : nom.startsWith("neon") ? { neonRose: [1.0, 0.4, 0.8], neonBleu: [0.4, 0.8, 1.0], neonJaune: [1.0, 0.9, 0.4], neonVert: [0.4, 1.0, 0.6] }[nom] : [1.0, 0.74, 0.38];
  const couleur = nom === "lanterneRouge" ? [255, 110, 70] : nom.startsWith("neon") ? c.map((v) => v * 255) : [255, 214, 140];
  lampe(m, x + 0.5, y + 0.5, z + 0.5, { r: 6, c, k: 0.85, couleur, ...o });
}

/**
 * Un garde (ou un PNJ) : on le pose dans le monde, avec son champ de vision et sa ronde s'il en a.
 * o : skin, x, y, z, lacet, pose, arme, lanterne, bulle ("!" ou "?"), vue ({ portee, angle }), ronde ([[x, z]…]).
 */
export function garde(m, o) {
  m.perso(o);
  if (o.vue) vision(m, o, o.vue);
  if (o.ronde) ronde(m, o.ronde, Math.round(o.y) - 1, { ferme: o.rondeFermee ?? true });
  if (o.lanterne) lampe(m, o.x + Math.sin((o.lacet ?? 0) * Math.PI / 180) * 0.6, o.y + 1.2, o.z + Math.cos((o.lacet ?? 0) * Math.PI / 180) * 0.6,
    { r: 6.5, c: o.lanterne === "rouge" ? [1.0, 0.45, 0.3] : [1.0, 0.74, 0.38], k: 0.95, halo: false });
}
