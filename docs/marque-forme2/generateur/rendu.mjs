// Maquettes de la marque maudite, forme 2 (ailes en forme de mains, peau grise, cheveux gris et longs, croix sur le nez).
// node rendu.mjs        → planche-forme2.png
// node rendu.mjs anim   → images/NNN.png (pour le GIF de la transformation)
import fs from "node:fs";
import zlib from "node:zlib";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));

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
      if (type === 3) { const i = cur[x]; r = pal[i * 3]; g = pal[i * 3 + 1]; bl = pal[i * 3 + 2]; al = trns && i < trns.length ? trns[i] : 255; }
      else if (type === 6) [r, g, bl, al] = [cur[x * 4], cur[x * 4 + 1], cur[x * 4 + 2], cur[x * 4 + 3]];
      else if (type === 2) [r, g, bl] = [cur[x * 3], cur[x * 3 + 1], cur[x * 3 + 2]];
      else { r = g = bl = cur[x * canaux]; if (type === 4) al = cur[x * 2 + 1]; }
      px.set([r, g, bl, al], (y * w + x) * 4);
    }
    prec = cur;
  }
  return { w, h, px };
}
function ecrirePng(fichier, w, h, rgba) {
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) rgba.copy(brut, y * (1 + w * 4) + 1, y * w * 4, (y + 1) * w * 4);
  const t = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const x of buf) c = t[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  fs.writeFileSync(fichier, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]));
}

// ------------------------------------------------------------------ le modèle du joueur
const PARTIES = (fin) => [
  ["tete", [-4, 24, -4, 4, 32, 4], [0, 0], [32, 0], 0.5],
  ["corps", [-4, 12, -2, 4, 24, 2], [16, 16], [16, 32], 0.25],
  ["brasD", fin ? [-7, 12, -2, -4, 24, 2] : [-8, 12, -2, -4, 24, 2], [40, 16], [40, 32], 0.25],
  ["brasG", fin ? [4, 12, -2, 7, 24, 2] : [4, 12, -2, 8, 24, 2], [32, 48], [48, 48], 0.25],
  ["jambeD", [-4, 0, -2, 0, 12, 2], [0, 16], [0, 32], 0.25],
  ["jambeG", [0, 0, -2, 4, 12, 2], [16, 48], [0, 48], 0.25],
];

function texels(boite, uv, gonfle, R = 1) {
  let [x0, y0, z0, x1, y1, z1] = boite;
  const w = Math.round((x1 - x0) * R), h = Math.round((y1 - y0) * R), d = Math.round((z1 - z0) * R);
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
    for (let j = 0; j < f.th; j++) for (let i = 0; i < f.tw; i++) {
      const p = (a, b) => [f.O[0] + f.U[0] * a + f.V[0] * b, f.O[1] + f.U[1] * a + f.V[1] * b, f.O[2] + f.U[2] * a + f.V[2] * b];
      l.push({ tx: f.tu + i, ty: f.tv + j, coins: [p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], centre: p(i + 0.5, j + 0.5), N: f.N, face: f.nom });
    }
  }
  return l;
}

// ------------------------------------------------------------------ couleurs
const get = (s, x, y) => { const i = (y * 64 + x) * 4; return [s.px[i], s.px[i + 1], s.px[i + 2], s.px[i + 3]]; };
const put = (s, x, y, c) => s.px.set(c.map((v) => Math.max(0, Math.min(255, Math.round(v)))), (y * 64 + x) * 4);
const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
const clair = (c, k) => c.slice(0, 3).map((v) => Math.min(255, v * k));
const mix = (a, b, k) => a.map((v, i) => v + (b[i] - v) * k);
const lum = (c) => 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2];
const lisse = (t) => { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); };
const hache = (i, j, k) => { let h = (i * 374761393 + j * 668265263 + k * 2147483647) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };

function teint(s) {
  const n = new Map();
  for (let y = 13; y < 16; y++) for (let x = 8; x < 16; x++) { const k = get(s, x, y).slice(0, 3).join(","); n.set(k, (n.get(k) ?? 0) + 1); }
  return [...n].sort((a, b) => b[1] - a[1])[0][0].split(",").map(Number);
}

const TSHIRT = [32, 38, 74], SHORT = [226, 220, 204];
/** La peau grise de la forme 2, les cheveux gris-bleu, les ailes. */
/** La peau de la forme 2 : beige pâle, un peu terreux. Les ailes : os beige, membrane brun terre. */
const GRIS = [184, 156, 130], CHEVEUX = [150, 160, 182], NOIR = [16, 11, 13];

// ------------------------------------------------------------------ la tenue (comme en jeu, forme 1) ou torse nu (forme 2)
function col(t) {
  const i = t.centre[0] + 3.5, j = 23.5 - t.centre[1];
  return t.face === "devant" && j <= 3 && Math.abs(i - 4.5) <= 3.5 - 0.9 * j;
}
const tissu = (c, t, ourlet) => clair(c, (ourlet ? 0.78 : 1) * (1 + (hache(t.tx, t.ty, 7) - 0.5) * 0.08));

function habiller(base, fin, tshirt) {
  const s = { px: Buffer.from(base.px) };
  const peau = teint(base);
  for (const [nom, boite, uv, uv2] of PARTIES(fin).slice(1)) {
    const jambe = nom.startsWith("jambe");
    for (const t of texels(boite, uv, 0)) {
      if (jambe && t.centre[1] < 2) continue;
      let c = clair(peau, 1 + (((t.tx * 7 + t.ty * 13) % 5) - 2) * 0.015);
      if (tshirt && nom === "corps" && !col(t)) c = tissu(TSHIRT, t, t.centre[1] < 13);
      else if (tshirt && nom.startsWith("bras") && (t.centre[1] > 20 || t.face === "haut")) c = tissu(TSHIRT, t, t.centre[1] < 21);
      else if (jambe && t.centre[1] > 6) c = tissu(SHORT, t, t.centre[1] < 7);
      put(s, t.tx, t.ty, [...c, 255]);
    }
    for (const t of texels(boite, uv2, 0)) if (!jambe || t.centre[1] >= 2) put(s, t.tx, t.ty, [0, 0, 0, 0]);
  }
  if (tshirt) {
    const rouge = [35, 36, 34, 35, 36, 37], blanc = [34, 35, 36, 37, 35, 36];
    for (let k = 0; k < 6; k++) {
      put(s, rouge[k], k < 2 ? 22 : 23, [190, 32, 30, 255]);
      put(s, blanc[k], k < 4 ? 24 : 25, [232, 230, 226, 255]);
    }
    put(s, 35, 26, [150, 148, 156, 255]);
    put(s, 36, 26, [150, 148, 156, 255]);
  } else {
    // torse nu : le bas des pectoraux, les abdos
    for (const x of [21, 22, 25, 26]) put(s, x, 23, [...clair(peau, 0.9), 255]);
    for (const [x, y] of [[22, 25], [25, 25], [22, 27], [25, 27], [22, 29], [25, 29]]) put(s, x, y, [...clair(peau, 0.93), 255]);
    for (let y = 24; y <= 30; y++) put(s, 23, y, [...clair(peau, 0.95), 255]);
  }
  return s;
}

// ------------------------------------------------------------------ la forme 2 sur la peau : gris, cheveux, yeux
// les yeux du skin de Sasuke : deux carrés de 2 x 2 ; l iris, le pixel du coin intérieur en haut
const YEUX = [[9, 12], [10, 12], [9, 13], [10, 13], [13, 12], [14, 12], [13, 13], [14, 13]];
const IRIS = [[10, 12], [13, 12]];
const estOeil = (t) => t.face === "devant" && YEUX.some(([x, y]) => x === t.tx && y === t.ty);
/** Une teinte de peau : claire et chaude. */
const tonPeau = (c) => lum(c) > 110 && c[0] >= c[1] && c[0] - c[2] > 15;
/** Ce qui est cheveux sur la tête : tout ce qui n a ni une teinte de peau, ni la place d un œil. */
function estCheveu(t, c, couche2) {
  if (c[3] < 128) return false;
  return couche2 || (!estOeil(t) && !tonPeau(c));
}

function transformer(s0, base, fin, e) {
  const s = { px: Buffer.from(s0.px) };
  const peau = teint(base);
  // la peau change de couleur (en gardant ses nuances) : tout le corps, et tout le visage, bouche comprise (sauf les yeux et les cheveux)
  if (e.gris > 0) {
    for (const [nom, boite, uv] of PARTIES(fin)) {
      for (const t of texels(boite, uv, 0)) {
        const c = get(s, t.tx, t.ty);
        if (nom === "tete") {
          if (estCheveu(t, get(s0, t.tx, t.ty), false) || estOeil(t)) continue;
        } else if (dist(c, peau) > 34) continue;
        const bord = boite[1] === 12 && t.face === "devant" && (Math.abs(t.centre[0] - boite[0] - 0.5) < 0.01 || Math.abs(boite[3] - t.centre[0] - 0.5) < 0.01);
        const g = clair(GRIS, Math.max(0.5, Math.min(1.2, lum(c) / Math.max(1, lum(peau)))) * (bord ? 0.86 : 1));
        put(s, t.tx, t.ty, [...mix(c.slice(0, 3), g, e.gris), 255]);
      }
    }
  }
  // les cheveux : gris-bleu
  if (e.cheveux > 0) {
    const [, boite, uv, uv2] = PARTIES(fin)[0];
    const l = [];
    for (const [couche, c2] of [[uv, false], [uv2, true]]) {
      for (const t of texels(boite, couche, 0)) {
        const c = get(s0, t.tx, t.ty);
        if (estCheveu(t, c, c2)) l.push([t, c]);
      }
    }
    const m = l.reduce((a, [, c]) => a + lum(c), 0) / Math.max(1, l.length);
    for (const [t, c] of l) {
      const k = Math.max(0.72, Math.min(1.25, 1 + ((lum(c) - m) / (m + 25)) * 0.9));
      put(s, t.tx, t.ty, [...mix(c.slice(0, 3), clair(CHEVEUX, k), e.cheveux), c[3]]);
    }
  }
  // les yeux : l'iris rouge dès la forme 1 ; avec la forme 2, tout l'œil devient noir, sauf ce pixel rouge
  for (const [x, y] of YEUX) {
    const iris = IRIS.some(([ix, iy]) => ix === x && iy === y);
    if (iris) put(s, x, y, [205, 18, 22, 255]);
    else if (e.cheveux > 0.6) put(s, x, y, [24, 18, 22, 255]);
  }
  return s;
}

// ------------------------------------------------------------------ les flammes de la forme 1 (comme les maquettes validées)
const SCEAU = [2.6, 22.6, 2.1];
const loin = (p) => Math.hypot(p[0] - SCEAU[0], (p[1] - SCEAU[1]) * 0.9, p[2] - SCEAU[2]);
const norme = (v) => { const l = Math.hypot(...v) || 1; return v.map((c) => c / l); };
const croix = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const tourner = (v, n, ang) => { const c = Math.cos(ang), s = Math.sin(ang), k = croix(n, v), d = n[0] * v[0] + n[1] * v[1] + n[2] * v[2]; return v.map((x, i) => x * c + k[i] * s + n[i] * d * (1 - c)); };
function flamme(tete, N, { r0, longueur, enroule, dir }) {
  const ech = []; let p = tete, d = dir;
  for (let i = 0; i <= 14; i++) { const t = i / 14; ech.push({ p, r: r0 * Math.pow(1 - t, 1.15) + 0.04, t }); d = tourner(d, N, enroule / 14); p = p.map((x, k) => x + d[k] * (longueur / 14)); }
  return ech;
}
function flammes(skin, peau, fin) {
  const candidats = [];
  for (const [nom, boite, uv] of PARTIES(fin)) {
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
  for (let k = 0; k < 3; k++) {
    const a = (k * 2 * Math.PI) / 3;
    const tete = [SCEAU[0] + Math.cos(a) * 0.62, SCEAU[1] + Math.sin(a) * 0.62, 2];
    l.push({ tete, d: 0, sceau: true, N: [0, 0, 1], ech: flamme(tete, [0, 0, 1], { r0: 0.3, longueur: 0.85, enroule: 1.6, dir: [-Math.sin(a), Math.cos(a), 0] }) });
  }
  return l;
}

const R = 4, T = 64 * R;
/** Les marques noires de la fin de la forme 1 (sur tout le corps, même sous le t-shirt), et leur distance aux texels de peau. */
function marques(nu, peau, fin) {
  const fl = flammes(nu, peau, fin);
  const marque = new Uint8Array(T * T);
  const peauHD = new Uint8Array(T * T);
  for (const [, boite, uv] of PARTIES(fin)) {
    for (const t of texels(boite, uv, 0, R)) {
      if (dist(get(nu, Math.floor(t.tx / R), Math.floor(t.ty / R)), peau) > 34) continue;
      peauHD[t.ty * T + t.tx] = 1;
      for (const f of fl) {
        if (Math.hypot(...f.tete.map((x, k) => x - t.centre[k])) > 2) continue;
        if (f.N[0] * t.N[0] + f.N[1] * t.N[1] + f.N[2] * t.N[2] < -0.5) continue;
        if (f.ech.some((e) => Math.hypot(...e.p.map((x, k) => x - t.centre[k])) < e.r)) { marque[t.ty * T + t.tx] = 1; break; }
      }
    }
  }
  // la distance (en texels fins) de chaque texel de peau à la marque la plus proche : les marques s'étalent en noir
  const D = new Float32Array(T * T).fill(1e9);
  const file = [];
  for (let i = 0; i < T * T; i++) if (marque[i]) { D[i] = 0; file.push(i); }
  for (let q = 0; q < file.length; q++) {
    const i = file[q], x = i % T, y = (i / T) | 0;
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const nx = x + dx, ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= T || ny >= T) continue;
      const j = ny * T + nx;
      if (peauHD[j] && D[j] > D[i] + 1) { D[j] = D[i] + 1; file.push(j); }
    }
  }
  return { marque, D, peauHD };
}

/**
 * Le calque fin : les marques noires qui s'étalent (noir 0 → 1) puis pâlissent en gris (gris 0 → 1), ne laissant que leur
 * trace ; la croix sur le nez (croixNez 0 → 1).
 */
function calque(m, skin, peau, e) {
  const px = Buffer.alloc(T * T * 4);
  for (let i = 0; i < T * T; i++) {
    if (!m.peauHD[i]) continue;
    const x = i % T, y = (i / T) | 0;
    const c = get(skin, (x / R) | 0, (y / R) | 0);
    if (c[3] < 128 || dist(c, mix(peau, GRIS, e.gris)) > 40) continue;
    const etale = m.D[i] <= e.noir * 9;
    if (!etale) continue;
    const g = clair(GRIS, lum(c) / Math.max(1, lum(mix(peau, GRIS, e.gris))));
    const k = m.D[i] === 0 ? e.gris * 0.93 : e.gris;
    px.set([...mix(NOIR, g, k).map(Math.round), 255], i * 4);
  }
  if (e.croixNez > 0) {
    // une étoile à quatre branches, sur le nez, juste sous les yeux, plus large que haute
    const cx = 12 * R, cy = 14 * R, a = 7.5 * e.croixNez, b = 5.5 * e.croixNez;
    for (let y = Math.floor(cy - b); y <= Math.ceil(cy + b); y++) for (let x = Math.floor(cx - a); x <= Math.ceil(cx + a); x++) {
      const u = Math.abs(x + 0.5 - cx) / a, v = Math.abs(y + 0.5 - cy) / b;
      if (Math.sqrt(u) + Math.sqrt(v) <= 1) px.set([20, 14, 16, 255], (y * T + x) * 4);
    }
  }
  return px;
}

// ------------------------------------------------------------------ l'aile : une main géante, palmée
// L'aile dessinée par l'utilisateur (skins/aile.png) : l'aile gauche vue de dos, le poignet contre le dos sur le bord
// droit de l'image, le pouce vers la gauche, les doigts vers le haut. Elle compte 40 points de l'image par pixel du
// modèle ; un texel fait 1/6 de pixel, à peu près la taille des pixels du dessin. Le blanc du fond et des déchirures
// devient transparent, comme le liseré gris clair que le dessin laisse autour du trait.
const AILE_T = 32, AILE_R = 6, AILE_N = AILE_T * AILE_R;
const AILE_POINTS = 40, AILE_DOS = 1194, AILE_PIED = 1250;
const versAile = ([x, y]) => [(AILE_DOS - x) / AILE_POINTS, (AILE_PIED - y) / AILE_POINTS];
const RACINE = versAile([1150, 720]);
const ENCRE_AILE = [44, 28, 28];
// l'épaisseur, en pixels du modèle (avant l'échelle de l'aile) : une demi-épaisseur de BORD_AILE sur le pourtour, qui
// monte doucement jusqu'à BORD_AILE + BOSSE_AILE à ARRONDI_AILE du bord, la plus forte à la paume et 40 % moindre
// au bout des doigts ; elle s'affine contre le dos, pour laisser voir la tête
const BORD_AILE = 0.3, BOSSE_AILE = 1.6, ARRONDI_AILE = 3.5;
const PAUME_AILE = versAile([950, 640]);

/** L'aile : sa texture, et sa demi-épaisseur en chaque coin de texel. */
function texAile() {
  const img = lire(path.join(ICI, "skins", "aile.png"));
  const tex = Buffer.alloc(AILE_N * AILE_N * 4);
  for (let y = 0; y < AILE_N; y++) for (let x = 0; x < AILE_N; x++) {
    const ix = Math.round(AILE_DOS - ((x + 0.5) / AILE_R) * AILE_POINTS), iy = Math.round(AILE_PIED - ((y + 0.5) / AILE_R) * AILE_POINTS);
    if (ix < 0 || iy < 0 || ix >= img.w || iy >= img.h) continue;
    const i = (iy * img.w + ix) * 4;
    const c = [img.px[i], img.px[i + 1], img.px[i + 2]];
    const gris = Math.max(...c) - Math.min(...c) < 30, l = lum(c);
    if (img.px[i + 3] < 128 || Math.min(...c) >= 225 || (gris && l > 140)) continue;
    // le gris moyen du liseré rejoint le trait
    tex.set([...(gris && l > 60 ? ENCRE_AILE : c), 255], (y * AILE_N + x) * 4);
  }
  const plein = (x, y) => x >= 0 && y >= 0 && x < AILE_N && y < AILE_N && tex[(y * AILE_N + x) * 4 + 3] > 0;
  // les texels isolés, sans voisin plein : enlevés
  for (let y = 0; y < AILE_N; y++) for (let x = 0; x < AILE_N; x++) {
    if (plein(x, y) && !plein(x - 1, y) && !plein(x + 1, y) && !plein(x, y - 1) && !plein(x, y + 1)) tex.fill(0, (y * AILE_N + x) * 4, (y * AILE_N + x) * 4 + 4);
  }
  // l'aile sans ses déchirures : le vide qu'on n'atteint pas depuis le bord de l'image est un trou, compté comme plein,
  // pour que le volume passe dessus sans creux
  const dehors = new Uint8Array(AILE_N * AILE_N);
  const pile = [];
  for (let i = 0; i < AILE_N; i++) pile.push([i, 0], [i, AILE_N - 1], [0, i], [AILE_N - 1, i]);
  while (pile.length) {
    const [x, y] = pile.pop();
    if (x < 0 || y < 0 || x >= AILE_N || y >= AILE_N || dehors[y * AILE_N + x] || plein(x, y)) continue;
    dehors[y * AILE_N + x] = 1;
    pile.push([x + 1, y], [x - 1, y], [x, y + 1], [x, y - 1]);
  }
  // la distance de chaque texel au bord (en texels), en deux passes
  const d = new Float32Array(AILE_N * AILE_N);
  const D = (x, y) => (x < 0 || y < 0 || x >= AILE_N || y >= AILE_N ? 0 : d[y * AILE_N + x]);
  for (let i = 0; i < AILE_N * AILE_N; i++) d[i] = dehors[i] ? 0 : 1e9;
  for (let y = 0; y < AILE_N; y++) for (let x = 0; x < AILE_N; x++) {
    if (d[y * AILE_N + x]) d[y * AILE_N + x] = Math.min(d[y * AILE_N + x], D(x - 1, y) + 1, D(x, y - 1) + 1, D(x - 1, y - 1) + Math.SQRT2, D(x + 1, y - 1) + Math.SQRT2);
  }
  for (let y = AILE_N - 1; y >= 0; y--) for (let x = AILE_N - 1; x >= 0; x--) {
    if (d[y * AILE_N + x]) d[y * AILE_N + x] = Math.min(d[y * AILE_N + x], D(x + 1, y) + 1, D(x, y + 1) + 1, D(x + 1, y + 1) + Math.SQRT2, D(x - 1, y + 1) + Math.SQRT2);
  }
  // la demi-épaisseur : bombée comme une main, montant doucement depuis les bords, la plus forte à la paume, plus fine
  // contre le dos
  let e = new Float32Array(AILE_N * AILE_N).fill(BORD_AILE);
  for (let y = 0; y < AILE_N; y++) for (let x = 0; x < AILE_N; x++) {
    const i = y * AILE_N + x;
    if (!d[i]) continue;
    const a = (x + 0.5) / AILE_R, b = (y + 0.5) / AILE_R;
    const k = Math.min(1, d[i] / AILE_R / ARRONDI_AILE);
    const paume = 1 - 0.4 * Math.min(1, Math.hypot(a - PAUME_AILE[0], b - PAUME_AILE[1]) / 18);
    const dos = Math.min(1, 0.3 + a / 6.7);
    e[i] = (BORD_AILE + BOSSE_AILE * Math.sin((k * Math.PI) / 2) * paume) * dos;
  }
  // lissée (trois flous en boîte d'un pixel du modèle de rayon), pour une surface sans bosses
  const RF = AILE_R;
  for (let passe = 0; passe < 3; passe++) for (const [px, py] of [[1, 0], [0, 1]]) {
    const f = new Float32Array(AILE_N * AILE_N);
    for (let y = 0; y < AILE_N; y++) for (let x = 0; x < AILE_N; x++) {
      let s = 0;
      for (let j = -RF; j <= RF; j++) {
        const nx = x + px * j, ny = y + py * j;
        s += nx < 0 || ny < 0 || nx >= AILE_N || ny >= AILE_N ? BORD_AILE : e[ny * AILE_N + nx];
      }
      f[y * AILE_N + x] = s / (2 * RF + 1);
    }
    e = f;
  }
  for (let i = 0; i < AILE_N * AILE_N; i++) if (dehors[i]) e[i] = Math.min(e[i], BORD_AILE);
  const L = AILE_N + 1;
  const epais = new Float32Array(L * L);
  const E = (x, y) => (x < 0 || y < 0 || x >= AILE_N || y >= AILE_N ? BORD_AILE : e[y * AILE_N + x]);
  for (let y = 0; y < L; y++) for (let x = 0; x < L; x++) epais[y * L + x] = (E(x - 1, y - 1) + E(x, y - 1) + E(x - 1, y) + E(x, y)) / 4;
  return { tex, epais };
}

// ------------------------------------------------------------------ le rendu (projection, tampon de profondeur)
function scene({ lacet = -28, tangage = 10, echelle = 9, w = 360, h = 520, pied = null, aura = null, fond = [236, 236, 242] }) {
  const img = Buffer.alloc(w * h * 4);
  const z = new Float32Array(w * h).fill(-1e9);
  const ailes = new Uint8Array(w * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const k = y / h;
    img.set([fond[0] * (1 - k * 0.25), fond[1] * (1 - k * 0.25), fond[2] * (1 - k * 0.25), 255].map(Math.round), (y * w + x) * 4);
  }
  if (aura) {
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const d = Math.hypot((x - w / 2) / 0.75, y - h * aura.y) / (h * aura.r);
      if (d < 1) {
        const a = (1 - d) ** 1.5 * aura.force, i = (y * w + x) * 4;
        for (let k = 0; k < 3; k++) img[i + k] = img[i + k] * (1 - a) + aura.c[k] * a;
      }
    }
  }
  const a = (lacet * Math.PI) / 180, b = (tangage * Math.PI) / 180;
  const vue = ([x, y, zz]) => {
    const x1 = x * Math.cos(a) + zz * Math.sin(a), z1 = -x * Math.sin(a) + zz * Math.cos(a);
    const y2 = y * Math.cos(b) - z1 * Math.sin(b), z2 = y * Math.sin(b) + z1 * Math.cos(b);
    return [w / 2 + x1 * echelle, (pied ?? h * 0.88) - y2 * echelle, z2];
  };
  const dirVue = (n) => { const o = vue([0, 0, 0]), p = vue(n); return [(p[0] - o[0]) / echelle, -(p[1] - o[1]) / echelle, p[2] - o[2]]; };
  /** Un quadrilatère plein, de couleur unie, à la profondeur de son centre. */
  const quad = (coins, c, decale = 0, aile = false) => {
    const pts = coins.map(vue);
    const zc = pts.reduce((s, p) => s + p[2], 0) / 4 + decale;
    const [p0, p1, p2] = Math.hypot(pts[1][0] - pts[0][0], pts[1][1] - pts[0][1]) > 1e-6 ? [pts[0], pts[1], pts[2]] : [pts[0], pts[2], pts[3]];
    const ux = p1[0] - p0[0], uy = p1[1] - p0[1], uz = p1[2] - p0[2], vx = p2[0] - p0[0], vy = p2[1] - p0[1], vz = p2[2] - p0[2];
    const nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    const prof = (x, y) => (Math.abs(nz) < 1e-9 ? zc : p0[2] - (nx * (x - p0[0]) + ny * (y - p0[1])) / nz + decale);
    const cx = pts.reduce((s, p) => s + p[0], 0) / 4, cy = pts.reduce((s, p) => s + p[1], 0) / 4;
    const q = pts.map(([x, y]) => [cx + (x - cx) * 1.08, cy + (y - cy) * 1.08]);
    const xs = q.map((p) => p[0]), ys = q.map((p) => p[1]);
    for (let y = Math.max(0, Math.floor(Math.min(...ys))); y <= Math.min(h - 1, Math.ceil(Math.max(...ys))); y++) {
      for (let x = Math.max(0, Math.floor(Math.min(...xs))); x <= Math.min(w - 1, Math.ceil(Math.max(...xs))); x++) {
        let dedans = true, signe = 0;
        for (let k = 0; k < 4 && dedans; k++) {
          const [ax, ay] = q[k], [bx, by] = q[(k + 1) % 4];
          const e = (bx - ax) * (y + 0.5 - ay) - (by - ay) * (x + 0.5 - ax);
          if (e !== 0) { const sg = Math.sign(e); if (signe === 0) signe = sg; else if (sg !== signe) dedans = false; }
        }
        const zp = dedans ? prof(x + 0.5, y + 0.5) : 0;
        if (dedans && zp > z[y * w + x]) { z[y * w + x] = zp; ailes[y * w + x] = aile ? 1 : 0; img.set([...c.map((v) => Math.max(0, Math.min(255, Math.round(v)))), 255], (y * w + x) * 4); }
      }
    }
  };
  const lumiere = (n, deuxFaces) => {
    const v = dirVue(n);
    if (!deuxFaces && v[2] < 0.01) return -1;
    const l = norme([-0.35, 0.55, 0.75]);
    const d = Math.abs(v[0] * l[0] + v[1] * l[1] + v[2] * l[2]);
    return 0.62 + 0.42 * d;
  };
  /** Une lueur autour des ailes, sur le fond seulement (comme le contour du Susano). */
  const lueur = (couleur, rayon, force) => {
    let m = Float32Array.from(ailes);
    for (let passe = 0; passe < 3; passe++) {
      const t = new Float32Array(w * h);
      for (let y = 0; y < h; y++) { let acc = 0; for (let x = -rayon; x < w; x++) { if (x + rayon < w) acc += m[y * w + x + rayon]; if (x - rayon - 1 >= 0) acc -= m[y * w + x - rayon - 1]; if (x >= 0) t[y * w + x] = acc / (2 * rayon + 1); } }
      const t2 = new Float32Array(w * h);
      for (let x = 0; x < w; x++) { let acc = 0; for (let y = -rayon; y < h; y++) { if (y + rayon < h) acc += t[(y + rayon) * w + x]; if (y - rayon - 1 >= 0) acc -= t[(y - rayon - 1) * w + x]; if (y >= 0) t2[y * w + x] = acc / (2 * rayon + 1); } }
      m = t2;
    }
    for (let i = 0; i < w * h; i++) {
      if (z[i] > -1e8) continue;
      const a = Math.min(0.85, m[i] * force);
      for (let k = 0; k < 3; k++) img[i * 4 + k] = img[i * 4 + k] * (1 - a) + couleur[k] * a;
    }
  };
  return { img, w, h, quad, lumiere, vue, lueur };
}

function dessinerJoueur(sc, skin, fin, calqueHD) {
  for (const [nom, boite, uv, uv2, gonfle] of PARTIES(fin)) {
    for (const [couche, g] of [[uv, 0], [uv2, gonfle]]) {
      for (const t of texels(boite, couche, g)) {
        const c = get(skin, t.tx, t.ty);
        if (c[3] < 128) continue;
        const l = sc.lumiere(t.N, false);
        if (l < 0) continue;
        sc.quad(t.coins, clair(c, l), g ? 0.02 : 0);
      }
    }
    if (calqueHD) {
      for (const t of texels(boite, uv, 0.03, R)) {
        const i = (t.ty * T + t.tx) * 4;
        if (calqueHD[i + 3] < 128) continue;
        const l = sc.lumiere(t.N, false);
        if (l < 0) continue;
        sc.quad(t.coins, clair([calqueHD[i], calqueHD[i + 1], calqueHD[i + 2]], l), 0.01);
      }
    }
  }
}

/** Une mèche en pointe : une pyramide, sa base carrée autour de b (perpendiculaire à dir), plus sombre vers la pointe. */
function meche(sc, b, dir, longueur, largeur, couleur) {
  const d = norme(dir);
  let e1 = croix(d, [0, 1, 0]);
  if (Math.hypot(...e1) < 0.2) e1 = croix(d, [1, 0, 0]);
  e1 = norme(e1);
  const e2 = croix(d, e1);
  const coin = (a, c) => b.map((x, i) => x + ((e1[i] * a + e2[i] * c) * largeur) / 2);
  const base = [coin(-1, -1), coin(1, -1), coin(1, 1), coin(-1, 1)];
  const pointe = b.map((x, i) => x + d[i] * longueur);
  for (let k = 0; k < 4; k++) {
    const p0 = base[k], p1 = base[(k + 1) % 4];
    const n = norme(croix(p1.map((x, i) => x - p0[i]), pointe.map((x, i) => x - p0[i])));
    const l = sc.lumiere(n, true);
    for (let j = 0; j < 4; j++) {
      const q = (p, t) => p.map((x, i) => x + (pointe[i] - x) * t);
      sc.quad([q(p0, j / 4), q(p1, j / 4), q(p1, (j + 1) / 4), q(p0, (j + 1) / 4)], clair(couleur, l * (1 - 0.06 * j)));
    }
  }
}

/**
 * Les cheveux : une grosse touffe gris-bleu, ébouriffée. De longues mèches en pointe tombent dans le dos, d'autres
 * partent sur les côtés et en arrière, des pointes hérissées sur le dessus, de courtes mèches sur le front.
 */
function dessinerCheveux(sc, k) {
  if (k <= 0) return;
  const m = [];
  for (const y of [31.2, 28.8, 26.4]) for (const x of [-3.5, -1.2, 1.2, 3.5]) m.push({ b: [x, y, -4.2], d: [x * 0.16, -0.8 - (31.2 - y) * 0.04, -0.6], l: 9 + (31.2 - y) * 0.5, w: 3.6 });
  for (const sx of [-1, 1]) for (const [y, zz] of [[31.2, -2.6], [31.2, 0.4], [28.8, -3.2], [28.8, -0.8], [26.6, -2.4], [26.6, 0.6]]) m.push({ b: [sx * 4.2, y, zz], d: [sx * 0.8, -0.5, -0.5], l: 6.5, w: 3.4 });
  for (const x of [-3.2, 0, 3.2]) for (const zz of [-2.8, 0.2, 3]) m.push({ b: [x, 32.1, zz], d: [x * 0.2, 0.5, -0.85], l: 5.5 + (3 - zz) * 0.45, w: 3.6 });
  for (const x of [-3.2, -1.1, 1.1, 3.2]) m.push({ b: [x, 31.6, 4.1], d: [x * 0.15, -0.55, 0.8], l: 2.8, w: 2.8 });
  m.forEach((h, i) => {
    const hasard = hache(i, 7, 13);
    meche(sc, h.b, h.d, h.l * k * (0.85 + 0.3 * hasard), h.w * Math.min(1, 0.4 + k), clair(CHEVEUX, 0.82 + 0.28 * hasard));
  });
}

/**
 * Les deux ailes, accrochées en haut du dos de part et d'autre de la colonne, ouvertes en V vers l'arrière et un peu
 * relevées ; l'aile droite est l'aile gauche en miroir. Épaisses comme des mains, elles se recourbent un peu vers
 * l'arrière, comme des mains qui se referment.
 * battement : l'angle (radians) dont elles s'ouvrent ou se referment ; k : de 0 (rien) à 1 (dépliées).
 */
const ECHELLE_AILE = 1.3, PENCHE_AILE = 0.12, COURBE_AILE = 0.005;
// la tranche, du même brun foncé que le trait : vue de côté, le bord reste un contour net
const TRANCHE = [62, 42, 38];
function dessinerAiles(sc, aile, k, battement = 0) {
  if (k <= 0) return;
  const { tex, epais } = aile;
  const L = AILE_N + 1;
  const plein = (x, y) => x >= 0 && y >= 0 && x < AILE_N && y < AILE_N && tex[(y * AILE_N + x) * 4 + 3] >= 128;
  for (const s of [-1, 1]) {
    const racine = [s * 2.4, 21.5, -2.8];
    const th = 0.3 + battement;
    const u = norme([s * Math.cos(th), 0.04, -Math.sin(th)]);
    let v = [0, Math.cos(0.12), -Math.sin(0.12)];
    const uv = u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
    v = norme(v.map((c, i) => c - uv * u[i]));
    // n : vers l'avant du joueur ; l'aile se recourbe vers l'arrière, d'autant plus loin du poignet
    let n = croix(u, v);
    if (n[2] < 0) n = n.map((c) => -c);
    // les coins de la grille des texels, sur la face avant (1) et la face arrière (-1)
    const faces = { 1: new Array(L * L), [-1]: new Array(L * L) };
    for (let y = 0; y < L; y++) for (let x = 0; x < L; x++) {
      const a = (x / AILE_R - RACINE[0]) * ECHELLE_AILE * k, b = (y / AILE_R - RACINE[1]) * ECHELLE_AILE * k;
      const du = a * Math.cos(PENCHE_AILE) - b * Math.sin(PENCHE_AILE), dv = a * Math.sin(PENCHE_AILE) + b * Math.cos(PENCHE_AILE);
      const h = -COURBE_AILE * (0.6 * du * du + dv * dv);
      const e = epais[y * L + x] * ECHELLE_AILE * k;
      for (const f of [1, -1]) faces[f][y * L + x] = [0, 1, 2].map((i) => racine[i] + u[i] * du + v[i] * dv + n[i] * (h + f * e));
    }
    const normale = (q) => norme(croix(q[2].map((c, j) => c - q[0][j]), q[3].map((c, j) => c - q[1][j])));
    for (let y = 0; y < AILE_N; y++) for (let x = 0; x < AILE_N; x++) {
      if (!plein(x, y)) continue;
      const i = (y * AILE_N + x) * 4;
      const c = [tex[i], tex[i + 1], tex[i + 2]];
      const coins = [y * L + x, y * L + x + 1, (y + 1) * L + x + 1, (y + 1) * L + x];
      for (const f of [1, -1]) {
        const q = coins.map((j) => faces[f][j]);
        sc.quad(q, clair(c, sc.lumiere(normale(q), true)), 0, true);
      }
      // la tranche, là où le texel voisin est vide (le bord de l'aile et celui des déchirures)
      const cotes = [[0, -1, 0, 1], [1, 0, 1, 2], [0, 1, 2, 3], [-1, 0, 3, 0]];
      for (const [dx, dy, a, b] of cotes) {
        if (plein(x + dx, y + dy)) continue;
        const q = [faces[1][coins[a]], faces[1][coins[b]], faces[-1][coins[b]], faces[-1][coins[a]]];
        sc.quad(q, clair(TRANCHE, sc.lumiere(normale(q), true)), 0, true);
      }
    }
  }
}

// ------------------------------------------------------------------ le bas de Sasuke (forme 2)
/**
 * La tenue de la forme 2 : torse nu, bras nus (sans les manchettes), et le bas du skin de Sasuke : le pantalon, les
 * sandales, la ceinture de corde violette. Son haut, enlevé, pend autour de la taille (dessiné à part).
 */
function basSasuke(nu, fin) {
  const sasuke = lire(path.join(ICI, "skins", "sasuke.png"));
  const s = { px: Buffer.from(nu.px) };
  for (const [nom, boite, uv, uv2] of PARTIES(fin)) {
    const jambe = nom.startsWith("jambe");
    if (!jambe && nom !== "corps") continue;
    for (const [couche] of [[uv], [uv2]]) {
      for (const t of texels(boite, couche, 0)) {
        if (jambe || t.centre[1] < 16) s.px.set(get(sasuke, t.tx, t.ty), (t.ty * 64 + t.tx) * 4);
      }
    }
  }
  return s;
}

const HAUT_SASUKE = [178, 170, 198];
/** Le haut de Sasuke, enlevé : roulé à la taille, il pend dans le dos et sur les côtés, ouvert devant. */
function dessinerHautPendant(sc) {
  const pas = 0.5;
  const pli = (i) => 0.8 + 0.28 * (0.5 + 0.5 * Math.sin(i * 1.9 + 0.7)) + 0.06 * hache(i, 2, 8);
  // un pan : une suite de colonnes posées le long d'une ligne (x, z) autour des hanches, qui s'évasent en descendant
  const pan = (debut, fin, sortie, bas, graine) => {
    const n = Math.round(Math.hypot(fin[0] - debut[0], fin[1] - debut[1]) / pas);
    for (let i = 0; i < n; i++) {
      const a = [debut[0] + ((fin[0] - debut[0]) * i) / n, debut[1] + ((fin[1] - debut[1]) * i) / n];
      const b = [debut[0] + ((fin[0] - debut[0]) * (i + 1)) / n, debut[1] + ((fin[1] - debut[1]) * (i + 1)) / n];
      const fond = bas + 1.4 * hache(i, graine, 4) - (i % 3 === 1 ? 0.6 : 0);
      const l = sc.lumiere([sortie[0], 0, sortie[1]], true);
      // le bourrelet, à la ceinture
      for (let y = 14.2; y > 12.4; y -= pas) {
        const r = 0.35 + 0.25 * Math.sin(((14.2 - y) / 1.8) * Math.PI);
        const p = (q, yy) => [q[0] + sortie[0] * r, yy, q[1] + sortie[1] * r];
        sc.quad([p(a, y), p(b, y), p(b, y - pas), p(a, y - pas)], clair(HAUT_SASUKE, l * pli(i + graine) * (y > 13.6 ? 1.08 : 0.92)));
      }
      // le pan qui pend
      for (let y = 12.4; y > fond; y -= pas) {
        const y1 = Math.max(fond, y - pas);
        const r0 = 0.3 + (12.4 - y) * 0.14, r1 = 0.3 + (12.4 - y1) * 0.14;
        const p = (q, yy, r) => [q[0] + sortie[0] * r, yy, q[1] + sortie[1] * r];
        sc.quad([p(a, y, r0), p(b, y, r0), p(b, y1, r1), p(a, y1, r1)], clair(HAUT_SASUKE, l * pli(i + graine) * (y1 - fond < 0.6 ? 0.82 : 1)));
      }
    }
  };
  pan([4.25, -2.25], [-4.25, -2.25], [0, -1], 5.2, 1);
  pan([-4.25, -2.25], [-4.25, 2.25], [-1, 0], 6.2, 2);
  pan([4.25, 2.25], [4.25, -2.25], [1, 0], 6.2, 3);
  pan([-4.25, 2.25], [-1.6, 2.25], [0, 1], 7.8, 4);
  pan([1.6, 2.25], [4.25, 2.25], [0, 1], 7.8, 5);
}

// ------------------------------------------------------------------ la police 3 x 5
const POLICE = {
  A: "010101111101101", B: "110101110101110", C: "011100100100011", D: "110101101101110", E: "111100110100111", F: "111100110100100",
  G: "011100101101011", H: "101101111101101", I: "111010010010111", J: "001001001101010", K: "101101110101101", L: "100100100100111",
  M: "101111111101101", N: "110101101101101", O: "010101101101010", P: "110101110100100", Q: "010101101110011", R: "110101110101101",
  S: "011100010001110", T: "111010010010010", U: "101101101101111", V: "101101101101010", W: "101101111111101", X: "101101010101101",
  Y: "101101010010010", Z: "111001010100111", " ": "000000000000000", 1: "010110010010111", 2: "110001010100111", 3: "110001010001110",
  4: "101101111001001", 5: "111100110001110", 6: "011100111101111",
};
function ecrireTexte(img, W, texte, x0, y0, t, couleur) {
  let x = x0;
  for (const ch of texte.toUpperCase()) {
    const m = POLICE[ch] ?? POLICE[" "];
    for (let k = 0; k < 15; k++) if (m[k] === "1") for (let dy = 0; dy < t; dy++) for (let dx = 0; dx < t; dx++) img.set(couleur, ((y0 + Math.floor(k / 3) * t + dy) * W + x + (k % 3) * t + dx) * 4);
    x += 4 * t;
  }
}

// ------------------------------------------------------------------ une étape de la transformation
const VIOLET = [86, 14, 120];
function preparer(fichier, fin) {
  const base = lire(path.join(ICI, "skins", fichier));
  const peau = teint(base);
  const nu = habiller(base, fin, false);
  const habille = habiller(base, fin, true);
  return { base, fin, peau, nu, habille, bas: basSasuke(nu, fin), m: marques(nu, peau, fin), aile: texAile() };
}
/** Le t-shirt qui se déchire (dechire 0 → 1) : il part en lambeaux, le dos d'abord, là où sortent les ailes. */
function dechirer(P, dechire) {
  const s = { px: Buffer.from(P.habille.px) };
  for (let y = 16; y < 64; y++) for (let x = 0; x < 64; x++) {
    const a = get(P.habille, x, y), b = get(P.nu, x, y);
    if (a.every((v, i) => v === b[i])) continue;
    const dos = (y >= 20 && y < 32 && x >= 32 && x < 40) || (y >= 20 && y < 32 && x >= 52 && x < 56) || (y >= 52 && y < 64 && x >= 44 && x < 48);
    if (hache(x, y, 21) * 0.7 + (dos ? 0 : 0.3) < dechire) s.px.set(b, (y * 64 + x) * 4);
  }
  return s;
}

/** Les lambeaux du t-shirt qui volent autour de lui, puis retombent (td : secondes depuis la déchirure). */
function dessinerLambeaux(sc, td) {
  if (td < 0 || td > 1.1) return;
  for (let i = 0; i < 28; i++) {
    const h = (k) => hache(i, k, 17);
    const depart = [(h(1) - 0.5) * 8, 13 + h(2) * 11, h(3) < 0.6 ? -2.2 : 2.2];
    const vit = [depart[0] * 1.6 + (h(4) - 0.5) * 6, 6 + h(5) * 8, depart[2] * (3 + h(6) * 4)];
    const p = depart.map((x, k) => x + vit[k] * td - (k === 1 ? 14 * td * td : 0));
    const a = h(7) * 6 + td * (4 + h(8) * 6), b = h(9) * 6 + td * 3;
    const e1 = [Math.cos(a), Math.sin(a) * Math.cos(b), Math.sin(a) * Math.sin(b)];
    const e2 = norme(croix(e1, [Math.sin(b), 0.3, Math.cos(b)]));
    const w = 0.7 + h(10) * 0.8, hh = 0.5 + h(11) * 0.6;
    const coin = (s1, s2) => p.map((x, k) => x + e1[k] * s1 * w + e2[k] * s2 * hh);
    sc.quad([coin(-1, -1), coin(1, -1), coin(1, 1), coin(-1, 1)], clair(TSHIRT, sc.lumiere(croix(e1, e2), true) * (0.85 + 0.3 * h(12))));
  }
}

/** e : { tshirt, dechire, debris, noir, gris, cheveux, croixNez, ailes, battement, aura } */
function rendre(P, e, opts) {
  const tenue = e.tshirt ? (e.dechire > 0 ? dechirer(P, e.dechire) : P.habille) : P.bas;
  const skin = transformer(tenue, P.base, P.fin, e);
  const hd = calque(P.m, skin, P.peau, e);
  const sc = scene({ ...opts, aura: e.aura > 0.01 ? { c: VIOLET, force: e.aura, r: 0.3 + 0.28 * Math.min(1, e.aura), y: 0.48 } : null });
  dessinerAiles(sc, P.aile, e.ailes, e.battement ?? 0);
  dessinerCheveux(sc, e.cheveux);
  dessinerJoueur(sc, skin, P.fin, hd);
  if (!e.tshirt) dessinerHautPendant(sc);
  dessinerLambeaux(sc, e.debris ?? -1);
  if (e.ailes > 0) sc.lueur([120, 40, 170], 5, 1.6 * Math.min(1, e.ailes));
  return sc;
}

const FORME1 = { tshirt: true, dechire: 0, noir: 0, gris: 0, cheveux: 0, croixNez: 0, ailes: 0, aura: 0.55 };
const FORME2 = { tshirt: false, dechire: 1, noir: 1, gris: 1, cheveux: 1, croixNez: 1, ailes: 1, aura: 0.16 };

/**
 * La transformation dans le temps (secondes) : les marques noircissent tout le corps, qui devient gris ; les cheveux
 * poussent et grisent, la croix apparaît ; le t-shirt se déchire et les ailes jaillissent du dos ; elles battent
 * ensuite doucement. L'aura violette monte pendant la transformation, puis redevient très légère.
 */
function etat(t) {
  const sortie = (t - 2.85) / 0.55;
  return {
    tshirt: t < 3.0,
    dechire: lisse((t - 2.72) / 0.28),
    debris: t - 2.85,
    noir: lisse((t - 0.3) / 1.3),
    gris: lisse((t - 1.5) / 1.1),
    cheveux: lisse((t - 1.3) / 1.4),
    croixNez: lisse((t - 2.2) / 0.4),
    ailes: sortie <= 0 ? 0 : sortie >= 1 ? 1 : 1 - (1 - sortie) ** 3 + 0.08 * Math.sin(sortie * Math.PI),
    battement: t < 2.85 ? 0 : 0.11 * Math.sin((t - 2.85) * 2 * Math.PI * 1.1) * Math.min(1, (t - 2.85) / 0.8),
    aura: t < 0.3 ? 0.55 : t < 3.4 ? 0.55 + 0.35 * lisse((t - 0.3) / 1) : 0.16 + 0.74 * (1 - lisse((t - 3.4) / 0.9)),
  };
}

const steve = preparer("sasuke.png", false);

if (process.argv.includes("anim")) {
  const dossier = path.join(ICI, "images");
  fs.rmSync(dossier, { recursive: true, force: true });
  fs.mkdirSync(dossier, { recursive: true });
  const N = 84;
  for (let i = 0; i < N; i++) {
    const t = i / 12;
    const lacet = -28 + Math.max(0, t - 3.6) * 60;
    const sc = rendre(steve, etat(t), { lacet, w: 560, h: 560, echelle: 6.2, pied: 530, tangage: 8 });
    ecrirePng(path.join(dossier, String(i).padStart(3, "0") + ".png"), sc.w, sc.h, sc.img);
  }
  console.log("images", N);
} else {
  const CW = 440, CH = 540, M = 34;
  const vues = [
    ["FORME 1", FORME1, { lacet: -28 }],
    ["FORME 2", FORME2, { lacet: -28 }],
    ["DE DOS", FORME2, { lacet: 155 }],
    ["DE PROFIL", FORME2, { lacet: -95 }],
    ["VISAGE", FORME2, { lacet: -18, echelle: 22, pied: 900, tangage: 4 }],
  ];
  const etapes = [["1 NOIRCIT", 1.6], ["2 PEAU CHEVEUX", 2.62], ["3 DECHIRE", 2.93], ["4 AILES", 3.25], ["5 FINAL", 5.2]];
  const W = CW * vues.length, H = (M + CH) * 2;
  const planche = Buffer.alloc(W * H * 4, 255);
  vues.forEach(([titre, e, o], c) => {
    const sc = rendre(steve, e, { w: CW, h: CH, echelle: 5.4, pied: 520, ...o });
    for (let y = 0; y < CH; y++) sc.img.copy(planche, ((M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(planche, W, titre, c * CW + 12, 10, 3, [30, 30, 40, 255]);
  });
  etapes.forEach(([titre, t], c) => {
    const sc = rendre(steve, etat(t), { w: CW, h: CH, echelle: 5.4, pied: 520, lacet: c === 2 || c === 3 ? 150 : c === 4 ? 130 : -28 });
    for (let y = 0; y < CH; y++) sc.img.copy(planche, ((2 * M + CH + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(planche, W, titre, c * CW + 12, M + CH + 10, 3, [30, 30, 40, 255]);
  });
  ecrirePng(path.join(ICI, "planche-forme2.png"), W, H, planche);
  // l'aile gauche seule, à plat, vue de dos
  const a = steve.aile.tex, Z = 2, AW = AILE_N * Z;
  const img = Buffer.alloc(AW * AW * 4, 255);
  for (let y = 0; y < AILE_N; y++) for (let x = 0; x < AILE_N; x++) {
    const i = ((AILE_N - 1 - y) * AILE_N + x) * 4;
    if (a[i + 3] < 128) continue;
    for (let dy = 0; dy < Z; dy++) for (let dx = 0; dx < Z; dx++) img.set([a[i], a[i + 1], a[i + 2], 255], ((y * Z + dy) * AW + (AILE_N - 1 - x) * Z + dx) * 4);
  }
  ecrirePng(path.join(ICI, "aile.png"), AW, AW, img);
  // les ailes de près : de trois quarts dos, et de côté pour voir leur épaisseur et leur courbe
  const pres = [
    rendre(steve, FORME2, { lacet: 145, tangage: 10, echelle: 11, w: 900, h: 640, pied: 640 }),
    rendre(steve, FORME2, { lacet: 105, tangage: 14, echelle: 11, w: 700, h: 640, pied: 640 }),
  ];
  const PW = pres.reduce((t, p) => t + p.w, 0);
  const deux = Buffer.alloc(PW * 640 * 4);
  for (let y = 0, x0 = 0; y < 640; y++, x0 = 0) for (const p of pres) { p.img.copy(deux, (y * PW + x0) * 4, y * p.w * 4, (y + 1) * p.w * 4); x0 += p.w; }
  ecrirePng(path.join(ICI, "ailes-de-pres.png"), PW, 640, deux);
  console.log("ok", W, H);
}
