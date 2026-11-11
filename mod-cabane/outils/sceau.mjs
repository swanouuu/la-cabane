// La salle du sceau (/sceau, voir sceau/Sceau.java) : la dimension cabane:sceau, vide et fermée par la salle que le mod y
// construit ; son biome donne l'eau rouge, la brume jaunâtre et les gouttes qui tombent ; le bouton du retour (le même
// dessin que celui du labo). Appelé par generer.mjs.

const TYPE_DIMENSION = {
  ultrawarm: false, natural: false, piglin_safe: false, respawn_anchor_works: false, bed_works: false, has_raids: false,
  has_skylight: false, has_ceiling: true, coordinate_scale: 1.0, ambient_light: 0.22,
  fixed_time: 18000,
  logical_height: 256, min_y: 0, height: 256, effects: "minecraft:overworld", infiniburn: "#minecraft:infiniburn_overworld",
  monster_spawn_light_level: 0, monster_spawn_block_light_limit: 0,
};
const DIMENSION = {
  type: "cabane:sceau",
  generator: { type: "minecraft:flat", settings: { biome: "cabane:sceau", layers: [], lakes: false, features: false, structure_overrides: [] } },
};
const BIOME = {
  has_precipitation: false, temperature: 0.8, downfall: 0.0,
  effects: {
    sky_color: 0x000000, fog_color: 0x4a3d16, water_color: 0xb3140f, water_fog_color: 0x3a0404,
    grass_color: 0x79c05a, foliage_color: 0x59ae30,
    particle: { options: { type: "minecraft:white_ash" }, probability: 0.004 },
    mood_sound: { sound: "minecraft:ambient.cave", tick_delay: 3000, block_search_extent: 8, offset: 2.0 },
    additions_sound: { sound: "minecraft:block.pointed_dripstone.drip_water_into_cauldron", tick_chance: 0.012 },
  },
  spawners: {}, spawn_costs: {}, carvers: {}, features: [],
};

export function genererSceau({ ecrire, lang }) {
  ecrire("data/cabane/dimension_type/sceau.json", TYPE_DIMENSION);
  ecrire("data/cabane/dimension/sceau.json", DIMENSION);
  ecrire("data/cabane/worldgen/biome/sceau.json", BIOME);
  lang["biome.cabane.sceau"] = "Salle du sceau";
  ecrire("assets/cabane/blockstates/bouton_sceau.json", { variants: { "": { model: "cabane:block/bouton_retour" } } });
  ecrire("assets/cabane/models/item/bouton_sceau.json", { parent: "cabane:block/bouton_retour" });
  lang["block.cabane.bouton_sceau"] = "Bouton de retour de la salle du sceau";
}
