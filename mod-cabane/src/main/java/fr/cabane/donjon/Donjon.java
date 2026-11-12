package fr.cabane.donjon;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import fr.cabane.Cabane;
import fr.cabane.hub.Accueil;
import fr.cabane.hub.Decollage;
import fr.cabane.hub.HubData;
import fr.cabane.titans.Titans;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

/**
 * Le portail de rang S de Sung Jin-woo. On lui parle sur l'avenue du sud, « On y va » : tout le groupe à côté de lui
 * voit la même cinématique (client/donjon/ClientPortail, 30 s) : l'air se fend, le grand portail bleu en spirale
 * s'ouvre, Jin-woo leur parle, puis chacun marche dans le portail, Jin-woo en dernier, et la caméra plonge dans la
 * spirale. Au flash blanc, le serveur les emmène dans le donjon (la dimension cabane:donjon, construite par
 * {@link DonjonBuilder}) ; la fenêtre du Système annonce le donjon.
 *
 * <p>Seuls ceux qui entrent voient la scène ; pendant qu'elle joue, ils attendent sur l'avenue, invisibles et
 * intouchables. Dans le donjon, on est en aventure (rien ne se casse) ; le portail de sortie, derrière l'arrivée,
 * ramène chacun pile là d'où il était parti.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Donjon {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("donjon"));
    /** La durée de la cinématique, en secondes (le client a la même) : au flash blanc, on part. */
    public static final double DUREE = 30;
    /** Les joueurs dessinés dans la scène, au plus. */
    public static final int MAX = 10;
    /** Le portail, relatif au centre de la place : son centre (il flotte au-dessus de l'avenue du sud) et son rayon. */
    public static final Vec3 PORTAIL = new Vec3(0.5, 7.5, 62.5);
    public static final double RAYON_PORTAIL = 6;
    /** Sung Jin-woo (la place de son PNJ, HubEvents), tourné vers l'avenue (l'ouest). */
    public static final Vec3 JINWOO = new Vec3(6.5, 1, 46.5);
    /** Où attendent les joueurs pendant la scène : sur l'avenue, devant le portail. */
    private static final Vec3 ATTENTE = new Vec3(0.5, 1, 50.5);
    /** Le groupe : ceux qui sont à moins de tant de blocs de Jin-woo (ou de l'hôte). */
    private static final double GROUPE = 16;

    private static final DeferredRegister<EntityType<?>> ENTITES = DeferredRegister.create(Registries.ENTITY_TYPE, Cabane.MODID);
    /** Ugrak, l'Ogre Écarlate, le boss de l'arène. */
    public static final DeferredHolder<EntityType<?>, EntityType<Ogre>> OGRE = ENTITES.register("ogre",
            () -> EntityType.Builder.<Ogre>of(Ogre::new, MobCategory.MONSTER).sized(3.6F, 9.9F).fireImmune().clientTrackingRange(16).updateInterval(1)
                    .build("ogre"));

    /** Les sbires : les gobelins et les brutes. */
    public static final DeferredHolder<EntityType<?>, EntityType<Sbire>> GOBELIN = ENTITES.register("gobelin",
            () -> EntityType.Builder.<Sbire>of(Sbire::new, MobCategory.MONSTER).sized(0.7F, 1.45F).clientTrackingRange(10).build("gobelin"));
    public static final DeferredHolder<EntityType<?>, EntityType<Sbire>> BRUTE = ENTITES.register("brute",
            () -> EntityType.Builder.<Sbire>of(Sbire::new, MobCategory.MONSTER).sized(1.3F, 2.6F).clientTrackingRange(10).build("brute"));

    public static void register(IEventBus modBus) {
        ENTITES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent e) -> {
            e.put(OGRE.get(), Ogre.attributs().build());
            e.put(GOBELIN.get(), Sbire.attributsGobelin().build());
            e.put(BRUTE.get(), Sbire.attributsBrute().build());
        });
    }

    /** Serveur → client : la cinématique commence (à {@code depart} secondes), au village de ce centre, avec ces joueurs. */
    public record Debut(BlockPos centre, float depart, List<Decollage.Passager> joueurs) implements CustomPacketPayload {
        public static final Type<Debut> TYPE = new Type<>(Cabane.id("donjon_portail"));
        public static final StreamCodec<ByteBuf, Debut> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Debut::centre, ByteBufCodecs.FLOAT, Debut::depart,
                Decollage.Passager.CODEC.apply(ByteBufCodecs.list(MAX)), Debut::joueurs, Debut::new);

        @Override
        public Type<Debut> type() {
            return TYPE;
        }
    }

    /** Serveur → client : on vient d'arriver dans le donjon (le blanc s'efface, la fenêtre du Système s'affiche). */
    public record Arrivee(int groupe) implements CustomPacketPayload {
        public static final Type<Arrivee> TYPE = new Type<>(Cabane.id("donjon_arrivee"));
        public static final StreamCodec<ByteBuf, Arrivee> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Arrivee::groupe, Arrivee::new);

        @Override
        public Type<Arrivee> type() {
            return TYPE;
        }
    }

    /** Client → serveur : ma cinématique est finie (le flash blanc) : emmène-moi dans le donjon. */
    public record Fini() implements CustomPacketPayload {
        public static final Type<Fini> TYPE = new Type<>(Cabane.id("donjon_fini"));
        public static final StreamCodec<ByteBuf, Fini> CODEC = StreamCodec.unit(new Fini());

        @Override
        public Type<Fini> type() {
            return TYPE;
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reseau {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("1")
                    .playToClient(Debut.TYPE, Debut.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.donjon.ClientPortail.demarrer(p)))
                    .playToClient(Arrivee.TYPE, Arrivee.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.donjon.ClientPortail.arrivee(p)))
                    .playToServer(Fini.TYPE, Fini.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                        if (ctx.player() instanceof ServerPlayer j) {
                            fini(j);
                        }
                    }));
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

    public static ServerLevel niveau(MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static boolean dedans(ServerPlayer joueur) {
        return joueur.level().dimension() == DIMENSION;
    }

    /** Le bouton de Jin-woo : tous ceux qui sont autour de lui (et celui qui a parlé) entrent ensemble. */
    public static void depuisJinwoo(ServerPlayer joueur) {
        if (!DonjonData.get(joueur.server).evenement()) {
            joueur.sendSystemMessage(Component.literal("Sung Jin-woo : « Pas encore. Le portail n'est pas ouvert. »").withStyle(ChatFormatting.DARK_PURPLE));
            return;
        }
        List<ServerPlayer> groupe = autour(joueur);
        String refus = lancer(joueur.server, groupe, 0, 0);
        if (!refus.isEmpty()) {
            joueur.sendSystemMessage(Component.literal("Sung Jin-woo : « " + refus + " »").withStyle(ChatFormatting.DARK_PURPLE));
        }
    }

    /** Ce joueur et ceux qui sont à côté de lui (pas les spectateurs, ni ceux qui sont déjà dans le donjon). */
    private static List<ServerPlayer> autour(ServerPlayer joueur) {
        List<ServerPlayer> groupe = new ArrayList<>();
        groupe.add(joueur);
        for (ServerPlayer j : joueur.serverLevel().players()) {
            if (j != joueur && !j.isSpectator() && j.distanceTo(joueur) < GROUPE) {
                groupe.add(j);
            }
        }
        return groupe;
    }

    /**
     * Lance la cinématique pour ce groupe (à partir de {@code depart} secondes ; {@code faux} : des joueurs inventés en
     * plus, pour les tests), puis les emmène dans le donjon. Renvoie la raison du refus, ou "".
     */
    public static String lancer(MinecraftServer server, List<ServerPlayer> groupe, float depart, int faux) {
        BlockPos c = HubData.get(server).centre();
        if (c == null) {
            return "Le village n'est pas encore construit.";
        }
        if (actif) {
            return "Un groupe est déjà en train d'entrer. Attendez qu'il soit passé.";
        }
        if (Accueil.enCours() || Decollage.enCours() || Titans.enCours()) {
            return "Pas maintenant : il se passe déjà quelque chose au village.";
        }
        ServerLevel donjon = niveau(server);
        if (donjon == null) {
            return "Ce portail ne mène nulle part dans ce monde.";
        }
        List<ServerPlayer> joueurs = groupe.stream().filter(p -> !p.isSpectator() && !dedans(p)).distinct().toList();
        if (joueurs.isEmpty()) {
            return "Personne pour entrer.";
        }
        DonjonData data = DonjonData.get(server);
        if (data.versionConstruite() != DonjonBuilder.VERSION) {
            DonjonBuilder.construire(donjon);
            data.setVersionConstruite(DonjonBuilder.VERSION);
        }
        // un nouveau groupe : le boss est de nouveau là
        Boss.nouveauGroupe(server);
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
        PARTIS = 0;
        GROUPE_TAILLE = joueurs.size();
        ticks = 0;
        duree = DUREE - depart;
        debutNanos = System.nanoTime();
        int effets = (int) (duree * 20) + 10 * 20;
        Vec3 a = monde(ATTENTE);
        Debut debut = new Debut(c, depart, liste);
        for (ServerPlayer j : joueurs) {
            if (j.isSleeping()) {
                j.stopSleepInBed(true, true);
            }
            j.stopRiding();
            // d'où il part : le portail de sortie l'y ramènera
            ServerLevel ici = j.serverLevel();
            data.setRetour(j.getUUID(), new DonjonData.Retour(ici.dimension().location(), j.getX(), j.getY(), j.getZ(), j.getYRot(), j.getXRot()));
            j.teleportTo(monde, a.x, a.y, a.z, 0F, 0F);
            j.fallDistance = 0;
            j.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, effets, 4, false, false));
            j.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, effets, 0, false, false));
            j.addEffect(new MobEffectInstance(MobEffects.SATURATION, effets, 0, false, false));
            PacketDistributor.sendToPlayer(j, debut);
        }
        Cabane.LOGGER.info("Donjon : {} joueur(s) entrent dans le portail ({} s de cinématique)", joueurs.size(), (int) duree);
        return "";
    }

    private static Vec3 monde(Vec3 relatif) {
        return new Vec3(centre.getX() + relatif.x, centre.getY() + relatif.y, centre.getZ() + relatif.z);
    }

    /** Ce joueur a vu toute la scène : il part maintenant (chacun à son rythme, si un écran a pris du retard). */
    private static void fini(ServerPlayer j) {
        if (actif && ACTEURS.remove(j.getUUID())) {
            arriver(j, PARTIS++, GROUPE_TAILLE);
            if (ACTEURS.isEmpty()) {
                actif = false;
            }
        }
    }

    private static int PARTIS;
    private static int GROUPE_TAILLE;
    /** Si un client ne dit jamais qu'il a fini : on l'emmène quand même, tant de secondes après la fin prévue. */
    private static final double RETARD_MAX = 12;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (actif) {
            ticks++;
            double ecoule = (System.nanoTime() - debutNanos) / 1e9;
            if (ecoule < duree + RETARD_MAX) {
                if (ticks % 20 == 0) {
                    // personne ne quitte l'avenue pendant la scène
                    Vec3 a = monde(ATTENTE);
                    for (UUID u : ACTEURS) {
                        ServerPlayer j = server.getPlayerList().getPlayer(u);
                        if (j != null && (j.level() != server.overworld() || j.position().distanceToSqr(a) > 4)) {
                            j.teleportTo(server.overworld(), a.x, a.y, a.z, 0F, 0F);
                            j.fallDistance = 0;
                        }
                    }
                }
            } else {
                actif = false;
                for (UUID u : List.copyOf(ACTEURS)) {
                    ServerPlayer j = server.getPlayerList().getPlayer(u);
                    if (j != null) {
                        arriver(j, PARTIS++, GROUPE_TAILLE);
                        ACTEURS.remove(u);
                    }
                }
            }
        }
        ServerLevel donjon = niveau(server);
        if (donjon == null || donjon.players().isEmpty()) {
            return;
        }
        for (ServerPlayer j : List.copyOf(donjon.players())) {
            // tombé dans le vide : retour à l'arrivée
            if (j.getY() < DonjonBuilder.SOL - 30) {
                Vec3 p = DonjonBuilder.ARRIVEE;
                j.teleportTo(donjon, p.x, p.y, p.z, 0F, 0F);
                j.fallDistance = 0;
            }
            // dans le portail de sortie : on repart
            Vec3 s = DonjonBuilder.SORTIE;
            double dz = j.getZ() - s.z;
            double dx = j.getX() - s.x;
            double dy = j.getY() + 1 - s.y;
            if ((Math.abs(dz) < 0.8 && dx * dx + dy * dy < DonjonBuilder.RAYON_SORTIE * DonjonBuilder.RAYON_SORTIE) || Boss.dansSortieArene(j)) {
                sortir(j);
                continue;
            }
            if (server.getTickCount() % 40 == 0) {
                verifierMode(j);
            }
        }
    }

    /** Le flash blanc : ce joueur arrive dans le donjon, devant le portail de sortie, avec les autres en arc de cercle. */
    private static void arriver(ServerPlayer j, int i, int groupe) {
        j.removeEffect(MobEffects.DAMAGE_RESISTANCE);
        j.removeEffect(MobEffects.INVISIBILITY);
        j.removeEffect(MobEffects.SATURATION);
        ServerLevel donjon = niveau(j.server);
        if (donjon == null) {
            return;
        }
        PacketDistributor.sendToPlayer(j, new Arrivee(groupe));
        fr.cabane.pnj.Missions.evenement(j, "portail");
        Vec3 p = DonjonBuilder.ARRIVEE.add((i % 5 - 2) * 1.6, 0, (i / 5) * 1.6);
        j.teleportTo(donjon, p.x, p.y, p.z, 0F, 0F);
        j.fallDistance = 0;
        j.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 4, false, false));
        verifierMode(j);
        donjon.playSound(null, p.x, p.y, p.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 0.5F);
    }

    /** Envoie ce joueur directement dans le donjon, sans la scène (l'hôte, pour rejoindre les autres). */
    public static void rejoindre(ServerPlayer j) {
        ServerLevel donjon = niveau(j.server);
        if (donjon == null || dedans(j)) {
            return;
        }
        DonjonData data = DonjonData.get(j.server);
        if (data.versionConstruite() != DonjonBuilder.VERSION) {
            DonjonBuilder.construire(donjon);
            data.setVersionConstruite(DonjonBuilder.VERSION);
        }
        data.setRetour(j.getUUID(), new DonjonData.Retour(j.serverLevel().dimension().location(), j.getX(), j.getY(), j.getZ(), j.getYRot(), j.getXRot()));
        arriver(j, 0, 1);
    }

    /** Ramène le joueur exactement là d'où il est parti (ou, à défaut, près de Jin-woo, ou à son lit). */
    public static void sortir(ServerPlayer joueur) {
        if (!dedans(joueur)) {
            return;
        }
        MinecraftServer server = joueur.server;
        DonjonData data = DonjonData.get(server);
        DonjonData.Retour retour = data.retour(joueur.getUUID());
        ServerLevel cible = retour == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, retour.dimension()));
        ServerLevel ici = joueur.serverLevel();
        ici.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 0.7F);
        BlockPos c = HubData.get(server).centre();
        if (cible != null && cible != ici) {
            joueur.teleportTo(cible, retour.x(), retour.y(), retour.z(), retour.yaw(), retour.pitch());
        } else if (c != null) {
            joueur.teleportTo(server.overworld(), c.getX() + ATTENTE.x, c.getY() + ATTENTE.y, c.getZ() + ATTENTE.z - 4, 180F, 0F);
        } else {
            ServerLevel monde = server.overworld();
            BlockPos p = monde.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, monde.getSharedSpawnPos());
            joueur.teleportTo(monde, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, joueur.getYRot(), 0F);
        }
        joueur.fallDistance = 0;
        data.setRetour(joueur.getUUID(), null);
        verifierMode(joueur);
        joueur.displayClientMessage(Component.literal("Vous êtes sorti du donjon.").withStyle(ChatFormatting.AQUA), true);
    }

    /** Dans le donjon, un joueur en survie passe en aventure (rien ne s'y casse) ; dehors, il retrouve son mode. */
    static void verifierMode(ServerPlayer joueur) {
        DonjonData data = DonjonData.get(joueur.server);
        GameType avant = data.mode(joueur.getUUID());
        if (dedans(joueur)) {
            if (joueur.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                data.setMode(joueur.getUUID(), GameType.SURVIVAL);
                joueur.setGameMode(GameType.ADVENTURE);
            }
        } else if (avant != null) {
            data.setMode(joueur.getUUID(), null);
            if (joueur.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
                joueur.setGameMode(avant);
            }
        }
    }

    @SubscribeEvent
    public static void changement(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    /** Parti en pleine scène : à son retour, il est dans le donjon avec les autres (ou dehors, son mode rendu). */
    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer j) {
            if (!actif && ACTEURS.remove(j.getUUID())) {
                arriver(j, 0, 1);
                return;
            }
            verifierMode(j);
        }
    }

    @SubscribeEvent
    public static void reapparition(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    /** Au démarrage : le donjon est (re)construit tout de suite s'il a changé, pour que personne n'attende à l'entrée. */
    @SubscribeEvent
    public static void demarrage(ServerStartedEvent event) {
        actif = false;
        ACTEURS.clear();
        ServerLevel donjon = niveau(event.getServer());
        DonjonData data = DonjonData.get(event.getServer());
        if (donjon != null && data.versionConstruite() != DonjonBuilder.VERSION) {
            DonjonBuilder.construire(donjon);
            data.setVersionConstruite(DonjonBuilder.VERSION);
        } else if (donjon != null) {
            // personne n'est dedans au démarrage : les énigmes et les portes reprennent leur état de départ
            Enigmes.reinitialiser(donjon);
        }
    }

    /** Aucune créature n'apparaît d'elle-même dans le donjon : les monstres, c'est le donjon qui les pose. */
    @SubscribeEvent
    public static void apparition(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getLevel().getLevel().dimension() == DIMENSION
                && (event.getSpawnType() == MobSpawnType.NATURAL || event.getSpawnType() == MobSpawnType.CHUNK_GENERATION)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    // ---------------------------------------------------------------- les commandes

    /**
     * /donjon : l'hôte et ceux qui sont à côté de lui entrent (avec la scène) ; /donjon &lt;joueurs&gt; : ceux-là ;
     * /donjon retour : sortir ; /donjon rejoindre : aller dans le donjon sans la scène ; /donjon fin : tout le monde
     * dehors ; /donjon reconstruire ; /donjon test &lt;seconde&gt; [faux] : la scène pour soi seul, à partir de telle
     * seconde, avec des joueurs inventés en plus.
     */
    /**
     * L'hôte lance l'événement : le portail de rang S « apparaît » ; tout le monde est prévenu, Jin-woo emmène désormais
     * les groupes et donne sa mission. {@code false} : l'événement s'arrête (Jin-woo redevient silencieux).
     */
    public static void evenement(MinecraftServer server, boolean lance) {
        DonjonData.get(server).setEvenement(lance);
        if (!lance) {
            return;
        }
        Boss.Annonce a = new Boss.Annonce("ALERTE", List.of("Un portail de rang S vient de s'ouvrir à Beauvais.",
                "Sung Jin-woo vous attend sur l'avenue du sud."), "", 0);
        for (ServerPlayer j : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(j, a);
            j.level().playSound(null, j.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.MASTER, 0.6F, 0.5F);
            j.sendSystemMessage(Component.literal("[ALERTE] Un portail de rang S vient de s'ouvrir. Sung Jin-woo vous attend sur l'avenue du sud.")
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        // /cabane evenement jinwoo : le portail s'ouvre (et /cabane evenement jinwoo fin : il se referme)
        event.getDispatcher().register(Commands.literal("cabane").then(Commands.literal("evenement").requires(Donjon::hote)
                .then(Commands.literal("jinwoo")
                        .executes(ctx -> {
                            evenement(ctx.getSource().getServer(), true);
                            ctx.getSource().sendSuccess(() -> Component.literal("Le portail de rang S est ouvert : Jin-woo attend les chasseurs.")
                                    .withStyle(ChatFormatting.AQUA), true);
                            return 1;
                        })
                        .then(Commands.literal("fin").executes(ctx -> {
                            evenement(ctx.getSource().getServer(), false);
                            ctx.getSource().sendSuccess(() -> Component.literal("Le portail de rang S est refermé."), true);
                            return 1;
                        })))));
        event.getDispatcher().register(Commands.literal("donjon")
                .then(Commands.literal("retour").executes(ctx -> {
                    ServerPlayer j = ctx.getSource().getPlayerOrException();
                    if (!dedans(j)) {
                        ctx.getSource().sendFailure(Component.literal("Tu n'es pas dans le donjon."));
                        return 0;
                    }
                    sortir(j);
                    return 1;
                }))
                .then(Commands.literal("rejoindre").requires(Donjon::hote).executes(ctx -> {
                    rejoindre(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("fin").requires(Donjon::hote).executes(ctx -> {
                    ServerLevel donjon = niveau(ctx.getSource().getServer());
                    int n = 0;
                    if (donjon != null) {
                        for (ServerPlayer j : List.copyOf(donjon.players())) {
                            sortir(j);
                            n++;
                        }
                    }
                    int total = n;
                    ctx.getSource().sendSuccess(() -> Component.literal(total + " joueur(s) sorti(s) du donjon."), true);
                    return n;
                }))
                .then(Commands.literal("boss").requires(Donjon::hote).executes(ctx -> {
                    ServerPlayer j = ctx.getSource().getPlayerOrException();
                    rejoindre(j);
                    Boss.nouveauGroupe(ctx.getSource().getServer());
                    ServerLevel donjon = niveau(ctx.getSource().getServer());
                    // un combat tout neuf : l'ancien boss et les sbires s'en vont
                    if (donjon != null) {
                        Boss.retirer(donjon);
                    }
                    if (donjon != null) {
                        Vec3 p = DonjonBuilder.ARENE.add(0, 0, -20);
                        j.teleportTo(donjon, p.x, p.y, p.z, 0F, 0F);
                    }
                    return 1;
                }))
                // pour l'hôte : les énigmes comme résolues et la porte des sceaux ouverte
                .then(Commands.literal("ouvrir").requires(Donjon::hote).executes(ctx -> {
                    ServerLevel donjon = niveau(ctx.getSource().getServer());
                    if (donjon != null) {
                        Enigmes.toutOuvrir(donjon);
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("Énigmes résolues, porte des sceaux ouverte."), true);
                    return 1;
                }))
                .then(Commands.literal("reconstruire").requires(Donjon::hote).executes(ctx -> {
                    ServerLevel donjon = niveau(ctx.getSource().getServer());
                    if (donjon == null) {
                        ctx.getSource().sendFailure(Component.literal("La dimension du donjon est introuvable."));
                        return 0;
                    }
                    Boss.retirer(donjon);
                    DonjonBuilder.construire(donjon);
                    DonjonData.get(ctx.getSource().getServer()).setVersionConstruite(DonjonBuilder.VERSION);
                    ctx.getSource().sendSuccess(() -> Component.literal("Le donjon est reconstruit."), true);
                    return 1;
                }))
                .then(Commands.literal("test").requires(Donjon::hote)
                        .then(Commands.argument("depart", FloatArgumentType.floatArg(0, (float) DUREE - 1))
                                .executes(ctx -> lancerDepuis(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException()),
                                        FloatArgumentType.getFloat(ctx, "depart"), 0))
                                .then(Commands.argument("faux", IntegerArgumentType.integer(0, MAX))
                                        .executes(ctx -> lancerDepuis(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException()),
                                                FloatArgumentType.getFloat(ctx, "depart"), IntegerArgumentType.getInteger(ctx, "faux"))))))
                .then(Commands.argument("joueurs", EntityArgument.players()).requires(Donjon::hote).executes(ctx -> {
                    Collection<ServerPlayer> joueurs = EntityArgument.getPlayers(ctx, "joueurs");
                    return lancerDepuis(ctx.getSource(), List.copyOf(joueurs), 0, 0);
                }))
                // seul (sans rien derrière) : réservé à l'hôte, les autres passent par Jin-woo
                .executes(ctx -> {
                    if (!hote(ctx.getSource())) {
                        ctx.getSource().sendFailure(Component.literal("Pour entrer, parle à Sung Jin-woo, sur l'avenue du sud."));
                        return 0;
                    }
                    return lancerDepuis(ctx.getSource(), autour(ctx.getSource().getPlayerOrException()), 0, 0);
                }));
    }

    private static int lancerDepuis(CommandSourceStack source, List<ServerPlayer> joueurs, float depart, int faux) {
        String refus = lancer(source.getServer(), joueurs, depart, faux);
        if (!refus.isEmpty()) {
            source.sendFailure(Component.literal(refus));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Le portail de rang S s'ouvre…").withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    private Donjon() {
    }
}
