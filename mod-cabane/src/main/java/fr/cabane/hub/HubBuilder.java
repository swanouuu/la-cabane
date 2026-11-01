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
 * loin des structures hostiles, et on y construit le nôtre, en grand : au centre une place avec le
 * grand panneau de la carte du monde ; au nord la Grande Cabane (notre base, trois niveaux et deux
 * tours) ; à l'ouest le Grand Bazar de Mouloud ; à l'est la rue du village, ses maisons et la forge ;
 * au sud les champs, le feu de camp, la tour de guet et la grande porte. Au-dessus, l'avion en feu
 * dont on saute en parachute.
 *
 * <p>Coordonnées relatives au centre {@code c} de la place : y = 0 est le sol.
 */
public class HubBuilder {
    private static final int RAYON = 64;
    private static final int FONDU = 18;
    /** L'avion vole tout en haut du monde (la limite est 320) : environ 200 blocs de chute. */
    public static int hauteurAvion(BlockPos centre) {
        return Math.max(62, Math.min(200, 306 - centre.getY()));
    }
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
        private static final int[][] AUTOUR = {{46, 0}, {-46, 0}, {0, 46}, {0, -46}, {33, 33}, {-33, -33}, {33, -33}, {-33, 33}};

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
        // le village candidat, calculé en arrière-plan comme à la génération du monde
        private java.util.concurrent.CompletableFuture<StructureStart> futur;
        private Candidat candidatFutur;
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
                // Beauvais s'installe en pleine jungle, loin de tout village : pas de recherche de village
                candidats = List.of();
                Cabane.LOGGER.info("Recherche d'une jungle assez plate pour Beauvais…");
                return;
            }
            if (bbEnCours != null) {
                evaluerCote();
                return;
            }
            if (futur != null) {
                if (!futur.isDone()) {
                    return;
                }
                Candidat c = candidatFutur;
                StructureStart start = futur.isCompletedExceptionally() ? null : futur.join();
                futur = null;
                BlockPos vp = c.chunk().getMiddleBlockPosition(0);
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
            if (index < candidats.size()) {
                Candidat c = candidats.get(index++);
                int x = c.chunk().getMiddleBlockX();
                int z = c.chunk().getMiddleBlockZ();
                Holder<Biome> enSurface = level.getUncachedNoiseBiome(x >> 2, sol(x, z) >> 2, z >> 2);
                if (!c.structure().value().biomes().contains(enSurface)) {
                    return;
                }
                candidatFutur = c;
                // le même calcul que la génération du monde, sans charger de chunk : sur un autre fil,
                // le serveur continue de tourner pendant ce temps
                Structure structure = c.structure().value();
                var acces = level.registryAccess();
                var modeles = level.getStructureManager();
                futur = java.util.concurrent.CompletableFuture.supplyAsync(() -> structure.generate(acces, generateur,
                        generateur.getBiomeSource(), aleatoire, modeles, graine, c.chunk(), 0, level, structure.biomes()::contains),
                        net.minecraft.Util.backgroundExecutor());
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
            for (int dx = -44; dx <= 44; dx += 44) {
                for (int dz = -44; dz <= 44; dz += 44) {
                    int h = sol(p[0] + dx, p[1] + dz);
                    eau |= h <= mer || biome(p[0] + dx, p[1] + dz).is(BiomeTags.IS_RIVER) || biome(p[0] + dx, p[1] + dz).is(BiomeTags.IS_OCEAN);
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                    hauteurs.add(h);
                }
            }
            if (!eau && max - min <= 20 && max - min < meilleurEcart) {
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
                int r = anneau * 64;
                if (r > 12000) {
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
                    int t = k % (2 * anneau) * 64 - r;
                    switch (segment) {
                        case 0 -> { x = t; z = -r; }
                        case 1 -> { x = r; z = t; }
                        case 2 -> { x = -t; z = r; }
                        default -> { x = -r; z = -t; }
                    }
                }
                if (!jungleA(level, x, mer + 12, z)) {
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
                    if (h <= mer + 1 || !jungleA(level, x + d[0], mer + 12, z + d[1])) {
                        ok = false;
                        break;
                    }
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                }
                BlockPos pos = new BlockPos(x, s, z);
                if (ok && max - min <= 26 && !hostileProche(pos)) {
                    Cabane.LOGGER.info("Jungle trouvée pour Beauvais en {}", pos);
                    resultat = new Site(pos, null);
                    fini = true;
                    return;
                }
            }
        }
    }

    private static boolean jungleA(ServerLevel level, int x, int y, int z) {
        Holder<Biome> biome = level.getUncachedNoiseBiome(x >> 2, y >> 2, z >> 2);
        return biome.is(BiomeTags.IS_JUNGLE) || biome.is(Tags.Biomes.IS_JUNGLE)
                || biome.unwrapKey().map(k -> k.location().getPath().matches(".*(jungle|rainforest|tropic).*")).orElse(false);
    }

    // ================================================================== construction

    /** Une étape de la construction : le serveur en fait une par tick, pour ne jamais rester bloqué. */
    public record Etape(String nom, Runnable action) {
    }

    private static final int TRANCHES = 6;

    public List<Etape> etapes(BlockPos village) {
        List<Etape> e = new ArrayList<>();
        e.add(new Etape("chunks", this::chargerChunks));
        for (int i = 0; i < TRANCHES; i++) {
            int t = i;
            e.add(new Etape("terrain " + (i + 1) + "/" + TRANCHES, () -> preparerTerrain(t)));
        }
        e.add(new Etape("avenues", this::avenues));
        e.add(new Etape("place", this::place));
        e.add(new Etape("grand panneau", this::grandPanneau));
        e.add(new Etape("grande cabane : murs", this::cabaneMurs));
        e.add(new Etape("grande cabane : toit et tours", this::cabaneToitEtTours));
        e.add(new Etape("grande cabane : intérieur", this::cabaneInterieur));
        e.add(new Etape("grand bazar : murs", this::bazarMurs));
        e.add(new Etape("grand bazar : intérieur", this::bazarInterieur));
        e.add(new Etape("maisons", this::maisons));
        e.add(new Etape("forge, puits et champs", this::forgePuitsChamps));
        e.add(new Etape("abords", this::abords));
        e.add(new Etape("décoration", this::decorer));
        e.add(new Etape("finitions", this::finitions));
        e.add(new Etape("arbres, buissons et animaux", this::vie));
        if (village != null) {
            e.add(new Etape("chemin vers le village", () -> cheminVersLeVillage(village)));
        }
        e.add(new Etape("avion", this::avion));
        e.add(new Etape("salle d'embarquement", this::embarquement));
        e.add(new Etape("textes", this::textes));
        e.add(new Etape("lumière", this::lumiere));
        return e;
    }

    private void chargerChunks() {
        int max = RAYON + FONDU;
        for (int cx = (c.getX() - max) >> 4; cx <= (c.getX() + max) >> 4; cx++) {
            for (int cz = (c.getZ() - max) >> 4; cz <= (c.getZ() + max) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

    // ------------------------------------------------------------------ terrain

    /** Aplanit une tranche (en x) du terrain : plat dans le village, raccordé en douceur au relief autour. */
    private void preparerTerrain(int tranche) {
        int y0 = c.getY();
        int max = RAYON + FONDU;
        int largeur = (2 * max + TRANCHES) / TRANCHES;
        int debut = -max + tranche * largeur;
        int fin = Math.min(max, debut + largeur - 1);
        for (int dx = debut; dx <= fin; dx++) {
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
                int haut = Math.max(sol, cible) + (dedans ? 40 : 16);
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
                if (dedans && !terreBattue && rnd.nextFloat() < 0.10F) {
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

    // ------------------------------------------------------------------ avenues et place

    private void avenues() {
        chemin(-2, 18, 2, RAYON - 1);       // place → grande porte du sud
        chemin(-2, CZ1 + 5, 2, -18);        // place → Grande Cabane
        chemin(BX1 + 1, -2, -18, 2);        // place → Grand Bazar
        chemin(18, -2, 53, 2);              // la rue du village, jusqu'à la forge
        chemin(-36, 35, -3, 37);            // → feu de camp et tour de guet
        chemin(30, 3, 31, 40);              // → puits et champs
        for (int z = 22; z <= 58; z += 9) {
            lampadaire(-4, z);
            lampadaire(4, z);
        }
        for (int x : new int[] {20, 42, 53}) {
            lampadaire(x, -3);
            lampadaire(x, 3);
        }
        lampadaire(-25, -3);
        lampadaire(-25, 3);
        lampadaire(-3, -24);
        lampadaire(3, -24);
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

    private static final double RAYON_PLACE = 18;

    /** Grande place pavée, quatre petites fontaines ; le grand panneau de la carte trône au milieu. */
    private void place() {
        int r = (int) Math.ceil(RAYON_PLACE);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > RAYON_PLACE + 0.3) {
                    continue;
                }
                float f = rnd.nextFloat();
                BlockState s = d > RAYON_PLACE - 0.9 ? Blocks.STONE_BRICKS.defaultBlockState()
                        : (f < 0.35F ? Blocks.POLISHED_ANDESITE : f < 0.6F ? Blocks.ANDESITE : f < 0.8F ? Blocks.STONE_BRICKS : Blocks.COBBLESTONE).defaultBlockState();
                pose(dx, 0, dz, s);
                pose(dx, 1, dz, AIR);
                pose(dx, 2, dz, AIR);
            }
        }
        for (int sx : new int[] {-1, 1}) {
            for (int sz : new int[] {-1, 1}) {
                fontaine(11 * sx, 11 * sz);
                lampadaire(6 * sx, 15 * sz);
                lampadaire(15 * sx, 6 * sz);
            }
        }
    }

    private void fontaine(int fx, int fz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean bord = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean coin = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                pose(fx + dx, -1, fz + dz, Blocks.STONE_BRICKS.defaultBlockState());
                if (coin) {
                    pose(fx + dx, 0, fz + dz, Blocks.STONE_BRICKS.defaultBlockState());
                    pose(fx + dx, 1, fz + dz, Blocks.STONE_BRICK_WALL.defaultBlockState());
                    pose(fx + dx, 2, fz + dz, Blocks.LANTERN.defaultBlockState());
                } else if (bord) {
                    pose(fx + dx, 0, fz + dz, Blocks.STONE_BRICKS.defaultBlockState());
                    pose(fx + dx, 1, fz + dz, Blocks.STONE_BRICK_SLAB.defaultBlockState());
                } else {
                    pose(fx + dx, 0, fz + dz, Blocks.WATER.defaultBlockState());
                }
            }
        }
        for (int y = 0; y <= 2; y++) {
            pose(fx, y, fz, Blocks.STONE_BRICK_WALL.defaultBlockState());
        }
        pose(fx, 3, fz, Blocks.LANTERN.defaultBlockState());
    }

    private void lampadaire(int x, int z) {
        pose(x, 0, z, Blocks.COBBLESTONE.defaultBlockState());
        pose(x, 1, z, Blocks.SPRUCE_FENCE.defaultBlockState());
        pose(x, 2, z, Blocks.SPRUCE_FENCE.defaultBlockState());
        pose(x, 3, z, Blocks.LANTERN.defaultBlockState());
    }

    // ------------------------------------------------------------------ le grand panneau de la carte, au centre

    /**
     * Côté sud, face à l'arrivée : la carte du monde, 9 x 5 cartes (une carte = 1024 blocs de côté).
     * Côté nord, face à la Grande Cabane : les environs, 7 x 4 cartes (256 blocs chacune).
     */
    private void grandPanneau() {
        for (int x = -8; x <= 8; x++) {
            for (int z = -4; z <= 4; z++) {
                boolean marche = Math.abs(x) == 8 || Math.abs(z) == 4;
                pose(x, 0, z, Blocks.POLISHED_ANDESITE.defaultBlockState());
                pose(x, 1, z, marche ? Blocks.POLISHED_ANDESITE_SLAB.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState());
            }
        }
        for (int y = 2; y <= 9; y++) {
            for (int z = -1; z <= 1; z++) {
                pose(-6, y, z, y == 9 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
                pose(6, y, z, y == 9 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
            }
            for (int x = -5; x <= 5; x++) {
                BlockState s;
                if (Math.abs(x) == 5) {
                    s = pilier(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y);
                } else if (y == 2 || y == 8) {
                    s = pilier(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.X);
                } else {
                    s = Blocks.DARK_OAK_PLANKS.defaultBlockState();
                }
                pose(x, y, 0, s);
            }
        }
        for (int x = -6; x <= 6; x++) {
            pose(x, 10, 0, Blocks.DARK_OAK_PLANKS.defaultBlockState());
            pose(x, 11, 0, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        }
        // toit à deux pans, posé sur quatre poteaux
        for (int x = -7; x <= 7; x++) {
            for (int z = -3; z <= 3; z++) {
                int y = 12 - Math.abs(z);
                if (z == 0) {
                    pose(x, y, z, Blocks.DARK_OAK_PLANKS.defaultBlockState());
                    pose(x, y + 1, z, x == 0 ? Blocks.LIGHTNING_ROD.defaultBlockState() : Blocks.DARK_OAK_SLAB.defaultBlockState());
                } else {
                    pose(x, y, z, escalier(Blocks.DARK_OAK_STAIRS, z < 0 ? Direction.SOUTH : Direction.NORTH));
                }
            }
        }
        for (int x : new int[] {-7, 7}) {
            for (int z : new int[] {-3, 3}) {
                for (int y = 2; y <= 8; y++) {
                    pose(x, y, z, pilier(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
                }
            }
        }
        for (int x : new int[] {-4, 0, 4}) {
            for (int z : new int[] {-2, 2}) {
                pose(x, 9, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }
        }
        for (int i = -4; i <= 4; i++) {
            for (int j = -2; j <= 2; j++) {
                cadre(i, 5 - j, 1, Direction.SOUTH, carte(c.getX() + i * 1024, c.getZ() + j * 1024, 3));
            }
        }
        // côté nord, vu depuis la cabane : la gauche du spectateur est à l'est
        for (int x = -3; x <= 3; x++) {
            for (int y = 4; y <= 7; y++) {
                cadre(x, y, -1, Direction.NORTH, carte(c.getX() - x * 256, c.getZ() + (5 - y) * 256, 1));
            }
        }
        texte(0.5, 8.12, 1.06, 180F, "{\"text\":\"CARTE DU MONDE\",\"color\":\"gold\",\"bold\":true}", 2.4F, 0x00000000, false);
        texte(0.5, 2.1, 1.06, 180F, "[{\"text\":\"La croix rouge, c'est nous.\\n\",\"color\":\"white\"},{\"text\":\"Ton livre de quêtes est dans ton inventaire.\",\"color\":\"gray\"}]", 0.75F, 0x00000000, false);
        texte(0.5, 8.12, -0.06, 0F, "{\"text\":\"LES ENVIRONS\",\"color\":\"gold\",\"bold\":true}", 2.4F, 0x00000000, false);
    }

    /** Une carte du monde coloriée par biome ; la cabane y est marquée d'une croix si elle est dessus. */
    private ItemStack carte(int x, int z, int echelle) {
        ItemStack carte = MapItem.create(level, x, z, (byte) echelle, false, false);
        MapItemSavedData data = MapItem.getSavedData(carte, level);
        if (data != null) {
            int demi = 64 << data.scale;
            if (Math.abs(c.getX() - data.centerX) < demi && Math.abs(c.getZ() - data.centerZ) < demi) {
                MapItemSavedData.addTargetDecoration(carte, c, "cabane", MapDecorationTypes.RED_X);
            }
        }
        dessinerCarte(level, carte);
        return carte;
    }

    // ------------------------------------------------------------------ la Grande Cabane : notre base

    private static final int CX0 = -22, CX1 = 22, CZ0 = -58, CZ1 = -34, CZC = -46;

    /** Hauteur du toit (deux pans, faîte est-ouest) : 14 au bord des murs, 26 au faîte. */
    private static int hauteurToit(int z) {
        return 26 - Math.abs(z - CZC);
    }

    private void cabaneMurs() {
        vider(CX0 - 2, CZ0 - 2, CX1 + 3, CZ1 + 5, 1, 4);
        for (int x = CX0; x <= CX1; x++) {
            for (int z = CZ0; z <= CZ1; z++) {
                boolean bord = x == CX0 || x == CX1 || z == CZ0 || z == CZ1;
                pose(x, -1, z, Blocks.COBBLESTONE.defaultBlockState());
                pose(x, 0, z, bord ? briques() : plancher());
                if (!bord) {
                    pose(x, 7, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
                    pose(x, 14, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
                }
            }
        }
        for (int y = 1; y <= 13; y++) {
            for (int x = CX0; x <= CX1; x++) {
                murCabane(x, y, CZ0);
                murCabane(x, y, CZ1);
            }
            for (int z = CZ0 + 1; z < CZ1; z++) {
                murCabane(CX0, y, z);
                murCabane(CX1, y, z);
            }
        }
        porcheEtBalcon();
    }

    /** Colombages : soubassement de pierre, rez-de-chaussée en épicéa, étage en torchis clair. */
    private void murCabane(int x, int y, int z) {
        boolean longMur = z == CZ0 || z == CZ1;
        boolean coin = (x == CX0 || x == CX1) && longMur;
        int pas = Math.floorMod(z - CZ0, 6);
        if (coin || (longMur && Math.abs(x) % 6 == 4) || (!longMur && pas == 0)) {
            pose(x, y, z, pilier(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
            return;
        }
        if (y == 7 || y == 13) {
            pose(x, y, z, pilier(Blocks.STRIPPED_DARK_OAK_LOG, longMur ? Direction.Axis.X : Direction.Axis.Z));
            return;
        }
        // la grande entrée (5 de large), la porte du balcon, la porte de côté
        if ((z == CZ1 && Math.abs(x) <= 2 && y <= 5) || (z == CZ1 && Math.abs(x) <= 1 && y >= 8 && y <= 10)
                || (x == CX0 && z == CZC + 3 && y <= 3)) {
            pose(x, y, z, AIR);
            return;
        }
        boolean fenetre = (y >= 3 && y <= 5) || (y >= 9 && y <= 11);
        boolean colonne = longMur ? Math.abs(x) % 6 <= 2 : pas >= 2 && pas <= 4;
        boolean murDesReliques = z == CZ0 && Math.abs(x) <= 5 && y <= 6;
        boolean cheminee = x == CX1 && Math.abs(z - CZC) <= 1;
        if (fenetre && colonne && !murDesReliques && !cheminee) {
            pose(x, y, z, Blocks.GLASS_PANE.defaultBlockState());
        } else if (y <= 1) {
            pose(x, y, z, briques());
        } else if (y >= 8) {
            pose(x, y, z, Blocks.WHITE_TERRACOTTA.defaultBlockState());
        } else {
            pose(x, y, z, rnd.nextFloat() < 0.08F ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState());
        }
    }

    /** Porche sur toute la façade, avec le balcon de l'étage au-dessus. */
    private void porcheEtBalcon() {
        int z0 = CZ1 + 1;
        int z1 = CZ1 + 4;
        for (int x = -16; x <= 16; x++) {
            for (int z = z0; z <= z1; z++) {
                pose(x, -1, z, Blocks.COBBLESTONE.defaultBlockState());
                pose(x, 0, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
                for (int y = 1; y <= 6; y++) {
                    pose(x, y, z, AIR);
                }
                pose(x, 7, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
                pose(x, 8, z, z == z1 || Math.abs(x) == 16 ? Blocks.SPRUCE_FENCE.defaultBlockState() : AIR);
            }
        }
        for (int x : new int[] {-16, -10, -4, 4, 10, 16}) {
            for (int y = 1; y <= 6; y++) {
                pose(x, y, z1, pilier(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
            }
            pose(x, 9, z1, Blocks.LANTERN.defaultBlockState());
        }
        for (int x : new int[] {-13, -7, 7, 13}) {
            pose(x, 6, z0 + 1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        for (int x : new int[] {-9, -8, 8, 9}) {
            pose(x, 1, z0, escalier(Blocks.SPRUCE_STAIRS, Direction.NORTH));
        }
        pose(-15, 1, z0, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        pose(15, 1, z0, Blocks.COMPOSTER.defaultBlockState());
        pose(-6, 1, z0, Blocks.POTTED_RED_TULIP.defaultBlockState());
        pose(6, 1, z0, Blocks.POTTED_ORANGE_TULIP.defaultBlockState());
    }

    private void cabaneToitEtTours() {
        for (int z = CZ0 - 1; z <= CZ1 + 1; z++) {
            int dz = z - CZC;
            int h = hauteurToit(z);
            for (int x = CX0 - 1; x <= CX1 + 1; x++) {
                if (dz == 0) {
                    pose(x, h, z, Blocks.DARK_OAK_PLANKS.defaultBlockState());
                    pose(x, h + 1, z, Blocks.DARK_OAK_SLAB.defaultBlockState());
                } else {
                    pose(x, h, z, escalier(Blocks.DARK_OAK_STAIRS, dz < 0 ? Direction.SOUTH : Direction.NORTH));
                    if (Math.abs(dz) < 12 && x > CX0 - 1 && x < CX1 + 1) {
                        pose(x, h - 1, z, escalier(Blocks.DARK_OAK_STAIRS, dz < 0 ? Direction.NORTH : Direction.SOUTH, Half.TOP));
                    }
                }
            }
        }
        // pignons, avec une grande rosace
        for (int z = CZ0 + 1; z < CZ1; z++) {
            for (int y = 14; y < hauteurToit(z) - 1; y++) {
                boolean rosace = Math.abs(z - CZC) <= 2 && y >= 18 && y <= 21;
                BlockState s = rosace ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState();
                pose(CX0, y, z, s);
                pose(CX1, y, z, s);
            }
        }
        cheminee();
        tourDeCabane(CX0, CZ1, -1);
        tourDeCabane(CX1, CZ1, 1);
        for (int y = 1; y <= 12; y++) {
            for (int x = CX0; x <= CX1; x++) {
                vigne(x, y, CZ0 - 1, Direction.SOUTH);
            }
        }
    }

    private void cheminee() {
        for (int y = -1; y <= 29; y++) {
            for (int z = CZC - 1; z <= CZC + 1; z++) {
                boolean foyer = z == CZC && (y == 1 || y == 2);
                pose(CX1 + 1, y, z, foyer ? AIR : briques());
            }
            pose(CX1 + 2, y, CZC, briques());
        }
        pose(CX1, 1, CZC, AIR);
        pose(CX1, 2, CZC, AIR);
        pose(CX1 + 1, 1, CZC, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.FACING, Direction.WEST));
        pose(CX1 + 1, 30, CZC, Blocks.CAMPFIRE.defaultBlockState());
    }

    /** Une tour de pierre à un coin de la façade, ouverte sur la cabane, avec un toit en pointe. */
    private void tourDeCabane(int tx, int tz, int sens) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int m = Math.max(Math.abs(dx), Math.abs(dz));
                pose(tx + dx, -1, tz + dz, Blocks.COBBLESTONE.defaultBlockState());
                pose(tx + dx, 0, tz + dz, m == 3 ? briques() : Blocks.SPRUCE_PLANKS.defaultBlockState());
                for (int y = 1; y <= 20; y++) {
                    if (m == 3) {
                        boolean fenetre = (dx == 0 || dz == 0) && dx != 3 * sens && (y == 3 || y == 4 || y == 10 || y == 11 || (y >= 17 && y <= 19));
                        pose(tx + dx, y, tz + dz, fenetre ? Blocks.GLASS_PANE.defaultBlockState() : briques());
                    } else {
                        pose(tx + dx, y, tz + dz, y == 7 || y == 16 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : AIR);
                    }
                }
            }
        }
        for (int k = 0; k <= 4; k++) {
            int r = 4 - k;
            int y = 21 + k;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    BlockState s;
                    if (r == 0 || (Math.abs(dx) == r && Math.abs(dz) == r)) {
                        s = Blocks.DARK_OAK_PLANKS.defaultBlockState();
                    } else if (Math.abs(dz) == r) {
                        s = escalier(Blocks.DARK_OAK_STAIRS, dz < 0 ? Direction.SOUTH : Direction.NORTH);
                    } else {
                        s = escalier(Blocks.DARK_OAK_STAIRS, dx < 0 ? Direction.EAST : Direction.WEST);
                    }
                    pose(tx + dx, y, tz + dz, s);
                }
            }
        }
        pose(tx, 26, tz, Blocks.LIGHTNING_ROD.defaultBlockState());
        // échelle contre le mur extérieur, portes vers la cabane au rez-de-chaussée et à l'étage
        for (int y = 1; y <= 16; y++) {
            pose(tx + 2 * sens, y, tz, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, sens > 0 ? Direction.WEST : Direction.EAST));
        }
        for (int y : new int[] {1, 2, 3, 8, 9, 10}) {
            pose(tx - 3 * sens, y, tz - 1, AIR);
        }
        pose(tx, 17, tz, Blocks.LANTERN.defaultBlockState());
    }

    private void cabaneInterieur() {
        // mur des reliques, au fond
        for (int x = -3; x <= 3; x++) {
            pose(x, 1, CZ0 + 1, Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            cadre(x, 2, CZ0 + 1, Direction.SOUTH, ItemStack.EMPTY);
        }
        texte(0.5, 4.4, CZ0 + 1.05, 180F, "{\"text\":\"Les Sept Reliques\",\"color\":\"gold\",\"bold\":true}", 1.2F, 0x00000000, false);
        // deux grandes tablées
        for (int x = -14; x <= 14; x++) {
            if (Math.abs(x) <= 1) {
                continue;
            }
            for (int z : new int[] {CZC - 3, CZC + 3}) {
                pose(x, 1, z, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
                pose(x, 1, z - 1, escalier(Blocks.SPRUCE_STAIRS, Direction.NORTH));
                pose(x, 1, z + 1, escalier(Blocks.SPRUCE_STAIRS, Direction.SOUTH));
            }
        }
        tapis(Blocks.RED_CARPET.defaultBlockState(), 1, -1, 1, CZ0 + 3, CZ1 - 1);
        // bibliothèque à l'ouest, avec le pupitre du Gardien
        for (int z = CZ0 + 1; z <= -38; z++) {
            if (z != CZC + 3) {
                for (int y = 1; y <= 3; y++) {
                    pose(CX0 + 1, y, z, Blocks.BOOKSHELF.defaultBlockState());
                }
            }
        }
        for (int x = CX0 + 2; x <= -8; x++) {
            for (int y = 1; y <= 3; y++) {
                pose(x, y, CZ0 + 1, Blocks.BOOKSHELF.defaultBlockState());
            }
        }
        pose(-16, 1, CZ0 + 6, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.EAST));
        journal(-16, 1, CZ0 + 6);
        pose(-14, 1, CZ0 + 6, escalier(Blocks.DARK_OAK_STAIRS, Direction.EAST));
        pose(-16, 1, CZ0 + 3, Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
        // la réserve : 14 grands coffres contre le mur du fond
        for (int x = 7; x <= 19; x += 2) {
            for (int y = 1; y <= 2; y++) {
                doubleCoffre(x, y, CZ0 + 1, Direction.SOUTH);
            }
        }
        coffre(7, 1, CZ0 + 1, "coffres/cabane");
        coffre(8, 1, CZ0 + 1, "coffres/cabane");
        // cuisine à l'est, près de la cheminée
        Direction ouest = Direction.WEST;
        pose(CX1 - 1, 1, CZ0 + 3, Blocks.FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, ouest));
        pose(CX1 - 1, 1, CZ0 + 4, Blocks.SMOKER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, ouest));
        pose(CX1 - 1, 1, CZ0 + 5, Blocks.BLAST_FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, ouest));
        pose(CX1 - 1, 1, CZ0 + 6, Blocks.CRAFTING_TABLE.defaultBlockState());
        pose(CX1 - 1, 1, CZ0 + 7, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, ouest));
        pose(CX1 - 1, 1, CZ0 + 8, Blocks.WATER_CAULDRON.defaultBlockState().setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3));
        for (int z = CZC - 2; z <= CZC + 2; z += 4) {
            pose(CX1 - 1, 1, z, escalier(Blocks.SPRUCE_STAIRS, Direction.EAST));
        }
        // escaliers : à l'ouest vers l'étage, à l'est vers les combles
        escalierDroit(-19, 0);
        escalierDroit(18, 7);
        // lustres
        for (int x = -12; x <= 12; x += 6) {
            for (int z : new int[] {CZC - 6, CZC + 6}) {
                pose(x, 6, z, Blocks.CHAIN.defaultBlockState());
                pose(x, 5, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }
        }
        etage();
        combles();
    }

    /** Un escalier de deux de large qui monte de 7 vers le nord, à partir de z = -38. */
    private void escalierDroit(int x0, int base) {
        for (int k = 0; k <= 6; k++) {
            int z = -38 - k;
            for (int x = x0; x <= x0 + 1; x++) {
                for (int y = base + 1; y <= base + k; y++) {
                    pose(x, y, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
                }
                pose(x, base + k + 1, z, escalier(Blocks.SPRUCE_STAIRS, Direction.NORTH));
                if (k < 6) {
                    pose(x, base + 7, z, AIR);
                }
            }
        }
        int rampe = x0 < 0 ? x0 + 2 : x0 - 1;
        for (int z = -43; z <= -38; z++) {
            pose(rampe, base + 8, z, Blocks.SPRUCE_FENCE.defaultBlockState());
        }
        pose(x0, base + 8, -37, Blocks.SPRUCE_FENCE.defaultBlockState());
        pose(x0 + 1, base + 8, -37, Blocks.SPRUCE_FENCE.defaultBlockState());
    }

    /** L'étage : le grand dortoir. */
    private void etage() {
        Block[] lits = {Blocks.RED_BED, Blocks.BLUE_BED, Blocks.GREEN_BED, Blocks.YELLOW_BED, Blocks.PURPLE_BED, Blocks.CYAN_BED, Blocks.ORANGE_BED, Blocks.LIME_BED};
        int i = 0;
        for (int x : new int[] {-15, -12, -9, -6, 6, 9, 12, 15}) {
            Block lit = lits[i++ % lits.length];
            pose(x, 8, CZ0 + 2, lit.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT));
            pose(x, 8, CZ0 + 1, lit.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD));
            pose(x + 1, 8, CZ0 + 1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        }
        for (int x : new int[] {-12, -9, -6, 6, 9, 12}) {
            Block lit = lits[i++ % lits.length];
            pose(x, 8, CZ1 - 2, lit.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT));
            pose(x, 8, CZ1 - 1, lit.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD));
        }
        tapis(Blocks.BROWN_CARPET.defaultBlockState(), 8, -2, 2, CZ0 + 4, CZ1 - 4);
        for (int x = -12; x <= 12; x += 12) {
            pose(x, 13, CZC, Blocks.CHAIN.defaultBlockState());
            pose(x, 12, CZC, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
    }

    /** Les combles : l'atelier, avec une table d'enchantement entourée de bibliothèques. */
    private void combles() {
        pose(0, 15, CZC, Blocks.ENCHANTING_TABLE.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 2 && !(dz == 2 && Math.abs(dx) <= 1)) {
                    pose(dx, 15, CZC + dz, Blocks.BOOKSHELF.defaultBlockState());
                    pose(dx, 16, CZC + dz, Blocks.BOOKSHELF.defaultBlockState());
                }
            }
        }
        BlockState[] atelier = {
                Blocks.CRAFTING_TABLE.defaultBlockState(),
                Blocks.ANVIL.defaultBlockState(),
                Blocks.SMITHING_TABLE.defaultBlockState(),
                Blocks.GRINDSTONE.defaultBlockState().setValue(net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.FLOOR),
                Blocks.STONECUTTER.defaultBlockState(),
                Blocks.LOOM.defaultBlockState(),
                Blocks.FLETCHING_TABLE.defaultBlockState(),
                Blocks.BREWING_STAND.defaultBlockState(),
                Blocks.CARTOGRAPHY_TABLE.defaultBlockState(),
                Blocks.COMPOSTER.defaultBlockState(),
        };
        int k = 0;
        for (int x = -15; x <= 15; x += 3) {
            if (Math.abs(x) <= 3) {
                continue;
            }
            pose(x, 15, CZC - 5, atelier[k++ % atelier.length]);
        }
        for (int x = -14; x <= 14; x += 2) {
            if (Math.abs(x) > 3) {
                pose(x, 15, CZC + 5, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
            }
        }
        for (int x : new int[] {-14, -7, 7, 14}) {
            for (int y = 19; y <= 25; y++) {
                pose(x, y, CZC, Blocks.CHAIN.defaultBlockState());
            }
            pose(x, 18, CZC, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
    }

    private void doubleCoffre(int x, int y, int z, Direction face) {
        // le coffre « gauche » a son voisin dans le sens horaire de la façade
        Direction versVoisin = face.getClockWise();
        pose(x, y, z, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face)
                .setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        pose(x - versVoisin.getStepX(), y, z - versVoisin.getStepZ(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face)
                .setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT));
    }

    private void tapis(BlockState tapis, int y, int xa, int xb, int za, int zb) {
        for (int x = xa; x <= xb; x++) {
            for (int z = za; z <= zb; z++) {
                if (level.getBlockState(c.offset(x, y, z)).isAir()) {
                    pose(x, y, z, tapis);
                }
            }
        }
    }

    private void vigne(int x, int y, int z, Direction versLeMur) {
        if (rnd.nextFloat() < 0.35F && level.getBlockState(c.offset(x, y, z)).isAir()
                && level.getBlockState(c.offset(x, y, z).relative(versLeMur)).isSolid()) {
            pose(x, y, z, Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(versLeMur), true));
        }
    }

    // ------------------------------------------------------------------ le Grand Bazar

    private static final int BX0 = -62, BX1 = -32, BZ0 = -14, BZ1 = 14;

    private static int hauteurToitBazar(int z) {
        return 25 - Math.abs(z);
    }

    private void bazarMurs() {
        vider(BX0 - 1, BZ0 - 1, BX1 + 4, BZ1 + 1, 1, 4);
        for (int x = BX0; x <= BX1; x++) {
            for (int z = BZ0; z <= BZ1; z++) {
                boolean bord = x == BX0 || x == BX1 || z == BZ0 || z == BZ1;
                pose(x, -1, z, Blocks.COBBLESTONE.defaultBlockState());
                pose(x, 0, z, bord ? briques() : Math.floorMod((x >> 1) + (z >> 1), 2) == 0
                        ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        for (int y = 1; y <= 10; y++) {
            for (int x = BX0; x <= BX1; x++) {
                murBazar(x, y, BZ0);
                murBazar(x, y, BZ1);
            }
            for (int z = BZ0 + 1; z < BZ1; z++) {
                murBazar(BX0, y, z);
                murBazar(BX1, y, z);
            }
        }
        for (int z = BZ0 - 1; z <= BZ1 + 1; z++) {
            int h = hauteurToitBazar(z);
            for (int x = BX0 - 1; x <= BX1 + 1; x++) {
                if (z == 0) {
                    pose(x, h, z, Blocks.ACACIA_PLANKS.defaultBlockState());
                    pose(x, h + 1, z, Blocks.ACACIA_SLAB.defaultBlockState());
                } else {
                    pose(x, h, z, escalier(Blocks.ACACIA_STAIRS, z < 0 ? Direction.SOUTH : Direction.NORTH));
                    if (Math.abs(z) < 14 && x > BX0 - 1 && x < BX1 + 1) {
                        pose(x, h - 1, z, escalier(Blocks.ACACIA_STAIRS, z < 0 ? Direction.NORTH : Direction.SOUTH, Half.TOP));
                    }
                }
            }
        }
        for (int z = BZ0 + 1; z < BZ1; z++) {
            for (int y = 11; y < hauteurToitBazar(z) - 1; y++) {
                boolean rosace = Math.abs(z) <= 2 && y >= 16 && y <= 19;
                BlockState s = rosace ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.ACACIA_PLANKS.defaultBlockState();
                pose(BX0, y, z, s);
                pose(BX1, y, z, s);
            }
        }
        // auvent rayé au-dessus de l'entrée
        for (int x = BX1 + 1; x <= BX1 + 3; x++) {
            for (int z = -5; z <= 5; z++) {
                pose(x, 8, z, (Math.floorMod(z, 2) == 0 ? Blocks.RED_WOOL : Blocks.WHITE_WOOL).defaultBlockState());
            }
        }
        for (int z : new int[] {-5, 5}) {
            for (int y = 1; y <= 7; y++) {
                pose(BX1 + 3, y, z, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.Y));
            }
        }
        for (int z : new int[] {-3, 3}) {
            pose(BX1 + 2, 7, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
    }

    /** Murs de grès lisse, poteaux et poutres d'acacia, grandes fenêtres. */
    private void murBazar(int x, int y, int z) {
        boolean longMur = z == BZ0 || z == BZ1;
        boolean coin = (x == BX0 || x == BX1) && longMur;
        int pas = Math.floorMod(x - BX0, 5);
        if (coin || (longMur && pas == 0) || (!longMur && Math.abs(z) % 5 == 4)) {
            pose(x, y, z, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.Y));
            return;
        }
        if (y == 10) {
            pose(x, y, z, pilier(Blocks.STRIPPED_ACACIA_LOG, longMur ? Direction.Axis.X : Direction.Axis.Z));
            return;
        }
        if (x == BX1 && Math.abs(z) <= 3 && y <= 7) {
            pose(x, y, z, AIR);
            return;
        }
        boolean fenetre = longMur ? (pas == 2 || pas == 3) && y >= 4 && y <= 8
                : (Math.abs(z) % 5 == 1 || Math.abs(z) % 5 == 2) && y >= 4 && y <= 8 && (x == BX1 || Math.abs(z) >= 6);
        if (fenetre) {
            pose(x, y, z, Blocks.GLASS_PANE.defaultBlockState());
        } else if (y <= 2) {
            pose(x, y, z, briques());
        } else {
            pose(x, y, z, Blocks.SMOOTH_SANDSTONE.defaultBlockState());
        }
    }

    private void bazarInterieur() {
        // le grand comptoir de Mouloud, en U
        for (int z = -6; z <= 6; z++) {
            pose(-52, 1, z, pilier(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Z));
        }
        for (int x = -58; x <= -53; x++) {
            pose(x, 1, -6, pilier(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.X));
            pose(x, 1, 6, pilier(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.X));
        }
        pose(-52, 1, 0, fr.cabane.CabaneBlocks.COMPTOIR.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
        // étagères et vitrine derrière lui
        for (int z = BZ0 + 1; z < BZ1; z++) {
            for (int y = 1; y <= 2; y++) {
                pose(BX0 + 1, y, z, Math.floorMod(z, 3) == 0 ? Blocks.BOOKSHELF.defaultBlockState()
                        : Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.EAST));
            }
        }
        List<ItemStack> vitrine = List.of(
                new ItemStack(fr.cabane.CabaneItems.TELEPHONE.get()), new ItemStack(fr.cabane.CabaneItems.BOOSTER.get()),
                new ItemStack(fr.cabane.CabaneItems.CLASSEUR.get()), new ItemStack(fr.cabane.CabaneItems.TICKET_A_GRATTER.get()),
                new ItemStack(fr.cabane.CabaneItems.PARACHUTE.get()), new ItemStack(fr.cabane.CabaneBlocks.ETAL.get()),
                new ItemStack(fr.cabane.CabaneBlocks.MACHINE_A_SOUS.get()), new ItemStack(fr.cabane.CabaneBlocks.TABLE_ROULETTE.get()),
                new ItemStack(fr.cabane.CabaneBlocks.TABLE_BLACKJACK.get()), new ItemStack(fr.cabane.CabaneBlocks.ROUE_FORTUNE.get()),
                new ItemStack(fr.cabane.CabaneBlocks.TABLE_DES.get()));
        List<ItemStack> vitrine2 = List.of(
                new ItemStack(Items.GOLDEN_APPLE), new ItemStack(Items.DIAMOND), new ItemStack(Items.EMERALD), new ItemStack(Items.SADDLE),
                new ItemStack(Items.NAME_TAG), new ItemStack(Items.ENDER_PEARL), new ItemStack(Items.SPYGLASS), new ItemStack(Items.COMPASS),
                new ItemStack(Items.CAKE), new ItemStack(Items.TRIDENT), new ItemStack(Items.ELYTRA));
        for (int i = 0; i < 11; i++) {
            cadre(BX0 + 1, 3, i - 5, Direction.EAST, vitrine.get(i));
            cadre(BX0 + 1, 4, i - 5, Direction.EAST, vitrine2.get(i));
        }
        // le tableau des contrats, près de l'entrée
        for (int x = -36; x <= -34; x++) {
            for (int y = 1; y <= 4; y++) {
                pose(x, y, -9, Math.abs(x + 35) == 1 ? pilier(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y) : Blocks.DARK_OAK_PLANKS.defaultBlockState());
            }
            pose(x, 5, -9, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        }
        pose(-36, 6, -9, Blocks.LANTERN.defaultBlockState());
        pose(-34, 6, -9, Blocks.LANTERN.defaultBlockState());
        pose(-35, 1, -8, fr.cabane.CabaneBlocks.TABLEAU_CONTRATS.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
        // huit emplacements d'étals pour les joueurs
        Block[][] toiles = {{Blocks.RED_WOOL, Blocks.WHITE_WOOL}, {Blocks.BLUE_WOOL, Blocks.WHITE_WOOL}, {Blocks.YELLOW_WOOL, Blocks.ORANGE_WOOL},
                {Blocks.GREEN_WOOL, Blocks.LIME_WOOL}};
        int i = 0;
        for (int cx : new int[] {-56, -50, -44, -38}) {
            for (int cz : new int[] {-11, 11}) {
                Block[] t = toiles[i++ % toiles.length];
                etalLibre(cx, cz, t[0], t[1]);
            }
        }
        // lustres sous le faîte, lanternes le long des murs
        for (int x : new int[] {-57, -51, -45, -39}) {
            for (int y = 13; y <= 24; y++) {
                pose(x, y, 0, Blocks.CHAIN.defaultBlockState());
            }
            pose(x, 12, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        // pas de lanterne pendue sous les marches retournées du toit : elles ne les tiennent pas
        // (et avec Amendments, une lanterne qui tombe de haut met le feu)
        for (int x : new int[] {-46, -40}) {
            for (int z : new int[] {-5, 5}) {
                pose(x, 1, z, Blocks.LANTERN.defaultBlockState());
            }
        }
        // caisses et ballots près de l'entrée
        pose(-34, 1, 10, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        pose(-34, 2, 10, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        pose(-33, 1, 10, Blocks.HAY_BLOCK.defaultBlockState());
        pose(-34, 1, 9, Blocks.HAY_BLOCK.defaultBlockState());
        pose(-33, 1, 6, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        // lisible des deux côtés du comptoir
        String enseigne = "[{\"text\":\"LA BOUTIQUE DE MOULOUD\\n\",\"color\":\"gold\",\"bold\":true},{\"text\":\"clic droit sur le comptoir\",\"color\":\"gray\",\"bold\":false}]";
        texte(-52.45, 4.6, 0.5, 90F, enseigne, 1.3F, 0x99000000, false);
        texte(-52.55, 4.6, 0.5, -90F, enseigne, 1.3F, 0x99000000, false);
    }

    /** Un emplacement d'étal (3 x 3) avec sa toile ; le joueur y pose l'étal acheté chez Mouloud. */
    private void etalLibre(int cx, int cz, Block toile1, Block toile2) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean coin = dx != 0 && dz != 0;
                for (int y = 1; y <= 3; y++) {
                    pose(cx + dx, y, cz + dz, coin ? Blocks.SPRUCE_FENCE.defaultBlockState() : AIR);
                }
                if (!coin) {
                    pose(cx + dx, 1, cz + dz, Blocks.WHITE_CARPET.defaultBlockState());
                }
                pose(cx + dx, 4, cz + dz, (Math.floorMod(dx, 2) == 0 ? toile1 : toile2).defaultBlockState());
            }
        }
        pose(cx, 3, cz, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        pose(cx - 1, 5, cz - 1, Blocks.LANTERN.defaultBlockState());
        pose(cx + 1, 5, cz + 1, Blocks.LANTERN.defaultBlockState());
        texte(cx + 0.5, 1.6, cz + 0.5, 0F, "[{\"text\":\"EMPLACEMENT LIBRE\\n\",\"color\":\"white\",\"bold\":true},{\"text\":\"Achète un étal chez Mouloud\",\"color\":\"gray\",\"bold\":false}]", 0.55F, 0x99000000, true);
    }

    // ------------------------------------------------------------------ le début du village

    private record Palette(Block mur, Block poutre, Block toit, Block toitPlein, Block toitDalle, Block porte, String bois) {
    }

    private static final Palette EPICEA = new Palette(Blocks.SPRUCE_PLANKS, Blocks.STRIPPED_SPRUCE_LOG, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_SLAB, Blocks.SPRUCE_DOOR, "spruce");
    private static final Palette ACACIA = new Palette(Blocks.ACACIA_PLANKS, Blocks.STRIPPED_ACACIA_LOG, Blocks.BRICK_STAIRS, Blocks.BRICKS, Blocks.BRICK_SLAB, Blocks.ACACIA_DOOR, "acacia");
    private static final Palette BOULEAU = new Palette(Blocks.BIRCH_PLANKS, Blocks.STRIPPED_BIRCH_LOG, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_SLAB, Blocks.BIRCH_DOOR, "birch");
    private static final Palette TORCHIS = new Palette(Blocks.WHITE_TERRACOTTA, Blocks.STRIPPED_DARK_OAK_LOG, Blocks.ACACIA_STAIRS, Blocks.ACACIA_PLANKS, Blocks.ACACIA_SLAB, Blocks.DARK_OAK_DOOR, "dark_oak");

    private static final String MAISON_LIBRE = "[{\"text\":\"MAISON LIBRE\\n\",\"color\":\"green\",\"bold\":true},{\"text\":\"Installe-toi !\",\"color\":\"white\",\"bold\":false}]";

    // repère de la maison en cours : u le long de la rue, v vers le fond
    private int mcx;
    private int mzFront;
    private int msens;

    private void m(int u, int y, int v, BlockState s) {
        pose(mcx + u, y, mzFront + msens * v, s);
    }

    private Direction versLeFond() {
        return msens < 0 ? Direction.NORTH : Direction.SOUTH;
    }

    private void maisons() {
        // côté nord de la rue : façades vers le sud ; côté sud : façades vers le nord
        maison(25, -7, -1, EPICEA, MAISON_LIBRE);
        maison(36, -7, -1, ACACIA, MAISON_LIBRE);
        chantier(47, -7, -1);
        maison(25, 7, 1, BOULEAU, MAISON_LIBRE);
        maison(36, 7, 1, TORCHIS, MAISON_LIBRE);
        parcelle(43, 7, 51, 13);
    }

    /** Une maison de 9 x 7 avec toit à deux pans, un lit, un petit jardin devant. */
    private void maison(int cx, int zFront, int sens, Palette p, String texte) {
        mcx = cx;
        mzFront = zFront;
        msens = sens;
        Direction fond = versLeFond();
        for (int u = -5; u <= 5; u++) {
            for (int v = -1; v <= 7; v++) {
                for (int y = 1; y <= 10; y++) {
                    m(u, y, v, AIR);
                }
            }
        }
        for (int u = -4; u <= 4; u++) {
            for (int v = 0; v <= 6; v++) {
                boolean bord = Math.abs(u) == 4 || v == 0 || v == 6;
                m(u, -1, v, Blocks.COBBLESTONE.defaultBlockState());
                m(u, 0, v, bord ? briques() : p.mur() == Blocks.WHITE_TERRACOTTA ? Blocks.SPRUCE_PLANKS.defaultBlockState() : p.mur().defaultBlockState());
                if (!bord) {
                    continue;
                }
                boolean coin = Math.abs(u) == 4 && (v == 0 || v == 6);
                for (int y = 1; y <= 4; y++) {
                    BlockState s;
                    if (coin) {
                        s = pilier(p.poutre(), Direction.Axis.Y);
                    } else if (y == 4) {
                        s = pilier(p.poutre(), Math.abs(u) == 4 ? Direction.Axis.Z : Direction.Axis.X);
                    } else if ((y == 2 || y == 3) && ((v == 0 || v == 6) ? Math.abs(u) == 2 : v == 3)) {
                        s = Blocks.GLASS_PANE.defaultBlockState();
                    } else {
                        s = p.mur().defaultBlockState();
                    }
                    m(u, y, v, s);
                }
            }
        }
        m(0, 1, 0, p.porte().defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, fond)
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
        m(0, 2, 0, p.porte().defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, fond)
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        // toit : faîte le long de la rue
        for (int v = -1; v <= 7; v++) {
            int h = 8 - Math.abs(v - 3);
            for (int u = -5; u <= 5; u++) {
                if (v == 3) {
                    m(u, h, v, p.toitPlein().defaultBlockState());
                    m(u, h + 1, v, p.toitDalle().defaultBlockState());
                } else {
                    m(u, h, v, escalier(p.toit(), v < 3 ? fond : fond.getOpposite()));
                }
            }
            for (int y = 5; y < h; y++) {
                if (v >= 1 && v <= 5) {
                    m(-4, y, v, p.toitPlein().defaultBlockState());
                    m(4, y, v, p.toitPlein().defaultBlockState());
                }
            }
        }
        // intérieur
        Block lit = msens < 0 ? Blocks.RED_BED : Blocks.LIGHT_BLUE_BED;
        m(-3, 1, 4, lit.defaultBlockState().setValue(BedBlock.FACING, fond).setValue(BedBlock.PART, BedPart.FOOT));
        m(-3, 1, 5, lit.defaultBlockState().setValue(BedBlock.FACING, fond).setValue(BedBlock.PART, BedPart.HEAD));
        m(3, 1, 5, Blocks.CRAFTING_TABLE.defaultBlockState());
        m(3, 1, 4, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        m(3, 1, 1, Blocks.FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
        m(-3, 1, 1, Blocks.POTTED_POPPY.defaultBlockState());
        m(0, 7, 3, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        jardin();
        usure(p);
        borne(mcx + 3 * 1, mzFront + msens * -3, PRIX_MAISON, msens < 0 ? Direction.SOUTH : Direction.NORTH);
        // meubles des mods de déco : table, chaise, vaisselle, jardinières, paillasson
        mObjet(0, 1, 4, "handcrafted:" + p.bois() + "_table", null);
        mObjet(0, 1, 3, "handcrafted:" + p.bois() + "_chair", fond);
        mObjet(0, 2, 4, "handcrafted:terracotta_crockery_combo", fond.getOpposite());
        mObjet(0, 1, -1, "supplementaries:doormat", fond.getOpposite());
        BlockState jardiniere = modOuNull("supplementaries:flower_box");
        if (jardiniere != null) {
            for (int u : new int[] {-2, 2}) {
                m(u, 1, -1, avec(vers(jardiniere, fond.getOpposite()), "face", "floor"));
            }
        }
    }

    private void mObjet(int u, int y, int v, String id, Direction face) {
        BlockState s = modOuNull(id);
        if (s != null) {
            m(u, y, v, face == null ? s : vers(s, face));
        }
    }

    /** L'allée jusqu'à la rue et des fleurs de chaque côté. */
    private void jardin() {
        Block[] fleurs = {Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.ALLIUM};
        for (int v = -3; v <= -1; v++) {
            m(0, 0, v, Blocks.DIRT_PATH.defaultBlockState());
            m(0, 1, v, AIR);
            for (int u : new int[] {-3, -2, 2, 3}) {
                m(u, 0, v, Blocks.GRASS_BLOCK.defaultBlockState());
                m(u, 1, v, fleurs[rnd.nextInt(fleurs.length)].defaultBlockState());
            }
        }
    }

    private void texteMaison(String json) {
        double z = msens < 0 ? mzFront + 1.06 : mzFront - 0.06;
        texte(mcx + 0.5, 3.15, z, msens < 0 ? 180F : 0F, json, 0.7F, 0x99000000, false);
    }

    /** Une maison commencée : à vous de la finir. */
    private void chantier(int cx, int zFront, int sens) {
        mcx = cx;
        mzFront = zFront;
        msens = sens;
        for (int u = -4; u <= 4; u++) {
            for (int v = 0; v <= 6; v++) {
                boolean bord = Math.abs(u) == 4 || v == 0 || v == 6;
                m(u, -1, v, Blocks.COBBLESTONE.defaultBlockState());
                m(u, 0, v, bord ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
                if (bord && !(u == 0 && v == 0) && (v != 0 || Math.abs(u) > 1 || rnd.nextBoolean())) {
                    m(u, 1, v, Blocks.OAK_PLANKS.defaultBlockState());
                    if (rnd.nextFloat() < 0.5F) {
                        m(u, 2, v, Blocks.OAK_PLANKS.defaultBlockState());
                    }
                }
            }
        }
        for (int u : new int[] {-5, 5}) {
            for (int v : new int[] {-1, 7}) {
                for (int y = 1; y <= 6; y++) {
                    m(u, y, v, Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0));
                }
            }
        }
        m(2, 1, -2, Blocks.OAK_PLANKS.defaultBlockState());
        m(2, 2, -2, Blocks.OAK_PLANKS.defaultBlockState());
        m(3, 1, -2, pilier(Blocks.OAK_LOG, Direction.Axis.X));
        m(-2, 1, -2, Blocks.CRAFTING_TABLE.defaultBlockState());
        m(-3, 1, -2, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        borne(cx + 4, zFront + sens * -2, PRIX_CHANTIER, sens < 0 ? Direction.SOUTH : Direction.NORTH);
    }

    static final long PRIX_MAISON = 5_000;
    static final long PRIX_CHANTIER = 3_000;
    static final long PRIX_PARCELLE = 8_000;
    static final long PRIX_COMMERCE = 20_000;

    /** Une borne « à vendre » : clic droit pour acheter (deux fois, pour confirmer). */
    private void borne(int x, int z, long prix, Direction face) {
        pose(x, 1, z, fr.cabane.CabaneBlocks.BORNE_TERRAIN.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, face));
        if (level.getBlockEntity(c.offset(x, 1, z)) instanceof fr.cabane.bloc.InteractifBlockEntity be) {
            be.ajouterCaisse(prix);
        }
    }

    /** Un grand terrain pour un commerce : bordure de gravier, poteaux à lanterne, borne à vendre. */
    private void terrainCommercial(int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x == x0 || x == x1 || z == z0 || z == z1) {
                    pose(x, 0, z, (rnd.nextFloat() < 0.3F ? Blocks.COARSE_DIRT : Blocks.GRAVEL).defaultBlockState());
                }
            }
        }
        for (int x : new int[] {x0, x1}) {
            for (int z : new int[] {z0, z1}) {
                pose(x, 0, z, Blocks.COBBLESTONE.defaultBlockState());
                pose(x, 1, z, pilier(Blocks.STRIPPED_OAK_LOG, Direction.Axis.Y));
                pose(x, 2, z, pilier(Blocks.STRIPPED_OAK_LOG, Direction.Axis.Y));
                pose(x, 3, z, Blocks.LANTERN.defaultBlockState());
            }
        }
        borne((x0 + x1) / 2, z1, PRIX_COMMERCE, Direction.SOUTH);
    }

    /** Un village un peu usé : quelques trous, des vitres cassées, de la mousse, des toiles d'araignée. */
    private void usure(Palette p) {
        for (int u = -4; u <= 4; u++) {
            for (int v = 0; v <= 6; v++) {
                boolean bord = Math.abs(u) == 4 || v == 0 || v == 6;
                boolean coin = Math.abs(u) == 4 && (v == 0 || v == 6);
                if (!bord || coin || (u == 0 && v == 0)) {
                    continue;
                }
                for (int y = 1; y <= 3; y++) {
                    float r = rnd.nextFloat();
                    BlockState actuel = level.getBlockState(c.offset(mcx + u, y, mzFront + msens * v));
                    if (actuel.is(Blocks.GLASS_PANE) && r < 0.3F) {
                        m(u, y, v, AIR);
                    } else if (!actuel.is(Blocks.GLASS_PANE) && r < 0.04F) {
                        m(u, y, v, AIR);
                    } else if (!actuel.is(Blocks.GLASS_PANE) && r < 0.12F && p.mur() != Blocks.WHITE_TERRACOTTA) {
                        m(u, y, v, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                    }
                }
            }
        }
        // le toit : deux ou trois trous, de la mousse
        for (int k = 0; k < 3; k++) {
            int v = rnd.nextInt(7);
            int u = rnd.nextInt(9) - 4;
            m(u, 8 - Math.abs(v - 3), v, AIR);
        }
        for (int k = 0; k < 6; k++) {
            int v = rnd.nextInt(9) - 1;
            int u = rnd.nextInt(11) - 5;
            int h = 8 - Math.abs(v - 3) + 1;
            if (level.getBlockState(c.offset(mcx + u, h, mzFront + msens * v)).isAir()) {
                m(u, h, v, Blocks.MOSS_CARPET.defaultBlockState());
            }
        }
        m(-3, 4, 1, Blocks.COBWEB.defaultBlockState());
        m(3, 4, 5, Blocks.COBWEB.defaultBlockState());
        m(1, 1, 2, Blocks.MOSS_CARPET.defaultBlockState());
    }

    /** Un terrain à bâtir, marqué par quatre poteaux à lanterne. */
    private void parcelle(int x0, int z0, int x1, int z1) {
        for (int x : new int[] {x0, x1}) {
            for (int z : new int[] {z0, z1}) {
                pose(x, 0, z, Blocks.COBBLESTONE.defaultBlockState());
                pose(x, 1, z, pilier(Blocks.STRIPPED_OAK_LOG, Direction.Axis.Y));
                pose(x, 2, z, pilier(Blocks.STRIPPED_OAK_LOG, Direction.Axis.Y));
                pose(x, 3, z, Blocks.LANTERN.defaultBlockState());
            }
        }
        borne((x0 + x1) / 2, (z0 + z1) / 2, PRIX_PARCELLE, Direction.SOUTH);
    }

    private void forgePuitsChamps() {
        forge();
        puits(36, 18);
        champs();
    }

    /**
     * La forge de Brokk, au bout de la rue : une grande halle de pierre noire, un mur de lave derrière une vitre,
     * deux cheminées géantes qui fument, une enclume monumentale au centre, les postes des orques (hauts-fourneaux,
     * meules, bacs de trempe), un râtelier d'armes, des chaînes et des lanternes, des engrenages de Create.
     */
    private void forge() {
        vider(52, -11, 63, 11, 1, 18);
        BlockState sol1 = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        BlockState sol2 = Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        BlockState mur = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        BlockState pilierNoir = Blocks.POLISHED_BASALT.defaultBlockState();
        BlockState tuiles = Blocks.DEEPSLATE_TILES.defaultBlockState();
        // le sol et les fondations
        for (int x = 53; x <= 61; x++) {
            for (int z = -9; z <= 9; z++) {
                pose(x, -1, z, Blocks.COBBLED_DEEPSLATE.defaultBlockState());
                float r = rnd.nextFloat();
                pose(x, 0, z, r < 0.15F ? sol2 : r < 0.25F ? Blocks.BLACKSTONE.defaultBlockState() : r < 0.3F ? Blocks.MAGMA_BLOCK.defaultBlockState() : sol1);
            }
        }
        // les murs, les piliers, les fenêtres à barreaux
        for (int x = 53; x <= 61; x++) {
            for (int z = -9; z <= 9; z++) {
                boolean bord = x == 53 || x == 61 || Math.abs(z) == 9;
                if (!bord) {
                    continue;
                }
                boolean pilier = (x == 53 || x == 57 || x == 61) && (Math.abs(z) == 9 || z % 4 == 1 || z % 4 == -3);
                for (int y = 1; y <= 6; y++) {
                    boolean entree = x == 53 && Math.abs(z) <= 2 && y <= 4;
                    boolean fenetre = Math.abs(z) == 9 && (x == 55 || x == 59) && (y == 3 || y == 4);
                    if (entree) {
                        continue;
                    }
                    pose(x, y, z, fenetre ? Blocks.IRON_BARS.defaultBlockState() : pilier ? pilierNoir : (y == 1 ? Blocks.BLACKSTONE.defaultBlockState() : mur));
                }
            }
        }
        // l'arche de l'entrée
        for (int z = -2; z <= 2; z++) {
            pose(53, 5, z, Math.abs(z) == 2 ? escalier(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, z < 0 ? Direction.SOUTH : Direction.NORTH, Half.TOP)
                    : Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
        }
        // le toit en gradins de tuiles d'ardoise
        for (int etage = 0; etage < 4; etage++) {
            for (int x = 53 + etage; x <= 61 - etage; x++) {
                for (int z = -9 + etage * 2; z <= 9 - etage * 2; z++) {
                    pose(x, 7 + etage, z, tuiles);
                }
            }
        }
        // les deux grandes cheminées, avec un feu de signal au sommet (une longue colonne de fumée)
        for (int cz : new int[] {-6, 6}) {
            for (int y = 1; y <= 17; y++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        boolean foyer = dx == -1 && dz == 0 && y <= 2;
                        boolean dedans = dx == 0 && dz == 0 && y <= 2;
                        if (foyer || dedans) {
                            continue;
                        }
                        pose(59 + dx, y, cz + dz, y > 11 && (dx == 0 && dz == 0) ? Blocks.HAY_BLOCK.defaultBlockState()
                                : (y + dx + dz) % 5 == 0 ? Blocks.BRICKS.defaultBlockState() : Blocks.NETHER_BRICKS.defaultBlockState());
                    }
                }
            }
            pose(59, 0, cz, Blocks.MAGMA_BLOCK.defaultBlockState());
            pose(59, 1, cz, Blocks.CAMPFIRE.defaultBlockState());
            pose(59, 18, cz, Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.SIGNAL_FIRE, true));
            pose(58, 0, cz, Blocks.MAGMA_BLOCK.defaultBlockState());
        }
        // le mur de lave, derrière une vitre, au fond de la halle
        for (int z = -2; z <= 2; z++) {
            for (int y = 1; y <= 5; y++) {
                pose(63, y, z, Blocks.BLACKSTONE.defaultBlockState());
                pose(61, y, z, Math.abs(z) <= 1 && y >= 2 && y <= 4 ? Blocks.GLASS.defaultBlockState() : mur);
            }
            pose(62, 0, z, Blocks.BLACKSTONE.defaultBlockState());
            pose(62, 6, z, Blocks.BLACKSTONE.defaultBlockState());
        }
        for (int y = 1; y <= 5; y++) {
            pose(62, y, -3, Blocks.BLACKSTONE.defaultBlockState());
            pose(62, y, 3, Blocks.BLACKSTONE.defaultBlockState());
        }
        for (int z = -2; z <= 2; z++) {
            for (int y = 1; y <= 5; y++) {
                pose(62, y, z, Math.abs(z) <= 1 && y >= 2 && y <= 4 ? Blocks.LAVA.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState());
            }
        }
        // l'enclume monumentale, au centre ; Brokk se tient derrière
        for (int z = -1; z <= 1; z++) {
            pose(56, 1, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
            pose(57, 1, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
            pose(56, 2, z, Blocks.IRON_BLOCK.defaultBlockState());
            pose(57, 2, z, Blocks.IRON_BLOCK.defaultBlockState());
        }
        pose(56, 3, 0, Blocks.ANVIL.defaultBlockState().setValue(net.minecraft.world.level.block.AnvilBlock.FACING, Direction.NORTH));
        pose(57, 3, 0, Blocks.ANVIL.defaultBlockState().setValue(net.minecraft.world.level.block.AnvilBlock.FACING, Direction.NORTH));
        pose(56, 3, -1, Blocks.CHAIN.defaultBlockState().setValue(net.minecraft.world.level.block.ChainBlock.AXIS, Direction.Axis.Z));
        // les postes de travail des orques
        for (int z : new int[] {-3, -2, 2, 3}) {
            pose(60, 1, z, Blocks.BLAST_FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST)
                    .setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT, true));
        }
        pose(60, 2, -3, Blocks.LAVA_CAULDRON.defaultBlockState());
        pose(60, 2, 3, Blocks.LAVA_CAULDRON.defaultBlockState());
        pose(55, 1, -7, Blocks.SMITHING_TABLE.defaultBlockState());
        pose(56, 1, -7, Blocks.ANVIL.defaultBlockState());
        pose(57, 1, -7, Blocks.CHIPPED_ANVIL.defaultBlockState());
        pose(54, 1, -7, Blocks.GRINDSTONE.defaultBlockState().setValue(net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE,
                net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        pose(55, 1, 7, Blocks.WATER_CAULDRON.defaultBlockState().setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3));
        pose(56, 1, 7, Blocks.WATER_CAULDRON.defaultBlockState().setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3));
        pose(57, 1, 7, Blocks.FLETCHING_TABLE.defaultBlockState());
        pose(54, 1, 7, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        pose(54, 2, 7, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        pose(60, 1, 8, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        pose(60, 1, -8, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        for (int z : new int[] {-8, -7, 7, 8}) {
            pose(58, 1, z, Blocks.IRON_BLOCK.defaultBlockState());
        }
        // la presse et le bassin de Create, et des engrenages sur la façade nord
        objet(55, 1, -4, "create:basin", null);
        objet(55, 3, -4, "create:mechanical_press", Direction.SOUTH);
        objet(55, 4, -4, "create:shaft", null);
        BlockState roue = modOuNull("create:large_cogwheel");
        if (roue != null) {
            pose(55, 4, -10, avec(roue, "axis", "z"));
            pose(59, 4, -10, avec(roue, "axis", "z"));
            pose(55, 4, 10, avec(roue, "axis", "z"));
            pose(59, 4, 10, avec(roue, "axis", "z"));
        }
        BlockState petite = modOuNull("create:cogwheel");
        if (petite != null) {
            pose(57, 5, -10, avec(petite, "axis", "z"));
            pose(57, 5, 10, avec(petite, "axis", "z"));
        }
        // chaînes et lanternes qui pendent du plafond
        for (int[] l : new int[][] {{55, -4}, {55, 4}, {58, -1}, {58, 1}, {55, 0}}) {
            pose(l[0], 6, l[1], Blocks.CHAIN.defaultBlockState());
            pose(l[0], 5, l[1], Blocks.CHAIN.defaultBlockState());
            pose(l[0], 4, l[1], Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        // les braseros de chaque côté de l'entrée
        for (int z : new int[] {-4, 4}) {
            pose(52, 0, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
            pose(52, 1, z, pilierNoir);
            pose(52, 2, z, pilierNoir);
            pose(52, 3, z, Blocks.CAMPFIRE.defaultBlockState());
        }
        // le râtelier : des armes accrochées aux murs
        net.minecraft.world.item.Item[] armes = {net.minecraft.world.item.Items.IRON_SWORD, net.minecraft.world.item.Items.IRON_AXE,
                net.minecraft.world.item.Items.SHIELD, net.minecraft.world.item.Items.DIAMOND_SWORD, net.minecraft.world.item.Items.IRON_HELMET,
                net.minecraft.world.item.Items.CROSSBOW, net.minecraft.world.item.Items.MACE};
        int a = 0;
        for (int x = 54; x <= 60; x += 1) {
            if (x == 55 || x == 59) {
                continue;
            }
            cadre(x, 3, -8, Direction.SOUTH, new ItemStack(armes[a++ % armes.length]));
            cadre(x, 3, 8, Direction.NORTH, new ItemStack(armes[a++ % armes.length]));
        }
        titre(52.5, 9.3, 0.5, "LA FORGE DE BROKK", "armes rares : parle à Brokk", 1.8F);
    }

    private void puits(int px, int pz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int m = Math.max(Math.abs(dx), Math.abs(dz));
                if (m == 2) {
                    pose(px + dx, 0, pz + dz, Blocks.STONE_BRICKS.defaultBlockState());
                } else if (m == 1) {
                    pose(px + dx, 0, pz + dz, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                    pose(px + dx, 1, pz + dz, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                } else {
                    for (int y = -3; y <= 0; y++) {
                        pose(px, y, pz, Blocks.WATER.defaultBlockState());
                    }
                }
            }
        }
        for (int dx : new int[] {-1, 1}) {
            for (int dz : new int[] {-1, 1}) {
                pose(px + dx, 2, pz + dz, Blocks.SPRUCE_FENCE.defaultBlockState());
                pose(px + dx, 3, pz + dz, Blocks.SPRUCE_FENCE.defaultBlockState());
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                pose(px + dx, 4, pz + dz, Blocks.SPRUCE_SLAB.defaultBlockState());
            }
        }
        pose(px, 3, pz, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    /** Les champs du village : blé, carottes, pommes de terre, betteraves, avec deux rigoles. */
    private void champs() {
        for (int x = 21; x <= 43; x++) {
            for (int z = 23; z <= 41; z++) {
                boolean bord = x == 21 || x == 43 || z == 23 || z == 41;
                if (bord) {
                    boolean porte = z == 23 && (x == 30 || x == 31);
                    pose(x, 1, z, porte ? AIR : Blocks.OAK_FENCE.defaultBlockState());
                    continue;
                }
                if (x == 30 || x == 31) {
                    pose(x, 0, z, Blocks.DIRT_PATH.defaultBlockState());
                    pose(x, 1, z, AIR);
                    continue;
                }
                if (z == 28 || z == 37) {
                    pose(x, 0, z, Blocks.WATER.defaultBlockState());
                    pose(x, 1, z, AIR);
                    continue;
                }
                Block culture = x < 30 ? (z < 32 ? Blocks.WHEAT : Blocks.CARROTS) : (z < 32 ? Blocks.POTATOES : Blocks.BEETROOTS);
                CropBlock plante = (CropBlock) culture;
                pose(x, 0, z, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
                pose(x, 1, z, plante.getStateForAge(rnd.nextInt(plante.getMaxAge() + 1)));
            }
        }
        // un épouvantail
        pose(36, 1, 33, Blocks.OAK_FENCE.defaultBlockState());
        pose(36, 2, 33, Blocks.HAY_BLOCK.defaultBlockState());
        pose(36, 3, 33, Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
    }

    // ------------------------------------------------------------------ abords : tour de guet, feu de camp, porte, palissade

    private void abords() {
        tourDeGuet(-40, 36);
        feuDeCamp(-20, 30);
        grandePorte();
        palissade();
        terrainCommercial(-44, -40, -32, -26);
        terrainCommercial(32, -40, 44, -26);
        terrainCommercial(-54, 18, -40, 30);
    }

    private void tourDeGuet(int tx, int tz) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean bord = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                pose(tx + dx, -1, tz + dz, Blocks.COBBLESTONE.defaultBlockState());
                pose(tx + dx, 0, tz + dz, briques());
                for (int y = 1; y <= 17; y++) {
                    boolean meurtriere = bord && (dx == 0 || dz == 0) && dx != -3 && y % 5 == 3;
                    pose(tx + dx, y, tz + dz, bord && !meurtriere ? briques() : AIR);
                }
                pose(tx + dx, 18, tz + dz, bord ? briques() : Blocks.SPRUCE_PLANKS.defaultBlockState());
                pose(tx + dx, 19, tz + dz, bord && (dx + dz) % 2 == 0 ? Blocks.STONE_BRICK_WALL.defaultBlockState() : AIR);
            }
        }
        for (int y = 1; y <= 2; y++) {
            pose(tx + 3, y, tz, AIR);
        }
        for (int y = 1; y <= 18; y++) {
            pose(tx - 2, y, tz, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST));
        }
        pose(tx + 1, 19, tz + 1, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, true));
        pose(tx + 1, 18, tz + 1, Blocks.HAY_BLOCK.defaultBlockState());
        pose(tx + 1, 19, tz - 1, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        coffre(tx + 1, 19, tz - 1, "coffres/tour");
        pose(tx, 1, tz + 2, Blocks.LANTERN.defaultBlockState());
        pose(tx - 1, 19, tz + 1, Blocks.LANTERN.defaultBlockState());
    }

    private void feuDeCamp(int fx, int fz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                pose(fx + dx, 0, fz + dz, (dx == 0 && dz == 0 ? Blocks.COBBLESTONE : Blocks.GRAVEL).defaultBlockState());
                pose(fx + dx, 1, fz + dz, AIR);
            }
        }
        pose(fx, 1, fz, Blocks.CAMPFIRE.defaultBlockState());
        for (int d = -1; d <= 1; d++) {
            pose(fx + 3, 1, fz + d, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.Z));
            pose(fx - 3, 1, fz + d, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.Z));
            pose(fx + d, 1, fz + 3, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.X));
            pose(fx + d, 1, fz - 3, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.X));
        }
        lampadaire(fx + 4, fz + 4);
        lampadaire(fx - 4, fz - 4);
    }

    /** La grande porte du village, au bout de l'allée sud. */
    private void grandePorte() {
        int z = 60;
        for (int sx : new int[] {-1, 1}) {
            for (int dx = 4; dx <= 6; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pose(sx * dx, -1, z + dz, Blocks.COBBLESTONE.defaultBlockState());
                    for (int y = 0; y <= 9; y++) {
                        pose(sx * dx, y, z + dz, briques());
                    }
                    pose(sx * dx, 10, z + dz, dx == 5 && dz == 0 ? Blocks.LANTERN.defaultBlockState() : Blocks.STONE_BRICK_WALL.defaultBlockState());
                }
            }
        }
        for (int x = -3; x <= 3; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                pose(x, 8, z + dz, pilier(Blocks.STRIPPED_ACACIA_LOG, Direction.Axis.X));
                pose(x, 9, z + dz, dz == 0 ? Blocks.ACACIA_PLANKS.defaultBlockState() : Blocks.ACACIA_SLAB.defaultBlockState());
            }
        }
        for (int x : new int[] {-2, 2}) {
            pose(x, 7, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
    }

    /** Une palissade tout autour, avec des piliers à lanterne et des passages. */
    private void palissade() {
        double r = RAYON - 1;
        int max = RAYON;
        double[] passages = {Math.PI / 4, 3 * Math.PI / 4, -Math.PI / 4, -3 * Math.PI / 4};
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (Math.abs(d - r) > 0.5 || (Math.abs(dx) <= 6 && dz > 0)) {
                    continue;
                }
                double angle = Math.atan2(dz, dx);
                boolean passage = false;
                for (double p : passages) {
                    double ecart = Math.abs(Math.atan2(Math.sin(angle - p), Math.cos(angle - p)));
                    passage |= ecart * r < 1.6;
                }
                if (passage || !level.getBlockState(c.offset(dx, 1, dz)).canBeReplaced()) {
                    continue;
                }
                boolean poteau = Math.floorMod((int) Math.round((angle + Math.PI) * r), 9) == 0;
                if (poteau) {
                    pose(dx, 1, dz, Blocks.STONE_BRICKS.defaultBlockState());
                    pose(dx, 2, dz, Blocks.STONE_BRICK_WALL.defaultBlockState());
                    pose(dx, 3, dz, Blocks.LANTERN.defaultBlockState());
                } else {
                    pose(dx, 1, dz, Blocks.OAK_FENCE.defaultBlockState());
                }
            }
        }
    }

    /** Remet de l'air (y0 à y1) sur un rectangle, sans compter ces blocs dans les finitions. */
    private void vider(int x0, int z0, int x1, int z1, int y0, int y1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockPos p = c.offset(x, y, z);
                    if (!level.getBlockState(p).isAir()) {
                        level.setBlock(p, AIR, FLAGS);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ décoration : les mods du pack (repli vanilla si un mod manque)

    /** Un bloc d'un mod de déco, ou {@code null} s'il n'est pas installé. */
    private static BlockState modOuNull(String id) {
        Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(id));
        return b == Blocks.AIR ? null : b.defaultBlockState();
    }

    private static BlockState deco(String id, BlockState repli) {
        BlockState s = modOuNull(id);
        return s == null ? repli : s;
    }

    /** Fixe une propriété par son nom (« facing », « lit »…), seulement si le bloc l'a. */
    private static BlockState avec(BlockState s, String nom, String valeur) {
        net.minecraft.world.level.block.state.properties.Property<?> p = s.getBlock().getStateDefinition().getProperty(nom);
        return p == null ? s : regler(s, p, valeur);
    }

    private static <T extends Comparable<T>> BlockState regler(BlockState s, net.minecraft.world.level.block.state.properties.Property<T> p, String valeur) {
        return p.getValue(valeur).map(v -> s.setValue(p, v)).orElse(s);
    }

    private static BlockState vers(BlockState s, Direction d) {
        return avec(s, "facing", d.getSerializedName());
    }

    /** Pose un objet de déco s'il existe ; sinon ne touche à rien. */
    private void objet(int x, int y, int z, String id, Direction face) {
        BlockState s = modOuNull(id);
        if (s != null) {
            pose(x, y, z, face == null ? s : vers(s, face));
        }
    }

    private void decorer() {
        // la grande salle : vraies tables, chaises, et de quoi manger dessus
        BlockState table = deco("handcrafted:spruce_table", Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        String[] plats = {"farmersdelight:roast_chicken_block", "farmersdelight:apple_pie", "farmersdelight:honey_glazed_ham_block", "farmersdelight:shepherds_pie_block"};
        int plat = 0;
        for (int x = -14; x <= 14; x++) {
            if (Math.abs(x) <= 1) {
                continue;
            }
            for (int z : new int[] {CZC - 3, CZC + 3}) {
                pose(x, 1, z, table);
                pose(x, 1, z - 1, vers(deco("handcrafted:spruce_chair", escalier(Blocks.SPRUCE_STAIRS, Direction.NORTH)), Direction.SOUTH));
                pose(x, 1, z + 1, vers(deco("handcrafted:spruce_chair", escalier(Blocks.SPRUCE_STAIRS, Direction.SOUTH)), Direction.NORTH));
                if (Math.abs(x) == 8) {
                    objet(x, 2, z, plats[plat++ % plats.length], Direction.SOUTH);
                } else if (Math.abs(x) == 5 || Math.abs(x) == 12) {
                    BlockState bougies = modOuNull("supplementaries:candle_holder");
                    if (bougies != null) {
                        pose(x, 2, z, avec(avec(avec(bougies, "candles", "3"), "lit", "true"), "face", "floor"));
                    }
                } else if (Math.floorMod(x, 2) == 0) {
                    objet(x, 2, z, x > 0 ? "handcrafted:wood_crockery_combo" : "handcrafted:white_crockery_combo", Direction.SOUTH);
                }
            }
        }
        // des lustres à la place des lanternes simples
        BlockState lustre = modOuNull("mcwlights:golden_chandelier");
        if (lustre != null) {
            for (int x = -12; x <= 12; x += 6) {
                for (int z : new int[] {CZC - 6, CZC + 6}) {
                    pose(x, 6, z, lustre);
                    pose(x, 5, z, AIR);
                }
            }
        }
        // appliques le long des murs de la grande salle et du dortoir, lanternes dans les combles
        BlockState applique = modOuNull("supplementaries:sconce_wall");
        if (applique != null) {
            for (int x : new int[] {-16, -10, 10, 16}) {
                for (int y : new int[] {4, 11}) {
                    pose(x, y, CZ0 + 1, avec(vers(applique, Direction.SOUTH), "lit", "true"));
                    pose(x, y, CZ1 - 1, avec(vers(applique, Direction.NORTH), "lit", "true"));
                }
            }
        }
        for (int x : new int[] {-11, -5, 5, 11}) {
            for (int z : new int[] {CZC - 3, CZC + 3}) {
                pose(x, 15, z, Blocks.LANTERN.defaultBlockState());
            }
        }
        // le coin cheminée : canapé, tapis, trophée au-dessus du foyer
        for (int z = CZC - 1; z <= CZC + 1; z++) {
            pose(18, 1, z, vers(deco("handcrafted:spruce_couch", escalier(Blocks.SPRUCE_STAIRS, Direction.WEST)), Direction.EAST));
            for (int x = 19; x <= 21; x++) {
                objet(x, 1, z, "farmersdelight:canvas_rug", null);
            }
        }
        objet(CX1 - 1, 3, CZC, "handcrafted:bear_trophy", Direction.WEST);
        // la cuisine
        BlockState fourneau = modOuNull("farmersdelight:stove");
        if (fourneau != null) {
            for (int z = CZ0 + 3; z <= CZ0 + 4; z++) {
                pose(CX1 - 1, 1, z, avec(vers(fourneau, Direction.WEST), "lit", "true"));
                objet(CX1 - 1, 3, z, "handcrafted:kitchen_hood", Direction.WEST);
            }
            objet(CX1 - 1, 2, CZ0 + 3, "farmersdelight:cooking_pot", Direction.WEST);
            objet(CX1 - 1, 2, CZ0 + 4, "farmersdelight:skillet", Direction.WEST);
        }
        objet(CX1 - 1, 1, CZ0 + 6, "handcrafted:spruce_counter", Direction.WEST);
        objet(CX1 - 1, 2, CZ0 + 6, "farmersdelight:cutting_board", Direction.WEST);
        objet(CX1 - 1, 1, CZ0 + 7, "farmersdelight:spruce_cabinet", Direction.WEST);
        objet(CX1 - 1, 2, CZ0 + 7, "handcrafted:berry_jam_jar", Direction.WEST);
        objet(CX1 - 1, 1, CZ0 + 1, "farmersdelight:carrot_crate", null);
        objet(CX1 - 1, 2, CZ0 + 1, "farmersdelight:potato_crate", null);
        objet(CX1 - 1, 1, CZ0 + 2, "farmersdelight:onion_crate", null);
        // la bibliothèque : bureau, globe, sablier, piles de livres
        objet(-17, 1, CZC - 3, "handcrafted:spruce_desk", Direction.WEST);
        objet(-18, 1, CZC - 3, "handcrafted:spruce_chair", Direction.EAST);
        objet(-17, 2, CZC - 3, "supplementaries:hourglass", Direction.UP);
        objet(-18, 1, CZ0 + 3, "supplementaries:globe", Direction.EAST);
        for (int z : new int[] {CZ0 + 8, CZC + 1, CZC + 6}) {
            BlockState livres = modOuNull("handcrafted:stackable_book");
            if (livres != null) {
                pose(CX0 + 2, 1, z, avec(livres, "books", "3"));
            }
        }
        // l'étage : tables de chevet et bougies à la place des tonneaux
        for (int x : new int[] {-15, -12, -9, -6, 6, 9, 12, 15}) {
            BlockState chevet = modOuNull("handcrafted:spruce_nightstand");
            if (chevet != null) {
                pose(x + 1, 8, CZ0 + 1, vers(chevet, Direction.SOUTH));
                BlockState bougie = modOuNull("supplementaries:candle_holder");
                if (bougie != null) {
                    pose(x + 1, 9, CZ0 + 1, avec(avec(avec(bougie, "candles", "1"), "lit", "true"), "face", "floor"));
                }
            }
        }
        // le porche : bancs, paillasson, lanternes de taverne
        for (int x : new int[] {-9, -8, 8, 9}) {
            pose(x, 1, CZ1 + 1, vers(deco("handcrafted:spruce_bench", escalier(Blocks.SPRUCE_STAIRS, Direction.NORTH)), Direction.SOUTH));
        }
        for (int x = -1; x <= 1; x++) {
            objet(x, 1, CZ1 + 1, "supplementaries:doormat", Direction.SOUTH);
        }
        BlockState taverne = modOuNull("mcwlights:tavern_lantern");
        if (taverne != null) {
            for (int x : new int[] {-13, -7, 7, 13}) {
                pose(x, 6, CZ1 + 2, avec(taverne, "hanging", "true"));
            }
        }
        // le Grand Bazar : panneaux d'annonces autour des contrats, horloge sur la façade, cageots chez Mouloud
        objet(-36, 1, -8, "supplementaries:notice_board", Direction.SOUTH);
        objet(-34, 1, -8, "supplementaries:notice_board", Direction.SOUTH);
        BlockState horloge = modOuNull("supplementaries:clock_block");
        if (horloge != null) {
            pose(BX1, 8, 0, avec(vers(horloge, Direction.EAST), "two_faced", "true"));
        }
        String[] cageots = {"farmersdelight:carrot_crate", "farmersdelight:potato_crate", "farmersdelight:beetroot_crate",
                "farmersdelight:cabbage_crate", "farmersdelight:onion_crate", "farmersdelight:tomato_crate"};
        int k = 0;
        for (int z : new int[] {-4, -3, 3, 4}) {
            objet(-60, 1, z, cageots[k++ % cageots.length], null);
        }
        objet(-60, 2, -4, cageots[k++ % cageots.length], null);
        objet(-60, 2, 4, cageots[k++ % cageots.length], null);
        objet(-59, 1, -5, "supplementaries:sack", null);
        objet(-59, 1, 5, "supplementaries:sack", null);
        objet(-52, 2, -4, "supplementaries:jar", null);
        objet(-52, 2, -2, "farmersdelight:apple_pie", Direction.EAST);
        objet(-52, 2, 2, "supplementaries:jar", null);
        objet(-52, 2, 4, "farmersdelight:wooden_basket", Direction.UP);
        objet(-52, 2, 1, "supplementaries:crystal_display", Direction.EAST);
        objet(BX1 + 2, 1, 7, "farmersdelight:carrot_crate", null);
        objet(BX1 + 2, 2, 7, "farmersdelight:tomato_crate", null);
        objet(BX1 + 3, 1, 8, "farmersdelight:straw_bale", null);
        objet(BX1 + 2, 1, -7, "farmersdelight:cabbage_crate", null);
        objet(BX1 + 3, 1, -8, "supplementaries:sack", null);
        BlockState fete = modOuNull("mcwlights:festive_lantern");
        if (fete != null) {
            for (int cx : new int[] {-56, -50, -44, -38}) {
                for (int cz : new int[] {-11, 11}) {
                    pose(cx, 3, cz, avec(fete, "hanging", "true"));
                }
            }
        }
        // la place : bancs face au grand panneau, panneaux d'annonces, girouette
        for (int s : new int[] {-1, 1}) {
            for (int x : new int[] {-4, 4}) {
                pose(x, 1, 16 * s, vers(deco("handcrafted:spruce_bench", escalier(Blocks.SPRUCE_STAIRS, s > 0 ? Direction.SOUTH : Direction.NORTH)),
                        s > 0 ? Direction.NORTH : Direction.SOUTH));
                pose(16 * s, 1, x, vers(deco("handcrafted:spruce_bench", escalier(Blocks.SPRUCE_STAIRS, s > 0 ? Direction.EAST : Direction.WEST)),
                        s > 0 ? Direction.WEST : Direction.EAST));
            }
        }
        objet(-6, 2, 2, "supplementaries:notice_board", Direction.SOUTH);
        objet(6, 2, 2, "supplementaries:notice_board", Direction.SOUTH);
        objet(0, 13, 0, "supplementaries:wind_vane", null);
        // la forge : un soufflet
        objet(60, 1, 2, "supplementaries:bellows", Direction.NORTH);
        enclos();
    }

    // ------------------------------------------------------------------ la vie : arbres, buissons, animaux

    private void vie() {
        arbres();
        buissons();
        lianes();
        animauxDeLEnclos();
        animal(EntityType.CAT, 30, -4);
        animal(EntityType.CAT, 41, 5);
        animal(EntityType.CAT, -2, -31);
    }

    private void arbres() {
        var features = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        // de grands arbres de jungle sur le pourtour, de plus petits le long de la grande allée
        int[][] geants = {{-58, 6}, {-55, -19}, {-52, -34}, {52, -32}, {26, -50}, {-29, -50}, {-26, 54}, {56, 14}, {12, -56}, {-12, -56}, {48, 34}, {-46, 44}};
        int[][] petits = {{-10, 30}, {10, 34}, {-10, 48}, {10, 52}, {16, 22}, {-14, 22}, {-24, 46}, {26, 48}};
        for (int[] g : geants) {
            planter(features, net.minecraft.data.worldgen.features.TreeFeatures.MEGA_JUNGLE_TREE, g[0], g[1], true);
        }
        for (int[] pt : petits) {
            planter(features, net.minecraft.data.worldgen.features.TreeFeatures.JUNGLE_TREE, pt[0], pt[1], false);
        }
        // une couronne d'arbres géants (tronc 4 x 4) juste derrière la palissade, sauf devant la grande porte
        for (int k = 0; k < 14; k++) {
            double angle = Math.toRadians(8 + k * 360.0 / 14);
            if (Math.abs(Math.toDegrees(angle) - 90) < 22) {
                continue;
            }
            double r = 76 + rnd.nextInt(8);
            int x = (int) Math.round(Math.cos(angle) * r);
            int z = (int) Math.round(Math.sin(angle) * r);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX() + x, c.getZ() + z);
            fr.cabane.monde.ArbreGeant.planter(level, new BlockPos(c.getX() + x, y, c.getZ() + z), rnd, 1.1F);
        }
    }

    private void planter(net.minecraft.core.Registry<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> features,
                         net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> arbre, int x, int z, boolean geant) {
        for (int dx = 0; dx <= (geant ? 1 : 0); dx++) {
            for (int dz = 0; dz <= (geant ? 1 : 0); dz++) {
                BlockPos sol = c.offset(x + dx, 0, z + dz);
                if (!level.getBlockState(sol).is(Blocks.GRASS_BLOCK) && !level.getBlockState(sol).is(Blocks.COARSE_DIRT)) {
                    return;
                }
                level.setBlock(sol, Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                level.setBlock(sol.above(), AIR, FLAGS);
            }
        }
        features.getHolder(arbre).ifPresent(h -> h.value().place(level, level.getChunkSource().getGenerator(), rnd, c.offset(x, 1, z)));
    }

    /** Des buissons et des fleurs hautes dans l'herbe, hors des parcelles et de l'enclos. */
    private void buissons() {
        for (int essai = 0; essai < 320; essai++) {
            int x = rnd.nextInt(2 * RAYON - 9) - RAYON + 5;
            int z = rnd.nextInt(2 * RAYON - 9) - RAYON + 5;
            if (x * x + z * z > (RAYON - 6) * (RAYON - 6) || (x * x + z * z) < 22 * 22
                    || dans(x, z, -45, -41, -31, -25) || dans(x, z, 31, -41, 45, -25) || dans(x, z, 43, 17, 53, 27)) {
                continue;
            }
            BlockPos sol = c.offset(x, 0, z);
            if (!level.getBlockState(sol).is(Blocks.GRASS_BLOCK) || !level.getBlockState(sol.above()).canBeReplaced()
                    || !level.getBlockState(sol.above(2)).isAir()) {
                continue;
            }
            int choix = rnd.nextInt(10);
            if (choix >= 6) {
                jungle(x, z, choix);
                continue;
            }
            if (choix <= 2) {
                Block feuilles = choix == 0 ? Blocks.OAK_LEAVES : choix == 1 ? Blocks.AZALEA_LEAVES : Blocks.FLOWERING_AZALEA_LEAVES;
                BlockState s = feuilles.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
                pose(x, 1, z, s);
                if (rnd.nextBoolean()) {
                    pose(x, 2, z, s);
                }
            } else if (choix == 3) {
                pose(x, 1, z, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3));
            } else {
                Block fleur = choix == 4 ? Blocks.ROSE_BUSH : Blocks.LILAC;
                net.minecraft.world.level.block.DoublePlantBlock.placeAt(level, fleur.defaultBlockState(), sol.above(), FLAGS);
            }
        }
    }

    /** Un peu de jungle : buisson de jungle, fougères, grande fougère, bambous. */
    private void jungle(int x, int z, int choix) {
        BlockPos sol = c.offset(x, 0, z);
        switch (choix) {
            case 6 -> level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                    .getHolder(net.minecraft.data.worldgen.features.TreeFeatures.JUNGLE_BUSH)
                    .ifPresent(h -> h.value().place(level, level.getChunkSource().getGenerator(), rnd, sol.above()));
            case 7 -> pose(x, 1, z, Blocks.FERN.defaultBlockState());
            case 8 -> net.minecraft.world.level.block.DoublePlantBlock.placeAt(level, Blocks.LARGE_FERN.defaultBlockState(), sol.above(), FLAGS);
            default -> {
                int h = 3 + rnd.nextInt(4);
                for (int y = 1; y <= h; y++) {
                    pose(x, y, z, Blocks.BAMBOO.defaultBlockState().setValue(net.minecraft.world.level.block.BambooStalkBlock.LEAVES,
                            y >= h - 1 ? net.minecraft.world.level.block.state.properties.BambooLeaves.LARGE : net.minecraft.world.level.block.state.properties.BambooLeaves.NONE));
                }
            }
        }
    }

    /**
     * Des lianes partout : sur les murs, et qui pendent des avant-toits. Une passe sur tout le village,
     * après les arbres.
     */
    private void lianes() {
        Direction[] cotes = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (int dx = -RAYON + 2; dx <= RAYON - 2; dx++) {
            for (int dz = -RAYON + 2; dz <= RAYON - 2; dz++) {
                if (dx * dx + dz * dz > (RAYON - 2) * (RAYON - 2) || dx * dx + dz * dz < 20 * 20) {
                    continue;
                }
                for (int y = 2; y <= 22; y++) {
                    if (rnd.nextFloat() > 0.05F) {
                        continue;
                    }
                    BlockPos p = c.offset(dx, y, dz);
                    if (!level.getBlockState(p).isAir()) {
                        continue;
                    }
                    for (Direction d : cotes) {
                        BlockState mur = level.getBlockState(p.relative(d));
                        if (mur.isFaceSturdy(level, p.relative(d), d.getOpposite()) && !mur.is(BlockTags.LEAVES)) {
                            BlockState liane = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d), true);
                            int longueur = 1 + rnd.nextInt(4);
                            for (int k = 0; k < longueur && level.getBlockState(p.below(k)).isAir() && p.below(k).getY() > c.getY() + 1; k++) {
                                level.setBlock(p.below(k), liane, FLAGS);
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    private static boolean dans(int x, int z, int x0, int z0, int x1, int z1) {
        return x >= x0 && x <= x1 && z >= z0 && z <= z1;
    }

    /** Un enclos près des champs (ses animaux arrivent après les finitions). */
    private void enclos() {
        int x0 = 44, x1 = 52, z0 = 18, z1 = 26;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean bord = x == x0 || x == x1 || z == z0 || z == z1;
                pose(x, 0, z, Blocks.GRASS_BLOCK.defaultBlockState());
                if (x == 48 && z == z0) {
                    pose(x, 1, z, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, Direction.NORTH));
                } else {
                    pose(x, 1, z, bord ? Blocks.OAK_FENCE.defaultBlockState() : AIR);
                }
            }
        }
        pose(x1 - 1, 1, z1 - 1, Blocks.HAY_BLOCK.defaultBlockState());
        pose(x1 - 2, 1, z1 - 1, Blocks.HAY_BLOCK.defaultBlockState());
        pose(x0 + 1, 1, z1 - 1, Blocks.WATER_CAULDRON.defaultBlockState().setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3));
        pose(x0 + 2, 1, z1 - 1, Blocks.WATER_CAULDRON.defaultBlockState().setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3));
    }

    private void animauxDeLEnclos() {
        for (int i = 0; i < 3; i++) {
            animal(EntityType.COW, 46 + i, 21);
            animal(EntityType.SHEEP, 46 + i, 23);
        }
        animal(EntityType.PIG, 50, 21);
        animal(EntityType.PIG, 50, 23);
        for (int i = 0; i < 4; i++) {
            animal(EntityType.CHICKEN, 45 + i * 2, 20);
        }
    }

    private void animal(EntityType<? extends net.minecraft.world.entity.Mob> type, int x, int z) {
        net.minecraft.world.entity.Mob m = type.create(level);
        if (m == null) {
            return;
        }
        m.moveTo(c.getX() + x + 0.5, c.getY() + 1, c.getZ() + z + 0.5, rnd.nextFloat() * 360F, 0F);
        m.setPersistenceRequired();
        m.addTag(TAG);
        level.addFreshEntity(m);
    }

    /**
     * Recalcule la lumière de tout le village : après avoir rasé la jungle et posé des milliers de blocs,
     * sinon des zones restent noires (la lumière du ciel n'y est pas revenue).
     */
    private void lumiere() {
        var moteur = level.getChunkSource().getLightEngine();
        int max = RAYON + FONDU;
        for (int cx = (c.getX() - max) >> 4; cx <= (c.getX() + max) >> 4; cx++) {
            for (int cz = (c.getZ() - max) >> 4; cz <= (c.getZ() + max) >> 4; cz++) {
                moteur.lightChunk(level.getChunk(cx, cz), false);
            }
        }
    }

    // ------------------------------------------------------------------ la salle d'embarquement

    /** Où attendent les joueurs avant le /start : au milieu de la salle d'embarquement. */
    public static BlockPos salleEmbarquement(BlockPos centre) {
        return centre.offset(-8, hauteurAvion(centre) + 1, -23);
    }

    /** La vitre qui ferme la passerelle jusqu'au départ. */
    public static java.util.List<BlockPos> porteEmbarquement(BlockPos centre) {
        int a = hauteurAvion(centre);
        java.util.List<BlockPos> l = new java.util.ArrayList<>();
        for (int x = -8; x <= -7; x++) {
            for (int y = 1; y <= 3; y++) {
                l.add(centre.offset(x, a + y, -3));
            }
        }
        return l;
    }

    /**
     * Une salle d'embarquement d'aéroport, au bout d'une passerelle fermée qui mène à l'avion. Les
     * joueurs y attendent que l'hôte tape /start ; alors la vitre s'ouvre et tout le monde embarque.
     */
    private void embarquement() {
        int a = hauteurAvion(c);
        BlockState sol = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        BlockState mur = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState vitre = Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState();
        // la passerelle : un couloir vitré jusqu'à une porte de l'avion
        for (int z = -13; z <= -3; z++) {
            for (int x = -9; x <= -6; x++) {
                boolean paroi = x == -9 || x == -6;
                pose(x, a, z, Blocks.GRAY_CONCRETE.defaultBlockState());
                for (int y = 1; y <= 3; y++) {
                    pose(x, a + y, z, paroi ? (y == 2 ? vitre : Blocks.GRAY_CONCRETE.defaultBlockState()) : AIR);
                }
                pose(x, a + 4, z, Blocks.GRAY_CONCRETE.defaultBlockState());
            }
        }
        for (int x = -8; x <= -7; x++) {
            pose(x, a + 1, -2, AIR);
            pose(x, a + 2, -2, AIR);
        }
        for (BlockPos p : porteEmbarquement(c)) {
            level.setBlock(p, Blocks.WHITE_STAINED_GLASS.defaultBlockState(), FLAGS);
        }
        pose(-8, a + 3, -8, Blocks.SEA_LANTERN.defaultBlockState());
        // la salle
        for (int x = -18; x <= 2; x++) {
            for (int z = -30; z <= -14; z++) {
                boolean bord = x == -18 || x == 2 || z == -30 || z == -14;
                pose(x, a, z, bord ? mur : (x + z) % 2 == 0 ? sol : Blocks.POLISHED_DIORITE.defaultBlockState());
                for (int y = 1; y <= 6; y++) {
                    BlockState s = AIR;
                    if (bord) {
                        boolean coin = (x == -18 || x == 2) && (z == -30 || z == -14);
                        s = coin || y == 1 || y == 6 ? mur : vitre;
                    }
                    pose(x, a + y, z, s);
                }
                pose(x, a + 7, z, bord || (x % 4 != 0 || z % 4 != 0) ? mur : Blocks.SEA_LANTERN.defaultBlockState());
            }
        }
        for (int x = -8; x <= -7; x++) {
            for (int y = 1; y <= 3; y++) {
                pose(x, a + y, -14, AIR);
            }
        }
        // des rangées de sièges, des plantes, un tapis vers la porte
        for (int x = -16; x <= 0; x++) {
            if (x == -8 || x == -7) {
                continue;
            }
            for (int z : new int[] {-27, -24, -21}) {
                pose(x, a + 1, z, escalier(Blocks.QUARTZ_STAIRS, Direction.NORTH));
            }
        }
        for (int z = -29; z <= -15; z++) {
            pose(-8, a + 1, z, Blocks.BLUE_CARPET.defaultBlockState());
            pose(-7, a + 1, z, Blocks.BLUE_CARPET.defaultBlockState());
        }
        for (int[] pt : new int[][] {{-17, -29}, {1, -29}, {-17, -15}, {1, -15}}) {
            pose(pt[0], a + 1, pt[1], Blocks.POTTED_BAMBOO.defaultBlockState());
        }
        // le tableau des départs, au fond
        for (int x = -12; x <= -3; x++) {
            for (int y = 3; y <= 5; y++) {
                pose(x, a + y, -29, Blocks.BLACK_CONCRETE.defaultBlockState());
            }
        }
        texte(-7.0, a + 4.9, -28.4, 180F, "{\"text\":\"DÉPARTS\",\"color\":\"yellow\",\"bold\":true}", 0.9F, 0x00000000, false);
        texte(-7.0, a + 3.4, -28.4, 180F, "[{\"text\":\"BV2026   BEAUVAIS   PORTE 12\\n\",\"color\":\"white\"},{\"text\":\"EMBARQUEMENT : DÈS LE /start\",\"color\":\"green\"}]", 0.6F, 0x00000000, false);
        texte(-7.0, a + 5.1, -14.6, 180F, "{\"text\":\"PORTE 12 — VOL BV2026 POUR BEAUVAIS\",\"color\":\"gold\",\"bold\":true}", 0.9F, 0x00000000, false);
        texte(-7.0, a + 4.3, -14.6, 180F, "{\"text\":\"L'hôte ouvre l'embarquement en tapant /start\",\"color\":\"white\"}", 0.6F, 0x00000000, false);
    }

    // ------------------------------------------------------------------ textes flottants

    /** Un titre doré, comme sur la porte, qui fait face à celui qui regarde. */
    private void titre(double x, double y, double z, String texte, String sousTitre, float echelle) {
        String json = sousTitre.isEmpty()
                ? "{\"text\":\"" + texte + "\",\"color\":\"gold\",\"bold\":true}"
                : "[{\"text\":\"" + texte + "\n\",\"color\":\"gold\",\"bold\":true},{\"text\":\"" + sousTitre + "\",\"color\":\"white\",\"bold\":false}]";
        texte(x, y, z, 0F, json, echelle, 0x00000000, true);
    }

    private void textes() {
        titre(31, 3.6, 22.5, "LES CHAMPS", "blé, carottes, pommes de terre, betteraves", 1.1F);
        titre(48.5, 3.2, 17.5, "L'ENCLOS", "", 1.1F);
        titre(-20.5, 3.6, 25.5, "LE FEU DE CAMP", "on s'y raconte les donjons lointains", 1.0F);
        titre(-40.5, 22.5, 36.5, "LA TOUR DE GUET", "", 1.3F);
        titre(36.5, 6.2, 18.5, "LE PUITS", "", 0.9F);
        titre(-34.5, 6.4, -8.4, "PÔLE EMPLOI", "les contrats de l'heure", 1.0F);
        texte(0.5, 15.5, CZ1 + 1.6, 180F, "{\"text\":\"LA GRANDE CABANE\",\"color\":\"gold\",\"bold\":true}", 3.5F, 0x55000000, false);
        texte(BX1 + 1.2, 12.5, 0.5, 90F, "{\"text\":\"GRAND BAZAR\",\"color\":\"gold\",\"bold\":true}", 3.5F, 0x55000000, false);
        texte(0.5, 11.4, 61.6, 180F, "{\"text\":\"BEAUVAIS\",\"color\":\"gold\",\"bold\":true}", 2.5F, 0x00000000, false);
        texte(0.5, 11.4, 58.4, 0F, "{\"text\":\"BEAUVAIS\",\"color\":\"gold\",\"bold\":true}", 2.5F, 0x00000000, false);
        texte(-1.5, hauteurAvion(c) + 2.2, 0.5, 90F, "[{\"text\":\"MAYDAY ! L'AVION EST EN FEU !\\n\",\"color\":\"red\",\"bold\":true},{\"text\":\"Prends un parachute dans le coffre au fond\\npuis saute par la porte !\",\"color\":\"white\",\"bold\":false}]", 0.5F, 0xAA000000, false);
    }

    // ------------------------------------------------------------------ cartes

    /**
     * Colorie une carte d'après les biomes, un échantillon pour 2x2 ou 4x4 pixels. Avec les mods de biomes,
     * chaque échantillon coûte cher : le calcul se fait sur un autre fil, et les couleurs sont posées
     * sur la carte par le serveur une fois prêtes.
     */
    static void dessinerCarte(ServerLevel level, ItemStack carte) {
        MapItemSavedData data = MapItem.getSavedData(carte, level);
        if (data == null) {
            return;
        }
        int echelle = 1 << data.scale;
        int pas = data.scale >= 1 ? 4 : 2;
        int y = level.getSeaLevel() + 8;
        int cx = data.centerX;
        int cz = data.centerZ;
        var biomes = level.getChunkSource().getGenerator().getBiomeSource();
        var echantillon = level.getChunkSource().randomState().sampler();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            byte[] couleurs = new byte[128 * 128];
            for (int px = 0; px < 128; px += pas) {
                for (int pz = 0; pz < 128; pz += pas) {
                    int x = cx + (px - 64) * echelle;
                    int z = cz + (pz - 64) * echelle;
                    Holder<Biome> biome = biomes.getNoiseBiome(x >> 2, y >> 2, z >> 2, echantillon);
                    net.minecraft.world.level.material.MapColor couleur = couleurBiome(biome);
                    int bruit = Math.floorMod(x * 734287 ^ z * 912931, 7);
                    var luminosite = bruit == 0 ? net.minecraft.world.level.material.MapColor.Brightness.LOW
                            : bruit == 1 ? net.minecraft.world.level.material.MapColor.Brightness.HIGH
                            : net.minecraft.world.level.material.MapColor.Brightness.NORMAL;
                    byte octet = couleur.getPackedId(luminosite);
                    for (int a = 0; a < pas; a++) {
                        for (int b = 0; b < pas; b++) {
                            couleurs[(px + a) + (pz + b) * 128] = octet;
                        }
                    }
                }
            }
            return couleurs;
        }, net.minecraft.Util.backgroundExecutor()).thenAcceptAsync(couleurs -> {
            for (int px = 0; px < 128; px++) {
                for (int pz = 0; pz < 128; pz++) {
                    data.setColor(px, pz, couleurs[px + pz * 128]);
                }
            }
        }, level.getServer());
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
    public static BlockPos dansAvion(BlockPos centre) {
        return centre.offset(4, hauteurAvion(centre) + 1, 0);
    }
    /** Le coffre aux parachutes. */
    public static BlockPos coffreAvion(BlockPos centre) {
        return centre.offset(-9, hauteurAvion(centre) + 1, 1);
    }

    private void avion() {
        int a = hauteurAvion(c);
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
        pose(-9, a + 1, 1, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST));
        pose(-9, a + 1, -1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        for (int x = 0; x <= 1; x++) {
            pose(x, a + 1, -2, AIR);
            pose(x, a + 2, -2, AIR);
        }
        for (int x = -8; x <= 10; x += 6) {
            pose(x, a + 3, 0, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true));
        }
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
                "Journal du Hejj\n\nMayday… votre avion s'est écrasé au-dessus de la jungle. Le village de Beauvais accepte de vous héberger dans cette Grande Cabane.",
                "En échange, aidez-le : les contrats du marché, les quêtes du tableau. Le village paie en SakaiDollars.",
                "Autrefois, la Confrérie des Explorateurs vivait ici. Sept reliques reposaient sur ce comptoir, puis ont été cachées au cœur de donjons lointains.",
                "Le Scarabée d'Or, sous le sable.\nL'Idole de Jade, dans la jungle.\nLa Perle des Abysses, sous la mer.\nLa Plume Céleste, dans le ciel.",
                "La Couronne Illageoise, chez nos ennemis.\nL'Œil du Gardien, sous la terre.\nL'Engrenage Ancien, dans les fonderies.\n\nRamenez-les.\n— Le Hejj du Village")
                .stream().map(t -> Filterable.passThrough((Component) Component.literal(t))).toList();
        ItemStack livre = new ItemStack(Items.WRITTEN_BOOK);
        livre.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough("Journal du Hejj"), "Le Hejj du Village", 0, pages, true));
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
