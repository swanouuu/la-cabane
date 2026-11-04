// Maquette : Hachibi 4. Le même Éveil que Hachibi 2, plus un ulti (une touche à lui) qui le change en Hachibi entier, le
// modèle du géant aux 2/3 (51 blocs avec les queues au lieu de 76), pendant 45 s. Ses attaques dans cette forme : les
// poings droite-gauche (clic gauche), la grosse Bijuu Dama noire (clic droit, toutes les 10 s), le grand coup des huit
// queues abattues devant lui (accroupi), et l'encre en idée de plus.
// node rendu.mjs → ../planche-hachibi4.png
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, scene, ecrireTexte, clair, mix, hache, norme, croix } from "../../fruits/generateur/moteur.mjs";
import { boule, cubeSimple, brille } from "../../minato2/generateur/effets.mjs";
import { hachibi, joueur, ajoute, sous, fois, DEG } from "./bete.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const FOND = [208, 226, 242];
const HERBE = [100, 164, 60];
const TERRE = [134, 96, 66];
const NOIR_BD = [16, 8, 22];
const VIOLET_BD = [74, 44, 110];
const ROSE = [255, 150, 180];
const TOUR = Math.PI * 2;

// ------------------------------------------------------------------ le décor et les effets (en blocs)
/** L'herbe, bloc par bloc ; tache(x, z) peut la recouvrir (fissures, encre, ombre). */
function sol(sc, [x0, x1], [z0, z1], tache = () => null) {
  const l = sc.lumiere([0, 1, 0], false);
  for (let x = x0; x < x1; x++) for (let z = z0; z < z1; z++) {
    for (let j = 0; j < 2; j++) for (let i = 0; i < 2; i++) {
      const a = x + i / 2, b = z + j / 2;
      const h = hache(x * 2 + i, z * 2 + j, 3);
      const c = tache(a + 0.25, b + 0.25) ?? clair(h < 0.15 ? [84, 142, 48] : HERBE, 0.92 + 0.12 * hache(x * 2 + i, z * 2 + j, 4));
      sc.quad([[a, 0, b], [a + 0.5, 0, b], [a + 0.5, 0, b + 0.5], [a, 0, b + 0.5]], clair(c, l));
    }
  }
  // le bord de devant : l'herbe qui dépasse, la terre
  const lb = sc.lumiere([0, 0, 1], false);
  if (lb > 0) for (let x = x0; x < x1; x++) for (let y = -1; y >= -3; y--) {
    for (let j = 0; j < 2; j++) for (let i = 0; i < 2; i++) {
      const c = y === -1 && j === 0 ? HERBE : TERRE;
      const a = x + i / 2, b = y + 1 - j / 2;
      sc.quad([[a, b, z1], [a + 0.5, b, z1], [a + 0.5, b - 0.5, z1], [a, b - 0.5, z1]], clair(c, lb * (0.9 + 0.16 * hache(x * 2 + i, y * 2 + j, 9))));
    }
  }
}

/** Des fissures sombres qui partent en étoile d'un point. */
const fissures = (c, n, R, graine) => (x, z) => {
  const dx = x - c[0], dz = z - c[2], d = Math.hypot(dx, dz);
  if (d > R) return null;
  const a = Math.atan2(dz, dx);
  for (let k = 0; k < n; k++) {
    const ak = (k / n) * TOUR + hache(k, 1, graine) * 0.6 + 0.25 * Math.sin(d * 0.9 + k);
    let da = Math.abs(a - ak) % TOUR;
    da = Math.min(da, TOUR - da);
    if (da * d < 0.32 * (1 - d / R) + 0.1) return [52, 40, 30];
  }
  return d < R * 0.35 ? clair(TERRE, 0.8) : null;
};

/** Un anneau de choc, à plat (normale n) : des voiles blancs. */
function anneau(sc, c, n, r, ep, alpha = 0.55, couleur = [255, 255, 255]) {
  const U = norme(Math.abs(n[1]) > 0.9 ? croix(n, [1, 0, 0]) : croix(n, [0, 1, 0])), V = croix(n, U);
  const N = 40;
  for (let k = 0; k < N; k++) {
    const a = (k / N) * TOUR, b = ((k + 1) / N) * TOUR;
    const p = (ang, rr) => ajoute(c, ajoute(fois(U, Math.cos(ang) * rr), fois(V, Math.sin(ang) * rr)));
    sc.voile([p(a, r), p(b, r), p(b, r + ep), p(a, r + ep)], couleur, alpha);
  }
}

/** Des morceaux de terre et d'herbe projetés autour d'un point. */
function debris(sc, c, n, R, graine, haut = 4, taille = 1) {
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, graine) * TOUR, d = R * (0.3 + 0.7 * hache(q, 2, graine));
    const p = ajoute(c, [Math.cos(a) * d, 0.3 + haut * hache(q, 3, graine) ** 1.5, Math.sin(a) * d]);
    cubeSimple(sc, p, taille * (0.3 + 0.5 * hache(q, 4, graine)), q % 3 ? TERRE : HERBE);
  }
}

/** Des traits de vitesse (voiles fins) derrière un point, le long de dir. */
function traits(sc, c, dir, n, L, R, graine) {
  const d = norme(dir), U = norme(croix(d, [0, 1, 0])), V = croix(U, d);
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, graine) * TOUR, r = R * (0.4 + 0.6 * hache(q, 2, graine)), l = L * (0.5 + 0.5 * hache(q, 3, graine));
    const o = ajoute(c, ajoute(fois(U, Math.cos(a) * r), fois(V, Math.sin(a) * r)));
    const e = fois(U, 0.07);
    const b = ajoute(o, fois(d, -l));
    sc.voile([sous(o, e), ajoute(o, e), ajoute(b, e), sous(b, e)], [255, 255, 255], 0.7);
  }
}

/** La Bijuu Dama : une grosse boule noire aux reflets violets, qui luit (la lueur se pose autour). */
function bijuuDama(sc, c, r) {
  boule(sc, c, r, (n, y) => brille(mix(NOIR_BD, VIOLET_BD, Math.max(0, n[1] * 0.45 + y * 0.35 + n[0] * 0.15))), { tranche: Math.max(0.5, r / 7) });
}

/** Le chakra qui converge vers la boule : des éclats noirs, violets, roses et blancs. */
function chakra(sc, c, r, R, n, graine, taille = 1) {
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, graine) * TOUR, b = (hache(q, 2, graine) - 0.5) * Math.PI;
    const d = r + (R - r) * hache(q, 3, graine);
    const col = [NOIR_BD, VIOLET_BD, ROSE, [255, 255, 255]][q % 4];
    cubeSimple(sc, ajoute(c, [Math.cos(a) * Math.cos(b) * d, Math.sin(b) * d, Math.sin(a) * Math.cos(b) * d]), taille * (0.25 + 0.45 * hache(q, 4, graine)), brille(col), q % 4 > 0);
  }
}

/** Le dôme de l'explosion : noir et violet, des éclats tout autour. */
function dome(sc, c, r) {
  boule(sc, c, r, (n, y) => brille(mix([255, 236, 250], VIOLET_BD, Math.min(1, Math.max(0, 0.25 + y * 0.9 - n[0] * 0.2)))), { tranche: r / 8, moitie: true });
  for (let q = 0; q < 40; q++) {
    const a = hache(q, 1, 830) * TOUR, rr = r * (1 + 0.2 * hache(q, 2, 830));
    cubeSimple(sc, ajoute(c, [Math.cos(a) * rr, r * 0.15 * hache(q, 3, 830), Math.sin(a) * rr]), r * (0.04 + 0.05 * hache(q, 4, 830)), brille(q % 3 ? VIOLET_BD : ROSE), true);
  }
}

/** Un joueur projeté (rougi), un peu au-dessus du sol. */
const touche = (sc, p, lacet) => joueur(sc, p, lacet, { c: [255, 60, 60], k: 0.35 });

/** Une règle rouge et blanche (tous les 5 blocs), et ses chiffres. */
function regle(sc, [x, z], hauteur, etiquettes) {
  for (let y = 0; y < hauteur; y++) cubeSimple(sc, [x, y + 0.5, z], 0.8, Math.floor(y / 5) % 2 ? [236, 236, 236] : [200, 40, 40]);
  for (let y = 5; y <= hauteur; y += 5) {
    const [sx, sy] = sc.vue([x - 0.8, y, z]);
    etiquettes.push([String(y), sx + 14, sy - 6, 2]);
  }
}

/** Écrit des étiquettes (texte, x, y, taille) sur un cartouche clair. */
function ecrire(sc, etiquettes) {
  for (const [texte, x, y, t] of etiquettes) {
    const larg = texte.length * 4 * t;
    const x0 = Math.round(Math.max(2, Math.min(sc.w - larg - 2, x))), y0 = Math.round(Math.max(2, Math.min(sc.h - 6 * t, y)));
    for (let yy = y0 - t; yy < y0 + 6 * t; yy++) for (let xx = x0 - t; xx < x0 + larg; xx++) {
      const i = (yy * sc.w + xx) * 4;
      for (let c = 0; c < 3; c++) sc.img[i + c] = sc.img[i + c] * 0.3 + 255 * 0.7;
    }
    ecrireTexte(sc.img, sc.w, texte, x0, y0, t, [30, 30, 40, 255]);
  }
}

function legende(sc, lignes) {
  const h = 16 + lignes.length * 18;
  for (let y = sc.h - h; y < sc.h; y++) for (let x = 0; x < sc.w; x++) {
    const i = (y * sc.w + x) * 4;
    for (let c = 0; c < 3; c++) sc.img[i + c] = sc.img[i + c] * 0.15 + 248 * 0.85;
  }
  lignes.forEach((t, k) => ecrireTexte(sc.img, sc.w, t, 14, sc.h - h + 9 + k * 18, 2, [60, 52, 64, 255]));
}

// ------------------------------------------------------------------ les vues
// la taille de la bête : 2/3 du géant (G = 1 au lieu de 1,5), soit ~30 blocs de corps et ~51 avec les queues, plus haut
// que le soleil de magma de la lave 3 (48 blocs de large) ; K : combien de fois plus gros que la première maquette
const G = 1;
const K = G / 0.375;
const k = (v) => (typeof v === "number" ? v * K : fois(v, K));
const zone = ([a, b]) => [Math.round(a * K), Math.round(b * K)];

/** Le poing (en blocs) d'un bras posé en "coup" : s = −1 droit, +1 gauche. */
const poing = (s) => fois([s * 7.9, 1.9, 26.9], G);

/** Le soleil de magma de la lave 3, pour comparer : 48 blocs de large. */
function soleil(sc, c) {
  boule(sc, c, 24, (n, y) => brille(mix([255, 216, 80], [222, 58, 16], Math.min(1, Math.max(0, 0.5 - y * 0.5 + n[0] * 0.2)))), { tranche: 2.4 });
}

/** Un coup de poing : les fissures, le joueur touché, la terre qui saute, les ondes, les traits de vitesse. */
function coupDePoing(sc, s, graine) {
  const p = poing(s), c = [p[0], 0, p[2] + k(0.6)];
  sol(sc, zone([-18, 18]), zone([-16, 20]), fissures(c, 7, k(3.4), graine));
  hachibi(sc, { G, brasD: s < 0 ? "coup" : "arme", brasG: s < 0 ? "arme" : "coup" });
  touche(sc, [p[0] + s * 1.5, 0.8, p[2] + k(1.6)], Math.PI);
  touche(sc, [p[0] - s * 3, 2.5, p[2] + k(2.4)], Math.PI + 0.6);
  debris(sc, c, 18, k(3.2), graine + 10, k(2.5), K);
  anneau(sc, ajoute(c, [0, 0.15, 0]), [0, 1, 0], k(2.6), k(0.6));
  anneau(sc, ajoute(c, [0, 0.15, 0]), [0, 1, 0], k(4), k(0.4), 0.35);
  traits(sc, p, [-s * 0.3, -0.6, 0.75], 10, k(4), k(1.6), graine + 20);
}

const VUES = [
  ["TAILLE : 51 BLOCS (LE GEANT : 76)", { lacet: -24, tangage: 8, echelle: 3.5, centre: [-24, 0, 0], pied: 400 }, (sc) => {
    sol(sc, [-96, 50], [-50, 44]);
    const { haut } = hachibi(sc, { G });
    soleil(sc, [-70, 24, 4]);
    joueur(sc, [22, 0, 30], Math.PI + 0.4);
    const e = [];
    regle(sc, [46, 26], 55, e);
    const [x, y] = sc.vue([22, 2.4, 30]);
    e.push(["TOI", x - 12, y - 22, 2]);
    const [hx, hy] = sc.vue([0, haut + 2, -14]);
    e.push([`HACHIBI 4 : ${Math.round(haut)} BLOCS`, hx - 90, hy - 22, 2]);
    const [sx, sy] = sc.vue([-70, 50, 4]);
    e.push(["SOLEIL DE MAGMA : 48", sx - 80, sy - 20, 2]);
    ecrire(sc, e);
    legende(sc, ["LE MODELE DU GEANT, AUX 2/3 : LE CORPS FAIT 30 BLOCS", "PLUS GROS QUE LE SOLEIL DE LA LAVE 3"]);
  }],
  ["CLIC GAUCHE : POING DROIT", { lacet: 58, tangage: 12, echelle: 15 / K, centre: k([0, 0, 4]), pied: 360 }, (sc) => {
    coupDePoing(sc, -1, 11);
    legende(sc, ["IL FRAPPE DEVANT LUI, VERS LE SOL", "DROITE, PUIS GAUCHE, PUIS DROITE..."]);
  }],
  ["CLIC GAUCHE : POING GAUCHE", { lacet: -58, tangage: 12, echelle: 15 / K, centre: k([0, 0, 4]), pied: 360 }, (sc) => {
    coupDePoing(sc, 1, 12);
    legende(sc, ["CHAQUE COUP FAIT UNE ONDE DE CHOC", "QUI TOUCHE TOUT CE QUI EST AUTOUR"]);
  }],
  ["CLIC DROIT : LA BIJUU DAMA", { lacet: -66, tangage: 8, echelle: 12 / K, centre: k([0, 0, 7]), pied: 372 }, (sc) => {
    sol(sc, zone([-20, 20]), zone([-18, 24]));
    const { gueule, devant } = hachibi(sc, { G, gueule: 1, tete: 2 });
    const c = ajoute(gueule, fois(devant, k(5.2)));
    bijuuDama(sc, c, k(4.4));
    chakra(sc, c, k(5), k(10), 80, 41, K);
    sc.lueur([150, 90, 220], 6, 1.4);
    legende(sc, ["IL LA CHARGE DANS SA GUEULE : 23 BLOCS DE LARGE", "BOULE NOIRE, CHAKRA QUI CONVERGE"]);
  }],
  ["CLIC DROIT : IL LA TIRE, TOUTES LES 10 S", { lacet: -78, tangage: 12, echelle: 6.2 / K, centre: k([0, 0, 30]), pied: 330 }, (sc) => {
    sol(sc, zone([-14, 14]), zone([-16, 80]), fissures(k([0, 0, 64]), 9, k(11), 51));
    const { gueule, devant } = hachibi(sc, { G, gueule: 1, tete: 4 });
    const c = ajoute(gueule, fois(devant, k(22)));
    bijuuDama(sc, c, k(4));
    for (let n = 1; n <= 5; n++) cubeSimple(sc, ajoute(c, fois(devant, -k(4 + n * 2.2))), k(2.4 - n * 0.38), brille(mix(VIOLET_BD, [200, 170, 230], n / 6)), true);
    dome(sc, k([0, 0, 64]), k(9));
    sc.lueur([150, 90, 220], 5, 1.2);
    traits(sc, c, devant, 12, k(12), k(3.6), 52);
    legende(sc, ["ELLE PART TOUT DROIT ET EXPLOSE EN DOME", "EN 45 S DE FORME : 4 TIRS AU PLUS"]);
  }],
  ["ACCROUPI : IL LEVE SES 8 QUEUES", { lacet: -36, tangage: 6, echelle: 9 / K, centre: [0, 0, 0], pied: 396 }, (sc) => {
    sol(sc, zone([-22, 22]), zone([-20, 22]));
    hachibi(sc, { G, queues: "levees", gueule: 0.6, tete: 4, brasD: "arme", brasG: "arme" });
    legende(sc, ["TOUTES ENSEMBLE, TRES HAUT AU-DESSUS DE LUI", "1 SECONDE POUR VOIR LE COUP ARRIVER"]);
  }],
  ["ACCROUPI : ET IL LES ABAT DEVANT LUI", { lacet: -20, tangage: 14, echelle: 10 / K, centre: k([0, 0, 14]), pied: 380 }, (sc) => {
    const z = k([0, 0, 24]);
    sol(sc, zone([-22, 22]), zone([-18, 36]), fissures(z, 14, k(13), 61));
    hachibi(sc, { G, queues: "abattues", gueule: 1, tete: 0 });
    for (const [x, zz, h] of [[-6, 22, 0.7], [5, 26, 1.2], [9, 19, 0.5], [-2, 31, 1.6], [-10, 27, 1]]) touche(sc, [k(x), 1 + k(h * 2.2), k(zz)], Math.PI + x * 0.1);
    debris(sc, z, 60, k(12), 71, k(6), K);
    anneau(sc, ajoute(z, [0, 0.2, 0]), [0, 1, 0], k(12), k(1.2), 0.5);
    anneau(sc, ajoute(z, [0, 0.2, 0]), [0, 1, 0], k(15), k(0.8), 0.3);
    legende(sc, ["SES 8 QUEUES S'ECRASENT DEVANT LUI EN EVENTAIL", "ZONE DE 30 BLOCS DE LARGE, 60 DE LONG"]);
  }],
  ["IDEE : L'ENCRE (ACCROUPI + CLIC DROIT)", { lacet: -62, tangage: 16, echelle: 11 / K, centre: k([0, 0, 9]), pied: 380 }, (sc) => {
    const flaque = k([0, 0, 17]);
    const encre = (x, z) => {
      const d = Math.hypot((x - flaque[0]) / k(6.5), (z - flaque[2]) / k(5)) + 0.18 * hache(Math.floor(x / 2), Math.floor(z / 2), 7);
      return d < 1 ? (d < 0.85 ? [20, 16, 26] : [46, 38, 54]) : null;
    };
    sol(sc, zone([-20, 20]), zone([-16, 28]), encre);
    const { gueule, devant } = hachibi(sc, { G, gueule: 1, tete: 10 });
    // le jet : des paquets noirs qui partent de la gueule et retombent sur la flaque
    for (let n = 0; n < 70; n++) {
      const t = n / 69;
      const p = ajoute(ajoute(gueule, fois(devant, k(1.5 + t * 9.5))), [Math.sin(n * 1.7) * k(0.5) * t, -t * t * k(2.6), 0]);
      cubeSimple(sc, p, k(1.8 - t * 0.5 + 0.4 * hache(n, 1, 81)), n % 3 ? [24, 18, 30] : [58, 46, 70]);
    }
    for (let q = 0; q < 30; q++) cubeSimple(sc, [flaque[0] + (hache(q, 1, 82) - 0.5) * k(11), k(0.4 + 2.5 * hache(q, 2, 82)), flaque[2] + (hache(q, 3, 82) - 0.5) * k(8)], k(0.3 + 0.4 * hache(q, 4, 82)), [24, 18, 30]);
    for (const [x, z, a] of [[3, 19, 0.5], [-4, 17, -0.3], [6, 15, 0.9]]) joueur(sc, k([x, 0, z]), Math.PI + a, { c: [20, 16, 26], k: 0.55 });
    legende(sc, ["IL CRACHE DE L'ENCRE COMME KILLER BEE", "DEDANS : AVEUGLE + RALENTI 4 S, 3 DEGATS"]);
  }],
];

// ------------------------------------------------------------------ le résumé (les touches et les chiffres à régler)
const RESUME = [
  ["HACHIBI 4", [180, 60, 90]],
  ["= TOUT HACHIBI 2 (EVEIL, 4 FORMES)", null],
  ["  + UN ULTI : LE HACHIBI ENTIER", null],
  ["", null],
  ["TOUCHE ULTI : J", [180, 60, 90]],
  ["  LIBRE, CHANGEABLE DANS LES TOUCHES", null],
  ["  TAILLE : CORPS 30, QUEUES 51 BLOCS", null],
  ["  45 S DANS LA FORME, PUIS REPOS", null],
  ["", null],
  ["A REGLER AVEC L'ULTI DU SUSANO :", [180, 60, 90]],
  ["CLIC GAUCHE  POINGS        12", null],
  ["  0,5 S, ONDE DE 8 BLOCS", null],
  ["CLIC DROIT   BIJUU DAMA    30 +12", null],
  ["  RECHARGE 10 S", null],
  ["ACCROUPI     8 QUEUES      22", null],
  ["  RECHARGE 12 S", null],
  ["IDEE  ACCROUPI + CLIC DROIT", null],
  ["  ENCRE, AVEUGLE 4 S", null],
];

function resume(sc) {
  for (let i = 0; i < sc.w * sc.h; i++) sc.img.set([250, 246, 248, 255], i * 4);
  RESUME.forEach(([t, c], k) => ecrireTexte(sc.img, sc.w, t, 22, 20 + k * 24, c ? 3 : 2, [...(c ?? [60, 52, 64]), 255]));
}

// les briques servent aussi à la transformation (transformation.mjs)
export { G, K, sol, debris, anneau, traits, legende, ecrire, FOND, HERBE, TERRE };

// ------------------------------------------------------------------ la planche
const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  const CW = 520, CH = 470, M = 40, HAUT = 64, COL = 3;
  const cases = [...VUES, ["LES TOUCHES", null, resume]];
  const lignes = Math.ceil(cases.length / COL);
  const W = CW * COL, H = HAUT + (M + CH) * lignes;
  const planche = Buffer.alloc(W * H * 4, 255);
  ecrireTexte(planche, W, "HACHIBI 4 : L'ULTI, LE HACHIBI ENTIER", 18, 16, 5, [150, 40, 70, 255]);
  const seule = process.argv[2] ? Number(process.argv[2]) : null;
  cases.forEach(([titre, o, dessin], k) => {
    if (seule !== null && seule !== k) return;
    const c = k % COL, l = Math.floor(k / COL);
    // node rendu.mjs <n> '{"lacet":-50}' : refait seulement la vue n, avec une autre caméra (pour chercher le bon angle)
    const sc = scene({ w: CW, h: CH, fond: FOND, ...(o ?? {}), ...(seule === k && process.argv[3] ? JSON.parse(process.argv[3]) : {}) });
    dessin(sc);
    for (let y = 0; y < CH; y++) sc.img.copy(planche, ((HAUT + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(planche, W, titre, c * CW + 12, HAUT + l * (M + CH) + 10, 3, k % 2 ? [40, 90, 170, 255] : [190, 70, 40, 255]);
    console.log("vue", k, titre);
  });
  ecrirePng(path.join(ICI, "..", "planche-hachibi4.png"), W, H, planche);
  console.log("ok", W, H);
}
