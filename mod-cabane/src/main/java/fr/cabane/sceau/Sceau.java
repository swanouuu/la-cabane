package fr.cabane.sceau;

import com.mojang.brigadier.CommandDispatcher;
import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
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
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.joml.Vector3f;

/**
 * La salle du sceau : une dimension vide (cabane:sceau) où le mod construit, à la première visite, la salle du croquis
 * validé : l'eau rouge au sol, la muraille de barreaux, et le Hachibi géant enchaîné derrière, tourné vers nous (voir
 * {@link SceauBuilder}). On y va par /sceau ; on en repart par le bouton rouge de l'arrivée (ou /sceau retour), pile là où
 * l'on était. On y est en aventure, on marche dans l'eau comme sur la terre ferme, et chaque pas fait des ronds dans l'eau.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Sceau {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("sceau"));

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cabane.MODID);
    /** Le bouton rouge qui ramène dans le vrai monde. */
    public static final DeferredBlock<BoutonSceau> BOUTON = BLOCKS.registerBlock("bouton_sceau", BoutonSceau::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1F, 3_600_000F).sound(SoundType.METAL).noOcclusion()
                    .lightLevel(s -> 7).noLootTable());

    /** Dans l'eau de la salle, on avance comme sur la terre ferme. */
    private static final ResourceLocation EAU = Cabane.id("sceau_eau");

    /** Un rond dans l'eau : son centre et le tick où il est né. */
    private record Onde(double x, double z, long debut) {
    }

    private static final List<Onde> ONDES = new ArrayList<>();
    private static final Map<UUID, Vec3> DERNIERE_POS = new HashMap<>();
    private static final Map<UUID, Long> DERNIERE_ONDE = new HashMap<>();
    /** La couleur des ronds : un rose pâle sur l'eau rouge. */
    private static final DustParticleOptions ROND = new DustParticleOptions(new Vector3f(1F, 0.86F, 0.82F), 1.0F);
    private static final int VIE_ONDE = 22;

    /** Envoyé par l'hôte : le temps que ses yeux se ferment (ClientSceau), puis on l'emmène, dans le noir. */
    public static final int ENDORMISSEMENT = 48;
    /** Les joueurs envoyés dont les yeux se ferment, et le tick où on les emmène. */
    private static final Map<UUID, Long> ENVOIS = new HashMap<>();

    /** Au serveur de dire au joueur : tes yeux se ferment (puis se rouvrent dans la salle). */
    public record Yeux() implements CustomPacketPayload {
        public static final Type<Yeux> TYPE = new Type<>(Cabane.id("sceau_yeux"));
        public static final StreamCodec<ByteBuf, Yeux> CODEC = StreamCodec.unit(new Yeux());

        @Override
        public Type<Yeux> type() {
            return TYPE;
        }
    }

    public static void register(IEventBus modBus) {
        CabaneItems.ITEMS.registerSimpleBlockItem(BOUTON);
        BLOCKS.register(modBus);
        modBus.addListener(Sceau::paquets);
    }

    private static void paquets(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Yeux.TYPE, Yeux.CODEC, (p, ctx) -> ctx.enqueueWork(fr.cabane.client.sceau.ClientSceau::yeux));
    }

    /** L'hôte envoie quelqu'un : ses yeux se ferment, et il se réveille dans la salle. */
    public static void envoyer(ServerPlayer joueur) {
        PacketDistributor.sendToPlayer(joueur, new Yeux());
        ENVOIS.put(joueur.getUUID(), (long) joueur.server.getTickCount() + ENDORMISSEMENT);
    }

    public static ServerLevel niveau(MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static boolean dedans(Player joueur) {
        return joueur.level().dimension() == DIMENSION;
    }

    static void construire(ServerLevel salle) {
        SceauData data = SceauData.get(salle.getServer());
        SceauBuilder.construire(salle, data.nouvelleConstruction());
        data.setVersionConstruite(SceauBuilder.VERSION);
    }

    // ------------------------------------------------------------------ entrer, sortir

    /** Emmène le joueur à la salle (construite d'abord s'il le faut), en retenant d'où il part. */
    public static void entrer(ServerPlayer joueur) {
        MinecraftServer server = joueur.server;
        ServerLevel salle = niveau(server);
        if (salle == null) {
            joueur.sendSystemMessage(Component.literal("La salle du sceau n'existe pas dans ce monde.").withStyle(ChatFormatting.RED));
            return;
        }
        SceauData data = SceauData.get(server);
        if (data.versionConstruite() != SceauBuilder.VERSION) {
            joueur.displayClientMessage(Component.literal("La salle du sceau se prépare…").withStyle(ChatFormatting.DARK_RED), true);
            construire(salle);
        }
        Vec3 a = SceauBuilder.arrivee();
        if (dedans(joueur)) {
            joueur.teleportTo(salle, a.x, a.y, a.z, SceauBuilder.ARRIVEE_YAW, 0F);
            return;
        }
        if (joueur.isSleeping()) {
            joueur.stopSleepInBed(true, true);
        }
        ServerLevel depart = joueur.serverLevel();
        data.setRetour(joueur.getUUID(), new SceauData.Retour(depart.dimension().location(), joueur.getX(), joueur.getY(), joueur.getZ(),
                joueur.getYRot(), joueur.getXRot()));
        depart.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 0.8F);
        depart.sendParticles(ParticleTypes.CLOUD, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 30, 0.3, 0.8, 0.3, 0.02);
        joueur.teleportTo(salle, a.x, a.y, a.z, SceauBuilder.ARRIVEE_YAW, 0F);
        verifierMode(joueur);
        joueur.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false));
        salle.playSound(null, a.x, a.y, a.z, SoundEvents.AMBIENT_UNDERWATER_ENTER, SoundSource.AMBIENT, 1F, 0.6F);
        joueur.connection.send(new ClientboundSetTitlesAnimationPacket(15, 60, 25));
        joueur.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Derrière les barreaux, la bête te regarde.").withStyle(ChatFormatting.GRAY)));
        joueur.connection.send(new ClientboundSetTitleTextPacket(Component.literal("LE SCEAU").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
        joueur.sendSystemMessage(Component.literal("Pour repartir : le bouton rouge, juste à côté de toi. Il te ramène pile là où tu étais.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

    /** Ramène le joueur exactement là d'où il est venu (ou, à défaut, à son point de réapparition). */
    public static void sortir(ServerPlayer joueur) {
        if (!dedans(joueur)) {
            joueur.displayClientMessage(Component.literal("Tu n'es pas dans la salle du sceau.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        MinecraftServer server = joueur.server;
        SceauData data = SceauData.get(server);
        SceauData.Retour retour = data.retour(joueur.getUUID());
        ServerLevel cible = retour == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, retour.dimension()));
        ServerLevel ici = joueur.serverLevel();
        ici.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.AMBIENT_UNDERWATER_EXIT, SoundSource.PLAYERS, 1F, 0.8F);
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
        oublier(joueur);
        verifierMode(joueur);
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 1.2F);
    }

    /** À la salle, un joueur en survie passe en aventure et marche dans l'eau sans ralentir ; dehors, tout redevient normal. */
    static void verifierMode(ServerPlayer joueur) {
        SceauData data = SceauData.get(joueur.server);
        GameType avant = data.mode(joueur.getUUID());
        AttributeInstance eau = joueur.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (dedans(joueur)) {
            if (joueur.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                data.setMode(joueur.getUUID(), GameType.SURVIVAL);
                joueur.setGameMode(GameType.ADVENTURE);
            }
            if (eau != null && !eau.hasModifier(EAU)) {
                eau.addTransientModifier(new AttributeModifier(EAU, 1.0, AttributeModifier.Operation.ADD_VALUE));
            }
        } else {
            if (eau != null) {
                eau.removeModifier(EAU);
            }
            if (avant != null) {
                data.setMode(joueur.getUUID(), null);
                if (joueur.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
                    joueur.setGameMode(avant);
                }
            }
        }
    }

    private static void oublier(Player joueur) {
        DERNIERE_POS.remove(joueur.getUUID());
        DERNIERE_ONDE.remove(joueur.getUUID());
    }

    // ------------------------------------------------------------------ commandes

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("sceau")
                .executes(ctx -> {
                    entrer(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("retour").executes(ctx -> {
                    sortir(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("envoyer").requires(Sceau::hote)
                        .then(Commands.argument("joueurs", EntityArgument.players()).executes(ctx -> {
                            Collection<ServerPlayer> joueurs = EntityArgument.getPlayers(ctx, "joueurs");
                            joueurs.forEach(Sceau::envoyer);
                            ctx.getSource().sendSuccess(() -> Component.literal(joueurs.size() + " joueur(s) envoyé(s) dans la salle du sceau."), true);
                            return joueurs.size();
                        })))
                .then(Commands.literal("reconstruire").requires(Sceau::hote).executes(ctx -> {
                    ServerLevel salle = niveau(ctx.getSource().getServer());
                    if (salle == null) {
                        ctx.getSource().sendFailure(Component.literal("La dimension de la salle du sceau est introuvable."));
                        return 0;
                    }
                    construire(salle);
                    ctx.getSource().sendSuccess(() -> Component.literal("La salle du sceau est reconstruite."), true);
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

    // ------------------------------------------------------------------ les ronds dans l'eau

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // les joueurs envoyés dont les yeux viennent de se fermer : on les emmène, dans le noir
        if (!ENVOIS.isEmpty()) {
            long maintenant = server.getTickCount();
            ENVOIS.entrySet().removeIf(e -> {
                if (e.getValue() > maintenant) {
                    return false;
                }
                ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
                if (joueur != null) {
                    entrer(joueur);
                }
                return true;
            });
        }
        ServerLevel l = niveau(server);
        if (l == null) {
            return;
        }
        if (l.players().isEmpty()) {
            ONDES.clear();
            return;
        }
        long t = server.getTickCount();
        for (ServerPlayer joueur : List.copyOf(l.players())) {
            // sorti de la salle (en volant, par une commande) : on le remet à l'arrivée
            if (!SceauBuilder.dansLaSalle(joueur.getX(), joueur.getY(), joueur.getZ())) {
                Vec3 a = SceauBuilder.arrivee();
                joueur.teleportTo(l, a.x, a.y, a.z, SceauBuilder.ARRIVEE_YAW, 0F);
                joueur.fallDistance = 0;
            }
            if (t % 40 == 0) {
                verifierMode(joueur);
            }
            // un rond à chaque pas, et de temps en temps même immobile ; seulement les pieds dans l'eau
            Vec3 ici = joueur.position();
            Vec3 avant = DERNIERE_POS.put(joueur.getUUID(), ici);
            if (joueur.getY() > SceauBuilder.SOL + 1.2 || joueur.isSpectator()) {
                continue;
            }
            boolean bouge = avant != null && ici.distanceToSqr(avant) > 0.0025;
            long derniere = DERNIERE_ONDE.getOrDefault(joueur.getUUID(), -1000L);
            if (t - derniere >= (bouge ? 7 : 30)) {
                DERNIERE_ONDE.put(joueur.getUUID(), t);
                ONDES.add(new Onde(ici.x, ici.z, t));
            }
        }
        if (t % 2 != 0) {
            return;
        }
        double y = SceauBuilder.surface();
        for (Iterator<Onde> it = ONDES.iterator(); it.hasNext(); ) {
            Onde o = it.next();
            long age = t - o.debut();
            if (age > VIE_ONDE) {
                it.remove();
                continue;
            }
            double r = 0.35 + age * 0.12;
            int n = 8 + (int) (r * 9);
            for (int k = 0; k < n; k++) {
                double a = Math.PI * 2 * k / n;
                l.sendParticles(ROND, o.x() + Math.cos(a) * r, y, o.z() + Math.sin(a) * r, 1, 0, 0, 0, 0);
            }
        }
    }

    // ------------------------------------------------------------------ événements

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
            if (!dedans(joueur)) {
                oublier(joueur);
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
        oublier(event.getEntity());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    /** Aucune créature n'apparaît d'elle-même dans la salle (des mods en ajoutent à tous les biomes). */
    @SubscribeEvent
    public static void onApparition(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getLevel().getLevel().dimension() == DIMENSION
                && (event.getSpawnType() == MobSpawnType.NATURAL || event.getSpawnType() == MobSpawnType.CHUNK_GENERATION)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /** La bête ou le papier d'une construction précédente (leur chunk n'était pas chargé quand on a tout refait) sont refusés. */
    @SubscribeEvent
    public static void onArrivee(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.getLevel().dimension() != DIMENSION || event.getLevel().getServer() == null) {
            return;
        }
        var tags = event.getEntity().getTags();
        if (tags.contains(SceauBuilder.TAG) &&!tags.contains(SceauBuilder.tagConstruction(SceauData.get(event.getLevel().getServer()).construction()))) {
            event.setCanceled(true);
        }
    }

    private Sceau() {
    }
}
