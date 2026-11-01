// Le pack de ressources « Beauvais - voix » : les répliques que certains PNJ lâchent tout seuls (voix/ambiance/<pnj>-N.ogg),
// et la bande-son de la cinématique de Vegeta (voix/cinematique/).
// Ces enregistrements ne vont ni sur GitHub ni dans le mod : ils voyagent seulement dans dist/SakaiMod.zip, envoyé à la main.
// Le serveur joue le son cabane:ambiance.<pnj> (Ambiance.java) ; ceux qui ont le pack l'entendent.
import fs from "node:fs";
import path from "node:path";
import { RACINE } from "./commun.mjs";

export const NOM_PACK_VOIX = "Beauvais - voix";

/** Les fichiers du pack, en chemins relatifs à son dossier ; vide s'il n'y a aucune réplique. */
export function packVoix() {
  const dossier = path.join(RACINE, "voix", "ambiance");
  if (!fs.existsSync(dossier)) return [];
  const sons = {};
  const fichiers = [];
  for (const fichier of fs.readdirSync(dossier).sort()) {
    const m = fichier.toLowerCase().match(/^([a-z_]+?)(?:[-_ ]?\d+|-.+)?\.ogg$/);
    if (!m) continue;
    const nom = fichier.toLowerCase().replace(/\.ogg$/, "").replace(/[^a-z0-9_-]/g, "_");
    fichiers.push([`assets/cabane/sounds/ambiance/${nom}.ogg`, fs.readFileSync(path.join(dossier, fichier))]);
    (sons[`ambiance.${m[1]}`] ??= { subtitle: "subtitles.cabane.voix", sounds: [] }).sounds.push({ name: `cabane:ambiance/${nom}`, stream: true });
  }
  // la bande-son de la cinématique (voix/cinematique/extrait.ogg → cabane:cine.extrait)
  const cine = path.join(RACINE, "voix", "cinematique");
  if (fs.existsSync(cine)) {
    for (const fichier of fs.readdirSync(cine).sort()) {
      if (!/\.ogg$/i.test(fichier)) continue;
      const nom = fichier.toLowerCase().replace(/\.ogg$/, "").replace(/[^a-z0-9_]/g, "_");
      fichiers.push([`assets/cabane/sounds/cinematique/${nom}.ogg`, fs.readFileSync(path.join(cine, fichier))]);
      sons[`cine.${nom}`] = { sounds: [{ name: `cabane:cinematique/${nom}`, stream: true }] };
    }
  }
  if (!fichiers.length) return [];
  return [
    ["pack.mcmeta", Buffer.from(JSON.stringify({ pack: { pack_format: 34, description: "Les voix de Beauvais" } }, null, 2))],
    ["assets/cabane/sounds.json", Buffer.from(JSON.stringify(sons, null, 2))],
    ...fichiers,
  ];
}
