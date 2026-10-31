package fr.cabane.hub;

import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import java.io.BufferedReader;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

public final class HubEvents {

    /** Les PNJ du centre de map : fichier data/cabane/pnj/&lt;nom&gt;.snbt, position relative à la place. */
    private record Pnj(String file, double dx, double dz, float yaw) {
    }

    private static final List<Pnj> PNJ = List.of(
            new Pnj("gardien", 2.5, -12.5, 180F),
            new Pnj("cartographe", -2.5, -8.5, 135F),
            new Pnj("marchand", -13.5, 0.5, -90F));

    /**
     * La construction se fait en plusieurs étapes, sur plusieurs ticks, pour ne jamais bloquer le serveur
     * trop longtemps (sinon son « watchdog » l'arrête) : recherche du site, génération des chunks en
     * arrière-plan, puis construction.
     */
    private enum Etape { RIEN, RECHERCHE, CHUNKS, CONSTRUCTION }

    private static Etape etape = Etape.RIEN;
    private static HubBuilder.Site site;
    private static HubBuilder.Recherche recherche;
    private static final List<net.minecraft.world.level.ChunkPos> FORCES = new java.util.ArrayList<>();
    private static int attente;
    private static int delai;
    private static net.minecraft.world.level.ChunkPos ticket;

    public static void onServerStarted(MinecraftServer server) {
        etape = HubData.get(server).centre() == null ? Etape.RECHERCHE : Etape.RIEN;
        attente = 40;
    }

    public static boolean pret(MinecraftServer server) {
        return HubData.get(server).centre() != null;
    }

    public static void tick(MinecraftServer server) {
        if (etape == Etape.RIEN || --attente > 0) {
            return;
        }
        ServerLevel level = server.overworld();
        switch (etape) {
            case RECHERCHE -> {
                if (recherche == null) {
                    Cabane.LOGGER.info("Recherche d'un site pour le centre de map…");
                    recherche = new HubBuilder.Recherche(level);
                }
                recherche.etape();
                attente = 1;
                if (!recherche.fini()) {
                    return;
                }
                site = recherche.resultat();
                recherche = null;
                if (site == null) {
                    BlockPos spawn = level.getSharedSpawnPos();
                    level.getChunk(spawn.getX() >> 4, spawn.getZ() >> 4);
                    BlockPos sol = new BlockPos(spawn.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()) - 1, spawn.getZ());
                    Cabane.LOGGER.warn("Aucun site trouvé, la cabane est construite au spawn ({})", sol);
                    site = new HubBuilder.Site(sol, null);
                }
                // un ticket de chargement : le jeu génère ces chunks en arrière-plan, sans bloquer
                int cx = site.centre().getX() >> 4;
                int cz = site.centre().getZ() >> 4;
                ticket = new net.minecraft.world.level.ChunkPos(cx, cz);
                level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, ticket, 4, ticket);
                for (int x = cx - 3; x <= cx + 3; x++) {
                    for (int z = cz - 3; z <= cz + 3; z++) {
                        FORCES.add(new net.minecraft.world.level.ChunkPos(x, z));
                    }
                }
                etape = Etape.CHUNKS;
                delai = server.getTickCount() + 20 * 60 * 3;
                attente = 1;
            }
            case CHUNKS -> {
                boolean tous = FORCES.stream().allMatch(p -> level.getChunkSource().hasChunk(p.x, p.z));
                if (tous || server.getTickCount() > delai) {
                    etape = Etape.CONSTRUCTION;
                }
                attente = 1;
            }
            case CONSTRUCTION -> {
                build(level, site.centre(), site.village());
                level.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, ticket, 4, ticket);
                FORCES.clear();
                etape = Etape.RIEN;
                for (ServerPlayer joueur : server.getPlayerList().getPlayers()) {
                    welcome(joueur);
                }
            }
            default -> {
            }
        }
    }

    public static void build(ServerLevel level, BlockPos centre, BlockPos village) {
        long start = System.currentTimeMillis();
        BlockPos ancien = HubData.get(level.getServer()).centre();
        if (ancien != null) {
            for (Entity e : level.getEntities((Entity) null, new AABB(ancien).inflate(80, 120, 80), e -> e.getTags().contains(HubBuilder.TAG))) {
                e.discard();
            }
        }
        new HubBuilder(level, centre).construire(village);
        HubData.get(level.getServer()).setCentre(centre);
        level.setDefaultSpawnPos(centre.offset(0, 1, 5), 180F);
        level.getGameRules().getRule(GameRules.RULE_SPAWN_RADIUS).set(0, level.getServer());
        // la cabane devient le vrai centre de la map : la bordure du monde se centre dessus
        level.getWorldBorder().setCenter(centre.getX() + 0.5, centre.getZ() + 0.5);
        int npcs = spawnNpcs(level);
        Cabane.LOGGER.info("Centre de map construit en {} : {} ms, {} PNJ", centre, System.currentTimeMillis() - start, npcs);
    }

    public static int spawnNpcs(ServerLevel level) {
        BlockPos centre = HubData.get(level.getServer()).centre();
        if (centre == null) {
            return 0;
        }
        int count = 0;
        for (Pnj pnj : PNJ) {
            if (spawn(level, centre, pnj)) {
                count++;
            }
        }
        return count;
    }

    private static boolean spawn(ServerLevel level, BlockPos centre, Pnj pnj) {
        ResourceLocation id = Cabane.id("pnj/" + pnj.file() + ".snbt");
        Optional<Resource> resource = level.getServer().getResourceManager().getResource(id);
        if (resource.isEmpty()) {
            return false;
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            CompoundTag tag = TagParser.parseTag(reader.lines().collect(Collectors.joining("\n")));
            if (tag.hasUUID("UUID")) {
                Entity old = level.getEntity(tag.getUUID("UUID"));
                if (old != null) {
                    old.discard();
                }
            }
            double x = centre.getX() + pnj.dx();
            double y = centre.getY() + 1;
            double z = centre.getZ() + pnj.dz();
            Entity entity = EntityType.loadEntityRecursive(tag, level, e -> {
                e.moveTo(x, y, z, pnj.yaw(), 0F);
                return e;
            });
            return entity != null && level.addFreshEntity(entity);
        } catch (Exception e) {
            Cabane.LOGGER.warn("PNJ {} non créé (Easy NPC absent ou fichier invalide) : {}", pnj.file(), e.toString());
            return false;
        }
    }

    /** Première connexion : le joueur se réveille dans l'avion en feu, avec son kit de départ. */
    public static void welcome(ServerPlayer player) {
        HubData data = HubData.get(player.server);
        if (data.centre() == null) {
            player.sendSystemMessage(Component.literal("Le centre de map se construit, ça arrive dans un instant…").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!data.markKit(player.getUUID())) {
            return;
        }
        give(player, new ItemStack(CabaneItems.TELEPHONE.get()));
        Item questBook = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("ftbquests", "book"));
        if (questBook != Items.AIR) {
            give(player, new ItemStack(questBook));
        }
        give(player, new ItemStack(Items.BREAD, 8));
        give(player, new ItemStack(Items.TORCH, 16));
        BlockPos centre = data.centre();
        if (centre != null) {
            ServerLevel level = player.server.overworld();
            BlockPos coffre = centre.offset(HubBuilder.COFFRE_AVION);
            if (level.getBlockEntity(coffre) instanceof Container conteneur) {
                remplirParachutes(conteneur);
            }
            BlockPos dedans = centre.offset(HubBuilder.DANS_AVION);
            player.teleportTo(level, dedans.getX() + 0.5, dedans.getY(), dedans.getZ() + 0.5, 90F, 0F);
            level.playSound(null, dedans, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.AMBIENT, 0.6F, 0.7F);
        }
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("MAYDAY !").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Prends un parachute dans le coffre et saute !").withStyle(ChatFormatting.YELLOW)));
        player.sendSystemMessage(Component.literal("Ton avion est en feu au-dessus de la savane !").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("• Prends un parachute dans le coffre, saute, puis clic droit en l'air pour l'ouvrir.").withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.literal("• En bas, le village t'accueille dans la Grande Cabane. Parle au Vieux Gardien.").withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.literal("• Téléphone : touche N. Ton argent s'affiche à droite de l'écran.").withStyle(ChatFormatting.YELLOW));
    }

    private static void remplirParachutes(Container coffre) {
        int presents = 0;
        for (int i = 0; i < coffre.getContainerSize(); i++) {
            if (coffre.getItem(i).is(CabaneItems.PARACHUTE.get())) {
                presents++;
            }
        }
        for (int i = 0; i < coffre.getContainerSize() && presents < 6; i++) {
            if (coffre.getItem(i).isEmpty()) {
                coffre.setItem(i, new ItemStack(CabaneItems.PARACHUTE.get()));
                presents++;
            }
        }
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private HubEvents() {
    }
}
