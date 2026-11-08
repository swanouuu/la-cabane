package fr.cabane.backrooms;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Le générateur de la dimension cabane:backrooms : chaque tronçon de 16 × 16 est posé d'après {@link Labyrinthe}, sans
 * rien d'autre (ni grottes, ni minerais, ni structures). La moquette en y = 0, les murs de y = 1 à 3 (le bas humide), le
 * plafond en y = 4 avec un néon au milieu de chaque pièce, et une dalle pleine au-dessus.
 */
public class GenerateurBackrooms extends ChunkGenerator {
    public static final MapCodec<GenerateurBackrooms> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)).apply(i, i.stable(GenerateurBackrooms::new)));

    public GenerateurBackrooms(BiomeSource biomes) {
        super(biomes);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    private static BlockState tourne(BlockState s, int sens) {
        return s.setValue(HorizontalDirectionalBlock.FACING, Labyrinthe.DIRECTIONS[sens]);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
        BlockState moquette = Backrooms.MOQUETTE.get().defaultBlockState();
        BlockState mur = Backrooms.MUR.get().defaultBlockState();
        BlockState plinthe = Backrooms.PLINTHE.get().defaultBlockState();
        BlockState plafond = Backrooms.PLAFOND.get().defaultBlockState();
        BlockState casier = Backrooms.CASIER.get().defaultBlockState();
        BlockState porte = Backrooms.PORTE.get().defaultBlockState();
        BlockState panneau = Backrooms.EXIT.get().defaultBlockState();
        BlockState[] neons = {plafond, Backrooms.NEON.get().defaultBlockState(), Backrooms.NEON_CLIGNOTE.get().defaultBlockState(),
                Backrooms.NEON_MORT.get().defaultBlockState()};
        Heightmap h1 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap h2 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz;
                long c = Labyrinthe.caseEn(x, z);
                int type = Labyrinthe.type(c), sens = Labyrinthe.sens(c);
                BlockState[] colonne = new BlockState[6];
                colonne[0] = moquette;
                colonne[5] = plafond;
                int lx = Math.floorMod(x, Labyrinthe.P), lz = Math.floorMod(z, Labyrinthe.P);
                colonne[4] = lx == 3 && lz == 3 ? neons[Labyrinthe.neon(Labyrinthe.fdiv(x, Labyrinthe.P), Labyrinthe.fdiv(z, Labyrinthe.P))] : plafond;
                switch (type) {
                    case Labyrinthe.MUR -> {
                        colonne[1] = plinthe;
                        colonne[2] = mur;
                        colonne[3] = mur;
                    }
                    case Labyrinthe.CASIER -> {
                        colonne[1] = tourne(casier, sens).setValue(BlocsBackrooms.MOITIE, DoubleBlockHalf.LOWER);
                        colonne[2] = tourne(casier, sens).setValue(BlocsBackrooms.MOITIE, DoubleBlockHalf.UPPER);
                    }
                    case Labyrinthe.PORTE -> {
                        colonne[1] = tourne(porte, sens).setValue(BlocsBackrooms.MOITIE, DoubleBlockHalf.LOWER);
                        colonne[2] = tourne(porte, sens).setValue(BlocsBackrooms.MOITIE, DoubleBlockHalf.UPPER);
                        colonne[3] = tourne(panneau, sens);
                    }
                    default -> {
                    }
                }
                for (int y = 0; y < colonne.length; y++) {
                    if (colonne[y] != null) {
                        chunk.setBlockState(p.set(dx, y, dz), colonne[y], false);
                        h1.update(dx, y, dz, colonne[y]);
                        h2.update(dx, y, dz, colonne[y]);
                    }
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk,
            GenerationStep.Carving step) {
    }

    /** Rien d'autre que le labyrinthe : ni minerais, ni plantes, ni structures (même celles que d'autres mods ajoutent partout). */
    @Override
    public void applyBiomeDecoration(net.minecraft.world.level.WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
    }

    @Override
    public void createStructures(net.minecraft.core.RegistryAccess registres, net.minecraft.world.level.chunk.ChunkGeneratorStructureState etat,
            StructureManager structures, ChunkAccess chunk, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager modeles) {
    }

    @Override
    public void createReferences(net.minecraft.world.level.WorldGenLevel level, StructureManager structures, ChunkAccess chunk) {
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structures, RandomState random, ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
    }

    @Override
    public int getGenDepth() {
        return 16;
    }

    @Override
    public int getSeaLevel() {
        return -63;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor level) {
        return 1;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        return 6;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        BlockState[] col = new BlockState[height.getHeight()];
        java.util.Arrays.fill(col, Blocks.AIR.defaultBlockState());
        col[0] = Backrooms.MOQUETTE.get().defaultBlockState();
        col[4] = Backrooms.PLAFOND.get().defaultBlockState();
        col[5] = Backrooms.PLAFOND.get().defaultBlockState();
        return new NoiseColumn(height.getMinBuildHeight(), col);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        int i = Labyrinthe.fdiv(pos.getX(), Labyrinthe.P), k = Labyrinthe.fdiv(pos.getZ(), Labyrinthe.P);
        info.add("Backrooms : pièce " + i + ", " + k + (Labyrinthe.salle(i, k) ? " (grande salle)" : "") + (Labyrinthe.sombre(i, k) ? " (zone noire)" : ""));
    }

    static Direction direction(int sens) {
        return Labyrinthe.DIRECTIONS[sens];
    }
}
