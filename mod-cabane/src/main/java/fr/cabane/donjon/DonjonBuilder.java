package fr.cabane.donjon;

import fr.cabane.Cabane;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;

/**
 * Le donjon de rang S, construit par le mod dans la dimension vide cabane:donjon, du nord au sud :
 * <ol>
 * <li>la grotte d'arrivée, aux cristaux bleus, avec au nord le portail de sortie (dessiné par le client) ;</li>
 * <li>le carrefour, rond, avec trois sorties : l'aile ouest, l'aile est, et au sud la porte des sceaux, fermée ;</li>
 * <li>l'aile ouest : la salle des braseros (quatre feux à allumer dans l'ordre) et son sceau, un cul-de-sac effondré
 * (une embuscade) et, au bout d'un long couloir, la salle de l'indice… de l'aile est ;</li>
 * <li>l'aile est : la salle des leviers (un code) et son sceau, un cul-de-sac avec un coffre, et la salle de l'indice de
 * l'aile ouest. Il faut donc se séparer, et se parler ;</li>
 * <li>quand quelqu'un se tient sur chacun des deux sceaux en même temps, la porte des sceaux s'ouvre (voir Enigmes) :
 * un escalier qui descend, la salle des piliers, l'antichambre et la grande porte de l'arène, qui ne s'ouvre que devant
 * tous les chasseurs ;</li>
 * <li>l'arène du boss, sous un dôme, avec son trône au fond.</li>
 * </ol>
 * Tout est taillé dans la roche : on calcule d'abord le vide (les salles), puis une coque de deux blocs autour.
 */
public final class DonjonBuilder {
    /** À changer quand le donjon change : il est reconstruit à la prochaine entrée. */
    public static final int VERSION = 3;
    /** Le sol de l'arrivée (on marche à SOL + 1). */
    public static final int SOL = 64;
    /** Le sol du bas (salle des piliers, arène). */
    public static final int SOL_BAS = 56;
    /** Où l'on arrive, face au sud (le portail de sortie dans le dos). */
    public static final Vec3 ARRIVEE = new Vec3(0.5, SOL + 1, -1.5);
    /** Le portail de sortie : son centre (tourné vers le sud) et son rayon. */
    public static final Vec3 SORTIE = new Vec3(0.5, SOL + 5.5, -7.5);
    public static final double RAYON_SORTIE = 4;
    /** Le centre de l'arène du boss, au niveau du sol. */
    public static final Vec3 ARENE = new Vec3(0.5, SOL_BAS + 1, 170.5);

    private static final int X0 = -66, X1 = 66, Y0 = 50, Y1 = 92, Z0 = -15, Z1 = 201;
    private static final int NX = X1 - X0 + 1, NY = Y1 - Y0 + 1, NZ = Z1 - Z0 + 1;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final ServerLevel level;
    private final boolean[] vide = new boolean[NX * NY * NZ];
    private final boolean[] roche = new boolean[NX * NY * NZ];
    private final Random rnd = new Random(1802);

    private DonjonBuilder(ServerLevel level) {
        this.level = level;
    }

    public static void construire(ServerLevel level) {
        long t0 = System.currentTimeMillis();
        DonjonBuilder b = new DonjonBuilder(level);
        b.creuser();
        b.coque();
        b.poser();
        b.decorer();
        Cabane.LOGGER.info("Donjon de rang S construit en {} ms", System.currentTimeMillis() - t0);
    }

    // ---------------------------------------------------------------- le vide : les salles

    private static int i(int x, int y, int z) {
        return ((x - X0) * NY + (y - Y0)) * NZ + (z - Z0);
    }

    private static boolean dans(int x, int y, int z) {
        return x >= X0 && x <= X1 && y >= Y0 && y <= Y1 && z >= Z0 && z <= Z1;
    }

    private boolean estVide(int x, int y, int z) {
        return dans(x, y, z) && vide[i(x, y, z)];
    }

    /** Le sol (le bloc sur lequel on marche) du couloir qui descend : 64 jusqu'à z = 72, puis un bloc tous les deux, jusqu'à 56. */
    private static int solEscalier(int z) {
        return z < 72 ? SOL : Math.max(SOL_BAS, SOL - (z - 72) / 2);
    }

    /** Un couloir (ou une petite salle) rectangulaire au niveau de l'arrivée : x0..x1, z0..z1, sa hauteur au-dessus du sol. */
    record Rect(int x0, int x1, int z0, int z1, int h) {
        boolean dans(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }
    }

    static final Rect[] COULOIRS = {
            new Rect(-3, 3, 14, 31, 7),      // de l'arrivée au carrefour
            new Rect(-31, -10, 37, 43, 6),   // vers l'ouest
            new Rect(-38, -30, 33, 47, 8),   // la croisée de l'ouest
            new Rect(-37, -31, 14, 33, 6),   // vers le nord-ouest…
            new Rect(-52, -31, 10, 16, 6),   // …puis l'ouest
            new Rect(-60, -50, 4, 18, 7),    // la salle de l'indice des leviers (cul-de-sac)
            new Rect(-37, -31, 47, 64, 6),   // vers la salle des braseros
            new Rect(-62, -43, 70, 76, 6),   // le tunnel effondré (cul-de-sac, embuscade)
            new Rect(10, 31, 37, 43, 6),     // vers l'est
            new Rect(30, 38, 33, 47, 8),     // la croisée de l'est
            new Rect(31, 37, 16, 33, 6),     // vers le nord-est…
            new Rect(31, 50, 14, 20, 6),     // …puis l'est
            new Rect(48, 60, 6, 22, 7),      // la salle de l'indice des braseros (cul-de-sac)
            new Rect(38, 62, 37, 43, 6),     // le long couloir de l'est…
            new Rect(56, 62, 43, 66, 6),     // …qui finit en cul-de-sac (le coffre)
            new Rect(31, 37, 47, 64, 6),     // vers la salle des leviers
    };
    /** Les deux salles des énigmes : les braseros à l'ouest, les leviers à l'est ; et le carrefour. */
    public static final int[] SALLE_BRASEROS = {-34, 73};
    public static final int[] SALLE_LEVIERS = {34, 73};
    public static final int[] CARREFOUR = {0, 40};
    /** L'antichambre va jusque-là ; la grande porte est juste après, l'arène commence derrière. */
    public static final int ANTICHAMBRE_Z1 = 141;
    public static final int GRANDE_PORTE_Z = 142;
    /** La porte des sceaux, au sud du carrefour. */
    public static final int PORTE_SCEAUX_Z = 52;

    /** Le plafond de chaque endroit (le dernier bloc vide), ou -1 si on est dans la roche. */
    private static int plafond(int x, int z) {
        int ax = Math.abs(x);
        // la grotte d'arrivée : une ellipse sous un dôme
        double ra = sq(x / 11.5) + sq((z - 3) / 14.5);
        int h = -1;
        if (ra <= 1) {
            h = Math.max(h, SOL + (int) (13 - 5 * ra));
        }
        for (Rect r : COULOIRS) {
            if (r.dans(x, z)) {
                h = Math.max(h, SOL + r.h());
            }
        }
        // le carrefour et les deux salles des énigmes, rondes, sous un dôme
        double rc = (sq(x - CARREFOUR[0]) + sq(z - CARREFOUR[1])) / sq(11.5);
        if (rc <= 1) {
            h = Math.max(h, SOL + (int) (14 - 6 * rc));
        }
        for (int[] c : new int[][] {SALLE_BRASEROS, SALLE_LEVIERS}) {
            double rs = (sq(x - c[0]) + sq(z - c[1])) / sq(10.5);
            if (rs <= 1) {
                h = Math.max(h, SOL + (int) (12 - 5 * rs));
            }
        }
        // l'escalier qui descend, derrière la porte des sceaux
        if (ax <= 3 && z >= 49 && z <= 99) {
            h = Math.max(h, solEscalier(z) + (ax == 3 ? 7 : 8));
        }
        // la salle des piliers
        if (ax <= 15 && z >= 97 && z <= 129) {
            h = Math.max(h, SOL_BAS + (ax >= 13 ? 12 : 16));
        }
        // l'antichambre, devant la grande porte
        if (ax <= 8 && z >= 128 && z <= ANTICHAMBRE_Z1) {
            h = Math.max(h, SOL_BAS + (ax >= 7 ? 11 : 13));
        }
        // l'arène du boss, sous son dôme
        double r2 = (sq(x) + sq(z - 170)) / sq(27.5);
        if (r2 <= 1) {
            h = Math.max(h, SOL_BAS + (int) (30 - 13 * r2));
        }
        return h;
    }

    /** Le sol de chaque endroit (le bloc sur lequel on marche). */
    static int sol(int x, int z) {
        if (z >= 49 && z <= 96 && Math.abs(x) <= 3) {
            return solEscalier(z);
        }
        return z >= 88 && Math.abs(x) <= 30 ? SOL_BAS : SOL;
    }

    private static double sq(double v) {
        return v * v;
    }

    private void creuser() {
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                int h = plafond(x, z);
                if (h < 0) {
                    continue;
                }
                int s = sol(x, z);
                for (int y = s + 1; y <= h; y++) {
                    if (dans(x, y, z)) {
                        vide[i(x, y, z)] = true;
                    }
                }
            }
        }
    }

    /** La roche : deux blocs d'épaisseur tout autour du vide (un filtre max, axe par axe). */
    private void coque() {
        boolean[] a = vide.clone();
        boolean[] b = new boolean[a.length];
        for (int axe = 0; axe < 3; axe++) {
            for (int x = X0; x <= X1; x++) {
                for (int y = Y0; y <= Y1; y++) {
                    for (int z = Z0; z <= Z1; z++) {
                        boolean v = false;
                        for (int d = -2; d <= 2 && !v; d++) {
                            int xx = x + (axe == 0 ? d : 0), yy = y + (axe == 1 ? d : 0), zz = z + (axe == 2 ? d : 0);
                            v = dans(xx, yy, zz) && a[i(xx, yy, zz)];
                        }
                        b[i(x, y, z)] = v;
                    }
                }
            }
            boolean[] t = a;
            a = b;
            b = t;
        }
        for (int k = 0; k < a.length; k++) {
            roche[k] = a[k] && !vide[k];
        }
    }

    // ---------------------------------------------------------------- les blocs

    private static long hash(int x, int y, int z) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L;
        h ^= h >>> 13;
        h *= 0x5bd1e995L;
        return h ^ (h >>> 15);
    }

    private BlockState mur(int x, int y, int z) {
        int r = (int) Math.floorMod(hash(x, y, z), 100L);
        // des veines violettes, rares
        if (r < 1) {
            return Blocks.CRYING_OBSIDIAN.defaultBlockState();
        }
        if (r < 46) {
            return Blocks.DEEPSLATE.defaultBlockState();
        }
        if (r < 66) {
            return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        }
        if (r < 76) {
            return Blocks.TUFF.defaultBlockState();
        }
        if (r < 88) {
            return Blocks.BLACKSTONE.defaultBlockState();
        }
        return Blocks.SMOOTH_BASALT.defaultBlockState();
    }

    private BlockState sol(int x, int y, int z) {
        int r = (int) Math.floorMod(hash(x, y, z), 100L);
        int ax = Math.abs(x);
        // les couloirs : des dalles de pierre
        if (couloir(x, z) || (ax <= 2 && z >= 49 && z <= 96) || (ax <= 8 && z >= 130 && z <= ANTICHAMBRE_Z1)) {
            return r < 60 ? Blocks.DEEPSLATE_TILES.defaultBlockState() : r < 85 ? Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState()
                    : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        }
        // la salle des piliers : le tapis rouge au milieu, des briques noires autour
        if (z >= 97 && z <= 129 && ax <= 15) {
            if (ax <= 1) {
                return Blocks.RED_NETHER_BRICKS.defaultBlockState();
            }
            return r < 70 ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState() : Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        }
        // l'arène : des anneaux
        double d = Math.sqrt(sq(x) + sq(z - 170));
        if (z >= 143) {
            if (d < 4) {
                return Blocks.CRYING_OBSIDIAN.defaultBlockState();
            }
            if (Math.abs(d - 9) < 0.7 || Math.abs(d - 18) < 0.7) {
                return Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
            }
            return r < 75 ? Blocks.POLISHED_BLACKSTONE.defaultBlockState() : Blocks.POLISHED_BASALT.defaultBlockState();
        }
        // les grottes : une roche brute
        return r < 45 ? Blocks.DEEPSLATE.defaultBlockState() : r < 75 ? Blocks.COBBLED_DEEPSLATE.defaultBlockState()
                : r < 90 ? Blocks.TUFF.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
    }

    private static boolean couloir(int x, int z) {
        for (Rect r : COULOIRS) {
            if (r.dans(x, z) && Math.min(r.x1() - r.x0(), r.z1() - r.z0()) <= 6) {
                return true;
            }
        }
        return false;
    }

    private void pose(int x, int y, int z, BlockState s) {
        BlockPos p = new BlockPos(x, y, z);
        if (level.getBlockState(p) != s) {
            level.setBlock(p, s, FLAGS);
        }
    }

    private void poser() {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x <= X1; x++) {
            for (int y = Y0; y <= Y1; y++) {
                for (int z = Z0; z <= Z1; z++) {
                    int k = i(x, y, z);
                    if (vide[k]) {
                        pose(x, y, z, air);
                    } else if (roche[k]) {
                        boolean dessus = estVide(x, y + 1, z);
                        boolean dessous = estVide(x, y - 1, z);
                        pose(x, y, z, dessus ? sol(x, y, z) : dessous ? plafondBloc(x, y, z) : mur(x, y, z));
                    } else {
                        pose(x, y, z, air);
                    }
                }
            }
        }
    }

    private BlockState plafondBloc(int x, int y, int z) {
        int r = (int) Math.floorMod(hash(x, y, z), 100L);
        return r < 55 ? Blocks.DEEPSLATE.defaultBlockState() : r < 80 ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.SMOOTH_BASALT.defaultBlockState();
    }

    // ---------------------------------------------------------------- le décor

    private void decorer() {
        escalier();
        lumieres();
        lichen();
        stalactites();
        cristaux();
        portailDeSortie();
        piliers();
        grandePorte();
        arene();
        culsDeSac();
        sallesDesEnigmes();
        panneaux();
        // les portes fermées, les braseros éteints, les leviers baissés
        Enigmes.reinitialiser(level);
    }

    /** Les marches du couloir qui descend : là où le sol baisse, une marche (on remonte sans sauter). */
    private void escalier() {
        BlockState marche = Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH).setValue(StairBlock.HALF, Half.BOTTOM);
        for (int z = 73; z <= 89; z++) {
            int s = solEscalier(z);
            if (s < solEscalier(z - 1)) {
                for (int x = -3; x <= 3; x++) {
                    pose(x, s + 1, z, marche);
                }
            }
        }
    }

    /** Une lumière invisible (le bloc « lumière ») : on y voit, sans que rien ne gâche la pénombre bleue. */
    private void lumiere(int x, int y, int z, int niveau) {
        if (estVide(x, y, z)) {
            pose(x, y, z, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, niveau));
        }
    }

    /** Une lanterne d'âme suspendue par une chaîne au plafond, au-dessus de ce point. */
    private void lanterne(int x, int z, int longueur) {
        int h = plafond(x, z);
        if (h < 0) {
            return;
        }
        for (int k = 0; k < longueur; k++) {
            pose(x, h - k, z, Blocks.CHAIN.defaultBlockState());
        }
        pose(x, h - longueur, z, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    private void lumieres() {
        // chaque couloir : une lanterne tous les sept blocs le long de son axe, et de la lumière à hauteur d'homme
        for (Rect r : COULOIRS) {
            boolean enX = r.x1() - r.x0() > r.z1() - r.z0();
            int cx = (r.x0() + r.x1()) / 2, cz = (r.z0() + r.z1()) / 2;
            int de = enX ? r.x0() + 2 : r.z0() + 2, a = enX ? r.x1() - 2 : r.z1() - 2;
            for (int k = de; k <= a; k += 7) {
                int x = enX ? k : cx, z = enX ? cz : k;
                lanterne(x, z, 2);
                lumiere(x, SOL + 3, z, 10);
            }
        }
        for (int z = 56; z <= 98; z += 7) {
            lanterne(0, z, 2);
            lumiere(0, solEscalier(z) + 3, z, 11);
        }
        for (int z = 131; z <= ANTICHAMBRE_Z1; z += 5) {
            lanterne(-5, z, 3);
            lanterne(5, z, 3);
            lumiere(0, SOL_BAS + 4, z, 13);
        }
        // les salles : une grille de lumières douces
        for (int x = -60; x <= 60; x += 6) {
            for (int z = -10; z <= 196; z += 6) {
                if (plafond(x, z) < 0) {
                    continue;
                }
                lumiere(x, sol(x, z) + 4, z, z >= 143 ? 13 : 9);
            }
        }
        // l'arène : on doit bien y voir le boss (une lumière vive au sol, une autre à mi-hauteur)
        for (int x = -24; x <= 24; x += 4) {
            for (int z = 145; z <= 196; z += 4) {
                if (plafond(x, z) > 0) {
                    lumiere(x, SOL_BAS + 2, z, 15);
                    lumiere(x, SOL_BAS + 12, z, 15);
                }
            }
        }
        // la grotte, le carrefour et les salles des énigmes : des lanternes au bout de longues chaînes
        for (int[] p : new int[][] {{-6, 6}, {6, 0}, {0, 10}, {-6, 36}, {6, 44}, {0, 40}, {-34, 73}, {34, 73}, {-30, 68}, {38, 68}}) {
            lanterne(p[0], p[1], 2 + Math.floorMod(p[0] + p[1], 3));
        }
    }

    /** Du lichen luisant sur la roche des grottes : la lueur bleu-vert qui fait vivre les murs. */
    private void lichen() {
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (int x = X0 + 1; x < X1; x++) {
            for (int y = Y0 + 1; y < Y1; y++) {
                for (int z = Z0 + 1; z < Z1; z++) {
                    if (!vide[i(x, y, z)] || z >= 97) {
                        continue;
                    }
                    for (Direction d : dirs) {
                        int xx = x + d.getStepX(), yy = y + d.getStepY(), zz = z + d.getStepZ();
                        if (dans(xx, yy, zz) && roche[i(xx, yy, zz)] && Math.floorMod(hash(x, y, z) + d.ordinal(), 23L) == 0) {
                            pose(x, y, z, Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(d), true));
                            break;
                        }
                    }
                }
            }
        }
    }

    /** Des stalactites qui pendent des voûtes des grottes. */
    private void stalactites() {
        for (int x = X0 + 1; x < X1; x++) {
            for (int z = Z0 + 1; z < 90; z++) {
                int h = plafond(x, z);
                if (h < 0 || Math.floorMod(hash(x, 7, z), 17L) != 0 || !estVide(x, h, z) || h - sol(x, z) < 7) {
                    continue;
                }
                int n = 1 + (int) Math.floorMod(hash(z, 3, x), 3L);
                for (int k = 0; k < n; k++) {
                    DripstoneThickness e = k == n - 1 ? DripstoneThickness.TIP : k == 0 && n > 2 ? DripstoneThickness.BASE : DripstoneThickness.MIDDLE;
                    if (n == 2 && k == 0) {
                        e = DripstoneThickness.FRUSTUM;
                    }
                    pose(x, h - k, z, Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN)
                            .setValue(PointedDripstoneBlock.THICKNESS, e));
                }
            }
        }
    }

    /** Un cristal bleu : une colonne de verre bleu clair avec une lanterne marine dedans, qui brille à travers. */
    private void cristal(int x, int z, int hauteur) {
        int s = sol(x, z);
        for (int k = 1; k <= hauteur; k++) {
            if (!estVide(x, s + k, z)) {
                return;
            }
            pose(x, s + k, z, k == 1 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
        }
        if (estVide(x, s + hauteur + 1, z)) {
            pose(x, s + hauteur + 1, z, Blocks.AMETHYST_CLUSTER.defaultBlockState());
        }
    }

    private void cristaux() {
        // des grappes de cristaux le long des parois de la grotte d'arrivée et de la première salle
        int[][] grappes = {{-9, -2}, {9, 1}, {-10, 6}, {10, 8}, {-7, 13}, {7, 14}, {-5, -8}, {6, -8}, {-9, 33}, {9, 47}, {-8, 47}, {8, 33},
                {-41, 68}, {-27, 79}, {41, 67}, {27, 79}, {-57, 8}, {55, 18}};
        for (int[] g : grappes) {
            for (int k = 0; k < 4; k++) {
                int dx = (int) Math.floorMod(hash(g[0], k, g[1]), 3L) - 1, dz = (int) Math.floorMod(hash(g[1], k, g[0]), 3L) - 1;
                cristal(g[0] + dx * (k > 0 ? 1 : 0), g[1] + dz * (k > 0 ? 1 : 0), k == 0 ? 4 : 1 + k % 3);
            }
        }
        // des améthystes, ici et là, sur le sol des grottes
        for (int x = -62; x <= 62; x++) {
            for (int z = -10; z <= 84; z++) {
                int s = sol(x, z);
                if (estVide(x, s + 1, z) && Math.floorMod(hash(x, s, z), 41L) == 0 && Math.abs(x) > 3) {
                    pose(x, s + 1, z, Blocks.LARGE_AMETHYST_BUD.defaultBlockState());
                }
            }
        }
    }

    /** Sous le portail de sortie : une estrade de pierre polie en demi-cercle, et des braseros d'âmes de chaque côté. */
    private void portailDeSortie() {
        int z0 = (int) Math.floor(SORTIE.z);
        for (int x = -5; x <= 5; x++) {
            for (int z = z0 - 1; z <= z0 + 2; z++) {
                if (estVide(x, SOL + 1, z)) {
                    pose(x, SOL, z, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                }
            }
        }
        for (int x : new int[] {-5, 6}) {
            if (estVide(x, SOL + 1, z0 + 1)) {
                pose(x, SOL + 1, z0 + 1, Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
            }
        }
    }

    /** La salle des piliers : deux rangées de piliers noirs, une lanterne d'âme au bout d'une chaîne entre chaque. */
    private void piliers() {
        for (int z : new int[] {103, 112, 121}) {
            for (int x : new int[] {-9, 8}) {
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        int h = plafond(x + dx, z + dz);
                        for (int y = SOL_BAS + 1; y <= h; y++) {
                            boolean bout = y == SOL_BAS + 1 || y == h;
                            pose(x + dx, y, z + dz, bout ? Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState()
                                    : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                        }
                    }
                }
                lanterne(x + (x < 0 ? 4 : -3), z, 5);
                pose(x + (x < 0 ? 2 : -1), SOL_BAS + 1, z, Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
            }
        }
    }

    /** Le mur de la grande porte de l'arène : de la pierre noire aux bords violets (la porte elle-même : Enigmes). */
    private void grandePorte() {
        int z = GRANDE_PORTE_Z;
        for (int x = -9; x <= 9; x++) {
            for (int y = SOL_BAS + 1; y <= SOL_BAS + 14; y++) {
                if (Math.abs(x) <= 3 && y <= SOL_BAS + 9) {
                    continue;
                }
                boolean bord = Math.abs(x) == 4 && y <= SOL_BAS + 10 || y == SOL_BAS + 10 && Math.abs(x) <= 4;
                pose(x, y, z, bord ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            }
        }
        // deux braseros de part et d'autre, côté antichambre
        for (int x : new int[] {-6, 6}) {
            pose(x, SOL_BAS + 1, z - 1, Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        }
    }

    /** Les culs-de-sac : le tunnel effondré à l'ouest (un éboulis, des toiles), le coffre au bout de l'est. */
    private void culsDeSac() {
        // l'éboulis au bout du tunnel de l'ouest
        for (int x = -62; x <= -56; x++) {
            for (int z = 70; z <= 76; z++) {
                int haut = SOL + Math.max(1, 6 - (x + 62)) - (int) Math.floorMod(hash(x, 1, z), 2L);
                for (int y = SOL + 1; y <= haut; y++) {
                    pose(x, y, z, Math.floorMod(hash(x, y, z), 3L) == 0 ? Blocks.GRAVEL.defaultBlockState() : Blocks.COBBLED_DEEPSLATE.defaultBlockState());
                }
            }
        }
        // des toiles d'araignée dans les coins des culs-de-sac et des salles des indices
        for (int[] t : new int[][] {{-54, SOL + 6, 71}, {-50, SOL + 1, 75}, {-46, SOL + 5, 70}, {-59, SOL + 6, 5}, {-51, SOL + 7, 17}, {59, SOL + 6, 7},
                {49, SOL + 6, 21}, {61, SOL + 5, 44}, {57, SOL + 1, 60}, {61, SOL + 6, 65}, {57, SOL + 5, 50}}) {
            if (estVide(t[0], t[1], t[2])) {
                pose(t[0], t[1], t[2], Blocks.COBWEB.defaultBlockState());
            }
        }
        // des os par terre
        for (int[] o : new int[][] {{-52, 73}, {-49, 71}, {58, 62}, {60, 57}, {-57, 12}, {53, 9}}) {
            pose(o[0], SOL + 1, o[1], Blocks.BONE_BLOCK.defaultBlockState());
        }
        Enigmes.coffre(level, new BlockPos(59, SOL + 1, 65));
    }

    /**
     * Les deux salles des énigmes. À l'ouest, quatre braseros (nord, sud, est, ouest) sur une dalle ciselée, un bouton
     * devant chacun, et le sceau au sud. À l'est, quatre leviers numérotés (I à IV) sur leurs socles, un cristal au-dessus de
     * chacun, et le sceau au sud.
     */
    private void sallesDesEnigmes() {
        int bx = SALLE_BRASEROS[0], bz = SALLE_BRASEROS[1];
        for (Enigmes.Brasero b : Enigmes.BRASEROS) {
            pose(bx + b.dx(), SOL, bz + b.dz(), Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
        }
        int lx = SALLE_LEVIERS[0], lz = SALLE_LEVIERS[1];
        for (int k = 0; k < 4; k++) {
            int x = lx - 3 + k * 2;
            pose(x, SOL + 1, lz + 5, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
            pose(x, SOL + 1, lz + 6, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            pose(x, SOL + 2, lz + 6, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        }
        // les deux sceaux : un carré de pierre ciselée autour d'un cœur d'obsidienne pleureuse
        for (int[] c : new int[][] {SALLE_BRASEROS, SALLE_LEVIERS}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pose(c[0] + dx, SOL, c[1] + Enigmes.DZ_SCEAU + dz,
                            dx == 0 && dz == 0 ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
                }
            }
        }
    }

    /** Les panneaux : les indices au fond des culs-de-sac, les noms des braseros et des leviers, les mots devant les portes. */
    private void panneaux() {
        // l'indice des leviers… à l'ouest
        Enigmes.panneau(level, new BlockPos(-56, SOL + 1, 11), 12, "LEVIERS DE L'EST", "Seuls le I", "et le III", "doivent briller.");
        Enigmes.panneau(level, new BlockPos(-56, SOL + 1, 13), 12, "Les autres", "dorment.", "", "");
        // l'indice des braseros… à l'est
        Enigmes.panneau(level, new BlockPos(55, SOL + 1, 13), 4, "FEUX DE L'OUEST", "1. le NORD", "2. le SUD", "3. l'EST");
        Enigmes.panneau(level, new BlockPos(55, SOL + 1, 15), 4, "4. l'OUEST", "", "Dans cet ordre,", "sinon gare.");
        // les noms des braseros et des leviers
        int bx = SALLE_BRASEROS[0], bz = SALLE_BRASEROS[1];
        for (Enigmes.Brasero b : Enigmes.BRASEROS) {
            int px = bx + b.dx() + (b.dz() != 0 ? 1 : 0), pz = bz + b.dz() + (b.dx() != 0 ? 1 : 0);
            Enigmes.panneau(level, new BlockPos(px, SOL + 1, pz), b.versCentre(), "", b.nom(), "", "");
        }
        String[] chiffres = {"I", "II", "III", "IV"};
        for (int k = 0; k < 4; k++) {
            Enigmes.panneau(level, new BlockPos(SALLE_LEVIERS[0] - 3 + k * 2, SOL + 1, SALLE_LEVIERS[1] + 3), 8, "", chiffres[k], "", "");
        }
        Enigmes.panneau(level, new BlockPos(SALLE_BRASEROS[0] + 2, SOL + 1, SALLE_BRASEROS[1] + Enigmes.DZ_SCEAU), 8, "SCEAU DE", "L'OUEST", "", "");
        Enigmes.panneau(level, new BlockPos(SALLE_LEVIERS[0] - 2, SOL + 1, SALLE_LEVIERS[1] + Enigmes.DZ_SCEAU), 8, "SCEAU DE", "L'EST", "", "");
        // devant la porte des sceaux, et devant la grande porte
        Enigmes.panneau(level, new BlockPos(-2, SOL + 1, PORTE_SCEAUX_Z - 2), 8, "PORTE DES", "SCEAUX", "Tenez les deux", "en même temps.");
        Enigmes.panneau(level, new BlockPos(2, SOL + 1, PORTE_SCEAUX_Z - 2), 8, "Deux salles,", "deux énigmes,", "deux sceaux.", "");
        Enigmes.panneau(level, new BlockPos(-3, SOL_BAS + 1, GRANDE_PORTE_Z - 3), 8, "Elle ne s'ouvre", "que devant", "TOUS les", "chasseurs.");
        // les directions, au carrefour
        Enigmes.panneau(level, new BlockPos(-9, SOL + 1, CARREFOUR[1] - 2), 4, "", "<- OUEST", "", "");
        Enigmes.panneau(level, new BlockPos(9, SOL + 1, CARREFOUR[1] - 2), 12, "", "EST ->", "", "");
    }

    /** L'arène : un anneau de grands piliers, des braseros, et le trône au fond, sur son estrade. */
    private void arene() {
        int cz = 170;
        for (int k = 0; k < 12; k++) {
            double a = k * Math.PI / 6;
            int x = (int) Math.round(Math.cos(a) * 22), z = cz + (int) Math.round(Math.sin(a) * 22);
            // pas de pilier devant la porte
            if (z < cz - 18 && Math.abs(x) < 8) {
                continue;
            }
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    int h = plafond(x + dx, z + dz);
                    for (int y = SOL_BAS + 1; y <= h; y++) {
                        pose(x + dx, y, z + dz, y % 6 == 0 ? Blocks.CHISELED_DEEPSLATE.defaultBlockState() : Blocks.DEEPSLATE_TILES.defaultBlockState());
                    }
                }
            }
            int bx = (int) Math.round(Math.cos(a) * 19), bz = cz + (int) Math.round(Math.sin(a) * 19);
            pose(bx, SOL_BAS + 1, bz, Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
            lumiere(bx, SOL_BAS + 6, bz, 14);
        }
        // l'estrade et le trône
        int zt = 192;
        for (int marche = 0; marche < 3; marche++) {
            for (int x = -6 + marche; x <= 6 - marche; x++) {
                for (int z = zt - 4 + marche; z <= zt + 2; z++) {
                    if (estVide(x, SOL_BAS + 1 + marche, z)) {
                        pose(x, SOL_BAS + 1 + marche, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                    }
                }
            }
        }
        int y = SOL_BAS + 4;
        pose(0, y, zt, Blocks.POLISHED_BLACKSTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        for (int k = 1; k <= 5; k++) {
            pose(0, y + k, zt + 1, k == 5 ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        }
        for (int x : new int[] {-1, 1}) {
            pose(x, y, zt, Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
            for (int k = 0; k <= 3; k++) {
                pose(x, y + k, zt + 1, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            }
        }
        for (int x : new int[] {-3, 3}) {
            pose(x, y, zt, Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        }
    }
}
