// Les slots accessoires de Curios (le mod est dans le pack) : quels objets du mod vont dans quel slot. Le jar ne garde qu'un
// fichier par tag (data/curios/tags/item/<slot>.json), donc tous les accessoires du mod sont listés ici. Les entrées sont
// facultatives (required: false) : un objet pas encore enregistré ne casse pas le tag des autres.
// Les slots eux-mêmes et les joueurs qui les ont sont dans data/cabane/curios (saiyan.mjs pour la queue et la tête,
// espionnes.mjs pour les pieds et la ceinture).
// Appelé par generer.mjs.
import { DETECTEURS } from "./saiyan.mjs";
import { NOMS_MASQUES } from "./masques.mjs";

const MASQUES = Object.keys(NOMS_MASQUES).map((nom) => "masque_" + nom);

/** slot Curios → objets du mod qui y vont. */
export const SLOTS_CURIOS = {
  // les masques ninja (accessoire/Masques.java) : sur la tête, ou à la ceinture, sur le côté
  head: [...DETECTEURS, "espionne_lunettes", ...MASQUES],
  queue: ["queue_saiyan"],
  // les gadgets des espionnes (pouvoir/EspionneCurios.java)
  feet: ["espionne_bottes"],
  belt: ["espionne_grappin", ...MASQUES],
};

export function genererCurios({ ecrire }) {
  for (const [slot, objets] of Object.entries(SLOTS_CURIOS)) {
    ecrire(`data/curios/tags/item/${slot}.json`, { replace: false, values: objets.map((id) => ({ id: `cabane:${id}`, required: false })) });
  }
}
