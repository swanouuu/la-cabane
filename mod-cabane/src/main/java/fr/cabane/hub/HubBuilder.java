package fr.cabane.hub;

import com.mojang.datafixers.util.Pair;
import fr.cabane.Cabane;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
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
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.common.Tags;

/**
 * Le centre de map. On s'installe à côté d'un village de savane (« le village qui nous héberge »),
 * loin des structures hostiles, et on y construit notre place : la Grande Cabane, une place pavée
 * avec fontaine, le marché, le tableau des quêtes avec une carte de la région, la tour de guet,
 * le potager, le feu de camp et l'arche d'entrée. Au-dessus, l'avion en feu dont on saute en parachute.
 *
 * <p>Coordonnées relatives au centre {@code c} de la place : y = 0 est le sol.
 */
public class HubBuilder {
    private static final int RAYON = 30;
    private static final int FONDU = 10;
    public static final int HAUTEUR_AVION = 62;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    public static final String TAG = "cabane_hub";
    private static final List<String> HOSTILES = List.of("outpost", "umvuthana", "bandit", "cataclysm:");

    private final ServerLevel level;
    private final BlockPos c;
    private final RandomSource rnd;
    private final List<BlockPos> poses = new ArrayList<>();

    public record Site(BlockPos centre, BlockPos village) {
    }

    public HubBuilder(ServerLevel level, BlockPos centre) {
        this.level = level;
        this.c = centre;
        this.rnd = RandomSource.create(level.getSeed() ^ centre.asLong());
    }

    // ================================================================== recherche de l'emplacement

    /**
     * La recherche du site, faite petit à petit avec un budget de temps par appel à {@link #etape()},
     * pour ne jamais bloquer le serveur. Les emplacements possibles des villages et des structures
     * hostiles sont calculés avec les règles de placement du jeu, sans assembler les structures ;
     * seul le village retenu est réellement préparé.
     */
    public static final class Recherche {
        private static final int DISTANCE_HOSTILE = 320;
        private static final int RAYON_VILLAGES = 8000;
        private static final long BUDGET_MS = 1500;
        private static final int[][] AUTOUR = {{26, 0}, {-26, 0}, {0, 26}, {0, -26}, {18, 18}, {-18, -18}, {18, -18}, {-18, 18}};

        private record Candidat(ChunkPos chunk, Holder<Structure> structure, double distance) {
        }

        private final ServerLevel level;
        private final ChunkGenerator generateur;
        private final RandomState aleatoire;
        private final ChunkGeneratorStructureState etat;
        private final long graine;
        private final int mer;
        private final List<Holder<StructureSet>> setsVillages = new ArrayList<>();
        private final List<Holder<StructureSet>> setsHostiles = new ArrayList<>();
        private List<Candidat> candidats;
        private int index;
        // le village en cours d'évaluation, un côté par appel
        private BoundingBox bbEnCours;
        private int coteEnCours;
        private Site meilleurEnCours;
        private int meilleurEcart;
        // le repli en savane : anneau carré et position dans l'anneau
        private int anneau = 0;
        private int pointAnneau = 0;
        private Site resultat;
        private boolean fini;

        public Recherche(ServerLevel level) {
            this.level = level;
            this.generateur = level.getChunkSource().getGenerator();
            this.aleatoire = level.getChunkSource().randomState();
            this.etat = level.getChunkSource().getGeneratorState();
            this.graine = etat.getLevelSeed();
            this.mer = level.getSeaLevel();
            for (Holder<StructureSet> set : etat.possibleStructureSets()) {
                if (!(set.value().placement() instanceof RandomSpreadStructurePlacement)) {
                    continue;
                }
                if (set.value().structures().stream().anyMatch(e -> estVillageDeSavane(e.structure()))) {
                    setsVillages.add(set);
                }
                if (set.value().structures().stream().anyMatch(e -> estHostile(e.structure()))) {
                    setsHostiles.add(set);
                }
            }
        }

        private static String nom(Holder<Structure> s) {
            return s.unwrapKey().map(k -> k.location().toString()).orElse("");
        }

        private static boolean estVillageDeSavane(Holder<Structure> s) {
            String n = nom(s);
            return n.contains("village") && n.contains("savanna");
        }

        private static boolean estHostile(Holder<Structure> s) {
            String n = nom(s);
            return HOSTILES.stream().anyMatch(n::contains);
        }

        public boolean fini() {
            return fini;
        }

        public Site resultat() {
            return resultat;
        }

        /** Biome approximatif (à hauteur fixe) : bien moins cher que de calculer le relief. */
        private Holder<Biome> biome(int x, int z) {
            return level.getUncachedNoiseBiome(x >> 2, (mer + 10) >> 2, z >> 2);
        }

        private int sol(int x, int z) {
            return generateur.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, aleatoire) - 1;
        }

        /** Les villages de savane possibles, du plus proche au plus loin du centre du monde. */
        private List<Candidat> calculerCandidats() {
            List<Candidat> liste = new ArrayList<>();
            int rayonChunks = RAYON_VILLAGES >> 4;
            for (Holder<StructureSet> set : setsVillages) {
                RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) set.value().placement();
                int ecart = placement.spacing();
                for (int rx = Math.floorDiv(-rayonChunks, ecart); rx <= Math.floorDiv(rayonChunks, ecart); rx++) {
                    for (int rz = Math.floorDiv(-rayonChunks, ecart); rz <= Math.floorDiv(rayonChunks, ecart); rz++) {
                        ChunkPos cp = placement.getPotentialStructureChunk(graine, rx * ecart, rz * ecart);
                        if (!placement.isStructureChunk(etat, cp.x, cp.z)) {
                            continue;
                        }
                        Holder<Biome> b = biome(cp.getMiddleBlockX(), cp.getMiddleBlockZ());
                        set.value().structures().stream()
                                .filter(e -> estVillageDeSavane(e.structure()) && e.structure().value().biomes().contains(b))
                                .findFirst()
                                .ifPresent(e -> liste.add(new Candidat(cp, e.structure(), Math.hypot(cp.getMiddleBlockX(), cp.getMiddleBlockZ()))));
                    }
                }
            }
            liste.sort(java.util.Comparator.comparingDouble(Candidat::distance));
            Cabane.LOGGER.info("{} villages de savane possibles autour du centre du monde", liste.size());
            return liste;
        }

        /** Une structure hostile (avant-poste, village Umvuthana, donjon à boss…) dans les parages ? */
        boolean hostileProche(BlockPos pos) {
            int r = DISTANCE_HOSTILE >> 4;
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            for (Holder<StructureSet> set : setsHostiles) {
                RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) set.value().placement();
                int ecart = placement.spacing();
                for (int rx = Math.floorDiv(cx - r, ecart); rx <= Math.floorDiv(cx + r, ecart); rx++) {
                    for (int rz = Math.floorDiv(cz - r, ecart); rz <= Math.floorDiv(cz + r, ecart); rz++) {
                        ChunkPos cp = placement.getPotentialStructureChunk(graine, rx * ecart, rz * ecart);
                        double d = Math.hypot(cp.getMiddleBlockX() - pos.getX(), cp.getMiddleBlockZ() - pos.getZ());
                        if (d > DISTANCE_HOSTILE || !placement.isStructureChunk(etat, cp.x, cp.z)) {
                            continue;
                        }
                        Holder<Biome> b = biome(cp.getMiddleBlockX(), cp.getMiddleBlockZ());
                        boolean present = set.value().structures().stream().anyMatch(e -> estHostile(e.structure())
                                && e.structure().value().biomes().contains(b)
                                && d <= (nom(e.structure()).contains("umvuthana") ? DISTANCE_HOSTILE : 160));
                        if (present) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /** Un pas de recherche, d'environ {@link #BUDGET_MS} au plus. */
        public void etape() {
            if (fini) {
                return;
            }
            if (candidats == null) {
                candidats = calculerCandidats();
                return;
            }
            if (bbEnCours != null) {
                evaluerCote();
                return;
            }
            if (index < candidats.size()) {
                Candidat c = candidats.get(index++);
                BlockPos vp = c.chunk().getMiddleBlockPosition(0);
                ChunkAccess chunk = level.getChunk(c.chunk().x, c.chunk().z, ChunkStatus.STRUCTURE_STARTS, true);
                StructureStart start = chunk.getStartForStructure(c.structure().value());
                if (start == null || !start.isValid()) {
                    Cabane.LOGGER.info("Village en {} écarté : il ne se génère pas finalement", vp);
                    return;
                }
                bbEnCours = start.getBoundingBox();
                coteEnCours = 0;
                meilleurEnCours = null;
                meilleurEcart = Integer.MAX_VALUE;
                Cabane.LOGGER.info("Village candidat en {} ({}), on regarde autour…", vp, c.structure().getRegisteredName());
                return;
            }
            repliSavane();
        }

        /** Un côté du village (nord, est, sud, ouest) : assez plat et sans eau pour notre place ? */
        private void evaluerCote() {
            BoundingBox bb = bbEnCours;
            int cx = (bb.minX() + bb.maxX()) / 2;
            int cz = (bb.minZ() + bb.maxZ()) / 2;
            int marge = RAYON + 6;
            int[][] cotes = {{cx, bb.minZ() - marge}, {bb.maxX() + marge, cz}, {cx, bb.maxZ() + marge}, {bb.minX() - marge, cz}};
            int[] p = cotes[coteEnCours++];
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            boolean eau = false;
            List<Integer> hauteurs = new ArrayList<>();
            for (int dx = -24; dx <= 24; dx += 24) {
                for (int dz = -24; dz <= 24; dz += 24) {
                    int h = sol(p[0] + dx, p[1] + dz);
                    eau |= h <= mer || biome(p[0] + dx, p[1] + dz).is(BiomeTags.IS_RIVER) || biome(p[0] + dx, p[1] + dz).is(BiomeTags.IS_OCEAN);
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                    hauteurs.add(h);
                }
            }
            if (!eau && max - min <= 16 && max - min < meilleurEcart) {
                hauteurs.sort(Integer::compare);
                meilleurEcart = max - min;
                meilleurEnCours = new Site(new BlockPos(p[0], hauteurs.get(hauteurs.size() / 2), p[1]), new BlockPos(cx, 0, cz));
            }
            if (coteEnCours < 4) {
                return;
            }
            bbEnCours = null;
            if (meilleurEnCours == null) {
                Cabane.LOGGER.info("Village écarté : terrain trop accidenté ou trop d'eau autour");
            } else if (hostileProche(meilleurEnCours.centre())) {
                Cabane.LOGGER.info("Village écarté : structure hostile près de notre place");
            } else {
                Cabane.LOGGER.info("Village de savane choisi, notre place sera en {}", meilleurEnCours.centre());
                resultat = meilleurEnCours;
                fini = true;
            }
        }

        /** Repli : une savane plate, sur la terre ferme, loin des structures hostiles. Avec budget de temps. */
        private void repliSavane() {
            long debut = System.currentTimeMillis();
            while (System.currentTimeMillis() - debut < BUDGET_MS) {
                int r = anneau * 48;
                if (r > 6000) {
                    fini = true;
                    return;
                }
                int cote = r == 0 ? 1 : 8 * anneau;
                if (pointAnneau >= cote) {
                    anneau++;
                    pointAnneau = 0;
                    continue;
                }
                int k = pointAnneau++;
                int x;
                int z;
                if (r == 0) {
                    x = 0;
                    z = 0;
                } else {
                    int segment = k / (2 * anneau);
                    int t = k % (2 * anneau) * 48 - r;
                    switch (segment) {
                        case 0 -> { x = t; z = -r; }
                        case 1 -> { x = r; z = t; }
                        case 2 -> { x = -t; z = r; }
                        default -> { x = -r; z = -t; }
                    }
                }
                if (!savaneA(level, x, mer + 12, z)) {
                    continue;
                }
                int s = sol(x, z);
                if (s <= mer + 1) {
                    continue;
                }
                int min = s;
                int max = s;
                boolean ok = true;
                for (int[] d : AUTOUR) {
                    int h = sol(x + d[0], z + d[1]);
                    if (h <= mer + 1 || !savaneA(level, x + d[0], mer + 12, z + d[1])) {
                        ok = false;
                        break;
                    }
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                }
                BlockPos pos = new BlockPos(x, s, z);
                if (ok && max - min <= 10 && !hostileProche(pos)) {
                    Cabane.LOGGER.info("Savane trouvée pour la cabane en {}", pos);
                    resultat = new Site(pos, null);
                    fini = true;
                    return;
                }
            }
        }
    }

    private static boolean savaneA(ServerLevel level, int x, int y, int z) {
        Holder<Biome> biome = level.getUncachedNoiseBiome(x >> 2, y >> 2, z >> 2);
        return biome.is(BiomeTags.IS_SAVANNA) || biome.is(Tags.Biomes.IS_SAVANNA)
                || biome.unwrapKey().map(k -> k.location().getPath().contains("savanna")).orElse(false);
    }

    // ================================================================== construction

    public void construire(BlockPos village) {
        int max = RAYON + FONDU;
        for (int cx = (c.getX() - max) >> 4; cx <= (c.getX() + max) >> 4; cx++) {
            for (int cz = (c.getZ() - max) >> 4; cz <= (c.getZ() + max) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        mesure("preparerTerrain", () -> preparerTerrain());
        mesure("chemins", () -> chemins());
        mesure("place", () -> place());
        mesure("grandeCabane", () -> grandeCabane());
        mesure("marche", () -> marche());
        mesure("tableauDesQuetes", () -> tableauDesQuetes());
        mesure("feuDeCamp", () -> feuDeCamp());
        mesure("tour", () -> tour());
        mesure("potager", () -> potager());
        mesure("arche", () -> arche());
        mesure("cloture", () -> cloture());
        mesure("finitions", () -> finitions());
        if (village != null) {
            mesure("cheminVersLeVillage", () -> cheminVersLeVillage(village));
        }
        mesure("avion", () -> avion());
        mesure("textes", () -> textes());
    }

    private static void mesure(String nom, Runnable r) {
        long t = System.currentTimeMillis();
        r.run();
        Cabane.LOGGER.info("  construction {} : {} ms", nom, System.currentTimeMillis() - t);
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
                int haut = Math.max(sol, cible) + (dedans ? 34 : 16);
                for (int y = cible + 1; y <= haut; y++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        abs(x, y, z, AIR);
                    }
                }
                for (int y = Math.min(sol + 1, cible - 3); y < cible; y++) {
                    abs(x, y, z, Blocks.DIRT.defaultBlockState());
                }
                boolean terreBattue = Math.sin(x * 0.73 + z * 0.21) * Math.cos(z * 0.67 - x * 0.19) + rnd.nextFloat() * 0.5 > 1.0;
                abs(x, cible, z, (terreBattue ? Blocks.COARSE_DIRT : Blocks.GRASS_BLOCK).defaultBlockState());
                if (dedans && !terreBattue && rnd.nextFloat() < 0.14F) {
                    abs(x, cible + 1, z, (rnd.nextFloat() < 0.06F ? Blocks.DANDELION : Blocks.SHORT_GRASS).defaultBlockState());
                }
            }
        }
    }

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

    // ------------------------------------------------------------------ chemins et place

    private void chemins() {
        chemin(-1, -14, 1, -9);     // place → Grande Cabane
        chemin(-1, 9, 1, 30);       // place → arche, puis le village
        chemin(-16, -1, -9, 1);     // place → boutique
        chemin(9, -1, 16, 1);       // place → contrats
        chemin(-24, 2, -16, 4);     // → tour
        chemin(-24, 4, -22, 8);
        chemin(16, -10, 18, -1);    // → potager
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

    /** Place pavée ronde avec une fontaine au milieu. */
    private void place() {
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 9.3) {
                    continue;
                }
                float r = rnd.nextFloat();
                BlockState s = d > 8.4 ? Blocks.STONE_BRICKS.defaultBlockState()
                        : (r < 0.35F ? Blocks.POLISHED_ANDESITE : r < 0.6F ? Blocks.ANDESITE : r < 0.8F ? Blocks.STONE_BRICKS : Blocks.COBBLESTONE).defaultBlockState();
                pose(dx, 0, dz, s);
                pose(dx, 1, dz, AIR);
                pose(dx, 2, dz, AIR);
            }
        }
        // fontaine
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean bord = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                boolean coin = Math.abs(dx) == 3 && Math.abs(dz) == 3;
                pose(dx, -1, dz, Blocks.STONE_BRICKS.defaultBlockState());
                if (coin) {
                    pose(dx, 0, dz, Blocks.STONE_BRICKS.defaultBlockState());
                    pose(dx, 1, dz, Blocks.STONE_BRICK_WALL.defaultBlockState());
                    pose(dx, 2, dz, Blocks.LANTERN.defaultBlockState());
                } else if (bord) {
                    pose(dx, 0, dz, Blocks.STONE_BRICKS.defaultBlockState());
                    pose(dx, 1, dz, Blocks.STONE_BRICK_SLAB.defaultBlockState());
                } else {
                    pose(dx, 0, dz, Blocks.WATER.defaultBlockState());
                }
            }
        }
        for (int y = 0; y <= 3; y++) {
            pose(0, y, 0, y == 3 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICK_WALL.defaultBlockState());
        }
        pose(0, 4, 0, Blocks.WATER.defaultBlockState());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            pose(d.getStepX(), 3, d.getStepZ(), Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        }
        lampadaire(-6, -6);
        lampadaire(6, -6);
        lampadaire(-6, 6);
        lampadaire(6, 6);
    }

    // ------------------------------------------------------------------ la Grande Cabane (deux étages)

    private static final int X0 = -10, X1 = 10, Z0 = -27, Z1 = -15, ZC = -21;

    private void grandeCabane() {
        for (int x = X0 - 1; x <= X1 + 2; x++) {
            for (int z = Z0 - 1; z <= Z1 + 3; z++) {
                for (int y = 1; y <= 16; y++) {
                    pose(x, y, z, AIR);
                }
            }
        }
        // fondations, plancher, étage
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                boolean bord = x == X0 || x == X1 || z == Z0 || z == Z1;
                pose(x, -1, z, Blocks.COBBLESTONE.defaultBlockState());
                pose(x, 0, z, bord ? briques() : plancher());
                if (!bord) {
                    pose(x, 5, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
                }
            }
        }
        for (int y = 1; y <= 9; y++) {
            for (int x = X0; x <= X1; x++) {
                murCabane(x, y, Z0);
                murCabane(x, y, Z1);
            }
            for (int z = Z0 + 1; z < Z1; z++) {
                murCabane(X0, y, z);
                murCabane(X1, y, z);
            }
        }
        toitCabane();
        porcheEtBalcon();
        escalier();
        rezDeChaussee();
        etage();
        chemineeCabane();
        lierre();
    }

    private void murCabane(int x, int y, int z) {
        boolean longMur = z == Z0 || z == Z1;
        boolean coin = (x == X0 || x == X1) && longMur;
        if (coin || (longMur && Math.abs(x) % 5 == 0) || (!longMur && (z == ZC || z == Z0 + 3 || z == Z1 - 3) && y != 5)) {
            pose(x, y, z, pilier(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
            return;
        }
        if (y == 5 || y == 9) {
            pose(x, y, z, pilier(Blocks.STRIPPED_DARK_OAK_LOG, longMur ? Direction.Axis.X : Direction.Axis.Z));
            return;
        }
        // grande porte d'entrée (3 de large) et porte du balcon
        if (z == Z1 && Math.abs(x) <= 1 && y <= 3) {
            pose(x, y, z, AIR);
            return;
        }
        if (z == Z1 && x == 0 && (y == 6 || y == 7)) {
            pose(x, y, z, AIR);
            return;
        }
        boolean fenetreBas = (y == 2 || y == 3);
        boolean fenetreHaut = (y == 7 || y == 8);
        boolean colonne = longMur ? (Math.abs(x) == 3 || Math.abs(x) == 7) : (z == Z0 + 6 || z == Z1 - 6 + 1 || z == ZC - 2 || z == ZC + 2);
        boolean murDesReliques = z == Z0 && Math.abs(x) <= 4 && y <= 4;
        if ((fenetreBas || fenetreHaut) && colonne && !murDesReliques && !(x == X1 && z == ZC)) {
            pose(x, y, z, Blocks.GLASS_PANE.defaultBlockState());
            return;
        }
        pose(x, y, z, y >= 6 ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                : rnd.nextFloat() < 0.08F ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState());
    }

    private int hauteurToit(int z) {
        return 9 + (7 - Math.abs(z - ZC));
    }

    private void toitCabane() {
        for (int z = Z0 - 1; z <= Z1 + 1; z++) {
            int dz = z - ZC;
            int h = hauteurToit(z);
            for (int x = X0 - 1; x <= X1 + 1; x++) {
                if (dz == 0) {
                    pose(x, h, z, Blocks.DARK_OAK_PLANKS.defaultBlockState());
                    pose(x, h + 1, z, Blocks.DARK_OAK_SLAB.defaultBlockState());
                } else {
                    pose(x, h, z, escalier(Blocks.DARK_OAK_STAIRS, dz < 0 ? Direction.SOUTH : Direction.NORTH));
                    if (Math.abs(dz) < 7 && x > X0 - 1 && x < X1 + 1) {
                        pose(x, h - 1, z, escalier(Blocks.DARK_OAK_STAIRS, dz < 0 ? Direction.NORTH : Direction.SOUTH, Half.TOP));
                    }
                }
            }
        }
        for (int z = Z0 + 1; z < Z1; z++) {
            for (int y = 10; y < hauteurToit(z); y++) {
                boolean rosace = Math.abs(z - ZC) <= 1 && (y == 12 || y == 13);
                BlockState s = rosace ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState();
                pose(X0, y, z, s);
                pose(X1, y, z, s);
            }
        }
    }

    private void porcheEtBalcon() {
        for (int x = -7; x <= 7; x++) {
            for (int z = Z1 + 1; z <= Z1 + 3; z++) {
                pose(x, 0, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
                pose(x, 5, z, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
                boolean bordBalcon = z == Z1 + 3 || Math.abs(x) == 7;
                pose(x, 6, z, bordBalcon ? Blocks.SPRUCE_FENCE.defaultBlockState() : AIR);
            }
        }
        for (int x : new int[] {-7, -3, 3, 7}) {
            for (int y = 1; y <= 4; y++) {
                pose(x, y, Z1 + 3, pilier(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
            }
            pose(x, 6, Z1 + 3, Blocks.LANTERN.defaultBlockState());
        }
        for (int x : new int[] {-5, 5}) {
            pose(x, 4, Z1 + 2, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        pose(-6, 1, Z1 + 1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        pose(6, 1, Z1 + 1, Blocks.COMPOSTER.defaultBlockState());
        pose(5, 1, Z1 + 1, Blocks.CHIPPED_ANVIL.defaultBlockState());
        pose(-5, 1, Z1 + 1, Blocks.FLOWER_POT.defaultBlockState());
    }

    private void escalier() {
        // escalier le long du mur ouest, du rez-de-chaussée à l'étage
        for (int i = 0; i < 4; i++) {
            int z = Z1 - 2 - i;
            pose(X0 + 1, i + 1, z, escalier(Blocks.SPRUCE_STAIRS, Direction.NORTH));
            for (int y = 1; y <= i; y++) {
                pose(X0 + 1, y, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
            pose(X0 + 1, 5, z, AIR);
            pose(X0 + 2, 6, z, Blocks.SPRUCE_FENCE.defaultBlockState());
        }
        pose(X0 + 1, 5, Z1 - 6, Blocks.SPRUCE_PLANKS.defaultBlockState());
    }

    private void rezDeChaussee() {
        // comptoir et mur des reliques
        for (int x = -3; x <= 3; x++) {
            pose(x, 1, Z0 + 1, Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        }
        // longues tables et bancs
        for (int x = -5; x <= 5; x++) {
            if (x == 0) {
                continue;
            }
            for (int z : new int[] {ZC - 1, ZC + 2}) {
                pose(x, 1, z, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
                pose(x, 1, z - 1, escalier(Blocks.SPRUCE_STAIRS, Direction.NORTH));
                pose(x, 1, z + 1, escalier(Blocks.SPRUCE_STAIRS, Direction.SOUTH));
            }
        }
        // bibliothèque et pupitre du Gardien
        for (int z = Z0 + 1; z <= Z0 + 4; z++) {
            pose(X0 + 1, 1, z, Blocks.BOOKSHELF.defaultBlockState());
            pose(X0 + 1, 2, z, Blocks.BOOKSHELF.defaultBlockState());
            pose(X0 + 1, 3, z, Blocks.BOOKSHELF.defaultBlockState());
        }
        pose(X0 + 3, 1, Z0 + 2, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.EAST));
        // coin cuisine et rangement
        pose(X1 - 1, 1, Z0 + 1, Blocks.CRAFTING_TABLE.defaultBlockState());
        pose(X1 - 2, 1, Z0 + 1, Blocks.SMOKER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
        pose(X1 - 3, 1, Z0 + 1, Blocks.FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
        pose(X1 - 1, 1, Z1 - 1, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        pose(X1 - 1, 1, Z1 - 2, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        pose(X1 - 1, 1, Z1 - 3, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        // lustres
        for (int x : new int[] {-5, 5}) {
            pose(x, 4, ZC, Blocks.CHAIN.defaultBlockState());
            pose(x, 3, ZC, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        pose(0, 4, ZC - 4, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        tapis(Blocks.RED_CARPET.defaultBlockState(), -1, 1, Z0 + 3, Z1 - 1);
        coffre(X1 - 1, 1, Z1 - 1, "coffres/cabane");
        coffre(X1 - 1, 1, Z1 - 2, "coffres/cabane");
        journal(X0 + 3, 1, Z0 + 2);
        for (int x = -3; x <= 3; x++) {
            cadre(x, 2, Z0 + 1, Direction.SOUTH, ItemStack.EMPTY);
        }
        texte(0.5, 4.2, Z0 + 1.05, 180F, "{\"text\":\"Les Sept Reliques\",\"color\":\"gold\",\"bold\":true}", 0.9F, 0x00000000, false);
    }

    private void etage() {
        // dortoir : 8 lits
        for (int x : new int[] {-7, -4, 4, 7}) {
            pose(x, 6, Z0 + 2, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT));
            pose(x, 6, Z0 + 1, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD));
            pose(x + (x < 0 ? 1 : -1), 6, Z0 + 1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        }
        for (int x : new int[] {-4, 4, 7}) {
            pose(x, 6, Z1 - 2, Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT));
            pose(x, 6, Z1 - 1, Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD));
        }
        tapis(Blocks.BROWN_CARPET.defaultBlockState(), -2, 2, Z0 + 4, Z1 - 3);
        for (int x : new int[] {-5, 0, 5}) {
            pose(x, 9, ZC, Blocks.CHAIN.defaultBlockState());
            pose(x, 8, ZC, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
    }

    private void chemineeCabane() {
        for (int y = -1; y <= 17; y++) {
            for (int z = ZC - 1; z <= ZC + 1; z++) {
                boolean foyer = z == ZC && (y == 1 || y == 2);
                pose(X1 + 1, y, z, foyer ? AIR : briques());
            }
            pose(X1 + 2, y, ZC, briques());
        }
        pose(X1, 1, ZC, AIR);
        pose(X1, 2, ZC, AIR);
        pose(X1 + 1, 1, ZC, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.FACING, Direction.WEST));
        pose(X1 + 1, 18, ZC, Blocks.CAMPFIRE.defaultBlockState());
    }

    private void tapis(BlockState tapis, int xa, int xb, int za, int zb) {
        int y = tapis.is(Blocks.BROWN_CARPET) ? 6 : 1;
        for (int x = xa; x <= xb; x++) {
            for (int z = za; z <= zb; z++) {
                if (level.getBlockState(c.offset(x, y, z)).isAir()) {
                    pose(x, y, z, tapis);
                }
            }
        }
    }

    private void lierre() {
        for (int y = 1; y <= 8; y++) {
            for (int x = X0; x <= X1; x++) {
                vigne(x, y, Z0 - 1, Direction.SOUTH);
            }
            for (int z = Z0; z <= Z1; z++) {
                vigne(X0 - 1, y, z, Direction.EAST);
            }
        }
    }

    private void vigne(int x, int y, int z, Direction versLeMur) {
        if (rnd.nextFloat() < 0.15F && level.getBlockState(c.offset(x, y, z)).isAir()
                && level.getBlockState(c.offset(x, y, z).relative(versLeMur)).isSolid()) {
            pose(x, y, z, Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(versLeMur), true));
        }
    }

    // ------------------------------------------------------------------ le marché

    private void marche() {
        echoppe(-14, Blocks.RED_WOOL, Blocks.WHITE_WOOL);
        echoppe(14, Blocks.BLUE_WOOL, Blocks.WHITE_WOOL);
        echoppe(-14, 8, Blocks.YELLOW_WOOL, Blocks.ORANGE_WOOL);
        echoppe(14, 8, Blocks.GREEN_WOOL, Blocks.LIME_WOOL);
        pose(-12, 1, 0, fr.cabane.CabaneBlocks.COMPTOIR.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
        pose(12, 1, 0, fr.cabane.CabaneBlocks.TABLEAU_CONTRATS.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
        pose(-12, 1, 1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        pose(-12, 1, -1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        pose(12, 1, 1, Blocks.HAY_BLOCK.defaultBlockState());
        pose(12, 1, -1, Blocks.COMPOSTER.defaultBlockState());
    }

    private void echoppe(int cx, Block toile1, Block toile2) {
        echoppe(cx, 0, toile1, toile2);
    }

    /** Une échoppe 5x5 : poteaux, comptoir, toile rayée. */
    private void echoppe(int cx, int cz, Block toile1, Block toile2) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                pose(cx + dx, 0, cz + dz, Blocks.SPRUCE_PLANKS.defaultBlockState());
                for (int y = 1; y <= 3; y++) {
                    pose(cx + dx, y, cz + dz, AIR);
                }
                pose(cx + dx, 4, cz + dz, (Math.floorMod(dx, 2) == 0 ? toile1 : toile2).defaultBlockState());
            }
        }
        for (int dx : new int[] {-2, 2}) {
            for (int dz : new int[] {-2, 2}) {
                for (int y = 1; y <= 3; y++) {
                    pose(cx + dx, y, cz + dz, Blocks.SPRUCE_FENCE.defaultBlockState());
                }
            }
        }
        pose(cx, 3, cz, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    // ------------------------------------------------------------------ tableau des quêtes avec la carte de la région

    private void tableauDesQuetes() {
        int z = -11;
        for (int x = -10; x <= -4; x++) {
            for (int y = 1; y <= 5; y++) {
                boolean bord = x == -10 || x == -4 || y == 5;
                pose(x, y, z, bord ? pilier(Blocks.STRIPPED_DARK_OAK_LOG, y == 5 ? Direction.Axis.X : Direction.Axis.Y) : Blocks.DARK_OAK_PLANKS.defaultBlockState());
            }
            pose(x, 6, z, Blocks.DARK_OAK_SLAB.defaultBlockState());
        }
        // 3x3 cartes de la région, la cabane marquée d'une croix
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                ItemStack carte = MapItem.create(level, c.getX() + i * 512, c.getZ() + j * 512, (byte) 2, false, false);
                dessinerCarte(carte);
                MapItemSavedData.addTargetDecoration(carte, c, "cabane", MapDecorationTypes.RED_X);
                cadre(-7 + i, 3 - j, z + 1, Direction.SOUTH, carte);
            }
        }
    }

    /**
     * Colorie une carte d'après les biomes, un échantillon pour 2x2 pixels : bien plus rapide
     * que le rendu du jeu (qui échantillonne chaque pixel plusieurs fois).
     */
    private void dessinerCarte(ItemStack carte) {
        MapItemSavedData data = MapItem.getSavedData(carte, level);
        if (data == null) {
            return;
        }
        int echelle = 1 << data.scale;
        int y = level.getSeaLevel() + 8;
        for (int px = 0; px < 128; px += 2) {
            for (int pz = 0; pz < 128; pz += 2) {
                int x = data.centerX + (px - 64) * echelle;
                int z = data.centerZ + (pz - 64) * echelle;
                Holder<Biome> biome = level.getUncachedNoiseBiome(x >> 2, y >> 2, z >> 2);
                net.minecraft.world.level.material.MapColor couleur = couleurBiome(biome);
                int bruit = Math.floorMod(x * 734287 ^ z * 912931, 7);
                var luminosite = bruit == 0 ? net.minecraft.world.level.material.MapColor.Brightness.LOW
                        : bruit == 1 ? net.minecraft.world.level.material.MapColor.Brightness.HIGH
                        : net.minecraft.world.level.material.MapColor.Brightness.NORMAL;
                byte octet = couleur.getPackedId(luminosite);
                for (int a = 0; a < 2; a++) {
                    for (int b = 0; b < 2; b++) {
                        data.setColor(px + a, pz + b, octet);
                    }
                }
            }
        }
    }

    private static net.minecraft.world.level.material.MapColor couleurBiome(Holder<Biome> b) {
        if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_RIVER)) {
            return net.minecraft.world.level.material.MapColor.WATER;
        }
        if (b.is(BiomeTags.IS_BEACH) || b.is(Tags.Biomes.IS_DESERT)) {
            return net.minecraft.world.level.material.MapColor.SAND;
        }
        if (b.is(BiomeTags.IS_SAVANNA) || b.is(Tags.Biomes.IS_SAVANNA)) {
            return net.minecraft.world.level.material.MapColor.TERRACOTTA_YELLOW;
        }
        if (b.is(BiomeTags.IS_BADLANDS)) {
            return net.minecraft.world.level.material.MapColor.TERRACOTTA_ORANGE;
        }
        if (b.is(Tags.Biomes.IS_SNOWY)) {
            return net.minecraft.world.level.material.MapColor.SNOW;
        }
        if (b.is(BiomeTags.IS_MOUNTAIN)) {
            return net.minecraft.world.level.material.MapColor.STONE;
        }
        if (b.is(Tags.Biomes.IS_SWAMP)) {
            return net.minecraft.world.level.material.MapColor.TERRACOTTA_GREEN;
        }
        if (b.is(Tags.Biomes.IS_MUSHROOM)) {
            return net.minecraft.world.level.material.MapColor.COLOR_PURPLE;
        }
        if (b.is(BiomeTags.IS_JUNGLE) || b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA)) {
            return net.minecraft.world.level.material.MapColor.PLANT;
        }
        return net.minecraft.world.level.material.MapColor.GRASS;
    }

    // ------------------------------------------------------------------ autour

    private void feuDeCamp() {
        int cz = 15;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                pose(dx + 5, 0, cz + dz, (dx == 0 && dz == 0 ? Blocks.COBBLESTONE : Blocks.GRAVEL).defaultBlockState());
                pose(dx + 5, 1, cz + dz, AIR);
            }
        }
        pose(5, 1, cz, Blocks.CAMPFIRE.defaultBlockState());
        for (int dz = -1; dz <= 1; dz++) {
            pose(8, 1, cz + dz, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.Z));
        }
        for (int dx = 4; dx <= 6; dx++) {
            pose(dx, 1, cz + 3, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.X));
        }
        lampadaire(2, 12);
        lampadaire(-2, 20);
        lampadaire(2, 26);
    }

    private void lampadaire(int x, int z) {
        pose(x, 0, z, Blocks.COBBLESTONE.defaultBlockState());
        pose(x, 1, z, Blocks.SPRUCE_FENCE.defaultBlockState());
        pose(x, 2, z, Blocks.SPRUCE_FENCE.defaultBlockState());
        pose(x, 3, z, Blocks.LANTERN.defaultBlockState());
    }

    private void tour() {
        final int tx = -24, tz = 10;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean bord = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                pose(tx + dx, 0, tz + dz, briques());
                pose(tx + dx, -1, tz + dz, Blocks.COBBLESTONE.defaultBlockState());
                for (int y = 1; y <= 11; y++) {
                    boolean effondre = dz == 2 && y >= 9 && rnd.nextFloat() < 0.55F;
                    pose(tx + dx, y, tz + dz, bord && !effondre ? briques() : AIR);
                }
                pose(tx + dx, 12, tz + dz, bord ? briques() : Blocks.SPRUCE_PLANKS.defaultBlockState());
                boolean creneau = bord && (dx + dz) % 2 == 0 && rnd.nextFloat() < 0.75F;
                pose(tx + dx, 13, tz + dz, creneau ? Blocks.STONE_BRICK_WALL.defaultBlockState() : AIR);
            }
        }
        pose(tx + 2, 1, tz - 4 + 4, AIR);
        pose(tx + 2, 1, tz, AIR);
        pose(tx + 2, 2, tz, AIR);
        for (int y = 1; y <= 12; y++) {
            pose(tx - 1, y, tz, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST));
        }
        pose(tx + 1, 12, tz + 1, Blocks.HAY_BLOCK.defaultBlockState());
        pose(tx + 1, 13, tz + 1, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, true));
        pose(tx + 1, 13, tz - 1, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        coffre(tx + 1, 13, tz - 1, "coffres/tour");
        for (int i = 0; i < 10; i++) {
            int x = tx + rnd.nextInt(9) - 4;
            int z = tz + 3 + rnd.nextInt(3);
            if (level.getBlockState(c.offset(x, 1, z)).canBeReplaced()) {
                pose(x, 1, z, (rnd.nextBoolean() ? Blocks.COBBLESTONE : Blocks.MOSSY_COBBLESTONE).defaultBlockState());
            }
        }
    }

    private void potager() {
        for (int x = 14; x <= 22; x++) {
            for (int z = -24; z <= -12; z++) {
                boolean bord = x == 14 || x == 22 || z == -24 || z == -12;
                if (bord) {
                    pose(x, 1, z, rnd.nextFloat() < 0.75F ? Blocks.OAK_FENCE.defaultBlockState() : AIR);
                    continue;
                }
                if (x == 18 && (z == -21 || z == -15)) {
                    pose(x, 0, z, Blocks.WATER.defaultBlockState());
                    pose(x, 1, z, AIR);
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
        pose(18, 1, -12, AIR);
    }

    private void arche() {
        int z = 28;
        for (int x : new int[] {-3, 3}) {
            for (int y = 1; y <= 5; y++) {
                pose(x, y, z, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.Y));
            }
            pose(x, 0, z, briques());
        }
        for (int x = -4; x <= 4; x++) {
            pose(x, 6, z, pilier(Blocks.ACACIA_LOG, Direction.Axis.X));
        }
        pose(-2, 5, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        pose(2, 5, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    private void cloture() {
        for (int dx = -RAYON; dx <= RAYON; dx++) {
            for (int dz = -RAYON; dz <= RAYON; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (Math.abs(d - 28) > 0.5 || (Math.abs(dx) <= 4 && dz > 0)) {
                    continue;
                }
                double angle = Math.atan2(dz, dx);
                if (Math.sin(angle * 7) > -0.35 && rnd.nextFloat() < 0.85F && level.getBlockState(c.offset(dx, 1, dz)).canBeReplaced()) {
                    pose(dx, 1, dz, Blocks.OAK_FENCE.defaultBlockState());
                }
            }
        }
    }

    /** Un chemin de terre battue qui suit le relief jusqu'au village. */
    private void cheminVersLeVillage(BlockPos village) {
        double dx = village.getX() - c.getX();
        double dz = village.getZ() - c.getZ();
        double longueur = Math.sqrt(dx * dx + dz * dz);
        if (longueur < 1) {
            return;
        }
        double ux = dx / longueur;
        double uz = dz / longueur;
        for (double t = RAYON - 4; t < longueur - 20; t += 0.7) {
            for (int largeur = -1; largeur <= 1; largeur++) {
                int x = (int) Math.round(c.getX() + ux * t - uz * largeur);
                int z = (int) Math.round(c.getZ() + uz * t + ux * largeur);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos p = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(p);
                if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL)) {
                    level.setBlock(p, Blocks.DIRT_PATH.defaultBlockState(), FLAGS);
                    if (level.getBlockState(p.above()).canBeReplaced()) {
                        level.setBlock(p.above(), AIR, FLAGS);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ l'avion en feu

    /** Position (relative) où apparaissent les nouveaux joueurs, dans la carlingue. */
    public static final BlockPos DANS_AVION = new BlockPos(4, HAUTEUR_AVION + 1, 0);
    /** Le coffre aux parachutes. */
    public static final BlockPos COFFRE_AVION = new BlockPos(-9, HAUTEUR_AVION + 1, 1);

    private void avion() {
        int a = HAUTEUR_AVION;
        BlockState blanc = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState bleu = Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState();
        BlockState gris = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState noir = Blocks.BLACK_CONCRETE.defaultBlockState();
        // carlingue : section 5x5 creuse, de x = -12 à x = 12
        for (int x = -12; x <= 12; x++) {
            for (int y = 0; y <= 4; y++) {
                for (int z = -2; z <= 2; z++) {
                    boolean coin = (y == 0 || y == 4) && Math.abs(z) == 2;
                    boolean paroi = y == 0 || y == 4 || Math.abs(z) == 2;
                    if (coin) {
                        continue;
                    }
                    BlockState s;
                    if (!paroi) {
                        s = AIR;
                    } else if (Math.abs(z) == 2 && y == 2 && x % 3 == 0 && Math.abs(x) < 11) {
                        s = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
                    } else if (Math.abs(z) == 2 && y == 1) {
                        s = bleu;
                    } else if (y == 0) {
                        s = gris;
                    } else {
                        s = rnd.nextFloat() < 0.07F ? noir : blanc;
                    }
                    pose(x, a + y, z, s);
                }
            }
        }
        // nez et cockpit
        for (int y = 1; y <= 3; y++) {
            for (int z = -1; z <= 1; z++) {
                pose(13, a + y, z, y == 3 ? Blocks.BLACK_STAINED_GLASS.defaultBlockState() : blanc);
            }
        }
        pose(14, a + 2, 0, gris);
        // queue et dérive
        for (int x = -15; x <= -13; x++) {
            int r = -12 - x;
            for (int y = 1 + r / 2; y <= 3 + r / 2; y++) {
                pose(x, a + y, 0, blanc);
            }
            for (int z = -4 + r; z <= 4 - r; z++) {
                pose(x, a + 2 + r / 2, z, blanc);
            }
            for (int y = 4; y <= 9 - r; y++) {
                pose(x, a + y, 0, Blocks.RED_CONCRETE.defaultBlockState());
            }
        }
        // ailes et réacteurs
        for (int x = -3; x <= 3; x++) {
            for (int z = 3; z <= 12; z++) {
                int recul = (z - 3) / 3;
                if (x - recul < -3 || x + recul > 4) {
                    continue;
                }
                pose(x - recul, a + 1, z, (rnd.nextFloat() < 0.15F ? noir : blanc));
                pose(x - recul, a + 1, -z, (rnd.nextFloat() < 0.15F ? noir : blanc));
            }
        }
        for (int z : new int[] {-6, 6}) {
            for (int x = -2; x <= 2; x++) {
                pose(x, a, z, Blocks.GRAY_CONCRETE.defaultBlockState());
            }
            pose(3, a, z, Blocks.BLACK_CONCRETE.defaultBlockState());
        }
        // l'aile droite brûle : netherrack en feu et colonnes de fumée
        for (int[] f : new int[][] {{0, 7}, {1, 8}, {-1, 9}, {0, 10}, {2, 6}, {-2, 8}}) {
            pose(f[0], a + 1, f[1], Blocks.NETHERRACK.defaultBlockState());
            pose(f[0], a + 2, f[1], Blocks.FIRE.defaultBlockState());
        }
        pose(0, a, 6, Blocks.HAY_BLOCK.defaultBlockState());
        pose(0, a + 1, 6, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, true));
        pose(-1, a + 1, 11, Blocks.MAGMA_BLOCK.defaultBlockState());
        // intérieur : sièges, coffre aux parachutes, porte ouverte côté nord
        for (int x = -6; x <= 8; x += 2) {
            pose(x, a + 1, -1, escalier(Blocks.QUARTZ_STAIRS, Direction.EAST));
            pose(x, a + 1, 1, escalier(Blocks.QUARTZ_STAIRS, Direction.EAST));
        }
        pose(COFFRE_AVION.getX(), COFFRE_AVION.getY(), COFFRE_AVION.getZ(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST));
        pose(-9, a + 1, -1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        for (int x = 0; x <= 1; x++) {
            pose(x, a + 1, -2, AIR);
            pose(x, a + 2, -2, AIR);
        }
        for (int x = -8; x <= 10; x += 6) {
            pose(x, a + 3, 0, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true));
        }
    }

    // ------------------------------------------------------------------ textes flottants

    private void textes() {
        texte(0.5, 11.5, Z1 + 3.6, 180F, "{\"text\":\"LA GRANDE CABANE\",\"color\":\"gold\",\"bold\":true}", 2.2F, 0x55000000, false);
        texte(-6.5, 7.0, -11 + 0.6, 180F, "{\"text\":\"TABLEAU DES QUÊTES\",\"color\":\"yellow\",\"bold\":true}", 1.2F, 0x99000000, false);
        texte(-6.5, 0.6, -11 + 0.9, 180F, "[{\"text\":\"Ton livre de quêtes est dans ton inventaire.\\n\",\"color\":\"white\"},{\"text\":\"Le village t'héberge si tu l'aides !\",\"color\":\"gray\"}]", 0.6F, 0x99000000, false);
        texte(-12.5, 5.3, 0.5, -90F, "{\"text\":\"BOUTIQUE\",\"color\":\"gold\",\"bold\":true}", 1.4F, 0x99000000, true);
        texte(12.5, 5.3, 0.5, 90F, "[{\"text\":\"CONTRATS\\n\",\"color\":\"aqua\",\"bold\":true},{\"text\":\"nouveaux toutes les heures\",\"color\":\"gray\",\"bold\":false}]", 1.1F, 0x99000000, true);
        texte(-13.5, 5.3, 8.5, 0F, "[{\"text\":\"EMPLACEMENT LIBRE\\n\",\"color\":\"white\",\"bold\":true},{\"text\":\"Achète un étal à la boutique\",\"color\":\"gray\",\"bold\":false}]", 0.8F, 0x99000000, true);
        texte(14.5, 5.3, 8.5, 0F, "[{\"text\":\"EMPLACEMENT LIBRE\\n\",\"color\":\"white\",\"bold\":true},{\"text\":\"Achète un étal à la boutique\",\"color\":\"gray\",\"bold\":false}]", 0.8F, 0x99000000, true);
        texte(0.5, 7.4, 28.5, 0F, "{\"text\":\"LA CABANE\",\"color\":\"gold\",\"bold\":true}", 1.8F, 0x00000000, false);
        texte(0.5, 7.4, 28.5, 180F, "{\"text\":\"LA CABANE\",\"color\":\"gold\",\"bold\":true}", 1.8F, 0x00000000, false);
        texte(-1.5, HAUTEUR_AVION + 2.2, 0.5, 90F, "[{\"text\":\"MAYDAY ! L'AVION EST EN FEU !\\n\",\"color\":\"red\",\"bold\":true},{\"text\":\"Prends un parachute dans le coffre au fond\\npuis saute par la porte !\",\"color\":\"white\",\"bold\":false}]", 0.5F, 0xAA000000, false);
    }

    /** Un texte flottant (text display). {@code tourne} : il fait face au joueur. */
    private void texte(double dx, double dy, double dz, float yaw, String json, float echelle, int fond, boolean tourne) {
        if (json.isEmpty()) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:text_display");
        tag.putString("text", json);
        tag.putString("billboard", tourne ? "center" : "fixed");
        tag.putString("alignment", "center");
        tag.putInt("background", fond);
        tag.putInt("line_width", 300);
        tag.putBoolean("shadow", true);
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("translation", floats(0, 0, 0));
        t.put("scale", floats(echelle, echelle, echelle));
        tag.put("transformation", t);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tag.put("Tags", tags);
        Entity e = EntityType.loadEntityRecursive(tag, level, ent -> {
            ent.moveTo(c.getX() + dx, c.getY() + dy, c.getZ() + dz, yaw, 0F);
            return ent;
        });
        if (e != null) {
            level.addFreshEntity(e);
        }
    }

    private static ListTag floats(float... v) {
        ListTag l = new ListTag();
        for (float f : v) {
            l.add(FloatTag.valueOf(f));
        }
        return l;
    }

    private void cadre(int x, int y, int z, Direction face, ItemStack contenu) {
        ItemFrame cadre = new ItemFrame(level, c.offset(x, y, z), face);
        if (!contenu.isEmpty()) {
            cadre.setItem(contenu, false);
            CompoundTag t = new CompoundTag();
            cadre.saveWithoutId(t);
            t.putBoolean("Fixed", true);
            cadre.load(t);
        }
        cadre.addTag(TAG);
        level.addFreshEntity(cadre);
    }

    // ------------------------------------------------------------------ finitions et outils

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

    private void journal(int x, int y, int z) {
        BlockPos p = c.offset(x, y, z);
        List<Filterable<Component>> pages = List.of(
                "Journal du Gardien\n\nMayday… votre avion s'est écrasé au-dessus de la savane. Le village voisin accepte de vous héberger dans cette Grande Cabane.",
                "En échange, aidez-le : les contrats du marché, les quêtes du tableau. Le village paie en SakaiDollars.",
                "Autrefois, la Confrérie des Explorateurs vivait ici. Sept reliques reposaient sur ce comptoir, puis ont été cachées au cœur de donjons lointains.",
                "Le Scarabée d'Or, sous le sable.\nL'Idole de Jade, dans la jungle.\nLa Perle des Abysses, sous la mer.\nLa Plume Céleste, dans le ciel.",
                "La Couronne Illageoise, chez nos ennemis.\nL'Œil du Gardien, sous la terre.\nL'Engrenage Ancien, dans les fonderies.\n\nRamenez-les.\n— Le Vieux Gardien")
                .stream().map(t -> Filterable.passThrough((Component) Component.literal(t))).toList();
        ItemStack livre = new ItemStack(Items.WRITTEN_BOOK);
        livre.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough("Journal du Gardien"), "Le Vieux Gardien", 0, pages, true));
        LecternBlock.tryPlaceBook(null, level, p, level.getBlockState(p), livre);
    }

    private void pose(int dx, int dy, int dz, BlockState state) {
        BlockPos p = c.offset(dx, dy, dz);
        level.setBlock(p, state, FLAGS);
        poses.add(p);
    }

    private void abs(int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, FLAGS);
    }

    private BlockState briques() {
        float r = rnd.nextFloat();
        return (r < 0.25F ? Blocks.MOSSY_STONE_BRICKS : r < 0.45F ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
    }

    private BlockState plancher() {
        float r = rnd.nextFloat();
        return (r < 0.12F ? Blocks.DARK_OAK_PLANKS : Blocks.SPRUCE_PLANKS).defaultBlockState();
    }

    private static BlockState pilier(Block block, Direction.Axis axis) {
        return block.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
    }

    private static BlockState escalier(Block block, Direction facing) {
        return block.defaultBlockState().setValue(StairBlock.FACING, facing);
    }

    private static BlockState escalier(Block block, Direction facing, Half moitie) {
        return escalier(block, facing).setValue(StairBlock.HALF, moitie);
    }
}
