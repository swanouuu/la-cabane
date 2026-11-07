// Le Cartel : la dimension « cabane:cartel » (type, générateur, biome de fin d'après-midi), les armes (icônes 64×64,
// modèles 3D construits pixel par pixel depuis la carte d'épaisseur des maquettes, bruits de tir synthétisés ici),
// les skins des gars, la boutique et le dialogue d'El Patrón. Les dessins viennent de docs/cartel/generateur.
// Appelé par generer.mjs.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { ARMES, PROJECTILES, icones } from "../../docs/cartel/generateur/armes.mjs";
import { GARS, skins } from "../../docs/cartel/generateur/skins.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const RACINE = path.join(ICI, "..", "..");

/** Une image RGBA en PNG. */
export function png(w, h, rgba) {
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) rgba.copy(brut, y * (1 + w * 4) + 1, y * w * 4, (y + 1) * w * 4);
  const t = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const x of buf) c = t[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

// ---------------------------------------------------------------- les modèles 3D des armes
/**
 * L'arme en volume : chaque ligne de l'icône est coupée en morceaux de même épaisseur, qu'on empile quand ils se
 * suivent ligne après ligne ; chaque morceau devient un pavé centré sur z = 8. Une icône de 64 pixels fait 16 unités
 * (un bloc) ; un pixel d'épaisseur fait 1/4 d'unité.
 */
export function elements(ic) {
  const N = 64, P = ic.prof, q = (d) => Math.max(1, Math.round(d * 2) / 2);
  const ouverts = new Map(), fini = [];
  for (let y = 0; y <= N; y++) {
    const ligne = [];
    for (let x = 0; y < N && x < N;) {
      const d = P[y * N + x] ? q(P[y * N + x]) : 0;
      if (!d) { x++; continue; }
      let x1 = x + 1;
      while (x1 < N && P[y * N + x1] && q(P[y * N + x1]) === d) x1++;
      ligne.push({ x0: x, x1, d });
      x = x1;
    }
    const suivants = new Map();
    for (const r of ligne) {
      const cle = `${r.x0},${r.x1},${r.d}`, o = ouverts.get(cle);
      if (o) { o.y1 = y + 1; suivants.set(cle, o); ouverts.delete(cle); } else suivants.set(cle, { ...r, y0: y, y1: y + 1 });
    }
    for (const o of ouverts.values()) fini.push(o);
    ouverts.clear();
    for (const [k, v] of suivants) ouverts.set(k, v);
  }
  const u = (v) => Math.round(v * 2500) / 10000;
  return fini.map(({ x0, x1, y0, y1, d }) => ({
    from: [u(x0), u(64 - y1), u(32 - d / 2)], to: [u(x1), u(64 - y0), u(32 + d / 2)],
    faces: {
      north: { uv: [u(x1), u(y0), u(x0), u(y1)], texture: "#t" },
      south: { uv: [u(x0), u(y0), u(x1), u(y1)], texture: "#t" },
      east: { uv: [u(x1 - 1), u(y0), u(x1), u(y1)], texture: "#t" },
      west: { uv: [u(x0), u(y0), u(x0 + 1), u(y1)], texture: "#t" },
      up: { uv: [u(x0), u(y0), u(x1), u(y0 + 1)], texture: "#t" },
      down: { uv: [u(x0), u(y1 - 1), u(x1), u(y1)], texture: "#t" },
    },
  }));
}

/**
 * Comment l'arme se tient : la bouche du canon vers +x dans le modèle (une rotation de 90° la tourne vers l'avant du
 * bras ; -90° la mettait à l'envers, crosse en avant), la poignée ramenée dans la main. À la troisième personne la grande arme
 * dépasse largement du bras (la Kalach fait presque deux blocs) ; à la première, elle part du bas à droite de l'écran.
 */
export const PRISES = {
  fusil: { thirdperson_righthand: { rotation: [0, 90, 0], translation: [0, 2, -2.5], scale: [1.75, 1.75, 1.75] }, firstperson_righthand: { rotation: [-2, 93, 0], translation: [0.5, 2.5, -4.5], scale: [1.15, 1.15, 1.15] } },
  epaule: { thirdperson_righthand: { rotation: [0, 90, 0], translation: [0, 3.5, -4], scale: [2, 2, 2] }, firstperson_righthand: { rotation: [-2, 93, 0], translation: [1.5, 2.5, -5], scale: [1.25, 1.25, 1.25] } },
  hanche: { thirdperson_righthand: { rotation: [0, 90, 0], translation: [0, -3, -6], scale: [1.9, 1.9, 1.9] }, firstperson_righthand: { rotation: [-2, 93, 0], translation: [0.5, 1.5, -4.5], scale: [1.15, 1.15, 1.15] } },
  main: { thirdperson_righthand: { rotation: [0, 90, 0], translation: [0, 2, -3], scale: [1, 1, 1] }, firstperson_righthand: { rotation: [-2, 93, 0], translation: [0.5, 3.5, -3], scale: [0.85, 0.85, 0.85] } },
  lancer: { thirdperson_righthand: { rotation: [0, 90, 0], translation: [0, 1, -1], scale: [0.55, 0.55, 0.55] }, firstperson_righthand: { rotation: [0, 93, 0], translation: [1.5, 3.5, -1], scale: [0.6, 0.6, 0.6] } },
  lame: { thirdperson_righthand: { rotation: [0, 90, 55], translation: [0, 4, 0.5], scale: [1, 1, 1] }, firstperson_righthand: { rotation: [0, 90, 25], translation: [1.13, 3.2, 1.13], scale: [0.8, 0.8, 0.8] } },
};
export const miroir = (d) => ({ rotation: [d.rotation[0], -d.rotation[1], -d.rotation[2]], translation: d.translation, scale: d.scale });

// ---------------------------------------------------------------- comment chaque arme se tient (les chiffres des armes sont dans cartel/Cartel.java)
const PRISE_DE = {
  ak47: "fusil", ak47_or: "fusil", uzi: "fusil", mac10: "main", thompson: "fusil", desert_eagle_or: "main", canon_scie: "fusil",
  barrett: "fusil", m60: "fusil", minigun: "hanche", rpg7: "epaule", m79: "fusil", grenade: "lancer", molotov: "lancer", machette: "lame",
};

// ---------------------------------------------------------------- les bruits des armes
/**
 * Un coup de feu synthétisé : le claquement (bruit blanc très bref), le corps (bruit filtré), le boum grave (une
 * sinusoïde qui descend) et la queue qui résonne. salves : les instants de chaque coup (la rafale au loin).
 */
const SONS = {
  ak47: { duree: 0.9, crack: [0.9, 0.006], corps: [0.8, 2200, 0.05], boum: [0.7, 140, 55, 0.07], queue: [0.32, 700, 0.35] },
  ak47_or: { duree: 0.9, crack: [0.9, 0.006], corps: [0.85, 2000, 0.055], boum: [0.75, 130, 50, 0.075], queue: [0.34, 650, 0.38] },
  uzi: { duree: 0.5, crack: [0.8, 0.004], corps: [0.7, 3200, 0.03], boum: [0.4, 180, 80, 0.04], queue: [0.18, 900, 0.18] },
  mac10: { duree: 0.4, crack: [0.12, 0.003], corps: [0.75, 900, 0.03], boum: [0.35, 160, 90, 0.03], queue: [0.08, 600, 0.12] },
  thompson: { duree: 0.8, crack: [0.7, 0.005], corps: [0.8, 1800, 0.05], boum: [0.6, 120, 60, 0.06], queue: [0.3, 650, 0.3] },
  desert_eagle_or: { duree: 1.3, crack: [1, 0.007], corps: [0.9, 1600, 0.08], boum: [1, 110, 40, 0.12], queue: [0.45, 500, 0.6] },
  canon_scie: { duree: 1.5, crack: [1, 0.008], corps: [1, 1200, 0.12], boum: [1, 90, 35, 0.15], queue: [0.5, 450, 0.8] },
  barrett: { duree: 2.2, crack: [1, 0.01], corps: [1, 1500, 0.1], boum: [1, 80, 30, 0.2], queue: [0.6, 400, 1.2] },
  m60: { duree: 0.8, crack: [0.85, 0.005], corps: [0.8, 1600, 0.05], boum: [0.75, 100, 45, 0.07], queue: [0.3, 600, 0.3] },
  minigun: { duree: 0.25, crack: [0.6, 0.003], corps: [0.7, 2500, 0.025], boum: [0.4, 160, 90, 0.03], queue: [0.08, 900, 0.08] },
  rpg7: { duree: 1.2, souffle: [1, 0.9], boum: [0.6, 90, 40, 0.1], queue: [0.3, 500, 0.5] },
  m79: { duree: 0.6, crack: [0.2, 0.004], corps: [0.6, 600, 0.06], boum: [0.9, 70, 40, 0.1], queue: [0.15, 400, 0.2] },
  explosion: { duree: 3, crack: [0.8, 0.012], corps: [1, 900, 0.4], boum: [1, 60, 25, 0.5], queue: [0.7, 300, 1.8] },
  lointain: { duree: 2.4, salves: [0, 0.11, 0.22, 0.33, 0.46, 0.57, 0.95, 1.06, 1.17], corps: [0.6, 700, 0.04], boum: [0.5, 110, 60, 0.05], queue: [0.35, 350, 0.9], volume: 0.5 },
};

function synthese(s) {
  const sr = 44100, n = Math.round(s.duree * sr), out = new Float32Array(n);
  let graine = 12345;
  const alea = () => ((graine = (graine * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff) * 2 - 1;
  const passeBas = (fc) => { const a = 1 - Math.exp((-2 * Math.PI * fc) / sr); let y = 0; return (x) => (y += a * (x - y)); };
  for (const t0 of s.salves ?? [0]) {
    const debut = Math.round(t0 * sr), pb = s.corps ? passeBas(s.corps[1]) : null, pq = s.queue ? passeBas(s.queue[1]) : null, pc = passeBas(2500);
    const sf = s.souffle ? passeBas(400) : null;
    let phase = 0;
    for (let i = 0; debut + i < n; i++) {
      const t = i / sr, b = alea();
      let v = 0;
      if (s.crack) v += (b - pc(b)) * s.crack[0] * Math.exp(-t / s.crack[1]);
      if (s.corps) v += pb(b) * s.corps[0] * 3 * Math.exp(-t / s.corps[2]);
      if (s.boum) {
        const [g, f0, f1, tau] = s.boum;
        phase += (2 * Math.PI * (f1 + (f0 - f1) * Math.exp(-t / 0.05))) / sr;
        v += Math.sin(phase) * g * Math.exp(-t / tau) * (1 - Math.exp(-t / 0.002));
      }
      if (s.queue) v += pq(b) * s.queue[0] * 4 * Math.exp(-t / s.queue[2]) * (1 - Math.exp(-t / 0.03));
      if (s.souffle) {
        // le départ de la roquette : un souffle qui monte puis s'éloigne
        const env = Math.min(1, t / 0.05) * Math.exp(-Math.max(0, t - 0.15) / 0.35);
        v += (sf(b) * 2.5 + (b - sf(b)) * 0.4) * s.souffle[0] * env;
      }
      out[debut + i] += v;
    }
  }
  let max = 0;
  for (const v of out) max = Math.max(max, Math.abs(v));
  const k = (0.92 * (s.volume ?? 1)) / (max || 1);
  // un fondu de 30 ms à la fin, pour ne pas finir sur un clic
  for (let i = 0; i < n; i++) out[i] *= k * Math.min(1, (n - i) / (0.03 * sr));
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

/** Les sons des armes pour sounds.json (les .ogg sont synthétisés une fois ; on efface le fichier pour le refaire). */
export function sonsCartel(RES) {
  const dossier = path.join(RES, "assets", "cabane", "sounds", "arme");
  fs.mkdirSync(dossier, { recursive: true });
  const ff = ffmpeg();
  for (const [nom, s] of Object.entries(SONS)) {
    const cible = path.join(dossier, nom + ".ogg");
    if (fs.existsSync(cible) || !ff) continue;
    const tmp = path.join(os.tmpdir(), `cabane-arme-${nom}.wav`);
    fs.writeFileSync(tmp, wav(synthese(s)));
    execFileSync(ff, ["-y", "-loglevel", "error", "-i", tmp, "-c:a", "libvorbis", "-q:a", "5", cible]);
    fs.rmSync(tmp, { force: true });
  }
  const sons = {};
  for (const nom of Object.keys(SONS)) {
    sons[`arme.${nom}`] = {
      subtitle: nom === "explosion" ? "subtitles.cabane.arme.explosion" : nom === "lointain" ? "subtitles.cabane.arme.lointain" : "subtitles.cabane.arme.tir",
      sounds: [{ name: `cabane:arme/${nom}`, attenuation_distance: nom === "explosion" ? 96 : nom === "lointain" ? 16 : 64 }],
    };
  }
  return sons;
}

// ---------------------------------------------------------------- la dimension
const TYPE_DIMENSION = {
  ultrawarm: false, natural: false, piglin_safe: false, respawn_anchor_works: false, bed_works: false, has_raids: false,
  has_skylight: true, has_ceiling: false, coordinate_scale: 1.0, ambient_light: 0.0,
  // toujours la fin d'après-midi : le soleil bas, la lumière dorée des films de narcos
  fixed_time: 10800,
  logical_height: 256, min_y: 0, height: 256, effects: "minecraft:overworld", infiniburn: "#minecraft:infiniburn_overworld",
  monster_spawn_light_level: 0, monster_spawn_block_light_limit: 0,
};
// un désert de sable rouge à perte de vue ; le cartel et sa colline sont construits par le mod au milieu
const DIMENSION = {
  type: "cabane:cartel",
  generator: {
    type: "minecraft:flat",
    settings: {
      biome: "cabane:cartel",
      layers: [{ block: "minecraft:bedrock", height: 1 }, { block: "minecraft:stone", height: 56 }, { block: "minecraft:red_sandstone", height: 4 },
        { block: "minecraft:coarse_dirt", height: 2 }, { block: "minecraft:red_sand", height: 1 }],
      lakes: false, features: false, structure_overrides: [],
    },
  },
};
const BIOME = {
  has_precipitation: false, temperature: 2.0, downfall: 0.0,
  effects: {
    sky_color: 0x86aedb, fog_color: 0xf0c890, water_color: 0x3cc8d2, water_fog_color: 0x1d6a7a, grass_color: 0x9fae4c, foliage_color: 0x7b9f3c,
    // des rafales au loin, de temps en temps, et la musique du désert
    additions_sound: { sound: "cabane:arme.lointain", tick_chance: 0.0035 },
    music: { sound: "minecraft:music.overworld.desert", min_delay: 3000, max_delay: 9000, replace_current_music: true },
  },
  spawners: {}, spawn_costs: {}, carvers: {}, features: [],
};

// ---------------------------------------------------------------- El Patrón : sa boutique et ce qu'il raconte
const DIALOGUE = {
  nom: "El Patrón", couleur: "gold", voix: "", qui: "patron_cartel",
  pages: {
    default: {
      titre: "El Patrón",
      textes: [
        "Bienvenido, amigo. Ici, pas de police, pas de questions. Juste des affaires.",
        "Tout ce que tu as vu sur les stands, c'est ma marchandise. Mais c'est ici, chez moi, qu'on paye.",
        "La Kalach ? Cinq cents dollars. Pour toi, c'est un prix d'ami. Tu es mon ami, non ?",
        "Le bazooka, c'est pour les grandes occasions. Les mariages, les anniversaires…",
        "Mes gars ne te feront rien. Tant que tu payes, tout le monde t'aime ici.",
      ],
      boutons: [{ texte: "Montre-moi tes armes", action: "boutique:patron_cartel" }, { texte: "Comment ça marche ?", vers: "aide" }, { texte: "Adiós" }],
    },
    aide: {
      titre: "El Patrón",
      textes: [
        "Clic droit pour tirer. Les mitraillettes, tu gardes le doigt dessus et ça arrose. Quand c'est vide, R pour recharger : les balles, c'est la maison qui offre. Les fusils et le bazooka se tiennent à deux mains. Le Barrett, accroupis-toi pour viser dans la lunette. La grenade, tu la lances : trois secondes, boum. Le cocktail : tout brûle autour, mais rien ne casse.",
      ],
      boutons: [{ texte: "Montre-moi tes armes", action: "boutique:patron_cartel" }, { texte: "Gracias" }],
    },
  },
};

export function genererCartel({ ecrire, lang }) {
  const IC = icones(), SK = skins();
  for (const a of [...ARMES, ...PROJECTILES]) {
    const ic = IC[a.id], id = a.id;
    ecrire(`assets/cabane/textures/item/${id}.png`, png(64, 64, ic.px));
    const prise = PRISES[PRISE_DE[id] ?? "fusil"];
    const display = {
      ...prise, thirdperson_lefthand: miroir(prise.thirdperson_righthand), firstperson_lefthand: miroir(prise.firstperson_righthand),
      ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
      fixed: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
      head: { rotation: [0, 180, 0], translation: [0, 13, 7], scale: [1, 1, 1] },
    };
    ecrire(`assets/cabane/models/item/${id}_3d.json`, { textures: { t: `cabane:item/${id}`, particle: `cabane:item/${id}` }, gui_light: "front", display, elements: elements(ic) });
    // dans l'inventaire : l'icône à plat ; partout ailleurs (en main, par terre, sur les stands) : le modèle 3D
    ecrire(`assets/cabane/models/item/${id}.json`, {
      loader: "neoforge:separate_transforms",
      base: { parent: `cabane:item/${id}_3d` },
      perspectives: { gui: { parent: "minecraft:item/generated", textures: { layer0: `cabane:item/${id}` } } },
    });
  }
  for (const [id, sk] of Object.entries(SK)) ecrire(`assets/cabane/textures/entity/cartel/${id}.png`, png(64, 64, sk.px));

  ecrire("data/cabane/dimension_type/cartel.json", TYPE_DIMENSION);
  ecrire("data/cabane/dimension/cartel.json", DIMENSION);
  ecrire("data/cabane/worldgen/biome/cartel.json", BIOME);
  ecrire("data/cabane/damage_type/balle.json", { message_id: "cabane.balle", exhaustion: 0.1, scaling: "when_caused_by_living_non_player" });

  // la boutique d'El Patrón (il faut être à côté de lui)
  ARMES.forEach((a, i) => ecrire(`data/cabane/boutique/cartel_${a.id}.json`, { item: `cabane:${a.id}`, nombre: 1, prix: a.prix, ordre: 500 + i, vendeur: "patron_cartel" }));
  ecrire("data/cabane/dialogues/patron_cartel.json", DIALOGUE);

  // le bouton du retour : le même dessin que celui du labo
  ecrire("assets/cabane/blockstates/bouton_cartel.json", { variants: { "": { model: "cabane:block/bouton_retour" } } });
  ecrire("assets/cabane/models/item/bouton_cartel.json", { parent: "cabane:block/bouton_retour" });

  for (const a of ARMES) lang[`item.cabane.${a.id}`] = a.nom;
  Object.assign(lang, {
    "item.cabane.roquette": "Roquette", "item.cabane.obus": "Obus de 40 mm",
    "entity.cabane.cartel_pnj": "Gars du cartel", "entity.cabane.projectile_arme": "Projectile",
    "block.cabane.bouton_cartel": "Bouton de retour du cartel", "biome.cabane.cartel": "Le Cartel",
    "key.cabane.recharger": "Recharger l'arme",
    "subtitles.cabane.arme.tir": "Coup de feu", "subtitles.cabane.arme.explosion": "Explosion", "subtitles.cabane.arme.lointain": "Rafales au loin",
    "death.attack.cabane.balle": "%1$s a été criblé de balles par %2$s",
    "death.attack.cabane.balle.item": "%1$s a été criblé de balles par %2$s avec %3$s",
    "death.attack.cabane.balle.player": "%1$s a pris une balle perdue",
  });
  return Object.keys(GARS).length;
}
