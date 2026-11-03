// Les accessoires Saiyan (accessoire/Saiyan.java) : la queue et les détecteurs (8 couleurs de verre). Ici : les modèles
// d'objet, les noms, et le slot Curios de la queue (le détecteur va dans celui de la tête). Les textures (icônes 64×64,
// poil de la queue, haut-parleur, icône du slot) sont dessinées par docs/saiyan/generateur/textures.mjs.
// Appelé par generer.mjs.

export const COULEURS_SAIYAN = ["rouge", "vert", "bleu", "violet", "jaune", "orange", "rose", "cyan"];
export const DETECTEURS = COULEURS_SAIYAN.map((c) => `detecteur_${c}`);

export function genererSaiyan({ ecrire, lang }) {
  for (const id of ["queue_saiyan", ...DETECTEURS]) {
    ecrire(`assets/cabane/models/item/${id}.json`, { parent: "minecraft:item/generated", textures: { layer0: `cabane:item/${id}` } });
  }
  lang["item.cabane.queue_saiyan"] = "Queue de Saiyan";
  for (const c of COULEURS_SAIYAN) lang[`item.cabane.detecteur_${c}`] = `Détecteur ${c}`;
  // le slot de la queue, entre la ceinture et le dos dans l'inventaire de Curios ; le joueur a aussi celui de la tête
  ecrire("data/cabane/curios/slots/queue.json", { order: 185, icon: "cabane:slot/queue", validators: ["curios:tag"] });
  ecrire("data/cabane/curios/entities/saiyan.json", { entities: ["minecraft:player"], slots: ["head", "queue"] });
  lang["curios.identifier.queue"] = "Queue";
}
