// Le Laboratoire : la dimension vide « cabane:laboratoire » (type, générateur, biome glauque), et les trois blocs
// qui vont avec — le terminal qui y emmène, le bouton rouge qui ramène, la table d'expériences —
// avec leurs textures dessinées ici, leurs modèles 3D et leurs noms. Appelé par generer.mjs.

// ---------------------------------------------------------------- textures 16 x 16, dessinées pixel par pixel

/** Une texture à partir d'une fonction (x, y) → [r, g, b] ou [r, g, b, a]. */
function peindre(png, f) {
  const palette = {}, index = new Map(), grille = [];
  for (let y = 0; y < 16; y++) {
    let ligne = "";
    for (let x = 0; x < 16; x++) {
      const c = f(x, y).map((v) => Math.max(0, Math.min(255, Math.round(v))));
      const cle = c.join(",");
      if (!index.has(cle)) {
        const ch = String.fromCodePoint(0x100 + index.size);
        index.set(cle, ch);
        palette[ch] = c;
      }
      ligne += index.get(cle);
    }
    grille.push(ligne);
  }
  return png(grille, palette);
}

// un bruit fixe (même dessin à chaque génération)
const bruit = (x, y, graine = 0) => {
  let h = (x * 374761393 + y * 668265263 + graine * 2147483647) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
};
const teinte = (c, d) => c.map((v) => v + d);

// l'acier brossé de la table et du pupitre : traits horizontaux, bord plus sombre
const metal = (x, y) => {
  const bord = x === 0 || y === 0 || x === 15 || y === 15;
  const base = [148, 154, 160];
  const brosse = (bruit(0, y, 3) - 0.5) * 16 + (bruit(x, y, 4) - 0.5) * 8;
  return bord ? [96, 100, 108] : teinte(base, brosse);
};

// l'acier sombre et riveté du terminal
const rivet = (x, y) => [[2, 2], [13, 2], [2, 13], [13, 13]].some(([a, b]) => a === x && b === y);
const metalSombre = (x, y) => {
  if (rivet(x, y)) return [150, 154, 160];
  if (rivet(x - 1, y - 1)) return [24, 26, 30];
  const bord = x === 0 || y === 0 || x === 15 || y === 15;
  return bord ? [30, 32, 36] : teinte([62, 66, 72], (bruit(x, y, 7) - 0.5) * 10);
};

// les bandes jaunes et noires « danger »
const danger = (x, y) => {
  const jaune = Math.floor((x + y) / 4) % 2 === 0;
  const sale = (bruit(x, y, 11) - 0.5) * 18;
  return jaune ? teinte([226, 182, 28], sale) : teinte([30, 30, 32], sale / 2);
};

// le drap de la table : blanc cassé, plis, et des taches de sang séché
const TACHES = [[4, 5, 2.6], [10, 9, 1.8], [7, 12, 1.4], [12, 3, 1.1]];
const drap = (x, y) => {
  let c = teinte([226, 224, 214], (bruit(x, y, 21) - 0.5) * 10 - (y % 5 === 0 ? 8 : 0));
  for (const [cx, cy, r] of TACHES) {
    const d = Math.hypot(x - cx, (y - cy) * 1.2) + (bruit(x, y, 22) - 0.5) * 1.6;
    if (d < r) c = d < r * 0.55 ? [92, 8, 10] : [140, 18, 20];
  }
  // une coulure qui descend de la grande tache
  if (x === 4 && y > 7 && y < 11) c = [120, 12, 14];
  return c;
};

// la sangle de cuir et sa boucle
const sangle = (x, y) => {
  if (x >= 6 && x <= 9 && y >= 5 && y <= 10) {
    const creux = x >= 7 && x <= 8 && y >= 7 && y <= 8;
    return creux ? [60, 34, 18] : [176, 180, 186];
  }
  return teinte(y === 0 || y === 15 ? [58, 34, 18] : [104, 62, 34], (bruit(x, y, 31) - 0.5) * 14);
};

// le gros bouton rouge, verni
const bouton = (x, y) => {
  const d = Math.hypot(x - 5, y - 5);
  if (d < 3) return [255, 150, 150];
  return teinte([200, 22, 22], -Math.hypot(x - 4, y - 4) * 4);
};

// l'avant du terminal : un écran vert où un œil regarde, un clavier, deux voyants, la bande danger en bas
const oeil = (x, y) => {
  const cx = 7.5, cy = 5.5;
  const e = ((x - cx) / 5.2) ** 2 + ((y - cy) / 2.9) ** 2;
  const iris = Math.hypot(x - cx, y - cy);
  if (iris < 0.9) return "pupille";
  if (iris < 2.1 && e < 1) return "iris";
  if (e < 1.25 && e > 0.62) return "contour";
  return null;
};
const terminalFace = (x, y) => {
  if (y >= 14) return danger(x, y);
  if (x >= 2 && x <= 13 && y >= 2 && y <= 9) {
    const z = oeil(x, y);
    const ligne = y % 2 === 0 ? 0 : -10;
    if (z === "pupille") return [4, 20, 8];
    if (z === "iris") return [150, 255, 160];
    if (z === "contour") return [70, 230, 110];
    return teinte([12, 52, 22], ligne + (bruit(x, y, 41) - 0.5) * 12);
  }
  if (x >= 2 && x <= 13 && (y === 1 || y === 10)) return [20, 22, 26];
  if ((x === 1 || x === 14) && y >= 1 && y <= 10) return [20, 22, 26];
  // le clavier
  if (y >= 11 && y <= 12 && x >= 2 && x <= 8) return x % 2 === 0 ? [120, 124, 130] : [40, 42, 46];
  if (y === 12 && x === 11) return [255, 40, 40];
  if (y === 12 && x === 13) return [60, 255, 90];
  return metalSombre(x, y);
};
const terminalCote = (x, y) => {
  if (y >= 13) return danger(x, y);
  if (x === 7 || x === 8) return [36, 38, 42];
  if (y >= 4 && y <= 8 && (x === 3 || x === 12) ) return [18, 18, 20];
  return metalSombre(x, y);
};
const terminalDessus = (x, y) => {
  if (x >= 3 && x <= 12 && y >= 3 && y <= 12) return y % 2 === 0 ? [16, 16, 18] : [84, 88, 94];
  return metalSombre(x, y);
};

// la pierre de l'autel : grise, gravée de runes, tachée de sang séché
const pierreAutel = (x, y) => teinte([118, 112, 108], (bruit(x, y, 51) - 0.5) * 22 + (bruit(x >> 2, y >> 2, 52) - 0.5) * 14);
const autelDessus = (x, y) => {
  let c = pierreAutel(x, y);
  if ((x === 2 || x === 13) && y > 1 && y < 14) c = teinte(c, -38);
  if ((y === 4 || y === 11) && x > 3 && x < 12 && (x + y) % 3 !== 0) c = teinte(c, -34);
  const d = Math.hypot(x - 8, y - 9) + (bruit(x, y, 53) - 0.5) * 2.5;
  if (d < 3.2) c = d < 1.8 ? [70, 6, 8] : [104, 14, 16];
  return c;
};
const autelCote = (x, y) => {
  let c = pierreAutel(x, y);
  if (y === 3 || y === 12) c = teinte(c, -30);
  if (y > 4 && y < 11 && [2, 5, 9, 12].includes(x) && bruit(x, y, 54) > 0.3) c = teinte(c, -40);
  if (x === 7 && y > 9) c = [96, 12, 14];
  return c;
};

// ---------------------------------------------------------------- le sceau du rituel (256 x 256, posé au sol en très grand)

/** Une image de n × n à partir d'une fonction (x, y) → [r, g, b, a]. */
function peindreGrand(png, n, f) {
  const palette = {}, index = new Map(), grille = [];
  for (let y = 0; y < n; y++) {
    let ligne = "";
    for (let x = 0; x < n; x++) {
      const c = f(x, y).map((v) => Math.max(0, Math.min(255, Math.round(v))));
      const cle = c.join(",");
      if (!index.has(cle)) {
        const ch = String.fromCodePoint(0x100 + index.size);
        index.set(cle, ch);
        palette[ch] = c;
      }
      ligne += index.get(cle);
    }
    grille.push(ligne);
  }
  return png(grille, palette);
}

/** Un tirage pseudo-aléatoire fixe. */
function hasard(graine) {
  let a = graine >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Les runes d'une bande : pour chaque case, quelques traits (en coordonnées de la case, de 0 à 1). */
function runes(nombre, graine) {
  const h = hasard(graine);
  return Array.from({ length: nombre }, () => {
    const traits = [];
    const n = 3 + Math.floor(h() * 3);
    for (let i = 0; i < n; i++) {
      const forme = h();
      const a = 0.15 + h() * 0.7, b = 0.15 + h() * 0.7, c = 0.15 + h() * 0.7;
      if (forme < 0.35) traits.push([0.15 + h() * 0.2, a, 0.6 + h() * 0.25, a]);
      else if (forme < 0.7) traits.push([a, 0.15 + h() * 0.2, a, 0.6 + h() * 0.25]);
      else traits.push([a, b, c, 0.15 + h() * 0.7]);
    }
    return traits;
  });
}

const BANDES = [
  { r0: 101, r1: 117, cases: 46, graine: 11 },
  { r0: 74, r1: 95, cases: 30, graine: 22 },
  { r0: 48, r1: 68, cases: 20, graine: 33 },
];
for (const b of BANDES) b.runes = runes(b.cases, b.graine);
const ANNEAUX = [[119, 122], [97.5, 99.5], [70.5, 72.5], [44, 46], [20, 22]];

const distSegment = (px, py, [x0, y0, x1, y1]) => {
  const dx = x1 - x0, dy = y1 - y0, l = dx * dx + dy * dy || 1;
  const t = Math.max(0, Math.min(1, ((px - x0) * dx + (py - y0) * dy) / l));
  return Math.hypot(px - x0 - t * dx, py - y0 - t * dy);
};

/** L'intensité d'un trait du sceau en ce point (1 : en plein dessus, 0 : rien). */
function traitSceau(x, y) {
  const dx = x + 0.5 - 128, dy = y + 0.5 - 128;
  const r = Math.hypot(dx, dy), theta = (Math.atan2(dy, dx) + Math.PI * 2) % (Math.PI * 2);
  let d = Infinity;
  for (const [a, b] of ANNEAUX) d = Math.min(d, r < a ? a - r : r > b ? r - b : 0);
  for (const b of BANDES) {
    if (r < b.r0 || r > b.r1) continue;
    const pos = theta / (Math.PI * 2) * b.cases, i = Math.floor(pos);
    const largeur = Math.PI * 2 * r / b.cases, hauteur = b.r1 - b.r0;
    const s = pos - i, t = (r - b.r0) / hauteur;
    for (const trait of b.runes[i]) {
      const [s0, t0, s1, t1] = trait;
      d = Math.min(d, distSegment(s * largeur, t * hauteur, [s0 * largeur, t0 * hauteur, s1 * largeur, t1 * hauteur]));
    }
  }
  // des rayons entre les deux anneaux du milieu
  if (r > 72.5 && r < 74 || r > 46 && r < 48) d = Math.min(d, 0.6);
  return d < 0.8 ? 1 : d < 1.8 ? 0.45 : 0;
}

const sceau = (x, y) => {
  const r = Math.hypot(x + 0.5 - 128, y + 0.5 - 128);
  if (r > 124) return [0, 0, 0, 0];
  const i = traitSceau(x, y);
  if (i >= 1) return [255, 70, 80, 255];
  if (i > 0) return [235, 30, 45, 190];
  // un voile rouge dans tout le cercle, plus dense vers le bord
  return [190, 12, 28, 46 + Math.round(30 * (r / 124) ** 2)];
};

// ---------------------------------------------------------------- modèles

const face = (texture, uv) => ({ texture, ...(uv ? { uv } : {}) });
/** Une boîte : toutes ses faces avec la même texture (sauf {@code dessus}, si donnée). */
function boite(from, to, cote, dessus = cote, extra = {}) {
  const faces = {};
  for (const f of ["north", "south", "east", "west", "down"]) faces[f] = face(cote);
  faces.up = face(dessus);
  return { from, to, faces, ...extra };
}
const LUEUR = { neoforge_data: { block_light: 15, sky_light: 15 } };

/** Les deux moitiés de la table, dessinées pour facing = nord (la tête au nord). */
function moitieTable(tete) {
  const el = [
    boite([3, 0, 3], [13, 1, 13], "#metal"),
    boite([6, 1, 6], [10, 8, 10], "#metal"),
    boite([0, 8, 0], [16, 10, 16], "#metal"),
    boite(tete ? [1, 10, 1] : [1, 10, 0], tete ? [15, 11, 16] : [15, 11, 15], "#drap"),
    // les sangles : la poitrine côté tête, les chevilles côté pieds
    boite([0.5, 9.5, tete ? 10 : 9], [15.5, 11.4, tete ? 12 : 11], "#sangle"),
  ];
  if (tete) el.push(boite([3, 11, 1.5], [13, 12.6, 5], "#drap"));
  // les rails sur les côtés
  el.push(boite([0, 10, tete ? 1 : 0], [0.6, 10.6, tete ? 16 : 15], "#metal"), boite([15.4, 10, tete ? 1 : 0], [16, 10.6, tete ? 16 : 15], "#metal"));
  return {
    parent: "minecraft:block/block",
    ambientocclusion: false,
    textures: { particle: "cabane:block/labo_metal", metal: "cabane:block/labo_metal", drap: "cabane:block/labo_drap", sangle: "cabane:block/labo_sangle" },
    elements: el,
  };
}

/** L'autel de pierre (deux moitiés comme la table), dessiné pour facing = nord : un bloc taillé, un appui-tête à la tête. */
function moitieAutel(tete) {
  const el = [boite([0, 0, 0], [16, 11, 16], "#cote", "#dessus")];
  if (tete) el.push(boite([3, 11, 1], [13, 12.5, 4.5], "#cote", "#cote"));
  return {
    parent: "minecraft:block/block",
    textures: { particle: "cabane:block/labo_autel_cote", cote: "cabane:block/labo_autel_cote", dessus: "cabane:block/labo_autel_dessus" },
    elements: el,
  };
}

// le sceau : un plan couché, au milieu du modèle (l'objet est centré sur l'entité qui l'affiche)
const MODELE_SCEAU = {
  textures: { sceau: "cabane:item/sceau_rituel", particle: "cabane:item/sceau_rituel" },
  elements: [{ from: [0, 8, 0], to: [16, 8.01, 16], faces: { up: face("#sceau", [0, 0, 16, 16]), down: face("#sceau", [0, 0, 16, 16]) } }],
};

const MODELE_BOUTON = {
  parent: "minecraft:block/block",
  ambientocclusion: false,
  textures: { particle: "cabane:block/labo_metal", metal: "cabane:block/labo_metal", danger: "cabane:block/labo_danger", bouton: "cabane:block/labo_bouton" },
  elements: [
    boite([3, 0, 3], [13, 1, 13], "#metal"),
    boite([5, 1, 5], [11, 11, 11], "#metal"),
    boite([2, 11, 2], [14, 13, 14], "#metal", "#danger"),
    boite([4.5, 13, 4.5], [11.5, 14.5, 11.5], "#bouton", "#bouton", LUEUR),
    boite([5.5, 14.5, 5.5], [10.5, 15.5, 10.5], "#bouton", "#bouton", LUEUR),
  ],
};

const MODELE_TERMINAL = {
  parent: "minecraft:block/block",
  textures: { particle: "cabane:block/labo_terminal_cote", face: "cabane:block/labo_terminal_face", cote: "cabane:block/labo_terminal_cote", dessus: "cabane:block/labo_terminal_dessus" },
  elements: [
    {
      from: [0, 0, 0], to: [16, 16, 16],
      faces: {
        north: face("#face"), south: face("#cote"), east: face("#cote"), west: face("#cote"),
        up: face("#dessus"), down: face("#dessus"),
      },
    },
    // l'écran, qui luit dans le noir
    { from: [2, 6, -0.05], to: [14, 14, 0], faces: { north: face("#face", [2, 2, 14, 10]) }, ...LUEUR },
  ],
};

const tourne = (modele) => Object.fromEntries(["north", "east", "south", "west"].map((d, i) => [`facing=${d}`, { model: modele, ...(i ? { y: i * 90 } : {}) }]));

// ---------------------------------------------------------------- la dimension

const TYPE_DIMENSION = {
  ultrawarm: false,
  natural: false,
  piglin_safe: false,
  respawn_anchor_works: false,
  bed_works: false,
  has_raids: false,
  has_skylight: false,
  has_ceiling: false,
  coordinate_scale: 1.0,
  ambient_light: 0.0,
  fixed_time: 18000,
  logical_height: 256,
  min_y: 0,
  height: 256,
  // le ciel de l'End : un vide noir et grouillant derrière les vitres
  effects: "minecraft:the_end",
  infiniburn: "#minecraft:infiniburn_overworld",
  monster_spawn_light_level: 0,
  monster_spawn_block_light_limit: 0,
};

// rien du tout : le laboratoire est construit par le mod, au milieu du vide
const DIMENSION = {
  type: "cabane:laboratoire",
  generator: {
    type: "minecraft:flat",
    settings: { biome: "cabane:laboratoire", layers: [], lakes: false, features: false, structure_overrides: [] },
  },
};

const BIOME = {
  has_precipitation: false,
  temperature: 0.4,
  downfall: 0.0,
  effects: {
    sky_color: 0,
    fog_color: 0x101410,
    // une eau verdâtre, comme le liquide des cuves
    water_color: 0x5f9a3c,
    water_fog_color: 0x1c2e12,
    grass_color: 0x4a5a3a,
    foliage_color: 0x4a5a3a,
    ambient_sound: "minecraft:ambient.basalt_deltas.loop",
    mood_sound: { sound: "minecraft:ambient.cave", tick_delay: 2400, block_search_extent: 8, offset: 2.0 },
    additions_sound: { sound: "minecraft:ambient.soul_sand_valley.additions", tick_chance: 0.0111 },
    // de la poussière qui flotte
    particle: { options: { type: "minecraft:white_ash" }, probability: 0.006 },
    music: { sound: "minecraft:music_disc.13", min_delay: 6000, max_delay: 14000, replace_current_music: true },
  },
  spawners: {},
  spawn_costs: {},
  carvers: {},
  features: [],
};

export function genererLabo({ ecrire, png, lang }) {
  const textures = {
    labo_metal: metal, labo_danger: danger, labo_drap: drap, labo_sangle: sangle, labo_bouton: bouton,
    labo_terminal_face: terminalFace, labo_terminal_cote: terminalCote, labo_terminal_dessus: terminalDessus,
    labo_autel_dessus: autelDessus, labo_autel_cote: autelCote,
  };
  for (const [nom, f] of Object.entries(textures)) {
    ecrire(`assets/cabane/textures/block/${nom}.png`, peindre(png, f));
  }
  ecrire("assets/cabane/textures/item/sceau_rituel.png", peindreGrand(png, 256, sceau));
  ecrire("assets/cabane/models/item/sceau_rituel.json", MODELE_SCEAU);

  ecrire("assets/cabane/models/block/autel_rituel_tete.json", moitieAutel(true));
  ecrire("assets/cabane/models/block/autel_rituel_pieds.json", moitieAutel(false));
  const variantesAutel = {};
  for (const [i, d] of ["north", "east", "south", "west"].entries()) {
    for (const partie of ["head", "foot"]) {
      variantesAutel[`facing=${d},part=${partie}`] = { model: `cabane:block/autel_rituel_${partie === "head" ? "tete" : "pieds"}`, ...(i ? { y: i * 90 } : {}) };
    }
  }
  ecrire("assets/cabane/blockstates/autel_rituel.json", { variants: variantesAutel });
  ecrire("assets/cabane/models/item/autel_rituel.json", { parent: "cabane:block/autel_rituel_tete" });

  ecrire("assets/cabane/models/block/table_experiences_tete.json", moitieTable(true));
  ecrire("assets/cabane/models/block/table_experiences_pieds.json", moitieTable(false));
  ecrire("assets/cabane/models/block/bouton_retour.json", MODELE_BOUTON);
  ecrire("assets/cabane/models/block/terminal_labo.json", MODELE_TERMINAL);

  const variantesTable = {};
  for (const [i, d] of ["north", "east", "south", "west"].entries()) {
    for (const partie of ["head", "foot"]) {
      variantesTable[`facing=${d},part=${partie}`] = { model: `cabane:block/table_experiences_${partie === "head" ? "tete" : "pieds"}`, ...(i ? { y: i * 90 } : {}) };
    }
  }
  ecrire("assets/cabane/blockstates/table_experiences.json", { variants: variantesTable });
  ecrire("assets/cabane/blockstates/bouton_retour.json", { variants: { "": { model: "cabane:block/bouton_retour" } } });
  ecrire("assets/cabane/blockstates/terminal_labo.json", { variants: tourne("cabane:block/terminal_labo") });
  ecrire("assets/cabane/models/item/table_experiences.json", { parent: "cabane:block/table_experiences_tete" });
  ecrire("assets/cabane/models/item/bouton_retour.json", { parent: "cabane:block/bouton_retour" });
  ecrire("assets/cabane/models/item/terminal_labo.json", { parent: "cabane:block/terminal_labo" });

  ecrire("data/cabane/dimension_type/laboratoire.json", TYPE_DIMENSION);
  ecrire("data/cabane/dimension/laboratoire.json", DIMENSION);
  ecrire("data/cabane/worldgen/biome/laboratoire.json", BIOME);

  Object.assign(lang, {
    "block.cabane.terminal_labo": "Terminal du Laboratoire",
    "block.cabane.bouton_retour": "Bouton de retour",
    "block.cabane.table_experiences": "Table d'expériences",
    "block.cabane.autel_rituel": "Autel du rituel",
    "item.cabane.sceau_rituel": "Sceau du rituel",
    "biome.cabane.laboratoire": "Le Laboratoire",
  });
}
