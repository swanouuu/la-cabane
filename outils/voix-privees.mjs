// Le pack de ressources « Beauvais - voix » : les répliques que certains PNJ lâchent tout seuls (voix/ambiance/<pnj>-N.ogg),
// la bande-son de la cinématique de Vegeta (voix/cinematique/), et des bruitages du doublage qui remplacent ceux du mod
// (voix/effets/<son>.ogg, par exemple minato.flash.ogg pour les téléportations de Minato), et Kurama pour la Bijuu Dama
// de Minato 2 (voix/kurama/modele.json et kurama.png : le modèle et la texture d'un autre mod, qui sont à leur auteur).
// Ces enregistrements ne vont ni sur GitHub ni dans le mod : ils voyagent seulement dans dist/SakaiMod.zip, envoyé à la main.
// Le serveur joue le son cabane:ambiance.<pnj> (Ambiance.java) ; ceux qui ont le pack l'entendent. Sans le pack, les
// bruitages restent ceux du mod.
import fs from "node:fs";
import path from "node:path";
import { RACINE } from "./commun.mjs";

export const NOM_PACK_VOIX = "Beauvais - voix";

/**
 * Un son du pack, s'il est en Ogg Vorbis tout seul ; sinon null, avec un avertissement : Minecraft reste muet sur un .ogg
 * sorti d'une vidéo (une piste Theora avant le son), et c'est alors le bruitage du mod qui se joue.
 */
function son(chemin) {
  const contenu = fs.readFileSync(chemin);
  const debut = 27 + (contenu[26] ?? 0);
  if (contenu.subarray(0, 4).toString("latin1") === "OggS" && contenu.subarray(debut, debut + 7).toString("latin1") === "\x01vorbis"
      && !contenu.subarray(0, 16384).includes("theora")) {
    return contenu;
  }
  console.warn(`⚠ ${path.basename(chemin)} n'est pas un son Ogg Vorbis seul (une vidéo dedans ?) : Minecraft ne le jouerait pas.`);
  console.warn("  Reconvertis-le : ffmpeg -i ancien.ogg -vn -c:a libvorbis nouveau.ogg");
  return null;
}

/** Les fichiers du pack, en chemins relatifs à son dossier ; vide s'il n'y a aucune réplique. */
export function packVoix() {
  const dossier = path.join(RACINE, "voix", "ambiance");
  const sons = {};
  const fichiers = [];
  for (const fichier of fs.existsSync(dossier) ? fs.readdirSync(dossier).sort() : []) {
    const m = fichier.toLowerCase().match(/^([a-z_]+?)(?:[-_ ]?\d+|-.+)?\.ogg$/);
    if (!m) continue;
    const nom = fichier.toLowerCase().replace(/\.ogg$/, "").replace(/[^a-z0-9_-]/g, "_");
    const contenu = son(path.join(dossier, fichier));
    if (!contenu) continue;
    fichiers.push([`assets/cabane/sounds/ambiance/${nom}.ogg`, contenu]);
    (sons[`ambiance.${m[1]}`] ??= { subtitle: "subtitles.cabane.voix", sounds: [] }).sounds.push({ name: `cabane:ambiance/${nom}`, stream: true });
  }
  // la bande-son de la cinématique (voix/cinematique/extrait.ogg → cabane:cine.extrait)
  const cine = path.join(RACINE, "voix", "cinematique");
  if (fs.existsSync(cine)) {
    for (const fichier of fs.readdirSync(cine).sort()) {
      if (!/\.ogg$/i.test(fichier)) continue;
      const nom = fichier.toLowerCase().replace(/\.ogg$/, "").replace(/[^a-z0-9_]/g, "_");
      const contenu = son(path.join(cine, fichier));
      if (!contenu) continue;
      fichiers.push([`assets/cabane/sounds/cinematique/${nom}.ogg`, contenu]);
      sons[`cine.${nom}`] = { sounds: [{ name: `cabane:cinematique/${nom}`, stream: true }] };
    }
  }
  // les bruitages qui remplacent ceux du mod (voix/effets/minato.flash.ogg → cabane:minato.flash)
  const effets = path.join(RACINE, "voix", "effets");
  if (fs.existsSync(effets)) {
    for (const fichier of fs.readdirSync(effets).sort()) {
      if (!/\.ogg$/i.test(fichier)) continue;
      const evenement = fichier.toLowerCase().replace(/\.ogg$/, "");
      const nom = evenement.replace(/[^a-z0-9_]/g, "_");
      const contenu = son(path.join(effets, fichier));
      if (!contenu) continue;
      fichiers.push([`assets/cabane/sounds/effets/${nom}.ogg`, contenu]);
      sons[evenement] = { replace: true, subtitle: `subtitles.cabane.${evenement}`, sounds: [{ name: `cabane:effets/${nom}` }] };
    }
  }
  // Kurama (ModeleKurama : assets/cabane/kurama/modele.json et textures/entity/kurama/kurama.png)
  const kurama = path.join(RACINE, "voix", "kurama");
  for (const [fichier, dans] of [["modele.json", "assets/cabane/kurama/modele.json"], ["kurama.png", "assets/cabane/textures/entity/kurama/kurama.png"]]) {
    if (fs.existsSync(path.join(kurama, fichier))) fichiers.push([dans, fs.readFileSync(path.join(kurama, fichier))]);
  }
  if (!fichiers.length) return [];
  return [
    ["pack.mcmeta", Buffer.from(JSON.stringify({ pack: { pack_format: 34, description: "Les voix de Beauvais" } }, null, 2))],
    ["assets/cabane/sounds.json", Buffer.from(JSON.stringify(sons, null, 2))],
    ...fichiers,
  ];
}
