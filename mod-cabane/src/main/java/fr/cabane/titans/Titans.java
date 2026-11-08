package fr.cabane.titans;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import fr.cabane.Cabane;
import fr.cabane.hub.Accueil;
import fr.cabane.hub.Decollage;
import fr.cabane.hub.HubBuilder;
import fr.cabane.hub.HubData;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * L'attaque des titans, l'événement d'Eren. L'hôte tape /cabane evenement eren : tout le monde (même ceux qui sont
 * ailleurs) est ramené au village et voit la même cinématique (133 s, ClientTitans) : le discours d'Erwin, plan par plan
 * comme la vidéo fournie et avec sa bande-son (pack privé), la foule des joueurs, la charge à cheval, les rochers, Erwin
 * à terre ; puis le Barbu qui enjambe la palissade près de la forge, Eren, derrière la forge, qui « savait », et tous les
 * titans autour de Beauvais. Ensuite, les titans apparaissent pour de vrai tout autour du village, juste derrière la
 * palissade, et marchent sur les joueurs. Une barre en haut de l'écran compte ceux qui restent ; quand le dernier tombe,
 * Beauvais a tenu.
 *
 * <p>Comme pour l'accueil, le serveur ne fait que poser le décor : pendant la scène, les joueurs attendent au centre du
 * village (invisibles, intouchables), puis se retrouvent sur la place quand les titans arrivent.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Titans {
    /** La durée de la cinématique, en secondes (le client a la même). */
    public static final double DUREE = 133;
    public static final int MAX = 10;
    /** Où attendent les joueurs pendant la scène : au centre, pour que tout le village (jusqu'à la palissade) soit chargé. */
    private static final Vec3 ATTENTE = new Vec3(0.5, 1, 12.5);
    /** Où ils se retrouvent quand les titans arrivent : sur la place, face au sud. */
    private static final Vec3 PLACE = new Vec3(0.5, 1, 15.5);

    private static final DeferredRegister<EntityType<?>> ENTITES = DeferredRegister.create(Registries.ENTITY_TYPE, Cabane.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<Titan>> TITAN = ENTITES.register("titan",
            () -> EntityType.Builder.<Titan>of(Titan::new, MobCategory.MONSTER).sized(3F, 15F).clientTrackingRange(16).updateInterval(2).build("titan"));

    public static void register(IEventBus modBus) {
        ENTITES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent e) -> e.put(TITAN.get(), Titan.attributs().build()));
    }

    /** Serveur → client : la cinématique commence (à {@code depart} secondes), au village de ce centre, avec ces joueurs. */
    public record Debut(BlockPos centre, float depart, List<Decollage.Passager> joueurs) implements CustomPacketPayload {
        public static final Type<Debut> TYPE = new Type<>(Cabane.id("titans"));
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
                    (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.titans.ClientTitans.demarrer(p)));
        }
    }

    // ---------------------------------------------------------------- la cinématique

    private static boolean actif;
    private static long debutNanos;
    private static double duree;
    private static int ticks;
    private static BlockPos centre;
    private static final List<UUID> ACTEURS = new ArrayList<>();
    private static final String[] FAUX = {"Alex", "Noor", "Kai", "Zuri", "Sunny", "Efe", "Ari", "Makena", "Lina", "Tom"};

    public static boolean enCours() {
        return actif;
    }

    /** Lance la cinématique pour tous les joueurs (au plus dix à cheval derrière Erwin), puis la vague de titans. */
    public static String lancer(MinecraftServer server, float depart, int faux) {
        BlockPos c = HubData.get(server).centre();
        if (c == null) {
            return "Le village n'est pas encore construit.";
        }
        // relancée pendant qu'elle joue : elle recommence (pour tout le monde)
        if (Accueil.enCours() || Decollage.enCours()) {
            return "Une autre cinématique est en cours.";
        }
        List<ServerPlayer> joueurs = server.getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).toList();
        if (joueurs.isEmpty()) {
            return "Personne pour se battre.";
        }
        ServerLevel monde = server.overworld();
        centre = c;
        List<Decollage.Passager> liste = new ArrayList<>();
        ACTEURS.clear();
        for (ServerPlayer j : joueurs) {
            ACTEURS.add(j.getUUID());
            if (liste.size() < MAX) {
                liste.add(new Decollage.Passager(j.getUUID(), j.getGameProfile().getName()));
            }
        }
        for (int i = 0; i < faux && liste.size() < MAX; i++) {
            liste.add(new Decollage.Passager(UUID.nameUUIDFromBytes(("cabane:faux/" + i).getBytes(StandardCharsets.UTF_8)), FAUX[i % FAUX.length]));
        }
        actif = true;
        ticks = 0;
        duree = DUREE - depart;
        debutNanos = System.nanoTime();
        int effets = (int) (duree * 20) + 20 * 20;
        Vec3 a = monde(ATTENTE);
        Debut debut = new Debut(c, depart, liste);
        for (ServerPlayer j : joueurs) {
            if (j.isSleeping()) {
                j.stopSleepInBed(true, true);
            }
            j.stopRiding();
            j.teleportTo(monde, a.x, a.y, a.z, 180F, 0F);
            j.fallDistance = 0;
            j.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, effets, 4, false, false));
            j.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, effets, 0, false, false));
            j.addEffect(new MobEffectInstance(MobEffects.SATURATION, effets, 0, false, false));
            PacketDistributor.sendToPlayer(j, debut);
        }
        for (ServerPlayer j : server.getPlayerList().getPlayers()) {
            if (j.isSpectator() && j.level() == monde) {
                PacketDistributor.sendToPlayer(j, debut);
            }
        }
        Cabane.LOGGER.info("Titans : {} joueur(s), cinématique de {} s", joueurs.size(), (int) duree);
        return "";
    }

    private static Vec3 monde(Vec3 relatif) {
        return new Vec3(centre.getX() + relatif.x, centre.getY() + relatif.y, centre.getZ() + relatif.z);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (actif) {
            ticks++;
            double ecoule = (System.nanoTime() - debutNanos) / 1e9;
            if (ecoule < duree) {
                if (ticks % 20 == 0) {
                    // personne ne quitte l'avenue pendant la scène
                    Vec3 a = monde(ATTENTE);
                    for (UUID u : ACTEURS) {
                        ServerPlayer j = server.getPlayerList().getPlayer(u);
                        if (j != null && (j.level() != server.overworld() || j.position().distanceToSqr(a) > 4)) {
                            j.teleportTo(server.overworld(), a.x, a.y, a.z, 180F, 0F);
                            j.fallDistance = 0;
                        }
                    }
                }
                return;
            }
            actif = false;
            int n = 0;
            for (UUID u : ACTEURS) {
                ServerPlayer j = server.getPlayerList().getPlayer(u);
                if (j != null) {
                    sortir(j, n++);
                }
            }
            ACTEURS.clear();
            // le maximum de titans, quel que soit le nombre de joueurs
            vague(server, MAX_TITANS);
            return;
        }
        if (server.getTickCount() % 20 == 0) {
            compter(server);
        }
    }

    private static void sortir(ServerPlayer j, int i) {
        j.removeEffect(MobEffects.DAMAGE_RESISTANCE);
        j.removeEffect(MobEffects.INVISIBILITY);
        j.removeEffect(MobEffects.SATURATION);
        // sur la place, en rangs, face au sud
        Vec3 p = monde(PLACE.add((i % 5 - 2) * 1.6, 0, (i / 5) * 1.8));
        j.teleportTo(j.server.overworld(), p.x, p.y, p.z, 0F, 0F);
        j.fallDistance = 0;
    }

    /** Parti en pleine scène : à son retour, on lui retire les effets et il arrive sur la place. */
    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer j && !actif && ACTEURS.remove(j.getUUID()) && centre != null) {
            sortir(j, 0);
        }
    }

    @SubscribeEvent
    public static void demarrage(ServerStartedEvent event) {
        actif = false;
        ACTEURS.clear();
        enVague = false;
        total = 0;
        BARRE.removeAllPlayers();
    }

    // ---------------------------------------------------------------- la vague de titans

    private static final ServerBossEvent BARRE = new ServerBossEvent(Component.literal("Les titans"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
    private static boolean enVague;
    private static int total;
    /** Jamais plus de titans en même temps autour du village, quel que soit le nombre demandé ou le nombre de joueurs. */
    public static final int MAX_TITANS = 20;
    /** Les titans venus avec un raid : c'est le raid qui les compte, pas la barre des titans. */
    private static final String RENFORT = "cabane_titan_renfort";

    private static int debout(ServerLevel monde, BlockPos c) {
        return monde.getEntities(TITAN.get(), new AABB(c).inflate(400, 200, 400), Titan::isAlive).size();
    }

    /** Pose ce titan dans cette direction, juste derrière la palissade, tourné vers la place. */
    private static boolean poser(ServerLevel monde, BlockPos c, Titan t, Titan.Sorte sorte, double angle, Random r) {
        t.preparer(sorte);
        Vec3 p = place(monde, c, t, angle, r);
        float lacet = (float) Math.toDegrees(Math.atan2(-(c.getX() + 0.5 - p.x), c.getZ() + 0.5 - p.z));
        t.moveTo(p.x, p.y, p.z, lacet, 0F);
        t.yBodyRot = lacet;
        t.yHeadRot = lacet;
        t.finalizeSpawn(monde, monde.getCurrentDifficultyAt(BlockPos.containing(p)), MobSpawnType.EVENT, null);
        t.preparer(sorte);
        return monde.addFreshEntity(t);
    }

    /**
     * Un titan qui accompagne un raid, venu de cette direction (π/2 : le sud). Null si le maximum est déjà atteint.
     * {@code tags} : ce que le raid veut retrouver sur lui.
     */
    public static Titan renfort(ServerLevel monde, BlockPos c, double angle, int rang, String... tags) {
        if (debout(monde, c) >= MAX_TITANS) {
            return null;
        }
        // le terrain doit être là pour lui trouver une place, même si personne n'est à côté
        double d = HubBuilder.rayon() + 4;
        int cx = (c.getX() + (int) Math.round(Math.cos(angle) * d)) >> 4, cz = (c.getZ() + (int) Math.round(Math.sin(angle) * d)) >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                monde.getChunk(cx + dx, cz + dz);
            }
        }
        Titan t = TITAN.get().create(monde);
        if (t == null) {
            return null;
        }
        t.addTag(RENFORT);
        for (String tag : tags) {
            t.addTag(tag);
        }
        return poser(monde, c, t, Titan.Sorte.de(rang), angle, new Random()) ? t : null;
    }

    /** Fait apparaître {@code nombre} titans tout autour du village (sans dépasser le maximum), tournés vers la place. */
    public static int vague(MinecraftServer server, int nombre) {
        BlockPos c = HubData.get(server).centre();
        if (c == null) {
            return 0;
        }
        ServerLevel monde = server.overworld();
        nombre = Math.min(nombre, MAX_TITANS - debout(monde, c));
        Random r = new Random();
        double depart = r.nextDouble() * Math.PI * 2;
        int poses = 0;
        for (int i = 0; i < nombre; i++) {
            Titan t = TITAN.get().create(monde);
            if (t != null && poser(monde, c, t, Titan.Sorte.de(i), depart + i * Math.PI * 2 / nombre, r)) {
                poses++;
            }
        }
        if (poses > 0) {
            enVague = true;
            total = poses;
            for (ServerPlayer j : monde.players()) {
                j.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
                j.connection.send(new ClientboundSetTitleTextPacket(Component.literal("LES TITANS !").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
                j.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Visez la nuque.").withStyle(ChatFormatting.GRAY)));
                monde.playSound(null, j.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 64F, 0.8F);
            }
            compter(server);
        }
        Cabane.LOGGER.info("Titans : {} titans autour du village", poses);
        return poses;
    }

    /**
     * Une place dans cette direction, juste derrière la palissade, assez dégagée pour un géant (pas de feuillage dans sa
     * hauteur) ; sinon on essaie un peu à côté, puis on se rabat dans le village.
     */
    private static Vec3 place(ServerLevel monde, BlockPos c, Titan t, double angle, Random r) {
        int rayon = HubBuilder.rayon();
        for (int essai = 0; essai < 24; essai++) {
            double a = angle + (essai == 0 ? 0 : (r.nextDouble() - 0.5) * 0.9);
            double d = rayon + 4 + r.nextDouble() * 10 - (essai > 12 ? 14 : 0);
            int x = c.getX() + (int) Math.round(Math.cos(a) * d);
            int z = c.getZ() + (int) Math.round(Math.sin(a) * d);
            int y = monde.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            Vec3 p = new Vec3(x + 0.5, y, z + 0.5);
            AABB boite = new AABB(p.x - t.getBbWidth() / 2, y + 0.6, p.z - t.getBbWidth() / 2, p.x + t.getBbWidth() / 2, y + t.getBbHeight(), p.z + t.getBbWidth() / 2);
            if (Math.abs(y - c.getY()) < 14 && monde.noCollision(boite) && !monde.containsAnyLiquid(boite)) {
                return p;
            }
        }
        double d = rayon - 12;
        int x = c.getX() + (int) Math.round(Math.cos(angle) * d);
        int z = c.getZ() + (int) Math.round(Math.sin(angle) * d);
        return new Vec3(x + 0.5, monde.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
    }

    /** Compte les titans encore debout et tient la barre à jour ; le dernier tombé, Beauvais a tenu. */
    private static void compter(MinecraftServer server) {
        BlockPos c = HubData.get(server).centre();
        ServerLevel monde = server.overworld();
        if (c == null) {
            return;
        }
        List<Titan> debout = monde.getEntities(TITAN.get(), new AABB(c).inflate(400, 200, 400), t -> t.isAlive() && !t.getTags().contains(RENFORT));
        if (!enVague) {
            if (debout.isEmpty()) {
                return;
            }
            // des titans d'une vague d'avant un redémarrage : on reprend le compte
            enVague = true;
            total = debout.size();
        }
        if (debout.isEmpty()) {
            enVague = false;
            BARRE.removeAllPlayers();
            for (ServerPlayer j : monde.players()) {
                j.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 30));
                j.connection.send(new ClientboundSetTitleTextPacket(Component.literal("BEAUVAIS A TENU").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
                j.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Le dernier titan est tombé.").withStyle(ChatFormatting.WHITE)));
                monde.playSound(null, j.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1F, 1F);
            }
            return;
        }
        total = Math.max(total, debout.size());
        BARRE.setName(Component.literal(debout.size() > 1 ? "Les titans : encore " + debout.size() + " debout" : "Le dernier titan")
                .withStyle(ChatFormatting.RED));
        BARRE.setProgress(debout.size() / (float) total);
        for (ServerPlayer j : server.getPlayerList().getPlayers()) {
            if (j.level() == monde) {
                BARRE.addPlayer(j);
            } else {
                BARRE.removePlayer(j);
            }
        }
    }

    /** Fin de l'événement : les titans disparaissent (pour l'hôte, ou après un test). */
    public static int fin(MinecraftServer server) {
        BlockPos c = HubData.get(server).centre();
        int n = 0;
        if (c != null) {
            for (Titan t : server.overworld().getEntities(TITAN.get(), new AABB(c).inflate(400, 200, 400), t -> true)) {
                t.discard();
                n++;
            }
        }
        enVague = false;
        BARRE.removeAllPlayers();
        return n;
    }

    // ---------------------------------------------------------------- les commandes

    /**
     * /cabane evenement eren : la cinématique pour tout le monde, puis les titans. Pour tester : /cabane evenement eren
     * &lt;depart&gt; [faux] (à partir de telle seconde, avec des faux cavaliers en plus), /cabane evenement eren titans [nombre]
     * (les titans tout de suite, sans la scène), /cabane evenement eren fin (ils disparaissent).
     */
    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cabane").then(Commands.literal("evenement").requires(Titans::hote)
                .then(Commands.literal("eren")
                        .executes(ctx -> lancerDepuis(ctx.getSource(), 0, 0))
                        .then(Commands.literal("titans")
                                .executes(ctx -> vagueDepuis(ctx.getSource(), MAX_TITANS))
                                .then(Commands.argument("nombre", IntegerArgumentType.integer(1, 60))
                                        .executes(ctx -> vagueDepuis(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "nombre")))))
                        .then(Commands.literal("fin").executes(ctx -> {
                            int n = fin(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal(n + " titan(s) retiré(s)."), true);
                            return n;
                        }))
                        .then(Commands.argument("depart", FloatArgumentType.floatArg(0, (float) DUREE - 1))
                                .executes(ctx -> lancerDepuis(ctx.getSource(), FloatArgumentType.getFloat(ctx, "depart"), 0))
                                .then(Commands.argument("faux", IntegerArgumentType.integer(0, MAX))
                                        .executes(ctx -> lancerDepuis(ctx.getSource(), FloatArgumentType.getFloat(ctx, "depart"),
                                                IntegerArgumentType.getInteger(ctx, "faux"))))))));
    }

    private static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    private static int lancerDepuis(CommandSourceStack source, float depart, int faux) {
        String refus = lancer(source.getServer(), depart, faux);
        if (!refus.isEmpty()) {
            source.sendFailure(Component.literal(refus));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Les titans arrivent…").withStyle(ChatFormatting.DARK_RED), true);
        return 1;
    }

    private static int vagueDepuis(CommandSourceStack source, int nombre) {
        int n = vague(source.getServer(), nombre);
        source.sendSuccess(() -> Component.literal(n == 0 ? "Il y a déjà " + MAX_TITANS + " titans autour du village." : n + " titan(s) autour du village."), true);
        return n;
    }

    private Titans() {
    }
}
