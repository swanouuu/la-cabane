package fr.cabane.arene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/**
 * L'arène, reprise du croquis validé (docs/arene/maquette.html) : un stade façon château (deux demi-cercles reliés par
 * deux lignes droites), sol de 96 × 52, rempart moussu et grille en fer, 28 rangs de gradins en bois jusqu'à 32 blocs,
 * muraille crénelée et ses 12 tours, la loge royale tout en haut au sud, l'écran géant pendu au milieu par des chaînes, et
 * derrière la loge la salle d'entrée (porte bleue à l'ouest, rouge à l'est, porte des gradins côté arène).
 *
 * <p>Tout est écrit dans un plan (coordonnées du croquis : le milieu de l'arène en (0, 0), le dessus du sol à y = 0), puis
 * posé dans la dimension avec le décalage {@link #SOL}. Le côté bleu est à l'ouest (x négatif), le rouge à l'est.
 */
public final class AreneBuilder {
    /** À changer après toute modification : l'arène se reconstruit à la prochaine visite. */
    public static final int VERSION = 1;
    /** Le dessus du sol de l'arène. */
    public static final int SOL = 64;

    /** Le stade : demi-longueur de la ligne droite, rayon des bouts, nombre de rangs de gradins. */
    static final int L = 22, R = 26, N = 28;
    static final int HAUT_ALLEE = 3 + N + 1, HAUT_MUR = HAUT_ALLEE + 5, FACADE = N + 5;
    /** La loge : l'allée du haut côté sud (z positif), de ZL à ZL + 2 ; la salle d'entrée derrière, de SZ0 à SZ1. */
    static final int ZL = R + N + 2, SZ0 = 76, SZ1 = 96;
    /** L'écran géant : son centre (y du plan), la demi-largeur du caisson. */
    public static final double ECRAN_Y = 50.5;
    public static final int DEMI = 13;
    /** Les 4 tours des coins, qui tiennent l'écran : x, z, hauteur. */
    static final double RT = R + FACADE + 3.5;

    // ---------------------------------------------------------------- ce qu'on lit pendant le jeu

    /** Distance au bord du sol de l'arène (négative dedans). */
    public static double ecart(double cx, double cz) {
        return Math.hypot(Math.max(Math.abs(cx) - L, 0), cz) - R;
    }

    /** Le point du sol le plus proche, rentré de {@code marge} blocs depuis le bord (pour repousser quelqu'un dans l'arène). */
    public static Vec3 rentrer(double x, double y, double z, double marge) {
        double sx = Math.max(-L, Math.min(L, x));
        double dx = x - sx, dz = z, d = Math.hypot(dx, dz);
        if (d < 1e-3) {
            return new Vec3(x, y, z);
        }
        double r = R - marge;
        return new Vec3(sx + dx / d * r, y, dz / d * r);
    }

    /** Dans la salle d'entrée. */
    public static boolean dansSalle(double x, double z) {
        return x >= -12 && x < 12 && z >= SZ0 + 1 && z < SZ1;
    }

    /** L'arrivée dans la salle d'entrée, tourné vers l'arène. */
    public static Vec3 arriveeSalle() {
        return new Vec3(0, SOL, SZ1 - 2.5);
    }

    public static final float SALLE_YAW = 180F;

    /** Le milieu de la base d'un côté (0 bleu à l'ouest, 1 rouge à l'est), sur la dalle. */
    public static Vec3 base(int camp) {
        return new Vec3(camp == 0 ? -41.5 : 41.5, SOL + 1, 0.5);
    }

    public static float baseYaw(int camp) {
        return camp == 0 ? -90F : 90F;
    }

    /** L'arrivée des spectateurs : la loge royale, en haut des gradins. */
    public static Vec3 loge() {
        return new Vec3(0, SOL + HAUT_ALLEE + 1, ZL + 1.5);
    }

    public static final float LOGE_YAW = 180F;

    /** Le haut de chacune des 4 tours qui tiennent l'écran (d'où partent les chaînes). */
    static List<Vec3> toursEcran() {
        List<Vec3> l = new ArrayList<>();
        for (int sx : new int[] {-1, 1}) {
            for (int sz : new int[] {-1, 1}) {
                l.add(new Vec3(sx * L, 61, sz * RT));
            }
        }
        return l;
    }

    // ---------------------------------------------------------------- le plan

    private final Map<Long, BlockState> plan = new HashMap<>();
    /** Les blocs du sol et des couverts (pour les réparer si un pouvoir en casse un). */
    private final List<Long> fragiles = new ArrayList<>();

    private static BlockState s(Block b) {
        return b.defaultBlockState();
    }

    private static long cle(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private void pose(int x, int y, int z, BlockState b) {
        if (b == null) {
            plan.remove(cle(x, y, z));
        } else {
            plan.put(cle(x, y, z), b);
        }
    }

    private void remplit(int xa, int xb, int ya, int yb, int za, int zb, BlockState b) {
        for (int x = Math.min(xa, xb); x <= Math.max(xa, xb); x++) {
            for (int y = Math.min(ya, yb); y <= Math.max(ya, yb); y++) {
                for (int z = Math.min(za, zb); z <= Math.max(za, zb); z++) {
                    pose(x, y, z, b);
                }
            }
        }
    }

    /** Pareil des deux côtés (miroir ouest-est), et au besoin aussi au nord et au sud. */
    private void quatre(int xa, int xb, int ya, int yb, int za, int zb, BlockState b, boolean nordSud) {
        int x0 = Math.min(xa, xb), x1 = Math.max(xa, xb), z0 = Math.min(za, zb), z1 = Math.max(za, zb);
        remplit(x0, x1, ya, yb, z0, z1, b);
        remplit(-x1 - 1, -x0 - 1, ya, yb, z0, z1, b);
        if (nordSud) {
            remplit(x0, x1, ya, yb, -z1 - 1, -z0 - 1, b);
            remplit(-x1 - 1, -x0 - 1, ya, yb, -z1 - 1, -z0 - 1, b);
        }
    }

    private static int mod(int a, int n) {
        return ((a % n) + n) % n;
    }

    private static double hache(int x, int y, int z) {
        double s = Math.sin(x * 127.1 + y * 311.7 + z * 74.7) * 43758.5453;
        return s - Math.floor(s);
    }

    private static final BlockState SABLE = s(Blocks.SAND), SOUS_SOL = s(Blocks.SANDSTONE), TERRE = s(Blocks.COARSE_DIRT), HERBE = s(Blocks.GRASS_BLOCK);
    private static final BlockState TAILLEE = s(Blocks.SMOOTH_STONE), PIERRE = s(Blocks.STONE_BRICKS), MOUSSE = s(Blocks.MOSSY_STONE_BRICKS);
    private static final BlockState SOMBRE = s(Blocks.DEEPSLATE_BRICKS), OMBRE = s(Blocks.POLISHED_BLACKSTONE), BARREAUX = s(Blocks.IRON_BARS);
    private static final BlockState CHENE = s(Blocks.OAK_PLANKS), SAPIN = s(Blocks.SPRUCE_PLANKS), MARCHE = s(Blocks.POLISHED_ANDESITE);
    private static final BlockState BLEU = s(Blocks.BLUE_WOOL), ROUGE = s(Blocks.RED_WOOL), JAUNE = s(Blocks.YELLOW_WOOL);
    private static final BlockState RONDIN = s(Blocks.SPRUCE_LOG), POTEAU = s(Blocks.SPRUCE_FENCE), TONNEAU = s(Blocks.BARREL);
    private static final BlockState CAISSE = s(Blocks.STRIPPED_OAK_WOOD), FOIN = s(Blocks.HAY_BLOCK), OR = s(Blocks.GOLD_BLOCK);
    private static final BlockState FEUILLES = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    private static final BlockState BRASERO = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true);
    private static final BlockState TAPIS_ROUGE = s(Blocks.RED_CARPET), TAPIS_BLEU = s(Blocks.BLUE_CARPET), TAPIS_OR = s(Blocks.YELLOW_CARPET);
    private static final BlockState ECRAN = s(Blocks.BLACK_CONCRETE), CAISSON = s(Blocks.DARK_OAK_PLANKS);
    private static final BlockState BANC = Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);

    private static BlockState vieux(int x, int y, int z, BlockState b) {
        return hache(x, y, z) < 0.22 ? MOUSSE : b;
    }

    private static BlockState drapDe(double cx) {
        return cx < -4.5 ? BLEU : cx > 4.5 ? ROUGE : JAUNE;
    }

    private static double longueurAnneau(double cx, double cz, double d) {
        return Math.abs(cx) <= L ? cx : Math.atan2(cz, Math.abs(cx) - L) * (R + d);
    }

    private static int rang(double d) {
        return d < 1.4 ? 0 : (int) Math.floor(d - 0.4);
    }

    private static double bruitSol(int x, int z) {
        return Math.sin(x * 0.21 + Math.cos(z * 0.17) * 2.1) + Math.sin(z * 0.27 + x * 0.06) * 0.9 + Math.sin((x + z) * 0.11) * 0.6;
    }

    private void planifier() {
        stade();
        bases();
        couverts();
        logeRoyale();
        tours();
        ecranGeant();
        salleEntree();
        iles();
    }

    // ---------------------------------------------------------------- les îles flottantes, tout autour, dans le ciel

    /** Une île : son centre (le dessus de l'herbe), son rayon, et ce qu'il y a dessus. */
    private record Ile(int x, int y, int z, int r, int sorte) {
    }

    /** 0 donjon en ruine, 1 squelette de dragon, 2 bosquet et autel, 3 château, 4 moulin en ruine. */
    static final Ile[] ILES = {
        new Ile(-112, 72, -58, 15, 0),
        new Ile(118, 86, -28, 13, 1),
        new Ile(-66, 98, -122, 11, 2),
        new Ile(4, 112, -148, 18, 3),
        new Ile(100, 66, 118, 12, 4),
        new Ile(-104, 92, 96, 10, 2),
    };

    private void iles() {
        for (Ile i : ILES) {
            ile(i);
        }
    }

    private void ile(Ile i) {
        int r = i.r();
        // le dessous en cône irrégulier : pierre, terre, un peu de minerai ; le dessus en herbe
        for (int dx = -r - 2; dx <= r + 2; dx++) {
            for (int dz = -r - 2; dz <= r + 2; dz++) {
                double d = Math.hypot(dx, dz) + (hache(i.x() + dx, 7, i.z() + dz) - 0.5) * 2.2;
                if (d > r) {
                    continue;
                }
                double f = 1 - d / r;
                int fond = (int) Math.round(-(r * 1.15) * Math.pow(f, 0.7) - 1 - hache(dx, 3, dz) * 3);
                for (int y = fond; y <= 0; y++) {
                    BlockState b;
                    if (y == 0) {
                        b = d > r - 1.2 && hache(dx, 1, dz) < 0.4 ? s(Blocks.MOSS_BLOCK) : HERBE;
                    } else if (y > -3) {
                        b = s(Blocks.DIRT);
                    } else {
                        double h = hache(i.x() + dx, y, i.z() + dz);
                        b = h < 0.05 ? s(Blocks.COPPER_ORE) : h < 0.25 ? s(Blocks.ANDESITE) : h < 0.35 ? s(Blocks.TUFF) : s(Blocks.STONE);
                    }
                    pose(i.x() + dx, i.y() + y, i.z() + dz, b);
                }
                // des chaînes et des lianes qui pendent dessous
                if (hache(dx, 9, dz) < 0.035 && f > 0.15) {
                    int n = 4 + (int) (hache(dz, 2, dx) * 9);
                    BlockState pend = hache(dx, 4, dz) < 0.5 ? s(Blocks.CHAIN) : s(Blocks.CAVE_VINES_PLANT);
                    for (int k = 1; k <= n; k++) {
                        pose(i.x() + dx, i.y() + fond - k, i.z() + dz, pend);
                    }
                }
            }
        }
        int x = i.x(), y = i.y() + 1, z = i.z();
        switch (i.sorte()) {
            case 0 -> donjon(x, y, z);
            case 1 -> dragonOs(x, y, z);
            case 2 -> {
                arbre(x - 4, y, z - 3, 7);
                arbre(x + 4, y, z + 2, 6);
                arbre(x - 2, y, z + 5, 5);
                remplit(x - 1, x + 1, y, y, z - 1, z + 1, TAILLEE);
                pose(x, y + 1, z, BRASERO);
            }
            case 3 -> chateau(x, y, z);
            default -> moulin(x, y, z);
        }
    }

    private void arbre(int x, int y, int z, int h) {
        remplit(x, x, y, y + h - 1, z, z, s(Blocks.SPRUCE_LOG));
        for (int dy = h - 4; dy <= h + 1; dy++) {
            int rr = dy >= h ? 1 : 2;
            for (int dx = -rr; dx <= rr; dx++) {
                for (int dz = -rr; dz <= rr; dz++) {
                    if ((dx != 0 || dz != 0 || dy >= h) && Math.abs(dx) + Math.abs(dz) <= rr + 1) {
                        pose(x + dx, y + dy, z + dz, Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                    }
                }
            }
        }
    }

    /** Un donjon carré en ruine : le haut cassé en marches, des fenêtres, des pierres tombées autour. */
    private void donjon(int x, int y, int z) {
        for (int dy = 0; dy < 22; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    boolean bord = Math.abs(dx) == 4 || Math.abs(dz) == 4;
                    if (!bord) {
                        continue;
                    }
                    int casse = 12 + (int) (Math.abs(dx * 1.3 + dz * 0.7) % 9);
                    if (dy > casse) {
                        continue;
                    }
                    boolean fenetre = (dx == 0 || dz == 0) && dy % 6 >= 3 && dy % 6 <= 4;
                    pose(x + dx, y + dy, z + dz, fenetre ? null : vieux(x + dx, y + dy, z + dz, PIERRE));
                }
            }
        }
        remplit(x - 3, x + 3, y + 7, y + 7, z - 3, z + 3, SAPIN);
        for (int[] c : new int[][] {{6, 2}, {-6, -1}, {3, -7}, {-2, 7}}) {
            pose(x + c[0], y, z + c[1], MOUSSE);
        }
        pose(x + 6, y + 1, z + 2, PIERRE);
    }

    /** Un squelette de dragon couché, en blocs d'os : colonne, côtes, ailes repliées, crâne. */
    private void dragonOs(int x, int y, int z) {
        BlockState os = s(Blocks.BONE_BLOCK);
        for (int k = -11; k <= 11; k++) {
            int hy = (int) Math.round(1 + Math.sin((k + 11) / 22.0 * Math.PI) * 2);
            pose(x + k, y + hy, z, os);
            if (k > -7 && k < 6 && k % 2 == 0) {
                for (int c = 1; c <= 4; c++) {
                    int cy = hy - (int) Math.round(c * 0.6);
                    pose(x + k, y + Math.max(0, cy), z + c, os);
                    pose(x + k, y + Math.max(0, cy), z - c, os);
                }
            }
        }
        // le crâne au bout ouest, la mâchoire ouverte
        remplit(x - 15, x - 12, y + 1, y + 2, z - 1, z + 1, os);
        remplit(x - 17, x - 15, y, y, z - 1, z + 1, os);
        pose(x - 13, y + 3, z - 1, os);
        pose(x - 13, y + 3, z + 1, os);
        // une aile repliée sur le côté
        for (int k = -4; k <= 5; k++) {
            pose(x + k, y + 1 + Math.max(0, 4 - Math.abs(k)), z + 6, os);
        }
        // la queue qui s'enroule
        for (int k = 12; k <= 16; k++) {
            pose(x + k, y, z + (k - 12) / 2, os);
        }
    }

    /** Un petit château : enceinte crénelée, deux tours rondes, un donjon et une bannière de chaque côté. */
    private void chateau(int x, int y, int z) {
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                boolean bord = Math.abs(dx) == 9 || Math.abs(dz) == 7;
                if (!bord) {
                    continue;
                }
                for (int dy = 0; dy < 6; dy++) {
                    pose(x + dx, y + dy, z + dz, vieux(x + dx, y + dy, z + dz, PIERRE));
                }
                if (mod(dx + dz, 2) == 0) {
                    pose(x + dx, y + 6, z + dz, PIERRE);
                }
            }
        }
        remplit(x - 1, x + 1, y, y + 3, z + 7, z + 7, null);
        for (int sx : new int[] {-1, 1}) {
            int tx = x + sx * 9, tz = z - 7;
            for (int dy = 0; dy < 14; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        double d = Math.hypot(dx, dz);
                        if (d <= 2.3 && d > 1.2) {
                            pose(tx + dx, y + dy, tz + dz, vieux(tx + dx, y + dy, tz + dz, PIERRE));
                        }
                    }
                }
            }
            for (int dy = 0; dy < 4; dy++) {
                int rr = 3 - dy;
                for (int dx = -rr; dx <= rr; dx++) {
                    for (int dz = -rr; dz <= rr; dz++) {
                        if (Math.hypot(dx, dz) <= rr + 0.4) {
                            pose(tx + dx, y + 14 + dy, tz + dz, sx < 0 ? BLEU : ROUGE);
                        }
                    }
                }
            }
        }
        for (int dy = 0; dy < 18; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (Math.abs(dx) == 3 || Math.abs(dz) == 3) {
                        boolean fenetre = (dx == 0 || dz == 0) && dy % 5 == 3;
                        pose(x + dx, y + dy, z - 1 + dz, fenetre ? OMBRE : vieux(x + dx, y + dy, z - 1 + dz, PIERRE));
                    }
                }
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if ((Math.abs(dx) == 3 || Math.abs(dz) == 3) && mod(dx + dz, 2) == 0) {
                    pose(x + dx, y + 18, z - 1 + dz, PIERRE);
                }
            }
        }
        remplit(x, x, y + 18, y + 25, z - 1, z - 1, POTEAU);
        remplit(x + 1, x + 4, y + 22, y + 24, z - 1, z - 1, JAUNE);
    }

    /** Un moulin en ruine : tour de pierre, toit de bois, deux grandes ailes en croix. */
    private void moulin(int x, int y, int z) {
        for (int dy = 0; dy < 12; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    double d = Math.hypot(dx, dz);
                    if (d <= 3.3 && d > 2.2) {
                        pose(x + dx, y + dy, z + dz, vieux(x + dx, y + dy, z + dz, PIERRE));
                    }
                }
            }
        }
        for (int dy = 0; dy < 4; dy++) {
            int rr = 4 - dy;
            for (int dx = -rr; dx <= rr; dx++) {
                for (int dz = -rr; dz <= rr; dz++) {
                    if (Math.hypot(dx, dz) <= rr + 0.3) {
                        pose(x + dx, y + 12 + dy, z + dz, s(Blocks.DARK_OAK_PLANKS));
                    }
                }
            }
        }
        int ay = y + 9, az = z - 4;
        for (int k = -8; k <= 8; k++) {
            pose(x + k, ay + k, az, k == 0 ? RONDIN : s(Blocks.STRIPPED_SPRUCE_LOG));
            if (Math.abs(k) > 1) {
                pose(x + k, ay - k, az, s(Blocks.STRIPPED_SPRUCE_LOG));
                pose(x + k, ay + k + (k > 0 ? -1 : 1), az, s(Blocks.WHITE_WOOL));
                pose(x + k, ay - k + (k > 0 ? 1 : -1), az, s(Blocks.WHITE_WOOL));
            }
        }
    }

    /** Le sol, le rempart et sa grille, les gradins, l'allée du haut et la muraille. */
    private void stade() {
        int xmax = L + R + FACADE + 3, zmax = R + FACADE + 3;
        for (int x = -xmax; x < xmax; x++) {
            for (int z = -zmax; z < zmax; z++) {
                double cx = x + 0.5, cz = z + 0.5, d = ecart(cx, cz);
                if (d < 0) {
                    double n = bruitSol(x, z);
                    boolean rond = Math.abs(Math.hypot(cx, cz) - 10.5) < 0.5;
                    pose(x, -1, z, rond ? TAILLEE : n > 1.35 ? HERBE : n < -1.3 ? TERRE : SABLE);
                    pose(x, -2, z, SOUS_SOL);
                    fragiles.add(cle(x, -1, z));
                    continue;
                }
                int k = rang(d);
                if (k > FACADE) {
                    continue;
                }
                double s = longueurAnneau(cx, cz, d);
                if (k == 0) {
                    // le rempart, avec des tentures aux couleurs du côté, puis la grille qui protège le public
                    int m8 = mod((int) Math.floor(s), 8);
                    boolean tenture = m8 >= 3 && m8 <= 4;
                    for (int y = -2; y <= 3; y++) {
                        pose(x, y, z, tenture && y >= 0 && y <= 2 ? drapDe(cx) : y == 3 ? TAILLEE : vieux(x, y, z, PIERRE));
                    }
                    for (int y = 4; y <= 6; y++) {
                        pose(x, y, z, BARREAUX);
                    }
                } else if (k == 1) {
                    pose(x, 3, z, TAILLEE);
                    if (mod((int) Math.floor(s), 10) == 0) {
                        pose(x, 4, z, BRASERO);
                    }
                } else if (k <= N + 1) {
                    int j = k - 1;
                    boolean allee;
                    if (Math.abs(cx) <= L) {
                        allee = Math.abs(cx / 14 - Math.round(cx / 14)) * 14 < 0.75;
                    } else {
                        double a = Math.atan2(cz, Math.abs(cx) - L) / (Math.PI / 4);
                        allee = Math.abs(a - Math.round(a)) * (Math.PI / 4) * (R + d) < 0.75;
                    }
                    pose(x, 3 + j, z, allee ? MARCHE : j % 5 == 0 ? drapDe(cx) : j % 2 == 1 ? CHENE : SAPIN);
                } else if (k < FACADE) {
                    pose(x, HAUT_ALLEE, z, TAILLEE);
                } else {
                    // la muraille : grandes arches en bas, meurtrières, bandeaux, créneaux
                    int m = mod((int) Math.floor(s), 6);
                    for (int y = -2; y <= HAUT_MUR; y++) {
                        boolean arche = (m == 2 || m == 3) && y >= 0 && y <= 4;
                        boolean meurtriere = m == 3 && ((y >= 11 && y <= 14) || (y >= 23 && y <= 26));
                        pose(x, y, z, arche || meurtriere ? OMBRE : y == 9 || y == 20 || y == HAUT_MUR ? SOMBRE : y <= 1 ? MOUSSE : vieux(x, y, z, PIERRE));
                    }
                    if (mod((int) Math.floor(s), 2) == 0) {
                        pose(x, HAUT_MUR + 1, z, PIERRE);
                    }
                }
            }
        }
    }

    /** Les deux bases : dalle, tapis du côté, porte du fort avec la herse relevée, bouton du retour, braseros. */
    private void bases() {
        for (int camp = 0; camp < 2; camp++) {
            final int c = camp;
            java.util.function.IntUnaryOperator X = a -> c == 0 ? a : -a - 1;
            BlockState drap = camp == 0 ? BLEU : ROUGE;
            for (int x = -46; x <= -38; x++) {
                for (int z = -4; z <= 3; z++) {
                    boolean bord = x == -46 || x == -38 || z == -4 || z == 3, tapis = x >= -44 && x <= -40 && z >= -2 && z <= 1;
                    pose(X.applyAsInt(x), 0, z, bord ? SOMBRE : tapis ? drap : TAILLEE);
                    fragiles.add(cle(X.applyAsInt(x), 0, z));
                }
            }
            for (int z : new int[] {-4, 3}) {
                remplit(X.applyAsInt(-37), X.applyAsInt(-37), 0, 6, z, z, PIERRE);
                pose(X.applyAsInt(-37), 3, z, drap);
                pose(X.applyAsInt(-37), 4, z, drap);
            }
            remplit(X.applyAsInt(-37), X.applyAsInt(-37), 5, 5, -3, 2, BARREAUX);
            remplit(X.applyAsInt(-37), X.applyAsInt(-37), 6, 6, -4, 3, drap);
            for (int z : new int[] {-4, -2, 0, 2}) {
                pose(X.applyAsInt(-37), 7, z, PIERRE);
            }
            for (int z : new int[] {-5, 4}) {
                remplit(X.applyAsInt(-46), X.applyAsInt(-39), 0, 1, z, z, MOUSSE);
            }
            pose(X.applyAsInt(-46), 1, 3, TAILLEE);
            pose(X.applyAsInt(-46), 2, 3, Arene.BOUTON_SALLE.get().defaultBlockState());
            for (int z : new int[] {-4, 3}) {
                pose(X.applyAsInt(-39), 1, z, BRASERO);
            }
        }
    }

    /** De quoi se cacher, pareil des deux côtés : murets, palissades, tonneaux, murs en L, piliers, foin, buissons, tours de guet, ruines. */
    private void couverts() {
        int avant = plan.size();
        quatre(-32, -32, 0, 1, -3, 2, MOUSSE, false);
        quatre(-24, -24, 0, 1, 8, 12, RONDIN, true);
        quatre(-24, -24, 2, 2, 8, 12, POTEAU, true);
        quatre(-17, -16, 0, 1, -1, 0, TONNEAU, false);
        quatre(-17, -17, 2, 2, -1, -1, TONNEAU, false);
        quatre(-14, -10, 0, 2, 19, 19, PIERRE, true);
        quatre(-10, -10, 0, 2, 16, 18, PIERRE, true);
        quatre(-30, -29, 0, 4, 15, 16, TAILLEE, true);
        quatre(-30, -30, 5, 5, 15, 15, BRASERO, true);
        quatre(-37, -36, 0, 1, 10, 11, FOIN, true);
        quatre(-37, -37, 2, 2, 10, 10, FOIN, true);
        quatre(-20, -19, 0, 1, 22, 22, FEUILLES, true);
        quatre(-6, -5, 0, 1, 22, 23, FEUILLES, true);
        quatre(-41, -40, 0, 1, 12, 13, FEUILLES, true);
        quatre(-21, -20, 0, 0, -6, -5, CAISSE, true);
        quatre(-21, -21, 1, 1, -6, -6, CAISSE, true);
        guet(-28, 20);
        guet(-28, -23);
        guet(25, 20);
        guet(25, -23);
        // les ruines du milieu : estrade ronde, 8 piliers cassés, deux bouts de mur
        for (int x = -4; x < 4; x++) {
            for (int z = -4; z < 4; z++) {
                if (Math.hypot(x + 0.5, z + 0.5) <= 3.2) {
                    pose(x, 0, z, TAILLEE);
                }
            }
        }
        int[] hauteurs = {5, 3, 3, 5, 4, 2, 2, 4};
        for (int k = 0; k < 8; k++) {
            double a = Math.PI / 8 + k * Math.PI / 4;
            int x = (int) Math.round(7 * Math.cos(a)), z = (int) Math.round(7 * Math.sin(a));
            remplit(x - 1, x, 0, hauteurs[k] - 1, z - 1, z, k % 2 == 1 ? MOUSSE : PIERRE);
        }
        remplit(-2, 1, 0, 1, 6, 6, MOUSSE);
        pose(1, 1, 6, null);
        remplit(-2, 1, 0, 1, -7, -7, MOUSSE);
        pose(-2, 1, -7, null);
        // tout ce qui est posé sur le sol de l'arène peut être réparé
        for (Map.Entry<Long, BlockState> e : plan.entrySet()) {
            BlockPos p = BlockPos.of(e.getKey());
            if (p.getY() >= 0 && p.getY() <= 8 && ecart(p.getX() + 0.5, p.getZ() + 0.5) < 0) {
                fragiles.add(e.getKey());
            }
        }
        if (plan.size() < avant) {
            throw new IllegalStateException("plan des couverts");
        }
    }

    /** Une tour de guet en bois : 4 poteaux, plancher à 4, garde-corps, échelle (sur une planche) côté nord. */
    private void guet(int x0, int z0) {
        for (int[] c : new int[][] {{0, 0}, {2, 0}, {0, 2}, {2, 2}}) {
            remplit(x0 + c[0], x0 + c[0], 0, 5, z0 + c[1], z0 + c[1], POTEAU);
        }
        remplit(x0, x0 + 2, 4, 4, z0, z0 + 2, SAPIN);
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 2; dz++) {
                if ((dx != 1 || dz != 1) && (dx != 1 || dz != 0)) {
                    pose(x0 + dx, 5, z0 + dz, RONDIN.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
                }
            }
        }
        remplit(x0 + 1, x0 + 1, 0, 3, z0, z0, SAPIN);
        for (int y = 0; y <= 4; y++) {
            pose(x0 + 1, y, z0 - 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        }
    }

    /** La loge royale en haut des gradins, côté sud : tapis, grille devant, dais rayé, bouton vers la salle d'entrée. */
    private void logeRoyale() {
        for (int x = -8; x <= 7; x++) {
            for (int z = ZL; z <= ZL + 2; z++) {
                pose(x, HAUT_ALLEE + 1, z, TAPIS_ROUGE);
            }
            if (x < -1 || x > 0) {
                pose(x, HAUT_ALLEE + 1, ZL, BARREAUX);
            }
            for (int z = ZL - 1; z <= ZL + 2; z++) {
                pose(x, HAUT_MUR + 1, z, z == ZL - 1 ? JAUNE : mod(x, 2) == 1 ? ROUGE : JAUNE);
            }
        }
        for (int x : new int[] {-8, 7}) {
            remplit(x, x, HAUT_ALLEE, HAUT_MUR, ZL - 1, ZL - 1, TAILLEE);
        }
        pose(-3, HAUT_ALLEE + 1, ZL + 2, Arene.BOUTON_SALLE.get().defaultBlockState());
        pose(1, HAUT_ALLEE + 1, ZL + 2, OR);
        pose(-2, HAUT_ALLEE + 1, ZL + 2, OR);
    }

    /** Les 12 tours de la muraille : les 4 des coins (plus hautes) tiennent l'écran, les autres portent une bannière. */
    private void tours() {
        record Tour(double x, double z, int h, boolean ecran) {
        }
        List<Tour> tours = new ArrayList<>();
        for (int sx : new int[] {-1, 1}) {
            for (int sz : new int[] {-1, 1}) {
                tours.add(new Tour(sx * L, sz * RT, 60, true));
            }
        }
        for (int sz : new int[] {-1, 1}) {
            tours.add(new Tour(0, sz * RT, 46, false));
        }
        for (int sx : new int[] {-1, 1}) {
            tours.add(new Tour(sx * (L + RT), 0, 50, false));
            for (int sz : new int[] {-1, 1}) {
                tours.add(new Tour(sx * (L + RT * Math.sqrt(0.5)), sz * RT * Math.sqrt(0.5), 46, false));
            }
        }
        for (Tour t : tours) {
            int x0 = (int) Math.round(t.x()) - 3, z0 = (int) Math.round(t.z()) - 3, h = t.h();
            for (int y = -2; y <= h; y++) {
                for (int dx = 0; dx <= 6; dx++) {
                    for (int dz = 0; dz <= 6; dz++) {
                        if (dx > 0 && dx < 6 && dz > 0 && dz < 6) {
                            if (y == h) {
                                pose(x0 + dx, y, z0 + dz, TAILLEE);
                            } else {
                                pose(x0 + dx, y, z0 + dz, null);
                            }
                            continue;
                        }
                        boolean fente = (dx == 3 || dz == 3) && mod(y, 9) >= 4 && mod(y, 9) <= 6 && y > 8;
                        pose(x0 + dx, y, z0 + dz, fente ? OMBRE : y % 12 == 0 ? SOMBRE : vieux(x0 + dx, y, z0 + dz, PIERRE));
                    }
                }
            }
            for (int dx = -1; dx <= 7; dx++) {
                for (int dz = -1; dz <= 7; dz++) {
                    if (dx != -1 && dx != 7 && dz != -1 && dz != 7) {
                        continue;
                    }
                    pose(x0 + dx, h + 1, z0 + dz, SOMBRE);
                    if (mod(dx + dz, 2) == 0) {
                        pose(x0 + dx, h + 2, z0 + dz, PIERRE);
                    }
                }
            }
            if (t.ecran()) {
                remplit(x0 + 2, x0 + 4, h + 1, h + 1, z0 + 2, z0 + 4, SOMBRE);
                pose(x0 + 3, h + 2, z0 + 3, BRASERO);
            } else {
                remplit(x0 + 3, x0 + 3, h + 1, h + 8, z0 + 3, z0 + 3, POTEAU);
                BlockState drap = drapDe(t.x());
                remplit(x0 + 4, x0 + 6, h + 6, h + 8, z0 + 3, z0 + 3, drap);
                pose(x0 + 7, h + 7, z0 + 3, drap);
            }
        }
    }

    /** L'écran géant : un caisson de bois sombre de 26 × 15 × 26 cerclé d'or, 4 faces noires (l'image y est dessinée par le jeu), et les chaînes. */
    private void ecranGeant() {
        int y0 = (int) Math.floor(ECRAN_Y) - 7, y1 = y0 + 14;
        for (int x = -DEMI; x < DEMI; x++) {
            for (int z = -DEMI; z < DEMI; z++) {
                boolean bord = x == -DEMI || x == DEMI - 1 || z == -DEMI || z == DEMI - 1;
                boolean coin = (x == -DEMI || x == DEMI - 1) && (z == -DEMI || z == DEMI - 1);
                for (int y = y0; y <= y1; y++) {
                    if (y == y0 || y == y1) {
                        pose(x, y, z, bord ? OR : CAISSON);
                    } else if (bord) {
                        boolean image = !coin && y > y0 && y < y1 && Math.abs(x + 0.5) < DEMI - 0.5 && Math.abs(z + 0.5) < DEMI - 0.5;
                        pose(x, y, z, coin ? OR : image ? ECRAN : CAISSON);
                    }
                }
                if (bord && mod(x + z, 2) == 0) {
                    pose(x, y1 + 1, z, OR);
                }
            }
        }
        // les chaînes, des 4 tours des coins jusqu'aux coins du caisson, avec un peu de mou
        for (Vec3 a : toursEcran()) {
            Vec3 b = new Vec3(Math.signum(a.x) * (DEMI - 0.5), y1 + 0.5, Math.signum(a.z) * (DEMI - 0.5));
            Vec3 h = a.add(0, 1.5, 0);
            int n = (int) Math.ceil(h.distanceTo(b) * 2.5);
            for (int i = 0; i <= n; i++) {
                double f = (double) i / n;
                Vec3 p = h.lerp(b, f).add(0, -Math.sin(f * Math.PI) * 2.5, 0);
                Direction.Axis axe = Math.abs(b.x - h.x) > Math.abs(b.z - h.z) ? Direction.Axis.X : Direction.Axis.Z;
                BlockPos q = BlockPos.containing(p);
                if (!plan.containsKey(q.asLong())) {
                    pose(q.getX(), q.getY(), q.getZ(), Blocks.CHAIN.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axe));
                }
            }
        }
    }

    /**
     * La salle d'entrée, derrière la loge : murs et créneaux, tapis doré au milieu, tapis d'attente bleu (ouest) et rouge
     * (est) devant leur porte vitrée, porte des gradins côté arène avec l'écran du combat au-dessus, bornes à bouton,
     * poteaux des panneaux d'attente, bancs et braseros. On y arrive par le fond (côté sud), devant la grande porte fermée.
     */
    private void salleEntree() {
        for (int x = -13; x <= 12; x++) {
            for (int z = SZ0; z <= SZ1; z++) {
                boolean mur = x == -13 || x == 12 || z == SZ0 || z == SZ1;
                pose(x, -1, z, mur ? PIERRE : mod(x + z, 2) == 1 ? TAILLEE : MARCHE);
                if (!mur) {
                    continue;
                }
                for (int y = 0; y <= 9; y++) {
                    pose(x, y, z, y == 0 || y == 9 ? SOMBRE : vieux(x, y, z, PIERRE));
                }
                if (mod(x + z, 2) == 0) {
                    pose(x, 10, z, PIERRE);
                }
            }
        }
        for (int z = SZ0 + 1; z < SZ1; z++) {
            pose(-1, 0, z, TAPIS_OR);
            pose(0, 0, z, TAPIS_OR);
        }
        for (int x = -12; x <= -9; x++) {
            for (int z = 84; z <= 88; z++) {
                pose(x, 0, z, TAPIS_BLEU);
                pose(-x - 1, 0, z, TAPIS_ROUGE);
            }
        }
        porteCote(-13, BLEU, s(Blocks.BLUE_STAINED_GLASS));
        porteCote(12, ROUGE, s(Blocks.RED_STAINED_GLASS));
        remplit(-2, 1, 0, 3, SZ0, SZ0, s(Blocks.YELLOW_STAINED_GLASS));
        remplit(-3, 2, 4, 4, SZ0, SZ0, JAUNE);
        for (int x : new int[] {-3, 2}) {
            remplit(x, x, 0, 3, SZ0, SZ0, SOMBRE);
        }
        // l'écran du combat au-dessus de la porte des gradins, cerclé d'or
        remplit(-4, 3, 5, 9, SZ0, SZ0, ECRAN);
        for (int x : new int[] {-5, 4}) {
            remplit(x, x, 5, 9, SZ0, SZ0, OR);
        }
        remplit(-2, 1, 0, 4, SZ1, SZ1, s(Blocks.DARK_OAK_PLANKS));
        borne(-12, 89, Arene.BOUTON_BLEU.get().defaultBlockState());
        borne(11, 89, Arene.BOUTON_ROUGE.get().defaultBlockState());
        borne(3, 77, Arene.BOUTON_GRADINS.get().defaultBlockState());
        borne(-4, 95, Arene.BOUTON_SORTIE.get().defaultBlockState());
        for (int z : new int[] {78, 79, 80, 92, 93, 94}) {
            pose(-12, 0, z, BANC);
            pose(11, 0, z, BANC);
        }
        for (int x : new int[] {-9, 8}) {
            remplit(x, x, 0, 5, 86, 86, POTEAU);
        }
        for (int[] c : new int[][] {{-12, 77}, {11, 77}, {-12, 95}, {11, 95}}) {
            pose(c[0], 0, c[1], BRASERO);
        }
        // de la lumière au plafond (la salle est à ciel ouvert, mais la nuit n'arrive jamais : c'est pour les coins d'ombre)
        for (int x : new int[] {-8, 7}) {
            for (int z : new int[] {80, 92}) {
                pose(x, 8, z, Blocks.LIGHT.defaultBlockState());
            }
        }
    }

    private void porteCote(int xMur, BlockState drap, BlockState vitre) {
        remplit(xMur, xMur, 0, 3, 85, 87, vitre);
        for (int z : new int[] {84, 88}) {
            remplit(xMur, xMur, 0, 4, z, z, SOMBRE);
        }
        remplit(xMur, xMur, 4, 4, 84, 88, drap);
    }

    private void borne(int x, int z, BlockState bouton) {
        pose(x, 0, z, TAILLEE);
        pose(x, 1, z, bouton);
    }

    // ---------------------------------------------------------------- la construction

    private static AreneBuilder enCache;

    static AreneBuilder plan() {
        if (enCache == null) {
            AreneBuilder b = new AreneBuilder();
            b.planifier();
            enCache = b;
        }
        return enCache;
    }

    private static final int XMAX = 90, ZMIN = -68, ZMAX = 98, YMIN = -3, YMAX = 64;

    /**
     * La construction, étalée sur quelques secondes (quelques colonnes par tick) pour ne jamais geler le serveur : le
     * grand bloc de l'arène et de la salle (tout ce qui n'est pas au plan y devient de l'air), puis les îles flottantes,
     * puis les barreaux et clôtures qui se raccordent à leurs voisins. Les chunks du chantier restent chargés pendant ce temps.
     */
    static final class Chantier {
        private final AreneBuilder b;
        private final List<Long> iles = new ArrayList<>(), raccords = new ArrayList<>();
        private final java.util.Set<Long> chunks = new java.util.HashSet<>();
        private int x = -XMAX, i = 0, etape = 0;

        Chantier(ServerLevel l) {
            enCache = null;
            b = plan();
            for (Map.Entry<Long, BlockState> e : b.plan.entrySet()) {
                BlockPos q = BlockPos.of(e.getKey());
                if (Math.abs(q.getX()) > XMAX || q.getZ() < ZMIN || q.getZ() > ZMAX || q.getY() < YMIN || q.getY() > YMAX) {
                    iles.add(e.getKey());
                    chunks.add(net.minecraft.world.level.ChunkPos.asLong(q.getX() >> 4, q.getZ() >> 4));
                }
                if (e.getValue().is(Blocks.IRON_BARS) || e.getValue().is(Blocks.SPRUCE_FENCE)) {
                    raccords.add(e.getKey());
                }
            }
            for (int cx = -XMAX >> 4; cx <= XMAX >> 4; cx++) {
                for (int cz = ZMIN >> 4; cz <= ZMAX >> 4; cz++) {
                    chunks.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
                }
            }
            // les chunks se chargent en arrière-plan (jamais d'attente du serveur) ; le chantier pose seulement ce qui est prêt
            for (long c : chunks) {
                net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(c);
                l.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, cp, 2, cp);
            }
        }

        private static boolean pret(ServerLevel l, int cx, int cz) {
            return l.getChunkSource().getChunkNow(cx, cz) != null;
        }

        /** Où en est le chantier, de 0 à 100. */
        int pourcent() {
            int total = 2 * XMAX + 1 + iles.size() / 3000 + 1;
            int fait = etape == 0 ? x + XMAX : 2 * XMAX + 1 + i / 3000;
            return Math.min(99, fait * 100 / total);
        }

        /** Avance un peu ; vrai quand tout est posé. */
        boolean avancer(ServerLevel l) {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            BlockState air = Blocks.AIR.defaultBlockState();
            if (etape == 0) {
                for (int n = 0; n < 3 && x <= XMAX; n++, x++) {
                    for (int cz = ZMIN >> 4; cz <= ZMAX >> 4; cz++) {
                        if (!pret(l, x >> 4, cz)) {
                            return false;
                        }
                    }
                    for (int z = ZMIN; z <= ZMAX; z++) {
                        for (int y = YMIN; y <= YMAX; y++) {
                            BlockState voulu = b.plan.getOrDefault(cle(x, y, z), air);
                            p.set(x, y + SOL, z);
                            if (l.getBlockState(p) != voulu) {
                                l.setBlock(p, voulu, Block.UPDATE_CLIENTS);
                            }
                        }
                    }
                }
                if (x > XMAX) {
                    etape = 1;
                }
                return false;
            }
            if (etape == 1) {
                for (int n = 0; n < 3000 && i < iles.size(); n++, i++) {
                    long k = iles.get(i);
                    if (!pret(l, BlockPos.getX(k) >> 4, BlockPos.getZ(k) >> 4)) {
                        return false;
                    }
                    p.set(BlockPos.getX(k), BlockPos.getY(k) + SOL, BlockPos.getZ(k));
                    BlockState voulu = b.plan.get(k);
                    if (l.getBlockState(p) != voulu) {
                        l.setBlock(p, voulu, Block.UPDATE_CLIENTS);
                    }
                }
                if (i >= iles.size()) {
                    etape = 2;
                }
                return false;
            }
            for (long k : raccords) {
                BlockPos q = BlockPos.of(k).offset(0, SOL, 0);
                l.setBlock(q, Block.updateFromNeighbourShapes(b.plan.get(k), l, q), Block.UPDATE_CLIENTS);
            }
            for (long c : chunks) {
                net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(c);
                l.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, cp, 2, cp);
            }
            return true;
        }
    }

    /** Remet en place ce qu'un pouvoir aurait cassé sur le sol de l'arène et dans les couverts. */
    static void reparer(ServerLevel l) {
        AreneBuilder b = plan();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (long k : b.fragiles) {
            BlockState voulu = b.plan.get(k);
            if (voulu == null) {
                continue;
            }
            p.set(BlockPos.getX(k), BlockPos.getY(k) + SOL, BlockPos.getZ(k));
            if (!l.isLoaded(p)) {
                continue;
            }
            BlockState la = l.getBlockState(p);
            if (la.getBlock() != voulu.getBlock()) {
                l.setBlock(p, voulu.is(Blocks.SPRUCE_FENCE) ? Block.updateFromNeighbourShapes(voulu, l, p) : voulu, Block.UPDATE_CLIENTS);
            }
        }
    }

    private AreneBuilder() {
    }
}
