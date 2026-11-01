// La police de Beauvais : les icônes des objets (16 x 16) et la tenue portée (couches d'armure 64 x 32).
// Lancer depuis n'importe où :  node mod-cabane/outils/police.mjs
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const RES = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources", "assets", "cabane", "textures");

const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16), 255];

function png(largeur, hauteur, px) {
  const brut = Buffer.alloc(hauteur * (1 + largeur * 4));
  for (let y = 0; y < hauteur; y++) { brut[y * (1 + largeur * 4)] = 0; px.copy(brut, y * (1 + largeur * 4) + 1, y * largeur * 4, (y + 1) * largeur * 4); }
  const table = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (b) => { let c = 0xffffffff; for (const v of b) c = table[(c ^ v) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => {
    const t = Buffer.from(type), l = Buffer.alloc(4), c = Buffer.alloc(4);
    l.writeUInt32BE(data.length); c.writeUInt32BE(crc(Buffer.concat([t, data])));
    return Buffer.concat([l, t, data, c]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(largeur, 0); ihdr.writeUInt32BE(hauteur, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

function ecrire(rel, donnees) {
  const f = path.join(RES, rel);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, donnees);
}

// ---------------------------------------------------------------- formes (comme icones.mjs)
const disque = (cx, cy, r) => (x, y) => (x - cx) ** 2 + (y - cy) ** 2 <= r * r;
const anneau = (cx, cy, r1, r2) => (x, y) => { const d = (x - cx) ** 2 + (y - cy) ** 2; return d <= r2 * r2 && d >= r1 * r1; };
const rect = (x0, y0, x1, y1) => (x, y) => x >= x0 && x <= x1 && y >= y0 && y <= y1;
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

/** Une icône 16 x 16 : des calques [forme, couleur], le dernier gagne ; un contour sombre autour. */
function objet(nom, calques) {
  const n = 16, px = Buffer.alloc(n * n * 4);
  const plein = Array.from({ length: n * n }, () => false);
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    let c = null;
    for (const [forme, couleur] of calques) if (forme(x + 0.5, y + 0.5)) c = hex(couleur);
    if (c) { const o = (y * n + x) * 4; px[o] = c[0]; px[o + 1] = c[1]; px[o + 2] = c[2]; px[o + 3] = 255; plein[y * n + x] = true; }
  }
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    if (plein[y * n + x]) continue;
    const voisin = [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([dx, dy]) => { const a = x + dx, b = y + dy; return a >= 0 && b >= 0 && a < n && b < n && plein[b * n + a]; });
    if (voisin) { const o = (y * n + x) * 4; px[o] = 20; px[o + 1] = 20; px[o + 2] = 28; px[o + 3] = 200; }
  }
  ecrire(`item/${nom}.png`, png(n, n, px));
}

const MARINE = "#1d2b57", MARINE_CLAIR = "#2f4380", MARINE_FONCE = "#141d3b", OR = "#e8c04a", NOIR = "#1b1b20", ARGENT = "#c9ced6", ARGENT_FONCE = "#7d838c";

objet("menottes", [
  [segment(6.5, 9.5, 9.5, 6.5, 0.9), ARGENT_FONCE],
  [anneau(5, 11, 2, 3.4), ARGENT],
  [anneau(11, 5, 2, 3.4), ARGENT],
  [rect(2, 10, 3, 11), ARGENT_FONCE],
  [rect(13, 4, 14, 5), ARGENT_FONCE],
]);
objet("pistolet", [
  [rect(2, 4, 14, 7.9), "#2b2b31"],
  [rect(2, 4, 14, 4.9), "#5a5a63"],
  [poly([[8, 7.5], [12.5, 7.5], [11.5, 14.5], [7.5, 14.5]]), "#25252a"],
  [rect(8.5, 9, 11, 13.5), "#3a3a42"],
  [anneau(6.8, 9.2, 0.8, 1.7), "#2b2b31"],
  [rect(13, 3, 13.9, 3.9), "#5a5a63"],
  [rect(1, 5, 1.9, 6.9), "#111114"],
]);
objet("tenue_police", [
  [poly([[2, 4], [6, 2], [10, 2], [14, 4], [13, 8], [11.5, 7.5], [11.5, 14.5], [4.5, 14.5], [4.5, 7.5], [3, 8]]), MARINE],
  [poly([[6, 2], [8, 5], [10, 2]]), "#d9dde6"],
  [rect(7.6, 5, 8.4, 14), MARINE_FONCE],
  [rect(9.5, 6.5, 10.5, 7.5), OR],
  [rect(4.5, 12, 11.5, 12.9), NOIR],
  [rect(7.5, 12, 8.5, 12.9), ARGENT],
]);
objet("casquette_police", [
  [poly([[3, 9], [4, 5], [8, 3.5], [12, 5], [13, 9]]), MARINE],
  [rect(3, 8, 13, 9.9), NOIR],
  [poly([[2, 10], [14, 10], [12, 12], [4, 12]]), NOIR],
  [rect(7.5, 5.5, 8.5, 6.9), OR],
]);
objet("veste_police", [
  [poly([[2, 4], [6, 2], [10, 2], [14, 4], [13, 9], [11.5, 8.5], [11.5, 14.5], [4.5, 14.5], [4.5, 8.5], [3, 9]]), MARINE],
  [rect(7.6, 3, 8.4, 14), MARINE_FONCE],
  [rect(9.5, 6, 10.5, 7), OR],
  [rect(2.5, 5, 3.5, 6), "#2b55c8"], [rect(2.5, 6, 3.5, 7), "#ffffff"], [rect(2.5, 7, 3.5, 8), "#d63a3a"],
]);
objet("pantalon_police", [
  [poly([[4, 2], [12, 2], [13, 14], [9.5, 14], [8, 6], [6.5, 14], [3, 14]]), MARINE],
  [rect(4, 2, 12, 3), NOIR],
  [segment(4.3, 4, 3.6, 13.5, 0.35), MARINE_CLAIR],
  [segment(11.7, 4, 12.4, 13.5, 0.35), MARINE_CLAIR],
]);
objet("chaussures_police", [
  [poly([[2, 8], [5, 8], [5, 10], [7.5, 11], [7.5, 13], [2, 13]]), NOIR],
  [poly([[8.5, 6], [11.5, 6], [11.5, 8], [14, 9], [14, 11], [8.5, 11]]), NOIR],
  [rect(2, 12.5, 7.5, 13), "#3a3a42"], [rect(8.5, 10.5, 14, 11), "#3a3a42"],
]);
objet("matraque", [
  [segment(3, 13, 12.5, 3.5, 1.15), "#1b1b20"],
  [segment(3.6, 12.4, 12, 4, 0.45), "#3a3a44"],
  [segment(5.5, 9.5, 8, 12, 0.7), "#1b1b20"],
  [segment(2.6, 13.4, 4, 12, 1.25), "#0d0d10"],
]);
objet("voiture_police", [
  [poly([[1, 8], [3, 7], [5, 4], [11, 4], [13, 7], [15, 8], [15, 11], [1, 11]]), "#f2f2f2"],
  [poly([[5.6, 5], [10.4, 5], [12, 7.2], [4, 7.2]]), "#2a3240"],
  [rect(7.7, 5, 8.3, 7.2), "#f2f2f2"],
  [rect(1, 8.6, 15, 9.6), "#2340b0"],
  [disque(4.5, 11.3, 1.9), NOIR], [disque(11.5, 11.3, 1.9), NOIR],
  [disque(4.5, 11.3, 0.8), ARGENT], [disque(11.5, 11.3, 0.8), ARGENT],
  [rect(5.5, 2.6, 7.9, 3.9), "#e53030"], [rect(8.1, 2.6, 10.5, 3.9), "#3a6cff"],
  [rect(14.4, 7.8, 15, 8.5), "#ffe680"],
]);

// ---------------------------------------------------------------- la tenue portée : couches d'armure 64 x 32
function couche() {
  const px = Buffer.alloc(64 * 32 * 4);
  return {
    px,
    set(x, y, c) { if (!c || x < 0 || y < 0 || x > 63 || y > 31) return; const o = (y * 64 + x) * 4; px[o] = c[0]; px[o + 1] = c[1]; px[o + 2] = c[2]; px[o + 3] = c[3]; },
  };
}
/** Les six faces d'une boîte du modèle (comme une peau) ; couleur(face, x, y) peut renvoyer null (transparent). */
function boite(img, ox, oy, w, h, d, couleur) {
  const zone = (x0, y0, lw, lh, face) => { for (let y = 0; y < lh; y++) for (let x = 0; x < lw; x++) img.set(x0 + x, y0 + y, couleur(face, x, y)); };
  zone(ox + d, oy, w, d, "haut");
  zone(ox + d + w, oy, w, d, "bas");
  zone(ox, oy + d, d, h, "droite");
  zone(ox + d, oy + d, w, h, "avant");
  zone(ox + d + w, oy + d, d, h, "gauche");
  zone(ox + 2 * d + w, oy + d, w, h, "arriere");
}
const bruit = (c, x, y) => { const k = 1 + (((x * 7 + y * 13) % 5) - 2) * 0.025; return [Math.min(255, c[0] * k), Math.min(255, c[1] * k), Math.min(255, c[2] * k), 255]; };
const m = hex(MARINE), mf = hex(MARINE_FONCE), mc = hex(MARINE_CLAIR), or = hex(OR), noir = hex(NOIR), argent = hex(ARGENT), blanc = hex("#f4f4f4");

const l1 = couche();
// la casquette : calotte marine, bandeau noir, insigne doré devant ; le visage reste visible
boite(l1, 0, 0, 8, 8, 8, (face, x, y) => {
  if (face === "haut") return bruit(x > 1 && x < 6 && y > 1 && y < 6 ? mc : m, x, y);
  if (face === "bas") return null;
  if (y < 2) return face === "avant" && (x === 3 || x === 4) ? or : bruit(m, x, y);
  if (y === 2) return noir;
  if (y === 3 && face === "avant") return noir; // la visière
  return null;
});
// la veste : marine, fermeture au milieu, écusson doré, ceinturon, « POLICE » en blanc dans le dos
boite(l1, 16, 16, 8, 12, 4, (face, x, y) => {
  if (face === "haut") return bruit(m, x, y);
  if (face === "bas") return noir;
  if (y === 11) return face === "avant" && (x === 3 || x === 4) ? argent : noir;
  if (face === "avant") {
    if (x === 3 || x === 4) return y === 0 ? blanc : mf;
    if ((x === 5 || x === 6) && (y === 2 || y === 3)) return or;
    return bruit(m, x, y);
  }
  if (face === "arriere" && (y === 2 || y === 3)) return x === 0 || x === 7 ? bruit(m, x, y) : (x + y) % 3 === 0 ? mf : blanc;
  return bruit(m, x, y);
});
// les manches : écusson tricolore à l'épaule, les mains restent nues
boite(l1, 40, 16, 4, 12, 4, (face, x, y) => {
  if (face === "bas" || y >= 10) return null;
  if (face === "haut") return bruit(m, x, y);
  if (face === "droite" && y >= 1 && y <= 3) return [hex("#2b55c8"), blanc, hex("#d63a3a")][Math.min(2, x)] ?? bruit(m, x, y);
  if (y === 9) return mf;
  return bruit(m, x, y);
});
// les chaussures (le bas des jambes)
boite(l1, 0, 16, 4, 12, 4, (face, x, y) => {
  if (face === "bas") return noir;
  if (face === "haut") return null;
  if (y >= 9) return y === 11 ? hex("#2a2a30") : noir;
  return null;
});
ecrire("models/armor/police_layer_1.png", png(64, 32, l1.px));

const l2 = couche();
// le pantalon : marine, une fine bande plus claire sur le côté ; le haut du corps n'a que la taille
boite(l2, 16, 16, 8, 12, 4, (face, x, y) => (face === "haut" || face === "bas" || y < 9 ? null : bruit(m, x, y)));
boite(l2, 0, 16, 4, 12, 4, (face, x, y) => {
  if (face === "haut") return bruit(m, x, y);
  if (face === "bas") return null;
  if ((face === "droite" || face === "gauche") && x === 1) return mc;
  return bruit(m, x, y);
});
ecrire("models/armor/police_layer_2.png", png(64, 32, l2.px));

// ---------------------------------------------------------------- le pistolet en 3D : une petite palette de 16 x 16
{
  const n = 16, px = Buffer.alloc(n * n * 4);
  const zones = [[0, 0, "#2b2b31"], [4, 0, "#151518"], [8, 0, "#4c4c55"], [12, 0, "#1f1f24"], [0, 4, "#3a3a42"], [4, 4, "#c9ced6"], [8, 4, "#ffd75e"], [12, 4, "#e53030"]];
  for (const [x0, y0, c] of zones) {
    const col = hex(c);
    for (let y = y0; y < y0 + 4; y++) for (let x = x0; x < x0 + 4; x++) {
      const k = 1 + (((x * 5 + y * 3) % 4) - 1.5) * 0.04;
      const o = (y * n + x) * 4; px[o] = Math.min(255, col[0] * k); px[o + 1] = Math.min(255, col[1] * k); px[o + 2] = Math.min(255, col[2] * k); px[o + 3] = 255;
    }
  }
  ecrire("item/pistolet_3d.png", png(n, n, px));
}

console.log("Police dessinée dans", RES);
