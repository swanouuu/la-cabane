// L'animation de la Bijuu Dama (clic droit de Minato 2), image par image, d'après la planche : le TP et le coup, le
// pilier de chakra, Kurama qui apparaît et rugit, les mains, la boule noire qui se forme puis se comprime, le tir,
// l'impact, le dôme vu du ciel, le flash blanc, la cible au sol. Les images vont dans un dossier, ffmpeg en fait la vidéo.
// node anim.mjs <dossier des images> [image de départ] [image de fin]
import fs from "node:fs";
import path from "node:path";
import { ecrirePng, mix, hache, scene, ecrireTexte } from "../../fruits/generateur/moteur.mjs";
import { cube, sol, STEVE } from "../../fruits/generateur/rendu.mjs";
import { ajoute, fois, TOUR, brille, joueur, aura, cubeSimple, flash, fantome, kunaiEnMain, boule, bijuuDama, convergent, dome, LUEUR, OR_C, BLANC } from "./effets.mjs";
import { monde } from "./kyubi.mjs";
import { kuramaMod } from "./kurama-mod.mjs";
import { etoiles, rayons, pilier, cailloux, brule, MODE } from "./rendu.mjs";

const W = 960, H = 540, FPS = 20;
const lisse = (u) => { u = Math.max(0, Math.min(1, u)); return u * u * (3 - 2 * u); };
const entre = (a, b, u) => (Array.isArray(a) ? a.map((v, i) => v + (b[i] - v) * u) : a + (b - a) * u);
const SOMBRE = [46, 30, 30], NUIT = [22, 20, 44];

/** Le mode Kurama, qui brille, avec son aura posée en fin d'image. */
const auras = [];
const mode = (sc, o = {}) => { const T = joueur(sc, MODE, { lueur: true, ...o }); auras.push(T); return T; };

// Les plans : [début, fin, légende, caméra(u) → options de scène, dessin(sc, u, t)]
const PLANS = [
  [0, 1.4, "0 S : TP SUR LA CIBLE, IL FRAPPE", (u) => ({ lacet: -70, tangage: 10, echelle: entre(4.2, 4.8, u), centre: [0, 16, entre(20, 0, u)] }), (sc, u, t) => {
    sol(sc, -50, -100, 50, 110);
    const coup = lisse((t - 0.1) / 0.3), vol = lisse((t - 0.4) / 0.9);
    joueur(sc, STEVE, { decale: [0, 16 * Math.sin(Math.PI * vol), -64 * vol], penche: -0.3 - 0.9 * vol, brasD: [-1 - 1.6 * vol, -0.6], brasG: [-1 - 1.6 * vol, 0.6] });
    if (t > 0.1) {
      const T = mode(sc, { decale: [0, 0, 14], lacet: Math.PI, brasD: [-0.4 - 1.2 * coup, 0], brasG: [0.4, 0.1], jambeD: 0.5 * coup, jambeG: -0.4 * coup });
      kunaiEnMain(sc, T);
      if (t < 0.5) flash(sc, ajoute(T.brasD.p([-6, 11, 0]), [0, 0, -3]), 1.1 - t);
    }
    if (t < 0.35) flash(sc, [0, 16, 80], 0.8);
    if (t > 0.4 && t < 1.1) {
      const r = 6 + 26 * lisse((t - 0.4) / 0.7);
      for (let q = 0; q < 24; q++) { const a = (q / 24) * TOUR; cubeSimple(sc, [Math.cos(a) * r, 20 + Math.sin(a) * r, -12], 1.5, brille(q % 2 ? BLANC : OR_C), true); }
    }
    sc.lueur(LUEUR, 7, 1.4);
    if (t < 0.35) fantome(sc, [0, 0, 80], Math.PI, 0.35 * (1 - t / 0.35));
  }],
  [1.4, 2.2, "1,4 S : LE CHAKRA MONTE EN PILIER", (u) => ({ lacet: -18, tangage: 4, echelle: entre(6, 7.2, u), centre: [0, 14, 0], fond: [255, 226, 150] }), (sc, u) => {
    rayons(sc, sc.w / 2, sc.h * 0.4, [255, 255, 220], 90, 12 + Math.floor(u * 6));
    sol(sc, -40, -40, 40, 40);
    mode(sc, { brasD: [-0.4, -0.8 * lisse(u * 2)], brasG: [-0.4, 0.8 * lisse(u * 2)], tete: [-0.4] });
    sc.lueur([255, 236, 160], 12, 1.8);
    pilier(sc, [0, 0, 0], 8 + 4 * u, 220, BLANC, 0.25 + 0.3 * lisse(u * 3));
  }],
  [2.2, 3.4, "2,2 S : KURAMA APPARAIT DERRIERE LUI", (u) => ({ lacet: entre(-10, -30, u), tangage: 4, echelle: entre(3.6, 1.7, lisse(u)), centre: entre([0, 30, 70], [0, 64, 10], lisse(u)), fond: SOMBRE }), (sc, u) => {
    sol(sc, -130, -130, 130, 130);
    kuramaMod(sc, monde([0, 0, -10], [0, 0, 1], 0.15 + 0.65 * lisse(u * 1.3)));
    mode(sc, { decale: [0, 0, 92], brasD: [-0.5, -0.3], brasG: [-0.5, 0.3] });
    sc.lueur(LUEUR, 7, 1.2);
  }],
  [3.4, 4.2, "3,2 S : IL RUGIT", (u, t) => ({ lacet: -26, tangage: -4, echelle: entre(4.2, 4.9, u), centre: [Math.sin(t * 60) * 1.4, 98 + Math.cos(t * 47) * 1.2, 40], fond: [80, 30, 16] }), (sc, u) => {
    rayons(sc, sc.w / 2, sc.h * 0.5, [255, 170, 60], 100, 14 + Math.floor(u * 8));
    kuramaMod(sc, monde([0, 0, 0], [0, 0, 1], 1));
    sc.lueur(LUEUR, 7, 1.2);
  }],
  [4.2, 5.0, "4 S : IL TEND LES MAINS, LA LUMIERE NAIT", (u) => ({ lacet: -10, echelle: entre(9.5, 11, u), centre: [0, 20, 0], fond: [110, 50, 20] }), (sc, u) => {
    rayons(sc, sc.w / 2, sc.h * 0.5, [255, 200, 110], 90, 22);
    const T = mode(sc, { brasD: [-1.45 * lisse(u * 2), 0.42], brasG: [-1.45 * lisse(u * 2), -0.42] });
    const ici = ajoute(fois(ajoute(T.brasD.p([-6, 11, 0]), T.brasG.p([6, 11, 0])), 0.5), [0, 0, 3]);
    flash(sc, ici, 0.3 + 0.8 * u);
    sc.lueur(LUEUR, 8, 1.4);
  }],
  [5.0, 6.2, "4,8 S : LA BOULE NOIRE SE FORME", (u) => ({ lacet: entre(-36, -24, u), tangage: 2, echelle: 2.2, centre: [0, 86, 40], fond: NUIT }), (sc, u) => {
    etoiles(sc, 160, 16);
    kuramaMod(sc, monde([0, 0, 0], [0, 0, 1], 1));
    const c = [0, 92, 78];
    if (u > 0.08) bijuuDama(sc, c, 13 * lisse(u * 1.2));
    convergent(sc, c, 14 * lisse(u), 60 - 40 * u, 80, 2 + Math.floor(u * 5));
    sc.lueur(LUEUR, 6, 1.1);
  }],
  [6.2, 7.4, "6 S : ELLE SE COMPRIME, LE COEUR ROUGE", (u) => ({ lacet: 0, echelle: entre(8, 9.4, u), centre: [0, 0, 0], fond: [16, 14, 34] }), (sc, u) => {
    etoiles(sc, 200, 18);
    convergent(sc, [0, 0, 0], 18 - 4 * u, 64 - 30 * u, 80, 4 + Math.floor(u * 6));
    bijuuDama(sc, [0, 0, 0], 16 - 5 * lisse(u), 0.2 + 0.8 * lisse(u));
    sc.lueur([236, 60, 20], 12, 1.2 + 0.6 * u);
  }],
  [7.4, 8.4, "7,2 S : KURAMA TIRE LA BIJUU DAMA", (u) => ({ lacet: -70, tangage: 10, echelle: 1.45, centre: [0, 50, 70], fond: SOMBRE }), (sc, u) => {
    sol(sc, -60, -120, 60, 260);
    kuramaMod(sc, monde([0, 0, -40], [0, 0, 1], 0.7));
    const depart = [0, 64, 10], fin = [0, 20, 180], v = lisse(u);
    const p = entre(depart, fin, v);
    for (let q = 1; q < 12; q++) cubeSimple(sc, entre(depart, fin, Math.max(0, v - q * 0.03)), 9 - q * 0.6, brille(mix([255, 226, 130], [255, 120, 30], q / 12)), true);
    boule(sc, p, 12, () => brille([255, 226, 120]), { tranche: 2 });
    cailloux(sc, [0, 0, entre(60, 180, v)], 30, 16, Math.floor(u * 4));
    joueur(sc, STEVE, { decale: fin.map((x, i) => (i === 1 ? 0 : x)), lacet: Math.PI, brasD: [-1.4, -0.4], brasG: [-1.4, 0.4] });
    sc.lueur(LUEUR, 8, 1.5);
  }],
  [8.4, 9.6, "8,2 S : IMPACT", (u, t) => ({ lacet: -40, tangage: 4, echelle: 5, centre: [Math.sin(t * 70) * 1.5, 18, 0], fond: [60, 24, 14] }), (sc, u) => {
    rayons(sc, sc.w * 0.45, sc.h * 0.45, [255, 150, 50], 110, 20 + Math.floor(u * 8));
    sol(sc, -60, -60, 60, 60);
    joueur(sc, STEVE, { decale: [12, 0, 0], lacet: -0.6, penche: -0.3, brasD: [-1.8, -0.5], brasG: [-1.8, 0.5] });
    boule(sc, [-10, 22, 0], 6 + 22 * lisse(u * 1.4), (n, y) => brille(mix([255, 244, 186], [255, 120, 26], Math.max(0, -y) * 0.8 + 0.2)), { tranche: 2.5 });
    cailloux(sc, [0, 0, 0], 20 + 40 * u, 24, 3);
    sc.lueur([255, 140, 40], 12, 1.7);
  }],
  [9.6, 11.8, "9,4 S : LE DOME, VU DU CIEL (10 BLOCS)", (u) => ({ lacet: entre(-42, -18, u), tangage: 32, echelle: 1.25, centre: [0, 20, 0], fond: [120, 96, 80] }), (sc, u) => {
    const r = 76 * lisse(u * 1.6);
    sol(sc, -160, -160, 160, 160, brule(0, 0, Math.max(1, r * 1.3)));
    if (r > 2) dome(sc, [0, 0, 0], r);
    sc.lueur([255, 170, 70], 12, 1.5);
  }],
  [11.8, 12.3, "12 S : FLASH BLANC", (u) => ({ lacet: -18, tangage: 32, echelle: 1.25, centre: [0, 20, 0], fond: [120, 96, 80] }), (sc, u) => {
    sol(sc, -160, -160, 160, 160, brule(0, 0, 99));
    dome(sc, [0, 0, 0], 76);
    sc.lueur([255, 170, 70], 12, 1.5);
    sc.blanc = lisse(u * 1.5);
  }],
  [12.3, 13.3, "12 S : LA CIBLE AU SOL", (u) => ({ lacet: -30, tangage: 18, echelle: 3.6, centre: [0, 8, 14] }), (sc, u) => {
    sol(sc, -60, -50, 60, 80, brule(0, -10, 46));
    joueur(sc, STEVE, { decale: [0, 2, -10], penche: -Math.PI / 2, brasD: [-0.4, -0.4], brasG: [-0.4, 0.4] });
    for (let q = 0; q < 10; q++) cube(sc, [(hache(q, 1, 640) - 0.5) * 50, 4 + 30 * hache(q, 2, 640) + 8 * u, -10 + (hache(q, 3, 640) - 0.5) * 40], 3 + 3 * hache(q, 4, 640), [q, q], [150, 140, 136]);
    kunaiEnMain(sc, mode(sc, { decale: [6, 0, 44], lacet: Math.PI + 0.3, brasD: [-0.2, -0.1] }));
    sc.lueur(LUEUR, 6, 1.2);
    sc.blanc = 1 - lisse(u * 2.5);
  }],
];
const DUREE = PLANS[PLANS.length - 1][1];

const [dossier, de = "0", a = String(Math.round(DUREE * FPS))] = process.argv.slice(2);
fs.mkdirSync(dossier, { recursive: true });
for (let n = +de; n < +a; n++) {
  const t = n / FPS;
  const plan = PLANS.find(([d, f]) => t >= d && t < f) ?? PLANS[PLANS.length - 1];
  const [d, f, legende, camera, dessin] = plan;
  const u = (t - d) / (f - d);
  const sc = scene({ w: W, h: H, pied: H * 0.6, fond: [236, 236, 242], ...camera(u, t) });
  auras.length = 0;
  dessin(sc, u, t - d);
  for (const T of auras) aura(sc, T);
  if (sc.blanc) for (let i = 0; i < W * H; i++) for (let k = 0; k < 3; k++) sc.img[i * 4 + k] += (255 - sc.img[i * 4 + k]) * sc.blanc;
  // la légende, en bas à gauche, sur une bande sombre
  for (let y = H - 40; y < H; y++) for (let x = 0; x < W; x++) { const i = (y * W + x) * 4; for (let k = 0; k < 3; k++) sc.img[i + k] *= 0.45; }
  ecrireTexte(sc.img, W, legende, 16, H - 30, 3, [255, 230, 160, 255]);
  ecrirePng(path.join(dossier, `f${String(n).padStart(4, "0")}.png`), W, H, sc.img);
}
console.log("ok", +a - +de, "images");
