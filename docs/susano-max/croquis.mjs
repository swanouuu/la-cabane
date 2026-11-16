// Croquis de toutes les attaques du Susano niveau 3 final (le chevalier tel qu'il est en jeu, rien de changé dans son look).
// Mêmes briques que docs/susano3/generateur/rendu.mjs.
// node croquis.mjs → images/*.png (une image par attaque)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, hache } from "../hachibi2/generateur/moteur.mjs";
import { ecran, polygone, opaque, melange, ligne, briller, sol, joueur, boite, dessinerChakra, lame, susano, flammes, COULEURS, FINI, POSES } from "../susano3/generateur/rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const SASUKE = lire(path.join(ICI, "..", "marque-forme2", "generateur", "skins", "sasuke.png"));
const STEVE = lire(path.join(ICI, "..", "hachibi2", "generateur", "skins", "steve.png"));
const SORTIE = path.join(ICI, "images");
const W = 440, H = 400;
const C = COULEURS.violet;

const DEG = Math.PI / 180;
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const len = (a) => Math.hypot(a[0], a[1], a[2]);
const norm = (a) => mul(a, 1 / (len(a) || 1));
const lerp = (a, b, t) => add(a, mul(sub(b, a), t));
const mix = (a, b, k) => a.map((v, i) => v + (b[i] - v) * k);
const rotX = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
const BLANC = [255, 255, 255], VIF = mix(C.contour, BLANC, 0.6), PALE = mix(C.corps, C.contour, 0.5);

// ------------------------------------------------------------------ les poses en plus (mêmes bras, mêmes épaules)
const BOUCLIER_REPOS = { E: [2.95, 3.2, -0.6], W: [2.7, 2.6, 0.9], bouclier: true };
Object.assign(POSES, {
  // le grand coup : l'épée abattue devant lui, la pointe dans le sol
  abattre: { D: { E: [-2.2, 3.6, 2.0], W: [-0.8, 2.4, 3.6], lame: norm([0.15, -0.55, 1]) }, G: BOUCLIER_REPOS },
  // la météorite / le Tsukuyomi : le bras levé, l'épée vers le ciel
  leve: { D: { E: [-2.9, 5.7, 0.5], W: [-2.7, 7.7, 1.2], lame: norm([0.05, 1, 0.25]) }, G: BOUCLIER_REPOS },
  // la lance : le bras tendu devant, la main vide (l'épée est partie)
  lance: { D: { E: [-2.3, 4.7, 1.4], W: [-1.4, 4.6, 3.3] }, G: { E: [2.95, 3.4, -0.9], W: [2.9, 2.8, 0.6], bouclier: true } },
  // la ruée : le bouclier devant lui, l'épée en arrière
  ruee: { D: { E: [-3.0, 3.4, -1.0], W: [-3.2, 2.6, 0.4], lame: norm([-0.2, -0.2, -1]) }, G: { E: [1.6, 4.0, 1.7], W: [-0.5, 4.0, 2.6], bouclier: true } },
});

/** Le chevalier, tourné/penché puis placé : (boîtes, polygones et traits). */
function chevalier(pose, { pos = [0, 0, 0], penche = 0, t = 0 } = {}) {
  const f = (p) => add(rotX(p, penche * DEG), pos), fd = (d) => rotX(d, penche * DEG);
  return susano(FINI, pose, "violet", t).map((b) =>
    b.poly ? { ...b, poly: b.poly.map(f) } : b.trait ? { ...b, trait: b.trait.map(f) } : { ...b, c: f(b.c), ax: b.ax.map(fd) });
}

/** La traînée d'un coup d'épée (comme dans rendu.mjs). */
function trainee(E, P) {
  const [a0, a1, sens] = P.trainee, y = P.D.W[1] + 0.1;
  for (let i = 0; i < 30; i++) {
    const t0 = i / 30, t1 = (i + 1) / 30;
    const b0 = (a0 + (a1 - a0) * t0) * DEG, b1 = (a0 + (a1 - a0) * t1) * DEG;
    const p = (b, r, t) => [Math.sin(b) * r, y + 1.4 * (0.5 - t) * sens * (r / 7), Math.cos(b) * r];
    const a = 0.03 + 0.22 * t1 * t1, av = 0.1 + 0.8 * t1 * t1;
    polygone(E, [p(b0, 3.2, t0), p(b1, 3.2, t1), p(b1, 6.9, t1), p(b0, 6.9, t0)], (i2, zz) => { if (zz < E.z[i2]) melange(E, i2, PALE, a, 0.2); });
    polygone(E, [p(b0, 6.9, t0), p(b1, 6.9, t1), p(b1, 7.4, t1), p(b0, 7.4, t0)], (i2, zz) => { if (zz < E.z[i2]) melange(E, i2, VIF, av, 0.6); });
  }
}

/** Un pavé opaque (min, max), ombré face par face. */
function pave(E, a, b, c) {
  const [x0, y0, z0] = a, [x1, y1, z1] = b;
  const F = [
    [[[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]], 1], [[[x0, y0, z0], [x0, y0, z1], [x1, y0, z1], [x1, y0, z0]], 0.5],
    [[[x0, y0, z1], [x0, y1, z1], [x1, y1, z1], [x1, y0, z1]], 0.8], [[[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0]], 0.8],
    [[[x1, y0, z0], [x1, y0, z1], [x1, y1, z1], [x1, y1, z0]], 0.68], [[[x0, y0, z0], [x0, y1, z0], [x0, y1, z1], [x0, y0, z1]], 0.68],
  ];
  for (const [pts, k] of F) opaque(E, pts, c.map((v) => v * k));
}

/** Un pavé translucide qui brille (les éclats de chakra, les plumes). */
function voile(E, pts, c, a, g = 0) {
  polygone(E, pts, (i, zz) => { if (zz < E.z[i]) melange(E, i, c, a, g); });
}

/** Un anneau au sol (l'onde de choc) : une bande translucide et un bord vif. */
function anneau(E, centre, r, epais, c, a, y = 0.08) {
  const N = 72;
  for (let i = 0; i < N; i++) {
    const b0 = (i / N) * Math.PI * 2, b1 = ((i + 1) / N) * Math.PI * 2;
    const p = (b, rr) => add(centre, [Math.cos(b) * rr, y, Math.sin(b) * rr]);
    voile(E, [p(b0, r - epais), p(b1, r - epais), p(b1, r), p(b0, r)], c, a, 0.4);
    ligne(E, p(b0, r), p(b1, r), BLANC, 0.9, 0.9);
  }
}

/** Un disque à l'écran, autour d'un point (la sphère noire, la lune). */
function disque(E, centre, rayon, c, a, g = 0, bord = null) {
  const q = E.proj(centre);
  if (!q) return null;
  const R = (rayon * E.F) / q.zc;
  for (let y = Math.max(0, Math.floor(q.y - R - 2)); y <= Math.min(E.h - 1, Math.ceil(q.y + R + 2)); y++) {
    for (let x = Math.max(0, Math.floor(q.x - R - 2)); x <= Math.min(E.w - 1, Math.ceil(q.x + R + 2)); x++) {
      const d = Math.hypot(x - q.x, y - q.y);
      if (d > R + 1.5) continue;
      const i = y * E.w + x;
      if (bord && d > R - 2.5) melange(E, i, bord, 0.9, 0.8);
      else if (d <= R) melange(E, i, c, a * (0.75 + 0.25 * (1 - d / R)), g);
    }
  }
  return { x: q.x, y: q.y, R };
}

/** Un trait à l'écran (les fissures). */
function trait2d(E, x0, y0, x1, y1, c, a, g = 0, epais = 1) {
  const n = Math.ceil(Math.hypot(x1 - x0, y1 - y0)) + 1; g *= 0.35;
  for (let k = 0; k <= n; k++) {
    const t = k / n, x = Math.round(x0 + (x1 - x0) * t), y = Math.round(y0 + (y1 - y0) * t);
    for (let dx = 0; dx < epais; dx++) for (let dy = 0; dy < epais; dy++) {
      const xx = x + dx, yy = y + dy;
      if (xx >= 0 && yy >= 0 && xx < E.w && yy < E.h) melange(E, yy * E.w + xx, c, a, g);
    }
  }
}

/** Des fissures qui partent d'un point, et se divisent. */
function fissures(E, x, y, n, longueur, c, graine, epais = 2) {
  const branche = (x0, y0, ang, l, prof, s) => {
    if (prof > 3 || l < 6) return;
    let px = x0, py = y0;
    const pas = 5;
    for (let d = 0; d < l; d += pas) {
      ang += (hache(s, d, graine) - 0.5) * 0.7;
      const nx = px + Math.cos(ang) * pas, ny = py + Math.sin(ang) * pas;
      trait2d(E, px, py, nx, ny, c, 0.95, 1.2, Math.max(1, epais - prof));
      if (hache(s, d, graine + 7) < 0.06) branche(nx, ny, ang + (hache(s, d, 3) < 0.5 ? -0.8 : 0.8), l * 0.5, prof + 1, s * 7 + d);
      px = nx; py = ny;
    }
  };
  for (let i = 0; i < n; i++) branche(x, y, (i / n) * Math.PI * 2 + hache(i, 1, graine), longueur * (0.6 + 0.6 * hache(i, 2, graine)), 0, i + 1);
}

/** Des flammes noires sur un joueur et autour de ses pieds. */
const brule = (pos, n = 18, g = 0) => [
  ...Array.from({ length: n }, (_, i) => [add(pos, [(hache(i, 1, 1 + g) - 0.5) * 0.8, 0.1 + hache(i, 2, 2 + g) * 1.7, (hache(i, 3, 3 + g) - 0.5) * 0.6]), 0.22 + hache(i, 4, 4 + g) * 0.3, i]),
  ...Array.from({ length: 8 }, (_, i) => [add(pos, [Math.cos(i * 0.8) * (0.9 + hache(i, 5, 5 + g)), 0, Math.sin(i * 0.8) * (0.9 + hache(i, 6, 6 + g))]), 0.3 + hache(i, 7, 7 + g) * 0.25, i + 40]),
];

/** Un cercle de flammes noires au sol. */
const cercle = (centre, r, n, h, g) => Array.from({ length: n }, (_, i) => {
  const a = (i / n) * Math.PI * 2 + hache(i, 1, g) * 0.2, rr = r * (0.85 + 0.3 * hache(i, 2, g));
  return [add(centre, [Math.cos(a) * rr, 0, Math.sin(a) * rr]), h * (0.6 + 0.8 * hache(i, 3, g)), i + g * 100];
});

/** Le corbeau (de profil, ailes ouvertes) : des pavés noirs, le bec et l'œil rouge. */
function corbeau(E, p, aile = 1) {
  const N = [16, 16, 24], N2 = [38, 38, 54], s = 0.18;
  const b = (x0, y0, z0, x1, y1, z1, c) => pave(E, add(p, [x0 * s, y0 * s, z0 * s]), add(p, [x1 * s, y1 * s, z1 * s]), c);
  b(-2, -2, -5, 2, 2, 4.5, N);
  b(-1.6, -0.6, 4.5, 1.6, 2.8, 8, N2);
  b(-0.5, 0.3, 8, 0.5, 1.3, 11, [70, 62, 52]);
  b(-1.7, 1.4, 6.6, 1.7, 2.1, 7.3, [235, 40, 40]);
  b(-1.4, -0.8, -10, 1.4, 0.4, -5, N);
  for (const cote of [-1, 1]) for (let i = 0; i < 6; i++) {
    const x0 = cote * (2 + i * 2.6), x1 = cote * (2 + (i + 1) * 2.6), y = aile * i * 1.1;
    b(Math.min(x0, x1), y - 0.4, -3.5 + i * 0.5, Math.max(x0, x1), y + 0.6, 3.5 - i * 0.3, i % 2 ? N2 : N);
  }
}

/** Les plumes qui volent (de petits losanges noirs). */
function plumes(E, centre, n, r, g) {
  for (let i = 0; i < n; i++) {
    const p = add(centre, [(hache(i, 1, g) - 0.5) * r * 2, (hache(i, 2, g) - 0.3) * r * 1.4, (hache(i, 3, g) - 0.5) * r * 2]);
    const a = hache(i, 4, g) * Math.PI, l = 0.35 + 0.2 * hache(i, 5, g);
    const d = [Math.cos(a) * l, Math.sin(a) * l * 0.6, 0.1], q = [-Math.sin(a) * 0.09, Math.cos(a) * 0.09, 0];
    voile(E, [sub(p, d), add(p, q), add(p, d), sub(p, q)], [14, 12, 20], 0.95, 0);
  }
}

// ------------------------------------------------------------------ les images
const VUES = {
  forme(E) {
    sol(E);
    joueur(E, SASUKE, [0, 0, 0]);
    dessinerChakra(E, chevalier("repos"), C, 1);
  },
  epee(E) {
    sol(E);
    joueur(E, SASUKE, [0, 0, 0], 0, [-40, 10]);
    joueur(E, STEVE, [1.2, 0, 6.2], 200, [-20, -20], 0.9);
    trainee(E, POSES.coup1);
    dessinerChakra(E, chevalier("coup1"), C, 1);
  },
  arc(E) {
    sol(E, 26);
    joueur(E, SASUKE, [0, 0, 0], 0, [-80, -80]);
    // la flèche : une longue ligne de lumière, et la bande de flammes noires sous sa ligne
    const depart = [0.4, 4.6, 4.2], fin = [3.2, 1.2, 24];
    for (let k = 0; k < 6; k++) ligne(E, add(depart, [0, k * 0.03, 0]), add(fin, [0, k * 0.03, 0]), VIF, 0.6, 1);
    const bande = [];
    for (let i = 0; i < 26; i++) {
      const t = 0.18 + (i / 26) * 0.82, p = lerp(depart, fin, t);
      bande.push([[p[0] + (hache(i, 1, 4) - 0.5) * 1.6, 0, p[2]], 0.35 + 0.35 * hache(i, 2, 4), i]);
    }
    flammes(E, [...bande, ...cercle([3.2, 0, 24], 2.6, 16, 0.9, 3)], 0.4);
    joueur(E, STEVE, [2.2, 0, 15], 200, [-20, -20], 0.8);
    flammes(E, brule([2.2, 0, 15], 14, 2), 0.6);
    dessinerChakra(E, chevalier("arc"), C, 1);
  },
  grandcoup(E) {
    sol(E, 18);
    joueur(E, SASUKE, [0, 0, 0], 0, [-40, 10]);
    anneau(E, [0, 0, 0], 8, 1.4, VIF, 0.28);
    anneau(E, [0, 0, 0], 5.6, 0.6, PALE, 0.18);
    flammes(E, [...cercle([0, 0, 0], 7.2, 40, 1.1, 1), ...cercle([0, 0, 0], 4.6, 22, 0.7, 2)], 0.3);
    for (const [x, z, a] of [[-6.5, 5, 140], [6, 4.5, 220], [2, -7, 10]]) {
      joueur(E, STEVE, [x, 0.8, z], a, [-120, -120], 0.8);
      flammes(E, brule([x, 0.8, z], 10, x | 0), 0.2);
    }
    dessinerChakra(E, chevalier("abattre"), C, 1);
  },
  flammes(E) {
    sol(E, 18);
    joueur(E, SASUKE, [0, 0, 0], 0, [-80, -80]);
    joueur(E, STEVE, [1.5, 0, 13], 200, [-20, -20], 0.6);
    const flaque = Array.from({ length: 30 }, (_, i) => [[1.5 + (hache(i, 1, 8) - 0.5) * 5.5, 0, 13 + (hache(i, 2, 8) - 0.5) * 5.5], 0.3 + 0.4 * hache(i, 3, 8), i + 300]);
    flammes(E, [...flaque, ...brule([1.5, 0, 13], 22, 5)], 0.5);
    dessinerChakra(E, chevalier("arc"), C, 1);
  },
  tsukuyomi(E) {
    // le monde en négatif : sol noir quadrillé de rouge, ciel rouge, lune noire
    for (let x = -14; x < 14; x++) for (let z = -14; z < 14; z++) opaque(E, [[x, 0, z], [x + 1, 0, z], [x + 1, 0, z + 1], [x, 0, z + 1]], [8, 4, 6]);
    for (let k = -14; k <= 14; k++) { ligne(E, [k, 0.01, -14], [k, 0.01, 14], [200, 20, 30], 0.7, 0.4); }
    for (let k = -14; k <= 14; k++) { ligne(E, [-14, 0.01, k], [14, 0.01, k], [200, 20, 30], 0.7, 0.4); }
    disque(E, [-1.5, 8.5, -18], 3.4, [6, 4, 8], 1, 0, [230, 40, 40]);
    // la croix de bois, la cible attachée
    pave(E, [-0.2, 0, 0.6], [0.2, 4.2, 1.0], [96, 64, 36]);
    pave(E, [-1.3, 2.6, 0.6], [1.3, 3.0, 1.0], [96, 64, 36]);
    joueur(E, STEVE, [0, 0.5, 1.25], 0, [0, 0], 0.85);
    for (const y of [1.2, 2.9]) pave(E, [-0.32, 0.5 + y * 0.5, 1.05], [0.32, 0.62 + y * 0.5, 1.5], [70, 46, 26]);
    // lui, devant elle, ses clones qui sortent du sol
    joueur(E, SASUKE, [0.9, 0, 4.2], 195);
    for (const [x, z, h] of [[-2.6, 3.2, 0.9], [3.4, 2.4, 1.3], [-3.6, -1.4, 0.5]]) joueur(E, SASUKE, [x, -2 + h, z], 180 + x * 10, [0, 0], 0.55);
  },
  kamui(E) {
    sol(E, 20);
    joueur(E, SASUKE, [0, 0, 0]);
    dessinerChakra(E, chevalier("repos"), C, 1);
    // la spirale qui l'aspire, autour de la cible
    const c = [2.4, 1.2, 12];
    joueur(E, STEVE, [2.4, 0.3, 12], 200, [-60, -60], 0.5);
    for (let bras = 0; bras < 3; bras++) for (let k = 0; k < 160; k++) {
      const t0 = k / 160, t1 = (k + 1) / 160;
      const p = (t) => { const a = t * 11 + bras * 2.1, r = 4.2 * (1 - t) + 0.08; return add(c, [Math.cos(a) * r * 0.5, Math.sin(a) * r * 0.9, Math.cos(a) * r]); };
      for (const dy of [0, 0.04, 0.08]) ligne(E, add(p(t0), [0, dy, 0]), add(p(t1), [0, dy, 0]), mix([120, 110, 140], BLANC, t0 * 0.7), 0.45 + 0.5 * t0, 0.5);
    }
    disque(E, c, 0.55, [10, 10, 14], 0.95, 0, [170, 170, 190]);
  },
  meteorite(E) {
    sol(E, 32);
    joueur(E, SASUKE, [0, 0, 0], 0, [-170, 0]);
    // les blocs arrachés qui montent vers la sphère
    for (let i = 0; i < 46; i++) {
      const t = hache(i, 1, 11), x = (hache(i, 2, 11) - 0.5) * 22 * (1 - t * 0.8), z = 18 + (hache(i, 3, 11) - 0.5) * 18 * (1 - t * 0.8), y = 2 + t * 18, s = 0.4 + 0.5 * hache(i, 4, 11);
      pave(E, [x - s, y - s, z - s], [x + s, y + s, z + s], hache(i, 5, 11) < 0.5 ? [110, 78, 50] : [120, 120, 124]);
    }
    joueur(E, STEVE, [0, 25, 18], 200, [-120, -120], 0.4);
    const s = disque(E, [0, 26, 18], 6.2, [10, 6, 18], 0.9, 0, [120, 70, 220]);
    if (s) fissures(E, s.x, s.y, 9, s.R * 0.95, [190, 120, 255], 5);
    dessinerChakra(E, chevalier("leve"), C, 1);
  },
  corbeau(E) {
    sol(E, 16);
    // l'encre au sol, là où était son corps
    for (let i = 0; i < 26; i++) {
      const a = hache(i, 1, 21) * Math.PI * 2, r = 0.3 + 2.2 * hache(i, 2, 21), p = [Math.cos(a) * r, 0.03, Math.sin(a) * r], s = 0.25 + 0.35 * hache(i, 3, 21);
      voile(E, [add(p, [-s, 0, -s]), add(p, [s, 0, -s]), add(p, [s, 0, s]), add(p, [-s, 0, s])], [10, 8, 14], 0.9);
    }
    plumes(E, [0, 2.6, 1], 60, 2.6, 4);
    corbeau(E, [0.8, 5.8, 4.5], 0.6);
    plumes(E, [0.8, 5.4, 2.5], 14, 1.4, 9);
  },
  fracture(E) {
    sol(E, 18);
    // le dôme du rembobinage (10 blocs autour)
    for (let k = 0; k < 7; k++) {
      const b = (k / 7) * (Math.PI / 2);
      for (let i = 0; i < 64; i++) {
        const a0 = (i / 64) * Math.PI * 2, a1 = ((i + 1) / 64) * Math.PI * 2, r = 10 * Math.cos(b), y = 10 * Math.sin(b);
        ligne(E, [Math.cos(a0) * r, y, Math.sin(a0) * r], [Math.cos(a1) * r, y, Math.sin(a1) * r], [200, 160, 255], 0.35, 0.3);
      }
    }
    // les blocs qui reviennent à leur place, et un mob qui revient en fantôme
    for (let i = 0; i < 9; i++) {
      const x = 3 + (i % 3), z = 4 + Math.floor(i / 3), y = 0.2 + hache(i, 1, 31) * 3.2;
      pave(E, [x, y, z], [x + 0.9, y + 0.9, z + 0.9], hache(i, 2, 31) < 0.5 ? [110, 78, 50] : [120, 120, 124]);
      ligne(E, [x + 0.45, y, z + 0.45], [x + 0.45, 0, z + 0.45], [220, 190, 255], 0.6, 0.6);
    }
    joueur(E, SASUKE, [0, 0, 0], 10, [-90, 0]);
    for (const [k, a] of [[0, 1], [1, 0.6], [2, 0.35]]) joueur(E, STEVE, [-3 - k * 1.1, 0, 4 + k * 0.6], 150, [-20, -20], a);
  },
  lance(E) {
    sol(E, 30);
    joueur(E, SASUKE, [0, 0, 0], 0, [-80, 10]);
    // l'épée plantée au bout, le pilier de flammes noires autour, les flammes sous sa trajectoire
    const L = [];
    lame(L, [0.3, 5.6, 24], norm([0, -1, 0.22]), 1);
    const trace = [];
    for (let i = 0; i < 22; i++) {
      const z = 5 + i, p = [0.1 + (hache(i, 1, 41) - 0.5) * 0.8, 0, z];
      trace.push([p, 0.3 + 0.3 * hache(i, 2, 41), i + 500]);
    }
    for (let k = 0; k < 4; k++) ligne(E, [-1.2, 4.7 + k * 0.03, 4], [0.3, 4.2 + k * 0.03, 22], VIF, 0.45, 0.8);
    joueur(E, STEVE, [0.6, 0, 14], 200, [-20, -20], 0.8);
    flammes(E, [...trace, ...brule([0.6, 0, 14], 16, 7), ...cercle([0.3, 0, 26], 1.2, 12, 3.6, 4), ...cercle([0.3, 0, 26], 2.6, 18, 2.4, 5), ...cercle([0.3, 0, 26], 3.4, 16, 1.2, 6)], 0.2);
    dessinerChakra(E, [...chevalier("lance"), ...L], C, 1);
  },
  ruee(E) {
    sol(E, 24);
    // les traits de vitesse derrière lui, la poussière
    for (let i = 0; i < 26; i++) {
      const y = 0.5 + hache(i, 1, 51) * 7, x = (hache(i, 2, 51) - 0.5) * 7, z0 = -2 - hache(i, 3, 51) * 3;
      ligne(E, [x, y, z0], [x, y, z0 - 5 - hache(i, 4, 51) * 6], mix(C.contour, BLANC, 0.4), 0.55, 0.4);
    }
    for (let i = 0; i < 14; i++) {
      const p = [(hache(i, 1, 52) - 0.5) * 6, 0.4 + hache(i, 2, 52), -1 - hache(i, 3, 52) * 6];
      voile(E, [add(p, [-0.6, -0.4, 0]), add(p, [0.6, -0.4, 0]), add(p, [0.5, 0.5, 0]), add(p, [-0.5, 0.5, 0])], [150, 140, 120], 0.25);
    }
    joueur(E, SASUKE, [0, 0, 0], 0, [-60, -60]);
    dessinerChakra(E, chevalier("ruee", { penche: 16 }), C, 1);
    // ceux qu'il percute, projetés, sonnés
    for (const [x, y, z, a] of [[1.6, 3.2, 8.2, 160], [-2.6, 2.2, 7.4, 210], [3.8, 1.2, 6.6, 120]]) {
      joueur(E, STEVE, [x, y, z], a, [-150, -150], 0.85);
      for (let k = 0; k < 5; k++) {
        const b = (k / 5) * Math.PI * 2, p = [x + Math.cos(b) * 0.55, y + 2.25, z + Math.sin(b) * 0.55];
        voile(E, [add(p, [-0.09, 0, 0]), add(p, [0, 0.12, 0]), add(p, [0.09, 0, 0]), add(p, [0, -0.12, 0])], [255, 230, 60], 1, 0.8);
      }
    }
  },
};

const CAMERAS = {
  forme: { lacet: -32, distance: 15.2 },
  epee: { lacet: -18, tangage: 20, distance: 15 },
  arc: { lacet: -62, tangage: 10, distance: 25, cible: [1.2, 3.6, 11] },
  grandcoup: { lacet: -30, tangage: 26, distance: 24, cible: [0, 2.5, 1.5] },
  flammes: { lacet: 34, tangage: 10, distance: 8.5, cible: [1.2, 1.6, 11] },
  tsukuyomi: { lacet: -24, tangage: 8, distance: 11, cible: [0, 2.8, 2], ciel: [[150, 10, 18], [60, 4, 10]] },
  kamui: { lacet: -62, tangage: 10, distance: 20, cible: [1.2, 3.4, 7] },
  meteorite: { lacet: -38, tangage: 4, distance: 44, cible: [0, 12, 10], fov: 50 },
  corbeau: { lacet: -40, tangage: 12, distance: 11, cible: [0.5, 3.4, 2.5] },
  fracture: { lacet: -30, tangage: 22, distance: 24, cible: [0, 2.5, 1] },
  lance: { lacet: -80, tangage: 9, distance: 32, cible: [0, 3.6, 14.5], fov: 54 },
  ruee: { lacet: -58, tangage: 10, distance: 21, cible: [0.5, 3.6, 3] },
};

fs.mkdirSync(SORTIE, { recursive: true });
const seules = process.argv.slice(2);
for (const [nom, dessin] of Object.entries(VUES)) {
  if (seules.length && !seules.includes(nom)) continue;
  const E = ecran({ w: W, h: H, ...CAMERAS[nom] });
  dessin(E);
  // la fracture : l'écran se fissure (par-dessus tout)
  if (nom === "fracture") fissures(E, W * 0.58, H * 0.3, 7, 140, [235, 225, 255], 2, 1);
  briller(E, 6, 0.9);
  ecrirePng(path.join(SORTIE, nom + ".png"), W, H, E.img);
  console.log(nom);
}
