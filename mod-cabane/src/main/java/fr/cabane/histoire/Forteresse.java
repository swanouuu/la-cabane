package fr.cabane.histoire;

import fr.cabane.Cabane;
import fr.cabane.CabaneBlocks;
import fr.cabane.hub.HubBuilder;
import fr.cabane.hub.HubData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Le PLATO 6Z, repaire des Pillards et refuge du Président, à 10 000 blocs plein sud du village :
 * une enceinte de 116 blocs, des tours d'angle, une grande porte scellée, un donjon de 55 blocs de haut et,
 * dedans, la Salle Zéro où attend le combat final. Pour l'instant, on ne peut pas y entrer (« c'est pour plus
 * tard ») : l'hôte l'ouvrira avec /cabane forteresse ouvrir. Elle se construit tranche par tranche, sans
 * jamais bloquer le serveur.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Forteresse {
    public static final int DISTANCE = 10_000;
    private static final int DEMI = 58;
    private static final int MUR = 48;
    /** Les pentes autour de la plate-forme, l'avenue qui mène à la porte (côté nord, vers le village). */
    private static final int PENTE = 30;
    private static final int LARGEUR_AVENUE = 9;
    private static final int AVENUE = 96;
    /** Jusqu'où va le chantier autour du centre (et vers le nord, avec l'avenue). */
    private static final int BORD = DEMI + 2 + PENTE + 4;
    private static final int BORD_NORD = DEMI + 2 + AVENUE + 4;
    /** L'écart entre deux sites essayés. */
    private static final int PAS_SITE = 240;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static Chantier chantier;

    /** Le pupitre de la Salle Zéro, par rapport au centre de la forteresse (au niveau du sol). */
    public static BlockPos pupitre(MinecraftServer server) {
        BlockPos f = HubData.get(server).forteresse();
        return f == null ? null : f.offset(0, 2, 0);
    }

    /** Lance la construction ; sans « refaire », seulement si elle n'existe pas encore. */
    public static void lancer(ServerLevel level, boolean refaire) {
        HubData data = HubData.get(level.getServer());
        if (data.centre() == null || chantier != null || (data.forteresse() != null && !refaire)) {
            return;
        }
        BlockPos centre = data.centre();
        int x = centre.getX();
        int z = centre.getZ() + DISTANCE;
        chantier = new Chantier(level, x, z);
        Cabane.LOGGER.info("PLATO 6Z : construction lancée en {} {}", x, z);
    }

    @SubscribeEvent
    public static void onStart(ServerStartedEvent event) {
        chantier = null;
    }

    /** Lève (ou rabaisse) la herse et le mur de la grande porte. Faux si la forteresse n'existe pas encore. */
    /**
     * Les coffres au trésor : quatre dans les coins de la Salle Zéro, et des tonneaux pleins dans le camp des Pillards.
     * Les tables de butin sont dans data/cabane/loot_table/coffres/ (Lootr en fait un butin par joueur).
     */
    public static int coffres(ServerLevel level) {
        BlockPos f = HubData.get(level.getServer()).forteresse();
        if (f == null) {
            return 0;
        }
        for (int cx = (f.getX() - 45) >> 4; cx <= (f.getX() + 45) >> 4; cx++) {
            for (int cz = (f.getZ() - 45) >> 4; cz <= (f.getZ() + 45) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        int n = 0;
        for (int[] c : new int[][] {{-13, -13}, {13, -13}, {-13, 13}, {13, 13}}) {
            BlockPos p = f.offset(c[0], 1, c[1]);
            level.setBlock(p, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, c[1] < 0 ? Direction.SOUTH : Direction.NORTH), 3);
            n += butin(level, p, "plato_tresor");
        }
        for (int[] t : new int[][] {{-32, -30}, {30, -28}, {-30, 30}, {32, 32}, {-36, 2}, {36, -4}}) {
            BlockPos p = f.offset(t[0] + 2, 1, t[1] + 3);
            if (!level.getBlockState(p).is(Blocks.BARREL)) {
                level.setBlock(p, Blocks.BARREL.defaultBlockState(), 3);
            }
            n += butin(level, p, "plato_camp");
        }
        return n;
    }

    /** Remplit un coffre ou un tonneau avec une table de butin du mod. */
    public static int butin(ServerLevel level, BlockPos p, String table) {
        if (level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity coffre) {
            coffre.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
                    Cabane.id("coffres/" + table)), level.random.nextLong());
            coffre.setChanged();
            return 1;
        }
        return 0;
    }

    public static boolean porte(ServerLevel level, boolean ouverte) {
        HubData data = HubData.get(level.getServer());
        BlockPos f = data.forteresse();
        if (f == null) {
            return false;
        }
        data.setForteresseOuverte(ouverte);
        for (int x = -7; x <= 7; x++) {
            for (int y = 1; y <= 20; y++) {
                if (y > 16 && Math.abs(x) > 7 - (y - 16) * 2) {
                    continue;
                }
                level.setBlock(f.offset(x, y, -MUR - 1), ouverte ? Blocks.AIR.defaultBlockState() : Blocks.IRON_BARS.defaultBlockState(), 3);
                level.setBlock(f.offset(x, y, -MUR), ouverte ? Blocks.AIR.defaultBlockState() : Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), 3);
            }
        }
        level.playSound(null, f.offset(0, 2, -MUR - 4), ouverte ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 4F, 0.5F);
        return true;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (chantier != null) {
            try {
                if (chantier.etape()) {
                    chantier = null;
                }
            } catch (RuntimeException e) {
                Cabane.LOGGER.error("PLATO 6Z : construction interrompue", e);
                chantier.forcer(false);
                chantier = null;
            }
        } else if (event.getServer().getTickCount() % 400 == 200) {
            // une fois le village construit, la forteresse se bâtit d'elle-même, très loin au sud
            lancer(event.getServer().overworld(), false);
        }
    }

    /** Tant qu'elle est scellée, qui entre dans la forteresse est reconduit dehors. */
    @SubscribeEvent
    public static void onJoueur(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer joueur) || joueur.tickCount % 10 != 0 || joueur.isCreative() || joueur.isSpectator()) {
            return;
        }
        HubData data = HubData.get(joueur.server);
        BlockPos f = data.forteresse();
        if (f == null || data.forteresseOuverte() || joueur.level() != joueur.server.overworld()) {
            return;
        }
        double dx = joueur.getX() - f.getX() - 0.5, dz = joueur.getZ() - f.getZ() - 0.5, dy = joueur.getY() - f.getY();
        if (Math.abs(dx) <= DEMI && Math.abs(dz) <= DEMI && dy > -12 && dy < 90) {
            ServerLevel level = joueur.serverLevel();
            int sx = f.getX(), sz = f.getZ() - DEMI - 8;
            int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
            joueur.teleportTo(sx + 0.5, sy, sz + 0.5);
            joueur.displayClientMessage(Component.literal("🔒 Le PLATO 6Z est scellé… c'est pour plus tard !").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), true);
            level.playSound(null, joueur.blockPosition(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1F, 0.6F);
        }
    }

    /** La construction, une tranche par tick. */
    private static final class Chantier {
        private final ServerLevel level;
        private final int x0;
        private final int z0;
        private int fx;
        private int fz;
        private final RandomSource rnd;
        /** Les sites possibles, évalués un par tick d'après le relief que le générateur prévoit (sans rien générer). */
        private final List<int[]> sites = new ArrayList<>();
        private int site;
        private int meilleur = Integer.MAX_VALUE;
        private final List<Runnable> etapes = new ArrayList<>();
        private int index;
        private int base;
        private boolean charge;

        Chantier(ServerLevel level, int x0, int z0) {
            this.level = level;
            this.x0 = x0;
            this.z0 = z0;
            this.fx = x0;
            this.fz = z0;
            this.rnd = RandomSource.create(x0 * 31L + z0);
            for (int j = -2; j <= 2; j++) {
                for (int k = -4; k <= 4; k++) {
                    sites.add(new int[] {x0 + k * PAS_SITE, z0 + j * PAS_SITE});
                }
            }
        }

        /** Les chunks du chantier : la forteresse, ses pentes, et l'avenue au nord. */
        private void forcer(boolean oui) {
            for (int cx = (fx - BORD) >> 4; cx <= (fx + BORD) >> 4; cx++) {
                for (int cz = (fz - BORD_NORD) >> 4; cz <= (fz + BORD) >> 4; cz++) {
                    level.setChunkForced(cx, cz, oui);
                }
            }
        }

        /** Un bon site : un terrain assez plat, ni noyé ni perché dans les nuages, pas trop loin de plein sud. */
        private int note(int x, int z) {
            var generateur = level.getChunkSource().getGenerator();
            var aleas = level.getChunkSource().randomState();
            List<Integer> h = new ArrayList<>();
            int eau = 0;
            for (int dx = -DEMI; dx <= DEMI; dx += 29) {
                for (int dz = -DEMI; dz <= DEMI; dz += 29) {
                    int y = generateur.getBaseHeight(x + dx, z + dz, Heightmap.Types.OCEAN_FLOOR_WG, level, aleas);
                    if (y < level.getSeaLevel() - 1) {
                        eau++;
                    }
                    h.add(y);
                }
            }
            h.sort(Integer::compare);
            int mediane = h.get(h.size() / 2);
            return (h.get(h.size() - 1) - h.get(0)) * 2 + Math.abs(mediane - 85) + eau * 25 + (Math.abs(x - x0) + Math.abs(z - z0)) / 100;
        }

        /** Vrai quand c'est fini. */
        boolean etape() {
            if (site < sites.size()) {
                int[] s = sites.get(site++);
                int n = note(s[0], s[1]);
                if (n < meilleur) {
                    meilleur = n;
                    fx = s[0];
                    fz = s[1];
                }
                if (site == sites.size()) {
                    Cabane.LOGGER.info("PLATO 6Z : site choisi en {} {} (note {})", fx, fz, meilleur);
                    forcer(true);
                }
                return false;
            }
            if (!charge) {
                // on attend que les chunks soient générés (en arrière-plan)
                for (int cx = (fx - BORD) >> 4; cx <= (fx + BORD) >> 4; cx++) {
                    for (int cz = (fz - BORD_NORD) >> 4; cz <= (fz + BORD) >> 4; cz++) {
                        if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                            return false;
                        }
                    }
                }
                charge = true;
                base = niveau();
                prevoir();
                return false;
            }
            if (index < etapes.size()) {
                etapes.get(index++).run();
                return false;
            }
            forcer(false);
            HubData.get(level.getServer()).setForteresse(new BlockPos(fx, base, fz));
            coffres(level);
            Cabane.LOGGER.info("PLATO 6Z construit en {} {} {}", fx, base, fz);
            return true;
        }

        /** Le niveau du sol : la médiane du terrain (sans les arbres), jamais sous l'eau. */
        private int niveau() {
            List<Integer> h = new ArrayList<>();
            for (int dx = -DEMI; dx <= DEMI; dx += 8) {
                for (int dz = -DEMI; dz <= DEMI; dz += 8) {
                    BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(fx + dx, level.getHeight(Heightmap.Types.OCEAN_FLOOR, fx + dx, fz + dz), fz + dz);
                    BlockState ici = level.getBlockState(p);
                    while (p.getY() > level.getMinBuildHeight() && (!ici.isSolid() || ici.is(BlockTags.LEAVES) || ici.is(BlockTags.LOGS))) {
                        p.move(Direction.DOWN);
                        ici = level.getBlockState(p);
                    }
                    h.add(p.getY() + 1);
                }
            }
            h.sort(Integer::compare);
            return Math.max(level.getSeaLevel() + 3, h.get(h.size() / 2));
        }

        private void pose(int dx, int dy, int dz, BlockState etat) {
            level.setBlock(new BlockPos(fx + dx, base + dy, fz + dz), etat, FLAGS);
        }

        private BlockState pierre() {
            float r = rnd.nextFloat();
            return (r < 0.55F ? Blocks.DEEPSLATE_BRICKS : r < 0.75F ? Blocks.POLISHED_BLACKSTONE_BRICKS
                    : r < 0.9F ? Blocks.CRACKED_DEEPSLATE_BRICKS : Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS).defaultBlockState();
        }

        private void prevoir() {
            // 1. le terrain : une grande plate-forme (fondations jusqu'au sol, tout vidé au-dessus), des pentes douces
            //    tout autour, et une avenue qui mène à la grande porte depuis le nord
            for (int dx = -BORD + 4; dx <= BORD - 4; dx++) {
                final int x = dx;
                etapes.add(() -> {
                    for (int dz = Math.abs(x) <= LARGEUR_AVENUE ? -BORD_NORD + 4 : -BORD + 4; dz <= BORD - 4; dz++) {
                        terrain(x, dz);
                    }
                });
            }
            // 2. l'enceinte : 3 blocs d'épaisseur, 26 de haut, créneaux
            for (int cote = 0; cote < 4; cote++) {
                final int k = cote;
                etapes.add(() -> {
                    for (int t = -MUR - 2; t <= MUR + 2; t++) {
                        for (int e = 0; e < 3; e++) {
                            int x = switch (k) { case 0, 1 -> t; case 2 -> -MUR - e; default -> MUR + e; };
                            int z = switch (k) { case 0 -> -MUR - e; case 1 -> MUR + e; default -> t; };
                            for (int y = 1; y <= 26; y++) {
                                pose(x, y, z, pierre());
                            }
                            if (e == 2 && Math.floorMod(t, 2) == 0) {
                                pose(x, 27, z, Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState());
                            }
                        }
                    }
                });
            }
            // 3. les quatre grandes tours d'angle et les tours du milieu
            for (int[] coin : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                etapes.add(() -> tour(coin[0] * (MUR + 1), coin[1] * (MUR + 1), 6, 42));
            }
            etapes.add(() -> tour(0, MUR + 1, 4, 34));
            etapes.add(() -> tour(-MUR - 1, 0, 4, 34));
            etapes.add(() -> tour(MUR + 1, 0, 4, 34));
            // 4. la grande porte, au nord (vers le village), scellée par une herse
            etapes.add(() -> {
                tour(-11, -MUR - 1, 3, 36);
                tour(11, -MUR - 1, 3, 36);
                for (int x = -7; x <= 7; x++) {
                    for (int z = -MUR - 3; z <= -MUR + 1; z++) {
                        for (int y = 1; y <= 20; y++) {
                            boolean arche = y > 16 && Math.abs(x) > 7 - (y - 16) * 2;
                            if (!arche) {
                                pose(x, y, z, z == -MUR - 1 ? Blocks.IRON_BARS.defaultBlockState()
                                        : z == -MUR ? Blocks.REINFORCED_DEEPSLATE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                    pose(x, 21, -MUR - 3, Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
                }
                for (int x : new int[] {-6, -2, 2, 6}) {
                    for (int y = 21; y >= 17; y--) {
                        pose(x, y, -MUR - 4, Blocks.CHAIN.defaultBlockState());
                    }
                }
                for (int x : new int[] {-9, 9}) {
                    pose(x, 1, -MUR - 5, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                    pose(x, 2, -MUR - 5, Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
                    pose(x, 3, -MUR - 5, Blocks.CAMPFIRE.defaultBlockState());
                }
            });
            // 5. le donjon central, et la Salle Zéro en dedans
            etapes.add(this::donjon);
            etapes.add(this::salleZero);
            // 6. la cour : des tentes de pillards, des feux, des bannières noires sur les murs
            etapes.add(() -> {
                for (int[] t : new int[][] {{-32, -30}, {30, -28}, {-30, 30}, {32, 32}, {-36, 2}, {36, -4}}) {
                    tente(t[0], t[1]);
                }
                for (int t = -40; t <= 40; t += 8) {
                    if (Math.abs(t) <= 8) {
                        continue;
                    }
                    for (int[] mur : new int[][] {{t, -MUR - 3, 0}, {t, MUR + 3, 1}, {-MUR - 3, t, 2}, {MUR + 3, t, 3}}) {
                        Direction d = switch (mur[2]) { case 0 -> Direction.NORTH; case 1 -> Direction.SOUTH; case 2 -> Direction.WEST; default -> Direction.EAST; };
                        pose(mur[0], 22, mur[1], Blocks.BLACK_WALL_BANNER.defaultBlockState().setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, d));
                    }
                }
                texte(0.5, 25, -MUR - 5.5, 180F, "{\"text\":\"PLATO 6Z\",\"color\":\"gold\",\"bold\":true}", 5F);
                texte(0.5, 23.2, -MUR - 5.5, 180F, "{\"text\":\"Repaire des Pillards — refuge du Président\",\"color\":\"white\"}", 1.6F);
                texte(0.5, 10, -MUR - 5.5, 180F, "{\"text\":\"🔒 SCELLÉE — c'est pour plus tard\",\"color\":\"red\",\"bold\":true}", 1.8F);
            });
        }

        /**
         * Une colonne du terrain. Sur la plate-forme : tout est rasé, et des fondations descendent jusqu'au sol. Autour,
         * le sol suit le relief mais ne s'écarte du niveau de la forteresse que d'un bloc par bloc de distance (une
         * pente qu'on monte ou qu'on descend à pied) ; sur l'avenue, d'un bloc tous les trois.
         */
        private void terrain(int x, int z) {
            int plateforme = DEMI + 2;
            int hors = Math.max(Math.abs(x), Math.abs(z)) - plateforme;
            if (hors <= 0) {
                for (int y = 1; y <= 75; y++) {
                    vider(x, y, z);
                }
                for (int y = 0; y >= -30; y--) {
                    BlockState ici = level.getBlockState(new BlockPos(fx + x, base + y, fz + z));
                    if (y < 0 && ici.isSolid()) {
                        break;
                    }
                    boolean bord = Math.abs(x) > DEMI || Math.abs(z) > DEMI;
                    pose(x, y, z, y < 0 ? Blocks.DEEPSLATE_BRICKS.defaultBlockState() : (bord ? Blocks.COBBLED_DEEPSLATE
                            : rnd.nextInt(5) == 0 ? Blocks.GRAVEL : Blocks.COARSE_DIRT).defaultBlockState());
                }
                return;
            }
            boolean avenue = Math.abs(x) <= LARGEUR_AVENUE && z < -plateforme;
            int marge = avenue ? hors / 3 : hors;
            if (!avenue && hors > PENTE) {
                return;
            }
            int sol = sol(x, z);
            int niveau = Math.max(-marge, Math.min(marge, sol));
            for (int y = niveau + 1; y <= 75; y++) {
                vider(x, y, z);
            }
            for (int y = niveau; y > sol && y > niveau - 40; y--) {
                pose(x, y, z, y > niveau - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
            }
            BlockState dessus = avenue ? (Math.abs(x) == LARGEUR_AVENUE ? Blocks.COBBLED_DEEPSLATE : rnd.nextInt(4) == 0 ? Blocks.GRAVEL : Blocks.COARSE_DIRT).defaultBlockState()
                    : (rnd.nextInt(6) == 0 ? Blocks.GRAVEL : Blocks.COARSE_DIRT).defaultBlockState();
            if (niveau != sol || avenue) {
                pose(x, niveau, z, dessus);
            }
        }

        /** La hauteur du vrai sol (sans les arbres), par rapport à la forteresse. */
        private int sol(int x, int z) {
            for (int y = 75; y > -60; y--) {
                BlockState ici = level.getBlockState(new BlockPos(fx + x, base + y, fz + z));
                if (ici.isSolid() && !ici.is(BlockTags.LEAVES) && !ici.is(BlockTags.LOGS)) {
                    return y;
                }
            }
            return -60;
        }

        private void vider(int x, int y, int z) {
            BlockPos p = new BlockPos(fx + x, base + y, fz + z);
            if (!level.getBlockState(p).isAir()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }

        /** Une tour carrée creuse de demi-côté r, avec un toit en pointe et des fenêtres rougeoyantes. */
        private void tour(int cx, int cz, int r, int hauteur) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    boolean bord = Math.abs(dx) == r || Math.abs(dz) == r;
                    for (int y = 1; y <= hauteur; y++) {
                        boolean fenetre = bord && y % 8 == 4 && (dx == 0 || dz == 0);
                        pose(cx + dx, y, cz + dz, fenetre ? Blocks.SHROOMLIGHT.defaultBlockState() : bord ? pierre()
                                : y % 10 == 0 ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                    }
                    pose(cx + dx, hauteur + 1, cz + dz, bord && Math.floorMod(dx + dz, 2) == 0 ? Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState()
                            : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                }
            }
            for (int k = 0; k <= r; k++) {
                for (int dx = -r + k; dx <= r - k; dx++) {
                    for (int dz = -r + k; dz <= r - k; dz++) {
                        pose(cx + dx, hauteur + 2 + k * 2, cz + dz, Blocks.DEEPSLATE_TILES.defaultBlockState());
                        pose(cx + dx, hauteur + 3 + k * 2, cz + dz, Blocks.DEEPSLATE_TILES.defaultBlockState());
                    }
                }
            }
            pose(cx, hauteur + 4 + r * 2, cz, Blocks.LIGHTNING_ROD.defaultBlockState());
        }

        /** Le donjon : 31 x 31, 55 de haut, quatre tourelles, un fanal rouge au sommet. */
        private void donjon() {
            int r = 15;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    boolean bord = Math.abs(dx) == r || Math.abs(dz) == r;
                    for (int y = 1; y <= 55; y++) {
                        boolean fenetre = bord && y > 18 && y % 9 == 0 && Math.floorMod(dx + dz, 6) == 0;
                        if (bord) {
                            pose(dx, y, dz, fenetre ? Blocks.RED_STAINED_GLASS.defaultBlockState() : pierre());
                        } else if (y > 16 && y % 13 == 4) {
                            pose(dx, y, dz, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                        }
                    }
                    pose(dx, 56, dz, bord && Math.floorMod(dx + dz, 2) == 0 ? Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState()
                            : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                }
            }
            for (int[] c : new int[][] {{-r, -r}, {r, -r}, {-r, r}, {r, r}}) {
                for (int y = 1; y <= 64; y++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                                pose(c[0] + dx, y, c[1] + dz, pierre());
                            }
                        }
                    }
                }
                pose(c[0], 65, c[1], Blocks.SHROOMLIGHT.defaultBlockState());
            }
            for (int y = 57; y <= 60; y++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        pose(dx, y, dz, y == 60 ? Blocks.RED_STAINED_GLASS.defaultBlockState() : dx == 0 && dz == 0 ? Blocks.SHROOMLIGHT.defaultBlockState()
                                : Blocks.CRYING_OBSIDIAN.defaultBlockState());
                    }
                }
            }
        }

        /** La Salle Zéro, au rez-de-chaussée du donjon : l'arène du combat final, que rien ne peut casser. */
        private void salleZero() {
            BlockState renforce = Blocks.REINFORCED_DEEPSLATE.defaultBlockState();
            for (int dx = -14; dx <= 14; dx++) {
                for (int dz = -14; dz <= 14; dz++) {
                    pose(dx, 0, dz, Math.floorMod(dx + dz, 7) == 0 ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : renforce);
                    pose(dx, 16, dz, renforce);
                }
            }
            // l'entrée du donjon, face à la grande porte
            for (int x = -2; x <= 2; x++) {
                for (int y = 1; y <= 7; y++) {
                    pose(x, y, -15, Math.abs(x) == 2 || y == 7 ? Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
            for (int[] p : new int[][] {{-10, -10}, {10, -10}, {-10, 10}, {10, 10}, {0, 12}, {-12, 0}, {12, 0}}) {
                for (int y = 1; y <= 15; y++) {
                    pose(p[0], y, p[1], y % 4 == 0 ? Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                }
                pose(p[0], 14, p[1] + (p[1] > 0 ? -1 : 1), Blocks.SOUL_LANTERN.defaultBlockState());
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    pose(dx, 1, dz, Math.abs(dx) == 2 || Math.abs(dz) == 2 ? Blocks.POLISHED_BLACKSTONE_BRICK_SLAB.defaultBlockState()
                            : Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                }
            }
            pose(0, 2, 0, CabaneBlocks.PUPITRE_PRESIDENT.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
            texte(0.5, 12, -14.4, 0F, "{\"text\":\"SALLE ZÉRO\",\"color\":\"dark_red\",\"bold\":true}", 3F);
        }

        private void tente(int cx, int cz) {
            Block[] laines = {Blocks.BROWN_WOOL, Blocks.GRAY_WOOL, Blocks.BLACK_WOOL};
            Block laine = laines[rnd.nextInt(laines.length)];
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int h = 4 - Math.abs(dx);
                    if (Math.abs(dz) == 2 || Math.abs(dx) == 3) {
                        pose(cx + dx, h, cz + dz, laine.defaultBlockState());
                    }
                    pose(cx + dx, h, cz, laine.defaultBlockState());
                }
            }
            pose(cx, 1, cz + 4, Blocks.CAMPFIRE.defaultBlockState());
            pose(cx + 2, 1, cz + 3, Blocks.BARREL.defaultBlockState());
        }

        private void texte(double dx, double dy, double dz, float yaw, String json, float echelle) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", "minecraft:text_display");
            tag.putString("text", json);
            tag.putString("billboard", "fixed");
            tag.putString("alignment", "center");
            tag.putInt("background", 0);
            tag.putBoolean("shadow", true);
            tag.putInt("line_width", 400);
            CompoundTag t = new CompoundTag();
            t.put("left_rotation", floats(0, 0, 0, 1));
            t.put("right_rotation", floats(0, 0, 0, 1));
            t.put("translation", floats(0, 0, 0));
            t.put("scale", floats(echelle, echelle, echelle));
            tag.put("transformation", t);
            ListTag tags = new ListTag();
            tags.add(StringTag.valueOf(HubBuilder.TAG));
            tag.put("Tags", tags);
            Entity e = EntityType.loadEntityRecursive(tag, level, ent -> {
                ent.moveTo(fx + dx, base + dy, fz + dz, yaw, 0F);
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
    }

    private Forteresse() {
    }
}
