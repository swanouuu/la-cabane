// Le coffre de l'élu du vent (/animation eluduvent) : un coffre au trésor 64 x 64, rangé comme celui de Minecraft
// (le modèle du coffre : le fond 14 x 10 x 14 en (0, 19), le couvercle 14 x 5 x 14 en (0, 0), la serrure 2 x 4 x 1 en (0, 0)).
// Bois sombre, renforts vert émeraude, coins dorés, une gemme du vent sur le devant ; dedans, le fond brille.
// Tous les motifs sont symétriques : peu importe dans quel sens Minecraft retourne chaque face.
// Lancer depuis n'importe où :  node mod-cabane/outils/coffre-vent.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { crc32 } from "../../outils/commun.mjs";
import zlib from "node:zlib";

const SORTIE = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources",
  "assets", "cabane", "textures", "entity", "coffre_vent.png");
const N = 64;
const px = new Uint8Array(N * N * 4);

const BOIS = [[112, 72, 40], [126, 82, 46], [100, 64, 35]];
const JOINT = [70, 43, 23];
const BOIS_SOMBRE = [[62, 40, 24], [70, 45, 27], [56, 36, 21]];
const VERT = [44, 158, 104], VERT_CLAIR = [104, 222, 160], VERT_SOMBRE = [22, 92, 62];
const OR = [232, 182, 62], OR_CLAIR = [255, 228, 128], OR_SOMBRE = [158, 108, 30];
const GEMME = [120, 255, 170], GEMME_BORD = [50, 196, 118], GEMME_ECLAT = [235, 255, 240];

function mettre(x, y, c) {
  const i = (y * N + x) * 4;
  px[i] = c[0]; px[i + 1] = c[1]; px[i + 2] = c[2]; px[i + 3] = c[3] ?? 255;
}

// le grain du bois : toujours le même pour le même pixel
const grain = (x, y) => ((x * 73 + y * 151 + ((x * y) % 7) * 31) % 11) / 11;

/** Une face : (u, v) son coin, l x h sa taille ; f(x, y) donne la couleur de chaque pixel de la face. */
function face(u, v, l, h, f) {
  for (let y = 0; y < h; y++) for (let x = 0; x < l; x++) mettre(u + x, v + y, f(x, y, l, h));
}

/** Des planches en bandes (horizontales ou verticales), avec leurs joints sombres. */
function planches(x, y, l, h, vertical, sombre = false) {
  const p = vertical ? x : y, q = vertical ? y : x;
  if (p % 5 === 4) return sombre ? [44, 28, 16] : JOINT;
  const teintes = sombre ? BOIS_SOMBRE : BOIS;
  const t = teintes[Math.floor(p / 5) % 3];
  const g = grain(q + Math.floor(p / 5) * 5, p);
  return g < 0.15 ? t.map((c) => c - 10) : g > 0.85 ? t.map((c) => c + 8) : t;
}

/** Le renfort vert le long du bord (épais d'un pixel, éclairé vers l'intérieur), les coins dorés (2 x 2). */
function cadre(x, y, l, h, epaisseur = 1) {
  const bordX = x < epaisseur || x >= l - epaisseur, bordY = y < epaisseur || y >= h - epaisseur;
  const coin = (x < 2 || x >= l - 2) && (y < 2 || y >= h - 2);
  if (coin) return (x === 0 || x === l - 1) && (y === 0 || y === h - 1) ? OR_SOMBRE : OR_CLAIR;
  if (bordX || bordY) return (x + y) % 6 === 0 ? VERT_CLAIR : VERT;
  return null;
}

/** Une gemme en losange (rayon r) au centre d'une face, sertie d'or. */
function gemme(x, y, l, h, r) {
  const d = Math.abs(x - (l - 1) / 2) + Math.abs(y - (h - 1) / 2);
  if (d <= r - 1.5) return d <= 0.6 ? GEMME_ECLAT : d <= r - 2.5 ? GEMME : GEMME_BORD;
  if (d <= r) return d <= r - 0.6 ? OR : OR_SOMBRE;
  return null;
}

// ---- le fond (14 x 10 x 14 en 0, 19)
const U = 0, V = 19;
// les quatre côtés : planches, renfort, une bande verte au milieu (et la gemme devant)
const cote = (devant) => (x, y, l, h) => {
  const c = cadre(x, y, l, h);
  if (c) return c;
  if (devant) {
    const g = gemme(x, y, l, h, 4);
    if (g) return g;
  }
  // deux rivets dorés à mi-hauteur près des bords
  if ((x === 2 || x === l - 3) && (y === 4 || y === 5)) return OR;
  return planches(x, y, l, h, false);
};
face(U, V + 14, 14, 10, cote(false));           // ouest
face(U + 14, V + 14, 14, 10, cote(false));      // nord (le dos)
face(U + 28, V + 14, 14, 10, cote(false));      // est
face(U + 42, V + 14, 14, 10, cote(true));       // sud (le devant, côté serrure)
// dessous : bois sombre
face(U + 14, V, 14, 14, (x, y, l, h) => cadre(x, y, l, h) ?? planches(x, y, l, h, true, true));
// dessus du fond = l'intérieur qu'on voit coffre ouvert : il brille, plus fort au milieu
face(U + 28, V, 14, 14, (x, y, l, h) => {
  if (x === 0 || y === 0 || x === l - 1 || y === h - 1) return BOIS_SOMBRE[0];
  const d = Math.hypot(x - (l - 1) / 2, y - (h - 1) / 2) / 7;
  const k = Math.max(0, 1 - d);
  return [Math.round(70 + 170 * k), Math.round(160 + 95 * k), Math.round(110 + 120 * k)];
});

// ---- le couvercle (14 x 5 x 14 en 0, 0)
const coteCouvercle = (devant) => (x, y, l, h) => {
  const coin = (x < 2 || x >= l - 2);
  if (y === 0 || y === h - 1) return coin ? OR_SOMBRE : VERT;
  if (coin) return OR_CLAIR;
  // la plaque dorée où la serrure s'accroche
  if (devant && x >= 5 && x <= 8) return x === 5 || x === 8 ? OR_SOMBRE : OR;
  return planches(x, y + 2, l, h, false);
};
face(0, 14, 14, 5, coteCouvercle(false));
face(14, 14, 14, 5, coteCouvercle(false));
face(28, 14, 14, 5, coteCouvercle(false));
face(42, 14, 14, 5, coteCouvercle(true));
// le dessus : planches, renfort, une croix de bandes dorées et l'emblème au centre
face(28, 0, 14, 14, (x, y, l, h) => {
  const c = cadre(x, y, l, h);
  if (c) return c;
  const g = gemme(x, y, l, h, 4);
  if (g) return g;
  if (x === 6 || x === 7 || y === 6 || y === 7) return (x + y) % 4 === 0 ? OR_CLAIR : OR;
  return planches(x, y, l, h, true);
});
// l'intérieur du couvercle : bois sombre (on le voit quand il s'ouvre), bordé de vert
face(14, 0, 14, 14, (x, y, l, h) => (x === 0 || y === 0 || x === l - 1 || y === h - 1 ? VERT_SOMBRE : planches(x, y, l, h, true, true)));

// ---- la serrure (2 x 4 x 1 en 0, 0) : en or, le trou de la clé devant
face(1, 0, 2, 1, () => OR_SOMBRE);                // dessous
face(3, 0, 2, 1, () => OR_CLAIR);                 // dessus
face(0, 1, 1, 4, () => OR_SOMBRE);                // ouest
face(1, 1, 2, 4, () => OR);                       // nord
face(3, 1, 1, 4, () => OR_SOMBRE);                // est
face(4, 1, 2, 4, (x, y) => (y === 1 || y === 2 ? [40, 26, 12] : OR_CLAIR)); // sud : la serrure

// ---- écrire le PNG
const brut = Buffer.alloc(N * (1 + N * 4));
for (let y = 0; y < N; y++) Buffer.from(px.subarray(y * N * 4, (y + 1) * N * 4)).copy(brut, y * (1 + N * 4) + 1);
const bloc = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, c]);
};
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(N, 0); ihdr.writeUInt32BE(N, 4); ihdr[8] = 8; ihdr[9] = 6;
fs.mkdirSync(path.dirname(SORTIE), { recursive: true });
fs.writeFileSync(SORTIE, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr),
  bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]));
console.log("Coffre du vent :", SORTIE);
