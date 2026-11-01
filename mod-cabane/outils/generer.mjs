// Génère les ressources du mod (textures, modèles, traductions, butins, tags, succès, PNJ).
// Lancer depuis n'importe où :  node mod-cabane/outils/generer.mjs
import { genererPnj } from "./pnj.mjs";
import { genererHistoire } from "./histoire.mjs";
import { genererBoissons, OFFRES_BOISSONS, CONTRATS_BOISSONS } from "./boissons.mjs";
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";
import { RELIQUES } from "./reliques.mjs";

const RES = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources");
const ecrire = (rel, contenu) => {
  const f = path.join(RES, rel);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, typeof contenu === "string" ? contenu : Buffer.isBuffer(contenu) ? contenu : JSON.stringify(contenu, null, 2) + "\n");
};

// table : coffres de donjons où la relique peut apparaître ; structures : pour les quêtes et les cartes

// ---------------------------------------------------------------- textures 16x16
function png(grille, palette) {
  const h = grille.length, w = grille[0].length;
  grille.forEach((ligne, y) => { if (ligne.length !== w) throw new Error(`ligne ${y} de largeur ${ligne.length} au lieu de ${w}`); });
  const brut = Buffer.alloc(h * (1 + w * 4));
  grille.forEach((ligne, y) => {
    brut[y * (1 + w * 4)] = 0;
    [...ligne].forEach((ch, x) => {
      const c = palette[ch] ?? [0, 0, 0, 0];
      brut.set(c.length === 3 ? [...c, 255] : c, y * (1 + w * 4) + 1 + x * 4);
    });
  });
  const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

const TEXTURES = {
  // un smartphone moderne : cadre titane, Dynamic Island, fond d'écran et icônes d'applis
  telephone: [[
    "................",
    ".....FFFFFF.....",
    "....FSSKKSSF....",
    "....FPPPPPPF....",
    "....FPRPGPYF....",
    "....FPPPPPPF....",
    "....FQBQAQRF....",
    "....FQQQQQQF....",
    "....FQGQYQBF....",
    "....FUUUUUUF....",
    "....FUUUUUUF....",
    "....FUAUAUAF....",
    "....FUUUUUUF....",
    "....FUUHHUUF....",
    ".....FFFFFF.....",
    "................"], { F: [150, 152, 162], K: [12, 12, 16], S: [36, 26, 82], P: [72, 50, 160], Q: [58, 78, 188], U: [40, 118, 200],
    R: [255, 80, 80], G: [80, 220, 120], Y: [255, 210, 70], B: [90, 170, 255], A: [235, 235, 245], H: [225, 225, 235] }],
  scarabee_dor: [[
    "................",
    "......K..K......",
    ".......KK.......",
    "......KYYK......",
    ".....KYWYYK.....",
    "...K.KYYYYK.K...",
    "....KYYOOYYK....",
    "...KYYYOOYYYK...",
    "..K.KYYOOYYK.K..",
    "....KYYOOYYK....",
    "...K.KYOOYK.K...",
    "......KYYK......",
    ".......KK.......",
    "................",
    "................",
    "................"], { K: [70, 45, 10], Y: [235, 190, 40], O: [190, 130, 20], W: [255, 250, 200] }],
  idole_de_jade: [[
    "................",
    "......KKKK......",
    ".....KJJJJK.....",
    ".....KJWJWK.....",
    ".....KJJJJK.....",
    ".....KJDDJK.....",
    "......KJJK......",
    "....KKJJJJKK....",
    "...KJJKJJKJJK...",
    "...KJK.KK.KJK...",
    ".....KJJJJK.....",
    ".....KJDDJK.....",
    ".....KJK.KJK....",
    "....KKK..KKK....",
    "................",
    "................"], { K: [20, 60, 40], J: [60, 180, 110], D: [30, 120, 70], W: [220, 255, 230] }],
  perle_des_abysses: [[
    "................",
    "................",
    "......KKKK......",
    ".....KPPPPK.....",
    "....KPWWPPPK....",
    "...KPWWPPPPBK...",
    "...KPWPPPPPBK...",
    "...KPPPPPPBBK...",
    "...KPPPPPBBBK...",
    "....KPPPBBBK....",
    ".....KBBBBK.....",
    "......KKKK......",
    "................",
    "................",
    "................",
    "................"], { K: [20, 30, 70], P: [150, 210, 240], W: [250, 255, 255], B: [70, 120, 200] }],
  plume_celeste: [[
    "...........KK...",
    "..........KWCK..",
    ".........KWWCK..",
    "........KWWCCK..",
    ".......KWWCCK...",
    "......KWWCCK....",
    ".....KWWCCK.....",
    "....KWWCCK......",
    "...KWWCCK.......",
    "...KWCCK........",
    "..KWCK..........",
    "..KCK...........",
    ".KQK............",
    ".KK.............",
    "................",
    "................"], { K: [40, 70, 110], W: [245, 250, 255], C: [140, 220, 245], Q: [200, 170, 90] }],
  couronne_illageoise: [[
    "................",
    "................",
    "................",
    "..K....K....K...",
    ".KRK..KRK..KRK..",
    ".KGK..KGK..KGK..",
    ".KGGK.KGK.KGGK..",
    ".KGGGKGGGKGGGK..",
    ".KGGGGGGGGGGGK..",
    ".KGEGGGRGGGEGK..",
    ".KGGGGGGGGGGGK..",
    ".KKKKKKKKKKKKK..",
    "................",
    "................",
    "................",
    "................"], { K: [70, 45, 10], G: [235, 190, 40], R: [200, 30, 40], E: [40, 180, 90] }],
  oeil_du_gardien: [[
    "................",
    "................",
    "................",
    ".....KKKKKK.....",
    "...KKWWWWWWKK...",
    "..KWWWPPPPWWWK..",
    ".KWWWPPMMPPWWWK.",
    ".KWWWPMBBMPWWWK.",
    ".KWWWPMBBMPWWWK.",
    ".KWWWPPMMPPWWWK.",
    "..KWWWPPPPWWWK..",
    "...KKWWWWWWKK...",
    ".....KKKKKK.....",
    "................",
    "................",
    "................"], { K: [30, 20, 40], W: [220, 230, 210], P: [120, 60, 170], M: [60, 200, 160], B: [10, 10, 20] }],
  engrenage_ancien: [[
    "................",
    "......KKKK......",
    "......KCCK......",
    "..KK.KCCCCK.KK..",
    "..KCKCCOOCCKCK..",
    "...KCCOOOOCCK...",
    "..KCCOK..KOCCK..",
    "KKCCOK....KOCCKK",
    "KCCCOK....KOCCCK",
    "..KCCOK..KOCCK..",
    "...KCCOOOOCCK...",
    "..KCKCCOOCCKCK..",
    "..KK.KCCCCK.KK..",
    "......KCCK......",
    "......KKKK......",
    "................"], { K: [60, 30, 15], C: [200, 110, 50], O: [140, 70, 30] }],
};

for (const [nom, [grille, palette]] of Object.entries(TEXTURES)) {
  ecrire(`assets/cabane/textures/item/${nom}.png`, png(grille, palette));
  ecrire(`assets/cabane/models/item/${nom}.json`, { parent: "minecraft:item/generated", textures: { layer0: `cabane:item/${nom}` } });
}

// ---------------------------------------------------------------- traductions (français pour tout le monde)
const lang = {
  "itemGroup.cabane": "SakaiMod",
  "item.cabane.telephone": "Téléphone",
  "item.cabane.telephone.desc": "Clic droit ou touche N : choisis ton numéro et appelle tes potes.",
  "item.cabane.relique": "Relique de la Cabane",
  "screen.cabane.telephone": "Téléphone",
  "key.cabane.telephone": "Ouvrir le téléphone",
  "key.categories.cabane": "Beauvais",
};
for (const r of RELIQUES) {
  lang[`item.cabane.${r.id}`] = r.nom;
  lang[`item.cabane.${r.id}.desc`] = r.desc;
}
ecrire("assets/cabane/lang/fr_fr.json", lang);
ecrire("assets/cabane/lang/en_us.json", lang);

// ---------------------------------------------------------------- reliques dans les coffres des donjons
const modifiers = [];
for (const r of RELIQUES) {
  ecrire(`data/cabane/loot_table/reliques/${r.id}.json`, {
    type: "minecraft:chest",
    pools: [{ rolls: 1, conditions: [{ condition: "minecraft:random_chance", chance: r.chance }],
      entries: [{ type: "minecraft:item", name: `cabane:${r.id}` }] }],
  });
  ecrire(`data/cabane/loot_modifiers/relique_${r.id}.json`, {
    type: "neoforge:add_table",
    conditions: [{ condition: "minecraft:any_of", terms: r.tables.map((t) => ({ condition: "neoforge:loot_table_id", loot_table_id: t })) }],
    table: `cabane:reliques/${r.id}`,
  });
  modifiers.push(`cabane:relique_${r.id}`);

  // structures de la quête + carte au trésor qui y mène
  ecrire(`data/cabane/tags/worldgen/structure/quete/${r.cle}.json`, { values: r.structures.map((id) => ({ id, required: false })) });
  ecrire(`data/cabane/loot_table/cartes/${r.cle}.json`, {
    type: "minecraft:chest",
    pools: [{ rolls: 1, entries: [{ type: "minecraft:item", name: "minecraft:map", functions: [
      { function: "minecraft:exploration_map", destination: `cabane:quete/${r.cle}`, decoration: "minecraft:red_x", zoom: 2, search_radius: 100, skip_existing_chunks: false },
      { function: "minecraft:set_name", target: "item_name", name: { text: `Carte : ${r.lieu}`, color: "gold" } },
    ] }] }],
  });

  ecrire(`data/cabane/advancement/relique_${r.id}.json`, {
    parent: "cabane:racine",
    display: { icon: { id: `cabane:${r.id}` }, title: { text: r.nom }, description: { text: r.desc },
      frame: "goal", show_toast: true, announce_to_chat: true },
    criteria: { trouvee: { trigger: "minecraft:inventory_changed", conditions: { items: [{ items: `cabane:${r.id}` }] } } },
  });
}
ecrire("data/neoforge/loot_modifiers/global_loot_modifiers.json", { replace: false, entries: modifiers });

// ---------------------------------------------------------------- succès
ecrire("data/cabane/advancement/racine.json", {
  display: { icon: { id: "cabane:telephone" }, title: { text: "Beauvais" }, description: { text: "Le village au cœur de la jungle, et de vos aventures." },
    background: "minecraft:textures/block/stripped_spruce_log.png", frame: "task", show_toast: false, announce_to_chat: false },
  criteria: { arrivee: { trigger: "minecraft:tick" } },
});
ecrire("data/cabane/advancement/premier_appel.json", {
  parent: "cabane:racine",
  display: { icon: { id: "cabane:telephone" }, title: { text: "Allô ?" }, description: { text: "Passer ton premier appel." }, frame: "task" },
  criteria: { appel: { trigger: "minecraft:impossible" } },
});
ecrire("data/cabane/advancement/premier_contrat.json", {
  parent: "cabane:racine",
  display: { icon: { id: "minecraft:bell" }, title: { text: "Au service du village" }, description: { text: "Remplir ton premier contrat au marché." }, frame: "task" },
  criteria: { contrat: { trigger: "minecraft:impossible" } },
});
ecrire("data/cabane/advancement/sept_reliques.json", {
  parent: "cabane:relique_oeil_du_gardien",
  display: { icon: { id: "minecraft:elytra" }, title: { text: "Le Réveil de la Cabane" }, description: { text: "Réunir les sept reliques." },
    frame: "challenge", show_toast: true, announce_to_chat: true },
  criteria: Object.fromEntries(RELIQUES.map((r) => [r.id, { trigger: "minecraft:inventory_changed", conditions: { items: [{ items: `cabane:${r.id}` }] } }])),
});

// pas de recette : le téléphone, comme tout ce qui se vend chez Mouloud, ne s'achète qu'en boutique

// ---------------------------------------------------------------- coffres du centre de map
const item = (name, poids, min = 1, max = 1) => ({ type: "minecraft:item", name, weight: poids,
  functions: max > 1 ? [{ function: "minecraft:set_count", count: { type: "minecraft:uniform", min, max } }] : [] });
ecrire("data/cabane/loot_table/coffres/cabane.json", {
  type: "minecraft:chest",
  pools: [{ rolls: { type: "minecraft:uniform", min: 5, max: 9 }, entries: [
    item("minecraft:bread", 10, 2, 5), item("minecraft:apple", 8, 1, 4), item("minecraft:torch", 8, 4, 12),
    item("minecraft:string", 6, 2, 6), item("minecraft:leather", 5, 1, 3), item("minecraft:iron_nugget", 6, 3, 9),
    item("minecraft:wheat_seeds", 6, 2, 8), item("minecraft:emerald", 3, 1, 3), item("minecraft:map", 3),
    item("minecraft:candle", 4, 1, 3), item("minecraft:book", 3, 1, 2), item("minecraft:lead", 2),
  ] }],
});
ecrire("data/cabane/loot_table/coffres/tour.json", {
  type: "minecraft:chest",
  pools: [
    { rolls: 1, entries: [item("minecraft:spyglass", 1)] },
    { rolls: { type: "minecraft:uniform", min: 3, max: 6 }, entries: [
      item("minecraft:arrow", 8, 6, 16), item("minecraft:bow", 3), item("minecraft:iron_ingot", 5, 1, 4),
      item("minecraft:gold_nugget", 5, 3, 10), item("minecraft:emerald", 3, 1, 4), item("minecraft:cooked_beef", 6, 2, 5),
      item("minecraft:compass", 2),
    ] },
  ],
});

// ---------------------------------------------------------------- PNJ (Easy NPC) : voir pnj.mjs
genererPnj(ecrire);

// ---------------------------------------------------------------- la forge de Brokk : lui seul sait faire ces pièces
const forge = (fichier, resultat, prix, ingredients, ordre) =>
  ecrire(`data/cabane/forge/${fichier}.json`, JSON.stringify({ resultat, prix, ordre, ingredients }, null, 2) + "\n");
forge("lance_umvuthana", "mowziesmobs:spear", 6000, [["minecraft:bamboo", 32], ["minecraft:flint", 8], ["minecraft:gold_ingot", 6], ["minecraft:feather", 4]], 10);
forge("epee_acier_noir", "cataclysm:black_steel_sword", 9000, [["minecraft:iron_block", 4], ["minecraft:coal_block", 8], ["minecraft:diamond", 2]], 20);
forge("targe_acier_noir", "cataclysm:black_steel_targe", 9000, [["minecraft:iron_block", 6], ["minecraft:coal_block", 6], ["minecraft:leather", 8]], 21);
forge("casque_forge", "mowziesmobs:wrought_helmet", 12000, [["minecraft:iron_block", 5], ["minecraft:diamond", 2], ["minecraft:leather", 6]], 30);
forge("hache_forgee", "mowziesmobs:wrought_axe", 15000, [["minecraft:iron_block", 8], ["minecraft:diamond", 4], ["minecraft:blaze_rod", 4]], 31);
forge("khopesh", "cataclysm:khopesh", 14000, [["minecraft:gold_ingot", 12], ["minecraft:sandstone", 32], ["minecraft:diamond", 3]], 40);
forge("bottes_robustes", "cataclysm:sturdy_boots", 12000, [["minecraft:leather", 12], ["minecraft:iron_ingot", 16], ["minecraft:slime_ball", 8]], 41);
forge("lance_corail", "cataclysm:coral_spear", 16000, [["minecraft:prismarine_shard", 24], ["minecraft:nautilus_shell", 2], ["minecraft:diamond", 3]], 42);
forge("gantelet_garde", "cataclysm:gauntlet_of_guard", 25000, [["minecraft:iron_block", 8], ["minecraft:diamond", 6], ["minecraft:obsidian", 8]], 50);
forge("gantelet_terre", "mowziesmobs:earthrend_gauntlet", 30000, [["minecraft:iron_block", 6], ["minecraft:amethyst_shard", 16], ["minecraft:diamond", 6], ["minecraft:netherite_scrap", 2]], 60);
forge("masque_soleil", "mowziesmobs:sol_visage", 45000, [["minecraft:gold_block", 8], ["minecraft:blaze_rod", 12], ["minecraft:magma_cream", 8], ["minecraft:diamond", 8]], 70);
forge("forge_infernale", "cataclysm:infernal_forge", 60000, [["minecraft:netherite_ingot", 1], ["minecraft:blaze_rod", 16], ["minecraft:magma_block", 16], ["minecraft:diamond", 8]], 80);

// ================================================================ économie, marché, casino, cartes, parachute

const P = { // palette commune
  K: [24, 18, 14], W: [120, 84, 50], w: [150, 108, 66], L: [92, 64, 38], Y: [240, 196, 60], y: [190, 140, 30],
  R: [190, 40, 40], r: [140, 24, 24], G: [36, 110, 60], g: [26, 84, 44], B: [40, 90, 170], b: [26, 60, 120],
  S: [200, 204, 210], s: [150, 154, 162], N: [30, 30, 34], T: [245, 240, 228], O: [230, 120, 40], C: [205, 170, 120],
  c: [170, 136, 90], V: [140, 70, 200], A: [70, 190, 230], ".": [0, 0, 0, 0],
};
const tex = (chemin, lignes) => ecrire(`assets/cabane/textures/${chemin}.png`, png(lignes, P));
const itemGenere = (nom) => ecrire(`assets/cabane/models/item/${nom}.json`, { parent: "minecraft:item/generated", textures: { layer0: `cabane:item/${nom}` } });

// ---- objets
tex("gui/piece", [
  "...YYYY...", "..YyyyyY..", ".YyYYYYyY.", "YyYYyyYYyY", "YyYyYYyYyY",
  "YyYyYYyYyY", "YyYYyyYYyY", ".YyYYYYyY.", "..YyyyyY..", "...YYYY..."]);
tex("item/parachute", [
  "................", "....RRRTTTRR....", "..RRRRTTTTRRRR..", ".RRRRRTTTTRRRRR.", "RRRRRRTTTTRRRRRR",
  "K.K...K..K...K.K", ".K.K...KK...K.K.", "..K.K..KK..K.K..", "...K.K.KK.K.K...", "....K.KKKK.K....",
  ".....KKKKKK.....", "......LWWL......", "......WwwW......", "......WwwW......", "......LWWL......", "................"]);
const carte = (cadre, fond) => [
  "................", "...KKKKKKKKKK...", "...K" + cadre.repeat(8) + "K...", "...K" + cadre + fond.repeat(6) + cadre + "K...",
  "...K" + cadre + fond + "TTTT" + fond + cadre + "K...", "...K" + cadre + fond + "TYYT" + fond + cadre + "K...",
  "...K" + cadre + fond + "TYYT" + fond + cadre + "K...", "...K" + cadre + fond + "TTTT" + fond + cadre + "K...",
  "...K" + cadre + fond.repeat(6) + cadre + "K...", "...K" + cadre + "SSSSSS" + cadre + "K...", "...K" + cadre + fond.repeat(6) + cadre + "K...",
  "...K" + cadre + "SSSS" + fond + fond + cadre + "K...", "...K" + cadre + fond.repeat(6) + cadre + "K...", "...K" + cadre.repeat(8) + "K...",
  "...KKKKKKKKKK...", "................"];
tex("item/carte_commune", carte("s", "N"));
tex("item/carte_rare", carte("A", "b"));
tex("item/carte_epique", carte("V", "N"));
tex("item/carte_legendaire", carte("Y", "r"));
tex("item/booster", [
  "....KKKKKKKK....", "...KRRRRRRRRK...", "...KRYYYYYYRK...", "...KRRRRRRRRK...", "...KRRTTTTRRK...", "...KRTYYYYTRK...",
  "...KRTYRRYTRK...", "...KRTYRRYTRK...", "...KRTYYYYTRK...", "...KRRTTTTRRK...", "...KRRRRRRRRK...", "...KRYYYYYYRK...",
  "...KRRRRRRRRK...", "...KrrrrrrrrK...", "....KKKKKKKK....", "................"]);
tex("item/classeur", [
  "................", "..KKKKKKKKKKKK..", "..KbBBBBBBBBBK..", "..KbBBBBBBBBBK..", "..KbBTTTTTTBBK..", "..KbBTsTTsTBBK..",
  "..KbBTTTTTTBBK..", "..KbBTsTTsTBBK..", "..KbBTTTTTTBBK..", "..KbBBBBBBBBBK..", "..KbBBBYYBBBBK..", "..KbBBBYYBBBBK..",
  "..KbBBBBBBBBBK..", "..KbbbbbbbbbbK..", "..KKKKKKKKKKKK..", "................"]);
tex("item/ticket_a_gratter", [
  "................", "................", ".KKKKKKKKKKKKKK.", ".KYYYYYYYYYYYYK.", ".KYRRYYRRYYRRYK.", ".KYSSYYSSYYSSYK.",
  ".KYSSYYSSYYSSYK.", ".KYYYYYYYYYYYYK.", ".KYSSYYSSYYTTYK.", ".KYSSYYSSYYTTYK.", ".KYYYYYYYYYYYYK.", ".KYyyyyyyyyyyYK.",
  ".KKKKKKKKKKKKKK.", "................", "................", "................"]);
for (const n of ["parachute", "booster", "classeur", "ticket_a_gratter"]) itemGenere(n);
for (const n of ["carte_rare", "carte_epique", "carte_legendaire"]) itemGenere(n);
ecrire("assets/cabane/models/item/carte.json", {
  parent: "minecraft:item/generated", textures: { layer0: "cabane:item/carte_commune" },
  overrides: [
    { predicate: { "cabane:rarete": 0.3 }, model: "cabane:item/carte_rare" },
    { predicate: { "cabane:rarete": 0.6 }, model: "cabane:item/carte_epique" },
    { predicate: { "cabane:rarete": 0.9 }, model: "cabane:item/carte_legendaire" },
  ],
});

// ---- blocs (orientés : face avant, côtés, dessus)
const bloc = (nom, { front, side, top, deuxFaces = false }) => {
  // deuxFaces : la face avant aussi à l'arrière (un comptoir se voit des deux côtés)
  ecrire(`assets/cabane/models/block/${nom}.json`, deuxFaces
    ? { parent: "minecraft:block/cube", textures: { north: front, south: front, east: side, west: side, up: top, down: side, particle: side } }
    : { parent: "minecraft:block/orientable", textures: { front, side, top } });
  ecrire(`assets/cabane/models/item/${nom}.json`, { parent: `cabane:block/${nom}` });
  ecrire(`assets/cabane/blockstates/${nom}.json`, { variants: Object.fromEntries(
    [["north", 0], ["east", 90], ["south", 180], ["west", 270]].map(([f, y]) => [`facing=${f}`, { model: `cabane:block/${nom}`, ...(y ? { y } : {}) }])) });
  ecrire(`data/cabane/loot_table/blocks/${nom}.json`, { type: "minecraft:block", pools: [{ rolls: 1, entries: [{ type: "minecraft:item", name: `cabane:${nom}` }],
    conditions: [{ condition: "minecraft:survives_explosion" }] }] });
};
const feutre = (motif) => motif.map((l) => l.replace(/ /g, "G"));
tex("block/comptoir_front", [
  "wwwwwwwwwwwwwwww", "LLLLLLLLLLLLLLLL", "WWWWWWWWWWWWWWWW", "WKKKKKKKKKKKKKKW", "WKCCCCCCCCCCCCKW", "WKCCCCYYYYCCCCKW",
  "WKCCCYyyyyYCCCKW", "WKCCCYyYYyYCCCKW", "WKCCCYyYYyYCCCKW", "WKCCCYyyyyYCCCKW", "WKCCCCYYYYCCCCKW", "WKCCCCCCCCCCCCKW",
  "WKKKKKKKKKKKKKKW", "WWWWWWWWWWWWWWWW", "LLLLLLLLLLLLLLLL", "LLLLLLLLLLLLLLLL"]);
tex("block/comptoir_top", Array.from({ length: 16 }, (_, i) => (i % 4 === 3 ? "LLLLLLLLLLLLLLLL" : "wwwwwwwwwwwwwwww")));
tex("block/tableau_contrats_front", [
  "LLLLLLLLLLLLLLLL", "LccccccccccccccL", "LcTTTTcccTTTTccL", "LcTSSTcccTSSTccL", "LcTTTTcccTTTTccL", "LcTSSTcccTSSTccL",
  "LcTTRTcccTTTRccL", "LccccccccccccccL", "LccTTTTTccTTTTcL", "LccTSSSTccTSSTcL", "LccTTTTTccTTTTcL", "LccTSSSTccTSSTcL",
  "LccTTTRTccTTRTcL", "LccccccccccccccL", "LLLLLLLLLLLLLLLL", "LLLLLLLLLLLLLLLL"]);
tex("block/etal_front", [
  "RRTTRRTTRRTTRRTT", "RRTTRRTTRRTTRRTT", "rRTTrRTTrRTTrRTT", ".R.T.R.T.R.T.R.T", "................", "L..............L",
  "L.WWWW....WWWW.L", "L.WOOW....WGGW.L", "L.WOOW....WGGW.L", "L.WWWW....WWWW.L", "LwwwwwwwwwwwwwwL", "LWWWWWWWWWWWWWWL",
  "LKKKKKKKKKKKKKKL", "LWWWWWWWWWWWWWWL", "LLLLLLLLLLLLLLLL", "LLLLLLLLLLLLLLLL"]);
tex("block/machine_a_sous_front", [
  "rrrrrrrrrrrrrrrr", "rYYYYYYYYYYYYYYr", "rYRRRRRRRRRRRRYr", "rYRTTTRTTTRTTTRr", "rYRTRTRTYTRTOTRr", "rYRTTTRTTTRTTTRr",
  "rYRRRRRRRRRRRRYr", "rYYYYYYYYYYYYYYr", "rRRRRRRRRRRRRRRr", "rRNNNNNNNNNNNNRr", "rRNYYNNYYNNYYNRr", "rRNNNNNNNNNNNNRr",
  "rRRRRRRRRRRRRRRr", "rRRRRYYYYYYRRRRr", "rrrrrrrrrrrrrrrr", "KKKKKKKKKKKKKKKK"]);
tex("block/machine_a_sous_side", Array.from({ length: 16 }, (_, i) => (i === 0 || i === 15 ? "KKKKKKKKKKKKKKKK" : "rRRRRRRRRRRRRRRr")));
tex("block/machine_a_sous_top", Array.from({ length: 16 }, (_, i) => (i === 0 || i === 15 ? "rrrrrrrrrrrrrrrr" : "rRRRRRRRRRRRRRRr")));
tex("block/table_side", [
  "gggggggggggggggg", "WWWWWWWWWWWWWWWW", "LLLLLLLLLLLLLLLL", "WLWWWWWWWWWWWWLW", "WLWWWWWWWWWWWWLW", "WLWWWWWWWWWWWWLW",
  "WLWWWWWWWWWWWWLW", "WLWWWWWWWWWWWWLW", "WLWWWWWWWWWWWWLW", "WLWWWWWWWWWWWWLW", "WLWWWWWWWWWWWWLW", "WLWWWWWWWWWWWWLW",
  "WLWWWWWWWWWWWWLW", "WLWWWWWWWWWWWWLW", "LLLLLLLLLLLLLLLL", "LLLLLLLLLLLLLLLL"]);
tex("block/table_roulette_top", feutre([
  "LLLLLLLLLLLLLLLL", "L              L", "L    WWWWWW    L", "L   WRNRNRNW   L", "L  WNWWWWWWRW  L", "L  WRWLLLLWNW  L",
  "L  WNWLYYLWRW  L", "L  WRWLYYLWNW  L", "L  WNWLLLLWRW  L", "L  WRWWWWWWNW  L", "L   WNRNRNRW   L", "L    WWWWWW    L",
  "L  TTTTTTTTTT  L", "L  RNRNRNRNRN  L", "L              L", "LLLLLLLLLLLLLLLL"]));
tex("block/table_blackjack_top", feutre([
  "LLLLLLLLLLLLLLLL", "L              L", "L  TTT    TTT  L", "L  TRT    TNT  L", "L  TTT    TTT  L", "L              L",
  "L YYYYYYYYYYYY L", "L  y        y  L", "L              L", "L   TTT  TTT   L", "L   TRT  TNT   L", "L   TTT  TTT   L",
  "L      YY      L", "L      YY      L", "L              L", "LLLLLLLLLLLLLLLL"]));
tex("block/table_des_top", feutre([
  "LLLLLLLLLLLLLLLL", "L              L", "L  TTTTT       L", "L  TNTTT       L", "L  TTNTT       L", "L  TTTNT       L",
  "L  TTTTT TTTTT L", "L        TNTNT L", "L        TTTTT L", "L        TNTNT L", "L        TTTTT L", "L              L",
  "L YYYYYYYYYYYY L", "L  7  <  =  >  L", "L              L", "LLLLLLLLLLLLLLLL"].map((l) => l.replace(/[7<=>]/g, "T"))));
tex("block/table_pile_ou_face_top", feutre([
  "LLLLLLLLLLLLLLLL", "L              L", "L              L", "L     YYYY     L", "L    YyyyyY    L", "L   YyYYYYyY   L",
  "L   YyYyyYyY   L", "L   YyYyyYyY   L", "L   YyYYYYyY   L", "L    YyyyyY    L", "L     YYYY     L", "L              L",
  "L  TTTT  TTTT  L", "L  TRRT  TNNT  L", "L              L", "LLLLLLLLLLLLLLLL"]));
tex("block/roue_fortune_front", [
  "LLLLLLLLLLLLLLLL", "L.....RRRB.....L", "L...OORRRBBA...L", "L..OOORRBBBAA..L", "L.YYYOORBBAAAG.L", "L.YYYYOTTAAGGG.L",
  "LYYYYYTYYTGGGGGL", "LRRRRRTYYTVVVVVL", "LBBBBBTYYTSSSSSL", "L.BBBBOTTRRSSS.L", "L.AAAGGORRRVVS.L", "L..AAGGGORRVV...L",
  "L...AGGGOOVV...L", "L.....GGOV.....L", "LLLLLLLRRLLLLLLL", "LLLLLLLLLLLLLLLL"].map((l) => l.length > 16 ? l.slice(0, 16) : l.padEnd(16, "L")));
bloc("comptoir", { front: "cabane:block/comptoir_front", side: "minecraft:block/spruce_planks", top: "cabane:block/comptoir_top", deuxFaces: true });
bloc("tableau_contrats", { front: "cabane:block/tableau_contrats_front", side: "minecraft:block/spruce_planks", top: "minecraft:block/spruce_planks", deuxFaces: true });
bloc("etal", { front: "cabane:block/etal_front", side: "minecraft:block/barrel_side", top: "minecraft:block/barrel_top" });
bloc("machine_a_sous", { front: "cabane:block/machine_a_sous_front", side: "cabane:block/machine_a_sous_side", top: "cabane:block/machine_a_sous_top" });
for (const t of ["roulette", "blackjack", "des", "pile_ou_face"]) {
  bloc(`table_${t}`, { front: "cabane:block/table_side", side: "cabane:block/table_side", top: `cabane:block/table_${t}_top` });
}
bloc("roue_fortune", { front: "cabane:block/roue_fortune_front", side: "minecraft:block/dark_oak_planks", top: "minecraft:block/dark_oak_planks" });

genererHistoire({ ecrire, png, lang });
genererBoissons({ ecrire, png, lang });

Object.assign(lang, {
  "item.cabane.parachute": "Parachute", "item.cabane.carte": "Carte", "item.cabane.booster": "Booster de cartes",
  "item.cabane.classeur": "Classeur de cartes", "item.cabane.ticket_a_gratter": "Ticket à gratter",
  "block.cabane.comptoir": "Comptoir de la boutique", "block.cabane.tableau_contrats": "Tableau des contrats",
  "block.cabane.etal": "Étal", "block.cabane.machine_a_sous": "Machine à sous", "block.cabane.table_roulette": "Table de roulette",
  "block.cabane.table_blackjack": "Table de blackjack", "block.cabane.table_des": "Table de dés",
  "block.cabane.roue_fortune": "Roue de la fortune", "block.cabane.table_pile_ou_face": "Table de pile ou face",
  "entity.cabane.parachute": "Parachute",
  "item.cabane.puff_rose": "Puff rose (goût surprise)", "item.cabane.puff_bleue": "Puff bleue (goût surprise)", "item.cabane.puff_menthe": "Puff verte (goût surprise)",
  "item.cabane.tuyau": "Tuyau de chicha", "block.cabane.chicha": "Chicha", "block.cabane.borne_terrain": "Borne de terrain à vendre",
  "item.cabane.menottes": "Menottes", "item.cabane.pistolet": "Pistolet de police", "item.cabane.gilet_police": "Gilet de police",
  "item.cabane.voiture_police": "Voiture de police", "entity.cabane.voiture_police": "Voiture de police",
  "key.cabane.sirene": "Sirène de la voiture de police", "block.cabane.partie_table": "Table du casino",
  "subtitles.cabane.voix": "Un habitant parle", "subtitles.cabane.voiture": "Voiture de police",
});
ecrire("assets/cabane/lang/fr_fr.json", lang);
ecrire("assets/cabane/lang/en_us.json", lang);

// ---- la boutique du marché (prix modifiables ici ou directement dans les fichiers)
const offres = [
  ["telephone", "cabane:telephone", 1, 750], ["parachute", "cabane:parachute", 1, 150],
  ["booster", "cabane:booster", 1, 120], ["classeur", "cabane:classeur", 1, 300], ["ticket", "cabane:ticket_a_gratter", 1, 20],
  ["etal", "cabane:etal", 1, 400], ["pain", "minecraft:bread", 8, 30], ["torches", "minecraft:torch", 16, 25],
  // le casino coûte cher : c'est un vrai investissement (et il faut encore remplir la caisse)
  ["machine_a_sous", "cabane:machine_a_sous", 1, 8000], ["table_roulette", "cabane:table_roulette", 1, 12000],
  ["table_blackjack", "cabane:table_blackjack", 1, 12000], ["table_des", "cabane:table_des", 1, 6000],
  ["roue_fortune", "cabane:roue_fortune", 1, 10000], ["table_pile_ou_face", "cabane:table_pile_ou_face", 1, 4000],
];
offres.push(["puff_rose", "cabane:puff_rose", 1, 60], ["puff_bleue", "cabane:puff_bleue", 1, 60], ["puff_menthe", "cabane:puff_menthe", 1, 60],
  ["chicha", "cabane:chicha", 1, 900]);
for (const nom of ["puff_rose", "puff_bleue", "puff_menthe", "tuyau"]) itemGenere(nom);
// la police (textures : outils/police.mjs)
offres.push(["menottes", "cabane:menottes", 1, 800], ["pistolet", "cabane:pistolet", 1, 2500],
  ["gilet_police", "cabane:gilet_police", 1, 1200], ["voiture_police", "cabane:voiture_police", 1, 25000]);
for (const nom of ["menottes", "gilet_police", "voiture_police"]) itemGenere(nom);
// le pistolet : un vrai petit modèle 3D en main, l'icône à plat dans l'inventaire
{
  const face = (uv) => Object.fromEntries(["north", "south", "east", "west", "up", "down"].map((c) => [c, { texture: "#p", uv }]));
  const CULASSE = [0, 0, 4, 4], NOIR = [4, 0, 8, 4], REFLET = [8, 0, 12, 4], CROSSE = [12, 0, 16, 4], CARCASSE = [0, 4, 4, 8], ARGENT = [4, 4, 8, 8];
  const bout = (de, a, uv, rotation) => ({ from: de, to: a, faces: face(uv), ...(rotation ? { rotation } : {}) });
  ecrire("assets/cabane/models/item/pistolet_3d.json", {
    textures: { p: "cabane:item/pistolet_3d", particle: "cabane:item/pistolet_3d" },
    elements: [
      bout([7, 9, 2], [9, 11, 13], CULASSE),
      bout([7.5, 9.5, 1], [8.5, 10.5, 2], NOIR),
      bout([7.4, 11, 3], [8.6, 11.2, 12], REFLET),
      bout([7.75, 11, 2.5], [8.25, 11.6, 3.2], NOIR),
      bout([7.5, 11, 12], [8.5, 11.5, 12.8], NOIR),
      bout([7.1, 8, 4], [8.9, 9, 12.5], CARCASSE),
      bout([7.1, 2.5, 10], [8.9, 8, 13], CROSSE, { angle: -22.5, axis: "x", origin: [8, 8, 11.5] }),
      bout([7.4, 6.6, 6.8], [8.6, 7.1, 10], NOIR),
      bout([7.4, 7.1, 6.8], [8.6, 8, 7.3], NOIR),
      bout([7.8, 7, 8.3], [8.2, 8, 8.8], ARGENT),
    ],
    display: {
      thirdperson_righthand: { rotation: [0, 0, 0], translation: [0, 2.5, 1.5], scale: [0.6, 0.6, 0.6] },
      thirdperson_lefthand: { rotation: [0, 0, 0], translation: [0, 2.5, 1.5], scale: [0.6, 0.6, 0.6] },
      firstperson_righthand: { rotation: [0, 0, 0], translation: [1.5, 2.5, 0], scale: [0.8, 0.8, 0.8] },
      firstperson_lefthand: { rotation: [0, 0, 0], translation: [1.5, 2.5, 0], scale: [0.8, 0.8, 0.8] },
      ground: { translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
      fixed: { rotation: [0, 90, 0], scale: [0.9, 0.9, 0.9] },
      head: { rotation: [0, 180, 0], translation: [0, 13, 7] },
    },
  });
  ecrire("assets/cabane/models/item/pistolet.json", {
    loader: "neoforge:separate_transforms",
    base: { parent: "cabane:item/pistolet_3d" },
    perspectives: { gui: { parent: "minecraft:item/generated", textures: { layer0: "cabane:item/pistolet" } } },
  });
}
// la chicha : un modèle en plusieurs morceaux (vase de verre, tige dorée, plateau, foyer, tuyau)
const morceau = (de, a, texture) => ({ from: de, to: a, faces: Object.fromEntries(["north", "south", "east", "west", "up", "down"].map((f) => [f, { texture }])) });
ecrire("assets/cabane/models/block/chicha.json", {
  parent: "minecraft:block/block", render_type: "minecraft:translucent",
  textures: { verre: "minecraft:block/purple_stained_glass", or: "minecraft:block/gold_block", fer: "minecraft:block/iron_block",
    terre: "minecraft:block/terracotta", braise: "minecraft:block/magma", tuyau: "minecraft:block/black_wool", particle: "minecraft:block/purple_stained_glass" },
  elements: [
    morceau([4, 0, 4], [12, 7, 12], "#verre"), morceau([6.5, 7, 6.5], [9.5, 9, 9.5], "#or"), morceau([7.25, 9, 7.25], [8.75, 12, 8.75], "#or"),
    morceau([5, 12, 5], [11, 12.5, 11], "#fer"), morceau([6.5, 12.5, 6.5], [9.5, 14.5, 9.5], "#terre"), morceau([7, 14.5, 7], [9, 15.2, 9], "#braise"),
    morceau([9.5, 8, 7.5], [12.5, 9, 8.5], "#tuyau"), morceau([12, 2, 7.5], [13, 9, 8.5], "#tuyau"), morceau([10, 1, 7.5], [13, 2, 8.5], "#tuyau"),
  ],
});
ecrire("assets/cabane/models/item/chicha.json", { parent: "cabane:block/chicha" });
// la borne de terrain : un poteau et un écriteau blanc bordé de rouge
ecrire("assets/cabane/models/block/borne_terrain.json", {
  parent: "minecraft:block/block",
  textures: { poteau: "minecraft:block/stripped_oak_log", planche: "minecraft:block/birch_planks", rouge: "minecraft:block/red_wool", particle: "minecraft:block/birch_planks" },
  elements: [morceau([7, 0, 7], [9, 10, 9], "#poteau"), morceau([2, 8, 7.25], [14, 15, 8.75], "#planche"),
    morceau([2, 14.25, 7.15], [14, 15, 8.85], "#rouge"), morceau([2, 8, 7.15], [14, 8.75, 8.85], "#rouge")],
});
ecrire("assets/cabane/models/item/borne_terrain.json", { parent: "cabane:block/borne_terrain" });
ecrire("assets/cabane/blockstates/borne_terrain.json", { variants: Object.fromEntries(
  [["north", 0], ["east", 90], ["south", 180], ["west", 270]].map(([f, y]) => [`facing=${f}`, { model: "cabane:block/borne_terrain", ...(y ? { y } : {}) }])) });
ecrire("data/cabane/loot_table/blocks/borne_terrain.json", { type: "minecraft:block", pools: [] });
ecrire("assets/cabane/models/block/partie_table.json", { textures: { particle: "minecraft:block/dark_oak_planks" } });
ecrire("assets/cabane/blockstates/partie_table.json", { variants: { "": { model: "cabane:block/partie_table" } } });
ecrire("assets/cabane/blockstates/chicha.json", { variants: Object.fromEntries(
  [["north", 0], ["east", 90], ["south", 180], ["west", 270]].map(([f, y]) => [`facing=${f}`, { model: "cabane:block/chicha", ...(y ? { y } : {}) }])) });
ecrire("data/cabane/loot_table/blocks/chicha.json", { type: "minecraft:block", pools: [{ rolls: 1, entries: [{ type: "minecraft:item", name: "cabane:chicha" }] }] });
offres.push(...OFFRES_BOISSONS);
offres.forEach(([id, item, nombre, prix], i) => ecrire(`data/cabane/boutique/${id}.json`, { item, nombre, prix, ordre: i }));

// ---- les contrats horaires du marché
const contrats = [
  ["ble", "minecraft:wheat", 16, 48, 3], ["carottes", "minecraft:carrot", 16, 48, 2], ["patates", "minecraft:potato", 16, 48, 2],
  ["canne", "minecraft:sugar_cane", 16, 32, 3], ["chene", "minecraft:oak_log", 16, 48, 2], ["acacia", "minecraft:acacia_log", 16, 48, 2],
  ["pierre", "minecraft:cobblestone", 32, 64, 1], ["fer", "minecraft:iron_ingot", 8, 24, 14], ["charbon", "minecraft:coal", 16, 32, 4],
  ["cuir", "minecraft:leather", 6, 16, 9], ["laine", "minecraft:white_wool", 8, 24, 5], ["pain", "minecraft:bread", 8, 24, 7],
  ["boeuf", "minecraft:cooked_beef", 8, 24, 8], ["citrouille", "minecraft:pumpkin", 6, 16, 7], ["fil", "minecraft:string", 8, 24, 4],
  ["os", "minecraft:bone", 8, 24, 4], ["poudre", "minecraft:gunpowder", 6, 16, 9], ["redstone", "minecraft:redstone", 16, 32, 4],
  ["cuivre", "minecraft:copper_ingot", 8, 32, 5], ["or", "minecraft:gold_ingot", 4, 12, 22], ["lapis", "minecraft:lapis_lazuli", 8, 24, 6],
];
contrats.push(...CONTRATS_BOISSONS);
contrats.forEach(([id, item, min, max, prix]) => ecrire(`data/cabane/contrats/${id}.json`, { item, min, max, prix_unitaire: prix }));

// ---- la première série de cartes (à remplacer par les vôtres)
const cartes = [
  ["la_cabane", "La Grande Cabane", "Le refuge de la Confrérie des Explorateurs, à Beauvais.", "commune"],
  ["feu_de_camp", "Le Feu de Camp", "Là où l'on se raconte les expéditions.", "commune"],
  ["tour_de_guet", "La Tour de Guet", "Sa fumée se voit à des kilomètres.", "commune"],
  ["telephone", "Le Téléphone", "3 ou 4 chiffres, et le monde entier au bout du fil.", "commune"],
  ["bako", "Mouloud le Marchand", "Toujours une bonne affaire… pour lui.", "commune"],
  ["lion", "Le Lion de la Savane", "Le vrai roi de la savane (ne le dites pas à Umvuthi).", "commune"],
  ["zebre", "Le Zèbre", "Noir sur blanc ou blanc sur noir ?", "commune"],
  ["sakaidollar", "Le SakaiDollar", "La monnaie du serveur.", "commune"],
  ["mira", "Karim le Chouf", "Une carte pour chaque relique.", "rare"],
  ["avion", "L'Avion en Feu", "Mayday ! On saute au-dessus de la jungle.", "rare"],
  ["elephant", "L'Éléphant", "Il n'oublie jamais une dette.", "rare"],
  ["scarabee", "Le Scarabée d'Or", "Gardé par le Pharaon.", "rare"],
  ["idole", "L'Idole de Jade", "Au cœur de la jungle.", "rare"],
  ["gardien", "Le Hejj du Village", "Il en sait plus qu'il ne le dit.", "epique"],
  ["umvuthi", "Umvuthi", "Le roi masqué de la savane.", "epique"],
  ["perle", "La Perle des Abysses", "Elle brille au fond de l'océan.", "epique"],
  ["plume", "La Plume Céleste", "Tombée d'une citadelle des nuages.", "epique"],
  ["ignis", "Ignis", "Le chevalier de flammes.", "legendaire"],
  ["leviathan", "Le Léviathan", "Ce qui dort sous la Cité Engloutie.", "legendaire"],
];
cartes.forEach(([id, nom, description, rarete], i) => ecrire(`data/cabane/cartes/${id}.json`, { nom, description, rarete, numero: i + 1, serie: "Série 1 — Beauvais" }));

console.log("Ressources générées dans", RES);

// ---- les sons : la voiture (synthétisée par le mod) et les voix des PNJ, rangées depuis le dossier voix/ à la racine
{
  const sons = { voiture: { sounds: [{ name: "minecraft:random/click", stream: true }] } };
  const dossierVoix = path.join(RES, "..", "..", "..", "..", "voix");
  const cible = path.join(RES, "assets", "cabane", "sounds", "voix");
  fs.rmSync(cible, { recursive: true, force: true });
  const aConvertir = [];
  if (fs.existsSync(dossierVoix)) {
    for (const fichier of fs.readdirSync(dossierVoix).sort()) {
      if (/.(txt|md)$/i.test(fichier)) continue;
      const m = fichier.toLowerCase().match(/^([a-z_]+?)(?:[-_ ]?\d+|-.+)?\.([a-z0-9]+)$/);
      if (!m) continue;
      const [, pnj, ext] = m;
      if (ext !== "ogg") { aConvertir.push(fichier); continue; }
      const nom = fichier.toLowerCase().replace(/\.ogg$/, "").replace(/[^a-z0-9_]/g, "_");
      fs.mkdirSync(path.join(cible, pnj), { recursive: true });
      fs.copyFileSync(path.join(dossierVoix, fichier), path.join(cible, pnj, nom + ".ogg"));
      (sons["voix." + pnj] ??= { subtitle: "subtitles.cabane.voix", sounds: [] }).sounds.push({ name: `cabane:voix/${pnj}/${nom}` });
    }
  }
  ecrire("assets/cabane/sounds.json", sons);
  const nb = Object.keys(sons).length - 1;
  console.log(`Voix : ${nb} PNJ ont des répliques enregistrées` + (aConvertir.length ? ` (à convertir en .ogg : ${aConvertir.join(", ")})` : ""));
}

// ---- les arbres géants (tronc 4 x 4) dans toutes les jungles du monde
ecrire("data/cabane/worldgen/configured_feature/arbre_geant.json", { type: "cabane:arbre_geant", config: {} });
ecrire("data/cabane/worldgen/placed_feature/arbre_geant.json", {
  feature: "cabane:arbre_geant",
  placement: [
    { type: "minecraft:rarity_filter", chance: 2 },
    { type: "minecraft:in_square" },
    { type: "minecraft:heightmap", heightmap: "WORLD_SURFACE_WG" },
    { type: "minecraft:biome" },
  ],
});
// toutes les forêts tropicales, même celles des mods qui ne les rangent pas dans « jungle »
const optionnel = (id) => ({ id, required: false });
ecrire("data/cabane/tags/worldgen/biome/jungles_geantes.json", { replace: false, values: ["#minecraft:is_jungle", optionnel("#c:is_jungle"),
  ...["biomesoplenty:rainforest", "biomesoplenty:rocky_rainforest", "biomesoplenty:tropics", "biomesoplenty:floodplain", "biomesoplenty:bayou",
    "terralith:amethyst_rainforest", "terralith:jungle_mountains", "terralith:rocky_jungle", "terralith:tropical_jungle"].map(optionnel)] });
ecrire("data/cabane/neoforge/biome_modifier/arbres_geants.json", {
  type: "neoforge:add_features", biomes: "#cabane:jungles_geantes", features: "cabane:arbre_geant", step: "vegetal_decoration",
});
