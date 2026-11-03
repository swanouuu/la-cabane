package fr.cabane.hub;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import fr.cabane.Cabane;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Le décollage : quand l'hôte tape /start, avant l'avion en feu, une cinématique raconte le vol BV2026. Tout le monde
 * embarque par la passerelle (chacun avec son skin, des passagers inconnus remplissent les sièges vides), on voit les
 * pilotes, l'avion quitte la porte et décolle ; dans la cabine on discute, l'hôtesse passe avec son chariot ; puis
 * l'orage, les secousses, une nuée de phantoms dans le réacteur droit… et l'on se réveille dans l'avion en feu.
 *
 * <p>Le serveur ne fait que poser le décor : il emmène les joueurs dans la dimension de l'aéroport (cachés dans une cave
 * sous le tarmac, le temps que la caméra filme), donne le top départ, puis à la fin les dépose dans l'avion en feu. La
 * cinématique elle-même (l'avion, les personnages, la caméra) est calculée sur l'écran de chacun : ClientDecollage.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Decollage {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("aeroport"));
    /** La durée de la cinématique, en secondes (le client a la même horloge) : ensuite, on se réveille dans l'avion en feu. */
    public static final double DUREE = 101.5;
    /** Autant de passagers au plus que de sièges dans l'avion. */
    public static final int SIEGES = 24;

    /** Un passager : un joueur (son skin vient de la liste des joueurs), ou un faux pour les tests. */
    public record Passager(UUID id, String nom) {
        public static final StreamCodec<ByteBuf, Passager> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Passager::id, ByteBufCodecs.STRING_UTF8, Passager::nom, Passager::new);
    }

    /** Serveur → client : la cinématique commence (à {@code depart} secondes), avec ces passagers, dans l'ordre tiré au sort. */
    public record Debut(long graine, float depart, List<Passager> passagers) implements CustomPacketPayload {
        public static final Type<Debut> TYPE = new Type<>(Cabane.id("decollage"));
        public static final StreamCodec<ByteBuf, Debut> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Debut::graine, ByteBufCodecs.FLOAT, Debut::depart,
                Passager.CODEC.apply(ByteBufCodecs.list(SIEGES)), Debut::passagers, Debut::new);

        @Override
        public Type<Debut> type() {
            return TYPE;
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reseau {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("1").playToClient(Debut.TYPE, Debut.CODEC,
                    (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.cinema.ClientDecollage.demarrer(p)));
        }
    }

    /** Où renvoyer chacun après une répétition : là où il était, dans son mode de jeu. */
    private record Retour(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch, GameType mode) {
    }

    private static boolean actif;
    /** C'est le vrai /start (à la fin : l'avion en feu), pas une répétition de l'hôte. */
    private static boolean pourDeVrai;
    private static int ticks;
    /** Quand la cinématique a commencé (System.nanoTime) et combien de secondes il en reste : sur l'horloge réelle, comme
     * chez les joueurs, même si le serveur rame. */
    private static long debutNanos;
    private static double duree;
    private static boolean avionCharge;
    private static final Map<UUID, Retour> RETOURS = new HashMap<>();
    private static final String[] FAUX = {"Alex", "Noor", "Kai", "Zuri", "Sunny", "Efe", "Ari", "Makena", "Steve", "Lina", "Tom", "Inès"};

    public static boolean enCours() {
        return actif;
    }

    /**
     * Lance la cinématique pour ces joueurs. Faux si l'aéroport n'existe pas (ou si elle tourne déjà).
     *
     * @param pourDeVrai le /start : à la fin, chacun se réveille dans l'avion en feu ; sinon chacun revient là où il était
     * @param depart     on peut commencer plus loin (en secondes), pour les tests
     * @param faux       des passagers inventés en plus des joueurs (pour voir l'avion plus rempli de « joueurs »)
     */
    public static boolean lancer(MinecraftServer server, List<ServerPlayer> joueurs, boolean pourDeVrai, float depart, int faux) {
        ServerLevel aeroport = server.getLevel(DIMENSION);
        if (aeroport == null || actif || joueurs.isEmpty()) {
            return false;
        }
        HubData data = HubData.get(server);
        if (data.versionAeroport() != AeroportBuilder.VERSION) {
            AeroportBuilder.construire(aeroport);
            data.setVersionAeroport(AeroportBuilder.VERSION);
        }
        long graine = new Random().nextLong();
        List<ServerPlayer> ordre = new ArrayList<>(joueurs);
        Collections.shuffle(ordre, new Random(graine));
        List<Passager> passagers = new ArrayList<>();
        for (ServerPlayer j : ordre) {
            if (passagers.size() < SIEGES) {
                passagers.add(new Passager(j.getUUID(), j.getGameProfile().getName()));
            }
        }
        for (int i = 0; i < faux && passagers.size() < SIEGES; i++) {
            String nom = FAUX[i % FAUX.length];
            passagers.add(new Passager(UUID.nameUUIDFromBytes(("cabane:faux/" + i).getBytes(StandardCharsets.UTF_8)), nom));
        }
        Decollage.pourDeVrai = pourDeVrai;
        actif = true;
        ticks = 0;
        debutNanos = System.nanoTime();
        duree = DUREE - depart;
        avionCharge = false;
        RETOURS.clear();
        Debut debut = new Debut(graine, depart, passagers);
        BlockPos cave = AeroportBuilder.ATTENTE;
        for (ServerPlayer j : joueurs) {
            RETOURS.put(j.getUUID(), new Retour(j.level().dimension(), j.position(), j.getYRot(), j.getXRot(), j.gameMode.getGameModeForPlayer()));
            if (j.isSleeping()) {
                j.stopSleepInBed(true, true);
            }
            j.stopRiding();
            j.teleportTo(aeroport, cave.getX() + 0.5, cave.getY(), cave.getZ() + 0.5, 0F, 0F);
            // il ne tombe pas de sa cage, même si le terrain tarde à arriver sur son écran
            j.setNoGravity(true);
            if (j.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                j.setGameMode(GameType.ADVENTURE);
            }
            // les effets durent bien plus que la cinématique (le serveur peut ramer) ; ils sont retirés à la sortie
            int effets = (int) (duree * 20) + 20 * 60;
            j.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, effets, 4, false, false));
            j.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, effets, 0, false, false));
            j.addEffect(new MobEffectInstance(MobEffects.SATURATION, effets, 0, false, false));
            j.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, effets, 0, false, false));
            PacketDistributor.sendToPlayer(j, debut);
        }
        Cabane.LOGGER.info("Décollage : {} joueur(s), {} passager(s), {} s", joueurs.size(), passagers.size(), (int) duree);
        return true;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!ARRIVEES.isEmpty()) {
            arrivees(server);
        }
        if (!actif) {
            return;
        }
        ticks++;
        double ecoule = (System.nanoTime() - debutNanos) / 1e9;
        if (ecoule < duree) {
            // l'avion en feu se charge un peu avant la fin : on y arrive sans attendre le terrain
            if (pourDeVrai && !avionCharge && ecoule >= duree - 5) {
                chargerAvion(server, true);
                avionCharge = true;
            }
            // personne ne quitte la cage (un joueur qui pousse l'autre, un bug de collision…)
            if (ticks % 20 == 0) {
                BlockPos cave = AeroportBuilder.ATTENTE;
                for (UUID u : RETOURS.keySet()) {
                    ServerPlayer j = server.getPlayerList().getPlayer(u);
                    if (j != null && j.level().dimension() == DIMENSION && j.position().distanceToSqr(Vec3.atBottomCenterOf(cave)) > 4) {
                        j.teleportTo(cave.getX() + 0.5, cave.getY(), cave.getZ() + 0.5);
                    }
                }
            }
            return;
        }
        actif = false;
        for (Map.Entry<UUID, Retour> e : RETOURS.entrySet()) {
            ServerPlayer j = server.getPlayerList().getPlayer(e.getKey());
            if (j != null && j.level().dimension() == DIMENSION) {
                sortir(j, e.getValue());
            }
        }
        RETOURS.clear();
    }

    private static void sortir(ServerPlayer j, Retour r) {
        j.removeEffect(MobEffects.DAMAGE_RESISTANCE);
        j.removeEffect(MobEffects.FIRE_RESISTANCE);
        j.removeEffect(MobEffects.SATURATION);
        j.removeEffect(MobEffects.INVISIBILITY);
        j.fallDistance = 0;
        if (pourDeVrai) {
            reveil(j);
        } else {
            ServerLevel monde = j.server.getLevel(r.dimension());
            if (monde == null) {
                monde = j.server.overworld();
            }
            j.teleportTo(monde, r.pos().x, r.pos().y, r.pos().z, r.yaw(), r.pitch());
            if (j.gameMode.getGameModeForPlayer() != r.mode()) {
                j.setGameMode(r.mode());
            }
        }
        // le temps que le terrain arrive sur son écran, il ne tombe pas (sinon il passerait à travers le plancher de l'avion)
        j.setNoGravity(true);
        ARRIVEES.put(j.getUUID(), new Arrivee(j.position(), 0));
    }

    /** Un joueur qui vient d'arriver (dans l'avion en feu, ou là d'où il était parti) : où, et depuis combien de ticks. */
    private record Arrivee(Vec3 pos, int ticks) {
    }

    private static final Map<UUID, Arrivee> ARRIVEES = new HashMap<>();

    /**
     * Les premières secondes après l'arrivée : la gravité revient au bout de 2 s ; et si le joueur passe quand même à
     * travers le sol (le terrain arrivé trop tard sur son écran), on le remet à sa place. Après 2,5 s, il est libre
     * (sauter de l'avion tout de suite reste possible).
     */
    private static void arrivees(MinecraftServer server) {
        for (UUID u : List.copyOf(ARRIVEES.keySet())) {
            ServerPlayer j = server.getPlayerList().getPlayer(u);
            Arrivee a = ARRIVEES.get(u);
            if (j == null) {
                ARRIVEES.remove(u);
                continue;
            }
            int age = a.ticks() + 1;
            if (j.getY() < a.pos().y - 6) {
                j.teleportTo(a.pos().x, a.pos().y, a.pos().z);
                j.fallDistance = 0;
            }
            if (age == 40) {
                j.setNoGravity(false);
            }
            if (age >= 50) {
                ARRIVEES.remove(u);
            } else {
                ARRIVEES.put(u, new Arrivee(a.pos(), age));
            }
        }
        if (ARRIVEES.isEmpty()) {
            chargerAvion(server, false);
        }
    }

    /** Garde chargés (ou libère) les chunks de l'avion en feu, au-dessus de Beauvais. */
    private static void chargerAvion(MinecraftServer server, boolean charger) {
        BlockPos centre = HubData.get(server).centre();
        if (centre == null) {
            return;
        }
        net.minecraft.world.level.ChunkPos p = new net.minecraft.world.level.ChunkPos(HubBuilder.dansAvion(centre));
        var chunks = server.overworld().getChunkSource();
        if (charger) {
            chunks.addRegionTicket(net.minecraft.server.level.TicketType.FORCED, p, 2, p);
        } else {
            chunks.removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, p, 2, p);
        }
    }

    /** Aucune créature n'apparaît d'elle-même à l'aéroport (des mods en ajoutent à tous les biomes). */
    @SubscribeEvent
    public static void apparition(net.neoforged.neoforge.event.entity.living.MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getLevel().getLevel().dimension() == DIMENSION && (event.getSpawnType() == net.minecraft.world.entity.MobSpawnType.NATURAL
                || event.getSpawnType() == net.minecraft.world.entity.MobSpawnType.CHUNK_GENERATION)) {
            event.setResult(net.neoforged.neoforge.event.entity.living.MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /** Après la cinématique (ou à la reconnexion de quelqu'un parti pendant) : le réveil dans l'avion en feu. */
    private static void reveil(ServerPlayer j) {
        if (j.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
            j.setGameMode(GameType.SURVIVAL);
        }
        if (!HubEvents.welcome(j)) {
            HubEvents.reveil(j);
        }
    }

    /** Parti en pleine cinématique (ou serveur arrêté pendant) : à son retour, on ne la rejoue pas, on le pose à l'arrivée. */
    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer j)) {
            return;
        }
        // le serveur s'est arrêté pendant ses 2 secondes d'arrivée : il retrouve la gravité
        if (j.isNoGravity()) {
            j.setNoGravity(false);
        }
        if (j.level().dimension() != DIMENSION) {
            return;
        }
        Retour r = RETOURS.remove(j.getUUID());
        if (HubData.get(j.server).demarre()) {
            reveil(j);
        } else if (r != null) {
            pourDeVrai = false;
            sortir(j, r);
        } else {
            ServerLevel monde = j.server.overworld();
            BlockPos s = monde.getSharedSpawnPos();
            j.teleportTo(monde, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0F, 0F);
            HubEvents.accueillir(j);
        }
    }

    @SubscribeEvent
    public static void demarrage(ServerStartedEvent event) {
        actif = false;
        RETOURS.clear();
        ARRIVEES.clear();
    }

    /**
     * Pour l'hôte : /cabane decollage [depart] [faux] rejoue la cinématique (chacun revient ensuite là où il était),
     * /cabane decollage reconstruire refait l'aéroport.
     */
    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cabane")
                .then(Commands.literal("decollage").requires(Decollage::hote)
                        .executes(ctx -> repeter(ctx.getSource(), 0, 0))
                        .then(Commands.literal("reconstruire").executes(ctx -> {
                            ServerLevel aeroport = ctx.getSource().getServer().getLevel(DIMENSION);
                            if (aeroport == null) {
                                ctx.getSource().sendFailure(Component.literal("La dimension de l'aéroport est introuvable."));
                                return 0;
                            }
                            AeroportBuilder.construire(aeroport);
                            HubData.get(ctx.getSource().getServer()).setVersionAeroport(AeroportBuilder.VERSION);
                            ctx.getSource().sendSuccess(() -> Component.literal("L'aéroport est reconstruit."), true);
                            return 1;
                        }))
                        .then(Commands.argument("depart", FloatArgumentType.floatArg(0, (float) DUREE - 1))
                                .executes(ctx -> repeter(ctx.getSource(), FloatArgumentType.getFloat(ctx, "depart"), 0))
                                .then(Commands.argument("faux", IntegerArgumentType.integer(0, SIEGES))
                                        .executes(ctx -> repeter(ctx.getSource(), FloatArgumentType.getFloat(ctx, "depart"),
                                                IntegerArgumentType.getInteger(ctx, "faux")))))));
    }

    /** L'hôte : un admin du serveur, ou le joueur qui a ouvert le monde en solo. */
    private static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    private static int repeter(CommandSourceStack source, float depart, int faux) {
        MinecraftServer server = source.getServer();
        List<ServerPlayer> joueurs = server.getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator() || p == source.getPlayer()).toList();
        if (!lancer(server, joueurs, false, depart, faux)) {
            source.sendFailure(Component.literal(actif ? "La cinématique est déjà en cours." : "L'aéroport est introuvable."));
            return 0;
        }
        return 1;
    }

    private Decollage() {
    }
}
