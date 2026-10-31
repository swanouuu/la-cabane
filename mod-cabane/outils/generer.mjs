// Génère les ressources du mod (textures, modèles, traductions, butins, tags, succès, PNJ).
// Lancer depuis n'importe où :  node mod-cabane/outils/generer.mjs
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
  telephone: [[
    "................",
    "....KKKKKKKK....",
    "...KDDDDDDDDK...",
    "...KDLLLLLLDK...",
    "...KDLSSLLLDK...",
    "...KDLSLLLLDK...",
    "...KDLLLLLLDK...",
    "...KDLLLLLLDK...",
    "...KDDDDDDDDK...",
    "...KDBDBDBDDK...",
    "...KDDDDDDDDK...",
    "...KDBDBDBDDK...",
    "...KDDDDDDDDK...",
    "...KDDDGGDDDK...",
    "....KKKKKKKK....",
    "................"], { K: [12, 12, 16], D: [52, 56, 66], L: [64, 170, 160], S: [190, 245, 230], B: [120, 126, 140], G: [90, 200, 110] }],
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
  "itemGroup.cabane": "La Cabane",
  "item.cabane.telephone": "Téléphone",
  "item.cabane.telephone.desc": "Clic droit ou touche N : choisis ton numéro et appelle tes potes.",
  "item.cabane.relique": "Relique de la Cabane",
  "screen.cabane.telephone": "Téléphone",
  "key.cabane.telephone": "Ouvrir le téléphone",
  "key.categories.cabane": "La Cabane",
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
  display: { icon: { id: "cabane:telephone" }, title: { text: "La Cabane" }, description: { text: "Le centre de la map, et de vos aventures." },
    background: "minecraft:textures/block/stripped_spruce_log.png", frame: "task", show_toast: false, announce_to_chat: false },
  criteria: { arrivee: { trigger: "minecraft:tick" } },
});
ecrire("data/cabane/advancement/premier_appel.json", {
  parent: "cabane:racine",
  display: { icon: { id: "cabane:telephone" }, title: { text: "Allô ?" }, description: { text: "Passer ton premier appel." }, frame: "task" },
  criteria: { appel: { trigger: "minecraft:impossible" } },
});
ecrire("data/cabane/advancement/sept_reliques.json", {
  parent: "cabane:relique_oeil_du_gardien",
  display: { icon: { id: "minecraft:elytra" }, title: { text: "Le Réveil de la Cabane" }, description: { text: "Réunir les sept reliques." },
    frame: "challenge", show_toast: true, announce_to_chat: true },
  criteria: Object.fromEntries(RELIQUES.map((r) => [r.id, { trigger: "minecraft:inventory_changed", conditions: { items: [{ items: `cabane:${r.id}` }] } }])),
});

// ---------------------------------------------------------------- recette du téléphone
ecrire("data/cabane/recipe/telephone.json", {
  type: "minecraft:crafting_shaped", category: "misc",
  pattern: [" G ", "IRI", "ICI"],
  key: { G: { item: "minecraft:glass_pane" }, I: { item: "minecraft:iron_ingot" }, R: { item: "minecraft:redstone" }, C: { item: "minecraft:copper_ingot" } },
  result: { id: "cabane:telephone", count: 1 },
});

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

// ---------------------------------------------------------------- PNJ (Easy NPC)
const uuid = (graine) => {
  // UUID fixe par PNJ, pour que les quêtes puissent le retrouver
  let h = 2166136261;
  const ints = [];
  for (let i = 0; i < 4; i++) {
    for (const ch of `${graine}#${i}`) h = Math.imul(h ^ ch.charCodeAt(0), 16777619);
    ints.push(h | 0);
  }
  return `[I;${ints.join(",")}]`;
};
const snbtTexte = (s) => JSON.stringify(s);
const nomJson = (texte, couleur) => `'${JSON.stringify({ text: texte, color: couleur }).replace(/'/g, "\\'")}'`;

function pnj({ fichier, nom, couleur, variante, dialogues }) {
  const entrees = dialogues.map((d) => `{
        Label:${snbtTexte(d.label)},
        Name:${snbtTexte(d.titre)},
        Text:${snbtTexte(d.texte)},
        Texts:[{Text:${snbtTexte(d.texte)}}],
        Buttons:[${d.boutons.map((b) => `{
          Label:${snbtTexte(b.label)},
          Name:${snbtTexte(b.texte)},
          Type:${snbtTexte(b.vers ? "ACTION" : "CLOSE")},
          Actions:[${b.vers ? `{Type:"OPEN_NAMED_DIALOG",Cmd:${snbtTexte(b.vers)}}` : ""}]
        }`).join(",")}]
      }`).join(",");
  ecrire(`data/cabane/pnj/${fichier}.snbt`, `{
  id:"easy_npc:humanoid",
  UUID:${uuid(fichier)},
  CustomName:${nomJson(nom, couleur)},
  CustomNameVisible:1b,
  Invulnerable:1b,
  PersistenceRequired:1b,
  EasyNPCVersion:3,
  VariantType:${snbtTexte(variante)},
  SkinData:{Type:"DEFAULT"},
  ObjectiveData:{ObjectiveDataSet:[{Type:"LOOK_AT_RESET"},{Type:"LOOK_AT_PLAYER"}]},
  ActionData:{ActionEventSet:{ON_INTERACTION:[{Type:"OPEN_DEFAULT_DIALOG"}]}},
  DialogData:{
    DialogDataSet:{
      Type:"STANDARD",
      DialogDataSet:[${entrees}]
    }
  },
  Status:{finalized:1b}
}
`);
}

pnj({ fichier: "gardien", nom: "Le Vieux Gardien", couleur: "gold", variante: "STEVE", dialogues: [
  { label: "default", titre: "Accueil", texte: "Ah… des voyageurs. Ça faisait longtemps. Cette cabane était le refuge de la Confrérie des Explorateurs. Aujourd'hui, elle est à vous.",
    boutons: [{ label: "reliques", texte: "La Confrérie ?", vers: "reliques" }, { label: "bye", texte: "À plus tard" }] },
  { label: "reliques", titre: "Les Sept Reliques", texte: "Sept reliques reposaient sur le comptoir. Elles ont été cachées dans les plus grands donjons du monde, parfois très loin d'ici. Ramenez-les, et la Cabane se réveillera. Tout est noté dans votre livre de quêtes.",
    boutons: [{ label: "cartes", texte: "Comment les trouver ?", vers: "cartes" }, { label: "bye", texte: "J'y vais !" }] },
  { label: "cartes", titre: "Les cartes", texte: "Mira, la cartographe, vous guidera : chaque quête d'indice vous donne une carte au trésor. Prenez des provisions… et vos téléphones.",
    boutons: [{ label: "bye", texte: "Merci, Gardien" }] },
] });
pnj({ fichier: "cartographe", nom: "Mira la Cartographe", couleur: "aqua", variante: "ALEX", dialogues: [
  { label: "default", titre: "Mira", texte: "Des cartes, des cartes, encore des cartes ! Chaque relique a la sienne. Complète les quêtes d'indice dans ton livre et je te les donne.",
    boutons: [{ label: "conseil", texte: "Un conseil ?", vers: "conseil" }, { label: "bye", texte: "Merci Mira" }] },
  { label: "conseil", titre: "Conseil", texte: "Les citadelles du ciel et les fonderies sont très loin. Pose des Waystones en chemin, et ne pars jamais sans lit.",
    boutons: [{ label: "bye", texte: "Noté !" }] },
] });
pnj({ fichier: "marchand", nom: "Bako le Marchand", couleur: "green", variante: "STEVE", dialogues: [
  { label: "default", titre: "Bako", texte: "Mes étals sont encore vides… Reviens quand la Cabane aura repris vie, j'aurai de quoi faire affaire !",
    boutons: [{ label: "bye", texte: "À bientôt" }] },
] });

console.log("Ressources générées dans", RES);
