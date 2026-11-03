// Les bruits du pouvoir Foudre (pouvoir/Foudre.java), synthétisés une fois en .ogg (on efface le fichier pour le refaire) :
//  - statique : l'électricité statique qui crépite sur lui tant qu'il est éveillé (une boucle de 3 s) ;
//  - zap : le choc d'un coup, l'éclair en chaîne (un claquement, puis un bourdonnement qui retombe) ;
//  - charge : le bourdonnement de la charge du Kirin (une boucle ; le jeu monte sa hauteur avec la barre).
// Le cri « Kirin » est un tonnerre par défaut ; la voix de l'hôte n'est que dans le pack privé (voix/effets/foudre.kirin.ogg).
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const RACINE = path.join(ICI, "..", "..");
const SR = 44100;

/** Un hasard qui donne toujours la même suite (pour que le son ne change pas d'une génération à l'autre). */
function hasard(graine) {
  let s = graine >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Un craquement : un éclat de bruit blanc très court, aigu, qui s'éteint en quelques millisecondes. */
function craquement(out, debut, force, duree, h) {
  let avant = 0;
  for (let i = 0; i < duree * SR && debut + i < out.length; i++) {
    const b = h() * 2 - 1, aigu = b - avant;
    avant = b;
    out[debut + i] += aigu * force * Math.exp(-i / (duree * SR * 0.25));
  }
}

/** L'électricité statique : des craquements épars, des rafales serrées (« zzt ») sur un bourdon à 100 Hz, un souffle. */
function statique() {
  const n = 3 * SR, out = new Float64Array(n), h = hasard(7);
  // le souffle, très bas, filtré vers l'aigu
  let a = 0;
  for (let i = 0; i < n; i++) {
    const b = h() * 2 - 1;
    out[i] += (b - a) * 0.04;
    a = b;
  }
  // les craquements épars
  for (let t = 0; t < 3; t += -Math.log(1 - h()) / 22) craquement(out, Math.floor(t * SR), 0.3 + 0.7 * h() ** 2, 0.002 + 0.006 * h(), h);
  // les rafales : 40 à 150 ms de craquements serrés, sur un bourdonnement (le courant)
  for (let k = 0; k < 6; k++) {
    const debut = Math.floor((0.2 + k * 0.45 + 0.2 * h()) * SR), duree = 0.04 + 0.11 * h();
    for (let t = 0; t < duree; t += 0.002 + 0.004 * h()) craquement(out, debut + Math.floor(t * SR), 0.5 * h(), 0.002, h);
    for (let i = 0; i < duree * SR; i++) {
      const env = Math.sin((Math.PI * i) / (duree * SR));
      const buzz = Math.sign(Math.sin((2 * Math.PI * 100 * i) / SR)) * 0.6 + Math.sin((2 * Math.PI * 300 * i) / SR) * 0.4;
      if (debut + i < n) out[debut + i] += buzz * 0.12 * env * (0.5 + 0.5 * h());
    }
  }
  return out;
}

/** Le choc : un claquement sec, puis un bourdonnement électrique qui descend de 900 à 120 Hz et s'éteint. */
function zap() {
  const n = Math.floor(0.45 * SR), out = new Float64Array(n), h = hasard(11);
  for (let t = 0; t < 0.06; t += 0.002 + 0.003 * h()) craquement(out, Math.floor(t * SR), 0.9 * h(), 0.003, h);
  let phase = 0;
  for (let i = 0; i < n; i++) {
    const t = i / SR, f = 120 + 780 * Math.exp(-t / 0.08);
    phase += (2 * Math.PI * f) / SR;
    const scie = ((phase / (2 * Math.PI)) % 1) * 2 - 1;
    out[i] += (scie * 0.5 + Math.sign(Math.sin(phase * 0.5)) * 0.25) * Math.exp(-t / 0.12) * (0.7 + 0.3 * h());
  }
  return out;
}

/** La charge du Kirin : un bourdonnement grave et plein (110 Hz et ses harmoniques), qui pulse, plein de craquements. */
function charge() {
  const n = 2 * SR, out = new Float64Array(n), h = hasard(23);
  for (let i = 0; i < n; i++) {
    const t = i / SR;
    let v = 0;
    // des harmoniques entières de 2 Hz : la boucle se recolle sans claquer
    for (const [f, a] of [[110, 0.5], [220, 0.3], [330, 0.18], [440, 0.1], [662, 0.06]]) v += a * Math.sin(2 * Math.PI * f * t);
    out[i] = v * (0.75 + 0.25 * Math.sin(2 * Math.PI * 4 * t)) * 0.5;
  }
  for (let t = 0; t < 2; t += -Math.log(1 - h()) / 30) craquement(out, Math.floor(t * SR), 0.5 * h(), 0.003, h);
  return out;
}

const SONS = { statique, zap, charge };

function normaliser(s, niveau = 0.9, boucle = false) {
  let max = 0;
  for (const v of s) max = Math.max(max, Math.abs(v));
  const k = niveau / (max || 1), n = s.length;
  // un petit fondu au début et à la fin, pour ne pas claquer (une boucle se recolle d'elle-même)
  return s.map((v, i) => v * k * (boucle ? 1 : Math.min(1, i / (0.005 * SR), (n - i) / (0.03 * SR))));
}

function wav(e) {
  const b = Buffer.alloc(44 + e.length * 2);
  b.write("RIFF", 0); b.writeUInt32LE(36 + e.length * 2, 4); b.write("WAVE", 8); b.write("fmt ", 12);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(SR, 24); b.writeUInt32LE(SR * 2, 28);
  b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write("data", 36); b.writeUInt32LE(e.length * 2, 40);
  e.forEach((v, i) => b.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(v * 32767))), 44 + i * 2));
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

/** Les sons de la foudre pour sounds.json. */
export function sonsFoudre(RES) {
  const dossier = path.join(RES, "assets", "cabane", "sounds", "foudre");
  fs.mkdirSync(dossier, { recursive: true });
  const ff = ffmpeg();
  for (const [nom, fabrique] of Object.entries(SONS)) {
    const cible = path.join(dossier, nom + ".ogg");
    if (fs.existsSync(cible) || !ff) continue;
    const tmp = path.join(os.tmpdir(), `cabane-foudre-${nom}.wav`);
    fs.writeFileSync(tmp, wav(normaliser(fabrique(), 0.9, nom !== "zap")));
    execFileSync(ff, ["-y", "-loglevel", "error", "-i", tmp, "-c:a", "libvorbis", "-q:a", "5", cible]);
    fs.rmSync(tmp, { force: true });
  }
  const s = (nom, volume) => ({ subtitle: `subtitles.cabane.foudre.${nom}`, sounds: [{ name: `cabane:foudre/${nom}`, volume }] });
  return {
    "foudre.statique": s("statique", 0.8),
    "foudre.zap": s("zap", 0.8),
    "foudre.charge": s("charge", 0.7),
    // le cri « Kirin » : le tonnerre du trident par défaut ; la voix de l'hôte dans le pack privé
    "foudre.kirin": { subtitle: "subtitles.cabane.foudre.kirin", sounds: [{ name: "minecraft:item/trident/thunder1", volume: 0.8 }] },
  };
}
