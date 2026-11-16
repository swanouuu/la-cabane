// Maquette : Hachibi niveau 3, la suite. La touche de l'ulti (J) ne donne plus tout de suite le géant : la première fois,
// il prend le manteau de chakra rouge (son skin recouvert de chakra, les cornes, ses huit queues en chakra) jusqu'à la
// fin de l'éveil, avec des attaques de jinchuriki ; la deuxième fois, il devient le Hachibi entier (le géant de
// docs/hachibi4, inchangé), avec de nouvelles attaques au choix.
// node rendu.mjs → ../planche-hachibi5.png        node rendu.mjs <n> '{"lacet":-50}' : une seule vue, autre caméra
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, scene, ecrireTexte, clair, mix, hache, norme, croix, tourner, PARTIES } from "../../fruits/generateur/moteur.mjs";
import { boule, cubeSimple, brille, fantome, joueur as perso } from "../../minato2/generateur/effets.mjs";
import { hachibi, cube, joueur as petit, ajoute, sous, fois, DEG } from "../../hachibi4/generateur/bete.mjs";
import { G, K, sol, debris, anneau, legende, ecrire, fissures, bijuuDama, chakra, dome, FOND, TERRE } from "../../hachibi4/generateur/rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const STEVE = lire(path.join(ICI, "..", "..", "fruits", "generateur", "skins", "steve.png"));
const TOUR = Math.PI * 2;
/** Un bloc fait ce nombre de pixels de skin (le joueur : 32 pixels pour 1,8 bloc). */
const U = 32 / 1.8;
const ROUGE = [255, 60, 30];

const rotX = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
const rotY = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];

/** La même scène, vue d'un repère posé ailleurs : tourné (lacet), penché en avant, à l'échelle k, décalé. */
function repere(sc, { lacet = 0, penche = 0, decale = [0, 0, 0], k = 1 } = {}) {
  const dir = (v) => rotY(rotX(v, penche), lacet);
  const p = (v) => ajoute(fois(dir(v), k), decale);
  return { ...sc, quad: (c, ...r) => sc.quad(c.map(p), ...r), voile: (c, ...r) => sc.voile(c.map(p), ...r), lumiere: (n, d) => sc.lumiere(dir(n), d), dirVue: (n) => sc.dirVue(dir(n)), vue: (v) => sc.vue(p(v)), p, dir };
}

// ------------------------------------------------------------------ le chakra
const chakraRouge = (rim, i, j) => mix(mix([190, 20, 10], [240, 56, 20], hache(i, j, 7)), [255, 160, 76], rim);
const rayonViolet = (rim, i, j) => mix([255, 244, 252], [176, 70, 236], Math.min(1, rim * 1.3 + 0.12 * hache(i, j, 9)));

/**
 * Un tube de chakra qui brille : il part de base vers d0 et tourne autour de axe (c0 partout, c1 vers la pointe) ;
 * rayon(s) de 0 à 1. Rend sa pointe et sa direction au bout.
 */
function tube(sc, base, d0, axe, L, rayon, { c0 = 0, c1 = 0, n = 40, vague = 0, phase = 0, m = 10, coul = chakraRouge } = {}) {
  const pas = L / n;
  let p = base, d = norme(d0);
  const an = [];
  for (let i = 0; i <= n; i++) {
    const s = i / n;
    let N = croix(axe, d);
    if (Math.hypot(...N) < 0.1) N = croix([0.3, 1, 0.2], d);
    N = norme(N);
    an.push({ p, N, B: norme(croix(d, N)), r: rayon(s) });
    if (i < n) {
      d = norme(tourner(d, axe, (c0 + c1 * s ** 2.5 + vague * Math.sin(TOUR * s * 1.2 + phase)) * pas));
      p = ajoute(p, fois(d, pas));
    }
  }
  for (let i = 0; i < n; i++) {
    const a = an[i], b = an[i + 1];
    for (let j = 0; j < m; j++) {
      const t0 = (TOUR * j) / m, t1 = (TOUR * (j + 1)) / m, tm = (t0 + t1) / 2;
      const sur = (o, th) => ajoute(o.p, ajoute(fois(o.N, Math.cos(th) * o.r), fois(o.B, Math.sin(th) * o.r)));
      const v = sc.dirVue(norme(ajoute(fois(a.N, Math.cos(tm)), fois(a.B, Math.sin(tm)))));
      const rim = (1 - Math.min(1, Math.abs(v[2]))) ** 1.4;
      sc.quad([sur(a, t0), sur(b, t0), sur(b, t1), sur(a, t1)], coul(rim, i, j), 0, true);
    }
  }
  return { p, d };
}

/** Un pavé qui brille, dans le repère d'une partie du corps. */
function paveT(sc, T, [x0, y0, z0], [x1, y1, z1], c) {
  const P = (x, y, z) => T.p([x, y, z]);
  for (const f of [
    [P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)], [P(x1, y0, z0), P(x0, y0, z0), P(x0, y1, z0), P(x1, y1, z0)],
    [P(x1, y0, z1), P(x1, y0, z0), P(x1, y1, z0), P(x1, y1, z1)], [P(x0, y0, z0), P(x0, y0, z1), P(x0, y1, z1), P(x0, y1, z0)],
    [P(x0, y1, z1), P(x1, y1, z1), P(x1, y1, z0), P(x0, y1, z0)], [P(x0, y0, z0), P(x1, y0, z0), P(x1, y0, z1), P(x0, y0, z1)],
  ]) sc.quad(f, c, 0.02, true);
}

/** Le manteau : chaque partie du corps redessinée plus grosse, en voile rouge (comme ClientHachibi.enveloppe). */
function manteau(sc, T) {
  const GONFLE = { tete: [1.14, 1.14, 1.14], corps: [1.14, 1.07, 1.3], brasD: [1.28, 1.07, 1.24], brasG: [1.28, 1.07, 1.24], jambeD: [1.22, 1.06, 1.22], jambeG: [1.22, 1.06, 1.22] };
  for (const [plus, alpha, coul] of [[1, 0.3, [255, 50, 24]], [1.16, 0.14, [255, 110, 50]]]) {
    for (const [nom, [x0, y0, z0, x1, y1, z1]] of PARTIES(false)) {
      const g = GONFLE[nom].map((v) => v * plus), cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
      const P = (x, y, z) => T[nom].p([cx + (x - cx) * g[0], cy + (y - cy) * g[1], cz + (z - cz) * g[2]]);
      for (const f of [
        [P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)], [P(x1, y0, z0), P(x0, y0, z0), P(x0, y1, z0), P(x1, y1, z0)],
        [P(x1, y0, z1), P(x1, y0, z0), P(x1, y1, z0), P(x1, y1, z1)], [P(x0, y0, z0), P(x0, y0, z1), P(x0, y1, z1), P(x0, y1, z0)],
        [P(x0, y1, z1), P(x1, y1, z1), P(x1, y1, z0), P(x0, y1, z0)],
      ]) sc.voile(f, coul, alpha);
    }
  }
}

// les huit queues du niveau 4 de Hachibi 2 (ClientPoulpe) : φ depuis le haut, le sens de l'enroulement, la longueur, le recul
const EVENTAIL = [[-16, 1, 88, -0.45], [16, -1, 88, -0.45], [-48, 1, 94, -0.3], [48, -1, 94, -0.3], [-86, -1, 90, -0.15], [86, 1, 90, -0.15], [-128, -1, 80, -0.35], [128, 1, 80, -0.35]];
const fine = (s) => 4.8 * (1 - 0.86 * s ** 0.85) + 0.3;

/** Les huit queues de chakra (en pixels de skin) : en éventail, en toupie (à plat tout autour), ou plantées dans le sol. */
function queues(s, mode) {
  EVENTAIL.forEach(([phi, sens, L, recul], k) => {
    const f = phi * DEG, base = [Math.sin(f) * 2.4, 13 + Math.cos(f) * 1.4, -2.4];
    if (mode === "toupie") {
      const a = (k * TOUR) / 8;
      tube(s, [Math.sin(a) * 2.4, 13, Math.cos(a) * 2.4], [Math.sin(a), 0.1, Math.cos(a)], [0, 1, 0], 96, fine, { c0: 0.013, n: 48 });
    } else if (mode === "haut") {
      // elles montent tout autour de lui et se rejoignent au-dessus de sa tête
      const a = (k * TOUR) / 8, d0 = norme([Math.sin(a), 0.5, Math.cos(a)]);
      tube(s, [Math.sin(a) * 2.4, 13, Math.cos(a) * 2.4], d0, norme(croix(d0, [0, 1, 0])), 84, fine, { c0: 0.0265, n: 48 });
    } else if (mode === "foreuse") {
      // torsadées autour de lui, vers sa tête, en pointe
      const a = (k * TOUR) / 8;
      tube(s, [Math.cos(a) * 7, 12, Math.sin(a) * 7], [Math.sin(a) * 0.5, 0.86, -Math.cos(a) * 0.5], [0, 1, 0], 84, (t) => 3.6 * (1 - 0.8 * t) + 0.3, { c0: 0.071, c1: 0.2, n: 60 });
    } else if (mode === "rafale") {
      // elles contournent son corps puis piquent droit devant, plus ou moins loin
      const d0 = norme([Math.sin(f), Math.cos(f) * 0.6, 0.6]);
      const { p, d } = tube(s, base, d0, norme(croix(d0, [0, 0, 1])), 30, () => 3.4, { c0: (Math.acos(d0[2]) / 30) * 0.9, n: 16 });
      tube(s, p, d, [0, 1, 0], 50 + 70 * hache(k, 1, 77), (t) => 3.4 * (1 - 0.9 * t) + 0.25, { n: 24 });
    } else if (mode === "sol") {
      const d0 = norme([Math.sin(f) * 0.9, 0.75, -0.55]);
      tube(s, base, d0, norme(croix(d0, [0, -1, 0])), 64, (t) => 3.2 * (1 - 0.5 * t), { c0: 0.042, n: 40 });
    } else tube(s, base, [Math.sin(f), Math.cos(f), recul], norme([0.35 * Math.cos(k * 1.7), 0.35 * Math.sin(k * 2.3), sens]), L, fine, { c0: 0.0015, c1: 0.11, n: 48, vague: 0.018, phase: k * 1.3 });
  });
}

/**
 * Le jinchuriki dans son manteau : son skin rougi qui brille, les cornes du Hachibi, les huit queues, des bulles de
 * chakra, puis le voile. o : sa pose (tete, brasD, brasG…), o.lacet / o.penche / o.decale (en blocs), o.queues.
 * Rend { s, T } : son repère (en pixels de skin) et ses parties.
 */
function jinchuriki(sc, o = {}) {
  const s = repere(sc, { lacet: o.lacet, penche: o.penche, decale: o.decale, k: 1 / U });
  queues(s, o.queues);
  const T = perso(s, STEVE, { tete: o.tete, brasD: o.brasD, brasG: o.brasG, jambeD: o.jambeD, jambeG: o.jambeG, teinte: { c: [222, 34, 18], k: 0.56 }, lueur: true });
  for (const c of [-1, 1]) {
    const x = (a, b) => (c > 0 ? [a, b] : [-b, -a]);
    const [a0, a1] = x(3.6, 6.6), [b0, b1] = x(5.6, 7.2), [c0, c1] = x(6.2, 7.2);
    paveT(s, T.tete, [a0, 29.4, -1.2], [a1, 31.4, 1.2], [226, 40, 20]);
    paveT(s, T.tete, [b0, 31.4, -0.9], [b1, 34.6, 0.9], [246, 84, 36]);
    paveT(s, T.tete, [c0, 34.6, -0.6], [c1, 36.8, 0.6], [255, 190, 110]);
    // les yeux blancs du manteau
    paveT(s, T.tete, [c * 1 + (c > 0 ? 0 : -2), 27, 3.9], [c * 1 + (c > 0 ? 2 : 0), 28, 4.2], [255, 255, 240]);
  }
  for (let q = 0; q < 16; q++) {
    const a = hache(q, 1, 900) * TOUR, r = 5 + 5 * hache(q, 2, 900);
    cubeSimple(s, [Math.cos(a) * r, 2 + 36 * hache(q, 3, 900), Math.sin(a) * r], 0.6 + 0.9 * hache(q, 4, 900), brille(q % 3 ? [255, 70, 30] : [255, 170, 90]), true);
  }
  manteau(s, T);
  return { s, T };
}

/** Un joueur touché (rougi), posé, tourné, renversé. */
const cible = (sc, pos, lacet = Math.PI, penche = 0) => petit(repere(sc, { decale: pos, lacet, penche }), [0, 0, 0], 0, { c: [255, 60, 60], k: 0.35 });

/** Des traits de vitesse (voiles), derrière c le long de dir, épais de ep. */
function filets(sc, c, dir, n, L, R, graine, ep = 0.1, coul = [255, 255, 255]) {
  const d = norme(dir), A = norme(Math.abs(d[1]) > 0.9 ? croix(d, [1, 0, 0]) : croix(d, [0, 1, 0])), B = croix(A, d);
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, graine) * TOUR, r = R * (0.4 + 0.6 * hache(q, 2, graine)), l = L * (0.5 + 0.5 * hache(q, 3, graine));
    const o = ajoute(c, ajoute(fois(A, Math.cos(a) * r), fois(B, Math.sin(a) * r))), b = ajoute(o, fois(d, -l));
    for (const E of [fois(A, ep), fois(B, ep)]) sc.voile([sous(o, E), ajoute(o, E), ajoute(b, E), sous(b, E)], coul, 0.7);
  }
}

/** La petite Bijuu Dama du manteau : noire, aux reflets rouges. */
const damaRouge = (sc, c, r) => boule(sc, c, r, (n, y) => brille(mix([18, 6, 12], [170, 26, 44], Math.max(0, n[1] * 0.45 + y * 0.35 + n[0] * 0.15))), { tranche: r / 4 });
/** Un petit dôme d'explosion rouge. */
function domeRouge(sc, c, r) {
  boule(sc, c, r, (n, y) => brille(mix([255, 240, 226], [226, 40, 30], Math.min(1, Math.max(0, 0.2 + y * 0.95)))), { tranche: r / 7, moitie: true });
  for (let q = 0; q < 24; q++) {
    const a = hache(q, 1, 910) * TOUR, rr = r * (1 + 0.25 * hache(q, 2, 910));
    cubeSimple(sc, ajoute(c, [Math.cos(a) * rr, r * 0.2 * hache(q, 3, 910), Math.sin(a) * rr]), r * (0.05 + 0.06 * hache(q, 4, 910)), brille(q % 3 ? [226, 40, 30] : [255, 170, 90]), true);
  }
}

/** Plusieurs taches de sol à la fois : la première qui répond gagne. */
const taches = (...l) => (x, z) => { for (const t of l) { const c = t(x, z); if (c) return c; } return null; };
/** Un disque (ou un anneau) peint au sol. */
const disque = (c, r, couleur, r0 = 0) => (x, z) => { const d = Math.hypot(x - c[0], z - c[2]); return d < r && d >= r0 ? couleur : null; };

const k = (v) => (typeof v === "number" ? v * K : fois(v, K));

// ------------------------------------------------------------------ les vues
const VUES = [
  // ---------------------------------------------------------------- le manteau
  ["J : LE MANTEAU DE CHAKRA", { lacet: -30, tangage: 8, echelle: 56, centre: [0, 0, 0], pied: 400 }, (sc) => {
    sol(sc, [-7, 7], [-5, 5]);
    jinchuriki(sc);
    sc.lueur(ROUGE, 6, 1.2);
    legende(sc, ["SON SKIN RECOUVERT DE CHAKRA ROUGE QUI BOUILLONNE", "LES CORNES DU HACHIBI, LES YEUX BLANCS, 8 QUEUES"]);
  }],
  ["DE DOS : SES 8 QUEUES EN CHAKRA", { lacet: 152, tangage: 8, echelle: 56, centre: [0, 0, 0], pied: 400 }, (sc) => {
    sol(sc, [-7, 7], [-5, 5]);
    jinchuriki(sc);
    sc.lueur(ROUGE, 6, 1.2);
    legende(sc, ["LES MEMES 8 TENTACULES QU'AVANT, MAIS EN CHAKRA ROUGE", "IL COURT PLUS VITE, SAUTE PLUS HAUT, ENCAISSE MIEUX"]);
  }],
  ["MANTEAU 1 : LE BRAS DE CHAKRA", { lacet: -66, tangage: 12, echelle: 42, centre: [0, 0, 3.6], pied: 372 }, (sc) => {
    const zc = 7.3;
    sol(sc, [-5, 5], [-4, 11], fissures([-0.3, 0, zc], 6, 2.2, 21));
    const { s } = jinchuriki(sc, { brasD: [-2.1, 0], brasG: [0.4, 0.2], penche: 0.12 });
    // un bras géant sort de son épaule, s'allonge et referme sa main griffue sur l'ennemi
    const { p, d } = tube(s, [-6, 22, 1], norme([0, 0.84, 1]), [1, 0, 0], 118, (t) => 2.6 + 2.4 * t, { c0: 0.0092, n: 44 });
    const A = [1, 0, 0], B0 = norme(croix(d, A)), B = B0[2] < 0 ? B0 : fois(B0, -1);
    const coul = (g) => (f, x, y, i, j) => [...mix([200, 24, 12], [250, 80, 30], hache(i, j, g)), 1];
    cube(s, ajoute(p, fois(d, 4)), [A, B, d], [7, 2.6, 5.5], coul(1), 1.6);
    cube(s, ajoute(ajoute(p, fois(d, 3)), fois(A, 8.6)), [A, B, d], [1.6, 2, 3.4], coul(2), 1.6);
    for (const x of [-5.1, -1.7, 1.7, 5.1]) {
      cube(s, ajoute(ajoute(p, fois(d, 13)), fois(A, x)), [A, B, d], [1.35, 1.8, 4], coul(3 + x), 1.4);
      cube(s, ajoute(ajoute(ajoute(p, fois(d, 17.4)), fois(A, x)), fois(B, 2.6)), [A, B, d], [1, 3, 1.1], () => [255, 214, 150, 1], 1.1);
    }
    cible(sc, [-0.3, 0, zc], Math.PI, -0.25);
    debris(sc, [-0.3, 0, zc], 10, 1.8, 22, 1.2, 0.6);
    anneau(sc, [-0.3, 0.1, zc], [0, 1, 0], 1.5, 0.3);
    sc.lueur(ROUGE, 6, 1.2);
    legende(sc, ["UN BRAS GEANT S'ALLONGE A 8 BLOCS ET SAISIT L'ENNEMI", "IL LE RAMENE VERS LUI OU LE PLAQUE AU SOL"]);
  }],
  ["MANTEAU 2 : LE LARIAT", { lacet: -58, tangage: 14, echelle: 40, centre: [0, 0, 1.2], pied: 372 }, (sc) => {
    const trace = (x, z) => (Math.abs(x) < 0.5 + 0.2 * hache(Math.round(z * 2), 1, 31) && z < 3.6 && z > -5 ? clair(TERRE, 0.75) : null);
    sol(sc, [-5, 5], [-6, 9], trace);
    const { s, T } = jinchuriki(sc, { penche: 0.55, decale: [0, 0, 3.6], brasD: [0, -1.5], brasG: [0.9, 0.3], jambeD: [0.7], jambeG: [-0.9], tete: [-0.4] });
    // le bras tendu, gonflé de chakra : une corne de taureau au bout
    const epaule = T.brasD.p([-6, 21, 0]), main = T.brasD.p([-6, 12, 0]), dir = norme(sous(main, epaule));
    tube(s, epaule, dir, [0, 1, 0], 15, (t) => 3.6 + 1.2 * t, { n: 10 });
    tube(s, main, norme(ajoute(dir, [0, 0.2, 0.5])), [0, 1, 0], 16, (t) => 3.4 * (1 - t) + 0.3, { c0: -0.06, n: 16 });
    cible(sc, [-1.4, 1.1, 5.4], Math.PI, -1.25);
    for (const [z, a] of [[1.9, 0.3], [0.2, 0.2], [-1.5, 0.12]]) fantome(repere(sc, { k: 1 / U, penche: 0.55, decale: [0, 0, z] }), [0, 0, 0], 0, a, [255, 70, 40]);
    filets(sc, [0, 1.3, 2.4], [0, 0, 1], 14, 5, 1.4, 32, 0.035);
    debris(sc, [0, 0, 3], 8, 1.2, 33, 0.8, 0.5);
    anneau(sc, [-1.1, 1.5, 4.6], [0, 0, 1], 0.7, 0.3, 0.6);
    sc.lueur(ROUGE, 6, 1.2);
    legende(sc, ["IL FONCE DE 12 BLOCS, LE BRAS TENDU (KILLER BEE)", "LE PREMIER TOUCHE EST EMPORTE ET PROJETE TRES LOIN"]);
  }],
  ["MANTEAU 3 : MINI BIJUU DAMA X3", { lacet: -72, tangage: 10, echelle: 29, centre: [0, 0, 6.6], pied: 372 }, (sc) => {
    sol(sc, [-5, 5], [-4, 15], fissures([0, 0, 12], 7, 2.6, 41));
    jinchuriki(sc, { penche: 0.2, brasD: [0.7, -0.35], brasG: [0.7, 0.35], tete: [-0.25] });
    damaRouge(sc, [0, 1.62, 1.0], 0.3);
    for (const [z, y] of [[4, 1.5], [7, 1.35], [9.6, 1.1]]) {
      damaRouge(sc, [0, y, z], 0.5);
      for (let n = 1; n <= 4; n++) cubeSimple(sc, [0, y + 0.03 * n, z - 0.5 - n * 0.42], 0.5 - n * 0.09, brille(mix([170, 26, 44], [255, 150, 110], n / 5)), true);
    }
    domeRouge(sc, [0, 0, 12], 2.1);
    cible(sc, [1.6, 0.9, 12.6], Math.PI + 0.5, -0.9);
    sc.lueur(ROUGE, 6, 1.2);
    legende(sc, ["3 PETITES BOULES NOIRES TIREES A LA SUITE", "CHACUNE EXPLOSE EN PETIT DOME (4 BLOCS)"]);
  }],
  ["MANTEAU 4 : LA TOUPIE DES 8 QUEUES", { lacet: -24, tangage: 30, echelle: 36, centre: [0, 0.4, 0], pied: 300 }, (sc) => {
    sol(sc, [-8, 8], [-7, 7]);
    jinchuriki(sc, { lacet: 0.7, queues: "toupie", brasD: [0, -1.35], brasG: [0, 1.35] });
    for (const [a, r, h] of [[0.4, 5.6, 0.9], [2.3, 6, 1.6], [4.1, 5.4, 0.6], [5.3, 6.2, 1.2]]) cible(sc, [Math.sin(a) * r, h, Math.cos(a) * r], a + Math.PI, -1.1);
    debris(sc, [0, 0, 0], 26, 6, 51, 1.6, 0.6);
    anneau(sc, [0, 0.75, 0], [0, 1, 0], 4.2, 0.5, 0.5, [255, 150, 110]);
    anneau(sc, [0, 0.75, 0], [0, 1, 0], 5.6, 0.35, 0.35, [255, 220, 200]);
    sc.lueur(ROUGE, 6, 1.2);
    legende(sc, ["IL TOURNE SUR LUI-MEME, LES QUEUES A L'HORIZONTALE", "TOUT CE QUI EST A 6 BLOCS AUTOUR EST FAUCHE"]);
  }],
  ["MANTEAU 5 : LES QUEUES SOUS LA TERRE", { lacet: -58, tangage: 18, echelle: 34, centre: [0, 0, 4.4], pied: 360 }, (sc) => {
    const PICS = [[-1.6, 6.2], [0.4, 7.4], [2, 6], [-0.6, 9], [1.6, 9.6], [-2.6, 8.4], [3.2, 8], [0.2, 11]];
    sol(sc, [-6, 6], [-4, 13], taches(...PICS.map(([x, z], n) => fissures([x, 0, z], 5, 1, 60 + n))));
    jinchuriki(sc, { queues: "sol", penche: 0.3, brasD: [-0.5, -0.3], brasG: [-0.5, 0.3], jambeD: [0.3], jambeG: [-0.3] });
    PICS.forEach(([x, z], n) => {
      const d0 = norme([0.3 * (hache(n, 1, 61) - 0.5), 1, 0.3 * (hache(n, 2, 61) - 0.5)]);
      tube(sc, [x, -0.2, z], d0, [1, 0, 0], 2.6 + 1.2 * hache(n, 3, 61), (t) => 0.36 * (1 - 0.92 * t) + 0.03, { c0: 0.12 * (hache(n, 4, 61) - 0.5), n: 14, m: 8 });
    });
    cible(sc, [0.4, 2.3, 7.4], Math.PI, -0.5);
    cible(sc, [-1.6, 1.9, 6.2], Math.PI + 0.6, 0.4);
    cible(sc, [1.6, 2.6, 9.6], Math.PI - 0.5, -0.8);
    debris(sc, [0.4, 0, 8], 22, 3.6, 62, 1.6, 0.5);
    sc.lueur(ROUGE, 6, 1.2);
    legende(sc, ["SES QUEUES PLONGENT DANS LE SOL...", "...ET RESSORTENT EN PICS SOUS LES ENNEMIS, 8 BLOCS DEVANT"]);
  }],
  ["MANTEAU 6 : LE RUGISSEMENT", { lacet: -32, tangage: 14, echelle: 30, centre: [0, 1, 0], pied: 372 }, (sc) => {
    sol(sc, [-9, 9], [-8, 8], fissures([0, 0, 0], 9, 3, 71));
    jinchuriki(sc, { tete: [-0.45], brasD: [0.5, -0.7], brasG: [0.5, 0.7], penche: -0.1 });
    for (const [a, r, h] of [[0.2, 6.4, 0.8], [1.9, 6, 1.3], [3.5, 6.6, 0.7], [5, 5.8, 1.1]]) cible(sc, [Math.sin(a) * r, h, Math.cos(a) * r], a + Math.PI, -0.9);
    debris(sc, [0, 0, 0], 30, 6.5, 72, 2, 0.6);
    boule(sc, [0, 0.2, 0], 3.4, () => [255, 70, 36], { voile: 0.2, tranche: 0.45, moitie: true });
    boule(sc, [0, 0.2, 0], 5.6, () => [255, 130, 80], { voile: 0.13, tranche: 0.6, moitie: true });
    anneau(sc, [0, 0.12, 0], [0, 1, 0], 5.4, 0.5, 0.5, [255, 200, 180]);
    anneau(sc, [0, 0.12, 0], [0, 1, 0], 7, 0.3, 0.3);
    sc.lueur(ROUGE, 6, 1.2);
    legende(sc, ["UNE ONDE DE CHAKRA REPOUSSE TOUT A 8 BLOCS AUTOUR", "TOUCHES : LENTS ET AFFAIBLIS 3 S"]);
  }],
  ["MANTEAU 7 : LA FOREUSE", { lacet: -62, tangage: 14, echelle: 36, centre: [0, 0, 3.4], pied: 372 }, (sc) => {
    const trace = (x, z) => (Math.abs(x) < 0.7 + 0.25 * hache(Math.round(z * 2), 1, 151) && z < 8 && z > -5 ? clair(TERRE, 0.72) : null);
    sol(sc, [-5, 5], [-6, 12], trace);
    jinchuriki(sc, { penche: 1.45, decale: [0, 1, 4.4], queues: "foreuse", brasD: [3.1, 0], brasG: [3.1, 0] });
    cible(sc, [-1.8, 1.2, 8.6], Math.PI + 0.6, -1.2);
    cible(sc, [1.9, 1.6, 7.6], Math.PI - 0.6, -1);
    for (const [z, r, a] of [[3.6, 0.9, 0.55], [2.4, 1.2, 0.4], [1, 1.5, 0.25]]) anneau(sc, [0, 1.2, z], [0, 0, 1], r, 0.25, a, [255, 150, 110]);
    filets(sc, [0, 1.2, 3.8], [0, 0, 1], 16, 6, 1.3, 152, 0.035);
    debris(sc, [0, 0, 6.5], 20, 2.2, 153, 1.2, 0.5);
    sc.lueur(ROUGE, 6, 1.2);
    legende(sc, ["SES 8 QUEUES SE TORSADENT AUTOUR DE LUI EN FOREUSE", "IL TRAVERSE TOUT EN LIGNE DROITE SUR 15 BLOCS"]);
  }],
  ["MANTEAU 8 : LA RAFALE DE QUEUES", { lacet: -64, tangage: 12, echelle: 38, centre: [0, 0, 3.4], pied: 372 }, (sc) => {
    sol(sc, [-5, 5], [-4, 11], taches(fissures([-0.6, 0, 6.4], 5, 1.4, 161), fissures([0.9, 0, 7.4], 5, 1.4, 162)));
    jinchuriki(sc, { queues: "rafale", penche: 0.15, brasD: [-0.5, -0.25], brasG: [-0.5, 0.25] });
    cible(sc, [-0.6, 0.5, 6.6], Math.PI, -0.7);
    cible(sc, [0.9, 0.9, 7.6], Math.PI + 0.4, -1.1);
    for (const [x, y, z] of [[-0.5, 1.3, 6], [0.8, 1.6, 6.8], [0.1, 0.9, 5.2]]) anneau(sc, [x, y, z], [0, 0, 1], 0.45, 0.2, 0.6);
    debris(sc, [0, 0, 6.8], 16, 2, 163, 1.2, 0.5);
    filets(sc, [0, 1.3, 6], [0, 0, 1], 12, 3, 1.5, 164, 0.03);
    sc.lueur(ROUGE, 6, 1.2);
    legende(sc, ["LES 8 QUEUES PIQUENT DEVANT LUI, L'UNE APRES L'AUTRE", "8 COUPS EN 2 S, JUSQU'A 7 BLOCS, EN CONE"]);
  }],
  ["TOUCHE I : IL CHARGE LA GRANDE BOULE", { lacet: -30, tangage: 10, echelle: 33, centre: [0, 4.2, 0], pied: 222 }, (sc) => {
    sol(sc, [-8, 8], [-7, 7], taches(disque([0, 0, 0], 1.6, [58, 92, 44]), fissures([0, 0, 0], 9, 4.5, 171)));
    jinchuriki(sc, { decale: [0, 1.5, 0], queues: "haut", brasD: [3, -0.3], brasG: [3, 0.3], tete: [-0.5] });
    const c = [0, 8.2, 0];
    damaRouge(sc, c, 2.3);
    for (let q = 0; q < 70; q++) {
      const a = hache(q, 1, 172) * TOUR, b = (hache(q, 2, 172) - 0.5) * Math.PI, d = 2.7 + 3 * hache(q, 3, 172);
      cubeSimple(sc, ajoute(c, [Math.cos(a) * Math.cos(b) * d, Math.sin(b) * d, Math.sin(a) * Math.cos(b) * d]), 0.12 + 0.24 * hache(q, 4, 172), brille([[20, 8, 14], [226, 40, 30], [255, 170, 90], [255, 255, 255]][q % 4]), q % 4 > 0);
    }
    debris(sc, [0, 0, 0], 26, 4.5, 173, 3, 0.5);
    anneau(sc, [0, 0.12, 0], [0, 1, 0], 3.4, 0.4, 0.5, [255, 150, 110]);
    sc.lueur(ROUGE, 7, 1.3);
    legende(sc, ["IL S'ELEVE, SES 8 QUEUES SE REJOIGNENT AU-DESSUS DE LUI", "3 S DE CHARGE : UNE BIJUU DAMA DE 5 BLOCS DE LARGE"]);
  }],
  ["TOUCHE I : ET IL LA LACHE", { lacet: -42, tangage: 16, echelle: 14.5, centre: [0, 2, 8], pied: 310 }, (sc) => {
    const z = [0, 0, 15];
    sol(sc, [-20, 20], [-10, 32], fissures(z, 13, 14, 181));
    const { s } = jinchuriki(sc, { decale: [0, 5.5, 0], penche: 0.5, brasD: [-1.2, 0], brasG: [-1.2, 0] });
    for (let n = 1; n <= 9; n++) cubeSimple(sc, ajoute([0, 6.6, 0.8], fois(sous([0, 3, 12], [0, 6.6, 0.8]), n / 10)), 0.5 + n * 0.14, brille(mix([255, 170, 110], [170, 26, 44], n / 9)), true);
    domeRouge(sc, z, 9);
    for (const [a, r, h] of [[0.4, 11, 2], [1.6, 12, 4.5], [2.9, 10.5, 1.5], [4.2, 12, 3.5], [5.4, 11, 6]]) cible(sc, ajoute(z, [Math.sin(a) * r, h, Math.cos(a) * r]), a, -1.1);
    debris(sc, z, 70, 14, 182, 6, 1.2);
    anneau(sc, ajoute(z, [0, 0.15, 0]), [0, 1, 0], 11, 1, 0.5, [255, 200, 180]);
    anneau(sc, ajoute(z, [0, 0.15, 0]), [0, 1, 0], 14, 0.7, 0.3);
    void s;
    sc.lueur(ROUGE, 7, 1.3);
    legende(sc, ["ELLE EXPLOSE EN DOME DE 18 BLOCS : SA PLUS GROSSE ATTAQUE", "UNE SEULE FOIS PAR MANTEAU"]);
  }],
  // ---------------------------------------------------------------- le géant
  ["J ENCORE : LE HACHIBI ENTIER", { lacet: -24, tangage: 8, echelle: 5.4, centre: [0, 0, 0], pied: 400 }, (sc) => {
    sol(sc, [-50, 50], [-46, 40]);
    const { haut } = hachibi(sc, { G });
    chakra(sc, [0, 22, 0], 30, 46, 70, 81, K);
    const e = [];
    const [hx, hy] = sc.vue([0, haut + 2, -14]);
    e.push(["LE MEME GEANT : 51 BLOCS", hx - 96, hy - 20, 2]);
    ecrire(sc, e);
    sc.lueur(ROUGE, 6, 1.2);
    legende(sc, ["LA 2E FOIS : LE GEANT, COMME IL EST AUJOURD'HUI, 45 S", "IL GARDE POINGS ET BIJUU DAMA + LES IDEES QUI SUIVENT"]);
  }],
  ["GEANT 1 : LE RAYON", { lacet: -76, tangage: 13, echelle: 3.3, centre: [0, 0, 66], pied: 340 }, (sc) => {
    const arc = (t) => [44 - 68 * t, 0, 118 - 18 * (t - 0.5) ** 2 * 4];
    const brule = (x, z) => {
      for (let t = 0; t <= 1; t += 0.02) { const p = arc(t); const d = Math.hypot(x - p[0], z - p[2]); if (d < 4.5) return d < 2.6 ? [30, 22, 26] : [70, 44, 40]; }
      return null;
    };
    sol(sc, [-40, 56], [-40, 140], brule);
    const { gueule } = hachibi(sc, { G, gueule: 1, tete: 8 });
    const fin = arc(1), d = norme(sous(fin, gueule));
    tube(sc, ajoute(gueule, fois(d, 2)), d, [0, 1, 0], Math.hypot(...sous(fin, gueule)) - 2, () => 3.2, { n: 30, m: 12, coul: rayonViolet });
    for (let q = 0; q < 60; q++) {
      const p = arc(hache(q, 1, 91));
      cubeSimple(sc, ajoute(p, [(hache(q, 2, 91) - 0.5) * 6, 0.8 + 5 * hache(q, 3, 91) ** 2, (hache(q, 4, 91) - 0.5) * 6]), 1 + 1.6 * hache(q, 5, 91), brille(q % 3 ? [255, 130, 40] : [255, 220, 120]), true);
    }
    dome(sc, fin, 9);
    for (const [x, z] of [[-14, 122], [-30, 108], [-20, 130]]) cible(sc, [x, 4 + (x % 5), z], Math.PI + 0.4, -1);
    sc.lueur([190, 110, 240], 6, 1.3);
    legende(sc, ["UN RAYON CONTINU DE 3 S, QU'IL BALAIE EN TOURNANT LA TETE", "PORTEE 100 BLOCS, IL BRULE LE SOL SUR SON PASSAGE"]);
  }],
  ["GEANT 2 : LA TOUPIE GEANTE", { lacet: -30, tangage: 24, echelle: 3.1, centre: [0, 4, 0], pied: 310 }, (sc) => {
    sol(sc, [-78, 78], [-70, 70], fissures([0, 0, 0], 12, 30, 101));
    const C = [0, 7, -5];
    const P = (th, r, a, y) => ajoute(C, [Math.sin(th + a) * r, y - 7, Math.cos(th + a) * r]);
    const chemins = Array.from({ length: 8 }, (_, n) => {
      const th = (n + 0.5) * TOUR / 8;
      return { morceaux: [[P(th, 6, 0, 7), P(th, 24, 0.15, 15), P(th, 44, 0.55, 12), P(th, 64, 1.05, 5)]], dessous: [0, -1, 0] };
    });
    hachibi(repere(sc, { lacet: 0.5 }), { G, queues: chemins, brasD: "arme", brasG: "arme", gueule: 0.7, tete: 6 });
    for (const [a, r, h] of [[0.3, 56, 9], [1.7, 60, 16], [3.2, 52, 6], [4.4, 62, 12], [5.5, 55, 20]]) cible(sc, [Math.sin(a) * r, h, Math.cos(a) * r], a, -1.1);
    debris(sc, [0, 0, 0], 90, 66, 102, 16, K);
    anneau(sc, [0, 8, 0], [0, 1, 0], 50, 4, 0.4);
    anneau(sc, [0, 5, 0], [0, 1, 0], 64, 3, 0.3);
    anneau(sc, [0, 14, 0], [0, 1, 0], 36, 3, 0.25, [255, 200, 210]);
    legende(sc, ["IL TOURNE SUR LUI-MEME, SES 8 QUEUES A L'HORIZONTALE", "TOUT EST FAUCHE A 60 BLOCS AUTOUR ET S'ENVOLE"]);
  }],
  ["GEANT 3 : LA SAISIE", { lacet: -62, tangage: 10, echelle: 5.2, centre: [0, 0, 10], pied: 376 }, (sc) => {
    const prise = [-9, 37, 33];
    sol(sc, [-48, 48], [-42, 60], taches(disque([-9, 0, 40], 9, [226, 60, 50], 7.6), fissures([-9, 0, 40], 7, 7, 111)));
    hachibi(sc, { G, gueule: 0.6, tete: 4, brasD: "arme", remplace: { 0: { morceaux: [
      [[-0.9, 5.8, -7.6], [-4, 38, -30], [-8, 66, -2], [-9, 50, 26]],
      [[-9, 50, 26], [-9, 42, 38], [-9, 31, 38], [-9, 32, 30]],
    ], dessous: [0, -0.4, 1] } } });
    cible(sc, prise, Math.PI, 0.3);
    const [x, y] = sc.vue(prise);
    ecrire(sc, [["ATTRAPE !", x - 96, y - 4, 2]]);
    filets(sc, [-9, 26, 40], [0, -1, 0], 8, 14, 4, 112, 0.14, [255, 120, 110]);
    legende(sc, ["UNE QUEUE ATTRAPE UN JOUEUR ET LE SOULEVE A 35 BLOCS", "PUIS ELLE L'ECRASE AU SOL (OU LE LANCE AU LOIN)"]);
  }],
  ["GEANT 4 : LE SAUT ECRASANT", { lacet: -38, tangage: 12, echelle: 3.3, centre: [0, 16, 6], pied: 300 }, (sc) => {
    const ombre = (x, z) => { const d = Math.hypot(x, (z - 4) * 1.15); return d < 24 ? [58, 92, 44] : d < 34 && d > 31.5 ? [226, 60, 50] : null; };
    sol(sc, [-60, 60], [-52, 56], ombre);
    hachibi(sc, { G, pos: [0, 30, 0], brasD: "arme", brasG: "arme", gueule: 1, tete: 20 });
    filets(sc, [0, 84, 0], [0, -1, 0], 22, 22, 30, 121, 0.22);
    for (const [x, z] of [[14, 30], [-20, 22], [4, 36]]) petit(sc, [x, 0, z], Math.PI);
    legende(sc, ["IL BONDIT A 30 BLOCS ET RETOMBE LA OU IL REGARDE", "CERCLE ROUGE = ZONE D'IMPACT, ONDE DE CHOC DE 30 BLOCS"]);
  }],
  ["GEANT 5 : LA PLUIE DE BIJUU DAMA", { lacet: -64, tangage: 12, echelle: 2.7, centre: [0, 24, 44], pied: 286 }, (sc) => {
    const SOL = [[-16, 50], [14, 74], [-6, 98], [22, 40], [-26, 82], [8, 118]];
    sol(sc, [-44, 50], [-40, 136], taches(...SOL.slice(3).map(([x, z]) => disque([x, 0, z], 9, [226, 60, 50], 7.4)), ...SOL.slice(0, 3).map(([x, z], n) => fissures([x, 0, z], 7, 9, 130 + n))));
    const { gueule } = hachibi(sc, { G, gueule: 1, tete: -26 });
    bijuuDama(sc, ajoute(gueule, [0, 12, 6]), 5);
    for (let n = 1; n <= 5; n++) cubeSimple(sc, ajoute(gueule, [0, 12 - n * 2.2, 6 - n * 1.1]), 5 - n * 0.8, brille(mix([74, 44, 110], [200, 170, 230], n / 6)), true);
    SOL.slice(0, 3).forEach(([x, z]) => dome(sc, [x, 0, z], 7.5));
    SOL.slice(3).forEach(([x, z], n) => {
      const y = 22 + n * 13;
      bijuuDama(sc, [x, y, z], 3.2);
      for (let q = 1; q <= 5; q++) cubeSimple(sc, [x, y + 2 + q * 2.4, z - q * 0.5], 3 - q * 0.5, brille(mix([74, 44, 110], [200, 170, 230], q / 6)), true);
    });
    sc.lueur([150, 90, 220], 6, 1.3);
    legende(sc, ["IL TIRE VERS LE CIEL : 8 BIJUU DAMA RETOMBENT EN PLUIE", "UN CERCLE ROUGE PREVIENT, CHACUNE VISE UN JOUEUR"]);
  }],
  ["GEANT 6 : LA CHARGE DU TAUREAU", { lacet: -60, tangage: 14, echelle: 3.3, centre: [0, 4, 26], pied: 330 }, (sc) => {
    const sillon = (x, z) => (Math.abs(x) < 15 + 2 * hache(Math.round(z / 2), 1, 141) && z < 60 ? clair(TERRE, 0.62 + 0.3 * hache(Math.round(x), Math.round(z), 142)) : null);
    sol(sc, [-44, 44], [-70, 110], sillon);
    hachibi(repere(sc, { penche: 0.14, decale: [0, 0, 44] }), { G, tete: 40, gueule: 0.4 });
    debris(sc, [0, 0, 72], 70, 22, 143, 14, K);
    for (const [x, y, z] of [[-10, 14, 80], [8, 22, 86], [16, 9, 76], [-4, 30, 92]]) cible(sc, [x, y, z], Math.PI + x * 0.05, -1.2);
    filets(sc, [0, 24, 30], [0, 0, 1], 26, 46, 30, 144, 0.22);
    anneau(sc, [0, 12, 76], [0, 0, 1], 18, 2.5, 0.4);
    legende(sc, ["TETE BAISSEE, CORNES EN AVANT : IL FONCE SUR 60 BLOCS", "IL LABOURE LE SOL ET PROJETTE TOUT SUR SON CHEMIN"]);
  }],
];

// ------------------------------------------------------------------ le résumé
const T = [200, 40, 30];
const RESUME = [
  ["HACHIBI NIVEAU 3", T],
  ["EVEIL : LES 4 FORMES, COMME AVANT", null],
  ["J, 1ERE FOIS : LE MANTEAU DE CHAKRA", null],
  ["   JUSQU'A LA FIN DE L'EVEIL (1 A 2 MIN)", null],
  ["J, 2E FOIS : LE HACHIBI ENTIER, 45 S", null],
  ["", null],
  ["LE MANTEAU (TOUCHES ET DEGATS A REGLER)", T],
  ["CLIC GAUCHE         1 BRAS DE CHAKRA    7", null],
  ["CLIC DROIT          2 LARIAT           10", null],
  ["ACCROUPI            3 MINI BIJUU DAMA  3X6", null],
  ["ACCROUPI + DROIT    4 TOUPIE            8", null],
  ["ACCROUPI + GAUCHE   5 PICS              9", null],
  ["TOUCHE TECHNIQUE    6 RUGISSEMENT       4", null],
  ["TOUCHE A CHOISIR    7 FOREUSE          12", null],
  ["TOUCHE A CHOISIR    8 RAFALE           8X2", null],
  ["TOUCHE I            LA GRANDE BOULE    25", null],
  ["LE GEANT", T],
  ["ON GARDE LES POINGS ET LA BIJUU DAMA", null],
  ["ET ON AJOUTE LES IDEES QUE TU CHOISIS", null],
  ["DIS-MOI LES NUMEROS QUE TU GARDES !", null],
];
function resume(sc) {
  for (let i = 0; i < sc.w * sc.h; i++) sc.img.set([250, 246, 246, 255], i * 4);
  RESUME.forEach(([t, c], n) => ecrireTexte(sc.img, sc.w, t, 20, 16 + n * 22, c ? 3 : 2, [...(c ?? [60, 52, 64]), 255]));
}

// ------------------------------------------------------------------ la planche
const CW = 520, CH = 470, M = 40, HAUT = 64, COL = 4;
const cases = [...VUES, ["RESUME", null, resume]];
const lignes = Math.ceil(cases.length / COL);
const W = CW * COL, H = HAUT + (M + CH) * lignes;
const seule = process.argv[2] ? Number(process.argv[2]) : null;
const planche = Buffer.alloc(W * H * 4, 255);
ecrireTexte(planche, W, "HACHIBI NIVEAU 3 : LE MANTEAU DE CHAKRA, PUIS LE HACHIBI ENTIER", 18, 16, 5, [170, 30, 30, 255]);
cases.forEach(([titre, o, dessin], n) => {
  if (seule !== null && seule !== n) return;
  const c = n % COL, l = Math.floor(n / COL);
  const sc = scene({ w: CW, h: CH, fond: FOND, ...(o ?? {}), ...(seule === n && process.argv[3] ? JSON.parse(process.argv[3]) : {}) });
  dessin(sc);
  if (seule !== null) ecrirePng(path.join(ICI, `vue-${n}.png`), CW, CH, sc.img);
  for (let y = 0; y < CH; y++) sc.img.copy(planche, ((HAUT + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
  ecrireTexte(planche, W, titre, c * CW + 12, HAUT + l * (M + CH) + 10, 3, n < 12 ? [200, 40, 30, 255] : n < 19 ? [40, 90, 170, 255] : [60, 52, 64, 255]);
  console.log("vue", n, titre);
});
if (seule === null) ecrirePng(path.join(ICI, "..", "planche-hachibi5.png"), W, H, planche);
console.log("ok", W, H);
