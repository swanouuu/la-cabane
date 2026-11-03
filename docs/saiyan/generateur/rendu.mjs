// Maquettes des accessoires Saiyan (seulement des visuels pour l'instant) : la queue, qui se balance toute seule, et le
// détecteur, comme celui de Vegeta, sur l'œil gauche. Les deux vont dans un slot accessoire (Curios, clic droit pour
// l'équiper), pas à la place du casque.
// node rendu.mjs → ../planche-saiyan.png, ../queue-qui-bouge.gif (ffmpeg de .outils) et les deux icônes 64×64
// node rendu.mjs essai 0,5 sortie.png : quelques vignettes en grand, pour les régler
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, scene, ecrireTexte, clair, mix, hache, norme, croix, PARTIES, texels } from "../../fruits/generateur/moteur.mjs";
import { ajoute, sous, fois, TOUR, ID, brille, pave, perso, sol, legende, FACES, FOND, STEVE } from "../../fruits/generateur/rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const RACINE = path.join(DOC, "../..");
const PNJ = path.join(RACINE, "mod-cabane/src/main/resources/assets/cabane/textures/entity/pnj");
const GOKU = lire(path.join(PNJ, "goku_base.png"));
const VEGETA = lire(path.join(PNJ, "vegeta.png"));

// le rouge brun de l'image (mesuré dessus), avec un poil plus foncé et des reflets
const POIL = [124, 20, 16];
const POIL_F = [70, 10, 10];
const POIL_C = [176, 54, 38];
// le détecteur : coque blanche, bras gris bleuté, cadre sombre, et le verre (et sa lumière) dans une des couleurs
const COQUE = [238, 238, 234];
const COQUE_F = [196, 198, 204];
const BRAS = [172, 180, 196];
const SOMBRE = [52, 54, 64];
const COULEURS = {
  rouge: [236, 52, 78], vert: [60, 224, 110], bleu: [60, 130, 255], violet: [170, 80, 240],
  jaune: [255, 214, 40], orange: [255, 140, 30], rose: [255, 110, 200], cyan: [40, 220, 230],
};
/** Le verre, les petits traits dessus (plus clairs) et la lumière de l'oreillette, pour une couleur. */
const teinte = (nom) => ({ nom, verre: COULEURS[nom], trait: mix(COULEURS[nom], [255, 255, 255], 0.62), led: COULEURS[nom] });
const DIAMANT = [90, 214, 214];

// ------------------------------------------------------------------ la queue
const N = 20, PAS_Q = 1.2;

/** Une valeur qui suit des repères [s, v] (s de 0 à 1 le long de la queue), en ligne droite entre eux. */
function cles(l, s) {
  for (let k = 1; k < l.length; k++) {
    if (s <= l[k][0]) { const [s0, v0] = l[k - 1], [s1, v1] = l[k]; return v0 + ((v1 - v0) * (s - s0)) / (s1 - s0); }
  }
  return l.at(-1)[1];
}

/**
 * Les poses (angles en degrés) : theta monte (négatif : vers le bas), phi part vers la gauche du personnage. Une onde
 * passe de la base au bout (la base bouge peu, le bout beaucoup) : c'est ce qui la fait bouger « naturellement ».
 */
const POSES = {
  repos: { theta: [[0, -15], [0.18, -48], [0.4, -12], [0.65, 35], [0.88, 85], [1, 120]], phi: [[0, 0], [0.5, -10], [1, -34]], ampl: 0.34, onde: 2.4, periode: 2 },
  // pour l'icône : immobile, de profil, le bout bien enroulé
  icone: { theta: [[0, -40], [0.3, -95], [0.55, -20], [0.75, 60], [0.9, 140], [1, 215]], phi: [[0, 0], [1, 0]], ampl: 0, onde: 0, periode: 1 },
  course: { theta: [[0, -5], [0.35, -8], [0.7, 5], [1, 30]], phi: [[0, 0], [1, -6]], ampl: 0.3, onde: 3.2, periode: 0.5 },
};

/** Les points de la queue (coordonnées du corps au repos), au temps t (s), avec leur épaisseur. */
function chaine(pose, t, base = [0, 13, -1.6]) {
  const P = POSES[pose], w = (TOUR * t) / P.periode;
  const pts = [{ p: base, w: 1.8 }];
  let p = base;
  for (let k = 0; k < N; k++) {
    const s = k / (N - 1);
    const th = (cles(P.theta, s) * Math.PI) / 180 + 0.13 * s * Math.sin(w - 3 * s + 1);
    const ph = (cles(P.phi, s) * Math.PI) / 180 + P.ampl * (0.25 + 0.9 * s) * Math.sin(w - P.onde * s);
    const d = [Math.cos(th) * Math.sin(ph), Math.sin(th), -Math.cos(th) * Math.cos(ph)];
    p = ajoute(p, fois(d, PAS_Q));
    pts.push({ p, w: s < 0.8 ? 1.8 - 0.45 * s : 1.44 - 3 * (s - 0.8) });
  }
  return pts;
}

/** Enroulée autour de la taille (l'option accroupi) : un tour par la gauche, qui monte un peu, le bout par-dessus la base. */
function ceinture() {
  const rx = 5.1, rz = 3.1, pts = [{ p: [0, 13, -1.6], w: 1.6 }];
  for (let k = 0; k <= N; k++) {
    const b = (k / N) * TOUR * 1.04;
    pts.push({ p: [rx * Math.sin(b), 13.1 + 1.5 * (k / N), -rz * Math.cos(b)], w: 1.6 - 0.3 * (k / N) });
  }
  return pts;
}

/** Le poil, case par case (4 cases par pixel) : des brins clairs et foncés dans le sens de la queue, et du grain. */
function poil(k, f, i, j) {
  const [le, tr] = f === "droite" || f === "gauche" ? [i, j] : [j, i];
  const decal = Math.floor(hache(tr, FACES[f], 912) * 6);
  const brin = hache(tr + FACES[f] * 17, Math.floor((le + k * 5 + decal) / 6), 910);
  const grain = hache(tr * 7 + FACES[f], le + k * 5, 911);
  const c = brin > 0.66 ? mix(POIL, POIL_C, (brin - 0.66) * 1.5) : brin < 0.28 ? mix(POIL, POIL_F, (0.28 - brin) * 2) : POIL;
  return mix(c, POIL_F, grain * 0.12);
}

/** Le duvet : une couche un peu plus grosse, percée (comme la deuxième couche d'un skin), qui rend le bord poilu. */
function duvet(k, f, i, j, bout) {
  if (f === "dos" || (f === "devant" && !bout)) return null;
  const [le, tr] = f === "droite" || f === "gauche" ? [i, j] : [j, i];
  // des mèches : quelques cases à la suite dans le sens de la queue, décalées d'une colonne à l'autre
  const decal = Math.floor(hache(tr, FACES[f], 922) * 4);
  const h = hache(tr + FACES[f] * 13, Math.floor((le + k * 5 + decal) / 4), 920);
  if (h > (bout ? 0.75 : 0.3)) return null;
  return h < 0.1 ? mix(POIL, POIL_F, 0.4) : mix(POIL, POIL_C, 0.25 + 0.3 * hache(tr, le, 921));
}

/** La queue, des points (coordonnées du corps) passés par Tc (la transformation du corps du joueur). */
function queue(sc, pts, Tc = ID) {
  const P = pts.map((q) => Tc.p(q.p));
  let e1 = norme(sous(Tc.p([1, 13, 0]), Tc.p([0, 13, 0])));
  const n = P.length - 1;
  for (let k = 0; k < n; k++) {
    const A = P[k], d = norme(sous(P[k + 1], A)), L = Math.hypot(...sous(P[k + 1], A)), w = pts[k].w;
    // le repère suit la queue sans tourner sur lui-même (sinon les pavés vrillent d'un bout à l'autre)
    e1 = norme(sous(e1, fois(d, e1[0] * d[0] + e1[1] * d[1] + e1[2] * d[2])));
    const e2 = croix(d, e1);
    const lin = (v) => ajoute(ajoute(fois(e1, v[0]), fois(e2, v[1])), fois(d, v[2]));
    const T = { p: (v) => ajoute(A, lin(v)), n: (v) => norme(lin(v)) };
    const bout = k >= n - 3, ov = w * 0.4, g = bout ? 0.45 : 0.22;
    pave(sc, [-w / 2, -w / 2, -ov], [w / 2, w / 2, L + ov], T, (f, u, v, i, j) => poil(k, f, i, j), 0.25);
    pave(sc, [-w / 2 - g, -w / 2 - g, -ov + 0.1], [w / 2 + g, w / 2 + g, L + ov - 0.1 + (k === n - 1 ? g : 0)], T,
      (f, u, v, i, j) => duvet(k, f, i, j, k === n - 1), 0.25);
  }
}

/** Un joueur et sa queue (pose, temps, ou des points tout faits). */
function saiyan(sc, peau, o = {}, q = {}) {
  const T = perso(sc, peau, o);
  const base = o.armure ? [0, 13, -2.4] : [0, 13, -1.6];
  queue(sc, q.points ?? chaine(q.pose ?? "repos", q.t ?? 0, base), T.corps);
  return T;
}

// ------------------------------------------------------------------ le détecteur
/** Une case de coque : blanche, les bords un peu plus gris (un petit biseau). */
const coque = (base = COQUE) => (f, u, v) => (Math.min(u, v, 1 - u, 1 - v) < 0.12 ? COQUE_F : base);

/**
 * Le détecteur sur la tête (transformation de la tête Tt, coordonnées du joueur au repos), sur l'œil gauche (+x).
 * g pousse tout d'un pixel quand il porte un casque. Rend une fonction qui pose le verre : à appeler en dernier (le verre
 * est transparent, il teinte ce qui est derrière).
 */
function detecteur(sc, Tt, o = {}) {
  const g = o.g ?? 0, c = teinte(o.couleur ?? "rouge"), x = 4 + g, zv = 4.72 + g;
  // l'oreillette : un pavé blanc avec un haut-parleur rond sur le côté
  pave(sc, [x, 25.6, -1.8], [x + 1.5, 29.6, 1.6], Tt, (f, u, v) => {
    if (f !== "gauche") return coque()(f, u, v);
    const r = Math.hypot((u - 0.52) * 3.4, (v - 0.5) * 4);
    if (r < 0.5) return [86, 90, 102];
    if (r < 0.72) return [150, 156, 168];
    if (r < 1.22) return r > 1.0 && r < 1.1 ? [96, 100, 112] : SOMBRE;
    if (r < 1.38) return COQUE_F;
    return coque()(f, u, v);
  }, 0.2);
  pave(sc, [x + 0.15, 29.6, -1.4], [x + 1.35, 29.95, 1.2], Tt, coque(), 0.2);
  pave(sc, [x + 0.15, 25.25, -1.4], [x + 1.35, 25.6, 1.2], Tt, coque(), 0.2);
  // le bouton dessus, la petite lumière sur le côté, la fente devant
  pave(sc, [x + 0.45, 29.95, 0.1], [x + 1.05, 30.35, 0.9], Tt, () => SOMBRE, 0.2);
  if (o.led !== false) pave(sc, [x + 1.5, 28.55, 0.85], [x + 1.62, 28.95, 1.25], Tt, () => brille(c.led), 0.2);
  pave(sc, [x + 0.3, 26.2, 1.6], [x + 1.2, 26.5, 1.64], Tt, () => SOMBRE, 0.3);
  // le bras qui part vers l'avant, le long de la tempe
  pave(sc, [x + 0.2, 28.3, 1.6], [x + 0.9, 29.0, zv - 0.1], Tt, (f, u, v) => (f === "bas" ? clair(BRAS, 0.8) : v > 0.85 && f !== "haut" ? clair(BRAS, 0.86) : BRAS), 0.2);
  // le cadre : une barre en haut du verre et une sur le côté
  pave(sc, [0.2, 29.0, zv - 0.25], [x + 0.9, 29.42, zv + 0.22], Tt, () => SOMBRE, 0.2);
  pave(sc, [x + 0.45, 26.1, zv - 0.25], [x + 0.9, 29.42, zv + 0.22], Tt, () => SOMBRE, 0.2);
  // des petits traits sur le verre (une mire et une ligne de chiffres), un poil devant lui
  const z = zv + 0.03, trait = (x0, y0, x1, y1) => pave(sc, [x0, y0, z - 0.02], [x1, y1, z], Tt, () => brille(c.trait), 0.5);
  if (o.t !== undefined) {
    // comme en jeu : une mire qui se promène, des chiffres qui changent, une barre, une ligne qui balaie
    const t = o.t, cx = 2.3 + 0.8 * Math.sin(0.9 * t), cy = 27.75 + 0.35 * Math.sin(1.3 * t + 1);
    trait(cx - 0.55, cy + 0.43, cx - 0.15, cy + 0.55); trait(cx - 0.55, cy + 0.15, cx - 0.43, cy + 0.55);
    trait(cx + 0.15, cy - 0.55, cx + 0.55, cy - 0.43); trait(cx + 0.43, cy - 0.55, cx + 0.55, cy - 0.15);
    for (let i = 0; i < 4; i++) if (hache(Math.floor(t * 4), i, 960) > 0.3) trait(0.6 + 0.45 * i, 26.4, 0.9 + 0.45 * i, 26.62);
    trait(2.6, 26.45, Math.min(2.6 + 0.5 + 1.2 * hache(Math.floor(t * 2.5), 7, 961), x + 0.3), 26.57);
    const yb = 28.95 - 2.8 * ((t / 1.6) % 1);
    trait(0.25, yb - 0.04, x + 0.4, yb + 0.04);
  } else if (o.traits !== false) {
    trait(1.4, 28.45, 2.2, 28.6); trait(1.4, 27.85, 1.55, 28.6);
    trait(0.7, 26.55, 1.0, 26.75); trait(1.2, 26.55, 1.5, 26.75); trait(1.7, 26.55, 2.0, 26.75); trait(2.6, 26.55, 3.9, 26.68);
  }
  return () => sc.voile([[0.2, 29.0, zv], [x + 0.45, 29.0, zv], [x + 0.45, 26.1, zv], [0.2, 26.1, zv]].map(Tt.p), c.verre, 0.44);
}

// ------------------------------------------------------------------ les vignettes
function queueDeDos(sc) {
  saiyan(sc, GOKU, { brasD: [0, -0.06], brasG: [0, 0.06] }, { t: 0.2 });
  legende(sc, "ELLE SORT DU BAS DU DOS, PAR-DESSUS LES HABITS");
}

function queueDeProfil(sc) {
  saiyan(sc, GOKU, { brasD: [0.1, -0.04], brasG: [-0.1, 0.04] }, { t: 0.6 });
  legende(sc, "LONGUE, EN POINT D'INTERROGATION");
}

function queueDePres(sc) {
  saiyan(sc, GOKU, {}, { t: 0.3 });
  legende(sc, "4 CASES PAR PIXEL, LE BOUT PLUS TOUFFU");
}

function balance(sc) {
  [[0, -24], [0.5, 0], [1, 24]].forEach(([t, x]) => saiyan(sc, GOKU, { decale: [x, 0, 0] }, { t }));
  legende(sc, "L'ONDE PART DE LA BASE ET FINIT AU BOUT");
}

/** La pose de course, à la phase a (en tours). */
const COURSE = (a, o = {}) => {
  const s = Math.sin(TOUR * a);
  return { jambeD: 0.75 * s, jambeG: -0.75 * s, brasD: [-0.65 * s, -0.05], brasG: [0.65 * s, 0.05], buste: 0.12, tete: [-0.1], ...o };
};

function enCourant(sc, t = 0.12, texte = "ELLE SUIT DERRIERE ET ONDULE PLUS VITE") {
  sol(sc, -40, -48, 40, 40, null, 0);
  const a = (t / POSES.course.periode) % 1, saut = 0.6 * Math.abs(Math.sin(TOUR * a));
  saiyan(sc, GOKU, COURSE(a, { decale: [0, saut, 0] }), { pose: "course", t });
  for (let k = 0; k < 5; k++) pave(sc, [-6 + 3 * k, 6 + 4 * hache(k, 1, 940), -26 - 10 * hache(k, 2, 940)], [-5.7 + 3 * k, 6.3 + 4 * hache(k, 1, 940), -18], ID, () => brille([250, 250, 255]), 1);
  legende(sc, texte);
}

function enroulee(sc) {
  saiyan(sc, GOKU, { buste: 0.32, tete: [-0.3], brasD: [0.2, -0.1], brasG: [0.2, 0.1], decale: [0, -1, 0] }, { points: ceinture() });
  legende(sc, "COMME VEGETA : UN TOUR DE TAILLE");
}

function avecArmure(sc) {
  saiyan(sc, GOKU, { armure: DIAMANT, brasD: [0, -0.06], brasG: [0, 0.06] }, { t: 0.9 });
  legende(sc, "ELLE PASSE PAR-DESSUS L'ARMURE");
}

function detFace(sc) {
  const T = perso(sc, VEGETA, { tete: [0.04, 0, -0.12] });
  detecteur(sc, T.tete, { couleur: "vert" })();
  legende(sc, "SUR L'OEIL GAUCHE, COMME VEGETA");
}

function detProfil(sc) {
  const T = perso(sc, VEGETA, {});
  detecteur(sc, T.tete, { couleur: "orange" })();
  legende(sc, "L'OREILLETTE BLANCHE ET SA LUMIERE");
}

function detPres(sc) {
  const T = perso(sc, VEGETA, {});
  detecteur(sc, T.tete, { couleur: "bleu" })();
  legende(sc, "VERRE TRANSPARENT : L'OEIL SE VOIT");
}

function detCasque(sc) {
  const T = perso(sc, VEGETA, { armure: DIAMANT, tete: [0.04, 0, -0.12] });
  detecteur(sc, T.tete, { g: 1, couleur: "violet" })();
  legende(sc, "SLOT ACCESSOIRE : TU GARDES TON CASQUE");
}

/** Une tête seule (les deux couches de la peau), décalée de d : pour le nuancier des couleurs. */
function teteSeule(sc, peau, d) {
  const T = { p: (v) => ajoute(v, d), n: (v) => v };
  const [, boite, uv1, uv2, gonfle] = PARTIES(false)[0];
  for (const [uv, g, dz] of [[uv1, 0, 0], [uv2, gonfle, 0.02]]) for (const t of texels(boite, uv, g, 1)) {
    const i = (t.ty * 64 + t.tx) * 4;
    if (peau.px[i + 3] < 128) continue;
    const l = sc.lumiere(t.N, false);
    if (l < 0) continue;
    sc.quad(t.coins.map(T.p), clair([peau.px[i], peau.px[i + 1], peau.px[i + 2]], l), dz);
  }
  return T;
}

/** Les 8 couleurs côte à côte, sur des têtes de Vegeta, avec leur nom dessous. */
function couleurs(sc) {
  const verres = Object.keys(COULEURS).map((nom, k) => {
    // alignées face à l'écran (la vue est tournée de 32°), pour que les rangées restent droites
    const x = -16.5 + 11 * (k % 4), d = [x, k < 4 ? 9 : -9, x * Math.tan((-32 * Math.PI) / 180)];
    return [nom, d, detecteur(sc, teteSeule(sc, VEGETA, d), { couleur: nom })];
  });
  for (const [nom, d, verre] of verres) {
    verre();
    const [x, y] = sc.vue(ajoute(d, [0, 22.6, 0]));
    ecrireTexte(sc.img, sc.w, nom, Math.round(x - nom.length * 4), Math.round(y), 2, [70, 64, 70, 255]);
  }
  legende(sc, "UN OBJET PAR COULEUR");
}

function ensemble(sc) {
  const T = saiyan(sc, VEGETA, { brasD: [0.05, -0.08], brasG: [-0.05, 0.08], tete: [0, 0, -0.2] }, { t: 1.3 });
  detecteur(sc, T.tete, { couleur: "cyan" })();
  legende(sc, "LA QUEUE ET LE DETECTEUR, SUR VEGETA");
}

function ensembleDos(sc) {
  const T = saiyan(sc, VEGETA, { tete: [0, 0, 0.35] }, { t: 0.7 });
  detecteur(sc, T.tete, { couleur: "rose" })();
  legende(sc, "DE DOS : L'OREILLETTE DEPASSE");
}

/** En première personne : le paysage, Steve au loin, et le verre rouge dans le coin gauche de l'écran (l'œil gauche). */
/** Le verre de près à trois moments : ce qu'il affiche bouge tout seul. */
function verreAnime(sc) {
  const verres = [0, 0.6, 1.3].map((t, k) => {
    const d = [0, 11 - 11 * k, 0];
    return [t, d, detecteur(sc, teteSeule(sc, VEGETA, d), { couleur: "rouge", t })];
  });
  for (const [t, d, verre] of verres) {
    verre();
    const [x, y] = sc.vue(ajoute(d, [-7, 28, 0]));
    ecrireTexte(sc.img, sc.w, `${t} S`, Math.round(x - 30), Math.round(y - 5), 2, [70, 64, 70, 255]);
  }
  legende(sc, "LE VERRE AFFICHE DES TRUCS QUI BOUGENT");
}

function premierePersonne(sc) {
  sol(sc, -700, -700, 700, 420, null, 0);
  for (const [x, z] of [[-150, -420], [90, -520], [190, -260], [-230, -160], [260, -600]]) {
    pave(sc, [x - 2, 0, z - 2], [x + 2, 40, z + 2], ID, () => [110, 80, 50], 4);
    pave(sc, [x - 14, 30, z - 14], [x + 14, 58, z + 14], ID, (f, u, v, i, j) => clair([62, 132, 52], 0.9 + 0.2 * hache(i, j, 950)), 4);
  }
  perso(sc, STEVE, { decale: [14, 0, -300] });
  const { img, w } = sc, x1 = 200, y0 = 44, y1 = 318, ROUGE = teinte("rouge");
  const plein = (a, b, c, d, col) => { for (let y = b; y < d; y++) for (let x = a; x < c; x++) img.set([...col, 255], (y * w + x) * 4); };
  for (let y = y0; y < y1; y++) for (let x = 0; x < x1; x++) {
    // le coin en bas à droite est arrondi
    const dx = x - (x1 - 30), dy = y - (y1 - 30);
    if (dx > 0 && dy > 0 && dx * dx + dy * dy > 900) continue;
    const bord = x1 - x < 3 || (dx > 0 && dy > 0 && dx * dx + dy * dy > 27 * 27);
    const i = (y * w + x) * 4, a = bord ? 0.55 : y % 3 === 0 ? 0.2 : 0.26;
    for (let k = 0; k < 3; k++) img[i + k] = Math.round(img[i + k] * (1 - a) + (bord ? ROUGE.trait : ROUGE.verre)[k] * a);
  }
  plein(0, y0 - 9, x1 + 4, y0, SOMBRE);
  plein(0, y0 - 26, 70, y0 - 9, BRAS);
  // des petits traits sur le verre, comme sur le vrai
  plein(120, 120, 150, 123, ROUGE.trait); plein(120, 120, 123, 146, ROUGE.trait);
  for (let k = 0; k < 4; k++) plein(22 + 18 * k, 290, 34 + 18 * k, 294, ROUGE.trait);
  legende(sc, "OPTION : UN LEGER VOILE DE SA COULEUR");
}

// ------------------------------------------------------------------ les icônes (64×64)
/**
 * Rend dessin(sc) en grand sur un fond magenta, recadre sur l'objet et le réduit en 64×64 (4×4 points par pixel). Le
 * fond est reconstruit et retiré ; verre (une couleur) : dessin rend la fonction du verre, qui devient semi-transparent.
 */
function icone(dessin, o, verre = null) {
  const S = 1024, K = 4, fond = [255, 0, 255];
  const passe = (avecVerre) => {
    const sc = scene({ w: S, h: S, fond, pied: S / 2, ...o });
    const v = dessin(sc);
    if (avecVerre && v) v();
    return sc.img;
  };
  const sans = passe(false), avec = verre ? passe(true) : sans;
  const estFond = (img, x, y) => {
    const k = y / S, i = (y * S + x) * 4;
    return fond.every((c, n) => Math.abs(img[i + n] - Math.round(c * (1 - k * 0.25))) <= 1);
  };
  let x0 = S, y0 = S, x1 = 0, y1 = 0;
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    if (!estFond(avec, x, y)) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
  }
  const cote = Math.max(x1 - x0, y1 - y0) * 1.06, cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
  const out = Buffer.alloc(64 * 64 * 4);
  for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) {
    let n = 0, nv = 0;
    const s = [0, 0, 0];
    for (let dy = 0; dy < K; dy++) for (let dx = 0; dx < K; dx++) {
      const X = Math.floor(cx - cote / 2 + ((x + (dx + 0.5) / K) * cote) / 64), Y = Math.floor(cy - cote / 2 + ((y + (dy + 0.5) / K) * cote) / 64);
      if (X < 0 || Y < 0 || X >= S || Y >= S) continue;
      const i = (Y * S + X) * 4;
      if (!estFond(sans, X, Y)) { n++; for (let c = 0; c < 3; c++) s[c] += avec[i + c]; }
      else if (verre && !estFond(avec, X, Y)) nv++;
    }
    const i = (y * 64 + x) * 4;
    if (n >= (K * K) / 2) out.set([...s.map((v) => Math.round(v / n)), 255], i);
    else if (nv + n >= (K * K) / 2) out.set([...verre, 150], i);
  }
  return out;
}

const ICONE_QUEUE = () => icone((sc) => queue(sc, chaine("icone", 0)), { lacet: -78, tangage: 8, echelle: 30, centre: [0, 6, -8] });
const ICONE_DETECTEUR = (nom) => icone((sc) => detecteur(sc, ID, { couleur: nom }), { lacet: -48, tangage: 14, echelle: 70, centre: [2.6, 27.8, 2] }, COULEURS[nom]);

/** La vignette des objets : la queue et un détecteur en grand, puis tous à leur vraie taille dans des cases d'inventaire. */
function objets(sc) {
  const { img, w } = sc;
  const case_ = (x0, y0, t) => {
    for (let y = y0; y < y0 + t; y++) for (let x = x0; x < x0 + t; x++) {
      const bord = x < x0 + 4 || y < y0 + 4 ? [55, 55, 55] : x >= x0 + t - 4 || y >= y0 + t - 4 ? [255, 255, 255] : [139, 139, 139];
      img.set([...bord, 255], (y * w + x) * 4);
    }
  };
  const colle = (ic, x0, y0, k) => {
    for (let y = 0; y < 64 * k; y++) for (let x = 0; x < 64 * k; x++) {
      const i = (Math.floor(y / k) * 64 + Math.floor(x / k)) * 4, a = ic[i + 3] / 255, j = ((y0 + y) * w + x0 + x) * 4;
      for (let c = 0; c < 3; c++) img[j + c] = Math.round(img[j + c] * (1 - a) + ic[i + c] * a);
    }
  };
  const q = ICONE_QUEUE(), d = Object.fromEntries(Object.keys(COULEURS).map((nom) => [nom, ICONE_DETECTEUR(nom)]));
  colle(q, 16, 30, 3); colle(d.vert, 232, 30, 3);
  ecrireTexte(img, w, "QUEUE DE SAIYAN", 112 - 15 * 4, 232, 2, [70, 64, 70, 255]);
  ecrireTexte(img, w, "DETECTEUR", 328 - 9 * 4, 232, 2, [70, 64, 70, 255]);
  [q, ...Object.values(d)].forEach((ic, k) => {
    const x = 24 + 80 * (k < 5 ? k : k - 5) + (k < 5 ? 0 : 40), y = k < 5 ? 266 : 346;
    case_(x, y, 72); colle(ic, x + 4, y + 4, 1);
  });
  ecrireTexte(img, w, "TAILLE REELLE (64 X 64)", 128, 432, 2, [70, 64, 70, 255]);
  legende(sc, "CLIC DROIT : DANS TON SLOT ACCESSOIRE");
  return { q, d };
}

// ------------------------------------------------------------------ la planche
const CW = 440, CH = 520, M = 34, HAUT = 600;
const TITRE = [30, 30, 40, 255], BRUN = [150, 40, 30, 255], BLEU = [40, 60, 108, 255];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

function entete(img, W) {
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, "ACCESSOIRES SAIYAN : LA QUEUE ET LE DETECTEUR", 30, 22, 5, TITRE);
  const colonnes = [
    [30, BRUN, "LA QUEUE DE SAIYAN", [
      "CLIC DROIT AVEC : ELLE VA DANS TON SLOT ACCESSOIRE",
      "ELLE SORT DU BAS DU DOS, PAR-DESSUS HABITS ET ARMURE",
      "FINE, ROUGE BRUN ET POILUE COMME TON IMAGE, BOUT TOUFFU",
      "AU REPOS : ELLE SE BALANCE DOUCEMENT, LE BOUT BOUGE PLUS",
      "EN MARCHANT ET EN COURANT : ELLE SUIT DERRIERE ET ONDULE",
      "OPTION : ACCROUPI, ELLE S'ENROULE AUTOUR DE LA TAILLE",
      "L'ANIMATION : QUEUE-QUI-BOUGE.GIF A COTE DE LA PLANCHE",
    ]],
    [900, BLEU, "LE DETECTEUR", [
      "CLIC DROIT AVEC : IL VA DANS LE SLOT ACCESSOIRE DE TETE",
      "    DONC TU GARDES TON CASQUE EN MEME TEMPS",
      "SUR L'OEIL GAUCHE, COMME CELUI DE VEGETA",
      "OREILLETTE BLANCHE, BRAS GRIS, VERRE TRANSPARENT",
      "8 COULEURS : ROUGE, VERT, BLEU, VIOLET, JAUNE, ORANGE,",
      "    ROSE ET CYAN (UN OBJET PAR COULEUR)",
      "LA PETITE LUMIERE DE L'OREILLETTE A LA MEME COULEUR",
      "LE VERRE AFFICHE DES PETITS TRUCS QUI BOUGENT",
      "RIEN SUR TON ECRAN : JUSTE UN ACCESSOIRE",
    ]],
  ];
  for (const [x, c, titre, lignes] of colonnes) {
    ecrireTexte(img, W, titre, x, 100, 4, c);
    lignes.forEach((t, k) => ecrireTexte(img, W, t, x, 160 + k * 34, 3, TITRE));
  }
  rect(img, W, 30, 470, W - 30, 473, [200, 200, 210, 255]);
  ecrireTexte(img, W, "POUR L'INSTANT CE N'EST QUE DU STYLE : AUCUNE PROTECTION, AUCUN POUVOIR, ON PEUT LES PORTER AVEC TOUT", 30, 492, 3, TITRE);
  ecrireTexte(img, W, "LES DEUX SONT FAITS DE PETITS CUBES COMME UN VRAI MODELE MINECRAFT : CE QUE TU VOIS ICI SE FAIT EN JEU", 30, 526, 3, TITRE);
  ecrireTexte(img, W, "OU LES AVOIR : EN RECOMPENSE DU COMBAT GOKU CONTRE VEGETA, GAGNE OU PERDU (LA QUEUE + UN DETECTEUR D'UNE COULEUR AU HASARD)", 30, 560, 3, BRUN);
}

const PIED = { echelle: 8.6, pied: 470 };
const TETE = { tangage: 6, pied: 300 };
const vues = [
  ["LA QUEUE DE DOS", queueDeDos, { ...PIED, lacet: 212, centre: [0, 0, -6] }],
  ["DE PROFIL", queueDeProfil, { ...PIED, lacet: -90, centre: [0, 0, -6] }],
  ["DE PRES : LE POIL", queueDePres, { lacet: 236, tangage: 8, echelle: 16, pied: 300, centre: [-1, 9, -8] }],
  ["ELLE SE BALANCE : 0 S, 0.5 S, 1 S", balance, { lacet: 200, tangage: 8, echelle: 5.6, pied: 400, centre: [0, 6, -6] }],
  ["EN COURANT", enCourant, { lacet: -78, tangage: 12, echelle: 6.6, pied: 400, centre: [0, 6, -8] }],
  ["OPTION : ACCROUPI, ENROULEE", enroulee, { lacet: -36, tangage: 22, echelle: 11, pied: 330, centre: [0, 12, 0] }],
  ["AVEC UNE ARMURE", avecArmure, { ...PIED, lacet: 212, centre: [0, 0, -6] }],
  ["LES OBJETS", objets, { echelle: 1, pied: 0 }],
  ["LE DETECTEUR", detFace, { ...TETE, lacet: -32, echelle: 17, centre: [0.5, 26, 0] }],
  ["DE PROFIL : L'OREILLETTE", detProfil, { ...TETE, lacet: -90, echelle: 20, centre: [0, 27, 0.5] }],
  ["DE PRES : LE VERRE", detPres, { tangage: 4, lacet: -22, echelle: 38, pied: 270, centre: [2.4, 27.7, 4] }],
  ["AVEC UN CASQUE", detCasque, { ...TETE, lacet: -32, echelle: 15, centre: [0.5, 26, 0] }],
  ["LE VERRE BOUGE", verreAnime, { lacet: -24, tangage: 4, echelle: 15, pied: 240, centre: [-0.5, 28, 2] }],
  ["PLEIN DE COULEURS", couleurs, { lacet: -32, tangage: 6, echelle: 7.6, pied: 250, centre: [1.2, 28, 0] }],
  ["LES DEUX ENSEMBLE", ensemble, { ...PIED, lacet: -62, centre: [0, 0, -4] }],
  ["DE DOS : LES DEUX", ensembleDos, { ...PIED, lacet: 222, centre: [0, 0, -5] }],
];

// les briques servent aussi aux textures du mod (textures.mjs)
export { ICONE_QUEUE, ICONE_DETECTEUR, COULEURS, POIL, POIL_F, POIL_C, COQUE, COQUE_F, SOMBRE };

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

/** Les images de l'animation : la queue au repos (de dos) et en courant (de profil), 2 s en boucle. */
function animation(fichier) {
  const dossier = fs.mkdtempSync(path.join(os.tmpdir(), "saiyan-"));
  const IMG = 36, W = CW * 2;
  for (let n = 0; n < IMG; n++) {
    const t = (n / IMG) * 2, img = Buffer.alloc(W * CH * 4);
    [[(sc) => { saiyan(sc, GOKU, { brasD: [0, -0.06], brasG: [0, 0.06] }, { t }); legende(sc, "AU REPOS"); }, vues[0][2]],
      [(sc) => enCourant(sc, t, "EN COURANT : ELLE SUIT DERRIERE"), vues[4][2]]].forEach(([dessin, o], c) => {
      const sc = scene({ w: CW, h: CH, fond: FOND, ...o });
      dessin(sc);
      for (let y = 0; y < CH; y++) sc.img.copy(img, (y * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    });
    ecrirePng(path.join(dossier, String(n).padStart(3, "0") + ".png"), W, CH, img);
  }
  const ffmpeg = path.join(RACINE, ".outils/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe");
  execFileSync(ffmpeg, ["-y", "-loglevel", "error", "-framerate", "18", "-i", path.join(dossier, "%03d.png"),
    "-vf", "split[a][b];[a]palettegen=stats_mode=full[p];[b][p]paletteuse=dither=sierra2_4a", "-loop", "0", fichier]);
  fs.rmSync(dossier, { recursive: true, force: true });
}

if (PRINCIPAL && process.argv[2] === "essai") {
  const choix = process.argv[3].split(",").map(Number), k2 = 2;
  const W2 = CW * k2 * choix.length, H2 = CH * k2, img2 = Buffer.alloc(W2 * H2 * 4, 255);
  choix.forEach((k, c) => {
    const [, dessin, o] = vues[k];
    const sc = scene({ w: CW * k2, h: CH * k2, fond: FOND, ...o, echelle: (o.echelle ?? 9) * k2, pied: (o.pied ?? CH * 0.88) * k2 });
    dessin(sc);
    for (let y = 0; y < H2; y++) sc.img.copy(img2, (y * W2 + c * CW * k2) * 4, y * CW * k2 * 4, (y + 1) * CW * k2 * 4);
  });
  ecrirePng(process.argv[4], W2, H2, img2);
} else if (PRINCIPAL) {
  const COLONNES = 4, lignes = Math.ceil(vues.length / COLONNES);
  const W = CW * COLONNES, H = HAUT + (M + CH) * lignes;
  const img = Buffer.alloc(W * H * 4, 255);
  entete(img, W);
  let icones = null;
  vues.forEach(([titre, dessin, o], k) => {
    const c = k % COLONNES, l = Math.floor(k / COLONNES);
    const sc = scene({ w: CW, h: CH, fond: FOND, ...o });
    const r = dessin(sc);
    if (dessin === objets) icones = r;
    for (let y = 0; y < CH; y++) sc.img.copy(img, ((HAUT + l * (M + CH) + M + y) * W + c * CW) * 4, y * CW * 4, (y + 1) * CW * 4);
    ecrireTexte(img, W, titre, c * CW + 12, HAUT + l * (M + CH) + 10, 3, k % 2 ? BLEU : BRUN);
  });
  ecrirePng(path.join(DOC, "planche-saiyan.png"), W, H, img);
  ecrirePng(path.join(DOC, "queue_saiyan.png"), 64, 64, icones.q);
  for (const [nom, ic] of Object.entries(icones.d)) ecrirePng(path.join(DOC, `detecteur_${nom}.png`), 64, 64, ic);
  if (process.argv[2] !== "sans-gif") animation(path.join(DOC, "queue-qui-bouge.gif"));
  console.log("ok", W, H);
}
