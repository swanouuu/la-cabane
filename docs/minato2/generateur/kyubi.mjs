// Kurama, le renard à neuf queues, fait de pavés jaunes unis comme le Kurama jaune de la capture de l'utilisateur :
// accroupi, le corps long, un long cou, de longues pattes aux longs doigts griffus, neuf queues épaisses en segments, et
// sur chaque segment un trait noir au milieu des flancs et un anneau noir à la jointure. La tête suit le modèle orange
// qu'il a envoyé (couleurs passées au jaune) : des sourcils en colère au-dessus des yeux rouges, un masque noir qui va
// d'un seul trait des yeux jusqu'aux longues oreilles pointues couchées en arrière, un museau au nez noir, la gueule
// rouge ouverte, des dents blanches en zigzag sur fond noir en haut et en bas, des joues en pointes.
// Unités : des pixels de joueur (un joueur fait 32 de haut) ; la bête regarde vers +z, les pattes au sol (y = 0).
import { clair, mix, hache, norme, croix } from "../../fruits/generateur/moteur.mjs";

const ajoute = (a, b) => a.map((v, i) => v + b[i]);
const sous = (a, b) => a.map((v, i) => v - b[i]);
const fois = (a, k) => a.map((v) => v * k);
const DEG = Math.PI / 180;

export const JAUNE = [236, 214, 74];
const JAUNE_CLAIR = [248, 232, 112];
const JAUNE_OMBRE = [206, 182, 50];
const TRAIT = [26, 20, 10];
const DENT = [250, 250, 240];
const GUEULE = [200, 40, 26];
const GUEULE_F = [120, 24, 16];
const OEIL = [214, 32, 20];
const brille = (c) => [...c, 1];
const grain = (c, i, j, g, f = 0.05) => clair(c, 1 - f + 2 * f * hache(i, j, g));

// ------------------------------------------------------------------ le pavé orienté
/**
 * Un pavé : son centre, ses axes [X, Y, Z] et ses demi-tailles, découpé en cases de `pas` (plus petit = plus de
 * pixels). couleur(face, u, v, i, j) : u, v de -1 à 1 sur la face (sur les côtés, v = 1 du côté de +Y ; dessus, v = 1
 * du côté de -Z) ; rend une couleur, ou null pour percer.
 */
function pave(sc, c, [X, Y, Z], [hx, hy, hz], couleur, pas, M) {
  const faces = [
    ["avant", Z, hz, X, hx, Y, hy], ["arriere", fois(Z, -1), hz, fois(X, -1), hx, Y, hy],
    ["gauche", X, hx, fois(Z, -1), hz, Y, hy], ["droite", fois(X, -1), hx, Z, hz, Y, hy],
    ["haut", Y, hy, X, hx, fois(Z, -1), hz], ["bas", fois(Y, -1), hy, X, hx, Z, hz],
  ];
  for (const [nom, n, hn, U, hu, V, hv] of faces) {
    const l = sc.lumiere(M.n(n), false);
    if (l < 0) continue;
    const cf = ajoute(c, fois(n, hn));
    const nu = Math.max(1, Math.round((2 * hu) / pas)), nv = Math.max(1, Math.round((2 * hv) / pas));
    for (let j = 0; j < nv; j++) for (let i = 0; i < nu; i++) {
      const col = couleur(nom, -1 + (2 * (i + 0.5)) / nu, 1 - (2 * (j + 0.5)) / nv, i, j);
      if (!col) continue;
      const p = (a, b) => M.p(ajoute(ajoute(cf, fois(U, -hu + (2 * hu * a) / nu)), fois(V, hv - (2 * hv * b) / nv)));
      sc.quad([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], col[3] === 1 ? col.slice(0, 3) : clair(col, 0.8 + 0.34 * (l - 0.62) / 0.42), 0, M.aile);
    }
  }
}

/** Les axes d'un morceau qui va de a vers b (Y le long ; X reste du côté de `cote`). */
function entre(a, b, cote = [1, 0, 0]) {
  const Y = norme(sous(b, a));
  let Z = croix(cote, Y);
  if (Math.hypot(...Z) < 0.1) Z = croix([0, 0, 1], Y);
  Z = norme(Z);
  return [croix(Y, Z), Y, Z];
}
/** Des axes tournés : tangage (le haut part vers +z), lacet, roulis. */
function axes(tangage = 0, lacet = 0, roulis = 0) {
  const rx = (v) => [v[0], v[1] * Math.cos(tangage) - v[2] * Math.sin(tangage), v[1] * Math.sin(tangage) + v[2] * Math.cos(tangage)];
  const ry = (v) => [v[0] * Math.cos(lacet) + v[2] * Math.sin(lacet), v[1], -v[0] * Math.sin(lacet) + v[2] * Math.cos(lacet)];
  const rz = (v) => [v[0] * Math.cos(roulis) - v[1] * Math.sin(roulis), v[0] * Math.sin(roulis) + v[1] * Math.cos(roulis), v[2]];
  return [[1, 0, 0], [0, 1, 0], [0, 0, 1]].map((v) => ry(rx(rz(v))));
}

// ------------------------------------------------------------------ le pelage et ses traits
/**
 * Le pelage jaune uni (plus clair dessus, plus sombre dessous, un grain léger). o.milieu : le trait noir au milieu des
 * flancs, sur toute la longueur ; o.joint : l'anneau noir au bout du côté -Y ; o.devant, o.dos : un trait au milieu de
 * l'avant ou du dessus ; o.noir(f, u, v) : là où le noir prend la place du jaune (le masque).
 */
function pelage(g, o = {}) {
  const cotes = ["avant", "arriere", "gauche", "droite"];
  return (f, u, v, i, j) => {
    if (o.noir && o.noir(f, u, v)) return TRAIT;
    if (o.joint && cotes.includes(f) && v < -1 + o.joint) return TRAIT;
    if (o.milieu && (f === "gauche" || f === "droite") && Math.abs(u) < o.milieu) return TRAIT;
    if (o.devant && f === "avant" && Math.abs(u) < o.devant) return TRAIT;
    if (o.dos && f === "haut" && Math.abs(u) < o.dos) return TRAIT;
    const base = f === "haut" ? JAUNE_CLAIR : f === "bas" ? JAUNE_OMBRE : mix(JAUNE_OMBRE, JAUNE, 0.55 + 0.45 * (v + 1) / 2);
    return grain(base, i, j, g);
  };
}

/** Une rangée de dents en zigzag, blanches sur fond noir ; vers : -1 (pointes en bas) ou 1 (en haut) ; n dents. */
const zigzag = (vers, n) => (f, u, v) => {
  if (f === "haut" || f === "bas") return TRAIT;
  const t = (((u + 1) / 2) * n) % 1, tri = 1 - 2 * Math.abs(t - 0.5);
  const h = vers < 0 ? (v + 1) / 2 : (1 - v) / 2;
  return h > 1 - tri * 0.95 ? DENT : TRAIT;
};

// ------------------------------------------------------------------ Kurama
/**
 * Kurama entier, dans le repère M (voir monde()). o.gueule : la gueule ouverte, de 0 à 1 ; o.tete : la tête levée
 * (radians) ; o.pas : la taille des cases (0,75 par défaut : des traits nets).
 */
export function kurama(sc, M, o = {}) {
  const g = o.gueule ?? 0.6, pas = o.pas ?? 0.75, levee = o.tete ?? 0;
  const P = (c, ax, demi, couleur, p = pas) => pave(sc, c, ax, demi, couleur, p, M);
  const membre = (a, b, larg, prof, peau) => P(fois(ajoute(a, b), 0.5), entre(a, b), [larg, Math.hypot(...sous(a, b)) / 2 + 1.5, prof], peau);

  // ---- le corps, accroupi : la croupe basse, le poitrail plus haut ; des traits sur les flancs et le dos
  P([0, 50, -48], axes(80 * DEG), [17, 18, 15], pelage(1, { milieu: 0.06, joint: 0.05 }));
  P([0, 57, -16], axes(76 * DEG), [20, 20, 19], pelage(2, { milieu: 0.06, joint: 0.05, dos: 0.06 }));
  P([0, 64, 16], axes(72 * DEG), [25, 19, 22], pelage(3, { milieu: 0.06, joint: 0.05, dos: 0.06 }));

  // ---- les pattes : quatre longs doigts griffus au bout
  const doigts = (poignet, gr) => {
    for (let q = 0; q < 4; q++) {
      const x = (q - 1.5) * 3.4;
      const a = ajoute(poignet, [x, -2, 2]), b = ajoute(poignet, [x * 1.35, -poignet[1] + 1.8, 17]);
      membre(a, b, 1.5, 1.5, pelage(gr + q, { joint: 0.12 }));
      P(ajoute(b, [x * 0.1, -0.4, 3]), axes(-70 * DEG), [1.2, 2.8, 1.2], () => DENT);
    }
  };
  for (const s of [-1, 1]) {
    // devant : l'épaule, le bras qui part en arrière, l'avant-bras en avant
    const epaule = [s * 22, 62, 24], coude = [s * 29, 36, 8], poignet = [s * 26, 9, 38];
    membre(epaule, coude, 7.5, 7.5, pelage(10, { milieu: 0.12, devant: 0.12, joint: 0.06 }));
    membre(coude, poignet, 6, 6, pelage(11, { milieu: 0.14, devant: 0.14, joint: 0.06 }));
    P(coude, axes(), [3.4, 3.4, 3.4], () => TRAIT);
    doigts(poignet, 20);
    // derrière, repliées : la cuisse vers l'avant, le jarret vers l'arrière
    const hanche = [s * 16, 52, -54], genou = [s * 22, 30, -30], jarret = [s * 21, 10, -66];
    membre(hanche, genou, 9, 10, pelage(30, { milieu: 0.1, joint: 0.06 }));
    membre(genou, jarret, 5.5, 5.5, pelage(31, { milieu: 0.14, devant: 0.14, joint: 0.06 }));
    P(genou, axes(), [3.6, 3.6, 3.6], () => TRAIT);
    doigts([s * 21, 7, -62], 40);
  }

  // ---- le cou, long, vers l'avant et le haut
  P([0, 74, 44], axes(58 * DEG + levee), [12, 16, 11], pelage(50, { milieu: 0.08, dos: 0.08 }));

  // ---- la tête, longue, en coin (tout ce qui suit tourne avec la tête levée)
  const H = [0, 80, 62];
  const ici = (v) => ajoute(H, [v[0], v[1] * Math.cos(levee) - v[2] * Math.sin(levee), v[1] * Math.sin(levee) + v[2] * Math.cos(levee)]);
  const TA = axes(-levee);
  const SX = 11, SY = 9, SZ = 11;
  // Sur chaque côté du crâne, la bande noire : elle part de l'œil (devant, à mi-hauteur) et monte en arrière jusqu'au
  // pied de l'oreille ; sur le dessus, elle longe le bord jusqu'à l'oreille. L'œil : une amande rouge en biais (le coin
  // extérieur plus haut), bordée de noir, une fente noire dedans.
  const ligne = (w) => 0.05 + 0.95 * w;
  const coteCrane = (f, u, v) => {
    const w = f === "gauche" ? (u + 1) / 2 : (1 - u) / 2; // 0 devant, 1 derrière
    const dv = v - ligne(w);
    if (w < 0.34) {
      const e = (w - 0.17) / 0.17, demi = 0.2 * Math.sqrt(Math.max(0, 1 - e * e));
      if (Math.abs(dv) < demi) return Math.abs(w - 0.2) < 0.03 ? TRAIT : brille(OEIL);
    }
    if (w < 0.86 && Math.abs(dv) < 0.17 + 0.05 * w) return TRAIT;
    return null;
  };
  P(ici([0, 1, 0]), TA, [SX, SY, SZ], (f, u, v, i, j) => {
    if (f === "gauche" || f === "droite") {
      const c = coteCrane(f, u, v);
      if (c) return c;
    }
    // le coin de l'œil déborde sur l'avant, et la bande longe le dessus jusqu'aux oreilles
    if (f === "avant" && Math.abs(u) > 0.84 && v > -0.2 && v < 0.3) return TRAIT;
    if (f === "haut" && Math.abs(u) > 0.66 && v > -0.1 && v < 0.85) return TRAIT;
    return pelage(51)(f, u, v, i, j);
  });
  // le front qui descend en pente vers le museau (la tête en coin)
  P(ici([0, 6.5, 13]), axes(24 * DEG - levee), [8.5, 3, 6.5], pelage(52));
  // les oreilles : longues, pointues, dressées en arrière et écartées, bien au-dessus de la tête ; le dedans noir
  // (avec un liseré jaune) prolonge la bande des yeux
  for (const s of [-1, 1]) {
    const pied = ici([s * 8, SY + 1.5, -5]);
    const d = norme(ajoute(ajoute(fois(TA[1], 1), fois(TA[2], -0.85)), fois(TA[0], s * 0.4)));
    const Y = d, X = norme(croix(Y, TA[2])), Z = croix(X, Y);
    const ax = s < 0 ? [fois(X, -1), Y, fois(Z, -1)] : [X, Y, Z];
    const dedans = "avant";
    P(ajoute(pied, fois(d, 14)), ax, [6, 15, 2.2], (f, u, v, i, j) => {
      const larg = 1 - (v + 1) * 0.48;
      if (Math.abs(u) > larg) return null;
      if ((f === dedans || f === "arriere") && Math.abs(u) < larg - 0.26 && v < 0.75) return TRAIT;
      return pelage(54 + s)(f, u, v, i, j);
    });
  }
  // les joues : des pointes de fourrure vers l'arrière et l'extérieur
  for (const s of [-1, 1]) {
    P(ici([s * 11.5, -3, 2]), axes(-8 * DEG - levee, s * 34 * DEG), [2.4, 4, 8], pelage(56 + s));
    P(ici([s * 10.5, -7, -1]), axes(-22 * DEG - levee, s * 46 * DEG), [2, 3.2, 6.5], pelage(58 + s));
  }
  // le museau : long (aussi long que le crâne), le trait noir dessus jusqu'au nez noir au bout
  P(ici([0, -2.5, SZ + 14]), TA, [7, 4.5, 14], pelage(60, { noir: (f, u, v) => (f === "haut" && Math.abs(u) < 0.14) || (f === "avant" && v > 0.15 && Math.abs(u) < 0.5) }));
  // les dents du haut, tout le long du museau : un bandeau, dents en zigzag pointées vers le bas
  P(ici([0, -8.3, SZ + 14]), TA, [6.8, 1.5, 13.8], zigzag(-1, 9), 0.3);
  // le palais et le fond de la gueule, rouge sombre
  P(ici([0, -9.9, SZ + 9]), TA, [6.2, 0.4, 13], () => GUEULE_F);
  P(ici([0, -12, SZ + 0.5]), TA, [6.2, 3.6, 0.6], () => GUEULE_F);
  // la mâchoire du bas, aussi longue, ouverte ; la langue rouge dessus, ses dents en zigzag pointées vers le haut
  const J = axes(g * 34 * DEG - levee);
  const charniere = ici([0, -9.4, SZ - 1]);
  const dansJ = (v) => ajoute(charniere, ajoute(ajoute(fois(J[0], v[0]), fois(J[1], v[1])), fois(J[2], v[2])));
  P(dansJ([0, -2.4, 14.5]), J, [6.6, 2.4, 14], pelage(61));
  P(dansJ([0, 0.3, 13.5]), J, [5.4, 0.5, 12.5], () => GUEULE);
  P(dansJ([0, 1, 15]), J, [6.4, 1.2, 13.4], (f, u, v) => (f === "haut" ? null : zigzag(1, 9)(f, u, v)), 0.3);

  // ---- les neuf queues : épaisses, en segments, en grand éventail derrière lui ; elles montent puis se recourbent
  const N = 9, SEG = 7;
  for (let q = 0; q < N; q++) {
    const m = (q - (N - 1) / 2) / ((N - 1) / 2);
    const az = m * 122 * DEG + 6 * DEG * Math.sin(q * 2.1);
    const courbe = 62 + 46 * (1 - Math.abs(m)) + 16 * hache(q, 1, 90);
    const cote = [Math.cos(az), 0, Math.sin(az)];
    let p = [m * 8, 60, -60];
    let d = [0, 1, 0];
    for (let s = 0; s < SEG; s++) {
      const t = s / (SEG - 1);
      const e = (6 + 26 * (1 - Math.abs(m)) + courbe * t ** 1.6) * DEG;
      d = norme([Math.sin(az) * Math.cos(e), Math.sin(e), -Math.cos(az) * Math.cos(e)]);
      const L = 25 - 6 * t, r = 9.5 - 5 * t;
      P(ajoute(p, fois(d, L / 2)), entre(p, ajoute(p, d), cote), [r, L / 2 + 0.8, r], pelage(60 + q * 10 + s, { milieu: 0.12, joint: 0.06 }));
      p = ajoute(p, fois(d, L));
    }
    // le bout, en pointe : trois paliers qui rétrécissent
    for (let k = 0; k < 3; k++) {
      const r = 3.6 - k * 1.1;
      P(ajoute(p, fois(d, 1.5 + k * 2.6)), entre(p, ajoute(p, d), cote), [r, 1.4, r], pelage(200 + q * 3 + k));
    }
  }
}

/** Le placement de Kurama : centre (au sol, sous le poitrail), vers dir, à l'échelle k ; aile : il brille (la lueur). */
export function monde(centre, dir = [0, 0, 1], k = 1, aile = true) {
  const f = norme(dir), d = norme(croix([0, 1, 0], f)), h = croix(f, d);
  const vers = (v) => ajoute(ajoute(fois(d, v[0]), fois(h, v[1])), fois(f, v[2]));
  return { p: (v) => ajoute(centre, fois(vers(v), k)), n: (v) => norme(vers(v)), aile };
}
