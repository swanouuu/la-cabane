// Le bar de Beauvais : bières, vin, pastis, rhum et sodas (textures, recettes, traductions, l'icône de l'ivresse).
// Appelé par generer.mjs ; les prix chez Mouloud et les contrats du marché sont exportés plus bas.

// une bouteille : G le verre, H le reflet, g le fond, C la capsule, L l'étiquette, l son motif
const BOUTEILLE = [
  "................",
  ".......CC.......",
  ".......GG.......",
  ".......GG.......",
  "......GHGG......",
  "......GHGG......",
  ".....GGHGGG.....",
  ".....GHGGGG.....",
  ".....LLLLLL.....",
  ".....LllllL.....",
  ".....LLLLLL.....",
  ".....GHGGGG.....",
  ".....GHGGGG.....",
  ".....GGGGGG.....",
  ".....gggggg.....",
  "................"];
// une canette : S l'alu, s le dessus, R la couleur, W le logo
const CANETTE = [
  "................",
  "................",
  "......SSSS......",
  ".....SssssS.....",
  ".....RRRRRR.....",
  ".....RRRRRR.....",
  ".....RWWWRR.....",
  ".....RRWWWR.....",
  ".....RRRRRR.....",
  ".....RRRRRR.....",
  ".....RRRRRR.....",
  ".....RRRRRR.....",
  ".....SssssS.....",
  "......SSSS......",
  "................",
  "................"];
const ALU = { S: [200, 204, 210], s: [150, 154, 162] };

// nom, texte, motif, palette
const BOISSONS = [
  ["biere_blonde", "Bière blonde", BOUTEILLE, { G: [214, 150, 40], H: [250, 214, 120], g: [150, 96, 20], C: [200, 170, 40], L: [240, 230, 200], l: [40, 90, 170] }],
  ["biere_brune", "Bière brune", BOUTEILLE, { G: [96, 52, 22], H: [160, 100, 50], g: [60, 30, 10], C: [180, 40, 40], L: [230, 210, 160], l: [120, 40, 30] }],
  ["biere_86", "Bière 8.6", BOUTEILLE, { G: [200, 140, 30], H: [250, 210, 110], g: [140, 90, 20], C: [30, 30, 34], L: [30, 30, 34], l: [240, 196, 60] }],
  ["vin_rouge", "Vin rouge", BOUTEILLE, { G: [40, 70, 40], H: [90, 130, 90], g: [20, 40, 20], C: [140, 20, 40], L: [245, 240, 228], l: [140, 20, 40] }],
  ["pastis", "Pastis", BOUTEILLE, { G: [236, 214, 90], H: [255, 245, 190], g: [190, 160, 50], C: [30, 80, 170], L: [30, 80, 170], l: [240, 196, 60] }],
  ["rhum", "Rhum", BOUTEILLE, { G: [140, 70, 25], H: [200, 130, 60], g: [90, 40, 10], C: [30, 30, 34], L: [230, 200, 140], l: [30, 30, 34] }],
  ["coca", "Coca", CANETTE, { ...ALU, R: [200, 20, 30], W: [245, 245, 245] }],
  ["fanta", "Fanta", CANETTE, { ...ALU, R: [250, 130, 20], W: [30, 80, 200] }],
  ["ice_tea", "Ice Tea pêche", CANETTE, { ...ALU, R: [240, 180, 60], W: [200, 80, 40] }],
];

// la chope de l'effet « Ivresse » (18 x 18)
const CHOPE = [
  "..................",
  "...TTT.TTTT.......",
  "..TTTTTTTTTTT.....",
  "..TTTTTTTTTTT.....",
  "..KYYYYYYYYYK.....",
  "..KYyYYyYYYYKKKK..",
  "..KYYYYYYyYYK..K..",
  "..KYYyYYYYYYK..K..",
  "..KYYYYYYyYYK..K..",
  "..KYyYYYYYYYK..K..",
  "..KYYYYYYYyYKKKK..",
  "..KYYYyYYYYYK.....",
  "..KYYYYYYYYYK.....",
  "..KKKKKKKKKKK.....",
  "..................",
  "..................",
  "..................",
  ".................."];

const ing = (...ids) => ids.map((id) => ({ item: id }));
const RECETTES = {
  biere_blonde: ing("minecraft:glass_bottle", "minecraft:wheat", "minecraft:wheat", "minecraft:sugar"),
  biere_brune: ing("minecraft:glass_bottle", "minecraft:wheat", "minecraft:wheat", "minecraft:cocoa_beans"),
  biere_86: ing("minecraft:glass_bottle", "minecraft:wheat", "minecraft:wheat", "minecraft:sugar", "minecraft:sugar", "minecraft:fermented_spider_eye"),
  vin_rouge: ing("minecraft:glass_bottle", "minecraft:sweet_berries", "minecraft:sweet_berries", "minecraft:sweet_berries"),
  pastis: ing("minecraft:glass_bottle", "minecraft:sugar", "minecraft:fern", "minecraft:fern"),
  rhum: ing("minecraft:glass_bottle", "minecraft:sugar_cane", "minecraft:sugar_cane", "minecraft:sugar_cane", "minecraft:sugar"),
  coca: ing("minecraft:iron_nugget", "minecraft:sugar", "minecraft:sugar", "minecraft:cocoa_beans"),
  fanta: ing("minecraft:iron_nugget", "minecraft:sugar", "minecraft:sugar", "minecraft:orange_dye"),
  ice_tea: ing("minecraft:iron_nugget", "minecraft:sugar", "minecraft:sugar", "minecraft:ice"),
};

// chez Mouloud : [fichier, objet, nombre, prix]
export const OFFRES_BOISSONS = [
  ["biere_blonde", "cabane:biere_blonde", 1, 15], ["biere_brune", "cabane:biere_brune", 1, 18], ["biere_86", "cabane:biere_86", 1, 25],
  ["vin_rouge", "cabane:vin_rouge", 1, 30], ["pastis", "cabane:pastis", 1, 35], ["rhum", "cabane:rhum", 1, 40],
  ["coca", "cabane:coca", 1, 8], ["fanta", "cabane:fanta", 1, 8], ["ice_tea", "cabane:ice_tea", 1, 8],
];
// le marché rachète aussi la production des brasseurs : [fichier, objet, min, max, prix unitaire]
export const CONTRATS_BOISSONS = [
  ["biere_blonde", "cabane:biere_blonde", 4, 12, 22], ["biere_brune", "cabane:biere_brune", 4, 12, 26],
  ["vin_rouge", "cabane:vin_rouge", 2, 8, 40], ["coca", "cabane:coca", 6, 16, 12],
];

export function genererBoissons({ ecrire, png, lang }) {
  for (const [id, nom, motif, palette] of BOISSONS) {
    ecrire(`assets/cabane/textures/item/${id}.png`, png(motif, palette));
    ecrire(`assets/cabane/models/item/${id}.json`, { parent: "minecraft:item/generated", textures: { layer0: `cabane:item/${id}` } });
    lang[`item.cabane.${id}`] = nom;
    ecrire(`data/cabane/recipe/${id}.json`, { type: "minecraft:crafting_shapeless", category: "misc", group: "boissons",
      ingredients: RECETTES[id], result: { id: `cabane:${id}`, count: 1 } });
  }
  ecrire("assets/cabane/textures/mob_effect/ivresse.png", png(CHOPE, { T: [250, 250, 240], Y: [240, 176, 40], y: [255, 220, 120], K: [90, 56, 24] }));
  lang["effect.cabane.ivresse"] = "Ivresse";
}
