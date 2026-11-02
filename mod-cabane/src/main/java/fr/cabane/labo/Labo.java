package fr.cabane.labo;

import com.mojang.brigadier.CommandDispatcher;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Le Laboratoire : une dimension vide (cabane:laboratoire) qui servira à d'autres choses, et, au milieu du vide,
 * un immense labo glauque construit par le mod à la première visite. On y entre par le Terminal du Laboratoire
 * (clic droit) ou par /laboratoire ; on ne peut pas en sortir à pied (on y est en mode aventure : rien à casser,
 * rien à poser), seulement par le bouton rouge, qui ramène pile là où l'on était, regard compris.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Labo {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("laboratoire"));
    /** Où l'on atterrit : la zone d'arrivée, face à la table d'expériences. */
    static final double ARRIVEE_X = 0.5;
    static final double ARRIVEE_Z = 17.5;
    static final float ARRIVEE_YAW = 180F;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cabane.MODID);
    /** Le terminal qui emmène au labo : l'hôte le pose où il veut. Incassable en survie. */
    public static final DeferredBlock<LaboBlocs.Terminal> TERMINAL = BLOCKS.registerBlock("terminal_labo", LaboBlocs.Terminal::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(-1F, 3_600_000F).sound(SoundType.NETHERITE_BLOCK)
                    .lightLevel(s -> 9).noLootTable());
    /** Le bouton rouge qui ramène dans le vrai monde. */
    public static final DeferredBlock<LaboBlocs.BoutonRetour> BOUTON_RETOUR = BLOCKS.registerBlock("bouton_retour", LaboBlocs.BoutonRetour::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1F, 3_600_000F).sound(SoundType.METAL).noOcclusion()
                    .lightLevel(s -> 7).noLootTable());
    /** La table où l'on s'allonge pour une expérience (deux blocs, comme un lit). */
    public static final DeferredBlock<LaboBlocs.TableExperiences> TABLE = BLOCKS.registerBlock("table_experiences", LaboBlocs.TableExperiences::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1F, 3_600_000F).sound(SoundType.METAL).noOcclusion().noLootTable());
    /** L'autel de pierre de la salle des rituels : la même chose que la table, mais c'est un rituel qui s'y joue. */
    public static final DeferredBlock<LaboBlocs.TableExperiences> AUTEL = BLOCKS.registerBlock("autel_rituel", LaboBlocs.TableExperiences::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(-1F, 3_600_000F).sound(SoundType.DEEPSLATE).noOcclusion().noLootTable());

    /** Le grand sceau rouge de la salle des rituels : un objet caché (pas dans l'onglet), affiché en grand au sol. */
    private static final DeferredRegister.Items OBJETS = DeferredRegister.createItems(Cabane.MODID);
    public static final DeferredItem<Item> SCEAU = OBJETS.registerSimpleItem("sceau_rituel");

    public static void register(IEventBus modBus) {
        for (DeferredBlock<?> bloc : List.of(TERMINAL, BOUTON_RETOUR, TABLE, AUTEL)) {
            CabaneItems.ITEMS.registerSimpleBlockItem(bloc);
        }
        BLOCKS.register(modBus);
        OBJETS.register(modBus);
    }

    /** Une nouvelle construction du labo (les entités des constructions précédentes seront refusées). */
    static void construire(ServerLevel labo) {
        LaboData data = LaboData.get(labo.getServer());
        LaboBuilder.construire(labo, data.nouvelleConstruction());
        data.setVersionConstruite(LaboBuilder.VERSION);
    }

    public static ServerLevel niveau(MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static boolean dedans(ServerPlayer joueur) {
        return joueur.level().dimension() == DIMENSION;
    }

    // ------------------------------------------------------------------ entrer, sortir

    /** Emmène le joueur au labo (en le construisant d'abord s'il le faut), en retenant d'où il part. */
    public static void entrer(ServerPlayer joueur) {
        MinecraftServer server = joueur.server;
        ServerLevel labo = niveau(server);
        if (labo == null) {
            joueur.sendSystemMessage(Component.literal("Le Laboratoire n'existe pas dans ce monde.").withStyle(ChatFormatting.RED));
            return;
        }
        LaboData data = LaboData.get(server);
        if (data.versionConstruite() != LaboBuilder.VERSION) {
            joueur.displayClientMessage(Component.literal("Le Laboratoire se prépare…").withStyle(ChatFormatting.DARK_GRAY), true);
            construire(labo);
        }
        if (dedans(joueur)) {
            joueur.displayClientMessage(Component.literal("Tu y es déjà. Le bouton rouge, près de l'arrivée, te ramène.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (joueur.isSleeping()) {
            joueur.stopSleepInBed(true, true);
        }
        ServerLevel depart = joueur.serverLevel();
        data.setRetour(joueur.getUUID(), new LaboData.Retour(depart.dimension().location(), joueur.getX(), joueur.getY(), joueur.getZ(),
                joueur.getYRot(), joueur.getXRot()));
        depart.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 0.5F);
        depart.sendParticles(ParticleTypes.REVERSE_PORTAL, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 60, 0.4, 0.9, 0.4, 0.15);

        joueur.teleportTo(labo, ARRIVEE_X, LaboBuilder.SOL + 1, ARRIVEE_Z, ARRIVEE_YAW, 0F);
        verifierMode(joueur);
        joueur.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 70, 0, false, false));
        joueur.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, false, false));
        labo.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.AMBIENT, 1F, 0.5F);
        labo.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, 1.4F, 0.8F);
        titre(joueur, Component.literal("LABORATOIRE").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                Component.literal("Sujet n°" + data.sujet(joueur.getUUID()) + " — bienvenue.").withStyle(ChatFormatting.GRAY));
        joueur.sendSystemMessage(Component.literal("Pour repartir : le bouton rouge, près de la zone d'arrivée. Il te ramène pile là où tu étais.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

    /** Ramène le joueur exactement là d'où il est venu (ou, à défaut, à son point de réapparition). */
    public static void sortir(ServerPlayer joueur) {
        if (!dedans(joueur)) {
            joueur.displayClientMessage(Component.literal("Tu n'es pas au Laboratoire.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (joueur.isSleeping()) {
            joueur.stopSleepInBed(true, true);
        }
        MinecraftServer server = joueur.server;
        LaboData data = LaboData.get(server);
        LaboData.Retour retour = data.retour(joueur.getUUID());
        ServerLevel cible = null;
        if (retour != null) {
            cible = server.getLevel(ResourceKey.create(Registries.DIMENSION, retour.dimension()));
        }
        ServerLevel ici = joueur.serverLevel();
        ici.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1F, 1.4F);
        if (cible != null && cible != ici) {
            joueur.teleportTo(cible, retour.x(), retour.y(), retour.z(), retour.yaw(), retour.pitch());
        } else {
            // pas de point de retour connu : le point de réapparition du joueur, ou le spawn du monde
            ServerLevel monde = server.getLevel(joueur.getRespawnDimension());
            BlockPos p = joueur.getRespawnPosition();
            if (monde == null || p == null || monde == ici) {
                monde = server.overworld();
                BlockPos s = monde.getSharedSpawnPos();
                p = monde.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s);
            } else {
                p = p.above();
            }
            joueur.teleportTo(monde, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, joueur.getYRot(), 0F);
        }
        data.setRetour(joueur.getUUID(), null);
        verifierMode(joueur);
        joueur.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 25, 0, false, false));
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 1.2F);
        joueur.displayClientMessage(Component.literal("Tu es de retour… Tu n'as rien oublié là-bas ?").withStyle(ChatFormatting.GRAY), true);
    }

    static void titre(ServerPlayer joueur, Component titre, Component sousTitre) {
        joueur.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 25));
        joueur.connection.send(new ClientboundSetSubtitleTextPacket(sousTitre));
        joueur.connection.send(new ClientboundSetTitleTextPacket(titre));
    }

    /**
     * Au labo, un joueur en survie passe en aventure (on n'y casse rien, on n'y pose rien : impossible d'en sortir
     * en creusant) ; dehors, il retrouve son mode. Les joueurs en créatif ou spectateur ne sont pas touchés.
     */
    static void verifierMode(ServerPlayer joueur) {
        LaboData data = LaboData.get(joueur.server);
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

    // ------------------------------------------------------------------ commandes

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("laboratoire")
                .executes(ctx -> {
                    entrer(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("retour").executes(ctx -> {
                    sortir(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                // l'hôte peut y envoyer quelqu'un (ou plusieurs) sans lui demander son avis
                .then(Commands.literal("envoyer").requires(Labo::hote)
                        .then(Commands.argument("joueurs", EntityArgument.players()).executes(ctx -> {
                            Collection<ServerPlayer> joueurs = EntityArgument.getPlayers(ctx, "joueurs");
                            joueurs.forEach(Labo::entrer);
                            ctx.getSource().sendSuccess(() -> Component.literal(joueurs.size() + " sujet(s) envoyé(s) au Laboratoire."), true);
                            return joueurs.size();
                        })))
                // pour l'hôte (et les tests) : s'allonger sur la grande table, comme d'un clic droit
                .then(Commands.literal("experience").requires(Labo::hote)
                        .executes(ctx -> allongerPourTest(ctx.getSource(), LaboBuilder.TABLE)))
                // pareil, sur l'autel de la salle des rituels
                .then(Commands.literal("rituel").requires(Labo::hote)
                        .executes(ctx -> allongerPourTest(ctx.getSource(), new BlockPos(LaboBuilder.RX + 1, 3, LaboBuilder.RZ))))
                .then(Commands.literal("reconstruire").requires(Labo::hote).executes(ctx -> {
                    ServerLevel labo = niveau(ctx.getSource().getServer());
                    if (labo == null) {
                        ctx.getSource().sendFailure(Component.literal("La dimension du Laboratoire est introuvable."));
                        return 0;
                    }
                    construire(labo);
                    ctx.getSource().sendSuccess(() -> Component.literal("Le Laboratoire est reconstruit."), true);
                    return 1;
                })));
    }

    /** {@code tete} : la tête de la table ou de l'autel, en coordonnées du labo. */
    private static int allongerPourTest(CommandSourceStack source, BlockPos tete) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer joueur = source.getPlayerOrException();
        if (!dedans(joueur)) {
            source.sendFailure(Component.literal("Il faut être au Laboratoire."));
            return 0;
        }
        Experiences.allonger(joueur, joueur.serverLevel(), LaboBuilder.abs(tete.getX(), tete.getY(), tete.getZ()), true);
        return 1;
    }

    /** L'hôte : un admin du serveur, ou le joueur qui a ouvert le monde en solo. */
    private static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
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
        ServerLevel labo = niveau(server);
        if (labo == null || labo.players().isEmpty()) {
            Experiences.tickHors(server);
            return;
        }
        for (ServerPlayer joueur : List.copyOf(labo.players())) {
            // tombé dans le vide (par une fenêtre cassée, un fruit de chorus…) : on le remet à l'arrivée
            if (joueur.getY() < LaboBuilder.SOL - 24) {
                joueur.teleportTo(labo, ARRIVEE_X, LaboBuilder.SOL + 1, ARRIVEE_Z, ARRIVEE_YAW, 0F);
                joueur.fallDistance = 0;
                joueur.displayClientMessage(Component.literal("Le vide n'a pas voulu de toi.").withStyle(ChatFormatting.DARK_PURPLE), true);
            }
            if (server.getTickCount() % 40 == 0) {
                verifierMode(joueur);
            }
        }
        LaboVie.tick(labo);
        Experiences.tick(labo);
    }

    /** Les sujets du labo ne disparaissent jamais, même en paisible (sinon les monstres des cuves s'évaporent). */
    @SubscribeEvent
    public static void onDisparition(MobDespawnEvent event) {
        if (event.getEntity().getTags().contains(LaboBuilder.TAG)) {
            event.setResult(MobDespawnEvent.Result.DENY);
        }
    }

    /** Aucune créature n'apparaît d'elle-même au labo (des mods en ajoutent à tous les biomes). */
    @SubscribeEvent
    public static void onApparition(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getLevel().getLevel().dimension() == DIMENSION
                && (event.getSpawnType() == MobSpawnType.NATURAL || event.getSpawnType() == MobSpawnType.CHUNK_GENERATION)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /**
     * Au labo, le seul Warden est le Sujet Zéro, figé dans sa cage : aucun autre ne peut y apparaître (hurleur, oeuf,
     * commande…). Dans les autres dimensions, les Wardens restent normaux.
     */
    @SubscribeEvent
    public static void onArrivee(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.getLevel().dimension() != DIMENSION) {
            return;
        }
        var tags = event.getEntity().getTags();
        if (event.getEntity() instanceof Warden && !tags.contains(LaboBuilder.TAG)) {
            event.setCanceled(true);
        } else if (tags.contains(LaboBuilder.TAG) && event.getLevel().getServer() != null
                && !tags.contains(LaboBuilder.tagConstruction(LaboData.get(event.getLevel().getServer()).construction()))) {
            // un reste d'une construction précédente (son chunk n'était pas chargé quand on a tout effacé)
            event.setCanceled(true);
        }
    }

    /** Les corps étendus dans le labo (étiquetés « labo.corps ») restent couchés, même sans lit sous eux. */
    @SubscribeEvent
    public static void onSommeil(CanContinueSleepingEvent event) {
        if (event.getEntity().getTags().contains(LaboBuilder.CORPS)) {
            event.setContinueSleeping(true);
        }
    }

    private Labo() {
    }
}
