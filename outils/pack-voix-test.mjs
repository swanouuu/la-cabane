// Met le pack privé « Beauvais - voix » (tout ce que voix/ contient : les voix, les bruitages, la bande-son, Kurama…)
// dans le Minecraft de test (mod-cabane/run/resourcepacks), pour que tester-en-jeu.bat ait le même pack que
// dist/SakaiMod.zip. Lancé par tester-en-jeu.bat ; rien ne part sur GitHub.
//   node outils/pack-voix-test.mjs
import fs from "node:fs";
import path from "node:path";
import { RACINE } from "./commun.mjs";
import { NOM_PACK_VOIX, packVoix } from "./voix-privees.mjs";

const fichiers = packVoix();
if (!fichiers.length) {
  console.log("Pas de pack privé : voix/ est vide.");
  process.exit(0);
}
const dossier = path.join(RACINE, "mod-cabane", "run", "resourcepacks", NOM_PACK_VOIX);
let copies = 0;
for (const [nom, contenu] of fichiers) {
  const cible = path.join(dossier, nom);
  if (fs.existsSync(cible) && fs.readFileSync(cible).equals(contenu)) continue;
  fs.mkdirSync(path.dirname(cible), { recursive: true });
  fs.writeFileSync(cible, contenu);
  copies++;
}
console.log(`Pack privé « ${NOM_PACK_VOIX} » : ${fichiers.length - 2} fichier(s), ${copies} mis à jour.`);
