// Les planches du mode catch : la salle (vue du ciel, l'entrée, le ring, les rings de toutes les tailles), les 5 tenues
// gonflées, les 5 masques et leurs prises de finition image par image.
// node rendu.mjs [salle|tenues|masques|prises] → ../planche-*.png (sans argument : toutes)
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  scene, ecrirePng, LUMIERE, melange, ajoute, fois, hache, etiquette, texte, largeur, rect, paragraphe, coller, feu, faisceau, impact, onde, nuage, vitesse, trait, corps,
} from "./moteur.mjs";
import { construireSalle, rendreMonde, rendreGens, lumiereSalle, Monde, ring, lumiereJour } from "./salle.mjs";
import { catcheur, dessiner, SKINS, TENUES, MASQUES, joueur, arbitre } from "./catcheur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const JAUNE = [255, 214, 90], BLANC = [236, 234, 240], GRIS = [170, 166, 186], ROSE = [255, 120, 150];
const FOND_PLANCHE = [16, 14, 22];

// ------------------------------------------------------------------ la mise en page
function planche(W, H) {
  const img = Buffer.alloc(W * H * 4);
  for (let i = 0; i < W * H; i++) img.set([...FOND_PLANCHE, 255], i * 4);
  return { img, W, H };
}
/** Une bande de titre : fond sombre, texte jaune (t = taille des pixels de la police). */
function bandeau(P, y, h, titre, { t = 4, couleur = JAUNE, x = 16 } = {}) {
  rect(P.img, P.W, 0, y, P.W, y + h, [24, 18, 30]);
  texte(P.img, P.W, titre, x, y + Math.round((h - 5 * t) / 2), t, couleur);
}
const coll = (P, sc, x, y) => coller(P.img, P.W, sc.img, sc.w, sc.h, x, y);
const pied = (x, y, z) => [x * 16, y * 16 + 12, z * 16];

/** Un petit cartouche de légende en bas à gauche d'une vue. */
function legende(sc, s, t = 3) {
  const L = largeur(s, t);
  rect(sc.img, sc.w, 8, sc.h - 5 * t - 22, 8 + L + 16, sc.h - 8, [18, 14, 24], 0.88);
  texte(sc.img, sc.w, s, 16, sc.h - 5 * t - 15, t, BLANC);
}

/** Un fond de salle sombre (pour les vues de la salle). */
const fondSalle = (H) => (x, y) => melange([12, 10, 20], [34, 26, 50], y / H);

// les catcheurs du spectacle
const C = {
  mysterio: catcheur(SKINS.kai, "shortLuchador", "mysterio"),
  vipere: catcheur(SKINS.steve, "slipNoir", "vipere"),
  champion: catcheur(SKINS.efe, "jean", "champion"),
  demon: catcheur(SKINS.steve, "collantFlammes", "demon"),
  taureau: catcheur(SKINS.kai, "slipEtoile", "taureau"),
};

/** Une colonne de flammes de la pyro (base en blocs). */
function pyro(sc, x, y, z, haut = 3, graine = 1) {
  for (let k = 0; k < 7; k++) {
    const b = [x * 16 + (hache(k, 1, graine) - 0.5) * 10, y * 16, z * 16 + (hache(k, 2, graine) - 0.5) * 10];
    feu(sc, b, [(hache(k, 3, graine) - 0.5) * 0.4, 1, (hache(k, 4, graine) - 0.5) * 0.4], haut * 16 * (0.55 + 0.45 * hache(k, 5, graine)), 7 + 5 * hache(k, 6, graine));
  }
}

// ------------------------------------------------------------------ la planche de la salle
function plancheSalle() {
  const W = 1600, HA = 980, Hv = 600, Hr = 660, HT = 330, P = planche(W, 100 + HA + 40 + Hv + 40 + Hr + HT);
  const m = construireSalle();
  lumiereSalle(m);
  // les catcheurs dans la salle : deux sur le ring avec l'arbitre, un qui arrive sur la scène, un dans l'allée
  const acteurs = [
    [C.mysterio, { pos: pied(-2.6, 2, 0.8), lacet: 100, brasD: [-35, -20], brasG: [-35, 20] }],
    [C.taureau, { pos: pied(2.6, 2, -0.8), lacet: -80, brasD: [-40, -25], brasG: [-40, 25], tangage: 8 }],
    [arbitre(), { pos: pied(0.3, 2, 3.6), lacet: 180, brasD: [-90, -60], brasG: [0, 10] }],
    [C.demon, { pos: pied(0, 3, -27.5), lacet: 0, brasD: [-165, -20], brasG: [-165, 20] }],
    [C.vipere, { pos: pied(-0.6, 1, -15), lacet: 5, jambeD: [25], jambeG: [-25], brasD: [-20], brasG: [20] }],
  ];
  const lesActeurs = (sc, garde) => { for (const [c, o] of acteurs) if (!garde || garde(Math.floor(o.pos[0] / 16), Math.floor((o.pos[1] - 12) / 16), Math.floor(o.pos[2] / 16))) dessiner(sc, c, o); };

  // 1. vue du ciel, en coupe : on enlève les tribunes de devant
  {
    const sc = scene({ w: W, h: HA, lacet: -38, tangage: 38, echelle: 1.32, centre: [-2 * 16, 0, -9 * 16], pied: 545, fond: fondSalle(HA) });
    const garde = (x, y, z) => { const d = Math.max(Math.abs(x + 0.5), Math.abs(z + 0.5)) - 0.5; return !((x >= 12 || z >= 12) && d > 14); };
    rendreMonde(sc, m, { n: 4, garde, sansHerse: true });
    rendreGens(sc, m, garde);
    lesActeurs(sc, garde);
    for (const [x, s] of [[-12, 1], [-5, 2], [4, 3], [11, 4]]) pyro(sc, x, 3, -25.4, 3.2, s);
    faisceau(sc, [0, 15 * 16, 0], [0, 2 * 16, 0], 8 * 16, [255, 240, 210], 0.1);
    etiquette(sc, [0, 3.5 * 16, -4 * 16], "LE RING 12 X 12", { dy: -150, dx: -60 });
    etiquette(sc, [-6 * 16, 13 * 16, -35 * 16], "L'ECRAN GEANT", { dy: -24, dx: -150 });
    etiquette(sc, [10 * 16, 3 * 16, -27 * 16], "LA SCENE D'ENTREE : FLAMMES", { dy: 40, dx: 150 });
    etiquette(sc, [-3 * 16, 3 * 16, -30 * 16], "L'ARRIVEE : /CATCH", { dy: -60, dx: -230 });
    etiquette(sc, [0.5 * 16, 16, -18 * 16], "L'ALLEE", { dy: 60, dx: 110 });
    etiquette(sc, [-20 * 16, 9 * 16, -2 * 16], "LES TRIBUNES : PLEIN DE MONDE", { dy: -40, dx: -60 });
    etiquette(sc, [7 * 16, 1.2 * 16, 1 * 16], "LA TABLE DES COMMENTATEURS", { dy: 90, dx: 150 });
    etiquette(sc, [6.8 * 16, 1.2 * 16, -2.7 * 16], "LA CLOCHE", { dy: -50, dx: 160 });
    etiquette(sc, [-4 * 16, 1 * 16, 9.5 * 16], "LES BARRIERES", { dy: 60, dx: -80 });
    etiquette(sc, [-0.6 * 16, 3 * 16, -15 * 16], "UN CATCHEUR QUI ENTRE", { dy: -110, dx: -180 });
    coll(P, sc, 0, 100);
  }
  // 2. l'entrée : la scène, l'écran, les flammes
  const yV = 100 + HA;
  {
    const sc = scene({ w: 800, h: Hv, lacet: -22, tangage: 18, echelle: 3.3, centre: [0, 5 * 16, -27 * 16], pied: Hv * 0.6, fond: fondSalle(Hv) });
    const garde = (x, y, z) => z < -19;
    rendreMonde(sc, m, { n: 8, garde });
    rendreGens(sc, m, garde);
    lesActeurs(sc, garde);
    faisceau(sc, [-9 * 16, 16 * 16, -24 * 16], [0, 3 * 16, -27.5 * 16], 2 * 16, [220, 150, 255], 0.16);
    faisceau(sc, [9 * 16, 16 * 16, -24 * 16], [0, 3 * 16, -27.5 * 16], 2 * 16, [220, 150, 255], 0.16);
    for (const [x, s] of [[-12, 1], [-5, 2], [4, 3], [11, 4]]) pyro(sc, x, 3, -25.4, 3.4, s);
    legende(sc, "LES FLAMMES PARTENT QUAND TU ARRIVES");
    coll(P, sc, 0, yV + 40);
  }
  // 3. le ring de près, avec le public derrière
  {
    const sc = scene({ w: 800, h: Hv, lacet: -40, tangage: 13, echelle: 3.1, centre: [-1 * 16, 4 * 16, -1 * 16], pied: Hv * 0.52, fond: fondSalle(Hv) });
    const proche = sc.vue([7.5 * 16, 0, 7.5 * 16])[2];
    const garde = (x, y, z) => sc.vue([x * 16 + 8, y * 16 + 8, z * 16 + 8])[2] < proche + 20 || (x <= 9 && z <= 9 && x >= -10 && z >= -10);
    rendreMonde(sc, m, { n: 8, garde });
    rendreGens(sc, m, garde);
    lesActeurs(sc, garde);
    for (const [x, z] of [[-6, -6], [6, -6], [6, 6], [-6, 6]]) faisceau(sc, [x * 0.9 * 16, 12.4 * 16, z * 0.9 * 16], [x * 0.35 * 16, 2 * 16, z * 0.35 * 16], 2.6 * 16, [255, 244, 220], 0.07);
    legende(sc, "3 CORDES, 4 POTEAUX, LE TAPIS ET LA HERSE DE LUMIERE");
    coll(P, sc, 800, yV + 40);
  }
  bandeau(P, 0, 100, "LE CATCH", { t: 9 });
  texte(P.img, W, "UNE SALLE COMME A LA TELE : LE RING, LE PUBLIC, L'ENTREE EN FLAMMES. ON Y VA AVEC /CATCH", 18, 80, 3, BLANC);
  bandeau(P, yV, 40, "L'ENTREE : L'ECRAN GEANT ET LA PYRO", { t: 4 });
  texte(P.img, W, "LE RING : LA OU CA SE PASSE", 816, yV + 10, 4, JAUNE);

  // 4. des rings de toutes les tailles, dehors
  const yR = yV + 40 + Hv;
  bandeau(P, yR, 40, "LE KIT DE RING : DES RINGS AUSSI GRANDS QUE TU VEUX, OU TU VEUX", { t: 4 });
  {
    const mm = new Monde([0, 78], [-2, 8], [0, 34]);
    mm.remplir(0, -2, 0, 78, -2, 34, "terre");
    mm.remplir(0, -1, 0, 78, -1, 34, "solHerbe");
    const r1 = ring(mm, 3, 16, 6, 6, 0, { couleur: "bleu" });
    const r2 = ring(mm, 13, 10, 12, 12, 0, { couleur: "rouge" });
    const r3 = ring(mm, 30, 4, 24, 24, 0, { couleur: "or" });
    // un arbre et une maison pour l'échelle
    mm.remplir(70, 0, 4, 70, 3, 4, "bois"); mm.remplir(68, 4, 2, 72, 6, 6, "feuilles");
    const A = [60, 0, 12], B = [75, 0, 26];
    const coinsK = [[A[0], A[2]], [B[0], A[2]], [B[0], B[2]], [A[0], B[2]]];
    for (const [x, z] of coinsK) mm.boite([x - 0.2, 0, z - 0.2], [x + 0.2, 0.5, z + 0.2], "or");
    lumiereJour(mm);
    const sc = scene({ w: W, h: Hr, lacet: -24, tangage: 34, echelle: 1.18, centre: [39 * 16, 0, 17 * 16], pied: Hr * 0.5, fond: (x, y) => melange([120, 170, 220], [220, 230, 236], y / Hr) });
    rendreMonde(sc, mm, { n: 4 });
    // des gens dans chaque ring, pour l'échelle
    const st = joueur(SKINS.steve), ef = joueur(SKINS.efe), ka = joueur(SKINS.kai);
    const gens = [[st, 5.5, 2, 18.5, 60], [ef, 7, 2, 20, -120], [ka, 17, 2, 15, 90], [st, 21, 2, 17, -90], [ef, 19, 2, 19.5, 180],
      [ka, 38, 2, 12, 45], [st, 44, 2, 16, -60], [ef, 47, 2, 22, 200], [ka, 36, 2, 24, 120], [st, 41, 2, 19, 0]];
    for (const [j, x, y, z, l] of gens) corps(sc, j.os, j.parties, { pos: pied(x, y, z), lacet: l, brasD: [-20], brasG: [20] });
    // le kit en cours : deux coins posés, le contour en pointillés
    for (let k = 0; k < 4; k++) {
      const [ax, az] = coinsK[k], [bx, bz] = coinsK[(k + 1) % 4], n = Math.round(Math.hypot(bx - ax, bz - az) * 2);
      for (let q = 0; q < n; q += 2) trait(sc, [(ax + ((bx - ax) * q) / n) * 16, 1, (az + ((bz - az) * q) / n) * 16], [(ax + ((bx - ax) * (q + 1)) / n) * 16, 1, (az + ((bz - az) * (q + 1)) / n) * 16], [255, 236, 120], 1.2, 1);
    }
    for (const [x, z, s] of [[A[0], A[2], "1"], [B[0], B[2], "2"]]) {
      impact(sc, [x * 16, 8, z * 16], 9, [255, 220, 80]);
      etiquette(sc, [x * 16, 8, z * 16], "CLIC " + s, { dy: s === "1" ? -50 : -40, dx: s === "1" ? 0 : 70 });
    }
    corps(sc, st.os, st.parties, { pos: pied(B[0] + 1.2, 0, B[2] + 1), lacet: -140, brasD: [-70, 0], brasG: [10] });
    etiquette(sc, [r1.x0 * 16 + 48, 2 * 16, r1.z0 * 16], "PETIT : 6 X 6", { dy: -110, dx: -30 });
    etiquette(sc, [r2.x0 * 16 + 96, 2 * 16, r2.z0 * 16], "NORMAL : 12 X 12", { dy: -120, dx: -40 });
    etiquette(sc, [r3.x0 * 16 + 192, 2 * 16, r3.z0 * 16], "GEANT : 24 X 24, ET PLUS", { dy: -90 });
    etiquette(sc, [(A[0] + B[0]) * 8, 8, (A[2] + B[2]) * 8], "LE CONTOUR AVANT DE POSER", { dy: -170, dx: 40 });
    coll(P, sc, 0, yR + 40);
    // 5. comment ça marche
    const yT = yR + 40 + Hr;
    rect(P.img, W, 0, yT, W, P.H, [20, 16, 28]);
    const col = (x, y, titre, lignes) => {
      texte(P.img, W, titre, x, y, 4, JAUNE);
      let yy = y + 34;
      for (const l of lignes) yy = paragraphe(P.img, W, l, x, yy, 3, BLANC, 500, 1.7) + 6;
    };
    col(30, yT + 24, "Y ALLER", [
      "/CATCH : TU ARRIVES SUR LA SCENE, COMME UNE STAR, LES FLAMMES PARTENT",
      "/CATCH @JOUEUR : L'HOTE Y ENVOIE QUELQU'UN",
      "BOUTON ROUGE AU FOND DE LA SCENE OU /CATCH RETOUR : TU REVIENS PILE OU TU ETAIS",
    ]);
    col(560, yT + 24, "SUR PLACE", [
      "ON NE CASSE RIEN (MODE AVENTURE). LE PUBLIC EST TOUJOURS LA ET BOUGE UN PEU",
      "LE PUBLIC BOUGE ET SE LEVE SUR LES PRISES (SANS BRUIT DE FOULE)",
      "LA CLOCHE DE LA TABLE : CLIC DROIT = DING DING DING",
    ]);
    col(1090, yT + 24, "LE KIT DE RING", [
      "CLIC DROIT AU SOL = 1ER COIN, CLIC DROIT = 2E COIN : LE CONTOUR S'AFFICHE, UN 3E CLIC LE MONTE",
      "DE 5 X 5 A 40 X 40, CARRE OU RECTANGLE, PARTOUT (DEHORS, CHEZ TOI, AU VILLAGE)",
      "ACCROUPI + CLIC DROIT : LA COULEUR DES CORDES (ROUGE, BLEU, BLANC, OR, NOIR)",
      "CASSER UN BLOC DU RING (MEME EN SURVIE) : IL SE RANGE DANS LE KIT",
    ]);
  }
  ecrirePng(path.join(DOC, "planche-salle.png"), W, P.H, P.img);
}

// ------------------------------------------------------------------ la planche des tenues
/** Le fond du studio : sombre, avec la tache du projecteur au sol. */
const fondStudio = (w, h, py) => (x, y) => {
  const d = Math.hypot((x - w / 2) / (w * 0.42), (y - py) / (h * 0.09));
  const base = melange([30, 26, 42], [14, 12, 20], y / h);
  return d < 1 ? melange([86, 74, 110], base, d * d) : base;
};

function plancheTenues() {
  LUMIERE.mode = "studio";
  const W = 1600, H1 = 640, H2 = 560, P = planche(W, 100 + 40 + H1 + 40 + H2);
  bandeau(P, 0, 100, "LES 5 TENUES", { t: 9 });
  texte(P.img, W, "TORSE NU D'OFFICE ET GONFLE A BLOC : EPAULES RONDES, PECS, ABDOS EN TABLETTE, GROS BRAS, GROS MOLLETS", 18, 80, 3, BLANC);
  // 1. les 5, de face, chacun dans sa pose
  const liste = [
    ["slipNoir", SKINS.steve, { brasD: [-12, -16], brasG: [-12, 16] }],
    ["slipEtoile", SKINS.kai, { brasD: [-168, -28], brasG: [-168, 28], tete: [-12] }],
    ["shortLuchador", SKINS.efe, { brasD: [-88, -4], brasG: [-10, 12] }],
    ["jean", SKINS.efe, { brasD: [-150, 22], brasG: [-8, 10], tete: [6] }],
    ["collantFlammes", SKINS.steve, { brasD: [-28, -6], brasG: [-28, 6], tete: [10] }],
  ];
  bandeau(P, 100, 40, "DES SLIPS, UN SHORT, UN JEAN, UN COLLANT : 5 FACONS D'ETRE CATCHEUR", { t: 4 });
  liste.forEach(([cle, skin, pose], k) => {
    const T = TENUES[cle], w = 320;
    const sc = scene({ w, h: H1, lacet: -24, tangage: 6, echelle: 8.6, centre: [0, 20, 0], pied: H1 * 0.47, fond: fondStudio(w, H1, H1 * 0.79) });
    dessiner(sc, catcheur(skin, cle, null), { pos: [0, 12, 0], ...pose });
    rect(sc.img, w, 0, 0, w, 44, [18, 14, 24], 0.9);
    texte(sc.img, w, (k + 1) + ". " + T.nom, 12, 15, 3, JAUNE);
    rect(sc.img, w, 0, H1 - 92, w, H1, [18, 14, 24], 0.9);
    T.texte.forEach((l, q) => texte(sc.img, w, l, 14, H1 - 80 + q * 26, 3, BLANC));
    coll(P, sc, k * w, 140);
  });
  // 2. avant / après, de dos, les muscles de près, comment ça marche
  const y2 = 140 + H1;
  bandeau(P, y2, 40, "AVANT / APRES", { t: 4 });
  texte(P.img, W, "DE DOS", 416, y2 + 10, 4, JAUNE);
  texte(P.img, W, "LES MUSCLES DE PRES", 816, y2 + 10, 4, JAUNE);
  texte(P.img, W, "COMMENT CA MARCHE", 1216, y2 + 10, 4, JAUNE);
  {
    const sc = scene({ w: 400, h: H2, lacet: -18, tangage: 5, echelle: 7.2, centre: [0, 18, 0], pied: H2 * 0.5, fond: fondStudio(400, H2, H2 * 0.8) });
    const j = joueur(SKINS.steve);
    corps(sc, j.os, j.parties, { pos: [-13, 12, 0], brasD: [-5], brasG: [5] });
    dessiner(sc, catcheur(SKINS.steve, "slipNoir", null), { pos: [14, 12, 0], brasD: [-12, -16], brasG: [-12, 16] });
    trait(sc, [-3, 22, 0], [3, 22, 0], JAUNE, 1.5, 1);
    trait(sc, [1, 24.5, 0], [3.4, 22, 0], JAUNE, 1.5, 1); trait(sc, [1, 19.5, 0], [3.4, 22, 0], JAUNE, 1.5, 1);
    legende(sc, "AVANT, PUIS AVEC LA TENUE");
    coll(P, sc, 0, y2 + 40);
  }
  {
    const sc = scene({ w: 400, h: H2, lacet: 160, tangage: 6, echelle: 7.2, centre: [0, 18, 0], pied: H2 * 0.5, fond: fondStudio(400, H2, H2 * 0.8) });
    dessiner(sc, catcheur(SKINS.kai, "slipEtoile", null), { pos: [-13, 12, -3] });
    dessiner(sc, catcheur(SKINS.efe, "jean", null), { pos: [13, 12, 3] });
    legende(sc, "LE DOS AUSSI EST TAILLE");
    coll(P, sc, 400, y2 + 40);
  }
  {
    const sc = scene({ w: 400, h: H2, lacet: -22, tangage: 4, echelle: 16, centre: [0, 20, 0], pied: H2 * 0.5, fond: fondStudio(400, H2, H2 * 1.1) });
    dessiner(sc, catcheur(SKINS.steve, "slipNoir", null), { pos: [0, 12, 0], brasD: [-12, -16], brasG: [-12, 16] });
    etiquette(sc, [-9.5, 24, 2], "EPAULES RONDES", { dy: -46, dx: 30, t: 2 });
    etiquette(sc, [3, 22, 4.1], "PECS", { dy: -80, dx: 60, t: 2 });
    etiquette(sc, [1.4, 16, 3.2], "ABDOS", { dy: 10, dx: 120, t: 2 });
    etiquette(sc, [-9, 17, 3.6], "BICEPS", { dy: 70, dx: -20, t: 2 });
    legende(sc, "PEAU = COULEUR DU VISAGE");
    coll(P, sc, 800, y2 + 40);
  }
  {
    rect(P.img, W, 1200, y2 + 40, W, P.H, [20, 16, 28]);
    let y = y2 + 66;
    for (const l of [
      "LA TENUE SE MET DANS LA CASE DU PANTALON (OU CLIC DROIT AVEC EN MAIN)",
      "TORSE NU D'OFFICE : LE HAUT DE TON SKIN EST CACHE, TA PEAU PREND LA COULEUR DE TON VISAGE",
      "LE CORPS GONFLE TANT QUE TU L'AS SUR TOI : PLUS LARGE, GROS BRAS, PECS, ABDOS",
      "DANS UN RING OU A LA SALLE, LA TENUE EST TON ARMURE DE CATCHEUR. DEHORS, C'EST JUSTE DES HABITS",
      "LES BOTTES, GENOUILLERES ET POIGNETS VONT AVEC LA TENUE",
      "ON LES TROUVE DANS L'ONGLET DU MOD",
    ]) y = paragraphe(P.img, W, l, 1216, y, 3, BLANC, 370, 1.7) + 12;
  }
  ecrirePng(path.join(DOC, "planche-tenues.png"), W, P.H, P.img);
}

// ------------------------------------------------------------------ la planche des masques
const FICHES = {
  mysterio: { tenue: "shortLuchador", skin: SKINS.kai, lignes: ["INSPIRE DE REY MYSTERIO", "PRISE : LE 619", "PROVOC : LE SIGNE 619"] },
  vipere: { tenue: "slipNoir", skin: SKINS.steve, lignes: ["INSPIRE DE RANDY ORTON", "PRISE : LE RKO", "PROVOC : TAPE LE TAPIS"] },
  champion: { tenue: "jean", skin: SKINS.efe, lignes: ["INSPIRE DE JOHN CENA", "PRISE : LE AA", "PROVOC : TU ME VOIS PAS"] },
  demon: { tenue: "collantFlammes", skin: SKINS.steve, lignes: ["INSPIRE DE KANE", "PRISE : LE CHOKESLAM", "PROVOC : FEU AUX POTEAUX"] },
  taureau: { tenue: "slipEtoile", skin: SKINS.kai, lignes: ["INSPIRE DE ROMAN REIGNS", "PRISE : LE SPEAR", "PROVOC : GRATTE LE SOL"] },
};

function plancheMasques() {
  LUMIERE.mode = "studio";
  const W = 1600, H1 = 660, HT = 300, P = planche(W, 100 + H1 + 40 + HT);
  bandeau(P, 0, 100, "LES 5 MASQUES", { t: 9 });
  texte(P.img, W, "CHAQUE MASQUE DONNE SA PRISE DE FINITION ET SA PROVOC, INSPIREES DES VRAIS CATCHEURS", 18, 80, 3, BLANC);
  Object.entries(FICHES).forEach(([cle, F], k) => {
    const M = MASQUES[cle], w = 320;
    const sc = scene({ w, h: H1, lacet: -26, tangage: 5, echelle: 12.5, centre: [0, 24, 0], pied: H1 * 0.5, fond: fondStudio(w, H1, H1 * 0.9) });
    dessiner(sc, catcheur(F.skin, F.tenue, cle), { pos: [0, 12, 0], brasD: [-8, -14], brasG: [-8, 14], tete: [0, 0, 8] });
    rect(sc.img, w, 0, 0, w, 50, [18, 14, 24], 0.92);
    texte(sc.img, w, (k + 1) + ". " + M.nom, 12, 12, 5, M.couleurs[1][0] + M.couleurs[1][1] < 120 ? JAUNE : M.couleurs[1]);
    rect(sc.img, w, 0, H1 - 104, w, H1, [18, 14, 24], 0.92);
    F.lignes.forEach((l, q) => texte(sc.img, w, l, 14, H1 - 90 + q * 30, 3, q === 1 ? JAUNE : q === 0 ? GRIS : BLANC));
    coll(P, sc, k * w, 100);
  });
  const yT = 100 + H1;
  bandeau(P, yT, 40, "COMMENT CA MARCHE", { t: 4 });
  rect(P.img, W, 0, yT + 40, W, P.H, [20, 16, 28]);
  const col = (x, y, lignes) => { for (const l of lignes) y = paragraphe(P.img, W, l, x, y, 3, BLANC, 480, 1.7) + 10; };
  col(30, yT + 66, [
    "LE MASQUE SE MET SUR LA TETE (CASE DU CASQUE) ET LA CACHE TOUTE ENTIERE",
    "TU PEUX LE PORTER AVEC N'IMPORTE QUELLE TENUE",
    "MEME SANS TENUE, LE MASQUE DONNE SA PRISE",
  ]);
  col(560, yT + 66, [
    "CLIC DROIT SUR UN JOUEUR A 3 BLOCS OU MOINS : LA PRISE DE TON MASQUE. L'AUTRE EST EMPORTE DANS L'ANIMATION",
    "ACCROUPI + CLIC DROIT : LA PROVOC",
    "5 SECONDES ENTRE DEUX CLICS DROITS. LES TOUCHES S'AFFICHENT A GAUCHE DE L'ECRAN",
  ]);
  col(1090, yT + 66, [
    "PAS DE CINEMATIQUE : LA PRISE DURE 1 SECONDE, CHACUN GARDE SA CAMERA, ON SE BAT POUR DE VRAI",
    "UNE PRISE = 3 COEURS ET DEMI SUR UN CATCHEUR EN TENUE. ELLES MARCHENT DANS UN RING OU A LA SALLE",
    "LES PRISES DE PRES, IMAGE PAR IMAGE : PLANCHE-PRISES",
  ]);
  ecrirePng(path.join(DOC, "planche-masques.png"), W, P.H, P.img);
}

// ------------------------------------------------------------------ la planche des prises, image par image
const ADVERSAIRE = catcheur(SKINS.steve, null, null);
const Y = 2;
/** Debout sur le tapis (x, z en blocs), plus des options. */
const sur = (x, z, o = {}) => ({ pos: [x * 16, Y * 16 + 12 + (o.haut ?? 0), z * 16], ...o });
/** Allongé sur le dos ou sur le ventre : la hanche juste au-dessus du tapis. */
const auSol = (x, z, o = {}) => ({ pos: [x * 16, Y * 16 + 2.6, z * 16], ...o });

/** Un gros mot de bande dessinée, jaune bordé de noir, en haut à droite de l'image. */
function bulle(sc, s, t = 9, gauche = false) {
  const L = largeur(s, t), x = gauche ? 22 : sc.w - L - 22, y = 18;
  for (const [dx, dy] of [[-3, 0], [3, 0], [0, -3], [0, 3], [-3, -3], [3, 3], [3, -3], [-3, 3]]) texte(sc.img, sc.w, s, x + dx, y + dy, t, [20, 10, 10]);
  texte(sc.img, sc.w, s, x, y, t, [255, 220, 60]);
}

const RANGEES = [
  {
    titre: "MYSTERIO : LE 619", qui: "mysterio", camera: { lacet: -52, tangage: 12, echelle: 4.0, centre: [-4.6 * 16, 3.2 * 16, 0.6 * 16] },
    images: [
      ["1. IL EST SONNE DANS LES CORDES", (sc, c) => {
        dessiner(sc, ADVERSAIRE, sur(-5.15, 0, { lacet: -90, corps: [72], brasD: [-72, -6], brasG: [-72, 6], tete: [-40] }));
        dessiner(sc, c, sur(-1.4, 1.0, { lacet: -95, brasD: [-150, 28], brasG: [-150, -28] }));
      }],
      ["2. IL COURT VERS LES CORDES", (sc, c) => {
        dessiner(sc, ADVERSAIRE, sur(-5.15, 0, { lacet: -90, corps: [72], brasD: [-72, -6], brasG: [-72, 6], tete: [-40] }));
        dessiner(sc, c, sur(-3.4, 1.5, { lacet: -100, tangage: 16, jambeD: [45], jambeG: [-45], brasD: [-50], brasG: [40] }));
        vitesse(sc, [-3.0 * 16, 3.6 * 16, 1.6 * 16], [-0.6 * 16, 3.6 * 16, 2.0 * 16]);
      }],
      ["3. IL PASSE ENTRE LES CORDES EN TOURNANT", (sc, c) => {
        dessiner(sc, ADVERSAIRE, sur(-5.15, 0, { lacet: -90, corps: [72], brasD: [-72, -6], brasG: [-72, 6], tete: [-40] }));
        dessiner(sc, c, { pos: [-6.3 * 16, 3.35 * 16, 1.5 * 16], roulis: 90, lacet: 128, brasD: [-175], brasG: [-175], jambeD: [-10], jambeG: [10] });
        for (let k = 0; k < 8; k++) { const a0 = 0.2 + k * 0.16, a1 = a0 + 0.1; trait(sc, [-6.0 * 16 - Math.sin(a0) * 22, 3.35 * 16, 1.5 * 16 - Math.cos(a0) * 22], [-6.0 * 16 - Math.sin(a1) * 22, 3.35 * 16, 1.5 * 16 - Math.cos(a1) * 22], [255, 255, 255], 1, 0.7); }
      }],
      ["4. LES DEUX PIEDS DANS LA TETE", (sc, c) => {
        dessiner(sc, ADVERSAIRE, sur(-5.15, 0, { lacet: -90, corps: [78], brasD: [-80, -10], brasG: [-80, 10], tete: [-10] }));
        dessiner(sc, c, { pos: [-6.25 * 16, 3.3 * 16, 0.9 * 16], roulis: 90, lacet: 92, brasD: [-175], brasG: [-175] });
        impact(sc, [-6.0 * 16, 3.1 * 16, 0.1 * 16], 24);
        bulle(sc, "619 !");
      }],
    ],
  },
  {
    titre: "VIPERE : LE RKO", qui: "vipere", camera: { lacet: -38, tangage: 12, echelle: 4.1, centre: [0.3 * 16, 3.1 * 16, 0] },
    images: [
      ["1. ACCROUPI, IL TAPE LE TAPIS", (sc, c) => {
        dessiner(sc, c, sur(-1.6, 0.3, { lacet: 90, haut: -3, jambeD: [-25, -10], jambeG: [20, 10], corps: [52], brasD: [-22], brasG: [-115], tete: [-45] }));
        dessiner(sc, ADVERSAIRE, sur(2.0, -0.2, { lacet: 90, corps: [10], brasD: [-8], brasG: [-8] }));
        impact(sc, [-0.9 * 16, Y * 16, 0.0], 8, [255, 255, 255]);
      }],
      ["2. IL SE RETOURNE... IL SAUTE", (sc, c) => {
        dessiner(sc, ADVERSAIRE, sur(1.9, -0.1, { lacet: -90, brasD: [-30, -10], brasG: [-30, 10] }));
        dessiner(sc, c, sur(-0.4, 0, { haut: 14, lacet: 90, tangage: 22, brasD: [-150, 8], brasG: [-150, -8], jambeD: [35], jambeG: [12] }));
        vitesse(sc, [-1.6 * 16, 4.0 * 16, 0], [-3.4 * 16, 3.8 * 16, 0]);
      }],
      ["3. IL LUI ATTRAPE LA TETE", (sc, c) => {
        dessiner(sc, ADVERSAIRE, sur(1.55, -0.1, { lacet: -90, corps: [42], brasD: [-40], brasG: [-40], tete: [20] }));
        dessiner(sc, c, sur(0.55, 0.15, { haut: 10, lacet: 90, tangage: -40, brasD: [-120, 20], brasG: [-90, -10], jambeD: [-50], jambeG: [-40] }));
      }],
      ["4. FACE AU TAPIS", (sc, c) => {
        dessiner(sc, c, auSol(-0.3, 0.1, { lacet: 90, tangage: -60, jambeD: [-30], jambeG: [-25], brasD: [-80, 20], brasG: [-20] }));
        dessiner(sc, ADVERSAIRE, auSol(1.9, 0.0, { lacet: -90, tangage: 90, brasD: [-160], brasG: [-160] }));
        nuage(sc, [0.8 * 16, Y * 16 + 4, 0], 2.2 * 16, [240, 236, 230], 0.45);
        onde(sc, [0.6 * 16, Y * 16 + 0.5, 0], 2.4 * 16);
        impact(sc, [0.7 * 16, Y * 16 + 4, 0], 20);
        bulle(sc, "RKO !");
      }],
    ],
  },
  {
    titre: "CHAMPION : LE AA", qui: "champion", camera: { lacet: -38, tangage: 12, echelle: 4.0, centre: [0.3 * 16, 3.5 * 16, 0.2 * 16] },
    images: [
      ["1. TU ME VOIS PAS !", (sc, c) => {
        dessiner(sc, c, sur(-0.9, 0, { lacet: 75, brasD: [-148, 34], brasG: [-10, 8], tete: [0, 0, -10] }));
        dessiner(sc, ADVERSAIRE, sur(1.8, -0.2, { lacet: -95, brasD: [-60, -20], brasG: [-60, 20] }));
      }],
      ["2. SUR LES EPAULES", (sc, c) => {
        dessiner(sc, c, sur(0, 0, { lacet: 30, brasD: [-165, -6], brasG: [-160, 10], tete: [-8] }));
        dessiner(sc, ADVERSAIRE, { pos: [0.33 * 16, 61, -0.19 * 16], lacet: -60, tangage: 90, brasD: [-90], brasG: [-90] });
      }],
      ["3. IL LE FAIT BASCULER", (sc, c) => {
        dessiner(sc, c, sur(0, 0, { lacet: 30, corps: [22], brasD: [-80], brasG: [-60] }));
        dessiner(sc, ADVERSAIRE, { pos: [0.7 * 16, 50, 0.5 * 16], lacet: -60, tangage: 90, roulis: 70, brasD: [-150], brasG: [-40] });
        for (let k = 0; k < 7; k++) { const a0 = 0.3 + k * 0.2, a1 = a0 + 0.12; trait(sc, [Math.sin(a0) * 34, 3.4 * 16 + Math.cos(a0) * 30, 1.2 * 16], [Math.sin(a1) * 34, 3.4 * 16 + Math.cos(a1) * 30, 1.2 * 16], [255, 255, 255], 1, 0.7); }
      }],
      ["4. A PLAT DOS", (sc, c) => {
        dessiner(sc, c, sur(-0.6, -0.4, { lacet: 45, brasD: [-55, 10], brasG: [-10] }));
        dessiner(sc, ADVERSAIRE, auSol(1.2, 0.9, { lacet: -150, tangage: -90, brasD: [-20, -40], brasG: [-20, 40] }));
        nuage(sc, [1.0 * 16, Y * 16 + 4, 0.6 * 16], 2.4 * 16, [240, 236, 230], 0.45);
        onde(sc, [1.0 * 16, Y * 16 + 0.5, 0.6 * 16], 2.6 * 16);
        bulle(sc, "AA !");
      }],
    ],
  },
  {
    titre: "DEMON : LE CHOKESLAM", qui: "demon", camera: { lacet: -38, tangage: 12, echelle: 3.0, centre: [0, 3.7 * 16, 0] },
    images: [
      ["1. LA MAIN A LA GORGE", (sc, c) => {
        dessiner(sc, c, sur(-0.8, 0, { lacet: 90, brasD: [-95, 6], brasG: [-15, 10] }));
        dessiner(sc, ADVERSAIRE, sur(0.4, 0.15, { lacet: -90, haut: 1.5, brasD: [-80, 12], brasG: [-80, -12], tete: [-22] }));
      }],
      ["2. IL LE SOULEVE TRES HAUT", (sc, c) => {
        dessiner(sc, c, sur(-0.8, 0, { lacet: 90, brasD: [-176, 6], brasG: [-30, 15], tete: [-25] }));
        dessiner(sc, ADVERSAIRE, { pos: [-0.5 * 16, 61, 0.45 * 16], lacet: -90, brasD: [-150, -10], brasG: [-60, 20], jambeD: [-15], jambeG: [10], tete: [-15] });
      }],
      ["3. ET LE PLAQUE AU TAPIS", (sc, c) => {
        dessiner(sc, c, sur(-0.8, 0, { lacet: 90, corps: [38], brasD: [-58, 4], brasG: [-20, 15] }));
        dessiner(sc, ADVERSAIRE, auSol(0.5, 0.3, { lacet: -90, tangage: -90, brasD: [-10, -40], brasG: [-10, 40] }));
        nuage(sc, [0.9 * 16, Y * 16 + 4, 0.3 * 16], 2.6 * 16, [240, 236, 230], 0.45);
        onde(sc, [0.9 * 16, Y * 16 + 0.5, 0.3 * 16], 2.8 * 16);
        impact(sc, [1.0 * 16, Y * 16 + 3, 0.3 * 16], 18);
      }],
      ["4. LES 4 POTEAUX CRACHENT DU FEU", (sc, c) => {
        dessiner(sc, c, sur(-0.8, 0, { lacet: 60, brasD: [-160, -30], brasG: [-160, 30], tete: [-15] }));
        dessiner(sc, ADVERSAIRE, auSol(0.5, 0.3, { lacet: -90, tangage: -90, brasD: [-10, -40], brasG: [-10, 40] }));
        for (const [x, z] of [[-5.68, -5.68], [5.68, -5.68], [-5.68, 5.68], [5.68, 5.68]]) pyro(sc, x, Y + 1.75, z, 2.6, Math.abs(x * 3 + z));
        bulle(sc, "BOUM !", 8);
      }],
    ],
  },
  {
    titre: "TAUREAU : LE SPEAR", qui: "taureau", camera: { lacet: -128, tangage: 12, echelle: 3.4, centre: [-1.0 * 16, 3.0 * 16, -1.0 * 16] },
    images: [
      ["1. EN POSITION DANS LE COIN", (sc, c) => {
        dessiner(sc, c, sur(-3.0, -3.0, { lacet: 45, haut: -4, corps: [55], jambeD: [-35, -12], jambeG: [15, 12], brasD: [-40], brasG: [-75], tete: [-45] }));
        dessiner(sc, ADVERSAIRE, sur(0.9, 0.9, { lacet: -135, corps: [10], brasD: [-10], brasG: [-10] }));
      }],
      ["2. IL CHARGE A FOND", (sc, c) => {
        dessiner(sc, c, sur(-1.4, -1.4, { lacet: 45, haut: -3, corps: [55], jambeD: [-50], jambeG: [40], brasD: [-20], brasG: [-20], tete: [-45] }));
        dessiner(sc, ADVERSAIRE, sur(0.9, 0.9, { lacet: -135, brasD: [-50, -20], brasG: [-50, 20] }));
        vitesse(sc, [-2.0 * 16, 3.0 * 16, -2.0 * 16], [-4.0 * 16, 3.0 * 16, -4.0 * 16], 9);
      }],
      ["3. EN PLEIN VENTRE", (sc, c) => {
        dessiner(sc, c, sur(0.1, 0.1, { lacet: 45, haut: -3, corps: [68], jambeD: [-30], jambeG: [35], brasD: [-80, 20], brasG: [-80, -20], tete: [-45] }));
        dessiner(sc, ADVERSAIRE, sur(0.9, 0.9, { lacet: -135, haut: 8, corps: [70], tangage: -18, brasD: [-120], brasG: [-120], jambeD: [-20], jambeG: [-30] }));
        impact(sc, [0.65 * 16, 3.0 * 16, 0.65 * 16], 20);
      }],
      ["4. ET LES DEUX AU TAPIS", (sc, c) => {
        dessiner(sc, ADVERSAIRE, auSol(1.4, 1.4, { lacet: -135, tangage: -90, brasD: [-30, -50], brasG: [-30, 50] }));
        dessiner(sc, c, { pos: [0.6 * 16, Y * 16 + 6, 0.6 * 16], lacet: 45, tangage: 72, brasD: [-100, 20], brasG: [-100, -20], jambeD: [20], jambeG: [10] });
        nuage(sc, [1.1 * 16, Y * 16 + 4, 1.1 * 16], 2.6 * 16, [240, 236, 230], 0.45);
        onde(sc, [1.1 * 16, Y * 16 + 0.5, 1.1 * 16], 2.8 * 16);
        bulle(sc, "SPEAR !");
      }],
    ],
  },
];

function planchePrises() {
  const W = 1600, w = 400, h = 330, bande = 40, P = planche(W, 100 + RANGEES.length * (bande + h));
  bandeau(P, 0, 100, "LES PRISES DE FINITION", { t: 9 });
  texte(P.img, W, "PAS UNE CINEMATIQUE : EN 1 SECONDE, POUR DE VRAI. CHACUN GARDE SA CAMERA, TOUT LE MONDE LE VOIT", 18, 80, 3, BLANC);
  const m = construireSalle();
  lumiereSalle(m);
  RANGEES.forEach((R, r) => {
    const y0 = 100 + r * (bande + h);
    bandeau(P, y0, bande, R.titre, { t: 4, couleur: MASQUES[R.qui].couleurs[1][0] + MASQUES[R.qui].couleurs[1][1] < 120 ? JAUNE : MASQUES[R.qui].couleurs[1] });
    const sc = scene({ w, h, ...R.camera, pied: h * 0.5, fond: fondSalle(h) });
    LUMIERE.spots[3] = { p: ajoute(R.camera.centre, fois(sc.versCamera, 40 * 16)), r1: 1e6, r2: 2e6, c: [0.5, 0.47, 0.46] };
    const limite = sc.vue(R.camera.centre)[2] + 3.6 * 16;
    const garde = (x, y, z) => sc.vue([x * 16 + 8, y * 16 + 8, z * 16 + 8])[2] < limite;
    const gardeBoites = (bo) => sc.vue(fois(bo.a.map((v, i) => (v + bo.b[i]) / 2), 16))[2] < limite;
    rendreMonde(sc, m, { n: 8, garde, gardeBoites });
    rendreGens(sc, m, garde);
    const decor = sc.sauver(), c = C[R.qui];
    R.images.forEach(([legendeImage, dessin], k) => {
      sc.remettre(decor);
      dessin(sc, c);
      legende(sc, legendeImage, 2);
      rect(sc.img, w, w - 2, 0, w, h, [16, 14, 22]);
      coll(P, sc, k * w, y0 + bande);
    });
  });
  ecrirePng(path.join(DOC, "planche-prises.png"), W, P.H, P.img);
}

// ------------------------------------------------------------------ la planche du combat : les coups, les dégâts, le KO
const COEUR = [230, 40, 50];
/** Une ligne de texte avec des cœurs : "♥" en rouge, le reste dans la couleur donnée. */
function texteCoeurs(img, W, s, x, y, t, c) {
  for (const morceau of s.split(/(♥)/)) if (morceau) x = texte(img, W, morceau, x, y, t, morceau === "♥" ? COEUR : c) + (morceau === "♥" ? 0 : 0);
  return x;
}
/** Les dégâts qui sautent au-dessus de celui qui prend le coup. */
function degats(sc, P, s) {
  const [x, y] = sc.vue(P), t = 4, L = largeur(s, t);
  for (const [dx, dy] of [[-2, 0], [2, 0], [0, -2], [0, 2]]) texte(sc.img, sc.w, s, Math.round(x - L / 2) + dx, Math.round(y) + dy, t, [20, 10, 10]);
  texteCoeurs(sc.img, sc.w, s, Math.round(x - L / 2), Math.round(y), t, [255, 255, 255]);
}

const FORME_COEUR = [".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."];
/** Un cœur de la barre de vie (rempli : 0, 0.5 ou 1), k pixels par point. */
function coeur(img, W, x, y, k, rempli) {
  FORME_COEUR.forEach((l, j) => [...l].forEach((c, i) => {
    if (c !== "X") return;
    const plein = rempli >= 1 || (rempli >= 0.5 && i < 4);
    rect(img, W, x + i * k, y + j * k, x + (i + 1) * k, y + (j + 1) * k, plein ? [230, 40, 50] : [64, 30, 40]);
  }));
}
/** Le haut de l'écran des deux catcheurs : nom et 10 cœurs de chaque côté, le chrono au milieu. */
function hud(sc, nomA, pvA, nomB, pvB, chrono, fini) {
  const W = sc.w;
  rect(sc.img, W, 0, 0, W, 58, [10, 8, 16], 0.8);
  texte(sc.img, W, nomA, 12, 8, 3, JAUNE);
  texte(sc.img, W, nomB, W - 12 - largeur(nomB, 3), 8, 3, JAUNE);
  for (let i = 0; i < 10; i++) {
    coeur(sc.img, W, 12 + i * 17, 30, 2, Math.max(0, Math.min(1, (pvA - i * 2) / 2)));
    coeur(sc.img, W, W - 26 - i * 17, 30, 2, Math.max(0, Math.min(1, (pvB - i * 2) / 2)));
  }
  texte(sc.img, W, chrono, Math.round(W / 2 - largeur(chrono, 6) / 2), 13, 6, fini ? JAUNE : BLANC);
}
/** Un gros mot centré, bordé de noir. */
function crier(sc, s, y, t = 8, c = [255, 220, 60]) {
  const x = Math.round(sc.w / 2 - largeur(s, t) / 2);
  for (const [dx, dy] of [[-3, 0], [3, 0], [0, -3], [0, 3], [-3, -3], [3, 3], [3, -3], [-3, 3]]) texte(sc.img, sc.w, s, x + dx, y + dy, t, [20, 10, 10]);
  texte(sc.img, sc.w, s, x, y, t, c);
}

const MATCH = [
  ["2 CATCHEURS DANS LE RING : DING, LE CHRONO PART", "0:00", 20, 20, (sc) => {
    dessiner(sc, C.vipere, sur(-1.1, 0.2, { lacet: 90, corps: [12], brasD: [-62, -8], brasG: [-78, 10] }));
    dessiner(sc, C.mysterio, sur(1.2, -0.1, { lacet: -90, corps: [12], brasD: [-72, -10], brasG: [-56, 10] }));
    crier(sc, "DING !", 80);
  }],
  ["LE CHRONO MONTE TANT QUE PERSONNE N'EST KO", "0:47", 14, 9, (sc) => {
    dessiner(sc, C.vipere, sur(-0.5, 0, { lacet: 90, tangage: 8, brasD: [-92, 6], brasG: [-40, -10] }));
    dessiner(sc, C.mysterio, sur(0.95, 0, { lacet: -90, tangage: -12, brasD: [-30, -30], brasG: [-20, 30], tete: [-25] }));
    impact(sc, [0.6 * 16, Y * 16 + 40, 0.2 * 16], 13);
  }],
  ["KO : LE CHRONO S'ARRETE, LE GAGNANT S'AFFICHE", "1:12", 12, 0, (sc) => {
    dessiner(sc, C.mysterio, auSol(0.9, 0.3, { lacet: -90, tangage: -90, brasD: [-10, -50], brasG: [-10, 50] }));
    dessiner(sc, arbitre(), sur(-0.2, -0.9, { lacet: 60, brasD: [-90, -10], brasG: [-170, 10] }));
    dessiner(sc, C.vipere, sur(-1.1, 0.4, { lacet: 70, brasD: [-165, -25], brasG: [-165, 25], tete: [-15] }));
    rect(sc.img, sc.w, 0, 70, sc.w, 104, [10, 8, 16], 0.8);
    const s = "VIPERE GAGNE PAR KO EN 1:12";
    texte(sc.img, sc.w, s, Math.round(sc.w / 2 - largeur(s, 3) / 2), 80, 3, JAUNE);
  }],
];

const COUPS = {
  titre: "LES COUPS DE BASE : TOUT LE MONDE LES A AVEC LA TENUE", camera: { lacet: -38, tangage: 12, echelle: 4.0, centre: [0.3 * 16, 3.3 * 16, 0] },
  images: [
    ["CLIC GAUCHE : COUP DE POING", (sc) => {
      dessiner(sc, C.champion, sur(-0.6, 0, { lacet: 90, brasD: [-92, 6], brasG: [-40, -10], tangage: 8 }));
      dessiner(sc, ADVERSAIRE, sur(0.9, 0, { lacet: -90, tangage: -12, brasD: [-30, -30], brasG: [-20, 30], tete: [-25] }));
      impact(sc, [0.6 * 16, Y * 16 + 40, 0.2 * 16], 13);
      degats(sc, [0.9 * 16, Y * 16 + 50, 0], "-1/2 ♥");
    }],
    ["SAUT + CLIC GAUCHE : COUP DE PIED SAUTE", (sc) => {
      dessiner(sc, C.mysterio, { pos: [-0.5 * 16, Y * 16 + 26, 0.1 * 16], lacet: 90, tangage: -80, jambeD: [-20], jambeG: [-28], brasD: [-30, -40], brasG: [-30, 40] });
      dessiner(sc, ADVERSAIRE, sur(1.2, 0, { lacet: -90, tangage: -25, brasD: [-60, -40], brasG: [-60, 40], tete: [-20] }));
      impact(sc, [0.7 * 16, Y * 16 + 26, 0.1 * 16], 14);
      vitesse(sc, [-1.2 * 16, Y * 16 + 26, 0], [-3.0 * 16, Y * 16 + 26, 0]);
      degats(sc, [1.2 * 16, Y * 16 + 50, 0], "-1 ♥");
    }],
    ["COURSE + CLIC GAUCHE : CORDE A LINGE", (sc) => {
      dessiner(sc, C.taureau, sur(-0.4, 0.3, { lacet: 90, tangage: 12, brasD: [-10, -88], brasG: [-30], jambeD: [40], jambeG: [-40] }));
      dessiner(sc, ADVERSAIRE, { pos: [0.6 * 16, Y * 16 + 22, -0.3 * 16], lacet: -90, tangage: -70, brasD: [-150], brasG: [-140], jambeD: [-30], jambeG: [-10] });
      vitesse(sc, [-1.2 * 16, Y * 16 + 30, 0.3 * 16], [-3.0 * 16, Y * 16 + 30, 0.3 * 16]);
      degats(sc, [0.6 * 16, Y * 16 + 56, -0.3 * 16], "-1 ♥ ET AU TAPIS");
    }],
    ["A 0 COEUR : KO (PAS MORT)", (sc) => {
      dessiner(sc, ADVERSAIRE, auSol(0.8, 0.3, { lacet: -90, tangage: -90, brasD: [-10, -50], brasG: [-10, 50] }));
      dessiner(sc, arbitre(), sur(0.6, -1.4, { lacet: 10, haut: -4, corps: [30], brasD: [-170, -10], brasG: [-40] }));
      dessiner(sc, C.vipere, sur(-1.4, 0.8, { lacet: 60, brasD: [-165, -25], brasG: [-165, 25], tete: [-15] }));
      for (let k = 0; k < 6; k++) impact(sc, [(0.8 + Math.cos(k) * 0.5) * 16, Y * 16 + 12 + (k % 2) * 3, (0.3 + Math.sin(k) * 0.5) * 16], 3, [255, 240, 120]);
      bulle(sc, "KO !");
      texte(sc.img, sc.w, "DING DING DING", 20, 24, 3, [255, 220, 120]);
    }],
  ],
};

function plancheCombat() {
  const W = 1600, w = 400, h = 330, bande = 40, HT = 470, P = planche(W, 100 + bande + h + bande + h + bande + HT);
  bandeau(P, 0, 100, "LE COMBAT", { t: 9 });
  texte(P.img, W, "PAS DES ANIMATIONS : ON SE BAT POUR DE VRAI, CHACUN AVEC SA CAMERA. PAS DE LIMITE : LE CHRONO MONTE JUSQU'AU KO", 18, 80, 3, BLANC);
  const m = construireSalle();
  lumiereSalle(m);
  bandeau(P, 100, bande, COUPS.titre, { t: 4 });
  const sc = scene({ w, h, ...COUPS.camera, pied: h * 0.5, fond: fondSalle(h) });
  LUMIERE.spots[3] = { p: ajoute(COUPS.camera.centre, fois(sc.versCamera, 40 * 16)), r1: 1e6, r2: 2e6, c: [0.5, 0.47, 0.46] };
  const limite = sc.vue(COUPS.camera.centre)[2] + 3.6 * 16;
  const garde = (x, y, z) => sc.vue([x * 16 + 8, y * 16 + 8, z * 16 + 8])[2] < limite;
  rendreMonde(sc, m, { n: 8, garde, gardeBoites: (bo) => sc.vue(fois(bo.a.map((v, i) => (v + bo.b[i]) / 2), 16))[2] < limite });
  rendreGens(sc, m, garde);
  const decor = sc.sauver();
  COUPS.images.forEach(([leg, dessin], k) => {
    sc.remettre(decor);
    dessin(sc);
    legende(sc, leg, 2);
    rect(sc.img, w, w - 2, 0, w, h, [16, 14, 22]);
    coll(P, sc, k * w, 100 + bande);
  });
  // le match : pas de limite de temps, le chrono monte jusqu'au KO
  const yM = 100 + bande + h, wm = 534;
  bandeau(P, yM, bande, "LE MATCH : PAS DE LIMITE DE TEMPS, LE CHRONO MONTE JUSQU'A CE QUE QUELQU'UN SOIT KO", { t: 4 });
  const sm = scene({ w: wm, h, ...COUPS.camera, echelle: 4.3, pied: h * 0.58, fond: fondSalle(h) });
  LUMIERE.spots[3] = { p: ajoute(COUPS.camera.centre, fois(sm.versCamera, 40 * 16)), r1: 1e6, r2: 2e6, c: [0.5, 0.47, 0.46] };
  const limM = sm.vue(COUPS.camera.centre)[2] + 3.6 * 16;
  const gardeM = (x, y, z) => sm.vue([x * 16 + 8, y * 16 + 8, z * 16 + 8])[2] < limM;
  rendreMonde(sm, m, { n: 8, garde: gardeM, gardeBoites: (bo) => sm.vue(fois(bo.a.map((v, i) => (v + bo.b[i]) / 2), 16))[2] < limM });
  rendreGens(sm, m, gardeM);
  const decorM = sm.sauver();
  MATCH.forEach(([leg, chrono, pvA, pvB, dessin], k) => {
    sm.remettre(decorM);
    dessin(sm);
    hud(sm, "VIPERE", pvA, "MYSTERIO", pvB, chrono, k === 2);
    legende(sm, leg, 2);
    rect(sm.img, wm, wm - 2, 0, wm, h, [16, 14, 22]);
    coll(P, sm, Math.min(k * 533, W - wm), yM + bande);
  });
  // le tableau des dégâts
  const yT = yM + bande + h;
  bandeau(P, yT, bande, "LES DEGATS (SUR UN CATCHEUR EN TENUE + MASQUE, 10 COEURS)", { t: 4 });
  rect(P.img, W, 0, yT + bande, W, P.H, [20, 16, 28]);
  const lignes = [
    ["COUP DE POING", "CLIC GAUCHE", "1/2 ♥", "UN COUP TOUTES LES 0,7 S"],
    ["COUP DE PIED SAUTE", "SAUTER + CLIC GAUCHE", "1 ♥", "IL RECULE FORT"],
    ["CORDE A LINGE", "COURIR + CLIC GAUCHE", "1 ♥", "IL TOMBE AU TAPIS 1 S"],
    ["LA PRISE DU MASQUE", "CLIC DROIT TOUT PRES", "3 ♥ ET DEMI", "UNE TOUTES LES 5 S"],
    ["LA PROVOC", "ACCROUPI + CLIC DROIT", "0", "UNE TOUTES LES 5 S"],
  ];
  const cols = [30, 380, 790, 1040];
  ["LE COUP", "COMMENT", "DEGATS", "EN PLUS"].forEach((s, i) => texte(P.img, W, s, cols[i], yT + bande + 22, 3, GRIS));
  lignes.forEach((l, j) => l.forEach((s, i) => texteCoeurs(P.img, W, s, cols[i], yT + bande + 56 + j * 36, 3, i === 0 ? JAUNE : BLANC)));
  let y = yT + bande + 56 + lignes.length * 36 + 18;
  for (const s of [
    "LE MATCH PART TOUT SEUL QUAND 2 CATCHEURS EN TENUE SONT DANS LE MEME RING (DING). LE CHRONO PART DE 0:00 ET MONTE SANS LIMITE JUSQU'AU KO. A LA SALLE, IL S'AFFICHE AUSSI SUR L'ECRAN GEANT",
    "LES PRISES DURENT 1 SECONDE : L'AUTRE EST VRAIMENT SOULEVE, JETE, PLAQUE. CHACUN GARDE SA CAMERA",
    "LA TENUE + LE MASQUE = L'ARMURE DU CATCHEUR, DANS UN RING OU A LA SALLE. LES AUTRES ARMURES NE COMPTENT PAS DANS LE RING. SANS TENUE, TU PRENDS 3 FOIS PLUS",
    "A 0 COEUR DANS UN RING : TU NE MEURS PAS. KO 5 S AU TAPIS, LA CLOCHE SONNE, PUIS TU TE RELEVES AVEC 5 COEURS",
  ]) y = paragraphe(P.img, W, s, 30, y, 3, BLANC, 1540, 1.7) + 8;
  ecrirePng(path.join(DOC, "planche-combat.png"), W, P.H, P.img);
}

const quoi = process.argv[2];
if (!quoi || quoi === "salle") plancheSalle();
if (!quoi || quoi === "tenues") plancheTenues();
if (!quoi || quoi === "masques") plancheMasques();
if (!quoi || quoi === "prises") planchePrises();
if (!quoi || quoi === "combat") plancheCombat();
console.log("ok");
