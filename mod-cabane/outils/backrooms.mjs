// Les backrooms (/backroom), d'après l'aperçu validé (docs/backrooms/apercu) : les mêmes peintures pixel par pixel, les
// mêmes recettes de sons. Écrit :
//  - les blocs du niveau 0 (papier peint, plinthe humide, moquette, plafond, néons, casier, fausse porte, panneau EXIT),
//    leurs textures, modèles et états ;
//  - l'atlas des cinq monstres (textures/entity/backrooms/monstres.png), dont les tuiles sont rangées dans l'ordre de
//    client/backrooms/ModeleMonstres.TUILES ;
//  - la vue depuis l'intérieur d'un casier (textures/gui/backrooms/casier.png) ;
//  - la dimension cabane:backrooms (type, générateur du mod, biome) ;
//  - les sons (bourdon des néons, pas, « il t'a vu », cœur, casier, reniflement, grésillement, les cinq cris).
// Appelé par generer.mjs ; lancé seul (node outils/backrooms.mjs), il écrit aussi les sons dans sounds.json et les noms
// dans les fichiers de langue.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";
import { execFileSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const RACINE = path.join(ICI, "..", "..");

// ---------------------------------------------------------------- un PNG à partir d'une fonction (x, y) → [r, g, b(, a)]
const CRC = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc = (buf) => { let c = 0xffffffff; for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
function encoder(w, h, rgba) {
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) {
    brut[y * (1 + w * 4)] = 0;
    rgba.copy(brut, y * (1 + w * 4) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const bloc = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}
/** Une toile : w × h pixels RGBA, qu'on peint par morceaux puis qu'on encode. */
class Toile {
  constructor(w, h) { this.w = w; this.h = h; this.px = Buffer.alloc(w * h * 4); }
  peindre(f, x0 = 0, y0 = 0, w = 16, h = 16) {
    for (let y = 0; y < h; y++)
      for (let x = 0; x < w; x++) {
        const c = f(x, y);
        const o = ((y0 + y) * this.w + x0 + x) * 4;
        for (let i = 0; i < 3; i++) this.px[o + i] = Math.max(0, Math.min(255, Math.round(c[i])));
        this.px[o + 3] = c.length > 3 ? Math.max(0, Math.min(255, Math.round(c[3]))) : 255;
      }
    return this;
  }
  png() { return encoder(this.w, this.h, this.px); }
}
const image = (f, w = 16, h = 16) => new Toile(w, h).peindre(f, 0, 0, w, h).png();

// ---------------------------------------------------------------- les peintures (les mêmes que l'aperçu, textures.js)
const bruit = (x, y, g = 0) => {
  let h = (x * 374761393 + y * 668265263 + g * 2147483647) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
};
const teinte = (c, d) => c.map((v, i) => (i < 3 ? v + d : v));
const melange = (a, b, t) => a.map((v, i) => v + (b[i] - v) * t);
const fois = (c, k) => [c[0] * k, c[1] * k, c[2] * k];

const papier = (x, y) => {
  const cx = x % 8;
  const cy = (y + (x >= 8 ? 4 : 0)) % 8;
  let c = [214, 197, 120];
  if (cx === 0 || cx === 1) c = [199, 181, 103];
  if ((cx === 4 && (cy === 2 || cy === 4)) || ((cx === 3 || cx === 5) && cy === 3)) c = [188, 168, 92];
  const n = (bruit(x, y, 1) - 0.5) * 9;
  return [c[0] + n, c[1] + n, c[2] + n * 0.6];
};
const papierBas = (x, y) => {
  const bord = 10 + Math.floor(bruit(x, 0, 5) * 3);
  const c = papier(x, y);
  if (y === 15) return teinte([96, 80, 44], (bruit(x, y, 6) - 0.5) * 8);
  if (y < bord) return c;
  const t = Math.min(1, (y - bord + 1) / 5);
  return melange(c, [138, 116, 62], 0.35 + t * 0.45);
};
const papierTache = (x, y) => {
  const d = Math.hypot((x - 7.5) / 1.1, y - 6.5) + (bruit(x, y, 9) - 0.5) * 1.6;
  const c = papier(x, y);
  if (d < 4.2) return melange(c, [168, 140, 72], 0.45);
  if (d < 5.2) return melange(c, [140, 112, 56], 0.55);
  if (d < 6.2 && y > 6) return melange(c, [170, 146, 80], 0.25);
  return c;
};
const moquette = (x, y) => {
  const n = (bruit(x, y, 21) - 0.5) * 18 + (bruit(x >> 1, y >> 1, 22) - 0.5) * 8;
  const fibre = bruit(x, y, 23) < 0.08 ? -16 : 0;
  return [152 + n + fibre, 133 + n + fibre, 78 + n * 0.5 + fibre];
};
const moquetteTache = (x, y) => {
  const d = Math.hypot(x - 8, (y - 8) * 1.2) + (bruit(x, y, 24) - 0.5) * 2.4;
  const c = moquette(x, y);
  if (d < 4.5) return melange(c, [92, 78, 42], 0.6);
  if (d < 6.5) return melange(c, [112, 96, 52], 0.35);
  return c;
};
const plafond = (x, y) => {
  if (x === 0 || y === 0) return [166, 160, 140];
  if (x === 15 || y === 15) return [196, 190, 170];
  const n = (bruit(x, y, 31) - 0.5) * 8;
  if (bruit(x, y, 32) < 0.13) return [186 + n, 180 + n, 160 + n];
  return [222 + n, 216 + n, 194 + n];
};
const neon = (x, y) => {
  if (x === 0 || y === 0 || x === 15 || y === 15) return [150, 150, 142];
  if (x === 1 || y === 1 || x === 14 || y === 14) return [196, 196, 186];
  if ((y >= 4 && y <= 5) || (y >= 10 && y <= 11)) return [255, 255, 250];
  if (x % 3 === 0 || y % 3 === 0) return [226, 228, 214];
  return [240, 242, 230];
};
const neonMort = (x, y) => {
  if (x === 0 || y === 0 || x === 15 || y === 15) return [110, 108, 100];
  const n = (bruit(x, y, 33) - 0.5) * 10;
  if ((y >= 4 && y <= 5) || (y >= 10 && y <= 11)) return [104 + n, 102 + n, 92 + n];
  if (x % 3 === 0 || y % 3 === 0) return [70 + n, 68 + n, 62 + n];
  return [84 + n, 82 + n, 74 + n];
};
// le néon qui faiblit : le diffuseur éteint (18 % de sa lumière, comme dans l'aperçu), le cadre inchangé
const neonFaible = (x, y) => (x <= 1 || y <= 1 || x >= 14 || y >= 14 ? neon(x, y) : fois(neon(x, y), 0.22));

const acier = [94, 114, 108];
const casierCote = (x, y) => {
  if (x === 0 || x === 15) return [66, 82, 78];
  const n = (bruit(x, y, 41) - 0.5) * 8;
  return [acier[0] + n, acier[1] + n, acier[2] + n];
};
const fente = (x, y, lignes) => x >= 4 && x <= 11 && lignes.includes(y);
const casierHaut = (x, y) => {
  if (x === 0 || x === 15 || y === 0) return [58, 72, 68];
  if (x === 1 || x === 14) return [80, 98, 92];
  if (fente(x, y, [3, 5, 7, 9])) return [16, 20, 20];
  if (fente(x, y, [4, 6, 8, 10])) return [132, 150, 144];
  if (y >= 12 && y <= 13 && x >= 6 && x <= 9) return x === 7 || x === 8 ? [60, 58, 50] : [206, 200, 172];
  return casierCote(x, y);
};
const casierBas = (x, y) => {
  if (x === 0 || x === 15 || y === 15) return [58, 72, 68];
  if (x === 1 || x === 14) return [80, 98, 92];
  if (x >= 11 && x <= 12 && y >= 1 && y <= 6) return x === 11 ? [44, 48, 48] : [150, 158, 154];
  if (fente(x, y, [10, 12])) return [16, 20, 20];
  if (fente(x, y, [11, 13])) return [132, 150, 144];
  if (bruit(x, y, 42) < 0.05) return [120, 84, 52];
  return casierCote(x, y);
};
const casierDessus = (x, y) => teinte(casierCote(x, y), -22);

const porteHaut = (x, y) => {
  if (x === 0 || x === 15) return [70, 70, 68];
  if (x >= 5 && x <= 10 && y >= 3 && y <= 9) return (x + y) % 3 === 0 ? [140, 150, 150] : [52, 62, 66];
  if (x >= 4 && x <= 11 && y >= 2 && y <= 10) return [88, 88, 84];
  const n = (bruit(x, y, 51) - 0.5) * 6;
  return [122 + n, 122 + n, 118 + n];
};
const porteBas = (x, y) => {
  if (x === 0 || x === 15 || y === 15) return [70, 70, 68];
  if (y >= 2 && y <= 3 && x >= 2 && x <= 13) return y === 2 ? [206, 206, 200] : [150, 150, 146];
  const n = (bruit(x, y, 52) - 0.5) * 6;
  return [122 + n, 122 + n, 118 + n];
};
const LETTRES = { E: ["111", "100", "110", "100", "111"], X: ["101", "101", "010", "101", "101"], I: ["1", "1", "1", "1", "1"], T: ["111", "010", "010", "010", "010"] };
const texteExit = (() => {
  const pts = new Set();
  let x0 = 1;
  for (const l of "EXIT") {
    const m = LETTRES[l];
    m.forEach((ligne, j) => [...ligne].forEach((b, i) => b === "1" && pts.add(x0 + i + "," + (6 + j))));
    x0 += m[0].length + 1;
  }
  return pts;
})();
const sortie = (x, y) => {
  if (y < 4 || y > 12 || x < 0 || x > 14) return [0, 0, 0, 0];
  if (y === 4 || y === 12 || x === 0 || x === 14) return [70, 70, 70, 255];
  if (texteExit.has(x + "," + y)) return [255, 60, 46, 255];
  return [46, 10, 10, 255];
};

// ---------------------------------------------------------------- les monstres (les mêmes que l'aperçu, monstres.js)
const t3 = (c, d) => [c[0] + d, c[1] + d, c[2] + d];
const peauR = (x, y) => {
  const n = (bruit(x, y, 101) - 0.5) * 16;
  const tache = bruit(x >> 1, y >> 1, 102) < 0.2 ? -24 : 0;
  return [190 + n + tache, 182 + n + tache, 170 + n + tache];
};
const cotesR = (x, y) => {
  const c = peauR(x, y);
  if (x > 1 && x < 14 && y % 4 === 1) return t3(c, -50);
  if (x > 1 && x < 14 && y % 4 === 2) return t3(c, 12);
  return c;
};
const dosR = (x, y) => {
  const c = peauR(x, y);
  if (x === 7 || x === 8) return t3(c, y % 3 === 0 ? 18 : -36);
  return c;
};
const yeuxR = (x, y, c) => {
  for (const [cx, cy, pente] of [[4.5, 6, 0.25], [11.5, 6, -0.25]]) {
    const yy = y - cy - (x - cx) * pente;
    const d = ((x - cx) / 2.5) ** 2 + (yy / 3) ** 2;
    if (d < 1) return Math.abs(x - cx) < 0.6 && Math.abs(yy - 0.5) < 0.6 ? [236, 230, 218] : [12, 6, 6];
    if (d < 1.7) return t3(c, -44);
  }
  return null;
};
const visageR = (x, y) => {
  const c = peauR(x, y);
  const o = yeuxR(x, y, c);
  if (o) return o;
  const dm = ((x - 7.5) / 3.4) ** 2 + ((y - 12.8) / 2.4) ** 2;
  if (dm < 1) return (y === 11 || y === 12) && x % 2 === 0 ? [232, 226, 200] : [24, 4, 4];
  if (dm < 1.5) return t3(c, -32);
  return c;
};
const machoireR = (x, y) => {
  const c = peauR(x, y + 3);
  if (y <= 4 && x > 2 && x < 13) return y <= 2 && x % 2 === 1 ? [232, 226, 200] : [24, 4, 4];
  return c;
};
const noir = (x, y) => {
  const n = (bruit(x, y, 201) - 0.5) * 8;
  return [11 + n, 10 + n, 13 + n];
};
const SOURIRE = { haut: (u) => 19 - 6 * u * u, bas: (u) => 29.5 - 13.5 * u * u };
const sourire = (partie) => (x, y) => {
  for (const [cx, cy, a] of [[9.5, 9, 0.38], [22.5, 9, -0.38]]) {
    const dx = x + 0.5 - cx, dy = y + 0.5 - cy;
    const rx = dx * Math.cos(a) + dy * Math.sin(a), ry = -dx * Math.sin(a) + dy * Math.cos(a);
    if ((rx / 4.6) ** 2 + (ry / 1.7) ** 2 < 1) return partie === "bas" ? [0, 0, 0, 0] : [255, 255, 250, 255];
  }
  const u = (x + 0.5 - 16) / 14;
  if (Math.abs(u) > 1) return [0, 0, 0, 0];
  const yt = SOURIRE.haut(u), yb = SOURIRE.bas(u);
  const yy = y + 0.5;
  if (yy < yt || yy > yb) return [0, 0, 0, 0];
  const ym = (yt + yb) / 2;
  if (Math.abs(yy - ym) < 0.7) return [0, 0, 0, 0];
  if (partie === "haut" && yy > ym) return [0, 0, 0, 0];
  if (x % 3 === 0) return [0, 0, 0, 0];
  return [252, 250, 240, 255];
};
const machoireS = (x, y) => {
  const u = (x + 0.5 - 16) / 14;
  if (Math.abs(u) > 0.92) return [0, 0, 0, 0];
  if (y < 2 || y > 9 || x % 3 === 0) return [0, 0, 0, 0];
  const pointe = y < 4 && (x % 3 === 1 ? y < 3 : false);
  return pointe ? [0, 0, 0, 0] : [252, 250, 240, 255];
};
const jaune = (x, y) => {
  const n = (bruit(x, y, 301) - 0.5) * 10;
  return [228 + n, 194 + n, 62 + n * 0.5];
};
const courbe = (x) => 8 + 3.4 * (1 - ((x - 7.5) / 4.5) ** 2);
const smiley = (x, y) => {
  const c = jaune(x, y);
  if ((x === 5 || x === 10) && y >= 3 && y <= 6) return [20, 16, 10];
  if (x >= 3 && x <= 12 && Math.abs(y - courbe(x)) < 0.75) return [20, 16, 10];
  return c;
};
const dechire = (x, y) => {
  const c = jaune(x, y);
  if ((x === 4 || x === 5 || x === 10 || x === 11) && y >= 3 && y <= 6) return y === 3 || y === 6 ? [40, 0, 0] : [230, 24, 20];
  if (x >= 2 && x <= 13) {
    const haut = courbe(x) - 1.2, bas = courbe(x) + 1.5 + 2.6 * (1 - ((x - 7.5) / 5.5) ** 2);
    if (y >= haut && y <= bas) {
      if (y < haut + 1.2 && x % 2 === 0) return [240, 236, 220];
      if (y > bas - 1.2 && x % 2 === 1) return [240, 236, 220];
      return [26, 0, 0];
    }
    if (y > bas && y < bas + 3 && bruit(x, 0, 302) < 0.35) return [150, 10, 10];
  }
  return c;
};
const chapeau = (x, y) => ((x + y) % 6 < 3 ? [222, 44, 60] : [52, 98, 222]);
const ballon = (x, y) => {
  if (x >= 3 && x <= 5 && y >= 3 && y <= 6) return [255, 150, 150];
  const n = (bruit(x, y, 303) - 0.5) * 10;
  return [206 + n, 28, 40];
};
const unie = (c, g, a = 6) => (x, y) => {
  const n = (bruit(x, y, g) - 0.5) * a * 2;
  return [c[0] + n, c[1] + n, c[2] + n];
};
const peauH = unie([212, 172, 142], 401);
const cheveux = unie([46, 35, 27], 402, 7);
const chemise = (x, y) => {
  const c = unie([228, 228, 220], 403, 4)(x, y);
  return (x + y * 3) % 11 === 0 ? t3(c, -14) : c;
};
const chemiseDevant = (x, y) => {
  if (y <= 1 && x >= 6 && x <= 9) return [150, 28, 36];
  if (x >= 7 && x <= 8 && y <= 12) return y === 12 ? [118, 20, 28] : [164, 32, 40];
  if (y === 0) return [198, 198, 190];
  if ((x === 5 || x === 10) && y % 4 === 2) return [170, 170, 160];
  return chemise(x, y);
};
const cheveuxArriere = (x, y) => (y < 11 ? cheveux(x, y) : peauH(x, y));
const cheveuxCote = (x, y) => (y < 4 || (y < 9 && (x < 3 || x > 12)) ? cheveux(x, y) : peauH(x, y));
const sansVisage = (x, y) => {
  if (y < 3) return cheveux(x, y);
  const c = peauH(x, y);
  if (y >= 6 && y <= 7 && ((x >= 3 && x <= 5) || (x >= 10 && x <= 12))) return t3(c, -9);
  if (y === 11 && x >= 6 && x <= 9) return t3(c, -5);
  return c;
};
const fenteV = (x, y) => {
  if (y < 3) return cheveux(x, y);
  const c = peauH(x, y);
  const l = 3.1 * Math.sin((Math.PI * (y - 2.5)) / 13.5);
  const dx = Math.abs(x + 0.5 - 8);
  if (dx < l - 0.9) return dx > l - 1.9 && y % 2 === 0 ? [236, 230, 210] : [34, 0, 2];
  if (dx < l + 0.2) return [140, 18, 22];
  if (dx < l + 1.2) return t3(c, -38);
  return c;
};
const fourrure = (x, y) => {
  const n = (bruit(x, y, 501) - 0.5) * 30;
  return [124 + n, 110 + n, 90 + n * 0.8];
};
const ventreP = (x, y) => t3(fourrure(x, y), y % 4 < 2 ? -30 : 8);
const aile = (x, y) => {
  const n = (bruit(x, y, 502) - 0.5) * 16;
  const c = [154 + n, 140 + n, 114 + n];
  const d = Math.hypot(x - 11, y - 7);
  if (d < 1.6) return [16, 14, 12];
  if (d < 2.6) return [214, 176, 70];
  if (d < 3.3) return [60, 48, 36];
  if (x === 0 || y === 0 || x === 15 || y === 15) return t3(c, -40);
  if ((x - y) % 5 === 0 || x === 4) return t3(c, -26);
  if (x < 4) return t3(c, -16);
  return c;
};
const oeilP = (x, y) => ((x === 4 || x === 5) && (y === 4 || y === 5) ? [230, 230, 225] : [14, 13, 16]);
const antenne = (x, y) => (x === 7 || x === 8 ? [70, 58, 44] : y % 2 === 0 ? [110, 96, 76] : [0, 0, 0, 0]);

/** Les tuiles 16 × 16 de l'atlas, dans l'ordre de ModeleMonstres.TUILES (8 par rangée). */
export const TUILES = [
  ["peauR", peauR], ["cotesR", cotesR], ["dosR", dosR], ["visageR", visageR], ["machoireR", machoireR], ["noir", noir],
  ["jaune", jaune], ["smiley", smiley], ["dechire", dechire], ["chapeau", chapeau], ["ballon", ballon],
  ["blanc", () => [236, 236, 230]], ["ficelle", () => [220, 220, 220]],
  ["peauH", peauH], ["cheveux", cheveux], ["cheveuxArriere", cheveuxArriere], ["cheveuxCote", cheveuxCote],
  ["chemise", chemise], ["chemiseDevant", chemiseDevant], ["pantalon", unie([48, 50, 58], 404)], ["chaussure", unie([26, 22, 20], 405, 3)],
  ["sansVisage", sansVisage], ["fente", fenteV], ["fourrure", fourrure], ["ventreP", ventreP], ["aile", aile], ["oeilP", oeilP], ["antenne", antenne],
];

function atlasMonstres() {
  const t = new Toile(128, 128);
  TUILES.forEach(([, f], i) => t.peindre(f, (i % 8) * 16, Math.floor(i / 8) * 16));
  // les grandes : le sourire (32 × 32), le sourire sans le bas (bouche ouverte) et la rangée de dents du bas (32 × 16)
  t.peindre(sourire("tout"), 0, 64, 32, 32);
  t.peindre(sourire("haut"), 32, 64, 32, 32);
  t.peindre(machoireS, 64, 64, 32, 16);
  return t.png();
}

// ---------------------------------------------------------------- la vue depuis un casier (comme B.toileCasier)
function vueCasier(w = 640, h = 360) {
  const fx = w * 0.16, fw = w * 0.68, fh = h * 0.07, pas = h * 0.135, y0 = h * 0.17;
  return image((x, y) => {
    let c = [0x15, 0x1a, 0x19, 255];
    for (let i = 0; i < 5; i++) {
      const yf = y0 + i * pas;
      if (x >= fx && x < fx + fw) {
        if (y >= yf && y < yf + fh) return [0, 0, 0, 0];
        if (y >= yf + fh && y < yf + fh + h * 0.012) c = [0x3b, 0x46, 0x44, 255];
        if (y >= yf - h * 0.008 && y < yf) c = [5, 7, 7, 255];
      }
    }
    for (const [rx, ry] of [[0.08, 0.08], [0.92, 0.08], [0.08, 0.92], [0.92, 0.92]]) {
      if (Math.abs(x - rx * w) < 6 && Math.abs(y - ry * h) < 6) c = [0x2a, 0x32, 0x31, 255];
    }
    // l'ombre qui gagne vers les bords
    const d = Math.hypot(x - w / 2, y - h / 2);
    const k = Math.max(0, Math.min(1, (d - h * 0.2) / (h * 0.7))) * 0.65;
    return [c[0] * (1 - k), c[1] * (1 - k), c[2] * (1 - k), c[3]];
  }, w, h);
}

// ---------------------------------------------------------------- les modèles
const face = (texture, extra = {}) => ({ texture, ...extra });
const LUEUR = { neoforge_data: { block_light: 15, sky_light: 15 } };
const cube = (textures) => ({ parent: "minecraft:block/cube", textures: { particle: textures.north, ...textures } });
const tout = (t) => cube({ up: t, down: t, north: t, south: t, east: t, west: t });
const tourne = (base) => Object.fromEntries(["north", "east", "south", "west"].map((d, i) => [d, { ...base, ...(i ? { y: i * 90 } : {}) }]));
const B = (nom) => `cabane:block/backrooms_${nom}`;

/** Un bloc de néon : la dalle du plafond, et dessous le diffuseur qui brille. */
const modeleNeon = (texture, brille) => ({
  parent: "minecraft:block/block",
  textures: { particle: B("plafond"), plafond: B("plafond"), neon: texture },
  elements: [{
    from: [0, 0, 0], to: [16, 16, 16],
    faces: { down: face("#neon", { cullface: "down" }), up: face("#plafond", { cullface: "up" }), north: face("#plafond", { cullface: "north" }),
      south: face("#plafond", { cullface: "south" }), east: face("#plafond", { cullface: "east" }), west: face("#plafond", { cullface: "west" }) },
    ...(brille ? LUEUR : {}),
  }],
});

/** Un bloc avec une face « devant » au nord, tourné ensuite par l'état. */
const devant = (avant, cote, dessus, dessous = dessus, arriere = cote) => ({
  parent: "minecraft:block/cube",
  textures: { particle: cote, north: avant, south: arriere, east: cote, west: cote, up: dessus, down: dessous },
});

function blocs(ecrire) {
  const textures = {
    papier, papier_bas: papierBas, papier_tache: papierTache, moquette, moquette_tache: moquetteTache, plafond, neon,
    neon_mort: neonMort, casier_cote: casierCote, casier_haut: casierHaut, casier_bas: casierBas, casier_dessus: casierDessus,
    porte_haut: porteHaut, porte_bas: porteBas, sortie,
  };
  for (const [nom, f] of Object.entries(textures)) ecrire(`assets/cabane/textures/block/backrooms_${nom}.png`, image(f));
  // les néons qui clignotent : deux images (allumé, faible) et deux rythmes différents, pour qu'ils ne clignotent pas ensemble
  const deux = new Toile(16, 32).peindre(neon, 0, 0).peindre(neonFaible, 0, 16).png();
  const rythmes = {
    a: [[0, 46], [1, 2], [0, 3], [1, 3], [0, 70], [1, 1], [0, 2], [1, 8], [0, 30], [1, 2], [0, 1], [1, 2]],
    b: [[0, 90], [1, 3], [0, 2], [1, 1], [0, 4], [1, 14], [0, 24], [1, 2], [0, 52], [1, 1], [0, 3], [1, 5]],
  };
  for (const [k, r] of Object.entries(rythmes)) {
    ecrire(`assets/cabane/textures/block/backrooms_neon_clignote_${k}.png`, deux);
    ecrire(`assets/cabane/textures/block/backrooms_neon_clignote_${k}.png.mcmeta`, { animation: { frames: r.map(([index, time]) => ({ index, time })) } });
  }

  const modeles = {
    mur: tout(B("papier")),
    mur_tache: cube({ up: B("papier"), down: B("papier"), north: B("papier_tache"), south: B("papier"), east: B("papier_tache"), west: B("papier") }),
    mur_tache2: cube({ up: B("papier"), down: B("papier"), north: B("papier"), south: B("papier_tache"), east: B("papier"), west: B("papier_tache") }),
    plinthe: cube({ up: B("papier"), down: B("papier"), north: B("papier_bas"), south: B("papier_bas"), east: B("papier_bas"), west: B("papier_bas") }),
    moquette: tout(B("moquette")),
    moquette_tache: cube({ up: B("moquette_tache"), down: B("moquette"), north: B("moquette"), south: B("moquette"), east: B("moquette"), west: B("moquette") }),
    plafond: tout(B("plafond")),
    neon: modeleNeon(B("neon"), true),
    neon_clignote_a: modeleNeon(B("neon_clignote_a"), true),
    neon_clignote_b: modeleNeon(B("neon_clignote_b"), true),
    neon_mort: modeleNeon(B("neon_mort"), false),
    casier_bas: devant(B("casier_bas"), B("casier_cote"), B("casier_dessus")),
    casier_haut: devant(B("casier_haut"), B("casier_cote"), B("casier_dessus")),
    porte_bas: devant(B("porte_bas"), B("papier_bas"), B("papier"), B("papier"), B("porte_bas")),
    porte_haut: devant(B("porte_haut"), B("papier"), B("papier"), B("papier"), B("porte_haut")),
    // le panneau EXIT : du papier peint, et le panneau rouge posé dessus qui luit
    exit: {
      parent: "minecraft:block/block",
      textures: { particle: B("papier"), papier: B("papier"), sortie: B("sortie") },
      elements: [
        { from: [0, 0, 0], to: [16, 16, 16], faces: Object.fromEntries(["north", "south", "east", "west", "up", "down"].map((f) => [f, face("#papier", { cullface: f })])) },
        { from: [0.5, 3, -0.06], to: [15.5, 13, 0], faces: { north: face("#sortie", { uv: [0, 3, 15, 13] }) }, ...LUEUR },
      ],
    },
  };
  for (const [nom, m] of Object.entries(modeles)) ecrire(`assets/cabane/models/block/backrooms_${nom}.json`, m);

  const etats = {
    mur: { variants: { "": [{ model: B("mur"), weight: 40 }, { model: B("mur_tache") }, { model: B("mur_tache2") }] } },
    plinthe: { variants: { "": { model: B("plinthe") } } },
    moquette: { variants: { "": [{ model: B("moquette"), weight: 10 }, { model: B("moquette_tache") }] } },
    plafond: { variants: { "": { model: B("plafond") } } },
    neon: { variants: { "": { model: B("neon") } } },
    neon_clignote: { variants: { "": [{ model: B("neon_clignote_a") }, { model: B("neon_clignote_b") }] } },
    neon_mort: { variants: { "": { model: B("neon_mort") } } },
    exit: { variants: Object.fromEntries(Object.entries(tourne({ model: B("exit") })).map(([d, v]) => [`facing=${d}`, v])) },
  };
  for (const nom of ["casier", "porte"]) {
    const v = {};
    for (const [d, base] of Object.entries(tourne({}))) {
      v[`facing=${d},half=lower`] = { model: B(`${nom}_bas`), ...base };
      v[`facing=${d},half=upper`] = { model: B(`${nom}_haut`), ...base };
    }
    etats[nom] = { variants: v };
  }
  for (const [nom, e] of Object.entries(etats)) ecrire(`assets/cabane/blockstates/backrooms_${nom}.json`, e);
}

// ---------------------------------------------------------------- la dimension
const TYPE_DIMENSION = {
  ultrawarm: false, natural: false, piglin_safe: false, respawn_anchor_works: false, bed_works: false, has_raids: false,
  has_skylight: false, has_ceiling: true, coordinate_scale: 1.0, ambient_light: 0.0, fixed_time: 18000,
  // la moquette en y = 0, trois blocs d'air, le plafond en y = 4, une dalle pleine au-dessus
  logical_height: 16, min_y: 0, height: 16,
  // le brouillard épais du Nether, teinté par le biome : les couloirs se perdent dans le jaune sale
  effects: "minecraft:the_nether",
  infiniburn: "#minecraft:infiniburn_overworld",
  monster_spawn_light_level: 0, monster_spawn_block_light_limit: 0,
};
const DIMENSION = {
  type: "cabane:backrooms",
  generator: { type: "cabane:backrooms", biome_source: { type: "minecraft:fixed", biome: "cabane:backrooms" } },
};
const BIOME = {
  has_precipitation: false, temperature: 0.8, downfall: 0.0,
  effects: {
    sky_color: 0, fog_color: 0x2e2817, water_color: 0x8a7a3c, water_fog_color: 0x3a3218, grass_color: 0x8a7a3c, foliage_color: 0x8a7a3c,
    // de la poussière qui flotte sous les néons
    particle: { options: { type: "minecraft:white_ash" }, probability: 0.0015 },
  },
  spawners: {}, spawn_costs: {}, carvers: {}, features: [],
};

// ---------------------------------------------------------------- les noms
export const NOMS = {
  "block.cabane.backrooms_mur": "Papier peint jaune",
  "block.cabane.backrooms_plinthe": "Papier peint humide",
  "block.cabane.backrooms_moquette": "Moquette humide",
  "block.cabane.backrooms_plafond": "Dalle de plafond",
  "block.cabane.backrooms_neon": "Néon",
  "block.cabane.backrooms_neon_clignote": "Néon qui clignote",
  "block.cabane.backrooms_neon_mort": "Néon mort",
  "block.cabane.backrooms_casier": "Casier",
  "block.cabane.backrooms_porte": "Porte EXIT",
  "block.cabane.backrooms_exit": "Panneau EXIT",
  "entity.cabane.backrooms_rampeur": "Le Rampeur",
  "entity.cabane.backrooms_souriant": "Le Souriant",
  "entity.cabane.backrooms_fetard": "Le Fêtard",
  "entity.cabane.backrooms_sansvisage": "Le Sans-Visage",
  "entity.cabane.backrooms_phalene": "La Phalène",
  "entity.cabane.backrooms_cachette": "Cachette",
  "biome.cabane.backrooms": "Les Backrooms",
  "subtitles.cabane.backrooms.bourdon": "Les néons bourdonnent",
  "subtitles.cabane.backrooms.pas": "Des pas sur la moquette",
  "subtitles.cabane.backrooms.pas_rampeur": "Des griffes qui cliquettent",
  "subtitles.cabane.backrooms.repere": "Il t'a vu",
  "subtitles.cabane.backrooms.casier": "Un casier claque",
  "subtitles.cabane.backrooms.renifle": "Quelque chose renifle",
  "subtitles.cabane.backrooms.cri": "UN CRI",
};

export function genererBackrooms({ ecrire, lang }) {
  blocs(ecrire);
  ecrire("assets/cabane/textures/entity/backrooms/monstres.png", atlasMonstres());
  ecrire("assets/cabane/textures/gui/backrooms/casier.png", vueCasier());
  ecrire("data/cabane/dimension_type/backrooms.json", TYPE_DIMENSION);
  ecrire("data/cabane/dimension/backrooms.json", DIMENSION);
  ecrire("data/cabane/worldgen/biome/backrooms.json", BIOME);
  Object.assign(lang, NOMS);
}

// ---------------------------------------------------------------- les sons : les recettes de son.js, calculées hors ligne
const SR = 44100;
/** Quand chaque monstre se jette sur toi après le début du screamer (secondes) ; les mêmes que l'aperçu et que le mod. */
export const DEBUT_SCREAMER = { rampeur: 0.05, souriant: 0.42, fetard: 0.62, sansvisage: 0.45, phalene: 0.05 };

function alea(graine) {
  return () => ((graine = (graine * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff) * 2 - 1;
}
/** Un filtre biquad (formules RBJ, comme ceux du navigateur), dont on peut changer la fréquence en route. */
function biquad(type, f, Q = 0.7071) {
  let b0, b1, b2, a1, a2, x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  const regler = (fc, q = Q) => {
    const w = (2 * Math.PI * Math.min(fc, SR * 0.45)) / SR, cs = Math.cos(w), sn = Math.sin(w), al = sn / (2 * q);
    if (type === "passe-bas") { b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = (1 - cs) / 2; }
    else if (type === "passe-haut") { b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = (1 + cs) / 2; }
    else { b0 = al; b1 = 0; b2 = -al; }
    const a0 = 1 + al;
    b0 /= a0; b1 /= a0; b2 /= a0; a1 = (-2 * cs) / a0; a2 = (1 - al) / a0;
  };
  regler(f);
  const p = (x) => { const y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; return y; };
  p.regler = regler;
  return p;
}
const exp = (a, b, k) => a * Math.pow(b / a, Math.min(1, Math.max(0, k)));
const lin = (a, b, k) => a + (b - a) * Math.min(1, Math.max(0, k));
const scie = (ph) => 2 * (ph / (2 * Math.PI) - Math.floor(ph / (2 * Math.PI) + 0.5));
const carre = (ph) => (Math.sin(ph) >= 0 ? 1 : -1);
const tampon = (s) => new Float32Array(Math.round(s * SR));

/** Le bourdonnement des néons, en boucle (8 s, bouclé sans couture) : 120 Hz râpeux, son octave, un grésillement aigu. */
function bourdon() {
  const D = 8, X = 0.5, n = Math.round((D + X) * SR), out = new Float32Array(n), b = alea(7);
  const lp = biquad("passe-bas", 460), bp = biquad("bande", 3900, 5);
  for (let i = 0; i < n; i++) {
    const t = i / SR;
    const g1 = 0.07 + 0.025 * Math.sin(2 * Math.PI * 0.25 * t);
    out[i] = lp(scie(2 * Math.PI * 120 * t)) * g1 + Math.sin(2 * Math.PI * 240 * t) * 0.014 + bp(b()) * 0.02 * 3;
  }
  // le fondu de la fin sur le début : la boucle ne claque pas
  const res = new Float32Array(Math.round(D * SR)), nx = Math.round(X * SR);
  for (let i = 0; i < res.length; i++) res[i] = out[i + nx];
  for (let i = 0; i < nx; i++) {
    const k = i / nx;
    res[res.length - nx + i] = res[res.length - nx + i] * (1 - k) + out[i] * k;
  }
  return { donnees: res, volume: 0.5 };
}

/** Un pas : sourd sur la moquette, ou cliquetant (le Rampeur). */
function pas(aigu, graine) {
  const out = tampon(aigu ? 0.12 : 0.22), b = alea(graine);
  const f = aigu ? biquad("bande", 1500 + (graine % 5) * 110, 3) : biquad("passe-bas", 260 + (graine % 4) * 25, 0.7);
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    out[i] = f(b()) * exp(1, 0.0005, t / (aigu ? 0.06 : 0.14)) * (aigu ? 1 : 3);
  }
  return { donnees: out, volume: 0.9 };
}

/** Un « boum » sourd (la fréquence et le volume qui tombent). */
function boum(out, t0, vol, f0, f1, duree) {
  let ph = 0;
  for (let i = Math.round(t0 * SR); i < out.length; i++) {
    const t = i / SR - t0;
    if (t > duree * 1.3) break;
    ph += (2 * Math.PI * exp(f0, f1, t / duree)) / SR;
    out[i] += Math.sin(ph) * exp(vol, 0.0005, t / (duree * 1.2));
  }
}

/** « Il t'a vu » : un accord de cordes qui grince et descend, et un coup sourd. */
function repere() {
  const out = tampon(1.7), hp = biquad("passe-haut", 600);
  const phases = [0, 0, 0, 0, 0];
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    const g = t < 0.04 ? exp(0.0001, 0.14, t / 0.04) : exp(0.14, 0.0005, (t - 0.04) / 1.46);
    let v = 0;
    [740, 784, 1046, 1108, 1480].forEach((f, j) => {
      phases[j] += (2 * Math.PI * lin(f, f * 0.93, t / 1.5)) / SR;
      v += scie(phases[j]);
    });
    out[i] = hp(v * g);
  }
  boum(out, 0, 0.55, 80, 38, 0.5);
  return { donnees: out, volume: 1 };
}

function coeur() {
  const out = tampon(0.45);
  boum(out, 0, 0.5, 62, 40, 0.13);
  boum(out, 0.2, 0.35, 58, 38, 0.12);
  return { donnees: out, volume: 1 };
}

/** La porte du casier qui claque (tôle). */
function casier() {
  const out = tampon(0.35), b = alea(91), bp = biquad("bande", 2300, 9);
  let ph = 0;
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    ph += (2 * Math.PI * exp(190, 120, t / 0.18)) / SR;
    out[i] = bp(b()) * exp(0.5, 0.0005, t / 0.25) * 4 + carre(ph) * exp(0.08, 0.0005, t / 0.2);
  }
  return { donnees: out, volume: 1 };
}

/** Trois coups de nez, devant le casier. */
function renifle() {
  const out = tampon(0.5), b = alea(33), bp = biquad("bande", 900, 1.5);
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    let g = 0;
    for (let k = 0; k < 3; k++) {
      const u = t - k * 0.16;
      if (u >= 0 && u < 0.15) g += u < 0.05 ? exp(0.0001, 0.18, u / 0.05) : exp(0.18, 0.0005, (u - 0.05) / 0.08);
    }
    out[i] = bp(b()) * g * 5;
  }
  return { donnees: out, volume: 1 };
}

/** L'arrivée : la neige qui grésille, l'écran qui saute. */
function gresille() {
  const out = tampon(0.9), b = alea(55), hp = biquad("passe-haut", 1800), lp = biquad("passe-bas", 300);
  let coupe = 1;
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    if (i % 1100 === 0) coupe = Math.abs(b()) > 0.3 ? 1 : 0.15;
    const env = Math.min(1, t / 0.02) * Math.min(1, (0.9 - t) / 0.3);
    out[i] = (hp(b()) * 0.6 * coupe + lp(b()) * 1.2) * env;
  }
  return { donnees: out, volume: 0.8 };
}

/** Le gros bruit du screamer : la langue de belle-mère du Fêtard avant, puis le cri saturé, le souffle, le coup de basse
 * et le sifflement dans les oreilles. Le début est calé sur DEBUT_SCREAMER : le son part au même instant que l'image. */
function cri(type) {
  const t0 = DEBUT_SCREAMER[type];
  const out = tampon(t0 + 4.3), sec = new Float32Array(out.length), b = alea(type.length * 97 + 13);
  const bases = {
    rampeur: [620, 655, 930, 1310], souriant: [150, 192, 288, 384], fetard: [440, 466, 690, 940],
    sansvisage: [196, 247, 330, 415], phalene: [900, 1250, 1700, 2350],
  }[type];
  const vibr = bases.map((_, j) => 16 + ((j * 7 + type.length) % 10) * 1.4);
  const ph = bases.map(() => 0), phv = bases.map(() => 0);
  const bp = biquad("bande", 1200, 0.8);
  let phG = 0, phK = 0, phKv = 0;
  const bpK = biquad("bande", 1400, 1.2);
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    // la langue de belle-mère, avant qu'il se jette
    if (type === "fetard" && t < 0.55) {
      phKv += (2 * Math.PI * 9) / SR;
      const f = (t < 0.08 ? lin(560, 600, t / 0.08) : lin(600, 520, (t - 0.08) / 0.37)) + Math.sin(phKv) * 14;
      phK += (2 * Math.PI * f) / SR;
      const g = t < 0.03 ? exp(0.0001, 0.4, t / 0.03) : t < 0.4 ? 0.4 : exp(0.4, 0.0005, (t - 0.4) / 0.1);
      sec[i] += bpK(scie(phK)) * g * 2.2;
    }
    const u = t - t0;
    if (u < 0) continue;
    // l'enveloppe du cri
    const env = u < 0.015 ? exp(0.0001, 0.85, u / 0.015) : u < 1.25 ? 0.85 : exp(0.85, 0.0001, (u - 1.25) / 0.45);
    let v = 0;
    if (u < 1.8) {
      bases.forEach((f, j) => {
        const fr = u < 0.2 ? exp(f * 0.6, f * 1.15, u / 0.2) : u < 1.0 ? lin(f * 1.15, f * 0.95, (u - 0.2) / 0.8) : exp(f * 0.95, f * 0.6, (u - 1.0) / 0.7);
        phv[j] += (2 * Math.PI * vibr[j]) / SR;
        ph[j] += (2 * Math.PI * (fr + Math.sin(phv[j]) * f * 0.06)) / SR;
        v += scie(ph[j]) * 0.2;
      });
      bp.regler(u < 0.25 ? exp(1200, 3400, u / 0.25) : exp(3400, 900, (u - 0.25) / 1.35), 0.8);
      let gs = 0.9;
      // la Phalène : le froissement des ailes, le souffle haché très vite
      if (type === "phalene") gs = Math.max(0, 0.9 + 0.6 * carre(2 * Math.PI * 38 * u));
      v += bp(b()) * gs * 2.5;
      if (type === "souriant" || type === "sansvisage") {
        phG += (2 * Math.PI * lin(58, 44, u / 1.6)) / SR;
        v += scie(phG) * 0.5;
      }
      // la saturation
      const x = v * env;
      out[i] += (41 * x) / (1 + 40 * Math.abs(x));
    }
    // le sifflement d'après, dans les oreilles
    if (u > 1.6) {
      const g = u < 1.7 ? exp(0.0001, 0.035, (u - 1.6) / 0.1) : exp(0.035, 0.0005, (u - 1.7) / 2.5);
      sec[i] += Math.sin(2 * Math.PI * 4100 * u) * g * 2.5;
    }
  }
  // le coup de basse, hors saturation
  boum(sec, t0, 1, 130, 30, 0.75);
  for (let i = 0; i < out.length; i++) out[i] = out[i] * 0.8 + sec[i] * 0.55;
  return { donnees: out, volume: 1 };
}

const SONS = {
  bourdon, repere, coeur, casier, renifle, gresille,
  pas1: () => pas(false, 11), pas2: () => pas(false, 23), pas3: () => pas(false, 37),
  griffe1: () => pas(true, 41), griffe2: () => pas(true, 53), griffe3: () => pas(true, 67),
  ...Object.fromEntries(Object.keys(DEBUT_SCREAMER).map((t) => [`cri_${t}`, () => cri(t)])),
};

function normaliser(out, volume = 1) {
  let max = 0;
  for (const v of out) max = Math.max(max, Math.abs(v));
  const k = (0.95 * volume) / (max || 1);
  for (let i = 0; i < out.length; i++) out[i] *= k;
  return out;
}
function wav(e) {
  const b = Buffer.alloc(44 + e.length * 2);
  b.write("RIFF", 0); b.writeUInt32LE(36 + e.length * 2, 4); b.write("WAVE", 8); b.write("fmt ", 12);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(SR, 24); b.writeUInt32LE(SR * 2, 28);
  b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write("data", 36); b.writeUInt32LE(e.length * 2, 40);
  e.forEach((v, i) => b.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(v * 32767))), 44 + i * 2));
  return b;
}
function ffmpeg() {
  const dossier = path.join(RACINE, ".outils", "ffmpeg");
  if (!fs.existsSync(dossier)) return null;
  for (const d of fs.readdirSync(dossier)) {
    const f = path.join(dossier, d, "bin", "ffmpeg.exe");
    if (fs.existsSync(f)) return f;
  }
  return null;
}

/** Les sons des backrooms pour sounds.json (les .ogg sont synthétisés une fois ; on efface le fichier pour le refaire). */
export function sonsBackrooms(RES) {
  const dossier = path.join(RES, "assets", "cabane", "sounds", "backrooms");
  fs.mkdirSync(dossier, { recursive: true });
  const ff = ffmpeg();
  for (const [nom, fabrique] of Object.entries(SONS)) {
    const cible = path.join(dossier, nom + ".ogg");
    if (fs.existsSync(cible) || !ff) continue;
    const { donnees, volume } = fabrique();
    const tmp = path.join(os.tmpdir(), `cabane-backrooms-${nom}.wav`);
    fs.writeFileSync(tmp, wav(normaliser(donnees, volume)));
    execFileSync(ff, ["-y", "-loglevel", "error", "-i", tmp, "-c:a", "libvorbis", "-q:a", "5", cible]);
    fs.rmSync(tmp, { force: true });
  }
  const s = (nom, sous, extra = {}) => ({ ...(sous ? { subtitle: `subtitles.cabane.backrooms.${sous}` } : {}), sounds: [{ name: `cabane:backrooms/${nom}`, ...extra }] });
  const sons = {
    "backrooms.bourdon": s("bourdon", null, { stream: true }),
    "backrooms.repere": s("repere", "repere"),
    "backrooms.coeur": s("coeur", null),
    "backrooms.casier": s("casier", "casier"),
    "backrooms.renifle": s("renifle", "renifle"),
    "backrooms.gresille": s("gresille", null),
    "backrooms.pas": { subtitle: "subtitles.cabane.backrooms.pas", sounds: [1, 2, 3].map((n) => ({ name: `cabane:backrooms/pas${n}` })) },
    "backrooms.griffe": { subtitle: "subtitles.cabane.backrooms.pas_rampeur", sounds: [1, 2, 3].map((n) => ({ name: `cabane:backrooms/griffe${n}` })) },
  };
  for (const t of Object.keys(DEBUT_SCREAMER)) sons[`backrooms.cri.${t}`] = s(`cri_${t}`, "cri");
  return sons;
}

// lancé seul : on écrit tout, on range les sons dans sounds.json et les noms dans les langues
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const RES = path.join(ICI, "..", "src", "main", "resources");
  const ecrire = (rel, contenu) => {
    const f = path.join(RES, rel);
    fs.mkdirSync(path.dirname(f), { recursive: true });
    fs.writeFileSync(f, Buffer.isBuffer(contenu) ? contenu : typeof contenu === "string" ? contenu : JSON.stringify(contenu, null, 2) + "\n");
  };
  const lang = {};
  genererBackrooms({ ecrire, lang });
  for (const l of ["fr_fr", "en_us"]) {
    const f = path.join(RES, "assets", "cabane", "lang", `${l}.json`);
    const avant = JSON.parse(fs.readFileSync(f, "utf8"));
    fs.writeFileSync(f, JSON.stringify({ ...avant, ...lang }, null, 2) + "\n");
  }
  const fichier = path.join(RES, "assets", "cabane", "sounds.json");
  const avant = JSON.parse(fs.readFileSync(fichier, "utf8"));
  const apres = Object.fromEntries(Object.entries(avant).filter(([k]) => !k.startsWith("backrooms.")));
  Object.assign(apres, sonsBackrooms(RES));
  fs.writeFileSync(fichier, JSON.stringify(apres, null, 2) + "\n");
  console.log(`Backrooms : blocs, monstres, dimension et ${Object.keys(SONS).length} sons écrits`);
}
