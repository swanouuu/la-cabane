// La salle d'entraînement (/entrainement, voir entrainement/Entrainement.java et docs/salle-entrainement) : la dimension
// cabane:entrainement, vide, toujours en plein jour ; un biome pour l'allée et un par salle, qui donne à chaque salle sa
// brume, ce qui flotte dans l'air et ses bruits ; le bouton du retour (le même dessin que celui du labo). Appelé par generer.mjs.

const TYPE_DIMENSION = {
  ultrawarm: false, natural: false, piglin_safe: false, respawn_anchor_works: false, bed_works: false, has_raids: false,
  has_skylight: true, has_ceiling: false, coordinate_scale: 1.0, ambient_light: 0.0,
  fixed_time: 6000,
  logical_height: 256, min_y: 0, height: 256, effects: "minecraft:overworld", infiniburn: "#minecraft:infiniburn_overworld",
  monster_spawn_light_level: 0, monster_spawn_block_light_limit: 0,
};
const DIMENSION = {
  type: "cabane:entrainement",
  generator: { type: "minecraft:flat", settings: { biome: "cabane:entrainement", layers: [], lakes: false, features: false, structure_overrides: [] } },
};

/** Un biome de la salle : brume, ciel, eau, et ce qu'on y voit et entend. */
function biome({ brume, eau = 0x3f76e4, particule, proba, son, ambiance, ajout, frequence = 0.006 }) {
  const effects = { sky_color: 0x78a7ff, fog_color: brume, water_color: eau, water_fog_color: 0x050533, grass_color: 0x79c05a, foliage_color: 0x59ae30 };
  if (particule) effects.particle = { options: particule, probability: proba };
  if (son) effects.ambient_sound = son;
  if (ambiance) effects.mood_sound = { sound: ambiance, tick_delay: 3000, block_search_extent: 8, offset: 2.0 };
  if (ajout) effects.additions_sound = { sound: ajout, tick_chance: frequence };
  return { has_precipitation: false, temperature: 0.8, downfall: 0.0, effects, spawners: {}, spawn_costs: {}, carvers: {}, features: [] };
}

/** Dans l'ordre des salles (entrainement/EntrainementBuilder.SALLES) : [suffixe, nom, biome]. */
const SALLES = [
  ["bois", "le mannequin géant", biome({ brume: 0x8a6a48, particule: { type: "minecraft:white_ash" }, proba: 0.004 })],
  ["bassin", "le bassin profond", biome({ brume: 0x2e8c96, eau: 0x2fb9c4, particule: { type: "minecraft:falling_water" }, proba: 0.006, ajout: "minecraft:block.water.ambient", frequence: 0.02 })],
  ["forge", "la fonderie", biome({ brume: 0xb3431a, particule: { type: "minecraft:ash" }, proba: 0.06, son: "minecraft:ambient.basalt_deltas.loop", ajout: "minecraft:block.lava.ambient", frequence: 0.02 })],
  ["froid", "la chambre froide", biome({ brume: 0xcfe3ff, particule: { type: "minecraft:snowflake" }, proba: 0.08, ambiance: "minecraft:ambient.cave", ajout: "minecraft:block.powder_snow.step", frequence: 0.01 })],
  ["vent", "le tunnel à vent", biome({ brume: 0xc4ccd6, particule: { type: "minecraft:white_ash" }, proba: 0.03, son: "minecraft:item.elytra.flying" })],
  ["gouffre", "les îlots du gouffre", biome({ brume: 0x0b0814, particule: { type: "minecraft:reverse_portal" }, proba: 0.02, son: "minecraft:ambient.soul_sand_valley.loop", ambiance: "minecraft:ambient.soul_sand_valley.mood" })],
  ["sable", "la fosse de sable", biome({ brume: 0xd9b25c, particule: { type: "minecraft:falling_dust", block_state: { Name: "minecraft:sand" } }, proba: 0.08, son: "minecraft:ambient.basalt_deltas.loop", ajout: "minecraft:block.sand.step", frequence: 0.01 })],
  ["cuivre", "les pylônes de cuivre", biome({ brume: 0x101a33, particule: { type: "minecraft:electric_spark" }, proba: 0.02, ajout: "minecraft:block.beacon.ambient", frequence: 0.01 })],
  ["os", "les piliers d'os", biome({ brume: 0xd8dccb, particule: { type: "minecraft:white_ash" }, proba: 0.1, son: "minecraft:ambient.warped_forest.loop", ambiance: "minecraft:ambient.soul_sand_valley.mood" })],
  ["voliere", "la volière", biome({ brume: 0xd6efc8, particule: { type: "minecraft:cherry_leaves" }, proba: 0.01, ajout: "minecraft:entity.parrot.ambient", frequence: 0.01 })],
];

export function genererEntrainement({ ecrire, lang }) {
  ecrire("data/cabane/dimension_type/entrainement.json", TYPE_DIMENSION);
  ecrire("data/cabane/dimension/entrainement.json", DIMENSION);
  ecrire("data/cabane/worldgen/biome/entrainement.json", biome({ brume: 0xc0d8ff }));
  lang["biome.cabane.entrainement"] = "Salle d'entraînement";
  for (const [suffixe, nom, b] of SALLES) {
    ecrire(`data/cabane/worldgen/biome/entrainement_${suffixe}.json`, b);
    lang[`biome.cabane.entrainement_${suffixe}`] = `Salle d'entraînement — ${nom}`;
  }
  ecrire("assets/cabane/blockstates/bouton_entrainement.json", { variants: { "": { model: "cabane:block/bouton_retour" } } });
  ecrire("assets/cabane/models/item/bouton_entrainement.json", { parent: "cabane:block/bouton_retour" });
  lang["block.cabane.bouton_entrainement"] = "Bouton de retour de la salle d'entraînement";
  lang["entity.cabane.pnj_entrainement"] = "Élève";
  return SALLES.length;
}
