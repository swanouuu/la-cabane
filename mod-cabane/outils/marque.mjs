// La marque des élus (/animation eludufeu) : une flamme de 16 x 16 qui se grave sur la main ; et la marque maudite.
// Lancer depuis n'importe où :  node mod-cabane/outils/marque.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { png } from "../../outils/commun.mjs";

const SORTIE = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources",
  "assets", "cabane", "textures", "misc", "marque.png");
const N = 16;

// une goutte de feu : ronde en bas (centre cx, cy, rayon r), effilée jusqu'à la pointe (px, py), un peu courbée
const goutte = (cx, cy, r, px, py, courbe = 0) => (x, y, retrait = 0) => {
  const L = Math.hypot(px - cx, py - cy), ax = (px - cx) / L, ay = (py - cy) / L;
  const s = (x - cx) * ax + (y - cy) * ay;
  const d = -(x - cx) * ay + (y - cy) * ax - courbe * (s / L) ** 2;
  const R = r - retrait;
  if (R <= 0) return false;
  if (s <= 0) return Math.hypot(x - cx, y - cy) <= R;
  return s <= L - retrait * 1.5 && Math.abs(d) <= R * (1 - s / L) ** 0.9;
};

const flammes = [
  goutte(8, 10.6, 4.3, 8.4, 0.6, 0.6),   // la grande flamme du milieu
  goutte(5.2, 11.6, 2.5, 1.8, 4.2, -1.2), // la langue de gauche
  goutte(10.8, 11.6, 2.5, 14.2, 4.2, 1.2) // la langue de droite
];
const dans = (x, y, retrait = 0) => flammes.some((f) => f(x + 0.5, y + 0.5, retrait));

const grille = [];
for (let y = 0; y < N; y++) {
  let ligne = "";
  for (let x = 0; x < N; x++) {
    if (!dans(x, y)) { ligne += "."; continue; }
    const bord = [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([dx, dy]) => !dans(x + dx, y + dy));
    ligne += bord ? "#" : flammes[0](x + 0.5, y + 0.5, 2.6) ? "y" : dans(x, y, 1.3) ? "o" : "r";
  }
  grille.push(ligne);
}

const palette = {
  "#": [122, 8, 6],     // le contour, rouge sombre
  r: [214, 34, 18],     // rouge braise
  o: [255, 106, 24],    // orange
  y: [255, 214, 92]     // le cœur, presque blanc
};
fs.mkdirSync(path.dirname(SORTIE), { recursive: true });
fs.writeFileSync(SORTIE, png(grille, palette));
console.log(grille.join("\n"));

// La marque maudite (/animation marquemaudite), 64 x 64 : trois virgules noires au centre, et autour un anneau cassé
// en quatre morceaux, hérissé de petites flammes noires vers l'extérieur.
const M = 64;
const C = M / 2;
let graine = 3;
const hasard = () => {
  graine = (graine * 16807) % 2147483647;
  return graine / 2147483647;
};
// une virgule : une tête ronde, et une queue qui tourne autour du centre (dans le sens des aiguilles) en s'affinant
const virgule = (angle) => (x, y) => {
  const R = 8.6;
  if (Math.hypot(x - (C + R * Math.cos(angle)), y - (C + R * Math.sin(angle))) <= 4.7) return true;
  for (let i = 1; i <= 46; i++) {
    const a = angle + i * 0.03, r = 4.1 * (1 - i / 48), d = R + 1.2 + i * 0.07;
    if (Math.hypot(x - (C + d * Math.cos(a)), y - (C + d * Math.sin(a))) <= r) return true;
  }
  return false;
};
const virgules = [0, 1, 2].map((i) => virgule(-Math.PI / 2 - 0.35 + i * 2 * Math.PI / 3));
// l'anneau : quatre arcs (avec des trous entre eux), d'épaisseur irrégulière
const RAYON = 24.5;
const arcs = [0, 1, 2, 3].map((i) => [Math.PI / 4 + i * Math.PI / 2 - 0.62, Math.PI / 4 + i * Math.PI / 2 + 0.62]);
const dansArc = (a) => arcs.some(([a0, a1]) => {
  const t = ((a - a0) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
  return t <= a1 - a0;
});
const epaisseur = (a) => 1.2 + 0.6 * Math.sin(a * 7.3) * Math.sin(a * 3.1 + 1) + 0.3 * Math.sin(a * 17);
// les flammes : de petites pointes qui sortent de l'anneau, penchées, de longueurs différentes
const pointes = [];
for (const [a0, a1] of arcs) {
  for (let k = 0; k < 4; k++) {
    pointes.push({ a: a0 + (a1 - a0) * (0.12 + 0.76 * hasard()), long: 5 + 6 * hasard(), penche: (hasard() - 0.5) * 0.9, base: 1.1 + 0.7 * hasard() });
  }
}
const dansFlamme = (x, y) => pointes.some((f) => {
  const ux = Math.cos(f.a), uy = Math.sin(f.a);
  const px = x - (C + RAYON * ux), py = y - (C + RAYON * uy);
  const s = px * ux + py * uy;
  if (s < 0 || s > f.long) return false;
  const lat = -px * uy + py * ux - f.penche * s;
  return Math.abs(lat) <= f.base * (1 - s / f.long);
});
const maudite = [];
for (let y = 0; y < M; y++) {
  let ligne = "";
  for (let x = 0; x < M; x++) {
    const px = x + 0.5, py = y + 0.5;
    const a = Math.atan2(py - C, px - C), d = Math.hypot(px - C, py - C);
    const anneau = dansArc(a) && Math.abs(d - RAYON) <= epaisseur(a);
    ligne += virgules.some((v) => v(px, py)) || anneau || (dansArc(a) && dansFlamme(px, py)) ? "#" : ".";
  }
  maudite.push(ligne);
}
fs.writeFileSync(path.join(path.dirname(SORTIE), "marque_maudite.png"), png(maudite, { "#": [16, 6, 8] }));
// le contour rouge de la marque (quand elle fait mal) : les pixels juste autour du noir
const noir = (x, y) => x >= 0 && y >= 0 && x < M && y < M && maudite[y][x] === "#";
const contour = maudite.map((ligne, y) => [...ligne].map((c, x) => {
  if (c === "#") return ".";
  for (let dy = -2; dy <= 2; dy++) {
    for (let dx = -2; dx <= 2; dx++) {
      if (dx * dx + dy * dy <= 2 && noir(x + dx, y + dy)) return "#";
    }
  }
  return ".";
}).join(""));
fs.writeFileSync(path.join(path.dirname(SORTIE), "marque_maudite_contour.png"), png(contour, { "#": [255, 40, 30] }));
console.log(maudite.join("\n"));
