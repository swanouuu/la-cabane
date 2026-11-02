// Les textures de Hachibi (une peau de joueur en 4 fois plus fin : 256 x 256) :
//  - hachibi_bete.png : la bête, rouge sombre et tachetée, avec sa tête de démon (yeux ronds, sourire en dents de scie) ;
//  - hachibi_yeux.png : seulement les yeux et les dents, pour qu'ils brillent dans le noir ;
//  - hachibi_manteau.png : le manteau de chakra, blanc uni (il est teinté en rouge en jeu).
// Les zones de la deuxième couche (chapeau, veste, manches, jambières) restent vides : sinon elles cacheraient le visage.
// Lancer depuis n'importe où :  node mod-cabane/outils/hachibi.mjs
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const DOSSIER = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources",
  "assets", "cabane", "textures", "entity");
const K = 4;
const N = 64 * K;

// les zones de la première couche d'une peau 64 x 64 : tête, corps, jambes, bras
const BASE = [[0, 0, 32, 16], [16, 16, 40, 32], [0, 16, 16, 32], [40, 16, 56, 32], [16, 48, 32, 64], [32, 48, 48, 64]];
const dansBase = (x, y) => BASE.some(([x0, y0, x1, y1]) => x >= x0 * K && x < x1 * K && y >= y0 * K && y < y1 * K);

let graine = 8;
const hasard = () => {
  graine = (graine + 0x6d2b79f5) | 0;
  let t = Math.imul(graine ^ (graine >>> 15), 1 | graine);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];

function encoder(px) {
  const brut = Buffer.alloc(N * (1 + N * 4));
  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) brut.set(px[y * N + x], y * (1 + N * 4) + 1 + x * 4);
  }
  const bloc = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(zlib.crc32(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(N, 0); ihdr.writeUInt32BE(N, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

/** Une texture tachetée : une couleur de la palette par pixel de peau (des carrés de K x K). */
function tachete(palette) {
  const couleurs = [];
  for (let i = 0; i < 64 * 64; i++) couleurs.push(palette[Math.floor(hasard() * palette.length)]);
  const px = new Array(N * N);
  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) {
      px[y * N + x] = dansBase(x, y) ? [...couleurs[Math.floor(y / K) * 64 + Math.floor(x / K)], 255] : [0, 0, 0, 0];
    }
  }
  return px;
}

// le visage, sur la face avant de la tête (x de 8 à 16, y de 8 à 16 sur une peau 64 x 64)
const FACE_X = 8 * K, FACE_Y = 8 * K;
const oeil = (u, v) => [[9, 12], [23, 12]].some(([cx, cy]) => (u - cx) ** 2 + (v - cy) ** 2 <= 4.3 ** 2);
const dent = (u, v) => {
  if (u < 4 || u > 27 || v < 20 || v > 27) return false;
  // les dents du haut pointent vers le bas, celles du bas vers le haut, en quinconce
  const haut = 4 - Math.abs(((u - 4) % 4) - 2) * 2;
  const bas = 4 - Math.abs(((u - 2 + 400) % 4) - 2) * 2;
  return v - 20 < haut || 27 - v < bas;
};
const bouche = (u, v) => u >= 4 && u <= 27 && v >= 20 && v <= 27;

function visage(px, avecFond) {
  for (let v = 0; v < 8 * K; v++) {
    for (let u = 0; u < 8 * K; u++) {
      const i = (FACE_Y + v) * N + FACE_X + u;
      if (oeil(u, v) || dent(u, v)) px[i] = [255, 255, 250, 255];
      else if (avecFond && bouche(u, v)) px[i] = [26, 2, 2, 255];
    }
  }
}

fs.mkdirSync(DOSSIER, { recursive: true });

/** Une case pleine, d'une seule couleur (x de 56 à 64, y de 16 à 24 sur une peau 64 x 64) : pour le chakra uni. */
function casePleine(px, couleur) {
  for (let y = 16 * K; y < 24 * K; y++) {
    for (let x = 56 * K; x < 64 * K; x++) px[y * N + x] = [...couleur, 255];
  }
}

const bete = tachete(["#4a0707", "#5c0b0b", "#6b0f0f", "#7a1414", "#8a1a1a", "#561010", "#3e0505"].map(hex));
visage(bete, true);
casePleine(bete, hex("#6b0f0f"));
fs.writeFileSync(path.join(DOSSIER, "hachibi_bete.png"), encoder(bete));

const yeux = new Array(N * N).fill([0, 0, 0, 0]);
visage(yeux, false);
fs.writeFileSync(path.join(DOSSIER, "hachibi_yeux.png"), encoder(yeux));

// le manteau est uni (blanc partout, teinté en rouge en jeu) : pas de taches, ni sur l'aura, ni sur les queues
const manteau = tachete([hex("#ffffff")]);
casePleine(manteau, hex("#ffffff"));
fs.writeFileSync(path.join(DOSSIER, "hachibi_manteau.png"), encoder(manteau));
console.log("Textures de Hachibi écrites dans", DOSSIER);
