// Calcule d'avance les cubes des ailes pour l'aperçu, à chaque finesse (fins, jeu, gros) : la main (main-*.bin), la main
// sans doigts d'où sortent les serpents (paume-*.bin), la main fissurée de la transformation (fissures-*.bin), l'anneau
// et la tête des serpents (anneau-*.bin, tete-*.bin) ; et les fichiers du mod, à la finesse « jeu », dans
// mod-cabane/src/main/resources/assets/cabane/marque : main.bin (la main), serpent.bin (l'anneau, la tête, les poses
// « doigt » des serpents) et fissures.bin (les fissures de la transformation).
// À relancer après chaque retouche de main-voxel.mjs : node docs/marque3/apercu/construire.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { construireMain, construireSerpent, versBinaire, versMod, versModSerpent, versModFissures, ECHELLE, PAS_FIN, PAS_JEU } from "./main-voxel.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const MOD = path.join(ICI, "../../../mod-cabane/src/main/resources/assets/cabane/marque");
const versLeMod = (nom, octets) => {
  fs.mkdirSync(MOD, { recursive: true });
  fs.writeFileSync(path.join(MOD, nom), octets);
  console.log("mod", nom, Math.round(octets.length / 1024), "Ko");
};
const ecrire = (nom, m) => {
  const bin = versBinaire(m);
  fs.writeFileSync(path.join(ICI, nom + ".bin"), bin);
  console.log(nom, Math.round(bin.length / 1024), "Ko", m.quads.nor.length / 3, "faces");
};
for (const [finesse, pas] of [["fins", PAS_FIN], ["jeu", PAS_JEU], ["gros", 1 / ECHELLE]]) {
  const main = construireMain(pas);
  ecrire("main-" + finesse, main);
  ecrire("paume-" + finesse, construireMain(pas, { doigts: false }));
  const fissures = construireMain(pas, { fissures: true });
  ecrire("fissures-" + finesse, fissures);
  const serpent = construireSerpent(pas);
  if (finesse === "jeu") {
    versLeMod("main.bin", versMod(main));
    versLeMod("serpent.bin", versModSerpent(serpent, pas));
    versLeMod("fissures.bin", versModFissures(fissures));
  }
  ecrire("anneau-" + finesse, serpent.anneau);
  ecrire("tete-" + finesse, serpent.tete);
}
