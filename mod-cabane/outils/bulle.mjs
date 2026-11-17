// La bulle de verre (accessoire/Bulle.java) : son icône, sa texture de rendu (blanche : client/RenduBulle la teinte et la
// rend transparente), son nom. Sur la tête, un fruit du démon ne prend plus de dégâts dans l'eau. Le slot de tête et la
// liste des objets qui y vont sont dans curios.mjs. Appelé par generer.mjs.

const ICONE = [
  "................",
  ".....AAAAAA.....",
  "...AABBBBBBAA...",
  "..ABWWBBBBBBBA..",
  "..AWWBBBBBBBBA..",
  ".ABWBBBBBBBBBBA.",
  ".ABBBBBBBBBBBBA.",
  ".ABBBBBBBBBBBBA.",
  ".ABBBBBBBBBBBCA.",
  ".ABBBBBBBBBBCCA.",
  "..ABBBBBBBBCCA..",
  "..ABBBBBBBCCCA..",
  "...KMMMMMMMMK...",
  "...KLLLLLLLLK...",
  "....KKKKKKKK....",
  "................",
];

export function genererBulle({ ecrire, png, lang }) {
  ecrire("assets/cabane/textures/item/bulle_verre.png", png(ICONE, {
    A: [150, 215, 240, 255], B: [170, 225, 245, 90], C: [120, 190, 225, 150], W: [255, 255, 255, 255],
    K: [60, 64, 72], M: [200, 205, 215], L: [140, 146, 158],
  }));
  ecrire("assets/cabane/models/item/bulle_verre.json", { parent: "minecraft:item/generated", textures: { layer0: "cabane:item/bulle_verre" } });
  ecrire("assets/cabane/textures/entity/bulle.png", png(Array(16).fill("W".repeat(16)), { W: [255, 255, 255, 255] }));
  lang["item.cabane.bulle_verre"] = "Bulle de verre";
  lang["item.cabane.bulle_verre.desc"] = "Accessoire de tête : avec un fruit du démon, l'eau ne fait plus mal.";
}
