// La marque des élus (/animation) : une flamme de 16 x 16 qui se grave sur la main.
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
