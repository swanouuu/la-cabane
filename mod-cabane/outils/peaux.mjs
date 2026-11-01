// Les peaux des habitants de Beauvais (format joueur 64 x 64) : des gens de la jungle, façon Mowgli
// ou George de la jungle, un peu médiévaux (tuniques, ceintures de corde, peaux de bête, plumes).
// Lancer depuis n'importe où :  node mod-cabane/outils/peaux.mjs
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const DOSSIER = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources",
  "assets", "cabane", "textures", "entity", "pnj");

const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16), 255];
const assombrir = (c, k) => [Math.round(c[0] * k), Math.round(c[1] * k), Math.round(c[2] * k), c[3]];

/** Une image 64 x 64 RGBA, transparente au départ. */
function image() {
  const px = Buffer.alloc(64 * 64 * 4);
  return {
    px,
    set(x, y, c) { if (x < 0 || y < 0 || x > 63 || y > 63 || !c) return; const o = (y * 64 + x) * 4; px[o] = c[0]; px[o + 1] = c[1]; px[o + 2] = c[2]; px[o + 3] = c[3]; },
    rect(x0, y0, x1, y1, f) { for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) this.set(x, y, typeof f === "function" ? f(x - x0, y - y0) : f); },
  };
}

// les zones de la peau (x, y du coin, largeur, hauteur) : faces d'une boîte de largeur w, hauteur h, profondeur d
function boite(img, ox, oy, w, h, d, couleur) {
  // haut, bas, puis côtés (droite, avant, gauche, arrière) ; couleur(face, x, y) avec y de 0 (haut) à h-1
  img.rect(ox + d, oy, ox + d + w, oy + d, (x, y) => couleur("haut", x, y));
  img.rect(ox + d + w, oy, ox + d + 2 * w, oy + d, (x, y) => couleur("bas", x, y));
  img.rect(ox, oy + d, ox + d, oy + d + h, (x, y) => couleur("droite", x, y));
  img.rect(ox + d, oy + d, ox + d + w, oy + d + h, (x, y) => couleur("avant", x, y));
  img.rect(ox + d + w, oy + d, ox + 2 * d + w, oy + d + h, (x, y) => couleur("gauche", x, y));
  img.rect(ox + 2 * d + w, oy + d, ox + 2 * d + 2 * w, oy + d + h, (x, y) => couleur("arriere", x, y));
}

/**
 * Une personne : teint, cheveux (couleur, coupe), barbe, tenue (pagne, tunique, peau de bête, robe),
 * accessoires (bandeau à plume, collier d'os, couronne de feuilles, écharpe tricolore, masque de garde).
 */
function personne(nom, o) {
  const img = image();
  const peau = hex(o.teint), peauOmbre = assombrir(peau, 0.85);
  const cheveux = hex(o.cheveux), cheveuxOmbre = assombrir(cheveux, 0.8);
  const tissu = hex(o.tissu ?? "#5b7a2e"), tissuOmbre = assombrir(tissu, 0.8);
  const corde = hex("#a0784a");
  // ---- tête (0,0) : 8 x 8 x 8
  boite(img, 0, 0, 8, 8, 8, (face, x, y) => {
    if (face === "haut") return o.coupe === "chauve" ? peau : cheveux;
    if (face === "bas") return peauOmbre;
    const longs = o.coupe === "longs";
    if (face === "arriere") return y < (longs ? 8 : o.coupe === "chauve" ? 1 : 5) ? cheveux : peau;
    if (face === "droite" || face === "gauche") {
      if (o.coupe === "chauve") return y < 1 ? cheveuxOmbre : peau;
      return y < 2 || (longs && x < 6) || (y < 4 && x < 5) ? cheveux : peau;
    }
    // le visage
    if (y < 2) return o.coupe === "chauve" ? peau : cheveux;
    if (y === 2) return o.coupe === "chauve" || x > 0 && x < 7 ? peau : cheveux;
    if (y === 3 && (x === 1 || x === 2 || x === 5 || x === 6)) return assombrir(cheveux, 0.9);
    if (y === 4 && (x === 1 || x === 6)) return hex("#ffffff");
    if (y === 4 && (x === 2 || x === 5)) return hex(o.yeux ?? "#3b2a1a");
    if (y === 5 && (x === 3 || x === 4)) return peauOmbre;
    if (o.barbe && y >= 6) return x === 0 || x === 7 ? cheveuxOmbre : y === 6 && x > 2 && x < 5 ? hex("#7a3b2e") : cheveux;
    if (y === 6 && x > 2 && x < 5) return hex("#9a4a3a");
    if (longs && (x === 0 || x === 7)) return cheveux;
    return peau;
  });
  // ---- accessoires de tête (couche « chapeau », 32,0)
  if (o.bandeau) boite(img, 32, 0, 8, 8, 8, (face, x, y) => (face !== "haut" && face !== "bas" && y === 2 ? hex(o.bandeau) : null));
  if (o.plume) { img.set(56, 9, hex(o.plume)); img.set(56, 10, hex(o.plume)); img.set(57, 8, hex(o.plume)); img.set(57, 9, hex(o.plume)); }
  if (o.couronne) boite(img, 32, 0, 8, 8, 8, (face, x, y) => (face !== "haut" && face !== "bas" && y === 1 && (x + y) % 2 === 0 ? hex("#4f8f2f") : face !== "haut" && face !== "bas" && y === 1 ? hex("#6fbf3f") : null));
  if (o.masque) boite(img, 32, 0, 8, 8, 8, (face, x, y) => {
    if (face !== "avant") return null;
    if (y >= 2 && y <= 6) { if (y === 4 && (x === 2 || x === 5)) return hex("#111111"); return (x + y) % 3 === 0 ? hex("#c9a227") : hex("#e8dcc0"); }
    return null;
  });
  // ---- corps (16,16) : 8 x 12 x 4
  boite(img, 16, 16, 8, 12, 4, (face, x, y) => {
    if (face === "haut") return o.tenue === "pagne" ? peau : tissu;
    if (face === "bas") return tissuOmbre;
    switch (o.tenue) {
      case "pagne":
        if (y >= 10) return hex("#7a5230");
        if (o.collier && face === "avant" && y === 2 && x > 0 && x < 7) return x % 2 ? hex("#f2ead3") : hex("#8a6a4a");
        return y < 9 ? peau : corde;
      case "peau":
        if (face === "avant" && x > 2 && x < 5 && y < 9) return peau;
        if (y === 8) return corde;
        return (x * 7 + y * 3) % 5 === 0 ? assombrir(tissu, 0.6) : tissu;
      case "robe":
        if (y === 6) return corde;
        return (x + y) % 4 === 0 ? tissuOmbre : tissu;
      case "armure":
        if (y === 3 || y === 7) return hex("#3b2614");
        if (x === 0 || x === 7) return hex("#5a3a1e");
        return (x + y) % 2 ? hex("#8a5a2b") : hex("#7a4e24");
      case "croupier":
        if (y === 11) return hex("#111114");
        if (face === "avant" && y === 0 && x > 1 && x < 6) return x === 3 || x === 4 ? hex("#c0182a") : hex("#8a1020");
        if (face === "avant" && (x === 3 || x === 4)) return y % 3 === 1 ? hex("#d8d8d8") : hex("#f4f4f4");
        return (x + y) % 5 === 0 ? hex("#1c1c22") : hex("#26262e");
      default: // tunique
        if (y === 7) return corde;
        if (face === "avant" && y < 3 && x > 2 && x < 5) return peau;
        return y > 7 && (x + y) % 3 === 0 ? tissuOmbre : tissu;
    }
  });
  // l'écharpe tricolore du président, en diagonale sur la veste
  if (o.echarpe) {
    for (let i = 0; i < 12; i++) {
      const x = 20 + Math.min(7, Math.floor(i * 0.7));
      const couleur = i % 3 === 0 ? "#2a4bd0" : i % 3 === 1 ? "#ffffff" : "#d02a2a";
      img.set(x, 20 + i, hex(couleur));
      img.set(x + 1, 20 + i, hex(couleur));
    }
  }
  if (o.badge) { img.set(25, 23, hex("#ffffff")); img.set(26, 23, hex("#2a6bd0")); img.set(25, 24, hex("#2a6bd0")); img.set(26, 24, hex("#ffffff")); }
  // ---- bras (40,16) et (32,48) : 4 x 12 x 4
  const bras = (face, x, y) => {
    if (o.tenue === "armure") return y < 4 ? hex("#7a4e24") : y > 9 ? hex("#5a3a1e") : peau;
    if (o.tenue === "croupier") return y < 10 ? (y === 9 ? hex("#d8d8d8") : hex("#f4f4f4")) : face === "bas" ? peauOmbre : peau;
    if (o.tenue === "tunique" || o.tenue === "robe") return y < (o.tenue === "robe" ? 6 : 4) ? tissu : face === "bas" ? peauOmbre : peau;
    if (o.bracelet && y === 9) return hex(o.bracelet);
    return face === "bas" ? peauOmbre : peau;
  };
  boite(img, 40, 16, 4, 12, 4, bras);
  boite(img, 32, 48, 4, 12, 4, bras);
  // ---- jambes (0,16) et (16,48) : 4 x 12 x 4
  const jambe = (face, x, y) => {
    if (y >= 10) return hex(o.sandales ?? "#5a3a1e");
    if (o.tenue === "robe") return y < 8 ? tissu : peau;
    if (o.tenue === "croupier") return y >= 10 ? hex("#0c0c0e") : (x + y) % 4 === 0 ? hex("#1a1a20") : hex("#22222a");
    if (o.tenue === "armure") return y < 5 ? hex("#7a4e24") : y < 8 ? peau : hex("#5a3a1e");
    const pagne = o.tenue === "pagne" ? hex("#7a5230") : tissu;
    return y < (o.tenue === "peau" ? 5 : 3) ? pagne : face === "bas" ? peauOmbre : peau;
  };
  boite(img, 0, 16, 4, 12, 4, jambe);
  boite(img, 16, 48, 4, 12, 4, jambe);
  fs.mkdirSync(DOSSIER, { recursive: true });
  fs.writeFileSync(path.join(DOSSIER, nom + ".png"), png(img.px));
}

function png(px) {
  const n = 64, brut = Buffer.alloc(n * (1 + n * 4));
  for (let y = 0; y < n; y++) { brut[y * (1 + n * 4)] = 0; px.copy(brut, y * (1 + n * 4) + 1, y * n * 4, (y + 1) * n * 4); }
  const table = Array.from({ length: 256 }, (_, k) => { let c = k; for (let j = 0; j < 8; j++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (b) => { let c = 0xffffffff; for (const v of b) c = table[(c ^ v) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => { const t = Buffer.from(type), l = Buffer.alloc(4), cc = Buffer.alloc(4); l.writeUInt32BE(data.length); cc.writeUInt32BE(crc(Buffer.concat([t, data]))); return Buffer.concat([l, t, data, cc]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(n, 0); ihdr.writeUInt32BE(n, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

// les habitants
personne("jean", { teint: "#e0b48a", cheveux: "#3a2416", coupe: "courts", barbe: true, tenue: "tunique", tissu: "#6b7a2e", sandales: "#4a3020" });
personne("jacques", { teint: "#c99366", cheveux: "#1d1410", coupe: "longs", tenue: "pagne", collier: true, bandeau: "#c03a2a", plume: "#f2f2f2" });
personne("francis", { teint: "#f0c8a0", cheveux: "#9a9a9a", coupe: "chauve", barbe: true, tenue: "peau", tissu: "#b5884e" });
personne("louis", { teint: "#8d5a3b", cheveux: "#120c0a", coupe: "courts", tenue: "pagne", collier: true, bracelet: "#d4af37" });
personne("bernard", { teint: "#e8c09a", cheveux: "#6a4428", coupe: "courts", barbe: true, tenue: "tunique", tissu: "#8a5a2b" });
personne("gerard", { teint: "#d9a57a", cheveux: "#c9c9c9", coupe: "chauve", tenue: "peau", tissu: "#a37a48", bandeau: "#2a6b3a" });
personne("ginette", { teint: "#f2cfaa", cheveux: "#8a3a1e", coupe: "longs", tenue: "robe", tissu: "#3f7a4a", couronne: true });
personne("monique", { teint: "#a8704a", cheveux: "#241612", coupe: "longs", tenue: "robe", tissu: "#b04a3a", bracelet: "#f2ead3" });
personne("martine", { teint: "#e6b896", cheveux: "#d8b25a", coupe: "longs", tenue: "robe", tissu: "#5a6fb0", plume: "#3a9ad9", bandeau: "#d4af37" });
personne("thierry", { teint: "#c48c5e", cheveux: "#3a2416", coupe: "longs", barbe: true, tenue: "pagne", collier: true });
// Mehdi, de Pôle emploi : une tunique propre et un badge
personne("mehdi", { teint: "#c99366", cheveux: "#1d1410", coupe: "courts", barbe: true, tenue: "tunique", tissu: "#2f5aa0", badge: true });
// Macron, le président du village : tunique fine, écharpe tricolore, couronne de feuilles
personne("macron", { teint: "#f2cfaa", cheveux: "#5a3a22", coupe: "courts", tenue: "tunique", tissu: "#1f2a4a", echarpe: true, couronne: true, yeux: "#2a6bd0" });
// ses gardes : armure de cuir et de bois, masque peint
for (const [n, teint] of [["garde_1", "#8d5a3b"], ["garde_2", "#c99366"], ["garde_3", "#6b4028"], ["garde_4", "#e0b48a"]]) {
  personne(n, { teint, cheveux: "#120c0a", coupe: "chauve", tenue: "armure", masque: true, sandales: "#3b2614" });
}
// le croupier du blackjack
personne("croupier", { teint: "#e8c09a", cheveux: "#141010", coupe: "courts", tenue: "croupier", tissu: "#26262e", yeux: "#2a4a6a" });
console.log("Peaux écrites dans", DOSSIER);
