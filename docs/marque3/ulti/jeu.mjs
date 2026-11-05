// Exporte le grand serpent de l'ulti de la marque 3 pour le mod : node jeu.mjs
// → mod-cabane/src/main/resources/assets/cabane/marque/serpent-geant.bin (les faces visibles, couleurs déjà éclairées)
// → mod-cabane/src/main/resources/assets/cabane/textures/marque/bouffee.png (une bouffée de fumée ronde et douce)
// Le sceau (textures/marque/sceau.png) vient de la page : apercu.sceau() dans ulti.html.
//
// Le fichier (gros-boutiste, comme DataInputStream) :
//   float CHARN, LH, LB
//   5 morceaux : crâne, yeux, mâchoire, langue (repère de la tête ; crâne, yeux et mâchoire depuis la charnière, la langue
//   depuis sa base), puis le corps (repère du tube : s le long du corps depuis le cou, a vers le dos, b sur le côté).
//   Chaque morceau : float ox, oy, oz, pas ; int n ; n faces : short i, j, k (le coin du cube = o + (i, j, k) × pas),
//   byte sens (0 +x, 1 -x, 2 +y, 3 -y, 4 +z, 5 -z), byte r, g, b.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { LH, CHARN, LB, rayon, construireSerpent, couleurCorps, clamp } from './serpent.mjs';

const ICI = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = path.join(ICI, '../../../mod-cabane/src/main/resources/assets/cabane');
const PAS_TETE = 0.25, PAS_CORPS = 0.5;

// la lumière cuite, selon le sens de la face (dessus clair, dessous sombre), appliquée en lumière linéaire
const LUM_TETE = [0.8, 0.66, 1.0, 0.42, 0.74, 0.62];
const eclaire = (c, l) => c.map((v) => Math.round(clamp(v * Math.pow(l, 1 / 2.2), 0, 1) * 255));

function morceau(v, decal, pas, filtre, lumieres) {
  const faces = [];
  for (let n = 0; n < v.reg.length; n++) {
    if (!filtre(n)) continue;
    const c = [v.col[n * 3], v.col[n * 3 + 1], v.col[n * 3 + 2]];
    for (let d = 0; d < 6; d++) if (v.faces[n] & (1 << d)) faces.push([v.idx[n * 3], v.idx[n * 3 + 1], v.idx[n * 3 + 2], d, ...eclaire(c, lumieres ? lumieres[d] : 1)]);
  }
  return { o: [v.origine[0] + decal[0], v.origine[1] + decal[1], v.origine[2] + decal[2]], pas, faces };
}

/** Le corps dans son repère droit (s, a, b), en cubes de pas p : seulement les faces libres. */
function corps(p) {
  const S0 = -1.0, A0 = -5.8, B0 = -5.8;
  const ns = Math.ceil((LB - S0) / p), na = Math.ceil(11.6 / p), nb = na;
  const dedans = (i, j, k) => {
    if (i < 0 || j < 0 || k < 0 || i >= ns || j >= na || k >= nb) return false;
    const s = S0 + (i + 0.5) * p, a = A0 + (j + 0.5) * p, b = B0 + (k + 0.5) * p, R = rayon(Math.max(0, s));
    const h = a >= 0 ? 0.86 * R : 0.64 * R;
    if ((a / h) ** 2 + (b / R) ** 2 <= 1) return true;
    // une petite crête sur le dos, tous les trois anneaux
    const ia = Math.round(s / p);
    return s > 1.5 && s < LB - 10 && ia % 3 === 0 && Math.abs(b) < p * 0.6 && a > 0.86 * R && a < 0.86 * R + p * 1.2;
  };
  const LUM = [0.78, 0.78, 1.0, 0.42, 0.72, 0.62];
  const faces = [];
  for (let i = 0; i < ns; i++) for (let j = 0; j < na; j++) for (let k = 0; k < nb; k++) {
    if (!dedans(i, j, k)) continue;
    const s = S0 + (i + 0.5) * p, a = A0 + (j + 0.5) * p, b = B0 + (k + 0.5) * p, R = rayon(Math.max(0, s));
    const th = Math.atan2(b / R, a / (a >= 0 ? 0.86 * R : 0.64 * R));
    const ia = Math.round(s / p), ii = Math.round(((th + Math.PI) * R) / p);
    const crete = a > 0.86 * R;
    const c = crete ? [36 / 255 * 1.1, 16 / 255 * 1.1, 56 / 255 * 1.1] : couleurCorps(Math.max(0, s), th, ia, ii);
    const voisins = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
    voisins.forEach(([di, dj, dk], d) => { if (!dedans(i + di, j + dj, k + dk)) faces.push([i, j, k, d, ...eclaire(c, LUM[d])]); });
  }
  return { o: [S0, A0, B0], pas: p, faces };
}

const t0 = Date.now();
const SP = construireSerpent(PAS_TETE);
const versCharn = [-CHARN, 0, 0];
const oeil = (n) => SP.crane.reg[n] === 2 && SP.crane.col[n * 3] >= 0.2;
const parties = [
  morceau(SP.crane, versCharn, PAS_TETE, (n) => !oeil(n), LUM_TETE),
  morceau(SP.crane, versCharn, PAS_TETE, oeil, null),
  morceau(SP.machoire, versCharn, PAS_TETE, () => true, LUM_TETE),
  morceau(SP.langue, [0, 0, 0], 0.25, () => true, LUM_TETE),
  corps(PAS_CORPS),
];
let taille = 12;
for (const m of parties) taille += 20 + m.faces.length * 10;
const buf = Buffer.alloc(taille);
let o = 0;
const f32 = (v) => { buf.writeFloatBE(v, o); o += 4; };
f32(CHARN); f32(LH); f32(LB);
for (const m of parties) {
  f32(m.o[0]); f32(m.o[1]); f32(m.o[2]); f32(m.pas);
  buf.writeInt32BE(m.faces.length, o); o += 4;
  for (const [i, j, k, d, r, g, b] of m.faces) {
    buf.writeInt16BE(i, o); buf.writeInt16BE(j, o + 2); buf.writeInt16BE(k, o + 4);
    buf.writeUInt8(d, o + 6); buf.writeUInt8(r, o + 7); buf.writeUInt8(g, o + 8); buf.writeUInt8(b, o + 9);
    o += 10;
  }
}
fs.mkdirSync(path.join(ASSETS, 'marque'), { recursive: true });
fs.writeFileSync(path.join(ASSETS, 'marque/serpent-geant.bin'), buf.subarray(0, o));
console.log('faces : crâne', parties[0].faces.length, 'yeux', parties[1].faces.length, 'mâchoire', parties[2].faces.length, 'langue', parties[3].faces.length, 'corps', parties[4].faces.length, '·', (o / 1024).toFixed(0), 'Ko,', Date.now() - t0, 'ms');

// la bouffée de fumée : un disque blanc, opaque au milieu et doux au bord, un peu bosselé
function png(l, h, rgba) {
  const crc = (b) => { let c = ~0; for (const x of b) { c ^= x; for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1; } return ~c >>> 0; };
  const bloc = (type, data) => { const t = Buffer.from(type), lg = Buffer.alloc(4), cr = Buffer.alloc(4); lg.writeUInt32BE(data.length); cr.writeUInt32BE(crc(Buffer.concat([t, data]))); return Buffer.concat([lg, t, data, cr]); };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(l, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  const brut = Buffer.alloc((l * 4 + 1) * h);
  for (let y = 0; y < h; y++) rgba.copy(brut, y * (l * 4 + 1) + 1, y * l * 4, (y + 1) * l * 4);
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc('IHDR', ihdr), bloc('IDAT', zlib.deflateSync(brut)), bloc('IEND', Buffer.alloc(0))]);
}
{
  const N = 64, px = Buffer.alloc(N * N * 4);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const dx = (x + 0.5) / N - 0.5, dy = (y + 0.5) / N - 0.5, r = Math.hypot(dx, dy) * 2;
    const ang = Math.atan2(dy, dx), bord = 0.86 + 0.07 * Math.sin(ang * 5) + 0.04 * Math.sin(ang * 11 + 1);
    const a = clamp(1 - (r - bord * 0.45) / (bord * 0.55), 0, 1) ** 1.4;
    const ombre = 1 - 0.22 * clamp(r, 0, 1) + 0.12 * -dy;
    const i = (y * N + x) * 4;
    px[i] = px[i + 1] = px[i + 2] = Math.round(clamp(ombre, 0, 1) * 255);
    px[i + 3] = Math.round(a * 255);
  }
  fs.writeFileSync(path.join(ASSETS, 'textures/marque/bouffee.png'), png(N, N, px));
}
