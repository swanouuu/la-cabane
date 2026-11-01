// Les skins des personnages principaux de Beauvais (format joueur 64 x 64), plus détaillés que peaux.mjs :
// ombrage, deuxième couche en relief (barbes, chapeaux, capes), plusieurs versions par personnage (a, b, c).
// La version utilisée en jeu est copiée sous le nom du personnage (CHOIX, en bas). Un aperçu de toutes les
// versions est écrit dans docs/skins.png pour choisir.
// Lancer depuis n'importe où :  node mod-cabane/outils/persos.mjs
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";
import { skinsPerso } from "./skins-perso.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOSSIER = path.join(ICI, "..", "src", "main", "resources", "assets", "cabane", "textures", "entity", "pnj");

// ---------------------------------------------------------------- couleurs
const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16), 255];
const fois = (c, k) => [Math.min(255, Math.round(c[0] * k)), Math.min(255, Math.round(c[1] * k)), Math.min(255, Math.round(c[2] * k)), c[3]];
const mel = (a, b, t) => [0, 1, 2].map((i) => Math.round(a[i] + (b[i] - a[i]) * t)).concat(255);
const grain = (x, y, graine = 0) => (((x * 73856093) ^ (y * 19349663) ^ (graine * 83492791)) >>> 0) % 1000 / 1000;
/** Ombrage : face avant éclairée, côtés un peu plus sombres, dos encore plus, léger dégradé vers le bas, grain. */
function ombre(c, face, y, h, gr = 0.05) {
  if (!c) return null;
  const k = { avant: 1, droite: 0.86, gauche: 0.86, arriere: 0.76, haut: 1.08, bas: 0.66 }[face] ?? 1;
  const v = 1.05 - 0.1 * (y / Math.max(1, h - 1));
  return fois(c, k * v * (1 + (grain(y * 7 + 3, face.length * 11 + y) - 0.5) * gr));
}

// ---------------------------------------------------------------- l'image et ses boîtes
function image() {
  const px = Buffer.alloc(64 * 64 * 4);
  return {
    px,
    set(x, y, c) { if (x < 0 || y < 0 || x > 63 || y > 63 || !c) return; const o = (y * 64 + x) * 4; px[o] = c[0]; px[o + 1] = c[1]; px[o + 2] = c[2]; px[o + 3] = c[3] ?? 255; },
    get(x, y) { const o = (y * 64 + x) * 4; return px[o + 3] ? [px[o], px[o + 1], px[o + 2], 255] : null; },
  };
}
function boite(img, ox, oy, w, h, d, f) {
  const zone = (x0, y0, lw, lh, face) => { for (let y = 0; y < lh; y++) for (let x = 0; x < lw; x++) img.set(x0 + x, y0 + y, f(face, x, y)); };
  zone(ox + d, oy, w, d, "haut");
  zone(ox + d + w, oy, w, d, "bas");
  zone(ox, oy + d, d, h, "droite");
  zone(ox + d, oy + d, w, h, "avant");
  zone(ox + d + w, oy + d, d, h, "gauche");
  zone(ox + 2 * d + w, oy + d, w, h, "arriere");
}
// les zones du skin : base, puis deuxième couche
const Z = {
  tete: [0, 0, 8, 8, 8], chapeau: [32, 0, 8, 8, 8],
  corps: [16, 16, 8, 12, 4], veste: [16, 32, 8, 12, 4],
  brasD: [40, 16, 4, 12, 4], mancheD: [40, 32, 4, 12, 4], brasG: [32, 48, 4, 12, 4], mancheG: [48, 48, 4, 12, 4],
  jambeD: [0, 16, 4, 12, 4], jambeDb: [0, 32, 4, 12, 4], jambeG: [16, 48, 4, 12, 4], jambeGb: [0, 48, 4, 12, 4],
};
const peindre = (img, zone, f) => { const [ox, oy, w, h, d] = Z[zone]; boite(img, ox, oy, w, h, d, f); };

// ---------------------------------------------------------------- la tête
/**
 * t : teint, cheveux, coupe (court, long, chauve, degarni, pics, tresses, chignon, crete), yeux, sourcils
 * (couleur), froncé, barbe ({couleur, style: longue|courte|pointue|tresses|bouc|moustache|favoris}), rides,
 * defenses (orques), levres, joues, lunettes, cicatrice.
 */
function tete(img, t) {
  const peau = hex(t.teint), cheveux = t.cheveux ? hex(t.cheveux) : peau;
  const yeux = hex(t.yeux ?? "#3b2a1a"), sourcils = t.sourcils ? hex(t.sourcils) : fois(cheveux, 0.8);
  const barbe = t.barbe ? hex(t.barbe.couleur ?? t.cheveux) : null;
  const coupe = t.coupe ?? "court";
  peindre(img, "tete", (face, x, y) => {
    const base = (c) => ombre(c, face, y, 8, 0.04);
    if (face === "haut") return coupe === "chauve" || coupe === "degarni" ? base(peau) : base(cheveux);
    if (face === "bas") return barbe && t.barbe.style !== "moustache" && t.barbe.style !== "bouc" ? base(barbe) : base(fois(peau, 0.85));
    if (face === "arriere") {
      const lim = { long: 8, tresses: 8, chignon: 6, chauve: 0, degarni: 3, pics: 7, crete: 0 }[coupe] ?? 5;
      if (coupe === "crete") return x >= 3 && x <= 4 ? base(cheveux) : base(peau);
      return y < lim ? base(cheveux) : base(peau);
    }
    if (face === "droite" || face === "gauche") {
      const ext = face === "droite" ? x : 7 - x; // 0 = vers l'arrière
      if (coupe === "chauve") return base(peau);
      if (coupe === "crete") return base(peau);
      if (coupe === "degarni") return y >= 2 && y <= 4 && ext < 5 ? base(cheveux) : base(peau);
      if ((coupe === "long" || coupe === "tresses") && ext < 6) return base(cheveux);
      if (y < 2 || (y < 4 && ext < 5) || (coupe === "pics" && y < 3)) return base(cheveux);
      if (barbe && t.barbe.style !== "moustache" && t.barbe.style !== "bouc" && y >= 4 && ext >= 4) return base(barbe);
      if (t.oreilles && y === 3 && ext === 3) return fois(peau, 0.75);
      return base(peau);
    }
    // ---- le visage
    let c = peau;
    if (coupe === "pics") { if (y === 0 || (y === 1 && x >= 1 && x <= 6) || (y === 2 && (x === 3 || x === 4))) c = cheveux; }
    else if (coupe === "crete") { if (y === 0 && x >= 3 && x <= 4) c = cheveux; }
    else if (coupe === "degarni") { if (y === 0 && (x === 0 || x === 7)) c = cheveux; }
    else if (coupe !== "chauve") {
      if (y === 0 || (y === 1 && (x < 2 || x > 5 || coupe === "long" || coupe === "tresses"))) c = cheveux;
      if ((coupe === "long" || coupe === "tresses") && (x === 0 || x === 7) && y < 7) c = cheveux;
    }
    if (y === 2 && (x === 1 || x === 2 || x === 5 || x === 6)) c = sourcils;
    if (t.fronce && y === 2 && (x === 3 || x === 4)) c = fois(peau, 0.8);
    if (t.fronce && y === 3 && (x === 1 || x === 6)) c = sourcils;
    if (y === 3) {
      if (x === 1 || x === 6) c = t.fronce ? c : hex("#f4f4f4");
      if (x === 2 || x === 5) c = yeux;
    }
    if (t.lunettes && y === 3 && (x <= 3 || x >= 4)) c = x === 0 || x === 7 || x === 3 || x === 4 ? hex(t.lunettes) : c;
    if (t.lunettes === "#101014" && y === 3 && x >= 1 && x <= 6) c = hex("#101014");
    if (y === 4 && (x === 3 || x === 4)) c = fois(peau, 0.86); // le nez
    if (t.joues && y === 4 && (x === 1 || x === 6)) c = hex(t.joues);
    if (t.rides && ((y === 1 && (x === 2 || x === 5) && coupe !== "pics") || (y === 5 && (x === 1 || x === 6)))) c = fois(peau, 0.82);
    if (y === 5 && (x === 3 || x === 4)) c = t.levres ? hex(t.levres) : fois(peau, 0.72);
    if (t.cicatrice && x === 5 && y >= 2 && y <= 5) c = hex("#c9897a");
    if (barbe) {
      const s = t.barbe.style ?? "courte";
      if (s === "moustache" && y === 5 && x >= 1 && x <= 6) c = barbe;
      else if (s === "bouc" && ((y === 5 && x >= 2 && x <= 5) || (y >= 6 && x >= 3 && x <= 4))) c = barbe;
      else if (s === "favoris" && (x === 0 || x === 7) && y >= 3) c = barbe;
      else if (s !== "moustache" && s !== "bouc" && s !== "favoris") {
        if (y === 5 && (x <= 2 || x >= 5)) c = barbe;
        if (y >= 6) c = barbe;
        if (y === 5 && (x === 3 || x === 4) && s !== "courte") c = fois(barbe, 0.85);
        if (y === 6 && (x === 3 || x === 4)) c = fois(barbe, 0.7); // la bouche dans la barbe
        if ((x === 0 || x === 7) && y >= 4) c = barbe;
      }
    }
    if (t.defenses && y === 6 && (x === 2 || x === 5)) c = hex("#f2ecd2");
    if (t.defenses && y === 5 && (x === 2 || x === 5)) c = hex("#f2ecd2");
    return ombre(c, "avant", y, 8, 0.05);
  });
  // la barbe en relief (deuxième couche) : longue, pointue ou tressée
  if (barbe && ["longue", "pointue", "tresses"].includes(t.barbe.style)) {
    peindre(img, "chapeau", (face, x, y) => {
      if (face !== "avant" && face !== "droite" && face !== "gauche" && face !== "bas") return null;
      if (face === "bas") return y >= 4 ? ombre(barbe, "bas", 0, 1) : null;
      const ext = face === "droite" ? x : face === "gauche" ? 7 - x : 9;
      if (face !== "avant") return y >= 5 && ext >= 5 ? ombre(barbe, face, y, 8, 0.15) : null;
      if (y < 5) return null;
      if (t.barbe.style === "pointue" && y === 7 && (x < 2 || x > 5)) return null;
      if (y === 5 && (x === 3 || x === 4)) return null;
      return ombre(t.barbe.style === "tresses" && x % 3 === 0 ? fois(barbe, 0.75) : barbe, "avant", y, 8, 0.2);
    });
  }
}

// ---------------------------------------------------------------- les chapeaux (deuxième couche de la tête)
function chapeau(img, style, c1, c2) {
  const a = hex(c1), b = c2 ? hex(c2) : fois(a, 0.7);
  peindre(img, "chapeau", (face, x, y) => {
    const o = (c) => ombre(c, face, y, 8, 0.08);
    const ancien = (() => { // ne pas effacer la barbe en relief
      const off = { droite: [0, 8], avant: [8, 8], gauche: [16, 8], arriere: [24, 8], haut: [8, 0], bas: [16, 0] }[face];
      return img.get(32 + off[0] + x, off[1] + y);
    })();
    let c = null;
    switch (style) {
      case "turban": if (face === "haut") c = o((x + y) % 3 === 0 ? b : a); else if (face !== "bas" && y <= 2) c = o((x + y) % 4 === 0 ? b : a); if (face === "avant" && y === 1 && (x === 3 || x === 4)) c = hex("#e0262e"); if (face === "avant" && y === 2 && (x === 3 || x === 4)) c = hex("#ffd75e"); break;
      case "fez": if (face === "haut") c = o(a); else if (face !== "bas" && y <= 1) c = o(a); if (face === "haut" && x === 4 && y === 4) c = hex("#111111"); if (face === "arriere" && x === 4 && y <= 3) c = hex("#111111"); break;
      case "sorcier": if (face === "haut") c = o((x * 3 + y * 5) % 7 === 0 ? b : a); else if (face !== "bas" && y <= 1) c = o(y === 1 ? b : a); break;
      case "capuche": if (face === "haut" || face === "arriere") c = o(a); else if ((face === "droite" || face === "gauche") && (y <= 6)) c = o(a); else if (face === "avant" && (y === 0 || x === 0 || x === 7) && y < 7) c = o(b); break;
      case "casque": if (face === "haut") c = o(a); else if (face !== "bas" && y <= 2) c = o(y === 2 ? b : a); if (face === "avant" && y >= 2 && y <= 4 && (x === 3 || x === 4)) c = o(b); break;
      case "cornes": if (face === "haut") c = o(a); else if (face !== "bas" && y <= 1) c = o(a); if ((face === "droite" || face === "gauche") && y <= 3 && (face === "droite" ? x : 7 - x) >= 2 && (face === "droite" ? x : 7 - x) <= 4) c = o(hex("#efe6cc")); break;
      case "couronne_fleurs": if (face !== "haut" && face !== "bas" && y === 1) c = (x % 3 === 0 ? hex("#ff8fc8") : x % 3 === 1 ? hex("#fff27a") : hex("#7fe08a")); break;
      case "foulard": if (face === "haut") c = o((x + y) % 2 ? a : b); else if (face !== "bas" && y <= 2) c = o((x + y) % 3 === 0 ? b : a); if (face === "arriere" && y <= 4 && x >= 3 && x <= 4) c = o(b); break;
      case "casquette": if (face === "haut") c = o(a); else if (face !== "bas" && y <= 1) c = o(a); if (face === "arriere" && y === 2 && x >= 2 && x <= 5) c = o(b); break;
      case "bandana": if (face !== "haut" && face !== "bas" && y === 1) c = o((x % 2) ? a : b); if (face === "arriere" && y >= 1 && y <= 3 && x >= 3 && x <= 4) c = o(a); break;
      case "pics": // la coiffure de Vegeta : des pics qui montent
        if (face === "haut") c = o(a);
        else if (face === "avant" && (y === 0 || (y === 1 && (x === 0 || x === 7)))) c = o(a);
        else if (face !== "bas" && face !== "avant" && y <= 3) c = o(a);
        break;
      default: break;
    }
    return c ?? ancien;
  });
}

// ---------------------------------------------------------------- le corps, les bras, les jambes
/** v : fonction (face, x, y) → couleur pour le corps (8 x 12), avec ombrage automatique. */
function corps(img, v, couche = "corps") { peindre(img, couche, (face, x, y) => { const c = v(face, x, y); return c ? ombre(c, face, y, 12) : null; }); }
function bras(img, v, couche = "bras") {
  const [d, g] = couche === "bras" ? ["brasD", "brasG"] : ["mancheD", "mancheG"];
  peindre(img, d, (face, x, y) => { const c = v(face === "droite" ? "dehors" : face === "gauche" ? "dedans" : face, x, y, "d"); return c ? ombre(c, face, y, 12) : null; });
  peindre(img, g, (face, x, y) => { const c = v(face === "gauche" ? "dehors" : face === "droite" ? "dedans" : face, x, y, "g"); return c ? ombre(c, face, y, 12) : null; });
}
function jambes(img, v, couche = "jambes") {
  const [d, g] = couche === "jambes" ? ["jambeD", "jambeG"] : ["jambeDb", "jambeGb"];
  peindre(img, d, (face, x, y) => { const c = v(face === "droite" ? "dehors" : face === "gauche" ? "dedans" : face, x, y, "d"); return c ? ombre(c, face, y, 12) : null; });
  peindre(img, g, (face, x, y) => { const c = v(face === "gauche" ? "dehors" : face === "droite" ? "dedans" : face, x, y, "g"); return c ? ombre(c, face, y, 12) : null; });
}
/** Des mains nues au bout des manches (ou des gants). */
const mains = (peau, manche, finManche = 10, gant) => (face, x, y) => (face === "bas" ? peau : y >= finManche ? (gant ?? peau) : manche(face, x, y));

// ---------------------------------------------------------------- écriture, aperçu
function png(w, h, px) {
  const brut = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) { brut[y * (1 + w * 4)] = 0; px.copy(brut, y * (1 + w * 4) + 1, y * w * 4, (y + 1) * w * 4); }
  const table = Array.from({ length: 256 }, (_, k) => { let c = k; for (let j = 0; j < 8; j++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (b) => { let c = 0xffffffff; for (const v of b) c = table[(c ^ v) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (t, d) => { const tb = Buffer.from(t), l = Buffer.alloc(4), cc = Buffer.alloc(4); l.writeUInt32BE(d.length); cc.writeUInt32BE(crc(Buffer.concat([tb, d]))); return Buffer.concat([l, tb, d, cc]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

const SKINS = {};
function skin(nom, dessin) {
  const img = image();
  dessin(img);
  SKINS[nom] = img;
  fs.mkdirSync(path.join(DOSSIER, "versions"), { recursive: true });
  fs.writeFileSync(path.join(DOSSIER, "versions", nom + ".png"), png(64, 64, img.px));
}

// ================================================================ les personnages

// ---- le Hejj : le sage du village
for (const [v, o] of Object.entries({
  a: { robe: "#2f5d3a", bord: "#d4af37", chapeau: ["turban", "#efe6cc", "#c9b98a"], teint: "#b07a4f" },
  b: { robe: "#5a3a22", bord: "#c9a227", chapeau: ["capuche", "#4a2e1a", "#2e1c10"], teint: "#c48c5e" },
  c: { robe: "#7a1f2b", bord: "#e8c04a", chapeau: ["foulard", "#f4efe2", "#b8322e"], teint: "#8d5a3b" },
})) {
  skin("hejj_" + v, (img) => {
    tete(img, { teint: o.teint, cheveux: "#e8e8e4", coupe: "degarni", sourcils: "#f4f4f0", yeux: "#3a5a7a", rides: true, barbe: { couleur: "#f2f2ee", style: "longue" } });
    chapeau(img, ...o.chapeau);
    const robe = hex(o.robe), bord = hex(o.bord), corde = hex("#a0784a"), barbe = hex("#f2f2ee");
    corps(img, (f, x, y) => {
      if (f === "avant" && y <= 3 && x >= 2 && x <= 5) return barbe; // la barbe descend sur la poitrine
      if (f === "avant" && (x === 3 || x === 4)) return bord;
      if (y === 7) return corde;
      return robe;
    });
    bras(img, mains(hex(o.teint), (f, x, y) => (y >= 8 ? bord : robe), 10));
    jambes(img, (f, x, y) => (y >= 11 ? hex("#5a3a1e") : y === 10 ? bord : robe));
  });
}

// ---- Karim le Chouf : un mage à grande barbe
for (const [v, o] of Object.entries({
  a: { robe: "#1f3a8a", motif: "#ffd75e", chapeau: ["sorcier", "#1a2f6e", "#ffd75e"], barbe: "#cfcfcf" },
  b: { robe: "#4b1f6e", motif: "#d9e2ff", chapeau: ["sorcier", "#3b1858", "#c9d3f2"], barbe: "#e8e8e8" },
  c: { robe: "#2d5a3a", motif: "#e8c04a", chapeau: ["foulard", "#8a5a2b", "#e8c04a"], barbe: "#9a9a9a" },
})) {
  skin("karim_" + v, (img) => {
    tete(img, { teint: "#d9a57a", cheveux: o.barbe, coupe: "long", sourcils: o.barbe, yeux: "#2a6bd0", rides: true, barbe: { couleur: o.barbe, style: "pointue" } });
    chapeau(img, ...o.chapeau);
    const robe = hex(o.robe), motif = hex(o.motif), barbe = hex(o.barbe);
    const etoile = (x, y) => (x * 5 + y * 3) % 11 === 0;
    corps(img, (f, x, y) => {
      if (f === "avant" && y <= 4 && x >= 2 && x <= 5) return barbe;
      if (y === 6) return hex("#5a3a1e");
      if (f === "avant" && y === 6 && x === 5) return motif;
      return etoile(x, y) ? motif : robe;
    });
    corps(img, (f, x, y) => (f === "avant" && y === 7 && x === 6 ? hex("#e8d9a0") : f === "dehors" ? null : null), "veste");
    bras(img, mains(hex("#d9a57a"), (f, x, y) => (y === 9 ? motif : etoile(x + 3, y) ? motif : robe), 10));
    jambes(img, (f, x, y) => (y >= 11 ? hex("#3a2a1a") : etoile(x + 1, y + 2) ? motif : robe));
  });
}

// ---- Mouloud le Marchand : fez, moustache, gilet brodé
for (const [v, o] of Object.entries({
  a: { chapeau: ["fez", "#b3202a"], gilet: "#7a1f2b", chemise: "#f2ead3", pantalon: "#5a4632" },
  b: { chapeau: ["turban", "#2f6fb0", "#ffd75e"], gilet: "#1f4f7a", chemise: "#efe6cc", pantalon: "#3a3a42" },
  c: { chapeau: ["casquette", "#2d5a3a", "#1c3a24"], gilet: "#8a5a2b", chemise: "#d9e2c0", pantalon: "#4a3a2a" },
})) {
  skin("mouloud_" + v, (img) => {
    tete(img, { teint: "#c99366", cheveux: "#1d1410", coupe: "court", yeux: "#3b2a1a", barbe: { couleur: "#1d1410", style: "moustache" }, joues: "#d98a6a" });
    chapeau(img, ...o.chapeau);
    const gilet = hex(o.gilet), chemise = hex(o.chemise), or = hex("#e8c04a");
    corps(img, (f, x, y) => {
      if (f === "avant" && x >= 2 && x <= 5) return y === 6 ? or : chemise;
      if (f === "avant" && (x === 1 || x === 6) && y % 3 === 1) return or; // broderies
      if (y >= 9) return hex("#3a2a1a");
      return gilet;
    });
    corps(img, (f, x, y) => (f === "avant" && y === 2 && x >= 2 && x <= 5 ? or : f === "avant" && y === 3 && x === 3 ? or : null), "veste");
    bras(img, mains(hex("#c99366"), () => chemise, 10));
    jambes(img, (f, x, y) => (y >= 11 ? hex("#8a3a1a") : hex(o.pantalon)));
  });
}

// ---- Gribbs, le cousin louche
skin("gribbs_a", (img) => {
  tete(img, { teint: "#b98a5e", cheveux: "#2a1d14", coupe: "court", lunettes: "#101014", barbe: { couleur: "#2a1d14", style: "bouc" } });
  chapeau(img, "casquette", "#d4202a", "#ffffff");
  const surv = hex("#2a2a6e"), bande = hex("#f4f4f4"), or = hex("#e8c04a");
  corps(img, (f, x, y) => {
    if (f === "avant" && y <= 4 && x >= 3 && x <= 4) return hex("#b98a5e");
    if (f === "avant" && y === 4 && x >= 2 && x <= 5) return or;
    if ((f === "droite" || f === "gauche") && x === 1) return bande;
    return surv;
  });
  bras(img, mains(hex("#b98a5e"), (f, x) => (f === "dehors" && x === 1 ? bande : surv), 10));
  jambes(img, (f, x, y) => (y >= 11 ? hex("#f4f4f4") : f === "dehors" && x === 1 ? bande : surv));
});

// ---- Mehdi de Pôle emploi : chemise, cravate, badge, lunettes
skin("mehdi_a", (img) => {
  tete(img, { teint: "#c99366", cheveux: "#1d1410", coupe: "court", lunettes: "#2a2a30", barbe: { couleur: "#1d1410", style: "courte" } });
  const chemise = hex("#dfe9f5"), cravate = hex("#1f3a8a"), pantalon = hex("#3a3a42");
  corps(img, (f, x, y) => {
    if (f === "avant" && (x === 3 || x === 4) && y <= 8) return y === 0 ? hex("#14286a") : cravate;
    if (f === "avant" && x === 6 && y >= 3 && y <= 5) return y === 3 ? hex("#e04040") : hex("#ffffff"); // le badge
    if (f === "avant" && x === 6 && y <= 2) return hex("#e04040"); // le cordon
    if (y >= 10) return hex("#1c1c22");
    return chemise;
  });
  bras(img, mains(hex("#c99366"), () => chemise, 10));
  jambes(img, (f, x, y) => (y >= 11 ? hex("#141414") : pantalon));
});

// ---- Brokk, l'ogre forgeron : peau verte, défenses, tablier de cuir, bras nus
for (const [v, o] of Object.entries({
  a: { teint: "#6f8a4a", tablier: "#5a3a22", chapeau: null, barbe: null },
  b: { teint: "#8a5a3a", tablier: "#3a2a1a", chapeau: ["cornes", "#5a5a62"], barbe: { couleur: "#2a1a10", style: "tresses" } },
  c: { teint: "#5a6a7a", tablier: "#6a2a1a", chapeau: ["casque", "#4a4a52", "#2a2a30"], barbe: null },
})) {
  skin("brokk_" + v, (img) => {
    tete(img, { teint: o.teint, cheveux: "#1a1410", coupe: "chauve", sourcils: "#1a1410", yeux: "#d4202a", fronce: true, defenses: true, cicatrice: true, barbe: o.barbe });
    if (o.chapeau) chapeau(img, ...o.chapeau);
    const peau = hex(o.teint), tablier = hex(o.tablier), metal = hex("#8a8a94");
    corps(img, (f, x, y) => {
      if (f === "avant" && y >= 1) return y === 6 ? hex("#2a1a10") : (x + y) % 5 === 0 ? fois(tablier, 0.8) : tablier;
      if (y === 6) return hex("#2a1a10");
      return (x * 3 + y) % 6 === 0 ? fois(peau, 0.85) : peau;
    });
    corps(img, (f, x, y) => (f === "avant" && y === 7 && (x === 1 || x === 6) ? metal : null), "veste");
    bras(img, (f, x, y) => (y >= 7 && y <= 9 ? (x % 2 ? metal : fois(metal, 0.8)) : y >= 10 ? fois(peau, 0.9) : (x + y) % 7 === 0 ? fois(peau, 0.85) : peau));
    jambes(img, (f, x, y) => (y >= 10 ? hex("#2a1a10") : hex("#4a3a2a")));
  });
}

// ---- les orques de la forge
for (const [nom, o] of Object.entries({
  orque_1: { teint: "#4f7a3a", coupe: "crete", cheveux: "#1a1a1a", tablier: "#4a3020", bandeau: null },
  orque_2: { teint: "#7a8a4a", coupe: "chauve", cheveux: "#2a2a1a", tablier: "#3a3a42", bandeau: ["bandana", "#b3202a", "#7a141c"] },
  orque_3: { teint: "#5f8a5a", coupe: "tresses", cheveux: "#3a2414", tablier: "#6a3a1a", bandeau: null },
})) {
  skin(nom, (img) => {
    tete(img, { teint: o.teint, cheveux: o.cheveux, coupe: o.coupe, yeux: "#ffcc33", fronce: nom !== "orque_3", defenses: true, levres: nom === "orque_3" ? "#3a5a2a" : null });
    if (o.bandeau) chapeau(img, ...o.bandeau);
    const peau = hex(o.teint), tablier = hex(o.tablier);
    corps(img, (f, x, y) => (f === "avant" && y >= 2 ? tablier : y === 7 ? hex("#2a1a10") : nom === "orque_3" && y <= 4 ? hex("#6a4a2a") : peau));
    bras(img, (f, x, y) => (y >= 9 ? hex("#3a2a1a") : peau));
    jambes(img, (f, x, y) => (y >= 10 ? hex("#2a1a10") : hex("#4a3a2a")));
  });
}

// ---- Ovsie la Fée : cheveux lilas, couronne de fleurs, robe dégradée, ailes dans le dos
for (const [v, o] of Object.entries({
  a: { cheveux: "#c9a0ff", haut: "#ff8fc8", bas: "#8a5aff", ailes: "#a8e8ff" },
  b: { cheveux: "#7fe0c8", haut: "#9affd0", bas: "#2aa0a0", ailes: "#ffe8a8" },
  c: { cheveux: "#ffe27a", haut: "#ffd0e8", bas: "#ff8a5a", ailes: "#ffffff" },
})) {
  skin("ovsie_" + v, (img) => {
    tete(img, { teint: "#f6d8c4", cheveux: o.cheveux, coupe: "long", yeux: "#9a3aff", joues: "#ff9ab0", levres: "#e0607a" });
    chapeau(img, "couronne_fleurs", "#ff8fc8");
    const haut = hex(o.haut), bas = hex(o.bas), ailes = hex(o.ailes), paillette = hex("#ffffff");
    corps(img, (f, x, y) => {
      const c = mel(haut, bas, y / 11);
      return (x * 7 + y * 5) % 13 === 0 ? paillette : c;
    });
    corps(img, (f, x, y) => (f === "arriere" && y >= 1 && y <= 8 && (x <= 2 || x >= 5) && !(y > 6 && (x === 0 || x === 7)) ? (y % 3 === 0 ? fois(ailes, 0.85) : ailes) : null), "veste");
    bras(img, mains(hex("#f6d8c4"), () => haut, 4));
    jambes(img, (f, x, y) => (y >= 11 ? hex("#ff8fc8") : y < 4 ? bas : hex("#f6d8c4")));
  });
}

// ---- Nyota la Guérisseuse : foulard en wax, robe colorée, collier de coquillages
for (const [v, o] of Object.entries({
  a: { foulard: ["foulard", "#e05a1a", "#ffd23a"], robe1: "#e05a1a", robe2: "#2a6bd0", robe3: "#ffd23a" },
  b: { foulard: ["foulard", "#2a8a4a", "#ffd23a"], robe1: "#2a8a4a", robe2: "#7a1f2b", robe3: "#f2ead3" },
})) {
  skin("nyota_" + v, (img) => {
    tete(img, { teint: "#6b4028", cheveux: "#140c08", coupe: "tresses", yeux: "#2a1a10", levres: "#4a2418" });
    chapeau(img, ...o.foulard);
    const a = hex(o.robe1), b = hex(o.robe2), c = hex(o.robe3), coquillage = hex("#f4ecd8");
    const wax = (x, y) => { const u = x >> 1, w = y >> 1; return (u + w) % 3 === 0 ? c : (u * 2 + w) % 5 === 0 ? b : a; };
    corps(img, (f, x, y) => (f === "avant" && y === 1 && x >= 1 && x <= 6 ? (x % 2 ? coquillage : hex("#3a8a3a")) : wax(x, y)));
    bras(img, mains(hex("#6b4028"), (f, x, y) => wax(x + 2, y), 5));
    jambes(img, (f, x, y) => (y >= 11 ? hex("#5a3a1e") : y < 8 ? wax(x + 1, y) : hex("#6b4028")));
  });
}

// ---- les gardes de la grande porte : casque d'acier, cotte de mailles, tabard vert et or à l'arbre de Beauvais
for (const [v, o] of Object.entries({ garde_porte_1: { teint: "#c48c5e", plume: false }, garde_porte_2: { teint: "#8d5a3b", plume: true } })) {
  skin(v, (img) => {
    tete(img, { teint: o.teint, cheveux: "#2a1d14", coupe: "court", fronce: true, barbe: { couleur: "#2a1d14", style: "courte" } });
    chapeau(img, "casque", "#9aa0a8", "#5a5f66");
    if (o.plume) { img.set(36, 0, hex("#c0182a")); img.set(36, 1, hex("#c0182a")); img.set(37, 0, hex("#e0262e")); }
    const maille = (x, y) => ((x + y) % 2 ? hex("#8a9098") : hex("#6a7078"));
    const vert = hex("#1f5a2e"), or = hex("#e8c04a");
    corps(img, (f, x, y) => {
      if ((f === "avant" || f === "arriere") && x >= 1 && x <= 6 && y <= 10) {
        if (y === 3 && x >= 3 && x <= 4) return or; // l'arbre : le feuillage
        if (y === 4 && x >= 2 && x <= 5) return or;
        if (y >= 5 && y <= 6 && (x === 3 || x === 4)) return hex("#8a5a2b"); // le tronc
        return x === 1 || x === 6 ? or : vert;
      }
      return y === 7 ? hex("#3a2414") : maille(x, y);
    });
    bras(img, mains(hex(o.teint), (f, x, y) => (y >= 8 ? hex("#3a2414") : maille(x, y)), 10, hex("#3a2414")));
    jambes(img, (f, x, y) => (y >= 8 ? hex("#3a2414") : maille(x, y)));
  });
}

// ---- Vegeta : cheveux en pics, combinaison bleue, armure blanche aux épaulettes jaunes, gants et bottes blancs
skin("vegeta_a", (img) => {
  tete(img, { teint: "#f0c8a0", cheveux: "#121218", coupe: "pics", sourcils: "#121218", yeux: "#121218", fronce: true });
  chapeau(img, "pics", "#121218");
  const bleu = hex("#1f2f8a"), blanc = hex("#f4f4f0"), jaune = hex("#e8b828"), peau = hex("#f0c8a0");
  corps(img, (f, x, y) => {
    if (y <= 6) {
      if ((f === "avant" || f === "arriere") && (x === 0 || x === 7) && y <= 1) return jaune;
      if (f === "haut") return jaune;
      if ((f === "droite" || f === "gauche") && y <= 1) return jaune;
      return y === 6 ? fois(blanc, 0.85) : blanc;
    }
    return bleu;
  });
  corps(img, (f, x, y) => ((f === "droite" || f === "gauche") && y <= 1 ? jaune : null), "veste");
  bras(img, (f, x, y) => (y <= 1 ? jaune : y >= 8 ? blanc : bleu));
  jambes(img, (f, x, y) => (y >= 8 ? (y === 8 ? jaune : blanc) : bleu));
});

// ---- Marcel le Conteur : vieil homme, chapeau de paille, moustache blanche, poncho rayé
skin("conteur", (img) => {
  tete(img, { teint: "#c48c5e", cheveux: "#e4e4e0", coupe: "degarni", sourcils: "#f0f0ec", yeux: "#5a3a1e", rides: true, barbe: { couleur: "#f0f0ec", style: "moustache" } });
  chapeau(img, "turban", "#d9b866", "#a8873a");
  const rayures = [hex("#b3322e"), hex("#e8c04a"), hex("#2f6b3a"), hex("#f2ead3")];
  corps(img, (f, x, y) => (y >= 9 ? hex("#5a4632") : rayures[(y >> 1) % 4]));
  bras(img, mains(hex("#c48c5e"), (f, x, y) => rayures[(y >> 1) % 4], 7));
  jambes(img, (f, x, y) => (y >= 11 ? hex("#4a3020") : hex("#6a5236")));
  // la pipe, au coin de la bouche
  img.set(14, 14, hex("#3a2414")); img.set(15, 14, hex("#3a2414"));
});
// ---- Pierre, le disparu : vêtements déchirés, barbe de plusieurs semaines
skin("prisonnier", (img) => {
  tete(img, { teint: "#d9a57a", cheveux: "#3a2a1a", coupe: "long", yeux: "#3b2a1a", rides: true, barbe: { couleur: "#3a2a1a", style: "courte" } });
  const toile = hex("#8a8270"), sale = hex("#5f584a");
  corps(img, (f, x, y) => ((x * 3 + y * 5) % 7 === 0 ? hex("#d9a57a") : (x + y) % 4 === 0 ? sale : toile));
  bras(img, mains(hex("#d9a57a"), (f, x, y) => (y >= 6 && (x + y) % 3 === 0 ? hex("#d9a57a") : toile), 8));
  jambes(img, (f, x, y) => (y >= 10 ? hex("#d9a57a") : (x + y) % 5 === 0 ? sale : hex("#6a6250")));
});

// ---- le Fourgue, le dealer : capuche noire, survêt gris, sacoche en bandoulière
skin("fourgue", (img) => {
  tete(img, { teint: "#a8754e", cheveux: "#151210", coupe: "court", yeux: "#2a1a10", barbe: { couleur: "#151210", style: "courte" } });
  chapeau(img, "capuche", "#1c1c20", "#2c2c32");
  const sweat = hex("#1c1c20"), gris = hex("#5a5a60"), sacoche = hex("#3a2a1a");
  corps(img, (f, x, y) => {
    if (f === "avant" && x + y === 7) return sacoche; // la bandoulière
    if (f === "avant" && y >= 8 && y <= 10 && x >= 1 && x <= 3) return sacoche;
    if (f === "avant" && y === 6 && x >= 2 && x <= 5) return fois(sweat, 1.4); // la poche kangourou
    return sweat;
  }, "veste");
  corps(img, () => sweat);
  bras(img, mains(hex("#a8754e"), () => sweat, 11));
  jambes(img, (f, x, y) => (y >= 11 ? hex("#f2f2f2") : (f === "dehors" && x === 1) ? hex("#f2f2f2") : gris));
});

// ================================================================ les choix (la version utilisée en jeu)
const CHOIX = { hejj: "hejj_a", karim: "karim_a", mouloud: "mouloud_a", gribbs: "gribbs_a", mehdi: "mehdi_a", brokk: "brokk_a",
  ovsie: "ovsie_a", nyota: "nyota_a" };
for (const [nom, version] of Object.entries(CHOIX)) {
  fs.copyFileSync(path.join(DOSSIER, "versions", version + ".png"), path.join(DOSSIER, nom + ".png"));
}
for (const nom of ["orque_1", "orque_2", "orque_3", "garde_porte_1", "garde_porte_2", "conteur", "prisonnier", "fourgue"]) {
  fs.copyFileSync(path.join(DOSSIER, "versions", nom + ".png"), path.join(DOSSIER, nom + ".png"));
}

// ================================================================ l'aperçu : chaque version de face et de dos, avec son nom
const POLICE = { // une petite police 3 x 5
  A: "010101111101101", B: "110101110101110", C: "011100100100011", D: "110101101101110", E: "111100110100111", F: "111100110100100",
  G: "011100101101011", H: "101101111101101", I: "111010010010111", J: "001001001101010", K: "101101110101101", L: "100100100100111",
  M: "101111111101101", N: "110101101101101", O: "010101101101010", P: "110101110100100", Q: "010101101110011", R: "110101110101101",
  S: "011100010001110", T: "111010010010010", U: "101101101101111", V: "101101101101010", W: "101101111111101", X: "101101010101101",
  Y: "101101010010010", Z: "111001010100111", _: "000000000000111", " ": "000000000000000",
  0: "111101101101111", 1: "010110010010111", 2: "110001010100111", 3: "110001010001110",
};
function apercu(noms, fichier) {
  const E = 6, LW = 16 * E, LH = 32 * E;
  const W = noms.length * (2 * LW + 30) + 20, H = LH + 70;
  const out = Buffer.alloc(W * H * 4);
  for (let i = 0; i < W * H; i++) { const c = 30 + (((i % W) >> 4) + ((i / W | 0) >> 4)) % 2 * 8; out[i * 4] = c; out[i * 4 + 1] = c + 2; out[i * 4 + 2] = c + 6; out[i * 4 + 3] = 255; }
  const pixel = (x, y, c) => { if (x < 0 || y < 0 || x >= W || y >= H || !c) return; const o = (y * W + x) * 4; out[o] = c[0]; out[o + 1] = c[1]; out[o + 2] = c[2]; };
  const bloc = (x0, y0, c) => { for (let dy = 0; dy < E; dy++) for (let dx = 0; dx < E; dx++) pixel(x0 + dx, y0 + dy, c); };
  // les faces avant / arrière de chaque partie, puis la deuxième couche par-dessus
  const vues = {
    avant: [["tete", 8, 8, 4, 0], ["corps", 20, 20, 4, 8], ["brasD", 44, 20, 0, 8], ["brasG", 36, 52, 12, 8], ["jambeD", 4, 20, 4, 20], ["jambeG", 20, 52, 8, 20],
      ["chapeau", 40, 8, 4, 0], ["veste", 20, 36, 4, 8], ["mancheD", 44, 36, 0, 8], ["mancheG", 52, 52, 12, 8], ["jambeDb", 4, 36, 4, 20], ["jambeGb", 4, 52, 8, 20]],
    arriere: [["tete", 24, 8, 4, 0], ["corps", 32, 20, 4, 8], ["brasD", 52, 20, 12, 8], ["brasG", 44, 52, 0, 8], ["jambeD", 12, 20, 8, 20], ["jambeG", 28, 52, 4, 20],
      ["chapeau", 56, 8, 4, 0], ["veste", 32, 36, 4, 8], ["mancheD", 52, 36, 12, 8], ["mancheG", 60, 52, 0, 8], ["jambeDb", 12, 36, 8, 20], ["jambeGb", 12, 52, 4, 20]],
  };
  const taille = { tete: [8, 8], chapeau: [8, 8], corps: [8, 12], veste: [8, 12] };
  noms.forEach((nom, i) => {
    const img = SKINS[nom];
    const x0 = 20 + i * (2 * LW + 30);
    for (const [k, vue] of [[0, "avant"], [1, "arriere"]]) {
      for (const [partie, u, v, ox, oy] of vues[vue]) {
        const [w, h] = taille[partie] ?? [4, 12];
        for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) bloc(x0 + k * (LW + 6) + (ox + x) * E, 20 + (oy + y) * E, img.get(u + x, v + y));
      }
    }
    const texte = nom.toUpperCase();
    texte.split("").forEach((ch, j) => {
      const g = POLICE[ch] ?? POLICE[" "];
      for (let gy = 0; gy < 5; gy++) for (let gx = 0; gx < 3; gx++) if (g[gy * 3 + gx] === "1") {
        for (let dy = 0; dy < 4; dy++) for (let dx = 0; dx < 4; dx++) pixel(x0 + j * 16 + gx * 4 + dx, LH + 34 + gy * 4 + dy, [255, 215, 94, 255]);
      }
    });
  });
  fs.mkdirSync(path.dirname(fichier), { recursive: true });
  fs.writeFileSync(fichier, png(W, H, out));
}
const DOCS = path.join(ICI, "..", "..", "docs", "skins");
apercu(["hejj_a", "hejj_b", "hejj_c", "karim_a", "karim_b", "karim_c"], path.join(DOCS, "1-hejj-et-karim.png"));
apercu(["mouloud_a", "mouloud_b", "mouloud_c", "gribbs_a", "mehdi_a", "vegeta_a"], path.join(DOCS, "2-bazar-et-vegeta.png"));
apercu(["brokk_a", "brokk_b", "brokk_c", "orque_1", "orque_2", "orque_3"], path.join(DOCS, "3-forge.png"));
apercu(["ovsie_a", "ovsie_b", "ovsie_c", "nyota_a", "nyota_b", "garde_porte_1", "garde_porte_2"], path.join(DOCS, "4-fee-guerisseuse-gardes.png"));
console.log("Skins écrits dans", DOSSIER, "— aperçus dans", DOCS);

// les skins fournis (Vegeta, Goku) passent après : ils remplacent les versions dessinées ici
skinsPerso();
