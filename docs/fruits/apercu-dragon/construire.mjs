// Met les modèles du jeu (la tête du dragon, le casque, les plaques) dans donnees.js, pour que dragon.html s'ouvre
// tout seul, sans serveur (file://).  node construire.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const F3 = path.join(ICI, "..", "..", "..", "mod-cabane", "src", "main", "resources", "assets", "cabane", "fruit3");
const NOMS = ["tete_lave", "tete_glace", "casque_lave", "casque_glace", "plaques_lave_0", "plaques_glace_0"];
const donnees = Object.fromEntries(NOMS.map((n) => [n, fs.readFileSync(path.join(F3, n + ".bin")).toString("base64")]));
fs.writeFileSync(path.join(ICI, "donnees.js"), "window.BINS = " + JSON.stringify(donnees) + ";\n");
console.log("donnees.js", NOMS.length, "modèles");
