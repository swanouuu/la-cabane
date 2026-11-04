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
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
 * L'accueil à Beauvais : une fois que tout le monde a atterri, l'hôte tape /accueil. Tout le monde voit la même
 * cinématique (68 s) : l'atterrissage sur l'avenue du sud, le Président qui vient les accueillir avec sa garde, un tour
 * rapide du village, et le Hejj sur son porche. Chacun y joue avec son skin, et le Président retient le pseudo de l'un
 * d'eux (tiré au sort).
 *
 * <p>Le serveur ne fait que poser le décor : il range les joueurs (invisibles, intouchables) sur la place, le temps que
 * la caméra filme, donne le top départ, puis à la fin dépose chacun au pied du porche du Hejj. La cinématique elle-même
 * (les doubles des joueurs et des PNJ, la caméra, les répliques) est calculée sur l'écran de chacun : ClientAccueil.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Accueil {
    /** La durée de la cinématique, en secondes (le client a la même). */
    public static final double DUREE = 68;
    /** Au plus dix joueurs dans la scène (deux rangées de cinq sur l'avenue). */
    public static final int MAX = 10;
    /** Où attendent les joueurs pendant la scène : sur la place, au sud du grand panneau (relatif au centre). */
    private static final Vec3 ATTENTE = new Vec3(0.5, 1, 15.5);

    /** Serveur → client : la cinématique commence (à {@code depart} secondes), au village de ce centre, avec ces joueurs. */
    public record Debut(BlockPos centre, float depart, List<Decollage.Passager> joueurs) implements CustomPacketPayload {
        public static final Type<Debut> TYPE = new Type<>(Cabane.id("accueil"));
        public static final StreamCodec<ByteBuf, Debut> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Debut::centre, ByteBufCodecs.FLOAT, Debut::depart,
                Decollage.Passager.CODEC.apply(ByteBufCodecs.list(MAX)), Debut::joueurs, Debut::new);

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
                    (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.cinema.ClientAccueil.demarrer(p)));
        }
    }

    private static boolean actif;
    private static int ticks;
    private static long debutNanos;
    private static double duree;
    private static BlockPos centre;
    /** Les joueurs de la scène, dans l'ordre de la cinématique (le premier est celui que le Président retient). */
    private static final List<UUID> ORDRE = new ArrayList<>();
    private static final Map<UUID, Integer> PLACES = new HashMap<>();
    private static int nombre;
    private static final String[] FAUX = {"Alex", "Noor", "Kai", "Zuri", "Sunny", "Efe", "Ari", "Makena", "Lina", "Tom"};

    public static boolean enCours() {
        return actif;
    }

    /** Lance la cinématique pour ces joueurs (au plus dix), avec des faux joueurs en plus pour les tests. */
    public static boolean lancer(MinecraftServer server, List<ServerPlayer> joueurs, float depart, int faux) {
        BlockPos c = HubData.get(server).centre();
        if (c == null || actif || joueurs.isEmpty() || Decollage.enCours()) {
            return false;
        }
        long graine = new Random().nextLong();
        List<ServerPlayer> ordre = new ArrayList<>(joueurs);
        Collections.shuffle(ordre, new Random(graine));
        if (ordre.size() > MAX) {
            ordre = new ArrayList<>(ordre.subList(0, MAX));
        }
        List<Decollage.Passager> liste = new ArrayList<>();
        ORDRE.clear();
        PLACES.clear();
        for (ServerPlayer j : ordre) {
            PLACES.put(j.getUUID(), liste.size());
            ORDRE.add(j.getUUID());
            liste.add(new Decollage.Passager(j.getUUID(), j.getGameProfile().getName()));
        }
        for (int i = 0; i < faux && liste.size() < MAX; i++) {
            liste.add(new Decollage.Passager(UUID.nameUUIDFromBytes(("cabane:faux/" + i).getBytes(StandardCharsets.UTF_8)), FAUX[i % FAUX.length]));
        }
        nombre = liste.size();
        centre = c;
        actif = true;
        ticks = 0;
        debutNanos = System.nanoTime();
        duree = DUREE - depart;
        Debut debut = new Debut(c, depart, liste);
        ServerLevel monde = server.overworld();
        int effets = (int) (duree * 20) + 20 * 30;
        for (ServerPlayer j : server.getPlayerList().getPlayers()) {
            if (!PLACES.containsKey(j.getUUID())) {
                // un spectateur (ou le onzième) : il voit quand même la scène, sans y être
                if (j.level() == monde) {
                    PacketDistributor.sendToPlayer(j, debut);
                }
                continue;
            }
            if (j.isSleeping()) {
                j.stopSleepInBed(true, true);
            }
            j.stopRiding();
            Vec3 a = monde(ATTENTE);
            j.teleportTo(monde, a.x, a.y, a.z, 180F, 0F);
            j.fallDistance = 0;
            j.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, effets, 4, false, false));
            j.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, effets, 0, false, false));
            j.addEffect(new MobEffectInstance(MobEffects.SATURATION, effets, 0, false, false));
            PacketDistributor.sendToPlayer(j, debut);
        }
        Cabane.LOGGER.info("Accueil : {} joueur(s) dans la scène, {} s", nombre, (int) duree);
        return true;
    }

    private static Vec3 monde(Vec3 relatif) {
        return new Vec3(centre.getX() + relatif.x, centre.getY() + relatif.y, centre.getZ() + relatif.z);
    }

    /**
     * Au pied du porche du Hejj, là où la cinématique laisse chacun : deux rangées de cinq, face au porche (la même
     * formule que ClientAccueil).
     */
    public static Vec3 porche(int i, int n) {
        int cols = Math.min(n, 5);
        int rangs = (n + 4) / 5;
        int c = i % 5;
        int rg = i / 5;
        int dansRang = rg == rangs - 1 ? n - rg * 5 : cols;
        return new Vec3(3.5 + (c - (dansRang - 1) / 2.0) * 1.8, 1, -27.2 + rg * 1.9);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!actif) {
            return;
        }
        MinecraftServer server = event.getServer();
        ticks++;
        double ecoule = (System.nanoTime() - debutNanos) / 1e9;
        if (ecoule < duree) {
            // personne ne quitte sa place pendant la scène
            if (ticks % 20 == 0) {
                Vec3 a = monde(ATTENTE);
                for (UUID u : ORDRE) {
                    ServerPlayer j = server.getPlayerList().getPlayer(u);
                    if (j != null && j.position().distanceToSqr(a) > 4) {
                        j.teleportTo(a.x, a.y, a.z);
                        j.fallDistance = 0;
                    }
                }
            }
            return;
        }
        actif = false;
        for (UUID u : ORDRE) {
            ServerPlayer j = server.getPlayerList().getPlayer(u);
            if (j != null) {
                sortir(j, PLACES.get(u));
            }
        }
        ORDRE.clear();
        PLACES.clear();
    }

    private static void sortir(ServerPlayer j, int place) {
        j.removeEffect(MobEffects.DAMAGE_RESISTANCE);
        j.removeEffect(MobEffects.INVISIBILITY);
        j.removeEffect(MobEffects.SATURATION);
        Vec3 p = monde(porche(place, nombre));
        j.teleportTo(j.server.overworld(), p.x, p.y, p.z, 180F, 0F);
        j.fallDistance = 0;
    }

    /** Parti en pleine scène : à son retour, on lui retire les effets et il arrive directement au porche. */
    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer j) || actif) {
            return;
        }
        Integer place = PLACES.remove(j.getUUID());
        if (place != null && centre != null) {
            sortir(j, place);
        }
    }

    @SubscribeEvent
    public static void demarrage(ServerStartedEvent event) {
        actif = false;
        ORDRE.clear();
        PLACES.clear();
    }

    /** /accueil pour l'hôte, une fois tout le monde au sol ; /cabane accueil [depart] [faux] pour la rejouer ou la tester. */
    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("accueil").requires(Accueil::hote)
                .executes(ctx -> lancerDepuis(ctx.getSource(), 0, 0)));
        event.getDispatcher().register(Commands.literal("cabane")
                .then(Commands.literal("accueil").requires(Accueil::hote)
                        .executes(ctx -> lancerDepuis(ctx.getSource(), 0, 0))
                        .then(Commands.literal("faux")
                                .executes(ctx -> lancerDepuis(ctx.getSource(), 0, 5))
                                .then(Commands.argument("nombre", IntegerArgumentType.integer(0, MAX))
                                        .executes(ctx -> lancerDepuis(ctx.getSource(), 0, IntegerArgumentType.getInteger(ctx, "nombre")))))
                        .then(Commands.argument("depart", FloatArgumentType.floatArg(0, (float) DUREE - 1))
                                .executes(ctx -> lancerDepuis(ctx.getSource(), FloatArgumentType.getFloat(ctx, "depart"), 0))
                                .then(Commands.argument("faux", IntegerArgumentType.integer(0, MAX))
                                        .executes(ctx -> lancerDepuis(ctx.getSource(), FloatArgumentType.getFloat(ctx, "depart"),
                                                IntegerArgumentType.getInteger(ctx, "faux")))))));
    }

    private static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    private static int lancerDepuis(CommandSourceStack source, float depart, int faux) {
        MinecraftServer server = source.getServer();
        List<ServerPlayer> joueurs = server.getPlayerList().getPlayers().stream()
                .filter(p -> !p.isSpectator() && p.level() == server.overworld()).toList();
        if (!lancer(server, joueurs, depart, faux)) {
            source.sendFailure(Component.literal(actif ? "L'accueil est déjà en cours."
                    : HubData.get(server).centre() == null ? "Le village n'est pas encore construit."
                    : joueurs.isEmpty() ? "Personne n'est au village." : "Une autre cinématique est en cours."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("L'accueil commence."), false);
        return 1;
    }

    private Accueil() {
    }
}
