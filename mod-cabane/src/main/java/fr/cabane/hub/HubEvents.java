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

    /**
     * Les PNJ du centre de map : fichier data/cabane/pnj/&lt;nom&gt;.snbt, position relative à la place (dy : la hauteur
     * des pieds ; 1 = sur la place). Quand : 0 toujours, -1 tant que le Président règne, 1 une fois Beauvais libéré.
     */
    private record Pnj(String file, double dx, double dy, double dz, float yaw, int quand) {
        Pnj(String file, double dx, double dz, float yaw) {
            this(file, dx, 1, dz, yaw, 0);
        }

        boolean present(boolean libere) {
            return quand == 0 || (quand > 0) == libere;
        }
    }

    private static final List<Pnj> PNJ = List.of(
            new Pnj("gardien", 3.5, -31.5, 0F),        // sur le porche de la Grande Cabane
            new Pnj("cartographe", -9.5, 4.5, 0F),     // à côté du grand panneau de la carte
            new Pnj("marchand", -54.5, 0.5, -90F),     // derrière son comptoir, au Grand Bazar
            new Pnj("forgeron", 60.5, 0.5, 90F),       // derrière l'enclume monumentale de sa forge, au bout de la rue
            new Pnj("fee", -27.5, 4.5, 0F),            // devant le Grand Bazar
            new Pnj("pole_emploi", -37.5, -7.5, 0F),   // au tableau des contrats, dans le bazar
            new Pnj("cousin", -27.5, -4.5, -90F),      // l'autre côté de l'entrée du bazar
            new Pnj("garde_ouest", -3.5, HubBuilder.Z_PORTE + 2.5, 0F),    // à la grande porte, côté dehors
            new Pnj("garde_est", 3.5, HubBuilder.Z_PORTE + 2.5, 0F),
            new Pnj("guerisseuse", -15.5, 30.5, 90F),  // au feu de camp
            // le Président sur la place, devant le grand panneau, et sa garde rapprochée autour
            new Pnj("macron", 0.5, 1, 9.5, 0F, -1),
            new Pnj("garde_president_1", -1.5, 1, 8.5, 0F, -1),
            new Pnj("garde_president_2", 2.5, 1, 8.5, 0F, -1),
            new Pnj("garde_president_3", -1.5, 1, 11.5, 0F, -1),
            new Pnj("garde_president_4", 2.5, 1, 11.5, 0F, -1),
            // Marcel raconte l'histoire du village au feu de camp ; Pierre, le Sujet 07, attend dans sa cellule du laboratoire
            new Pnj("conteur", -24.5, 30.5, -90F),
            new Pnj("prisonnier", HubBuilder.CELLULE_PIERRE.getX() + 0.5, HubBuilder.CELLULE_PIERRE.getY(), HubBuilder.CELLULE_PIERRE.getZ() + 0.5, 180F, -1),
            // après la libération, Pierre retrouve le feu de camp et c'est le Président qui finit en cellule
            new Pnj("pierre_libre", -22.5, 1, 32.5, -135F, 1),
            new Pnj("macron_prison", HubBuilder.CELLULE_PRESIDENT.getX() + 0.5, HubBuilder.CELLULE_PRESIDENT.getY(), HubBuilder.CELLULE_PRESIDENT.getZ() + 0.5, 180F, 1),
            // les habitants, chacun dans son coin
            new Pnj("jean", 20.5, 0.5, 90F),           // la rue du village
            new Pnj("jacques", 44.5, 0.5, -90F),
            new Pnj("francis", -12.5, -12.5, 45F),     // la place
            new Pnj("louis", 12.5, -6.5, 0F),
            new Pnj("bernard", 31.5, 22.5, 180F),      // le chemin des champs
            new Pnj("gerard", -24.5, 35.5, 90F),       // le feu de camp
            new Pnj("ginette", 6.5, -14.5, 0F),
            new Pnj("monique", -7.5, 24.5, -90F),      // l'avenue de la grande porte
            new Pnj("martine", 29.5, 3.5, 180F),
            new Pnj("thierry", 0.5, 40.5, 0F),
            // Vegeta s'entraîne près de la tour de guet ; les orques travaillent à la forge de Brokk
            new Pnj("vegeta", -30.5, 42.5, 90F),
            // Jean-Mi, derrière le comptoir de sa boutique de cartes, sur la grande allée (la porte donne à l'est)
            new Pnj("vendeur_cartes", HubBuilder.BOUTIQUE_CARTES[0] - 4.5, HubBuilder.BOUTIQUE_CARTES[1] + 0.5, -90F),
            // le Fourgue, dans l'ombre derrière la tour de guet : le marché noir
            new Pnj("fourgue", -38.5, 48.5, 135F),
            new Pnj("orque_1", 55.5, -12.5, 180F),     // devant les hauts-fourneaux
            new Pnj("orque_2", 63.5, -12.5, 180F),
            new Pnj("orque_3", 59.5, 12.5, 0F),
            // Sylvie et son fils Lucas attendent Pierre devant la Mairie, chaque jour ; libéré, il les retrouve au feu de camp
            new Pnj("sylvie", 4.5, 1, 53.5, -90F, -1),
            new Pnj("lucas", 4.5, 1, 55.5, -90F, -1),
            new Pnj("sylvie_libre", -21.5, 1, 34.5, 180F, 1),
            new Pnj("lucas_libre", -20.5, 1, 34.5, 180F, 1));

    /** Les PNJ qui ne font plus partie du village : Kylian, l'équipier d'un fast-food que les joueurs n'ont pas encore bâti. */
    private static final List<String> PARTIS = List.of("equipier");

    /** Rayon (en chunks) chargé autour du centre pendant la construction. */
    private static final int RAYON_CHUNKS = 10;

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
    private static List<HubBuilder.Etape> etapes;
    private static int indexEtape;
    private static long debutConstruction;

    /** À augmenter quand les PNJ changent (dialogues, skins, nouveaux venus) : ils sont remplacés au démarrage suivant. */
    public static final int VERSION_PNJ = 14;
    private static int rafraichir = -1;

    public static void onServerStarted(MinecraftServer server) {
        HubData donnees = HubData.get(server);
        rafraichir = -1;
        fr.cabane.monde.CabaneMonde.zoneCalme(donnees.centre());
        if (donnees.centre() != null && donnees.versionPnj() != VERSION_PNJ) {
            // on charge le village, on laisse aux PNJ le temps d'apparaître, puis on les remplace
            forcerVillage(server.overworld(), donnees.centre(), true);
            rafraichir = 100;
        }
        // en solo, on peut ouvrir plusieurs mondes de suite : rien ne doit rester du précédent
        recherche = null;
        etapes = null;
        ticket = null;
        site = null;
        FORCES.clear();
        etape = HubData.get(server).centre() == null ? Etape.RECHERCHE : Etape.RIEN;
        attente = 40;
    }

    public static boolean pret(MinecraftServer server) {
        return HubData.get(server).centre() != null;
    }

    private static void forcerVillage(ServerLevel level, BlockPos centre, boolean forcer) {
        // tout le village, jusqu'aux gardes de la grande porte
        for (int cx = (centre.getX() >> 4) - 8; cx <= (centre.getX() >> 4) + 8; cx++) {
            for (int cz = (centre.getZ() >> 4) - 8; cz <= (centre.getZ() >> 4) + 8; cz++) {
                level.setChunkForced(cx, cz, forcer);
            }
        }
    }

    public static void tick(MinecraftServer server) {
        if (rafraichir > 0 && --rafraichir == 0) {
            ServerLevel level = server.overworld();
            HubData donnees = HubData.get(server);
            int n = spawnNpcs(level);
            donnees.setVersionPnj(VERSION_PNJ);
            forcerVillage(level, donnees.centre(), false);
            Cabane.LOGGER.info("PNJ du village mis à jour (version {}) : {} PNJ", VERSION_PNJ, n);
        }
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
                // plus aucune structure ne s'installera autour du village (ses chunks ne sont pas encore générés)
                fr.cabane.monde.CabaneMonde.zoneCalme(site == null ? null : site.centre());
                if (site == null) {
                    BlockPos spawn = level.getSharedSpawnPos();
                    level.getChunk(spawn.getX() >> 4, spawn.getZ() >> 4);
                    BlockPos sol = new BlockPos(spawn.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()) - 1, spawn.getZ());
                    Cabane.LOGGER.warn("Aucun site trouvé, la cabane est construite au spawn ({})", sol);
                    site = new HubBuilder.Site(sol, null);
                }
                demanderChunks(level);
            }
            case CHUNKS -> {
                // getChunkNow ne renvoie le chunk qu'une fois généré (hasChunk dit seulement qu'il est demandé)
                boolean tous = FORCES.stream().allMatch(p -> level.getChunkSource().getChunkNow(p.x, p.z) != null);
                if (tous || server.getTickCount() > delai) {
                    etape = Etape.CONSTRUCTION;
                }
                attente = 1;
            }
            case CONSTRUCTION -> {
                // une étape par tick : le serveur reste fluide pendant toute la construction
                if (etapes == null) {
                    retirerAnciens(level);
                    etapes = new HubBuilder(level, site.centre()).etapes(site.village());
                    indexEtape = 0;
                    debutConstruction = System.currentTimeMillis();
                }
                HubBuilder.Etape e = etapes.get(indexEtape++);
                long t = System.currentTimeMillis();
                e.action().run();
                Cabane.LOGGER.info("  construction {} : {} ms", e.nom(), System.currentTimeMillis() - t);
                attente = 1;
                if (indexEtape < etapes.size()) {
                    return;
                }
                etapes = null;
                terminer(level, site.centre(), debutConstruction);
                if (ticket != null) {
                    level.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, ticket, RAYON_CHUNKS + 1, ticket);
                    ticket = null;
                }
                FORCES.clear();
                etape = Etape.RIEN;
                for (ServerPlayer joueur : server.getPlayerList().getPlayers()) {
                    accueillir(joueur);
                }
            }
            default -> {
            }
        }
    }

    /** Commande /cabane placer : reconstruit le centre de map ici, en plusieurs ticks. */
    public static void placer(ServerLevel level, BlockPos centre) {
        site = new HubBuilder.Site(centre, null);
        demanderChunks(level);
    }

    /** Un ticket de chargement : le jeu génère les chunks du village en arrière-plan, sans bloquer. */
    private static void demanderChunks(ServerLevel level) {
        if (ticket != null) {
            level.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, ticket, RAYON_CHUNKS + 1, ticket);
        }
        FORCES.clear();
        etapes = null;
        int cx = site.centre().getX() >> 4;
        int cz = site.centre().getZ() >> 4;
        ticket = new net.minecraft.world.level.ChunkPos(cx, cz);
        level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, ticket, RAYON_CHUNKS + 1, ticket);
        for (int x = cx - RAYON_CHUNKS; x <= cx + RAYON_CHUNKS; x++) {
            for (int z = cz - RAYON_CHUNKS; z <= cz + RAYON_CHUNKS; z++) {
                FORCES.add(new net.minecraft.world.level.ChunkPos(x, z));
            }
        }
        etape = Etape.CHUNKS;
        delai = level.getServer().getTickCount() + 20 * 60 * 5;
        attente = 1;
    }

    /** Les textes, cadres et PNJ de l'ancien centre de map, s'il y en avait un. */
    private static void retirerAnciens(ServerLevel level) {
        BlockPos ancien = HubData.get(level.getServer()).centre();
        if (ancien != null) {
            for (Entity e : level.getEntities((Entity) null, new AABB(ancien).inflate(100, 120, 100), e -> e.getTags().contains(HubBuilder.TAG))) {
                e.discard();
            }
        }
    }

    private static void terminer(ServerLevel level, BlockPos centre, long start) {
        HubData.get(level.getServer()).setCentre(centre);
        HubData data = HubData.get(level.getServer());
        level.setDefaultSpawnPos(data.demarre() ? HubBuilder.dansAvion(centre) : HubBuilder.salleEmbarquement(centre), 180F);
        level.getGameRules().getRule(GameRules.RULE_SPAWN_RADIUS).set(0, level.getServer());
        // la cabane devient le vrai centre de la map : la bordure du monde se centre dessus
        level.getWorldBorder().setCenter(centre.getX() + 0.5, centre.getZ() + 0.5);
        int npcs = spawnNpcs(level);
        data.setVersionPnj(VERSION_PNJ);
        Cabane.LOGGER.info("Centre de map construit en {} : {} ms, {} PNJ", centre, System.currentTimeMillis() - start, npcs);
    }

    public static int spawnNpcs(ServerLevel level) {
        BlockPos centre = HubData.get(level.getServer()).centre();
        if (centre == null) {
            return 0;
        }
        boolean libere = HubData.get(level.getServer()).libere();
        // ceux qui ne font plus partie du village s'en vont
        for (Entity e : level.getEntities((Entity) null, new AABB(centre).inflate(160, 120, 160),
                e -> PARTIS.stream().anyMatch(p -> e.getTags().contains("pnj." + p)))) {
            e.discard();
        }
        int count = 0;
        for (Pnj pnj : PNJ) {
            if (!pnj.present(libere)) {
                retirer(level, pnj);
            } else if (spawn(level, centre, pnj)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Le Président est tombé : sa garde et lui quittent la place, il finit en cellule sous la Mairie. Le combat a lieu
     * loin du village : on le charge, et les PNJ sont remplacés quelques secondes plus tard.
     */
    public static void apresLiberation(ServerLevel level) {
        rafraichirPnj(level);
    }

    /** Les PNJ changent de discours (événement, libération) : le village est chargé, et ils sont remplacés juste après. */
    public static void rafraichirPnj(ServerLevel level) {
        BlockPos centre = HubData.get(level.getServer()).centre();
        if (centre == null) {
            return;
        }
        forcerVillage(level, centre, true);
        rafraichir = 100;
    }

    /** Vegeta change de discours selon l'événement : armé, puis après le combat selon le camp choisi. */
    private static String fichier(ServerLevel level, String fichier) {
        if (fichier.equals("vegeta")) {
            HubData data = HubData.get(level.getServer());
            if (data.vegeta() == 1) {
                return "vegeta_arme";
            }
            if (data.vegeta() == 2 && !data.issueVegeta().isEmpty()) {
                return "vegeta_" + data.issueVegeta();
            }
        }
        return fichier;
    }

    private static void retirer(ServerLevel level, Pnj pnj) {
        Optional<Resource> resource = level.getServer().getResourceManager().getResource(Cabane.id("pnj/" + pnj.file() + ".snbt"));
        if (resource.isEmpty()) {
            return;
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            CompoundTag tag = TagParser.parseTag(reader.lines().collect(Collectors.joining("\n")));
            Entity old = tag.hasUUID("UUID") ? level.getEntity(tag.getUUID("UUID")) : null;
            if (old != null) {
                old.discard();
            }
        } catch (Exception e) {
            Cabane.LOGGER.warn("PNJ {} non retiré : {}", pnj.file(), e.toString());
        }
    }

    private static boolean spawn(ServerLevel level, BlockPos centre, Pnj pnj) {
        ResourceLocation id = Cabane.id("pnj/" + fichier(level, pnj.file()) + ".snbt");
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
            double y = centre.getY() + pnj.dy();
            double z = centre.getZ() + pnj.dz();
            // la maison du PNJ : ceux qui se baladent y reviennent toujours
            CompoundTag maison = new CompoundTag();
            maison.putInt("X", (int) Math.floor(x));
            maison.putInt("Y", (int) Math.floor(y));
            maison.putInt("Z", (int) Math.floor(z));
            CompoundTag navigation = tag.getCompound("Navigation");
            navigation.put("Home", maison);
            tag.put("Navigation", navigation);
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

    /** À la connexion : avant le /start, la salle d'embarquement ; après, l'avion (la première fois). */
    public static void accueillir(ServerPlayer joueur) {
        HubData data = HubData.get(joueur.server);
        if (data.centre() != null && !data.demarre()) {
            BlockPos salle = HubBuilder.salleEmbarquement(data.centre());
            joueur.teleportTo(joueur.server.overworld(), salle.getX() + 0.5, salle.getY(), salle.getZ() + 0.5, 180F, 0F);
            if (!joueur.isCreative()) {
                joueur.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
            }
            joueur.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
            joueur.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Porte 12").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
            joueur.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Vol BV2026 pour Beauvais : embarquement imminent").withStyle(ChatFormatting.YELLOW)));
            joueur.sendSystemMessage(Component.literal("✈ Tu attends en salle d'embarquement. L'hôte lance la partie avec /start quand tout le monde est là.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        welcome(joueur);
    }

    /**
     * /start : la vitre de la passerelle s'ouvre, l'avion devient le point de départ, et tout le monde part pour la
     * cinématique du décollage (Decollage) ; à la fin, on se réveille dans l'avion en feu.
     */
    public static void demarrer(MinecraftServer server, net.minecraft.commands.CommandSourceStack source) {
        HubData data = HubData.get(server);
        if (data.centre() == null) {
            source.sendFailure(Component.literal("Beauvais n'est pas encore construit, patiente un instant."));
            return;
        }
        if (data.demarre()) {
            source.sendFailure(Component.literal("La partie a déjà commencé."));
            return;
        }
        if (Decollage.enCours()) {
            source.sendFailure(Component.literal("Une cinématique du décollage est déjà en cours : attends qu'elle finisse."));
            return;
        }
        data.setDemarre(true);
        ServerLevel level = server.overworld();
        level.setDefaultSpawnPos(HubBuilder.dansAvion(data.centre()), 90F);
        for (BlockPos p : HubBuilder.porteEmbarquement(data.centre())) {
            level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal("✈ Embarquement ! Bon vol vers Beauvais…").withStyle(ChatFormatting.GOLD), false);
        List<ServerPlayer> joueurs = server.getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).toList();
        if (Decollage.lancer(server, joueurs, true, 0, 0)) {
            return;
        }
        // pas de cinématique (l'aéroport manque) : on se réveille tout de suite dans l'avion en feu
        for (ServerPlayer joueur : server.getPlayerList().getPlayers()) {
            if (joueur.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.ADVENTURE) {
                joueur.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            }
            if (!welcome(joueur)) {
                reveil(joueur);
            }
        }
    }

    /** Mort sans lit : on renaît dans l'avion, avec un parachute. */
    public static void reapparition(ServerPlayer joueur) {
        HubData data = HubData.get(joueur.server);
        if (data.centre() == null || !data.demarre() || joueur.getRespawnPosition() != null) {
            return;
        }
        BlockPos dedans = HubBuilder.dansAvion(data.centre());
        joueur.teleportTo(joueur.server.overworld(), dedans.getX() + 0.5, dedans.getY(), dedans.getZ() + 0.5, 90F, 0F);
        give(joueur, new ItemStack(CabaneItems.PARACHUTE.get()));
        joueur.sendSystemMessage(Component.literal("Retour dans l'avion… Prends ton parachute et saute !").withStyle(ChatFormatting.YELLOW));
    }

    /** Première connexion : le joueur se réveille dans l'avion en feu, avec son kit de départ. Faux s'il l'avait déjà. */
    public static boolean welcome(ServerPlayer player) {
        HubData data = HubData.get(player.server);
        if (data.centre() == null) {
            player.sendSystemMessage(Component.literal("Le centre de map se construit, ça arrive dans un instant…").withStyle(ChatFormatting.GRAY));
            return false;
        }
        if (!data.markKit(player.getUUID())) {
            return false;
        }
        give(player, new ItemStack(CabaneItems.TELEPHONE.get()));
        Item questBook = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("ftbquests", "book"));
        if (questBook != Items.AIR) {
            give(player, new ItemStack(questBook));
        }
        give(player, new ItemStack(Items.BREAD, 8));
        give(player, new ItemStack(Items.TORCH, 16));
        reveil(player);
        player.sendSystemMessage(Component.literal("Ton avion est en feu au-dessus de la jungle !").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("• Prends un parachute dans le coffre, saute, puis clic droit en l'air pour l'ouvrir.").withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.literal("• En bas, le village de Beauvais t'accueille. Va écouter Marcel le Conteur, au feu de camp : il connaît l'histoire du village.").withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.literal("• Téléphone : touche N. Ton argent s'affiche à droite de l'écran.").withStyle(ChatFormatting.YELLOW));
        return true;
    }

    /** Le réveil dans l'avion en feu : des parachutes dans le coffre, une explosion, MAYDAY. */
    public static void reveil(ServerPlayer player) {
        BlockPos centre = HubData.get(player.server).centre();
        if (centre == null) {
            return;
        }
        ServerLevel level = player.server.overworld();
        BlockPos coffre = HubBuilder.coffreAvion(centre);
        if (level.getBlockEntity(coffre) instanceof Container conteneur) {
            remplirParachutes(conteneur);
        }
        BlockPos dedans = HubBuilder.dansAvion(centre);
        player.teleportTo(level, dedans.getX() + 0.5, dedans.getY(), dedans.getZ() + 0.5, 90F, 0F);
        player.fallDistance = 0;
        level.playSound(null, dedans, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.AMBIENT, 0.6F, 0.7F);
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("MAYDAY !").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Prends un parachute dans le coffre et saute !").withStyle(ChatFormatting.YELLOW)));
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
