// Les combos des lames de la Brume : une seule source pour l'aperçu 3D (apercu/combos.html) et pour le jeu
// (assets/cabane/lames/combos.json, joué par playerAnimator). Chaque coup est une suite de poses clés.
//
// Une pose (tout est à 0 par défaut, angles en radians, pour un droitier ; repère du modèle de Minecraft) :
//   bd, bg      bras droit / gauche [x, y, z] : x négatif = le bras monte devant ; y = il tourne vers le dedans
//               (négatif pour le droit) ; z = il s'écarte (positif pour le droit)
//   cd, cg      coude (négatif : plié vers l'avant)
//   jd, jg      jambe (négatif : vers l'avant) ; gd, gg : genou (positif : plié)
//   penche      le buste plié à la taille (négatif : vers l'avant)
//   tour        tout le corps tourne (radians, un tour = 2π) ; pieds : les jambes restent face à l'avant (twist)
//   haut, avant le corps monte ou descend, avance ou recule (en pixels du joueur)
//   tete        [x, y]
//   poignet     0 : la lame part devant le poing ; 1 : dans l'axe de l'avant-bras (pour piquer)
//   roulis      la lame tourne sur elle-même dans la main (le tranchant devant)
// Un coup : { nom, zone, trace, cles }. zone : il touche aussi tout ce qui est autour (arc : devant, en large ;
// cercle : tout autour ; impact : là où il frappe le sol ; ligne : droit devant) ; sans zone, seulement ce qu'on vise.
// Une clé : { t (ticks), ease (comment on y arrive), ...pose }. La première et la dernière sont la garde.
// Easings : lin, sortie (ralentit en arrivant), entree (accélère : la frappe), doux.

const G2 = { bd: [-0.95, -0.25, 0], bg: [-1.05, 0.6, 0], cd: -0.55, cg: -0.65, jd: 0.15, jg: -0.2, gd: 0.15, gg: 0.2, haut: -0.5 };
const G1 = { bd: [-0.55, 0.05, 0.1], bg: [0.15, 0, -0.15], cd: -0.45, cg: -0.25, jd: 0.1, jg: -0.15, gd: 0.1, gg: 0.15, haut: -0.3 };
const GK = { bd: [-0.65, 0.15, 0.2], bg: [-0.65, -0.15, -0.2], cd: -0.7, cg: -0.7, jd: 0.15, jg: -0.2, gd: 0.15, gg: 0.2, haut: -0.6 };
const GN = { bd: [-0.85, 0.35, 0], bg: [0.2, 0, -0.35], cd: -1.1, cg: -0.6, jd: 0.35, jg: -0.25, gd: 0.25, gg: 0.3, haut: -1, tour: 0.35, tete: [0, -0.3] };

const k = (t, ease, garde, pose = {}) => ({ t, ease, ...garde, ...pose });

/** Les coups de chaque lame, dans l'ordre du combo. trace : la traînée suit la pointe entre ces ticks. */
export const COMBOS = {
  kubikiribocho: {
    garde: G2,
    coups: [
      { nom: "Fendoir", zone: "arc", trace: [5, 12], cles: [
        k(0, "doux", G2),
        k(5, "sortie", G2, { bd: [-2.75, 0.55, 0.45], bg: [-2.65, 0.95, 0.1], cd: -1.15, cg: -1.25, tour: -0.55, penche: 0.25, jd: 0.25, jg: -0.35, gd: 0.15, gg: 0.4, haut: 0 }),
        k(8, "entree", G2, { bd: [-0.9, -0.2, 0.05], bg: [-1.0, 0.5, 0], cd: -0.1, cg: -0.25, tour: 0.1, penche: -0.45, haut: -2.3, jd: -0.9, jg: 0.5, gd: 0.9, gg: 0.15, avant: 3 }),
        k(11, "sortie", G2, { bd: [-0.35, -0.8, -0.2], bg: [-0.5, 0.15, 0], cd: -0.15, cg: -0.3, tour: 0.55, penche: -0.6, jd: -0.95, jg: 0.5, gd: 1, gg: 0.15, haut: -3, avant: 3.5 }),
        k(17, "doux", G2)] },
      { nom: "Faucheuse", zone: "arc", trace: [5, 12], cles: [
        k(0, "doux", G2),
        k(5, "sortie", G2, { bd: [-0.6, 1.1, 0.5], bg: [-0.6, 1.6, 0.2], cd: -0.7, cg: -0.8, poignet: 0.5, roulis: Math.PI / 2, tour: -0.65, penche: -0.3, haut: -4, jd: 0.3, jg: -0.5, gd: 1.2, gg: 1.0 }),
        k(8, "entree", G2, { bd: [-0.75, -0.1, 0.05], bg: [-0.75, 0.45, 0], cd: -0.1, cg: -0.2, poignet: 0.5, roulis: Math.PI / 2, tour: 0.1, penche: -0.4, haut: -5, jd: -0.8, jg: 0.6, gd: 1.3, gg: 0.7, avant: 2.5 }),
        k(11, "sortie", G2, { bd: [-0.7, -1.2, -0.2], bg: [-0.7, -0.5, 0], cd: -0.15, cg: -0.25, poignet: 0.5, roulis: Math.PI / 2, tour: 0.8, penche: -0.4, haut: -5, jd: -0.8, jg: 0.6, gd: 1.3, gg: 0.7, avant: 3 }),
        k(17, "doux", G2)] },
      { nom: "Exécution", zone: "impact", trace: [6, 11], cles: [
        k(0, "doux", G2),
        k(6, "sortie", G2, { bd: [-3.1, -0.1, 0], bg: [-3.1, 0.4, 0], cd: -1.3, cg: -1.3, penche: 0.45, haut: 4, jd: -0.6, jg: 0.1, gd: 1.1, gg: 0.4 }),
        k(10, "entree", G2, { bd: [-0.25, -0.1, 0], bg: [-0.35, 0.4, 0], cd: -0.05, cg: -0.05, penche: -0.85, haut: -4.5, jd: -1.05, jg: 0.6, gd: 1.35, gg: 0.35, avant: 2.5 }),
        k(15, "sortie", G2, { bd: [-0.2, -0.1, 0], bg: [-0.3, 0.4, 0], cd: -0.05, cg: -0.05, penche: -0.9, haut: -4.5, jd: -1.05, jg: 0.6, gd: 1.35, gg: 0.35, avant: 2.5 }),
        k(22, "doux", G2)] },
    ],
  },
  samehada: {
    garde: G1,
    coups: [
      { nom: "Râpe", zone: "arc", trace: [4, 11], cles: [
        k(0, "doux", G1),
        k(4, "sortie", G1, { bd: [-1.25, 1.15, 0.45], cd: -0.95, bg: [-0.35, 0, -0.45], cg: -0.5, tour: -0.65, haut: -1, jd: 0.25, jg: -0.25, gd: 0.25, gg: 0.3 }),
        k(7, "entree", G1, { bd: [-1.4, -0.15, 0.1], cd: -0.1, bg: [-0.25, 0, -0.55], cg: -0.4, tour: 0.1, haut: -1.6, jd: -0.4, jg: 0.3, gd: 0.45, gg: 0.2, avant: 1.5 }),
        k(10, "sortie", G1, { bd: [-1.2, -1.4, -0.1], cd: -0.2, bg: [-0.2, 0, -0.6], cg: -0.4, tour: 0.9, haut: -1.8, jd: -0.45, jg: 0.35, gd: 0.5, gg: 0.2, avant: 2 }),
        k(15, "doux", G1)] },
      { nom: "Revers", zone: "arc", trace: [4, 11], cles: [
        k(0, "doux", G1),
        k(4, "sortie", G1, { bd: [-1.4, -1.35, 0], cd: -1.05, bg: [-0.3, 0, -0.5], tour: 0.65, haut: -1, jd: 0.2, jg: -0.2 }),
        k(7, "entree", G1, { bd: [-1.4, 0.15, 0.2], cd: -0.1, bg: [-0.3, 0, -0.65], tour: -0.1, haut: -1.6, jd: -0.35, jg: 0.35, gd: 0.4, avant: 2 }),
        k(10, "sortie", G1, { bd: [-1.1, 1.45, 0.5], cd: -0.15, bg: [-0.3, 0, -0.7], tour: -0.9, haut: -1.8, jd: -0.4, jg: 0.4, gd: 0.45, avant: 2.5 }),
        k(15, "doux", G1)] },
      { nom: "Écrasement", zone: "impact", trace: [6, 11], cles: [
        k(0, "doux", G1),
        k(6, "sortie", G1, { bd: [-3.05, -0.1, 0], bg: [-3.05, 0.45, 0], cd: -1.3, cg: -1.3, penche: 0.45, haut: 4, jd: -0.6, jg: 0.1, gd: 1.1, gg: 0.4 }),
        k(10, "entree", G1, { bd: [-0.4, -0.1, 0], bg: [-0.5, 0.45, 0], cd: -0.05, cg: -0.1, penche: -0.85, haut: -4.5, jd: -1.05, jg: 0.6, gd: 1.35, gg: 0.35, avant: 2.5 }),
        k(15, "sortie", G1, { bd: [-0.35, -0.1, 0], bg: [-0.45, 0.45, 0], cd: -0.05, cg: -0.1, penche: -0.9, haut: -4.5, jd: -1.05, jg: 0.6, gd: 1.35, gg: 0.35, avant: 2.5 }),
        k(22, "doux", G1)] },
    ],
  },
  kiba: {
    garde: GK,
    coups: [
      { nom: "Croc droit", trace: [3, 8], cles: [
        k(0, "doux", GK),
        k(3, "sortie", GK, { bd: [-2.75, 0.3, 0.65], cd: -1, tour: -0.4, haut: -0.5 }),
        k(5, "entree", GK, { bd: [-1.25, -0.2, 0.1], cd: -0.1, tour: 0, haut: -1.5, jd: -0.4, jg: 0.25, gd: 0.4, avant: 1.2 }),
        k(8, "sortie", GK, { bd: [-0.45, -0.95, -0.1], cd: -0.15, tour: 0.5, haut: -1.8, jd: -0.5, jg: 0.3, gd: 0.5, avant: 1.8 }),
        k(12, "doux", GK)] },
      { nom: "Croc gauche", trace: [3, 8], cles: [
        k(0, "doux", GK),
        k(3, "sortie", GK, { bg: [-2.75, -0.3, -0.65], cg: -1, tour: 0.4, haut: -0.5 }),
        k(5, "entree", GK, { bg: [-1.25, 0.2, -0.1], cg: -0.1, tour: 0, haut: -1.5, jd: 0.25, jg: -0.4, gg: 0.4, avant: 1.2 }),
        k(8, "sortie", GK, { bg: [-0.45, 0.95, 0.1], cg: -0.15, tour: -0.5, haut: -1.8, jd: 0.3, jg: -0.5, gg: 0.5, avant: 1.8 }),
        k(12, "doux", GK)] },
      { nom: "Tourbillon", zone: "cercle", trace: [3, 17], cles: [
        k(0, "doux", GK),
        k(3, "sortie", GK, { bd: [0, 0, 1.5], bg: [0, 0, -1.5], cd: -0.05, cg: -0.05, haut: -1.5, jd: 0.15, jg: -0.15, gd: 0.45, gg: 0.45 }),
        k(17, "lin", GK, { bd: [0, 0, 1.5], bg: [0, 0, -1.5], cd: -0.05, cg: -0.05, haut: -1.5, jd: 0.15, jg: -0.15, gd: 0.45, gg: 0.45, tour: -4 * Math.PI }),
        k(22, "doux", GK, { tour: -4 * Math.PI })] },
      { nom: "Rafale", trace: [3, 15], cles: [
        k(0, "doux", GK),
        k(3, "sortie", GK, { bd: [-1.35, 1.1, 0.4], cd: -0.8, tour: -0.45, haut: -1 }),
        k(6, "entree", GK, { bd: [-1.3, -1.0, 0], cd: -0.1, tour: 0.35, haut: -1.5, avant: 1, bg: [-1.35, -1.1, -0.4], cg: -0.8 }),
        k(9, "entree", GK, { bd: [-0.9, -0.6, 0], cd: -0.5, bg: [-1.3, 1.0, 0], cg: -0.1, tour: -0.35, haut: -1.5, avant: 2 }),
        k(11, "sortie", GK, { bd: [-0.7, 0.2, 0], bg: [-0.7, -0.2, 0], cd: -1.4, cg: -1.4, haut: -2, penche: 0.15, jd: 0.3, gd: 0.6, gg: 0.5, avant: 1.5 }),
        k(14, "entree", GK, { bd: [-1.55, -0.12, 0], bg: [-1.55, 0.12, 0], cd: 0, cg: 0, poignet: 1, haut: -3, penche: -0.35, jd: -1.1, jg: 0.6, gd: 0.9, avant: 6 }),
        k(17, "sortie", GK, { bd: [-1.55, -0.12, 0], bg: [-1.55, 0.12, 0], cd: 0, cg: 0, poignet: 1, haut: -3, penche: -0.35, jd: -1.1, jg: 0.6, gd: 0.9, avant: 6.5 }),
        k(22, "doux", GK)] },
    ],
  },
  nuibari: {
    garde: GN,
    coups: [
      { nom: "Fente", trace: [3, 7], cles: [
        k(0, "doux", GN),
        k(3, "sortie", GN, { bd: [-0.6, 0.55, 0], cd: -1.5, tour: 0.55, jd: 0.4, gd: 0.6, haut: -1.5 }),
        k(5, "entree", GN, { bd: [-1.6, -0.05, 0], cd: 0, poignet: 1, tour: -0.25, jd: -1.15, gd: 0.9, jg: 0.65, gg: 0, avant: 6, haut: -3, penche: -0.2, bg: [0.6, 0, -0.5] }),
        k(9, "sortie", GN, { bd: [-1.6, -0.05, 0], cd: 0, poignet: 1, tour: -0.25, jd: -1.15, gd: 0.9, jg: 0.65, avant: 6.5, haut: -3, penche: -0.2, bg: [0.6, 0, -0.5] }),
        k(14, "doux", GN)] },
      { nom: "Double piqûre", trace: [2, 9], cles: [
        k(0, "doux", GN),
        k(2, "sortie", GN, { bd: [-1.0, 0.4, 0], cd: -1.4 }),
        k(4, "entree", GN, { bd: [-1.85, 0, 0], cd: 0, poignet: 1, avant: 3, jd: -0.6, gd: 0.5 }),
        k(6, "sortie", GN, { bd: [-1.1, 0.35, 0], cd: -1.3, poignet: 0.3, avant: 3 }),
        k(8, "entree", GN, { bd: [-1.5, -0.1, 0], cd: 0, poignet: 1, avant: 5, jd: -0.9, gd: 0.8, jg: 0.5, haut: -2 }),
        k(13, "doux", GN)] },
      { nom: "Flèche", zone: "ligne", trace: [6, 11], cles: [
        k(0, "doux", GN),
        k(6, "sortie", GN, { bd: [-2.3, 0.6, 0.3], cd: -1.6, poignet: 0.6, tour: 0.75, penche: 0.2, haut: -4, jd: 0.45, jg: -0.6, gd: 1.3, gg: 0.9, avant: -2, tete: [0.2, -0.5] }),
        k(9, "entree", GN, { bd: [-1.62, -0.05, 0], cd: 0, poignet: 1, bg: [0.9, 0, -0.4], cg: 0, tour: -0.35, penche: -0.45, haut: 0, jd: -1.3, jg: 0.9, gd: 0.4, gg: 0, avant: 10, tete: [-0.2, 0.3] }),
        k(10, "sortie", GN, { bd: [-1.6, -0.05, 0], cd: 0, poignet: 1, bg: [0.9, 0, -0.4], cg: 0, tour: -0.3, penche: -0.4, haut: -3, jd: -1.2, jg: 0.8, gd: 1.0, gg: 0, avant: 11, tete: [-0.2, 0.3] }),
        k(14, "sortie", GN, { bd: [-1.58, -0.05, 0], cd: 0, poignet: 1, bg: [0.9, 0, -0.4], cg: 0, tour: -0.3, penche: -0.4, haut: -3.5, jd: -1.2, jg: 0.8, gd: 1.05, gg: 0, avant: 11 }),
        k(20, "doux", GN)] },
    ],
  },
  shibuki: {
    garde: G1,
    coups: [
      { nom: "Remontée", trace: [5, 11], cles: [
        k(0, "doux", G1),
        k(5, "sortie", G1, { bd: [-0.25, -0.65, -0.35], cd: -0.35, tour: 0.55, penche: -0.45, haut: -3, jd: 0.2, gd: 0.7, gg: 0.4 }),
        k(8, "entree", G1, { bd: [-1.5, -0.1, 0.15], cd: -0.1, tour: 0, penche: -0.1, haut: -1.2, jd: -0.5, jg: 0.35, gd: 0.4, avant: 1.8 }),
        k(11, "sortie", G1, { bd: [-2.85, 0.5, 0.65], cd: -0.25, tour: -0.55, penche: 0.3, haut: 1, jd: -0.5, jg: 0.35, gd: 0.3, avant: 2.2 }),
        k(16, "doux", G1)] },
      { nom: "Chute", trace: [5, 11], cles: [
        k(0, "doux", G1),
        k(5, "sortie", G1, { bd: [-2.75, 0.45, 0.6], cd: -1.05, tour: -0.45, penche: 0.25, jd: 0.25, jg: -0.3, gg: 0.35 }),
        k(8, "entree", G1, { bd: [-0.85, -0.2, 0.05], cd: -0.1, tour: 0.1, penche: -0.45, haut: -2.3, jd: -0.85, jg: 0.45, gd: 0.9, avant: 3 }),
        k(11, "sortie", G1, { bd: [-0.35, -0.95, -0.15], cd: -0.15, tour: 0.6, penche: -0.55, haut: -2.8, jd: -0.85, jg: 0.45, gd: 0.95, avant: 3.3 }),
        k(16, "doux", G1)] },
      { nom: "Déflagration", zone: "impact", trace: [6, 11], cles: [
        k(0, "doux", G1),
        k(6, "sortie", G1, { bd: [-3.05, -0.1, 0], bg: [-3.05, 0.45, 0], cd: -1.3, cg: -1.3, penche: 0.45, haut: 4, jd: -0.6, jg: 0.1, gd: 1.1, gg: 0.4 }),
        k(10, "entree", G1, { bd: [-0.95, -0.1, 0], bg: [-1.05, 0.45, 0], cd: -0.05, cg: -0.1, penche: -0.85, haut: -4.5, jd: -1.05, jg: 0.6, gd: 1.35, gg: 0.35, avant: 2.5 }),
        k(15, "sortie", G1, { bd: [-0.9, -0.1, 0], bg: [-1.0, 0.45, 0], cd: -0.05, cg: -0.1, penche: -0.9, haut: -4.5, jd: -1.05, jg: 0.6, gd: 1.35, gg: 0.35, avant: 2.5 }),
        k(22, "doux", G1)] },
    ],
  },
  kabutowari: {
    garde: GK,
    coups: [
      { nom: "Hache", zone: "arc", trace: [5, 10], cles: [
        k(0, "doux", GK),
        k(5, "sortie", GK, { bd: [-2.95, 0, 0.25], cd: -1.2, tour: -0.35, penche: 0.25, jd: 0.25, jg: -0.3, gg: 0.35 }),
        k(9, "entree", GK, { bd: [-0.75, -0.3, 0], cd: -0.1, tour: 0.4, penche: -0.5, haut: -2.5, jd: -0.75, jg: 0.4, gd: 0.85, avant: 2.5 }),
        k(12, "sortie", GK, { bd: [-0.6, -0.4, 0], cd: -0.15, tour: 0.45, penche: -0.55, haut: -2.7, jd: -0.75, jg: 0.4, gd: 0.85, avant: 2.8 }),
        k(17, "doux", GK)] },
      { nom: "Marteau", zone: "impact", trace: [6, 11], cles: [
        k(0, "doux", GK),
        k(6, "sortie", GK, { bg: [-3.0, -0.3, -0.5], cg: -1.3, bd: [0.2, 0, 0.5], cd: -0.4, poignet: 0.9, tour: 0.6, penche: 0.3, haut: 2.5, jd: 0.2, jg: -0.5, gd: 0.4, gg: 0.9 }),
        k(10, "entree", GK, { bg: [-0.5, 0.5, 0.2], cg: -0.05, bd: [0.4, 0, 0.6], cd: -0.2, tour: -0.5, penche: -0.75, haut: -4.5, jd: 0.55, jg: -1.0, gd: 0.3, gg: 1.2, avant: 3 }),
        k(14, "sortie", GK, { bg: [-0.4, 0.55, 0.2], cg: -0.05, bd: [0.4, 0, 0.6], cd: -0.2, tour: -0.55, penche: -0.8, haut: -4.8, jd: 0.55, jg: -1.0, gd: 0.3, gg: 1.25, avant: 3.2 }),
        k(20, "doux", GK)] },
      { nom: "Brise-crâne", zone: "impact", trace: [6, 11], cles: [
        k(0, "doux", GK),
        k(6, "sortie", GK, { bd: [-3.05, -0.05, 0.1], bg: [-3.05, 0.05, -0.1], cd: -1.3, cg: -1.3, penche: 0.45, haut: 4.5, jd: -0.6, jg: 0.1, gd: 1.1, gg: 0.4 }),
        k(10, "entree", GK, { bd: [-0.45, -0.25, 0], bg: [-0.45, 0.25, 0], cd: -0.05, cg: -0.05, penche: -0.9, haut: -5, jd: -1.1, jg: 0.6, gd: 1.4, gg: 0.35, avant: 2.5 }),
        k(15, "sortie", GK, { bd: [-0.4, -0.25, 0], bg: [-0.4, 0.25, 0], cd: -0.05, cg: -0.05, penche: -0.9, haut: -5, jd: -1.1, jg: 0.6, gd: 1.4, gg: 0.35, avant: 2.5 }),
        k(22, "doux", GK)] },
    ],
  },
  hiramekarei: {
    garde: G2,
    coups: [
      { nom: "Marée montante", zone: "arc", trace: [5, 10], cles: [
        k(0, "doux", G2),
        k(5, "sortie", G2, { bd: [-0.2, -0.65, -0.25], bg: [-0.3, 0.3, 0], cd: -0.4, cg: -0.6, tour: 0.55, penche: -0.45, haut: -3, jd: 0.25, jg: -0.25, gd: 0.7, gg: 0.4 }),
        k(9, "entree", G2, { bd: [-2.8, 0.45, 0.35], bg: [-2.9, 0.85, 0], cd: -0.15, cg: -0.25, tour: -0.45, penche: 0.25, haut: 1, jd: -0.55, jg: 0.35, gd: 0.3, avant: 2 }),
        k(12, "sortie", G2, { bd: [-2.95, 0.55, 0.4], bg: [-3, 0.9, 0], cd: -0.25, cg: -0.3, tour: -0.55, penche: 0.3, haut: 1, jd: -0.55, jg: 0.35, gd: 0.3, avant: 2.2 }),
        k(17, "doux", G2)] },
      { nom: "Ressac", zone: "arc", trace: [6, 12], cles: [
        k(0, "doux", G2),
        k(6, "sortie", G2, { bd: [-2.6, -0.6, -0.2], bg: [-2.7, -0.2, -0.5], cd: -1.0, cg: -0.9, tour: 0.55, penche: 0.2, jd: -0.3, jg: 0.25, gd: 0.35, gg: 0.1 }),
        k(9, "entree", G2, { bd: [-0.9, 0.1, 0.1], bg: [-0.9, 0.6, 0], cd: -0.1, cg: -0.2, tour: 0, penche: -0.45, haut: -2.3, jd: -0.9, jg: 0.5, gd: 0.9, gg: 0.1, avant: 3 }),
        k(12, "sortie", G2, { bd: [-0.4, 0.75, 0.3], bg: [-0.4, 1.15, 0.1], cd: -0.15, cg: -0.25, tour: -0.6, penche: -0.6, haut: -3, jd: -0.95, jg: 0.5, gd: 1.0, gg: 0.1, avant: 3.4 }),
        k(18, "doux", G2)] },
      { nom: "Exécution", zone: "impact", trace: [6, 11], cles: [
        k(0, "doux", G2),
        k(6, "sortie", G2, { bd: [-3.1, -0.1, 0], bg: [-3.1, 0.4, 0], cd: -1.3, cg: -1.3, penche: 0.45, haut: 4, jd: -0.6, jg: 0.1, gd: 1.1, gg: 0.4 }),
        k(10, "entree", G2, { bd: [-0.25, -0.1, 0], bg: [-0.35, 0.4, 0], cd: -0.05, cg: -0.05, penche: -0.85, haut: -4.5, jd: -1.05, jg: 0.6, gd: 1.35, gg: 0.35, avant: 2.5 }),
        k(15, "sortie", G2, { bd: [-0.2, -0.1, 0], bg: [-0.3, 0.4, 0], cd: -0.05, cg: -0.05, penche: -0.9, haut: -4.5, jd: -1.05, jg: 0.6, gd: 1.35, gg: 0.35, avant: 2.5 }),
        k(22, "doux", G2)] },
    ],
  },
};

/** Comment chaque lame est tenue : deux mains, une lame par main (Kiba), la hache et le marteau (Kabutowari). */
export const TENUE = {
  kubikiribocho: { deuxMains: true }, hiramekarei: { deuxMains: true }, samehada: {}, shibuki: {}, nuibari: {},
  kiba: { gauche: "kiba" }, kabutowari: { gauche: "marteau" },
};

/** La couleur de la traînée de chaque lame. */
export const TRAINEES = {
  kubikiribocho: 0xd2dcf0, samehada: 0x5a8cff, kiba: 0x78dcff, nuibari: 0xe8e8ff, shibuki: 0xff8c32, kabutowari: 0xffdc78, hiramekarei: 0x6ee6ff,
};

// Le poignet pendant les coups : en garde la lame part devant le poing ; armée, elle se cale un peu ; à l'impact
// et après, elle se tend dans l'axe du bras (sinon, bras levé à l'horizontale, elle finit à la verticale et
// frappe au-dessus de la cible). Dans les tours (tourbillon, tempête), elle reste tendue et à plat, parallèle au sol.
for (const lame of Object.values(COMBOS)) {
  for (const coup of lame.coups) {
    const n = coup.cles.length;
    const tour = Math.abs((coup.cles[n - 1].tour || 0) - (coup.cles[0].tour || 0)) > 3;
    coup.cles.forEach((c, i) => {
      if (c.poignet !== undefined) return;
      if (i === 0 || i === n - 1) c.poignet = 0;
      else if (tour) { c.poignet = 0.95; c.roulis = Math.PI / 2; }
      else c.poignet = i === 1 ? 0.35 : 0.75;
    });
  }
}

/**
 * Les coups spéciaux (clic droit), avec leur recharge en secondes (la même que dans le jeu, voir Lames.Sorte) : la
 * pose du joueur, et l'effet que l'aperçu dessine (effet : son nom ; les instants en ticks).
 */
export const SPECIAUX = {
  kubikiribocho: { nom: "Lancer tournoyant", recharge: 8, effet: "lancer", cles: [
    k(0, "doux", G2),
    k(6, "sortie", G2, { bd: [-2.3, 0.8, 0.6], cd: -1.1, bg: [-0.4, 0, -0.5], cg: -0.4, tour: -0.75, penche: 0.15, jd: 0.3, jg: -0.3, gg: 0.4 }),
    k(9, "entree", G2, { bd: [-1.55, -0.6, 0], cd: 0, bg: [-0.2, 0, -0.6], cg: -0.3, tour: 0.45, penche: -0.3, jd: -0.7, jg: 0.4, gd: 0.6, avant: 2, haut: -1.5 }),
    k(30, "sortie", G2, { bd: [-1.5, 0.1, 0.2], cd: -0.2, bg: [-0.3, 0, -0.5], tour: 0.1, avant: 1, haut: -1 }),
    k(36, "doux", G2, { bd: [-1.2, 0.1, 0.2], cd: -0.6, avant: 0.5 }),
    k(42, "doux", G2)] },
  samehada: { nom: "Avale", recharge: 15, effet: "avale", cles: [
    k(0, "doux", G1),
    k(5, "sortie", G1, { bd: [-1.55, 0, 0.05], cd: 0, poignet: 1, haut: -1.2, jd: -0.4, jg: 0.3, gd: 0.4, avant: 1 }),
    k(30, "doux", G1, { bd: [-1.5, 0, 0.05], cd: -0.1, poignet: 1, haut: -1, jd: -0.4, jg: 0.3, gd: 0.4, avant: 1 }),
    k(34, "entree", G1, { bd: [-1.75, 0, 0.05], cd: 0, poignet: 1, haut: -1.5, jd: -0.5, jg: 0.35, gd: 0.5, avant: 2, penche: -0.2 }),
    k(46, "doux", G1)] },
  kiba: { nom: "Foudre", recharge: 10, effet: "foudre", cles: [
    k(0, "doux", GK),
    k(6, "sortie", GK, { bd: [-2.9, 0.45, 0.3], bg: [-2.9, -0.45, -0.3], cd: -0.2, cg: -0.2, poignet: 0.6, tete: [-0.35, 0], haut: -1, gd: 0.4, gg: 0.4 }),
    k(22, "doux", GK, { bd: [-2.9, 0.45, 0.3], bg: [-2.9, -0.45, -0.3], cd: -0.2, cg: -0.2, poignet: 0.6, tete: [-0.35, 0], haut: -1, gd: 0.4, gg: 0.4 }),
    k(30, "doux", GK)] },
  nuibari: { nom: "Couture", recharge: 12, effet: "couture", cles: [
    k(0, "doux", GN),
    k(3, "sortie", GN, { bd: [-0.7, 0.5, 0], cd: -1.5, tour: 0.5, jd: 0.35, gd: 0.5, haut: -1.5 }),
    k(5, "entree", GN, { bd: [-1.6, -0.05, 0], cd: 0, poignet: 1, tour: -0.2, jd: -0.9, jg: 0.5, gd: 0.7, avant: 3, haut: -2 }),
    k(24, "doux", GN, { bd: [-1.55, -0.05, 0], cd: -0.1, poignet: 1, tour: -0.2, jd: -0.9, jg: 0.5, gd: 0.7, avant: 3, haut: -2 }),
    k(28, "entree", GN, { bd: [-0.5, 0.4, 0.1], cd: -1.6, tour: 0.6, penche: 0.25, jd: 0.4, jg: -0.6, gg: 0.8, avant: 0, haut: -2 }),
    k(40, "doux", GN)] },
  shibuki: { nom: "Déflagration", recharge: 12, effet: "explosions", trace: [6, 11], cles: [
    k(0, "doux", G1),
    k(6, "sortie", G1, { bd: [-3.05, -0.1, 0], bg: [-3.05, 0.45, 0], cd: -1.3, cg: -1.3, penche: 0.45, haut: 4, jd: -0.6, jg: 0.1, gd: 1.1, gg: 0.4 }),
    k(10, "entree", G1, { bd: [-0.95, -0.1, 0], bg: [-1.05, 0.45, 0], cd: -0.05, cg: -0.1, poignet: 0.7, penche: -0.85, haut: -4.5, jd: -1.05, jg: 0.6, gd: 1.35, gg: 0.35, avant: 2.5 }),
    k(24, "doux", G1, { bd: [-0.9, -0.1, 0], bg: [-1.0, 0.45, 0], cd: -0.05, cg: -0.1, poignet: 0.7, penche: -0.8, haut: -4, jd: -1.05, jg: 0.6, gd: 1.3, gg: 0.35, avant: 2.5 }),
    k(34, "doux", G1)] },
  kabutowari: { nom: "Brise-casque", recharge: 15, effet: "seisme", trace: [12, 18], cles: [
    k(0, "doux", GK),
    k(5, "sortie", GK, { bd: [-0.4, 0, 0.3], bg: [-0.4, 0, -0.3], haut: -4, penche: -0.4, gd: 1.4, gg: 1.4, jd: -0.5, jg: -0.5 }),
    k(12, "sortie", GK, { bd: [-3.05, 0, 0.1], bg: [-3.05, 0, -0.1], cd: -1.2, cg: -1.2, poignet: 0.9, penche: 0.35, haut: 38, jd: -1.0, jg: -0.6, gd: 1.6, gg: 1.4, avant: 3 }),
    k(17, "entree", GK, { bd: [-0.6, -0.2, 0], bg: [-0.6, 0.2, 0], cd: -0.05, cg: -0.05, poignet: 0.7, penche: -0.85, haut: -5, jd: -1.1, jg: 0.6, gd: 1.4, gg: 0.4, avant: 5 }),
    k(28, "doux", GK, { bd: [-0.55, -0.2, 0], bg: [-0.55, 0.2, 0], cd: -0.05, cg: -0.05, poignet: 0.7, penche: -0.8, haut: -4.5, jd: -1.1, jg: 0.6, gd: 1.35, gg: 0.4, avant: 5 }),
    k(38, "doux", GK)] },
  hiramekarei: { nom: "Lame de chakra", recharge: 15, effet: "chakra", trace: [18, 24], cles: [
    k(0, "doux", G2),
    k(5, "sortie", G2, { bd: [-2.95, -0.1, 0], bg: [-2.95, 0.45, 0], cd: -0.3, cg: -0.35, poignet: 0.8, penche: 0.3, haut: -1, jd: 0.2, jg: -0.3, gd: 0.4, gg: 0.4 }),
    k(18, "doux", G2, { bd: [-2.95, -0.1, 0], bg: [-2.95, 0.45, 0], cd: -0.3, cg: -0.35, poignet: 0.8, penche: 0.3, haut: -1, jd: 0.2, jg: -0.3, gd: 0.4, gg: 0.4 }),
    k(23, "entree", G2, { bd: [-1.0, -0.1, 0], bg: [-1.05, 0.45, 0], cd: -0.05, cg: -0.1, poignet: 0.8, penche: -0.6, haut: -3.5, jd: -0.9, jg: 0.5, gd: 1.0, avant: 2.5 }),
    k(32, "doux", G2, { bd: [-0.95, -0.1, 0], bg: [-1.0, 0.45, 0], cd: -0.05, cg: -0.1, poignet: 0.8, penche: -0.55, haut: -3.5, jd: -0.9, jg: 0.5, gd: 1.0, avant: 2.5 }),
    k(40, "doux", G2)] },
};
for (const s of Object.values(SPECIAUX)) for (const c of s.cles) { c.poignet ??= 0; c.roulis ??= 0; }
