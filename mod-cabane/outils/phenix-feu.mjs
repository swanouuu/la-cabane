// La texture de bruit des flammes du phénix (deux bruits qui bouclent, dans le rouge et le vert), lue par le shader
// cabane:phenix_feu (assets/cabane/shaders/core). La même que l'aperçu docs/phenix/apercu (TEX_FEU).
// node outils/phenix-feu.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng } from "../../docs/fruits/generateur/moteur.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const DOSSIER = path.join(ICI, "..", "src", "main", "resources", "assets", "cabane", "textures", "effet");
const hache = (i, j = 0, k = 0) => { let h = (i * 374761393 + j * 668265263 + k * 2147483647) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };
const entre = (a, b, u) => a + (b - a) * u;
const W = 64, H = 128;
const bruit = (x, y, px, py, s) => {
  const xi = Math.floor(x), yi = Math.floor(y), fx = x - xi, fy = y - yi;
  const h = (i, j) => hache(((i % px) + px) % px, ((j % py) + py) % py, s);
  const sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
  return entre(entre(h(xi, yi), h(xi + 1, yi), sx), entre(h(xi, yi + 1), h(xi + 1, yi + 1), sx), sy);
};
const img = Buffer.alloc(W * H * 4);
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
  let r = 0, v = 0, amp = 0.55, tot = 0;
  for (let o = 0; o < 4; o++) {
    const px = 3 << o, py = 6 << o;
    r += amp * bruit((x / W) * px, (y / H) * py, px, py, 11 + o);
    v += amp * bruit((x / W) * px, (y / H) * py, px, py, 31 + o);
    tot += amp; amp *= 0.55;
  }
  const i = (y * W + x) * 4;
  img[i] = Math.min(255, Math.round(Math.pow(r / tot, 1.3) * 330));
  img[i + 1] = Math.min(255, Math.round(Math.pow(v / tot, 1.3) * 330));
  img[i + 2] = 0;
  img[i + 3] = 255;
}
fs.mkdirSync(DOSSIER, { recursive: true });
ecrirePng(path.join(DOSSIER, "phenix_bruit.png"), W, H, img);
fs.writeFileSync(path.join(DOSSIER, "phenix_bruit.png.mcmeta"), JSON.stringify({ texture: { blur: true, clamp: false } }, null, 2) + "\n");
console.log("ok", path.join(DOSSIER, "phenix_bruit.png"));
