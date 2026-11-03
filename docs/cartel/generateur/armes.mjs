// Les armes du cartel, en 64×64, vues de profil, le canon vers la droite. Chaque arme est dessinée en pièces (bois,
// acier, or…) ; l'ombrage vient tout seul : le haut de chaque pièce s'éclaire, le bas s'assombrit, le bois a son
// fil, l'or brille, et tout le contour est cerné de sombre comme les objets de Minecraft.
// node armes.mjs → ../armes/*.png (les vraies textures) et ../armes/apercu.png
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, hache, mix } from "../../spies/generateur/moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const N = 64;

// ------------------------------------------------------------------ les matières
const MATIERES = {
  acier: { c: [62, 66, 74], metal: 1 },
  acierClair: { c: [118, 124, 136], metal: 1 },
  noir: { c: [38, 38, 44], metal: 0.5 },
  caoutchouc: { c: [30, 28, 30] },
  bois: { c: [158, 82, 38], fil: 1 },
  boisClair: { c: [190, 116, 58], fil: 1 },
  boisFonce: { c: [92, 44, 26], fil: 1 },
  or: { c: [228, 178, 52], metal: 1.4, or: 1 },
  orFonce: { c: [170, 118, 30], metal: 1, or: 1 },
  olive: { c: [92, 104, 56], metal: 0.4 },
  oliveFonce: { c: [62, 72, 40], metal: 0.3 },
  laiton: { c: [214, 166, 72], metal: 1.2 },
  cuivre: { c: [196, 112, 60], metal: 1 },
  lame: { c: [196, 202, 210], metal: 1.5 },
  chiffon: { c: [222, 210, 182], fil: 0.5 },
  verre: { c: [84, 150, 92], metal: 1.2 },
  essence: { c: [196, 120, 40] },
  flamme: { c: [255, 150, 30], lumiere: 1 },
  flammeClaire: { c: [255, 228, 110], lumiere: 1 },
  rouge: { c: [178, 34, 38] },
  vert: { c: [52, 96, 58] },
  ivoire: { c: [236, 226, 200], fil: 0.25 },
  verreOptique: { c: [60, 140, 200], metal: 1.6 },
};

// ------------------------------------------------------------------ la toile
/** L'épaisseur de chaque matière, en pixels de l'icône, quand l'arme est en 3D (dans les mains, sur les stands). */
const EPAISSEUR = { acier: 4.5, acierClair: 4, noir: 4.5, caoutchouc: 4, bois: 4.5, boisClair: 4.5, boisFonce: 4.5, or: 4.5, orFonce: 4,
  olive: 5, oliveFonce: 4, laiton: 2, cuivre: 2, lame: 1.2, chiffon: 3, flamme: 2, flammeClaire: 2.5, ivoire: 4.5, verreOptique: 2, rouge: 3, vert: 3 };

/**
 * Chaque pièce peut dire comment elle est en volume (dernier argument, facultatif) : { ep } son épaisseur, { profil }
 * "plat" (une boîte), "rond" (un tube couché, rond colonne par colonne), "rondH" (un tube debout, rond ligne par
 * ligne), "boule", "disque". Sans rien : une boîte de l'épaisseur de sa matière, ou un tube si elle est longue et fine.
 */
export function toile() {
  const piece = new Int16Array(N * N).fill(-1);
  const matiere = new Array(N * N).fill(null);
  const mod = new Float32Array(N * N).fill(1);
  const meta = [];
  const nouvelle = (m, o, forme = {}) => { meta.push({ m, ...forme, ...o }); return meta.length - 1; };
  const poser = (x, y, m, p) => {
    if (x < 0 || y < 0 || x >= N || y >= N) return;
    piece[y * N + x] = p; matiere[y * N + x] = m; mod[y * N + x] = 1;
  };
  const dansPoly = (pts, x, y) => {
    let d = false;
    for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
      const [xi, yi] = pts[i], [xj, yj] = pts[j];
      if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) d = !d;
    }
    return d;
  };
  const t = {
    /** Un polygone plein : une nouvelle pièce. */
    poly(pts, m, o = {}) {
      const p = nouvelle(m, o);
      for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) if (dansPoly(pts, x + 0.5, y + 0.5)) poser(x, y, m, p);
      return t;
    },
    rect(x0, y0, x1, y1, m, o = {}) { return t.poly([[x0, y0], [x1, y0], [x1, y1], [x0, y1]], m, o); },
    /** Une ellipse pleine (en volume : une boule, sauf si on dit autre chose). */
    rond(cx, cy, rx, ry, m, o = {}) {
      const p = nouvelle(m, o, { profil: "boule", cx, cy, rx, ry });
      for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) if (((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1) poser(x, y, m, p);
      return t;
    },
    /** Un anneau (anneau de goupille, pontet). */
    anneau(cx, cy, r, e, m, o = {}) {
      const p = nouvelle(m, o, { ep: 1.4 });
      for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) { const d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy); if (d <= r && d >= r - e) poser(x, y, m, p); }
      return t;
    },
    /** Un trait épais le long de points (une seule pièce). */
    trait(pts, e, m, o = {}) {
      const p = nouvelle(m, o, { ep: Math.max(1.2, e) });
      for (let k = 0; k + 1 < pts.length; k++) {
        const [ax, ay] = pts[k], [bx, by] = pts[k + 1], L = Math.hypot(bx - ax, by - ay) || 1;
        for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
          const px = x + 0.5, py = y + 0.5;
          const s = Math.max(0, Math.min(1, ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / (L * L)));
          if (Math.hypot(px - ax - s * (bx - ax), py - ay - s * (by - ay)) <= e / 2) poser(x, y, m, p);
        }
      }
      return t;
    },
    /** Assombrit (k < 1) ou éclaire (k > 1) des pixels déjà posés, sans changer de pièce : rainures, gravures, vis. */
    marque(pts, k) {
      for (const [x, y] of pts) if (x >= 0 && y >= 0 && x < N && y < N && matiere[y * N + x]) mod[y * N + x] *= k;
      return t;
    },
    ligneMarque(x0, y0, x1, y1, k) {
      const l = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)), pts = [];
      for (let i = 0; i <= l; i++) pts.push([Math.round(x0 + ((x1 - x0) * i) / (l || 1)), Math.round(y0 + ((y1 - y0) * i) / (l || 1))]);
      return t.marque(pts, k);
    },
    /** Efface des pixels (une découpe : crosse squelette, pontet). */
    trou(pts) {
      for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) if (dansPoly(pts, x + 0.5, y + 0.5)) { piece[y * N + x] = -1; matiere[y * N + x] = null; }
      return t;
    },
    finir: () => ({ ...ombrer(piece, matiere, mod), prof: profondeurs(piece, matiere, meta) }),
  };
  return t;
}

/** L'épaisseur de chaque pixel (0 : vide), pour le modèle 3D. */
function profondeurs(piece, matiere, meta) {
  const prof = new Float32Array(N * N);
  const meme = (x, y, p) => x >= 0 && y >= 0 && x < N && y < N && piece[y * N + x] === p;
  // la taille de chaque pièce, pour reconnaître les tubes
  const boite = meta.map(() => [N, N, -1, -1]);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const p = piece[y * N + x];
    if (p < 0) continue;
    const b = boite[p]; b[0] = Math.min(b[0], x); b[1] = Math.min(b[1], y); b[2] = Math.max(b[2], x); b[3] = Math.max(b[3], y);
  }
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const p = piece[y * N + x];
    if (p < 0) continue;
    const M = meta[p], [bx0, by0, bx1, by1] = boite[p], l = bx1 - bx0 + 1, h = by1 - by0 + 1;
    let profil = M.profil;
    if (!profil) profil = matiere[y * N + x] === "verre" || matiere[y * N + x] === "essence" ? "rondH" : l >= 2.5 * h && h <= 5 ? "rond" : "plat";
    const ep = M.ep ?? EPAISSEUR[matiere[y * N + x]] ?? 4;
    let d = ep;
    if (profil === "rond" || profil === "rondH") {
      // le tube : on mesure la pièce dans cette colonne (ou cette ligne), et on arrondit
      const [dx, dy] = profil === "rond" ? [0, 1] : [1, 0];
      let a = 0; while (meme(x - dx * (a + 1), y - dy * (a + 1), p)) a++;
      let b = 0; while (meme(x + dx * (b + 1), y + dy * (b + 1), p)) b++;
      const r = (a + b + 1) / 2, tt = (a + 0.5 - r) / r;
      d = (M.ep ?? 2 * r) * Math.sqrt(Math.max(0.2, 1 - tt * tt));
    } else if (profil === "boule") {
      const u = (x + 0.5 - M.cx) / M.rx, v = (y + 0.5 - M.cy) / M.ry;
      d = (M.ep ?? 2 * Math.min(M.rx, M.ry)) * Math.sqrt(Math.max(0.12, 1 - u * u - v * v));
    } else if (profil === "disque") {
      const u = (x + 0.5 - M.cx) / M.rx, v = (y + 0.5 - M.cy) / M.ry;
      d = ep * (u * u + v * v > 0.8 ? 0.8 : 1);
    }
    prof[y * N + x] = Math.max(1, d);
  }
  return prof;
}

/** L'ombrage automatique, puis le contour sombre. */
function ombrer(piece, matiere, mod) {
  const px = Buffer.alloc(N * N * 4);
  const meme = (x, y, p) => x >= 0 && y >= 0 && x < N && y < N && piece[y * N + x] === p;
  const rempli = (x, y) => x >= 0 && y >= 0 && x < N && y < N && piece[y * N + x] >= 0;
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const i = y * N + x, p = piece[i];
    if (p < 0) continue;
    const M = MATIERES[matiere[i]];
    let haut = 0; while (haut < 4 && meme(x, y - haut - 1, p)) haut++;
    let bas = 0; while (bas < 4 && meme(x, y + bas + 1, p)) bas++;
    let k = 1;
    const m = M.metal ?? 0.7;
    k *= [1 + 0.26 * m, 1 + 0.12 * m, 1 + 0.03 * m, 1, 1][haut];
    k *= [0.68, 0.84, 0.95, 1, 1][bas];
    if (!meme(x - 1, y, p)) k *= 1.06;
    if (!meme(x + 1, y, p)) k *= 0.88;
    if (M.fil) {
      // le fil du bois : de longues veines qui ondulent, et quelques nœuds sombres
      const veine = Math.sin(x * 0.22 + y * 1.3 + 2.5 * Math.sin(x * 0.09 + y * 0.4));
      k *= 1 + 0.09 * M.fil * veine;
      if (hache(x >> 2, y, 7) < 0.1) k *= 1 - 0.12 * M.fil;
    }
    if (M.or && hache(x, y, 11) > 0.93 && haut < 2) k *= 1.25;
    if (!M.fil && !M.lumiere) k *= 0.97 + 0.06 * hache(x, y, 3);
    k *= mod[i];
    const c = M.c.map((v) => Math.max(0, Math.min(255, Math.round(v * k))));
    px.set([...c, 255], i * 4);
  }
  // le contour : chaque vide qui touche l'arme prend une version très sombre de sa voisine
  const contour = [];
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    if (rempli(x, y)) continue;
    const v = [[1, 0], [-1, 0], [0, 1], [0, -1]].find(([dx, dy]) => rempli(x + dx, y + dy));
    if (!v) continue;
    const j = ((y + v[1]) * N + x + v[0]) * 4;
    const lumiere = MATIERES[matiere[(y + v[1]) * N + x + v[0]]].lumiere;
    contour.push([(y * N + x) * 4, lumiere ? [px[j], px[j + 1] * 0.7, px[j + 2] * 0.3, 150] : [px[j] * 0.28 + 8, px[j + 1] * 0.28 + 8, px[j + 2] * 0.28 + 10, 255]]);
  }
  for (const [o, c] of contour) px.set(c.map(Math.round), o);
  return { w: N, h: N, px };
}

// ------------------------------------------------------------------ les armes
const vis = (t, pts) => t.marque(pts, 0.55);

/** Le chargeur banane de l'AK, qui se courbe vers l'avant. */
function chargeurBanane(t, x, y, m) {
  const pts = [];
  for (let k = 0; k <= 8; k++) { const s = k / 8; pts.push([x + 13 * s * s + 1.5 * s, y + 15 * s]); }
  const avant = pts.map(([a, b]) => [a + 6.5, b - 0.8 * (1 - 0)]);
  t.poly([...pts.map(([a, b]) => [a, b]), ...avant.reverse()], m, { ep: 3.2 });
  for (let k = 2; k <= 7; k++) { const s = k / 8; const a = x + 13 * s * s + 1.5 * s, b = y + 15 * s; t.ligneMarque(Math.round(a + 1), Math.round(b), Math.round(a + 5), Math.round(b - 0.5), 0.8); }
}

function ak(t, metal, bois, boisFonce, gravure) {
  // la crosse en bois, qui descend vers la plaque de couche
  t.poly([[1, 28.5], [21, 27], [21, 33.5], [1, 39]], bois);
  t.rect(0.5, 28, 2.5, 39.5, boisFonce);
  // la poignée pistolet
  t.poly([[24, 33], [29.5, 33], [27.5, 43], [22.5, 42]], bois);
  // la carcasse, le couvercle et la hausse
  t.rect(20, 27, 39, 34, metal);
  t.poly([[20, 27], [21, 25.6], [38, 25.6], [39, 27]], metal);
  t.rect(37, 24.2, 40.5, 26, metal, { ep: 2.5 });
  t.trait([[30, 33.5], [30.5, 37.5], [34.5, 37.5], [35, 34]], 1.3, metal);
  t.rect(30.5, 30, 35, 31.2, metal);
  t.ligneMarque(21, 29, 38, 29, 0.7);
  vis(t, [[23, 31], [36, 31], [26, 28]]);
  // le levier d'armement
  t.rect(34, 28.8, 38.5, 30, metal === "or" ? "orFonce" : "acierClair", { ep: 6.5 });
  // le chargeur
  chargeurBanane(t, 34.2, 34, metal === "or" ? "orFonce" : metal);
  // le garde-main en bois (dessous et dessus), le tube de gaz, le canon, le guidon et le frein de bouche
  t.poly([[39, 29.5], [49, 29.5], [50.5, 31], [50, 34], [39, 34]], bois);
  t.ligneMarque(41, 31, 48, 31, 0.8); t.ligneMarque(41, 32.5, 48, 32.5, 0.8);
  t.poly([[39, 26], [47, 26], [48, 27.5], [47, 29.3], [39, 29.3]], bois);
  t.rect(47, 26.5, 52.5, 28.4, metal);
  t.rect(49.5, 29.5, 60.5, 31.6, metal);
  t.rect(50, 31.7, 57, 32.6, metal);
  t.poly([[54, 31.5], [55, 27], [56, 25.2], [57, 25.2], [57.5, 29.5], [58, 31.5]], metal, { ep: 1.6 });
  t.rect(59.5, 29, 63.5, 32, metal, { profil: "rond", ep: 3.4 });
  t.marque([[61, 30], [62, 30]], 0.5);
  if (gravure) {
    // les arabesques gravées sur l'or
    for (let x = 22; x < 38; x += 2) t.marque([[x, 30 + ((x / 2) % 2)], [x + 1, 31 - ((x / 2) % 2)]], 0.72);
    t.marque([[24, 32], [27, 32], [33, 32], [36, 32]], 1.3);
  }
}

export const ARMES = [
  {
    id: "ak47", nom: "AK-47", prix: 500, degats: 4, cadence: 9, portee: 6,
    ligne: "La Kalach. Le fusil du grand banditisme.",
    dessin(t) { ak(t, "acier", "bois", "boisFonce"); },
  },
  {
    id: "ak47_or", nom: "AK-47 en or", prix: 1500, degats: 5, cadence: 9, portee: 6,
    ligne: "Toute plaquée or, gravée. Celle du patron.",
    dessin(t) { ak(t, "or", "boisFonce", "caoutchouc", true); },
  },
  {
    id: "uzi", nom: "Uzi", prix: 250, degats: 2, cadence: 10, portee: 3,
    ligne: "Petite, rapide, ça arrose.",
    dessin(t) {
      // la crosse repliée, en fil d'acier
      t.trait([[18, 25.5], [6, 26.5], [6, 34], [18, 32.5]], 1.6, "acier");
      t.rect(4.5, 25, 7, 35, "acier");
      t.rect(17, 22.5, 49, 32, "noir");
      t.poly([[17, 22.5], [18.5, 21], [47.5, 21], [49, 22.5]], "noir");
      t.rect(40, 19.5, 43.5, 21.5, "acierClair");
      t.rect(49, 25.5, 56, 28.5, "acier", { profil: "rond" });
      t.rect(55, 25, 57, 29, "acier");
      t.poly([[37, 32], [48, 32], [48, 35], [38, 35]], "caoutchouc");
      // la poignée, avec le chargeur dedans qui dépasse en bas
      t.poly([[27, 32], [35, 32], [34, 44], [27, 44]], "caoutchouc");
      t.rect(28, 44, 33.5, 55, "acier");
      t.rect(27.5, 54, 34, 56, "noir");
      t.trait([[35, 32], [36, 36.5], [39.5, 36.5], [40, 32]], 1.3, "noir");
      for (let y = 34; y < 43; y += 2) t.ligneMarque(28, y, 33, y, 0.7);
      t.ligneMarque(19, 25, 47, 25, 0.7);
      for (let x = 21; x < 46; x += 3) t.marque([[x, 28], [x, 29]], 0.6);
    },
  },
  {
    id: "mac10", nom: "MAC-10", prix: 250, degats: 2, cadence: 10, portee: 3,
    ligne: "Avec son silencieux. Le PM des braqueurs.",
    dessin(t) {
      t.rect(10, 25.5, 22, 27, "acier");
      t.rect(10, 30, 22, 31.5, "acier");
      t.rect(8.5, 24, 11, 33.5, "acier");
      t.rect(21, 23, 41, 33, "acier");
      t.rect(23, 21.5, 37, 23.2, "acierClair");
      t.rect(40.5, 25, 63, 31.2, "noir", { profil: "rond" });
      for (let x = 44; x < 62; x += 4) t.ligneMarque(x, 26, x, 30, 0.75);
      t.poly([[26, 33], [32.5, 33], [32, 44], [26, 44]], "acier");
      t.rect(27, 44, 31.5, 56, "acier");
      t.rect(26.5, 55, 32, 57, "noir");
      t.trait([[32.5, 33], [33, 37], [36.5, 37], [37, 33]], 1.3, "acier");
      for (let y = 35; y < 43; y += 2) t.ligneMarque(27, y, 31, y, 0.7);
      // la sangle qui pend
      t.trait([[39, 33], [36, 41], [28, 47], [18, 44], [11, 34]], 1.4, "caoutchouc");
      vis(t, [[24, 26], [38, 26], [24, 31]]);
    },
  },
  {
    id: "thompson", nom: "Thompson", prix: 400, degats: 3, cadence: 8, portee: 4,
    ligne: "La sulfateuse des gangsters, avec son chargeur camembert.",
    dessin(t) {
      t.poly([[1, 29], [19, 27.5], [19, 33.5], [1, 39]], "boisClair");
      t.rect(0.5, 28.5, 2.4, 39.5, "boisFonce");
      t.poly([[21, 32], [26.5, 32], [25, 41], [19.5, 40]], "boisClair");
      t.rect(18, 25.5, 37, 33, "acier");
      t.poly([[18, 25.5], [19, 24], [36, 24], [37, 25.5]], "acier");
      t.rect(30, 22.6, 33, 24.5, "acierClair");
      t.trait([[27, 32.5], [27.5, 36.5], [31, 36.5], [31.5, 33]], 1.3, "acier");
      // le camembert de 50 coups
      t.rond(31.5, 40, 8.5, 8.5, "acier", { profil: "disque", ep: 6 });
      t.anneau(31.5, 40, 5, 1, "acierClair", { ep: 6.6 });
      t.rond(31.5, 40, 1.6, 1.6, "acierClair", { profil: "disque", ep: 7.2 });
      t.rect(29, 31, 34, 33.5, "acier");
      // le canon à ailettes et le compensateur
      t.rect(37, 27, 55, 30.8, "acier");
      for (let x = 38; x < 49; x += 2) t.rect(x, 26, x + 1, 31.8, "acierClair", { profil: "rond" });
      t.rect(54.5, 26.2, 61, 31.5, "acier", { profil: "rond" });
      t.marque([[56, 27], [58, 27], [60, 27]], 0.5);
      // la poignée avant verticale
      t.poly([[41, 30.8], [46.5, 30.8], [47, 38], [42, 40], [41, 38]], "boisClair");
      for (let y = 33; y < 39; y += 2) t.ligneMarque(42, y, 46, y, 0.8);
      vis(t, [[21, 28], [35, 28]]);
    },
  },
  {
    id: "desert_eagle_or", nom: "Desert Eagle en or", prix: 300, degats: 7, cadence: 2, portee: 5,
    ligne: "Le gros calibre plaqué or. Ça fait mal.",
    dessin(t) {
      t.poly([[11, 22], [13, 19], [54, 19], [55.5, 21], [55.5, 30], [11, 30]], "or");
      t.rect(9.5, 18.5, 13.5, 23, "orFonce");
      t.rect(50, 17.5, 53, 19.2, "orFonce");
      t.rect(12, 16.8, 15, 19.2, "orFonce");
      t.ligneMarque(14, 26, 55, 26, 0.72);
      for (let x = 15; x < 23; x += 2) t.ligneMarque(x, 20, x, 25, 0.68);
      t.rect(30, 21, 40, 23.5, "noir");
      t.marque([[54, 22], [54, 23], [54, 24]], 0.4);
      // la carcasse, la poignée noire et son pontet
      t.rect(13, 30, 44, 34.5, "orFonce");
      t.poly([[13.5, 32], [27, 32], [24.5, 53], [10, 51.5]], "caoutchouc");
      for (let y = 36; y < 50; y += 2.5) for (let x = 13; x < 25; x += 2.5) t.marque([[Math.round(x - (y - 36) * 0.15), Math.round(y)]], 1.5);
      t.trait([[27.5, 34], [28.5, 40], [35, 41], [37, 34.5]], 1.6, "orFonce");
      t.rect(29, 34.5, 30.6, 38, "acier");
      vis(t, [[18, 33], [39, 32]]);
      t.marque([[16, 21], [17, 21], [18, 21], [44, 21], [45, 21]], 1.25);
    },
  },
  {
    id: "canon_scie", nom: "Canon scié", prix: 300, degats: 8, cadence: 1, portee: 2,
    ligne: "Double canon coupé court. À bout portant, c'est fini.",
    dessin(t) {
      t.poly([[22, 29], [29, 33], [16, 45], [9, 44.5], [7, 41]], "bois");
      t.marque([[8, 42], [9, 43], [10, 44]], 0.6);
      t.rect(20, 25, 31, 34, "acier");
      t.rect(23, 22.5, 25.5, 25.2, "acierClair");
      t.rect(27, 22.5, 29.5, 25.2, "acierClair");
      for (let x = 21; x < 30; x += 2) t.marque([[x, 28 + ((x >> 1) % 2)], [x + 1, 30 - ((x >> 1) % 2)]], 0.65);
      t.rect(31, 25.5, 57, 29.4, "acier");
      t.rect(31, 29.6, 57, 33.4, "acier");
      t.rect(56, 25, 58, 34, "acierClair", { profil: "rond", ep: 4.5 });
      t.marque([[57, 27], [57, 31]], 0.3);
      t.poly([[31, 33.4], [45, 33.4], [44, 37], [31, 37]], "bois");
      t.trait([[25.5, 34], [26, 38.5], [30, 38.5], [31, 34]], 1.3, "acier");
    },
  },
  {
    id: "barrett", nom: "Barrett .50", prix: 900, degats: 16, cadence: 1, portee: 10,
    ligne: "Le fusil de sniper de l'armée. Une balle, un mec.",
    dessin(t) {
      t.rect(0.5, 28, 3.5, 37.5, "caoutchouc");
      t.poly([[3.5, 29], [19, 28], [19, 35], [10, 35], [8, 37], [3.5, 37]], "noir");
      t.trou([[8, 31.5], [15, 31], [15, 33], [10, 33]]);
      t.rect(18, 26.5, 41, 34, "noir");
      t.poly([[23, 34], [28, 34], [27, 42.5], [22.5, 41.5]], "caoutchouc");
      t.trait([[28, 34], [28.5, 37.5], [31.5, 37.5], [32, 34]], 1.3, "noir");
      t.rect(31.5, 34, 38.5, 43, "acier");
      // la lunette
      t.rect(22, 22, 38, 24.8, "noir");
      t.poly([[18, 21], [22, 21.8], [22, 25], [18, 25.8]], "noir", { profil: "rond" });
      t.poly([[38, 21.8], [42, 20.5], [42, 26.2], [38, 25]], "noir", { profil: "rond" });
      t.rect(41.5, 21, 42.5, 25.8, "verreOptique", { profil: "rond" });
      t.rect(24, 24.8, 26, 26.6, "acier"); t.rect(34, 24.8, 36, 26.6, "acier");
      t.rect(29, 20.5, 31, 22.2, "acierClair");
      // le canon, le frein de bouche en flèche et le bipied déplié
      t.rect(41, 28.3, 58, 31.4, "acier");
      for (let x = 42; x < 50; x += 2) t.ligneMarque(x, 28, x, 31, 0.75);
      t.poly([[57, 26.5], [62, 27.8], [63.5, 29.8], [62, 31.8], [57, 33]], "acier", { ep: 6 });
      t.marque([[59, 29], [59, 30], [61, 29], [61, 30]], 0.4);
      t.trait([[46, 31.5], [42, 45]], 1.5, "acier");
      t.trait([[47, 31.5], [51, 45]], 1.5, "acier");
      t.rect(41, 44.5, 43.5, 46, "caoutchouc"); t.rect(50, 44.5, 52.5, 46, "caoutchouc");
      vis(t, [[20, 30], [39, 30], [34, 37]]);
    },
  },
  {
    id: "m60", nom: "Mitrailleuse M60", prix: 1000, degats: 4, cadence: 8, portee: 7,
    ligne: "La mitrailleuse de guerre, avec sa bande de balles.",
    dessin(t) {
      t.poly([[1, 28], [16, 27], [16, 34], [1, 38]], "noir");
      t.rect(0.5, 27.5, 2.5, 38.5, "caoutchouc");
      t.rect(15, 25.5, 39, 33.5, "acier");
      t.poly([[17, 25.5], [18, 23.5], [36, 23.5], [37, 25.5]], "acier");
      t.poly([[21, 33.5], [26, 33.5], [25, 41.5], [20, 40.5]], "noir");
      t.trait([[26, 33.5], [26.5, 37.5], [30, 37.5], [30.5, 33.5]], 1.3, "acier");
      // la poignée de transport, le canon, le cache-flamme, le bipied replié
      t.trait([[40, 27], [41.5, 22.5], [47, 22.5], [48.5, 27]], 1.6, "noir");
      t.rect(39, 27.2, 60, 31, "acier");
      t.rect(39, 31, 47, 33, "noir");
      t.rect(59.5, 26.5, 63.5, 31.6, "acier", { profil: "rond" });
      t.marque([[61, 28], [61, 29], [62, 28], [62, 29]], 0.5);
      t.trait([[50, 31.5], [57, 33.5]], 1.4, "acier");
      // la bande de balles qui pend de la boîte
      t.rect(28, 31, 35, 37.5, "olive", { ep: 7 });
      const bande = [];
      for (let k = 0; k < 9; k++) { const s = k / 8; bande.push([31 - 15 * s + 3 * Math.sin(s * 3), 37.5 + 16 * s - 6 * s * s]); }
      for (const [x, y] of bande) { t.rect(x - 1.1, y - 2.6, x + 1.1, y + 1.4, "laiton"); t.rect(x - 1.1, y - 3.6, x + 1.1, y - 2.4, "cuivre"); }
      vis(t, [[18, 29], [36, 29]]);
    },
  },
  {
    id: "minigun", nom: "Minigun", prix: 2000, degats: 3, cadence: 10, portee: 6,
    ligne: "Six canons qui tournent. Le carnage.",
    dessin(t) {
      // le moteur, le carter et la poignée en bêche
      t.rect(6, 25, 18, 37, "noir", { profil: "rond" });
      t.rect(4, 27, 6.5, 35, "acier", { profil: "rond" });
      t.trait([[9, 25], [10, 17], [20, 17], [21, 23]], 1.8, "acier");
      t.rect(12, 15.5, 18, 18.5, "caoutchouc");
      t.rect(17, 22, 30, 40, "acier", { profil: "rond" });
      t.ligneMarque(18, 31, 29, 31, 0.7);
      vis(t, [[19, 24], [28, 24], [19, 38], [28, 38]]);
      // les six canons (on en voit trois), tenus par deux colliers
      for (const y of [24.5, 29.5, 34.5]) t.rect(30, y, 61, y + 3.2, y === 29.5 ? "acierClair" : "acier", { profil: "rond", ep: y === 29.5 ? 13 : 9 });
      t.rect(38, 23.5, 41, 38.6, "acier", { profil: "rond" });
      t.rect(55, 23.5, 57.5, 38.6, "acier", { profil: "rond" });
      for (const y of [25, 30, 35]) t.marque([[61, y + 1], [62, y + 1]], 0.4);
      t.rect(60.5, 24, 62.5, 38.2, "acier", { profil: "rond" });
      for (const y of [25.8, 30.8, 35.8]) t.marque([[61, Math.round(y)], [62, Math.round(y)]], 0.35);
      // la goulotte et la bande de balles
      t.trait([[22, 40], [18, 47], [10, 52]], 3.4, "noir");
      for (let k = 0; k < 4; k++) { const x = 9 - k * 2.4, y = 53 + k * 1.6; t.rect(x - 1, y - 2.2, x + 1.2, y + 1.6, "laiton"); t.rect(x - 1, y - 3.2, x + 1.2, y - 2, "cuivre"); }
    },
  },
  {
    id: "rpg7", nom: "Bazooka RPG-7", prix: 1500, degats: 18, cadence: 1, portee: 8,
    ligne: "Lance-roquettes. Ça explose (sans casser les blocs).",
    dessin(t) {
      t.poly([[0.5, 26.5], [5, 28.5], [5, 33.5], [0.5, 35.5]], "acier", { profil: "rond" });
      t.rect(4, 28.8, 46, 33.2, "olive");
      t.rect(15, 27, 31, 35, "bois", { profil: "rond" });
      for (let x = 17; x < 30; x += 3) t.ligneMarque(x, 27, x, 34, 0.78);
      t.rect(14, 26.6, 15.6, 35.4, "acier", { profil: "rond" }); t.rect(30.4, 26.6, 32, 35.4, "acier", { profil: "rond" });
      t.poly([[20, 35], [24.5, 35], [23.5, 43], [19, 42]], "noir");
      t.trait([[24.5, 35], [25, 38.5], [28, 38.5], [28.5, 35]], 1.2, "acier");
      t.poly([[33, 33], [36, 33], [35.5, 39], [32.5, 39]], "noir");
      t.rect(26, 23, 33, 26.8, "noir");
      t.rect(32.5, 23.5, 33.5, 26, "verreOptique");
      // la roquette : le tube, la tête en bulbe vert olive et la pointe
      t.rect(45.5, 29.2, 50, 32.8, "oliveFonce", { profil: "rond" });
      t.poly([[49.5, 29.4], [53, 24.5], [57, 24], [59.5, 27], [59.5, 35], [57, 38], [53, 37.5], [49.5, 32.6]], "olive", { profil: "rond" });
      t.poly([[59, 27.4], [63.5, 30.4], [63.5, 31.6], [59, 34.6]], "oliveFonce", { profil: "rond" });
      t.ligneMarque(54, 25, 54, 37, 0.75);
      t.marque([[56, 26], [57, 26], [55, 27]], 1.25);
    },
  },
  {
    id: "m79", nom: "Lance-grenades M79", prix: 1000, degats: 12, cadence: 1, portee: 7,
    ligne: "Le gros tube qui envoie des grenades en cloche.",
    dessin(t) {
      t.poly([[1, 29], [8, 27.5], [21, 28], [21, 35], [12, 36], [1, 39.5]], "bois");
      t.trou([[7, 32], [14, 31.5], [14, 33.5], [8, 34.2]]);
      t.rect(0.5, 28.5, 2.4, 40, "caoutchouc");
      t.rect(20, 26.5, 29, 35.5, "acier");
      t.rect(29, 25, 57, 34.5, "acier", { profil: "rond" });
      t.rect(56, 24.5, 59, 35, "acierClair", { profil: "rond" });
      t.marque([[57, 28], [57, 29], [57, 30], [57, 31]], 0.4);
      t.rect(31, 20.5, 35, 25.2, "acierClair");
      t.ligneMarque(32, 21, 32, 24, 0.6);
      t.poly([[30, 34.5], [48, 34.5], [47, 38.5], [30, 38.5]], "bois");
      t.trait([[24, 35.5], [24.5, 40], [28.5, 40], [29, 35.5]], 1.3, "acier");
      t.ligneMarque(30, 29.5, 55, 29.5, 1.12);
      vis(t, [[22, 28], [27, 33]]);
    },
  },
  {
    id: "grenade", nom: "Grenade", prix: 40, degats: 14, cadence: 1, portee: 4,
    ligne: "L'ananas. Tu dégoupilles, tu lances, 3 secondes.",
    dessin(t) {
      t.rond(30, 40, 14, 17, "olive");
      for (let k = -3; k <= 3; k++) t.ligneMarque(16, 40 + k * 5, 44, 40 + k * 5, 0.66);
      for (let k = -2; k <= 2; k++) {
        const x = 30 + k * 5.5;
        for (let y = 24; y < 57; y++) t.marque([[Math.round(x + (k * (y - 40) ** 2) / 300), y]], 0.66);
      }
      t.rect(25, 17, 35, 24, "acier", { profil: "rondH" });
      t.rect(26, 15, 34, 17.5, "acierClair", { profil: "rondH" });
      t.trait([[34, 17], [41, 18.5], [44.5, 24], [45, 36]], 2.6, "acierClair");
      t.anneau(19.5, 15, 6, 1.6, "acierClair");
      t.trait([[25, 17], [23, 19.5]], 1.2, "acierClair");
    },
  },
  {
    id: "molotov", nom: "Cocktail Molotov", prix: 30, degats: 6, cadence: 1, portee: 4,
    ligne: "Une bouteille, de l'essence, un chiffon. Tout brûle.",
    dessin(t) {
      t.poly([[22, 32], [26, 28], [38, 28], [42, 32], [42, 59], [22, 59]], "verre");
      t.rect(23, 42, 41, 58, "essence");
      t.ligneMarque(24, 42, 40, 42, 1.3);
      t.rect(28, 15, 36, 28.5, "verre");
      t.rect(27.5, 15, 36.5, 17, "verre");
      t.marque(Array.from({ length: 22 }, (_, k) => [25, 33 + k]), 1.45);
      t.marque(Array.from({ length: 10 }, (_, k) => [29, 17 + k]), 1.4);
      // le chiffon qui sort du goulot et la flamme
      t.poly([[28, 16], [27, 11], [30, 7], [35, 8], [37, 13], [36, 17]], "chiffon", { profil: "rondH" });
      t.poly([[35.5, 16], [39, 19], [40, 25], [37.5, 23], [36, 19]], "chiffon");
      t.ligneMarque(29, 9, 35, 15, 0.8);
      t.poly([[26, 9], [24, 4], [27, 5], [29, 0.5], [32, 4], [35, 1], [36.5, 5], [39, 3], [38.5, 9], [35, 11], [29, 11]], "flamme");
      t.poly([[29, 9], [29.5, 5], [32, 7], [34, 4], [35, 9], [32, 10]], "flammeClaire");
    },
  },
  {
    id: "machette", nom: "Machette", prix: 50, degats: 7, cadence: 3, portee: 1,
    ligne: "Pour le corps à corps. Classique du cartel.",
    dessin(t) {
      t.poly([[1.5, 33], [3, 30.5], [17, 30], [19, 30], [19, 36.5], [17, 36.5], [3, 37], [1.5, 35]], "caoutchouc");
      t.marque([[6, 33], [6, 34], [12, 33], [12, 34]], 2.6);
      t.rect(18.5, 28.5, 21, 38.5, "acier");
      t.poly([[21, 30], [50, 29], [58, 28], [62.5, 29.5], [60, 34], [52, 38.5], [36, 38], [21, 36.5]], "lame");
      t.ligneMarque(22, 37, 54, 38, 1.22);
      t.ligneMarque(22, 31, 50, 30, 0.82);
      t.marque([[30, 33], [31, 33], [40, 34], [47, 33], [48, 34]], 0.7);
    },
  },
];

/** Ce qui vole : la roquette du RPG et l'obus du lance-grenades (vus en jeu en 3D, jamais dans l'inventaire). */
export const PROJECTILES = [
  {
    id: "roquette",
    dessin(t) {
      t.poly([[12, 26], [16, 29.5], [16, 33.5], [12, 37]], "oliveFonce", { profil: "rond" });
      t.rect(15, 29.6, 40, 33.4, "oliveFonce", { profil: "rond" });
      t.poly([[39.5, 29.4], [43, 24.5], [47, 24], [49.5, 27], [49.5, 35], [47, 38], [43, 37.5], [39.5, 32.6]], "olive", { profil: "rond" });
      t.poly([[49, 27.4], [54, 30.4], [54, 31.6], [49, 34.6]], "oliveFonce", { profil: "rond" });
      t.ligneMarque(44, 25, 44, 37, 0.75);
    },
  },
  {
    id: "obus",
    dessin(t) {
      t.rect(22, 25, 34, 38, "laiton", { profil: "rond" });
      t.rect(21, 24, 23, 39, "laiton", { profil: "rond" });
      t.rect(34, 26, 40, 37, "olive", { profil: "rond" });
      t.poly([[40, 26.5], [44, 29], [44.5, 31.5], [44, 34], [40, 36.5]], "olive", { profil: "rond" });
      t.ligneMarque(36, 26, 36, 36, 1.3);
    },
  },
];

/** Le symbole « $ » et quelques signes pour la police des planches. */
export const SIGNES = { $: "011110010011110", "-": "000000111000000", ":": "000010000010000", ".": "000000000000010", "'": "010010000000000", ",": "000000000010100", "!": "010010010000010", "+": "000010111010000", "/": "001001010100100", "@": "011101111100011", "(": "010100100100010", ")": "010001001001010" };

export function icones() {
  return Object.fromEntries([...ARMES, ...PROJECTILES].map((a) => { const t = toile(); a.dessin(t); return [a.id, t.finir()]; }));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const DOSSIER = path.join(ICI, "..", "armes");
  fs.mkdirSync(DOSSIER, { recursive: true });
  const ic = icones();
  for (const [id, im] of Object.entries(ic)) ecrirePng(path.join(DOSSIER, id + ".png"), N, N, im.px);
  // l'aperçu : toutes les armes en ×4 sur un fond gris d'inventaire
  const K = 4, C = 5, cote = N * K + 16, W = C * cote, H = Math.ceil(ARMES.length / C) * cote;
  const img = Buffer.alloc(W * H * 4);
  for (let i = 0; i < W * H; i++) img.set([139, 139, 139, 255], i * 4);
  ARMES.forEach((a, n) => {
    const x0 = (n % C) * cote + 8, y0 = Math.floor(n / C) * cote + 8, im = ic[a.id];
    for (let y = 0; y < N * K; y++) for (let x = 0; x < N * K; x++) {
      const o = ((Math.floor(y / K)) * N + Math.floor(x / K)) * 4, al = im.px[o + 3] / 255;
      const d = ((y0 + y) * W + x0 + x) * 4;
      for (let q = 0; q < 3; q++) img[d + q] = img[d + q] * (1 - al) + im.px[o + q] * al;
    }
  });
  ecrirePng(path.join(DOSSIER, "apercu.png"), W, H, img);
  console.log("armes dessinées dans", DOSSIER);
}

export { MATIERES, mix };
