// Les skins fournis tels quels (dossier outils/skins/) : Vegeta (le PNJ du village et la cinématique), Goku
// Super Saiyan 2, Eren (le PNJ caché derrière la forge), Erwin (la cinématique des titans) et les mannequins du stand de tir. On en tire Majin Vegeta en ajoutant le « M » de Babidi sur le front.
// Appelé à la fin de persos.mjs (ou seul :  node mod-cabane/outils/skins-perso.mjs).
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const SOURCES = path.join(ICI, "skins");
const DOSSIER = path.join(ICI, "..", "src", "main", "resources", "assets", "cabane", "textures", "entity", "pnj");

/** Lit un PNG (indexé ou RGBA) en pixels RGBA. */
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
      if (type === 3) {
        const i = prof === 8 ? cur[x] : (cur[(x * prof) >> 3] >> (8 - prof - ((x * prof) & 7))) & ((1 << prof) - 1);
        r = pal[i * 3]; g = pal[i * 3 + 1]; bl = pal[i * 3 + 2]; al = trns && i < trns.length ? trns[i] : 255;
      } else if (type === 6) {
        [r, g, bl, al] = [cur[x * 4], cur[x * 4 + 1], cur[x * 4 + 2], cur[x * 4 + 3]];
      } else if (type === 2) {
        [r, g, bl] = [cur[x * 3], cur[x * 3 + 1], cur[x * 3 + 2]];
      } else {
        r = g = bl = cur[x * canaux];
        if (type === 4) al = cur[x * 2 + 1];
      }
      px.set([r, g, bl, al], (y * w + x) * 4);
    }
    prec = cur;
  }
  return { w, h, px };
}

function ecrire(fichier, { w, h, px }) {
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) { brut[y * (1 + w * 4)] = 0; px.copy(brut, y * (1 + w * 4) + 1, y * w * 4, (y + 1) * w * 4); }
  const table = Array.from({ length: 256 }, (_, k) => { let c = k; for (let j = 0; j < 8; j++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (b) => { let c = 0xffffffff; for (const v of b) c = table[(c ^ v) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (t, d) => { const tb = Buffer.from(t), l = Buffer.alloc(4), cc = Buffer.alloc(4); l.writeUInt32BE(d.length); cc.writeUInt32BE(crc(Buffer.concat([tb, d]))); return Buffer.concat([l, tb, d, cc]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  fs.writeFileSync(fichier, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]));
}

export function skinsPerso() {
  if (!fs.existsSync(SOURCES)) return;
  const vegeta = lire(path.join(SOURCES, "vegeta.png"));
  const goku = lire(path.join(SOURCES, "goku_ssj2.png"));
  ecrire(path.join(DOSSIER, "vegeta.png"), vegeta);
  ecrire(path.join(DOSSIER, "goku_ssj2.png"), goku);
  // Eren, du Bataillon d'exploration : caché derrière la forge, il sait des choses
  const eren = lire(path.join(SOURCES, "eren.png"));
  ecrire(path.join(DOSSIER, "eren.png"), eren);
  // Erwin, le commandant qui mène la charge dans la cinématique des titans : le skin fourni (bras larges, cape verte)
  ecrire(path.join(DOSSIER, "erwin.png"), lire(path.join(SOURCES, "erwin.png")));
  // les soldats du Bataillon, au fond de la foule derrière les joueurs : l'uniforme d'Eren, chacun ses cheveux
  const CHEVEUX = [[34, 30, 34], [96, 62, 38], [206, 172, 96], [168, 78, 38], [122, 110, 102]];
  CHEVEUX.forEach((c, i) => {
    const soldat = recolorer(eren, (r, g, b) => (r + g + b) / 3 < 100 && Math.max(r, g, b) - Math.min(r, g, b) < 22,
      (l) => { const k = 0.55 + Math.min(1, Math.max(0, (l - 0.07) / 0.28)) * 0.7; return c.map((v) => Math.round(Math.min(255, v * k))); }, true);
    ecrire(path.join(DOSSIER, `soldat_${i + 1}.png`), soldat);
  });
  // Goku en forme normale : sa crinière dorée redevient noire
  ecrire(path.join(DOSSIER, "goku_base.png"), recolorer(goku, (r, g, b) => r > 150 && g > 110 && g - b > 40 && (r - g < 35 || (b < 90 && r - g < 75)),
    (l) => { const v = Math.round(14 + l * 50); return [v, v, v + 6]; }, false));
  // Majin Vegeta : Super Saiyan (cheveux dorés) et le « M » noir de Babidi sur le front
  const majin = recolorer(vegeta, (r, g, b) => (r + g + b) / 3 < 70 && Math.max(r, g, b) - Math.min(r, g, b) < 40,
    (l) => [Math.round(205 + l * 50), Math.round(150 + l * 85), Math.round(25 + l * 70)], true);
  for (const [x, y] of [[1, 1], [2, 1], [5, 1], [6, 1], [1, 2], [3, 2], [4, 2], [6, 2]]) {
    majin.px.set([18, 18, 22, 255], ((8 + y) * 64 + 40 + x) * 4);
  }
  ecrire(path.join(DOSSIER, "majin_vegeta.png"), majin);
  // Sung Jin-woo (Solo Leveling), sur l'avenue du sud : le skin fourni, bras larges
  ecrire(path.join(DOSSIER, "jinwoo.png"), lire(path.join(SOURCES, "jinwoo.png")));
  // les mannequins du stand de tir du commissariat (Rachid, Mamadou, Mouloud) : trois skins de gens du serveur RP,
  // tirés au hasard à chaque apparition
  for (const n of [1, 2, 3]) ecrire(path.join(DOSSIER, `cible_${n}.png`), lire(path.join(SOURCES, `cible_${n}.png`)));
  // la texture des mèches en 3D : un blanc légèrement grainé, teint en noir ou en doré au rendu
  const meche = { w: 16, h: 16, px: Buffer.alloc(16 * 16 * 4) };
  for (let i = 0; i < 256; i++) {
    const v = 215 + ((((i * 73856093) ^ (i * 19349663)) >>> 0) % 40);
    meche.px.set([v, v, v, 255], i * 4);
  }
  ecrire(path.join(DOSSIER, "..", "cheveux.png"), meche);
  console.log("Skins fournis copiés : vegeta, goku_ssj2, goku_base, majin_vegeta, eren, erwin, jinwoo, soldat_1 à 5, cible_1 à 3");
}

/**
 * Recolore les cheveux d'un skin : seulement sur la tête (et sa deuxième couche), sans toucher aux yeux ni à la
 * bouche si protegerVisage (sur le visage, seules les trois rangées du haut changent). l : la clarté d'origine, de 0 à 1.
 */
function recolorer(skin, estCheveu, couleur, protegerVisage) {
  const out = { ...skin, px: Buffer.from(skin.px) };
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 64; x++) {
      const visage = y >= 8 && ((x >= 8 && x < 16) || (x >= 40 && x < 48));
      if (protegerVisage && visage && y > 10) continue;
      const o = (y * 64 + x) * 4;
      const [r, g, b, a] = out.px.subarray(o, o + 4);
      if (a === 0 || !estCheveu(r, g, b)) continue;
      out.px.set([...couleur((r + g + b) / 765), a], o);
    }
  }
  return out;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  skinsPerso();
}
