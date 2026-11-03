// Le Kurama du mod « AHZNB's Naruto ShinobiCraft » (gardé de côté dans narutomod/, hors du dépôt), dessiné dans le
// moteur des maquettes : ses pièces (rejouées depuis son modèle 1.12), sa texture jaune du mode Kurama, avec les yeux
// rouges de sa texture de base à la place des ronds lumineux. Les yeux sont une plaque à part qui brille.
import path from "node:path";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import { lire, clair, norme, croix } from "../../fruits/generateur/moteur.mjs";

const ICI = path.join(path.dirname(fileURLToPath(import.meta.url)), "narutomod");
const MODELE = JSON.parse(fs.readFileSync(path.join(ICI, "kurama-modele.json"), "utf8"));
const TEX = lire(path.join(ICI, "kurama-kcm.png"));
const BASE = lire(path.join(ICI, "kurama-base.png"));
// les yeux : la plaque des yeux (u 48 à 56, v 12 à 14 sur 64) prise dans la texture de base
const K = TEX.w / 64;
for (let y = 12 * K; y < 14 * K; y++) for (let x = 48 * K; x < 56 * K; x++) {
  const i = (y * TEX.w + x) * 4;
  BASE.px.copy(TEX.px, i, i, i + 4);
}

const mul = (A, B) => A.map((r) => [0, 1, 2].map((j) => r[0] * B[0][j] + r[1] * B[1][j] + r[2] * B[2][j]));
const app = (A, v) => [0, 1, 2].map((i) => A[i][0] * v[0] + A[i][1] * v[1] + A[i][2] * v[2]);
const Rx = (a) => [[1, 0, 0], [0, Math.cos(a), -Math.sin(a)], [0, Math.sin(a), Math.cos(a)]];
const Ry = (a) => [[Math.cos(a), 0, Math.sin(a)], [0, 1, 0], [-Math.sin(a), 0, Math.cos(a)]];
const Rz = (a) => [[Math.cos(a), -Math.sin(a), 0], [Math.sin(a), Math.cos(a), 0], [0, 0, 1]];

/** Les faces du modèle (coordonnées du modèle, y vers le bas), avec leurs coins de texture ; brille : la couche des yeux. */
function faces(n, M, O, brille, liste) {
  // comme ModelRenderer.render : translate(rp), puis rotate Z, Y, X
  const R = mul(M, mul(Rz(n.rot[2]), mul(Ry(n.rot[1]), Rx(n.rot[0]))));
  const O2 = O.map((v, i) => v + app(M, n.rp)[i]);
  const ici = (p) => app(R, p).map((v, i) => v + O2[i]);
  for (const b of n.boites) {
    let x0 = b.x - b.gonfle, x1 = b.x + b.dx + b.gonfle;
    const y0 = b.y - b.gonfle, z0 = b.z - b.gonfle, y1 = b.y + b.dy + b.gonfle, z1 = b.z + b.dz + b.gonfle;
    if (b.miroir) [x0, x1] = [x1, x0];
    const v7 = [x0, y0, z0], v = [x1, y0, z0], v1 = [x1, y1, z0], v2 = [x0, y1, z0];
    const v3 = [x0, y0, z1], v4 = [x1, y0, z1], v5 = [x1, y1, z1], v6 = [x0, y1, z1];
    const { u, v: w, dx, dy, dz } = b;
    const plat = dx === 0 || dy === 0 || dz === 0;
    const centre = ici([(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2]);
    for (const [vs, u1, v1_, u2, v2_] of [
      [[v4, v, v1, v5], u + dz + dx, w + dz, u + dz + dx + dz, w + dz + dy],
      [[v7, v3, v6, v2], u, w + dz, u + dz, w + dz + dy],
      [[v4, v3, v7, v], u + dz, w, u + dz + dx, w + dz],
      [[v1, v2, v6, v5], u + dz + dx, w + dz, u + dz + dx + dx, w],
      [[v, v7, v2, v1], u + dz, w + dz, u + dz + dx, w + dz + dy],
      [[v3, v4, v5, v6], u + dz + dx + dz, w + dz, u + dz + dx + dz + dx, w + dz + dy],
    ]) {
      if (u1 === u2 || v1_ === v2_) continue;
      liste.push({ coins: vs.map(ici), u1, v1: v1_, u2, v2: v2_, centre, plat, brille });
    }
  }
  for (const e of n.enfants) faces(e, R, O2, brille, liste);
  return liste;
}
const ID = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
const FACES = MODELE.racines.flatMap((r) => faces(r, ID, [0, 0, 0], r.nom === "field_178720_f", []));

function texel(u, v) {
  const x = Math.min(TEX.w - 1, Math.max(0, Math.floor(u * K))), y = Math.min(TEX.h - 1, Math.max(0, Math.floor(v * K)));
  const i = (y * TEX.w + x) * 4;
  return [TEX.px[i], TEX.px[i + 1], TEX.px[i + 2], TEX.px[i + 3]];
}

/** L'échelle du modèle dans les maquettes (il fait environ 51 de haut dans son modèle) ; 1 = un Kurama de 180 de haut. */
export const ECHELLE = 3.5;
/** Où est sa gueule (le bout du museau), dans son repère à k = 1 : pour y poser la Bijuu Dama. */
export const GUEULE = [0, 82, 66];

/**
 * Kurama, dans le repère M (voir kyubi.monde : p, n, aile). o.pas : la taille des cases en unités du modèle (0,25 =
 * deux pixels de sa texture HD).
 */
export function kuramaMod(sc, M, o = {}) {
  const pas = o.pas ?? 0.25;
  const W = ([x, y, z]) => M.p([x * ECHELLE, (24 - y) * ECHELLE, -z * ECHELLE]);
  for (const f of FACES) {
    const [P0, P1, P2, P3] = f.coins.map(W);
    let n;
    if (f.plat) n = norme(croix(P1.map((v, i) => v - P0[i]), P2.map((v, i) => v - P0[i])));
    else {
      const c = W(f.centre), m = [0, 1, 2].map((i) => (P0[i] + P1[i] + P2[i] + P3[i]) / 4);
      n = norme(m.map((v, i) => v - c[i]));
    }
    const l = sc.lumiere(n, f.plat);
    if (l < 0) continue;
    const nu = Math.max(1, Math.ceil(Math.abs(f.u2 - f.u1) / pas)), nv = Math.max(1, Math.ceil(Math.abs(f.v2 - f.v1) / pas));
    // P(s, t) : s de u1 à u2, t de v1 à v2 ; (u1,v1) = P1, (u2,v1) = P0, (u1,v2) = P2, (u2,v2) = P3
    const P = (s, t) => [0, 1, 2].map((i) => (1 - s) * (1 - t) * P1[i] + s * (1 - t) * P0[i] + (1 - s) * t * P2[i] + s * t * P3[i]);
    for (let j = 0; j < nv; j++) for (let i = 0; i < nu; i++) {
      const s0 = i / nu, s1 = (i + 1) / nu, t0 = j / nv, t1 = (j + 1) / nv;
      const tx = texel(f.u1 + (f.u2 - f.u1) * (s0 + s1) / 2, f.v1 + (f.v2 - f.v1) * (t0 + t1) / 2);
      if (tx[3] < 100) continue;
      const c = f.brille ? tx.slice(0, 3) : clair(tx.slice(0, 3), 0.78 + 0.36 * (l - 0.62) / 0.42);
      sc.quad([P(s0, t0), P(s1, t0), P(s1, t1), P(s0, t1)], c, f.brille ? 0.05 : 0, M.aile || f.brille);
    }
  }
}
