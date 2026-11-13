// Le skin de Minato 3 (le mode rouge, touche J) : la tenue du mode Kurama (niveau 2) passée en rouge, comme le mode
// Baryon des images de l'utilisateur. Le jaune de la tenue devient rouge, l'orange des flammes reste orange (les
// rayures), les traits noirs restent noirs. La tête garde ses cheveux jaunes et le visage du mode Kurama, avec en plus
// les traits noirs du mode Baryon autour des yeux (qui remontent vers le front) et des yeux orange-rouge.
// node skin.mjs → ../apercu/skins/<joueur>-niv2.png, <joueur>-niv3.png, steve.png
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lire, ecrirePng, PARTIES, texels } from "../../fruits/generateur/moteur.mjs";
import { tenue } from "../../minato2/generateur/effets.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const SORTIE = path.join(ICI, "..", "apercu", "skins");
fs.mkdirSync(SORTIE, { recursive: true });

/** RVB → TSV (t en degrés). */
function tsv([r, g, b]) {
  const M = Math.max(r, g, b), m = Math.min(r, g, b), d = M - m;
  let t = 0;
  if (d > 0) t = M === r ? 60 * (((g - b) / d) % 6) : M === g ? 60 * ((b - r) / d + 2) : 60 * ((r - g) / d + 4);
  return [(t + 360) % 360, M ? d / M : 0, M / 255];
}
function rvb([t, s, v]) {
  const c = v * s, x = c * (1 - Math.abs(((t / 60) % 2) - 1)), m = v - c;
  const [r, g, b] = t < 60 ? [c, x, 0] : t < 120 ? [x, c, 0] : t < 180 ? [0, c, x] : t < 240 ? [0, x, c] : t < 300 ? [x, 0, c] : [c, 0, x];
  return [r + m, g + m, b + m].map((k) => Math.round(k * 255));
}

/** Une couleur de la tenue : le jaune (et le crème) passe en rouge, l'orange reste orange, le noir reste noir. */
function rougir(c) {
  const [t, s, v] = tsv(c);
  if (v < 0.3 || s < 0.12) return c; // les traits noirs, les gris
  if (t >= 38 && t <= 75) {
    // le jaune : un rouge vif, plus clair là où le jaune était pâle
    return rvb([358 + 3 * (1 - s), Math.min(1, 0.8 + s * 0.17), Math.min(1, 0.6 + v * 0.3)]);
  }
  if (t < 38) return rvb([t < 12 ? 6 : 24, Math.min(1, s * 1.05), v]); // l'orange garde son orange
  return c;
}

// le jaune des cheveux du mode Kurama (comme ClientKurama.dore)
const CHEVEUX = [[0, [140, 104, 10]], [0.18, [222, 172, 22]], [0.42, [250, 212, 44]], [0.68, [255, 234, 86]], [1, [255, 248, 156]]];
const dore = (c) => {
  const l = (0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]) / 255;
  for (let k = 1; k < CHEVEUX.length; k++) if (l <= CHEVEUX[k][0] || k === CHEVEUX.length - 1) {
    const [a, p] = CHEVEUX[k - 1], [bb, q] = CHEVEUX[k], u = Math.max(0, Math.min(1, (l - a) / (bb - a)));
    return p.map((v, i) => Math.round(v + (q[i] - v) * u));
  }
};
const lumi = (c) => 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2];
/** De la peau : une couleur chaude (plus de rouge que de bleu), ni noire ni blanche. Les cheveux gris ou sombres n'en sont pas. */
const chaud = (c) => {
  const max = Math.max(...c), min = Math.min(...c), l = lumi(c);
  return c[0] - c[2] > 25 && max > 0 && (max - min) / max > 0.15 && l > 60 && l < 235;
};
/** Le blanc d'un œil : très clair et sans couleur. */
const blanc = (c) => lumi(c) >= 245 && Math.max(...c) - Math.min(...c) < 20;

/** Comme ClientMinatoRouge.composer dans le mod : la tenue en rouge, le visage de base, les yeux rouges, les cornes. */
export function niveau3(skin) {
  const base = tenue(skin);
  const px = Buffer.from(base.px);
  const lit = (x, y) => [px[(y * 64 + x) * 4], px[(y * 64 + x) * 4 + 1], px[(y * 64 + x) * 4 + 2]];
  const met = (x, y, c) => px.set(c, (y * 64 + x) * 4);
  for (const [nom, boite, uv1, uv2] of PARTIES(false)) {
    if (nom === "tete") {
      // le visage reprend ses couleurs de base (la peau, le blanc des yeux) ; les cheveux restent jaunes
      for (const t of texels(boite, uv1, 0, 1)) {
        const i = (t.ty * 64 + t.tx) * 4;
        const orig = [skin.px[i], skin.px[i + 1], skin.px[i + 2]];
        const devant = t.tx >= 8 && t.tx < 16 && t.ty >= 10 && t.ty < 16;
        // tout le reste (les mèches, même sur le front) prend le jaune des cheveux du mode
        if (skin.px[i + 3] > 128) px.set([...(chaud(orig) || (devant && blanc(orig)) ? orig : dore(orig)), 255], i);
      }
      continue;
    }
    for (const uv of [uv1, uv2]) for (const t of texels(boite, uv, 0, 1)) {
      const i = (t.ty * 64 + t.tx) * 4;
      if (px[i + 3] < 128) continue;
      px.set(rougir([px[i], px[i + 1], px[i + 2]]), i);
    }
  }
  // les yeux, d'après le skin : le blanc, et la pupille à côté (ni blanche ni peau) qui passe en rouge
  const ROUGE = [255, 52, 36, 255];
  let haut = 99, bas = -1, gauche = [99, -1], droite = [99, -1];
  for (let y = 10; y <= 15; y++) for (let x = 8; x <= 15; x++) {
    const orig = (xx) => [skin.px[(y * 64 + xx) * 4], skin.px[(y * 64 + xx) * 4 + 1], skin.px[(y * 64 + xx) * 4 + 2]];
    if (!blanc(orig(x))) continue;
    haut = Math.min(haut, y); bas = Math.max(bas, y);
    const cote = x < 12 ? gauche : droite;
    cote[0] = Math.min(cote[0], x); cote[1] = Math.max(cote[1], x);
    for (const dx of [-1, 1]) {
      const xx = x + dx;
      if (xx >= 8 && xx <= 15 && (xx < 12) === (x < 12) && !blanc(orig(xx)) && (!chaud(orig(xx)) || lumi(orig(xx)) < 100)) {
        met(xx, y, ROUGE);
        cote[0] = Math.min(cote[0], xx); cote[1] = Math.max(cote[1], xx);
      }
    }
  }
  if (bas < 0) { haut = bas = 12; met(10, 12, ROUGE); met(13, 12, ROUGE); gauche = [9, 10]; droite = [13, 14]; }
  if (gauche[1] < 0) gauche = [9, 10];
  if (droite[1] < 0) droite = [13, 14];
  // les cornes peintes : le contour des yeux, qui remonte par les bords du visage jusqu'aux coins du haut
  const CLAIR = [218, 24, 14, 255], SOMBRE = [170, 12, 8, 255], TRAIT = [118, 8, 4, 255];
  const cornes = [];
  for (let y = 8; y <= Math.min(15, bas + 1); y++) { cornes.push([8, y, y >= haut ? SOMBRE : CLAIR]); cornes.push([15, y, y >= haut ? SOMBRE : CLAIR]); }
  if (haut - 2 >= 8) { cornes.push([gauche[0], haut - 2, CLAIR]); cornes.push([droite[1], haut - 2, CLAIR]); }
  if (haut - 1 >= 8) {
    for (let x = gauche[0]; x <= gauche[1]; x++) cornes.push([x, haut - 1, TRAIT]);
    for (let x = droite[0]; x <= droite[1]; x++) cornes.push([x, haut - 1, TRAIT]);
  }
  if (bas + 1 <= 15) { cornes.push([gauche[0], bas + 1, SOMBRE]); cornes.push([droite[1], bas + 1, SOMBRE]); }
  for (const [x, y, c] of cornes) { met(x, y, c); px[(y * 64 + x + 32) * 4 + 3] = 0; }
  // le capuchon rouge sur les côtés de la tête (seconde couche), et son bord sur le dessus
  const R = [214, 22, 14, 255], R2 = [168, 12, 8, 255];
  for (let y = 8; y <= 13; y++) for (let x = 0; x < 8; x++) { met(32 + x, y, y >= 12 ? R2 : R); met(48 + x, y, y >= 12 ? R2 : R); }
  for (let y = 0; y < 8; y++) { met(40, y, R); met(47, y, R); }
  return { w: 64, h: 64, px };
}

const PRINCIPAL = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (PRINCIPAL) {
  for (const nom of ["moi", "kai", "efe", "steve"]) {
    const skin = lire(nom === "moi" ? path.join(ICI, "skins", "moi.png") : path.join(ICI, "..", "..", "fruits", "generateur", "skins", nom + ".png"));
    ecrirePng(path.join(SORTIE, nom + ".png"), 64, 64, skin.px);
    ecrirePng(path.join(SORTIE, nom + "-niv2.png"), 64, 64, tenue(skin).px);
    ecrirePng(path.join(SORTIE, nom + "-niv3.png"), 64, 64, niveau3(skin).px);
  }
  console.log("ok", SORTIE);
}
