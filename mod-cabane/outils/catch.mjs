// Le catch : le corps gonflé des catcheurs (le modèle du jeu, repris pavé par pavé des maquettes de docs/catch), les
// 5 tenues et les 5 masques (leurs textures et leurs icônes 64×64), les blocs du ring (tapis, tablier et ses lettres,
// poteaux, cordes de 5 couleurs, marches), la salle (public, écran géant, led, projecteurs, cloche, bouton du retour),
// la dimension cabane:catch, et les bruits (la cloche, le choc sur le tapis), synthétisés ici. Plus de bruit de public :
// l'hôte l'a trouvé horrible. Appelé par generer.mjs.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { catcheur, TENUES, MASQUES, SKINS } from "../../docs/catch/generateur/catcheur.mjs";
import { scene, corps, LUMIERE, hache, POLICE } from "../../docs/catch/generateur/moteur.mjs";
import { Monde, ring, rendreMonde } from "../../docs/catch/generateur/salle.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const RACINE = path.join(ICI, "..", "..");

/** Une image RGBA en PNG. */
function png(w, h, rgba) {
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) rgba.copy(brut, y * (1 + w * 4) + 1, y * w * 4, (y + 1) * w * 4);
  const t = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const x of buf) c = t[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}
const image = (w, h) => ({ w, h, px: Buffer.alloc(w * h * 4) });
const poser = (im, x, y, c, a = 255) => { if (x >= 0 && y >= 0 && x < im.w && y < im.h) im.px.set([...c.map((v) => Math.max(0, Math.min(255, Math.round(v)))), a], (y * im.w + x) * 4); };
const peindre = (w, h, f) => { const im = image(w, h); for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const c = f(x, y); if (c) poser(im, x, y, c, c[3] ?? 255); } return im; };
const bruit = (c, x, y, s, f = 0.06) => c.map((v) => v * (1 - f + 2 * f * hache(x, y, s)));

// ---------------------------------------------------------------- les clés du jeu
export const CLES_TENUES = { slipNoir: "slip_noir", slipEtoile: "slip_etoile", shortLuchador: "short_luchador", jean: "jean", collantFlammes: "collant_flammes" };
const NOMS_TENUES = { slipNoir: "le slip noir", slipEtoile: "le slip étoilé", shortLuchador: "le short luchador", jean: "le short en jean", collantFlammes: "le collant à flammes" };
export const CLES_MASQUES = ["mysterio", "vipere", "champion", "demon", "taureau"];
const COULEURS = { rouge: [210, 30, 36], bleu: [40, 80, 220], blanc: [240, 240, 244], or: [246, 200, 60], noir: [34, 34, 40] };
const COUSSINS = { rouge: [200, 30, 36], bleu: [40, 70, 200], blanc: [30, 30, 36], or: [30, 30, 36], noir: [236, 186, 50] };

// ---------------------------------------------------------------- le corps gonflé, pour le jeu
/**
 * Où pivote chaque os (en pixels des maquettes : pieds à 0, l'avant vers +z). Le corps tourne au cou comme celui de
 * Minecraft ; les bras sont 3,4 pixels plus loin du corps et 1 plus haut ; les jambes 0,5 plus écartées
 * (client/lutte/CorpsCatcheur décale les parties du joueur d'autant).
 */
const PIVOTS = { corps: [0, 24, 0], brasD: [-8.4, 23, 0], brasG: [8.4, 23, 0], jambeD: [-2.4, 12, 0], jambeG: [2.4, 12, 0], tete: [0, 24, 0] };
/** Les faces d'un pavé dans sa texture (« box UV » de Minecraft, comme un skin), en unités : [u, v, largeur, hauteur]. */
const FACES_UV = (u, v, w, h, d) => ({
  haut: [u + d, v, w, d], bas: [u + d + w, v, w, d], droite: [u, v + d, d, h], devant: [u + d, v + d, w, h], gauche: [u + d + w, v + d, d, h], dos: [u + 2 * d + w, v + d, w, h],
});

/** Range les pavés dans une texture de `largeur` texels (2 texels par unité), coins pairs ; rend la hauteur. */
function emballer(cubes, largeur) {
  let x = 0, y = 0, haut = 0;
  for (const c of cubes) {
    const W = Math.ceil(4 * (c.w + c.d) / 2) * 2, H = Math.ceil(2 * (c.d + c.h) / 2) * 2;
    if (x + W > largeur) { x = 0; y += haut; haut = 0; }
    c.uv = [x / 2, y / 2];
    x += W;
    haut = Math.max(haut, H);
  }
  return Math.max(2, Math.ceil((y + haut) / 32) * 32);
}

/** Peint un pavé : coul(face, s, t) en (0..1) sur chaque face → [r, g, b] (ou un objet), avec ecrire(X, Y, valeur). */
function peindrePave(c, coul, ecrireTexel) {
  for (const [face, [fu, fv, fw, fh]] of Object.entries(FACES_UV(c.uv[0], c.uv[1], c.w, c.h, c.d))) {
    const X0 = 2 * fu, Y0 = 2 * fv, W = 2 * fw, H = 2 * fh;
    for (let Y = Math.floor(Y0); Y < Math.ceil(Y0 + H); Y++) for (let X = Math.floor(X0); X < Math.ceil(X0 + W); X++) {
      const s = Math.min(0.999, Math.max(0, (X + 0.5 - X0) / W)), t = Math.min(0.999, Math.max(0, (Y + 0.5 - Y0) / H));
      ecrireTexel(X, Y, coul(face, s, t));
    }
  }
}

/** Le pavé dans le repère de son os, en coordonnées du modèle de Minecraft (y vers le bas, l'avant vers -z). */
function versJeu(os, a, b) {
  const P = PIVOTS[os];
  return { os, de: [a[0] - P[0], P[1] - b[1], P[2] - b[2]].map(r4), w: b[0] - a[0], h: b[1] - a[1], d: b[2] - a[2] };
}
const r4 = (v) => Math.round(v * 10000) / 10000;

/** Le corps d'une tenue : les pavés (os, origine, taille, uv) et deux textures : l'ombre des muscles et le tissu. */
function corpsDuJeu(cle) {
  const c = catcheur(SKINS.steve, cle, null, { peauBrute: true });
  const pav = c.parties.filter((p) => p.role === "peau" || p.role === "tenue").map((p) => ({ ...versJeu(p.os, p.a, p.b), partie: p }));
  const L = 256, H = emballer(pav, L);
  const ombre = image(L, H), tissu = image(L, H);
  for (const q of pav) peindrePave(q, (f, s, t) => q.partie.couleur(f, s, t, 0, 0, 1, 1), (X, Y, col) => {
    if (!col) return;
    if (col[0] > 300) poser(ombre, X, Y, col.map((v) => Math.min(255, (v / 1000 / 1.25) * 255)));
    else poser(tissu, X, Y, col);
  });
  return {
    modele: { texture: [L, H], cubes: pav.map(({ os, de, w, h, d, uv }) => ({ os, de, taille: [w, h, d].map(r4), uv })) },
    ombre, tissu,
  };
}

/** Un masque : le pavé de la tête (8 × 8 × 8, gonflé de 0,35, 16 texels par face) et ses morceaux en plus. */
function masqueDuJeu(cle) {
  const M = MASQUES[cle], c = catcheur(SKINS.steve, null, cle);
  const tete = { os: "tete", de: [-4, -8, -4], w: 8, h: 8, d: 8, gonfle: 0.35, uv: [0, 0] };
  const extras = c.parties.filter((p) => p.role === "extra").map((p) => ({ ...versJeu("tete", p.a, p.b), partie: p }));
  // les morceaux en plus sous la tête (32 texels de haut)
  const H = 32 + (extras.length ? emballer(extras, 64) : 0);
  for (const e of extras) e.uv = [e.uv[0], e.uv[1] + 16];
  const im = image(64, H);
  peindrePave(tete, (f, s, t) => M.c(f, Math.floor(s * 16), Math.floor(t * 16)), (X, Y, col) => col && poser(im, X, Y, col.slice(0, 3)));
  for (const e of extras) peindrePave(e, (f, s, t) => e.partie.couleur(f, s, t, 0, 0, 1, 1), (X, Y, col) => col && poser(im, X, Y, col.slice(0, 3)));
  return { modele: { texture: [64, H], cubes: [tete, ...extras].map(({ os, de, w, h, d, uv, gonfle }) => ({ os, de, taille: [w, h, d].map(r4), uv, ...(gonfle ? { gonfle } : {}) })) }, im };
}

// ---------------------------------------------------------------- les icônes 64×64
function icone(dessin, o) {
  LUMIERE.mode = "studio";
  const sc = scene({ w: 64, h: 64, transparent: true, ...o });
  dessin(sc);
  return { w: 64, h: 64, px: sc.img };
}
const iconeTenue = (cle) => icone((sc) => {
  const c = catcheur(SKINS.steve, cle, null);
  corps(sc, c.os, c.parties.filter((p) => p.os.startsWith("jambe") || (p.os === "corps" && p.role === "tenue")), { pos: [0, 12, 0] });
}, { lacet: -24, tangage: 10, echelle: 3.9, centre: [0, 7, 0], pied: 33 });
const iconeMasque = (cle) => icone((sc) => {
  const c = catcheur(SKINS.steve, null, cle);
  corps(sc, c.os, c.parties.filter((p) => p.os === "tete"), { pos: [0, 12, 0] });
}, { lacet: -28, tangage: 12, echelle: 4.6, centre: [0, 29.5, 0], pied: 32 });
function iconeKit() {
  LUMIERE.mode = "studio";
  const m = new Monde([0, 5], [0, 4], [0, 5]);
  ring(m, 0, 0, 5, 5, 0, { couleur: "rouge", mot: "", marches: false });
  const sc = scene({ w: 64, h: 64, transparent: true, lacet: -45, tangage: 32, echelle: 0.62, centre: [2.5 * 16, 1.6 * 16, 2.5 * 16], pied: 34 });
  rendreMonde(sc, m, { n: 4 });
  return { w: 64, h: 64, px: sc.img };
}

// ---------------------------------------------------------------- le public : 16 skins de spectateurs
const PEAUX = [[244, 204, 170], [226, 178, 140], [198, 146, 104], [150, 100, 66], [108, 70, 46], [236, 190, 150]];
const CHEVEUX = [[40, 28, 20], [90, 60, 30], [20, 18, 18], [200, 160, 80], [130, 70, 30], [60, 50, 46], [160, 160, 160]];
const HABITS = [[200, 36, 40], [36, 70, 180], [30, 30, 34], [236, 236, 236], [250, 200, 40], [60, 150, 70], [130, 60, 170], [250, 130, 40], [120, 124, 130], [60, 200, 210], [236, 90, 150]];
const PANTALONS = [[50, 70, 120], [40, 40, 46], [90, 80, 70], [70, 96, 150]];
/** [u, v, largeur, hauteur, profondeur] de chaque partie d'un skin (première couche). */
const ZONES = { tete: [0, 0, 8, 8, 8], corps: [16, 16, 8, 12, 4], brasD: [40, 16, 4, 12, 4], brasG: [32, 48, 4, 12, 4], jambeD: [0, 16, 4, 12, 4], jambeG: [16, 48, 4, 12, 4] };
function skinSpectateur(n) {
  const h = (k) => hache(n, k, 977), pick = (t, k) => t[Math.floor(h(k) * t.length)];
  const peau = pick(PEAUX, 1), cheveux = pick(CHEVEUX, 2), habit = pick(HABITS, 3), pantalon = pick(PANTALONS, 4);
  const masque = n % 8 === 5 ? MASQUES[CLES_MASQUES[n % 5]] : null, imprime = h(7) < 0.4, casquette = h(8) < 0.2;
  const im = image(64, 64);
  for (const [nom, [u, v, w, H, d]] of Object.entries(ZONES)) {
    const faces = { haut: [u + d, v, w, d], bas: [u + d + w, v, w, d], droite: [u, v + d, d, H], devant: [u + d, v + d, w, H], gauche: [u + d + w, v + d, d, H], dos: [u + 2 * d + w, v + d, w, H] };
    for (const [f, [x0, y0, fw, fh]] of Object.entries(faces)) for (let j = 0; j < fh; j++) for (let i = 0; i < fw; i++) {
      let c;
      if (nom === "tete") {
        if (masque) c = masque.c(f, i * 2, j * 2) ?? peau;
        else if (casquette && (f === "haut" || j < 2)) c = habit;
        else if (f === "haut" || f === "dos" || (j < 2 && f !== "bas") || ((f === "droite" || f === "gauche") && j < 5)) c = cheveux;
        else if (f === "devant" && j === 4 && (i === 1 || i === 2 || i === 5 || i === 6)) c = i === 2 || i === 5 ? [30, 30, 40] : [240, 240, 240];
        else if (f === "devant" && j === 6 && i > 2 && i < 5) c = peau.map((x) => x * 0.7);
        else c = peau;
      } else if (nom === "corps") c = imprime && f === "devant" && i > 1 && i < 6 && j > 2 && j < 7 ? [240, 240, 240] : habit;
      else if (nom.startsWith("bras")) c = j < 4 && f !== "bas" ? habit : peau;
      else c = j >= 11 ? [40, 36, 36] : pantalon;
      poser(im, x0 + i, y0 + j, bruit(c.slice(0, 3), x0 + i, y0 + j, n, 0.05));
    }
  }
  return im;
}

// ---------------------------------------------------------------- les textures des blocs (16×16)
const ACIER = (x, y) => ((x + y) % 4 === 0 && (x - y + 16) % 4 === 0 ? [196, 200, 206] : bruit([150, 154, 162], x, y, 17, 0.05));
const TOILE = (x, y) => bruit([226, 224, 216].map((v) => v * ((x % 8 === 0 || y % 8 === 0) ? 0.95 : 1)), x, y, 2, 0.03);
const NOIR = (x, y) => bruit([24, 24, 30], x, y, 3, 0.05);
/** Une lettre (la police 3 × 5) sur la colonne de deux blocs du tablier : moitié haute (tapis) ou basse (tablier). */
function lettre(l, haut) {
  const motif = POLICE[l.toUpperCase()];
  return (x, y) => {
    const Y = haut ? y : y + 16;
    if (haut && y < 2) return [210, 30, 36];
    // la lettre : 3 colonnes de 4 texels (x 2..13), 5 lignes de 5 texels (Y 4..28)
    const cx = Math.floor((x - 2) / 4), cy = Math.floor((Y - 4) / 5);
    if (x >= 2 && x < 14 && Y >= 4 && Y < 29 && cx < 3 && cy < 5 && motif[cy * 3 + cx] === "1") return [240, 240, 244];
    return NOIR(x, y);
  };
}

// ---------------------------------------------------------------- l'écran géant
function logoEcran() {
  const W = 512, H = 256, mot = "CATCH", cols = mot.length * 4 - 1;
  return peindre(W, H, (x, y) => {
    if (x < 6 || y < 6 || x >= W - 6 || y >= H - 6) return [40, 40, 50];
    const t = y / H, scan = Math.floor(y / 2) % 2 ? 0.88 : 1;
    const gx = ((x - 40) / (W - 80)) * cols, gy = ((y - 50) / 130) * 5;
    const encre = (gx, gy) => gx >= 0 && gy >= 0 && gx < cols && gy < 5 && Math.floor(gx) % 4 !== 3 && POLICE[mot[Math.floor(gx / 4)]][Math.floor(gy) * 3 + (Math.floor(gx) % 4)] === "1";
    if (encre(gx, gy)) return [255, 255, 255];
    if (encre(gx - 0.18, gy - 0.12)) return [255, 210, 60];
    if (y > 200 && y < 214) return [255, 220, 90].map((c) => c * (0.7 + 0.3 * Math.sin(x / 12)));
    return [40 + 170 * (1 - t), 20 + 20 * t, 110 + 80 * t].map((c) => c * scan);
  });
}

// ---------------------------------------------------------------- les sons
const SR = 44100;
function alea(graine) { let g = graine; return () => ((g = (g * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff) * 2 - 1; }
function cloche(coups = [0]) {
  const duree = coups[coups.length - 1] + 1.8, n = Math.round(duree * SR), out = new Float32Array(n);
  const partiels = [[1, 1], [2.0, 0.55], [2.76, 0.42], [3.0, 0.3], [5.4, 0.22], [6.8, 0.12]], f0 = 1046;
  for (const t0 of coups) {
    const d0 = Math.round(t0 * SR);
    for (let i = 0; d0 + i < n; i++) {
      const t = i / SR;
      let v = 0;
      for (const [k, a] of partiels) v += Math.sin(2 * Math.PI * f0 * k * t) * a * Math.exp(-t * (1.6 + k * 0.5));
      out[d0 + i] += v * (1 - Math.exp(-t / 0.001));
    }
  }
  return out;
}
function impact() {
  const n = Math.round(0.6 * SR), out = new Float32Array(n), b = alea(5);
  let phase = 0, y = 0;
  for (let i = 0; i < n; i++) {
    const t = i / SR;
    phase += (2 * Math.PI * (45 + 80 * Math.exp(-t / 0.05))) / SR;
    y += 0.08 * (b() - y);
    out[i] = Math.sin(phase) * Math.exp(-t / 0.16) + y * 3 * Math.exp(-t / 0.05);
  }
  return out;
}
const SONS = {
  cloche: () => cloche([0]),
  cloche3: () => cloche([0, 0.3, 0.6]),
  impact: () => impact(),
};
function normaliser(s, niveau = 0.9) {
  let max = 0;
  for (const v of s) max = Math.max(max, Math.abs(v));
  const k = niveau / (max || 1), n = s.length;
  // un petit fondu au début et à la fin, pour ne pas claquer
  return s.map((v, i) => v * k * Math.min(1, i / (0.01 * SR), (n - i) / (0.03 * SR)));
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
/**
 * Les sons du catch pour sounds.json : la cloche et le choc sur le tapis. Les .ogg sont synthétisés une fois (on efface
 * le fichier pour le refaire).
 */
export function sonsCatch(RES) {
  const dossier = path.join(RES, "assets", "cabane", "sounds", "catch");
  fs.mkdirSync(dossier, { recursive: true });
  const ff = ffmpeg();
  for (const [nom, fabrique] of Object.entries(SONS)) {
    const cible = path.join(dossier, nom + ".ogg");
    if (fs.existsSync(cible) || !ff) continue;
    const tmp = path.join(os.tmpdir(), `cabane-catch-${nom}.wav`);
    fs.writeFileSync(tmp, wav(normaliser(fabrique(), 0.9)));
    execFileSync(ff, ["-y", "-loglevel", "error", "-i", tmp, "-c:a", "libvorbis", "-q:a", "4", cible]);
    fs.rmSync(tmp, { force: true });
  }
  const s = (nom, volume, o = {}) => ({ subtitle: `subtitles.cabane.catch.${nom.replace(/3$/, "")}`, sounds: [{ name: `cabane:catch/${nom}`, volume, ...o }] });
  return {
    "catch.cloche": s("cloche", 0.8),
    "catch.cloche3": s("cloche3", 0.8),
    "catch.impact": s("impact", 0.7),
  };
}

// ---------------------------------------------------------------- la dimension
const TYPE_DIMENSION = {
  ultrawarm: false, natural: false, piglin_safe: false, respawn_anchor_works: false, bed_works: false, has_raids: false,
  has_skylight: false, has_ceiling: false, coordinate_scale: 1.0, ambient_light: 0.0, fixed_time: 18000,
  logical_height: 256, min_y: 0, height: 256, effects: "minecraft:the_end", infiniburn: "#minecraft:infiniburn_overworld",
  monster_spawn_light_level: 0, monster_spawn_block_light_limit: 0,
};
const DIMENSION = {
  type: "cabane:catch",
  generator: { type: "minecraft:flat", settings: { biome: "cabane:catch", layers: [], lakes: false, features: false, structure_overrides: [] } },
};
const BIOME = {
  has_precipitation: false, temperature: 0.8, downfall: 0.0,
  effects: { sky_color: 0, fog_color: 0x120e1e, water_color: 0x3f76e4, water_fog_color: 0x050533, grass_color: 0x6fb85a, foliage_color: 0x5aa04a },
  spawners: {}, spawn_costs: {}, carvers: {}, features: [],
};

// ---------------------------------------------------------------- tout écrire
export function genererCatch({ ecrire, lang }) {
  const tex = (chemin, im) => ecrire(`assets/cabane/textures/${chemin}.png`, png(im.w, im.h, im.px));
  const modeles = { tenues: {}, masques: {} };
  // le corps de chaque tenue, et son icône
  for (const [cle, id] of Object.entries(CLES_TENUES)) {
    const c = corpsDuJeu(cle);
    tex(`entity/catch/corps_${id}_ombre`, c.ombre);
    tex(`entity/catch/corps_${id}_tissu`, c.tissu);
    modeles.tenues[id] = c.modele;
    tex(`item/tenue_catch_${id}`, iconeTenue(cle));
    ecrire(`assets/cabane/models/item/tenue_catch_${id}.json`, { parent: "minecraft:item/generated", textures: { layer0: `cabane:item/tenue_catch_${id}` } });
    lang[`item.cabane.tenue_catch_${id}`] = "Tenue de catch : " + NOMS_TENUES[cle];
  }
  // les masques : sur la tête (le dessin de l'objet ne s'y montre pas : le joueur porte le vrai masque en 3D)
  const NOMS = { mysterio: "Mystério", vipere: "Vipère", champion: "Champion", demon: "Démon", taureau: "Taureau" };
  for (const cle of CLES_MASQUES) {
    const m = masqueDuJeu(cle);
    tex(`entity/catch/masque_${cle}`, m.im);
    modeles.masques[cle] = m.modele;
    tex(`item/masque_catch_${cle}`, iconeMasque(cle));
    ecrire(`assets/cabane/models/item/masque_catch_${cle}.json`, {
      parent: "minecraft:item/generated", textures: { layer0: `cabane:item/masque_catch_${cle}` },
      display: { head: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0, 0, 0] } },
    });
    lang[`item.cabane.masque_catch_${cle}`] = "Masque de catch : " + NOMS[cle];
  }
  ecrire("assets/cabane/catch/modeles.json", modeles);
  // le kit de ring
  tex("item/kit_ring", iconeKit());
  ecrire("assets/cabane/models/item/kit_ring.json", { parent: "minecraft:item/generated", textures: { layer0: "cabane:item/kit_ring" } });
  // le public
  for (let n = 0; n < 16; n++) tex(`entity/catch/public_${n}`, skinSpectateur(n));
  tex("entity/catch/ecran", logoEcran());

  // ---- les blocs
  const t16 = (nom, f) => tex(`block/catch/${nom}`, peindre(16, 16, f));
  t16("toile", TOILE); t16("tablier", (x, y) => (y < 2 ? [210, 30, 36] : NOIR(x, y))); t16("tablier_bas", NOIR); t16("acier", ACIER);
  for (const l of ["c", "a", "t", "h"]) { t16(`tablier_${l}`, lettre(l, true)); t16(`tablier_bas_${l}`, lettre(l, false)); }
  for (const [c, rgb] of Object.entries(COULEURS)) {
    t16(`corde_${c}`, (x, y) => bruit(rgb.map((v) => v * ((x + y) % 4 < 2 ? 1 : 0.85)), x, y, 27, 0.03));
    t16(`coussin_${c}`, (x, y) => bruit(COUSSINS[c].map((v) => v * (x === 0 || y === 0 || x === 15 || y === 15 ? 0.8 : 1)), x, y, 22, 0.04));
  }
  t16("led", (x, y) => (y % 4 === 0 ? [40, 24, 80] : x % 4 === 0 ? [90, 44, 210] : [160, 96, 255]));
  t16("projecteur", (x, y) => (x === 0 || y === 0 || x === 15 || y === 15 ? [60, 60, 66] : [255, 250, 228]));
  t16("ecran", (x, y) => (x % 4 === 0 || y % 4 === 0 ? [16, 14, 24] : [40, 22, 70]));
  t16("siege", (x, y) => bruit([160, 34, 42], x, y, 32, 0.04));
  t16("or", (x, y) => bruit([240, 196, 60].map((v) => v * (Math.abs(x - y) < 2 ? 1.12 : 1)), x, y, 34, 0.04));
  t16("bois", (x, y) => bruit([110, 70, 40].map((v) => v * (y % 4 === 3 ? 0.7 : 1)), x, y, 36, 0.05));

  const bs = (nom, variants) => ecrire(`assets/cabane/blockstates/${nom}.json`, { variants });
  const md = (nom, contenu) => ecrire(`assets/cabane/models/block/${nom}.json`, contenu);
  const item = (nom, modele = `cabane:block/${nom}`) => ecrire(`assets/cabane/models/item/${nom}.json`, { parent: modele });
  const F = ["north", "east", "south", "west"];
  const el = (from, to, t, faces = ["north", "east", "south", "west", "up", "down"]) => ({ from, to, faces: Object.fromEntries(faces.map((f) => [f, { texture: t }])) });

  // le tapis (dessus : la toile ; côtés : le tablier avec le liseré rouge et la moitié haute d'une lettre) et le tablier
  for (const l of ["vide", "c", "a", "t", "h"]) {
    const cote = l === "vide" ? "cabane:block/catch/tablier" : `cabane:block/catch/tablier_${l}`, bas = l === "vide" ? "cabane:block/catch/tablier_bas" : `cabane:block/catch/tablier_bas_${l}`;
    md(`tapis_ring_${l}`, { parent: "minecraft:block/cube", textures: { up: "cabane:block/catch/toile", down: "cabane:block/catch/tablier_bas", north: cote, south: cote, east: cote, west: cote, particle: "cabane:block/catch/toile" } });
    md(`tablier_ring_${l}`, { parent: "minecraft:block/cube", textures: { up: "cabane:block/catch/tablier_bas", down: "cabane:block/catch/tablier_bas", north: bas, south: bas, east: bas, west: bas, particle: "cabane:block/catch/tablier_bas" } });
  }
  bs("tapis_ring", Object.fromEntries(["vide", "c", "a", "t", "h"].map((l) => [`lettre=${l}`, { model: `cabane:block/tapis_ring_${l}` }])));
  bs("tablier_ring", Object.fromEntries(["vide", "c", "a", "t", "h"].map((l) => [`lettre=${l}`, { model: `cabane:block/tablier_ring_${l}` }])));
  item("tapis_ring", "cabane:block/tapis_ring_vide"); item("tablier_ring", "cabane:block/tablier_ring_vide");

  // les cordes : trois barres le long du bord nord (à 5 texels du bord), et le poteau du coin nord-ouest
  const H3 = [8.8, 16, 23.2];
  for (const c of Object.keys(COULEURS)) {
    const corde = `cabane:block/catch/corde_${c}`, coussin = `cabane:block/catch/coussin_${c}`;
    md(`corde_ring_${c}`, { textures: { c: corde, particle: corde }, elements: H3.map((h) => el([0, h - 0.8, 4.3], [16, h + 0.8, 5.9], "#c")) });
    md(`poteau_ring_${c}`, {
      textures: { a: "cabane:block/catch/acier", k: coussin, c: corde, particle: "cabane:block/catch/acier" },
      elements: [
        el([2.9, 0, 2.9], [7.3, 28, 7.3], "#a"),
        ...H3.map((h) => el([1.9, h - 2.7, 1.9], [8.3, h + 2.7, 8.3], "#k")),
        ...H3.map((h) => el([8.3, h - 0.8, 4.3], [16, h + 0.8, 5.9], "#c")),
        ...H3.map((h) => el([4.3, h - 0.8, 8.3], [5.9, h + 0.8, 16], "#c")),
      ],
    });
  }
  const tournes = (nom, couleurs) => {
    const v = {};
    F.forEach((f, i) => { for (const c of couleurs) v[`couleur=${c},facing=${f}`] = { model: `cabane:block/${nom}_${c}`, ...(i ? { y: i * 90 } : {}) }; });
    return v;
  };
  bs("corde_ring", tournes("corde_ring", Object.keys(COULEURS)));
  bs("poteau_ring", tournes("poteau_ring", Object.keys(COULEURS)));
  item("corde_ring", "cabane:block/corde_ring_rouge"); item("poteau_ring", "cabane:block/poteau_ring_rouge");
  // les marches d'acier (elles montent vers le nord)
  md("marches_ring", { textures: { a: "cabane:block/catch/acier", particle: "cabane:block/catch/acier" }, elements: [el([0, 0, 0], [16, 16, 8], "#a"), el([0, 0, 8], [16, 8, 16], "#a")] });
  bs("marches_ring", Object.fromEntries(F.map((f, i) => [`facing=${f}`, { model: "cabane:block/marches_ring", ...(i ? { y: i * 90 } : {}) }])));
  item("marches_ring");

  // la cloche de la table, le siège du public, les panneaux qui brillent, le bouton du retour
  md("cloche_catch", { textures: { b: "cabane:block/catch/bois", o: "cabane:block/catch/or", particle: "cabane:block/catch/or" },
    elements: [el([3, 0, 3], [13, 2, 13], "#b"), el([7.25, 2, 7.25], [8.75, 4, 8.75], "#o"), el([5, 4, 5], [11, 9, 11], "#o"), el([6, 9, 6], [10, 10.5, 10], "#o"), el([7.25, 10.5, 7.25], [8.75, 12, 8.75], "#o")] });
  bs("cloche_catch", { "": { model: "cabane:block/cloche_catch" } }); item("cloche_catch");
  md("public_catch", { textures: { s: "cabane:block/catch/siege", particle: "cabane:block/catch/siege" }, elements: [el([2, 5, 3], [14, 7, 13], "#s"), el([2, 7, 12], [14, 15, 14], "#s"), el([7, 0, 6], [9, 5, 9], "#s")] });
  bs("public_catch", Object.fromEntries(F.map((f, i) => [`facing=${f}`, { model: "cabane:block/public_catch", ...(i ? { y: i * 90 } : {}) }]))); item("public_catch");
  for (const n of ["led", "projecteur", "ecran"]) {
    md(`${n}_catch`, { parent: "minecraft:block/cube_all", textures: { all: `cabane:block/catch/${n}` } });
    bs(`${n}_catch`, { "": { model: `cabane:block/${n}_catch` } }); item(`${n}_catch`);
  }
  bs("ecran_logo_catch", { "": { model: "cabane:block/ecran_catch" } }); item("ecran_logo_catch", "cabane:block/ecran_catch");
  bs("bouton_catch", { "": { model: "cabane:block/bouton_retour" } }); item("bouton_catch", "cabane:block/bouton_retour");

  // la dimension, le biome, la prise qui fait mal
  ecrire("data/cabane/dimension_type/catch.json", TYPE_DIMENSION);
  ecrire("data/cabane/dimension/catch.json", DIMENSION);
  ecrire("data/cabane/worldgen/biome/catch.json", BIOME);
  ecrire("data/cabane/damage_type/prise_catch.json", { message_id: "cabane.prise_catch", exhaustion: 0.1, scaling: "never" });

  Object.assign(lang, {
    "item.cabane.kit_ring": "Kit de ring",
    "block.cabane.tapis_ring": "Tapis de ring", "block.cabane.tablier_ring": "Tablier de ring", "block.cabane.poteau_ring": "Poteau de ring",
    "block.cabane.corde_ring": "Cordes du ring", "block.cabane.marches_ring": "Marches du ring", "block.cabane.cloche_catch": "Cloche du catch",
    "block.cabane.public_catch": "Public", "block.cabane.led_catch": "Panneau à led", "block.cabane.projecteur_catch": "Projecteur",
    "block.cabane.ecran_catch": "Écran géant", "block.cabane.ecran_logo_catch": "Écran géant (logo)", "block.cabane.bouton_catch": "Bouton de retour de la salle de catch",
    "biome.cabane.catch": "La salle de catch",
    "subtitles.cabane.catch.cloche": "La cloche", "subtitles.cabane.catch.impact": "Choc sur le tapis",
    "death.attack.cabane.prise_catch": "%1$s a pris la prise de %2$s",
    "death.attack.cabane.prise_catch.player": "%1$s a pris la prise de %2$s",
  });
  return Object.keys(CLES_TENUES).length + CLES_MASQUES.length;
}
