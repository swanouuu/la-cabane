// Planche « Les gars du cartel » : chaque skin en 3D avec son arme, et le skin à plat à côté.
// node gars.mjs → ../planche-gars.png
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng } from "../../spies/generateur/moteur.mjs";
import { icones } from "./armes.mjs";
import { skins, GARS } from "./skins.mjs";
import { sceneCiel, dessinerPerso, texte, largeur, rect, filtreChaud } from "./commun.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));

export function planche() {
  const IC = icones(), SK = skins();
  const liste = [
    ["patron", { pose: "pistolet", arme: "desert_eagle_or", lacet: 62, teteLacet: -40 }],
    ["sicario", { pose: "vise", arme: "ak47", lacet: 84, teteLacet: -20 }],
    ["narco", { pose: "porte", arme: "ak47_or", lacet: 30 }],
    ["carioca", { pose: "porte", arme: "uzi", lacet: 24 }],
    ["garde", { pose: "vise", arme: "rpg7", lacet: 84, teteLacet: -20 }],
    ["reina", { pose: "pistolet", arme: "mac10", lacet: 62, teteLacet: -40 }],
    ["instructeur", { pose: "croise", lacet: 6 }],
  ];
  const C = 4, PW = 380, PH = 500, HAUT = 70, W = C * PW, H = HAUT + Math.ceil(liste.length / C) * PH;
  const img = Buffer.alloc(W * H * 4);
  rect(img, W, 0, 0, W, H, [28, 20, 16]);
  texte(img, W, "LES GARS DU CARTEL", 20, 18, 6, [255, 214, 90, 255]);
  texte(img, W, "DES PNJ PARTOUT : DANS L'ALLEE, SUR LES MIRADORS, AU STAND DE TIR, DANS LA FAVELA", 20, 54, 2, [230, 210, 180, 255]);
  liste.forEach(([id, o], n) => {
    const sc = sceneCiel({ lacet: -24, tangage: 8, echelle: 9.5, w: PW, h: PH, pied: PH - 44, cielHaut: [70, 118, 170], cielBas: [246, 196, 128], centre: [0, 0, 0] });
    // le sol : de la terre sèche
    for (let y = PH - 70; y < PH; y++) rect(sc.img, PW, 0, y, PW, y + 1, [150 - (y - PH + 70), 112 - (y - PH + 70) * 0.8, 72], 1);
    dessinerPerso(sc, null, { skin: SK[id], x: 0, y: 0, z: 0, ...o }, IC);
    filtreChaud(sc, 0.8);
    const x0 = (n % C) * PW, y0 = HAUT + Math.floor(n / C) * PH;
    for (let y = 0; y < PH; y++) sc.img.copy(img, ((y0 + y) * W + x0) * 4, y * PW * 4, (y + 1) * PW * 4);
    // le skin à plat, en petit dans le coin
    const sk = SK[id], K = 2;
    rect(img, W, x0 + PW - 64 * K - 14, y0 + 10, x0 + PW - 6, y0 + 64 * K + 18, [20, 16, 14], 0.7);
    for (let y = 0; y < 64 * K; y++) for (let x = 0; x < 64 * K; x++) {
      const o2 = ((y >> 1) * 64 + (x >> 1)) * 4;
      if (sk.px[o2 + 3] < 128) continue;
      img.set([sk.px[o2], sk.px[o2 + 1], sk.px[o2 + 2], 255], ((y0 + 14 + y) * W + x0 + PW - 64 * K - 10 + x) * 4);
    }
    const g = GARS[id];
    rect(img, W, x0, y0 + PH - 52, x0 + PW, y0 + PH, [22, 16, 14], 0.85);
    texte(img, W, g.nom, x0 + 14, y0 + PH - 44, 4, [255, 214, 90, 255]);
    texte(img, W, g.role, x0 + 14, y0 + PH - 18, 2, [236, 220, 196, 255]);
    rect(img, W, x0, y0, x0 + 2, y0 + PH, [28, 20, 16]);
  });
  // la dernière case : les voix et les bruits
  const n = liste.length, x0 = (n % C) * PW, y0 = HAUT + Math.floor(n / C) * PH;
  const lignes = ["CE QU'ON ENTEND", "", "- DES RAFALES AU STAND DE TIR", "  (LES PNJ TIRENT POUR DE VRAI", "  SUR LES CIBLES)", "",
    "- LA MUSIQUE : GUITARE, TROMPETTE,", "  AMBIANCE NARCO", "", "- LES CHIENS, LES MOBYLETTES,", "  LES GENS QUI PARLENT ESPAGNOL", "  ET PORTUGAIS", "",
    "- CHAQUE ARME A SON BRUIT", "  (CLAC DE LA KALACH, BOUM DU", "  BAZOOKA, BRRRT DU MINIGUN)"];
  lignes.forEach((l, k) => texte(img, W, l, x0 + 24, y0 + 30 + k * 26, k === 0 ? 4 : 2, k === 0 ? [255, 214, 90, 255] : [236, 220, 196, 255]));
  return { W, H, img };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { W, H, img } = planche();
  ecrirePng(path.join(ICI, "..", "planche-gars.png"), W, H, img);
  console.log("planche des gars faite");
}
