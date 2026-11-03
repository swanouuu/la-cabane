// Les slots accessoires de Curios (le mod est dans le pack) : quels objets du mod vont dans quel slot. Le jar ne garde qu'un
// fichier par tag (data/curios/tags/item/<slot>.json), donc tous les accessoires du mod sont listés ici. Les entrées sont
// facultatives (required: false) : un objet pas encore enregistré ne casse pas le tag des autres.
// Les slots eux-mêmes et les joueurs qui les ont sont dans data/cabane/curios (espionnes.mjs).
// Appelé par generer.mjs.

/** slot Curios → objets du mod qui y vont. */
export const SLOTS_CURIOS = {
  // les gadgets des espionnes (pouvoir/EspionneCurios.java)
  head: ["espionne_lunettes"],
  feet: ["espionne_bottes"],
  belt: ["espionne_grappin"],
};

export function genererCurios({ ecrire }) {
  for (const [slot, objets] of Object.entries(SLOTS_CURIOS)) {
    ecrire(`data/curios/tags/item/${slot}.json`, { replace: false, values: objets.map((id) => ({ id: `cabane:${id}`, required: false })) });
  }
}
