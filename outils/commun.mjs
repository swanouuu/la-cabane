// Petits outils partagés par les scripts : configuration, chemins, zip et png sans dépendance.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export const RACINE = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
export const PACK = path.join(RACINE, "pack");
export const PACKWIZ = path.join(RACINE, ".outils", "gopath", "bin", os.platform() === "win32" ? "packwiz.exe" : "packwiz");

export function config() {
  const c = JSON.parse(fs.readFileSync(path.join(RACINE, "outils", "config.json"), "utf8"));
  c.instancePrism = c.instancePrism.replace(/%([A-Z_]+)%/gi, (_, v) => process.env[v] ?? "");
  return c;
}

export function lancer(cmd, args, cwd = RACINE) {
  const r = spawnSync(cmd, args, { cwd, encoding: "utf8" });
  return { ok: r.status === 0, sortie: `${r.stdout ?? ""}${r.stderr ?? ""}`.trim() };
}

export const packwiz = (...args) => lancer(PACKWIZ, args, PACK);

const CRC = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
export const crc32 = (buf) => { let c = 0xffffffff; for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };

/** Écrit un .zip : fichiers = [[chemin dans le zip, Buffer]]. */
export function zip(destination, fichiers) {
  const locaux = [], central = [];
  let offset = 0;
  for (const [nom, contenu] of fichiers) {
    const n = Buffer.from(nom.replace(/\\/g, "/"));
    const data = zlib.deflateRawSync(contenu);
    const crc = crc32(contenu);
    const h = Buffer.alloc(30);
    h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt16LE(0x0800, 6); h.writeUInt16LE(8, 8);
    h.writeUInt32LE(crc, 14); h.writeUInt32LE(data.length, 18); h.writeUInt32LE(contenu.length, 22); h.writeUInt16LE(n.length, 26);
    locaux.push(h, n, data);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6); c.writeUInt16LE(0x0800, 8); c.writeUInt16LE(8, 10);
    c.writeUInt32LE(crc, 16); c.writeUInt32LE(data.length, 20); c.writeUInt32LE(contenu.length, 24); c.writeUInt16LE(n.length, 28);
    c.writeUInt32LE(offset, 42);
    central.push(c, n);
    offset += h.length + n.length + data.length;
  }
  const taille = central.reduce((s, b) => s + b.length, 0);
  const fin = Buffer.alloc(22);
  fin.writeUInt32LE(0x06054b50, 0); fin.writeUInt16LE(fichiers.length, 8); fin.writeUInt16LE(fichiers.length, 10);
  fin.writeUInt32LE(taille, 12); fin.writeUInt32LE(offset, 16);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, Buffer.concat([...locaux, ...central, fin]));
}

/** PNG RGBA depuis une grille de caractères, agrandie d'un facteur entier. */
export function png(grille, palette, echelle = 1) {
  const w = grille[0].length * echelle, h = grille.length * echelle;
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const c = palette[grille[Math.floor(y / echelle)][Math.floor(x / echelle)]] ?? [0, 0, 0, 0];
      brut.set(c.length === 3 ? [...c, 255] : c, y * (1 + w * 4) + 1 + x * 4);
    }
  }
  const bloc = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}
