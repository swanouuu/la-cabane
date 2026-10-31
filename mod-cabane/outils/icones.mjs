// Les icônes des applis du téléphone (32 x 32, coins arrondis), dessinées ici, inspirées d'iOS.
// Lancer depuis n'importe où :  node mod-cabane/outils/icones.mjs
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const DOSSIER = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources",
  "assets", "cabane", "textures", "gui", "app");
const N = 32;
const SS = 4; // sur-échantillonnage pour des bords lisses

const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const mix = (a, b, t) => a.map((v, i) => v + (b[i] - v) * t);

// formes : renvoient true si le point (x, y), en pixels de l'icône, est dedans
const disque = (cx, cy, r) => (x, y) => (x - cx) ** 2 + (y - cy) ** 2 <= r * r;
const anneau = (cx, cy, r1, r2) => (x, y) => { const d = (x - cx) ** 2 + (y - cy) ** 2; return d <= r2 * r2 && d >= r1 * r1; };
const rect = (x0, y0, x1, y1, r = 0) => (x, y) => {
  if (x < x0 || x > x1 || y < y0 || y > y1) return false;
  const cx = Math.min(Math.max(x, x0 + r), x1 - r), cy = Math.min(Math.max(y, y0 + r), y1 - r);
  return (x - cx) ** 2 + (y - cy) ** 2 <= r * r + 1e-9;
};
const poly = (pts) => (x, y) => {
  let dedans = false;
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const [xi, yi] = pts[i], [xj, yj] = pts[j];
    if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) dedans = !dedans;
  }
  return dedans;
};
const segment = (x0, y0, x1, y1, e) => (x, y) => {
  const dx = x1 - x0, dy = y1 - y0, t = Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy)));
  return (x - x0 - t * dx) ** 2 + (y - y0 - t * dy) ** 2 <= e * e;
};
const ou = (...f) => (x, y) => f.some((g) => g(x, y));
const sauf = (a, b) => (x, y) => a(x, y) && !b(x, y);

/** Une icône : un fond en dégradé (haut → bas) puis des calques [forme, couleur]. */
function icone(nom, haut, bas, calques) {
  const px = Buffer.alloc(N * N * 4);
  const fond = rect(0, 0, N, N, 7.5);
  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) {
      let acc = [0, 0, 0], a = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const fx = x + (sx + 0.5) / SS, fy = y + (sy + 0.5) / SS;
          if (!fond(fx, fy)) continue;
          let c = mix(hex(haut), hex(bas), fy / N);
          for (const [forme, couleur] of calques) if (forme(fx, fy)) c = typeof couleur === "function" ? couleur(fx, fy) : hex(couleur);
          acc = acc.map((v, i) => v + c[i]);
          a++;
        }
      }
      const o = (y * N + x) * 4;
      if (a) { px[o] = acc[0] / a; px[o + 1] = acc[1] / a; px[o + 2] = acc[2] / a; px[o + 3] = 255 * a / (SS * SS); }
    }
  }
  fs.mkdirSync(DOSSIER, { recursive: true });
  fs.writeFileSync(path.join(DOSSIER, nom + ".png"), encoder(px));
}

function encoder(px) {
  const brut = Buffer.alloc(N * (1 + N * 4));
  for (let y = 0; y < N; y++) { brut[y * (1 + N * 4)] = 0; px.copy(brut, y * (1 + N * 4) + 1, y * N * 4, (y + 1) * N * 4); }
  const table = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (b) => { let c = 0xffffffff; for (const v of b) c = table[(c ^ v) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => {
    const t = Buffer.from(type), l = Buffer.alloc(4), c = Buffer.alloc(4);
    l.writeUInt32BE(data.length); c.writeUInt32BE(crc(Buffer.concat([t, data])));
    return Buffer.concat([l, t, data, c]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(N, 0); ihdr.writeUInt32BE(N, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

const BLANC = "#ffffff";

// Téléphone : combiné blanc sur vert
const combine = ou(
  poly([[9, 9], [13, 8], [15, 13], [13, 15], [17, 19], [19, 17], [24, 19], [23, 23], [20, 24], [14, 21], [10, 16], [8, 12]]),
);
icone("telephone", "#65f27e", "#13b931", [[combine, BLANC]]);

// Contacts : silhouette sur gris
icone("contacts", "#d8d8de", "#9c9ca6", [
  [ou(disque(16, 12, 5.5), rect(7, 19, 25, 30, 7)), "#6e6e78"],
]);

// Récents : horloge blanche sur orange
icone("recents", "#ffb340", "#ff7a00", [
  [disque(16, 16, 10.5), BLANC],
  [ou(segment(16, 16, 16, 9, 1.2), segment(16, 16, 21, 18, 1.2)), "#ff8a00"],
  [disque(16, 16, 1.6), "#ff8a00"],
]);

// Piaf : oiseau blanc sur bleu
const oiseau = ou(
  disque(14, 17, 6.5),
  disque(20, 12, 4),
  poly([[23, 11], [28, 11.5], [23.5, 14]]),
  poly([[8, 17], [3, 12], [11, 14]]),
  poly([[9, 21], [14, 25], [20, 21]]),
);
icone("piaf", "#3cb4ff", "#0a7bd6", [[oiseau, BLANC], [disque(21, 11, 0.9), "#0a7bd6"]]);

// Appareil photo : boîtier sombre sur gris clair
icone("photo", "#e9e9ee", "#b4b4bd", [
  [ou(rect(5, 10, 27, 25, 3), rect(11, 7, 19, 11, 1.5)), "#3a3a3f"],
  [disque(16, 17.5, 5.5), "#9aa3ad"],
  [disque(16, 17.5, 3.5), "#1d2a38"],
  [disque(14.8, 16.3, 1.1), "#e8f0ff"],
  [disque(23.5, 13, 1.1), "#ffcc00"],
]);

// Photos : une fleur de couleurs sur blanc
const petales = ["#ff3b30", "#ff9500", "#ffcc00", "#34c759", "#00c7be", "#007aff", "#5856d6", "#ff2d55"];
icone("galerie", "#ffffff", "#f2f2f7", petales.map((c, i) => {
  const a = i * Math.PI / 4;
  return [((x, y) => {
    const dx = x - 16, dy = y - 16;
    const u = dx * Math.cos(a) + dy * Math.sin(a), v = -dx * Math.sin(a) + dy * Math.cos(a);
    return (u - 6) ** 2 / 36 + v * v / 9 <= 1;
  }), c];
}));

// Portefeuille : cartes de couleur dans un étui noir
icone("argent", "#2c2c2e", "#0c0c0e", [
  [rect(6, 8, 26, 14, 2), "#0a84ff"],
  [rect(6, 11, 26, 17, 2), "#ff9f0a"],
  [rect(6, 14, 26, 20, 2), "#30d158"],
  [rect(5, 17, 27, 27, 3), "#48484a"],
  [rect(5, 17, 27, 19, 0), "#3a3a3c"],
]);

// Réglages : engrenage gris
const engrenage = (x, y) => {
  const dx = x - 16, dy = y - 16, r = Math.hypot(dx, dy), a = Math.atan2(dy, dx);
  const dent = Math.cos(a * 8) > 0.35 ? 11 : 9;
  return r <= dent && r >= 4;
};
icone("reglages", "#a8a8b0", "#6c6c74", [[engrenage, "#e5e5ea"], [anneau(16, 16, 5.5, 7), "#8e8e93"]]);

// Cartes à collectionner : deux cartes sur violet
icone("cartes", "#bf5af2", "#7d2ae8", [
  [poly([[8, 9], [19, 6], [24, 24], [13, 27]]), "#ffffff"],
  [poly([[10, 11], [18, 9], [22, 23], [14, 25]]), "#ffd60a"],
]);

// ---------------------------------------------------------------- textures d'objets 16 x 16
function objet(nom, calques) {
  const n = 16, px = Buffer.alloc(n * n * 4);
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    let c = null;
    for (const [forme, couleur] of calques) if (forme(x + 0.5, y + 0.5)) c = typeof couleur === "function" ? couleur(x, y) : hex(couleur);
    if (c) { const o = (y * n + x) * 4; px[o] = c[0]; px[o + 1] = c[1]; px[o + 2] = c[2]; px[o + 3] = 255; }
  }
  const brut = Buffer.alloc(n * (1 + n * 4));
  for (let y = 0; y < n; y++) { brut[y * (1 + n * 4)] = 0; px.copy(brut, y * (1 + n * 4) + 1, y * n * 4, (y + 1) * n * 4); }
  const table = Array.from({ length: 256 }, (_, k) => { let c = k; for (let j = 0; j < 8; j++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (b) => { let c = 0xffffffff; for (const v of b) c = table[(c ^ v) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => { const t = Buffer.from(type), l = Buffer.alloc(4), cc = Buffer.alloc(4); l.writeUInt32BE(data.length); cc.writeUInt32BE(crc(Buffer.concat([t, data]))); return Buffer.concat([l, t, data, cc]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(n, 0); ihdr.writeUInt32BE(n, 4); ihdr[8] = 8; ihdr[9] = 6;
  const dossier = path.join(DOSSIER, "..", "..", "item");
  fs.mkdirSync(dossier, { recursive: true });
  fs.writeFileSync(path.join(dossier, nom + ".png"), Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]));
}
// la puff : un petit cylindre plat de couleur, embout noir en haut, reflet sur le côté
for (const [nom, clair, fonce] of [["puff_rose", "#ff9ccf", "#d9558f"], ["puff_bleue", "#8fd3ff", "#3a8fd9"], ["puff_menthe", "#9cffcf", "#3ac987"]]) {
  objet(nom, [
    [rect(5, 4, 10, 15, 1.5), (x, y) => mix(hex(clair), hex(fonce), (x - 5) / 6)],
    [rect(6, 1, 9, 4, 1), "#222226"],
    [rect(6, 6, 7, 13), "#ffffff"],
    [rect(5, 11, 10, 12), "#ffffff"],
  ]);
}
// le tuyau : un serpentin sombre et un embout doré
objet("tuyau", [
  [ou(anneau(8, 9, 3, 5), segment(3, 9, 2, 15, 1)), "#2a2a2e"],
  [anneau(8, 9, 3.6, 4.2), "#4a4a52"],
  [rect(11, 2, 13, 7, 0.8), "#d4af37"],
  [segment(12, 7, 11.5, 9, 0.9), "#2a2a2e"],
]);

console.log("Icônes écrites dans", DOSSIER);
