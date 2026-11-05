// La main géante en cubes des ailes de la marque : la forme (des volumes arrondis fondus ensemble, des bosses de
// bruit, des palmes entre les doigts, le dos plat), ses couleurs (celles de la peau du skin de la forme 2), puis les cubes.
// Sert à la page ailes.html et à construire.mjs (qui la calcule d'avance dans main-fins.bin et main-gros.bin).
// ------------------------------------------------------------------ petits outils (1 unité = 1 pixel du modèle)
export const hache = (i, j, k) => { let h = (i * 374761393 + j * 668265263 + k * 2147483647) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };
const lisse = (t) => { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); };
const mix = (a, b, k) => a.map((v, i) => v + (b[i] - v) * k);
export const plus = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const moins = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
export const fois = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const scal = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
export const croix = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
export const norme = (v) => { const l = Math.hypot(v[0], v[1], v[2]) || 1; return [v[0] / l, v[1] / l, v[2] / l]; };
const tourner = (v, n, a) => { const c = Math.cos(a), s = Math.sin(a), k = croix(n, v), d = scal(n, v); return v.map((x, i) => x * c + k[i] * s + n[i] * d * (1 - c)); };
const deg = Math.PI / 180;

/** Un bruit doux en 3D (valeurs aléatoires aux coins d'une grille, interpolées). */
function bruit(x, y, z, graine = 0) {
  const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z);
  const u = x - xi, v = y - yi, w = z - zi;
  const a = u * u * (3 - 2 * u), b = v * v * (3 - 2 * v), c = w * w * (3 - 2 * w);
  const h = (i, j, k) => hache(xi + i + graine * 101, yi + j, zi + k);
  const x00 = h(0, 0, 0) + (h(1, 0, 0) - h(0, 0, 0)) * a, x10 = h(0, 1, 0) + (h(1, 1, 0) - h(0, 1, 0)) * a;
  const x01 = h(0, 0, 1) + (h(1, 0, 1) - h(0, 0, 1)) * a, x11 = h(0, 1, 1) + (h(1, 1, 1) - h(0, 1, 1)) * a;
  const y0 = x00 + (x10 - x00) * b, y1 = x01 + (x11 - x01) * b;
  return y0 + (y1 - y0) * c;
}
const fbm = (p, f, g = 0) => bruit(p[0] * f, p[1] * f, p[2] * f, g) * 0.62 + bruit(p[0] * f * 2.3 + 7, p[1] * f * 2.3, p[2] * f * 2.3, g + 1) * 0.38;

// les formes de base, en distance signée (négatif dedans)
function cone(p, a, b, ra, rb) {
  const bx = b[0] - a[0], by = b[1] - a[1], bz = b[2] - a[2];
  const px = p[0] - a[0], py = p[1] - a[1], pz = p[2] - a[2];
  let t = (px * bx + py * by + pz * bz) / (bx * bx + by * by + bz * bz);
  t = t < 0 ? 0 : t > 1 ? 1 : t;
  const dx = px - bx * t, dy = py - by * t, dz = pz - bz * t;
  return [Math.sqrt(dx * dx + dy * dy + dz * dz) - (ra + (rb - ra) * t), t];
}
function ellipsoide(p, c, r) {
  const x = (p[0] - c[0]) / r[0], y = (p[1] - c[1]) / r[1], z = (p[2] - c[2]) / r[2];
  return (Math.sqrt(x * x + y * y + z * z) - 1) * Math.min(r[0], r[1], r[2]);
}
function smax(a, b, k) { return -smin(-a, -b, k); }
function smin(a, b, k) { const h = Math.max(k - Math.abs(a - b), 0) / k; return Math.min(a, b) - h * h * k * 0.25; }

/**
 * Une chaîne : des cônes arrondis bout à bout (un doigt, une griffe, une corne). d(p) donne la distance et t, la
 * place (0 → 1) du point le plus proche le long de la chaîne.
 */
function chaine(points, rayons) {
  const segs = [];
  let long = 0;
  for (let i = 0; i + 1 < points.length; i++) {
    const l = Math.hypot(...moins(points[i + 1], points[i]));
    segs.push({ a: points[i], b: points[i + 1], ra: rayons[i], rb: rayons[i + 1], t0: long, l });
    long += l;
  }
  const centre = fois(points.reduce((s, q) => plus(s, q), [0, 0, 0]), 1 / points.length);
  const rayon = Math.max(...points.map((q, i) => Math.hypot(...moins(q, centre)) + rayons[i]));
  return {
    points, centre, rayon,
    d(p) {
      const loin = Math.hypot(p[0] - centre[0], p[1] - centre[1], p[2] - centre[2]) - rayon;
      if (loin > 1.5) return [loin, 0];
      let best = 1e9, t = 0;
      for (const s of segs) {
        const [d, ts] = cone(p, s.a, s.b, s.ra, s.rb);
        if (d < best) { best = d; t = (s.t0 + ts * s.l) / long; }
      }
      return [best, t];
    },
    /** Le point de l'axe le plus proche de p, et la direction de l'axe là. */
    axe(p) {
      let best = 1e9, q = null, dir = null;
      for (const s of segs) {
        const [d, ts] = cone(p, s.a, s.b, s.ra, s.rb);
        if (d < best) { best = d; q = plus(s.a, fois(moins(s.b, s.a), ts)); dir = norme(moins(s.b, s.a)); }
      }
      return { q, dir };
    },
  };
}
/** Une corne ou un pic : part de base dans la direction dir et se courbe vers vers, en s'affinant jusqu'à une pointe. */
function pic(base, dir, longueur, r, vers = [0, 0, 0], courbe = 0) {
  const n = 6, pts = [base], rs = [r];
  let d = norme(dir), p = base;
  for (let i = 1; i <= n; i++) {
    d = norme(plus(d, fois(vers, courbe / n)));
    p = plus(p, fois(d, longueur / n));
    pts.push(p);
    rs.push(Math.max(0.06, r * Math.pow(1 - i / n, 0.9)));
  }
  return chaine(pts, rs);
}

// ------------------------------------------------------------------ la main (repère de l'aile : X vers l'extérieur, Y en haut, Z vers l'avant du joueur)
// Le dos de la main (bosses, cornes) regarde vers l'avant, comme dans l'anime ; la paume vers l'arrière ; les
// doigts se recourbent vers l'arrière, comme une main qui se referme.
const DOIGTS = [
  // jointure, angle (degrés depuis l'extérieur, vers le haut), longueur, rayon, pli dans le plan (par phalange), pli vers l'arrière, griffe
  { nom: 'pouce', K: [8.5, -6.8, 0.6], phi: -58, L: 14, r: 2.9, plan: 8, arriere: 12, griffe: 7.5, crochet: 40 },
  { nom: 'index', K: [17.5, 10, 0.6], phi: 38, L: 20, r: 2.8, plan: -17, arriere: 10, griffe: 8.5, crochet: -62 },
  { nom: 'majeur', K: [20.5, 5, 0.7], phi: 17, L: 22, r: 2.9, plan: -16, arriere: 10, griffe: 9, crochet: -62 },
  { nom: 'annulaire', K: [21, 0, 0.6], phi: -3, L: 20, r: 2.7, plan: -15, arriere: 10, griffe: 8.5, crochet: -60 },
  { nom: 'auriculaire', K: [19, -5, 0.4], phi: -21, L: 16, r: 2.4, plan: -13, arriere: 11, griffe: 7, crochet: -56 },
];
const PHALANGES = [0.4, 0.33, 0.27], RAYONS = [1, 0.86, 0.74, 0.62];

/** Le squelette d'un doigt (dans le repère de l'aile) : ses jointures, ses phalanges, sa griffe en crochet, ses petits pics. */
function construireDoigt(D) {
  const Z = [0, 0, 1];
  let d = [Math.cos(D.phi * deg), Math.sin(D.phi * deg), 0], p = D.K;
  const plier = (v, plan, arriere) => {
    v = tourner(v, Z, plan * deg);
    const axe = norme(croix(Z, v));
    return norme(tourner(v, axe, arriere * deg));
  };
  const jointures = [p], dirs = [d];
  for (let j = 0; j < 3; j++) {
    if (j > 0) d = plier(d, D.plan, D.arriere);
    p = plus(p, fois(d, D.L * PHALANGES[j]));
    jointures.push(p); dirs.push(d);
  }
  const os = chaine(jointures, RAYONS.map((k) => k * D.r));
  // les jointures noueuses
  const noeuds = jointures.slice(0, 3).map((q, j) => ({ c: plus(q, fois(Z, 0.35 * D.r)), r: D.r * RAYONS[j] * 1.2 }));
  // la griffe : elle prolonge le doigt et se recourbe en crochet, vers le bas et vers l'arrière
  const gp = [p], gr = [D.r * RAYONS[3] * 0.92];
  let g = d;
  for (let i = 1; i <= 8; i++) {
    g = plier(g, D.crochet / 8, 32 / 8);
    p = plus(p, fois(g, D.griffe / 8));
    gp.push(p);
    gr.push(Math.max(0.05, gr[0] * Math.pow(1 - i / 8, 0.75)));
  }
  const croc = chaine(gp, gr);
  // les petits pics : un sur la jointure de la main, un plus petit sur la jointure du milieu
  const pics = [
    pic(plus(jointures[0], fois(Z, D.r * 0.75)), plus(Z, fois(dirs[0], 0.5)), 2.1, 0.8, fois(dirs[0], 1), 0.6),
    pic(plus(jointures[1], fois(Z, D.r * 0.7)), plus(Z, fois(dirs[1], 0.6)), 1.4, 0.55, fois(dirs[1], 1), 0.6),
  ];
  // une sphère qui contient tout le doigt : loin d'elle, inutile de le calculer
  const tout = [...os.points, ...croc.points], centre = fois(tout.reduce((s, q) => plus(s, q), [0, 0, 0]), 1 / tout.length);
  const rayon = Math.max(...tout.map((q) => Math.hypot(...moins(q, centre)))) + D.r * 1.3 + 2.5;
  return { ...D, jointures, dirs, os, noeuds, croc, pics, centre, rayon };
}
export const SQUELETTE = DOIGTS.map(construireDoigt);

// le dos de la main : la paume, le poignet, les tendons vers chaque doigt, des bosses
const TENDONS = SQUELETTE.map((D) => chaine([D.nom === 'pouce' ? [4, -1.5, 2.1] : [5, 1.5, 2.4], plus(moins(D.K, fois(D.dirs[0], 1.6)), [0, 0, 1.9])], [0.9, 1.05]));
const BOSSES = [[8, 6.2, 2.6, 1.7], [14, 8, 2.2, 1.5], [12, -2.2, 2.4, 1.6], [6, -3, 2.0, 1.4], [16.5, 2.5, 2.2, 1.3], [3, 2.8, 2.1, 1.3], [10.5, 9.5, 1.6, 1.3]];
const CORNES = [
  pic([12.5, 9.2, 1.9], [0.3, 1, 0.45], 12.5, 2.7, [1, 0, -0.35], 1.1),
  pic([7.8, 8.6, 2.2], [-0.08, 1, 0.5], 8, 2.0, [0.7, 0, -0.4], 1.0),
  pic([16.5, 12.4, 1.4], [0.25, 1, 0.4], 4.6, 1.25, [0.8, 0, 0], 0.7),
  // la rangée de petits pics sur le haut du poignet, et sous le poignet
  pic([-1.2, 3.2, 1.5], [-0.35, 1, 0.45], 2.7, 0.95, [-1, 0, 0], 0.5),
  pic([1.6, 3.9, 1.8], [-0.3, 1, 0.5], 3.3, 1.0, [-1, 0, 0], 0.5),
  pic([4.4, 4.8, 1.9], [-0.2, 1, 0.5], 2.9, 0.95, [-1, 0, 0], 0.5),
  pic([0.2, -3.7, 1.2], [-0.3, -1, 0.5], 2.3, 0.85, [-1, 0, 0], 0.4),
  pic([3.2, -4.6, 1.4], [-0.2, -1, 0.5], 2.0, 0.8, [-1, 0, 0], 0.4),
  // sous la paume, côté auriculaire
  pic([14, -7.2, 0.4], [0.15, -1, 0.25], 2.1, 0.8, [1, 0, 0], 0.4),
  pic([11, -8.2, 0.4], [0.05, -1, 0.25], 1.8, 0.7, [1, 0, 0], 0.4),
  // les petits pics des jointures, sur la paume
  ...SQUELETTE.map((D) => D.pics[0]),
];
/** Le plan où la main est coupée à plat, côté paume (vu de dos). */
const DOS_PLAT = -1.9;
/**
 * Le creux de la paume : un œuf posé derrière la main, enfoncé de CREUX_PAUME, au bord irrégulier (du bruit), pour que
 * les marches des cubes ne dessinent pas des cercles réguliers.
 */
const CREUX_PAUME = 1.1;
const creuxPaume = (p) => ellipsoide(p, [12, 1.5, DOS_PLAT - 5], [8, 7.2, 5 + CREUX_PAUME]) + (fbm(p, 0.3, 60) - 0.5) * 1.4;
/** Le dos de la main seul (sans les cornes), plat côté paume, avec ses bosses de bruit côté avant. */
function corpsPaume(p) {
  let d = ellipsoide(p, [11, 2.5, 0.2], [10.2, 9.6, 3.4]);
  d = smin(d, ellipsoide(p, [12, 3.6, 1.5], [8, 7.5, 3.1]), 2);
  d = smin(d, cone(p, [-3.5, -0.8, 0], [6, 1.5, 0.2], 3.9, 4.4)[0], 2.5);
  for (const D of SQUELETTE) {
    d = smin(d, Math.hypot(...moins(p, D.noeuds[0].c)) - D.noeuds[0].r, 1.3);
  }
  for (const T of TENDONS) d = smin(d, T.d(p)[0], 1.2);
  for (const [x, y, z, r] of BOSSES) d = smin(d, Math.hypot(p[0] - x, p[1] - y, p[2] - z) - r, 1.6);
  // la paume, de dos : coupée à plat
  d = smax(d, DOS_PLAT - p[2], 0.6);
  // les bosses : fortes sur le dos de la main ; côté paume, le même relief mais en creux
  if (Math.abs(d) > 2.5) return d;
  return d - relief(p, p[2] > 0 ? 1.15 : -0.9);
}
/** Les bosses : un bruit large et doux, dont la force change d'un endroit à l'autre (très bossu ici, lisse là). */
function relief(p, fort, g = 0) {
  const zone = lisse((bruit(p[0] * 0.09, p[1] * 0.09, p[2] * 0.09, 40 + g) - 0.3) / 0.45);
  return (fbm(p, 0.22, g) - 0.5) * 1.9 * fort * (0.25 + 0.95 * zone);
}
function formeDoigt(D, p) {
  let d = D.os.d(p)[0];
  for (const n of D.noeuds.slice(1)) d = smin(d, Math.hypot(...moins(p, n.c)) - n.r, 1);
  if (Math.abs(d) < 2) d -= relief(p, 0.5, 3);
  d = smin(d, D.croc.d(p)[0], 0.4);
  d = Math.min(d, D.pics[1].d(p)[0]);
  return d;
}

/** Le point de l'axe du doigt à la fraction f de sa longueur (f < 0 : avant la jointure, dans la paume). */
function surAxe(D, f) {
  if (f <= 0) return plus(D.K, fois(D.dirs[1], f * D.L));
  let reste = f * D.L;
  for (let j = 0; j < 3; j++) {
    const l = D.L * PHALANGES[j];
    if (reste <= l || j === 2) return plus(D.jointures[j], fois(D.dirs[j + 1], reste));
    reste -= l;
  }
}
/** La distance d'un point à un triangle (Inigo Quilez). */
function triangle(a, b, c) {
  const ba = moins(b, a), cb = moins(c, b), ac = moins(a, c), nor = croix(ba, ac);
  const n1 = croix(ba, nor), n2 = croix(cb, nor), n3 = croix(ac, nor);
  const lba = scal(ba, ba), lcb = scal(cb, cb), lac = scal(ac, ac), lnor = scal(nor, nor);
  // sans créer de tableaux : appelée des millions de fois
  const arete = (o, e, le, px, py, pz) => {
    const qx = px - o[0], qy = py - o[1], qz = pz - o[2];
    let t = (e[0] * qx + e[1] * qy + e[2] * qz) / le;
    t = t < 0 ? 0 : t > 1 ? 1 : t;
    const dx = e[0] * t - qx, dy = e[1] * t - qy, dz = e[2] * t - qz;
    return dx * dx + dy * dy + dz * dz;
  };
  // une sphère autour du triangle : si le point en est plus loin que le meilleur déjà trouvé, on passe
  const centre = [(a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3, (a[2] + b[2] + c[2]) / 3];
  const rayon = Math.max(...[a, b, c].map((q) => Math.hypot(q[0] - centre[0], q[1] - centre[1], q[2] - centre[2])));
  return (p, mieux = 1e9) => {
    const [px, py, pz] = p;
    const ox = px - centre[0], oy = py - centre[1], oz = pz - centre[2];
    const loin = Math.sqrt(ox * ox + oy * oy + oz * oz) - rayon;
    if (loin >= mieux) return loin;
    const sa = Math.sign(n1[0] * (px - a[0]) + n1[1] * (py - a[1]) + n1[2] * (pz - a[2]));
    const sb = Math.sign(n2[0] * (px - b[0]) + n2[1] * (py - b[1]) + n2[2] * (pz - b[2]));
    const sc = Math.sign(n3[0] * (px - c[0]) + n3[1] * (py - c[1]) + n3[2] * (pz - c[2]));
    if (sa + sb + sc < 2) return Math.sqrt(Math.min(arete(a, ba, lba, px, py, pz), arete(b, cb, lcb, px, py, pz), arete(c, ac, lac, px, py, pz)));
    const h = nor[0] * (px - a[0]) + nor[1] * (py - a[1]) + nor[2] * (pz - a[2]);
    return Math.sqrt((h * h) / lnor);
  };
}
/**
 * Les palmes entre deux doigts voisins : une peau tendue d'un axe à l'autre, de la paume jusqu'à une part de chaque doigt
 * (jusqu'où), avec un bord libre creusé en arc au milieu (creux) ; elle se bombe un peu vers l'arrière, comme les doigts.
 */
const PALMES = [
  ['pouce', 'index', 0.9, 0.74, 0.18], ['index', 'majeur', 0.95, 0.95, 0.1],
  ['majeur', 'annulaire', 0.95, 0.95, 0.1], ['annulaire', 'auriculaire', 0.95, 0.97, 0.1],
].map(([a, b, jA, jB, creux]) => {
  const A = SQUELETTE.find((D) => D.nom === a), B = SQUELETTE.find((D) => D.nom === b);
  const NT = 6, NU = 4, P = [];
  for (let i = 0; i <= NT; i++) for (let k = 0; k <= NU; k++) {
    const t = i / NT, u = k / NU, bord = 1 - creux * (1 - (2 * u - 1) ** 2);
    const q = mix(surAxe(A, -0.08 + t * bord * jA), surAxe(B, -0.08 + t * bord * jB), u);
    P.push(plus(q, [0, 0, -0.3 * Math.sin(Math.PI * u) * t]));
  }
  const tris = [], at = (i, k) => P[i * (NU + 1) + k];
  for (let i = 0; i < NT; i++) for (let k = 0; k < NU; k++) {
    tris.push(triangle(at(i, k), at(i + 1, k), at(i + 1, k + 1)), triangle(at(i, k), at(i + 1, k + 1), at(i, k + 1)));
  }
  const centre = fois(P.reduce((s2, q) => plus(s2, q), [0, 0, 0]), 1 / P.length);
  const rayon = Math.max(...P.map((q) => Math.hypot(...moins(q, centre))));
  return {
    d(p, mieux = 1e9) {
      const loin = Math.hypot(p[0] - centre[0], p[1] - centre[1], p[2] - centre[2]) - rayon;
      if (loin > 2 || loin >= mieux) return loin;
      let best = mieux;
      for (const f of tris) best = Math.min(best, f(p, best));
      return best;
    },
  };
});
// la demi-épaisseur des palmes : assez pour qu'il n'y ait jamais de jour entre les cubes
let EP_PALME = 0.42;
/** La distance aux palmes ; seuil : au-delà, la valeur exacte ne compte plus (on rend seulement « au moins seuil »). */
function palme(p, seuil = 1e9) {
  let d = seuil + EP_PALME;
  for (const W of PALMES) d = Math.min(d, W.d(p, d));
  return d - EP_PALME;
}

/** Toute la main, d'un seul bloc : la paume, les doigts, les palmes, les cornes. */
// une sphère qui contient la paume, ses bosses et ses tendons
const PAUME_C = [9, 2.5, 0], PAUME_R = 17.5;
/** Sans doigts : la paume seule, d'où sortent les serpents (marque 3). Fissurée : des fissures luisent (la transformation). */
let AVEC_DOIGTS = true, AVEC_FISSURES = false;
function formeMain(p) {
  const lp = Math.hypot(p[0] - PAUME_C[0], p[1] - PAUME_C[1], p[2] - PAUME_C[2]) - PAUME_R;
  let d = lp > 3 ? lp : corpsPaume(p);
  for (const D of AVEC_DOIGTS ? SQUELETTE : []) {
    const l = Math.hypot(p[0] - D.centre[0], p[1] - D.centre[1], p[2] - D.centre[2]) - D.rayon;
    d = l > d + 1 ? d : smin(d, l > 3 ? l : formeDoigt(D, p), 1);
  }
  // vu de dos, tout le creux de la main est plat (la paume, la base des doigts) ; plus loin, les doigts se recourbent
  const r = Math.hypot((p[0] - 11) / 13.5, (p[1] - 2) / 12);
  if (r < 1) d = smax(d, DOS_PLAT - (1 - lisse((1 - r) / 0.15)) * 12 - p[2], 0.5);
  // et au milieu de la paume, un creux doux, d'au plus CREUX_PAUME, aux bords arrondis
  if (r < 0.9) d = smax(d, -creuxPaume(p), 0.6);
  // déjà bien dans la main : les palmes ne changent rien ; sinon, seules comptent celles à moins de 0,3 près
  if (AVEC_DOIGTS && d > -0.3) d = smin(d, palme(p, d + 0.3), 0.3);
  for (const C of CORNES) d = smin(d, C.d(p)[0], 0.5);
  return d;
}

// ------------------------------------------------------------------ les couleurs
// La peau du skin de la forme 2 (GRIS dans docs/marque3/generateur/rendu.mjs), presque unie comme dans l'anime : le relief
// et la lumière font les volumes, quelques traits sombres marquent les plis, les palmes sont plus foncées et déchirées.
const CLAIR = [204, 178, 152], BASE = [184, 156, 130], ROSE = [182, 150, 132], CHAUD = [188, 156, 124];
const OMBRE = [122, 98, 80], ENCRE = [56, 40, 34], PALME = mix(BASE, OMBRE, 0.4);
/** Un trait : un cube de la couleur c, poussée vers le sombre de k. */
const trait = (c, k, dist) => ({ c: mix(c, ENCRE, k), veine: false, dist });

/** Couleur d'un cube de la surface : [r, g, b], veine (inutilisé : plus de veines), dist (sa distance au poignet). */
function couleurPaume(p) {
  const dist = Math.hypot(...p);
  for (const C of CORNES) {
    const [d, t] = C.d(p);
    if (d < 0.3) return trait(BASE, lisse((t - 0.5) / 0.5) * 0.85, dist);
  }
  // la paume, de dos : la même peau, un peu plus sombre au fond des creux
  if (p[2] < DOS_PLAT + CREUX_PAUME + 0.8) {
    const fond = lisse((p[2] - DOS_PLAT) / (CREUX_PAUME + 0.6));
    return { c: mix(peau(p), OMBRE, 0.2 * fond), veine: false, dist };
  }
  return { c: peau(p), veine: false, dist };
}
/** Couleur d'un cube de la surface de la main ; fissurée, certains cubes luisent (le réseau de fissures). */
function couleurMain(p) {
  const info = couleurPartie(p);
  if (AVEC_FISSURES && fissure(p)) info.veine = true;
  return info;
}
/** Les fissures : deux réseaux de lignes fines et irrégulières, sur toute la main. */
function fissure(p) {
  return Math.abs(bruit(p[0] * 0.26, p[1] * 0.26, p[2] * 0.26, 90) - 0.5) < 0.028 || Math.abs(bruit(p[0] * 0.5 + 3, p[1] * 0.5, p[2] * 0.5, 91) - 0.5) < 0.018;
}
/** La couleur de la partie la plus proche (corne, doigt, palme ou paume). */
function couleurPartie(p) {
  for (const C of CORNES) if (C.d(p)[0] < 0.3) return couleurPaume(p);
  if (!AVEC_DOIGTS) return couleurPaume(p);
  let doigt = null, dd = 1e9;
  for (const D of SQUELETTE) {
    const d = Math.min(D.os.d(p)[0] - 0.4, D.croc.d(p)[0], D.pics[1].d(p)[0]);
    if (d < dd) { dd = d; doigt = D; }
  }
  const dp = corpsPaume(p), dw = palme(p);
  if (dd <= dp && dd <= dw + 0.3) return couleurDoigt(doigt, p, dw);
  if (dw < dp) return couleurPalme(p, dd);
  return couleurPaume(p);
}
/**
 * La palme : unie, plus foncée que la peau, avec un trait sombre le long des doigts, et des déchirures claires bordées
 * de sombre, qui partent du bord libre vers la paume (comme les palmes de l'anime).
 */
function couleurPalme(p, dd) {
  const dist = Math.hypot(...p);
  if (dd < 0.45) return trait(PALME, 0.6, dist);
  const angle = Math.atan2(p[1] - 2, p[0] - 11), rayon = Math.hypot(p[0] - 11, p[1] - 2);
  const loinDuCentre = lisse((rayon - 12) / 8);
  const dechire = Math.abs(bruit(angle * 10, rayon * 0.3, 0, 70) - 0.5);
  if (loinDuCentre > 0.3 && bruit(angle * 4, 3, 0, 71) > 0.45) {
    if (dechire < 0.02) return { c: CLAIR, veine: false, dist };
    if (dechire < 0.034) return trait(PALME, 0.55, dist);
  }
  return { c: mix(PALME, BASE, 0.08 * (fbm(p, 0.12, 7) - 0.5)), veine: false, dist };
}
function couleurDoigt(D, p, dw) {
  const dist = Math.hypot(...p);
  const [dg, tg] = D.croc.d(p);
  const [dos, to] = D.os.d(p);
  // la griffe : la couleur de l'aile, le bout qui fonce
  if (dg < 0.3 && dg <= dos + 0.2) return trait(BASE, lisse((tg - 0.35) / 0.65) * 0.9, dist);
  const [dp, tp] = D.pics[1].d(p);
  if (dp < 0.25) return trait(BASE, lisse((tp - 0.4) / 0.6) * 0.8, dist);
  // là où la palme rejoint le doigt : un trait sombre
  if (dw < 0.35) return trait(BASE, 0.55, dist);
  // un pli en travers de chaque jointure, sur le dessus du doigt
  const { q, dir } = D.os.axe(p);
  const v = moins(p, q), cote = scal(v, norme(croix(dir, [0, 0, 1]))), dessus = v[2];
  for (let j = 1; j < 3; j++) {
    const x = scal(moins(p, D.jointures[j]), D.dirs[j]);
    if (dessus > 0 && Math.abs(cote) < D.r * 0.55 && Math.abs(x) < 0.17) return trait(BASE, 0.45, dist);
  }
  return { c: peau(p), veine: false, dist };
}
/** La peau : presque unie, à peine nuancée (quelques pour cent), pour garder le dessin propre. */
function peau(p) {
  const m = fbm(p, 0.1, 9);
  return mix(mix(BASE, ROSE, lisse((m - 0.35) / 0.3) * 0.6), CHAUD, lisse((fbm(p, 0.08, 13) - 0.5) / 0.2) * 0.4);
}

// ------------------------------------------------------------------ des formes aux cubes
/**
 * Remplit une grille : chaque case dont le centre tombe dans la forme devient un cube. versAile passe des coordonnées
 * de la grille à celles de l'aile (la grille d'un doigt suit le doigt).
 */
function voxeliser({ min, max, pas, forme, couleur, versAile, periodique = false }) {
  const debut = performance.now();
  const nx = Math.ceil((max[0] - min[0]) / pas), ny = Math.ceil((max[1] - min[1]) / pas), nz = Math.ceil((max[2] - min[2]) / pas);
  const plein = new Uint8Array(nx * ny * nz);
  const centre = (i, j, k) => [min[0] + (i + 0.5) * pas, min[1] + (j + 0.5) * pas, min[2] + (k + 0.5) * pas];
  // par blocs de 4 x 4 x 4 : loin de la surface, tout le bloc est vide (ou plein) d'un coup
  const B = 4, marge = (B / 2) * pas * Math.sqrt(3) + 1.9;
  for (let bk = 0; bk < nz; bk += B) for (let bj = 0; bj < ny; bj += B) for (let bi = 0; bi < nx; bi += B) {
    const d = forme(versAile([min[0] + (bi + B / 2) * pas, min[1] + (bj + B / 2) * pas, min[2] + (bk + B / 2) * pas]));
    if (d > marge) continue;
    for (let k = bk; k < Math.min(nz, bk + B); k++) for (let j = bj; j < Math.min(ny, bj + B); j++) for (let i = bi; i < Math.min(nx, bi + B); i++) {
      if (d < -marge || forme(versAile(centre(i, j, k))) < 0) plein[(k * ny + j) * nx + i] = 1;
    }
  }
  const at = periodique
    ? (i, j, k) => j >= 0 && k >= 0 && j < ny && k < nz && plein[(k * ny + j) * nx + (((i % nx) + nx) % nx)] === 1
    : (i, j, k) => i >= 0 && j >= 0 && k >= 0 && i < nx && j < ny && k < nz && plein[(k * ny + j) * nx + i] === 1;
  console.log('cubes', Math.round(performance.now() - debut), 'ms');
  // la couleur des cubes de surface, assombrie dans les creux (beaucoup de voisins pleins)
  const infos = new Map();
  const r = Math.max(1, Math.round(1 / pas));
  for (let k = 0; k < nz; k++) for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) {
    if (!at(i, j, k)) continue;
    if (at(i + 1, j, k) && at(i - 1, j, k) && at(i, j + 1, k) && at(i, j - 1, k) && at(i, j, k + 1) && at(i, j, k - 1)) continue;
    let n = 0, t = 0;
    for (let c = -r * 2; c <= r * 2; c += r) for (let b = -r * 2; b <= r * 2; b += r) for (let a = -r * 2; a <= r * 2; a += r) { t++; if (at(i + a, j + b, k + c)) n++; }
    const creux = Math.max(0, n / t - 0.5);
    const c0 = centre(i, j, k), info = couleur(versAile(c0));
    info.c = info.c.map((v) => v * (1 - creux * 0.3));
    // la vraie pente de la surface à cet endroit (la forme vue de près), pour éclairer les marches des cubes en douceur
    const h = pas * 0.75, f = (dx, dy, dz) => forme(versAile([c0[0] + dx, c0[1] + dy, c0[2] + dz]));
    info.n = norme([f(h, 0, 0) - f(-h, 0, 0), f(0, h, 0) - f(0, -h, 0), f(0, 0, h) - f(0, 0, -h)]);
    infos.set((k * ny + j) * nx + i, info);
  }
  console.log('couleurs', Math.round(performance.now() - debut), 'ms,', infos.size, 'cubes de surface');
  return { nx, ny, nz, pas, min, at, infos, centre };
}

const LIN = new Float32Array(256).map((_, i) => { const c = i / 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
export const lin = (v) => LIN[Math.max(0, Math.min(255, Math.round(v)))];
const FACES = [
  { n: [1, 0, 0], u: [0, 1, 0], v: [0, 0, 1] }, { n: [-1, 0, 0], u: [0, 0, 1], v: [0, 1, 0] },
  { n: [0, 1, 0], u: [0, 0, 1], v: [1, 0, 0] }, { n: [0, -1, 0], u: [1, 0, 0], v: [0, 0, 1] },
  { n: [0, 0, 1], u: [1, 0, 0], v: [0, 1, 0] }, { n: [0, 0, -1], u: [0, 1, 0], v: [1, 0, 0] },
];
const AO = [0.74, 0.84, 0.92, 1];
/**
 * La normale d'une face : un mélange de celle du cube et de la pente de la surface, pour que les marches des cubes ne
 * dessinent pas des anneaux d'ombre et de lumière (la face reste un cube, mais s'éclaire comme la surface).
 */
function normaleFace(n, pente) {
  if (scal(n, pente) <= 0) return n;
  return norme(plus(fois(n, 0.4), fois(pente, 0.6)));
}
/** Les faces visibles des cubes, avec l'ombre douce des coins (comme l'éclairage lisse de Minecraft) ; les cubes qui luisent à part. */
function maillage(G) {
  const pos = [], nor = [], col = [], gpos = [], gdist = [], surface = [];
  // les mêmes faces en quadrilatères, pour le mod : 4 coins, leur couleur (sRGB, ombre des coins comprise), une normale
  const qpos = [], qcol = [], qnor = [], gqpos = [], gqdist = [];
  const { nx, ny, at, infos, centre, pas } = G;
  for (const [idx, info] of infos) {
    const i = idx % nx, j = Math.floor(idx / nx) % ny, k = Math.floor(idx / (nx * ny));
    const c0 = centre(i, j, k);
    surface.push(c0);
    for (const F of FACES) {
      if (at(i + F.n[0], j + F.n[1], k + F.n[2])) continue;
      const fc = plus(c0, fois(F.n, pas / 2));
      const coins = [[-1, -1], [1, -1], [1, 1], [-1, 1]];
      const pts = coins.map(([su, sv]) => plus(fc, plus(fois(F.u, su * pas / 2), fois(F.v, sv * pas / 2))));
      if (info.veine) {
        for (const q of [0, 1, 2, 0, 2, 3]) { gpos.push(...pts[q]); gdist.push(info.dist); }
        for (const q of [0, 1, 2, 3]) gqpos.push(...pts[q]);
        gqdist.push(info.dist);
      }
      const ao = coins.map(([su, sv]) => {
        const b = [i + F.n[0], j + F.n[1], k + F.n[2]];
        const s1 = at(b[0] + su * F.u[0], b[1] + su * F.u[1], b[2] + su * F.u[2]);
        const s2 = at(b[0] + sv * F.v[0], b[1] + sv * F.v[1], b[2] + sv * F.v[2]);
        const cc = at(b[0] + su * F.u[0] + sv * F.v[0], b[1] + su * F.u[1] + sv * F.v[1], b[2] + su * F.u[2] + sv * F.v[2]);
        return AO[s1 && s2 ? 0 : 3 - (s1 + s2 + cc)];
      });
      const ordre = ao[0] + ao[2] >= ao[1] + ao[3] ? [0, 1, 2, 0, 2, 3] : [1, 2, 3, 1, 3, 0];
      const nf = normaleFace(F.n, info.n);
      for (const q of ordre) {
        pos.push(...pts[q]); nor.push(...nf);
        col.push(lin(info.c[0] * ao[q]), lin(info.c[1] * ao[q]), lin(info.c[2] * ao[q]));
      }
      // Minecraft coupe un quadrilatère par (0, 1, 2) et (2, 3, 0) : on commence au bon coin pour la même diagonale
      for (const q of ordre[0] === 0 ? [0, 1, 2, 3] : [1, 2, 3, 0]) {
        qpos.push(...pts[q]);
        qcol.push(...info.c.map((v) => Math.max(0, Math.min(255, Math.round(v * ao[q])))));
      }
      qnor.push(...nf);
    }
  }
  return {
    pos: new Float32Array(pos), nor: new Float32Array(nor), col: new Float32Array(col),
    gpos: new Float32Array(gpos), gdist: new Float32Array(gdist), surface: new Float32Array(surface.flat()),
    quads: { pos: qpos, col: qcol, nor: qnor },
    quadsLueur: { pos: gqpos, dist: gqdist },
  };
}

// ------------------------------------------------------------------ les serpents (marque 3)
// Un serpent, dans son repère : x le long du corps (vers la tête), y le dos, z le côté. Le corps est un anneau de cubes
// d'une longueur LONG_ANNEAU qui se répète (on en enfile autant qu'il faut le long d'une courbe : le serpent s'allonge
// sans que ses cubes s'étirent) ; la tête est à part, au bout. Écailles hexagonales sur le dos et les flancs, ventre clair
// en bandes, comme sur la planche de l'utilisateur ; la même couleur que la main.
const DOS_S = 2.5, FLANC_S = 2.7;
/** Un pavage hexagonal (x autour du corps, y le long) : la distance (0 → 1) au bord de l'écaille, et l'écaille. */
function hexa(x, y, t) {
  const q = ((Math.sqrt(3) / 3) * x - y / 3) / t, r = ((2 / 3) * y) / t;
  let rx = Math.round(q), rz = Math.round(r), ry = Math.round(-q - r);
  const dx = Math.abs(rx - q), dy = Math.abs(ry + q + r), dz = Math.abs(rz - r);
  if (dx > dy && dx > dz) rx = -ry - rz; else if (dz > dy) rz = -rx - ry;
  const cx = t * Math.sqrt(3) * (rx + rz / 2), cy = t * 1.5 * rz;
  const px = Math.abs(x - cx), py = Math.abs(y - cy);
  return { bord: 1 - Math.max(px, px / 2 + (py * Math.sqrt(3)) / 2) / ((t * Math.sqrt(3)) / 2), cellule: [rx, rz] };
}
/** La peau du serpent au point (x, y, z) de son repère ; t : la taille des écailles (le motif se répète tous les 3 t en x). */
function ecaille(p, t) {
  const angle = Math.atan2(p[2], p[1]), tour = angle * (DOS_S + FLANC_S) / 2;
  // le ventre : plus clair, en bandes
  if (Math.cos(angle) < -0.55) {
    const k = ((p[0] / (1.5 * t)) % 1 + 1) % 1;
    return k < 0.18 ? mix(CLAIR, OMBRE, 0.45) : mix(CLAIR, BASE, 0.25);
  }
  const { bord, cellule } = hexa(tour, p[0], t);
  if (bord < 0.16) return mix(BASE, ENCRE, 0.62);
  return fois(mix(BASE, CLAIR, 0.25 * lisse((bord - 0.3) / 0.6)), 0.95 + 0.08 * hache(cellule[0], cellule[1], 5));
}
/** L'anneau du corps : un cylindre un peu aplati (plus large que haut), de longueur long. */
/** La longueur d'un anneau du corps : un nombre entier de cubes, à peu près 5 pixels de la main. */
export const longueurAnneau = (pas) => Math.max(3, Math.round(5 / pas)) * pas;
function anneau(pas) {
  const long = longueurAnneau(pas), t = long / 3;
  const G = voxeliser({
    min: [0, -3.4, -3.6], max: [long, 3.4, 3.6], pas, periodique: true, versAile: (q) => q,
    forme: (p) => (Math.hypot(p[1] / DOS_S, p[2] / FLANC_S) - 1) * Math.min(DOS_S, FLANC_S),
    couleur: (p) => ({ c: ecaille(p, t), veine: false, dist: 0 }),
  });
  return { ...maillage(G), long, t };
}
/**
 * La tête, au bout du corps (x = 0 : la fin du dernier anneau) : un cou un peu plus fin qui rentre dans le corps, un
 * crâne large et plat, un museau arrondi, une arête au-dessus de chaque œil, une mâchoire, la bouche, les narines.
 */
function formeTete(p) {
  const k = 0.97 - 0.1 * lisse((p[0] + 1) / 3);
  let d = Math.max((Math.hypot(p[1] / (DOS_S * k), p[2] / (FLANC_S * k)) - 1) * Math.min(DOS_S, FLANC_S) * k, p[0] - 3);
  d = Math.max(d, -3.2 - p[0]);
  let t = smin(ellipsoide(p, [3.6, 0.6, 0], [5.1, 3.0, 3.7]), ellipsoide(p, [7.6, 0.2, 0], [3.1, 2.1, 2.8]), 1.3);
  t = smin(t, ellipsoide(p, [4.6, -1.1, 0], [4.3, 1.8, 3.3]), 0.9);
  for (const c of [-1, 1]) t = smin(t, ellipsoide(p, [4.2, 2.6, c * 1.9], [2.4, 0.8, 1.0]), 0.5);
  return smin(d, t, 1.4);
}
const OEIL = (c) => [4.9, 1.6, c * 2.95];
function couleurTete(p, t) {
  for (const c of [-1, 1]) {
    const o = OEIL(c), d = Math.hypot(p[0] - o[0], p[1] - o[1], p[2] - o[2]);
    if (d < 0.95) return { c: p[1] > o[1] + 0.25 && p[0] > o[0] + 0.15 ? CLAIR : ENCRE, veine: false, dist: 0 };
    if (Math.hypot(p[0] - 10.3, p[1] - 0.9, p[2] - c * 0.8) < 0.4) return { c: ENCRE, veine: false, dist: 0 };
  }
  // la bouche : un trait sur les côtés et le bout du museau
  if (p[0] > 2.6 && Math.abs(p[1] + 0.45) < 0.24 && (Math.abs(p[2]) > 1.6 || p[0] > 9.6)) return { c: mix(BASE, ENCRE, 0.75), veine: false, dist: 0 };
  // le dessus de la tête : de grandes plaques ; ailleurs les écailles du corps
  if (p[0] > 1 && p[1] > 0.9) {
    const { bord } = hexa(p[2] * 1.2, p[0], t * 1.35);
    return { c: bord < 0.14 ? mix(BASE, ENCRE, 0.6) : mix(BASE, CLAIR, 0.2 * lisse(bord)), veine: false, dist: 0 };
  }
  return { c: ecaille(p, t), veine: false, dist: 0 };
}
/**
 * Les 7 serpents de la marque 3 (partagés par l'aperçu et le mod, qui refait les mêmes calculs dans ClientMarque3) :
 * l'angle (degrés, depuis l'extérieur vers le haut) et la longueur relative de chacun ; ils sortent du dos en faisceau.
 * Une pose : les quatre points de contrôle d'une courbe de Bézier, dans le repère de la main.
 */
export const SERPENTS = [[62, 0.86], [42, 0.95], [22, 1], [2, 1.02], [-18, 0.98], [-38, 0.92], [-58, 0.84]];
const DOIGT_DE = { 1: 'index', 2: 'majeur', 3: 'annulaire', 4: 'auriculaire', 6: 'pouce' };
const AXE_Y = [0, 1, 0], AXE_Z = [0, 0, 1];
export function axes(i) {
  const f = (SERPENTS[i][0] * Math.PI) / 180;
  const d = [Math.cos(f), Math.sin(f), 0], e = [-Math.sin(f), Math.cos(f), 0];
  // la racine : serrées les unes contre les autres, là où l'aile s'accroche au dos
  return { d, e, R: [-2.5, 1.6 + (3 - i) * 0.55, i % 2 ? -0.7 : 0.7], L: 30 * SERPENTS[i][1] + 16 };
}
export function repos(i, t) {
  const { d, e, R, L } = axes(i), w = 1.6 * t + i * 0.9;
  const P3 = [d, e, AXE_Z, e, AXE_Y, AXE_Z].reduce((acc, v, k) => plus(acc, fois(v, [L, 1.5, 7, 2.6 * Math.sin(w - 2.2), 2 * Math.sin(1.1 * t + 1.3 * i), Math.sin(1.4 * t + 0.7 * i)][k])), R);
  const C1 = [d, e, AXE_Z, e].reduce((acc, v, k) => plus(acc, fois(v, [L * 0.33, 4, -1.5, 2.2 * Math.sin(w)][k])), R);
  const regard = norme(plus(plus(fois(d, 0.55), fois(AXE_Z, 0.75)), fois(AXE_Y, 0.25)));
  const C2 = plus(plus(plus(P3, fois(regard, -L * 0.35)), fois(e, 3 * Math.sin(w - 1.1))), fois(AXE_Z, 1.5 * Math.sin(1.2 * t + i)));
  return [R, C1, C2, P3];
}
/** La pose « doigt » : depuis le dos, le serpent passe par la jointure et suit le doigt (les deux de plus : un moignon). */
export function poseDoigt(i) {
  const D = SQUELETTE.find((x) => x.nom === DOIGT_DE[i]), { d, R } = axes(i);
  if (!D) return [R, plus(R, d), plus(R, fois(d, 2)), plus(R, fois(d, 3))];
  return [R, D.K, D.jointures[2], D.jointures[3]];
}

/** Les deux morceaux d'un serpent, à la finesse pas : l'anneau du corps (à répéter) et la tête. */
export function construireSerpent(pas) {
  const corps = anneau(pas);
  const G = voxeliser({ min: [-3.4, -3.4, -4.4], max: [11.4, 4.2, 4.4], pas, versAile: (q) => q, forme: formeTete, couleur: (p) => couleurTete(p, corps.t) });
  return { anneau: corps, tete: maillage(G), longAnneau: corps.long };
}

/** Écrit des quadrilatères (4 coins, 4 couleurs, une normale par face) dans vue à partir de o ; rend la suite. */
function ecrireQuads(vue, o, { pos, col, nor }) {
  const n = nor.length / 3;
  vue.setInt32(o, n); o += 4;
  for (let f = 0; f < n; f++) {
    for (let k = 0; k < 12; k++) { vue.setFloat32(o, pos[f * 12 + k]); o += 4; }
    for (let k = 0; k < 12; k++) vue.setUint8(o++, col[f * 12 + k]);
    for (let k = 0; k < 3; k++) { vue.setFloat32(o, nor[f * 3 + k]); o += 4; }
  }
  return o;
}
const tailleQuads = (q) => 4 + (q.nor.length / 3) * (12 * 4 + 12 + 3 * 4);

/**
 * Le fichier des serpents du mod (assets/cabane/marque/serpent.bin), lu par ClientMarque3 : la longueur d'un anneau
 * (float), les faces de l'anneau puis celles de la tête (comme main.bin), puis les poses « doigt » des 7 serpents
 * (7 × 4 points × 3 float). Gros-boutiste.
 */
export function versModSerpent(serpent, pas) {
  const vue = new DataView(new ArrayBuffer(4 + tailleQuads(serpent.anneau.quads) + tailleQuads(serpent.tete.quads) + SERPENTS.length * 12 * 4));
  let o = 0;
  vue.setFloat32(o, longueurAnneau(pas)); o += 4;
  o = ecrireQuads(vue, o, serpent.anneau.quads);
  o = ecrireQuads(vue, o, serpent.tete.quads);
  for (let i = 0; i < SERPENTS.length; i++) for (const q of poseDoigt(i)) for (const c of q) { vue.setFloat32(o, c); o += 4; }
  return new Uint8Array(vue.buffer);
}
/**
 * Le fichier des fissures du mod (assets/cabane/marque/fissures.bin) : le nombre de faces (int), puis pour chacune ses
 * 4 coins (12 float) et sa distance au poignet (float), pour qu'elles s'étendent depuis le poignet.
 */
export function versModFissures(main) {
  const { pos, dist } = main.quadsLueur, n = dist.length;
  const vue = new DataView(new ArrayBuffer(4 + n * 13 * 4));
  let o = 0;
  vue.setInt32(o, n); o += 4;
  for (let f = 0; f < n; f++) {
    for (let k = 0; k < 12; k++) { vue.setFloat32(o, pos[f * 12 + k]); o += 4; }
    vue.setFloat32(o, dist[f]); o += 4;
  }
  return new Uint8Array(vue.buffer);
}

/**
 * Le fichier du mod (assets/cabane/marque/main.bin), lu par ClientMarque2 : le nombre de faces (int), puis pour chaque
 * face 4 coins (x, y, z en float), 4 couleurs (r, g, b en octets), sa normale (3 float). Tout en gros-boutiste (Java).
 * Les coordonnées sont celles de la main, avant ECHELLE.
 */
export function versMod(main) {
  const { pos, col, nor } = main.quads, n = nor.length / 3;
  const vue = new DataView(new ArrayBuffer(4 + n * (12 * 4 + 12 + 3 * 4)));
  let o = 0;
  vue.setInt32(o, n); o += 4;
  for (let f = 0; f < n; f++) {
    for (let k = 0; k < 12; k++) { vue.setFloat32(o, pos[f * 12 + k]); o += 4; }
    for (let k = 0; k < 12; k++) vue.setUint8(o++, col[f * 12 + k]);
    for (let k = 0; k < 3; k++) { vue.setFloat32(o, nor[f * 3 + k]); o += 4; }
  }
  return new Uint8Array(vue.buffer);
}

/** La taille des ailes sur le joueur, et la finesse des cubes « fins » (en pixels du dessin de la main). */
export const ECHELLE = 0.68, PAS_FIN = 0.42;
/** La finesse des cubes dans le jeu : un demi-pixel Minecraft (assez fin, et léger à dessiner). */
export const PAS_JEU = 0.5 / ECHELLE;
/** Calcule les cubes de la main entière, dans le repère de l'aile ; pas : la taille d'un cube. */
export function construireMain(pas, { doigts = true, fissures = false } = {}) {
  AVEC_DOIGTS = doigts;
  AVEC_FISSURES = fissures;
  EP_PALME = Math.max(0.42, pas * 0.95);
  const G = voxeliser({ min: [-6, -34, -24], max: [48, 33, 10], pas, forme: formeMain, couleur: couleurMain, versAile: (q) => q });
  return maillage(G);
}
// le fichier .bin : six longueurs (Uint32), puis les six tableaux de nombres (Float32) à la suite
const CHAMPS = ['pos', 'nor', 'col', 'gpos', 'gdist', 'surface'];
export function versBinaire(main) {
  const tete = new Uint32Array(CHAMPS.map((c) => main[c].length));
  const total = CHAMPS.reduce((t, c) => t + main[c].length, 0);
  const corps = new Float32Array(total);
  let o = 0;
  for (const c of CHAMPS) { corps.set(main[c], o); o += main[c].length; }
  const out = new Uint8Array(tete.byteLength + corps.byteLength);
  out.set(new Uint8Array(tete.buffer), 0);
  out.set(new Uint8Array(corps.buffer), tete.byteLength);
  return out;
}
export function depuisBinaire(buf) {
  const tete = new Uint32Array(buf, 0, CHAMPS.length), main = {};
  let o = CHAMPS.length * 4;
  CHAMPS.forEach((c, i) => { main[c] = new Float32Array(buf, o, tete[i]); o += tete[i] * 4; });
  return main;
}

