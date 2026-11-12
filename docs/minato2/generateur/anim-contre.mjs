// Le contre de Minato (touche L, niveau 1 ou 2), d'après la vidéo de l'utilisateur (Naruto arrête la lame à main nue,
// « Calm down », puis Kurama la gueule ouverte au-dessus de l'attaquant) : il se met en garde 20 secondes ; un joueur
// qui le frappe se fait arrêter à la main (chakra rouge), voit ses yeux rouges, puis la tête géante de Kurama, gueule
// grande ouverte, qui le domine ; dehors, tout s'assombrit et les autres voient Kurama (tête et pattes, énormes, dans
// orange, ramassé et la tête basse, prêt à bondir) derrière Minato.
// node anim-contre.mjs <dossier des images> [image de départ] [image de fin]
import fs from "node:fs";
import path from "node:path";
import { ecrirePng, mix, hache, scene, ecrireTexte } from "../../fruits/generateur/moteur.mjs";
import { sol, epee, SKINS, STEVE } from "../../fruits/generateur/rendu.mjs";
import { ajoute, fois, TOUR, brille, joueur, cubeSimple } from "./effets.mjs";
import { monde } from "./kyubi.mjs";
import { kuramaMod, TETE } from "./kurama-mod.mjs";

const W = 960, H = 540, FPS = 20;
const lisse = (u) => { u = Math.max(0, Math.min(1, u)); return u * u * (3 - 2 * u); };
const entre = (a, b, u) => (Array.isArray(a) ? a.map((v, i) => v + (b[i] - v) * u) : a + (b - a) * u);
const KAI = SKINS.kai;
const ROUGE = [255, 46, 24], ORANGE = [255, 132, 40], SANG = [200, 20, 10];

/** Minato (niveau 1 : son skin, une lueur à peine dorée). */
const minato = (sc, o = {}) => joueur(sc, KAI, { teinte: { c: [255, 242, 186], k: 0.08 }, ...o });

/** Ses yeux qui virent au rouge de Kurama : deux plaques qui brillent sur le visage (k : leur éclat, de 0 à 1). */
function yeuxRouges(sc, T, k = 1) {
  const c = mix([255, 255, 255], ROUGE, Math.min(1, k)).map((v) => v * (0.7 + 0.3 * k));
  for (const [x0, x1] of [[-3, -1], [1, 3]]) {
    sc.quad([[x0, 28, 4.06], [x1, 28, 4.06], [x1, 27, 4.06], [x0, 27, 4.06]].map(T.tete.p), c, 0.05, true);
  }
}

/** Le chakra rouge autour d'une main : des éclats rouges et orange qui crépitent et montent (k : sa force). */
function chakraRouge(sc, p, k, graine) {
  const n = Math.round(26 * k);
  for (let q = 0; q < n; q++) {
    const a = hache(q, graine, 51) * TOUR, r = (1 + 3.2 * hache(q, graine, 52)) * k;
    const monte = ((hache(q, 3, 53) + graine * 0.07) % 1) * 5 * k;
    const c = [Math.cos(a) * r, -1.5 + monte, Math.sin(a) * r];
    cubeSimple(sc, ajoute(p, c), (0.5 + 0.9 * hache(q, graine, 54)) * k, brille(mix(ROUGE, ORANGE, hache(q, 9, 55))), true);
  }
  cubeSimple(sc, p, 1.6 * k, brille([255, 200, 150]), true);
}

/** Un anneau d'éclats au sol (la mise en garde). */
function anneau(sc, c, r, couleur) {
  for (let q = 0; q < 40; q++) {
    const a = (q / 40) * TOUR;
    cubeSimple(sc, ajoute(c, [Math.cos(a) * r, 0.6, Math.sin(a) * r]), 1.1, brille(q % 2 ? couleur : ORANGE), true);
  }
}

const OR_K = [255, 120, 30], NUIT = [3, 3, 5];
/**
 * Kurama, le renard orange : sa tête, sa poitrine et ses pattes de devant, ramassé, prêt à bondir (le buste penché en
 * avant, les pattes plantées au sol, la tête basse entre elles, relevée pour regarder devant). Ses pieds de modèle en p
 * (au sol), tourné vers +z, à l'échelle k, la gueule ouverte de m.
 */
const POSE = { penche: 1.45, pivot: [0, 20, 0], bras: -2.1, leve: -1.35, sol: 27 };
const rotX = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
function repere(p, k) {
  const { penche, pivot } = POSE;
  return { p: (v) => rotX(v.map((x, i) => x - pivot[i]), penche).map((x, i) => p[i] + (x + pivot[i]) * k), aile: true };
}
function kurama(sc, p, k, m = 0) {
  kuramaMod(sc, repere(ajoute(p, [0, POSE.sol * k, 0]), k), { tete: true, machoire: m, bras: POSE.bras, leve: POSE.leve, orange: true });
}
/** La même chose, posée par le milieu de sa tête (pour les gros plans). */
function tete(sc, h, k, m = 0) {
  const o = repere([0, POSE.sol * k, 0], k).p(rotX(TETE.map((x, i) => x - [0, 89, 17][i]), POSE.leve).map((x, i) => x + [0, 89, 17][i]));
  kurama(sc, h.map((v, i) => v - o[i]), k, m);
}

/** Un fond en dégradé (du haut vers le bas) et un voile de couleur sur toute l'image. */
function fondDegrade(sc, haut, bas) {
  for (let y = 0; y < sc.h; y++) {
    const c = mix(haut, bas, y / sc.h);
    for (let x = 0; x < sc.w; x++) sc.img.set([...c.map(Math.round), 255], (y * sc.w + x) * 4);
  }
}
const teinter = (sc, c, a) => { for (let i = 0; i < sc.w * sc.h; i++) for (let k = 0; k < 3; k++) sc.img[i * 4 + k] += (c[k] - sc.img[i * 4 + k]) * a; };
function sousTitre(sc, t) {
  const l = t.length * 4 * 4;
  ecrireTexte(sc.img, sc.w, t, Math.round((sc.w - l) / 2) + 2, sc.h - 92, 4, [0, 0, 0, 255]);
  ecrireTexte(sc.img, sc.w, t, Math.round((sc.w - l) / 2), sc.h - 94, 4, [255, 255, 255, 255]);
}

// L'attaquant : Steve, épée en main ; il court vers Minato (en z positif, tourné vers lui) et frappe.
const attaquant = (sc, o) => { const T = joueur(sc, STEVE, { lacet: Math.PI, ...o }); epee(sc, T.brasD, "D"); return T; };

// Les plans : [début, fin, légende, caméra(u, t) → options de scène, dessin(sc, u, t)]
const PLANS = [
  [0, 1.8, "TOUCHE L (NIVEAU 1 OU 2) : 20 S DE GARDE", (u) => ({ lacet: -24, tangage: 6, echelle: entre(7.5, 8.5, u), centre: [0, 16, 0] }), (sc, u, t) => {
    sol(sc, -50, -50, 50, 50);
    const g = lisse(u * 2.2);
    const T = minato(sc, { brasD: [-0.5 * g, 0.2 * g], brasG: [0.3 * g, -0.1], jambeD: 0.2 * g, jambeG: -0.2 * g });
    yeuxRouges(sc, T, lisse((u - 0.15) * 3));
    if (u > 0.1) anneau(sc, [0, 0, 0], 4 + 18 * lisse((u - 0.1) * 2), ROUGE);
    chakraRouge(sc, T.brasD.p([-6, 11, 0]), 0.35 + 0.25 * Math.sin(t * 20) ** 2, Math.floor(t * 12));
    sc.lueur(ROUGE, 7, 1.3);
  }],
  [1.8, 3.2, "UN JOUEUR L'ATTAQUE (PVP)", (u) => ({ lacet: -78, tangage: 6, echelle: 4.4, centre: [0, 16, 30] }), (sc, u, t) => {
    sol(sc, -40, -40, 40, 120);
    const T = minato(sc, { brasD: [-0.3, 0.2], jambeD: 0.2, jambeG: -0.2 });
    yeuxRouges(sc, T, 1);
    chakraRouge(sc, T.brasD.p([-6, 11, 0]), 0.3, Math.floor(t * 12));
    const v = lisse(u * 1.1), pas = Math.sin(t * 16);
    attaquant(sc, { decale: [0, 0, entre(100, 16, v)], penche: 0.25, brasD: [-1 - 1.9 * lisse((u - 0.55) * 3), 0], brasG: [0.8 * pas, 0], jambeD: 0.9 * pas, jambeG: -0.9 * pas });
    sc.lueur(ROUGE, 6, 1.1);
  }],
  [3.2, 4.6, "IL L'ARRETE A MAIN NUE", (u, t) => ({ lacet: -90, tangage: 4, echelle: entre(7.5, 8.5, u), centre: [0, 20 + Math.sin(t * 80) * (u < 0.3 ? 0.5 : 0), 12] }), (sc, u, t) => {
    sol(sc, -40, -40, 40, 60);
    const c = lisse(u * 4);
    const T = minato(sc, { brasG: [-0.3 - 1.7 * c, -0.15], jambeD: 0.3, jambeG: -0.3, penche: 0.05 });
    yeuxRouges(sc, T, 1);
    // la lame s'abat, et s'arrête net dans sa main
    const coupe = Math.min(1, u * 4);
    attaquant(sc, { decale: [0, 0, 24], penche: 0.18, brasD: [-2.9 + 0.7 * coupe, 0.05], brasG: [-0.4, 0], jambeD: 0.4, jambeG: -0.4 });
    const main = T.brasG.p([6, 11, 0]);
    chakraRouge(sc, main, 0.6 + 0.7 * lisse((u - 0.15) * 3), Math.floor(t * 14));
    if (u > 0.2 && u < 0.6) anneau(sc, ajoute(main, [0, -0.6, 0]), 3 + 12 * lisse((u - 0.2) * 2.5), ROUGE);
    sc.lueur(ROUGE, 9, 1.6);
  }],
  [4.6, 6.4, "CE QUE VOIT L'ATTAQUANT : SES YEUX", (u) => ({ lacet: 0, tangage: -6, echelle: entre(26, 30, u), centre: [0, 27, 0], fond: [8, 8, 14] }), (sc, u) => {
    const T = minato(sc, { teinte: { c: [6, 6, 12], k: 0.72 } });
    yeuxRouges(sc, T, 0.6 + 0.4 * lisse(u * 2));
    sc.lueur(ROUGE, 12, 2.2);
    if (u > 0.3) sousTitre(sc, "CALME-TOI.");
  }],
  [6.4, 6.8, "", () => ({ lacet: 0, echelle: 26, centre: [0, 27, 0], fond: [8, 8, 14] }), (sc, u) => {
    const T = minato(sc, { teinte: { c: [6, 6, 12], k: 0.72 } });
    yeuxRouges(sc, T, 1);
    sc.lueur(ROUGE, 12, 2.2);
    teinter(sc, [236, 240, 150], lisse(u * 1.4));
  }],
  [6.8, 9.0, "PUIS KURAMA, LA GUEULE GRANDE OUVERTE", (u, t) => ({ lacet: entre(-8, 4, u), tangage: entre(-4, 2, u), echelle: entre(2.2, 3.8, lisse(u)), centre: [Math.sin(t * 50) * 1.2 * u, 80 + Math.cos(t * 41) * 1.2 * u, 0] }), (sc, u) => {
    fondDegrade(sc, NUIT, [20, 10, 4]);
    tete(sc, [0, 90, 0], 2.2, 0.85 * lisse((u - 0.15) * 1.8));
    sc.lueur(OR_K, 10, 1.2);
    teinter(sc, [255, 226, 130], 1 - lisse(u * 5));
    if (u > 0.3) sousTitre(sc, "KURAMA...");
  }],
  [9.0, 10.8, "IL LE DOMINE DE TOUTE SA TAILLE", (u) => ({ lacet: 0, tangage: -8, echelle: entre(1.75, 1.55, u), centre: [0, 62, 0] }), (sc, u, t) => {
    fondDegrade(sc, NUIT, [16, 9, 4]);
    kurama(sc, [0, 0, -360], 3.2, 0.7 + 0.08 * Math.sin(t * 6));
    // l'attaquant, minuscule, de dos, qui recule
    attaquant(sc, { decale: [0, 0, entre(70, 80, lisse(u))], penche: -0.15, brasD: [-1.2, -0.3], brasG: [-1.2, 0.3], jambeD: 0.3, jambeG: -0.2 });
    sc.lueur(OR_K, 10, 1.2);
    if (u < 0.6) sousTitre(sc, "KURAMA...");
  }],
  [10.8, 14.0, "DEHORS : KURAMA ENORME DERRIERE MINATO", (u) => {
    const n = lisse(u * 2.2);
    return { lacet: entre(-46, -30, u), tangage: 4, echelle: entre(2.6, 0.9, n), centre: entre([0, 30, 12], [0, 70, -130], n), fond: mix([196, 214, 236], NUIT, n) };
  }, (sc, u, t) => {
    const n = lisse(u * 2.2);
    sol(sc, -220, -520, 220, 160);
    const T = minato(sc, { brasG: [-2.0, -0.15], jambeD: 0.3, jambeG: -0.3 });
    attaquant(sc, { decale: [0, 0, 24], penche: 0.18, brasD: [-2.2, 0.05], brasG: [-0.4, 0], jambeD: 0.4, jambeG: -0.4 });
    // tout s'assombrit derrière lui, puis Kurama sort de l'ombre
    teinter(sc, NUIT, 0.75 * n);
    yeuxRouges(sc, T, 1);
    chakraRouge(sc, T.brasG.p([6, 11, 0]), 0.9, Math.floor(t * 14));
    if (n > 0.02) kurama(sc, [0, 0, -380 * n], 0.6 + 2.6 * n, 0.8 * lisse((u - 0.3) * 2.5));
    sc.lueur(OR_K, 10, 1.2 * n);
  }],
  [14.0, 15.6, "IL LACHE : L'ATTAQUANT EST REPOUSSE", (u) => ({ lacet: -60, tangage: 8, echelle: 3.4, centre: [0, 20, 26], fond: mix(NUIT, [196, 214, 236], lisse(u * 1.6)) }), (sc, u, t) => {
    sol(sc, -60, -40, 60, 120);
    const T = minato(sc, { brasG: [-2.0 + 1.8 * lisse(u * 3), -0.15], jambeD: 0.15, jambeG: -0.15 });
    const v = lisse(u * 1.8);
    attaquant(sc, { decale: [0, 10 * Math.sin(Math.PI * v), entre(24, 80, v)], penche: -0.6 * v, brasD: [-1.6, -0.4], brasG: [-1.6, 0.4] });
    teinter(sc, NUIT, 0.75 * (1 - lisse(u * 1.6)));
    yeuxRouges(sc, T, 1 - lisse(u * 1.5));
    // Kurama se défait en éclats dorés
    const f = lisse(u * 2.5);
    if (f < 1) for (let q = 0; q < 60; q++) {
      const p = [(hache(q, 1, 61) - 0.5) * 90, 34 + hache(q, 2, 61) * 60 + 30 * f, -30 + (hache(q, 3, 61) - 0.5) * 60];
      cubeSimple(sc, p, 3 * (1 - f), brille(mix(OR_K, [255, 240, 180], hache(q, 4, 61))), true);
    }
    sc.lueur(OR_K, 8, 1.2 * (1 - f));
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
  const sc = scene({ w: W, h: H, pied: H * 0.6, fond: [196, 214, 236], ...camera(u, t) });
  dessin(sc, u, t - d);
  if (legende) {
    for (let y = H - 40; y < H; y++) for (let x = 0; x < W; x++) { const i = (y * W + x) * 4; for (let k = 0; k < 3; k++) sc.img[i + k] *= 0.45; }
    ecrireTexte(sc.img, W, legende, 16, H - 30, 3, [255, 190, 170, 255]);
  }
  ecrirePng(path.join(dossier, `f${String(n).padStart(4, "0")}.png`), W, H, sc.img);
}
console.log("ok", +a - +de, "images");
