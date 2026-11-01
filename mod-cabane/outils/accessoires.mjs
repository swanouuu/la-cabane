// Les accessoires marrants (accessoire/Accessoires.java) : l'icône de chacun (16 x 16) et son dessin sur la tête
// (calque de casque 64 x 32 : la face avant de la tête va de x 8 à 15, y 8 à 15 ; les côtés, x 0-7 et 16-23).
// Appelé par generer.mjs.

// [nom, icône, palette, dessin sur la face avant (8 x 8), couleur des branches sur les côtés (ou rien)]
const ACCESSOIRES = [
  ["lunettes_soleil", "Lunettes de soleil", [
    "................", "................", "................", "................", "................", "KKKKKKKKKKKKKKKK",
    "KLLLLLKKKKLLLLLK", "KLWLLLK..KLWLLLK", "KLLLLLK..KLLLLLK", ".KLLLK....KLLLK.", "..KKK......KKK..", "................",
    "................", "................", "................", "................"], { K: [20, 20, 24], L: [40, 40, 60], W: [200, 200, 220] },
    ["........", "........", "........", "KKKKKKKK", "LLLKKLLL", "LWLKKLWL", ".LL..LL.", "........"], "K"],
  ["lunettes_coeur", "Lunettes cœur", [
    "................", "................", "................", "................", "..RR.RR..RR.RR..", ".RPPRPPRRPPRPPR.",
    "KRPPPPPKKPPPPPRK", ".RPPPPPR.RPPPPR.", "..RPPPR...RPPR..", "...RPR.....RR...", "....R...........", "................",
    "................", "................", "................", "................"], { R: [200, 30, 90], P: [255, 120, 180], K: [200, 30, 90] },
    ["........", "........", "........", "RRRRRRRR", "PPRRRPPR", ".PR..PR.", "..R...R.", "........"], "R"],
  ["lunettes_3d", "Lunettes 3D", [
    "................", "................", "................", "................", "................", "WWWWWWWWWWWWWWWW",
    "WRRRRRWWWWBBBBBW", "WRRRRRW..WBBBBBW", "WRRRRRW..WBBBBBW", "WWWWWWW..WWWWWWW", "................", "................",
    "................", "................", "................", "................"], { W: [240, 240, 240], R: [230, 40, 40], B: [40, 200, 240] },
    ["........", "........", "........", "WWWWWWWW", "RRRWWBBB", "RRRWWBBB", "WWW..WWW", "........"], "W"],
  ["lunettes_pixel", "Lunettes « deal with it »", [
    "................", "................", "................", "................", "................", "KKKKKKKKKKKKKKKK",
    "KWWKKKKKKWWKKKK.", "KKWWKKKKKKWWKKK.", ".KKKKKK..KKKKKK.", "..KKKK....KKKK..", "................", "................",
    "................", "................", "................", "................"], { K: [10, 10, 10], W: [250, 250, 250] },
    ["........", "........", "........", "KKKKKKKK", "KWKKKWKK", "KKKK.KKK", ".KK...KK", "........"], "K"],
  ["moustache", "Moustache", [
    "................", "................", "................", "................", "................", "................",
    "......MMMM......", "..MMMMMMMMMMMM..", ".MMMMMMMMMMMMMM.", "MMMM..MMMM..MMMM", "MM............MM", "................",
    "................", "................", "................", "................"], { M: [70, 40, 20] },
    ["........", "........", "........", "........", "........", "..MMMM..", ".MMMMMM.", "MM....MM"], null],
  ["nez_clown", "Nez de clown", [
    "................", "................", "................", "................", ".....RRRRRR.....", "....RRRRRRRR....",
    "...RRWWRRRRRR...", "...RRWRRRRRRR...", "...RRRRRRRRRR...", "...RRRRRRRRRR...", "....RRRRRRRR....", ".....RRRRRR.....",
    "................", "................", "................", "................"], { R: [220, 30, 30], W: [255, 160, 160] },
    ["........", "........", "........", "........", "...RR...", "...WR...", "........", "........"], null],
];

/** Le calque de casque (64 x 32) : le dessin sur la face avant, et une ligne (les branches des lunettes) sur les côtés. */
function calque(face, branche) {
  const px = Array.from({ length: 32 }, () => Array(64).fill("."));
  face.forEach((ligne, y) => [...ligne].forEach((c, x) => { px[8 + y][8 + x] = c; }));
  if (branche) {
    const y = 8 + face.findIndex((l) => /^[^.]{8}$/.test(l)); // la ligne pleine : le haut de la monture
    for (let x = 0; x < 8; x++) { px[y][x] = branche; px[y][16 + x] = branche; }
  }
  return px.map((l) => l.join(""));
}

export function genererAccessoires({ ecrire, png, lang }) {
  for (const [id, nom, icone, palette, face, branche] of ACCESSOIRES) {
    ecrire(`assets/cabane/textures/item/${id}.png`, png(icone, palette));
    ecrire(`assets/cabane/models/item/${id}.json`, { parent: "minecraft:item/generated", textures: { layer0: `cabane:item/${id}` } });
    ecrire(`assets/cabane/textures/models/armor/${id}_layer_1.png`, png(calque(face, branche), palette));
    lang[`item.cabane.${id}`] = nom;
  }
}

/** Chez Mouloud : [fichier, objet, nombre, prix]. */
export const OFFRES_ACCESSOIRES = ACCESSOIRES.map(([id]) => [id, `cabane:${id}`, 1, id === "nez_clown" ? 50 : 150]);
