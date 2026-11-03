// Les peaux de la cinématique du décollage (format joueur 64 x 64) : l'équipage du vol BV2026 (commandant, copilote,
// hôtesse), l'agent de piste, et les passagers qui remplissent les sièges quand il n'y a pas assez de joueurs.
// Des gens d'aujourd'hui : uniformes, sweats, jeans, costumes.
// Lancer depuis n'importe où :  node mod-cabane/outils/avion.mjs
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const DOSSIER = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources",
  "assets", "cabane", "textures", "entity", "avion");

const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16), 255];
const assombrir = (c, k) => [Math.round(c[0] * k), Math.round(c[1] * k), Math.round(c[2] * k), c[3]];

function image() {
  const px = Buffer.alloc(64 * 64 * 4);
  return {
    px,
    set(x, y, c) { if (x < 0 || y < 0 || x > 63 || y > 63 || !c) return; const o = (y * 64 + x) * 4; px[o] = c[0]; px[o + 1] = c[1]; px[o + 2] = c[2]; px[o + 3] = c[3]; },
    rect(x0, y0, x1, y1, f) { for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) this.set(x, y, typeof f === "function" ? f(x - x0, y - y0) : f); },
  };
}

// une boîte de largeur w, hauteur h, profondeur d dont le patron commence en (ox, oy) ; couleur(face, x, y)
function boite(img, ox, oy, w, h, d, couleur) {
  img.rect(ox + d, oy, ox + d + w, oy + d, (x, y) => couleur("haut", x, y));
  img.rect(ox + d + w, oy, ox + d + 2 * w, oy + d, (x, y) => couleur("bas", x, y));
  img.rect(ox, oy + d, ox + d, oy + d + h, (x, y) => couleur("droite", x, y));
  img.rect(ox + d, oy + d, ox + d + w, oy + d + h, (x, y) => couleur("avant", x, y));
  img.rect(ox + d + w, oy + d, ox + 2 * d + w, oy + d + h, (x, y) => couleur("gauche", x, y));
  img.rect(ox + 2 * d + w, oy + d, ox + 2 * d + 2 * w, oy + d + h, (x, y) => couleur("arriere", x, y));
}

/**
 * Une personne. o : teint, cheveux, coupe (courts, longs, chignon, chauve, couronne : chauve avec une couronne de
 * cheveux), yeux, moustache, barbe, rouge (aux lèvres), lunettes (couleur de monture), solaires,
 * haut (couleur), col (couleur du col ou de la chemise sous la veste), cravate, foulard, motif (hawaï),
 * manches (courtes | longues), galons (dorés aux poignets), epaulettes, gilet (haute visibilité),
 * bas (couleur), jupe, short, chaussures, casquette (pilote), casque (anti-bruit), capuche (sweat).
 */
function personne(nom, o) {
  const img = image();
  const peau = hex(o.teint), peauOmbre = assombrir(peau, 0.86);
  const cheveux = hex(o.cheveux ?? "#2a1a10"), cheveuxOmbre = assombrir(cheveux, 0.8);
  const haut = hex(o.haut ?? "#3a5a9a"), hautOmbre = assombrir(haut, 0.82);
  const bas = hex(o.bas ?? "#2a3a5a"), basOmbre = assombrir(bas, 0.82);
  const chaussures = hex(o.chaussures ?? "#1a1a1a");
  const col = o.col ? hex(o.col) : null;
  const coupe = o.coupe ?? "courts";
  const chauve = coupe === "chauve" || coupe === "couronne";

  // ---- tête (0,0)
  boite(img, 0, 0, 8, 8, 8, (face, x, y) => {
    if (face === "haut") return chauve ? peau : cheveux;
    if (face === "bas") return peauOmbre;
    if (face === "arriere") {
      if (coupe === "couronne") return y >= 2 && y < 5 ? cheveux : peau;
      if (coupe === "chignon") return y < 7 ? cheveux : peau;
      return y < (coupe === "longs" ? 8 : chauve ? 0 : 5) ? cheveux : peauOmbre;
    }
    if (face === "droite" || face === "gauche") {
      if (coupe === "couronne") return y >= 2 && y < 5 && x < 5 ? cheveux : peau;
      if (chauve) return peau;
      if (coupe === "longs") return y < 2 || x < 6 ? cheveux : peau;
      return y < 2 || (y < 4 && x < 5) ? cheveux : peau;
    }
    // le visage
    if (y < 2) return chauve ? peau : cheveux;
    if (y === 2) {
      if (coupe === "couronne" && (x === 0 || x === 7)) return cheveux;
      return chauve || (x > 0 && x < 7) ? peau : cheveux;
    }
    if (o.solaires && (y === 3 || y === 4) && x > 0 && x < 7) return x === 3 || x === 4 ? (y === 3 ? hex("#202020") : peau) : hex("#101014");
    if (o.lunettes && y === 3 && x > 0 && x < 7) return hex(o.lunettes);
    if (o.lunettes && y === 4 && (x === 0 || x === 3 || x === 4 || x === 7)) return hex(o.lunettes);
    if (y === 3 && (x === 1 || x === 2 || x === 5 || x === 6)) return chauve ? assombrir(peau, 0.7) : cheveuxOmbre;
    if (y === 4 && (x === 1 || x === 6)) return hex("#ffffff");
    if (y === 4 && (x === 2 || x === 5)) return hex(o.yeux ?? "#3b2a1a");
    if (coupe === "longs" && (x === 0 || x === 7)) return cheveux;
    if (y === 5 && (x === 3 || x === 4)) return peauOmbre;
    if (o.barbe && y >= 5 && (x === 0 || x === 7 || y === 7)) return hex(o.barbe);
    if (o.moustache && y === 6 && x > 1 && x < 6) return hex(o.moustache);
    if (y === 6 && x > 2 && x < 5) return o.rouge ? hex(o.rouge) : hex("#9a4a3a");
    return peau;
  });

  // ---- la couche « chapeau » (32,0) : casquette de pilote, chignon, casque anti-bruit
  if (o.casquette) {
    const bleu = hex(o.casquette), or = hex("#d8b23a"), noir = hex("#141418");
    boite(img, 32, 0, 8, 8, 8, (face, x, y) => {
      if (face === "haut") return x === 0 || x === 7 || y === 0 || y === 7 ? assombrir(bleu, 0.85) : bleu;
      if (face === "bas") return null;
      if (y < 2) return face === "avant" && y === 1 && x > 2 && x < 5 ? or : bleu;
      if (y === 2) return face === "avant" ? or : noir;
      if (face === "avant" && y === 3) return noir;
      return null;
    });
  }
  if (coupe === "chignon") {
    // le chignon : une petite boule de cheveux sur l'arrière du crâne
    boite(img, 32, 0, 8, 8, 8, (face, x, y) => (face === "arriere" && y >= 1 && y <= 3 && x >= 2 && x <= 5 ? cheveuxOmbre
      : face === "haut" && y >= 5 && x >= 2 && x <= 5 ? cheveux : null));
  }
  if (o.casque) {
    const c = hex(o.casque);
    boite(img, 32, 0, 8, 8, 8, (face, x, y) => {
      if (face === "haut") return x === 3 || x === 4 ? hex("#202020") : null;
      if ((face === "droite" || face === "gauche") && y >= 2 && y <= 5 && x >= 2 && x <= 5) return (x === 2 || x === 5 || y === 2 || y === 5) ? assombrir(c, 0.8) : c;
      if ((face === "droite" || face === "gauche") && y < 2 && (x === 3 || x === 4)) return hex("#202020");
      return null;
    });
  }

  // ---- corps (16,16) : 8 x 12 x 4
  const motif = (x, y) => ((x * 3 + y * 5) % 7 === 0 ? hex("#f2e04a") : (x + y * 2) % 5 === 0 ? hex("#ffffff") : null);
  boite(img, 16, 16, 8, 12, 4, (face, x, y) => {
    if (face === "haut") return haut;
    if (face === "bas") return basOmbre;
    if (y >= 10) return o.jupe || o.short ? bas : y === 10 ? hex("#2a2a2a") : bas;
    if (face === "avant") {
      if (o.gilet && (y === 6 || y === 7)) return hex("#d8d8e0");
      if (o.gilet && (x === 0 || x === 7 || y > 1)) return (x + y) % 6 === 0 ? hex("#e06a10") : hex("#ff7a18");
      if (o.cravate && (x === 3 || x === 4) && y < 9) return y === 0 ? assombrir(hex(o.cravate), 0.8) : hex(o.cravate);
      if (o.foulard && y < 2) return x === 3 || x === 4 ? assombrir(hex(o.foulard), 0.8) : hex(o.foulard);
      if (o.foulard && y === 2 && (x === 3 || x === 4)) return hex(o.foulard);
      if (col && y < 3 && x > 1 && x < 6) return col;
      if (col && o.veste && x > 2 && x < 5) return col;
      if (o.veste && (x === 3 || x === 4) && y > 2) return hautOmbre;
      if (o.veste && (y === 5 || y === 8) && x === 2) return hex("#d8b23a");
      if (o.capuche && y < 2 && x > 1 && x < 6) return hautOmbre;
      if (o.capuche && (x === 3 || x === 4) && y > 2 && y < 6) return hex("#e8e8e8");
      if (o.motif) return motif(x, y) ?? haut;
    }
    if (face === "arriere" && o.gilet && (y === 6 || y === 7)) return hex("#d8d8e0");
    if (face === "arriere" && o.gilet) return hex("#ff7a18");
    if (face === "arriere" && o.capuche && y < 3) return hautOmbre;
    if (o.motif) return motif(x + 9, y) ?? haut;
    if (o.gilet) return hex("#ff7a18");
    return y > 7 && (x + y) % 4 === 0 ? hautOmbre : haut;
  });
  if (o.epaulettes) {
    for (const x of [44, 45, 46, 47, 36, 37, 38, 39]) { img.set(x, 16, hex("#141418")); img.set(x, 17, hex("#141418")); }
    for (const x of [44, 46, 36, 38]) img.set(x, 17, hex("#d8b23a"));
  }

  // ---- bras (40,16) et (32,48)
  const courtes = o.manches === "courtes";
  const manche = o.gilet ? hex(o.chemise ?? "#3a3a44") : haut;
  const bras = (face, x, y) => {
    if (face === "haut") return manche;
    if (face === "bas") return peauOmbre;
    if (courtes ? y >= 4 : y >= 11) return peau;
    if (o.galons && (y === 8 || y === 10) && face !== "arriere") return hex("#d8b23a");
    if (o.motif) return motif(x * 2 + 3, y) ?? manche;
    return y === 10 && !courtes && col && o.veste ? col : manche;
  };
  boite(img, 40, 16, 4, 12, 4, bras);
  boite(img, 32, 48, 4, 12, 4, bras);

  // ---- jambes (0,16) et (16,48)
  const jambe = (face, x, y) => {
    if (face === "bas") return chaussures;
    if (y >= 11) return chaussures;
    if (o.jupe) return y < 5 ? (face === "haut" ? bas : y === 4 ? basOmbre : bas) : assombrir(peau, 0.95);
    if (o.short) return y < 5 ? bas : face === "haut" ? bas : peau;
    if (o.gilet && (y === 8)) return hex("#d8d8e0");
    if (o.jean) return (x + y) % 5 === 0 ? basOmbre : y === 0 ? assombrir(bas, 0.7) : bas;
    return y === 0 && face === "avant" ? basOmbre : bas;
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

// l'équipage
personne("pilote", { teint: "#f0c8a0", cheveux: "#a8a8a8", coupe: "courts", moustache: "#9a9a9a", yeux: "#2a4a6a",
  haut: "#1c2440", veste: true, col: "#f4f4f4", cravate: "#101014", galons: true, bas: "#1c2440", casquette: "#1c2440" });
personne("copilote", { teint: "#b07a50", cheveux: "#2a1a10", coupe: "courts", haut: "#f2f2f2", manches: "courtes", cravate: "#101014",
  epaulettes: true, bas: "#1c2440", casquette: "#1c2440" });
personne("hotesse", { teint: "#f2cfaa", cheveux: "#4a2a18", coupe: "chignon", yeux: "#2a5a3a", rouge: "#c0283a",
  haut: "#1c2a5a", veste: true, col: "#f4f4f4", foulard: "#d0303a", bas: "#1c2a5a", jupe: true, chaussures: "#101014" });
personne("agent_piste", { teint: "#6b4028", cheveux: "#120c0a", coupe: "courts", gilet: true, chemise: "#3a3a44",
  bas: "#4a4a52", chaussures: "#2a2016", casque: "#e8c020" });
// les passagers
personne("passager_1", { teint: "#e8c09a", cheveux: "#5a3a22", coupe: "courts", haut: "#c0302a", capuche: true, bas: "#3a5a8a", jean: true, chaussures: "#f0f0f0" });
personne("passager_2", { teint: "#f2d2b8", cheveux: "#d8d8dc", coupe: "courts", lunettes: "#6a4a8a", rouge: "#b05a6a",
  haut: "#a88ac8", veste: true, col: "#f4f0e8", bas: "#6a6a72", jupe: true, chaussures: "#4a3020" });
personne("passager_3", { teint: "#e0b48a", cheveux: "#141010", coupe: "courts", lunettes: "#202020", haut: "#3a3a42", veste: true,
  col: "#f4f4f4", cravate: "#2a5ac0", bas: "#3a3a42" });
personne("passager_4", { teint: "#f0a888", cheveux: "#c8a070", coupe: "chauve", solaires: true, haut: "#2a9ac8", motif: true,
  manches: "courtes", bas: "#d8c49a", short: true, chaussures: "#7a5030" });
personne("passager_5", { teint: "#f2cfaa", cheveux: "#e8c870", coupe: "longs", yeux: "#3a6ab0", rouge: "#d06a7a", haut: "#f08ab0",
  manches: "courtes", bas: "#1a1a20", chaussures: "#f0f0f0" });
personne("passager_6", { teint: "#5a3420", cheveux: "#0c0806", coupe: "courts", barbe: "#0c0806", haut: "#e8c020", manches: "courtes",
  bas: "#6a6a72", chaussures: "#c03030" });
personne("passager_7", { teint: "#c99366", cheveux: "#1a1010", coupe: "longs", rouge: "#a03a3a", haut: "#f4f4f4", bas: "#3a5a8a", jean: true });
personne("passager_8", { teint: "#e8c09a", cheveux: "#b0b0b0", coupe: "couronne", moustache: "#c0c0c0", lunettes: "#5a4030",
  haut: "#d8c8a0", bas: "#6a4a30", chaussures: "#3a2414" });
console.log("Peaux écrites dans", DOSSIER);
