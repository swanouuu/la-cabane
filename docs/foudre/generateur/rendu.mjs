// Maquettes du pouvoir Foudre.
// Éveil 1 : la foudre lui tombe dessus, puis plein d'éclairs courent autour de lui ; un peu plus vite, plus solide et plus
// fort, comme tous les niveaux 1. Clic gauche : toute arme électrise (un peu plus de dégâts, et ça ralentit). Clic droit :
// un éclair en chaîne. Accroupi : le pas de l'éclair (il file où il regarde, et la foudre tombe là où il arrive).
// Éveil 2 : une aura bleu clair (comme le manteau de Hachibi, sans oreilles ni queues) et encore plus d'éclairs ; le clic
// gauche en plus fort ; clic droit : le Kirin (il lève la main et crie, un gros nuage noir se forme au-dessus d'une zone
// marquée de petits nuages blancs, la tête du Kirin en sort et rugit ; main en bas, le Kirin tombe d'un coup) ; accroupi :
// deux fois plus loin, plus souvent. Le Kirin est dessiné comme dans l'animé : des traits de foudre cyan, les yeux rouges.
// node rendu.mjs [1|2] → planche-foudre.png, planche-niveau2.png (sans rien : les deux)
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, PARTIES, clair, mix, hache, norme, croix, scene, ecrireTexte, POLICE } from "../../fruits/generateur/moteur.mjs";
import { ajoute, sous, fois, rotX, TOUR, brille, pave, cube, pointe, perso, sol, legende, regarde, epee, croissant, STEVE } from "../../fruits/generateur/rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const ZURI = lire(path.join(ICI, "skins", "zuri.png"));
POLICE["%"] = "100001010100001";

const BLANC = [250, 252, 255];
const CIEL = [160, 220, 255];
const AURA = [130, 210, 255];
const NUIT = [64, 72, 100];
const NOIR_NUAGE = [30, 30, 40];
const GRIS_NUAGE = [62, 64, 80];
const SUIE = [44, 42, 48];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

// ------------------------------------------------------------------ la lueur
// Les cœurs des éclairs se dessinent d'une couleur repère (que rien d'autre n'a) ; à la fin de la vignette, on les
// retrouve, on les repeint (blanc, ou rouge pour les yeux du Kirin), et on ajoute autour une lueur floue, par-dessus tout.
const LUEURS = {
  cyan: { repere: [1, 2, 3], coeur: BLANC, c: [120, 200, 255], k: 1 },
  doux: { repere: [1, 2, 4], coeur: BLANC, c: [120, 200, 255], k: 0.5 },
  rouge: { repere: [3, 1, 1], coeur: [255, 70, 60], c: [255, 40, 30], k: 1.1 },
  kirin: { repere: [1, 3, 2], coeur: [200, 244, 255], c: [50, 160, 255], k: 0.5 },
};
const repere = (nom) => brille(LUEURS[nom].repere);

/** Un flou en boîte (trois passes, presque un flou gaussien) d'un masque w × h. */
function flou(m, w, h, r) {
  for (let passe = 0; passe < 3; passe++) {
    const t = new Float32Array(w * h);
    for (let y = 0; y < h; y++) { let acc = 0; for (let x = -r; x < w; x++) { if (x + r < w) acc += m[y * w + x + r]; if (x - r - 1 >= 0) acc -= m[y * w + x - r - 1]; if (x >= 0) t[y * w + x] = acc / (2 * r + 1); } }
    const t2 = new Float32Array(w * h);
    for (let x = 0; x < w; x++) { let acc = 0; for (let y = -r; y < h; y++) { if (y + r < h) acc += t[(y + r) * w + x]; if (y - r - 1 >= 0) acc -= t[(y - r - 1) * w + x]; if (y >= 0) t2[y * w + x] = acc / (2 * r + 1); } }
    m = t2;
  }
  return m;
}

/** La fin de la vignette : les cœurs retrouvés, puis les voiles (transparents), puis la lueur. */
function finir(sc) {
  const { w, h, img } = sc, masques = {};
  for (const [nom, l] of Object.entries(LUEURS)) {
    const m = new Float32Array(w * h);
    let vu = false;
    for (let i = 0; i < w * h; i++) {
      if (img[i * 4] === l.repere[0] && img[i * 4 + 1] === l.repere[1] && img[i * 4 + 2] === l.repere[2]) {
        m[i] = 1;
        vu = true;
        img.set([...l.coeur, 255], i * 4);
      }
    }
    if (vu) masques[nom] = m;
  }
  for (const [q, c, a] of sc.voiles) sc.voile(q, c, a);
  sc.voiles = [];
  for (const [nom, m] of Object.entries(masques)) {
    const l = LUEURS[nom], a = flou(m, w, h, 1), b = flou(m, w, h, 4), c = flou(m, w, h, 12);
    for (let i = 0; i < w * h; i++) {
      const g = (1.4 * a[i] + 1.0 * b[i] + 0.7 * c[i]) * l.k;
      if (g < 0.004) continue;
      for (let k = 0; k < 3; k++) img[i * 4 + k] = Math.min(255, img[i * 4 + k] + l.c[k] * g);
    }
  }
}

// ------------------------------------------------------------------ les éclairs
/** Le sens « vers l'œil » de la caméra de cette vignette, dans le monde (la vue est orthographique). */
function oeil(sc) {
  sc.oeil ??= norme([sc.dirVue([1, 0, 0])[2], sc.dirVue([0, 1, 0])[2], sc.dirVue([0, 0, 1])[2]]);
  return sc.oeil;
}

/** Un éclair de A à B : une ligne brisée (le milieu de chaque morceau poussé de côté, puis encore, et encore). */
function zigzag(A, B, graine, tordu = 0.25, niveaux = 4) {
  const ax = norme(sous(B, A));
  let e1 = croix(ax, [0, 1, 0]);
  if (Math.hypot(...e1) < 0.2) e1 = croix(ax, [1, 0, 0]);
  e1 = norme(e1);
  const e2 = croix(ax, e1);
  let pts = [A, B];
  for (let n = 0; n < niveaux; n++) {
    const suite = [pts[0]];
    for (let k = 0; k < pts.length - 1; k++) {
      const p = pts[k], q = pts[k + 1], L = Math.hypot(...sous(q, p));
      const a = (hache(graine, n * 64 + k, 1) - 0.5) * 2 * tordu * L, b = (hache(graine, n * 64 + k, 2) - 0.5) * 2 * tordu * L;
      suite.push(ajoute(fois(ajoute(p, q), 0.5), ajoute(fois(e1, a), fois(e2, b))), q);
    }
    pts = suite;
  }
  return pts;
}

/** Le cœur d'un éclair le long de pts, face à l'œil ; il brillera (voir finir). */
function trait(sc, pts, large = 0.35, lueur = "cyan") {
  const o = oeil(sc);
  for (let k = 0; k < pts.length - 1; k++) {
    const a = pts[k], b = pts[k + 1];
    const cote = fois(norme(croix(sous(b, a), o)), large / 2);
    sc.quad([sous(a, cote), ajoute(a, cote), ajoute(b, cote), sous(b, cote)], LUEURS[lueur].repere, 0.3);
  }
}

/** Un éclair de A à B avec quelques branches qui s'en détachent. */
function eclair(sc, A, B, graine, o = {}) {
  const large = o.large ?? 0.4, pts = zigzag(A, B, graine, o.tordu ?? 0.25, o.niveaux ?? 4);
  trait(sc, pts, large, o.lueur ?? "cyan");
  const L = Math.hypot(...sous(B, A)), ax = norme(sous(B, A));
  for (let k = 0; k < (o.branches ?? 0); k++) {
    const i = 2 + Math.floor(hache(k, 1, graine) * (pts.length - 4));
    const d = norme(ajoute(ax, [hache(k, 2, graine) - 0.5, (hache(k, 3, graine) - 0.5) * 0.6, hache(k, 4, graine) - 0.5].map((v) => v * 1.6)));
    const P = pts[i];
    trait(sc, zigzag(P, ajoute(P, fois(d, L * (0.12 + 0.2 * hache(k, 5, graine)))), graine + 31 * k + 7, 0.3, 3), large * 0.55, "doux");
  }
  return pts;
}

/** Les six faces d'un pavé de a à b, dont la normale (au repos) part vers l'extérieur. */
function facesPave([x0, y0, z0], [x1, y1, z1]) {
  return [
    [[0, 0, 1], [[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]]], [[0, 0, -1], [[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]]],
    [[-1, 0, 0], [[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]]], [[1, 0, 0], [[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]]],
    [[0, 1, 0], [[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]]], [[0, -1, 0], [[x0, y0, z1], [x1, y0, z1], [x1, y0, z0], [x0, y0, z0]]],
  ];
}

// ------------------------------------------------------------------ les éclairs sur un corps
const BOITES = Object.fromEntries(PARTIES(false).map(([nom, b]) => [nom, b]));
const MEMBRES = ["tete", "corps", "brasD", "brasG", "jambeD", "jambeG"];
const VOISINS = [["corps", "brasD"], ["corps", "brasG"], ["corps", "tete"], ["corps", "jambeD"], ["corps", "jambeG"], ["jambeD", "jambeG"],
  ["brasD", "brasD"], ["brasG", "brasG"], ["tete", "tete"], ["corps", "corps"], ["brasD", "corps"], ["brasG", "corps"]];

/** Un point juste à côté de la surface d'une partie du corps (à `ecart` d'elle), à une hauteur au hasard. */
function pointSur(T, nom, k, graine, ecart) {
  const [x0, y0, z0, x1, y1, z1] = BOITES[nom];
  const hx = (x1 - x0) / 2, hz = (z1 - z0) / 2, a = hache(k, 1, graine) * TOUR;
  const s = 1 / Math.max(Math.abs(Math.cos(a)) / hx, Math.abs(Math.sin(a)) / hz);
  return T[nom].p([(x0 + x1) / 2 + Math.cos(a) * (s + ecart), y0 + (y1 - y0) * (0.08 + 0.84 * hache(k, 2, graine)), (z0 + z1) / 2 + Math.sin(a) * (s + ecart)]);
}

/**
 * Des éclairs qui courent sur le corps : n d'une partie à sa voisine ; o.libres qui s'échappent dans l'air ; o.sol qui
 * descendent des jambes jusqu'au sol (y = o.y).
 */
function crepite(sc, T, n, graine, o = {}) {
  // ils restent collés au corps : décollés d'à peine un pixel ou deux, jamais bien loin (au sol, juste autour des pieds)
  const ecart = Math.min(o.ecart ?? 0.5, 1.6), large = o.large ?? 0.32, loin = Math.min(o.loin ?? 3, 3.5), rayon = Math.min(o.rayon ?? 5, 5.5);
  for (let k = 0; k < n; k++) {
    const [a, b] = VOISINS[Math.floor(hache(k, 3, graine) * VOISINS.length)];
    trait(sc, zigzag(pointSur(T, a, k * 2, graine, ecart), pointSur(T, b, k * 2 + 1, graine, ecart), graine * 100 + k, 0.32, 3), large);
  }
  for (let k = 0; k < (o.libres ?? 0); k++) {
    const A = pointSur(T, MEMBRES[k % 6], 500 + k, graine, ecart);
    const d = norme([hache(k, 4, graine) - 0.5, hache(k, 5, graine) - 0.25, hache(k, 6, graine) - 0.5]);
    eclair(sc, A, ajoute(A, fois(d, loin * (0.6 + 0.6 * hache(k, 7, graine)))), graine * 100 + 500 + k, { large: large * 0.85, niveaux: 3, tordu: 0.3 });
  }
  for (let k = 0; k < (o.sol ?? 0); k++) {
    const A = pointSur(T, k % 2 ? "jambeD" : "jambeG", 900 + k, graine, ecart);
    const a = hache(k, 8, graine) * TOUR, r = rayon * (0.6 + 0.5 * hache(k, 9, graine));
    eclair(sc, A, [A[0] + Math.cos(a) * r, o.y ?? 0.2, A[2] + Math.sin(a) * r], graine * 100 + 900 + k, { large: large * 0.9, niveaux: 3, tordu: 0.3 });
  }
}

/** Les yeux qui brillent : juste leurs deux pixels de chaque côté (la rangée y de 27 à 28), sans lueur autour. */
function yeux(sc, T) {
  for (const x of [-3, 1]) pave(sc, [x, 27, 4.01], [x + 2, 28, 4.04], T.tete, () => brille([225, 248, 255]), 1, 0.2);
}

/** L'aura de l'éveil 2 : chaque partie du corps dans une coque bleu clair transparente, et une plus large, plus légère. */
function manteau(sc, T) {
  for (const [nom, b] of PARTIES(false)) {
    for (const [m, alpha, c] of [[1.1, 0.2, AURA], [2.4, 0.08, CIEL]]) {
      const bas = m * (nom.startsWith("jambe") ? 0.2 : 1);
      for (const [N, coins] of facesPave([b[0] - m, b[1] - bas, b[2] - m], [b[3] + m, b[4] + m, b[5] + m])) {
        if (N[1] < 0 || sc.lumiere(T[nom].n(N), false) < 0) continue;
        sc.voiles.push([coins.map(T[nom].p), c, alpha]);
      }
    }
  }
}

// ------------------------------------------------------------------ le décor
/** Un nuage d'orage : des blocs noirs et gris, et des éclairs entre eux. */
function nuage(sc, c, rx, ry, rz, graine, o = {}) {
  const n = o.n ?? 70, [c0, c1] = o.couleurs ?? [NOIR_NUAGE, GRIS_NUAGE], pts = [];
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, graine) * TOUR, r = Math.sqrt(hache(q, 2, graine));
    const p = ajoute(c, [Math.cos(a) * r * rx, (hache(q, 3, graine) - 0.5) * ry * (1.2 - r), Math.sin(a) * r * rz]);
    const s = (o.taille ?? 1) * (9 + 12 * hache(q, 4, graine)) * (1.1 - 0.5 * r) * (rx / 60);
    cube(sc, p, s, [hache(q, 6, graine), hache(q, 7, graine) * 3], mix(c0, c1, hache(q, 8, graine)));
    pts.push(sous(p, [0, s * 0.45, 0]));
  }
  for (let k = 0; k < (o.eclairs ?? 0); k++) {
    const A = pts[Math.floor(hache(k, 9, graine) * n)], B = pts[Math.floor(hache(k, 10, graine) * n)];
    if (Math.hypot(...sous(B, A)) > 10) eclair(sc, A, B, graine * 7 + k, { large: 0.9, niveaux: 4, tordu: 0.3, branches: 2 });
  }
  return pts;
}

/** Le sol brûlé là où la foudre tombe : une tache de suie, et des fissures qui brillent en étoile. */
function brulure(cx, cz, R, graine, n = 9) {
  return (x, z) => {
    const dx = x - cx, dz = z - cz, r = Math.hypot(dx, dz);
    if (r < R * 0.12) return repere("cyan");
    let p = 1e9;
    for (let k = 0; k < n; k++) {
      const g = (k / n) * TOUR + hache(k, 1, graine), t = dx * Math.cos(g) + dz * Math.sin(g);
      if (t < 0 || t > R * (0.6 + 0.45 * hache(k, 2, graine))) continue;
      p = Math.min(p, Math.abs(-dx * Math.sin(g) + dz * Math.cos(g) + 1.6 * Math.sin(t * 0.45 + k * 2)));
    }
    if (p < 0.7) return r < R * 0.4 ? repere("doux") : brille(CIEL);
    if (r < R * 0.55 + 3 * hache(Math.floor(x / 3), Math.floor(z / 3), graine)) return mix(SUIE, [80, 72, 64], 0.5 * hache(Math.floor(x), Math.floor(z), graine + 1));
    return null;
  };
}

/** Un anneau qui brille sur le sol (le point visé). */
function anneau(cx, cz, R, e = 1.3) {
  return (x, z) => (Math.abs(Math.hypot(x - cx, z - cz) - R) < e ? brille(CIEL) : null);
}

/** Plusieurs dessus de sol l'un après l'autre (le premier qui répond gagne). */
const dessus = (...f) => (x, z) => { for (const g of f) { const c = g?.(x, z); if (c) return c; } return null; };

/** Un coup de tonnerre qui tombe du ciel en p : un gros éclair, des branches, et des éclairs qui filent au sol. */
function foudreTombe(sc, p, haut, graine, large = 2.2) {
  eclair(sc, [p[0] + 14, haut, p[2] - 10], p, graine, { large, tordu: 0.18, branches: 6 });
  eclair(sc, [p[0] - 10, haut, p[2] + 6], ajoute(p, [2, 0, -1]), graine + 3, { large: large * 0.45, tordu: 0.22, branches: 2 });
  for (let k = 0; k < 10; k++) {
    const a = (k / 10) * TOUR + hache(k, 1, graine), r = 10 + 10 * hache(k, 2, graine);
    eclair(sc, ajoute(p, [0, 0.4, 0]), ajoute(p, [Math.cos(a) * r, 0.4 + 3 * hache(k, 3, graine), Math.sin(a) * r]), graine + 50 + k, { large: 0.5, niveaux: 3, tordu: 0.3 });
  }
}

/** Un dôme de lumière (la moitié d'une boule transparente, plus vive sur les bords), posé au sol en c. */
function dome(sc, c, R, graine) {
  const o = oeil(sc), N = 14, M = 28;
  const P = (t, p) => ajoute(c, [R * Math.sin(t) * Math.cos(p), R * Math.cos(t), R * Math.sin(t) * Math.sin(p)]);
  for (let i = 0; i < N; i++) for (let j = 0; j < M; j++) {
    const t0 = (i / N) * (Math.PI / 2), t1 = ((i + 1) / N) * (Math.PI / 2), p0 = (j / M) * TOUR, p1 = ((j + 1) / M) * TOUR;
    const n = norme(sous(P((t0 + t1) / 2, (p0 + p1) / 2), c)), v = dot(n, o);
    if (v <= 0) continue;
    sc.voiles.push([[P(t0, p0), P(t0, p1), P(t1, p1), P(t1, p0)], mix(BLANC, CIEL, Math.min(1, 0.4 + v)), 0.1 + 0.5 * (1 - v) ** 2]);
  }
  for (let k = 0; k < 8; k++) {
    const a = (k / 8) * TOUR + hache(k, 1, graine), b = a + 0.6 + 0.6 * hache(k, 2, graine);
    eclair(sc, P(1.35, a), P(0.25 + 0.4 * hache(k, 3, graine), b), graine + k, { large: 0.9, niveaux: 4, tordu: 0.2, lueur: "doux" });
  }
}

// ------------------------------------------------------------------ le Kirin
/**
 * Le Kirin, comme dans l'animé : un dragon dessiné en traits de foudre cyan, à peine rempli, les yeux rouges, la gueule
 * ouverte, une crinière d'éclairs. tete : le centre de son crâne ; f : où regarde son museau ; h0 : à peu près le haut
 * de sa tête ; k : sa taille ; corps(s) : le chemin du corps, de la nuque (s = 0) au nuage (s = 1), ou null.
 */
function kirin(sc, tete, f, h0, k, corps, graine = 3, o = {}) {
  f = norme(f);
  const y = norme(sous(h0, fois(f, dot(h0, f)))), x = croix(y, f);
  const T = { p: (v) => ajoute(tete, ajoute(ajoute(fois(x, v[0] * k), fois(y, v[1] * k)), fois(f, v[2] * k))), n: (v) => norme(ajoute(ajoute(fois(x, v[0]), fois(y, v[1])), fois(f, v[2]))) };
  // la mâchoire, ouverte : tournée autour de sa charnière, au fond de la gueule
  const charniere = [0, -2, 5], ouvre = o.ouvre ?? 0.6;
  const J = { p: (v) => T.p(ajoute(charniere, rotX(sous(v, charniere), ouvre))), n: (v) => T.n(rotX(v, ouvre)) };
  const PIECES = [
    [[-6, -5, -8], [6, 5, 5], T], // le crâne
    [[-4.2, -2, 5], [4.2, 3.4, 16], T], // le museau
    [[-3, -1.2, 16], [3, 2.6, 20], T], // le bout du nez, plus étroit
    [[-3.6, -4.6, 5], [3.6, -2, 17], J], // la mâchoire
  ];
  const fin = o.fin ?? 1;
  PIECES.forEach(([a, b, P], i) => {
    for (const [N, coins] of facesPave(a, b)) {
      const v = dot(P.n(N), oeil(sc));
      if (v <= 0) continue;
      sc.voiles.push([coins.map(P.p), mix(CIEL, BLANC, 0.3), 0.14 + 0.16 * (1 - v)]);
    }
    // les arêtes : des traits de foudre
    const [x0, y0, z0] = a, [x1, y1, z1] = b;
    const C = [[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0], [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]];
    [[0, 1], [1, 2], [2, 3], [3, 0], [4, 5], [5, 6], [6, 7], [7, 4], [0, 4], [1, 5], [2, 6], [3, 7]].forEach(([u, w], e) => {
      trait(sc, zigzag(P.p(C[u]), P.p(C[w]), graine * 50 + i * 12 + e, 0.07, 3), 0.42 * k * fin, "kirin");
    });
  });
  // le fond de la gueule, des rides de foudre sur le museau
  for (let q = 0; q < 4; q++) trait(sc, zigzag(T.p([-4.2, 1 + q * 0.6, 7 + q * 2.4]), T.p([4.2, 1.6 + q * 0.6, 8 + q * 2.4]), graine * 9 + q, 0.12, 3), 0.25 * k * fin, "doux");
  // les crocs
  for (let q = 0; q < 5; q++) {
    for (const s of [-1, 1]) {
      pointe(sc, T.p([s * 3.4, -2, 8 + q * 2]), T.n([0, -1, 0.15]), 2.4 * k, 0.9 * k, () => repere("cyan"), false, true);
      pointe(sc, J.p([s * 3, -2, 8 + q * 2]), J.n([0, 1, 0.15]), 2 * k, 0.8 * k, () => repere("cyan"), false, true);
    }
  }
  for (const s of [-1, 1]) {
    // les yeux rouges, sur le devant du crâne, au-dessus du museau ; dessus, un sourcil froncé (un trait, pas un bloc)
    pave(sc, s > 0 ? [2.2, 3.6, 4.9] : [-5.6, 3.6, 4.9], s > 0 ? [5.6, 4.6, 5.6] : [-2.2, 4.6, 5.6], T, () => repere("rouge"), 0.5, 0.05);
    trait(sc, zigzag(T.p([s * 1.4, 4.9, 5.4]), T.p([s * 6.6, 6.4, 4.4]), graine + 80 + s, 0.05, 2), 0.6 * k * fin, "kirin");
    // les barbillons, et des pointes de foudre aux joues
    const b = T.p([s * 4.2, -0.5, 15]);
    eclair(sc, b, ajoute(b, ajoute(fois(T.n([s, -0.5, -0.8]), 26 * k), [0, -5 * k, 0])), graine + 40 + s, { large: 0.4 * k * fin, niveaux: 4, tordu: 0.2, lueur: "kirin" });
    for (let q = 0; q < 3; q++) {
      const c = T.p([s * 6, -3 + q * 2.5, 1 - q * 2]);
      eclair(sc, c, ajoute(c, fois(T.n([s, -0.3 + q * 0.3, -0.5]), (6 + 3 * q) * k)), graine + 90 + q * 2 + s, { large: 0.35 * k * fin, niveaux: 3, tordu: 0.25, lueur: "kirin" });
    }
  }
  // la crinière : des éclairs qui partent de l'arrière du crâne en éventail, vers l'arrière et les côtés (pas de cornes)
  for (let q = 0; q < (o.criniere ?? 18); q++) {
    const a = (q / (o.criniere ?? 18)) * Math.PI * 1.4 - 0.7 * Math.PI;
    const base = T.p([Math.sin(a) * 5.5, Math.cos(a) * 4, -6 - 2 * hache(q, 1, graine)]);
    const d = T.n([Math.sin(a) * 1.3, Math.cos(a) * 0.9, -0.9 - 0.6 * hache(q, 2, graine)]);
    eclair(sc, base, ajoute(base, fois(d, (12 + 12 * hache(q, 3, graine)) * k)), graine * 300 + q, { large: 0.45 * k * fin, niveaux: 4, tordu: 0.28, branches: 2, lueur: "kirin" });
  }
  if (o.rugit) {
    // le rugissement : des arcs qui partent de la gueule
    const g = T.p([0, -3, 16]);
    for (let q = 0; q < 10; q++) {
      const a = (q / 10) * TOUR;
      const d = T.n([Math.cos(a) * 0.9, Math.sin(a) * 0.9, 1]);
      eclair(sc, ajoute(g, fois(d, 6 * k)), ajoute(g, fois(d, (14 + 6 * hache(q, 1, graine)) * k)), graine * 500 + q, { large: 0.3 * k * fin, niveaux: 3, tordu: 0.2, lueur: "doux" });
    }
  }
  if (!corps) return T;
  // le corps : un ruban à peine bleu, ses deux bords en traits de foudre, et des éclairs qui s'enroulent autour
  const N = 48, ob = oeil(sc), R = (s) => 7 * k * (1 - 0.6 * s);
  const bord = [[], []];
  for (let i = 0; i <= N; i++) {
    const s = i / N, a = corps(s), b = corps(Math.min(1, s + 0.01));
    const c = norme(croix(sous(b, a), ob));
    bord[0].push(ajoute(a, fois(c, R(s) * (1 + 0.12 * (hache(i, 1, graine) - 0.5)))));
    bord[1].push(sous(a, fois(c, R(s) * (1 + 0.12 * (hache(i, 2, graine) - 0.5)))));
    if (i < N) {
      const s1 = (i + 1) / N, a1 = corps(s1), c1 = norme(croix(sous(corps(Math.min(1, s1 + 0.01)), a1), ob));
      for (const [w, col, al] of [[1, CIEL, 0.22], [0.45, BLANC, 0.18]]) {
        sc.voiles.push([[sous(a, fois(c, R(s) * w)), ajoute(a, fois(c, R(s) * w)), ajoute(a1, fois(c1, R(s1) * w)), sous(a1, fois(c1, R(s1) * w))], col, al]);
      }
    }
  }
  trait(sc, bord[0], 0.5 * k, "kirin");
  trait(sc, bord[1], 0.5 * k, "kirin");
  // dedans, des éclairs en long, cassés, qui se croisent un peu
  for (let q = 0; q < 4; q++) {
    const pts = [];
    for (let i = 0; i <= 24; i++) {
      const s = i / 24, a = corps(s), u = norme(croix(sous(corps(Math.min(1, s + 0.02)), a), ob));
      pts.push(ajoute(a, fois(u, R(s) * (0.75 * Math.sin(q * 2.1 + s * (5 + q)) + 0.35 * (hache(q, i, graine) - 0.5)))));
    }
    trait(sc, pts, 0.3 * k, "doux");
  }
  for (let q = 0; q < 14; q++) {
    const s = 0.04 + 0.7 * (q / 14), a = corps(s), t = norme(sous(corps(s + 0.02), a));
    const cote = norme(croix(t, ob)), sg = q % 2 ? 1 : -1;
    const A = ajoute(a, fois(cote, sg * R(s)));
    eclair(sc, A, ajoute(A, ajoute(fois(cote, sg * 8 * k), fois(t, 7 * k))), graine + 100 + q, { large: 0.35 * k, niveaux: 3, tordu: 0.3, lueur: "kirin" });
  }
  return T;
}

// ------------------------------------------------------------------ les poses
const FOUDRE = ZURI;
/** La garde : jambes écartées, un peu penché, les mains ouvertes. */
const GARDE = { brasD: [-0.5, -0.35], brasG: [-0.3, 0.4], jambeD: [0.2, -0.12], jambeG: [-0.2, 0.12], tete: [0.08] };
const POSE_SOL = { decale: [0, -5, 0], buste: 0.9, jambeD: -0.85, jambeG: 0.85, tete: [-0.75] };
const elec = { c: CIEL, k: 0.28 };

function cible(sc, decale, o = {}) {
  return perso(sc, STEVE, { decale, lacet: o.lacet ?? Math.PI, penche: o.penche ?? -0.15, brasD: o.brasD ?? [-0.5, -0.45], brasG: o.brasG ?? [-0.5, 0.45], jambeD: o.jambeD ?? 0, jambeG: o.jambeG ?? 0, tete: o.tete, teinte: o.teinte === null ? undefined : o.teinte ?? elec });
}

// ------------------------------------------------------------------ éveil 1
function eveil(sc) {
  sol(sc, -40, -40, 40, 40, brulure(0, 0, 30, 11));
  const T = perso(sc, FOUDRE, { brasD: [0, -2.3], brasG: [0, 2.3], tete: [0.45], jambeD: [0, -0.15], jambeG: [0, 0.15], teinte: { c: BLANC, k: 0.2 } });
  yeux(sc, T);
  eclair(sc, [10, 170, -14], T.tete.p([0, 33, 0]), 21, { large: 2.4, tordu: 0.16, branches: 7 });
  crepite(sc, T, 14, 12, { libres: 6, sol: 6 });
  return "TOUCHE EVEIL : LA FOUDRE LUI TOMBE DESSUS";
}

function enPied(sc) {
  const T = perso(sc, FOUDRE, GARDE);
  yeux(sc, T);
  crepite(sc, T, 18, 13, { libres: 5, loin: 6 });
}

function dePres(sc) {
  const T = perso(sc, FOUDRE, { ...GARDE, brasD: [-1.2, -0.2] });
  yeux(sc, T);
  crepite(sc, T, 16, 14, { libres: 4, loin: 5, large: 0.22 });
}

function vitesse(sc) {
  sol(sc, -30, -90, 30, 40);
  const pose = { brasD: [0.8], brasG: [-0.9], jambeD: [-0.8], jambeG: [0.75], penche: 0.25, tete: [-0.2] };
  perso(sc, FOUDRE, { ...pose, decale: [0, 0, -44], teinte: { c: CIEL, k: 0.85 } });
  perso(sc, FOUDRE, { ...pose, decale: [0, 0, -22], teinte: { c: CIEL, k: 0.6 } });
  const T = perso(sc, FOUDRE, pose);
  yeux(sc, T);
  crepite(sc, T, 10, 15);
  for (let k = 0; k < 7; k++) {
    const A = pointSur(T, MEMBRES[k % 6], 300 + k, 15, 0.6);
    eclair(sc, A, ajoute(A, [(hache(k, 1, 16) - 0.5) * 4, (hache(k, 2, 16) - 0.5) * 4, -26 - 20 * hache(k, 3, 16)]), 160 + k, { large: 0.4, niveaux: 4, tordu: 0.12 });
  }
  return "+20% VITESSE, PROTEGE COMME DU FER";
}

function coup(sc, fort = false) {
  sol(sc, -24, -16, 24, 60);
  const T = perso(sc, FOUDRE, { brasD: [-1.75, -0.25], brasG: [-0.3, 0.25], jambeD: -0.3, jambeG: 0.25 });
  yeux(sc, T);
  const lame = epee(sc, T.brasD, "D", 15);
  crepite(sc, T, fort ? 12 : 6, 17, { large: 0.26, ecart: fort ? 1.4 : 0.5 });
  if (fort) manteau(sc, T);
  const C = cible(sc, [-2, 0, 34], { penche: fort ? -0.3 : -0.15, teinte: fort ? { c: BLANC, k: 0.45 } : elec, brasD: fort ? [-1.2, -0.9] : undefined, brasG: fort ? [-1.2, 0.9] : undefined });
  eclair(sc, lame[2], C.corps.p([0, 20, 2]), 171, { large: fort ? 0.9 : 0.6, branches: fort ? 3 : 1 });
  crepite(sc, C, fort ? 22 : 9, 18, fort ? { libres: 8, sol: 6, loin: 7, rayon: 10 } : { libres: 2 });
  croissant(sc, T.brasD, "D", CIEL);
  return fort ? "+4 DEGATS, GROS CHOC : +2, TRES RALENTI 3 S" : "+2 DEGATS, CHOC : +1 ET RALENTI 2 S";
}

function chaine(sc) {
  sol(sc, -50, -16, 50, 100);
  const T = perso(sc, FOUDRE, { brasD: [-1.57, -0.06], brasG: [0.2, 0.1], jambeD: -0.3, jambeG: 0.25 });
  yeux(sc, T);
  crepite(sc, T, 6, 19, { large: 0.26 });
  const cibles = [[-4, 0, 62], [30, 0, 80], [-34, 0, 88]].map((p, k) => [p, cible(sc, p, { lacet: regarde(p, [0, 0, 0]), penche: -0.25 - 0.1 * k })]);
  let avant = T.brasD.p([-6, 11, 0]);
  cibles.forEach(([, C], k) => {
    const P = C.corps.p([0, 19, 0]);
    eclair(sc, avant, P, 190 + k, { large: k ? 0.8 : 1.3, tordu: 0.2, branches: k ? 2 : 4 });
    crepite(sc, C, 7, 191 + k, { large: 0.24 });
    avant = P;
  });
  return "6 DEGATS, PUIS SAUTE SUR 2 AUTRES : 4";
}

function pas(sc, loin = 90) {
  sol(sc, -26, -20, 26, loin + 30, dessus(brulure(0, 0, 14, 23, 7), anneau(0, loin, 12)));
  const fort = loin > 100;
  const D = perso(sc, FOUDRE, { ...GARDE, teinte: { c: CIEL, k: 0.82 } });
  if (fort) manteau(sc, D);
  for (let k = 0; k < 3; k++) {
    const y = 8 + k * 7;
    eclair(sc, [0, y, 2], [0, y + 2, loin - 4], 230 + k, { large: k === 1 ? 1.4 : 0.7, tordu: 0.12, niveaux: fort ? 6 : 5, branches: fort ? 6 : 3 });
  }
  const T = perso(sc, FOUDRE, { ...POSE_SOL, decale: [0, -5, loin], brasD: [-1.4, -0.15], brasG: [0.6, 0.5] });
  yeux(sc, T);
  crepite(sc, T, 8, 24, { large: 0.3, ecart: fort ? 1.4 : 0.5 });
  if (fort) {
    manteau(sc, T);
    foudreTombe(sc, [0, 0, loin], 200, 25, 2.6);
  }
  return fort ? "50 BLOCS, RECHARGE 10 S AU LIEU DE 15" : "IL FILE OU IL REGARDE, 25 BLOCS MAX";
}

function impact(sc) {
  sol(sc, -84, -84, 84, 84, brulure(0, 0, 50, 27, 11));
  const T = perso(sc, FOUDRE, { ...POSE_SOL, brasD: [-1.4, -0.15], brasG: [0.6, 0.5] });
  yeux(sc, T);
  [0.4, 2.5, 4.4].forEach((a, k) => {
    const p = [34 * Math.cos(a), 6 + 6 * k, 34 * Math.sin(a)];
    const C = cible(sc, p, { lacet: regarde(p, [0, 0, 0]), penche: -0.8, brasD: [-2.6, -0.4], brasG: [-2.6, 0.4], jambeD: 0.3, jambeG: -0.2 });
    crepite(sc, C, 6, 270 + k, { large: 0.4 });
  });
  foudreTombe(sc, [0, 0, 0], 220, 28, 3.2);
  return "6 DEGATS SUR 3 BLOCS, RALENTIT. RIEN NE BRULE";
}

// ------------------------------------------------------------------ éveil 2
function aura(sc) {
  sol(sc, -40, -40, 40, 40, brulure(0, 0, 22, 31, 8));
  const T = perso(sc, FOUDRE, { ...GARDE, teinte: { c: AURA, k: 0.15 } });
  yeux(sc, T);
  crepite(sc, T, 34, 32, { libres: 10, loin: 3.5, sol: 8, rayon: 5, ecart: 1.4, large: 0.34 });
  manteau(sc, T);
}

/** La zone du Kirin : où il vise, un cercle de petits nuages blancs au ras du sol. */
const ZONE = [0, 0, 130], RZ = 36, CIEL_K = [0, 228, 136];

function zone(sc, graine) {
  for (let q = 0; q < 30; q++) {
    const a = (q / 30) * TOUR + 0.1 * hache(q, 1, graine), r = RZ * (0.94 + 0.12 * hache(q, 2, graine));
    const p = ajoute(ZONE, [Math.cos(a) * r, 2 + 2.5 * hache(q, 3, graine), Math.sin(a) * r]);
    for (let b = 0; b < 3; b++) {
      cube(sc, ajoute(p, [(hache(q, b, graine + 1) - 0.5) * 6, b * 1.5, (hache(q, b, graine + 2) - 0.5) * 6]), 4 + 3 * hache(q, b, graine + 3), [q, b], mix([214, 220, 232], BLANC, hache(q, b, graine + 4)));
    }
  }
}

/** Le Kirin en quatre temps : 1 il lève la main et crie, 2 la tête sort et rugit, 3 main en bas, il tombe, 4 l'impact. */
function kirinVue(sc, etape) {
  sol(sc, -70, -30, 80, 190, etape === 4 ? brulure(ZONE[0], ZONE[2], 60, 41, 12) : null);
  if (etape < 4) zone(sc, 40);
  const bras = [-3.05, -2.0, -0.6, -0.4][etape - 1];
  const T = perso(sc, FOUDRE, { brasD: [bras, -0.08], brasG: [0.15, 0.15], jambeD: [0.2, -0.12], jambeG: [-0.2, 0.12], tete: [etape <= 2 ? -0.35 : 0] });
  yeux(sc, T);
  crepite(sc, T, 18, 40 + etape, { ecart: 1.4, libres: 4 });
  manteau(sc, T);
  [[-14, 0, 124], [14, 0, 138]].forEach((p, k) => {
    if (etape === 4) {
      const d = norme(sous([p[0], 0, p[2]], [ZONE[0], 0, ZONE[2]]));
      const q = ajoute(ZONE, ajoute(fois(d, 70 + 8 * k), [0, 22 + 10 * k, 0]));
      const C = cible(sc, q, { lacet: regarde(q, ZONE), penche: -0.9, brasD: [-2.6, -0.4], brasG: [-2.6, 0.4], teinte: { c: BLANC, k: 0.45 } });
      crepite(sc, C, 8, 420 + k, { large: 0.45 });
    } else if (etape < 3) {
      cible(sc, p, { lacet: regarde(p, [0, 0, 0]) + (k ? 0.6 : -0.5), tete: [-0.7], teinte: null, brasD: [0.1, -0.1], brasG: [0.1, 0.1] });
    }
  });
  nuage(sc, CIEL_K, etape === 1 ? 60 : 84, 34, etape === 1 ? 40 : 56, 44, { n: etape === 1 ? 60 : 110, eclairs: etape === 1 ? 6 : 10, taille: 1.1 });
  const corps = (bas, tete) => (s) => {
    const h = bas + (CIEL_K[1] - bas) * s;
    return [tete[0] + 22 * Math.sin(s * 5.5 + 0.3) * s, h, tete[2] + 16 * Math.sin(s * 3.6) * s - 8 * s];
  };
  if (etape === 1) {
    // la lumière de sa main monte jusqu'au nuage
    eclair(sc, T.brasD.p([-6, 10, 0]), sous(CIEL_K, [0, 22, 30]), 450, { large: 0.6, tordu: 0.1, niveaux: 5, lueur: "doux" });
    const [x, y] = sc.vue(T.tete.p([0, 40, 0]));
    bulle(sc, "KIRIN !", Math.round(x) - 30, Math.round(y) - 80);
    barre(sc, 0.15);
    return "IL LEVE LA MAIN ET CRIE. L'ORAGE SE FORME";
  }
  if (etape === 2) {
    const tete = [ZONE[0] + 4, 182, ZONE[2] - 14];
    kirin(sc, tete, sous([0, 40, 0], tete), [0, 1, 0], 4.2, corps(tete[1] + 16, tete), 46, { rugit: true, ouvre: 0.75 });
    barre(sc, 0.7);
    return "LA BARRE MONTE, LA TETE SORT ET RUGIT";
  }
  if (etape === 3) {
    const tete = [ZONE[0], 66, ZONE[2]];
    kirin(sc, tete, [0.05, -1, -0.25], [0, 0.2, -1], 3.6, corps(86, tete), 47, { ouvre: 0.5 });
    // la vitesse : des traits qui tombent avec lui
    for (let q = 0; q < 8; q++) {
      const x = ZONE[0] + (hache(q, 1, 48) - 0.5) * 90, z = ZONE[2] + (hache(q, 2, 48) - 0.5) * 40, h = 60 + 90 * hache(q, 3, 48);
      trait(sc, [[x, h, z], [x, h + 30 + 30 * hache(q, 4, 48), z]], 0.8, "doux");
    }
    return "MAIN EN BAS : IL TOMBE D'UN COUP";
  }
  kirin(sc, [ZONE[0], 40, ZONE[2]], [0, -1, -0.2], [0, 0.2, -1], 3.6, corps(60, ZONE), 49, { criniere: 10 });
  dome(sc, ZONE, 64, 50);
  foudreTombe(sc, ZONE, 30, 51, 1.6);
  return "16 AU CENTRE, 8 JUSQU'A 7 BLOCS";
}

/** La tête du Kirin de près, qui rugit en sortant du nuage. */
function teteKirin(sc) {
  nuage(sc, [0, 74, -70], 120, 26, 50, 60, { n: 170, eclairs: 8, taille: 0.5 });
  const tete = [0, 18, 0];
  kirin(sc, tete, [-0.15, -0.35, 1], [0, 1, 0.2], 2.4, (s) => [22 * Math.sin(s * 5 + 0.4) * s, tete[1] + 8 + 60 * s, -10 - 50 * s], 61, { rugit: true, ouvre: 0.75, criniere: 16, fin: 0.6 });
  return "TRAITS DE FOUDRE CYAN, YEUX ROUGES";
}

/** La barre du Kirin, comme à l'écran du joueur (en haut de la vignette, sur un fond sombre) : plus elle est pleine, plus il fera mal. */
function barre(sc, charge) {
  const avant = sc.apres;
  sc.apres = () => {
    avant?.();
    const w = 300, h = 14, x = Math.round(sc.w / 2 - w / 2), y = 40;
    for (let j = -30; j < h + 10; j++) for (let i = -12; i < w + 12; i++) sc.img.set([20, 24, 40, 255], ((y + j) * sc.w + x + i) * 4);
    for (let j = -2; j < h + 2; j++) for (let i = -2; i < w + 2; i++) sc.img.set([6, 8, 14, 255], ((y + j) * sc.w + x + i) * 4);
    for (let j = 0; j < h; j++) for (let i = 0; i < Math.round(w * charge); i++) {
      const k = i / w;
      sc.img.set([Math.round(63 + 120 * k), Math.round(168 + 64 * k), 255, 255], ((y + j) * sc.w + x + i) * 4);
    }
    ecrireTexte(sc.img, sc.w, "KIRIN", x, y - 18, 3, [140, 216, 255, 255]);
    const d = `${Math.round(6 + 10 * charge)} DEGATS`;
    ecrireTexte(sc.img, sc.w, d, x + w - d.length * 12, y - 18, 3, [235, 245, 255, 255]);
  };
}

/** Une bulle de paroles blanche avec son texte (écrit après la lueur). */
function bulle(sc, texte, x, y) {
  const w = texte.length * 16 + 24, h = 44;
  sc.apres = () => {
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
      const bord = i < 3 || j < 3 || i >= w - 3 || j >= h - 3, X = x + i, Y = y + j;
      if (X >= 0 && X < sc.w && Y >= 0 && Y < sc.h) sc.img.set(bord ? [30, 40, 70, 255] : [255, 255, 255, 255], (Y * sc.w + X) * 4);
    }
    for (let j = 0; j < 14; j++) for (let i = 0; i < 14 - j; i++) {
      const X = x + 30 + i, Y = y + h + j;
      if (X >= 0 && X < sc.w && Y >= 0 && Y < sc.h) sc.img.set(i === 0 || i === 13 - j ? [30, 40, 70, 255] : [255, 255, 255, 255], (Y * sc.w + X) * 4);
    }
    ecrireTexte(sc.img, sc.w, texte, x + 12, y + 12, 4, [30, 80, 200, 255]);
  };
}

// ------------------------------------------------------------------ les planches
const CW = 440, CH = 520, M = 34;
const NOIR = [30, 30, 40, 255], TITRE = [40, 110, 200, 255];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

function planche(fichier, titre, lignes, vues) {
  const COLONNES = 4, rangs = Math.ceil(vues.length / COLONNES);
  const HAUT = 110 + lignes.length * 34 + 20;
  const W = CW * COLONNES, H = HAUT + (M + CH) * rangs;
  const img = Buffer.alloc(W * H * 4, 255);
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, titre, 30, 22, 5, NOIR);
  lignes.forEach((t, k) => ecrireTexte(img, W, t, 30, 100 + k * 34, 3, t.startsWith(" ") ? NOIR : TITRE));
  vues.forEach(([nom, dessin, o], k) => {
    const c = k % COLONNES, l = Math.floor(k / COLONNES);
    const sc = scene({ w: CW, h: CH, fond: NUIT, ...o });
    sc.voiles = [];
    const texte = dessin(sc);
    finir(sc);
    sc.apres?.();
    if (texte) legende(sc, texte, [40, 60, 110, 255]);
    for (let y = 0; y < CH; y++) sc.img.copy(img, ((HAUT + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(img, W, nom, c * CW + 12, HAUT + l * (M + CH) + 10, 3, TITRE);
  });
  ecrirePng(path.join(DOC, fichier), W, H, img);
  console.log("ok", fichier, W, H);
}

const ACTION = { echelle: 5.6, tangage: 18, pied: 300 };
const PLAN = { echelle: 11, pied: 490 };
const KIRIN = { lacet: 115, tangage: 8, echelle: 1.55, pied: 395, centre: [0, 40, 80] };

const NIVEAU_1 = [
  "TOUCHE EVEIL : LA FOUDRE LUI TOMBE DESSUS, PUIS PLEIN D'ECLAIRS COURENT AUTOUR DE LUI, SES YEUX BRILLENT",
  "TOUJOURS : +20% DE VITESSE, PROTEGE COMME UNE ARMURE DE FER (COMME LES AUTRES NIVEAUX 1)",
  "CLIC GAUCHE : TOUTE ARME +2 DEGATS, ET UN CHOC ELECTRIQUE : +1 DEGAT, LA CIBLE EST RALENTIE 2 S",
  "CLIC DROIT : ECLAIR EN CHAINE, 16 BLOCS, 6 DEGATS, PUIS IL SAUTE SUR 2 AUTRES (6 BLOCS AUTOUR) : 4 DEGATS",
  "    TOUS RALENTIS 2 S. RECHARGE 8 S",
  "ACCROUPI : LE PAS DE L'ECLAIR, IL FILE OU IL REGARDE (25 BLOCS MAX), SANS DEGATS DE CHUTE",
  "    LA FOUDRE TOMBE LA OU IL ARRIVE : 6 DEGATS SUR 3 BLOCS, RALENTIT. RECHARGE 15 S",
  "SONS : DE L'ELECTRICITE STATIQUE CREPITE SUR LUI. LE PAS : LE TP DE MINATO + UN COUP DE TONNERRE",
  "    LA FOUDRE NE MET PAS LE FEU ET NE CASSE RIEN",
  "EVEIL 2 MIN, PUIS 2 MIN DE REPOS, COMME LES AUTRES NIVEAUX 1",
];

const NIVEAU_2 = [
  "TOUCHE EVEIL (ENCORE) : UNE AURA BLEU CLAIR COMME LE MANTEAU DE HACHIBI, SANS OREILLES NI QUEUES",
  "    ET ENCORE PLUS D'ECLAIRS AUTOUR DE LUI, JUSQU'AU SOL",
  "TOUJOURS : +40% DE VITESSE, PROTEGE COMME DEUX FOIS DU FER (COMME LES AUTRES NIVEAUX 2)",
  "CLIC GAUCHE : TOUTE ARME +4 DEGATS, GROS CHOC : +2 DEGATS, PLEIN D'ECLAIRS SUR LUI, TRES RALENTI 3 S",
  "CLIC DROIT MAINTENU : KIRIN. IL VISE (40 BLOCS MAX), LEVE LA MAIN ET CRIE KIRIN (TA VOIX, PACK PRIVE)",
  "    AU SOL, UN CERCLE DE PETITS NUAGES BLANCS. EN HAUT, UN GROS NUAGE NOIR PLEIN D'ECLAIRS",
  "    UNE BARRE SE CHARGE EN 4 S : PLUS ELLE EST PLEINE, PLUS CA FAIT MAL (6 A 16 AU CENTRE, LA MOITIE AU BORD)",
  "    IL NE BOUGE PAS ET UN COUP RECU ANNULE TOUT (RECHARGE 8 S). A MI-BARRE, LA TETE SORT DU NUAGE ET RUGIT",
  "    IL LACHE : LA MAIN BAISSE, LE KIRIN TOMBE D'UN COUP EN RUGISSANT, GROS COUP DE TONNERRE, DOME DE LUMIERE",
  "    PROJETTE, NE CASSE RIEN, NE BRULE RIEN. RECHARGE 30 S",
  "ACCROUPI : LE PAS DE L'ECLAIR, 50 BLOCS MAX (DEUX FOIS PLUS LOIN), RECHARGE 10 S",
  "    LA FOUDRE QUI TOMBE A L'ARRIVEE EST PLUS GROSSE : 8 DEGATS SUR 4 BLOCS. SON : TP DE MINATO + TONNERRE",
  "LISTES : FOUDRE (NIVEAU 1 SEULEMENT) ET FOUDRE2 (NIVEAU 1 PUIS 2). REPOS : 1 MIN S'IL EST MONTE AU NIVEAU 2",
];

// les briques servent aussi aux planches du niveau 3 (docs/foudre3)
export { finir, zigzag, trait, eclair, crepite, pointSur, manteau, nuage, brulure, anneau, dessus, foudreTombe, dome, oeil, repere, LUEURS, BLANC, CIEL, AURA, NUIT, MEMBRES, ZURI, CW, CH, M, rect };

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
const quoi = PRINCIPAL ? process.argv[2] : "aucune";
if (!quoi || quoi === "1") {
  planche("planche-foudre.png", "FOUDRE : EVEIL 1", NIVEAU_1, [
    ["EVEIL : LA FOUDRE LE FRAPPE", eveil, { lacet: -24, tangage: 12, echelle: 6.2, pied: 450, centre: [0, 10, 0] }],
    ["EVEILLE : PLEIN D'ECLAIRS", enPied, { lacet: -28, ...PLAN }],
    ["DE PRES", dePres, { lacet: -30, echelle: 20, pied: 300, centre: [0, 22, 0], tangage: 6 }],
    ["TOUJOURS : PLUS VITE", vitesse, { lacet: -62, tangage: 14, echelle: 5, pied: 330, centre: [0, 10, -18] }],
    ["CLIC GAUCHE : CHOC", (sc) => coup(sc), { ...ACTION, echelle: 6.6, lacet: 64, centre: [0, 13, 18] }],
    ["CLIC DROIT : ECLAIR EN CHAINE", chaine, { ...ACTION, echelle: 3.4, lacet: -40, tangage: 22, centre: [0, 12, 48] }],
    ["ACCROUPI : LE PAS DE L'ECLAIR", (sc) => pas(sc), { lacet: -70, tangage: 22, echelle: 3.6, pied: 320, centre: [0, 10, 45] }],
    ["ACCROUPI : LA FOUDRE TOMBE", impact, { lacet: -30, tangage: 26, echelle: 3.2, pied: 330, centre: [0, 14, 0] }],
  ]);
}
if (!quoi || quoi === "2") {
  planche("planche-niveau2.png", "FOUDRE : EVEIL 2", NIVEAU_2, [
    ["EVEIL 2 : L'AURA BLEUE", aura, { lacet: -28, ...PLAN, aura: { y: 0.55, r: 0.5, c: AURA, force: 0.35 } }],
    ["CLIC GAUCHE : GROS CHOC", (sc) => coup(sc, true), { ...ACTION, echelle: 6.6, lacet: 64, centre: [0, 13, 18] }],
    ["ACCROUPI : DEUX FOIS PLUS LOIN", (sc) => pas(sc, 180), { lacet: -78, tangage: 22, echelle: 2.0, pied: 330, centre: [0, 10, 90] }],
    ["LE KIRIN DE PRES", teteKirin, { lacet: -35, tangage: 6, echelle: 4.6, pied: 300, centre: [0, 26, 0] }],
    ["KIRIN 1 : IL LEVE LA MAIN", (sc) => kirinVue(sc, 1), KIRIN],
    ["KIRIN 2 : LA TETE RUGIT", (sc) => kirinVue(sc, 2), KIRIN],
    ["KIRIN 3 : IL TOMBE D'UN COUP", (sc) => kirinVue(sc, 3), KIRIN],
    ["KIRIN 4 : L'IMPACT", (sc) => kirinVue(sc, 4), KIRIN],
  ]);
}
