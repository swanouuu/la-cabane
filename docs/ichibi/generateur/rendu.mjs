// Maquettes du mode Ichibi (Shukaku, le démon du sable). Touche Éveil : la moitié droite du corps devient Ichibi — un
// grand bras de sable griffu, l'oreille, l'œil, des piques sur l'épaule et la queue tout de suite —, avec un bord qui
// n'est jamais droit. Clic gauche : un coup du bras d'Ichibi. Clic droit : la main l'attrape (clic droit encore : elle
// serre fort ; clic gauche : elle le jette). Shift : un nuage de sable sous ses pieds, pour voler 30 s.
// node rendu.mjs → ../planche-ichibi.png (les briques viennent des maquettes des fruits du démon)
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, PARTIES, texels, clair, mix, hache, norme, croix, scene, ecrireTexte } from "../../fruits/generateur/moteur.mjs";
import { ajoute, sous, fois, rotX, rotY, TOUR, ID, pave, cube, pointe, perso, sol, legende, FACES, FOND, HERBE, SKINS, STEVE } from "../../fruits/generateur/rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const rotZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];

// le beige rosé de la première image (mesuré dessus), un poil plus foncé que le sable jaune d'avant
const SABLE = [214, 186, 160];
const SABLE_C = [228, 204, 180];
const SABLE_F = [182, 152, 130];
const QUEUE = [180, 150, 128];
const ENCRE = [44, 64, 112];
const GRIFFE = [246, 240, 224];
const NOIR = [16, 14, 20];
const VERT = [206, 198, 70];
const VERT_C = [232, 226, 122];
const VERT_F = [128, 120, 40];
const PUPILLE = [26, 18, 12];
const TOUCHE = { c: [255, 60, 50], k: 0.34 };
const PEAU = SKINS.efe;
const griffe = (t) => mix(GRIFFE, [206, 196, 172], t * 0.6);

/** Un bruit doux, de -1 à 1 (comme celui des fruits) : pour des bords et des traits qui ne soient pas droits. */
function bruit(a, b, k) {
  const un = (x, y, kk) => {
    const i = Math.floor(x), j = Math.floor(y), fx = x - i, fy = y - j, l = (t) => t * t * (3 - 2 * t);
    const h = (p, q) => hache(p, q, kk) * 2 - 1;
    const haut = h(i, j) + (h(i + 1, j) - h(i, j)) * l(fx), bas = h(i, j + 1) + (h(i + 1, j + 1) - h(i, j + 1)) * l(fx);
    return haut + (bas - haut) * l(fy);
  };
  return 0.7 * un(a / 3.2, b / 3.2, k) + 0.3 * un(a / 1.4, b / 1.4, k + 9);
}

/** Le sable d'Ichibi en (a, b) (pixels du modèle) : beige, un grain fin, et des traits d'encre bleue qui ondulent. */
function sable(a, b, k, base = SABLE, encre = true) {
  if (encre) {
    // la distance à la ligne de niveau 0 du bruit (divisée par sa pente : un trait d'épaisseur égale, sans taches)
    const f = 0.72, e = 0.25, u = a * f + 7 * k, v = b * f - 3 * k;
    const n = bruit(u, v, 400 + k);
    const gx = (bruit(u + e * f, v, 400 + k) - bruit(u - e * f, v, 400 + k)) / (2 * e);
    const gy = (bruit(u, v + e * f, 400 + k) - bruit(u, v - e * f, 400 + k)) / (2 * e);
    const coupe = bruit(a * 0.7 - b * 0.4 + 11 * k, b * 0.7 + a * 0.35, 450 + k);
    if (Math.abs(n) / Math.max(Math.hypot(gx, gy), 1e-3) < 0.42 && coupe > -0.2) return ENCRE;
  }
  const g = hache(Math.floor(a * 2 + 400), Math.floor(b * 2 + 400), 470 + k);
  return mix(mix(base, SABLE_C, 0.4 * g), SABLE_F, g > 0.92 ? 0.6 : 0);
}

/** La couleur d'un pavé de sable (cases de pas pixels). */
const motif = (k, base = SABLE, encre = true, pas = 0.5) => (f, u, v, i, j) => sable(i * pas, j * pas, k + FACES[f] * 3, base, encre);

// ------------------------------------------------------------------ la moitié du corps
/**
 * L'œil d'Ichibi, à la place de l'œil droit (le visage est coupé en cases de 1/4 de pixel : une texture 256×256 en
 * jeu), comme sur la première image : une tache noire presque rectangle, un peu en biais, avec une pointe qui part vers
 * le haut et le dehors (une plus petite en bas, vers le nez), un petit iris jaune-vert et sa pupille ; deux traits
 * d'encre qui suivent la tache.
 */
function oeil(x, y) {
  if (x > -0.25) return null;
  const dx = x + 2, dy = y - 27.8;
  // u : le long de la tache, vers le dehors (un peu plus haut) ; w : vers le haut
  const u = -0.98 * dx + 0.2 * dy, w = 0.2 * dx + 0.98 * dy;
  const r = Math.hypot(dx, dy);
  if (r < 0.58) {
    if (r < 0.2) return PUPILLE;
    if (dx > -0.42 && dx < -0.16 && dy > 0.14 && dy < 0.4) return [250, 248, 222];
    return r > 0.45 ? VERT_F : mix(VERT_C, VERT, r / 0.45);
  }
  const pique = (cx, cy, sx, sy, L, l) => {
    const qx = dx - cx, qy = dy - cy, le = qx * sx + qy * sy;
    return le > -0.3 && le < L && Math.abs(qx * sy - qy * sx) < l * (1 - le / L);
  };
  if ((Math.abs(u) < 1.65 && Math.abs(w) < 0.88 && Math.abs(u) + 0.5 * Math.abs(w) < 2)
    || pique(-1.44, 1.19, -0.55, 0.83, 1.3, 0.5) || pique(1.44, -1.19, 0.55, -0.83, 0.7, 0.4)) return NOIR;
  if (x > -0.7) return null;
  if (Math.abs(w - 1.4) < 0.17 && u > -1.4 && u < 0.9) return ENCRE;
  if (Math.abs(w + 1.35) < 0.17 && u > -1.1 && u < 1.7) return ENCRE;
  return null;
}

/**
 * Ce texel (de la partie nom) est-il pris par le sable ? Étape 1 : seulement le bras, le sable mord l'épaule. Étape 2 :
 * la moitié droite ; le bord ondule et déborde du milieu par endroits, des taches passent de l'autre côté. Le visage
 * reste à lui, sauf la tempe, le haut du front et l'œil droit, qui devient celui d'Ichibi.
 */
function pris(nom, t, etape) {
  const [x, y, z] = t.centre, sx = -x;
  if (etape === 1) return nom === "corps" && sx + 1.5 * bruit(y, z + x, 330) > 2.2 && y > 19.5 + 2 * bruit(x, y, 331);
  switch (nom) {
    case "corps": {
      const bord = 2.2 * Math.sin(y * 0.8 + z * 0.6 + 2.1) + 1.2 * Math.sin(y * 2.3 + 4.2) + 1.5 * bruit(y, z, 351) - 0.4;
      const ile = bruit(y * 1.2 + z, x * 1.2, 363);
      return (sx + bord > 0 && ile > -0.5) || ile > 0.62;
    }
    case "tete":
      if (t.face === "devant") {
        return !!oeil(x, y) || sx > 2.3 + 0.6 * bruit(y * 2, x, 352) || (y > 30.1 && sx > 0.2 + 1.2 * bruit(y, x, 353))
          || (y < 26 && sx > 1.3 + 0.8 * bruit(y * 2, x, 354));
      }
      return sx + 1.6 * Math.sin(y * 1.1 + z * 0.7 + 1.7) + 3 * bruit(y + z, z - x, 355) > -0.4;
    case "jambeD": return y > 4 + 4 * bruit(z + x * 0.7, y * 0.7, 356) && bruit(y, z - x, 357) > -0.5;
    case "jambeG": return y > 8 + 3 * bruit(z, y, 358) && sx + 4.5 * bruit(z + x, y, 359) > 0.8;
    case "brasG": return y > 20 + 2.5 * bruit(z + x, y, 360) && bruit(y, z + x, 361) > 0.15;
    default: return false;
  }
}

const NOMS = PARTIES(false).map((p) => p[0]);

/** La couleur du sable sur ce texel, éclairée par l (l'œil ne s'éclaire pas : il brille un peu). */
function teinte(nom, t, l) {
  const [x, y, z] = t.centre, N = t.N;
  if (nom === "tete" && t.face === "devant") {
    const e = oeil(x, y);
    if (e) return e;
  }
  const [a, b] = Math.abs(N[2]) > 0.5 ? [x, y] : Math.abs(N[0]) > 0.5 ? [z, y] : [x, z];
  return clair(sable(a + 40, b + 40, FACES[t.face] + 10 * NOMS.indexOf(nom)), l);
}

/** L'armure (casque, plastron, jambières, bottes), comme dans les maquettes des fruits. */
const ARMURE = [
  ["tete", 1, (t) => !(t.face === "devant" && t.centre[1] < 29.5 && Math.abs(t.centre[0]) < 3.5)],
  ["corps", 1, () => true],
  ["brasD", 1, (t) => t.centre[1] > 16.5], ["brasG", 1, (t) => t.centre[1] > 16.5],
  ["jambeD", 0.5, (t) => t.centre[1] > 4.5], ["jambeG", 0.5, (t) => t.centre[1] > 4.5],
  ["jambeD", 1, (t) => t.centre[1] < 4.5], ["jambeG", 1, (t) => t.centre[1] < 4.5],
];

const PIVOTS = { tete: [0, 24, 0], corps: [0, 12, 0], brasD: [-5, 22, 0], brasG: [5, 22, 0], jambeD: [-2, 12, 0], jambeG: [2, 12, 0] };
/** L'épaisseur de la couche de sable (0,55 pixel, plus sur la tête, dont la seconde couche est plus gonflée). */
const EPAIS = { tete: 0.75 };

/**
 * Le joueur en mode Ichibi. Comme perso (o.tete, o.brasG : [rx, rz, ry] ; o.jambeD, o.jambeG : rx ; o.buste, o.lacet,
 * o.penche, o.decale), plus o.etape (0 : normal, 1 : le bras seulement, 2 : la moitié, par défaut), o.bras (la pose du
 * grand bras, voir REPOS), o.armure (sa couleur). Le sable est une couche épaisse posée sur le corps (EPAIS), un peu
 * plus haute que la seconde couche du skin ; le texel est coupé en quatre (en seize pour la tête, pour l'œil).
 */
function joueur(sc, peau, o = {}) {
  const lacet = o.lacet ?? 0, penche = o.penche ?? 0, decale = o.decale ?? [0, 0, 0], buste = o.buste ?? 0;
  const monde = (v) => ajoute(rotY(rotX(sous(v, [0, 12, 0]), penche), lacet), ajoute(decale, [0, 12, 0]));
  const T = { monde, mn: (v) => norme(rotY(rotX(v, penche), lacet)) };
  for (const [nom, pv] of Object.entries(PIVOTS)) {
    const a = o[nom];
    const [rx = 0, rz = 0, ry = 0] = typeof a === "number" ? [a] : a ?? [];
    const b = nom.startsWith("jambe") ? 0 : buste;
    const local = (v) => ajoute(rotY(rotX(rotZ(sous(v, pv), rz), rx), ry), pv);
    T[nom] = {
      p: (v) => monde(ajoute(rotX(sous(local(v), [0, 12, 0]), b), [0, 12, 0])),
      n: (v) => norme(rotY(rotX(rotX(rotY(rotX(rotZ(v, rz), rx), ry), b), penche), lacet)),
    };
  }
  const etape = o.etape ?? 2;
  for (const [nom, boite, uv1, uv2, gonfle] of PARTIES(false)) {
    if (etape && nom === "brasD") continue;
    const R = nom === "tete" ? 4 : 2, base = texels(boite, uv1, 0, R);
    const couvert = base.map((t) => etape > 0 && pris(nom, t, etape));
    const dessus = texels(boite, uv1, EPAIS[nom] ?? 0.55, R);
    for (const [uv, g, d] of [[uv1, 0, 0], [uv2, gonfle, 0.02]]) texels(boite, uv, g, R).forEach((t, k) => {
      const l = sc.lumiere(T[nom].n(t.N), false);
      if (l < 0) return;
      if (couvert[k]) {
        // le sable remplace la peau et les habits (la seconde couche disparaît de ce côté) : dessous, l'ombre de la
        // couche ; dessus, le sable, épais
        if (g > 0) return;
        sc.quad(t.coins.map(T[nom].p), clair(SABLE_F, l * 0.7), d, false);
        sc.quad(dessus[k].coins.map(T[nom].p), teinte(nom, t, l), d, false);
        return;
      }
      const i = (Math.floor(t.ty / R) * 64 + Math.floor(t.tx / R)) * 4;
      if (peau.px[i + 3] < 128) return;
      let c = clair([peau.px[i], peau.px[i + 1], peau.px[i + 2]], l);
      if (o.teinte) c = mix(c, o.teinte.c, o.teinte.k);
      sc.quad(t.coins.map(T[nom].p), c, d, false);
    });
  }
  if (o.armure) {
    for (const [nom, g, garde] of ARMURE) {
      if (etape && nom === "brasD") continue;
      const boite = PARTIES(false).find((p) => p[0] === nom)[1];
      for (const t of texels(boite, [0, 0], g, 2)) {
        if (!garde(t)) continue;
        const l = sc.lumiere(T[nom].n(t.N), false);
        if (l < 0) continue;
        // de son côté, le sable passe par-dessus l'armure
        if (etape && pris(nom, t, etape)) {
          sc.quad(t.coins.map(T[nom].p), teinte(nom, t, l), 0.04, false);
          continue;
        }
        const bord = hache(t.tx >> 1, t.ty >> 1, 311) < 0.12;
        const c = mix(o.armure, [255, 255, 255], bord ? 0.35 : 0.12 * hache(t.tx >> 2, t.ty >> 2, 312));
        sc.quad(t.coins.map(T[nom].p), clair(c, l), 0.04, false);
      }
    }
  }
  if (etape) {
    brasGeant(sc, T, o.bras ?? REPOS);
    if (etape === 2) {
      oreille(sc, T.tete);
      piquesDos(sc, T.corps);
      queue(sc, T.corps);
      grains(sc, T.corps, o.grains ?? 10, o.k ?? 0);
    }
  }
  return T;
}

// ------------------------------------------------------------------ les morceaux d'Ichibi
/** Un pavé de a vers b (largeur w, épaisseur e), qui garde ref sur le côté ; rend son repère. */
function membre(sc, a, b, w, e, ref, couleur, ext = 0, pas = 0.5) {
  const f = norme(sous(b, a)), L = Math.hypot(...sous(b, a));
  let e1 = croix(f, ref);
  if (Math.hypot(...e1) < 0.15) e1 = croix(f, [0, 0, 1]);
  e1 = norme(e1);
  const e3 = croix(e1, f);
  const vers = (v) => ajoute(ajoute(fois(e1, v[0]), fois(f, v[1])), fois(e3, v[2]));
  const R = { p: (v) => ajoute(a, vers(v)), n: (v) => norme(vers(v)), e1, f, e3, L };
  pave(sc, [-w / 2, -ext, -e / 2], [w / 2, L + ext, e / 2], R, couleur, pas);
  return R;
}

/**
 * La pose du grand bras, dans le repère du buste : l'épaule E, le coude C, le poignet P ; les doigts vers f, la paume
 * vers n ; main = "griffe" (ouverte, crochue), "poing", "ouverte" (après un lancer) ou null (une prise la dessine).
 */
const REPOS = { E: [-7.6, 21.6, 0], C: [-9.6, 14.4, 1.4], P: [-9.4, 8.2, 4], f: [0.05, -0.8, 0.6], n: [1, 0, 0.1], main: "griffe" };
const POING = { E: [-7.6, 21.6, 0], C: [-9.4, 20.8, 8], P: [-8, 21.2, 15.4], f: [0, 0, 1], n: [1, 0, 0], main: "poing" };
const LANCE = { E: [-7.6, 21.6, 0], C: [-9.4, 26.5, 6.5], P: [-8.4, 31, 13.5], f: [0, 0.55, 0.83], n: [0.3, -0.8, 0.5], main: "ouverte" };

/** Le grand bras de sable (à la place du bras droit) : épaule, coude, main griffue, des piques le long du dos du bras. */
function brasGeant(sc, T, b) {
  const E = T.corps.p(b.E), C = T.corps.p(b.C), P = T.corps.p(b.P), cote = T.corps.n([1, 0, 0]);
  const H = membre(sc, E, C, 6.8, 6.8, cote, motif(1), 1.8);
  if (b.etire) {
    // il attrape : le bras s'allonge, l'avant-bras grossit jusqu'à la main, du sable file le long
    for (let k = 0; k < 4; k++) {
      const w = 7.6 + 1.5 * k;
      membre(sc, ajoute(C, fois(sous(P, C), k / 4)), ajoute(C, fois(sous(P, C), (k + 1) / 4)), w, w, cote, motif(60 + k), 0.9);
    }
    for (let q = 0; q < 14; q++) {
      const t = hache(q, 1, 610), r = 5 + 3 * t;
      cube(sc, ajoute(ajoute(C, fois(sous(P, C), t)), [(hache(q, 2, 610) - 0.5) * 2 * r, (hache(q, 3, 610) - 0.5) * 2 * r, 0]), 0.7, [q, t], SABLE_C);
    }
  } else membre(sc, C, P, 7.6, 7.6, cote, motif(2), 1);
  // des piques sur l'épaule et le long du dos du bras, vers l'extérieur
  for (let k = 0; k < 5; k++) {
    const base = H.p([-1.6 - 0.4 * (k % 2), -0.6 + k * H.L * 0.22, -3.2]);
    const dir = norme(ajoute(ajoute(fois(H.e3, -1), fois(H.e1, -0.75)), fois(H.f, -0.55)));
    pointe(sc, base, dir, 4.6 - 0.55 * k, 2.4 - 0.2 * k, (t) => mix(SABLE, SABLE_F, t * 0.8), false, false);
  }
  if (b.main) main(sc, P, T.corps.n(b.f), T.corps.n(b.n), b.main);
}

/** La main d'Ichibi au poignet P : une grosse paume, quatre doigts en deux phalanges, des griffes blanches, le pouce. */
function main(sc, P, f0, n0, sorte) {
  const f = norme(f0), n = norme(sous(n0, fois(f, n0[0] * f[0] + n0[1] * f[1] + n0[2] * f[2])));
  const lat = norme(croix(f, n));
  const vers = (v) => ajoute(ajoute(fois(lat, v[0]), fois(f, v[1])), fois(n, v[2]));
  const H = { p: (v) => ajoute(P, vers(v)), n: (v) => norme(vers(v)) };
  pave(sc, [-3.8, -0.6, -1.8], [3.8, 5, 1.8], H, motif(3));
  const [c1, c2, ecart] = { griffe: [0.45, 0.65, 0.12], poing: [1.45, 1.5, 0], ouverte: [0.05, 0.2, 0.22] }[sorte];
  for (let k = 0; k < 4; k++) {
    const ox = -2.85 + k * 1.9, sp = (k - 1.5) * ecart;
    const d1 = H.n([sp, Math.cos(c1), Math.sin(c1)]), d2 = H.n([sp, Math.cos(c1 + c2), Math.sin(c1 + c2)]);
    const a = H.p([ox, 4.6, 0.6]), b = ajoute(a, fois(d1, 3.2)), c = ajoute(b, fois(d2, 2.6));
    membre(sc, a, b, 1.8, 1.8, lat, motif(4 + k), 0.4);
    membre(sc, b, c, 1.6, 1.6, lat, motif(8 + k), 0.4);
    pointe(sc, c, H.n([sp, Math.cos(c1 + c2 + 0.4), Math.sin(c1 + c2 + 0.4)]), 2.8, 1.4, griffe, false, false);
  }
  // le pouce, sur le côté de la paume
  const a = H.p([-3.8, 1.4, 1]), d = H.n([-0.6, 0.6, sorte === "poing" ? 1.2 : 0.5]);
  const b = ajoute(a, fois(d, 3.2));
  membre(sc, a, b, 1.9, 1.9, f, motif(12), 0.4);
  pointe(sc, b, d, 2.4, 1.4, griffe, false, false);
}

/** L'oreille d'Ichibi : en pointe et en marches, sur le côté droit du haut de la tête, le bout et le dedans bleus. */
function oreille(sc, T) {
  const d = norme([-0.45, 1, -0.1]), base = [-2.6, 31.2, -0.4];
  for (let k = 0; k < 4; k++) {
    const a = T.p(ajoute(base, fois(d, k * 1.7))), b = T.p(ajoute(base, fois(d, k * 1.7 + 1.9)));
    const w = 4.4 - k * 1;
    const sab = motif(30 + k);
    membre(sc, a, b, w, 1.6 - k * 0.15, T.n([0, 0, 1]), (f, u, v, i, j) =>
      k === 3 || (f === "devant" && Math.abs(u - 0.5) < 0.17 && k > 0) ? ENCRE : sab(f, u, v, i, j), 0.1);
  }
}

/** Deux rangées de piques sur l'omoplate droite. */
function piquesDos(sc, T) {
  for (const [p, L] of [[[-2.6, 23, -2], 4.2], [[-1.4, 20, -2], 3.4], [[-3.2, 18, -2], 3], [[-1, 16.2, -2], 2.4]]) {
    pointe(sc, T.p(p), T.n([-0.35, 0.55, -1]), L, 1.9, (t) => mix(SABLE, SABLE_F, t * 0.8), false, false);
  }
}

/** La queue d'Ichibi, du même sable que le corps : très épaisse (presque la largeur du buste au départ), en tronçons de plus en plus fins, qui part du bas du dos et remonte en crosse. */
function queue(sc, T) {
  const P = [[-0.8, 12, -4], [-1.2, 6, -17], [-2.2, 33, -25], [-1, 41, -13]];
  const B = (t) => [0, 1, 2].map((i) => (1 - t) ** 3 * P[0][i] + 3 * (1 - t) ** 2 * t * P[1][i] + 3 * (1 - t) * t * t * P[2][i] + t ** 3 * P[3][i]);
  const n = 12;
  for (let k = 0; k < n; k++) {
    const w = 8.8 - 4.4 * (k / (n - 1));
    membre(sc, T.p(B(k / n)), T.p(B((k + 1) / n)), w, w, T.n([1, 0, 0]), motif(20 + k), 0.7);
  }
  const bout = T.p(B(1));
  pointe(sc, bout, norme(sous(bout, T.p(B(0.95)))), 4.5, 4.2, (t) => mix(SABLE, ENCRE, t * 0.9), false, false);
}

/** Des grains de sable qui flottent autour de la moitié d'Ichibi. */
function grains(sc, T, n, k = 0) {
  for (let q = 0; q < n; q++) {
    const p = T.p([-6 - 9 * hache(q, 1, 500 + k), 2 + 30 * hache(q, 2, 500 + k), (hache(q, 3, 500 + k) - 0.5) * 16]);
    cube(sc, p, 0.5 + 0.4 * hache(q, 4, 500 + k), [q, q * 0.7], hache(q, 5, 500 + k) < 0.3 ? SABLE_F : SABLE_C);
  }
}

/** Une gerbe de sable qui part de p. */
function eclat(sc, p, n = 14, r = 6, k = 0) {
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, 520 + k) * TOUR, b = (hache(q, 2, 520 + k) - 0.5) * 2.4;
    const d = [Math.cos(a) * Math.cos(b), Math.sin(b), Math.sin(a) * Math.cos(b)];
    cube(sc, ajoute(p, fois(d, r * (0.4 + 0.6 * hache(q, 3, 520 + k)))), 0.6 + 1.1 * hache(q, 4, 520 + k), [a, b], q % 3 ? SABLE_C : SABLE_F);
  }
}

// ------------------------------------------------------------------ la prise (clic droit)
/** Où la main tient sa proie, dans le repère du joueur : face à lui, devant à droite. */
const PROIE = [-6, 0, 34];
const tenir = (yv) => ({ E: [-7.6, 21.6, 0], C: [-11, yv - 4, 9], P: [-6.5, yv, PROIE[2] - 6.4], main: null, etire: true });

/**
 * La main qui tient, grossie : la paume sur tout son ventre, quatre gros doigts le long de son flanc puis dans son dos,
 * griffes plantées ; le pouce de l'autre côté. yv : la hauteur de son ventre ; serre : la main se referme plus fort.
 */
function prise(sc, T, yv, serre = false) {
  const L = { p: T.monde, n: T.mn };
  const [cx, , cz] = PROIE, s = serre ? 0.8 : 0, ep = 2.8;
  const av = cz - 2.3, ar = cz + 2.3 - s, ga = cx - 4.7 + s, dr = cx + 4.7 - s;
  pave(sc, [cx - 6.6, yv - 7.6, av - 3.8], [cx + 6.6, yv + 7.2, av], L, motif(40));
  for (let k = 0; k < 4; k++) {
    // des doigts séparés (on voit ses habits entre eux), une jointure plus grosse au milieu
    const y = yv - 7.2 + k * 3.65, bout = ga + 5 + 1.2 * (k % 2);
    pave(sc, [ga - ep, y, av - 3.4], [ga, y + ep, ar + ep], L, motif(41 + k));
    pave(sc, [ga - ep - 0.7, y - 0.3, cz - 1.5], [ga - ep + 0.8, y + ep + 0.3, cz + 1.5], L, motif(55 + k, SABLE_F));
    pave(sc, [ga - ep, y, ar], [bout, y + ep, ar + ep], L, motif(45 + k));
    pointe(sc, L.p([bout, y + ep / 2, ar + ep / 2]), L.n([1, 0.1, -0.4]), 3.8, 2.1, griffe, false, false);
  }
  pave(sc, [dr, yv - 1.7, av - 3.4], [dr + ep, yv + 1.7, ar - 0.4], L, motif(50));
  pointe(sc, L.p([dr + ep / 2, yv, ar - 0.4]), L.n([-0.4, 0, 1]), 3.4, 2, griffe, false, false);
}

/** Celui qu'on tient : face à Ichibi, soulevé, les bras en l'air, les jambes qui pendent. */
function proie(sc, decale, yv, serre) {
  return perso(sc, STEVE, {
    decale: ajoute(decale, [PROIE[0], yv - 18, PROIE[2]]), lacet: Math.PI, brasD: [-2.75, -0.35], brasG: [serre ? -2.2 : -2.95, 0.5],
    jambeD: serre ? 0.5 : 0.25, jambeG: serre ? -0.45 : -0.15, teinte: serre ? TOUCHE : null,
  });
}

// ------------------------------------------------------------------ le nuage (shift)
/** Le nuage de sable : une galette épaisse au milieu, bosselée dessus, le bord qui s'effiloche, du sable qui tombe. */
function nuage(sc, c, rx, rz, k = 0) {
  const pas = 1.6;
  for (let x = -rx; x < rx; x += pas) for (let z = -rz; z < rz; z += pas) {
    const mx = x + pas / 2, mz = z + pas / 2;
    const r = Math.hypot(mx / rx, mz / rz) + 0.18 * bruit(mx * 1.3 + 50, mz * 1.3 + 50, 540 + k);
    if (r > 1) continue;
    const haut = c[1] + 1.2 + 1.8 * (1 - r) + 1.2 * bruit(mx * 1.4 + 50, mz * 1.4 + 50, 550 + k);
    const bas = c[1] - 1.6 - 6.5 * (1 - r * r) + 1.2 * bruit(mx * 1.6 + 70, mz * 1.6 + 70, 555 + k);
    pave(sc, [c[0] + x, bas, c[2] + z], [c[0] + x + pas, haut, c[2] + z + pas], ID, (f, u, v, i, j) =>
      sable(mx * 2 + i * 0.8 + 60, mz * 2 + j * 0.8 + 60, 560 + k + FACES[f], f === "haut" ? SABLE_C : SABLE, false), 0.8);
  }
  for (let q = 0; q < 40; q++) {
    const a = hache(q, 1, 570 + k) * TOUR, r = Math.sqrt(hache(q, 2, 570 + k));
    const p = [c[0] + Math.cos(a) * rx * r, c[1] - 7 - 16 * hache(q, 3, 570 + k), c[2] + Math.sin(a) * rz * r];
    cube(sc, p, 0.9 - 0.5 * hache(q, 4, 570 + k), [q, a], q % 3 ? SABLE : SABLE_F);
  }
}

/** Le sol de sable sous lui, en rond (quand le nuage se forme). */
const auSol = (cx, cz, r) => (x, z) => (Math.hypot(x - cx, z - cz) < r + 3 * bruit(x + 80, z + 80, 580) ? sable(x + 90, z + 90, 581, SABLE, false) : null);

// ------------------------------------------------------------------ les vignettes
function enPied(sc) {
  joueur(sc, PEAU, { tete: [0.05, 0, 0.12], brasG: [0.08, 0.06] });
  legende(sc, "LA MOITIE DROITE EN ICHIBI, LA QUEUE AVEC");
}

function deDos(sc) {
  joueur(sc, PEAU, { tete: [0.05, 0, -0.1], brasG: [0.08, 0.06] });
  legende(sc, "DE DOS : LA QUEUE ET LES PIQUES");
}

function dePres(sc) {
  joueur(sc, PEAU, { tete: [0, 0, 0.18], brasG: [0.08, 0.06], grains: 4 });
  legende(sc, "L'OEIL D'ICHIBI, LE RESTE DU VISAGE SE VOIT");
}

function enArmure(sc) {
  joueur(sc, PEAU, { armure: [92, 219, 213], tete: [0.05, 0, 0.12], brasG: [0.08, 0.06] });
  legende(sc, "ARMURE EN DIAMANT : LE SABLE L'AVALE");
}

/** La touche Éveil : le son de la vidéo, le sable prend le bras, puis la moitié du corps. */
function montee(sc) {
  joueur(sc, PEAU, { etape: 0, decale: [-30, 0, 0], brasG: [0.08, 0.06] });
  joueur(sc, PEAU, { etape: 1, decale: [0, 0, 0], brasG: [0.08, 0.06] });
  const T = joueur(sc, PEAU, { etape: 2, decale: [32, 0, 0], brasG: [0.08, 0.06], grains: 6 });
  for (let q = 0; q < 22; q++) {
    const a = q * 0.85, h = q * 1.6;
    cube(sc, T.corps.p([Math.cos(a) * 11, h, Math.sin(a) * 8]), 0.9, [q, a], q % 3 ? SABLE_C : SABLE_F);
  }
  for (const [x, t] of [[-30, "0 S"], [0, "1 S"], [32, "2 S"]]) {
    const [sx, sy] = sc.vue([x, 42, 0]);
    ecrireTexte(sc.img, sc.w, t, Math.round(sx) - 16, Math.max(4, Math.round(sy) - 20), 3, [40, 40, 50, 255]);
  }
  legende(sc, "LE SON DE TA VIDEO, LE SABLE MONTE");
}

function coup(sc) {
  sol(sc, -34, -18, 26, 52);
  joueur(sc, PEAU, { bras: POING, brasG: [0.45, 0.15], jambeD: -0.35, jambeG: 0.3, buste: 0.12, tete: [0.08] });
  perso(sc, STEVE, { decale: [-7, 4, 30], lacet: Math.PI, penche: -0.6, brasD: [-2.3, -0.7], brasG: [-2.4, 0.7], jambeD: 0.5, jambeG: -0.3, teinte: TOUCHE });
  eclat(sc, [-8, 22, 23], 16, 7);
  // le mouvement : une traînée de sable derrière le poing
  for (let q = 0; q < 12; q++) cube(sc, [-10 + 4 * hache(q, 1, 590), 18 + 6 * hache(q, 2, 590), 2 + q * 1.1], 0.7, [q, q], SABLE_C);
  legende(sc, "10 DEGATS, 5 BLOCS, IL L'ENVOIE LOIN");
}

function attrape(serre) {
  return (sc) => {
    // son ombre sur l'herbe : il ne touche plus le sol
    sol(sc, -34, -16, 26, 46, (x, z) => (Math.hypot((x - PROIE[0]) / 1.6, z - PROIE[2]) < 3.4 ? clair(HERBE, 0.62) : null));
    const yv = 31;
    const T = joueur(sc, PEAU, { bras: tenir(yv), brasG: [serre ? -0.3 : 0.2, 0.2], jambeD: -0.2, jambeG: 0.2, buste: serre ? 0.12 : 0 });
    proie(sc, [0, 0, 0], yv, serre);
    prise(sc, T, yv, serre);
    if (serre) {
      eclat(sc, [-12, yv, PROIE[2]], 18, 9, 3);
      eclat(sc, [-2, yv + 6, PROIE[2] + 3], 12, 6, 4);
    }
    legende(sc, serre ? "IL SERRE FORT : 12 DEGATS, PUIS IL LACHE" : "LE BRAS S'ALLONGE, LA MAIN GROSSIT (8 BLOCS)");
  };
}

function jette(sc) {
  sol(sc, -40, -16, 40, 112);
  const T = joueur(sc, PEAU, { bras: LANCE, brasG: [0.5, 0.2], jambeD: -0.45, jambeG: 0.4, buste: 0.2 });
  const A = T.corps.p([-8, 34, 17]), B = [6, 30, 92];
  for (let q = 1; q < 18; q++) {
    const t = q / 18, p = ajoute(ajoute(A, fois(sous(B, A), t)), [0, 30 * 4 * t * (1 - t), 0]);
    cube(sc, p, 1.1 - 0.4 * t, [q, t], q % 2 ? SABLE_C : SABLE_F);
  }
  perso(sc, STEVE, { decale: [B[0], B[1] - 16, B[2]], lacet: Math.PI * 0.8, penche: 1.2, brasD: [-2.6, -0.8], brasG: [-1, 0.9], jambeD: 0.6, jambeG: -0.4 });
  legende(sc, "IL LE JETTE LOIN : 8 DEGATS A L'ARRIVEE");
}

function decolle(sc) {
  sol(sc, -40, -26, 40, 40, auSol(0, 0, 16));
  const h = 15;
  nuage(sc, [0, h, 0], 24, 18, 1);
  joueur(sc, PEAU, { decale: [0, h + 2.8, 0], brasG: [-0.2, 0.3], tete: [-0.2] });
  for (let q = 0; q < 30; q++) {
    const a = q * 0.7, r = 15 - q * 0.25;
    cube(sc, [Math.cos(a) * r, (q / 30) * h, Math.sin(a) * r * 0.75], 0.9, [q, a], q % 3 ? SABLE : SABLE_F);
  }
  legende(sc, "SHIFT : LE SABLE MONTE SOUS SES PIEDS");
}

function vol(sc) {
  sol(sc, -80, -70, 80, 50, null, -72);
  for (const [x, z] of [[-50, -30], [-20, 20], [30, -40], [55, 10], [-62, 30], [12, -8]]) {
    pave(sc, [x - 4, -72, z - 4], [x + 4, -64, z + 4], ID, () => [60, 130, 50], 2);
    pave(sc, [x - 1, -72, z - 1], [x + 1, -66, z + 1], ID, () => [110, 80, 50], 2);
  }
  nuage(sc, [0, 0, 0], 24, 18, 2);
  joueur(sc, PEAU, { decale: [0, 2.8, 0], buste: 0.15, brasG: [0.35, 0.25], jambeD: -0.15, jambeG: 0.15 });
  for (let q = 0; q < 34; q++) {
    const t = q / 34;
    cube(sc, [(hache(q, 1, 595) - 0.5) * (14 + 24 * t), -1 + (hache(q, 2, 595) - 0.5) * 6 - 6 * t, -12 - 46 * t], 1.3 - 0.8 * t, [q, t], q % 3 ? SABLE_C : SABLE);
  }
  legende(sc, "30 S DE VOL : ESPACE MONTE, SHIFT DESCEND");
}

function volPrise(sc) {
  sol(sc, -80, -60, 80, 70, null, -66);
  nuage(sc, [0, 0, 0], 24, 18, 3);
  const yv = 31, d = [0, 2.8, 0];
  const T = joueur(sc, PEAU, { decale: d, bras: tenir(yv), brasG: [0.2, 0.2], jambeD: -0.2, jambeG: 0.2 });
  proie(sc, d, yv, false);
  prise(sc, T, yv, false);
  legende(sc, "EN VOL, IL FRAPPE ET ATTRAPE AUSSI");
}

// ------------------------------------------------------------------ la planche
const CW = 440, CH = 520, M = 34, HAUT = 640;
const TITRE = [30, 30, 40, 255], BRUN = [160, 112, 40, 255], BLEU = [40, 60, 108, 255];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

function entete(img, W) {
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, "ICHIBI : LE MODE SHUKAKU", 30, 22, 5, TITRE);
  const colonnes = [
    [30, BRUN, "CE QU'IL FAIT", [
      "TOUCHE EVEIL : LA MOITIE DROITE DEVIENT ICHIBI",
      "    (GRAND BRAS GRIFFU, OREILLE, OEIL, PIQUES, QUEUE)",
      "CLIC GAUCHE : COUP DU BRAS D'ICHIBI, 10 DEGATS,",
      "    5 BLOCS DE PORTEE, IL L'ENVOIE LOIN",
      "CLIC DROIT : LE BRAS S'ALLONGE, LA MAIN GROSSIT ET L'ATTRAPE",
      "    JUSQU'A 8 BLOCS, ET LE SOULEVE",
      "    CLIC DROIT ENCORE : IL SERRE FORT, 12 DEGATS",
      "    CLIC GAUCHE : IL LE JETTE LOIN, 8 DEGATS. RECHARGE 12 S",
      "SHIFT : UN NUAGE DE SABLE SOUS SES PIEDS, 30 S DE VOL",
      "    (ESPACE MONTE, SHIFT DESCEND). RECHARGE 30 S",
    ]],
    [900, BLEU, "L'EQUILIBRE", [
      "NIVEAU 1 : 2 MIN D'EVEIL, PUIS 2 MIN DE REPOS",
      "SON DE L'EVEIL : CELUI DE TA VIDEO (PACK PRIVE)",
      "ARMURE DE SABLE : COMME UNE ARMURE EN DIAMANT,",
      "    LE SABLE PASSE PAR-DESSUS L'ARMURE PORTEE",
      "TENU : LE SABLE L'ECRASE, 1 DEGAT PAR SECONDE, 8 S AU PLUS",
      "FIN DU VOL : LE NUAGE LE POSE DOUCEMENT, PAS DE CHUTE",
      "RIEN NE RESTE AU SOL : LE NUAGE S'EFFRITE",
      "A COTE : SUSANO 1 FRAPPE 9, HACHIBI 1 GRIFFE 9 (BETE 13)",
      "LISTE BLANCHE : /POUVOIR ICHIBI AJOUTER (JOUEUR)",
    ]],
  ];
  for (const [x, c, titre, lignes] of colonnes) {
    ecrireTexte(img, W, titre, x, 100, 4, c);
    lignes.forEach((t, k) => ecrireTexte(img, W, t, x, 160 + k * 34, 3, TITRE));
  }
  rect(img, W, 30, 512, W - 30, 515, [200, 200, 210, 255]);
  ecrireTexte(img, W, "EN VOL IL PEUT AUSSI FRAPPER ET ATTRAPER. IL PREND LES COUPS COMME TOUT LE MONDE (PAS DE CONTRE-POUVOIR)", 30, 534, 3, TITRE);
  ecrireTexte(img, W, "LE BORD DU SABLE N'EST JAMAIS DROIT ET IL EST EPAIS. LA QUEUE ET LE BRAS SONT LA DES L'EVEIL", 30, 568, 3, TITRE);
}

const ACTION = { echelle: 5, tangage: 16, pied: 330 };
const vues = [
  ["EVEIL : MOITIE ICHIBI", enPied, { lacet: 32, echelle: 8.6, pied: 470, centre: [-2, 0, -4] }],
  ["DE DOS", deDos, { lacet: 205, echelle: 8.6, pied: 470, centre: [-2, 0, -4] }],
  ["DE PRES : L'OEIL ET L'OREILLE", dePres, { lacet: 24, tangage: 4, echelle: 21, pied: 250, centre: [-1.5, 28, 0] }],
  ["AVEC UNE ARMURE", enArmure, { lacet: 32, echelle: 8.6, pied: 470, centre: [-2, 0, -4] }],
  ["TOUCHE EVEIL : LE SABLE MONTE", montee, { lacet: 24, tangage: 8, echelle: 4.8, pied: 410, centre: [0, 12, -6] }],
  ["CLIC GAUCHE : COUP DE BRAS", coup, { ...ACTION, lacet: 84, centre: [-4, 14, 14] }],
  ["CLIC DROIT : IL L'ATTRAPE", attrape(false), { ...ACTION, echelle: 4.3, lacet: 82, centre: [-4, 20, 16] }],
  ["CLIC DROIT ENCORE : IL SERRE", attrape(true), { ...ACTION, echelle: 4.3, lacet: 82, centre: [-4, 20, 16] }],
  ["CLIC GAUCHE EN TENANT : IL JETTE", jette, { ...ACTION, echelle: 3.2, lacet: 58, centre: [0, 24, 46] }],
  ["SHIFT : LE NUAGE DE SABLE", decolle, { lacet: 30, tangage: 16, echelle: 6, pied: 400, centre: [0, 14, 0] }],
  ["EN VOL : IL SE BALADE", vol, { lacet: 38, tangage: 24, echelle: 3.4, pied: 300, centre: [0, -16, -8] }],
  ["EN VOL : IL ATTRAPE AUSSI", volPrise, { lacet: 70, tangage: 18, echelle: 3.6, pied: 300, centre: [-3, -10, 14] }],
];

// les briques servent aussi aux textures du mod (textures.mjs)
export { bruit, sable, oeil, pris, NOMS, SABLE, SABLE_C, SABLE_F, QUEUE, ENCRE, GRIFFE };

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
// node rendu.mjs essai 0,5 sortie.png : quelques vignettes en grand, pour les régler
if (PRINCIPAL && process.argv[2] === "essai") {
  const choix = process.argv[3].split(",").map(Number), k2 = 2;
  const W2 = CW * k2 * choix.length, H2 = CH * k2, img2 = Buffer.alloc(W2 * H2 * 4, 255);
  choix.forEach((k, c) => {
    const [, dessin, o] = vues[k];
    const sc = scene({ w: CW * k2, h: CH * k2, fond: FOND, ...o, echelle: (o.echelle ?? 9) * k2, pied: (o.pied ?? CH * 0.88) * k2 });
    dessin(sc);
    for (let y = 0; y < H2; y++) sc.img.copy(img2, (y * W2 + c * CW * k2) * 4, y * CW * k2 * 4, (y + 1) * CW * k2 * 4);
  });
  ecrirePng(process.argv[4], W2, H2, img2);
} else if (PRINCIPAL) {
  const COLONNES = 4, lignes = Math.ceil(vues.length / COLONNES);
  const W = CW * COLONNES, H = HAUT + (M + CH) * lignes;
  const img = Buffer.alloc(W * H * 4, 255);
  entete(img, W);
  vues.forEach(([titre, dessin, o], k) => {
    const c = k % COLONNES, l = Math.floor(k / COLONNES);
    const sc = scene({ w: CW, h: CH, fond: FOND, ...o });
    dessin(sc);
    for (let y = 0; y < CH; y++) sc.img.copy(img, ((HAUT + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(img, W, titre, c * CW + 12, HAUT + l * (M + CH) + 10, 3, k % 2 ? BLEU : BRUN);
  });
  ecrirePng(path.join(DOC, "planche-ichibi.png"), W, H, img);
  console.log("ok", W, H);
}
