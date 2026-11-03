// Brokk le Forgeron en orque de guerre, d'après la maquette validée (docs/brokk/generateur/rendu.mjs) : les mêmes pavés,
// les mêmes couleurs pixel par pixel. Écrit :
//  - textures/entity/brokk_orque.png : toutes les faces des pavés, rangées comme les boîtes d'un modèle de Minecraft ;
//  - modeles/brokk.json : les parties (pivot, rotation) et leurs boîtes (place dans la texture, coin, taille), que
//    client/ClientBrokk.java transforme en modèle au premier rendu.
// La maquette est en pixels, pieds à y = 0, regard vers +z, sa gauche vers +x ; un modèle de Minecraft a y vers le bas,
// les pieds à y = 24 et regarde vers -z : on passe de l'un à l'autre en retournant y et z.
// Lancer depuis n'importe où :  node mod-cabane/outils/brokk.mjs
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const RES = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources", "assets", "cabane");
const DEG = Math.PI / 180;

// ---------------------------------------------------------------- les couleurs (celles de la maquette)
const PEAU = [84, 132, 52];
const PEAU_SOMBRE = [62, 102, 38];
const PEAU_CLAIRE = [104, 154, 64];
const PEAU_TRAIT = [44, 76, 28];
const CUIR = [156, 84, 44];
const CUIR_SOMBRE = [112, 58, 30];
const CUIR_CLAIR = [180, 104, 60];
const CUIR_NOIR = [62, 36, 22];
const CORNE = [228, 204, 126];
const CORNE_OMBRE = [196, 168, 92];
const CORNE_CLAIRE = [244, 230, 170];
const FER = [140, 140, 146];
const FER_SOMBRE = [86, 86, 94];
const FER_CLAIR = [188, 188, 194];
const DENT = [232, 206, 118];
const OEIL = [222, 200, 168];
const ROUGE = [196, 34, 30];
const GUEULE = [112, 28, 24];
const GUEULE_FOND = [62, 16, 14];
const LAME = [52, 52, 60];
const LAME_CLAIRE = [92, 92, 102];

const hache = (i, j, k) => { let h = (i * 374761393 + j * 668265263 + k * 2147483647) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };
const clair = (c, k) => c.slice(0, 3).map((v) => Math.min(255, v * k));
const mix = (a, b, k) => a.map((v, i) => v + (b[i] - v) * k);
const norme = (v) => { const l = Math.hypot(...v) || 1; return v.map((c) => c / l); };
const ajoute = (a, b) => a.map((v, i) => v + b[i]);
const fois = (a, k) => a.map((v) => v * k);
const tournerZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];
/** Un peu de grain sur chaque pixel, comme une vraie texture. */
const grain = (c, i, j, graine, force = 0.07) => clair(c, 1 - force + 2 * force * hache(i, j, graine));

// ---------------------------------------------------------------- les peintures (face, u de gauche à droite, v de haut en bas)
const jambe = (graine) => (face, u, v) => {
  if (face === "haut") return grain(CUIR_SOMBRE, u, v, graine);
  if (face === "bas") return grain(CUIR_NOIR, u, v, graine);
  if (v < 5 || (v === 5 && hache(u, face.length, graine) < 0.55)) return grain(v === 4 ? CUIR_SOMBRE : CUIR, u, v, graine + v, 0.09);
  return grain(v === 6 ? PEAU_SOMBRE : PEAU, u, v, graine + 1);
};
const botte = (graine) => (face, u, v, nu, nv) => {
  if (face === "bas") return grain(CUIR_NOIR, u, v, graine);
  if (face === "haut") return grain(CUIR_CLAIR, u, v, graine);
  if (v === nv - 1) return grain(CUIR_NOIR, u, v, graine);
  if (v === 0) return grain(CUIR_CLAIR, u, v, graine);
  if (face === "avant" && u === Math.floor(nu / 2)) return grain(CUIR_SOMBRE, u, v, graine);
  return grain(CUIR, u, v, graine + 2, 0.09);
};
function torse(face, u, v, nu) {
  const mid = (nu - 1) / 2, du = Math.abs(u - mid);
  if (face === "haut") return grain(CUIR, u, v, 3, 0.09);
  if (face === "bas") return grain(CUIR_SOMBRE, u, v, 4);
  if (v <= 3) return grain(v === 3 ? CUIR_SOMBRE : CUIR, u, v, 5, 0.09);
  if (v === 4) return hache(u, face.length, 6) < 0.5 ? grain(CUIR_SOMBRE, u, v, 6) : grain(PEAU_SOMBRE, u, v, 7);
  if (v <= 10) {
    if (face === "avant") {
      if (v === 5 && (u + 1) % 3 === 0) return grain(CUIR_NOIR, u, v, 8);
      if (v === 7 && du <= 4.5) return grain(PEAU_TRAIT, u, v, 9);
      if (v >= 8 && du < 0.6) return grain(PEAU_TRAIT, u, v, 10);
      if (v === 9 && du <= 3) return grain(PEAU_SOMBRE, u, v, 11);
      return grain(v === 8 || v === 10 ? PEAU : PEAU_CLAIRE, u, v, 12);
    }
    if (face === "arriere" && du < 0.6) return grain(PEAU_SOMBRE, u, v, 13);
    return grain(PEAU, u, v, 14 + v);
  }
  if (v <= 12) return grain(v === 11 ? CUIR_SOMBRE : CUIR, u, v, 15, 0.09);
  return grain(CUIR_SOMBRE, u, v, 16);
}
const pagne = (face, u, v, nu, nv) => {
  if (v === nv - 1 && u % 2 === 1) return null;
  if (u === 0 || u === nu - 1) return grain(CUIR_SOMBRE, u, v, 20);
  return grain(v % 3 === 2 ? mix(CUIR, CUIR_SOMBRE, 0.35) : CUIR, u, v, 21, 0.09);
};
const brasCouleur = (graine) => (face, u, v, nu, nv) => {
  if (face === "bas" || v >= nv - 2) return grain(face === "avant" && v === nv - 2 && u % 2 === 1 ? PEAU_TRAIT : PEAU_SOMBRE, u, v, graine);
  if (face === "avant" && v === 5) return grain(PEAU_SOMBRE, u, v, graine + 1);
  return grain(v < 2 ? PEAU_SOMBRE : PEAU, u, v, graine + 2);
};
const cuirEpais = (graine) => (face, u, v, nu, nv) => {
  if (face === "haut") return grain(hache(u, v, graine) < 0.06 ? CUIR : CUIR_CLAIR, u, v, graine, 0.06);
  if (face === "bas") return grain(CUIR_SOMBRE, u, v, graine);
  if (v === nv - 1) return grain(CUIR_SOMBRE, u, v, graine + 1);
  if (hache(u, v, graine + face.length) < 0.05) return grain(mix(CUIR, CUIR_SOMBRE, 0.5), u, v, graine + 2);
  return grain(v === 0 ? CUIR_CLAIR : CUIR, u, v, graine + 3, 0.06);
};
const lameCouleur = (face, u, v) => {
  if (face === "haut") return grain(LAME_CLAIRE, u, v, 30);
  if (face === "bas") return grain(mix(LAME, LAME_CLAIRE, 0.5), u, v, 31);
  if (face !== "gauche" && face !== "droite") return grain(LAME, u, v, 32);
  if (v === 0) return grain(LAME_CLAIRE, u, v, 33);
  return grain(hache(u, v, 34) < 0.12 ? mix(LAME, LAME_CLAIRE, 0.4) : LAME, u, v, 35, 0.05);
};
const mancheCouleur = (face, u, v) => ((u + v) % 2 ? grain(CUIR_NOIR, u, v, 36) : grain(CUIR_SOMBRE, u, v, 37));
const crane = (face, u, v, nu, nv) => {
  if (face === "haut") return grain(CUIR, u, v, 40, 0.09);
  if (face === "bas") return grain(GUEULE_FOND, u, v, 41);
  if (v === 0) return grain(CUIR_SOMBRE, u, v, 42);
  if (face !== "avant") return grain(v >= nv - 2 ? PEAU_SOMBRE : PEAU, u, v, 43);
  if (v === 3) return u === 1 || u === 2 || u === 5 || u === 6 ? grain(PEAU_TRAIT, u, v, 44) : grain(PEAU_SOMBRE, u, v, 45);
  if (v === 4) {
    if (u === 1 || u === 6) return OEIL;
    if (u === 2 || u === 5) return ROUGE;
    return grain(PEAU, u, v, 46);
  }
  if (v === 6) return u === 0 || u === nu - 1 ? grain(PEAU_SOMBRE, u, v, 47) : u % 2 === 1 ? DENT : GUEULE;
  if (v === 7) return u === 0 || u === nu - 1 ? grain(PEAU_SOMBRE, u, v, 48) : u === 1 || u === nu - 2 ? DENT : GUEULE_FOND;
  return grain(v <= 2 ? PEAU_SOMBRE : PEAU, u, v, 49);
};
const machoire = (f, u, v, nu, nv) => {
  if (f === "haut") return v === nv - 1 ? (u % 2 === 0 ? DENT : GUEULE) : grain(GUEULE_FOND, u, v, 55);
  return grain(f === "avant" ? (v === 0 ? PEAU_SOMBRE : PEAU) : PEAU_SOMBRE, u, v, 56);
};

// ---------------------------------------------------------------- le modèle (comme dans la maquette)
/** Les parties, dans l'ordre (un parent avant ses enfants) : pivot et rotation (degrés) dans le repère du parent. */
const PARTIES = [];
function partie(nom, pivot, rotation = [0, 0, 0], parent = null) {
  const p = { nom, parent, pivot, rotation, boites: [] };
  PARTIES.push(p);
  p.boite = (a, b, peint) => { p.boites.push({ a, b, peint }); return p; };
  return p;
}
/** Les deux coins d'un pavé posé du côté s (1 : sa gauche, +x ; -1 : sa droite), donnés vers l'extérieur. */
const cote = (s, [a0, y0, z0], [a1, y1, z1]) => [[Math.min(s * a0, s * a1), y0, z0], [Math.max(s * a0, s * a1), y1, z1]];
const nomCote = (s) => (s > 0 ? "g" : "d");

const corps = partie("corps", [0, 0, 0]);
corps.boite([-6, 13, -3.5], [6, 27, 3.5], torse);
corps.boite([-2, 13.5, 3.5], [2, 16.5, 4.2], (f, u, v) => (f === "avant" && v === 1 && (u === 1 || u === 2) ? FER_SOMBRE : grain(u === 0 || v === 0 ? FER_CLAIR : FER, u, v, 22)));
corps.boite([-2.5, 6, 3.5], [2.5, 13.5, 4], pagne);
corps.boite([-2.5, 7, -4], [2.5, 13.5, -3.5], pagne);
for (const [x, y] of [[-4.5, 20], [-1.5, 19.5], [1.5, 19.5], [4.5, 20]]) {
  corps.boite([x - 1, y, 3.5], [x + 1, y + 2, 4.5], (f, u, v) => grain(f === "haut" || v === 0 ? FER_CLAIR : v === 1 && u === 1 ? FER_SOMBRE : FER, u, v, 23));
  corps.boite([x - 0.3, y + 2, 3.7], [x + 0.3, y + 2.8, 4.3], () => FER_CLAIR);
}
for (const s of [-1, 1]) {
  partie("jambe_" + nomCote(s), [s * 2.75, 13, 0], [0, 0, s * 2])
    .boite([-2.5, -13, -2.5], [2.5, 0, 2.5], jambe(s > 0 ? 50 : 51))
    .boite([-3, -13, -3], [3, -10, 3.5], botte(s > 0 ? 52 : 53));
}
for (const s of [-1, 1]) {
  const bras = partie("bras_" + nomCote(s), [s * 8.5, 25, 0], [0, 0, s * 4])
    .boite([-2.5, -12, -2.5], [2.5, 2, 2.5], brasCouleur(s > 0 ? 60 : 61))
    .boite([-3, -8, -3], [3, -5, 3], cuirEpais(s > 0 ? 62 : 63))
    .boite(...cote(s, [-3, -3, -4.5], [5, 4, 4.5]), cuirEpais(s > 0 ? 64 : 65));
  partie("hachoir_" + nomCote(s), [0, -11, 0], [0, s * 55, -s * 4], bras)
    .boite([-0.75, -0.75, -2], [0.75, 0.75, 1.5], mancheCouleur)
    .boite([-0.5, -2, 1.5], [0.5, 1, 11.5], lameCouleur);
}
const tete = partie("tete", [0, 24.5, 1])
  .boite([-4, 2, -4], [4, 10, 4], crane)
  .boite([-4.5, 9.5, -4.5], [4.5, 10.5, 4.5], (f, u, v) => grain(f === "haut" ? CUIR : CUIR_SOMBRE, u, v, 54, 0.09))
  .boite([-4.5, -1, -3], [4.5, 2, 5.5], machoire);
for (const s of [-1, 1]) tete.boite(...cote(s, [3, 2, 4.4], [4.2, 4.5, 5.4]), (f, u, v, nu, nv) => (v === 0 ? CORNE_CLAIRE : v === nv - 1 ? CORNE_OMBRE : DENT));
// les cornes en croissant : des tronçons de plus en plus fins, chacun tourné dans l'axe de la courbe
let sommet = 0;
for (const s of [-1, 1]) {
  let p = [s * 3.6, 8, 0], d = norme([s, -0.4, 0]);
  const n = 11, pas = 1.75;
  for (let k = 0; k < n; k++) {
    const w = 3.9 * (1 - (k / n) * 0.62);
    const c = ajoute(p, fois(d, pas / 2));
    const col = k >= n - 2 ? CORNE_CLAIRE : k % 4 === 3 ? CORNE_OMBRE : CORNE;
    // le tronçon a son axe Y le long de d : une rotation autour de z
    partie(`corne_${nomCote(s)}${k}`, c, [0, 0, Math.atan2(-d[0], d[1]) / DEG], tete)
      .boite([-w / 2, -pas * 0.68, -w / 2], [w / 2, pas * 0.68, w / 2], (f, u, v) => grain(col, u + k, v, 70 + k, 0.05));
    p = ajoute(p, fois(d, pas));
    sommet = Math.max(sommet, tete.pivot[1] + p[1] + w / 2);
    d = norme(tournerZ(d, s * 11 * DEG));
  }
}

// ---------------------------------------------------------------- vers Minecraft : les boîtes entières, rangées dans la texture
const LARGEUR = 128;
const boites = PARTIES.flatMap((p) => p.boites.map((b) => {
  const c = fois(ajoute(b.a, b.b), 0.5), dim = [0, 1, 2].map((i) => Math.abs(b.b[i] - b.a[i]));
  const n = dim.map((v) => Math.max(1, Math.round(v)));
  Object.assign(b, { c, dim, n, uw: 2 * (n[2] + n[0]), uh: n[2] + n[1] });
  return b;
}));
// rangées les unes sous les autres, les plus hautes d'abord
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
  const t = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const o of buf) c = t[(c ^ o) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

const arrondi = (v) => Math.round(v * 10000) / 10000;
const modele = {
  texture: [LARGEUR, HAUTEUR],
  // le haut des cornes, en pixels au-dessus des pieds (pour placer le nom au-dessus)
  sommet: arrondi(sommet),
  parties: PARTIES.map((p) => ({
    nom: p.nom,
    parent: p.parent?.nom ?? null,
    // y et z retournés ; une partie posée sur le modèle a ses pieds à y = 24
    pivot: [p.pivot[0], (p.parent ? 0 : 24) - p.pivot[1], -p.pivot[2]].map(arrondi),
    // retourner y et z garde l'angle autour de x, et change le signe des deux autres
    rotation: [p.rotation[0], -p.rotation[1], -p.rotation[2]].map((a) => arrondi(a * DEG)),
    boites: p.boites.map((b) => ({
      uv: [b.u, b.v],
      coin: [b.c[0] - b.n[0] / 2, -b.c[1] - b.n[1] / 2, -b.c[2] - b.n[2] / 2].map(arrondi),
      taille: b.n,
      // la boîte garde sa taille entière dans la texture ; on la gonfle (ou la dégonfle) jusqu'à la taille de la maquette
      gonfle: b.dim.map((v, i) => arrondi((v - b.n[i]) / 2)),
    })),
  })),
};

fs.mkdirSync(path.join(RES, "modeles"), { recursive: true });
fs.writeFileSync(path.join(RES, "textures", "entity", "brokk_orque.png"), png(LARGEUR, HAUTEUR, px));
// une partie par ligne
const { parties, ...entete } = modele;
fs.writeFileSync(path.join(RES, "modeles", "brokk.json"),
  JSON.stringify(entete).slice(0, -1) + ',"parties":[\n' + parties.map((p) => "  " + JSON.stringify(p)).join(",\n") + "\n]}\n");
console.log(`Brokk : ${PARTIES.length} parties, ${boites.length} boîtes, texture ${LARGEUR} x ${HAUTEUR}, sommet ${modele.sommet} px`);
