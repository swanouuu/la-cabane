// Maquettes de la marque maudite, niveau 3 : le marqué de la forme 2 (peau beige, cheveux gris, croix sur le nez), qui
// garde l'aile en main à sa droite ; à sa gauche, l'aile se défait et ses doigts deviennent cinq serpents.
// Repris du générateur de la forme 2 (docs/marque-forme2/generateur/rendu.mjs).
// node rendu.mjs        → ../planche-marque3.png, ../serpents-de-pres.png, ../idee-attaque.png
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
  // l'ordre dans lequel la peau part quand l'aile devient serpents : d'abord loin des doigts, les doigts en dernier,
  // par miettes de 2 x 2 texels
  const cle = new Float32Array(AILE_N * AILE_N);
  for (let y = 0; y < AILE_N; y++) for (let x = 0; x < AILE_N; x++) {
    const p = [(x + 0.5) / AILE_R, (y + 0.5) / AILE_R];
    const d = Math.min(...DOIGTS.map((b) => distSegment(p, RACINE, b)));
    cle[y * AILE_N + x] = (1 - Math.min(1, d / 5)) * 0.7 + hache(x >> 1, y >> 1, 77) * 0.3;
  }
  return { tex, epais, cle };
}

// les cinq doigts du dessin (en points de l'image), du bout du doigt du haut au lobe du bas : chacun devient un serpent
const DOIGTS = [[840, 20], [560, 180], [215, 215], [90, 760], [610, 1100]].map(versAile);
function distSegment(p, a, b) {
  const ab = [b[0] - a[0], b[1] - a[1]];
  const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * ab[0] + (p[1] - a[1]) * ab[1]) / (ab[0] ** 2 + ab[1] ** 2)));
  return Math.hypot(p[0] - a[0] - ab[0] * t, p[1] - a[1] - ab[1] * t);
}

// ------------------------------------------------------------------ le rendu (projection, tampon de profondeur)
function scene({ lacet = -28, tangage = 10, echelle = 9, w = 360, h = 520, pied = null, aura = null, fond = [236, 236, 242], centre = [0, 0, 0] }) {
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
  const vue = ([x0, y0, z0]) => {
    const x = x0 - centre[0], y = y0 - centre[1], zz = z0 - centre[2];
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
  return { img, w, h, quad, lumiere, vue, lueur, dirVue };
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
/** Le repère de l'aile du côté s (-1 sa droite, 1 sa gauche) : la racine, et les directions u (en dehors), v (vers le haut), n (vers l'avant). */
function repereAile(s, battement) {
  const racine = [s * 2.4, 21.5, -2.8];
  const th = 0.3 + battement;
  const u = norme([s * Math.cos(th), 0.04, -Math.sin(th)]);
  let v = [0, Math.cos(0.12), -Math.sin(0.12)];
  const uv = u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
  v = norme(v.map((c, i) => c - uv * u[i]));
  // n : vers l'avant du joueur ; l'aile se recourbe vers l'arrière, d'autant plus loin du poignet
  let n = croix(u, v);
  if (n[2] < 0) n = n.map((c) => -c);
  return { racine, u, v, n };
}
/** Un point de l'aile (en pixels de son dessin, depuis son coin), dans le monde ; e : le décalage vers l'avant. */
function pointAile(R, xa, ya, k, e = 0) {
  const a = (xa - RACINE[0]) * ECHELLE_AILE * k, b = (ya - RACINE[1]) * ECHELLE_AILE * k;
  const du = a * Math.cos(PENCHE_AILE) - b * Math.sin(PENCHE_AILE), dv = a * Math.sin(PENCHE_AILE) + b * Math.cos(PENCHE_AILE);
  const h = -COURBE_AILE * (0.6 * du * du + dv * dv);
  return [0, 1, 2].map((i) => R.racine[i] + R.u[i] * du + R.v[i] * dv + R.n[i] * (h + e));
}

/**
 * gauche : ce que devient l'aile de sa gauche (celle qui se change en serpents) :
 *  fonte 0 → 1 : la peau entre les doigts part en miettes, d'abord loin des doigts, puis les doigts eux-mêmes ;
 *  teinte 0 → 1 : elle fonce et violit ; tremble : elle tremble ; absente : plus d'aile du tout ;
 *  t : le temps (pour les miettes qui volent).
 */
function dessinerAiles(sc, aile, k, battement = 0, gauche = {}) {
  if (k <= 0) return;
  const { tex, epais, cle } = aile;
  const L = AILE_N + 1;
  for (const s of [-1, 1]) {
    const o = s === 1 ? gauche : {};
    if (o.absente) continue;
    const fonte = o.fonte ?? 0, teinte = o.teinte ?? 0;
    const parti = (x, y) => fonte > 0 && cle[y * AILE_N + x] < fonte;
    const plein = (x, y) => x >= 0 && y >= 0 && x < AILE_N && y < AILE_N && tex[(y * AILE_N + x) * 4 + 3] >= 128 && !parti(x, y);
    const R = repereAile(s, battement + (o.tremble ?? 0));
    // les coins de la grille des texels, sur la face avant (1) et la face arrière (-1)
    const faces = { 1: new Array(L * L), [-1]: new Array(L * L) };
    for (let y = 0; y < L; y++) for (let x = 0; x < L; x++) {
      const e = epais[y * L + x] * ECHELLE_AILE * k;
      for (const f of [1, -1]) faces[f][y * L + x] = pointAile(R, x / AILE_R, y / AILE_R, k, f * e);
    }
    const normale = (q) => norme(croix(q[2].map((c, j) => c - q[0][j]), q[3].map((c, j) => c - q[1][j])));
    for (let y = 0; y < AILE_N; y++) for (let x = 0; x < AILE_N; x++) {
      if (!plein(x, y)) continue;
      const i = (y * AILE_N + x) * 4;
      let c = [tex[i], tex[i + 1], tex[i + 2]];
      // des veines violettes montent dans la peau qui va partir
      if (teinte > 0) c = mix(c, mix(VIOLET_SOMBRE, [150, 70, 190], hache(x >> 2, y >> 2, 5) * 0.5), teinte * (0.25 + 0.45 * (1 - cle[y * AILE_N + x])));
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
    // les miettes : un texel sur quinze qui part s'envole un moment, en tournant, puis retombe
    if (fonte > 0 && o.t != null) {
      for (let y = 0; y < AILE_N; y += 2) for (let x = 0; x < AILE_N; x += 2) {
        const i = y * AILE_N + x;
        if (tex[i * 4 + 3] < 128 || hache(x, y, 41) > 0.13) continue;
        const age = (o.t - o.debutFonte) - cle[i] * o.dureeFonte;
        if (age < 0 || age > 0.9) continue;
        const p0 = pointAile(R, x / AILE_R, y / AILE_R, k);
        const h = (j) => hache(x, y, 50 + j);
        const vit = [R.u[0] * (4 + 8 * h(1)) + (h(2) - 0.5) * 6, 3 + 7 * h(3), -4 - 6 * h(4)];
        const p = p0.map((c, j) => c + vit[j] * age - (j === 1 ? 9 * age * age : 0));
        const a = h(5) * 6 + age * 9, cote = 0.5 + 0.5 * h(6);
        const e1 = [Math.cos(a), Math.sin(a), 0.3], e2 = norme(croix(e1, [0.2, 0.4, 1]));
        const coin = (s1, s2) => p.map((c, j) => c + e1[j] * s1 * cote + e2[j] * s2 * cote);
        const c = mix([tex[i * 4], tex[i * 4 + 1], tex[i * 4 + 2]], [120, 50, 160], 0.3 + 0.5 * Math.min(1, age / 0.6));
        sc.quad([coin(-1, -1), coin(1, -1), coin(1, 1), coin(-1, 1)], clair(c, sc.lumiere(croix(e1, e2), true)), 0, true);
      }
    }
  }
}

// ------------------------------------------------------------------ les serpents, à sa gauche
// Cinq serpents, de la couleur de l'aile : des écailles hexagonales sur le dos et les flancs (comme dans l'anime), le
// ventre plus clair en bandes, une tête plate et large, des yeux jaunes à pupille fendue. Ils partent du haut du dos,
// montent, passent en arc au-dessus de son épaule gauche et retombent, têtes vers le bas et vers l'avant, étagées de
// l'épaule à la hanche.
const VIOLET_SOMBRE = [70, 20, 90];
const ECAILLE = [200, 172, 150], ECAILLE_TRAIT = [98, 66, 58], VENTRE = [228, 212, 190], VENTRE_TRAIT = [170, 140, 118];
const OEIL = [238, 190, 40], BOUCHE = [184, 72, 90], GORGE = [88, 22, 40], LANGUE = [200, 36, 62], CROC = [246, 242, 234];
/** Chaque serpent, au repos : sa racine sur le dos, deux points de contrôle, le bout du museau (une courbe de Bézier). */
const SERPENTS = [
  { racine: [2.6, 23.6, -2.6], b1: [9, 47, -14], b2: [41, 51, -6], bout: [38.5, 37, 9.5] },
  { racine: [2.8, 22.6, -2.6], b1: [10, 42, -14], b2: [37, 44, -4], bout: [34, 30.5, 12] },
  { racine: [3.0, 21.6, -2.6], b1: [11, 36, -13], b2: [33, 37, -6], bout: [30, 24, 9] },
  { racine: [3.2, 20.6, -2.6], b1: [11, 30, -12], b2: [29, 30, -3], bout: [26, 17.5, 12] },
  { racine: [3.4, 19.6, -2.6], b1: [10, 24, -11], b2: [25, 23, -4], bout: [22, 11, 9.5] },
];
/** Le creux des arcs : le dos des serpents regarde à l'opposé, le ventre vers lui. */
const CREUX_SERPENTS = [17, 24, -4];
/** Le dessus des têtes : vers l'extérieur, un peu vers le haut et vers l'avant (on voit leurs yeux de face). */
const HAUT_TETE = norme([0.75, 0.35, 0.55]);
const TETE_L = 7.5, CHARNIERE = 0.3, R_SERPENT = 2.3, TOURS = 48;
const plus = (a, b) => a.map((x, i) => x + b[i]);
const moins = (a, b) => a.map((x, i) => x - b[i]);
const fois = (a, k) => a.map((x) => x * k);
const scal = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const bezier = (P, t) => { const m = 1 - t; return [0, 1, 2].map((i) => m * m * m * P[0][i] + 3 * m * m * t * P[1][i] + 3 * m * t * t * P[2][i] + t * t * t * P[3][i]); };

/** Les serpents ondulent doucement, chacun à son rythme. */
function balance(i, t) {
  return {
    b2: [0.8 * Math.sin(t * 1.3 + i), 1.2 * Math.sin(t * 1.7 + i * 1.1), 0.8 * Math.cos(t * 1.2 + i)],
    bout: [1.0 * Math.sin(t * 2.1 + i * 1.4), 1.3 * Math.sin(t * 1.9 + i * 0.9), 1.0 * Math.cos(t * 1.6 + i * 1.2)],
  };
}

/**
 * La colonne du serpent i : une fonction f (0 la racine → 1 le museau) → point, et sa longueur au repos. em (0 → 1) le
 * fait passer du doigt de l'aile (couché dans le plan de l'aile, de son poignet au bout du doigt) à sa pose.
 */
function colonne(i, o, R) {
  const S = SERPENTS[i];
  const bal = balance(i, o.t ?? 0);
  const P = o.points?.[i] ?? [S.racine, S.b1, plus(S.b2, bal.b2), plus(S.bout, bal.bout)];
  const M = 300, pts = [], cum = [0];
  for (let k = 0; k <= M; k++) pts.push(bezier(P, k / M));
  for (let k = 1; k <= M; k++) cum.push(cum[k - 1] + Math.hypot(...moins(pts[k], pts[k - 1])));
  const L = cum[M];
  const surCourbe = (f) => {
    const c = f * L;
    let k = 0;
    while (k < M - 1 && cum[k + 1] < c) k++;
    const a = (c - cum[k]) / Math.max(1e-9, cum[k + 1] - cum[k]);
    return mix(pts[k], pts[k + 1], Math.max(0, Math.min(1, a)));
  };
  const em = o.em ?? 1;
  if (em >= 1) return { L, pos: surCourbe };
  const D = DOIGTS[i];
  const doigt = (f) => pointAile(R, RACINE[0] + (D[0] - RACINE[0]) * f, RACINE[1] + (D[1] - RACINE[1]) * f, 1);
  return { L, pos: (f) => mix(doigt(f), surCourbe(f), em) };
}

/** La section du serpent à la distance u de sa racine : demi-largeur, demi-hauteur, et h, la place dans la tête (< 0 : le corps). */
function section(u, L) {
  const h = (u - (L - TETE_L)) / TETE_L;
  if (h < 0) {
    // plus épais là où il sort du dos ; un cou un peu plus fin juste avant la tête
    let r = R_SERPENT + 0.8 * Math.max(0, 1 - u / 3.5) ** 2;
    r -= 0.4 * lisse((u - (L - TETE_L - 3)) / 3);
    return { rw: r, rh: r, h };
  }
  // la tête : large et plate, la plus large vers la charnière de la mâchoire, puis le museau qui s'arrondit
  let rw = h <= CHARNIERE ? 1.9 + 1.4 * lisse(h / CHARNIERE) : 3.3 - 1.6 * ((h - CHARNIERE) / (1 - CHARNIERE)) ** 1.6;
  let rh = h <= CHARNIERE ? 1.9 + 0.1 * lisse(h / CHARNIERE) : 2.0 - 0.9 * ((h - CHARNIERE) / (1 - CHARNIERE)) ** 1.3;
  if (h > 0.8) { const k = Math.sqrt(Math.max(0, 1 - ((h - 0.8) / 0.2) ** 2)); rw *= k; rh *= k; }
  return { rw, rh, h };
}

/** Les écailles : un pavage hexagonal (x autour du corps, y le long), et la distance (0 → 1) du point au bord de son écaille. */
function hexa(x, y, s) {
  const q = ((Math.sqrt(3) / 3) * x - y / 3) / s, r = ((2 / 3) * y) / s;
  let rx = Math.round(q), rz = Math.round(r), ry = Math.round(-q - r);
  const dx = Math.abs(rx - q), dy = Math.abs(ry + q + r), dz = Math.abs(rz - r);
  if (dx > dy && dx > dz) rx = -ry - rz; else if (dz > dy) rz = -rx - ry;
  const cx = s * Math.sqrt(3) * (rx + rz / 2), cy = s * 1.5 * rz;
  const px = Math.abs(x - cx), py = Math.abs(y - cy);
  const ri = (s * Math.sqrt(3)) / 2;
  return { bord: 1 - Math.max(px, px / 2 + (py * Math.sqrt(3)) / 2) / ri, cellule: [rx, rz] };
}

/** La couleur de la peau du serpent i au point (u le long, θ autour ; θ = π/2 le dessus, -π/2 le ventre). */
function couleurSerpent(i, u, th, L, ouvert) {
  const { h } = section(u, L);
  const sn = Math.sin(th);
  // les yeux, de chaque côté du haut de la tête : jaunes, pupille noire fendue, cerclés de brun
  if (h > 0) {
    for (const te of [0.62, Math.PI - 0.62]) {
      const du = (u - (L - TETE_L + 0.42 * TETE_L)) / 0.85, da = ((th - te) * 2.4) / 0.62;
      const d = Math.hypot(du, da);
      if (d < 1) return Math.abs(du) < 0.2 ? [14, 10, 12] : d > 0.8 ? [60, 30, 20] : mix(OEIL, [255, 236, 120], 1 - d);
      if (d < 1.35 && Math.sin(th) > Math.sin(te) + 0.05) return ECAILLE_TRAIT;
    }
    // les narines
    for (const tn of [Math.PI / 2 - 0.5, Math.PI / 2 + 0.5]) if (Math.hypot((u - (L - 0.9)) / 0.25, ((th - tn) * 1.4) / 0.22) < 1) return [40, 24, 22];
    // la commissure : un trait sombre là où les mâchoires se touchent
    if (!ouvert && h > CHARNIERE * 0.8 && sn <= 0 && sn > -0.14) return [70, 40, 40];
  }
  // le ventre : des bandes claires
  if (sn < -0.62) {
    const k = (u / 0.9) % 1;
    return k < 0.22 ? VENTRE_TRAIT : clair(VENTRE, 0.96 + 0.08 * k);
  }
  if (sn < -0.5) return ECAILLE_TRAIT;
  // le dos et les flancs : des écailles hexagonales, plus grandes sur le dessus de la tête
  const tour = ((th + Math.PI / 2) / (2 * Math.PI)) * 2 * Math.PI * R_SERPENT;
  const { bord, cellule } = hexa(tour, u, h > 0.05 && sn > 0.2 ? 1.25 : 0.95);
  if (bord < 0.16) return ECAILLE_TRAIT;
  return clair(ECAILLE, (0.9 + 0.12 * bord) * (0.95 + 0.1 * hache(cellule[0], cellule[1], i + 3)));
}

/**
 * Le serpent i. o : { g (0 → 1, il grossit en sortant du doigt), em (0 → 1, il quitte l'aile pour sa pose), bouche (0 → 1),
 * langue (0 → 1), t, points (pour l'attaque) }.
 */
function dessinerSerpent(sc, i, o, R) {
  const g = o.g ?? 1;
  if (g <= 0) return;
  const { L, pos } = colonne(i, o, R);
  const rk = 0.45 + 0.55 * g;
  const n = Math.ceil(L / 0.25);
  const alpha = (o.bouche ?? 0) * (0.7 + 0.2 * hache(i, 3, 9));
  // les anneaux, et leur repère : d le long, B le dessus (le dos), N le côté
  const an = [];
  for (let j = 0; j <= n; j++) an.push({ p: pos(j / n), u: (j / n) * L });
  for (let j = 0; j <= n; j++) {
    const a = an[j];
    const d = norme(moins(an[Math.min(n, j + 1)].p, an[Math.max(0, j - 1)].p));
    let ref = norme(moins(a.p, CREUX_SERPENTS));
    ref = norme(mix(ref, HAUT_TETE, lisse((a.u - (L - TETE_L - 7)) / 7)));
    let B = moins(ref, fois(d, scal(ref, d)));
    if (Math.hypot(...B) < 0.1) B = croix(d, [1, 0, 0]);
    B = norme(B);
    Object.assign(a, { d, B, N: croix(B, d), ...section(a.u, L) });
  }
  // la charnière de la mâchoire
  const jc = an.findIndex((a) => a.h >= CHARNIERE);
  const pivot = jc >= 0 ? an[jc].p : null, axe = jc >= 0 ? an[jc].N : null;
  const machoire = (q, j) => (alpha > 0 && jc >= 0 && j >= jc ? plus(pivot, tourner(moins(q, pivot), axe, alpha)) : q);
  const th = (k) => -Math.PI / 2 + (2 * Math.PI * k) / TOURS;
  const bas = (k) => k < TOURS / 4 || k >= (3 * TOURS) / 4;
  const sur = (j, k, mach) => {
    const a = an[j], t = th(k);
    const q = plus(a.p, plus(fois(a.N, Math.cos(t) * a.rw * rk), fois(a.B, Math.sin(t) * a.rh * rk)));
    return mach ? machoire(q, j) : q;
  };
  const normale = (q) => norme(croix(moins(q[2], q[0]), moins(q[3], q[1])));
  for (let j = 0; j < n; j++) {
    for (let k = 0; k < TOURS; k++) {
      const m = bas(k);
      const q = [sur(j, k, m), sur(j + 1, k, m), sur(j + 1, k + 1, m), sur(j, k + 1, m)];
      const nr = normale(q);
      const c = couleurSerpent(i, an[j].u + 0.125, th(k) + Math.PI / TOURS, L, alpha > 0.05);
      // les bords de la silhouette s'assombrissent, comme le trait de l'anime
      const v = sc.dirVue(nr);
      const contour = Math.abs(v[2]) < 0.22 ? 0.55 : 1;
      sc.quad(q, clair(c, sc.lumiere(nr, true) * contour), 0, true);
    }
  }
  if (alpha <= 0.02 || jc < 0) return;
  // l'intérieur de la bouche : le palais (en haut) et le plancher (la mâchoire du bas), rose, sombre vers la gorge
  for (let j = jc; j < n; j++) {
    for (const mach of [false, true]) {
      const bord = (jj, s) => { const a = an[jj]; const q = plus(a.p, fois(a.N, s * a.rw * rk * 0.94)); return mach ? machoire(q, jj) : q; };
      const c = mix(GORGE, BOUCHE, lisse((an[j].h - CHARNIERE) / 0.35));
      sc.quad([bord(j, -1), bord(j, 1), bord(j + 1, 1), bord(j + 1, -1)], clair(c, 0.9), 0, true);
    }
  }
  // les crochets, en haut devant, et la langue fourchue qui sort
  const jf = an.findIndex((a) => a.h >= 0.8);
  if (jf >= 0) {
    const a = an[jf];
    for (const s of [-1, 1]) {
      const base = plus(a.p, fois(a.N, s * a.rw * rk * 0.6));
      meche(sc, base, plus(fois(a.B, -1), fois(a.d, -0.25)), 1.1 * rk * Math.min(1, alpha / 0.4), 0.42 * rk, CROC);
    }
  }
  const lg = o.langue ?? 0;
  if (lg > 0.02) {
    const jl = an.findIndex((a) => a.h >= 0.5);
    const a = an[jl];
    const dir = norme(tourner(a.d, axe, alpha * 0.5));
    const haut = croix(dir, a.N);
    const t = o.t ?? 0;
    let p = machoire(plus(a.p, fois(a.B, -0.2 * a.rh)), jl);
    const longueur = (TETE_L * 0.6 + 3.2) * lg, pas = 0.3;
    for (let s = 0; s < longueur; s += pas) {
      const onde = 0.18 * Math.sin(s * 1.8 - t * 18);
      const fourche = s > longueur - 1.2 ? (s - (longueur - 1.2)) * 0.5 : 0;
      for (const cote of fourche > 0 ? [-1, 1] : [0]) {
        const c0 = plus(p, plus(fois(a.N, cote * fourche), fois(haut, onde)));
        const c1 = plus(c0, fois(dir, pas));
        const lw = fourche > 0 ? 0.2 : 0.32;
        sc.quad([plus(c0, fois(a.N, -lw)), plus(c0, fois(a.N, lw)), plus(c1, fois(a.N, lw)), plus(c1, fois(a.N, -lw))], LANGUE, 0.02, true);
      }
      p = plus(p, fois(dir, pas));
    }
  }
}

function dessinerSerpents(sc, o, battement = 0) {
  if (!o || (o.g ?? 1) <= 0) return;
  const R = repereAile(1, battement);
  for (let i = 0; i < SERPENTS.length; i++) dessinerSerpent(sc, i, o, R);
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

/**
 * e : { tshirt, dechire, debris, noir, gris, cheveux, croixNez, ailes, battement, aura,
 *       gauche (l'aile qui devient serpents, voir dessinerAiles), serpents (voir dessinerSerpent) }
 */
function rendre(P, e, opts, apres) {
  const tenue = e.tshirt ? (e.dechire > 0 ? dechirer(P, e.dechire) : P.habille) : P.bas;
  const skin = transformer(tenue, P.base, P.fin, e);
  const hd = calque(P.m, skin, P.peau, e);
  const sc = scene({ ...opts, aura: e.aura > 0.01 ? { c: VIOLET, force: e.aura, r: 0.3 + 0.28 * Math.min(1, e.aura), y: 0.48 } : null });
  dessinerAiles(sc, P.aile, e.ailes, e.battement ?? 0, e.gauche ?? {});
  dessinerSerpents(sc, e.serpents, e.battement ?? 0);
  dessinerCheveux(sc, e.cheveux);
  dessinerJoueur(sc, skin, P.fin, hd);
  if (!e.tshirt) dessinerHautPendant(sc);
  dessinerLambeaux(sc, e.debris ?? -1);
  apres?.(sc);
  if (e.ailes > 0 || e.serpents) sc.lueur([120, 40, 170], 5, 1.6 * Math.min(1, e.ailes));
  return sc;
}

const FORME2 = { tshirt: false, dechire: 1, noir: 1, gris: 1, cheveux: 1, croixNez: 1, ailes: 1, aura: 0.16 };
const FORME3 = { ...FORME2, gauche: { absente: true }, serpents: { g: 1, em: 1, bouche: 0, langue: 0, t: 0 } };
const SIFFLE = { ...FORME3, aura: 0.3, serpents: { g: 1, em: 1, bouche: 1, langue: 1, t: 0.4 } };

/**
 * Le passage de la forme 2 au niveau 3, dans le temps (secondes). Les deux ailes battent ; l'aura monte, l'aile de sa
 * gauche tremble et des veines violettes la parcourent ; sa peau part en miettes, d'abord entre les doigts ; les doigts
 * restent, grossissent, deviennent des serpents qui se détachent de l'aile, montent en arc au-dessus de son épaule, puis
 * ouvrent la gueule et sifflent. L'aura redescend, les serpents ondulent.
 */
const DEBUT_FONTE = 1.7, DUREE_FONTE = 1.4;
function etat(t) {
  const fonte = Math.max(0, Math.min(1, (t - DEBUT_FONTE) / DUREE_FONTE));
  const siffle = lisse((t - 3.55) / 0.25) * (1 - lisse((t - 4.7) / 0.4));
  return {
    ...FORME2,
    battement: 0.11 * Math.sin(t * 2 * Math.PI * 1.1),
    gauche: {
      teinte: lisse((t - 0.6) / 0.9),
      tremble: 0.045 * Math.sin(t * 41) * lisse((t - 0.8) / 0.5) * (1 - lisse((t - 2.6) / 0.4)),
      fonte, absente: fonte >= 1, t, debutFonte: DEBUT_FONTE, dureeFonte: DUREE_FONTE,
    },
    serpents: t < 1.9 ? null : { g: lisse((t - 1.9) / 0.9), em: lisse((t - 2.5) / 1.1), bouche: siffle, langue: siffle, t },
    aura: t < 0.5 ? 0.16 : t < 3.9 ? 0.16 + 0.74 * lisse((t - 0.5) / 1) : 0.16 + 0.74 * (1 - lisse((t - 3.9) / 1)),
  };
}

const marque = preparer("sasuke.png", false);

/** Colle des images côte à côte (même hauteur), avec leur titre au-dessus. */
function rangee(images, M = 34) {
  const W = images.reduce((t, [, sc]) => t + sc.w, 0), H = M + images[0][1].h;
  const out = Buffer.alloc(W * H * 4, 255);
  let x0 = 0;
  for (const [titre, sc] of images) {
    for (let y = 0; y < sc.h; y++) sc.img.copy(out, ((M + y) * W + x0) * 4, y * sc.w * 4, (y + 1) * sc.w * 4);
    ecrireTexte(out, W, titre, x0 + 12, 10, 3, [30, 30, 40, 255]);
    x0 += sc.w;
  }
  return { w: W, h: H, img: out };
}
function empiler(rangees) {
  const W = Math.max(...rangees.map((r) => r.w)), H = rangees.reduce((t, r) => t + r.h, 0);
  const out = Buffer.alloc(W * H * 4, 255);
  let y0 = 0;
  for (const r of rangees) { for (let y = 0; y < r.h; y++) r.img.copy(out, ((y0 + y) * W) * 4, y * r.w * 4, (y + 1) * r.w * 4); y0 += r.h; }
  return { w: W, h: H, img: out };
}

const mode = process.argv[2] ?? "planche";
if (mode === "peau") {
  // la peau de la forme 2 pour l'aperçu 3D : le skin 64 x 64 agrandi x 4, avec par-dessus le calque fin (marques, croix)
  const skin = transformer(marque.bas, marque.base, marque.fin, FORME2);
  const hd = calque(marque.m, skin, marque.peau, FORME2);
  const px = Buffer.alloc(T * T * 4);
  for (let y = 0; y < T; y++) for (let x = 0; x < T; x++) {
    const i = (y * T + x) * 4;
    px.set(hd[i + 3] >= 128 ? hd.subarray(i, i + 4) : get(skin, (x / R) | 0, (y / R) | 0), i);
  }
  fs.mkdirSync(path.join(ICI, "..", "apercu"), { recursive: true });
  ecrirePng(path.join(ICI, "..", "apercu", "peau-forme2.png"), T, T, px);
  // les couleurs de l'aile dessinée : combien de texels par teinte (arrondie)
  const tex = marque.aile.tex, n = new Map();
  for (let i = 0; i < tex.length; i += 4) if (tex[i + 3] > 128) { const k = [tex[i], tex[i + 1], tex[i + 2]].map((v) => Math.round(v / 24) * 24).join(","); n.set(k, (n.get(k) ?? 0) + 1); }
  console.log([...n].sort((a, b) => b[1] - a[1]).slice(0, 14));
} else if (mode === "etapes") {
  const o = { w: 440, h: 540, echelle: 4.6, pied: 500 };
  const r = rangee([1.3, 2.0, 2.4, 2.8, 3.2, 3.8].map((t) => [String(t).replace(".", " "), rendre(marque, etat(t), { ...o, lacet: -24 })]));
  ecrirePng(path.join(ICI, "etapes.png"), r.w, r.h, r.img);
} else if (mode === "tete") {
  // la tête du serpent du milieu, seule, de près
  const R = repereAile(1, 0);
  const vues = [["COTE", 90], ["FACE", 20], ["DESSUS", 140]].map(([titre, lacet]) => {
    const sc = scene({ w: 600, h: 520, echelle: 30, pied: 260, lacet, tangage: 20, centre: [29, 24, 9] });
    dessinerSerpent(sc, 2, { g: 1, em: 1, bouche: 1, langue: 1, t: 0.3 }, R);
    return [titre, sc];
  });
  const r = rangee(vues);
  ecrirePng(path.join(ICI, "tete.png"), r.w, r.h, r.img);
} else if (mode === "zoom") {
  const o = { w: 900, h: 760, echelle: 10, pied: 470, tangage: 6 };
  const r = rangee([["FACE", rendre(marque, FORME3, { ...o, lacet: -20, centre: [12, 14, 0] })], ["SIFFLE", rendre(marque, SIFFLE, { ...o, lacet: -45, centre: [14, 14, 0] })]]);
  ecrirePng(path.join(ICI, "zoom.png"), r.w, r.h, r.img);
} else if (mode === "essai") {
  // quelques vues rapides, pour régler la forme
  const o = { w: 520, h: 600, echelle: 4.6, pied: 560 };
  const r = rangee([
    ["FACE", rendre(marque, FORME3, { ...o, lacet: -20 })],
    ["DOS", rendre(marque, FORME3, { ...o, lacet: 150 })],
    ["COTE", rendre(marque, FORME3, { ...o, lacet: -100 })],
    ["SIFFLE", rendre(marque, SIFFLE, { ...o, lacet: -40 })],
  ]);
  ecrirePng(path.join(ICI, "essai.png"), r.w, r.h, r.img);
  console.log("essai", r.w, r.h);
} else if (mode === "anim") {
  const dossier = path.join(ICI, "images");
  fs.rmSync(dossier, { recursive: true, force: true });
  fs.mkdirSync(dossier, { recursive: true });
  const N = 96;
  for (let i = 0; i < N; i++) {
    const t = i / 12;
    const lacet = -24 + Math.max(0, t - 4.9) * 55;
    const sc = rendre(marque, etat(t), { lacet, w: 600, h: 600, echelle: 4.9, pied: 560, tangage: 8 });
    ecrirePng(path.join(dossier, String(i).padStart(3, "0") + ".png"), sc.w, sc.h, sc.img);
  }
  console.log("images", N);
}
