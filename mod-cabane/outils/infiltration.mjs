// Les missions d'infiltration (/mission whoop 1-3, /mission anbu 1-3 : voir mission/Missions.java et docs/missions).
// Appelé par generer.mjs : les six scènes exportées par docs/missions/generateur/jeu.mjs (data/cabane/infiltration/<id>.json),
// les skins des gardes, ce qu'ils tiennent en main, les objectifs à ramener, les deux dimensions (le jour, la nuit).
import { exporter, MISSIONS } from "../../docs/missions/generateur/jeu.mjs";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { skinsGens, skinsBoss } from "../../docs/missions/generateur/skins.mjs";
import { icones } from "../../docs/missions/generateur/icones.mjs";
import { png, elements, PRISES, miroir } from "./cartel.mjs";

const GARDES = ["agent", "employe", "mannequin", "vigile", "sbire", "ninjaBrume", "villageois", "deserteur", "otage", "samourai", "archer"];
/** Ce que les gardes tiennent (jamais dans l'inventaire des joueurs) et comment ils le tiennent. */
const TENUS = { katana: "lame", kunai: "lame", lance: "lame", arc: "lame", matraque: "lame", pistolet_laser: "main", fusil_laser: "fusil", lampe_torche: "main" };
/** Les objectifs : on les ramasse dans la scène et on les ramène à la sortie. */
const OBJECTIFS = {
  formule: "Formule du rouge à lèvres", puce: "Puce du styliste", rouleau: "Rouleau interdit", pacte: "Pacte signé",
};
const NOMS_TENUS = {
  katana: "Katana", kunai: "Kunaï", lance: "Lance", arc: "Arc", matraque: "Matraque", pistolet_laser: "Pistolet laser",
  fusil_laser: "Fusil laser", lampe_torche: "Lampe torche",
};

const TYPE = (temps) => ({
  ultrawarm: false, natural: false, piglin_safe: false, respawn_anchor_works: false, bed_works: false, has_raids: false,
  has_skylight: true, has_ceiling: false, coordinate_scale: 1.0, ambient_light: 0.0, fixed_time: temps,
  logical_height: 256, min_y: 0, height: 256, effects: "minecraft:overworld", infiniburn: "#minecraft:infiniburn_overworld",
  monster_spawn_light_level: 0, monster_spawn_block_light_limit: 0,
});
// du vide : les scènes sont construites par le mod, chacune loin des autres
const DIMENSION = (nom) => ({
  type: `cabane:${nom}`,
  generator: { type: "minecraft:flat", settings: { biome: `cabane:${nom}`, layers: [], lakes: false, features: false, structure_overrides: [] } },
});
const BIOME = (e) => ({ has_precipitation: false, temperature: 0.8, downfall: 0.4, effects: e, spawners: {}, spawn_costs: {}, carvers: {}, features: [] });

/** En attendant son vrai skin : une silhouette noire aux yeux rouges. */
function silhouette() {
  const px = Buffer.alloc(64 * 64 * 4);
  const zones = [[0, 0, 32, 16], [0, 16, 56, 32], [16, 48, 48, 64]];
  for (const [x0, y0, x1, y1] of zones) for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
    const v = 14 + ((x * 7 + y * 13) % 5);
    px.set([v, v - 2, v + 2, 255], (y * 64 + x) * 4);
  }
  for (const x of [9, 10, 13, 14]) px.set([220, 20, 24, 255], (12 * 64 + x) * 4);
  return px;
}

export function genererInfiltration({ ecrire, lang }) {
  for (const id of MISSIONS) ecrire(`data/cabane/infiltration/${id}.json`, JSON.stringify(exporter(id)));

  const SK = skinsGens();
  // les chemins des ressources sont en minuscules (ninjaBrume → ninjabrume)
  for (const id of GARDES) ecrire(`assets/cabane/textures/entity/mission/${id.toLowerCase()}.png`, png(64, 64, SK[id].px));

  const IC = icones();
  const objet = (id, nomItem, prise) => {
    const ic = IC[id];
    ecrire(`assets/cabane/textures/item/${nomItem}.png`, png(64, 64, ic.px));
    const p = PRISES[prise];
    const display = {
      ...p, thirdperson_lefthand: miroir(p.thirdperson_righthand), firstperson_lefthand: miroir(p.firstperson_righthand),
      ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
      fixed: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
      head: { rotation: [0, 180, 0], translation: [0, 13, 7], scale: [1, 1, 1] },
    };
    ecrire(`assets/cabane/models/item/${nomItem}_3d.json`, { textures: { t: `cabane:item/${nomItem}`, particle: `cabane:item/${nomItem}` }, gui_light: "front", display, elements: elements(ic) });
    ecrire(`assets/cabane/models/item/${nomItem}.json`, {
      loader: "neoforge:separate_transforms",
      base: { parent: `cabane:item/${nomItem}_3d` },
      perspectives: { gui: { parent: "minecraft:item/generated", textures: { layer0: `cabane:item/${nomItem}` } } },
    });
  };
  for (const [id, prise] of Object.entries(TENUS)) { objet(id, `mission_${id}`, prise); lang[`item.cabane.mission_${id}`] = NOMS_TENUS[id]; }
  for (const [id, nom] of Object.entries(OBJECTIFS)) { objet(id, `mission_${id}`, "main"); lang[`item.cabane.mission_${id}`] = nom; }

  // les skins des méchants (/skin, voir costume/Costumes.java) : les boss des missions dessinés, les autres fournis (outils/skins/)
  for (const [id, sk] of Object.entries(skinsBoss())) ecrire(`assets/cabane/textures/entity/costume/${id}.png`, png(64, 64, sk.px));
  const FOURNIS = path.join(path.dirname(fileURLToPath(import.meta.url)), "skins");
  for (const id of ["docteur", "jerry", "boss"]) {
    const f = path.join(FOURNIS, id + ".png");
    if (fs.existsSync(f)) ecrire(`assets/cabane/textures/entity/costume/${id}.png`, fs.readFileSync(f));
    else ecrire(`assets/cabane/textures/entity/costume/${id}.png`, png(64, 64, silhouette()));
  }

  ecrire("data/cabane/dimension_type/mission_jour.json", TYPE(6000));
  ecrire("data/cabane/dimension_type/mission_nuit.json", TYPE(18000));
  ecrire("data/cabane/dimension/mission_jour.json", DIMENSION("mission_jour"));
  ecrire("data/cabane/dimension/mission_nuit.json", DIMENSION("mission_nuit"));
  ecrire("data/cabane/worldgen/biome/mission_jour.json", BIOME({
    sky_color: 0x78a7ff, fog_color: 0xc0d8ff, water_color: 0x3f76e4, water_fog_color: 0x050533, grass_color: 0x79c05a, foliage_color: 0x59ae30,
  }));
  ecrire("data/cabane/worldgen/biome/mission_nuit.json", BIOME({
    sky_color: 0x1a2240, fog_color: 0x0c1226, water_color: 0x1c3a6a, water_fog_color: 0x040818, grass_color: 0x4f7a48, foliage_color: 0x3f6a3a,
  }));
  // les coups des gardes : les mêmes dégâts quelle que soit la difficulté (en paisible, ils attrapent quand même)
  ecrire("data/cabane/damage_type/garde.json", { message_id: "cabane.garde", exhaustion: 0.1, scaling: "never" });
  Object.assign(lang, {
    "death.attack.cabane.garde": "%1$s s'est fait attraper par %2$s", "death.attack.cabane.garde.player": "%1$s s'est fait attraper",
    "death.attack.cabane.garde.item": "%1$s s'est fait attraper par %2$s",
    "entity.cabane.mission_garde": "Garde", "entity.cabane.mission_chien": "Chien de garde",
    "biome.cabane.mission_jour": "Mission", "biome.cabane.mission_nuit": "Mission de nuit",
  });
  return MISSIONS.length;
}
