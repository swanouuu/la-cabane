// Le moteur des maquettes du cartel : un monde de blocs (vraies textures 16×16 dessinées ici, occlusion dans les coins,
// ombres portées d'un soleil de fin d'après-midi), des pavés libres (auvents, tables, voitures), les persos avec leur
// skin et leur arme en main, les flammes de bouche, les traçantes et les étiquettes.
// Unités : 16 par bloc (comme les pixels du modèle du joueur) ; y vers le haut, la caméra voit les faces +x et +z.
import { PARTIES, texels, hache, norme, croix, scene, POLICE } from "../../spies/generateur/moteur.mjs";
import { SIGNES } from "./armes.mjs";

Object.assign(POLICE, SIGNES);
export const ajoute = (a, b) => a.map((v, i) => v + b[i]);
export const sous = (a, b) => a.map((v, i) => v - b[i]);
export const fois = (a, k) => a.map((v) => v * k);
export const melange = (a, b, k) => a.map((v, i) => v + (b[i] - v) * k);
export const rotX = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
export const rotY = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];
export const rotZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];
const DEG = Math.PI / 180;

/** Le soleil de fin d'après-midi : il vient de l'ouest-sud-ouest, assez bas, et jaunit tout. */
const SOLEIL = norme([-0.55, 0.78, 0.36]);
const CHAUD = [1.07, 0.99, 0.86];
const OMBRE = [0.6, 0.63, 0.78];

// ------------------------------------------------------------------ les textures 16×16
const T = 16;
function texture(f) {
  const px = [];
  for (let y = 0; y < T; y++) for (let x = 0; x < T; x++) px.push(f(x, y).map((v) => Math.max(0, Math.min(255, v))));
  return px;
}
const bruit = (c, x, y, s, f = 0.1) => c.map((v) => v * (1 - f + 2 * f * hache(x, y, s)));
const ton = (c, k) => c.map((v) => v * k);

/** Des pierres arrondies avec des joints sombres (pavés, cailloux). */
function cailloux(base, s, taille = 5) {
  const graines = [];
  for (let k = 0; k < 9; k++) graines.push([hache(k, 1, s) * 16, hache(k, 2, s) * 16, 0.85 + 0.3 * hache(k, 3, s)]);
  return texture((x, y) => {
    let d1 = 99, d2 = 99, k1 = 0;
    for (const [gx, gy, k] of graines) for (const ox of [-16, 0, 16]) for (const oy of [-16, 0, 16]) {
      const d = Math.hypot(x + 0.5 - gx - ox, y + 0.5 - gy - oy);
      if (d < d1) { d2 = d1; d1 = d; k1 = k; } else if (d < d2) d2 = d;
    }
    if (d2 - d1 < 1.1) return bruit(ton(base, 0.55), x, y, s);
    return bruit(ton(base, k1 * (1.08 - 0.05 * d1 / taille)), x, y, s, 0.06);
  });
}
/** Des briques : hauteur h, longueur l, joints clairs ou sombres. */
const briques = (base, joint, s, h = 4, l = 8) => texture((x, y) => {
  const rang = Math.floor(y / h), dx = (x + (rang % 2) * (l / 2)) % l;
  if (y % h === h - 1 || dx === 0) return bruit(joint, x, y, s, 0.04);
  return bruit(ton(base, 0.92 + 0.16 * hache(rang, Math.floor((x + (rang % 2) * (l / 2)) / l), s)), x, y, s, 0.05);
});
/** Des planches horizontales de 4 de haut, avec le fil du bois. */
const planches = (base, s) => texture((x, y) => {
  const rang = Math.floor(y / 4), bout = (x + rang * 5) % 16 === 0;
  if (y % 4 === 3 || bout) return ton(base, 0.62);
  const fil = 1 + 0.07 * Math.sin(x * 0.7 + rang * 3 + 2 * Math.sin(x * 0.3 + rang));
  return bruit(ton(base, fil * (0.95 + 0.1 * hache(rang, 0, s))), x, y, s, 0.04);
});
/** Un crépi peint (les maisons de la favela), avec des coulures et la saleté en bas. */
const crepi = (base, s) => texture((x, y) => {
  let c = bruit(base, x, y, s, 0.05);
  if (hache(x, 0, s + 1) < 0.18 && y > 4 + 8 * hache(x, 1, s)) c = ton(c, 0.9);
  if (y > 12) c = ton(c, 1 - (y - 12) * 0.03);
  return c;
});
const uni = (base, s, f = 0.06) => texture((x, y) => bruit(base, x, y, s, f));

const TEX = {};
function def(nom, cote, haut = cote, bas = haut) { TEX[nom] = { cote, haut, bas }; }
{
  const TERRE = [124, 88, 60];
  const terre = texture((x, y) => (hache(x, y, 5) < 0.15 ? ton(TERRE, 0.72) : hache(x, y, 6) < 0.1 ? ton(TERRE, 1.2) : bruit(TERRE, x, y, 4, 0.06)));
  def("terre", terre);
  def("terreSeche", uni([170, 128, 84], 8, 0.08), texture((x, y) => (hache(x, y, 9) < 0.08 ? [120, 92, 64] : hache(x, y, 10) < 0.06 ? [214, 190, 140] : bruit([176, 134, 88], x, y, 11, 0.07))));
  const herbe = texture((x, y) => bruit(hache(x, y, 12) < 0.3 ? [128, 150, 62] : [108, 140, 54], x, y, 13, 0.08));
  def("herbe", texture((x, y) => (y < 3 + Math.floor(2 * hache(x, 0, 14)) ? bruit([112, 142, 56], x, y, 15, 0.07) : terre[y * 16 + x])), herbe, terre);
  def("sable", uni([222, 204, 156], 16, 0.06));
  def("sableRouge", uni([190, 108, 52], 17, 0.07));
  def("chemin", terre, texture((x, y) => bruit(hache(x >> 1, y >> 1, 18) < 0.2 ? [128, 100, 62] : [150, 120, 72], x, y, 19, 0.06)));
  def("pave", cailloux([150, 140, 126], 20));
  def("pavesClairs", cailloux([196, 176, 146], 21, 4));
  def("pierre", uni([124, 124, 126], 22, 0.08));
  def("pierreTaillee", briques([138, 136, 134], [92, 90, 90], 23, 8, 16));
  def("blanc", crepi([236, 230, 216], 24));
  def("creme", crepi([232, 212, 168], 25));
  const tuiles = texture((x, y) => {
    const rang = Math.floor(y / 4), dx = (x + (rang % 2) * 2) % 4, v = y % 4;
    const k = [1.12, 1.02, 0.92, 0.7][v] * (dx === 0 ? 0.82 : 1) * (0.94 + 0.12 * hache(rang, Math.floor((x + (rang % 2) * 2) / 4), 26));
    return bruit(ton([184, 90, 48], k), x, y, 27, 0.04);
  });
  def("tuile", tuiles, tuiles, tuiles);
  def("brique", briques([150, 72, 54], [184, 170, 150], 28));
  def("briqueNue", briques([168, 96, 62], [120, 110, 100], 29, 4, 6));
  for (const [nom, c, s] of [["rose", [226, 142, 166], 30], ["jaune", [238, 198, 84], 31], ["bleu", [104, 164, 212], 32], ["vert", [128, 186, 116], 33],
    ["orange", [228, 134, 64], 34], ["turquoise", [82, 182, 172], 35], ["beige", [214, 184, 142], 36], ["lilas", [168, 140, 206], 37], ["rouge", [196, 70, 60], 38]]) def(nom, crepi(c, s), uni(ton(c, 0.85), s + 50));
  def("bois", planches([166, 122, 72], 40));
  def("boisFonce", planches([84, 56, 34], 41));
  def("boisRouge", planches([128, 58, 40], 42));
  def("tronc", texture((x, y) => bruit(ton([150, 116, 74], y % 3 === 0 ? 0.72 : 1 + 0.06 * Math.sin(x)), x, y, 43, 0.06)),
    texture((x, y) => { const d = Math.hypot(x - 7.5, y - 7.5); return ton([170, 136, 90], d > 6.5 ? 0.6 : 0.9 + 0.12 * Math.sin(d * 2)); }));
  def("palme", texture((x, y) => bruit(hache(x, y, 44) < 0.25 ? [84, 150, 54] : hache(x, y, 45) < 0.15 ? [44, 96, 36] : [64, 128, 44], x, y, 46, 0.08)));
  def("sacSable", texture((x, y) => {
    const rang = Math.floor(y / 5), dx = (x + (rang % 2) * 4) % 8;
    if (y % 5 === 4) return [118, 102, 72];
    const bord = dx === 0 || dx === 7 ? 0.82 : 1;
    return bruit(ton([196, 176, 128], bord * (1.05 - 0.06 * (y % 5))), x, y, 47, 0.06);
  }));
  def("caisse", texture((x, y) => {
    const cadre = x < 2 || x > 13 || y < 2 || y > 13 || Math.abs(x - y) < 1.2;
    const lettres = !cadre && y >= 6 && y <= 9 && x >= 4 && x <= 11 && (x + y) % 3 !== 0 && hache(x, y, 48) < 0.55;
    if (lettres) return [214, 200, 120];
    return bruit(ton([90, 102, 58], cadre ? 0.78 : 1 + 0.06 * Math.sin(y * 1.6)), x, y, 49, 0.05);
  }), texture((x, y) => bruit(ton([96, 108, 62], x % 4 === 3 ? 0.7 : 1), x, y, 50, 0.05)));
  def("tonneau", texture((x, y) => bruit(ton([128, 82, 46], y === 2 || y === 13 ? 0.45 : x % 4 === 0 ? 0.8 : 1), x, y, 51, 0.05)),
    texture((x, y) => bruit(ton([140, 96, 56], Math.hypot(x - 7.5, y - 7.5) > 6.5 ? 0.5 : y % 4 === 0 ? 0.8 : 1), x, y, 52, 0.05)));
  def("bidon", texture((x, y) => bruit(ton([170, 44, 36], y === 3 || y === 12 ? 0.62 : 1 + 0.08 * Math.sin(x * 0.8)), x, y, 53, 0.05)),
    texture((x, y) => ton([120, 124, 130], Math.hypot(x - 7.5, y - 7.5) > 6.5 ? 0.6 : Math.hypot(x - 11, y - 5) < 1.5 ? 0.4 : 1)));
  const orT = texture((x, y) => {
    const bord = x === 0 || y === 0 ? 1.25 : x === 15 || y === 15 ? 0.72 : 1;
    const reflet = Math.abs(x - y - 3) < 2 ? 1.18 : 1;
    return bruit(ton([246, 196, 58], bord * reflet), x, y, 54, 0.05);
  });
  def("or", orT, orT, orT);
  def("vitre", texture((x, y) => (x === 0 || y === 0 || x === 15 || y === 15 || x === 7 || y === 7 ? [226, 222, 210] : Math.abs(x - y + 4) < 1.5 ? [120, 150, 170] : [44, 62, 82])));
  def("vitreArc", texture((x, y) => (x === 0 || x === 15 || y === 15 ? [226, 222, 210] : Math.abs(x - y + 4) < 1.5 ? [120, 150, 170] : [40, 56, 74])));
  def("volet", texture((x, y) => bruit(ton([60, 120, 86], y % 3 === 0 ? 0.7 : 1), x, y, 55, 0.05)));
  def("porte", texture((x, y) => {
    if (x === 0 || x === 15 || y === 0) return [212, 168, 60];
    if ((x === 7 || x === 8) && y > 1) return [52, 32, 20];
    if (y > 7 && y < 10 && (x === 5 || x === 10)) return [240, 200, 80];
    return bruit(ton([96, 58, 32], 1 + 0.06 * Math.sin(y * 1.2)), x, y, 56, 0.04);
  }));
  def("fer", uni([170, 172, 176], 57, 0.04));
  def("tole", texture((x, y) => bruit(ton([150, 154, 158], x % 4 === 0 ? 0.7 : x % 4 === 2 ? 1.12 : 1).map((v, i) => (hache(x >> 1, y >> 2, 58) < 0.1 ? [150, 90, 50][i] : v)), x, y, 59, 0.04)));
  def("beton", uni([158, 156, 150], 60, 0.05));
  def("noir", uni([30, 30, 34], 61, 0.05));
  def("carrosserie", texture((x, y) => ton([28, 28, 32], y < 3 ? 1.6 : 1)));
  def("chrome", texture((x, y) => ton([200, 204, 210], 0.8 + 0.4 * ((x + y) % 5 === 0 ? 1 : 0.4))));
  def("cible", texture((x, y) => { const d = Math.max(Math.abs(x - 7.5), Math.abs(y - 7.5)); return d < 1.6 ? [200, 30, 30] : d < 3.6 ? [236, 230, 220] : d < 5.6 ? [200, 30, 30] : [236, 230, 220]; }),
    texture((x, y) => bruit(hache(x, y, 62) < 0.2 ? [180, 150, 70] : [214, 186, 92], x, y, 63, 0.05)));
  def("foin", texture((x, y) => (y === 4 || y === 11 ? [150, 40, 30] : bruit(ton([206, 172, 64], 1 + 0.1 * Math.sin(x * 2.1 + y)), x, y, 64, 0.06))),
    texture((x, y) => bruit(ton([214, 184, 80], 1 + 0.12 * Math.sin(Math.hypot(x - 8, y - 8) * 2)), x, y, 65, 0.05)));
  for (const [nom, c] of [["laineRouge", [176, 40, 38]], ["laineVerte", [52, 120, 60]], ["laineBlanche", [232, 230, 222]], ["laineJaune", [236, 196, 50]], ["laineBleue", [52, 80, 170]], ["laineNoire", [30, 28, 32]]])
    def(nom, texture((x, y) => bruit(ton(c, 1 + 0.05 * Math.sin(x * 1.3 + y * 0.7)), x, y, 66, 0.05)));
  def("marbre", texture((x, y) => { const v = Math.sin(x * 0.5 + y * 0.9 + 3 * Math.sin(y * 0.3 + x * 0.1)); return bruit(ton([236, 234, 228], Math.abs(v) < 0.08 ? 0.78 : 1), x, y, 67, 0.02); }));
  def("marbreNoir", texture((x, y) => { const v = Math.sin(x * 0.6 - y * 0.8 + 3 * Math.sin(y * 0.25)); return bruit(Math.abs(v) < 0.1 ? [160, 140, 90] : [34, 32, 34], x, y, 68, 0.03); }));
  def("tapis", texture((x, y) => (x < 2 || x > 13 ? [212, 168, 60] : bruit([160, 24, 32], x, y, 69, 0.05))));
  def("lanterne", texture((x, y) => (x === 0 || x === 15 || y === 0 || y === 15 ? [60, 50, 40] : [255, 214, 120])));
  def("eau", texture((x, y) => bruit(ton([64, 128, 210], 1 + 0.12 * Math.sin(x * 0.8 + y * 0.4)), x, y, 70, 0.04)));
  def("liasses", texture((x, y) => (y % 4 === 0 ? [214, 220, 196] : x === 7 || x === 8 ? [222, 214, 150] : bruit([96, 150, 92], x, y, 71, 0.06))));
  def("cuve", texture((x, y) => bruit(ton([44, 92, 170], x % 5 === 0 ? 0.8 : 1), x, y, 72, 0.04)), uni([60, 110, 190], 73));
  def("graffiti", texture((x, y) => {
    const b = bruit([166, 162, 154], x, y, 74, 0.05);
    const t1 = Math.abs(y - 8 - 3 * Math.sin(x * 0.7)) < 1.2, t2 = Math.abs(y - 4 - 2 * Math.cos(x * 0.9 + 1)) < 0.9;
    return t1 ? [228, 52, 140] : t2 ? [60, 200, 220] : hache(x >> 2, y >> 2, 75) < 0.12 ? [246, 214, 40] : b;
  }));
  def("rail", uni([90, 70, 50], 76));
  def("cactus", texture((x, y) => bruit(ton([70, 128, 52], x % 4 === 0 ? 0.7 : x % 4 === 2 ? 1.12 : 1), x, y, 77, 0.05).map((v, i) => ((x % 4 === 0) && y % 5 === 2 ? [230, 220, 170][i] : v))),
    texture((x, y) => bruit([80, 140, 60], x, y, 78, 0.06)));
  for (const [nom, c] of [["conteneurRouge", [168, 52, 40]], ["conteneurBleu", [44, 88, 150]], ["conteneurVert", [56, 112, 72]]])
    def(nom, texture((x, y) => bruit(ton(c, x % 3 === 0 ? 0.72 : x % 3 === 1 ? 1.1 : 1).map((v, i) => (hache(x, y >> 1, 79) < 0.06 ? [130, 80, 50][i] : v)), x, y, 80, 0.04)), uni(ton(c, 0.9), 81));
  def("bitume", uni([66, 64, 62], 82, 0.08));
}

/** Les textures réduites (moyenne par case) pour n cases par face. */
const RED = new Map();
function reduite(nom, face, n) {
  const cle = nom + face + n;
  if (RED.has(cle)) return RED.get(cle);
  const src = TEX[nom]?.[face === "haut" ? "haut" : face === "bas" ? "bas" : "cote"] ?? TEX.pierre.cote, k = T / n, r = [];
  for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) {
    const s = [0, 0, 0];
    for (let y = 0; y < k; y++) for (let x = 0; x < k; x++) { const c = src[(j * k + y) * T + i * k + x]; s[0] += c[0]; s[1] += c[1]; s[2] += c[2]; }
    r.push(s.map((v) => v / (k * k)));
  }
  RED.set(cle, r);
  return r;
}

// ------------------------------------------------------------------ le monde
export class Monde {
  constructor([x0, x1], [y0, y1], [z0, z1]) {
    Object.assign(this, { x0, y0, z0, sx: x1 - x0 + 1, sy: y1 - y0 + 1, sz: z1 - z0 + 1 });
    this.g = new Uint8Array(this.sx * this.sy * this.sz);
    this.porteOmbre = new Uint8Array(this.sx * this.sy * this.sz);
    this.noms = ["air"]; this.ids = { air: 0 };
    this.boites = []; this.persos = []; this.objets = []; this.lignes = []; this.peintures = []; this.etiquettes = [];
  }
  i(x, y, z) {
    x -= this.x0; y -= this.y0; z -= this.z0;
    return x < 0 || y < 0 || z < 0 || x >= this.sx || y >= this.sy || z >= this.sz ? -1 : (x * this.sy + y) * this.sz + z;
  }
  set(x, y, z, nom) {
    const i = this.i(x, y, z);
    if (i < 0) return;
    if (nom === null || nom === "air") { this.g[i] = 0; return; }
    if (!(nom in this.ids)) { this.ids[nom] = this.noms.length; this.noms.push(nom); }
    this.g[i] = this.ids[nom];
  }
  get(x, y, z) { const i = this.i(x, y, z); return i < 0 ? null : this.g[i] ? this.noms[this.g[i]] : null; }
  plein(x, y, z) { const i = this.i(x, y, z); return i >= 0 && this.g[i] > 0; }
  remplir(x0, y0, z0, x1, y1, z1, nom) {
    for (let x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) for (let y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
      for (let z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) this.set(x, y, z, typeof nom === "function" ? nom(x, y, z) : nom);
  }
  /** Un pavé libre en coordonnées de blocs (auvent, plateau de table, carrosserie), avec la texture d'un bloc. */
  boite(a, b, nom, o = {}) {
    this.boites.push({ a, b, nom, ...o });
    if (o.ombre !== false) {
      for (let x = Math.floor(a[0]); x < Math.ceil(b[0]); x++) for (let y = Math.floor(a[1]); y < Math.ceil(b[1]); y++)
        for (let z = Math.floor(a[2]); z < Math.ceil(b[2]); z++) { const i = this.i(x, y, z); if (i >= 0) this.porteOmbre[i] = 1; }
    }
  }
  perso(o) { this.persos.push(o); }
  /** Est-ce que ce point (en blocs) est à l'ombre du soleil ? */
  aLOmbre(p, clip) {
    let [x, y, z] = p;
    for (let k = 0; k < 64; k++) {
      x += SOLEIL[0] * 0.5; y += SOLEIL[1] * 0.5; z += SOLEIL[2] * 0.5;
      const bx = Math.floor(x), by = Math.floor(y), bz = Math.floor(z), i = this.i(bx, by, bz);
      if (by > this.y0 + this.sy) return false;
      if (i >= 0 && (this.g[i] || this.porteOmbre[i]) && (!clip || clip(bx, by, bz))) return true;
    }
    // les persos font une petite ombre ronde à leurs pieds, un peu décalée à l'opposé du soleil
    if (!this.pieds) {
      this.pieds = new Map();
      for (const pe of this.persos) if (pe.ombre !== false) {
        const cle = Math.floor(pe.x) + "," + Math.round(pe.y) + "," + Math.floor(pe.z);
        this.pieds.set(cle, (this.pieds.get(cle) ?? []).concat([pe]));
      }
    }
    if (Math.abs(p[1] - Math.round(p[1])) > 0.05) return false;
    for (const [ox, oz] of [[0, 0], [-1, 0], [0, -1], [-1, -1], [1, 0], [0, 1], [1, 1], [-1, 1], [1, -1]]) {
      for (const pe of this.pieds.get(Math.floor(p[0]) + ox + "," + Math.round(p[1]) + "," + (Math.floor(p[2]) + oz)) ?? []) {
        const dx = p[0] - pe.x - 0.3, dz = p[2] - pe.z + 0.2;
        if (dx * dx + dz * dz < 0.2) return true;
      }
    }
    return false;
  }
}

const FACES = [
  { nom: "est", N: [1, 0, 0], O: [1, 1, 1], U: [0, 0, -1], V: [0, -1, 0] },
  { nom: "ouest", N: [-1, 0, 0], O: [0, 1, 0], U: [0, 0, 1], V: [0, -1, 0] },
  { nom: "sud", N: [0, 0, 1], O: [0, 1, 1], U: [1, 0, 0], V: [0, -1, 0] },
  { nom: "nord", N: [0, 0, -1], O: [1, 1, 0], U: [-1, 0, 0], V: [0, -1, 0] },
  { nom: "haut", N: [0, 1, 0], O: [0, 1, 0], U: [1, 0, 0], V: [0, 0, 1] },
  { nom: "bas", N: [0, -1, 0], O: [0, 0, 1], U: [1, 0, 0], V: [0, 0, -1] },
];

/** La lumière d'une face : le soleil, et la teinte chaude ou l'ombre bleutée. */
function eclairage(N, aLOmbre) {
  const d = Math.max(0, N[0] * SOLEIL[0] + N[1] * SOLEIL[1] + N[2] * SOLEIL[2]);
  const base = N[1] < 0 ? 0.5 : 0.6 + 0.46 * d;
  return (aLOmbre || d === 0 ? OMBRE.map((v) => v * (N[1] > 0 ? 1 : 0.92) * (0.6 + 0.4 * (base / 1.06))) : CHAUD.map((v) => v * base));
}

/** Le monde dans une scène : n cases par face ; clip(x, y, z) limite ce qu'on dessine (vue en coupe). */
export function rendreMonde(sc, m, { n = 4, clip = null } = {}) {
  const dedans = (x, y, z) => m.plein(x, y, z) && (!clip || clip(x, y, z));
  for (let xi = 0; xi < m.sx; xi++) for (let yi = 0; yi < m.sy; yi++) for (let zi = 0; zi < m.sz; zi++) {
    const id = m.g[(xi * m.sy + yi) * m.sz + zi];
    if (!id) continue;
    const x = xi + m.x0, y = yi + m.y0, z = zi + m.z0;
    if (clip && !clip(x, y, z)) continue;
    const nom = m.noms[id];
    for (const f of FACES) {
      const q = [x + f.N[0], y + f.N[1], z + f.N[2]];
      if (dedans(...q)) continue;
      if (sc.lumiere(f.N, false) < 0) continue;
      const tex = reduite(nom, f.nom === "haut" ? "haut" : f.nom === "bas" ? "bas" : "cote", n);
      // l'occlusion : les blocs qui touchent l'arête ou le coin de la face assombrissent ce côté
      const voisin = (du, dv) => dedans(q[0] + f.U[0] * du + f.V[0] * dv, q[1] + f.U[1] * du + f.V[1] * dv, q[2] + f.U[2] * du + f.V[2] * dv) ? 1 : 0;
      const g = voisin(-1, 0), d = voisin(1, 0), h = voisin(0, -1), b = voisin(0, 1);
      const cs = [[voisin(-1, -1) && !g && !h, 0, 0], [voisin(1, -1) && !d && !h, 1, 0], [voisin(-1, 1) && !g && !b, 0, 1], [voisin(1, 1) && !d && !b, 1, 1]];
      const O = [x + f.O[0], y + f.O[1], z + f.O[2]];
      for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) {
        const u = (i + 0.5) / n, v = (j + 0.5) / n;
        let occ = g * Math.max(0, 1 - u / 0.5) + d * Math.max(0, 1 - (1 - u) / 0.5) + h * Math.max(0, 1 - v / 0.5) + b * Math.max(0, 1 - (1 - v) / 0.5);
        for (const [c, cu, cv] of cs) if (c) occ += 0.8 * Math.max(0, 1 - Math.hypot(u - cu, v - cv) / 0.5);
        const ao = 1 - Math.min(0.5, occ * 0.3);
        const centre = [O[0] + f.U[0] * u + f.V[0] * v + f.N[0] * 0.02, O[1] + f.U[1] * u + f.V[1] * v + f.N[1] * 0.02, O[2] + f.U[2] * u + f.V[2] * v + f.N[2] * 0.02];
        let c = tex[j * n + i];
        for (const pe of m.peintures) { const r = pe(x, y, z, f.nom, u, v); if (r) { c = r; break; } }
        const lum = nom === "lanterne" ? [1.25, 1.2, 1.1] : eclairage(f.N, m.aLOmbre(centre, clip));
        const p = (a, bb) => fois([O[0] + f.U[0] * a + f.V[0] * bb, O[1] + f.U[1] * a + f.V[1] * bb, O[2] + f.U[2] * a + f.V[2] * bb], 16);
        sc.quad([p(i / n, j / n), p((i + 1) / n, j / n), p((i + 1) / n, (j + 1) / n), p(i / n, (j + 1) / n)], c.map((w, k) => w * lum[k] * ao));
      }
    }
  }
  for (const bo of m.boites) if (!clip || clip(Math.floor(bo.a[0]), Math.floor(bo.a[1]), Math.floor(bo.a[2]))) boiteTexturee(sc, m, bo, clip, n);
}

/** Un pavé libre texturé, éclairé comme les blocs. */
function boiteTexturee(sc, m, { a, b, nom, couleur }, clip, n) {
  for (const f of FACES) {
    if (sc.lumiere(f.N, false) < 0) continue;
    const taille = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    const O = [0, 1, 2].map((k) => a[k] + f.O[k] * taille[k]);
    const U = f.U.map((v, k) => v * taille[k]), V = f.V.map((v, k) => v * taille[k]);
    const nu = Math.max(1, Math.round(Math.hypot(...U) * n)), nv = Math.max(1, Math.round(Math.hypot(...V) * n));
    const tex = reduite(nom, f.nom === "haut" ? "haut" : f.nom === "bas" ? "bas" : "cote", n);
    for (let j = 0; j < nv; j++) for (let i = 0; i < nu; i++) {
      const pt = (s, t) => [O[0] + (U[0] * s) / nu + (V[0] * t) / nv, O[1] + (U[1] * s) / nu + (V[1] * t) / nv, O[2] + (U[2] * s) / nu + (V[2] * t) / nv];
      const centre = ajoute(pt(i + 0.5, j + 0.5), fois(f.N, 0.03));
      const c = couleur ? couleur(f.nom, i, j) : tex[(j % n) * n + (i % n)];
      const lum = nom === "lanterne" ? [1.25, 1.2, 1.1] : eclairage(f.N, m.aLOmbre(centre, clip));
      sc.quad([pt(i, j), pt(i + 1, j), pt(i + 1, j + 1), pt(i, j + 1)].map((p) => fois(p, 16)), c.map((w, k) => w * lum[k]), 0.01);
    }
  }
}

// ------------------------------------------------------------------ les persos
/**
 * Comment on tient chaque arme : main (pixel de la poignée, main droite), appui (pixel où va la main gauche, null si
 * une seule main), echelle (unités par pixel d'icône : 64 pixels × 0,42 ≈ 1,7 bloc pour la Kalach), type :
 * "main" (à une main), "fusil" (à deux mains, à l'épaule), "epaule" (le bazooka posé sur l'épaule), "hanche" (le minigun).
 */
export const EN_MAIN = {
  ak47: { main: [26, 37], appui: [45, 32], echelle: 0.44, type: "fusil" },
  ak47_or: { main: [26, 37], appui: [45, 32], echelle: 0.44, type: "fusil" },
  thompson: { main: [23, 36], appui: [44, 35], echelle: 0.4, type: "fusil" },
  barrett: { main: [25, 38], appui: [40, 32], echelle: 0.48, type: "fusil" },
  m60: { main: [23, 37], appui: [44, 32], echelle: 0.48, type: "fusil" },
  m79: { main: [20, 33], appui: [39, 37], echelle: 0.42, type: "fusil" },
  canon_scie: { main: [17, 39], appui: [38, 35], echelle: 0.36, type: "fusil" },
  uzi: { main: [30, 38], appui: [42, 34], echelle: 0.3, type: "fusil" },
  rpg7: { main: [22, 39], appui: [34, 37], echelle: 0.56, type: "epaule" },
  minigun: { main: [15, 17], appui: [27, 22], echelle: 0.5, type: "hanche" },
  mac10: { main: [29, 38], appui: null, echelle: 0.3, type: "main" },
  desert_eagle_or: { main: [19, 40], appui: null, echelle: 0.26, type: "main" },
  machette: { main: [9, 33], appui: null, echelle: 0.32, type: "main" },
  grenade: { main: [30, 44], appui: null, echelle: 0.18, type: "main" },
  molotov: { main: [32, 48], appui: null, echelle: 0.2, type: "main" },
};

const EPAULE = { brasD: [-5, 22, 0], brasG: [5, 22, 0] };
/** Les angles d'un bras (rx, rz, ry) pour que sa main pointe vers la cible (en coordonnées du corps). */
function versCible(nom, cible) {
  const d = norme(sous(cible, EPAULE[nom]));
  return [-Math.acos(Math.max(-1, Math.min(1, -d[1]))), 0, Math.atan2(d[0], d[2])];
}
/** Où est l'arme, dans le repère du corps : la poignée, l'axe avant (vers la bouche) et l'axe haut de l'icône. */
function placement(type, pose) {
  if (type === "epaule") return { G: [-4.6, 20.4, 1.2], F: pose === "vise" ? [0, 0, 1] : norme([0, 0.18, 1]), U: [0, 1, 0] };
  if (type === "hanche") return { G: [-3.4, 19, 0.6], F: [0, 0, 1], U: [0, 1, 0] };
  if (type === "fusil" && pose === "vise") return { G: [-2.2, 19.6, 3.2], F: [0, 0, 1], U: [0, 1, 0] };
  if (type === "fusil") {
    // en travers du torse, la bouche vers le haut à gauche, comme une patrouille
    const F = norme([0.62, 0.52, 0.58]);
    return { G: [-3.4, 14.5, 4.6], F, U: norme(sous([0, 1, 0], fois(F, F[1]))) };
  }
  if (pose === "pistolet") return { G: [-6, 21.2, 9.2], F: [0, 0, 1], U: [0, 1, 0] };
  return { G: [-6, 12.6, 1.4], F: norme([0, -0.35, 1]), U: norme([0, 1, 0.35]) };
}

/**
 * Un perso (en blocs : x, y, z aux pieds ; lacet en degrés, 0 = il regarde vers +z). pose : "vise" (l'arme en joue),
 * "porte" (l'arme en travers), "repos" (bras le long du corps), "croise" (bras croisés), "pistolet" (bras tendu).
 * arme : un id d'icône. Les armes à deux mains placent les deux bras d'elles-mêmes. Rend la bouche du canon (en unités)
 * quand il vise, sinon null.
 */
export function dessinerPerso(sc, m, pe, icones) {
  const peau = pe.skin, lacet = (pe.lacet ?? 0) * DEG, base = fois([pe.x, pe.y, pe.z], 16);
  const poses = {
    vise: { brasD: [-88 * DEG, 0, -6 * DEG], brasG: [-84 * DEG, 0, 38 * DEG], tete: [4 * DEG, 0] },
    porte: { brasD: [-38 * DEG, 0, -10 * DEG], brasG: [-58 * DEG, 0, 44 * DEG], tete: [0, 0] },
    repos: { brasD: [0, 4 * DEG, 0], brasG: [0, -4 * DEG, 0], tete: [0, 0] },
    croise: { brasD: [-62 * DEG, 0, 46 * DEG], brasG: [-62 * DEG, 0, -46 * DEG], tete: [-6 * DEG, 0] },
    tend: { brasD: [-70 * DEG, 0, 0], brasG: [0, -4 * DEG, 0], tete: [0, 0] },
    pistolet: { brasD: [-90 * DEG, 0, 0], brasG: [0, -4 * DEG, 0], tete: [0, 0] },
  };
  const P = { ...poses[pe.pose ?? "repos"] };
  // l'arme d'abord : elle décide où vont les mains
  const prise = pe.arme ? EN_MAIN[pe.arme] : null;
  let pt = null;
  if (prise) {
    const pose = pe.pose === "vise" || pe.pose === "pistolet" ? pe.pose : "porte";
    const { G, F, U } = placement(prise.type, pose);
    const k = pe.echelleArme ?? prise.echelle, gauche = norme(croix(U, F));
    const local = (ix, iy, e) => ajoute(ajoute(G, fois(F, (ix - prise.main[0]) * k)), ajoute(fois(U, -(iy - prise.main[1]) * k), fois(gauche, e)));
    pt = local;
    if (prise.type !== "main") P.brasD = versCible("brasD", ajoute(G, [0, -0.6, 0]));
    if (prise.appui) P.brasG = versCible("brasG", local(prise.appui[0], prise.appui[1] + 1.5, 0));
    if (prise.type === "main" && pose === "porte") P.brasD = [-18 * DEG, 0, 0];
    pe._axes = { F, U, gauche, k };
  }
  const monde = (v) => ajoute(rotY(v, lacet), base);
  const piv = { brasD: EPAULE.brasD, brasG: EPAULE.brasG, tete: [0, 24, 0] };
  const local = (nom) => {
    if (nom === "brasD" || nom === "brasG") { const [rx, rz, ry] = P[nom]; return { p: (v) => ajoute(rotY(rotX(rotZ(sous(v, piv[nom]), rz), rx), ry), piv[nom]), n: (v) => rotY(rotX(rotZ(v, rz), rx), ry) }; }
    if (nom === "tete") { const [rx, ry] = P.tete; return { p: (v) => ajoute(rotY(rotX(sous(v, piv.tete), rx), ry + (pe.teteLacet ?? 0) * DEG), piv.tete), n: (v) => rotY(rotX(v, rx), ry + (pe.teteLacet ?? 0) * DEG) }; }
    return { p: (v) => v, n: (v) => v };
  };
  const ombre = m && m.aLOmbre([pe.x + 0.0, pe.y + 1.2, pe.z], null);
  for (const [nom, boite, uv1, uv2, gonfle] of PARTIES(false)) {
    const L = local(nom);
    for (const [uv, g, d] of [[uv1, 0, 0], [uv2, gonfle, 0.02]]) for (const t of texels(boite, uv, g, 1)) {
      const k = (t.ty * 64 + t.tx) * 4;
      if (peau.px[k + 3] < 128) continue;
      const N = rotY(L.n(t.N), lacet);
      if (sc.lumiere(N, false) < 0) continue;
      const lum = eclairage(N, ombre);
      sc.quad(t.coins.map((v) => monde(L.p(v))), [peau.px[k], peau.px[k + 1], peau.px[k + 2]].map((w, q) => w * lum[q] * 1.04), d);
    }
  }
  if (!prise) return null;
  const ic = icones[pe.arme];
  // l'arme : une plaque de l'épaisseur d'un pixel, dans le plan vertical, la bouche vers l'avant
  const enMonde = (ix, iy, e) => monde(pt(ix, iy, e));
  const ax = pe._axes;
  plaque(sc, ic, enMonde, ax.k, { A: rotY(ax.F, lacet), H: rotY(ax.U, lacet), G: rotY(ax.gauche, lacet) }, ombre);
  if (pe.pose !== "vise" && pe.pose !== "pistolet") return null;
  // la bouche du canon : le pixel plein le plus à droite
  let bx = 0, by = 32;
  for (let x = 63; x >= 0 && !bx; x--) for (let y = 0; y < 64; y++) if (ic.px[(y * 64 + x) * 4 + 3] > 200) { bx = x; by = y; break; }
  return enMonde(bx + 1, by + 1.5, 0);
}

/**
 * L'arme en 3D : chaque pixel de l'icône devient une colonne de son épaisseur (ic.prof), centrée sur le plan de
 * l'arme ; on dessine la face avant ou arrière, et les marches là où le voisin est moins épais (canons ronds,
 * carcasse en boîte, chargeur plus fin). pt(ix, iy, e) → point ; unite : la taille d'un pixel ; axes A (vers la
 * bouche), H (vers le haut de l'icône), G (l'épaisseur), déjà dans le monde.
 */
export function plaque(sc, ic, pt, unite, { A, H, G }, ombre = false) {
  const prof = (x, y) => (x >= 0 && y >= 0 && x < 64 && y < 64 ? ic.prof[y * 64 + x] : 0);
  const voir = (n) => sc.lumiere(n, false) >= 0;
  const faces = [
    { n: G, ok: voir(G) }, { n: fois(G, -1), ok: voir(fois(G, -1)) },
    { n: A, ok: voir(A), dx: 1, dy: 0, a: (x, y) => [x + 1, y], b: (x, y) => [x + 1, y + 1] },
    { n: fois(A, -1), ok: voir(fois(A, -1)), dx: -1, dy: 0, a: (x, y) => [x, y + 1], b: (x, y) => [x, y] },
    { n: H, ok: voir(H), dx: 0, dy: -1, a: (x, y) => [x, y], b: (x, y) => [x + 1, y] },
    { n: fois(H, -1), ok: voir(fois(H, -1)), dx: 0, dy: 1, a: (x, y) => [x + 1, y + 1], b: (x, y) => [x, y + 1] },
  ].map((f) => ({ ...f, lum: eclairage(f.n, ombre) }));
  for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) {
    const d = prof(x, y);
    if (!d) continue;
    const o = (y * 64 + x) * 4, c = [ic.px[o], ic.px[o + 1], ic.px[o + 2]], h = (d / 2) * unite;
    // la face qu'on voit (avant ou arrière)
    const f = faces[0].ok ? faces[0] : faces[1], e = faces[0].ok ? h : -h;
    sc.quad([pt(x, y, e), pt(x + 1, y, e), pt(x + 1, y + 1, e), pt(x, y + 1, e)], c.map((w, k) => w * f.lum[k] * 1.05), 0.03);
    // les marches sur les côtés
    for (const s of faces.slice(2)) {
      if (!s.ok) continue;
      const dn = prof(x + s.dx, y + s.dy);
      if (dn >= d) continue;
      const hn = (dn / 2) * unite, A2 = s.a(x, y), B2 = s.b(x, y), col = c.map((w, k) => w * s.lum[k] * 0.9);
      if (dn === 0) sc.quad([pt(...A2, h), pt(...B2, h), pt(...B2, -h), pt(...A2, -h)], col, 0.02);
      else {
        sc.quad([pt(...A2, h), pt(...B2, h), pt(...B2, hn), pt(...A2, hn)], col, 0.02);
        sc.quad([pt(...A2, -hn), pt(...B2, -hn), pt(...B2, -h), pt(...A2, -h)], col, 0.02);
      }
    }
  }
}

/** Une arme posée (sur une table, accrochée au mur) : centre en blocs, axes avant/haut de l'icône. */
export function armePosee(sc, m, ic, centre, avant, haut, taille = 1) {
  const A = norme(avant), Hh = norme(sous(haut, fois(A, A[0] * haut[0] + A[1] * haut[1] + A[2] * haut[2]))), G = norme(croix(Hh, A));
  const k = taille / 64;
  const pt = (ix, iy, e) => fois(ajoute(ajoute(centre, fois(A, (ix - 32) * k)), ajoute(fois(Hh, -(iy - 32) * k), fois(G, e / 16))), 16);
  plaque(sc, ic, pt, taille / 4, { A, H: Hh, G }, m.aLOmbre(centre, null));
}

// ------------------------------------------------------------------ les effets, par-dessus l'image
/** Un trait de lumière entre deux points (en unités) : traçante, guirlande. */
export function trait2d(sc, a, b, couleur, e = 1, alpha = 1) {
  const [ax, ay] = sc.vue(a), [bx, by] = sc.vue(b), L = Math.hypot(bx - ax, by - ay);
  for (let s = 0; s <= L; s += 0.5) {
    const x = ax + ((bx - ax) * s) / (L || 1), y = ay + ((by - ay) * s) / (L || 1);
    for (let dy = -e; dy <= e; dy++) for (let dx = -e; dx <= e; dx++) {
      const X = Math.round(x + dx), Y = Math.round(y + dy);
      if (X < 0 || Y < 0 || X >= sc.w || Y >= sc.h || Math.hypot(dx, dy) > e + 0.3) continue;
      const o = (Y * sc.w + X) * 4;
      for (let q = 0; q < 3; q++) sc.img[o + q] = sc.img[o + q] * (1 - alpha) + couleur[q] * alpha;
    }
  }
}
/** La flamme de bouche : une étoile jaune-orange avec un halo. */
export function flamme(sc, p, r) {
  const [cx, cy] = sc.vue(p);
  for (let y = Math.floor(cy - r * 2); y <= cy + r * 2; y++) for (let x = Math.floor(cx - r * 2); x <= cx + r * 2; x++) {
    if (x < 0 || y < 0 || x >= sc.w || y >= sc.h) continue;
    const dx = x - cx, dy = y - cy, d = Math.hypot(dx, dy), ang = Math.atan2(dy, dx);
    const rayon = r * (0.55 + 0.45 * Math.abs(Math.cos(ang * 4))) * (1 + 0.25 * Math.sin(ang * 7 + p[0]));
    const o = (y * sc.w + x) * 4;
    if (d < rayon * 0.45) sc.img.set([255, 252, 220], o);
    else if (d < rayon) sc.img.set([255, 200, 60], o);
    else if (d < r * 2) { const a = 0.4 * (1 - d / (r * 2)); for (let q = 0; q < 3; q++) sc.img[o + q] = sc.img[o + q] * (1 - a) + [255, 190, 90][q] * a; }
  }
}
/** Une fumée grise (après le tir). */
export function fumee(sc, p, r) {
  const [cx, cy] = sc.vue(p);
  for (let y = Math.floor(cy - r); y <= cy + r; y++) for (let x = Math.floor(cx - r); x <= cx + r; x++) {
    if (x < 0 || y < 0 || x >= sc.w || y >= sc.h) continue;
    const d = Math.hypot(x - cx, y - cy) / r;
    if (d > 1) continue;
    const a = 0.35 * (1 - d) * (0.6 + 0.4 * hache(x >> 1, y >> 1, 3)), o = (y * sc.w + x) * 4;
    for (let q = 0; q < 3; q++) sc.img[o + q] = sc.img[o + q] * (1 - a) + 220 * a;
  }
}

// ------------------------------------------------------------------ le texte
const sansAccent = (s) => s.normalize("NFD").replace(/[̀-ͯ]/g, "");
export function texte(img, W, s, x0, y0, t, couleur) {
  let x = x0;
  for (const ch of sansAccent(s).toUpperCase()) {
    const mm = POLICE[ch] ?? POLICE[" "];
    for (let k = 0; k < 15; k++) if (mm[k] === "1") for (let dy = 0; dy < t; dy++) for (let dx = 0; dx < t; dx++) {
      const X = x + (k % 3) * t + dx, Y = y0 + Math.floor(k / 3) * t + dy;
      if (X >= 0 && Y >= 0 && X < W && Y * W + X < img.length / 4) img.set(couleur, (Y * W + X) * 4);
    }
    x += 4 * t;
  }
  return x;
}
export const largeur = (s, t) => sansAccent(s).length * 4 * t - t;
export function rect(img, W, x0, y0, x1, y1, c, a = 1) {
  for (let y = Math.max(0, Math.round(y0)); y < Math.round(y1); y++) for (let x = Math.max(0, Math.round(x0)); x < Math.min(W, Math.round(x1)); x++) {
    const o = (y * W + x) * 4;
    if (o >= img.length) return;
    for (let q = 0; q < 3; q++) img[o + q] = img[o + q] * (1 - a) + c[q] * a;
    img[o + 3] = 255;
  }
}
/** Une étiquette : un cartouche sombre avec le texte, et un petit trait vers ce qu'elle montre (point en unités). */
export function etiquette(sc, p, s, { t = 3, dy = -40, couleur = [255, 214, 90] } = {}) {
  const [x, y] = sc.vue(p), L = largeur(s, t);
  const bx = Math.round(x - L / 2), by = Math.round(y + dy);
  for (let k = 0; k < Math.abs(dy) - 4; k++) rect(sc.img, sc.w, x - 1, y + Math.sign(dy) * k - (dy < 0 ? 0 : 0), x + 1, y + Math.sign(dy) * k + 1, [20, 16, 14], 0.85);
  rect(sc.img, sc.w, bx - 7, by - 6, bx + L + 7, by + 5 * t + 6, [22, 16, 14], 0.88);
  rect(sc.img, sc.w, bx - 7, by - 6, bx + L + 7, by - 4, couleur, 1);
  texte(sc.img, sc.w, s, bx, by, t, [...couleur, 255]);
}

/** Le ciel de fin d'après-midi, puis la scène. */
export function sceneCiel(o) {
  const sc = scene(o);
  const haut = o.cielHaut ?? [92, 150, 206], bas = o.cielBas ?? [250, 206, 142];
  for (let y = 0; y < sc.h; y++) {
    const k = Math.pow(y / sc.h, 0.9), c = melange(haut, bas, k);
    for (let x = 0; x < sc.w; x++) sc.img.set([...c.map(Math.round), 255], (y * sc.w + x) * 4);
  }
  return sc;
}
/** Le filtre « Mexique » des films : un peu plus chaud, un peu plus contrasté. */
export function filtreChaud(sc, force = 1) {
  for (let i = 0; i < sc.w * sc.h; i++) {
    const o = i * 4, r = sc.img[o], g = sc.img[o + 1], b = sc.img[o + 2];
    const l = (r * 0.3 + g * 0.59 + b * 0.11) / 255, con = (v) => 255 * (0.5 + (v / 255 - 0.5) * (1 + 0.1 * force));
    sc.img[o] = Math.max(0, Math.min(255, con(r * (1 + 0.06 * force))));
    sc.img[o + 1] = Math.max(0, Math.min(255, con(g * (1 + 0.015 * force))));
    sc.img[o + 2] = Math.max(0, Math.min(255, con(b * (1 - 0.1 * force * (1 - l * 0.5)))));
  }
}
