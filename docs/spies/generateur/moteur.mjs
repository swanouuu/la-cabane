// Le moteur des maquettes (repris de docs/hachibi2, avec quelques chiffres et signes de plus dans la police) : PNG, modèle du joueur texel par texel, rendu avec tampon de
// profondeur, mèches en pointe, petite police. Coordonnées en pixels du modèle : pieds à y = 0, l'avant vers +z, la
// gauche du personnage vers +x.
import fs from "node:fs";
import zlib from "node:zlib";

export function lire(fichier) {
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

export function ecrirePng(fichier, w, h, rgba) {
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) rgba.copy(brut, y * (1 + w * 4) + 1, y * w * 4, (y + 1) * w * 4);
  const t = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const x of buf) c = t[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  fs.writeFileSync(fichier, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]));
}

export const PARTIES = (fin) => [
  ["tete", [-4, 24, -4, 4, 32, 4], [0, 0], [32, 0], 0.5],
  ["corps", [-4, 12, -2, 4, 24, 2], [16, 16], [16, 32], 0.25],
  ["brasD", fin ? [-7, 12, -2, -4, 24, 2] : [-8, 12, -2, -4, 24, 2], [40, 16], [40, 32], 0.25],
  ["brasG", fin ? [4, 12, -2, 7, 24, 2] : [4, 12, -2, 8, 24, 2], [32, 48], [48, 48], 0.25],
  ["jambeD", [-4, 0, -2, 0, 12, 2], [0, 16], [0, 32], 0.25],
  ["jambeG", [0, 0, -2, 4, 12, 2], [16, 48], [0, 48], 0.25],
];

export function texels(boite, uv, gonfle, R = 1) {
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

export const clair = (c, k) => c.slice(0, 3).map((v) => Math.min(255, v * k));

export const mix = (a, b, k) => a.map((v, i) => v + (b[i] - v) * k);

export const lum = (c) => 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2];

export const lisse = (t) => { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); };

export const hache = (i, j, k) => { let h = (i * 374761393 + j * 668265263 + k * 2147483647) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };

export const norme = (v) => { const l = Math.hypot(...v) || 1; return v.map((c) => c / l); };

export const croix = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];

export const tourner = (v, n, ang) => { const c = Math.cos(ang), s = Math.sin(ang), k = croix(n, v), d = n[0] * v[0] + n[1] * v[1] + n[2] * v[2]; return v.map((x, i) => x * c + k[i] * s + n[i] * d * (1 - c)); };

export function scene({ lacet = -28, tangage = 10, echelle = 9, w = 360, h = 520, pied = null, aura = null, fond = [236, 236, 242], centre = [0, 0, 0] }) {
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

export function meche(sc, b, dir, longueur, largeur, couleur) {
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

export const POLICE = {
  A: "010101111101101", B: "110101110101110", C: "011100100100011", D: "110101101101110", E: "111100110100111", F: "111100110100100",
  G: "011100101101011", H: "101101111101101", I: "111010010010111", J: "001001001101010", K: "101101110101101", L: "100100100100111",
  M: "101111111101101", N: "110101101101101", O: "010101101101010", P: "110101110100100", Q: "010101101110011", R: "110101110101101",
  S: "011100010001110", T: "111010010010010", U: "101101101101111", V: "101101101101010", W: "101101111111101", X: "101101010101101",
  Y: "101101010010010", Z: "111001010100111", " ": "000000000000000", 1: "010110010010111", 2: "110001010100111", 3: "110001010001110",
  4: "101101111001001", 5: "111100110001110", 6: "011100111101111", 7: "111001010010010", 8: "111101111101111",
  9: "111101111001110", 0: "111101101101111", "+": "000010111010000", "-": "000000111000000", ":": "000010000010000",
  "/": "001001010100100", ".": "000000000000010", ",": "000000000010100", "'": "010010000000000", "!": "010010010000010",
};

export function ecrireTexte(img, W, texte, x0, y0, t, couleur) {
  let x = x0;
  for (const ch of texte.toUpperCase()) {
    const m = POLICE[ch] ?? POLICE[" "];
    for (let k = 0; k < 15; k++) if (m[k] === "1") for (let dy = 0; dy < t; dy++) for (let dx = 0; dx < t; dx++) img.set(couleur, ((y0 + Math.floor(k / 3) * t + dy) * W + x + (k % 3) * t + dx) * 4);
    x += 4 * t;
  }
}

