package fr.cabane.entrainement;

import com.mojang.brigadier.CommandDispatcher;
import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * La salle d'entraînement : une dimension vide (cabane:entrainement) où le mod construit, à la première visite, la salle
 * de la maquette docs/salle-entrainement : dix salles fermées autour d'une allée, chacune avec son ambiance. On y va par
 * /entrainement ; on en repart par le bouton rouge de l'arrivée (ou /entrainement retour), pile là où l'on était. On y
 * est en aventure (rien à casser), la lave, le feu et les chutes n'y font pas de mal, et les pnj encaissent sans tomber.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Entrainement {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("entrainement"));

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cabane.MODID);
    /** Le bouton rouge qui ramène dans le vrai monde. */
    public static final DeferredBlock<BoutonEntrainement> BOUTON = BLOCKS.registerBlock("bouton_entrainement", BoutonEntrainement::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1F, 3_600_000F).sound(SoundType.METAL).noOcclusion()
                    .lightLevel(s -> 7).noLootTable());

    /** La salle où chaque joueur était au dernier passage (pour annoncer celle où il entre). */
    private static final Map<UUID, Integer> SALLES = new HashMap<>();
    private static long prochainEclair;
    private static long finEclair = -1;

    public static void register(IEventBus modBus) {
        CabaneItems.ITEMS.registerSimpleBlockItem(BOUTON);
        BLOCKS.register(modBus);
    }

    public static ServerLevel niveau(MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static boolean dedans(Player joueur) {
        return joueur.level().dimension() == DIMENSION;
    }

    static void construire(ServerLevel salle) {
        EntrainementData data = EntrainementData.get(salle.getServer());
        EntrainementBuilder.construire(salle, data.nouvelleConstruction());
        data.setVersionConstruite(EntrainementBuilder.VERSION);
    }

    // ------------------------------------------------------------------ entrer, sortir

    /** Emmène le joueur à la salle (construite d'abord s'il le faut), en retenant d'où il part. */
    public static void entrer(ServerPlayer joueur) {
        MinecraftServer server = joueur.server;
        ServerLevel salle = niveau(server);
        if (salle == null) {
            joueur.sendSystemMessage(Component.literal("La salle d'entraînement n'existe pas dans ce monde.").withStyle(ChatFormatting.RED));
            return;
        }
        EntrainementData data = EntrainementData.get(server);
        if (data.versionConstruite() != EntrainementBuilder.VERSION) {
            joueur.displayClientMessage(Component.literal("La salle d'entraînement se prépare…").withStyle(ChatFormatting.GOLD), true);
            construire(salle);
        }
        Vec3 a = EntrainementBuilder.arrivee();
        if (dedans(joueur)) {
            joueur.teleportTo(salle, a.x, a.y, a.z, EntrainementBuilder.ARRIVEE_YAW, 0F);
            return;
        }
        if (joueur.isSleeping()) {
            joueur.stopSleepInBed(true, true);
        }
        ServerLevel depart = joueur.serverLevel();
        data.setRetour(joueur.getUUID(), new EntrainementData.Retour(depart.dimension().location(), joueur.getX(), joueur.getY(), joueur.getZ(),
                joueur.getYRot(), joueur.getXRot()));
        depart.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 1F);
        depart.sendParticles(ParticleTypes.CLOUD, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 30, 0.3, 0.8, 0.3, 0.02);
        joueur.teleportTo(salle, a.x, a.y, a.z, EntrainementBuilder.ARRIVEE_YAW, 0F);
        verifierMode(joueur);
        joueur.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
        salle.playSound(null, a.x, a.y, a.z, SoundEvents.BELL_BLOCK, SoundSource.AMBIENT, 0.8F, 1.4F);
        SALLES.put(joueur.getUUID(), -1);
        titre(joueur, Component.literal("SALLE D'ENTRAÎNEMENT").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("Dix salles, chacune son ambiance. À toi de voir laquelle te va.").withStyle(ChatFormatting.GRAY));
        joueur.sendSystemMessage(Component.literal("Pour repartir : le bouton rouge, à côté de l'arrivée. Il te ramène pile là où tu étais.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

    /** Ramène le joueur exactement là d'où il est venu (ou, à défaut, à son point de réapparition). */
    public static void sortir(ServerPlayer joueur) {
        if (!dedans(joueur)) {
            joueur.displayClientMessage(Component.literal("Tu n'es pas à la salle d'entraînement.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        MinecraftServer server = joueur.server;
        EntrainementData data = EntrainementData.get(server);
        EntrainementData.Retour retour = data.retour(joueur.getUUID());
        ServerLevel cible = retour == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, retour.dimension()));
        ServerLevel ici = joueur.serverLevel();
        ici.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1F, 1.4F);
        joueur.clearFire();
        joueur.setTicksFrozen(0);
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
        SALLES.remove(joueur.getUUID());
        verifierMode(joueur);
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 1.2F);
        joueur.displayClientMessage(Component.literal("Fin de l'entraînement. Bien joué.").withStyle(ChatFormatting.GOLD), true);
    }

    static void titre(ServerPlayer joueur, Component titre, Component sousTitre) {
        joueur.connection.send(new ClientboundSetTitlesAnimationPacket(8, 50, 20));
        joueur.connection.send(new ClientboundSetSubtitleTextPacket(sousTitre));
        joueur.connection.send(new ClientboundSetTitleTextPacket(titre));
    }

    /** À la salle, un joueur en survie passe en aventure (on n'y casse rien) ; dehors, il retrouve son mode. */
    static void verifierMode(ServerPlayer joueur) {
        EntrainementData data = EntrainementData.get(joueur.server);
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
        dispatcher.register(Commands.literal("entrainement")
                .executes(ctx -> {
                    entrer(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("retour").executes(ctx -> {
                    sortir(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("envoyer").requires(Entrainement::hote)
                        .then(Commands.argument("joueurs", EntityArgument.players()).executes(ctx -> {
                            Collection<ServerPlayer> joueurs = EntityArgument.getPlayers(ctx, "joueurs");
                            joueurs.forEach(Entrainement::entrer);
                            ctx.getSource().sendSuccess(() -> Component.literal(joueurs.size() + " joueur(s) envoyé(s) à la salle d'entraînement."), true);
                            return joueurs.size();
                        })))
                .then(Commands.literal("reconstruire").requires(Entrainement::hote).executes(ctx -> {
                    ServerLevel salle = niveau(ctx.getSource().getServer());
                    if (salle == null) {
                        ctx.getSource().sendFailure(Component.literal("La dimension de la salle d'entraînement est introuvable."));
                        return 0;
                    }
                    construire(salle);
                    ctx.getSource().sendSuccess(() -> Component.literal("La salle d'entraînement est reconstruite."), true);
                    return 1;
                })));
    }

    /** L'hôte : un admin du serveur, ou le joueur qui a ouvert le monde en solo. */
    static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    // ------------------------------------------------------------------ l'ambiance, à chaque tick

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel l = niveau(server);
        if (l == null || l.players().isEmpty()) {
            return;
        }
        long t = server.getTickCount();
        boolean fonderie = false, pylones = false, tunnel = false;
        double[] col = EntrainementBuilder.colonne();
        for (ServerPlayer joueur : List.copyOf(l.players())) {
            // tombé hors de la salle : on le remet à l'arrivée
            if (joueur.getY() < EntrainementBuilder.SOL - 16) {
                Vec3 a = EntrainementBuilder.arrivee();
                joueur.teleportTo(l, a.x, a.y, a.z, EntrainementBuilder.ARRIVEE_YAW, 0F);
                joueur.fallDistance = 0;
            }
            int salle = EntrainementBuilder.salleDe(joueur.getX(), joueur.getZ());
            Integer avant = SALLES.put(joueur.getUUID(), salle);
            if (avant == null || avant != salle) {
                annoncer(joueur, salle);
            }
            switch (salle) {
                case 2 -> fonderie = true;
                case 3 -> joueur.setTicksFrozen(Math.min(120, joueur.getTicksFrozen() + 4)); // le givre au bord de l'écran, sans jamais geler
                case 7 -> pylones = true;
                default -> {
                }
            }
            // le courant d'air de la colonne vitrée : il porte vers le haut, sauf si l'on s'accroupit
            if (joueur.getX() >= col[0] && joueur.getX() < col[2] && joueur.getZ() >= col[1] && joueur.getZ() < col[3]) {
                tunnel = true;
                if (!joueur.isShiftKeyDown() && !joueur.getAbilities().flying) {
                    Vec3 v = joueur.getDeltaMovement();
                    double haut = joueur.getY() - EntrainementBuilder.SOL;
                    joueur.setDeltaMovement(v.x, Math.max(v.y, haut < 20 ? 0.42 : 0.06), v.z);
                    joueur.hurtMarked = true;
                }
                joueur.fallDistance = 0;
            }
            if (t % 40 == 0) {
                verifierMode(joueur);
            }
        }
        if (fonderie && t % 3 == 0) {
            List<BlockPos> rigole = EntrainementBuilder.rigole();
            BlockPos p = rigole.get(l.random.nextInt(rigole.size()));
            l.sendParticles(ParticleTypes.LAVA, p.getX() + 0.5, p.getY() + 1.05, p.getZ() + 0.5, 1, 0.2, 0, 0.2, 0);
            l.sendParticles(ParticleTypes.FLAME, p.getX() + 0.5, p.getY() + 1.2, p.getZ() + 0.5, 2, 0.3, 0.6, 0.3, 0.01);
        }
        if (tunnel && t % 2 == 0) {
            l.sendParticles(ParticleTypes.CLOUD, (col[0] + col[2]) / 2, EntrainementBuilder.SOL + 0.3, (col[1] + col[3]) / 2, 3, 1.1, 0.1, 1.1, 0.25);
        }
        if (pylones) {
            eclairs(l, t);
        } else if (finEclair >= 0) {
            lumierePylones(l, false);
            finEclair = -1;
        }
    }

    /** De temps en temps, un arc entre deux paratonnerres, et toute la salle s'éclaire un instant. */
    private static void eclairs(ServerLevel l, long t) {
        if (finEclair >= 0 && t >= finEclair) {
            lumierePylones(l, false);
            finEclair = -1;
        }
        if (t < prochainEclair) {
            return;
        }
        prochainEclair = t + 30 + l.random.nextInt(70);
        List<Vec3> hauts = EntrainementBuilder.pylones();
        Vec3 a = hauts.get(l.random.nextInt(hauts.size())), b = hauts.get(l.random.nextInt(hauts.size()));
        if (a == b) {
            return;
        }
        for (int k = 0; k <= 24; k++) {
            double f = k / 24.0;
            Vec3 p = a.lerp(b, f).add((l.random.nextDouble() - 0.5) * 0.6, -Math.sin(f * Math.PI) * 0.8 + (l.random.nextDouble() - 0.5) * 0.6,
                    (l.random.nextDouble() - 0.5) * 0.6);
            l.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.02);
        }
        Vec3 m = a.lerp(b, 0.5);
        l.playSound(null, m.x, m.y, m.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.AMBIENT, 0.5F, 1.7F);
        lumierePylones(l, true);
        finEclair = t + 3;
    }

    private static void lumierePylones(ServerLevel l, boolean eclair) {
        int niveau = eclair ? 15 : EntrainementBuilder.salle(7).lumiere();
        for (BlockPos plan : EntrainementBuilder.lumieres(7)) {
            BlockPos p = EntrainementBuilder.monde(plan);
            if (l.getBlockState(p).is(Blocks.LIGHT)) {
                l.setBlock(p, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, niveau), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** En entrant dans une salle : son nom, sa température et ce qu'on y ressent. */
    private static void annoncer(ServerPlayer joueur, int salle) {
        if (salle < 0) {
            joueur.displayClientMessage(Component.literal("L'allée").withStyle(ChatFormatting.GOLD), true);
            return;
        }
        EntrainementBuilder.Salle s = EntrainementBuilder.salle(salle);
        titre(joueur, Component.literal(s.nom()).withStyle(ChatFormatting.GOLD),
                Component.literal(s.temp() + " · ").withStyle(ChatFormatting.YELLOW).append(Component.literal(s.ressenti()).withStyle(ChatFormatting.GRAY)));
    }

    // ------------------------------------------------------------------ événements

    /** À la salle, la lave, le feu, le magma, le froid et les chutes ne font pas de mal (on s'entraîne, on ne meurt pas bêtement). */
    @SubscribeEvent
    public static void onBlessure(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player) || event.getEntity().level().dimension() != DIMENSION) {
            return;
        }
        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_FALL) || source.is(DamageTypeTags.IS_FREEZING)
                || source.is(DamageTypes.HOT_FLOOR) || source.is(DamageTypes.LAVA)) {
            event.setCanceled(true);
        }
    }

    /** Les pnj de la salle encaissent tout (on voit qu'ils sont touchés) sans jamais tomber ; /kill marche toujours. */
    @SubscribeEvent
    public static void encaisser(LivingDamageEvent.Pre event) {
        if (event.getEntity().getTags().contains(EntrainementBuilder.PNJ) && !event.getSource().is(DamageTypes.GENERIC_KILL)) {
            event.setNewDamage(0F);
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
            if (!dedans(joueur)) {
                SALLES.remove(joueur.getUUID());
            }
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SALLES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    /** Les pnj de la salle ne disparaissent jamais, même en paisible. */
    @SubscribeEvent
    public static void onDisparition(MobDespawnEvent event) {
        if (event.getEntity().getTags().contains(EntrainementBuilder.TAG)) {
            event.setResult(MobDespawnEvent.Result.DENY);
        }
    }

    /** Aucune créature n'apparaît d'elle-même à la salle (des mods en ajoutent à tous les biomes). */
    @SubscribeEvent
    public static void onApparition(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getLevel().getLevel().dimension() == DIMENSION
                && (event.getSpawnType() == MobSpawnType.NATURAL || event.getSpawnType() == MobSpawnType.CHUNK_GENERATION)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /** Un pnj d'une construction précédente (son chunk n'était pas chargé quand on a tout refait) est refusé. */
    @SubscribeEvent
    public static void onArrivee(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.getLevel().dimension() != DIMENSION || event.getLevel().getServer() == null) {
            return;
        }
        var tags = event.getEntity().getTags();
        if (tags.contains(EntrainementBuilder.TAG)
                && !tags.contains(EntrainementBuilder.tagConstruction(EntrainementData.get(event.getLevel().getServer()).construction()))) {
            event.setCanceled(true);
        }
    }

    private Entrainement() {
    }
}
