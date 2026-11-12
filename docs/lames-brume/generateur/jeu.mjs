// Les lames de la Brume pour le jeu : les mêmes dessins que la planche (rendu.mjs, samehada3d.mjs), transformés en
// faces colorées (le format .bin de ModeleVent / MaillageLame : nombre de groupes, puis par groupe le nombre de faces,
// et chaque face : 4 coins, la normale, r g b et sa sorte — 0 pleine, 1 qui brille, ≥ 8 transparente de cette opacité).
// Repère de la lame : x en travers (vers le dos de la lame), y le long (vers la pointe), z son épaisseur (vers l'avant
// pour la Samehada, où est sa gueule) ; en pixels du joueur, la prise de la main à l'origine. Les faces voisines de même
// couleur sont fusionnées en rectangles.
// node jeu.mjs → mod-cabane/src/main/resources/assets/cabane/lames/*.bin
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { KUBI, KIBA, NUI, SHIB, HACHE, MARTEAU, HIRA } from "./rendu.mjs";
import { voxels } from "./samehada3d.mjs";
import { COMBOS, SPECIAUX, TENUE, TRAINEES } from "./combos.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = path.join(ICI, "..", "..", "..", "mod-cabane", "src", "main", "resources", "assets", "cabane", "lames");
const CHAKRA = [110, 220, 255];

/** Fusionne une grille 2D (cle(a, b) → chaîne ou null) en rectangles [a0, a1, b0, b1, cle] (bornes exclues). */
function rectangles(na, nb, cle) {
  const pris = new Uint8Array(na * nb), sortie = [];
  for (let a = 0; a < na; a++) for (let b = 0; b < nb; b++) {
    const k = cle(a, b);
    if (k == null || pris[a * nb + b]) continue;
    let b1 = b + 1;
    while (b1 < nb && !pris[a * nb + b1] && cle(a, b1) === k) b1++;
    let a1 = a + 1;
    for (; a1 < na; a1++) {
      let ok = true;
      for (let x = b; x < b1 && ok; x++) ok = !pris[a1 * nb + x] && cle(a1, x) === k;
      if (!ok) break;
    }
    for (let x = a; x < a1; x++) for (let y = b; y < b1; y++) pris[x * nb + y] = 1;
    sortie.push([a, a1, b, b1, k]);
  }
  return sortie;
}

const decode = (k) => { const [r, g, b, sorte, t] = k.split(",").map(Number); return { c: [r, g, b], sorte, t }; };

/**
 * Une lame dessinée en pixels (une épaisseur par pixel) : les grandes faces avant et arrière fusionnées, puis les
 * bords (là où le voisin est plus mince).
 */
export function facesPixels(def, variante, { chakra = false } = {}) {
  const [ni, nj] = def.n, e = def.ech, [g0, g1] = def.g;
  const info = (i, j) => {
    if (i < 0 || j < 0 || i >= ni || j >= nj) return null;
    const k = def.forme(i, j, variante);
    if (!k) return null;
    const P = def.pal[k], t = def.epais?.(i, j, k, variante) ?? P.ep ?? def.ep ?? 1;
    let c = P.c.map((v) => Math.min(255, Math.round(v * (P.g ? 1 : def.teinte?.(i, j, variante) ?? 1))));
    let sorte = P.g ? 1 : 0;
    if (chakra) { c = P.g ? [220, 250, 255] : CHAKRA; sorte = P.g ? 1 : 130; }
    return { c, sorte, t: Math.round(t * 100) / 100 };
  };
  const cle = (o) => (o ? `${o.c.join(",")},${o.sorte},${o.t}` : null);
  const X = (j) => (j - g1 - 0.5) * e, Y = (i) => (i - g0 - 0.5) * e;
  const faces = [];
  const face = (coins, n, k) => { const d = decode(k); faces.push({ coins, n, c: d.c, sorte: d.sorte }); };
  // devant et derrière
  for (const [i0, i1, j0, j1, k] of rectangles(ni, nj, (i, j) => cle(info(i, j)))) {
    const z = (decode(k).t * e) / 2, x0 = X(j0), x1 = X(j1), y0 = Y(i0), y1 = Y(i1);
    face([[x0, y0, z], [x1, y0, z], [x1, y1, z], [x0, y1, z]], [0, 0, 1], k);
    face([[x0, y0, -z], [x0, y1, -z], [x1, y1, -z], [x1, y0, -z]], [0, 0, -1], k);
  }
  const ep = (i, j) => info(i, j)?.t ?? 0;
  // les bords le long de la lame (vers la pointe, vers le manche) : fusionnés en travers
  for (const s of [1, -1]) {
    for (const [i0, , j0, j1, k] of rectangles(ni, nj, (i, j) => (ep(i + s, j) < ep(i, j) ? cle(info(i, j)) : null))) {
      const z = (decode(k).t * e) / 2, y = s > 0 ? Y(i0 + 1) : Y(i0), x0 = X(j0), x1 = X(j1);
      face([[x0, y, -z], [x1, y, -z], [x1, y, z], [x0, y, z]], [0, s, 0], k);
    }
  }
  // les bords en travers : fusionnés le long
  for (const s of [1, -1]) {
    for (const [j0, , i0, i1, k] of rectangles(nj, ni, (j, i) => (ep(i, j + s) < ep(i, j) ? cle(info(i, j)) : null))) {
      const z = (decode(k).t * e) / 2, x = s > 0 ? X(j0 + 1) : X(j0), y0 = Y(i0), y1 = Y(i1);
      face([[x, y0, -z], [x, y1, -z], [x, y1, z], [x, y0, z]], [s, 0, 0], k);
    }
  }
  return faces;
}

/** Un modèle en voxels (la Samehada) : seules les faces à l'air libre, fusionnées couche par couche. */
export function facesVoxels(liste, e, g0) {
  const plein = new Map(liste.map((v) => [`${v[0]},${v[1]},${v[2]}`, v]));
  const xs = liste.map((v) => v[0]), ys = liste.map((v) => v[1]), zs = liste.map((v) => v[2]);
  const min = [Math.min(...xs), Math.min(...ys), Math.min(...zs)], max = [Math.max(...xs), Math.max(...ys), Math.max(...zs)];
  const faces = [];
  const P = (x, y, z) => [(x) * e, (y - g0) * e, (z) * e];
  for (let axe = 0; axe < 3; axe++) for (const s of [1, -1]) {
    const [u, w] = [(axe + 1) % 3, (axe + 2) % 3];
    for (let c = min[axe]; c <= max[axe]; c++) {
      const nu = max[u] - min[u] + 1, nw = max[w] - min[w] + 1;
      const cle = (a, b) => {
        const p = [0, 0, 0];
        p[axe] = c; p[u] = a + min[u]; p[w] = b + min[w];
        const v = plein.get(p.join(","));
        if (!v) return null;
        const q = [...p];
        q[axe] += s;
        return plein.has(q.join(",")) ? null : `${v[3]},${v[4]},${v[5]}`;
      };
      for (const [a0, a1, b0, b1, k] of rectangles(nu, nw, cle)) {
        const coin = (a, b) => { const p = [0, 0, 0]; p[axe] = c + (s > 0 ? 1 : 0); p[u] = a + min[u]; p[w] = b + min[w]; return P(...p); };
        const n = [0, 0, 0];
        n[axe] = s;
        faces.push({ coins: [coin(a0, b0), coin(a1, b0), coin(a1, b1), coin(a0, b1)], n, c: k.split(",").map(Number), sorte: 0 });
      }
    }
  }
  return faces;
}

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
      dv.setUint8(o, f.sorte); o += 1;
    }
  }
  fs.mkdirSync(ASSETS, { recursive: true });
  fs.writeFileSync(path.join(ASSETS, nom + ".bin"), Buffer.from(dv.buffer));
  console.log(nom, groupes.map((g) => g.length).join(" + "), "faces,", (taille / 1024).toFixed(0), "Ko");
}

// node jeu.mjs : écrit les modèles du jeu (importer ce fichier n'écrit rien)
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  ecrire("kubikiribocho", [facesPixels(KUBI)]);
  ecrire("samehada", ["normale", "pleine", "ouverte"].map((v) => facesVoxels(voxels(v), 0.5, 7)));
  ecrire("kiba", [facesPixels(KIBA)]);
  ecrire("nuibari", [facesPixels(NUI)]);
  ecrire("shibuki", [facesPixels(SHIB)]);
  ecrire("kabutowari", [facesPixels(HACHE), facesPixels(MARTEAU)]);
  ecrire("hiramekarei", [facesPixels(HIRA), facesPixels(HIRA, "chakra"), facesPixels(HIRA, "chakra", { chakra: true })]);
  ecrireCombos();
}

/** La longueur de la lame, de la prise (0) à la pointe, en pixels. */
function pointe(faces) { return Math.max(...faces.flatMap((f) => f.coins.map((p) => p[1]))); }

/**
 * assets/cabane/lames/combos.json : les combos et les clics droits de combos.mjs (les mêmes que l'aperçu), joués en jeu
 * par playerAnimator (AnimLames). Les canaux à 0 sont omis. En jeu, Brise-casque saute pour de vrai (le serveur lance
 * le joueur) : l'animation ne monte pas le corps dans les airs.
 */
/** Le tick où le coup touche : la première clé « entree » (la lame part), sinon le début de la traînée. */
function frappe(c) {
  const k = c.cles.find((k) => k.ease === "entree");
  return k ? k.t : c.trace[0] + 2;
}

function ecrireCombos() {
  const CANAUX = ["cd", "cg", "jd", "jg", "gd", "gg", "penche", "tour", "haut", "avant", "poignet", "roulis"];
  const cle = (c) => {
    const r = { t: c.t, ease: c.ease || "doux" };
    for (const b of ["bd", "bg"]) r[b] = (c[b] || [0, 0, 0]).map((v) => +v.toFixed(4));
    for (const k of CANAUX) if (c[k]) r[k] = +c[k].toFixed(4);
    return r;
  };
  const longueurs = {
    kubikiribocho: pointe(facesPixels(KUBI)), samehada: pointe(facesVoxels(voxels("normale"), 0.5, 7)), kiba: pointe(facesPixels(KIBA)),
    nuibari: pointe(facesPixels(NUI)), shibuki: pointe(facesPixels(SHIB)), kabutowari: pointe(facesPixels(HACHE)), hiramekarei: pointe(facesPixels(HIRA)),
  };
  const sortie = {};
  for (const [id, combo] of Object.entries(COMBOS)) {
    const S = SPECIAUX[id];
    const special = { nom: S.nom, recharge: S.recharge, effet: S.effet, trace: S.trace || null, cles: S.cles.map(cle) };
    if (id === "kabutowari") for (const c of special.cles) c.haut = Math.min(c.haut || 0, 2);
    sortie[id] = {
      pointe: +longueurs[id].toFixed(2), couleur: TRAINEES[id], deuxMains: !!TENUE[id].deuxMains, gauche: TENUE[id].gauche || null,
      coups: combo.coups.map((c) => ({ nom: c.nom, trace: c.trace, zone: c.zone || null, frappe: frappe(c), cles: c.cles.map(cle) })), special,
    };
  }
  fs.writeFileSync(path.join(ASSETS, "combos.json"), JSON.stringify(sortie));
  console.log("combos.json", Object.keys(sortie).length, "lames");
}
