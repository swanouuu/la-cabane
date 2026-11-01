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
  const POSTES = {
    sechoir: ["Séchoir à weed", { bois: "minecraft:block/spruce_log", planche: "minecraft:block/spruce_planks", fil: "minecraft:block/white_wool",
      feuille: "minecraft:block/azalea_leaves" }, false, [
      morceau([0, 0, 4], [3, 1, 12], "#planche"), morceau([13, 0, 4], [16, 1, 12], "#planche"),
      morceau([1, 1, 7], [2.5, 14, 9], "#bois"), morceau([13.5, 1, 7], [15, 14, 9], "#bois"),
      morceau([1, 12.5, 7.4], [15, 13.5, 8.6], "#bois"),
      ...[3, 6, 9, 12].map((x) => morceau([x, 10.5, 7.9], [x + 0.3, 12.5, 8.1], "#fil")),
      morceau([2.5, 2, 6], [13.5, 2.5, 10], "#planche"), morceau([4, 2.5, 6.5], [7, 3.5, 9.5], "#feuille"), morceau([9, 2.5, 6.5], [12, 3.2, 9.5], "#feuille")]],
    presse_hash: ["Presse à hash", { fer: "minecraft:block/iron_block", sombre: "minecraft:block/anvil", plateau: "minecraft:block/smooth_stone",
      bois: "minecraft:block/stripped_spruce_log" }, false, [
      morceau([1, 0, 1], [15, 3, 15], "#fer"), morceau([2, 3, 7], [4, 15, 9], "#sombre"), morceau([12, 3, 7], [14, 15, 9], "#sombre"),
      morceau([2, 13, 6.5], [14, 15, 9.5], "#sombre"), morceau([7.25, 8, 7.25], [8.75, 13, 8.75], "#fer"),
      morceau([4, 7, 4], [12, 8, 12], "#plateau"), morceau([2, 15, 7.5], [14, 15.6, 8.5], "#bois")]],
    bac_maceration: ["Bac de macération", { planche: "minecraft:block/spruce_planks", fer: "minecraft:block/iron_block",
      jus: "minecraft:block/green_concrete_powder" }, false, [
      morceau([1, 0, 1], [15, 1, 15], "#planche"), morceau([1, 1, 1], [15, 13, 2], "#planche"), morceau([1, 1, 14], [15, 13, 15], "#planche"),
      morceau([1, 1, 2], [2, 13, 14], "#planche"), morceau([14, 1, 2], [15, 13, 14], "#planche"),
      morceau([0.8, 2.5, 0.8], [15.2, 3.2, 15.2], "#fer"), morceau([0.8, 10, 0.8], [15.2, 10.7, 15.2], "#fer"),
      morceau([2, 11.5, 2], [14, 12.2, 14], "#jus")]],
    table_raffinage: ["Table de raffinage", { bois: "minecraft:block/birch_planks", plateau: "minecraft:block/white_concrete",
      fer: "minecraft:block/iron_block", verre: "minecraft:block/glass" }, true, [
      ...pieds("#bois", 13), morceau([0, 13, 0], [16, 15, 16], "#bois"), morceau([3, 15, 3], [13, 15.4, 13], "#plateau"),
      morceau([12.5, 15, 11.5], [14.5, 15.5, 13.5], "#fer"), morceau([13.25, 15.5, 12.25], [13.75, 17, 12.75], "#fer"),
      morceau([12, 17, 11.75], [15, 17.3, 13.25], "#fer"), morceau([1, 15, 1], [3, 18, 3], "#verre"), morceau([3.5, 15, 1], [5, 17, 2.5], "#verre")]],
    reacteur: ["Réacteur de cuisson", { acier: "minecraft:block/iron_block", feu: "minecraft:block/magma", ballon: "minecraft:block/light_blue_stained_glass",
      cuivre: "minecraft:block/copper_block", vert: "minecraft:block/lime_stained_glass" }, true, [
      ...pieds("#acier", 11), morceau([0, 11, 0], [16, 13, 16], "#acier"), morceau([5, 13, 5], [11, 14, 11], "#feu"),
      morceau([4, 14, 4], [12, 22, 12], "#ballon"), morceau([7, 22, 7], [9, 25, 9], "#ballon"),
      morceau([9, 23.5, 7.5], [14, 24.5, 8.5], "#cuivre"), morceau([13.5, 15, 7.5], [14.5, 24.5, 8.5], "#cuivre"),
      morceau([12.5, 13, 11], [15.5, 17, 14], "#vert")]],
    table_decoupe: ["Table de découpe", { pied: "minecraft:block/dark_oak_log", plateau: "minecraft:block/smooth_stone", plaque: "minecraft:block/light_gray_concrete",
      lame: "minecraft:block/iron_block", manche: "minecraft:block/black_concrete" }, false, [
      ...pieds("#pied", 13), morceau([0, 13, 0], [16, 15, 16], "#plateau"), morceau([2, 15, 3], [14, 15.3, 13], "#plaque"),
      morceau([11, 15, 14], [15, 15.2, 15], "#lame"), morceau([8.5, 15, 13.9], [11, 15.5, 15.1], "#manche")]],
  };
  for (const [id, [nom, textures, translucide, elements]] of Object.entries(POSTES)) {
    ecrire(`assets/cabane/models/block/${id}.json`, { parent: "minecraft:block/block", ...(translucide ? { render_type: "minecraft:translucent" } : {}),
      textures: { ...textures, particle: Object.values(textures)[0] }, elements });
    ecrire(`assets/cabane/models/item/${id}.json`, { parent: `cabane:block/${id}` });
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
