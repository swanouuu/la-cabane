// Les noms français des objets et des monstres cités par les quêtes, les contrats, la forge… pour le serveur.
// Un serveur dédié ne connaît que l'anglais : ce que le mod écrit lui-même (« Apporter 16 × … ») sortait en anglais.
// Écrit src/main/resources/data/cabane/noms_fr.json (clé de traduction → nom), lu par fr.cabane.Noms.
//   node mod-cabane/outils/noms-fr.mjs <dossier des mods> <fichier fr_fr.json de Minecraft>
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { A_LA_MAIN } from "./noms-fr-main.mjs";

const ici = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(ici, "..", "src", "main", "resources");
const JAVA = path.join(ici, "..", "src", "main", "java", "fr", "cabane");
const [dossierMods, vanilla] = process.argv.slice(2);
const TAR = "C:/Windows/System32/tar.exe";

const lire = (texte) => { try { return JSON.parse(texte.replace(/^\uFEFF/, "")); } catch { return {}; } };
const fr = {}, en = {};
Object.assign(fr, lire(fs.readFileSync(vanilla, "utf8")));
for (const jar of fs.readdirSync(dossierMods).filter((f) => f.endsWith(".jar") && !f.startsWith("cabane-"))) {
  const chemin = path.join(dossierMods, jar);
  const noms = execFileSync(TAR, ["-tf", chemin], { encoding: "utf8", maxBuffer: 1 << 28 }).split(/\r?\n/);
  for (const n of noms.filter((x) => /^assets\/[^/]+\/lang\/(fr_fr|en_us)\.json$/.test(x))) {
    const contenu = lire(execFileSync(TAR, ["-xOf", chemin, n], { encoding: "utf8", maxBuffer: 1 << 28 }));
    Object.assign(n.endsWith("fr_fr.json") ? fr : en, contenu);
  }
}
const cabane = lire(fs.readFileSync(path.join(RES, "assets", "cabane", "lang", "fr_fr.json"), "utf8"));
Object.assign(fr, cabane);

// tous les identifiants cités dans les données du mod et dans les listes écrites en Java
const ids = new Set();
function fouiller(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) fouiller(p);
    else if (/\.(json|java|snbt)$/.test(e.name)) for (const m of fs.readFileSync(p, "utf8").matchAll(/"([a-z0-9_.-]+:[a-z0-9_/.-]+)"/g)) ids.add(m[1]);
  }
}
fouiller(path.join(RES, "data", "cabane"));
fouiller(path.join(ici, "..", "..", "pack", "config", "ftbquests", "quests"));
fouiller(path.join(JAVA, "hub"));
fouiller(path.join(JAVA, "pnj"));
fouiller(path.join(JAVA, "boutique"));
fouiller(path.join(JAVA, "drogue"));

const sortie = {}, manquants = [];
for (const id of [...ids].sort()) {
  const [ns, nom] = id.split(":");
  for (const type of ["item", "block", "entity"]) {
    const cle = `${type}.${ns}.${nom.replace(/\//g, ".")}`;
    if (A_LA_MAIN[cle]) sortie[cle] = A_LA_MAIN[cle];
    else if (fr[cle]) sortie[cle] = fr[cle];
    else if (en[cle]) manquants.push(`${cle} = ${en[cle]}`);
  }
}
// les objets du mod : tous, ils ne sont pas tous cités dans les données
for (const [cle, valeur] of Object.entries(cabane)) if (/^(item|block|entity)\.cabane\.[^.]+$/.test(cle)) sortie[cle] = valeur;

fs.writeFileSync(path.join(RES, "data", "cabane", "noms_fr.json"), JSON.stringify(sortie, null, 0).replace(/","/g, '",\n"') + "\n");
console.log(Object.keys(sortie).length, "noms ;", manquants.length, "sans français (restent en anglais) :");
for (const m of manquants) console.log("  " + m);
