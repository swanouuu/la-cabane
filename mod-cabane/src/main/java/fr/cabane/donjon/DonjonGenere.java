package fr.cabane.donjon;

import fr.cabane.Cabane;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * Construit un donjon tracé par {@link PlanDonjon}, avec les pierres et le décor de son thème : le glacier (glace,
 * neige, calcite), le nid de soie (mousse, racines, toiles, cocons), la nécropole (briques d'ardoise, terre des âmes,
 * crânes, bougies), la fourmilière (terre cuite, boue, rayons de miel, œufs). Comme pour la grotte d'Ugrak : on creuse
 * d'abord le vide, puis une coque de deux blocs autour, puis le décor.
 */
public final class DonjonGenere {
    /** À changer quand les donjons tracés changent : ils sont reconstruits au prochain démarrage. */
    public static final int VERSION = 2;

    private static final int Y0 = 50, Y1 = 92, Z0 = -15;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final ServerLevel level;
    private final TypeDonjon type;
    private final PlanDonjon plan;
    private final int X0, X1, Z1, NX, NY, NZ;
    private final boolean[] vide;
    private final boolean[] roche;

    private DonjonGenere(ServerLevel level, TypeDonjon type) {
        this.level = level;
        this.type = type;
        this.plan = PlanDonjon.de(type);
        X0 = plan.xMin();
        X1 = plan.xMax();
        Z1 = plan.zMax();
        NX = X1 - X0 + 1;
        NY = Y1 - Y0 + 1;
        NZ = Z1 - Z0 + 1;
        vide = new boolean[NX * NY * NZ];
        roche = new boolean[NX * NY * NZ];
    }

    public static void construire(ServerLevel level, TypeDonjon type) {
        long t0 = System.currentTimeMillis();
        DonjonGenere b = new DonjonGenere(level, type);
        b.creuser();
        b.coque();
        b.poser();
        b.decorer();
        Cabane.LOGGER.info("Donjon « {} » construit en {} ms", type.nom, System.currentTimeMillis() - t0);
    }

    // ---------------------------------------------------------------- le vide

    private int i(int x, int y, int z) {
        return ((x - X0) * NY + (y - Y0)) * NZ + (z - Z0);
    }

    private boolean dans(int x, int y, int z) {
        return x >= X0 && x <= X1 && y >= Y0 && y <= Y1 && z >= Z0 && z <= Z1;
    }

    private boolean estVide(int x, int y, int z) {
        return dans(x, y, z) && vide[i(x, y, z)];
    }

    private void creuser() {
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                int h = plan.plafond(x, z);
                if (h < 0) {
                    continue;
                }
                // un peu de relief au plafond des salles : la roche n'est pas taillée au cordeau
                int bosse = (int) Math.floorMod(hash(x / 3, 0, z / 3), 3L) - 1;
                int s = plan.sol(x, z);
                if (h - s > 9) {
                    h += bosse;
                }
                for (int y = s + 1; y <= h; y++) {
                    if (dans(x, y, z)) {
                        vide[i(x, y, z)] = true;
                    }
                }
            }
        }
    }

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

    // ---------------------------------------------------------------- les pierres du thème

    private static long hash(int x, int y, int z) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L;
        h ^= h >>> 13;
        h *= 0x5bd1e995L;
        return h ^ (h >>> 15);
    }

    private static int r100(int x, int y, int z) {
        return (int) Math.floorMod(hash(x, y, z), 100L);
    }

    private static BlockState b(Block b) {
        return b.defaultBlockState();
    }

    /** Choisit dans une liste de (pourcentage cumulé, bloc). */
    private static BlockState tirer(int r, Object... choix) {
        for (int k = 0; k < choix.length; k += 2) {
            if (r < (Integer) choix[k]) {
                return b((Block) choix[k + 1]);
            }
        }
        return b((Block) choix[choix.length - 1]);
    }

    private BlockState mur(int x, int y, int z) {
        int r = r100(x, y, z);
        return switch (type) {
            case GIVRE -> tirer(r, 38, Blocks.PACKED_ICE, 50, Blocks.BLUE_ICE, 74, Blocks.SNOW_BLOCK, 88, Blocks.CALCITE, 100, Blocks.POLISHED_DIORITE);
            case ARAIGNEE -> tirer(r, 25, Blocks.MOSSY_COBBLESTONE, 45, Blocks.MOSS_BLOCK, 65, Blocks.DEEPSLATE, 80, Blocks.ROOTED_DIRT, 92, Blocks.COBBLESTONE,
                    100, Blocks.MUD);
            case LICHE -> tirer(r, 2, Blocks.BONE_BLOCK, 36, Blocks.DEEPSLATE_BRICKS, 52, Blocks.CRACKED_DEEPSLATE_BRICKS, 68, Blocks.DEEPSLATE_TILES, 90,
                    Blocks.POLISHED_BLACKSTONE_BRICKS, 100, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS);
            default -> tirer(r, 3, Blocks.HONEYCOMB_BLOCK, 28, Blocks.TERRACOTTA, 43, Blocks.ORANGE_TERRACOTTA, 58, Blocks.BROWN_TERRACOTTA, 80,
                    Blocks.PACKED_MUD, 100, Blocks.MUD_BRICKS);
        };
    }

    private boolean couloir(int x, int z) {
        for (PlanDonjon.Couloir c : plan.couloirs) {
            if (c.dans(x, z) && Math.min(c.x1() - c.x0(), c.z1() - c.z0()) <= 6) {
                return true;
            }
        }
        return false;
    }

    private BlockState sol(int x, int y, int z) {
        int r = r100(x, y, z);
        PlanDonjon.Salle a = plan.arene;
        double d = Math.sqrt(Math.pow(x - a.cx(), 2) + Math.pow(z - a.cz(), 2));
        boolean arene = z > plan.grandePorteZ && d <= a.rx() + 1;
        boolean anneau = arene && (Math.abs(d - 8) < 0.7 || Math.abs(d - 16) < 0.7);
        boolean dalle = couloir(x, z) || (Math.abs(x - plan.x0) <= 2 && z >= plan.porteSceauxZ && z <= plan.escalierZ1 + 2)
                || plan.dansAntichambre(new net.minecraft.world.phys.Vec3(x + 0.5, PlanDonjon.SOL_BAS + 1, z + 0.5));
        return switch (type) {
            case GIVRE -> anneau ? b(Blocks.BLUE_ICE) : arene ? tirer(r, 70, Blocks.SNOW_BLOCK, 100, Blocks.POLISHED_DIORITE)
                    : dalle ? tirer(r, 55, Blocks.POLISHED_DIORITE, 85, Blocks.CALCITE, 100, Blocks.SNOW_BLOCK)
                    : tirer(r, 60, Blocks.SNOW_BLOCK, 76, Blocks.PACKED_ICE, 90, Blocks.CALCITE, 100, Blocks.POLISHED_DIORITE);
            case ARAIGNEE -> anneau ? b(Blocks.MOSSY_STONE_BRICKS) : arene ? tirer(r, 50, Blocks.MOSS_BLOCK, 80, Blocks.COARSE_DIRT, 100, Blocks.ROOTED_DIRT)
                    : dalle ? tirer(r, 55, Blocks.MOSSY_STONE_BRICKS, 85, Blocks.CRACKED_STONE_BRICKS, 100, Blocks.MOSS_BLOCK)
                    : tirer(r, 45, Blocks.MOSS_BLOCK, 65, Blocks.COARSE_DIRT, 80, Blocks.ROOTED_DIRT, 100, Blocks.MOSSY_COBBLESTONE);
            case LICHE -> anneau ? b(Blocks.CRYING_OBSIDIAN) : arene ? tirer(r, 70, Blocks.POLISHED_BLACKSTONE, 100, Blocks.POLISHED_BLACKSTONE_BRICKS)
                    : dalle ? tirer(r, 60, Blocks.POLISHED_DEEPSLATE, 85, Blocks.DEEPSLATE_TILES, 100, Blocks.CRACKED_DEEPSLATE_TILES)
                    : tirer(r, 35, Blocks.SOUL_SOIL, 70, Blocks.DEEPSLATE_TILES, 90, Blocks.DEEPSLATE_BRICKS, 100, Blocks.SOUL_SAND);
            default -> anneau ? b(Blocks.HONEYCOMB_BLOCK) : arene ? tirer(r, 60, Blocks.PACKED_MUD, 100, Blocks.COARSE_DIRT)
                    : dalle ? tirer(r, 60, Blocks.MUD_BRICKS, 100, Blocks.PACKED_MUD)
                    : tirer(r, 40, Blocks.COARSE_DIRT, 70, Blocks.PACKED_MUD, 90, Blocks.ROOTED_DIRT, 100, Blocks.TERRACOTTA);
        };
    }

    private BlockState plafondBloc(int x, int y, int z) {
        int r = r100(x, y, z);
        return switch (type) {
            case GIVRE -> tirer(r, 55, Blocks.PACKED_ICE, 80, Blocks.SNOW_BLOCK, 100, Blocks.BLUE_ICE);
            case ARAIGNEE -> tirer(r, 50, Blocks.ROOTED_DIRT, 80, Blocks.MOSS_BLOCK, 100, Blocks.DEEPSLATE);
            case LICHE -> tirer(r, 60, Blocks.DEEPSLATE_BRICKS, 85, Blocks.POLISHED_BLACKSTONE_BRICKS, 100, Blocks.DEEPSLATE_TILES);
            default -> tirer(r, 45, Blocks.PACKED_MUD, 75, Blocks.TERRACOTTA, 90, Blocks.HONEYCOMB_BLOCK, 100, Blocks.MUD_BRICKS);
        };
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
                    if (roche[k]) {
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

    // ---------------------------------------------------------------- le décor

    private void decorer() {
        escalier();
        lumieres();
        parois();
        plafonds();
        cristaux();
        portailDeSortie();
        gardes();
        arene();
        sallesDesEnigmes();
        culsDeSac();
        Epreuves.de(type).reinitialiser(level, true);
    }

    private BlockState marche() {
        Block m = switch (type) {
            case GIVRE -> Blocks.POLISHED_DIORITE_STAIRS;
            case ARAIGNEE -> Blocks.MOSSY_STONE_BRICK_STAIRS;
            case LICHE -> Blocks.DEEPSLATE_BRICK_STAIRS;
            default -> Blocks.MUD_BRICK_STAIRS;
        };
        return m.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH).setValue(StairBlock.HALF, Half.BOTTOM);
    }

    private void escalier() {
        for (int z = plan.escalierZ0; z <= plan.escalierZ1 + 1; z++) {
            int s = plan.sol(plan.x0, z);
            if (s < plan.sol(plan.x0, z - 1)) {
                for (int x = plan.x0 - 3; x <= plan.x0 + 3; x++) {
                    pose(x, s + 1, z, marche());
                }
            }
        }
    }

    private void lumiere(int x, int y, int z, int niveau) {
        if (estVide(x, y, z) && level.getBlockState(new BlockPos(x, y, z)).isAir()) {
            pose(x, y, z, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, niveau));
        }
    }

    private int plafondReel(int x, int z) {
        int s = plan.sol(x, z);
        int y = s + 1;
        while (y < Y1 && estVide(x, y + 1, z)) {
            y++;
        }
        return estVide(x, s + 1, z) ? y : -1;
    }

    private BlockState lanterneBloc() {
        return switch (type) {
            case LICHE, GIVRE -> Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
            default -> Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
        };
    }

    private void lanterne(int x, int z, int longueur) {
        int h = plafondReel(x, z);
        if (h < 0 || h - plan.sol(x, z) < longueur + 3) {
            return;
        }
        for (int k = 0; k < longueur; k++) {
            pose(x, h - k, z, Blocks.CHAIN.defaultBlockState());
        }
        pose(x, h - longueur, z, lanterneBloc());
    }

    private void lumieres() {
        for (PlanDonjon.Couloir c : plan.couloirs) {
            boolean enX = c.x1() - c.x0() > c.z1() - c.z0();
            int cx = (c.x0() + c.x1()) / 2, cz = (c.z0() + c.z1()) / 2;
            int de = enX ? c.x0() + 2 : c.z0() + 2, a = enX ? c.x1() - 2 : c.z1() - 2;
            for (int k = de; k <= a; k += 7) {
                int x = enX ? k : cx, z = enX ? cz : k;
                lanterne(x, z, 2);
                lumiere(x, plan.sol(x, z) + 3, z, 10);
            }
        }
        for (int x = X0 + 2; x <= X1 - 2; x += 6) {
            for (int z = Z0 + 2; z <= Z1 - 2; z += 6) {
                if (plan.plafond(x, z) < 0) {
                    continue;
                }
                boolean arene = z > plan.grandePorteZ;
                lumiere(x, plan.sol(x, z) + 4, z, arene ? 14 : 10);
                if (arene) {
                    lumiere(x, plan.sol(x, z) + 12, z, 15);
                }
            }
        }
        for (PlanDonjon.Salle s : plan.salles) {
            if (!s.nom().equals("arene")) {
                lanterne(s.cx(), s.cz(), 3);
            }
        }
    }

    /** Ce qui pousse contre les parois : lichen, toiles, crânes, œufs… */
    private void parois() {
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (int x = X0 + 1; x < X1; x++) {
            for (int z = Z0 + 1; z < Z1; z++) {
                int s = plan.sol(x, z);
                if (!estVide(x, s + 1, z) || !level.getBlockState(new BlockPos(x, s + 1, z)).isAir() || Math.abs(x - plan.x0) <= 3 && z < 20) {
                    continue;
                }
                Direction contre = null;
                for (Direction d : dirs) {
                    if (roche(x + d.getStepX(), s + 1, z + d.getStepZ())) {
                        contre = d;
                        break;
                    }
                }
                long h = Math.floorMod(hash(x, s, z), 1000L);
                if (contre == null) {
                    // au sol, loin des murs : un peu de neige, de mousse…
                    if (type == TypeDonjon.GIVRE && h < 90) {
                        pose(x, s + 1, z, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1 + (int) (h % 2)));
                    } else if (type == TypeDonjon.ARAIGNEE && h < 70 && z < plan.grandePorteZ) {
                        pose(x, s + 1, z, b(Blocks.MOSS_CARPET));
                    }
                    continue;
                }
                switch (type) {
                    case GIVRE -> {
                        if (h < 25) {
                            pose(x, s + 1, z, b(Blocks.BLUE_ICE));
                            if (h < 10) {
                                pose(x, s + 2, z, b(Blocks.PACKED_ICE));
                            }
                        } else if (h < 120) {
                            pose(x, s + 1, z, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2 + (int) (h % 3)));
                        }
                    }
                    case ARAIGNEE -> {
                        if (h < 60) {
                            pose(x, s + 1, z, b(Blocks.COBWEB));
                        } else if (h < 85) {
                            // un cocon : quelque chose (quelqu'un ?) enroulé dans la soie
                            pose(x, s + 1, z, b(Blocks.WHITE_WOOL));
                            pose(x, s + 2, z, b(Blocks.MUSHROOM_STEM));
                            pose(x, s + 3, z, b(Blocks.COBWEB));
                        } else if (h < 140) {
                            pose(x, s + 1, z, Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(contre), true));
                        }
                    }
                    case LICHE -> {
                        if (h < 30) {
                            pose(x, s + 1, z, Blocks.SKELETON_SKULL.defaultBlockState().setValue(SkullBlock.ROTATION, (int) (h % 16)));
                        } else if (h < 60) {
                            pose(x, s + 1, z, Blocks.PURPLE_CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 1 + (int) (h % 4))
                                    .setValue(CandleBlock.LIT, true));
                        } else if (h < 75) {
                            pose(x, s + 1, z, b(Blocks.BONE_BLOCK));
                        } else if (h < 95) {
                            pose(x, s + 1, z, b(Blocks.COBWEB));
                        }
                    }
                    default -> {
                        if (h < 30) {
                            // des œufs de fourmi géante, en grappes
                            pose(x, s + 1, z, b(Blocks.SNIFFER_EGG));
                        } else if (h < 42) {
                            pose(x, s + 1, z, b(Blocks.BEE_NEST));
                        } else if (h < 60) {
                            pose(x, s + 1, z, b(Blocks.HONEY_BLOCK));
                        } else if (h < 70) {
                            pose(x, s + 1, z, b(Blocks.OCHRE_FROGLIGHT));
                        }
                    }
                }
            }
        }
    }

    private boolean roche(int x, int y, int z) {
        return dans(x, y, z) && roche[i(x, y, z)];
    }

    /** Ce qui pend des voûtes : glaçons, racines et lianes lumineuses, chaînes, stalactites. */
    private void plafonds() {
        for (int x = X0 + 1; x < X1; x++) {
            for (int z = Z0 + 1; z < Z1; z++) {
                int h = plafondReel(x, z);
                if (h < 0 || h - plan.sol(x, z) < 7 || !level.getBlockState(new BlockPos(x, h, z)).isAir()) {
                    continue;
                }
                long k = Math.floorMod(hash(x, 7, z), 100L);
                switch (type) {
                    case GIVRE -> {
                        if (k < 5) {
                            int n = 1 + (int) Math.floorMod(hash(z, 3, x), 3L);
                            for (int j = 0; j < n; j++) {
                                pose(x, h - j, z, b(j == n - 1 ? Blocks.ICE : Blocks.PACKED_ICE));
                            }
                        }
                    }
                    case ARAIGNEE -> {
                        if (k < 6) {
                            pose(x, h, z, b(Blocks.HANGING_ROOTS));
                        } else if (k < 10) {
                            int n = 2 + (int) Math.floorMod(hash(z, 5, x), 4L);
                            for (int j = 0; j < n; j++) {
                                BlockState v = j == n - 1 ? Blocks.CAVE_VINES.defaultBlockState().setValue(CaveVines.BERRIES, j % 2 == 0)
                                        : Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(CaveVines.BERRIES, j % 2 == 0);
                                pose(x, h - j, z, v);
                            }
                        } else if (k < 11) {
                            pose(x, h, z, b(Blocks.SPORE_BLOSSOM));
                        } else if (k < 13) {
                            pose(x, h, z, b(Blocks.COBWEB));
                        }
                    }
                    case LICHE -> {
                        if (k < 3) {
                            int n = 2 + (int) Math.floorMod(hash(z, 5, x), 4L);
                            for (int j = 0; j < n; j++) {
                                pose(x, h - j, z, b(Blocks.CHAIN));
                            }
                        } else if (k < 5) {
                            pose(x, h, z, b(Blocks.COBWEB));
                        }
                    }
                    default -> {
                        if (k < 6) {
                            int n = 1 + (int) Math.floorMod(hash(z, 3, x), 3L);
                            for (int j = 0; j < n; j++) {
                                DripstoneThickness e = j == n - 1 ? DripstoneThickness.TIP : j == 0 && n > 2 ? DripstoneThickness.BASE : DripstoneThickness.MIDDLE;
                                if (n == 2 && j == 0) {
                                    e = DripstoneThickness.FRUSTUM;
                                }
                                pose(x, h - j, z, Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN)
                                        .setValue(PointedDripstoneBlock.THICKNESS, e));
                            }
                        } else if (k < 9) {
                            pose(x, h, z, b(Blocks.HANGING_ROOTS));
                        }
                    }
                }
            }
        }
    }

    /**
     * Les cristaux de mana, à miner : des grappes d'améthyste contre les parois des salles (pas dans l'arène). On les
     * reconnaît à leur socle de bloc d'améthyste.
     */
    private void cristaux() {
        int n = 0;
        for (int x = X0 + 1; x < X1; x++) {
            for (int z = Z0 + 1; z < plan.grandePorteZ - 2; z++) {
                int s = plan.sol(x, z);
                if (!estVide(x, s + 1, z) || Math.floorMod(hash(x, 91, z), 13L) != 0) {
                    continue;
                }
                // contre une paroi, et pas dans un couloir étroit
                boolean contre = roche(x + 1, s + 1, z) || roche(x - 1, s + 1, z) || roche(x, s + 1, z + 1) || roche(x, s + 1, z - 1);
                if (!contre || couloir(x, z)) {
                    continue;
                }
                pose(x, s, z, b(Blocks.AMETHYST_BLOCK));
                pose(x, s + 1, z, (hash(x, 3, z) & 1) == 0 ? Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP)
                        : Blocks.LARGE_AMETHYST_BUD.defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP));
                n++;
            }
        }
        // et une belle grappe au fond de chaque cul-de-sac et de chaque salle des indices
        for (PlanDonjon.Salle s : new PlanDonjon.Salle[] {plan.coffre, plan.indiceOrdre, plan.indiceCode}) {
            int sx = s.cx() < plan.x0 ? -1 : 1;
            for (int k = -2; k <= 2; k++) {
                int z = s.cz() + k, x = s.cx();
                // contre la paroi du fond
                while (estVide(x + sx, PlanDonjon.SOL + 1, z) && Math.abs(x - s.cx()) < 12) {
                    x += sx;
                }
                if (estVide(x, PlanDonjon.SOL + 1, z) && level.getBlockState(new net.minecraft.core.BlockPos(x, PlanDonjon.SOL + 1, z)).isAir()) {
                    pose(x, PlanDonjon.SOL, z, b(Blocks.AMETHYST_BLOCK));
                    pose(x, PlanDonjon.SOL + 1, z, Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP));
                    n++;
                }
            }
        }
        Cabane.LOGGER.info("Donjon « {} » : {} cristaux de mana", type.nom, n);
    }

    private void portailDeSortie() {
        int z0 = (int) Math.floor(DonjonBuilder.SORTIE.z);
        for (int x = plan.x0 - 5; x <= plan.x0 + 5; x++) {
            for (int z = z0 - 1; z <= z0 + 2; z++) {
                if (estVide(x, PlanDonjon.SOL + 1, z)) {
                    pose(x, PlanDonjon.SOL, z, b(Blocks.POLISHED_DEEPSLATE));
                    pose(x, PlanDonjon.SOL + 1, z, b(Blocks.AIR));
                }
            }
        }
        for (int x : new int[] {plan.x0 - 5, plan.x0 + 6}) {
            pose(x, PlanDonjon.SOL + 1, z0 + 1, Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        }
        // devant l'arrivée, rien ne gêne
        for (int x = plan.x0 - 3; x <= plan.x0 + 3; x++) {
            for (int z = -4; z <= 2; z++) {
                pose(x, PlanDonjon.SOL + 1, z, b(Blocks.AIR));
            }
        }
    }

    private BlockState pilier(int y) {
        return switch (type) {
            case GIVRE -> b(y % 5 == 0 ? Blocks.BLUE_ICE : Blocks.PACKED_ICE);
            case ARAIGNEE -> b(y % 5 == 0 ? Blocks.CHISELED_STONE_BRICKS : Blocks.MOSSY_STONE_BRICKS);
            case LICHE -> b(y % 6 == 0 ? Blocks.CHISELED_DEEPSLATE : Blocks.DEEPSLATE_BRICKS);
            default -> b(y % 5 == 0 ? Blocks.HONEYCOMB_BLOCK : Blocks.MUD_BRICKS);
        };
    }

    private void colonne(int x, int z) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                int s = plan.sol(x + dx, z + dz);
                int h = plafondReel(x + dx, z + dz);
                for (int y = s + 1; y <= h; y++) {
                    pose(x + dx, y, z + dz, pilier(y));
                }
            }
        }
    }

    private BlockState feu() {
        return (type == TypeDonjon.FOURMI || type == TypeDonjon.ARAIGNEE ? Blocks.CAMPFIRE : Blocks.SOUL_CAMPFIRE).defaultBlockState()
                .setValue(CampfireBlock.LIT, true);
    }

    /** La salle des gardes : deux rangées de piliers et des feux entre eux. */
    private void gardes() {
        PlanDonjon.Salle g = plan.gardes;
        int z0 = (int) (g.cz() - g.rz() + 6), z1 = (int) (g.cz() + g.rz() - 6);
        for (int z = z0; z <= z1; z += 9) {
            for (int x : new int[] {g.cx() - 8, g.cx() + 7}) {
                colonne(x, z);
                pose(x + (x < g.cx() ? 3 : -2), PlanDonjon.SOL_BAS + 1, z, feu());
            }
        }
    }

    /** L'arène : un anneau de grands piliers, des feux, et le lit du boss au fond. */
    private void arene() {
        PlanDonjon.Salle a = plan.arene;
        double r = a.rx() - 5;
        for (int k = 0; k < 12; k++) {
            double ang = k * Math.PI / 6;
            int x = a.cx() + (int) Math.round(Math.cos(ang) * r), z = a.cz() + (int) Math.round(Math.sin(ang) * r);
            if (z < a.cz() - r + 4 && Math.abs(x - a.cx()) < 8) {
                continue;
            }
            colonne(x, z);
            int bx = a.cx() + (int) Math.round(Math.cos(ang) * (r - 3)), bz = a.cz() + (int) Math.round(Math.sin(ang) * (r - 3));
            pose(bx, PlanDonjon.SOL_BAS + 1, bz, feu());
            lumiere(bx, PlanDonjon.SOL_BAS + 6, bz, 14);
        }
        net.minecraft.world.phys.Vec3 repos = plan.repos();
        int rx = (int) Math.floor(repos.x), rz = (int) Math.floor(repos.z);
        int y = PlanDonjon.SOL_BAS;
        switch (type) {
            case GIVRE -> {
                // un trône de glace derrière le colosse
                for (int dx = -3; dx <= 3; dx++) {
                    for (int k = 1; k <= 7 - Math.abs(dx); k++) {
                        pose(rx + dx, y + k, rz + 6, b(k % 3 == 0 ? Blocks.BLUE_ICE : Blocks.PACKED_ICE));
                    }
                }
                for (int dx = -5; dx <= 5; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        if (dx * dx + dz * dz <= 22) {
                            pose(rx + dx, y, rz + dz, b(Blocks.BLUE_ICE));
                        }
                    }
                }
            }
            case ARAIGNEE -> {
                // le nid : un tapis de soie et de mousse sous l'endroit où elle pend
                for (int dx = -6; dx <= 6; dx++) {
                    for (int dz = -6; dz <= 6; dz++) {
                        int d2 = dx * dx + dz * dz;
                        if (d2 <= 36) {
                            pose(rx + dx, y, rz + dz, b(d2 < 9 ? Blocks.WHITE_WOOL : Blocks.MOSS_BLOCK));
                        }
                    }
                }
                // des cocons pendus autour
                for (int k = 0; k < 6; k++) {
                    double ang = k * Math.PI / 3;
                    int cx = rx + (int) Math.round(Math.cos(ang) * 8), cz = rz + (int) Math.round(Math.sin(ang) * 8);
                    int h = plafondReel(cx, cz);
                    for (int j = 0; j < 6 && h > 0; j++) {
                        pose(cx, h - j, cz, j < 3 ? b(Blocks.COBWEB) : b(j == 5 ? Blocks.MUSHROOM_STEM : Blocks.WHITE_WOOL));
                    }
                }
            }
            case LICHE -> {
                // l'autel : une estrade, des bougies, deux braseros d'âmes
                for (int m = 0; m < 2; m++) {
                    for (int dx = -5 + m; dx <= 5 - m; dx++) {
                        for (int dz = -4 + m; dz <= 4 - m; dz++) {
                            pose(rx + dx, y + 1 + m, rz + dz, b(m == 1 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE));
                        }
                    }
                }
                for (int[] c : new int[][] {{-5, -4}, {5, -4}, {-5, 4}, {5, 4}, {-6, 0}, {6, 0}}) {
                    pose(rx + c[0], y + 1, rz + c[1], Blocks.PURPLE_CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 4).setValue(CandleBlock.LIT, true));
                }
                for (int k = 1; k <= 6; k++) {
                    pose(rx, y + 2 + k, rz + 6, b(k == 6 ? Blocks.CRYING_OBSIDIAN : Blocks.POLISHED_BLACKSTONE_BRICKS));
                }
            }
            default -> {
                // un monceau d'œufs derrière le roi
                for (int dx = -6; dx <= 6; dx++) {
                    for (int dz = 4; dz <= 8; dz++) {
                        if (Math.floorMod(hash(dx, 9, dz), 3L) != 0) {
                            pose(rx + dx, y + 1, rz + dz, b(Blocks.SNIFFER_EGG));
                        }
                    }
                }
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        if (dx * dx + dz * dz <= 18) {
                            pose(rx + dx, y, rz + dz, b(Blocks.HONEYCOMB_BLOCK));
                        }
                    }
                }
            }
        }
    }

    private void sallesDesEnigmes() {
        BlockState socle, bord, coeur;
        switch (type) {
            case GIVRE -> {
                socle = b(Blocks.PACKED_ICE);
                bord = b(Blocks.CHISELED_QUARTZ_BLOCK);
                coeur = b(Blocks.BLUE_ICE);
            }
            case ARAIGNEE -> {
                socle = b(Blocks.MOSSY_STONE_BRICKS);
                bord = b(Blocks.CHISELED_STONE_BRICKS);
                coeur = b(Blocks.VERDANT_FROGLIGHT);
            }
            case LICHE -> {
                socle = b(Blocks.POLISHED_BLACKSTONE_BRICKS);
                bord = b(Blocks.CHISELED_POLISHED_BLACKSTONE);
                coeur = b(Blocks.CRYING_OBSIDIAN);
            }
            default -> {
                socle = b(Blocks.MUD_BRICKS);
                bord = b(Blocks.CHISELED_SANDSTONE);
                coeur = b(Blocks.OCHRE_FROGLIGHT);
            }
        }
        // rien ne doit gêner autour des feux, des leviers et des sceaux
        for (PlanDonjon.Salle s : new PlanDonjon.Salle[] {plan.ordre, plan.code}) {
            for (int dx = -8; dx <= 8; dx++) {
                for (int dz = -8; dz <= 8; dz++) {
                    for (int y = PlanDonjon.SOL + 1; y <= PlanDonjon.SOL + 3; y++) {
                        if (estVide(s.cx() + dx, y, s.cz() + dz)) {
                            pose(s.cx() + dx, y, s.cz() + dz, b(Blocks.AIR));
                        }
                    }
                }
            }
        }
        Epreuves.de(type).construire(level, socle, socle, bord, coeur);
    }

    /** L'embuscade : un éboulis et des os ; le coffre : sur un socle. */
    private void culsDeSac() {
        PlanDonjon.Salle e = plan.embuscade;
        int s = e.cx() < plan.x0 ? -1 : 1;
        for (int dz = -3; dz <= 3; dz++) {
            for (int k = 0; k < 3; k++) {
                int x = e.cx() + s * (4 + k);
                int haut = PlanDonjon.SOL + 3 - k + (int) Math.floorMod(hash(x, 1, dz), 2L);
                for (int y = PlanDonjon.SOL + 1; y <= haut; y++) {
                    if (estVide(x, y, e.cz() + dz)) {
                        pose(x, y, e.cz() + dz, Math.floorMod(hash(x, y, dz), 3L) == 0 ? b(Blocks.GRAVEL) : mur(x, y, dz));
                    }
                }
            }
        }
        BlockPos c = Epreuves.de(type).coffre();
        pose(c.getX(), c.getY() - 1, c.getZ(), b(Blocks.CHISELED_POLISHED_BLACKSTONE));
    }
}
