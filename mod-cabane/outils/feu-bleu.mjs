// Les flammes bleues du phénix posées au sol (pouvoir/FeuBleu.java) : la forme du feu de Minecraft et la texture animée
// du feu des âmes (minecraft:block/soul_fire_0 et soul_fire_1, prises dans le jeu lui-même, jamais copiées ici), un peu
// poussées vers le cyan par ClientFeuNoir. Ce script n'écrit que l'état de bloc et les deux modèles.
// node mod-cabane/outils/feu-bleu.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(ICI, "..", "src", "main", "resources", "assets", "cabane");

const ecrire = (rel, objet) => {
  const f = path.join(RES, rel);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, JSON.stringify(objet, null, 2) + "\n");
};

const face = { uv: [0, 0, 16, 16], texture: "#fire", tintindex: 0 };
const plan = (from, to, axe, angle, faces) => ({
  from, to, rotation: { origin: [8, 8, 8], axis: axe, angle, rescale: true }, shade: false,
  faces: Object.fromEntries(faces.map((f) => [f, face])),
});
const elements = [
  plan([0, 0, 8.8], [16, 22.4, 8.8], "x", -22.5, ["south", "north"]),
  plan([0, 0, 7.2], [16, 22.4, 7.2], "x", 22.5, ["north", "south"]),
  plan([8.8, 0, 0], [8.8, 22.4, 16], "z", -22.5, ["west", "east"]),
  plan([7.2, 0, 0], [7.2, 22.4, 16], "z", 22.5, ["east", "west"]),
];
for (const k of [0, 1]) {
  ecrire(`models/block/feu_bleu_${k}.json`, {
    render_type: "minecraft:cutout",
    ambientocclusion: false,
    textures: { particle: `minecraft:block/soul_fire_${k}`, fire: `minecraft:block/soul_fire_${k}` },
    elements,
  });
}
ecrire("blockstates/feu_bleu.json", {
  variants: {
    "": [0, 1].flatMap((k) => [0, 90, 180, 270].map((y) => (y ? { model: `cabane:block/feu_bleu_${k}`, y } : { model: `cabane:block/feu_bleu_${k}` }))),
  },
});
console.log("Flammes bleues écrites dans", RES);
