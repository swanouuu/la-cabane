// Les effets de Minato 2 : le joueur (qui peut briller, pour la lueur jaune-orange), la tenue du mode Kurama posée sur
// son skin (il garde sa tête, passée dans le doré du mode, et prend toute la tenue aux traits noirs), le balayage de la
// transformation, le flash d'une téléportation, le double qui reste là où il était, le kunai à trois pointes, la Bijuu
// Dama et son dôme. Rien en pointe ni en cône : des pavés, des voiles, de la lumière.
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, PARTIES, texels, clair, mix, hache, norme, croix, lum } from "../../fruits/generateur/moteur.mjs";
import { pointe } from "../../fruits/generateur/rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));

export const ajoute = (a, b) => a.map((v, i) => v + b[i]);
export const sous = (a, b) => a.map((v, i) => v - b[i]);
export const fois = (a, k) => a.map((v) => v * k);
const rotX = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
const rotY = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];
const rotZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];
export const TOUR = Math.PI * 2;
export const brille = (c) => [...c, 1];

export const LUEUR = [255, 176, 54];
export const OR = [255, 214, 90];
export const OR_C = [255, 242, 186];
export const BLANC = [255, 255, 246];
export const NOIR = [34, 24, 12];

// ------------------------------------------------------------------ le joueur
const PIVOTS = { tete: [0, 24, 0], corps: [0, 12, 0], brasD: [-5, 22, 0], brasG: [5, 22, 0], jambeD: [-2, 12, 0], jambeG: [2, 12, 0] };

/**
 * Un joueur : sa peau, dans une pose (comme perso des fruits : o.tete, o.brasD, o.brasG = [rx, rz, ry], les jambes de
 * même ; o.lacet, o.penche, o.decale pour tout le corps ; o.teinte = { c, k }). o.lueur : il brille (la lueur se pose
 * autour de lui) ; o.bande = h : la ligne de la transformation, à la hauteur h (au repos), en lumière blanche.
 */
export function joueur(sc, peau, o = {}) {
  const lacet = o.lacet ?? 0, penche = o.penche ?? 0, decale = o.decale ?? [0, 0, 0];
  const monde = (v) => ajoute(rotY(rotX(sous(v, [0, 12, 0]), penche), lacet), ajoute(decale, [0, 12, 0]));
  const T = {};
  for (const [nom, pv] of Object.entries(PIVOTS)) {
    const a = o[nom];
    const [rx = 0, rz = 0, ry = 0] = typeof a === "number" ? [a] : a ?? [];
    const local = (v) => ajoute(rotY(rotX(rotZ(sous(v, pv), rz), rx), ry), pv);
    T[nom] = { p: (v) => monde(local(v)), n: (v) => norme(rotY(rotX(rotY(rotX(rotZ(v, rz), rx), ry), penche), lacet)) };
  }
  for (const [nom, boite, uv1, uv2, gonfle] of PARTIES(false)) {
    for (const [uv, g, d] of [[uv1, 0, 0], [uv2, gonfle, 0.02]]) for (const t of texels(boite, uv, g, 1)) {
      const i = (t.ty * 64 + t.tx) * 4;
      if (peau.px[i + 3] < 128) continue;
      const l = sc.lumiere(T[nom].n(t.N), false);
      if (l < 0) continue;
      if (o.bande !== undefined && Math.abs(t.centre[1] - o.bande) < 0.9) {
        sc.quad(t.coins.map(T[nom].p), mix(BLANC, OR_C, Math.abs(t.centre[1] - o.bande)), d + 0.01, true);
        continue;
      }
      let c = clair([peau.px[i], peau.px[i + 1], peau.px[i + 2]], o.lueur ? 0.86 + 0.24 * (l - 0.62) / 0.42 : l);
      if (o.teinte) c = mix(c, o.teinte.c, o.teinte.k);
      sc.quad(t.coins.map(T[nom].p), c, d, !!o.lueur);
    }
  }
  if (o.moustaches) moustaches(sc, T.tete);
  if (o.oreilles) oreilles(sc, T.tete);
  return T;
}

/**
 * Les deux oreilles de chakra du manteau, comme celles du manteau de Hachibi (ClientHachibi.oreilles : des blocs de plus
 * en plus fins, penchés de 20° vers l'extérieur), mais plus courtes (deux crans au lieu de trois), dorées et qui brillent.
 */
function oreilles(sc, T) {
  const BLOCS = [[1.45, 0, 3.6, 0.81], [0.85, 3.6, 6.8, 0.58]];
  for (const s of [-1, 1]) {
    const th = s * 20 * Math.PI / 180, c = Math.cos(th), sn = Math.sin(th);
    const ici = ([ex, ey, ez]) => T.p([s * 2.4 + ex * c + ey * sn, 31.7 - ex * sn + ey * c, 0.5 + ez]);
    BLOCS.forEach(([hx, y0, y1, hz], k) => {
      const coul = brille(mix([248, 190, 40], [255, 236, 130], k));
      const P = (x, y, z) => ici([x, y, z]);
      for (const f of [
        [P(-hx, y0, hz), P(hx, y0, hz), P(hx, y1, hz), P(-hx, y1, hz)], [P(hx, y0, -hz), P(-hx, y0, -hz), P(-hx, y1, -hz), P(hx, y1, -hz)],
        [P(hx, y0, hz), P(hx, y0, -hz), P(hx, y1, -hz), P(hx, y1, hz)], [P(-hx, y0, -hz), P(-hx, y0, hz), P(-hx, y1, hz), P(-hx, y1, -hz)],
        [P(-hx, y1, hz), P(hx, y1, hz), P(hx, y1, -hz), P(-hx, y1, -hz)],
      ]) sc.quad(f, coul.slice(0, 3), 0.02, true);
    });
  }
}

/**
 * L'aura du manteau, comme celle de Hachibi (ClientHachibi.enveloppe) : chaque partie du corps redessinée un peu plus
 * grosse autour de son centre, en voile doré très transparent, puis un halo encore plus large et plus léger. À dessiner
 * après tout ce qui est plein (c'est un voile).
 */
export function aura(sc, T, force = 1) {
  const GONFLE = { tete: [1.12, 1.12, 1.12], corps: [1.12, 1.07, 1.25], brasD: [1.24, 1.07, 1.2], brasG: [1.24, 1.07, 1.2], jambeD: [1.2, 1.06, 1.2], jambeG: [1.2, 1.06, 1.2] };
  for (const [plus, alpha, coul] of [[1, 0.22 * force, [255, 210, 70]], [1.12, 0.1 * force, [255, 224, 110]]]) {
    for (const [nom, [x0, y0, z0, x1, y1, z1]] of PARTIES(false)) {
      const g = GONFLE[nom].map((v) => v * plus), cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
      const X = (x) => cx + (x - cx) * g[0], Y = (y) => cy + (y - cy) * g[1], Z = (z) => cz + (z - cz) * g[2];
      const P = (x, y, z) => T[nom].p([X(x), Y(y), Z(z)]);
      for (const f of [
        [P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)], [P(x1, y0, z0), P(x0, y0, z0), P(x0, y1, z0), P(x1, y1, z0)],
        [P(x1, y0, z1), P(x1, y0, z0), P(x1, y1, z0), P(x1, y1, z1)], [P(x0, y0, z0), P(x0, y0, z1), P(x0, y1, z1), P(x0, y1, z0)],
        [P(x0, y1, z1), P(x1, y1, z1), P(x1, y1, z0), P(x0, y1, z0)],
      ]) sc.voile(f, coul, alpha);
    }
  }
}

/**
 * Les moustaches du mode Kurama : trois traits fins sur chaque joue, sous les yeux, du bord du visage vers le nez (le
 * haut remonte un peu vers l'extérieur, le bas descend), et qui débordent un peu sur le côté de la tête.
 */
function moustaches(sc, T) {
  const e = 0.34;
  for (const s of [-1, 1]) {
    [[26.5, 0.35], [25.6, 0], [24.7, -0.35]].forEach(([y, pente]) => {
      // sur le visage (z = 4) : de x = s × 1,5 au bord
      const a = [s * 1.5, y - pente * 0.4, 4.03], b = [s * 4.0, y + pente * 0.6, 4.03];
      sc.quad([[a[0], a[1] - e / 2, a[2]], [b[0], b[1] - e / 2, b[2]], [b[0], b[1] + e / 2, b[2]], [a[0], a[1] + e / 2, a[2]]].map(T.p), NOIR, 0.03);
      // sur le côté (x = ±4) : le trait continue un peu vers l'arrière
      const c = [s * 4.03, y + pente * 0.6, 4.0], d = [s * 4.03, y + pente * 0.9, 2.9];
      sc.quad([[c[0], c[1] - e / 2, c[2]], [d[0], d[1] - e / 2, d[2]], [d[0], d[1] + e / 2, d[2]], [c[0], c[1] + e / 2, c[2]]].map(T.p), NOIR, 0.03);
    });
  }
}

// Le skin du mode Kurama envoyé par l'utilisateur : sa tenue est copiée telle quelle (corps, bras, jambes, les deux
// couches) ; de sa tête, on ne garde que les traits noirs du visage (les moustaches, la bouche) et les yeux rouges.
const KCM = lire(path.join(ICI, "skins", "kurama-mode.png"));
/**
 * La tête du joueur passe dans les couleurs du skin, en gardant la différence entre ses cheveux et son visage : on
 * repère la couleur de sa peau (le bas du visage, sous les yeux, hors bouche) ; ce qui lui ressemble devient un crème
 * clair, tout le reste (les cheveux, la barbe, un chapeau) prend le jaune-orange plus vif des cheveux du skin.
 */
function teintePeau(skin) {
  const l = [];
  for (const [x, y] of [[9, 13], [10, 13], [13, 13], [14, 13], [9, 14], [10, 14], [13, 14], [14, 14], [9, 15], [14, 15]]) {
    const i = (y * 64 + x) * 4;
    if (skin.px[i + 3] > 128) l.push([skin.px[i], skin.px[i + 1], skin.px[i + 2]]);
  }
  if (!l.length) return [200, 150, 120];
  return [0, 1, 2].map((k) => l.map((c) => c[k]).sort((a, b) => a - b)[Math.floor(l.length / 2)]);
}
const rampe = (stops, t) => {
  for (let k = 1; k < stops.length; k++) if (t <= stops[k][0]) return mix(stops[k - 1][1], stops[k][1], (t - stops[k - 1][0]) / (stops[k][0] - stops[k - 1][0]));
  return stops[stops.length - 1][1];
};
const CHEVEUX = [[0, [140, 104, 10]], [0.18, [222, 172, 22]], [0.42, [250, 212, 44]], [0.68, [255, 234, 86]], [1, [255, 248, 156]]];
const estPeau = (c, peau) => Math.hypot(c[0] - peau[0], c[1] - peau[1], c[2] - peau[2]) < 52;
/** Ses cheveux (et tout ce qui n'est pas sa peau) passés dans le jaune des cheveux du skin. */
const dore = (c) => rampe(CHEVEUX, lum(c) / 255);
const VISAGE_KCM = [255, 255, 153];
/**
 * Ce que la tête du skin impose : ses yeux (blanc dehors, rouge dedans) et ses moustaches (ses deux traits noirs sur
 * chaque joue, qui passent sur les côtés de la tête), et une petite bouche de deux pixels noirs ; sinon null.
 */
function imposeTete(i) {
  if (KCM.px[i + 3] < 128) return null;
  const x = (i / 4) % 64, y = Math.floor(i / 4 / 64);
  const c = [KCM.px[i], KCM.px[i + 1], KCM.px[i + 2]];
  if ((y === 11 || y === 12) && [9, 10, 13, 14].includes(x)) return c;
  // la bouche : deux pixels noirs au milieu (celle du skin en fait trois)
  if (y === 14 && (x === 11 || x === 12)) return NOIR;
  if (y === 14 && x === 13) return null;
  return lum(c) < 70 ? c : null;
}

/**
 * La peau du mode Kurama posée sur le skin du joueur : sous la hauteur h (au repos, de 0 aux pieds à 32 en haut de la
 * tête), la tenue du skin et la tête du joueur passée dans son jaune, avec les traits noirs et les yeux du skin ;
 * au-dessus, son skin normal. h = 99 : tout le mode. blanc : le flash (tout blanc, les traits noirs seuls).
 */
export function tenue(skin, h = 99, blanc = false) {
  const px = Buffer.from(skin.px);
  const peau = teintePeau(skin);
  for (const [nom, boite, uv1, uv2] of PARTIES(false)) {
    for (const [uv, couche] of [[uv1, 1], [uv2, 2]]) for (const t of texels(boite, uv, 0, 1)) {
      if (t.centre[1] > h) continue;
      const i = (t.ty * 64 + t.tx) * 4;
      let c;
      if (nom === "tete") {
        if (couche === 1) c = imposeTete(i);
        if (!c) {
          if (skin.px[i + 3] < 128) continue;
          const orig = [skin.px[i], skin.px[i + 1], skin.px[i + 2]];
          // le visage du skin Kurama (son jaune, ses yeux, ses moustaches, sans sa bouche noire) : sur tout le bas du
          // visage (des yeux au menton), et ailleurs là où il a de la peau ; ses cheveux restent les siens
          if (couche === 1 && KCM.px[i + 3] > 128 && ((t.face === "devant" && t.ty >= 11) || estPeau(orig, peau))) {
            const k = [KCM.px[i], KCM.px[i + 1], KCM.px[i + 2]];
            c = lum(k) < 70 ? VISAGE_KCM : k;
          } else c = dore(orig);
        }
      } else {
        if (KCM.px[i + 3] < 128) { px[i + 3] = 0; continue; }
        c = [KCM.px[i], KCM.px[i + 1], KCM.px[i + 2]];
      }
      if (blanc && lum(c) > 60) c = mix(c, [255, 255, 255], 0.9);
      px.set([...c.map(Math.round), 255], i);
    }
  }
  return { w: 64, h: 64, px };
}

/** L'anneau de lumière qui monte le long du corps pendant la transformation, à la hauteur h, autour de c. */
export function anneauMontant(sc, c, h, r = 9) {
  const n = 32;
  for (let q = 0; q < 26; q++) {
    const a = hache(q, 1, 800) * TOUR, rr = r * (0.9 + 0.4 * hache(q, 2, 800)), dy = (hache(q, 3, 800) - 0.3) * 6;
    cubeSimple(sc, ajoute(c, [Math.cos(a) * rr, h + dy, Math.sin(a) * rr]), 0.6 + 0.5 * hache(q, 4, 800), brille(q % 3 ? OR_C : BLANC), true);
  }
  for (let q = 0; q < n; q++) {
    const a0 = (q / n) * TOUR, a1 = ((q + 1) / n) * TOUR;
    const P = (a, rr, y) => ajoute(c, [Math.cos(a) * rr, h + y, Math.sin(a) * rr]);
    sc.voile([P(a0, r - 2, 0), P(a1, r - 2, 0), P(a1, r + 2.5, 0), P(a0, r + 2.5, 0)], OR_C, 0.55);
    sc.voile([P(a0, r, -0.6), P(a1, r, -0.6), P(a1, r, 3), P(a0, r, 3)], OR, 0.35);
  }
}

// ------------------------------------------------------------------ petites formes
/** Un cube droit, d'une couleur. */
export function cubeSimple(sc, c, s, couleur, aile = false) {
  const h = s / 2;
  const F = [
    [[0, 0, 1], [[-h, -h, h], [h, -h, h], [h, h, h], [-h, h, h]]], [[0, 0, -1], [[h, -h, -h], [-h, -h, -h], [-h, h, -h], [h, h, -h]]],
    [[1, 0, 0], [[h, -h, h], [h, -h, -h], [h, h, -h], [h, h, h]]], [[-1, 0, 0], [[-h, -h, -h], [-h, -h, h], [-h, h, h], [-h, h, -h]]],
    [[0, 1, 0], [[-h, h, h], [h, h, h], [h, h, -h], [-h, h, -h]]], [[0, -1, 0], [[-h, -h, -h], [h, -h, -h], [h, -h, h], [-h, -h, h]]],
  ];
  for (const [n, coins] of F) {
    const l = sc.lumiere(n, false);
    if (l < 0) continue;
    sc.quad(coins.map((p) => ajoute(c, p)), couleur[3] === 1 ? couleur.slice(0, 3) : clair(couleur, l), 0, aile);
  }
}

/** Le flash d'une téléportation : un éclat blanc-jaune qui brille (la lueur fait le halo), et un trait de lumière vertical. */
export function flash(sc, p, k = 1) {
  cubeSimple(sc, p, 3.2 * k, brille(BLANC), true);
  for (const [dx, dy, dz, s] of [[0, 4, 0, 1.6], [0, -4, 0, 1.6], [0, 8, 0, 1], [0, -8, 0, 1], [3, 0, 0, 1.4], [-3, 0, 0, 1.4], [0, 0, 3, 1.4], [0, 0, -3, 1.4]]) {
    cubeSimple(sc, ajoute(p, [dx * k, dy * k, dz * k]), s * k, brille(dy ? OR_C : BLANC), true);
  }
  for (let q = 0; q < 8; q++) {
    const a = (q / 8) * TOUR + 0.3;
    cubeSimple(sc, ajoute(p, [Math.cos(a) * 7 * k, (hache(q, 1, 810) - 0.5) * 6 * k, Math.sin(a) * 7 * k]), 0.7 * k, brille(OR), true);
  }
}

/** Le double transparent qui reste une fraction de seconde là où il était. */
export function fantome(sc, decale, lacet = 0, alpha = 0.3, c = OR_C) {
  const m = (v) => ajoute(rotY(sous(v, [0, 12, 0]), lacet), ajoute(decale, [0, 12, 0]));
  for (const [, [x0, y0, z0, x1, y1, z1]] of PARTIES(false)) {
    const P = (x, y, z) => m([x, y, z]);
    for (const f of [
      [P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)], [P(x0, y0, z0), P(x1, y0, z0), P(x1, y1, z0), P(x0, y1, z0)],
      [P(x0, y0, z0), P(x0, y0, z1), P(x0, y1, z1), P(x0, y1, z0)], [P(x1, y0, z0), P(x1, y0, z1), P(x1, y1, z1), P(x1, y1, z0)],
      [P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)],
    ]) sc.voile(f, c, alpha);
  }
}

/** Le kunai de Minato, à trois pointes : la pointe en p, vers dir ; k = 1 pour un kunai en main. */
export function kunai(sc, p, dir, k = 1) {
  const d = norme(dir);
  let e = croix(d, [0, 1, 0]);
  if (Math.hypot(...e) < 0.2) e = croix(d, [1, 0, 0]);
  e = norme(e);
  const base = ajoute(p, fois(d, -6 * k));
  const acier = (t) => mix([92, 96, 108], [220, 226, 236], t);
  pointe(sc, base, d, 6 * k, 1.8 * k, acier, false, false);
  for (const s of [-1, 1]) pointe(sc, ajoute(base, fois(e, s * 0.6 * k)), norme(ajoute(fois(d, 0.55), fois(e, s))), 3 * k, 1 * k, acier, false, false);
  for (let q = 1; q <= 4; q++) cubeSimple(sc, ajoute(base, fois(d, -q * 0.9 * k)), 1 * k, q % 2 ? [56, 44, 38] : [86, 70, 58]);
  const bout = ajoute(base, fois(d, -5.2 * k));
  for (let q = 0; q < 10; q++) {
    const a = (q / 10) * TOUR;
    cubeSimple(sc, ajoute(bout, ajoute(fois(e, Math.cos(a) * 1.1 * k), fois(d, Math.sin(a) * 1.1 * k))), 0.6 * k, [70, 72, 80]);
  }
}

/** Le kunai dans la main droite d'un joueur. */
export const kunaiEnMain = (sc, T, k = 1) => kunai(sc, T.brasD.p([-6, 4.5, 0.6]), T.brasD.n([0, -1, 0.12]), k);

// ------------------------------------------------------------------ boules en paliers (Minecraft) : la Bijuu Dama, le dôme
/**
 * Une boule faite de tranches (chaque tranche : deux pavés croisés, en octogone), comme le fruit posé. couleur(n, y) pour
 * chaque face ; voile : alpha (transparente) ou 0 (pleine) ; moitie : seulement le haut (le dôme).
 */
export function boule(sc, c, r, couleur, { tranche = 2, voile = 0, moitie = false, aile = true } = {}) {
  const n = Math.max(2, Math.round((2 * r) / tranche));
  for (let k = moitie ? Math.floor(n / 2) : 0; k < n; k++) {
    const y0 = -r + k * (2 * r / n), y1 = y0 + 2 * r / n, ym = (y0 + y1) / 2;
    const w = Math.sqrt(Math.max(0, r * r - ym * ym));
    if (w < 0.3) continue;
    for (const [hx, hz] of [[w, w * 0.42], [w * 0.42, w], [w * 0.78, w * 0.78]]) {
      const x0 = c[0] - hx, x1 = c[0] + hx, z0 = c[2] - hz, z1 = c[2] + hz, a = c[1] + y0, b = c[1] + y1;
      const F = [
        [[0, 0, 1], [[x0, a, z1], [x1, a, z1], [x1, b, z1], [x0, b, z1]]], [[0, 0, -1], [[x1, a, z0], [x0, a, z0], [x0, b, z0], [x1, b, z0]]],
        [[1, 0, 0], [[x1, a, z1], [x1, a, z0], [x1, b, z0], [x1, b, z1]]], [[-1, 0, 0], [[x0, a, z0], [x0, a, z1], [x0, b, z1], [x0, b, z0]]],
        [[0, 1, 0], [[x0, b, z1], [x1, b, z1], [x1, b, z0], [x0, b, z0]]], [[0, -1, 0], [[x0, a, z0], [x1, a, z0], [x1, a, z1], [x0, a, z1]]],
      ];
      for (const [nn, coins] of F) {
        // transparente : seulement les faces tournées vers nous, et pas les dessus/dessous entre deux tranches (sinon des rayures)
        if (voile > 0 && (sc.dirVue(nn)[2] <= 0 || (nn[1] > 0 && k < n - 1) || (nn[1] < 0 && k > 0))) continue;
        const l = sc.lumiere(nn, voile > 0);
        if (l < 0) continue;
        const col = couleur(nn, ym / r);
        if (voile > 0) sc.voile(coins, col.slice(0, 3), voile);
        else sc.quad(coins, col[3] === 1 ? col.slice(0, 3) : clair(col, l), 0, aile);
      }
    }
  }
}

/** La Bijuu Dama : une boule noire aux reflets violets ; coeur (0 à 1) : le cœur rouge qui brûle à travers. */
export function bijuuDama(sc, c, r, coeur = 0) {
  if (coeur > 0) {
    // la boule noire, pleine ; son cœur rouge perce par devant (vers qui regarde, +z) en un disque qui brûle
    boule(sc, c, r, (n, y) => brille(mix([16, 8, 22], [74, 44, 110], Math.max(0, n[1] * 0.5 + y * 0.3))), { tranche: 2 });
    boule(sc, [c[0], c[1], c[2] + r * 0.5], r * (0.35 + 0.35 * coeur), (n, y) => brille(mix([255, 240, 160], [236, 52, 18], Math.min(1, Math.hypot(n[0], n[1]) * 1.2))), { tranche: 1.2 });
  } else {
    boule(sc, c, r, (n, y) => brille(mix([16, 8, 22], [74, 44, 110], Math.max(0, n[1] * 0.5 + y * 0.3))), { tranche: 2 });
  }
}

/** Des éclats de chakra (bleus, rouges, noirs) qui convergent vers p, entre r et R. */
export function convergent(sc, p, r, R, n = 40, graine = 0) {
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, 820 + graine) * TOUR, b = (hache(q, 2, 820 + graine) - 0.5) * Math.PI;
    const d = r + (R - r) * hache(q, 3, 820 + graine);
    const c = [[60, 110, 255], [230, 50, 34], [24, 16, 30], [150, 90, 255]][q % 4];
    cubeSimple(sc, ajoute(p, [Math.cos(a) * Math.cos(b) * d, Math.sin(b) * d, Math.sin(a) * Math.cos(b) * d]), 0.8 + 1.4 * hache(q, 4, 820 + graine), brille(c), q % 4 !== 2);
  }
}

/** Le dôme de l'explosion : une demi-boule en paliers, blanche en haut, orange au pied, des éclats tout autour. */
export function dome(sc, c, r) {
  boule(sc, c, r, (n, y) => brille(mix([255, 252, 236], [255, 140, 36], Math.max(0, 1 - y * 1.25) ** 2)), { tranche: r / 9, moitie: true });
  for (let q = 0; q < 60; q++) {
    const a = hache(q, 1, 830) * TOUR, rr = r * (1 + 0.15 * hache(q, 2, 830));
    cubeSimple(sc, ajoute(c, [Math.cos(a) * rr, r * 0.08 * hache(q, 3, 830), Math.sin(a) * rr]), r * (0.03 + 0.04 * hache(q, 4, 830)), brille(q % 3 ? [255, 160, 50] : [255, 230, 140]), true);
  }
}
