// La planche du cartel : la vue du ciel de toute la dimension, puis trois gros plans (la grande allée, la boutique du
// patron en coupe, le stand de tir) et comment ça marche. Et la planche des armes avec leurs prix.
// node rendu.mjs → ../planche-cartel.png, ../planche-armes.png (et ../planche-gars.png)
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, hache, POLICE } from "../../spies/generateur/moteur.mjs";
import { icones, ARMES } from "./armes.mjs";
import { skins } from "./skins.mjs";
import { planche as plancheGars } from "./gars.mjs";
import { construire, MUR } from "./monde.mjs";
import { sceneCiel, rendreMonde, dessinerPerso, armePosee, plaque, trait2d, flamme, fumee, etiquette, filtreChaud, texte, largeur, rect, ajoute, fois } from "./commun.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const IC = icones(), SK = skins();
const m = construire();

// l'enseigne EL CARTEL sur l'arche du portail, en lettres d'or sur le bois
{
  const { x0, x1, y0, y1, z, texte: mot } = m.enseigne;
  const cols = mot.length * 4 - 1, L = x1 - x0, H = y1 - y0;
  m.peintures.push((x, y, zz, face, u, v) => {
    if (face !== "sud" || zz !== z - 1 || x < x0 || x >= x1 || y < y0 || y >= y1) return null;
    const gx = ((x - x0 + u) / L) * (cols + 2) - 1, gy = ((y1 - (y + 1 - v)) / H) * 7 - 1;
    if (gx < 0 || gy < 0 || gx >= cols || gy >= 5) return null;
    const c = Math.floor(gx / 4), dans = Math.floor(gx) % 4;
    if (dans === 3) return null;
    const motif = POLICE[mot[c]] ?? POLICE[" "];
    return motif[Math.floor(gy) * 3 + dans] === "1" ? [255, 210, 70] : null;
  });
}

/** Les persos, les armes posées, les lignes et les effets d'une vue. dans(x, y, z) dit ce qu'on garde. */
function peupler(sc, dans, { etiquettesPersos = false, tirs = true } = {}) {
  for (const o of m.objets) {
    if (o.type !== "arme" || !dans(...o.centre)) continue;
    armePosee(sc, m, IC[o.id], o.centre, o.avant, o.haut, o.taille);
  }
  const bouches = [];
  for (const pe of m.persos) {
    if (!dans(pe.x, pe.y + 1, pe.z)) continue;
    const bouche = dessinerPerso(sc, m, { ...pe, skin: SK[pe.skin] }, IC);
    if (bouche && pe.tire && tirs) bouches.push([bouche, pe]);
    if (bouche && pe.surPickup && tirs) bouches.push([bouche, { ...pe, tire: [pe.x + 1, 3, pe.z + 14] }]);
  }
  for (const l of m.lignes) {
    if (!dans(...l.a) && !dans(...l.b)) continue;
    const A = fois(l.a, 16), B = fois(l.b, 16);
    if (l.barbele) {
      trait2d(sc, A, B, [70, 70, 76], 0, 1);
      const n = Math.round(Math.hypot(...l.b.map((v, i) => v - l.a[i])) * 1.5);
      for (let k = 0; k < n; k++) { const p = ajoute(A, fois(B.map((v, i) => v - A[i]), k / n)); trait2d(sc, ajoute(p, [-3, -3, 0]), ajoute(p, [3, 3, 0]), [60, 60, 66], 0, 1); }
    }
    if (l.fanions) {
      // la ficelle qui pend un peu, et les fanions de papier de toutes les couleurs
      const n = 22, cols = [[236, 64, 140], [250, 200, 40], [60, 180, 90], [60, 140, 230], [250, 120, 40], [170, 80, 220]];
      let prec = null;
      for (let k = 0; k <= n; k++) {
        const t = k / n, p = ajoute(ajoute(A, fois(B.map((v, i) => v - A[i]), t)), [0, -14 * Math.sin(Math.PI * t), 0]);
        if (prec) trait2d(sc, prec, p, [240, 236, 220], 0, 0.9);
        if (k < n) {
          const q = ajoute(ajoute(A, fois(B.map((v, i) => v - A[i]), t + 0.5 / n)), [0, -14 * Math.sin(Math.PI * (t + 0.5 / n)) - 7, 0]);
          const c = cols[k % cols.length];
          for (let s = 0; s <= 1; s += 0.1) trait2d(sc, ajoute(p, fois(ajoute(q, fois(p, -1)), s)), ajoute(ajoute(p, [5, 0, 0]), fois(ajoute(q, fois(ajoute(p, [5, 0, 0]), -1)), s)), c, 0.6, 1);
        }
        prec = p;
      }
    }
  }
  // les rafales : la traçante jusqu'à la cible, la flamme au bout du canon, un peu de fumée
  for (const [b, pe] of bouches) {
    const cible = fois(pe.tire, 16);
    trait2d(sc, b, cible, [255, 236, 140], 0.6, 0.9);
    flamme(sc, b, Math.max(3, sc.echelle * 6));
    fumee(sc, ajoute(b, [0, 6, -4]), Math.max(4, sc.echelle * 9));
    flamme(sc, cible, Math.max(2, sc.echelle * 3));
  }
}

function vue(o, dans, opt) {
  const sc = sceneCiel(o);
  sc.echelle = o.echelle;
  rendreMonde(sc, m, { n: o.n ?? 4, clip: o.clip ?? null });
  peupler(sc, dans, opt);
  return sc;
}

// ------------------------------------------------------------------ la vue du ciel
const W = 1600;
const ciel = vue({ lacet: -32, tangage: 30, echelle: 0.56, w: W, h: 1080, pied: 640, n: 4 }, () => true);
const lab = (p, s, dy = -46) => etiquette(ciel, fois(p, 16), s, { t: 3, dy });
lab([0, 9, 59], "L'ARRIVEE  /CARTEL", 34);
lab([0, 2, 30], "LA GRANDE ALLEE DES ARMES", -60);
lab([0, 19, -24], "LA HACIENDA D'EL PATRON", -40);
lab([30, 2, 26], "LE STAND DE TIR", -70);
lab([-26, 5, 33], "L'HELICO", -40);
lab([-28, 6, 2], "LE DEPOT", -40);
lab([28, 1, -24], "LA PISCINE", -34);
lab([-20, 22, -50], "LA FAVELA", -40);
lab([MUR.x1, 11, MUR.z1], "MIRADOR", 40);
filtreChaud(ciel, 1);

// ------------------------------------------------------------------ les gros plans
const PW = 800, PH = 600;
const PALMIER = new Set(["tronc", "palme"]);
const allee = vue({ lacet: 52, tangage: 14, echelle: 3.1, w: PW, h: PH, pied: PH * 0.6, centre: [5 * 16, 2 * 16, 36 * 16], n: 8, clip: (x, y, z) => z >= 22 && z <= 50 && x >= -4 && x <= 16 && !PALMIER.has(m.get(x, y, z)) },
  (x, y, z) => z >= 22 && z <= 50 && x >= -4 && x <= 16);
filtreChaud(allee, 1);

const boutique = vue({ lacet: -12, tangage: 20, echelle: 3.1, w: PW, h: PH, pied: PH * 0.6, centre: [0, 2.2 * 16, -26.5 * 16], n: 8, clip: (x, y, z) => y <= 5 && z <= -18 && z >= -33 && x >= -18 && x <= 18 },
  (x, y, z) => y <= 6 && z <= -18 && z >= -33 && x >= -18 && x <= 18);
etiquette(boutique, fois([m.patron[0], 3.3, m.patron[2]], 16), "EL PATRON : CLIC DROIT", { t: 3, dy: -64 });
filtreChaud(boutique, 0.7);

const tir = vue({ lacet: -146, tangage: 18, echelle: 2.5, w: PW, h: PH, pied: PH * 0.5, centre: [28 * 16, 1 * 16, 36 * 16], n: 8, clip: (x, y, z) => x >= 16 && x <= 46 && z >= 6 && z <= 50 },
  (x, y, z) => x >= 16 && x <= 46 && z >= 6 && z <= 50);
filtreChaud(tir, 1);

// ------------------------------------------------------------------ la planche du cartel
const TITRE = 96, BANDE = 40;
const H = TITRE + ciel.h + 2 * (PH + BANDE);
const img = Buffer.alloc(W * H * 4);
rect(img, W, 0, 0, W, H, [28, 20, 16]);
const coller = (sc, x0, y0) => { for (let y = 0; y < sc.h; y++) sc.img.copy(img, ((y0 + y) * W + x0) * 4, y * sc.w * 4, (y + 1) * sc.w * 4); };
texte(img, W, "LE CARTEL", 22, 18, 9, [255, 214, 90, 255]);
texte(img, W, "UNE DIMENSION A PART : LES ARMES, LES NARCOS, LA FAVELA. ON Y VA AVEC /CARTEL", 22, 70, 3, [236, 220, 196, 255]);
coller(ciel, 0, TITRE);
const titrePanneau = (s, x0, y0) => { rect(img, W, x0, y0, x0 + PW, y0 + BANDE, [22, 16, 14]); texte(img, W, s, x0 + 16, y0 + 10, 4, [255, 214, 90, 255]); };
let y0 = TITRE + ciel.h;
titrePanneau("LA GRANDE ALLEE : DES STANDS D'ARMES PARTOUT", 0, y0); coller(allee, 0, y0 + BANDE);
titrePanneau("CHEZ EL PATRON : IL TE VEND TOUT", PW, y0); coller(boutique, PW, y0 + BANDE);
y0 += PH + BANDE;
titrePanneau("LE STAND DE TIR : LES PNJ TIRENT POUR DE VRAI", 0, y0); coller(tir, 0, y0 + BANDE);
titrePanneau("COMMENT CA MARCHE", PW, y0);
{
  const x0 = PW + 30;
  let y = y0 + BANDE + 26;
  const ligne = (s, t = 2, c = [236, 220, 196, 255], saut = 24) => { texte(img, W, s, x0, y, t, c); y += saut; };
  const titre = (s) => { y += 8; ligne(s, 3, [255, 214, 90, 255], 30); };
  titre("Y ALLER");
  ligne("/CARTEL : TU Y VAS, TU ARRIVES DEVANT LE PORTAIL");
  ligne("/CARTEL @JOUEUR : TU Y ENVOIES QUELQU'UN (L'HOTE)");
  ligne("BOUTON ROUGE A L'ARRIVEE OU /CARTEL RETOUR :");
  ligne("TU REVIENS PILE LA OU TU ETAIS");
  titre("ACHETER");
  ligne("CLIC DROIT SUR EL PATRON : SON MENU DES ARMES,");
  ligne("PAS CHER (DE 30 $ A 2000 $), AVEC TES $ DU SERVEUR");
  ligne("LES STANDS DE L'ALLEE, C'EST LA DECO : TOUT SE");
  ligne("PREND CHEZ LUI");
  titre("TIRER");
  ligne("CLIC DROIT : TIRER. MUNITIONS INFINIES, MAIS IL FAUT");
  ligne("RECHARGER (TOUCHE R). FUSILS ET BAZOOKA A DEUX MAINS");
  ligne("LES EXPLOSIONS FONT MAL MAIS NE CASSENT AUCUN BLOC");
  ligne("LES ARMES MARCHENT PARTOUT, PAS QUE AU CARTEL");
  titre("SUR PLACE");
  ligne("AU CARTEL ON NE CASSE RIEN (MODE AVENTURE)");
  ligne("PLEIN DE PNJ : ILS NE T'ATTAQUENT PAS");
  ligne("BRUITS DE RAFALES, MUSIQUE NARCO, FIN D'APRES-MIDI");
}
ecrirePng(path.join(ICI, "..", "planche-cartel.png"), W, H, img);
console.log("planche du cartel faite");

// ------------------------------------------------------------------ la planche des armes
{
  const C = 5, CW = 320, CH = 360, HT = 90, WA = C * CW, HA = HT + Math.ceil(ARMES.length / C) * CH;
  const ia = Buffer.alloc(WA * HA * 4);
  rect(ia, WA, 0, 0, WA, HA, [28, 20, 16]);
  texte(ia, WA, "LES ARMES D'EL PATRON", 20, 16, 7, [255, 214, 90, 255]);
  texte(ia, WA, "EN 3D DANS LES MAINS ET SUR LES STANDS, 64X64 DANS L'INVENTAIRE. TOUT S'ACHETE CHEZ LUI.", 20, 66, 2, [236, 220, 196, 255]);
  ARMES.forEach((a, n) => {
    const x0 = (n % C) * CW, y0 = HT + Math.floor(n / C) * CH;
    rect(ia, WA, x0 + 6, y0 + 6, x0 + CW - 6, y0 + CH - 6, [46, 34, 26]);
    rect(ia, WA, x0 + 6, y0 + 6, x0 + CW - 6, y0 + 10, [255, 214, 90]);
    // l'arme en 3D, de trois quarts, sous une lumière chaude
    const im = IC[a.id];
    let haut = 64, bas = 0;
    for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) if (im.prof[y * 64 + x] > 0) { haut = Math.min(haut, y); bas = Math.max(bas, y); }
    const cy = (haut + bas + 1) / 2, grand = bas - haut > 36;
    const sc = sceneCiel({ lacet: -30, tangage: 16, echelle: grand ? 2.9 : 3.7, w: 296, h: 170, pied: 85, centre: [0, 0, 0], cielHaut: [70, 54, 44], cielBas: [118, 88, 64] });
    const pt = (ix, iy, e) => [ix - 32, -(iy - cy), e];
    plaque(sc, im, pt, 1, { A: [1, 0, 0], H: [0, 1, 0], G: [0, 0, 1] }, false);
    for (let y = 0; y < sc.h; y++) sc.img.copy(ia, ((y0 + 14 + y) * WA + x0 + 12) * 4, y * sc.w * 4, (y + 1) * sc.w * 4);
    // l'icône d'inventaire, dans sa case, en bas à gauche de l'image
    const cx0 = x0 + 18, cy0 = y0 + 14 + 170 - 74;
    rect(ia, WA, cx0, cy0, cx0 + 68, cy0 + 68, [55, 55, 55]); rect(ia, WA, cx0 + 2, cy0 + 2, cx0 + 66, cy0 + 66, [139, 139, 139]);
    for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) {
      const o = (y * 64 + x) * 4, al = im.px[o + 3] / 255;
      if (!al) continue;
      const d = ((cy0 + 2 + y) * WA + cx0 + 2 + x) * 4;
      for (let q = 0; q < 3; q++) ia[d + q] = ia[d + q] * (1 - al) + im.px[o + q] * al;
    }
    const yt = y0 + 194;
    const prix = a.prix + " $", tNom = largeur(a.nom, 3) > CW - 70 - largeur(prix, 3) ? 2 : 3;
    texte(ia, WA, a.nom, x0 + 20, yt + (tNom === 2 ? 4 : 0), tNom, [255, 236, 200, 255]);
    rect(ia, WA, x0 + CW - 26 - largeur(prix, 3) - 10, yt - 6, x0 + CW - 20, yt + 20, [255, 214, 90]);
    texte(ia, WA, prix, x0 + CW - 26 - largeur(prix, 3) - 4, yt, 3, [40, 26, 10, 255]);
    const barre = (nom, v, y) => {
      texte(ia, WA, nom, x0 + 20, y, 2, [200, 184, 160, 255]);
      for (let k = 0; k < 10; k++) rect(ia, WA, x0 + 120 + k * 17, y - 1, x0 + 134 + k * 17, y + 11, k < v ? [236, 90 + k * 12, 40] : [70, 56, 46]);
    };
    barre("DEGATS", Math.min(10, Math.ceil(a.degats / 1.8)), yt + 34); barre("CADENCE", a.cadence, yt + 54); barre("PORTEE", a.portee, yt + 74);
    // la petite phrase, coupée en lignes
    const lignes = [""];
    for (const w of a.ligne.split(" ")) { if (largeur(lignes.at(-1) + " " + w, 2) > CW - 44) lignes.push(w); else lignes[lignes.length - 1] = (lignes.at(-1) + " " + w).trim(); }
    lignes.forEach((l, k) => texte(ia, WA, l, x0 + 20, yt + 100 + k * 18, 2, [236, 220, 196, 255]));
  });
  ecrirePng(path.join(ICI, "..", "planche-armes.png"), WA, HA, ia);
  console.log("planche des armes faite");
}
{
  const { W: WG, H: HG, img: ig } = plancheGars();
  ecrirePng(path.join(ICI, "..", "planche-gars.png"), WG, HG, ig);
}
