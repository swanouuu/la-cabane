// Les trois missions du WOOHP, bloc par bloc (ce que le code construira dans la dimension des missions).
// x vers l'est, z vers le sud, le sol à y = -1. Chaque construire() rend un Monde avec ses persos, ses lampes, ses
// champs de vision, ses étiquettes (m.etiquettes : [x, y, z, texte, dy]) et ses vues (m.vues).
import { hache } from "../../spies/generateur/moteur.mjs";
import { Monde, lampe, anneau } from "./commun.mjs";
import { bloc, dalle, murs, vider, arbre, palmier, lanterne, garde } from "./batir.mjs";

/** Une enseigne en lettres pleines peinte sur la face sud (ou est) d'un rang de blocs. */
export function enseigne(m, { x0, x1, y0, y1, z, mot, face = "sud", couleur = [255, 255, 255], POLICE }) {
  const cols = mot.length * 4 - 1, L = x1 - x0, H = y1 - y0;
  m.peintures.push((x, y, zz, f, u, v) => {
    if (f !== face || zz !== z || x < x0 || x >= x1 || y < y0 || y >= y1) return null;
    const gx = ((x - x0 + u) / L) * (cols + 2) - 1, gy = ((y1 - (y + 1 - v)) / H) * 7 - 1;
    if (gx < 0 || gy < 0 || gx >= cols || gy >= 5) return null;
    const c = Math.floor(gx / 4), dans = Math.floor(gx) % 4;
    if (dans === 3) return null;
    const motif = POLICE[mot[c]] ?? POLICE[" "];
    return motif[Math.floor(gy) * 3 + dans] === "1" ? couleur : null;
  });
}

/** Une voiture garée (pavés libres). */
function voiture(m, x, z, couleur, long = 5, axe = "x") {
  const [a, b] = axe === "x" ? [[x, 0.25, z], [x + long, 1.5, z + 2.4]] : [[x, 0.25, z], [x + 2.4, 1.5, z + long]];
  m.boite(a, b, couleur);
  const [c, d] = axe === "x" ? [[x + 1.2, 1.5, z + 0.2], [x + long - 1.4, 2.4, z + 2.2]] : [[x + 0.2, 1.5, z + 1.2], [x + 2.2, 2.4, z + long - 1.4]];
  m.boite(c, d, "verreFume");
  for (const [dx, dz] of axe === "x" ? [[0.8, -0.05], [long - 1.6, -0.05], [0.8, 2.15], [long - 1.6, 2.15]] : [[-0.05, 0.8], [-0.05, long - 1.6], [2.15, 0.8], [2.15, long - 1.6]])
    m.boite([x + dx, 0, z + dz], [x + dx + (axe === "x" ? 0.8 : 0.3), 0.8, z + dz + (axe === "x" ? 0.3 : 0.8)], "noir");
}

// ======================================================================== WOOHP 1 : la tour Glamtek
export function glamtek(POLICE) {
  const m = new Monde([-34, 34], [-3, 30], [-34, 34]);
  const X0 = -13, X1 = 13, Z0 = -6, Z1 = 6;
  m.nom = "glamtek";
  m.ambiance = "jour";
  // ---------------------------------------------------------------- le sol de la ville
  for (let x = -34; x <= 34; x++) for (let z = -34; z <= 34; z++) {
    m.set(x, -3, z, "pierre"); m.set(x, -2, z, "terre");
    let nom = "herbe";
    if (z >= 18 && z <= 26) nom = "bitume";
    else if ((z >= 14 && z <= 17) || (z >= 27 && z <= 28)) nom = "beton";
    else if (z >= 7 && z <= 13 && Math.abs(x) <= 20) nom = "pavesClairs";
    else if (x >= X0 && x <= X1 && z >= Z0 && z <= Z1) nom = "marbre";
    else if (z >= -12 && z < Z0 && Math.abs(x) <= 16) nom = "beton";
    m.set(x, -1, z, nom);
  }
  m.peintures.push((x, y, z, f, u, v) => (f === "haut" && y === -1 && z === 22 && (x & 3) < 2 && Math.abs(v - 0.5) < 0.12 ? [236, 230, 200] : null));
  // les passages piétons devant la tour
  m.peintures.push((x, y, z, f, u, v) => (f === "haut" && y === -1 && z >= 18 && z <= 26 && x >= -3 && x <= 3 && Math.floor((x + u) * 2) % 2 === 0 ? [236, 236, 232] : null));

  // ---------------------------------------------------------------- la tour
  const facade = (x, y, z) => {
    if ((y + 1) % 6 === 0) return "quartz";
    if ((z === Z0 || z === Z1) && (x - X0) % 4 === 0) return "quartz";
    if ((x === X0 || x === X1) && (z - Z0) % 4 === 0) return "quartz";
    return "verreBleu";
  };
  murs(m, X0, 0, Z0, X1, 22, Z1, facade);
  for (const y of [5, 11, 17]) dalle(m, X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, y, "plafond");
  dalle(m, X0, Z0, X1, Z1, 23, "beton");
  murs(m, X0, 24, Z0, X1, 24, Z1, (x, y, z) => ((x + z) % 2 ? "quartz" : "roseVif"));
  // la porte tournante du hall
  vider(m, -1, 0, Z1, 1, 2, Z1);
  // l'enseigne GLAMTEK sur le toit, face à la rue
  bloc(m, -10, 25, Z0, 10, 28, Z0, "rosePastel");
  for (const x of [-10, 10]) bloc(m, x, 24, Z0, x, 29, Z0, "quartz");
  bloc(m, -10, 29, Z0, 10, 29, Z0, "quartz");
  enseigne(m, { x0: -9, x1: 10, y0: 25, y1: 29, z: Z0, mot: "GLAMTEK", couleur: [214, 30, 120], POLICE });
  // les murs coupés de la vue : on regarde la tour comme une maison de poupée, la façade sud ouverte
  m.coupe = (x, y, z) => !(z === Z1 && x >= X0 && x <= X1 && y >= 0 && y <= 22 && (y + 1) % 6 !== 0);
  m.eclaire = (x, y, z) => x > X0 && x < X1 && z > Z0 && z <= Z1 && y >= -1 && y <= 22;

  // ---------------------------------------------------------------- étage 0 : le hall
  for (let x = X0 + 1; x < X1; x++) for (let z = Z0 + 1; z <= Z1 - 1; z++) m.set(x, -1, z, (x + z) % 2 ? "marbre" : "marbreNoir");
  bloc(m, -4, 0, 1, 4, 0, 1, "roseVif");
  m.boite([-4.2, 1, 0.8], [4.2, 1.15, 1.6], "quartz");
  // les ascenseurs au fond, sur tous les étages
  for (const b of [0, 6, 12, 18]) {
    bloc(m, -3, b, Z0 + 1, 3, b + 3, Z0 + 1, "acierPlaque");
    for (const x of [-2, 1]) m.boite([x + 0.05, b + 0.05, Z0 + 1.95], [x + 1.95, b + 2.9, Z0 + 2.05], "chrome");
  }
  // les portiques de sécurité
  for (const x of [-7, 7]) { bloc(m, x - 1, 0, -2, x - 1, 2, -2, "fer"); bloc(m, x + 1, 0, -2, x + 1, 2, -2, "fer"); bloc(m, x - 1, 3, -2, x + 1, 3, -2, "fer"); }
  // le poste de sécurité et ses écrans
  bloc(m, 8, 0, 2, 11, 0, 2, "quartz");
  for (const x of [8, 10]) m.boite([x + 0.1, 1, 2.3], [x + 1.9, 2.2, 2.45], "ecran");
  // la cellule (la salle de sécurité), barreaux sur le hall
  murs(m, X0 + 1, 0, Z0 + 1, -9, 3, -1, (x, y, z) => (x === -9 ? "barreaux" : "beton"));
  dalle(m, X0 + 1, Z0 + 1, -9, -1, 4, "beton");
  m.boite([-8.5, 1, -0.6], [-8.2, 1.6, -0.3], "rouge");
  // des plantes
  for (const [x, z] of [[-11, 4], [11, 4], [-6, -4], [6, -4]]) { m.set(x, 0, z, "boisFonce"); m.set(x, 1, z, "feuilles"); m.set(x, 2, z, "feuilles"); }

  // ---------------------------------------------------------------- étage 1 : l'open space
  for (let x = X0 + 1; x < X1; x++) for (let z = Z0 + 1; z < Z1; z++) m.set(x, 5, z, "moquette");
  for (const x of [-11, -7, 4, 8]) for (const z of [-3, 1]) {
    bloc(m, x, 6, z, x + 1, 6, z, "quartz");
    m.boite([x + 0.2, 7, z + 0.35], [x + 1.8, 7.9, z + 0.5], "ecran");
    m.boite([x + 0.3, 6, z + 1.2], [x + 1.6, 6.6, z + 1.9], "noir");
  }
  bloc(m, 11, 6, -4, 11, 7, -4, "verreBleu");
  for (const [x, z] of [[-1, 3], [12 - 1, 4]]) { m.set(x, 6, z, "boisFonce"); m.set(x, 7, z, "feuilles"); }

  // ---------------------------------------------------------------- étage 2 : les serveurs et les lasers
  for (let x = X0 + 1; x < X1; x++) for (let z = Z0 + 1; z < Z1; z++) m.set(x, 11, z, "carrelageNoir");
  for (let x = -11; x <= 10; x += 3) {
    bloc(m, x, 12, Z0 + 1, x + 1, 15, Z0 + 2, "verreFume");
    for (let y = 12; y <= 15; y++) m.boite([x + 0.2, y + 0.4, Z0 + 2.95], [x + 1.8, y + 0.5, Z0 + 3.02], hache(x, y, 3) < 0.5 ? "neonVert" : "neonBleu", { ombre: false });
  }
  for (let k = 0; k < 7; k++) {
    const z1 = -1.5 + k * 0.95, y1 = 12.3 + (k % 3) * 1.1;
    m.lignes.push({ a: [X0 + 1, y1, z1], b: [X1, y1 + (k % 2 ? 0.8 : -0.3), z1 + (k % 2 ? 1.6 : -1.2)], laser: true });
  }
  // la caméra au plafond, dans le coin
  m.cameras = [{ p: [11.5, 16.4, 4.5], lacet: -135 }];

  // ---------------------------------------------------------------- étage 3 : le labo et le bureau de la patronne
  for (let x = X0 + 1; x < X1; x++) for (let z = Z0 + 1; z < Z1; z++) m.set(x, 17, z, x <= 1 ? "carrelage" : "moquetteRose");
  for (let z = Z0 + 1; z < Z1; z++) for (let y = 18; y <= 22; y++) if (!(z >= 3 && y <= 19)) m.set(1, y, z, "verreRose");
  for (const x of [-11, -8, -5]) {
    m.set(x, 18, -4, "acierPlaque"); m.set(x, 19, -4, "neonRose"); m.set(x, 20, -4, "neonRose"); m.set(x, 21, -4, "acierPlaque");
    lampe(m, x + 0.5, 19.5, -3, { r: 5, c: [1.0, 0.35, 0.75], k: 0.7, halo: false });
  }
  bloc(m, -11, 18, 0, -8, 18, 0, "quartz");
  for (let k = 0; k < 4; k++) m.boite([-10.8 + k * 0.9, 19, 0.3], [-10.5 + k * 0.9, 19.5, 0.6], k % 2 ? "roseVif" : "verreBleu");
  // l'objectif : la fiole de la formule, sur son socle
  m.set(-4, 18, 2, "quartz");
  m.boite([-3.75, 19, 2.25], [-3.25, 19.7, 2.75], "objectif");
  lampe(m, -3.5, 19.6, 2.5, { r: 4, c: [1.0, 0.85, 0.3], k: 1, couleur: [255, 220, 90] });
  anneau(m, -3.5, 2.5, 1.7, 17, { couleur: [255, 210, 60] });
  // le bureau de la patronne
  bloc(m, 5, 18, -2, 9, 18, -2, "boisFonce");
  m.boite([4.8, 19, -2.2], [9.2, 19.15, -1.6], "boisFonce");
  bloc(m, 7, 18, -4, 7, 20, -4, "roseVif");
  bloc(m, 4, 19, Z0 + 1, 10, 21, Z0 + 1, "ecranRose");
  for (const z of [3, 4]) { m.set(11, 18, z, "boisFonce"); m.set(11, 19, z, "feuilles"); }

  // ---------------------------------------------------------------- le toit
  m.peintures.push((x, y, z, f, u, v) => {
    if (f !== "haut" || y !== 23 || Math.abs(x) > 6 || Math.abs(z) > 5) return null;
    const px = x + u - 0.5, pz = z + v - 0.5, d = Math.hypot(px, pz);
    if (d > 4.4 && d < 4.9) return [240, 200, 40];
    return (Math.abs(px) < 2 && Math.abs(pz) < 0.4) || (Math.abs(Math.abs(px) - 1.7) < 0.4 && Math.abs(pz) < 2.4) ? [244, 242, 236] : null;
  });
  anneau(m, 0.5, 0.5, 4.9, 23, { couleur: [90, 255, 160], force: 0.7, plein: 0.0 });
  for (const [x, z] of [[9, -4], [-11, -4]]) bloc(m, x, 24, z, x + 1, 25, z + 2, "acierPlaque");

  // ---------------------------------------------------------------- la place devant
  bloc(m, -11, 0, 8, -5, 0, 12, "marbre");
  bloc(m, -10, 0, 9, -6, 0, 11, "eau");
  bloc(m, -8, 1, 10, -8, 2, 10, "quartz");
  m.set(-8, 3, 10, "eau");
  // le logo : un grand anneau rose
  bloc(m, 6, 0, 10, 10, 0, 10, "quartz");
  for (let k = 0; k < 40; k++) { const a = (k / 40) * Math.PI * 2; m.set(8 + Math.round(2.6 * Math.cos(a)), 3.6 + Math.round(2.6 * Math.sin(a)), 10, "roseVif"); }
  for (const x of [-18, -14, 14, 18]) arbre(m, x, 0, 9, 4, "feuilles", 2);
  for (const x of [-30, -24, 24, 30]) arbre(m, x, 0, 31, 5, "feuilles", 2.6);
  for (const x of [-26, -10, 6, 22]) { bloc(m, x, 0, 15, x, 3, 15, "fer"); m.set(x, 4, 15, "lanterne"); }
  // les voisins : un bureau gris à l'est, un café de briques à l'ouest
  murs(m, 21, 0, -14, 33, 13, 5, (x, y, z) => (y % 3 === 1 && (x + z) % 4 ? "verreFume" : "beton"));
  dalle(m, 21, -14, 33, 5, 14, "beton");
  murs(m, -33, 0, -10, -22, 5, 5, (x, y, z) => (y >= 1 && y <= 3 && (z === 5 || x === -22) && (x + z) % 3 ? "vitre" : "brique"));
  dalle(m, -33, -10, -22, 5, 6, "boisFonce");
  for (let x = -33; x <= -22; x++) m.boite([x, 4, 5], [x + 1, 4.2, 7], (x & 1) ? "laineBlanche" : "laineRouge");
  for (let x = -16; x <= 16; x += 8) arbre(m, x, 0, -12, 4, "feuilles", 2);
  // la rue : la camionnette du WOOHP (l'arrivée) et deux voitures
  m.boite([-26, 0.3, 23.4], [-19, 3, 26.2], "laineBlanche");
  m.boite([-26.05, 2.3, 23.35], [-19, 2.7, 26.25], "roseVif");
  m.boite([-19.2, 1.6, 23.6], [-18.6, 2.6, 26], "verreFume");
  for (const [dx, dz] of [[1, -0.05], [5, -0.05], [1, 2.55], [5, 2.55]]) m.boite([-26 + dx, 0, 23.4 + dz], [-25 + dx, 0.9, 23.7 + dz], "noir");
  voiture(m, 14, 19, "laineBleue");
  voiture(m, -6, 23.5, "laineRouge");

  // ---------------------------------------------------------------- les gens
  const E = (o) => garde(m, o);
  // les espionnes à la camionnette
  E({ skin: "espionneVerte", x: -23, y: 0, z: 27.6, lacet: 160, pose: "repos" });
  E({ skin: "espionneRouge", x: -21.5, y: 0, z: 27.9, lacet: 180, pose: "repos" });
  E({ skin: "espionneJaune", x: -20, y: 0, z: 27.6, lacet: 200, pose: "repos" });
  // dehors : l'agent devant la porte
  E({ skin: "agent", x: 3.5, y: 0, z: 8.5, lacet: 10, pose: "croise", vue: { portee: 9, angle: 45 } });
  E({ skin: "employe", x: -15, y: 0, z: 15.5, lacet: 90, pose: "repos" });
  // le hall
  E({ skin: "employe", x: 0.5, y: 0, z: -0.3, lacet: 0, pose: "repos" });
  E({ skin: "agent", x: 9.5, y: 0, z: 0.6, lacet: -60, pose: "porte", arme: "matraque", vue: { portee: 8, angle: 50 } });
  E({ skin: "agent", x: -5, y: 0, z: 3.5, lacet: 90, pose: "porte", arme: "matraque", ronde: [[-6, 3.5], [5, 3.5], [5, -3.5], [-6, -3.5]] });
  // l'open space
  E({ skin: "employe", x: -9.5, y: 6, z: 3, lacet: 120, pose: "repos" });
  E({ skin: "employe", x: 2, y: 6, z: -0.5, lacet: 200, pose: "repos" });
  E({ skin: "employe", x: 6.5, y: 6, z: 3.6, lacet: 80, pose: "croise", bulle: "!" });
  E({ skin: "agent", x: -3, y: 6, z: 4, lacet: 100, pose: "porte", arme: "matraque", vue: { portee: 9, angle: 50 }, ronde: [[-10, 4], [10, 4], [10, -1.5], [-10, -1.5]] });
  // les lasers
  E({ skin: "agent", x: -9, y: 12, z: 3.5, lacet: 80, pose: "porte", arme: "matraque", vue: { portee: 8, angle: 45 } });
  // le labo, le bureau
  E({ skin: "agent", x: 2.5, y: 18, z: 4.2, lacet: -90, pose: "croise", vue: { portee: 7, angle: 45 } });
  E({ skin: "toi", x: 7.5, y: 18, z: -3.2, lacet: 0, pose: "croise" });
  // une espionne déjà en haut, en train de prendre la fiole… et une autre attrapée dans la cellule
  E({ skin: "espionneVerte", x: -5.2, y: 18, z: 3.6, lacet: 120, pose: "tend" });
  E({ skin: "espionneJaune", x: -11, y: 0, z: -3, lacet: 90, pose: "croise" });

  m.etiquettes = [
    [-22.5, 3.6, 25, "ARRIVEE DU TOBOGGAN", 40],
    [0, 3.2, 0, "LE HALL : AGENTS ET PORTIQUES", -30],
    [-11, 3.2, -1, "LA CELLULE", -28],
    [6.5, 9.4, 3.6, "OPEN SPACE : LES EMPLOYES CRIENT", -26],
    [-2, 15.5, 2, "LES LASERS : ALARME", -26],
    [-4, 20.6, 2.5, "OBJECTIF : LA FORMULE", -30],
    [7.5, 21, -3, "LA PATRONNE (TOI)", -26],
    [0.5, 25, 0.5, "EXTRACTION : LE TOIT", -40],
  ];
  return m;
}

// ======================================================================== WOOHP 2 : le Méga Mall, la nuit
export function megamall(POLICE) {
  const m = new Monde([-34, 34], [-8, 20], [-34, 34]);
  const X0 = -26, X1 = 26, Z0 = -18, Z1 = 14;
  m.nom = "megamall";
  m.ambiance = "neons";
  const dansAtelier = (x, y, z) => x >= -6 && x <= 6 && z >= -6 && z <= 6 && y >= -7 && y <= -2;
  // ---------------------------------------------------------------- le sol, le parking
  for (let x = -34; x <= 34; x++) for (let z = -34; z <= 34; z++) {
    for (const y of [-3, -2]) if (!dansAtelier(x, y, z)) m.set(x, y, z, y === -3 ? "pierre" : "terre");
    let nom = "herbe";
    if (x >= X0 && x <= X1 && z >= Z0 && z <= Z1) nom = "carrelage";
    else if (z > Z1 && z <= 33) nom = "bitume";
    m.set(x, -1, z, nom);
  }
  m.peintures.push((x, y, z, f, u, v) => (f === "haut" && y === -1 && z >= 20 && z <= 32 && z !== 26 && ((x % 4) + 4) % 4 === 0 && u < 0.14 ? [230, 226, 210] : null));
  for (const x of [-24, -12, 0, 12, 24]) { bloc(m, x, 0, 26, x, 4, 26, "fer"); lanterne(m, x, 5, 26, "lanterne", { r: 8 }); }

  // ---------------------------------------------------------------- le bâtiment
  murs(m, X0, 0, Z0, X1, 12, Z1, (x, y, z) => (y === 5 || y === 12 ? "neonRose" : y >= 7 && y <= 10 && (x + z) % 5 ? "vitrine" : "beton"));
  for (let x = X0 + 1; x < X1; x++) for (let z = Z0 + 1; z < Z1; z++) {
    if (!(x >= -9 && x <= 9 && z >= -6 && z <= 5)) m.set(x, 6, z, "carrelage");
    m.set(x, 13, z, "beton");
  }
  vider(m, -5, 0, Z1, 5, 4, Z1);
  // l'enseigne sur le toit
  bloc(m, -14, 14, Z0 + 1, 14, 17, Z0 + 1, "noir");
  enseigne(m, { x0: -13, x1: 14, y0: 14, y1: 18, z: Z0 + 1, mot: "MEGA MALL", couleur: [255, 90, 210], POLICE });
  for (let x = -14; x <= 14; x += 4) lampe(m, x, 16, Z0 + 2.5, { r: 5, c: [1, 0.4, 0.8], k: 0.6, couleur: [255, 90, 210] });
  // la coupe : le toit retiré au-dessus de l'atrium, la moitié sud de l'étage et la façade sud ouvertes
  m.coupe = (x, y, z) => {
    if (y === 13 && z > -14) return false;
    if (y >= 6 && y <= 12 && z > 0 && x >= X0 && x <= X1 && z <= Z1) return false;
    if (z === Z1 && y >= 0 && y <= 5) return false;
    if (x === X1 && y >= 0 && y <= 5 && z > 0) return false;
    return true;
  };

  // ---------------------------------------------------------------- l'atrium : la fontaine, l'escalator
  murs(m, -3, 0, -3, 3, 0, 3, "marbre");
  bloc(m, -2, 0, -2, 2, 0, 2, "eau");
  bloc(m, 0, 1, 0, 0, 3, 0, "neonBleu");
  lampe(m, 0.5, 2.5, 0.5, { r: 9, c: [0.3, 0.7, 1.0], k: 0.9, couleur: [80, 190, 255] });
  m.set(4, -1, 1, "danger");
  for (let k = 0; k <= 6; k++) {
    bloc(m, 10, k, 4 - k, 11, k, 4 - k, "caoutchouc");
    m.set(9, k + 1, 4 - k, "verreBleu"); m.set(12, k + 1, 4 - k, "verreBleu");
  }
  for (const [x, z] of [[-8, 4], [8, -5], [-8, -5]]) { m.set(x, 0, z, "boisFonce"); m.set(x, 1, z, "feuilles"); m.set(x, 2, z, "feuilles"); }
  for (const [x, z] of [[-6, 2], [6, 2], [-6, -4], [6, -4]]) lampe(m, x, 4.5, z, { r: 7, c: [0.5, 0.45, 0.9], k: 0.5, halo: false });

  // ---------------------------------------------------------------- les boutiques du bas (au sud)
  const vitrine = (x0, x1, porte) => { for (let x = x0; x <= x1; x++) for (let y = 0; y <= 5; y++) m.set(x, y, 7, y === 5 ? "noir" : Math.abs(x - porte) <= 1 && y <= 2 ? null : "vitrineAllumee"); };
  for (const x of [-15, -6, 6, 15]) bloc(m, x, 0, 7, x, 5, Z1 - 1, "beton");
  vitrine(-25, -16, -20); vitrine(-14, -7, -10); vitrine(7, 14, 10);
  // la mode : les mannequins sur leur podium, les portants
  for (let x = -25; x <= -16; x++) for (let z = 8; z <= 13; z++) m.set(x, -1, z, "parquet");
  bloc(m, -25, 0, 12, -16, 0, 13, "quartz");
  for (const z of [9, 10]) m.boite([-24, 1.8, z + 0.4], [-18, 1.9, z + 0.5], "fer");
  for (let k = 0; k < 10; k++) m.boite([-23.8 + k * 0.6, 0.6, 9.3], [-23.5 + k * 0.6, 1.8, 9.6], ["laineRouge", "laineBlanche", "rosePastel", "laineNoire"][k % 4]);
  m.set(-17, 0, 9, "quartz");
  m.boite([-16.85, 1, 9.15], [-16.15, 1.25, 9.85], "puce");
  lampe(m, -16.5, 1.4, 9.5, { r: 3.5, c: [0.3, 1.0, 0.9], k: 1, couleur: [80, 240, 230] });
  // les chaussures
  for (let x = -14; x <= -7; x++) for (let z = 8; z <= 13; z++) m.set(x, -1, z, "moquetteRose");
  const COULEURS = ["laineRouge", "rosePastel", "laineBlanche", "laineNoire", "laineJaune", "laineBleue"];
  for (const z of [10, 12]) { bloc(m, -13, 0, z, -8, 0, z, "boisFonce"); for (let x = -13; x <= -8; x++) m.boite([x + 0.2, 1, z + 0.2], [x + 0.8, 1.4, z + 0.8], COULEURS[((x + z) % 6 + 6) % 6]); }
  // les jouets
  for (let x = 7; x <= 14; x++) for (let z = 8; z <= 13; z++) m.set(x, -1, z, "moquette");
  for (const [x, z] of [[8, 12], [9, 12], [12, 12], [13, 12], [8, 9]]) for (let y = 0; y < 3; y++) m.set(x, y, z, ["laineRouge", "laineJaune", "laineBleue", "laineVerte"][(x + y + z) % 4]);
  m.boite([10.2, 0, 12.1], [11.8, 2.2, 13.6], "boisClair");
  m.boite([10.4, 2.2, 12.3], [11.6, 3.2, 13.4], "boisClair");
  m.set(12, 0, 9, "quartz");
  m.boite([12.15, 1, 9.15], [12.85, 1.25, 9.85], "puce");
  lampe(m, 12.5, 1.4, 9.5, { r: 3.5, c: [0.3, 1.0, 0.9], k: 1, couleur: [80, 240, 230] });
  // la remise (la cellule) et la cafétéria, à l'est
  murs(m, 16, 0, 0, 25, 4, 6, (x, y, z) => (x === 16 && z >= 2 && z <= 4 ? "barreaux" : "beton"));
  for (const [x, z] of [[18, 9], [22, 9], [18, 12], [22, 12]]) { m.set(x, 0, z, "fer"); m.boite([x - 0.6, 1, z - 0.6], [x + 1.6, 1.15, z + 1.6], "quartz"); }
  bloc(m, 24, 0, 8, 25, 1, 13, "neonJaune");

  // ---------------------------------------------------------------- l'étage : cinéma, arcade, électro (au nord)
  for (let x = X0 + 1; x < X1; x++) for (let y = 7; y <= 12; y++) {
    if ([-11, 2].includes(x)) for (let z = Z0 + 1; z <= -9; z++) m.set(x, y, z, "beton");
    m.set(x, y, -9, y === 11 || y === 12 ? "noir" : y <= 9 && x % 6 !== 0 ? "vitrineAllumee" : "beton");
  }
  for (const x0 of [-20, -7, 12]) vider(m, x0, 7, -9, x0 + 1, 8, -9);
  enseigne(m, { x0: -24, x1: -12, y0: 11, y1: 13, z: -9, mot: "CINEMA", couleur: [255, 230, 80], POLICE });
  enseigne(m, { x0: -10, x1: 2, y0: 11, y1: 13, z: -9, mot: "ARCADE", couleur: [90, 255, 140], POLICE });
  enseigne(m, { x0: 4, x1: 22, y0: 11, y1: 13, z: -9, mot: "ELECTRO", couleur: [80, 200, 255], POLICE });
  for (const [x, c] of [[-18, [1, 0.9, 0.3]], [-4, [0.3, 1, 0.5]], [12, [0.3, 0.8, 1]]]) lampe(m, x, 11.5, -7.5, { r: 7, c, k: 0.7, halo: false });
  for (let x = -9; x <= 0; x += 3) { bloc(m, x, 7, -12, x, 8, -12, "noir"); m.set(x, 8, -11, "ecran"); }
  for (let x = 4; x <= 24; x++) for (let y = 8; y <= 10; y++) m.set(x, y, -16, (x + y) % 3 ? "ecran" : "ecranRose");
  m.set(-22, 7, -12, "quartz");
  m.boite([-21.85, 8, -11.85], [-21.15, 8.25, -11.15], "puce");
  lampe(m, -21.5, 8.4, -11.5, { r: 3.5, c: [0.3, 1.0, 0.9], k: 1, couleur: [80, 240, 230] });
  for (let x = -24; x <= -13; x += 3) m.set(x, 9, -10, "neonJaune");

  // ---------------------------------------------------------------- sous la fontaine : l'atelier du styliste
  murs(m, -6, -7, -6, 6, -2, 6, "acierPlaque");
  dalle(m, -5, -5, 5, 5, -7, "carrelageNoir");
  bloc(m, -3, -6, -5, 3, -4, -5, "ecranRose");
  bloc(m, 0, -6, -3, 0, -5, -3, "roseVif");
  bloc(m, -5, -6, 1, -2, -6, 1, "boisFonce");
  for (let k = 0; k < 4; k++) m.boite([-4.8 + k * 0.8, -5, 1.2], [-4.3 + k * 0.8, -4.5, 1.7], k % 2 ? "fer" : "laineRouge");
  for (let x = 2; x <= 4; x++) m.boite([x + 0.25, -5, 4.2], [x + 0.75, -4.5, 4.7], "quartz");
  lampe(m, 0, -3, 0, { r: 9, c: [1.0, 0.4, 0.8], k: 0.8, halo: false });
  bloc(m, 5, -6, -1, 5, -2, -1, "fer");

  // ---------------------------------------------------------------- les gens
  const E = (o) => garde(m, o);
  E({ skin: "espionneVerte", x: 2.5, y: 0, z: 18, lacet: 180, pose: "repos" });
  E({ skin: "espionneRouge", x: 4, y: 0, z: 18.4, lacet: 190, pose: "repos" });
  // la troisième, dans le hall, qui fixe un mannequin… pendant qu'un autre arrive dans son dos
  E({ skin: "espionneJaune", x: -3, y: 0, z: 8.5, lacet: -120, pose: "repos" });
  E({ skin: "mannequin", x: -5.5, y: 0, z: 7.2, lacet: 60, pose: "repos", bulle: "?" });
  E({ skin: "mannequin", x: -0.5, y: 0, z: 11, lacet: -150, pose: "tend", bulle: "!" });
  // les mannequins de la vitrine, figés
  for (let k = 0; k < 5; k++) E({ skin: "mannequin", x: -24 + k * 2, y: 1, z: 12.6, lacet: 0, pose: k % 2 ? "repos" : "croise" });
  E({ skin: "mannequin", x: -12, y: 0, z: 4, lacet: 30, pose: "tend" });
  E({ skin: "mannequin", x: 14, y: 0, z: -2, lacet: -60, pose: "tend" });
  E({ skin: "mannequin", x: -16, y: 7, z: -7, lacet: 90, pose: "tend" });
  // le vigile et sa lampe
  E({ skin: "vigile", x: -10, y: 0, z: 2, lacet: 70, pose: "pistolet", arme: "lampe_torche", vue: { portee: 11, angle: 22, couleur: [255, 236, 140], force: 0.55 },
    ronde: [[-10, 2], [10, 2], [14, -6], [-10, -6]] });
  E({ skin: "espionneRouge", x: 18.5, y: 0, z: 3, lacet: 90, pose: "croise" });
  E({ skin: "toi", x: 0.5, y: -6, z: -2.5, lacet: 0, pose: "croise" });
  for (const [x, z, l] of [[-3.5, -1, 30], [3.5, 0, -40]]) E({ skin: "mannequin", x, y: -6, z, lacet: l, pose: "repos" });

  m.etiquettes = [
    [3, 3, 18.5, "ARRIVEE DU TOBOGGAN", 40],
    [-1, 3.2, 11, "ILS BOUGENT QUAND TU NE LES REGARDES PAS", -50],
    [-20, 3.4, 12.6, "LA VITRINE DES MANNEQUINS", -36],
    [-16.5, 1.4, 9.5, "PUCE 1", 30],
    [12.5, 1.4, 9.5, "PUCE 2", 30],
    [-21.5, 8.5, -11.5, "PUCE 3", -30],
    [-10, 0.4, 2, "LE VIGILE ET SA LAMPE", 50],
    [20.5, 4.6, 3, "LA REMISE (CELLULE)", -30],
    [0.5, 3.6, 0.5, "SOUS LA FONTAINE : L'ATELIER (TOI)", -60],
    [0, 15, -13, "SORTIE : LE TOIT (GRAPPIN)", -30],
  ];
  return m;
}

// ======================================================================== WOOHP 3 : l'île du méchant
export function ile(POLICE) {
  const m = new Monde([-36, 36], [-4, 32], [-36, 36]);
  m.nom = "ile";
  m.ambiance = "jour";
  m.ciel = { cielHaut: [64, 150, 222], cielBas: [250, 214, 168] };
  const VX = -8, VZ = -8;
  const rayon = (x, z) => Math.hypot(x, z * 1.1) + 2.4 * Math.sin(x * 0.3) + 2 * Math.cos(z * 0.27);
  const dV = (x, z) => Math.hypot(x - VX, z - VZ);
  const hauteur = (x, z) => {
    const r = rayon(x, z);
    let h = r > 28 ? -2 : r > 23 ? 0 : r > 19 ? 1 : 2;
    const d = dV(x, z);
    if (d < 18 && d >= 5) h = Math.max(h, Math.round(24 - 1.3 * d));
    if (d < 5) h = 5;
    return h;
  };
  const roche = new Set(["basalte", "roche"]);
  for (let x = -36; x <= 36; x++) for (let z = -36; z <= 36; z++) {
    const h = hauteur(x, z), d = dV(x, z), r = rayon(x, z);
    for (let y = -4; y < h; y++) {
      let nom = y < -2 ? "pierre" : "terre";
      if (d < 18 && y >= 1) nom = hache(x, y * 7 + z, 5) < 0.3 ? "roche" : "basalte";
      else if (y === h - 1) nom = r > 23 ? "sable" : r > 20 && h <= 1 ? "sable" : "herbe";
      m.set(x, y, z, nom);
    }
    if (h <= 0) for (let y = h; y <= -1; y++) if (!m.plein(x, y, z)) m.set(x, y, z, "eau");
  }
  // le silo au fond du cratère, la fusée
  for (let x = VX - 4; x <= VX + 4; x++) for (let z = VZ - 4; z <= VZ + 4; z++) if (dV(x, z) < 5) m.set(x, 4, z, (x + z) % 2 ? "danger" : "acierPlaque");
  for (let y = 5; y <= 25; y++) for (let x = VX - 1; x <= VX + 1; x++) for (let z = VZ - 1; z <= VZ + 1; z++) {
    if (y > 22 && Math.abs(x - VX) + Math.abs(z - VZ) > (25 - y)) continue;
    m.set(x, y, z, y > 22 ? "laineRouge" : y % 6 === 0 ? "laineNoire" : "quartz");
  }
  for (const [dx, dz] of [[2, 0], [-2, 0], [0, 2], [0, -2]]) bloc(m, VX + dx, 5, VZ + dz, VX + dx, 8, VZ + dz, "metalOrange");
  // les trois réservoirs à saboter
  m.reservoirs = [];
  for (let k = 0; k < 3; k++) {
    const a = (k / 3) * Math.PI * 2 + 0.5, x = Math.round(VX + 3.4 * Math.cos(a)), z = Math.round(VZ + 3.4 * Math.sin(a));
    bloc(m, x, 5, z, x, 7, z, "metalOrange");
    m.set(x, 8, z, "danger");
    lampe(m, x + 0.5, 8.5, z + 0.5, { r: 3, c: [1, 0.6, 0.2], k: 0.8, couleur: [255, 170, 60] });
    m.reservoirs.push([x, z]);
  }
  // la base creusée dans le flanc sud-est : le couloir, la salle de contrôle, les cellules
  const creuser = (x0, y0, z0, x1, y1, z1, sol) => { bloc(m, x0, y0, z0, x1, y1, z1, null); dalle(m, x0, z0, x1, z1, y0 - 1, sol); };
  creuser(-3, 2, -3, 4, 6, 4, "acierPlaque");
  for (let k = 0; k < 9; k++) creuser(3 + k, 2, 3 + k, 4 + k, 4, 4 + k, "acierPlaque");
  creuser(-6, 5, -6, -3, 7, -3, "danger");
  creuser(-16, 2, -3, -9, 5, 2, "beton");
  for (let z = -3; z <= 2; z++) for (let y = 2; y <= 5; y++) m.set(-9, y, z, z === 0 && y <= 3 ? null : "barreaux");
  creuser(-9, 2, 0, -4, 3, 1, "beton");
  // la porte blindée, dans le flanc
  for (let y = 2; y <= 5; y++) { m.set(13, y, 12, "danger"); m.set(10, y, 15, "danger"); }
  bloc(m, 11, 5, 13, 12, 5, 14, "danger");
  // la salle de contrôle : le grand écran (le compte à rebours), les pupitres, le fauteuil du chef
  bloc(m, -3, 2, -3, 4, 6, -3, "ecran");
  for (let x = -2; x <= 3; x++) for (let y = 3; y <= 5; y++) m.set(x, y, -3, (x + y) % 4 === 0 ? "ecranRouge" : "ecran");
  for (const x of [-2, 0, 2]) { m.set(x, 2, -1, "acierPlaque"); m.boite([x + 0.1, 3, -1.1], [x + 0.9, 3.6, -0.9], "ecran"); }
  bloc(m, 1, 2, 2, 1, 3, 2, "roseVif");
  lampe(m, 0.5, 5, 0, { r: 8, c: [0.4, 0.6, 1.0], k: 0.7, halo: false });
  // la coupe : le quart sud-est du volcan retiré, pour voir dedans
  m.coupe = (x, y, z) => !(x > VX + 1 && z > VZ + 1 && y >= 2 && dV(x, z) < 19 && roche.has(m.get(x, y, z)));

  // ---------------------------------------------------------------- la plage : le ponton, les projecteurs, le sous-marin
  for (let z = 21; z <= 33; z++) for (let x = 4; x <= 6; x++) m.set(x, 0, z, "boisFonce");
  for (const z of [24, 28, 32]) for (const x of [4, 6]) bloc(m, x, -3, z, x, -1, z, "tronc");
  m.boite([7.3, -0.6, 27], [9.8, 0.6, 33], "laineBlanche");
  m.boite([7.25, 0.2, 26.9], [9.85, 0.5, 33.1], "laineRouge");
  m.boite([7.8, 0.6, 29.5], [9.3, 1.4, 31], "verreFume");
  m.boite([-34, -1.2, 2], [-26, 0.8, 6], "laineJaune");
  m.boite([-31, 0.8, 3.2], [-29, 2.6, 4.8], "laineJaune");
  m.boite([-30.6, 2.6, 3.6], [-30.3, 3.6, 3.9], "fer");
  const tour = (x, z) => {
    const y0 = hauteur(x, z);
    for (const [dx, dz] of [[0, 0], [2, 0], [0, 2], [2, 2]]) bloc(m, x + dx, y0, z + dz, x + dx, y0 + 5, z + dz, "fer");
    bloc(m, x - 1, y0 + 6, z - 1, x + 3, y0 + 6, z + 3, "boisFonce");
    m.set(x + 1, y0 + 7, z + 1, "phare");
    lampe(m, x + 1.5, y0 + 7.5, z + 1.5, { r: 4, c: [1, 1, 0.8], k: 0.6, couleur: [255, 250, 210] });
    return y0 + 7;
  };
  const yA = tour(17, 11), yB = tour(-23, 7);
  // le chemin de la plage à la porte
  for (let k = 0; k <= 40; k++) {
    const t = k / 40, x = Math.round(5 + (12 - 5) * t + 3 * Math.sin(t * 5)), z = Math.round(21 + (14 - 21) * t);
    for (const dx of [0, 1]) { const h = hauteur(x + dx, z); if (h >= 0 && h <= 2) m.set(x + dx, h - 1, z, "chemin"); }
  }
  for (let n = 0; n < 34; n++) {
    const a = hache(n, 1, 9) * Math.PI * 2, r = 12 + hache(n, 2, 9) * 10, x = Math.round(r * Math.cos(a)), z = Math.round(r * Math.sin(a) / 1.1);
    const h = hauteur(x, z);
    if (h < 1 || h > 2 || dV(x, z) < 18 || m.plein(x, h, z) || m.get(x, h - 1, z) === "chemin") continue;
    if (hache(n, 3, 9) < 0.6) palmier(m, x, h, z, 5 + (n % 3)); else arbre(m, x, h, z, 4, "feuillesFoncees", 2.2);
  }

  // ---------------------------------------------------------------- les gens
  const E = (o) => garde(m, o);
  E({ skin: "espionneVerte", x: 5.5, y: 1, z: 30, lacet: 180, pose: "repos" });
  E({ skin: "espionneRouge", x: 4.5, y: 1, z: 28, lacet: 170, pose: "repos" });
  E({ skin: "espionneJaune", x: 6.5, y: 1, z: 27, lacet: 200, pose: "repos" });
  // les projecteurs balaient la plage (le faisceau, peint au sol)
  E({ skin: "sbire", x: 18.5, y: yA, z: 12.5, lacet: 200, pose: "porte", arme: "fusil_laser" });
  E({ skin: "sbire", x: -22, y: yB, z: 8.5, lacet: 150, pose: "porte", arme: "fusil_laser" });
  garde(m, { fantome: true, x: 18, y: 0, z: 14, lacet: 25, vue: { portee: 13, angle: 14, couleur: [255, 250, 200], force: 0.6 } });
  garde(m, { fantome: true, x: -23, y: 0, z: 11, lacet: -30, vue: { portee: 13, angle: 14, couleur: [255, 250, 200], force: 0.6 } });
  // sur le chemin, à la porte
  E({ skin: "sbire", x: 9, y: 2, z: 18, lacet: 160, pose: "porte", arme: "fusil_laser", vue: { portee: 9, angle: 45 }, ronde: [[9, 18], [7, 23], [11, 15]], rondeFermee: false });
  E({ skin: "sbire", x: 12, y: 2, z: 15.5, lacet: 135, pose: "croise" });
  // dans la base
  E({ skin: "toi", x: 1.5, y: 4, z: 2.5, lacet: 180, pose: "croise" });
  E({ skin: "sbire", x: -1.5, y: 2, z: 0, lacet: 180, pose: "repos" });
  E({ skin: "sbire", x: 3, y: 2, z: 3.5, lacet: 45, pose: "porte", arme: "fusil_laser", vue: { portee: 7, angle: 45 } });
  E({ skin: "sbire", x: -4.5, y: 5, z: -4.5, lacet: 45, pose: "porte", arme: "pistolet_laser" });
  E({ skin: "espionneRouge", x: -13, y: 2, z: -0.5, lacet: 90, pose: "croise" });
  E({ skin: "sbire", x: -7, y: 2, z: 0.5, lacet: -90, pose: "porte", arme: "fusil_laser" });

  m.etiquettes = [
    [6, 2.6, 30, "ARRIVEE DU TOBOGGAN", 40],
    [18, yA + 1, 12, "LES PROJECTEURS", -36],
    [11.5, 6, 14, "LA PORTE DE LA BASE", -40],
    [0.5, 6.5, -2.5, "LA SALLE DE CONTROLE (TOI)", -40],
    [VX, 26, VZ, "LA FUSEE : SABOTE LES 3 RESERVOIRS", -36],
    [-12.5, 5.5, -0.5, "LES CELLULES", -36],
    [-30, 3.6, 4, "SORTIE : LE SOUS-MARIN", -36],
  ];
  return m;
}
