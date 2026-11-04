// Maquette : la transformation de l'ulti de Hachibi 4. Le chakra rouge monte de partout et converge sur le joueur, se
// referme en une boule rouge autour de lui, qui gonfle jusqu'à tout avaler (plus grosse que la bête) ; des fissures de
// lumière la parcourent, elle éclate d'un coup, et le démon est là, qui rugit, fumant de chakra. Aucun dégât : du chakra.
// node transformation.mjs                → ../planche-transformation.png (six moments)
// node transformation.mjs video <dossier> → les images de la vidéo (24 par seconde), assemblées ensuite par ffmpeg
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, scene, ecrireTexte, clair, mix, hache, lisse } from "../../fruits/generateur/moteur.mjs";
import { boule, cubeSimple, brille } from "../../minato2/generateur/effets.mjs";
import { hachibi, joueur, ajoute, fois } from "./bete.mjs";
import { G, sol, anneau, legende } from "./rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const FOND = [196, 210, 226];
const ROUGE_SOMBRE = [110, 8, 10];
const ROUGE = [226, 30, 22];
const ROUGE_VIF = [255, 92, 50];
const BRAISE = [255, 214, 150];
const TOUR = Math.PI * 2;

/** Les temps (en secondes) : le chakra monte, la boule se forme, elle gonfle, se fissure, éclate ; la fin. */
const T = { boule: 0.9, pleine: 1.5, enorme: 3.0, fissures: 2.8, eclat: 3.5, fin: 5.4 };
const RAYON_MAX = 31;
const entre = (t, a, b) => Math.max(0, Math.min(1, (t - a) / (b - a)));

/** Le rayon de la boule à l'instant t : elle naît autour de lui, puis gonfle de plus en plus vite. */
function rayon(t) {
  if (t < T.boule) return 0;
  if (t < T.pleine) return 1.4 + 1.8 * lisse(entre(t, T.boule, T.pleine));
  return 3.2 + (RAYON_MAX - 3.2) * entre(t, T.pleine, T.enorme) ** 2.2;
}

/** Le centre de la boule : posée sur le sol (elle s'y enfonce un peu). */
const centre = (r) => [0, Math.max(1, r * 0.82), 0];

/** La couleur de la boule dans la direction d (normée) : des bandes de chakra qui tournent, et les fissures. */
function surface(d, t, fissure) {
  const a = Math.atan2(d[2], d[0]);
  const bande = 0.5 + 0.5 * Math.sin(a * 5 + d[1] * 7 + t * 9) * Math.sin(a * 3 - d[1] * 4 - t * 5);
  let c = mix(ROUGE_SOMBRE, ROUGE_VIF, 0.25 + 0.6 * bande);
  if (fissure > 0) {
    // de grands cercles de lumière, qui s'élargissent jusqu'à l'éclat
    const axes = [[0.3, 0.9, 0.2], [0.8, -0.2, 0.55], [-0.5, 0.3, 0.8], [0.1, -0.6, -0.8], [-0.9, 0.1, -0.3]];
    for (const [k, ax] of axes.entries()) {
      const n = Math.hypot(...ax), p = (d[0] * ax[0] + d[1] * ax[1] + d[2] * ax[2]) / n;
      const ondule = 0.04 * Math.sin(a * 9 + k * 2);
      if (Math.abs(p + ondule) < 0.012 + 0.05 * fissure * (k < 2 + fissure * 4 ? 1 : 0)) return mix(BRAISE, [255, 255, 255], fissure);
    }
    c = mix(c, ROUGE_VIF, fissure * 0.4);
  }
  return c;
}

/** La boule pleine, faite de cubes (seulement la coquille) : ce qu'il faut pour les fissures et l'éclat. */
function coquille(r, pas) {
  const cubes = [];
  const n = Math.ceil(r / pas);
  for (let i = -n; i <= n; i++) for (let j = -n; j <= n; j++) for (let k = -n; k <= n; k++) {
    const p = [i * pas, j * pas, k * pas], d = Math.hypot(...p);
    if (d <= r && d > r - pas * 1.6) cubes.push(p);
  }
  return cubes;
}

/** Le chakra rouge qui monte du sol et converge en spirale sur le joueur. */
function filets(sc, t) {
  const n = 90;
  for (let q = 0; q < n; q++) {
    const debut = hache(q, 1, 900) * 0.7, u = entre(t, debut, debut + 0.9);
    if (u <= 0 || u >= 1) continue;
    const a = hache(q, 2, 900) * TOUR + u * 5, r0 = 1.5 + 4 * hache(q, 3, 900), rr = r0 * (1 - lisse(u));
    const h = (0.2 + 4 * hache(q, 4, 900)) * (1 - u) + 1.1 * u + Math.sin(u * 7 + q) * 0.6 * (1 - u);
    const c = hache(q, 5, 900) < 0.3 ? ROUGE_VIF : ROUGE;
    for (let k = 0; k < 4; k++) {
      // une petite traînée derrière chaque filet
      const v = Math.max(0, u - k * 0.03), ak = hache(q, 2, 900) * TOUR + v * 5, rk = r0 * (1 - lisse(v));
      cubeSimple(sc, [Math.cos(ak) * rk, h + k * 0.12, Math.sin(ak) * rk], (0.32 - k * 0.05) * (0.7 + 0.6 * hache(q, 6, 900)), brille(mix(c, ROUGE_SOMBRE, k * 0.2)), true);
    }
  }
}

/** Les morceaux de la coquille qui volent, l'onde au sol, et la brume de chakra qui retombe. */
function eclat(sc, t, cubes) {
  const u = t - T.eclat;
  cubes.forEach((p, q) => {
    if (q % 11) return;
    const d = Math.hypot(...p) || 1, v = 22 + 30 * hache(q, 1, 910);
    const pos = ajoute(fois(p, (RAYON_MAX + v * u) / d), [0, RAYON_MAX * 0.82 - 9 * u * u, 0]);
    if (pos[1] < -1) return;
    const vie = Math.max(0, 1 - u / 0.9);
    if (vie <= 0) return;
    cubeSimple(sc, pos, 2.2 * vie, brille(mix(ROUGE_VIF, ROUGE_SOMBRE, 1 - vie)), true);
  });
}

/** La brume de chakra qui fume encore de la bête, et monte. */
function fumee(sc, t) {
  const u = t - T.eclat;
  for (let q = 0; q < 70; q++) {
    const a = hache(q, 1, 920) * TOUR, r = 6 + 22 * hache(q, 2, 920);
    const y = 2 + 30 * hache(q, 3, 920) + u * (3 + 4 * hache(q, 4, 920));
    const vie = Math.max(0, 1 - u / (1.6 + hache(q, 5, 920)));
    if (vie <= 0) continue;
    const c = [Math.cos(a) * r, y, Math.sin(a) * r - 4], s = (1 + 2 * hache(q, 6, 920)) * (0.5 + u);
    const f = [[c[0] - s, c[1] - s, c[2]], [c[0] + s, c[1] - s, c[2]], [c[0] + s, c[1] + s, c[2]], [c[0] - s, c[1] + s, c[2]]];
    sc.voile(f, ROUGE, 0.35 * vie);
  }
}

/** Le sol rougi sous la boule (le chakra s'y reflète). */
const reflet = (t, r) => (x, z) => {
  const d = Math.hypot(x, z);
  const k = r > 0 && t < T.eclat ? Math.max(0, 1 - d / (r * 1.4)) * 0.55 : t < T.eclat + 0.6 ? Math.max(0, 1 - Math.abs(d - (RAYON_MAX + (t - T.eclat) * 70)) / 6) * 0.6 : 0;
  if (k <= 0) return null;
  const h = hache(Math.floor(x * 2), Math.floor(z * 2), 3);
  return mix(clair(h < 0.15 ? [84, 142, 48] : [100, 164, 60], 0.95), ROUGE, k);
};

let COQUILLE = null;

/** Une image de la transformation, à l'instant t (en secondes). */
function image(sc, t) {
  const r = rayon(t);
  sol(sc, [-72, 72], [-62, 62], reflet(t, r));
  if (t < T.eclat) {
    // le joueur, de plus en plus rouge, tant qu'on le voit encore dans la boule
    if (r < 9) joueur(sc, [0, 0, 0], Math.PI * 0.85, { c: [255, 40, 30], k: Math.min(0.7, t * 0.5) });
    filets(sc, t);
    if (r > 0 && r < 7) {
      // la boule naissante : transparente, on le voit dedans
      boule(sc, centre(r), r, () => brille(ROUGE), { tranche: 0.5, voile: 0.45 + 0.04 * r });
    } else if (r >= 7) {
      const pas = Math.max(0.6, r / 22);
      const fissure = entre(t, T.fissures, T.eclat);
      const c = centre(r);
      for (const p of coquille(r, pas)) {
        const d = Math.hypot(...p);
        cubeSimple(sc, ajoute(c, p), pas * 1.02, brille(surface(p.map((v) => v / d), t, fissure)), true);
      }
    }
    sc.lueur([255, 40, 30], 8, 1.2 + r / 20);
  } else {
    // le démon, qui rugit (gueule grande ouverte, tête levée), dans la brume de chakra
    COQUILLE ??= coquille(RAYON_MAX, RAYON_MAX / 22);
    hachibi(sc, { G, gueule: 1, tete: -10 });
    eclat(sc, t, COQUILLE);
    sc.lueur([255, 40, 30], 7, 1.2);
    const u = t - T.eclat;
    if (u < 0.7) anneau(sc, [0, 0.4, 0], [0, 1, 0], RAYON_MAX + u * 70, 3, 0.5 * (1 - u / 0.7), [255, 120, 90]);
    fumee(sc, t);
  }
  // le flash de l'éclat : l'écran blanchit, puis rosit, et revient
  const f = t >= T.eclat ? Math.max(0, 1 - (t - T.eclat) / 0.25) : t > T.eclat - 0.12 ? (t - T.eclat + 0.12) / 0.12 : 0;
  if (f > 0) {
    for (let i = 0; i < sc.w * sc.h; i++) for (let k = 0; k < 3; k++) sc.img[i * 4 + k] = sc.img[i * 4 + k] * (1 - f * 0.9) + [255, 236, 230][k] * f * 0.9;
  }
}

// ------------------------------------------------------------------ la planche et la vidéo
/** La caméra part tout près du joueur et recule à mesure que la boule gonfle (k : 1 pour la planche, plus pour la vidéo). */
const CAMERA = (t, k = 1) => {
  const s = t >= T.eclat ? RAYON_MAX : Math.max(rayon(t), 1.2);
  return { lacet: -18 - 10 * entre(t, 0, T.fin), tangage: 8, echelle: (k * 330) / (2 * s + 6), centre: [0, 0, 0], pied: 405 * k, fond: FOND };
};
const MOMENTS = [
  [0.55, "1. LE CHAKRA ROUGE MONTE ET CONVERGE"],
  [1.35, "2. IL SE REFERME EN BOULE AUTOUR DE LUI"],
  [2.45, "3. LA BOULE GONFLE..."],
  [3.3, "4. ...PREND TOUT, ET SE FISSURE"],
  [3.66, "5. ELLE ECLATE"],
  [4.4, "6. LE DEMON RUGIT"],
];

const [mode, dossier] = process.argv.slice(2);
if (mode === "video") {
  fs.mkdirSync(dossier, { recursive: true });
  const n = Math.round(T.fin * 24);
  for (let i = 0; i < n; i++) {
    const t = i / 24;
    const sc = scene({ w: 960, h: 540, ...CAMERA(t, 1.16) });
    image(sc, t);
    ecrirePng(path.join(dossier, `i${String(i).padStart(4, "0")}.png`), sc.w, sc.h, sc.img);
    if (i % 12 === 0) console.log("image", i, "/", n);
  }
  console.log("ok video", n);
} else {
  const CW = 520, CH = 470, M = 40, HAUT = 64, COL = 3;
  const W = CW * COL, H = HAUT + (M + CH) * 2;
  const planche = Buffer.alloc(W * H * 4, 255);
  ecrireTexte(planche, W, "HACHIBI 4 : LA TRANSFORMATION DE L'ULTI", 18, 16, 5, [150, 40, 70, 255]);
  MOMENTS.forEach(([t, titre], k) => {
    const c = k % COL, l = Math.floor(k / COL);
    const sc = scene({ w: CW, h: CH, ...CAMERA(t) });
    image(sc, t);
    legende(sc, [`${t.toFixed(1).replace(".", ",")} S`]);
    for (let y = 0; y < CH; y++) sc.img.copy(planche, ((HAUT + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(planche, W, titre, c * CW + 12, HAUT + l * (M + CH) + 10, 3, [190, 40, 40, 255]);
    console.log("moment", k);
  });
  ecrirePng(path.join(ICI, "..", "planche-transformation.png"), W, H, planche);
  console.log("ok", W, H);
}
