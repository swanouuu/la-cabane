// Le McBeauvais : frites, burgers, Filet-O-Fish, nuggets, sundae (textures, recettes, traductions), la borne de
// commande (son icône et son bloc), les objets de l'histoire de Pierre, et les boutiques des habitants
// (Karim, Ovsie, Nyota, Gribbs). Appelé par generer.mjs.

// toutes les grilles font 16 x 16 : on complète ou on coupe au besoin
const g = (lignes) => [...lignes, ...Array(16).fill("")].slice(0, 16).map((l) => l.padEnd(16, ".").slice(0, 16));

const PAIN = { B: [222, 152, 64], b: [168, 102, 36], s: [250, 238, 200], K: [74, 40, 18] };
const STEAK = { M: [110, 58, 32], m: [74, 38, 20] };
const SALADE = { G: [104, 186, 60], H: [70, 140, 40] };
const CHEDDAR = { C: [255, 196, 40], c: [228, 150, 20] };

const PLATS = {
  frites: ["Frites", g([
    "",
    ".....Y..Y.Y.",
    "....YY.YYyY.Y",
    "...YyYYyYYyYY",
    "...yYyYYyYyYy",
    "..KRRRRRRRRRRK",
    "..KRRRRRRRRRRK",
    "...KRRYRRYRRK",
    "...KRYRYYRYRK",
    "...KRYRRRRYRK",
    "...KRRRRRRRRK",
    "....KRRRRRRK",
    "....KrrrrrrK",
    "....KKKKKKKK",
  ]), { Y: [255, 214, 70], y: [232, 160, 30], R: [218, 41, 28], r: [160, 26, 18], K: [60, 14, 10] }],
  hamburger: ["Hamburger", g([
    "",
    "",
    ".....KKKKKK",
    "...KKBBBsBBKK",
    "..KBBsBBBBBsBK",
    "..KBBBBBsBBBBK",
    "..KbbbbbbbbbbK",
    "..HGGHGGGHGGGH",
    "..MMMMMMMMMMMM",
    "..MmMMMmMMMmMM",
    "..KBBBBBBBBBBK",
    "...KbbbbbbbbK",
    "....KKKKKKKK",
  ]), { ...PAIN, ...STEAK, ...SALADE }],
  cheeseburger: ["Cheeseburger", g([
    "",
    "",
    ".....KKKKKK",
    "...KKBBBsBBKK",
    "..KBBsBBBBBsBK",
    "..KBBBBBsBBBBK",
    "..KbbbbbbbbbbK",
    "..CCCCCCCCCCCC",
    "..MMcMMMMMcMMM",
    "..MmMMMmMMMmMM",
    "..KBBBBBBBBBBK",
    "...KbbbbbbbbK",
    "....KKKKKKKK",
  ]), { ...PAIN, ...STEAK, ...CHEDDAR }],
  double_cheese: ["Double Cheese", g([
    "",
    ".....KKKKKK",
    "...KKBBBsBBKK",
    "..KBBsBBBBBsBK",
    "..KbbbbbbbbbbK",
    "..CCCCCCCCCCCC",
    "..MMcMMMMMcMMM",
    "..MmMMMmMMMmMM",
    "..CCCCCCCCCCCC",
    "..MMMMcMMMMMcM",
    "..MmMMMmMMMmMM",
    "..KBBBBBBBBBBK",
    "...KbbbbbbbbK",
    "....KKKKKKKK",
  ]), { ...PAIN, ...STEAK, ...CHEDDAR }],
  big_beauvais: ["Big Beauvais", g([
    ".....KKKKKK",
    "...KKBBsBBBKK",
    "..KBsBBBBsBBBK",
    "..KbbbbbbbbbbK",
    "..HGGHGGGHGGGH",
    "..MMMMMMMMMMMM",
    "..CCCCCCCCCCCC",
    "..KBBBBBBBBBBK",
    "..KbbbbbbbbbbK",
    "..HGGGHGGHGGGH",
    "..MMMMMMMMMMMM",
    "..MmMMMmMMMmMM",
    "..KBBBBBBBBBBK",
    "...KbbbbbbbbK",
    "....KKKKKKKK",
  ]), { ...PAIN, ...STEAK, ...SALADE, ...CHEDDAR }],
  filet_o_fish: ["Filet-O-Fish", g([
    "",
    "",
    "....KKKKKKKK",
    "...KWWWWWWWWK",
    "..KWWWWWWWWWWK",
    "..KwwwwwwwwwwK",
    "..CCCCCC......",
    "..FFFFFFFFFFFF",
    "..FfFFfFFFfFFF",
    "..FFFfFFFfFFFF",
    "..ttttttttttt.",
    "..KWWWWWWWWWWK",
    "...KwwwwwwwwK",
    "....KKKKKKKK",
  ]), { W: [246, 230, 196], w: [214, 190, 150], K: [110, 80, 50], F: [214, 136, 48], f: [176, 100, 30], t: [236, 236, 220], ...CHEDDAR }],
  nuggets: ["Nuggets", g([
    "",
    "",
    "",
    "....NNn.NN",
    "...NNNnNNNNn",
    "...NnNNNnNNN",
    "..KRRRRRRRRRRK",
    "..KRRRRYRRRRRK",
    "..KRRRYRYRRRRK",
    "..KRRYRRRYRRRK",
    "..KRRRRRRRRRRK",
    "..KrrrrrrrrrrK",
    "..KKKKKKKKKKKK",
  ]), { N: [214, 150, 60], n: [168, 104, 30], R: [218, 41, 28], r: [160, 26, 18], Y: [255, 199, 44], K: [60, 14, 10] }],
  sundae: ["Sundae caramel", g([
    "",
    "",
    "......cc",
    ".....cCCc",
    "....cWCWCc",
    "...WWWCWWWW",
    "...WWCWWWCWW",
    "..KKKKKKKKKKK",
    "...KTTTTTTTK",
    "....KTTTTTK",
    ".....KTTTK",
    "......KTK",
    ".....KKKKK",
  ]), { W: [250, 246, 236], C: [204, 120, 40], c: [150, 80, 24], T: [236, 240, 246], K: [150, 160, 176] }],
};

// les objets de l'histoire de Pierre
const LORE = {
  lettre_pierre: ["Lettre de Pierre", g([
    "",
    "",
    "...PPPPPPPPPP",
    "...PlllllllpP",
    "...PPPPPPPPpP",
    "...PllllllPpP",
    "...PPPPPPPPpP",
    "...PlllllllpP",
    "...PPPPPPPPpP",
    "...PllllPPPpP",
    "...PPPPPPPPpP",
    "...PPPPPRPPpP",
    "...pppppppppp",
  ]), { P: [236, 226, 200], p: [196, 182, 150], l: [60, 56, 52], R: [190, 40, 40] }],
  dessin_lucas: ["Dessin de Lucas", g([
    "",
    "..PPPPPPPPPPPP",
    "..PPPPPPPPPYYP",
    "..PPPPPPPPYYYY",
    "..PPPkPPPPPYYP",
    "..PPkkkPPPPPPP",
    "..PPPkPPPPPPPP",
    "..PRkkkRPPPPPP",
    "..PRRkRRPPPPPP",
    "..PRkPkRPPPPPP",
    "..PPkPkPPPPPPP",
    "..PGGGGGGGGGGP",
    "..PGGGGGGGGGGP",
    "..PPPPPPPPPPPP",
  ]), { P: [246, 244, 236], k: [40, 40, 50], R: [210, 40, 40], Y: [255, 210, 40], G: [90, 190, 70] }],
};

const ing = (...ids) => ids.map((id) => ({ item: id }));
const RECETTES = {
  frites: [ing("minecraft:baked_potato", "minecraft:baked_potato", "minecraft:paper"), 2],
  hamburger: [ing("minecraft:bread", "minecraft:cooked_beef"), 1],
  cheeseburger: [ing("minecraft:bread", "minecraft:cooked_beef", "minecraft:milk_bucket"), 2],
  double_cheese: [ing("cabane:cheeseburger", "minecraft:cooked_beef"), 1],
  big_beauvais: [ing("minecraft:bread", "minecraft:bread", "minecraft:cooked_beef", "minecraft:cooked_beef", "minecraft:milk_bucket", "minecraft:dried_kelp"), 2],
  filet_o_fish: [ing("minecraft:bread", "minecraft:cooked_cod", "minecraft:milk_bucket"), 2],
  nuggets: [ing("minecraft:cooked_chicken", "minecraft:wheat"), 1],
  sundae: [ing("minecraft:milk_bucket", "minecraft:sugar", "minecraft:sugar", "minecraft:snowball"), 2],
};

// les boutiques des habitants : [fichier, objet, nombre, prix, vendeur (tag du PNJ), potion]
const BOUTIQUES = [
  // Karim : le matériel d'explorateur
  ["karim_boussole", "minecraft:compass", 1, 60, "pnj.cartographe"], ["karim_carte", "minecraft:map", 1, 40, "pnj.cartographe"],
  ["karim_longue_vue", "minecraft:spyglass", 1, 150, "pnj.cartographe"], ["karim_lanternes", "minecraft:lantern", 4, 50, "pnj.cartographe"],
  ["karim_lit", "minecraft:red_bed", 1, 80, "pnj.cartographe"], ["karim_waystone", "waystones:waystone", 1, 900, "pnj.cartographe"],
  ["karim_sac", "sophisticatedbackpacks:backpack", 1, 450, "pnj.cartographe"],
  // Ovsie : ses petits sorts en bouteille
  ["ovsie_vision", "minecraft:potion", 1, 120, "pnj.fee", "minecraft:night_vision"], ["ovsie_plume", "minecraft:potion", 1, 150, "pnj.fee", "minecraft:slow_falling"],
  ["ovsie_feu", "minecraft:potion", 1, 220, "pnj.fee", "minecraft:fire_resistance"], ["ovsie_vitesse", "minecraft:potion", 1, 150, "pnj.fee", "minecraft:swiftness"],
  ["ovsie_saut", "minecraft:potion", 1, 120, "pnj.fee", "minecraft:leaping"], ["ovsie_eau", "minecraft:potion", 1, 160, "pnj.fee", "minecraft:water_breathing"],
  // Nyota : ses remèdes
  ["nyota_soin", "minecraft:potion", 1, 100, "pnj.guerisseuse", "minecraft:healing"], ["nyota_soin_fort", "minecraft:potion", 1, 220, "pnj.guerisseuse", "minecraft:strong_healing"],
  ["nyota_regeneration", "minecraft:potion", 1, 250, "pnj.guerisseuse", "minecraft:regeneration"], ["nyota_carottes", "minecraft:golden_carrot", 4, 90, "pnj.guerisseuse"],
  ["nyota_miel", "minecraft:honey_bottle", 2, 40, "pnj.guerisseuse"], ["nyota_lait", "minecraft:milk_bucket", 1, 35, "pnj.guerisseuse"],
  // Gribbs : des choses dont il ne faut pas demander la provenance
  ["gribbs_etiquette", "minecraft:name_tag", 1, 90, "pnj.cousin"], ["gribbs_laisse", "minecraft:lead", 2, 40, "pnj.cousin"],
  ["gribbs_montre", "minecraft:clock", 1, 80, "pnj.cousin"], ["gribbs_selle", "minecraft:saddle", 1, 160, "pnj.cousin"],
  ["gribbs_cailloux_orange", "minecraft:orange_terracotta", 16, 40, "pnj.cousin"], ["gribbs_cailloux_cyan", "minecraft:cyan_terracotta", 16, 40, "pnj.cousin"],
];

const morceau = (de, a, texture) => ({ from: de, to: a, faces: Object.fromEntries(["north", "south", "east", "west", "up", "down"].map((f) => [f, { texture }])) });

export function genererFastFood({ ecrire, png, lang }) {
  for (const [id, [nom, grille, palette]] of Object.entries({ ...PLATS, ...LORE })) {
    ecrire(`assets/cabane/textures/item/${id}.png`, png(grille, palette));
    ecrire(`assets/cabane/models/item/${id}.json`, { parent: "minecraft:item/generated", textures: { layer0: `cabane:item/${id}` } });
    lang[`item.cabane.${id}`] = nom;
  }
  for (const [id, [ingredients, nombre]] of Object.entries(RECETTES)) {
    ecrire(`data/cabane/recipe/${id}.json`, { type: "minecraft:crafting_shapeless", category: "misc", group: "mcbeauvais",
      ingredients, result: { id: `cabane:${id}`, count: nombre } });
  }
  // la borne se fabrique aussi (pour bâtir son McBeauvais soi-même) : fer, vitre, redstone, rouge et jaune
  ecrire("data/cabane/recipe/borne_commande.json", { type: "minecraft:crafting_shaped", category: "misc", pattern: ["RYR", "IGI", "IDI"],
    key: { R: { item: "minecraft:red_dye" }, Y: { item: "minecraft:yellow_dye" }, I: { item: "minecraft:iron_ingot" },
      G: { item: "minecraft:glass_pane" }, D: { item: "minecraft:redstone" } }, result: { id: "cabane:borne_commande", count: 1 } });
  // la borne : un bloc dessiné en 3D par le mod ; son modèle ne sert qu'aux particules, son icône est une petite borne
  ecrire("assets/cabane/models/block/borne_commande.json", { textures: { particle: "minecraft:block/white_concrete" } });
  ecrire("assets/cabane/blockstates/borne_commande.json", { variants: Object.fromEntries(
    ["north", "east", "south", "west"].map((f) => [`facing=${f}`, { model: "cabane:block/borne_commande" }])) });
  ecrire("assets/cabane/models/item/borne_commande.json", {
    parent: "minecraft:block/block",
    textures: { blanc: "minecraft:block/white_concrete", noir: "minecraft:block/black_concrete", rouge: "minecraft:block/red_concrete",
      jaune: "minecraft:block/yellow_concrete", gris: "minecraft:block/gray_concrete", particle: "minecraft:block/white_concrete" },
    elements: [
      morceau([3, 0, 4], [13, 1, 12], "#noir"), morceau([4, 1, 6], [12, 13, 10], "#blanc"),
      morceau([3.5, 1, 5.5], [4.5, 14, 10.5], "#gris"), morceau([11.5, 1, 5.5], [12.5, 14, 10.5], "#gris"),
      morceau([5, 4, 5.6], [11, 12, 10.4], "#noir"), morceau([5.6, 10, 5.5], [10.4, 11.5, 10.5], "#rouge"),
      morceau([3.5, 13, 5], [12.5, 16, 11], "#rouge"), morceau([6, 13.8, 4.9], [10, 15.2, 11.1], "#jaune"),
      morceau([5.5, 2.5, 5.2], [10.5, 3.3, 10.8], "#gris"),
    ],
  });
  ecrire("data/cabane/loot_table/blocks/borne_commande.json", { type: "minecraft:block",
    pools: [{ rolls: 1, entries: [{ type: "minecraft:item", name: "cabane:borne_commande" }], conditions: [{ condition: "minecraft:survives_explosion" }] }] });
  lang["block.cabane.borne_commande"] = "Borne de commande McBeauvais";
  BOUTIQUES.forEach(([id, item, nombre, prix, vendeur, potion], i) =>
    ecrire(`data/cabane/boutique/${id}.json`, { item, nombre, prix, ordre: i, vendeur, ...(potion ? { potion } : {}) }));
}
