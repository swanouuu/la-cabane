// Samehada en vrai volume (des voxels, comme le modèle Sketchfab 7598d3dc), pas une forme plate extrudée.
// Repère : y le long de la lame (0 = sous le pommeau), x en travers, z vers l'avant (le devant, où est la gueule).
// Un voxel = un demi-pixel du modèle du joueur. voxels(variante) → [[x, y, z, r, g, b], …].
//   « normale » : l'image 1 (manche de bois, pommeau blanc, bandages + bandes en croix, écailles, couronne de poils).
//   « pleine »  : quand elle a quelqu'un dedans — toute en mèches d'écailles violettes qui pendent, une gueule en
//                 fente sur le dessus, avec des dents blanches.
//   « ouverte » : pareil, la gueule grande ouverte pour engloutir.
import { hache } from "../../hachibi2/generateur/moteur.mjs";

const TOUR = Math.PI * 2;
const C = {
  pommeau: [240, 240, 236], pommeauF: [200, 200, 196], bois: [104, 72, 44], boisC: [140, 100, 62], boisF: [76, 52, 32],
  w: [224, 224, 220], x: [190, 190, 188], Y: [150, 150, 154], W: [246, 246, 242], X: [120, 120, 124],
  a: [74, 80, 108], A: [40, 44, 66], l: [110, 118, 150], s: [150, 160, 194], h: [30, 32, 50],
  f: [82, 76, 136], F: [50, 46, 94], L: [114, 108, 176], P: [146, 140, 206],
  r: [216, 96, 124], R: [52, 6, 18], d: [252, 252, 246],
};

/** Le rayon du corps à la hauteur y (0 : rien). */
export function rayon(y, v) {
  if (v === "normale") {
    if (y < 16 || y > 67) return 0;
    let r = 5.8 + (1.6 * (y - 16)) / 38;
    if (y < 19) r -= (19 - y) * 0.8;
    if (y > 58) r *= Math.sqrt(Math.max(0, 1 - ((y - 58) / 9.5) ** 2));
    return r;
  }
  if (y < 8 || y > 84) return 0;
  let r = 7.8 + 4 * Math.sin((Math.PI / 2) * Math.min(1, (y - 8) / 54));
  if (y > 72) r *= Math.sqrt(Math.max(0, 1 - ((y - 72) / 12.5) ** 2));
  return r;
}

const cache = new Map();
export function voxels(v = "normale") {
  if (cache.has(v)) return cache.get(v);
  const vox = new Map();
  const mettre = (x, y, z, c) => vox.set(`${x},${y},${z}`, [x, y, z, ...c.map(Math.round)]);
  const pleine = v !== "normale";

  // le pommeau blanc et le manche de bois
  for (let y = 0; y <= 2; y++) for (let x = -2; x <= 1; x++) for (let z = -2; z <= 1; z++) mettre(x, y, z, y === 1 ? C.pommeau : C.pommeauF);
  for (let y = 3; y <= (pleine ? 24 : 17); y++) for (let x = -1; x <= 0; x++) for (let z = -1; z <= 0; z++) {
    const bande = y % 4 === 0, croise = (y + x * 2 + z) % 4 === 1;
    mettre(x, y, z, bande ? C.boisF : croise ? C.boisC : C.bois);
  }

  if (!pleine) {
    for (let y = 16; y <= 67; y++) {
      const r = rayon(y, v);
      if (r <= 0) continue;
      const B = Math.ceil(r) + 2;
      for (let x = -B; x < B; x++) for (let z = -B; z < B; z++) {
        const px = x + 0.5, pz = z + 0.5, d = Math.hypot(px, pz), th = Math.atan2(px, pz);
        let rr = r, c;
        if (y >= 46) {
          // des écailles qui se chevauchent : chaque rangée déborde un peu sur celle du dessous
          const rang = Math.floor((y - 46) / 4), w = (y - 46) % 4, n = Math.max(6, Math.round((TOUR * r) / 4.2));
          const a = ((th / TOUR + 1) * n + (rang % 2) * 0.5) % 1, centre = 1 - Math.abs(a - 0.5) * 2;
          if (w <= 1 && centre > 0.25) rr = r + 1;
          c = w === 3 || centre < 0.2 ? C.A : w === 1 && a > 0.3 && a < 0.55 ? C.s : w === 2 ? C.l : C.a;
        } else if (y >= 44) {
          rr = r + 0.4; c = C.Y;
        } else {
          // les bandages qui s'enroulent, et les deux bandes en croix devant (et derrière)
          const c1 = Math.abs(y - 30 - px * 1.7), c2 = Math.abs(y - 30 + px * 1.7);
          if (Math.abs(pz) > 1.5 && (c1 < 1.7 || c2 < 1.7)) { rr = r + 1; c = c1 < 0.6 || c2 < 0.6 ? C.X : C.W; }
          else { const t = (((y + (th / TOUR) * 3) % 3) + 3) % 3; c = t < 1 ? C.Y : t < 2 ? C.x : C.w; if (t < 1) rr = r - 0.3; }
        }
        if (d <= rr && d > rr - 2.4) mettre(x, y, z, c);
      }
    }
    // la couronne de poils en pointe tout en haut
    for (let k = 0; k < 26; k++) {
      const th = (k / 26) * TOUR + hache(k, 1, 4) * 0.4, el = (0.35 + 0.9 * hache(k, 2, 4)) * (Math.PI / 2) * 0.95;
      const dir = [Math.sin(th) * Math.cos(el), Math.sin(el), Math.cos(th) * Math.cos(el)];
      const lg = 2 + Math.floor(hache(k, 3, 4) * 3.5);
      for (let t = 0; t <= lg; t++) {
        const p = [dir[0] * (7.2 + t), 58 + dir[1] * (9 + t), dir[2] * (7.2 + t)];
        mettre(Math.floor(p[0]), Math.round(p[1]), Math.floor(p[2]), t === lg ? C.h : C.A);
      }
    }
  } else {
    // pleine : des mèches d'écailles qui pendent, rangée par rangée, et la gueule
    for (let y = 8; y <= 84; y++) {
      const r0 = rayon(y, v);
      if (r0 <= 0) continue;
      const B = Math.ceil(r0) + 4;
      for (let x = -B; x < B; x++) for (let z = -B; z < B; z++) {
        const px = x + 0.5, pz = z + 0.5, d = Math.hypot(px, pz), th = Math.atan2(px, pz);
        const col = Math.floor((th / TOUR + 1) * 52) % 52, rang = Math.floor((84 - y) / 4), w = (84 - y) % 4;
        const rr = r0 - 0.4 + (w / 3) * (0.5 + 2.4 * hache(rang, col, 5));
        if (y < 8 + Math.floor(hache(col, 7, 3) * 14)) continue;
        if (d > rr) continue;
        if (y < 70 && d < rr - (y < 24 ? 1.8 : 2.6)) continue; // creux en bas (le manche passe dedans), plein en haut
        const ton = hache(col, Math.floor((y + hache(col, 2, 2) * 9) / 7), 11);
        mettre(x, y, z, w === 3 ? C.F : w === 0 && ton > 0.5 ? C.P : ton < 0.28 ? C.F : ton > 0.72 ? C.L : C.f);
      }
    }
    // la gueule, SUR LE DESSUS : une fente d'avant en arrière, gencives roses et dents blanches sur les deux lèvres
    const ouverte = v === "ouverte", demi = ouverte ? 6.6 : 2.8, creux = ouverte ? 4.6 : 1.2, prof = ouverte ? 6 : 2;
    const hauts = new Map();
    for (const [x, y, z] of vox.values()) { const k = x + "," + z; if (!hauts.has(k) || hauts.get(k) < y) hauts.set(k, y); }
    for (const [k, yt] of hauts) {
      const [x, z] = k.split(",").map(Number), px = x + 0.5, ax = Math.abs(px);
      if (yt < 72 || ax >= demi) continue;
      if (ax < creux) {
        for (let y = yt - prof + 1; y <= yt; y++) vox.delete(x + "," + y + "," + z);
        mettre(x, yt - prof, z, C.R);
      } else {
        for (let y = yt - prof; y <= yt; y++) if (vox.has(x + "," + y + "," + z)) mettre(x, y, z, C.r);
        const dent = ouverte ? ((z % 3) + 3) % 3 !== 0 : ((z % 2) + 2) % 2 === (px < 0 ? 0 : 1);
        if (dent) for (let t = 1; t <= (ouverte ? 2 : 1); t++) mettre(x + (ouverte && t === 2 ? (px < 0 ? 1 : -1) : 0), yt + t, z, C.d);
      }
    }
  }
  const liste = [...vox.values()];
  cache.set(v, liste);
  return liste;
}

// node samehada3d.mjs → ../apercu/samehada.json (pour la page 3D)
import path from "node:path";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const sortie = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "apercu", "samehada.json");
  fs.mkdirSync(path.dirname(sortie), { recursive: true });
  const donnees = Object.fromEntries(["normale", "pleine", "ouverte"].map((v) => [v, voxels(v)]));
  fs.writeFileSync(sortie, JSON.stringify(donnees));
  console.log(sortie, Object.entries(donnees).map(([k, l]) => `${k} ${l.length}`).join(", "));
}
