// Le cartel, bloc par bloc (ce que le code construira dans la dimension) : on arrive par le portail au sud, la grande
// allée bordée de stands d'armes monte vers la place et la hacienda du patron ; à l'est le stand de tir, à l'ouest
// l'héliport et le dépôt de conteneurs ; autour, le mur et ses miradors ; derrière, la favela sur la colline.
// x vers l'est, z vers le sud, le sol à y = -1.
import { hache } from "../../spies/generateur/moteur.mjs";
import { Monde } from "./commun.mjs";

const FAVELA = ["rose", "jaune", "bleu", "vert", "orange", "turquoise", "beige", "lilas", "brique", "briqueNue", "rouge", "blanc"];
export const MUR = { x0: -38, x1: 46, z0: -34, z1: 58 };

/** La hauteur du terrain : plat dans le cartel, la colline de la favela qui monte au nord et à l'ouest. */
export function hauteur(x, z) {
  const n = -z - 37, o = -x - 41;
  let h = Math.max(0, n * 0.62, o * 0.62);
  if (n > 0 && o > 0) h += Math.min(n, o) * 0.35;
  if (h > 0) h += 1.6 * Math.sin(x * 0.21) + 1.3 * Math.cos(z * 0.17);
  return Math.max(0, Math.round(Math.min(26, h)));
}

export function construire() {
  const m = new Monde([-64, 63], [-4, 46], [-64, 63]);
  const pose = (x, y, z, nom) => m.set(x, y, z, nom);
  const dansMur = (x, z) => x > MUR.x0 && x < MUR.x1 && z > MUR.z0 && z < MUR.z1;

  // ---------------------------------------------------------------- le sol
  for (let x = -64; x <= 63; x++) for (let z = -64; z <= 63; z++) {
    const h = hauteur(x, z);
    for (let y = -4; y < h; y++) {
      let nom = y < -2 ? "pierre" : "terre";
      if (y === h - 1) {
        if (dansMur(x, z)) nom = hache(x, z, 1) < 0.25 ? "terre" : "terreSeche";
        else if (h > 0) nom = hache(x, z, 2) < 0.15 ? "terre" : "herbe";
        else nom = hache(x >> 1, z >> 1, 3) < 0.35 ? "sableRouge" : "terreSeche";
      }
      pose(x, y, z, nom);
    }
  }
  // la route qui arrive au portail
  for (let z = 58; z <= 63; z++) for (let x = -4; x <= 4; x++) pose(x, -1, z, Math.abs(x) === 4 ? "terre" : "chemin");

  // ---------------------------------------------------------------- le mur, le portail, les miradors
  for (let x = MUR.x0; x <= MUR.x1; x++) for (let z = MUR.z0; z <= MUR.z1; z++) {
    if (x !== MUR.x0 && x !== MUR.x1 && z !== MUR.z0 && z !== MUR.z1) continue;
    if (z === MUR.z1 && Math.abs(x) <= 3) continue;
    for (let y = 0; y <= 3; y++) pose(x, y, z, y === 0 ? "pierreTaillee" : (x + z) % 23 === 0 && y === 2 ? "graffiti" : "creme");
  }
  m.lignes.push({ a: [MUR.x0 + 0.5, 4.4, MUR.z1 + 0.5], b: [-3.5, 4.4, MUR.z1 + 0.5], couleur: [90, 90, 96], barbele: true });
  m.lignes.push({ a: [3.5, 4.4, MUR.z1 + 0.5], b: [MUR.x1 + 0.5, 4.4, MUR.z1 + 0.5], couleur: [90, 90, 96], barbele: true });
  m.lignes.push({ a: [MUR.x1 + 0.5, 4.4, MUR.z0 + 0.5], b: [MUR.x1 + 0.5, 4.4, MUR.z1 + 0.5], couleur: [90, 90, 96], barbele: true });
  m.lignes.push({ a: [MUR.x0 + 0.5, 4.4, MUR.z0 + 0.5], b: [MUR.x1 + 0.5, 4.4, MUR.z0 + 0.5], couleur: [90, 90, 96], barbele: true });
  m.lignes.push({ a: [MUR.x0 + 0.5, 4.4, MUR.z0 + 0.5], b: [MUR.x0 + 0.5, 4.4, MUR.z1 + 0.5], couleur: [90, 90, 96], barbele: true });
  // le portail : deux piliers blancs coiffés d'or et l'arche avec l'enseigne
  for (const s of [-1, 1]) {
    m.remplir(s * 4, 0, 57, s * 5, 7, 59, "blanc");
    m.remplir(s * 4, 8, 57, s * 5, 8, 59, "or");
  }
  m.remplir(-3, 6, 58, 3, 7, 58, "boisFonce");
  m.enseigne = { x0: -3, x1: 4, y0: 6, y1: 8, z: 59, texte: "EL CARTEL" };
  for (const [cx, cz] of [[MUR.x0, MUR.z0], [MUR.x1, MUR.z0], [MUR.x0, MUR.z1], [MUR.x1, MUR.z1]]) mirador(m, cx, cz);

  // ---------------------------------------------------------------- la grande allée
  for (let z = -3; z <= 57; z++) for (let x = -5; x <= 5; x++) pose(x, -1, z, Math.abs(x) === 5 ? "pierreTaillee" : Math.abs(x) <= 1 ? "pavesClairs" : "pave");
  const couleurs = [["laineRouge", "laineBlanche"], ["laineVerte", "laineBlanche"], ["laineJaune", "laineVerte"], ["laineRouge", "laineVerte"], ["laineBleue", "laineJaune"], ["laineNoire", "laineRouge"]];
  const armesStands = [["ak47", "uzi", "ak47"], ["rpg7", "grenade", "m79"], ["thompson", "mac10", "canon_scie"], ["barrett", "m60", "ak47"], ["desert_eagle_or", "ak47_or", "machette"], ["minigun", "molotov", "uzi"]];
  let k = 0;
  for (let z = 50; z >= 6; z -= 9, k++) {
    stand(m, -1, z, couleurs[k % couleurs.length], armesStands[k % armesStands.length]);
    stand(m, 1, z - 4, couleurs[(k + 3) % couleurs.length], armesStands[(k + 2) % armesStands.length]);
  }
  // les palmiers et les lampadaires entre les stands
  for (let z = 54; z >= 2; z -= 9) { palmier(m, -6, 0, z, 7 + (z % 3)); palmier(m, 6, 0, z - 4, 6 + (z % 2)); }
  // les guirlandes de fanions (papel picado) au-dessus de l'allée
  for (let z = 52; z >= 4; z -= 8) m.lignes.push({ a: [-5.5, 6.2, z], b: [5.5, 6.2, z + 1.5], fanions: true });

  // ---------------------------------------------------------------- la place et sa fontaine en or
  for (let z = -13; z <= -4; z++) for (let x = -15; x <= 15; x++) pose(x, -1, z, (x + z) % 2 === 0 ? "pavesClairs" : "pave");
  m.remplir(-3, 0, -10, 3, 0, -6, "marbre");
  m.remplir(-2, 0, -9, 2, 0, -7, "eau");
  m.remplir(-3, 1, -10, 3, 1, -10, "marbre"); m.remplir(-3, 1, -6, 3, 1, -6, "marbre");
  m.remplir(-3, 1, -10, -3, 1, -6, "marbre"); m.remplir(3, 1, -10, 3, 1, -6, "marbre");
  m.remplir(0, 1, -8, 0, 3, -8, "or");
  m.statue = [0.5, 4, -7.5];
  pose(0, 4, -8, "or");
  // le 4×4 noir du patron et le pick-up avec la mitrailleuse
  voiture(m, -11, -9, "carrosserie", false);
  voiture(m, 9, -10, "blanc", true);
  palmier(m, -14, 0, -4, 8); palmier(m, 14, 0, -4, 7);

  // ---------------------------------------------------------------- la hacienda
  hacienda(m);
  piscine(m);

  // ---------------------------------------------------------------- le stand de tir
  standDeTir(m);

  // ---------------------------------------------------------------- l'héliport et le dépôt
  for (let x = -34; x <= -18; x++) for (let z = 24; z <= 42; z++) pose(x, -1, z, "beton");
  m.remplir(-33, 0, 25, -19, 0, 41, "beton");
  m.peintures.push((x, y, z, face, u, v) => {
    if (face !== "haut" || y !== 0 || x < -33 || x > -19 || z < 25 || z > 41) return null;
    const px = x + u + 26, pz = z + v - 33, d = Math.hypot(px, pz);
    if (d > 6.2 && d < 6.9) return [240, 200, 40];
    const H = (Math.abs(px) < 3 && Math.abs(pz) < 0.6) || (Math.abs(Math.abs(px) - 2.5) < 0.6 && Math.abs(pz) < 3.6);
    return H ? [244, 242, 236] : null;
  });
  helico(m, -26, 1, 33);
  depot(m);

  // ---------------------------------------------------------------- la favela et le désert
  favela(m);
  for (let n = 0; n < 26; n++) {
    const x = 48 + Math.floor(hache(n, 1, 90) * 15), z = -60 + Math.floor(hache(n, 2, 90) * 122);
    if (m.plein(x, 0, z)) continue;
    const h = 2 + Math.floor(hache(n, 3, 90) * 3);
    m.remplir(x, 0, z, x, h - 1, z, "cactus");
    if (h > 2 && hache(n, 4, 90) < 0.6) { pose(x + 1, 1, z, "cactus"); pose(x + 1, 2, z, "cactus"); }
  }
  for (let n = 0; n < 7; n++) palmier(m, 50 + Math.floor(hache(n, 5, 91) * 12), 0, -50 + n * 16, 6 + (n % 3));

  // ---------------------------------------------------------------- les gars
  gens(m);
  return m;
}

// ---------------------------------------------------------------- les morceaux
function mirador(m, cx, cz) {
  for (const [dx, dz] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) m.remplir(cx + dx, 0, cz + dz, cx + dx, 6, cz + dz, "boisFonce");
  m.remplir(cx - 2, 7, cz - 2, cx + 2, 7, cz + 2, "bois");
  for (let x = cx - 2; x <= cx + 2; x++) for (let z = cz - 2; z <= cz + 2; z++)
    if (Math.abs(x - cx) === 2 || Math.abs(z - cz) === 2) m.set(x, 8, z, (x + z) % 2 ? "sacSable" : "bois");
  m.boite([cx - 2.6, 10.5, cz - 2.6], [cx + 3.6, 10.8, cz + 3.6], "tole");
  for (const [dx, dz] of [[-2, -2], [2, -2], [-2, 2], [2, 2]]) m.boite([cx + dx + 0.4, 9, cz + dz + 0.4], [cx + dx + 0.6, 10.5, cz + dz + 0.6], "boisFonce", { ombre: false });
  m.miradors = (m.miradors ?? []).concat([[cx, 8, cz]]);
}

/**
 * Un stand d'armes au bord de l'allée. cote -1 : à l'ouest (il regarde vers l'est), 1 : à l'est. Une table chargée
 * d'armes, un présentoir mural couvert de fusils, un auvent rayé, des caisses et des sacs de sable.
 */
function stand(m, cote, z, [c1, c2], armes) {
  const xt = cote < 0 ? -7 : 7, xm = cote < 0 ? -11 : 11;
  // le mur du fond et ses montants
  m.remplir(xm, 0, z - 2, xm, 3, z + 2, "bois");
  for (const dz of [-2, 2]) m.remplir(xm - cote * 4, 0, z + dz, xm - cote * 4, 3, z + dz, "boisFonce");
  // l'auvent rayé
  for (let dz = -2; dz <= 2; dz++) {
    const nom = (dz + 10) % 2 ? c1 : c2;
    const xa = Math.min(xt - cote, xm), xb = Math.max(xt - cote, xm);
    m.boite([xa, 4, z + dz], [xb + 1, 4.35, z + dz + 1], nom);
  }
  m.boite([cote < 0 ? xt - cote : xt - cote, 3.65, z - 2], [cote < 0 ? xt - cote + 1 : xt - cote + 1, 4, z + 3], c1);
  // la table : plateau sombre sur deux tréteaux, et les armes posées dessus
  const xa = Math.min(xt, xt + cote), xb = Math.max(xt, xt + cote) + 1;
  m.boite([xa, 0.8, z - 1.5], [xb, 1, z + 2.5], "boisFonce");
  m.boite([xa + 0.2, 0, z - 1.3], [xb - 0.2, 0.8, z - 1.1], "boisFonce", { ombre: false });
  m.boite([xa + 0.2, 0, z + 2.1], [xb - 0.2, 0.8, z + 2.3], "boisFonce", { ombre: false });
  armes.forEach((id, i) => m.objets.push({ type: "arme", id, centre: [(xa + xb) / 2, 1.04, z - 0.9 + i * 1.3], avant: [cote, 0, 0.18], haut: [0, 0, 1], taille: id === "grenade" || id === "molotov" ? 0.7 : 1.15, pose: "table" }));
  // le présentoir : des armes accrochées au mur du fond, côté allée
  const xf = xm - cote * 0.02 + (cote < 0 ? 1 : 0);
  ["ak47", "rpg7", armes[0], "ak47_or", armes[1], "barrett"].forEach((id, i) => {
    const ligne = Math.floor(i / 2), col = i % 2;
    m.objets.push({ type: "arme", id, centre: [xf, 1.0 + ligne * 1.05, z - 0.9 + col * 2.0], avant: [0, 0, 1], haut: [0, 1, 0], taille: 1.6, pose: "mur", face: -cote });
  });
  // des caisses militaires, des bidons, des sacs de sable
  m.set(xm - cote, 0, z + 2, "caisse"); m.set(xm - cote, 1, z + 2, "caisse"); m.set(xm - cote * 2, 0, z + 2, "caisse");
  m.set(xm - cote, 0, z - 2, "bidon");
  m.set(xt + cote * 0, 0, z + 3, "sacSable");
}

function palmier(m, x, y, z, h) {
  const dx = hache(x, z, 7) < 0.5 ? 1 : -1;
  for (let k = 0; k < h; k++) m.set(x + (k > h * 0.6 ? dx : 0), y + k, z, "tronc");
  const tx = x + dx, ty = y + h, tz = z;
  m.set(tx, ty, tz, "palme");
  for (const [ax, az] of [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [-1, -1], [1, -1], [-1, 1]]) {
    const L = ax && az ? 2 : 3;
    for (let s = 1; s <= L; s++) m.set(tx + ax * s, ty - (s === L ? 1 : 0), tz + az * s, "palme");
  }
}

function voiture(m, x, z, peinture, technique) {
  // la caisse, la cabine et ses vitres, les roues
  const long = 5, larg = 2.6;
  m.boite([x, 0.5, z], [x + larg, 1.6, z + long], peinture);
  m.boite([x + 0.1, 1.6, z + (technique ? 2.6 : 0.6)], [x + larg - 0.1, 2.5, z + 4.3], peinture);
  m.boite([x + 0.15, 1.7, z + 4.3], [x + larg - 0.15, 2.4, z + 4.36], "vitre");
  m.boite([x + larg - 0.04, 1.75, z + (technique ? 2.8 : 0.9)], [x + larg + 0.02, 2.35, z + 4.1], "vitre");
  for (const [rx, rz] of [[x - 0.1, z + 0.7], [x - 0.1, z + 3.6], [x + larg - 0.3, z + 0.7], [x + larg - 0.3, z + 3.6]]) m.boite([rx, 0, rz], [rx + 0.4, 0.9, rz + 0.9], "noir");
  m.boite([x + 0.3, 1.0, z + long], [x + larg - 0.3, 1.25, z + long + 0.05], "chrome");
  if (technique) {
    // la mitrailleuse montée sur le plateau du pick-up
    m.boite([x + 1.15, 1.6, z + 1.2], [x + 1.45, 2.4, z + 1.5], "noir");
    m.objets.push({ type: "arme", id: "m60", centre: [x + 1.3, 2.75, z + 1.8], avant: [0, 0.1, 1], haut: [0, 1, 0], taille: 1.8, pose: "pickup" });
  }
}

function hacienda(m) {
  const X0 = -17, X1 = 17, Z0 = -32, ZF = -17, ZA = -14;
  // la terrasse de pierre et les marches
  m.remplir(X0 - 1, 0, Z0 - 1, X1 + 1, 0, ZA + 1, "pierreTaillee");
  m.remplir(-3, 0, ZA + 2, 3, 0, ZA + 2, "pierreTaillee");
  // le rez-de-chaussée : murs blancs, soubassement crème
  for (let x = X0; x <= X1; x++) for (let z = Z0; z <= ZF; z++) for (let y = 1; y <= 5; y++) {
    const bord = x === X0 || x === X1 || z === Z0 || z === ZF;
    if (!bord) continue;
    m.set(x, y, z, y === 1 ? "creme" : "blanc");
  }
  // la grande porte et les fenêtres en arc à volets verts
  m.remplir(-1, 1, ZF, 1, 4, ZF, "porte");
  m.remplir(-2, 1, ZF, -2, 5, ZF, "or"); m.remplir(2, 1, ZF, 2, 5, ZF, "or"); m.remplir(-1, 5, ZF, 1, 5, ZF, "or");
  for (const x of [-13, -8, 8, 13]) {
    m.remplir(x, 2, ZF, x + 1, 4, ZF, "vitreArc");
    m.set(x - 1, 2, ZF, "volet"); m.set(x - 1, 3, ZF, "volet"); m.set(x + 2, 2, ZF, "volet"); m.set(x + 2, 3, ZF, "volet");
  }
  for (const z of [-28, -23]) for (const x of [X0, X1]) m.remplir(x, 2, z, x, 4, z + 1, "vitreArc");
  // les arcades devant : colonnes et arcs, le sol en tomettes
  for (let x = X0; x <= X1; x++) for (let z = ZF + 1; z <= ZA; z++) m.set(x, 0, z, "briqueNue");
  for (const x of [-17, -12, -7, -3, 3, 7, 12, 17]) m.remplir(x, 1, ZA, x, 5, ZA, "blanc");
  for (let x = X0; x <= X1; x++) m.set(x, 5, ZA, "blanc");
  // le plancher de l'étage et les poutres (vigas) qui dépassent
  m.remplir(X0, 6, Z0, X1, 6, ZA, "boisFonce");
  for (let x = X0 + 1; x < X1; x += 3) m.boite([x + 0.3, 6.25, ZA + 1], [x + 0.7, 6.65, ZA + 1.8], "boisFonce", { ombre: false });
  // l'étage, en retrait au-dessus des arcades, avec son balcon à balustrade dorée
  for (let x = X0; x <= X1; x++) for (let z = Z0; z <= ZA - 1; z++) for (let y = 7; y <= 10; y++) {
    if (x !== X0 && x !== X1 && z !== Z0 && z !== ZA - 1) continue;
    m.set(x, y, z, "blanc");
  }
  for (let x = X0 + 2; x <= X1 - 2; x += 4) { m.remplir(x, 8, ZA - 1, x + 1, 9, ZA - 1, "vitreArc"); m.set(x - 1, 8, ZA - 1, "volet"); m.set(x + 2, 8, ZA - 1, "volet"); }
  m.boite([X0, 7.6, ZA + 0.75], [X1 + 1, 7.8, ZA + 0.95], "or");
  for (let x = X0; x <= X1 + 0.5; x += 0.75) m.boite([x, 7, ZA + 0.8], [x + 0.12, 7.6, ZA + 0.9], "or", { ombre: false });
  // le toit de tuiles en croupe, par marches
  for (let k = 0; ; k++) {
    const x0 = X0 - 1 + k, x1 = X1 + 1 - k, z0 = Z0 - 1 + k, z1 = ZA - k;
    if (x0 > x1 || z0 > z1) break;
    for (let x = x0; x <= x1; x++) for (let z = z0; z <= z1; z++) if (x === x0 || x === x1 || z === z0 || z === z1) m.set(x, 11 + k, z, "tuile");
  }
  // la tour du coin, plus haute
  m.remplir(12, 11, -31, 16, 16, -27, "blanc");
  m.remplir(13, 13, -27, 15, 15, -27, "vitreArc");
  m.remplir(11, 17, -32, 17, 17, -26, "tuile"); m.remplir(12, 18, -31, 16, 18, -27, "tuile"); m.remplir(13, 19, -30, 15, 19, -28, "tuile"); m.set(14, 20, -29, "or");
  // le sol de la boutique : damier de marbre, tapis rouge jusqu'au comptoir
  for (let x = X0 + 1; x < X1; x++) for (let z = Z0 + 1; z < ZF; z++) m.set(x, 0, z, (x + z) % 2 === 0 ? "marbre" : "marbreNoir");
  for (let z = -26; z < ZF; z++) for (let x = -1; x <= 1; x++) m.set(x, 0, z, "tapis");
  // le comptoir (bois sombre, dessus en or) et le patron derrière
  m.remplir(-6, 1, -26, 6, 1, -26, "boisFonce");
  m.boite([-6, 2, -26.1], [7, 2.2, -24.9], "or");
  // le mur des armes, derrière le comptoir
  const murArmes = ["ak47", "ak47_or", "rpg7", "barrett", "m60", "minigun", "thompson", "uzi", "mac10", "m79", "canon_scie", "desert_eagle_or"];
  murArmes.forEach((id, i) => {
    const col = i % 6, ligne = Math.floor(i / 6);
    m.objets.push({ type: "arme", id, centre: [-12.5 + col * 5, 4.2 - ligne * 1.6, Z0 + 1.03], avant: [1, 0, 0], haut: [0, 1, 0], taille: id === "desert_eagle_or" || id === "mac10" || id === "uzi" ? 1.4 : 2.2, pose: "mur", face: 1 });
  });
  // l'or, les liasses, le coffre
  m.remplir(-15, 1, -30, -14, 2, -29, "liasses"); m.remplir(-13, 1, -30, -13, 1, -29, "liasses");
  m.remplir(14, 1, -30, 15, 1, -29, "or"); m.set(15, 2, -30, "or");
  m.remplir(-15, 1, -20, -15, 3, -19, "fer"); m.set(-15, 2, -19, "noir");
  for (const x of [-10, 10]) { m.remplir(x, 1, -21, x, 1, -21, "boisFonce"); m.boite([x - 0.4, 2, -21.4], [x + 1.4, 2.3, -20.6], "liasses"); }
  // le lambris et la poutre du mur des armes, les vitrines, les plantes
  m.remplir(X0 + 1, 1, Z0, X1 - 1, 1, Z0, "boisFonce"); m.remplir(X0 + 1, 5, Z0, X1 - 1, 5, Z0, "boisFonce");
  for (const s of [-1, 1]) {
    const xv = s < 0 ? -13 : 10;
    m.boite([xv, 0.9, -24], [xv + 4, 1.1, -22], "or");
    m.boite([xv + 0.2, 0, -23.8], [xv + 3.8, 0.9, -22.2], "boisFonce");
    ["rpg7", "ak47_or", "barrett"].forEach((id, i) => m.objets.push({ type: "arme", id: s < 0 ? id : ["minigun", "thompson", "m60"][i], centre: [xv + 2, 1.14, -23.6 + i * 0.6], avant: [1, 0, 0], haut: [0, 0, -1], taille: 1.8, pose: "table" }));
  }
  for (const [px, pz] of [[-15.5, -30.5], [15.5, -30.5], [-15.5, -18.5], [15.5, -18.5]]) {
    m.boite([px - 0.4, 1, pz - 0.4], [px + 0.4, 1.7, pz + 0.4], "brique");
    m.boite([px - 0.9, 1.7, pz - 0.15], [px + 0.9, 2.3, pz + 0.15], "palme", { ombre: false });
    m.boite([px - 0.15, 1.7, pz - 0.9], [px + 0.15, 2.5, pz + 0.9], "palme", { ombre: false });
  }
  m.patron = [0.5, 1, -27.5];
}

function piscine(m) {
  for (let x = 20; x <= 34; x++) for (let z = -30; z <= -18; z++) m.set(x, -1, z, "marbre");
  for (let x = 22; x <= 32; x++) for (let z = -28; z <= -20; z++) m.set(x, -1, z, "eau");
  for (const z of [-27, -24, -21]) { m.boite([34.2, 0, z], [35.4, 0.45, z + 2.2], "laineBlanche"); m.boite([34.2, 0.45, z + 1.6], [35.4, 1, z + 2.2], "laineBlanche"); }
  palmier(m, 21, 0, -31, 8); palmier(m, 35, 0, -18, 7); palmier(m, 33, 0, -31, 6);
}

function standDeTir(m) {
  const X0 = 18, X1 = 42;
  for (let x = X0; x <= X1; x++) for (let z = 10; z <= 46; z++) m.set(x, -1, z, z >= 40 ? "beton" : hache(x, z, 33) < 0.3 ? "terreSeche" : "sable");
  // la butte de sable et de sacs derrière les cibles
  for (let x = X0 - 1; x <= X1 + 1; x++) { m.remplir(x, 0, 8, x, 4, 8, "terre"); m.remplir(x, 0, 9, x, 3, 9, "sacSable"); m.set(x, 5, 8, "herbe"); }
  // les murets de sacs sur les côtés
  for (let z = 10; z <= 46; z++) { m.remplir(X0 - 1, 0, z, X0 - 1, 1, z, "sacSable"); m.remplir(X1 + 1, 0, z, X1 + 1, 1, z, "sacSable"); }
  // six couloirs : séparations, tablettes de tir, cibles au fond et silhouettes à mi-distance
  m.couloirs = [];
  for (let n = 0; n < 6; n++) {
    const x = X0 + 1 + n * 4;
    if (n < 5) m.remplir(x + 3, 0, 39, x + 3, 1, 42, "sacSable");
    m.boite([x, 0.9, 39.4], [x + 3, 1.1, 40.2], "bois");
    m.boite([x + 0.2, 0, 39.6], [x + 0.4, 0.9, 39.8], "bois", { ombre: false });
    m.boite([x + 2.6, 0, 39.6], [x + 2.8, 0.9, 39.8], "bois", { ombre: false });
    m.set(x + 1, 0, 12, "foin"); m.set(x + 1, 1, 12, "cible");
    if (n % 2 === 0) m.set(x + 1, 0, 11, "foin");
    // la silhouette en carton, sur deux piquets
    const zs = 22 + (n % 3) * 4;
    m.boite([x + 0.7, 0, zs], [x + 0.8, 0.7, zs + 0.1], "bois", { ombre: false });
    m.boite([x + 2.2, 0, zs], [x + 2.3, 0.7, zs + 0.1], "bois", { ombre: false });
    m.boite([x + 0.6, 0.7, zs], [x + 2.4, 2.6, zs + 0.08], "laineBlanche", { couleur: (face, i, j) => silhouette(i, j) });
    m.couloirs.push({ x: x + 1.5, cible: n % 2 ? [x + 1.5, 1.5, 12.95] : [x + 1.5, 1.6, zs + 0.1] });
  }
  // le toit de tôle derrière les tireurs, les caisses de munitions
  for (const x of [X0, X0 + 8, X0 + 16, X1]) m.boite([x - 0.1, 0, 44.9], [x + 0.1, 4, 45.1], "boisFonce", { ombre: false });
  m.boite([X0 - 0.5, 4, 43], [X1 + 1.5, 4.25, 47], "tole");
  for (let x = X0 + 2; x < X1; x += 5) { m.set(x, 0, 45, "caisse"); if (x % 2) m.set(x, 1, 45, "caisse"); m.set(x + 1, 0, 45, "caisse"); }
  m.set(X1, 0, 44, "bidon"); m.set(X1 - 1, 0, 44, "bidon");
}

/** La silhouette noire sur carton blanc, avec ses cercles (i de gauche à droite, j de haut en bas, en cases de 1/4). */
function silhouette(i, j) {
  const x = (i + 0.5) / 7 - 0.5, y = (j + 0.5) / 8;
  const tete = Math.hypot(x, (y - 0.14) * 1.1) < 0.13;
  const corps = y > 0.28 && Math.abs(x) < 0.34 - (y < 0.38 ? (0.38 - y) * 1.6 : 0);
  if (tete || corps) { const r = Math.hypot(x, y - 0.55); return r < 0.07 ? [220, 40, 40] : Math.abs(r - 0.17) < 0.03 ? [220, 210, 200] : [28, 28, 32]; }
  return [232, 224, 206];
}

function helico(m, x, y, z) {
  m.boite([x - 1.5, y + 0.6, z - 2], [x + 1.5, y + 3.2, z + 3.4], "carrosserie");
  m.boite([x - 1.3, y + 1, z + 3.4], [x + 1.3, y + 3, z + 4.6], "vitre");
  m.boite([x - 1.52, y + 2.2, z - 1.6], [x - 1.48, y + 2.5, z + 3.2], "or", { ombre: false });
  m.boite([x + 1.48, y + 2.2, z - 1.6], [x + 1.52, y + 2.5, z + 3.2], "or", { ombre: false });
  m.boite([x + 1.49, y + 1.2, z - 0.6], [x + 1.53, y + 2.1, z + 1.8], "vitre", { ombre: false });
  m.boite([x - 0.5, y + 2, z - 9], [x + 0.5, y + 2.8, z - 2], "carrosserie");
  m.boite([x - 0.3, y + 2, z - 9.6], [x + 0.3, y + 4.6, z - 8.4], "carrosserie");
  m.boite([x + 0.3, y + 3.2, z - 9.4], [x + 0.4, y + 3.4, z - 7.4], "noir", { ombre: false });
  for (const s of [-1, 1]) {
    m.boite([x + s * 1.6 - 0.2, y, z - 2.4], [x + s * 1.6 + 0.2, y + 0.25, z + 4], "chrome", { ombre: false });
    m.boite([x + s * 1.4 - 0.1, y + 0.25, z - 1], [x + s * 1.4 + 0.1, y + 0.7, z - 0.8], "chrome", { ombre: false });
    m.boite([x + s * 1.4 - 0.1, y + 0.25, z + 2.4], [x + s * 1.4 + 0.1, y + 0.7, z + 2.6], "chrome", { ombre: false });
  }
  m.boite([x - 0.2, y + 3.2, z + 0.3], [x + 0.2, y + 3.9, z + 0.7], "noir", { ombre: false });
  m.boite([x - 8, y + 3.9, z + 0.35], [x + 8, y + 4.05, z + 0.65], "noir");
  m.boite([x - 0.15, y + 3.9, z - 7.5], [x + 0.15, y + 4.05, z + 8.5], "noir");
}

function depot(m) {
  for (let x = -35; x <= -16; x++) for (let z = -10; z <= 18; z++) m.set(x, -1, z, hache(x, z, 40) < 0.2 ? "terre" : "bitume");
  // trois conteneurs, dont un ouvert plein de caisses
  const conteneur = (x, z, nom, ouvert, y = 0) => {
    for (let dx = 0; dx < 3; dx++) for (let dz = 0; dz < 7; dz++) for (let dy = 0; dy < 3; dy++) {
      const bord = dx === 0 || dx === 2 || dy === 2 || dz === 0 || (dz === 6 && !ouvert);
      if (bord) m.set(x + dx, y + dy, z + dz, nom);
      else if (ouvert && dy < 2 && dz < 5) m.set(x + dx, y + dy, z + dz, "caisse");
    }
  };
  conteneur(-34, -8, "conteneurRouge", false);
  conteneur(-34, -8, "conteneurBleu", false, 3);
  conteneur(-29, -8, "conteneurVert", true);
  conteneur(-34, 2, "conteneurBleu", true);
  // des piles de caisses, des bidons, un chariot de sacs de sable
  for (let n = 0; n < 16; n++) {
    const x = -27 + (n % 4) * 2, z = 2 + Math.floor(n / 4) * 3, h = 1 + Math.floor(hache(n, 1, 41) * 3);
    m.remplir(x, 0, z, x + (n % 3 === 0 ? 1 : 0), h - 1, z + 1, n % 5 === 0 ? "tonneau" : "caisse");
  }
  for (let z = -8; z <= 16; z += 3) m.set(-18, 0, z, z % 2 ? "bidon" : "tonneau");
  voiture(m, -22, -7, "conteneurVert", false);
}

/** La favela : des petites maisons cubiques de toutes les couleurs, empilées sur la colline, avec leurs cuves bleues. */
function favela(m) {
  let n = 0;
  for (let gx = -64; gx <= 63; gx += 5) for (let gz = -64; gz <= 63; gz += 5) {
    const x = gx + Math.floor(hache(gx, gz, 50) * 2), z = gz + Math.floor(hache(gz, gx, 51) * 2);
    const h0 = hauteur(x + 2, z + 2);
    if (h0 < 1 || x > 60 || z > 60) continue;
    if (x > MUR.x0 - 2 && x < MUR.x1 + 2 && z > MUR.z0 - 2 && z < MUR.z1 + 2) continue;
    if (hache(x, z, 52) < 0.12) continue;
    const L = 3 + Math.floor(hache(x, z, 53) * 2), P = 3 + Math.floor(hache(x, z, 54) * 2), etages = 1 + Math.floor(hache(x, z, 55) * 2.4);
    const couleur = FAVELA[Math.floor(hache(x, z, 56) * FAVELA.length)];
    const sol = Math.min(...[[0, 0], [L, 0], [0, P], [L, P]].map(([a, b]) => hauteur(x + a, z + b)));
    const haut = sol + etages * 3;
    for (let dx = 0; dx <= L; dx++) for (let dz = 0; dz <= P; dz++) for (let y = sol; y < haut; y++) {
      const bord = dx === 0 || dx === L || dz === 0 || dz === P;
      m.set(x + dx, y, z + dz, bord ? (y - sol) % 3 === 2 && etages > 1 && hache(x, y, 57) < 0.5 ? "briqueNue" : couleur : "beton");
    }
    const toit = ["beton", "beton", "beton", "tuile", "briqueNue", "tole"][Math.floor(hache(x, z, 61) * 6)];
    m.remplir(x, haut, z, x + L, haut, z + P, toit);
    // la cuve d'eau bleue sur le toit, parfois un étage en briques nues pas fini
    if (hache(x, z, 58) < 0.55) m.set(x + 1, haut + 1, z + 1, "cuve");
    if (hache(x, z, 59) < 0.25) { for (const [a, b] of [[0, 0], [L, 0], [0, P], [L, P]]) m.set(x + a, haut + 1, z + b, "briqueNue"); }
    m.favela = (m.favela ?? []).concat([{ x, z, L, P, sol, haut, couleur }]);
    n++;
  }
  // les fenêtres et les portes : des carrés sombres sur les murs peints
  const couleursFavela = new Set(FAVELA);
  m.peintures.push((x, y, z, face, u, v) => {
    if (face === "haut" || face === "bas") return null;
    const nom = m.get(x, y, z);
    if (!couleursFavela.has(nom) || (x > MUR.x0 && x < MUR.x1 && z > MUR.z0 && z < MUR.z1)) return null;
    const h = hache(x + z * 3, y, 60);
    if (h < 0.42 && u > 0.22 && u < 0.78 && v > 0.2 && v < 0.72) return v < 0.27 || u < 0.29 || u > 0.71 ? [220, 214, 200] : [36, 34, 40];
    if (h > 0.93 && u > 0.25 && u < 0.75 && v > 0.15) return [92, 60, 40];
    return null;
  });
  // le Christ en haut de la colline
  const cx = -55, cz = -55, h = hauteur(cx, cz);
  m.remplir(cx - 1, h, cz - 1, cx + 1, h + 2, cz + 1, "pierreTaillee");
  m.remplir(cx, h + 3, cz, cx, h + 10, cz, "blanc");
  m.remplir(cx - 1, h + 3, cz, cx + 1, h + 6, cz, "blanc");
  m.remplir(cx - 5, h + 9, cz, cx + 5, h + 9, cz, "blanc");
  m.set(cx, h + 11, cz, "blanc");
}

function gens(m) {
  const P = (o) => m.perso(o);
  // à l'arrivée et au portail
  P({ skin: "garde", x: -3, y: 0, z: 56, lacet: 180, pose: "porte", arme: "ak47" });
  P({ skin: "sicario", x: 3.8, y: 0, z: 56, lacet: 180, pose: "porte", arme: "m60" });
  // sur les miradors
  for (const [x, y, z] of m.miradors) P({ skin: hache(x, z, 1) < 0.5 ? "sicario" : "garde", x: x + 0.5, y, z: z + 0.5, lacet: 135 + (x > 0 ? 0 : 90), pose: "porte", arme: hache(x, z, 2) < 0.5 ? "barrett" : "ak47" });
  // dans l'allée : des gars qui traînent, qui montrent les armes
  const allee = [["narco", -2.5, 48, 90, "porte", "ak47_or"], ["carioca", 2.6, 44, 220, "porte", "uzi"], ["garde", -3, 36, 70, "porte", "rpg7"], ["reina", 2, 30, 250, "repos", null],
    ["sicario", -2, 24, 150, "porte", "ak47"], ["narco", 3, 18, 200, "croise", null], ["carioca", -3.2, 12, 60, "porte", "mac10"], ["garde", 2.5, 6, 230, "porte", "thompson"],
    ["sicario", -1, 0, 180, "porte", "ak47"], ["carioca", 8, 46, 270, "repos", null], ["narco", -8, 41, 90, "croise", null], ["garde", 8, 28, 270, "porte", "ak47"], ["carioca", -8, 23, 90, "repos", null]];
  for (const [skin, x, z, lacet, pose, arme] of allee) P({ skin, x, y: 0, z, lacet, pose, arme });
  // sur la place, devant la hacienda
  P({ skin: "garde", x: -4.5, y: 1, z: -15.5, lacet: 180, pose: "porte", arme: "ak47" });
  P({ skin: "garde", x: 5.5, y: 1, z: -15.5, lacet: 180, pose: "porte", arme: "ak47" });
  P({ skin: "reina", x: -8, y: 0, z: -6, lacet: 120, pose: "repos" });
  P({ skin: "sicario", x: 11, y: 1.6, z: -8, lacet: 180, pose: "vise", arme: "m60", ombre: false, surPickup: true });
  // dans la boutique : le patron derrière son comptoir, ses deux gardes du corps
  P({ skin: "patron", x: m.patron[0], y: m.patron[1], z: m.patron[2], lacet: 0, pose: "croise", patron: true });
  P({ skin: "sicario", x: -8.5, y: 1, z: -27, lacet: 20, pose: "porte", arme: "ak47_or", boutique: true });
  P({ skin: "sicario", x: 9.5, y: 1, z: -27, lacet: -20, pose: "porte", arme: "ak47_or", boutique: true });
  P({ skin: "carioca", x: -3.5, y: 1, z: -22, lacet: 160, pose: "repos", boutique: true, client: true });
  // au stand de tir : les tireurs, l'instructeur
  const tireurs = [["sicario", "ak47"], ["garde", "m60"], ["narco", "ak47_or"], ["carioca", "uzi"], ["garde", "rpg7"], ["sicario", "barrett"]];
  m.couloirs.forEach((c, n) => P({ skin: tireurs[n][0], x: c.x, y: 0, z: 41.2, lacet: 180, pose: "vise", arme: tireurs[n][1], tire: c.cible }));
  P({ skin: "instructeur", x: 30, y: 0, z: 44, lacet: 180, pose: "croise" });
  // près de l'hélico et du dépôt, à la piscine
  P({ skin: "garde", x: -21, y: 1, z: 37, lacet: 120, pose: "porte", arme: "minigun" });
  P({ skin: "sicario", x: -20, y: 0, z: 4, lacet: 90, pose: "porte", arme: "ak47" });
  P({ skin: "narco", x: -24, y: 0, z: 10, lacet: 60, pose: "croise" });
  P({ skin: "reina", x: 34.8, y: 0.45, z: -24, lacet: 270, pose: "repos" });
  // dans la favela
  for (const f of (m.favela ?? []).filter((_, i) => i % 6 === 2).slice(0, 10)) P({ skin: hache(f.x, f.z, 3) < 0.5 ? "carioca" : "garde", x: f.x + 1.5, y: f.haut + 1, z: f.z + f.P / 2, lacet: 150, pose: hache(f.x, f.z, 4) < 0.5 ? "porte" : "repos", arme: "ak47" });
}
