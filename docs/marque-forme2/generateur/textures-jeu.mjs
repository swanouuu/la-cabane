// Les textures de la forme 2 pour le mod : l'aile nettoyée (192 × 192, le haut de l'aile en haut, l'extérieur à droite),
// le bas du skin de Sasuke (pantalon, sandales, ceinture de corde) seul sur une peau 64 × 64, et un carré blanc.
import fs from "fs";
import path from "path";
const src = fs.readFileSync("rendu.mjs", "utf8").replace(/\nconst steve = preparer[\s\S]*$/, "\nexport { texAile, AILE_N, lire, ecrirePng, PARTIES, texels, get };");
fs.writeFileSync("rendu-export.mjs", src);
const { texAile, AILE_N, lire, ecrirePng, PARTIES, texels, get } = await import("./rendu-export.mjs");
const sortie = process.argv[2];
const { tex } = texAile();
const aile = Buffer.alloc(AILE_N * AILE_N * 4);
for (let y = 0; y < AILE_N; y++) tex.copy(aile, (AILE_N - 1 - y) * AILE_N * 4, y * AILE_N * 4, (y + 1) * AILE_N * 4);
ecrirePng(path.join(sortie, "aile.png"), AILE_N, AILE_N, aile);
const sasuke = lire(path.join("skins", "sasuke.png"));
const bas = Buffer.alloc(64 * 64 * 4);
for (const [nom, boite, uv, uv2] of PARTIES(false)) {
  const jambe = nom.startsWith("jambe");
  if (!jambe && nom !== "corps") continue;
  for (const couche of [uv, uv2]) for (const t of texels(boite, couche, 0)) {
    if (jambe || t.centre[1] < 16) bas.set(get(sasuke, t.tx, t.ty), (t.ty * 64 + t.tx) * 4);
  }
}
ecrirePng(path.join(sortie, "bas_sasuke.png"), 64, 64, bas);
ecrirePng(path.join(sortie, "blanc.png"), 16, 16, Buffer.alloc(16 * 16 * 4, 255));
fs.rmSync("rendu-export.mjs");
console.log("ok");
