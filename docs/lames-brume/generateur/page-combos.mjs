// La page 3D des combos des lames (three.js) : le ninja de la Brume avec sa peau, les bras et les jambes qui plient
// (coudes, genoux, buste), la lame tenue dans la main (elle suit tout le geste), la traînée qui suit sa pointe.
// node page-combos.mjs → ../apercu/combos.html (publiée comme Artifact)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { KUBI, KIBA, NUI, SHIB, HACHE, MARTEAU, HIRA } from "./rendu.mjs";
import { voxels } from "./samehada3d.mjs";
import { facesPixels, facesVoxels } from "./jeu.mjs";
import { COMBOS, TENUE, TRAINEES, SPECIAUX } from "./combos.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const r2 = (v) => Math.round(v * 100) / 100;
/** Les faces d'une lame, en une liste plate : 12 coordonnées puis la couleur (un entier), par face. */
const plat = (faces) => faces.flatMap((f) => [...f.coins.flat().map(r2), (f.c[0] << 16) | (f.c[1] << 8) | f.c[2]]);
const LAMES = {
  kubikiribocho: plat(facesPixels(KUBI)), samehada: plat(facesVoxels(voxels("normale"), 0.5, 7)), kiba: plat(facesPixels(KIBA)),
  nuibari: plat(facesPixels(NUI)), shibuki: plat(facesPixels(SHIB)), kabutowari: plat(facesPixels(HACHE)), marteau: plat(facesPixels(MARTEAU)),
  hiramekarei: plat(facesPixels(HIRA)), hiramekarei_chakra: plat(facesPixels(HIRA, "chakra")),
  samehada_pleine: plat(facesVoxels(voxels("pleine"), 0.5, 7)), samehada_ouverte: plat(facesVoxels(voxels("ouverte"), 0.5, 7)),
};
const PEAU = "data:image/png;base64," + fs.readFileSync(path.join(ICI, "..", "..", "missions", "skins", "ninjaBrume.png")).toString("base64");
const NOMS = { kubikiribocho: "Kubikiribôchô", samehada: "Samehada", kiba: "Kiba", nuibari: "Nuibari", shibuki: "Shibuki", kabutowari: "Kabutowari", hiramekarei: "Hiramekarei" };

const html = `<title>Combos des lames</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@500;700&family=Inter:wght@400;500&display=swap">
<style>
/* Une seule ambiance : la brume de Kiri au crépuscule. La scène prend tout l'écran ; les commandes flottent en bas. */
:root {
  --fond: #141923; --panneau: rgba(26, 32, 44, 0.86); --texte: #e8ebf3; --doux: #9aa3b8; --accent: #7fd3ff; --trait: #36415a; --bouton: #2a3346;
  --titre: "Space Grotesk", "Segoe UI", system-ui, sans-serif; --corps: "Inter", "Segoe UI", system-ui, sans-serif;
  color-scheme: dark;
}
html, body { height: 100%; }
body { margin: 0; background: var(--fond); color: var(--texte); font-family: var(--corps); overflow: hidden; }
#scene { position: fixed; inset: 0; }
canvas { display: block; touch-action: none; cursor: grab; }
canvas:active { cursor: grabbing; }
header { position: fixed; top: 0; left: 0; right: 0; pointer-events: none; padding-top: calc(14px + env(safe-area-inset-top, 0px));
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }
h1 { font-family: var(--titre); font-size: 1.45rem; margin: 0; }
#coup { margin: 4px 0 0; color: var(--accent); font: 500 1rem var(--titre); min-height: 1.3em; }
.bas { position: fixed; left: 0; right: 0; bottom: 0; display: flex; flex-direction: column; gap: 8px; align-items: center;
  padding-bottom: calc(14px + env(safe-area-inset-bottom, 0px)); padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }
.groupe { display: flex; flex-wrap: wrap; justify-content: center; gap: 4px; background: var(--panneau); border: 1px solid var(--trait); border-radius: 10px; padding: 4px; max-width: 100%; }
button { font: 500 0.88rem var(--corps); color: var(--texte); background: transparent; border: 0; border-radius: 7px; padding: 7px 11px; cursor: pointer; }
button:hover { background: var(--bouton); }
button[aria-pressed="true"] { background: var(--accent); color: #0c1420; }
button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.aide { position: fixed; top: calc(16px + env(safe-area-inset-top, 0px)); right: max(16px, env(safe-area-inset-right, 0px)); color: var(--doux); font-size: 0.78rem; text-align: right; pointer-events: none; }
@media (max-width: 600px) { .aide { display: none; } }
.note { position: fixed; left: 50%; top: calc(86px + env(safe-area-inset-top, 0px)); transform: translateX(-50%); background: var(--panneau); border: 1px solid var(--trait); border-radius: 8px; padding: 6px 12px; font-size: 0.9rem; color: var(--texte); pointer-events: none; }
</style>

<div id="scene"></div>
<header><h1 id="titre">Kubikiribôchô</h1><p id="coup"></p></header>
<div id="note" class="note" hidden></div>
<div class="aide">Glisser pour tourner autour<br>Molette ou pincer pour zoomer</div>
<div class="bas">
  <div class="groupe" id="lames" role="group" aria-label="Lame"></div>
  <div class="groupe" role="group" aria-label="Mode">
    <button id="mc" aria-pressed="true">Combo (clic gauche)</button><button id="ms" aria-pressed="false">Coup spécial (clic droit)</button>
  </div>
  <div class="groupe" role="group" aria-label="Lecture">
    <button id="v1" aria-pressed="true">Vitesse réelle</button><button id="v2" aria-pressed="false">Ralenti</button><button id="v4" aria-pressed="false">Très lent</button>
    <button id="tr" aria-pressed="true">Traînée</button>
  </div>
  <div class="groupe" role="group" aria-label="Vue">
    <button id="vf">Face</button><button id="vp">Profil</button><button id="vt">Trois quarts</button><button id="vd">Dos</button>
  </div>
</div>

<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
<script>
const LAMES = ${JSON.stringify(LAMES)};
const COMBOS = ${JSON.stringify(COMBOS)};
const TENUE = ${JSON.stringify(TENUE)};
const TRAINEES = ${JSON.stringify(TRAINEES)};
const SPECIAUX = ${JSON.stringify(SPECIAUX)};
const NOMS = ${JSON.stringify(NOMS)};

// ------------------------------------------------------------------ la scène
const conteneur = document.getElementById("scene");
const rendu = new THREE.WebGLRenderer({ antialias: true });
rendu.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
rendu.shadowMap.enabled = true;
conteneur.appendChild(rendu.domElement);
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x1b2230);
scene.fog = new THREE.Fog(0x1b2230, 9, 22);
const camera = new THREE.PerspectiveCamera(40, 1, 0.05, 60);
scene.add(new THREE.HemisphereLight(0xd8e2ff, 0x30283a, 0.7));
const soleil = new THREE.DirectionalLight(0xfff2e0, 0.85);
soleil.position.set(-3, 6, 4);
soleil.castShadow = true;
soleil.shadow.mapSize.set(1024, 1024);
Object.assign(soleil.shadow.camera, { left: -4, right: 4, top: 4, bottom: -4 });
scene.add(soleil);
// l'herbe, en damier de blocs
const sol = new THREE.Group();
for (let x = -8; x < 8; x++) for (let z = -8; z < 8; z++) {
  const m = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshLambertMaterial({ color: (x + z) & 1 ? 0x4a7a31 : 0x558838 }));
  m.rotation.x = -Math.PI / 2; m.position.set(x + 0.5, 0, z + 0.5); m.receiveShadow = true; sol.add(m);
}
scene.add(sol);

// ------------------------------------------------------------------ le joueur : la peau sur des boîtes, bras et jambes en deux morceaux
const image = new Image();
const peau = new THREE.Texture(image);
peau.magFilter = THREE.NearestFilter; peau.minFilter = THREE.NearestFilter;
image.onload = () => { peau.needsUpdate = true; };
image.src = "${PEAU}";
const matPeau = new THREE.MeshLambertMaterial({ map: peau, alphaTest: 0.5, side: THREE.DoubleSide });

/**
 * Une boîte du modèle de Minecraft (repère : y vers le haut, l'avant vers +z, la gauche vers +x), dépliée sur la peau
 * comme dans le jeu. morceau : 'tout', 'haut' ou 'bas' (la moitié d'un membre : les côtés prennent la moitié de la peau).
 */
function boite(x0, y0, z0, x1, y1, z1, u, v, gonfle, morceau) {
  const w = x1 - x0, h = 12, d = z1 - z0, H = y1 - y0;
  const hauteurPeau = morceau === "tout" ? H : 6, decal = morceau === "bas" ? 6 : 0;
  x0 -= gonfle; y0 -= gonfle; z0 -= gonfle; x1 += gonfle; y1 += gonfle; z1 += gonfle;
  const faces = [
    { O: [x0, y1, z1], U: [x1 - x0, 0, 0], V: [0, y0 - y1, 0], r: [u + d, v + d + decal, w, hauteurPeau] },
    { O: [x1, y1, z0], U: [x0 - x1, 0, 0], V: [0, y0 - y1, 0], r: [u + d + w + d, v + d + decal, w, hauteurPeau] },
    { O: [x0, y1, z0], U: [0, 0, z1 - z0], V: [0, y0 - y1, 0], r: [u, v + d + decal, d, hauteurPeau] },
    { O: [x1, y1, z1], U: [0, 0, z0 - z1], V: [0, y0 - y1, 0], r: [u + d + w, v + d + decal, d, hauteurPeau] },
  ];
  if (morceau !== "bas") faces.push({ O: [x0, y1, z0], U: [x1 - x0, 0, 0], V: [0, 0, z1 - z0], r: [u + d, v, w, d] });
  if (morceau !== "haut") faces.push({ O: [x0, y0, z1], U: [x1 - x0, 0, 0], V: [0, 0, z0 - z1], r: [u + d + w, v, w, d] });
  const pos = [], uv = [], idx = [];
  for (const f of faces) {
    const b = pos.length / 3;
    for (const [a, c] of [[0, 0], [1, 0], [1, 1], [0, 1]]) {
      pos.push(f.O[0] + f.U[0] * a + f.V[0] * c, f.O[1] + f.U[1] * a + f.V[1] * c, f.O[2] + f.U[2] * a + f.V[2] * c);
      uv.push((f.r[0] + f.r[2] * a) / 64, 1 - (f.r[1] + f.r[3] * c) / 64);
    }
    idx.push(b, b + 1, b + 2, b, b + 2, b + 3);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute("uv", new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  g.computeVertexNormals();
  const m = new THREE.Mesh(g, matPeau);
  m.castShadow = true;
  return m;
}
/** Une partie, avec la deuxième couche de la peau (veste, manches) un peu gonflée. */
function partie(groupe, boiteXYZ, uv1, uv2, gonfle, morceau) {
  groupe.add(boite(...boiteXYZ, uv1[0], uv1[1], 0, morceau));
  groupe.add(boite(...boiteXYZ, uv2[0], uv2[1], gonfle, morceau));
}
const pivot = (parent, x, y, z) => { const g = new THREE.Group(); g.position.set(x, y, z); parent.add(g); return g; };

const racine = new THREE.Group();
racine.scale.setScalar(1 / 16);
scene.add(racine);
const corps = pivot(racine, 0, 0, 0);
// les jambes : la cuisse, puis le genou
const J = {};
for (const [cote, x, uv1, uv2] of [["d", -2, [0, 16], [0, 32]], ["g", 2, [16, 48], [0, 48]]]) {
  const hanche = pivot(corps, x, 12, 0);
  partie(hanche, [x < 0 ? -2 : -2, -6, -2, 2, 0, 2].map((v, i) => i === 0 || i === 3 ? v : v), uv1, uv2, 0.25, "haut");
  const genou = pivot(hanche, 0, -6, 0);
  partie(genou, [-2, -6, -2, 2, 0, 2], uv1, uv2, 0.25, "bas");
  J[cote] = { hanche, genou };
}
// le buste : le bas, puis le pli à la taille, le haut, la tête et les bras
partie(pivot(corps, 0, 12, 0), [-4, 0, -2, 4, 6, 2], [16, 16], [16, 32], 0.25, "bas");
const pli = pivot(corps, 0, 18, 0);
partie(pli, [-4, 0, -2, 4, 6, 2], [16, 16], [16, 32], 0.25, "haut");
const tete = pivot(pli, 0, 6, 0);
partie(tete, [-4, 0, -4, 4, 8, 4], [0, 0], [32, 0], 0.5, "tout");
const B = {};
for (const [cote, x, uv1, uv2] of [["d", -5, [40, 16], [40, 32]], ["g", 5, [32, 48], [48, 48]]]) {
  const epaule = pivot(pli, x, 4, 0);
  const cx = x < 0 ? -1 : 1;
  partie(epaule, [cx - 2, -4, -2, cx + 2, 2, 2], uv1, uv2, 0.25, "haut");
  const coude = pivot(epaule, cx, -4, 0);
  partie(coude, [-2, -6, -2, 2, 0, 2], uv1, uv2, 0.25, "bas");
  const main = pivot(coude, 0, -5, 0);
  B[cote] = { epaule, coude, main };
}

// ------------------------------------------------------------------ les lames
function maillage(liste) {
  const pos = [], col = [], c = new THREE.Color();
  for (let i = 0; i < liste.length; i += 13) {
    const q = liste.slice(i, i + 12);
    c.setHex(liste[i + 12]);
    for (const k of [0, 1, 2, 0, 2, 3]) { pos.push(q[k * 3], q[k * 3 + 1], q[k * 3 + 2]); col.push(c.r, c.g, c.b); }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute("color", new THREE.Float32BufferAttribute(col, 3));
  g.computeVertexNormals();
  g.computeBoundingBox();
  const m = new THREE.Mesh(g, new THREE.MeshLambertMaterial({ vertexColors: true, side: THREE.DoubleSide }));
  m.castShadow = true;
  return m;
}
const MODELES = Object.fromEntries(Object.entries(LAMES).map(([k, v]) => [k, maillage(v)]));
/** La lame dans une main : la prise dans le poing ; x = sa largeur, y = vers la pointe, z = son épaisseur. */
const tenues = { d: new THREE.Group(), g: new THREE.Group() };
B.d.main.add(tenues.d); B.g.main.add(tenues.g);
const V = (x, y, z) => new THREE.Vector3(x, y, z);
function orienter(g, poignet, roulis, hache) {
  const L = V(0, 0.25, 1).lerp(V(0, -1, 0.08), poignet).normalize();
  let W = V(0, 1, -0.25).lerp(V(0, 0.08, 1), poignet);
  if (hache) W.multiplyScalar(-1);
  W.sub(L.clone().multiplyScalar(W.dot(L))).normalize().applyAxisAngle(L, roulis);
  const N = new THREE.Vector3().crossVectors(W, L);
  g.matrix.makeBasis(W, L, N);
  g.matrixAutoUpdate = false;
}

// ------------------------------------------------------------------ la cible
const cible = new THREE.Group();
for (const [x, y, z, w, h, d, c] of [[0, 28, 0, 8, 8, 8, 0x8a8f99], [0, 18, 0, 8, 12, 4, 0x6c7280], [-6, 18, 0, 4, 12, 4, 0x7a808c], [6, 18, 0, 4, 12, 4, 0x7a808c], [-2, 6, 0, 4, 12, 4, 0x555a66], [2, 6, 0, 4, 12, 4, 0x555a66]]) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), new THREE.MeshLambertMaterial({ color: c }));
  m.position.set(x, y, z); m.castShadow = true; cible.add(m);
}
cible.scale.setScalar(1 / 16);
cible.position.set(0, 0, 2.6);
cible.rotation.y = Math.PI;
scene.add(cible);

// ------------------------------------------------------------------ la traînée : un ruban qui suit la pointe
const ruban = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ vertexColors: true, transparent: true, side: THREE.DoubleSide,
  blending: THREE.AdditiveBlending, depthWrite: false }));
ruban.frustumCulled = false;
scene.add(ruban);

// ------------------------------------------------------------------ le jeu des poses
const EASE = { lin: (u) => u, sortie: (u) => 1 - (1 - u) ** 3, entree: (u) => u ** 3, doux: (u) => u * u * (3 - 2 * u) };
const CANAUX = ["cd", "cg", "jd", "jg", "gd", "gg", "penche", "tour", "haut", "avant", "poignet", "roulis"];
function pose(cles, t) {
  let i = 0;
  while (i < cles.length - 2 && t > cles[i + 1].t) i++;
  const a = cles[i], b = cles[i + 1], u = EASE[b.ease || "doux"](Math.min(1, Math.max(0, (t - a.t) / (b.t - a.t))));
  const r = {};
  for (const c of CANAUX) r[c] = (a[c] || 0) + ((b[c] || 0) - (a[c] || 0)) * u;
  for (const c of ["bd", "bg", "tete"]) {
    const x = a[c] || [0, 0, 0], y = b[c] || [0, 0, 0];
    r[c] = x.map((v, k) => v + ((y[k] || 0) - v) * u);
  }
  return r;
}
/** Une pose sur le joueur. Les angles des bras sont ceux du modèle de Minecraft (y vers le bas) : y et z changent de signe. */
function appliquer(p) {
  corps.position.set(0, p.haut, p.avant);
  corps.rotation.set(0, p.tour, 0);
  pli.rotation.set(-p.penche, 0, 0);
  tete.rotation.set(p.tete[0], -p.tete[1], 0);
  for (const [c, a, coude] of [["d", p.bd, p.cd], ["g", p.bg, p.cg]]) {
    B[c].epaule.rotation.set(a[0], -a[1], -a[2], "ZYX");
    B[c].coude.rotation.set(coude, 0, 0);
  }
  J.d.hanche.rotation.set(p.jd, 0, 0); J.g.hanche.rotation.set(p.jg, 0, 0);
  J.d.genou.rotation.set(p.gd, 0, 0); J.g.genou.rotation.set(p.gg, 0, 0);
}

let lame = "kubikiribocho", coupIdx = 0, tCoup = 0, vitesse = 1, trainee = true, pause = 0;
function choisirLame(id) {
  lame = id; coupIdx = 0; tCoup = 0; pause = 8;
  if (typeof cacherEffets === "function") cacherEffets();
  tenues.d.clear(); tenues.g.clear();
  tenues.d.add(MODELES[id]);
  if (TENUE[id].gauche) tenues.g.add(TENUE[id].gauche === "kiba" ? MODELES.kiba.clone() : MODELES.marteau);
  document.getElementById("titre").textContent = NOMS[id];
  for (const b of document.querySelectorAll("#lames button")) b.setAttribute("aria-pressed", String(b.dataset.id === id));
}
const lames = document.getElementById("lames");
for (const id of Object.keys(COMBOS)) {
  const b = document.createElement("button");
  b.textContent = NOMS[id]; b.dataset.id = id;
  b.addEventListener("click", () => choisirLame(id));
  lames.appendChild(b);
}
for (const [id, v] of [["v1", 1], ["v2", 0.4], ["v4", 0.15]]) {
  document.getElementById(id).addEventListener("click", () => {
    vitesse = v;
    for (const x of ["v1", "v2", "v4"]) document.getElementById(x).setAttribute("aria-pressed", String(x === id));
  });
}
document.getElementById("tr").addEventListener("click", (e) => { trainee = !trainee; e.currentTarget.setAttribute("aria-pressed", String(trainee)); });

// ------------------------------------------------------------------ la caméra
let lacet = 1.15, tangage = 0.2, distance = 5.6, recul = 0;
const cibleCam = new THREE.Vector3(0, 1.05, 1.1);
for (const [id, l] of [["vf", 0.35], ["vp", Math.PI / 2], ["vt", 1.15], ["vd", Math.PI - 0.4]]) document.getElementById(id).addEventListener("click", () => { lacet = l; });
const doigts = new Map();
let pince = 0;
rendu.domElement.addEventListener("pointerdown", (e) => { doigts.set(e.pointerId, [e.clientX, e.clientY]); rendu.domElement.setPointerCapture(e.pointerId); });
rendu.domElement.addEventListener("pointermove", (e) => {
  if (!doigts.has(e.pointerId)) return;
  const [x0, y0] = doigts.get(e.pointerId);
  if (doigts.size === 1) { lacet -= (e.clientX - x0) * 0.008; tangage = Math.max(-0.3, Math.min(1.3, tangage + (e.clientY - y0) * 0.006)); }
  doigts.set(e.pointerId, [e.clientX, e.clientY]);
  if (doigts.size === 2) { const [a, b] = [...doigts.values()], d = Math.hypot(a[0] - b[0], a[1] - b[1]); if (pince) distance = Math.max(2, Math.min(12, distance * (pince / d))); pince = d; }
});
const lacher = (e) => { doigts.delete(e.pointerId); pince = 0; };
rendu.domElement.addEventListener("pointerup", lacher);
rendu.domElement.addEventListener("pointercancel", lacher);
rendu.domElement.addEventListener("wheel", (e) => { e.preventDefault(); distance = Math.max(2, Math.min(12, distance * (1 + e.deltaY * 0.001))); }, { passive: false });

const taille = () => { const w = conteneur.clientWidth || innerWidth, h = conteneur.clientHeight || innerHeight; rendu.setSize(w, h); camera.aspect = w / h; camera.updateProjectionMatrix(); };
addEventListener("resize", taille);
taille();

// ------------------------------------------------------------------ les coups spéciaux : ce que chacun fait apparaître
let mode = "combo";
const TRACES_SPE = { chakra: [18, 24], seisme: [12, 18], explosions: [6, 11] };
const effets = new THREE.Group();
scene.add(effets);
const additif = (couleur, opacite = 1) => new THREE.MeshBasicMaterial({ color: couleur, transparent: true, opacity: opacite, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide });
const volante = new THREE.Group();
volante.scale.setScalar(1 / 16);
effets.add(volante);
const cibles = [cible, cible.clone(), cible.clone()];
cibles[1].position.set(0.25, 0, 4.6);
cibles[2].position.set(-0.2, 0, 6.6);
scene.add(cibles[1], cibles[2]);
const PLACES = cibles.map((c) => c.position.clone());
const fil = new THREE.Line(new THREE.BufferGeometry(), new THREE.LineBasicMaterial({ color: 0xf4f4ff }));
fil.frustumCulled = false;
effets.add(fil);
let eclairs = [];
/** Un éclair en zigzag de a à b : un cœur blanc et un halo bleu. */
function eclair(a, b, amp, rayon) {
  const pts = [a];
  for (let k = 1; k < 10; k++) {
    const t = k / 10, e = Math.sin(Math.PI * t) * amp;
    pts.push(a.clone().lerp(b, t).add(new THREE.Vector3((Math.random() - 0.5) * e, (Math.random() - 0.5) * e * 0.3, (Math.random() - 0.5) * e)));
  }
  pts.push(b);
  const courbe = new THREE.CatmullRomCurve3(pts, false, "catmullrom", 0.1), g = new THREE.Group();
  g.add(new THREE.Mesh(new THREE.TubeGeometry(courbe, 50, rayon, 6), additif(0xffffff)));
  g.add(new THREE.Mesh(new THREE.TubeGeometry(courbe, 50, rayon * 3, 6), additif(0x78c8ff, 0.45)));
  effets.add(g);
  eclairs.push(g);
}
const booms = [0, 1, 2].map((i) => {
  const g = new THREE.Group();
  g.add(new THREE.Mesh(new THREE.SphereGeometry(1, 16, 12), additif(0xff8a30)));
  g.add(new THREE.Mesh(new THREE.SphereGeometry(0.6, 12, 10), additif(0xfff0a0)));
  g.add(new THREE.Mesh(new THREE.SphereGeometry(1.3, 12, 10), new THREE.MeshLambertMaterial({ color: 0x5a5652, transparent: true, opacity: 0.5, depthWrite: false })));
  g.position.set([0.1, -0.35, 0.3][i], 0.6, [2.6, 3.7, 4.8][i]);
  effets.add(g);
  return g;
});
const onde = new THREE.Mesh(new THREE.RingGeometry(0.85, 1, 64), additif(0xffe6a0));
onde.rotation.x = -Math.PI / 2;
effets.add(onde);
const debris = Array.from({ length: 22 }, (_, i) => {
  const m = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.16, 0.16), new THREE.MeshLambertMaterial({ color: i % 2 ? 0x6b4f36 : 0x4f7d33 }));
  m.userData = { a: (i / 22) * Math.PI * 2 + Math.random() * 0.25, v: 2.5 + Math.random() * 3, h: 3.5 + Math.random() * 3.5 };
  effets.add(m);
  return m;
});
const geant = new THREE.Mesh(MODELES.hiramekarei.geometry, additif(0x6ee6ff, 0.45));
geant.scale.setScalar(4.2);
const note = document.getElementById("note");
const disque = new THREE.Mesh(new THREE.RingGeometry(0.25, 1.15, 48), additif(0xdfe6ff, 0.22));
disque.geometry.rotateX(-Math.PI / 2 + 0.15);
effets.add(disque);
const fumees = Array.from({ length: 9 }, (_, i) => {
  const m = new THREE.Mesh(new THREE.SphereGeometry(1, 10, 8), new THREE.MeshLambertMaterial({ color: 0x4a4644, transparent: true, opacity: 0.5, depthWrite: false }));
  m.userData = { b: i % 3, dx: (Math.random() - 0.5) * 1.2, dz: (Math.random() - 0.5) * 1.2, v: 0.04 + Math.random() * 0.05 };
  effets.add(m);
  return m;
});

/** Tout remis en place : rien d'affiché, les cibles à leur place, la lame normale dans la main. */
function cacherEffets() {
  for (const o of effets.children) o.visible = false;
  volante.clear();
  for (const e of eclairs) effets.remove(e);
  eclairs = [];
  note.hidden = true;
  cibles.forEach((c, i) => { c.position.copy(PLACES[i]); c.scale.setScalar(1 / 16); c.rotation.set(0, Math.PI, 0); c.visible = i === 0; });
  if (geant.parent) geant.parent.remove(geant);
  tenues.d.visible = true;
  if (MODELES[lame] && tenues.d.children[0] !== MODELES[lame]) { tenues.d.clear(); tenues.d.add(MODELES[lame]); }
}
const enMonde = (o, x, y, z) => new THREE.Vector3(x, y, z).applyMatrix4(o.matrixWorld);

/** L'effet du coup spécial à l'instant t (en ticks). */
function effet(t) {
  cacherEffets();
  const S = SPECIAUX[lame], main = new THREE.Vector3();
  B.d.main.getWorldPosition(main);
  if (S.effet === "lancer" && t >= 9 && t <= 33) {
    // elle part en tournoyant, à plat, et revient dans la main
    tenues.d.visible = false;
    const m = MODELES[lame].clone(), pivotV = new THREE.Group();
    m.position.y = -18;
    pivotV.add(m);
    pivotV.rotation.x = Math.PI / 2 - 0.15;
    volante.add(pivotV);
    disque.visible = true;
    volante.visible = true;
    const loin = new THREE.Vector3(0.1, 1.2, 6), u = t <= 20 ? 1 - (1 - (t - 9) / 11) ** 2 : 1 - ((t - 20) / 13) ** 2;
    volante.position.copy(main).lerp(loin, u);
    volante.rotation.set(0, t * 0.9, 0);
    disque.position.copy(volante.position);
    if (u > 0.85 && t <= 20) { cible.rotation.x = -0.35; cible.position.z = PLACES[0].z + 0.4; }
  }
  if (S.effet === "avale") {
    // la gueule s'ouvre, il est aspiré, elle reste pleine, puis elle le recrache
    const v = (t >= 5 && t < 12) || (t >= 33 && t < 38) ? "samehada_ouverte" : t >= 12 && t < 33 ? "samehada_pleine" : "samehada";
    tenues.d.clear();
    tenues.d.add(MODELES[v]);
    scene.updateMatrixWorld(true);
    const bout = enMonde(MODELES[v], 0, MODELES[v].geometry.boundingBox.max.y, 0);
    if (t >= 6 && t < 12) {
      const u = (t - 6) / 6;
      cible.position.copy(PLACES[0]).lerp(bout.clone().setY(bout.y - 0.9), u * u);
      cible.scale.setScalar((1 - u) / 16);
      cible.rotation.x = -u * 1.2;
    } else if (t >= 12 && t < 34) {
      cible.visible = false;
      if (t > 16) { note.hidden = false; note.textContent = "Il est dedans : 10 s, ou re-clic droit pour le recracher"; }
    } else if (t >= 34 && t < 42) {
      const u = (t - 34) / 8;
      cible.position.copy(bout).lerp(PLACES[0], u);
      cible.position.y = Math.max(0, (bout.y - 0.9) * (1 - u) + Math.sin(u * Math.PI) * 1.2);
      cible.scale.setScalar(Math.min(1, 0.3 + u) / 16);
    }
  }
  if (S.effet === "foudre") {
    // des arcs entre les deux lames levées, puis la foudre tombe sur la cible
    if (t >= 5 && t < 22 && tenues.g.children[0]) {
      const h = MODELES[lame].geometry.boundingBox.max.y;
      eclair(enMonde(MODELES[lame], 0, h, 0), enMonde(tenues.g.children[0], 0, h, 0), 0.35, 0.015);
    }
    if (t >= 10 && t <= 17) {
      eclair(new THREE.Vector3(0.6, 12, 3.4), new THREE.Vector3(0, 1.9, 2.6), 1.6, 0.05);
      const u = (t - 10) / 7;
      onde.visible = true;
      onde.position.set(0, 0.03, 2.6);
      onde.scale.setScalar(0.3 + u * 2.2);
      onde.material.opacity = 1 - u;
    }
  }
  if (S.effet === "couture") {
    // l'aiguille traverse les trois, le fil les relie à la main, puis il tire
    cibles[1].visible = cibles[2].visible = true;
    if (t >= 5 && t < 30) {
      const z = 1 + Math.min(1, (t - 5) / 7) * 8.5;
      tenues.d.visible = false;
      volante.add(MODELES[lame].clone());
      volante.visible = true;
      volante.position.set(0.05, 1.3, z - 2.4);
      volante.rotation.set(Math.PI / 2, 0, 0);
      const pts = [main.clone()];
      for (const c of cibles) if (c.position.z < z - 2.2) pts.push(c.position.clone().setY(1.25));
      pts.push(new THREE.Vector3(0.05, 1.3, z - 2.4));
      fil.geometry.setFromPoints(pts);
      fil.visible = true;
    }
    if (t >= 26) {
      const u = Math.min(1, (t - 26) / 8);
      cibles.forEach((c, i) => { c.position.z = PLACES[i].z + ([1.5, 2.2, 2.9][i] - PLACES[i].z) * u * u; c.rotation.x = -0.4 * Math.sin(u * Math.PI); });
    }
  }
  if (S.effet === "explosions") {
    booms.forEach((b, i) => {
      const d = t - (10 + i * 3), r = [0.75, 0.95, 1.2][i];
      if (d < 0 || d > 14) return;
      b.visible = true;
      const u = Math.min(1, d / 4);
      b.children[0].scale.setScalar(r * u); b.children[0].material.opacity = Math.max(0, 1 - d / 8);
      b.children[1].scale.setScalar(r * u * 0.9); b.children[1].material.opacity = Math.max(0, 1 - d / 5);
      b.children[2].scale.setScalar(r * (0.6 + d / 10)); b.children[2].material.opacity = Math.max(0, 0.55 - d / 24);
      b.children[2].position.y = d * 0.04;
      if (d > 2 && d < 8) { cibles[0].position.z = PLACES[0].z + Math.min(1.2, (t - 12) * 0.12); cibles[0].rotation.x = -0.3; }
    });
    for (const f of fumees) {
      const q = f.userData, d = t - (10 + q.b * 3) - 3;
      if (d < 0 || d > 20) continue;
      f.visible = true;
      f.position.set(booms[q.b].position.x + q.dx, 0.6 + d * q.v * 3, booms[q.b].position.z + q.dz);
      f.scale.setScalar(0.35 + d * 0.04);
      f.material.opacity = Math.max(0, 0.55 - d / 36);
    }
  }
  if (S.effet === "seisme" && t >= 17) {
    // l'onde de choc, la terre qui saute, la cible soulevée
    const d = t - 17, s = d / 20;
    onde.visible = d < 12;
    onde.position.set(0, 0.03, 0.3);
    onde.scale.setScalar(0.4 + d * 0.45);
    onde.material.opacity = Math.max(0, 1 - d / 12);
    for (const m of debris) {
      const q = m.userData, y = q.h * s - 4.9 * s * s;
      m.visible = d < 24 && y > 0;
      m.position.set(Math.cos(q.a) * (0.6 + q.v * s), y, 0.3 + Math.sin(q.a) * (0.6 + q.v * s));
      m.rotation.set(d * 0.3, d * 0.2, 0);
    }
    cible.position.y = Math.max(0, 4.5 * s - 4.9 * s * s);
    cible.rotation.x = -Math.min(0.5, s * 1.2);
  }
  if (S.effet === "chakra") {
    // il charge (l'arête brille), puis la grande lame de chakra s'abat avec ses bras
    if (t >= 4 && t < 18) { tenues.d.clear(); tenues.d.add(MODELES.hiramekarei_chakra); }
    if (t >= 18 && t < 34) { tenues.d.add(geant); geant.material.opacity = 0.45 * (t < 26 ? 1 : 1 - (t - 26) / 8); }
  }
}
for (const [id, m] of [["mc", "combo"], ["ms", "special"]]) {
  document.getElementById(id).addEventListener("click", () => {
    mode = m; tCoup = 0; coupIdx = 0; pause = 8;
    for (const x of ["mc", "ms"]) document.getElementById(x).setAttribute("aria-pressed", String(x === id));
    cacherEffets();
  });
}

// ------------------------------------------------------------------ la boucle : le combo en entier, coup après coup, puis on recommence
// ?lame=kiba&coup=2&t=6&lacet=1.2 : figé à cet instant (pour regarder une pose)
const Q = new URLSearchParams(location.search);
let fige = Q.has("t") ? parseFloat(Q.get("t")) : null;
if (Q.has("lacet")) lacet = parseFloat(Q.get("lacet"));
const pointe = new THREE.Vector3(), milieu = new THREE.Vector3();
let avant = performance.now();
choisirLame(Q.get("lame") || "kubikiribocho");
if (Q.has("coup")) coupIdx = parseInt(Q.get("coup"), 10);
if (fige !== null) { pause = 0; tCoup = fige; }

/** La pointe et le milieu de la lame, si le joueur était dans cette pose. */
function bout(p) {
  appliquer(p);
  orienter(tenues.d, p.poignet, p.roulis, lame === "kabutowari");
  scene.updateMatrixWorld(true);
  const bb = MODELES[lame].geometry.boundingBox;
  return [pointe.set(0, bb.max.y, 0).applyMatrix4(MODELES[lame].matrixWorld).clone(), milieu.set(0, bb.max.y * 0.32, 0).applyMatrix4(MODELES[lame].matrixWorld).clone()];
}

function boucle(maintenant) {
  const dt = Math.min(0.05, (maintenant - avant) / 1000);
  avant = maintenant;
  uneImage(dt);
  requestAnimationFrame(boucle);
}

/** Une image : on avance le temps (sauf si c'est figé), on pose le joueur, la traînée, et on dessine. */
function uneImage(dt) {
  const combo = COMBOS[lame];
  if (fige === null) {
    if (pause > 0) {
      pause -= dt * 20 * vitesse;
    } else {
      tCoup += dt * 20 * vitesse;
      const duree = mode === "special" ? SPECIAUX[lame].cles.at(-1).t : combo.coups[coupIdx].cles.at(-1).t;
      if (tCoup >= duree) {
        tCoup = 0;
        if (mode === "special") pause = 16;
        else {
          coupIdx = (coupIdx + 1) % combo.coups.length;
          if (coupIdx === 0) pause = 14;
        }
      }
    }
  }
  const S = SPECIAUX[lame], t = pause > 0 ? 0 : tCoup;
  const c = mode === "special" ? { nom: S.nom, cles: S.cles, trace: S.trace || [999, 999] } : combo.coups[coupIdx];
  document.getElementById("coup").textContent = mode === "special" ? "Coup spécial (clic droit)  ·  " + S.nom + "  ·  recharge " + S.recharge + " s"
    : (coupIdx + 1) + " / " + combo.coups.length + "  ·  " + c.nom;
  if (tenues.d.children[0] !== MODELES[lame]) { tenues.d.clear(); tenues.d.add(MODELES[lame]); }
  // la traînée : le chemin exact de la pointe pendant les derniers ticks du coup (elle suit la lame à toute vitesse)
  const pos = [], col = [], couleur = new THREE.Color(TRAINEES[lame]);
  if (trainee && t > c.trace[0]) {
    const fin = Math.min(t, c.trace[1] + 0.5), debut = Math.max(c.trace[0], fin - 4.5), eteint = Math.max(0, t - c.trace[1] - 0.5);
    const n = Math.max(2, Math.ceil((fin - debut) / 0.15)), chemin = [];
    for (let i = 0; i <= n; i++) chemin.push(bout(pose(c.cles, debut + (fin - debut) * (i / n))));
    const force = Math.max(0, 1 - eteint / 3);
    for (let i = 0; i < n; i++) {
      const a0 = (i / n) ** 1.6 * force, a1 = ((i + 1) / n) ** 1.6 * force;
      const [p0, m0] = chemin[i], [p1, m1] = chemin[i + 1];
      for (const [q, al] of [[m0, a0 * 0.12], [m1, a1 * 0.12], [p1, a1], [m0, a0 * 0.12], [p1, a1], [p0, a0]]) {
        pos.push(q.x, q.y, q.z);
        col.push(couleur.r * al, couleur.g * al, couleur.b * al);
      }
    }
  }
  ruban.geometry.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
  ruban.geometry.setAttribute("color", new THREE.Float32BufferAttribute(col, 3));
  const p = pose(c.cles, t);
  appliquer(p);
  orienter(tenues.d, p.poignet, p.roulis, lame === "kabutowari");
  orienter(tenues.g, TENUE[lame].gauche ? p.poignet : 0, TENUE[lame].gauche ? p.roulis : 0, false);
  scene.updateMatrixWorld(true);
  if (mode === "special") effet(t); else cacherEffets();
  const loinVoulu = mode === "special" && ["lancer", "couture", "explosions", "seisme"].includes(SPECIAUX[lame].effet) ? 1 : 0;
  recul += (loinVoulu - recul) * (fige !== null && fige !== undefined ? 1 : 0.08);
  const dCam = distance + recul * 3.2, vise = cibleCam.clone(); vise.z += recul * 1.6; vise.y += recul * 0.2;
  camera.position.set(vise.x + Math.sin(lacet) * Math.cos(tangage) * dCam, vise.y + Math.sin(tangage) * dCam, vise.z + Math.cos(lacet) * Math.cos(tangage) * dCam);
  camera.lookAt(vise);
  rendu.render(scene, camera);
}

/** Pour regarder les poses : des images figées enregistrées par le petit serveur (captures/nom.png). */
window.serie = async (id, coup, instants, l, nom, special) => {
  choisirLame(id);
  mode = special ? "special" : "combo";
  coupIdx = coup; pause = 0; if (l !== undefined) lacet = l;
  for (const t of instants) {
    fige = t; tCoup = t;
    uneImage(0);
    const b = await new Promise((r) => rendu.domElement.toBlob(r));
    await fetch("captures/" + nom + "-" + String(t).replace(".", "_") + ".png", { method: "PUT", body: b });
  }
  return "ok";
};
requestAnimationFrame(boucle);
</script>
`;
const sortie = path.join(ICI, "..", "apercu", "combos.html");
fs.writeFileSync(sortie, html);
console.log(sortie, (html.length / 1024).toFixed(0) + " Ko");
