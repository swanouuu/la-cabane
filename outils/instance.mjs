// Les fichiers d'une instance Prism Launcher du pack : pour le zip à importer (construire-instance.mjs)
// et pour l'installeur en un clic (construire-installeur.mjs).
import fs from "node:fs";
import path from "node:path";
import { RACINE, config } from "./commun.mjs";
import { NOM_PACK_VOIX, packVoix } from "./voix-privees.mjs";

/** avecVoix : met le pack privé dedans (jamais dans ce qui peut devenir public, comme l'installeur). */
export function fichiersInstance({ avecVoix = true } = {}) {
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
    // entre guillemets, avec les guillemets intérieurs échappés : sinon Prism les retire et colle « -jar » au chemin de Java
    `PreLaunchCommand="\\"$INST_JAVA\\" -jar packwiz-installer-bootstrap.jar ${c.urlDuPack}"`,
    "OverrideMemory=true",
    "MinMemAlloc=2048",
    `MaxMemAlloc=${c.memoireMaxMo}`,
    "",
  ].join("\r\n");

  // Icône dans la liste de Prism : le logo du pack (celui du raccourci est outils/logo-launcher.ico)
  const icone = fs.readFileSync(path.join(RACINE, "outils", "logo-launcher.png"));

  const fichiers = [
    ["instance.cfg", Buffer.from(instanceCfg)],
    ["mmc-pack.json", fs.readFileSync(path.join(MODELE, "mmc-pack.json"))],
    ["cabane.png", icone],
    ["minecraft/packwiz-installer-bootstrap.jar", fs.readFileSync(path.join(MODELE, "minecraft", "packwiz-installer-bootstrap.jar"))],
  ];

  // Les voix privées : un pack de ressources activé d'office (il n'existe que dans ce zip, pas sur GitHub)
  const voix = avecVoix ? packVoix() : [];
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

  return { c, fichiers };
}
