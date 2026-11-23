// Isma Kebab : les items (dessinés par la maquette docs/kebab/maquette.html, rejoués ici en 16 x 16), la broche et son
// grill, les recettes, la boutique d'Isma, les traductions, et le plan de blocs de /kebab (fr.cabane.bouffe.IsmaKebab).
// Appelé par generer.mjs.
//
// La maquette reste la source : on rejoue tel quel son code (dessins des items, pose des blocs), puis on traduit.
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const ici = path.dirname(fileURLToPath(import.meta.url));
const MAQUETTE = path.join(ici, "../../docs/kebab/maquette.html");

function morceauDe(source, debut, fin) {
  const a = source.indexOf(debut), b = source.indexOf(fin, a);
  if (a < 0 || b < a) throw new Error("repères introuvables dans la maquette du kebab : " + debut);
  return source.slice(a, b);
}

// ---------------------------------------------------------------- un petit canevas 16 x 16 (ce dont les dessins ont besoin)
function canevas() {
  const px = new Uint8ClampedArray(16 * 16 * 4);
  let couleur = [0, 0, 0, 1];
  const lire = (s) => {
    if (s.startsWith("#")) { const n = parseInt(s.slice(1), 16); return [n >> 16, (n >> 8) & 255, n & 255, 1]; }
    const m = s.match(/rgba?\(([^)]+)\)/);
    if (!m) throw new Error("couleur inconnue : " + s);
    const v = m[1].split(",").map((x) => parseFloat(x));
    return [v[0], v[1], v[2], v.length > 3 ? v[3] : 1];
  };
  return {
    px,
    set fillStyle(s) { couleur = lire(s); },
    get fillStyle() { return couleur; },
    fillRect(x, y, w, h) {
      for (let j = Math.max(0, Math.floor(y)); j < Math.min(16, Math.floor(y + h)); j++) {
        for (let i = Math.max(0, Math.floor(x)); i < Math.min(16, Math.floor(x + w)); i++) {
          const o = (j * 16 + i) * 4, a = couleur[3], b = px[o + 3] / 255, sortie = a + b * (1 - a);
          for (let k = 0; k < 3; k++) px[o + k] = sortie ? (couleur[k] * a + px[o + k] * b * (1 - a)) / sortie : 0;
          px[o + 3] = sortie * 255;
        }
      }
    },
    getImageData() { return { data: new Uint8ClampedArray(px) }; },
  };
}

/** Les pixels d'un canevas en grille + palette, pour le png() de generer.mjs. */
function versGrille(px) {
  const palette = {}, cles = new Map();
  const grille = [];
  for (let y = 0; y < 16; y++) {
    let ligne = "";
    for (let x = 0; x < 16; x++) {
      const o = (y * 16 + x) * 4;
      if (px[o + 3] === 0) { ligne += "."; continue; }
      const c = [px[o], px[o + 1], px[o + 2], px[o + 3]], k = c.join(",");
      if (!cles.has(k)) { const ch = String.fromCharCode(0x4e00 + cles.size); cles.set(k, ch); palette[ch] = c; }
      ligne += cles.get(k);
    }
    grille.push(ligne);
  }
  return [grille, palette];
}

/** Dessine une texture 16 x 16 avec une fonction (c, hasard). */
function dessiner(fn, graine) {
  const c = canevas();
  let s = graine >>> 0;
  fn(c, () => (s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
  return versGrille(c.px);
}

// ---------------------------------------------------------------- les items de la maquette
function itemsDeLaMaquette() {
  const source = fs.readFileSync(MAQUETTE, "utf8");
  const code = morceauDe(source, "// ================================================================ les items, en 16 x 16", "const IMG = {};");
  const contexte = { Math, sortie: null };
  vm.runInNewContext(`const alea = (s) => () => (s = (s * 1664525 + 1013904223) >>> 0) / 4294967296;
const rgb = (a) => "rgb(" + a[0] + "," + a[1] + "," + a[2] + ")";
${code}
sortie = { DESSINS, ITEMS, alea };`, contexte);
  return contexte.sortie;
}

// ---------------------------------------------------------------- textures des blocs
const grain = (c, r, base, amp, x0 = 0, y0 = 0, w = 16, h = 16) => {
  for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) {
    const d = (r() - 0.5) * amp;
    c.fillStyle = `rgb(${base.map((v) => Math.max(0, Math.min(255, Math.round(v + d)))).join(",")})`;
    c.fillRect(x, y, 1, 1);
  }
};
// la viande de la broche : des stries verticales (la texture est écrasée tranche par tranche), grillée sur les bords
const viande = (claire, foncee, grillee) => (c, r) => {
  for (let x = 0; x < 16; x++) {
    const base = r() < 0.35 ? foncee : r() < 0.2 ? grillee : claire;
    for (let y = 0; y < 16; y++) {
      const bord = y < 2 || y > 13;
      const d = (r() - 0.5) * 22;
      const b = bord ? grillee : base;
      c.fillStyle = `rgb(${b.map((v) => Math.max(0, Math.min(255, Math.round(v + d)))).join(",")})`;
      c.fillRect(x, y, 1, 1);
    }
  }
};
const BLOCS = {
  viande_broche: viande([150, 82, 40], [112, 58, 26], [78, 38, 16]),
  viande_poulet: viande([214, 150, 70], [184, 112, 44], [138, 74, 26]),
  grill_kebab: (c, r) => {
    grain(c, r, [42, 34, 36], 8);
    for (let x = 1; x < 16; x += 3) for (let y = 1; y < 15; y++) {
      const t = y % 4 < 2 ? [255, 122, 26] : [255, 176, 46], d = (r() - 0.5) * 40;
      c.fillStyle = `rgb(${t.map((v) => Math.max(0, Math.min(255, Math.round(v + d)))).join(",")})`;
      c.fillRect(x, y, 2, 1);
    }
    c.fillStyle = "#9aa1ab"; c.fillRect(0, 0, 16, 1); c.fillRect(0, 15, 16, 1);
  },
};

// ---------------------------------------------------------------- noms, recettes, boutique
const NOMS = {
  sandwich_kebab: "Sandwich kebab", galette_kebab: "Galette kebab", assiette_kebab: "Assiette kebab", tacos: "Tacos",
  broche: "Broche à kebab", couteau_kebab: "Couteau à kebab", viande_kebab: "Viande de kebab", pain_turc: "Pain turc",
  sauce_blanche: "Sauce blanche", sauce_samourai: "Sauce samouraï", sauce_algerienne: "Sauce algérienne", ayran: "Ayran", baklava: "Baklava",
};
const SAUCES = { sauce_blanche: "blanche", sauce_samourai: "samourai", sauce_algerienne: "algerienne" };
const c = (id) => ({ item: id.includes(":") ? id : "cabane:" + id });
// les plats : ce qu'il y a dedans (la sauce se rajoute, au choix)
const PLATS = {
  sandwich_kebab: ["pain_turc", "viande_kebab"],
  galette_kebab: ["minecraft:wheat", "viande_kebab"],
  assiette_kebab: ["viande_kebab", "viande_kebab", "frites"],
  tacos: ["minecraft:wheat", "viande_kebab", "frites"],
};
// [fichier, objet, nombre, prix, sauce]
const OFFRES = [
  ["sandwich", "sandwich_kebab", 1, 20, "blanche"], ["galette", "galette_kebab", 1, 20, "blanche"], ["assiette", "assiette_kebab", 1, 30, "blanche"],
  ["tacos", "tacos", 1, 28, "blanche"], ["ayran", "ayran", 1, 6], ["baklava", "baklava", 2, 10],
  ["sauce_blanche", "sauce_blanche", 1, 3], ["sauce_samourai", "sauce_samourai", 1, 3], ["sauce_algerienne", "sauce_algerienne", 1, 3],
  ["pain_turc", "pain_turc", 1, 4], ["viande", "viande_kebab", 1, 6], ["couteau", "couteau_kebab", 1, 60], ["broche", "broche", 1, 250], ["grill", "grill_kebab", 1, 80],
];

const morceau = (de, a, faces) => ({ from: de, to: a, faces });
const toutes = (texture, uv) => Object.fromEntries(["north", "south", "east", "west", "up", "down"].map((f) => [f, { texture, ...(uv ? { uv } : {}) }]));

export function genererKebab({ ecrire, png, lang }) {
  // ---- les items, dessinés comme dans la maquette
  const { DESSINS, ITEMS } = itemsDeLaMaquette();
  ITEMS.forEach((it, i) => {
    const [grille, palette] = dessiner(DESSINS[it.id], i * 31 + 7);
    ecrire(`assets/cabane/textures/item/${it.id}.png`, png(grille, palette));
    ecrire(`assets/cabane/models/item/${it.id}.json`, {
      parent: it.id === "couteau_kebab" ? "minecraft:item/handheld" : "minecraft:item/generated", textures: { layer0: `cabane:item/${it.id}` } });
    lang[`item.cabane.${it.id}`] = NOMS[it.id];
  });
  lang["block.cabane.broche"] = NOMS.broche;

  // ---- la broche : un socle en inox (la tige et la viande, c'est le rendu du mod qui les fait tourner)
  for (const [id, fn] of Object.entries(BLOCS)) { const [g, p] = dessiner(fn, id.length * 977); ecrire(`assets/cabane/textures/block/${id}.png`, png(g, p)); }
  ecrire("assets/cabane/models/block/broche.json", {
    parent: "minecraft:block/block", textures: { inox: "minecraft:block/iron_block", noir: "minecraft:block/black_concrete", particle: "minecraft:block/iron_block" },
    elements: [morceau([2, 0, 2], [14, 1.5, 14], toutes("#inox")), morceau([3.5, 1.5, 3.5], [12.5, 2, 12.5], toutes("#noir"))],
  });
  ecrire("assets/cabane/blockstates/broche.json", { variants: { "": { model: "cabane:block/broche" } } });
  ecrire("data/cabane/loot_table/blocks/broche.json", { type: "minecraft:block",
    pools: [{ rolls: 1, entries: [{ type: "minecraft:item", name: "cabane:broche" }], conditions: [{ condition: "minecraft:survives_explosion" }] }] });
  // la viande, une tranche (pas d'objet)
  for (const [poulet, tex] of [[false, "viande_broche"], [true, "viande_poulet"]]) {
    ecrire(`assets/cabane/models/block/${tex}.json`, { parent: "minecraft:block/cube_all", textures: { all: `cabane:block/${tex}` } });
  }
  ecrire("assets/cabane/blockstates/viande_broche.json", { variants: {
    "poulet=false": { model: "cabane:block/viande_broche" }, "poulet=true": { model: "cabane:block/viande_poulet" } } });
  lang["block.cabane.viande_broche"] = "Viande de la broche";

  // ---- le grill : une plaque rougeoyante, la broche se met devant
  ecrire("assets/cabane/models/block/grill_kebab.json", {
    parent: "minecraft:block/block", textures: { grill: "cabane:block/grill_kebab", inox: "minecraft:block/iron_block", particle: "minecraft:block/iron_block" },
    elements: [
      morceau([1, 0, 1], [15, 16, 4], { north: { texture: "#grill" }, south: { texture: "#inox" }, east: { texture: "#inox" }, west: { texture: "#inox" },
        up: { texture: "#inox" }, down: { texture: "#inox" } }),
      morceau([0.5, 0, 0], [1.5, 16, 4.5], toutes("#inox")), morceau([14.5, 0, 0], [15.5, 16, 4.5], toutes("#inox")),
    ],
  });
  ecrire("assets/cabane/blockstates/grill_kebab.json", { variants: Object.fromEntries(
    [["north", 0], ["east", 90], ["south", 180], ["west", 270]].map(([f, y]) => [`facing=${f}`, { model: "cabane:block/grill_kebab", ...(y ? { y } : {}) }])) });
  ecrire("assets/cabane/models/item/grill_kebab.json", { parent: "cabane:block/grill_kebab" });
  ecrire("data/cabane/loot_table/blocks/grill_kebab.json", { type: "minecraft:block",
    pools: [{ rolls: 1, entries: [{ type: "minecraft:item", name: "cabane:grill_kebab" }], conditions: [{ condition: "minecraft:survives_explosion" }] }] });
  lang["block.cabane.grill_kebab"] = "Grill à broche";

  // ---- les recettes
  const recette = (id, ingredients, result, conditions) => ecrire(`data/cabane/recipe/${id}.json`, {
    ...(conditions ? { "neoforge:conditions": conditions } : {}),
    type: "minecraft:crafting_shapeless", category: "food", group: "isma_kebab", ingredients: ingredients.map(c), result });
  const avec = (sauce) => ({ "minecraft:custom_data": { sauce } });
  for (const [plat, dedans] of Object.entries(PLATS)) {
    recette(plat, dedans, { id: `cabane:${plat}`, count: 1 });
    for (const [s, nom] of Object.entries(SAUCES)) {
      recette(`${plat}_${nom}`, [...dedans, s], { id: `cabane:${plat}`, count: 1, components: avec(nom) });
      // changer la sauce d'un plat déjà fait
      recette(`${plat}_resauce_${nom}`, [plat, s], { id: `cabane:${plat}`, count: 1, components: avec(nom) });
    }
  }
  recette("pain_turc", ["minecraft:wheat", "minecraft:wheat", "minecraft:wheat_seeds"], { id: "cabane:pain_turc", count: 2 });
  recette("sauce_blanche", ["minecraft:milk_bucket", "minecraft:egg"], { id: "cabane:sauce_blanche", count: 4 });
  recette("sauce_samourai", ["sauce_blanche", "minecraft:sweet_berries"], { id: "cabane:sauce_samourai", count: 1 });
  recette("sauce_algerienne", ["sauce_blanche", "farmersdelight:onion"], { id: "cabane:sauce_algerienne", count: 1 },
    [{ type: "neoforge:mod_loaded", modid: "farmersdelight" }]);
  recette("ayran", ["minecraft:milk_bucket", "minecraft:glass_bottle", "minecraft:glass_bottle", "minecraft:glass_bottle"], { id: "cabane:ayran", count: 3 });
  recette("baklava", ["minecraft:wheat", "minecraft:wheat", "minecraft:honey_bottle"], { id: "cabane:baklava", count: 3 });
  ecrire("data/cabane/recipe/couteau_kebab.json", { type: "minecraft:crafting_shaped", category: "equipment", pattern: ["  I", " I ", "S  "],
    key: { I: { item: "minecraft:iron_ingot" }, S: { item: "minecraft:stick" } }, result: { id: "cabane:couteau_kebab", count: 1 } });

  // ---- la boutique d'Isma (son PNJ : outils/pnj.mjs, « kebabier »)
  OFFRES.forEach(([fichier, item, nombre, prix, sauce], i) => ecrire(`data/cabane/boutique/kebab_${fichier}.json`,
    { item: `cabane:${item}`, nombre, prix, ordre: 500 + i, vendeur: "pnj.kebabier", ...(sauce ? { sauce } : {}) }));

  // ---- le plan de /kebab
  return genererPlan(ecrire);
}

// ================================================================ le plan de blocs
function genererPlan(ecrire) {
  const source = fs.readFileSync(MAQUETTE, "utf8");
  const code = morceauDe(source, "// ================================================================ la rue", "// ================================================================ vues et zones");
  const cases = new Map();
  const cle = (x, y, z) => x + "," + y + "," + z;
  function pose(x, y, z, nom, m) { if (nom) cases.set(cle(x, y, z), { x, y, z, nom, m: m || 0 }); else cases.delete(cle(x, y, z)); }
  function zone(x0, y0, z0, x1, y1, z1, nom, m) { for (let z = z0; z <= z1; z++) for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) pose(x, y, z, nom, m); }
  const vide = (x0, y0, z0, x1, y1, z1) => zone(x0, y0, z0, x1, y1, z1, null);
  vm.runInNewContext(code, { pose, zone, vide, Math, X0: -10, X1: 26, Z0: -14, Z1: 20 });

  // ---------------------------------------------------------------- ce que le jeu fait autrement
  // la rue reste celle du monde : on ne garde qu'un trottoir devant, pour la terrasse
  for (const [k, b] of cases) if (["asphalte", "ligne", "bordure", "trottoir"].includes(b.nom) || (["poteau", "lanterne", "poubelle"].includes(b.nom) && b.z < 0)) cases.delete(k);
  zone(-3, 0, -8, 17, 0, -1, "trottoir");
  for (const x of [-2, 16]) { zone(x, 1, -6, x, 5, -6, "poteau"); pose(x, 6, -6, "lanterne"); }
  pose(15, 1, -1, "poubelle");
  // l'auvent rayé : un seul rang en pente
  for (const [k, b] of cases) if (b.nom === "auventH" || b.nom === "auventB") cases.delete(k);
  zone(1, 4, -1, 13, 4, -1, "auvent");
  // la porte vitrée et son paillasson
  pose(7, 1, 0, "porte"); pose(7, 2, 0, "porteH");
  // les plafonniers : encastrés dans le toit ; la hotte contre le mur du fond seulement
  for (const [k, b] of [...cases]) if (b.nom === "plafonnier") { cases.delete(k); pose(b.x, b.y + 1, b.z, "dalle_lumineuse"); }
  for (const [k, b] of [...cases]) if (b.nom === "hotte" && b.z !== 11) cases.delete(k);
  // la cheminée : un conduit, et un feu de camp qui fume tout en haut
  // (les crudités, les sauces, les pains et les frites : des cadres posés à plat, plus bas)
  for (const [k, b] of [...cases]) if (["bacs", "sauces", "serviettes"].includes(b.nom)) cases.delete(k);

  // ---------------------------------------------------------------- traduction en blocs (devant = nord, z négatifs)
  const SIMPLES = {
    trottoir: "minecraft:smooth_stone", toit: "minecraft:smooth_quartz", crepi: "minecraft:smooth_quartz",
    mur_o: "minecraft:smooth_quartz", mur_e: "minecraft:smooth_quartz", mur_s: "minecraft:smooth_quartz", mur_n: "minecraft:smooth_quartz",
    murh_o: "minecraft:red_concrete", murh_e: "minecraft:red_concrete", murh_s: "minecraft:red_concrete", murh_n: "minecraft:red_concrete",
    caisson: "minecraft:yellow_concrete", verre: "minecraft:glass_pane", dalle_lumineuse: "minecraft:sea_lantern",
    auvent: "mcwroofs:red_striped_awning[facing=south,shape=straight]",
    poteau: "minecraft:polished_blackstone_wall", poubelle: "minecraft:cauldron", clim: "minecraft:iron_block",
    tuyau: "minecraft:andesite_wall",
    tablette: "minecraft:spruce_slab[type=top]", tabouret: "refurbished_furniture:red_stool",
    tablebois: "handcrafted:dark_oak_table[color=none]", tablepl: "handcrafted:birch_table[color=white]",
    comptoir: "refurbished_furniture:red_kitchen_cabinetry[facing=north]",
    plancha: "farmersdelight:stove[facing=north,lit=true]", pains: "refurbished_furniture:light_gray_kitchen_drawer[facing=north]",
    planche: "refurbished_furniture:light_gray_kitchen_cabinetry[facing=north]", plan: "refurbished_furniture:light_gray_kitchen_cabinetry[facing=north]",
    friteuse: "refurbished_furniture:light_gray_kitchen_cabinetry[facing=north]", evier: "refurbished_furniture:light_gray_kitchen_sink[facing=north]",
    grill: "cabane:grill_kebab[facing=north]", hotte: "handcrafted:kitchen_hood[facing=north]",
  };
  const FINITIONS = {
    lanterne: "minecraft:lantern[hanging=false]", chapeau: "minecraft:campfire[lit=true,signal_fire=false,facing=north]",
    paillasson: "refurbished_furniture:door_mat[facing=north]", antimouche: "minecraft:soul_lantern[hanging=true]",
    porte: "minecraft:birch_door[half=lower,facing=south,hinge=left,open=false,powered=false]",
    porteH: "minecraft:birch_door[half=upper,facing=south,hinge=left,open=false,powered=false]",
    caisse: "refurbished_furniture:computer[facing=south]",
  };
  function traduire(b) {
    switch (b.nom) {
      case "carrelage": return (b.x + b.z) % 2 ? "minecraft:white_terracotta" : "minecraft:terracotta";
      // les chaises regardent la table
      case "chaiserouge": return `handcrafted:dark_oak_chair[color=red,facing=${b.m === 2 ? "south" : "north"}]`;
      case "chaisepl": return `handcrafted:birch_chair[color=white,facing=${b.m === 2 ? "south" : "north"}]`;
      // le frigo à canettes regarde la salle (vers l'ouest), le frigo inox regarde la cuisine
      case "frigo": return `refurbished_furniture:light_${b.y === 1 ? "fridge" : "freezer"}[facing=west]`;
      case "frigoinox": return `refurbished_furniture:light_${b.y === 1 ? "fridge" : "freezer"}[facing=north]`;
      case "socle": return `cabane:broche[poulet=${b.x === 7}]`;
      default: if (SIMPLES[b.nom]) return SIMPLES[b.nom];
    }
    throw new Error("bloc du kebab inconnu : " + b.nom);
  }

  // rien ne dépasse de la zone que /kebab photographie (IsmaKebab : x -3..17, y -5..9, z -8..13)
  for (const b of cases.values()) if (b.x < -3 || b.x > 17 || b.y < -5 || b.y > 9 || b.z < -8 || b.z > 13) throw new Error("bloc hors zone : " + JSON.stringify(b));
  const palette = [], indice = new Map();
  const numero = (etat) => { if (!indice.has(etat)) { indice.set(etat, palette.length); palette.push(etat); } return indice.get(etat); };
  const structure = [], finitions = [];
  const metS = (x, y, z, etat) => structure.push({ x, y, z, p: numero(etat) });
  const metF = (x, y, z, etat) => finitions.push({ x, y, z, p: numero(etat) });
  for (const b of cases.values()) {
    if (FINITIONS[b.nom]) metF(b.x, b.y, b.z, FINITIONS[b.nom]);
    else metS(b.x, b.y, b.z, traduire(b));
  }
  // la planche à découper sur son meuble, les chaînes de la carte, des lumières cachées devant l'enseigne et en cuisine
  metF(3, 2, 11, "farmersdelight:cutting_board[facing=north]");
  for (const x of [5, 9]) metF(x, 4, 8, "minecraft:chain[axis=y]");
  for (const x of [1, 4, 7, 10, 13]) metS(x, 6, -2, "minecraft:light[level=15,waterlogged=false]");
  for (const x of [2, 6, 10]) metS(x, 3, 10, "minecraft:light[level=12,waterlogged=false]");
  for (const x of [3, 11]) metS(x, 3, -5, "minecraft:light[level=11,waterlogged=false]");

  // ---------------------------------------------------------------- les cadres posés à plat sur le comptoir et en cuisine
  const cadres = [
    [4, 2, 8, "up", "farmersdelight:cabbage_leaf"], [5, 2, 8, "up", "farmersdelight:tomato"], [6, 2, 8, "up", "farmersdelight:onion"],
    [7, 2, 8, "up", "minecraft:beetroot"], [8, 2, 8, "up", "cabane:viande_kebab"],
    [9, 2, 8, "up", "cabane:sauce_blanche"], [10, 2, 8, "up", "cabane:sauce_samourai"], [11, 2, 8, "up", "cabane:sauce_algerienne"],
    [12, 2, 8, "up", "minecraft:paper"], [2, 2, 11, "up", "cabane:pain_turc"], [9, 2, 11, "up", "cabane:frites"], [10, 2, 11, "up", "cabane:frites"],
    [5, 2, 11, "up", "cabane:sandwich_kebab", "blanche"], [6, 2, 11, "up", "cabane:galette_kebab", "samourai"], [8, 2, 11, "up", "cabane:tacos", "algerienne"],
  ];

  // ---------------------------------------------------------------- les textes : enseigne, carte, affiche du chef, néon
  const t = (texte) => texte;
  const textes = [
    { x: 7.5, y: 5.42, z: -1.07, lacet: 180, echelle: 5.2, fond: "0", largeur: 400, lumiere: true,
      texte: t({ text: "ISMA KEBAB", color: "#d8202a", bold: true }) },
    { x: 7.5, y: 5.05, z: -1.07, lacet: 180, echelle: 1.45, fond: "0", largeur: 600, lumiere: true,
      texte: t({ text: "SANDWICH · GALETTE · TACOS · ASSIETTE", color: "#7a1014", bold: true }) },
    { x: 11.5, y: 1.55, z: 0.38, lacet: 180, echelle: 1.3, fond: "0", largeur: 200, lumiere: true, ombre: true,
      texte: t({ text: "OUVERT", color: "#ff2a3a", bold: true }) },
    { x: 3.5, y: 2.35, z: 0.38, lacet: 180, echelle: 0.75, fond: "0", largeur: 200, lumiere: true, ombre: true,
      texte: t({ text: "SUR PLACE\nOU À EMPORTER", color: "#ffd21f", bold: true }) },
    { x: 7.5, y: 3.0, z: 8.5, lacet: 180, echelle: 0.95, fond: "f01b1b1f", largeur: 400, lumiere: true,
      texte: t([{ text: "LA CARTE\n", color: "#ffd21f", bold: true },
        { text: "Sandwich 20 $   Galette 20 $\nAssiette 30 $   Tacos 28 $\nAyran 6 $   Baklava 10 $", color: "white" }]) },
    { x: 13.95, y: 1.75, z: 9.6, lacet: 90, echelle: 0.9, fond: "fff4ead2", largeur: 200,
      texte: t([{ text: "SAUCE DU CHEF\n", color: "#c4262c", bold: true }, { text: "faite maison\ncomme il faut\n", color: "#2a1a10", italic: true },
        { text: "ISMA KEBAB", color: "#c4262c", bold: true }]) },
  ];

  // ---------------------------------------------------------------- écriture
  const ordre = (a, b) => a.y - b.y || a.z - b.z || a.x - b.x;
  const aplatir = (l) => l.sort(ordre).flatMap((b) => [b.x, b.y, b.z, b.p]);
  const plan = { palette, structure: aplatir(structure), finitions: aplatir(finitions), cadres, textes, kebabier: [2.5, 1, 9.5, 180] };
  ecrire("data/cabane/kebab/plan.json", JSON.stringify(plan));
  return `${structure.length} blocs, ${finitions.length} finitions, ${cadres.length} cadres, ${palette.length} sortes`;
}
