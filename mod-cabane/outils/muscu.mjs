// La salle de muscu : les bâtis 3D des machines noires et rouges (le reste, barres, poignées, pédales, plaques qui montent,
// est dessiné par client/muscu/RenduMachine), les haltères de 5 à 30 kg, les noms. Voir muscu/Muscu.java et docs/muscu.
// Les coordonnées sont en blocs dans le repère de la machine : le joueur dessus regarde +Z, sa gauche est +X, le bloc
// principal est centré sur l'origine. Appelé par generer.mjs.

const T = {
  noir: "minecraft:block/black_concrete", noir2: "minecraft:block/polished_blackstone", rouge: "minecraft:block/red_concrete",
  chrome: "minecraft:block/iron_block", gris: "minecraft:block/gray_concrete", cuir: "minecraft:block/black_wool",
  ecran: "minecraft:block/redstone_lamp_on", tapis: "minecraft:block/smooth_basalt",
};

const px = (x) => Math.max(-16, Math.min(32, +(8 + x * 16).toFixed(3)));
const py = (y) => Math.max(-16, Math.min(32, +(y * 16).toFixed(3)));
const pz = px;
const faces = (t) => Object.fromEntries(["north", "south", "east", "west", "up", "down"].map((f) => [f, { uv: [4, 4, 12, 12], texture: `#${t}` }]));

/** Une boîte du bâti, en blocs (repère de la machine). */
function b(x0, y0, z0, x1, y1, z1, t, rotation) {
  const e = { from: [px(Math.min(x0, x1)), py(Math.min(y0, y1)), pz(Math.min(z0, z1))], to: [px(Math.max(x0, x1)), py(Math.max(y0, y1)), pz(Math.max(z0, z1))], faces: faces(t) };
  if (rotation) e.rotation = rotation;
  return e;
}
/** La même boîte à gauche et à droite. */
const bx = (x0, y0, z0, x1, y1, z1, t) => [b(x0, y0, z0, x1, y1, z1, t), b(-x1, y0, z0, -x0, y1, z1, t)];
/** Le bas d'une pile de plaques (les plaques du haut, qui montent, sont dessinées à part). */
const pile = (hx, z0, z1, y0, n) => Array.from({ length: n }, (_, i) => b(-hx, y0 + i * 0.075, z0, hx, y0 + i * 0.075 + 0.068, z1, i % 2 ? "gris" : "noir2"));
/** Une boîte tournée autour de l'axe X (pupitre, dossier). */
const penche = (x0, y0, z0, x1, y1, z1, t, angle, oy, oz) => b(x0, y0, z0, x1, y1, z1, t, { origin: [8, py(oy), pz(oz)], axis: "x", angle });

/** Un haltère couché sur le rack (le manche le long de Z). */
function haltereRack(x, kg) {
  const r = 0.042 + kg * 0.0021, lt = 0.035 + kg * 0.0028, y = 0.5 + r;
  return [b(x - 0.02, y - 0.02, -0.1, x + 0.02, y + 0.02, 0.1, "chrome"),
    b(x - r, y - r, 0.1, x + r, y + r, 0.1 + lt, "noir"), b(x - r, y - r, -0.1 - lt, x + r, y + r, -0.1, "noir"),
    b(x - r * 1.05, y - r * 1.05, 0.1, x + r * 1.05, y + r * 1.05, 0.112, "rouge"), b(x - r * 1.05, y - r * 1.05, -0.112, x + r * 1.05, y + r * 1.05, -0.1, "rouge")];
}

export const KILOS = [5, 10, 15, 20, 25, 30];

const MACHINES = {
  tapis_course: ["Tapis de course", [
    b(-0.5, 0, -1.5, 0.5, 0.22, 1.15, "noir"), ...bx(0.5, 0.06, -1.5, 0.51, 0.16, 1.15, "rouge"),
    b(-0.42, 0.22, -1.42, 0.42, 0.28, 1.1, "tapis"),
    b(-0.5, 0, 1.1, 0.5, 0.42, 1.5, "noir2"), ...bx(0.42, 0.3, 1.25, 0.5, 1.45, 1.35, "noir"),
    b(-0.5, 1.4, 1.12, 0.5, 1.68, 1.45, "noir"), b(-0.32, 1.46, 1.11, 0.32, 1.62, 1.12, "ecran"),
    ...bx(0.44, 1.12, 0.55, 0.5, 1.18, 1.3, "chrome")]],
  velo_elliptique: ["Vélo elliptique", [
    b(-0.12, 0, -0.5, 0.12, 0.14, 1.45, "noir"), b(-0.5, 0, -0.5, 0.5, 0.08, -0.35, "noir"), b(-0.45, 0, 1.3, 0.45, 0.08, 1.45, "noir"),
    b(-0.2, 0.08, -0.5, 0.2, 0.7, 0, "noir2"),
    b(-0.1, 0.08, 1.22, 0.1, 1.38, 1.42, "noir"), b(-0.32, 0.27, 1.25, 0.32, 0.33, 1.31, "chrome"),
    b(-0.3, 1.32, 1.2, 0.3, 1.52, 1.45, "noir"), b(-0.2, 1.37, 1.195, 0.2, 1.48, 1.2, "ecran")]],
  velo_appartement: ["Vélo d'appartement", [
    b(-0.45, 0, -0.45, 0.45, 0.08, -0.3, "noir"), b(-0.45, 0, 1.25, 0.45, 0.08, 1.4, "noir"), b(-0.08, 0, -0.4, 0.08, 0.12, 1.35, "noir"),
    b(-0.06, 0.1, -0.12, 0.06, 0.88, 0, "noir"), b(-0.17, 0.88, -0.25, 0.17, 0.98, 0.15, "cuir"), ...bx(0.17, 0.9, -0.24, 0.175, 0.95, 0.14, "rouge"),
    b(-0.07, 0.1, 1.3, 0.07, 1.15, 1.42, "noir"), ...bx(0.05, 0.4, 1.0, 0.08, 0.5, 1.36, "noir"),
    b(-0.32, 1.12, 1.15, 0.32, 1.2, 1.33, "noir"), ...bx(0.24, 1.11, 1.14, 0.33, 1.21, 1.34, "cuir"),
    b(-0.15, 1.2, 1.2, 0.15, 1.36, 1.35, "noir"), b(-0.11, 1.24, 1.195, 0.11, 1.32, 1.2, "ecran")]],
  rameur: ["Rameur", [
    b(-0.1, 0.18, -1.45, 0.1, 0.24, 1.0, "noir"), b(-0.3, 0, -1.5, 0.3, 0.18, -1.35, "noir"),
    b(-0.32, 0, 0.95, 0.32, 0.1, 1.5, "noir2"), b(-0.12, 0.1, 0.88, 0.12, 0.32, 1.0, "noir"),
    ...bx(0.05, 0.14, 0.72, 0.3, 0.5, 0.8, "noir"), ...bx(0.05, 0.3, 0.7, 0.3, 0.35, 0.72, "rouge"),
    b(-0.03, 0.32, 0.95, 0.03, 0.95, 1.0, "noir"), b(-0.15, 0.95, 0.84, 0.15, 1.15, 0.96, "noir"), b(-0.11, 0.99, 0.835, 0.11, 1.11, 0.84, "ecran")]],
  presse_cuisses: ["Presse à cuisses", [
    b(-0.55, 0, -1.5, 0.55, 0.1, 1.5, "noir"),
    // le dossier et l'assise, penchés vers l'arrière autour de la hanche
    penche(-0.3, 0.45, -0.84, 0.3, 1.45, -0.71, "cuir", -45, 0.5, -0.55), penche(-0.3, 0.28, -0.83, 0.3, 0.4, -0.25, "cuir", -45, 0.5, -0.55),
    penche(0.3, 0.5, -0.83, 0.31, 1.4, -0.72, "rouge", -45, 0.5, -0.55), penche(-0.31, 0.5, -0.83, -0.3, 1.4, -0.72, "rouge", -45, 0.5, -0.55),
    b(-0.08, 0.1, -0.95, 0.08, 0.45, -0.65, "noir"), b(-0.08, 0.1, -1.45, 0.08, 0.8, -1.25, "noir"),
    ...bx(0.33, 0.42, -0.85, 0.37, 0.47, -0.45, "chrome")]],
  tirage_vertical: ["Tirage vertical", [
    b(-0.5, 0, -0.5, 0.5, 0.08, 1.5, "noir"), ...bx(0.33, 0.08, 1.3, 0.45, 2.0, 1.48, "noir"),
    b(-0.45, 1.88, 0.38, 0.45, 2.0, 1.48, "noir"), ...bx(0.45, 1.91, 0.4, 0.46, 1.97, 1.46, "rouge"),
    b(-0.03, 1.82, 0.4, 0.03, 1.9, 0.5, "gris"), b(-0.03, 1.82, 1.34, 0.03, 1.9, 1.44, "gris"), b(-0.01, 1.86, 0.45, 0.01, 1.88, 1.39, "gris"),
    ...bx(0.2, 0.08, 1.36, 0.23, 1.8, 1.39, "chrome"), ...pile(0.28, 1.32, 1.45, 0.1, 9),
    b(-0.06, 0.08, 0.07, 0.06, 0.5, 0.17, "noir"), b(-0.26, 0.5, -0.12, 0.26, 0.6, 0.34, "cuir"), ...bx(0.26, 0.52, -0.1, 0.265, 0.57, 0.32, "rouge"),
    b(-0.06, 0.08, 0.42, 0.06, 0.8, 0.52, "noir"), b(-0.31, 0.84, 0.38, 0.31, 0.98, 0.52, "rouge")]],
  tirage_horizontal: ["Tirage horizontal", [
    b(-0.18, 0, -1.5, 0.18, 0.3, 1.0, "noir"), b(-0.25, 0.3, -1.05, 0.25, 0.42, -0.25, "cuir"), ...bx(0.25, 0.32, -1.04, 0.255, 0.4, -0.26, "rouge"),
    b(-0.36, 0.1, 0.12, 0.36, 0.62, 0.2, "noir"), b(-0.36, 0.3, 0.11, 0.36, 0.34, 0.12, "rouge"),
    ...bx(0.33, 0, 1.05, 0.45, 2.0, 1.5, "noir"), b(-0.45, 1.9, 1.05, 0.45, 2.0, 1.5, "noir"), ...bx(0.45, 1.93, 1.07, 0.46, 1.98, 1.48, "rouge"),
    ...pile(0.28, 1.2, 1.36, 0.05, 8),
    b(-0.03, 0.55, 0.95, 0.03, 0.65, 1.05, "gris"), b(-0.03, 1.8, 1.23, 0.03, 1.9, 1.33, "gris"), b(-0.01, 0.59, 1.0, 0.01, 0.61, 1.28, "gris")]],
  developpe_couche: ["Développé couché", [
    b(-0.2, 0.36, -0.98, 0.2, 0.46, 0.45, "cuir"), ...bx(0.2, 0.38, -0.97, 0.205, 0.44, 0.44, "rouge"),
    b(-0.24, 0, -0.9, 0.24, 0.36, -0.8, "noir"), b(-0.24, 0, 0.3, 0.24, 0.36, 0.4, "noir"), b(-0.06, 0.14, -0.85, 0.06, 0.28, 0.35, "noir"),
    b(-0.72, 0, -1.3, 0.72, 0.08, -1.02, "noir"), ...bx(0.55, 0.08, -1.2, 0.65, 1.42, -1.1, "noir"),
    ...bx(0.55, 1.12, -1.1, 0.65, 1.18, -0.98, "rouge"), ...bx(0.55, 1.18, -1.0, 0.65, 1.26, -0.98, "rouge"),
    ...bx(0.65, 0.41, -1.17, 0.85, 0.47, -1.13, "chrome"), ...bx(0.76, 0.23, -1.37, 0.81, 0.67, -0.93, "rouge"), ...bx(0.69, 0.29, -1.31, 0.74, 0.61, -0.99, "noir")]],
  machine_biceps: ["Machine à biceps", [
    b(-0.45, 0, -0.45, 0.45, 0.08, 1.4, "noir"),
    b(-0.06, 0.08, -0.06, 0.06, 0.55, 0.04, "noir"), b(-0.25, 0.55, -0.26, 0.25, 0.65, 0.2, "cuir"), ...bx(0.25, 0.57, -0.25, 0.255, 0.62, 0.19, "rouge"),
    b(-0.07, 0.08, 0.5, 0.07, 0.95, 0.62, "noir"),
    // le pupitre penché, où l'on pose les bras
    penche(-0.38, 0.97, 0.22, 0.38, 1.07, 0.66, "cuir", 45, 1.02, 0.44), penche(-0.39, 0.95, 0.21, 0.39, 0.97, 0.67, "rouge", 45, 1.02, 0.44),
    ...bx(0.3, 0.08, 1.1, 0.42, 1.4, 1.36, "noir"), b(-0.42, 1.35, 1.1, 0.42, 1.45, 1.36, "noir"), ...pile(0.25, 1.16, 1.3, 0.1, 7)]],
  rack_halteres: ["Rack d'haltères", [
    ...[-1.5, -0.05, 1.4].map((x) => b(x, 0, -0.3, x + 0.1, 0.95, 0.3, "noir")),
    b(-1.5, 0.44, -0.3, 1.5, 0.5, 0.3, "noir"), b(-1.5, 0.36, -0.32, 1.5, 0.5, -0.28, "rouge"),
    b(-1.5, 0.9, -0.05, 1.5, 0.95, 0.05, "noir"), b(-1.5, 0.95, -0.02, 1.5, 0.97, 0.02, "rouge"),
    ...KILOS.flatMap((kg, i) => [...haltereRack(1.25 - i * 0.5 + 0.11, kg), ...haltereRack(1.25 - i * 0.5 - 0.11, kg)])]],
};

/** Un haltère en main (le manche le long de X), plus gros quand il est plus lourd. */
function haltereObjet(kg) {
  const h = 1.6 + kg * 0.07, l = 1.2 + kg * 0.06;
  const e = (de, a, t) => ({ from: de, to: a, faces: faces(t) });
  return [e([3, 7.3, 7.3], [13, 8.7, 8.7], "chrome"),
    e([3 - l, 8 - h, 8 - h], [3, 8 + h, 8 + h], "noir"), e([13, 8 - h, 8 - h], [13 + l, 8 + h, 8 + h], "noir"),
    e([2.7, 8 - h * 1.05, 8 - h * 1.05], [3, 8 + h * 1.05, 8 + h * 1.05], "rouge"), e([13, 8 - h * 1.05, 8 - h * 1.05], [13.3, 8 + h * 1.05, 8 + h * 1.05], "rouge")];
}

export function genererMuscu({ ecrire, lang }) {
  for (const [id, [nom, elements]] of Object.entries(MACHINES)) {
    ecrire(`assets/cabane/models/block/${id}.json`, { parent: "minecraft:block/block", textures: { ...T, particle: T.noir }, elements });
    ecrire(`assets/cabane/models/item/${id}.json`, { parent: `cabane:block/${id}`, display: {
      gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.3, 0.3, 0.3] },
      ground: { translation: [0, 3, 0], scale: [0.2, 0.2, 0.2] },
      firstperson_righthand: { rotation: [0, 45, 0], scale: [0.25, 0.25, 0.25] }, thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.2, 0.2, 0.2] },
      fixed: { scale: [0.3, 0.3, 0.3] } } });
    const variantes = {};
    for (const [f, y] of [["north", 0], ["east", 90], ["south", 180], ["west", 270]]) variantes[`facing=${f}`] = { model: `cabane:block/${id}`, ...(y ? { y } : {}) };
    ecrire(`assets/cabane/blockstates/${id}.json`, { variants: variantes });
    ecrire(`data/cabane/loot_table/blocks/${id}.json`, { type: "minecraft:block", pools: [{ rolls: 1, entries: [{ type: "minecraft:item", name: `cabane:${id}` }] }] });
    lang[`block.cabane.${id}`] = nom;
  }
  for (const kg of KILOS) {
    ecrire(`assets/cabane/models/item/haltere_${kg}.json`, { parent: "minecraft:block/block", textures: { ...T, particle: T.noir }, elements: haltereObjet(kg),
      display: {
        gui: { rotation: [30, 45, 0], scale: [0.85, 0.85, 0.85] },
        ground: { translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
        fixed: { scale: [0.8, 0.8, 0.8] },
        thirdperson_righthand: { rotation: [0, 90, 0], translation: [0, 1, 1.5], scale: [0.6, 0.6, 0.6] },
        thirdperson_lefthand: { rotation: [0, 90, 0], translation: [0, 1, 1.5], scale: [0.6, 0.6, 0.6] },
        firstperson_righthand: { rotation: [0, 70, 0], translation: [1, 2, 0], scale: [0.55, 0.55, 0.55] },
        firstperson_lefthand: { rotation: [0, 70, 0], translation: [1, 2, 0], scale: [0.55, 0.55, 0.55] },
      } });
    lang[`item.cabane.haltere_${kg}`] = `Haltère ${kg} kg`;
  }
  lang["itemGroup.cabane_muscu"] = "Salle de muscu";
  lang["entity.cabane.siege_muscu"] = "Machine de muscu";
}
