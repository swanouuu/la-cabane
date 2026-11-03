// Les fichiers du niveau 3 des fruits pour le jeu (mod-cabane), tirés des mêmes dessins que les planches (niveau3.mjs) :
// le casque en tête de dragon et les plaques à pics, face par face, rangés par partie du joueur ; la tête du grand
// dragon ; la texture du corps (la lave ou la glace partout, sauf une partie de l'autre bras) et ses étapes quand elle
// gagne le corps.
// node jeu3.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, mix, norme, croix, texels, PARTIES } from "./moteur.mjs";
import { sous, ajoute, fois, ID, magma, couvert, bruit, LAVE_O, GLACE_C, BLANC } from "./rendu.mjs";
import { couvert3, cadre, teteDragon, plaques, glace3 } from "./niveau3.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = path.join(ICI, "..", "..", "..", "mod-cabane", "src", "main", "resources", "assets", "cabane");

// ------------------------------------------------------------------ les faces, enregistrées au lieu d'être dessinées
const PARTIES_JOUEUR = ["tete", "corps", "brasD", "brasG", "jambeD", "jambeG"];
/** Les pivots des parties dans le modèle du jeu (comme PlayerModel) : x, et y compté depuis le cou vers le bas. */
const PIVOT = { tete: [0, 0], corps: [0, 0], brasD: [-5, 2], brasG: [5, 2], jambeD: [-1.9, 12], jambeG: [1.9, 12] };

/**
 * Une fausse scène qui garde chaque face (ses coins, sa couleur sans éclairage, si elle brille, sa normale) au lieu de la
 * dessiner ; et le joueur au repos, dont chaque partie note qu'elle vient de servir (la face qui suit est à elle).
 */
function enregistrer(dessin) {
  const faces = [];
  let partie = null;
  const sc = {
    lumiere: () => 1,
    voile() {},
    lueur() {},
    quad(coins, c, decale, aile, info = {}) {
      let n = info.normale ?? norme(croix(sous(coins[1], coins[0]), sous(coins[2], coins[0])));
      // les côtés d'une pointe : la normale vers le dehors
      if (info.centre) {
        const m = fois(coins.reduce((s, p) => ajoute(s, p), [0, 0, 0]), 0.25);
        const d = sous(m, info.centre);
        if (n[0] * d[0] + n[1] * d[1] + n[2] * d[2] < 0) n = fois(n, -1);
      }
      faces.push({ coins: coins.map((p) => [...p]), c: c.slice(0, 3), emissif: !!info.emissif, n, partie });
    },
  };
  const T = Object.fromEntries(PARTIES_JOUEUR.map((nom) => [nom, { p: (v) => { partie = nom; return v; }, n: (v) => norme(v) }]));
  dessin(sc, T);
  return faces;
}

/** Du repère des planches (y vers le haut, l'avant vers +z) à celui de la partie dans le jeu (y vers le bas, l'avant vers -z). */
function versJeu(f) {
  const [px, py] = PIVOT[f.partie];
  return { ...f, coins: f.coins.map(([x, y, z]) => [x - px, 24 - y - py, -z]), n: [f.n[0], -f.n[1], -f.n[2]] };
}

/**
 * Le fichier : le nombre de groupes, puis pour chacun le nombre de faces, et chaque face : 4 coins (12 flottants), la
 * normale (3), la couleur (r, g, b) et 1 si elle brille. En gros-boutiste, comme le lit DataInputStream.
 */
function ecrireMaillages(fichier, groupes) {
  const taille = 4 + groupes.reduce((s, g) => s + 4 + g.length * (15 * 4 + 4), 0);
  const dv = new DataView(new ArrayBuffer(taille));
  let o = 0;
  dv.setInt32(o, groupes.length); o += 4;
  for (const g of groupes) {
    dv.setInt32(o, g.length); o += 4;
    for (const f of g) {
      for (const p of f.coins) for (const v of p) { dv.setFloat32(o, v); o += 4; }
      for (const v of f.n) { dv.setFloat32(o, v); o += 4; }
      for (const v of f.c) { dv.setUint8(o, Math.max(0, Math.min(255, Math.round(v)))); o += 1; }
      dv.setUint8(o, f.emissif ? 1 : 0); o += 1;
    }
  }
  fs.mkdirSync(path.dirname(fichier), { recursive: true });
  fs.writeFileSync(fichier, Buffer.from(dv.buffer));
  return groupes.map((g) => g.length);
}

// ------------------------------------------------------------------ la texture du corps
const IMAGES = 8, FRONT = 0.1, CHAUD = [255, 244, 190];

/**
 * Le corps au niveau 3 (256×256 sur la disposition d'une peau, quatre pixels par pixel du modèle) : couvert3, la lave
 * en fusion à grandes plaques (comme sur la planche) ou la glace à facettes ; et quand chaque pixel est pris pendant la
 * transformation : ce que tenait déjà le niveau 2 tout de suite, puis le reste, du côté du fruit vers l'autre bras.
 */
function corps3(sorte, s, cote) {
  const N = 256, px = Buffer.alloc(N * N * 4), lueur = Buffer.alloc(N * N * 4), arrivee = new Float32Array(N * N);
  for (const [nom, boite, uv1] of PARTIES(false)) {
    for (const t of texels(boite, uv1, 0, 1)) {
      const pris = couvert3({ sorte }, s, cote, nom, t);
      if (!pris) continue;
      const [x, y, z] = t.centre, deja = couvert({ sorte }, s, cote, nom, t) === 1;
      const quand = deja ? 0 : Math.max(0.05, Math.min(0.999, 0.12 + 0.8 * Math.min(1, Math.max(0, (4 - s * x) / 13)) + 0.08 * bruit(y + z, x - z, 440)));
      for (let dy = 0; dy < 4; dy++) for (let dx = 0; dx < 4; dx++) {
        const X = t.tx * 4 + dx, Y = t.ty * 4 + dy, i = (Y * N + X) * 4;
        arrivee[i >> 2] = quand;
        if (pris === 2) {
          if (sorte === "lave") { px.set([...LAVE_O, 255], i); lueur.set([...LAVE_O, 255], i); }
          else px.set([...mix(GLACE_C, BLANC, 0.5).map(Math.round), 235], i);
          continue;
        }
        if (sorte === "lave") {
          const c = magma(X, Y, 403, 20, 7.6);
          px.set([...c.slice(0, 3).map(Math.round), 255], i);
          if (c[3] === 1) lueur.set([...c.slice(0, 3).map(Math.round), 255], i);
        } else {
          px.set([...glace3(X >> 1, Y >> 1, 404, Math.max(0, Math.min(1, 1 - y / 32))).map(Math.round), 228], i);
        }
      }
    }
  }
  return { px, lueur, arrivee };
}

/** Les images 1 à n-1 de la transformation (comme objets.mjs) : ce qui vient d'être pris passe du front à sa couleur. */
function etapes(base, lueur, arrivee, glace) {
  const images = [];
  for (let k = 1; k < IMAGES; k++) {
    const p = k / IMAGES, px = Buffer.alloc(base.length), lu = Buffer.alloc(base.length);
    for (let i = 0; i < base.length; i += 4) {
      const a = arrivee[i >> 2];
      if (a > p || base[i + 3] === 0) continue;
      const f = (p - a) / FRONT;
      if (f >= 1) {
        base.copy(px, i, i, i + 4);
        if (lueur) lueur.copy(lu, i, i, i + 4);
        continue;
      }
      const c = mix(glace ? BLANC : CHAUD, [base[i], base[i + 1], base[i + 2]], f * f).map(Math.round);
      px.set([...c, glace ? Math.round(255 + (base[i + 3] - 255) * f) : 255], i);
      if (!glace) lu.set([...c, 255], i);
    }
    images.push({ px, lueur: lu });
  }
  return images;
}

// ------------------------------------------------------------------ tout
const png = (fichier, w, h, px) => { fs.mkdirSync(path.dirname(fichier), { recursive: true }); ecrirePng(fichier, w, h, px); };

for (const [sorte, s, cote] of [["lave", 1, "G"], ["glace", -1, "D"]]) {
  const lave = sorte === "lave";
  // le casque : la tête de dragon posée sur sa tête (comme dans niveau3)
  const casque = enregistrer((sc, T) => teteDragon(sc, cadre(T.tete, [0, 31.4, 0.6], [0, 0.04, 1], [0, 1, 0], 1.18), sorte, { casque: true }));
  console.log("casque", sorte, ecrireMaillages(path.join(ASSETS, "fruit3", `casque_${sorte}.bin`), [casque.map(versJeu)]));
  // les plaques : sans armure (0) et par-dessus une armure (1), un groupe par partie
  for (const g of [0, 1]) {
    const faces = enregistrer((sc, T) => plaques(sc, T, lave, g)).map(versJeu);
    console.log("plaques", sorte, g, ecrireMaillages(path.join(ASSETS, "fruit3", `plaques_${sorte}_${g}.bin`), PARTIES_JOUEUR.map((nom) => faces.filter((f) => f.partie === nom))));
  }
  // la tête du grand dragon, gueule ouverte, dans son propre repère (le nez vers +z, environ 13 de long)
  const tete = enregistrer((sc) => teteDragon(sc, cadre(ID, [0, 0, 0], [0, 0, 1], [0, 1, 0], 1), sorte, { gueule: 0.85, ouvert: 0.6, pas: 0.5 }));
  console.log("tete", sorte, ecrireMaillages(path.join(ASSETS, "fruit3", `tete_${sorte}.bin`), [tete]));
  // le corps
  const c = corps3(sorte, s, cote), TEX = path.join(ASSETS, "textures", "entity", "fruit");
  png(path.join(TEX, `moitie3_${sorte}.png`), 256, 256, c.px);
  if (lave) png(path.join(TEX, `moitie3_${sorte}_lueur.png`), 256, 256, c.lueur);
  etapes(c.px, lave ? c.lueur : null, c.arrivee, !lave).forEach((im, k) => {
    png(path.join(TEX, "eveil", `moitie3_${sorte}_${k + 1}.png`), 256, 256, im.px);
    if (lave) png(path.join(TEX, "eveil", `moitie3_${sorte}_lueur_${k + 1}.png`), 256, 256, im.lueur);
  });
}
console.log("ok");
