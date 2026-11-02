// Maquettes des espionnes du WOOHP (Totally Spies).
// Touche Éveil : la tenue arrive tout de suite. Le joueur garde sa tête (sa peau) ; le reste devient une combinaison
// (verte pour Sam, rouge pour Clover, jaune pour Alex) sur un modèle tout fin, avec la ceinture-grappin et des bottes à
// talons. Les gadgets : sept objets 16×16 (les vraies textures, dans textures/) et ce que chacun fait.
// node rendu.mjs → planche-tenues.png, planche-gadgets.png, textures/*.png
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, PARTIES, texels, clair, mix, lum, hache, norme, croix, scene, ecrireTexte } from "./moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const SKINS = Object.fromEntries(["steve", "efe", "kai"].map((n) => [n, lire(path.join(ICI, "skins", n + ".png"))]));

const ajoute = (a, b) => a.map((v, i) => v + b[i]);
const sous = (a, b) => a.map((v, i) => v - b[i]);
const fois = (a, k) => a.map((v) => v * k);
const rotX = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
const rotY = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];
const rotZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];

const ARGENT = [206, 210, 222];
const SEMELLE = [42, 36, 48];
const ROSE = [246, 104, 172];
const ROSE_CLAIR = [255, 170, 210];
const OR = [232, 182, 64];
const OR_CLAIR = [255, 222, 120];
const FOND = [236, 236, 242];

const ESPIONNES = {
  sam: { nom: "SAM", couleur: "verte", base: [58, 168, 70], peau: "efe" },
  clover: { nom: "CLOVER", couleur: "rouge", base: [212, 34, 42], peau: "kai" },
  alex: { nom: "ALEX", couleur: "jaune", base: [240, 196, 28], peau: "steve" },
};

// ------------------------------------------------------------------ la combinaison (une peau 64×64 sans tête)
/** La couleur de la combinaison sur un texel : plis, col, fermeture, ceinture et sa boucle, gants, bottes. */
function couleurTenue(nom, face, [x, y, z], base, tx, ty) {
  const g = 0.95 + 0.07 * hache(tx, ty, 3);
  const pli = face === "haut" || face === "bas" ? 1 : 1 + 0.07 * Math.sin(x * 1.9 + z * 1.4 + y * 0.35 + nom.length);
  const t = (k) => clair(base, k * g);
  let c = t(pli);
  if (nom === "corps") {
    const devant = face === "devant", milieu = Math.abs(x) < 1;
    if (y > 23 || face === "haut") c = t(0.8);
    else if (y < 14) c = devant && milieu ? clair(ARGENT, g * (y > 13 ? 1.06 : 0.9)) : t(0.5);
    else if (devant && milieu) c = y > 22 ? clair(ARGENT, g) : t(1.12 * pli);
    if (face === "bas") c = t(0.5);
  } else if (nom.startsWith("bras")) {
    if (face === "bas" || y < 15) c = t(0.8);
    else if (y < 16) c = t(0.62);
  } else {
    if (face === "bas") c = SEMELLE;
    else if (y < 5) c = t(0.74);
    else if (y < 6) c = t(0.56);
  }
  return [...c.map(Math.round), 255];
}

function tenue(base) {
  const px = Buffer.alloc(64 * 64 * 4);
  for (const [nom, boite, uv] of PARTIES(false)) {
    if (nom === "tete") continue;
    for (const t of texels(boite, uv, 0, 1)) px.set(couleurTenue(nom, t.face, t.centre, base, t.tx, t.ty), (t.ty * 64 + t.tx) * 4);
  }
  return { w: 64, h: 64, px };
}
const TENUES = Object.fromEntries(Object.values(ESPIONNES).map((e) => [e.couleur, tenue(e.base)]));

// ------------------------------------------------------------------ le modèle fin
/** Les talons soulèvent tout le corps. */
const TALON = 2;
/** Le modèle fin : pour chaque partie, son nouveau milieu en x, et ce qu'on garde de sa largeur et de sa profondeur. */
const FIN = {
  corps: { cx: 0, sx: 0.5, sz: 0.6 },
  brasD: { cx: -2.8, sx: 0.4, sz: 0.45 },
  brasG: { cx: 2.8, sx: 0.4, sz: 0.45 },
  jambeD: { cx: -1, sx: 0.45, sz: 0.55 },
  jambeG: { cx: 1, sx: 0.45, sz: 0.55 },
};
/** Le devant du ventre (où est la boucle), une fois aminci. */
const VENTRE = 2 * FIN.corps.sz;
const amincir = (nom, cx0) => (v) => [FIN[nom].cx + (v[0] - cx0) * FIN[nom].sx, v[1] + TALON, v[2] * FIN[nom].sz];

/** Une pose : chaque partie tourne autour de son pivot (rz, puis rx, puis ry), puis tout le corps tourne et se déplace. */
function poseur(o = {}) {
  const lacet = o.lacet ?? 0, decale = o.decale ?? [0, 0, 0], bascule = o.bascule ?? 0;
  const monde = (v) => ajoute(rotY(rotX(sous(v, [0, 14, 0]), bascule), lacet), ajoute(decale, [0, 14, 0]));
  return (pivot, rx = 0, rz = 0, ry = 0) => ({
    p: (v) => monde(ajoute(rotY(rotX(rotZ(sous(v, pivot), rz), rx), ry), pivot)),
    n: (v) => norme(rotY(rotX(rotY(rotX(rotZ(v, rz), rx), ry), bascule), lacet)),
  });
}
const ID = { p: (v) => v, n: (v) => norme(v) };

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
      sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], clair(c, l), decale, aile);
    }
  }
}

/** Un bâton entre deux points (câble, rayon, souffle) : une boîte fine, de section `e`. */
function baton(sc, a, b, e, couleur, aile = true, decale = 0) {
  const d = sous(b, a), L = Math.hypot(...d), ax = norme(d);
  let u = croix(ax, [0, 1, 0]);
  if (Math.hypot(...u) < 0.2) u = croix(ax, [1, 0, 0]);
  u = norme(u);
  const v = croix(ax, u);
  const n = Math.max(1, Math.round(L / 1.5));
  for (const [nu, nv] of [[u, v], [v, fois(u, -1)], [fois(u, -1), fois(v, -1)], [fois(v, -1), u]]) {
    const l = sc.lumiere(nu, true);
    for (let k = 0; k < n; k++) {
      const p = (s, w) => ajoute(ajoute(a, fois(d, s / n)), ajoute(fois(nu, e / 2), fois(nv, (w * e) / 2)));
      sc.quad([p(k, -1), p(k + 1, -1), p(k + 1, 1), p(k, 1)], clair(couleur, l), decale, aile);
    }
  }
}

/** Une boule : un cœur et un bord plus clair là où on la voit de biais. */
function boule(sc, c, r, coeur, bord, aile = false) {
  const lat = 12, lon = 18;
  for (let i = 0; i < lat; i++) for (let j = 0; j < lon; j++) {
    const pt = (a, b) => {
      const p = -Math.PI / 2 + (Math.PI * a) / lat, q = (2 * Math.PI * b) / lon;
      return [Math.cos(p) * Math.cos(q), Math.sin(p), Math.cos(p) * Math.sin(q)];
    };
    const ns = [pt(i, j), pt(i + 1, j), pt(i + 1, j + 1), pt(i, j + 1)];
    const n = norme(ns.reduce((s, v) => ajoute(s, v), [0, 0, 0]));
    const vue = sc.dirVue(n);
    const rim = Math.pow(1 - Math.min(1, Math.abs(vue[2])), 1.6);
    sc.quad(ns.map((v) => ajoute(c, fois(v, r))), mix(coeur, bord, rim), 0, aile);
  }
}

/** Un objet 16×16 à plat (comme un objet tenu en main) : son centre, son axe droite et son axe haut, sa taille. */
function objet(sc, icone, T, centre, droite, haut, taille = 8) {
  const n = croix(droite, haut);
  const l = sc.lumiere(T.n(n), true);
  for (let j = 0; j < 16; j++) for (let i = 0; i < 16; i++) {
    const k = (j * 16 + i) * 4;
    if (icone.px[k + 3] < 128) continue;
    const p = (a, b) => T.p(ajoute(centre, ajoute(fois(droite, (a / 16 - 0.5) * taille), fois(haut, (0.5 - b / 16) * taille))));
    sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], clair([icone.px[k], icone.px[k + 1], icone.px[k + 2]], l), 0.05);
  }
}

/** Où tient-on un objet : sa nouvelle main (D ou G), et s'il pointe en avant (rouge à lèvres) ou se tient droit. */
function prise(cote, pointe = false) {
  const x = FIN["bras" + cote].cx;
  return pointe
    ? { centre: [x, 12 + TALON - 3.2, 0.6], droite: [0, 0, 1], haut: [0, -1, 0] }
    : { centre: [x, 12 + TALON - 2.6, 1.6], droite: [0, -1, 0], haut: [0, 0, 1] };
}

/**
 * Une espionne : sa tête (sa peau, les deux couches), et sa combinaison sur le modèle fin, dans une pose. o.brasD,
 * o.brasG : [rx, rz] ; o.jambeD, o.jambeG : rx ; o.tete : [rx, ry] ; o.lacet, o.decale, o.bascule : tout le corps ;
 * o.lunettes ; o.objetD, o.objetG : { icone, pointe }. Rend les transformations de chaque partie.
 */
function espionne(sc, esp, o = {}) {
  const peau = SKINS[esp.peau], habit = TENUES[esp.couleur];
  const P = poseur(o);
  const T = {
    tete: P([0, 24 + TALON, 0], o.tete?.[0] ?? 0, 0, o.tete?.[1] ?? 0),
    corps: P([0, 0, 0]),
    brasD: P([FIN.brasD.cx, 22 + TALON, 0], ...(o.brasD ?? [0, -0.06])),
    brasG: P([FIN.brasG.cx, 22 + TALON, 0], ...(o.brasG ?? [0, 0.06])),
    jambeD: P([FIN.jambeD.cx, 12 + TALON, 0], o.jambeD ?? 0),
    jambeG: P([FIN.jambeG.cx, 12 + TALON, 0], o.jambeG ?? 0),
  };
  for (const [nom, boite, uv1, uv2, gonfle] of PARTIES(false)) {
    if (nom === "tete") {
      for (const [uv, g, d] of [[uv1, 0, 0], [uv2, gonfle, 0.02]]) for (const t of texels(boite, uv, g, 1)) {
        const i = (t.ty * 64 + t.tx) * 4;
        if (peau.px[i + 3] < 128) continue;
        const l = sc.lumiere(T.tete.n(t.N), false);
        if (l < 0) continue;
        sc.quad(t.coins.map((v) => T.tete.p(ajoute(v, [0, TALON, 0]))), clair([peau.px[i], peau.px[i + 1], peau.px[i + 2]], l), d);
      }
      continue;
    }
    const fin = amincir(nom, (boite[0] + boite[3]) / 2);
    for (const t of texels(boite, uv1, 0, 1)) {
      const i = (t.ty * 64 + t.tx) * 4;
      const l = sc.lumiere(T[nom].n(t.N), false);
      if (l < 0) continue;
      sc.quad(t.coins.map((v) => T[nom].p(fin(v))), clair([habit.px[i], habit.px[i + 1], habit.px[i + 2]], l));
    }
  }
  // les talons : une tige d'argent sous l'arrière de chaque botte, avec sa petite tuyère
  for (const nom of ["jambeD", "jambeG"]) {
    const cx = FIN[nom].cx;
    pave(sc, [cx - 0.4, 0.4, -1], [cx + 0.4, TALON, -0.3], T[nom], () => ARGENT, 0.4);
    pave(sc, [cx - 0.5, 0, -1.1], [cx + 0.5, 0.4, -0.2], T[nom], () => [110, 114, 130], 0.5);
  }
  // la boucle de la ceinture, d'où part le grappin : ronde, en argent, un cœur rose au milieu
  pave(sc, [-0.85, 12.3 + TALON, VENTRE - 0.05], [0.85, 13.9 + TALON, VENTRE + 0.4], T.corps,
    (f, u, v) => (f === "devant" && Math.abs(u - 0.5) < 0.26 && Math.abs(v - 0.5) < 0.26 ? ROSE : ARGENT), 0.32);
  if (o.lunettes) {
    for (const s of [-1, 1]) {
      pave(sc, [s > 0 ? 0.5 : -3.8, 26.6 + TALON, 4.05], [s > 0 ? 3.8 : -0.5, 29.1 + TALON, 4.5], T.tete,
        (f, u, v) => (f === "devant" && u > 0.12 && u < 0.88 && v > 0.16 && v < 0.84 ? mix([236, 40, 40], [255, 150, 40], v) : ROSE), 0.42);
    }
    pave(sc, [-0.5, 28.2 + TALON, 4.1], [0.5, 28.7 + TALON, 4.4], T.tete, () => ROSE, 0.5);
    for (const s of [-1, 1]) pave(sc, [s * 3.8 - 0.2, 28.2 + TALON, -1], [s * 3.8 + 0.2, 28.7 + TALON, 4.4], T.tete, () => ROSE, 0.6);
  }
  for (const cote of ["D", "G"]) {
    const ob = o["objet" + cote];
    if (!ob) continue;
    const pr = prise(cote, ob.pointe);
    objet(sc, ob.icone, T["bras" + cote], pr.centre, pr.droite, pr.haut, ob.taille ?? 8);
  }
  return T;
}

/** Un joueur normal (la cible) : sa peau, debout ou qui vole ; o.chaleur le dessine comme vu aux lunettes infrarouges. */
function joueur(sc, peau, o = {}) {
  const lacet = o.lacet ?? 0, penche = o.penche ?? 0, decale = o.decale ?? [0, 0, 0], bras = o.bras ?? [0, 0];
  for (const [nom, boite, uv1, uv2, gonfle] of PARTIES(false)) {
    const s = nom === "brasD" ? -1 : nom === "brasG" ? 1 : 0;
    const local = s ? (v) => ajoute(rotX(rotZ(sous(v, [s * 5, 22, 0]), s * bras[1]), bras[0]), [s * 5, 22, 0]) : (v) => v;
    const nLocal = s ? (v) => rotX(rotZ(v, s * bras[1]), bras[0]) : (v) => v;
    const T = (v) => ajoute(rotY(rotX(sous(local(v), [0, 12, 0]), penche), lacet), ajoute(decale, [0, 12, 0]));
    for (const [uv, g, d] of [[uv1, 0, 0], [uv2, gonfle, 0.02]]) for (const t of texels(boite, uv, g, 1)) {
      const i = (t.ty * 64 + t.tx) * 4;
      if (peau.px[i + 3] < 128) continue;
      const l = sc.lumiere(rotY(rotX(nLocal(t.N), penche), lacet), false);
      if (l < 0) continue;
      const rgb = [peau.px[i], peau.px[i + 1], peau.px[i + 2]];
      const c = o.chaleur ? mix([196, 24, 10], [255, 236, 96], 0.25 + 0.75 * (lum(rgb) / 255) * l) : clair(rgb, l);
      sc.quad(t.coins.map(T), c, d + (o.devant ?? 0), !!o.aile);
    }
  }
}

/** Un bloc de pierre taillée (16 de côté), coin bas en (x, y, z). */
function bloc(sc, x, y, z, base = [132, 132, 138]) {
  pave(sc, [x, y, z], [x + 16, y + 16, z + 16], ID, (f, u, v, i, j) => {
    const joint = j % 8 === 0 || (j < 8 ? i === 0 : i === 8);
    return clair(base, joint ? 0.62 : 0.9 + 0.16 * hache(i + x, j + y * 3 + z, 9));
  }, 1);
}

/** Une bulle de chewing-gum transparente, posée par-dessus l'image (avec un reflet). */
function bulle(sc, c, r) {
  const [cx, cy] = sc.vue(c), [hx, hy] = sc.vue(ajoute(c, [0, r, 0]));
  const R = Math.hypot(hx - cx, hy - cy);
  for (let y = Math.max(0, Math.floor(cy - R)); y < Math.min(sc.h, cy + R); y++) {
    for (let x = Math.max(0, Math.floor(cx - R)); x < Math.min(sc.w, cx + R); x++) {
      const d = Math.hypot(x - cx, y - cy) / R;
      if (d > 1) continue;
      const reflet = Math.hypot((x - (cx - 0.38 * R)) / (0.2 * R), (y - (cy - 0.42 * R)) / (0.11 * R)) < 1;
      const a = reflet ? 0.75 : 0.2 + 0.6 * Math.pow(d, 7);
      const col = reflet ? [255, 245, 250] : [255, 108, 186];
      const k = (y * sc.w + x) * 4;
      for (let q = 0; q < 3; q++) sc.img[k + q] = sc.img[k + q] * (1 - a) + col[q] * a;
    }
  }
}

// ------------------------------------------------------------------ les gadgets (16×16)
function icone(dessin) {
  const px = Buffer.alloc(16 * 16 * 4);
  const dans = (x, y) => x >= 0 && x < 16 && y >= 0 && y < 16;
  const plein = (x, y) => dans(x, y) && px[(y * 16 + x) * 4 + 3] > 0;
  const p = {
    pt(x, y, c) { if (dans(x, y)) px.set([...c.map(Math.round), 255], (y * 16 + x) * 4); },
    efface(x, y) { if (dans(x, y)) px.fill(0, (y * 16 + x) * 4, (y * 16 + x) * 4 + 4); },
    rect(x0, y0, x1, y1, c) { for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) p.pt(x, y, c); },
    ligne(x0, y0, x1, y1, c) {
      const n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
      for (let k = 0; k <= n; k++) p.pt(Math.round(x0 + ((x1 - x0) * k) / n), Math.round(y0 + ((y1 - y0) * k) / n), c);
    },
    rond(cx, cy, r, c) { for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) if (Math.hypot(x - cx, y - cy) <= r) p.pt(x, y, c); },
    /** Assombrit le bord de ce qui est dessiné (ce qui touche le vide). */
    bord(k = 0.6) {
      const l = [];
      for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
        if (plein(x, y) && (!plein(x - 1, y) || !plein(x + 1, y) || !plein(x, y - 1) || !plein(x, y + 1))) l.push([x, y]);
      }
      for (const [x, y] of l) { const i = (y * 16 + x) * 4; for (let q = 0; q < 3; q++) px[i + q] = Math.round(px[i + q] * k); }
    },
  };
  dessin(p);
  return { w: 16, h: 16, px };
}

const GADGETS = {
  rouge_a_levres: icone((p) => {
    p.rect(5, 10, 10, 14, OR);
    p.rect(5, 8, 10, 9, ARGENT);
    p.rect(6, 6, 9, 7, OR_CLAIR);
    p.rect(6, 1, 9, 5, [222, 30, 70]);
    p.efface(6, 1); p.efface(7, 1); p.efface(6, 2);
    p.bord();
    p.rect(6, 11, 6, 13, OR_CLAIR);
    p.rect(6, 9, 9, 9, [240, 244, 252]);
    p.pt(7, 3, [255, 128, 150]); p.pt(7, 4, [255, 128, 150]); p.pt(8, 2, [255, 128, 150]);
    for (const [x, y] of [[12, 0], [11, 1], [13, 1], [12, 2]]) p.pt(x, y, [255, 150, 200]);
    p.pt(12, 1, [255, 255, 255]);
  }),
  seche_cheveux: icone((p) => {
    p.rect(2, 3, 11, 8, ROSE);
    p.efface(2, 3); p.efface(2, 8);
    p.rect(12, 4, 14, 7, [150, 150, 166]);
    p.rect(1, 4, 1, 7, [150, 150, 166]);
    p.rect(5, 9, 8, 14, ROSE);
    p.bord();
    p.rect(3, 4, 10, 4, ROSE_CLAIR);
    p.pt(1, 5, [90, 90, 104]); p.pt(1, 7, [90, 90, 104]);
    p.pt(14, 5, [70, 70, 84]); p.pt(14, 6, [70, 70, 84]);
    p.pt(6, 10, [255, 236, 120]);
  }),
  lunettes: icone((p) => {
    p.rect(0, 5, 6, 11, ROSE); p.rect(9, 5, 15, 11, ROSE);
    for (const [x, y] of [[0, 5], [6, 5], [0, 11], [6, 11], [9, 5], [15, 5], [9, 11], [15, 11]]) p.efface(x, y);
    p.rect(7, 6, 8, 7, ROSE);
    p.bord();
    for (const x0 of [1, 10]) {
      for (let y = 6; y <= 10; y++) p.rect(x0, y, x0 + 4, y, mix([228, 36, 36], [255, 150, 40], (y - 6) / 4));
      p.pt(x0 + 1, 7, [255, 220, 214]); p.pt(x0 + 1, 8, [255, 190, 170]);
    }
  }),
  parfum: icone((p) => {
    p.rect(4, 7, 11, 14, [150, 78, 206]);
    p.rect(4, 7, 11, 8, [214, 190, 244]);
    for (const [x, y] of [[4, 7], [11, 7], [4, 14], [11, 14]]) p.efface(x, y);
    p.rect(6, 5, 9, 6, OR);
    p.rect(7, 3, 8, 4, OR);
    p.rect(3, 4, 6, 4, [190, 140, 60]);
    p.rect(0, 3, 2, 6, ROSE);
    p.efface(0, 3); p.efface(0, 6);
    p.bord();
    p.rect(5, 9, 5, 12, [196, 140, 244]);
    for (const [x, y] of [[10, 3], [12, 2], [13, 4], [14, 1]]) p.pt(x, y, [228, 206, 255]);
  }),
  chewing_gum: icone((p) => {
    p.rond(8, 7, 6.2, [255, 128, 190]);
    p.rect(6, 13, 10, 14, [230, 96, 158]);
    p.bord(0.7);
    for (const [x, y] of [[5, 3], [4, 4], [4, 5], [5, 4]]) p.pt(x, y, [255, 228, 242]);
    p.pt(11, 10, [255, 176, 214]);
  }),
  talons: icone((p) => {
    const botte = [230, 64, 146];
    p.rect(3, 1, 7, 9, botte);
    p.rect(3, 10, 9, 11, botte);
    p.rect(6, 12, 12, 12, botte);
    p.rect(9, 13, 13, 13, botte);
    p.rect(9, 14, 13, 14, SEMELLE);
    p.rect(3, 12, 4, 14, ARGENT);
    p.rect(3, 15, 4, 15, [120, 124, 140]);
    p.bord();
    p.rect(3, 1, 7, 1, [255, 150, 200]);
    p.rect(4, 2, 4, 8, [255, 120, 186]);
    for (const [x, y, c] of [[1, 14, [255, 150, 40]], [2, 15, [255, 214, 80]], [1, 15, [255, 120, 30]], [0, 15, [255, 186, 60]], [2, 14, [255, 232, 130]]]) p.pt(x, y, c);
  }),
  grappin: icone((p) => {
    p.rond(4.5, 11, 3.8, ARGENT);
    p.bord(0.66);
    for (const [x, y] of [[3, 10], [5, 10], [3, 11], [4, 11], [5, 11], [4, 12]]) p.pt(x, y, ROSE);
    p.ligne(7, 8, 11, 6, [74, 74, 88]);
    p.rect(12, 2, 12, 6, [150, 156, 172]);
    for (const [x, y] of [[12, 0], [12, 1], [11, 1], [10, 2], [13, 1], [14, 2], [10, 3], [14, 3]]) p.pt(x, y, [176, 182, 198]);
  }),
};

// ------------------------------------------------------------------ ce que fait chaque gadget
const DROIT = [-1.5, -0.05];
const posePas = { jambeD: -0.25, jambeG: 0.2 };

function laser(sc) {
  const T = espionne(sc, ESPIONNES.clover, { brasD: DROIT, ...posePas, objetD: { icone: GADGETS.rouge_a_levres, pointe: true } });
  const pr = prise("D", true);
  const bout = T.brasD.p(ajoute(pr.centre, fois(pr.haut, 4.2)));
  const cible = [-3, 20, 82];
  baton(sc, bout, cible, 0.55, [255, 70, 140]);
  boule(sc, cible, 1.6, [255, 255, 255], [255, 120, 180], true);
  joueur(sc, SKINS.steve, { decale: [-3, 0, 84], lacet: Math.PI, penche: -0.18, bras: [-0.4, 0.3] });
  sc.lueur([255, 60, 140], 4, 1.4);
}

function secheCheveux(sc) {
  const T = espionne(sc, ESPIONNES.sam, { brasD: DROIT, ...posePas, objetD: { icone: GADGETS.seche_cheveux } });
  const pr = prise("D");
  const buse = T.brasD.p(ajoute(pr.centre, fois(pr.droite, 4)));
  for (let k = 0; k < 16; k++) {
    const a = hache(k, 1, 4) * Math.PI * 2, r = 1 + 6 * hache(k, 2, 4);
    const debut = 3 + 18 * hache(k, 3, 4), long = 10 + 14 * hache(k, 4, 4);
    const p0 = ajoute(buse, [Math.cos(a) * r * (debut / 30), Math.sin(a) * r * (debut / 30), debut]);
    const p1 = ajoute(buse, [Math.cos(a) * r * ((debut + long) / 30), Math.sin(a) * r * ((debut + long) / 30), debut + long]);
    baton(sc, p0, p1, 0.35, mix([255, 255, 255], [180, 220, 255], hache(k, 5, 4)));
  }
  joueur(sc, SKINS.steve, { decale: [-2, 12, 70], lacet: Math.PI, penche: -1.0, bras: [-2.4, 0.5] });
  sc.lueur([170, 210, 255], 3, 0.9);
}

function lunettes(sc) {
  espionne(sc, ESPIONNES.alex, { lunettes: true, brasD: [-2.5, -0.35], brasG: [0, 0.08] });
  for (const x of [-48, -32, -16, 0, 16]) for (const y of [0, 16, 32]) bloc(sc, x, y, 26);
  joueur(sc, SKINS.efe, { decale: [-2, 0, 64], lacet: Math.PI * 0.85, chaleur: true, devant: 5000, aile: true });
  joueur(sc, SKINS.kai, { decale: [-24, 0, 74], lacet: Math.PI * 1.2, chaleur: true, devant: 5000, aile: true });
}

function parfum(sc) {
  const T = espionne(sc, ESPIONNES.clover, { brasD: [-1.35, -0.05], ...posePas, objetD: { icone: GADGETS.parfum } });
  const pr = prise("D");
  const bec = T.brasD.p(ajoute(pr.centre, fois(pr.haut, 3.5)));
  const tete = [-1, 28, 54];
  for (let k = 0; k < 12; k++) {
    const s = k / 11, p = ajoute(mix(bec, tete, s), [Math.sin(k * 2.1) * 2 * s, Math.cos(k * 1.7) * 1.5 * s, 0]);
    boule(sc, p, 0.6 + 2.2 * s, [196, 150, 232], [246, 226, 255]);
  }
  for (let k = 0; k < 9; k++) {
    const a = k * 0.7;
    boule(sc, ajoute(tete, [Math.cos(a) * 6, Math.sin(a * 1.7) * 3 + 2, Math.sin(a) * 4]), 2.6 + 1.6 * hache(k, 1, 2), [206, 162, 238], [250, 232, 255]);
  }
  joueur(sc, SKINS.steve, { decale: [-1, 0, 58], lacet: Math.PI * 1.05, penche: 0.32, bras: [0.15, 0.1] });
}

function chewingGum(sc) {
  const T = espionne(sc, ESPIONNES.sam, { brasD: [-1.75, -0.05], ...posePas });
  // la trace du chewing-gum lancé, en cloche
  const main = T.brasD.p([FIN.brasD.cx, 12 + TALON, 0]);
  for (let k = 1; k < 7; k++) {
    const s = k / 7;
    boule(sc, ajoute(mix(main, [-2, 22, 44], s), [0, Math.sin(s * Math.PI) * 6, 0]), 0.5 + 0.25 * s, [255, 128, 190], [255, 200, 226]);
  }
  joueur(sc, SKINS.kai, { decale: [-2, 0, 62], lacet: Math.PI * 0.95, bras: [-0.6, 0.7] });
  bulle(sc, [-2, 16.5, 62], 19);
}

function talons(sc) {
  const T = espionne(sc, ESPIONNES.alex, { decale: [0, 22, 0], jambeD: -0.55, jambeG: 0.45, brasD: [0.3, -1.1], brasG: [-0.4, 1.0] });
  for (const nom of ["jambeD", "jambeG"]) {
    for (let k = 0; k < 4; k++) {
      boule(sc, T[nom].p([FIN[nom].cx, -0.4 - k * 1.7, -0.65]), 1.3 - k * 0.22, k < 2 ? [255, 250, 200] : [255, 170, 40], [255, 110, 20], true);
    }
  }
  for (let k = 0; k < 10; k++) {
    const a = (k / 10) * Math.PI * 2;
    boule(sc, [Math.cos(a) * 9, 1.2, Math.sin(a) * 9], 2 + hache(k, 2, 7), [214, 214, 222], [250, 250, 252]);
  }
  sc.lueur([255, 150, 40], 3, 1);
}

function grappin(sc) {
  const T = espionne(sc, ESPIONNES.clover, { decale: [0, 26, 6], bascule: 0.45, brasD: [-2.5, -0.25], brasG: [-2.5, 0.25], jambeD: 0.35, jambeG: 0.15 });
  for (const x of [-16, 0]) for (const y of [0, 16, 32, 48, 64]) bloc(sc, x, y, 40, [150, 150, 156]);
  const boucle = T.corps.p([0, 13.1 + TALON, VENTRE + 0.4]);
  const crochet = [0, 70, 39.6];
  baton(sc, boucle, crochet, 0.35, [60, 60, 74], false);
  pave(sc, [-0.8, 68.6, 37.6], [0.8, 71.4, 39.6], ID, () => ARGENT, 0.4);
  for (const s of [-1, 1]) pave(sc, [s * 1.6 - 0.4, 69.6, 37.8], [s * 1.6 + 0.4, 72.6, 38.6], ID, () => ARGENT, 0.4);
}

function enMain(sc) {
  espionne(sc, ESPIONNES.clover, { brasD: [-0.95, -0.1], brasG: [-0.5, 0.1], tete: [0.1, 0.15], objetD: { icone: GADGETS.seche_cheveux }, objetG: { icone: GADGETS.rouge_a_levres, pointe: true } });
}

// ------------------------------------------------------------------ les planches
const CW = 440, CH = 520, M = 34;
const NOIR = [30, 30, 40, 255], ACCENT = [214, 60, 140, 255];

function planche(vues, colonnes, haut = 0, dessinHaut = null) {
  const lignes = Math.ceil(vues.length / colonnes);
  const W = CW * colonnes, H = haut + (M + CH) * lignes;
  const img = Buffer.alloc(W * H * 4, 255);
  if (dessinHaut) dessinHaut(img, W);
  vues.forEach(([titre, dessin, o], k) => {
    const c = k % colonnes, l = Math.floor(k / colonnes);
    const sc = scene({ w: CW, h: CH, echelle: 11, pied: 490, fond: FOND, ...o });
    dessin(sc);
    for (let y = 0; y < CH; y++) sc.img.copy(img, ((haut + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(img, W, titre, c * CW + 12, haut + l * (M + CH) + 10, 3, NOIR);
  });
  return { img, W, H };
}

/** La moitié de la largeur d'un texte (entière : écrire à une position fractionnaire décale les canaux). */
const demiTexte = (t, taille) => Math.round((t.length * 4 * taille - taille) / 2);

/** Un objet agrandi (pixels nets) sur la planche, sur une petite carte blanche. */
function poserIcone(img, W, ic, x0, y0, k, carte = true) {
  if (carte) for (let y = -10; y < 16 * k + 10; y++) for (let x = -10; x < 16 * k + 10; x++) img.set([248, 248, 252, 255], ((y0 + y) * W + x0 + x) * 4);
  for (let j = 0; j < 16; j++) for (let i = 0; i < 16; i++) {
    const s = (j * 16 + i) * 4;
    if (ic.px[s + 3] < 128) continue;
    for (let y = 0; y < k; y++) for (let x = 0; x < k; x++) img.set([ic.px[s], ic.px[s + 1], ic.px[s + 2], 255], ((y0 + j * k + y) * W + x0 + i * k + x) * 4);
  }
}

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

/** Le haut de la planche des gadgets : les sept objets, leur touche, la barre de choix et les règles. */
function hautGadgets(img, W) {
  rect(img, W, 0, 0, W, HAUT_GADGETS, [236, 236, 242, 255]);
  ecrireTexte(img, W, "GADGETS DES ESPIONNES", 30, 20, 5, NOIR);
  const liste = [
    ["rouge_a_levres", "ROUGE A LEVRES", "CLIC GAUCHE"], ["seche_cheveux", "SECHE CHEVEUX", "CLIC DROIT"],
    ["lunettes", "LUNETTES", "CLIC DROIT"], ["parfum", "PARFUM", "CLIC DROIT"], ["chewing_gum", "CHEWING GUM", "CLIC DROIT"],
    ["talons", "TALONS", "TOUJOURS"], ["grappin", "GRAPPIN", "ACCROUPI"],
  ];
  liste.forEach(([cle, nom, touche], k) => {
    const cx = 130 + k * 250, y0 = 100;
    poserIcone(img, W, GADGETS[cle], cx - 80, y0, 10);
    ecrireTexte(img, W, nom, cx - demiTexte(nom, 3), y0 + 190, 3, NOIR);
    ecrireTexte(img, W, touche, cx - demiTexte(touche, 3), y0 + 215, 3, ACCENT);
  });
  // la barre de choix : le rouge à lèvres (clic gauche), puis les quatre du clic droit, l'un choisi
  const y0 = 420, s = 76;
  ecrireTexte(img, W, "LA BARRE", 30, y0 + 28, 3, NOIR);
  const cases = [["rouge_a_levres", 160], ["seche_cheveux", 290], ["lunettes", 290 + s], ["parfum", 290 + 2 * s], ["chewing_gum", 290 + 3 * s]];
  rect(img, W, 150, y0 - 10, 150 + s + 20, y0 + s + 10, [52, 50, 62, 255]);
  rect(img, W, 280, y0 - 10, 290 + 4 * s + 10, y0 + s + 10, [52, 50, 62, 255]);
  cases.forEach(([cle, x], k) => {
    const choisi = k === 2;
    rect(img, W, x, y0, x + s - 6, y0 + s - 6, choisi ? [255, 255, 255, 255] : [120, 118, 132, 255]);
    rect(img, W, x + 4, y0 + 4, x + s - 10, y0 + s - 10, [84, 82, 96, 255]);
    poserIcone(img, W, GADGETS[cle], x + 11, y0 + 11, 3, false);
  });
  ecrireTexte(img, W, "CLIC GAUCHE", 150, y0 - 34, 2, ACCENT);
  ecrireTexte(img, W, "CLIC DROIT : CELUI QUI EST CHOISI", 280, y0 - 34, 2, ACCENT);
  ecrireTexte(img, W, "ACCROUPI + CLIC DROIT : SUIVANT", 280, y0 + s + 22, 2, ACCENT);
  const regles = [
    "TOUCHE EVEIL : LA TENUE ARRIVE TOUT DE SUITE",
    "EVEIL 3 MIN, PUIS 1 MIN DE REPOS",
    "CHANGER DE GADGET : SANS LIMITE, SANS DELAI",
    "CHAQUE GADGET : 1 A 2 S ENTRE DEUX COUPS",
    "TALONS ET GRAPPIN : TOUJOURS LA",
    "ACCROUPI SEUL : LE GRAPPIN TIRE VERS LE MUR",
  ];
  regles.forEach((t, k) => ecrireTexte(img, W, t, 760, 380 + k * 28, 3, NOIR));
}
const HAUT_GADGETS = 600;

fs.mkdirSync(path.join(DOC, "textures"), { recursive: true });
for (const [cle, ic] of Object.entries(GADGETS)) ecrirePng(path.join(DOC, "textures", cle + ".png"), 16, 16, ic.px);
for (const e of Object.values(ESPIONNES)) ecrirePng(path.join(DOC, "textures", "tenue_" + e.couleur + ".png"), 64, 64, TENUES[e.couleur].px);

// ------------------------------------------------------------------ les textures du mod (mod-cabane, assets/cabane)
const MOD = path.join(DOC, "..", "..", "mod-cabane", "src", "main", "resources", "assets", "cabane");
fs.mkdirSync(path.join(MOD, "textures", "espionnes"), { recursive: true });
// les gadgets tenus en main (les talons et le grappin ne se tiennent pas)
for (const cle of ["rouge_a_levres", "seche_cheveux", "lunettes", "parfum", "chewing_gum"]) {
  ecrirePng(path.join(MOD, "textures", "item", "espionne_" + cle + ".png"), 16, 16, GADGETS[cle].px);
  fs.writeFileSync(path.join(MOD, "models", "item", "espionne_" + cle + ".json"),
    JSON.stringify({ parent: "minecraft:item/generated", textures: { layer0: "cabane:item/espionne_" + cle } }, null, 2) + "\n");
}
for (const e of Object.values(ESPIONNES)) ecrirePng(path.join(MOD, "textures", "espionnes", "tenue_" + e.couleur + ".png"), 64, 64, TENUES[e.couleur].px);
// les accessoires en 3D (64 × 32) : le talon en argent (et sa tuyère plus sombre), les verres des lunettes, leur monture rose
{
  const px = Buffer.alloc(64 * 32 * 4);
  const pose = (x, y, c) => px.set([...c.map(Math.round), 255], (y * 64 + x) * 4);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) pose(x, y, clair(y < 8 ? ARGENT : [120, 124, 140], 0.92 + 0.12 * hache(x, y, 4)));
  for (let y = 0; y < 16; y++) for (let x = 16; x < 32; x++) pose(x, y, mix([236, 40, 40], [255, 150, 40], Math.min(1, y / 3)));
  for (let y = 0; y < 16; y++) for (let x = 32; x < 64; x++) pose(x, y, clair(ROSE, 0.94 + 0.1 * hache(x, y, 6)));
  ecrirePng(path.join(MOD, "textures", "espionnes", "accessoires.png"), 64, 32, px);
}

const tenues = planche([
  ["SAM", (sc) => espionne(sc, ESPIONNES.sam), { lacet: -28 }],
  ["CLOVER", (sc) => espionne(sc, ESPIONNES.clover), { lacet: -28 }],
  ["ALEX", (sc) => espionne(sc, ESPIONNES.alex), { lacet: -28 }],
  ["LES TROIS", (sc) => {
    espionne(sc, ESPIONNES.sam, { decale: [-15, 0, -4], lacet: 0.25 });
    espionne(sc, ESPIONNES.clover, { decale: [0, 0, 2] });
    espionne(sc, ESPIONNES.alex, { decale: [15, 0, -4], lacet: -0.25 });
  }, { lacet: -18, echelle: 6.8, pied: 440 }],
  ["AVANT / APRES", (sc) => {
    joueur(sc, SKINS.kai, { decale: [-10, 0, 0] });
    espionne(sc, ESPIONNES.clover, { decale: [10, 0, 0] });
  }, { lacet: -12, echelle: 8.4, pied: 460 }],
  ["DE DOS", (sc) => espionne(sc, ESPIONNES.clover), { lacet: 152 }],
  ["PROFIL", (sc) => espionne(sc, ESPIONNES.alex, posePas), { lacet: -90 }],
  ["TETE ET CEINTURE", (sc) => espionne(sc, ESPIONNES.sam), { lacet: -22, echelle: 19, pied: 330, centre: [0, 20, 0], tangage: 6 }],
], 4);
ecrirePng(path.join(DOC, "planche-tenues.png"), tenues.W, tenues.H, tenues.img);

const ACTION = { echelle: 4.4, pied: 330, tangage: 10 };
const gadgets = planche([
  ["CLIC GAUCHE : LASER", laser, { ...ACTION, lacet: 64, centre: [0, 14, 40] }],
  ["SECHE CHEVEUX", secheCheveux, { ...ACTION, lacet: 64, centre: [0, 14, 36] }],
  ["LUNETTES INFRAROUGES", lunettes, { ...ACTION, lacet: 152, centre: [-10, 18, 30], echelle: 4.6, tangage: 14 }],
  ["PARFUM", parfum, { ...ACTION, lacet: 62, centre: [0, 14, 28], echelle: 5.2 }],
  ["CHEWING GUM", chewingGum, { ...ACTION, lacet: 60, centre: [0, 14, 30], echelle: 5.0 }],
  ["TALONS : DOUBLE SAUT", talons, { lacet: -30, echelle: 6.4, pied: 470, tangage: 8 }],
  ["ACCROUPI : GRAPPIN", grappin, { lacet: 122, echelle: 4.6, pied: 470, tangage: 10, centre: [-4, 0, 24] }],
  ["EN MAIN", enMain, { lacet: -24, echelle: 13, pied: 330, centre: [0, 16, 0], tangage: 8 }],
], 4, HAUT_GADGETS, hautGadgets);
ecrirePng(path.join(DOC, "planche-gadgets.png"), gadgets.W, gadgets.H, gadgets.img);
console.log("ok", tenues.W, tenues.H, gadgets.W, gadgets.H);
