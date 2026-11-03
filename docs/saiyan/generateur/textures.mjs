// Les textures du mod pour les accessoires Saiyan (accessoire/Saiyan.java, client/RenduSaiyan.java), tirées des maquettes :
// les icônes 64×64, le poil de la queue et son duvet percé (une bande de 8 colonnes par côté de pavé, 4 lignes par pixel le
// long de la queue, le bout dans les 8 dernières lignes), le haut-parleur de l'oreillette et l'icône du slot de la queue.
// node textures.mjs → mod-cabane/src/main/resources/assets/cabane/textures/…
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, hache, mix } from "../../fruits/generateur/moteur.mjs";
import { ICONE_QUEUE, ICONE_DETECTEUR, COULEURS, POIL, POIL_F, POIL_C, COQUE, COQUE_F, SOMBRE } from "./rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const TEX = path.join(ICI, "../../../mod-cabane/src/main/resources/assets/cabane/textures");

function ecrire(rel, w, h, px) {
  const f = path.join(TEX, rel);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  ecrirePng(f, w, h, px);
}

/**
 * Le poil de la queue (32 × 128), comme poil() et duvet() des maquettes : des brins clairs et foncés dans le sens de la
 * queue ; le duvet n'en garde que des mèches (le reste est transparent), plus serrées vers le bout.
 */
function poil(duvet) {
  const W = 32, H = 128, px = Buffer.alloc(W * H * 4);
  for (let v = 0; v < H; v++) for (let u = 0; u < W; u++) {
    const tr = u, bout = v >= 88;
    let c;
    if (!duvet) {
      const decal = Math.floor(hache(tr, 3, 912) * 6);
      const brin = hache(tr * 17, Math.floor((v + decal) / 6), 910);
      c = brin > 0.66 ? mix(POIL, POIL_C, (brin - 0.66) * 1.5) : brin < 0.28 ? mix(POIL, POIL_F, (0.28 - brin) * 2) : POIL;
      c = mix(c, POIL_F, hache(tr * 7, v, 911) * 0.12);
    } else {
      const decal = Math.floor(hache(tr, 5, 922) * 4);
      const h = hache(tr * 13, Math.floor((v + decal) / 4), 920);
      if (h > (bout ? 0.75 : 0.3)) continue;
      c = h < 0.1 ? mix(POIL, POIL_F, 0.4) : mix(POIL, POIL_C, 0.25 + 0.3 * hache(tr, v, 921));
    }
    px.set([...c.map(Math.round), 255], (v * W + u) * 4);
  }
  return px;
}

/** Le côté dehors de l'oreillette (32 × 32, u de l'avant vers l'arrière, v du haut vers le bas) : le haut-parleur rond. */
function oreillette() {
  const S = 32, px = Buffer.alloc(S * S * 4);
  for (let j = 0; j < S; j++) for (let i = 0; i < S; i++) {
    const u = (i + 0.5) / S, v = (j + 0.5) / S, r = Math.hypot((u - 0.52) * 3.4, (v - 0.5) * 4);
    const c = r < 0.5 ? [86, 90, 102] : r < 0.72 ? [150, 156, 168] : r < 1.22 ? (r > 1.0 && r < 1.1 ? [96, 100, 112] : SOMBRE)
      : r < 1.38 ? COQUE_F : Math.min(u, v, 1 - u, 1 - v) < 0.08 ? COQUE_F : COQUE;
    px.set([...c, 255], (j * S + i) * 4);
  }
  return px;
}

/** L'icône du slot vide (16 × 16), dans le gris des icônes de Curios : une queue en point d'interrogation. */
function iconeSlot() {
  const dessin = [
    "................", "................", ".....BBBB.......", "....B....B......", "....B.....B.....", ".....BB....B....",
    "...........B....", "..........B.....", ".........B......", "........B.......", ".......B........", "......B.........",
    ".....B..........", "....BB..........", "................", "................",
  ];
  const px = Buffer.alloc(16 * 16 * 4);
  dessin.forEach((l, y) => [...l].forEach((c, x) => { if (c === "B") px.set([85, 85, 85, 255], (y * 16 + x) * 4); }));
  return px;
}

ecrire("item/queue_saiyan.png", 64, 64, ICONE_QUEUE());
for (const nom of Object.keys(COULEURS)) ecrire(`item/detecteur_${nom}.png`, 64, 64, ICONE_DETECTEUR(nom));
ecrire("entity/saiyan/queue.png", 32, 128, poil(false));
ecrire("entity/saiyan/queue_duvet.png", 32, 128, poil(true));
ecrire("entity/saiyan/oreillette.png", 32, 32, oreillette());
ecrire("slot/queue.png", 16, 16, iconeSlot());
console.log("ok");
