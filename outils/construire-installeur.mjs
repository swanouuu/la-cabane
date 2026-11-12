// Fabrique dist/Installer SakaiMod.bat : un seul fichier à envoyer aux potes. Il installe Prism Launcher, notre instance
// et un raccourci sur le Bureau (voir installeur-modele.ps1). Il ne contient rien de privé : on peut le mettre en ligne.
// À refaire quand l'adresse du serveur change (adresseServeur dans outils/config.json).
//   node outils/construire-installeur.mjs
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { RACINE, zip } from "./commun.mjs";
import { fichiersInstance } from "./instance.mjs";

// La version de Prism est figée, avec son empreinte : l'installeur refuse un fichier différent de celui-ci.
// Version MinGW : elle embarque tout ce qu'il lui faut, sans dépendre d'un composant Microsoft à installer.
const PRISM_VERSION = "11.1.1";
const PRISM_FICHIER = `PrismLauncher-Windows-MinGW-w64-Portable-${PRISM_VERSION}.zip`;
const PRISM_URL = `https://github.com/PrismLauncher/PrismLauncher/releases/download/${PRISM_VERSION}/${PRISM_FICHIER}`;
const PRISM_SHA = "05841d0b3bfc0a8212658457cc6035e1c29cd7ffae919464e874e384cbe8d062";

const { c, fichiers } = fichiersInstance({ avecVoix: false });

// Le contenu, zippé puis collé en texte dans le script
const tempZip = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "installeur-")), "contenu.zip");
const raccourci = fs.readFileSync(path.join(RACINE, "outils", "logo-launcher.ico"));
zip(tempZip, [...fichiers.map(([nom, contenu]) => [`instance/${nom}`, contenu]), ["raccourci.ico", raccourci]]);
const contenu = fs.readFileSync(tempZip).toString("base64");
fs.rmSync(path.dirname(tempZip), { recursive: true, force: true });

const valeurs = {
  NOM: c.nomDuPack,
  PRISM_VERSION,
  PRISM_URL,
  PRISM_SHA,
  SERVEUR: c.adresseServeur ?? "",
  MEMOIRE: String(c.memoireMaxMo),
  CONTENU: contenu,
};
const script = fs.readFileSync(path.join(RACINE, "outils", "installeur-modele.ps1"), "utf8")
  .replace(/\r\n/g, "\n")
  .replace(/@@([A-Z_]+)@@/g, (_, cle) => {
    if (!(cle in valeurs)) throw new Error(`Valeur inconnue dans le modèle : ${cle}`);
    return valeurs[cle];
  });

// Un fichier à la fois batch et PowerShell : le double-clic lance cmd, qui relit ce même fichier avec PowerShell.
// Pour PowerShell, l'en-tête batch est un commentaire. Le nom du fichier passe par une variable (une apostrophe dans
// le chemin casserait la commande).
const entete = [
  "<# : batch",
  "@echo off",
  "chcp 65001 >nul",
  'set "SAKAIMOD_SCRIPT=%~f0"',
  'powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-Expression (Get-Content -LiteralPath $env:SAKAIMOD_SCRIPT -Raw -Encoding UTF8)"',
  "echo.",
  "pause",
  "exit /b",
  "#>",
  "",
].join("\n");

const sortie = path.join(RACINE, "dist", `Installer ${c.nomDuPack}.bat`);
fs.mkdirSync(path.dirname(sortie), { recursive: true });
fs.writeFileSync(sortie, (entete + script).replace(/\n/g, "\r\n"));
console.log(`Installeur prêt : ${sortie} (${Math.round(fs.statSync(sortie).size / 1024)} Ko)`);
console.log(c.adresseServeur ? `Serveur : ${c.adresseServeur}` : "Pas encore d'adresse de serveur : le raccourci ouvrira le jeu sans rejoindre de serveur.");
