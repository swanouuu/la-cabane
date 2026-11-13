// Assemble les captures de l'aperçu 3D (apercu/captures, faites par window.capturer) en planches, comme les autres
// pouvoirs : un titre, des lignes de texte, puis les images en grille avec leur légende.
// node planche.mjs [tenue|attaques|ulti] (sans rien : les trois)
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, ecrireTexte, POLICE } from "../../fruits/generateur/moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOC = path.join(ICI, "..");
const CAPTURES = path.join(DOC, "apercu", "captures");
Object.assign(POLICE, { "%": "101001010100101", ">": "100010001010100", "?": "110001010000010", ";": "000010000010100", "+": "000010111010000" });
const NOIRT = [30, 30, 40, 255], ROUGE_T = [190, 30, 20, 255];
// la police n'a pas d'accents
const sansAccents = (t) => t.normalize("NFD").replace(/[̀-ͯ]/g, "").toUpperCase();

function rect(img, W, x0, y0, x1, y1, c) {
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) img.set(c, (y * W + x) * 4);
}

export function planche(fichier, titre, lignes, vues, { colonnes = 3, cw = 640 } = {}) {
  const imgs = vues.map(([nom]) => lire(path.join(CAPTURES, nom)));
  const k = imgs[0].w / cw, ch = Math.round(imgs[0].h / k);
  const HAUT = 100 + lignes.length * 34 + 20, M = 34;
  const rangs = Math.ceil(vues.length / colonnes);
  const W = cw * colonnes, H = HAUT + (M + ch) * rangs;
  const img = Buffer.alloc(W * H * 4, 255);
  rect(img, W, 0, 0, W, HAUT, [242, 234, 232, 255]);
  ecrireTexte(img, W, sansAccents(titre), 30, 22, 5, NOIRT);
  lignes.forEach((t, n) => {
    const [texte, c] = Array.isArray(t) ? t : [t, NOIRT];
    ecrireTexte(img, W, sansAccents(texte), 30, 100 + n * 34, 3, c);
  });
  vues.forEach(([, legende], n) => {
    const im = imgs[n], c = n % colonnes, l = Math.floor(n / colonnes);
    const ox = c * cw, oy = HAUT + l * (M + ch) + M;
    // réduction par moyenne (les images font 1280 de large)
    for (let y = 0; y < ch; y++) for (let x = 0; x < cw; x++) {
      const s = [0, 0, 0];
      let q = 0;
      for (let dy = 0; dy < k; dy++) for (let dx = 0; dx < k; dx++) {
        const i = ((Math.floor(y * k) + dy) * im.w + Math.floor(x * k) + dx) * 4;
        if (i + 2 < im.px.length) { s[0] += im.px[i]; s[1] += im.px[i + 1]; s[2] += im.px[i + 2]; q++; }
      }
      img.set([s[0] / q, s[1] / q, s[2] / q, 255].map(Math.round), ((oy + y) * W + ox + x) * 4);
    }
    ecrireTexte(img, W, sansAccents(legende), ox + 12, HAUT + l * (M + ch) + 10, 3, ROUGE_T);
  });
  ecrirePng(path.join(DOC, fichier), W, H, img);
  console.log("ok", fichier, W, H);
}

const PLANCHES = {
  tenue: () => planche("planche-tenue.png", "Minato 3 : le mode rouge (touche J)", [
    ["Au niveau 2 (mode Kurama), J le met 1 minute en mode rouge, puis il redevient normal. J : une fois par eveil", ROUGE_T],
    "Le skin du niveau 2, pixel pour pixel, passe en rouge (les flammes orange restent orange, les traits noirs aussi)",
    "La tete : son visage de base, les pupilles rouges (2 pixels pour des yeux 2 x 2). Les cornes font le contour des yeux et montent en 3D",
    "Par-dessus : le manteau de chakra (col haut, spirale, ouvert en V, bord creme, traits noirs) et 10 langues en 3D qui partent dans tous les sens",
  ], [
    ["t-avant.png", "Devant"], ["t-dos.png", "Dos : les 10 langues"], ["t-cote.png", "De cote : elles partent en arriere"],
    ["t-visage.png", "Le capuchon et les cornes"], ["t-trois-quarts.png", "De trois quarts"], ["t-sans.png", "Option : sans le manteau"],
  ]),
  attaques: () => planche("planche-attaques.png", "Minato 3 : les attaques du mode rouge", [
    ["Pendant la minute du mode rouge, les touches changent. Chaque TP fait le son du flash de Minato (le tien, pack prive)", ROUGE_T],
    "Clic gauche : coup eclair, TP autour de la cible + un double rouge qui frappe de l'autre cote",
    "Clic droit : ronde des flashs, 6 kunai en cercle, il saute de l'un a l'autre (une etoile rouge), coup de talon du ciel",
    "Accroupi + clic droit : ligne rouge, TP en ligne droite (25 blocs), ceux qu'il traverse explosent 0,8 s apres",
    "Accroupi + clic gauche : Rasengan eclair, TP sur ce qu'il vise (40 blocs) et Rasengan rouge, la cible est projetee",
    "Technique : doubles eclair, 4 doubles rouges filent sur les 4 plus proches. L : le contre, sans les yeux rouges en garde",
  ], [
    ["a-gauche.png", "Clic gauche"], ["a-droite-1.png", "Clic droit : l'etoile"], ["a-droite-2.png", "Clic droit : le talon"],
    ["a-ligne.png", "Accroupi + clic droit"], ["a-rasengan-1.png", "Accroupi + clic gauche"], ["a-rasengan-2.png", "La cible projetee"],
    ["a-doubles.png", "Technique : doubles"], ["a-contre.png", "L : en garde, rien ne se voit"], ["a-doubles-2.png", "Les doubles explosent"],
  ]),
  ulti: () => planche("planche-ulti.png", "Minato 3 : l'ulti, mille flashs (J dans le mode)", [
    ["Une fois par mode rouge. Il plante son kunai : un sceau geant de 25 blocs s'etend autour de lui", ROUGE_T],
    "Le temps s'arrete : tout devient gris sauf lui. Ceux dans le sceau ne peuvent plus bouger ni frapper",
    "Mille flashs : il frappe tout le monde de partout, chaque TP laisse un trait rouge. Puis Rasengan geant du ciel",
    "Il l'ecrase au centre du sceau, le temps repart : tous les coups tombent d'un coup, un dome rouge, tout le monde vole",
  ], [
    ["u1.png", "0,6 s : le sceau s'etend"], ["u2.png", "1,7 s : le temps s'arrete"], ["u3.png", "2,4 s : mille flashs"],
    ["u4.png", "6 s : Rasengan geant"], ["u5.png", "8 s : il l'ecrase"], ["u6.png", "8,5 s : le temps repart"],
    ["u7.png", "9 s : le dome"], ["u8.png", "10 s : tout le monde vole"], ["u9.png", "12 s : fini"],
  ]),
};

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  const quoi = process.argv[2];
  for (const [nom, f] of Object.entries(PLANCHES)) if (!quoi || quoi === nom) f();
}
