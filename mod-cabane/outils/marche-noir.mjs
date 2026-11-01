// Le marché noir : graines, plantes, les six postes des labos, joints, hash, coke et meth (textures, modèles, butins, recettes,
// traductions) et les offres du Fourgue. Appelé par generer.mjs ; la logique est dans drogue/MarcheNoir.java.

const ITEMS = {
  graine_weed: ["Graine de weed", [
    "................", "................", "................", "......SS........", ".....SssS.......", ".....SssS...SS..",
    "......SS...SssS.", "............SS..", "...SS...........", "..SssS..........", "..SssS....SS....", "...SS....SssS...",
    ".........SssS...", "..........SS....", "................", "................"], { S: [70, 52, 30], s: [130, 104, 60] }],
  graine_coca: ["Graine de coca", [
    "................", "................", "................", "......RR........", ".....RrrR.......", ".....RrrR...RR..",
    "......RR...RrrR.", "............RR..", "...RR...........", "..RrrR..........", "..RrrR....RR....", "...RR....RrrR...",
    ".........RrrR...", "..........RR....", "................", "................"], { R: [120, 30, 26], r: [200, 70, 50] }],
  tete_weed: ["Weed fraîche", [
    "................", ".......G........", "......GgG.......", "..G...GgG...G...", "..GG..GgG..GG...", "...GG.GgG.GG....",
    "G...GGGgGGG...G.", ".GGg.GGgGG.gGG..", "..GGGgGgGgGGG...", "....GGGgGGG.....", "..GGGGGgGGGGG...", ".GG....g....GG..",
    "G......g......G.", ".......g........", ".......T........", "................"], { G: [46, 120, 40], g: [120, 190, 80], T: [100, 80, 40] }],
  weed_sechee: ["Weed séchée", [
    "................", "................", ".......G........", "......GgG.......", ".....GgOgG......", "....GgggOgG.....",
    "....GOgggggG....", "...GgggOgggG....", "...GggggggOgG...", "....GgOgggggG...", "....GgggOgggG...", ".....GgggOgG....",
    "......GgggG.....", ".......TT.......", "................", "................"], { G: [96, 110, 50], g: [150, 160, 80], O: [220, 130, 50], T: [110, 90, 50] }],
  feuille_coca: ["Feuille de coca", [
    "................", "..........GG....", ".........GggG...", "........GggggG..", ".......GggVggG..", "......GggVgggG..",
    ".....GggVgggG...", "....GggVgggG....", "...GggVgggG.....", "..GggVgggG......", "..GgVgggG.......", "..GVgggG........",
    ".TTggGG.........", "TT..............", "................", "................"], { G: [40, 100, 40], g: [80, 160, 60], V: [150, 210, 110], T: [90, 70, 40] }],
  hash: ["Hash", [
    "................", "................", "................", "................", "....HHHHHHHH....", "...HhhhhhhhhH...",
    "..HhHHHHHHHHhH..", "..HhHddHHddHhH..", "..HhHHHHHHHHhH..", "..HhHHddHHHdhH..", "...HhhhhhhhhH...", "....HHHHHHHH....",
    "................", "................", "................", "................"], { H: [90, 58, 30], h: [140, 96, 52], d: [60, 36, 18] }],
  joint: ["Joint", [
    "................", "..............RO", ".............WRR", "............WWR.", "...........WWW..", "..........WWW...",
    ".........WWw....", "........WWw.....", ".......WWw......", "......WWw.......", ".....WWw........", "....WWw.........",
    "...TTw..........", "..TT............", "................", "................"], { W: [240, 236, 226], w: [200, 196, 186], R: [230, 90, 40], O: [255, 190, 80], T: [196, 160, 110] }],
  joint_hash: ["Joint de hash", [
    "................", "..............RO", ".............BRR", "............WBR.", "...........WWB..", "..........WWW...",
    ".........WWw....", "........WWw.....", ".......WWw......", "......WWw.......", ".....WWw........", "....WWw.........",
    "...TTw..........", "..TT............", "................", "................"], { W: [240, 236, 226], w: [200, 196, 186], B: [120, 80, 40], R: [230, 90, 40], O: [255, 190, 80], T: [196, 160, 110] }],
  cocaine: ["Cocaïne", [
    "................", "................", "................", "................", "................", "................",
    "........W.......", "......WWwW......", ".....WwWWWwW....", "....WWWwWWWWW...", "...WwWWWWwWWWW..", "..WWWWwWWWWwWWW.",
    "..wWWWWWWWWWWWw.", "...wwwwwwwwwww..", "................", "................"], { W: [252, 252, 252], w: [214, 220, 230] }],
  pate_coca: ["Pâte de coca", [
    "................", "................", "................", "................", "................", "....BBBBBBBB....",
    "...BbbbbbbbbB...", "..BbYYbbYYbbbB..", "..BbbbYbbbbYbB..", "..BbYbbbYbbbbB..", "..BbbbbbbbbbbB..", "...BBBBBBBBBB...",
    "................", "................", "................", "................"], { B: [150, 130, 90], b: [215, 200, 160], Y: [240, 230, 200] }],
  plaque_meth: ["Plaque de meth", [
    "................", "................", "................", "................", "..TTTTTTTTTTTT..", "..TBBbBBBBbBBT..",
    "..TBCBBbBCBBBT..", "..TBBBCBBBBbBT..", "..TbBBBBBCBBBT..", "..TBBCBbBBBBBT..", "..TBBBBBBbBCBT..", "..TTTTTTTTTTTT..",
    "................", "................", "................", "................"], { T: [150, 150, 160], B: [60, 150, 220], b: [120, 210, 245], C: [220, 250, 255] }],
  meth: ["Cristaux bleus", [
    "................", "................", "......B.........", ".....BbB........", "....BbCbB...B...", "....BCbbB..BbB..",
    ".....BbB..BbCbB.", "..B...B...BCbB..", ".BbB.......BB...", "BbCbB...B.......", ".BbB...BbB......", "..B...BbCbB.....",
    "......BCbbB.....", ".......BB.......", "................", "................"], { B: [40, 120, 200], b: [110, 200, 240], C: [220, 250, 255] }],
};

// les plantes : quatre stades (germe, jeune pousse, plante, plante en fleurs / chargée)
const STADES = [
  ["................", "................", "................", "................", "................", "................",
   "................", "................", "................", "................", "................", "....G......G....",
   "...GgG....GgG...", "....T..G...T....", "....T.GgG..T....", "....T..T...T...."],
  ["................", "................", "................", "................", "................", "................",
   "................", "..G........G....", ".GgG..G...GgG...", "..T..GgG...T..G.", "..T...T....T.GgG", "G.T...T..G.T..T.",
   "gGT..GT.GgGT..T.", "..T.GgT..T.T..T.", "..T...T..T.T..T.", "..T...T..T.T..T."],
  ["................", "................", "...G.......G....", "..GgG..G..GgG...", ".GgggG.GgGgggG..", "..GgG.GgggGgG...",
   "...T...GgG..T...", ".G.T.G..T...T.G.", "GgGT.GgGT..GTGgG", ".G.TGgggT.GgT.G.", "...T.GgGTGggGT..", "..GT..T.T.GgGT..",
   ".GgT..T.T..T.T..", "..GT..T.T..T.T..", "...T..T.T..T.T..", "...T..T.T..T.T.."],
  ["...F.......F....", "..FgF..F..FgF...", ".GgFgG.FG.GgFG..", "..GgFGGgFGgggG..", ".GgggGGgFgGgFgG.", "..GgFGGgggGgG...",
   "...T.GGgFG..T...", ".G.T.G.GT...T.G.", "GgFT.GgFT..GTgFG", ".G.TGgFgT.GgT.G.", "...T.GgGTGgFGT..", "..GT..T.T.GgGT..",
   ".GgT..T.T..T.T..", "..GT..T.T..T.T..", "...T..T.T..T.T..", "...T..T.T..T.T.."],
];
// la weed : des feuilles en éventail à sept doigts
const STADES_WEED = [
  STADES[0], STADES[1],
  ["................", "................", "................", "................", "....G.......G...", "..G.GgG...GgG.G.",
   "...GgGgG.GgGgG..", ".GgGGgGTGgGGgG..", "...GGgGTGgGG....", ".....GGTGG......", "...G.GGTGG.G....", "..GgG..T..GgG...",
   "...GgG.T.GgG....", ".....GGTGG......", ".......T........", ".......T........"],
  ["......F.F.......", "....FgFGFgF.....", "..G.GgGFGgG.G...", ".GgGGgGgGgGGgG..", "GgGgGGFTFGGgGgG.", ".GGgGgGTGgGgGG..",
   "..GGGgGTGgGGG...", ".G..GGgTgGG..G..", "GgG.GgGTGgG.GgG.", ".GgGGgGTGgGGgG..", "..GGgGGTGGgGG...", "...GGG.T.GGG....",
   ".G..GgGTGgG..G..", "GgGG.GGTGG.GGgG.", ".GG....T....GG..", ".......T........"],
];
const PLANTES = {
  plant_weed: { G: [50, 120, 40], g: [100, 180, 70], T: [70, 110, 40], F: [235, 150, 60] },
  plant_coca: { G: [30, 100, 40], g: [70, 150, 60], T: [100, 80, 50], F: [210, 40, 40] },
};

// les icônes des effets (18 x 18)
const ICONE = (motif) => motif.map((l) => "." + l + ".");
const EFFETS = {
  planant: ["Planant", ICONE(["................", "......GG........", ".....GggG.G.....", "..G..GggGGgG....", ".GgG..GgGGgG..G.", ".GggG.GgGgG..GgG",
    "..GggGGgGgGGGgG.", "...GgggGggGggG..", "....GGgggggGG...", "......GGgGG.....", ".......GTG......", "........T.......",
    "........T.......", "........T.......", "................", "................"]), { G: [50, 130, 40], g: [110, 190, 70], T: [80, 110, 40] }],
  defonce: ["Défonce", ICONE(["................", ".......YY.......", "...Y...YY...Y...", "....Y..YY..Y....", ".....YYYYYY.....", "..YYYYwwwwYYYY..",
    ".....YwWWwY.....", "YYYYYwWWWwYYYYY.", ".....YwWWwY.....", "..YYYYwwwwYYYY..", ".....YYYYYY.....", "....Y..YY..Y....",
    "...Y...YY...Y...", ".......YY.......", "................", "................"]), { Y: [250, 220, 60], w: [255, 250, 200], W: [255, 255, 255] }],
  descente: ["Descente", ICONE(["................", "......KKKK......", "....KKPPPPKK....", "...KPPPPPPPPK...", "..KPPWWPPWWPPK..", "..KPPWKPPWKPPK..",
    "..KPPPPPPPPPPK..", "..KPPPPPPPPPPK..", "..KPPPKKKKPPPK..", "..KPPKPPPPKPPK..", "...KPPPPPPPPK...", "....KKPPPPKK....",
    "......KKKK......", "......B.........", ".....B..........", "................"]), { K: [40, 30, 50], P: [120, 100, 140], W: [230, 230, 240], B: [90, 140, 220] }],
};

const morceau = (de, a, texture) => ({ from: de, to: a, faces: Object.fromEntries(["north", "south", "east", "west", "up", "down"].map((f) => [f, { texture }])) });

/** Les offres du Fourgue : [fichier, objet, nombre, prix]. */
export const OFFRES_NOIR = [
  ["noir_graine_weed", "cabane:graine_weed", 4, 200], ["noir_graine_coca", "cabane:graine_coca", 4, 450],
  ["noir_papier", "minecraft:paper", 16, 40],
  ["noir_sechoir", "cabane:sechoir", 1, 1500], ["noir_presse_hash", "cabane:presse_hash", 1, 2500],
  ["noir_bac_maceration", "cabane:bac_maceration", 1, 2000], ["noir_table_raffinage", "cabane:table_raffinage", 1, 4000],
  ["noir_reacteur", "cabane:reacteur", 1, 6000], ["noir_table_decoupe", "cabane:table_decoupe", 1, 3000],
];

export function genererMarcheNoir({ ecrire, png, lang }) {
  for (const [id, [nom, motif, palette]] of Object.entries(ITEMS)) {
    ecrire(`assets/cabane/textures/item/${id}.png`, png(motif, palette));
    ecrire(`assets/cabane/models/item/${id}.json`, { parent: "minecraft:item/generated", textures: { layer0: `cabane:item/${id}` } });
    lang[`item.cabane.${id}`] = nom;
  }
  for (const [id, palette] of Object.entries(PLANTES)) {
    (id === "plant_weed" ? STADES_WEED : STADES).forEach((motif, i) => {
      ecrire(`assets/cabane/textures/block/${id}_${i}.png`, png(motif, palette));
      ecrire(`assets/cabane/models/block/${id}_${i}.json`, { parent: "minecraft:block/crop", render_type: "minecraft:cutout", textures: { crop: `cabane:block/${id}_${i}` } });
    });
    ecrire(`assets/cabane/blockstates/${id}.json`, { variants: Object.fromEntries(
      Array.from({ length: 8 }, (_, age) => [`age=${age}`, { model: `cabane:block/${id}_${Math.min(3, Math.floor(age / 2))}` }])) });
  }
  // la récolte : mûre, la plante donne sa récolte et des graines ; sinon, juste sa graine
  const mure = (bloc) => [{ condition: "minecraft:block_state_property", block: `cabane:${bloc}`, properties: { age: "7" } }];
  for (const [bloc, graine, recolte] of [["plant_weed", "graine_weed", "tete_weed"], ["plant_coca", "graine_coca", "feuille_coca"]]) {
    ecrire(`data/cabane/loot_table/blocks/${bloc}.json`, { type: "minecraft:block", pools: [
      { rolls: 1, entries: [{ type: "minecraft:alternatives", children: [
        { type: "minecraft:item", name: `cabane:${recolte}`, conditions: mure(bloc),
          functions: [{ function: "minecraft:set_count", count: { type: "minecraft:uniform", min: 2, max: 4 } }] },
        { type: "minecraft:item", name: `cabane:${graine}` }] }] },
      { rolls: 1, conditions: mure(bloc), entries: [{ type: "minecraft:item", name: `cabane:${graine}`,
        functions: [{ function: "minecraft:set_count", count: { type: "minecraft:uniform", min: 1, max: 2 } }] }] },
    ] });
  }

  // les postes des labos : deux par produit, chacun en 3D (la marchandise qui travaille est dessinée par PosteRenderer)
  const pieds = (bois, h) => [[0, 0], [14, 0], [0, 14], [14, 14]].map(([x, z]) => morceau([x, 0, z], [x + 2, h, z + 2], bois));
  const b = (de, a, t) => morceau(de, a, t);
  const POSTES = {
    // le séchoir : un long étendoir à trois poteaux, deux cordes, des bottes de weed pendues (fraîches en bas, sèches en haut)
    sechoir: ["Séchoir à weed", { bois: "minecraft:block/spruce_log", planche: "minecraft:block/spruce_planks", corde: "minecraft:block/white_wool",
      frais: "minecraft:block/azalea_leaves", sec: "minecraft:block/moss_block", caisse: "minecraft:block/stripped_spruce_log" }, "cutout", [
      ...[-13, 8, 29].flatMap((x) => [b([x - 3, 0, 3], [x + 3, 1, 13], "#planche"), b([x - 1, 1, 7], [x + 1, 31, 9], "#bois")]),
      b([-15, 29, 7], [31, 31, 9], "#bois"),
      b([-13, 21, 7.75], [29, 21.5, 8.25], "#corde"), b([-13, 12, 7.75], [29, 12.5, 8.25], "#corde"),
      ...[-11, -6, -1, 3, 12, 17, 22, 26].flatMap((x, k) => [
        b([x, 16 + (k % 2), 6.5], [x + 3, 20.5, 9.5], "#sec"), b([x + 1.25, 20.5, 7.9], [x + 1.75, 21, 8.1], "#corde"),
        b([x + (k % 2), 7, 6.5], [x + 3, 11.5, 9.5], "#frais"), b([x + 1.25, 11.5, 7.9], [x + 1.75, 12, 8.1], "#corde")]),
      b([1, 0, 2], [15, 5, 14], "#caisse"), b([2, 5, 3], [14, 6, 13], "#frais")]],
    // la presse : un bâti d'acier, deux colonnes, une grosse vis, un plateau qui écrase la weed en briques de hash
    presse_hash: ["Presse à hash", { acier: "minecraft:block/iron_block", noir: "minecraft:block/polished_blackstone", plateau: "minecraft:block/smooth_stone",
      lourd: "minecraft:block/netherite_block", bois: "minecraft:block/stripped_spruce_log", hash: "minecraft:block/brown_terracotta" }, null, [
      b([-15, 0, 1], [15, 3, 15], "#acier"), b([-11, 3, 3], [11, 5, 13], "#plateau"),
      b([-15, 3, 5], [-11, 31, 11], "#noir"), b([11, 3, 5], [15, 31, 11], "#noir"), b([-15, 27, 4], [15, 31, 12], "#noir"),
      b([-1.5, 16, 6.5], [1.5, 27, 9.5], "#acier"), b([-10, 13, 3], [10, 16, 13], "#lourd"),
      b([-12, 31, 7.5], [12, 32, 8.5], "#bois"), b([-0.5, 31, -4], [0.5, 32, 20], "#bois"),
      b([-14, 3, 0.5], [-11, 5, 2.5], "#hash"), b([-14, 5, 0.5], [-12, 6.5, 2.5], "#hash"), b([11.5, 3, 13.5], [14.5, 5, 15.5], "#hash")]],
    // la macération : deux grands bacs cerclés (les feuilles, la pâte), une caisse de feuilles et des sacs de sucre
    bac_maceration: ["Bac de macération", { planche: "minecraft:block/spruce_planks", fer: "minecraft:block/iron_block", jus: "minecraft:block/green_concrete_powder",
      pate: "minecraft:block/brown_concrete_powder", feuilles: "minecraft:block/azalea_leaves", caisse: "minecraft:block/stripped_oak_log", sac: "minecraft:block/white_wool" }, "cutout", [
      ...[[1, 1, "#jus"], [-15, 17, "#pate"]].flatMap(([x, z, liquide]) => [
        b([x, 0, z], [x + 14, 1, z + 14], "#planche"),
        b([x, 1, z], [x + 14, 14, z + 1], "#planche"), b([x, 1, z + 13], [x + 14, 14, z + 14], "#planche"),
        b([x, 1, z + 1], [x + 1, 14, z + 13], "#planche"), b([x + 13, 1, z + 1], [x + 14, 14, z + 13], "#planche"),
        b([x - 0.3, 2, z - 0.3], [x + 14.3, 3, z + 14.3], "#fer"), b([x - 0.3, 10, z - 0.3], [x + 14.3, 11, z + 14.3], "#fer"),
        b([x + 1, 11.5, z + 1], [x + 13, 12.2, z + 13], liquide)]),
      b([-15, 0, 2], [-1, 8, 14], "#caisse"), b([-14, 8, 3], [-2, 9.5, 13], "#feuilles"),
      b([2, 0, 18], [8, 6, 24], "#sac"), b([8, 0, 23], [14, 8, 30], "#sac"), b([3, 6, 19], [7, 7, 23], "#sac")]],
    // le raffinage : un grand établi blanc, deux plateaux de poudre, une balance, des flacons, un brûleur, une lampe
    table_raffinage: ["Table de raffinage", { blanc: "minecraft:block/quartz_block_top", fer: "minecraft:block/iron_block", poudre: "minecraft:block/white_concrete_powder",
      verre: "minecraft:block/glass", vert: "minecraft:block/lime_stained_glass", bouleau: "minecraft:block/birch_planks", carton: "minecraft:block/stripped_birch_log",
      feu: "minecraft:block/blackstone", lampe: "minecraft:block/glowstone" }, "translucent", [
      ...[[-15, 1], [-15, 29], [29, 1], [29, 29], [7, 1], [7, 29]].map(([x, z]) => b([x, 0, z], [x + 2, 13, z + 2], "#fer")),
      b([-16, 13, 0], [32, 15, 32], "#blanc"), b([-14, 4, 2], [30, 5, 30], "#bouleau"),
      b([-10, 5, 5], [-2, 10, 13], "#carton"), b([18, 5, 18], [26, 9, 26], "#carton"),
      b([-12, 15, 3], [4, 15.6, 13], "#poudre"), b([12, 15, 3], [28, 15.6, 13], "#poudre"),
      b([18, 15, 18], [24, 16, 24], "#fer"), b([20.5, 16, 20.5], [21.5, 19, 21.5], "#fer"), b([17, 19, 19], [25, 19.4, 23], "#fer"),
      b([-12, 15, 20], [-9, 21, 23], "#verre"), b([-7, 15, 22], [-4, 19, 25], "#verre"), b([-2, 15, 20], [1, 20, 23], "#vert"),
      b([5, 15, 22], [9, 17, 26], "#feu"),
      b([28, 15, 28], [29, 26, 29], "#fer"), b([25, 26, 25], [31, 28, 31], "#lampe")]],
    // le réacteur : une plateforme d'acier, une grande cuve de verre cerclée de cuivre, un dôme, une cheminée, des tuyaux,
    // un pupitre de commande et des bidons de produits
    reacteur: ["Réacteur de cuisson", { acier: "minecraft:block/iron_block", feu: "minecraft:block/magma", cuve: "minecraft:block/light_blue_stained_glass",
      cuivre: "minecraft:block/copper_block", vert: "minecraft:block/lime_stained_glass", jaune: "minecraft:block/yellow_stained_glass",
      pupitre: "minecraft:block/polished_andesite", voyant: "minecraft:block/redstone_lamp_on" }, "translucent", [
      b([-16, 0, 0], [16, 2, 32], "#acier"), b([-8, 2, 8], [8, 4, 24], "#feu"),
      b([-10, 4, 6], [10, 24, 26], "#cuve"),
      b([-10.5, 8, 5.5], [10.5, 9, 26.5], "#cuivre"), b([-10.5, 19, 5.5], [10.5, 20, 26.5], "#cuivre"),
      b([-8, 24, 8], [8, 27, 24], "#cuivre"), b([-2, 27, 14], [2, 32, 18], "#cuivre"),
      b([10, 20, 14], [16, 22, 17], "#cuivre"), b([13, 2, 14], [16, 20, 17], "#cuivre"),
      b([11, 2, 1], [15, 14, 5], "#pupitre"), b([11.5, 10, 0.5], [14.5, 13, 1], "#voyant"),
      b([-15, 2, 27], [-12, 10, 31], "#vert"), b([-15, 2, 22], [-12, 8, 26], "#jaune")]],
    // la découpe : une longue table d'acier, deux planches à découper, un tas de cristaux bleus, un couteau, une balance
    table_decoupe: ["Table de découpe", { pied: "minecraft:block/dark_oak_log", plateau: "minecraft:block/smooth_stone", bouleau: "minecraft:block/birch_planks",
      cristal: "minecraft:block/light_blue_stained_glass", lame: "minecraft:block/iron_block", manche: "minecraft:block/black_concrete" }, "translucent", [
      ...[[-15, 2], [-15, 12], [29, 2], [29, 12]].map(([x, z]) => b([x, 0, z], [x + 2, 13, z + 2], "#pied")),
      b([-16, 13, 1], [32, 15, 15], "#plateau"), b([-14, 4, 2], [30, 5, 14], "#bouleau"),
      b([-12, 15, 3], [2, 15.5, 13], "#bouleau"), b([14, 15, 3], [28, 15.5, 13], "#bouleau"),
      b([18, 15.5, 5], [21, 17, 8], "#cristal"), b([21, 15.5, 6], [24, 16.5, 9], "#cristal"), b([19, 15.5, 9], [22, 16, 11], "#cristal"),
      b([4, 15, 11], [9, 15.3, 11.6], "#lame"), b([9, 15, 10.8], [12, 15.6, 11.8], "#manche"),
      b([25, 15, 10], [30, 16, 14], "#lame"), b([25.5, 17, 10.5], [29.5, 17.3, 13.5], "#lame"), b([27.25, 16, 11.75], [27.75, 17, 12.25], "#lame")]],
  };
  for (const [id, [nom, textures, rendu, elements]] of Object.entries(POSTES)) {
    ecrire(`assets/cabane/models/block/${id}.json`, { parent: "minecraft:block/block", ...(rendu ? { render_type: `minecraft:${rendu}` } : {}),
      textures: { ...textures, particle: Object.values(textures)[0] }, elements });
    // dans l'inventaire, le grand poste est réduit pour tenir dans sa case
    ecrire(`assets/cabane/models/item/${id}.json`, { parent: `cabane:block/${id}`, display: {
      gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.32, 0.32, 0.32] },
      ground: { translation: [0, 3, 0], scale: [0.2, 0.2, 0.2] },
      firstperson_righthand: { rotation: [0, 45, 0], scale: [0.25, 0.25, 0.25] }, thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.2, 0.2, 0.2] },
      fixed: { scale: [0.3, 0.3, 0.3] } } });
    const variantes = {};
    for (const [f, y] of [["north", 0], ["east", 90], ["south", 180], ["west", 270]]) {
      for (const actif of [false, true]) variantes[`actif=${actif},facing=${f}`] = { model: `cabane:block/${id}`, ...(y ? { y } : {}) };
    }
    ecrire(`assets/cabane/blockstates/${id}.json`, { variants: variantes });
    ecrire(`data/cabane/loot_table/blocks/${id}.json`, { type: "minecraft:block", pools: [{ rolls: 1, entries: [{ type: "minecraft:item", name: `cabane:${id}` }] }] });
    lang[`block.cabane.${id}`] = nom;
  }

  // les joints se roulent à la main
  for (const [id, herbe] of [["joint", "cabane:weed_sechee"], ["joint_hash", "cabane:hash"]]) {
    ecrire(`data/cabane/recipe/${id}.json`, { type: "minecraft:crafting_shapeless", category: "misc",
      ingredients: [{ item: herbe }, { item: "minecraft:paper" }], result: { id: `cabane:${id}`, count: 1 } });
  }

  for (const [id, [nom, motif, palette]] of Object.entries(EFFETS)) {
    ecrire(`assets/cabane/textures/mob_effect/${id}.png`, png(motif, palette));
    lang[`effect.cabane.${id}`] = nom;
  }
  Object.assign(lang, {
    "block.cabane.plant_weed": "Plant de weed", "block.cabane.plant_coca": "Plant de coca",
  });
}
