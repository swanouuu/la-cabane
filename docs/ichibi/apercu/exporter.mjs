// Exporte les modèles des croquis (refonte.mjs) pour l'animation 3D de l'ulti : node exporter.mjs → modeles/*.bin
// On rejoue les fonctions de dessin sur une fausse scène qui garde chaque face (ses 4 coins, sa couleur sans ombre, sa
// normale) au lieu de la dessiner. Format : par face, 12 float32 (coins, en blocs), 3 float32 (normale), 4 octets
// (r, g, b, brille). Les coordonnées passent des pixels du modèle aux blocs (÷ 16).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { jarre, bete, statue, vagueSable, perso, STEVE, volume, ellip, bosse, sableI } from "../generateur/refonte.mjs";
import { ID } from "../../fruits/generateur/rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const SORTIE = path.join(ICI, "modeles");
fs.mkdirSync(SORTIE, { recursive: true });

function capteur() {
  const faces = [];
  let derniere = [0, 1, 0];
  return {
    faces, w: 1, h: 1, img: Buffer.alloc(4),
    lumiere: (n) => { derniere = n; return 1; },
    dirVue: (n) => n,
    vue: () => [0, 0, 0],
    voile: () => {},
    lueur: () => {},
    quad: (coins, c, decale, aile, opt) => faces.push({ coins, c, n: opt?.normale ?? derniere, e: !!opt?.emissif }),
  };
}

function ecrire(nom, sc) {
  const b = Buffer.alloc(sc.faces.length * 64);
  sc.faces.forEach((f, i) => {
    let o = i * 64;
    for (const p of f.coins) for (const v of p) { b.writeFloatLE(v / 16, o); o += 4; }
    for (const v of f.n) { b.writeFloatLE(v, o); o += 4; }
    for (const v of f.c.slice(0, 3)) b[o++] = Math.max(0, Math.min(255, Math.round(v)));
    b[o] = f.e ? 1 : 0;
  });
  fs.writeFileSync(path.join(SORTIE, nom + ".bin"), b);
  console.log(nom, sc.faces.length, "faces");
}

const quoi = process.argv.slice(2);
const veut = (n) => !quoi.length || quoi.includes(n);

// la bête (niveau 3), sans jarre, et la jarre à part (pour la faire briller)
if (veut("bete")) {
  const sc = capteur(), scj = capteur();
  const T = bete(sc, {});
  jarre(scj, T.corps, { ouverte: true });
  ecrire("bete", sc);
  ecrire("jarre", scj);
}
// la bête en morceaux, pour qu'elle coure : le corps (sans jambes ni bras), chaque jambe droite (on la tourne à la
// hanche dans la page), et les deux bras en 8 poses de course (les mains se posent l'une après l'autre) + au repos
if (veut("course")) {
  const corps = capteur();
  bete(corps, { morceau: "corps" });
  ecrire("bete-corps", corps);
  for (const nom of ["jambeD", "jambeG"]) {
    const sc = capteur();
    bete(sc, { morceau: nom, jambeD: 0, jambeG: 0 });
    ecrire(nom, sc);
  }
  const repos = capteur();
  bete(repos, { morceau: "bras" });
  ecrire("bras-repos", repos);
  for (let k = 0; k < 8; k++) {
    const sc = capteur(), ph = (k / 8) * Math.PI * 2;
    const main = (s, f) => [s * 19, 3 + 8 * Math.max(0, -Math.sin(f)), 17 + 10 * Math.cos(f)];
    bete(sc, { morceau: "bras", mains: [main(-1, ph), main(1, ph + Math.PI)] });
    ecrire("bras-" + k, sc);
  }
}
if (veut("rire")) {
  const sc = capteur(), scj = capteur();
  const T = bete(sc, { buste: 0.15, tete: [-0.6, 0, 0], rire: 1.4, jambeD: 0.2, jambeG: -0.2, mains: [[-22, 40, 8], [22, 40, 8]], devant: [-0.3, 0.8, 0.5] });
  jarre(scj, T.corps, { ouverte: true });
  ecrire("rire", sc);
  ecrire("jarre-rire", scj);
}
// les cibles : debout, bras ballants ; prises, bras en l'air ; projetées
if (veut("steve")) {
  for (const [nom, o] of [
    ["steve", { brasD: [-0.1, -0.05], brasG: [0.1, 0.05] }],
    ["steve-peur", { brasD: [-1.6, -0.5], brasG: [-1.7, 0.5], tete: [-0.3] }],
    ["steve-pris", { brasD: [-2.7, -0.4], brasG: [-2.7, 0.4], tete: [-0.5], jambeD: 0.3, jambeG: -0.2 }],
    ["steve-vole", { brasD: [-2.4, -0.9], brasG: [-1.4, 0.9], jambeD: 0.6, jambeG: -0.4 }],
  ]) {
    const sc = capteur();
    perso(sc, STEVE, o);
    ecrire(nom, sc);
  }
}
// la statue géante (16 blocs) : les mains ouvertes, puis refermées
if (veut("statue")) {
  for (const [nom, m] of [["statue-ouverte", 0], ["statue-fermee", 1]]) {
    const sc = capteur();
    statue(sc, [0, 0, 0], 1, m, 7, 3);
    ecrire(nom, sc);
  }
}
// la vague géante (10 blocs de haut, 60 de large), qui avance vers -z
if (veut("vague")) {
  const sc = capteur();
  vagueSable(sc, 0, 160, 960, -1, 4, 5);
  ecrire("vague", sc);
}
// la pyramide de sable qui l'écrase (7 blocs de base, 5 de haut)
if (veut("pyramide")) {
  const sc = capteur();
  const f = (p) => Math.max(Math.abs(p[0]) + Math.abs(p[2]) + p[1] * 1.12 - 62, -p[1]) + 1.6 * bosse(p, 3, 1800);
  volume(sc, ID, f, [-66, -1, -66, 66, 60, 66], 1.6, sableI(130, 0.5));
  ecrire("pyramide", sc);
}
