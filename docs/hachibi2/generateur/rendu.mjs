// Maquettes de Hachibi 2.
// Forme 1 : la bête rouge du Hachibi (tête de démon, oreilles), avec six queues et un squelette gris-bleu par-dessus
// (crâne, côtes, colonne, os des bras et des jambes, griffes), comme le « bone mode » de la vidéo de référence.
// Forme 2 : le joueur avec sa propre peau, et huit tentacules de poulpe qui sortent du bas de son dos (Killer Bee).
// node rendu.mjs        → planche-hachibi2.png
// node rendu.mjs anim   → images/NNN.png (pour le GIF des tentacules)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, PARTIES, texels, clair, mix, lisse, hache, norme, croix, tourner, scene, meche, ecrireTexte } from "./moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const BETE = lire(path.join(ICI, "skins", "bete.png"));
const STEVE = lire(path.join(ICI, "skins", "steve.png"));

const ROUGE = [130, 22, 16];
const OS = [176, 190, 210];
const OS_SOMBRE = [64, 70, 88];
const DENT = [236, 236, 228];
const PEAU_POULPE = [178, 108, 124];
const DESSOUS_POULPE = [222, 170, 176];
const VENTOUSE = [236, 226, 226];
const BORD_VENTOUSE = [204, 140, 152];

const ajoute = (a, b) => a.map((v, i) => v + b[i]);
const sous = (a, b) => a.map((v, i) => v - b[i]);
const fois = (a, k) => a.map((v) => v * k);

// ------------------------------------------------------------------ les poses : chaque partie bouge autour de son pivot
const rotX = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
const rotY = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];
const rotZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];

/** Une transformation : un point (ou une direction, sans translation) de la pose de repos vers la pose voulue. */
function transfo({ pivot = [0, 0, 0], rx = 0, rz = 0, s = 1, global = 1, decale = [0, 0, 0], lacet = 0 }) {
  const dir = (v) => rotY(rotX(rotZ(v, rz), rx), lacet);
  return {
    p: (v) => ajoute(fois(rotY(ajoute(fois(rotX(rotZ(fois(sous(v, pivot), s), rz), rx), 1), pivot), lacet), global), decale),
    n: (v) => norme(dir(v)),
  };
}

/** La pose de chaque partie (par son nom dans PARTIES). */
function pose(nom, poseBras, opts) {
  const { global = 1, tete = 1, decale = [0, 0, 0], lacet = 0 } = opts;
  if (nom === "tete") return transfo({ pivot: [0, 24, 0], s: tete, global, decale, lacet });
  if (nom === "brasD") return transfo({ pivot: [-5, 22, 0], rx: poseBras[0], rz: -poseBras[1], global, decale, lacet });
  if (nom === "brasG") return transfo({ pivot: [5, 22, 0], rx: poseBras[2] ?? poseBras[0], rz: poseBras[1], global, decale, lacet });
  return transfo({ global, decale, lacet });
}

// ------------------------------------------------------------------ dessiner une peau, des pavés, des boîtes orientées
/** Une peau sur le modèle (R texels par pixel), dans une pose. */
function dessinerPeau(sc, peau, R, poseBras, opts = {}) {
  const couches = opts.couche2 ? [2, 3] : [2];
  for (const partie of PARTIES(false)) {
    const T = pose(partie[0], poseBras, opts);
    for (const k of couches) {
      for (const t of texels(partie[1], partie[k], k === 3 ? partie[4] : 0, R)) {
        const i = (t.ty * peau.w + t.tx) * 4;
        if (peau.px[i + 3] < 128) continue;
        const l = sc.lumiere(T.n(t.N), false);
        if (l < 0) continue;
        sc.quad(t.coins.map(T.p), clair([peau.px[i], peau.px[i + 1], peau.px[i + 2]], l * (opts.teinte ?? 1)), k === 3 ? 0.02 : 0);
      }
    }
  }
}

/**
 * Un pavé (en coordonnées de repos), découpé en cases de `pas` pour le grain ; `couleur(face, u, v)` donne la couleur de
 * chaque case (u, v de 0 à 1 sur la face), ou null pour la percer.
 */
function pave(sc, a, b, T, couleur, pas = 0.5, decale = 0) {
  const [x0, y0, z0] = a, [x1, y1, z1] = b;
  const faces = [
    { N: [0, 0, 1], O: [x0, y1, z1], U: [x1 - x0, 0, 0], V: [0, y0 - y1, 0], nom: "devant" },
    { N: [0, 0, -1], O: [x1, y1, z0], U: [x0 - x1, 0, 0], V: [0, y0 - y1, 0], nom: "dos" },
    { N: [-1, 0, 0], O: [x0, y1, z0], U: [0, 0, z1 - z0], V: [0, y0 - y1, 0], nom: "droite" },
    { N: [1, 0, 0], O: [x1, y1, z1], U: [0, 0, z0 - z1], V: [0, y0 - y1, 0], nom: "gauche" },
    { N: [0, 1, 0], O: [x0, y1, z0], U: [x1 - x0, 0, 0], V: [0, 0, z1 - z0], nom: "haut" },
    { N: [0, -1, 0], O: [x0, y0, z1], U: [x1 - x0, 0, 0], V: [0, 0, z0 - z1], nom: "bas" },
  ];
  for (const f of faces) {
    const lu = Math.hypot(...f.U), lv = Math.hypot(...f.V);
    const nu = Math.max(1, Math.round(lu / pas)), nv = Math.max(1, Math.round(lv / pas));
    const l = sc.lumiere(T.n(f.N), true);
    for (let j = 0; j < nv; j++) for (let i = 0; i < nu; i++) {
      const c = couleur(f.nom, (i + 0.5) / nu, (j + 0.5) / nv, i, j);
      if (!c) continue;
      const p = (u, v) => T.p(ajoute(ajoute(f.O, fois(f.U, u / nu)), fois(f.V, v / nv)));
      sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], clair(c, l), decale);
    }
  }
}

/** Une couleur d'os, un peu irrégulière, plus sombre au bord des faces. */
const os = (graine) => (face, u, v, i, j) => {
  const bord = Math.min(u, v, 1 - u, 1 - v) < 0.12 ? 0.82 : 1;
  return clair(OS, bord * (0.94 + 0.12 * hache(i + graine, j * 3 + graine, 17)));
};
/** Le rouge de la bête : du bruit rouge sombre, comme sa peau. */
const rouge = (graine) => (face, u, v, i, j) => clair(ROUGE, 0.7 + 0.55 * hache(i * 7 + graine, j * 13 + graine, 5));

/** Une boîte orientée : son centre, ses trois axes (unitaires) et ses demi-tailles. */
function boite(sc, c, axes, demi, couleur, pas = 0.6) {
  const [ax, ay, az] = axes;
  const [hx, hy, hz] = demi;
  // chaque face : sa normale et sa demi-épaisseur, ses deux axes et leurs demi-tailles
  const faces = [
    [az, hz, ax, hx, ay, hy], [fois(az, -1), hz, fois(ax, -1), hx, ay, hy],
    [ax, hx, fois(az, -1), hz, ay, hy], [fois(ax, -1), hx, az, hz, ay, hy],
    [ay, hy, ax, hx, az, hz], [fois(ay, -1), hy, ax, hx, fois(az, -1), hz],
  ];
  faces.forEach(([n, hn, u, hu, v, hv], k) => {
    const cf = ajoute(c, fois(n, hn));
    const nu = Math.max(1, Math.round((2 * hu) / pas)), nv = Math.max(1, Math.round((2 * hv) / pas));
    const l = sc.lumiere(n, true);
    for (let j = 0; j < nv; j++) for (let i = 0; i < nu; i++) {
      const p = (a, b) => ajoute(ajoute(cf, fois(u, -hu + (2 * hu * a) / nu)), fois(v, -hv + (2 * hv * b) / nv));
      const col = couleur(k, (i + 0.5) / nu, (j + 0.5) / nv, i, j);
      if (col) sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], clair(col, l), 0, true);
    }
  });
}

/** Une base orthonormée dont le premier axe est d. */
function repere(d) {
  const a = norme(d);
  let b = croix(a, [0, 1, 0]);
  if (Math.hypot(...b) < 0.2) b = croix(a, [1, 0, 0]);
  b = norme(b);
  return [b, croix(b, a), a];
}

// ------------------------------------------------------------------ forme 1 : la bête aux os
/** La pose de la bête au repos : griffes levées devant elle. */
const BRAS_BETE = [-0.95, 0.3];
const BETE_OPTS = { global: 1.15, tete: 1.35 };

/**
 * Les six queues : épaisses, rouges, en éventail derrière elle comme des flammes (larges au premier tiers, puis en
 * pointe), qui montent en se courbant et ondulent.
 */
function queues(sc, t, k = 1) {
  const angles = [[-72, 32], [-44, 52], [-15, 66], [15, 66], [44, 52], [72, 32]];
  angles.forEach(([lacet, tangage], n) => {
    let p = [0, 13.5 * 1.15, -2.3 * 1.15];
    const la = (lacet * Math.PI) / 180, ta = (tangage * Math.PI) / 180;
    let d = norme([Math.sin(la) * Math.cos(ta), Math.sin(ta), -Math.cos(la) * Math.cos(ta)]);
    const morceaux = 14, longueur = 2.2 * 1.15 * 16 * k, pas = longueur / morceaux;
    for (let i = 0; i < morceaux; i++) {
      const f = i / morceaux;
      const forme = f < 0.3 ? 1 + f : f < 0.6 ? 1.3 : 1.3 * (1 - (f - 0.6) / 0.4) * 0.92 + 0.1;
      const l = 0.42 * 1.15 * 16 * 1.2 * forme * Math.max(0.3, k);
      // elle se redresse peu à peu, et ondule de côté
      d = norme(ajoute(d, [0, 0.07 + 0.03 * Math.sin(t * 1.5 - i * 0.35 + n * 1.1), 0]));
      d = norme(tourner(d, [0, 1, 0], 0.07 * Math.sin(t * 2 - i * 0.42 + n * 2.3)));
      const c = ajoute(p, fois(d, pas * 0.5));
      const [a1, a2, a3] = repere(d);
      // un peu aplatie comme une flamme : large dans le plan de l'éventail, un peu moins d'avant en arrière
      boite(sc, c, [a1, a2, a3], [l / 2, l * 0.42, pas * 0.8], rouge(n * 31 + i * 7), 1.1);
      p = ajoute(p, fois(d, pas));
    }
  });
}

/** Le squelette par-dessus la bête : le crâne, la cage, la colonne, les os des membres et les griffes. */
function squelette(sc) {
  const tete = pose("tete", BRAS_BETE, BETE_OPTS), corps = pose("corps", BRAS_BETE, BETE_OPTS);
  const brasD = pose("brasD", BRAS_BETE, BETE_OPTS), brasG = pose("brasG", BRAS_BETE, BETE_OPTS);
  // le crâne : une coque qui enferme la tête, percée aux yeux et à la gueule (on y voit les yeux et les dents de la bête)
  pave(sc, [-4.6, 25.4, -4.6], [4.6, 32.8, 4.6], tete, (face, u, v, i, j) => {
    if (face === "devant") {
      const x = -4.6 + u * 9.2, y = 32.8 - v * 7.4;
      if (Math.hypot((Math.abs(x) - 2) / 1.6, (y - 28.6) / 1.3) < 1) return null;
      if (y < 27 && Math.abs(x) < 3.4) return null;
      if (Math.abs(x) < 0.5 && y > 27 && y < 28) return OS_SOMBRE;
    }
    return os(1)(face, u, v, i, j);
  }, 0.4, 0.02);
  // l'arcade au-dessus des yeux, la crête sur le dessus, les pommettes
  pave(sc, [-4.9, 29.8, 4.4], [4.9, 30.6, 5.3], tete, os(2), 0.4);
  for (let z = -3.5; z <= 3.5; z += 1.75) pave(sc, [-0.6, 32.8, z - 0.6], [0.6, 33.8 + (3.5 - Math.abs(z)) * 0.25, z + 0.6], tete, os(3), 0.4);
  for (const s of [-1, 1]) pave(sc, [s > 0 ? 4.4 : -5.2, 26.6, 1.5], [s > 0 ? 5.2 : -4.4, 28.2, 4.6], tete, os(4), 0.4);
  // la mâchoire, ouverte, et ses dents
  pave(sc, [-4.3, 23.2, -2], [4.3, 24.4, 5.2], tete, os(5), 0.4);
  for (let x = -3; x <= 3; x += 1) {
    pave(sc, [x - 0.35, 24.4, 4.4], [x + 0.35, 25.5, 5.1], tete, () => DENT, 0.35);
    pave(sc, [x - 0.35, 26.0, 4.4], [x + 0.35, 27.0, 5.1], tete, () => DENT, 0.35);
  }
  // la cage : le sternum, trois côtes qui entourent le torse, la colonne et ses pointes dans le dos
  pave(sc, [-0.6, 14.5, 2.2], [0.6, 23.5, 2.9], corps, os(6), 0.5);
  for (const y of [22, 19.5, 17]) {
    pave(sc, [-4.4, y - 0.5, 2.2], [4.4, y + 0.5, 2.8], corps, os(7 + y), 0.5);
    for (const s of [-1, 1]) pave(sc, [s > 0 ? 4.1 : -4.7, y - 0.5, -2.4], [s > 0 ? 4.7 : -4.1, y + 0.5, 2.8], corps, os(8 + y), 0.5);
  }
  for (let y = 12.5; y < 24; y += 1.7) {
    pave(sc, [-0.8, y, -2.9], [0.8, y + 1.2, -2.1], corps, os(9 + y), 0.4);
    pave(sc, [-0.35, y + 0.3, -4.1], [0.35, y + 0.9, -2.9], corps, os(10 + y), 0.35);
  }
  // le bassin
  pave(sc, [-4.5, 12, -2.6], [4.5, 13.2, 2.6], corps, os(11), 0.5);
  // les bras : un os le long du dessus, l'épaulière, et trois longues griffes au bout de la main
  for (const [s, T] of [[-1, brasD], [1, brasG]]) {
    const x0 = s > 0 ? 8.1 : -9.5, x1 = s > 0 ? 9.5 : -8.1;
    pave(sc, [x0, 13.5, -0.7], [x1, 23.5, 0.7], T, os(12 + s), 0.45);
    pave(sc, [s > 0 ? 3.6 : -9.2, 23.6, -2.6], [s > 0 ? 9.2 : -3.6, 25, 2.6], T, os(13 + s), 0.5);
    pave(sc, [s > 0 ? 7.8 : -9.8, 17.4, -1.1], [s > 0 ? 9.8 : -7.8, 19, 1.1], T, os(14 + s), 0.45);
    for (const dz of [-1.3, 0, 1.3]) {
      const x = s * 6 + (dz === 0 ? 0 : s * 0.3);
      pave(sc, [x - 0.4, 8.6, dz - 0.4], [x + 0.4, 12, dz + 0.4], T, os(15 + dz * 3), 0.35);
      pave(sc, [x - 0.3, 7.2, dz + 0.1], [x + 0.3, 8.6, dz + 1.1], T, os(16 + dz * 3), 0.3);
    }
  }
  // les jambes : un os sur le côté, le genou, et des griffes devant les pieds
  for (const s of [-1, 1]) {
    pave(sc, [s > 0 ? 4.1 : -5.3, 1, -0.6], [s > 0 ? 5.3 : -4.1, 11.5, 0.6], corps, os(17 + s), 0.45);
    pave(sc, [s * 2 - 1.3, 5.2, 2], [s * 2 + 1.3, 7, 2.9], corps, os(18 + s), 0.45);
    for (const dx of [-1.2, 0, 1.2]) pave(sc, [s * 2 + dx - 0.3, 0, 2], [s * 2 + dx + 0.3, 0.6, 3.6], corps, os(19 + dx * 5), 0.3);
  }
}

function oreilles(sc) {
  const tete = pose("tete", BRAS_BETE, BETE_OPTS);
  for (const s of [-1, 1]) {
    const b = tete.p([s * 2.6, 32.6, -1]);
    meche(sc, b, tete.n([s * 0.45, 1, -0.25]), 5.5 * 1.15, 3 * 1.15, clair(ROUGE, 0.95));
  }
}

/** Une boule d'énergie : un cœur et un bord qui brille (plus clair là où on la voit de biais). */
function boule(sc, c, r, coeur, bord) {
  const lat = 16, lon = 24;
  for (let i = 0; i < lat; i++) for (let j = 0; j < lon; j++) {
    const pt = (a, b) => {
      const p = -Math.PI / 2 + (Math.PI * a) / lat, q = (2 * Math.PI * b) / lon;
      return [Math.cos(p) * Math.cos(q), Math.sin(p), Math.cos(p) * Math.sin(q)];
    };
    const ns = [pt(i, j), pt(i + 1, j), pt(i + 1, j + 1), pt(i, j + 1)];
    const n = norme(ns.reduce((s, v) => ajoute(s, v), [0, 0, 0]));
    const vue = sc.dirVue(n);
    const rim = Math.pow(1 - Math.min(1, Math.abs(vue[2])), 1.6);
    sc.quad(ns.map((v) => ajoute(c, fois(v, r))), mix(coeur, bord, rim));
  }
}

function forme1(sc, t, bombe = 0) {
  queues(sc, t);
  dessinerPeau(sc, BETE, 4, BRAS_BETE, BETE_OPTS);
  squelette(sc);
  oreilles(sc);
  if (bombe > 0) {
    boule(sc, [0, 28 * 1.15 - 3, 13], 6.2 * bombe, [70, 12, 96], [236, 120, 255]);
    for (let k = 0; k < 6; k++) {
      const a = k * 1.05 + t * 3;
      boule(sc, [Math.cos(a) * 10, 30 + Math.sin(a * 1.3) * 3, 13 + Math.sin(a) * 4], 1.1, [90, 20, 120], [230, 140, 255]);
    }
  }
  sc.lueur([255, 40, 20], 4, 1.1);
}

// ------------------------------------------------------------------ forme 2 : les huit tentacules
/**
 * Une tentacule : un tube qui part de base dans la direction d0 et s'enroule autour de axe, de plus en plus vers la
 * pointe (sa pointe fait une spirale). Le côté intérieur, plus clair, porte les ventouses.
 */
function tentacule(sc, base, d0, axe, L, r0, c0, c1, n = 48, vague = 0, phase = 0) {
  const pas = L / n;
  let p = base, d = norme(d0);
  const anneaux = [];
  for (let i = 0; i <= n; i++) {
    const s = i / n;
    const N = norme(croix(axe, d)), B = norme(croix(d, N));
    const r = r0 * (1 - 0.86 * Math.pow(s, 0.85)) + 0.2;
    anneaux.push({ p, N, B, r, s });
    d = norme(tourner(d, axe, (c0 + c1 * Math.pow(s, 2.5) + vague * Math.sin(2 * Math.PI * s * 1.2 + phase)) * pas));
    p = ajoute(p, fois(d, pas));
  }
  const m = 12;
  for (let i = 0; i < n; i++) {
    const a = anneaux[i], b = anneaux[i + 1];
    for (let j = 0; j < m; j++) {
      const t0 = (2 * Math.PI * j) / m, t1 = (2 * Math.PI * (j + 1)) / m;
      const sur = (o, th) => ajoute(o.p, ajoute(fois(o.N, Math.cos(th) * o.r), fois(o.B, Math.sin(th) * o.r)));
      const tm = (t0 + t1) / 2;
      const nrm = norme(ajoute(fois(a.N, Math.cos(tm)), fois(a.B, Math.sin(tm))));
      // le dessous (vers l'intérieur de l'enroulement) est plus clair
      const dessous = Math.max(0, Math.cos(tm));
      const c = mix(PEAU_POULPE, DESSOUS_POULPE, Math.pow(dessous, 1.5));
      sc.quad([sur(a, t0), sur(b, t0), sur(b, t1), sur(a, t1)], clair(c, sc.lumiere(nrm, true) * (0.96 + 0.06 * hache(i, j, 3))), 0, true);
    }
    // les ventouses : deux rangées sur le dessous
    if (i % 3 === 1 && a.s < 0.94) {
      for (const dt of [-0.42, 0.42]) {
        const nrm = norme(ajoute(fois(a.N, Math.cos(dt)), fois(a.B, Math.sin(dt))));
        const t2 = norme(croix(nrm, a.B)), u2 = norme(croix(nrm, t2));
        const centre = ajoute(a.p, fois(nrm, a.r + 0.06));
        const rv = Math.max(0.35, a.r * 0.5);
        for (const [rr, col, ec] of [[rv, BORD_VENTOUSE, 0.02], [rv * 0.62, VENTOUSE, 0.06]]) {
          const ctr = ajoute(centre, fois(nrm, ec));
          for (let k = 0; k < 8; k++) {
            const q0 = (2 * Math.PI * k) / 8, q1 = (2 * Math.PI * (k + 1)) / 8;
            const pt = (q) => ajoute(ctr, ajoute(fois(t2, Math.cos(q) * rr), fois(u2, Math.sin(q) * rr)));
            sc.quad([ctr, pt(q0), pt(q1), ctr], clair(col, sc.lumiere(nrm, true)), 0.01, true);
          }
        }
      }
    }
  }
}

/**
 * Les huit tentacules, en éventail derrière lui : φ l'angle depuis le haut (vers sa gauche, +x), le côté vers lequel
 * la pointe s'enroule, la longueur. t les fait onduler.
 */
const TENTACULES = [
  [-16, 1, 88, -0.45], [16, -1, 88, -0.45], [-48, 1, 94, -0.3], [48, -1, 94, -0.3], [-86, -1, 90, -0.15], [86, 1, 90, -0.15],
  [-128, -1, 80, -0.35], [128, 1, 80, -0.35],
];
function tentacules(sc, t, sauf = -1) {
  TENTACULES.forEach(([phi, sens, L, recul], k) => {
    if (k === sauf) return;
    const f = (phi * Math.PI) / 180;
    const base = [Math.sin(f) * 2.4, 13 + Math.cos(f) * 1.4, -2.4];
    const d0 = norme([Math.sin(f), Math.cos(f), recul]);
    // elles ne s'enroulent pas toutes dans le même plan : un peu vers l'avant ou l'arrière, selon la tentacule
    const axe = norme([0.35 * Math.cos(k * 1.7), 0.35 * Math.sin(k * 2.3), sens]);
    const onde = 0.003 * Math.sin(t * 1.6 + k * 0.8);
    tentacule(sc, base, d0, axe, L, 4.8, 0.0015 + onde, 0.11, 48, 0.018, k * 1.3 + t * 1.2);
  });
}

function forme2(sc, t, opts = {}) {
  tentacules(sc, t, opts.sauf ?? -1);
  dessinerPeau(sc, STEVE, 1, opts.bras ?? [0, 0], { couche2: true });
  sc.lueur([255, 120, 150], 3, 0.5);
}

/** Une cible : un joueur debout (ou qui vole, renversé), quelque part devant. */
function cible(sc, decale, lacet = Math.PI, penche = 0) {
  for (const partie of PARTIES(false)) {
    for (const k of [2, 3]) {
      for (const tx of texels(partie[1], partie[k], k === 3 ? partie[4] : 0, 1)) {
        const i = (tx.ty * 64 + tx.tx) * 4;
        if (STEVE.px[i + 3] < 128) continue;
        const T = (v) => ajoute(rotY(rotX(sous(v, [0, 12, 0]), penche), lacet), ajoute(decale, [0, 12, 0]));
        const n = rotY(rotX(tx.N, penche), lacet);
        const l = sc.lumiere(n, false);
        if (l < 0) continue;
        sc.quad(tx.coins.map(T), clair([STEVE.px[i], STEVE.px[i + 1], STEVE.px[i + 2]], l * 0.92), k === 3 ? 0.02 : 0);
      }
    }
  }
}

/** Clic gauche : une tentacule fouette tout droit, loin (huit blocs), sur une seule cible. */
function coupTentacule(sc, t) {
  forme2(sc, t, { sauf: 2, bras: [-0.3, 0] });
  tentacule(sc, [-2.4, 14, -2.4], norme([-0.12, 0.08, 1]), norme([1, 0.2, 0]), 132, 4.8, -0.0015, 0.05);
  cible(sc, [-4, 0, 128], Math.PI, -0.45);
}

/** Clic droit : la grande tentacule balaie tout ce qui est devant lui, et les envoie voler. */
function balayage(sc, t) {
  forme2(sc, t, { sauf: 3 });
  // la traînée du balayage, en arc devant lui
  for (let k = 0; k < 48; k++) {
    const a0 = -1.2 + (k / 48) * 2.5, a1 = a0 + 0.055;
    const p = (a, r) => [Math.sin(a) * r, 10, Math.cos(a) * r];
    sc.quad([p(a0, 40), p(a1, 40), p(a1, 76), p(a0, 76)], mix([250, 246, 246], [222, 170, 182], 1 - k / 48), 0, false);
  }
  tentacule(sc, [2.4, 13, -2.4], norme([1, 0.02, 0.9]), norme([0, 1, 0.1]), 96, 5.6, 0.012, 0.05);
  cible(sc, [-52, 16, 46], Math.PI * 0.7, 1.3);
  cible(sc, [-8, 22, 74], Math.PI, 1.5);
  cible(sc, [40, 12, 52], Math.PI * 1.3, 1.2);
}

/** Accroupi : la bombe noire, devant lui, que les tentacules nourrissent. */
function bombeNoire(sc, t) {
  forme2(sc, t, { bras: [-1.3, -0.15] });
  boule(sc, [0, 24, 22], 12, [6, 3, 10], [190, 40, 130]);
  for (let k = 0; k < 8; k++) {
    const a = k * 0.8 + t * 3;
    boule(sc, [Math.cos(a) * 18, 26 + Math.sin(a * 1.3) * 7, 22 + Math.sin(a) * 7], 1.6, [10, 6, 14], [160, 40, 120]);
  }
}

// ------------------------------------------------------------------ la planche
const FOND = [236, 236, 242];
if (process.argv.includes("anim")) {
  const dossier = path.join(ICI, "images");
  fs.rmSync(dossier, { recursive: true, force: true });
  fs.mkdirSync(dossier, { recursive: true });
  const N = 48;
  for (let i = 0; i < N; i++) {
    const t = i / 12;
    const sc = scene({ lacet: -28 + (i / N) * 360, tangage: 8, echelle: 3.4, w: 560, h: 560, pied: 380, fond: FOND });
    forme2(sc, t);
    ecrirePng(path.join(dossier, String(i).padStart(3, "0") + ".png"), sc.w, sc.h, sc.img);
  }
  console.log("images", N);
} else {
  const CW = 440, CH = 520, M = 34;
  const vues = [
    ["FORME 1", (sc) => forme1(sc, 0), { lacet: -28 }],
    ["FORME 1 DE DOS", (sc) => forme1(sc, 0.7), { lacet: 155 }],
    ["FORME 1 CRANE", (sc) => forme1(sc, 0), { lacet: -18, echelle: 16, pied: 800, tangage: 6 }],
    ["FORME 1 BOMBE", (sc) => forme1(sc, 0.3, 1.3), { lacet: -40 }],
    ["FORME 1 PROFIL", (sc) => forme1(sc, 1.2), { lacet: -95 }],
    ["FORME 2", (sc) => forme2(sc, 0), { lacet: -28, echelle: 2.9, pied: 330, centre: [0, 18, 0] }],
    ["FORME 2 DE DOS", (sc) => forme2(sc, 0.8), { lacet: 160, echelle: 2.9, pied: 330, centre: [0, 18, 0] }],
    ["CLIC GAUCHE", (sc) => coupTentacule(sc, 0.3), { lacet: -78, echelle: 2.4, pied: 320, tangage: 14, centre: [0, 0, 62] }],
    ["CLIC DROIT", (sc) => balayage(sc, 0.3), { lacet: -10, echelle: 2.6, pied: 330, tangage: 34, centre: [0, 0, 24] }],
    ["ACCROUPI", (sc) => bombeNoire(sc, 0.3), { lacet: -40, echelle: 2.9, pied: 330, centre: [0, 18, 6] }],
  ];
  const colonnes = 5, lignes = 2;
  const W = CW * colonnes, H = (M + CH) * lignes;
  const planche = Buffer.alloc(W * H * 4, 255);
  vues.forEach(([titre, dessin, o], k) => {
    const c = k % colonnes, l = Math.floor(k / colonnes);
    const sc = scene({ w: CW, h: CH, echelle: 5.4, pied: 470, fond: FOND, ...o });
    dessin(sc);
    for (let y = 0; y < CH; y++) sc.img.copy(planche, ((l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(planche, W, titre, c * CW + 12, l * (M + CH) + 10, 3, [30, 30, 40, 255]);
  });
  ecrirePng(path.join(ICI, "..", "planche-hachibi2.png"), W, H, planche);
  console.log("ok", W, H);
}
