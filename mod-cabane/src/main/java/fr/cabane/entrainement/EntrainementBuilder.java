package fr.cabane.entrainement;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * La salle d'entraînement, reprise de la maquette (docs/salle-entrainement/maquette.html) : dix salles fermées de 22 × 24,
 * cinq de chaque côté d'une allée de 7 blocs, chacune avec ses murs, sa hauteur, sa lumière et son matériel. On ne dit
 * nulle part à quoi sert chaque salle : chacune se décrit par ce qu'il y a dedans.
 *
 * <p>Tout est d'abord écrit dans un plan (coordonnées de la maquette : x de 0 à 119, z de 0 à 58, le sol à y = 0), puis
 * posé dans la dimension avec le décalage {@link #OX}, {@link #SOL}, {@link #OZ}.
 */
public final class EntrainementBuilder {
    private static final Logger LOG = LogUtils.getLogger();
    /** À changer après toute modification : la salle se reconstruit à la prochaine visite. */
    public static final int VERSION = 1;
    /** Le dessus du sol de la salle. */
    public static final int SOL = 64;
    /** Le plan est centré sur (0, 0) de la dimension. */
    static final int OX = -60, OZ = -29;
    static final int W = 22, D = 24, PAS = W + 2, LX = 5 * PAS - 1, Z_ALLEE = D + 2, Z_FIN = Z_ALLEE + 6, Z_SUD = Z_FIN + 2, LZ = Z_SUD + D;
    static final int MILIEU = Z_ALLEE + 3;
    /** Toutes les entités posées par la salle. */
    public static final String TAG = "cabane_entrainement";
    /** Les pnj de la salle (ils encaissent les coups sans tomber). */
    public static final String PNJ = "cabane_entrainement_pnj";

    /** On arrive au bout ouest de l'allée, tourné vers l'est, le bouton du retour juste à côté. */
    public static Vec3 arrivee() {
        return new Vec3(3.5 + OX, SOL, MILIEU + 0.5 + OZ);
    }

    public static final float ARRIVEE_YAW = -90F;

    /** Une salle : sa hauteur, ses murs, ses vitres, sa lumière (0 à 15), son biome. */
    record Salle(int i, int h, BlockState mur, boolean fenetres, int lumiere, String biome, String nom, String temp, String ressenti) {
        boolean nord() {
            return i < 5;
        }

        int cx() {
            return 1 + (i % 5) * PAS;
        }

        int x0() {
            return cx() - 1;
        }

        int x1() {
            return cx() + W;
        }

        int z0() {
            return nord() ? 0 : Z_SUD - 1;
        }

        int z1() {
            return nord() ? D + 1 : LZ;
        }

        /** Le bloc (u, v) de la salle, u le long de l'allée, v de l'allée (0) vers le fond (23). */
        int x(int u) {
            return cx() + u;
        }

        int z(int v) {
            return nord() ? D - v : Z_SUD + v;
        }

        /** Un point (u, v) en continu (le milieu du bloc u, v est en u + 0,5, v + 0,5). */
        double mx(double u) {
            return cx() + u;
        }

        double mz(double v) {
            return nord() ? D + 1 - v : Z_SUD + v;
        }

        /** Vers l'allée, vers le fond. */
        Direction allee() {
            return nord() ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private static BlockState s(Block b) {
        return b.defaultBlockState();
    }

    static final Salle[] SALLES = {
        new Salle(0, 22, s(Blocks.DARK_OAK_PLANKS), true, 13, "entrainement_bois", "Le mannequin géant", "19 °C",
                "Un grand hall en bois qui sent le foin. La lumière tombe d'en haut sur le mannequin."),
        new Salle(1, 10, s(Blocks.PRISMARINE_BRICKS), false, 9, "entrainement_bassin", "Le bassin profond", "27 °C",
                "Humide et tiède. L'eau clapote, une buée flotte au-dessus du bassin."),
        new Salle(2, 10, s(Blocks.POLISHED_BLACKSTONE_BRICKS), false, 5, "entrainement_forge", "La fonderie", "48 °C",
                "Une chaleur écrasante. Des braises montent de la lave, l'air brûle les yeux."),
        new Salle(3, 10, s(Blocks.PACKED_ICE), false, 12, "entrainement_froid", "La chambre froide", "−15 °C",
                "Un froid qui mord. La neige tombe du plafond et chaque souffle fait de la buée."),
        new Salle(4, 26, s(Blocks.WHITE_CONCRETE), true, 13, "entrainement_vent", "Le tunnel à vent", "12 °C",
                "Un courant d'air permanent siffle dans la colonne vitrée. Accroupis-toi pour redescendre."),
        new Salle(5, 10, s(Blocks.DEEPSLATE_BRICKS), false, 3, "entrainement_gouffre", "Les îlots du gouffre", "9 °C",
                "Presque le noir complet. On sent le vide juste devant ses pieds."),
        new Salle(6, 10, s(Blocks.SMOOTH_SANDSTONE), false, 12, "entrainement_sable", "La fosse de sable", "41 °C",
                "Sec et brûlant. Le sable vole dans une lumière jaune et pique la gorge."),
        new Salle(7, 13, s(Blocks.TUFF_BRICKS), false, 4, "entrainement_cuivre", "Les pylônes de cuivre", "20 °C",
                "Sombre, et ça grésille. Chaque arc éclaire toute la pièce d'un coup."),
        new Salle(8, 12, s(Blocks.POLISHED_DIORITE), false, 7, "entrainement_os", "Les piliers d'os", "14 °C",
                "Pâle et silencieux. Une poussière blanche traîne entre les piliers."),
        new Salle(9, 23, s(Blocks.QUARTZ_BRICKS), true, 14, "entrainement_voliere", "La volière", "22 °C",
                "Lumineux et aéré. Des feuilles tombent doucement à travers les barreaux."),
    };

    // ---------------------------------------------------------------- ce qu'on lit pendant le jeu

    /** La salle où se trouve ce point de la dimension, -1 dans l'allée ou dehors. */
    public static int salleDe(double wx, double wz) {
        double x = wx - OX, z = wz - OZ;
        for (Salle s : SALLES) {
            if (x >= s.x0() + 1 && x < s.x1() && z >= s.z0() + 1 && z < s.z1()) {
                return s.i();
            }
        }
        return -1;
    }

    static Salle salle(int i) {
        return SALLES[i];
    }

    /** La colonne vitrée du tunnel à vent, en coordonnées de la dimension : {x0, z0, x1, z1} (x1, z1 exclus). */
    static double[] colonne() {
        Salle s = SALLES[4];
        return new double[] {s.x(8) + OX, s.z(13) + OZ, s.x(13) + OX, s.z(9) + 1 + OZ};
    }

    /** Le haut des paratonnerres des pylônes, en coordonnées de la dimension. */
    static List<Vec3> pylones() {
        Salle s = SALLES[7];
        List<Vec3> l = new ArrayList<>();
        for (int[] p : PIEDS) {
            l.add(new Vec3(s.x(p[0]) + 0.5 + OX, SOL + 10.9, s.z(p[1]) + 0.5 + OZ));
        }
        return l;
    }

    /** La rigole de lave de la fonderie, en coordonnées de la dimension (le dessus des blocs). */
    static List<BlockPos> rigole() {
        Salle s = SALLES[2];
        List<BlockPos> l = new ArrayList<>();
        for (int u = 3; u <= 18; u++) {
            for (int v = 4; v <= 19; v++) {
                if ((u == 3 || u == 18 || v == 4 || v == 19) && !(v == 4 && (u == 10 || u == 11))) {
                    l.add(new BlockPos(s.x(u) + OX, SOL - 1, s.z(v) + OZ));
                }
            }
        }
        return l;
    }

    /** Les lumières invisibles du plafond d'une salle (là où rien d'autre n'est posé). */
    static List<BlockPos> lumieres(int i) {
        Salle s = SALLES[i];
        List<BlockPos> l = new ArrayList<>();
        for (int u = 2; u <= 21; u += 5) {
            for (int v = 2; v <= 23; v += 5) {
                l.add(new BlockPos(s.x(u), s.h() - 2, s.z(v)));
            }
        }
        return l;
    }

    static BlockPos monde(BlockPos plan) {
        return new BlockPos(plan.getX() + OX, plan.getY() + SOL, plan.getZ() + OZ);
    }

    // ---------------------------------------------------------------- le plan

    private final Map<Long, BlockState> plan = new HashMap<>();
    private final Random alea = new Random(12345);
    private final List<Pnj> pnj = new ArrayList<>();
    private Salle c;
    private int osU, osV;

    /** Un pnj : où (en coordonnées de la maquette, continues), vers où il regarde, à quelle hauteur. */
    private record Pnj(double x, double y, double z, double vx, double vz) {
    }

    private static long cle(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private void pose(int x, int y, int z, BlockState st) {
        if (st == null) {
            plan.remove(cle(x, y, z));
        } else {
            plan.put(cle(x, y, z), st);
        }
    }

    private void remplit(int xa, int xb, int ya, int yb, int za, int zb, BlockState st) {
        for (int x = Math.min(xa, xb); x <= Math.max(xa, xb); x++) {
            for (int y = Math.min(ya, yb); y <= Math.max(ya, yb); y++) {
                for (int z = Math.min(za, zb); z <= Math.max(za, zb); z++) {
                    pose(x, y, z, st);
                }
            }
        }
    }

    private void b(int u, int y, int v, BlockState st) {
        pose(c.x(u), y, c.z(v), st);
    }

    private void zone(int u0, int u1, int y0, int y1, int v0, int v1, BlockState st) {
        for (int u = u0; u <= u1; u++) {
            for (int v = v0; v <= v1; v++) {
                for (int y = y0; y <= y1; y++) {
                    b(u, y, v, st);
                }
            }
        }
    }

    private void anneau(int u0, int u1, int y0, int y1, int v0, int v1, BlockState st) {
        for (int u = u0; u <= u1; u++) {
            for (int v = v0; v <= v1; v++) {
                if (u == u0 || u == u1 || v == v0 || v == v1) {
                    for (int y = y0; y <= y1; y++) {
                        b(u, y, v, st);
                    }
                }
            }
        }
    }

    /** Une bannière accrochée au mur du fond (elle pend sous le bloc où elle est accrochée). */
    private void banniere(int u, int y, Block b) {
        b(u, y + 1, 23, b.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, c.allee()));
    }

    private void pnjIci(double u, double v, double y, double vu, double vv) {
        pnj.add(new Pnj(c.mx(u), y, c.mz(v), c.mx(vu), c.mz(vv)));
    }

    private static final BlockState DALLE = s(Blocks.POLISHED_ANDESITE), POTEAU = s(Blocks.DARK_OAK_FENCE), FOIN = s(Blocks.HAY_BLOCK),
            CIBLE = s(Blocks.TARGET), PLANCHES_SOMBRES = s(Blocks.DARK_OAK_PLANKS), LANTERNE = s(Blocks.LANTERN),
            BANC = s(Blocks.OAK_SLAB), POT = s(Blocks.POTTED_FLOWERING_AZALEA), PERCHOIR = s(Blocks.SPRUCE_SLAB),
            NEIGE = s(Blocks.SNOW_BLOCK), ARDOISE = s(Blocks.COBBLED_DEEPSLATE), GRES_TAILLE = s(Blocks.CUT_SANDSTONE),
            TONNEAU = s(Blocks.BARREL).setValue(BlockStateProperties.FACING, Direction.UP);

    private static final int[][] PIEDS = {{4, 5}, {14, 5}, {4, 12}, {14, 12}, {4, 19}, {14, 19}};

    private static int sableH(int u, int v) {
        return Math.max(-4, Math.min(0, (int) Math.round(-1.8 + 1.9 * Math.sin(u * 0.55) * Math.cos(v * 0.4))));
    }

    private void planifier() {
        // le sol, l'allée bordée de quartz
        for (int x = 0; x <= LX; x++) {
            for (int z = 0; z <= LZ; z++) {
                boolean allee = z >= Z_ALLEE && z <= Z_FIN, bord = z == Z_ALLEE || z == Z_FIN;
                pose(x, -1, z, allee ? s(bord ? Blocks.SMOOTH_QUARTZ : Blocks.POLISHED_DEEPSLATE) : DALLE);
            }
        }
        // les salles : leurs murs, une porte de 4 blocs sur l'allée, deux lumières au-dessus de la porte
        for (Salle sa : SALLES) {
            for (int y = 0; y < sa.h(); y++) {
                for (int x = sa.x0(); x <= sa.x1(); x++) {
                    for (int z = sa.z0(); z <= sa.z1(); z++) {
                        if (x != sa.x0() && x != sa.x1() && z != sa.z0() && z != sa.z1()) {
                            continue;
                        }
                        boolean dehors = z == 0 || z == LZ || x == 0 || x == LX;
                        boolean vitre = sa.fenetres() && dehors && y >= 3 && y <= sa.h() - 4 && Math.floorMod(x + z, 6) >= 2 && Math.floorMod(x + z, 6) <= 3;
                        pose(x, y, z, vitre ? s(Blocks.GLASS) : sa.mur());
                    }
                }
            }
            // le toit
            remplit(sa.x0(), sa.x1(), sa.h(), sa.h(), sa.z0(), sa.z1(), sa.mur());
            int zPorte = sa.nord() ? sa.z1() : sa.z0();
            remplit(sa.cx() + 9, sa.cx() + 12, 0, 3, zPorte, zPorte, null);
            pose(sa.cx() + 8, 3, zPorte, s(Blocks.OCHRE_FROGLIGHT));
            pose(sa.cx() + 13, 3, zPorte, s(Blocks.OCHRE_FROGLIGHT));
        }
        // les deux bouts de l'allée (fermés : dehors, c'est le vide) et son toit
        for (int x : new int[] {0, LX}) {
            remplit(x, x, 0, 8, Z_ALLEE, Z_FIN, s(Blocks.STONE_BRICKS));
        }
        remplit(0, LX, 9, 9, Z_ALLEE - 1, Z_FIN + 1, PLANCHES_SOMBRES);
        for (int x = 6; x < LX; x += 9) {
            pose(x, 0, MILIEU, POTEAU);
            pose(x, 1, MILIEU, LANTERNE);
        }

        mannequin();
        bassin();
        fonderie();
        chambreFroide();
        tunnel();
        ilots();
        fosse();
        pylonesCuivre();
        piliersOs();
        voliere();
        allee();

        // les lumières invisibles des plafonds, et celles de l'allée
        for (Salle sa : SALLES) {
            for (BlockPos p : lumieres(sa.i())) {
                if (!plan.containsKey(cle(p.getX(), p.getY(), p.getZ()))) {
                    pose(p.getX(), p.getY(), p.getZ(), s(Blocks.LIGHT).setValue(LightBlock.LEVEL, sa.lumiere()));
                }
            }
        }
        for (int x = 3; x < LX; x += 6) {
            for (int z : new int[] {MILIEU - 2, MILIEU + 2}) {
                if (!plan.containsKey(cle(x, 7, z))) {
                    pose(x, 7, z, s(Blocks.LIGHT).setValue(LightBlock.LEVEL, 12));
                }
            }
        }
    }

    // ---------------------------------------------------------------- 1, le mannequin géant
    private void mannequin() {
        c = SALLES[0];
        zone(0, 21, -1, -1, 0, 23, DALLE);
        zone(0, 21, -1, -1, 1, 1, s(Blocks.QUARTZ_BLOCK));
        zone(5, 16, 0, 0, 15, 21, PLANCHES_SOMBRES);
        zone(10, 11, 1, 5, 18, 18, s(Blocks.DARK_OAK_LOG));
        zone(7, 14, 6, 14, 17, 19, FOIN);
        zone(2, 6, 12, 13, 18, 18, FOIN);
        zone(15, 19, 12, 13, 18, 18, FOIN);
        zone(8, 13, 15, 19, 17, 19, CIBLE);
        for (int[] p : new int[][] {{2, 7}, {19, 11}, {2, 15}, {20, 20}}) {
            b(p[0], 0, p[1], POTEAU);
            b(p[0], 1, p[1], POTEAU);
            b(p[0], 2, p[1], CIBLE);
        }
        b(0, 0, 1, TONNEAU);
        b(0, 0, 2, TONNEAU);
        b(21, 0, 1, s(Blocks.CHEST).setValue(BlockStateProperties.HORIZONTAL_FACING, c.allee()));
        banniere(3, 3, Blocks.RED_WALL_BANNER);
        banniere(18, 3, Blocks.RED_WALL_BANNER);
        pnjIci(10.5, 1.5, 0, 10.5, 18);
        pnjIci(6.5, 1.5, 0, 9, 18);
        pnjIci(15.5, 2.5, 0, 10.5, 16);
    }

    // ---------------------------------------------------------------- 2, le bassin profond
    private void bassin() {
        c = SALLES[1];
        zone(0, 21, -1, -1, 0, 23, DALLE);
        anneau(0, 21, -9, -1, 1, 22, s(Blocks.PRISMARINE_BRICKS));
        for (int u = 1; u <= 20; u++) {
            for (int v = 2; v <= 21; v++) {
                b(u, -9, v, u % 3 == 1 && v % 3 == 2 ? s(Blocks.SEA_LANTERN) : s(Blocks.PRISMARINE));
            }
        }
        zone(1, 20, -1, -1, 2, 21, null);
        zone(1, 20, -8, -2, 2, 21, s(Blocks.WATER));
        for (int[] p : new int[][] {{4, 6}, {15, 5}, {8, 15}, {17, 16}, {11, 10}, {4, 18}, {14, 9}}) {
            zone(p[0], p[0], -8, 1, p[1], p[1], s(Blocks.STRIPPED_OAK_LOG));
        }
        zone(10, 11, 0, 0, 18, 23, s(Blocks.BIRCH_SLAB));
        // une échelle pour sortir de l'eau
        for (int y = -8; y <= -1; y++) {
            b(1, y, 7, s(Blocks.LADDER).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST).setValue(BlockStateProperties.WATERLOGGED, y <= -2));
        }
        // la chaise du maître-nageur, les serviettes, les plantes
        b(0, 0, 12, POTEAU);
        b(0, 1, 12, POTEAU);
        b(0, 2, 12, BANC);
        b(3, 0, 23, s(Blocks.RED_CARPET));
        b(5, 0, 23, s(Blocks.BLUE_CARPET));
        b(16, 0, 23, s(Blocks.GREEN_CARPET));
        b(0, 0, 0, POT);
        b(21, 0, 0, POT);
        banniere(7, 3, Blocks.BLUE_WALL_BANNER);
        banniere(14, 3, Blocks.BLUE_WALL_BANNER);
        pnjIci(0.5, 12.5, 2.5, 10, 12.5);
        pnjIci(11, 18.6, 0.5, 11, 10);
    }

    // ---------------------------------------------------------------- 3, la fonderie
    private void fonderie() {
        c = SALLES[2];
        zone(0, 21, -1, -1, 0, 23, s(Blocks.BLACKSTONE));
        anneau(3, 18, -2, -2, 4, 19, s(Blocks.POLISHED_BASALT));
        anneau(3, 18, -1, -1, 4, 19, s(Blocks.LAVA));
        zone(10, 11, -1, -1, 4, 4, s(Blocks.BLACKSTONE));
        for (int u = 6; u <= 15; u++) {
            for (int v = 7; v <= 16; v++) {
                if ((u + v) % 2 == 0) {
                    b(u, -1, v, s(Blocks.MAGMA_BLOCK));
                }
            }
        }
        for (int[] p : new int[][] {{0, 1}, {21, 1}, {0, 12}, {21, 12}, {0, 23}, {21, 23}}) {
            zone(p[0], p[0], 0, 5, p[1], p[1], s(Blocks.POLISHED_BASALT));
        }
        for (int u : new int[] {3, 8, 13, 18}) {
            zone(u, u + 1, 0, 2, 21, 22, s(Blocks.OBSIDIAN));
        }
        b(20, 0, 2, s(Blocks.ANVIL));
        b(21, 0, 3, TONNEAU);
        b(1, 0, 2, s(Blocks.CHEST).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        banniere(10, 4, Blocks.YELLOW_WALL_BANNER);
        pnjIci(9, 20.4, 0, 9, 22);
        pnjIci(19.5, 2.5, 0, 10, 12);
    }

    // ---------------------------------------------------------------- 4, la chambre froide
    private void chambreFroide() {
        c = SALLES[3];
        zone(0, 21, -1, -1, 0, 23, s(Blocks.PACKED_ICE));
        for (int[] p : new int[][] {{4, 5}, {17, 5}, {4, 11}, {17, 11}, {4, 17}, {17, 17}}) {
            zone(p[0], p[0], 0, 6, p[1], p[1], s(Blocks.BLUE_ICE));
        }
        for (int[] p : new int[][] {{9, 3, 0}, {10, 3, 1}, {11, 3, 0}, {1, 8, 0}, {20, 13, 1}, {20, 14, 0}, {9, 13, 0}, {12, 8, 0}, {13, 8, 1},
                {8, 18, 0}, {14, 16, 0}, {1, 15, 1}}) {
            zone(p[0], p[0], 0, p[2], p[1], p[1], NEIGE);
        }
        zone(1, 20, 0, 4, 21, 23, NEIGE);
        // le bonhomme de neige de l'entrée, un banc
        b(19, 0, 2, NEIGE);
        b(19, 1, 2, NEIGE);
        b(19, 2, 2, s(Blocks.CARVED_PUMPKIN).setValue(BlockStateProperties.HORIZONTAL_FACING, c.allee()));
        b(1, 0, 1, BANC);
        b(2, 0, 1, BANC);
        banniere(5, 5, Blocks.LIGHT_BLUE_WALL_BANNER);
        banniere(16, 5, Blocks.LIGHT_BLUE_WALL_BANNER);
        pnjIci(7, 8, 0, 14, 8);
        pnjIci(2.5, 6.5, 0, 2.5, 19);
        pnjIci(10.5, 20.3, 0, 10.5, 22);
    }

    // ---------------------------------------------------------------- 5, le tunnel à vent
    private void tunnel() {
        c = SALLES[4];
        zone(0, 21, -1, -1, 0, 23, DALLE);
        anneau(7, 13, 0, 23, 8, 14, s(Blocks.GLASS));
        zone(10, 10, 0, 1, 8, 8, null);
        zone(8, 12, -1, -1, 9, 13, s(Blocks.IRON_TRAPDOOR).setValue(BlockStateProperties.HALF, Half.TOP));
        b(8, 6, 13, PERCHOIR);
        b(12, 12, 9, PERCHOIR);
        b(8, 18, 9, PERCHOIR);
        for (int[] p : new int[][] {{2, 4}, {2, 11}, {2, 18}, {19, 4}, {19, 11}, {19, 18}, {6, 21}, {15, 21}}) {
            b(p[0], 0, p[1], POTEAU);
            b(p[0], 1, p[1], FOIN);
            b(p[0], 2, p[1], FOIN);
        }
        b(4, 0, 1, BANC);
        b(5, 0, 1, BANC);
        b(16, 0, 1, BANC);
        b(17, 0, 1, BANC);
        b(21, 0, 23, s(Blocks.CHEST).setValue(BlockStateProperties.HORIZONTAL_FACING, c.allee()));
        banniere(3, 3, Blocks.GREEN_WALL_BANNER);
        banniere(18, 3, Blocks.GREEN_WALL_BANNER);
        pnjIci(10.5, 6.6, 0, 10.5, 11);
        pnjIci(14.5, 5, 0, 10.5, 11);
    }

    // ---------------------------------------------------------------- 6, les îlots du gouffre
    private void ilots() {
        c = SALLES[5];
        zone(0, 21, -1, -1, 0, 23, s(Blocks.DEEPSLATE_TILES));
        anneau(0, 21, -11, -1, 0, 23, ARDOISE);
        anneau(0, 21, -1, -1, 0, 23, s(Blocks.DEEPSLATE_TILES));
        zone(1, 20, -11, -11, 1, 22, ARDOISE);
        zone(1, 20, -10, -1, 1, 22, null);
        Block[] laines = {Blocks.RED_WOOL, Blocks.YELLOW_WOOL, Blocks.LIME_WOOL, Blocks.BLUE_WOOL, Blocks.PURPLE_WOOL, Blocks.ORANGE_WOOL,
            Blocks.WHITE_WOOL, Blocks.CYAN_WOOL};
        int[][] ilots = {{3, 3}, {10, 4}, {17, 3}, {6, 10}, {15, 11}, {3, 17}, {11, 18}, {18, 20}};
        for (int k = 0; k < ilots.length; k++) {
            zone(ilots[k][0], ilots[k][0], -10, -1, ilots[k][1], ilots[k][1], ARDOISE);
            b(ilots[k][0], 0, ilots[k][1], s(laines[k]));
        }
        // une échelle pour remonter du fond
        for (int y = -10; y <= -1; y++) {
            b(1, y, 11, s(Blocks.LADDER).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        }
        for (int u : new int[] {0, 21}) {
            b(u, 0, 0, POTEAU);
            b(u, 1, 0, LANTERNE);
        }
        banniere(10, 3, Blocks.PURPLE_WALL_BANNER);
        pnjIci(17.5, 3.5, 1, 6.5, 10.5);
        pnjIci(6.5, 10.5, 1, 15.5, 11.5);
        pnjIci(10.5, 0.5, 0, 10.5, 8);
    }

    // ---------------------------------------------------------------- 7, la fosse de sable
    private void fosse() {
        c = SALLES[6];
        zone(0, 21, -1, -1, 0, 23, GRES_TAILLE);
        anneau(0, 21, -6, -1, 0, 23, GRES_TAILLE);
        zone(1, 20, -6, -6, 1, 22, GRES_TAILLE);
        zone(1, 20, -5, -1, 1, 22, null);
        for (int u = 1; u <= 20; u++) {
            for (int v = 1; v <= 22; v++) {
                zone(u, u, -5, sableH(u, v), v, v, s(Blocks.SAND));
            }
        }
        for (int[] p : new int[][] {{4, 16, 3}, {16, 6, 2}, {11, 19, 4}, {5, 5, 2}, {17, 15, 5}}) {
            zone(p[0], p[0], -5, p[2], p[1], p[1], s(Blocks.SANDSTONE));
        }
        b(9, 0, 9, FOIN);
        b(9, 1, 9, FOIN);
        b(13, 0, 13, FOIN);
        // le parasol, les tonneaux
        b(21, 0, 0, POTEAU);
        b(21, 1, 0, POTEAU);
        b(21, 2, 0, POTEAU);
        for (int u = 20; u <= 21; u++) {
            for (int v = 0; v <= 1; v++) {
                b(u, 3, v, s((u + v) % 2 == 1 ? Blocks.RED_WOOL : Blocks.WHITE_WOOL));
            }
        }
        b(0, 0, 0, TONNEAU);
        b(1, 0, 0, TONNEAU);
        banniere(6, 3, Blocks.ORANGE_WALL_BANNER);
        banniere(15, 3, Blocks.ORANGE_WALL_BANNER);
        pnjIci(8.5, 11.5, sableH(8, 11) + 1, 9, 14);
        pnjIci(20.5, 0.5, 0, 10, 11);
    }

    // ---------------------------------------------------------------- 8, les pylônes de cuivre
    private void pylonesCuivre() {
        c = SALLES[7];
        Block[] plaques = {Blocks.WAXED_CUT_COPPER, Blocks.WAXED_CUT_COPPER, Blocks.WAXED_EXPOSED_CUT_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER};
        for (int u = 0; u <= 18; u++) {
            for (int v = 0; v <= 23; v++) {
                b(u, -1, v, s(plaques[alea.nextInt(4)]));
            }
        }
        zone(19, 21, -1, -1, 0, 23, s(Blocks.SMOOTH_STONE));
        zone(19, 21, -1, -1, 0, 0, s(Blocks.WHITE_CONCRETE));
        zone(19, 21, -1, -1, 23, 23, s(Blocks.WHITE_CONCRETE));
        for (int[] p : PIEDS) {
            zone(p[0], p[0], 0, 9, p[1], p[1], s(Blocks.WAXED_COPPER_BLOCK));
            b(p[0], 10, p[1], s(Blocks.LIGHTNING_ROD));
        }
        b(0, 0, 0, s(Blocks.CHEST).setValue(BlockStateProperties.HORIZONTAL_FACING, c.allee()));
        b(17, 0, 23, BANC);
        b(18, 0, 23, BANC);
        banniere(4, 3, Blocks.CYAN_WALL_BANNER);
        banniere(16, 3, Blocks.CYAN_WALL_BANNER);
        pnjIci(18.5, 0.6, 0, 20.5, 12);
        pnjIci(9.5, 8.5, 0, 4.5, 5.5);
        pnjIci(20.5, 3, 0, 20.5, 22);
    }

    // ---------------------------------------------------------------- 9, les piliers d'os
    private void piliersOs() {
        c = SALLES[8];
        zone(0, 21, -1, -1, 0, 23, s(Blocks.CALCITE));
        Set<Long> pris = new HashSet<>();
        List<int[]> piliers = new ArrayList<>();
        for (int u = 2; u <= 19; u += 4) {
            for (int v = 2; v <= 22; v += 4) {
                int pu = u + alea.nextInt(3) - 1, pv = v + alea.nextInt(3) - 1, h = 2 + alea.nextInt(9);
                zone(pu, pu, 0, h - 1, pv, pv, s(Blocks.BONE_BLOCK));
                pris.add(cle(pu, 0, pv));
                piliers.add(new int[] {pu, pv});
            }
        }
        for (int k = 0, n = 0; k < 200 && n < 20; k++) {
            int u = 1 + alea.nextInt(20), v = 1 + alea.nextInt(22);
            if (pris.add(cle(u, 0, v))) {
                b(u, 0, v, s(Blocks.POINTED_DRIPSTONE));
                n++;
            }
        }
        int[] cible = piliers.stream().filter(p -> p[1] >= 2 && !pris.contains(cle(p[0], 0, p[1] - 1))).findFirst().orElse(piliers.get(0));
        osU = cible[0];
        osV = cible[1];
        b(10, 0, 0, BANC);
        b(11, 0, 0, BANC);
        b(0, 0, 0, POT);
        b(21, 0, 0, TONNEAU);
        banniere(10, 3, Blocks.WHITE_WALL_BANNER);
        pnjIci(osU + 0.5, osV - 0.4, 0, osU + 0.5, osV + 0.5);
        pnjIci(10.5, 0.55, 0.5, 10.5, 10);
    }

    // ---------------------------------------------------------------- 10, la volière
    private void voliere() {
        c = SALLES[9];
        zone(0, 21, -1, -1, 0, 23, s(Blocks.MOSS_BLOCK));
        BlockState barreaux = s(Blocks.IRON_BARS);
        for (int y = 0; y <= 20; y++) {
            for (int u = 0; u <= 21; u++) {
                b(u, y, 0, barreaux);
                b(u, y, 23, barreaux);
            }
            for (int v = 1; v <= 22; v++) {
                b(0, y, v, barreaux);
                b(21, y, v, barreaux);
            }
        }
        zone(10, 11, 0, 2, 0, 0, null);
        int[][] tour = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int[] cc : new int[][] {{4, 17}, {16, 6}, {15, 18}}) {
            for (int y = 0; y <= 19; y++) {
                int[] a = tour[y % 4], o = tour[(y + 2) % 4];
                b(cc[0] + a[0], y, cc[1] + a[1], s(Blocks.PURPUR_PILLAR));
                b(cc[0] + o[0], y, cc[1] + o[1], s(Blocks.PURPUR_PILLAR));
            }
        }
        zone(1, 2, 7, 7, 9, 10, PERCHOIR);
        zone(19, 20, 13, 13, 13, 14, PERCHOIR);
        zone(9, 10, 16, 16, 21, 22, PERCHOIR);
        // les anneaux dorés : des cadres de 5 × 5 en or, pendus à des chaînes
        int[][] anneaux = {{10, 4, 6}, {6, 9, 11}, {15, 11, 14}, {11, 15, 10}, {17, 7, 3}, {5, 16, 5}, {12, 18, 19}};
        for (int k = 0; k < anneaux.length; k++) {
            int u = anneaux[k][0], y = anneaux[k][1], v = anneaux[k][2];
            for (int a = -2; a <= 2; a++) {
                for (int h = -2; h <= 2; h++) {
                    if (Math.abs(a) == 2 || Math.abs(h) == 2) {
                        if (k % 2 == 0) {
                            b(u + a, y + h, v, s(Blocks.GOLD_BLOCK));
                        } else {
                            b(u, y + h, v + a, s(Blocks.GOLD_BLOCK));
                        }
                    }
                }
            }
            for (int yy = y + 3; yy <= 21; yy++) {
                b(u, yy, v, s(Blocks.CHAIN));
            }
        }
        for (int[] p : new int[][] {{2, 2}, {19, 3}, {3, 21}, {19, 21}, {10, 14}}) {
            b(p[0], 0, p[1], POT);
        }
        pnjIci(2, 9.5, 7.5, 12, 9.5);
        pnjIci(8, 4, 0, 13, 4);
        pnjIci(11.5, 11.5, 0, 6, 11);
    }

    // ---------------------------------------------------------------- l'allée
    private void allee() {
        remplit(0, LX, 8, 8, MILIEU, MILIEU, s(Blocks.DARK_OAK_LOG).setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        for (int x = 11; x < LX; x += 20) {
            remplit(x, x, 5, 7, MILIEU, MILIEU, s(Blocks.CHAIN));
            pose(x, 4, MILIEU, LANTERNE.setValue(BlockStateProperties.HANGING, true));
        }
        remplit(3, 5, 0, 0, Z_ALLEE + 1, Z_ALLEE + 1, s(Blocks.STRIPPED_DARK_OAK_WOOD));
        pose(2, 0, Z_FIN, POT);
        for (int x : new int[] {20, 43, 66, 89}) {
            remplit(x, x + 1, 0, 0, Z_ALLEE + 1, Z_ALLEE + 1, BANC);
            remplit(x, x + 1, 0, 0, Z_FIN - 1, Z_FIN - 1, BANC);
            pose(x + 3, 0, Z_ALLEE + 1, POT);
        }
        remplit(97, 103, 0, 0, Z_ALLEE + 1, Z_FIN - 1, s(Blocks.BLUE_CARPET));
        // les vestiaires : des portes de fer, comme des casiers
        for (int x = 111; x <= 116; x++) {
            for (int[] zz : new int[][] {{Z_ALLEE, 2}, {Z_FIN, 0}}) {
                Direction face = zz[1] == 2 ? Direction.SOUTH : Direction.NORTH;
                BlockState porte = s(Blocks.IRON_DOOR).setValue(BlockStateProperties.HORIZONTAL_FACING, face);
                pose(x, 0, zz[0], porte.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
                pose(x, 1, zz[0], porte.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
            }
        }
        pose(LX - 1, 0, MILIEU - 2, TONNEAU);
        pose(LX - 1, 0, MILIEU + 2, TONNEAU);
        // le bouton du retour, à côté de l'arrivée
        pose(1, 0, MILIEU - 1, Entrainement.BOUTON.get().defaultBlockState());
        // les pnj de l'allée : l'accueil, l'échauffement sur les tapis, les vestiaires
        pnj.add(new Pnj(4.5, 0, Z_ALLEE + 0.5, 4.5, MILIEU));
        for (double[] p : new double[][] {{98.5, Z_ALLEE + 2.5}, {100.5, Z_FIN - 1.5}, {102.5, Z_ALLEE + 2.5}}) {
            pnj.add(new Pnj(p[0], 0, p[1], 100.5, MILIEU + 0.2));
        }
        pnj.add(new Pnj(100.5, 0, MILIEU + 0.2, 100.5, Z_ALLEE));
        pnj.add(new Pnj(113.5, 0, Z_ALLEE + 1.5, 113.5, Z_ALLEE));
    }

    // ---------------------------------------------------------------- la construction

    /** Construit (ou reconstruit) toute la salle : les blocs, les biomes des salles, les pnj. */
    static void construire(ServerLevel l, int construction) {
        EntrainementBuilder b = new EntrainementBuilder();
        b.planifier();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -2; x <= LX + 2; x++) {
            for (int z = -2; z <= LZ + 2; z++) {
                for (int y = -13; y <= 30; y++) {
                    BlockState voulu = b.plan.getOrDefault(cle(x, y, z), air);
                    p.set(x + OX, y + SOL, z + OZ);
                    if (l.getBlockState(p) != voulu) {
                        l.setBlock(p, voulu, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        // les barreaux et les clôtures se raccordent à leurs voisins
        for (Map.Entry<Long, BlockState> e : b.plan.entrySet()) {
            BlockState st = e.getValue();
            if (st.is(Blocks.IRON_BARS) || st.is(Blocks.DARK_OAK_FENCE)) {
                BlockPos q = BlockPos.of(e.getKey()).offset(OX, SOL, OZ);
                l.setBlock(q, Block.updateFromNeighbourShapes(st, l, q), Block.UPDATE_CLIENTS);
            }
        }
        biomes(l);
        b.placerPnj(l, construction);
    }

    /** Chaque salle prend son biome (sa brume, ce qui flotte dans l'air, ses bruits). */
    private static void biomes(ServerLevel l) {
        MinecraftServer server = l.getServer();
        var source = server.createCommandSourceStack().withLevel(l).withPermission(4).withSuppressedOutput().withPosition(arrivee());
        for (Salle sa : SALLES) {
            String cmd = String.format("fillbiome %d %d %d %d %d %d cabane:%s", sa.x0() + OX, SOL - 13, sa.z0() + OZ, sa.x1() + OX, SOL + sa.h() + 2,
                    sa.z1() + OZ, sa.biome());
            server.getCommands().performPrefixedCommand(source, cmd);
        }
    }

    /** Les variantes de skin des pnj (les skins de base du jeu), à tour de rôle. */
    private static final String[] VARIANTES = {"STEVE", "ALEX", "ARI", "EFE", "KAI", "MAKENA", "NOOR", "SUNNY", "ZURI"};

    private void placerPnj(ServerLevel l, int construction) {
        AABB zone = new AABB(OX - 2, SOL - 14, OZ - 2, OX + LX + 3, SOL + 31, OZ + LZ + 3);
        for (Entity e : l.getEntities((Entity) null, zone, e -> e.getTags().contains(TAG))) {
            e.discard();
        }
        int k = 0;
        for (Pnj n : pnj) {
            String snbt = "{id:\"easy_npc:humanoid\",PersistenceRequired:1b,NoAI:1b,Tags:[\"" + TAG + "\",\"" + PNJ + "\",\"" + tagConstruction(construction)
                    + "\"],EasyNPCVersion:3,VariantType:\"" + VARIANTES[k++ % VARIANTES.length] + "\","
                    + "attributes:[{id:\"minecraft:generic.knockback_resistance\",base:1.0d}],ObjectiveData:{ObjectiveDataSet:[]},Status:{finalized:1b}}";
            float lacet = (float) Math.toDegrees(Math.atan2(-(n.vx() - n.x()), n.vz() - n.z()));
            double x = n.x() + OX, y = n.y() + SOL, z = n.z() + OZ;
            try {
                Entity e = EntityType.loadEntityRecursive(TagParser.parseTag(snbt), l, en -> {
                    en.moveTo(x, y, z, lacet, 0F);
                    return en;
                });
                if (e instanceof LivingEntity vivant) {
                    vivant.setYHeadRot(lacet);
                    vivant.setYBodyRot(lacet);
                    l.addFreshEntity(vivant);
                }
            } catch (Exception ex) {
                LOG.warn("Pnj de la salle d'entraînement impossible à poser (Easy NPC absent ?)", ex);
                return;
            }
        }
    }

    public static String tagConstruction(int n) {
        return TAG + ".n" + n;
    }

    private EntrainementBuilder() {
    }
}
