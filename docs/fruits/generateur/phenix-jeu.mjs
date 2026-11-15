// Les fichiers du fruit du phénix pour le jeu (mod-cabane), tirés des mêmes dessins que les planches (phenix.mjs) :
// - le fruit lui-même (tori_tori_no_mi) : l'icône 64×64, le fruit posé en 3D (sa peau, sa tige en flamme orange), ses
//   états, son butin ;
// - le plumage qui brille, en disposition de skin (64×64, transparent là où il ne couvre pas) : l'avant-bras droit
//   (niveau 1), la moitié droite (niveau 2), tout le corps (niveau 3) ;
// - des pièces portées, face par face, rangées par partie du joueur (lues par ModeleVent) : la petite aile de
//   l'avant-bras (bras1), la crête et les yeux (crete), les deux serres (serres) ;
// - chaque aile, autour de son attache dans le dos (aile2_d, aile2_g, aile3_d, aile3_g) : le jeu les fait battre ;
// - des objets dans leur propre repère (y vers le haut, l'avant vers +z, en pixels) : le grand phénix (oiseau), une
//   plume de feu (plume), l'œuf de flammes de la renaissance (oeuf, oeuf_fele).
// Tout brille : ce sont des flammes.
// node phenix-jeu.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, norme, croix } from "./moteur.mjs";
import { sous, ID } from "./rendu.mjs";
import { oiseau, aile, serre, crete, yeux, plume, oeuf, flammeB, plumesT } from "./phenix.mjs";
import { icone, peau, MODELE, VRILLE, TIGE } from "./objets.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(ICI, "..", "..", "..", "mod-cabane", "src", "main", "resources");
const ASSETS = path.join(RES, "assets", "cabane"), DATA = path.join(RES, "data", "cabane");

// ------------------------------------------------------------------ les faces, enregistrées au lieu d'être dessinées
const PARTIES_JOUEUR = ["tete", "corps", "brasD", "brasG", "jambeD", "jambeG"];
/** Les pivots des parties dans le modèle du jeu (comme PlayerModel) : x, et y compté depuis le cou vers le bas. */
const PIVOT = { tete: [0, 0], corps: [0, 0], brasD: [-5, 2], brasG: [5, 2], jambeD: [-1.9, 12], jambeG: [1.9, 12] };

/** Une fausse scène qui garde chaque face (sorte 1 : pleine et qui brille). */
function enregistrer(dessin) {
  const faces = [];
  let partie = null;
  const sc = {
    oeil: norme([0.25, 0.35, 1]),
    voiles: [],
    lumiere: () => 1,
    dirVue: (n) => n,
    voile() {},
    lueur() {},
    quad(coins, c, decale, aile, info = {}) {
      const n = info.normale ?? norme(croix(sous(coins[1], coins[0]), sous(coins[2], coins[0])));
      faces.push({ coins: coins.map((p) => [...p]), c: c.slice(0, 3), sorte: 1, n, partie });
    },
  };
  const T = { k: 1, ...Object.fromEntries(PARTIES_JOUEUR.map((nom) => [nom, { p: (v) => { partie = nom; return v; }, n: (v) => norme(v) }])) };
  dessin(sc, T);
  return faces;
}

/** Du repère des planches (y vers le haut, l'avant vers +z) à celui de la partie dans le jeu (y vers le bas, l'avant vers -z). */
function versJeu(f) {
  const [px, py] = PIVOT[f.partie ?? "corps"];
  return { ...f, coins: f.coins.map(([x, y, z]) => [x - px, 24 - y - py, -z]), n: [f.n[0], -f.n[1], -f.n[2]] };
}

/** Le fichier, comme le lit ModeleVent : les groupes, et pour chaque face ses 4 coins, sa normale, sa couleur, sa sorte. */
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
      dv.setUint8(o, f.sorte); o += 1;
    }
  }
  fs.mkdirSync(path.dirname(fichier), { recursive: true });
  fs.writeFileSync(fichier, Buffer.from(dv.buffer));
  return groupes.map((g) => g.length);
}

/** Une pièce portée : ses faces, un groupe par partie du joueur. */
function portee(nom, dessin) {
  const faces = enregistrer(dessin).map((f) => versJeu({ ...f, partie: f.partie ?? "corps" }));
  console.log(nom, ecrireMaillages(path.join(ASSETS, "phenix", `${nom}.bin`), PARTIES_JOUEUR.map((p) => faces.filter((f) => f.partie === p))));
}

/** Un objet libre, dans son propre repère (celui des planches : y en haut, l'avant vers +z, en pixels). */
function libre(nom, dessin) {
  console.log(nom, ecrireMaillages(path.join(ASSETS, "phenix", `${nom}.bin`), [enregistrer(dessin)]));
}

/** Une aile, autour de son attache (l'origine), déjà dans le sens du jeu (y vers le bas, l'avant vers -z). */
function attachee(nom, dessin) {
  const faces = enregistrer(dessin).map((f) => ({ ...f, coins: f.coins.map(([x, y, z]) => [x, -y, -z]), n: [f.n[0], -f.n[1], -f.n[2]] }));
  console.log(nom, ecrireMaillages(path.join(ASSETS, "phenix", `${nom}.bin`), [faces]));
}

// ------------------------------------------------------------------ les pièces portées
// niveau 1 : la petite aile qui pend sous l'avant-bras droit (comme sur la planche), et ses flammèches
portee("bras1", (sc, T) => {
  const B = T.brasD;
  for (let q = 0; q < 9; q++) {
    const y = 11.5 + q * 1.3;
    plume(sc, B.p([-8.2, y, -1.6]), B.n([-0.45, -0.35 - q * 0.04, -1]), B.n([1, 0, 0]), 10 + (8 - q) * 1.2, 3.2, q + 300, { jaune: 0.4 });
    plume(sc, B.p([-8.4, y, -0.6]), B.n([-0.45, -0.2, -1]), B.n([1, 0, 0]), 5 + (8 - q) * 0.6, 2.6, q + 330);
  }
  for (let q = 0; q < 5; q++) flammeB(sc, B.p([-8.6, 12 + q * 2, 0.5]), B.n([-0.3, 1, 0]), 2.4 + q * 0.5, 1.3);
});
portee("crete", (sc, T) => {
  crete(sc, T.tete);
  yeux(sc, T.tete);
});
portee("serres", (sc, T) => {
  serre(sc, T.jambeD, "D", 3.8);
  serre(sc, T.jambeG, "G", 3.8);
});
// les ailes : niveau 2 (24 de long), niveau 3 (40, plus à plat) ; s = -1 : son aile droite
for (const [n, W, o] of [[2, 24, {}], [3, 40, { leve: 0.6 }]]) {
  for (const [s, cote] of [[-1, "d"], [1, "g"]]) attachee(`aile${n}_${cote}`, (sc) => aile(sc, ID, s, W, o));
}

// ------------------------------------------------------------------ les objets libres
libre("oiseau", (sc) => oiseau(sc, ID, 1, { pas: 1.6 }));
libre("plume", (sc) => {
  plume(sc, [0, 0, -7], [0, 0, 1], [0, 1, 0], 14, 4.2, 3, { jaune: 1 });
  plume(sc, [0, 0.1, -6], [0, 0, 1], [1, 0, 0], 12, 3, 5, { jaune: 0 });
  flammeB(sc, [0, 0, -7], [0, 0.1, -1], 7, 2.2);
});
libre("oeuf", (sc) => oeuf(sc, [0, 0, 0], 1));
libre("oeuf_fele", (sc) => oeuf(sc, [0, 0, 0], 1, { fissures: true }));

// ------------------------------------------------------------------ le plumage (disposition de skin)
const N = 64;
/** Les zones de la moitié droite (x0, y0, x1, y1) : sa tête, son corps, son bras droit, sa jambe droite. */
const MOITIE = [
  [0, 8, 8, 16], [8, 8, 12, 16], [8, 0, 12, 8], [16, 0, 20, 8], [28, 8, 32, 16],
  [16, 20, 20, 32], [20, 20, 24, 32], [36, 20, 40, 32], [20, 16, 24, 20], [28, 16, 32, 20],
  [40, 16, 56, 32], [0, 16, 16, 32],
];
/** L'avant-bras droit et la main (le bas des faces du bras, et le dessous). */
const BRAS = [[40, 26, 56, 32], [48, 16, 52, 20]];
const dans = (zones, x, y) => zones.some(([x0, y0, x1, y1]) => x >= x0 && x < x1 && y >= y0 && y < y1);
const OEIL = [232, 255, 255];
function plumage(garde, yeuxGardes) {
  const px = Buffer.alloc(N * N * 4);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    if (!garde(x, y)) continue;
    let c = plumesT(x, y, 0).slice(0, 3);
    if (y === 12 && yeuxGardes.includes(x)) c = OEIL;
    px.set([...c.map((v) => Math.round(v)), 255], (y * N + x) * 4);
  }
  return px;
}
const TEX = path.join(ASSETS, "textures", "pouvoir");
fs.mkdirSync(TEX, { recursive: true });
ecrirePng(path.join(TEX, "phenix_bras.png"), N, N, plumage((x, y) => dans(BRAS, x, y), []));
ecrirePng(path.join(TEX, "phenix_moitie.png"), N, N, plumage((x, y) => dans(MOITIE, x, y), [9, 10]));
ecrirePng(path.join(TEX, "phenix_plumes.png"), N, N, plumage(() => true, [9, 10, 13, 14]));
console.log("plumages ok");

// ------------------------------------------------------------------ le fruit : Tori Tori no Mi
// les mêmes tourbillons que les deux autres fruits, en cyan aux traits dorés ; la tige est une flamme orange
const PALETTE = { contour: [14, 92, 160], fonce: [26, 146, 212], ligne: [255, 176, 40], ombre: [72, 214, 240], milieu: [176, 248, 252], clair: [244, 255, 255] };
const ORANGE = { contour: [130, 44, 10], fonce: [232, 98, 20], milieu: [255, 152, 40], clair: [255, 222, 120] };
const ic = icone(PALETTE, 37);
// la tige de l'icône : du vert des autres fruits à l'orange d'une flamme
for (let i = 0; i < N * N; i++) {
  const c = [ic.px[i * 4], ic.px[i * 4 + 1], ic.px[i * 4 + 2]];
  for (const nom of ["contour", "fonce", "milieu", "clair"]) {
    if (TIGE[nom] && TIGE[nom].every((v, k) => v === c[k])) ic.px.set(ORANGE[nom], i * 4);
  }
}
const pe = peau(PALETTE, 38);
const ID_FRUIT = "tori_tori_no_mi";
const json = (fichier, contenu) => { fs.mkdirSync(path.dirname(fichier), { recursive: true }); fs.writeFileSync(fichier, JSON.stringify(contenu, null, 2) + "\n"); };
const png = (fichier, w, h, px) => { fs.mkdirSync(path.dirname(fichier), { recursive: true }); ecrirePng(fichier, w, h, px); };
const arrondi = (v) => Math.round(v * 1000) / 1000;
png(path.join(ASSETS, "textures", "item", ID_FRUIT + ".png"), 64, 64, ic.px);
png(path.join(ASSETS, "textures", "block", ID_FRUIT + ".png"), 64, 64, pe.px);
png(path.join(ICI, "..", ID_FRUIT + ".png"), 64, 64, ic.px);
const tige = Buffer.alloc(16 * 16 * 4);
for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
  const c = x % 4 === 0 ? ORANGE.fonce : x % 4 === 1 ? ORANGE.clair : ORANGE.milieu;
  tige.set([...c, 255], (y * 16 + x) * 4);
}
png(path.join(ASSETS, "textures", "block", "tori_tige.png"), 16, 16, tige);
json(path.join(ASSETS, "models", "item", ID_FRUIT + ".json"), { parent: "minecraft:item/generated", textures: { layer0: "cabane:item/" + ID_FRUIT } });
const faces = (t) => Object.fromEntries(["north", "south", "east", "west", "up", "down"].map((f) => [f, { texture: t }]));
json(path.join(ASSETS, "models", "block", ID_FRUIT + ".json"), {
  textures: { peau: "cabane:block/" + ID_FRUIT, tige: "cabane:block/tori_tige", particle: "cabane:block/" + ID_FRUIT },
  elements: [
    ...MODELE.map(([a, b]) => ({ from: a.map(arrondi), to: b.map(arrondi), faces: faces("#peau") })),
    ...VRILLE.map(([a, b]) => ({ from: a.map(arrondi), to: b.map(arrondi), faces: faces("#tige") })),
  ],
});
json(path.join(ASSETS, "blockstates", ID_FRUIT + ".json"), { variants: Object.fromEntries(
  [["north", 0], ["east", 90], ["south", 180], ["west", 270]].map(([f, y]) => ["facing=" + f, y ? { model: "cabane:block/" + ID_FRUIT, y } : { model: "cabane:block/" + ID_FRUIT }])) });
json(path.join(DATA, "loot_table", "blocks", ID_FRUIT + ".json"), { type: "minecraft:block", pools: [{ rolls: 1,
  entries: [{ type: "minecraft:item", name: "cabane:" + ID_FRUIT }], conditions: [{ condition: "minecraft:survives_explosion" }] }] });
console.log("fruit ok");
