// Le pack de ressources « Beauvais - voix » : les répliques que certains PNJ lâchent tout seuls (voix/ambiance/<pnj>-N.ogg),
// la bande-son de la cinématique de Vegeta (voix/cinematique/), et des bruitages du doublage qui remplacent ceux du mod
// (voix/effets/<son>.ogg, par exemple minato.flash.ogg pour les téléportations de Minato).
// Ces enregistrements ne vont ni sur GitHub ni dans le mod : ils voyagent seulement dans dist/SakaiMod.zip, envoyé à la main.
// Le serveur joue le son cabane:ambiance.<pnj> (Ambiance.java) ; ceux qui ont le pack l'entendent. Sans le pack, les
// bruitages restent ceux du mod.
import fs from "node:fs";
import path from "node:path";
import { RACINE } from "./commun.mjs";

export const NOM_PACK_VOIX = "Beauvais - voix";

/** Les fichiers du pack, en chemins relatifs à son dossier ; vide s'il n'y a aucune réplique. */
export function packVoix() {
  const dossier = path.join(RACINE, "voix", "ambiance");
  const sons = {};
  const fichiers = [];
  for (const fichier of fs.existsSync(dossier) ? fs.readdirSync(dossier).sort() : []) {
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
  // les bruitages qui remplacent ceux du mod (voix/effets/minato.flash.ogg → cabane:minato.flash)
  const effets = path.join(RACINE, "voix", "effets");
  if (fs.existsSync(effets)) {
    for (const fichier of fs.readdirSync(effets).sort()) {
      if (!/\.ogg$/i.test(fichier)) continue;
      const son = fichier.toLowerCase().replace(/\.ogg$/, "");
      const nom = son.replace(/[^a-z0-9_]/g, "_");
      fichiers.push([`assets/cabane/sounds/effets/${nom}.ogg`, fs.readFileSync(path.join(effets, fichier))]);
      sons[son] = { replace: true, subtitle: `subtitles.cabane.${son}`, sounds: [{ name: `cabane:effets/${nom}` }] };
    }
  }
  if (!fichiers.length) return [];
  return [
    ["pack.mcmeta", Buffer.from(JSON.stringify({ pack: { pack_format: 34, description: "Les voix de Beauvais" } }, null, 2))],
    ["assets/cabane/sounds.json", Buffer.from(JSON.stringify(sons, null, 2))],
    ...fichiers,
  ];
}
