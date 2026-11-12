// Le donjon de rang S (Sung Jin-woo) : la dimension « cabane:donjon », vide, où le mod construit le donjon
// (fr.cabane.donjon.DonjonBuilder) : une grotte d'arrivée, des couloirs, des salles, l'arène du boss.

// une grotte sans ciel ni soleil : seule la lumière du donjon éclaire
const TYPE_DIMENSION = {
  ultrawarm: false,
  natural: false,
  piglin_safe: false,
  respawn_anchor_works: false,
  bed_works: false,
  has_raids: false,
  has_skylight: false,
  has_ceiling: true,
  coordinate_scale: 1.0,
  ambient_light: 0.0,
  fixed_time: 18000,
  logical_height: 256,
  min_y: 0,
  height: 256,
  effects: "minecraft:overworld",
  infiniburn: "#minecraft:infiniburn_overworld",
  monster_spawn_light_level: 0,
  monster_spawn_block_light_limit: 0,
};

const DIMENSION = {
  type: "cabane:donjon",
  generator: {
    type: "minecraft:flat",
    settings: { biome: "cabane:donjon", layers: [], lakes: false, features: false, structure_overrides: [] },
  },
};

// un brouillard bleu nuit, des spores bleues qui flottent, le souffle de la vallée des âmes
const BIOME = {
  has_precipitation: false,
  temperature: 0.2,
  downfall: 0.0,
  effects: {
    sky_color: 0,
    fog_color: 0x0a0e26,
    water_color: 0x2a4fd0,
    water_fog_color: 0x050a26,
    grass_color: 0x3a4a6a,
    foliage_color: 0x3a4a6a,
    ambient_sound: "minecraft:ambient.soul_sand_valley.loop",
    mood_sound: { sound: "minecraft:ambient.cave", tick_delay: 1800, block_search_extent: 8, offset: 2.0 },
    additions_sound: { sound: "minecraft:ambient.soul_sand_valley.additions", tick_chance: 0.0111 },
    particle: { options: { type: "minecraft:warped_spore" }, probability: 0.004 },
    music: { sound: "minecraft:music.nether.soul_sand_valley", min_delay: 2400, max_delay: 6000, replace_current_music: true },
  },
  spawners: {},
  spawn_costs: {},
  carvers: {},
  features: [],
};

export function genererDonjon({ ecrire }) {
  ecrire("data/cabane/dimension_type/donjon.json", TYPE_DIMENSION);
  ecrire("data/cabane/dimension/donjon.json", DIMENSION);
  ecrire("data/cabane/worldgen/biome/donjon.json", BIOME);
  // les coups d'Ugrak et de ses sbires : les mêmes dégâts quelle que soit la difficulté (même en Paisible ou Facile)
  ecrire("data/cabane/damage_type/coup_donjon.json", { message_id: "cabane.donjon", exhaustion: 0.1, scaling: "never" });
}
