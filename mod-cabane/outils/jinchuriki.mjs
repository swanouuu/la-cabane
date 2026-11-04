// La marque des Jinchûriki (d'après l'image de l'utilisateur) : une spirale au centre et huit groupes de signes tracés au
// pinceau, en rayons tout autour. Deux textures blanches de 128 × 128 (le jeu les teinte : or qui brille, puis encre
// noire) : la spirale seule et les signes seuls, pour que la spirale puisse apparaître avant eux.
// Lancer :  node mod-cabane/outils/jinchuriki.mjs
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const N = 128, SUR = 4, G = N * SUR;

/** Un tirage pseudo-aléatoire fixe (même dessin à chaque génération). */
function hasard(graine) {
  let a = graine >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Une toile en niveaux d'encre (0 à 1), dessinée en grand puis réduite (bords doux). */
function toile() {
  const encre = new Float32Array(G * G);
  /** Un trait de pinceau de a à b, d'épaisseur e (en pixels de la grande toile), aux bouts arrondis. */
  const trait = ([ax, ay], [bx, by], e) => {
    const r = e / 2, x0 = Math.max(0, Math.floor(Math.min(ax, bx) - r - 1)), x1 = Math.min(G - 1, Math.ceil(Math.max(ax, bx) + r + 1));
    const y0 = Math.max(0, Math.floor(Math.min(ay, by) - r - 1)), y1 = Math.min(G - 1, Math.ceil(Math.max(ay, by) + r + 1));
    const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy || 1;
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
      const u = Math.max(0, Math.min(1, ((x + 0.5 - ax) * dx + (y + 0.5 - ay) * dy) / l2));
      const d = Math.hypot(x + 0.5 - ax - u * dx, y + 0.5 - ay - u * dy);
      const c = Math.max(0, Math.min(1, r - d + 0.5));
      if (c > encre[y * G + x]) encre[y * G + x] = c;
    }
  };
  const png = () => {
    const rgba = Buffer.alloc(N * N * 4);
    for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
      let s = 0;
      for (let j = 0; j < SUR; j++) for (let i = 0; i < SUR; i++) s += encre[(y * SUR + j) * G + x * SUR + i];
      const a = Math.round((s / (SUR * SUR)) * 255);
      rgba.set([255, 255, 255, a], (y * N + x) * 4);
    }
    return encoder(rgba);
  };
  return { trait, png };
}

function encoder(rgba) {
  const brut = Buffer.alloc(N * (1 + N * 4));
  for (let y = 0; y < N; y++) rgba.copy(brut, y * (1 + N * 4) + 1, y * N * 4, (y + 1) * N * 4);
  const table = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (b) => { let c = 0xffffffff; for (const x of b) c = table[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const bloc = (type, data) => {
    const l = Buffer.alloc(4); l.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
    return Buffer.concat([l, td, c]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(N, 0); ihdr.writeUInt32BE(N, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), bloc("IHDR", ihdr), bloc("IDAT", zlib.deflateSync(brut)), bloc("IEND", Buffer.alloc(0))]);
}

/** La spirale du centre : deux tours et demi, d'un trait épais, qui finit par une petite queue vers l'intérieur. */
function spirale() {
  const t = toile(), c = G / 2, R = G * 0.19, tours = 2.6, fin = tours * Math.PI * 2;
  let prec = null;
  for (let i = 0; i <= 900; i++) {
    const th = (i / 900) * fin, r = G * 0.012 + (R - G * 0.012) * (th / fin);
    // elle tourne dans le sens des aiguilles d'une montre et se termine à droite, un peu sous le milieu
    const a = th + Math.PI * 0.6;
    const p = [c + Math.cos(a) * r, c + Math.sin(a) * r];
    if (prec) t.trait(prec, p, G * 0.027);
    prec = p;
  }
  return t.png();
}

/** Les huit groupes de signes, en rayons : un trait principal qui tremble, des petites barres et des points. */
function signes() {
  const t = toile(), c = G / 2, h = hasard(8);
  for (let k = 0; k < 8; k++) {
    const a = -Math.PI / 2 + Math.PI / 8 + (k * Math.PI) / 4 + (h() - 0.5) * 0.12;
    const rad = [Math.cos(a), Math.sin(a)], cote = [-Math.sin(a), Math.cos(a)];
    const r0 = G * (0.29 + h() * 0.03), r1 = G * (0.47 + h() * 0.02);
    const point = (r, d) => [c + rad[0] * r + cote[0] * d, c + rad[1] * r + cote[1] * d];
    // le trait principal, en quelques morceaux qui ne se touchent pas toujours
    let r = r0, d = (h() - 0.5) * G * 0.02;
    while (r < r1) {
      const l = G * (0.025 + h() * 0.045), d2 = d + (h() - 0.5) * G * 0.035;
      t.trait(point(r, d), point(Math.min(r1, r + l), d2), G * (0.012 + h() * 0.012));
      r += l + (h() < 0.35 ? G * 0.012 : 0);
      d = Math.max(-G * 0.03, Math.min(G * 0.03, d2));
    }
    // les barres en travers et en biais, comme les traits d'un caractère
    for (let i = 0; i < 6 + Math.floor(h() * 4); i++) {
      const rr = r0 + (r1 - r0) * h(), l = G * (0.02 + h() * 0.035), biais = (h() - 0.5) * 1.4, s = h() < 0.5 ? -1 : 1;
      const dd = (h() - 0.5) * G * 0.03;
      t.trait(point(rr, dd), point(rr + l * Math.sin(biais), dd + s * l * Math.cos(biais)), G * (0.009 + h() * 0.01));
    }
    // quelques points d'encre
    for (let i = 0; i < 3; i++) {
      const p = point(r0 + (r1 - r0) * h(), (h() - 0.5) * G * 0.07);
      t.trait(p, [p[0] + 0.1, p[1] + 0.1], G * (0.012 + h() * 0.01));
    }
  }
  return t.png();
}

export function genererJinchuriki({ ecrire }) {
  ecrire("assets/cabane/textures/entity/jinchuriki_spirale.png", spirale());
  ecrire("assets/cabane/textures/entity/jinchuriki_signes.png", signes());
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const RES = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources");
  genererJinchuriki({
    ecrire: (rel, contenu) => {
      fs.mkdirSync(path.dirname(path.join(RES, rel)), { recursive: true });
      fs.writeFileSync(path.join(RES, rel), contenu);
    },
  });
  console.log("marque des Jinchûriki : ok");
}
