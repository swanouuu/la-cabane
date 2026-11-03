// La salle de catch en blocs (repris du moteur du cartel, avec la lumière d'une salle de spectacle) : le ring et ses
// cordes, les tapis du bord, les barrières, la table des commentateurs, l'allée et la scène d'entrée avec l'écran géant,
// les tribunes pleines de monde tout autour, la herse de projecteurs. Et un ring tout seul, de n'importe quelle taille.
// Unités : les blocs pour le monde ; ×16 pour la scène (comme les pixels du joueur).
import { hache, norme, POLICE, LUMIERE, eclaire, ajoute, fois, corps } from "./moteur.mjs";
import { spectateur } from "./catcheur.mjs";

// ------------------------------------------------------------------ les textures 16×16
const T = 16;
function texture(f) {
  const px = [];
  for (let y = 0; y < T; y++) for (let x = 0; x < T; x++) px.push(f(x, y).map((v) => Math.max(0, Math.min(255, v))));
  return px;
}
const bruit = (c, x, y, s, f = 0.08) => c.map((v) => v * (1 - f + 2 * f * hache(x, y, s)));
const ton = (c, k) => c.map((v) => v * k);
const uni = (base, s, f = 0.06) => texture((x, y) => bruit(base, x, y, s, f));

const TEX = {};
/** brille : les faces qui éclairent elles-mêmes ("cote", "haut", ou les deux). */
function def(nom, cote, haut = cote, bas = haut, brille = "") { TEX[nom] = { cote, haut, bas, brille }; }
{
  def("sol", uni([34, 34, 42], 1, 0.08));
  def("solHerbe", texture((x, y) => (y < 3 + Math.floor(2 * hache(x, 0, 14)) ? bruit([104, 150, 56], x, y, 15, 0.07) : bruit([124, 88, 60], x, y, 4, 0.06))),
    texture((x, y) => bruit(hache(x, y, 12) < 0.3 ? [120, 170, 64] : [102, 152, 56], x, y, 13, 0.08)), uni([124, 88, 60], 4));
  def("terre", uni([124, 88, 60], 4));
  const toile = texture((x, y) => bruit(ton([190, 188, 182], (x % 8 === 0 || y % 8 === 0) ? 0.95 : 1), x, y, 2, 0.03));
  const tablier = texture((x, y) => bruit([24, 24, 30], x, y, 3, 0.05));
  def("tablier", tablier);
  def("tapis", tablier, toile);
  def("matelas", texture((x, y) => (x === 0 || y === 0 || x === 15 || y === 15 ? [70, 86, 140] : bruit([34, 44, 86], x, y, 5, 0.06))));
  def("barriere", texture((x, y) => (y < 4 ? bruit([30, 40, 80], x, y, 6, 0.05) : x % 4 === 0 ? [74, 74, 84] : bruit([38, 38, 46], x, y, 7, 0.05))),
    uni([30, 40, 80], 8));
  def("gradin", uni([64, 62, 76], 9, 0.06), uni([74, 72, 86], 10, 0.05));
  def("ledGradin", texture((x, y) => {
    const k = 0.75 + 0.25 * Math.sin(x * 0.8);
    return y < 2 || y > 13 ? [20, 20, 26] : ton([120, 60, 255], k).map((v, i) => v + (y > 6 && y < 9 ? [100, 60, 0][i] : 0));
  }), uni([74, 72, 86], 10, 0.05), uni([74, 72, 86], 10, 0.05), "cote");
  def("scene", texture((x, y) => (x === 0 || y === 0 ? [60, 40, 90] : bruit([22, 20, 30], x, y, 11, 0.05))));
  def("ecran", uni([60, 20, 120], 12), uni([30, 30, 36], 13), uni([30, 30, 36], 13), "cote");
  def("led", texture((x, y) => (y % 4 === 0 ? [30, 20, 60] : x % 4 === 0 ? [80, 40, 200] : [150, 90, 255])), uni([30, 30, 36], 14), uni([30, 30, 36], 14), "cote");
  def("ledRouge", texture((x, y) => (y % 4 === 0 ? [60, 10, 10] : x % 4 === 0 ? [200, 30, 30] : [255, 80, 50])), uni([30, 30, 36], 14), uni([30, 30, 36], 14), "cote");
  def("rampe", texture((x, y) => bruit([26, 24, 34], x, y, 15, 0.05)), texture((x, y) => (x < 1 || x > 14 ? [180, 120, 255] : bruit([28, 26, 36], x, y, 16, 0.05))));
  def("acier", texture((x, y) => ((x + y) % 4 === 0 && (x - y + 16) % 4 === 0 ? [196, 200, 206] : bruit([150, 154, 162], x, y, 17, 0.05))));
  def("table", texture((x, y) => (y < 2 ? [200, 200, 210] : bruit([22, 22, 28], x, y, 18, 0.05))), uni([30, 30, 36], 19));
  def("mur", uni([26, 26, 34], 20, 0.06));
  def("truss", texture((x, y) => ((x + y) % 8 < 2 || x % 15 === 0 || y % 15 === 0 ? [190, 194, 200] : [40, 40, 48])));
  def("projecteur", uni([255, 248, 226], 21, 0.02), uni([255, 248, 226], 21, 0.02), uni([255, 248, 226], 21, 0.02), "cote haut");
  def("coussinRouge", uni([200, 30, 36], 22, 0.05)); def("coussinBleu", uni([40, 70, 200], 23, 0.05)); def("coussinNoir", uni([30, 30, 36], 24, 0.05));
  def("coussinOr", uni([236, 186, 50], 25, 0.05)); def("coussinBlanc", uni([236, 236, 240], 26, 0.04));
  def("cordeRouge", uni([210, 30, 36], 27, 0.04)); def("cordeBleue", uni([40, 80, 220], 28, 0.04)); def("cordeBlanche", uni([240, 240, 244], 29, 0.03));
  def("cordeOr", uni([246, 200, 60], 30, 0.04)); def("cordeNoire", uni([34, 34, 40], 31, 0.04));
  def("siege", uni([150, 32, 40], 32, 0.04)); def("noir", uni([24, 24, 28], 33, 0.04)); def("or", uni([240, 196, 60], 34, 0.04));
  def("moniteur", texture((x, y) => (x < 1 || x > 14 || y < 1 || y > 12 ? [20, 20, 24] : [70, 140, 220])), uni([20, 20, 24], 35), uni([20, 20, 24], 35), "cote");
  def("bois", texture((x, y) => bruit(ton([160, 118, 70], y % 4 === 3 ? 0.65 : 1), x, y, 36, 0.05)));
  def("pierre", uni([124, 124, 128], 37, 0.08));
  def("feuilles", texture((x, y) => bruit(hache(x, y, 38) < 0.3 ? [60, 130, 50] : [44, 106, 40], x, y, 39, 0.08)));
}

const RED = new Map();
function reduite(nom, face, n) {
  const cle = nom + face + n;
  if (RED.has(cle)) return RED.get(cle);
  const src = TEX[nom]?.[face] ?? TEX.sol.cote, k = T / n, r = [];
  for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) {
    const s = [0, 0, 0];
    for (let y = 0; y < k; y++) for (let x = 0; x < k; x++) { const c = src[Math.floor(j * k + y) * T + Math.floor(i * k + x)]; s[0] += c[0]; s[1] += c[1]; s[2] += c[2]; }
    r.push(s.map((v) => v / (k * k)));
  }
  RED.set(cle, r);
  return r;
}
const brille = (nom, face) => TEX[nom]?.brille.includes(face === "haut" || face === "bas" ? "haut" : "cote");

// ------------------------------------------------------------------ le monde
export class Monde {
  constructor([x0, x1], [y0, y1], [z0, z1]) {
    Object.assign(this, { x0, y0, z0, sx: x1 - x0 + 1, sy: y1 - y0 + 1, sz: z1 - z0 + 1 });
    this.g = new Uint8Array(this.sx * this.sy * this.sz);
    this.porteOmbre = new Uint8Array(this.sx * this.sy * this.sz);
    this.noms = ["air"]; this.ids = { air: 0 };
    this.boites = []; this.gens = []; this.peintures = []; this.reperes = {};
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
  /** Un pavé libre, en blocs, avec la texture d'un bloc ; o.ombre : false s'il ne fait pas d'ombre. */
  boite(a, b, nom, o = {}) {
    this.boites.push({ a, b, nom, ...o });
    if (o.ombre !== false) for (let x = Math.floor(a[0]); x < Math.ceil(b[0]); x++) for (let y = Math.floor(a[1]); y < Math.ceil(b[1]); y++)
      for (let z = Math.floor(a[2]); z < Math.ceil(b[2]); z++) { const i = this.i(x, y, z); if (i >= 0 && (b[0] - a[0]) * (b[2] - a[2]) > 0.2) this.porteOmbre[i] = 1; }
  }
  /** Est-ce qu'un point (en unités) est caché de la source S (en unités) par des blocs ? */
  cache(P, S) {
    const d = [S[0] - P[0], S[1] - P[1], S[2] - P[2]], L = Math.hypot(...d) / 16, n = Math.ceil(L * 2.5);
    for (let k = 2; k < n; k++) {
      const t = k / n, x = Math.floor((P[0] + d[0] * t) / 16), y = Math.floor((P[1] + d[1] * t) / 16), z = Math.floor((P[2] + d[2] * t) / 16);
      if (y >= this.y0 + this.sy) return false;
      const i = this.i(x, y, z);
      if (i >= 0 && (this.g[i] || this.porteOmbre[i])) return true;
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

/** Le monde dans une scène : n cases par face ; garde(x, y, z) dit quels blocs dessiner (vue en coupe). */
export function rendreMonde(sc, m, { n = 4, garde = null, sansHerse = false, gardeBoites = null } = {}) {
  const dedans = (x, y, z) => m.plein(x, y, z) && (!garde || garde(x, y, z));
  for (let xi = 0; xi < m.sx; xi++) for (let yi = 0; yi < m.sy; yi++) for (let zi = 0; zi < m.sz; zi++) {
    const id = m.g[(xi * m.sy + yi) * m.sz + zi];
    if (!id) continue;
    const x = xi + m.x0, y = yi + m.y0, z = zi + m.z0;
    if (garde && !garde(x, y, z)) continue;
    const nom = m.noms[id];
    for (const f of FACES) {
      const q = [x + f.N[0], y + f.N[1], z + f.N[2]];
      if (dedans(...q) || !sc.visible(f.N)) continue;
      const cote = f.nom === "haut" ? "haut" : f.nom === "bas" ? "bas" : "cote";
      const tex = reduite(nom, cote, n), luit = brille(nom, f.nom);
      const voisin = (du, dv) => (dedans(q[0] + f.U[0] * du + f.V[0] * dv, q[1] + f.U[1] * du + f.V[1] * dv, q[2] + f.U[2] * du + f.V[2] * dv) ? 1 : 0);
      const g = voisin(-1, 0), d = voisin(1, 0), h = voisin(0, -1), b = voisin(0, 1);
      const cs = [[voisin(-1, -1) && !g && !h, 0, 0], [voisin(1, -1) && !d && !h, 1, 0], [voisin(-1, 1) && !g && !b, 0, 1], [voisin(1, 1) && !d && !b, 1, 1]];
      const O = [x + f.O[0], y + f.O[1], z + f.O[2]];
      for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) {
        const u = (i + 0.5) / n, v = (j + 0.5) / n;
        let occ = g * Math.max(0, 1 - u / 0.5) + d * Math.max(0, 1 - (1 - u) / 0.5) + h * Math.max(0, 1 - v / 0.5) + b * Math.max(0, 1 - (1 - v) / 0.5);
        for (const [c, cu, cv] of cs) if (c) occ += 0.8 * Math.max(0, 1 - Math.hypot(u - cu, v - cv) / 0.5);
        const ao = luit ? 1 : 1 - Math.min(0.5, occ * 0.3);
        const centre = [O[0] + f.U[0] * u + f.V[0] * v + f.N[0] * 0.02, O[1] + f.U[1] * u + f.V[1] * v + f.N[1] * 0.02, O[2] + f.U[2] * u + f.V[2] * v + f.N[2] * 0.02];
        let c = tex[j * n + i], peint = false;
        for (const pe of m.peintures) { const r = pe(x, y, z, f.nom, u, v); if (r) { c = r; peint = r[3] === 1; break; } }
        const lum = luit || peint ? [1, 1, 1] : eclaire(sc, f.N, fois(centre, 16));
        const p = (a, bb) => fois([O[0] + f.U[0] * a + f.V[0] * bb, O[1] + f.U[1] * a + f.V[1] * bb, O[2] + f.U[2] * a + f.V[2] * bb], 16);
        sc.quad([p(i / n, j / n), p((i + 1) / n, j / n), p((i + 1) / n, (j + 1) / n), p(i / n, (j + 1) / n)], c.slice(0, 3).map((w, k) => w * lum[k] * ao));
      }
    }
  }
  for (const bo of m.boites) if ((!garde || garde(Math.floor(bo.a[0]), Math.floor(bo.a[1]), Math.floor(bo.a[2]))) && !(sansHerse && bo.herse) && (!gardeBoites || gardeBoites(bo))) boiteTexturee(sc, bo, n);
}

const FB = [
  { nom: "devant", N: [0, 0, 1] }, { nom: "dos", N: [0, 0, -1] }, { nom: "droite", N: [-1, 0, 0] }, { nom: "gauche", N: [1, 0, 0] }, { nom: "haut", N: [0, 1, 0] }, { nom: "bas", N: [0, -1, 0] },
];
/** Un pavé libre texturé, éclairé comme les blocs. */
function boiteTexturee(sc, { a, b, nom, couleur }, n) {
  const T = { p: (v) => fois(v, 16), n: (v) => v };
  const tailles = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
  for (const f of FACES) {
    if (!sc.visible(f.N)) continue;
    const O = [0, 1, 2].map((k) => a[k] + f.O[k] * tailles[k]);
    const U = f.U.map((v, k) => v * tailles[k]), V = f.V.map((v, k) => v * tailles[k]);
    const nu = Math.max(1, Math.round(Math.hypot(...U) * n)), nv = Math.max(1, Math.round(Math.hypot(...V) * n));
    const cote = f.nom === "haut" ? "haut" : f.nom === "bas" ? "bas" : "cote";
    const tex = reduite(nom, cote, n), luit = brille(nom, f.nom);
    for (let j = 0; j < nv; j++) for (let i = 0; i < nu; i++) {
      const pt = (s, t) => [O[0] + (U[0] * s) / nu + (V[0] * t) / nv, O[1] + (U[1] * s) / nu + (V[1] * t) / nv, O[2] + (U[2] * s) / nu + (V[2] * t) / nv];
      const c = couleur ? couleur(f.nom, i, j, nu, nv) : tex[(j % n) * n + (i % n)];
      if (!c) continue;
      const lum = luit || c[3] === 1 ? [1, 1, 1] : eclaire(sc, f.N, fois(ajoute(pt(i + 0.5, j + 0.5), fois(f.N, 0.03)), 16));
      sc.quad([pt(i, j), pt(i + 1, j), pt(i + 1, j + 1), pt(i, j + 1)].map(T.p), c.slice(0, 3).map((w, k) => w * lum[k]), 0.01);
    }
  }
}

/** Les gens de la salle (le public, les commentateurs), avec les catcheurs qu'on ajoute : garde(x, y, z) en blocs. */
export function rendreGens(sc, m, garde = null) {
  for (const g of m.gens) {
    if (garde && !garde(Math.floor(g.x), Math.floor(g.y), Math.floor(g.z))) continue;
    const s = g.qui ?? spectateur(g.n);
    corps(sc, s.os, s.parties, { pos: [g.x * 16, g.y * 16 + (g.assis ? 0 : 12), g.z * 16], lacet: g.lacet, ...g.pose });
    if (g.pancarte) pancarte(sc, g);
  }
}
/** Une pancarte en carton tenue au-dessus de la tête, avec des lettres griffonnées. */
function pancarte(sc, g) {
  const l = (g.lacet * Math.PI) / 180, R = (v) => [v[0] * Math.cos(l) + v[2] * Math.sin(l), v[1], -v[0] * Math.sin(l) + v[2] * Math.cos(l)];
  const base = [g.x * 16, g.y * 16 + (g.assis ? 0 : 12), g.z * 16];
  const P = (v) => ajoute(R(v), base);
  const cols = [[220, 30, 40], [30, 60, 200], [20, 20, 20], [240, 140, 20]], c = cols[Math.floor(hache(g.n, 3, 5) * 4)];
  corps(sc, { p: { pivot: [0, 12, 0] } }, [{ os: "p", a: [-9, 32, 0.4], b: [9, 41, 1.0], pas: 1.5, couleur: (f, u, v, i, j) => (f === "devant" && j > 0 && j < 5 && i > 1 && i < 11 && hache(i, j, g.n) < 0.6 ? c : [236, 232, 220]) }], { pos: base, lacet: g.lacet });
  void P;
}

// ------------------------------------------------------------------ l'écriture sur les blocs
/** Est-ce que (r, Y) tombe sur une lettre du mot, dans la boîte [r0, r1] × [ya, yb] (7 lignes de haut) ? */
function encre(mot, r, Y, r0, r1, ya, yb) {
  const cols = mot.length * 4 - 1;
  const gx = ((r - r0) / (r1 - r0)) * (cols + 2) - 1, gy = ((yb - Y) / (yb - ya)) * 7 - 1;
  if (gx < 0 || gy < 0 || gx >= cols || gy >= 5) return false;
  const c = Math.floor(gx / 4), dans = Math.floor(gx) % 4;
  if (dans === 3) return false;
  return (POLICE[mot[c]] ?? POLICE[" "])[Math.floor(gy) * 3 + dans] === "1";
}
/** La position de lecture (de gauche à droite pour qui regarde la face) et la hauteur d'une case de face. */
function lecture(x, y, z, face, u, v) {
  const Y = y + 1 - v;
  if (face === "sud") return [x + u, Y];
  if (face === "nord") return [-(x + 1 - u), Y];
  if (face === "est") return [-(z + 1 - u), Y];
  if (face === "ouest") return [z + u, Y];
  return [null, Y];
}

// ------------------------------------------------------------------ le ring
export const COULEURS_RING = {
  rouge: { corde: "cordeRouge", coussin: "coussinRouge" }, bleu: { corde: "cordeBleue", coussin: "coussinBleu" },
  blanc: { corde: "cordeBlanche", coussin: "coussinNoir" }, or: { corde: "cordeOr", coussin: "coussinNoir" }, noir: { corde: "cordeNoire", coussin: "coussinOr" },
};

/**
 * Un ring de L × P blocs, coin nord-ouest (x0, z0), posé sur y0 : le plancher et son tablier, le tapis à y0 + 2, les 4
 * poteaux, les 3 cordes, les coussins de coin et deux escaliers d'acier. Rend le dessus du tapis et les coins.
 */
export function ring(m, x0, z0, L, P, y0, { couleur = "rouge", mot = "CATCH", marches = true } = {}) {
  const C = COULEURS_RING[couleur];
  m.remplir(x0, y0, z0, x0 + L - 1, y0, z0 + P - 1, "tablier");
  m.remplir(x0, y0 + 1, z0, x0 + L - 1, y0 + 1, z0 + P - 1, "tapis");
  const Y = y0 + 2, x1 = x0 + L, z1 = z0 + P, e = 0.32;
  const coins = [[x0 + e, z0 + e], [x1 - e, z0 + e], [x1 - e, z1 - e], [x0 + e, z1 - e]];
  const hauteurs = [0.55, 1.0, 1.45].map((h) => Y + h);
  for (const [cx, cz] of coins) {
    m.boite([cx - 0.14, Y, cz - 0.14], [cx + 0.14, Y + 1.75, cz + 0.14], "acier");
    for (const h of hauteurs) m.boite([cx - 0.23, h - 0.17, cz - 0.23], [cx + 0.23, h + 0.17, cz + 0.23], C.coussin, { ombre: false });
  }
  for (let k = 0; k < 4; k++) {
    const [ax, az] = coins[k], [bx, bz] = coins[(k + 1) % 4];
    for (const h of hauteurs) m.boite([Math.min(ax, bx) - 0.06, h - 0.06, Math.min(az, bz) - 0.06], [Math.max(ax, bx) + 0.06, h + 0.06, Math.max(az, bz) + 0.06], C.corde, { ombre: false });
  }
  if (marches) for (const [sx, sz] of [[1, 1], [-1, -1]]) {
    const cx = sx > 0 ? x1 : x0, cz = sz > 0 ? z1 : z0;
    for (let k = 0; k < 3; k++) {
      const r = 1.3 - k * 0.38;
      m.boite([cx - (sx > 0 ? 0.1 : r), y0, cz - (sz > 0 ? 0.1 : r)].map((v, i) => (i === 1 ? y0 : v)), [cx + (sx > 0 ? r : 0.1), y0 + 0.62 * (k + 1), cz + (sz > 0 ? r : 0.1)], "acier");
    }
  }
  // le mot sur le tablier, au milieu de chaque côté, et un lisere de la couleur des cordes en haut
  const larg = (n) => Math.min(n - 0.6, 6.3);
  const lisere = C.corde === "cordeBlanche" ? [220, 30, 36] : TEX[C.corde].cote[0];
  m.peintures.push((x, y, z, face, u, v) => {
    if (y < y0 || y > y0 + 1 || x < x0 || x >= x1 || z < z0 || z >= z1) return null;
    const bord = (face === "sud" && z === z1 - 1) || (face === "nord" && z === z0) || (face === "est" && x === x1 - 1) || (face === "ouest" && x === x0);
    if (!bord) return null;
    const [r, Yh] = lecture(x, y, z, face, u, v);
    if (Yh > Y - 0.12) return lisere;
    const cote = face === "sud" || face === "nord" ? L : P;
    const mid = face === "sud" ? x0 + L / 2 : face === "nord" ? -(x0 + L / 2) : face === "est" ? -(z0 + P / 2) : z0 + P / 2;
    return encre(mot, r, Yh, mid - larg(cote) / 2, mid + larg(cote) / 2, y0 + 0.05, Y - 0.15) ? [240, 240, 244] : null;
  });
  return { Y, coins, hauteurs, x0, z0, x1, z1 };
}

// ------------------------------------------------------------------ la grande salle
/** Les blocs de la salle et tout son monde. Le ring fait 12 × 12, centré en (0, 0). */
export function construireSalle() {
  const m = new Monde([-29, 29], [-1, 20], [-38, 29]);
  m.remplir(-29, -1, -38, 29, -1, 29, "sol");
  m.remplir(-9, -1, -9, 8, -1, 8, "matelas");
  const R = ring(m, -6, -6, 12, 12, 0, { couleur: "rouge" });
  m.reperes.ring = R;
  // les barrières autour, avec l'ouverture de l'allée au nord
  for (let k = -10; k <= 9; k++) for (const [x, z] of [[k, -10], [k, 9], [-10, k], [9, k]]) if (!(z === -10 && x >= -2 && x <= 1)) m.set(x, 0, z, "barriere");
  // la table des commentateurs, à l'est, et ses écrans ; la cloche
  m.boite([6.4, 0, -3], [7.4, 0.95, 3], "table");
  for (const z of [-2.2, -0.2, 1.8]) m.boite([6.9, 0.95, z - 0.45], [7.0, 1.55, z + 0.45], "moniteur");
  m.boite([6.6, 0.95, -2.9], [6.95, 1.15, -2.55], "or");
  for (const [k, z] of [[0, -1.7], [1, 0.3], [2, 2.1]]) m.gens.push({ n: 900 + k, x: 8.0, y: 0.45, z, lacet: -90, assis: true, pose: { jambeD: [-90], jambeG: [-90], brasD: [-50, 0], brasG: [-50, 0] } });
  // l'allée vers la scène, un peu surélevée, bordée de barrières
  m.remplir(-2, 0, -23, 1, 0, -10, "rampe");
  for (let z = -23; z <= -11; z++) { m.set(-3, 0, z, "barriere"); m.set(2, 0, z, "barriere"); }
  m.remplir(-2, 1, -24, 1, 1, -24, "scene");
  // la scène, l'écran géant et les colonnes de led
  m.remplir(-15, 0, -34, 14, 2, -25, "scene");
  m.remplir(-2, 0, -24, 1, 1, -24, "scene");
  m.remplir(-11, 3, -35, 10, 13, -35, "ecran");
  for (const x of [-14, -13, 12, 13]) m.remplir(x, 3, -35, x, 15, -34, "led");
  m.remplir(-15, 14, -35, 14, 14, -35, "led");
  m.reperes.ecran = { x0: -11, x1: 11, y0: 3, y1: 14, z: -35 };
  // le logo de l'écran
  m.peintures.push((x, y, z, face, u, v) => {
    if (face !== "sud" || z !== -35 || x < -11 || x > 10 || y < 3 || y > 13) return null;
    const X = x + u, Y = y + 1 - v;
    const bord = X < -10.6 || X > 10.6 || Y < 3.4 || Y > 13.6;
    if (bord) return [40, 40, 50, 1];
    const lettre = encre("CATCH", X, Y, -9.6, 9.6, 5.4, 12.2);
    if (lettre) return [255, 255, 255, 1];
    if (encre("CATCH", X - 0.25, Y + 0.25, -9.6, 9.6, 5.4, 12.2)) return [255, 210, 60, 1];
    const t = (Y - 3.4) / 10.2, scan = Math.floor(Y * 4) % 2 ? 0.9 : 1;
    const fond = [40 + 170 * (1 - t), 20 + 20 * t, 110 + 80 * t].map((c) => c * scan);
    if (Y < 4.9 && Y > 4.2) return [255, 220, 90, 1].map((c, i) => (i < 3 ? c * (0.7 + 0.3 * Math.sin(X * 2)) : c));
    return [...fond, 1];
  });
  // les tribunes en gradins : une marche par bloc en s'éloignant du ring, la première rangée a ses led
  const zoneScene = (x, z) => z < -10 && Math.abs(x) <= 16;
  const haut = (x, z) => {
    const ax = Math.abs(x + 0.5) - 0.5;
    if (z < -10) return ax >= 17 && ax <= 26 && z >= -37 ? ax - 11 : 0;
    const d = Math.max(ax, Math.abs(z + 0.5) - 0.5);
    if (d < 12 || d > 26 || zoneScene(x, z)) return 0;
    return Math.round(d) - 11;
  };
  m.reperes.haut = haut;
  for (let x = -29; x <= 29; x++) for (let z = -38; z <= 29; z++) {
    const h = haut(x, z);
    if (h) m.remplir(x, 0, z, x, h - 1, z, (xx, y) => (h === 1 && y === 0 ? "ledGradin" : "gradin"));
  }
  // les murs du fond
  for (let x = -28; x <= 28; x++) for (let z = -38; z <= 28; z++) if (Math.abs(x) === 28 || z === 28 || z === -38) m.remplir(x, 0, z, x, 17, z, "mur");
  // le public : un siège et quelqu'un sur chaque marche (sauf les escaliers), tourné vers le ring
  let n = 0;
  for (let x = -29; x <= 29; x++) for (let z = -38; z <= 29; z++) {
    const h = haut(x, z);
    if (!h || h > 15) continue;
    const ax = Math.abs(x + 0.5), az = Math.abs(z + 0.5);
    const lacet = ax > az ? (x > 0 ? -90 : 90) : z > 0 ? 180 : 0;
    const le_long = ax > az ? z : x;
    if (((le_long % 9) + 9) % 9 === 4) continue;
    if (hache(x, z, 41) < 0.06) continue;
    const r = hache(x, z, 42), recul = 0.12;
    const dx = lacet === -90 ? recul : lacet === 90 ? -recul : 0, dz = lacet === 180 ? recul : lacet === 0 ? -recul : 0;
    const cx = x + 0.5 + dx, cz = z + 0.5 + dz;
    m.boite([cx - 0.3, h, cz - 0.3], [cx + 0.3, h + 0.42, cz + 0.3], "siege", { ombre: false });
    const g = { n: n++, x: cx, y: h, z: cz, lacet: lacet + (hache(x, z, 43) - 0.5) * 30 };
    if (r < 0.62) Object.assign(g, { assis: true, y: h + 0.45, pose: { jambeD: [-90], jambeG: [-90], brasD: [-30 + 20 * hache(x, z, 44), 0], brasG: [-30, 0] } });
    else if (r < 0.88) Object.assign(g, { pose: { brasD: [-160 - 15 * hache(x, z, 45), -15], brasG: [-160, 15], tete: [-15] } });
    else Object.assign(g, { pancarte: true, pose: { brasD: [-170, -12], brasG: [-170, 12] } });
    m.gens.push(g);
  }
  // la herse de projecteurs au-dessus du ring
  const yH = 12.5;
  for (const [a, b] of [[[-7.2, yH, -7.2], [7.2, yH + 0.6, -6.6]], [[-7.2, yH, 6.6], [7.2, yH + 0.6, 7.2]], [[-7.2, yH, -7.2], [-6.6, yH + 0.6, 7.2]], [[6.6, yH, -7.2], [7.2, yH + 0.6, 7.2]]])
    m.boite(a, b, "truss", { ombre: false, herse: true });
  for (let k = -6; k <= 6; k += 3) for (const [x, z] of [[k, -6.9], [k, 6.9], [-6.9, k], [6.9, k]]) m.boite([x - 0.25, yH - 0.5, z - 0.25], [x + 0.25, yH, z + 0.25], "projecteur", { ombre: false, herse: true });
  m.reperes.herse = yH;
  return m;
}

/** La lumière de la salle : le gros projecteur sur le ring (avec les ombres) et la lumière violette sur la scène. */
export function lumiereSalle(m, { force = 1 } = {}) {
  LUMIERE.mode = "salle";
  LUMIERE.ambiant = [0.36, 0.33, 0.5];
  LUMIERE.zenith = [0.22, 0.2, 0.3];
  const S = [0, 14 * 16, 0];
  LUMIERE.spots = [
    { p: S, r1: 7.5 * 16, r2: 12.5 * 16, c: [0.66 * force, 0.63 * force, 0.58 * force], ombre: (P) => m.cache(P, S) },
    { p: [0, 15 * 16, -29 * 16], r1: 7 * 16, r2: 15 * 16, c: [0.7, 0.4, 1.0] },
    { p: [0, 10 * 16, -16 * 16], r1: 2 * 16, r2: 5 * 16, c: [0.6, 0.55, 0.7] },
  ];
}

// ------------------------------------------------------------------ un ring tout seul, dehors
/** Un terrain d'herbe de L × P avec un ring de taille t au milieu, et la lumière du jour. */
export function terrainAvecRing(taille, { couleur = "rouge", marge = 4 } = {}) {
  const L = taille + 2 * marge, m = new Monde([0, L - 1], [-2, 6], [0, L - 1]);
  m.remplir(0, -2, 0, L - 1, -2, L - 1, "terre");
  m.remplir(0, -1, 0, L - 1, -1, L - 1, "solHerbe");
  m.reperes.ring = ring(m, marge, marge, taille, taille, 0, { couleur });
  return m;
}
export function lumiereJour(m) {
  LUMIERE.mode = "jour";
  LUMIERE.ombreSoleil = (P) => m.cache(P, ajoute(P, fois(LUMIERE.soleil, 16 * 30)));
}
