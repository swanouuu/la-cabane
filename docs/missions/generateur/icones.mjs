// Ce que tiennent les gardes des missions, en 64×64, de profil, la pointe vers la droite (même toile que les armes du
// cartel : chaque pièce a sa matière et son épaisseur, l'objet est en 3D dans les mains).
import { toile } from "../../cartel/generateur/armes.mjs";

export const OBJETS = [
  {
    id: "katana",
    dessin(t) {
      t.rect(2, 30.5, 17, 35.5, "noir");
      for (let x = 3; x < 17; x += 3) t.marque([[x, 31], [x + 1, 32], [x, 33], [x + 1, 34]], 1.6);
      t.rond(18.5, 33, 2, 5, "laiton", { profil: "disque", ep: 2 });
      t.poly([[20, 31], [44, 30], [58, 28], [63, 26.5], [60, 30.5], [46, 33.6], [20, 34.6]], "lame");
      t.ligneMarque(21, 34, 58, 29.5, 1.25);
    },
  },
  {
    id: "kunai",
    dessin(t) {
      t.anneau(7, 32, 4, 1.6, "acier");
      t.rect(10, 30, 22, 34, "chiffon");
      for (let x = 11; x < 22; x += 2) t.ligneMarque(x, 30, x + 1, 33, 0.75);
      t.poly([[22, 30.5], [30, 26], [44, 32], [30, 38], [22, 33.5]], "acier");
      t.ligneMarque(24, 32, 42, 32, 1.3);
    },
  },
  {
    id: "lance",
    dessin(t) {
      t.rect(0, 31, 50, 33.6, "boisFonce");
      t.rect(47, 30, 51, 34.6, "laiton");
      t.poly([[51, 30.2], [57, 29], [63.5, 32.3], [57, 35.6], [51, 34.4]], "lame");
      t.ligneMarque(52, 32, 62, 32, 1.3);
      t.rect(18, 30.6, 22, 34, "rouge");
    },
  },
  {
    id: "arc",
    dessin(t) {
      const pts = [];
      for (let k = 0; k <= 20; k++) { const s = k / 20; pts.push([4 + 56 * s, 40 - 22 * Math.sin(Math.PI * s)]); }
      t.trait(pts, 3, "boisFonce");
      t.trait([[4.5, 40], [59.5, 40]], 1, "chiffon", { ep: 1 });
      t.rect(29, 16, 35, 21.5, "chiffon");
    },
  },
  {
    id: "matraque",
    dessin(t) {
      t.rect(8, 30, 20, 34.4, "caoutchouc");
      t.rect(20, 30.4, 52, 34, "noir");
      t.rect(15, 26, 18, 30, "noir");
    },
  },
  {
    id: "pistolet_laser",
    dessin(t) {
      t.poly([[14, 36], [22, 36], [21, 48], [15, 48]], "noir");
      t.rond(24, 33, 9, 6, "acierClair");
      t.rect(30, 31, 46, 35, "acier", { profil: "rond" });
      for (const x of [34, 38, 42]) t.rect(x, 29.6, x + 1.6, 36.4, "rouge", { profil: "disque" });
      t.rond(48, 33, 2.4, 3, "verreOptique");
    },
  },
  {
    id: "fusil_laser",
    dessin(t) {
      t.poly([[4, 34], [18, 33], [20, 40], [6, 42]], "noir");
      t.rect(18, 31, 36, 38, "acierClair");
      t.poly([[22, 38], [27, 38], [26, 46], [22, 46]], "noir");
      t.rect(36, 32, 56, 35.5, "acier", { profil: "rond" });
      for (const x of [40, 45, 50]) t.rect(x, 30.8, x + 1.6, 36.6, "rouge", { profil: "disque" });
      t.rond(58, 33.7, 2.4, 3, "verreOptique");
      t.rect(22, 28, 32, 31, "noir");
    },
  },
  {
    id: "lampe_torche",
    dessin(t) {
      t.rect(8, 30, 30, 34.5, "noir", { profil: "rond" });
      t.poly([[30, 29], [36, 27], [36, 37.5], [30, 35.5]], "acierClair", { profil: "rond" });
      t.rect(36, 27.5, 37.5, 37, "verreOptique");
      t.rect(14, 29, 18, 30, "rouge");
    },
  },
  {
    id: "rouleau",
    dessin(t) {
      t.rect(20, 26, 44, 38, "chiffon", { profil: "rond" });
      t.rect(17, 25, 21, 39, "rouge", { profil: "rond" });
      t.rect(43, 25, 47, 39, "rouge", { profil: "rond" });
      t.rect(30, 26, 33, 38, "orFonce");
    },
  },
  // les objectifs des missions (on les ramasse, on les ramène à la sortie)
  {
    id: "formule",
    dessin(t) {
      t.rect(20, 27, 40, 37, "verreOptique", { profil: "rond" });
      t.rect(23, 28.5, 37, 35.5, "rouge", { profil: "rond" });
      t.rect(40, 29, 44, 35, "verreOptique", { profil: "rond" });
      t.rect(44, 28, 49, 36, "orFonce", { profil: "rond" });
    },
  },
  {
    id: "puce",
    dessin(t) {
      t.rect(18, 22, 46, 42, "vert");
      t.rect(24, 27, 40, 37, "noir");
      for (let x = 20; x < 46; x += 4) { t.rect(x, 19, x + 1.6, 22, "or"); t.rect(x, 42, x + 1.6, 45, "or"); }
      t.ligneMarque(26, 29, 38, 29, 0.8);
    },
  },
  {
    id: "pacte",
    dessin(t) {
      t.rect(20, 26, 44, 38, "ivoire", { profil: "rond" });
      t.rect(17, 25, 21, 39, "boisFonce", { profil: "rond" });
      t.rect(43, 25, 47, 39, "boisFonce", { profil: "rond" });
      t.rect(27, 26, 37, 38, "rouge");
      t.rond(32, 32, 3, 3, "orFonce", { profil: "disque" });
    },
  },
];

export function icones() {
  return Object.fromEntries(OBJETS.map((a) => { const t = toile(); a.dessin(t); return [a.id, t.finir()]; }));
}
