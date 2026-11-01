// Fabrique dist/SakaiMod.zip : l'instance Prism Launcher à envoyer aux potes.
// À chaque lancement, Prism exécute packwiz-installer, qui met les mods à jour depuis urlDuPack.
//   node outils/construire-instance.mjs
import fs from "node:fs";
import path from "node:path";
import { RACINE, config, png, zip } from "./commun.mjs";
import { NOM_PACK_VOIX, packVoix } from "./voix-privees.mjs";

const c = config();
const MODELE = path.join(RACINE, "outils", "instance-prism");
if (c.urlDuPack.includes("TON-PSEUDO")) {
  console.warn("⚠ urlDuPack n'est pas encore rempli dans outils/config.json : l'instance ne pourra pas se mettre à jour.");
}

const instanceCfg = [
  "[General]",
  "ConfigVersion=1.2",
  "InstanceType=OneSix",
  `name=${c.nomDuPack}`,
  "iconKey=cabane",
  "OverrideCommands=true",
  `PreLaunchCommand="$INST_JAVA" -jar packwiz-installer-bootstrap.jar ${c.urlDuPack}`,
  "OverrideMemory=true",
  "MinMemAlloc=2048",
  `MaxMemAlloc=${c.memoireMaxMo}`,
  "",
].join("\r\n");

// Icône : la cabane, 16x16 agrandie en 64x64
const icone = png([
  "................",
  ".......RR.......",
  "......RRRR....C.",
  ".....RRRRRR...C.",
  "....RRRRRRRR..C.",
  "...RRRRRRRRRR.C.",
  "..RRRRRRRRRRRRC.",
  ".RRRRRRRRRRRRRRR",
  "...WWWWWWWWWW...",
  "...WWGGWWWGGW...",
  "...WWGGWWWGGW...",
  "...WWWWDDWWWW...",
  "...WWWWDDWWWW...",
  "...WWWWDDWWWW...",
  "HHHHHHHHHHHHHHHH",
  "HHHHHHHHHHHHHHHH"], { R: [92, 58, 38], C: [120, 120, 120], W: [158, 112, 70], G: [255, 210, 110], D: [60, 38, 22], H: [110, 160, 60] }, 4);

const fichiers = [
  ["instance.cfg", Buffer.from(instanceCfg)],
  ["mmc-pack.json", fs.readFileSync(path.join(MODELE, "mmc-pack.json"))],
  ["cabane.png", icone],
  ["minecraft/packwiz-installer-bootstrap.jar", fs.readFileSync(path.join(MODELE, "minecraft", "packwiz-installer-bootstrap.jar"))],
];

// Les voix privées : un pack de ressources activé d'office (il n'existe que dans ce zip, pas sur GitHub)
const voix = packVoix();
let options = fs.readFileSync(path.join(MODELE, "minecraft", "options.txt"), "utf8").trimEnd() + "\n";
if (voix.length) {
  for (const [nom, contenu] of voix) fichiers.push([`minecraft/resourcepacks/${NOM_PACK_VOIX}/${nom}`, contenu]);
  options += `resourcePacks:["vanilla","mod_resources","file/${NOM_PACK_VOIX}"]\n`;
  console.log(`Voix privées : ${voix.length - 2} son(s) dans le pack « ${NOM_PACK_VOIX} »`);
}
fichiers.push(["minecraft/options.txt", Buffer.from(options)]);

// Le serveur apparaît directement dans la liste Multijoueur
if (c.adresseServeur) {
  const str = (s) => { const b = Buffer.from(s, "utf8"); const l = Buffer.alloc(2); l.writeUInt16BE(b.length); return Buffer.concat([l, b]); };
  const tagStr = (nom, val) => Buffer.concat([Buffer.from([8]), str(nom), str(val)]);
  const serveur = Buffer.concat([tagStr("name", c.nomDuPack), tagStr("ip", c.adresseServeur), Buffer.from([1]), str("acceptTextures"), Buffer.from([1]), Buffer.from([0])]);
  const liste = Buffer.concat([Buffer.from([9]), str("servers"), Buffer.from([10]), Buffer.from([0, 0, 0, 1]), serveur]);
  fichiers.push(["minecraft/servers.dat", Buffer.concat([Buffer.from([10]), str(""), liste, Buffer.from([0])])]);
}

const sortie = path.join(RACINE, "dist", `${c.nomDuPack}.zip`);
zip(sortie, fichiers);
console.log(`Instance prête : ${sortie}`);
console.log("À envoyer aux potes : ils l'importent dans Prism Launcher (Ajouter une instance → Importer).");
