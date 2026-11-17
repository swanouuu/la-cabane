package fr.cabane.parc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.SharedSuggestionProvider;
import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import java.util.Collection;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Le parc d'attractions : une dimension à part (cabane:parc) où il fait toujours grand soleil, avec une musique de
 * fête foraine. Le mod y construit la place, l'avenue, l'entrée et les manèges ({@link ParcBuilder}) ; les trains des
 * manèges roulent tout seuls ({@link ParcVie}). /parc pour y aller, /parc &lt;joueurs&gt; pour l'hôte qui y envoie du
 * monde, et le bouton rouge de l'entrée (ou /parc retour) ramène pile là d'où l'on venait. On n'y casse rien.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Parc {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("parc"));

    private static final DeferredRegister<EntityType<?>> ENTITES = DeferredRegister.create(Registries.ENTITY_TYPE, Cabane.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<WagonParc>> WAGON = ENTITES.register("wagon_parc",
            () -> EntityType.Builder.<WagonParc>of(WagonParc::new, MobCategory.MISC).sized(2.0F, 1.4F).clientTrackingRange(16)
                    .updateInterval(1).build("wagon_parc"));

    public static final DeferredHolder<EntityType<?>, EntityType<NacelleParc>> NACELLE = ENTITES.register("nacelle_parc",
            () -> EntityType.Builder.<NacelleParc>of(NacelleParc::new, MobCategory.MISC).sized(1.5F, 1.5F).clientTrackingRange(16)
                    .updateInterval(2).build("nacelle_parc"));
    public static final DeferredHolder<EntityType<?>, EntityType<AutoParc>> AUTO = ENTITES.register("auto_parc",
            () -> EntityType.Builder.<AutoParc>of(AutoParc::new, MobCategory.MISC).sized(2.6F, 1.2F).clientTrackingRange(10)
                    .updateInterval(2).build("auto_parc"));

    /** La musique de manège (le biome du parc la joue en boucle) : elle doit exister pour que le monde se charge. */
    private static final DeferredRegister<SoundEvent> SONS = DeferredRegister.create(Registries.SOUND_EVENT, Cabane.MODID);
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIQUE = SONS.register("musique.parc",
            () -> SoundEvent.createVariableRangeEvent(Cabane.id("musique.parc")));

    public static final DeferredHolder<EntityType<?>, EntityType<VisiteurParc>> VISITEUR = ENTITES.register("visiteur_parc",
            () -> EntityType.Builder.of(VisiteurParc::new, MobCategory.MISC).sized(0.6F, 1.8F).clientTrackingRange(10).build("visiteur_parc"));

    private static final DeferredRegister.Blocks BLOCS = DeferredRegister.createBlocks(Cabane.MODID);
    public static final DeferredBlock<BoutonParc> BOUTON = BLOCS.registerBlock("bouton_parc", BoutonParc::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1F, 3_600_000F).sound(SoundType.METAL).noOcclusion()
                    .lightLevel(s -> 7).noLootTable());

    public static void register(IEventBus modBus) {
        CabaneItems.ITEMS.registerSimpleBlockItem(BOUTON);
        SONS.register(modBus);
        ENTITES.register(modBus);
        BLOCS.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent e) -> e.put(VISITEUR.get(), VisiteurParc.attributs().build()));
        modBus.addListener(MaisonHantee::paquets);
    }

    // ------------------------------------------------------------------ entrer, sortir

    public static ServerLevel niveau(MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static boolean dedans(ServerPlayer joueur) {
        return joueur.level().dimension() == DIMENSION;
    }

    static void construire(ServerLevel parc) {
        ParcVie.oublier();
        ParcData data = ParcData.get(parc.getServer());
        ParcBuilder.construire(parc, data.nouvelleConstruction());
        data.setVersionConstruite(ParcBuilder.VERSION);
    }

    /** Emmène le joueur au parc (en le construisant d'abord s'il le faut), en retenant d'où il part. */
    public static void entrer(ServerPlayer joueur) {
        MinecraftServer server = joueur.server;
        ServerLevel parc = niveau(server);
        if (parc == null) {
            joueur.sendSystemMessage(Component.literal("Le parc n'existe pas dans ce monde.").withStyle(ChatFormatting.RED));
            return;
        }
        ParcData data = ParcData.get(server);
        if (data.versionConstruite() != ParcBuilder.VERSION) {
            joueur.displayClientMessage(Component.literal("Le parc se prépare…").withStyle(ChatFormatting.GOLD), true);
            construire(parc);
        }
        var a = ParcBuilder.ARRIVEE;
        if (dedans(joueur)) {
            joueur.teleportTo(parc, a.x, a.y, a.z, 180F, 0F);
            return;
        }
        if (joueur.isSleeping()) {
            joueur.stopSleepInBed(true, true);
        }
        ServerLevel depart = joueur.serverLevel();
        data.setRetour(joueur.getUUID(), new ParcData.Retour(depart.dimension().location(), joueur.getX(), joueur.getY(), joueur.getZ(),
                joueur.getYRot(), joueur.getXRot()));
        depart.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 1.2F);
        depart.sendParticles(ParticleTypes.HAPPY_VILLAGER, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 30, 0.4, 0.8, 0.4, 0.02);

        joueur.teleportTo(parc, a.x, a.y, a.z, 180F, 0F);
        verifierMode(joueur);
        joueur.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 15, 0, false, false));
        parc.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.4F);
        joueur.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 25));
        joueur.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Ici il fait toujours beau. Amuse-toi bien !")
                .withStyle(ChatFormatting.YELLOW)));
        joueur.connection.send(new ClientboundSetTitleTextPacket(Component.literal("PARC DE LA CABANE").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)));
        joueur.sendSystemMessage(Component.literal("Pour repartir : le bouton rouge, juste à côté de l'entrée (ou /parc retour). Il te ramène pile là où tu étais.")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    /** Ramène le joueur exactement là d'où il est venu (ou, à défaut, à son point de réapparition). */
    public static void sortir(ServerPlayer joueur) {
        if (!dedans(joueur)) {
            joueur.displayClientMessage(Component.literal("Tu n'es pas au parc.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (joueur.isPassenger()) {
            joueur.displayClientMessage(Component.literal("Attends la fin du tour !").withStyle(ChatFormatting.GOLD), true);
            return;
        }
        MinecraftServer server = joueur.server;
        ParcData data = ParcData.get(server);
        ParcData.Retour retour = data.retour(joueur.getUUID());
        ServerLevel cible = retour == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, retour.dimension()));
        ServerLevel ici = joueur.serverLevel();
        ici.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 1.2F);
        if (cible != null && cible != ici) {
            joueur.teleportTo(cible, retour.x(), retour.y(), retour.z(), retour.yaw(), retour.pitch());
        } else {
            ServerLevel monde = server.getLevel(joueur.getRespawnDimension());
            BlockPos p = joueur.getRespawnPosition();
            if (monde == null || p == null || monde == ici) {
                monde = server.overworld();
                p = monde.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, monde.getSharedSpawnPos());
            } else {
                p = p.above();
            }
            joueur.teleportTo(monde, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, joueur.getYRot(), 0F);
        }
        data.setRetour(joueur.getUUID(), null);
        verifierMode(joueur);
        joueur.displayClientMessage(Component.literal("À bientôt au parc !").withStyle(ChatFormatting.LIGHT_PURPLE), true);
    }

    /** Au parc, un joueur en survie passe en aventure (on n'y casse rien) ; dehors, il retrouve son mode. */
    static void verifierMode(ServerPlayer joueur) {
        ParcData data = ParcData.get(joueur.server);
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

    /** L'hôte : un admin du serveur, ou le joueur qui a ouvert le monde en solo. */
    static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    // ------------------------------------------------------------------ commandes

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("parc")
                .executes(ctx -> {
                    entrer(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("retour").executes(ctx -> {
                    sortir(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                // pour l'hôte (et les tests) : s'asseoir au premier rang d'un manège, qui part dans 3 secondes
                .then(Commands.literal("monter").requires(Parc::hote)
                        .executes(ctx -> monter(ctx.getSource(), Maneges.DRAGON))
                        .then(Commands.argument("manege", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(List.of("dragon", "mine", "cosmos", "splash", "tour", "roue", "carrousel",
                                        "tasses", "bateau", "toupie", "autos"), b))
                                .executes(ctx -> {
                                    String nom = StringArgumentType.getString(ctx, "manege");
                                    byte id = Maneges.parNom(nom);
                                    if (id < 0) {
                                        ServerPlayer j = ctx.getSource().getPlayerOrException();
                                        if (!dedans(j)) {
                                            entrer(j);
                                        }
                                        if (!TournantsVie.monter(j.serverLevel(), j, nom)) {
                                            ctx.getSource().sendFailure(Component.literal("Manèges : dragon, mine, cosmos, splash, tour, roue, carrousel, tasses, bateau, toupie, autos."));
                                            return 0;
                                        }
                                        return 1;
                                    }
                                    return monter(ctx.getSource(), id);
                                })))
                // l'événement : le portail gaulois s'ouvre au-dessus de la place (et /parc event fin le referme)
                .then(Commands.literal("event").requires(Parc::hote)
                        .executes(ctx -> evenement(ctx.getSource(), true))
                        .then(Commands.literal("fin").executes(ctx -> evenement(ctx.getSource(), false))))
                .then(Commands.literal("reconstruire").requires(Parc::hote).executes(ctx -> {
                    ServerLevel parc = niveau(ctx.getSource().getServer());
                    if (parc == null) {
                        ctx.getSource().sendFailure(Component.literal("La dimension du parc est introuvable."));
                        return 0;
                    }
                    construire(parc);
                    ctx.getSource().sendSuccess(() -> Component.literal("Le parc est reconstruit."), true);
                    return 1;
                }))
                .then(Commands.argument("joueurs", EntityArgument.players()).requires(Parc::hote).executes(ctx -> {
                    Collection<ServerPlayer> joueurs = EntityArgument.getPlayers(ctx, "joueurs");
                    joueurs.forEach(Parc::entrer);
                    ctx.getSource().sendSuccess(() -> Component.literal(joueurs.size() + " joueur(s) envoyé(s) au parc."), true);
                    return joueurs.size();
                })));
    }

    private static int evenement(CommandSourceStack source, boolean ouvrir) {
        ServerLevel parc = niveau(source.getServer());
        if (parc == null) {
            source.sendFailure(Component.literal("La dimension du parc est introuvable."));
            return 0;
        }
        ParcData data = ParcData.get(source.getServer());
        if (data.versionConstruite() != ParcBuilder.VERSION) {
            construire(parc);
        }
        PortailGaulois.ouvrir(parc, ouvrir);
        source.sendSuccess(() -> Component.literal(ouvrir ? "Le portail gaulois est ouvert au-dessus de la place du parc." : "Le portail gaulois est refermé."), true);
        return 1;
    }

    private static int monter(CommandSourceStack source, byte id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer j = source.getPlayerOrException();
        if (!dedans(j)) {
            entrer(j);
        }
        if (!ParcVie.monter(j.serverLevel(), j, id)) {
            source.sendFailure(Component.literal("Impossible de monter dans ce manège."));
            return 0;
        }
        return 1;
    }

    // ------------------------------------------------------------------ événements

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel parc = niveau(server);
        if (parc == null) {
            return;
        }
        ParcVie.tick(parc);
        if (parc.players().isEmpty()) {
            return;
        }
        var a = ParcBuilder.ARRIVEE;
        for (ServerPlayer joueur : List.copyOf(parc.players())) {
            if (joueur.getY() < Maneges.SOL - 40) {
                joueur.teleportTo(parc, a.x, a.y, a.z, 180F, 0F);
                joueur.fallDistance = 0;
            }
            if (server.getTickCount() % 40 == 0) {
                verifierMode(joueur);
            }
        }
        // les jets de la fontaine
        if (server.getTickCount() % 4 == 0) {
            ParcBuilder.fontaine(parc);
        }
        MaisonHantee.tick(parc);
        PortailGaulois.tick(parc);
    }

    /** Dans un manège, on ne descend pas en route : seulement en gare. */
    @SubscribeEvent
    public static void onDescente(EntityMountEvent event) {
        boolean bloque = event.getEntityBeingMounted() instanceof WagonParc w && !ParcVie.descenteLibre(w)
                || event.getEntityBeingMounted() instanceof NacelleParc n && !n.libre();
        if (event.isDismounting() && bloque
                && event.getEntityMounting().isAlive() && !event.getEntityMounting().isRemoved() && event.getEntityMounting().isShiftKeyDown()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onArret(ServerStoppingEvent event) {
        ParcVie.arret();
        MaisonHantee.oublier();
    }

    /** Les tableaux de la maison hantée et les décors accrochés ne se décrochent pas (sauf en créatif). */
    @SubscribeEvent
    public static void onFrappe(AttackEntityEvent event) {
        if (event.getTarget() instanceof HangingEntity && event.getEntity().level().dimension() == DIMENSION && !event.getEntity().isCreative()) {
            event.setCanceled(true);
        }
    }

    /** Les visiteurs ne disparaissent jamais, même en paisible. */
    @SubscribeEvent
    public static void onDisparition(MobDespawnEvent event) {
        if (event.getEntity().getTags().contains(ParcBuilder.TAG)) {
            event.setResult(MobDespawnEvent.Result.DENY);
        }
    }

    /** Les restes d'une construction précédente (leur tronçon n'était pas chargé quand on a tout effacé) sont refusés. */
    @SubscribeEvent
    public static void onArrivee(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.getLevel().dimension() != DIMENSION || event.getLevel().getServer() == null
                || event.getEntity() instanceof WagonParc || event.getEntity() instanceof NacelleParc || event.getEntity() instanceof AutoParc) {
            return;
        }
        var tags = event.getEntity().getTags();
        if (tags.contains(ParcBuilder.TAG) && !tags.contains(ParcBuilder.tagConstruction(ParcData.get(event.getLevel().getServer()).construction()))) {
            event.setCanceled(true);
        }
    }

    /** Aucune créature n'apparaît d'elle-même au parc. */
    @SubscribeEvent
    public static void onApparition(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getLevel().getLevel().dimension() == DIMENSION
                && (event.getSpawnType() == MobSpawnType.NATURAL || event.getSpawnType() == MobSpawnType.CHUNK_GENERATION)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    private Parc() {
    }
}
