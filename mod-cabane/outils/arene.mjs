// L'arène de combat (/arene, voir arene/Arene.java et docs/arene) : la dimension cabane:arene, vide, toujours en fin
// d'après-midi (les braseros ressortent), un biome au ciel doré avec un peu de poussière dans l'air, et les boutons
// (bleu, rouge, doré : le dessin du bouton rouge du labo, recoloré). Appelé par generer.mjs.

const TYPE_DIMENSION = {
  ultrawarm: false, natural: false, piglin_safe: false, respawn_anchor_works: false, bed_works: false, has_raids: false,
  has_skylight: true, has_ceiling: false, coordinate_scale: 1.0, ambient_light: 0.0,
  fixed_time: 11200,
  logical_height: 256, min_y: 0, height: 256, effects: "minecraft:overworld", infiniburn: "#minecraft:infiniburn_overworld",
  monster_spawn_light_level: 0, monster_spawn_block_light_limit: 0,
};
const DIMENSION = {
  type: "cabane:arene",
  generator: { type: "minecraft:flat", settings: { biome: "cabane:arene", layers: [], lakes: false, features: false, structure_overrides: [] } },
};
const BIOME = {
  has_precipitation: false, temperature: 0.8, downfall: 0.0,
  effects: {
    sky_color: 0x7a9fe0, fog_color: 0xe8c79a, water_color: 0x3f76e4, water_fog_color: 0x050533, grass_color: 0x79c05a, foliage_color: 0x59ae30,
    particle: { options: { type: "minecraft:white_ash" }, probability: 0.003 },
    additions_sound: { sound: "minecraft:block.campfire.crackle", tick_chance: 0.004 },
  },
  spawners: {}, spawn_costs: {}, carvers: {}, features: [],
};

/** Les boutons de l'arène : [suffixe, texture du dessus (null : le rouge du labo), nom]. */
const BOUTONS = [
  ["bleu", "minecraft:block/blue_concrete", "Bouton du côté bleu"],
  ["rouge", "minecraft:block/red_concrete", "Bouton du côté rouge"],
  ["gradins", "minecraft:block/gold_block", "Bouton des gradins"],
  ["sortie", null, "Bouton de sortie de l'arène"],
  ["salle", null, "Bouton de retour à la salle d'entrée"],
];

export function genererArene({ ecrire, lang }) {
  ecrire("data/cabane/dimension_type/arene.json", TYPE_DIMENSION);
  ecrire("data/cabane/dimension/arene.json", DIMENSION);
  ecrire("data/cabane/worldgen/biome/arene.json", BIOME);
  lang["biome.cabane.arene"] = "L'Arène";
  for (const [suffixe, dessus, nom] of BOUTONS) {
    const id = `bouton_arene_${suffixe}`;
    let modele = "cabane:block/bouton_retour";
    if (dessus) {
      modele = `cabane:block/${id}`;
      ecrire(`assets/cabane/models/block/${id}.json`, { parent: "cabane:block/bouton_retour", textures: { bouton: dessus } });
    }
    ecrire(`assets/cabane/blockstates/${id}.json`, { variants: { "": { model: modele } } });
    ecrire(`assets/cabane/models/item/${id}.json`, { parent: modele });
    lang[`block.cabane.${id}`] = nom;
  }
  return BOUTONS.length;
}
