// Maquettes de la marque maudite sur des skins Minecraft : rendu 3D isométrique du joueur, sans dépendance.
// node rendu.mjs  → planche.png
import fs from "node:fs";
import zlib from "node:zlib";

// ------------------------------------------------------------------ PNG
function lire(fichier) {
  const b = fs.readFileSync(fichier);
  let o = 8, w, h, type, prof, pal = null, trns = null;
  const idat = [];
  while (o < b.length) {
    const l = b.readUInt32BE(o), t = b.toString("ascii", o + 4, o + 8), d = b.subarray(o + 8, o + 8 + l);
    if (t === "IHDR") { w = d.readUInt32BE(0); h = d.readUInt32BE(4); prof = d[8]; type = d[9]; }
    if (t === "PLTE") pal = d;
    if (t === "tRNS") trns = d;
    if (t === "IDAT") idat.push(d);
    o += 12 + l;
  }
  const brut = zlib.inflateSync(Buffer.concat(idat));
  const canaux = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[type];
  const ligne = Math.ceil((w * canaux * prof) / 8);
  const pas = Math.max(1, Math.ceil((canaux * prof) / 8));
  const px = Buffer.alloc(w * h * 4);
  let prec = Buffer.alloc(ligne);
  for (let y = 0; y < h; y++) {
    const filtre = brut[y * (ligne + 1)];
    const cur = Buffer.from(brut.subarray(y * (ligne + 1) + 1, (y + 1) * (ligne + 1)));
    for (let i = 0; i < ligne; i++) {
      const a = i >= pas ? cur[i - pas] : 0, haut = prec[i], c = i >= pas ? prec[i - pas] : 0;
      const p = a + haut - c, pa = Math.abs(p - a), pb = Math.abs(p - haut), pc = Math.abs(p - c);
      const pred = [0, a, haut, (a + haut) >> 1, pa <= pb && pa <= pc ? a : pb <= pc ? haut : c][filtre];
      cur[i] = (cur[i] + pred) & 255;
    }
    for (let x = 0; x < w; x++) {
      let r, g, bl, al = 255;
      if (type === 3) {
        const i = cur[x];
        r = pal[i * 3]; g = pal[i * 3 + 1]; bl = pal[i * 3 + 2]; al = trns && i < trns.length ? trns[i] : 255;
      } else if (type === 6) {
        [r, g, bl, al] = [cur[x * 4], cur[x * 4 + 1], cur[x * 4 + 2], cur[x * 4 + 3]];
      } else if (type === 2) {
        [r, g, bl] = [cur[x * 3], cur[x * 3 + 1], cur[x * 3 + 2]];
      } else {
        r = g = bl = cur[x * canaux];
        if (type === 4) al = cur[x * 2 + 1];
      }
      px.set([r, g, bl, al], (y * w + x) * 4);
    }
    prec = cur;
  }
  return { w, h, px };
}
function ecrirePng(fichier, w, h, rgba) {
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) {
    brut[y * (1 + w * 4)] = 0;
    rgba.copy(brut, y * (1 + w * 4) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const t = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const x of buf) c = t[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  fs.writeFileSync(fichier, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]));
}

// ------------------------------------------------------------------ le modèle du joueur (bras larges)
// [nom, boîte x0 y0 z0 x1 y1 z1, texture de base (u,v), texture de la 2e couche, gonflement de la 2e couche, pivot, rotation z]
const PARTIES = [
  ["tete", [-4, 24, -4, 4, 32, 4], [0, 0], [32, 0], 0.5],
  ["corps", [-4, 12, -2, 4, 24, 2], [16, 16], [16, 32], 0.25],
  ["brasD", [-8, 12, -2, -4, 24, 2], [40, 16], [40, 32], 0.25],
  ["brasG", [4, 12, -2, 8, 24, 2], [32, 48], [48, 48], 0.25],
  ["jambeD", [-4, 0, -2, 0, 12, 2], [0, 16], [0, 32], 0.25],
  ["jambeG", [0, 0, -2, 4, 12, 2], [16, 48], [0, 48], 0.25],
];

/** Chaque texel d'une boîte : sa case dans la texture, ses 4 coins en 3D et sa normale. */
function texels(boite, uv, gonfle, R = 1) {
  let [x0, y0, z0, x1, y1, z1] = boite;
  const w = (x1 - x0) * R, h = (y1 - y0) * R, d = (z1 - z0) * R;
  x0 -= gonfle; y0 -= gonfle; z0 -= gonfle; x1 += gonfle; y1 += gonfle; z1 += gonfle;
  const sx = (x1 - x0) / w, sy = (y1 - y0) / h, sz = (z1 - z0) / d;
  const [u, v] = uv.map((c) => c * R);
  const faces = [
    { nom: "devant", tu: u + d, tv: v + d, tw: w, th: h, O: [x0, y1, z1], U: [sx, 0, 0], V: [0, -sy, 0], N: [0, 0, 1] },
    { nom: "dos", tu: u + d + w + d, tv: v + d, tw: w, th: h, O: [x1, y1, z0], U: [-sx, 0, 0], V: [0, -sy, 0], N: [0, 0, -1] },
    { nom: "droite", tu: u, tv: v + d, tw: d, th: h, O: [x0, y1, z0], U: [0, 0, sz], V: [0, -sy, 0], N: [-1, 0, 0] },
    { nom: "gauche", tu: u + d + w, tv: v + d, tw: d, th: h, O: [x1, y1, z1], U: [0, 0, -sz], V: [0, -sy, 0], N: [1, 0, 0] },
    { nom: "haut", tu: u + d, tv: v, tw: w, th: d, O: [x0, y1, z0], U: [sx, 0, 0], V: [0, 0, sz], N: [0, 1, 0] },
    { nom: "bas", tu: u + d + w, tv: v, tw: w, th: d, O: [x0, y0, z1], U: [sx, 0, 0], V: [0, 0, -sz], N: [0, -1, 0] },
  ];
  const l = [];
  for (const f of faces) {
    for (let j = 0; j < f.th; j++) {
      for (let i = 0; i < f.tw; i++) {
        const p = (a, b) => [f.O[0] + f.U[0] * a + f.V[0] * b, f.O[1] + f.U[1] * a + f.V[1] * b, f.O[2] + f.U[2] * a + f.V[2] * b];
        l.push({ tx: f.tu + i, ty: f.tv + j, coins: [p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], centre: p(i + 0.5, j + 0.5), N: f.N, face: f.nom });
      }
    }
  }
  return l;
}

// ------------------------------------------------------------------ la marque maudite
const get = (s, x, y) => { const i = (y * 64 + x) * 4; return [s.px[i], s.px[i + 1], s.px[i + 2], s.px[i + 3]]; };
const put = (s, x, y, c) => s.px.set(c.map((v) => Math.max(0, Math.min(255, Math.round(v)))), (y * 64 + x) * 4);
const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
const clair = (c, k) => c.map((v) => Math.min(255, v * k));

/** La couleur de peau : la plus fréquente sur le bas du visage (sous les yeux). */
function teint(s) {
  const n = new Map();
  for (let y = 13; y < 16; y++) for (let x = 8; x < 16; x++) {
    const k = get(s, x, y).slice(0, 3).join(",");
    n.set(k, (n.get(k) ?? 0) + 1);
  }
  return [...n].sort((a, b) => b[1] - a[1])[0][0].split(",").map(Number);
}

// un bruit doux en 3D (valeurs aléatoires lissées), pour des taches de flammes rondes et irrégulières
const hache = (i, j, k) => { let h = (i * 374761393 + j * 668265263 + k * 2147483647) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };
const lisse = (t) => t * t * (3 - 2 * t);
function bruit(x, y, z) {
  const i = Math.floor(x), j = Math.floor(y), k = Math.floor(z);
  const fx = lisse(x - i), fy = lisse(y - j), fz = lisse(z - k);
  let v = 0;
  for (let a = 0; a <= 1; a++) for (let b = 0; b <= 1; b++) for (let c = 0; c <= 1; c++) {
    v += hache(i + a, j + b, k + c) * (a ? fx : 1 - fx) * (b ? fy : 1 - fy) * (c ? fz : 1 - fz);
  }
  return v;
}

/** Le sceau : sur le côté gauche du cou. */
const SCEAU = [2.6, 22.6, 2.1];
const loin = (p) => Math.hypot(p[0] - SCEAU[0], (p[1] - SCEAU[1]) * 0.9, p[2] - SCEAU[2]);

/** Le motif complet : des flammes rondes, étirées vers le haut, qui couvrent à peu près 40 % de la peau. */
function motif(p) {
  const [x, y, z] = p;
  const f = bruit(x * 0.42 + 11, y * 0.3, z * 0.42 + 5) * 0.65 + bruit(x * 0.95, y * 0.75 + 3, z * 0.95 + 9) * 0.35;
  // des taches pleines, et autour des langues fines qui s'enroulent (le contour des flammes)
  return f > 0.6 || Math.abs(f - 0.5) < 0.028;
}

/**
 * Une étape de la transformation. avance : 0 (rien que le sceau) → 1 (la marque couvre tout) ; noirci : 0 (rouge) → 1 (noir).
 * Les deux se propagent depuis le sceau.
 */
function transformer(base, { torseNu, avance, noirci, yeux = true, avecPoints = true }) {
  const s = { px: Buffer.from(base.px) };
  const peau = teint(base);
  if (torseNu) {
    // le haut enlevé : le corps et les bras à la couleur du visage, sans la 2e couche (veste, manches)
    for (const [, , [u, v], [ou, ov]] of PARTIES.filter((p) => ["corps", "brasD", "brasG"].includes(p[0]))) {
      for (let y = v; y < v + 16; y++) for (let x = u; x < Math.min(64, u + 24); x++) put(s, x, y, [...clair(peau, 1 + (((x * 7 + y * 13) % 5) - 2) * 0.015), 255]);
      for (let y = ov; y < ov + 16; y++) for (let x = ou; x < Math.min(64, ou + 24); x++) put(s, x, y, [0, 0, 0, 0]);
    }
    // un peu de relief : le bas des pectoraux, les abdos
    for (const x of [21, 22, 25, 26]) put(s, x, 23, [...clair(peau, 0.9), 255]);
    for (const [x, y] of [[22, 25], [25, 25], [22, 27], [25, 27], [22, 29], [25, 29]]) put(s, x, y, [...clair(peau, 0.93), 255]);
    for (let y = 24; y <= 30; y++) put(s, 23, y, [...clair(peau, 0.95), 255]);
  }
  // les yeux rouges, dès que la marque s'éveille
  if (yeux && avance > 0.15) {
    for (const x of [9, 10, 13, 14]) {
      const c = get(s, x, 12);
      if (!(c[0] > 200 && c[1] > 200 && c[2] > 200) && dist(c, peau) > 40) put(s, x, 12, [205, 18, 22, 255]);
    }
  }
  if (!avecPoints) {
    return s;
  }
  // les marques : plein de petits points séparés (1 ou 2 pixels, jamais collés entre eux), toujours les mêmes d'une
  // étape à l'autre ; plus serrés près du sceau. Ils apparaissent en rouge depuis le sceau, puis deviennent noirs.
  const candidats = [];
  for (const [nom, boite, uv] of PARTIES) {
    for (const t of texels(boite, uv, 0)) {
      if (nom === "tete" && (t.face === "haut" || t.ty === 12 || t.centre[0] < -1)) continue;
      if (dist(get(s, t.tx, t.ty), peau) > 34) continue;
      candidats.push({ x: t.tx, y: t.ty, d: loin(t.centre), tirage: hache(t.tx, t.ty, 77) });
    }
  }
  const libre = new Set(candidats.map((c) => c.x + "," + c.y));
  const pris = new Set();
  const voisinsLibres = (x, y, sauf) => {
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      const k = x + dx + "," + (y + dy);
      if (k !== sauf && pris.has(k)) return false;
    }
    return true;
  };
  const points = [];
  for (const c of [...candidats].sort((a, b) => a.tirage - b.tirage)) {
    const densite = c.d < 1.4 ? 1 : c.d < 5 ? 0.42 : 0.24;
    if (hache(c.x, c.y, 13) > densite || !voisinsLibres(c.x, c.y, null)) continue;
    const point = [c.x + "," + c.y];
    pris.add(point[0]);
    // la moitié des points font 2 pixels (à côté, ou en dessous)
    if (hache(c.x, c.y, 5) < 0.5) {
      const [dx, dy] = hache(c.x, c.y, 9) < 0.5 ? [1, 0] : [0, 1];
      const k = c.x + dx + "," + (c.y + dy);
      if (libre.has(k) && voisinsLibres(c.x + dx, c.y + dy, point[0])) {
        pris.add(k);
        point.push(k);
      }
    }
    points.push({ pixels: point, d: c.d });
  }
  const rayon = avance * 34;
  const rayonNoir = noirci * 40;
  for (const p of points) {
    const sceau = p.d < 1.4;
    if (!sceau && p.d > rayon + 1.5 * Math.sin(p.d * 2.1)) continue;
    const noir = sceau || p.d < rayonNoir;
    for (const k of p.pixels) {
      const [x, y] = k.split(",").map(Number);
      put(s, x, y, noir ? [16, 10, 12, 255] : [232, 52, 22, 255]);
    }
  }
  return s;
}

// ------------------------------------------------------------------ la marque en HD : un calque collé à la peau, 4 fois plus fin
const R = 4;
const T = 64 * R;
const ROUGE_PLEIN = [226, 46, 20, 255], ROUGE_BORD = [96, 12, 8, 255], NOIR = [14, 9, 11, 255];
const norme = (v) => { const l = Math.hypot(...v) || 1; return v.map((c) => c / l); };
const croix = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const tourner = (v, n, ang) => {
  // rotation de v autour de l'axe n (Rodrigues)
  const c = Math.cos(ang), s = Math.sin(ang), k = croix(n, v), d = n[0] * v[0] + n[1] * v[1] + n[2] * v[2];
  return v.map((x, i) => x * c + k[i] * s + n[i] * d * (1 - c));
};

/** Une flamme : une tête ronde et une queue qui s'effile en s'enroulant, posée à plat sur la peau. */
function flamme(tete, N, { r0, longueur, enroule, dir }) {
  const ech = [];
  let p = tete, d = dir;
  const n = 14;
  for (let i = 0; i <= n; i++) {
    const t = i / n;
    ech.push({ p, r: r0 * Math.pow(1 - t, 1.15) + 0.04, t });
    d = tourner(d, N, enroule / n);
    p = p.map((x, k) => x + d[k] * (longueur / n));
  }
  return ech;
}

/** Les flammes de la marque (toujours les mêmes pour un skin) : posées sur la peau, plus serrées près du sceau. */
function flammes(skin, peau) {
  const candidats = [];
  for (const [nom, boite, uv] of PARTIES) {
    if (!["tete", "corps", "brasD", "brasG", "jambeD", "jambeG"].includes(nom)) continue;
    for (const t of texels(boite, uv, 0, 2)) {
      if (nom === "tete" && (t.face === "haut" || t.face === "bas" || t.centre[0] < -0.5)) continue;
      if (dist(get(skin, Math.floor(t.tx / 2), Math.floor(t.ty / 2)), peau) > 34) continue;
      candidats.push({ t, tirage: hache(t.tx, t.ty, 31) });
    }
  }
  candidats.sort((a, b) => a.tirage - b.tirage);
  const l = [];
  for (const { t } of candidats) {
    const d = loin(t.centre);
    const ecart = d < 5 ? 1.15 : 1.45;
    if (d < 1.6 || l.some((f) => Math.hypot(...f.tete.map((x, k) => x - t.centre[k])) < ecart)) continue;
    const h = (k) => hache(t.tx * 7 + k, t.ty * 13 + k, 3);
    let haut = [0, 1, 0].map((x, k) => x - t.N[k] * t.N[1]);
    if (Math.hypot(...haut) < 0.2) haut = croix(t.N, [0, 0, 1]);
    const dir = tourner(norme(haut), t.N, (h(1) - 0.5) * 1.6);
    const tete = t.centre.map((x, k) => x + (h(2) - 0.5) * 0.6 * (k === 1 ? 1 : Math.abs(t.N[k]) < 0.5 ? 1 : 0));
    l.push({ tete, d, N: t.N, ech: flamme(tete, t.N, { r0: 0.3 + h(3) * 0.18, longueur: 0.7 + h(4) * 0.7, enroule: (h(5) < 0.5 ? -1 : 1) * (1.2 + h(6) * 1.3), dir }) });
  }
  // le sceau : trois virgules (tomoe) qui tournent autour d'un point, sur le côté gauche du cou
  const N = [0, 0, 1];
  for (let k = 0; k < 3; k++) {
    const a = (k * 2 * Math.PI) / 3;
    const tete = [SCEAU[0] + Math.cos(a) * 0.62, SCEAU[1] + Math.sin(a) * 0.62, 2];
    const dir = [-Math.sin(a), Math.cos(a), 0];
    l.push({ tete, d: 0, sceau: true, N, ech: flamme(tete, N, { r0: 0.3, longueur: 0.85, enroule: 1.6, dir }) });
  }
  return l;
}

/** Le calque (256 x 256, comme la peau mais 4 fois plus fin) à une étape de l'éveil. */
function calque(skin, peau, fl, { avance, noirci }) {
  const px = Buffer.alloc(T * T * 4);
  const marque = new Uint8Array(T * T);
  const rayon = avance * 34, rayonNoir = noirci * 40;
  for (const [nom, boite, uv] of PARTIES) {
    if (!["tete", "corps", "brasD", "brasG", "jambeD", "jambeG"].includes(nom)) continue;
    for (const t of texels(boite, uv, 0, R)) {
      if (dist(get(skin, Math.floor(t.tx / R), Math.floor(t.ty / R)), peau) > 34) continue;
      for (const f of fl) {
        const pousse = f.sceau ? 1 : Math.min(1, Math.max(0, (rayon - f.d) / 2.5));
        if (pousse <= 0) continue;
        if (Math.hypot(...f.tete.map((x, k) => x - t.centre[k])) > 4.2) continue;
        // seulement sur la face où la flamme est posée, ou sur une face voisine (elle passe l'arête)
        if (f.N[0] * t.N[0] + f.N[1] * t.N[1] + f.N[2] * t.N[2] < -0.5) continue;
        const dedans = f.ech.some((e) => e.t <= pousse && Math.hypot(...e.p.map((x, k) => x - t.centre[k])) < e.r * Math.min(1, 0.45 + pousse));
        if (dedans) {
          marque[t.ty * T + t.tx] = f.sceau || f.d < rayonNoir ? 2 : 1;
          break;
        }
      }
    }
  }
  for (let y = 0; y < T; y++) for (let x = 0; x < T; x++) {
    const m = marque[y * T + x];
    if (!m) continue;
    let c = NOIR;
    if (m === 1) {
      const bord = [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([dx, dy]) => !marque[(y + dy) * T + x + dx]);
      c = bord ? ROUGE_BORD : ROUGE_PLEIN;
    }
    px.set(c, (y * T + x) * 4);
  }
  return px;
}

// ------------------------------------------------------------------ le rendu
function rendre(skin, { lacet = -28, tangage = 12, echelle = 11, w = 300, h = 500, fond = [235, 235, 240], aura = null, calque = null, pied = null }) {
  const img = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const k = y / h;
    img.set([fond[0] * (1 - k * 0.25), fond[1] * (1 - k * 0.25), fond[2] * (1 - k * 0.25), 255].map(Math.round), (y * w + x) * 4);
  }
  if (aura) {
    // l'aura de la marque : une lueur violette sombre derrière lui
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const d = Math.hypot((x - w / 2) / 0.6, y - h * 0.47) / (h * 0.45);
      if (d < 1) {
        const a = (1 - d) ** 1.6 * 0.75, i = (y * w + x) * 4;
        img[i] = img[i] * (1 - a) + aura[0] * a; img[i + 1] = img[i + 1] * (1 - a) + aura[1] * a; img[i + 2] = img[i + 2] * (1 - a) + aura[2] * a;
      }
    }
  }
  const a = (lacet * Math.PI) / 180, b = (tangage * Math.PI) / 180;
  const vue = ([x, y, z]) => {
    const x1 = x * Math.cos(a) + z * Math.sin(a), z1 = -x * Math.sin(a) + z * Math.cos(a);
    const y2 = y * Math.cos(b) - z1 * Math.sin(b), z2 = y * Math.sin(b) + z1 * Math.cos(b);
    return [w / 2 + x1 * echelle, (pied ?? h * 0.9) - y2 * echelle, z2];
  };
  const quads = [];
  const lumiere = { devant: 0.98, dos: 0.72, droite: 0.8, gauche: 0.86, haut: 1.06, bas: 0.55 };
  for (const [, boite, uv, uv2, gonfle] of PARTIES) {
    for (const [couche, g] of [[uv, 0], [uv2, gonfle]]) {
      for (const t of texels(boite, couche, g)) {
        const c = get(skin, t.tx, t.ty);
        if (c[3] < 128) continue;
        const n = vue([t.N[0], t.N[1], t.N[2]]);
        const o = vue([0, 0, 0]);
        if (n[2] - o[2] < 0.01) continue;
        const pts = t.coins.map(vue);
        quads.push({ pts, z: vue(t.centre)[2] + (g ? 0.01 : 0), c: c.map((v, k) => (k < 3 ? Math.min(255, v * lumiere[t.face]) : 255)) });
      }
    }
  }
  if (calque) {
    // le calque HD de la marque, collé juste au-dessus de la peau
    for (const [nom, boite, uv] of PARTIES) {
      if (!["tete", "corps", "brasD", "brasG", "jambeD", "jambeG"].includes(nom)) continue;
      for (const t of texels(boite, uv, 0.03, R)) {
        const i = (t.ty * T + t.tx) * 4;
        if (calque[i + 3] < 128) continue;
        const n = vue(t.N), o = vue([0, 0, 0]);
        if (n[2] - o[2] < 0.01) continue;
        quads.push({ pts: t.coins.map(vue), z: vue(t.centre)[2] + 0.02, c: [0, 1, 2].map((k) => Math.min(255, calque[i + k] * lumiere[t.face])).concat(255) });
      }
    }
  }
  quads.sort((p, q) => p.z - q.z);
  for (const q of quads) {
    const cx = q.pts.reduce((s, p) => s + p[0], 0) / 4, cy = q.pts.reduce((s, p) => s + p[1], 0) / 4;
    const pts = q.pts.map(([x, y]) => [cx + (x - cx) * 1.06, cy + (y - cy) * 1.06]);
    const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
    for (let y = Math.max(0, Math.floor(Math.min(...ys))); y <= Math.min(h - 1, Math.ceil(Math.max(...ys))); y++) {
      for (let x = Math.max(0, Math.floor(Math.min(...xs))); x <= Math.min(w - 1, Math.ceil(Math.max(...xs))); x++) {
        let dedans = true, signe = 0;
        for (let k = 0; k < 4 && dedans; k++) {
          const [ax, ay] = pts[k], [bx, by] = pts[(k + 1) % 4];
          const e = (bx - ax) * (y + 0.5 - ay) - (by - ay) * (x + 0.5 - ax);
          if (e !== 0) { const sg = Math.sign(e); if (signe === 0) signe = sg; else if (sg !== signe) dedans = false; }
        }
        if (dedans) img.set(q.c, (y * w + x) * 4);
      }
    }
  }
  return { w, h, img };
}

// ------------------------------------------------------------------ la police 3 x 5 (pour les légendes)
const POLICE = {
  A: "010101111101101", B: "110101110101110", C: "011100100100011", D: "110101101101110", E: "111100110100111", F: "111100110100100",
  G: "011100101101011", H: "101101111101101", I: "111010010010111", J: "001001001101010", K: "101101110101101", L: "100100100100111",
  M: "101111111101101", N: "110101101101101", O: "010101101101010", P: "110101110100100", Q: "010101101110011", R: "110101110101101",
  S: "011100010001110", T: "111010010010010", U: "101101101101111", V: "101101101101010", W: "101101111111101", X: "101101010101101",
  Y: "101101010010010", Z: "111001010100111", " ": "000000000000000", 1: "010110010010111", 2: "110001010100111", 3: "110001010001110",
};
function ecrireTexte(img, W, texte, x0, y0, t, couleur) {
  let x = x0;
  for (const ch of texte.toUpperCase()) {
    const m = POLICE[ch] ?? POLICE[" "];
    for (let k = 0; k < 15; k++) if (m[k] === "1") {
      for (let dy = 0; dy < t; dy++) for (let dx = 0; dx < t; dx++) img.set(couleur, ((y0 + Math.floor(k / 3) * t + dy) * W + x + (k % 3) * t + dx) * 4);
    }
    x += 4 * t;
  }
}

// ------------------------------------------------------------------ la planche, et l'animation (calque HD)
/** Un skin en short : le bas des jambes à la couleur du visage (les chaussures restent). */
function enShort(base) {
  const s = { w: 64, h: 64, px: Buffer.from(base.px) };
  const peau = teint(base);
  for (const [nom, boite, uv, uv2] of PARTIES) {
    if (!nom.startsWith("jambe")) continue;
    for (const t of texels(boite, uv, 0)) {
      if (t.centre[1] < 6.5 && t.centre[1] > 1.5 && t.face !== "haut" && t.face !== "bas") put(s, t.tx, t.ty, [...clair(peau, 0.97), 255]);
    }
    for (const t of texels(boite, uv2, 0)) {
      if (t.centre[1] < 6.5) put(s, t.tx, t.ty, [0, 0, 0, 0]);
    }
  }
  return s;
}
const SKINS = [["steve", "assets/minecraft/textures/entity/player/wide/steve.png"], ["steve en short", "assets/minecraft/textures/entity/player/wide/steve.png", enShort], ["zuri", "assets/minecraft/textures/entity/player/wide/zuri.png"],
  ["kai", "assets/minecraft/textures/entity/player/wide/kai.png"], ["goku", "goku_base.png"]];
const ROUGE = [120, 20, 10], VIOLET = [70, 10, 90];
/** Une étape : le skin torse nu (sans les petits points) et son calque HD. */
function etape(base, avance, noirci) {
  const skin = transformer(base, { torseNu: true, avance: Math.max(avance, 0.2), noirci, avecPoints: false, yeux: avance > 0.15 });
  const peau = teint(base);
  return { skin, calque: calque(skin, peau, flammes(skin, peau), { avance, noirci }) };
}
const COLONNES = [
  ["NORMAL", (s) => ({ skin: s }), {}],
  ["LE SCEAU", (s) => etape(s, 0, 0), {}],
  ["PROPAGATION", (s) => etape(s, 0.3, 0), { aura: ROUGE }],
  ["ROUGE PARTOUT", (s) => etape(s, 1, 0), { aura: ROUGE }],
  ["NOIR FINAL", (s) => etape(s, 1, 1), { aura: VIOLET }],
  ["DE DOS", (s) => etape(s, 1, 1), { aura: VIOLET, lacet: 152 }],
  ["GROS PLAN", (s) => etape(s, 1, 0.55), { aura: VIOLET, echelle: 22, pied: 680, lacet: -18 }],
];
const CW = 300, CH = 500, MARGE = 40;
const W = CW * COLONNES.length, H = MARGE + CH * SKINS.length;
const planche = Buffer.alloc(W * H * 4, 255);
COLONNES.forEach(([titre], c) => ecrireTexte(planche, W, titre, c * CW + 12, 12, 3, [30, 30, 40, 255]));
SKINS.forEach(([, fichier, retouche], r) => {
  const base = retouche ? retouche(lire(fichier)) : lire(fichier);
  COLONNES.forEach(([, f, opts], c) => {
    const e = f(base);
    const { img } = rendre(e.skin, { ...opts, calque: e.calque, w: CW, h: CH });
    for (let y = 0; y < CH; y++) img.copy(planche, ((MARGE + r * CH + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
  });
});
ecrirePng("planche-hd.png", W, H, planche);

if (process.argv.includes("anim")) {
  fs.mkdirSync("images-hd", { recursive: true });
  const base = enShort(lire(SKINS[0][1]));
  // à 20 images/s : 0,4 s de sceau, 4 s de propagation en rouge, 0,6 s tout rouge, 2,4 s pour passer au noir
  const N = 148;
  for (let i = 0; i < N; i++) {
    const t = i / (N - 1);
    const sec = i / 20;
    const avance = Math.min(1, Math.max(0, (sec - 0.4) / 4));
    const noirci = Math.min(1, Math.max(0, (sec - 5) / 2.4));
    const aura = avance === 0 ? null : noirci > 0.5 ? VIOLET : ROUGE;
    const e = etape(base, avance, noirci);
    const { img } = rendre(e.skin, { calque: e.calque, w: 480, h: 720, echelle: 17, lacet: -40 + t * 30, aura });
    ecrirePng(`images-hd/${String(i).padStart(3, "0")}.png`, 480, 720, img);
  }
}
console.log("ok", W, H);
