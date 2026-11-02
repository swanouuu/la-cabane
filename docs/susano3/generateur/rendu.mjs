// Maquettes du Susano niveau 3 (susano3) : le Susano du niveau 2 (les os) se couvre de fibres puis de chair, comme dans
// l'anime, puis une armure se pose pièce par pièce, et il devient le haut du corps d'un chevalier : casque, cuirasse,
// épaulières, cape, une grande épée et un bouclier (et un arc pour le clic droit, avec des flammes noires d'Amaterasu).
// Le casque change selon la couleur du Susano (violet : Sasuke, rouge : Itachi, bleu : Madara, noir : un heaume).
// Rendu translucide comme en jeu : du chakra qu'on voit à travers, des arêtes, une lueur.
// Coordonnées en blocs : les pieds du joueur à l'origine, il regarde vers +z, sa gauche vers +x.
// node rendu.mjs        → ../planche-susano3.png
// node rendu.mjs anim   → images/NNN.png (pour le GIF de la transformation)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, PARTIES, texels, hache, ecrireTexte, POLICE } from "../../hachibi2/generateur/moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const SASUKE = lire(path.join(ICI, "..", "..", "marque-forme2", "generateur", "skins", "sasuke.png"));
const CIBLE = lire(path.join(ICI, "..", "..", "hachibi2", "generateur", "skins", "steve.png"));
Object.assign(POLICE, { 0: "111101101101111", 7: "111001010010010", 8: "111101111101111", 9: "111101111001110" });

const DEG = Math.PI / 180;
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const len = (a) => Math.hypot(a[0], a[1], a[2]);
const norm = (a) => mul(a, 1 / (len(a) || 1));
const lerp = (a, b, t) => add(a, mul(sub(b, a), t));
const clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
const lisse = (t) => { t = clamp(t); return t * t * (3 - 2 * t); };
const rotY = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];
const rgb = (c) => [(c >> 16) & 255, (c >> 8) & 255, c & 255];
const mix = (a, b, k) => a.map((v, i) => v + (b[i] - v) * k);

/** Les couleurs du Susano, comme dans le mod : le corps, et le contour. */
const COULEURS = {
  violet: { corps: rgb(0x6a2cff), contour: rgb(0xe0b8ff) },
  rouge: { corps: rgb(0xe8401c), contour: rgb(0xffb070) },
  bleu: { corps: rgb(0x2a5cff), contour: rgb(0x9cc8ff) },
  noir: { corps: rgb(0x0a0a10), contour: rgb(0xffffff) },
};
const JAUNE = [255, 230, 40];

// ================================================================== l'écran : perspective, profondeur, lueur

function ecran({ w, h, cible = [0, 4.6, 0], lacet = -32, tangage = 8, distance = 15.2, fov = 44, ciel = [[40, 44, 66], [96, 92, 120]] }) {
  const img = Buffer.alloc(w * h * 4), z = new Float32Array(w * h).fill(Infinity), lueur = new Float32Array(w * h * 3);
  for (let y = 0; y < h; y++) {
    const c = mix(ciel[0], ciel[1], y / h);
    for (let x = 0; x < w; x++) img.set([...c.map(Math.round), 255], (y * w + x) * 4);
  }
  const a = lacet * DEG, b = tangage * DEG;
  const oeil = add(cible, mul([Math.sin(a) * Math.cos(b), Math.sin(b), Math.cos(a) * Math.cos(b)], distance));
  const f = norm(sub(cible, oeil)), r = norm(cross(f, [0, 1, 0])), u = cross(r, f);
  const F = w / 2 / Math.tan((fov * DEG) / 2);
  const proj = (p) => {
    const v = sub(p, oeil), zc = dot(v, f);
    if (zc < 0.2) return null;
    return { x: w / 2 + (dot(v, r) / zc) * F, y: h / 2 - (dot(v, u) / zc) * F, iz: 1 / zc, zc };
  };
  return { img, z, lueur, w, h, proj, oeil, f, F };
}

/** Un triangle à l'écran ; plot(i, profondeur) pour chaque pixel couvert. */
function triangle(E, p0, p1, p2, plot) {
  const aire = (p1.x - p0.x) * (p2.y - p0.y) - (p1.y - p0.y) * (p2.x - p0.x);
  if (Math.abs(aire) < 1e-9) return;
  const x0 = Math.max(0, Math.floor(Math.min(p0.x, p1.x, p2.x))), x1 = Math.min(E.w - 1, Math.ceil(Math.max(p0.x, p1.x, p2.x)));
  const y0 = Math.max(0, Math.floor(Math.min(p0.y, p1.y, p2.y))), y1 = Math.min(E.h - 1, Math.ceil(Math.max(p0.y, p1.y, p2.y)));
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
    const px = x + 0.5, py = y + 0.5;
    const w0 = ((p2.x - p1.x) * (py - p1.y) - (p2.y - p1.y) * (px - p1.x)) / aire;
    const w1 = ((p0.x - p2.x) * (py - p2.y) - (p0.y - p2.y) * (px - p2.x)) / aire;
    const w2 = 1 - w0 - w1;
    if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
    plot(y * E.w + x, 1 / (w0 * p0.iz + w1 * p1.iz + w2 * p2.iz));
  }
}

function polygone(E, pts, plot) {
  const p = pts.map(E.proj);
  if (p.some((q) => !q)) return;
  for (let k = 1; k + 1 < p.length; k++) triangle(E, p[0], p[k], p[k + 1], plot);
}

const opaque = (E, pts, c) => polygone(E, pts, (i, zz) => {
  if (zz < E.z[i]) { E.z[i] = zz; E.img[i * 4] = c[0]; E.img[i * 4 + 1] = c[1]; E.img[i * 4 + 2] = c[2]; }
});

function melange(E, i, c, a, g) {
  for (let k = 0; k < 3; k++) {
    E.img[i * 4 + k] = Math.max(0, Math.min(255, E.img[i * 4 + k] * (1 - a) + c[k] * a));
    if (g) E.lueur[i * 3 + k] += c[k] * a * g;
  }
}

function ligne(E, A, B, c, a, g = 0) {
  const pa = E.proj(A), pb = E.proj(B);
  if (!pa || !pb) return;
  const n = Math.ceil(Math.max(Math.abs(pb.x - pa.x), Math.abs(pb.y - pa.y))) + 1;
  for (let k = 0; k <= n; k++) {
    const t = k / n, x = Math.round(pa.x + (pb.x - pa.x) * t), y = Math.round(pa.y + (pb.y - pa.y) * t);
    if (x < 0 || y < 0 || x >= E.w || y >= E.h) continue;
    const i = y * E.w + x;
    if (1 / (pa.iz + (pb.iz - pa.iz) * t) <= E.z[i] + 0.03) melange(E, i, c, a, g);
  }
}

/** La lueur : ce qui brille déborde autour (un flou), et s'ajoute à l'image. */
function briller(E, rayon = 7, force = 1) {
  let m = E.lueur;
  for (let passe = 0; passe < 3; passe++) {
    const t = new Float32Array(m.length);
    for (let y = 0; y < E.h; y++) for (let k = 0; k < 3; k++) {
      let acc = 0;
      for (let x = -rayon; x < E.w + rayon; x++) {
        if (x + rayon < E.w) acc += m[(y * E.w + x + rayon) * 3 + k];
        if (x - rayon - 1 >= 0) acc -= m[(y * E.w + x - rayon - 1) * 3 + k];
        if (x >= 0 && x < E.w) t[(y * E.w + x) * 3 + k] = acc / (2 * rayon + 1);
      }
    }
    const t2 = new Float32Array(m.length);
    for (let x = 0; x < E.w; x++) for (let k = 0; k < 3; k++) {
      let acc = 0;
      for (let y = -rayon; y < E.h + rayon; y++) {
        if (y + rayon < E.h) acc += t[((y + rayon) * E.w + x) * 3 + k];
        if (y - rayon - 1 >= 0) acc -= t[((y - rayon - 1) * E.w + x) * 3 + k];
        if (y >= 0 && y < E.h) t2[(y * E.w + x) * 3 + k] = acc / (2 * rayon + 1);
      }
    }
    m = t2;
  }
  for (let i = 0; i < E.w * E.h; i++) for (let k = 0; k < 3; k++) E.img[i * 4 + k] = Math.min(255, E.img[i * 4 + k] + m[i * 3 + k] * force);
}

// ================================================================== le décor et les joueurs

function sol(E, rayon = 13) {
  for (let x = -rayon; x < rayon; x++) for (let z = -rayon; z < rayon; z++) {
    if (Math.hypot(x + 0.5, z + 0.5) > rayon) continue;
    for (let j = 0; j < 2; j++) for (let i = 0; i < 2; i++) {
      const h = hache(x * 2 + i, z * 2 + j, 3), a = x + i / 2, b = z + j / 2;
      const c = (h < 0.15 ? [62, 104, 40] : [76, 124, 48]).map((v) => v * (0.85 + 0.15 * hache(x * 2 + i, z * 2 + j, 9)) * (1 - 0.35 * clamp(1 - Math.hypot(a, b) / 5)));
      opaque(E, [[a, 0, b], [a + 0.5, 0, b], [a + 0.5, 0, b + 0.5], [a, 0, b + 0.5]], c);
    }
  }
}

const rotX = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
const rotZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];

/**
 * Un joueur (sa peau), à cette place, tourné de ce lacet ; bras = [tangage droit, tangage gauche] en degrés.
 * rire (0 à 1) : il rit comme un fou, la tête renversée, les bras écartés et un peu pliés, et il tressaute (au temps t).
 */
function joueur(E, peau, pos, lacet = 0, bras = [0, 0], teinte = 1, rire = 0, t = 0) {
  const secousse = Math.sin(t * 26), tete = (-40 + 7 * secousse) * rire, ecart = (70 + 5 * secousse) * rire;
  const k = 0.9375 / 16;
  for (const partie of PARTIES(false)) for (const couche of [2, 3]) {
    for (const t of texels(partie[1], partie[couche], couche === 3 ? partie[4] : 0, 1)) {
      const i = (t.ty * peau.w + t.tx) * 4;
      if (peau.px[i + 3] < 128) continue;
      const nom = partie[0];
      const piv = nom === "brasD" ? [-5, 22, 0] : nom === "brasG" ? [5, 22, 0] : nom === "tete" ? [0, 24, 0] : null;
      const ang = (nom === "brasD" ? bras[0] : nom === "brasG" ? bras[1] : 0) * (1 - rire) + (nom.startsWith("bras") ? -28 * rire : 0);
      const roule = nom === "brasD" ? -ecart : nom === "brasG" ? ecart : 0;
      const place = (v) => {
        let p = v;
        if (piv) {
          const d = sub(p, piv);
          p = add(piv, nom === "tete" ? rotX(d, tete * DEG) : rotX(rotZ(d, roule * DEG), ang * DEG));
        }
        // le corps se cambre un peu en arrière, et tressaute en riant
        if (rire > 0 && !nom.startsWith("jambe")) p = add([0, 12, 0], rotX(sub(p, [0, 12, 0]), (-8 + 2 * secousse) * rire * DEG));
        return add(rotY(mul(p, k), lacet * DEG), add(pos, [0, Math.abs(secousse) * 0.04 * rire, 0]));
      };
      const n = rotY(t.N, lacet * DEG), l = 0.62 + 0.38 * Math.max(0, dot(n, norm([-0.4, 0.7, 0.6])));
      opaque(E, t.coins.map(place), [peau.px[i], peau.px[i + 1], peau.px[i + 2]].map((v) => v * l * teinte));
    }
  }
}

// ================================================================== les boîtes de chakra

/**
 * Une boîte : son centre, ses axes [côté, haut, avant], ses demi-tailles, son genre (os, fibre, chair, armure, lame,
 * sombre, oeil, cape, flamme), et combien elle est apparue (0 à 1 : elle grandit et s'éclaire).
 */
const boite = (L, c, ax, h, genre, k = 1) => { if (k > 0.02) L.push({ c, ax, h: h.map((v) => v * Math.min(1, 0.3 + 0.7 * k)), genre, k }); };
const DROIT = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];

/** Les axes d'une boîte allongée de a vers b (haut = la direction), tournée selon l'indice. */
function axesLe(d, indice = [0, 0, 1]) {
  const Y = norm(d);
  let X = cross(Y, indice);
  if (len(X) < 0.2) X = cross(Y, [1, 0, 0]);
  X = norm(X);
  return [X, Y, cross(X, Y)];
}
/** Une boîte tendue de a à b (largeur, épaisseur), qui pousse de a vers b. */
function seg(L, a, b, larg, ep, genre, k = 1, pousse = 1, indice) {
  const d = sub(b, a), l = len(d) * pousse;
  if (l < 0.02) return;
  boite(L, add(a, mul(norm(d), l / 2)), axesLe(d, indice), [larg / 2, l / 2, ep / 2], genre, k);
}

function coins(b) {
  const [X, Y, Z] = b.ax, [hx, hy, hz] = b.h, r = [];
  for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1]) r.push(add(b.c, add(add(mul(X, sx * hx), mul(Y, sy * hy)), mul(Z, sz * hz))));
  return r; // index = (sx+1)/2*4 + (sy+1)/2*2 + (sz+1)/2
}
const FACES = [[[1, 3, 7, 5], 0.68], [[0, 4, 6, 2], 0.68], [[2, 6, 7, 3], 1], [[0, 1, 5, 4], 0.55], [[4, 5, 7, 6], 0.8], [[0, 2, 3, 1], 0.8]];
const ARETES = [[0, 1], [2, 3], [4, 5], [6, 7], [0, 2], [1, 3], [4, 6], [5, 7], [0, 4], [1, 5], [2, 6], [3, 7]];

/** Le style de chaque genre : la couleur du remplissage, son opacité, celle des arêtes, et ce qui brille. */
function style(genre, C, niveau3) {
  const noir = C.corps[0] < 30;
  const corps = noir ? [16, 14, 22] : C.corps, clair = mix(corps, C.contour, 0.45);
  const trait = noir ? [235, 235, 245] : niveau3 ? mix(C.contour, corps, 0.25) : mul(corps, 0.42);
  switch (genre) {
    case "os": return { c: corps, a: noir ? 0.8 : 0.5, t: niveau3 ? null : trait, at: 0.7, g: 0.12 };
    case "fibre": return { c: mix(C.contour, [255, 255, 255], 0.35), a: 0.95, t: null, g: 0.55 };
    case "chair": return { c: mix(corps, C.contour, 0.15), a: noir ? 0.82 : 0.45, t: niveau3 ? null : trait, at: 0.25, g: 0.05 };
    case "armure": return { c: noir ? [22, 20, 30] : clair, a: noir ? 0.94 : 0.86, t: noir ? [230, 230, 240] : mix(C.contour, [255, 255, 255], 0.35), at: noir ? 0.6 : 0.3, g: 0.08 };
    case "lame": return { c: mix(mix(C.corps, C.contour, 0.6), [255, 255, 255], 0.25), a: 0.7, t: mix(C.contour, [255, 255, 255], 0.7), at: 0.6, g: 0.22 };
    case "cape": return { c: mul(corps, noir ? 1 : 0.7), a: noir ? 0.85 : 0.6, t: trait, at: 0.18, g: 0.05 };
    case "flamme": return { c: mix(corps, C.contour, 0.3), a: 0.25, t: null, g: 0.35 };
    case "sombre": return { c: noir ? [2, 2, 4] : mul(corps, 0.18), a: 0.95, t: null, g: 0 };
    case "dent": return { c: mix(C.contour, [255, 255, 255], 0.6), a: 0.9, t: null, g: 0.4 };
    case "oeil": return { c: JAUNE, a: 1, t: null, g: 2.4 };
    case "flammeLame": return { c: noir ? [24, 20, 30] : mix(corps, C.contour, 0.4), a: noir ? 0.92 : 0.82, t: null, g: 0.45 };
    case "casque": return { c: noir ? [22, 20, 30] : clair, a: noir ? 0.94 : 0.86, t: null, g: 0.08 };
    case "visage": return { c: noir ? [26, 24, 34] : mix(corps, C.contour, 0.38), a: noir ? 0.95 : 0.88, t: null, g: 0.06 };
    case "couronne": return { c: noir ? [20, 18, 26] : mix(corps, C.contour, 0.3), a: noir ? 0.92 : 0.72, t: noir ? [235, 235, 245] : mix(C.contour, [255, 255, 255], 0.35), at: 0.35, g: 0.25 };
    default: return { c: corps, a: 0.5, t: trait, at: 0.8, g: 0.2 };
  }
}

/** Dessine toutes les boîtes : les faces de la plus loin à la plus proche, puis les arêtes, puis la lueur. */
function dessinerChakra(E, L, C, niveau3 = 1) {
  const faces = [];
  for (const b of L) {
    const s = style(b.genre, C, niveau3 > 0.5);
    if (b.trait) continue;
    if (b.poly) {
      const n = norm(cross(sub(b.poly[1], b.poly[0]), sub(b.poly[2], b.poly[0])));
      const ombre = 0.62 + 0.38 * Math.abs(dot(n, norm([-0.4, 0.7, 0.6])));
      const centre = mul(b.poly.reduce(add, [0, 0, 0]), 1 / b.poly.length);
      faces.push({ pts: b.poly, c: mul(s.c, ombre).map((v) => Math.min(255, v)), a: s.a * Math.min(1, b.k * 1.4), g: s.g, prof: dot(sub(centre, E.oeil), E.f) });
      continue;
    }
    const p = coins(b);
    for (const [f, ombre] of FACES) {
      const pts = f.map((i) => p[i]);
      const centre = mul(pts.reduce(add, [0, 0, 0]), 0.25);
      faces.push({ pts, c: mul(s.c, ombre).map((v) => Math.min(255, v)), a: s.a * Math.min(1, b.k * 1.4), g: s.g, prof: dot(sub(centre, E.oeil), E.f) });
    }
  }
  faces.sort((a, b) => b.prof - a.prof);
  for (const f of faces) polygone(E, f.pts, (i, zz) => { if (zz < E.z[i]) melange(E, i, f.c, f.a, f.g * 0.6); });
  for (const b of L) {
    const s = style(b.genre, C, niveau3 > 0.5);
    if (b.trait) {
      ligne(E, b.trait[0], b.trait[1], b.couleur ?? s.t ?? s.c, (b.alpha ?? 0.9) * Math.min(1, b.k * 1.3), 0.8);
      continue;
    }
    if (!s.t || b.poly) continue;
    const p = coins(b);
    for (const [i, j] of ARETES) ligne(E, p[i], p[j], s.t, s.at * Math.min(1, b.k * 1.3), 0.35);
  }
}

// ================================================================== le Susano niveau 3

/** Les poses : coude et poignet de chaque bras (en blocs), et ce que tient chaque main. */
const EPAULE_D = [-2.15, 4.45, -0.2], EPAULE_G = [2.15, 4.45, -0.2];
const POSES = {
  repos: { D: { E: [-3.1, 2.9, 0.6], W: [-3.4, 3.3, 1.9], lame: norm([-0.22, 0.96, 0.18]) },
    G: { E: [2.9, 2.85, 0.35], W: [1.55, 2.95, 1.85], bouclier: true } },
  coup1: { D: { E: [-2.6, 3.9, 1.6], W: [-1.1, 3.6, 2.6], lame: norm([1, -0.06, 0.32]) },
    G: { E: [2.95, 3.2, -0.6], W: [2.7, 2.6, 0.9], bouclier: true }, trainee: [-100, 25, 1] },
  coup2: { D: { E: [-1.6, 3.75, 1.45], W: [-3.0, 3.5, 2.5], lame: norm([-1, 0.08, 0.38]) },
    G: { E: [2.95, 3.2, -0.6], W: [2.7, 2.6, 0.9], bouclier: true }, trainee: [70, -35, -1] },
  arc: { D: { E: [-2.5, 4.9, -1.3], W: [-0.55, 4.75, 0.1], tire: true },
    G: { E: [2.25, 4.55, 1.9], W: [0.75, 4.6, 3.7], arc: true } },
};

/**
 * Le Susano à un moment de sa transformation. e = { taille, os, fibres, chair, armure, casque, armes, cape } de 0 à 1.
 * Tout est construit à la taille finale puis réduit autour du joueur (taille 0 : celle du niveau 2).
 */
function susano(e, pose, couleur, t = 0) {
  const L = [];
  const P = POSES[pose];
  const bras = (cote) => {
    const S = cote < 0 ? EPAULE_D : EPAULE_G, b = cote < 0 ? P.D : P.G;
    return { S, E: b.E, W: b.W, b };
  };
  // ---- les os (le Susano du niveau 2)
  const os = e.os * (1 - e.armure);
  if (os > 0) {
    for (let y = 1.0; y < 5.1; y += 0.38) boite(L, [0, y, -1.25], DROIT, [0.17, 0.13, 0.17], "os", os);
    for (let n = 0; n < 6; n++) {
      const y = 2.0 + n * 0.47, r = 1.45 + (n >= 1 && n <= 4 ? 0.14 : 0);
      for (const cote of [-1, 1]) {
        let prec = null;
        for (let i = 0; i <= 4; i++) {
          const phi = (168 + (38 - 168) * (i / 4)) * DEG;
          const p = [cote * Math.sin(phi) * r, y - 0.2 * (1 - phi / Math.PI), Math.cos(phi) * r - 0.15];
          if (prec) seg(L, prec, p, 0.38, 0.24, "os", os, 1, [0, 1, 0]);
          prec = p;
        }
      }
    }
    for (const cote of [-1, 1]) {
      const { S, E: C, W } = bras(cote);
      seg(L, [0, 4.75, -0.7], S, 0.3, 0.26, "os", os);
      boite(L, S, DROIT, [0.32, 0.32, 0.32], "os", os);
      seg(L, S, C, 0.36, 0.36, "os", os);
      boite(L, C, DROIT, [0.26, 0.26, 0.26], "os", os);
      seg(L, C, W, 0.3, 0.3, "os", os);
    }
    // le crâne du niveau 2, tant que la chair ne l'a pas couvert
    const crane = os * (1 - e.chair * 0.6);
    const H = [0, 5.75, -0.2];
    boite(L, H, DROIT, [0.7, 0.6, 0.66], "os", crane);
    boite(L, add(H, [0, -0.72, 0.18]), DROIT, [0.5, 0.16, 0.5], "os", crane);
    for (const s of [-1, 1]) {
      boite(L, add(H, [s * 0.32, -0.02, 0.62]), DROIT, [0.22, 0.18, 0.06], e.chair > 0.5 ? "sombre" : "oeil", crane);
    }
  }
  // ---- la chair : torse, épaules, bras, mains, cou, tête (les fibres viennent dessus d'abord)
  const muscles = [];
  const muscle = (c, ax, h) => muscles.push({ c, ax, h });
  muscle([0, 3.75, -0.1], DROIT, [1.75, 0.95, 1.1]);
  muscle([0, 2.45, -0.12], DROIT, [1.05, 0.7, 0.85]);
  muscle([0, 1.55, -0.12], DROIT, [1.2, 0.35, 0.88]);
  muscle([0, 4.95, -0.3], DROIT, [0.45, 0.35, 0.45]);
  for (const cote of [-1, 1]) {
    const { S, E: C, W } = bras(cote);
    muscle(add(S, [cote * 0.08, 0.05, 0]), DROIT, [0.62, 0.58, 0.62]);
    muscle(lerp(S, C, 0.5), axesLe(sub(C, S)), [0.42, len(sub(C, S)) / 2 + 0.2, 0.42]);
    muscle(lerp(C, W, 0.5), axesLe(sub(W, C)), [0.38, len(sub(W, C)) / 2 + 0.12, 0.38]);
    muscle(add(W, mul(norm(sub(W, C)), 0.32)), axesLe(sub(W, C)), [0.36, 0.36, 0.2]);
  }
  if (e.casque < 0.6) muscle([0, 5.85, -0.15], DROIT, [0.62, 0.68, 0.62]);
  muscles.forEach((m, n) => {
    if (e.chair > 0) boite(L, m.c, m.ax, m.h, "chair", e.chair);
    if (e.fibres <= 0 || e.chair >= 0.98) return;
    // les fibres : des brins qui courent le long du muscle, de bas en haut, chacun à son tour
    const [X, Y, Z] = m.ax, [hx, hy, hz] = m.h;
    const brins = 10;
    for (let k = 0; k < brins; k++) {
      const a = (k / brins) * Math.PI * 2 + n;
      const ox = Math.cos(a) * hx * 1.04, oz = Math.sin(a) * hz * 1.04;
      const retard = hache(n, k, 5) * 0.45, pousse = lisse((e.fibres - retard) / 0.5);
      if (pousse <= 0) continue;
      const bas = add(add(m.c, add(mul(X, ox), mul(Z, oz))), mul(Y, -hy * 1.02));
      const haut = add(bas, mul(Y, 2 * hy * 1.02 * pousse));
      const ondule = mul(add(mul(X, -Math.sin(a)), mul(Z, Math.cos(a))), 0.08 * Math.sin(k * 2.3 + n));
      seg(L, add(bas, ondule), add(haut, mul(ondule, -1)), 0.11, 0.07, "fibre", 1 - e.chair * 0.9, 1, Y);
    }
  });
  // la tête de chair (avant le casque) : des yeux qui brillent, une bouche
  if (e.chair > 0.3 && e.casque < 0.95) {
    const k = e.chair * (1 - e.casque);
    for (const s of [-1, 1]) boite(L, [s * 0.32, 6.0, 0.62], DROIT, [0.17, 0.09, 0.05], "oeil", k);
    boite(L, [0, 5.4, 0.62], DROIT, [0.42, 0.06, 0.05], "sombre", k);
  }
  // ---- l'armure, pièce par pièce
  const pieces = [];
  const piece = (f) => pieces.push(f);
  piece((k) => {
    // la cuirasse : large en haut, plus étroite en bas (en V), avec une arête au milieu
    boite(L, [0, 4.15, 1.0], DROIT, [1.8, 0.55, 0.16], "armure", k);
    boite(L, [0, 3.35, 0.95], DROIT, [1.3, 0.3, 0.16], "armure", k);
    boite(L, [0, 3.8, 1.18], DROIT, [0.09, 0.85, 0.06], "lame", k);
  });
  piece((k) => { boite(L, [0, 4.1, -1.25], DROIT, [1.8, 0.6, 0.16], "armure", k); boite(L, [0, 3.35, -1.2], DROIT, [1.3, 0.3, 0.16], "armure", k); });
  for (const cote of [-1, 1]) piece((k) => {
    const S = cote < 0 ? EPAULE_D : EPAULE_G;
    [[0.42, 0.85, 0.9, 25], [0.05, 0.75, 0.8, 42]].forEach(([dy, hx, hz, incl], i) => {
      const a = cote * incl * DEG, ax = [[Math.cos(a), -Math.sin(a), 0], [Math.sin(a), Math.cos(a), 0], [0, 0, 1]];
      boite(L, add(S, [cote * (0.15 + i * 0.25), dy, 0]), ax, [hx, 0.17, hz], "armure", k);
    });
    // une pointe sur l'épaulière
    seg(L, add(S, [cote * 0.2, 0.6, 0]), add(S, [cote * 0.6, 1.35, -0.2]), 0.2, 0.2, "armure", k);
  });
  piece((k) => {
    for (let a = 0; a < 360; a += 60) {
      const r = a * DEG;
      boite(L, [Math.sin(r) * 0.58, 4.95, -0.3 + Math.cos(r) * 0.58], [[Math.cos(r), 0, -Math.sin(r)], [0, 1, 0], [Math.sin(r), 0, Math.cos(r)]], [0.33, 0.26, 0.08], "armure", k);
    }
  });
  piece((k) => { boite(L, [0, 2.0, -0.12], DROIT, [1.15, 0.16, 0.9], "armure", k); boite(L, [0, 2.0, 0.8], DROIT, [0.24, 0.22, 0.06], "lame", k); });
  for (const cote of [-1, 1]) piece((k) => {
    const { S, E: C, W } = bras(cote);
    boite(L, lerp(C, W, 0.55), axesLe(sub(W, C)), [0.46, len(sub(W, C)) * 0.36, 0.46], "armure", k);
    boite(L, add(W, mul(norm(sub(W, C)), 0.36)), axesLe(sub(W, C)), [0.4, 0.38, 0.24], "armure", k);
    boite(L, lerp(S, C, 0.55), axesLe(sub(C, S)), [0.5, len(sub(C, S)) * 0.3, 0.5], "armure", k);
  });
  piece((k) => {
    for (let a = -120; a <= 120; a += 60) {
      const r = a * DEG, c = [Math.sin(r) * 1.02, 1.3, -0.12 + Math.cos(r) * 0.86];
      const n = [Math.sin(r), 0, Math.cos(r)], incl = -12 * DEG;
      const Y = norm(add([0, Math.cos(incl), 0], mul(n, Math.sin(-incl))));
      boite(L, c, [[Math.cos(r), 0, -Math.sin(r)], Y, cross([Math.cos(r), 0, -Math.sin(r)], Y)], [0.42, 0.48, 0.08], "armure", k);
    }
  });
  pieces.forEach((f, i) => f(lisse(e.armure * (pieces.length + 1) - i)));
  // ---- la cape, déchirée, qui part des épaules et ondule
  if (e.cape > 0) {
    for (let i = 0; i < 9; i++) {
      const x = -1.8 + i * 0.45, bas = 0.15 + hache(i, 3, 7) * 1.1;
      let prec = [x * 1.05, 4.7, -1.45];
      for (let j = 1; j <= 6; j++) {
        const y = 4.7 - (4.7 - bas) * (j / 6) * e.cape;
        const p = [x * (1.05 + j * 0.06), y, -1.45 - j * 0.16 - 0.25 * Math.sin(j * 0.9 + i * 1.3 + t * 3)];
        seg(L, prec, p, 0.47, 0.08, "cape", e.cape, 1, [0, 0, 1]);
        prec = p;
      }
    }
  }
  // ---- le bas du corps : des flammes de chakra qui montent du sol jusqu'à la taille
  if (e.chair > 0.2) {
    for (let i = 0; i < 16; i++) {
      const a = (i / 16) * Math.PI * 2, r = 1.0 + 0.18 * Math.sin(i * 1.7);
      const h = 0.9 + 0.6 * hache(i, 1, 2) + 0.25 * Math.sin(t * 6 + i);
      seg(L, [Math.sin(a) * r, 0.05, Math.cos(a) * r - 0.12], [Math.sin(a) * r * 0.85, h, Math.cos(a) * r * 0.85 - 0.12], 0.38, 0.38, "flamme", e.chair);
    }
  }
  // ---- le casque
  if (e.casque > 0) casque(L, couleur, lisse(e.casque));
  // ---- les armes
  if (e.armes > 0) armes(L, P, e.armes, t);
  return L;
}

/** Un ruban plat le long de pts (demi-largeur larg(i)), tourné face à l'indice, et ses deux bords en traits. */
function ruban(L, pts, larg, indice, genre, k, bords = true) {
  const n = pts.length, g = [], d = [];
  for (let i = 0; i < n; i++) {
    const t = norm(sub(pts[Math.min(n - 1, i + 1)], pts[Math.max(0, i - 1)])), c = norm(cross(t, indice)), w = larg(i);
    g.push(sub(pts[i], mul(c, w)));
    d.push(add(pts[i], mul(c, w)));
  }
  for (let i = 0; i + 1 < n; i++) {
    L.push({ poly: [g[i], d[i], d[i + 1], g[i + 1]], genre, k });
    if (bords) {
      L.push({ trait: [g[i], g[i + 1]], genre, k });
      L.push({ trait: [d[i], d[i + 1]], genre, k });
    }
  }
}

/**
 * Le heaume de chevalier, en blocs comme le reste du Susano : une calotte arrondie par tranches (chaque tranche est une
 * croix de deux pavés, ce qui arrondit les coins), un bandeau de renfort, une crête au milieu, une visière en pointe
 * (deux plaques qui se rejoignent sur une arête) percée d'une fente où brillent les yeux et de trous pour respirer, une
 * gorgerette sous le menton, et un panache de flammes de la couleur du Susano qui part en arrière. Chaque couleur a sa
 * touche : rouge, une visière plus longue, en bec ; bleu, deux cornes ; noir, des traits blancs.
 */
function casque(L, couleur, k) {
  const H = [0, 6.0, -0.1], S = 1.2;
  const tourneY = (a) => [[Math.cos(a), 0, -Math.sin(a)], [0, 1, 0], [Math.sin(a), 0, Math.cos(a)]];
  const B = (c, h, genre, axes = DROIT) => boite(L, add(H, mul(c, S)), axes, h.map((v) => v * S), genre, k);
  const S2 = (a, b, larg, ep, genre) => seg(L, add(H, mul(a, S)), add(H, mul(b, S)), larg * S, ep * S, genre, k);
  // la calotte : ronde en haut, presque droite sur les côtés jusqu'à la mâchoire
  const profil = (y) => {
    const f = y > 0 ? Math.max(0.05, 1 - (y / 1.22) ** 1.5) ** 0.75 : 1 - 0.36 * (-y) ** 1.2;
    return { rx: 0.86 * f, rz: 0.86 * f };
  };
  const tranches = 11;
  for (let i = 0; i < tranches; i++) {
    const y = -0.85 + (i + 0.5) * (2.05 / tranches), p = profil(y);
    B([0, y, -0.04], [p.rx, 1.025 / tranches + 0.005, p.rz * 0.74], "casque");
    B([0, y, -0.04], [p.rx * 0.74, 1.025 / tranches + 0.005, p.rz], "casque");
  }
  // le bandeau de renfort, et la crête de l'avant à l'arrière
  B([0, 0.36, -0.04], [0.9, 0.07, 0.68], "armure");
  B([0, 0.36, -0.04], [0.68, 0.07, 0.9], "armure");
  for (let i = 0; i < 0; i++) {
    const a = (-60 + i * 22) * DEG;
    B([0, Math.cos(a) * 1.0 + 0.04, Math.sin(a) * 0.95 - 0.04], [0.06, 0.1, 0.14], "lame", [[1, 0, 0], [0, Math.cos(a), Math.sin(a)], [0, -Math.sin(a), Math.cos(a)]]);
  }
  // la visière en pointe : deux plaques qui se rejoignent sur une arête (plus longue en rouge)
  const bec = couleur === "rouge" ? 0.62 : 0.4;
  for (const c of [-1, 1]) {
    B([c * 0.27, -0.2, 0.74 + bec * 0.5], [0.34, 0.43, 0.05], "armure", tourneY(-c * (30 + bec * 35) * DEG));
    // les trous pour respirer
    for (let i = 0; i < 3; i++) for (let j = 0; j < 2; j++) {
      B([c * (0.16 + j * 0.13), -0.32 - i * 0.12, 0.82 + bec * 0.38 - j * (0.07 + bec * 0.13)], [0.035, 0.025, 0.03], "sombre", tourneY(-c * (28 + bec * 30) * DEG));
    }
  }
  B([0, -0.2, 0.9 + bec * 0.78], [0.05, 0.43, 0.06], "lame");
  // le long nez, comme un espadon : il part de l'arête de la visière, file droit devant, et se relève un peu au bout
  {
    const n = couleur === "rouge" ? 9 : 7;
    let p = [0, -0.08, 0.92 + bec * 0.78], d = norm([0, -0.12, 1]);
    for (let i = 0; i < n; i++) {
      const q = add(p, mul(d, 0.24));
      S2(p, q, 0.27 * (1 - i / (n + 1)), 0.2 * (1 - i / (n + 1.5)), "armure");
      p = q;
      d = norm(add(d, [0, 0.035, 0]));
    }
  }
  // la fente des yeux, et les yeux qui brillent dedans
  B([0, 0.36, 0.88], [0.7, 0.06, 0.05], "sombre");
  for (const c of [-1, 1]) B([c * 0.3, 0.36, 0.92], [0.17, 0.04, 0.03], "oeil");
  // la gorgerette sous le menton
  for (let i = 0; i < 2; i++) {
    const y = -0.86 - i * 0.14, r = 0.62 - i * 0.1;
    B([0, y, 0.02], [r, 0.07, r * 0.72], "armure");
    B([0, y, 0.02], [r * 0.72, 0.07, r], "armure");
  }
  // les cornes (bleu)
  if (couleur === "bleu") for (const c of [-1, 1]) {
    let p = [c * 0.78, 0.45, 0.05], d = norm([c, 0.55, 0.25]);
    for (let i = 0; i < 7; i++) {
      const q = add(p, mul(d, 0.2));
      S2(p, q, 0.2 * (1 - i / 8), 0.14 * (1 - i / 9), "couronne");
      p = q;
      d = norm(add(d, [-c * 0.06, 0.18, -0.02]));
    }
  }
  // le panache : des flammes basses, en petits pavés, qui partent du haut de la crête vers l'arrière
  for (let n = 0; n < 5; n++) {
    let p = [(n - 2) * 0.1, 1.08 - Math.abs(n - 2) * 0.08, -0.12 - Math.abs(n - 2) * 0.06];
    let d = norm([(n - 2) * 0.25, 0.75, -0.7]);
    const segs = n === 2 ? 4 : 3;
    for (let i = 0; i < segs; i++) {
      const q = add(p, mul(d, 0.24));
      S2(p, q, 0.22 * (1 - i / (segs + 0.5)), 0.12 * (1 - i / (segs + 1)), "couronne");
      p = q;
      d = norm(add(d, [Math.sin(n * 1.9 + i) * 0.12, -0.12, -0.25]));
    }
  }
}

/** L'épée dans la main droite, le bouclier sur le bras gauche, ou l'arc et la flèche aux flammes noires. */
function armes(L, P, k, t) {
  const D = P.D, G = P.G;
  if (D.lame) {
    lame(L, D.W, D.lame, k);
  }
  if (G.bouclier) {
    const face = norm([0.5, 0, 1]), X = norm(cross([0, 1, 0], face)), Y = cross(face, X);
    const centre = add(lerp(G.E, G.W, 0.6), [0.75, 0.1, 0.7]);
    const rangs = 9;
    for (let i = 0; i < rangs; i++) {
      const y = 1.6 - i * 0.42, w = i < 4 ? 1.3 : 1.3 * (1 - (i - 3.5) / 5.3);
      boite(L, add(centre, mul(Y, y)), [X, Y, face], [w, 0.22, 0.1], "armure", k);
      // le bord lumineux, de chaque côté
      for (const sx of [-1, 1]) boite(L, add(add(centre, mul(Y, y)), mul(X, sx * w)), [X, Y, face], [0.09, 0.23, 0.14], "lame", k);
    }
    boite(L, add(centre, mul(Y, 1.82)), [X, Y, face], [1.38, 0.08, 0.14], "lame", k);
    const em = add(add(centre, mul(Y, 0.45)), mul(face, 0.14));
    for (let i = 0; i < 12; i++) {
      const a = (i / 12) * Math.PI * 2;
      boite(L, add(em, add(mul(X, Math.cos(a) * 0.55), mul(Y, Math.sin(a) * 0.55))), [X, Y, face], [0.1, 0.1, 0.04], "lame", k);
    }
    for (let i = 0; i < 3; i++) {
      const a = (i / 3) * Math.PI * 2 + 0.5;
      boite(L, add(em, add(mul(X, Math.cos(a) * 0.3), mul(Y, Math.sin(a) * 0.3))), [X, Y, face], [0.09, 0.09, 0.05], "sombre", k);
    }
    boite(L, add(em, mul(face, 0.02)), [X, Y, face], [0.13, 0.13, 0.05], "oeil", k);
  }
  if (G.arc) {
    // un grand arc : la poignée dans la main gauche, les branches qui reviennent vers lui puis se recourbent en avant,
    // hérissées de flammes vers l'arrière comme des ailes, une lame à chaque bout, un œil à la poignée, la corde tirée
    const W = G.W, avant = norm(sub(G.W, D.W)), X = norm(cross([0, 1, 0], avant)), haut = cross(avant, X);
    const branche = (signe, s) => add(add(W, mul(haut, signe * 3.4 * s)), mul(avant, -1.15 * s * s + 1.1 * Math.max(0, s - 0.78) ** 2 / 0.05));
    const bouts = [];
    for (const signe of [-1, 1]) {
      const N = 12;
      let prec = branche(signe, 0);
      for (let i = 1; i <= N; i++) {
        const t = i / N, p = branche(signe, t);
        seg(L, prec, p, 0.42 * (1 - t * 0.55), 0.26 * (1 - t * 0.4), "lame", k, 1, X);
        // les flammes sur le dos de la branche, qui partent en arrière et vers l'extérieur
        if (i % 2 === 0 && i < N) {
          const pts = [];
          const dehors = norm(add(mul(haut, signe * 0.8), mul(avant, -0.7)));
          for (let j = 0; j <= 6; j++) {
            const u = j / 6, l = 0.85 - t * 0.35;
            pts.push(add(add(p, mul(dehors, l * u)), mul(avant, -0.35 * l * u * u)));
          }
          ruban(L, pts, (j) => 0.2 * (1 - j / 6) ** 0.8 + 0.005, X, "flammeLame", k, false);
        }
        prec = p;
      }
      // la lame au bout, recourbée vers l'avant
      const bout = branche(signe, 1);
      bouts.push(bout);
      seg(L, bout, add(bout, mul(norm(add(mul(avant, 1), mul(haut, signe * 0.5))), 0.9)), 0.22, 0.1, "lame", k, 1, X);
    }
    // la garde de la poignée : un anneau, et un œil au milieu
    for (let i = 0; i < 12; i++) {
      const a = (i / 12) * Math.PI * 2;
      boite(L, add(add(W, mul(haut, Math.cos(a) * 0.5)), mul(X, Math.sin(a) * 0.5)), [X, haut, avant], [0.09, 0.09, 0.09], "lame", k);
    }
    boite(L, add(W, mul(avant, 0.1)), [X, haut, avant], [0.16, 0.16, 0.06], "oeil", k);
    // la corde, tirée jusqu'à sa main droite
    for (const b of bouts) L.push({ trait: [b, D.W], genre: "lame", k, couleur: [255, 255, 255], alpha: 0.95 });
    // la flèche : une hampe de chakra, et une grosse pointe noire (les flammes noires sont dessinées autour)
    const pointe = add(W, mul(avant, 2.0));
    seg(L, D.W, pointe, 0.14, 0.14, "lame", k);
    seg(L, pointe, add(pointe, mul(avant, 0.75)), 0.42, 0.12, "sombre", k, 1, X);
    for (const c of [-1, 1]) seg(L, add(D.W, mul(avant, 0.3)), add(add(D.W, mul(avant, 0.95)), mul(X, c * 0.28)), 0.05, 0.16, "lame", k, 1, X);
  }
}

/**
 * L'épée : une lame de flammes (de la couleur du Susano), comme une longue flamme figée. Une âme un peu ondulée dont les
 * bords vagues, avec au milieu un cœur plus clair ; tout le long, des petites flammes serrées, de tailles différentes,
 * qui remontent vers la pointe et s'enroulent un peu ; la pointe finit en une longue flamme fine ; à la garde, un
 * tourbillon de flammes. La poignée a ses bandes et un pommeau.
 */
function lame(L, W, d, k) {
  const plat = norm(cross(d, Math.abs(d[1]) > 0.9 ? [0, 0, 1] : [0, 1, 0])), ind = cross(plat, d);
  seg(L, add(W, mul(d, -0.6)), add(W, mul(d, 0.45)), 0.18, 0.18, "armure", k, 1, ind);
  for (let i = 0; i < 4; i++) boite(L, add(W, mul(d, -0.45 + i * 0.27)), [plat, d, ind], [0.12, 0.05, 0.12], "lame", k);
  boite(L, add(W, mul(d, -0.72)), [plat, d, ind], [0.17, 0.13, 0.17], "armure", k);
  const longueur = 5.3 * lisse(k * 1.2);
  if (longueur < 0.1) return;
  const ame = (s) => add(W, mul(d, 0.55 + longueur * s));
  const largeur = (s) => (0.3 * (1 - s) ** 0.5 + 0.01) * (1 + 0.2 * Math.sin(s * 37));
  const N = 40;
  ruban(L, Array.from({ length: N + 1 }, (_, i) => ame(i / N)), (i) => largeur(i / N), ind, "flammeLame", k, false);
  ruban(L, Array.from({ length: N + 1 }, (_, i) => ame((i / N) * 0.92)), (i) => largeur((i / N) * 0.92) * 0.35, ind, "lame", k, false);
  // les petites flammes des deux bords, serrées, qui remontent vers la pointe
  for (const cote of [-1, 1]) for (let n = 0; n < 16; n++) {
    const s0 = 0.02 + n * 0.058 + hache(n, cote + 5, 1) * 0.025;
    if (s0 > 0.95) continue;
    const base = add(ame(s0), mul(plat, cote * largeur(s0) * 0.7));
    const l = (0.55 + 0.75 * hache(n, cote + 3, 2)) * (1 - 0.5 * s0);
    const dir = norm(add(mul(d, 0.8), mul(plat, cote * (0.42 + 0.3 * hache(n, 7, cote + 2)))));
    const pts = [];
    for (let i = 0; i <= 6; i++) {
      const t = i / 6;
      pts.push(add(add(base, mul(dir, l * t)), mul(plat, -cote * 0.12 * l * t * t)));
    }
    ruban(L, pts, (i) => (0.17 + 0.09 * hache(n, 9, cote + 4)) * (1 - i / 6) ** 0.8 + 0.005, ind, "flammeLame", k, false);
  }
  // la pointe : une longue flamme fine qui s'enroule
  const bout = [];
  for (let i = 0; i <= 8; i++) {
    const t = i / 8;
    bout.push(add(ame(0.94 + 0.06 * t), mul(d, 0.7 * t)));
  }
  ruban(L, bout, (i) => 0.1 * (1 - i / 8) + 0.005, ind, "flammeLame", k, false);
  // le tourbillon de flammes à la garde
  for (let n = 0; n < 7; n++) {
    const a = (n / 7) * Math.PI * 2, r = add(mul(plat, Math.cos(a)), mul(ind, Math.sin(a)));
    const base = add(W, mul(d, 0.5)), pts = [];
    for (let i = 0; i <= 7; i++) {
      const t = i / 7, tourne = add(mul(r, Math.cos(t * 1.6)), mul(norm(cross(d, r)), Math.sin(t * 1.6)));
      pts.push(add(add(base, mul(tourne, 0.55 * t + 0.1)), mul(d, 0.7 * t * t - 0.1 * t)));
    }
    ruban(L, pts, (i) => 0.16 * (1 - i / 7) + 0.005, d, "flammeLame", k, false);
  }
}

/** La traînée d'un coup d'épée : un ruban en arc devant lui, plus vif vers la lame. */
function trainee(E, P, C) {
  if (!P.trainee) return;
  const [a0, a1, sens] = P.trainee, y = P.D.W[1] + 0.1;
  for (let i = 0; i < 30; i++) {
    const t0 = i / 30, t1 = (i + 1) / 30;
    const b0 = (a0 + (a1 - a0) * t0) * DEG, b1 = (a0 + (a1 - a0) * t1) * DEG;
    const p = (b, r, t) => [Math.sin(b) * r, y + 1.4 * (0.5 - t) * sens * (r / 7), Math.cos(b) * r];
    const pale = mix(C.corps, C.contour, 0.5), vif = mix(C.contour, [255, 255, 255], 0.6);
    const a = 0.03 + 0.22 * t1 * t1, av = 0.1 + 0.8 * t1 * t1;
    polygone(E, [p(b0, 3.2, t0), p(b1, 3.2, t1), p(b1, 6.9, t1), p(b0, 6.9, t0)], (i2, zz) => { if (zz < E.z[i2]) melange(E, i2, pale, a, 0.2); });
    polygone(E, [p(b0, 6.9, t0), p(b1, 6.9, t1), p(b1, 7.4, t1), p(b0, 7.4, t0)], (i2, zz) => { if (zz < E.z[i2]) melange(E, i2, vif, av, 0.6); });
  }
}

/** Les flammes noires d'Amaterasu : des langues noires bordées de rouge sombre, qui montent. */
function flammes(E, points, t) {
  for (const [p0, h0, graine0] of points) for (const [dx, kh, gs] of [[-0.09, 0.75, 7], [0.06, 1, 0]]) {
    const p = add(p0, [dx, 0, 0]), h = h0 * kh, graine = graine0 + gs;
    const q = E.proj(p);
    if (!q) continue;
    const H = (h * E.F) / q.zc, Wd = H * 0.42, oscille = Math.sin(t * 9 + graine * 3) * H * 0.18;
    const forme = (gros) => {
      const pts = [];
      for (let k = 0; k <= 16; k++) {
        const a = (k / 16) * Math.PI * 2;
        const bas = Math.sin(a) < 0;
        const x = Math.cos(a) * Wd * (bas ? 1 : 1 - Math.sin(a) * 0.9) + (bas ? 0 : oscille * Math.sin(a) ** 2 + Math.sin(a * 3 + graine) * Wd * 0.18);
        const y = bas ? -Math.sin(a) * Wd * 0.7 : -Math.sin(a) * H;
        pts.push([q.x + x * gros, q.y + y * gros]);
      }
      return pts;
    };
    for (const [gros, c] of [[1.12, [16, 12, 18]], [1, [3, 2, 4]]]) {
      const pts = forme(gros);
      const xs = pts.map((p2) => p2[0]), ys = pts.map((p2) => p2[1]);
      for (let y = Math.max(0, Math.floor(Math.min(...ys))); y <= Math.min(E.h - 1, Math.ceil(Math.max(...ys))); y++) {
        for (let x = Math.max(0, Math.floor(Math.min(...xs))); x <= Math.min(E.w - 1, Math.ceil(Math.max(...xs))); x++) {
          let dedans = false;
          for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
            if ((pts[i][1] > y) !== (pts[j][1] > y) && x < ((pts[j][0] - pts[i][0]) * (y - pts[i][1])) / (pts[j][1] - pts[i][1]) + pts[i][0]) dedans = !dedans;
          }
          const idx = y * E.w + x;
          if (dedans && q.zc < E.z[idx] + 0.6) melange(E, idx, c, 0.97, 0);
        }
      }
    }
  }
}

// ================================================================== les vues

const FINI = { taille: 1, os: 1, fibres: 1, chair: 1, armure: 1, casque: 1, armes: 1, cape: 1 };
/** La transformation, de 0 (le niveau 2) à 1 (le chevalier). */
function etape(T) {
  return {
    taille: lisse(T / 0.3), os: 1, fibres: lisse((T - 0.12) / 0.3), chair: lisse((T - 0.32) / 0.2), armure: lisse((T - 0.5) / 0.25),
    casque: lisse((T - 0.68) / 0.12), armes: lisse((T - 0.8) / 0.14), cape: lisse((T - 0.7) / 0.2),
    rire: lisse(T / 0.06) * (1 - lisse((T - 0.84) / 0.1)),
  };
}

function vue(o) {
  const E = ecran(o.ecran);
  sol(E);
  const C = COULEURS[o.couleur ?? "violet"];
  const e = o.etat, t = o.t ?? 0;
  const pose = o.pose ?? "repos";
  // le joueur, au milieu
  joueur(E, SASUKE, [0, 0, 0], 0, pose === "arc" ? [-80, -80] : pose === "repos" ? [0, 0] : [-40, 10], 1, e.rire ?? 0, t);
  if (o.cible) joueur(E, CIBLE, o.cible, 180, [-20, -20], 0.55);
  // le Susano : réduit à la taille du niveau 2 au début
  const k = 0.72 + 0.28 * e.taille;
  const kp = (p) => [p[0] * k, (p[1] - 0.35) * k + 0.35, p[2] * k];
  const L = susano(e, pose, o.couleur ?? "violet", t).map((b) =>
    b.poly ? { ...b, poly: b.poly.map(kp) } : b.trait ? { ...b, trait: b.trait.map(kp) } : { ...b, c: kp(b.c), h: b.h.map((v) => v * k) });
  trainee(E, POSES[pose], C);
  dessinerChakra(E, L, C, e.armure);
  if (o.flammes) flammes(E, o.flammes, t);
  briller(E, 6, 0.9);
  return E;
}

/** Des flammes noires sur un joueur (à cette place) : plein de petites langues sur tout le corps. */
const brule = (pos, n = 18) => [
  ...Array.from({ length: n }, (_, i) => [add(pos, [(hache(i, 1, 1) - 0.5) * 0.8, 0.1 + hache(i, 2, 2) * 1.7, (hache(i, 3, 3) - 0.5) * 0.6]), 0.22 + hache(i, 4, 4) * 0.3, i]),
  ...Array.from({ length: 10 }, (_, i) => [add(pos, [Math.cos(i * 0.63) * (0.9 + hache(i, 5, 5)), 0, Math.sin(i * 0.63) * (0.9 + hache(i, 6, 6))]), 0.3 + hache(i, 7, 7) * 0.25, i + 40]),
];

const CW = 440, CH = 520, M = 34;
if (process.argv.includes("anim")) {
  const dossier = path.join(ICI, "images");
  fs.rmSync(dossier, { recursive: true, force: true });
  fs.mkdirSync(dossier, { recursive: true });
  const N = 96;
  for (let i = 0; i < N; i++) {
    const T = Math.min(1, i / 80);
    // la caméra commence tout près de lui, qui rit, puis recule pendant que le Susano grandit
    const recul = lisse(T / 0.42);
    const E = vue({ ecran: { w: 480, h: 540, lacet: -30 + i * 0.4, tangage: 6 + 4 * recul, distance: 5.5 + 10.5 * recul, cible: [0, 1.5 + 2.3 * recul, 0] }, etat: etape(T), t: i / 18 });
    ecrirePng(path.join(dossier, String(i).padStart(3, "0") + ".png"), E.w, E.h, E.img);
  }
  console.log("images", N);
} else {
  // la pointe de la flèche, en vue CLIC DROIT (la même que dans armes() : la main gauche + 2 à 2,75 devant)
  const avantArc = norm(sub(POSES.arc.G.W, POSES.arc.D.W));
  const fleche = Array.from({ length: 9 }, (_, i) => {
    const t = 1.75 + i * 0.13, k = 0.72 + 0.28 * FINI.taille;
    const p = add(POSES.arc.G.W, mul(avantArc, t));
    return [[p[0] * k + (hache(i, 1, 9) - 0.5) * 0.3, (p[1] - 0.35) * k + 0.35 + (hache(i, 2, 9) - 0.3) * 0.2, p[2] * k], 0.3 + 0.18 * hache(i, 3, 9), i];
  });
  const vues = [
    ["NIVEAU 2", { etat: etape(0) }],
    ["LA PEAU", { etat: etape(0.3) }],
    ["LA CHAIR", { etat: etape(0.48) }],
    ["L ARMURE", { etat: etape(0.64) }],
    ["LE CHEVALIER", { etat: FINI }],
    ["ROUGE", { etat: FINI, couleur: "rouge" }],
    ["BLEU", { etat: FINI, couleur: "bleu" }],
    ["NOIR", { etat: FINI, couleur: "noir" }],
    ["DE DOS", { etat: FINI, ecran: { lacet: 160 } }],
    ["LES CASQUES", { casques: true }],
    ["CLIC GAUCHE 1", { etat: FINI, pose: "coup1", ecran: { lacet: -18, tangage: 20, distance: 15 } }],
    ["CLIC GAUCHE 2", { etat: FINI, pose: "coup2", ecran: { lacet: -18, tangage: 20, distance: 15 } }],
    ["CLIC DROIT", { etat: FINI, pose: "arc", ecran: { lacet: -78, tangage: 8, distance: 17, cible: [0.6, 4.6, 2.4] }, flammes: fleche }],
    ["FLAMMES NOIRES", { etat: FINI, pose: "arc", cible: [1.5, 0, 13], ecran: { lacet: 34, tangage: 10, distance: 8.5, cible: [1.2, 1.6, 11] }, flammes: brule([1.5, 0, 13]) }],
    ["L EPEE", { epee: true }],
  ];
  const colonnes = 5, lignes = 3;
  const W = CW * colonnes, H = (M + CH) * lignes;
  const planche = Buffer.alloc(W * H * 4, 255);
  vues.forEach(([titre, o], n) => {
    const c = n % colonnes, l = Math.floor(n / colonnes);
    let E;
    if (o.casques) {
      // les quatre casques, de près
      E = ecran({ w: CW, h: CH });
      const quart = ["violet", "rouge", "bleu", "noir"];
      quart.forEach((couleur, q) => {
        const Eq = ecran({ w: CW / 2, h: CH / 2, lacet: -24, tangage: 6, distance: 6.8, cible: [0, 6.5, 0], ciel: [[30, 32, 46], [70, 66, 90]] });
        const L = [];
        casque(L, couleur, 1);
        for (const s of [-1, 1]) boite(L, [s * 2.4, 4.4, -0.25], DROIT, [0.9, 0.8, 0.9], "chair", 1);
        boite(L, [0, 4.95, -0.35], DROIT, [0.62, 0.42, 0.62], "chair", 1);
        dessinerChakra(Eq, L, COULEURS[couleur], 1);
        briller(Eq, 4, 0.9);
        for (let y = 0; y < Eq.h; y++) Eq.img.copy(E.img, ((Math.floor(q / 2) * Eq.h + y) * CW + (q % 2) * Eq.w) * 4, y * Eq.w * 4, (y + 1) * Eq.w * 4);
      });
    } else if (o.epee) {
      E = ecran({ w: CW, h: CH, cible: [0, 3.6, 0], lacet: 0, tangage: 4, distance: 8.5, ciel: [[24, 26, 38], [60, 56, 80]] });
      const L = [];
      lame(L, [0.9, 0.5, 0], norm([-0.22, 1, 0]), 1);
      dessinerChakra(E, L, COULEURS.violet, 1);
      briller(E, 7, 1);
    } else if (o.vide) {
      E = ecran({ w: CW, h: CH, ciel: [[245, 245, 248], [245, 245, 248]] });
      ecrireTexte(E.img, CW, "SUSANO NIVEAU 3", 20, 40, 4, [30, 30, 40, 255]);
      ecrireTexte(E.img, CW, "LES OS PUIS LA PEAU", 20, 100, 3, [60, 60, 70, 255]);
      ecrireTexte(E.img, CW, "PUIS L ARMURE", 20, 130, 3, [60, 60, 70, 255]);
      ecrireTexte(E.img, CW, "PUIS LE CHEVALIER", 20, 160, 3, [60, 60, 70, 255]);
      ecrireTexte(E.img, CW, "UN CASQUE PAR COULEUR", 20, 220, 3, [60, 60, 70, 255]);
      ecrireTexte(E.img, CW, "CLIC GAUCHE EPEE", 20, 280, 3, [60, 60, 70, 255]);
      ecrireTexte(E.img, CW, "CLIC DROIT ARC", 20, 310, 3, [60, 60, 70, 255]);
      ecrireTexte(E.img, CW, "ET FLAMMES NOIRES", 20, 340, 3, [60, 60, 70, 255]);
    } else {
      E = vue({ ...o, ecran: { w: CW, h: CH, ...(o.ecran ?? {}) } });
    }
    for (let y = 0; y < CH; y++) E.img.copy(planche, ((l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(planche, W, titre, c * CW + 12, l * (M + CH) + 10, 3, [30, 30, 40, 255]);
  });
  ecrirePng(path.join(ICI, "..", "planche-susano3.png"), W, H, planche);
  console.log("ok", W, H);
}
