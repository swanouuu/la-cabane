// Les fichiers des pouvoirs Ténèbres et Lumière pour le jeu (mod-cabane), tirés des mêmes dessins que les planches
// (rendu.mjs), lus par ModeleVent :
// - les pièces portées, face par face, rangées par partie du joueur : la couronne d'obsidienne (Ténèbres 3) ; l'auréole,
//   les deux ailes qui s'ouvrent (Lumière 2), l'armure blanc et or, les six ailes et le soleil du dos (Lumière 3) ;
// - des objets dans leur propre repère (y vers le haut, l'avant vers +z, en pixels) : l'orbe noir, la lame d'ombre, une
//   pique d'ombre, deux maillons de chaîne, la lance et l'épée de lumière, une plume, le chakram.
// Les traits qui brillent deviennent des cœurs (sorte 5 : violet, 6 : or) ; ModeleVent leur met leur lueur.
// node jeu.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { norme, croix } from "../../fruits/generateur/moteur.mjs";
import { sous, pointe, TOUR } from "../../fruits/generateur/rendu.mjs";
import { cubeSimple } from "../../minato2/generateur/effets.mjs";
import { couronne, armureLumiere, aile, aureole, soleilDos, orbe, lameOmbre, epeeLumiere, lance, chaine, flammeSacree, LUEURS } from "./rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = path.join(ICI, "..", "..", "..", "mod-cabane", "src", "main", "resources", "assets", "cabane");

const PARTIES_JOUEUR = ["tete", "corps", "brasD", "brasG", "jambeD", "jambeG"];
/** Les pivots des parties dans le modèle du jeu (comme PlayerModel) : x, et y compté depuis le cou vers le bas. */
const PIVOT = { tete: [0, 0], corps: [0, 0], brasD: [-5, 2], brasG: [5, 2], jambeD: [-1.9, 12], jambeG: [1.9, 12] };

/** Les couleurs repères des traits qui brillent → leur cœur et leur sorte (5 : lueur violette, 6 : lueur dorée). */
const COEURS = Object.entries(LUEURS)
  .filter(([nom]) => ["violet", "violetDoux", "or", "orDoux"].includes(nom))
  .map(([nom, l]) => ({ repere: l.repere, coeur: l.coeur, sorte: nom.startsWith("violet") ? 5 : 6 }));

/** Une fausse scène qui garde chaque face au lieu de la dessiner (sorte : 0 plein, 1 plein qui brille, 5-6 un cœur). */
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
      const coeur = COEURS.find((k) => k.repere.every((v, i) => Math.round(c[i]) === v));
      const f = { coins: coins.map((p) => [...p]), c: coeur ? coeur.coeur : c.slice(0, 3), sorte: coeur ? coeur.sorte : info.emissif ? 1 : 0, n, partie };
      faces.push(f);
      // les cœurs sont dessinés dans un rendu qui cache les faces vues de dos : on les met dans les deux sens
      if (f.sorte >= 2) faces.push({ ...f, coins: [...f.coins].reverse(), n: f.n.map((v) => -v) });
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

function portee(dossier, nom, dessin) {
  const faces = enregistrer(dessin).map((f) => versJeu({ ...f, partie: f.partie ?? "corps" }));
  console.log(dossier, nom, ecrireMaillages(path.join(ASSETS, dossier, `${nom}.bin`), PARTIES_JOUEUR.map((p) => faces.filter((f) => f.partie === p))));
}

function libre(dossier, nom, dessin) {
  console.log(dossier, nom, ecrireMaillages(path.join(ASSETS, dossier, `${nom}.bin`), [enregistrer(dessin)]));
}

// ------------------------------------------------------------ les Ténèbres
portee("tenebres", "couronne", (sc, T) => couronne(sc, T));
libre("tenebres", "orbe", (sc) => orbe(sc, [0, 0, 0], 2.2));
// la lame d'ombre : le creux vers -z, elle file vers +z (rayon 14 pixels)
libre("tenebres", "lame", (sc) => lameOmbre(sc, [0, 0, 0], [0, 0, 1], 14, 111));
// une pique d'ombre : la base en 0, la pointe en haut (16 pixels, large de 4), le bout violet
libre("tenebres", "pique", (sc) => {
  pointe(sc, [0, 0, 0], [0, 1, 0], 16, 4, (t) => [8 + 82 * t * t, 4 + 36 * t * t, 12 + 138 * t * t], false, true);
  cubeSimple(sc, [0, 15, 0], 1, [5, 1, 7, 1]);
});
// deux maillons de chaîne, le long de z (de -1,25 à 1,25)
libre("tenebres", "maillons", (sc) => chaine(sc, [0, 0, -2.5], [0, 0, 2.5]));

// ------------------------------------------------------------ la Lumière
portee("lumiere", "aureole", (sc, T) => aureole(sc, T, 5.6, 37));
portee("lumiere", "aureole1", (sc, T) => aureole(sc, T, 4.6, 35.5));
[0.3, 0.65, 1].forEach((k, i) => portee("lumiere", `ailes2_${i + 1}`, (sc, T) => { aile(sc, T, -1, { ouvert: k }); aile(sc, T, 1, { ouvert: k }); }));
portee("lumiere", "ailes3", (sc, T) => {
  for (const c of [-1, 1]) {
    aile(sc, T, c, { angle: -0.45, taille: 1.05 });
    aile(sc, T, c, { angle: 0.15, taille: 0.85, plumes: 8 });
    aile(sc, T, c, { angle: 0.75, taille: 0.6, plumes: 7 });
  }
});
portee("lumiere", "armure", (sc, T) => armureLumiere(sc, T));
portee("lumiere", "soleil", (sc, T) => soleilDos(sc, T));
// la lance de lumière : la pointe en 0, le manche vers -z
libre("lumiere", "lance", (sc) => lance(sc, [0, 0, 0], [0, 0, 1], 26, 1));
// l'épée de lumière : plantée en 0, la lame vers +y
libre("lumiere", "epee", (sc) => epeeLumiere(sc, [0, 0, 0], 1));
// une plume d'or : la pointe vers +z
libre("lumiere", "plume", (sc) => flammeSacree(sc, [0, 0, -4.5], [0, 0, 1], 4.5, 1.4));
// le chakram (l'auréole lancée), à plat dans le plan xz, rayon 7,5 pixels, des dents qui tournent
libre("lumiere", "chakram", (sc) => {
  for (let q = 0; q < 36; q++) {
    const a = (q / 36) * TOUR;
    cubeSimple(sc, [Math.cos(a) * 7.5, 0, Math.sin(a) * 7.5], 1.3, [7, 5, 1, 1]);
    if (q % 3 === 0) flammeSacree(sc, [Math.cos(a) * 7.8, 0, Math.sin(a) * 7.8], [-Math.sin(a), 0, Math.cos(a)], 2.6, 1);
  }
  for (let q = 0; q < 24; q++) {
    const a = (q / 24) * TOUR;
    cubeSimple(sc, [Math.cos(a) * 5.6, 0, Math.sin(a) * 5.6], 0.7, [7, 5, 3, 1]);
  }
});

// ------------------------------------------------------------ les tenues du niveau 3 : les deux skins de l'utilisateur, adaptés
// Ténèbres (docs/tenebres-lumiere/skins/tenebres.png) : les yeux violets (ils brillent aussi en jeu), les sourcils noirs,
// la peau un peu assombrie vers le violet, des veines violettes qui montent des mains, des pieds et de la taille.
// Lumière (skins/lumiere.png, bras fins) : les verres de la visière en or clair.
import { lire, ecrirePng, mix } from "../../fruits/generateur/moteur.mjs";
import { veines, peindre } from "./rendu.mjs";
const SKINS_U = path.join(ICI, "..", "skins"), TEXTURES = path.join(ASSETS, "textures", "pouvoir");
fs.mkdirSync(TEXTURES, { recursive: true });
{
  const yeux = { "9,12": [236, 214, 255], "10,12": [176, 96, 255], "13,12": [176, 96, 255], "14,12": [236, 214, 255] };
  let t = peindre(lire(path.join(SKINS_U, "tenebres.png")), (c, x, y) => {
    if (yeux[x + "," + y]) return yeux[x + "," + y];
    if (y === 11 && x >= 9 && x <= 14) return [18, 8, 26];
    if (c[0] < 60 && c[1] < 60 && c[2] < 60) return c;
    return mix(c.map((v) => v * 0.8), [64, 36, 92], 0.18);
  });
  t = veines(t, 26, 31, [150, 70, 230], 0.7);
  ecrirePng(path.join(TEXTURES, "tenebres3.png"), 64, 64, t.px);
  ecrirePng(path.join(SKINS_U, "tenebres3-adapte.png"), 64, 64, t.px);
}
{
  const verres = { 12: [255, 236, 150], 13: [255, 198, 60] };
  const t = peindre(lire(path.join(SKINS_U, "lumiere.png")), (c, x, y) => {
    const face = (x >= 8 && x < 16) || (x >= 40 && x < 48);
    if (face && verres[y] && [1, 2, 5, 6].includes(x % 8)) return verres[y];
    return null;
  });
  ecrirePng(path.join(TEXTURES, "lumiere3.png"), 64, 64, t.px);
  ecrirePng(path.join(SKINS_U, "lumiere3-adapte.png"), 64, 64, t.px);
}
console.log("tenues ok");
