// Nos émotes en plus de celles d'Emotecraft (format Emotecraft JSON), écrites dans pack/emotes/ :
// le pack les copie dans le dossier « emotes » de chaque instance, Emotecraft les charge tout seul.
// Lancer :  node outils/emotes.mjs   (puis publier comme d'habitude)
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const DOSSIER = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "pack", "emotes");
fs.mkdirSync(DOSSIER, { recursive: true });

// des jambes à leur place (comme les émotes d'Emotecraft)
const JAMBES = { rightLeg: { x: -1.9, y: 12 }, leftLeg: { x: 1.9, y: 12 } };

function emote(fichier, { nom, description, boucle = false, debut = 0, fin, arret, retour = 0, mouvements }) {
  const uuid = crypto.createHash("md5").update("beauvais-emote-" + fichier).digest("hex")
    .replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, "$1-$2-$3-$4-$5");
  const contenu = {
    name: nom, author: "Beauvais", description, uuid,
    emote: { isLoop: String(boucle), returnTick: retour, beginTick: debut, endTick: fin, stopTick: arret ?? fin + 3, degrees: true, moves: mouvements },
  };
  fs.writeFileSync(path.join(DOSSIER, fichier + ".json"), JSON.stringify(contenu, null, 2) + "\n");
}
const pose = (tick, parties, easing = "InOutSine") => ({ tick, easing, turn: 0, ...parties });

// ---- le Dab
emote("dab", { nom: "Le Dab", description: "Le classique.", fin: 30, mouvements: [
  pose(0, { ...JAMBES }),
  pose(5, { rightArm: { roll: 140, yaw: 20 }, leftArm: { pitch: -95, yaw: -55, roll: -20 }, head: { pitch: 30, yaw: -35 }, torso: { roll: -6 } }, "outBack"),
  pose(25, { rightArm: { roll: 140, yaw: 20 }, leftArm: { pitch: -95, yaw: -55, roll: -20 }, head: { pitch: 30, yaw: -35 }, torso: { roll: -6 } }),
  pose(30, { rightArm: { roll: 0, yaw: 0 }, leftArm: { pitch: 0, yaw: 0, roll: 0 }, head: { pitch: 0, yaw: 0 }, torso: { roll: 0 } }),
] });

// ---- la T-pose
emote("t_pose", { nom: "T-pose", description: "Pour asserter sa dominance.", boucle: true, debut: 6, fin: 40, retour: 6, mouvements: [
  pose(0, { ...JAMBES }),
  pose(6, { rightArm: { roll: 90 }, leftArm: { roll: -90 }, head: { pitch: 0 } }, "outQuad"),
  pose(40, { rightArm: { roll: 90 }, leftArm: { roll: -90 }, head: { pitch: 0 } }),
] });

// ---- les muscles
emote("muscles", { nom: "Les muscles", description: "Regarde-moi ces biceps.", boucle: true, debut: 8, fin: 32, retour: 8, mouvements: [
  pose(0, { ...JAMBES }),
  pose(8, { rightArm: { roll: 95, pitch: -10 }, leftArm: { roll: -95, pitch: -10 }, torso: { pitch: -4 } }, "outBack"),
  pose(14, { rightArm: { roll: 120 }, leftArm: { roll: -120 }, head: { roll: 6 } }),
  pose(20, { rightArm: { roll: 95 }, leftArm: { roll: -95 }, head: { roll: -6 } }),
  pose(26, { rightArm: { roll: 120 }, leftArm: { roll: -120 }, head: { roll: 6 } }),
  pose(32, { rightArm: { roll: 95 }, leftArm: { roll: -95 }, head: { roll: 0 } }),
] });

// ---- le floss : les bras passent devant et derrière, les hanches font l'inverse
{
  const mv = [pose(0, { ...JAMBES })];
  for (let i = 0; i < 4; i++) {
    const t = 4 + i * 8;
    const s = i % 2 === 0 ? 1 : -1;
    mv.push(pose(t, { rightArm: { roll: 25 * s, pitch: 25 * s }, leftArm: { roll: 25 * s, pitch: 25 * s }, torso: { yaw: -12 * s, roll: -5 * s } }, "linear"));
    mv.push(pose(t + 4, { rightArm: { roll: -15 * s, pitch: -25 * s }, leftArm: { roll: -15 * s, pitch: -25 * s }, torso: { yaw: 12 * s, roll: 5 * s } }, "linear"));
  }
  emote("floss", { nom: "Floss", description: "La danse de la cour de récré.", boucle: true, debut: 4, fin: 36, retour: 4, mouvements: mv });
}

// ---- haussement d'épaules
emote("bof", { nom: "Bof", description: "Aucune idée.", fin: 26, mouvements: [
  pose(0, { ...JAMBES }),
  pose(6, { rightArm: { roll: 35, pitch: -30 }, leftArm: { roll: -35, pitch: -30 }, head: { roll: 14 }, torso: { y: -0.4 } }, "outBack"),
  pose(20, { rightArm: { roll: 35, pitch: -30 }, leftArm: { roll: -35, pitch: -30 }, head: { roll: 14 }, torso: { y: -0.4 } }),
  pose(26, { rightArm: { roll: 0, pitch: 0 }, leftArm: { roll: 0, pitch: 0 }, head: { roll: 0 }, torso: { y: 0 } }),
] });

// ---- le salut militaire (Macron approuve)
emote("salut", { nom: "Salut militaire", description: "À vos ordres, Monsieur le Président !", fin: 34, mouvements: [
  pose(0, { ...JAMBES }),
  pose(6, { rightArm: { pitch: -135, yaw: -40, roll: 30 }, head: { pitch: -5 }, torso: { pitch: -3 } }, "outQuad"),
  pose(28, { rightArm: { pitch: -135, yaw: -40, roll: 30 }, head: { pitch: -5 }, torso: { pitch: -3 } }),
  pose(34, { rightArm: { pitch: 0, yaw: 0, roll: 0 }, head: { pitch: 0 }, torso: { pitch: 0 } }),
] });

// ---- le headbang
{
  const mv = [pose(0, { ...JAMBES })];
  for (let i = 0; i < 8; i++) {
    mv.push(pose(2 + i * 5, { head: { pitch: 35 }, torso: { pitch: 12 }, rightArm: { roll: 150 }, leftArm: { roll: -40 } }, "inQuad"));
    mv.push(pose(4 + i * 5, { head: { pitch: -15 }, torso: { pitch: 0 }, rightArm: { roll: 170 }, leftArm: { roll: -30 } }, "outQuad"));
  }
  emote("headbang", { nom: "Headbang", description: "Ça déménage à Beauvais.", boucle: true, debut: 2, fin: 42, retour: 2, mouvements: mv });
}

// ================================================================ les émotes les plus connues (danses et poses de RP)
// Une boucle : une suite de poses répétées n fois (chaque pose : [durée en ticks, parties, easing]).
function boucle(poses, n, debut = 4) {
  const mv = [pose(0, { ...JAMBES })];
  let t = debut;
  for (let k = 0; k < n; k++) {
    for (const [duree, parties, easing] of poses) {
      mv.push(pose(t, parties, easing ?? "InOutSine"));
      t += duree;
    }
  }
  return { mouvements: mv, debut, fin: t };
}
const repos = { rightArm: { pitch: 0, yaw: 0, roll: 0 }, leftArm: { pitch: 0, yaw: 0, roll: 0 }, head: { pitch: 0, yaw: 0, roll: 0 },
  torso: { pitch: 0, yaw: 0, roll: 0, y: 0 }, rightLeg: { pitch: 0, roll: 0 }, leftLeg: { pitch: 0, roll: 0 } };
// une pose qu'on garde (en boucle) tant qu'on ne bouge pas
const tenir = (fichier, nom, description, parties, entree = 6) => emote(fichier, { nom, description, boucle: true, debut: entree, fin: 60, retour: entree,
  mouvements: [pose(0, { ...JAMBES }), pose(entree, parties, "outQuad"), pose(60, parties)] });

// ---- la danse de base : les bras qui pompent, la tête qui suit, les genoux qui rebondissent
{
  const b = boucle([
    [4, { rightArm: { pitch: -70, roll: 25 }, leftArm: { pitch: 10, roll: -10 }, head: { roll: 8 }, torso: { y: -0.6 }, rightLeg: { roll: 8 }, leftLeg: { roll: 0 } }],
    [4, { rightArm: { pitch: -20, roll: 10 }, leftArm: { pitch: -20, roll: -10 }, head: { roll: 0 }, torso: { y: 0 }, rightLeg: { roll: 0 }, leftLeg: { roll: 0 } }],
    [4, { leftArm: { pitch: -70, roll: -25 }, rightArm: { pitch: 10, roll: 10 }, head: { roll: -8 }, torso: { y: -0.6 }, leftLeg: { roll: -8 }, rightLeg: { roll: 0 } }],
    [4, { rightArm: { pitch: -20, roll: 10 }, leftArm: { pitch: -20, roll: -10 }, head: { roll: 0 }, torso: { y: 0 }, rightLeg: { roll: 0 }, leftLeg: { roll: 0 } }],
  ], 4);
  emote("danse_de_base", { nom: "La danse de base", description: "Celle que tout le monde connaît.", boucle: true, retour: b.debut, ...b });
}

// ---- Take the L : le L sur le front, les jambes qui battent sur le côté
{
  const L = { leftArm: { pitch: -165, yaw: 20, roll: -25 }, rightArm: { pitch: -10, roll: 35 }, head: { pitch: -5 } };
  const b = boucle([
    [4, { ...L, rightLeg: { roll: 40 }, leftLeg: { roll: 0 }, torso: { roll: -6, y: -0.3 } }],
    [4, { ...L, rightLeg: { roll: 0 }, leftLeg: { roll: 0 }, torso: { roll: 0, y: 0 } }],
    [4, { ...L, leftLeg: { roll: -40 }, rightLeg: { roll: 0 }, torso: { roll: 6, y: -0.3 } }],
    [4, { ...L, rightLeg: { roll: 0 }, leftLeg: { roll: 0 }, torso: { roll: 0, y: 0 } }],
  ], 5);
  emote("take_the_l", { nom: "Take the L", description: "Perdu ! (pour l'adversaire)", boucle: true, retour: b.debut, ...b });
}

// ---- Orange Justice : les bras qui balaient devant soi, les genoux qui partent de côté
{
  const b = boucle([
    [5, { rightArm: { pitch: -45, yaw: 45 }, leftArm: { pitch: -45, yaw: 45 }, torso: { yaw: 18, y: -0.5 }, rightLeg: { pitch: -25, roll: 15 }, leftLeg: { pitch: 10, roll: 0 } }],
    [5, { rightArm: { pitch: -45, yaw: -45 }, leftArm: { pitch: -45, yaw: -45 }, torso: { yaw: -18, y: -0.5 }, leftLeg: { pitch: -25, roll: -15 }, rightLeg: { pitch: 10, roll: 0 } }],
  ], 6);
  emote("orange_justice", { nom: "Orange Justice", description: "Les bras qui balaient, les genoux qui suivent.", boucle: true, retour: b.debut, ...b });
}

// ---- le Griddy : les talons qui tapent, les bras qui balancent, et les mains en lunettes
{
  const b = boucle([
    [3, { rightLeg: { pitch: -30 }, leftLeg: { pitch: 15 }, rightArm: { pitch: 40, yaw: 0, roll: 0 }, leftArm: { pitch: -50, yaw: 0, roll: 0 }, torso: { pitch: 8, y: -0.4 } }],
    [3, { rightLeg: { pitch: 0 }, leftLeg: { pitch: 0 }, rightArm: { pitch: -10 }, leftArm: { pitch: -10 }, torso: { pitch: 8, y: 0 } }],
    [3, { leftLeg: { pitch: -30 }, rightLeg: { pitch: 15 }, leftArm: { pitch: 40 }, rightArm: { pitch: -50 }, torso: { pitch: 8, y: -0.4 } }],
    [3, { rightLeg: { pitch: 0 }, leftLeg: { pitch: 0 }, rightArm: { pitch: -10 }, leftArm: { pitch: -10 }, torso: { pitch: 8, y: 0 } }],
    [3, { rightLeg: { pitch: -30 }, leftLeg: { pitch: 15 }, rightArm: { pitch: -150, yaw: -35, roll: 20 }, leftArm: { pitch: -150, yaw: 35, roll: -20 }, torso: { pitch: 0, y: -0.4 } }],
    [3, { rightLeg: { pitch: 0 }, leftLeg: { pitch: 0 }, rightArm: { pitch: -150, yaw: -35, roll: 20 }, leftArm: { pitch: -150, yaw: 35, roll: -20 }, torso: { pitch: 0, y: 0 } }],
  ], 4);
  emote("griddy", { nom: "Le Griddy", description: "Talons, bras, lunettes.", boucle: true, retour: b.debut, ...b });
}

// ---- Gangnam Style : les poignets croisés comme sur un cheval, le petit galop
{
  const cheval = { rightArm: { pitch: -75, yaw: -28 }, leftArm: { pitch: -75, yaw: 28 }, head: { pitch: 5 } };
  const b = boucle([
    [3, { ...cheval, rightLeg: { pitch: -35 }, leftLeg: { pitch: 0 }, torso: { y: -0.6 } }],
    [3, { ...cheval, rightLeg: { pitch: 0 }, leftLeg: { pitch: 0 }, torso: { y: 0 } }],
    [3, { ...cheval, leftLeg: { pitch: -35 }, rightLeg: { pitch: 0 }, torso: { y: -0.6 } }],
    [3, { ...cheval, rightLeg: { pitch: 0 }, leftLeg: { pitch: 0 }, torso: { y: 0 } }],
  ], 6);
  emote("gangnam_style", { nom: "Gangnam Style", description: "Oppa !", boucle: true, retour: b.debut, ...b });
}

// ---- la danse des canards : le bec, les ailes, la queue, et on tape dans les mains
{
  const mv = [pose(0, { ...JAMBES })];
  let t = 4;
  for (let k = 0; k < 4; k++) {
    mv.push(pose(t, { rightArm: { pitch: -85, yaw: -10, roll: 0 }, leftArm: { pitch: -85, yaw: 10, roll: 0 } })); t += 2;
    mv.push(pose(t, { rightArm: { pitch: -95, yaw: -10, roll: 0 }, leftArm: { pitch: -95, yaw: 10, roll: 0 } })); t += 2;
  }
  for (let k = 0; k < 4; k++) {
    mv.push(pose(t, { rightArm: { pitch: -10, yaw: 0, roll: 60 }, leftArm: { pitch: -10, yaw: 0, roll: -60 } })); t += 2;
    mv.push(pose(t, { rightArm: { pitch: -10, yaw: 0, roll: 15 }, leftArm: { pitch: -10, yaw: 0, roll: -15 } })); t += 2;
  }
  for (let k = 0; k < 4; k++) {
    mv.push(pose(t, { torso: { pitch: 25, yaw: 15, y: -1 }, rightArm: { pitch: 0, roll: 10 }, leftArm: { pitch: 0, roll: -10 }, rightLeg: { pitch: -20 }, leftLeg: { pitch: -20 } })); t += 2;
    mv.push(pose(t, { torso: { pitch: 25, yaw: -15, y: -1 }, rightLeg: { pitch: -20 }, leftLeg: { pitch: -20 } })); t += 2;
  }
  for (let k = 0; k < 4; k++) {
    mv.push(pose(t, { torso: { pitch: 0, yaw: 0, y: 0 }, rightLeg: { pitch: 0 }, leftLeg: { pitch: 0 }, rightArm: { pitch: -60, yaw: 25, roll: 0 }, leftArm: { pitch: -60, yaw: -25, roll: 0 } })); t += 2;
    mv.push(pose(t, { rightArm: { pitch: -60, yaw: -10, roll: 0 }, leftArm: { pitch: -60, yaw: 10, roll: 0 } })); t += 2;
  }
  emote("danse_des_canards", { nom: "La danse des canards", description: "Coin coin, tous les mariages de France.", boucle: true, debut: 4, fin: t, retour: 4, mouvements: mv });
}

// ---- Macarena : un bras après l'autre, devant, retournés, aux épaules, derrière la tête, aux hanches, et ça ondule
{
  const etapes = [
    { rightArm: { pitch: -90, yaw: 0, roll: 0 } }, { leftArm: { pitch: -90, yaw: 0, roll: 0 } },
    { rightArm: { pitch: -90, yaw: 0, roll: 90 } }, { leftArm: { pitch: -90, yaw: 0, roll: -90 } },
    { rightArm: { pitch: -100, yaw: -60, roll: 0 } }, { leftArm: { pitch: -100, yaw: 60, roll: 0 } },
    { rightArm: { pitch: -160, yaw: -40, roll: 40 } }, { leftArm: { pitch: -160, yaw: 40, roll: -40 } },
    { rightArm: { pitch: -25, yaw: -45, roll: 0 } }, { leftArm: { pitch: -25, yaw: 45, roll: 0 } },
  ];
  const mv = [pose(0, { ...JAMBES })];
  let t = 4;
  let courant = {};
  for (let k = 0; k < 2; k++) {
    for (const e of etapes) {
      courant = { ...courant, ...e };
      mv.push(pose(t, { ...courant, torso: { yaw: 0, roll: 0 } }, "outQuad"));
      t += 5;
    }
    for (const sens of [1, -1, 1, -1]) {
      mv.push(pose(t, { ...courant, torso: { yaw: 14 * sens, roll: 4 * sens } }));
      t += 3;
    }
  }
  mv.push(pose(t, { ...repos }));
  emote("macarena", { nom: "Macarena", description: "Hé, Macarena !", boucle: true, debut: 4, fin: t, retour: 4, mouvements: mv });
}

// ---- le twerk
{
  const b = boucle([
    [3, { torso: { pitch: 40, y: -1.2 }, rightArm: { pitch: -40 }, leftArm: { pitch: -40 }, rightLeg: { pitch: -25, roll: 10 }, leftLeg: { pitch: -25, roll: -10 }, head: { pitch: -30 } }],
    [3, { torso: { pitch: 30, y: -0.6 }, rightArm: { pitch: -40 }, leftArm: { pitch: -40 }, rightLeg: { pitch: -15, roll: 10 }, leftLeg: { pitch: -15, roll: -10 }, head: { pitch: -25 } }],
  ], 10);
  emote("twerk", { nom: "Twerk", description: "Pas devant le Président.", boucle: true, retour: b.debut, ...b });
}

// ---- coucou
{
  const b = boucle([
    [4, { rightArm: { pitch: 0, yaw: 0, roll: 150 }, head: { roll: -6 } }, "outQuad"],
    [4, { rightArm: { pitch: 0, yaw: 0, roll: 120 }, head: { roll: -6 } }, "outQuad"],
  ], 4);
  b.mouvements.push(pose(b.fin + 4, { rightArm: { roll: 0 }, head: { roll: 0 } }));
  emote("coucou", { nom: "Coucou", description: "Salut à toi !", debut: b.debut, fin: b.fin + 4, mouvements: b.mouvements });
}

// ---- les poses de RP : pour la police, les discussions, les embrouilles
tenir("mains_en_l_air", "Les mains en l'air !", "Je me rends, monsieur l'agent.", { rightArm: { pitch: -170, roll: 12 }, leftArm: { pitch: -170, roll: -12 }, head: { pitch: -8 } });
tenir("mains_sur_la_tete", "Mains sur la tête", "Pour une arrestation dans les règles.", { rightArm: { pitch: -160, yaw: -55, roll: 50 }, leftArm: { pitch: -160, yaw: 55, roll: -50 }, head: { pitch: 10 } });
tenir("bras_croises", "Bras croisés", "J'attends.", { rightArm: { pitch: -55, yaw: -42, roll: 0 }, leftArm: { pitch: -50, yaw: 42, roll: 0 }, head: { pitch: -4 } });
tenir("mains_aux_hanches", "Mains aux hanches", "Alors, on fait quoi ?", { rightArm: { pitch: -15, yaw: -10, roll: 35 }, leftArm: { pitch: -15, yaw: 10, roll: -35 }, torso: { pitch: -3 } });
tenir("pointer", "Toi !", "C'est toi que je regarde.", { rightArm: { pitch: -90, yaw: -5 }, head: { pitch: 0 }, torso: { yaw: -8 } }, 4);
{
  const b = boucle([
    [10, { rightArm: { pitch: -125, yaw: -35, roll: 0 }, leftArm: { pitch: -45, yaw: 40 }, head: { pitch: 12, roll: 6 } }],
    [10, { rightArm: { pitch: -128, yaw: -35, roll: 0 }, leftArm: { pitch: -45, yaw: 40 }, head: { pitch: 8, roll: -4 } }],
  ], 3);
  emote("reflechir", { nom: "Je réfléchis…", description: "Hmm.", boucle: true, retour: b.debut, ...b });
}
{
  const b = boucle([
    [3, { torso: { pitch: 18, y: -0.4 }, rightArm: { pitch: -30, yaw: 30 }, leftArm: { pitch: -30, yaw: -30 }, head: { pitch: -25 } }],
    [3, { torso: { pitch: 10, y: 0 }, rightArm: { pitch: -25, yaw: 30 }, leftArm: { pitch: -25, yaw: -30 }, head: { pitch: -10 } }],
  ], 8);
  emote("mort_de_rire", { nom: "Mort de rire", description: "Hahaha !", boucle: true, retour: b.debut, ...b });
}

console.log("Émotes écrites dans", DOSSIER);
