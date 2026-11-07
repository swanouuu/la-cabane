// Planche « Les gardes et les gens des missions » : chaque skin en 3D avec ce qu'il tient, le skin à plat à côté.
// node gens.mjs → ../planche-gens.png
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng } from "../../spies/generateur/moteur.mjs";
import { icones } from "./icones.mjs";
import { skins, GENS } from "./skins.mjs";
import { sceneCiel, dessinerPerso, dessinerChien, texte, rect, ambiance, halos } from "./commun.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));

export function planche() {
  const IC = icones(), SK = skins();
  const vide = { lumieres: [], halos: [], aLOmbre: () => false };
  const liste = [
    ["agent", { pose: "porte", arme: "matraque", lacet: 30 }],
    ["employe", { pose: "repos", lacet: 20 }],
    ["mannequin", { pose: "tend", lacet: 40, teteLacet: -30 }],
    ["vigile", { pose: "pistolet", arme: "lampe_torche", lacet: 62, teteLacet: -30 }],
    ["sbire", { pose: "vise", arme: "fusil_laser", lacet: 84, teteLacet: -20 }],
    ["ninjaBrume", { pose: "tend", lanterne: true, lacet: 40 }],
    ["villageois", { pose: "croise", lacet: 10 }],
    ["deserteur", { pose: "porte", arme: "kunai", lacet: 30 }],
    ["otage", { pose: "repos", lacet: 20, taille: 0.7 }],
    ["samourai", { pose: "porte", arme: "lance", lacet: 24 }],
    ["archer", { pose: "porte", arme: "arc", lacet: 50 }],
    ["chien", null],
  ];
  const C = 4, PW = 380, PH = 470, HAUT = 70, W = C * PW, H = HAUT + Math.ceil(liste.length / C) * PH;
  const img = Buffer.alloc(W * H * 4);
  rect(img, W, 0, 0, W, H, [22, 18, 26]);
  texte(img, W, "LES GARDES ET LES GENS DES MISSIONS", 20, 18, 6, [255, 214, 90, 255]);
  texte(img, W, "ILS FONT LEUR RONDE. S'ILS TE VOIENT : ILS TE COURENT DESSUS ET TE TAPENT", 20, 54, 2, [230, 210, 200, 255]);
  liste.forEach(([id, o], n) => {
    const anbu = id === "chien" || GENS[id].serie === "anbu";
    ambiance("jour");
    const sc = sceneCiel({ lacet: -24, tangage: 8, echelle: 9.5, w: PW, h: PH, pied: PH - 40, centre: [0, 0, 0],
      cielHaut: anbu ? [34, 44, 80] : [120, 60, 120], cielBas: anbu ? [120, 130, 170] : [250, 170, 200] });
    sc.echelle = 9.5;
    for (let y = PH - 64; y < PH; y++) rect(sc.img, PW, 0, y, PW, y + 1, anbu ? [70, 90, 60] : [180, 180, 190], 1);
    const m = { ...vide, halos: [] };
    if (id === "chien") dessinerChien(sc, m, { x: 0, y: 0, z: 0, lacet: 60 });
    else dessinerPerso(sc, m, { skin: SK[id], x: 0, y: 0, z: 0, ...o }, IC);
    halos(sc, m, 0.6);
    const x0 = (n % C) * PW, y0 = HAUT + Math.floor(n / C) * PH;
    for (let y = 0; y < PH; y++) sc.img.copy(img, ((y0 + y) * W + x0) * 4, y * PW * 4, (y + 1) * PW * 4);
    if (id !== "chien") {
      const sk = SK[id], K = 2;
      rect(img, W, x0 + PW - 64 * K - 14, y0 + 10, x0 + PW - 6, y0 + 64 * K + 18, [20, 16, 14], 0.7);
      for (let y = 0; y < 64 * K; y++) for (let x = 0; x < 64 * K; x++) {
        const o2 = ((y >> 1) * 64 + (x >> 1)) * 4;
        if (sk.px[o2 + 3] < 128) continue;
        img.set([sk.px[o2], sk.px[o2 + 1], sk.px[o2 + 2], 255], ((y0 + 14 + y) * W + x0 + PW - 64 * K - 10 + x) * 4);
      }
    }
    const g = id === "chien" ? { nom: "Chien ninja", role: "Au chateau. Il te sent meme accroupi." } : GENS[id];
    rect(img, W, x0, y0 + PH - 52, x0 + PW, y0 + PH, [22, 16, 20], 0.85);
    texte(img, W, g.nom, x0 + 14, y0 + PH - 44, 4, anbu ? [150, 200, 255, 255] : [255, 150, 210, 255]);
    texte(img, W, g.role, x0 + 14, y0 + PH - 18, 2, [236, 220, 210, 255]);
    rect(img, W, x0, y0, x0 + 2, y0 + PH, [22, 18, 26]);
  });
  return { W, H, img };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { W, H, img } = planche();
  ecrirePng(path.join(ICI, "..", "planche-gens.png"), W, H, img);
  console.log("planche des gens faite");
}
