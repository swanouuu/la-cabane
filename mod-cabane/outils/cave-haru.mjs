// Transforme le croquis de la Cave à Haru (docs/cave-haru/cave.html) en plan de blocs pour /caveharu.
// node outils/cave-haru.mjs → src/main/resources/data/cabane/cave_haru/plan.json
//   + les tableaux (affiches dessinées par le croquis, docs/cave-haru/affiches/*.png, 128 px par bloc)
//   + le skin d'Haru pour le barman.
//
// Le croquis reste la source : on rejoue tel quel le bloc « PLAN » qui pose ses blocs, puis on traduit chaque case en bloc du jeu.
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const ici = path.dirname(fileURLToPath(import.meta.url));
const docs = path.join(ici, "../../docs/cave-haru");
const res = path.join(ici, "../src/main/resources");
const croquis = fs.readFileSync(path.join(docs, "cave.html"), "utf8");

const debut = croquis.indexOf("// ================================================================ PLAN : début");
const fin = croquis.indexOf("// ================================================================ PLAN : fin");
if (debut < 0 || fin < debut) throw new Error("repères PLAN introuvables dans le croquis");

// ---------------------------------------------------------------- on rejoue le croquis
const cases = new Map();
const cle = (x, y, z) => x + "," + y + "," + z;
const affiches = [];
let haru = null;
function h3(x, y, z) { const n = Math.sin(x * 127.1 + y * 311.7 + z * 74.7) * 43758.5453; return n - Math.floor(n); }
const contexte = {
  pose(x, y, z, t, f = "cube", d = null, plafond = false, e = null) { cases.set(cle(x, y, z), { x, y, z, t, f, d, e }); },
  vide(x, y, z) { cases.delete(cle(x, y, z)); },
  deco() {}, // les chopes et bouteilles posées : rien dans le jeu
  affiche(nom, x, y, z, face) { affiches.push({ nom, x, y, z, face }); },
  haru(x, y, z, yaw) { haru = [x, y, z, yaw]; },
  h3,
  pierreMix(x, y, z, mousse) { const r = h3(x, y, z); return r < mousse ? "pierre_m" : r < mousse + 0.13 ? "pierre_f" : "pierre"; },
  Math,
};
vm.runInNewContext(croquis.slice(debut, fin), contexte);

// ---------------------------------------------------------------- traduction en blocs
const SIMPLES = {
  pierre: "minecraft:stone_bricks", pierre_m: "minecraft:mossy_stone_bricks", pierre_f: "minecraft:cracked_stone_bricks",
  pave: "minecraft:cobblestone", pave_m: "minecraft:mossy_cobblestone", cn_pl: "minecraft:dark_oak_planks", ep_pl: "minecraft:spruce_planks",
  ep_ec: "minecraft:stripped_spruce_wood[axis=y]", porte_s: "minecraft:stripped_spruce_wood[axis=y]", briques: "minecraft:bricks",
  ardoise: "minecraft:polished_deepslate", vitre: "minecraft:glass_pane", chaine: "minecraft:chain[axis=y]", barriere: "minecraft:dark_oak_fence",
  plaque: "minecraft:dark_oak_pressure_plate", tapis: "minecraft:red_carpet", cuivre: "minecraft:waxed_cut_copper", chaudron: "minecraft:cauldron",
  or: "minecraft:gold_block", toile: "minecraft:cobweb", bougie: "minecraft:candle[candles=3,lit=true]",
  noir_br: "minecraft:polished_blackstone_bricks", dore: "minecraft:gilded_blackstone", quartz: "minecraft:quartz_block",
  quartz_p: "minecraft:quartz_pillar[axis=y]", fer: "minecraft:iron_block", diamant: "minecraft:diamond_block",
  emeraude: "minecraft:emerald_block", or_brut: "minecraft:raw_gold_block",
};
const BOIS = { cn: "dark_oak", ep: "spruce", tuile: "deepslate_tile" };
const SENS = { n: "north", s: "south", e: "east", w: "west" };
// besoin d'un support, ou en deux morceaux : posés après le reste, sans mise à jour des voisins
const FINITIONS = new Set(["lanterne", "bougie", "chaine", "porte", "plaque", "tapis", "robinet", "echelle", "toile"]);

function bloc(c) {
  if (SIMPLES[c.t]) return SIMPLES[c.t];
  const [famille, sorte] = c.t.split("_");
  if (sorte === "dalle") return `minecraft:${BOIS[famille]}_slab[type=${c.f === "dalle_h" ? "top" : "bottom"}]`;
  if (sorte === "esc") return `minecraft:${BOIS[famille]}_stairs[facing=${SENS[c.d]},half=bottom]`;
  switch (c.t) {
    case "buche": return `minecraft:dark_oak_log[axis=${c.d || "y"}]`;
    case "foin": return `minecraft:hay_block[axis=${c.d || "y"}]`;
    case "tonneau": {
      const sens = c.d === "y" ? "up" : c.d === "x" ? (c.x < 10 ? "east" : "west") : "north";
      return `minecraft:barrel[facing=${sens},open=false]`;
    }
    case "lanterne": return `minecraft:lantern[hanging=${c.f === "petit_h"}]`;
    case "feu": return `minecraft:campfire[lit=true,facing=${SENS[c.d] || "north"}]`;
    case "porte": {
      // la porte d'entrée, vue de la rue : elle s'ouvre vers l'intérieur, les gonds sur les côtés
      const [moitie, cote] = c.e.split("_");
      return `minecraft:dark_oak_door[facing=south,half=${moitie === "bas" ? "lower" : "upper"},hinge=${cote === "g" ? "right" : "left"},open=false]`;
    }
    case "casier": return `minecraft:chiseled_bookshelf[facing=${SENS[c.d]}]`;
    case "tuyau": return `minecraft:lightning_rod[facing=${c.d === "z" ? "south" : c.d === "x" ? "east" : "up"}]`;
    case "robinet": return `supplementaries:faucet[facing=${SENS[c.d]}]`;
    case "coffre": return `minecraft:chest[facing=${SENS[c.d]},type=single]`;
    case "echelle": return `minecraft:ladder[facing=${SENS[c.d]}]`;
    default: throw new Error("bloc inconnu : " + c.t);
  }
}

const palette = [];
const indice = new Map();
const numero = (nom) => { if (!indice.has(nom)) { indice.set(nom, palette.length); palette.push(nom); } return indice.get(nom); };
const structure = [], finitions = [];
for (const c of [...cases.values()].sort((a, b) => a.y - b.y || a.z - b.z || a.x - b.x)) {
  (FINITIONS.has(c.t) ? finitions : structure).push(c.x, c.y, c.z, numero(bloc(c)));
}

// ---------------------------------------------------------------- les tableaux
// Le jeu accroche un tableau à une case et le décale d'un demi-bloc quand sa taille est paire (voir Painting) :
// on calcule cette case à partir du coin bas de l'affiche dans le croquis.
const TAILLES = {};
const blocAffiches = croquis.slice(croquis.indexOf("const AFFICHES = {"), croquis.indexOf("const CANVAS = {};"));
for (const m of blocAffiches.matchAll(/^  (\w+): \{ titre: .*?, w: (\d+), h: (\d+),/gm)) TAILLES[m[1]] = { w: +m[2], h: +m[3] };
const tableaux = affiches.map(({ nom, x, y, z, face }) => {
  const { w, h } = TAILLES[nom] ?? (() => { throw new Error("taille inconnue : " + nom); })();
  const pair = w % 2 === 0 ? 0.5 : 0;
  const milieu = w / 2 - 0.5;
  let px = x, pz = z;
  if (face === "s") px = x + milieu - pair;
  if (face === "n") px = x + milieu + pair;
  if (face === "e") pz = z + milieu + pair;
  if (face === "w") pz = z + milieu - pair;
  const py = y + h / 2 - 0.5 - (h % 2 === 0 ? 0.5 : 0);
  return ["cave_haru_" + nom, px, py, pz, SENS[face]];
});
const peints = path.join(res, "assets/cabane/textures/painting");
const variantes = path.join(res, "data/cabane/painting_variant");
for (const nom of Object.keys(TAILLES)) {
  const png = path.join(docs, "affiches", nom + ".png");
  if (!fs.existsSync(png)) throw new Error("affiche pas encore exportée : " + png + " (bouton du croquis, ou envoyerAffiches())");
  fs.copyFileSync(png, path.join(peints, "cave_haru_" + nom + ".png"));
  fs.writeFileSync(path.join(variantes, "cave_haru_" + nom + ".json"),
    JSON.stringify({ asset_id: "cabane:cave_haru_" + nom, width: TAILLES[nom].w, height: TAILLES[nom].h }, null, 2) + "\n");
}
fs.copyFileSync(path.join(docs, "skin-haru.png"), path.join(res, "assets/cabane/textures/entity/pnj/haru.png"));

const sortie = path.join(res, "data/cabane/cave_haru/plan.json");
fs.mkdirSync(path.dirname(sortie), { recursive: true });
fs.writeFileSync(sortie, JSON.stringify({ palette, structure, finitions, tableaux, haru }) + "\n");
console.log(`${structure.length / 4} blocs + ${finitions.length / 4} finitions, ${palette.length} sortes, ${tableaux.length} tableaux → ${path.relative(process.cwd(), sortie)}`);
