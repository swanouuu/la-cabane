// Les modèles d'Ichibi pour le jeu (mod-cabane), tirés des mêmes dessins que la planche de la refonte (refonte.mjs) :
// la jarre, le sable qui tourne, les poings, les cercueils, le rocher, la statue du mausolée, la vague et la bête du
// niveau 3, face par face (format de Maillage.java). Les cubes sont plus gros que sur la planche (PAS.k), pour que le jeu
// tienne.
// node jeu.mjs → mod-cabane/src/main/resources/assets/cabane/ichibi/*.bin
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { norme, croix, mix, hache } from "../../fruits/generateur/moteur.mjs";
import { sous, ajoute, fois, ID, pointe } from "../../fruits/generateur/rendu.mjs";
import { SABLE, SABLE_F, ENCRE, bruit, membre, motif } from "./rendu.mjs";
import {
  PAS, volume, jarre, statue, vagueSable, boule, bosse, sableI, dune, ellip, boudin, fondu, boite, helice, cocon, rocher,
  teteBete, grosseQueue, bras3, DUNE, DUNE_F, J_BOUT, bezier,
} from "./refonte.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const SORTIE = path.join(ICI, "..", "..", "..", "mod-cabane", "src", "main", "resources", "assets", "cabane", "ichibi");

// ------------------------------------------------------------------ les faces, enregistrées au lieu d'être dessinées
/** Une fausse scène qui garde chaque face (ses coins, sa couleur, sa normale) au lieu de la dessiner (comme jeu3 des fruits). */
function enregistrer(dessin) {
  const faces = [];
  const sc = {
    w: 1, h: 1, img: Buffer.alloc(4),
    lumiere: () => 1,
    dirVue: (n) => n,
    vue: () => [0, 0, 0],
    voile() {},
    lueur() {},
    quad(coins, c, decale, aile, info = {}) {
      let n = info.normale ?? norme(croix(sous(coins[1], coins[0]), sous(coins[2], coins[0])));
      if (info.centre) {
        const m = fois(coins.reduce((s, p) => ajoute(s, p), [0, 0, 0]), 0.25);
        const d = sous(m, info.centre);
        if (n[0] * d[0] + n[1] * d[1] + n[2] * d[2] < 0) n = fois(n, -1);
      }
      faces.push({ coins: coins.map((p) => [...p]), c: c.slice(0, 3), emissif: !!info.emissif, n });
    },
  };
  dessin(sc);
  return faces;
}

/** Du repère des planches (y vers le haut, l'avant vers +z, en pixels) à celui d'une partie du joueur dans le jeu (y vers le bas, l'avant vers -z). */
const partie = (px, py) => (f) => ({ ...f, coins: f.coins.map(([x, y, z]) => [x - px, 24 - y - py, -z]), n: [f.n[0], -f.n[1], -f.n[2]] });
const CORPS = partie(0, 0), JAMBE_D = partie(-1.9, 12), JAMBE_G = partie(1.9, 12);

/** Le fichier : le nombre de groupes, puis pour chacun le nombre de faces, et chaque face (gros-boutiste, comme DataInputStream). */
function ecrire(nom, groupes) {
  const taille = 4 + groupes.reduce((s, g) => s + 4 + g.length * (15 * 4 + 4), 0);
  const dv = new DataView(new ArrayBuffer(taille));
  let o = 0;
  dv.setInt32(o, groupes.length); o += 4;
  for (const g of groupes) {
    dv.setInt32(o, g.length); o += 4;
    for (const f of g) {
      for (const p of f.coins) for (const v of p) { dv.setFloat32(o, v); o += 4; }
      for (const v of f.n) { dv.setFloat32(o, v); o += 4; }
      for (const v of f.c) { dv.setUint8(o, Math.max(0, Math.min(255, Math.round(v)))); o += 1; }
      dv.setUint8(o, f.emissif ? 1 : 0); o += 1;
    }
  }
  fs.mkdirSync(SORTIE, { recursive: true });
  fs.writeFileSync(path.join(SORTIE, nom + ".bin"), Buffer.from(dv.buffer));
  console.log(nom, groupes.map((g) => g.length).join(" + "), "faces");
}

const avec = (k, f) => { const avant = PAS.k; PAS.k = k; try { return f(); } finally { PAS.k = avant; } };
const quoi = process.argv.slice(2);
const veut = (n) => !quoi.length || quoi.includes(n);

// ------------------------------------------------------------------ la jarre (repère du buste)
if (veut("jarre")) {
  avec(1.6, () => {
    // le corps (goulot creux) et la sangle ; le bouchon à part (fermée, il est dessus ; à l'éveil, il saute)
    const ouverte = enregistrer((sc) => jarre(sc, ID, { ouverte: true })).map(CORPS);
    const bouchon = enregistrer((sc) => volume(sc, ID, boudin(ajoute(J_BOUT, [0, -0.4, 0]), ajoute(J_BOUT, [0.15, 1.4, 0]), 1.25, 1.05), [-5, 29, -11, 0, 34, -5], 0.3, () => [92, 64, 44])).map(CORPS);
    ecrire("jarre", [ouverte, bouchon]);
    const cassee = enregistrer((sc) => jarre(sc, ID, { casse: 0.75, ouverte: true })).map(CORPS);
    ecrire("jarre_cassee", [cassee]);
  });
}

// ------------------------------------------------------------------ le sable qui tourne (dans le monde, autour de ses pieds)
if (veut("tourbillon")) {
  avec(1.6, () => {
    // niveau 1 : le gros ruban de sable de Gaara ; niveau 2 : un ruban plus fin, plus large, plus bas
    const n1 = enregistrer((sc) => volume(sc, ID, helice([0, 0, 0], { r0: 15, haut: 22, tours: 1.4, k: 2, ep: [1.1, 2.6] }), [-24, -2, -24, 24, 40, 24], 0.6, dune(7)));
    const n2 = enregistrer((sc) => volume(sc, ID, helice([0, 0, 0], { r0: 19, haut: 14, tours: 1.2, k: 10, ep: [0.8, 1.8], fin: 0.8 }), [-26, -2, -26, 26, 30, 26], 0.6, dune(15)));
    // la tempête de l'ulti : le grand ruban qui monte autour de lui
    const n3 = enregistrer((sc) => volume(sc, ID, helice([0, 0, 0], { r0: 24, haut: 44, tours: 2.4, k: 30, ep: [1.6, 3.4] }), [-32, -2, -32, 32, 56, 32], 0.7, dune(150)));
    ecrire("tourbillon", [n1, n2, n3]);
  });
}

// ------------------------------------------------------------------ les poings de sable du niveau 1 (dans le monde, vers +z)
if (veut("poing")) {
  avec(1.4, () => {
    // le poing seul (sans le bras, que le jeu dessine en tubes), centré, les jointures vers +z ; cote : +1 gauche, -1 droit
    for (const cote of [-1, 1]) {
      const E = 2.3;
      const poing = fondu(0.6, boite([0, 0, 0], [3.8, 3.4, 3], 1.3), ...[-2.85, -0.95, 0.95, 2.85].map((x) => boule([x, 1.3, 2.9], 1.45)),
        boudin([-3.4, -0.9, 2.6], [3.4, -0.9, 2.6], 1.55), boudin([cote * 3.9, -1.2, -0.4], [cote * 2.2, -2.3, 2.3], 1.3), boudin([0, 0, -1.5], [0, -0.3, -6.5], 2.6, 2.3));
      const f = (p) => poing([p[0] / E, p[1] / E, p[2] / E]) * E + 0.3 * bosse(p, 1.4, 975 + cote);
      const faces = enregistrer((sc) => volume(sc, ID, f, [-12, -11, -17, 12, 11, 12], 0.6, dune(10 + cote)));
      ecrire(cote < 0 ? "poing_d" : "poing_g", [faces]);
    }
  });
}

// ------------------------------------------------------------------ un bout de tube de sable (16 pixels de long, le long de +y)
if (veut("tube")) {
  avec(1, () => {
    const f = (p) => boudin([0, 0, 0], [0, 16, 0], 4)(p) + 0.35 * bosse(p, 1.5, 960);
    ecrire("tube", [
      enregistrer((sc) => volume(sc, ID, f, [-6, -5, -6, 6, 21, 6], 1, dune(3))),
      enregistrer((sc) => volume(sc, ID, f, [-6, -5, -6, 6, 21, 6], 1, sableI(70))),
    ]);
  });
}

// ------------------------------------------------------------------ les cercueils (dans le monde, ses pieds en 0)
if (veut("cercueil")) {
  avec(1.8, () => {
    // la hauteur du sable : 4 étapes jusqu'au cou ; puis serré (les pointes de sable le transpercent)
    const H = [6, 12, 18, 24.4];
    for (const [nom, couleur, cle] of [["cercueil", dune(31), 0], ["cercueil_ichibi", sableI(31, 1), 1]]) {
      const groupes = H.map((h, k) => enregistrer((sc) => cocon(sc, [0, 0, 0], h, 0, k, couleur)));
      groupes.push(enregistrer((sc) => {
        cocon(sc, [0, 0, 0], 24.4, 1, 2, couleur);
        for (let q = 0; q < 26; q++) {
          const a = hache(q, 1, 1320) * Math.PI * 2, b = (hache(q, 2, 1320) - 0.3) * 1.6;
          const d = [Math.cos(a) * Math.cos(b), Math.sin(b), Math.sin(a) * Math.cos(b)];
          const base = [d[0] * 7.5, 13 + 7 * Math.sin(b) + d[1] * 6, d[2] * 6];
          pointe(sc, base, d, 8 + 6 * hache(q, 3, 1320), 3, (t) => mix(cle ? SABLE : DUNE, cle ? SABLE_F : DUNE_F, t), false, false);
        }
      }));
      // la colonne qui le soulève (2 blocs de haut ; le jeu la tasse tant qu'il monte)
      groupes.push(enregistrer((sc) => volume(sc, ID, (p) => boudin([0, -2, 0], [0, 34, 0], 6.5, 4.5)(p) + 0.6 * bosse(p, 1.5, 1310), [-9, -3, -9, 9, 38, 9], 0.7, cle ? sableI(32) : dune(32))));
      ecrire(nom, groupes);
    }
  });
}

// ------------------------------------------------------------------ la boule (niveau 1) et le rocher (niveau 2)
if (veut("rocher")) {
  avec(1.4, () => {
    ecrire("boule", [enregistrer((sc) => volume(sc, ID, (p) => boule([0, 0, 0], 6)(p) + 1.1 * bosse(p, 2.2, 1420), [-9, -9, -9, 9, 9, 9], 0.6, dune(81)))]);
    ecrire("rocher", [enregistrer((sc) => rocher(sc, [0, 0, 0], 13, 1))]);
  });
}

// ------------------------------------------------------------------ la statue du mausolée (dans le monde, ses pieds en 0, elle regarde vers -z)
if (veut("statue")) {
  avec(2, () => {
    // 9 blocs (taille 1,6) : sortie les mains ouvertes, à moitié refermées, refermées
    const groupes = [0, 0.55, 1].map((m) => enregistrer((sc) => statue(sc, [0, 0, 0], 1, m, 1, 1.6)));
    ecrire("statue", groupes);
  });
}

// ------------------------------------------------------------------ la grande vague du niveau 3 (dans le monde, son front en z = 0, vers +z)
if (veut("vague")) {
  ecrire("vague", [enregistrer((sc) => vagueSable(sc, 0, 64, 112, 1, 2.2, 3))]);
}

/** La grosse queue du niveau 3 (comme grosseQueue de la planche), en plus gros carreaux. */
function queue(sc) {
  const P = [[0, 12, -4], [-4, 2, -26], [-6, 52, -40], [-2, 54, -8]];
  const B = (t) => [0, 1, 2].map((i) => (1 - t) ** 3 * P[0][i] + 3 * (1 - t) ** 2 * t * P[1][i] + 3 * (1 - t) * t * t * P[2][i] + t ** 3 * P[3][i]);
  const n = 16;
  for (let q = 0; q < n; q++) {
    const w = 12.5 - 7 * (q / (n - 1));
    membre(sc, B(q / n), B((q + 1) / n), w, w, [1, 0, 0], motif(200 + q), 0.9, 1.6);
  }
  const bout = B(1);
  pointe(sc, bout, norme(sous(bout, B(0.95))), 6, 5.6, (t) => mix(SABLE, ENCRE, t * 0.9), false, false);
}

// ------------------------------------------------------------------ la bête du niveau 3 (par partie du joueur)
if (veut("bete")) {
  avec(2, () => {
    // le buste gonflé de sable, les piques du dos et la grosse queue (repère du buste)
    const torse = enregistrer((sc) => {
      volume(sc, ID, fondu(2, ellip([0, 18.5, 0.6], [6.6, 7.6, 4.8]), ellip([-5.5, 22, 0], [4.2, 3.6, 4.2]), ellip([5.5, 22, 0], [4.2, 3.6, 4.2])),
        [-12, 9, -7, 12, 28, 7], 0.45, sableI(300));
      queue(sc);
      for (const s of [-1, 1]) for (const [p, L] of [[[s * 2.6, 23, -2], 4.2], [[s * 1.4, 19.5, -2], 3.4], [[s * 3, 16.5, -2], 3]]) {
        pointe(sc, p, [s * 0.5, 0.5, -1], L, 1.9, (t) => mix(SABLE, SABLE_F, t * 0.8), false, false);
      }
    }).map(CORPS);
    // la tête de bête : la gueule fermée, puis grande ouverte (le rire)
    const tete = enregistrer((sc) => teteBete(sc, ID, 0)).map(CORPS);
    const rire = enregistrer((sc) => teteBete(sc, ID, 1.4)).map(CORPS);
    // le haut des jambes (le bas reste à lui)
    const jambe = (s) => enregistrer((sc) => volume(sc, ID, (q) => Math.max(ellip([s * 2, 9.5, 0], [3.4, 4.2, 3.6])(q) + 0.5 * bosse(q, 1, 1375 + s),
      6.4 - q[1] + 1.4 * bruit(q[0] * 2 + q[2] * 2, 3, 1376)), [-7, 0, -6, 7, 15, 6], 0.45, sableI(310 + s)));
    ecrire("bete", [torse, tete, rire, jambe(-1).map(JAMBE_D), jambe(1).map(JAMBE_G)]);
    // les deux grands bras en arc (repère du buste), en plusieurs poses : au repos (les mains au sol devant), griffe à
    // droite, griffe à gauche, les mains abattues au sol (la vague), levées (les cercueils), en l'air (le rire)
    const dec = [0, -1.5, 0];
    const bras = (mains, devant, devantG) => enregistrer((sc) => {
      for (const s of [-1, 1]) {
        const H = ajoute(mains?.[s < 0 ? 0 : 1] ?? [s * 19, 3, 17], dec);
        bras3(sc, [s * 6.4, 21, 0], H, s, s < 0 ? devant : devantG ?? null);
      }
    }).map(CORPS);
    ecrire("bete_bras", [
      bras(null),
      bras([[-4, 16, 30], [19, 3, 17]], [0.9, -0.1, 0.4]),
      bras([[-19, 3, 17], [4, 16, 30]], null, [-0.9, -0.1, 0.4]),
      bras([[-16, 3, 22], [16, 3, 22]], [0, -0.6, 1], [0, -0.6, 1]),
      bras([[-20, 18, 24], [20, 18, 24]], [0, 0.4, 1], [0, 0.4, 1]),
      bras([[-22, 40, 8], [22, 40, 8]], [-0.3, 0.8, 0.5], [0.3, 0.8, 0.5]),
    ]);
  });
}

// ------------------------------------------------------------------ des pics de sable qui jaillissent du sol (dans le monde, leur pied en 0)
if (veut("pics")) {
  avec(1.6, () => {
    ecrire("pics", [enregistrer((sc) => {
      // un petit monticule, un grand pic au milieu (2,5 blocs), six autres autour, penchés vers le dehors
      volume(sc, ID, (p) => ellip([0, 0, 0], [13, 5, 13])(p) + 1.2 * bosse(p, 2, 1900), [-16, -1, -16, 16, 7, 16], 0.8, sableI(190));
      pointe(sc, [0, 2, 0], [0.05, 1, 0.02], 40, 7.5, (t) => mix(SABLE, SABLE_F, t * 0.8), false, false);
      for (let q = 0; q < 6; q++) {
        const a = (q / 6) * Math.PI * 2 + 0.4, r = 5 + 2 * hache(q, 1, 1910);
        pointe(sc, [Math.cos(a) * r, 1, Math.sin(a) * r], [Math.cos(a) * 0.45, 1, Math.sin(a) * 0.45], 18 + 12 * hache(q, 2, 1910), 4.5,
          (t) => mix(q % 2 ? SABLE : SABLE_F, ENCRE, t * 0.5), false, false);
      }
    })]);
  });
}

// ------------------------------------------------------------------ l'ulti : une lame de sable (un croissant à plat, sa pointe vers -x) et la main géante
if (veut("tempete")) {
  avec(2, () => {
    // le croissant : le disque de 42 pixels moins un disque décalé ; 3 pixels d'épaisseur ; il tourne à plat
    const lame = (p) => Math.max(Math.hypot(p[0], p[2]) - 42, -(Math.hypot(p[0] - 15, p[2]) - 32), Math.abs(p[1]) - 2.4 + 0.8 * (Math.hypot(p[0], p[2]) / 42));
    ecrire("lame", [enregistrer((sc) => volume(sc, ID, lame, [-44, -4, -44, 44, 4, 44], 1, (p, n) => mix(sableI(260)(p, n), [250, 236, 200], 0.35)))]);
  });
  avec(1.8, () => {
    // la main : le bras sort du sol (y < 0), la paume tournée vers +z, quatre doigts et le pouce ; ouverte puis refermée
    const main = (ferme) => {
      const parts = [boudin([0, -70, -6], [0, 6, 0], 19, 16), ellip([0, 16, 2], [27, 18, 11])];
      const bouts = [];
      for (let k = 0; k < 4; k++) {
        const x = -19 + k * 12.6, b = [x, 30, 2];
        const P = ferme
          ? [b, [x * 1.05, 58, 4], [x * 0.9, 74, 30], [x * 0.8, 52, 44]]
          : [b, [x * 1.2, 50, 3], [x * 1.45, 72, 8], [x * 1.5, 88, 14]];
        const c = bezier(P, 6.2, 4.6, 10);
        parts.push(c.f);
        bouts.push([P[3], norme(sous(P[3], c.B(0.92)))]);
      }
      const pb = [-26, 12, 6], P = ferme ? [pb, [-38, 26, 18], [-30, 40, 38], [-14, 44, 42]] : [pb, [-44, 22, 12], [-56, 40, 18], [-60, 56, 22]];
      const c = bezier(P, 6.8, 5, 8);
      parts.push(c.f);
      bouts.push([P[3], norme(sous(P[3], c.B(0.9)))]);
      return enregistrer((sc) => {
        volume(sc, ID, (p) => fondu(5, ...parts)(p) + 1.2 * bosse(p, 3, 2100), [-70, -72, -24, 70, 96, 60], 1, sableI(270, 0.7));
        for (const [b, d] of bouts) pointe(sc, b, d, 12, 5, (t) => mix([60, 54, 66], [206, 196, 172], t * 0.6), false, false);
      });
    };
    ecrire("main_geante", [main(false), main(true)]);
  });
}
