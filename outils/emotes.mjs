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

console.log("Émotes écrites dans", DOSSIER);
