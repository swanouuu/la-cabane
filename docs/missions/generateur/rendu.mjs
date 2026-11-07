// Les planches des missions : pour chaque série (WOOHP, ANBU), les trois missions l'une sous l'autre. Chaque mission :
// la grande vue de la scène (en coupe quand il le faut), un gros plan, et sa fiche (l'histoire, l'objectif, ce qui la
// rend différente, la commande). Puis la planche des règles communes.
// node rendu.mjs [whoop|anbu|regles] → ../planche-whoop.png, ../planche-anbu.png, ../planche-regles.png
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, POLICE } from "../../spies/generateur/moteur.mjs";
import { icones } from "./icones.mjs";
import { skins } from "./skins.mjs";
import {
  sceneCiel, sceneNuit, rendreMonde, dessinerPerso, dessinerChien, trait2d, etiquette, texte, largeur, rect, fois, ajoute,
  ambiance, halos, bulle, brume, filtreNuit, filtreChaud, cube, Monde,
} from "./commun.mjs";
import { bloc, murs, garde } from "./batir.mjs";
import { glamtek, megamall, ile } from "./whoop.mjs";
import { brume as villageBrume, foret, chateau } from "./anbu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const IC = icones(), SK = skins();

/** Les persos, les chiens, les caméras, les lignes (lasers, cordes), les halos et les bulles d'une vue. */
function peupler(sc, m, dans) {
  for (const pe of m.persos) {
    if (pe.fantome || !dans(pe.x, pe.y + 1, pe.z)) continue;
    if (pe.chien) dessinerChien(sc, m, pe);
    else dessinerPerso(sc, m, { ...pe, skin: SK[pe.skin] }, IC);
  }
  for (const c of m.cameras ?? []) {
    if (!dans(...c.p)) continue;
    const p = fois(c.p, 16), a = (c.lacet * Math.PI) / 180, d = [Math.sin(a), 0, Math.cos(a)];
    cube(sc, m, p, [2.6, 2, 2.6], [220, 220, 226]);
    cube(sc, m, ajoute(p, fois(d, 4)), [1.6, 1.6, 1.6], [40, 40, 46]);
    cube(sc, m, ajoute(p, fois(d, 5.6)), [0.8, 0.8, 0.8], [255, 40, 40], true);
  }
  for (const l of m.lignes) {
    if (!dans(...l.a) && !dans(...l.b)) continue;
    const A = fois(l.a, 16), B = fois(l.b, 16);
    if (l.laser) { trait2d(sc, A, B, [255, 60, 60], Math.max(1, sc.echelle * 1.2), 0.35); trait2d(sc, A, B, [255, 200, 200], 0, 1); }
    else trait2d(sc, A, B, l.couleur ?? [60, 50, 40], l.e ?? 0, l.alpha ?? 1);
  }
  halos(sc, m, m.nuit ? 1 : 0.5);
  for (const pe of m.persos) {
    if (!pe.bulle || !dans(pe.x, pe.y + 1, pe.z)) continue;
    bulle(sc, [pe.x, pe.y + 2.3 * (pe.taille ?? 1), pe.z], pe.bulle, pe.bulle === "!" ? {} : { encre: [200, 150, 0] });
  }
}

/** Une vue d'une mission. o : lacet, tangage, echelle, w, h, pied, centre (en blocs), n, clip, dans, etiquettes. */
function vue(m, o) {
  ambiance(o.ambiance ?? m.ambiance ?? "jour");
  const nuit = (o.ambiance ?? m.ambiance) === "nuit" || (o.ambiance ?? m.ambiance) === "neons";
  m.nuit = nuit;
  const prm = { lacet: o.lacet, tangage: o.tangage, echelle: o.echelle, w: o.w, h: o.h, pied: o.pied, centre: fois(o.centre ?? [0, 0, 0], 16) };
  const sc = nuit ? sceneNuit({ ...prm, ...(m.ciel ?? {}) }) : sceneCiel({ ...prm, ...(m.ciel ?? {}) });
  sc.echelle = o.echelle;
  const clip = (x, y, z) => (!m.coupe || m.coupe(x, y, z)) && (!o.clip || o.clip(x, y, z));
  rendreMonde(sc, m, { n: o.n ?? 4, clip });
  peupler(sc, m, o.dans ?? (() => true));
  if (m.brume) brume(sc, m.brume, 2);
  if (nuit) filtreNuit(sc, 1); else filtreChaud(sc, 0.35);
  if (o.etiquettes !== false) for (const [x, y, z, s, dy, couleur] of m.etiquettes) {
    if (o.dans && !o.dans(x, y, z)) continue;
    etiquette(sc, fois([x, y, z], 16), s, { t: o.tEtiq ?? 2, dy: dy ?? -40, couleur: couleur ?? (m.serie === "anbu" ? [150, 210, 255] : [255, 150, 210]) });
  }
  return sc;
}

const coller = (img, W, sc, x0, y0) => { for (let y = 0; y < sc.h; y++) sc.img.copy(img, ((y0 + y) * W + x0) * 4, y * sc.w * 4, (y + 1) * sc.w * 4); };

/** Une fiche : titre, lignes (« # » en tête = intertitre). */
function fiche(img, W, x0, y0, w, lignes, couleur) {
  let y = y0;
  for (const l of lignes) {
    if (l.startsWith("# ")) { y += 6; texte(img, W, l.slice(2), x0, y, 3, [...couleur, 255]); y += 26; continue; }
    // coupe la ligne si elle déborde
    const mots = l.split(" ");
    let cur = "";
    for (const mo of mots) {
      if (largeur(cur + " " + mo, 2) > w && cur) { texte(img, W, cur, x0, y, 2, [236, 226, 220, 255]); y += 19; cur = "  " + mo; }
      else cur = cur ? cur + " " + mo : mo;
    }
    texte(img, W, cur, x0, y, 2, [236, 226, 220, 255]); y += 19;
  }
}

// ------------------------------------------------------------------ une mission sur la planche
function mission(img, W, y0, { m, titre, sousTitre, grande, gros, lignes, couleur }) {
  const GW = 1000, GH = 700, PW = W - GW;
  rect(img, W, 0, y0, W, y0 + 56, [24, 18, 30]);
  rect(img, W, 0, y0, W, y0 + 4, couleur);
  texte(img, W, titre, 18, y0 + 14, 5, [...couleur, 255]);
  texte(img, W, sousTitre, 24 + largeur(titre, 5) + 16, y0 + 26, 2, [230, 220, 230, 255]);
  const g = vue(m, { w: GW, h: GH, ...grande });
  coller(img, W, g, 0, y0 + 56);
  const p = vue(m, { w: PW, h: 330, n: 8, tEtiq: 2, ...gros });
  coller(img, W, p, GW, y0 + 56);
  rect(img, W, GW, y0 + 56 + 330, W, y0 + 56 + GH, [30, 24, 36]);
  fiche(img, W, GW + 18, y0 + 56 + 330 + 10, PW - 36, lignes, couleur);
  return y0 + 56 + GH;
}

function planche(nom, titre, sousTitre, missions, couleur) {
  const W = 1600, H = 110 + missions.length * 756;
  const img = Buffer.alloc(W * H * 4);
  rect(img, W, 0, 0, W, H, [18, 14, 22]);
  texte(img, W, titre, 22, 18, 9, [...couleur, 255]);
  texte(img, W, sousTitre, 22, 76, 2, [236, 220, 230, 255]);
  let y = 110;
  for (const mi of missions) y = mission(img, W, y, { ...mi, couleur });
  ecrirePng(path.join(ICI, "..", nom), W, H, img);
  console.log(nom, "faite");
}

// ------------------------------------------------------------------ WOOHP
const ROSE = [255, 120, 200];
function plancheWhoop() {
  const g = glamtek(POLICE), mm = megamall(POLICE), il = ile(POLICE);
  for (const m of [g, mm, il]) m.serie = "whoop";
  planche("planche-whoop.png", "MISSIONS WOOHP", "/MISSION WHOOP 1, 2, 3 : COMME LE /WHOOP (LE BRUIT, ELLES PARTENT EN ARRIERE, LE TOBOGGAN) MAIS ELLES ATTERRISSENT DANS LA MISSION. TOI ET UN POTE : LES MECHANTS.", [
    {
      m: g, titre: "1. GLAMTEK", sousTitre: "LA TOUR DE LA MARQUE DE MAQUILLAGE (LE JOUR)",
      grande: { lacet: -32, tangage: 24, echelle: 1.02, pied: 410, centre: [-5, 10, 6] },
      gros: { lacet: -22, tangage: 12, echelle: 1.75, pied: 215, centre: [-2, 16, 0], clip: (x, y, z) => y >= 11 && y <= 23, dans: (x, y, z) => y >= 11.5 && y <= 23 },
      lignes: [
        "# L'HISTOIRE",
        "LA PATRONNE DE GLAMTEK VEND UN ROUGE A LEVRES QUI HYPNOTISE LES CLIENTES. VOLEZ LA FORMULE DANS SON LABO, TOUT EN HAUT.",
        "# CE QUI LA REND DIFFERENTE",
        "- LES LASERS : LES TOUCHER = ALARME, TOUS LES AGENTS ARRIVENT",
        "- LA CAMERA AU PLAFOND VOIT AUSSI",
        "- LES EMPLOYEES NE TAPENT PAS, MAIS ELLES CRIENT",
        "# SORTIE",
        "LE TOIT : LE WOOHP VOUS RECUPERE A L'HELIPORT",
      ],
    },
    {
      m: mm, titre: "2. LE MEGA MALL", sousTitre: "LE CENTRE COMMERCIAL, LA NUIT, FERME",
      grande: { lacet: -30, tangage: 34, echelle: 0.95, pied: 410, centre: [-2, 2, 4] },
      gros: { lacet: -28, tangage: 22, echelle: 2.6, pied: 230, centre: [0, -5, 0], clip: (x, y, z) => y <= -2 && x < 6 && z < 6, dans: (x, y, z) => y <= -2, etiquettes: false },
      lignes: [
        "# L'HISTOIRE",
        "UN STYLISTE RATE A TRANSFORME LES MANNEQUINS DU MALL EN ROBOTS. TROUVEZ LES 3 PUCES DE CONTROLE DANS LES BOUTIQUES, PUIS DESCENDEZ DANS SON ATELIER, SOUS LA FONTAINE.",
        "# CE QUI LA REND DIFFERENTE",
        "- LES MANNEQUINS NE BOUGENT QUE QUAND PERSONNE NE LES REGARDE : RESTEZ DOS A DOS",
        "- LE VIGILE ET SA LAMPE : SON FAISCEAU TE REPERE",
        "# EN GROS PLAN : L'ATELIER SOUS LA FONTAINE (TOI)",
      ],
    },
    {
      m: il, titre: "3. L'ILE", sousTitre: "LE REPAIRE DANS LE VOLCAN, LA FUSEE (LE JOUR)",
      grande: { lacet: -36, tangage: 30, echelle: 0.92, pied: 430, centre: [-2, 4, 2] },
      gros: { lacet: -36, tangage: 30, echelle: 1.55, pied: 215, centre: [-5, 5, -3], dans: (x, y, z) => x > -20 && z > -20 && x < 14 && z < 14, etiquettes: false },
      lignes: [
        "# L'HISTOIRE",
        "UN GENIE DU MAL FINIT SA FUSEE-LASER DANS SON VOLCAN. SABOTEZ LES 3 RESERVOIRS AVANT LA FIN DU COMPTE A REBOURS, ET FILEZ AU SOUS-MARIN.",
        "# CE QUI LA REND DIFFERENTE",
        "- UN COMPTE A REBOURS (15 MIN) : A ZERO, LA FUSEE DECOLLE, C'EST RATE",
        "- LES PROJECTEURS BALAIENT LA PLAGE",
        "- LES SBIRES ONT DES PISTOLETS LASER",
      ],
    },
  ], ROSE);
}

// ------------------------------------------------------------------ ANBU
const BLEU = [130, 200, 255];
function plancheAnbu() {
  const b = villageBrume(), f = foret(), c = chateau();
  for (const m of [b, f, c]) m.serie = "anbu";
  planche("planche-anbu.png", "MISSIONS ANBU", "/MISSION ANBU 1, 2, 3 : DES MISSIONS D'INFILTRATION A LA NARUTO. TOI ET UN POTE, VOUS JOUEZ LES MECHANTS.", [
    {
      m: b, titre: "1. LE VILLAGE DE LA BRUME", sousTitre: "UN VILLAGE NINJA SUR UN LAC (LA NUIT)",
      grande: { lacet: -32, tangage: 30, echelle: 0.98, pied: 420, centre: [0, 4, 2] },
      gros: { lacet: -38, tangage: 16, echelle: 2.3, pied: 250, centre: [1, 12, 1], dans: (x, y, z) => Math.abs(x) < 9 && Math.abs(z) < 9, etiquettes: false },
      lignes: [
        "# L'HISTOIRE",
        "LE CHEF D'UN VILLAGE ENNEMI GARDE UN ROULEAU INTERDIT EN HAUT DE SA TOUR. ENTREZ PAR LES ROSEAUX, PRENEZ-LE, RESSORTEZ PAR LE TORII SANS VOUS FAIRE VOIR.",
        "# CE QUI LA REND DIFFERENTE",
        "- LA NUIT ET LA BRUME : LES GARDES VOIENT MOINS LOIN, SAUF SOUS LES LANTERNES",
        "- ACCROUPI DANS LES ROSEAUX OU DANS L'EAU SOUS LES PONTONS = INVISIBLE",
        "- LES VILLAGEOIS APPELLENT LES GARDES",
      ],
    },
    {
      m: f, titre: "2. LA FORET DES PIEGES", sousTitre: "DES ARBRES GEANTS, UN CAMP DE DESERTEURS (LE JOUR)",
      grande: { lacet: -30, tangage: 34, echelle: 0.9, pied: 380, centre: [0, 4, 6] },
      gros: { lacet: -26, tangage: 36, echelle: 2.0, pied: 200, centre: [1, 0, -14], clip: (x, y, z) => !(z > -10 && y > 0) && !(f.get(x, y, z) === "tronc" && z > -14), dans: (x, y, z) => Math.abs(x) < 13 && z > -26 && z < -4, etiquettes: false },
      lignes: [
        "# L'HISTOIRE",
        "DES NINJAS DESERTEURS ONT ENLEVE LE FILS D'UN SEIGNEUR. ILS LE GARDENT DANS LEUR CAMP, AU COEUR DE LA FORET. RAMENEZ-LE VIVANT AU DEPART.",
        "# CE QUI LA REND DIFFERENTE",
        "- DES PIEGES : FILS TENDUS, TROUS A PIEUX, TRONC QUI TOMBE",
        "- ILS GUETTENT DANS LES ARBRES ET TE TOMBENT DESSUS",
        "- L'ESCORTE : L'OTAGE TE SUIT, S'ILS LE REPRENNENT IL FAUT Y RETOURNER",
      ],
    },
    {
      m: c, titre: "3. LE CHATEAU DU SEIGNEUR", sousTitre: "UN CHATEAU JAPONAIS, LES DOUVES, LE DONJON (LA NUIT)",
      grande: { lacet: -30, tangage: 30, echelle: 0.98, pied: 430, centre: [0, 6, -2] },
      gros: { lacet: -24, tangage: 16, echelle: 2.5, pied: 250, centre: [0, 15, -13], clip: (x, y, z) => !(z >= -11 && y >= 11), dans: (x, y, z) => Math.abs(x) < 9 && z < -10 && z > -22 && y > 6, etiquettes: false },
      lignes: [
        "# L'HISTOIRE",
        "LE SEIGNEUR DU CHATEAU A TRAHI LE VILLAGE : IL A SIGNE UN PACTE AVEC L'ENNEMI. TROUVEZ LE PACTE DANS LE DONJON ET RESSORTEZ AVANT L'AUBE.",
        "# CE QUI LA REND DIFFERENTE",
        "- LES ARCHERS SUR LES REMPARTS TIRENT DE LOIN",
        "- LE CHIEN TE SENT MEME ACCROUPI",
        "- LE GONG : SI UN GARDE Y ARRIVE, TOUT LE CHATEAU EST EN ALERTE",
      ],
    },
  ], BLEU);
}

const quoi = process.argv[2] ?? "tout";
if (quoi === "anbu" || quoi === "tout") plancheAnbu();
if (quoi === "whoop" || quoi === "tout") plancheWhoop();

// ------------------------------------------------------------------ les règles communes
/** Une petite scène d'exemple : un sol, un bout de mur, des persos. */
function vignette(construire, o) {
  const m = new Monde([-8, 8], [-2, 6], [-6, 6]);
  for (let x = -8; x <= 8; x++) for (let z = -6; z <= 6; z++) { m.set(x, -2, z, "terre"); m.set(x, -1, z, (x + z) & 1 ? "carrelage" : "pave"); }
  m.ambiance = "jour";
  construire(m);
  return vue(m, { lacet: -28, tangage: 30, echelle: 2.6, w: 500, h: 300, pied: 190, centre: [0, 1, 0], n: 8, etiquettes: false, ...o });
}

function plancheRegles() {
  const V = [
    ["IL FAIT SA RONDE", "IL SUIT SON CHEMIN. LE ROUGE AU SOL : CE QU'IL VOIT.", (m) => {
      bloc(m, 2, 0, -3, 2, 2, 1, "pierreTaillee");
      garde(m, { skin: "agent", x: -4, y: 0, z: -1, lacet: 60, pose: "porte", arme: "matraque", vue: { portee: 7, angle: 45 }, ronde: [[-6, -1], [-6, 4], [6, 4], [6, -4], [-1, -4]] });
      garde(m, { skin: "espionneVerte", x: 4.5, y: 0, z: -1.5, lacet: -90, pose: "repos" });
    }],
    ["IL TE VOIT : !", "IL TE COURT DESSUS ET TAPE. SES COPAINS AUSSI.", (m) => {
      garde(m, { skin: "ninjaBrume", x: -4, y: 0, z: 0, lacet: 90, pose: "porte", arme: "kunai", bulle: "!", vue: { portee: 9, angle: 45 } });
      garde(m, { skin: "anbu1", x: 3, y: 0, z: 0.5, lacet: -90, pose: "porte", arme: "katana" });
    }],
    ["TU TE CACHES : ?", "IL CHERCHE OU IL T'A VU, PUIS REPREND SA RONDE.", (m) => {
      bloc(m, 0, 0, -3, 0, 2, 3, "boisJapon");
      garde(m, { skin: "samourai", x: -4, y: 0, z: 0, lacet: 80, pose: "porte", arme: "lance", bulle: "?", vue: { portee: 8, angle: 45 } });
      garde(m, { skin: "anbu2", x: 2, y: 0, z: 1, lacet: -100, pose: "croise" });
    }],
    ["PAR DERRIERE : KO", "UN COUP DANS LE DOS AVANT QU'IL TE VOIE = KO.", (m) => {
      garde(m, { skin: "sbire", x: -1, y: 0, z: 0, lacet: 90, pose: "porte", arme: "fusil_laser", bulle: "ZZZ", vue: { portee: 7, angle: 45 } });
      garde(m, { skin: "espionneRouge", x: -3.2, y: 0, z: 0, lacet: 90, pose: "tend" });
    }],
    ["ATTRAPE : LA CELLULE", "PAS DE MORT : A 2 COEURS, TU ES ATTRAPE.", (m) => {
      murs(m, -6, 0, -3, -1, 2, 2, (x, y, z) => (x === -1 ? "barreaux" : "pierreTaillee"));
      garde(m, { skin: "espionneJaune", x: -3.5, y: 0, z: 0, lacet: 90, pose: "croise" });
      garde(m, { skin: "toi", x: 2, y: 0, z: 0.5, lacet: -90, pose: "croise" });
    }],
    ["TOI : LE MECHANT", "LES GARDES VOUS LAISSENT PASSER, TOI ET TON POTE.", (m) => {
      garde(m, { skin: "toi", x: 0, y: 0, z: 0, lacet: 0, pose: "croise" });
      garde(m, { skin: "samourai", x: -2.5, y: 0, z: -0.5, lacet: 0, pose: "porte", arme: "lance" });
      garde(m, { skin: "samourai", x: 2.5, y: 0, z: -0.5, lacet: 0, pose: "porte", arme: "lance" });
    }],
  ];
  const W = 1560, C = 3, VW = 520, VH = 340, TITRE = 110, BAS = 300;
  const H = TITRE + 2 * VH + BAS;
  const img = Buffer.alloc(W * H * 4);
  rect(img, W, 0, 0, W, H, [18, 14, 22]);
  texte(img, W, "LES MISSIONS : COMMENT CA MARCHE", 22, 18, 7, [255, 214, 90, 255]);
  texte(img, W, "PAREIL POUR LES 6 MISSIONS. TOUT SE PASSE DANS UNE DIMENSION A PART (COMME LE CARTEL) : RIEN NE TOUCHE AU VILLAGE.", 22, 72, 2, [236, 220, 230, 255]);
  V.forEach(([titre, ligne, f], k) => {
    const x0 = (k % C) * VW, y0 = TITRE + Math.floor(k / C) * VH;
    const sc = vignette(f, { w: VW - 8, h: VH - 64 });
    coller(img, W, sc, x0 + 4, y0);
    rect(img, W, x0 + 4, y0 + VH - 64, x0 + VW - 4, y0 + VH - 4, [30, 24, 36]);
    texte(img, W, titre, x0 + 16, y0 + VH - 56, 3, [255, 214, 90, 255]);
    texte(img, W, ligne, x0 + 16, y0 + VH - 26, 2, [236, 226, 220, 255]);
  });
  const y0 = TITRE + 2 * VH + 10;
  const col = (x, lignes) => fiche(img, W, x, y0, 740, lignes, [255, 214, 90]);
  col(24, [
    "# LANCER UNE MISSION (L'HOTE)",
    "/MISSION WHOOP 1 @ESPIONNES : COMME LE /WHOOP. LE MEME BRUIT, ELLES PARTENT EN ARRIERE, LE TOBOGGAN... ET ELLES ATTERRISSENT AU DEPART DE LA MISSION (PAS AU QG).",
    "/MISSION ANBU 1 @NINJAS : UN NUAGE DE FUMEE, ILS DISPARAISSENT ET APPARAISSENT AU DEPART.",
    "MEME CHOSE AVEC WHOOP 2, WHOOP 3, ANBU 2, ANBU 3.",
    "# TOI ET TON POTE (LES MECHANTS)",
    "/MISSION WHOOP 1 MECHANT @TOI @TONPOTE : VOUS ARRIVEZ DANS LA SALLE DU MECHANT, AVANT LES AUTRES. LES GARDES VOUS LAISSENT PASSER.",
    "/MISSION ALERTE : TOUS LES GARDES EN ALERTE, QUAND TU VEUX LANCER LA POURSUITE.",
    "/MISSION LIBERER @JOUEUR : TU SORS QUELQU'UN DE LA CELLULE.",
    "/MISSION FIN : C'EST FINI, TOUT LE MONDE RENTRE PILE LA OU IL ETAIT.",
  ]);
  col(800, [
    "# PENDANT LA MISSION",
    "L'OBJECTIF S'AFFICHE EN HAUT A DROITE (LA FORMULE, LES 3 PUCES, LE ROULEAU...).",
    "OBJECTIF RAMENE A LA SORTIE = GRAND TITRE \"MISSION ACCOMPLIE\".",
    "MODE AVENTURE : ON NE CASSE RIEN. PAS DE MORT, ON NE PERD RIEN.",
    "LES GADGETS DES ESPIONNES ET LES POUVOIRS MARCHENT SUR LES GARDES.",
    "/MISSION RETOUR : UN JOUEUR RENTRE TOUT SEUL, PILE LA OU IL ETAIT.",
    "# A CHAQUE LANCEMENT",
    "TOUT EST REMIS A ZERO : LES GARDES A LEUR PLACE, LES OBJECTIFS, LES CELLULES.",
    "SEULS LES JOUEURS NOMMES DANS LA COMMANDE Y VONT.",
  ]);
  ecrirePng(path.join(ICI, "..", "planche-regles.png"), W, H, img);
  console.log("planche-regles.png faite");
}
if (quoi === "regles" || quoi === "tout") plancheRegles();
