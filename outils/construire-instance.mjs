// Fabrique dist/SakaiMod.zip : l'instance Prism Launcher à envoyer aux potes.
// À chaque lancement, Prism exécute packwiz-installer, qui met les mods à jour depuis urlDuPack.
//   node outils/construire-instance.mjs
import path from "node:path";
import { RACINE, zip } from "./commun.mjs";
import { fichiersInstance } from "./instance.mjs";

const { c, fichiers } = fichiersInstance();
const sortie = path.join(RACINE, "dist", `${c.nomDuPack}.zip`);
zip(sortie, fichiers);
console.log(`Instance prête : ${sortie}`);
console.log("À envoyer aux potes : ils l'importent dans Prism Launcher (Ajouter une instance → Importer).");
