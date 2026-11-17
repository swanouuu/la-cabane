// Le parc d'attractions : la dimension « cabane:parc » (toujours midi, grand soleil), son biome, le bouton du retour,
// et la musique de fête foraine (un orgue de manège qui joue une valse composée ici, synthétisée en .ogg avec ffmpeg
// la première fois). Voir parc/Parc.java et docs/parc/maquette.html. Appelé par generer.mjs.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const RACINE = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

// ---------------------------------------------------------------- la dimension
const TYPE_DIMENSION = {
  ultrawarm: false, natural: false, piglin_safe: false, respawn_anchor_works: false, bed_works: false, has_raids: false,
  has_skylight: true, has_ceiling: false, coordinate_scale: 1.0, ambient_light: 0.0,
  // toujours midi : il fait forcément beau au parc
  fixed_time: 6000,
  logical_height: 256, min_y: 0, height: 256, effects: "minecraft:overworld", infiniburn: "#minecraft:infiniburn_overworld",
  monster_spawn_light_level: 0, monster_spawn_block_light_limit: 0,
};
const DIMENSION = {
  type: "cabane:parc",
  generator: {
    type: "minecraft:flat",
    settings: {
      biome: "cabane:parc",
      layers: [{ block: "minecraft:bedrock", height: 1 }, { block: "minecraft:stone", height: 59 }, { block: "minecraft:dirt", height: 3 },
        { block: "minecraft:grass_block", height: 1 }],
      lakes: false, features: false, structure_overrides: [],
    },
  },
};
const BIOME = {
  has_precipitation: false, temperature: 0.8, downfall: 0.0,
  effects: {
    sky_color: 0x5cb3ff, fog_color: 0xd6ecff, water_color: 0x3f9bff, water_fog_color: 0x2a6fb0, grass_color: 0x79c05a, foliage_color: 0x59ae30,
    // la musique de manège, en boucle (ou presque)
    music: { sound: "cabane:musique.parc", min_delay: 20, max_delay: 120, replace_current_music: true },
  },
  spawners: {}, spawn_costs: {}, carvers: {}, features: [],
};

// ---------------------------------------------------------------- la musique : une valse d'orgue de manège
// La mélodie (note MIDI, durée en temps) et les accords par mesure, comme dans la maquette.
const MELODIE = [[67, 1], [72, 1], [76, 1], [79, 2], [76, 1], [77, 1], [76, 1], [74, 1], [71, 3], [74, 1], [77, 1], [79, 1], [83, 2], [79, 1],
  [81, 1], [79, 1], [76, 1], [72, 3], [69, 1], [72, 1], [77, 1], [81, 2], [77, 1], [79, 1], [76, 1], [72, 1], [76, 3], [74, 1], [76, 1], [77, 1],
  [79, 1], [71, 1], [74, 1], [77, 1], [74, 1], [71, 1], [72, 3]];
const ACCORDS = "CCGGGGCCFFCCGGGC";
const BASSE = { C: 48, G: 43, F: 41 }, NOTES = { C: [60, 64, 67], G: [59, 62, 67], F: [60, 65, 69] };
const TEMPS = 0.42, TOURS = 4;

function musique() {
  const sr = 44100, duree = 48 * TEMPS * TOURS + 1.5, n = Math.round(duree * sr), out = new Float32Array(n);
  const hz = (m) => 440 * Math.pow(2, (m - 69) / 12);
  // une voix : des harmoniques (son d'orgue), un vibrato, une enveloppe qui attaque vite et retombe un peu
  const voix = (t0, f, d, vol, harm, vibrato = true) => {
    const i0 = Math.round(t0 * sr), len = Math.round((d + 0.05) * sr);
    let phase = 0;
    for (let i = 0; i < len && i0 + i < n; i++) {
      const t = i / sr;
      const env = Math.min(1, t / 0.012) * (0.55 + 0.45 * Math.exp(-t / 0.14)) * Math.min(1, Math.max(0, (d - t) / 0.05));
      const fr = f * (vibrato ? 1 + 0.0045 * Math.sin(2 * Math.PI * 5.5 * (t0 + t)) : 1);
      phase += (2 * Math.PI * fr) / sr;
      let v = 0;
      for (const [k, a] of harm) v += a * Math.sin(phase * k);
      out[i0 + i] += v * vol * env;
    }
  };
  const ORGUE = [[1, 1], [2, 0.5], [3, 0.33], [4, 0.18], [5, 0.2], [7, 0.1]];
  const FLUTE = [[1, 1], [2, 0.15]];
  const TRIANGLE = [[1, 1], [3, -0.11], [5, 0.04]];
  for (let tour = 0; tour < TOURS; tour++) {
    const base = tour * 48 * TEMPS;
    let b = 0;
    for (const [m, d] of MELODIE) {
      voix(base + b * TEMPS, hz(m), d * TEMPS * 0.95, 0.18, ORGUE);
      if (tour % 2 === 1) voix(base + b * TEMPS, hz(m + 12), 0.25, 0.07, FLUTE, false);
      b += d;
    }
    for (let beat = 0; beat < 48; beat++) {
      const t = base + beat * TEMPS, acc = ACCORDS[Math.floor(beat / 3)];
      if (beat % 3 === 0) voix(t, hz(BASSE[acc]), TEMPS * 0.8, 0.3, TRIANGLE, false);
      else for (const m of NOTES[acc]) voix(t, hz(m), TEMPS * 0.35, 0.05, ORGUE, false);
    }
  }
  // un peu d'écho de grand air
  const retard = Math.round(0.11 * sr);
  for (let i = retard; i < n; i++) out[i] += out[i - retard] * 0.22;
  let max = 0;
  for (const v of out) max = Math.max(max, Math.abs(v));
  const k = 0.85 / (max || 1);
  for (let i = 0; i < n; i++) out[i] *= k * Math.min(1, (n - i) / (0.5 * sr));
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

/** Les sons du parc pour sounds.json (la musique est synthétisée une fois ; on efface le .ogg pour la refaire). */
export function sonsParc(RES) {
  const dossier = path.join(RES, "assets", "cabane", "sounds", "musique");
  fs.mkdirSync(dossier, { recursive: true });
  const cible = path.join(dossier, "parc.ogg"), ff = ffmpeg();
  if (!fs.existsSync(cible) && ff) {
    const tmp = path.join(os.tmpdir(), "cabane-musique-parc.wav");
    fs.writeFileSync(tmp, wav(musique()));
    execFileSync(ff, ["-y", "-loglevel", "error", "-i", tmp, "-c:a", "libvorbis", "-q:a", "4", cible]);
    fs.rmSync(tmp, { force: true });
  }
  return { "musique.parc": { sounds: [{ name: "cabane:musique/parc", stream: true, volume: 0.6 }] } };
}

export function genererParc({ ecrire, lang }) {
  ecrire("data/cabane/dimension_type/parc.json", TYPE_DIMENSION);
  ecrire("data/cabane/dimension/parc.json", DIMENSION);
  ecrire("data/cabane/worldgen/biome/parc.json", BIOME);
  // le bouton du retour : le même dessin que celui du labo
  ecrire("assets/cabane/blockstates/bouton_parc.json", { variants: { "": { model: "cabane:block/bouton_retour" } } });
  ecrire("assets/cabane/models/item/bouton_parc.json", { parent: "cabane:block/bouton_retour" });
  Object.assign(lang, {
    "entity.cabane.wagon_parc": "Wagon de manège", "entity.cabane.visiteur_parc": "Visiteur du parc", "entity.cabane.nacelle_parc": "Nacelle de manège", "entity.cabane.auto_parc": "Auto-tamponneuse", "entity.cabane.asterix": "Astérix", "entity.cabane.obelix": "Obélix", "block.cabane.bouton_parc": "Bouton de retour du parc", "biome.cabane.parc": "Parc de la Cabane",
  });
}
