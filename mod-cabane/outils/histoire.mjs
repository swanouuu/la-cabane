// L'histoire de Beauvais : la clé de la Salle Zéro, le dossier secret, la bibliothèque qui pivote, le pupitre,
// et les succès de l'histoire. Appelé par generer.mjs.

const CLE = [[
  "................",
  "..........KKKK..",
  ".........KYYYYK.",
  "........KYRYYGYK",
  "........KYYKKYYK",
  "........KBYKKYPK",
  "........KYYYYAYK",
  ".........KYYYYK.",
  "........KYKKKK..",
  ".......KYK......",
  "......KYK.......",
  ".....KYK........",
  "....KYKK........",
  "...KYKYK........",
  "..KYK.KK........",
  "...K............"], { K: [40, 25, 10], Y: [240, 196, 60], R: [220, 40, 40], G: [60, 200, 90], B: [60, 110, 230], P: [170, 70, 220], A: [80, 220, 230] }];

const DOSSIER = [[
  "................",
  ".cccccc.........",
  ".cCCCCCcccccccc.",
  ".cTTTTTTTTTTTTc.",
  ".cCCCCCCCCCCCCc.",
  ".cCCCCCCCCCCCCc.",
  ".cCRRRRRRRRRRCc.",
  ".cCRTRTRRTRTRCc.",
  ".cCRRRRRRRRRRCc.",
  ".cCCCCCCCCCCCCc.",
  ".cCCNNNNNNNNCCc.",
  ".cCCCCCCCCCCCCc.",
  ".cCCNNNNNNCCCCc.",
  ".cCCCCCCCCCCCCc.",
  ".cccccccccccccc.",
  "................"], { C: [205, 170, 120], c: [150, 116, 70], T: [245, 240, 228], R: [190, 30, 30], N: [50, 40, 30] }];

const morceau = (de, a, texture) => ({ from: de, to: a, faces: Object.fromEntries(["north", "south", "east", "west", "up", "down"].map((f) => [f, { texture }])) });
const facing = (modele) => ({ variants: Object.fromEntries(
  [["north", 0], ["east", 90], ["south", 180], ["west", 270]].map(([f, y]) => [`facing=${f}`, { model: modele, ...(y ? { y } : {}) }])) });

export function genererHistoire({ ecrire, png, lang }) {
  for (const [nom, [grille, palette]] of Object.entries({ cle_salle_zero: CLE, dossier_secret: DOSSIER })) {
    ecrire(`assets/cabane/textures/item/${nom}.png`, png(grille, palette));
    ecrire(`assets/cabane/models/item/${nom}.json`, { parent: "minecraft:item/generated", textures: { layer0: `cabane:item/${nom}` } });
  }

  // la bibliothèque secrète ressemble trait pour trait à une vraie (ouverte, elle ne se dessine plus du tout)
  ecrire("assets/cabane/blockstates/bibliotheque_secrete.json", { variants: {
    "ouverte=false": { model: "minecraft:block/bookshelf" }, "ouverte=true": { model: "minecraft:block/bookshelf" } } });
  ecrire("assets/cabane/models/item/bibliotheque_secrete.json", { parent: "minecraft:block/bookshelf" });

  // le pupitre : un socle de pierre noire, une colonne dorée, un plateau d'or et la serrure qui pleure
  ecrire("assets/cabane/models/block/pupitre_president.json", {
    parent: "minecraft:block/block",
    textures: { noir: "minecraft:block/polished_blackstone", dore: "minecraft:block/gilded_blackstone", or: "minecraft:block/gold_block",
      serrure: "minecraft:block/crying_obsidian", particle: "minecraft:block/polished_blackstone" },
    elements: [
      morceau([1, 0, 1], [15, 2, 15], "#noir"), morceau([3, 2, 3], [13, 3, 13], "#dore"),
      morceau([5, 3, 5], [11, 11, 11], "#dore"), morceau([2, 11, 2], [14, 13, 14], "#or"),
      morceau([3, 13, 3], [13, 14, 13], "#noir"), morceau([6, 14, 7], [10, 14.5, 9], "#serrure"),
    ],
  });
  ecrire("assets/cabane/blockstates/pupitre_president.json", facing("cabane:block/pupitre_president"));
  ecrire("assets/cabane/models/item/pupitre_president.json", { parent: "cabane:block/pupitre_president" });

  // les succès de l'histoire (décernés par le mod)
  ecrire("data/cabane/advancement/histoire/laboratoire.json", {
    parent: "cabane:racine",
    display: { icon: { id: "minecraft:brewing_stand" }, title: { text: "Ce que cache la Mairie" },
      description: { text: "Découvrir le laboratoire secret du Président." }, frame: "goal", show_toast: true, announce_to_chat: true },
    criteria: { entree: { trigger: "minecraft:impossible" } },
  });
  ecrire("data/cabane/advancement/histoire/liberation.json", {
    parent: "cabane:sept_reliques",
    display: { icon: { id: "cabane:cle_salle_zero" }, title: { text: "Beauvais libéré !" },
      description: { text: "Vaincre le Président Augmenté dans la Salle Zéro." }, frame: "challenge", show_toast: true, announce_to_chat: true },
    criteria: { victoire: { trigger: "minecraft:impossible" } },
  });

  Object.assign(lang, {
    "block.cabane.bibliotheque_secrete": "Bibliothèque",
    "block.cabane.pupitre_president": "Pupitre de la Salle Zéro",
    "item.cabane.cle_salle_zero": "Clé de la Salle Zéro",
    "item.cabane.dossier_secret": "Dossier « Projet Augmenté »",
  });
}
