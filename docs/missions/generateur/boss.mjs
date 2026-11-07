// Planche « Les skins des méchants » : /skin whoop1…anbu3 (les boss des missions), /skin docteur, /skin jerry, /skin boss.
// node boss.mjs → ../planche-boss.png
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, lire } from "../../spies/generateur/moteur.mjs";
import { icones } from "./icones.mjs";
import { skinsBoss } from "./skins.mjs";
import { sceneCiel, dessinerPerso, texte, rect, ambiance, halos } from "./commun.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const FOURNIS = path.join(ICI, "..", "..", "..", "mod-cabane", "outils", "skins");

export function planche() {
  const IC = icones(), SK = skinsBoss();
  SK.docteur = lire(path.join(FOURNIS, "docteur.png"));
  SK.jerry = lire(path.join(FOURNIS, "jerry.png"));
  SK.boss = lire(path.join(FOURNIS, "boss.png"));
  const liste = [
    ["whoop1", "LA PATRONNE", "/SKIN WHOOP1", { pose: "croise", lacet: 20 }],
    ["whoop2", "LE STYLISTE", "/SKIN WHOOP2", { pose: "croise", lacet: 30 }],
    ["whoop3", "BARON MAGMA", "/SKIN WHOOP3", { pose: "croise", lacet: 24 }],
    ["anbu1", "LE MIZUKAGE", "/SKIN ANBU1", { pose: "croise", lacet: 20 }],
    ["anbu2", "LE DESERTEUR", "/SKIN ANBU2", { pose: "porte", arme: "kunai", lacet: 30 }],
    ["anbu3", "LE SEIGNEUR", "/SKIN ANBU3", { pose: "porte", arme: "katana", lacet: 24 }],
    ["docteur", "DR GERO", "/SKIN DOCTEUR", { pose: "croise", lacet: 20 }],
    ["jerry", "JERRY", "/SKIN JERRY", { pose: "repos", lacet: 20 }],
    ["boss", "???", "/SKIN BOSS", { pose: "croise", lacet: 20 }],
  ];
  const C = 4, PW = 380, PH = 470, HAUT = 70, W = C * PW, H = HAUT + Math.ceil(liste.length / C) * PH;
  const img = Buffer.alloc(W * H * 4);
  rect(img, W, 0, 0, W, H, [22, 18, 26]);
  texte(img, W, "LES SKINS DES MECHANTS", 20, 18, 6, [255, 214, 90, 255]);
  texte(img, W, "/SKIN <NOM> : TON SKIN ET TON PSEUDO CHANGENT. /SKIN ENLEVER : TU REDEVIENS TOI.", 20, 54, 2, [230, 210, 200, 255]);
  liste.forEach(([id, nom, cmd, o], n) => {
    const anbu = id.startsWith("anbu");
    ambiance("jour");
    const sc = sceneCiel({ lacet: -24, tangage: 8, echelle: 9.5, w: PW, h: PH, pied: PH - 40, centre: [0, 0, 0],
      cielHaut: anbu ? [34, 44, 80] : [120, 60, 120], cielBas: anbu ? [120, 130, 170] : [250, 170, 200] });
    for (let y = PH - 64; y < PH; y++) rect(sc.img, PW, 0, y, PW, y + 1, anbu ? [70, 90, 60] : [180, 180, 190], 1);
    const m = { lumieres: [], halos: [], aLOmbre: () => false };
    dessinerPerso(sc, m, { skin: SK[id], x: 0, y: 0, z: 0, ...o }, IC);
    halos(sc, m, 0.6);
    const x0 = (n % C) * PW, y0 = HAUT + Math.floor(n / C) * PH;
    for (let y = 0; y < PH; y++) sc.img.copy(img, ((y0 + y) * W + x0) * 4, y * PW * 4, (y + 1) * PW * 4);
    const sk = SK[id], K = 2;
    rect(img, W, x0 + PW - 64 * K - 14, y0 + 10, x0 + PW - 6, y0 + 64 * K + 18, [20, 16, 14], 0.7);
    for (let y = 0; y < 64 * K; y++) for (let x = 0; x < 64 * K; x++) {
      const o2 = ((y >> 1) * 64 + (x >> 1)) * 4;
      if (sk.px[o2 + 3] < 128) continue;
      img.set([sk.px[o2], sk.px[o2 + 1], sk.px[o2 + 2], 255], ((y0 + 14 + y) * W + x0 + PW - 64 * K - 10 + x) * 4);
    }
    rect(img, W, x0, y0 + PH - 52, x0 + PW, y0 + PH, [22, 16, 20], 0.85);
    texte(img, W, nom, x0 + 14, y0 + PH - 44, 4, anbu ? [150, 200, 255, 255] : [255, 150, 210, 255]);
    texte(img, W, cmd, x0 + 14, y0 + PH - 18, 2, [236, 220, 210, 255]);
    rect(img, W, x0, y0, x0 + 2, y0 + PH, [22, 18, 26]);
  });
  return { W, H, img };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { W, H, img } = planche();
  ecrirePng(path.join(ICI, "..", "planche-boss.png"), W, H, img);
  console.log("planche des boss faite");
}
