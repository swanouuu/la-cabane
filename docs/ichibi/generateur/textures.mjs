// Les textures du mode Ichibi dans le mod (à relancer si la planche change), faites avec les mêmes briques que la
// planche : la moitié du corps sur la disposition d'une peau de joueur, quatre fois plus fine (256×256, pour l'œil), et
// le sable du grand bras, de la queue, de l'oreille et du nuage (ichibi.png : 256×256 pour une taille déclarée de 64×64 ;
// en haut le sable aux traits d'encre, en bas à gauche le sable du nuage, puis le blanc des griffes et l'encre seule).
// node textures.mjs → mod-cabane/src/main/resources/assets/cabane/textures/entity/ichibi/
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ecrirePng, PARTIES, texels, mix } from "../../fruits/generateur/moteur.mjs";
import { FACES } from "../../fruits/generateur/rendu.mjs";
import { sable, oeil, pris, NOMS, SABLE, SABLE_C, ENCRE, GRIFFE } from "./rendu.mjs";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const SORTIE = path.join(ICI, "..", "..", "..", "mod-cabane", "src", "main", "resources", "assets", "cabane", "textures", "entity", "ichibi");
fs.mkdirSync(SORTIE, { recursive: true });
const T = 256;
const point = (img, x, y, c) => img.set([...c.slice(0, 3).map(Math.round), 255], (y * T + x) * 4);

// la moitié du corps (le bras droit n'y est pas : le grand bras le remplace)
{
  const img = Buffer.alloc(T * T * 4, 0);
  for (const [nom, boite, uv] of PARTIES(false)) {
    if (nom === "brasD") continue;
    for (const t of texels(boite, uv, 0, 4)) {
      if (!pris(nom, t, 2)) continue;
      const [x, y, z] = t.centre, N = t.N;
      let c = nom === "tete" && t.face === "devant" ? oeil(x, y) : null;
      if (!c) {
        const [a, b] = Math.abs(N[2]) > 0.5 ? [x, y] : Math.abs(N[0]) > 0.5 ? [z, y] : [x, z];
        c = sable(a + 40, b + 40, FACES[t.face] + 10 * NOMS.indexOf(nom));
      }
      point(img, t.tx, t.ty, c);
    }
  }
  ecrirePng(path.join(SORTIE, "moitie.png"), T, T, img);
}

// le sable des morceaux d'Ichibi
{
  const img = Buffer.alloc(T * T * 4, 0);
  for (let y = 0; y < T; y++) for (let x = 0; x < T; x++) {
    const a = x / 4, b = y / 4;
    const c = b < 48 ? sable(a + 7, b + 3, 900)
      : a < 32 ? sable(a, b, 910, b < 52 ? SABLE_C : SABLE, false)
        : a < 48 ? mix(GRIFFE, [206, 196, 172], (b - 48) / 16 * 0.6)
          : ENCRE;
    point(img, x, y, c);
  }
  ecrirePng(path.join(SORTIE, "ichibi.png"), T, T, img);
}
console.log("textures dans", SORTIE);
