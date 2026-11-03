// Les gadgets des espionnes (pouvoir/Espionne.java) : leurs noms, les slots de Curios où vont les accessoires, et le bruit de
// la fermeture éclair quand on enfile une tenue (synthétisé ici, une fois). Les icônes et les combinaisons sont dessinées
// par docs/spies/generateur/rendu.mjs ; les tags des slots sont dans curios.mjs. Appelé par generer.mjs.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const RACINE = path.join(ICI, "..", "..");

const NOMS = {
  espionne_rouge_a_levres: ["Rouge à lèvres laser", "Clic droit : un rayon rose, 4 cœurs, jusqu'à 24 blocs."],
  espionne_seche_cheveux: ["Sèche-cheveux turbo", "Clic droit : une rafale qui envoie voler tout ce qui est devant."],
  espionne_parfum: ["Parfum soporifique", "Clic droit : un nuage qui ralentit et aveugle 3 secondes."],
  espionne_chewing_gum: ["Chewing-gum collant", "Clic droit : la cible visée reste collée 2 secondes dans une bulle."],
  espionne_lunettes: ["Lunettes infrarouges", "Accessoire (tête) : tout ce qui vit brille en orange, à travers les murs."],
  espionne_bottes: ["Bottes à talons", "Accessoire (pieds) : un double saut en l'air, et plus aucun dégât de chute."],
  espionne_grappin: ["Ceinture grappin", "Accessoire (ceinture) : un appui court sur accroupi tire vers le mur visé."],
  espionne_tenue_verte: ["Tenue de Sam"],
  espionne_tenue_rouge: ["Tenue de Clover"],
  espionne_tenue_jaune: ["Tenue d'Alex"],
};

export function genererEspionnes({ ecrire, lang }) {
  for (const [id, [nom, desc]] of Object.entries(NOMS)) {
    lang[`item.cabane.${id}`] = nom;
    if (desc) lang[`item.cabane.${id}.desc`] = desc;
  }
  lang["item.cabane.espionne_tenue.desc"] = "Clic droit : on l'enfile (zip !). On l'enlève dans l'inventaire.";
  lang["subtitles.cabane.espionne.zip"] = "Fermeture éclair";
  // les slots de Curios : les joueurs ont une tête, des pieds et une ceinture (les pieds n'existent pas de base dans Curios)
  ecrire("data/cabane/curios/slots/feet.json", { size: 1, order: 190, icon: "minecraft:item/empty_armor_slot_boots", validators: ["curios:tag"] });
  ecrire("data/cabane/curios/entities/espionnes.json", { entities: ["minecraft:player"], slots: ["head", "feet", "belt"] });
}

// ---------------------------------------------------------------- le zip
/** Une fermeture éclair qu'on remonte d'un coup : une rafale de petits clics de plus en plus serrés et de plus en plus aigus. */
function zip() {
  const sr = 44100, duree = 0.42, n = Math.round(sr * duree);
  const out = new Float32Array(n);
  let graine = 7;
  const bruit = () => ((graine = (graine * 1103515245 + 12345) % 2147483648) / 1073741824) - 1;
  let t = 0.01, k = 0;
  while (t < duree - 0.03) {
    // les dents passent de plus en plus vite
    const pas = 0.016 - 0.011 * (t / duree);
    const debut = Math.round(t * sr), clair = 0.35 + 0.65 * (t / duree);
    let passe = 0;
    for (let i = 0; i < Math.round(0.006 * sr) && debut + i < n; i++) {
      const env = Math.exp(-i / (0.0012 * sr));
      // un bruit filtré (plus aigu à la fin) et un petit « tic » métallique
      passe = passe + clair * (bruit() - passe);
      out[debut + i] += env * (0.55 * passe + 0.25 * Math.sin(2 * Math.PI * (2600 + 1800 * clair) * i / sr));
    }
    t += pas * (0.85 + 0.3 * ((k++ * 7) % 5) / 4);
  }
  // un léger frottement continu sous les clics
  let frotte = 0;
  for (let i = 0; i < n; i++) {
    frotte = frotte + 0.2 * (bruit() - frotte);
    out[i] += 0.06 * frotte * Math.sin(Math.PI * i / n);
  }
  let max = 0;
  for (const v of out) max = Math.max(max, Math.abs(v));
  for (let i = 0; i < n; i++) out[i] *= 0.8 / max;
  return out;
}

function wav(echantillons) {
  const sr = 44100, b = Buffer.alloc(44 + echantillons.length * 2);
  b.write("RIFF", 0); b.writeUInt32LE(36 + echantillons.length * 2, 4); b.write("WAVE", 8); b.write("fmt ", 12);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(sr, 24); b.writeUInt32LE(sr * 2, 28);
  b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write("data", 36); b.writeUInt32LE(echantillons.length * 2, 40);
  echantillons.forEach((v, i) => b.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(v * 32767))), 44 + i * 2));
  return b;
}

function ffmpeg() {
  const dossier = path.join(RACINE, ".outils", "ffmpeg");
  if (!fs.existsSync(dossier)) return null;
  for (const d of fs.readdirSync(dossier)) {
    const f = path.join(dossier, d, "bin", "ffmpeg.exe");
    if (fs.existsSync(f)) return f;
  }
  return null;
}

/** Le son du zip pour sounds.json (le .ogg est synthétisé une fois ; on efface le fichier pour le refaire). */
export function sonsEspionnes(RES) {
  const cible = path.join(RES, "assets", "cabane", "sounds", "espionne", "zip.ogg");
  const ff = ffmpeg();
  if (!fs.existsSync(cible) && ff) {
    fs.mkdirSync(path.dirname(cible), { recursive: true });
    const tmp = path.join(os.tmpdir(), "cabane-espionne-zip.wav");
    fs.writeFileSync(tmp, wav(zip()));
    execFileSync(ff, ["-y", "-loglevel", "error", "-i", tmp, "-c:a", "libvorbis", "-q:a", "5", cible]);
    fs.rmSync(tmp, { force: true });
  }
  return { "espionne.zip": { subtitle: "subtitles.cabane.espionne.zip", sounds: [{ name: "cabane:espionne/zip" }] } };
}
