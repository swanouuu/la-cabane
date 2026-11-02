// La forge de Brokk : des armes et des armures (vanilla enchantées, et celles des mods du pack), et les reliques des
// boss qu'il sait refaire. Les matériaux sont logiques mais restent bien plus simples à réunir que d'aller battre le
// boss : jamais un butin de boss. Chaque recette devient data/cabane/forge/<fichier>.json.
//   categorie : l'onglet (arme, armure, relique) ; nom et enchantements : une pièce vanilla signée Brokk ;
//   indice : une ligne quand on survole la pièce ; temps : la durée de forge en secondes (sinon selon le prix).
import fs from "node:fs";

const mc = (id, n) => [`minecraft:${id}`, n];

const ARMES = [
  ["epee_brokk", "minecraft:diamond_sword", 9000, [mc("diamond", 3), mc("iron_block", 2), mc("blaze_rod", 2)],
    { nom: "Épée de Brokk", enchantements: { sharpness: 5, unbreaking: 3, looting: 2 } }],
  ["hache_guerre", "minecraft:diamond_axe", 9000, [mc("diamond", 4), mc("iron_block", 2), mc("obsidian", 4)],
    { nom: "Hache de guerre de Brokk", enchantements: { sharpness: 5, efficiency: 4, unbreaking: 3 } }],
  ["arc_chasseur", "minecraft:bow", 7000, [mc("string", 12), mc("stick", 6), mc("feather", 16), mc("iron_ingot", 4)],
    { nom: "Arc du chasseur", enchantements: { power: 5, punch: 2, flame: 1, unbreaking: 3 } }],
  ["arbalete", "minecraft:crossbow", 7000, [mc("iron_ingot", 8), mc("string", 8), mc("tripwire_hook", 2), mc("diamond", 1)],
    { nom: "Arbalète de Brokk", enchantements: { quick_charge: 3, multishot: 1, unbreaking: 3 } }],
  ["trident", "minecraft:trident", 20000, [mc("prismarine_shard", 32), mc("prismarine_crystals", 16), mc("nautilus_shell", 3), mc("diamond", 2)],
    { nom: "Trident des profondeurs", enchantements: { loyalty: 3, impaling: 5, unbreaking: 3 }, indice: "Un trident, sans aller le prendre aux noyés." }],
  ["masse", "minecraft:mace", 25000, [mc("iron_block", 8), mc("obsidian", 8), mc("diamond", 2), mc("feather", 8)],
    { nom: "Masse de Brokk", enchantements: { density: 4, unbreaking: 3 }, indice: "Plus on tombe de haut, plus elle frappe fort." }],
  ["lance_umvuthana", "mowziesmobs:spear", 6000, [mc("bamboo", 32), mc("flint", 8), mc("gold_ingot", 6), mc("feather", 4)]],
  ["dague_naga", "mowziesmobs:naga_fang_dagger", 9000, [mc("iron_ingot", 4), mc("phantom_membrane", 4), mc("spider_eye", 6), mc("emerald", 2)],
    { indice: "Le croc du Naga, sans affronter le serpent des falaises." }],
  ["epee_acier_noir", "cataclysm:black_steel_sword", 9000, [mc("iron_block", 4), mc("coal_block", 8), mc("diamond", 2)]],
  ["khopesh", "cataclysm:khopesh", 14000, [mc("gold_ingot", 12), mc("sandstone", 32), mc("diamond", 3)]],
  ["lance_corail", "cataclysm:coral_spear", 16000, [mc("prismarine_shard", 24), mc("nautilus_shell", 2), mc("diamond", 3)]],
  ["fouet_tendon", "alexsmobs:tendon_whip", 8000, [mc("string", 16), mc("leather", 8), mc("iron_ingot", 4), mc("slime_ball", 4)]],
  ["lame_corrompue", "endermanoverhaul:corrupted_blade", 18000, [mc("ender_pearl", 12), mc("obsidian", 8), mc("amethyst_shard", 8), mc("diamond", 3)]],
  ["marteau_hulk", "mutantmonsters:hulk_hammer", 20000, [mc("iron_block", 6), mc("rotten_flesh", 32), mc("obsidian", 4), mc("diamond", 2)],
    { indice: "Le marteau du zombie mutant. Brokk l'a refait, en mieux." }],
  ["main_endersoul", "mutantmonsters:endersoul_hand", 25000, [mc("ender_pearl", 16), mc("ender_eye", 4), mc("obsidian", 8), mc("diamond", 2)]],
];

const ARMURES = [
  ["casque_brokk", "minecraft:diamond_helmet", 10000, [mc("diamond", 5), mc("iron_block", 1), mc("leather", 4)],
    { nom: "Casque de Brokk", enchantements: { protection: 4, respiration: 3, aqua_affinity: 1, unbreaking: 3 } }],
  ["plastron_brokk", "minecraft:diamond_chestplate", 15000, [mc("diamond", 8), mc("iron_block", 2), mc("leather", 6)],
    { nom: "Plastron de Brokk", enchantements: { protection: 4, thorns: 2, unbreaking: 3 } }],
  ["jambieres_brokk", "minecraft:diamond_leggings", 13000, [mc("diamond", 7), mc("iron_block", 2), mc("leather", 6)],
    { nom: "Jambières de Brokk", enchantements: { protection: 4, swift_sneak: 3, unbreaking: 3 } }],
  ["bottes_brokk", "minecraft:diamond_boots", 10000, [mc("diamond", 4), mc("iron_block", 1), mc("leather", 4)],
    { nom: "Bottes de Brokk", enchantements: { protection: 4, feather_falling: 4, depth_strider: 3, unbreaking: 3 } }],
  ["plastron_netherite", "minecraft:netherite_chestplate", 60000, [mc("netherite_ingot", 2), mc("diamond", 8), mc("blaze_rod", 4)],
    { nom: "Plastron noir de Brokk", enchantements: { protection: 4, unbreaking: 3, mending: 1 }, indice: "Le meilleur plastron du monde. Brokk le dit." }],
  ["bouclier_brokk", "minecraft:shield", 5000, [mc("iron_block", 2), mc("oak_planks", 8), mc("leather", 4)],
    { nom: "Bouclier de Brokk", enchantements: { unbreaking: 3, mending: 1 } }],
  ["targe_acier_noir", "cataclysm:black_steel_targe", 9000, [mc("iron_block", 6), mc("coal_block", 6), mc("leather", 8)]],
  ["bottes_robustes", "cataclysm:sturdy_boots", 12000, [mc("leather", 12), mc("iron_ingot", 16), mc("slime_ball", 8)]],
  ["masque_fureur", "mowziesmobs:umvuthana_mask_fury", 6000, [mc("gold_ingot", 4), mc("leather", 4), mc("feather", 8), mc("bone", 4)]],
  ["masque_foi", "mowziesmobs:umvuthana_mask_faith", 6000, [mc("gold_ingot", 4), mc("leather", 4), mc("feather", 8), mc("glistering_melon_slice", 2)]],
  ["robe_geomancien", "mowziesmobs:geomancer_robe", 15000, [mc("amethyst_shard", 16), mc("calcite", 16), mc("leather", 8), mc("diamond", 2)]],
  ["plastron_squelette", "mutantmonsters:mutant_skeleton_chestplate", 12000, [mc("bone_block", 16), mc("bone", 16), mc("iron_ingot", 8)]],
  ["jambieres_squelette", "mutantmonsters:mutant_skeleton_leggings", 10000, [mc("bone_block", 12), mc("bone", 12), mc("iron_ingot", 6)]],
  ["bottes_squelette", "mutantmonsters:mutant_skeleton_boots", 8000, [mc("bone_block", 8), mc("bone", 8), mc("iron_ingot", 4)]],
  ["plastron_crocodile", "alexsmobs:crocodile_chestplate", 9000, [mc("leather", 16), mc("iron_ingot", 8), mc("slime_ball", 4)]],
  ["bottes_coureur", "alexsmobs:roadrunner_boots", 6000, [mc("feather", 16), mc("leather", 8), mc("sugar", 8)]],
  ["casque_givre", "alexsmobs:froststalker_helmet", 7000, [mc("packed_ice", 16), mc("leather", 8), mc("white_wool", 4)]],
  ["bottes_poisson_volant", "alexsmobs:flying_fish_boots", 7000, [mc("cod", 8), mc("prismarine_shard", 8), mc("leather", 6)]],
  ["bouclier_profondeurs", "alexsmobs:shield_of_the_deep", 15000, [mc("prismarine", 16), mc("nautilus_shell", 2), mc("iron_block", 2)]],
  ["bouclier_corrompu", "endermanoverhaul:corrupted_shield", 15000, [mc("ender_pearl", 8), mc("obsidian", 8), mc("iron_block", 2)]],
  ["elytre_tarantule", "alexsmobs:tarantula_hawk_elytra", 35000, [mc("phantom_membrane", 16), mc("leather", 8), mc("string", 16), mc("diamond", 4)],
    { indice: "Des ailes. De vraies ailes. Brokk a pas compris comment, mais ça vole." }],
];

// les reliques : les armes des boss, refaites par Brokk avec ce qui leur ressemble (le feu des blazes pour Ignis, le
// cuivre et la foudre pour Scylla, l'obsidienne et l'ender pour le Vide…) ; plus chères, plus longues à forger
const IGNIS = "L'arme d'Ignis, le chevalier de feu. Brokk la refait au feu des blazes : pas besoin d'aller le battre.";
const SCYLLA = "L'arme de Scylla, la reine de la tempête. Brokk y met la foudre : cuivre et paratonnerres.";
const VIDE = "Une arme du Vide. Brokk la forge dans l'obsidienne et l'œil de l'Ender.";
const RELIQUES = [
  ["casque_forge", "mowziesmobs:wrought_helmet", 12000, [mc("iron_block", 5), mc("diamond", 2), mc("leather", 6)], { indice: "Le heaume du Wroughtnaut, le géant de fer.", temps: 30 }],
  ["hache_forgee", "mowziesmobs:wrought_axe", 15000, [mc("iron_block", 8), mc("diamond", 4), mc("blaze_rod", 4)], { indice: "La hache aux mille métaux du Wroughtnaut.", temps: 35 }],
  ["gantelet_terre", "mowziesmobs:earthrend_gauntlet", 30000, [mc("iron_block", 6), mc("amethyst_shard", 16), mc("diamond", 6), mc("netherite_scrap", 2)],
    { indice: "Le gantelet de la Sculptrice : il fend la terre.", temps: 40 }],
  ["baton_sculptrice", "mowziesmobs:sculptor_staff", 30000, [mc("amethyst_shard", 16), mc("calcite", 32), mc("blaze_rod", 4), mc("diamond", 4)],
    { indice: "Le bâton de la Sculptrice : il fait pousser la roche.", temps: 40 }],
  ["masque_soleil", "mowziesmobs:sol_visage", 45000, [mc("gold_block", 8), mc("blaze_rod", 12), mc("magma_cream", 8), mc("diamond", 8)],
    { indice: "Le masque du Soleil de Barako, le chef Umvuthana.", temps: 45 }],
  ["bardiche_corail", "cataclysm:coral_bardiche", 22000, [mc("prismarine_shard", 32), mc("prismarine_crystals", 16), mc("nautilus_shell", 2), mc("diamond", 3)],
    { indice: "La hache du Coralssus, le colosse de corail.", temps: 30 }],
  ["griffes_marees", "cataclysm:tidal_claws", 45000, [mc("prismarine_shard", 24), mc("nautilus_shell", 4), mc("heart_of_the_sea", 1), mc("diamond", 4)],
    { indice: "Les griffes du Léviathan, le monstre des abysses.", temps: 40 }],
  ["gantelet_maelstrom", "cataclysm:gauntlet_of_maelstrom", 55000, [mc("lightning_rod", 8), mc("copper_block", 8), mc("heart_of_the_sea", 1), mc("diamond", 4)],
    { indice: SCYLLA, temps: 45 }],
  ["astrape", "cataclysm:astrape", 40000, [mc("lightning_rod", 6), mc("copper_block", 6), mc("prismarine_crystals", 16), mc("diamond", 3)], { indice: SCYLLA, temps: 40 }],
  ["ceraunus", "cataclysm:ceraunus", 70000, [mc("lightning_rod", 8), mc("trident", 1), mc("prismarine_crystals", 16), mc("diamond", 4)], { indice: SCYLLA, temps: 50 }],
  ["sable_en_bouteille", "cataclysm:sandstorm_in_a_bottle", 25000, [mc("sand", 64), mc("ghast_tear", 2), mc("gold_block", 2), mc("glass_bottle", 1)],
    { indice: "La tempête de sable du Vestige antique, dans une bouteille.", temps: 30 }],
  ["colere_desert", "cataclysm:wrath_of_the_desert", 40000, [mc("bow", 1), mc("gold_block", 6), mc("sandstone", 32), mc("string", 16)],
    { indice: "L'arc du Vestige antique, le gardien du désert.", temps: 40 }],
  ["gantelet_garde", "cataclysm:gauntlet_of_guard", 35000, [mc("iron_block", 8), mc("obsidian", 16), mc("ender_eye", 4), mc("diamond", 6)],
    { indice: "Le gantelet du Gardien de l'Ender.", temps: 40 }],
  ["gantelet_rempart", "cataclysm:gauntlet_of_bulwark", 50000, [mc("blaze_rod", 16), mc("magma_block", 16), mc("netherite_scrap", 2), mc("iron_block", 4)], { indice: IGNIS, temps: 45 }],
  ["immolateur", "cataclysm:the_immolator", 70000, [mc("blaze_rod", 24), mc("magma_cream", 16), mc("netherite_ingot", 1), mc("diamond", 4)], { indice: IGNIS, temps: 50 }],
  ["plastron_ignitium", "cataclysm:ignitium_chestplate", 90000, [mc("netherite_chestplate", 1), mc("blaze_rod", 16), mc("magma_block", 16), mc("gold_block", 4)],
    { indice: IGNIS, temps: 55 }],
  ["forge_infernale", "cataclysm:infernal_forge", 60000, [mc("netherite_ingot", 1), mc("blaze_rod", 16), mc("magma_block", 16), mc("diamond", 8)],
    { indice: "Le marteau de la Monstruosité de netherite, au fond du Nether.", temps: 50 }],
  ["casque_monstrueux", "cataclysm:monstrous_helm", 55000, [mc("netherite_scrap", 4), mc("magma_block", 16), mc("blaze_rod", 8), mc("iron_block", 4)],
    { indice: "Le casque à cornes de la Monstruosité de netherite.", temps: 45 }],
  ["casque_cursium", "cataclysm:cursium_helmet", 75000, [mc("netherite_helmet", 1), mc("soul_sand", 32), mc("crying_obsidian", 8), mc("ghast_tear", 4)],
    { indice: "Le casque de Maledictus, le roi maudit. Brokk l'a maudit lui-même.", temps: 55 }],
  ["hachoir", "cataclysm:meat_shredder", 50000, [mc("iron_block", 6), mc("redstone_block", 6), mc("netherite_scrap", 2), mc("diamond", 2)],
    { indice: "La scie du Harbinger, la machine du Wither.", temps: 45 }],
  ["canon_vide", "cataclysm:void_assault_shoulder_weapon", 75000, [mc("obsidian", 16), mc("ender_eye", 8), mc("redstone_block", 4), mc("netherite_ingot", 1)],
    { indice: VIDE, temps: 55 }],
  ["forge_vide", "cataclysm:void_forge", 80000, [mc("obsidian", 16), mc("ender_eye", 8), mc("crying_obsidian", 8), mc("netherite_ingot", 1)], { indice: VIDE, temps: 55 }],
  ["decoupeur_dimensions", "alexsmobs:dimensional_carver", 40000, [mc("ender_eye", 8), mc("obsidian", 16), mc("amethyst_shard", 16), mc("diamond", 4)],
    { indice: "La lame du Ver du Vide, qui découpe les dimensions.", temps: 45 }],
  ["incinerateur", "cataclysm:the_incinerator", 120000, [mc("netherite_sword", 1), mc("blaze_rod", 24), mc("magma_block", 32), mc("diamond_block", 2)],
    { indice: "La grande épée d'Ignis. La plus belle pièce de Brokk.", temps: 60 }],
];

export function genererForge({ ecrire }) {
  // on repart de zéro : une recette retirée d'ici ne doit pas rester dans le mod
  fs.rmSync(new URL("../src/main/resources/data/cabane/forge/", import.meta.url), { recursive: true, force: true });
  for (const [categorie, liste, base] of [["arme", ARMES, 100], ["armure", ARMURES, 200], ["relique", RELIQUES, 300]]) {
    liste.forEach(([fichier, resultat, prix, ingredients, plus = {}], i) => {
      const { enchantements, ...reste } = plus;
      const json = { resultat, prix, ordre: base + i, categorie, ...reste,
        ...(enchantements ? { enchantements: Object.fromEntries(Object.entries(enchantements).map(([e, n]) => [`minecraft:${e}`, n])) } : {}),
        ingredients };
      ecrire(`data/cabane/forge/${fichier}.json`, JSON.stringify(json, null, 2) + "\n");
    });
  }
}
