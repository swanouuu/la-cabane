// Maquettes de deux pouvoirs demandés le 2026-11-12 : les Ténèbres et la Lumière, trois niveaux chacun, des attaques à
// chaque niveau et un ulti au niveau 3. Rien en jeu pour l'instant.
//   Ténèbres : 1 la flaque noire qui monte, veines noires ; 2 son ombre se relève (un double noir qui copie ses coups) ;
//              3 la nuit tombe, tenue noire aux veines violettes, couronne de piques ; ulti : l'Éclipse (un trou noir).
//   Lumière  : 1 un rayon du ciel, une auréole ; 2 deux ailes de lumière ; 3 tenue blanc et or, six ailes, un soleil
//              dans le dos ; ulti : le Jugement (des épées de lumière, puis une colonne de lumière).
// node rendu.mjs [tenebres|lumiere] [niveaux|attaques] → ../planche-<pouvoir>-<sorte>.png (sans rien : les quatre)
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, PARTIES, texels, clair, mix, hache, norme, croix, scene, ecrireTexte, lum } from "../../fruits/generateur/moteur.mjs";
import { ajoute, sous, fois, rotX, rotY, TOUR, brille, pave, cube, pointe, sol, legende, regarde, epee, croissant, SKINS, STEVE } from "../../fruits/generateur/rendu.mjs";
import { cubeSimple, boule, fantome } from "../../minato2/generateur/effets.mjs";
import { finir, trait, zigzag, pointSur, dessus, MEMBRES, LUEURS, oeil, ZURI, CW, CH, M, rect } from "../../foudre/generateur/rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const rotZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];
const lerpV = (a, b, t) => a.map((v, i) => v + (b[i] - v) * t);

// ------------------------------------------------------------------ couleurs et lueurs
const NOIR = [8, 4, 12], OMBRE = [26, 12, 38], VIOLET = [150, 70, 255], VIOLET_C = [214, 176, 255];
const OR = [255, 200, 70], OR_C = [255, 238, 170], BLANC = [255, 255, 248];
const SUIES = [[8, 4, 12], [24, 12, 36], [44, 22, 64]];
LUEURS.violet = { repere: [5, 1, 7], coeur: [240, 220, 255], c: [150, 60, 255], k: 0.95 };
LUEURS.violetDoux = { repere: [5, 1, 9], coeur: [196, 150, 255], c: [120, 50, 220], k: 0.5 };
LUEURS.or = { repere: [7, 5, 1], coeur: [255, 253, 236], c: [255, 190, 60], k: 0.95 };
LUEURS.orDoux = { repere: [7, 5, 3], coeur: [255, 244, 200], c: [255, 210, 110], k: 0.5 };
/** Une couleur qui brillera (la lueur posée à la fin par finir). */
const lumi = (nom) => brille(LUEURS[nom].repere);

// ------------------------------------------------------------------ un joueur, à n'importe quelle taille
const PIVOTS = { tete: [0, 24, 0], corps: [0, 12, 0], brasD: [-5, 22, 0], brasG: [5, 22, 0], jambeD: [-2, 12, 0], jambeG: [2, 12, 0] };

/**
 * Un joueur comme joueur() de Minato 2, mais à la taille k (k = 1 : un joueur). o.voile = alpha : transparent ;
 * o.lueur : ses couleurs ne s'assombrissent pas (il brille) ; o.aile : la lueur de la scène se pose autour de lui.
 */
function bonhomme(sc, peau, o = {}) {
  const k = o.k ?? 1, lacet = o.lacet ?? 0, penche = o.penche ?? 0, decale = o.decale ?? [0, 0, 0];
  const monde = (v) => ajoute(fois(rotY(rotX(sous(v, [0, 12, 0]), penche), lacet), k), ajoute(decale, [0, 12 * k, 0]));
  const T = { k };
  for (const [nom, pv] of Object.entries(PIVOTS)) {
    const a = o[nom];
    const [rx = 0, rz = 0, ry = 0] = typeof a === "number" ? [a] : a ?? [];
    const local = (v) => ajoute(rotY(rotX(rotZ(sous(v, pv), rz), rx), ry), pv);
    T[nom] = { p: (v) => monde(local(v)), n: (v) => norme(rotY(rotX(rotY(rotX(rotZ(v, rz), rx), ry), penche), lacet)) };
  }
  for (const [nom, boite, uv1, uv2, gonfle] of PARTIES(false)) {
    for (const [uv, g, d] of [[uv1, 0, 0], [uv2, gonfle, 0.02]]) {
      if (o.voile && g > 0) continue;
      for (const t of texels(boite, uv, g, 1)) {
        const i = (t.ty * 64 + t.tx) * 4;
        if (peau.px[i + 3] < 128) continue;
        const l = sc.lumiere(T[nom].n(t.N), false);
        if (l < 0) continue;
        let c = clair([peau.px[i], peau.px[i + 1], peau.px[i + 2]], o.lueur ? 0.86 + (0.24 * (l - 0.62)) / 0.42 : l);
        if (o.teinte) c = mix(c, o.teinte.c, o.teinte.k);
        if (o.voile) sc.voiles.push([t.coins.map(T[nom].p), c, o.voile]);
        else sc.quad(t.coins.map(T[nom].p), c, d, !!o.aile);
      }
    }
  }
  return T;
}

/** Les yeux qui brillent : leurs deux pixels de chaque côté, de la couleur de la lueur `nom`. */
function yeux(sc, T, nom) {
  for (const x of [-3, 1]) pave(sc, [x, 27, 4.01], [x + 2, 28, 4.06], T.tete, () => lumi(nom), 1, 0.2);
}

/** Une cible (Steve), tournée vers le joueur par défaut. */
function cible(sc, p, o = {}) {
  return bonhomme(sc, o.peau ?? STEVE, {
    decale: p, lacet: o.lacet ?? Math.PI, penche: o.penche ?? -0.15, brasD: o.brasD ?? [-0.5, -0.45], brasG: o.brasG ?? [-0.5, 0.45],
    jambeD: o.jambeD, jambeG: o.jambeG, tete: o.tete, teinte: o.teinte, aile: o.aile, lueur: o.lueur,
  });
}

// ------------------------------------------------------------------ les skins
function peindre(skin, f) {
  const px = Buffer.from(skin.px);
  for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) {
    const i = (y * 64 + x) * 4;
    if (px[i + 3] < 128) continue;
    const c = f([px[i], px[i + 1], px[i + 2]], x, y);
    if (c) px.set(c.map((v) => Math.max(0, Math.min(255, Math.round(v)))), i);
  }
  return { w: 64, h: 64, px };
}
const VISAGE = (x, y) => y >= 8 && y < 16 && ((x >= 8 && x < 16) || (x >= 40 && x < 48));
const rampe = (R, v) => {
  for (let k = 1; k < R.length; k++) if (v <= R[k][0]) return mix(R[k - 1][1], R[k][1], (v - R[k - 1][0]) / (R[k][0] - R[k - 1][0]));
  return R[R.length - 1][1];
};

/** Des veines : n lignes qui montent des mains, des pieds et de la taille, en zigzag, sur les deux couches du skin. */
function veines(skin, n, graine, couleur, haut = 1) {
  const px = Buffer.from(skin.px);
  const parts = PARTIES(false).filter(([nom]) => nom !== "tete");
  for (let q = 0; q < n; q++) {
    const [, b, uv1, uv2] = parts[Math.floor(hache(q, 1, graine) * parts.length)];
    const w = b[3] - b[0], h = b[4] - b[1], d = b[5] - b[2];
    const [fu, fw] = [[d, w], [2 * d + w, w], [0, d], [d + w, d]][Math.floor(hache(q, 2, graine) * 4)];
    let x = Math.floor(hache(q, 3, graine) * fw);
    const L = Math.max(2, Math.round(h * haut * (0.45 + 0.55 * hache(q, 4, graine))));
    for (let s = 0; s < L; s++) {
      const yy = h - 1 - s;
      for (const uv of [uv1, uv2]) {
        const i = ((uv[1] + d + yy) * 64 + uv[0] + fu + x) * 4;
        if (uv === uv2 && px[i + 3] < 128) continue;
        px.set([...couleur, 255], i);
      }
      if (hache(q, 10 + s, graine) < 0.5) x = Math.max(0, Math.min(fw - 1, x + (hache(q, 40 + s, graine) < 0.5 ? -1 : 1)));
    }
  }
  return { w: 64, h: 64, px };
}

/** Le skin `autre` sous la hauteur h (0 aux pieds, 32 en haut de la tête) ; depuisHaut : au-dessus de h. */
function jusqua(base, autre, h, depuisHaut = false) {
  const px = Buffer.from(base.px);
  for (const [, boite, uv1, uv2] of PARTIES(false)) for (const uv of [uv1, uv2]) for (const t of texels(boite, uv, 0, 1)) {
    if (depuisHaut ? t.centre[1] < h : t.centre[1] > h) continue;
    const i = (t.ty * 64 + t.tx) * 4;
    autre.px.copy(px, i, i, i + 4);
  }
  return { w: 64, h: 64, px };
}

// Ténèbres : son skin, avec des veines noires (1), plus de veines (2), puis la tenue noire aux veines violettes (3)
const BASE_T = SKINS.efe;
const T1 = veines(BASE_T, 30, 11, [22, 8, 34], 0.55);
const T2 = veines(T1, 34, 12, [34, 12, 52], 1);
const RAMPE_T = [[0, [6, 3, 10]], [70, [18, 8, 28]], [130, [36, 18, 56]], [200, [62, 34, 96]], [255, [96, 60, 140]]];
const T3 = veines(peindre(BASE_T, (c, x, y) => (y >= 16 ? rampe(RAMPE_T, lum(c)) : VISAGE(x, y) ? mix(c, [70, 50, 96], 0.3) : rampe(RAMPE_T, lum(c) * 0.9))), 40, 13, [196, 120, 255], 1);
const OMBRE_SKIN = peindre(BASE_T, (c, x, y) => mix(NOIR, [30, 16, 44], 0.7 * hache(x, y, 5)));

// Lumière : son skin (1, 2), puis la tenue blanc et or (3) avec des lignes d'or
const BASE_L = ZURI;
const RAMPE_L = [[0, [150, 100, 18]], [60, [214, 156, 36]], [110, [246, 212, 116]], [170, [250, 246, 234]], [255, [255, 255, 255]]];
const L3 = veines(peindre(BASE_L, (c, x, y) => (y >= 16 ? rampe(RAMPE_L, lum(c) * 1.15 + 20) : VISAGE(x, y) ? null : rampe(RAMPE_L, lum(c) * 1.2 + 30))), 22, 21, [220, 160, 34], 1);

// ------------------------------------------------------------------ les briques : ombre
/** Des bouffées de fumée noire autour de c, qui montent (r : le rayon au sol, h : la hauteur). */
function fumee(sc, c, n, graine, o = {}) {
  const r = o.r ?? 6, h = o.h ?? 14, s = o.s ?? 2.4;
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, graine) * TOUR, t = hache(q, 2, graine) ** 0.8, rr = r * Math.sqrt(hache(q, 3, graine)) * (1 + t * (o.evase ?? 0.6));
    const p = ajoute(c, [Math.cos(a) * rr, t * h, Math.sin(a) * rr]);
    cube(sc, p, s * (0.5 + hache(q, 4, graine)) * (1 - 0.55 * t), [hache(q, 5, graine) * 3, hache(q, 6, graine) * 3], SUIES[Math.floor(hache(q, 7, graine) * 3)]);
  }
}

/** De la fumée noire collée au corps, qui s'en échappe. */
function fumeeCorps(sc, T, n, graine, o = {}) {
  const m = o.membres ?? MEMBRES;
  for (let q = 0; q < n; q++) {
    const p = ajoute(pointSur(T, m[q % m.length], q, graine, 0.6), [0, hache(q, 8, graine) * 3 * T.k, 0]);
    cube(sc, p, T.k * (o.s ?? 1.6) * (0.5 + hache(q, 9, graine)), [hache(q, 5, graine) * 3, hache(q, 6, graine) * 3], SUIES[q % 3]);
  }
}

/** Une mèche qui ondule de A vers dir, sur L, et qui brille. */
function meche(sc, A, dir, L, graine, nom = "violetDoux", large = 0.5) {
  const d = norme(dir), x = croix(d, [0, 1, 0]);
  const e = norme(Math.hypot(...x) < 0.2 ? croix(d, [1, 0, 0]) : x), f = croix(d, e), pts = [];
  for (let s = 0; s <= 14; s++) {
    const t = s / 14;
    pts.push(ajoute(A, ajoute(fois(d, t * L), ajoute(fois(e, Math.sin(t * 6 + graine) * L * 0.09 * t), fois(f, Math.cos(t * 5 + graine * 2) * L * 0.06 * t)))));
  }
  trait(sc, pts, large, nom);
}

/** Une flaque d'ombre au sol (pour sol(..., dessus)) : noire, au bord violet qui brille. */
const flaque = (cx, cz, R, graine = 0) => (x, z) => {
  const dx = x - cx, dz = z - cz, r = Math.hypot(dx, dz), a = Math.atan2(dz, dx);
  const bord = R * (1 + 0.14 * Math.sin(a * 5 + graine) + 0.08 * Math.sin(a * 11 + graine * 3));
  if (r < bord - 1.3) return brille(mix(NOIR, OMBRE, 0.6 * hache(Math.floor(x / 2), Math.floor(z / 2), graine)));
  if (r < bord) return lumi("violetDoux");
  return null;
};

/** Une vrille d'ombre : une chaîne de cubes noirs qui ondule de A à B, un fil violet dedans. */
function vrille(sc, A, B, graine, s0 = 2.2, s1 = 0.8, n = 16) {
  const d = sous(B, A), x = croix(norme(d), [0, 1, 0]);
  const e = norme(Math.hypot(...x) < 0.2 ? croix(norme(d), [1, 0, 0]) : x), pts = [];
  for (let q = 0; q <= n; q++) {
    const t = q / n, p = ajoute(ajoute(A, fois(d, t)), fois(e, Math.sin(t * 7 + graine) * 2 * (1 - t * 0.5)));
    cube(sc, p, s0 + (s1 - s0) * t, [t * 3 + graine, t * 2], SUIES[q % 2]);
    pts.push(p);
  }
  trait(sc, pts, 0.35, "violetDoux");
}

const facesPave = ([x0, y0, z0], [x1, y1, z1]) => [
  [[0, 0, 1], [[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]]], [[0, 0, -1], [[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]]],
  [[-1, 0, 0], [[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]]], [[1, 0, 0], [[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]]],
  [[0, 1, 0], [[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]]], [[0, -1, 0], [[x0, y0, z1], [x1, y0, z1], [x1, y0, z0], [x0, y0, z0]]],
];
/** Une aura : chaque partie du corps dans des coques transparentes (couches : [marge, alpha, couleur]). */
function aura(sc, T, couches) {
  for (const [nom, b] of PARTIES(false)) for (const [m, alpha, c] of couches) {
    const bas = m * (nom.startsWith("jambe") ? 0.2 : 1);
    for (const [N, coins] of facesPave([b[0] - m, b[1] - bas, b[2] - m], [b[3] + m, b[4] + m, b[5] + m])) {
      if (N[1] < 0 || sc.lumiere(T[nom].n(N), false) < 0) continue;
      sc.voiles.push([coins.map(T[nom].p), c, alpha]);
    }
  }
}
const AURA_T = [[1.1, 0.34, [16, 6, 26]], [2.6, 0.16, [100, 44, 170]]];
const AURA_L = [[1.1, 0.22, [255, 222, 130]], [2.6, 0.1, [255, 250, 224]]];

/** Son ombre vivante : un double tout noir, plus grand, aux yeux violets, derrière lui. */
function ombreVivante(sc, o = {}) {
  const D = bonhomme(sc, OMBRE_SKIN, { k: o.k ?? 1.3, decale: o.decale ?? [-10, 0, -14], lacet: o.lacet ?? 0.15, penche: o.penche, aile: o.aile, ...o.pose });
  yeux(sc, D, "violet");
  fumeeCorps(sc, D, o.fumee ?? 26, o.graine ?? 300, { s: 1.3 });
  return D;
}

/**
 * La couronne d'obsidienne, qui flotte juste au-dessus de sa tête : un anneau de blocs noirs serti de deux fils violets,
 * huit piques facettées qui s'évasent (celle de devant plus haute, une pierre violette à son pied), des flammes violettes.
 */
function couronne(sc, T) {
  const k = T.k, H = 33.4, R = 4.9;
  const ici = (a, r, y) => T.tete.p([Math.cos(a) * r, y, Math.sin(a) * r]);
  for (let q = 0; q < 22; q++) {
    const a = (q / 22) * TOUR;
    cube(sc, ici(a, R, H), 1.6 * k, [0, -a], mix([10, 5, 16], [46, 24, 70], q % 2 ? 0.25 : 0.6));
  }
  for (const dy of [-0.95, 0.95]) trait(sc, Array.from({ length: 49 }, (_, q) => ici((q / 48) * TOUR, R + 0.85, H + dy)), 0.32 * k, "violetDoux");
  for (let q = 0; q < 8; q++) {
    const a = Math.PI / 2 + (q / 8) * TOUR, L = q === 0 ? 9.5 : q % 2 ? 4.6 : 7;
    const base = ici(a, R - 0.2, H + 0.7), dir = T.tete.n([Math.cos(a) * 0.32, 1, Math.sin(a) * 0.32]);
    pointe(sc, base, dir, L * k, (q === 0 ? 2.4 : 1.8) * k, (t) => mix([16, 8, 26], [92, 50, 140], t * t), false, false);
    trait(sc, [ici(a, R + 0.5, H + 1), ajoute(base, fois(dir, L * k * 0.97))], 0.28 * k, "violetDoux");
    cubeSimple(sc, ajoute(base, fois(dir, L * k)), 0.55 * k, lumi("violet"));
  }
  cubeSimple(sc, ici(Math.PI / 2, R + 0.9, H), 1.5 * k, lumi("violet"));
  for (let q = 0; q < 12; q++) {
    const a = (q / 12) * TOUR + 0.2;
    meche(sc, ici(a, R - 0.4, H + 1.2), [0, 1, 0], (2.5 + 2 * hache(q, 1, 77)) * k, q, "violetDoux", 0.3 * k);
  }
}

/** Les points d'un cercle de centre c, de rayon r, dans le plan (u, v). */
const cercle = (c, r, n = 40, u = [1, 0, 0], v = [0, 0, 1], a0 = 0, a1 = TOUR) =>
  Array.from({ length: n + 1 }, (_, q) => { const a = a0 + ((a1 - a0) * q) / n; return ajoute(c, ajoute(fois(u, Math.cos(a) * r), fois(v, Math.sin(a) * r))); });
/** Un cercle face à la caméra. */
function cercleFace(sc, c, r, n = 40) {
  const o = oeil(sc), e1 = norme(croix(o, [0, 1, 0])), e2 = croix(e1, o);
  return cercle(c, r, n, e1, e2);
}

/** Un orbe noir au contour violet qui brille. */
function orbe(sc, c, r) {
  boule(sc, c, r, () => brille(NOIR), { tranche: r / 2.5, aile: false });
  trait(sc, cercleFace(sc, c, r * 1.05, 24), 0.4, "violet");
}

/** La lame d'ombre lancée : un croissant noir (le creux vers l'arrière) au bord violet, une traînée de fumée. */
function lameOmbre(sc, c, dir, R, graine) {
  const d = norme(dir), u = norme(croix(d, [0, 1, 0])), v = norme(ajoute([0, 1, 0], fois(u, 0.6)));
  const P = (a, r) => ajoute(c, ajoute(fois(v, Math.sin(a) * r), fois(d, Math.cos(a) * r)));
  const n = 18, bord = [];
  for (let q = 0; q < n; q++) {
    const a0 = -1.35 + (2.7 * q) / n, a1 = -1.35 + (2.7 * (q + 1)) / n;
    const e0 = 1 - 0.55 * Math.cos((a0 / 1.35) * Math.PI / 2), e1 = 1 - 0.55 * Math.cos((a1 / 1.35) * Math.PI / 2);
    sc.quad([P(a0, R * e0), P(a1, R * e1), P(a1, R), P(a0, R)], OMBRE, 0, false);
    bord.push(P(a0, R));
  }
  bord.push(P(1.35, R));
  trait(sc, bord, 0.9, "violet");
  for (let q = 0; q < 26; q++) {
    const t = hache(q, 1, graine);
    cube(sc, ajoute(P((hache(q, 2, graine) - 0.5) * 2.4, R * 0.7), fois(d, -t * 30)), 2.6 * (1 - t) + 0.4, [q, t * 3], SUIES[q % 3]);
  }
}

// ------------------------------------------------------------------ les briques : lumière
/** L'auréole : un anneau d'or au-dessus de sa tête. */
function aureole(sc, T, r = 4.6, y = 35.5) {
  trait(sc, Array.from({ length: 33 }, (_, q) => T.tete.p([Math.cos((q / 32) * TOUR) * r, y, Math.sin((q / 32) * TOUR) * r])), 0.8 * T.k, "or");
}

/** Des étincelles d'or qui montent autour du corps. */
function etincelles(sc, T, n, graine, nom = "orDoux", loin = 3) {
  for (let q = 0; q < n; q++) {
    const p = ajoute(pointSur(T, MEMBRES[q % 6], q, graine, (0.8 + loin * hache(q, 3, graine)) * T.k), [0, hache(q, 4, graine) * 8 * T.k, 0]);
    cubeSimple(sc, p, (0.35 + 0.6 * hache(q, 5, graine)) * T.k, lumi(nom));
  }
}

/**
 * Une aile de lumière dans le dos (c = -1 à droite, +1 à gauche) : un os qui brille, des plumes d'or au bout blanc.
 * o.ouvert (0 → 1) : elle s'ouvre ; o.angle : la tourner (les six ailes du niveau 3) ; o.taille.
 */
function aile(sc, T, c, o = {}) {
  const kl = o.taille ?? 1, kw = kl * T.k, ou = o.ouvert ?? 1, ang = (o.angle ?? 0) * c, pl = o.plumes ?? 9;
  const S = [c * 2.2, 21, -2.4];
  const rot = (dx, dy) => [dx * Math.cos(ang) - dy * Math.sin(ang), dx * Math.sin(ang) + dy * Math.cos(ang)];
  const os = (t) => {
    const [dx, dy] = rot(c * 22 * ou * t * kl, 9 * kl * Math.sin(t * 2.4) * (0.4 + 0.6 * ou));
    return T.corps.p([S[0] + dx, S[1] + dy, S[2] - 4 * t * kl]);
  };
  for (let q = 0; q < pl; q++) {
    const t = 0.1 + (0.9 * q) / (pl - 1);
    const [dx, dy] = rot(c * (0.15 + 0.75 * t), -1);
    const L = (7 + 12 * t) * kw * (0.55 + 0.45 * ou);
    pointe(sc, os(t), T.corps.n([dx, dy, -0.25]), L, 2.6 * kw, (s) => mix(o.c0 ?? OR, BLANC, s), true, true);
    pointe(sc, ajoute(os(t), T.corps.n([0, 0, 0.5])), T.corps.n([dx * 0.7, dy, 0]), L * 0.5, 2.2 * kw, (s) => mix(OR_C, BLANC, s), true, true);
  }
  trait(sc, Array.from({ length: 13 }, (_, q) => os(q / 12)), 0.9 * kw, "or");
}

const PLAQUE = [236, 232, 220], ORA = [226, 168, 44], ORA_F = [168, 112, 22];
/** Une plaque d'armure : claire au milieu, bordée (bord : la largeur de la bordure, de 0 à 0,5). */
const plaque = (bord = 0.14, c0 = PLAQUE, c1 = ORA) => (f, u, v) => (u < bord || u > 1 - bord || v < bord || v > 1 - bord ? c1 : mix(c0, [255, 255, 255], 0.3 * (1 - v)));

/**
 * L'armure du niveau 3 de la Lumière, par-dessus la tenue : un plastron plus large que le corps avec un soleil d'or,
 * une ceinture d'or, des épaulières en trois gradins avec une petite aile d'or, des gantelets d'or, des tassettes qui
 * s'évasent sur les cuisses, des genouillères, des bottes d'or, un diadème avec une pierre qui brille.
 */
function armureLumiere(sc, T) {
  pave(sc, [-4.7, 13.2, -2.75], [4.7, 24.3, 2.75], T.corps, (f, u, v) => {
    if (u < 0.09 || u > 0.91 || v < 0.06) return ORA;
    if (f === "devant") {
      const du = (u - 0.5) * 9.4, dv = (v - 0.36) * 11.1, r = Math.hypot(du, dv), a = Math.atan2(dv, du);
      if (r < 1.4) return brille([255, 236, 150]);
      if (r < 3.4 && Math.abs(((a / TOUR) * 8 + 8) % 1 - 0.5) < 0.18) return ORA;
      if (Math.abs(v - 0.66) < 0.035 || Math.abs(v - 0.83) < 0.035) return ORA_F;
    }
    return mix(PLAQUE, [255, 255, 255], 0.3 * (1 - v));
  }, 0.5);
  pave(sc, [-4.85, 12, -2.85], [4.85, 13.4, 2.85], T.corps, () => ORA, 0.5);
  for (const [b, sg] of [["brasD", -1], ["brasG", 1]]) {
    const x0 = sg < 0 ? -8 : 4, x1 = sg < 0 ? -4 : 8, xo = sg < 0 ? x0 : x1;
    for (const [y0, y1, m] of [[21.4, 25, 1.7], [19.5, 22.2, 1.3], [17.7, 20.2, 0.95]]) {
      pave(sc, [x0 - (sg < 0 ? m : 0.4), y0, -2 - m], [x1 + (sg > 0 ? m : 0.4), y1, 2 + m], T[b], plaque(0.13), 0.5);
    }
    pointe(sc, T[b].p([xo + sg * 1.2, 24.6, 0]), T[b].n([sg * 0.9, 1, 0]), 5, 1.8, (t) => mix(ORA, [255, 236, 170], t), false, false);
    pave(sc, [x0 - 0.5, 12, -2.5], [x1 + 0.5, 16.6, 2.5], T[b], plaque(0.18, [244, 204, 110], ORA_F), 0.5);
  }
  for (const [b, x0, x1] of [["jambeD", -4, 0], ["jambeG", 0, 4]]) {
    pave(sc, [x0 - 0.7, 6.6, -2.85], [x1 + 0.7, 12.4, 2.85], T[b], plaque(0.13), 0.5);
    pave(sc, [x0 - 0.5, 0, -2.6], [x1 + 0.5, 5.2, 2.75], T[b], plaque(0.16, [244, 204, 110], ORA_F), 0.5);
    pave(sc, [x0 + 0.6, 4.8, 2.4], [x1 - 0.6, 7.4, 3.2], T[b], () => ORA, 0.5);
  }
  pave(sc, [-4.35, 29.2, -4.35], [4.35, 30.3, 4.35], T.tete, () => ORA, 0.5);
  cubeSimple(sc, T.tete.p([0, 30, 4.6]), 1.3 * T.k, lumi("or"));
}

/** Le soleil dans le dos (niveau 3) : un anneau d'or et des rayons, derrière lui. */
function soleilDos(sc, T, r = 9) {
  const P = (a, rr) => T.corps.p([Math.cos(a) * rr, 25 + Math.sin(a) * rr, -7]);
  trait(sc, Array.from({ length: 41 }, (_, q) => P((q / 40) * TOUR, r)), 0.9 * T.k, "or");
  for (let q = 0; q < 16; q++) {
    const a = (q / 16) * TOUR;
    trait(sc, [P(a, r + 1.2), P(a, r + (q % 2 ? 4 : 8))], (q % 2 ? 0.5 : 0.8) * T.k, q % 2 ? "orDoux" : "or");
  }
}

/** Une bande transparente de A à B, face à la caméra (un rayon, une colonne). */
function bande(sc, A, B, large, c, alpha) {
  const cote = fois(norme(croix(sous(B, A), oeil(sc))), large / 2);
  sc.voiles.push([[sous(A, cote), ajoute(A, cote), ajoute(B, cote), sous(B, cote)], c, alpha]);
}

/** Un rayon de lumière de A à B : un cœur qui brille, deux voiles autour. */
function rayon(sc, A, B, w = 1) {
  for (const [k, c, a] of [[6, OR, 0.07], [4.2, OR_C, 0.1], [2.8, BLANC, 0.16], [1.8, BLANC, 0.24]]) bande(sc, A, B, w * k, c, a);
  trait(sc, [A, B], w, "or");
}

/** Une lance de lumière qui file vers dir, la pointe en p. */
function lance(sc, p, dir, L = 26, k = 1) {
  const d = norme(dir), b = sous(p, fois(d, 5 * k));
  trait(sc, [sous(p, fois(d, L)), b], 1.1 * k, "or");
  pointe(sc, b, d, 5 * k, 2.6 * k, (t) => mix(OR_C, BLANC, t), true, true);
  trait(sc, [sous(p, fois(d, L + 18 * k)), sous(p, fois(d, L))], 2.2 * k, "orDoux");
}

/** Une épée de lumière plantée dans le sol en p (dessinée en traits qui brillent). */
function epeeLumiere(sc, p, k = 1) {
  const P = (x, y) => ajoute(p, [x * k, y * k, 0]);
  trait(sc, [P(0, -2), P(0, 18)], 1.8 * k, "or");
  trait(sc, [P(-4, 18), P(4, 18)], 1.1 * k, "or");
  trait(sc, [P(0, 18), P(0, 23)], 1 * k, "orDoux");
  cubeSimple(sc, P(0, 24), 1.4 * k, lumi("or"));
}

/** Une flamme sacrée, blanc et or. */
const flammeSacree = (sc, b, dir, L, l) => pointe(sc, b, dir, L, l, (t) => mix(OR, BLANC, t), true, true);

/** Un cercle d'or au sol (la zone du Jugement) : deux anneaux, des rayons, des marques. */
const cercleOr = (cx, cz, R) => (x, z) => {
  const dx = x - cx, dz = z - cz, r = Math.hypot(dx, dz), a = Math.atan2(dz, dx);
  if (Math.abs(r - R) < 1.3 || Math.abs(r - R * 0.82) < 0.9) return lumi("or");
  if (r < R * 0.82 && r > R * 0.2 && Math.abs(((a / TOUR) * 12 + 12) % 1 - 0.5) * r * 0.5 < 0.6) return lumi("orDoux");
  if (r > R * 0.82 && r < R && Math.floor(((a / TOUR) * 48 + 48) % 2) === 0 && Math.abs(r - R * 0.91) < 0.9) return lumi("orDoux");
  return null;
};

// ------------------------------------------------------------------ les poses
const GARDE = { brasD: [-0.5, -0.35], brasG: [-0.3, 0.4], jambeD: [0.2, -0.12], jambeG: [-0.2, 0.12], tete: [0.08] };
const OUVERTS = { brasD: [-0.15, -0.5], brasG: [-0.15, 0.5], jambeD: [0, -0.15], jambeG: [0, 0.15] };
const LEVES = { brasD: [0, -2.4], brasG: [0, 2.4], tete: [-0.4], jambeD: [0, -0.12], jambeG: [0, 0.12] };
const FRAPPE = { brasD: [-1.75, -0.25], brasG: [-0.3, 0.25], jambeD: -0.3, jambeG: 0.25 };
const VISE = { brasD: [-1.57, -0.06], brasG: [0.2, 0.1], jambeD: -0.3, jambeG: 0.25 };
const DEUX_MAINS = { brasD: [-1.57, 0.12], brasG: [-1.57, -0.12], jambeD: -0.35, jambeG: 0.3 };
const PROJETE = { penche: -0.7, brasD: [-2.6, -0.4], brasG: [-2.6, 0.4], jambeD: 0.3, jambeG: -0.2 };
const YEUX_CACHES = { brasD: [-2.5, 0.5], brasG: [-2.5, -0.5], tete: [0.3], penche: -0.2 };

// ================================================================== TÉNÈBRES : les niveaux
function niveau1T(sc, o = {}) {
  const T = bonhomme(sc, T1, { ...GARDE, ...o });
  yeux(sc, T, "violet");
  for (const bras of ["brasD", "brasG"]) {
    const main = T[bras].p([bras === "brasD" ? -6 : 6, 12, 0]);
    fumee(sc, sous(main, [0, 1, 0]), 12, bras === "brasD" ? 21 : 22, { r: 1.6, h: 9, s: 1.5 });
    meche(sc, main, [0, 1, 0.1], 9, bras === "brasD" ? 1 : 2);
  }
  return T;
}

function niveau2T(sc, o = {}) {
  const D = ombreVivante(sc, { decale: ajoute(o.decale ?? [0, 0, 0], [-24, 0, -8]), pose: o.poseOmbre ?? GARDE });
  const T = bonhomme(sc, T2, { ...GARDE, teinte: { c: OMBRE, k: 0.15 }, ...o });
  yeux(sc, T, "violet");
  fumeeCorps(sc, T, 22, 41);
  for (let q = 0; q < 6; q++) meche(sc, pointSur(T, MEMBRES[q], q, 42, 1), [hache(q, 1, 43) - 0.5, 1, hache(q, 2, 43) - 0.5], 10, q);
  aura(sc, T, AURA_T);
  return [T, D];
}

function niveau3T(sc, o = {}) {
  const D = o.ombre === false ? null : ombreVivante(sc, { k: 1.55, decale: ajoute(o.decale ?? [0, 0, 0], [-28, 0, -12]), pose: o.poseOmbre ?? GARDE, fumee: 34 });
  const T = bonhomme(sc, T3, { ...GARDE, lueur: true, ...o });
  yeux(sc, T, "violet");
  couronne(sc, T);
  fumeeCorps(sc, T, 30, 51, { s: 1.8 });
  for (let q = 0; q < 10; q++) meche(sc, pointSur(T, MEMBRES[q % 6], q + 9, 52, 1.2), [hache(q, 1, 53) - 0.5, 1, hache(q, 2, 53) - 0.5], 9 + 5 * hache(q, 3, 53), q, q % 3 ? "violetDoux" : "violet", 0.45);
  aura(sc, T, AURA_T);
  const c = ajoute(o.decale ?? [0, 0, 0], [0, 0, 0]);
  if (o.orbes !== false) {
    orbe(sc, ajoute(c, [11, 24, 4]), 2.2);
    orbe(sc, ajoute(c, [-10, 17, 6]), 1.8);
  }
  return [T, D];
}

function tEveil1(sc) {
  sol(sc, -40, -40, 40, 40, flaque(0, 0, 15, 1));
  const T = bonhomme(sc, jusqua(BASE_T, T1, 14), { ...OUVERTS, tete: [0.35] });
  yeux(sc, T, "violet");
  for (let q = 0; q < 7; q++) {
    const a = (q / 7) * TOUR + 0.3;
    vrille(sc, [Math.cos(a) * 12, 0.3, Math.sin(a) * 9], pointSur(T, q % 2 ? "jambeD" : "jambeG", q, 61, 0.4), q);
  }
  fumee(sc, [0, 0, 0], 40, 62, { r: 9, h: 12 });
  return "L'OMBRE MONTE DE SES PIEDS, LES VEINES NOIRCISSENT";
}

function tNiv1(sc) {
  sol(sc, -40, -40, 40, 40, flaque(0, 0, 8, 2));
  niveau1T(sc);
  return "VEINES NOIRES, YEUX VIOLETS, FUMEE AUX MAINS";
}

function tEveil2(sc) {
  sol(sc, -50, -60, 50, 40, flaque(-15, -12, 18, 3));
  const T = bonhomme(sc, T2, { ...OUVERTS, tete: [0.2], teinte: { c: OMBRE, k: 0.15 } });
  yeux(sc, T, "violet");
  fumeeCorps(sc, T, 16, 71);
  ombreVivante(sc, { decale: [-17, -12, -12], pose: { brasD: [-2.6, -0.3], brasG: [-2.4, 0.3], tete: [-0.3] }, fumee: 30 });
  fumee(sc, [-17, 0, -12], 40, 72, { r: 12, h: 14, s: 2.6 });
  return "SON OMBRE SORT DU SOL ET SE RELEVE";
}

function tNiv2(sc) {
  sol(sc, -50, -60, 50, 40, flaque(-5, -6, 12, 4));
  niveau2T(sc);
  return "UN DOUBLE NOIR PLUS GRAND, QUI LE SUIT";
}

function tEveil3(sc) {
  const T = bonhomme(sc, jusqua(T2, T3, 15), { ...LEVES, lueur: true, decale: [0, 2, 0] });
  yeux(sc, T, "violet");
  // le tourbillon : une spirale de fumée noire et des fils violets qui montent autour de lui
  for (let q = 0; q < 110; q++) {
    const t = q / 110, a = t * TOUR * 4.5, r = 17 - 5 * t + 2 * hache(q, 1, 81);
    if (Math.sin(a) > 0.35) continue; // devant lui, la spirale s'ouvre : on le voit dedans
    cube(sc, [Math.cos(a) * r, -2 + t * 46, Math.sin(a) * r * 0.8], 2.8 * (0.6 + hache(q, 2, 81)), [q, t * 3], SUIES[q % 3]);
  }
  for (let k = 0; k < 3; k++) trait(sc, Array.from({ length: 60 }, (_, q) => { const t = q / 59, a = t * TOUR * 3 + k * 2.1; return [Math.cos(a) * (19 - 6 * t), t * 44, Math.sin(a) * (19 - 6 * t) * 0.8]; }), 0.5, k ? "violetDoux" : "violet");
  sc.lueur(VIOLET, 6, 0.6);
  return "LA NUIT TOMBE, UN TOURBILLON NOIR L'AVALE";
}

function tNiv3(sc) {
  sol(sc, -50, -60, 50, 40, flaque(-4, -6, 16, 5));
  niveau3T(sc);
  return "TENUE NOIRE AUX VEINES VIOLETTES, COURONNE";
}

function tVisage(sc) {
  const T = bonhomme(sc, T3, { lueur: true });
  yeux(sc, T, "violet");
  couronne(sc, T);
  return "LES YEUX VIOLETS, LA COURONNE DE PIQUES";
}

function tTrois(sc) {
  sol(sc, -60, -60, 60, 30);
  niveau1T(sc, { decale: [-36, 0, 0] });
  niveau2T(sc, { decale: [0, 0, 0] });
  niveau3T(sc, { decale: [38, 0, 0], orbes: false });
  etiquette(sc, "NIV. 1", [-36, 62, 0]);
  etiquette(sc, "NIV. 2", [-6, 62, 0]);
  etiquette(sc, "NIV. 3", [30, 62, 0]);
  return "EVEIL 1 FOIS, 2 FOIS, 3 FOIS";
}

// ================================================================== TÉNÈBRES : les attaques
function obscurcie(sc, C, graine) {
  fumee(sc, C.tete.p([0, 26, 0]), 22, graine, { r: 4.5, h: 8, s: 2 });
}

function tGriffe(sc, fort = 0) {
  sol(sc, -30, -30, 30, 60);
  const D = fort ? ombreVivante(sc, { decale: [-11, 0, -12], pose: FRAPPE }) : null;
  const T = bonhomme(sc, fort ? T2 : T1, { ...FRAPPE, teinte: fort ? { c: OMBRE, k: 0.15 } : undefined });
  yeux(sc, T, "violet");
  epee(sc, T.brasD, "D", 15);
  croissant(sc, T.brasD, "D", [30, 10, 50]);
  if (D) {
    epee(sc, D.brasD, "D", 15 * D.k);
    croissant(sc, D.brasD, "D", [60, 20, 100]);
    aura(sc, T, AURA_T);
  }
  const C = cible(sc, [-2, 0, 34], { penche: fort ? -0.35 : -0.2 });
  obscurcie(sc, C, 101 + fort);
  meche(sc, C.corps.p([0, 18, 2]), [0, 1, -0.2], 10, 3, "violet", 0.6);
  return fort ? "+4 DEGATS, SON OMBRE FRAPPE AUSSI : +2" : "+2 DEGATS, LA CIBLE A L'OBSCURITE 2 S";
}

function tLame(sc) {
  sol(sc, -30, -20, 30, 130);
  const T = bonhomme(sc, T1, VISE);
  yeux(sc, T, "violet");
  lameOmbre(sc, [0, 20, 56], [0, 0, 1], 14, 111);
  const C = cible(sc, [0, 0, 112]);
  obscurcie(sc, C, 112);
  return "LAME D'OMBRE : 6 DEGATS, 20 BLOCS, RECHARGE 6 S";
}

function tPlonge(sc, loin = 100, explose = false) {
  sol(sc, -40, -30, 40, loin + 40, dessus(flaque(0, 0, 11, 6), flaque(0, loin, 13, 7), (x, z) => (Math.abs(x + 2 * Math.sin(z * 0.12)) < 1.2 && z > 8 && z < loin - 10 ? brille(OMBRE) : null)));
  const A = bonhomme(sc, T1, { decale: [0, -18, 0], brasD: [-2.8, -0.2], brasG: [-2.8, 0.2], tete: [0.4] });
  yeux(sc, A, "violet");
  fumee(sc, [0, 0, 0], 30, 121, { r: 8, h: 10 });
  const B = bonhomme(sc, explose ? T2 : T1, { decale: [0, -6, loin], brasD: [-2.4, -0.4], brasG: [-0.6, 0.5], tete: [-0.2] });
  yeux(sc, B, "violet");
  fumee(sc, [0, 0, loin], 40, 122, { r: 9, h: 16 });
  if (explose) {
    for (let q = 0; q < 70; q++) {
      const a = hache(q, 1, 123) * TOUR, r = 22 + 14 * hache(q, 2, 123);
      cube(sc, [Math.cos(a) * r, 2 + 10 * hache(q, 3, 123), loin + Math.sin(a) * r], 2 + 3 * hache(q, 4, 123), [q, a], SUIES[q % 3]);
    }
    trait(sc, cercle([0, 0.4, loin], 30, 60), 1, "violet");
    [0.6, 2.4].forEach((a, k) => {
      const p = [Math.cos(a) * 30, 8 + 6 * k, loin + Math.sin(a) * 30];
      cible(sc, p, { lacet: regarde(p, [0, 0, loin]), ...PROJETE });
    });
    boule(sc, [0, 8, loin], 20, () => [60, 20, 100], { voile: 0.22, tranche: 3 });
  }
  return explose ? "30 BLOCS, ET L'OMBRE EXPLOSE : 8 DEGATS" : "IL PLONGE DANS SON OMBRE : 15 BLOCS, RECHARGE 12 S";
}

function tMains(sc) {
  sol(sc, -40, -20, 40, 90, flaque(0, 56, 14, 8));
  const T = bonhomme(sc, T2, { ...VISE, teinte: { c: OMBRE, k: 0.15 } });
  yeux(sc, T, "violet");
  aura(sc, T, AURA_T);
  const C = cible(sc, [0, 3, 56], { brasD: [-0.2, -0.9], brasG: [-0.2, 0.9], tete: [-0.3], penche: 0 });
  // deux mains noires sortent de la flaque : cinq doigts chacune, qui se referment sur lui
  for (const [s, h] of [[-1, 14], [1, 26]]) {
    for (let f = 0; f < 5; f++) {
      const a = s * 0.4 + (f - 2) * 0.55 + (s > 0 ? Math.PI : 0);
      const pied = [Math.cos(a) * 11, 0, 56 + Math.sin(a) * 9];
      const mi = [Math.cos(a) * 9, h - 2, 56 + Math.sin(a) * 7];
      const bout = [Math.cos(a) * 3.5, h + 3 - Math.abs(f - 2), 56 + Math.sin(a) * 3];
      vrille(sc, pied, mi, f + s * 7, 3.6, 2.8, 10);
      vrille(sc, mi, bout, f + s * 9, 2.8, 1.4, 8);
      pointe(sc, bout, sous([0, bout[1], 56], bout), 2.4, 1.2, () => [200, 140, 255], false, true);
      cubeSimple(sc, bout, 0.9, lumi("violet"));
    }
  }
  fumee(sc, [0, 0, 56], 30, 131, { r: 12, h: 8 });
  return "MAINS D'OMBRE : TENU 2 S, 10 DEGATS";
}

function tVol(sc) {
  sol(sc, -30, -30, 30, 60);
  const D = ombreVivante(sc, { k: 1.55, decale: [-12, 0, -16], pose: FRAPPE, fumee: 20 });
  const T = bonhomme(sc, T3, { ...FRAPPE, lueur: true });
  yeux(sc, T, "violet");
  couronne(sc, T);
  epee(sc, T.brasD, "D", 15);
  croissant(sc, T.brasD, "D", [90, 30, 160]);
  aura(sc, T, AURA_T);
  epee(sc, D.brasD, "D", 15 * D.k);
  const C = cible(sc, [-2, 0, 34], { penche: -0.4, brasD: [-1.2, -0.9], brasG: [-1.2, 0.9] });
  obscurcie(sc, C, 141);
  // la vie qui revient : des fils et des gouttes rouges de la cible jusqu'à lui
  for (let q = 0; q < 4; q++) meche(sc, C.corps.p([0, 16 + q * 2, 0]), sous(T.corps.p([0, 18, 0]), C.corps.p([0, 16 + q * 2, 0])), 30, q, q % 2 ? "violet" : "rouge", 0.4);
  for (let q = 0; q < 14; q++) cubeSimple(sc, lerpV(C.corps.p([0, 18, 0]), T.corps.p([0, 18, 0]), q / 14), 0.9, brille([220, 30, 40]));
  return "+6 DEGATS, ET IL VOLE 1 COEUR";
}

function tPiques(sc) {
  sol(sc, -30, -20, 30, 140, (x, z) => (Math.abs(x) < 3 && z > 14 && z < 116 ? brille(OMBRE) : null));
  const T = bonhomme(sc, T3, { ...VISE, brasD: [-0.4, -0.2], penche: 0.3, buste: 0.4, lueur: true });
  yeux(sc, T, "violet");
  couronne(sc, T);
  aura(sc, T, AURA_T);
  for (let q = 0; q < 9; q++) {
    const z = 20 + q * 11, L = 8 + q * 3.2;
    for (const dx of [-3, 3]) pointe(sc, [dx * (0.6 + 0.5 * hache(q, dx, 151)), 0, z], [dx * 0.06, 1, (hache(q, 2, 151) - 0.5) * 0.3], L, 4 + q * 0.3, (t) => mix(NOIR, [90, 40, 150], t * t), false, true);
    cubeSimple(sc, [0, L * 0.9, z], 1, lumi("violet"));
  }
  const C = cible(sc, [0, 30, 120], { ...PROJETE, lacet: Math.PI });
  obscurcie(sc, C, 152);
  return "LANCES D'OMBRE EN LIGNE : 14, PROJETE EN L'AIR";
}

function tTroisOmbres(sc) {
  sol(sc, -60, -40, 60, 80, flaque(0, 34, 32, 9));
  const T = bonhomme(sc, T3, { ...GARDE, lueur: true, decale: [0, 0, -30] });
  yeux(sc, T, "violet");
  couronne(sc, T);
  const C = cible(sc, [0, 0, 34], { penche: -0.2, brasD: [-1.0, -0.9], brasG: [-1.0, 0.9] });
  obscurcie(sc, C, 161);
  [0.3, 2.3, 4.3].forEach((a, k) => {
    const p = [Math.cos(a) * 24, 0, 34 + Math.sin(a) * 18];
    const D = ombreVivante(sc, { k: 1.1, decale: p, lacet: regarde(p, [0, 0, 34]), pose: FRAPPE, fumee: 14, graine: 310 + k });
    epee(sc, D.brasD, "D", 15 * D.k);
    croissant(sc, D.brasD, "D", [70, 24, 120]);
  });
  return "3 OMBRES SURGISSENT AUTOUR : 3 X 5 DEGATS";
}

function tPrison(sc) {
  sol(sc, -50, -40, 40, 90, flaque(0, 56, 13, 11));
  niveau2T(sc, { ...VISE, brasD: [-1.9, -0.1] });
  const C = cible(sc, [0, 0, 56], { brasD: [-1.3, -0.6], brasG: [-1.3, 0.6], penche: 0, tete: [-0.2] });
  obscurcie(sc, C, 401);
  // la cage : douze barreaux noirs qui sortent de la flaque et se referment en dôme au-dessus de lui
  for (let q = 0; q < 12; q++) {
    const a = (q / 12) * TOUR, pts = [];
    for (let k = 0; k <= 24; k++) {
      const t = k / 24, h = t < 0.65 ? 0 : (t - 0.65) / 0.35;
      const r = t < 0.65 ? 9.5 : 9.5 * Math.cos((h * Math.PI) / 2) + 0.4, y = t < 0.65 ? (t / 0.65) * 24 : 24 + Math.sin((h * Math.PI) / 2) * 10;
      pts.push([Math.cos(a) * r, y, 56 + Math.sin(a) * r]);
    }
    for (const pt of pts) cube(sc, pt, 1.6, [0, a], SUIES[q % 2]);
    trait(sc, pts, 0.3, "violetDoux");
  }
  for (const y of [0.5, 12, 24]) trait(sc, cercle([0, y, 56], 9.5, 48), y < 1 ? 0.7 : 0.4, y < 1 ? "violet" : "violetDoux");
  cubeSimple(sc, [0, 34.6, 56], 1.6, lumi("violet"));
  return "PRISON D'OMBRE : ENFERME 3 S, 8 DEGATS";
}

function tFaux(sc) {
  sol(sc, -70, -60, 70, 70);
  const P = (a, r, y = 12) => [Math.cos(a) * r, y, Math.sin(a) * r];
  const D = ombreVivante(sc, { k: 2.1, decale: [-4, 0, -24], pose: { brasD: [-1.4, -0.9], brasG: [-1.2, 0.5], tete: [0.2] }, fumee: 30 });
  const T = bonhomme(sc, T3, { brasD: [-1.5, -1.2], brasG: [-0.4, 0.6], jambeD: -0.3, jambeG: 0.3, lueur: true });
  yeux(sc, T, "violet");
  couronne(sc, T);
  aura(sc, T, AURA_T);
  // le balayage : un grand croissant d'ombre autour de lui, le bord violet, la faux au bout
  for (let q = 0; q < 44; q++) {
    const a0 = -2.8 + q * 0.1, a1 = a0 + 0.1;
    sc.voiles.push([[P(a0, 16), P(a1, 16), P(a1, 50), P(a0, 50)], [50, 16, 90], 0.08 + (0.42 * q) / 44]);
  }
  trait(sc, Array.from({ length: 45 }, (_, q) => P(-2.8 + q * 0.1, 50)), 1.1, "violet");
  trait(sc, Array.from({ length: 45 }, (_, q) => P(-2.8 + q * 0.1, 33, 13)), 0.4, "violetDoux");
  const aF = 1.6, mains = D.brasD.p([-6, 11, 0]), manche = P(aF, 34, 14);
  for (let q = 0; q <= 20; q++) cube(sc, lerpV(mains, manche, q / 20), 1.8, [q, 0.3], SUIES[q % 2]);
  lameOmbre(sc, P(aF, 44, 13), [-Math.sin(aF), 0, Math.cos(aF)], 16, 402);
  [[0.2, 1], [-1.3, 2], [-2.5, 3]].forEach(([a, k]) => {
    const pt = P(a, 56, 8 + 4 * k);
    cible(sc, pt, { lacet: regarde(pt, [0, 0, 0]), ...PROJETE });
  });
  return "LA FAUX : SON OMBRE BALAIE TOUT AUTOUR, 16";
}

/** Une chaîne d'ombre de A à B : des maillons noirs croisés, aux reflets violets. */
function chaine(sc, A, B) {
  const d = sous(B, A), L = Math.hypot(...d), ax = norme(d), n = Math.max(2, Math.round(L / 2.5));
  const x = croix(ax, [0, 1, 0]), e1 = norme(Math.hypot(...x) < 0.2 ? croix(ax, [1, 0, 0]) : x), e2 = croix(ax, e1);
  for (let q = 0; q < n; q++) {
    const c = ajoute(A, fois(d, (q + 0.5) / n)), e = q % 2 ? e1 : e2;
    const pts = Array.from({ length: 13 }, (_, k) => { const t = (k / 12) * TOUR; return ajoute(c, ajoute(fois(ax, Math.cos(t) * 1.6), fois(e, Math.sin(t) * 0.9))); });
    for (const pt of pts.filter((_, i) => i % 2 === 0)) cubeSimple(sc, pt, 0.8, q % 2 ? [34, 16, 50] : [14, 7, 22]);
    trait(sc, pts, 0.22, "violetDoux");
  }
}

function tChaines(sc) {
  const C3 = [[-34, 0, 70], [6, 0, 92], [40, 0, 64]];
  sol(sc, -60, -30, 60, 110, dessus(...C3.map(([x, , z], k) => flaque(x, z, 7, 12 + k))));
  const T = bonhomme(sc, T3, { brasD: [-1.6, -0.1], brasG: [-1.6, 0.1], jambeD: 0.4, jambeG: -0.4, penche: -0.15, lueur: true });
  yeux(sc, T, "violet");
  couronne(sc, T);
  aura(sc, T, AURA_T);
  const main = T.brasD.p([-6, 11, 0]);
  C3.forEach((pt, k) => {
    const pp = [pt[0] * 0.85, 4, pt[2] * 0.85];
    const C = cible(sc, pp, { lacet: regarde(pp, [0, 0, 0]), penche: 0.45, brasD: [-0.1, -0.2], brasG: [-0.1, 0.2], jambeD: -0.5, jambeG: 0.4 });
    obscurcie(sc, C, 410 + k);
    const corps = C.corps.p([0, 18, 0]);
    chaine(sc, corps, main);
    chaine(sc, [pt[0] + 4, 0.5, pt[2] + 3], C.corps.p([2, 14, 0]));
    chaine(sc, [pt[0] - 4, 0.5, pt[2] + 2], C.corps.p([-2, 20, 0]));
    trait(sc, cercle(C.corps.p([0, 16, 0]), 3.4, 24), 0.4, "violet");
  });
  return "CHAINES D'OMBRE : 3 ENNEMIS TIRES A LUI, 12";
}

// ---- l'ulti : l'Éclipse
const ZONE = [0, 0, 130];
/** Le trou noir : une boule toute noire, un anneau violet qui brille, un disque de matière qui tourne autour. */
function trouNoir(sc, c, r, graine, force = 1) {
  const u = [1, 0, 0], v = norme([0, 0.32, 1]);
  const P = (a, rr) => ajoute(c, ajoute(fois(u, Math.cos(a) * rr), fois(v, Math.sin(a) * rr)));
  for (let q = 0; q < 72; q++) {
    const a0 = (q / 72) * TOUR, a1 = ((q + 1) / 72) * TOUR;
    for (const [r0, r1, col, al] of [[1.12, 1.6, [236, 200, 255], 0.6], [1.6, 2.3, [160, 80, 255], 0.42], [2.3, 3.1, [90, 40, 170], 0.22]]) {
      sc.voiles.push([[P(a0, r * r0), P(a1, r * r0), P(a1, r * r1), P(a0, r * r1)], col, al * force]);
    }
  }
  boule(sc, c, r, () => brille([3, 1, 6]), { tranche: r / 7, aile: false });
  trait(sc, cercleFace(sc, c, r * 1.04, 60), Math.max(1.1, r * 0.07), "violet");
  for (let k = 0; k < 5; k++) trait(sc, Array.from({ length: 40 }, (_, q) => P(k * 1.25 + q * 0.12, r * (3.2 - 1.9 * (q / 39)))), Math.max(0.6, r * 0.04), "violetDoux");
  for (let q = 0; q < 40; q++) cubeSimple(sc, P(hache(q, 1, graine) * TOUR, r * (1.3 + 1.8 * hache(q, 2, graine))), (0.6 + hache(q, 3, graine)) * Math.max(1, r / 12), lumi(q % 3 ? "violetDoux" : "violet"));
}

/** L'éclipse dans le ciel : un disque tout noir, une couronne de rayons violets qui flambe autour. */
function eclipse(sc, c, r) {
  const o = oeil(sc), e1 = norme(croix(o, [0, 1, 0])), e2 = croix(e1, o);
  const E = (a, rr) => ajoute(c, ajoute(fois(e1, Math.cos(a) * rr), fois(e2, Math.sin(a) * rr)));
  for (let q = 0; q < 48; q++) {
    const a = (q / 48) * TOUR + 0.05 * hache(q, 1, 90), L = r * (0.25 + 0.9 * hache(q, 2, 90) ** 2);
    trait(sc, [E(a, r * 1.02), E(a + 0.04, r * 1.04 + L)], q % 4 ? 0.7 : 1.3, q % 4 ? "violetDoux" : "violet");
  }
  for (const [k, al] of [[1.25, 0.3], [1.55, 0.16], [1.95, 0.08]]) {
    for (let q = 0; q < 48; q++) sc.voiles.push([[E((q / 48) * TOUR, r), E(((q + 1) / 48) * TOUR, r), E(((q + 1) / 48) * TOUR, r * k), E((q / 48) * TOUR, r * k)], [170, 90, 255], al]);
  }
  boule(sc, c, r, () => brille([2, 1, 4]), { tranche: r / 8, aile: false });
  trait(sc, cercleFace(sc, c, r * 1.02, 72), 1.6, "violet");
}

const NUIT_T = [16, 12, 26];
const ULTI = { lacet: 125, tangage: 12, echelle: 1.75, pied: 250, centre: [0, 62, 52], fond: NUIT_T };
const CIEL_T = [20, 150, 120];
/** Des étoiles violettes dans le ciel noir. */
function etoiles(sc, n, graine) {
  for (let q = 0; q < n; q++) cubeSimple(sc, [(hache(q, 1, graine) - 0.5) * 300, 90 + 120 * hache(q, 2, graine), 60 + (hache(q, 3, graine) - 0.2) * 300], 0.8 + hache(q, 4, graine), lumi("violetDoux"));
}

function tUlti(sc, etape) {
  const t = tUltiScene(sc, etape);
  sc.lueur([160, 80, 255], 10, 1.1);
  return t;
}

function tUltiScene(sc, etape) {
  sol(sc, -80, -80, 80, 210, etape === 4 ? flaque(ZONE[0], ZONE[2], 56, 10) : (x, z) => brille(mix([20, 26, 18], [34, 40, 30], hache(Math.floor(x / 3), Math.floor(z / 3), 7))), 0, [40, 52, 34]);
  const leve = etape === 1 ? { brasD: [-2.9, -0.2], brasG: [-0.3, 0.3], tete: [-0.5] } : { brasD: [-1.6, -0.1], brasG: [-0.3, 0.3], tete: [-0.3] };
  const T = bonhomme(sc, T3, { ...leve, lueur: true });
  yeux(sc, T, "violet");
  couronne(sc, T);
  aura(sc, T, AURA_T);
  etoiles(sc, 50, 180 + etape);
  eclipse(sc, CIEL_T, etape === 4 ? 30 : 24);
  if (etape === 1) {
    fumee(sc, [0, 0, 0], 70, 181, { r: 26, h: 60, s: 4 });
    for (let q = 0; q < 6; q++) meche(sc, T.brasD.p([-6, 11, 0]), sous(CIEL_T, T.brasD.p([-6, 11, 0])), 60 + 30 * hache(q, 1, 182), q, "violetDoux", 0.6);
    [[-26, 0, 118], [24, 0, 142]].forEach((pt) => cible(sc, pt, { lacet: regarde(pt, [0, 0, 0]), tete: [-0.7] }));
    return "1 : LE CIEL S'ECLIPSE, TOUT DEVIENT NOIR";
  }
  // son ombre, devenue un géant derrière lui
  const pose = etape === 2 ? { brasD: [-2.9, 0.35], brasG: [-2.9, -0.35], tete: [-0.4] } : etape === 3 ? { brasD: [-1.7, -0.2], brasG: [-0.5, 0.4], tete: [0.1] } : { brasD: [-1.9, -0.6], brasG: [-1.9, 0.6], tete: [0.2] };
  const D = ombreVivante(sc, { k: 4, decale: [-14, 0, -46], lacet: 0.35, pose, fumee: 70, graine: 330, aile: true });
  aura(sc, D, [[1.2, 0.18, [120, 50, 200]]]);
  fumee(sc, [-14, 0, -46], 60, 183, { r: 30, h: 30, s: 5 });
  if (etape === 2) {
    const c = ajoute(D.brasD.p([-6, 11, 0]), fois(sous(D.brasG.p([6, 11, 0]), D.brasD.p([-6, 11, 0])), 0.5));
    trouNoir(sc, ajoute(c, [0, 14, 0]), 9, 184);
    for (let q = 0; q < 8; q++) meche(sc, ajoute(c, [Math.cos(q) * 30, -10, Math.sin(q) * 30]), [-Math.cos(q), 0.8, -Math.sin(q)], 34, q, "violet", 0.8);
    [[-26, 0, 118], [24, 0, 142]].forEach((pt) => cible(sc, pt, { lacet: regarde(pt, [0, 0, 0]), tete: [-0.7], brasD: [-1, -0.4] }));
    return "2 : SON OMBRE DEVIENT UN GEANT, LE TROU NOIR NAIT";
  }
  const c = ajoute(ZONE, [0, 46, 0]);
  if (etape === 3) {
    trouNoir(sc, c, 18, 185);
    trait(sc, [D.brasD.p([-6, 11, 0]), c], 1, "violetDoux");
    [[-30, 22, 118, 0], [24, 36, 146, 1], [8, 14, 100, 2], [-6, 54, 156, 3]].forEach(([x, y, z]) => {
      const pt = [x, y, z];
      cible(sc, pt, { lacet: regarde(pt, c), penche: 0.7, brasD: [-2.4, -0.3], brasG: [-2.2, 0.4], jambeD: 0.4, jambeG: -0.3 });
      trait(sc, Array.from({ length: 16 }, (_, q) => lerpV(ajoute(pt, [Math.sin(q) * 2, 12 - q, 0]), ajoute(pt, [0, 12, 0]), 1 - q / 15)), 0.4, "violetDoux");
    });
    for (let q = 0; q < 46; q++) {
      const a = hache(q, 1, 186) * TOUR, d = 34 + 40 * hache(q, 2, 186), h = -40 + 50 * hache(q, 3, 186);
      const pt = ajoute(c, [Math.cos(a) * d, h, Math.sin(a) * d * 0.7]);
      cube(sc, pt, 3 + 3 * hache(q, 4, 186), [q, a], q % 3 ? [110, 80, 54] : q % 2 ? [96, 140, 60] : [120, 120, 126]);
      trait(sc, [pt, lerpV(pt, c, 0.25)], 0.35, "violetDoux");
    }
    return "3 : IL LE LANCE, TOUT EST ASPIRE (3 S)";
  }
  // l'implosion : une colonne noire qui monte au ciel, une boule violette, des ondes au sol, des éclairs violets
  bande(sc, ZONE, ajoute(ZONE, [0, 400, 0]), 40, [0, 0, 0], 0.7);
  bande(sc, ZONE, ajoute(ZONE, [0, 400, 0]), 70, [90, 30, 160], 0.25);
  boule(sc, c, 52, () => [180, 100, 255], { voile: 0.24, tranche: 5 });
  boule(sc, c, 30, () => [240, 220, 255], { voile: 0.28, tranche: 4 });
  cubeSimple(sc, c, 8, lumi("violet"));
  for (let q = 0; q < 22; q++) {
    const a = (q / 22) * TOUR;
    trait(sc, zigzag(c, ajoute(c, [Math.cos(a) * 70, (hache(q, 1, 187) - 0.4) * 70, Math.sin(a) * 70]), 187 + q, 0.15, 3), 1.1, "violet");
  }
  for (const [r, l] of [[56, 2.4], [76, 1.6], [96, 1]]) trait(sc, cercle(ajoute(ZONE, [0, 0.6, 0]), r, 90), l, "violet");
  [[-66, 30, 112], [56, 40, 156], [14, 58, 200], [-40, 70, 170]].forEach(([x, y, z]) => { const pt = [x, y, z]; cible(sc, pt, { lacet: regarde(pt, ZONE), ...PROJETE }); });
  for (let q = 0; q < 30; q++) {
    const a = hache(q, 1, 188) * TOUR, d = 60 + 30 * hache(q, 2, 188);
    cube(sc, ajoute(ZONE, [Math.cos(a) * d, 10 + 50 * hache(q, 3, 188), Math.sin(a) * d]), 3 + 3 * hache(q, 4, 188), [q, a], q % 2 ? [110, 80, 54] : [120, 120, 126]);
  }
  return "4 : L'IMPLOSION : 22 DEGATS, ONDE DE CHOC";
}

// ================================================================== LUMIÈRE : les niveaux
function niveau1L(sc, o = {}) {
  const T = bonhomme(sc, BASE_L, { ...GARDE, teinte: { c: OR_C, k: 0.08 }, ...o });
  yeux(sc, T, "or");
  aureole(sc, T);
  etincelles(sc, T, 18, o.graine ?? 201);
  return T;
}

function niveau2L(sc, o = {}) {
  const T = bonhomme(sc, BASE_L, { ...GARDE, teinte: { c: OR_C, k: 0.16 }, lueur: true, ...o });
  yeux(sc, T, "or");
  aile(sc, T, -1, { ouvert: o.ouvert ?? 1 });
  aile(sc, T, 1, { ouvert: o.ouvert ?? 1 });
  aureole(sc, T, 5.2);
  etincelles(sc, T, 26, o.graine ?? 211);
  aura(sc, T, AURA_L);
  return T;
}

function niveau3L(sc, o = {}) {
  const T = bonhomme(sc, L3, { ...GARDE, lueur: true, ...o });
  yeux(sc, T, "or");
  armureLumiere(sc, T);
  soleilDos(sc, T);
  for (const c of [-1, 1]) {
    aile(sc, T, c, { angle: -0.45, taille: 1.05 });
    aile(sc, T, c, { angle: 0.15, taille: 0.85, plumes: 8 });
    aile(sc, T, c, { angle: 0.75, taille: 0.6, plumes: 7 });
  }
  if (o.aureole !== false) aureole(sc, T, 5.6, 37);
  etincelles(sc, T, 34, o.graine ?? 221, "orDoux", 4);
  aura(sc, T, AURA_L);
  return T;
}

const BLEU_NUIT = [50, 58, 92];

function lEveil1(sc) {
  sol(sc, -40, -40, 40, 40, (x, z) => (Math.hypot(x, z) < 9 ? lumi("orDoux") : Math.hypot(x, z) < 13 ? brille(mix(OR_C, [120, 170, 70], 0.4)) : null));
  const T = bonhomme(sc, BASE_L, { ...OUVERTS, tete: [-0.45], teinte: { c: BLANC, k: 0.25 }, lueur: true });
  yeux(sc, T, "or");
  rayon(sc, [0, 0, 0], [0, 200, 0], 7);
  aureole(sc, T, 4.6, 37);
  etincelles(sc, T, 30, 231, "orDoux", 5);
  return "UN RAYON DU CIEL TOMBE SUR LUI";
}

function lNiv1(sc) {
  sol(sc, -40, -40, 40, 40);
  niveau1L(sc);
  return "AUREOLE D'OR, YEUX DORES, ETINCELLES";
}

function lEveil2(sc) {
  sol(sc, -60, -40, 60, 40);
  const T = niveau2L(sc, { ...OUVERTS, tete: [-0.3], ouvert: 0.45 });
  for (let q = 0; q < 16; q++) {
    const a = hache(q, 1, 241) * TOUR, p = T.corps.p([Math.cos(a) * (14 + 14 * hache(q, 2, 241)), 18 + 16 * hache(q, 3, 241), -4 + Math.sin(a) * 6]);
    flammeSacree(sc, p, [Math.cos(a), -0.6, 0.3], 3.5, 1.2);
  }
  sc.lueur(OR, 8, 1.2);
  return "DEUX AILES DE LUMIERE S'OUVRENT DANS SON DOS";
}

function lNiv2(sc) {
  sol(sc, -60, -40, 60, 40);
  niveau2L(sc);
  sc.lueur(OR, 8, 1.2);
  return "LES AILES, L'AURA DOREE";
}

function lEveil3(sc) {
  const T = bonhomme(sc, jusqua(BASE_L, L3, 15, true), { ...LEVES, lueur: true, decale: [0, 6, 0] });
  yeux(sc, T, "or");
  rayon(sc, [0, -10, 0], [0, 200, 0], 14);
  soleilDos(sc, T, 11);
  for (const c of [-1, 1]) aile(sc, T, c, { angle: -0.45, ouvert: 0.6 });
  trait(sc, Array.from({ length: 41 }, (_, q) => [Math.cos((q / 40) * TOUR) * 12, 15 + 6, Math.sin((q / 40) * TOUR) * 9]), 1, "or");
  etincelles(sc, T, 40, 251, "orDoux", 6);
  sc.lueur(OR, 8, 1.2);
  return "L'OR L'HABILLE DE LA TETE AUX PIEDS";
}

function lNiv3(sc) {
  sol(sc, -70, -40, 70, 40);
  niveau3L(sc, { decale: [0, 2, 0] });
  sc.lueur(OR, 8, 1.2);
  return "ARMURE BLANC ET OR, 6 AILES, UN SOLEIL";
}

function lVisage(sc) {
  const T = bonhomme(sc, L3, { lueur: true });
  yeux(sc, T, "or");
  armureLumiere(sc, T);
  aureole(sc, T, 5.6, 37);
  soleilDos(sc, T, 9);
  etincelles(sc, T, 16, 261, "orDoux", 2);
  return "YEUX D'OR, DIADEME, PLASTRON AU SOLEIL";
}

function lTrois(sc) {
  sol(sc, -80, -60, 80, 30);
  niveau1L(sc, { decale: [-38, 0, 0] });
  niveau2L(sc, { decale: [0, 0, 0] });
  niveau3L(sc, { decale: [38, 0, 0] });
  sc.lueur(OR, 6, 1);
  etiquette(sc, "NIV. 1", [-38, 46, 0]);
  etiquette(sc, "NIV. 2", [0, 46, 0]);
  etiquette(sc, "NIV. 3", [38, 46, 0]);
  return "EVEIL 1 FOIS, 2 FOIS, 3 FOIS";
}

// ================================================================== LUMIÈRE : les attaques
function lCoup(sc, fort = 0) {
  sol(sc, -30, -30, 30, 60);
  const T = fort === 2 ? bonhomme(sc, L3, { ...FRAPPE, lueur: true }) : bonhomme(sc, BASE_L, { ...FRAPPE, teinte: { c: OR_C, k: fort ? 0.16 : 0.08 }, lueur: !!fort });
  yeux(sc, T, "or");
  if (fort === 2) armureLumiere(sc, T);
  aureole(sc, T, fort ? 5.2 : 4.6, fort === 2 ? 37 : 35.5);
  if (fort) for (const c of [-1, 1]) aile(sc, T, c, { ouvert: 0.8, angle: fort === 2 ? -0.3 : 0 });
  epee(sc, T.brasD, "D", 15);
  croissant(sc, T.brasD, "D", OR_C);
  if (fort === 0) {
    const C = cible(sc, [-2, 0, 34], { penche: -0.2, teinte: { c: BLANC, k: 0.3 }, aile: true });
    etincelles(sc, C, 10, 271);
    sc.lueur([255, 255, 255], 4, 3);
    return "+2 DEGATS, SURBRILLANCE 5 S (VU A TRAVERS LES MURS)";
  }
  if (fort === 1) {
    const C = cible(sc, [-2, 6, 44], { penche: -0.6, brasD: [-2.2, -0.6], brasG: [-2.2, 0.6], jambeD: 0.4, jambeG: -0.3 });
    for (let q = 0; q < 10; q++) trait(sc, [C.corps.p([0, 18, 2]), ajoute(C.corps.p([0, 18, 2]), [(hache(q, 1, 272) - 0.5) * 22, (hache(q, 2, 272) - 0.5) * 22, -6 - 6 * hache(q, 3, 272)])], 0.5, "orDoux");
    cubeSimple(sc, C.corps.p([0, 18, 3]), 3, lumi("or"));
    sc.lueur(OR, 8, 1.2);
    return "+4 DEGATS, UN CHOC DE LUMIERE QUI REPOUSSE";
  }
  const C = cible(sc, [-2, 0, 34], { penche: -0.3, brasD: [-1.2, -0.9], brasG: [-1.2, 0.9] });
  for (let q = 0; q < 18; q++) {
    const nom = MEMBRES[q % 6], p = pointSur(C, nom, q, 273, 0.3);
    flammeSacree(sc, p, [(hache(q, 1, 274) - 0.5) * 0.4, 1, (hache(q, 2, 274) - 0.5) * 0.4], 3 + 4 * hache(q, 3, 274), 1.6);
  }
  sc.lueur(OR, 8, 1.2);
  return "+6 DEGATS, FEU SACRE BLANC ET OR 3 S";
}

function lFleche(sc) {
  sol(sc, -30, -20, 30, 130);
  const T = niveau1L(sc, { ...VISE, teinte: undefined });
  const A = T.brasD.p([-6, 11, 0]), C = cible(sc, [0, 0, 112], { penche: -0.3 });
  const B = C.corps.p([0, 19, 2]);
  rayon(sc, A, B, 1.2);
  cubeSimple(sc, B, 3, lumi("or"));
  for (let q = 0; q < 8; q++) { const a = (q / 8) * TOUR; trait(sc, [B, ajoute(B, [Math.cos(a) * 7, Math.sin(a) * 7, -1])], 0.5, "orDoux"); }
  return "FLECHE DE LUMIERE : 6, 25 BLOCS, RECHARGE 6 S";
}

function lFlash(sc) {
  sol(sc, -60, -60, 60, 60, (x, z) => (Math.hypot(x, z) < 34 ? brille(mix(OR_C, [130, 170, 80], Math.hypot(x, z) / 40)) : null));
  const T = niveau1L(sc, { brasD: [0, -2.4], brasG: [0, 2.4], tete: [-0.3], teinte: { c: BLANC, k: 0.4 } });
  boule(sc, [0, 16, 0], 34, () => BLANC, { voile: 0.3, tranche: 4 });
  cubeSimple(sc, T.corps.p([0, 20, 3]), 5, lumi("or"));
  for (let q = 0; q < 14; q++) { const a = (q / 14) * TOUR; trait(sc, [T.corps.p([0, 20, 0]), [Math.cos(a) * 46, 16 + Math.sin(a) * 30, Math.sin(a) * 20]], 0.7, "orDoux"); }
  [[0.5, 30], [2.5, 28], [4.2, 32]].forEach(([a, r]) => {
    const p = [Math.cos(a) * r, 0, Math.sin(a) * r];
    cible(sc, p, { lacet: regarde(p, [0, 0, 0]) + Math.PI, ...YEUX_CACHES });
  });
  return "ECLAT : AVEUGLE 2 S A 6 BLOCS, 3 DEGATS";
}

function lLances(sc) {
  sol(sc, -40, -20, 40, 120, (x, z) => (Math.abs(Math.hypot(x, z - 80) - 14) < 1.2 ? lumi("orDoux") : null));
  const T = niveau2L(sc, { ...VISE, brasD: [-2.6, -0.1] });
  const C = cible(sc, [0, 0, 80], { tete: [-0.5], brasD: [-0.8, -0.8], brasG: [-0.8, 0.8] });
  lance(sc, C.tete.p([0, 33, 0]), [0.15, -1, -0.1], 30);
  lance(sc, [9, 4, 76], [-0.1, -1, 0.15], 30);
  lance(sc, [-8, 16, 86], [0.2, -1, 0], 30);
  sc.lueur(OR, 8, 1.2);
  return "3 LANCES TOMBENT DU CIEL SUR LA CIBLE : 10";
}

function lEnvol(sc) {
  sol(sc, -50, -50, 50, 50, (x, z) => (Math.abs(Math.hypot(x, z) - 18) < 1.3 || Math.abs(Math.hypot(x, z) - 30) < 0.8 ? lumi("orDoux") : null));
  const T = niveau2L(sc, { decale: [0, 50, 0], brasD: [0.3, -0.4], brasG: [0.3, 0.4], jambeD: 0.3, jambeG: -0.1, ouvert: 1 });
  for (let q = 0; q < 14; q++) {
    const a = hache(q, 1, 281) * TOUR, r = 10 + 20 * hache(q, 2, 281);
    flammeSacree(sc, [Math.cos(a) * r, 4 + 30 * hache(q, 3, 281), Math.sin(a) * r], [0.3, -1, 0.2], 3, 1.1);
  }
  for (let k = 0; k < 3; k++) trait(sc, cercle([0, 2 + k * 10, 0], 24 - k * 4, 50), 0.5, "orDoux");
  sc.lueur(OR, 8, 1.2);
  return "UN BATTEMENT D'AILES : 12 BLOCS EN L'AIR, IL PLANE";
}

function lRayonSolaire(sc) {
  sol(sc, -40, -20, 40, 140, (x, z) => (Math.abs(x) < 6 && z > 10 && z < 120 ? brille(mix(OR_C, [120, 170, 70], 0.5)) : null));
  const T = niveau3L(sc, { ...DEUX_MAINS, graine: 291 });
  const A = T.corps.p([0, 22, 10]);
  rayon(sc, A, [0, 18, 140], 4.5);
  for (let k = 0; k < 2; k++) trait(sc, Array.from({ length: 50 }, (_, q) => { const z = 10 + q * 2.6, a = q * 0.5 + k * Math.PI; return [Math.cos(a) * 6, 18 + Math.sin(a) * 6, z]; }), 0.4, "orDoux");
  cible(sc, [4, 8, 112], { ...PROJETE, lacet: Math.PI });
  sc.lueur(OR, 8, 1.2);
  return "RAYON SOLAIRE MAINTENU 2 S : 16, REPOUSSE";
}

function lVitesse(sc) {
  sol(sc, -30, -20, 30, 170, (x, z) => (Math.abs(x) < 2 && z > 0 && z < 150 ? lumi("orDoux") : null));
  for (const [z, al] of [[10, 0.18], [40, 0.25], [70, 0.32]]) fantome(sc, [0, 0, z], 0, al, OR_C);
  for (let k = 0; k < 5; k++) trait(sc, [[(k - 2) * 2.5, 6 + k * 5, 0], [(k - 2) * 2.5, 6 + k * 5, 140]], k === 2 ? 1.6 : 0.7, k === 2 ? "or" : "orDoux");
  niveau3L(sc, { decale: [0, 0, 150], brasD: [0.8, -0.2], brasG: [0.8, 0.2], jambeD: -0.6, jambeG: 0.6, penche: 0.35, graine: 301 });
  [[-12, 60], [12, 100]].forEach(([x, z], k) => {
    const C = cible(sc, [x, 4, z], { lacet: x < 0 ? Math.PI / 2 : -Math.PI / 2, ...PROJETE });
    cubeSimple(sc, C.corps.p([0, 18, 0]), 2.4, lumi("or"));
  });
  sc.lueur(OR, 8, 1.2);
  return "IL FILE 40 BLOCS EN LUMIERE : 8 AU PASSAGE";
}

function lPlumes(sc) {
  sol(sc, -50, -30, 50, 120);
  const T = niveau2L(sc, { brasD: [-1.3, -0.6], brasG: [-1.3, 0.6], jambeD: -0.3, jambeG: 0.25, tete: [0.1] });
  const C2 = [[-14, 0, 96], [14, 0, 104]];
  C2.forEach((pt, k) => {
    const C = cible(sc, pt, { lacet: regarde(pt, [0, 0, 0]), penche: -0.35, brasD: [-1.1, -0.8], brasG: [-1.1, 0.8] });
    for (let q = 0; q < 5; q++) flammeSacree(sc, pointSur(C, MEMBRES[(q + k) % 6], q, 510 + k, -0.2), norme(sous(pt, [0, 10, 0])), 3, 1.2);
  });
  for (let q = 0; q < 34; q++) {
    const sg = q % 2 ? -1 : 1, t = hache(q, 1, 501);
    const A = T.corps.p([sg * (10 + 14 * t), 20 + 10 * hache(q, 2, 501), -3]);
    const vise = ajoute(C2[q % 2], [(hache(q, 3, 501) - 0.5) * 16, 14 + (hache(q, 4, 501) - 0.5) * 18, 0]);
    const B = lerpV(A, vise, 0.25 + 0.65 * hache(q, 5, 501)), d = norme(sous(vise, A));
    flammeSacree(sc, B, d, 4.5, 1.4);
    trait(sc, [sous(B, fois(d, 9)), B], 0.4, "orDoux");
  }
  sc.lueur(OR, 8, 1.2);
  return "PLUIE DE PLUMES : 8 DEGATS EN CONE, 15 BLOCS";
}

function lChakram(sc) {
  sol(sc, -40, -20, 40, 140);
  const T = niveau3L(sc, { brasD: [-1.7, 0.5], brasG: [-0.2, 0.3], jambeD: -0.3, jambeG: 0.25, aureole: false, graine: 521 });
  const c = [6, 20, 74], u = [1, 0, 0], v = norme([0, 0.3, 1]);
  trait(sc, cercle(c, 7.5, 48, u, v), 1.6, "or");
  trait(sc, cercle(c, 5.6, 40, u, v), 0.6, "orDoux");
  for (let q = 0; q < 12; q++) {
    const a = (q / 12) * TOUR, P = ajoute(c, ajoute(fois(u, Math.cos(a) * 7.6), fois(v, Math.sin(a) * 7.6)));
    flammeSacree(sc, P, ajoute(fois(u, -Math.sin(a)), fois(v, Math.cos(a))), 2.6, 1);
  }
  const main = T.brasD.p([-6, 11, 0]);
  for (let k = 0; k < 3; k++) trait(sc, Array.from({ length: 21 }, (_, q) => { const t = q / 20; return ajoute(lerpV(main, c, t), [Math.sin(t * Math.PI) * (10 + 4 * k), (k - 1) * 2, 0]); }), 0.5, "orDoux");
  for (let q = 0; q < 18; q++) { const t = q / 17; cubeSimple(sc, ajoute(lerpV([0, 22, 112], main, t), [-Math.sin(t * Math.PI) * 22, 0, 0]), 0.9, lumi("orDoux")); }
  const C = cible(sc, [0, 0, 112], { penche: -0.4, brasD: [-1.2, -0.9], brasG: [-1.2, 0.9] });
  for (let q = 0; q < 3; q++) trait(sc, [C.corps.p([-5, 22 - q * 4, 3]), C.corps.p([5, 18 - q * 4, 3])], 0.6, "or");
  sc.lueur(OR, 8, 1.2);
  return "CHAKRAM : SON AUREOLE LANCEE, 12, ELLE REVIENT";
}

function lCroix(sc) {
  sol(sc, -50, -20, 50, 140, (x, z) => ((Math.abs(x) < 2.4 && Math.abs(z - 90) < 30) || (Math.abs(z - 90) < 2.4 && Math.abs(x) < 30) ? lumi("or") : Math.hypot(x, z - 90) < 34 ? brille(mix(OR_C, [110, 160, 70], 0.55)) : null));
  niveau3L(sc, { ...VISE, brasD: [-2.5, -0.2], graine: 531 });
  rayon(sc, [0, 0, 90], [0, 110, 90], 5.5);
  rayon(sc, [-34, 62, 90], [34, 62, 90], 4.5);
  cubeSimple(sc, [0, 62, 90], 5, lumi("or"));
  const C = cible(sc, [0, 30, 90], { ...PROJETE, lacet: Math.PI });
  for (let q = 0; q < 10; q++) flammeSacree(sc, pointSur(C, MEMBRES[q % 6], q, 532, 0.2), [0, 1, 0], 3 + 3 * hache(q, 1, 533), 1.4);
  sc.lueur(OR, 8, 1.2);
  return "CROIX DE LUMIERE : 15, L'ENVOIE EN L'AIR";
}

// ---- l'ulti : le Jugement
const ULTI_L = { lacet: 125, tangage: 12, echelle: 1.75, pied: 250, centre: [0, 62, 52], fond: [34, 40, 70] };
const TROU_CIEL = [20, 160, 120];

/** Les nuages qui s'ouvrent : un anneau de gros blocs blancs et gris autour d'un trou de lumière. */
function cielOuvert(sc, c, R) {
  const o = oeil(sc), e1 = norme(croix(o, [0, 1, 0])), e2 = croix(e1, o);
  for (let q = 0; q < 160; q++) {
    const a = hache(q, 1, 601) * TOUR, r = R * (1 + 1.3 * hache(q, 2, 601) ** 1.5), bord = 1 - Math.min(1, (r - R) / (R * 1.3));
    const pt = ajoute(c, ajoute(fois(e1, Math.cos(a) * r), fois(e2, Math.sin(a) * r * 0.55)));
    cube(sc, ajoute(pt, fois(o, -6 * hache(q, 3, 601))), 9 + 12 * hache(q, 4, 601), [0, a], mix([96, 100, 128], [255, 246, 214], 0.15 + 0.85 * bord ** 1.5));
  }
  for (let q = 0; q < 24; q++) {
    const a = (q / 24) * TOUR;
    trait(sc, [c, ajoute(c, ajoute(fois(e1, Math.cos(a) * R * 1.05), fois(e2, Math.sin(a) * R * 0.6)))], q % 2 ? 1 : 2, q % 2 ? "orDoux" : "or");
  }
  cubeSimple(sc, c, R * 0.5, lumi("or"));
}

/** L'ange géant de lumière derrière lui : transparent, doré, ses ailes, son auréole, son épée. */
function ange(sc, pose, epeeVers = null) {
  const A = bonhomme(sc, L3, { k: 4, decale: [-8, 0, -34], lacet: 0.35, voile: 0.55, teinte: { c: OR_C, k: 0.5 }, ...pose });
  aura(sc, A, [[1.2, 0.2, [255, 220, 120]]]);
  for (const c of [-1, 1]) { aile(sc, A, c, { angle: -0.35, taille: 0.6 }); aile(sc, A, c, { angle: 0.35, taille: 0.45, plumes: 7 }); }
  aureole(sc, A, 5.6, 37);
  yeux(sc, A, "or");
  const main = A.brasD.p([-6, 11, 0]);
  const bout = epeeVers ?? ajoute(main, [0, 130, 0]);
  const d = norme(sous(bout, main)), cote = norme(croix(d, [0, 0, 1]));
  for (const [w, nom] of [[16, "orDoux"], [8, "or"]]) trait(sc, [ajoute(main, fois(d, 14)), bout], w, nom);
  trait(sc, [ajoute(ajoute(main, fois(d, 14)), fois(cote, -16)), ajoute(ajoute(main, fois(d, 14)), fois(cote, 16))], 3, "or");
  return A;
}

function lUlti(sc, etape) {
  sol(sc, -80, -80, 80, 210, etape >= 3 ? cercleOr(ZONE[0], ZONE[2], 50) : null);
  const sol0 = ajoute(ZONE, [0, 0.5, 0]);
  if (etape === 1) {
    cielOuvert(sc, TROU_CIEL, 22);
    for (const [x, w] of [[-20, 6], [0, 12], [22, 7]]) rayon(sc, ajoute(TROU_CIEL, [x, 0, 0]), [x * 2, 0, 60 + x], w);
    niveau3L(sc, { decale: [0, 40, 0], brasD: [-2.9, -0.15], brasG: [-2.9, 0.15], tete: [-0.4], jambeD: 0.2, jambeG: -0.1, graine: 611 });
    [[-26, 0, 118], [22, 0, 142]].forEach((pt) => cible(sc, pt, { lacet: regarde(pt, [0, 0, 0]), tete: [-0.7] }));
    sc.lueur(OR, 9, 1.3);
    return "1 : LE CIEL S'OUVRE, IL S'ELEVE";
  }
  cielOuvert(sc, TROU_CIEL, 22);
  niveau3L(sc, { decale: [0, 40, 0], brasD: [-2.6, -0.15], brasG: [-0.4, 0.3], tete: [-0.2], jambeD: 0.2, jambeG: -0.1, graine: 612 });
  if (etape === 2) {
    ange(sc, { brasD: [-3, -0.15], brasG: [-0.5, 0.4], tete: [-0.2] });
    [[-26, 0, 118], [22, 0, 142]].forEach((pt) => cible(sc, pt, { lacet: regarde(pt, [0, 0, 0]), tete: [-0.7], brasD: [-1, -0.4] }));
    sc.lueur(OR, 9, 1.3);
    return "2 : UN ANGE GEANT DE LUMIERE APPARAIT";
  }
  if (etape === 3) {
    ange(sc, { brasD: [-3, -0.15], brasG: [-1.4, 0.4], tete: [0.1] });
    for (let q = 0; q < 26; q++) {
      const a = hache(q, 1, 613) * TOUR, r = 50 * Math.sqrt(hache(q, 2, 613));
      lance(sc, ajoute(sol0, [Math.cos(a) * r, 4 + 50 * hache(q, 3, 613), Math.sin(a) * r]), [0.15, -1, 0.1], 26, 1.3);
    }
    for (let q = 0; q < 12; q++) { const a = (q / 12) * TOUR; epeeLumiere(sc, ajoute(sol0, [Math.cos(a) * 50, 0, Math.sin(a) * 50]), 1.3); }
    [[-18, 0, 124], [16, 0, 140], [4, 0, 112]].forEach((pt) => cible(sc, pt, { lacet: regarde(pt, [0, 0, 0]), tete: [-0.6], brasD: [-2.6, -0.5], brasG: [-2.6, 0.5] }));
    sc.lueur(OR, 9, 1.3);
    return "3 : UNE PLUIE D'EPEES SUR LE CERCLE D'OR";
  }
  ange(sc, { brasD: [-1.2, -0.1], brasG: [-1.2, 0.3], tete: [0.3], penche: 0.25 }, ajoute(sol0, [0, 6, 0]));
  rayon(sc, sol0, ajoute(sol0, [0, 400, 0]), 26);
  for (const [r, l] of [[60, 2.6], [80, 1.8], [100, 1.1]]) trait(sc, cercle(ajoute(sol0, [0, 0.4, 0]), r, 90), l, "or");
  for (let q = 0; q < 16; q++) { const a = (q / 16) * TOUR; trait(sc, [sol0, ajoute(sol0, [Math.cos(a) * 100, 1, Math.sin(a) * 100])], 1.2, "orDoux"); }
  [[-66, 24, 112], [56, 34, 156], [14, 50, 200]].forEach(([x, y, z]) => { const pt = [x, y, z]; cible(sc, pt, { lacet: regarde(pt, ZONE), ...PROJETE }); });
  sc.lueur(OR, 11, 1.5);
  return "4 : LE JUGEMENT : 22 DEGATS, ONDE DE LUMIERE";
}

// ------------------------------------------------------------------ les planches
/** Un texte posé sur un point de la scène (centré dessus). */
function etiquette(sc, t, p, c = [236, 230, 255, 255], taille = 3) {
  const [sx, sy] = sc.vue(p);
  const l = t.length * 4 * taille;
  sc.apres = ((avant) => () => { avant?.(); ecrireTexte(sc.img, sc.w, t, Math.max(4, Math.min(sc.w - l - 4, Math.round(sx - l / 2))), Math.max(4, Math.round(sy)), taille, c); })(sc.apres);
}

const TEXTE = [30, 30, 40, 255];

function planche(fichier, titre, couleur, fond, lignes, vues) {
  const COLONNES = 4, rangs = Math.ceil(vues.length / COLONNES);
  const HAUT = 110 + lignes.length * 34 + 20;
  const W = CW * COLONNES, H = HAUT + (M + CH) * rangs;
  const img = Buffer.alloc(W * H * 4, 255);
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, titre, 30, 22, 5, TEXTE);
  lignes.forEach((t, k) => ecrireTexte(img, W, t, 30, 100 + k * 34, 3, t.startsWith(" ") ? TEXTE : couleur));
  vues.forEach(([nom, dessin, o], k) => {
    const c = k % COLONNES, l = Math.floor(k / COLONNES);
    const sc = scene({ w: CW, h: CH, fond, ...o });
    sc.voiles = [];
    const texte = dessin(sc);
    finir(sc);
    sc.apres?.();
    if (texte) legende(sc, texte, couleur);
    for (let y = 0; y < CH; y++) sc.img.copy(img, ((HAUT + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(img, W, nom, c * CW + 12, HAUT + l * (M + CH) + 10, 3, couleur);
  });
  ecrirePng(path.join(DOC, fichier), W, H, img);
  console.log("ok", fichier, W, H);
}

const PLAN = { lacet: -24, echelle: 10, pied: 480 };
const PLAN_OMBRE = { lacet: -30, tangage: 8, echelle: 6, pied: 460, centre: [-8, 6, -6] };
const PLAN_AILES = { lacet: -16, tangage: 8, echelle: 6, pied: 440, centre: [0, 8, 0] };
const PRES = { lacet: -18, tangage: 4, echelle: 26, pied: 330, centre: [0, 28, 0] };
const TROIS = { lacet: -14, tangage: 8, echelle: 3.9, pied: 420, centre: [0, 10, 0] };
const ACTION = { echelle: 5.6, tangage: 18, pied: 300 };
const LOIN = { lacet: -42, tangage: 20, echelle: 2.9, pied: 330 };

const VIOLET_T = [96, 40, 160, 255], FOND_T = [150, 144, 172];
const OR_T = [176, 120, 10, 255];

const PLANCHES = {
  "tenebres-niveaux": () => planche("planche-tenebres-niveaux.png", "TENEBRES : LES 3 NIVEAUX", VIOLET_T, FOND_T, [
    "LISTES TENEBRES, TENEBRES2, TENEBRES3. TOUCHE EVEIL 1, 2 OU 3 FOIS SELON SON NIVEAU. CHACUN GARDE SA TETE (SON SKIN)",
    "NIVEAU 1 : UNE FLAQUE NOIRE S'OUVRE SOUS LUI ET MONTE SUR SES JAMBES. VEINES NOIRES, YEUX VIOLETS, FUMEE NOIRE AUX MAINS",
    "    TOUJOURS : +20% VITESSE, PROTEGE COMME DU FER, IL VOIT DANS LE NOIR. EVEIL 2 MIN, PUIS 2 MIN DE REPOS",
    "NIVEAU 2 : SON OMBRE SORT DU SOL ET SE RELEVE : UN DOUBLE NOIR AUX YEUX VIOLETS, PLUS GRAND QUE LUI, QUI LE SUIT ET COPIE SES COUPS",
    "    AURA NOIRE ET VIOLETTE, VEINES PARTOUT. +40% VITESSE, PROTEGE COMME DEUX FOIS DU FER",
    "NIVEAU 3 : LA NUIT TOMBE AUTOUR DE LUI, UN TOURBILLON NOIR L'AVALE ET LE RECRACHE EN TENUE NOIRE AUX VEINES VIOLETTES",
    "    UNE COURONNE D'OBSIDIENNE QUI FLOTTE (PIQUES FACETTEES, FLAMMES ET PIERRE VIOLETTES), DEUX ORBES NOIRS, SON OMBRE PLUS GRANDE",
    "SONS : UN SOUFFLE GRAVE ET DES MURMURES A L'EVEIL. LES AUTRES VOIENT TOUT PAREIL",
  ], [
    ["EVEIL 1", tEveil1, { lacet: -24, tangage: 14, echelle: 7.5, pied: 450, centre: [0, 6, 0] }],
    ["NIVEAU 1", tNiv1, PLAN],
    ["EVEIL 2", tEveil2, PLAN_OMBRE],
    ["NIVEAU 2", tNiv2, PLAN_OMBRE],
    ["EVEIL 3", tEveil3, { lacet: -20, tangage: 8, echelle: 7, pied: 460, centre: [0, 8, 0], fond: NUIT_T }],
    ["NIVEAU 3", tNiv3, { ...PLAN_OMBRE, echelle: 5.2, pied: 440, centre: [-8, 12, -6] }],
    ["NIVEAU 3 DE PRES", tVisage, { ...PRES, echelle: 14, pied: 330, centre: [0, 26, 0] }],
    ["LES 3 NIVEAUX", tTrois, { ...TROIS, echelle: 3.6, pied: 450, centre: [-6, 14, 0] }],
  ]),
  "tenebres-attaques": () => planche("planche-tenebres-attaques.png", "TENEBRES : LES ATTAQUES", VIOLET_T, FOND_T, [
    "NIVEAU 1 - CLIC GAUCHE : TOUTE ARME +2, LA CIBLE A L'OBSCURITE 2 S (L'ECRAN QUI S'ASSOMBRIT). CLIC DROIT : LAME D'OMBRE, 6, RECHARGE 6 S",
    "    ACCROUPI : IL PLONGE DANS SON OMBRE ET RESSORT 15 BLOCS PLUS LOIN, OU IL REGARDE. RECHARGE 12 S",
    "NIVEAU 2 - CLIC GAUCHE : +4, SON OMBRE FRAPPE AVEC LUI (+2), OBSCURITE 3 S. CLIC DROIT : MAINS D'OMBRE, LA CIBLE TENUE 2 S, 10. RECHARGE 15 S",
    "    ACCROUPI : LA PLONGEE VA A 30 BLOCS ET L'OMBRE EXPLOSE A LA SORTIE : 8 DEGATS SUR 4 BLOCS. RECHARGE 12 S",
    "    EN PLUS - ACCROUPI + CLIC DROIT : PRISON D'OMBRE, UNE CAGE NOIRE SORT DU SOL AUTOUR DE LA CIBLE : 3 S, 8. RECHARGE 20 S",
    "NIVEAU 3 - CLIC GAUCHE : +6 ET VOLE 1 COEUR. CLIC DROIT : LANCES D'OMBRE, UNE LIGNE DE PIQUES (20 BLOCS) : 14, PROJETTE. RECHARGE 12 S",
    "    ACCROUPI : 3 OMBRES SURGISSENT AUTOUR DE LA CIBLE ET FRAPPENT : 3 X 5. RECHARGE 15 S",
    "    EN PLUS - TOUCHE TECHNIQUE : LA FAUX, SON OMBRE GEANTE BALAIE TOUT AUTOUR DE LUI (8 BLOCS) : 16, PROJETTE. RECHARGE 20 S",
    "    EN PLUS - ACCROUPI + CLIC GAUCHE : CHAINES D'OMBRE, JUSQU'A 3 ENNEMIS (20 BLOCS) ENCHAINES ET TIRES JUSQU'A LUI : 12. RECHARGE 20 S",
    "    LA PRISON D'OMBRE (ACCROUPI + CLIC DROIT) RESTE AU NIVEAU 3.",
    "ULTI (TOUCHE ULTI, NIVEAU 3) : L'ECLIPSE. LE SOLEIL S'ECLIPSE, LE CIEL DEVIENT NOIR ET VIOLET. SON OMBRE DEVIENT UN GEANT (8 BLOCS)",
    "    QUI FAIT NAITRE UN TROU NOIR ENTRE SES MAINS ET LE LANCE (40 BLOCS) : IL ASPIRE TOUT A 15 BLOCS PENDANT 3 S (JOUEURS, MOBS, BLOCS)",
    "    PUIS IMPLOSE : COLONNE NOIRE JUSQU'AU CIEL, ONDE DE CHOC, 22 AU CENTRE. LES BLOCS REVIENNENT A LEUR PLACE. 1 FOIS PAR EVEIL",
  ], [
    ["1 - CLIC GAUCHE", (sc) => tGriffe(sc, 0), { ...ACTION, echelle: 6.6, lacet: 64, centre: [0, 13, 18] }],
    ["1 - CLIC DROIT", tLame, { ...LOIN, centre: [0, 12, 56] }],
    ["1 - ACCROUPI", (sc) => tPlonge(sc), { ...LOIN, lacet: -60, centre: [0, 6, 50] }],
    ["2 - CLIC GAUCHE", (sc) => tGriffe(sc, 1), { ...ACTION, echelle: 5.2, lacet: 64, centre: [-2, 16, 12] }],
    ["2 - CLIC DROIT", tMains, { ...ACTION, echelle: 3.8, lacet: -40, tangage: 16, centre: [0, 14, 34] }],
    ["2 - ACCROUPI", (sc) => tPlonge(sc, 160, true), { ...LOIN, echelle: 2.1, lacet: -60, centre: [0, 8, 90] }],
    ["2 - ACCROUPI + CLIC DROIT", tPrison, { ...ACTION, echelle: 3.6, lacet: -35, tangage: 16, centre: [-4, 16, 30] }],
    ["3 - CLIC GAUCHE", tVol, { ...ACTION, echelle: 5.2, lacet: 64, centre: [-2, 16, 12] }],
    ["3 - CLIC DROIT", tPiques, { ...LOIN, echelle: 2.6, lacet: -55, centre: [0, 14, 60] }],
    ["3 - ACCROUPI", tTroisOmbres, { ...ACTION, echelle: 4, lacet: -20, tangage: 24, pied: 330, centre: [0, 14, 22] }],
    ["3 - TOUCHE TECHNIQUE", tFaux, { ...ACTION, echelle: 2.9, lacet: -20, tangage: 28, pied: 300, centre: [0, 16, 0] }],
    ["3 - ACCROUPI + CLIC GAUCHE", tChaines, { ...ACTION, echelle: 3, lacet: -150, tangage: 24, pied: 300, centre: [0, 12, 40] }],
    ["ULTI 1", (sc) => tUlti(sc, 1), ULTI],
    ["ULTI 2", (sc) => tUlti(sc, 2), ULTI],
    ["ULTI 3", (sc) => tUlti(sc, 3), ULTI],
    ["ULTI 4", (sc) => tUlti(sc, 4), ULTI],
  ]),
  "lumiere-niveaux": () => planche("planche-lumiere-niveaux.png", "LUMIERE : LES 3 NIVEAUX", OR_T, BLEU_NUIT, [
    "LISTES LUMIERE, LUMIERE2, LUMIERE3. TOUCHE EVEIL 1, 2 OU 3 FOIS SELON SON NIVEAU. CHACUN GARDE SA TETE (SON SKIN)",
    "NIVEAU 1 : UN RAYON DE LUMIERE TOMBE DU CIEL SUR LUI. UNE AUREOLE D'OR, YEUX DORES, DES ETINCELLES QUI MONTENT",
    "    TOUJOURS : +20% VITESSE, PROTEGE COMME DU FER, IL SE SOIGNE DOUCEMENT (REGENERATION). EVEIL 2 MIN, PUIS 2 MIN DE REPOS",
    "NIVEAU 2 : DEUX AILES DE LUMIERE S'OUVRENT DANS SON DOS (PLUMES D'OR AU BOUT BLANC), UNE AURA DOREE",
    "    +40% VITESSE, PROTEGE COMME DEUX FOIS DU FER, PAS DE DEGATS DE CHUTE",
    "NIVEAU 3 : IL S'ELEVE DANS UNE COLONNE DE LUMIERE, L'OR L'HABILLE DE LA TETE AUX PIEDS : UNE ARMURE BLANC ET OR",
    "    PLASTRON AU SOLEIL D'OR, EPAULIERES EN GRADINS, GANTELETS, TASSETTES, BOTTES D'OR, DIADEME. SIX AILES, UN SOLEIL DANS LE DOS",
    "SONS : UN CHOEUR ET UN CARILLON A L'EVEIL. LES AUTRES VOIENT TOUT PAREIL",
  ], [
    ["EVEIL 1", lEveil1, { lacet: -24, tangage: 10, echelle: 8.5, pied: 470, centre: [0, 4, 0] }],
    ["NIVEAU 1", lNiv1, PLAN],
    ["EVEIL 2", lEveil2, PLAN_AILES],
    ["NIVEAU 2", lNiv2, PLAN_AILES],
    ["EVEIL 3", lEveil3, { ...PLAN_AILES, echelle: 5.6, centre: [0, 14, 0] }],
    ["NIVEAU 3", lNiv3, { ...PLAN_AILES, echelle: 5.6, pied: 450, centre: [0, 16, 0] }],
    ["NIVEAU 3 DE PRES", lVisage, { ...PRES, echelle: 18, centre: [0, 26, 0], pied: 300 }],
    ["LES 3 NIVEAUX", lTrois, { ...TROIS, echelle: 3.3 }],
  ]),
  "lumiere-attaques": () => planche("planche-lumiere-attaques.png", "LUMIERE : LES ATTAQUES", OR_T, BLEU_NUIT, [
    "NIVEAU 1 - CLIC GAUCHE : TOUTE ARME +2, LA CIBLE EN SURBRILLANCE 5 S (ON LA VOIT A TRAVERS LES MURS). CLIC DROIT : FLECHE DE LUMIERE",
    "    INSTANTANEE, 25 BLOCS : 6, RECHARGE 6 S. ACCROUPI : ECLAT AVEUGLANT, CECITE 2 S A 6 BLOCS, 3 DEGATS. RECHARGE 15 S",
    "NIVEAU 2 - CLIC GAUCHE : +4, UN CHOC DE LUMIERE QUI REPOUSSE. CLIC DROIT : 3 LANCES DE LUMIERE TOMBENT DU CIEL SUR LA CIBLE (40 BLOCS) : 10",
    "    RECHARGE 15 S. ACCROUPI : UN BATTEMENT D'AILES, IL MONTE DE 12 BLOCS ET PLANE. RECHARGE 10 S",
    "    EN PLUS - ACCROUPI + CLIC DROIT : PLUIE DE PLUMES, SES AILES TIRENT UNE RAFALE DE PLUMES D'OR DEVANT LUI (15 BLOCS) : 8. RECHARGE 15 S",
    "NIVEAU 3 - CLIC GAUCHE : +6, FEU SACRE 3 S (BRULE, NE MET PAS LE FEU AU DECOR). CLIC DROIT MAINTENU : RAYON SOLAIRE 2 S, 16, REPOUSSE",
    "    RECHARGE 15 S. ACCROUPI : VITESSE DE LA LUMIERE, IL FILE 40 BLOCS : 8 A CEUX QU'IL TRAVERSE. RECHARGE 12 S",
    "    EN PLUS - TOUCHE TECHNIQUE : CHAKRAM, IL LANCE SON AUREOLE (25 BLOCS), ELLE TRANCHE : 12, PUIS LUI REVIENT. RECHARGE 12 S",
    "    EN PLUS - ACCROUPI + CLIC GAUCHE : CROIX DE LUMIERE, UNE CROIX GEANTE JAILLIT DU SOL SOUS LA CIBLE : 15, L'ENVOIE EN L'AIR. RECHARGE 20 S",
    "    LA PLUIE DE PLUMES (ACCROUPI + CLIC DROIT) RESTE AU NIVEAU 3.",
    "ULTI (TOUCHE ULTI, NIVEAU 3) : LE JUGEMENT. LES NUAGES S'OUVRENT, IL S'ELEVE. UN ANGE GEANT DE LUMIERE (8 BLOCS) APPARAIT DERRIERE LUI",
    "    L'EPEE LEVEE. UN CERCLE D'OR MARQUE LA ZONE (20 BLOCS, 40 BLOCS DEVANT) ET UNE PLUIE D'EPEES S'Y ABAT, PUIS L'ANGE ABAT SON EPEE :",
    "    UNE COLONNE DE LUMIERE JUSQU'AU CIEL, ONDE DE LUMIERE, 22 AU CENTRE. NE CASSE RIEN, NE MET PAS LE FEU. 1 FOIS PAR EVEIL",
  ], [
    ["1 - CLIC GAUCHE", (sc) => lCoup(sc, 0), { ...ACTION, echelle: 6.6, lacet: 64, centre: [0, 13, 18] }],
    ["1 - CLIC DROIT", lFleche, { ...LOIN, centre: [0, 12, 56] }],
    ["1 - ACCROUPI", lFlash, { ...ACTION, echelle: 3.6, lacet: -24, tangage: 22, centre: [0, 10, 0] }],
    ["2 - CLIC GAUCHE", (sc) => lCoup(sc, 1), { ...ACTION, echelle: 4.8, lacet: 64, centre: [0, 16, 18] }],
    ["2 - CLIC DROIT", lLances, { ...ACTION, echelle: 3, lacet: -40, tangage: 14, centre: [0, 22, 40] }],
    ["2 - ACCROUPI", lEnvol, { ...ACTION, echelle: 3.4, lacet: -24, tangage: 12, centre: [0, 34, 0], pied: 280 }],
    ["2 - ACCROUPI + CLIC DROIT", lPlumes, { ...ACTION, echelle: 3, lacet: -40, tangage: 16, centre: [0, 18, 46] }],
    ["3 - CLIC GAUCHE", (sc) => lCoup(sc, 2), { ...ACTION, echelle: 4.8, lacet: 64, centre: [0, 16, 18] }],
    ["3 - CLIC DROIT", lRayonSolaire, { ...LOIN, echelle: 2.4, lacet: -50, centre: [0, 16, 64] }],
    ["3 - ACCROUPI", lVitesse, { ...LOIN, echelle: 2.2, lacet: -62, centre: [0, 12, 80] }],
    ["3 - TOUCHE TECHNIQUE", lChakram, { ...LOIN, echelle: 2.8, lacet: -48, centre: [0, 16, 56] }],
    ["3 - ACCROUPI + CLIC GAUCHE", lCroix, { ...LOIN, echelle: 2.5, lacet: -50, tangage: 14, centre: [0, 30, 50] }],
    ["ULTI 1", (sc) => lUlti(sc, 1), ULTI_L],
    ["ULTI 2", (sc) => lUlti(sc, 2), ULTI_L],
    ["ULTI 3", (sc) => lUlti(sc, 3), ULTI_L],
    ["ULTI 4", (sc) => lUlti(sc, 4), ULTI_L],
  ]),
};

// les briques servent aussi aux fichiers du jeu (jeu.mjs)
export { veines, peindre, couronne, armureLumiere, aile, aureole, soleilDos, orbe, lameOmbre, epeeLumiere, lance, chaine, flammeSacree, LUEURS, cercle };

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
const [pouvoir, sorte] = process.argv.slice(2);
if (PRINCIPAL) for (const [nom, f] of Object.entries(PLANCHES)) if ((!pouvoir || nom.startsWith(pouvoir)) && (!sorte || nom.endsWith(sorte))) f();
