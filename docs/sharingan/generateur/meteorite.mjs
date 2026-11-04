// L'ultime du Rinnegan (demandé le 2026-11-04, un ult de niveau 4) : la météorite. Il reste au sol et lève le bras vers le
// ciel ; une petite sphère noire naît très haut devant lui ; elle attire tout : la terre, l'herbe, les pierres, les arbres arrachés au sol (et les joueurs trop près), qui
// montent en spirale, tournent autour et s'agglutinent en une énorme météorite (55 blocs de large). Des fissures violettes
// s'allument, il serre le poing : elle explose. Des blocs pleuvent partout autour, les joueurs aspirés sont éjectés.
// Plan 1 (0 → 5,6 s) : sa caméra, de dos, en contre-plongée. Plan 2 (5,6 → 10,5 s) : ce que voient les autres, en bas.
// node meteorite.mjs video <dossier> [de] [pas] → les images (24 par seconde) ; node meteorite.mjs image <t> [fichier.png] ;
// node meteorite.mjs → planche-meteorite.png.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, ecrireTexte, mix, hache, norme, scene, clair } from "../../fruits/generateur/moteur.mjs";
import { ajoute, fois, sous, rotX, rotY, TOUR, ID, brille, pave, cube, perso, HERBE, TERRE, SKINS, STEVE } from "../../fruits/generateur/rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const borne = (u) => Math.max(0, Math.min(1, u));
const entre = (t, a, b) => borne((t - a) / (b - a));
const sortie = (u) => 1 - (1 - borne(u)) ** 3;
const entree = (u) => borne(u) ** 2;
const lisse = (u) => { u = borne(u); return u * u * (3 - 2 * u); };

// ------------------------------------------------------------------ les couleurs et les repères (16 = un bloc)
const CIEL = [150, 190, 236], CIEL_SOMBRE = [96, 82, 128];
const PIERRE = [128, 128, 132], PIERRE_F = [92, 92, 98], BOIS = [104, 80, 50], FEUILLE = [66, 122, 40], GRAVIER = [140, 130, 124];
const VIOLET = [176, 120, 255], VIOLET_C = [226, 204, 255], VIOLET_F = [90, 40, 160], NOIR = [12, 6, 20], POUSSIERE = [176, 160, 140];
const C = [0, 1000, 1200];          // le cœur de la météorite : 62 blocs de haut, 75 blocs devant lui
const G = [0, 0, 1200];            // le sol sous elle
const RF = 440;                    // le rayon final : 27 blocs (55 de large)
const ZONE = 1100;                 // le sol arraché : 68 blocs autour (lui, au bord, reste sur un sol intact)
const T_EXPLO = 7.0, DUREE = 10.5;

const hauteur = () => 0;
/** Le rayon de la météorite en train de se former, puis serrée juste avant l'explosion. */
const rayon = (t) => (t < 2.2 ? 0 : 50 + (RF - 50) * lisse(entre(t, 2.4, 6.0)) ** 0.8) * (1 - 0.07 * lisse(entre(t, 6.3, 6.95)));

// ------------------------------------------------------------------ les briques
/** Un bloc de Minecraft tourné : l'herbe (dessus vert, côtés terre), la terre, la pierre, le bois, les feuilles. */
function bloc(sc, c, s, [ax, ay], sorte, k = 0, lueur = 0) {
  const T = { p: (v) => ajoute(c, rotY(rotX(v, ax), ay)), n: (v) => norme(rotY(rotX(v, ax), ay)) };
  const base = { herbe: TERRE, terre: TERRE, pierre: PIERRE, bois: BOIS, feuille: FEUILLE, gravier: GRAVIER }[sorte];
  pave(sc, [-s / 2, -s / 2, -s / 2], [s / 2, s / 2, s / 2], T, (f, u, v, i, j) => {
    let col = sorte === "herbe" && f === "haut" ? HERBE : sorte === "herbe" && v < 0.25 ? HERBE : base;
    col = clair(col, 0.85 + 0.25 * hache(i + k, j, FACES_N[f]));
    if (lueur > 0) return brille(mix(col, VIOLET_C, lueur));
    return col;
  }, s / 2, 0, lueur > 0.3);
}
const FACES_N = { devant: 1, dos: 2, gauche: 3, droite: 4, haut: 5, bas: 6 };

/** Un anneau transparent (une onde, la gravité) : centre, normale, rayon, largeur. */
function anneau(sc, c, n, r, l, couleur, alpha, segs = 40) {
  n = norme(n);
  let e1 = norme(Math.abs(n[1]) > 0.9 ? [1, 0, 0] : [n[2], 0, -n[0]]);
  const e2 = [n[1] * e1[2] - n[2] * e1[1], n[2] * e1[0] - n[0] * e1[2], n[0] * e1[1] - n[1] * e1[0]];
  const p = (a, rr) => ajoute(c, ajoute(fois(e1, Math.cos(a) * rr), fois(e2, Math.sin(a) * rr)));
  for (let q = 0; q < segs; q++) {
    const a0 = (q / segs) * TOUR, a1 = ((q + 1) / segs) * TOUR;
    sc.voile([p(a0, r - l / 2), p(a1, r - l / 2), p(a1, r + l / 2), p(a0, r + l / 2)], couleur, alpha);
  }
}

/** Un disque transparent tourné vers la caméra (la boule de lumière de l'explosion, la poussière). */
function disque(sc, c, r, couleur, alpha, vers) {
  const n = norme(sous(vers, c));
  for (let k = 0; k < 4; k++) anneau(sc, c, n, r * (k + 0.5) / 4, r / 4, couleur, alpha * (1 - k * 0.2), 28);
}

function flash(sc, a, c) {
  if (a <= 0) return;
  for (let i = 0; i < sc.w * sc.h; i++) for (let k = 0; k < 3; k++) sc.img[i * 4 + k] = sc.img[i * 4 + k] * (1 - a) + c[k] * a;
}

// ------------------------------------------------------------------ le sol, les arbres
const ARBRES = [[-500, 900], [420, 800], [-200, 1500], [350, 1450], [-700, 1200], [750, 1150], [-600, 300], [980, 600], [-1300, 1900], [1250, 2100], [-300, -500], [1300, -60], [200, 2000], [-950, 1550], [-1500, 600], [1600, 1000]]
  .map(([x, z], k) => ({ x, z, k, pris: Math.hypot(x - G[0], z - G[2]) < ZONE }));

/** Le moment où la case (i, j) du sol est arrachée (ou Infinity), et si elle part en bloc visible. */
function arrache(i, j) {
  const x = i * 16 + 8, z = j * 16 + 8, d = Math.hypot(x - G[0], z - G[2]);
  if (d > ZONE) return Infinity;
  const k = d / ZONE;
  if (hache(i, j, 41) > 0.92 - 0.55 * k * k) return Infinity;
  return 2.35 + 3.0 * k ** 0.9 + 0.5 * hache(i, j, 42);
}

function terrain(sc, t, impacts, cam, ciel) {
  const coul = (x, z, pas) => {
    const c = coulSol(x, z, pas);
    const d = Math.hypot(x - cam[0], z - cam[2]);
    return mix(c, ciel, borne((d - 2200) / 6000));
  };
  const coulSol = (x, z, pas) => {
    const i = Math.floor(x / 16), j = Math.floor(z / 16);
    const ta = arrache(i, j);
    if (t > ta) {
      // un trou : la terre, puis la pierre au fond
      const prof = hache(i, j, 43);
      return clair(prof < 0.4 ? [70, 52, 38] : [74, 74, 80], 0.75 + 0.2 * hache(i, j, 44));
    }
    // le sol tremble et se fend juste avant
    if (t > ta - 0.5 && hache(i, j, 45) < 0.5) return clair([96, 72, 50], 0.9);
    for (const [ix, iz, ti, r] of impacts) if (t > ti && Math.hypot(x - ix, z - iz) < r) return clair([88, 70, 52], 0.8 + 0.2 * hache(i, j, 46));
    return clair(HERBE, 0.86 + 0.16 * hache(Math.floor(x / pas), Math.floor(z / pas), 3));
  };
  // le sol loin est poussé derrière tout le reste (ses grandes cases rasantes se trompent de profondeur)
  const zone = (x0, z0, x1, z1, pas, recule = 0) => pave(sc, [x0, -40, z0], [x1, 0, z1], ID, (f, u, v, i, j) => {
    if (f === "bas") return null;
    if (f !== "haut") return recule ? null : clair(TERRE, 0.85);
    return coul(x0 + (i + 0.5) * pas, z0 + (j + 0.5) * pas, pas);
  }, pas, recule);
  zone(-1280, -640, 1280, 2432, 16);
  zone(-6400, -2400, -1280, 4800, 64, -3000); zone(1280, -2400, 6400, 4800, 64, -3000); zone(-1280, 2432, 1280, 4800, 64, -3000); zone(-1280, -2400, 1280, -640, 64, -3000);
  zone(-16000, 4800, 16000, 20000, 400, -1e6); zone(-16000, -2400, -6400, 4800, 400, -1e6); zone(6400, -2400, 16000, 4800, 400, -1e6);
}

/** Un arbre (tronc de 5 blocs, feuillage), posé ou arraché ; T place ses blocs. */
function arbre(sc, T, k) {
  for (let y = 0; y < 5; y++) bloc(sc, T([0, y * 16 + 8, 0]), 16, T.a, "bois", k * 10 + y);
  for (let x = -2; x <= 2; x++) for (let z = -2; z <= 2; z++) for (let y = 3; y <= 6; y++) {
    if ((Math.abs(x) === 2 && Math.abs(z) === 2) || (y === 6 && (Math.abs(x) > 1 || Math.abs(z) > 1)) || (y < 5 && x === 0 && z === 0)) continue;
    if (hache(x + 9, z + 9, y + k * 7) < 0.12) continue;
    bloc(sc, T([x * 16, y * 16 + 8, z * 16]), 16, T.a, "feuille", k + x * 3 + z * 5 + y);
  }
}

// ------------------------------------------------------------------ ce qui monte : les blocs, les arbres, les joueurs
/** La trajectoire d'un morceau attiré : il monte en spirale autour de la météorite et se colle à sa surface. */
function vol(p0, u, ta, k) {
  const d0 = sous(p0, C), e = lisse(u) ** 1.3, tour = 2.4 + 1.2 * hache(k, 9, 50);
  const dr = rotY(d0, tour * e);
  const n = norme([dr[0], dr[1] * 0.4 + (hache(k, 8, 50) - 0.5) * 900, dr[2]]);
  return ajoute(C, ajoute(fois(dr, 1 - e), ajoute(fois(n, rayon(ta) * e), [0, 200 * Math.sin(Math.PI * u) * (1 - e), 0])));
}

/** La liste des blocs visibles qui volent (un sur cinq des cases arrachées, plus de gros morceaux de pierre). */
const VOLANTS = (() => {
  const l = [];
  for (let i = -72; i <= 72; i++) for (let j = 0; j <= 150; j++) {
    const ta = arrache(i, j);
    if (!isFinite(ta) || hache(i, j, 47) > 0.13) continue;
    const gros = hache(i, j, 48) < 0.12;
    l.push({ p0: [i * 16 + 8, gros ? 16 : 8, j * 16 + 8], tr: ta, duree: 1.0 + 0.7 * hache(i, j, 49), s: gros ? 32 + 16 * Math.floor(hache(i, j, 51) * 2) : 16,
      sorte: gros ? "pierre" : hache(i, j, 52) < 0.6 ? "herbe" : hache(i, j, 52) < 0.85 ? "terre" : "gravier", k: l.length });
  }
  return l;
})();

/** Des morceaux en orbite autour de la météorite (« les pierres qui tournent autour »). */
const ORBITES = Array.from({ length: 120 }, (_, k) => ({
  axe: norme([hache(k, 1, 60) - 0.5, 2.2 + hache(k, 2, 60), hache(k, 3, 60) - 0.5]), a0: hache(k, 4, 60) * TOUR,
  dr: 100 + 280 * hache(k, 5, 60), vit: 1.0 + 1.2 * hache(k, 6, 60), s: 16 + 16 * Math.floor(hache(k, 7, 60) * 3),
  sorte: ["pierre", "terre", "herbe", "gravier", "pierre"][Math.floor(hache(k, 8, 60) * 5)], t0: 2.6 + 2.6 * hache(k, 9, 60),
}));

/** Les blocs de la surface de la météorite (une sphère de Fibonacci), et quelques gros rochers par-dessus. */
const SURFACE = (() => {
  const l = [], n = 1300, or = Math.PI * (3 - Math.sqrt(5));
  for (let k = 0; k < n; k++) {
    const y = 1 - (2 * (k + 0.5)) / n, r = Math.sqrt(1 - y * y), a = k * or;
    const dir = [Math.cos(a) * r, y, Math.sin(a) * r];
    const h = hache(k, 1, 70);
    l.push({ dir, s: 58 + 22 * hache(k, 2, 70), rot: [hache(k, 3, 70) * 3, hache(k, 4, 70) * 3], k, bosse: 1 + 0.08 * (hache(k, 5, 70) - 0.5),
      sorte: h < 0.38 ? "pierre" : h < 0.6 ? "terre" : h < 0.8 ? "herbe" : h < 0.9 ? "gravier" : "bois",
      fissure: [[1, 0.3, 0], [0.2, 1, 0.6], [-0.5, 0.2, 1]].some((ax) => Math.abs(dir[0] * ax[0] + dir[1] * ax[1] + dir[2] * ax[2]) / Math.hypot(...ax) < 0.07),
      v: 300 + 480 * hache(k, 6, 70), spin: [(hache(k, 7, 70) - 0.5) * 10, (hache(k, 8, 70) - 0.5) * 10] });
  }
  for (let k = 0; k < 80; k++) {
    const dir = norme([hache(k, 1, 71) - 0.5, hache(k, 2, 71) - 0.5, hache(k, 3, 71) - 0.5]);
    l.push({ dir, s: 100 + 60 * hache(k, 4, 71), rot: [hache(k, 5, 71) * 3, hache(k, 6, 71) * 3], k: 1000 + k, bosse: 0.96, sorte: "pierre", fissure: false,
      v: 300 + 300 * hache(k, 7, 71), spin: [(hache(k, 8, 71) - 0.5) * 6, (hache(k, 9, 71) - 0.5) * 6] });
  }
  return l;
})();

const GRAV = 620;
/** Où est le morceau k de la météorite τ secondes après l'explosion ; le temps de l'impact au sol. */
function eclat(m, tau) {
  const r0 = RF * 0.93 * m.bosse;
  const p = ajoute(C, fois(m.dir, r0 + m.v * tau));
  p[1] += 140 * tau - 0.5 * GRAV * tau * tau;
  return p;
}
function impact(m) {
  // y(τ) = C.y + d.y (r0 + v τ) + 140 τ - g τ² / 2 = 0
  const r0 = RF * 0.93 * m.bosse, a = -0.5 * GRAV, b = m.dir[1] * m.v + 140, c = C[1] + m.dir[1] * r0 + m.s / 2;
  return (-b - Math.sqrt(b * b - 4 * a * c)) / (2 * a);
}
const IMPACTS = SURFACE.filter((m) => m.k % 3 === 0).map((m) => {
  const ti = impact(m), p = eclat(m, ti);
  return [p[0], p[2], T_EXPLO + ti, m.s * 0.9];
});

const ENNEMIS = [
  { p0: [-260, 0, 1050], skin: () => STEVE, k: 0 },
  { p0: [300, 0, 1300], skin: () => SKINS.efe, k: 1 },
];

// ------------------------------------------------------------------ la scène
function monde(sc, t, cam) {
  const sombre = lisse(entre(t, 1.6, 3.0)) * (1 - 0.6 * lisse(entre(t, 7.6, 10.5)));
  terrain(sc, t, IMPACTS, cam, mix(CIEL, CIEL_SOMBRE, sombre).map((v) => v * 0.88));
  // les arbres : debout, ou arrachés, qui tournent et montent
  for (const a of ARBRES) {
    const ta = a.pris ? 2.9 + 2.0 * (Math.hypot(a.x - G[0], a.z - G[2]) / ZONE) : Infinity;
    if (t < ta) { const T = (v) => ajoute([a.x, 0, a.z], v); T.a = [0, 0]; arbre(sc, T, a.k); continue; }
    const u = entre(t, ta, ta + 1.6);
    if (u >= 1) continue;
    const c = vol([a.x, 40, a.z], u, ta + 1.6, a.k + 500), rot = [u * 2.5 * (a.k % 2 ? 1 : -1), u * 3];
    const T = (v) => ajoute(c, rotY(rotX(sous(v, [0, 40, 0]), rot[0]), rot[1])); T.a = rot;
    arbre(sc, T, a.k);
  }
  // le lanceur : il reste au sol, lève le bras droit vers la météorite, serre le poing ; à l'explosion le bras recule d'un coup
  const y = 0, leve = sortie(entre(t, 1.2, 1.9)), poing = sortie(entre(t, 6.4, 6.9)), recul = Math.max(0, 1 - Math.abs(t - T_EXPLO - 0.1) / 0.35) * (t > T_EXPLO ? 1 : 0);
  const T = perso(sc, SKINS.kai, {
    penche: 0.06 * recul,
    brasD: [-2.3 * leve - 0.15 * poing + 0.5 * recul, 0.1 * leve, 0], brasG: [0.25 * leve, 0.25 * leve, 0],
    jambeD: -0.25 * leve, jambeG: 0.2 * leve, tete: [-0.5 * sortie(entre(t, 0.3, 1.0)), 0, 0],
  });
  // ses yeux : le Rinnegan, violet, qui brille
  for (const x0 of [-3, 1]) sc.quad([[x0, 28, 4.08], [x0 + 2, 28, 4.08], [x0 + 2, 27, 4.08], [x0, 27, 4.08]].map(T.tete.p), x0 > 0 ? VIOLET_C : [230, 30, 30], 0.2, x0 > 0);
  // l'aura violette autour de lui, et la traînée de l'envol
  for (let q = 0; q < 18; q++) {
    const vie = 0.9, age = (t + q * 0.31) % vie, a = q * 2.1;
    cube(sc, [Math.cos(a) * (10 + 6 * age), y + 4 + age * 34, Math.sin(a) * (10 + 6 * age)], 1.6 * (1 - age / vie) + 0.3, [q, q], brille(q % 3 ? VIOLET : VIOLET_C), true);
  }
  // quand il lève le bras : une onde violette part de ses pieds, un trait de lumière monte de sa main vers la sphère
  const onde = entre(t, 1.5, 2.6);
  if (onde > 0 && onde < 1) anneau(sc, [0, 2, 0], [0, 1, 0], 30 + 520 * sortie(onde), 26, VIOLET_C, 0.55 * (1 - onde));
  const trait = Math.max(0, 1 - Math.abs(t - 1.85) / 0.3);
  if (trait > 0) { const m = T.brasD.p([-6, 11, 0]); for (let q = 0; q < 24; q++) cube(sc, ajoute(m, fois(sous(C, m), q / 24)), 6 * trait, [q, q], brille(VIOLET_C), true); }

  // les ennemis trop près : aspirés à leur tour, puis rejetés à l'explosion
  for (const e of ENNEMIS) {
    const t0 = 3.1 + 0.5 * e.k, ta = t0 + 1.7;
    if (t < t0) perso(sc, e.skin(), { decale: e.p0, lacet: Math.PI + 0.3 * e.k, brasD: [-0.4, 0], brasG: [0.3, 0] });
    else if (t < ta) {
      const u = entre(t, t0, ta);
      perso(sc, e.skin(), { decale: sous(vol(ajoute(e.p0, [0, 16, 0]), u, ta, 900 + e.k), [0, 16, 0]), lacet: Math.PI + 4 * u, penche: -1.2 * u, brasD: [-2.6, -0.6 - 0.4 * Math.sin(t * 20)], brasG: [-2.6, 0.6 + 0.4 * Math.sin(t * 18)], jambeD: 0.5 * Math.sin(t * 16), jambeG: -0.5 * Math.sin(t * 16) });
    } else if (t > T_EXPLO) {
      const tau = t - T_EXPLO, d = norme(sous(e.p0, G));
      const p = [d[0] * (RF + 500 * tau) + C[0], C[1] + 200 * tau - 0.5 * GRAV * tau * tau, d[2] * (RF + 500 * tau) + C[2]];
      if (p[1] > 0) perso(sc, e.skin(), { decale: p, lacet: 3 * tau, penche: -2 * tau, brasD: [-2.8, -0.5], brasG: [-2.8, 0.5], jambeD: 0.4, jambeG: -0.3 });
      else perso(sc, e.skin(), { decale: [p[0], 0, p[2]], penche: -1.5, lacet: 1 + e.k });
    }
  }

  // la sphère noire, au début, et la gravité : des anneaux violets qui tournent autour
  const r = rayon(t);
  const naissance = sortie(entre(t, 1.75, 2.3));
  if (t < T_EXPLO && naissance > 0) {
    const rc = 70 * naissance;
    cube(sc, C, rc * 1.3, [t * 0.7, t], [10, 4, 16], true);
    cube(sc, C, rc * 1.3, [t * 0.7 + 0.6, t + 0.8], [16, 8, 26], true);
    for (let q = 0; q < 3; q++) {
      const ax = rotY(rotX([0, 1, 0], 0.5 + q * 0.9), t * (0.8 + q * 0.3) + q * 2);
      anneau(sc, C, ax, Math.max(rc * 1.6, r * (1.15 + 0.12 * q)) + 10 * Math.sin(t * 5 + q), 6 + 4 * naissance, VIOLET, 0.35 * naissance);
    }
  }
  // les blocs arrachés qui montent
  if (t < T_EXPLO) for (const v of VOLANTS) {
    if (t < v.tr) continue;
    const u = (t - v.tr) / v.duree;
    if (u >= 1) continue;
    const p = vol(v.p0, u, v.tr + v.duree, v.k);
    bloc(sc, p, v.s, [u * 6 * (v.k % 2 ? 1 : -1), u * 5 + v.k], v.sorte, v.k);
    // un peu de poussière derrière les premiers instants
    if (u < 0.3 && v.k % 3 === 0) cube(sc, sous(p, [0, 14, 0]), 8 * (1 - u / 0.3), [v.k, u], POUSSIERE);
  }
  // les morceaux qui tournent autour avant de se coller
  if (t < T_EXPLO) for (const o of ORBITES) {
    if (t < o.t0) continue;
    const vie = entre(t, o.t0, o.t0 + 2.6);
    if (vie >= 1) continue;
    const ang = o.a0 + o.vit * (t - o.t0) * 2;
    const rr = r + o.dr * (1 - lisse(entre(vie, 0.6, 1)));
    let e1 = norme([o.axe[1], -o.axe[0], 0]);
    const e2 = norme([o.axe[1] * e1[2] - o.axe[2] * e1[1], o.axe[2] * e1[0] - o.axe[0] * e1[2], o.axe[0] * e1[1] - o.axe[1] * e1[0]]);
    const p = ajoute(C, ajoute(fois(e1, Math.cos(ang) * rr), fois(e2, Math.sin(ang) * rr)));
    bloc(sc, p, o.s, [ang, ang * 0.7], o.sorte, o.a0 * 100);
  }
  // la météorite : la surface grossit, les fissures violettes s'allument, puis elle éclate
  if (t < T_EXPLO && r > 0) {
    const fiss = lisse(entre(t, 5.9, 6.9));
    const trem = 6 * entre(t, 6.0, 6.95);
    for (const m of SURFACE) {
      if (m.k >= 1000 && t < 4.4) continue;
      const tr = [trem * Math.sin(t * 61 + m.k), trem * Math.sin(t * 53 + m.k * 2), trem * Math.cos(t * 47 + m.k)];
      const p = ajoute(ajoute(C, fois(m.dir, r * m.bosse * 0.93)), tr);
      const s = m.s * Math.max(0.35, r / RF);
      bloc(sc, p, s, m.rot, m.sorte, m.k, m.fissure ? fiss * (0.75 + 0.25 * Math.sin(t * 30 + m.k)) : 0);
    }
  }
  if (t >= T_EXPLO) {
    const tau = t - T_EXPLO;
    // la boule de lumière qui gonfle et s'éteint, les ondes dans le ciel
    const b = entre(tau, 0, 0.9);
    if (b < 1) {
      disque(sc, C, RF * (0.7 + 1.8 * sortie(b)), mix([255, 255, 255], VIOLET, b), 0.85 * (1 - b), cam);
      cube(sc, C, RF * 0.9 * (1 - b), [tau, tau], brille(mix([255, 255, 255], VIOLET_C, b)), true);
    }
    for (const [d, n] of [[0, [0, 1, 0]], [0.12, [0.4, 1, 0.2]], [0.22, [-0.3, 0.6, 1]]]) {
      const o = entre(tau, d, d + 1.6);
      if (o > 0 && o < 1) anneau(sc, C, n, RF + 2600 * sortie(o), 50 * (1 - o) + 10, mix(VIOLET_C, [255, 255, 255], 0.3), 0.6 * (1 - o), 56);
    }
    // les éclats : ils partent dans tous les sens, retombent et se plantent dans le sol
    for (const m of SURFACE) {
      const ti = impact(m);
      const tt = Math.min(tau, ti);
      const p = eclat(m, tt);
      // un éclat qui passe juste devant la caméra cacherait tout l'écran
      if (Math.hypot(p[0] - cam[0], p[1] - cam[1], p[2] - cam[2]) < m.s * 2.5) continue;
      const chaud = 1 - entre(tau, 0, 1.2);
      bloc(sc, p, m.s, [m.rot[0] + m.spin[0] * tt, m.rot[1] + m.spin[1] * tt], m.sorte, m.k, (m.fissure ? 1 : 0.55) * chaud);
      if (tau < ti) {
        // une traînée de poussière (et de lueur au début)
        for (let q = 1; q <= 3; q++) {
          const pq = eclat(m, Math.max(0, tt - q * 0.05));
          cube(sc, pq, m.s * (0.6 - q * 0.12), [q, m.k], chaud > 0.3 ? brille(mix(VIOLET_C, VIOLET, q / 3)) : mix(POUSSIERE, [120, 110, 104], q / 3), chaud > 0.3);
        }
      } else if (m.k % 3 === 0) {
        // l'impact : une bouffée de poussière qui s'étale
        const d = entre(tau, ti, ti + 1.0);
        if (d < 1) disque(sc, [p[0], 20 + 40 * d, p[2]], m.s * (1 + 3 * sortie(d)), POUSSIERE, 0.55 * (1 - d), cam);
      }
    }
  }
  return sombre;
}

// ------------------------------------------------------------------ les caméras
function depuis(pos, lacet, tangage, recul) {
  const a = (lacet * Math.PI) / 180, b = (tangage * Math.PI) / 180;
  return sous(pos, fois([-Math.sin(a) * Math.cos(b), Math.sin(b), Math.cos(a) * Math.cos(b)], recul));
}
function cles(t, l) {
  if (t <= l[0][0]) return l[0][1];
  for (let i = 1; i < l.length; i++) {
    if (t > l[i][0]) continue;
    const u = (t - l[i - 1][0]) / (l[i][0] - l[i - 1][0]);
    return l[i - 1][1] + (l[i][1] - l[i - 1][1]) * u * u * (3 - 2 * u);
  }
  return l[l.length - 1][1];
}
/** Le lacet et le tangage (en degrés) d'une caméra en pos qui regarde le point c. */
function vise(pos, c) {
  const d = norme(sous(c, pos));
  return [(Math.atan2(d[0], -d[2]) * 180) / Math.PI, (-Math.asin(d[1]) * 180) / Math.PI];
}

/** Plan 1 : sa caméra, de dos ; elle monte avec lui, recule pour voir la météorite entière. Plan 2 : en bas, chez les autres. */
function camera(t, k = 1, secousse = true) {
  if (t < 5.6) {
    const s = secousse ? 3 * entre(t, 2.4, 5.6) : 0;
    const recule = lisse(entre(t, 2.2, 5.4));
    const pos = [-40 - 30 * recule + s * Math.sin(t * 83), 26 - 6 * recule + s * Math.cos(t * 71), -95 - 40 * recule];
    const lacet = cles(t, [[0, 180], [2, 182], [5.6, 186]]);
    const tangage = cles(t, [[0, -3], [1.0, -10], [2.0, -24], [5.6, -26]]);
    return { lacet, tangage, echelle: cles(t, [[0, 1.15], [2.0, 1.0], [5.6, 0.92]]) * k, recul: 420, pied: 270 * k, centre: depuis(pos, lacet, tangage, 420), fond: CIEL, pos };
  }
  const tau = t - T_EXPLO;
  const s = secousse ? (tau > 0 ? 22 * (1 - entre(tau, 0, 0.9)) + 5 * (1 - entre(tau, 1.6, 3.3)) * entre(tau, 1.2, 1.6) : 3 * entre(t, 5.6, 7)) : 0;
  const pos = [1050 - 80 * entre(t, 5.6, 10.5) + s * Math.sin(t * 91), 44 + s * Math.cos(t * 77), 150 + 80 * entre(t, 5.6, 10.5)];
  const cible = [0, cles(t, [[5.6, 800], [7.2, 820], [8.6, 520], [10.5, 360]]), cles(t, [[5.6, 900], [10.5, 1000]])];
  const [lacet, tangage] = vise(pos, cible);
  return { lacet, tangage, echelle: cles(t, [[5.6, 0.95], [7.0, 0.88], [7.7, 0.62]]) * k, recul: 520, pied: 270 * k, centre: depuis(pos, lacet, tangage, 520), fond: CIEL, pos };
}

export function image(t, w = 960, h = 540, secousse = true) {
  const cam = camera(t, w / 960, secousse);
  const sombre = lisse(entre(t, 1.6, 3.0)) * (1 - 0.6 * lisse(entre(t, 7.6, 10.5)));
  const sc = scene({ w, h, ...cam, fond: mix(CIEL, CIEL_SOMBRE, sombre) });
  monde(sc, t, cam.pos);
  sc.lueur(VIOLET, 10, 1.4);
  // l'éclair de l'explosion
  const tau = t - T_EXPLO;
  if (tau >= 0) flash(sc, 0.92 * (1 - entre(tau, 0, 0.45)), [244, 236, 255]);
  // l'éclair violet quand la sphère naît
  flash(sc, 0.35 * Math.max(0, 1 - Math.abs(t - 1.85) / 0.15), VIOLET_C);
  return sc;
}

// ------------------------------------------------------------------ la planche
const ETAPES = [
  [0.4, "1. LE RINNEGAN S'ALLUME"], [1.6, "2. IL LEVE LE BRAS VERS LE CIEL"], [2.1, "3. LA SPHERE NOIRE NAIT TRES HAUT"],
  [3.4, "4. ELLE ARRACHE LE SOL, LES ARBRES, LES JOUEURS"], [4.8, "5. TOUT TOURNE AUTOUR ET S'AGGLUTINE"], [5.5, "6. LA METEORITE : 55 BLOCS DE LARGE"],
  [6.8, "7. LES FISSURES S'ALLUMENT, IL SERRE LE POING"], [7.25, "8. ELLE EXPLOSE"], [8.6, "9. LES BLOCS PLEUVENT AUTOUR"],
];
function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}
function planche() {
  const VW = 640, VH = 360, M = 34, HAUT = 120, W = VW * 3, H = HAUT + 3 * (M + VH);
  const img = Buffer.alloc(W * H * 4, 255);
  rect(img, W, 0, 0, W, HAUT, [20, 16, 28, 255]);
  ecrireTexte(img, W, "RINNEGAN : L'ULTIME, LA METEORITE", 30, 24, 6, [210, 180, 255, 255]);
  ecrireTexte(img, W, "IL LEVE LE BRAS, TOUT EST ATTIRE (TERRE, PIERRES, ARBRES, JOUEURS) ET FORME UNE METEORITE QUI EXPLOSE", 30, 78, 3, [200, 200, 210, 255]);
  ETAPES.forEach(([t, titre], k) => {
    const sc = image(t, VW, VH, false), c0 = k % 3, l0 = Math.floor(k / 3);
    rect(img, W, c0 * VW, HAUT + l0 * (M + VH), (c0 + 1) * VW, HAUT + l0 * (M + VH) + M, [20, 16, 28, 255]);
    for (let r = 0; r < VH; r++) sc.img.copy(img, ((HAUT + l0 * (M + VH) + M + r) * W + c0 * VW) * 4, r * VW * 4, (r + 1) * VW * 4);
    ecrireTexte(img, W, titre, c0 * VW + 12, HAUT + l0 * (M + VH) + 10, 3, [210, 180, 255, 255]);
  });
  ecrirePng(path.join(DOC, "planche-meteorite.png"), W, H, img);
  console.log("ok planche", W, H);
}

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  const [mode, a, b, c] = process.argv.slice(2);
  if (mode === "image") {
    const sc = image(Number(a));
    ecrirePng(b ?? path.join(DOC, "image.png"), sc.w, sc.h, sc.img);
    console.log("ok", a);
  } else if (mode === "video") {
    fs.mkdirSync(a, { recursive: true });
    const n = Math.round(DUREE * 24), de = Number(b ?? 0), pas = Number(c ?? 1);
    for (let i = de; i < n; i += pas) {
      const sc = image(i / 24);
      ecrirePng(path.join(a, String(i).padStart(4, "0") + ".png"), sc.w, sc.h, sc.img);
    }
    console.log("ok video", n);
  } else planche();
}
