// Le pack de ressources « Beauvais - voix » : les répliques que certains PNJ lâchent tout seuls (voix/ambiance/<pnj>-N.ogg),
// la bande-son de la cinématique de Vegeta (voix/cinematique/), et des bruitages du doublage qui remplacent ceux du mod
// (voix/effets/<son>.ogg, par exemple minato.flash.ogg pour les téléportations de Minato), et Kurama pour la Bijuu Dama
// de Minato 2 (voix/kurama/modele.json et kurama.png : le modèle et la texture d'un autre mod, qui sont à leur auteur).
// Et la musique du menu Échap (voix/musique/<catégorie>/<Animé - Titre>.mp3, les OST : voir musique() plus bas).
// Ces enregistrements ne vont ni sur GitHub ni dans le mod : ils voyagent seulement dans dist/SakaiMod.zip, envoyé à la main.
// Le serveur joue le son cabane:ambiance.<pnj> (Ambiance.java) ; ceux qui ont le pack l'entendent. Sans le pack, les
// bruitages restent ceux du mod.
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { RACINE, crc32 } from "./commun.mjs";

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

/** Les catégories du menu Musique, dans cet ordre ; un autre dossier de voix/musique devient une catégorie de plus. */
export const CATEGORIES_MUSIQUE = ["Épique", "Tristesse", "Ambiance", "Tension", "Drôle", "Génériques"];
const FORMATS = /\.(mp3|ogg|oga|opus|m4a|aac|wav|flac|webm|mp4|wma)$/i;

function ffmpeg() {
  const dossier = path.join(RACINE, ".outils", "ffmpeg");
  if (!fs.existsSync(dossier)) return null;
  for (const d of fs.readdirSync(dossier)) {
    const f = path.join(dossier, d, "bin", "ffmpeg.exe");
    if (fs.existsSync(f)) return f;
  }
  return null;
}

/** « Naruto - Sadness and Sorrow » → naruto_sadness_and_sorrow (le nom du son) ; un titre sans lettres latines → son numéro. */
function identifiant(nom) {
  const id = nom.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 48);
  return id || `morceau_${crc32(Buffer.from(nom)).toString(16)}`;
}

/** La durée d'un Ogg Vorbis en secondes : la position de sa dernière page, divisée par sa fréquence. */
function dureeOgg(contenu) {
  const debut = 27 + contenu[26];
  const frequence = contenu.readUInt32LE(debut + 12);
  const derniere = contenu.lastIndexOf("OggS");
  return Number(contenu.readBigInt64LE(derniere + 6)) / frequence;
}

/**
 * La musique du menu Échap (ClientMusique) : chaque fichier de voix/musique/<catégorie>/ (mp3, m4a, wav… tout ce que
 * ffmpeg lit), nommé « Animé - Titre », devient le son cabane:ost.<id>. Il est converti une fois pour toutes en Ogg
 * Vorbis, tous au même volume, dans voix/musique/.converties (refait si le fichier change), et la liste que le menu
 * affiche, avec les durées, va dans assets/cabane/musique/catalogue.json. Le même fichier dans deux dossiers (Tension
 * et Tristesse…) n'est converti qu'une fois et s'affiche dans les deux onglets.
 */
function musique(fichiers, sons) {
  const racine = path.join(RACINE, "voix", "musique");
  if (!fs.existsSync(racine)) return;
  const cache = path.join(racine, ".converties");
  const rang = (c) => (CATEGORIES_MUSIQUE.indexOf(c) + 1 || 99);
  const categories = fs.readdirSync(racine, { withFileTypes: true })
    .filter((d) => d.isDirectory() && !d.name.startsWith("."))
    .map((d) => d.name)
    .sort((a, b) => rang(a) - rang(b) || a.localeCompare(b, "fr"));
  const catalogue = [];
  const pris = new Set();
  // le même morceau dans plusieurs dossiers : un seul son, rangé dans chacune de ces catégories
  const parNom = new Map();
  let ff;
  for (const categorie of categories) {
    for (const fichier of fs.readdirSync(path.join(racine, categorie)).filter((f) => FORMATS.test(f)).sort((a, b) => a.localeCompare(b, "fr"))) {
      const source = path.join(racine, categorie, fichier);
      const nom = fichier.replace(FORMATS, "").trim();
      if (parNom.has(nom.toLowerCase())) {
        parNom.get(nom.toLowerCase()).categories.push(categorie);
        continue;
      }
      let id = identifiant(nom);
      for (let n = 2; pris.has(id); n++) id = `${identifiant(nom)}_${n}`;
      pris.add(id);
      const converti = path.join(cache, `${id}.ogg`);
      if (!fs.existsSync(converti) || fs.statSync(converti).mtimeMs < fs.statSync(source).mtimeMs) {
        ff ??= ffmpeg();
        if (!ff) {
          console.warn(`⚠ ${fichier} : ffmpeg introuvable dans .outils (lance installer-outils.bat), morceau ignoré.`);
          continue;
        }
        console.log(`Musique : conversion de « ${nom} »…`);
        fs.mkdirSync(cache, { recursive: true });
        const r = spawnSync(ff, ["-y", "-v", "error", "-i", source, "-vn", "-map", "0:a:0", "-map_metadata", "-1",
          "-af", "loudnorm=I=-18:TP=-2:LRA=11", "-ar", "44100", "-ac", "2", "-c:a", "libvorbis", "-q:a", "4", converti], { encoding: "utf8" });
        if (r.status !== 0) {
          console.warn(`⚠ ${fichier} n'a pas pu être converti : ${(r.stderr ?? "").trim().split("\n").pop()}`);
          fs.rmSync(converti, { force: true });
          continue;
        }
      }
      const contenu = fs.readFileSync(converti);
      const coupe = nom.indexOf(" - ");
      fichiers.push([`assets/cabane/sounds/ost/${id}.ogg`, contenu]);
      sons[`ost.${id}`] = { sounds: [{ name: `cabane:ost/${id}`, stream: true }] };
      const morceau = {
        id,
        categories: [categorie],
        anime: coupe > 0 ? nom.slice(0, coupe).trim() : "",
        titre: coupe > 0 ? nom.slice(coupe + 3).trim() : nom,
        duree: Math.round(dureeOgg(contenu) * 100) / 100,
      };
      parNom.set(nom.toLowerCase(), morceau);
      catalogue.push(morceau);
    }
  }
  if (catalogue.length) fichiers.push(["assets/cabane/musique/catalogue.json", Buffer.from(JSON.stringify(catalogue, null, 2))]);
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
  musique(fichiers, sons);
  if (!fichiers.length) return [];
  return [
    ["pack.mcmeta", Buffer.from(JSON.stringify({ pack: { pack_format: 34, description: "Les voix de Beauvais" } }, null, 2))],
    ["assets/cabane/sounds.json", Buffer.from(JSON.stringify(sons, null, 2))],
    ...fichiers,
  ];
}
