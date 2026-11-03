// Maquettes des fruits du démon, éveil 1 : le fruit de la lave (Magu Magu no Mi, celui d'Akainu) et celui de la glace
// (Hie Hie no Mi, celui d'Aokiji). Touche Éveil : la main gauche devient de la lave, ou la main droite de la glace.
// Clic gauche : toute arme frappe un peu plus fort et enflamme (ou ralentit). Clic droit : un jet de lave (ou de glace)
// qui ne laisse rien au sol. Shift : une éruption (ou une ère glaciaire) tout autour. Dans l'eau, les deux perdent un
// demi-cœur par seconde, comme du poison.
// node rendu.mjs → planche-fruits.png
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, PARTIES, texels, clair, mix, hache, norme, croix, scene, ecrireTexte } from "./moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const SKINS = Object.fromEntries(["steve", "efe", "kai"].map((n) => [n, lire(path.join(ICI, "skins", n + ".png"))]));

const ajoute = (a, b) => a.map((v, i) => v + b[i]);
const sous = (a, b) => a.map((v, i) => v - b[i]);
const fois = (a, k) => a.map((v) => v * k);
const rotX = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
const rotY = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];
const rotZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];
const TOUR = Math.PI * 2;

const CROUTE = [40, 24, 22];
const CROUTE_R = [96, 36, 22];
const LAVE_R = [222, 58, 16];
const LAVE_O = [255, 116, 20];
const LAVE_J = [255, 216, 80];
const GLACE = [146, 206, 244];
const GLACE_C = [214, 240, 255];
const BLANC = [248, 252, 255];
const FUMEE = [128, 122, 120];
const VAPEUR = [226, 228, 232];
const HERBE = [104, 160, 60];
const TERRE = [134, 96, 66];
const SABLE = [218, 204, 150];
const EAU = [40, 96, 220];
const FOND = [236, 236, 242];
const ID = { p: (v) => v, n: (v) => norme(v) };
/** Une couleur qui brille : la lumière de la scène ne l'assombrit pas. */
const brille = (c) => [...c, 1];

// ------------------------------------------------------------------ les briques de dessin
/**
 * Un pavé (en coordonnées de repos), découpé en cases de `pas` ; `couleur(face, u, v, i, j)` donne la couleur de chaque
 * case (u, v de 0 à 1 sur la face), ou null pour la percer.
 */
function pave(sc, a, b, T, couleur, pas = 0.5, decale = 0, aile = false) {
  const [x0, y0, z0] = a, [x1, y1, z1] = b;
  const faces = [
    { N: [0, 0, 1], O: [x0, y1, z1], U: [x1 - x0, 0, 0], V: [0, y0 - y1, 0], nom: "devant" },
    { N: [0, 0, -1], O: [x1, y1, z0], U: [x0 - x1, 0, 0], V: [0, y0 - y1, 0], nom: "dos" },
    { N: [-1, 0, 0], O: [x0, y1, z0], U: [0, 0, z1 - z0], V: [0, y0 - y1, 0], nom: "droite" },
    { N: [1, 0, 0], O: [x1, y1, z1], U: [0, 0, z0 - z1], V: [0, y0 - y1, 0], nom: "gauche" },
    { N: [0, 1, 0], O: [x0, y1, z0], U: [x1 - x0, 0, 0], V: [0, 0, z1 - z0], nom: "haut" },
    { N: [0, -1, 0], O: [x0, y0, z1], U: [x1 - x0, 0, 0], V: [0, 0, z0 - z1], nom: "bas" },
  ];
  for (const f of faces) {
    const l = sc.lumiere(T.n(f.N), false);
    if (l < 0) continue;
    const nu = Math.max(1, Math.round(Math.hypot(...f.U) / pas)), nv = Math.max(1, Math.round(Math.hypot(...f.V) / pas));
    for (let j = 0; j < nv; j++) for (let i = 0; i < nu; i++) {
      const c = couleur(f.nom, (i + 0.5) / nu, (j + 0.5) / nv, i, j);
      if (!c) continue;
      const p = (u, v) => T.p(ajoute(ajoute(f.O, fois(f.U, u / nu)), fois(f.V, v / nv)));
      sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], c[3] === 1 ? c.slice(0, 3) : clair(c, l), decale, aile);
    }
  }
}

/** Un petit cube tourné (goutte, éclat, bouffée de fumée), d'une seule couleur. */
function cube(sc, c, s, [ax, ay], couleur, aile = false) {
  const T = { p: (v) => ajoute(c, rotY(rotX(v, ax), ay)), n: (v) => norme(rotY(rotX(v, ax), ay)) };
  pave(sc, [-s / 2, -s / 2, -s / 2], [s / 2, s / 2, s / 2], T, () => couleur, s, 0, aile);
}

/** Une pointe à base carrée, de `b` vers `dir` ; `couleur(t)` va de la base (0) à la pointe (1). */
function pointe(sc, b, dir, longueur, largeur, couleur, aile, emissif) {
  const d = norme(dir);
  let e1 = croix(d, [0, 1, 0]);
  if (Math.hypot(...e1) < 0.2) e1 = croix(d, [1, 0, 0]);
  e1 = norme(e1);
  const e2 = croix(d, e1);
  const coin = (a, c) => b.map((x, i) => x + ((e1[i] * a + e2[i] * c) * largeur) / 2);
  const base = [coin(-1, -1), coin(1, -1), coin(1, 1), coin(-1, 1)];
  const bout = b.map((x, i) => x + d[i] * longueur);
  sc.quad(base, emissif ? couleur(0) : clair(couleur(0), sc.lumiere(fois(d, -1), true)), 0, aile);
  for (let k = 0; k < 4; k++) {
    const p0 = base[k], p1 = base[(k + 1) % 4];
    const l = emissif ? 1 : sc.lumiere(norme(croix(sous(p1, p0), sous(bout, p0))), true);
    for (let j = 0; j < 4; j++) {
      const q = (p, t) => p.map((x, i) => x + (bout[i] - x) * t);
      sc.quad([q(p0, j / 4), q(p1, j / 4), q(p1, (j + 1) / 4), q(p0, (j + 1) / 4)], clair(couleur(j / 3), l), 0, aile);
    }
  }
}
/** Une langue de feu : jaune à la base, rouge orangé au bout. */
const flamme = (sc, b, dir, L, l) => pointe(sc, b, dir, L, l, (t) => mix(LAVE_J, LAVE_R, t * 0.85), true, true);
/** Un cristal de glace : bleu clair à la base, blanc au bout. */
const cristal = (sc, b, dir, L, l, aile = true) => pointe(sc, b, dir, L, l, (t) => mix(GLACE, BLANC, t), aile, false);

/** La distance aux bords des cellules (pour les fissures de la lave et de la glace) : petite = sur une fissure. */
function fissure(i, j, k, taille = 3) {
  const ci = Math.floor(i / taille), cj = Math.floor(j / taille);
  let d1 = 1e9, d2 = 1e9;
  for (let a = -1; a <= 1; a++) for (let b = -1; b <= 1; b++) {
    const px = (ci + a + hache(ci + a, cj + b, k)) * taille, py = (cj + b + hache(ci + a, cj + b, k + 50)) * taille;
    const d = Math.hypot(i + 0.5 - px, j + 0.5 - py);
    if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) d2 = d;
  }
  return d2 - d1;
}

/** Une roche de magma : des plaques de croûte noire, séparées par des fissures qui brillent. */
function magma(i, j, k, taille = 4, ouvert = 0.7) {
  const f = fissure(i, j, k, taille);
  if (f < 0.5 * ouvert) return brille(mix(LAVE_J, LAVE_O, f / (0.5 * ouvert)));
  if (f < 1.0 * ouvert) return brille(mix(LAVE_O, LAVE_R, (f - 0.5 * ouvert) / (0.5 * ouvert)));
  return mix(CROUTE, CROUTE_R, 0.2 + 0.5 * hache(i, j, k + 3));
}

/** De la glace : plus claire en haut (v = 0) qu'en bas (v = 1), des reflets en biais, de rares coins plus profonds. */
function glace(i, j, k, v = 0.5) {
  const reflet = (i - j + 60) % 7;
  if (reflet === 0) return BLANC;
  if (reflet === 1) return mix(GLACE_C, BLANC, 0.6);
  return mix(mix(GLACE_C, GLACE, v), BLANC, 0.35 * hache(i >> 1, j >> 1, k + 4));
}

const FACES = { devant: 1, dos: 2, droite: 3, gauche: 4, haut: 5, bas: 6 };

/** Un bruit doux, de -1 à 1 (des taches de quelques pixels, puis plus fines) : pour un bord qui ne soit pas droit. */
function bruit(a, b, k) {
  const un = (x, y, kk) => {
    const i = Math.floor(x), j = Math.floor(y), fx = x - i, fy = y - j, l = (t) => t * t * (3 - 2 * t);
    const h = (p, q) => hache(p, q, kk) * 2 - 1;
    const haut = h(i, j) + (h(i + 1, j) - h(i, j)) * l(fx), bas = h(i, j + 1) + (h(i + 1, j + 1) - h(i, j + 1)) * l(fx);
    return haut + (bas - haut) * l(fy);
  };
  return 0.7 * un(a / 3.2, b / 3.2, k) + 0.3 * un(a / 1.4, b / 1.4, k + 9);
}

/**
 * L'éveil 2 : ce texel de la partie nom est-il pris par la lave (ou la glace) ? Le bras du fruit, tout entier ; sa
 * jambe, presque ; le buste et la tête, avec un bord qui ondule et déborde du milieu par endroits ; quelques coulées
 * de l'autre côté. Le visage reste libre (seul le bord de la joue et le bas de la mâchoire, du côté du fruit).
 * m.bras : la lave ne tient encore que le bras, et mord l'épaule.
 */
function couvert(m, s, cote, nom, t) {
  const plein = prisDedans(m, s, cote, nom, t);
  if (plein || m.bras) return plein ? 1 : 0;
  // des veines fines (des fissures de lave, du givre) qui dépassent le bord et rampent sur la peau
  const [x, y, z] = t.centre, k = s > 0 ? 0 : 50;
  if (nom === "tete" && t.face === "devant") return 0;
  return Math.abs(bruit(y * 1.3 + z, x * 1.3 - z, 320 + k)) < 0.035 && s * x > -2.2 ? 2 : 0;
}

function prisDedans(m, s, cote, nom, t) {
  const [x, y, z] = t.centre, sx = s * x, autre = cote === "G" ? "D" : "G";
  if (nom === "bras" + cote) return true;
  if (m.bras) return nom === "corps" && sx + 1.5 * bruit(y, z + x, 300) > 2.4 && y > 20 + 2 * bruit(x, y, 310);
  switch (nom) {
    // un bord qui ondule (jamais droit), des taches qui passent le milieu, des trous de peau du côté du fruit
    case "corps": {
      const ph = s > 0 ? 0 : 2.1, k = s > 0 ? 0 : 50;
      const bord = 2.2 * Math.sin(y * 0.8 + z * 0.6 + ph) + 1.2 * Math.sin(y * 2.3 + ph * 2) + 1.5 * bruit(y, z, 301 + k) - 0.4;
      const ile = bruit(y * 1.2 + z, x * 1.2, 313 + k);
      return (sx + bord > 0 && ile > -0.5) || ile > 0.6;
    }
    case "tete":
      if (t.face === "devant") return (sx > 3 && bruit(y, x, 302) > -0.3) || (y < 25.6 && sx > 1.2 + 1.5 * bruit(y, x, 303));
      return sx + 1.6 * Math.sin(y * 1.1 + z * 0.7 + (s > 0 ? 0 : 1.7)) + 3 * bruit(y + z, z - x, 304 + (s > 0 ? 0 : 50)) > -0.4;
    case "jambe" + cote: return y + 7 * bruit(z + x * 0.7, y * 0.7, 305) > 3 && bruit(y, z - x, 314) > -0.55;
    case "jambe" + autre: return y > 6 + 4 * bruit(z, y, 306) && sx + 4.5 * bruit(z + x, y, 307) > 0.6;
    case "bras" + autre: return y > 19 + 3 * bruit(z + x, y, 308) && bruit(y, z + x, 309) > 0.05;
    default: return false;
  }
}

/** La couleur de la matière de l'éveil 2 sur ce texel (lave ou glace), éclairée par l, et si elle brille. */
function matiere(m, nom, t, l) {
  const [x, y, z] = t.centre, N = t.N;
  const [a, b] = Math.abs(N[2]) > 0.5 ? [x, y] : Math.abs(N[0]) > 0.5 ? [z, y] : [x, z];
  const k = FACES[t.face] + 10 * PARTIES(false).findIndex((p) => p[0] === nom);
  const c = m.sorte === "lave" ? magma(Math.floor(a + 40), Math.floor(b + 40), k, 3, 0.85) : glace(Math.floor(a + 40), Math.floor(40 - b), k, 1 - y / 32);
  return c[3] === 1 ? c.slice(0, 3) : clair(c, l);
}

/** L'armure (casque, plastron, jambières, bottes) : les morceaux, leur gonflement, et la hauteur qu'ils couvrent. */
const ARMURE = [
  ["tete", 1, (t) => !(t.face === "devant" && t.centre[1] < 29.5 && Math.abs(t.centre[0]) < 3.5)],
  ["corps", 1, () => true],
  ["brasD", 1, (t) => t.centre[1] > 16.5], ["brasG", 1, (t) => t.centre[1] > 16.5],
  ["jambeD", 0.5, (t) => t.centre[1] > 4.5], ["jambeG", 0.5, (t) => t.centre[1] > 4.5],
  ["jambeD", 1, (t) => t.centre[1] < 4.5], ["jambeG", 1, (t) => t.centre[1] < 4.5],
];

// ------------------------------------------------------------------ le joueur et sa main
const PIVOTS = { tete: [0, 24, 0], corps: [0, 12, 0], brasD: [-5, 22, 0], brasG: [5, 22, 0], jambeD: [-2, 12, 0], jambeG: [2, 12, 0] };

/**
 * Un joueur : sa peau, dans une pose. o.tete, o.brasD, o.brasG : [rx, rz, ry] ; o.jambeD, o.jambeG : rx ; o.buste penche
 * le haut du corps en avant ; o.lacet, o.penche, o.decale : tout le corps. o.main = { cote: "G" | "D", sorte: "lave" |
 * "glace" } change cette main ; o.teinte = { c, k } colore tout le corps (le givre). o.moitie = { sorte, bras } (l'éveil 2) :
 * la moitié du corps du côté de la main (gauche pour la lave, droite pour la glace) devient de la lave ou de la glace ;
 * avec bras, seulement ce bras (la moitié qui gagne). Rend les transformations des parties.
 */
function perso(sc, peau, o = {}) {
  const lacet = o.lacet ?? 0, penche = o.penche ?? 0, decale = o.decale ?? [0, 0, 0], buste = o.buste ?? 0;
  const monde = (v) => ajoute(rotY(rotX(sous(v, [0, 12, 0]), penche), lacet), ajoute(decale, [0, 12, 0]));
  const T = {};
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
  const elem = o.main;
  const brasElem = elem && "bras" + elem.cote;
  // l'éveil 2 : la moitié du corps (le côté de la main du fruit) ; s = +1 à gauche (+x), -1 à droite
  const m = o.moitie, s = m && (m.sorte === "lave" ? 1 : -1), cote = s > 0 ? "G" : "D";
  for (const [nom, boite, uv1, uv2, gonfle] of PARTIES(false)) {
    for (const [uv, g, d] of [[uv1, 0, 0], [uv2, gonfle, 0.02]]) for (const t of texels(boite, uv, g, 1)) {
      const pris = m ? couvert(m, s, cote, nom, t) : 0;
      if (pris === 2) {
        const i = (t.ty * 64 + t.tx) * 4;
        const l = sc.lumiere(T[nom].n(t.N), false);
        if (l < 0 || (g > 0 && peau.px[i + 3] < 128)) continue;
        sc.quad(t.coins.map(T[nom].p), m.sorte === "lave" ? LAVE_O : clair(mix(GLACE_C, BLANC, 0.5), l), d + 0.005, m.sorte === "lave");
        continue;
      }
      if (pris === 1) {
        // la matière remplace la peau et les habits (la seconde couche disparaît de ce côté)
        if (g > 0 || (nom === brasElem && t.centre[1] < 16.4)) continue;
        const l = sc.lumiere(T[nom].n(t.N), false);
        if (l < 0) continue;
        sc.quad(t.coins.map(T[nom].p), matiere(m, nom, t, l), d, true);
        continue;
      }
      const i = (t.ty * 64 + t.tx) * 4;
      if (peau.px[i + 3] < 128) continue;
      const l = sc.lumiere(T[nom].n(t.N), false);
      if (l < 0) continue;
      let c = clair([peau.px[i], peau.px[i + 1], peau.px[i + 2]], l);
      if (nom === brasElem) {
        // sous le gant, rien ; au-dessus, les veines (ou le givre) remontent un peu le long du bras
        const y = t.centre[1];
        if (y < 16.4 || (g > 0 && y < 20.5)) continue;
        if (y < 20.5 && hache(t.tx, t.ty, 77) < (20.5 - y) / 4.2) {
          if (elem.sorte === "lave") c = hache(t.tx, t.ty, 78) < 0.55 ? LAVE_O : clair(CROUTE_R, l);
          else c = clair(mix(GLACE, GLACE_C, hache(t.tx, t.ty, 78)), l);
        }
      }
      if (o.teinte) c = mix(c, o.teinte.c, o.teinte.k);
      sc.quad(t.coins.map(T[nom].p), c, d, false);
    }
  }
  if (o.armure) {
    for (const [nom, g, garde] of ARMURE) {
      const boite = PARTIES(false).find((p) => p[0] === nom)[1];
      for (const t of texels(boite, [0, 0], g, 1)) {
        if (!garde(t) || (nom === brasElem && t.centre[1] < 16.4)) continue;
        const l = sc.lumiere(T[nom].n(t.N), false);
        if (l < 0) continue;
        if (m && couvert(m, s, cote, nom, t) === 1) {
          sc.quad(t.coins.map(T[nom].p), matiere(m, nom, t, l), 0.04, true);
          continue;
        }
        const bord = hache(t.tx, t.ty, 311) < 0.12;
        const c = mix(o.armure, [255, 255, 255], bord ? 0.35 : 0.12 * hache(t.tx >> 1, t.ty >> 1, 312));
        sc.quad(t.coins.map(T[nom].p), clair(c, l), 0.04, false);
      }
    }
  }
  if (elem) (elem.sorte === "lave" ? mainLave : mainGlace)(sc, T[brasElem], elem.cote, elem);
  return T;
}

/** La main de lave : un gant de magma fissuré, des flammes qui montent, des gouttes qui tombent, un peu de fumée. */
function mainLave(sc, T, cote, o = {}) {
  const x0 = cote === "G" ? 4 : -8, cx = x0 + 2;
  pave(sc, [x0 - 0.35, 11.6, -2.35], [x0 + 4.35, 16.6, 2.35], T, (f, u, v, i, j) => magma(i, j, FACES[f]), 0.5, 0.01, true);
  for (let q = 0; q < (o.flammes ?? 6); q++) {
    const base = T.p([x0 + 0.6 + 2.8 * hache(q, 1, 31), 12.4 + 3.6 * hache(q, 2, 31), -1.6 + 3.2 * hache(q, 3, 31)]);
    flamme(sc, base, [0.3 * (hache(q, 4, 31) - 0.5), 1, 0.3 * (hache(q, 5, 31) - 0.5)], 2.4 + 3 * hache(q, 6, 31), 1.1 + 0.7 * hache(q, 7, 31));
  }
  if (o.gouttes !== false) {
    const bas = T.p([cx, 11.4, 0]);
    for (let q = 0; q < 3; q++) {
      const p = ajoute(bas, [(hache(q, 1, 33) - 0.5) * 2.4, -1.2 - q * 2.2 - hache(q, 2, 33), (hache(q, 3, 33) - 0.5) * 2.4]);
      cube(sc, p, 0.9 - 0.18 * q, [q, q * 2], brille(q ? LAVE_O : LAVE_J), true);
    }
  }
  if (o.fumee) {
    const haut = T.p([cx, 16, 0]);
    for (let q = 0; q < 4; q++) {
      cube(sc, ajoute(haut, [(hache(q, 4, 33) - 0.5) * 4, 5.5 + q * 2.6, (hache(q, 5, 33) - 0.5) * 4]), 1 + 0.35 * q, [q * 0.7, q * 1.3], FUMEE);
    }
  }
}

/** La main de glace : un gant de glace un peu plus gros, des cristaux qui en sortent, du givre qui flotte autour. */
function mainGlace(sc, T, cote, o = {}) {
  const x0 = cote === "G" ? 4 : -8, s = cote === "G" ? 1 : -1, cx = x0 + 2;
  const aile = o.lueur !== false;
  pave(sc, [x0 - 0.45, 11.5, -2.45], [x0 + 4.45, 16.8, 2.45], T, (f, u, v, i, j) => glace(i, j, FACES[f], f === "haut" ? 0 : v), 0.5, 0.01, aile);
  const ECLATS = [
    [[s * 2.2, 15.4, -1.2], [s, 0.5, -0.4], 3.8, 1.5],
    [[s * 2.2, 13.0, 0.9], [s, -0.15, 0.5], 2.8, 1.2],
    [[0, 16.6, -2], [s * 0.3, 1, -0.6], 3.4, 1.3],
    [[s * 1.0, 14.0, -2.4], [s * 0.4, 0.2, -1], 3.0, 1.2],
    [[s * 0.6, 11.8, 1.6], [s * 0.3, -0.6, 1], 2.2, 1.0],
    [[s * 2.0, 18.4, 0.2], [s, 0.9, 0.1], 2.6, 1.1],
  ];
  for (const [p, d, L, l] of ECLATS) cristal(sc, T.p(ajoute([cx, 0, 0], p)), T.n(d), L, l, aile);
  if (o.givre !== false) {
    const c = T.p([cx, 14, 0]);
    for (let q = 0; q < 7; q++) {
      const a = q * 1.9, r = 4.5 + 2 * hache(q, 1, 35);
      cube(sc, ajoute(c, [Math.cos(a) * r, (hache(q, 2, 35) - 0.3) * 7, Math.sin(a) * r]), 0.55, [q, q], BLANC, aile);
    }
  }
}

/** Le joueur qui brûle : des flammes sur tout le corps, comme le feu de Minecraft. */
function brule(sc, T, n = 14) {
  for (let q = 0; q < n; q++) {
    const devant = q % 2 === 0, cote = hache(q, 9, 41) < 0.5 ? -1 : 1;
    const loc = devant ? [(hache(q, 1, 41) - 0.5) * 14, 0.5 + 27 * hache(q, 2, 41), cote * 2.6] : [cote * 8.6, 0.5 + 20 * hache(q, 2, 41), (hache(q, 3, 41) - 0.5) * 4];
    flamme(sc, T.corps.p(loc), [0.25 * (hache(q, 4, 41) - 0.5), 1, 0.25 * (hache(q, 5, 41) - 0.5)], 3 + 3.5 * hache(q, 6, 41), 1.8 + 1.4 * hache(q, 7, 41));
  }
}

/** Le joueur gelé : des cristaux autour des pieds, des flocons qui tournent (la lenteur). */
function givre(sc, T, aile = true) {
  for (let q = 0; q < 8; q++) {
    const a = (q / 8) * TOUR + 0.3;
    cristal(sc, T.corps.p([Math.cos(a) * 4.6, 0, Math.sin(a) * 2.8]), [Math.cos(a) * 0.5, 1, Math.sin(a) * 0.5], 2.6 + 2.4 * hache(q, 1, 43), 1.5, aile);
  }
  for (let q = 0; q < 10; q++) {
    const a = q * 0.9, h = 4 + 22 * hache(q, 2, 43);
    cube(sc, T.corps.p([Math.cos(a) * 9, h, Math.sin(a) * 6]), 0.7, [q, q * 0.5], q % 3 ? BLANC : [150, 170, 200], aile);
  }
}

/** Une dalle d'herbe d'un bloc d'épaisseur ; dessus(x, z) peut changer la couleur du haut (fissures, givre). */
function sol(sc, x0, z0, x1, z1, dessus = null, haut = 0, base = HERBE) {
  pave(sc, [x0, haut - 16, z0], [x1, haut, z1], ID, (f, u, v, i, j) => {
    if (f === "bas") return null;
    if (f === "haut") {
      const x = x0 + i + 0.5, z = z0 + j + 0.5;
      return dessus?.(x, z) ?? clair(base, 0.88 + 0.18 * hache(Math.floor(x), Math.floor(z), 3));
    }
    if (base !== HERBE) return clair(base, 0.84 + 0.18 * hache(i, j, 5));
    return j < 3 - (hache(i, 0, 8) < 0.4 ? 1 : 0) ? clair(HERBE, 0.8 + 0.1 * hache(i, j, 4)) : clair(TERRE, 0.86 + 0.2 * hache(i, j, 5));
  }, 1);
}

/** Un bloc d'eau transparent (ou de glace), de a à b. */
function bain(sc, a, b, c, alpha) {
  const [x0, y0, z0] = a, [x1, y1, z1] = b;
  for (const f of [
    [[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]],
    [[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]],
    [[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0]],
    [[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]],
    [[x1, y0, z0], [x1, y0, z1], [x1, y1, z1], [x1, y1, z0]],
  ]) sc.voile(f, c, alpha);
}

/** Les arêtes blanches d'un bloc de glace. */
function aretes(sc, a, b, e = 0.6) {
  const [x0, y0, z0] = a, [x1, y1, z1] = b;
  const tige = (p, q) => pave(sc, sous(p, [e / 2, e / 2, e / 2]), ajoute(q, [e / 2, e / 2, e / 2]), ID, () => BLANC, 4);
  for (const y of [y0, y1]) {
    tige([x0, y, z0], [x1, y, z0]); tige([x0, y, z1], [x1, y, z1]);
    tige([x0, y, z0], [x0, y, z1]); tige([x1, y, z0], [x1, y, z1]);
  }
  for (const [x, z] of [[x0, z0], [x1, z0], [x0, z1], [x1, z1]]) tige([x, y0, z], [x, y1, z]);
}

/** Le lacet qui fait regarder un joueur placé en p vers le point c. */
const regarde = (p, c) => Math.atan2(c[0] - p[0], c[2] - p[2]);

// ------------------------------------------------------------------ l'épée (n'importe quelle arme marche)
const EPEE = (() => {
  const px = Buffer.alloc(16 * 16 * 4);
  const pt = (x, y, c) => { if (x >= 0 && x < 16 && y >= 0 && y < 16) px.set([...c, 255], (y * 16 + x) * 4); };
  for (let x = 5; x <= 15; x++) { pt(x, 14 - x, [52, 52, 62]); pt(x, 17 - x, [52, 52, 62]); }
  for (let x = 6; x <= 14; x++) { pt(x, 15 - x, [236, 238, 246]); pt(x, 16 - x, [176, 180, 192]); }
  pt(15, 0, [52, 52, 62]);
  for (const [x, y] of [[2, 7], [3, 8], [4, 9], [5, 10], [6, 11], [7, 12]]) pt(x, y, [70, 70, 82]);
  pt(4, 9, [120, 120, 134]); pt(5, 10, [120, 120, 134]);
  for (const [x, y] of [[4, 11], [3, 12], [2, 13]]) pt(x, y, [112, 80, 44]);
  for (const [x, y] of [[4, 12], [3, 13]]) pt(x, y, [72, 50, 26]);
  pt(1, 14, [70, 70, 82]); pt(2, 14, [52, 52, 62]); pt(1, 13, [52, 52, 62]);
  return px;
})();

/**
 * L'épée dans la main `cote` (lame vers l'avant et le bas, bras au repos) ; rend des points le long de la lame, pour y
 * mettre des flammes ou du givre.
 */
function epee(sc, T, cote, taille = 11) {
  const droite = [0, 0, 1], haut = [0, -1, 0];
  const prise = [cote === "G" ? 6 : -6, 11.6, 0];
  const ici = (a, b) => ajoute(fois(droite, (a / 16 - 0.5) * taille), fois(haut, (0.5 - b / 16) * taille));
  const centre = sous(prise, ici(3.5, 11.5));
  const n = croix(droite, haut);
  const l = sc.lumiere(T.n(n), true);
  for (let j = 0; j < 16; j++) for (let i = 0; i < 16; i++) {
    const k = (j * 16 + i) * 4;
    if (EPEE[k + 3] < 128) continue;
    const p = (a, b) => T.p(ajoute(centre, ici(a, b)));
    sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], clair([EPEE[k], EPEE[k + 1], EPEE[k + 2]], l), 0.05);
  }
  return [8, 10.5, 13].map((a) => T.p(ajoute(centre, ici(a + 0.5, 15.5 - a))));
}

/** Le coup d'épée : un croissant transparent de la couleur du fruit, devant le joueur. */
function croissant(sc, T, cote, couleur) {
  const x = cote === "G" ? 6 : -6;
  const n = 14;
  for (let k = 0; k < n; k++) {
    const a0 = -2.7 + (1.9 * k) / n, a1 = -2.7 + (1.9 * (k + 1)) / n;
    const pt = (a, r) => T.p([x, 22 + Math.cos(a) * -r, Math.sin(-a) * r]);
    const e = Math.sin((Math.PI * (k + 0.5)) / n);
    sc.voile([pt(a0, 13), pt(a1, 13), pt(a1, 13 + 7 * e), pt(a0, 13 + 7 * e)], couleur, 0.5 * e + 0.12);
  }
}

// ------------------------------------------------------------------ les jets (clic droit)
function repere(F, C) {
  const d = sous(C, F), ax = norme(d);
  const u = norme(croix(ax, [0, 1, 0]));
  return { d, ax, u, v: croix(u, ax), L: Math.hypot(...d) };
}

/** Le jet de lave : un flot de magma qui grossit, des éclats de croûte, de la fumée au-dessus, et ça éclabousse la cible. */
function jetLave(sc, F, C) {
  const { d, ax, u, v, L } = repere(F, C);
  const N = Math.round(L / 2);
  for (let k = 0; k <= N; k++) {
    const s = k / N, r = 1 + 2.4 * s;
    const P = ajoute(F, fois(d, s));
    cube(sc, P, 1.4 + 2 * s, [k * 0.7, k * 1.3], brille(mix(LAVE_J, LAVE_O, s * 0.7)), true);
    for (let q = 0; q < 2; q++) {
      const a = hache(k, q, 51) * TOUR, rr = r * (0.6 + 0.5 * hache(k, q, 52));
      const Q = ajoute(P, ajoute(fois(u, Math.cos(a) * rr), fois(v, Math.sin(a) * rr)));
      const croute = hache(k, q, 53) < 0.4;
      cube(sc, Q, 0.8 + 1.3 * s * hache(k, q, 54), [a, k], croute ? mix(CROUTE, CROUTE_R, 0.5) : brille(mix(LAVE_O, LAVE_R, hache(k, q, 55))), !croute);
    }
  }
  for (let k = 0; k < 7; k++) {
    const s = (k + 0.5) / 7;
    cube(sc, ajoute(ajoute(F, fois(d, s)), [0, 3.5 + 2.5 * hache(k, 1, 56) + 2.5 * s, 0]), 1 + 1.4 * s, [k, k * 2], FUMEE);
  }
  for (let q = 0; q < 12; q++) {
    const a = (q / 12) * TOUR;
    const dir = norme(ajoute(fois(ax, -0.5), ajoute(fois(u, Math.cos(a)), fois(v, Math.sin(a)))));
    cube(sc, ajoute(C, fois(dir, 3 + 4 * hache(q, 1, 57))), 0.8 + 0.7 * hache(q, 2, 57), [q, a], brille(q % 3 ? LAVE_O : LAVE_J), true);
  }
}

/** Le jet de glace : des éclats de glace qui filent tout droit dans une brume froide, et qui éclatent sur la cible. */
function jetGlace(sc, F, C) {
  const { d, ax, u, v, L } = repere(F, C);
  const N = Math.round(L / 2.6);
  for (let k = 0; k < N; k++) {
    const s = (k + 0.5) / N, r = 0.4 + 2.4 * s;
    for (let q = 0; q < 2; q++) {
      const a = hache(k, q, 61) * TOUR, rr = r * hache(k, q, 62);
      const P = ajoute(ajoute(F, fois(d, s)), ajoute(fois(u, Math.cos(a) * rr), fois(v, Math.sin(a) * rr)));
      const dev = ajoute(ax, ajoute(fois(u, (hache(k, q, 63) - 0.5) * 0.3), fois(v, (hache(k, q, 64) - 0.5) * 0.3)));
      cristal(sc, P, dev, 3 + 3 * s + 2 * hache(k, q, 65), 0.9 + s);
    }
    for (let q = 0; q < 3; q++) {
      const a = hache(k, q, 66) * TOUR, rr = (r + 1.5) * hache(k, q, 67);
      const P = ajoute(ajoute(F, fois(d, s)), ajoute(fois(u, Math.cos(a) * rr), fois(v, Math.sin(a) * rr)));
      cube(sc, P, 0.5 + 0.5 * hache(k, q, 68), [a, k], q ? BLANC : GLACE_C, true);
    }
  }
  for (let q = 0; q < 10; q++) {
    const a = (q / 10) * TOUR;
    const dir = norme(ajoute(fois(ax, -0.4), ajoute(fois(u, Math.cos(a)), fois(v, Math.sin(a)))));
    cristal(sc, ajoute(C, fois(dir, 1.5)), dir, 3 + 2.5 * hache(q, 1, 69), 1.4);
  }
}

// ------------------------------------------------------------------ les vignettes
const PEAU_LAVE = SKINS.efe, PEAU_GLACE = SKINS.kai, STEVE = SKINS.steve;
const LAVE = (o = {}) => ({ cote: "G", sorte: "lave", ...o });
const GEL = (o = {}) => ({ cote: "D", sorte: "glace", ...o });
/** Une ligne d'explication en bas de la vignette, sur une bande claire. */
function legende(sc, t, c = [70, 64, 70, 255], x = 14) {
  for (let y = sc.h - 40; y < sc.h; y++) for (let i = x - 8; i < Math.min(sc.w, x + t.length * 8 + 6); i++) sc.img.set([246, 246, 250, 255], (y * sc.w + i) * 4);
  ecrireTexte(sc.img, sc.w, t, x, sc.h - 30, 2, c);
}

function enPiedLave(sc) {
  perso(sc, PEAU_LAVE, { main: LAVE({ flammes: 9 }), brasG: [-0.55, 0.12], brasD: [0.08, -0.05], tete: [0.05, 0, -0.15] });
  sc.lueur(LAVE_O, 5, 1.3);
}

function enPiedGlace(sc) {
  perso(sc, PEAU_GLACE, { main: GEL(), brasD: [-0.55, -0.12], brasG: [0.08, 0.05], tete: [0.05, 0, 0.15] });
  sc.lueur(GLACE, 5, 1.1);
}

function procheLave(sc) {
  perso(sc, PEAU_LAVE, { main: LAVE({ flammes: 10 }), brasG: [-1.45, 0.1], brasD: [0.1, -0.05] });
  sc.lueur(LAVE_O, 6, 1.4);
}

function procheGlace(sc) {
  perso(sc, PEAU_GLACE, { main: GEL(), brasD: [-1.45, -0.1], brasG: [0.1, 0.05] });
  sc.lueur(GLACE, 6, 1.1);
}

function coupLave(sc) {
  sol(sc, -24, -16, 24, 60);
  const T = perso(sc, PEAU_LAVE, { main: LAVE({ gouttes: false }), brasD: [-1.75, -0.25], brasG: [-0.3, 0.25], jambeD: -0.3, jambeG: 0.25 });
  for (const p of epee(sc, T.brasD, "D", 15)) flamme(sc, p, [0, 1, 0.3], 3, 1.3);
  const cible = perso(sc, STEVE, { decale: [-2, 0, 34], lacet: Math.PI, penche: -0.18, brasD: [-0.5, -0.4], brasG: [-0.5, 0.4] });
  brule(sc, cible, 18);
  sc.lueur(LAVE_O, 4, 1.3);
  croissant(sc, T.brasD, "D", [255, 140, 40]);
  legende(sc, "+2 DEGATS ET LA CIBLE BRULE 4 S");
}

function coupGlace(sc) {
  sol(sc, -24, -16, 24, 60);
  const T = perso(sc, PEAU_GLACE, { main: GEL({ givre: false }), brasD: [-1.75, -0.25], brasG: [-0.3, 0.25], jambeD: -0.3, jambeG: 0.25 });
  for (const p of epee(sc, T.brasD, "D", 15)) cristal(sc, p, [0, 0.6, 1], 2.4, 1.1);
  const cible = perso(sc, STEVE, { decale: [-2, 0, 34], lacet: Math.PI, penche: -0.1, brasD: [-0.3, -0.25], brasG: [-0.3, 0.25], teinte: { c: GLACE_C, k: 0.36 } });
  givre(sc, cible);
  sc.lueur(GLACE, 4, 1.1);
  croissant(sc, T.brasD, "D", [170, 220, 255]);
  legende(sc, "+2 DEGATS ET LA CIBLE EST RALENTIE 3 S");
}

function tirLave(sc) {
  sol(sc, -24, -16, 28, 82);
  const T = perso(sc, PEAU_LAVE, { main: LAVE({ flammes: 4, gouttes: false }), brasG: [-1.57, 0.06], brasD: [0.2, -0.1], jambeD: 0.25, jambeG: -0.3 });
  const cible = perso(sc, STEVE, { decale: [4, 0, 70], lacet: Math.PI, penche: -0.3, brasD: [-0.9, -0.5], brasG: [-0.9, 0.5] });
  brule(sc, cible, 12);
  jetLave(sc, T.brasG.p([6, 11, 0]), [5, 21, 68]);
  sc.lueur(LAVE_O, 4, 1.3);
  legende(sc, "12 BLOCS, 6 DEGATS, BRULE 5 S. RIEN AU SOL");
}

function tirGlace(sc) {
  sol(sc, -28, -16, 24, 82);
  const T = perso(sc, PEAU_GLACE, { main: GEL({ givre: false }), brasD: [-1.57, -0.06], brasG: [0.2, 0.1], jambeD: -0.3, jambeG: 0.25 });
  const cible = perso(sc, STEVE, { decale: [-4, 0, 70], lacet: Math.PI, penche: -0.12, brasD: [-0.4, -0.3], brasG: [-0.4, 0.3], teinte: { c: GLACE_C, k: 0.38 } });
  givre(sc, cible);
  jetGlace(sc, T.brasD.p([-6, 11, 0]), [-5, 20, 68]);
  sc.lueur(GLACE, 4, 1.1);
  legende(sc, "12 BLOCS, 6 DEGATS, TRES LENT 4 S. RIEN AU SOL");
}

/** La pose du shift : un grand pas, le buste en avant, la main du fruit plaquée au sol. */
const POSE_SOL = { decale: [0, -5, 0], buste: 0.9, jambeD: -0.85, jambeG: 0.85, tete: [-0.75] };

function eruption(sc) {
  const R = 50, N = 7;
  const geysers = Array.from({ length: N }, (_, k) => (k / N) * TOUR + 0.3);
  sol(sc, -84, -84, 84, 84, (x, z) => {
    const r = Math.hypot(x, z);
    if (r < 5) return magma(Math.floor(x + 10), Math.floor(z + 10), 90, 3, 1.4);
    if (r > R + 4) return null;
    let p = 1e9;
    for (const g of geysers) {
      const t = x * Math.cos(g) + z * Math.sin(g);
      if (t < 0 || t > R) continue;
      p = Math.min(p, Math.abs(-x * Math.sin(g) + z * Math.cos(g) + 1.4 * Math.sin(t * 0.35 + g * 5)));
    }
    if (p < 0.8) return brille(LAVE_J);
    if (p < 1.6) return brille(LAVE_O);
    if (p < 3.4) return mix(clair(HERBE, 0.9), [52, 40, 30], 0.7 + 0.2 * hache(Math.floor(x), Math.floor(z), 91));
    return null;
  });
  geysers.forEach((g, k) => {
    const base = [R * Math.cos(g), 0, R * Math.sin(g)];
    const H = 26 + 12 * hache(k, 1, 71);
    for (let y = 0, n = 0; y < H; y += 1.8, n++) {
      const s = y / H, w = 3.8 * (1 - s * 0.55);
      for (let q = 0; q < 3; q++) {
        const a = hache(k * 40 + n, q, 72) * TOUR, rr = w * 0.6 * hache(k * 40 + n, q, 73);
        cube(sc, ajoute(base, [Math.cos(a) * rr, y, Math.sin(a) * rr]), w * (0.7 + 0.4 * hache(k * 40 + n, q, 74)), [a, y], brille(mix(LAVE_J, LAVE_R, s ** 0.8)), true);
      }
    }
    for (let q = 0; q < 4; q++) {
      const a = hache(k, q, 75) * TOUR;
      cube(sc, ajoute(base, [Math.cos(a) * 6, H * (0.35 + 0.4 * hache(k, q, 76)), Math.sin(a) * 6]), 1.1, [a, q], brille(LAVE_O), true);
      cube(sc, ajoute(base, [(hache(k, q, 77) - 0.5) * 6, H + 3 + q * 3, (hache(k, q, 78) - 0.5) * 6]), 2.4 + q * 0.8, [a, q], FUMEE);
    }
  });
  perso(sc, PEAU_LAVE, { ...POSE_SOL, main: LAVE({ flammes: 8, gouttes: false }), brasG: [-1.4, 0.15], brasD: [0.6, -0.5] });
  // la cible, projetée en l'air entre deux geysers
  const p = [38 * Math.cos(-0.15), 22, 38 * Math.sin(-0.15)];
  const cible = perso(sc, STEVE, { decale: p, lacet: regarde(p, [0, 0, 0]), penche: -0.9, brasD: [-2.8, -0.4], brasG: [-2.8, 0.4], jambeD: 0.3, jambeG: -0.2 });
  brule(sc, cible, 14);
  sc.lueur(LAVE_O, 4, 1.3);
  legende(sc, "5 BLOCS AUTOUR : 8 DEGATS, EN L'AIR, BRULE");
}

function ereGlaciaire(sc) {
  const R = 64;
  const g = 1.25, p = [36 * Math.cos(g), 0, 36 * Math.sin(g)];
  sol(sc, -84, -84, 84, 84, (x, z) => {
    const r = Math.hypot(x, z), a = Math.atan2(z, x);
    if (r > R + 5 * Math.sin(a * 7) + 4 * hache(Math.floor(x / 4), Math.floor(z / 4), 81)) return null;
    if (fissure(x + 200, z + 200, 82, 7) < 0.55) return GLACE;
    return mix(GLACE_C, BLANC, 0.2 + 0.6 * hache(Math.floor(x), Math.floor(z), 83));
  });
  for (let k = 0; k < 22; k++) {
    const a = (k / 22) * TOUR + 0.25 * hache(k, 1, 84), r = 24 + 36 * hache(k, 2, 84);
    const base = [r * Math.cos(a), -1, r * Math.sin(a)];
    if (Math.hypot(base[0] - p[0], base[2] - p[2]) < 13) continue;
    const L = (8 + 18 * (1 - Math.abs(r - 42) / 30)) * (0.7 + 0.5 * hache(k, 3, 84));
    cristal(sc, base, [Math.cos(a) * 0.45, 1, Math.sin(a) * 0.45], L, 3.5 + 3 * hache(k, 4, 84));
    for (let q = 0; q < 2; q++) {
      const b = a + (q ? 0.12 : -0.12);
      cristal(sc, [(r + 4) * Math.cos(b), -1, (r + 4) * Math.sin(b)], [Math.cos(b) * 0.7, 1, Math.sin(b) * 0.7], L * 0.45, 2.2);
    }
  }
  perso(sc, PEAU_GLACE, { ...POSE_SOL, main: GEL(), brasD: [-1.4, -0.15], brasG: [0.6, 0.5] });
  const cible = perso(sc, STEVE, { decale: p, lacet: regarde(p, [0, 0, 0]), brasD: [-0.6, -0.45], brasG: [-0.6, 0.45], teinte: { c: GLACE_C, k: 0.45 } });
  givre(sc, cible);
  aretes(sc, sous(p, [8, 0, 8]), ajoute(p, [8, 18, 8]));
  sc.lueur(GLACE, 4, 1.1);
  bain(sc, sous(p, [8, 0, 8]), ajoute(p, [8, 18, 8]), [176, 222, 255], 0.32);
  legende(sc, "5 BLOCS AUTOUR : 6 DEGATS, GELE SUR PLACE 2 S");
}

const COEUR = ["011000110", "133101221", "132222221", "122222221", "012222210", "001222100", "000121000", "000010000"];

/** La barre de vie : `valeur` cœurs sur 10, de la couleur du poison. */
function coeurs(img, W, x0, y0, k, valeur) {
  const PLEIN = [146, 158, 24], REFLET = [214, 222, 120], VIDE = [62, 44, 44], BORD = [24, 18, 18];
  for (let n = 0; n < 10; n++) for (let j = 0; j < 8; j++) for (let i = 0; i < 9; i++) {
    const m = COEUR[j][i];
    if (m === "0") continue;
    const rempli = valeur >= n + 1 || (valeur > n && i < 4.5);
    const c = m === "1" ? BORD : !rempli ? VIDE : m === "3" ? REFLET : PLEIN;
    for (let dy = 0; dy < k; dy++) for (let dx = 0; dx < k; dx++) img.set([...c, 255], ((y0 + j * k + dy) * W + x0 + n * 10 * k + i * k + dx) * 4);
  }
}

function dansLEau(sc) {
  sol(sc, -44, -30, 44, 30, null, -16, SABLE);
  // les deux ont mal (le rouge des dégâts de Minecraft)
  const mal = { c: [255, 60, 60], k: 0.2 };
  perso(sc, PEAU_LAVE, { decale: [-18, -16, 0], lacet: 0.25, teinte: mal, main: LAVE({ gouttes: false, flammes: 3 }), brasG: [-0.9, 0.5], brasD: [-0.9, -0.5], tete: [-0.2] });
  perso(sc, PEAU_GLACE, { decale: [18, -16, 0], lacet: -0.25, teinte: mal, main: GEL({ lueur: false }), brasD: [-0.9, -0.5], brasG: [-0.9, 0.5], tete: [-0.2] });
  // la lave dans l'eau : de la vapeur qui monte autour de lui
  for (let q = 0; q < 10; q++) {
    const cote = q % 2 ? -1 : 1;
    cube(sc, [-18 + cote * (8 + q * 0.6) - q * 0.5, -2 + q * 2.6, -5 - q * 0.8 + 3 * hache(q, 2, 95)], 1.6 + 0.4 * q, [q, q * 0.8], VAPEUR);
  }
  // des ronds dans l'eau autour des deux, et des bulles
  for (const x of [-18, 18]) for (let q = 0; q < 16; q++) {
    const a = (q / 16) * TOUR;
    pave(sc, [x + Math.cos(a) * 8 - 0.8, -3.1, Math.sin(a) * 6 - 0.8], [x + Math.cos(a) * 8 + 0.8, -2.8, Math.sin(a) * 6 + 0.8], ID, () => [214, 232, 255], 2);
    if (q % 4 === 0) cube(sc, [x + Math.cos(a + 0.4) * 5, -7 - 4 * hache(q, x, 96), Math.sin(a + 0.4) * 4], 0.8, [q, a], [190, 220, 255]);
  }
  sc.lueur(LAVE_O, 4, 1.0);
  bain(sc, [-44, -16, -30], [44, -3, 30], EAU, 0.4);
  sc.voile([[-44, -3, -30], [44, -3, -30], [44, -3, 30], [-44, -3, 30]], [200, 225, 255], 0.18);
  for (const [x, c] of [[-18, [214, 40, 40, 255]], [18, [214, 40, 40, 255]]]) {
    const [sx, sy] = sc.vue([x, 24, 0]);
    ecrireTexte(sc.img, sc.w, "-1/2", Math.round(sx) - 22, Math.round(sy) - 30, 3, c);
  }
  coeurs(sc.img, sc.w, 14, 48, 3, 7.5);
  legende(sc, "-1/2 COEUR CHAQUE SECONDE, COMME DU POISON");
}

function passifs(sc) {
  // la lave : il se tient dedans, rien ne le brûle
  sol(sc, -66, -22, -6, 22, (x, z) => magma(Math.floor(x + 100), Math.floor(z + 100), 96, 5, 2.6), -6, CROUTE);
  perso(sc, PEAU_LAVE, { decale: [-36, -12, 0], lacet: 0.35, main: LAVE({ gouttes: false }), brasG: [-0.4, 0.2], brasD: [0.1, -0.1] });
  for (let q = 0; q < 9; q++) {
    const a = (q / 9) * TOUR, r = 12 + 8 * hache(q, 1, 97);
    flamme(sc, [-36 + Math.cos(a) * r, -6, Math.sin(a) * r * 0.8], [0, 1, 0], 3 + 4 * hache(q, 2, 97), 2.2);
  }
  // la glace : l'eau gèle sous ses pieds, puis refond derrière lui
  sol(sc, 6, -22, 66, 22, null, -16, SABLE);
  for (const [bx, bz, k] of [[28, -8, 0.0], [44, -8, 0.0], [28, 8, 0.0], [44, 8, 0.0], [12, -8, 0.5], [12, 8, 0.7]]) {
    const t = (x, z) => (fissure(x + bx, z + bz, 98, 5) < 0.5 ? GLACE : mix(GLACE_C, BLANC, 0.2 + 0.5 * hache(Math.floor(x), Math.floor(z), 99)));
    pave(sc, [bx - 8, -2, bz - 8], [bx + 8, 0, bz + 8], ID, (f, u, v, i, j) => (k && hache(i, j, 100) < k ? null : f === "haut" ? t(i, j) : GLACE), 1);
  }
  perso(sc, PEAU_GLACE, { decale: [36, 0, 0], lacet: -0.35, main: GEL({ lueur: false }), brasD: [-0.4, -0.2], brasG: [0.1, 0.1], jambeD: -0.35, jambeG: 0.3 });
  sc.lueur(LAVE_O, 4, 1.1);
  bain(sc, [6, -16, -22], [66, -2, 22], EAU, 0.3);
  legende(sc, "FEU ET LAVE : RIEN", [190, 70, 20, 255], 14);
  legende(sc, "MARCHE SUR L'EAU", [40, 110, 200, 255], 252);
}

// ------------------------------------------------------------------ la planche
const CW = 440, CH = 520, M = 34, HAUT = 560;
const NOIR = [30, 30, 40, 255], ORANGE = [214, 92, 20, 255], BLEU = [40, 110, 200, 255];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

function haut(img, W) {
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, "FRUITS DU DEMON : EVEIL 1", 30, 22, 5, NOIR);
  const colonnes = [
    [30, ORANGE, "MAGU MAGU NO MI : LAVE (AKAINU)", [
      "TOUCHE EVEIL : LA MAIN GAUCHE DEVIENT DE LA LAVE",
      "CLIC GAUCHE : TOUTE ARME +2 DEGATS, ENFLAMME 4 S",
      "CLIC DROIT : JET DE LAVE, 12 BLOCS, 6 DEGATS",
      "    LA CIBLE BRULE 5 S. RECHARGE 6 S",
      "SHIFT : ERUPTION, 5 BLOCS AUTOUR, 8 DEGATS",
      "    PROJETTE EN L'AIR ET BRULE. RECHARGE 20 S",
      "TOUJOURS : LE FEU ET LA LAVE NE LUI FONT RIEN",
    ]],
    [900, BLEU, "HIE HIE NO MI : GLACE (AOKIJI)", [
      "TOUCHE EVEIL : LA MAIN DROITE DEVIENT DE LA GLACE",
      "CLIC GAUCHE : TOUTE ARME +2 DEGATS, RALENTIT 3 S",
      "CLIC DROIT : JET DE GLACE, 12 BLOCS, 6 DEGATS",
      "    LA CIBLE EST TRES LENTE 4 S. RECHARGE 6 S",
      "SHIFT : ERE GLACIAIRE, 5 BLOCS AUTOUR, 6 DEGATS",
      "    GELE SUR PLACE 2 S. RECHARGE 20 S",
      "EVEILLE : MARCHE SUR L'EAU (ELLE GELE, PUIS FOND)",
    ]],
  ];
  for (const [x, c, titre, lignes] of colonnes) {
    ecrireTexte(img, W, titre, x, 100, 4, c);
    lignes.forEach((t, k) => ecrireTexte(img, W, t, x, 160 + k * 34, 3, NOIR));
  }
  rect(img, W, 30, 410, W - 30, 413, [200, 200, 210, 255]);
  [
    "LES DEUX : DANS L'EAU, -1/2 ♥ CHAQUE SECONDE, COMME DU POISON",
    "RIEN NE RESTE AU SOL : LA LAVE ET LA GLACE DISPARAISSENT TOUT DE SUITE",
    "EVEIL 2 MIN, PUIS 2 MIN DE REPOS, COMME LES AUTRES POUVOIRS DE NIVEAU 1",
  ].forEach((t, k) => ecrireTexte(img, W, t, 30, 432 + k * 34, 3, NOIR));
}

const ACTION = { echelle: 5.6, tangage: 18, pied: 300 };
const vues = [
  ["LAVE : MAIN GAUCHE", enPiedLave, { lacet: -28, echelle: 11, pied: 490 }],
  ["GLACE : MAIN DROITE", enPiedGlace, { lacet: 28, echelle: 11, pied: 490 }],
  ["MAIN DE LAVE", procheLave, { lacet: -72, echelle: 20, pied: 270, centre: [5, 22, 5], tangage: 8 }],
  ["MAIN DE GLACE", procheGlace, { lacet: 72, echelle: 20, pied: 270, centre: [-5, 22, 5], tangage: 8 }],
  ["CLIC GAUCHE : ENFLAMME", coupLave, { ...ACTION, echelle: 6.6, lacet: 64, centre: [0, 13, 18] }],
  ["CLIC GAUCHE : RALENTIT", coupGlace, { ...ACTION, echelle: 6.6, lacet: 64, centre: [0, 13, 18] }],
  ["CLIC DROIT : JET DE LAVE", tirLave, { ...ACTION, echelle: 4.4, lacet: -64, centre: [0, 12, 34] }],
  ["CLIC DROIT : JET DE GLACE", tirGlace, { ...ACTION, echelle: 4.4, lacet: 64, centre: [0, 12, 34] }],
  ["SHIFT : ERUPTION", eruption, { lacet: -30, tangage: 30, echelle: 2.9, pied: 290, centre: [0, 10, 0] }],
  ["SHIFT : ERE GLACIAIRE", ereGlaciaire, { lacet: 30, tangage: 30, echelle: 2.9, pied: 290, centre: [0, 10, 0] }],
  ["DANS L'EAU : POISON", dansLEau, { lacet: -16, tangage: 14, echelle: 4.6, pied: 330, centre: [0, -4, 0] }],
  ["PASSIFS", passifs, { lacet: -10, tangage: 22, echelle: 3.2, pied: 320, centre: [0, 4, 0] }],
];

// les briques servent aussi à la planche des fruits eux-mêmes (objets.mjs)
export { ajoute, sous, fois, rotX, rotY, TOUR, ID, brille, pave, cube, pointe, flamme, cristal, perso, brule, givre, sol, legende, regarde, epee, EPEE, fissure,
  magma, glace, jetLave, jetGlace, croissant, aretes, bain, FACES, couvert, matiere,
  CROUTE, CROUTE_R, LAVE_R, LAVE_O, LAVE_J, GLACE, GLACE_C, BLANC, FUMEE, HERBE, TERRE, FOND, SKINS, PEAU_LAVE, PEAU_GLACE, STEVE, LAVE, GEL };

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  const COLONNES = 4, lignes = Math.ceil(vues.length / COLONNES);
  const W = CW * COLONNES, H = HAUT + (M + CH) * lignes;
  const img = Buffer.alloc(W * H * 4, 255);
  haut(img, W);
  vues.forEach(([titre, dessin, o], k) => {
    const c = k % COLONNES, l = Math.floor(k / COLONNES);
    const sc = scene({ w: CW, h: CH, fond: FOND, ...o });
    dessin(sc);
    for (let y = 0; y < CH; y++) sc.img.copy(img, ((HAUT + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(img, W, titre, c * CW + 12, HAUT + l * (M + CH) + 10, 3, k % 2 ? BLEU : ORANGE);
  });
  ecrirePng(path.join(DOC, "planche-fruits.png"), W, H, img);
  console.log("ok", W, H);
}
