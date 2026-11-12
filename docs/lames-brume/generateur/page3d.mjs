// La page 3D de la Samehada (three.js) : on la tourne à la souris, normale / pleine / gueule ouverte, à côté d'un
// joueur pour la taille. node page3d.mjs → ../apercu/samehada-3d.html (publiée comme Artifact).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { voxels } from "./samehada3d.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
// x, y, z et la couleur en un seul nombre : 7 valeurs par voxel → 4
const pack = (l) => l.flatMap(([x, y, z, r, g, b]) => [x, y, z, (r << 16) | (g << 8) | b]);
const donnees = JSON.stringify(Object.fromEntries(["normale", "pleine", "ouverte"].map((v) => [v, pack(voxels(v))])));

const html = `<title>Samehada 3D</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@500;700&family=Inter:wght@400;500&display=swap">
<style>
/* Une seule ambiance, sombre (la brume de Kiri la nuit) : la vue 3D prend tout l'écran, les commandes flottent en bas. */
:root {
  --fond: #151a24; --fond2: #222b3a; --texte: #e6e9f2; --doux: #9aa3b8; --accent: #8f86e0; --bouton: #2b3445; --trait: #3a4558;
  --titre: "Space Grotesk", "Segoe UI", system-ui, sans-serif; --corps: "Inter", "Segoe UI", system-ui, sans-serif;
  color-scheme: dark;
}
html, body { height: 100%; }
body { margin: 0; background: var(--fond); color: var(--texte); font-family: var(--corps); overflow: hidden; }
#scene { position: fixed; inset: 0; }
canvas { display: block; touch-action: none; cursor: grab; }
canvas:active { cursor: grabbing; }
header { position: fixed; top: 0; left: 0; right: 0; padding-top: calc(14px + env(safe-area-inset-top, 0px));
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); pointer-events: none; }
h1 { font-family: var(--titre); font-weight: 700; font-size: 1.5rem; margin: 0; letter-spacing: 0.01em; }
header p { margin: 4px 0 0; color: var(--doux); font-size: 0.85rem; max-width: 60ch; }
.barre { position: fixed; left: 0; right: 0; bottom: 0; display: flex; flex-wrap: wrap; gap: 8px; justify-content: center;
  padding-bottom: calc(16px + env(safe-area-inset-bottom, 0px)); padding-top: 12px;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }
.groupe { display: flex; gap: 4px; background: var(--fond2); border: 1px solid var(--trait); border-radius: 10px; padding: 4px; }
button { font: 500 0.9rem var(--corps); color: var(--texte); background: transparent; border: 0; border-radius: 7px; padding: 8px 12px; cursor: pointer; }
button:hover { background: var(--bouton); }
button[aria-pressed="true"] { background: var(--accent); color: #12101e; }
button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.aide { position: fixed; right: max(16px, env(safe-area-inset-right, 0px)); top: calc(18px + env(safe-area-inset-top, 0px)); color: var(--doux); font-size: 0.78rem; text-align: right; pointer-events: none; }
@media (max-width: 560px) { .aide { display: none; } }
</style>

<div id="scene"></div>
<header>
  <h1>Samehada</h1>
  <p id="legende">Normale : le manche de bois, les bandages, les écailles en haut.</p>
</header>
<div class="aide">Glisser pour tourner<br>Molette ou pincer pour zoomer</div>
<div class="barre">
  <div class="groupe" role="group" aria-label="Forme">
    <button id="b-normale" aria-pressed="true">Normale</button>
    <button id="b-pleine" aria-pressed="false">Pleine</button>
    <button id="b-ouverte" aria-pressed="false">Gueule ouverte</button>
  </div>
  <div class="groupe">
    <button id="b-joueur" aria-pressed="true">Joueur à côté</button>
    <button id="b-tourne" aria-pressed="true">Tourne seule</button>
  </div>
</div>

<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
<script>
const DONNEES = ${donnees};
const LEGENDES = {
  normale: "Normale : le manche de bois, les bandages, les écailles en haut.",
  pleine: "Pleine : quelqu'un est dedans. Elle se couvre de mèches d'écailles violettes, la gueule sur le dessus, avec ses dents.",
  ouverte: "Gueule ouverte : elle engloutit l'ennemi, le garde 10 s puis le recrache.",
};
const U = 1 / 32; // un voxel = un demi-pixel du joueur, le joueur fait 2 blocs = 64 voxels
const conteneur = document.getElementById("scene");
const rendu = new THREE.WebGLRenderer({ antialias: true });
rendu.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
conteneur.appendChild(rendu.domElement);
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x151a24);
scene.fog = new THREE.Fog(0x151a24, 6, 14);
const camera = new THREE.PerspectiveCamera(38, 1, 0.05, 50);
scene.add(new THREE.HemisphereLight(0xcfd8ff, 0x2a2430, 0.75));
const soleil = new THREE.DirectionalLight(0xffffff, 0.85);
soleil.position.set(-2, 4, 3);
scene.add(soleil);

// le sol en damier herbe, comme en jeu
const sol = new THREE.Group();
for (let x = -4; x < 4; x++) for (let z = -4; z < 4; z++) {
  const m = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshLambertMaterial({ color: (x + z) % 2 ? 0x4c7a30 : 0x568836 }));
  m.rotation.x = -Math.PI / 2; m.position.set(x + 0.5, 0, z + 0.5); sol.add(m);
}
scene.add(sol);

const cube = new THREE.BoxGeometry(U, U, U);
const modeles = {};
for (const [nom, l] of Object.entries(DONNEES)) {
  const n = l.length / 4;
  const mesh = new THREE.InstancedMesh(cube, new THREE.MeshLambertMaterial(), n);
  const m = new THREE.Matrix4(), c = new THREE.Color();
  for (let i = 0; i < n; i++) {
    m.makeTranslation((l[i * 4] + 0.5) * U, (l[i * 4 + 1] + 0.5) * U, (l[i * 4 + 2] + 0.5) * U);
    mesh.setMatrixAt(i, m);
    c.setHex(l[i * 4 + 3]); mesh.setColorAt(i, c);
  }
  mesh.visible = false;
  modeles[nom] = mesh;
}
const epee = new THREE.Group();
Object.values(modeles).forEach((m) => epee.add(m));
epee.position.set(0.35, 0.02, 0);
scene.add(epee);

// un joueur (2 blocs) pour la taille
const joueur = new THREE.Group();
const bloc = (w, h, d, x, y, z, couleur) => { const b = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), new THREE.MeshLambertMaterial({ color: couleur })); b.position.set(x, y, z); joueur.add(b); };
bloc(0.5, 0.5, 0.5, 0, 1.75, 0, 0x6b7891); bloc(0.5, 0.75, 0.25, 0, 1.125, 0, 0x3c4d6e);
bloc(0.25, 0.75, 0.25, -0.375, 1.125, 0, 0x6b7891); bloc(0.25, 0.75, 0.25, 0.375, 1.125, 0, 0x6b7891);
bloc(0.25, 0.75, 0.25, -0.125, 0.375, 0, 0x2c3446); bloc(0.25, 0.75, 0.25, 0.125, 0.375, 0, 0x2c3446);
joueur.position.set(-0.75, 0, 0);
scene.add(joueur);

let forme = "normale";
const choisir = (f) => {
  forme = f;
  for (const [nom, m] of Object.entries(modeles)) m.visible = nom === f;
  for (const n of ["normale", "pleine", "ouverte"]) document.getElementById("b-" + n).setAttribute("aria-pressed", String(n === f));
  document.getElementById("legende").textContent = LEGENDES[f];
};
choisir("normale");
for (const n of ["normale", "pleine", "ouverte"]) document.getElementById("b-" + n).addEventListener("click", () => choisir(n));
const bJoueur = document.getElementById("b-joueur"), bTourne = document.getElementById("b-tourne");
bJoueur.addEventListener("click", () => { joueur.visible = !joueur.visible; bJoueur.setAttribute("aria-pressed", String(joueur.visible)); });
let tourne = !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
bTourne.setAttribute("aria-pressed", String(tourne));
bTourne.addEventListener("click", () => { tourne = !tourne; bTourne.setAttribute("aria-pressed", String(tourne)); });

// la caméra tourne autour de l'épée : glisser pour tourner, molette ou pincer pour zoomer
let lacet = 0.5, tangage = 0.18, distance = 4.4;
const cible = new THREE.Vector3(0, 1.15, 0);
const placer = () => {
  camera.position.set(cible.x + Math.sin(lacet) * Math.cos(tangage) * distance, cible.y + Math.sin(tangage) * distance, cible.z + Math.cos(lacet) * Math.cos(tangage) * distance);
  camera.lookAt(cible);
};
const doigts = new Map();
let pince = 0;
rendu.domElement.addEventListener("pointerdown", (e) => { doigts.set(e.pointerId, [e.clientX, e.clientY]); rendu.domElement.setPointerCapture(e.pointerId); tourne = false; bTourne.setAttribute("aria-pressed", "false"); });
rendu.domElement.addEventListener("pointermove", (e) => {
  if (!doigts.has(e.pointerId)) return;
  const [x0, y0] = doigts.get(e.pointerId);
  if (doigts.size === 1) { lacet -= (e.clientX - x0) * 0.008; tangage = Math.max(-0.6, Math.min(1.3, tangage + (e.clientY - y0) * 0.006)); }
  doigts.set(e.pointerId, [e.clientX, e.clientY]);
  if (doigts.size === 2) {
    const [a, b] = [...doigts.values()], d = Math.hypot(a[0] - b[0], a[1] - b[1]);
    if (pince) distance = Math.max(1.6, Math.min(9, distance * (pince / d)));
    pince = d;
  }
});
const lacher = (e) => { doigts.delete(e.pointerId); pince = 0; };
rendu.domElement.addEventListener("pointerup", lacher);
rendu.domElement.addEventListener("pointercancel", lacher);
rendu.domElement.addEventListener("wheel", (e) => { e.preventDefault(); distance = Math.max(1.6, Math.min(9, distance * (1 + e.deltaY * 0.001))); }, { passive: false });

const taille = () => { const w = conteneur.clientWidth || window.innerWidth, h = conteneur.clientHeight || window.innerHeight; rendu.setSize(w, h); camera.aspect = w / h; camera.updateProjectionMatrix(); };
window.addEventListener("resize", taille);
taille();
const boucle = () => { if (tourne) lacet += 0.006; placer(); rendu.render(scene, camera); requestAnimationFrame(boucle); };
boucle();
</script>
`;
const sortie = path.join(ICI, "..", "apercu", "samehada-3d.html");
fs.mkdirSync(path.dirname(sortie), { recursive: true });
fs.writeFileSync(sortie, html);
console.log(sortie, (html.length / 1024).toFixed(0) + " Ko");
