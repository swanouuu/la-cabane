// La grande expérience du labo (/experience) : ses bruits, synthétisés ici une fois pour toutes (la perceuse, les bips
// du moniteur et l'électrocardiogramme plat, le grésillement des lampes, le bourdon sourd de la salle).
// Appelé par generer.mjs ; lancé seul (node outils/operation.mjs), il fabrique les .ogg et les range dans sounds.json.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const RACINE = path.join(ICI, "..", "..");
const SR = 44100;

function bruiteur(graine) {
  return () => ((graine = (graine * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff) * 2 - 1;
}

function passeBas(fc) {
  const a = 1 - Math.exp((-2 * Math.PI * fc) / SR);
  let y = 0;
  return (x) => (y += a * (x - y));
}

function passeHaut(fc) {
  const pb = passeBas(fc);
  return (x) => x - pb(x);
}

/** Une dent de scie douce (les premières harmoniques), pour les moteurs. */
function scie(phase, n = 10) {
  let v = 0;
  for (let k = 1; k <= n; k++) v += Math.sin(phase * k) / k;
  return v;
}

const lisse = (k) => k * k * (3 - 2 * k);
const entre = (t, a, b) => Math.min(1, Math.max(0, (t - a) / (b - a)));

/** La perceuse qui entre dans le ventre : elle démarre, tourne à vide, force (le moteur peine et broute), puis s'arrête. */
function perceuse(duree, charge) {
  const n = Math.round(duree * SR), out = new Float32Array(n), alea = bruiteur(77);
  // la meule : un bruit serré autour de 2 kHz (deux filtres de chaque côté) ; les craquements et le grondement, plus graves
  const meule1 = passeBas(3000), meule2 = passeBas(3000), meuleH1 = passeHaut(1400), meuleH2 = passeHaut(1400), craque = passeBas(1200), grave = passeBas(300);
  let phase = 0, broute = 0, crac = 0;
  for (let i = 0; i < n; i++) {
    const t = i / SR;
    const demarre = lisse(entre(t, 0, 0.3)), arret = 1 - lisse(entre(t, duree - 0.55, duree - 0.05));
    const force = charge ? entre(t, 0.55, 0.75) * (1 - entre(t, duree - 0.8, duree - 0.55)) : 0;
    // le moteur peine sous la charge, et broute par à-coups
    if (i % 2205 === 0) broute = alea();
    const f = (190 + 30 * demarre) * (0.25 + 0.75 * demarre * arret) * (1 - 0.18 * force + 0.07 * force * broute * Math.sin(t * 2 * Math.PI * 9));
    phase += (2 * Math.PI * f) / SR;
    // le moteur (bourdonnant) et le sifflement aigu des engrenages, qui dominent
    let v = scie(phase, 20) * 0.5 + (Math.sin(phase * 16) * 0.32 + Math.sin(phase * 23) * 0.16) * demarre * arret;
    // la mèche qui gratte : la meule, hachée par la rotation
    const b = alea();
    const gratte = meuleH2(meuleH1(meule2(meule1(b)))) * Math.pow(Math.abs(Math.sin(phase * 2)), 4);
    v += gratte * force * 5;
    // des craquements (l'os !) de temps en temps
    if (force > 0.5 && alea() > 0.99993) crac = 1;
    crac *= 0.9993;
    v += craque(b) * crac * 2.5 + grave(b) * force * 0.9;
    out[i] = v * Math.max(demarre, 0.15) * arret;
  }
  return out;
}

/** Le bip du moniteur (court), ou le bip continu de l'électrocardiogramme plat. */
function bip(duree) {
  const n = Math.round(duree * SR), out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const t = i / SR;
    out[i] = (Math.sin(2 * Math.PI * 980 * t) + 0.08 * Math.sin(2 * Math.PI * 2940 * t)) * Math.min(1, t / 0.006) * Math.min(1, (duree - t) / 0.02);
  }
  return out;
}

/** Une lampe qui grésille : un bourdonnement électrique, des claquements et un souffle aigu. */
function zap(duree) {
  const n = Math.round(duree * SR), out = new Float32Array(n), alea = bruiteur(31), hp = passeHaut(2500), pb = passeBas(6000);
  let phase = 0, crac = 0;
  for (let i = 0; i < n; i++) {
    const t = i / SR;
    phase += (2 * Math.PI * 100) / SR;
    const b = alea();
    if (alea() > 0.9985) crac = 1;
    crac *= 0.997;
    const env = Math.min(1, t / 0.004) * Math.exp(-t / 0.35);
    out[i] = (scie(phase, 25) * 0.5 + hp(pb(b)) * 0.9 + b * crac * 0.9) * env;
  }
  return out;
}

/** Le bourdon de la salle : deux graves qui battent, un grondement, et un sifflement très loin, à peine là. */
function bourdon(duree) {
  const n = Math.round(duree * SR), out = new Float32Array(n), alea = bruiteur(13), pb = passeBas(160), pb2 = passeBas(900), hp = passeHaut(400);
  let p1 = 0, p2 = 0, p3 = 0, p4 = 0;
  for (let i = 0; i < n; i++) {
    const t = i / SR;
    p1 += (2 * Math.PI * 41) / SR;
    p2 += (2 * Math.PI * 41.7) / SR;
    p3 += (2 * Math.PI * 61.5) / SR;
    p4 += (2 * Math.PI * (1180 + 6 * Math.sin(2 * Math.PI * 0.21 * t))) / SR;
    const b = alea();
    const v = (Math.sin(p1) + Math.sin(p2)) * 0.5 + Math.sin(p3) * 0.25 * (0.5 + 0.5 * Math.sin(2 * Math.PI * 0.13 * t)) + pb(b) * 3
      + hp(pb2(b)) * 0.08 + Math.sin(p4) * 0.02 * (0.5 + 0.5 * Math.sin(2 * Math.PI * 0.3 * t));
    out[i] = v * Math.min(1, t / 1.5) * Math.min(1, (duree - t) / 1.5);
  }
  return out;
}

const SONS = {
  perceuse: () => perceuse(6.9, true),
  perceuse_vide: () => perceuse(0.95, false),
  bip: () => bip(0.13),
  bip_long: () => bip(3.0),
  zap: () => zap(0.95),
  bourdon: () => bourdon(10),
};

function normaliser(out, volume = 1) {
  let max = 0;
  for (const v of out) max = Math.max(max, Math.abs(v));
  const k = (0.92 * volume) / (max || 1), n = out.length;
  for (let i = 0; i < n; i++) out[i] *= k * Math.min(1, (n - i) / (0.01 * SR));
  return out;
}

function wav(echantillons) {
  const b = Buffer.alloc(44 + echantillons.length * 2);
  b.write("RIFF", 0); b.writeUInt32LE(36 + echantillons.length * 2, 4); b.write("WAVE", 8); b.write("fmt ", 12);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(SR, 24); b.writeUInt32LE(SR * 2, 28);
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

/** Les sons de l'expérience pour sounds.json (les .ogg sont synthétisés une fois ; on efface le fichier pour le refaire). */
export function sonsOperation(RES) {
  const dossier = path.join(RES, "assets", "cabane", "sounds", "operation");
  fs.mkdirSync(dossier, { recursive: true });
  const ff = ffmpeg();
  for (const [nom, fabrique] of Object.entries(SONS)) {
    const cible = path.join(dossier, nom + ".ogg");
    if (fs.existsSync(cible) || !ff) continue;
    const tmp = path.join(os.tmpdir(), `cabane-operation-${nom}.wav`);
    fs.writeFileSync(tmp, wav(normaliser(fabrique())));
    execFileSync(ff, ["-y", "-loglevel", "error", "-i", tmp, "-c:a", "libvorbis", "-q:a", "5", cible]);
    fs.rmSync(tmp, { force: true });
  }
  const sons = {};
  for (const nom of Object.keys(SONS)) sons[`operation.${nom}`] = { sounds: [{ name: `cabane:operation/${nom}` }] };
  return sons;
}

// lancé seul : on fabrique les sons et on les range dans sounds.json, juste après ceux du cartel (là où generer.mjs les met)
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const RES = path.join(ICI, "..", "src", "main", "resources");
  const fichier = path.join(RES, "assets", "cabane", "sounds.json");
  const avant = JSON.parse(fs.readFileSync(fichier, "utf8"));
  const nouveaux = sonsOperation(RES);
  const apres = {};
  const cles = Object.keys(avant).filter((k) => !k.startsWith("operation."));
  const dernierCartel = cles.findLastIndex((k) => k.startsWith("arme."));
  cles.forEach((k, i) => {
    apres[k] = avant[k];
    if (i === dernierCartel) Object.assign(apres, nouveaux);
  });
  if (dernierCartel < 0) Object.assign(apres, nouveaux);
  fs.writeFileSync(fichier, JSON.stringify(apres, null, 2) + "\n");
  console.log(`Sons de l'expérience : ${Object.keys(nouveaux).length} dans sounds.json`);
}
