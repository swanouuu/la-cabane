// Maquettes de Minato 2 : la touche Éveil une première fois, c'est le Minato de toujours (niveau 1) ; une seconde
// fois, le mode Kurama (niveau 2). Il garde sa tête (passée dans le doré du mode) et prend toute la tenue aux traits
// noirs du skin de l'utilisateur, avec une lueur jaune-orange ; la tenue monte sur lui de bas en haut, et s'enlève de
// haut en bas à la fin. Clic droit : la Bijuu Dama de la vidéo, avec Kurama fait de pavés comme le Hachibi géant,
// en jaune. Plus la planche d'équilibrage des dégâts par niveau.
// node rendu.mjs [kurama|transformation|bijuu|equilibrage] (sans rien : les quatre planches)
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, mix, hache, scene, ecrireTexte, POLICE } from "../../fruits/generateur/moteur.mjs";
import { cube, sol, legende, regarde, SKINS, STEVE, FOND } from "../../fruits/generateur/rendu.mjs";
import {
  ajoute, fois, TOUR, brille, joueur, tenue, aura, anneauMontant, cubeSimple, flash, fantome, kunai, kunaiEnMain, boule, bijuuDama,
  convergent, dome, LUEUR, OR, OR_C, BLANC, NOIR,
} from "./effets.mjs";
import { monde } from "./kyubi.mjs";
import { kuramaMod } from "./kurama-mod.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
// le joueur d'exemple (kai) ; le mode garde sa tête et pose la tenue sur le reste
const KAI = SKINS.kai, EFE = SKINS.efe;
const MODE = tenue(KAI), MODE_EFE = tenue(EFE), FLASH_BLANC = tenue(KAI, 99, true);
Object.assign(POLICE, { "%": "101001010100101", ">": "100010001010100", "?": "110001010000010", ";": "000010000010100" });

// ------------------------------------------------------------------ petites aides
/** Un texte posé sur un point de la scène (centré dessus). */
function etiquette(sc, t, p, c = [40, 40, 50, 255], taille = 3) {
  const [sx, sy] = sc.vue(p);
  const l = t.length * 4 * taille;
  ecrireTexte(sc.img, sc.w, t, Math.max(4, Math.min(sc.w - l - 4, Math.round(sx - l / 2))), Math.max(4, Math.min(sc.h - 60, Math.round(sy))), taille, c);
}

/** Des étoiles sur le fond (avant de dessiner le reste). */
function etoiles(sc, n = 140, graine = 0) {
  for (let q = 0; q < n; q++) {
    const x = Math.floor(hache(q, 1, 500 + graine) * sc.w), y = Math.floor(hache(q, 2, 500 + graine) * sc.h);
    sc.img.set(q % 5 ? [230, 230, 255, 255] : [120, 160, 255, 255], (y * sc.w + x) * 4);
  }
}

/** Des traits de vitesse qui partent de (cx, cy) vers les bords (sur le fond, avant le reste). */
function rayons(sc, cx, cy, c, n = 60, graine = 0) {
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, 510 + graine) * TOUR, r0 = 70 + 120 * hache(q, 2, 510 + graine), r1 = r0 + 80 + 200 * hache(q, 3, 510 + graine);
    for (let r = r0; r < r1; r += 0.7) {
      const x = Math.round(cx + Math.cos(a) * r), y = Math.round(cy + Math.sin(a) * r);
      if (x < 0 || y < 0 || x >= sc.w || y >= sc.h) break;
      const i = (y * sc.w + x) * 4, k = 0.5 * (1 - (r - r0) / (r1 - r0));
      for (let j = 0; j < 3; j++) sc.img[i + j] = sc.img[i + j] * (1 - k) + c[j] * k;
    }
  }
}

/** Le viseur bloqué : quatre coins jaunes autour d'une cible (p : le milieu de son corps), et le mot VERROUILLE. */
function viseur(sc, p, haut = 32) {
  const [cx, cy] = sc.vue(p).map(Math.round);
  const demi = Math.round(0.62 * Math.abs(sc.vue(ajoute(p, [0, haut / 2, 0]))[1] - sc.vue(ajoute(p, [0, -haut / 2, 0]))[1]));
  const c = [255, 214, 40, 255], e = 4, l = 18;
  const trait = (x0, y0, x1, y1) => { for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) if (x >= 0 && y >= 0 && x < sc.w && y < sc.h) sc.img.set(c, (y * sc.w + x) * 4); };
  for (const [sx, sy] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) {
    const x = cx + sx * demi, y = cy + sy * demi;
    trait(Math.min(x, x - sx * l), sy > 0 ? y - e : y, Math.max(x, x - sx * l) + 1, sy > 0 ? y : y + e);
    trait(sx > 0 ? x - e : x, Math.min(y, y - sy * l), sx > 0 ? x : x + e, Math.max(y, y - sy * l) + 1);
  }
  trait(cx - 1, cy - 7, cx + 2, cy + 8);
  trait(cx - 7, cy - 1, cx + 8, cy + 2);
  ecrireTexte(sc.img, sc.w, "VERROUILLE", cx - 60, cy - demi - 28, 3, c);
}

/** Une traînée de petits éclats jaunes de a à b (le chemin d'une téléportation). */
function trainee(sc, a, b, n = 18) {
  for (let q = 0; q <= n; q++) {
    const t = q / n;
    cubeSimple(sc, ajoute(a, fois(ajoute(b, fois(a, -1)), t)), 1.2 - 0.6 * Math.abs(t - 0.5), brille(q % 2 ? OR : OR_C), true);
  }
}

/** Le sceau du kunai posé : un cercle de signes noirs au sol, quelques traits qui brillent. */
function sceauSol(sc, p, r = 7) {
  for (let q = 0; q < 28; q++) {
    const a = (q / 28) * TOUR;
    if (hache(q, 1, 520) < 0.25) continue;
    const c = ajoute(p, [Math.cos(a) * r, 0.08, Math.sin(a) * r]);
    sc.quad([ajoute(c, [-0.7, 0, -0.7]), ajoute(c, [0.7, 0, -0.7]), ajoute(c, [0.7, 0, 0.7]), ajoute(c, [-0.7, 0, 0.7])], NOIR, 0.05);
  }
  for (let q = 0; q < 6; q++) {
    const a = (q / 6) * TOUR;
    const c = ajoute(p, [Math.cos(a) * r * 0.55, 0.1, Math.sin(a) * r * 0.55]);
    sc.quad([ajoute(c, [-0.5, 0, -1.2]), ajoute(c, [0.5, 0, -1.2]), ajoute(c, [0.5, 0, 1.2]), ajoute(c, [-0.5, 0, 1.2])], [255, 200, 60], 0.05, true);
  }
}

/** Une colonne de lumière transparente autour de p. */
function pilier(sc, p, r, h, c = OR_C, a = 0.35) {
  const [x, y, z] = p;
  for (const f of [
    [[x - r, y, z + r], [x + r, y, z + r], [x + r, y + h, z + r], [x - r, y + h, z + r]], [[x - r, y, z - r], [x + r, y, z - r], [x + r, y + h, z - r], [x - r, y + h, z - r]],
    [[x - r, y, z - r], [x - r, y, z + r], [x - r, y + h, z + r], [x - r, y + h, z - r]], [[x + r, y, z - r], [x + r, y, z + r], [x + r, y + h, z + r], [x + r, y + h, z - r]],
  ]) sc.voile(f, c, a);
}

/** Des cailloux qui se soulèvent autour de p. */
function cailloux(sc, p, r, n = 14, graine = 0) {
  for (let q = 0; q < n; q++) {
    const a = hache(q, 1, 530 + graine) * TOUR, d = r * (0.3 + 0.7 * hache(q, 2, 530 + graine));
    cube(sc, ajoute(p, [Math.cos(a) * d, 2 + 26 * hache(q, 3, 530 + graine), Math.sin(a) * d]), 1.4 + 2.6 * hache(q, 4, 530 + graine), [q, a], [70 + 30 * hache(q, 5, 530), 54, 44]);
  }
}

/** Le sol brûlé (en disque) au point (cx, cz), de rayon r. */
const brule = (cx, cz, r) => (x, z) => {
  const d = Math.hypot(x - cx, z - cz);
  return d < r ? mix([42, 32, 26], [100, 84, 52], (d / r) ** 3 * 0.7 + 0.3 * hache(Math.floor(x), Math.floor(z), 540)) : null;
};

/**
 * Le mode Kurama, debout (o comme pour joueur) : il brille ; son aura (un voile) se pose à
 * la fin de la vignette, quand tout ce qui est plein est dessiné (voir planche()).
 */
const AURAS = [];
const mode = (sc, o = {}, peau = MODE) => {
  const T = joueur(sc, peau, { lueur: true, ...o });
  AURAS.push(T);
  return T;
};
/** Minato au niveau 1 : son skin, une lueur très légère. */
const niveau1 = (sc, o = {}) => joueur(sc, KAI, { teinte: { c: OR_C, k: 0.08 }, ...o });

// ------------------------------------------------------------------ planche 1 : le mode Kurama et ses commandes
function enPied(sc) {
  kunaiEnMain(sc, mode(sc, { brasD: [-0.45, -0.1], brasG: [-0.15, 0.1], tete: [0.05, 0, -0.15] }));
  sc.lueur(LUEUR, 8, 1.5);
  legende(sc, "SA TETE EN JAUNE, LA TENUE DU SKIN, LA LUEUR");
}

function deDos(sc) {
  mode(sc, { brasG: [0.1, 0.1], brasD: [0.1, -0.1] });
  sc.lueur(LUEUR, 8, 1.5);
  legende(sc, "LE SCEAU DANS LE DOS");
}

function autreSkin(sc) {
  joueur(sc, EFE, { decale: [-14, 0, 0] });
  mode(sc, { decale: [14, 0, 0] }, MODE_EFE);
  sc.lueur(LUEUR, 6, 1.4);
  etiquette(sc, "SON SKIN", [-14, 40, 0]);
  etiquette(sc, "MODE", [14, 40, 0]);
  legende(sc, "CHACUN GARDE SA TETE (TRAITS DE BASE)");
}

function niveaux(sc) {
  kunaiEnMain(sc, niveau1(sc, { decale: [-14, 0, 0], brasD: [-0.4, -0.1] }));
  kunaiEnMain(sc, mode(sc, { decale: [14, 0, 0], brasD: [-0.4, -0.1] }));
  sc.lueur(LUEUR, 6, 1.4);
  etiquette(sc, "NIV. 1", [-14, 40, 0]);
  etiquette(sc, "NIV. 2", [14, 40, 0]);
  legende(sc, "EVEIL 1 FOIS, PUIS ENCORE 1 FOIS");
}

// Les huit places autour de la cible (qui regarde vers +z) : devant, gauche, droite, derrière, en haut à gauche, en haut
// à droite, en haut derrière, en bas derrière (accroupi).
const R8 = 26, H8 = 22;
const HUIT = [
  [0, 0, R8], [R8, 0, 0], [-R8, 0, 0], [0, 0, -R8],
  [R8 * 0.8, H8, 4], [-R8 * 0.8, H8, 4], [0, H8 + 4, -R8 * 0.8], [-R8 * 0.62, 0, -R8 * 0.62],
];

function huitTP(sc) {
  sol(sc, -46, -46, 46, 46);
  joueur(sc, STEVE, { penche: -0.12, brasD: [-0.4, -0.3], brasG: [-0.4, 0.3] });
  for (let k = 0; k < 7; k++) trainee(sc, ajoute(HUIT[k], [0, 16, 0]), ajoute(HUIT[k + 1], [0, 16, 0]), 10);
  const p8 = HUIT[7];
  kunaiEnMain(sc, mode(sc, { decale: p8, lacet: regarde(p8, [0, 0, 0]), penche: 0.25, brasD: [-2.2, 0.2], jambeD: -0.6, jambeG: 0.4 }));
  for (const p of HUIT) flash(sc, ajoute(p, [0, 16, 0]), 0.5);
  sc.lueur(LUEUR, 6, 1.3);
  for (const p of HUIT.slice(0, 7)) fantome(sc, p, regarde(p, [0, p[1], 0]), 0.3);
  HUIT.forEach((p, k) => etiquette(sc, String(k + 1), ajoute(p, [0, 40, 0]), [200, 110, 0, 255], 4));
  legende(sc, "CHAQUE COUP : UN TP AUTOUR, FLASH ET BRUIT");
}

function verrou(sc) {
  sol(sc, -40, -30, 40, 60);
  joueur(sc, STEVE, { penche: -0.1, brasD: [-0.3, -0.3], brasG: [-0.3, 0.3] });
  kunaiEnMain(sc, mode(sc, { decale: [-14, 0, 40], lacet: Math.PI - 0.3, brasD: [-1.9, 0.15] }));
  for (const p of [[R8, H8, 4], [-R8, 0, 0]]) flash(sc, ajoute(p, [0, 16, 0]), 0.45);
  sc.lueur(LUEUR, 6, 1.3);
  for (const p of [[R8, H8, 4], [-R8, 0, 0]]) fantome(sc, p, regarde(p, [0, p[1], 0]), 0.28);
  viseur(sc, [0, 16, 0]);
  legende(sc, "BLOQUE SUR LUI. 1 S SANS TAPER : LIBRE");
}

function tpRegard(sc) {
  sol(sc, -30, -20, 30, 120);
  const fin = [0, 0, 96];
  trainee(sc, [0, 16, 0], ajoute(fin, [0, 16, 0]), 34);
  kunaiEnMain(sc, mode(sc, { decale: fin, brasD: [-0.4, -0.1] }));
  flash(sc, [0, 16, 0], 0.8);
  flash(sc, ajoute(fin, [0, 16, 0]), 0.8);
  sc.lueur(LUEUR, 6, 1.3);
  fantome(sc, [0, 0, 0], 0, 0.32);
  etiquette(sc, "AVANT", [0, 40, 0]);
  etiquette(sc, "APRES", ajoute(fin, [0, 42, 0]));
  legende(sc, "LA OU IL REGARDE, 40 BLOCS, RECHARGE 5 S");
}

function kunaiLance(sc) {
  sol(sc, -30, -20, 30, 120);
  const depart = [-4, 20, 6], plante = [2, 1.5, 88];
  for (let q = 1; q < 16; q++) {
    const t = q / 16;
    cubeSimple(sc, [depart[0] + (plante[0] - depart[0]) * t, depart[1] + (plante[1] - depart[1]) * t + 18 * Math.sin(t * Math.PI), depart[2] + (plante[2] - depart[2]) * t], 0.8, [70, 72, 80]);
  }
  kunai(sc, plante, [0.1, -0.7, 0.7], 1.4);
  mode(sc, { brasD: [-2.5, -0.1], brasG: [-0.4, 0.2], jambeD: 0.3, jambeG: -0.3 });
  flash(sc, [6, 16, 84], 0.8);
  sc.lueur(LUEUR, 6, 1.3);
  fantome(sc, [6, 0, 84], -0.5, 0.32);
  etiquette(sc, "ENCORE : IL Y VA", [6, 44, 84]);
  legende(sc, "8 DEGATS. RECOMMENCER : IL Y VA");
}

function kunaiPose(sc) {
  sol(sc, -30, -24, 30, 36);
  sceauSol(sc, [7, 0, 10], 7);
  kunai(sc, [7, 0.5, 10], [0, -1, 0.15], 1.5);
  kunaiEnMain(sc, mode(sc, { lacet: -0.3, brasD: [-0.2, -0.1] }));
  sc.lueur(LUEUR, 6, 1.3);
  legende(sc, "UN SHIFT : LE KUNAI SE PLANTE A SES PIEDS");
}

function retourKunai(sc) {
  sol(sc, -30, -20, 30, 120);
  sceauSol(sc, [4, 0, 92], 7);
  kunai(sc, [4, 0.5, 92], [0, -1, 0.15], 1.5);
  trainee(sc, [0, 16, 0], [4, 16, 86], 30);
  kunaiEnMain(sc, mode(sc, { decale: [0, 0, 86], lacet: 0.4, brasD: [-0.4, -0.1] }));
  flash(sc, [0, 16, 0], 0.8);
  flash(sc, [0, 16, 86], 0.8);
  sc.lueur(LUEUR, 6, 1.3);
  fantome(sc, [0, 0, 0], 0.3, 0.32);
  etiquette(sc, "SHIFT SHIFT", [0, 40, 0]);
  legende(sc, "DEUX SHIFTS VITE : IL RETOURNE AU KUNAI");
}

function heroBijuu(sc) {
  etoiles(sc, 90, 1);
  sol(sc, -110, -110, 110, 110);
  kuramaMod(sc, monde([0, 0, -20], [0, 0, 1], 0.62), { gueule: 1 });
  mode(sc, { decale: [16, 0, 70], lacet: Math.PI + 0.4, brasD: [-1.6, 0.3], brasG: [-1.6, -0.3] });
  const c = [0, 57, 25];
  bijuuDama(sc, c, 7, 0.4);
  convergent(sc, c, 10, 22, 30);
  sc.lueur(LUEUR, 6, 1.2);
  legende(sc, "CLIC DROIT : VOIR LA PLANCHE 3");
}

function heroKurama(sc) {
  sol(sc, -130, -130, 130, 130);
  kuramaMod(sc, monde([0, 0, 0], [0, 0, 1], 1), { gueule: 0.7 });
  mode(sc, { decale: [34, 0, 96], lacet: Math.PI - 0.5 });
  sc.lueur(LUEUR, 6, 1.1);
  legende(sc, "LE KURAMA DU MOD, EN JAUNE, YEUX ROUGES");
}

function deCote(sc) {
  kunaiEnMain(sc, mode(sc, { brasD: [-0.3, -0.05], brasG: [0.2, 0.05] }));
  sc.lueur(LUEUR, 8, 1.5);
  legende(sc, "DE COTE : LES TRAITS DES BRAS ET DES JAMBES");
}

function visage(sc) {
  mode(sc, {});
  sc.lueur(LUEUR, 8, 1.4);
  legende(sc, "SES CHEVEUX EN JAUNE, LE VISAGE DU SKIN");
}

// ------------------------------------------------------------------ planche 2 : la transformation (de bas en haut)
const etape = (h, texte) => (sc) => {
  joueur(sc, tenue(KAI, h), { bande: h, brasD: [-0.15, -0.3], brasG: [-0.15, 0.3] });
  anneauMontant(sc, [0, 0, 0], h);
  sc.lueur(LUEUR, 7, 1.4);
  legende(sc, texte);
};

function tFlash(sc) {
  joueur(sc, FLASH_BLANC, { lueur: true, brasD: [-0.3, -0.5], brasG: [-0.3, 0.5] });
  sc.lueur([255, 236, 170], 12, 1.8);
  legende(sc, "2,4 S : FLASH, LES TRAITS NOIRS RESTENT");
}

function tPret(sc) {
  sol(sc, -34, -24, 34, 40);
  kunai(sc, [9, 0.5, 18], [0.15, -1, 0.1], 1.4);
  mode(sc, { brasD: [-1.25, 0.5], brasG: [-1.25, -0.5], jambeD: [0, -0.28], jambeG: [0, 0.28] });
  sc.lueur(LUEUR, 9, 1.6);
  legende(sc, "3 S : PRET, LA LUEUR JAUNE-ORANGE RESTE");
}

function tFin(sc) {
  const h = 18;
  joueur(sc, tenue(KAI, h), { bande: h });
  anneauMontant(sc, [0, 0, 0], h);
  for (let q = 0; q < 14; q++) cubeSimple(sc, [(hache(q, 1, 900) - 0.5) * 16, h - 4 - 14 * hache(q, 2, 900), (hache(q, 3, 900) - 0.5) * 8], 0.8, brille(q % 2 ? OR : OR_C), true);
  sc.lueur(LUEUR, 6, 1.2);
  legende(sc, "A LA FIN : TOUT S'ENLEVE, DE HAUT EN BAS");
}

// ------------------------------------------------------------------ planche 3 : la Bijuu Dama (clic droit)
function b1Frappe(sc) {
  sol(sc, -40, -50, 40, 100);
  joueur(sc, STEVE, { penche: -0.35, brasD: [-1.0, -0.6], brasG: [-1.0, 0.6], tete: [-0.4] });
  const T = mode(sc, { decale: [0, 0, 16], lacet: Math.PI, brasD: [-1.6, 0], brasG: [0.4, 0.1], jambeD: 0.5, jambeG: -0.4 });
  flash(sc, ajoute(T.brasD.p([-6, 11, 0]), [0, 0, -3]), 1);
  flash(sc, [0, 16, 80], 0.7);
  sc.lueur(LUEUR, 7, 1.4);
  fantome(sc, [0, 0, 80], Math.PI, 0.3);
  legende(sc, "0 S : TP SUR LA CIBLE, IL FRAPPE");
}

function b2Projete(sc) {
  sol(sc, -40, -90, 40, 50);
  joueur(sc, STEVE, { decale: [0, 14, -46], penche: -1.1, brasD: [-2.6, -0.8], brasG: [-2.6, 0.8], jambeD: 0.4, jambeG: -0.3 });
  mode(sc, { decale: [0, 0, 6], lacet: Math.PI, brasD: [-1.7, 0], jambeD: 0.4, jambeG: -0.3 });
  for (let q = 0; q < 24; q++) {
    const a = (q / 24) * TOUR;
    cubeSimple(sc, [Math.cos(a) * 18, 20 + Math.sin(a) * 18, -14], 1.4, brille(q % 2 ? BLANC : OR_C), true);
  }
  sc.lueur(LUEUR, 7, 1.4);
  legende(sc, "0,6 S : LA CIBLE EST PROJETEE");
}

function b3Pilier(sc) {
  rayons(sc, sc.w / 2, sc.h * 0.4, [255, 255, 220], 80, 12);
  sol(sc, -40, -40, 40, 40);
  mode(sc, { brasD: [-0.4, -0.8], brasG: [-0.4, 0.8], tete: [-0.4] });
  sc.lueur([255, 236, 160], 12, 1.8);
  pilier(sc, [0, 0, 0], 8, 200, BLANC, 0.45);
  legende(sc, "1,4 S : LE CHAKRA MONTE EN PILIER");
}

function b4Kurama(sc) {
  sol(sc, -120, -120, 120, 120);
  kuramaMod(sc, monde([0, 0, -10], [0, 0, 1], 0.8), { gueule: 0.3 });
  mode(sc, { decale: [0, 0, 92], brasD: [-0.5, -0.3], brasG: [-0.5, 0.3] });
  sc.lueur(LUEUR, 7, 1.2);
  legende(sc, "2,2 S : KURAMA APPARAIT DERRIERE LUI");
}

function b5Rugit(sc) {
  rayons(sc, sc.w / 2, sc.h * 0.5, [255, 170, 60], 90, 14);
  kuramaMod(sc, monde([0, 0, 0], [0, 0, 1], 1), { gueule: 1, tete: 0.15 });
  sc.lueur(LUEUR, 7, 1.2);
  legende(sc, "3,2 S : IL RUGIT");
}

function b6Mains(sc) {
  rayons(sc, sc.w / 2, sc.h * 0.5, [255, 200, 110], 80, 22);
  const T = mode(sc, { brasD: [-1.45, 0.42], brasG: [-1.45, -0.42] });
  const entre = ajoute(fois(ajoute(T.brasD.p([-6, 11, 0]), T.brasG.p([6, 11, 0])), 0.5), [0, 0, 3]);
  flash(sc, entre, 0.8);
  sc.lueur(LUEUR, 8, 1.4);
  legende(sc, "4 S : IL TEND LES MAINS, LA LUMIERE NAIT");
}

function b7Forme(sc) {
  etoiles(sc, 160, 16);
  kuramaMod(sc, monde([0, 0, 0], [0, 0, 1], 1), { gueule: 1, tete: 0.1 });
  const c = [0, 92, 78];
  bijuuDama(sc, c, 13);
  convergent(sc, c, 17, 52, 70, 2);
  sc.lueur(LUEUR, 6, 1.1);
  legende(sc, "4,8 S : LA BOULE NOIRE SE FORME");
}

function b8Comprime(sc) {
  etoiles(sc, 200, 18);
  convergent(sc, [0, 0, 0], 20, 64, 80, 4);
  bijuuDama(sc, [0, 0, 0], 16, 0.9);
  sc.lueur([236, 60, 20], 12, 1.6);
  legende(sc, "6 S : ELLE SE COMPRIME, LE COEUR ROUGE");
}

function b9Tire(sc) {
  sol(sc, -60, -120, 60, 300);
  kuramaMod(sc, monde([0, 0, -40], [0, 0, 1], 0.7), { gueule: 1 });
  for (let q = 1; q < 12; q++) cubeSimple(sc, [0, 60 - q * 1.6, 120 - q * 6], 9 - q * 0.6, brille(mix([255, 226, 130], [255, 120, 30], q / 12)), true);
  boule(sc, [0, 58, 126], 12, () => brille([255, 226, 120]), { tranche: 2 });
  cailloux(sc, [0, 0, 170], 34, 18);
  joueur(sc, STEVE, { decale: [0, 0, 180], lacet: Math.PI, brasD: [-1.4, -0.4], brasG: [-1.4, 0.4] });
  sc.lueur(LUEUR, 8, 1.5);
  legende(sc, "7,2 S : KURAMA TIRE LA BIJUU DAMA");
}

function b10Impact(sc) {
  rayons(sc, sc.w * 0.4, sc.h * 0.45, [255, 150, 50], 90, 20);
  sol(sc, -50, -50, 50, 50);
  joueur(sc, STEVE, { decale: [12, 0, 0], lacet: -0.6, penche: -0.3, brasD: [-1.8, -0.5], brasG: [-1.8, 0.5] });
  boule(sc, [-10, 22, 0], 22, (n, y) => brille(mix([255, 244, 186], [255, 120, 26], Math.max(0, -y) * 0.8 + 0.2)), { tranche: 2.5 });
  cailloux(sc, [0, 0, 0], 40, 24, 3);
  sc.lueur([255, 140, 40], 12, 1.7);
  legende(sc, "8,2 S : IMPACT");
}

function b11Dome(sc) {
  sol(sc, -160, -160, 160, 160, brule(0, 0, 100));
  dome(sc, [0, 0, 0], 76);
  sc.lueur([255, 170, 70], 12, 1.5);
  legende(sc, "9,4 S : LE DOME, VU DU CIEL (10 BLOCS)");
}

function b12Apres(sc) {
  sol(sc, -60, -50, 60, 80, brule(0, -10, 46));
  joueur(sc, STEVE, { decale: [0, 2, -10], penche: -Math.PI / 2, brasD: [-0.4, -0.4], brasG: [-0.4, 0.4] });
  for (let q = 0; q < 10; q++) cube(sc, [(hache(q, 1, 640) - 0.5) * 50, 4 + 30 * hache(q, 2, 640), -10 + (hache(q, 3, 640) - 0.5) * 40], 3 + 3 * hache(q, 4, 640), [q, q], [150, 140, 136]);
  kunaiEnMain(sc, mode(sc, { decale: [6, 0, 44], lacet: Math.PI + 0.3, brasD: [-0.2, -0.1] }));
  sc.lueur(LUEUR, 6, 1.2);
  legende(sc, "12 S : FLASH BLANC, PUIS LA CIBLE AU SOL");
}

// ------------------------------------------------------------------ l'assemblage
const NOIRT = [30, 30, 40, 255], OR_T = [206, 120, 10, 255], GRIS = [120, 120, 130, 255], VERT = [30, 140, 60, 255], ROUGE_T = [200, 50, 40, 255];

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

/** Une planche : le titre, des lignes de texte (["---"] trace un trait), puis les vignettes en grille. */
function planche(fichier, titre, lignes, vues, { colonnes = 4, cw = 440, ch = 520 } = {}) {
  const HAUT = 100 + lignes.length * 34 + 20, M = 34;
  const rangs = Math.ceil(vues.length / colonnes);
  const W = cw * colonnes, H = HAUT + (M + ch) * rangs;
  const img = Buffer.alloc(W * H * 4, 255);
  rect(img, W, 0, 0, W, HAUT, [236, 236, 242, 255]);
  ecrireTexte(img, W, titre, 30, 22, 5, NOIRT);
  lignes.forEach((t, k) => {
    const [texte, c] = Array.isArray(t) ? t : [t, NOIRT];
    if (texte === "---") rect(img, W, 30, 100 + k * 34 + 12, W - 30, 100 + k * 34 + 15, [200, 200, 210, 255]);
    else ecrireTexte(img, W, texte, 30, 100 + k * 34, 3, c);
  });
  vues.forEach(([t, dessin, o], k) => {
    const c = k % colonnes, l = Math.floor(k / colonnes);
    const sc = scene({ w: cw, h: ch, fond: FOND, ...o });
    AURAS.length = 0;
    dessin(sc);
    for (const T of AURAS) aura(sc, T);
    for (let y = 0; y < ch; y++) sc.img.copy(img, ((HAUT + l * (M + ch) + M + y) * W + c * cw) * 4, y * cw * 4, (y + 1) * cw * 4);
    ecrireTexte(img, W, t, c * cw + 12, HAUT + l * (M + ch) + 10, 3, OR_T);
  });
  ecrirePng(path.join(DOC, fichier), W, H, img);
  console.log("ok", fichier, W, H);
}

/**
 * La planche d'équilibrage : chaque ligne = [pouvoir, ce qui reste, [avant, après]...] ; les changements en vert (plus
 * fort) ou en rouge (moins fort).
 */
function equilibrage() {
  const W = 1760;
  const PALIERS = [
    ["NIVEAU 1", "COUPS 8 A 9, GROS COUPS 12 A 14", [
      ["SUSANO", "COUP 9, JET 5, CHARGE 8", []],
      ["HACHIBI", "GRIFFE 9", [["GRIFFE DE LA BETE", "13", "11"], ["BOMBE", "18", "14"]]],
      ["MARQUE", "ARME +1,5", []],
      ["FRUITS", "ARME +2, JET 6, ERUPTION 8, ERE GLACIAIRE 6", []],
    ]],
    ["NIVEAU 2", "COUPS 11 A 14, GROS COUPS 16 A 20", [
      ["MINATO (ET MINATO 2 AU NIV. 1)", "", [["KUNAI EN MAIN", "8,5", "11"], ["KUNAI LANCE", "4", "6"], ["COMBO", "16", "20"], ["ARMURE", "DIAMANT", "NETHERITE"]]],
      ["SUSANO 2", "COUP 14, ECRASE 16, CHARGE 12", []],
      ["MARQUE 2", "ARME +3", [["CHIDORI (= COMBO DE MINATO)", "16", "20"]]],
      ["HACHIBI 2 (NIV. 1 ET 2)", "GRIFFE 13, BOMBE 18", []],
      ["FRUITS 2", "ARME +4, LANCES 6, METEORES 5", [["POING DE MAGMA", "12", "14"], ["FAISAN DE GLACE", "12", "14"]]],
    ]],
    ["NIVEAU 3", "COUPS 13 A 16, GROS COUPS 22 A 30", [
      ["MINATO 2 (MODE KURAMA)", "NOUVEAU", [["KUNAI + TP", "-", "13 / 0,4 S"], ["KUNAI LANCE", "-", "8"], ["BIJUU DAMA", "-", "30 (+12 AUTOUR)"], ["ARMURE", "-", "CHEVALIER"]]],
      ["SUSANO 3", "EPEE 16, FLECHE 10 + FEU NOIR", [["GRAND COUP", "18", "22"]]],
      ["HACHIBI 2 (NIV. 3 ET 4)", "GRIFFE 16, BOMBE 22, BOMBE NOIRE 30, SERRE 16", []],
      ["ESPIONNES (TOUJOURS LES PLUS FORTES)", "GADGETS, 3 MIN D'EVEIL", [["LASER", "5", "8"], ["COMBINAISON", "FER", "CHEVALIER"]]],
    ]],
  ];
  const H = 3000;
  const img = Buffer.alloc(W * H * 4, 255);
  rect(img, W, 0, 0, W, H, [240, 240, 245, 255]);
  ecrireTexte(img, W, "EQUILIBRAGE DES DEGATS PAR NIVEAU", 30, 22, 5, NOIRT);
  [
    "LA REGLE : LES NIVEAUX 3 FRAPPENT LE PLUS FORT, PUIS LES NIVEAUX 2, PUIS LES NIVEAUX 1. LES ESPIONNES SONT TOUJOURS AU PLUS HAUT.",
    "CE QUI SE SPAMME (LES COUPS, LE LASER) RESTE RAISONNABLE ; CE QUI A UNE GROSSE RECHARGE (BOMBES, COMBOS) PEUT FAIRE TRES MAL.",
    "MINATO 1 MONTE AU NIVEAU DES AUTRES NIVEAUX 2 ; MINATO 2 (LE MODE KURAMA) EST AU NIVEAU DES AUTRES NIVEAUX 3.",
    "DEGATS EN POINTS (2 POINTS = 1 COEUR), AVANT L'ARMURE. EN VERT CE QUI MONTE, EN ROUGE CE QUI BAISSE ; LE RESTE NE BOUGE PAS.",
  ].forEach((t, k) => ecrireTexte(img, W, t, 30, 100 + k * 32, 3, NOIRT));
  let y = 250;
  for (const [palier, cible, pouvoirs] of PALIERS) {
    rect(img, W, 30, y - 6, W - 30, y + 26, [226, 226, 234, 255]);
    ecrireTexte(img, W, palier, 44, y, 4, OR_T);
    ecrireTexte(img, W, cible, 300, y + 3, 3, GRIS);
    y += 50;
    for (const [nom, reste, changes] of pouvoirs) {
      ecrireTexte(img, W, nom, 60, y, 3, NOIRT);
      if (reste) ecrireTexte(img, W, reste + (changes.length ? "" : " (NE BOUGE PAS)"), 620, y, 3, GRIS);
      changes.forEach(([quoi, avant, apres], k) => {
        const yy = y + (reste ? k + 1 : k) * 34;
        const plus = avant === "-" || parseFloat(apres) > parseFloat(avant) || ["NETHERITE", "CHEVALIER"].includes(apres);
        ecrireTexte(img, W, quoi + " : " + avant + " > " + apres, 620, yy, 3, plus ? VERT : ROUGE_T);
      });
      y += 34 * Math.max(1, changes.length + (reste && changes.length ? 1 : 0)) + 6;
    }
    y += 20;
  }
  ecrirePng(path.join(DOC, "planche-equilibrage.png"), W, Math.min(H, y + 40), img.subarray(0, W * Math.min(H, y + 40) * 4));
  console.log("ok planche-equilibrage.png", W, y + 40);
}

const SOMBRE = [46, 30, 30], NUIT = [22, 20, 44], ORANGE_F = [250, 176, 96];

const PLANCHES = {
  tenue: () => planche("planche-tenue.png", "MINATO 2 : LA TENUE DU MODE KURAMA", [
    ["LA TENUE : TON SKIN DU MODE KURAMA, PIXEL POUR PIXEL (CORPS, BRAS, JAMBES, TOUS SES TRAITS NOIRS, L'OEIL DANS LE DOS)", OR_T],
    "LA TETE : IL GARDE SES CHEVEUX (PASSES EN JAUNE) ; LE VISAGE EST CELUI DU SKIN KURAMA (YEUX ROUGES, MOUSTACHES, BOUCHE DE 2 PIXELS)",
    "PAR-DESSUS : UNE AURA DOREE, COMME CELLE DU MANTEAU DE HACHIBI, ET LA LUEUR. ICI SUR UN SKIN D'EXEMPLE",
    "TRANSFORMATION : UN ANNEAU DE LUMIERE MONTE DES PIEDS A LA TETE ET POSE LA TENUE ; A LA FIN, IL REDESCEND ET ENLEVE TOUT",
  ], [
    ["DEVANT", enPied, { lacet: -24, echelle: 10, pied: 470 }],
    ["DOS", deDos, { lacet: 158, echelle: 10, pied: 470 }],
    ["DE COTE", deCote, { lacet: -78, echelle: 10, pied: 470 }],
    ["LE VISAGE", visage, { lacet: -18, echelle: 30, pied: 300, centre: [0, 27, 0] }],
    ["AVEC UN AUTRE SKIN", autreSkin, { lacet: -16, tangage: 6, echelle: 5.8, pied: 420, centre: [0, 14, 0] }],
    ["NIVEAU 1 PUIS NIVEAU 2", niveaux, { lacet: -16, tangage: 6, echelle: 5.8, pied: 420, centre: [0, 14, 0] }],
    ["FLASH", tFlash, { lacet: -20, echelle: 10, pied: 480, fond: [252, 248, 230] }],
    ["PRET", tPret, { lacet: -10, tangage: 10, echelle: 7.6, pied: 430, centre: [0, 4, 0], fond: [44, 64, 70] }],
    ["0,6 S", etape(8, "0,6 S : L'ANNEAU MONTE, LA TENUE APPARAIT"), { lacet: -20, echelle: 10, pied: 480 }],
    ["1,2 S", etape(16, "1,2 S : LA CEINTURE, LE SCEAU"), { lacet: -20, echelle: 10, pied: 480 }],
    ["2,2 S", etape(29, "2,2 S : LA TETE PASSE EN JAUNE"), { lacet: -20, echelle: 10, pied: 480 }],
    ["APRES 2 MIN", tFin, { lacet: -20, echelle: 10, pied: 480 }],
  ]),
  kurama: () => planche("planche-minato2.png", "MINATO 2 : LE MODE KURAMA", [
    ["LISTE BLANCHE MINATO2. TOUCHE EVEIL 1 FOIS : LE MINATO DE TOUJOURS (NIVEAU 1). ENCORE 1 FOIS : LE MODE KURAMA (NIVEAU 2)", OR_T],
    "LE LOOK (PLANCHE TENUE) : SES CHEVEUX EN JAUNE, LE VISAGE DE TON SKIN KURAMA (YEUX ROUGES, MOUSTACHES, BOUCHE DE 2 PIXELS),",
    "    TOUTE LA TENUE DE TON SKIN PIXEL POUR PIXEL, UNE AURA DOREE ET UNE LUEUR JAUNE-ORANGE. A LA FIN DU MODE, TOUT S'ENLEVE",
    "---",
    "CLIC GAUCHE : CHAQUE COUP QUI TOUCHE LE TELEPORTE AUTOUR DE LA CIBLE (8 PLACES), FLASH ET BRUIT DU TP A CHAQUE FOIS.",
    "    LE VISEUR RESTE BLOQUE SUR ELLE TANT QU'IL TAPE ; 1 S SANS TAPER ET IL EST LIBRE. 13 DEGATS, UN COUP TOUTES LES 0,4 S.",
    "    EN L'AIR IL FLOTTE LE TEMPS DU COUP, ET AUCUN DEGAT DE CHUTE TANT QU'IL EST BLOQUE",
    "SHIFT + CLIC DROIT : TP INSTANTANE LA OU IL REGARDE (40 BLOCS AU PLUS), RECHARGE 5 S",
    "SHIFT + CLIC GAUCHE : LE KUNAI LANCE, COMME AU NIVEAU 1 (8 DEGATS) ; RECOMMENCER LE TELEPORTE DESSUS",
    "SHIFT UNE FOIS : UN KUNAI SE PLANTE A SES PIEDS. DEUX SHIFTS VITE : IL Y RETOURNE (D'OU QU'IL SOIT)",
    "CLIC DROIT : LA BIJUU DAMA AVEC KURAMA (PLANCHE 3) : 30 DEGATS DONT 14 TRAVERSENT L'ARMURE, 12 AUTOUR, RECHARGE 60 S",
    "NIVEAU 2 : 2 MIN, PUIS 1 MIN DE REPOS. ARMURE COMME LE CHEVALIER DU SUSANO 3. +80 % DE VITESSE (+55 % AU NIVEAU 1). KUNAI EN MAIN",
  ], [
    ["MODE KURAMA : DEVANT", enPied, { lacet: -28, echelle: 10, pied: 470 }],
    ["MODE KURAMA : DOS", deDos, { lacet: 158, echelle: 10, pied: 470 }],
    ["AVEC UN AUTRE SKIN", autreSkin, { lacet: -16, tangage: 6, echelle: 5.8, pied: 420, centre: [0, 14, 0] }],
    ["NIVEAU 1 PUIS NIVEAU 2", niveaux, { lacet: -16, tangage: 6, echelle: 5.8, pied: 420, centre: [0, 14, 0] }],
    ["CLIC GAUCHE : 8 TP AUTOUR", huitTP, { lacet: -22, tangage: 50, echelle: 4.4, pied: 280, centre: [0, 10, 0] }],
    ["CLIC GAUCHE : VISEUR BLOQUE", verrou, { lacet: -20, tangage: 10, echelle: 5, pied: 300, centre: [-4, 18, 22] }],
    ["SHIFT + CLIC DROIT : TP", tpRegard, { lacet: 62, tangage: 16, echelle: 3.2, pied: 330, centre: [0, 14, 48] }],
    ["SHIFT + CLIC GAUCHE : KUNAI", kunaiLance, { lacet: 62, tangage: 16, echelle: 3.2, pied: 330, centre: [0, 14, 46] }],
    ["SHIFT : KUNAI A SES PIEDS", kunaiPose, { lacet: -34, tangage: 24, echelle: 5.8, pied: 300, centre: [0, 14, 4] }],
    ["DOUBLE SHIFT : RETOUR", retourKunai, { lacet: 60, tangage: 16, echelle: 3.2, pied: 330, centre: [0, 14, 46] }],
    ["CLIC DROIT : BIJUU DAMA", heroBijuu, { lacet: -38, tangage: 8, echelle: 2.2, pied: 330, centre: [0, 50, 20], fond: NUIT }],
    ["KURAMA", heroKurama, { lacet: -48, tangage: 10, echelle: 1.45, pied: 330, centre: [0, 80, -10] }],
  ]),
  transformation: () => planche("planche-transformation.png", "MINATO 2 : LA TRANSFORMATION EN MODE KURAMA", [
    ["TOUCHE EVEIL QUAND IL EST DEJA AU NIVEAU 1 : 3 S. UN ANNEAU DE LUMIERE MONTE DES PIEDS A LA TETE ET POSE LA TENUE", OR_T],
    "SOUS L'ANNEAU : LA TENUE AUX TRAITS NOIRS ; LA TETE PASSE EN DORE QUAND L'ANNEAU Y ARRIVE ; PUIS UN FLASH, ET LA LUEUR RESTE",
    "A LA FIN DES 2 MIN, L'ANNEAU REDESCEND DE LA TETE AUX PIEDS ET ENLEVE TOUT : IL RETROUVE SON SKIN",
    "LES AUTRES VOIENT LA MEME CHOSE. SONS : UN GRONDEMENT QUI MONTE, LE FLASH, LE RUGISSEMENT DE KURAMA",
  ], [
    ["0 S", etape(0.5, "0 S : L'ANNEAU APPARAIT A SES PIEDS"), { lacet: -20, echelle: 10, pied: 480 }],
    ["0,6 S", etape(8, "0,6 S : IL MONTE, LA TENUE APPARAIT"), { lacet: -20, echelle: 10, pied: 480 }],
    ["1,2 S", etape(16, "1,2 S : LA TENUE ET LA CEINTURE"), { lacet: -20, echelle: 10, pied: 480 }],
    ["1,8 S", etape(23.5, "1,8 S : LES BRAS, LE COL"), { lacet: -20, echelle: 10, pied: 480 }],
    ["2,2 S", etape(29, "2,2 S : LA TETE PASSE EN DORE"), { lacet: -20, echelle: 10, pied: 480 }],
    ["2,4 S", tFlash, { lacet: -20, echelle: 10, pied: 480, fond: [252, 248, 230] }],
    ["3 S", tPret, { lacet: -10, tangage: 10, echelle: 7.6, pied: 430, centre: [0, 4, 0], fond: [44, 64, 70] }],
    ["APRES 2 MIN", tFin, { lacet: -20, echelle: 10, pied: 480 }],
  ]),
  bijuu: () => planche("planche-bijuu-dama.png", "MINATO 2 : CLIC DROIT, LA BIJUU DAMA", [
    ["CLIC DROIT : MINATO SE TELEPORTE SUR CE QU'IL VISE (30 BLOCS) ET FRAPPE. RATE : 10 S DE RECHARGE", OR_T],
    "TOUCHE : LA BIJUU DAMA, 12,5 S. LUI ET LA CIBLE LA VOIENT EN CINEMATIQUE, LES AUTRES LA VOIENT DANS LE MONDE",
    "PENDANT CE TEMPS LES DEUX RESTENT FIGES ET PERSONNE D'AUTRE NE PEUT LES TOUCHER (COMME LE COMBO DU NIVEAU 1)",
    "DEGATS : 30 SUR LA CIBLE, DONT 14 QUI TRAVERSENT L'ARMURE ; LE DOME FAIT 12 A TOUS CEUX A 10 BLOCS. AUCUN BLOC CASSE",
    "RECHARGE 60 S. KURAMA (CELUI DU MOD, EN JAUNE, YEUX ROUGES), 12 BLOCS DE HAUT, APPARAIT DERRIERE LUI ET DISPARAIT A LA FIN",
  ], [
    ["0 S", b1Frappe, { lacet: -70, tangage: 10, echelle: 4.2, pied: 340, centre: [0, 16, 30] }],
    ["0,6 S", b2Projete, { lacet: -70, tangage: 10, echelle: 4.2, pied: 340, centre: [0, 16, -12] }],
    ["1,4 S", b3Pilier, { lacet: -18, tangage: 6, echelle: 6.4, pied: 440, fond: [255, 226, 150] }],
    ["2,2 S", b4Kurama, { lacet: -28, tangage: 4, echelle: 1.7, pied: 330, centre: [0, 64, 10], fond: SOMBRE }],
    ["3,2 S", b5Rugit, { lacet: -26, tangage: -4, echelle: 4.4, pied: 260, centre: [0, 98, 40], fond: [80, 30, 16] }],
    ["4 S", b6Mains, { lacet: -10, echelle: 10, pied: 300, centre: [0, 20, 0], fond: [110, 50, 20] }],
    ["4,8 S", b7Forme, { lacet: -34, tangage: 2, echelle: 2.2, pied: 280, centre: [0, 86, 40], fond: NUIT }],
    ["6 S", b8Comprime, { lacet: 0, echelle: 8, pied: 260, centre: [0, 0, 0], fond: [16, 14, 34] }],
    ["7,2 S", b9Tire, { lacet: -70, tangage: 10, echelle: 1.45, pied: 330, centre: [0, 50, 70], fond: SOMBRE }],
    ["8,2 S", b10Impact, { lacet: -40, tangage: 4, echelle: 5, pied: 330, centre: [0, 18, 0], fond: [60, 24, 14] }],
    ["9,4 S", b11Dome, { lacet: -30, tangage: 32, echelle: 1.25, pied: 330, centre: [0, 20, 0], fond: [120, 96, 80] }],
    ["12 S", b12Apres, { lacet: -30, tangage: 18, echelle: 3.6, pied: 330, centre: [0, 8, 14] }],
  ]),
  equilibrage,
};

// les briques servent aussi à l'animation de l'attaque (anim.mjs)
export { etoiles, rayons, trainee, pilier, cailloux, brule, mode, KAI, MODE };

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  const quoi = process.argv[2];
  for (const [nom, f] of Object.entries(PLANCHES)) if (!quoi || quoi === nom) f();
}
void ORANGE_F;
