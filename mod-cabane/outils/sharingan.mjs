// Le Sharingan (pouvoir/Sharingan.java, docs/sharingan) : ses textures, la dimension du genjutsu, ses sons et ses textes.
// Les motifs viennent des mêmes calculs que les aperçus validés (docs/sharingan/generateur/yeux.mjs) :
//   textures/sharingan/pupilles.png : le motif dans le pixel de la pupille du skin, 16 × 16, image par image (12 par seconde)
//     pour les trois éveils à la suite (EVEIL_IMAGES images chacun), rangées par 16 ;
//   textures/sharingan/iris.png : le même en 48 × 48, rond (pour les deux grands yeux qu'on voit à l'écran) ;
//   textures/sharingan/oeil_blanc.png, oeil_masque.png, oeil_traits.png, oeil_halo.png : les calques d'un grand œil (le coin
//     extérieur à droite), dessinés l'un sur l'autre par client/pouvoir/ClientSharingan.
// La dimension cabane:tsukuyomi : vide, construite par le mod (Genjutsu.java), un ciel rouge dessiné par le client.
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";
import { iris, etat, N } from "../../docs/sharingan/generateur/yeux.mjs";

/** Le nombre d'images de chaque éveil (12 par seconde) : les mêmes nombres sont dans ClientSharingan.IMAGES. */
export const EVEIL_IMAGES = [62, 73, 78];
const PAR_SECONDE = 12;
const COLONNES = 16;

function pngRgba(w, h, rgba) {
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) rgba.copy(brut, y * (1 + w * 4) + 1, y * w * 4, (y + 1) * w * 4);
  const t = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const x of buf) c = t[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

/** Toutes les images, dans l'ordre : [éveil, t]. */
function images() {
  const l = [];
  EVEIL_IMAGES.forEach((n, k) => { for (let i = 0; i < n; i++) l.push([k + 1, i / PAR_SECONDE]); });
  return l;
}

/** La pupille du skin : le motif qui remplit tout le carré (sans bord noir), réduit de 48 à 16. */
function pupille(e) {
  const tex = iris({ ...e, plein: true }), k = N / 16, out = [];
  for (let j = 0; j < 16; j++) for (let i = 0; i < 16; i++) {
    let r = 0, g = 0, b = 0, a = 0;
    for (let y = 0; y < k; y++) for (let x = 0; x < k; x++) {
      const q = ((j * k + y) * N + i * k + x) * 4;
      if (!tex[q + 3]) continue;
      r += tex[q]; g += tex[q + 1]; b += tex[q + 2]; a++;
    }
    out.push(a ? [r / a, g / a, b / a, 255] : [20, 4, 8, 255]);
  }
  return out;
}

/** L'iris rond de 48 × 48, avec son reflet blanc en haut à gauche ; transparent autour. */
function irisRond(e) {
  const tex = iris(e), out = [];
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const q = (j * N + i) * 4;
    if (!tex[q + 3]) { out.push([0, 0, 0, 0]); continue; }
    const u = ((i + 0.5) / N) * 2 - 1, v = ((j + 0.5) / N) * 2 - 1;
    let c = [tex[q], tex[q + 1], tex[q + 2]];
    if (Math.hypot(u + 0.38, v + 0.42) < 0.11) c = c.map((x) => x + (255 - x) * 0.85);
    out.push([...c, 255]);
  }
  return out;
}

function atlas(cote, dessin) {
  const l = images(), lignes = Math.ceil(l.length / COLONNES), W = COLONNES * cote, H = lignes * cote;
  const img = Buffer.alloc(W * H * 4);
  l.forEach(([n, t], k) => {
    const px = dessin(etat(n, t)), x0 = (k % COLONNES) * cote, y0 = Math.floor(k / COLONNES) * cote;
    px.forEach((c, i) => img.set(c.map((v) => Math.max(0, Math.min(255, Math.round(v)))), ((y0 + Math.floor(i / cote)) * W + x0 + (i % cote)) * 4));
  });
  return pngRgba(W, H, img);
}

// ------------------------------------------------------------------ les calques du grand œil (comme yeux.mjs, à l'échelle 540)
const A = 132, HT = 50, HB = 36;
const CW = 360, CH = 220, CX = 180, CY = 110;
const borne = (u) => Math.max(0, Math.min(1, u));
const haut = (q) => -HT * Math.max(0, 1 - q * q) ** 0.72 - 7 * q;
const bas = (q) => HB * Math.max(0, 1 - q * q) ** 0.95 - 4 * q;

// miroir : l'œil de gauche de l'écran (le coin extérieur à gauche), déjà retourné (le jeu ne dessine pas une image retournée)
let miroir = false;
function calque(f) {
  const img = Buffer.alloc(CW * CH * 4);
  for (let y = 0; y < CH; y++) for (let x = 0; x < CW; x++) {
    const lx = (x + 0.5 - CX) * (miroir ? -1 : 1), ly = y + 0.5 - CY, nx = lx / A;
    const c = f(lx, ly, nx);
    if (c) img.set(c.map((v) => Math.max(0, Math.min(255, Math.round(v)))), (y * CW + x) * 4);
  }
  return pngRgba(CW, CH, img);
}
const dedans = (ly, nx) => Math.abs(nx) < 1 && ly > haut(nx) && ly < bas(nx);
const bordure = (ly, nx) => borne(Math.min(ly - haut(nx), bas(nx) - ly) / 1.3);

const calques = {
  // le blanc de l'œil, avec l'ombre de la paupière en haut et les coins plus gris
  oeil_blanc: () => calque((lx, ly, nx) => {
    if (!dedans(ly, nx)) return null;
    const k = Math.exp(-(ly - haut(nx)) / 9) * 0.8, coin = borne(Math.abs(nx) - 0.6) * 0.6;
    const s = [226, 226, 232].map((v, i) => { const a = v + ([150, 150, 165][i] - v) * k; return a + ([180, 180, 192][i] - a) * coin; });
    return [...s, 255 * bordure(ly, nx)];
  }),
  // noir hors de l'amande (pour couper l'iris qui dépasse), transparent dedans
  oeil_masque: () => calque((lx, ly, nx) => [3, 2, 4, 255 * (1 - (dedans(ly, nx) ? bordure(ly, nx) : 0))]),
  // l'ombre de la paupière sur l'iris, les traits des paupières (épais en haut, avec la pointe du coin extérieur)
  oeil_traits: () => calque((lx, ly, nx) => {
    let a = 0;
    if (dedans(ly, nx)) a = Math.exp(-(ly - haut(nx)) / 10) * 0.55 * bordure(ly, nx);
    if (nx > -1.05 && nx < 1.32) {
      let yl, ep;
      if (nx <= 1) { yl = haut(Math.max(-1, nx)); ep = 3 + 7 * Math.sin(Math.PI * (Math.max(-1, nx) + 1) / 2.4); }
      else { yl = haut(1) - (nx - 1) * A * 0.32; ep = 6 * (1 - (nx - 1) / 0.32); }
      const dl = Math.abs(ly - (yl - ep * 0.4));
      if (dl < ep) a = Math.max(a, borne(ep - dl));
    }
    if (Math.abs(nx) < 1) {
      const dl = Math.abs(ly - bas(nx) - 1), ep = 1.6 * borne((nx + 0.7) / 0.6);
      if (dl < ep) a = Math.max(a, borne(ep - dl) * 0.9);
    }
    return a > 0 ? [8, 8, 10, 255 * a] : null;
  }),
  // le halo blanc autour de l'œil (teinté par le jeu de la couleur de l'éveil)
  oeil_halo: () => calque((lx, ly, nx) => {
    // seulement autour de l'œil : dedans, c'est le blanc et l'iris qu'on voit
    const g = Math.max(0, 1 - Math.hypot(nx / 1.22, (ly + 4) / (HT * 1.9))) ** 1.6 * (dedans(ly, nx) ? 1 - bordure(ly, nx) : 1);
    return g > 0 ? [255, 255, 255, 255 * g * 0.55] : null;
  }),
};

// ------------------------------------------------------------------ la dimension du genjutsu
const TYPE_DIMENSION = {
  ultrawarm: false, natural: false, piglin_safe: false, respawn_anchor_works: false, bed_works: false, has_raids: false,
  has_skylight: false, has_ceiling: false, coordinate_scale: 1.0,
  // tout se voit bien : le sol noir et rouge, la croix, les clones
  ambient_light: 0.7, fixed_time: 18000, logical_height: 256, min_y: 0, height: 256,
  // le ciel rouge et la lune noire : dessinés par le client (ClientGenjutsu)
  effects: "cabane:tsukuyomi", infiniburn: "#minecraft:infiniburn_overworld",
  monster_spawn_light_level: 0, monster_spawn_block_light_limit: 0,
};
const DIMENSION = {
  type: "cabane:tsukuyomi",
  generator: { type: "minecraft:flat", settings: { biome: "cabane:tsukuyomi", layers: [], lakes: false, features: false, structure_overrides: [] } },
};
const BIOME = {
  has_precipitation: false, temperature: 0.5, downfall: 0.0,
  effects: {
    sky_color: 0xa0101a, fog_color: 0x8a0c14, water_color: 0x600000, water_fog_color: 0x300000, grass_color: 0x300808, foliage_color: 0x300808,
    ambient_sound: "minecraft:ambient.crimson_forest.loop",
    mood_sound: { sound: "minecraft:ambient.cave", tick_delay: 6000, block_search_extent: 8, offset: 2.0 },
    particle: { options: { type: "minecraft:crimson_spore" }, probability: 0.004 },
  },
  spawners: {}, spawn_costs: {}, carvers: {}, features: [],
};

// ------------------------------------------------------------------ la dimension du Kamui (de grands blocs de pierre dans le vide gris)
const TYPE_KAMUI = { ...TYPE_DIMENSION, ambient_light: 0.6, effects: "cabane:kamui" };
const DIMENSION_KAMUI = {
  type: "cabane:kamui",
  generator: { type: "minecraft:flat", settings: { biome: "cabane:kamui", layers: [], lakes: false, features: false, structure_overrides: [] } },
};
const BIOME_KAMUI = {
  has_precipitation: false, temperature: 0.5, downfall: 0.0,
  effects: {
    sky_color: 0x8a96a0, fog_color: 0x7d8a94, water_color: 0x404850, water_fog_color: 0x202428, grass_color: 0x707070, foliage_color: 0x707070,
    ambient_sound: "minecraft:ambient.basalt_deltas.loop",
    mood_sound: { sound: "minecraft:ambient.cave", tick_delay: 6000, block_search_extent: 8, offset: 2.0 },
    particle: { options: { type: "minecraft:white_ash" }, probability: 0.003 },
  },
  spawners: {}, spawn_costs: {}, carvers: {}, features: [],
};

export function genererSharingan({ ecrire, lang }) {
  ecrire("data/cabane/dimension_type/kamui.json", TYPE_KAMUI);
  ecrire("data/cabane/dimension/kamui.json", DIMENSION_KAMUI);
  ecrire("data/cabane/worldgen/biome/kamui.json", BIOME_KAMUI);
  ecrire("assets/cabane/textures/sharingan/pupilles.png", atlas(16, pupille));
  ecrire("assets/cabane/textures/sharingan/iris.png", atlas(N, irisRond));
  for (const [nom, f] of Object.entries(calques)) {
    miroir = false;
    ecrire(`assets/cabane/textures/sharingan/${nom}.png`, f());
    miroir = true;
    ecrire(`assets/cabane/textures/sharingan/${nom}_g.png`, f());
  }
  miroir = false;
  ecrire("data/cabane/dimension_type/tsukuyomi.json", TYPE_DIMENSION);
  ecrire("data/cabane/dimension/tsukuyomi.json", DIMENSION);
  ecrire("data/cabane/worldgen/biome/tsukuyomi.json", BIOME);
  ecrire("data/cabane/damage_type/tsukuyomi.json", { message_id: "cabane.tsukuyomi", exhaustion: 0.1, scaling: "never" });
  ecrire("data/cabane/damage_type/meteorite.json", { message_id: "cabane.meteorite", exhaustion: 0.1, scaling: "never" });
  Object.assign(lang, {
    "key.cabane.technique": "Technique (pouvoir) : Susano, kunai de Minato", "key.cabane.kamui": "Kamui (Sharingan)", "key.cabane.meteorite": "Météorite (Rinnegan)",
    "subtitles.cabane.sharingan.eveil": "Le Sharingan s'éveille", "subtitles.cabane.sharingan.mangekyo": "Le Mangekyo s'éveille",
    "death.attack.cabane.tsukuyomi": "%1$s n'est jamais revenu du Tsukuyomi de %2$s",
    "death.attack.cabane.tsukuyomi.item": "%1$s n'est jamais revenu du Tsukuyomi de %2$s",
    "death.attack.cabane.tsukuyomi.player": "%1$s n'est jamais revenu d'un Tsukuyomi",
    "death.attack.cabane.meteorite": "%1$s a été écrasé par la météorite de %2$s",
    "death.attack.cabane.meteorite.item": "%1$s a été écrasé par la météorite de %2$s",
    "death.attack.cabane.meteorite.player": "%1$s a été écrasé par une météorite",
  });
}

/**
 * Les sons de l'éveil : par défaut, des sons de Minecraft (pris dans le jeu) ; les vrais (extraits de l'anime) ne sont que
 * dans le pack privé (voix/effets/sharingan.eveil.ogg et sharingan.mangekyo.ogg), jamais sur GitHub.
 */
export function sonsSharingan() {
  return {
    "sharingan.eveil": { subtitle: "subtitles.cabane.sharingan.eveil", sounds: [{ name: "minecraft:block.beacon.power_select", type: "event", pitch: 0.6, volume: 0.6 }] },
    "sharingan.mangekyo": { subtitle: "subtitles.cabane.sharingan.mangekyo", sounds: [{ name: "minecraft:entity.warden.sonic_charge", type: "event", volume: 0.5 }] },
  };
}

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) console.log("à lancer par generer.mjs");
