package fr.cabane.backrooms;

import net.minecraft.core.Direction;

/**
 * Les règles du niveau 0, exactement celles de l'aperçu (docs/backrooms/apercu/monde.js) : chaque bloc se calcule tout
 * seul à partir de ses coordonnées (un hachage), sans rien connaître du reste, ce qui fait un labyrinthe infini.
 * <ul>
 * <li>une grille de murs tous les 6 blocs (pièces de 5 × 5), un poteau à chaque croisement ;</li>
 * <li>chaque pan de mur est plein, percé d'une porte (1 ou 2 blocs), réduit à un muret, ou absent ;</li>
 * <li>des grandes salles à piliers (tous les pans d'un carré de 4 × 4 pièces enlevés) ;</li>
 * <li>des zones noires (néons morts, quelques-uns qui clignotent) où vit le Souriant ;</li>
 * <li>un néon au milieu de chaque pièce, des casiers contre les murs, et de temps en temps une porte EXIT.</li>
 * </ul>
 * Hauteur : moquette en y = 0, on marche en y = 1, trois blocs d'air, plafond en y = 4.
 */
public final class Labyrinthe {
    public static final int P = 6;
    public static final int VIDE = 0, MUR = 1, CASIER = 2, PORTE = 3;
    public static final int SOL = 0, PLAFOND = 4;
    /** Les sens comme dans l'aperçu : 0 sud (+z), 1 ouest (-x), 2 nord (-z), 3 est (+x). */
    public static final int[] DX = {0, -1, 0, 1};
    public static final int[] DZ = {1, 0, -1, 0};
    public static final Direction[] DIRECTIONS = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};
    public static final int NEON_ALLUME = 1, NEON_CLIGNOTE = 2, NEON_MORT = 3;

    private static final int GRAINE = 0x2b4c0de;

    /** Un nombre entre 0 et 1 tiré de trois entiers, toujours le même (le même calcul que h3 dans monde.js). */
    public static double h3(int a, int b, int c) {
        int h = a * 0x27d4eb2d ^ b * 0x165667b1 ^ c * 0x9e3779b9 ^ GRAINE;
        h = (h ^ (h >>> 15)) * 0x85ebca6b;
        h = (h ^ (h >>> 13)) * 0xc2b2ae35;
        h ^= h >>> 16;
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }

    public static int fdiv(int a, int b) {
        return Math.floorDiv(a, b);
    }

    private static int mod(int a, int b) {
        return Math.floorMod(a, b);
    }

    /** La pièce (i, k) fait partie d'une grande salle à piliers. */
    public static boolean salle(int i, int k) {
        return h3(fdiv(i, 4), fdiv(k, 4), 31) < 0.16;
    }

    /** La pièce (i, k) est dans une zone noire. */
    public static boolean sombre(int i, int k) {
        return h3(fdiv(i, 5), fdiv(k, 5), 41) < 0.24;
    }

    /** Le bloc j (0 à 4) d'un pan de mur est-il plein ? axe 0 = la ligne z = k·P, axe 1 = la ligne x = i·P. */
    static boolean pan(int i, int k, int axe, int j) {
        boolean autre = axe == 0 ? salle(i, k - 1) : salle(i - 1, k);
        if (salle(i, k) && autre) {
            return false;
        }
        double r = h3(i, k, axe * 7 + 1), s = h3(i, k, axe * 7 + 2);
        if (r < 0.34) {
            return true;
        }
        if (r < 0.62) {
            int o = (int) Math.floor(s * 4);
            return j != o && j != o + 1;
        }
        if (r < 0.72) {
            return j != (int) Math.floor(s * 5);
        }
        if (r < 0.82) {
            return s < 0.5 ? j <= 1 : j >= 3;
        }
        return false;
    }

    /** Le néon de la pièce : 1 allumé, 2 qui clignote, 3 mort. */
    public static int neon(int i, int k) {
        double r = h3(i, k, 43);
        if (sombre(i, k)) {
            return r < 0.12 ? NEON_CLIGNOTE : NEON_MORT;
        }
        return r < 0.06 ? NEON_MORT : r < 0.13 ? NEON_CLIGNOTE : NEON_ALLUME;
    }

    /** Le bloc (x, z) du labyrinthe nu, sans casier ni porte : MUR ou VIDE. */
    public static int base(int x, int z) {
        int lx = mod(x, P), lz = mod(z, P);
        if (lx == 0 && lz == 0) {
            return MUR;
        }
        if (lz == 0) {
            return pan(fdiv(x, P), fdiv(z, P), 0, lx - 1) ? MUR : VIDE;
        }
        if (lx == 0) {
            return pan(fdiv(x, P), fdiv(z, P), 1, lz - 1) ? MUR : VIDE;
        }
        return VIDE;
    }

    /** Une case posée : un casier (et vers où il s'ouvre) ou une porte (et vers quelle pièce elle donne). */
    public record Pose(int x, int z, int sens) {
    }

    /** Le casier de la pièce (i, k), s'il y en a un : contre un mur plein, tourné vers la pièce. */
    public static Pose casier(int i, int k) {
        if (h3(i, k, 51) >= 0.34) {
            return null;
        }
        int c0 = (int) Math.floor(h3(i, k, 52) * 4);
        int pos = 2 + (int) Math.floor(h3(i, k, 53) * 3);
        for (int e = 0; e < 4; e++) {
            int cote = (c0 + e) % 4;
            int x, z, sens, mx, mz;
            switch (cote) {
                case 0 -> { x = i * P + pos; z = k * P + 1; sens = 0; mx = x; mz = k * P; }
                case 1 -> { x = i * P + pos; z = k * P + 5; sens = 2; mx = x; mz = (k + 1) * P; }
                case 2 -> { x = i * P + 1; z = k * P + pos; sens = 3; mx = i * P; mz = z; }
                default -> { x = i * P + 5; z = k * P + pos; sens = 1; mx = (i + 1) * P; mz = z; }
            }
            if (base(mx, mz) == MUR) {
                return new Pose(x, z, sens);
            }
        }
        return null;
    }

    /** La case (x, z) avec les casiers (mais sans les portes). */
    private static int sansPorte(int x, int z) {
        int b = base(x, z);
        if (b != VIDE) {
            return b;
        }
        Pose c = casier(fdiv(x, P), fdiv(z, P));
        return c != null && c.x == x && c.z == z ? CASIER : VIDE;
    }

    /** La porte EXIT de la région (rx, rz) de 8 × 8 pièces, une fois sur deux. */
    public static Pose porte(int rx, int rz) {
        if (h3(rx, rz, 61) >= 0.5) {
            return null;
        }
        int i = rx * 8 + (int) Math.floor(h3(rx, rz, 62) * 8), k = rz * 8 + (int) Math.floor(h3(rx, rz, 63) * 8);
        int c0 = (int) Math.floor(h3(i, k, 64) * 4);
        for (int e = 0; e < 4; e++) {
            int cote = (c0 + e) % 4;
            int x, z, sens;
            switch (cote) {
                case 0 -> { x = i * P + 3; z = k * P; sens = 0; }
                case 1 -> { x = i * P + 3; z = (k + 1) * P; sens = 2; }
                case 2 -> { x = i * P; z = k * P + 3; sens = 3; }
                default -> { x = (i + 1) * P; z = k * P + 3; sens = 1; }
            }
            if (base(x, z) == MUR && sansPorte(x + DX[sens], z + DZ[sens]) == VIDE && sansPorte(x + DX[sens] * 2, z + DZ[sens] * 2) == VIDE) {
                return new Pose(x, z, sens);
            }
        }
        return null;
    }

    /** La porte posée exactement en (x, z), s'il y en a une (elle est sur une ligne de murs, d'un côté ou de l'autre). */
    private static Pose porteEn(int x, int z) {
        int lx = mod(x, P), lz = mod(z, P);
        if (lx != 0 && lz != 0) {
            return null;
        }
        int i = fdiv(x, P), k = fdiv(z, P);
        int[][] pieces = lz == 0 ? new int[][]{{i, k}, {i, k - 1}} : new int[][]{{i, k}, {i - 1, k}};
        for (int[] p : pieces) {
            Pose porte = porte(fdiv(p[0], 8), fdiv(p[1], 8));
            if (porte != null && porte.x == x && porte.z == z) {
                return porte;
            }
        }
        return null;
    }

    /** Ce qu'il y a en (x, z) : VIDE, MUR, CASIER ou PORTE ; et le sens du casier ou de la porte (sinon 0). */
    public static long caseEn(int x, int z) {
        int b = base(x, z);
        if (b == MUR) {
            Pose p = porteEn(x, z);
            return p != null ? PORTE | (long) p.sens << 8 : MUR;
        }
        Pose c = casier(fdiv(x, P), fdiv(z, P));
        if (c != null && c.x == x && c.z == z) {
            return CASIER | (long) c.sens << 8;
        }
        return VIDE;
    }

    public static int type(long c) {
        return (int) (c & 0xFF);
    }

    public static int sens(long c) {
        return (int) (c >> 8 & 0xFF);
    }

    /** On peut marcher en (x, z) (ni mur, ni casier, ni porte). */
    public static boolean marchable(int x, int z) {
        return type(caseEn(x, z)) == VIDE;
    }

    /** Une pièce éclairée (néon allumé ou qui clignote). */
    public static boolean eclairee(int i, int k) {
        return neon(i, k) != NEON_MORT;
    }

    private Labyrinthe() {
    }
}
