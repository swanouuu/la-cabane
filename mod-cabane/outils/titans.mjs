// Les titans qui attaquent Beauvais, d'après le rendu validé (docs/titans/apercu/titans.html) : les mêmes pavés, les mêmes
// visages pixel par pixel. Pour chacun des cinq, écrit :
//  - textures/entity/titan/<id>.png : toutes les faces des pavés, rangées comme les boîtes d'un modèle de Minecraft ;
//  - modeles/titan_<id>.json : les parties (pivot, rotation) et leurs boîtes, que client/titans/ModeleTitan.java
//    transforme en modèle au premier rendu.
// Un pixel de titan vaut 1/4 de bloc (le modèle est dessiné 4 fois plus grand). Comme pour Brokk (outils/brokk.mjs), la
// maquette a les pieds à y = 0, regarde vers +z et a sa gauche vers +x ; un modèle de Minecraft a y vers le bas, les pieds
// à y = 24 et regarde vers -z : on retourne y et z. Les tailles (jambe, tronc, tête) sont aussi dans titans/Titan.java.
// Lancer depuis n'importe où :  node mod-cabane/outils/titans.mjs
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const RES = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources", "assets", "cabane");
const DEG = Math.PI / 180;

const hash = (x, y, s) => { let h = (x * 374761393 + y * 668265263 + s * 982451653) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };
const fois = (c, k) => [0, 1, 2].map((i) => Math.max(0, Math.min(255, c[i] * k)));
const melange = (a, b, k) => [0, 1, 2].map((i) => a[i] + (b[i] - a[i]) * k);
const grain = (c, x, y, s, f = 0.05) => fois(c, 1 - f + 2 * f * hash(x, y, s));

// ---------------------------------------------------------------- les cinq titans (les mêmes que le rendu)
export const TITANS = [
  { id: "barbu", peau: [204, 142, 104], cheveux: [86, 56, 34], cote: 4, dos: 9, jambe: 26, tronc: 18, bras: 24, larg: 10, tete: [12, 12, 11], maigre: false,
    visage: ["hhhhhhhhhhhh", "hhhhhhhhhhhh", "hhh.hhhh.hhh", "hh........hh", "h.bbb..bbb.h", "h.sss..sss.h", "h.wpw..wpw.h", "h....nn....h", "hd..nnnn..dh", "ddddkkkkdddd", "dddddddddddd", "dddddddddddd"] },
  { id: "bigleux", peau: [218, 162, 122], cheveux: [184, 88, 40], cote: 3, dos: 6, jambe: 30, tronc: 17, bras: 28, larg: 9, tete: [11, 12, 10], maigre: true, grandesOreilles: true,
    visage: ["hhhhhhhhhhh", "hhHhhhhhHhh", "hhh.hhhh.hh", ".bbb.......", ".wwww..bb..", ".wwww..wwp.", ".wpww..www.", ".wwww..www.", ".....n.....", "....kkk....", "....kRk....", "....kkk...."] },
  { id: "souriant", peau: [236, 192, 166], cheveux: [64, 42, 30], cote: 3, dos: 5, jambe: 28, tronc: 18, bras: 26, larg: 10, tete: [12, 12, 11], maigre: false,
    visage: ["hhhhhhhhhhhh", "hhhhhhhhhhhh", "hhHhhhhhhHhh", "h.hh.hh.hh.h", ".bbb....bbb.", ".wwp....pww.", "............", "kc...nn...ck", ".kkkkkkkkkk.", ".kttttttttk.", "..kttttttk..", "...kkkkkk..."] },
  { id: "machoire", peau: [190, 146, 106], cheveux: [26, 22, 24], cote: 4, dos: 10, jambe: 32, tronc: 20, bras: 27, larg: 11, tete: [11, 10, 10], maigre: false, cheveuxLongs: true, machoire: true,
    visage: ["hhhhhhhhhhh", "hhhhhhhhhhh", "hhHhhhhhHhh", "hh.......hh", "h.bbbbbbb.h", "h.pw...wp.h", "h.........h", ".....n.....", "....nnn....", ".kkkkkkkkk."] },
  { id: "chauve", peau: [222, 190, 176], cheveux: null, jambe: 24, tronc: 16, bras: 25, larg: 9, tete: [12, 12, 11], maigre: true,
    visage: ["............", "............", "..s......s..", ".ss......ss.", ".wwww..wwww.", ".wwpw..wpww.", ".wwww..wwww.", ".....nn.....", "..gggggggg..", ".gttttttttg.", ".kRRRRRRRRk.", "..tttttttt.."] },
];
TITANS.forEach((t, i) => { t.graine = 17 + i * 31; });
/** Le dos voûté (en radians), le même pour tous ; Titan.java s'en sert pour placer la nuque. */
const VOUTE = 0.13;

// ---------------------------------------------------------------- les peintures (face, x de gauche à droite, y de haut en bas, vues de dehors)
function lettre(t, ch, x, y) {
  const P = grain(t.peau, x, y, t.graine), Hc = t.cheveux || t.peau;
  switch (ch) {
    case "s": return fois(P, 0.84);
    case "S": return fois(P, 1.07);
    case "n": return fois(P, 0.88);
    case "c": return melange(P, [222, 92, 92], 0.33);
    case "h": return grain(Hc, x, y, t.graine + 3, 0.12);
    case "H": return fois(grain(Hc, x, y, t.graine + 3, 0.1), 0.72);
    case "b": return fois(Hc, 0.72);
    case "d": return grain(fois(Hc, 0.95), x, y, t.graine + 5, 0.15);
    case "w": return [238, 236, 226];
    case "p": return [24, 18, 16];
    case "k": return [72, 32, 30];
    case "r": return [132, 40, 40];
    case "R": return [58, 14, 16];
    case "t": return x % 2 ? [222, 212, 182] : [244, 238, 214];
    case "g": return [206, 104, 108];
  }
  return P;
}
const peauSimple = (t, k = 1) => (f, x, y) => fois(grain(t.peau, x, y, t.graine + f.length), f === "bas" ? 0.8 * k : k);
function peintTete(t) {
  return (f, x, y, w, h) => {
    const P = grain(t.peau, x, y, t.graine);
    const cheveu = () => lettre(t, hash(x, y, t.graine + 9) < 0.18 ? "H" : "h", x, y);
    if (f === "avant") { const ligne = t.visage[y] || ""; return lettre(t, ligne[x] || ".", x, y); }
    if (f === "bas") return fois(P, 0.78);
    if (!t.cheveux) return f === "haut" ? fois(P, 0.96) : (f === "arriere" && y > h - 3 ? fois(P, 0.9) : P);
    if (f === "haut") return cheveu();
    if (f === "arriere") return y < t.dos + (hash(x, 0, t.graine) < 0.4 ? 1 : 0) ? cheveu() : P;
    const versDos = f === "droite" ? x : w - 1 - x;
    return (y < t.cote || (versDos < 4 && y < t.dos)) ? cheveu() : P;
  };
}
function peintTorse(t) {
  return (f, x, y, w, h) => {
    let P = grain(t.peau, x, y, t.graine + 1);
    const mi = (w - 1) / 2;
    if (f === "avant") {
      if (y === 3 && Math.abs(x - mi) > 0.6 && Math.abs(x - mi) < mi - 0.5) return fois(P, 0.84);
      if (Math.abs(x - mi) < 0.6 && y > 1 && y < h - 3) P = fois(P, 0.94);
      if (t.maigre && y >= 5 && y <= 10 && y % 2 === 1 && (x < 2 || x > w - 3)) return fois(P, 0.85);
      if (Math.abs(x - mi) < 0.6 && y === h - 2) return fois(P, 0.7);
      return P;
    }
    if (f === "droite" || f === "gauche") return t.maigre && y >= 4 && y <= 11 && y % 2 === 0 ? fois(P, 0.86) : P;
    if (f === "arriere") {
      if (Math.abs(x - mi) < 0.6) return fois(P, 0.9);
      if (y >= 2 && y <= 4 && (x === 2 || x === w - 3)) return fois(P, 0.86);
      return P;
    }
    return f === "bas" ? fois(P, 0.8) : f === "haut" ? fois(P, 1.05) : P;
  };
}
function peintJambe(t) {
  const genou = Math.floor(t.jambe * 0.5);
  return (f, x, y, w, h) => {
    const P = grain(t.peau, x, y, t.graine + 2);
    if (f === "avant" && y === genou && x > 0 && x < w - 1) return fois(P, 1.08);
    if ((f === "avant" || f === "arriere") && (y === genou + 1 || y === genou - 1)) return fois(P, 0.92);
    if (y > h - 3) return fois(P, 0.93);
    return f === "bas" ? fois(P, 0.8) : P;
  };
}
const peintPied = (t) => (f, x, y, w, h) => {
  const P = grain(t.peau, x, y, t.graine + 6);
  if (f === "avant" && x >= 1 && x <= 3) return fois(P, y === 0 ? 0.85 : 0.92);
  if (f === "haut" && y > h - 2 && x >= 1 && x <= 3) return fois(P, 0.86);
  return f === "bas" ? fois(P, 0.7) : P;
};
function peintBras(t) {
  const coude = Math.floor(t.bras * 0.45);
  return (f, x, y, w, h) => {
    const P = grain(t.peau, x, y, t.graine + 3);
    if (y === coude && (f === "arriere" || f === "gauche" || f === "droite")) return fois(P, 0.88);
    if (y >= h - 4) {
      if (y === h - 4) return fois(P, 0.92);
      if ((f === "avant" || f === "arriere") && x === 1) return fois(P, 0.8);
      return fois(P, 1.03);
    }
    return f === "bas" ? fois(P, 0.85) : P;
  };
}
const peintCheveux = (t) => (f, x, y) => lettre(t, hash(x, y, t.graine + 13) < 0.22 ? "H" : "h", x, y);
const peintBarbe = (t) => (f, x, y) => (f === "haut" ? fois(t.peau, 0.8) : lettre(t, "d", x, y + 7));
const dent = () => [244, 238, 214];

// ---------------------------------------------------------------- les parties d'un titan (comme construireTitan du rendu)
function construire(t) {
  const PARTIES = [];
  const partie = (nom, pivot, rotation = [0, 0, 0], parent = null) => {
    const p = { nom, parent, pivot, rotation, boites: [] };
    PARTIES.push(p);
    p.boite = (centre, taille, peint) => { p.boites.push({ a: centre.map((c, i) => c - taille[i] / 2), b: centre.map((c, i) => c + taille[i] / 2), peint }); return p; };
    return p;
  };
  const L = t.jambe, C = t.tronc, B = t.bras, lg = t.larg, [hw, hh, hd] = t.tete, hc = C - 4;
  for (const s of [-1, 1]) {
    partie("jambe_" + (s > 0 ? "g" : "d"), [s * (lg / 2 - 2.6), L, 0])
      .boite([0, -L / 2, 0], [3.6, L, 3.6], peintJambe(t))
      .boite([0, -L + 1 - 0.02, 1], [4, 2, 6], peintPied(t));
  }
  const bassin = partie("bassin", [0, L, 0]).boite([0, 2, 0], [lg - 2, 5, 4.6], peauSimple(t, 0.97));
  const torse = partie("torse", [0, 4, 0], [VOUTE / DEG, 0, 0], bassin)
    .boite([0, hc / 2, 0], [lg, hc, 5], peintTorse(t))
    .boite([0, hc + 1.3, 0.3], [4, 3, 4], peauSimple(t));
  for (const s of [-1, 1]) {
    partie("bras_" + (s > 0 ? "g" : "d"), [s * (lg / 2 + 1.6), hc - 1.6, 0], [-VOUTE / DEG, 0, s * 0.07 / DEG], torse)
      .boite([0, -B / 2 + 1.6, 0], [3.2, B, 3.2], peintBras(t));
  }
  const tete = partie("tete", [0, hc + 2.6, 0.6], [0, 0, 0], torse).boite([0, hh / 2, 0.6], [hw, hh, hd], peintTete(t));
  const avant = 0.6 + hd / 2;
  const o = t.grandesOreilles ? [1.2, 4, 3] : [1, 3, 2];
  for (const s of [-1, 1]) tete.boite([s * (hw / 2 + o[0] / 2), hh * 0.45, 0.1], o, peauSimple(t, 0.95));
  const gros = t.id === "barbu" ? 3 : 2;
  const ligneNez = t.visage.findIndex((l) => l.includes("n"));
  tete.boite([0, hh - ligneNez + 0.7, avant + (gros === 3 ? 1 : 0.7)], [gros, 2.4, gros === 3 ? 2 : 1.4], peauSimple(t, 0.97));
  if (t.cheveuxLongs) {
    tete.boite([0, -3.4, 0.6 - hd / 2 + 0.9], [hw + 0.4, 9, 2.4], peintCheveux(t));
    for (const s of [-1, 1]) tete.boite([s * (hw / 2 + 0.5), hh - 6, 0.6 - hd * 0.12], [1.4, 7, hd * 0.7], peintCheveux(t));
  }
  if (t.id === "barbu") {
    tete.boite([0, -2.2, avant - 1.2], [10, 5, 3], peintBarbe(t));
    tete.boite([0, hh - 10.5, 0.6 - hd / 2 + 0.6], [hw + 0.4, 5, 2], peintCheveux(t));
  }
  if (t.machoire) {
    const m = partie("machoire", [0, 0.2, 0.6 - hd / 2 + 1], [0, 0, 0], tete)
      .boite([0, -2, hd / 2 + 1.1], [hw, 4.2, hd - 0.4], (f, x, y, w, h) => (f === "avant" && y === 0 ? (x % 2 ? [72, 32, 30] : [244, 238, 214])
        : fois(grain(t.peau, x, y, t.graine + 8), f === "bas" ? 0.75 : y === h - 1 ? 0.88 : 0.97)));
    for (const dx of [-4, -1.5, 1.5, 4]) m.boite([dx, 0.6, hd + 0.6], [1, 1.6, 0.9], dent);
  }
  if (t.id === "chauve") {
    for (const [dx, dy] of [[-3, hh - 1.5], [2.5, hh - 2.5]]) tete.boite([dx, dy, avant + 0.2], [0.5, 3, 0.4], () => fois(t.peau, 0.82));
  }
  return { PARTIES, sommet: L + 4 + (hc + 2.6 + hh) * Math.cos(VOUTE) };
}

// ---------------------------------------------------------------- vers Minecraft (le même rangement que outils/brokk.mjs)
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

function ecrire(t) {
  const { PARTIES, sommet } = construire(t);
  const LARGEUR = 128;
  const boites = PARTIES.flatMap((p) => p.boites.map((b) => {
    const c = b.a.map((v, i) => (v + b.b[i]) / 2), dim = [0, 1, 2].map((i) => Math.abs(b.b[i] - b.a[i]));
    const n = dim.map((v) => Math.max(1, Math.round(v)));
    return Object.assign(b, { c, dim, n, uw: 2 * (n[2] + n[0]), uh: n[2] + n[1] });
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
  const modele = {
    texture: [LARGEUR, HAUTEUR],
    sommet: arrondi(sommet),
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
  fs.mkdirSync(path.join(RES, "textures", "entity", "titan"), { recursive: true });
  fs.writeFileSync(path.join(RES, "textures", "entity", "titan", t.id + ".png"), png(LARGEUR, HAUTEUR, px));
  const { parties, ...entete } = modele;
  fs.writeFileSync(path.join(RES, "modeles", `titan_${t.id}.json`),
    JSON.stringify(entete).slice(0, -1) + ',"parties":[\n' + parties.map((p) => "  " + JSON.stringify(p)).join(",\n") + "\n]}\n");
  return `${t.id} (${boites.length} boîtes, ${LARGEUR} x ${HAUTEUR}, ${(sommet / 4).toFixed(1)} blocs)`;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  console.log("Titans : " + TITANS.map(ecrire).join(", "));
}
