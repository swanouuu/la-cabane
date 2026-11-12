// Croquis des sept lames de la Brume (Naruto) pour la Cabane : chaque lame seule, portée (dans le dos ou sur le côté
// quand on ne la tient pas), son clic gauche (une façon de frapper par lame) et son clic droit (le coup spécial).
// Les lames sont des pixels extrudés, comme les objets en jeu, avec une épaisseur par pixel pour les volumes ronds
// (Samehada, le rouleau de Shibuki, le tonneau de Kabutowari). Designs d'après les images de l'utilisateur (v2).
// Coordonnées du perso en pixels du modèle (pieds à y = 0, il regarde vers +z, sa gauche vers +x), le monde en blocs.
// node rendu.mjs                 → ../planche-lames-brume.png
// node rendu.mjs essai 0,1,2,3   → ../essai.png (quelques cases seulement)
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, PARTIES, texels, hache, ecrireTexte, POLICE } from "../../hachibi2/generateur/moteur.mjs";
import { ecran, polygone, melange, ligne, briller, sol } from "../../susano3/generateur/rendu.mjs";
import { voxels as voxSame } from "./samehada3d.mjs";

Object.assign(POLICE, {
  "+": "000010111010000", "-": "000000111000000", ":": "000010000010000", "/": "001001010100100", ".": "000000000000010",
  ",": "000000000010100", "'": "010010000000000", "!": "010010010000010", "(": "010100100100010", ")": "010001001001010",
  "=": "000111000111000", "%": "101001010100101",
});

const ICI = path.dirname(fileURLToPath(import.meta.url));
const NINJA = lire(path.join(ICI, "..", "..", "missions", "skins", "ninjaBrume.png"));
const STEVE = lire(path.join(ICI, "..", "..", "hachibi2", "generateur", "skins", "steve.png"));

const DEG = Math.PI / 180, TOUR = Math.PI * 2;
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const len = (a) => Math.hypot(a[0], a[1], a[2]);
const norm = (a) => mul(a, 1 / (len(a) || 1));
const lerp = (a, b, t) => add(a, mul(sub(b, a), t));
const clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
const mix = (a, b, k) => a.map((v, i) => v + (b[i] - v) * k);
const rotX = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
const rotY = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];
const rotZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];
const rodrigues = (v, k, a) => { const c = Math.cos(a), s = Math.sin(a); return add(add(mul(v, c), mul(cross(k, v), s)), mul(k, dot(k, v) * (1 - c))); };
const LUM = norm([-0.4, 0.75, 0.55]);
const ombre = (n) => 0.58 + 0.42 * Math.max(0, dot(n, LUM)) + 0.08 * Math.max(0, -dot(n, LUM));
/** L'épaisseur d'un volume rond à la distance d de son axe (rayon r, épaisseur au centre e). */
const rond = (d, r, e, min = 1) => Math.max(min, e * Math.sqrt(Math.max(0, 1 - (d / r) ** 2)));

// ================================================================== les briques de dessin

/** Un polygone plein (il cache ce qui est derrière) ; g : il brille. */
function plein(E, pts, c, g = 0) {
  polygone(E, pts, (i, zz) => {
    if (zz >= E.z[i]) return;
    E.z[i] = zz;
    for (let k = 0; k < 3; k++) E.img[i * 4 + k] = Math.max(0, Math.min(255, Math.round(c[k])));
    if (g) for (let k = 0; k < 3; k++) E.lueur[i * 3 + k] += c[k] * g * 0.5;
  });
}
/** Un polygone transparent (il teinte ce qui est derrière lui, sans rien cacher). */
const voile = (E, pts, c, a, g = 0) => polygone(E, pts, (i, zz) => { if (zz < E.z[i]) melange(E, i, c, a, g); });

/** Un petit cube tourné au hasard (étincelle, caillou, bout de chakra). */
function cube(E, c, s, col, { g = 0, graine = 0, alpha = 1 } = {}) {
  const a = hache(graine, 1, 7) * TOUR, b = hache(graine, 2, 7) * TOUR;
  const X = rotY(rotX([1, 0, 0], a), b), Y = rotY(rotX([0, 1, 0], a), b), Z = cross(X, Y);
  for (const [n, u, v] of [[X, Y, Z], [Y, Z, X], [Z, X, Y]]) for (const sg of [-1, 1]) {
    const m = add(c, mul(n, (sg * s) / 2)), U = mul(u, s / 2), V = mul(v, s / 2);
    const pts = [add(m, add(U, V)), add(m, sub(U, V)), sub(m, add(U, V)), add(m, sub(V, U))];
    const cc = g ? col : mul(col, ombre(mul(n, sg)));
    if (alpha < 1) voile(E, pts, cc, alpha, g); else plein(E, pts, cc, g);
  }
}

/** Un anneau à plat (onde de choc, disque de rotation), autour de c, de r0 à r1. */
function anneau(E, c, r0, r1, col, a, g = 0, { de = 0, a2 = 360, n = 72, axeY = [0, 1, 0] } = {}) {
  const e1 = norm(cross(axeY, [0.2, 0.1, 1])), e2 = cross(axeY, e1);
  const p = (t, r) => add(c, add(mul(e1, Math.cos(t) * r), mul(e2, Math.sin(t) * r)));
  for (let k = 0; k < n; k++) {
    const t0 = (de + ((a2 - de) * k) / n) * DEG, t1 = (de + ((a2 - de) * (k + 1)) / n) * DEG;
    voile(E, [p(t0, r0), p(t1, r0), p(t1, r1), p(t0, r1)], col, typeof a === "function" ? a(k / n) : a, g);
  }
}

/** Un trait épais (fil, éclair) : plusieurs lignes serrées. */
function trait(E, A, B, col, a, g = 0, ep = 0.02) {
  const d = norm(sub(B, A)), e1 = norm(cross(d, [0.31, 0.77, 0.55])), e2 = cross(d, e1);
  for (const [u, v] of [[0, 0], [1, 0], [-1, 0], [0, 1], [0, -1]]) {
    const o = add(mul(e1, u * ep), mul(e2, v * ep));
    ligne(E, add(A, o), add(B, o), col, a, g);
  }
}

/** Un éclair de A à B (zigzag, avec des branches). */
function eclair(E, A, B, graine, { col = [150, 215, 255], amp = 0.45, n = 14, ep = 0.035, branches = 3 } = {}) {
  const d = sub(B, A), e1 = norm(cross(d, [0.3, 0.1, 1])), e2 = norm(cross(d, e1));
  const pts = [A];
  for (let k = 1; k < n; k++) {
    const t = k / n, f = Math.sin(Math.PI * t) * amp;
    pts.push(add(add(A, mul(d, t)), add(mul(e1, (hache(k, graine, 1) - 0.5) * 2 * f), mul(e2, (hache(k, graine, 2) - 0.5) * 2 * f))));
  }
  pts.push(B);
  for (let k = 0; k + 1 < pts.length; k++) {
    trait(E, pts[k], pts[k + 1], col, 0.9, 1.6, ep * 2.2);
    trait(E, pts[k], pts[k + 1], [255, 255, 255], 1, 1.2, ep * 0.6);
  }
  for (let b = 0; b < branches; b++) {
    const k = 2 + Math.floor(hache(b, graine, 5) * (n - 4)), P = pts[k];
    const dir = norm(add(mul(norm(d), 0.6), [(hache(b, graine, 6) - 0.5) * 1.6, 0, (hache(b, graine, 7) - 0.5) * 1.6]));
    eclair(E, P, add(P, mul(dir, len(d) * 0.22)), graine * 7 + b + 1, { col, amp: amp * 0.4, n: 6, ep: ep * 0.6, branches: 0 });
  }
}

/** Une explosion : un cœur de cubes qui brillent (jaune au centre, rouge au bord), et de la fumée autour. */
function explosion(E, c, r, graine) {
  for (let k = 0; k < 26; k++) {
    const t = hache(k, graine, 1), u = hache(k, graine, 2) * TOUR, v = Math.acos(1 - 2 * hache(k, graine, 3));
    const dir = [Math.sin(v) * Math.cos(u), Math.abs(Math.cos(v)) * 0.8 + 0.1, Math.sin(v) * Math.sin(u)];
    const p = add(c, mul(dir, r * t * 0.9));
    cube(E, p, r * (0.42 - 0.22 * t), mix(mix([255, 246, 170], [255, 150, 40], Math.min(1, t * 1.4)), [210, 50, 20], clamp(t * 1.4 - 1)), { g: 1.1, graine: k + graine * 31 });
  }
  for (let k = 0; k < 14; k++) {
    const u = hache(k, graine, 8) * TOUR, h = hache(k, graine, 9);
    const p = add(c, [Math.cos(u) * r * (0.9 + 0.4 * h), r * (0.3 + 0.9 * h), Math.sin(u) * r * (0.9 + 0.4 * h)]);
    cube(E, p, r * (0.2 + 0.14 * hache(k, graine, 10)), [150, 146, 144], { graine: k + graine * 17, alpha: 0.35 });
  }
  anneau(E, [c[0], 0.03, c[2]], 0, r * 1.25, [40, 30, 26], 0.45);
}

/** La brume : ce qui est loin se noie dans le gris bleuté (le village caché de la Brume). */
function brume(E, col = [196, 204, 212], debut = 4, fin = 24, max = 0.6) {
  for (let i = 0; i < E.w * E.h; i++) {
    if (!isFinite(E.z[i])) continue;
    const k = clamp((E.z[i] - debut) / (fin - debut)) * max;
    for (let c = 0; c < 3; c++) E.img[i * 4 + c] = E.img[i * 4 + c] * (1 - k) + col[c] * k;
  }
}

// ================================================================== le perso

const K = 0.9375 / 16;
const PIVOTS = { brasD: [-5, 22, 0], brasG: [5, 22, 0], jambeD: [-2, 12, 0], jambeG: [2, 12, 0], tete: [0, 24, 0] };
const MAIN = { brasD: [-6, 13, 0], brasG: [6, 13, 0] };

/** La rotation qui envoie le bras pendant ([0,-1,0]) vers cette direction. */
function versDir(dir) {
  const b = norm(dir), c = -b[1];
  if (c > 0.9999) return (v) => v;
  if (c < -0.9999) return (v) => rotX(v, Math.PI);
  const ax = norm(cross([0, -1, 0], b)), ang = Math.acos(c);
  return (v) => rodrigues(v, ax, ang);
}

/**
 * Les chaînes de placement du perso (de ses pixels au monde) : corps (le haut du corps tourné et penché), bras, jambes,
 * tête. o = { pos, lacet, torse, penche, saut, bascule, roule, taille, brasD, brasG (directions), jambeD, jambeG, tete }.
 */
function chaines(o) {
  const pos = o.pos ?? [0, 0, 0], lac = (o.lacet ?? 0) * DEG, tor = (o.torse ?? 0) * DEG, pen = (o.penche ?? 0) * DEG;
  const bas = (o.bascule ?? 0) * DEG, rou = (o.roule ?? 0) * DEG, saut = o.saut ?? 0, k = K * (o.taille ?? 1);
  const toutD = (d) => rotY(rotZ(rotX(d, bas), rou), lac);
  const monde = (v) => add(toutD(mul(sub(v, [0, 12, 0]), k)), add(pos, [0, 12 * k + saut, 0]));
  const hautD = (d) => rotX(rotY(d, tor), pen);
  const haut = (v) => add([0, 12, 0], hautD(sub(v, [0, 12, 0])));
  const chaine = (f, fd) => ({ p: (v) => monde(f(v)), n: (d) => norm(toutD(fd(d))) });
  const C = { corps: chaine(haut, hautD), bassin: chaine((v) => v, (d) => d) };
  for (const nom of ["brasD", "brasG"]) {
    const r = versDir(o[nom] ?? [0, -1, 0.05]), piv = PIVOTS[nom];
    C[nom] = chaine((v) => haut(add(piv, r(sub(v, piv)))), (d) => hautD(r(d)));
  }
  for (const nom of ["jambeD", "jambeG"]) {
    const a = (o[nom] ?? 0) * DEG, piv = PIVOTS[nom];
    C[nom] = chaine((v) => add(piv, rotX(sub(v, piv), a)), (d) => rotX(d, a));
  }
  const [tx, ty] = (o.tete ?? [0, 0]).map((v) => v * DEG), piv = PIVOTS.tete;
  C.tete = chaine((v) => haut(add(piv, rotY(rotX(sub(v, piv), tx), ty))), (d) => hautD(rotY(rotX(d, tx), ty)));
  return C;
}

function perso(E, peau, o) {
  const C = chaines(o);
  for (const partie of PARTIES(false)) for (const couche of [2, 3]) {
    for (const t of texels(partie[1], partie[couche], couche === 3 ? partie[4] : 0, 1)) {
      const i = (t.ty * peau.w + t.tx) * 4;
      if (peau.px[i + 3] < 128) continue;
      const ch = C[partie[0]];
      let c = [peau.px[i], peau.px[i + 1], peau.px[i + 2]];
      if (o.teinte) c = mix(c, o.teinte, o.teinteK ?? 0.4);
      plein(E, t.coins.map(ch.p), mul(c, ombre(ch.n(t.N))));
    }
  }
  return C;
}

/** Un mannequin (Steve) qui prend le coup, tourné vers le joueur. */
const cible = (pos, plus = {}) => ({ peau: STEVE, pos, lacet: 180, brasD: [0, -1, 0.12], brasG: [0, -1, 0.12], ...plus });

// ================================================================== les lames (des pixels extrudés)

const BOIS = [104, 72, 44], BOIS_C = [140, 100, 62], CORDE = [124, 88, 58], CORDE_F = [80, 56, 38];

/** Samehada (Kisame) : un vrai volume en voxels, voir samehada3d.mjs (normale, pleine, ouverte). */
const SAME = { n: [85, 1], g: [7, 0], ech: 0.5, voxels: voxSame };

/**
 * Kubikiribôchô (Zabuza), d'après la photo de la réplique : une longue lame presque rectangulaire, gris très foncé,
 * le tranchant en biseau argenté. Près de la garde, la demi-lune creusée dans le tranchant (avec des pans coupés de
 * chaque côté). Le bout n'est pas arrondi : le dos finit en pointe nette, le bord du bout se creuse un peu jusqu'au
 * tranchant (coin à peine adouci). Le trou rond près du bout, au milieu. Une garde plate noire, le manche noir avec des
 * bagues argentées. Mesures en « gros pixels » (I le long, J en travers : 0 = tranchant, 17,5 = dos), dessin en
 * demi-pixels.
 */
const KUBI = {
  n: [178, 40], g: [16, 23.5], ech: 0.24, ep: 3.4, net: true,
  pal: { k: { c: [26, 26, 30] }, K: { c: [202, 206, 212] }, G: { c: [20, 20, 24] },
    a: { c: [66, 68, 74] }, A: { c: [56, 58, 64] }, o: { c: [24, 24, 28] }, e: { c: [236, 238, 242] }, E: { c: [196, 200, 206] }, s: { c: [150, 154, 160] } },
  /** Le bout : il se creuse un peu entre le coin du tranchant (I = 82) et la pointe du dos (I = 88). */
  fin(J) { const t = Math.max(0, Math.min(1, J / 17.5)); return 82 + 6 * t ** 1.3 - 1.3 * Math.sin(Math.PI * t); },
  dedans(I, J) {
    if (I < 17 || J < 0 || J > 17.5 + Math.max(0, (I - 85) / 3) * 0.6) return false;
    if (I > this.fin(J)) return false;
    if (I > 80.5 && J < 1.5 && Math.hypot(I - 80.5, J - 1.5) > 1.5) return false; // le coin du tranchant, à peine adouci
    if (I < 18.5 && J < 18.5 - I) return false; // le pan coupé contre la garde
    // la demi-lune dans le tranchant, avec un pan coupé de chaque côté
    const dn = Math.hypot(I - 28, J);
    if (dn < 4.6) return false;
    if (Math.abs(I - 28) < 6 && J < 6 - Math.abs(I - 28) && J < 1.4) return false;
    return Math.hypot(I - 76, J - 8.6) >= 2.5;
  },
  forme(i, j) {
    const I = (i + 0.5) / 2, J = (j + 0.5) / 2;
    if (I < 16) {
      if (Math.abs(J - 12) >= 1.1) return null;
      return I < 1.5 || (I > 3 && I < 4.2) || (I > 7.5 && I < 8.7) || (I > 12 && I < 13.2) || I > 15 ? "K" : "k";
    }
    if (I < 17) return J >= -0.5 && J <= 19.5 ? "G" : null;
    if (!this.dedans(I, J)) return null;
    const d = (a, b) => this.dedans(I + a, J + b);
    // le biseau argenté : le long du tranchant, de la demi-lune et du bout
    const bord = (r) => !d(0, -r) || !d(r, 0) || !d(r * 0.7, -r * 0.7) || !d(-r * 0.7, -r * 0.7);
    if (!d(0.5, 0) || !d(-0.5, 0) || !d(0, 0.5) || !d(0, -0.5)) return J > 15 || !bord(1.2) ? "o" : "E";
    if (J < 14 && bord(1.2)) return "e";
    if (J < 14 && bord(2.4)) return "E";
    if (J < 14 && bord(2.9)) return "s";
    return J > 15.5 ? "A" : "a";
  },
  epais(i, j, k) { return k === "k" || k === "K" ? 4.6 : k === "G" ? 7 : k === "e" ? 2.2 : k === "E" || k === "s" ? 2.8 : 3.4; },
};

/**
 * Shibuki (Jinpachi), image 3 : un gros rouleau violet-noir le long du haut, avec des bouchons gris aux deux bouts ;
 * en dessous la lame couverte de parchemins explosifs (blancs, taches noires, fissures rouges) ; une poignée fine
 * qui sort à l'arrière.
 */
const SHIB = {
  n: [42, 24], g: [3, 17], ech: 0.72, ep: 3,
  pal: { h: { c: [150, 154, 162] }, H: { c: [100, 104, 112] }, c: { c: [42, 34, 52] }, C: { c: [82, 70, 100] }, s: { c: [160, 162, 170] }, S: { c: [104, 106, 114] },
    t: { c: [234, 232, 224] }, T: { c: [38, 38, 42] }, r: { c: [190, 44, 44] }, e: { c: [208, 212, 218] } },
  forme(i, j) {
    if (i <= 7) return j >= 16 && j <= 17 ? (i % 3 === 0 ? "H" : "h") : null;
    if (i === 8) return j >= 14 && j <= 19 ? "S" : null;
    if (j >= 12) return i <= 10 || i >= 39 ? (j === 12 || j === 23 ? "S" : "s") : j === 20 || j === 21 ? "C" : "c";
    if (i > 38) return null;
    if (j < 4) return null;
    if (i >= 37 || j === 4) return i >= 37 ? "S" : "e";
    const f1 = Math.abs(j - 2 - (i - 12) * 0.45 - 2 * Math.sin(i * 0.9)), f2 = Math.abs(j - 9 + (i - 22) * 0.6 + 1.5 * Math.sin(i * 1.3));
    if ((f1 < 0.55 && i < 30) || (f2 < 0.55 && i > 18)) return "r";
    return hache(i, j, 5) < 0.07 ? "T" : "t";
  },
  epais(i, j, k) {
    if (i <= 7) return 2;
    if (i === 8) return 5;
    if (j >= 12) return rond(Math.abs(j + 0.5 - 18), i <= 10 || i >= 39 ? 6.6 : 6.2, i <= 10 || i >= 39 ? 13 : 12, 3);
    return 1.8;
  },
  teinte(i, j) { return j >= 12 ? 1 - 0.3 * ((j + 0.5 - 18) / 6.5) ** 2 : 1; },
};

/**
 * Hiramekarei (Chôjûrô), d'après le modèle 3D que l'utilisateur a choisi (Sketchfab cb784173) : une lame en feuille,
 * large et arrondie en bas, qui s'effile jusqu'à une pointe ; une encoche ronde de chaque côté près de la base ; un
 * dôme en relief au milieu, avec deux trous ovales au-dessus de ses épaules ; l'arête du centre jusqu'à la pointe ; le
 * bord biseauté (en marches) ; une seule poignée, fine, tressée d'or. « chakra » : l'arête brille.
 * Mesures : u de 0 (la base de la lame, côté poignée) à 1 (la pointe), x de 0 (l'arête) à 1 (la plus grande demi-largeur).
 */
const HIRA = {
  n: [100, 52], g: [9, 25.5], ech: 0.38, ep: 3,
  pal: { c: { c: [214, 186, 122] }, C: { c: [120, 92, 46] }, o: { c: [62, 60, 58] },
    a: { c: [104, 104, 110] }, d: { c: [124, 124, 131] }, D: { c: [114, 114, 121] }, b: { c: [158, 158, 166] }, m: { c: [66, 66, 72] },
    r: { c: [44, 44, 50] }, f: { c: [110, 230, 255], g: 1.1 } },
  manche: 18, L: 82, M: 23,
  /** La demi-largeur de la lame à la hauteur u (en largeurs M). */
  bord(u) {
    const P = [[0, 0.1], [0.04, 0.48], [0.1, 0.74], [0.18, 0.9], [0.3, 1], [0.44, 0.97], [0.58, 0.85], [0.73, 0.6], [0.87, 0.32], [1, 0]];
    if (u < 0 || u > 1) return -1;
    for (let k = 0; k + 1 < P.length; k++) {
      if (u <= P[k + 1][0]) {
        const t = (u - P[k][0]) / (P[k + 1][0] - P[k][0]), s = (1 - Math.cos(Math.PI * t)) / 2;
        return P[k][1] + (P[k + 1][1] - P[k][1]) * s;
      }
    }
    return 0;
  },
  /** La lame en (u, x) (x en largeurs) : le profil, moins les deux encoches et les deux trous. */
  dedans(u, x) {
    if (x > this.bord(u)) return false;
    // les encoches rondes, au bord, près de la base
    if (Math.hypot((x - this.bord(0.24) + 0.06) * this.M, (u - 0.24) * this.L) < 7.4) return false;
    return true;
  },
  trou(u, x) {
    return ((x - 0.44) / 0.15) ** 2 + ((u - 0.66) / 0.065) ** 2 < 1;
  },
  /** Le dôme en relief : droit sur les côtés, arrondi en haut, une petite bosse au sommet entre les deux trous. */
  dome(u, x) {
    if (u < 0.04 || u > 0.7) return false;
    if (u <= 0.38) return x < 0.6;
    if (((u - 0.38) / 0.22) ** 2 + (x / 0.6) ** 2 < 1) return true;
    return (x / 0.16) ** 2 + ((u - 0.62) / 0.06) ** 2 < 1;
  },
  forme(i, j, v) {
    const I = i + 0.5, J = j + 0.5, c = 26;
    // la poignée : tressée en losanges, un pommeau sombre au bout
    if (I < this.manche) {
      if (Math.abs(J - c) > 2.2) return null;
      if (I < 1.6) return "o";
      const dj = J - c, p = ((I + dj * 1.7) % 3.4 + 3.4) % 3.4 < 0.8 || ((I - dj * 1.7) % 3.4 + 3.4) % 3.4 < 0.8;
      return p ? "C" : "c";
    }
    const u = (I - this.manche) / this.L, x = Math.abs(J - c) / this.M;
    if (!this.dedans(u, x) || this.trou(u, x)) return null;
    const ex = 1 / this.M, eu = 1 / this.L;
    // le bord biseauté : la marche extérieure plus claire
    const auBord = !this.dedans(u, x + 1.2 * ex) || !this.dedans(u + 1.2 * eu, x) || !this.dedans(u - 1.2 * eu, x)
      || this.trou(u, x + ex) || this.trou(u, x - ex) || this.trou(u + eu, x) || this.trou(u - eu, x);
    if (auBord) return "b";
    if (Math.abs(J - c) < 0.6) return v === "chakra" ? "f" : "r";
    // le dôme : un liseré sombre tout autour, puis le relief
    const D = this.dome(u, x);
    if (D && (!this.dome(u, x + ex) || !this.dome(u + eu, x) || !this.dome(u - eu, x))) return "m";
    if (D) return x < 0.3 ? "d" : "D";
    return "a";
  },
  epais(i, j, k) {
    if (k === "c" || k === "C") return 3.2;
    if (k === "o") return 3.8;
    if (k === "b") return 1.6;
    const I = i + 0.5, J = j + 0.5, u = (I - this.manche) / this.L, x = Math.abs(J - 26) / this.M;
    const plein = 2.4 + 1.4 * Math.max(0, 1 - x / Math.max(0.1, this.bord(u)));
    return k === "d" || k === "D" || k === "m" || k === "r" || k === "f" ? plein + (this.dome(u, x) ? 1.2 : 0) : plein;
  },
  teinte(i, j) { return 1; },
};

/**
 * Kabutowari (Jinin), image 5 : la hache (un bloc vert-de-gris en haut du manche, une grande lame recourbée qui
 * descend, le fil clair)…
 */
const ANNEAU = (i, j, ci, cj) => { const d = Math.hypot(i + 0.5 - ci, j + 0.5 - cj); return d < 2.7 && d >= 1.2; };
const HACHE = {
  n: [42, 24], g: [8, 4], ech: 0.85, ep: 2,
  pal: { R: { c: [150, 154, 162] }, b: { c: CORDE }, B: { c: CORDE_F }, k: { c: [70, 82, 80] }, K: { c: [46, 56, 56] }, a: { c: [80, 92, 90] }, e: { c: [220, 226, 232] }, E: { c: [160, 168, 174] } },
  lame(i, j) {
    if (i < 18 || i > 41 || j < 11) return false;
    const jmax = 11 + 12.5 * Math.sqrt(Math.max(0, 1 - ((i - 31) / 12.5) ** 2));
    const jmin = i >= 31 ? 11 : 11 + (31 - i) * 0.75;
    return j < jmax && j >= jmin;
  },
  forme(i, j) {
    if (i <= 4) return ANNEAU(i, j, 2.5, 4) ? "R" : null;
    if (i >= 31 && j <= 10) return i === 31 || i === 41 || j === 0 || j === 10 ? "K" : "k";
    if (j >= 3 && j <= 4) return (i + (j === 3 ? 0 : 2)) % 4 < 2 ? "b" : "B";
    if (!this.lame(i, j)) return null;
    if (!this.lame(i, j + 2)) return "e";
    if (!this.lame(i, j + 3)) return "E";
    return "a";
  },
  epais(i, j, k) { return k === "R" ? 1.5 : k === "k" || k === "K" ? 4.5 : k === "b" || k === "B" ? 2.4 : k === "e" ? 1 : 1.8; },
};
/** … et le marteau : un tonneau bleu nuit avec une bande de corde au milieu ; la corde relie les deux anneaux. */
const MARTEAU = {
  n: [42, 26], g: [8, 12.5], ech: 0.85, ep: 2,
  pal: { R: { c: [150, 154, 162] }, b: { c: CORDE }, B: { c: CORDE_F }, n: { c: [60, 64, 94] }, N: { c: [46, 48, 72] }, l: { c: [92, 98, 132] } },
  r(j) { return 7.4 + 1.0 * Math.sin((Math.PI * (j + 0.5)) / 26); },
  forme(i, j) {
    if (i <= 4) return ANNEAU(i, j, 2.5, 13) ? "R" : null;
    if (i <= 25) return j >= 12 && j <= 13 ? ((i + (j === 12 ? 0 : 2)) % 4 < 2 ? "b" : "B") : null;
    if (Math.abs(i + 0.5 - 33.5) > this.r(j)) return null;
    if (j >= 11 && j <= 14) return (i + j) % 4 < 2 ? "b" : "B";
    if (j <= 1 || j >= 24) return "l";
    return (i + 1) % 5 === 0 ? "N" : "n";
  },
  epais(i, j, k) {
    if (k === "R") return 1.5;
    if (i <= 25) return 2.4;
    return rond(Math.abs(i + 0.5 - 33.5), this.r(j) + 0.4, 2 * this.r(j) + (j >= 11 && j <= 14 ? 1 : 0), 2);
  },
  teinte(i, j) { return i <= 25 ? 1 : 1 - 0.32 * ((i + 0.5 - 33.5) / this.r(j)) ** 2; },
};

/**
 * Kiba (Raiga, Ameyuri), image 6 : deux longues épées fines, avec des pointes recourbées qui sortent de la lame
 * (comme des branches d'éclair), une garde ronde, un manche sombre ; le bout de chaque pointe crépite.
 */
const KIBA = {
  n: [54, 10], g: [5, 4.5], ech: 0.58, ep: 1.2,
  pal: { m: { c: [38, 42, 58], ep: 2 }, M: { c: [72, 78, 104], ep: 2 }, t: { c: [50, 54, 70], ep: 2.6 }, a: { c: [176, 198, 228] }, e: { c: [230, 240, 252] }, f: { c: [140, 225, 255], g: 0.9 } },
  forme(i, j) {
    if (i <= 10) return j >= 4 && j <= 5 ? (i % 3 === 0 ? "M" : "m") : null;
    if (i <= 12) return j >= 2 && j <= 7 && !((j === 2 || j === 7) && i === 11) ? "t" : null;
    if (i <= 49 && (j === 4 || j === 5)) return j === 4 ? "e" : "a";
    if (i <= 52 && j === 4) return "e";
    for (const [i0, s, n] of [[17, 1, 3], [26, -1, 5], [36, 1, 5], [44, -1, 4]]) {
      for (let k = 1; k <= n; k++) {
        const pi = i0 + k, pj = s > 0 ? 5 + Math.round(k * 0.8) : 4 - Math.round(k * 0.8);
        if (j === pj && (i === pi || (k <= 2 && i === pi - 1))) return k === n ? "f" : "a";
      }
    }
    return null;
  },
};

/** Nuibari (Kushimaru), image 7 : très longue et très fine, un manche clair bagué, un anneau au bout pour le fil. */
const NUI = {
  n: [84, 3], g: [10, 1], ech: 0.5, ep: 1,
  pal: { r: { c: [130, 134, 142] }, t: { c: [200, 180, 134] }, T: { c: [96, 82, 62] }, c: { c: [120, 124, 132] }, a: { c: [150, 154, 162] }, A: { c: [184, 188, 196] }, e: { c: [226, 230, 236] } },
  forme(i, j) {
    if (i <= 2) return i === 1 && j === 1 ? null : "r";
    if (i <= 19) return i % 4 === 0 ? "T" : "t";
    if (i <= 21) return "c";
    return j === 1 ? (i > 76 ? "e" : i % 6 < 3 ? "a" : "A") : null;
  },
  epais(i, j, k) { return k === "r" ? 1.2 : k === "t" ? 2.8 : k === "T" ? 3.1 : k === "c" ? 2.4 : Math.max(0.6, 1.4 - (0.7 * (i - 22)) / 62); },
};

/** Un cœur en pixels. */
const COEUR = {
  n: [6, 7], g: [3, 3], ech: 1, ep: 1.5, pal: { r: { c: [232, 40, 52], g: 0.7 } },
  forme(i, j) { const r = ["0001000", "0011100", "0111110", "1111111", "1111111", "0110110"][i]; return r[j] === "1" ? "r" : null; },
};
/** Un parchemin explosif. */
const PARCHEMIN = {
  n: [9, 4], g: [4, 2], ech: 1, ep: 0.4, pal: { p: { c: [236, 220, 178] }, q: { c: [200, 40, 30], g: 0.4 } },
  forme(i, j) { return (j === 1 || j === 2) && i >= 2 && i <= 6 ? "q" : "p"; },
};

/** Le pixel (i, j) d'une lame, dans le repère d'un cadre : o (la prise), L (vers la pointe), W (vers j croissant). */
function repere(frame) {
  const L = norm(frame.L);
  let W = sub(frame.W, mul(L, dot(frame.W, L)));
  if (len(W) < 1e-3) W = cross(L, [0.3, 0.2, 1]);
  W = norm(W);
  return { o: frame.o, L, W, N: cross(L, W) };
}
const pointLame = (def, R, i, j, e = def.ech) => add(R.o, add(mul(R.L, (i - def.g[0]) * e), mul(R.W, (j - def.g[1]) * e)));

/** Dessine une lame. chakra : en grand, transparente et lumineuse (la lame de chakra de Hiramekarei). */
/** Une arme en voxels (x en travers, y le long, z vers l'avant) : seules les faces à l'air libre sont dessinées. */
function lameVoxels(E, def, R, e, chaine, variante) {
  const vox = def.voxels(variante ?? "normale"), plein3 = new Set(vox.map((v) => v[0] + "," + v[1] + "," + v[2]));
  for (const [x, y, z, r, g, b] of vox) {
    const c = add(R.o, add(add(mul(R.W, (x + 0.5) * e), mul(R.L, (y + 0.5 - def.g[0]) * e)), mul(R.N, (z + 0.5) * e)));
    for (const [dx, dy, dz] of [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]]) {
      if (plein3.has(x + dx + "," + (y + dy) + "," + (z + dz))) continue;
      const n = add(add(mul(R.W, dx), mul(R.L, dy)), mul(R.N, dz));
      const [u, w] = dx ? [R.L, R.N] : dy ? [R.N, R.W] : [R.W, R.L];
      const m = add(c, mul(n, e / 2)), U = mul(u, e / 2), V = mul(w, e / 2);
      const pts = [add(m, add(U, V)), add(m, sub(U, V)), sub(m, add(U, V)), add(m, sub(V, U))].map(chaine.p);
      plein(E, pts, mul([r, g, b], ombre(chaine.n(n))));
    }
  }
  return R;
}

function lame(E, def, frame, chaine, { variante, echelle = 1, chakra = null } = {}) {
  const R = repere(frame), e = def.ech * echelle;
  if (def.voxels) return lameVoxels(E, def, R, e, chaine, variante);
  const cell = (i, j) => (i < 0 || j < 0 || i >= def.n[0] || j >= def.n[1] ? null : def.forme(i, j, variante));
  const epA = (i, j) => {
    const k = cell(i, j);
    if (!k) return 0;
    return def.epais?.(i, j, k, variante) ?? def.pal[k].ep ?? def.ep ?? 1;
  };
  for (let i = 0; i < def.n[0]; i++) for (let j = 0; j < def.n[1]; j++) {
    const k = cell(i, j);
    if (!k) continue;
    const P = def.pal[k], t = epA(i, j);
    const c = pointLame(def, R, i, j, e);
    const hl = e / 2, hn = (t * e) / 2;
    const faces = [[R.N, R.L, R.W, hn, hl, hl], [mul(R.N, -1), R.W, R.L, hn, hl, hl]];
    if (epA(i + 1, j) < t) faces.push([R.L, R.W, R.N, hl, hl, hn]);
    if (epA(i - 1, j) < t) faces.push([mul(R.L, -1), R.N, R.W, hl, hn, hl]);
    if (epA(i, j + 1) < t) faces.push([R.W, R.N, R.L, hl, hn, hl]);
    if (epA(i, j - 1) < t) faces.push([mul(R.W, -1), R.L, R.N, hl, hl, hn]);
    const ton = (def.net ? 1 : 0.94 + 0.12 * hache(i, j, def.n[0] * 7 + def.n[1])) * (def.teinte?.(i, j, variante) ?? 1);
    for (const [n, u, v, h, hu, hv] of faces) {
      const m = add(c, mul(n, h)), U = mul(u, hu), V = mul(v, hv);
      const pts = [add(m, add(U, V)), add(m, sub(U, V)), sub(m, add(U, V)), add(m, sub(V, U))].map(chaine.p);
      if (chakra) { voile(E, pts, chakra, P.g ? 0.5 : 0.26, P.g ? 1.2 : 0.6); continue; }
      plein(E, pts, P.g ? P.c : mul(P.c, ombre(chaine.n(n)) * ton), P.g ?? 0);
    }
  }
  return R;
}

// ================================================================== les poses et les coups

/** La prise de la main (dans le repère du corps), selon la direction du bras. */
function mainCorps(o, cote) {
  const nom = cote === "G" ? "brasG" : "brasD", piv = PIVOTS[nom];
  return add(piv, versDir(o[nom] ?? [0, -1, 0.05])(sub(MAIN[nom], piv)));
}

/** Mêle deux poses clés (u de 0 à 1). */
function mele(a, b, u) {
  const r = {};
  for (const cle of new Set([...Object.keys(a), ...Object.keys(b)])) {
    const x = a[cle], y = b[cle];
    if (x === undefined) r[cle] = y;
    else if (y === undefined) r[cle] = x;
    else if (typeof x === "number") r[cle] = x + (y - x) * u;
    else if (cle === "armes") r[cle] = x.map((p, i) => (y[i]?.L && p.L ? { ...p, L: norm(lerp(p.L, y[i].L, u)), W: norm(lerp(p.W, y[i].W, u)) } : p));
    else if (x.length === 3) r[cle] = norm(lerp(x, y, u));
    else r[cle] = x.map((v, i) => v + (y[i] - v) * u);
  }
  return r;
}
const surCle = (cles, t) => { const n = cles.length - 1, s = Math.min(n - 1e-9, t * n), k = Math.floor(s); return mele(cles[k], cles[k + 1], s - k); };
/** La pose de base, avec une pose clé par-dessus (les armes sont mêlées une à une). */
const avec = (base, cle) => ({ ...base, ...cle, armes: (base.armes ?? []).map((a, i) => ({ ...a, ...(cle.armes?.[i] ?? {}) })) });

/** Les cadres des armes (dans le repère du corps), et le bras gauche qui rejoint le manche quand c'est à deux mains. */
function preparer(o) {
  const p = { ...o };
  const cadres = (p.armes ?? []).map((a) => (a.cote ? { o: mainCorps(p, a.cote), L: a.L, W: a.W } : { o: a.o, L: a.L, W: a.W }));
  if (p.deuxMains && cadres[0]) {
    const R = repere(cadres[0]);
    p.brasG = norm(sub(sub(R.o, mul(R.L, p.armes[0].def.ech * (p.deuxMains === true ? 3.4 : p.deuxMains))), PIVOTS.brasG));
  }
  return { p, cadres };
}

/** Le ninja et ses armes, dans cette pose. */
function ninja(E, o) {
  const { p, cadres } = preparer(o);
  const C = perso(E, o.peau ?? NINJA, p);
  (p.armes ?? []).forEach((a, k) => lame(E, a.def, cadres[k], C.corps, { variante: a.variante }));
  return { C, cadres, p };
}

/** La traînée d'un coup : un ruban entre le milieu et la pointe de la lame, de plus en plus vif vers la fin. */
function trainee(E, base, coup) {
  const N = 44, def = base.armes[coup.arme].def, A = [], B = [], B2 = [];
  for (let s = 0; s <= N; s++) {
    const o = avec(base, surCle(coup.cles, (s / N) * (coup.jusqua ?? 1)));
    const { p, cadres } = preparer(o);
    const C = chaines(p), R = repere(cadres[coup.arme]);
    const mj = coup.j ?? def.n[1] / 2 - 0.5;
    A.push(C.corps.p(pointLame(def, R, (coup.fin ?? def.n[0]) * (coup.f0 ?? 0.4), mj)));
    const fin = coup.fin ?? def.n[0];
    B.push(C.corps.p(pointLame(def, R, fin - 0.5, mj)));
    B2.push(C.corps.p(pointLame(def, R, fin + 1.2, mj)));
  }
  const pale = coup.couleur ?? [235, 240, 255], vif = coup.vif ?? [255, 255, 255];
  for (let s = 0; s < N; s++) {
    const t = (s + 1) / N, a = (coup.force ?? 1) * (0.04 + 0.3 * t * t), av = 0.1 + 0.8 * t * t;
    voile(E, [A[s], A[s + 1], B[s + 1], B[s]], pale, a, coup.lueur ?? 0.2);
    voile(E, [B[s], B[s + 1], B2[s + 1], B2[s]], vif, av, coup.lueur ? coup.lueur * 2 : 0.5);
  }
}

// ================================================================== les cases

const CW = 420, CH = 400, M = 58;
const CIEL = [[112, 128, 144], [198, 206, 212]];
const NUIT = [[30, 34, 46], [74, 80, 98]];

function cas({ cam = {}, solide = () => {}, effets = () => {}, avecSol = true, ciel = CIEL, flou = true }) {
  const E = ecran({ w: CW, h: CH, cible: [0, 1.0, 0], lacet: -32, tangage: 10, distance: 5.2, fov: 44, ciel, ...cam });
  if (avecSol) sol(E, 16);
  solide(E);
  if (flou) brume(E);
  effets(E);
  briller(E, 5, 0.9);
  return E;
}

const monde = { p: (v) => mul(v, K), n: (d) => norm(d) };

/** L'arme seule, en grand, de trois quarts, sur fond sombre. */
function vitrine(armes, { distance, cible = [0, 0, 0], lacet = 0, tangage = 6, effets = () => {} } = {}) {
  return cas({
    cam: { cible, lacet, tangage, distance, fov: 40 }, avecSol: false, ciel: NUIT, flou: false,
    solide: (E) => armes.forEach((a) => lame(E, a.def, a.frame, monde, { variante: a.variante })), effets,
  });
}
/** Un cadre qui centre la lame sur le point c (en blocs), penchée de `pente` et tournée de `tour` degrés. */
function centree(def, c, pente = 18, tour = 30, rouleau = 0) {
  const L = rotY(rotZ([0, 1, 0], -pente * DEG), tour * DEG), W0 = rotY(rotZ([1, 0, 0], -pente * DEG), tour * DEG);
  const W = rodrigues(W0, L, rouleau * DEG);
  const milieu = add(mul(L, (def.n[0] / 2 - def.g[0]) * def.ech), mul(W, (def.n[1] / 2 - def.g[1]) * def.ech));
  return { o: sub(mul(c, 1 / K), milieu), L, W };
}

/** Une lame posée dans le monde (en blocs), centrée sur c. */
function lameMonde(E, def, c, L, W, options = {}) {
  const R = repere({ o: [0, 0, 0], L, W }), k = options.echelle ?? 1;
  const milieu = add(mul(R.L, (def.n[0] / 2 - def.g[0]) * def.ech * k), mul(R.W, (def.n[1] / 2 - def.g[1]) * def.ech * k));
  return lame(E, def, { o: sub(mul(c, 1 / K), milieu), L: R.L, W: R.W }, monde, options);
}

/** Une corde (ou une chaîne) qui pend entre deux points. */
function corde(E, A, B, creux, n = 18, couleurs = [CORDE, CORDE_F], taille = 0.06) {
  const mil = add(lerp(A, B, 0.5), mul(creux, 2));
  let prec = A;
  for (let k = 1; k <= n; k++) {
    const t = k / n, p = add(add(mul(A, (1 - t) ** 2), mul(mil, 2 * t * (1 - t))), mul(B, t * t));
    cube(E, lerp(prec, p, 0.5), taille, couleurs[k % 2], { graine: k + 3 });
    prec = p;
  }
}
/** Le centre de l'anneau au bout du manche (Kabutowari), dans le monde. */
const anneauDe = (C, a, cadre) => C.corps.p(pointLame(a.def, repere(cadre), 2, a.def === HACHE ? 3.5 : 12.5));

// ------------------------------------------------------------------ 1. Kubikiribôchô
const kubiDos = { def: KUBI, o: [-2.6, 38.5, -4.9], L: norm([0.2, -1, 0]), W: norm([-1, -0.22, 0]) };
const kubiCoup = { deuxMains: true, brasG: [0, -1, 0], armes: [{ def: KUBI, cote: "D", L: [0, -0.55, 1], W: [0, 1, 0.55] }] };
const kubiCles = [
  { brasD: norm([0.15, 0.95, -0.3]), penche: -8, jambeD: 0, jambeG: 0, armes: [{ L: norm([0.05, 0.12, -1]), W: [0, -1, -0.12] }] },
  { brasD: norm([0.1, 0.92, 0.42]), penche: 4, armes: [{ L: norm([0.02, 1, 0.3]), W: [0, 0.3, -1] }] },
  { brasD: norm([0.05, -0.32, 1]), penche: 24, jambeD: -32, jambeG: 18, armes: [{ L: norm([0, -0.5, 1]), W: [0, 1, 0.5] }] },
];

// ------------------------------------------------------------------ 2. Samehada
const sameDos = { def: SAME, o: [5.8, 33, -6.0], L: norm([-0.4, -1, 0]), W: [1, -0.4, 0] };
const sameCles = [
  { brasD: norm([-1, 0.25, 0.15]), torse: -50, armes: [{ L: norm([-0.7, 0.25, -0.65]), W: [0, 1, 0] }] },
  { brasD: norm([-0.25, 0.1, 1]), torse: -5, armes: [{ L: norm([0.05, 0.15, 1]), W: [0, 1, 0] }] },
  { brasD: norm([0.6, 0.02, 1]), torse: 38, armes: [{ L: norm([1, 0.08, 0.3]), W: [0, 1, 0] }] },
];

// ------------------------------------------------------------------ 3. Kiba
const kibaG = { def: KIBA, o: [5.2, 29, -3.4], L: norm([-0.5, -1, 0]), W: norm([1, -0.5, 0]) };
const kibaD = { def: KIBA, o: [-5.2, 29, -2.8], L: norm([0.5, -1, 0]), W: norm([1, 0.5, 0]) };
const kibaCles = [
  { brasD: norm([-0.6, 0.85, 0.3]), brasG: norm([0.6, 0.85, 0.3]), penche: -4,
    armes: [{ L: norm([-0.45, 1, -0.3]), W: [1, 0.3, 0] }, { L: norm([0.45, 1, -0.3]), W: [-1, 0.3, 0] }] },
  { brasD: norm([-0.1, 0.25, 1]), brasG: norm([0.1, 0.25, 1]), penche: 6,
    armes: [{ L: norm([0.1, 0.5, 1]), W: [1, 0, 0] }, { L: norm([-0.1, 0.5, 1]), W: [-1, 0, 0] }] },
  { brasD: norm([0.55, -0.45, 1]), brasG: norm([-0.55, -0.45, 1]), penche: 14, jambeD: -22, jambeG: 14,
    armes: [{ L: norm([0.8, -0.55, 0.35]), W: [0.4, 0.7, 0] }, { L: norm([-0.8, -0.55, 0.35]), W: [-0.4, 0.7, 0] }] },
];

// ------------------------------------------------------------------ 4. Nuibari
const nuiCote = { def: NUI, o: [5.0, 14.2, 3.5], L: norm([0.05, -0.12, -1]), W: [0, 1, -0.1] };
const nuiCles = [
  { brasD: norm([-0.3, -0.5, -0.45]), torse: 10, penche: 0, jambeD: 0, jambeG: 0, armes: [{ L: norm([0, 0.1, 1]), W: [0, 1, 0] }] },
  { brasD: norm([0, 0.04, 1]), torse: -18, penche: 14, jambeD: -46, jambeG: 26, armes: [{ L: norm([0, 0.02, 1]), W: [0, 1, 0] }] },
];

// ------------------------------------------------------------------ 5. Shibuki
const shibDos = { def: SHIB, o: [-10.5, 23, -6.6], L: norm([1, 0.08, 0]), W: [0, 1, 0] };
const shibCles = [
  { brasD: norm([-0.65, 0.8, 0.15]), torse: -25, penche: -4, armes: [{ L: norm([-0.55, 0.85, -0.45]), W: [0.5, 0.3, -0.6] }] },
  { brasD: norm([-0.1, 0.55, 1]), torse: 0, penche: 6, armes: [{ L: norm([0.05, 0.7, 1]), W: [0.7, 0, 0] }] },
  { brasD: norm([0.5, -0.45, 1]), torse: 25, penche: 16, jambeD: -26, jambeG: 16, armes: [{ L: norm([0.8, -0.6, 0.45]), W: [0.5, 0.5, -0.6] }] },
];

// ------------------------------------------------------------------ 6. Kabutowari (dans le dos, en croix : elles sont énormes)
const hacheDos = { def: HACHE, o: [-1.9, 14.7, -4.2], L: norm([0.45, 1, 0]), W: norm([1, -0.45, 0]) };
const marteauDos = { def: MARTEAU, o: [0, 17.6, -7.2], L: norm([-0.45, 1, 0]), W: norm([1, 0.45, 0]) };
const kabuCles = [
  { brasG: norm([0.15, 1, -0.25]), armes: [{}, { L: norm([0.05, 0.25, -1]), W: [1, 0, 0] }] },
  { brasG: norm([0.05, 0.92, 0.45]), armes: [{}, { L: norm([0, 1, 0.35]), W: [1, 0, 0] }] },
  { brasG: norm([-0.12, 0.12, 1]), armes: [{}, { L: norm([-0.25, -0.3, 1]), W: [1, 0, 0] }] },
];

// ------------------------------------------------------------------ 7. Hiramekarei
const hiraDos = { def: HIRA, o: [-2, 33.5, -3.8], L: norm([0.12, -1, 0]), W: [1, 0.12, 0] };
const hiraCles = [
  { brasD: norm([0.35, -0.85, 0.45]), torse: 20, penche: 8, armes: [{ L: norm([0.55, -0.75, 0.45]), W: [-0.6, -0.4, 0.4] }] },
  { brasD: norm([0.05, 0.05, 1]), torse: 0, penche: 4, armes: [{ L: norm([0.1, 0.2, 1]), W: [-1, 0, 0] }] },
  { brasD: norm([-0.25, 0.85, 0.55]), torse: -18, penche: -6, jambeD: 16, jambeG: -14, armes: [{ L: norm([-0.35, 1, 0.3]), W: [-0.9, -0.35, 0] }] },
];

const T = (titre, sous) => ({ titre, sous });
const ROUGE = { teinte: [255, 40, 40], teinteK: 0.35 };
const CASES = [
  // ---------------- 1. KUBIKIRIBOCHO
  [T("1. KUBIKIRIBOCHO", "ZABUZA - LE GRAND COUPERET, PLUS GRAND QUE TOI"), () => vitrine([{ def: KUBI, frame: centree(KUBI, [0, 0, 0], -32, 18) }], { distance: 4.0 })],
  [T("DANS LE DOS", "EN TRAVERS, LA POIGNEE AU-DESSUS DE L'EPAULE"), () => cas({ cam: { lacet: 150, tangage: 12, distance: 5.0, cible: [0, 1.15, 0] }, solide: (E) => ninja(E, { armes: [kubiDos] }) })],
  [T("CLIC GAUCHE : COUP DU BOURREAU", "A DEUX MAINS, DE TOUT EN HAUT JUSQU'EN BAS. LENT, TRES FORT"), () => {
    const base = avec(kubiCoup, kubiCles[2]);
    return cas({ cam: { lacet: -62, tangage: 8, distance: 6.2, cible: [0, 1.3, 1.2] },
      solide: (E) => { ninja(E, base); perso(E, STEVE, cible([0.1, 0, 3.1], { ...ROUGE, penche: 10 })); },
      effets: (E) => trainee(E, base, { arme: 0, cles: kubiCles, f0: 0.35, force: 0.55 }) });
  }],
  [T("CLIC DROIT : LANCER TOURNOYANT", "ELLE COUPE TOUT SUR SON CHEMIN ET REVIENT DANS LA MAIN"), () => {
    const lance = { brasD: norm([0.6, 0.05, 1]), brasG: norm([-0.25, -0.6, -0.6]), torse: 35, penche: 8, jambeD: -20, jambeG: 15 };
    const c = [0.5, 1.25, 3.4], ang = 62 * DEG;
    return cas({ cam: { lacet: -38, tangage: 24, distance: 9.0, cible: [0.4, 0.8, 3.0] },
      solide: (E) => {
        ninja(E, lance);
        lameMonde(E, KUBI, c, [Math.sin(ang), 0.05, Math.cos(ang)], [Math.cos(ang), 0, -Math.sin(ang)]);
        perso(E, STEVE, cible([-0.8, 0, 5.0], { ...ROUGE, lacet: 160 }));
        perso(E, STEVE, cible([2.1, 0, 5.9], { ...ROUGE, lacet: 200 }));
      },
      effets: (E) => {
        anneau(E, c, 0.7, 1.32, [235, 242, 255], (t) => 0.06 + 0.22 * t, 0.3, { de: 0, a2: 320 });
        anneau(E, c, 1.25, 1.34, [255, 255, 255], (t) => 0.1 + 0.7 * t, 0.6, { de: 0, a2: 320 });
        const centre = [0.6, 1.25, 4.3], pt = (th) => add(centre, [1.8, 0, 3.6].map((r, i) => (i === 0 ? r * Math.cos(th) : i === 2 ? r * Math.sin(th) : 0)));
        for (let k = 0; k < 40; k += 2) trait(E, pt((-118 + k * 9) * DEG), pt((-118 + (k + 1) * 9) * DEG), [255, 255, 255], 0.75, 0.5, 0.025);
      } });
  }],

  // ---------------- 2. SAMEHADA
  [T("2. SAMEHADA", "KISAME - NORMALE / PLEINE (QUAND ELLE A QUELQU'UN DEDANS)"), () => vitrine([
    { def: SAME, frame: centree(SAME, [-0.62, -0.2, 0], 6, -25, 180) },
    { def: SAME, variante: "pleine", frame: centree(SAME, [0.55, 0, 0], 6, -25, 180) }], { distance: 4.7, tangage: 14 })],
  [T("DANS LE DOS", "LA POIGNEE AU-DESSUS DE L'EPAULE GAUCHE"), () => cas({ cam: { lacet: -150, tangage: 12, distance: 5.0, cible: [0, 1.15, 0] }, solide: (E) => ninja(E, { armes: [sameDos] }) })],
  [T("CLIC GAUCHE : RAPE", "GRAND BALAYAGE A PLAT, LES ECAILLES ARRACHENT"), () => {
    const base = avec({ brasG: norm([0.35, -0.9, 0.1]), armes: [{ def: SAME, cote: "D", L: [1, 0, 0], W: [0, 1, 0] }] }, sameCles[2]);
    return cas({ cam: { lacet: -28, tangage: 26, distance: 6.0, cible: [0.4, 1.0, 1.1] },
      solide: (E) => { ninja(E, base); perso(E, STEVE, cible([1.6, 0, 2.4], { ...ROUGE, lacet: 210, roule: 8 })); },
      effets: (E) => {
        trainee(E, base, { arme: 0, cles: sameCles, f0: 0.45, fin: 70, couleur: [170, 205, 240], vif: [220, 240, 255] });
        for (let k = 0; k < 12; k++) cube(E, [1.6 + (hache(k, 1, 4) - 0.2) * 1.1, 0.9 + hache(k, 2, 4) * 0.8, 2.2 + (hache(k, 3, 4) - 0.5) * 0.7], 0.06, [104, 110, 140], { graine: k });
      } });
  }],
  [T("CLIC DROIT : AVALE", "LA GUEULE S'OUVRE ET L'ENGLOUTIT : 10 S DEDANS, PUIS ELLE LE RECRACHE (OU RE-CLIC DROIT)"), () => {
    const base = { deuxMains: 4, brasD: norm([0.05, 0.05, 1]), penche: 4, jambeD: -26, jambeG: 16,
      armes: [{ def: SAME, variante: "ouverte", cote: "D", L: norm([0, 0.08, 1]), W: [-1, 0, 0] }] };
    return cas({ cam: { lacet: -72, tangage: 14, distance: 7.4, cible: [0, 1.3, 2.4] },
      solide: (E) => {
        const { C, cadres } = ninja(E, base);
        const Rg = repere(cadres[0]);
        E.gueule = C.corps.p(pointLame(SAME, Rg, 84, 0));
        // aspiré la tête la première : on ne voit plus que ses jambes qui dépassent de la gueule
        perso(E, STEVE, cible(add(E.gueule, [0, -0.7, 0.95]), { bascule: 86, teinte: [120, 150, 220], teinteK: 0.3, jambeD: 14, jambeG: -12, brasD: norm([0.4, -0.2, 1]), brasG: norm([-0.4, -0.2, 1]) }));
      },
      effets: (E) => {
        for (let k = 0; k < 12; k++) {
          const a = (k / 12) * TOUR, d = [Math.cos(a) * 0.8, Math.sin(a) * 0.8, 0.5];
          trait(E, add(E.gueule, d), add(E.gueule, mul(d, 1.7)), [255, 255, 255], 0.55, 0.4, 0.01);
        }
      } });
  }],

  // ---------------- 3. KIBA
  [T("3. KIBA", "RAIGA, AMEYURI - LES CROCS DE LA FOUDRE"), () => vitrine([
    { def: KIBA, frame: centree(KIBA, [-0.3, 0, 0], 14, 24) },
    { def: KIBA, frame: centree(KIBA, [0.3, 0, 0], -14, -24) }], { distance: 3.4 })],
  [T("DANS LE DOS, EN CROIX", "LES POIGNEES AU-DESSUS DES EPAULES"), () => cas({ cam: { lacet: 160, tangage: 12, distance: 4.8, cible: [0, 1.15, 0] }, solide: (E) => ninja(E, { armes: [kibaD, kibaG] }) })],
  [T("CLIC GAUCHE : CROIX ELECTRIQUE", "LES DEUX LAMES EN X, TRES RAPIDE. CHAQUE COUP PIQUE"), () => {
    const base = avec({ armes: [{ def: KIBA, cote: "D", L: [0, 0, 1], W: [0, 1, 0] }, { def: KIBA, cote: "G", L: [0, 0, 1], W: [0, 1, 0] }] }, kibaCles[2]);
    return cas({ cam: { lacet: -40, tangage: 8, distance: 5.6, cible: [0.1, 1.2, 1.0] },
      solide: (E) => { ninja(E, base); perso(E, STEVE, cible([0.6, 0, 2.6], { teinte: [200, 230, 255], teinteK: 0.35 })); },
      effets: (E) => {
        const el = { cles: kibaCles, f0: 0.4, couleur: [120, 210, 255], vif: [210, 245, 255], lueur: 0.5 };
        trainee(E, base, { ...el, arme: 0 });
        trainee(E, base, { ...el, arme: 1 });
        for (let k = 0; k < 4; k++) eclair(E, [0.3 + (hache(k, 1, 3) - 0.5) * 0.8, 1.0 + hache(k, 2, 3) * 0.8, 2.4], [0.3 + (hache(k, 3, 3) - 0.5) * 1.2, 0.8 + hache(k, 4, 3) * 1.1, 2.6], k + 5, { amp: 0.12, n: 6, ep: 0.012, branches: 0 });
      } });
  }],
  [T("CLIC DROIT : FOUDRE", "LAMES CROISEES EN L'AIR : LA FOUDRE TOMBE OU TU VISES"), () => {
    const base = { brasD: norm([0.3, 1, 0.18]), brasG: norm([-0.3, 1, 0.18]), tete: [-20, 0],
      armes: [{ def: KIBA, cote: "D", L: norm([0.55, 1, 0.1]), W: [1, -0.55, 0] }, { def: KIBA, cote: "G", L: norm([-0.55, 1, 0.1]), W: [-1, -0.55, 0] }] };
    return cas({ cam: { lacet: -30, tangage: 8, distance: 9.0, cible: [0.7, 2.0, 2.4] },
      solide: (E) => {
        const { C, cadres } = ninja(E, base);
        E.pointes = cadres.map((f) => C.corps.p(pointLame(KIBA, repere(f), KIBA.n[0] - 2, 4.5)));
        perso(E, STEVE, cible([1.3, 0, 4.8], { teinte: [210, 240, 255], teinteK: 0.55, lacet: 200, brasD: norm([0.4, -0.6, 0.3]), brasG: norm([-0.4, -0.6, 0.3]), tete: [-30, 0] }));
      },
      effets: (E) => {
        anneau(E, [1.3, 0.03, 4.8], 0, 1.3, [30, 30, 40], 0.5);
        anneau(E, [1.3, 0.04, 4.8], 0.9, 1.35, [140, 220, 255], 0.5, 1);
        eclair(E, [1.0, 9.5, 5.4], [1.3, 0.05, 4.8], 11, { amp: 0.7, n: 18, ep: 0.05, branches: 4 });
        E.pointes.forEach((p, i) => eclair(E, p, add(p, [p[0] * 0.8, 1.1, 0.3]), i + 21, { amp: 0.2, n: 7, ep: 0.02, branches: 1 }));
      } });
  }],

  // ---------------- 4. NUIBARI
  [T("4. NUIBARI", "KUSHIMARU - L'AIGUILLE, TRES LONGUE ET TRES FINE"), () => vitrine([{ def: NUI, frame: centree(NUI, [0, 0, 0], 34, 30) }], {
    distance: 4.0,
    effets: (E) => {
      const chas = mul(pointLame(NUI, repere(centree(NUI, [0, 0, 0], 34, 30)), 1, 1), K);
      let prec = chas;
      for (let k = 1; k <= 60; k++) {
        const t = k / 60, p = add(chas, [0.05 + Math.cos(t * 9) * 0.2 * t + t * 0.5, -0.05 - t * 0.55 + Math.sin(t * 9) * 0.08, Math.sin(t * 9) * 0.2 * t]);
        ligne(E, prec, p, [240, 240, 250], 0.95, 0.4);
        prec = p;
      }
    } })],
  [T("SUR LE COTE", "A LA CEINTURE, A GAUCHE, LE FIL ENROULE"), () => cas({ cam: { lacet: 115, tangage: 12, distance: 5.6, cible: [0, 0.95, -0.8] }, solide: (E) => ninja(E, { armes: [nuiCote] }) })],
  [T("CLIC GAUCHE : ESTOC", "ON PIQUE DROIT DEVANT, TRES LOIN (+2 BLOCS)"), () => {
    const base = avec({ brasG: norm([0.45, -0.4, -1]), armes: [{ def: NUI, cote: "D", L: [0, 0, 1], W: [0, 1, 0] }] }, nuiCles[1]);
    return cas({ cam: { lacet: -76, tangage: 8, distance: 6.6, cible: [0, 1.1, 2.0] },
      solide: (E) => { ninja(E, base); perso(E, STEVE, cible([0.0, 0, 3.9], ROUGE)); },
      effets: (E) => {
        trainee(E, base, { arme: 0, cles: nuiCles, f0: 0.55, force: 0.6 });
        for (let k = 0; k < 5; k++) {
          const y = 1.18 + (hache(k, 1, 2) - 0.5) * 0.35, x = (hache(k, 2, 2) - 0.5) * 0.3;
          trait(E, [x - 0.1, y, 1.4 + hache(k, 3, 2) * 0.6], [x, y, 3.2 + hache(k, 4, 2) * 0.5], [255, 255, 255], 0.5, 0.3, 0.008);
        }
      } });
  }],
  [T("CLIC DROIT : COUTURE", "L'AIGUILLE TRAVERSE 3 ENNEMIS, LE FIL LES RAMENE A TOI"), () => {
    const base = { brasD: norm([0.05, 0.15, 1]), brasG: norm([-0.2, -0.7, -0.4]), penche: -10, jambeD: 14, jambeG: -14, torse: -10 };
    const ennemis = [[0.15, 0, 3.0], [0.35, 0, 4.7], [0.55, 0, 6.4]];
    return cas({ cam: { lacet: -80, tangage: 12, distance: 10.4, cible: [0.3, 1.0, 4.0] },
      solide: (E) => {
        const { C } = ninja(E, base);
        ennemis.forEach((p, k) => perso(E, STEVE, cible(p, { ...ROUGE, teinteK: 0.3, penche: 14, brasD: norm([0, -0.6, 0.8]), brasG: norm([0, -0.6, 0.8]), lacet: 180 + (k - 1) * 8 })));
        lameMonde(E, NUI, [0.8, 1.25, 8.0], [0.02, 0, 1], [0, 1, 0]);
        E.main = C.brasD.p(MAIN.brasD);
      },
      effets: (E) => {
        const pts = [E.main, ...ennemis.map((p) => add(p, [0, 1.25, 0])), [0.8, 1.25, 6.85]];
        for (let k = 0; k + 1 < pts.length; k++) trait(E, pts[k], pts[k + 1], [245, 245, 255], 0.95, 0.8, 0.012);
        for (const p of ennemis) for (let k = 0; k < 6; k++) cube(E, add(p, [(hache(k, 1, p[2] * 9) - 0.5) * 0.5, 1.0 + hache(k, 2, p[2] * 9) * 0.5, -0.3]), 0.05, [210, 40, 40], { graine: k });
        for (const p of ennemis) for (const dy of [0.4, 0.9]) trait(E, add(p, [0, dy, -0.6]), add(p, [0, dy, -1.1]), [255, 255, 255], 0.5, 0.3, 0.008);
      } });
  }],

  // ---------------- 5. SHIBUKI
  [T("5. SHIBUKI", "JINPACHI - LE ROULEAU ET LA LAME AUX PARCHEMINS EXPLOSIFS"), () => vitrine([{ def: SHIB, frame: centree(SHIB, [0, 0, 0], 82, 34, 180) }], { distance: 2.9, tangage: 12 })],
  [T("DANS LE DOS", "A L'HORIZONTALE, LE ROULEAU EN HAUT"), () => cas({ cam: { lacet: 155, tangage: 12, distance: 4.8, cible: [0.15, 1.1, 0] }, solide: (E) => ninja(E, { armes: [shibDos] }) })],
  [T("CLIC GAUCHE : TAILLE EN BIAIS", "UN COUP SUR TROIS FAIT UNE PETITE EXPLOSION"), () => {
    const base = avec({ brasG: norm([-0.2, -0.85, -0.4]), armes: [{ def: SHIB, cote: "D", L: [0, 0, 1], W: [0, 1, 0] }] }, shibCles[2]);
    return cas({ cam: { lacet: -30, tangage: 10, distance: 5.8, cible: [0.3, 1.1, 1.1] },
      solide: (E) => { ninja(E, base); perso(E, STEVE, cible([1.0, 0, 2.6], { teinte: [255, 90, 30], teinteK: 0.35, lacet: 200 })); },
      effets: (E) => { trainee(E, base, { arme: 0, cles: shibCles, f0: 0.35, j: 6, couleur: [255, 220, 170], vif: [255, 240, 200] }); explosion(E, [1.05, 0.75, 2.3], 0.45, 3); } });
  }],
  [T("CLIC DROIT : DEFLAGRATION", "IL FRAPPE LE SOL : UN CONE D'EXPLOSIONS DEVANT"), () => {
    const base = { brasD: norm([0.15, -0.5, 1]), brasG: norm([-0.3, -0.5, -0.6]), penche: 26, jambeD: -34, jambeG: 18,
      armes: [{ def: SHIB, cote: "D", L: norm([0.15, -0.6, 1]), W: [0, 1, 0.6] }] };
    return cas({ cam: { lacet: -58, tangage: 14, distance: 9.0, cible: [0.4, 1.1, 2.9] },
      solide: (E) => {
        ninja(E, base);
        perso(E, STEVE, cible([1.9, 0, 4.6], { saut: 1.0, bascule: -30, roule: 25, teinte: [255, 90, 30], teinteK: 0.35, brasD: norm([-0.5, 0.6, 0.2]), brasG: norm([0.5, 0.6, 0.2]) }));
        for (let k = 0; k < 12; k++) {
          const p = [-0.8 + hache(k, 1, 5) * 2.8, 0.6 + hache(k, 2, 5) * 2.2, 1.6 + hache(k, 3, 5) * 4.2];
          lameMonde(E, PARCHEMIN, p, rotY(rotX([0, 1, 0], hache(k, 4, 5) * 2), hache(k, 5, 5) * 6), rotY([1, 0, 0], hache(k, 6, 5) * 6));
        }
      },
      effets: (E) => { explosion(E, [0.3, 0.35, 2.4], 0.6, 1); explosion(E, [-0.1, 0.55, 3.7], 0.85, 2); explosion(E, [0.6, 0.75, 5.3], 1.15, 4); } });
  }],

  // ---------------- 6. KABUTOWARI
  [T("6. KABUTOWARI", "JININ - LA HACHE ET LE MARTEAU, RELIES PAR UNE CORDE"), () => {
    const fh = centree(HACHE, [-0.42, 0.05, 0], 10, 22), fm = centree(MARTEAU, [0.48, -0.05, 0], -12, 22);
    return vitrine([{ def: HACHE, frame: fh }, { def: MARTEAU, frame: fm }], {
      distance: 3.4,
      effets: (E) => corde(E, mul(pointLame(HACHE, repere(fh), 2, 3.5), K), mul(pointLame(MARTEAU, repere(fm), 2, 12.5), K), [0, -0.2, 0.1], 22),
    });
  }],
  [T("DANS LE DOS, EN CROIX", "ELLES SONT ENORMES : LES TETES AU-DESSUS DES EPAULES"), () => cas({ cam: { lacet: 160, tangage: 12, distance: 5.2, cible: [0, 1.25, 0] },
    solide: (E) => {
      const { C, cadres } = ninja(E, { armes: [hacheDos, marteauDos] });
      corde(E, anneauDe(C, hacheDos, cadres[0]), anneauDe(C, marteauDos, cadres[1]), [0, -0.15, -0.05], 16);
    } })],
  [T("CLIC GAUCHE : CLOU", "LA HACHE ENTRE, LE MARTEAU L'ENFONCE. CASSE LES BOUCLIERS"), () => {
    const base = avec({ brasD: norm([0, -0.12, 1]), armes: [{ def: HACHE, cote: "D", L: norm([0, -0.25, 1]), W: [0, -1, -0.25] }, { def: MARTEAU, cote: "G", L: [0, 0, 1], W: [1, 0, 0] }] }, kabuCles[2]);
    return cas({ cam: { lacet: -58, tangage: 10, distance: 6.0, cible: [0, 1.2, 1.2] },
      solide: (E) => {
        const { C, cadres } = ninja(E, base);
        perso(E, STEVE, cible([0.05, 0, 2.5], { ...ROUGE, penche: 12 }));
        corde(E, anneauDe(C, base.armes[0], cadres[0]), anneauDe(C, base.armes[1], cadres[1]), [0, -0.3, 0], 16);
        E.choc = C.corps.p(pointLame(MARTEAU, repere(cadres[1]), 33, 12.5));
      },
      effets: (E) => {
        trainee(E, base, { arme: 1, cles: kabuCles, f0: 0.6 });
        for (let k = 0; k < 9; k++) { const a = k * 0.7; trait(E, add(E.choc, [Math.cos(a) * 0.3, Math.sin(a) * 0.3, 0.1]), add(E.choc, [Math.cos(a) * 0.6, Math.sin(a) * 0.6, 0.15]), [255, 230, 120], 0.9, 1, 0.01); }
      } });
  }],
  [T("CLIC DROIT : BRISE-CASQUE", "SAUT, ET TOUT ECRASER : ONDE DE CHOC, ILS SONT SONNES"), () => {
    const base = { penche: 34, jambeD: -62, jambeG: 24, brasD: norm([0.05, -0.78, 0.7]), brasG: norm([-0.15, 0.1, 1]),
      armes: [{ def: HACHE, cote: "D", L: norm([0, -0.92, 0.45]), W: [0, -0.45, -0.9] }, { def: MARTEAU, cote: "G", L: norm([-0.3, -0.45, 1]), W: [1, 0, 0.3] }] };
    return cas({ cam: { lacet: -8, tangage: 26, distance: 9.6, cible: [0.1, 0.8, 1.6] },
      solide: (E) => {
        const { C, cadres } = ninja(E, base);
        corde(E, anneauDe(C, base.armes[0], cadres[0]), anneauDe(C, base.armes[1], cadres[1]), [0, -0.25, 0], 16);
        perso(E, STEVE, cible([2.6, 0, 2.6], { saut: 0.8, bascule: -24, roule: -20, lacet: 230, teinte: [255, 230, 90], teinteK: 0.3, brasD: norm([-0.5, 0.5, 0.2]), brasG: norm([0.5, 0.5, 0.2]) }));
        perso(E, STEVE, cible([-2.4, 0, 2.4], { saut: 0.6, bascule: -20, roule: 18, lacet: 140, teinte: [255, 230, 90], teinteK: 0.3, brasD: norm([-0.5, 0.5, 0.2]), brasG: norm([0.5, 0.5, 0.2]) }));
        for (let k = 0; k < 16; k++) {
          const a = hache(k, 1, 6) * TOUR, r = 0.8 + hache(k, 2, 6) * 2.4;
          cube(E, [0.1 + Math.cos(a) * r, 0.1 + hache(k, 3, 6) * 1.2, 1.5 + Math.sin(a) * r], 0.12 + hache(k, 4, 6) * 0.14, [112, 92, 70], { graine: k });
        }
      },
      effets: (E) => {
        const c = [0.1, 0.03, 1.6];
        for (let k = 0; k < 12; k++) { const a = k * 0.53 + hache(k, 1, 8) * 0.3, r = 1.4 + hache(k, 2, 8) * 2.2; trait(E, add(c, [Math.cos(a) * 0.3, 0, Math.sin(a) * 0.3]), add(c, [Math.cos(a) * r, 0, Math.sin(a) * r]), [30, 24, 20], 0.8, 0, 0.02); }
        anneau(E, c, 1.7, 2.1, [255, 240, 200], 0.35, 0.4);
        anneau(E, c, 3.3, 3.6, [255, 240, 200], 0.2, 0.3);
        for (const p of [[2.6, 2.75, 2.6], [-2.4, 2.55, 2.4]]) for (let k = 0; k < 5; k++) cube(E, add(p, [Math.cos(k * 1.26) * 0.35, 0, Math.sin(k * 1.26) * 0.35]), 0.08, [255, 230, 70], { g: 1, graine: k });
      } });
  }],

  // ---------------- 7. HIRAMEKAREI
  [T("7. HIRAMEKAREI", "CHOJURO - LA LAME EN GOUTTE. NORMALE / CHARGEE DE CHAKRA"), () => vitrine([
    { def: HIRA, frame: centree(HIRA, [-0.5, 0, 0], 172, 18) },
    { def: HIRA, variante: "chakra", frame: centree(HIRA, [0.5, 0, 0], 188, 18) }], { distance: 3.9 })],
  [T("DANS LE DOS", "DROITE, LA POINTE EN BAS, LA POIGNEE DOREE EN HAUT"), () => cas({ cam: { lacet: 160, tangage: 12, distance: 4.8, cible: [0, 1.15, 0] }, solide: (E) => ninja(E, { armes: [hiraDos] }) })],
  [T("CLIC GAUCHE : COUP MONTANT", "A DEUX MAINS, DU BAS VERS LE HAUT, UNE TRAINEE DE CHAKRA"), () => {
    const base = avec({ deuxMains: 5, armes: [{ def: HIRA, cote: "D", variante: "chakra", L: [0, 0, 1], W: [0, 1, 0] }] }, hiraCles[2]);
    return cas({ cam: { lacet: -36, tangage: 6, distance: 5.8, cible: [0, 1.3, 1.1] },
      solide: (E) => { ninja(E, base); perso(E, STEVE, cible([-0.2, 0, 2.6], { teinte: [120, 200, 255], teinteK: 0.35, saut: 0.25, bascule: -10 })); },
      effets: (E) => trainee(E, base, { arme: 0, cles: hiraCles, f0: 0.4, couleur: [110, 220, 255], vif: [200, 245, 255], lueur: 0.5 }) });
  }],
  [T("CLIC DROIT : LAME DE CHAKRA", "TU CHARGES, PUIS UNE LAME GEANTE S'ABAT DEVANT TOI"), () => {
    const L = norm([0, 1.5, 1]);
    const base = { deuxMains: 5, brasD: norm([0.05, 0.75, 1]), penche: 6, jambeD: -30, jambeG: 18, armes: [{ def: HIRA, cote: "D", variante: "chakra", L, W: [0, 1, -1.5] }] };
    return cas({ cam: { lacet: -78, tangage: 8, distance: 12.5, cible: [0, 3.2, 2.8] },
      solide: (E) => {
        const { C, cadres } = ninja(E, base);
        perso(E, STEVE, cible([0.2, 0, 5.0], { teinte: [120, 200, 255], teinteK: 0.5, penche: 40, brasD: norm([0.4, -0.3, 0.6]), brasG: norm([-0.4, -0.3, 0.6]) }));
        E.cadre = cadres[0];
        E.C = C;
      },
      effets: (E) => {
        lame(E, HIRA, E.cadre, E.C.corps, { echelle: 4.4, chakra: [110, 220, 255], variante: "chakra" });
        for (let k = 0; k < 20; k++) cube(E, [(hache(k, 1, 9) - 0.5) * 2.2, 0.1 + hache(k, 2, 9) * 0.6, 3 + hache(k, 3, 9) * 4], 0.07, [150, 235, 255], { g: 1, graine: k });
      } });
  }],
];

// ================================================================== la planche

const sansAccent = (s) => s.normalize("NFD").replace(/[̀-ͯ]/g, "");
const texte = (img, W, s, x, y, t, c) => ecrireTexte(img, W, sansAccent(s).split("").map((ch) => (POLICE[ch.toUpperCase()] ? ch : " ")).join(""), x, y, t, c);

// les lames, pour le jeu (jeu.mjs) : importer ce fichier ne dessine pas la planche
export { KUBI, SAME, KIBA, NUI, SHIB, HACHE, MARTEAU, HIRA };
const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
const args = process.argv.slice(2);
const essai = args[0] === "essai";
const choix = essai ? args[1].split(",").map(Number) : CASES.map((_, i) => i);
const colonnes = 4, lignes = Math.ceil(choix.length / colonnes), HAUT = essai ? 0 : 70;
const W = CW * colonnes, H = HAUT + (M + CH) * lignes;
const planche = Buffer.alloc(W * H * 4, 255);
if (!essai) {
  texte(planche, W, "LES 7 LAMES DE LA BRUME - CROQUIS 2", 14, 14, 5, [24, 28, 40, 255]);
  texte(planche, W, "PAR LAME : L'ARME / OU ELLE SE RANGE QUAND ON NE LA TIENT PAS / CLIC GAUCHE / CLIC DROIT", 14, 48, 2, [90, 94, 110, 255]);
}
choix.forEach((n, k) => {
  const [{ titre, sous }, f] = CASES[n];
  const E = f();
  const c = k % colonnes, l = Math.floor(k / colonnes), y0 = HAUT + l * (M + CH);
  for (let y = 0; y < CH; y++) E.img.copy(planche, ((y0 + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
  texte(planche, W, titre, c * CW + 10, y0 + 8, 3, [24, 28, 40, 255]);
  const lignesTxt = [""];
  for (const m of sous.split(" ")) {
    const l2 = lignesTxt.length - 1;
    if ((lignesTxt[l2] + " " + m).trim().length > 50) lignesTxt.push(m); else lignesTxt[l2] = (lignesTxt[l2] + " " + m).trim();
  }
  lignesTxt.slice(0, 2).forEach((s, i) => texte(planche, W, s, c * CW + 10, y0 + 30 + i * 13, 2, [96, 100, 116, 255]));
  if (c === 0 && l > 0) for (let x = 0; x < W; x++) for (let e = 0; e < 3; e++) planche.set([60, 70, 90, 255], ((y0 + e) * W + x) * 4);
});
const sortie = path.join(ICI, "..", essai ? "essai.png" : "planche-lames-brume.png");
ecrirePng(sortie, W, H, planche);
console.log("ok", sortie, W, H);
}
