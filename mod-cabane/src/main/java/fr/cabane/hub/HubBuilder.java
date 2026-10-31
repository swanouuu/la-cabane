package fr.cabane.hub;

import com.mojang.datafixers.util.Pair;
import fr.cabane.Cabane;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.common.Tags;

/**
 * Construit le centre de map : une clairière dans la savane avec la cabane abandonnée,
 * un feu de camp, un puits, une tour de guet en ruine (feu de signal visible de loin),
 * un vieux potager et le tableau des quêtes.
 *
 * <p>Coordonnées relatives au centre {@code c} : y = 0 est le niveau du sol.
 */
public class HubBuilder {
    private static final int RAYON = 22;
    private static final int FONDU = 8;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ServerLevel level;
    private final BlockPos c;
    private final RandomSource rnd;
    private final List<BlockPos> poses = new ArrayList<>();

    public HubBuilder(ServerLevel level, BlockPos centre) {
        this.level = level;
        this.c = centre;
        this.rnd = RandomSource.create(level.getSeed() ^ centre.asLong());
    }

    /** Cherche une savane près du centre du monde, sur la terre ferme. Renvoie le bloc de sol. */
    public static BlockPos trouverSavane(ServerLevel level) {
        BlockPos origine = new BlockPos(0, 80, 0);
        for (int essai = 0; essai < 6; essai++) {
            Pair<BlockPos, Holder<Biome>> trouve = level.findClosestBiome3d(HubBuilder::estSavane, origine, 6400, 32, 64);
            if (trouve == null) {
                return null;
            }
            BlockPos p = trouve.getFirst();
            level.getChunk(p.getX() >> 4, p.getZ() >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) - 1;
            BlockPos sol = new BlockPos(p.getX(), y, p.getZ());
            if (level.getFluidState(sol).isEmpty() && y > level.getSeaLevel()) {
                Cabane.LOGGER.info("Savane trouvée pour la cabane en {}", sol);
                return sol;
            }
            origine = p.offset(160, 0, 160);
        }
        return null;
    }

    private static boolean estSavane(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_SAVANNA) || biome.is(Tags.Biomes.IS_SAVANNA)
                || biome.unwrapKey().map(k -> k.location().getPath().contains("savanna")).orElse(false);
    }

    public void construire() {
        int max = RAYON + FONDU;
        for (int cx = (c.getX() - max) >> 4; cx <= (c.getX() + max) >> 4; cx++) {
            for (int cz = (c.getZ() - max) >> 4; cz <= (c.getZ() + max) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        preparerTerrain();
        chemins();
        cabane();
        porche();
        feuDeCamp();
        puits();
        tour();
        potager();
        tableau();
        cloture();
        finitions();
    }

    // ------------------------------------------------------------------ terrain

    private void preparerTerrain() {
        int y0 = c.getY();
        int max = RAYON + FONDU;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > max) {
                    continue;
                }
                int x = c.getX() + dx;
                int z = c.getZ() + dz;
                int sol = solNaturel(x, z);
                boolean dedans = d <= RAYON;
                if (!dedans && !level.getFluidState(new BlockPos(x, sol, z)).isEmpty()) {
                    continue;
                }
                int cible = y0;
                if (!dedans) {
                    double t = (d - RAYON) / FONDU;
                    t = t * t * (3 - 2 * t);
                    cible = (int) Math.round(y0 + (sol - y0) * t);
                }
                int haut = Math.max(sol, cible) + (dedans ? 32 : 16);
                for (int y = cible + 1; y <= haut; y++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        abs(x, y, z, AIR);
                    }
                }
                for (int y = Math.min(sol + 1, cible - 3); y < cible; y++) {
                    abs(x, y, z, Blocks.DIRT.defaultBlockState());
                }
                boolean terreBattue = Math.sin(x * 0.31) + Math.cos(z * 0.27) + rnd.nextFloat() * 0.6 > 1.55;
                abs(x, cible, z, (terreBattue ? Blocks.COARSE_DIRT : Blocks.GRASS_BLOCK).defaultBlockState());
                if (dedans && !terreBattue && rnd.nextFloat() < 0.14F) {
                    abs(x, cible + 1, z, (rnd.nextFloat() < 0.06F ? Blocks.DANDELION : Blocks.SHORT_GRASS).defaultBlockState());
                }
            }
        }
    }

    /** Hauteur du vrai sol, sans les arbres ni les plantes. */
    private int solNaturel(int x, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
        while (p.getY() > level.getMinBuildHeight()) {
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty()) {
                return p.getY();
            }
            if (s.isAir() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.canBeReplaced() || !s.isSolid()) {
                p.move(Direction.DOWN);
                continue;
            }
            return p.getY();
        }
        return p.getY();
    }

    // ------------------------------------------------------------------ chemins

    private void chemins() {
        chemin(-1, -4, 1, -2);    // porche → feu de camp
        chemin(-1, 3, 1, 21);     // feu de camp → sortie sud
        chemin(2, 2, 9, 3);       // → puits
        chemin(-11, 2, -2, 3);    // → tour
        chemin(-11, 4, -11, 6);
    }

    private void chemin(int xa, int za, int xb, int zb) {
        for (int x = xa; x <= xb; x++) {
            for (int z = za; z <= zb; z++) {
                float r = rnd.nextFloat();
                pose(x, 0, z, (r < 0.12F ? Blocks.COARSE_DIRT : r < 0.18F ? Blocks.GRAVEL : Blocks.DIRT_PATH).defaultBlockState());
                pose(x, 1, z, AIR);
            }
        }
    }

    // ------------------------------------------------------------------ la cabane

    private static final int X0 = -6, X1 = 6, Z0 = -15, Z1 = -7, ZC = -11;

    private void cabane() {
        for (int x = X0 - 1; x <= X1 + 1; x++) {
            for (int z = Z0 - 1; z <= Z1 + 1; z++) {
                for (int y = 1; y <= 10; y++) {
                    pose(x, y, z, AIR);
                }
            }
        }
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                boolean bord = x == X0 || x == X1 || z == Z0 || z == Z1;
                pose(x, -1, z, Blocks.COBBLESTONE.defaultBlockState());
                pose(x, 0, z, bord ? pierre() : plancher());
            }
        }
        for (int y = 1; y <= 4; y++) {
            for (int x = X0; x <= X1; x++) {
                mur(x, y, Z0);
                mur(x, y, Z1);
            }
            for (int z = Z0 + 1; z < Z1; z++) {
                mur(X0, y, z);
                mur(X1, y, z);
            }
        }
        toit();
        cheminee();
        interieur();
        lierre();
    }

    private void mur(int x, int y, int z) {
        boolean longMur = z == Z0 || z == Z1;
        boolean coin = (x == X0 || x == X1) && longMur;
        if (coin) {
            pose(x, y, z, pilier(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
            return;
        }
        if (y == 4) {
            pose(x, y, z, pilier(Blocks.STRIPPED_SPRUCE_LOG, longMur ? Direction.Axis.X : Direction.Axis.Z));
            return;
        }
        // porte (ouverture, la porte a disparu depuis longtemps)
        if (z == Z1 && x == 0 && y <= 2) {
            pose(x, y, z, AIR);
            return;
        }
        // âtre, côté est
        if (x == X1 && z == ZC && y <= 2) {
            pose(x, y, z, AIR);
            return;
        }
        if ((longMur && x == 0) || (!longMur && z == ZC)) {
            pose(x, y, z, pilier(Blocks.SPRUCE_LOG, Direction.Axis.Y));
            return;
        }
        boolean fenetre = y >= 2 && y <= 3 && ((z == Z1 && (Math.abs(x) == 3 || Math.abs(x) == 4)) || (!longMur && (z == -13 || z == -9)));
        if (fenetre) {
            pose(x, y, z, rnd.nextFloat() < 0.3F ? AIR : Blocks.GLASS_PANE.defaultBlockState());
            return;
        }
        float r = rnd.nextFloat();
        boolean murDesReliques = z == Z0 && Math.abs(x) <= 3;
        if (!murDesReliques && r < 0.04F) {
            pose(x, y, z, AIR);
        } else if (r < 0.10F) {
            pose(x, y, z, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        } else {
            pose(x, y, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
        }
    }

    private int hauteurToit(int z) {
        return 4 + (5 - Math.abs(z - ZC));
    }

    private void toit() {
        for (int z = Z0 - 1; z <= Z1 + 1; z++) {
            int dz = z - ZC;
            int h = hauteurToit(z);
            for (int x = X0 - 1; x <= X1 + 1; x++) {
                boolean bord = x == X0 - 1 || x == X1 + 1 || Math.abs(dz) == 5;
                if (!bord && rnd.nextFloat() < 0.05F) {
                    continue; // trou dans le toit
                }
                if (dz == 0) {
                    pose(x, h, z, Blocks.DARK_OAK_PLANKS.defaultBlockState());
                } else {
                    pose(x, h, z, escalier(Blocks.DARK_OAK_STAIRS, dz < 0 ? Direction.SOUTH : Direction.NORTH));
                }
            }
        }
        // pignons
        for (int z = Z0 + 1; z < Z1; z++) {
            for (int y = 5; y < hauteurToit(z); y++) {
                BlockState s = z == ZC && (y == 6 || y == 7) ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState();
                pose(X0, y, z, s);
                pose(X1, y, z, s);
            }
        }
    }

    private void cheminee() {
        for (int y = -1; y <= 11; y++) {
            for (int z = ZC - 1; z <= ZC + 1; z++) {
                boolean foyer = z == ZC && (y == 1 || y == 2);
                pose(7, y, z, foyer ? AIR : pierre());
            }
            pose(8, y, ZC, pierre());
        }
        pose(7, 1, ZC, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.FACING, Direction.WEST));
        pose(7, 12, ZC, Blocks.CAMPFIRE.defaultBlockState());
    }

    private void interieur() {
        // lit
        pose(-5, 1, -13, Blocks.BROWN_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT));
        pose(-5, 1, -14, Blocks.BROWN_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD));
        // comptoir et mur des reliques
        for (int x = -3; x <= 3; x++) {
            pose(x, 1, -14, Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        }
        // bibliothèque et pupitre avec le journal du Gardien
        for (int z = -11; z <= -10; z++) {
            pose(-5, 1, z, Blocks.BOOKSHELF.defaultBlockState());
            pose(-5, 2, z, Blocks.BOOKSHELF.defaultBlockState());
        }
        pose(-4, 1, -12, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.EAST));
        // cuisine
        pose(5, 1, -14, Blocks.CRAFTING_TABLE.defaultBlockState());
        pose(5, 1, -13, Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, Direction.WEST));
        pose(5, 1, -9, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        // table et chaises
        pose(0, 1, ZC, Blocks.DARK_OAK_FENCE.defaultBlockState());
        pose(0, 2, ZC, Blocks.SPRUCE_PRESSURE_PLATE.defaultBlockState());
        pose(-1, 1, ZC, escalier(Blocks.SPRUCE_STAIRS, Direction.WEST));
        pose(1, 1, ZC, escalier(Blocks.SPRUCE_STAIRS, Direction.EAST));
        pose(0, 1, ZC + 1, escalier(Blocks.SPRUCE_STAIRS, Direction.SOUTH));
        // coffres
        pose(-4, 1, -8, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST));
        pose(4, 1, -8, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        // lanternes suspendues au faîtage
        for (int x : new int[] {-3, 0, 3}) {
            for (int y = 6; y <= 8; y++) {
                pose(x, y, ZC, Blocks.CHAIN.defaultBlockState());
            }
            pose(x, 5, ZC, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        // toiles d'araignée et mousse
        for (int[] t : new int[][] {{-5, 4, -14}, {5, 4, -8}, {-5, 4, -8}, {5, 4, -14}, {-2, 7, -12}, {2, 6, -10}}) {
            pose(t[0], t[1], t[2], Blocks.COBWEB.defaultBlockState());
        }
        for (int x = X0 + 1; x < X1; x++) {
            for (int z = Z0 + 1; z < Z1; z++) {
                if (level.getBlockState(c.offset(x, 1, z)).isAir() && rnd.nextFloat() < 0.10F) {
                    pose(x, 1, z, Blocks.MOSS_CARPET.defaultBlockState());
                }
            }
        }

        coffre(-4, 1, -8, "coffres/cabane");
        coffre(4, 1, -8, "coffres/cabane");
        journal(-4, 1, -12);
        panneau(0, 3, -14, Direction.SOUTH, "", "LES SEPT", "RELIQUES", "");
        for (int x = -3; x <= 3; x++) {
            level.addFreshEntity(new ItemFrame(level, c.offset(x, 2, -14), Direction.SOUTH));
        }
    }

    private void lierre() {
        for (int y = 1; y <= 4; y++) {
            for (int x = X0; x <= X1; x++) {
                vigne(x, y, Z1 + 1, Direction.NORTH);
                vigne(x, y, Z0 - 1, Direction.SOUTH);
            }
            for (int z = Z0; z <= Z1; z++) {
                vigne(X0 - 1, y, z, Direction.EAST);
            }
        }
    }

    private void vigne(int x, int y, int z, Direction versLeMur) {
        if (rnd.nextFloat() < 0.18F && level.getBlockState(c.offset(x, y, z)).isAir()
                && level.getBlockState(c.offset(x, y, z).relative(versLeMur)).isSolid()) {
            pose(x, y, z, Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(versLeMur), true));
        }
    }

    private void porche() {
        for (int x = -4; x <= 4; x++) {
            for (int z = -6; z <= -5; z++) {
                pose(x, 0, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
                for (int y = 1; y <= 3; y++) {
                    pose(x, y, z, AIR);
                }
            }
            pose(x, 4, -5, Blocks.SPRUCE_SLAB.defaultBlockState());
        }
        for (int x : new int[] {-4, 4}) {
            for (int y = 1; y <= 3; y++) {
                pose(x, y, -5, pilier(Blocks.SPRUCE_LOG, Direction.Axis.Y));
            }
            pose(x, 1, -6, Blocks.SPRUCE_FENCE.defaultBlockState());
        }
        for (int x : new int[] {-3, -2, 2, 3}) {
            pose(x, 1, -5, Blocks.SPRUCE_FENCE.defaultBlockState());
        }
        pose(-3, 3, -5, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        pose(3, 3, -5, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        pose(3, 1, -6, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        pose(-3, 1, -6, Blocks.COMPOSTER.defaultBlockState());
        pose(-2, 1, -6, Blocks.CHIPPED_ANVIL.defaultBlockState());
    }

    // ------------------------------------------------------------------ autour

    private void feuDeCamp() {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                pose(dx, 0, dz, (dx == 0 && dz == 0 ? Blocks.COBBLESTONE : Blocks.GRAVEL).defaultBlockState());
                pose(dx, 1, dz, AIR);
            }
        }
        pose(0, 1, 0, Blocks.CAMPFIRE.defaultBlockState());
        for (int dz = -1; dz <= 1; dz++) {
            pose(-3, 1, dz, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.Z));
            pose(3, 1, dz, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.Z));
        }
        lampadaire(2, -3);
        lampadaire(-2, 8);
        lampadaire(2, 14);
        lampadaire(-2, 20);
    }

    private void lampadaire(int x, int z) {
        pose(x, 0, z, Blocks.COBBLESTONE.defaultBlockState());
        pose(x, 1, z, Blocks.SPRUCE_FENCE.defaultBlockState());
        pose(x, 2, z, Blocks.LANTERN.defaultBlockState());
    }

    private void puits() {
        final int px = 10, pz = 6;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean bord = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int y = -3; y <= 0; y++) {
                    pose(px + dx, y, pz + dz, bord || y == -3 ? pierre() : Blocks.WATER.defaultBlockState());
                }
                pose(px + dx, 1, pz + dz, bord ? pierre() : AIR);
                for (int y = 2; y <= 3; y++) {
                    pose(px + dx, y, pz + dz, AIR);
                }
                if (rnd.nextFloat() < 0.85F) {
                    pose(px + dx, 4, pz + dz, Blocks.SPRUCE_SLAB.defaultBlockState());
                }
            }
        }
        for (int dx : new int[] {-2, 2}) {
            for (int dz : new int[] {-2, 2}) {
                pose(px + dx, 2, pz + dz, Blocks.SPRUCE_FENCE.defaultBlockState());
                pose(px + dx, 3, pz + dz, Blocks.SPRUCE_FENCE.defaultBlockState());
            }
        }
    }

    private void tour() {
        final int tx = -14, tz = 6;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean bord = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                pose(tx + dx, 0, tz + dz, briques());
                pose(tx + dx, -1, tz + dz, Blocks.COBBLESTONE.defaultBlockState());
                for (int y = 1; y <= 9; y++) {
                    boolean effondre = dz == 2 && y >= 7 && rnd.nextFloat() < 0.55F;
                    pose(tx + dx, y, tz + dz, bord && !effondre ? briques() : AIR);
                }
                pose(tx + dx, 10, tz + dz, bord ? briques() : Blocks.SPRUCE_PLANKS.defaultBlockState());
                boolean creneau = bord && (dx + dz) % 2 == 0 && rnd.nextFloat() < 0.7F;
                pose(tx + dx, 11, tz + dz, creneau ? Blocks.STONE_BRICK_WALL.defaultBlockState() : AIR);
            }
        }
        // porte côté est, échelle contre le mur ouest
        pose(tx + 2, 1, tz, AIR);
        pose(tx + 2, 2, tz, AIR);
        for (int y = 1; y <= 10; y++) {
            pose(tx - 1, y, tz, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST));
        }
        // feu de signal : sa fumée se voit de très loin
        pose(tx + 1, 10, tz + 1, Blocks.HAY_BLOCK.defaultBlockState());
        pose(tx + 1, 11, tz + 1, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, true));
        pose(tx + 1, 11, tz - 1, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        coffre(tx + 1, 11, tz - 1, "coffres/tour");
        // éboulis
        for (int i = 0; i < 10; i++) {
            int x = tx + rnd.nextInt(9) - 4;
            int z = tz + 3 + rnd.nextInt(3);
            if (level.getBlockState(c.offset(x, 1, z)).canBeReplaced()) {
                pose(x, 1, z, (rnd.nextBoolean() ? Blocks.COBBLESTONE : Blocks.MOSSY_COBBLESTONE).defaultBlockState());
            }
        }
    }

    private void potager() {
        for (int x = 9; x <= 15; x++) {
            for (int z = -15; z <= -7; z++) {
                boolean bord = x == 9 || x == 15 || z == -15 || z == -7;
                if (bord) {
                    pose(x, 1, z, rnd.nextFloat() < 0.7F ? Blocks.OAK_FENCE.defaultBlockState() : AIR);
                    continue;
                }
                if (x == 12 && z == ZC) {
                    pose(x, 0, z, Blocks.WATER.defaultBlockState());
                    pose(x, 1, z, AIR);
                    continue;
                }
                if (rnd.nextFloat() < 0.15F) {
                    pose(x, 0, z, Blocks.COARSE_DIRT.defaultBlockState());
                    pose(x, 1, z, Blocks.DEAD_BUSH.defaultBlockState());
                    continue;
                }
                Block culture = switch (Math.floorMod(z, 3)) {
                    case 0 -> Blocks.WHEAT;
                    case 1 -> Blocks.CARROTS;
                    default -> Blocks.POTATOES;
                };
                pose(x, 0, z, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
                pose(x, 1, z, culture.defaultBlockState().setValue(CropBlock.AGE, rnd.nextInt(8)));
            }
        }
    }

    private void tableau() {
        for (int x = -5; x <= -3; x++) {
            pose(x, 1, -3, x == -4 ? AIR : Blocks.DARK_OAK_FENCE.defaultBlockState());
            pose(x, 2, -3, Blocks.SPRUCE_PLANKS.defaultBlockState());
            pose(x, 3, -3, Blocks.SPRUCE_PLANKS.defaultBlockState());
            pose(x, 4, -3, Blocks.SPRUCE_SLAB.defaultBlockState());
        }
        panneau(-4, 3, -2, Direction.SOUTH, "~ TABLEAU ~", "DES QUÊTES", "", "");
        panneau(-5, 2, -2, Direction.SOUTH, "Ouvre ton", "livre de", "quêtes !", "");
        panneau(-3, 2, -2, Direction.SOUTH, "Le Gardien", "t'attend près", "du porche.", "");
    }

    private void cloture() {
        for (int dx = -RAYON; dx <= RAYON; dx++) {
            for (int dz = -RAYON; dz <= RAYON; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (Math.abs(d - 20) > 0.5 || (Math.abs(dx) <= 2 && dz > 0)) {
                    continue;
                }
                double angle = Math.atan2(dz, dx);
                if (Math.sin(angle * 7) > -0.35 && rnd.nextFloat() < 0.85F) {
                    pose(dx, 1, dz, Blocks.OAK_FENCE.defaultBlockState());
                }
            }
        }
    }

    // ------------------------------------------------------------------ finitions

    /** Raccorde vitres, barrières et escaliers à leurs voisins une fois tout posé. */
    private void finitions() {
        for (BlockPos p : poses) {
            BlockState s = level.getBlockState(p);
            BlockState raccorde = Block.updateFromNeighbourShapes(s, level, p);
            if (raccorde != s && !raccorde.isAir()) {
                level.setBlock(p, raccorde, FLAGS);
            }
        }
    }

    private void coffre(int x, int y, int z, String table) {
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, Cabane.id(table));
        RandomizableContainer.setBlockEntityLootTable(level, rnd, c.offset(x, y, z), key);
    }

    private void panneau(int x, int y, int z, Direction facing, String... lignes) {
        BlockPos p = c.offset(x, y, z);
        level.setBlock(p, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing), FLAGS);
        if (level.getBlockEntity(p) instanceof SignBlockEntity sign) {
            SignText texte = new SignText();
            for (int i = 0; i < lignes.length && i < 4; i++) {
                texte = texte.setMessage(i, Component.literal(lignes[i]));
            }
            sign.setText(texte, true);
            sign.setWaxed(true);
        }
    }

    private void journal(int x, int y, int z) {
        BlockPos p = c.offset(x, y, z);
        List<Filterable<Component>> pages = List.of(
                "Journal du Gardien\n\nCette cabane fut le refuge de la Confrérie des Explorateurs. Nous partions d'ici vers les quatre coins du monde.",
                "Sept reliques reposaient sur ce comptoir. Quand la Confrérie s'est dispersée, chacune a été cachée au cœur d'un donjon lointain.",
                "Le Scarabée d'Or, sous le sable.\nL'Idole de Jade, dans la jungle.\nLa Perle des Abysses, sous la mer.\nLa Plume Céleste, dans le ciel.",
                "La Couronne Illageoise, chez nos ennemis.\nL'Œil du Gardien, sous la terre.\nL'Engrenage Ancien, dans les fonderies.",
                "Ramenez-les et posez-les sur les cadres du mur. Alors la Cabane se réveillera.\n\n— Le Vieux Gardien")
                .stream().map(t -> Filterable.passThrough((Component) Component.literal(t))).toList();
        ItemStack livre = new ItemStack(Items.WRITTEN_BOOK);
        livre.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough("Journal du Gardien"), "Le Vieux Gardien", 0, pages, true));
        LecternBlock.tryPlaceBook(null, level, p, level.getBlockState(p), livre);
    }

    // ------------------------------------------------------------------ outils

    private void pose(int dx, int dy, int dz, BlockState state) {
        BlockPos p = c.offset(dx, dy, dz);
        level.setBlock(p, state, FLAGS);
        poses.add(p);
    }

    private void abs(int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, FLAGS);
    }

    private BlockState pierre() {
        return (rnd.nextFloat() < 0.3F ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE).defaultBlockState();
    }

    private BlockState briques() {
        float r = rnd.nextFloat();
        return (r < 0.25F ? Blocks.MOSSY_STONE_BRICKS : r < 0.45F ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
    }

    private BlockState plancher() {
        float r = rnd.nextFloat();
        return (r < 0.06F ? Blocks.COARSE_DIRT : r < 0.15F ? Blocks.DARK_OAK_PLANKS : Blocks.SPRUCE_PLANKS).defaultBlockState();
    }

    private static BlockState pilier(Block block, Direction.Axis axis) {
        return block.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
    }

    private static BlockState escalier(Block block, Direction facing) {
        return block.defaultBlockState().setValue(StairBlock.FACING, facing);
    }
}
