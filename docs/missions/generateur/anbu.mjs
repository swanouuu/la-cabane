// Les trois missions ANBU, bloc par bloc (ce que le code construira dans la dimension des missions).
// x vers l'est, z vers le sud, le sol à y = -1 (l'eau du lac aussi). Mêmes conventions que whoop.mjs.
import { hache } from "../../spies/generateur/moteur.mjs";
import { Monde, lampe, anneau } from "./commun.mjs";
import { bloc, dalle, murs, vider, arbre, bambous, toitJapon, lanterne, garde } from "./batir.mjs";

/** Une maison japonaise : murs de bois et de papier, fenêtres allumées la nuit, toit à quatre pans. Rend le haut du toit. */
function maison(m, x0, y0, z0, x1, z1, { haut = 3, nuit = true, porte = "sud", toit = "tuileGrise" } = {}) {
  murs(m, x0, y0, z0, x1, y0 + haut - 1, z1, (x, y, z) => {
    const coin = (x === x0 || x === x1) && (z === z0 || z === z1);
    if (coin || y === y0 + haut - 1) return "boisJapon";
    return y === y0 + 1 && (x + z) % 3 === 0 ? (nuit ? "fenetreAllumee" : "shoji") : "shoji";
  });
  const cx = Math.round((x0 + x1) / 2), cz = Math.round((z0 + z1) / 2);
  if (porte === "sud") vider(m, cx, y0, z1, cx, y0 + 1, z1);
  if (porte === "nord") vider(m, cx, y0, z0, cx, y0 + 1, z0);
  if (porte === "est") vider(m, x1, y0, cz, x1, y0 + 1, cz);
  if (porte === "ouest") vider(m, x0, y0, cz, x0, y0 + 1, cz);
  dalle(m, x0 + 1, z0 + 1, x1 - 1, z1 - 1, y0 + haut - 1, "boisJapon");
  for (let x = x0; x <= x1; x++) for (let z = z0; z <= z1; z++) if (m.get(x, y0 + 1, z) === "fenetreAllumee" && nuit) lampe(m, x + 0.5, y0 + 1.5, z + 0.5, { r: 4, k: 0.5, halo: false });
  return toitJapon(m, x0, z0, x1, z1, y0 + haut, toit);
}

/** Un torii : deux piliers rouges, deux poutres. */
function torii(m, x, y, z, larg = 3, h = 5) {
  for (const dx of [-larg, larg]) bloc(m, x + dx, y, z, x + dx, y + h - 1, z, "boisRouge");
  bloc(m, x - larg - 1, y + h, z, x + larg + 1, y + h, z, "noir");
  bloc(m, x - larg, y + h - 2, z, x + larg, y + h - 2, z, "boisRouge");
}

// ======================================================================== ANBU 1 : le village de la Brume, la nuit
export function brume() {
  const m = new Monde([-36, 36], [-4, 30], [-36, 36]);
  m.nom = "brume";
  m.ambiance = "nuit";
  m.brume = 0.16;
  m.ciel = { cielHaut: [8, 12, 30], cielBas: [52, 62, 100] };
  // ---------------------------------------------------------------- le lac, l'île du milieu, les rives
  for (let x = -36; x <= 36; x++) for (let z = -36; z <= 36; z++) {
    const d = Math.hypot(x, z);
    m.set(x, -4, z, "pierre"); m.set(x, -3, z, "terre");
    let h = -1;
    if (d < 9) h = 1;
    if (z < -26 + 2 * Math.sin(x * 0.2)) h = 1;
    if (z > 29 + 1.5 * Math.cos(x * 0.25)) h = 0;
    for (let y = -2; y < h; y++) m.set(x, y, z, y === h - 1 ? (d < 9 ? (d < 7 ? "pave" : "herbe") : h === 0 ? "terre" : "herbe") : "terre");
    if (h <= 0) for (let y = h; y <= -1; y++) m.set(x, y, z, "eau");
  }
  // les roseaux au sud (là où on arrive) et la forêt au nord (là où on repart)
  for (let x = -36; x <= 36; x++) for (let z = 18; z <= 36; z++) {
    if (hache(x, z, 7) < 0.55 - (z < 24 ? 0.3 : 0) && Math.abs(x + 2) > 2) {
      const y0 = m.plein(x, -1, z) && m.get(x, -1, z) !== "eau" ? 0 : 0;
      m.set(x, y0, z, "roseaux");
      if (hache(x, z, 8) < 0.5) m.set(x, y0 + 1, z, "roseaux");
    }
  }
  for (let n = 0; n < 40; n++) {
    const x = -34 + Math.floor(hache(n, 1, 11) * 68), z = -35 + Math.floor(hache(n, 2, 11) * 7);
    if (Math.abs(x) < 4) continue;
    arbre(m, x, 1, z, 5 + Math.floor(hache(n, 3, 11) * 4), "feuillesFoncees", 2.6);
  }
  torii(m, 0, 1, -29, 3, 6);
  lanterne(m, -3, 4, -28, "lanterneRouge"); lanterne(m, 3, 4, -28, "lanterneRouge");

  // ---------------------------------------------------------------- les pontons : un rayon par maison, et l'entrée au sud
  const ponton = (a, r0, r1) => {
    for (let r = r0; r <= r1; r += 0.4) {
      const x = Math.round(r * Math.cos(a)), z = Math.round(r * Math.sin(a));
      for (const [dx, dz] of [[0, 0], [Math.round(-Math.sin(a)), Math.round(Math.cos(a))]]) if (!m.plein(x + dx, 1, z + dz)) m.set(x + dx, 0, z + dz, "boisClairJ");
    }
    for (let r = r0 + 2; r <= r1; r += 4) {
      const x = Math.round(r * Math.cos(a)), z = Math.round(r * Math.sin(a));
      bloc(m, x, -3, z, x, -1, z, "tronc");
    }
    const r = (r0 + r1) / 2, x = Math.round(r * Math.cos(a) - 1.4 * Math.sin(a)), z = Math.round(r * Math.sin(a) + 1.4 * Math.cos(a));
    if (!m.plein(x, 1, z)) { bloc(m, x, -2, z, x, 2, z, "tronc"); lanterne(m, x, 3, z); }
  };
  const MAISONS = [];
  for (let k = 0; k < 8; k++) {
    const a = (k / 8) * Math.PI * 2 + Math.PI / 8, hx = Math.round(19 * Math.cos(a)), hz = Math.round(19 * Math.sin(a));
    MAISONS.push([hx, hz, a]);
    ponton(a, 8.5, 15.5);
  }
  ponton(Math.PI / 2, 8.5, 29);
  ponton(-Math.PI / 2, 8.5, 26);
  // les maisons sur pilotis
  for (const [hx, hz, a] of MAISONS) {
    for (const [dx, dz] of [[-3, -3], [3, -3], [-3, 3], [3, 3]]) bloc(m, hx + dx, -3, hz + dz, hx + dx, 0, hz + dz, "tronc");
    dalle(m, hx - 3, hz - 3, hx + 3, hz + 3, 0, "boisClairJ");
    const porte = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? (Math.cos(a) > 0 ? "ouest" : "est") : Math.sin(a) > 0 ? "nord" : "sud";
    maison(m, hx - 2, 1, hz - 2, hx + 2, hz + 2, { haut: 3, porte, toit: hache(hx, hz, 3) < 0.4 ? "paille" : "tuileGrise" });
  }
  // la prison : une cage de bambou sur un radeau, à l'est de l'île
  dalle(m, 11, -3, 15, 1, 0, "boisClairJ");
  for (let x = 11; x <= 15; x++) for (let z = -3; z <= 1; z++) if ((x === 11 || x === 15 || z === -3 || z === 1) && (x + z) % 2 === 0) bloc(m, x, 1, z, x, 3, z, "bambou");
  dalle(m, 11, -3, 15, 1, 4, "paille");

  // ---------------------------------------------------------------- la tour du chef, au milieu
  const dansTour = (x, z, r = 4) => Math.abs(x) <= r && Math.abs(z) <= r && Math.abs(x) + Math.abs(z) <= r + 2;
  const MUR = new Set(["platre", "boisJapon", "fenetreAllumee", "tuileGrise"]);
  for (let y = 1; y <= 20; y++) for (let x = -4; x <= 4; x++) for (let z = -4; z <= 4; z++) {
    if (!dansTour(x, z)) continue;
    const bord = !dansTour(x + 1, z) || !dansTour(x - 1, z) || !dansTour(x, z + 1) || !dansTour(x, z - 1);
    if ((y - 1) % 5 === 4) { m.set(x, y, z, "boisJapon"); continue; }
    if (!bord) continue;
    const milieu = x === 0 || z === 0;
    m.set(x, y, z, milieu && (y - 1) % 5 === 2 ? "fenetreAllumee" : (y - 1) % 5 === 0 ? "boisJapon" : "platre");
  }
  vider(m, 0, 1, 4, 0, 2, 4); vider(m, 0, 1, 5, 0, 2, 5);
  for (let y = 21; y <= 27; y++) {
    const r = Math.max(0, 6 - (y - 21));
    for (let x = -r; x <= r; x++) for (let z = -r; z <= r; z++) if (Math.abs(x) + Math.abs(z) <= r + 2) m.set(x, y, z, "tuileGrise");
  }
  bloc(m, 0, 28, 0, 0, 29, 0, "boisRouge");
  for (const [x, z] of [[-5, 0], [5, 0], [0, -5], [0, 5]]) lanterne(m, x, 19, z, "lanterneRouge");
  // dedans : le rouleau interdit (3e étage), le chef (4e étage)
  m.set(2, 11, 1, "boisRouge");
  m.boite([2.2, 12, 1.25], [2.8, 12.25, 1.75], "objectif");
  lampe(m, 2.5, 12.4, 1.5, { r: 4, c: [1, 0.85, 0.3], k: 1, couleur: [255, 220, 90] });
  anneau(m, 2.5, 1.5, 1.5, 10, { couleur: [255, 210, 60] });
  dalle(m, -3, -3, 3, 3, 15, "tatami");
  m.set(-1, 16, -2, "boisRouge"); m.set(0, 16, -2, "boisRouge"); m.set(1, 16, -2, "boisRouge");
  lampe(m, 0, 18, 0, { r: 7, k: 0.8, halo: false });
  lampe(m, 0, 8, 0, { r: 6, k: 0.6, halo: false });
  m.coupe = (x, y, z) => !(x > 0 && z > 0 && y >= 1 && y <= 27 && Math.abs(x) <= 7 && Math.abs(z) <= 7 && MUR.has(m.get(x, y, z)) && !((y - 1) % 5 === 4 && y <= 20 && dansTour(x, z, 3)));
  m.eclaire = (x, y, z) => dansTour(x, z, 3) && y >= 1 && y <= 20;

  // ---------------------------------------------------------------- les gens
  const G = (o) => garde(m, o);
  // les ANBU : l'un dans les roseaux, l'un qui nage sous un ponton, l'un sur un toit
  G({ skin: "anbu1", x: -6, y: 0, z: 27, lacet: 180, pose: "repos" });
  G({ skin: "anbu2", x: 3, y: -1.3, z: 18, lacet: 180, pose: "repos" });
  const [tx, tz] = MAISONS[1];
  G({ skin: "anbu3", x: tx + 0.5, y: 7, z: tz + 0.5, lacet: 200, pose: "porte", arme: "katana" });
  // les gardes à lanterne sur les pontons, les sentinelles, les gardes de la tour
  G({ skin: "ninjaBrume", x: 0.5, y: 1, z: 15, lacet: 0, pose: "tend", lanterne: true, vue: { portee: 7, angle: 50, force: 0.45 }, ronde: [[0.5, 9], [0.5, 22]], rondeFermee: false });
  G({ skin: "ninjaBrume", x: -10, y: 1, z: -4.5, lacet: 250, pose: "tend", lanterne: true, vue: { portee: 7, angle: 50, force: 0.45 } });
  G({ skin: "ninjaBrume", x: 12, y: 1, z: 6, lacet: 60, pose: "tend", lanterne: true, vue: { portee: 7, angle: 50, force: 0.45 } });
  const [sx, sz] = MAISONS[6];
  G({ skin: "ninjaBrume", x: sx + 0.5, y: 7, z: sz + 0.5, lacet: 30, pose: "croise" });
  G({ skin: "ninjaBrume", x: 1.5, y: 1, z: 6.5, lacet: 0, pose: "porte", arme: "kunai", vue: { portee: 6, angle: 50, force: 0.45 } });
  G({ skin: "ninjaBrume", x: 1.5, y: 6, z: 1.5, lacet: 45, pose: "porte", arme: "kunai" });
  G({ skin: "ninjaBrume", x: 13, y: 1, z: 2.5, lacet: 0, pose: "croise" });
  G({ skin: "anbu2", x: 13, y: 1, z: -1, lacet: 200, pose: "croise", taille: 1 });
  // les villageois qui traînent, dont un qui a vu quelque chose
  G({ skin: "villageois", x: -5, y: 1, z: 4, lacet: 120, pose: "croise" });
  G({ skin: "villageois", x: -6, y: 1, z: 15, lacet: 140, pose: "repos", bulle: "!" });
  G({ skin: "villageois", x: 7, y: 1, z: -15, lacet: 200, pose: "repos" });
  // le chef (toi), en haut de la tour
  G({ skin: "toi", x: 0.5, y: 16, z: -0.5, lacet: 30, pose: "croise" });

  m.etiquettes = [
    [-6, 2.6, 27, "DEPART : LES ROSEAUX", 40],
    [3, 0.8, 18, "SOUS LES PONTONS : ON NAGE SANS ETRE VU", -60],
    [-10, 3, -4.5, "GARDES A LANTERNE", -40],
    [2.5, 13, 1.5, "LE ROULEAU INTERDIT", -40],
    [0.5, 18.4, -0.5, "LE CHEF DU VILLAGE (TOI)", -46],
    [13, 4.6, -1, "LA CAGE (PRISON)", -36],
    [0, 8, -29, "SORTIE : LE TORII", -36],
  ];
  return m;
}

// ======================================================================== ANBU 2 : la forêt des pièges
export function foret() {
  const m = new Monde([-36, 36], [-4, 34], [-36, 36]);
  m.nom = "foret";
  m.ambiance = "foret";
  m.ciel = { cielHaut: [104, 150, 180], cielBas: [200, 220, 190] };
  const riviere = (x) => 8 + 4 * Math.sin(x / 9);
  // ---------------------------------------------------------------- le sol, la rivière
  for (let x = -36; x <= 36; x++) for (let z = -36; z <= 36; z++) {
    m.set(x, -4, z, "pierre"); m.set(x, -3, z, "terre"); m.set(x, -2, z, "terre");
    const dr = Math.abs(z - riviere(x));
    if (dr < 2.2) { m.set(x, -1, z, "eau"); continue; }
    m.set(x, -1, z, dr < 3.2 ? "terre" : hache(x >> 1, z >> 1, 3) < 0.35 ? "mousse" : "herbe");
    if (hache(x, z, 4) < 0.05) m.set(x, 0, z, "feuilles");
  }
  // le sentier : du sud jusqu'au camp
  const chemin = (z) => Math.round(3 * Math.sin(z / 7));
  for (let z = -6; z <= 36; z++) for (const dx of [-1, 0, 1]) {
    const x = chemin(z) + dx;
    if (m.get(x, -1, z) !== "eau") m.set(x, -1, z, "chemin");
    if (m.get(x, 0, z) === "feuilles") m.set(x, 0, z, null);
  }
  // le pont de rondins sur la rivière
  for (let z = 4; z <= 13; z++) for (const dx of [-1, 0, 1]) { const x = chemin(z) + dx; if (m.get(x, -1, z) === "eau" || Math.abs(z - riviere(x)) < 3) m.set(x, 0, z, "tronc"); }

  // ---------------------------------------------------------------- les arbres géants
  const GEANTS = [[-20, 20], [16, 24], [-12, -2], [14, -4], [-26, -18], [24, -22], [-4, 26], [26, 6], [-28, 6], [6, -28], [-14, -30]];
  const plateformes = [];
  for (const [gx, gz] of GEANTS) {
    const h = 17 + Math.floor(hache(gx, gz, 5) * 6);
    for (let y = 0; y < h; y++) for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) if (!(Math.abs(dx) + Math.abs(dz) === 2 && y > 4 && hache(gx + dx, y, gz + dz) < 0.4)) m.set(gx + dx, y, gz + dz, "ecorce");
    // les racines
    for (const [dx, dz] of [[2, 0], [-2, 0], [0, 2], [0, -2], [2, 2], [-2, -2]]) { m.set(gx + dx, 0, gz + dz, "ecorce"); if (hache(gx, dx, dz) < 0.5) m.set(gx + dx * 1.5 | 0, 0, gz + dz * 1.5 | 0, "ecorce"); }
    // la couronne
    for (let dx = -7; dx <= 7; dx++) for (let dy = -3; dy <= 4; dy++) for (let dz = -7; dz <= 7; dz++) {
      const d = Math.hypot(dx / 7, dy / 3.6, dz / 7);
      if (d < 1 && hache(gx + dx, h + dy, gz + dz) > 0.12 * d) m.set(gx + dx, h + dy, gz + dz, "feuillesFoncees");
    }
    // une branche avec sa plateforme de guet
    const s = hache(gx, gz, 6) < 0.5 ? 1 : -1, yb = 8 + Math.floor(hache(gx, gz, 7) * 3);
    for (let k = 2; k <= 4; k++) m.set(gx + s * k, yb, gz, "ecorce");
    dalle(m, gx + s * 4 - 1, gz - 1, gx + s * 4 + 1, gz + 1, yb, "boisClairJ");
    plateformes.push([gx + s * 4, yb + 1, gz]);
  }
  // des arbres plus petits, des buissons
  for (let n = 0; n < 50; n++) {
    const x = -34 + Math.floor(hache(n, 1, 21) * 68), z = -34 + Math.floor(hache(n, 2, 21) * 68);
    if (Math.abs(x - chemin(z)) < 4 || Math.abs(z - riviere(x)) < 4 || (Math.abs(x) < 12 && z > -26 && z < -4) || m.plein(x, 0, z)) continue;
    if (hache(n, 3, 21) < 0.4) arbre(m, x, 0, z, 5, "feuilles", 2.2); else { m.set(x, 0, z, "feuilles"); m.set(x + 1, 0, z, "feuilles"); }
  }

  // ---------------------------------------------------------------- les pièges sur le sentier
  m.pieges = [];
  const fil = (z) => { const x = chemin(z); bloc(m, x - 2, 0, z, x - 2, 1, z, "tronc"); bloc(m, x + 2, 0, z, x + 2, 1, z, "tronc"); m.lignes.push({ a: [x - 1.5, 0.35, z + 0.5], b: [x + 2.5, 0.35, z + 0.5], couleur: [236, 236, 236] }); };
  fil(27); fil(-1);
  // le trou couvert de feuilles, avec ses pieux
  { const x = chemin(18); for (let dx = -1; dx <= 1; dx++) for (let dz = 0; dz <= 1; dz++) { m.set(x + dx, -1, 18 + dz, null); m.set(x + dx, -2, 18 + dz, "fer"); } }
  // la plaque qui fait tomber le tronc
  { const x = chemin(14); m.boite([x - 0.4, -0.02, 14.1], [x + 1.4, 0.06, 14.9], "fer"); m.boite([x - 2.5, 6, 14.1], [x + 3.5, 6.9, 14.9], "tronc"); m.lignes.push({ a: [x - 2, 6.9, 14.5], b: [x - 2, 11, 14.5], couleur: [180, 160, 110] }); m.lignes.push({ a: [x + 3, 6.9, 14.5], b: [x + 3, 11, 14.5], couleur: [180, 160, 110] }); }

  // ---------------------------------------------------------------- le camp des déserteurs
  const CX = 0, CZ = -15;
  for (let k = 0; k < 64; k++) {
    const a = (k / 64) * Math.PI * 2, x = Math.round(CX + 10 * Math.cos(a)), z = Math.round(CZ + 9 * Math.sin(a));
    if (z > CZ + 6 && Math.abs(x - CX) <= 1) continue;
    bloc(m, x, 0, z, x, 2 + (k % 2), z, "tronc");
  }
  // la tour de guet à l'entrée
  for (const [dx, dz] of [[3, 6], [5, 6], [3, 8], [5, 8]]) bloc(m, CX + dx, 0, CZ + dz, CX + dx, 5, CZ + dz, "tronc");
  dalle(m, CX + 2, CZ + 6, CX + 6, CZ + 8, 6, "boisClairJ");
  // les tentes, le feu, la cage de l'otage, la grande tente du chef
  const tente = (x, z, l = 4, nom = "toile") => { for (let k = 0; k <= 2; k++) for (let dz = 0; dz < l; dz++) { m.set(x - k, 2 - k, z + dz, nom); m.set(x + k, 2 - k, z + dz, nom); } };
  tente(CX - 6, CZ - 3); tente(CX + 6, CZ - 3);
  for (let k = 0; k <= 3; k++) for (let dz = 0; dz <= 5; dz++) { m.set(CX - k, 3 - k, CZ - 8 + dz, "boisRouge"); m.set(CX + k, 3 - k, CZ - 8 + dz, "boisRouge"); }
  m.set(CX, 0, CZ + 2, "feu");
  for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) m.set(CX + dx, 0, CZ + 2 + dz, "pierre");
  lampe(m, CX + 0.5, 1.2, CZ + 2.5, { r: 9, c: [1, 0.6, 0.25], k: 1, couleur: [255, 170, 70] });
  for (let x = CX + 4; x <= CX + 7; x++) for (let z = CZ + 1; z <= CZ + 4; z++) if ((x === CX + 4 || x === CX + 7 || z === CZ + 1 || z === CZ + 4) && (x + z) % 2 === 0) bloc(m, x, 0, z, x, 2, z, "bambou");
  dalle(m, CX + 4, CZ + 1, CX + 7, CZ + 4, 3, "paille");
  bloc(m, CX - 8, 0, CZ + 2, CX - 7, 1, CZ + 3, "caisse");

  // ---------------------------------------------------------------- la coupe : on ôte la couronne des arbres pour voir le sol
  m.coupe = (x, y, z) => !(m.get(x, y, z) === "feuillesFoncees" && z > -26 && x > -30);

  // ---------------------------------------------------------------- les gens
  const G = (o) => garde(m, o);
  // les ANBU sur le sentier, un dans un arbre
  G({ skin: "anbu1", x: chemin(31) + 0.5, y: 0, z: 31, lacet: 180, pose: "porte", arme: "katana" });
  G({ skin: "anbu2", x: chemin(29) - 0.5, y: 0, z: 29.5, lacet: 170, pose: "repos" });
  const [px0, py0, pz0] = plateformes[6];
  G({ skin: "anbu3", x: px0 + 0.5, y: py0, z: pz0 + 0.5, lacet: 180, pose: "porte", arme: "kunai" });
  // les guetteurs dans les arbres : ils regardent le sentier en bas
  for (const i of [2, 3, 0, 1]) {
    const [x, y, z] = plateformes[i], l = x < chemin(z) ? 90 : -90;
    G({ skin: "deserteur", x: x + 0.5, y, z: z + 0.5, lacet: l, pose: "porte", arme: "kunai" });
    garde(m, { fantome: true, x: x + 0.5, y: 0, z: z + 0.5, lacet: l, vue: { portee: 10, angle: 35, force: 0.45 } });
  }
  G({ skin: "deserteur", x: chemin(4) + 0.5, y: 0, z: 4, lacet: 0, pose: "porte", arme: "katana", vue: { portee: 8, angle: 50 }, ronde: [[chemin(-3), -3], [chemin(4), 4]], rondeFermee: false });
  G({ skin: "deserteur", x: CX - 1.5, y: 0, z: CZ + 8.5, lacet: 0, pose: "croise", vue: { portee: 8, angle: 50 } });
  G({ skin: "deserteur", x: CX + 4, y: 7, z: CZ + 7, lacet: 0, pose: "porte", arme: "arc", vue: { portee: 12, angle: 30, force: 0.35 } });
  G({ skin: "deserteur", x: CX - 1.5, y: 0, z: CZ + 3.5, lacet: 90, pose: "repos" });
  G({ skin: "otage", x: CX + 5.8, y: 0, z: CZ + 2.6, lacet: 200, pose: "repos", taille: 0.6 });
  G({ skin: "toi", x: CX + 0.5, y: 0, z: CZ - 4, lacet: 0, pose: "croise" });

  m.etiquettes = [
    [chemin(31), 2.6, 31, "DEPART : LE SENTIER", 40],
    [chemin(27), 0.5, 27.5, "PIEGE : LE FIL", -50],
    [chemin(18), 0, 18.5, "PIEGE : LE TROU", 40],
    [chemin(14) + 0.5, 6.9, 14.5, "PIEGE : LE TRONC", -40],
    [plateformes[2][0], plateformes[2][1] + 2.4, plateformes[2][2], "ILS GUETTENT DANS LES ARBRES", -40],
    [CX + 5.5, 3.6, CZ + 2.5, "L'OTAGE (A RAMENER)", -50],
    [CX, 4.2, CZ - 4, "LE CHEF DES DESERTEURS (TOI)", -36],
  ];
  return m;
}

// ======================================================================== ANBU 3 : le château du seigneur, la nuit
export function chateau() {
  const m = new Monde([-36, 36], [-4, 34], [-36, 36]);
  m.nom = "chateau";
  m.ambiance = "nuit";
  m.ciel = { cielHaut: [14, 16, 40], cielBas: [70, 66, 110] };
  const X0 = -21, X1 = 21, Z0 = -24, Z1 = 18, D = 3;
  // ---------------------------------------------------------------- le terrain, les douves, la plate-forme de pierre
  for (let x = -36; x <= 36; x++) for (let z = -36; z <= 36; z++) {
    m.set(x, -4, z, "pierre"); m.set(x, -3, z, "terre"); m.set(x, -2, z, "terre");
    const dedans = x >= X0 && x <= X1 && z >= Z0 && z <= Z1;
    const douve = !dedans && x >= X0 - D && x <= X1 + D && z >= Z0 - D && z <= Z1 + D;
    if (douve) { m.set(x, -1, z, "eau"); continue; }
    if (dedans) {
      for (let y = -1; y <= 1; y++) m.set(x, y, z, "pierreChateau");
      m.set(x, 2, z, "gravier");
    } else m.set(x, -1, z, hache(x >> 1, z >> 1, 4) < 0.3 ? "mousse" : "herbe");
  }
  // le pont sur les douves, au sud
  for (let z = Z1 + 1; z <= Z1 + D + 1; z++) for (let x = -1; x <= 1; x++) m.set(x, 1, z, "boisJapon");
  // la bambouseraie au sud-ouest (l'arrivée), des pins
  bambous(m, -36, 24, -12, 36, 0, 0.32, 41);
  for (let n = 0; n < 16; n++) {
    const x = 26 + Math.floor(hache(n, 1, 31) * 10), z = -34 + Math.floor(hache(n, 2, 31) * 68);
    arbre(m, x, 0, z, 6, "feuillesFoncees", 2.4);
  }
  // ---------------------------------------------------------------- les remparts, les tours d'angle, la porte
  for (let x = X0; x <= X1; x++) for (let z = Z0; z <= Z1; z++) {
    const bord = x <= X0 + 1 || x >= X1 - 1 || z <= Z0 + 1 || z >= Z1 - 1;
    if (!bord) continue;
    for (let y = 3; y <= 5; y++) m.set(x, y, z, y === 3 ? "pierreChateau" : "platre");
    if (x === X0 || x === X1 || z === Z0 || z === Z1) m.set(x, 6, z, "tuileGrise");
  }
  for (const [cx, cz] of [[X0 + 2, Z0 + 2], [X1 - 2, Z0 + 2], [X0 + 2, Z1 - 2], [X1 - 2, Z1 - 2]]) {
    bloc(m, cx - 3, 3, cz - 3, cx + 3, 6, cz + 3, "pierreChateau");
    maison(m, cx - 2, 7, cz - 2, cx + 2, cz + 2, { haut: 3, porte: "sud" });
    lanterne(m, cx, 8, cz + 3, "lanterneRouge");
  }
  // la porte
  bloc(m, -4, 3, Z1 - 2, 4, 7, Z1, "platre");
  vider(m, -1, 3, Z1 - 2, 1, 5, Z1);
  toitJapon(m, -4, Z1 - 2, 4, Z1, 8, "tuileGrise");
  lanterne(m, -2, 4, Z1 + 1, "lanterneRouge"); lanterne(m, 2, 4, Z1 + 1, "lanterneRouge");

  // ---------------------------------------------------------------- la cour : jardin sec, étang aux carpes, cerisiers, lanternes de pierre
  for (let x = -14; x <= -4; x++) for (let z = 2; z <= 10; z++) if (Math.hypot((x + 9) / 5, (z - 6) / 3.6) < 1) m.set(x, 2, z, "eau");
  for (let x = -13; x <= -5; x++) m.set(x, 3, 6, x >= -11 && x <= -7 ? "boisRouge" : null);
  for (const [x, z] of [[10, 4], [14, 8], [6, 9]]) { m.set(x, 3, z, "roche"); if (hache(x, z, 2) < 0.6) m.set(x + 1, 3, z, "roche"); }
  for (const [x, z] of [[-16, 12], [16, 12], [-15, -2], [15, -1]]) arbre(m, x, 3, z, 4, "cerisier", 2.4);
  for (const [x, z] of [[-4, 12], [4, 12], [-4, 0], [4, 0], [-10, -4], [10, -4]]) { m.set(x, 3, z, "pierre"); m.set(x, 4, z, "lanterne"); lampe(m, x + 0.5, 4.5, z + 0.5, { r: 6 }); m.set(x, 5, z, "pierre"); }
  // l'allée de pierre de la porte au donjon
  for (let z = -6; z <= Z1 - 2; z++) for (let x = -1; x <= 1; x++) m.set(x, 2, z, "pave");
  // la caserne à l'ouest, le gong devant
  maison(m, -19, 3, -16, -13, -6, { haut: 4, porte: "est" });
  bloc(m, -11, 3, -9, -11, 6, -9, "boisJapon"); bloc(m, -11, 3, -12, -11, 6, -12, "boisJapon"); bloc(m, -11, 7, -12, -11, 7, -9, "boisJapon");
  m.boite([-11.3, 3.6, -11.4], [-10.7, 6.2, -9.6], "or");
  // les cachots sous la caserne : une grille sur la cour
  for (let z = -15; z <= -11; z++) m.set(-12, 2, z, "barreaux");

  // ---------------------------------------------------------------- le donjon : socle de pierre, trois étages
  bloc(m, -8, 3, -20, 8, 6, -8, "pierreChateau");
  const etage = (x0, y0, z0, x1, z1, h) => {
    murs(m, x0, y0, z0, x1, y0 + h - 1, z1, (x, y, z) => {
      const coin = (x === x0 || x === x1) && (z === z0 || z === z1);
      if (coin || y === y0 + h - 1) return "boisJapon";
      return y === y0 + 1 && (x + z) % 2 === 0 ? "fenetreAllumee" : "platre";
    });
    dalle(m, x0 + 1, z0 + 1, x1 - 1, z1 - 1, y0 - 1, "tatami");
  };
  etage(-7, 7, -19, 7, -9, 4); toitJapon(m, -7, -19, 7, -9, 11, "tuileGrise", 1);
  bloc(m, -5, 11, -17, 5, 12, -11, null);
  etage(-5, 12, -17, 5, -11, 4);
  bloc(m, -4, 12, -16, 4, 14, -12, null);
  toitJapon(m, -5, -17, 5, -11, 16, "tuileGrise", 1);
  bloc(m, -3, 16, -16, 3, 17, -12, null);
  etage(-3, 17, -16, 3, -12, 4);
  bloc(m, -2, 17, -15, 2, 19, -13, null);
  const sommet = toitJapon(m, -3, -16, 3, -12, 21, "tuileGrise", 1);
  for (const x of [-2, 2]) m.set(x, sommet + 1, -14, "or");
  vider(m, -1, 7, -9, 1, 8, -9);
  for (const [x, z] of [[-8, -8], [8, -8]]) lanterne(m, x, 10, z, "lanterneRouge");
  // dedans : le pacte (2e étage), le seigneur (3e étage)
  m.set(2, 12, -12, "boisRouge");
  m.boite([2.2, 13, -11.75], [2.8, 13.25, -11.25], "objectif");
  lampe(m, 2.5, 13.4, -11.5, { r: 4, c: [1, 0.85, 0.3], k: 1, couleur: [255, 220, 90] });
  anneau(m, 2.5, -11.5, 1.4, 11, { couleur: [255, 210, 60] });
  lampe(m, 0, 15, -14, { r: 6, k: 0.6, halo: false }); lampe(m, 0, 19.5, -14, { r: 5, k: 0.6, halo: false });
  // le cerf-volant sur le toit : la sortie
  m.boite([4, sommet + 1, -15], [7, sommet + 4.5, -14.8], "laineRouge", { ombre: false });
  m.boite([4.6, sommet + 1.6, -14.82], [6.4, sommet + 3.9, -14.78], "laineBlanche", { ombre: false });
  m.lignes.push({ a: [5.5, sommet + 1, -14.8], b: [2, sommet, -14], couleur: [230, 230, 230] });
  // la coupe : le mur sud des deux étages du haut, pour voir dedans
  m.coupe = (x, y, z) => !((z === -11 && y >= 12 && y <= 15) || (z === -12 && y >= 17 && y <= 20));
  m.eclaire = (x, y, z) => x >= -5 && x <= 5 && z >= -17 && z <= -11 && y >= 11 && y <= 20;

  // ---------------------------------------------------------------- les gens
  const G = (o) => garde(m, o);
  G({ skin: "anbu1", x: -20, y: 0, z: 27, lacet: 160, pose: "repos" });
  G({ skin: "anbu2", x: -17.5, y: 0, z: 26, lacet: 180, pose: "porte", arme: "katana" });
  G({ skin: "anbu3", x: X1 + 1.5, y: 3.3, z: 2, lacet: -90, pose: "porte", arme: "kunai" });
  // à la porte, les samouraïs ; sur les remparts, les archers qui voient loin
  G({ skin: "samourai", x: -0.5, y: 3, z: Z1 - 0.5, lacet: 0, pose: "porte", arme: "lance", vue: { portee: 9, angle: 40 } });
  G({ skin: "samourai", x: 1.5, y: 3, z: Z1 - 0.5, lacet: 0, pose: "porte", arme: "lance" });
  G({ skin: "archer", x: X1 - 0.5, y: 6, z: -4, lacet: 90, pose: "porte", arme: "arc" });
  garde(m, { fantome: true, x: X1 + 0.5, y: 0, z: -4, lacet: 90, vue: { portee: 12, angle: 28, force: 0.45 } });
  G({ skin: "archer", x: 10, y: 6, z: Z1 - 0.5, lacet: 0, pose: "porte", arme: "arc" });
  garde(m, { fantome: true, x: 10, y: 0, z: Z1 + 4, lacet: 0, vue: { portee: 10, angle: 28, force: 0.45 } });
  G({ skin: "archer", x: X0 + 1.5, y: 6, z: 2, lacet: -90, pose: "porte", arme: "arc" });
  // dans la cour : la ronde, le chien et son maître, le garde du gong
  G({ skin: "samourai", x: 8, y: 3, z: 2, lacet: 180, pose: "porte", arme: "lance", vue: { portee: 8, angle: 45 }, ronde: [[8, 14], [8, -3], [-8, -3], [-8, 14]] });
  G({ skin: "samourai", x: -9.5, y: 3, z: -10.5, lacet: 90, pose: "porte", arme: "lance", vue: { portee: 7, angle: 45 } });
  G({ skin: "samourai", x: 12, y: 3, z: 12, lacet: 220, pose: "tend", lanterne: "rouge" });
  m.perso({ chien: true, x: 12.5, y: 3, z: 9.5, lacet: 200, robe: [214, 134, 60] });
  anneau(m, 12.5, 9.5, 4.2, 2, { couleur: [255, 160, 60], force: 0.75, plein: 0.12 });
  G({ skin: "samourai", x: 0.5, y: 7, z: -7.5, lacet: 0, pose: "porte", arme: "lance", vue: { portee: 8, angle: 45 } });
  G({ skin: "anbu3", x: -15, y: 3, z: -13, lacet: 90, pose: "croise" });
  // le seigneur (toi), en haut du donjon
  G({ skin: "toi", x: 0.5, y: 17, z: -13.5, lacet: 0, pose: "croise" });

  m.etiquettes = [
    [-19, 2.6, 27, "DEPART : LES BAMBOUS", 44],
    [X1 - 0.5, 8.4, -4, "ARCHERS SUR LES REMPARTS", -40],
    [12.5, 4.6, 9.5, "LE CHIEN TE SENT, MEME ACCROUPI", -46],
    [-11, 7.6, -10.5, "LE GONG : TOUT LE CHATEAU SE REVEILLE", -50],
    [-15.5, 4, -13, "LES CACHOTS", 44],
    [2.5, 14, -11.5, "LE PACTE SIGNE", -36],
    [0.5, 20, -13.5, "LE SEIGNEUR (TOI)", -40],
    [5.5, sommet + 5, -15, "SORTIE : LE CERF-VOLANT", -30],
  ];
  return m;
}
