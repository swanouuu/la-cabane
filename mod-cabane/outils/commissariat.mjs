// Transforme la maquette du commissariat (docs/commissariat/maquette.html) en plan de blocs pour /commissariat.
// node outils/commissariat.mjs → src/main/resources/data/cabane/commissariat/plan.json
//
// La maquette reste la source : on rejoue tel quel le code qui pose ses blocs, puis on traduit chaque nom en bloc du jeu.
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const ici = path.dirname(fileURLToPath(import.meta.url));
const maquette = fs.readFileSync(path.join(ici, "../../docs/commissariat/maquette.html"), "utf8");
const sortie = path.join(ici, "../src/main/resources/data/cabane/commissariat/plan.json");

const debut = maquette.indexOf("// ================================================================ le terrain et la coque");
const fin = maquette.indexOf("// ================================================================ étages et pièces");
if (debut < 0 || fin < debut) throw new Error("repères introuvables dans la maquette");

// ---------------------------------------------------------------- on rejoue la maquette
const cases = new Map();
const cle = (x, y, z) => x + "," + y + "," + z;
function pose(x, y, z, nom, m) { if (nom) cases.set(cle(x, y, z), { x, y, z, nom, m: m || 0 }); else cases.delete(cle(x, y, z)); }
function zone(x0, y0, z0, x1, y1, z1, nom, m) { for (let z = z0; z <= z1; z++) for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) pose(x, y, z, nom, m); }
const vide = (x0, y0, z0, x1, y1, z1) => zone(x0, y0, z0, x1, y1, z1, null);
function murs(x0, y0, z0, x1, y1, z1, nom) { zone(x0, y0, z0, x1, y1, z0, nom); zone(x0, y0, z1, x1, y1, z1, nom); zone(x0, y0, z0, x0, y1, z1, nom); zone(x1, y0, z0, x1, y1, z1, nom); }
const contexte = { pose, zone, vide, murs, forme() {}, Math, sortie: null };
vm.runInNewContext(maquette.slice(debut, fin) + "\nsortie = { PORTES, LEVIERS, CELLULES };", contexte);
const { PORTES, LEVIERS, CELLULES } = contexte.sortie;

// le décor autour du bâtiment reste celui du monde : on ne garde que le jardin
for (const [k, c] of cases) if (c.nom === "tronc" || c.nom === "feuille" || (c.nom === "herbe" && !(c.x >= 0 && c.x <= 6 && c.z >= 0 && c.z <= 7))) cases.delete(k);

// ---------------------------------------------------------------- ce que la maquette simplifie
// les deux escaliers s'arrêtent une marche trop tôt : on ne pourrait pas les monter sans sauter
zone(1, 5, 16, 3, 5, 16, "marche", 0);
zone(6, 0, 19, 6, 0, 21, "marche", 1);
zone(1, 6, 16, 3, 6, 16, null); // pas de tapis sur la dernière marche
// cette lampe pendait dans le vide, en plein passage : on s'y cognait la tête en descendant
pose(2, -1, 20, null); pose(1, -1, 18, "plafonnier");
// les gyrophares du toit sur un petit mât
for (const x of [8, 21]) pose(x, 6, 1, "poteau");

// ---------------------------------------------------------------- traduction en blocs
const SIMPLES = {
  blanc: "minecraft:white_concrete", marine: "minecraft:blue_concrete", marine2: "minecraft:gray_concrete", comptoir: "minecraft:blue_concrete",
  gris: "minecraft:gray_concrete", grisclair: "minecraft:light_gray_concrete", beton: "minecraft:stone", pierre: "minecraft:stone_bricks",
  noirpierre: "minecraft:polished_blackstone", verre: "minecraft:glass", barreaux: "minecraft:iron_bars", poteau: "minecraft:iron_bars",
  herbe: "minecraft:grass_block[snowy=false]", sable: "minecraft:sand", cible: "minecraft:target", ratelier: "minecraft:dark_oak_planks", etagere: "minecraft:bookshelf",
  bleufr: "minecraft:blue_wool", blancfr: "minecraft:white_wool", rougefr: "minecraft:red_wool",
  drapeau_b: "minecraft:blue_wool", drapeau_w: "minecraft:white_wool", drapeau_r: "minecraft:red_wool",
  cloture: "minecraft:dark_oak_fence", clim: "minecraft:iron_block", chaine: "minecraft:chain[axis=y]", wc: "minecraft:cauldron",
  dalle_marine: "minecraft:stone_brick_slab[type=bottom]", plafrouge: "minecraft:redstone_lamp[lit=true]",
  lampebleue: "minecraft:blue_stained_glass", lamperouge: "minecraft:red_stained_glass",
  tableblanche: "handcrafted:spruce_table[color=light_gray]", tablenoire: "handcrafted:dark_oak_table[color=gray]", bureau: "handcrafted:dark_oak_desk[color=gray,facing=north]",
};
const TAPIS = { tapisbleu: "blue", tapisrouge: "red", tapisgris: "gray", lignejaune: "yellow", solnoir: "black", soltache: "red" };

// un petit hasard, toujours le même d'une génération à l'autre
function hasard(graine) { let s = graine >>> 0; return () => { s = (s * 1664525 + 1013904243) >>> 0; return s / 4294967296; }; }
const dansLaPrison = (c) => c.x >= 7 && c.x <= 22 && c.z >= 11 && c.z <= 22 && c.y >= 0 && c.y <= 4;
const dansUneCellule = (c) => CELLULES.some((k) => c.x >= k.x0 && c.x <= k.x1 && c.z >= k.z0 && c.z <= k.z1 && c.z !== k.zFront);
const r = hasard(20261110);

function traduire(c) {
  const facing = (d) => "[facing=" + d + "]";
  switch (c.nom) {
    case "carrelage": return (c.x + c.z) % 2 ? "minecraft:polished_andesite" : "minecraft:polished_diorite";
    case "marche": return "minecraft:stone_brick_stairs[half=bottom,shape=straight,facing=" + (c.m === 1 ? "east" : "south") + "]";
    case "casier": return "minecraft:barrel" + facing(c.x === 21 ? "west" : "north");
    case "pierre": { // les murs de la prison, fissurés et moussus
      if (!dansLaPrison(c)) return SIMPLES.pierre;
      const t = r(); return t < .24 ? "minecraft:cracked_stone_bricks" : t < .38 ? "minecraft:mossy_stone_bricks" : SIMPLES.pierre;
    }
    case "gris": { // le sol des cellules, abîmé
      if (!dansLaPrison(c)) return SIMPLES.gris;
      const t = r();
      if (dansUneCellule(c)) return t < .4 ? "minecraft:cobblestone" : t < .6 ? "minecraft:mossy_cobblestone" : t < .75 ? "minecraft:andesite" : SIMPLES.gris;
      return t < .14 ? "minecraft:cobblestone" : t < .24 ? "minecraft:andesite" : SIMPLES.gris;
    }
    case "chaise": case "fauteuil": return "handcrafted:spruce_chair[color=" + (c.nom === "chaise" ? "blue" : "black") + ",facing=north]";
    case "banc": return "handcrafted:spruce_bench[color=blue,facing=" + (c.m === 1 ? "east" : c.m === 3 ? "west" : "north") + "]";
    case "pc": return "refurbished_furniture:computer[powered=false,facing=north]"; // l'écran vers la chaise
    case "pupitre": return "minecraft:lectern[has_book=false,powered=false,facing=south]";
    default: if (SIMPLES[c.nom]) return SIMPLES[c.nom];
  }
  throw new Error("bloc inconnu : " + c.nom);
}

const palette = [], indice = new Map();
function numero(etat) { if (!indice.has(etat)) { indice.set(etat, palette.length); palette.push(etat); } return indice.get(etat); }
const structure = [], finitions = [], cadres = [];
const metS = (x, y, z, etat) => structure.push({ x, y, z, p: numero(etat) });
const metF = (x, y, z, etat) => finitions.push({ x, y, z, p: numero(etat) });

const SECRET = [[9, 6, 14], [9, 7, 14]];
for (const c of cases.values()) {
  if (SECRET.some(([a, b, d]) => a === c.x && b === c.y && d === c.z)) { metS(c.x, c.y, c.z, "cabane:bibliotheque_secrete[ouverte=false]"); continue; }
  if (TAPIS[c.nom]) { metF(c.x, c.y, c.z, "minecraft:" + TAPIS[c.nom] + "_carpet"); continue; }
  if (c.nom === "plafonnier") { metF(c.x, c.y, c.z, "minecraft:" + (dansLaPrison(c) ? "soul_lantern" : "lantern") + "[hanging=true,waterlogged=false]"); continue; }
  if (c.nom === "lit" || c.nom === "litT") { metF(c.x, c.y, c.z, "minecraft:gray_bed[occupied=false,part=" + (c.nom === "litT" ? "head" : "foot") + ",facing=" + (c.z < 16 ? "north" : "south") + "]"); continue; }
  if (c.nom === "armeposee") { cadres.push({ x: c.x, y: c.y, z: c.z, dir: "up", objet: c.y > 0 ? "matraque" : "pistolet" }); continue; }
  if (c.nom === "pc") { metF(c.x, c.y, c.z, traduire(c)); continue; }
  metS(c.x, c.y, c.z, traduire(c));
  if (c.nom === "plafrouge") metS(c.x, c.y + 1, c.z, "minecraft:redstone_block"); // la lampe reste allumée, le bloc est caché dans le toit
  if (c.nom === "lampebleue" || c.nom === "lamperouge") metS(c.x, c.y + 1, c.z, "minecraft:light[level=15,waterlogged=false]");
}
// le râtelier : les armes accrochées au mur
for (let x = 1; x <= 7; x++) cadres.push({ x, y: -3, z: 13, dir: "south", objet: x % 2 ? "pistolet" : "matraque" });

// les cellules : une porte de fer et son levier, seulement côté couloir
for (const p of PORTES) {
  const nord = p.z < 16, sens = nord ? "north" : "south", charniere = nord ? "left" : "right";
  for (const [y, demi] of [[p.y, "lower"], [p.y + 1, "upper"]]) metF(p.x, y, p.z, "minecraft:iron_door[half=" + demi + ",facing=" + sens + ",hinge=" + charniere + ",open=false,powered=false]");
}
for (const l of LEVIERS) {
  const z = l.nord ? 16 : 17;
  metF(l.x, l.y, z, "minecraft:lever[face=wall,powered=false,facing=" + (l.nord ? "south" : "north") + "]");
}

// ---------------------------------------------------------------- le côté lugubre
const libre = (x, y, z) => !cases.has(cle(x, y, z)) && !structure.some((b) => b.x === x && b.y === y && b.z === z) && !finitions.some((b) => b.x === x && b.y === y && b.z === z);
const toile = (x, y, z) => { if (libre(x, y, z)) metS(x, y, z, "minecraft:cobweb"); };
CELLULES.forEach((k, i) => {
  const fond = k.nord ? k.z0 : k.z1, avant = k.nord ? k.zFront - 1 : k.zFront + 1, d = k.nord ? 1 : -1;
  for (const x of [k.x0, k.x1]) { toile(x, 4, fond); toile(x, 4, avant); if (r() < .7) toile(x, 3, fond); }
  toile(k.x0 + 1 + Math.floor(r() * 2), 4, fond);
  if (r() < .6) toile(k.x0, 3, avant);
  if (r() < .5 && i !== 1 && i !== 4) toile(k.x0, 1, avant);
  // des chaînes au mur du fond, du sang par terre, un crâne ici et là
  if (i % 3 !== 1) { const x = k.x1 - 1; if (libre(x, 4, fond) && libre(x, 3, fond)) { metS(x, 4, fond, "minecraft:chain[axis=y]"); metS(x, 3, fond, "minecraft:chain[axis=y]"); } }
  if (i % 2 === 0) for (const [x, z] of [[k.x0 + 1, fond + d], [k.x0 + 2, fond + d], [k.x0 + 2, avant]]) if (libre(x, 1, z) && !(x === k.x1 && z === avant)) metF(x, 1, z, "minecraft:redstone_wire");
  if (i === 1 || i === 4) { const x = k.x0; if (libre(x, 1, avant)) metF(x, 1, avant, "minecraft:skeleton_skull[rotation=" + (i === 1 ? 3 : 11) + "]"); }
});
// le couloir de la prison, la salle secrète, le sous-sol
for (const [x, y, z] of [[8, 4, 16], [8, 4, 17], [21, 4, 16], [21, 4, 17], [12, 4, 17], [13, 4, 17], [18, 4, 16], [17, 4, 17], [8, 3, 17], [21, 3, 16],
  [5, 9, 10], [12, 9, 10], [5, 9, 13], [12, 9, 13], [5, 8, 10], [12, 8, 13], [7, 9, 13], [9, 9, 10],
  [1, -1, 13], [7, -1, 13], [1, -1, 19], [1, -1, 21], [20, -1, 12], [20, -1, 21], [9, -1, 12], [9, -1, 21], [1, -2, 21]]) toile(x, y, z);

// ---------------------------------------------------------------- écriture
const ordre = (a, b) => a.y - b.y || a.z - b.z || a.x - b.x;
const aplatir = (l) => l.sort(ordre).flatMap((b) => [b.x, b.y, b.z, b.p]);
const plan = {
  palette, structure: aplatir(structure), finitions: aplatir(finitions),
  pupitre: [7, 6, 15], secret: SECRET,
  mannequins: [[17, 1, 2, 0], [20, 1, 2, 0], [17, 1, 6, 180], [20, 1, 6, 180]],
  cadres: cadres.map((c) => [c.x, c.y, c.z, c.dir, c.objet]),
  affiche: [4, 5, 7], enseigne: [11.5, 6.5, -0.06],
};
fs.mkdirSync(path.dirname(sortie), { recursive: true });
fs.writeFileSync(sortie, JSON.stringify(plan));
console.log("structure", structure.length, "| finitions", finitions.length, "| cadres", cadres.length, "| palette", palette.length, "|", Math.round(fs.statSync(sortie).size / 1024), "Ko");
const noms = new Map(); for (const b of structure.concat(finitions)) noms.set(palette[b.p].split("[")[0], (noms.get(palette[b.p].split("[")[0]) || 0) + 1);
console.log([...noms].sort((a, b) => b[1] - a[1]).map(([n, k]) => n.replace("minecraft:", "") + " " + k).join(", "));
