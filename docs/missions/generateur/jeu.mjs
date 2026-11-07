// Ce que le mod construit pour les six missions (/mission whoop 1..3, /mission anbu 1..3) : les scènes des planches, plus
// ce qu'il faut pour y jouer pour de vrai (échelles et échafaudages entre les étages, la trappe de l'atelier, l'escalator,
// les cellules fermées, les pièges de la forêt, la lierre des remparts…), puis tout est exporté pour le mod :
// les blocs (palette + RLE), les gardes et leurs rondes, la cellule, la sortie, les objectifs, les lasers, les caméras,
// les projecteurs, les pièges, le gong, les enseignes. Coordonnées de la scène : x vers l'est, z vers le sud, y = 0 au ras du
// sol (le sol est en y = -1) ; le mod ajoute le décalage de la scène dans sa dimension.
import { POLICE } from "../../spies/generateur/moteur.mjs";
import { glamtek, megamall, ile } from "./whoop.mjs";
import { brume, foret, chateau } from "./anbu.mjs";

// ---------------------------------------------------------------- les textures des maquettes → les blocs du jeu
const BLOCS = {
  pierre: "minecraft:stone", terre: "minecraft:dirt", herbe: "minecraft:grass_block", boue: "minecraft:mud",
  beton: "minecraft:light_gray_concrete", bitume: "minecraft:gray_concrete", pavesClairs: "minecraft:smooth_stone",
  marbre: "minecraft:calcite", marbreNoir: "minecraft:black_concrete", quartz: "minecraft:quartz_block",
  verreBleu: "minecraft:light_blue_stained_glass", verreRose: "minecraft:pink_stained_glass", verreFume: "minecraft:gray_stained_glass",
  vitre: "minecraft:glass", vitrine: "minecraft:glass", vitrineAllumee: "minecraft:white_stained_glass",
  plafond: "minecraft:smooth_quartz", roseVif: "minecraft:magenta_concrete", rosePastel: "minecraft:pink_concrete",
  acierPlaque: "minecraft:iron_block", fer: "minecraft:iron_block", chrome: "minecraft:iron_block", barreaux: "minecraft:iron_bars",
  boisFonce: "minecraft:dark_oak_planks", boisClair: "minecraft:birch_planks", parquet: "minecraft:oak_planks",
  feuilles: "minecraft:oak_leaves[persistent=true]", feuillesFoncees: "minecraft:spruce_leaves[persistent=true]",
  palme: "minecraft:jungle_leaves[persistent=true]", cerisier: "minecraft:cherry_leaves[persistent=true]",
  moquette: "minecraft:gray_wool", moquetteRose: "minecraft:pink_wool", carrelage: "minecraft:polished_diorite",
  carrelageNoir: "minecraft:polished_blackstone", neonRose: "minecraft:pearlescent_froglight", neonBleu: "minecraft:sea_lantern",
  neonJaune: "minecraft:ochre_froglight", neonVert: "minecraft:verdant_froglight", ecran: "minecraft:sea_lantern",
  ecranRose: "minecraft:pearlescent_froglight", ecranRouge: "minecraft:red_concrete", eau: "minecraft:water",
  tronc: "minecraft:oak_log", lanterne: "minecraft:lantern", lanternePapier: "minecraft:lantern", lanterneRouge: "minecraft:shroomlight",
  brique: "minecraft:bricks", noir: "minecraft:black_concrete", rouge: "minecraft:red_concrete", danger: "minecraft:yellow_concrete",
  caoutchouc: "minecraft:polished_blackstone", laineRouge: "minecraft:red_wool", laineJaune: "minecraft:yellow_wool",
  laineBleue: "minecraft:blue_wool", laineVerte: "minecraft:lime_wool", laineNoire: "minecraft:black_wool", laineBlanche: "minecraft:white_wool",
  sable: "minecraft:sand", basalte: "minecraft:smooth_basalt", roche: "minecraft:blackstone", metalOrange: "minecraft:orange_concrete",
  phare: "minecraft:glowstone", chemin: "minecraft:dirt_path", pave: "minecraft:polished_andesite", roseaux: "minecraft:tall_grass",
  boisRouge: "minecraft:red_concrete", boisClairJ: "minecraft:birch_planks", boisJapon: "minecraft:spruce_planks",
  shoji: "minecraft:white_terracotta", fenetreAllumee: "minecraft:ochre_froglight", tuileGrise: "minecraft:deepslate_tiles",
  paille: "minecraft:hay_block", bambou: "minecraft:bamboo_block", platre: "minecraft:white_concrete", tatami: "minecraft:bamboo_mosaic",
  mousse: "minecraft:moss_block", ecorce: "minecraft:spruce_wood", toile: "minecraft:white_wool", feu: "minecraft:campfire",
  caisse: "minecraft:barrel[facing=up]", pierreChateau: "minecraft:stone_bricks", gravier: "minecraft:gravel", or: "minecraft:gold_block",
};
const bloc = (nom) => (nom.startsWith("mc:") ? nom.slice(3) : BLOCS[nom] ?? (() => { throw new Error(`texture sans bloc : ${nom}`); })());

// ce qu'on peut traverser (pour poser les gens) et ce qui fait le sol prolongé au-delà de la scène
const TRAVERSABLE = new Set(["air", "roseaux", "mc:minecraft:short_grass", "mc:minecraft:tall_grass[half=upper]", "mc:minecraft:tall_grass[half=lower]"]);
const SOL = new Set(["pierre", "terre", "herbe", "eau", "sable", "bitume", "beton", "mousse", "chemin", "boue"]);

// ---------------------------------------------------------------- petits outils sur un Monde
const echelle = (m, x, y0, y1, z, face) => { for (let y = y0; y <= y1; y++) m.set(x, y, z, `mc:minecraft:ladder[facing=${face}]`); };
const echafaudage = (m, x, y0, y1, z) => { for (let y = y0; y <= y1; y++) m.set(x, y, z, "mc:minecraft:scaffolding[distance=0,bottom=false]"); };
const passe = (m, x, y, z) => TRAVERSABLE.has(m.get(x, y, z) ?? "air");
/** Le pied libre le plus proche de (x, y, z) : deux cases traversables sur un sol plein. */
function libre(m, x, y, z) {
  const bx = Math.floor(x), by = Math.round(y), bz = Math.floor(z);
  for (let dy = 0; dy <= 3; dy++) for (const s of [1, -1]) for (let r = 0; r <= 4; r++) for (let dx = -r; dx <= r; dx++) for (let dz = -r; dz <= r; dz++) {
    if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue;
    const yy = by + s * dy;
    if (passe(m, bx + dx, yy, bz + dz) && passe(m, bx + dx, yy + 1, bz + dz) && !passe(m, bx + dx, yy - 1, bz + dz) && m.get(bx + dx, yy - 1, bz + dz) !== "eau")
      return r === 0 && dy === 0 ? [x, by, z] : [bx + dx + 0.5, yy, bz + dz + 0.5];
  }
  return [x, by, z];
}
/** Des lampes invisibles au plafond d'un étage, tous les {@code pas} blocs. */
function eclairer(m, x0, z0, x1, z1, y, pas = 6, niveau = 12) {
  for (let x = x0; x <= x1; x += pas) for (let z = z0; z <= z1; z += pas) m.lumieresJeu.push([x, y, z, niveau]);
}
/** Les trous d'une cage de bambou (un poteau sur deux) bouchés par des barreaux. */
function fermerCage(m, x0, y0, z0, x1, y1, z1) {
  for (let x = x0; x <= x1; x++) for (let z = z0; z <= z1; z++) for (let y = y0; y <= y1; y++)
    if ((x === x0 || x === x1 || z === z0 || z === z1) && m.get(x, y, z) !== "bambou") m.set(x, y, z, "barreaux");
}

// ---------------------------------------------------------------- ce qu'on ajoute à chaque scène pour y jouer
const JEU = {
  // ======================================================== WOOHP 1 : la tour Glamtek
  whoop1: {
    construire: () => glamtek(POLICE), dimension: "jour", titre: "LA TOUR GLAMTEK",
    ajuster(m) {
      // les échelles d'un étage à l'autre, contre le mur nord, une fois à l'est, une fois à l'ouest : il faut traverser
      echelle(m, 11, 0, 5, -5, "south");
      echelle(m, -12, 6, 11, -5, "south");
      echelle(m, 12, 12, 17, -5, "south");
      echelle(m, -12, 18, 23, -5, "south");
      // la caméra au plafond de l'étage des lasers
      m.set(11, 16, 4, "mc:minecraft:observer[facing=up]");
      for (const y of [4, 10, 16, 22]) eclairer(m, -10, -3, 10, 3, y, 6, 12);
    },
    depart: [-21.5, 0, 27.9, 180],
    sortie: [-12, 24, -5, 12, 28, 5],
    cellule: { grille: [-9, 0, -5, -9, 3, -1], dedans: [-10.5, 0, -2.5], dehors: [-7.5, 0, -0.5] },
    objets: [{ id: "formule", item: "mission_formule", p: [-3.5, 19.1, 2.5] }],
    textes: [{ texte: "GLAMTEK", p: [0.5, 25.4, -4.95], lacet: 0, taille: 13, couleur: 0xd61e78 }],
  },
  // ======================================================== WOOHP 2 : le Méga Mall
  whoop2: {
    construire: () => megamall(POLICE), dimension: "nuit", titre: "LE MEGA MALL",
    ajuster(m) {
      // l'escalator : de vraies marches, et le trou dans l'étage au-dessus
      for (let x = 10; x <= 11; x++) {
        for (let z = -1; z <= 4; z++) m.set(x, 6, z, null);
        for (let k = 0; k <= 6; k++) m.set(x, k, 4 - k, "mc:minecraft:polished_blackstone_stairs[facing=north,half=bottom]");
      }
      // la trappe de l'atelier, à côté de la fontaine, et l'échelle qui descend
      echelle(m, 5, -6, -1, 1, "west");
      // l'échelle du toit, au fond du magasin d'électro
      echelle(m, 25, 7, 13, -17, "south");
      eclairer(m, -6, -4, 6, 4, -3, 5, 11);
    },
    depart: [3, 0, 18.2, 180],
    sortie: [-25, 14, -17, 25, 19, 13],
    cellule: { grille: [16, 0, 2, 16, 4, 4], dedans: [21.5, 0, 3.5], dehors: [14.5, 0, 3.5] },
    objets: [
      { id: "puce1", item: "mission_puce", p: [-16.5, 1.1, 9.5] },
      { id: "puce2", item: "mission_puce", p: [12.5, 1.1, 9.5] },
      { id: "puce3", item: "mission_puce", p: [-21.5, 8.1, -11.5] },
    ],
    textes: [
      { texte: "MEGA MALL", p: [0.5, 14.6, -15.95], lacet: 0, taille: 9, couleur: 0xff5ad2 },
      { texte: "CINEMA", p: [-17.5, 11.2, -7.95], lacet: 0, taille: 5, couleur: 0xffe650 },
      { texte: "ARCADE", p: [-3.5, 11.2, -7.95], lacet: 0, taille: 5, couleur: 0x5aff8c },
      { texte: "ELECTRO", p: [13.5, 11.2, -7.95], lacet: 0, taille: 5, couleur: 0x50c8ff },
    ],
  },
  // ======================================================== WOOHP 3 : l'île du méchant
  whoop3: {
    construire: () => ile(POLICE), dimension: "jour", titre: "L'ILE DU VOLCAN", compteRebours: 15 * 60,
    ajuster(m) {
      // de la salle de contrôle au balcon du silo : une échelle, et l'écran du coin ouvert
      echelle(m, -3, 2, 5, -2, "east");
      m.set(-3, 5, -3, null); m.set(-3, 6, -3, null);
      // la cellule : on bouche la porte des barreaux
      m.set(-9, 2, 0, "barreaux"); m.set(-9, 3, 0, "barreaux");
      eclairer(m, -2, -1, 3, 3, 6, 4, 12);
      eclairer(m, -15, -2, -10, 1, 5, 5, 9);
    },
    depart: [5.5, 1, 30, 180],
    mechant: [1.5, 2, 0.5, 180],
    sortie: [-35, -2, 0, -25, 3, 8],
    cellule: { grille: [-9, 2, -3, -9, 5, 2], dedans: [-13.5, 2, -0.5], dehors: [-7.5, 2, 0.5] },
    objets: [],
  },
  // ======================================================== ANBU 1 : le village de la Brume
  anbu1: {
    construire: () => brume(), dimension: "nuit", titre: "LE VILLAGE DE LA BRUME",
    ajuster(m) {
      // le lac sur deux blocs de fond (on nage dessous sans être vu), les roseaux plantés dans un marais de boue
      for (let x = m.x0; x < m.x0 + m.sx; x++) for (let z = m.z0; z < m.z0 + m.sz; z++) {
        if (m.get(x, -1, z) === "eau" && m.get(x, -2, z) !== "tronc") m.set(x, -2, z, "eau");
        if (m.get(x, 0, z) === "roseaux" && m.get(x, -1, z) === "eau") { m.set(x, -1, z, "boue"); m.set(x, -2, z, "terre"); }
      }
      // la tour du chef : un échafaudage de bambou par étage, d'un côté puis de l'autre
      echafaudage(m, -3, 1, 5, 0);
      echafaudage(m, 3, 6, 10, 0);
      echafaudage(m, -3, 11, 15, 0);
      // la cage : on bouche les trous entre les bambous
      fermerCage(m, 11, 1, -3, 15, 3, 1);
      m.lumieresJeu.push([13, 2, -1, 6]);
    },
    depart: [-6, 0, 27, 180],
    sortie: [-4, 0, -31, 4, 8, -27],
    cellule: { grille: [11, 1, -3, 15, 3, 1], dedans: [13.5, 1, -0.5], dehors: [8.5, 1, -0.5] },
    objets: [{ id: "rouleau", item: "mission_rouleau", p: [2.5, 12.1, 1.5] }],
    brume: true,
  },
  // ======================================================== ANBU 2 : la forêt des pièges
  anbu2: {
    construire: () => foret(), dimension: "jour", titre: "LA FORET DES PIEGES",
    ajuster(m) {
      const chemin = (z) => Math.round(3 * Math.sin(z / 7));
      // la cage de l'otage, fermée (les ANBU attrapés y finissent aussi)
      fermerCage(m, 4, 0, -14, 7, 2, -11);
      // les fils tendus en travers du sentier
      for (const z of [27, -1]) for (let dx = -1; dx <= 1; dx++)
        m.set(chemin(z) + dx, 0, z, "mc:minecraft:tripwire[attached=false,disarmed=false,east=true,west=true,north=false,south=false,powered=false]");
      // le trou : un vrai trou, les pieux au fond (le mod le recouvre du sentier jusqu'à ce qu'on marche dessus)
      { const x = chemin(18); for (let dx = -1; dx <= 1; dx++) for (const z of [18, 19]) { m.set(x + dx, -1, z, "chemin"); m.set(x + dx, -2, z, null); m.set(x + dx, -3, z, "mc:minecraft:pointed_dripstone[vertical_direction=up,thickness=tip]"); } }
      // la plaque et le tronc suspendu à ses chaînes
      { const x = chemin(14);
        for (let dx = -1; dx <= 1; dx++) m.set(x + dx, 0, 14, "mc:minecraft:heavy_weighted_pressure_plate");
        for (let dx = -2; dx <= 3; dx++) m.set(x + dx, 6, 14, "mc:minecraft:oak_log[axis=x]");
        for (let dx = -3; dx <= 4; dx++) m.set(x + dx, 11, 14, "mc:minecraft:oak_log[axis=x]");
        for (const dx of [-2, 3]) for (let y = 7; y <= 10; y++) m.set(x + dx, y, 14, "mc:minecraft:chain[axis=y]");
        for (const dx of [-3, 4]) for (let y = 0; y <= 10; y++) m.set(x + dx, y, 14, "tronc");
      }
    },
    depart: [Math.round(3 * Math.sin(33 / 7)) + 0.5, 0, 33, 180],
    sortie: [-7, -1, 31, 7, 5, 36],
    cellule: { grille: [4, 0, -14, 7, 2, -11], dedans: [5.5, 0, -12.5], dehors: [8.5, 0, -12.5] },
    objets: [],
    pieges() {
      const chemin = (z) => Math.round(3 * Math.sin(z / 7));
      const p = [];
      for (const z of [27, -1]) p.push({ type: "fil", zone: [chemin(z) - 1, 0, z, chemin(z) + 1, 1, z] });
      { const x = chemin(18); p.push({ type: "trou", zone: [x - 1, -1, 18, x + 1, 0, 19], couvercle: "minecraft:dirt_path" }); }
      { const x = chemin(14); p.push({ type: "tronc", zone: [x - 1, 0, 14, x + 1, 0, 14], tronc: [x - 2, 6, 14, x + 3, 6, 14], frappe: [x - 3, 0, 13, x + 4, 2, 15] }); }
      return p;
    },
  },
  // ======================================================== ANBU 3 : le château du seigneur
  anbu3: {
    construire: () => chateau(), dimension: "nuit", titre: "LE CHATEAU DU SEIGNEUR",
    ajuster(m) {
      // les cachots sous la caserne, sous la grille de la cour
      for (let x = -17; x <= -12; x++) for (let z = -15; z <= -11; z++) for (let y = 0; y <= 1; y++) m.set(x, y, z, null);
      m.lumieresJeu.push([-15, 1, -13, 7]);
      // le marchepied du pont et la marche de la porte
      for (let x = -1; x <= 1; x++) { m.set(x, 0, 23, "mc:minecraft:stone_brick_stairs[facing=north,half=bottom]"); m.set(x, 2, 19, "mc:minecraft:spruce_stairs[facing=north,half=bottom]"); }
      // les marches du donjon (sa porte est 4 blocs au-dessus de la cour)
      for (let x = -1; x <= 1; x++) {
        m.set(x, 3, -5, "mc:minecraft:stone_brick_stairs[facing=north,half=bottom]");
        m.set(x, 3, -6, "pierreChateau"); m.set(x, 4, -6, "mc:minecraft:stone_brick_stairs[facing=north,half=bottom]");
        m.set(x, 3, -7, "pierreChateau"); m.set(x, 4, -7, "pierreChateau"); m.set(x, 5, -7, "mc:minecraft:stone_brick_stairs[facing=north,half=bottom]");
      }
      // les échafaudages d'un étage du donjon à l'autre, puis jusqu'au toit (le cerf-volant)
      echafaudage(m, 4, 7, 11, -14);
      echafaudage(m, -2, 12, 16, -14);
      echafaudage(m, -2, 17, 23, -15);
      // la lierre du rempart ouest : le chemin des ninjas, depuis les douves
      for (let z = -8; z <= -6; z++) for (let y = 0; y <= 6; y++) m.set(-22, y, z, "mc:minecraft:vine[east=true]");
      // le gong (le disque d'or)
      for (let y = 4; y <= 5; y++) for (let z = -11; z <= -10; z++) m.set(-11, y, z, "or");
      eclairer(m, -5, -17, 5, -10, 10, 5, 10);
    },
    depart: [-19, 0, 27, 180],
    sortie: [-5, 23, -18, 8, 30, -10],
    cellule: { grille: [-12, 2, -15, -12, 2, -11], dedans: [-15.5, 0, -12.5], dehors: [-10.5, 3, -13.5] },
    objets: [{ id: "pacte", item: "mission_pacte", p: [2.5, 13.1, -11.5] }],
    gong: { zone: [-11, 4, -11, -11, 5, -10], p: [-9.5, 3, -10.5] },
  },
};

// ---------------------------------------------------------------- les gens
const HEROS = /^(espionne|anbu\d)/;
const TYPES = {
  agent: "garde", vigile: "garde", sbire: "garde", ninjaBrume: "garde", deserteur: "garde", samourai: "garde",
  archer: "archer", employe: "civil", villageois: "civil", mannequin: "mannequin", otage: "otage",
};
const VUE = { garde: [9, 50], archer: [14, 35], civil: [8, 60] };

export const deplaces = [];
function gens(m, id) {
  const pnj = [];
  const fantomes = m.persos.filter((p) => p.fantome);
  for (const pe of m.persos) {
    if (pe.fantome || (pe.skin && HEROS.test(pe.skin)) || pe.skin === "toi") continue;
    if (pe.chien) { pnj.push({ type: "chien", skin: "chien", p: libre(m, pe.x, pe.y, pe.z), lacet: pe.lacet ?? 0 }); continue; }
    let type = TYPES[pe.skin];
    if (!type) throw new Error(`${id} : skin inconnu ${pe.skin}`);
    if (type === "garde" && pe.arme === "arc") type = "archer";
    // posé sur une case libre (quelques gens de la maquette ont un pied dans un mur)
    const p = libre(m, pe.x, pe.y, pe.z);
    if (Math.hypot(p[0] - pe.x, p[1] - pe.y, p[2] - pe.z) > 0.01) deplaces.push(`${pe.skin} (${pe.x}, ${pe.y}, ${pe.z}) → (${p.join(", ")})`);
    const g = { type, skin: pe.skin, p, lacet: pe.lacet ?? 0 };
    if (pe.arme) g.arme = pe.arme;
    let vue = pe.vue;
    // les guetteurs des arbres et les archers : leur champ de vision est peint au sol par un « fantôme » juste sous eux
    if (!vue && id !== "whoop3") {
      const f = fantomes.find((f) => Math.hypot(f.x - pe.x, f.z - pe.z) < 5);
      if (f) vue = f.vue;
    }
    if (VUE[type]) g.vue = vue ? [vue.portee ?? VUE[type][0], vue.angle ?? VUE[type][1]] : VUE[type];
    if (type === "archer") g.vue = [Math.max(14, g.vue[0]), Math.max(30, g.vue[1])];
    if (pe.ronde) { g.ronde = pe.ronde.map(([x, z]) => [x, pe.y, z]); g.ferme = pe.rondeFermee ?? true; }
    if (pe.lanterne) g.lanterne = true;
    if (pe.arme === "lampe_torche") g.lampe = true;
    if (pe.taille) g.taille = pe.taille;
    pnj.push(g);
  }
  return pnj;
}

// ---------------------------------------------------------------- l'export
const MARGE = 24;
const varint = (out, v) => { while (v > 0x7f) { out.push((v & 0x7f) | 0x80); v >>>= 7; } out.push(v); };

export function exporter(id) {
  const J = JEU[id];
  const m = J.construire();
  m.lumieresJeu = [];
  J.ajuster(m);
  // les boîtes libres (voitures, auvents, tables…) : un bloc là où elles remplissent au moins la moitié de la case
  for (const b of m.boites) {
    if (b.nom === "objectif" || b.nom === "puce") continue;
    for (let x = Math.floor(b.a[0]); x < Math.ceil(b.b[0]); x++) for (let y = Math.floor(b.a[1]); y < Math.ceil(b.b[1]); y++)
      for (let z = Math.floor(b.a[2]); z < Math.ceil(b.b[2]); z++) {
        const v = (Math.min(b.b[0], x + 1) - Math.max(b.a[0], x)) * (Math.min(b.b[1], y + 1) - Math.max(b.a[1], y)) * (Math.min(b.b[2], z + 1) - Math.max(b.a[2], z));
        if (v >= 0.45 && !m.plein(x, y, z)) m.set(x, y, z, b.nom);
      }
  }
  // les roseaux : de hautes herbes sur deux blocs
  for (let x = m.x0; x < m.x0 + m.sx; x++) for (let z = m.z0; z < m.z0 + m.sz; z++) for (let y = m.y0; y < m.y0 + m.sy; y++) {
    if (m.get(x, y, z) !== "roseaux") continue;
    m.set(x, y, z, "mc:minecraft:tall_grass[half=lower]");
    if (!m.plein(x, y + 1, z) || m.get(x, y + 1, z) === "roseaux") m.set(x, y + 1, z, "mc:minecraft:tall_grass[half=upper]");
    else m.set(x, y, z, "mc:minecraft:short_grass");
  }

  // la grille finale, avec le sol prolongé tout autour et un mur invisible au bord
  const X0 = m.x0 - MARGE, Z0 = m.z0 - MARGE, Y0 = m.y0, SX = m.sx + 2 * MARGE, SY = m.sy + 4, SZ = m.sz + 2 * MARGE;
  const palette = ["minecraft:air"], index = new Map([["minecraft:air", 0]]);
  const id_ = (etat) => { if (!index.has(etat)) { index.set(etat, palette.length); palette.push(etat); } return index.get(etat); };
  const grille = new Uint16Array(SX * SY * SZ);
  const I = (x, y, z) => ((y - Y0) * SZ + (z - Z0)) * SX + (x - X0);
  for (let y = Y0; y < Y0 + SY; y++) for (let z = Z0; z < Z0 + SZ; z++) for (let x = X0; x < X0 + SX; x++) {
    const dedans = x >= m.x0 && x < m.x0 + m.sx && z >= m.z0 && z < m.z0 + m.sz;
    let nom = null;
    if (dedans) nom = m.get(x, y, z);
    else {
      const cx = Math.min(m.x0 + m.sx - 1, Math.max(m.x0, x)), cz = Math.min(m.z0 + m.sz - 1, Math.max(m.z0, z));
      const n = m.get(cx, y, cz);
      if (n && SOL.has(n) && y <= 0) nom = n;
    }
    const bord = x === X0 || x === X0 + SX - 1 || z === Z0 || z === Z0 + SZ - 1;
    if (bord && !nom) { grille[I(x, y, z)] = id_("minecraft:barrier"); continue; }
    if (nom) grille[I(x, y, z)] = id_(bloc(nom));
  }
  // les lampes : de la lumière invisible dans l'air (pas celles des lanternes que les gardes tiennent à la main)
  const tenues = m.persos.filter((p) => p.lanterne).map((p) => [p.x + Math.sin((p.lacet ?? 0) * Math.PI / 180) * 0.6, p.y + 1.2, p.z + Math.cos((p.lacet ?? 0) * Math.PI / 180) * 0.6]);
  const lumieres = [
    ...m.lumieres.filter((l) => !tenues.some((t) => Math.hypot(t[0] - l.p[0], t[1] - l.p[1], t[2] - l.p[2]) < 0.05))
      .map((l) => [Math.floor(l.p[0]), Math.floor(l.p[1]), Math.floor(l.p[2]), Math.min(15, Math.round(6 + l.r))]),
    ...m.lumieresJeu,
  ];
  for (const [x, y, z, niveau] of lumieres) {
    for (const dy of [0, 1, -1, 2]) {
      const i = I(x, y + dy, z);
      if (x < X0 || x >= X0 + SX || z < Z0 || z >= Z0 + SZ || y + dy < Y0 || y + dy >= Y0 + SY) break;
      if (grille[i] === 0) { grille[i] = id_(`minecraft:light[level=${niveau}]`); break; }
    }
  }
  // en RLE : (indice de palette, longueur) en varints, y puis z puis x
  const out = [];
  for (let i = 0; i < grille.length;) {
    let j = i + 1;
    while (j < grille.length && grille[j] === grille[i]) j++;
    varint(out, grille[i]); varint(out, j - i);
    i = j;
  }

  const toi = m.persos.find((p) => p.skin === "toi");
  const scene = {
    id, titre: J.titre, dimension: J.dimension,
    origine: [X0, Y0, Z0], taille: [SX, SY, SZ], palette, blocs: Buffer.from(out).toString("base64"),
    depart: [...libre(m, J.depart[0], J.depart[1], J.depart[2]), J.depart[3]],
    mechant: J.mechant ?? [...libre(m, toi.x, toi.y, toi.z), toi.lacet ?? 0],
    sortie: J.sortie, cellule: J.cellule, objets: J.objets,
    pnj: gens(m, id),
    lasers: (m.lignes ?? []).filter((l) => l.laser).map((l) => [...l.a, ...l.b]),
    cameras: (m.cameras ?? []).map((c) => ({ p: c.p, lacet: c.lacet, portee: 11, angle: 30, balayage: 40 })),
    projecteurs: [], reservoirs: [], pieges: J.pieges ? J.pieges() : [], textes: J.textes ?? [],
    compteRebours: J.compteRebours ?? 0, brume: !!J.brume,
  };
  if (J.gong) scene.gong = J.gong;
  // l'île : les projecteurs en haut des deux tours, chacun vers la plage (le « fantôme » de la maquette), et les réservoirs
  if (id === "whoop3") {
    const phares = [];
    for (let x = m.x0; x < m.x0 + m.sx; x++) for (let z = m.z0; z < m.z0 + m.sz; z++) for (let y = m.y0; y < m.y0 + m.sy; y++)
      if (m.get(x, y, z) === "phare") phares.push([x + 0.5, y + 0.5, z + 0.5]);
    for (const f of m.persos.filter((p) => p.fantome)) {
      const ph = phares.reduce((a, b) => (Math.hypot(b[0] - f.x, b[2] - f.z) < Math.hypot(a[0] - f.x, a[2] - f.z) ? b : a));
      scene.projecteurs.push({ p: ph, lacet: f.lacet, portee: 22, angle: 13, balayage: 35 });
    }
    scene.reservoirs = m.reservoirs.map(([x, z]) => [x - 1, 5, z - 1, x + 1, 8, z + 1]);
  }
  return scene;
}

export const MISSIONS = Object.keys(JEU);
