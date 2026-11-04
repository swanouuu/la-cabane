// Zeus géant de /zeus, d'après l'aperçu validé (docs/zeus) : les mêmes pavés, les mêmes couleurs. Écrit :
//  - textures/entity/zeus.png : toutes les faces des pavés, rangées comme les boîtes d'un modèle de Minecraft ;
//  - modeles/zeus.json : les parties (pivot, rotation) et leurs boîtes, que client/zeus/ClientZeus.java lit.
// Comme pour Brokk (outils/brokk.mjs) : la maquette a y vers le haut, les pieds à y = 0, regarde vers +z, sa gauche vers +x.
// L'unité est le demi-pixel d'un joueur (la tête fait 16 de côté) : la texture est donc deux fois plus fine qu'une skin.
// Lancer depuis n'importe où :  node mod-cabane/outils/zeus.mjs
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const RES = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources", "assets", "cabane");
const DEG = Math.PI / 180;

// ---------------------------------------------------------------- les couleurs (celles de l'aperçu)
const rgb = (h) => [(h >> 16) & 255, (h >> 8) & 255, h & 255];
const SK = rgb(0xc68a5c), SKD = rgb(0xa46c44), SKDD = rgb(0x74482b), SKL = rgb(0xdca171);
const HR = rgb(0xfafaf6), HRD = rgb(0xcbc9c1), HRDD = rgb(0xa29f98);
const CL = rgb(0xf1eee6), CLD = rgb(0xd3cdbf), CLDD = rgb(0xaea793);
const GD = rgb(0xf5c443), GDD = rgb(0xc88a1b), GDL = rgb(0xffe48a);
const SOCK = rgb(0x24130b), BRN = rgb(0x5e3a1f), DENT = rgb(0xf3eee2), DENT2 = rgb(0xddd5c3), GUEULE = rgb(0x2b120b);
const CAPE = rgb(0x2a3aa0), CAPE2 = rgb(0x223189), CAPE3 = rgb(0x1a2462);
const OEIL = rgb(0xe6faff), GEMME = rgb(0x5ad8ff);

const hache = (i, j, k) => { let h = (i * 374761393 + j * 668265263 + k * 2147483647) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };
const clair = (c, k) => c.slice(0, 3).map((v) => Math.min(255, v * k));
/** Un peu de grain sur chaque pixel, comme une vraie texture. */
const grain = (c, i, j, graine, force = 0.07) => clair(c, 1 - force + 2 * force * hache(i, j, graine));

// ---------------------------------------------------------------- les peintures (face, u de gauche à droite, v de haut en bas)
const visage = (x, y) => {
  if (y < 3) return y === 2 && x % 3 === 1 ? HRD : HR;
  if (x < 2 || x > 13) return y < 11 ? (x === 1 || x === 14 ? HRD : HR) : HR;
  const sourcil = x <= 7 ? 3 + Math.round((x - 2) * 0.55) : 3 + Math.round((13 - x) * 0.55);
  if (x <= 6 || x >= 9) {
    if (y === sourcil) return HR;
    if (y === sourcil + 1) return HRD;
    if (y === sourcil + 2) return SKDD;
  }
  if ((x === 7 || x === 8) && y >= 3 && y <= 6) return y % 2 ? SKD : SKDD;
  if (y >= 7 && y <= 8 && ((x >= 3 && x <= 6) || (x >= 9 && x <= 12))) return SOCK;
  if ((x === 7 || x === 8) && y >= 7 && y <= 9) return x === 7 ? SKL : SKD;
  if ((x === 7 || x === 8) && y === 10) return SKDD;
  if (y === 9) return x === 3 || x === 12 || x === 6 || x === 9 ? SKD : SK;
  if (y === 10) return x <= 4 || x >= 11 ? SKD : SK;
  if (y === 11) return x === 7 || x === 8 ? HRD : HR;
  if (y === 12 && x >= 4 && x <= 11) return x % 2 ? DENT : DENT2;
  if (y === 13 && x >= 4 && x <= 11) return GUEULE;
  return (x * 7 + y * 3) % 5 === 0 ? HRD : HR;
};
const coteTete = (devantAGauche) => (x, y) => {
  const X = devantAGauche ? 15 - x : x;
  if (y < 3 || X < 8) return (X + y) % 4 === 0 ? HRD : HR;
  if (X >= 8 && X <= 9 && y >= 7 && y <= 9) return SKD;
  if (X >= 10 && X <= 11 && y < 11) return HRD;
  if (y >= 10) return HR;
  return X >= 14 ? SKD : SK;
};
const tete = (f, u, v) => {
  const c = f === "avant" ? visage(u, v) : f === "droite" ? coteTete(false)(u, v) : f === "gauche" ? coteTete(true)(u, v)
    : f === "arriere" ? ((u * 3 + v) % 5 === 0 ? HRD : (u + v * 2) % 9 === 0 ? HRDD : HR)
    : f === "haut" ? ((u + v) % 5 === 0 ? HRD : HR) : HRD;
  return grain(c, u, v, 1);
};
const torse = (f, u, v) => {
  let c;
  if (f === "haut") c = u < 6 ? CL : SK;
  else if (f === "bas") c = CL;
  else if (v >= 18) c = v >= 20 ? (u % 4 === 0 ? CLD : CL) : GD;
  else if (f === "avant") {
    const e = 2 + v * 0.55;
    if (Math.abs(u - e) <= 2.6) c = Math.abs(u - e) > 1.9 ? CLD : v % 4 === 0 ? CLD : CL;
    else if (u === 0 || u === 15) c = SKD;
    else if (v === 9 && u >= 1 && u <= 14 && u !== 7 && u !== 8) c = SKDD;
    else if ((u === 7 || u === 8) && v >= 2 && v <= 16) c = SKD;
    else if (v >= 3 && v <= 8 && (u === 3 || u === 12)) c = SKL;
    else if ((v === 12 || v === 15) && u >= 4 && u <= 11) c = SKD;
    else if (v >= 10 && v <= 17 && (u === 5 || u === 10)) c = SKL;
    else c = SK;
  } else if (f === "arriere") c = Math.abs(u - (13 - v * 0.55)) <= 2.6 ? CL : u === 7 || u === 8 ? SKD : SK;
  else if (f === "droite") c = v < 6 ? CLD : SK;
  else c = SKD;
  return grain(c, u, v, 2);
};
const bras = (graine) => (f, u, v) => {
  let c;
  if (f === "haut") c = SK;
  else if (f === "bas") c = (u + v) % 3 ? SKD : SKDD;
  else if (v === 2 || v === 3) c = v === 2 ? GDL : GDD;
  else if (v >= 21) c = u % 2 && v === 21 ? SKDD : SKD;
  else if (v === 10) c = SKD;
  else if (v >= 4 && v <= 9 && u >= 2 && u <= 5) c = SKL;
  else if (u === 0 || u === 7) c = SKD;
  else c = SK;
  return grain(c, u, v, graine);
};
const jambe = (graine) => (f, u, v) => {
  let c;
  if (f === "haut") c = CL;
  else if (f === "bas") c = BRN;
  else if (v < 9) c = u % 4 === 0 ? CLD : CL;
  else if (v >= 12 && v <= 18) c = v === 12 || v === 18 || u === 0 || u === 7 ? GDD : v === 15 ? GDL : GD;
  else if (v >= 20) c = v === 21 || u % 3 === 0 ? GD : BRN;
  else c = u === 0 || u === 7 ? SKD : SK;
  return grain(c, u, v, graine);
};
const or = (graine) => (f, u, v, nu, nv) => {
  const bord = u === 0 || v === 0 || f === "haut";
  const c = bord ? GDL : u === nu - 1 || v === nv - 1 ? GDD : hache(u, v, graine) < 0.14 ? GDL : hache(u, v, graine + 9) < 0.1 ? GDD : GD;
  return grain(c, u, v, graine, 0.05);
};
const cheveux = (f, u, v) => grain(u % 4 === 0 || (u * 7 + v * 3) % 13 === 0 ? HRD : HR, u, v, 5, 0.03);
const barbe = (f, u, v) => grain(u % 3 === 0 && (v + u) % 5 ? HRD : HR, u, v, 6, 0.03);
const jupe = (f, u, v) => grain(v < 1 ? GDD : u % 4 === 0 ? CLD : u % 4 === 3 ? CLDD : CL, u, v, 7);
const cape = (f, u, v, nu, nv) => {
  if (f !== "avant" && f !== "arriere") return grain(v % 2 ? GDD : GD, u, v, 8);
  if (u === 0 || u === nu - 1 || v >= nv - 3) return grain(v % 2 ? GDD : GD, u, v, 8);
  return grain(u % 4 === 1 ? CAPE3 : u % 4 === 2 ? CAPE2 : CAPE, u, v, 9);
};
const uni = (c) => (f, u, v) => grain(c, u, v, 10, 0.02);

// ---------------------------------------------------------------- le modèle (comme dans l'aperçu, en demi-pixels)
const PARTIES = [];
function partie(nom, pivot, rotation = [0, 0, 0], parent = null) {
  const p = { nom, parent, pivot, rotation, boites: [] };
  PARTIES.push(p);
  p.boite = (a, b, peint) => { p.boites.push({ a, b, peint }); return p; };
  return p;
}
const nomCote = (s) => (s > 0 ? "g" : "d");

for (const s of [-1, 1]) partie("jambe_" + nomCote(s), [s * 4, 24, 0]).boite([-4, -24, -4], [4, 0, 4], jambe(s > 0 ? 20 : 21));
partie("jupe", [0, 0, 0]).boite([-8.9, 15.6, -4.9], [8.9, 26.8, 4.9], jupe);
const corps = partie("torse", [0, 24, 0])
  .boite([-8, 0, -4], [8, 24, 4], torse)
  .boite([-8.7, 1.6, -4.7], [8.7, 4.8, 4.7], or(30))
  .boite([-2.2, 0.8, 4.4], [2.2, 5.6, 5.8], or(31));
partie("cape", [0, 23.2, -4.4], [0, 0, 0], corps).boite([-9, -42, -1.5], [9, 0, -0.1], cape);
const t = partie("tete", [0, 24, 0], [0, 0, 0], corps)
  .boite([-8, 0, -8], [8, 16, 8], tete)
  .boite([-8.8, -0.7, -9.4], [8.8, 14.3, -3.8], cheveux)
  .boite([-7.8, -6.8, 7.2], [7.8, 2, 12.4], barbe)
  .boite([-3.8, -11.6, 7.8], [3.8, -6.8, 11.8], barbe)
  .boite([-8.9, 14.7, -8.9], [8.9, 17.7, 8.9], or(32));
for (const [x, y, z, w, h] of [[0, 21.2, 8, 2.4, 6.8], [-5.4, 20.2, 8, 2, 4.8], [5.4, 20.2, 8, 2, 4.8], [-8, 20.2, 3.2, 2, 4.8], [8, 20.2, 3.2, 2, 4.8],
  [-8, 20.2, -3.2, 2, 4.8], [8, 20.2, -3.2, 2, 4.8], [0, 20.2, -8, 2, 4.8]]) t.boite([x - w / 2, y - h / 2, z - w / 2], [x + w / 2, y + h / 2, z + w / 2], or(33));
for (const s of [-1, 1]) {
  // les sourcils froncés, en V (le bout intérieur plus bas)
  partie("sourcil_" + nomCote(s), [s * 4, 10.3, 8.7], [0, 0, s * 0.42 / DEG], t).boite([-3.6, -1.1, -1.5], [3.6, 1.1, 1.5], cheveux);
  // les yeux qui brillent (dessinés à part, en pleine lumière)
  partie("oeil_" + nomCote(s), [s * 3, 8, 8.12], [0, 0, s * 0.32 / DEG], t).boite([-2.1, -0.75, -0.25], [2.1, 0.75, 0.25], uni(OEIL));
}
partie("gemme", [0, 16.2, 9.1], [0, 0, 0], t).boite([-1.1, -1.1, -0.4], [1.1, 1.1, 0.4], uni(GEMME));
for (const s of [-1, 1]) {
  const b = partie("bras_" + nomCote(s), [s * 12, 20, 0], [0, 0, 0], corps)
    .boite([-4, -20, -4], [4, 4, 4], bras(s > 0 ? 40 : 41))
    .boite([s * 0.6 - 5.6, -0.6, -5.6], [s * 0.6 + 5.6, 5, 5.6], or(42))
    .boite([-4.7, -18, -4.7], [4.7, -10.4, 4.7], or(43));
  partie("main_" + nomCote(s), [0, -21.2, 0], [0, 0, 0], b);
}

// ---------------------------------------------------------------- vers Minecraft : les boîtes entières, rangées dans la texture
const LARGEUR = 256;
const boites = PARTIES.flatMap((p) => p.boites.map((b) => {
  const c = b.a.map((v, i) => (v + b.b[i]) / 2), dim = [0, 1, 2].map((i) => Math.abs(b.b[i] - b.a[i]));
  const n = dim.map((v) => Math.max(1, Math.round(v)));
  Object.assign(b, { c, dim, n, uw: 2 * (n[2] + n[0]), uh: n[2] + n[1] });
  return b;
}));
let x = 0, y = 0, rangee = 0;
for (const b of [...boites].sort((a, b) => b.uh - a.uh || b.uw - a.uw)) {
  if (x + b.uw > LARGEUR) { x = 0; y += rangee; rangee = 0; }
  Object.assign(b, { u: x, v: y });
  x += b.uw;
  rangee = Math.max(rangee, b.uh);
}
const HAUTEUR = [64, 128, 256, 512].find((h) => h >= y + rangee);

const px = Buffer.alloc(LARGEUR * HAUTEUR * 4);
for (const b of boites) {
  const [w, h, d] = b.n;
  const zone = (x0, y0, lw, lh, face) => {
    for (let j = 0; j < lh; j++) for (let i = 0; i < lw; i++) {
      const col = b.peint(face, i, j, lw, lh);
      if (col) px.set([...col.map(Math.round), 255], ((y0 + j) * LARGEUR + x0 + i) * 4);
    }
  };
  zone(b.u + d, b.v, w, d, "haut");
  zone(b.u + d + w, b.v, w, d, "bas");
  zone(b.u, b.v + d, d, h, "droite");
  zone(b.u + d, b.v + d, w, h, "avant");
  zone(b.u + d + w, b.v + d, d, h, "gauche");
  zone(b.u + 2 * d + w, b.v + d, w, h, "arriere");
}

function png(w, h, rgba) {
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let j = 0; j < h; j++) rgba.copy(brut, j * (1 + w * 4) + 1, j * w * 4, (j + 1) * w * 4);
  const tb = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const o of buf) c = tb[(c ^ o) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

const arrondi = (v) => Math.round(v * 10000) / 10000;
const modele = {
  texture: [LARGEUR, HAUTEUR],
  parties: PARTIES.map((p) => ({
    nom: p.nom,
    parent: p.parent?.nom ?? null,
    pivot: [p.pivot[0], (p.parent ? 0 : 24) - p.pivot[1], -p.pivot[2]].map(arrondi),
    rotation: [p.rotation[0], -p.rotation[1], -p.rotation[2]].map((a) => arrondi(a * DEG)),
    boites: p.boites.map((b) => ({
      uv: [b.u, b.v],
      coin: [b.c[0] - b.n[0] / 2, -b.c[1] - b.n[1] / 2, -b.c[2] - b.n[2] / 2].map(arrondi),
      taille: b.n,
      gonfle: b.dim.map((v, i) => arrondi((v - b.n[i]) / 2)),
    })),
  })),
};

fs.mkdirSync(path.join(RES, "modeles"), { recursive: true });
fs.writeFileSync(path.join(RES, "textures", "entity", "zeus.png"), png(LARGEUR, HAUTEUR, px));
const { parties, ...entete } = modele;
fs.writeFileSync(path.join(RES, "modeles", "zeus.json"),
  JSON.stringify(entete).slice(0, -1) + ',"parties":[\n' + parties.map((p) => "  " + JSON.stringify(p)).join(",\n") + "\n]}\n");
console.log(`Zeus : ${PARTIES.length} parties, ${boites.length} boîtes, texture ${LARGEUR} x ${HAUTEUR}`);
